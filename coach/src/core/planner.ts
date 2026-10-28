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
import type { FactorFinding, FactorResult } from './analyses/factors.ts'
import type { BenchmarkResult } from './analyses/benchmarks.ts'
import type { BattleRecord } from './analyses/battles.ts'
import type { IntelReport } from './analyses/intel.ts'
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
  readonly factors: readonly FactorResult[]
  readonly benchmarks: readonly BenchmarkResult[]
  readonly battles: readonly BattleRecord[]
  readonly intel: IntelReport | null
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
    lines: ranked.map((h) =>
      [heatmapLine(i.tone, h.spec, h.claim!, h.topBreakdown), adviceFor(i.profile, (h.topBreakdown && h.spec.breakdown?.playbook?.[h.topBreakdown.value]) || null)]
        .filter(Boolean)
        .join(' '),
    ),
    topics: ranked.map((h) => `zone:${h.spec.id}:${h.claim!.cell}`),
    best: ranked[0]?.claim!.grade ?? null,
  }
}

/** Each playbook fix once: a later line that repeats one keeps its finding and drops the repeat. */
function withoutRepeatedAdvice(profile: GameProfile, lines: readonly string[]): string[] {
  const fixes = (profile.knowledge.playbook ?? []).map((p) => p.fix)
  const said = new Set<string>()
  return lines.map((line) => {
    let out = line
    for (const f of fixes) {
      if (!out.includes(f)) continue
      if (said.has(f)) out = out.replace(` ${f}`, '').replace(f, '').trim()
      else said.add(f)
    }
    return out
  })
}

/** The pack's fix for a finding, so a count always comes with what to do about it. */
export function adviceFor(profile: GameProfile, playbookId: string | null): string | null {
  return playbookId ? (profile.knowledge.playbook?.find((p) => p.id === playbookId)?.fix ?? null) : null
}

/** Fight-factor findings, weaknesses first, each with the playbook's fix. */
export function factorFindings(factors: readonly FactorResult[], minGrade: EvidenceGrade): FactorFinding[] {
  return factors
    .flatMap((f) => f.findings)
    .filter((f) => gradeAtLeast(f.grade, minGrade))
    .sort((a, b) => (a.kind === b.kind ? 0 : a.kind === 'weakness' ? -1 : 1) || GRADE_ORDER[b.grade] - GRADE_ORDER[a.grade] || b.count / b.of - a.count / a.of)
}

function factorLines(i: ReviewInputs, max: number): { lines: string[]; topics: string[]; best: EvidenceGrade | null; strength: string | null } {
  const all = factorFindings(i.factors, i.minGrade)
  const weak = all.filter((f) => f.kind === 'weakness').slice(0, max)
  const strength = all.find((f) => f.kind === 'strength' && gradeAtLeast(f.grade, 'moderate'))
  return {
    lines: weak.map((f) => [f.statement, adviceFor(i.profile, f.playbook)].filter(Boolean).join(' ')),
    topics: weak.map((f) => `factor:${f.factorId}:${f.value}`),
    best: weak[0]?.grade ?? null,
    strength: strength ? `Good: ${strength.statement[0].toLowerCase()}${strength.statement.slice(1)}` : null,
  }
}

/** "Fights I could follow: 2 won, 3 lost." (unclear ones are not called either way). */
function battleLine(battles: readonly BattleRecord[]): string[] {
  const won = battles.filter((b) => b.outcome === 'won').length
  const lost = battles.filter((b) => b.outcome === 'lost').length
  if (won + lost === 0) return []
  return [`Fights I could follow: ${won} won, ${lost} lost.`]
}

/** "You mostly met Knights (8 at once); Spearmen and Crossbowmen beat them." */
function intelLines(intel: IntelReport | null): string[] {
  const top = intel?.counters[0]
  const seen = intel?.units[0]
  if (!top || !seen || seen.count < 3) return []
  return [`You mostly met ${seen.name} (${seen.count} at once)${top.build.length ? `; ${top.build.join(' and ')} beat them` : ''}.`]
}

function benchmarkLines(i: ReviewInputs, max: number): { lines: string[]; topics: string[] } {
  const behind = i.benchmarks.filter((b) => b.worst).slice(0, max)
  return {
    lines: behind.map((b) => [b.worst!.statement, adviceFor(i.profile, b.spec.playbook ?? null)].filter(Boolean).join(' ')),
    topics: behind.map((b) => `benchmark:${b.spec.id}`),
  }
}

function mistakeLines(i: ReviewInputs, onlyQueued: boolean, max: number): { lines: string[]; topics: string[] } {
  const severity = { high: 2, medium: 1, low: 0 }
  const specs = (i.profile.mistakes ?? [])
    .filter((m) => (i.mistakes[m.id]?.count ?? 0) > 0)
    .filter((m) => !onlyQueued || i.queuedTopics.includes(`mistake:${m.id}`))
    .sort((a, b) => severity[b.severity] - severity[a.severity] || (i.mistakes[b.id].totalMs - i.mistakes[a.id].totalMs))
    .slice(0, max)
  return {
    lines: specs.map((m) => [mistakeLine(m.summary, i.mistakes[m.id]), adviceFor(i.profile, m.playbook ?? null)].filter(Boolean).join(' ')),
    topics: specs.map((m) => `mistake:${m.id}`),
  }
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
  const facts = factorLines(i, i.limit)
  const bench = benchmarkLines(i, 1)
  const ins = insightLines({ ...i, reviewLines: Math.max(0, i.limit - facts.lines.length) }, () => true)
  const zones = heatmapLines(i, Math.max(1, i.limit - ins.lines.length - facts.lines.length))
  const mis = mistakeLines(i, false, 2)
  const stats = statLines(i)
  // Most actionable first: how fights are lost, the economy against its benchmark, where things happen, then the rest.
  const lines = withoutRepeatedAdvice(i.profile, [...facts.lines, ...intelLines(i.intel), ...bench.lines, ...zones.lines, ...mis.lines, ...ins.lines, ...stats])
  const opening = [...(i.score ? [scoreLine(i.score)] : []), ...battleLine(i.battles), ...(facts.strength ? [facts.strength] : [])]
  const best = [ins.best, zones.best, facts.best].filter((g): g is EvidenceGrade => !!g).sort((a, b) => GRADE_ORDER[b] - GRADE_ORDER[a])[0]
  return {
    category: 'post_event',
    text: matchSummaryText(i.tone, i.rounds, lines, opening),
    confidence: 1,
    grade: best ?? 'insufficient',
    evidenceSummary: `${[...facts.topics, ...bench.topics, ...ins.topics, ...zones.topics, ...mis.topics].join(', ') || 'no repeated patterns'}`,
    topic: 'match-summary',
    priority: 'normal',
    createdAt: now,
    basedOnObservationAt: null,
    expiresAtMs: now + SUMMARY_TTL_MS,
    author: 'template',
  }
}

/**
 * At most two statistics: those that moved most against the player's recent
 * matches (opt-in history), and any on the wrong side of the pack's warning
 * line, which comes with its fix. One line per statistic.
 */
function statLines(i: SummaryInputs): string[] {
  const specs = i.profile.matchStats?.keys ?? []
  const rows = specs
    .filter((s) => i.stats[s.key] !== undefined && s.better !== 'neutral')
    .map((s) => {
      const v = i.stats[s.key]
      const past = i.history.map((m) => m.stats[s.key]).filter((x): x is number => typeof x === 'number').slice(-5)
      const avg = past.length >= 2 ? past.reduce((a, b) => a + b, 0) / past.length : null
      const change = avg === null ? 0 : Math.abs(v - avg) / Math.max(1, Math.abs(avg))
      const warned = !!s.warn && (s.better === 'higher' ? v < s.warn.at : v > s.warn.at)
      const unit = s.unit ?? ''
      const head = avg !== null ? statLine(s.label, v, s.unit, s.better, avg) : `${s.label} ${Math.round(v * 100) / 100}${unit}.`
      const warning = warned ? ` That is ${s.better === 'higher' ? 'under' : 'over'} ${s.warn!.at}${unit}, ${s.warn!.note}. ${adviceFor(i.profile, s.warn!.playbook ?? null) ?? ''}` : ''
      return { line: `${head}${warning}`.trim(), rank: (warned ? 10 : 0) + change, show: warned || avg !== null }
    })
    // A number with nothing to compare it with is not coaching; the stats are in the written review.
    .filter((r) => r.show)
    .sort((a, b) => b.rank - a.rank)
  return rows.slice(0, 2).map((r) => r.line)
}
