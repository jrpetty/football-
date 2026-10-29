// Benchmarks: a number read off the screen (e.g. villagers) compared, at
// checkpoints of the game clock, with what a simple, explainable curve would
// give (e.g. one Town Center that never stops making villagers). The result
// is concrete — "at 8:00 you had 24; a Town Center that never stops has 30 by
// then, about 2 minutes of idle production" — and only uses readings that
// were actually visible near each checkpoint, that agree with each other (one
// misread number is never stated as fact), and that are possible (never more
// villagers than population).

import type { BenchmarkSpec } from '../profile/schema.ts'
import type { VisibleState } from '../types.ts'

/** A reading counts for a checkpoint when the game clock was within this many seconds of it. */
export const CHECKPOINT_WINDOW_SEC = 30
/** A checkpoint is stated only when at least this many of its readings agree. */
export const MIN_AGREEING = 2

type Reading = { value: number; dist: number }

/** The agreed value of a checkpoint's readings (the one closest in time among those near their median), or null. */
export function agreed(readings: readonly Reading[]): number | null {
  if (readings.length < MIN_AGREEING) return null
  const sorted = [...readings].map((r) => r.value).sort((a, b) => a - b)
  const median = sorted[Math.floor(sorted.length / 2)]
  const slack = Math.max(2, Math.round(median * 0.1))
  const near = readings.filter((r) => Math.abs(r.value - median) <= slack)
  if (near.length < MIN_AGREEING) return null
  return [...near].sort((a, b) => a.dist - b.dist)[0].value
}

export interface BenchmarkPoint {
  readonly atSec: number
  readonly actual: number
  readonly expected: number
  /** Below expected by more than the tolerance. */
  readonly behind: boolean
  /** What the player usually reaches here (their recent matches), when known. */
  readonly usual?: { readonly value: number; readonly games: number; readonly sameFaction: boolean }
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

/** The spec as it applies to one civilisation: its own curve, or null when it does not apply. */
export function specFor(spec: BenchmarkSpec, faction: string | null): BenchmarkSpec | null {
  const f = faction ? spec.byFaction?.[faction] : undefined
  if (!f) return spec
  if (f.skip) return null
  return {
    ...spec,
    expected: { ...spec.expected, start: f.start ?? spec.expected.start, perMinute: f.perMinute ?? spec.expected.perMinute },
    expectedPhrase: f.expectedPhrase ?? spec.expectedPhrase,
  }
}

export class BenchmarkTracker {
  private readonly all: readonly BenchmarkSpec[]
  private specs: readonly BenchmarkSpec[]
  /** Readings per spec and checkpoint (up to a dozen each). */
  private readings = new Map<string, Map<number, Reading[]>>()
  /** Checkpoints agreed for the first time since the last take (for commentary). */
  private fresh: { spec: BenchmarkSpec; atSec: number; actual: number }[] = []
  private stated = new Set<string>()
  /** When each spec's number was last read, so one reading lingering in the state counts once. */
  private lastRead = new Map<string, number>()
  /** The last match's readings, shown until the next match has some of its own. */
  private previous = new Map<string, Map<number, Reading[]>>()

  constructor(specs: readonly BenchmarkSpec[], faction: string | null = null) {
    this.all = specs
    this.specs = specs
    this.setFaction(faction)
  }

  /** The player's civilisation: its own curve where it has one (readings so far are kept). */
  setFaction(faction: string | null): void {
    this.specs = this.all.flatMap((s) => specFor(s, faction) ?? [])
  }

  observe(state: VisibleState): void {
    if (state.stale) return
    for (const spec of this.specs) {
      const v = state.values[spec.key]
      const t = state.values[spec.timeKey]
      if (!v || !t || typeof v.value !== 'number' || typeof t.value !== 'number' || v.conflicted || t.conflicted) continue
      // An impossible reading (more villagers than population) is a misread.
      const cap = spec.atMostKey ? state.values[spec.atMostKey] : undefined
      if (cap && typeof cap.value === 'number' && !cap.conflicted && v.value > cap.value) continue
      if (v.observedAt <= (this.lastRead.get(spec.id) ?? -Infinity)) continue
      this.lastRead.set(spec.id, v.observedAt)
      for (const cp of spec.checkpointsSec) {
        const dist = Math.abs(t.value - cp)
        if (dist > CHECKPOINT_WINDOW_SEC) continue
        let m = this.readings.get(spec.id)
        if (!m) this.readings.set(spec.id, (m = new Map()))
        const list = m.get(cp) ?? []
        if (list.length < 12) list.push({ value: v.value, dist })
        m.set(cp, list)
        const k = `${spec.id}@${cp}`
        const value = agreed(list)
        if (value !== null && !this.stated.has(k)) {
          this.stated.add(k)
          this.fresh.push({ spec, atSec: cp, actual: value })
        }
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
    this.stated = new Set()
    this.lastRead = new Map()
    if (this.readings.size) this.previous = this.readings
    this.readings = new Map()
  }

  /** This match's comparison, or the last match's while this one has no readings yet. */
  results(): BenchmarkResult[] {
    const source = this.readings.size ? this.readings : this.previous
    return this.specs.map((spec) =>
      benchmarkResult(
        spec,
        [...(source.get(spec.id) ?? new Map<number, Reading[]>())].flatMap(([atSec, list]) => {
          const actual = agreed(list)
          return actual === null ? [] : [{ atSec, actual }]
        }),
      ),
    )
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
  return s
}
