// ---------------------------------------------------------------------------
// Predicted starting elevens.
//
// Confirmed team sheets only appear about an hour before kickoff, and this
// system publishes days ahead — so it predicts the XI rather than waiting for
// one. That is the honest position and it is what the model already does
// internally: every scoring rate is weighted by how much a player is expected
// to feature. This module makes that implicit judgement visible.
//
// The selection is deliberately simple and legible. A manager's real team
// sheet depends on things no public feed carries — training-ground fitness,
// the next fixture, a grudge — so an elaborate model here would dress up
// guesswork. Recent minutes plus availability captures most of it.
// ---------------------------------------------------------------------------

import { clamp, round } from './math.ts'
import { playProbability, absenceReason, type PlayerRates, type Position } from './availability.ts'
import { DEFAULT_SHAPE, type ClubShape } from './formation.ts'

export interface LineupSlot {
  id: number
  name: string
  position: Position
  /** Probability this player features at all. */
  playProbability: number
  /** How confident we are that he *starts*, 0-1. */
  startConfidence: number
  /** Share of his club's minutes he has been taking. */
  minuteShare: number
  status: string
  news: string
  /**
   * True when the player joined within the last few months. His record came
   * with him from another club, so it says something about his quality but
   * little about the role he will be given here.
   */
  newSigning: boolean
}

export interface PredictedLineup {
  /** Outfield shape, e.g. "4-3-3". Derived from who is picked, not imposed. */
  formation: string
  /**
   * What the selection was based on. 'minutes' is a real signal; 'price' means
   * the club has no top-flight record to read (a promoted side), so the eleven
   * is inferred from squad valuation and should be treated as a rough guess.
   */
  basis: 'minutes' | 'price'
  starters: LineupSlot[]
  bench: LineupSlot[]
  /** Players who would likely start but are injured, suspended or doubtful. */
  absent: LineupSlot[]
  /** Mean start confidence across the eleven. */
  confidence: number
  /** How often the club has actually started this shape, 0-1. */
  shapeShare: number
}

/**
 * How likely a player is to start.
 *
 * Minute share is the signal that matters: a player taking 90% of his club's
 * minutes is a starter, one taking 30% is a substitute. Availability scales
 * it, so a doubtful player drops down the order rather than being deleted.
 */
function startScore(p: PlayerRates): number {
  return clamp(p.minuteShare, 0, 1) * playProbability(p)
}

/**
 * Fallback ranking for a club with no top-flight minutes.
 *
 * A promoted side's players have no record in this data at all — their
 * Championship season simply is not covered. Squad price is the only signal
 * left: it encodes what the club and the market expect of a player. It is a
 * weak proxy and the lineup is labelled as such rather than presented with
 * the same confidence as one built from minutes.
 */
function priceScore(p: PlayerRates, maxCost: number): number {
  const base = maxCost > 0 ? clamp(p.cost / maxCost, 0, 1) : 0
  return base * playProbability(p)
}

/** Days within which a move still counts as "new" for role uncertainty. */
const NEW_SIGNING_DAYS = 120

function isNewSigning(joinedAt: string | null, now: number): boolean {
  if (!joinedAt) return false
  const t = Date.parse(joinedAt)
  return Number.isFinite(t) && now - t < NEW_SIGNING_DAYS * 86400000
}

function toSlot(
  p: PlayerRates,
  basis: 'minutes' | 'price',
  maxCost: number,
  now: number,
): LineupSlot {
  return {
    id: p.id,
    name: p.name,
    position: p.position,
    playProbability: round(playProbability(p), 3),
    startConfidence: round(
      clamp(basis === 'minutes' ? startScore(p) : priceScore(p, maxCost), 0, 1),
      3,
    ),
    minuteShare: round(p.minuteShare, 3),
    status: p.status,
    news: p.news,
    newSigning: isNewSigning(p.joinedAt, now),
  }
}

/**
 * Absolute bounds, used only as a backstop when a club's real shape is
 * unknown. The shape itself now comes from what the club has actually been
 * starting — see formation.ts.
 */
const BOUNDS: Record<Exclude<Position, 'GK'>, { min: number; max: number }> = {
  DEF: { min: 3, max: 5 },
  MID: { min: 2, max: 6 },
  FWD: { min: 0, max: 3 },
}

/**
 * Pick the likely eleven.
 *
 * Filling by raw score alone can produce nonsense — eight midfielders, or a
 * side with no striker — because minute share does not know about shape. So
 * each line's minimum is filled first, then the remaining places go to the
 * best available regardless of position, subject to each line's maximum.
 */
export function predictLineup(
  squad: readonly PlayerRates[],
  shape: ClubShape = DEFAULT_SHAPE,
  now: number = Date.now(),
): PredictedLineup {
  // A promoted club has no minutes in this data at all, so fall back to price.
  const withMinutes = squad.filter((p) => p.minutes > 0).length
  const basis: 'minutes' | 'price' = withMinutes >= 11 ? 'minutes' : 'price'
  const maxCost = squad.reduce((m, p) => Math.max(m, p.cost), 0)
  const score = (p: PlayerRates): number =>
    basis === 'minutes' ? startScore(p) : priceScore(p, maxCost)

  const pool = squad
    .map((p) => ({ p, score: score(p) }))
    // A player who cannot feature is never a selection case, whatever his
    // record. Without this the shape-filling loops reach past the fit players
    // to satisfy a line and start someone who is injured.
    .filter((x) => playProbability(x.p) > 0)
    .filter((x) => (basis === 'minutes' ? x.p.minutes > 0 : true))
    .sort((a, b) => b.score - a.score)

  const empty: PredictedLineup = {
    formation: 'unknown',
    basis,
    starters: [],
    bench: [],
    absent: [],
    confidence: 0,
    shapeShare: shape.share,
  }
  if (pool.length < 11) {
    return { ...empty, starters: pool.map((x) => toSlot(x.p, basis, maxCost, now)) }
  }

  const chosen: PlayerRates[] = []
  const used = new Set<number>()
  const counts: Record<Position, number> = { GK: 0, DEF: 0, MID: 0, FWD: 0 }

  const take = (p: PlayerRates): void => {
    chosen.push(p)
    used.add(p.id)
    counts[p.position]++
  }

  // A goalkeeper is not optional and never interchangeable with an outfielder.
  const keeper =
    pool.find((x) => x.p.position === 'GK')?.p ??
    [...squad]
      .filter((p) => p.position === 'GK' && playProbability(p) > 0)
      .sort((a, b) => b.cost - a.cost)[0]
  if (keeper) take(keeper)

  // Fill each line to exactly the number the club actually starts. This is the
  // whole point: minute share alone knows nothing about shape, and letting it
  // decide produced sides no manager would ever pick.
  const target: Record<Exclude<Position, 'GK'>, number> = {
    DEF: shape.def,
    MID: shape.mid,
    FWD: shape.fwd,
  }

  // Fit players with no minutes yet — squad depth and new signings. They are
  // not first choice, but they are who actually plays when a club runs short
  // in one position. Liverpool went into this season with three fit defenders
  // who had Premier League minutes and five who did not; without this the
  // selection reached for a sixth midfielder instead of a reserve full-back,
  // which is not a side anyone would field.
  const reserves = squad
    .filter((p) => playProbability(p) > 0 && !pool.some((x) => x.p.id === p.id))
    .sort((a, b) => b.cost - a.cost)

  for (const position of ['DEF', 'MID', 'FWD'] as const) {
    for (const entry of pool) {
      if (counts[position] >= target[position]) break
      if (used.has(entry.p.id) || entry.p.position !== position) continue
      take(entry.p)
    }
    // Still short in this line: call on the reserves before distorting shape.
    for (const p of reserves) {
      if (counts[position] >= target[position]) break
      if (used.has(p.id) || p.position !== position) continue
      take(p)
    }
  }

  // A line the club cannot fill — three fit defenders for a back four, say —
  // leaves places over. Give them to the best available, within sane bounds.
  for (const entry of pool) {
    if (chosen.length >= 11) break
    if (used.has(entry.p.id) || entry.p.position === 'GK') continue
    const position = entry.p.position as Exclude<Position, 'GK'>
    if (counts[position] >= BOUNDS[position].max) continue
    take(entry.p)
  }
  for (const entry of pool) {
    if (chosen.length >= 11) break
    if (used.has(entry.p.id) || entry.p.position === 'GK') continue
    take(entry.p)
  }

  // If eleven fit players cannot be found, report an incomplete side rather
  // than filling the gap with someone who is injured or suspended.
  if (chosen.length < 11) {
    return {
      ...empty,
      starters: chosen.map((p) => toSlot(p, basis, maxCost, now)),
      absent: squad
        .filter((p) => playProbability(p) < 0.5 && p.minuteShare >= 0.4)
        .map((p) => toSlot(p, basis, maxCost, now)),
    }
  }

  // Present the eleven back-to-front, the way a team sheet reads.
  const order: Record<Position, number> = { GK: 0, DEF: 1, MID: 2, FWD: 3 }
  const starters = chosen
    .sort((a, b) => order[a.position] - order[b.position] || score(b) - score(a))
    .map((p) => toSlot(p, basis, maxCost, now))

  const bench = pool
    .filter((x) => !used.has(x.p.id) && playProbability(x.p) > 0)
    .slice(0, 7)
    .map((x) => toSlot(x.p, basis, maxCost, now))

  const absent = squad
    .filter((p) => playProbability(p) < 0.5 && p.minuteShare >= 0.4)
    .sort((a, b) => b.minuteShare - a.minuteShare)
    .map((p) => toSlot(p, basis, maxCost, now))

  const confidence =
    starters.length > 0
      ? round(starters.reduce((s, x) => s + x.startConfidence, 0) / starters.length, 3)
      : 0

  return {
    formation: `${counts.DEF}-${counts.MID}-${counts.FWD}`,
    basis,
    starters,
    bench,
    absent,
    confidence: basis === 'price' ? round(confidence * 0.5, 3) : confidence,
    shapeShare: shape.share,
  }
}

/** Readable reason a player is unavailable, for the UI. */
export function slotAbsenceReason(slot: LineupSlot): string {
  return absenceReason(slot.status)
}
