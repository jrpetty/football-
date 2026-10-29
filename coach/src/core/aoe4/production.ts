// Town Center production for Age of Empires IV, from the villager counter on
// the HUD (the upper-left total), read about once a second on this PC once the
// coach has learned its digits (every few seconds from the cloud model until
// then).
//
// One Town Center that never stops makes a villager every 20 seconds (19 to 23
// for some civilisations). When the counter goes longer than that without
// rising, the Town Center stood idle for the difference. Not counted:
// - time at full population (200): nothing can be made;
// - time housed (population at its cap): idle by itself is not counted. Only
//   the time a villager was ready and waiting for space is, as its own number:
//   that is shown by the villager appearing the moment space is added;
// - stretches where villagers were lost: a loss hides a villager made, so the
//   count cannot tell;
// - gaps in the readings, and everything from 100 villagers on (the counter is
//   reported not to show more than 100, and most players stop near there).
// Each change of the counter must be read twice before it counts, so one
// misread number never makes a villager or an idle Town Center.

/** Population that is full: nothing can be trained. */
export const FULL_POPULATION = 200
/** From this many villagers on, nothing is judged. */
export const VILLAGER_STOP = 100
/** Readings further apart than this (game seconds) leave a gap that is not judged. */
const MAX_GAP_SEC = 15
/** After space is added, a waiting villager appears within this long (reading delay included). */
const POP_GRACE_SEC = 5
/** Idle time is only stated beyond the reading's own uncertainty: at least this many seconds… */
const MIN_IDLE_SEC = 3
/** …or this many reading intervals. */
const IDLE_INTERVALS = 2
/** The live alert: this long past when the next villager was due. */
export const ALERT_IDLE_SEC = 15

export interface ProductionStretch {
  /** Game seconds when the Town Center went idle (the next villager was due). */
  readonly at: number
  readonly sec: number
}

export interface ProductionSummary {
  /** Seconds per villager used. */
  readonly trainSec: number
  /** Game seconds judged (from the first reading to the last, or to 100 villagers). */
  readonly fromSec: number
  readonly toSec: number
  /** Median seconds between villager readings. */
  readonly readEverySec: number
  readonly idleSec: number
  readonly stretches: readonly ProductionStretch[]
  /** Housed with a villager ready and waiting for space. */
  readonly housedWaitSec: number
  readonly housedWaits: readonly ProductionStretch[]
  /** Not counted: housed with nothing waiting, at full population, and stretches with villagers lost or readings missing. */
  readonly excluded: { readonly housedSec: number; readonly fullSec: number; readonly unclearSec: number }
  readonly villagersMade: number
  readonly reached100: boolean
}

type Span = readonly [number, number]

/** What the readings showed, as times (game seconds). Exported for tests and for after the match. */
export interface ProductionTimeline {
  readonly trainSec: number
  readonly start: number
  readonly end: number
  readonly made: readonly number[]
  readonly housed: readonly Span[]
  readonly full: readonly Span[]
  /** Gaps in the readings and stretches where villagers were lost. */
  readonly unclear: readonly Span[]
  readonly stopAt: number | null
  readonly readEverySec: number
}

const overlap = (a: Span, b: Span) => Math.max(0, Math.min(a[1], b[1]) - Math.max(a[0], b[0]))
const total = (span: Span, spans: readonly Span[]) => spans.reduce((s, x) => s + overlap(span, x), 0)
/** A span minus spans (which may overlap each other): its remaining length. */
function remaining(span: Span, cuts: readonly Span[]): number {
  const parts = cuts
    .map((c) => [Math.max(span[0], c[0]), Math.min(span[1], c[1])] as const)
    .filter(([a, b]) => b > a)
    .sort((x, y) => x[0] - y[0])
  let covered = 0
  let at = span[0]
  for (const [a, b] of parts) {
    if (b <= at) continue
    covered += b - Math.max(a, at)
    at = b
  }
  return Math.max(0, span[1] - span[0] - covered)
}

/** Idle time from a timeline: the stretches between villagers longer than one villager's training. */
export function summarise(tl: ProductionTimeline): ProductionSummary {
  const end = tl.stopAt !== null ? Math.min(tl.end, tl.stopAt) : tl.end
  const tol = Math.max(MIN_IDLE_SEC, IDLE_INTERVALS * tl.readEverySec)
  const made = tl.made.filter((t) => t > tl.start && t <= end)
  const marks = [tl.start, ...made]
  // Housed with a villager waiting: it appeared as soon as space was added.
  const housedWaits: ProductionStretch[] = []
  const graces: Span[] = []
  for (const h of tl.housed) {
    if (h[1] > end) continue
    const popped = made.find((t) => t >= h[1] && t <= h[1] + POP_GRACE_SEC)
    if (popped === undefined) continue
    const before = [tl.start, ...made].filter((t) => t <= h[0]).at(-1) ?? tl.start
    const from = Math.max(h[0], before + tl.trainSec)
    if (h[1] - from >= 1) housedWaits.push({ at: Math.round(from), sec: Math.round(h[1] - from) })
    graces.push([h[1], popped])
  }
  const stretches: ProductionStretch[] = []
  let unclearSec = 0
  for (let i = 0; i < marks.length; i++) {
    const from = marks[i] + tl.trainSec
    const to = i + 1 < marks.length ? marks[i + 1] : end
    if (to <= from) continue
    const span: Span = [from, to]
    const unclear = total(span, tl.unclear)
    if (unclear > 0) {
      unclearSec += remaining(span, [...tl.housed, ...tl.full])
      continue
    }
    const idle = remaining(span, [...tl.housed, ...tl.full, ...graces])
    if (idle > tol) stretches.push({ at: Math.round(from), sec: Math.round(idle) })
  }
  const judged: Span = [tl.start, end]
  const housedSec = total(judged, tl.housed) - housedWaits.reduce((s, w) => s + w.sec, 0)
  return {
    trainSec: tl.trainSec,
    fromSec: Math.round(tl.start),
    toSec: Math.round(end),
    readEverySec: Math.round(tl.readEverySec * 10) / 10,
    idleSec: stretches.reduce((s, x) => s + x.sec, 0),
    stretches,
    housedWaitSec: housedWaits.reduce((s, w) => s + w.sec, 0),
    housedWaits,
    excluded: { housedSec: Math.max(0, Math.round(housedSec)), fullSec: Math.round(total(judged, tl.full)), unclearSec: Math.round(unclearSec) },
    villagersMade: made.length,
    reached100: tl.stopAt !== null && tl.stopAt <= tl.end,
  }
}

const clock = (sec: number) => `${Math.floor(sec / 60)}:${String(Math.round(sec % 60)).padStart(2, '0')}`
const span = (sec: number) => (sec >= 60 ? clock(sec) : `${sec} s`)

/** "Town Center idle 1:40 in total, the longest 35 s at 6:10." and the housed and not-counted lines. */
export function productionLines(s: ProductionSummary): string[] {
  const lines: string[] = []
  const longest = [...s.stretches].sort((a, b) => b.sec - a.sec)[0]
  if (!longest) lines.push(`Town Center: no idle time seen between ${clock(s.fromSec)} and ${clock(s.toSec)} (${s.villagersMade} villagers made).`)
  else lines.push(`Town Center idle ${span(s.idleSec)} in total, the longest ${span(longest.sec)} at ${clock(longest.at)}${s.stretches.length > 1 ? ` (${s.stretches.length} times)` : ''}.`)
  if (s.housedWaitSec > 0) lines.push(`Housed with a villager waiting: ${span(s.housedWaitSec)}${s.housedWaits.length > 1 ? ` (${s.housedWaits.length} times)` : ` at ${clock(s.housedWaits[0].at)}`}.`)
  const not: string[] = []
  if (s.excluded.housedSec >= 5) not.push(`${span(s.excluded.housedSec)} housed with no villager waiting`)
  if (s.excluded.fullSec >= 5) not.push(`${span(s.excluded.fullSec)} at full population`)
  if (s.excluded.unclearSec >= 5) not.push(`${span(s.excluded.unclearSec)} while villagers were lost or the counter was not read`)
  if (s.reached100) not.push('everything from 100 villagers on')
  if (not.length) lines.push(`Not counted: ${not.join('; ')}.`)
  return lines
}

export interface ProductionReading {
  /** Game seconds. */
  readonly t: number
  readonly villagers?: number | null
  readonly population?: number | null
  readonly populationCap?: number | null
}

/** Follows the readings as they come, for the live alert and the match's summary. */
export class ProductionTracker {
  private trainSec: number | null
  private start: number | null = null
  private lastT = -Infinity
  private lastVillT: number | null = null
  private villagers: number | null = null
  private pending: { value: number; t: number; before: number } | null = null
  private made: number[] = []
  private unclear: Span[] = []
  private intervals: number[] = []
  private stopAt: number | null = null
  private housed = new FlagSpans()
  private full = new FlagSpans()
  private alerted = new Set<number>()

  /** `trainSec` null: this civilisation's villager time is not known, so nothing is judged. */
  constructor(trainSec: number | null) {
    this.trainSec = trainSec
  }

  setTrainSec(sec: number | null): void {
    this.trainSec = sec
  }

  observe(r: ProductionReading): void {
    if (!Number.isFinite(r.t)) return
    // The clock went back (a new game, or a replay): start again.
    if (r.t < this.lastT - 5) this.reset()
    this.lastT = Math.max(this.lastT, r.t)
    const pop = typeof r.population === 'number' ? r.population : null
    const cap = typeof r.populationCap === 'number' ? r.populationCap : null
    if (pop !== null && cap !== null && cap > 0) {
      this.full.read(r.t, pop >= FULL_POPULATION)
      this.housed.read(r.t, pop >= cap && cap < FULL_POPULATION)
    }
    const v = typeof r.villagers === 'number' && r.villagers >= 0 ? r.villagers : null
    // More villagers than population is a misread.
    if (v === null || (pop !== null && v > pop)) return
    if (this.lastVillT !== null) {
      const gap = r.t - this.lastVillT
      if (gap > 0) this.intervals = [...this.intervals.slice(-59), gap]
      // Missing readings: longer than the usual rhythm allows (at least 15 s).
      if (gap > Math.max(MAX_GAP_SEC, 2.5 * this.readEvery())) this.unclear.push([this.lastVillT, r.t])
    }
    if (this.villagers === null) {
      // The first reading counts once the next one agrees with it (the same, or a villager or two more).
      if (this.pending && v >= this.pending.value && v - this.pending.value <= 2) {
        this.villagers = this.pending.value
        this.start = this.pending.t
        this.pending = null
        this.take(v, r.t)
      } else this.pending = { value: v, t: r.t, before: r.t }
      this.lastVillT = r.t
      return
    }
    this.take(v, r.t)
    this.lastVillT = r.t
  }

  /**
   * A change of the counter counts once the next reading is consistent with it: as high or a villager
   * or two higher after a rise, as low or lower after a drop. A reading that goes back is a misread.
   */
  private take(v: number, t: number): void {
    const cur = this.villagers!
    const p = this.pending
    if (p) {
      const consistent = p.value > cur ? v >= p.value && v - p.value <= 2 : v <= p.value
      if (consistent) {
        this.accept(p)
        this.pending = v !== p.value ? { value: v, t, before: p.t } : null
        return
      }
      this.pending = v === cur ? null : { value: v, t, before: this.lastVillT ?? t }
      return
    }
    if (v !== cur) this.pending = { value: v, t, before: this.lastVillT ?? t }
  }

  private accept(p: { value: number; t: number; before: number }): void {
    const d = p.value - this.villagers!
    const since = this.made.at(-1) ?? this.start ?? p.t
    if (d > 0 && d <= Math.max(2, Math.ceil((p.t - since) / Math.max(1, this.trainSec ?? 20)) + 1)) for (let i = 0; i < d; i++) this.made.push(p.t)
    // Fewer (lost or converted), or a jump no Town Center makes: the stretch cannot be judged.
    else this.unclear.push([Math.min(p.before, p.t - 1), p.t])
    this.villagers = p.value
    if (p.value >= VILLAGER_STOP && this.stopAt === null) this.stopAt = p.t
  }

  /** The live alert: once per idle stretch, when the next villager is well overdue. */
  alert(now: number): { text: string; topic: string } | null {
    if (this.trainSec === null || this.start === null || this.villagers === null || this.stopAt !== null) return null
    if (this.housed.on || this.full.on) return null
    const last = this.made.at(-1) ?? this.start
    // Readings must be current, and nothing lost or missed since the last villager.
    if (this.lastVillT === null || now - this.lastVillT > Math.max(MAX_GAP_SEC, 2.5 * this.readEvery())) return null
    if (this.unclear.some((u) => u[1] > last)) return null
    // Overdue time, less any spent housed or at full population.
    const due = last + this.trainSec
    const idleSec = now > due ? remaining([due, now], [...this.housed.spans(now), ...this.full.spans(now)]) : 0
    if (idleSec < ALERT_IDLE_SEC + IDLE_INTERVALS * this.readEvery() || this.alerted.has(last)) return null
    this.alerted.add(last)
    return { text: `No new villager for ${Math.round(now - last)} seconds. Keep the Town Center working.`, topic: `tc-idle:${Math.round(last)}` }
  }

  /** Median game seconds between villager readings (1 until measured). */
  private readEvery(): number {
    const iv = [...this.intervals].sort((a, b) => a - b)
    return iv.length ? iv[Math.floor(iv.length / 2)] : 1
  }

  timeline(): ProductionTimeline | null {
    if (this.trainSec === null || this.start === null) return null
    return {
      trainSec: this.trainSec,
      start: this.start,
      end: this.lastVillT ?? this.start,
      made: [...this.made],
      housed: this.housed.spans(this.lastT),
      full: this.full.spans(this.lastT),
      unclear: [...this.unclear],
      stopAt: this.stopAt,
      readEverySec: this.readEvery(),
    }
  }

  /** The match so far, or null when the counter was not read long enough to judge. */
  summary(): ProductionSummary | null {
    const tl = this.timeline()
    if (!tl || tl.end - tl.start < 60) return null
    return summarise(tl)
  }

  reset(): void {
    this.start = null
    this.lastT = -Infinity
    this.lastVillT = null
    this.villagers = null
    this.pending = null
    this.made = []
    this.unclear = []
    this.intervals = []
    this.stopAt = null
    this.housed = new FlagSpans()
    this.full = new FlagSpans()
    this.alerted = new Set()
  }
}

/** On/off spans of a condition read from the screen; a change counts once read twice the same. */
class FlagSpans {
  on = false
  private since = 0
  private pending: { value: boolean; t: number } | null = null
  private done: Span[] = []

  read(t: number, value: boolean): void {
    if (value === this.on) {
      this.pending = null
      return
    }
    if (this.pending?.value !== value) {
      this.pending = { value, t }
      return
    }
    if (value) this.since = this.pending.t
    else this.done.push([this.since, this.pending.t])
    this.on = value
    this.pending = null
  }

  spans(now: number): Span[] {
    return this.on ? [...this.done, [this.since, now]] : [...this.done]
  }
}

export function isProductionSummary(v: unknown): v is ProductionSummary {
  const o = v as ProductionSummary | null
  const stretchList = (x: unknown) => Array.isArray(x) && x.length <= 200 && x.every((s) => s && typeof s.at === 'number' && typeof s.sec === 'number')
  return (
    !!o &&
    typeof o.trainSec === 'number' &&
    typeof o.fromSec === 'number' &&
    typeof o.toSec === 'number' &&
    typeof o.idleSec === 'number' &&
    typeof o.housedWaitSec === 'number' &&
    stretchList(o.stretches) &&
    stretchList(o.housedWaits) &&
    !!o.excluded &&
    typeof o.excluded.housedSec === 'number' &&
    typeof o.excluded.fullSec === 'number' &&
    typeof o.excluded.unclearSec === 'number'
  )
}
