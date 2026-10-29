// The coach's review of a session: a short headline, what went well, and at
// most three things to work on — each with the evidence that was counted, the
// fix from the pack's playbook and a drill. Built on this computer from the
// findings; when cloud features are on, Claude can write a deeper one from the
// same digest and playbook (validated against the same shape).

import { productionLines } from './aoe4/production.ts'
import type { SessionReport } from './engine.ts'
import { humanize, mistakeLine } from './language.ts'
import { factorFindings } from './planner.ts'
import { plural } from './analyses/intel.ts'
import { BANNED_CLAIMS, type GameProfile, type PlaybookEntry } from './profile/schema.ts'

export interface ReviewFocus {
  /** What was counted, e.g. "8 of your 12 deaths came while you were in the open." */
  readonly evidence: string
  /** What good players do instead (the playbook principle, shortened). */
  readonly principle: string
  readonly fix: string
  readonly drill: string | null
  readonly playbook: string | null
}

export interface MatchReview {
  readonly headline: string
  readonly strengths: readonly string[]
  readonly focus: readonly ReviewFocus[]
  /** One sentence the player can carry into the next match. */
  readonly nextMatch: string
  readonly author: 'local' | 'ai'
}

const MAX_FOCUS = 3

/** A review from the recorded findings and the playbook, with no network. Null when there is too little to say. */
export function localReview(profile: GameProfile, report: SessionReport): MatchReview | null {
  const book = new Map((profile.knowledge.playbook ?? []).map((e) => [e.id, e]))
  const focus: ReviewFocus[] = []
  const used = new Set<string>()
  const add = (evidence: string, id: string | null | undefined) => {
    if (focus.length >= MAX_FOCUS) return
    const e = id ? book.get(id) : undefined
    if (e && used.has(e.id)) return
    if (e) used.add(e.id)
    focus.push(e ? fromEntry(evidence, e) : { evidence, principle: '', fix: '', drill: null, playbook: null })
  }

  const findings = factorFindings(report.factors, 'weak')
  for (const f of findings.filter((x) => x.kind === 'weakness' && x.grade !== 'weak')) add(f.statement, f.playbook)
  // The Town Center's idle time (from the villager counter) is more exact than the curve: it goes first.
  const prod = report.production
  if (prod && (prod.idleSec >= 60 || prod.housedWaitSec >= 20)) add(productionLines(prod).filter((l) => !l.startsWith('Not counted')).join(' '), 'tc-never-idle')
  for (const b of report.benchmarks.filter((x) => x.worst)) add(b.worst!.statement, b.spec.playbook)
  // What the opponent fielded, and what beats it (from the roster, for the player's civilisation).
  const met = report.intel?.units[0]
  const answer = report.intel?.counters.find((c) => c.against === met?.name && c.build.length)
  if (met && met.count >= 3 && answer && focus.length < MAX_FOCUS) {
    focus.push({
      evidence: `You mostly met ${plural(met.name)} (${met.count} at once).`,
      principle: answer.why ?? '',
      fix: `Build ${answer.build.slice(0, 2).map(plural).join(' or ')} against them.`,
      drill: null,
      playbook: book.has('counter-core') ? 'counter-core' : null,
    })
  }
  for (const h of report.heatmaps.filter((x) => x.claim && x.claim.grade !== 'weak')) {
    const top = h.topBreakdown
    const playbook = top ? h.spec.breakdown?.playbook?.[top.value] : undefined
    add(`${h.claim!.count} of the ${h.claim!.located} ${h.spec.phrase} came from ${h.claim!.cellLabel}${top ? `, most often ${humanize(top.value)}` : ''}.`, playbook)
  }
  for (const m of [...report.mistakes].filter((x) => x.totals.count > 0).sort((a, b) => b.totals.totalMs - a.totals.totalMs)) add(mistakeLine(m.spec.summary, m.totals), m.spec.playbook)
  // Early signs only when nothing firmer is there.
  if (!focus.length) for (const f of findings.filter((x) => x.kind === 'weakness' && x.grade === 'weak')) add(`Early sign: ${f.statement}`, f.playbook)

  const strengths = [
    ...findings.filter((f) => f.kind === 'strength').map((f) => f.statement),
    ...report.benchmarks.filter((b) => b.points.length && !b.worst).map((b) => `${b.spec.label}: on track at every checkpoint read.`),
  ].slice(0, 2)

  if (!focus.length && !strengths.length) return null
  const first = focus.find((f) => f.fix)
  return {
    headline: focus.length ? `Main thing to work on: ${lowerFirst(focus[0].evidence)}` : 'Nothing stood out as a repeated problem this session.',
    strengths,
    focus,
    nextMatch: first ? `Next match, one thing: ${lowerFirst(first.fix)}` : 'Next match, keep doing what worked and let the coach see a few more games.',
    author: 'local',
  }
}

function fromEntry(evidence: string, e: PlaybookEntry): ReviewFocus {
  return { evidence, principle: firstSentence(e.principle), fix: e.fix, drill: e.drill ?? null, playbook: e.id }
}

function firstSentence(s: string): string {
  const m = /^(.+?[.!?])(\s|$)/.exec(s)
  return m ? m[1] : s
}

function lowerFirst(s: string): string {
  return /^[A-Z][a-z]/.test(s) ? s[0].toLowerCase() + s.slice(1) : s
}

/** The review as one spoken passage. */
export function reviewSpeech(r: MatchReview): string {
  const parts = [r.headline]
  if (r.strengths.length) parts.push(`Good: ${r.strengths.map(lowerFirst).join(' And ')}`)
  for (const [i, f] of r.focus.entries()) {
    if (i === 0) {
      if (f.fix) parts.push(f.fix)
      continue
    }
    parts.push(`Also: ${f.evidence}${f.fix ? ` ${f.fix}` : ''}`)
  }
  parts.push(r.nextMatch)
  return parts.join(' ')
}

/**
 * Check a review that came from the model: bounded sizes, no causal or
 * absolute claims, playbook ids that exist. Returns null if it does not hold.
 */
export function validateReview(profile: GameProfile, raw: unknown): MatchReview | null {
  if (!raw || typeof raw !== 'object') return null
  const r = raw as Record<string, unknown>
  const ids = new Set((profile.knowledge.playbook ?? []).map((e) => e.id))
  const str = (v: unknown, max: number) => (typeof v === 'string' && v.trim() && v.length <= max && !BANNED_CLAIMS.test(v) ? v.trim() : null)
  const headline = str(r.headline, 240)
  const nextMatch = str(r.nextMatch, 240)
  if (!headline || !nextMatch || !Array.isArray(r.strengths) || !Array.isArray(r.focus)) return null
  const strengths = r.strengths.map((s) => str(s, 240)).filter((s): s is string => !!s).slice(0, 3)
  const focus: ReviewFocus[] = []
  for (const f of r.focus.slice(0, MAX_FOCUS)) {
    if (!f || typeof f !== 'object') continue
    const o = f as Record<string, unknown>
    const evidence = str(o.evidence, 300)
    const fix = str(o.fix, 300)
    if (!evidence || !fix) continue
    const playbook = typeof o.playbook === 'string' && ids.has(o.playbook) ? o.playbook : null
    focus.push({ evidence, principle: str(o.principle, 400) ?? '', fix, drill: str(o.drill, 300), playbook })
  }
  if (!focus.length && !strengths.length) return null
  return { headline, strengths, focus, nextMatch, author: 'ai' }
}
