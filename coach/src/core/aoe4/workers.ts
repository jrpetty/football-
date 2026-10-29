// Villagers on each resource (Age of Empires IV): the small numbers beside
// food, wood, gold and stone on the HUD (the game's "Show Advanced Resource"
// option). They count villagers gathering or carrying that resource, plus
// fishing ships under food and traders under gold; villagers building or
// walking are in none of them.
//
// - A build can hold the split it wants at a few times ("by 4:00: 8 food,
//   6 wood, 2 gold"); during play against the AI the coach compares and says
//   the biggest shortfall.
// - Gold for the Castle Age: from what the player really gathers per villager
//   on gold (measured from the gold count going up), when the 600 gold will be
//   there against the build's Castle time, and how many more on gold would make it.

import type { ReadingLog } from '../truth/readingLog.ts'

export const RESOURCES = ['food', 'wood', 'gold', 'stone'] as const
export type Resource = (typeof RESOURCES)[number]
export const WORKER_KEYS: Readonly<Record<Resource, string>> = { food: 'foodWorkers', wood: 'woodWorkers', gold: 'goldWorkers', stone: 'stoneWorkers' }

export type Workers = Readonly<Record<Resource, number>>

/** The split a build wants by a time. */
export interface WorkerSplit extends Workers {
  readonly atSec: number
}

export const MAX_SPLITS = 8
/** A shortfall is said from this many villagers. */
const SHORT_BY = 2

const clock = (sec: number) => `${Math.floor(sec / 60)}:${String(Math.round(sec % 60)).padStart(2, '0')}`

/** "Build wants 6 on wood by 4:00, you have 3." (the two biggest shortfalls), or null when on plan. */
export function splitLine(want: WorkerSplit, have: Workers): string | null {
  const short = RESOURCES.map((r) => ({ r, want: want[r], have: have[r], by: want[r] - have[r] }))
    .filter((x) => x.by >= SHORT_BY)
    .sort((a, b) => b.by - a.by)
    .slice(0, 2)
  if (!short.length) return null
  if (short.length === 1) return `Build wants ${short[0].want} on ${short[0].r} by ${clock(want.atSec)}, you have ${short[0].have}.`
  return `Build wants ${short[0].want} on ${short[0].r} and ${short[1].want} on ${short[1].r} by ${clock(want.atSec)}; you have ${short[0].have} and ${short[1].have}.`
}

export const splitText = (w: Workers) => RESOURCES.map((r) => `${w[r]} ${r}`).join(', ')

/** What the coach read of the split in a match (the 20-second log): the row nearest each time, all four read. */
export function splitAt(log: ReadingLog | undefined | null, sec: number, within = 15): Workers | null {
  if (!log) return null
  let best: { d: number; w: Workers } | null = null
  log.t.forEach((t, i) => {
    const d = Math.abs(t - sec)
    if (d > within || (best && best.d <= d)) return
    const vals = RESOURCES.map((r) => log.values[WORKER_KEYS[r]]?.[i])
    if (vals.every((v) => typeof v === 'number')) best = { d, w: Object.fromEntries(RESOURCES.map((r, k) => [r, vals[k] as number])) as unknown as Workers }
  })
  return (best as { w: Workers } | null)?.w ?? null
}

/** A build's split targets from a match the coach watched: every 2 minutes up to `untilSec`. */
export function splitsFromReadings(log: ReadingLog | undefined | null, untilSec: number): WorkerSplit[] {
  const out: WorkerSplit[] = []
  for (let sec = 120; sec <= Math.min(untilSec, 1200) && out.length < MAX_SPLITS; sec += 120) {
    const w = splitAt(log, sec)
    if (w) out.push({ atSec: sec, ...w })
  }
  return out
}

export interface SplitCheck {
  readonly want: WorkerSplit
  readonly had: Workers | null
  readonly line: string | null
}

/** After a match: each split the build wants against what was read then. */
export function compareSplits(splits: readonly WorkerSplit[] | undefined, log: ReadingLog | undefined | null): SplitCheck[] {
  return (splits ?? []).map((want) => {
    const had = splitAt(log, want.atSec)
    return { want, had, line: had ? splitLine(want, had) : null }
  })
}

export function isWorkerSplit(v: unknown): v is WorkerSplit {
  const o = v as WorkerSplit | null
  return !!o && typeof o.atSec === 'number' && o.atSec >= 0 && o.atSec <= 3600 && RESOURCES.every((r) => typeof o[r] === 'number' && Number.isInteger(o[r]) && o[r] >= 0 && o[r] <= 200)
}

// --- Gold for the Castle Age ------------------------------------------------------------------

/** The usual Castle Age cost in gold (with 1,200 food). */
export const CASTLE_GOLD = 600
/** Gold readings further apart than this are not used to measure the gathering rate. */
const RATE_GAP_SEC = 8
/** The rate is trusted once this many villager-seconds on gold were measured. */
const RATE_MIN_WORKER_SEC = 120
/** Nobody on gold for this long in Feudal is said. */
const NONE_ON_GOLD_SEC = 60
/** Said when the gold arrives this much later than the build's Castle time. */
const LATE_BY_SEC = 45

export interface GoldReading {
  readonly t: number
  readonly age: string | null
  readonly gold: number | null
  readonly goldWorkers: number | null
}

export class GoldForCastle {
  private targetSec: number | null
  private feudalAt: number | null = null
  private last: { t: number; gold: number } | null = null
  private gained = 0
  private workerSec = 0
  private noneSince: number | null = null
  private saidNone = false
  private saidLate: number[] = []

  /** `targetSec`: when the build reaches the Castle Age (null without a build). */
  constructor(targetSec: number | null) {
    this.targetSec = targetSec
  }

  /** Gold gathered per villager per second, once measured well enough. */
  get rate(): number | null {
    return this.workerSec >= RATE_MIN_WORKER_SEC && this.gained > 0 ? this.gained / this.workerSec : null
  }

  observe(r: GoldReading): { text: string; topic: string; viaBuild: boolean } | null {
    if (r.age && r.age !== 'feudal') {
      if (r.age === 'dark') this.feudalAt = null
      this.last = null
      this.noneSince = null
      return null
    }
    if (r.age === 'feudal' && this.feudalAt === null) this.feudalAt = r.t
    if (r.gold === null || r.gold < 0) return null
    // The gathering rate: the gold count going up, over the villagers on gold (spending is a drop, not counted).
    if (this.last && r.goldWorkers !== null && r.t > this.last.t && r.t - this.last.t <= RATE_GAP_SEC && r.goldWorkers > 0) {
      // An interval with spending hides what was gathered in it: left out of both.
      const up = r.gold - this.last.gold
      if (up >= 0 && up < 400) {
        this.gained += up
        this.workerSec += r.goldWorkers * (r.t - this.last.t)
      }
    }
    this.last = { t: r.t, gold: r.gold }
    if (this.feudalAt === null || r.goldWorkers === null || r.gold >= CASTLE_GOLD) return null
    if (r.goldWorkers === 0) this.noneSince ??= r.t
    else this.noneSince = null
    const inFeudal = r.t - this.feudalAt
    if (!this.saidNone && this.noneSince !== null && r.t - this.noneSince >= NONE_ON_GOLD_SEC && inFeudal >= NONE_ON_GOLD_SEC) {
      this.saidNone = true
      return { text: `No villagers on gold for a minute, and the Castle Age needs ${CASTLE_GOLD} gold (you have ${r.gold}).`, topic: 'gold-for-castle:none', viaBuild: false }
    }
    const rate = this.rate
    const target = this.targetSec
    if (rate === null || target === null || r.goldWorkers === 0 || r.t >= target || inFeudal < 45) return null
    if (this.saidLate.length >= 2 || (this.saidLate.length && r.t - this.saidLate[this.saidLate.length - 1] < 90)) return null
    const left = CASTLE_GOLD - r.gold
    const eta = r.t + left / (r.goldWorkers * rate)
    if (eta <= target + LATE_BY_SEC) return null
    const need = Math.ceil(left / (rate * Math.max(30, target - r.t)))
    const more = need - r.goldWorkers
    if (more < 1) return null
    this.saidLate.push(r.t)
    return {
      text: `Gold for Castle: ${r.gold} of ${CASTLE_GOLD} with ${r.goldWorkers} on gold, there around ${clock(eta)}; the build ages up at ${clock(target)}. About ${more} more on gold.`,
      topic: `gold-for-castle:${Math.round(r.t)}`,
      viaBuild: true,
    }
  }

  reset(targetSec: number | null): void {
    this.targetSec = targetSec
    this.feudalAt = null
    this.last = null
    this.gained = 0
    this.workerSec = 0
    this.noneSince = null
    this.saidNone = false
    this.saidLate = []
  }
}
