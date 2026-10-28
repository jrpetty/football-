// The language renderer. Deterministic templates, not a language model: every
// sentence the coach can say is traceable to counts it actually observed, and
// the wording strength is tied to the evidence grade. Templates never use
// causal or absolute language ("caused", "because", "always", "definitely").

import type { EpisodeSpec, LiveCueSpec } from './profile/schema.ts'
import { NO_RESPONSE, type CoachTone, type Episode, type EvidenceGrade, type Insight, type RoundResult } from './types.ts'

/** Maximum words per message, by category. Live tips are short by construction. */
export const WORD_LIMITS = {
  training: 14,
  live_tactical: 14,
  descriptive: 25,
  commentary: 22,
  reflective: 45,
  post_event: 110,
  answer: 140,
} as const

const GRADE_LABEL: Record<EvidenceGrade, string> = {
  insufficient: 'Not enough',
  weak: 'Weak',
  moderate: 'Moderate',
  strong: 'Strong',
}

interface Wording {
  trigger: string
  worse: string
  better: string
  worseLabel: string
  betterLabel: string
  negative: string
  negativeShort: string
  kw: number
  nw: number
  kb: number
  nb: number
}

function wording(spec: EpisodeSpec, insight: Insight): Wording {
  const response = (id: string) =>
    id === NO_RESPONSE ? spec.noResponse : (spec.responses.find((r) => r.id === id) ?? spec.noResponse)
  const w = response(insight.worse.response)
  const b = response(insight.better.response)
  return {
    trigger: spec.phrases.trigger,
    worse: w.phrase,
    better: b.phrase,
    worseLabel: w.label,
    betterLabel: b.label,
    negative: spec.phrases.negative,
    negativeShort: spec.phrases.negativeShort,
    kw: insight.worse.counts.negative,
    nw: insight.worse.counts.n,
    kb: insight.better.counts.negative,
    nb: insight.better.counts.n,
  }
}

export function responseLabel(spec: EpisodeSpec, id: string): string {
  return id === NO_RESPONSE ? spec.noResponse.label : (spec.responses.find((r) => r.id === id)?.label ?? id)
}

/** Neutral written statement for the review timeline and tendencies view. */
export function insightStatement(spec: EpisodeSpec, insight: Insight): string {
  const w = wording(spec, insight)
  const history = insight.usesHistory ? ' Includes earlier sessions.' : ''
  return (
    `When ${w.trigger}: ${w.worseLabel} → ${w.negativeShort} ${w.kw} of ${w.nw}; ` +
    `${w.betterLabel} → ${w.kb} of ${w.nb}. ` +
    `${GRADE_LABEL[insight.grade]} evidence (${Math.round(insight.probability * 100)}% probability the rates differ).${history}`
  )
}

/** One spoken reflective line about an insight, in the chosen tone. */
export function insightLine(tone: CoachTone, spec: EpisodeSpec, insight: Insight): string {
  const w = wording(spec, insight)
  const weak = insight.grade === 'weak'
  const often = insight.grade === 'strong' ? ' consistently' : insight.grade === 'moderate' ? ' often' : ''
  let body: string
  switch (tone) {
    case 'gentle':
      body = `${weak ? '' : 'Something I noticed: '}when ${w.trigger} and ${w.worse}, ${w.negative}${often}, ${w.kw} of ${w.nw} times. When ${w.better}, it was ${w.kb} of ${w.nb}.`
      break
    case 'direct':
      body = `When ${w.trigger} and ${w.worse}, ${w.negative} ${w.kw} of ${w.nw} times. When ${w.better}: ${w.kb} of ${w.nb}.`
      break
    case 'analytical':
      body = `${w.trigger}: ${w.worseLabel}, ${w.negativeShort} ${w.kw} of ${w.nw}; ${w.betterLabel}, ${w.kb} of ${w.nb}. ${GRADE_LABEL[insight.grade]} evidence.`
      break
    case 'high-energy':
      body = `${weak ? '' : 'Big one! '}When ${w.trigger}, ${w.better} works for you: ${w.negativeShort} just ${w.kb} of ${w.nb}, versus ${w.kw} of ${w.nw} when ${w.worse}.`
      break
  }
  return weak ? `Early sign, not settled yet: ${lowerFirst(body)}` : cap(body)
}

/** A live cue. Short by construction; the governor still enforces the word limit. */
export function liveCueText(tone: CoachTone, cue: LiveCueSpec, spec: EpisodeSpec, insight: Insight): string {
  const w = wording(spec, insight)
  const imp = cap(cue.imperative)
  switch (tone) {
    case 'gentle':
      return `${imp}, that's gone better for you.`
    case 'direct':
      return `${imp}.`
    case 'analytical':
      return `${imp}: ${w.negativeShort} ${w.kw} of ${w.nw} otherwise.`
    case 'high-energy':
      return `${imp}, you've got this!`
  }
}

const RESULT_LINE: Record<CoachTone, Partial<Record<RoundResult, string>>> = {
  gentle: { win: 'Nice round.', loss: 'Tough round, that happens.', draw: 'Even round.' },
  direct: { win: 'Round won.', loss: 'Round lost.', draw: 'Round drawn.' },
  analytical: { win: 'Round result: win.', loss: 'Round result: loss.', draw: 'Round result: draw.' },
  'high-energy': { win: 'Round won, let’s go!', loss: 'Shake it off, next one is ours!', draw: 'Dead even!' },
}

export function roundReviewText(tone: CoachTone, result: RoundResult, lines: readonly string[]): string {
  return fitWords([RESULT_LINE[tone][result] ?? '', ...lines], WORD_LIMITS.reflective)
}

export interface RoundRecord {
  won: number
  lost: number
  drawn: number
  unread: number
}

const CLOSING: Record<CoachTone, { withLines: string; withoutLines: string }> = {
  gentle: { withLines: 'Good session. Pick one thing to try next time.', withoutLines: 'Good session.' },
  direct: { withLines: 'Pick one thing and work on it next match.', withoutLines: 'More rounds will sharpen the picture.' },
  analytical: {
    withLines: 'These are associations in what was visible, not proof of cause.',
    withoutLines: 'Sample sizes are still too small for conclusions.',
  },
  'high-energy': { withLines: 'Great session. Let’s run it back!', withoutLines: 'Let’s run it back!' },
}

export function matchSummaryText(tone: CoachTone, record: RoundRecord, lines: readonly string[], opening: readonly string[] = []): string {
  const seen = record.won + record.lost + record.drawn
  const parts: string[] = ['Match summary.', ...opening]
  // With a final score, round tallies add nothing (football halves are not "rounds").
  if (opening.length === 0) {
    if (seen + record.unread === 1 && seen === 1) {
      parts.push(`Result: ${record.won ? 'a win' : record.lost ? 'a loss' : 'a draw'}.`)
    } else if (seen > 0) {
      parts.push(
        `You won ${record.won} of ${seen} round${seen === 1 ? '' : 's'} I could read` + (record.drawn ? `, with ${record.drawn} drawn.` : '.'),
      )
      // Only worth saying when some results were read; a game without visible round results is not "unread".
      if (record.unread > 0) parts.push(`I could not read the result of ${record.unread} round${record.unread === 1 ? '' : 's'}.`)
    }
  }
  if (lines.length === 0) {
    parts.push('I did not see enough repeated situations to call a pattern yet.')
    return fitWords([...parts, CLOSING[tone].withoutLines], WORD_LIMITS.post_event)
  }
  // The closing goes after the first detail line so the word cap trims extra detail, not the sign-off.
  return fitWords([...parts, lines[0], CLOSING[tone].withLines, ...lines.slice(1)], WORD_LIMITS.post_event)
}

/** One-line description of an episode for the timeline. */
export function episodeSummary(spec: EpisodeSpec, ep: Episode, triggerLabel: string): string {
  const outcome =
    ep.outcome === 'negative'
      ? spec.phrases.negativeShort
      : ep.outcome === 'positive'
        ? 'positive outcome'
        : 'no visible outcome in the window'
  return `${triggerLabel} → ${responseLabel(spec, ep.response)} → ${outcome}`
}

/** Join whole sentences until the next one would exceed the word limit. Never cuts mid-sentence. */
export function fitWords(sentences: readonly string[], limit: number): string {
  const out: string[] = []
  let words = 0
  for (const s of sentences) {
    const t = s.trim()
    if (!t) continue
    const n = countWords(t)
    if (words + n > limit) continue
    out.push(t)
    words += n
  }
  return out.join(' ')
}

export function countWords(s: string): number {
  return s.split(/\s+/).filter(Boolean).length
}

function cap(s: string): string {
  return s.charAt(0).toUpperCase() + s.slice(1)
}

function lowerFirst(s: string): string {
  return s.charAt(0).toLowerCase() + s.slice(1)
}

// --- v2: zones, mistakes, scores and statistics -------------------------------------------

/** "box-left" style ids and "through_ball" style values, for speech. */
export function humanize(value: string): string {
  return value.replaceAll('_', ' ').replaceAll('-', ' ')
}

/** A spatial claim, worded by grade and tone. Zones are always described as approximate where sampled. */
export function heatmapLine(
  tone: CoachTone,
  h: { phrase: string; label: string; sampled: boolean },
  claim: { grade: EvidenceGrade; cellLabel: string; count: number; located: number },
  topBreakdown: { value: string; count: number } | null,
): string {
  const sampled = h.sampled ? ' (from sampled frames)' : ''
  const how = topBreakdown ? ` Most often: ${humanize(topBreakdown.value)}, ${topBreakdown.count} times.` : ''
  const k = claim.count
  const n = claim.located
  let body: string
  switch (tone) {
    case 'gentle':
      body = `Something to look at: ${k} of the ${n} ${h.phrase} came from ${claim.cellLabel}${sampled}.`
      break
    case 'direct':
      body = `${k} of the ${n} ${h.phrase} came from ${claim.cellLabel}${sampled}.`
      break
    case 'analytical':
      body = `${h.label}: ${claim.cellLabel}, ${k} of ${n} (${Math.round((k / n) * 100)}%)${sampled}. ${GRADE_LABEL[claim.grade]} evidence.`
      break
    case 'high-energy':
      body = `Hot spot: ${k} of ${n} ${h.phrase} came from ${claim.cellLabel}${sampled}!`
      break
  }
  return claim.grade === 'weak' ? `Early sign, not settled yet: ${lowerFirst(body)}${how}` : `${body}${how}`
}

export function formatDuration(ms: number): string {
  const s = Math.round(ms / 1000)
  if (s < 60) return `${s} seconds`
  const m = Math.floor(s / 60)
  const r = s % 60
  return r ? `${m} min ${r} s` : `${m} min`
}

/** Fill a pack's mistake summary template. */
export function mistakeLine(template: string, totals: { count: number; totalMs: number }): string {
  return template
    .replaceAll('{count} times', totals.count === 1 ? 'once' : totals.count === 2 ? 'twice' : `${totals.count} times`)
    .replaceAll('{count}', String(totals.count))
    .replaceAll('{total}', formatDuration(totals.totalMs))
}

export function scoreLine(score: { player: number; opponent: number }): string {
  const result = score.player > score.opponent ? 'a win' : score.player < score.opponent ? 'a loss' : 'a draw'
  return `Final score ${score.player}–${score.opponent}, ${result}.`
}

/** "Pass accuracy 78%, down from your recent 84%." */
export function statLine(
  label: string,
  value: number,
  unit: string | undefined,
  better: 'higher' | 'lower' | 'neutral',
  recentAverage: number | null,
): string {
  const fmt = (v: number) => `${Number.isInteger(v) ? v : v.toFixed(1)}${unit ?? ''}`
  if (recentAverage === null || better === 'neutral') return `${label} ${fmt(value)}.`
  const diff = value - recentAverage
  if (Math.abs(diff) < Math.max(1, Math.abs(recentAverage) * 0.03)) return `${label} ${fmt(value)}, in line with your recent ${fmt(recentAverage)}.`
  const direction = diff > 0 ? 'up' : 'down'
  return `${label} ${fmt(value)}, ${direction} from your recent ${fmt(recentAverage)}.`
}
