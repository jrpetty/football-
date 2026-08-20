// ---------------------------------------------------------------------------
// The Dixon-Coles scoreline model.
//
// Two independent scoring rates would be the naive model, but real football
// has more 0-0s and 1-1s than independence predicts — teams at 0-0 late play
// differently than the average. Dixon & Coles (1997) handle that with a
// correction on the four low scorelines, which is where most of the
// probability mass sits.
//
// Everything works from a joint score matrix rather than closed forms, so any
// derived market (1X2, BTTS, over/under, clean sheets, exact score) is read off
// the same object and they can never disagree with each other.
// ---------------------------------------------------------------------------

import { negBinomialVector, poissonVector, round } from './math.ts'

/** Highest goal count modelled per side. P(7+ goals) is ~1e-5; 10 is ample. */
export const MAX_GOALS = 10

export interface ScoreMatrix {
  /** `grid[h][a]` = P(home scores h AND away scores a). Sums to 1. */
  grid: number[][]
  lambdaHome: number
  lambdaAway: number
  rho: number
}

export interface OutcomeProbs {
  home: number
  draw: number
  away: number
}

/**
 * Dixon-Coles low-score dependency correction.
 *
 * `rho < 0` shifts mass toward 0-0 and 1-1 and away from 1-0 and 0-1, which is
 * the direction real data shows. Only the four lowest scorelines are touched;
 * everything else is left independent.
 */
export function tau(x: number, y: number, lh: number, la: number, rho: number): number {
  if (rho === 0) return 1
  if (x === 0 && y === 0) return 1 - lh * la * rho
  if (x === 0 && y === 1) return 1 + lh * rho
  if (x === 1 && y === 0) return 1 + la * rho
  if (x === 1 && y === 1) return 1 - rho
  return 1
}

/**
 * Build the joint scoreline distribution.
 *
 * `dispersion` is the Negative Binomial `r`. Leaving it Infinity gives the
 * classic Poisson model; a finite value fattens the tails to represent
 * uncertainty in the rate itself (thin data, missing players, promoted sides).
 * That is what stops the model being systematically overconfident about
 * favourites — and it is where honest upset probability comes from.
 */
export function scoreMatrix(
  lambdaHome: number,
  lambdaAway: number,
  rho: number,
  dispersion: number = Infinity,
): ScoreMatrix {
  const ph = isFinite(dispersion)
    ? negBinomialVector(lambdaHome, dispersion, MAX_GOALS)
    : poissonVector(lambdaHome, MAX_GOALS)
  const pa = isFinite(dispersion)
    ? negBinomialVector(lambdaAway, dispersion, MAX_GOALS)
    : poissonVector(lambdaAway, MAX_GOALS)

  const grid: number[][] = []
  let total = 0
  for (let h = 0; h <= MAX_GOALS; h++) {
    const row = new Array<number>(MAX_GOALS + 1)
    for (let a = 0; a <= MAX_GOALS; a++) {
      // tau can go slightly negative for extreme rho; clamp so we never emit
      // a negative probability.
      const t = Math.max(0, tau(h, a, lambdaHome, lambdaAway, rho))
      const p = ph[h]! * pa[a]! * t
      row[a] = p
      total += p
    }
    grid.push(row)
  }
  // Truncation at MAX_GOALS and the tau correction both cost a little mass;
  // renormalising keeps the matrix a proper distribution.
  if (total > 0) {
    for (let h = 0; h <= MAX_GOALS; h++) {
      for (let a = 0; a <= MAX_GOALS; a++) grid[h]![a] = grid[h]![a]! / total
    }
  }
  return { grid, lambdaHome, lambdaAway, rho }
}

/** Home win / draw / away win, read off the score matrix. */
export function outcomeProbs(m: ScoreMatrix): OutcomeProbs {
  let home = 0
  let draw = 0
  let away = 0
  for (let h = 0; h <= MAX_GOALS; h++) {
    for (let a = 0; a <= MAX_GOALS; a++) {
      const p = m.grid[h]![a]!
      if (h > a) home += p
      else if (h === a) draw += p
      else away += p
    }
  }
  return { home, draw, away }
}

/** P(both teams score at least one). */
export function bttsProb(m: ScoreMatrix): number {
  let p = 0
  for (let h = 1; h <= MAX_GOALS; h++) for (let a = 1; a <= MAX_GOALS; a++) p += m.grid[h]![a]!
  return p
}

/** P(total goals > `line`). Use 2.5 for the usual over/under. */
export function overProb(m: ScoreMatrix, line: number): number {
  let p = 0
  for (let h = 0; h <= MAX_GOALS; h++) {
    for (let a = 0; a <= MAX_GOALS; a++) if (h + a > line) p += m.grid[h]![a]!
  }
  return p
}

/** P(home keeps a clean sheet) and P(away keeps a clean sheet). */
export function cleanSheetProbs(m: ScoreMatrix): { home: number; away: number } {
  let home = 0
  let away = 0
  for (let h = 0; h <= MAX_GOALS; h++) home += m.grid[h]![0]!
  for (let a = 0; a <= MAX_GOALS; a++) away += m.grid[0]![a]!
  return { home, away }
}

/** Expected goals for each side under the (corrected, truncated) matrix. */
export function expectedGoals(m: ScoreMatrix): { home: number; away: number } {
  let home = 0
  let away = 0
  for (let h = 0; h <= MAX_GOALS; h++) {
    for (let a = 0; a <= MAX_GOALS; a++) {
      home += h * m.grid[h]![a]!
      away += a * m.grid[h]![a]!
    }
  }
  return { home, away }
}

export interface Scoreline {
  home: number
  away: number
  prob: number
}

/**
 * The `n` most likely exact scorelines, descending.
 *
 * Artifacts store these rather than the full 11x11 grid: the top dozen carry
 * most of the mass and the client can rebuild the matrix from the lambdas
 * whenever it needs the whole thing.
 */
export function topScorelines(m: ScoreMatrix, n: number): Scoreline[] {
  const all: Scoreline[] = []
  for (let h = 0; h <= MAX_GOALS; h++) {
    for (let a = 0; a <= MAX_GOALS; a++) all.push({ home: h, away: a, prob: m.grid[h]![a]! })
  }
  all.sort((x, y) => y.prob - x.prob)
  return all.slice(0, n).map((s) => ({ home: s.home, away: s.away, prob: round(s.prob, 5) }))
}

/**
 * Most likely scoreline *conditional on each outcome* — the honest way to
 * answer "what would an upset actually look like?". The single most likely
 * score is often a draw even when a win is the likelier outcome, so quoting
 * only the global mode misleads.
 */
export function modalScoreByOutcome(m: ScoreMatrix): {
  home: Scoreline
  draw: Scoreline
  away: Scoreline
} {
  let home: Scoreline = { home: 1, away: 0, prob: 0 }
  let draw: Scoreline = { home: 0, away: 0, prob: 0 }
  let away: Scoreline = { home: 0, away: 1, prob: 0 }
  for (let h = 0; h <= MAX_GOALS; h++) {
    for (let a = 0; a <= MAX_GOALS; a++) {
      const p = m.grid[h]![a]!
      if (h > a) {
        if (p > home.prob) home = { home: h, away: a, prob: p }
      } else if (h === a) {
        if (p > draw.prob) draw = { home: h, away: a, prob: p }
      } else if (p > away.prob) away = { home: h, away: a, prob: p }
    }
  }
  return { home, draw, away }
}
