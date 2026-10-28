// Fight factors: how the circumstances of a fight (range, cover, movement,
// height, support…) relate to winning or losing it. Each death and kill read
// from the screen can carry a tag per factor; the analysis then says, with a
// graded confidence:
//   - "You lost 5 of 6 fights at long range" (a value whose loss rate stands
//     clearly above your overall loss rate, when kills are tagged too);
//   - "You won 8 of 10 fights at close range" (a strength);
//   - "7 of your 10 deaths came while you were in the open" (an avoidable
//     circumstance behind a large share of deaths).
// Untagged events (the killcam was missed or unclear) are counted, never
// guessed. These are associations in what was visible, not causes.

import { wilson } from '../evidence.ts'
import type { FactorSpec, FactorValueSpec } from '../profile/schema.ts'
import type { EvidenceGrade, GameEvent } from '../types.ts'

export interface FactorRow {
  readonly value: string
  readonly label: string
  readonly losses: number
  readonly wins: number
}

export interface FactorFinding {
  readonly kind: 'weakness' | 'strength'
  readonly factorId: string
  readonly value: string
  readonly grade: Exclude<EvidenceGrade, 'insufficient'>
  /** Built from counts; no causal words. */
  readonly statement: string
  readonly count: number
  readonly of: number
  readonly playbook: string | null
  readonly liveTip: string | null
}

export interface FactorResult {
  readonly spec: FactorSpec
  readonly rows: readonly FactorRow[]
  /** Losses and wins that carried a known value for this factor. */
  readonly taggedLosses: number
  readonly taggedWins: number
  readonly untagged: number
  readonly findings: readonly FactorFinding[]
}

interface Recorded {
  readonly factor: string
  readonly kind: 'loss' | 'win'
  readonly value: string | null
  readonly match: number
}

export class FactorAnalyzer {
  private readonly specs: readonly FactorSpec[]
  private readonly events = new Map<string, Recorded & { readonly uid: string }>()
  private match = 0
  /** Last time each `afterEvent` event was seen. */
  private readonly lastSeen = new Map<string, number>()

  constructor(specs: readonly FactorSpec[]) {
    this.specs = specs
  }

  record(ev: GameEvent): void {
    for (const spec of this.specs) {
      const kind = ev.ruleId === spec.lossEvent ? 'loss' : spec.winEvent && ev.ruleId === spec.winEvent ? 'win' : null
      if (!kind) continue
      let value: string | null
      if (spec.afterEvent) {
        const last = this.lastSeen.get(spec.afterEvent.event)
        value = last !== undefined && ev.at - last >= 0 && ev.at - last <= spec.afterEvent.withinMs ? 'yes' : 'no'
      } else {
        const raw = spec.attribute ? ev.attributes?.[spec.attribute] : undefined
        value = typeof raw === 'string' && raw !== 'unknown' && spec.values.some((v) => v.value === raw) ? raw : null
      }
      this.events.set(`${spec.id}:${ev.uid}`, { uid: ev.uid, factor: spec.id, kind, value, match: this.match })
    }
    // After the loss check, so a kill and a death in one batch are not "after" each other in the wrong order.
    if (this.specs.some((s) => s.afterEvent?.event === ev.ruleId)) this.lastSeen.set(ev.ruleId, Math.max(ev.at, this.lastSeen.get(ev.ruleId) ?? -Infinity))
  }

  /** Attributes that arrived later for an event already recorded (e.g. a goal replay after the scoreboard change). */
  amend(uid: string, attributes: Readonly<Record<string, string | number | boolean>> | undefined): void {
    if (!attributes) return
    for (const spec of this.specs) {
      const key = `${spec.id}:${uid}`
      const r = this.events.get(key)
      if (!r || r.value !== null || !spec.attribute) continue
      const raw = attributes[spec.attribute]
      if (typeof raw === 'string' && raw !== 'unknown' && spec.values.some((v) => v.value === raw)) this.events.set(key, { ...r, value: raw })
    }
  }

  newMatch(): void {
    this.match++
    this.lastSeen.clear()
  }

  results(scope: 'session' | 'match' = 'session'): FactorResult[] {
    return this.specs.map((spec) => {
      const counts = new Map<string, { l: number; w: number }>(spec.values.map((v) => [v.value, { l: 0, w: 0 }]))
      let untagged = 0
      for (const r of this.events.values()) {
        if (r.factor !== spec.id || (scope === 'match' && r.match !== this.match)) continue
        if (r.value === null) {
          untagged++
          continue
        }
        const c = counts.get(r.value)!
        if (r.kind === 'loss') c.l++
        else c.w++
      }
      return factorResult(spec, counts, untagged)
    })
  }

  /** Per-match counts, for match records: value → [losses, wins]. */
  matchCounts(): Record<string, Record<string, [number, number]>> {
    const out: Record<string, Record<string, [number, number]>> = {}
    for (const res of this.results('match')) {
      const rows = res.rows.filter((r) => r.losses + r.wins > 0)
      if (rows.length) out[res.spec.id] = Object.fromEntries(rows.map((r) => [r.value, [r.losses, r.wins]]))
    }
    return out
  }
}

/** Analyse counts (also used for match history: counts summed over matches). */
export function factorResult(spec: FactorSpec, counts: ReadonlyMap<string, { l: number; w: number }>, untagged = 0): FactorResult {
  const rows: FactorRow[] = spec.values.map((v) => ({ value: v.value, label: v.label, losses: counts.get(v.value)?.l ?? 0, wins: counts.get(v.value)?.w ?? 0 }))
  const L = rows.reduce((s, r) => s + r.losses, 0)
  const W = rows.reduce((s, r) => s + r.wins, 0)
  const findings: FactorFinding[] = []
  const byValue = new Map(spec.values.map((v) => [v.value, v]))
  const finding = (kind: FactorFinding['kind'], v: FactorValueSpec, grade: FactorFinding['grade'], statement: string, count: number, of: number): FactorFinding => ({
    kind,
    factorId: spec.id,
    value: v.value,
    grade,
    statement,
    count,
    of,
    playbook: v.playbook ?? null,
    liveTip: v.liveTip ?? null,
  })

  // Fights won vs lost by value (needs wins tagged with the same factor).
  if (spec.winEvent && L + W >= spec.minEvents && L > 0 && W > 0) {
    const overall = L / (L + W)
    for (const r of rows) {
      const n = r.losses + r.wins
      if (n < 3) continue
      const v = byValue.get(r.value)!
      const p = r.losses / n
      const worse = gradeAbove(r.losses, n, overall)
      if (worse) findings.push(finding('weakness', v, worse, `You lost ${r.losses} of ${n} fights ${v.phrase}.`, r.losses, n))
      const better = gradeAbove(r.wins, n, 1 - overall)
      if (better && !worse && p < overall) findings.push(finding('strength', v, better, `You won ${r.wins} of ${n} fights ${v.phrase}.`, r.wins, n))
    }
  }

  // Avoidable circumstances behind a large share of deaths.
  if (L >= spec.minEvents) {
    for (const r of rows) {
      const v = byValue.get(r.value)!
      if (!v.avoidable || r.losses < 2) continue
      const g = gradeShare(r.losses, L)
      if (!g) continue
      // One finding per value: the share replaces a weaker comparison (a few fights can be a clear share of losses).
      const i = findings.findIndex((f) => f.value === r.value && f.kind === 'weakness')
      if (i >= 0 && RANK[findings[i].grade] >= RANK[g]) continue
      if (i >= 0) findings.splice(i, 1)
      const noun = spec.lossNoun ?? 'deaths'
      const statement = r.losses === L ? `All ${L} ${noun} came ${v.phrase}.` : `${r.losses} of your ${L} ${noun} came ${v.phrase}.`
      findings.push(finding('weakness', v, g, statement, r.losses, L))
    }
  }
  return { spec, rows, taggedLosses: L, taggedWins: W, untagged, findings: findings.sort((a, b) => RANK[b.grade] - RANK[a.grade] || b.count / b.of - a.count / a.of) }
}

const RANK = { weak: 1, moderate: 2, strong: 3 } as const

/** k of n clearly above a baseline rate. */
function gradeAbove(k: number, n: number, baseline: number): FactorFinding['grade'] | null {
  const p = k / n
  if (n >= 8 && k >= 5 && wilson(k, n, 1.645).lo > baseline + 0.05 && p - baseline >= 0.2) return 'strong'
  if (n >= 5 && k >= 3 && wilson(k, n, 1.2816).lo > baseline && p - baseline >= 0.15) return 'moderate'
  if (n >= 3 && k >= 2 && p - baseline >= 0.25) return 'weak'
  return null
}

/** An avoidable circumstance in a large share of deaths (a third or more, with confidence). */
function gradeShare(k: number, n: number): FactorFinding['grade'] | null {
  if (k >= 5 && wilson(k, n, 1.645).lo >= 0.35) return 'strong'
  if (k >= 3 && wilson(k, n, 1.2816).lo >= 0.25) return 'moderate'
  if (k >= 2 && k / n >= 0.4) return 'weak'
  return null
}

/** Sum per-match factor counts (from match records) into one result per factor. */
export function historyFactors(specs: readonly FactorSpec[], perMatch: readonly (Readonly<Record<string, Readonly<Record<string, readonly [number, number]>>>> | undefined)[]): FactorResult[] {
  return specs.map((spec) => {
    const counts = new Map<string, { l: number; w: number }>()
    for (const m of perMatch) {
      for (const [value, [l, w]] of Object.entries(m?.[spec.id] ?? {})) {
        const c = counts.get(value) ?? { l: 0, w: 0 }
        counts.set(value, { l: c.l + l, w: c.w + w })
      }
    }
    return factorResult(spec, counts)
  })
}
