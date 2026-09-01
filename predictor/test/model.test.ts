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
    intercept: 0.3,
    attack: { A: 0.2, B: -0.2, OLD: 0.5 },
    defence: { A: 0.1, B: -0.1, OLD: 0.3 },
    homeAdvantage: 0.25,
    homeAttackDev: { A: 0.1, B: -0.1, OLD: 0.2 },
    homeDefenceDev: { A: 0.05, B: -0.05, OLD: 0.1 },
    rho: -0.05, weight: {}, effectiveN: 10,
  }
  const xg = {
    intercept: 0.9,
    attack: { A: 0.4, B: -0.4 },
    defence: { A: 0.3, B: -0.3 },
    homeAdvantage: 0.21,
    homeAttackDev: { A: 0.3, B: -0.3 },
    homeDefenceDev: { A: 0.15, B: -0.15 },
    rho: 0, weight: {}, effectiveN: 10,
  }
  const b = blendRatings(goals, xg, 0.5)
  close(b.attack['A']!, 0.3)
  // A club absent from the xG era keeps its goals-fitted rating rather than
  // being dragged toward zero.
  close(b.attack['OLD']!, 0.5)
  close(b.homeAdvantage, 0.23)
  // The intercept is the goals-scale level and does not blend.
  close(b.intercept, 0.3)
  // Venue deviations blend on the same terms, and a club with no xG-era home
  // record keeps the one its goals record produced.
  close(b.homeAttackDev['A']!, 0.2)
  close(b.homeDefenceDev['A']!, 0.1)
  close(b.homeAttackDev['OLD']!, 0.2)
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

// ---------------------------------------------------------------------------
// Venue effects and the league baseline.
//
// The two tests below guard bugs that were live in this repo. The first is the
// one that caught them: the fitted goal rates must reproduce the goal rates
// actually observed. When the league scoring level was carried by the defence
// ratings, the ridge shrank it, and the fit settled on away lambdas 7% below
// what teams really scored — with the home-advantage term quietly absorbing
// the error and reading 0.246 when the data said 0.175.
// ---------------------------------------------------------------------------

/** A round-robin season generated from known parameters, with fixed scorelines. */
function syntheticSeason(opts: {
  teams: string[]
  intercept: number
  home: number
  /** Per-team extra home scoring, log scale. */
  venueAttack?: Record<string, number>
  /** Per-team extra visitor suppression, log scale. */
  venueDefence?: Record<string, number>
  rounds?: number
}) {
  const day = 86400000
  const base = 1_700_000_000_000
  const obs: { home: string; away: string; homeGoals: number; awayGoals: number; date: number }[] = []
  let k = 0
  for (let r = 0; r < (opts.rounds ?? 30); r++) {
    for (const h of opts.teams) {
      for (const a of opts.teams) {
        if (h === a) continue
        // Expected goals under the generating parameters, realised as the two
        // integers straddling them in the ratio that reproduces the mean. That
        // keeps the test deterministic while giving the fitter the right
        // first moment to recover.
        const lh = Math.exp(opts.intercept + opts.home + (opts.venueAttack?.[h] ?? 0))
        const la = Math.exp(opts.intercept - (opts.venueDefence?.[h] ?? 0))
        const split = (lam: number, i: number) => {
          const lo = Math.floor(lam)
          return (i % 100) / 100 < lam - lo ? lo + 1 : lo
        }
        obs.push({ home: h, away: a, homeGoals: split(lh, k * 37), awayGoals: split(la, k * 53), date: base + k * day })
        k++
      }
    }
  }
  return obs
}

test('the fit reproduces the goal rates actually observed', () => {
  const obs = syntheticSeason({ teams: ['A', 'B', 'C', 'D', 'E'], intercept: Math.log(1.3), home: 0.22 })
  const r = fitRatings(obs, { xi: 0, ridge: 0.02, fitRho: false, iterations: 800 })

  let lh = 0
  let la = 0
  for (const m of obs) {
    lh += Math.exp(r.intercept + r.attack[m.home]! - r.defence[m.away]! + r.homeAdvantage + (r.homeAttackDev[m.home] ?? 0))
    la += Math.exp(r.intercept + r.attack[m.away]! - r.defence[m.home]! - (r.homeDefenceDev[m.home] ?? 0))
  }
  const actualHome = obs.reduce((s, m) => s + m.homeGoals, 0)
  const actualAway = obs.reduce((s, m) => s + m.awayGoals, 0)

  // Within half a percent on both sides. The ridge shrinks how far clubs sit
  // from the league mean, so it must not move the league mean itself.
  close(lh / obs.length, actualHome / obs.length, 0.006)
  close(la / obs.length, actualAway / obs.length, 0.006)

  // And the reported home advantage is the log ratio of the two, not a
  // parameter that has quietly absorbed the fit's leftovers.
  close(r.homeAdvantage, Math.log(actualHome / actualAway), 0.01)
})

test('the fit recovers a per-team home advantage it was given', () => {
  // B is a fortress: scores more at home and lets visitors score less. D is the
  // opposite. A, C and E are unremarkable.
  const venueAttack = { A: 0, B: 0.25, C: 0, D: -0.2, E: 0 }
  const venueDefence = { A: 0, B: 0.2, C: 0, D: -0.15, E: 0 }
  const obs = syntheticSeason({
    teams: ['A', 'B', 'C', 'D', 'E'],
    intercept: Math.log(1.3),
    home: 0.2,
    venueAttack,
    venueDefence,
  })
  const r = fitRatings(obs, { xi: 0, ridge: 0.02, homeRidge: 0.002, fitRho: false, iterations: 1200 })

  const edge = (t: string) => (r.homeAttackDev[t] ?? 0) + (r.homeDefenceDev[t] ?? 0)
  assert.ok(edge('B') > edge('A'), 'the fortress should out-rate an average ground')
  assert.ok(edge('A') > edge('D'), 'the soft ground should under-rate an average one')
  assert.ok(edge('B') > 0.25, `B's venue edge should be substantial, got ${edge('B').toFixed(3)}`)
  assert.ok(edge('D') < -0.2, `D's venue edge should be clearly negative, got ${edge('D').toFixed(3)}`)

  // Both directions are recovered separately: B's is not all attributed to
  // scoring, which would make the model wrong about how visitors fare there.
  assert.ok(r.homeAttackDev['B']! > 0.1, 'B scores more at home')
  assert.ok(r.homeDefenceDev['B']! > 0.1, 'B concedes less at home')

  // Deviations are centred, so the shared figure stays the league average.
  const codes = ['A', 'B', 'C', 'D', 'E']
  close(codes.reduce((s, t) => s + r.homeAttackDev[t]!, 0) / codes.length, 0, 1e-6)
  close(codes.reduce((s, t) => s + r.homeDefenceDev[t]!, 0) / codes.length, 0, 1e-6)
})

test('turning per-team home advantage off leaves every club on the shared figure', () => {
  const obs = syntheticSeason({
    teams: ['A', 'B', 'C', 'D'],
    intercept: Math.log(1.3),
    home: 0.2,
    venueAttack: { A: 0.3, B: -0.3, C: 0, D: 0 },
  })
  const r = fitRatings(obs, { xi: 0, ridge: 0.02, perTeamHome: false, fitRho: false, iterations: 400 })
  for (const t of ['A', 'B', 'C', 'D']) {
    close(r.homeAttackDev[t]!, 0, 1e-12)
    close(r.homeDefenceDev[t]!, 0, 1e-12)
  }
})

// --- The headline score, and its right to say "draw" -------------------------

import { headlineScore, DRAW_THRESHOLD } from '../src/core/predict.ts'

const modal = {
  home: { home: 2, away: 1, prob: 0.09 },
  draw: { home: 1, away: 1, prob: 0.11 },
  away: { home: 1, away: 2, prob: 0.08 },
}

test('a draw can be the headline score when the model rates one highly', () => {
  // The exact case the old rule could never express: a favourite on 45%, but a
  // draw above its long-run average. Picking inside the likeliest outcome gave
  // 2-1 here and could not produce a draw in forty consecutive forecasts.
  const s = headlineScore({ probs: { home: 0.45, draw: 0.28, away: 0.27 }, modalScore: modal })
  assert.deepEqual({ home: s.home, away: s.away }, { home: 1, away: 1 })
})

test('a clear favourite still gets a winning scoreline', () => {
  const s = headlineScore({ probs: { home: 0.74, draw: 0.18, away: 0.08 }, modalScore: modal })
  assert.equal(s.home > s.away, true, 'a 74% favourite must not be given a draw')

  const away = headlineScore({ probs: { home: 0.2, draw: 0.24, away: 0.56 }, modalScore: modal })
  assert.equal(away.away > away.home, true)
})

test('the draw threshold is the boundary, and it is inclusive', () => {
  const just = headlineScore({ probs: { home: 0.45, draw: DRAW_THRESHOLD, away: 0.3 }, modalScore: modal })
  assert.deepEqual({ home: just.home, away: just.away }, { home: 1, away: 1 })

  const under = headlineScore({ probs: { home: 0.47, draw: DRAW_THRESHOLD - 0.01, away: 0.29 }, modalScore: modal })
  assert.equal(under.home > under.away, true, 'below the threshold the favourite wins the headline')
})

test('a draw is not called against a clear favourite', () => {
  // Hull 26 / draw 26 / Villa 48. The draw clears its own average, but Villa
  // are 22 points clear of it — calling 1-1 here reads as broken.
  const s = headlineScore({ probs: { home: 0.26, draw: 0.26, away: 0.48 }, modalScore: modal })
  assert.equal(s.away > s.home, true, 'a 48% favourite must not be given a draw')

  // Everton 36 / draw 27 / United 37 — genuinely tight, so a draw is right.
  const tight = headlineScore({ probs: { home: 0.36, draw: 0.27, away: 0.37 }, modalScore: modal })
  assert.deepEqual({ home: tight.home, away: tight.away }, { home: 1, away: 1 })
})
