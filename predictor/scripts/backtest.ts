// ---------------------------------------------------------------------------
// Walk-forward backtest.
//
// Run: node scripts/backtest.ts [--season 2025-26] [--sweep]
//
// The model is refitted repeatedly through the target season, each time seeing
// only matches that had already been played. Nothing about the future leaks
// in, which is the only way a reported accuracy figure means anything.
//
// Scope note, stated plainly: this exercises ratings, the goals/xG blend,
// promoted priors and rest. It does NOT exercise the player-availability
// adjustment, because doing so honestly would need historical injury reports
// — knowing who was fit *before* each match — and the feed only carries
// today's status. Reconstructing line-ups from minutes played would leak the
// result. The availability effect is instead measured separately (teams
// missing two of their three top attackers scored 1.146 vs 1.520), and that
// measurement, not a backtest, is what sets its coefficient.
// ---------------------------------------------------------------------------

import { loadCorpus } from './ingest.ts'
import { fitModel, DEFAULT_PARAMS, type ModelParams } from './build-model.ts'
import { scoreMatrix, outcomeProbs } from '../src/core/dixonColes.ts'
import { dispersionFromUncertainty } from '../src/core/predict.ts'
import { outcomeOf, summarize, calibration, expectedCalibrationError, type ScoredPrediction } from '../src/core/metrics.ts'
import { restProfile } from '../src/core/congestion.ts'
import type { NormMatch } from './sources/types.ts'

/** Refit every N matches. Lower is more faithful but slower. */
const REFIT_EVERY = 10

function seasonTeams(matches: readonly NormMatch[], season: string): string[] {
  const s = new Set<string>()
  for (const m of matches) if (m.season === season) { s.add(m.home); s.add(m.away) }
  return [...s].sort()
}

export interface BacktestResult {
  season: string
  params: ModelParams
  scored: ScoredPrediction[]
}

export async function backtest(
  season: string,
  params: ModelParams,
  useRest: boolean,
): Promise<BacktestResult> {
  const corpus = await loadCorpus()
  const seasons = [...new Set(corpus.matches.map((m) => m.season))].sort()
  const prevSeason = seasons[seasons.indexOf(season) - 1] ?? ''
  const currentTeams = seasonTeams(corpus.matches, season)
  const previousTeams = seasonTeams(corpus.matches, prevSeason)

  const test = corpus.matches
    .filter((m) => m.season === season && m.finished && m.homeGoals !== null)
    .sort((a, b) => a.date - b.date)

  const kickoffsByTeam = new Map<string, number[]>()
  for (const m of corpus.matches) {
    for (const t of [m.home, m.away]) {
      if (!kickoffsByTeam.has(t)) kickoffsByTeam.set(t, [])
      kickoffsByTeam.get(t)!.push(m.date)
    }
  }

  const scored: ScoredPrediction[] = []
  let model: ReturnType<typeof fitModel> | null = null
  let lastFit = -1

  for (let i = 0; i < test.length; i++) {
    const m = test[i]!
    if (model === null || i - lastFit >= REFIT_EVERY) {
      model = fitModel(corpus.matches, corpus.championship, m.date, currentTeams, previousTeams, params)
      lastFit = i
    }

    const attackHome = model.ratings.attack[m.home] ?? 0
    const attackAway = model.ratings.attack[m.away] ?? 0
    const defenceHome = model.ratings.defence[m.home] ?? 0
    const defenceAway = model.ratings.defence[m.away] ?? 0

    let logHome = attackHome - defenceAway + model.ratings.homeAdvantage
    let logAway = attackAway - defenceHome

    if (useRest) {
      logHome += restProfile(m.date, kickoffsByTeam.get(m.home) ?? []).logShift
      logAway += restProfile(m.date, kickoffsByTeam.get(m.away) ?? []).logShift
    }

    const sdHome = model.ratingSd[m.home] ?? 0.15
    const sdAway = model.ratingSd[m.away] ?? 0.15
    const sigma = Math.sqrt(sdHome * sdHome + sdAway * sdAway + 0.08 * 0.08)

    const matrix = scoreMatrix(
      Math.exp(logHome),
      Math.exp(logAway),
      model.ratings.rho,
      dispersionFromUncertainty(sigma),
    )
    scored.push({ probs: outcomeProbs(matrix), actual: outcomeOf(m.homeGoals!, m.awayGoals!) })
  }

  return { season, params, scored }
}

function report(label: string, scored: ScoredPrediction[]): void {
  const s = summarize(scored)
  console.log(
    `  ${label.padEnd(34)} RPS ${s.rps.toFixed(4)}  logloss ${s.logLoss.toFixed(4)}  ` +
      `Brier ${s.brier.toFixed(4)}  acc ${(s.accuracy * 100).toFixed(1)}%  n=${s.n}`,
  )
}

const args = process.argv.slice(2)
const seasonArg = args.includes('--season') ? args[args.indexOf('--season') + 1]! : '2025-26'
const sweep = args.includes('--sweep')

console.log(`Walk-forward backtest on ${seasonArg} (refit every ${REFIT_EVERY} matches)\n`)

// Baselines first, so the model has something honest to beat.
const base = await backtest(seasonArg, DEFAULT_PARAMS, true)
const fixed: ScoredPrediction[] = base.scored.map((s) => ({
  probs: { home: 0.44, draw: 0.25, away: 0.31 },
  actual: s.actual,
}))
console.log('Baselines:')
report('fixed 44/25/31', fixed)

console.log('\nModel:')
report('full (xG blend + priors + rest)', base.scored)

const noRest = await backtest(seasonArg, DEFAULT_PARAMS, false)
report('without rest adjustment', noRest.scored)

const goalsOnly = await backtest(seasonArg, { ...DEFAULT_PARAMS, xgBlend: 0 }, true)
report('goals only (no xG blend)', goalsOnly.scored)

const noDecay = await backtest(seasonArg, { ...DEFAULT_PARAMS, xi: 0 }, true)
report('no time decay', noDecay.scored)

const bins = calibration(base.scored)
console.log(`\nCalibration (ECE ${expectedCalibrationError(bins).toFixed(4)}):`)
for (const b of bins) {
  if (b.count < 5) continue
  const bar = '#'.repeat(Math.round(b.observed * 40))
  console.log(
    `  ${(b.lower * 100).toFixed(0).padStart(3)}-${(b.upper * 100).toFixed(0).padEnd(3)}% ` +
      `n=${String(b.count).padStart(4)}  predicted ${(b.predicted * 100).toFixed(1).padStart(5)}%  ` +
      `observed ${(b.observed * 100).toFixed(1).padStart(5)}%  ${bar}`,
  )
}

if (sweep) {
  console.log('\nHyperparameter sweep:')
  for (const xi of [0.0012, 0.0018, 0.0025, 0.0035]) {
    for (const xgBlend of [0.5, 0.6, 0.7, 0.8]) {
      const r = await backtest(seasonArg, { ...DEFAULT_PARAMS, xi, xgBlend }, true)
      report(`xi=${xi} xgBlend=${xgBlend}`, r.scored)
    }
  }
}
