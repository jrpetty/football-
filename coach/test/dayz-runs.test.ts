// DayZ loot runs (the shortest way through one of each kind of place asked
// for) and the trail of where the player has been, life by life.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { DAYZ_MAPS, distance, findPlace } from '../src/core/dayz/places.ts'
import { isLootRun, lootRunLine, lootRunPath, parseLootRun, planLootRun, pointsOf, stopsIn } from '../src/core/dayz/lootRun.ts'
import { lifeLine, trailStep, type TrailLife } from '../src/core/dayz/trail.ts'
import { answerPlaceQuestion, placeAnswer } from '../src/core/dayz/ask.ts'
import { classify, lookupKind } from '../src/core/qa/ask.ts'
import { BANNED_CLAIMS } from '../src/core/profile/schema.ts'
import { builtInProfile } from '../src/profiles/index.ts'

const dayz = builtInProfile('dayz')

test('reading a loot run: the start, the kinds of place in order, back or on to a town', () => {
  const q = 'Plan a route from Berezino hitting a hospital, a police station and military'
  assert.ok(isLootRun(q))
  assert.deepEqual(stopsIn(q.toLowerCase()), ['hospital', 'police', 'military'])
  const r = parseLootRun(q, 'chernarus', null)!
  assert.equal(r.start.place.name, 'Berezino')
  assert.equal(r.back, false)
  assert.equal(parseLootRun('loot run from cherno via police and fire station and back', null, null)!.back, true)
  assert.equal(parseLootRun('plan a loot run from Turovo hitting military, ending at Berezino', null, null)!.end!.place.name, 'Berezino')
  assert.equal(parseLootRun('plan a route from here hitting police', null, findPlace('Turovo'))!.start.place.name, 'Turovo')
  assert.ok(!isLootRun('where is berezino'))
  assert.ok(!isLootRun('how do I get from cherno to nwaf'))
})

test('the plan: one of each kind, the shortest order over the candidates, real points from the game files', () => {
  const req = parseLootRun('loot run from Chernogorsk hitting police, fire station and a hospital and back', null, null)!
  const plan = planLootRun(req)
  assert.deepEqual(new Set(plan.stops.map((s) => s.kind)), new Set(['police', 'fire', 'hospital']))
  for (const s of plan.stops) assert.ok(pointsOf(plan.map, s.kind).some((p) => distance(p, s) < 2), `${s.kind} is a real point`)
  // No other order through the same stops is shorter.
  const pts = plan.stops.map((s) => ({ x: s.x, y: s.y }))
  const start = plan.start
  const len = (order: typeof pts) => order.reduce((acc, p, i) => acc + distance(i ? order[i - 1] : start, p), 0) + distance(order.at(-1)!, start)
  const perms = (a: typeof pts): (typeof pts)[] => (a.length <= 1 ? [a] : a.flatMap((x, i) => perms([...a.slice(0, i), ...a.slice(i + 1)]).map((r) => [x, ...r])))
  assert.ok(perms(pts).every((o) => len(o) >= len(pts) - 1))
  assert.ok(Math.abs(plan.totalM - len(pts)) < 2)
  const line = lootRunLine(plan)
  assert.match(line, /^Loot run from Chernogorsk, [\d.]+ k?m in all \(straight lines; about \d+ min jogging(?: \(\d+ with sprints\))?(?:, \d+ min by car)?, not counting looting\): 1\. /)
  assert.match(line, /Then back to Chernogorsk/)
  assert.doesNotMatch(line, BANNED_CLAIMS)
  const path = lootRunPath(plan)
  assert.equal(path.length, 5, 'start, three stops, back')
  assert.match(path[1].label, /^1 /)
})

test('a kind the map does not have is said, not invented; answered locally and as a look-up', () => {
  const sakhal = planLootRun(parseLootRun('loot run from Rudnogorsk hitting water and military', 'sakhal', null)!)
  assert.deepEqual(sakhal.missing, ['water'])
  assert.match(lootRunLine(sakhal), /Sakhal has no water pump in the game's files/)
  const q = 'plan a route from Berezino hitting a hospital and police'
  assert.equal(classify(q, dayz).kind, 'place')
  assert.match(answerPlaceQuestion(q, {})!, /^Loot run from Berezino/)
  assert.equal(lookupKind(classify(q, dayz), dayz, { roadSign: 'Турово' }), 'places', 'from a named town: a look-up')
  assert.equal(placeAnswer('plan a route from here hitting police', { roadSign: 'Турово' })!.fromScreen, true, 'from here: the sign read')
})

test('the trail: signs become towns in order, a death closes the life, a stale sign does not start a new one', () => {
  let lives: readonly TrailLife[] = []
  const step = (sign: string | null, ended: boolean | null = null, now = 0) => (lives = trailStep(lives, { sign, prefer: 'chernarus', lifeEnded: ended === null ? null : { died: ended }, now }))
  step('Березино', null, 1)
  step('Березино', null, 2)
  step('Хелм', null, 3)
  step('Турово', null, 4)
  assert.equal(lives.length, 1)
  assert.deepEqual(lives[0].stops.map((s) => s.name), ['Berezino', 'Khelm', 'Turovo'])
  step('Турово', true, 5)
  assert.deepEqual(lives[0].end, { died: true, near: 'Turovo', at: 5 })
  assert.equal(lifeLine(lives[0], 0), 'Life 1: Berezino → Khelm → Turovo, died near Turovo.')
  step('Турово', null, 6)
  assert.equal(lives.length, 1, 'the old sign after death is not a new life')
  step('Черногорск', null, 7)
  assert.equal(lives.length, 2)
  assert.equal(lifeLine(lives[1], 1), 'Life 2: Chernogorsk (this life).')
  assert.equal(DAYZ_MAPS[0].id, lives[1].map)
})
