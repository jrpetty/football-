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
  iterations: number
  learningRate: number
}

export const DEFAULT_FIT: FitOptions = {
  xi: 0.0018,
  asOf: 0,
  ridge: 0.02,
  fitRho: true,
  iterations: 500,
  learningRate: 0.05,
}

export interface TeamRatings {
  /** code -> attack rating (log scale, centred on 0). */
  attack: Record<string, number>
  /** code -> defence rating (log scale, higher = concedes fewer). */
  defence: Record<string, number>
  homeAdvantage: number
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
    return { attack: {}, defence: {}, homeAdvantage: 0.25, rho: 0, weight: {}, effectiveN: 0 }
  }

  const att = new Float64Array(n)
  const def = new Float64Array(n)
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
    let gHome = 0
    let gRho = 0

    for (let k = 0; k < obs.length; k++) {
      const m = obs[k]!
      const w = wt[k]!
      const a = ih[k]!
      const b = ia[k]!
      const lh = Math.exp(att[a]! - def[b]! + home)
      const la = Math.exp(att[b]! - def[a]!)

      // d/dtheta of [y*log(mu) - mu] where mu = exp(theta) gives (y - mu).
      const rh = (m.homeGoals - lh) * w
      const ra = (m.awayGoals - la) * w
      gAtt[a] = gAtt[a]! + rh
      gDef[b] = gDef[b]! - rh
      gHome += rh
      gAtt[b] = gAtt[b]! + ra
      gDef[a] = gDef[a]! - ra

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
    }

    mHome = b1 * mHome + (1 - b1) * gHome
    vHome = b2 * vHome + (1 - b2) * gHome * gHome
    home += (lr * (mHome / c1)) / (Math.sqrt(vHome / c2) + eps)

    if (o.fitRho) {
      mRho = b1 * mRho + (1 - b1) * gRho
      vRho = b2 * vRho + (1 - b2) * gRho * gRho
      rho += (lr * 0.2 * (mRho / c1)) / (Math.sqrt(vRho / c2) + eps)
      rho = Math.max(-0.25, Math.min(0.25, rho))
    }

    // Identifiability: centre attack ratings on zero.
    let meanAtt = 0
    for (let i = 0; i < n; i++) meanAtt += att[i]!
    meanAtt /= n
    for (let i = 0; i < n; i++) att[i] = att[i]! - meanAtt
  }

  const attack: Record<string, number> = {}
  const defence: Record<string, number> = {}
  for (let i = 0; i < n; i++) {
    attack[teams[i]!] = att[i]!
    defence[teams[i]!] = def[i]!
  }
  return { attack, defence, homeAdvantage: home, rho, weight: weightByTeam, effectiveN }
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
export function blendRatings(goals: TeamRatings, xg: TeamRatings, w: number): TeamRatings {
  const codes = [...new Set([...Object.keys(goals.attack), ...Object.keys(xg.attack)])]
  const attack: Record<string, number> = {}
  const defence: Record<string, number> = {}
  for (const c of codes) {
    // A team missing from the xG fit (pre-2022 only) keeps its goals rating.
    const ga = goals.attack[c]
    const xa = xg.attack[c]
    const gd = goals.defence[c]
    const xd = xg.defence[c]
    attack[c] = xa === undefined ? (ga ?? 0) : ga === undefined ? xa : (1 - w) * ga + w * xa
    defence[c] = xd === undefined ? (gd ?? 0) : gd === undefined ? xd : (1 - w) * gd + w * xd
  }
  return {
    attack,
    defence,
    homeAdvantage: (1 - w) * goals.homeAdvantage + w * xg.homeAdvantage,
    // rho describes the goals process specifically; xG has no scorelines.
    rho: goals.rho,
    weight: goals.weight,
    effectiveN: goals.effectiveN,
  }
}
