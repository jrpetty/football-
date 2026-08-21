// ---------------------------------------------------------------------------
// The model's measured reference performance.
//
// Kept in one place because these numbers appear in several parts of the UI
// and in the README, and prose copies of a figure drift the moment the model
// changes. Reproduce any of them with the commands named below.
// ---------------------------------------------------------------------------

export interface BacktestReference {
  season: string
  matches: number
  command: string
  /** Walk-forward, refitting through the season, never seeing a result early. */
  model: { rps: number; logLoss: number; brier: number; accuracy: number }
  /** Fixed league-average probabilities — the honest "do nothing" comparison. */
  baseline: { rps: number; logLoss: number; brier: number; accuracy: number }
  /** Where a strong bookmaker sits, for scale. */
  bookmakerRps: number
  calibrationError: number
}

export const BACKTEST: BacktestReference = {
  season: '2025-26',
  matches: 380,
  command: 'npm run backtest',
  model: { rps: 0.2095, logLoss: 1.0284, brier: 0.6179, accuracy: 0.482 },
  baseline: { rps: 0.2274, logLoss: 1.0808, brier: 0.6542, accuracy: 0.426 },
  bookmakerRps: 0.195,
  calibrationError: 0.0157,
}

/** Standard error on RPS at this sample size — the noise floor for comparisons. */
export const RPS_STANDARD_ERROR = 0.008
