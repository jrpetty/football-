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
import {
  teamAvailability, squadAttackValue, squadDefenceValue,
  type PlayerRates, type TeamAvailability,
} from './availability.ts'
import { clamp } from './math.ts'
import { predictLineup, type PredictedLineup } from './lineup.ts'
import type { TeamRatings } from './fit.ts'
import type { RecomputeInputs, RecomputeSide } from './schema.ts'

export interface ConfirmedLineups {
  /** Player ids named in the home side's starting eleven. */
  home: ReadonlySet<number>
  away: ReadonlySet<number>
}

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
  /**
   * A confirmed team sheet, once one is available. Quite different from an
   * edit: it replaces the model's guess at who plays with what a reader can
   * actually see, which is the single piece of information the pipeline
   * cannot have when it publishes.
   */
  confirmed?: ConfirmedLineups | null
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

/**
 * Minutes a named substitute is expected to get.
 *
 * A confirmed eleven does not mean the other eighteen contribute nothing —
 * substitutes come on and score. Zeroing them would overstate the effect of
 * being left out and make every confirmed line-up swing the forecast further
 * than it should.
 */
export const BENCH_MINUTE_SHARE = 0.12

/**
 * Replace expected minutes with a confirmed team sheet.
 *
 * Starters are treated as playing the full match, everyone else as a possible
 * substitute. Players already flagged unavailable stay that way — a team sheet
 * that omits an injured player tells us nothing new about him.
 */
export function applyConfirmedLineup(
  squad: readonly PlayerRates[],
  starting: ReadonlySet<number>,
): PlayerRates[] {
  // A sheet that named nobody is not information; leave the squad alone.
  if (starting.size === 0) return [...squad]
  return squad.map((p) => {
    if (starting.has(p.id)) {
      return { ...p, minuteShare: 1, status: 'a', chanceNextRound: 100 }
    }
    return { ...p, minuteShare: Math.min(p.minuteShare, BENCH_MINUTE_SHARE) }
  })
}

/**
 * How much a confirmed eleven differs from the side that was expected.
 *
 * Returned as log shifts on the club's attack and defence ratings, damped by
 * the same coefficient the pipeline uses for availability and clamped so one
 * unusual team sheet cannot send a forecast somewhere absurd. A side naming
 * its strongest available eleven produces roughly zero; one resting five
 * players produces a real, visible reduction.
 */
export function confirmedShift(
  expected: readonly PlayerRates[],
  confirmed: readonly PlayerRates[],
  strength: number,
): { attack: number; defence: number } {
  const baseAttack = squadAttackValue(expected)
  const baseDefence = squadDefenceValue(expected)
  if (baseAttack <= 0 || baseDefence <= 0) return { attack: 0, defence: 0 }
  return {
    attack: strength * Math.log(clamp(squadAttackValue(confirmed) / baseAttack, 0.7, 1.2)),
    defence: strength * Math.log(clamp(squadDefenceValue(confirmed) / baseDefence, 0.7, 1.2)),
  }
}

function ratingsFrom(
  inputs: RecomputeInputs,
  homeCode: string,
  awayCode: string,
  shifts: { home: { attack: number; defence: number }; away: { attack: number; defence: number } },
): TeamRatings {
  return {
    attack: {
      [homeCode]: inputs.home.attack + shifts.home.attack,
      [awayCode]: inputs.away.attack + shifts.away.attack,
    },
    defence: {
      [homeCode]: inputs.home.defence + shifts.home.defence,
      [awayCode]: inputs.away.defence + shifts.away.defence,
    },
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

  // A confirmed sheet is applied first, then the reader's own edits on top —
  // so someone can still ask "what if this named starter picks up a knock in
  // the warm-up".
  const homeBase = req.confirmed
    ? applyConfirmedLineup(req.homeSquad, req.confirmed.home)
    : req.homeSquad
  const awayBase = req.confirmed
    ? applyConfirmedLineup(req.awaySquad, req.confirmed.away)
    : req.awaySquad

  const homeSquad = applyEdits(homeBase, req.removed, restored)
  const awaySquad = applyEdits(awayBase, req.removed, restored)

  // Use the pipeline's own damping, never the library default.
  const options = { strength: req.inputs.availabilityStrength }
  const homeAvailability = teamAvailability(req.homeCode, homeSquad, options)
  const awayAvailability = teamAvailability(req.awayCode, awaySquad, options)

  // A confirmed sheet is a rating adjustment, not an availability one: it says
  // who is actually on the pitch, measured against who was expected to be.
  const strength = req.inputs.availabilityStrength
  const noShift = { attack: 0, defence: 0 }
  const shifts = req.confirmed
    ? {
        home: confirmedShift(req.homeSquad, homeSquad, strength),
        away: confirmedShift(req.awaySquad, awaySquad, strength),
      }
    : { home: noShift, away: noShift }

  const prediction = predictFixture({
    home: contextFrom(req.inputs.home, req.homeCode, homeAvailability),
    away: contextFrom(req.inputs.away, req.awayCode, awayAvailability),
    kickoff: req.kickoff,
    ratings: ratingsFrom(req.inputs, req.homeCode, req.awayCode, shifts),
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
