// The coach planner: turns evidence into candidate CoachingMessages. It decides
// *what* would be worth saying; the governor decides *whether* it may be said.
// Every message is built from templates filled with observed counts, and
// carries a one-line evidence summary.
//
// The planner can always choose silence: no review when there is nothing to
// learn, no tip without supporting evidence.

import { gradeAtLeast, GRADE_ORDER } from './evidence.ts'
import {
  heatmapLine,
  insightLine,
  insightStatement,
  liveCueText,
  matchSummaryText,
  mistakeLine,
  roundReviewText,
  scoreLine,
  statLine,
  type RoundRecord,
} from './language.ts'
import type { HeatmapResult } from './analyses/heatmap.ts'
import type { MatchRecord } from './analyses/matchRecorder.ts'
import type { MistakeTotals } from './analyses/mistakes.ts'
import type { EpisodeSpec, GameProfile, LiveCueSpec, MistakeSpec } from './profile/schema.ts'
import type { CoachTone, CoachingMessage, EvidenceGrade, GameEvent, Insight, RoundResult } from './types.ts'

const REFLECTIVE_TTL_MS = 20_000
const SUMMARY_TTL_MS = 120_000

type NewMessage = Omit<CoachingMessage, 'id'>

export function liveCueMessage(
  tone: CoachTone,
  cue: LiveCueSpec,
  spec: EpisodeSpec,
  insight: Insight,
  ev: GameEvent,
  now: number,
): NewMessage {
  return {
    category: 'training',
    text: liveCueText(tone, cue, spec, insight),
    confidence: ev.confidence,
    grade: insight.grade,
    evidenceSummary: insightStatement(spec, insight),
    topic: `cue:${cue.id}`,
    priority: 'high',
    createdAt: now,
    basedOnObservationAt: ev.at,
    expiresAtMs: ev.at + cue.maxAgeMs,
    author: 'template',
  }
}

/** A live tip for a visible mistake (e.g. "You're housed"). Direct observation, not a statistical pattern. */
export function mistakeTipMessage(spec: MistakeSpec, ev: GameEvent, freshnessMs: number, now: number): NewMessage | null {
  if (!spec.liveTip) return null
  const grade: EvidenceGrade = ev.confidence >= 0.8 ? 'strong' : ev.confidence >= 0.6 ? 'moderate' : 'weak'
  return {
    category: 'training',
    text: spec.liveTip,
    confidence: ev.confidence,
    grade,
    evidenceSummary: `Seen on screen: ${ev.evidence}.`,
    topic: `mistake:${spec.id}`,
    priority: spec.severity === 'high' ? 'high' : 'normal',
    createdAt: now,
    basedOnObservationAt: ev.at,
    expiresAtMs: ev.at + freshnessMs,
    author: 'template',
  }
}

export interface ReviewInputs {
  readonly tone: CoachTone
  readonly profile: GameProfile
  readonly insights: readonly Insight[]
  readonly heatmaps: readonly HeatmapResult[]
  readonly mistakes: Readonly<Record<string, MistakeTotals>>
  /** Mistake ids or episode ids queued from live play since the last review. */
  readonly queuedTopics: readonly string[]
  readonly reviewLines: number
  readonly minGrade: EvidenceGrade
}

function insightLines(i: ReviewInputs, filter: (insight: Insight) => boolean): { lines: string[]; topics: string[]; best: EvidenceGrade | null } {
  const episodes = new Map(i.profile.episodes.map((e) => [e.id, e]))
  const chosen = [...i.insights]
    .filter((x) => gradeAtLeast(x.grade, i.minGrade) && filter(x))
    .sort((a, b) => GRADE_ORDER[b.grade] - GRADE_ORDER[a.grade] || b.probability - a.probability)
    .slice(0, i.reviewLines)
  return {
    lines: chosen.map((x) => insightLine(i.tone, episodes.get(x.episodeId)!, x)),
    topics: chosen.map((x) => x.key),
    best: chosen[0]?.grade ?? null,
  }
}

function heatmapLines(i: ReviewInputs, max: number): { lines: string[]; topics: string[]; best: EvidenceGrade | null } {
  // Any claim of moderate or better ranks by the pack's own order (authors list the most important first).
  const order = (h: HeatmapResult) => (gradeAtLeast(h.claim!.grade, 'moderate') ? 0 : 1) * 100 + i.heatmaps.indexOf(h)
  const ranked = i.heatmaps
    .filter((h) => h.claim && gradeAtLeast(h.claim.grade, i.minGrade))
    .sort((a, b) => order(a) - order(b))
    .slice(0, max)
  return {
    lines: ranked.map((h) => heatmapLine(i.tone, h.spec, h.claim!, h.topBreakdown)),
    topics: ranked.map((h) => `zone:${h.spec.id}:${h.claim!.cell}`),
    best: ranked[0]?.claim!.grade ?? null,
  }
}

function mistakeLines(i: ReviewInputs, onlyQueued: boolean, max: number): { lines: string[]; topics: string[] } {
  const severity = { high: 2, medium: 1, low: 0 }
  const specs = (i.profile.mistakes ?? [])
    .filter((m) => (i.mistakes[m.id]?.count ?? 0) > 0)
    .filter((m) => !onlyQueued || i.queuedTopics.includes(`mistake:${m.id}`))
    .sort((a, b) => severity[b.severity] - severity[a.severity] || (i.mistakes[b.id].totalMs - i.mistakes[a.id].totalMs))
    .slice(0, max)
  return { lines: specs.map((m) => mistakeLine(m.summary, i.mistakes[m.id])), topics: specs.map((m) => `mistake:${m.id}`) }
}

/** A short reflective note at a visible round/half end, or null when there is nothing worth saying. */
export function roundReviewMessage(
  i: ReviewInputs & { result: RoundResult; roundEpisodeIds: ReadonlySet<string>; at: number; confidence: number },
  now: number,
): NewMessage | null {
  const queuedEpisodes = new Set(
    i.queuedTopics.map((t) => i.profile.liveCues.find((c) => `cue:${c.id}` === t)?.episode).filter((x): x is string => !!x),
  )
  const ins = insightLines(i, (x) => i.roundEpisodeIds.has(x.episodeId) || queuedEpisodes.has(x.episodeId))
  const mis = mistakeLines(i, true, 1)
  const zones = ins.lines.length ? { lines: [], topics: [], best: null } : heatmapLines(i, 1)
  const lines = [...ins.lines, ...mis.lines, ...zones.lines]
  // A bare "round won" every round is chatter; only speak when there is something to learn.
  if (lines.length === 0) return null
  const best = [ins.best, zones.best].filter((g): g is EvidenceGrade => !!g).sort((a, b) => GRADE_ORDER[b] - GRADE_ORDER[a])[0]
  return {
    category: 'reflective',
    text: roundReviewText(i.tone, i.result, lines),
    confidence: i.confidence,
    grade: best ?? 'moderate',
    evidenceSummary: [...ins.topics, ...mis.topics, ...zones.topics].join(', '),
    topic: [...ins.topics, ...mis.topics, ...zones.topics].join('+'),
    priority: 'normal',
    createdAt: now,
    basedOnObservationAt: i.at,
    expiresAtMs: now + REFLECTIVE_TTL_MS,
    author: 'template',
  }
}

export interface SummaryInputs extends ReviewInputs {
  readonly rounds: RoundRecord
  readonly score: { player: number; opponent: number } | null
  readonly stats: Readonly<Record<string, number>>
  /** Recent matches of the same pack (opt-in history), for "up/down from your recent" comparisons. */
  readonly history: readonly MatchRecord[]
  readonly limit: number
}

/** The post-match summary. Always produced when the match had content; the governor decides delivery. */
export function matchSummaryMessage(i: SummaryInputs, now: number): NewMessage {
  const ins = insightLines({ ...i, reviewLines: i.limit }, () => true)
  const zones = heatmapLines(i, Math.max(1, i.limit - ins.lines.length))
  const mis = mistakeLines(i, false, 2)
  const stats = statLines(i)
  const lines = [...zones.lines, ...mis.lines, ...ins.lines, ...stats]
  const opening = i.score ? [scoreLine(i.score)] : []
  const best = [ins.best, zones.best].filter((g): g is EvidenceGrade => !!g).sort((a, b) => GRADE_ORDER[b] - GRADE_ORDER[a])[0]
  return {
    category: 'post_event',
    text: matchSummaryText(i.tone, i.rounds, lines, opening),
    confidence: 1,
    grade: best ?? 'insufficient',
    evidenceSummary: `${[...ins.topics, ...zones.topics, ...mis.topics].join(', ') || 'no repeated patterns'}`,
    topic: 'match-summary',
    priority: 'normal',
    createdAt: now,
    basedOnObservationAt: null,
    expiresAtMs: now + SUMMARY_TTL_MS,
    author: 'template',
  }
}

/** At most two statistics, those that moved most against the player's recent matches (opt-in history). */
function statLines(i: SummaryInputs): string[] {
  const specs = i.profile.matchStats?.keys ?? []
  const rows = specs
    .filter((s) => i.stats[s.key] !== undefined)
    .map((s) => {
      const past = i.history.map((m) => m.stats[s.key]).filter((v): v is number => typeof v === 'number').slice(-5)
      const avg = past.length >= 2 ? past.reduce((a, b) => a + b, 0) / past.length : null
      const change = avg === null ? 0 : Math.abs(i.stats[s.key] - avg) / Math.max(1, Math.abs(avg))
      return { line: statLine(s.label, i.stats[s.key], s.unit, s.better, avg), change, better: s.better, hasHistory: avg !== null }
    })
    // A number with nothing to compare it with is not coaching; the stats are in the written review.
    .filter((r) => r.better !== 'neutral' && r.hasHistory)
    .sort((a, b) => b.change - a.change)
  return rows.slice(0, 2).map((r) => r.line)
}
