// Benchmarks: a number read off the screen (e.g. villagers) compared, at
// checkpoints of the game clock, with what a simple, explainable curve would
// give (e.g. one Town Center that never stops making villagers). The result
// is concrete — "at 8:00 you had 24; a Town Center that never stops has 30 by
// then, about 2 minutes of idle production" — and only uses readings that
// were actually visible near each checkpoint.

import type { BenchmarkSpec } from '../profile/schema.ts'
import type { VisibleState } from '../types.ts'

/** A reading counts for a checkpoint when the game clock was within this many seconds of it. */
export const CHECKPOINT_WINDOW_SEC = 30

export interface BenchmarkPoint {
  readonly atSec: number
  readonly actual: number
  readonly expected: number
  /** Below expected by more than the tolerance. */
  readonly behind: boolean
}

export interface BenchmarkResult {
  readonly spec: BenchmarkSpec
  readonly points: readonly BenchmarkPoint[]
  /** The checkpoint furthest behind (if any is behind), with its statement. */
  readonly worst: (BenchmarkPoint & { readonly statement: string }) | null
  /** Checkpoints met, for strengths. */
  readonly met: number
}

export function expectedAt(spec: BenchmarkSpec, sec: number): number {
  const v = spec.expected.start + (spec.expected.perMinute * sec) / 60
  return Math.floor(spec.expected.cap !== undefined ? Math.min(spec.expected.cap, v) : v)
}

export class BenchmarkTracker {
  private readonly specs: readonly BenchmarkSpec[]
  /** Best reading per spec and checkpoint: the one closest in game time. */
  private readings = new Map<string, Map<number, { value: number; dist: number }>>()
  /** Checkpoints read for the first time since the last take (for commentary). */
  private fresh: { spec: BenchmarkSpec; atSec: number; actual: number }[] = []
  /** The last match's readings, shown until the next match has some of its own. */
  private previous = new Map<string, Map<number, { value: number; dist: number }>>()

  constructor(specs: readonly BenchmarkSpec[]) {
    this.specs = specs
  }

  observe(state: VisibleState): void {
    if (state.stale) return
    for (const spec of this.specs) {
      const v = state.values[spec.key]
      const t = state.values[spec.timeKey]
      if (!v || !t || typeof v.value !== 'number' || typeof t.value !== 'number' || v.conflicted || t.conflicted) continue
      for (const cp of spec.checkpointsSec) {
        const dist = Math.abs(t.value - cp)
        if (dist > CHECKPOINT_WINDOW_SEC) continue
        let m = this.readings.get(spec.id)
        if (!m) this.readings.set(spec.id, (m = new Map()))
        const cur = m.get(cp)
        if (!cur) this.fresh.push({ spec, atSec: cp, actual: v.value })
        if (!cur || dist < cur.dist) m.set(cp, { value: v.value, dist })
      }
    }
  }

  /** Checkpoints just reached, once each. */
  takeFresh(): { spec: BenchmarkSpec; atSec: number; actual: number; expected: number }[] {
    const out = this.fresh.map((f) => ({ ...f, expected: expectedAt(f.spec, f.atSec) }))
    this.fresh = []
    return out
  }

  newMatch(): void {
    this.fresh = []
    if (this.readings.size) this.previous = this.readings
    this.readings = new Map()
  }

  /** This match's comparison, or the last match's while this one has no readings yet. */
  results(): BenchmarkResult[] {
    const source = this.readings.size ? this.readings : this.previous
    return this.specs.map((spec) => benchmarkResult(spec, [...(source.get(spec.id) ?? new Map())].map(([atSec, r]) => ({ atSec, actual: r.value }))))
  }
}

export function benchmarkResult(spec: BenchmarkSpec, readings: readonly { atSec: number; actual: number }[]): BenchmarkResult {
  const points: BenchmarkPoint[] = [...readings]
    .sort((a, b) => a.atSec - b.atSec)
    .map(({ atSec, actual }) => {
      const expected = expectedAt(spec, atSec)
      return { atSec, actual, expected, behind: actual < expected * (1 - spec.tolerance) }
    })
  const behind = points.filter((p) => p.behind)
  const worstPoint = behind.sort((a, b) => b.expected - b.actual - (a.expected - a.actual))[0] ?? null
  return {
    spec,
    points,
    met: points.filter((p) => !p.behind).length,
    worst: worstPoint ? { ...worstPoint, statement: benchmarkStatement(spec, worstPoint) } : null,
  }
}

function clock(sec: number): string {
  return `${Math.floor(sec / 60)}:${String(Math.round(sec % 60)).padStart(2, '0')}`
}

export function benchmarkStatement(spec: BenchmarkSpec, p: BenchmarkPoint): string {
  const gap = p.expected - p.actual
  let s = `At ${clock(p.atSec)} you had ${p.actual} ${spec.noun}; ${spec.expectedPhrase} has ${p.expected} by then.`
  if (spec.gapPhrase && spec.expected.perMinute > 0) {
    const minutes = gap / spec.expected.perMinute
    const amount = minutes >= 1.5 ? `about ${Math.round(minutes)} minutes` : minutes >= 0.75 ? 'about a minute' : `about ${Math.round(minutes * 60)} seconds`
    s += ` That is ${amount} ${spec.gapPhrase}.`
  }
  const ref = spec.reference?.filter((r) => Math.abs(r.atSec - p.atSec) <= 90).sort((a, b) => Math.abs(a.atSec - p.atSec) - Math.abs(b.atSec - p.atSec))[0]
  if (ref) s += ` For reference, ${ref.label}: about ${ref.value}.`
  return s
}
