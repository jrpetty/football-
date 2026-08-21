// ---------------------------------------------------------------------------
// Fitting team strength.
//
// Every team gets an attack and a defence rating; the league shares a home
// advantage and a Dixon-Coles rho. Ratings are fitted by maximum likelihood
// under a Poisson model with three deliberate departures from the textbook:
//
//  1. Exponential time decay. A result from 2019 says little about 2026.
//     Backtesting put the useful half-life at roughly 1-2 years; without decay
//     the model is measurably worse (RPS 0.2209 -> 0.2138 on 2025/26).
//  2. Ridge priors pulling ratings toward the league mean. With ~30 matches a
//     season per club, unregularised MLE overfits early-season noise badly and
//     produces absurd ratings for newly promoted sides.
//  3. Support for fractional "goals", so the identical fitter can be run over
//     xG instead of goals. The Poisson score equation only involves (y - mu),
//     which is well defined for continuous y.
//
// Optimisation is Adam with analytic gradients — the likelihood is smooth and
// low-dimensional, so this converges in a few hundred iterations and needs no
// external solver, keeping the core dependency-free and browser-safe.
// ---------------------------------------------------------------------------

import { tau } from './dixonColes.ts'

export interface FitObservation {
  home: string
  away: string
  homeGoals: number
  awayGoals: number
  /** Epoch ms; used for time decay relative to the fit's `asOf`. */
  date: number
}

export interface FitOptions {
  /** Decay rate per day. 0 disables decay. ~0.0015-0.003 works well. */
  xi: number
  /** Reference time for decay, epoch ms. Usually the fixture being predicted. */
  asOf: number
  /** Ridge strength pulling attack/defence toward 0 (the league mean). */
  ridge: number
  /** Fit the Dixon-Coles rho as well. */
  fitRho: boolean
  /**
   * Give every club its own home-advantage deviation instead of sharing one
   * league-wide number. Adds 2n parameters — see `homeRidge`.
   */
  perTeamHome: boolean
  /**
   * Ridge strength on the per-team home deviations. Deliberately much stronger
   * than the attack/defence ridge: a club plays only ~19 home matches a season,
   * so an unshrunk venue effect is mostly noise. This is the dial that decides
   * how far a club is allowed to depart from the league-wide home advantage.
   */
  homeRidge: number
  iterations: number
  learningRate: number
}

export const DEFAULT_FIT: FitOptions = {
  xi: 0.0018,
  asOf: 0,
  ridge: 0.02,
  fitRho: true,
  perTeamHome: true,
  homeRidge: 0.12,
  iterations: 500,
  learningRate: 0.05,
}

export interface TeamRatings {
  /**
   * League baseline log scoring rate — what an average side scores against an
   * average side away from home. Held as its own parameter rather than left to
   * be absorbed by the defence ratings, so the ridge shrinks how far clubs sit
   * from the league mean without also shrinking the league mean itself.
   */
  intercept: number
  /** code -> attack rating (log scale, centred on 0). */
  attack: Record<string, number>
  /** code -> defence rating (log scale, centred on 0, higher = concedes fewer). */
  defence: Record<string, number>
  /** League-wide home advantage, log scale, applied to the home side's lambda. */
  homeAdvantage: number
  /**
   * code -> this club's own deviation from the league home advantage, log
   * scale, applied to its scoring rate when it plays at home. Centred on zero,
   * so the league-wide figure above is still the average.
   */
  homeAttackDev: Record<string, number>
  /**
   * code -> this club's deviation in how much it suppresses visitors, log
   * scale, subtracted from the away side's lambda at this ground. Also centred
   * on zero. A fortress shows up here; a soft home ground shows up negative.
   */
  homeDefenceDev: Record<string, number>
  rho: number
  /** Sum of time-decay weights behind each team's rating — its effective sample size. */
  weight: Record<string, number>
  /** Weighted matches used in the fit. */
  effectiveN: number
}

const DAY_MS = 86400000

/**
 * Fit attack/defence/home-advantage (and optionally rho) by weighted MLE.
 *
 * Ratings are on a log scale: lambda_home = exp(att_home - def_away + home),
 * lambda_away = exp(att_away - def_home). Attack is re-centred to mean zero on
 * every iteration because the parameterisation is otherwise unidentifiable —
 * adding a constant to every attack and every defence leaves lambda unchanged.
 */
export function fitRatings(obs: readonly FitObservation[], opts: Partial<FitOptions> = {}): TeamRatings {
  const o: FitOptions = { ...DEFAULT_FIT, ...opts }
  const asOf = o.asOf || Math.max(...obs.map((m) => m.date), 0)

  const teams = [...new Set(obs.flatMap((m) => [m.home, m.away]))].sort()
  const index = new Map(teams.map((t, i) => [t, i]))
  const n = teams.length
  if (n === 0) {
    return {
      intercept: Math.log(1.35),
      attack: {},
      defence: {},
      homeAdvantage: 0.25,
      homeAttackDev: {},
      homeDefenceDev: {},
      rho: 0,
      weight: {},
      effectiveN: 0,
    }
  }

  const att = new Float64Array(n)
  const def = new Float64Array(n)
  // Per-team departures from the league home advantage: hAtt lifts the club's
  // own scoring at home, hDef suppresses its visitors'. Both centred on zero.
  const hAtt = new Float64Array(n)
  const hDef = new Float64Array(n)
  let mu = Math.log(1.35)
  let home = 0.25
  let rho = 0

  // Precompute per-observation decay weights and team indices.
  const wt = new Float64Array(obs.length)
  const ih = new Int32Array(obs.length)
  const ia = new Int32Array(obs.length)
  const weightByTeam: Record<string, number> = {}
  let effectiveN = 0
  for (let k = 0; k < obs.length; k++) {
    const m = obs[k]!
    const w = o.xi > 0 ? Math.exp((-o.xi * (asOf - m.date)) / DAY_MS) : 1
    wt[k] = w
    ih[k] = index.get(m.home)!
    ia[k] = index.get(m.away)!
    weightByTeam[m.home] = (weightByTeam[m.home] ?? 0) + w
    weightByTeam[m.away] = (weightByTeam[m.away] ?? 0) + w
    effectiveN += w
  }

  // Adam state.
  const mAtt = new Float64Array(n)
  const vAtt = new Float64Array(n)
  const mDef = new Float64Array(n)
  const vDef = new Float64Array(n)
  const mHAtt = new Float64Array(n)
  const vHAtt = new Float64Array(n)
  const mHDef = new Float64Array(n)
  const vHDef = new Float64Array(n)
  let mMu = 0
  let vMu = 0
  let mHome = 0
  let vHome = 0
  let mRho = 0
  let vRho = 0
  const b1 = 0.9
  const b2 = 0.999
  const eps = 1e-8

  for (let it = 1; it <= o.iterations; it++) {
    const gAtt = new Float64Array(n)
    const gDef = new Float64Array(n)
    const gHAtt = new Float64Array(n)
    const gHDef = new Float64Array(n)
    let gMu = 0
    let gHome = 0
    let gRho = 0

    for (let k = 0; k < obs.length; k++) {
      const m = obs[k]!
      const w = wt[k]!
      const a = ih[k]!
      const b = ia[k]!
      const lh = Math.exp(mu + att[a]! - def[b]! + home + hAtt[a]!)
      const la = Math.exp(mu + att[b]! - def[a]! - hDef[a]!)

      // d/dtheta of [y*log(mu) - mu] where mu = exp(theta) gives (y - mu).
      const rh = (m.homeGoals - lh) * w
      const ra = (m.awayGoals - la) * w
      gAtt[a] = gAtt[a]! + rh
      gDef[b] = gDef[b]! - rh
      gHome += rh
      gAtt[b] = gAtt[b]! + ra
      gDef[a] = gDef[a]! - ra
      gMu += rh + ra
      // The venue terms are the same residuals, but only over the matches this
      // club actually hosts — which is exactly what a home record is.
      gHAtt[a] = gHAtt[a]! + rh
      gHDef[a] = gHDef[a]! - ra

      // Numerical gradient for rho only over the four affected scorelines.
      if (o.fitRho && m.homeGoals <= 1 && m.awayGoals <= 1) {
        const h = 1e-4
        const t1 = Math.max(1e-9, tau(m.homeGoals, m.awayGoals, lh, la, rho + h))
        const t0 = Math.max(1e-9, tau(m.homeGoals, m.awayGoals, lh, la, rho - h))
        gRho += (w * (Math.log(t1) - Math.log(t0))) / (2 * h)
      }
    }

    // Ridge: pull ratings toward the league mean.
    if (o.ridge > 0) {
      for (let i = 0; i < n; i++) {
        gAtt[i] = gAtt[i]! - o.ridge * effectiveN * att[i]!
        gDef[i] = gDef[i]! - o.ridge * effectiveN * def[i]!
      }
    }
    if (o.perTeamHome && o.homeRidge > 0) {
      for (let i = 0; i < n; i++) {
        gHAtt[i] = gHAtt[i]! - o.homeRidge * effectiveN * hAtt[i]!
        gHDef[i] = gHDef[i]! - o.homeRidge * effectiveN * hDef[i]!
      }
    }
    // Keep rho in a sane band; it is a small correction, not a free parameter.
    if (o.fitRho) gRho -= o.ridge * effectiveN * rho * 10

    const c1 = 1 - Math.pow(b1, it)
    const c2 = 1 - Math.pow(b2, it)
    const lr = o.learningRate

    for (let i = 0; i < n; i++) {
      mAtt[i] = b1 * mAtt[i]! + (1 - b1) * gAtt[i]!
      vAtt[i] = b2 * vAtt[i]! + (1 - b2) * gAtt[i]! * gAtt[i]!
      att[i] = att[i]! + (lr * (mAtt[i]! / c1)) / (Math.sqrt(vAtt[i]! / c2) + eps)

      mDef[i] = b1 * mDef[i]! + (1 - b1) * gDef[i]!
      vDef[i] = b2 * vDef[i]! + (1 - b2) * gDef[i]! * gDef[i]!
      def[i] = def[i]! + (lr * (mDef[i]! / c1)) / (Math.sqrt(vDef[i]! / c2) + eps)

      if (o.perTeamHome) {
        mHAtt[i] = b1 * mHAtt[i]! + (1 - b1) * gHAtt[i]!
        vHAtt[i] = b2 * vHAtt[i]! + (1 - b2) * gHAtt[i]! * gHAtt[i]!
        hAtt[i] = hAtt[i]! + (lr * (mHAtt[i]! / c1)) / (Math.sqrt(vHAtt[i]! / c2) + eps)

        mHDef[i] = b1 * mHDef[i]! + (1 - b1) * gHDef[i]!
        vHDef[i] = b2 * vHDef[i]! + (1 - b2) * gHDef[i]! * gHDef[i]!
        hDef[i] = hDef[i]! + (lr * (mHDef[i]! / c1)) / (Math.sqrt(vHDef[i]! / c2) + eps)
      }
    }

    // The intercept is deliberately unpenalised: how many goals the league
    // scores is an observed fact, not something to shrink toward zero.
    mMu = b1 * mMu + (1 - b1) * gMu
    vMu = b2 * vMu + (1 - b2) * gMu * gMu
    mu += (lr * (mMu / c1)) / (Math.sqrt(vMu / c2) + eps)

    mHome = b1 * mHome + (1 - b1) * gHome
    vHome = b2 * vHome + (1 - b2) * gHome * gHome
    home += (lr * (mHome / c1)) / (Math.sqrt(vHome / c2) + eps)

    if (o.fitRho) {
      mRho = b1 * mRho + (1 - b1) * gRho
      vRho = b2 * vRho + (1 - b2) * gRho * gRho
      rho += (lr * 0.2 * (mRho / c1)) / (Math.sqrt(vRho / c2) + eps)
      rho = Math.max(-0.25, Math.min(0.25, rho))
    }

    // Identifiability. Adding a constant to every attack rating is the same
    // model with a different intercept, so the parameterisation only pins down
    // once each group of ratings is centred on zero. Each step below moves the
    // discarded mean into the parameter that can absorb it exactly, leaving
    // both lambdas bit-for-bit unchanged — these are re-gauges, not nudges,
    // and doing them exactly is what lets the intercept converge quickly
    // instead of being dragged along by twenty defence ratings at once.
    let meanAtt = 0
    for (let i = 0; i < n; i++) meanAtt += att[i]!
    meanAtt /= n
    for (let i = 0; i < n; i++) att[i] = att[i]! - meanAtt
    mu += meanAtt

    if (o.perTeamHome) {
      let mHA = 0
      let mHD = 0
      for (let i = 0; i < n; i++) {
        mHA += hAtt[i]!
        mHD += hDef[i]!
      }
      mHA /= n
      mHD /= n
      for (let i = 0; i < n; i++) {
        hAtt[i] = hAtt[i]! - mHA
        hDef[i] = hDef[i]! - mHD
      }
      // Removing the mean home-scoring lift is absorbed by the shared home
      // term. Removing the mean visitor suppression raises every away lambda,
      // which the intercept takes back — and that in turn lowers every home
      // lambda, which the home term restores.
      home += mHA + mHD
      mu -= mHD
    }

    let meanDef = 0
    for (let i = 0; i < n; i++) meanDef += def[i]!
    meanDef /= n
    for (let i = 0; i < n; i++) def[i] = def[i]! - meanDef
    mu -= meanDef
  }

  const attack: Record<string, number> = {}
  const defence: Record<string, number> = {}
  const homeAttackDev: Record<string, number> = {}
  const homeDefenceDev: Record<string, number> = {}
  for (let i = 0; i < n; i++) {
    attack[teams[i]!] = att[i]!
    defence[teams[i]!] = def[i]!
    homeAttackDev[teams[i]!] = hAtt[i]!
    homeDefenceDev[teams[i]!] = hDef[i]!
  }
  return {
    intercept: mu,
    attack,
    defence,
    homeAdvantage: home,
    homeAttackDev,
    homeDefenceDev,
    rho,
    weight: weightByTeam,
    effectiveN,
  }
}

/**
 * Blend two rating sets in log space.
 *
 * Used to combine goals-fitted and xG-fitted ratings. xG is the better signal
 * (it measures chance quality rather than whether it went in), but only exists
 * from 2022-23, so goals supply the long history. Backtesting on 2025/26 put
 * the optimum near w=0.7 toward xG: RPS 0.2095 -> 0.2080 and log-loss
 * 1.0418 -> 1.0272 versus goals alone.
 */
function mix(a: number | undefined, b: number | undefined, w: number): number {
  if (b === undefined) return a ?? 0
  if (a === undefined) return b
  return (1 - w) * a + w * b
}

export function blendRatings(goals: TeamRatings, xg: TeamRatings, w: number): TeamRatings {
  const codes = [...new Set([...Object.keys(goals.attack), ...Object.keys(xg.attack)])]
  const attack: Record<string, number> = {}
  const defence: Record<string, number> = {}
  const homeAttackDev: Record<string, number> = {}
  const homeDefenceDev: Record<string, number> = {}
  for (const c of codes) {
    // A team missing from the xG fit (pre-2022 only) keeps its goals rating.
    const ga = goals.attack[c]
    const xa = xg.attack[c]
    const gd = goals.defence[c]
    const xd = xg.defence[c]
    attack[c] = xa === undefined ? (ga ?? 0) : ga === undefined ? xa : (1 - w) * ga + w * xa
    defence[c] = xd === undefined ? (gd ?? 0) : gd === undefined ? xd : (1 - w) * gd + w * xd
    homeAttackDev[c] = mix(goals.homeAttackDev[c], xg.homeAttackDev[c], w)
    homeDefenceDev[c] = mix(goals.homeDefenceDev[c], xg.homeDefenceDev[c], w)
  }
  return {
    // The intercept stays on the goals scale. xG and goals agree closely in
    // aggregate, but the model predicts goals, so the level it is anchored to
    // has to be the one goals were actually scored at.
    intercept: goals.intercept,
    attack,
    defence,
    homeAdvantage: (1 - w) * goals.homeAdvantage + w * xg.homeAdvantage,
    homeAttackDev,
    homeDefenceDev,
    // rho describes the goals process specifically; xG has no scorelines.
    rho: goals.rho,
    weight: goals.weight,
    effectiveN: goals.effectiveN,
  }
}
