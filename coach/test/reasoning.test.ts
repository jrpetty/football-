// Profiles, episodes, evidence, pattern memory and language.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { EpisodeEvaluator } from '../src/core/episodes.ts'
import { evaluateEpisode, grade, probabilityWorse, wilson, normalCdf } from '../src/core/evidence.ts'
import { countWords, insightLine, insightStatement, liveCueText, matchSummaryText, WORD_LIMITS } from '../src/core/language.ts'
import { HISTORY_CAP, isPatternSnapshot, PatternMemory } from '../src/core/patterns.ts'
import { modeAllowsLiveCoaching, validateProfile, type ProfileMode } from '../src/core/profile/schema.ts'
import { TONES } from '../src/core/settings.ts'
import { NO_RESPONSE, type EvidenceGrade, type GameEvent, type Insight, type OutcomeCounts, type SignalState } from '../src/core/types.ts'
import { arena } from './helpers.ts'

// --- Profiles ------------------------------------------------------------------

test('the reference profile validates', () => {
  const r = validateProfile(structuredClone(arena))
  assert.ok(r.ok, r.ok ? '' : r.errors.join('\n'))
})

test('profile validation catches broken references, regions and hysteresis', () => {
  const p = structuredClone(arena) as any
  p.signals[0].region.x = 0.9 // extends past the right edge
  p.signals[1].off = 0.9 // off above on
  p.events[0].signal = 'nope'
  p.episodes[0].responses[0].id = 'none'
  p.liveCues[0].recommend = 'jump'
  const r = validateProfile(p)
  assert.equal(r.ok, false)
  const errors = r.ok ? '' : r.errors.join('\n')
  for (const expected of ['right edge', 'hysteresis', 'unknown signal "nope"', 'reserved', '"jump" is not a response']) {
    assert.ok(errors.includes(expected), `missing error about ${expected}:\n${errors}`)
  }
})

test('"approved" live coaching must cite a source and a review date', () => {
  const p = structuredClone(arena) as any
  p.modes[2].liveCoaching = { status: 'approved' }
  const r = validateProfile(p)
  assert.ok(!r.ok && r.errors.some((e) => e.includes('requires a cited source')))
})

test('live coaching is allowed only for single-player non-competitive modes or documented approval', () => {
  const mode = (over: Partial<ProfileMode>): ProfileMode => ({
    id: 'm', label: 'M', multiplayer: true, competitive: true, liveCoaching: { status: 'not-reviewed' }, ...over,
  })
  assert.equal(modeAllowsLiveCoaching(null), false)
  assert.equal(modeAllowsLiveCoaching(mode({})), false)
  assert.equal(modeAllowsLiveCoaching(mode({ competitive: false })), false, 'casual multiplayer still has other players')
  assert.equal(modeAllowsLiveCoaching(mode({ competitive: false, multiplayer: false })), true)
  assert.equal(
    modeAllowsLiveCoaching(mode({ competitive: false, multiplayer: false, liveCoaching: { status: 'prohibited' } })),
    false,
  )
  assert.equal(
    modeAllowsLiveCoaching(mode({ liveCoaching: { status: 'approved', source: 'https://example.com/policy', reviewedAt: '2026-10-01' } })),
    true,
  )
})

// --- Episodes --------------------------------------------------------------------

const lowSpec = arena.episodes.find((e) => e.id === 'low-health')!
let uid = 0
function ev(ruleId: string, at: number, extra: Partial<GameEvent> = {}): GameEvent {
  const rule = arena.events.find((e) => e.id === ruleId)
  return {
    uid: `${ruleId}#${++uid}`, ruleId, label: rule?.label ?? ruleId, category: rule?.category ?? 'context',
    at, confidence: 0.9, evidence: '', ...(rule?.boundary ? { boundary: rule.boundary, result: rule.result } : {}), ...extra,
  }
}
const visible = new Map<string, SignalState>([['health', { id: 'health', known: true, kind: 'number', value: 0.5, confidence: 0.9, observedAt: 0 }]])
const hidden = new Map<string, SignalState>([['health', { id: 'health', known: false, lastKnownAt: 0 }]])

test('an episode records the first visible response before the outcome', () => {
  const e = new EpisodeEvaluator([lowSpec])
  e.update(0, [ev('health-low', 0)], visible)
  e.update(800, [ev('healed', 800)], visible)
  const [ep] = e.update(2000, [ev('eliminated', 2000)], visible)
  assert.equal(ep.response, 'healed')
  assert.equal(ep.outcome, 'negative')
  assert.equal(ep.excluded, null)
})

test('no response then window expiry closes as neutral', () => {
  const e = new EpisodeEvaluator([lowSpec])
  e.update(0, [ev('health-low', 0)], visible)
  assert.equal(e.update(3000, [], visible).length, 0)
  const [ep] = e.update(lowSpec.windowMs, [], visible)
  assert.equal(ep.response, NO_RESPONSE)
  assert.equal(ep.outcome, 'neutral')
})

test('episodes cut short by a round end, hidden HUD or session end are excluded from evidence', () => {
  const e = new EpisodeEvaluator([lowSpec])
  e.update(0, [ev('health-low', 0)], visible)
  const [cut] = e.update(1000, [ev('round-won', 1000)], visible)
  assert.equal(cut.excluded, 'truncated-by-boundary')

  e.update(10_000, [ev('health-low', 10_000)], visible)
  e.update(11_000, [], hidden)
  e.update(13_000, [], hidden)
  const [dark] = e.update(14_000, [ev('eliminated', 14_000)], hidden)
  assert.equal(dark.excluded, 'low-visibility')

  e.update(20_000, [ev('health-low', 20_000)], visible)
  const [ended] = e.closeAll(21_000, 'session-ended')
  assert.equal(ended.excluded, 'session-ended')

  const mem = new PatternMemory(arena)
  for (const ep of [cut, dark, ended]) mem.record(ep)
  assert.equal(mem.counts('low-health').counts.size, 0)
})

test('an elimination on the same frame as the round-end banner still counts as the outcome', () => {
  const e = new EpisodeEvaluator([lowSpec])
  e.update(0, [ev('health-low', 0)], visible)
  const [ep] = e.update(1500, [ev('round-lost', 1500), ev('eliminated', 1500)], visible)
  assert.equal(ep.outcome, 'negative')
  assert.equal(ep.excluded, null)
})

// --- Evidence --------------------------------------------------------------------

const c = (n: number, negative: number): OutcomeCounts => ({ n, negative, positive: 0 })

test('statistics helpers', () => {
  assert.ok(Math.abs(normalCdf(0) - 0.5) < 1e-6)
  assert.ok(Math.abs(normalCdf(1.96) - 0.975) < 1e-3)
  const w = wilson(5, 10)
  assert.ok(w.lo > 0.2 && w.lo < 0.25 && w.hi > 0.75 && w.hi < 0.8)
  assert.ok(probabilityWorse(c(10, 8), c(10, 2)) > 0.99)
  assert.ok(Math.abs(probabilityWorse(c(10, 5), c(10, 5)) - 0.5) < 1e-9)
})

test('grades need support on both sides; one bad outcome is never a pattern', () => {
  const g = (w: OutcomeCounts, b: OutcomeCounts): EvidenceGrade => grade(w, b, probabilityWorse(w, b))
  assert.equal(g(c(1, 1), c(1, 0)), 'insufficient')
  assert.equal(g(c(3, 1), c(5, 0)), 'weak')
  assert.equal(g(c(4, 2), c(5, 0)), 'weak', 'tiny samples stay weak even with a big gap')
  assert.equal(g(c(8, 5), c(5, 0)), 'moderate')
  assert.equal(g(c(10, 7), c(8, 0)), 'strong')
  assert.equal(g(c(40, 12), c(40, 10)), 'insufficient', 'a small difference is not a finding')
})

test('the best comparison is chosen and absent responses are ignored', () => {
  const counts = new Map([
    ['none', c(8, 6)],
    ['healed', c(7, 1)],
  ])
  const i = evaluateEpisode(lowSpec, counts, false)!
  assert.equal(i.worse.response, 'none')
  assert.equal(i.better.response, 'healed')
  assert.equal(evaluateEpisode(lowSpec, new Map([['none', c(9, 7)]]), false), null)
})

// --- Pattern memory ----------------------------------------------------------------

test('pattern memory merges compatible history and refuses a different major version', () => {
  const mem = new PatternMemory(arena)
  mem.record({ uid: 'a', episodeId: 'low-health', triggerUid: 't', startedAt: 0, endedAt: 1, response: 'none', outcome: 'negative', outcomeEventUid: 'x', confidence: 1, excluded: null })
  const snap = mem.snapshot(new Date('2026-10-27T00:00:00Z'))
  assert.ok(isPatternSnapshot(snap))
  assert.deepEqual(snap.episodes['low-health'].none, { n: 1, negative: 1, positive: 0 })
  assert.equal(JSON.stringify(snap).includes('startedAt'), false, 'no timing data is persisted')

  const next = new PatternMemory(arena, snap)
  assert.equal(next.historyNotice, null)
  assert.equal(next.counts('low-health').usesHistory, true)

  const patched = new PatternMemory({ ...arena, version: '2.0.0' }, snap)
  assert.match(patched.historyNotice ?? '', /new game patch/)
  assert.equal(patched.counts('low-health').counts.size, 0)
})

test('history is capped so recent play dominates', () => {
  const big = {
    schemaVersion: 1 as const, profileId: arena.id, profileVersion: arena.version, updatedAt: '', sessions: 9,
    episodes: { 'low-health': { none: c(300, 150), healed: c(300, 30) } },
  }
  const snap = new PatternMemory(arena, big).snapshot(new Date())
  const total = Object.values(snap.episodes['low-health']).reduce((s, x) => s + x.n, 0)
  assert.ok(total <= HISTORY_CAP)
  assert.equal(snap.sessions, 10)
})

test('malformed snapshots are rejected', () => {
  assert.equal(isPatternSnapshot({ schemaVersion: 1, profileId: 'x', profileVersion: '1.0.0', updatedAt: '', sessions: 1, episodes: { e: { none: { n: 1, negative: 5, positive: 0 } } } }), false)
  assert.equal(isPatternSnapshot(null), false)
})

// --- Language ------------------------------------------------------------------------

const BANNED = /\b(caused|causes|causing|because|definitely|always|never fails|guarantee[sd]?|proves?|certainly)\b/i

function insight(g: EvidenceGrade): Insight {
  return {
    key: 'k', episodeId: 'low-health', worse: { response: 'none', counts: c(8, 6) }, better: { response: 'healed', counts: c(6, 1) },
    grade: g, probability: 0.97, usesHistory: false,
  }
}

test('every tone renders every grade without causal or absolute language, within word limits', () => {
  const cue = arena.liveCues[0]
  for (const tone of TONES) {
    for (const g of ['weak', 'moderate', 'strong'] as const) {
      const line = insightLine(tone, lowSpec, insight(g))
      assert.doesNotMatch(line, BANNED, line)
      assert.match(line, /6 of 8/, 'counts are stated')
      if (g === 'weak') assert.match(line, /^Early sign/)
      assert.ok(countWords(line) <= WORD_LIMITS['round-review'], line)
    }
    const live = liveCueText(tone, cue, lowSpec, insight('strong'))
    assert.ok(countWords(live) <= WORD_LIMITS['live-cue'], live)
    assert.doesNotMatch(live, BANNED)
    const summary = matchSummaryText(tone, { won: 3, lost: 2, drawn: 0, unread: 1 }, [insightLine(tone, lowSpec, insight('strong'))])
    assert.ok(countWords(summary) <= WORD_LIMITS['match-summary'], summary)
    assert.doesNotMatch(summary, BANNED)
  }
  assert.match(insightStatement(lowSpec, insight('moderate')), /Moderate evidence/)
})

test('tones actually differ', () => {
  const lines = new Set(TONES.map((t) => insightLine(t, lowSpec, insight('strong'))))
  assert.equal(lines.size, TONES.length)
})
