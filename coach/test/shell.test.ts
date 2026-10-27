// Electron-side logic that does not need Electron: consent, storage, channels.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile, readdir, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { CaptureConsent, isSourceId } from '../electron/consent.ts'
import { Storage } from '../electron/storage.ts'
import { CHANNELS } from '../shared/channels.ts'
import { PatternMemory } from '../src/core/patterns.ts'
import { sanitizeSettings, DEFAULT_SETTINGS } from '../src/core/settings.ts'
import { arena } from './helpers.ts'

test('consent is explicit, single-use and short-lived', () => {
  let now = 0
  const c = new CaptureConsent(15_000, () => now)
  assert.equal(c.take(), null, 'no choice, no capture')
  c.choose('window:42:0')
  assert.equal(c.take(), 'window:42:0')
  assert.equal(c.take(), null, 'single use')
  c.choose('screen:0:0')
  now = 16_000
  assert.equal(c.take(), null, 'expired')
  assert.ok(isSourceId('window:123:0') && isSourceId('screen:1:0'))
  assert.ok(!isSourceId('window:../../x') && !isSourceId(42))
})

test('settings from the renderer are sanitised field by field', () => {
  const s = sanitizeSettings({ mode: 'aimbot', tone: 'gentle', cooldownSec: 1, voice: { rate: 9, volume: -1 }, profileId: '../etc' })
  assert.equal(s.mode, DEFAULT_SETTINGS.mode)
  assert.equal(s.tone, 'gentle')
  assert.equal(s.cooldownSec, 5)
  assert.equal(s.voice.rate, 2)
  assert.equal(s.voice.volume, 0)
  assert.equal(s.profileId, DEFAULT_SETTINGS.profileId)
  assert.equal(DEFAULT_SETTINGS.saveTendencies, false, 'long-term memory is opt-in')
  assert.equal(DEFAULT_SETTINGS.mode, 'competitive-safe')
})

async function store() {
  const s = new Storage(await mkdtemp(join(tmpdir(), 'sightline-')))
  await s.init()
  return s
}

test('tendencies are only saved after opting in, and can be forgotten', async () => {
  const s = await store()
  const snap = new PatternMemory(arena).snapshot(new Date())
  assert.equal(await s.saveTendencies(snap), false, 'refused while opted out')
  await s.saveSettings({ ...DEFAULT_SETTINGS, saveTendencies: true })
  assert.equal(await s.saveTendencies(snap), true)
  assert.deepEqual(await s.loadTendencies(arena.id), snap)
  await s.forgetTendencies(null)
  assert.equal(await s.loadTendencies(arena.id), null)
})

test('storage rejects traversal ids and malformed data', async () => {
  const s = await store()
  await s.saveSettings({ ...DEFAULT_SETTINGS, saveTendencies: true })
  assert.equal(await s.loadTendencies('../settings'), null)
  assert.equal(await s.saveTendencies({ schemaVersion: 1, profileId: '../../x', profileVersion: '1.0.0', updatedAt: '', sessions: 1, episodes: {} }), false)
  await writeFile(join(s.root, 'settings.json'), '{not json')
  assert.deepEqual(await s.loadSettings(), DEFAULT_SETTINGS)
  // Only settings.json and the two folders exist: nothing else is ever written.
  assert.deepEqual((await readdir(s.root)).sort(), ['profiles', 'settings.json', 'tendencies'])
})

test('user profiles are read as raw JSON with size limits', async () => {
  const s = await store()
  await writeFile(join(s.profilesDir, 'a.json'), JSON.stringify({ id: 'a' }))
  await writeFile(join(s.profilesDir, 'big.json'), JSON.stringify({ pad: 'x'.repeat(600 * 1024) }))
  await writeFile(join(s.profilesDir, 'notes.txt'), 'ignored')
  assert.deepEqual(await s.listUserProfiles(), [{ id: 'a' }])
})

test('the preload uses exactly the shared channel names', async () => {
  const preload = await readFile(new URL('../electron/preload.cts', import.meta.url), 'utf8')
  for (const ch of Object.values(CHANNELS)) assert.ok(preload.includes(`'${ch}'`), `preload is missing ${ch}`)
  const used = [...preload.matchAll(/'(sightline:[a-z-]+)'/g)].map((m) => m[1])
  for (const ch of used) assert.ok((Object.values(CHANNELS) as string[]).includes(ch), `unknown channel ${ch}`)
})
