// Villagers on each resource: the build's split checked live, split targets from a
// watched game, gold for the Castle Age from the measured gathering rate, and the
// engine putting it together with the Town Center's idle time.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { compareSplits, GoldForCastle, splitLine, splitsFromReadings, type WorkerSplit } from '../src/core/aoe4/workers.ts'
import { BuildCaller, isBuildOrder, type BuildOrder } from '../src/core/aoe4/buildOrder.ts'
import { isMatchRecord } from '../src/core/analyses/matchRecorder.ts'
import { localReview } from '../src/core/review.ts'
import { matchSummary } from '../src/core/notify.ts'
import { BANNED_CLAIMS } from '../src/core/profile/schema.ts'
import type { ReadingLog } from '../src/core/truth/readingLog.ts'
import type { MatchRecord } from '../src/core/analyses/matchRecorder.ts'
import type { TimelineEntry, Utterance } from '../src/core/types.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { aoe4GameReplay } from '../src/sim/visionReplay.ts'
import { engineFor, settings } from './helpers.ts'

const aoe4 = builtInProfile('aoe4')
const split = (atSec: number, food: number, wood: number, gold: number, stone = 0): WorkerSplit => ({ atSec, food, wood, gold, stone })

test('the split line: the biggest shortfalls, from 2 villagers; nothing when on plan', () => {
  assert.equal(splitLine(split(240, 8, 6, 2), { food: 9, wood: 3, gold: 2, stone: 0 }), 'Build wants 6 on wood by 4:00, you have 3.')
  assert.equal(splitLine(split(480, 10, 8, 6), { food: 10, wood: 5, gold: 2, stone: 0 }), 'Build wants 6 on gold and 8 on wood by 8:00; you have 2 and 5.')
  assert.equal(splitLine(split(240, 8, 6, 2), { food: 7, wood: 5, gold: 1, stone: 3 }), null, 'one short on each is on plan')
  assert.doesNotMatch(splitLine(split(240, 8, 6, 2), { food: 0, wood: 0, gold: 0, stone: 0 })!, BANNED_CLAIMS)
})

const log = (rows: [number, number, number, number, number][]): ReadingLog => ({
  t: rows.map((r) => r[0]),
  values: { foodWorkers: rows.map((r) => r[1]), woodWorkers: rows.map((r) => r[2]), goldWorkers: rows.map((r) => r[3]), stoneWorkers: rows.map((r) => r[4]) },
  changes: [],
})

test('split targets from a watched game every 2 minutes; compared after the next game', () => {
  const l = log([
    [100, 6, 2, 0, 0],
    [120, 7, 3, 0, 0],
    [240, 9, 5, 2, 0],
    [360, 10, 7, 4, 0],
  ])
  assert.deepEqual(splitsFromReadings(l, 400), [split(120, 7, 3, 0), split(240, 9, 5, 2), split(360, 10, 7, 4)])
  const checks = compareSplits([split(240, 9, 8, 2), split(600, 12, 10, 6)], l)
  assert.equal(checks[0].line, 'Build wants 8 on wood by 4:00, you have 5.')
  assert.equal(checks[1].had, null, 'not read then')
})

const build = (splits: WorkerSplit[], castleAt?: number): BuildOrder => ({
  schema: 1,
  id: 'b-test',
  name: 'Test',
  civ: 'english',
  source: 'custom',
  createdAt: '2026-10-29T00:00:00Z',
  steps: [{ atSec: 270, kind: 'age', key: 'feudal', name: 'Feudal Age', count: 1 }, ...(castleAt ? [{ atSec: castleAt, kind: 'age' as const, key: 'castle', name: 'Castle Age', count: 1 }] : [])],
  splits,
})

test('the build trainer checks the split once at its time, in a tick with no step call; a build with splits is valid', () => {
  const b = build([split(240, 8, 8, 2)])
  assert.ok(isBuildOrder(b))
  assert.ok(!isBuildOrder({ ...b, splits: [{ atSec: 240, food: -1, wood: 0, gold: 0, stone: 0 }] }))
  const caller = new BuildCaller(b)
  const w = { food: 9, wood: 5, gold: 2, stone: 0 }
  assert.deepEqual(caller.tick(230, null, w), [])
  assert.deepEqual(caller.tick(241, null, null), [], 'numbers not read: waits')
  assert.deepEqual(caller.tick(243, null, w), [{ text: 'Build wants 8 on wood by 4:00, you have 5.', topic: 'build:b-test:split:240' }])
  assert.deepEqual(caller.tick(250, null, w), [], 'once')
})

test('gold for Castle: measured rate per villager on gold, the build time, and how many more; nobody on gold for a minute', () => {
  const g = new GoldForCastle(600)
  const said: string[] = []
  // Feudal at 300; 3 on gold gathering 0.5 gold/s each (1.5 a second), spending 50 every 60 s.
  let gold = 100
  for (let t = 300; t <= 560; t++) {
    gold += 1.5
    if (t % 60 === 0) gold -= 50
    const r = g.observe({ t, age: 'feudal', gold: Math.round(gold), goldWorkers: 3 })
    if (r) said.push(`${t}|${r.text}`)
  }
  assert.ok(Math.abs(g.rate! - 0.5) < 0.02, String(g.rate))
  assert.ok(said.length >= 1 && said.length <= 2, said.join('\n'))
  if (said[1]) assert.ok(Number(said[1].split('|')[0]) - Number(said[0].split('|')[0]) >= 90, 'at most twice, 90 s apart')
  assert.match(said[0], /^3[4-9]\d\|Gold for Castle: \d+ of 600 with 3 on gold, there around \d+:\d\d; the build ages up at 10:00\. About \d+ more on gold\.$/)
  assert.doesNotMatch(said[0], BANNED_CLAIMS)

  const none = new GoldForCastle(null)
  const lines: string[] = []
  for (let t = 300; t <= 420; t += 3) {
    const r = none.observe({ t, age: 'feudal', gold: 120, goldWorkers: 0 })
    if (r) lines.push(r.text)
  }
  assert.deepEqual(lines, ['No villagers on gold for a minute, and the Castle Age needs 600 gold (you have 120).'])
  const castle = new GoldForCastle(null)
  assert.equal(castle.observe({ t: 900, age: 'castle', gold: 0, goldWorkers: 0 }), null, 'only in Feudal')
})

function play(modeId: string, b: BuildOrder | null, commentary = false) {
  const s = settings({ profileId: 'aoe4', profileModeId: modeId, mode: 'training', commentary, context: { aoe4: { civ: 'English' } } })
  const engine = engineFor(aoe4, s, { build: b, context: { civ: 'English' } })
  const entries: TimelineEntry[] = [...engine.startNotices(0)]
  const utterances: Utterance[] = []
  const matches: MatchRecord[] = []
  let now = 0
  for (const batch of aoe4GameReplay({ seed: 4 }).batches) {
    const out = engine.ingestObservations(batch.observations, (now = Math.max(now, batch.arrivesAt)))
    entries.push(...out.entries)
    utterances.push(...out.utterances)
    matches.push(...out.matches)
  }
  return { entries, utterances, matches, engine }
}

test('in a game against the AI: the split is called, the idle Town Center said once, and the match keeps its idle time', () => {
  const r = play('skirmish-vs-ai', build([split(240, 8, 8, 2)]))
  const said = r.utterances.map((u) => u.text)
  assert.ok(said.includes('Build wants 8 on wood by 4:00, you have 5.'), said.join(' | '))
  assert.equal(said.filter((t) => /^No new villager for \d+ seconds\./.test(t)).length, 1, said.join(' | '))
  const rec = r.matches[0]
  assert.ok(rec.production, 'the match record has the Town Center idle time')
  assert.ok(isMatchRecord(rec))
  // The replay stops villagers from 4:00 to 6:30: one villager due at 4:20, the next made at 6:50.
  assert.equal(rec.production!.stretches.length, 1)
  assert.ok(Math.abs(rec.production!.stretches[0].at - 260) <= 13 && Math.abs(rec.production!.stretches[0].sec - 150) <= 13, JSON.stringify(rec.production!.stretches))
  const review = localReview(aoe4, r.engine.report())!
  assert.ok(review.focus.some((f) => /^Town Center idle 2:\d\d in total, the longest 2:\d\d at 4:\d\d\./.test(f.evidence)), review.focus.map((f) => f.evidence).join(' | '))
  assert.match(matchSummary(aoe4, rec, null), /Town Center idle 2:\d\d in total/)
})

test('in ranked play nothing about the split or the Town Center is said during the match, but the idle time is kept', () => {
  const r = play('ranked-1v1', build([split(240, 8, 8, 2)]), true)
  assert.ok(!r.utterances.some((u) => /Build wants|No new villager/.test(u.text)), r.utterances.map((u) => u.text).join(' | '))
  assert.ok(r.matches[0].production)
})
