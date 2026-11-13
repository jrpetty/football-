// DayZ guns: finding a gun by any name, what it looks like, its round and
// magazines, its range (bullet drop over the zero), where it spawns on each
// map, comparisons, the best of a kind, the gun in hand spoken offline, and
// the data itself.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { answerGunQuestion, findGun, gunLine, guns, gunsIn, isGunQuestion } from '../src/core/dayz/guns.ts'
import { DAYZ_MAPS } from '../src/core/dayz/places.ts'
import { classify, answerLocally } from '../src/core/qa/ask.ts'
import { validateProfile, BANNED_CLAIMS } from '../src/core/profile/schema.ts'
import { Builder } from '../src/sim/visionReplay.ts'
import type { Utterance } from '../src/core/types.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { engineFor, settings } from './helpers.ts'

const dayz = builtInProfile('dayz')
const ask = (q: string, context: Record<string, string> = { map: 'Chernarus' }) => answerGunQuestion(q, dayz, context) ?? ''

test('a gun by its in-game name, the real gun, a nickname or the game class', () => {
  const cases: [string, string][] = [
    ['KA-M', 'KA-M'],
    ['akm', 'KA-M'],
    ['the AK', 'KA-M'],
    ['m4', 'M4-A1'],
    ['M4A1', 'M4-A1'],
    ['mosin', 'Mosin 91/30'],
    ['sawed off mosin', 'Sawed-off Mosin 91/30'],
    ['svd', 'VSD'],
    ['sks', 'SK 59/66'],
    ['mp5', 'SG5-K'],
    ['glock', 'Mlock-91'],
    ['double barrel', 'BK-43'],
    ['winchester', 'M70 Tundra'],
    ['deagle', 'Deagle'],
  ]
  for (const [q, name] of cases) assert.equal(findGun(q, dayz)?.name, name, q)
  assert.deepEqual(gunsIn('mosin vs svd', dayz).map((g) => g.name), ['Mosin 91/30', 'VSD'])
  // No town, village or base is taken for a gun.
  for (const m of DAYZ_MAPS) for (const p of m.places) assert.equal(findGun(p.name, dayz), null, p.name)
})

test('what it looks like, its round, magazines and attachments', () => {
  assert.match(ask('what does the KA-M look like'), /^KA-M \(assault rifle, like the AKM\): Classic AK shape: curved 30-round magazine/)
  assert.match(ask('what ammo does the sks use'), /^SK 59\/66 fires 7\.62x39mm \(55 damage, 66 shock a hit\)\. It holds 10\. The same round fits the KA-M, CR-527, BK-18\./)
  assert.match(ask('what attachments go on the akm'), /30-round, 75-round drum magazines; sights and scopes: PSO-1, .*Kobra/)
  assert.match(ask('what guns use 7.62x39'), /^7\.62x39mm fits: KA-M \(assault rifle\), SK 59\/66/)
  assert.match(ask('what uses 12 gauge'), /BK-133 \(shotgun\)/)
})

test('range: the hold over the zero, the speed kept, how far the sights go', () => {
  const mosin = ask('range of the mosin')
  assert.match(mosin, /^Mosin 91\/30: best at long range\. Zeroed at 100 m, aim about \d+ cm high at 200 m, \d+ cm high at 300 m, 1\.\d m high at 400 m/)
  assert.match(mosin, /keeps \d+% of that at 300 m; sights zero out to 1300 m/)
  // Faster, flatter rounds drop less: the Mosin's 7.62x54R against a 9x19 SMG at the same zero.
  const hold = (name: string) => guns(dayz).find((g) => g.name === name)!.stats!.hold300 as number
  assert.ok(hold('Mosin 91/30') < 1 && hold('SG5-K') > 4, `${hold('Mosin 91/30')} ${hold('SG5-K')}`)
  assert.match(ask('range of the bk-133'), /shotgun, sighted for about 25 m/)
})

test('where it spawns, on the map being played or the one named', () => {
  assert.match(ask('where can I find an M4'), /^M4-A1 spawns: Chernarus, contaminated zones, about 1 on the map\.$/)
  assert.match(ask('where can i find an m4 on livonia'), /^M4-A1 spawns: Livonia,/)
  assert.match(ask('where does the mosin spawn', {}), /Chernarus, .*Livonia, .*Sakhal, /)
})

test('comparisons and the best of a kind', () => {
  assert.match(ask('mosin vs svd'), /Same round; the VSD fires faster; the Mosin 91\/30 is far easier to find\./)
  assert.match(ask('compare the m4 and the ka-101'), /the M4-A1 fires faster; the KA-101 is far easier to find/)
  assert.match(ask('best sniper'), /^Hardest-hitting long range on Chernarus: .*Mosin 91\/30/)
  assert.match(ask('whats the best shotgun'), /110 damage a slug/)
})

test('through the coach: gun questions are answered on this computer, before places', () => {
  const report = engineFor(dayz, settings({ profileId: 'dayz', profileModeId: 'offline' })).report()
  for (const q of ['where can I find an M4', 'mosin range', 'what does the vss look like', 'best pistol']) {
    const intent = classify(q, dayz)
    assert.equal(intent.kind, 'gun', q)
    const a = answerLocally(intent, dayz, report, [])
    assert.ok(a && a.length > 20, q)
    assert.doesNotMatch(a!, BANNED_CLAIMS, q)
  }
  assert.equal(classify('where is typobo', dayz).kind, 'place')
  assert.ok(!isGunQuestion('how do I get from cherno to nwaf', dayz))
  // Gun mechanics without a gun named: from the pack's reference.
  for (const [q, re] of [['why does my gun keep jamming', /jams or misfires more/], ['how do I zero my scope', /Page Up and Page Down/]] as const) {
    const intent = classify(q, dayz)
    assert.equal(intent.kind, 'survival', q)
    assert.match(answerLocally(intent, dayz, report, [])!, re)
  }
  assert.match(ask('what gun is this'), /not read a gun in your hands yet/)
  assert.match(ask('what gun is this', { weaponInHands: 'KA-M' }), /^KA-M, assault rifle/)
})

test('the data: every gun valid, with a look, a calibre and somewhere to find it', () => {
  assert.ok(validateProfile(dayz).ok)
  const list = guns(dayz)
  assert.ok(list.length >= 55, String(list.length))
  for (const g of list) {
    assert.ok(g.look && g.look.length > 20, `${g.name}: look`)
    assert.ok(g.stats?.calibre, `${g.name}: calibre`)
    assert.doesNotMatch(g.summary, BANNED_CLAIMS, g.name)
    if (!/^Sawed-off/.test(g.name) && g.name !== 'Signal Pistol') assert.ok(['whereChernarus', 'whereLivonia', 'whereSakhal'].some((k) => /about [1-9]/.test(String(g.stats?.[k]))), `${g.name}: spawns nowhere`)
  }
})

test('offline with commentary on: the gun picked up is named with its round and range', () => {
  const s = settings({ profileId: 'dayz', profileModeId: 'offline', mode: 'training', commentary: true })
  const engine = engineFor(dayz, s)
  const b = new Builder('dayz', () => 0.5)
  for (let i = 0; i < 6; i++) b.batch(i * 4000, { phase: 'round', weaponInHands: 'Mosin 91/30', water: 'ok' })
  const said: Utterance[] = []
  let now = 0
  for (const batch of b.batches) said.push(...engine.ingestObservations(batch.observations, (now = Math.max(now, batch.arrivesAt))).utterances)
  const texts = said.map((u) => u.text)
  assert.ok(texts.includes(gunLine('Mosin 91/30', dayz)!), texts.join(' | '))
  assert.match(gunLine('Mosin 91/30', dayz)!, /^Mosin 91\/30: 7\.62x54mmR, holds 5; \d+ cm high at 300 m\.$/)
})
