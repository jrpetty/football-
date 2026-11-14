// DayZ: finding places by any spelling (road-sign look-alikes included), where
// they are and what is near them, the way between two, the map data itself,
// the answers, the policy, and the coach reading a road sign.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { DAYZ_MAPS, describePlace, findPlace, gridRef, looksLike, placesIn, routeLine, transliterate } from '../src/core/dayz/places.ts'
import { answerPlaceQuestion, isPlaceQuestion, placeAnswer, signLine } from '../src/core/dayz/ask.ts'
import { validateRegistry } from '../src/core/policy/registry.ts'
import { classify, answerLocally } from '../src/core/qa/ask.ts'
import { validateProfile, BANNED_CLAIMS } from '../src/core/profile/schema.ts'
import { Builder } from '../src/sim/visionReplay.ts'
import type { Utterance } from '../src/core/types.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { engineFor, policyFor, settings } from './helpers.ts'

const dayz = builtInProfile('dayz')

test('a place by its name, its sign, the transliteration, what the sign looks like, or a nickname', () => {
  const cases: [string, string, string][] = [
    ['Turovo', 'Turovo', 'name'],
    ['Турово', 'Turovo', 'sign'],
    ['Typobo', 'Turovo', 'sign look-alike'],
    ['Kpachoctab', 'Krasnostav', 'sign look-alike'],
    ['cherno', 'Chernogorsk', 'nickname'],
    ['NWAF', 'Vybor Air Base', 'nickname'],
    ['Berezno', 'Berezino', 'close spelling'],
    ['Brena', 'Brena', 'name'],
  ]
  for (const [q, name, via] of cases) {
    const m = findPlace(q, 'chernarus')
    assert.equal(m?.place.name, name, q)
    assert.equal(m?.via, via, q)
  }
  assert.equal(findPlace('xyzzyplugh'), null)
  assert.equal(looksLike('Турово'), 'typobo')
  assert.equal(transliterate('Красностав'), 'krasnostav')
})

test('where a place is: region, grid, nearest towns, what is in it, water and medical', () => {
  const t = describePlace(findPlace('Typobo')!)
  assert.match(t, /^Turovo \(the sign reads Турово\) is a village in the far north-east of Chernarus, grid 135 012/)
  assert.match(t, /Nearest: Svetloyarsk 1\.0 km south-east/)
  assert.match(t, /In it: .*industrial buildings/)
  assert.match(t, /Nearest water pump \d+ m/)
  assert.match(t, /On the sign it looks like "Typobo"/)
  assert.doesNotMatch(t, BANNED_CLAIMS)
  const tisy = describePlace(findPlace('Tisy Military Base')!)
  assert.match(tisy, /military base in the far north-west of Chernarus.*\d+ military buildings.*loot tier 4/)
  assert.equal(gridRef(DAYZ_MAPS[0], 13550, 14110), '135 012')
})

test('the way between two places: distance, direction and the towns on the way', () => {
  const [a, b] = placesIn('how do I get from cherno to nwaf', 'chernarus')
  assert.equal(a.place.name, 'Chernogorsk')
  assert.equal(b.place.name, 'Vybor Air Base')
  assert.match(routeLine(a, b), /^Vybor Air Base is 8\.2 km north of Chernogorsk in a straight line/)
})

test('the map data: every place inside its map, settlements on real buildings, the official water pumps', () => {
  const counts = Object.fromEntries(DAYZ_MAPS.map((m) => [m.id, m.places.length]))
  assert.ok(counts.chernarus >= 90 && counts.livonia >= 30 && counts.sakhal >= 50, JSON.stringify(counts))
  for (const m of DAYZ_MAPS) {
    for (const p of m.places) assert.ok(p.x >= 0 && p.y >= 0 && p.x <= m.worldSize && p.y <= m.worldSize, `${m.id}: ${p.name} at ${p.x}/${p.y}`)
    const names = m.places.map((p) => p.name)
    assert.equal(new Set(names).size, names.length, `${m.id}: a name twice`)
  }
  assert.equal(DAYZ_MAPS[0].world.water.length, 79, 'Chernarus water pumps in the game files')
  assert.equal(DAYZ_MAPS[1].world.water.length, 35, 'Livonia water pumps')
  // Every city, town and village sits among the game's own buildings.
  for (const m of DAYZ_MAPS) for (const p of m.places.filter((x) => ['city', 'town', 'village'].includes(x.kind))) assert.ok(m.world.near[p.name], `${m.id}: no buildings at ${p.name}`)
})

test('answers: where is, the way, the nearest water, where am I (from the last sign)', () => {
  assert.ok(isPlaceQuestion('where is typobo'))
  assert.ok(isPlaceQuestion('Typobo?'))
  assert.ok(!isPlaceQuestion('how do I treat cholera'))
  assert.match(answerPlaceQuestion('where is typobo', { map: 'Chernarus' })!, /^Turovo \(the sign reads Турово\)/)
  assert.match(answerPlaceQuestion('how far from elektro to cherno', {})!, /^Chernogorsk is 3\.\d km west of Elektrozavodsk/)
  assert.match(answerPlaceQuestion('nearest water to turovo', {})!, /^From Turovo: nearest water pump \d+ m [a-z-]+\.$/)
  assert.match(answerPlaceQuestion('where am i', { roadSign: 'Турово' })!, /^The last sign I read was Турово: Turovo/)
  assert.match(answerPlaceQuestion('where am i', {})!, /not read a road sign yet/)
  assert.match(answerPlaceQuestion('nearest hospital', {})!, /^Nearest to where\?/)
  // Through the coach's classifier and local answers.
  const intent = classify('where is typobo', dayz)
  assert.equal(intent.kind, 'place')
  const report = engineFor(dayz, settings({ profileId: 'dayz', profileModeId: 'offline' })).report()
  assert.match(answerLocally(intent, dayz, report, [])!, /^Turovo/)
})

test('the pack validates; live tips offline, after the life on public servers', () => {
  assert.ok(validateProfile(dayz).ok)
  assert.ok(policyFor(dayz, 'offline').scope.has('live'))
  for (const mode of ['official', 'community']) {
    const p = policyFor(dayz, mode)
    assert.ok(!p.scope.has('live'), mode)
    assert.ok(p.scope.has('post_match'), mode)
  }
})

test('offline with commentary on: a road sign read twice becomes the place, and bleeding gets its tip', () => {
  const s = settings({ profileId: 'dayz', profileModeId: 'offline', mode: 'training', commentary: true, context: { dayz: { map: 'Chernarus' } } })
  const engine = engineFor(dayz, s, { context: { map: 'Chernarus' } })
  const b = new Builder('dayz', () => 0.5)
  for (let i = 0; i < 6; i++) b.batch(i * 4000, { phase: 'round', roadSign: 'Турово', water: 'ok' })
  for (let i = 6; i < 12; i++) b.batch(i * 4000, { phase: 'round', bleeding: 1, roadSign: 'Турово' })
  const said: Utterance[] = []
  let now = 0
  for (const batch of b.batches) said.push(...engine.ingestObservations(batch.observations, (now = Math.max(now, batch.arrivesAt))).utterances)
  const texts = said.map((u) => u.text)
  assert.ok(texts.some((t) => /^Turovo \(Турово\), far north-east\. Svetloyarsk is 1\.0 km south-east\./.test(t)), texts.join(' | '))
  assert.ok(texts.some((t) => /bleeding/i.test(t)), texts.join(' | '))
  assert.equal(signLine('Турово', {}), signLine('Typobo', {}))
})

test('survival questions are answered on this computer from the pack', () => {
  const ask = (q: string) => {
    const intent = classify(q, dayz)
    assert.equal(intent.kind, 'survival', q)
    return answerLocally(intent, dayz, engineFor(dayz, settings({ profileId: 'dayz', profileModeId: 'offline' })).report(), [])!
  }
  assert.match(ask('how do I treat cholera?'), /Cholera \(unclean water; tetracycline or multivitamins\)/)
  assert.match(ask('how do I make a splint'), /4 rags, or 1 bandage, or 1 duct tape, plus 2 short sticks/)
  assert.match(ask('what blood type can I take'), /O- gives to anyone/)
  assert.match(ask('do I need a gas mask in the toxic zone'), /gas mask and the full NBC suit/)
  assert.doesNotMatch(ask('how do I treat cholera?'), BANNED_CLAIMS)
})

test('online: a place look-up from the built-in map is answered during play; anything from the screen still waits', () => {
  const official = policyFor(dayz, 'official')
  assert.ok(official.lookups.has('places') && !official.scope.has('live'))
  assert.equal(policyFor(dayz, 'official', { tournament: true }).lookups.size, 0, 'tournament switch: nothing')
  // Default mode (competitive-safe): coaching waits, the look-up is spoken.
  const s = settings({ profileId: 'dayz', profileModeId: 'official' })
  const engine = engineFor(dayz, s)
  const b = new Builder('dayz', () => 0.5)
  for (let i = 0; i < 4; i++) b.batch(i * 4000, { phase: 'round', water: 'ok', roadSign: 'Турово' })
  let now = 0
  for (const batch of b.batches) engine.ingestObservations(batch.observations, (now = Math.max(now, batch.arrivesAt)))
  const decision = (o: ReturnType<typeof engine.answer>) => o.entries.flatMap((e) => (e.type === 'message' ? [e.decision] : []))[0]
  const look = engine.answer('where is typobo', 'Turovo is a village in the far north-east of Chernarus.', 'template', now, 'places')
  assert.equal(decision(look).decision, 'spoken')
  assert.match(decision(look).reasons[0], /place look-up you asked for, from the built-in map data only/)
  assert.equal(look.utterances.length, 1)
  // An answer without the look-up mark (anything else, or one from the screen) is held as before.
  assert.equal(decision(engine.answer('how am I doing', 'Fine.', 'template', now + 1)).decision, 'queued')
  // Which place answers count as look-ups: not the ones built from the last road sign read.
  const ctx = { map: 'Chernarus', roadSign: 'Турово' }
  assert.equal(placeAnswer('where is typobo', ctx)!.fromScreen, false)
  assert.equal(placeAnswer('how do I get from cherno to nwaf', ctx)!.fromScreen, false)
  assert.equal(placeAnswer('nearest water to turovo', ctx)!.fromScreen, false)
  assert.equal(placeAnswer('where am i', ctx)!.fromScreen, true)
  assert.equal(placeAnswer('how far to berezino', ctx)!.fromScreen, true)
  assert.equal(placeAnswer('nearest hospital', ctx)!.fromScreen, true)
})

test('the registry checks look-up allowances', () => {
  const entry = { id: 'x/y', publisher: 'P', game: 'G', gameIds: ['x'], modes: ['*'], status: 'needs_publisher_review', basis: 'internal', sources: [], rationale: 'r', reviewedAt: '2026-11-01', reviewer: 'me', notes: '' }
  const reg = (l: unknown, status = 'needs_publisher_review') => validateRegistry({ schemaVersion: 1, reviewMaxAgeDays: 90, note: '', entries: [{ ...entry, status, lookupsDuringPlay: l }] })
  assert.ok(reg({ kinds: ['places'], rationale: 'map facts', decidedBy: 'the player', decidedAt: '2026-11-14' }).ok)
  assert.ok(!reg({ kinds: ['screen'], rationale: 'r', decidedBy: 'p', decidedAt: '2026-11-14' }).ok)
  assert.ok(!reg({ kinds: ['places'], rationale: '', decidedBy: 'p', decidedAt: '2026-11-14' }).ok)
  assert.ok(!reg({ kinds: ['places'], rationale: 'r', decidedBy: 'p', decidedAt: '2026-11-14' }, 'disabled').ok)
})
