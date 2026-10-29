// What the coach read during a match, kept compactly with the match record so
// it can be checked afterwards against what really happened (the game's own
// summary, or numbers the player enters). Every 20 seconds of game time (the
// step AoE4World's summaries use), the numbers on screen then; and each change
// of a watched enum (the age) with its time. No frames, only numbers.

import type { VisibleState } from '../types.ts'

/** Game seconds between two rows (AoE4World's resource timelines are every 20 s). */
export const LOG_STEP_SEC = 20
/** At most this many rows (100 minutes). */
const MAX_ROWS = 300
/** A reading older than this (ms of capture time) is not logged. */
const FRESH_MS = 6000

export interface ReadingLog {
  /** Game time of each row (seconds). */
  readonly t: readonly number[]
  /** Reading → its value at each row (null where it was not on screen). */
  readonly values: Readonly<Record<string, readonly (number | null)[]>>
  /** Changes of watched readings (the age): [game seconds, key, value]. */
  readonly changes: readonly (readonly [number, string, string])[]
}

export class ReadingLogger {
  private readonly keys: readonly string[]
  private readonly timeKey: string
  private readonly watched: readonly string[]
  private t: number[] = []
  private values = new Map<string, (number | null)[]>()
  private changes: [number, string, string][] = []
  private lastWatched = new Map<string, string>()

  constructor(keys: readonly string[], timeKey: string, watched: readonly string[] = []) {
    this.keys = keys
    this.timeKey = timeKey
    this.watched = watched
  }

  observe(state: VisibleState, now: number): void {
    const clock = state.values[this.timeKey]
    if (!clock || clock.conflicted || typeof clock.value !== 'number' || now - clock.observedAt > FRESH_MS) return
    const sec = clock.value
    for (const k of this.watched) {
      const v = state.values[k]
      if (!v || v.conflicted || (typeof v.value !== 'string' && typeof v.value !== 'number')) continue
      const value = String(v.value)
      if (this.lastWatched.get(k) !== value) {
        this.lastWatched.set(k, value)
        if (this.changes.length < 50) this.changes.push([Math.round(sec), k, value])
      }
    }
    const row = Math.round(sec / LOG_STEP_SEC) * LOG_STEP_SEC
    // Within a few seconds of a step, once per step.
    if (Math.abs(sec - row) > 5 || this.t[this.t.length - 1] === row || this.t.length >= MAX_ROWS) return
    this.t.push(row)
    for (const k of this.keys) {
      const v = state.values[k]
      const ok = v && !v.conflicted && typeof v.value === 'number' && now - v.observedAt <= FRESH_MS
      const list = this.values.get(k) ?? new Array<number | null>(this.t.length - 1).fill(null)
      list.push(ok ? (v.value as number) : null)
      this.values.set(k, list)
    }
  }

  /** The log of the match so far, or null when nothing was read. */
  take(): ReadingLog | null {
    const log = this.t.length || this.changes.length ? { t: this.t, values: Object.fromEntries(this.values), changes: this.changes } : null
    this.t = []
    this.values = new Map()
    this.changes = []
    this.lastWatched = new Map()
    return log
  }
}

export function isReadingLog(v: unknown): v is ReadingLog {
  const o = v as ReadingLog | null
  if (!o || !Array.isArray(o.t) || o.t.length > MAX_ROWS || !o.t.every((x) => typeof x === 'number')) return false
  if (typeof o.values !== 'object' || o.values === null) return false
  for (const list of Object.values(o.values)) if (!Array.isArray(list) || list.length !== o.t.length || !list.every((x) => x === null || typeof x === 'number')) return false
  return Array.isArray(o.changes) && o.changes.length <= 50 && o.changes.every((c) => Array.isArray(c) && c.length === 3 && typeof c[0] === 'number' && typeof c[1] === 'string' && typeof c[2] === 'string')
}
