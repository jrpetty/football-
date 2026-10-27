// Electron-side logic that does not need Electron: consent, storage (v2,
// encrypted at rest), IPC channel parity, the model archive reader, and local
// speech recognition.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { existsSync } from 'node:fs'
import { mkdtemp, mkdir, readFile, readdir, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import type { Cipher } from '../electron/cloud/keystore.ts'
import { CaptureConsent, isSourceId } from '../electron/consent.ts'
import { sanitizeCloud, Storage } from '../electron/storage.ts'
import { untar } from '../electron/stt/tar.ts'
import { CHANNELS } from '../shared/channels.ts'
import { DEFAULT_CLOUD } from '../shared/cloud.ts'
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

test('settings from the renderer are sanitised field by field; privacy options default off', () => {
  const s = sanitizeSettings({
    mode: 'aimbot', tone: 'gentle', cooldownSec: 1, voice: { rate: 9, volume: -1 }, profileId: '../etc',
    context: { 'ea-fc': { side: 'away', '../x': 'y', huge: 'x'.repeat(500) }, 'BAD ID': { a: 1 } },
  })
  assert.equal(s.mode, DEFAULT_SETTINGS.mode)
  assert.equal(s.tone, 'gentle')
  assert.equal(s.cooldownSec, 5)
  assert.equal(s.voice.rate, 2)
  assert.equal(s.voice.volume, 0)
  assert.equal(s.profileId, DEFAULT_SETTINGS.profileId)
  assert.deepEqual(s.context, { 'ea-fc': { side: 'away' } })
  for (const off of ['saveTendencies', 'voiceInput', 'tournament'] as const) assert.equal(DEFAULT_SETTINGS[off], false)
  assert.equal(DEFAULT_SETTINGS.mode, 'competitive-safe')
  assert.deepEqual(sanitizeCloud({ enabled: 'yes', visionModel: 'gpt-x', sessionCapUsd: 1e9 }, DEFAULT_CLOUD), { ...DEFAULT_CLOUD, sessionCapUsd: 100 })
})

const cipher: Cipher = {
  available: true,
  encrypt: (t) => Buffer.from(Buffer.from(t).toString('base64').split('').reverse().join('')),
  decrypt: (b) => Buffer.from(b.toString().split('').reverse().join(''), 'base64').toString(),
}

async function store(withCipher: Cipher | null = cipher) {
  const s = new Storage(await mkdtemp(join(tmpdir(), 'sightline-')), withCipher)
  await s.init()
  return s
}

const match = (id: string) => ({
  schema: 1 as const, id, gameProfileId: 'ea-fc', profileVersion: '1.0.0', modeId: null, endedAt: '2026-10-27T00:00:00Z', durationSec: 600,
  result: 'loss' as const, score: { player: 1, opponent: 2 }, rounds: { won: 0, lost: 0, drawn: 0, unread: 0 },
  stats: { passAccuracyPct: 80 }, mistakes: {}, zones: { conceded: { 'box-left': 2 } }, endedManually: false,
})

test('long-term data is saved only after opting in, encrypted at rest, and exportable and deletable', async () => {
  const s = await store()
  const snap = new PatternMemory(arena).snapshot(new Date())
  assert.equal(await s.saveTendencies(snap), false, 'refused while opted out')
  assert.equal(await s.saveMatch(match('a')), false)
  assert.equal(await s.saveNotes('ea-fc', { goals: 'stop conceding cutbacks', notes: '', updatedAt: '' }), false)

  await s.saveSettings({ ...DEFAULT_SETTINGS, saveTendencies: true })
  assert.equal(await s.saveTendencies(snap), true)
  assert.equal(await s.saveMatch(match('a')), true)
  assert.equal(await s.saveNotes('ea-fc', { goals: 'stop conceding cutbacks', notes: '4-2-3-1', updatedAt: '' }), true)

  // On disk: no readable player data.
  for (const [dir, file, secret] of [['history', 'ea-fc.json', 'box-left'], ['notes', 'ea-fc.json', 'cutbacks']] as const) {
    const raw = await readFile(join(s.root, dir, file), 'utf8')
    assert.ok(raw.includes('"enc": "safeStorage"') && !raw.includes(secret), `${dir} is encrypted`)
  }
  assert.deepEqual((await s.loadMatches('ea-fc')).map((m) => m.id), ['a'])
  assert.equal((await s.loadNotes('ea-fc'))?.goals, 'stop conceding cutbacks')
  assert.ok((await s.loadTendencies(arena.id))?.segments)

  const exported = JSON.parse(await s.exportPlayerData())
  assert.equal(exported.notes['ea-fc'].notes, '4-2-3-1')
  assert.equal(exported.encryptedAtRest, true)

  await s.forgetPlayerData(null)
  assert.equal(await s.loadTendencies(arena.id), null)
  assert.deepEqual(await s.loadMatches('ea-fc'), [])
  assert.equal(await s.loadNotes('ea-fc'), null)
})

test('pattern verdicts and deletion persist; v1 snapshots migrate', async () => {
  const s = await store()
  await s.saveSettings({ ...DEFAULT_SETTINGS, saveTendencies: true })
  const v1 = { schemaVersion: 1, profileId: arena.id, profileVersion: arena.version, updatedAt: '2026-10-01T00:00:00Z', sessions: 3, episodes: { 'low-health': { none: { n: 6, negative: 4, positive: 0 } } } }
  assert.equal(await s.saveTendencies(v1), true, 'a v1 snapshot is migrated on save')
  const loaded = await s.loadTendencies(arena.id)
  assert.equal(loaded?.schemaVersion, 2)
  assert.equal(loaded?.segments.unspecified['low-health'].none.n, 6)
  assert.equal((await s.setPatternVerdict(arena.id, 'low-health', 'disputed'))?.verdicts['low-health'].verdict, 'disputed')
  const deleted = await s.deletePattern(arena.id, 'low-health')
  assert.equal(deleted?.segments.unspecified['low-health'], undefined)
  assert.equal(deleted?.verdicts['low-health'], undefined)
})

test('storage rejects traversal ids and malformed data, and keeps a bounded match history', async () => {
  const s = await store(null)
  await s.saveSettings({ ...DEFAULT_SETTINGS, saveTendencies: true })
  assert.equal(await s.loadTendencies('../settings'), null)
  assert.equal(await s.saveMatch({ ...match('x'), gameProfileId: '../../x' }), false)
  assert.equal(await s.saveMatch({ ...match('y'), stats: { a: 'NaN' } }), false)
  await writeFile(join(s.root, 'settings.json'), '{not json')
  assert.deepEqual(await s.loadSettings(), DEFAULT_SETTINGS)
  await s.saveSettings({ ...DEFAULT_SETTINGS, saveTendencies: true })
  for (let i = 0; i < 205; i++) await s.saveMatch(match(`m${i}`))
  assert.equal((await s.loadMatches('ea-fc')).length, 200)
  assert.equal(s.encrypted, false, 'reported honestly when the OS cannot encrypt')
})

test('user packs are read as raw JSON with size limits', async () => {
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

test('the tar reader extracts regular files and refuses paths outside the target', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'tar-'))
  await mkdir(join(dir, 'pkg'))
  await writeFile(join(dir, 'pkg', 'a.txt'), 'hello')
  await writeFile(join(dir, 'pkg', 'tokens.txt'), 'x'.repeat(1500))
  execFileSync('tar', ['-cf', join(dir, 'out.tar'), '-C', dir, 'pkg'])
  const entries = [...untar(await readFile(join(dir, 'out.tar')))]
  assert.deepEqual(entries.map((e) => [e.name, e.data.length]).sort(), [['pkg/a.txt', 5], ['pkg/tokens.txt', 1500]])

  const evil = Buffer.alloc(1024)
  evil.write('../escape.txt', 0)
  evil.write('0000000', 124)
  evil.write('0', 156)
  assert.throws(() => [...untar(evil)], /Unsafe path/)
})

// Real local speech recognition. Needs the model archive (≈108 MB), so it runs
// when SIGHTLINE_STT_ARCHIVE points at a downloaded copy.
const archive = process.env.SIGHTLINE_STT_ARCHIVE
test('local speech recognition transcribes a test recording', { skip: !archive || !existsSync(archive) ? 'set SIGHTLINE_STT_ARCHIVE to the model archive to run' : false }, async () => {
  const { installFromFile, isInstalled } = await import('../electron/stt/model.ts')
  const { RecognizerThread } = await import('../electron/stt/recognizer.ts')
  const root = await mkdtemp(join(tmpdir(), 'stt-'))
  await installFromFile(archive!, root)
  assert.equal(await isInstalled(root), true)
  assert.deepEqual((await readdir(join(root, 'models', 'moonshine-tiny-en-int8'))).sort(), ['LICENSE', 'cached_decode.int8.onnx', 'encode.int8.onnx', 'preprocess.onnx', 'tokens.txt', 'uncached_decode.int8.onnx'])

  // A test recording shipped inside the archive (16 kHz mono PCM WAV).
  const tar = execFileSync('tar', ['-xjOf', archive!, 'sherpa-onnx-moonshine-tiny-en-int8/test_wavs/0.wav'], { maxBuffer: 10_000_000 })
  const pcm = new Int16Array(tar.buffer, tar.byteOffset + 44, (tar.length - 44) / 2)
  const samples = Float32Array.from(pcm, (v) => v / 32768)
  const rec = new RecognizerThread(root)
  try {
    const text = await rec.transcribe(samples)
    assert.match(text.toLowerCase(), /after early nightfall, the yellow lamps would light up/)
  } finally {
    rec.close()
  }
})
