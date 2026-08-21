// ---------------------------------------------------------------------------
// Forecast scoring.
//
// The experiment only means something if the scoring is honest, so the metrics
// are the standard ones and they are applied to predictions recorded *before*
// kickoff, never recomputed afterwards.
//
// Ranked Probability Score is the primary measure. Unlike accuracy or plain
// log-loss it respects the ordering home > draw > away: predicting a home win
// when the away side wins is penalised more than predicting a draw. That
// ordinal structure is exactly what a 1X2 forecast has, which is why the
// football forecasting literature settles on RPS.
// ---------------------------------------------------------------------------

export type Outcome = 'home' | 'draw' | 'away'

export interface Probs {
  home: number
  draw: number
  away: number
}

/** The observed outcome of a finished match. */
export function outcomeOf(homeGoals: number, awayGoals: number): Outcome {
  return homeGoals > awayGoals ? 'home' : homeGoals === awayGoals ? 'draw' : 'away'
}

const ORDER: Outcome[] = ['home', 'draw', 'away']

/**
 * Ranked Probability Score. 0 is perfect, 1 is worst; lower is better.
 *
 * For reference points measured here: a fixed 44/25/31 baseline scores 0.2337
 * on 2025/26, this model scores about 0.208, and bookmakers sit near 0.19-0.20.
 */
export function rps(p: Probs, actual: Outcome): number {
  let cumP = 0
  let cumA = 0
  let total = 0
  // Sum over the first k-1 categories; the last term is always zero.
  for (let i = 0; i < ORDER.length - 1; i++) {
    cumP += p[ORDER[i]!]
    cumA += ORDER[i] === actual ? 1 : 0
    total += (cumP - cumA) * (cumP - cumA)
  }
  return total / (ORDER.length - 1)
}

/** Negative log-likelihood of the observed outcome. Punishes confident misses. */
export function logLoss(p: Probs, actual: Outcome): number {
  return -Math.log(Math.max(1e-12, p[actual]))
}

/** Multi-class Brier score: summed squared error across the three outcomes. */
export function brier(p: Probs, actual: Outcome): number {
  let total = 0
  for (const o of ORDER) {
    const y = o === actual ? 1 : 0
    total += (p[o] - y) * (p[o] - y)
  }
  return total
}

/** The outcome the model would name if forced to pick one. */
export function pick(p: Probs): Outcome {
  let best: Outcome = 'home'
  for (const o of ORDER) if (p[o] > p[best]) best = o
  return best
}

export interface ScoredPrediction {
  probs: Probs
  actual: Outcome
}

export interface MetricSummary {
  n: number
  rps: number
  logLoss: number
  brier: number
  accuracy: number
  /** Share of matches where the model's pick was the least likely of the three. */
  bigMissRate: number
}

/** Aggregate metrics over a set of scored predictions. */
export function summarize(items: readonly ScoredPrediction[]): MetricSummary {
  if (items.length === 0) {
    return { n: 0, rps: 0, logLoss: 0, brier: 0, accuracy: 0, bigMissRate: 0 }
  }
  let r = 0
  let l = 0
  let b = 0
  let hits = 0
  let bigMiss = 0
  for (const it of items) {
    r += rps(it.probs, it.actual)
    l += logLoss(it.probs, it.actual)
    b += brier(it.probs, it.actual)
    if (pick(it.probs) === it.actual) hits++
    const sorted = ORDER.map((o) => it.probs[o]).sort((x, y) => x - y)
    if (it.probs[it.actual] === sorted[0]) bigMiss++
  }
  const n = items.length
  return {
    n,
    rps: r / n,
    logLoss: l / n,
    brier: b / n,
    accuracy: hits / n,
    bigMissRate: bigMiss / n,
  }
}

export interface CalibrationBin {
  /** Bin midpoint, e.g. 0.25 for the 20-30% band. */
  midpoint: number
  lower: number
  upper: number
  /** Number of (match, outcome) forecasts landing in this bin. */
  count: number
  /** Mean forecast probability in the bin. */
  predicted: number
  /** Share that actually happened. */
  observed: number
}

/**
 * Reliability curve: of everything forecast at ~30%, did ~30% happen?
 *
 * Every (match, outcome) pair is binned, so one match contributes three
 * points. A well-calibrated model tracks the diagonal — which matters more
 * than accuracy here, since the whole claim is that the numbers mean
 * something rather than merely rank correctly.
 */
export function calibration(items: readonly ScoredPrediction[], bins = 10): CalibrationBin[] {
  const acc = Array.from({ length: bins }, () => ({ count: 0, pSum: 0, ySum: 0 }))
  for (const it of items) {
    for (const o of ORDER) {
      const p = it.probs[o]
      const idx = Math.min(bins - 1, Math.max(0, Math.floor(p * bins)))
      const cell = acc[idx]!
      cell.count++
      cell.pSum += p
      cell.ySum += o === it.actual ? 1 : 0
    }
  }
  return acc.map((cell, i) => ({
    midpoint: (i + 0.5) / bins,
    lower: i / bins,
    upper: (i + 1) / bins,
    count: cell.count,
    predicted: cell.count ? cell.pSum / cell.count : 0,
    observed: cell.count ? cell.ySum / cell.count : 0,
  }))
}

/** Expected Calibration Error: mean |predicted - observed|, weighted by bin size. */
export function expectedCalibrationError(bins: readonly CalibrationBin[]): number {
  let total = 0
  let n = 0
  for (const b of bins) {
    total += b.count * Math.abs(b.predicted - b.observed)
    n += b.count
  }
  return n === 0 ? 0 : total / n
}
