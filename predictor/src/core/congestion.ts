// ---------------------------------------------------------------------------
// Rest and fixture congestion.
//
// A side playing its third match in eight days is measurably worse than the
// same side after a free week — through fatigue and, just as much, through
// rotation. The effect is small next to team strength, which is why it enters
// as a bounded log-lambda nudge rather than a headline factor.
// ---------------------------------------------------------------------------

import { clamp } from './math.ts'

const DAY_MS = 86400000

export interface RestProfile {
  /** Days since this team's previous match; null when none is known. */
  daysRest: number | null
  /** Matches played by this team in the 14 days before kickoff. */
  matchesIn14Days: number
  /** Additive log-lambda adjustment for this team's attack. */
  logShift: number
  /** Short human-readable summary, or null when nothing notable. */
  note: string | null
}

export interface CongestionOptions {
  /** Log-lambda penalty applied at the shortest turnaround. */
  maxPenalty: number
  /** Rest days at or above which there is no penalty. */
  fullRestDays: number
  /** Rest days at or below which the full penalty applies. */
  minRestDays: number
}

export const DEFAULT_CONGESTION: CongestionOptions = {
  maxPenalty: 0.07,
  fullRestDays: 6,
  minRestDays: 3,
}

/**
 * Rest profile for one team going into a fixture.
 *
 * `previousDates` is that team's earlier kickoffs (any competition we know
 * about), and only those strictly before `kickoff` are considered.
 */
export function restProfile(
  kickoff: number,
  previousDates: readonly number[],
  options: Partial<CongestionOptions> = {},
): RestProfile {
  const o = { ...DEFAULT_CONGESTION, ...options }
  const before = previousDates.filter((d) => d > 0 && d < kickoff).sort((a, b) => b - a)
  if (before.length === 0) {
    return { daysRest: null, matchesIn14Days: 0, logShift: 0, note: null }
  }
  const daysRest = (kickoff - before[0]!) / DAY_MS
  const matchesIn14Days = before.filter((d) => kickoff - d <= 14 * DAY_MS).length

  // Linear ramp between min and full rest.
  const span = Math.max(0.001, o.fullRestDays - o.minRestDays)
  const shortfall = clamp((o.fullRestDays - daysRest) / span, 0, 1)
  let logShift = -o.maxPenalty * shortfall

  // A third match in a fortnight compounds the turnaround penalty.
  if (matchesIn14Days >= 3) logShift -= 0.02 * (matchesIn14Days - 2)
  // `+ 0` normalises -0, which would otherwise leak into artifacts and read
  // oddly in comparisons despite being numerically zero.
  logShift = clamp(logShift, -0.15, 0) + 0

  let note: string | null = null
  if (daysRest <= 3.5) note = `Only ${daysRest.toFixed(0)} days since their last match`
  else if (matchesIn14Days >= 3) note = `${matchesIn14Days} matches in the last fortnight`

  return { daysRest, matchesIn14Days, logShift, note }
}
