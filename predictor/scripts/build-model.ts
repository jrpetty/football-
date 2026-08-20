// ---------------------------------------------------------------------------
// Turning the corpus into fitted ratings and per-player rates.
//
// Shared by the artifact build and the backtest harness, so the numbers shown
// on the site and the numbers the backtest scores come from identical code.
// ---------------------------------------------------------------------------

import { fitRatings, blendRatings, type TeamRatings, type FitObservation } from '../src/core/fit.ts'
import { measurePromotedPrior, promotedPrior, championshipTable, type PromotedPrior } from '../src/core/promoted.ts'
import type { PlayerRates } from '../src/core/availability.ts'
import type { TeamForm } from '../src/core/evidence.ts'
import type { NormMatch, NormPlayer, NormPlayerGw } from './sources/types.ts'
import { clamp } from '../src/core/math.ts'

export interface ModelParams {
  /** Time decay per day. */
  xi: number
  /** Weight on xG-fitted ratings versus goals-fitted, 0-1. */
  xgBlend: number
  /** Ridge strength in the fit. */
  ridge: number
  /** Effective-match scale controlling how fast a new club's prior fades. */
  shrinkScale: number
  /** Availability damping. */
  availabilityStrength: number
  /** Trailing matches used for form. */
  formWindow: number
}

export const DEFAULT_PARAMS: ModelParams = {
  // Both validated by walk-forward backtest on 2025/26.
  xi: 0.0018,
  xgBlend: 0.7,
  ridge: 0.02,
  shrinkScale: 12,
  availabilityStrength: 0.7,
  formWindow: 8,
}

export interface FittedModel {
  ratings: TeamRatings
  /** Per-team rating uncertainty, inflated where evidence is thin. */
  ratingSd: Record<string, number>
  /** Teams with no top-flight record last season. */
  promoted: Set<string>
  prior: PromotedPrior & { sampleSize: number }
  /** Effective (decay-weighted) matches behind each team's rating. */
  effectiveMatches: Record<string, number>
}

/** Observations for the goals fit. */
function goalObservations(matches: readonly NormMatch[], before: number): FitObservation[] {
  const out: FitObservation[] = []
  for (const m of matches) {
    if (!m.finished || m.homeGoals === null || m.awayGoals === null) continue
    if (m.date >= before) continue
    out.push({ home: m.home, away: m.away, homeGoals: m.homeGoals, awayGoals: m.awayGoals, date: m.date })
  }
  return out
}

/** Observations for the xG fit; only matches that actually carry xG. */
function xgObservations(matches: readonly NormMatch[], before: number): FitObservation[] {
  const out: FitObservation[] = []
  for (const m of matches) {
    if (!m.finished || m.homeXg === null || m.awayXg === null) continue
    if (m.date >= before) continue
    // A fixture with no recorded xG on either side is missing data, not a 0-0.
    if (m.homeXg === 0 && m.awayXg === 0) continue
    out.push({ home: m.home, away: m.away, homeGoals: m.homeXg, awayGoals: m.awayXg, date: m.date })
  }
  return out
}

/**
 * Fit team ratings as of a point in time.
 *
 * Only matches strictly before `asOf` are used, which is what makes the
 * backtest honest — the walk-forward evaluation calls this with each fixture's
 * kickoff and therefore never sees the future.
 */
export function fitModel(
  matches: readonly NormMatch[],
  championship: readonly NormMatch[],
  asOf: number,
  currentSeasonTeams: readonly string[],
  previousSeasonTeams: readonly string[],
  params: ModelParams = DEFAULT_PARAMS,
): FittedModel {
  const goals = goalObservations(matches, asOf)
  const xg = xgObservations(matches, asOf)

  const goalsFit = fitRatings(goals, { xi: params.xi, asOf, ridge: params.ridge, fitRho: true })
  const xgFit =
    xg.length > 200
      ? fitRatings(xg, { xi: params.xi, asOf, ridge: params.ridge, fitRho: false })
      : goalsFit
  const blended = blendRatings(goalsFit, xgFit, xg.length > 200 ? params.xgBlend : 0)

  const prior = measurePromotedPrior(matches.filter((m) => m.date < asOf))

  // A club is "promoted" if it was not in the top flight last season.
  const prevSet = new Set(previousSeasonTeams)
  const promoted = new Set(currentSeasonTeams.filter((t) => !prevSet.has(t)))

  // Championship record from the season before, for the promoted prior.
  const champSeasons = [...new Set(championship.map((m) => m.season))].sort()
  const lastChampSeason = champSeasons[champSeasons.length - 1] ?? ''
  const champTable = championshipTable(championship, lastChampSeason)

  const ratingSd: Record<string, number> = {}
  const effectiveMatches: Record<string, number> = {}
  const attack: Record<string, number> = { ...blended.attack }
  const defence: Record<string, number> = { ...blended.defence }

  for (const team of currentSeasonTeams) {
    // Decay-weighted matches behind this club's rating. Each match contributes
    // to two clubs, so halve to get per-club matches.
    const w = (blended.weight[team] ?? 0) / 2
    effectiveMatches[team] = w

    const isPromoted = promoted.has(team)
    const teamPrior = isPromoted ? promotedPrior(champTable.get(team) ?? null, prior) : null

    // Shrink toward the prior in proportion to how little evidence there is.
    // A club with no top-flight record at all gets the prior outright; one
    // with a long recent history is barely moved.
    const shrink = teamPrior ? clamp(params.shrinkScale / (params.shrinkScale + w), 0, 1) : 0

    if (teamPrior) {
      const fittedAttack = attack[team] ?? teamPrior.attack
      const fittedDefence = defence[team] ?? teamPrior.defence
      attack[team] = (1 - shrink) * fittedAttack + shrink * teamPrior.attack
      defence[team] = (1 - shrink) * fittedDefence + shrink * teamPrior.defence
      ratingSd[team] = clamp(0.09 + shrink * (teamPrior.sd - 0.02), 0.08, 0.3)
    } else {
      if (attack[team] === undefined) attack[team] = 0
      if (defence[team] === undefined) defence[team] = 0
      // More evidence, tighter rating. 40 effective matches is about a season.
      ratingSd[team] = clamp(0.2 - 0.12 * Math.min(1, w / 40), 0.08, 0.25)
    }
  }

  return {
    ratings: { ...blended, attack, defence },
    ratingSd,
    promoted,
    prior,
    effectiveMatches,
  }
}

// --- Player rates ------------------------------------------------------------

/**
 * Build per-90 rates and minute shares for every player.
 *
 * At the start of a season the FPL pre-season snapshot carries each player's
 * *previous* season totals already attributed to their new club, so a summer
 * transfer arrives with his record intact. Once matches are played, the
 * per-gameweek rows take over.
 */
export function buildPlayerRates(
  players: readonly NormPlayer[],
  playerGws: readonly NormPlayerGw[],
  asOf: number,
  windowMatches = 12,
): PlayerRates[] {
  // Recent per-player windows, newest first.
  const recent = new Map<number, NormPlayerGw[]>()
  for (const g of playerGws) {
    if (g.date >= asOf) continue
    if (!recent.has(g.element)) recent.set(g.element, [])
    recent.get(g.element)!.push(g)
  }
  for (const list of recent.values()) {
    list.sort((a, b) => b.date - a.date)
    list.length = Math.min(list.length, windowMatches)
  }

  // Team match counts, so minute share is a share of one XI slot.
  const teamMatches = new Map<string, Set<number>>()
  for (const g of playerGws) {
    if (g.date >= asOf) continue
    if (!teamMatches.has(g.team)) teamMatches.set(g.team, new Set())
    teamMatches.get(g.team)!.add(g.fixtureId)
  }

  const out: PlayerRates[] = []
  for (const p of players) {
    const window = recent.get(p.id) ?? []
    const useWindow = window.length >= 4

    const minutes = useWindow ? window.reduce((s, g) => s + g.minutes, 0) : p.minutes
    const xg = useWindow ? window.reduce((s, g) => s + g.xg, 0) : p.xg
    const xa = useWindow ? window.reduce((s, g) => s + g.xa, 0) : p.xa
    const xgc = useWindow ? window.reduce((s, g) => s + g.xgConceded, 0) : p.xgConceded
    const saves = useWindow ? window.reduce((s, g) => s + g.saves, 0) : p.saves
    const yellows = useWindow ? window.reduce((s, g) => s + g.yellowCards, 0) : p.yellowCards
    const reds = useWindow ? window.reduce((s, g) => s + g.redCards, 0) : p.redCards

    const per90 = (v: number): number => (minutes > 0 ? (v * 90) / minutes : 0)

    // Denominator for minute share: how many matches the club has played in
    // the same window.
    const clubMatches = useWindow
      ? Math.max(window.length, 1)
      : Math.max((teamMatches.get(p.team)?.size ?? 0) || 38, 1)
    const minuteShare = clamp(minutes / (clubMatches * 90), 0, 1)

    out.push({
      id: p.id,
      name: p.name,
      team: p.team,
      position: p.position,
      minutes,
      xgi90: per90(xg + xa),
      xgc90: per90(xgc),
      saves90: per90(saves),
      yellow90: per90(yellows),
      red90: per90(reds),
      status: p.status,
      chanceNextRound: p.chanceNextRound,
      news: p.news,
      cost: p.cost,
      minuteShare,
    })
  }
  return out
}

// --- Form --------------------------------------------------------------------

/** Trailing form for one team, optionally restricted to a venue. */
export function teamForm(
  matches: readonly NormMatch[],
  team: string,
  asOf: number,
  window: number,
  venue: 'home' | 'away' | null,
): TeamForm {
  const played = matches
    .filter((m) => m.finished && m.date < asOf && (m.home === team || m.away === team))
    .sort((a, b) => b.date - a.date)

  const recentMatches = played.slice(0, window)
  let goalsFor = 0
  let goalsAgainst = 0
  let xgFor = 0
  let xgAgainst = 0
  let points = 0
  const recent: string[] = []
  for (const m of recentMatches) {
    const isHome = m.home === team
    const gf = (isHome ? m.homeGoals : m.awayGoals) ?? 0
    const ga = (isHome ? m.awayGoals : m.homeGoals) ?? 0
    goalsFor += gf
    goalsAgainst += ga
    xgFor += (isHome ? m.homeXg : m.awayXg) ?? gf
    xgAgainst += (isHome ? m.awayXg : m.homeXg) ?? ga
    points += gf > ga ? 3 : gf === ga ? 1 : 0
    recent.push(gf > ga ? 'W' : gf === ga ? 'D' : 'L')
  }

  // Venue split over a longer window; a single season of home games is only 19.
  let venuePpg: number | null = null
  let venueMatches = 0
  if (venue) {
    const atVenue = played.filter((m) => (venue === 'home' ? m.home === team : m.away === team)).slice(0, 19)
    venueMatches = atVenue.length
    if (venueMatches > 0) {
      let pts = 0
      for (const m of atVenue) {
        const isHome = m.home === team
        const gf = (isHome ? m.homeGoals : m.awayGoals) ?? 0
        const ga = (isHome ? m.awayGoals : m.homeGoals) ?? 0
        pts += gf > ga ? 3 : gf === ga ? 1 : 0
      }
      venuePpg = pts / venueMatches
    }
  }

  return {
    team,
    matches: recentMatches.length,
    goalsFor,
    goalsAgainst,
    xgFor,
    xgAgainst,
    points,
    recent,
    venuePpg,
    venueMatches,
  }
}
