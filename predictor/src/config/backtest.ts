// ---------------------------------------------------------------------------
// The model's measured reference performance.
//
// Kept in one place because these numbers appear in several parts of the UI
// and in the README, and prose copies of a figure drift the moment the model
// changes. Reproduce any of them with the commands named below.
//
// The headline is pooled across five seasons rather than one. A single season
// is 380 matches, where the standard error on RPS is about 0.008 — wide enough
// that a real improvement and a real regression look identical. Pooling to
// 1,900 halves it, and it was that difference that settled whether per-team
// home advantage belonged in the model: it looked slightly harmful on 2025/26
// alone and was clearly worth having across five seasons.
// ---------------------------------------------------------------------------

export interface Scoreboard {
  rps: number
  logLoss: number
  brier: number
  accuracy: number
}

export interface BacktestReference {
  /** Seasons pooled into the headline figures. */
  season: string
  matches: number
  command: string
  /** Walk-forward, refitting through each season, never seeing a result early. */
  model: Scoreboard
  /** Fixed league-average probabilities — the honest "do nothing" comparison. */
  baseline: Scoreboard
  /** Where a strong bookmaker sits, for scale. */
  bookmakerRps: number
  calibrationError: number
  /**
   * The most recent season on its own. Kept visible because it is the hardest
   * of the five and noticeably worse than the pooled figure — quoting only the
   * average would make the model look steadier than it is.
   */
  latest: { season: string; matches: number; model: Scoreboard; baseline: Scoreboard }
}

export const BACKTEST: BacktestReference = {
  season: '2021-22 to 2025-26',
  matches: 1900,
  command: 'npm run backtest',
  model: { rps: 0.2011, logLoss: 0.9794, brier: 0.5824, accuracy: 0.5379 },
  baseline: { rps: 0.232, logLoss: 1.0679, brier: 0.6461, accuracy: 0.4416 },
  bookmakerRps: 0.195,
  calibrationError: 0.0156,
  latest: {
    season: '2025-26',
    matches: 380,
    model: { rps: 0.2108, logLoss: 1.0332, brier: 0.6213, accuracy: 0.4895 },
    baseline: { rps: 0.2274, logLoss: 1.0808, brier: 0.6542, accuracy: 0.4263 },
  },
}

/**
 * Standard error on RPS at the pooled sample size — the noise floor for
 * comparisons. Roughly 0.008 for a single 380-match season, halved by pooling
 * five of them. Any difference smaller than this is not evidence of anything.
 */
export const RPS_STANDARD_ERROR = 0.0036
