// Your usual: what you yourself reach at each checkpoint, from your recent
// matches, so a number is compared with your own level as well as with the
// ideal curve. The median of your last games with a reading there (your own
// civilisation's when there are enough of them), using the real number from a
// match checked against the real game when there is one.

import type { MatchRecord } from './matchRecorder.ts'
import type { BenchmarkResult } from './benchmarks.ts'

/** Matches looked at (the most recent). */
const RECENT = 10
/** Fewer matches than this with a reading at a checkpoint: no usual yet. */
export const MIN_GAMES = 3

export interface Usual {
  readonly value: number
  readonly games: number
  /** Only matches with the player's current civilisation. */
  readonly sameFaction: boolean
}

const clock = (sec: number) => `${Math.floor(sec / 60)}:${String(Math.round(sec % 60)).padStart(2, '0')}`

/** The value of one match at a checkpoint: the real one when it was checked, else the coach's agreed reading. */
function valueAt(m: MatchRecord, specId: string, atSec: number): number | null {
  const real = m.truth?.lines.find((l) => l.what === `Villagers at ${clock(atSec)}`)?.real
  if (specId === 'villager-curve' && typeof real === 'number') return real
  return m.benchmarks?.[specId]?.find((p) => p.atSec === atSec)?.actual ?? null
}

export function usualAt(history: readonly MatchRecord[], specId: string, atSec: number, faction: string | null): Usual | null {
  const withValue = history
    .map((m) => ({ m, v: valueAt(m, specId, atSec) }))
    .filter((x): x is { m: MatchRecord; v: number } => x.v !== null)
  const own = faction ? withValue.filter((x) => x.m.context?.playerFaction === faction) : []
  const pool = (own.length >= MIN_GAMES ? own : withValue).slice(-RECENT)
  if (pool.length < MIN_GAMES) return null
  const sorted = pool.map((x) => x.v).sort((a, b) => a - b)
  const mid = Math.floor(sorted.length / 2)
  const value = sorted.length % 2 ? sorted[mid] : Math.round((sorted[mid - 1] + sorted[mid]) / 2)
  return { value, games: pool.length, sameFaction: own.length >= MIN_GAMES }
}

/** Benchmark results with the player's usual at each checkpoint, and said in the statement. */
export function withUsual(results: readonly BenchmarkResult[], history: readonly MatchRecord[], faction: string | null): BenchmarkResult[] {
  return results.map((r) => {
    const points = r.points.map((p) => {
      const usual = usualAt(history, r.spec.id, p.atSec, faction)
      return usual ? { ...p, usual } : p
    })
    const worst = r.worst ? (points.find((p) => p.atSec === r.worst!.atSec) ?? r.worst) : null
    const usual = worst && 'usual' in worst ? (worst.usual as Usual) : null
    return {
      ...r,
      points,
      worst: worst && r.worst ? { ...worst, statement: usual ? `${r.worst.statement} ${usualSentence(r.spec.noun, worst.actual, usual)}` : r.worst.statement } : null,
    }
  })
}

/** "Your usual is 33 (your last 8 games as English)." / "That is your usual." */
export function usualSentence(noun: string, actual: number, u: Usual): string {
  const games = `your last ${u.games} games${u.sameFaction ? ' with this civilisation' : ''}`
  if (actual === u.value) return `That is your usual (${games}).`
  return `Your usual is ${u.value} ${noun} (${games}).`
}
