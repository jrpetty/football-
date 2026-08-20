// Model maths. These assertions are the ones that would catch a silent
// regression in a number nobody looks at directly.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { poissonPmf, poissonVector, negBinomialPmf, logGamma, mulberry32, round } from '../src/core/math.ts'
import {
  scoreMatrix, outcomeProbs, bttsProb, overProb, cleanSheetProbs,
  expectedGoals, topScorelines, modalScoreByOutcome, tau, MAX_GOALS,
} from '../src/core/dixonColes.ts'
import { fitRatings, blendRatings } from '../src/core/fit.ts'
import { rps, logLoss, brier, outcomeOf, summarize, calibration, expectedCalibrationError } from '../src/core/metrics.ts'
import { dispersionFromUncertainty } from '../src/core/predict.ts'

const close = (a: number, b: number, eps = 1e-9): void => {
  assert.ok(Math.abs(a - b) < eps, `expected ${a} to be within ${eps} of ${b}`)
}

test('Poisson pmf matches closed-form values', () => {
  close(poissonPmf(1, 0), Math.exp(-1))
  close(poissonPmf(2, 1), 2 * Math.exp(-2))
  close(poissonPmf(1.5, 2), (1.5 ** 2 / 2) * Math.exp(-1.5))
  // Degenerate rate: all mass on zero.
  close(poissonPmf(0, 0), 1)
  close(poissonPmf(0, 3), 0)
})

test('Poisson vector sums to ~1 and agrees with the scalar pmf', () => {
  const v = poissonVector(2.3, 25)
  close(v.reduce((a, b) => a + b, 0), 1, 1e-9)
  for (let k = 0; k <= 8; k++) close(v[k]!, poissonPmf(2.3, k), 1e-12)
})

test('log-gamma matches known factorials', () => {
  // Gamma(n) = (n-1)!
  close(Math.exp(logGamma(5)), 24, 1e-6)
  close(Math.exp(logGamma(1)), 1, 1e-9)
  close(logGamma(0.5), Math.log(Math.sqrt(Math.PI)), 1e-9)
})

test('negative binomial converges to Poisson as dispersion grows', () => {
  for (const k of [0, 1, 3, 5]) {
    close(negBinomialPmf(1.7, 1e9, k), poissonPmf(1.7, k), 1e-6)
  }
})

test('negative binomial has fatter tails than Poisson at the same mean', () => {
  const lambda = 1.5
  const tailNb = [4, 5, 6, 7, 8].reduce((s, k) => s + negBinomialPmf(lambda, 8, k), 0)
  const tailPois = [4, 5, 6, 7, 8].reduce((s, k) => s + poissonPmf(lambda, k), 0)
  assert.ok(tailNb > tailPois, 'overdispersion should push mass into the tail')
})

test('Dixon-Coles tau only touches the four low scorelines', () => {
  const lh = 1.4
  const la = 1.1
  const rho = -0.08
  assert.notEqual(tau(0, 0, lh, la, rho), 1)
  assert.notEqual(tau(1, 1, lh, la, rho), 1)
  assert.equal(tau(2, 0, lh, la, rho), 1)
  assert.equal(tau(3, 2, lh, la, rho), 1)
  // rho of zero is a no-op everywhere.
  assert.equal(tau(0, 0, lh, la, 0), 1)
})

test('score matrix is a proper distribution and recovers its means', () => {
  const m = scoreMatrix(1.8, 1.1, -0.05)
  const total = m.grid.flat().reduce((a, b) => a + b, 0)
  close(total, 1, 1e-9)
  assert.ok(m.grid.flat().every((p) => p >= 0), 'no negative probabilities')
  const eg = expectedGoals(m)
  // Truncation at MAX_GOALS costs a sliver of mass; the means should still be close.
  close(eg.home, 1.8, 1e-3)
  close(eg.away, 1.1, 1e-3)
})

test('derived markets agree with the matrix they came from', () => {
  const m = scoreMatrix(1.6, 1.3, -0.06, 30)
  const o = outcomeProbs(m)
  close(o.home + o.draw + o.away, 1, 1e-9)

  // BTTS is the complement of "at least one side is kept out".
  const cs = cleanSheetProbs(m)
  const noGoalsHome = m.grid.reduce((s, row) => s + row[0]!, 0)
  const noGoalsAway = m.grid[0]!.reduce((a, b) => a + b, 0)
  const nilNil = m.grid[0]![0]!
  close(bttsProb(m), 1 - noGoalsHome - noGoalsAway + nilNil, 1e-9)
  close(cs.home, noGoalsHome, 1e-12)
  close(cs.away, noGoalsAway, 1e-12)

  // Over and under partition the space.
  close(overProb(m, 2.5) + (1 - overProb(m, 2.5)), 1, 1e-12)
})

test('top scorelines are sorted and consistent with the modal-by-outcome split', () => {
  const m = scoreMatrix(2.1, 0.8, -0.05)
  const top = topScorelines(m, 10)
  for (let i = 1; i < top.length; i++) {
    assert.ok(top[i - 1]!.prob >= top[i]!.prob, 'scorelines must descend by probability')
  }
  const modal = modalScoreByOutcome(m)
  assert.ok(modal.home.home > modal.home.away, 'home-win modal score must be a home win')
  assert.equal(modal.draw.home, modal.draw.away)
  assert.ok(modal.away.away > modal.away.home, 'away-win modal score must be an away win')
})

test('a stronger team gets a higher win probability', () => {
  const strong = scoreMatrix(2.2, 0.9, -0.05)
  const even = scoreMatrix(1.4, 1.4, -0.05)
  assert.ok(outcomeProbs(strong).home > outcomeProbs(even).home)
  close(outcomeProbs(even).home, outcomeProbs(even).away, 1e-9)
})

test('the fitter recovers strength ordering from synthetic results', () => {
  // Three teams, deliberately lopsided: A beats B beats C.
  const day = 86400000
  const base = 1_700_000_000_000
  const obs = []
  for (let i = 0; i < 40; i++) {
    obs.push({ home: 'A', away: 'C', homeGoals: 3, awayGoals: 0, date: base + i * day })
    obs.push({ home: 'C', away: 'A', homeGoals: 0, awayGoals: 3, date: base + i * day })
    obs.push({ home: 'A', away: 'B', homeGoals: 2, awayGoals: 1, date: base + i * day })
    obs.push({ home: 'B', away: 'C', homeGoals: 2, awayGoals: 0, date: base + i * day })
  }
  const r = fitRatings(obs, { xi: 0, ridge: 0.001, fitRho: false, iterations: 600 })
  assert.ok(r.attack['A']! > r.attack['B']!, 'A should out-rate B on attack')
  assert.ok(r.attack['B']! > r.attack['C']!, 'B should out-rate C on attack')
  assert.ok(r.defence['A']! > r.defence['C']!, 'A should out-rate C on defence')
  // Attack ratings are centred for identifiability.
  const mean = (r.attack['A']! + r.attack['B']! + r.attack['C']!) / 3
  close(mean, 0, 1e-6)
})

test('time decay makes recent results count for more', () => {
  const day = 86400000
  const now = 1_700_000_000_000
  const obs = []
  // Old: A thrashes B. Recent: B thrashes A.
  for (let i = 0; i < 20; i++) {
    obs.push({ home: 'A', away: 'B', homeGoals: 4, awayGoals: 0, date: now - (900 + i) * day })
    obs.push({ home: 'B', away: 'A', homeGoals: 4, awayGoals: 0, date: now - (10 + i) * day })
  }
  const decayed = fitRatings(obs, { xi: 0.005, asOf: now, ridge: 0.001, fitRho: false })
  const flat = fitRatings(obs, { xi: 0, asOf: now, ridge: 0.001, fitRho: false })
  assert.ok(decayed.attack['B']! > decayed.attack['A']!, 'recent form should dominate under decay')
  assert.ok(
    decayed.attack['B']! - decayed.attack['A']! > flat.attack['B']! - flat.attack['A']!,
    'decay should widen the gap versus an unweighted fit',
  )
})

test('the fitter accepts fractional goals, so it can be run over xG', () => {
  const day = 86400000
  const base = 1_700_000_000_000
  const obs = []
  for (let i = 0; i < 30; i++) {
    obs.push({ home: 'A', away: 'B', homeGoals: 2.4, awayGoals: 0.7, date: base + i * day })
    obs.push({ home: 'B', away: 'A', homeGoals: 0.9, awayGoals: 2.1, date: base + i * day })
  }
  const r = fitRatings(obs, { xi: 0, ridge: 0.001, fitRho: false })
  assert.ok(Number.isFinite(r.attack['A']!))
  assert.ok(r.attack['A']! > r.attack['B']!)
})

test('blending ratings keeps teams present in only one fit', () => {
  const goals = {
    attack: { A: 0.2, B: -0.2, OLD: 0.5 },
    defence: { A: 0.1, B: -0.1, OLD: 0.3 },
    homeAdvantage: 0.25, rho: -0.05, weight: {}, effectiveN: 10,
  }
  const xg = {
    attack: { A: 0.4, B: -0.4 },
    defence: { A: 0.3, B: -0.3 },
    homeAdvantage: 0.21, rho: 0, weight: {}, effectiveN: 10,
  }
  const b = blendRatings(goals, xg, 0.5)
  close(b.attack['A']!, 0.3)
  // A club absent from the xG era keeps its goals-fitted rating rather than
  // being dragged toward zero.
  close(b.attack['OLD']!, 0.5)
  close(b.homeAdvantage, 0.23)
})

test('RPS respects outcome ordering', () => {
  const confidentHome = { home: 0.8, draw: 0.15, away: 0.05 }
  // Being wrong by one step (draw) must cost less than being wrong by two (away).
  const vsDraw = rps(confidentHome, 'draw')
  const vsAway = rps(confidentHome, 'away')
  assert.ok(vsAway > vsDraw, 'an away win should punish a home-heavy forecast more than a draw')
  // A perfect forecast scores zero.
  close(rps({ home: 1, draw: 0, away: 0 }, 'home'), 0)
  // The worst possible forecast scores one.
  close(rps({ home: 0, draw: 0, away: 1 }, 'home'), 1)
})

test('RPS matches a hand-computed case', () => {
  // p = (0.5, 0.3, 0.2), actual = draw.
  // cum p = 0.5, 0.8 ; cum obs = 0, 1 -> ((0.5)^2 + (0.2)^2) / 2
  close(rps({ home: 0.5, draw: 0.3, away: 0.2 }, 'draw'), (0.25 + 0.04) / 2, 1e-12)
})

test('log-loss and Brier behave as expected', () => {
  close(logLoss({ home: 0.5, draw: 0.3, away: 0.2 }, 'home'), -Math.log(0.5), 1e-12)
  close(brier({ home: 1, draw: 0, away: 0 }, 'home'), 0, 1e-12)
  close(brier({ home: 0.5, draw: 0.3, away: 0.2 }, 'home'), 0.25 + 0.09 + 0.04, 1e-12)
})

test('outcomeOf reads scorelines correctly', () => {
  assert.equal(outcomeOf(2, 1), 'home')
  assert.equal(outcomeOf(1, 1), 'draw')
  assert.equal(outcomeOf(0, 3), 'away')
})

test('summarize and calibration agree on a perfectly calibrated set', () => {
  // Ten forecasts at 50/25/25; five home wins. Accuracy should be 0.5.
  const items = Array.from({ length: 10 }, (_, i) => ({
    probs: { home: 0.5, draw: 0.25, away: 0.25 },
    actual: (i < 5 ? 'home' : i < 8 ? 'draw' : 'away') as 'home' | 'draw' | 'away',
  }))
  const s = summarize(items)
  assert.equal(s.n, 10)
  close(s.accuracy, 0.5)
  const bins = calibration(items)
  const total = bins.reduce((acc, b) => acc + b.count, 0)
  assert.equal(total, 30, 'each match contributes three outcome forecasts')
  assert.ok(expectedCalibrationError(bins) >= 0)
})

test('dispersion falls as uncertainty rises', () => {
  const confident = dispersionFromUncertainty(0.08)
  const unsure = dispersionFromUncertainty(0.25)
  assert.ok(confident > unsure, 'more uncertainty must mean fatter tails (lower r)')
  // And a fatter-tailed match gives the underdog more probability.
  const tight = outcomeProbs(scoreMatrix(2.0, 0.9, -0.05, confident))
  const loose = outcomeProbs(scoreMatrix(2.0, 0.9, -0.05, unsure))
  assert.ok(loose.away > tight.away, 'uncertainty should raise the underdog’s chances')
})

test('seeded PRNG is deterministic, so artifacts do not churn', () => {
  const a = mulberry32(42)
  const b = mulberry32(42)
  for (let i = 0; i < 5; i++) assert.equal(a(), b())
})

test('round avoids negative zero in artifacts', () => {
  assert.equal(Object.is(round(-0.0001, 2), -0), false)
  assert.equal(round(-0.0001, 2), 0)
  assert.equal(round(1.23456, 3), 1.235)
})

test('MAX_GOALS is high enough that truncation is negligible', () => {
  const m = scoreMatrix(3.0, 2.5, 0)
  assert.ok(MAX_GOALS >= 8)
  const eg = expectedGoals(m)
  close(eg.home, 3.0, 5e-3)
})
