// ---------------------------------------------------------------------------
// Re-running a fixture with a changed squad.
//
// This is why the model core was kept free of Node and DOM dependencies from
// the start. When a reader removes a player, the browser runs the *same*
// functions that produced the published numbers — the same availability
// weighting, the same Dixon-Coles fit, the same scoreline matrix. Nothing is
// interpolated or approximated, so "what if he is out" gives the model's real
// answer rather than a plausible-looking one.
//
// Only the squad changes. Team ratings, home advantage, rest and the club's
// shape are fixed inputs carried in the artifact: a reader dropping a striker
// should not silently re-rate the whole club.
// ---------------------------------------------------------------------------

import { predictFixture, type FixturePrediction, type TeamContext } from './predict.ts'
import { teamAvailability, type PlayerRates, type TeamAvailability } from './availability.ts'
import { predictLineup, type PredictedLineup } from './lineup.ts'
import type { TeamRatings } from './fit.ts'
import type { RecomputeInputs, RecomputeSide } from './schema.ts'

export interface WhatIfRequest {
  inputs: RecomputeInputs
  homeCode: string
  awayCode: string
  kickoff: number
  homeSquad: readonly PlayerRates[]
  awaySquad: readonly PlayerRates[]
  /** Player ids the reader has removed, on either side. */
  removed: ReadonlySet<number>
  /** Players the reader has forced back in, overriding an injury flag. */
  restored?: ReadonlySet<number>
  now?: number
}

export interface WhatIfResult {
  prediction: FixturePrediction
  homeLineup: PredictedLineup
  awayLineup: PredictedLineup
  homeAvailability: TeamAvailability
  awayAvailability: TeamAvailability
}

/**
 * Apply the reader's edits to a squad.
 *
 * A removal is expressed as unavailability rather than deletion, so the player
 * still counts toward the club's replacement level — losing a striker from a
 * deep squad should hurt less than losing one from a thin squad, and deleting
 * the row outright would lose that.
 */
export function applyEdits(
  squad: readonly PlayerRates[],
  removed: ReadonlySet<number>,
  restored: ReadonlySet<number> = new Set(),
): PlayerRates[] {
  return squad.map((p) => {
    if (removed.has(p.id)) return { ...p, status: 'u', chanceNextRound: 0, news: 'Removed by you' }
    if (restored.has(p.id)) return { ...p, status: 'a', chanceNextRound: 100, news: 'Forced in by you' }
    return p
  })
}

function ratingsFrom(inputs: RecomputeInputs, homeCode: string, awayCode: string): TeamRatings {
  return {
    attack: { [homeCode]: inputs.home.attack, [awayCode]: inputs.away.attack },
    defence: { [homeCode]: inputs.home.defence, [awayCode]: inputs.away.defence },
    homeAdvantage: inputs.homeAdvantage,
    rho: inputs.rho,
    weight: {},
    effectiveN: 0,
  }
}

function contextFrom(side: RecomputeSide, code: string, availability: TeamAvailability): TeamContext {
  return {
    code,
    availability,
    rest: side.rest,
    ratingSd: side.ratingSd,
    matchesPlayed: side.matchesPlayed,
    promoted: side.promoted,
  }
}

/** Re-run a fixture with the reader's squad edits applied. */
export function recomputeFixture(req: WhatIfRequest): WhatIfResult {
  const now = req.now ?? Date.now()
  const restored = req.restored ?? new Set<number>()

  const homeSquad = applyEdits(req.homeSquad, req.removed, restored)
  const awaySquad = applyEdits(req.awaySquad, req.removed, restored)

  const homeAvailability = teamAvailability(req.homeCode, homeSquad)
  const awayAvailability = teamAvailability(req.awayCode, awaySquad)

  const prediction = predictFixture({
    home: contextFrom(req.inputs.home, req.homeCode, homeAvailability),
    away: contextFrom(req.inputs.away, req.awayCode, awayAvailability),
    kickoff: req.kickoff,
    ratings: ratingsFrom(req.inputs, req.homeCode, req.awayCode),
  })

  return {
    prediction,
    homeLineup: predictLineup(homeSquad, req.inputs.home.shape, now),
    awayLineup: predictLineup(awaySquad, req.inputs.away.shape, now),
    homeAvailability,
    awayAvailability,
  }
}

export interface ProbDelta {
  home: number
  draw: number
  away: number
  /** Largest absolute shift across the three outcomes. */
  largest: number
}

/** How far the edited forecast has moved from the published one. */
export function probabilityDelta(
  before: { home: number; draw: number; away: number },
  after: { home: number; draw: number; away: number },
): ProbDelta {
  const home = after.home - before.home
  const draw = after.draw - before.draw
  const away = after.away - before.away
  return { home, draw, away, largest: Math.max(Math.abs(home), Math.abs(draw), Math.abs(away)) }
}
