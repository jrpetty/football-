// The language renderer. Deterministic templates, not a language model: every
// sentence the coach can say is traceable to counts it actually observed, and
// the wording strength is tied to the evidence grade. Templates never use
// causal or absolute language ("caused", "because", "always", "definitely").

import type { EpisodeSpec, GameProfile, LiveCueSpec } from './profile/schema.ts'
import { NO_RESPONSE, type CoachTone, type Episode, type EvidenceGrade, type Insight, type RoundResult } from './types.ts'

export const WORD_LIMITS = { 'live-cue': 12, 'round-review': 45, 'match-summary': 90 } as const

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
  return fitWords([RESULT_LINE[tone][result] ?? '', ...lines], WORD_LIMITS['round-review'])
}

export interface MatchRecord {
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

export function matchSummaryText(tone: CoachTone, record: MatchRecord, lines: readonly string[]): string {
  const seen = record.won + record.lost + record.drawn
  const parts: string[] = ['Match summary.']
  if (seen > 0) {
    parts.push(
      `You won ${record.won} of ${seen} round${seen === 1 ? '' : 's'} I could read` +
        (record.drawn ? `, with ${record.drawn} drawn.` : '.'),
    )
  }
  if (record.unread > 0) parts.push(`I could not read the result of ${record.unread} round${record.unread === 1 ? '' : 's'}.`)
  if (lines.length === 0) {
    parts.push('I did not see enough repeated situations to call a pattern yet.')
    return fitWords([...parts, CLOSING[tone].withoutLines], WORD_LIMITS['match-summary'])
  }
  // The closing goes after the first detail line so the word cap trims extra detail, not the sign-off.
  return fitWords([...parts, lines[0], CLOSING[tone].withLines, ...lines.slice(1)], WORD_LIMITS['match-summary'])
}

/** One-line description of an episode for the timeline. */
export function episodeSummary(spec: EpisodeSpec, ep: Episode, profile: Pick<GameProfile, 'events'>): string {
  const outcome =
    ep.outcome === 'negative'
      ? spec.phrases.negativeShort
      : ep.outcome === 'positive'
        ? 'positive outcome'
        : 'no visible outcome in the window'
  const trigger = profile.events.find((e) => e.id === spec.trigger)?.label ?? spec.trigger
  return `${trigger} → ${responseLabel(spec, ep.response)} → ${outcome}`
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
