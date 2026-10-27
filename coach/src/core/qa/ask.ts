// "Ask the coach". Common questions are answered locally from the structured
// session data — no API key, no network, no invented numbers. Anything else
// is routed to Claude (if the player enabled cloud features) with a compact,
// structured digest: the model phrases an answer from that evidence, it does
// not observe the game.
//
// When the question is asked matters too: during restricted play the answer is
// held until the match ends (the governor decides, like any other message).

import type { HeatmapResult } from '../analyses/heatmap.ts'
import type { MatchRecord } from '../analyses/matchRecorder.ts'
import type { SessionReport } from '../engine.ts'
import { formatDuration, humanize, mistakeLine, responseLabel } from '../language.ts'
import type { GameProfile } from '../profile/schema.ts'

export type Intent =
  | { readonly kind: 'heatmap'; readonly heatmapId: string }
  | { readonly kind: 'mistakes' }
  | { readonly kind: 'summary' }
  | { readonly kind: 'patterns' }
  | { readonly kind: 'stats' }
  | { readonly kind: 'open' }

const HEATMAP_WORDS: readonly [RegExp, readonly string[]][] = [
  [/\b(concede|conceding|conceded|goals? against|let (?:in|goals))\b/i, ['conceded']],
  [/\bwhere (?:do|did) i score|\bmy goals\b|\bscor(?:e|ing) from\b/i, ['scored']],
  [/\b(lose|losing|lost) (?:the )?(?:ball|possession)\b|\bturnovers?\b|\bpass(?:es|ing)?\b.*\b(fail\w*|wrong|unsuccessful|intercepted|misplaced)\b|\b(misplaced|unsuccessful|failed)\b.*\bpass/i, ['turnovers']],
  [/\b(die|dying|died|deaths?|killed)\b/i, ['deaths']],
]

export function classify(question: string, profile: GameProfile): Intent {
  const q = question.toLowerCase()
  const heatmaps = new Set((profile.heatmaps ?? []).map((h) => h.id))
  for (const [re, ids] of HEATMAP_WORDS) {
    const id = ids.find((x) => heatmaps.has(x))
    if (id && re.test(q)) return { kind: 'heatmap', heatmapId: id }
  }
  if (/\b(mistakes?|wrong|bad habits?|what should i (?:work on|improve|fix)|improve)\b/.test(q)) return { kind: 'mistakes' }
  if (/\b(pass(?:ing)? accuracy|possession|shots|stats|statistics|trend)\b/.test(q)) return { kind: 'stats' }
  if (/\b(patterns?|tendenc(?:y|ies)|habits?)\b/.test(q)) return { kind: 'patterns' }
  if (/\b(how (?:did|am|was) i|how'?s it going|summary|recap|how did (?:the|that) (?:match|game) go)\b/.test(q)) return { kind: 'summary' }
  return { kind: 'open' }
}

/** A local answer from structured data, or null when the question needs the model. */
export function answerLocally(intent: Intent, profile: GameProfile, report: SessionReport, history: readonly MatchRecord[]): string | null {
  switch (intent.kind) {
    case 'heatmap': {
      const h = report.heatmaps.find((x) => x.spec.id === intent.heatmapId)
      return h ? heatmapAnswer(h) : null
    }
    case 'mistakes': {
      const lines = report.mistakes
        .filter((m) => m.totals.count > 0)
        .sort((a, b) => b.totals.totalMs - a.totals.totalMs)
        .slice(0, 3)
        .map((m) => mistakeLine(m.spec.summary, m.totals))
      const pattern = report.insights.find((i) => i.verdict === null && (i.insight.grade === 'moderate' || i.insight.grade === 'strong'))
      if (pattern) lines.push(pattern.statement)
      const claims = report.heatmaps.filter((h) => h.claim && h.claim.grade !== 'weak').slice(0, 1).map(heatmapAnswer)
      lines.push(...claims)
      if (lines.length === 0) return 'I have not seen a repeated mistake yet this session. I only count what is visible on screen, so keep playing and ask again later.'
      return `This session: ${lines.join(' ')}`
    }
    case 'summary': {
      const parts: string[] = []
      if (report.score) parts.push(`The score I last read was ${report.score.player}–${report.score.opponent}.`)
      const seen = report.record.won + report.record.lost + report.record.drawn
      if (seen > 1) parts.push(`You won ${report.record.won} of ${seen} rounds I could read.`)
      if (report.matches.length) {
        const wins = report.matches.filter((m) => m.result === 'win').length
        parts.push(`Matches this session: ${report.matches.length}, ${wins} won.`)
      }
      const top = report.heatmaps.find((h) => h.claim)
      if (top) parts.push(heatmapAnswer(top))
      const worst = report.mistakes.filter((m) => m.totals.count > 0).sort((a, b) => b.totals.totalMs - a.totals.totalMs)[0]
      if (worst) parts.push(mistakeLine(worst.spec.summary, worst.totals))
      return parts.length ? parts.join(' ') : 'Not much to report yet: I have not seen enough of this session to summarise it.'
    }
    case 'patterns': {
      const usable = report.insights.filter((i) => i.verdict === null && i.insight.grade !== 'insufficient')
      if (!usable.length) return 'No patterns yet. I need to see the same situation several times, with different responses, before I can compare them.'
      return usable.slice(0, 2).map((i) => i.statement).join(' ')
    }
    case 'stats': {
      const recent = [...history, ...report.matches].filter((m) => m.gameProfileId === profile.id && Object.keys(m.stats).length).slice(-5)
      if (!recent.length) return 'I have no match statistics yet. They come from the end-of-match statistics screen, so leave it on screen for a few seconds.'
      const keys = profile.matchStats?.keys ?? []
      const lines = keys
        .map((k) => {
          const vals = recent.map((m) => m.stats[k.key]).filter((v): v is number => typeof v === 'number')
          if (!vals.length) return null
          const last = vals[vals.length - 1]
          return vals.length > 1 ? `${k.label}: ${fmt(last, k.unit)} last match, ${fmt(avg(vals), k.unit)} on average over ${vals.length}.` : `${k.label}: ${fmt(last, k.unit)}.`
        })
        .filter((x): x is string => !!x)
      return lines.length ? lines.join(' ') : null
    }
    case 'open':
      return null
  }
}

function heatmapAnswer(h: HeatmapResult): string {
  const sampled = h.spec.sampled ? ' These come from sampled frames, so some are missed.' : ''
  if (h.total === 0) return `I have not seen any ${h.spec.phrase} yet this session.`
  const ranked = Object.entries(h.cells).filter(([, n]) => n > 0).sort((a, b) => b[1] - a[1])
  const label = (id: string) => h.zoneSet.cells.find((c) => c.id === id)?.label ?? id
  if (h.located === 0) return `${h.spec.label}: I saw ${h.total === 1 ? 'one' : h.total}, but could not place ${h.total === 1 ? 'it' : 'any of them'}.${sampled}`
  if (h.located === 1) {
    const unplaced = h.total - 1
    return `${h.spec.label}: so far just one I could place, ${label(ranked[0][0])}${unplaced ? ` (${unplaced} more I could not place)` : ''}. One is not a pattern yet.${sampled}`
  }
  const [first, second] = ranked
  let text = `Of ${h.total} ${h.spec.phrase} I saw, ${h.located} could be placed. Most came from ${label(first[0])}: ${first[1]}.`
  if (second) text += ` Next, ${label(second[0])}: ${second[1]}.`
  if (h.topBreakdown && h.spec.breakdown) text += ` ${h.spec.breakdown.label}: mostly ${humanize(h.topBreakdown.value)}, ${h.topBreakdown.count} times.`
  const strength = h.claim ? (h.claim.grade === 'weak' ? ' Early sign only.' : '') : ' Too few to call it a pattern yet.'
  return `${text}${strength} Zones are approximate.${sampled}`
}

// --- Digest for the model -----------------------------------------------------------------

export interface DigestInputs {
  readonly profile: GameProfile
  readonly modeLabel: string | null
  readonly policyLine: string
  readonly report: SessionReport
  readonly history: readonly MatchRecord[]
  readonly notes: { readonly goals: string; readonly notes: string } | null
  readonly research: { readonly text: string; readonly createdAt: string; readonly sources: readonly { readonly url: string; readonly title: string }[] } | null
}

/** A compact, structured, size-bounded description of what the coach has seen. */
export function buildDigest(i: DigestInputs): string {
  const p = i.profile
  const digest = {
    game: p.game.title,
    pack: { id: p.id, depth: p.depth, validation: p.validation.status, limitations: p.limitations },
    mode: i.modeLabel,
    policy: i.policyLine,
    session: {
      score: i.report.score,
      rounds: i.report.record,
      zones: i.report.heatmaps
        .filter((h) => h.total > 0)
        .map((h) => ({
          what: h.spec.phrase,
          total: h.total,
          placed: h.located,
          sampledFrames: h.spec.sampled,
          byZone: Object.fromEntries(Object.entries(h.cells).filter(([, n]) => n > 0).map(([id, n]) => [h.zoneSet.cells.find((c) => c.id === id)?.label ?? id, n])),
          howCreated: h.breakdown,
          claimGrade: h.claim?.grade ?? 'none',
        })),
      mistakes: i.report.mistakes.filter((m) => m.totals.count > 0).map((m) => ({ mistake: m.spec.label, times: m.totals.count, total: formatDuration(m.totals.totalMs) })),
      patterns: i.report.insights
        .filter((x) => x.verdict === null)
        .map((x) => {
          const spec = p.episodes.find((e) => e.id === x.insight.episodeId)!
          return {
            pattern: x.episodeLabel,
            grade: x.insight.grade,
            statement: x.statement,
            worse: { response: responseLabel(spec, x.insight.worse.response), ...x.insight.worse.counts },
            better: { response: responseLabel(spec, x.insight.better.response), ...x.insight.better.counts },
          }
        }),
      matchesThisSession: i.report.matches.map(slimMatch),
    },
    recentMatches: i.history.slice(-10).map(slimMatch),
    playerNotes: i.notes ? { goals: i.notes.goals.slice(0, 600), notes: i.notes.notes.slice(0, 600) } : null,
    research: i.research ? { asOf: i.research.createdAt, brief: i.research.text.slice(0, 3500), sources: i.research.sources.slice(0, 8) } : null,
  }
  return JSON.stringify(digest)
}

function slimMatch(m: MatchRecord) {
  return { endedAt: m.endedAt.slice(0, 10), result: m.result, score: m.score, stats: m.stats, mistakes: m.mistakes, zones: m.zones }
}

function avg(v: readonly number[]): number {
  return v.reduce((a, b) => a + b, 0) / v.length
}

function fmt(v: number, unit?: string): string {
  return `${Number.isInteger(v) ? v : v.toFixed(1)}${unit ?? ''}`
}
