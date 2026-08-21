// ---------------------------------------------------------------------------
// Priors for newly promoted clubs.
//
// A promoted side has no Premier League record, so the fitter has nothing to
// work with and would otherwise treat them as league-average. Measured across
// 33 promoted club-seasons in this corpus (2015-16 to 2025-26):
//
//   attack   0.713 x league average  (sd 0.163)  -> log prior -0.338
//   conceded 1.234 x league average  (sd 0.236)  -> log prior +0.210
//   points   0.919 per game          (sd 0.322)
//
// The spread matters more than the mean. Of those 33 seasons, 19 finished
// under a point per game but 5 cleared 1.30 — Leeds took 1.55 in 2020-21 while
// Southampton managed 0.32 in 2024-25. A promoted club is not reliably bad; it
// is reliably *uncertain*. So the prior carries an inflated variance, which
// widens their scoreline distribution and gives them honest upset probability
// rather than writing them off.
// ---------------------------------------------------------------------------

import { clamp } from './math.ts'

export interface PromotedPrior {
  /** Log-scale attack rating relative to the league mean. */
  attack: number
  /** Log-scale defence rating (negative = concedes more). */
  defence: number
  /** Standard deviation on those ratings, used to fatten score tails. */
  sd: number
}

/** Measured from this corpus; recomputed by the ratings build each week. */
export const MEASURED_PROMOTED_PRIOR: PromotedPrior = {
  attack: -0.338,
  defence: -0.21,
  sd: 0.2,
}

export interface ChampionshipRecord {
  team: string
  played: number
  goalsFor: number
  goalsAgainst: number
  points: number
}

/**
 * Adjust the generic promoted prior using how the club did in the Championship.
 *
 * The coefficients below are measured, not chosen, and the measurement is
 * humbling. Across 27 promoted seasons where both records are available:
 *
 *   Championship GD/game  -> PL attack (log)   r = 0.28,  slope  0.25
 *   Championship GD/game  -> PL conceded (log) r = 0.05,  slope  0.03
 *   Championship points/g -> PL attack (log)   r = -0.21
 *   Championship GD/game  -> PL points/game    r = 0.09
 *
 * Only the first is worth anything, and even that is weak. Championship points
 * per game actually correlates *negatively* with Premier League attacking
 * output, so an earlier version of this function that rewarded it was pushing
 * predictions the wrong way. Burnley won their division by +1.16 goals a game
 * and then took 0.63 points a game in the top flight; Sunderland came up at
 * +0.31 and took 1.42.
 *
 * So: goal difference feeds the attack prior at roughly half the fitted slope
 * (shrunk because r is low and n is small), nothing feeds the defence prior,
 * and points per game is ignored. Most of a promoted club's prior stays the
 * cohort average, which is the honest position given the evidence.
 */
export function promotedPrior(
  record: ChampionshipRecord | null,
  base: PromotedPrior = MEASURED_PROMOTED_PRIOR,
): PromotedPrior {
  if (!record || record.played < 20) return base

  const gdPerGame = record.goalsFor / record.played - record.goalsAgainst / record.played
  // Centre on a typical promoted side: about +0.6 goal difference per game.
  const gdDelta = clamp(gdPerGame - 0.6, -0.8, 0.8)

  // Fitted slope is 0.25; halved to shrink toward the cohort mean.
  const attackAdj = 0.12 * gdDelta

  return {
    attack: base.attack + attackAdj,
    // Championship defensive record tells us nothing about Premier League
    // conceding (r = 0.05), so it is deliberately not used.
    defence: base.defence,
    // Championship evidence does not meaningfully narrow the uncertainty
    // either; the spread of promoted outcomes stays wide regardless.
    sd: base.sd,
  }
}

/**
 * Build Championship records from match rows, for the season before promotion.
 */
export function championshipTable(
  matches: readonly {
    season: string
    home: string
    away: string
    homeGoals: number | null
    awayGoals: number | null
    finished: boolean
  }[],
  season: string,
): Map<string, ChampionshipRecord> {
  const table = new Map<string, ChampionshipRecord>()
  const ensure = (team: string): ChampionshipRecord => {
    let r = table.get(team)
    if (!r) {
      r = { team, played: 0, goalsFor: 0, goalsAgainst: 0, points: 0 }
      table.set(team, r)
    }
    return r
  }
  for (const m of matches) {
    if (m.season !== season || !m.finished || m.homeGoals === null || m.awayGoals === null) continue
    const h = ensure(m.home)
    const a = ensure(m.away)
    h.played++
    a.played++
    h.goalsFor += m.homeGoals
    h.goalsAgainst += m.awayGoals
    a.goalsFor += m.awayGoals
    a.goalsAgainst += m.homeGoals
    if (m.homeGoals > m.awayGoals) h.points += 3
    else if (m.homeGoals < m.awayGoals) a.points += 3
    else {
      h.points += 1
      a.points += 1
    }
  }
  return table
}

/**
 * Recompute the promoted-cohort prior from history.
 *
 * Run by the weekly build so the constant above never silently drifts away
 * from what the data says.
 */
export function measurePromotedPrior(
  matches: readonly {
    season: string
    home: string
    away: string
    homeGoals: number | null
    awayGoals: number | null
    finished: boolean
  }[],
): PromotedPrior & { sampleSize: number } {
  const seasons = [...new Set(matches.map((m) => m.season))].sort()
  const teamsBySeason = new Map<string, Set<string>>()
  for (const m of matches) {
    if (!teamsBySeason.has(m.season)) teamsBySeason.set(m.season, new Set())
    teamsBySeason.get(m.season)!.add(m.home)
    teamsBySeason.get(m.season)!.add(m.away)
  }

  const attackRatios: number[] = []
  const concedeRatios: number[] = []

  for (let i = 1; i < seasons.length; i++) {
    const season = seasons[i]!
    const prev = seasons[i - 1]!
    const cur = teamsBySeason.get(season)
    const before = teamsBySeason.get(prev)
    if (!cur || !before) continue
    const played = matches.filter((m) => m.season === season && m.finished)
    // Skip a season still in progress; its averages are not comparable.
    if (played.length < 300) continue

    const totalGoals = played.reduce((s, m) => s + (m.homeGoals ?? 0) + (m.awayGoals ?? 0), 0)
    const leagueAvg = totalGoals / (played.length * 2)
    if (leagueAvg <= 0) continue

    for (const team of cur) {
      if (before.has(team)) continue
      const theirs = played.filter((m) => m.home === team || m.away === team)
      if (theirs.length < 20) continue
      let gf = 0
      let ga = 0
      for (const m of theirs) {
        const isHome = m.home === team
        gf += (isHome ? m.homeGoals : m.awayGoals) ?? 0
        ga += (isHome ? m.awayGoals : m.homeGoals) ?? 0
      }
      attackRatios.push(gf / theirs.length / leagueAvg)
      concedeRatios.push(ga / theirs.length / leagueAvg)
    }
  }

  if (attackRatios.length === 0) {
    return { ...MEASURED_PROMOTED_PRIOR, sampleSize: 0 }
  }
  const avg = (xs: number[]): number => xs.reduce((a, b) => a + b, 0) / xs.length
  const meanAttack = avg(attackRatios)
  const meanConcede = avg(concedeRatios)
  const sdOf = (xs: number[]): number => {
    const m = avg(xs)
    return Math.sqrt(avg(xs.map((x) => (x - m) * (x - m))))
  }
  return {
    attack: Math.log(meanAttack),
    defence: -Math.log(meanConcede),
    // Combine the two spreads into one rating-scale uncertainty.
    sd: clamp((sdOf(attackRatios) + sdOf(concedeRatios)) / 2, 0.12, 0.3),
    sampleSize: attackRatios.length,
  }
}
