// Battles in strategy games: fights followed frame by frame, settled from the
// visible trend and the army size, then compared by circumstance.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { BattleTracker } from '../src/core/analyses/battles.ts'
import type { BattleSpec } from '../src/core/profile/schema.ts'
import type { Observation, TimelineEntry, Utterance, VisibleState } from '../src/core/types.ts'
import { answerLocally, buildDigest, classify } from '../src/core/qa/ask.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { aoe4GameReplay } from '../src/sim/visionReplay.ts'
import { engineFor, settings } from './helpers.ts'

const aoe4 = builtInProfile('aoe4')
const spec: BattleSpec = { event: 'fight', trendAttribute: 'trend', army: { total: 'population', minus: 'villagers' }, gapMs: 20_000, minFrames: 2, wonEvent: 'fight_won', lostEvent: 'fight_lost' }

const state = (pop: number, vills: number, at: number, phase: VisibleState['phase'] = 'round'): VisibleState => {
  const fv = (value: number) => ({ value, confidence: 0.9, observedAt: at, source: 'vision-model' as const, conflicted: false })
  return { values: { population: fv(pop), villagers: fv(vills) }, phase, stale: false, staleReasons: [] } as unknown as VisibleState
}
const fight = (at: number, attributes: Record<string, string>): Observation => ({
  id: `o${at}`, timestampMs: at, gameProfileId: 'aoe4', sourceRegion: 'full-frame', kind: 'event', eventType: 'fight', attributes, confidence: 0.9, freshnessMs: 1000, evidence: [], source: 'vision-model',
})

function run(frames: { at: number; army: number; trend?: string; tags?: Record<string, string> }[], until: number) {
  const t = new BattleTracker(spec, null)
  const events = []
  for (const f of frames) events.push(...t.update(f.trend ? [fight(f.at, { trend: f.trend, ...(f.tags ?? {}) })] : [], state(20 + f.army, 20, f.at), f.at))
  events.push(...t.update([], state(20, 20, until), until))
  return { events, log: t.battles() }
}

test('battles: a fight that looks lost and costs the army is lost, and how it ended is recorded', () => {
  const { events, log } = run([{ at: 0, army: 30 }, { at: 4000, army: 30, trend: 'even', tags: { location: 'near_enemy_defenses' } }, { at: 8000, army: 18, trend: 'losing' }, { at: 12_000, army: 6, trend: 'losing' }], 40_000)
  assert.equal(events.length, 1)
  assert.equal(events[0].ruleId, 'fight_lost')
  assert.equal(events[0].attributes?.location, 'near_enemy_defenses')
  assert.equal(events[0].attributes?.ending, 'fought_to_the_end')
  assert.match(events[0].evidence, /army 30 → 6/)
  assert.equal(log[0].outcome, 'lost')
})

test('battles: a winning look with a small cost is won; a winning look while losing most of the army is not called', () => {
  const won = run([{ at: 0, army: 30 }, { at: 4000, army: 28, trend: 'winning' }, { at: 8000, army: 26, trend: 'winning' }], 40_000)
  assert.equal(won.events[0].ruleId, 'fight_won')
  const odd = run([{ at: 0, army: 30 }, { at: 4000, army: 20, trend: 'even' }, { at: 8000, army: 10, trend: 'winning' }], 40_000)
  assert.equal(odd.events.length, 0)
  assert.equal(odd.log[0].outcome, 'unclear')
})

test('battles: one frame is a skirmish; a gap splits fights; the match ending closes an open fight', () => {
  const one = run([{ at: 0, army: 30 }, { at: 4000, army: 10, trend: 'losing' }], 40_000)
  assert.equal(one.events.length, 0, 'a single frame is not called')
  const two = run(
    [{ at: 0, army: 30 }, { at: 4000, army: 30, trend: 'losing' }, { at: 8000, army: 15, trend: 'losing' }, { at: 40_000, army: 25, trend: 'winning' }, { at: 44_000, army: 24, trend: 'winning' }],
    90_000,
  )
  assert.deepEqual(two.events.map((e) => e.ruleId), ['fight_lost', 'fight_won'])
  const t = new BattleTracker(spec, null)
  t.update([], state(50, 20, 0), 0)
  t.update([fight(4000, { trend: 'losing' })], state(40, 20, 4000), 4000)
  t.update([fight(8000, { trend: 'losing' })], state(30, 20, 8000), 8000)
  assert.equal(t.update([], state(30, 20, 9000, 'match_end'), 9000)[0]?.ruleId, 'fight_lost')
})

test('AoE4: fights from the scripted game are settled as scripted, compared by where they happened, and coached with the fix', () => {
  const replay = aoe4GameReplay({ seed: 4 })
  const t = replay.truth as Record<string, number>
  const engine = engineFor(aoe4, settings({ profileId: 'aoe4', profileModeId: 'ranked-1v1', mode: 'training' }))
  const entries: TimelineEntry[] = []
  const utterances: Utterance[] = []
  let now = 0
  for (const b of replay.batches) {
    const out = engine.ingestObservations(b.observations, (now = Math.max(now, b.arrivesAt)))
    entries.push(...out.entries)
    utterances.push(...out.utterances)
  }
  const report = engine.report()
  assert.equal(report.battles.filter((b) => b.outcome === 'won').length, t.fightsWon)
  assert.equal(report.battles.filter((b) => b.outcome === 'lost').length, t.fightsLost)
  const location = report.factors.find((f) => f.spec.id === 'fight-location')!
  assert.equal(location.rows.find((r) => r.value === 'near_enemy_defenses')!.losses, t.lostNearDefenses)
  const finding = location.findings.find((f) => f.value === 'near_enemy_defenses')!
  assert.equal(finding.statement, `All ${t.lostNearDefenses} fights lost came near their Town Center, towers or Keep.`)
  assert.equal(finding.playbook, 'arrow-range')
  // Ranked: nothing during play; the summary counts the fights and gives the fix.
  assert.ok(utterances.every((u) => u.category === 'post_event'))
  const summary = utterances.at(-1)!.text
  assert.match(summary, /Fights I could follow: 3 won, 3 lost\./)
  assert.ok(summary.includes(aoe4.knowledge.playbook!.find((e) => e.id === 'arrow-range')!.fix))
  // The game itself (map, opponent) is kept for the review and answers.
  assert.deepEqual(report.context, { mapName: 'Dry Arabia', opponentCiv: 'English' })
  const digest = JSON.parse(buildDigest({ profile: aoe4, modeLabel: null, policyLine: '', report, history: [], notes: null, research: null }))
  assert.equal(digest.session.fightsFollowed.won, 3)
  assert.equal(digest.session.game.opponentCiv, 'English')
  assert.equal(classify('how did my fights go?', aoe4).kind, 'fights')
  assert.match(answerLocally(classify('how did my fights go?', aoe4), aoe4, report, [])!, /near their Town Center/)
  assert.equal(entries.filter((e) => e.type === 'event' && e.event.ruleId === 'fight').length, t.fightsWon + t.fightsLost, 'one timeline entry per fight, not per frame')
})

test('AoE4 against the AI in training mode: a live tip once fights near their defences are clearly lost', () => {
  const engine = engineFor(aoe4, settings({ profileId: 'aoe4', profileModeId: 'skirmish-vs-ai', mode: 'training', cooldownSec: 5 }))
  const utterances: Utterance[] = []
  let now = 0
  for (const b of aoe4GameReplay({ seed: 4 }).batches) utterances.push(...engine.ingestObservations(b.observations, (now = Math.max(now, b.arrivesAt))).utterances)
  assert.ok(utterances.some((u) => u.category === 'training' && u.text === 'Pull back out of their arrow range.'), utterances.map((u) => u.text).join(' | '))
})
