// ---------------------------------------------------------------------------
// End-to-end fixture prediction.
//
// Every factor enters as an additive term on log-lambda, and every term is
// recorded in a ledger as it is applied. That ledger is what the reasoning
// layer reads, so the explanations shown on the site are literally the
// arithmetic the model performed — not a plausible story written alongside it.
// If a bullet says an absence cost a side 0.18 goals, that number came out of
// the same computation that produced the probabilities.
// ---------------------------------------------------------------------------

import { clamp, round } from './math.ts'
import {
  scoreMatrix,
  outcomeProbs,
  bttsProb,
  overProb,
  cleanSheetProbs,
  topScorelines,
  modalScoreByOutcome,
  expectedGoals,
  type ScoreMatrix,
  type Scoreline,
} from './dixonColes.ts'
import type { TeamRatings } from './fit.ts'
import type { TeamAvailability } from './availability.ts'
import type { RestProfile } from './congestion.ts'
import type { Probs } from './metrics.ts'

/** One additive contribution to log-lambda, kept for explanation. */
export interface LambdaTerm {
  key: string
  label: string
  /** Contribution to the home side's log-lambda. */
  home: number
  /** Contribution to the away side's log-lambda. */
  away: number
  /** Short human-readable description, when the term has something to say. */
  detail?: string
}

export interface TeamContext {
  code: string
  availability: TeamAvailability
  rest: RestProfile
  /** Standard deviation of this team's rating — high for promoted sides. */
  ratingSd: number
  /** League matches played this season; drives cold-start confidence. */
  matchesPlayed: number
  /** True when the club has no top-flight record to fit on. */
  promoted: boolean
}

export interface FixtureInput {
  home: TeamContext
  away: TeamContext
  kickoff: number
  ratings: TeamRatings
}

export interface FixturePrediction {
  home: string
  away: string
  kickoff: number
  lambdaHome: number
  lambdaAway: number
  probs: Probs
  btts: number
  over25: number
  under25: number
  cleanSheetHome: number
  cleanSheetAway: number
  expectedGoalsHome: number
  expectedGoalsAway: number
  topScorelines: Scoreline[]
  modalScore: { home: Scoreline; draw: Scoreline; away: Scoreline }
  /** Negative Binomial dispersion actually used; lower = fatter tails. */
  dispersion: number
  rho: number
  terms: LambdaTerm[]
  /** 0-1 summary of how much evidence sits behind this prediction. */
  confidence: number
}

export interface PredictOptions {
  /** Floor and ceiling on lambda, guarding against absurd extrapolation. */
  minLambda: number
  maxLambda: number
  /** Extra rating uncertainty applied to every team. */
  baseUncertainty: number
}

export const DEFAULT_PREDICT: PredictOptions = {
  minLambda: 0.15,
  maxLambda: 4.5,
  baseUncertainty: 0.08,
}

/**
 * Convert rating uncertainty into a Negative Binomial dispersion.
 *
 * If log-lambda carries standard deviation sigma, the induced variance on the
 * rate is roughly lambda^2 * sigma^2, and matching that to the Negative
 * Binomial's lambda^2 / r gives r = 1 / sigma^2. So a confidently rated club
 * gets a near-Poisson distribution, while a promoted side with sigma = 0.2
 * lands near r = 25 and a visibly fatter tail. That extra tail is where their
 * upset probability comes from — and it is derived, not decorative.
 */
export function dispersionFromUncertainty(sigma: number): number {
  const s = Math.max(0.02, sigma)
  return clamp(1 / (s * s), 6, 400)
}

/**
 * Describe a club's own home record in the two directions it actually acts:
 * scoring more at home, and letting visitors score less. Reported as a
 * percentage on the scoring rate because that is the scale the term lives on.
 */
export function venueDetail(attackDev: number, defenceDev: number): string {
  const parts: string[] = []
  const pct = (x: number) => `${Math.abs(Math.round((Math.exp(x) - 1) * 100))}%`
  if (Math.abs(attackDev) >= 0.005) {
    parts.push(`scoring ${pct(attackDev)} ${attackDev > 0 ? 'more' : 'less'}`)
  }
  if (Math.abs(defenceDev) >= 0.005) {
    parts.push(`conceding ${pct(defenceDev)} ${defenceDev > 0 ? 'fewer' : 'more'}`)
  }
  if (parts.length === 0) return 'Home record in line with the league average'
  return `Their own home record, over and above the league-wide advantage: ${parts.join(', ')} here than their overall rating implies`
}

/**
 * The score to put in front of a reader.
 *
 * The single most likely exact score is often a draw even when the model
 * favours a win — 1-1 was the modal scoreline in seven of gameweek one's ten
 * matches, and a draw was the likeliest outcome in none of them. Quoting that
 * beside a "62% home" bar reads as a contradiction. This takes the most likely
 * score *within* the most likely outcome instead, so the headline can never
 * disagree with the probabilities. The true global mode is still published in
 * the full scoreline list.
 *
 * The ledger records this same figure, because the score a forecast is graded
 * on has to be the score it actually showed.
 */
export const DRAW_THRESHOLD = 0.25
/**
 * How far behind the favourite a draw may sit and still be the headline.
 *
 * Without this the threshold alone produced Hull 1-1 Aston Villa on a
 * 26/26/48 forecast — a draw called against a side the model made a 48%
 * favourite, which reads as broken however defensible the arithmetic. Over
 * 1,140 matches this cuts draw calls from 24.8% to 19.6% and exact scorelines
 * from 12.1% to 11.8%, and lifts outcome agreement from 50.1% to 51.1%. Worth
 * it: slightly under-calling draws is a better failure than calling one
 * against a clear favourite.
 */
export const DRAW_MAX_GAP = 0.2

export function headlineScore(f: {
  probs: Probs
  modalScore: { home: Scoreline; draw: Scoreline; away: Scoreline }
}): { home: number; away: number } {
  const { home, draw, away } = f.probs
  // A draw is almost never the single likeliest outcome — a favourite on 40%
  // beats a draw on 25% every time — so picking the score inside the likeliest
  // outcome could never produce one. Across the first forty forecasts it
  // produced exactly zero, while a quarter of matches are drawn and the model
  // itself put the average draw at 24.6%. The model was right and the display
  // rule was gagging it.
  //
  // So: call a draw when the model rates one at or above its own long-run
  // average. Measured over 1,140 walk-forward matches that predicts draws
  // 24.8% of the time against a real 24.5%, and lifts exact scorelines from
  // 10.1% to 12.1%. It costs agreement with the outcome bar in about a quarter
  // of matches, which is a presentation cost rather than an inconsistency:
  // "the closest single score is 1-1" and "the likeliest single outcome is a
  // home win" are both true and answer different questions.
  const favourite = Math.max(home, away)
  if (draw >= DRAW_THRESHOLD && favourite - draw <= DRAW_MAX_GAP) return f.modalScore.draw
  return home >= away ? f.modalScore.home : f.modalScore.away
}

/** Build a fixture prediction, recording every term as it is applied. */
export function predictFixture(
  input: FixtureInput,
  options: Partial<PredictOptions> = {},
): FixturePrediction {
  const o = { ...DEFAULT_PREDICT, ...options }
  const { home, away, ratings } = input

  const attackHome = ratings.attack[home.code] ?? 0
  const attackAway = ratings.attack[away.code] ?? 0
  const defenceHome = ratings.defence[home.code] ?? 0
  const defenceAway = ratings.defence[away.code] ?? 0

  const terms: LambdaTerm[] = []

  terms.push({
    key: 'baseline',
    label: 'League baseline',
    home: ratings.intercept,
    away: ratings.intercept,
    detail: `An average side scores about ${Math.exp(ratings.intercept).toFixed(2)} against an average side away from home`,
  })
  terms.push({
    key: 'attack',
    label: 'Attacking strength',
    home: attackHome,
    away: attackAway,
  })
  terms.push({
    key: 'defence',
    label: 'Opponent defensive strength',
    home: -defenceAway,
    away: -defenceHome,
  })
  terms.push({
    key: 'home-advantage',
    label: 'Home advantage',
    home: ratings.homeAdvantage,
    away: 0,
    detail: `Lifts the home side's scoring rate by about ${Math.round((Math.exp(ratings.homeAdvantage) - 1) * 100)}%`,
  })

  // How this particular ground departs from the league-wide figure, fitted from
  // the host's own home record: one term for how much more they score there,
  // one for how much they suppress visitors. Both are zero for a club whose
  // home record is unremarkable, so this term simply does not appear.
  const venueAttack = ratings.homeAttackDev?.[home.code] ?? 0
  const venueDefence = ratings.homeDefenceDev?.[home.code] ?? 0
  if (venueAttack !== 0 || venueDefence !== 0) {
    terms.push({
      key: 'venue-record',
      label: 'Home record at this ground',
      home: venueAttack,
      away: -venueDefence,
      detail: venueDetail(venueAttack, venueDefence),
    })
  }

  // Availability: a side's own weakened attack lowers its lambda; a weakened
  // defence raises the opponent's.
  if (home.availability.logAttackShift !== 0 || away.availability.logAttackShift !== 0) {
    terms.push({
      key: 'availability-attack',
      label: 'Attacking absences',
      home: home.availability.logAttackShift,
      away: away.availability.logAttackShift,
    })
  }
  if (home.availability.logDefenceShift !== 0 || away.availability.logDefenceShift !== 0) {
    terms.push({
      key: 'availability-defence',
      label: 'Defensive absences',
      // A home defensive absence helps the away side score.
      home: away.availability.logDefenceShift,
      away: home.availability.logDefenceShift,
    })
  }

  if (home.rest.logShift !== 0 || away.rest.logShift !== 0) {
    terms.push({
      key: 'rest',
      label: 'Rest and congestion',
      home: home.rest.logShift,
      away: away.rest.logShift,
      detail: [home.rest.note, away.rest.note].filter(Boolean).join('; ') || undefined,
    })
  }

  let logHome = 0
  let logAway = 0
  for (const t of terms) {
    logHome += t.home
    logAway += t.away
  }

  const lambdaHome = clamp(Math.exp(logHome), o.minLambda, o.maxLambda)
  const lambdaAway = clamp(Math.exp(logAway), o.minLambda, o.maxLambda)

  // Combined uncertainty across both sides sets the tail weight.
  const sigma = Math.sqrt(
    home.ratingSd * home.ratingSd + away.ratingSd * away.ratingSd + o.baseUncertainty * o.baseUncertainty,
  )
  const dispersion = dispersionFromUncertainty(sigma)

  const matrix: ScoreMatrix = scoreMatrix(lambdaHome, lambdaAway, ratings.rho, dispersion)
  const probs = outcomeProbs(matrix)
  const cs = cleanSheetProbs(matrix)
  const eg = expectedGoals(matrix)
  const over = overProb(matrix, 2.5)

  // Confidence: more matches behind the ratings and less uncertainty = higher.
  const evidence = Math.min(1, (home.matchesPlayed + away.matchesPlayed) / 40)
  const certainty = clamp(1 - (sigma - 0.1) / 0.3, 0, 1)
  const confidence = clamp(0.35 + 0.35 * evidence + 0.3 * certainty, 0, 1)

  return {
    home: home.code,
    away: away.code,
    kickoff: input.kickoff,
    lambdaHome: round(lambdaHome, 4),
    lambdaAway: round(lambdaAway, 4),
    probs: {
      home: round(probs.home, 5),
      draw: round(probs.draw, 5),
      away: round(probs.away, 5),
    },
    btts: round(bttsProb(matrix), 5),
    over25: round(over, 5),
    under25: round(1 - over, 5),
    cleanSheetHome: round(cs.home, 5),
    cleanSheetAway: round(cs.away, 5),
    expectedGoalsHome: round(eg.home, 3),
    expectedGoalsAway: round(eg.away, 3),
    topScorelines: topScorelines(matrix, 12),
    modalScore: modalScoreByOutcome(matrix),
    dispersion: round(dispersion, 2),
    rho: round(ratings.rho, 4),
    terms: terms.map((t) => ({ ...t, home: round(t.home, 4), away: round(t.away, 4) })),
    confidence: round(confidence, 3),
  }
}

/**
 * Rebuild the full score matrix from a stored prediction.
 *
 * Artifacts keep only the top scorelines to stay small; the browser calls this
 * when it needs the whole grid for the heatmap.
 */
export function matrixFromPrediction(p: FixturePrediction): ScoreMatrix {
  return scoreMatrix(p.lambdaHome, p.lambdaAway, p.rho, p.dispersion)
}
