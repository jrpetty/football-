// Positioning and fight analysis (deaths vs kills by what the killcam shows),
// benchmarks against the clock, and the researched playbooks that turn each
// finding into a fix.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import type { CoachEngine } from '../src/core/engine.ts'
import { FactorAnalyzer, factorResult, historyFactors } from '../src/core/analyses/factors.ts'
import { BenchmarkTracker, agreed, benchmarkResult, benchmarkStatement, expectedAt, specFor } from '../src/core/analyses/benchmarks.ts'
import { playbookText } from '../src/core/knowledge/base.ts'
import { answerLocally, buildDigest, classify } from '../src/core/qa/ask.ts'
import { BANNED_CLAIMS, validateProfile, type FactorSpec, type GameProfile } from '../src/core/profile/schema.ts'
import type { GameEvent, TimelineEntry, Utterance, VisibleState } from '../src/core/types.ts'
import { builtInProfile } from '../src/profiles/index.ts'
import { aoe4GameReplay, mw4MatchReplay, type VisionReplay } from '../src/sim/visionReplay.ts'
import { engineFor, settings } from './helpers.ts'

const mw4 = builtInProfile('cod-mw4')
const aoe4 = builtInProfile('aoe4')
const fc = builtInProfile('ea-fc')

function play(engine: CoachEngine, replay: VisionReplay) {
  const entries: TimelineEntry[] = [...engine.startNotices(0)]
  const utterances: Utterance[] = []
  let now = 0
  for (const b of replay.batches) {
    now = Math.max(now, b.arrivesAt)
    const out = engine.ingestObservations(b.observations, now)
    entries.push(...out.entries)
    utterances.push(...out.utterances)
  }
  return { entries, utterances }
}

const fix = (p: GameProfile, id: string) => p.knowledge.playbook!.find((e) => e.id === id)!.fix

// --- MW4: fights from the killcam -----------------------------------------------------------

test('MW4: deaths and kills are compared by range, cover, movement and timing, matching the ground truth', () => {
  const replay = mw4MatchReplay({ seed: 2 })
  const t = replay.truth as Record<string, number>
  const engine = engineFor(mw4, settings({ profileId: 'cod-mw4', profileModeId: 'multiplayer', mode: 'post-match' }))
  play(engine, replay)
  const f = Object.fromEntries(engine.report().factors.map((r) => [r.spec.id, r]))

  const range = f.range.rows.find((r) => r.value === 'long')!
  assert.deepEqual([range.losses, range.wins], [t.longLost, t.longWon])
  const close = f.range.rows.find((r) => r.value === 'close')!
  assert.deepEqual([close.losses, close.wins], [t.closeLost, t.closeWon])
  assert.equal(f.exposure.rows.find((r) => r.value === 'in_open')!.losses, t.inOpen)
  assert.equal(f.exposure.taggedLosses, t.exposureKnown)
  assert.equal(f.exposure.untagged, t.deaths - t.exposureKnown, 'an unreadable killcam is counted, not guessed')
  assert.equal(f['after-kill'].rows.find((r) => r.value === 'yes')!.losses, t.afterKill)

  const findings = f.range.findings
  const long = findings.find((x) => x.value === 'long' && x.kind === 'weakness')!
  assert.equal(long.statement, `You lost ${t.longLost} of ${t.longLost + t.longWon} fights at long range.`)
  assert.equal(long.grade, 'moderate')
  assert.ok(findings.some((x) => x.value === 'close' && x.kind === 'strength'))

  const open = f.exposure.findings.find((x) => x.value === 'in_open')!
  assert.equal(open.statement, `${t.inOpen} of your ${t.exposureKnown} deaths came while you were in the open.`)
  assert.equal(open.grade, 'strong')
  assert.equal(open.playbook, 'fight-from-cover')
  assert.ok(f['after-kill'].findings.some((x) => x.value === 'yes' && x.playbook === 'reposition-after-kill'))
})

test('MW4 online: nothing during play; the summary leads with how fights were lost, each with a fix', () => {
  const engine = engineFor(mw4, settings({ profileId: 'cod-mw4', profileModeId: 'multiplayer', mode: 'training' }))
  const { utterances } = play(engine, mw4MatchReplay({ seed: 2 }))
  assert.equal(utterances.length, 1)
  assert.equal(utterances[0].category, 'post_event')
  const text = utterances[0].text
  assert.match(text, /8 of your 12 deaths came while you were in the open\./)
  assert.ok(text.includes(fix(mw4, 'fight-from-cover')), text)
  assert.doesNotMatch(text, BANNED_CLAIMS)
})

test('MW4 against bots in training mode: a short live tip once dying in the open is moderately clear', () => {
  const engine = engineFor(mw4, settings({ profileId: 'cod-mw4', profileModeId: 'private-bots', mode: 'training', cooldownSec: 10 }))
  const { utterances } = play(engine, mw4MatchReplay({ seed: 2 }))
  const tips = utterances.filter((u) => u.category === 'training').map((u) => u.text)
  assert.ok(tips.includes('Fight from cover, not the open.'), tips.join(' | '))
  assert.equal(tips.filter((x) => x === 'Fight from cover, not the open.').length, 1, 'once per match')
})

test('MW4: "why do I keep dying" is answered locally with the findings and fixes', () => {
  const engine = engineFor(mw4, settings({ profileId: 'cod-mw4', profileModeId: 'multiplayer', mode: 'post-match' }))
  play(engine, mw4MatchReplay({ seed: 2 }))
  const intent = classify('Why do I keep dying?', mw4)
  assert.equal(intent.kind, 'fights')
  const answer = answerLocally(intent, mw4, engine.report(), [])!
  assert.match(answer, /in the open/)
  assert.match(answer, /likely rather than certain/)
  assert.equal(classify('where do I die most', mw4).kind, 'heatmap')
  const digest = JSON.parse(buildDigest({ profile: mw4, modeLabel: null, policyLine: '', report: engine.report(), history: [], notes: null, research: null }))
  const exposure = digest.session.fights.find((x: { factor: string }) => x.factor === 'Cover when you died')
  assert.equal(exposure.byValue['In the open'].deaths, 8)
  assert.equal(exposure.notReadable, 1)
})

// --- Factor grading ---------------------------------------------------------------------------

const spec: FactorSpec = {
  id: 'range', label: 'Range', attribute: 'range', lossEvent: 'death', winEvent: 'kill', minEvents: 6,
  values: [
    { value: 'close', label: 'Close', phrase: 'at close range' },
    { value: 'long', label: 'Long', phrase: 'at long range', avoidable: true, playbook: 'x' },
  ],
}
const counts = (c: Record<string, [number, number]>) => new Map(Object.entries(c).map(([k, [l, w]]) => [k, { l, w }]))

test('factors: no claim below the minimum, and grades rise with evidence', () => {
  assert.equal(factorResult(spec, counts({ long: [2, 0], close: [0, 2] })).findings.length, 0, 'four fights is below the minimum')
  const weak = factorResult(spec, counts({ long: [3, 0], close: [1, 4] })).findings.find((f) => f.value === 'long' && f.kind === 'weakness')
  assert.equal(weak?.grade, 'weak')
  const strong = factorResult(spec, counts({ long: [9, 1], close: [3, 9] })).findings.find((f) => f.value === 'long' && f.kind === 'weakness')
  assert.equal(strong?.grade, 'strong')
  assert.equal(strong?.statement, 'You lost 9 of 10 fights at long range.')
})

test('factors: an even split is not a weakness or a strength (an avoidable value can still be a large share of deaths)', () => {
  const r = factorResult(spec, counts({ long: [5, 5], close: [5, 5] }))
  assert.deepEqual(r.findings.map((f) => f.statement), ['5 of your 10 deaths came at long range.'])
  const plain = factorResult({ ...spec, values: spec.values.map((v) => ({ ...v, avoidable: false })) }, counts({ long: [5, 5], close: [5, 5] }))
  assert.equal(plain.findings.length, 0)
})

test('factors: unknown and unlisted values are not counted as a value; timing factors use event times', () => {
  const a = new FactorAnalyzer([
    spec,
    { id: 'after', label: 'After a kill', afterEvent: { event: 'kill', withinMs: 8000 }, lossEvent: 'death', minEvents: 2, values: [{ value: 'yes', label: 'Yes', phrase: 'right after a kill' }, { value: 'no', label: 'No', phrase: 'otherwise' }] },
  ])
  const ev = (uid: string, ruleId: string, at: number, attributes?: Record<string, string>): GameEvent => ({ uid, ruleId, label: ruleId, category: 'outcome', at, confidence: 0.9, evidence: '', attributes })
  a.record(ev('1', 'death', 1000, { range: 'unknown' }))
  a.record(ev('2', 'death', 2000, { range: 'sideways' }))
  a.record(ev('3', 'kill', 10_000, { range: 'long' }))
  a.record(ev('4', 'death', 15_000, { range: 'long' }))
  a.record(ev('5', 'death', 40_000))
  const [range, after] = a.results()
  assert.equal(range.untagged, 3)
  assert.deepEqual(range.rows.find((r) => r.value === 'long'), { value: 'long', label: 'Long', losses: 1, wins: 1 })
  assert.deepEqual(after.rows.map((r) => r.losses), [1, 3])
  // A goal replay that arrives after the scoreboard change fills in the value.
  a.amend('5', { range: 'close' })
  assert.equal(a.results()[0].rows.find((r) => r.value === 'close')!.losses, 1)
  assert.deepEqual(a.matchCounts().range, { close: [1, 0], long: [1, 1] })
})

test('factors: history sums matches and keeps the same grading', () => {
  const [r] = historyFactors([spec], [{ range: { long: [4, 1], close: [1, 3] } }, { range: { long: [4, 0], close: [1, 5] } }, undefined])
  assert.deepEqual(r.rows.map((x) => [x.losses, x.wins]), [[2, 8], [8, 1]])
  assert.equal(r.findings[0].statement, 'You lost 8 of 9 fights at long range.')
})

// --- Benchmarks -------------------------------------------------------------------------------

test('AoE4: villagers are checked against one Town Center that never stops, with the fix (no two- or three-Town-Center talk)', () => {
  const replay = aoe4GameReplay({ seed: 4 })
  const engine = engineFor(aoe4, settings({ profileId: 'aoe4', profileModeId: 'ranked-1v1', mode: 'post-match' }))
  const { utterances } = play(engine, replay)
  const [b] = engine.report().benchmarks
  const t = replay.truth as Record<string, number>
  assert.deepEqual(b.points.map((p) => [p.atSec, p.actual, p.expected]), [[300, t.at300, 21], [600, t.at600, 36], [900, t.at900, 51]])
  assert.deepEqual(b.points.map((p) => p.behind), [false, true, true])
  assert.equal(
    b.worst!.statement,
    `At 10:00 you had ${t.at600} villagers; one Town Center that never stops has 36 by then. That is about 3 minutes of villager production.`,
  )
  assert.doesNotMatch(utterances.map((u) => u.text).join(' '), /two or three Town Centers|second Town Center|third Town Center/i)
  assert.ok(utterances[0].text.includes('At 10:00 you had 28 villagers'), utterances[0].text)
  assert.ok(utterances[0].text.includes(fix(aoe4, 'tc-never-idle')))
})

test('benchmarks: the curve, its cap, and readings far from a checkpoint', () => {
  const s = aoe4.benchmarks![0]
  assert.equal(expectedAt(s, 0), 6)
  assert.equal(expectedAt(s, 600), 36)
  assert.equal(expectedAt(s, 36_000), 120)
  const r = benchmarkResult(s, [{ atSec: 300, actual: 21 }])
  assert.equal(r.worst, null)
  assert.equal(r.met, 1)
})

test('benchmarks: a civilisation with slower or faster villagers has its own curve; one not yet checked is not judged', () => {
  const s = aoe4.benchmarks![0]
  assert.equal(specFor(s, 'english'), s)
  const dragon = specFor(s, 'order-of-the-dragon')!
  // Gilded Villagers take 23 seconds: about 2.6 a minute, not 3.
  assert.deepEqual([300, 600, 900, 1200].map((t) => expectedAt(dragon, t)), [19, 32, 45, 58])
  assert.deepEqual([300, 600, 900, 1200].map((t) => expectedAt(s, t)), [21, 36, 51, 66])
  // 28 Gilded Villagers at 10:00 is within the Order of the Dragon's curve, but behind the usual one.
  assert.equal(benchmarkResult(dragon, [{ atSec: 600, actual: 28 }]).worst, null)
  assert.ok(benchmarkResult(s, [{ atSec: 600, actual: 28 }]).worst)
  assert.match(benchmarkStatement(dragon, { atSec: 600, actual: 26, expected: 32, behind: true }), /Gilded Villagers take 23 seconds/)
  assert.equal(specFor(s, 'golden-horde'), null)
  const t = new BenchmarkTracker([s], 'golden-horde')
  assert.equal(t.results().length, 0)
  t.setFaction('order-of-the-dragon')
  assert.equal(t.results()[0].spec.expected.perMinute, 2.6)
})

const at = (observedAt: number, values: Record<string, number>): VisibleState => ({
  timestampMs: observedAt,
  phase: 'round',
  values: Object.fromEntries(Object.entries(values).map(([k, value]) => [k, { value, confidence: 0.9, observedAt, source: 'vision-model' as const, conflicted: false }])),
  activeSignals: [],
  confidence: 0.9,
  stale: false,
  staleReasons: [],
})

test('benchmarks: one reading is never stated; readings must agree, count once, and be possible', () => {
  const spec = aoe4.benchmarks![0]
  assert.equal(agreed([{ value: 27, dist: 0 }]), null)
  assert.equal(agreed([{ value: 27, dist: 4 }, { value: 41, dist: 1 }]), null, 'two readings that disagree')
  assert.equal(agreed([{ value: 35, dist: 9 }, { value: 36, dist: 2 }, { value: 12, dist: 0 }]), 36, 'the outlier is dropped')

  const t = new BenchmarkTracker([spec])
  // A single misread (the sum of the per-resource worker counts) lingering in the state for several ticks.
  const misread = at(1000, { villagers: 27, population: 52, gameTimeSec: 598 })
  for (let i = 0; i < 4; i++) t.observe(misread)
  assert.equal(t.takeFresh().length, 0)
  assert.equal(t.results()[0].points.length, 0)
  // More villagers than population is impossible: ignored.
  t.observe(at(2000, { villagers: 61, population: 52, gameTimeSec: 601 }))
  t.observe(at(3000, { villagers: 61, population: 52, gameTimeSec: 604 }))
  assert.equal(t.results()[0].points.length, 0)
  // Two fresh readings of the one villager count that agree: stated once.
  t.observe(at(4000, { villagers: 36, population: 52, gameTimeSec: 607 }))
  t.observe(at(5000, { villagers: 37, population: 53, gameTimeSec: 610 }))
  const [p] = t.results()[0].points
  assert.deepEqual([p.atSec, p.actual, p.behind], [600, 36, false])
  assert.equal(t.takeFresh().length, 1)
  t.observe(at(6000, { villagers: 37, population: 53, gameTimeSec: 612 }))
  assert.equal(t.takeFresh().length, 0)
})

test('AoE4: floating is judged against what the age costs, and no advice asks for more Town Centers', () => {
  const floats = aoe4.mistakes!.filter((m) => m.id.startsWith('floating'))
  const bank = (id: string) => (floats.find((m) => m.id === id)!.when.find((c) => 'sum' in c)! as { value: number }).value
  const age = (id: string) => (floats.find((m) => m.id === id)!.when.find((c) => 'key' in c && c.key === 'age')! as { value: string }).value
  assert.deepEqual(floats.map((m) => [age(m.id), bank(m.id)]), [['dark', 1000], ['feudal', 2200], ['castle', 4000], ['imperial', 3000]])
  // Each age's bank is above what the next age costs (Feudal 600, Castle 1,800, Imperial 3,600).
  assert.ok(bank('floating-dark') > 600 && bank('floating-resources') > 1800 && bank('floating-castle') > 3600)
  // What the coach tells the player to do (not what it says about the opponent's build).
  const k = aoe4.knowledge
  const factions = k.roster!.factions
  const plans = factions.flatMap((f) => f.agePlans ?? [])
  const advice = [
    ...plans.flatMap((a) => a.playAs ?? []),
    ...factions.map((f) => f.howToPlayAgainst ?? ''),
    ...k.concepts.map((c) => c.summary),
    ...k.playbook!.flatMap((e) => [e.fix, ...e.good]),
    ...(k.reference ?? []).flatMap((r) => r.items.map((i) => i.detail)),
    ...aoe4.mistakes!.map((m) => m.summary),
  ]
  const responses = plans.flatMap((a) => a.theirMoves.flatMap((m) => [m.punish ?? '', ...(m.response ?? [])]))
  for (const t of advice) assert.doesNotMatch(t, /\b[23] ?TCs?\b|another Town Cent|two or three Town|For reference, strong players/i, t)
  for (const t of [...advice, ...responses]) assert.doesNotMatch(t, /out-?boom|out-?expand|go [23] ?TC/i, t)
  assert.equal(aoe4.benchmarks![0].atMostKey, 'population')
})

// --- Packs and playbooks ----------------------------------------------------------------------

test('playbooks: every pack entry is sourced, linked findings resolve, and the prompt text keeps entries whole', () => {
  for (const p of [mw4, aoe4, fc]) {
    const book = p.knowledge.playbook!
    assert.ok(book.length >= 20, `${p.id}: ${book.length} entries`)
    for (const e of book) {
      assert.ok(e.sources.length >= 1 && e.sources.every((s) => s.url.startsWith('https://')), e.id)
      assert.doesNotMatch(`${e.principle} ${e.fix}`, BANNED_CLAIMS)
    }
    const text = playbookText(book, 4000)
    assert.ok(text.length <= 4000)
    assert.ok(text.trimEnd().endsWith(')'), 'the last entry is complete')
  }
  assert.ok(mw4.knowledge.playbook!.some((e) => e.sources.some((s) => s.kind === 'pro')))
  assert.match(fc.game.patch, /FC 27/)
})

test('playbooks: links to unknown entries and mixed factor kinds are rejected', () => {
  const base = JSON.parse(JSON.stringify(mw4)) as Record<string, unknown> & { factors: Record<string, unknown>[] }
  base.factors[0] = { ...base.factors[0], values: [{ value: 'in_open', label: 'Open', phrase: 'in the open', playbook: 'nope' }] }
  base.factors[1] = { ...base.factors[1], afterEvent: { event: 'kill', withinMs: 8000 } }
  const r = validateProfile(base)
  assert.equal(r.ok, false)
  const errors = r.ok ? '' : r.errors.join('\n')
  assert.match(errors, /unknown playbook entry "nope"/)
  assert.match(errors, /either attribute or afterEvent/)
})

test('FC: conceded goals can be read for the first defensive mistake, linked to the fix', () => {
  const f = fc.factors!.find((x) => x.id === 'conceded-mistake')!
  assert.equal(f.lossEvent, 'goal_conceded')
  const r = factorResult(f, counts({ back_post_unmarked: [3, 0], line_broken: [1, 0], none_visible: [1, 0] }))
  const top = r.findings[0]
  assert.equal(top.statement, '3 of your 5 goals conceded came from an unmarked far-post runner.')
  assert.equal(top.playbook, 'back-post-coverage')
})

// --- The coach's review -----------------------------------------------------------------------

test('review: built locally from the findings, three focus points with fixes, read aloud without causal words', async () => {
  const { localReview, reviewSpeech } = await import('../src/core/review.ts')
  const engine = engineFor(mw4, settings({ profileId: 'cod-mw4', profileModeId: 'multiplayer', mode: 'post-match' }))
  play(engine, mw4MatchReplay({ seed: 2 }))
  const r = localReview(mw4, engine.report())!
  assert.equal(r.author, 'local')
  assert.match(r.headline, /^Main thing to work on: 8 of your 12 deaths came while you were in the open\./)
  assert.ok(r.focus.length >= 2 && r.focus.length <= 3)
  assert.equal(r.focus[0].fix, fix(mw4, 'fight-from-cover'))
  assert.equal(new Set(r.focus.map((f) => f.playbook)).size, r.focus.length, 'each playbook entry once')
  assert.ok(r.strengths.some((s) => /close range/.test(s)))
  const spoken = reviewSpeech(r)
  assert.doesNotMatch(spoken, BANNED_CLAIMS)
  assert.ok(spoken.split(/\s+/).length < 160, `${spoken.split(/\s+/).length} words`)
})

test('review: nothing to say gives null; a model review is checked for size, wording and playbook links', async () => {
  const { localReview, validateReview } = await import('../src/core/review.ts')
  const engine = engineFor(mw4, settings({ profileId: 'cod-mw4', profileModeId: 'multiplayer', mode: 'post-match' }))
  assert.equal(localReview(mw4, engine.report()), null)
  const good = { headline: 'Most deaths came in the open.', strengths: [], focus: [{ evidence: '8 of 12 deaths in the open.', principle: 'Fight from cover.', fix: 'Take fights from cover.', drill: null, playbook: 'fight-from-cover' }], nextMatch: 'Cover first.' }
  assert.equal(validateReview(mw4, good)?.author, 'ai')
  assert.equal(validateReview(mw4, { ...good, focus: [{ ...good.focus[0], playbook: 'made-up' }] })?.focus[0].playbook, null)
  assert.equal(validateReview(mw4, { ...good, headline: 'You died because you were in the open.' }), null)
  assert.equal(validateReview(mw4, { ...good, headline: 'x'.repeat(500) }), null)
  assert.equal(validateReview(mw4, null), null)
})
