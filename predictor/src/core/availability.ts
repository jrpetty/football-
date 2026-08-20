// ---------------------------------------------------------------------------
// Who is actually playing, and what it costs the team.
//
// This is the part that makes the model about *players* rather than just club
// badges. The measured effect is real and sizeable: across 2023-24 to 2025-26,
// teams with all three of their top expected-goal-involvement attackers
// available scored 1.520 goals per match (n=1522); teams missing at least two
// of the three scored 1.146 (n=103). That is a x0.75 attacking multiplier, a
// log-lambda shift of about -0.29. Crucially the xG deltas match the goal
// deltas to within 0.005, so it is a change in chance creation, not finishing
// luck.
//
// That raw split is confounded — sides that rest attackers are often already
// safe, and squads that lose two of three best players may be weaker overall.
// So the multiplier is deliberately damped by `strength` below 1 rather than
// applied at face value.
// ---------------------------------------------------------------------------

import { clamp } from './math.ts'

export type Position = 'GK' | 'DEF' | 'MID' | 'FWD'

/** Per-90 rates and availability for one player. */
export interface PlayerRates {
  id: number
  name: string
  team: string
  position: Position
  /** Minutes played in the window these rates were built from. */
  minutes: number
  /** Expected goal involvements per 90 (xG + xA). */
  xgi90: number
  /** Expected goals conceded per 90 while on the pitch (defensive proxy). */
  xgc90: number
  /** Saves per 90; only meaningful for keepers. */
  saves90: number
  /** Yellow cards per 90. */
  yellow90: number
  /** Red cards per 90. */
  red90: number
  /** FPL availability flag: a/i/d/s/u. */
  status: string
  /** Percent chance of featuring, when the feed states one. */
  chanceNextRound: number | null
  /** Free-text injury/suspension note from the feed. */
  news: string
  /** Price in millions — a serviceable proxy for perceived quality. */
  cost: number
  /** Share of the team's available minutes this player took recently. */
  minuteShare: number
  /** ISO date he joined this club, when known — used to flag new signings. */
  joinedAt: string | null
}

export interface MissingPlayer {
  id: number
  name: string
  position: Position
  /** 'injured' | 'suspended' | 'doubtful' | 'unavailable' */
  reason: string
  news: string
  /** Probability the player features at all. */
  playProbability: number
  /** Share of team attacking value this player represents. */
  attackShare: number
  /** Share of team defensive value this player represents. */
  defenceShare: number
}

export interface TeamAvailability {
  team: string
  /** Ratio of available attacking value to full-strength value. */
  attackMultiplier: number
  /** Ratio of available defensive value to full-strength value. */
  defenceMultiplier: number
  /** Additive shift applied to log(lambda) for this team's attack. */
  logAttackShift: number
  /** Additive shift applied to log(lambda) for the opponent's attack. */
  logDefenceShift: number
  missing: MissingPlayer[]
  /** True when the feed gave us nothing to work with. */
  unknown: boolean
}

export interface AvailabilityOptions {
  /**
   * Damping on the raw multiplier. 1.0 would take the observed x0.75 at face
   * value; the confounding above argues for less. Tunable by backtest.
   */
  strength: number
  /** Bounds on the multiplier, so one odd data point cannot distort a fixture. */
  minMultiplier: number
  maxMultiplier: number
  /** A doubtful player at this chance-of-playing or below counts as absent. */
  doubtfulThreshold: number
}

export const DEFAULT_AVAILABILITY: AvailabilityOptions = {
  strength: 0.7,
  minMultiplier: 0.72,
  maxMultiplier: 1.06,
  doubtfulThreshold: 50,
}

/** Probability a player features, from the feed's status and chance fields. */
export function playProbability(p: Pick<PlayerRates, 'status' | 'chanceNextRound'>): number {
  if (p.chanceNextRound !== null && Number.isFinite(p.chanceNextRound)) {
    return clamp(p.chanceNextRound / 100, 0, 1)
  }
  switch (p.status) {
    case 'a':
      return 1
    case 'd':
      return 0.5
    case 'i':
    case 's':
    case 'u':
    case 'n':
      return 0
    default:
      return 1
  }
}

/** Human-readable absence reason. */
export function absenceReason(status: string): string {
  switch (status) {
    case 'i':
      return 'injured'
    case 's':
      return 'suspended'
    case 'd':
      return 'doubtful'
    case 'u':
    case 'n':
      return 'unavailable'
    default:
      return 'available'
  }
}

/**
 * Attacking value of a player-slot: involvement rate weighted by how much of
 * the team's minutes they take. A high-rate player who plays 20 minutes a week
 * is worth less than a good one who plays every minute.
 */
function attackValue(p: PlayerRates): number {
  return Math.max(0, p.xgi90) * p.minuteShare
}

/**
 * Defensive value. There is no clean per-player defensive metric in this feed,
 * so this blends two weak proxies: goals-conceded rate while on the pitch
 * (inverted) and price, which encodes the market's view of quality. Keepers
 * additionally get credit for save volume. This is the least well-grounded
 * part of the model and is flagged as such in the UI.
 */
function defenceValue(p: PlayerRates, squadMedianCost: number): number {
  if (p.position === 'FWD') return 0.15 * p.minuteShare
  const costRatio = squadMedianCost > 0 ? clamp(p.cost / squadMedianCost, 0.5, 2) : 1
  const concedeQuality = p.xgc90 > 0 ? clamp(1.4 / (1 + p.xgc90), 0.4, 1.6) : 1
  const keeperBonus = p.position === 'GK' ? clamp(p.saves90 / 3, 0.5, 1.5) : 1
  const positional = p.position === 'GK' ? 1.6 : p.position === 'DEF' ? 1.2 : 0.7
  return positional * costRatio * concedeQuality * keeperBonus * p.minuteShare
}

/**
 * Replacement level: what the club actually gets when a starter is out.
 *
 * Not zero — a squad player comes in. Taken as the 35th percentile of value
 * among that club's players in the same position who have real minutes, so a
 * deep squad is punished less than a thin one. That squad-depth effect is the
 * whole reason this is computed per club rather than league-wide.
 */
function replacementValue(pool: readonly number[]): number {
  if (pool.length === 0) return 0
  const sorted = [...pool].sort((a, b) => a - b)
  const idx = Math.min(sorted.length - 1, Math.floor(sorted.length * 0.35))
  return sorted[idx]!
}

/**
 * Compute a team's availability multipliers from its current squad.
 *
 * Each absent player's value is replaced by replacement level rather than
 * deleted, and the resulting ratio is damped and clamped.
 */
export function teamAvailability(
  team: string,
  squad: readonly PlayerRates[],
  options: Partial<AvailabilityOptions> = {},
): TeamAvailability {
  const o = { ...DEFAULT_AVAILABILITY, ...options }
  // Squad players with enough minutes to have meaningful rates.
  const rated = squad.filter((p) => p.minutes >= 180)
  if (rated.length < 6) {
    return {
      team,
      attackMultiplier: 1,
      defenceMultiplier: 1,
      logAttackShift: 0,
      logDefenceShift: 0,
      missing: [],
      unknown: true,
    }
  }

  const costs = rated.map((p) => p.cost).sort((a, b) => a - b)
  const squadMedianCost = costs[Math.floor(costs.length / 2)] ?? 5

  const attackByPos = new Map<Position, number[]>()
  const defenceByPos = new Map<Position, number[]>()
  for (const p of rated) {
    if (!attackByPos.has(p.position)) attackByPos.set(p.position, [])
    if (!defenceByPos.has(p.position)) defenceByPos.set(p.position, [])
    attackByPos.get(p.position)!.push(attackValue(p))
    defenceByPos.get(p.position)!.push(defenceValue(p, squadMedianCost))
  }

  let baseAttack = 0
  let availAttack = 0
  let baseDefence = 0
  let availDefence = 0
  const missing: MissingPlayer[] = []

  const totalAttack = rated.reduce((s, p) => s + attackValue(p), 0) || 1
  const totalDefence = rated.reduce((s, p) => s + defenceValue(p, squadMedianCost), 0) || 1

  for (const p of rated) {
    const av = attackValue(p)
    const dv = defenceValue(p, squadMedianCost)
    baseAttack += av
    baseDefence += dv

    const prob = playProbability(p)
    const attackRepl = replacementValue(attackByPos.get(p.position) ?? [])
    const defenceRepl = replacementValue(defenceByPos.get(p.position) ?? [])

    // Expected value on the day: their own if they play, replacement if not.
    availAttack += prob * av + (1 - prob) * attackRepl
    availDefence += prob * dv + (1 - prob) * defenceRepl

    const effectivelyOut =
      prob === 0 || (p.chanceNextRound !== null && p.chanceNextRound <= o.doubtfulThreshold)
    if (effectivelyOut && p.status !== 'a') {
      missing.push({
        id: p.id,
        name: p.name,
        position: p.position,
        reason: absenceReason(p.status),
        news: p.news,
        playProbability: prob,
        attackShare: av / totalAttack,
        defenceShare: dv / totalDefence,
      })
    }
  }

  const rawAttack = baseAttack > 0 ? availAttack / baseAttack : 1
  const rawDefence = baseDefence > 0 ? availDefence / baseDefence : 1

  // Damp toward 1, then clamp.
  const attackMultiplier = clamp(
    1 + (rawAttack - 1) * o.strength,
    o.minMultiplier,
    o.maxMultiplier,
  )
  const defenceMultiplier = clamp(
    1 + (rawDefence - 1) * o.strength,
    o.minMultiplier,
    o.maxMultiplier,
  )

  missing.sort((a, b) => b.attackShare + b.defenceShare - (a.attackShare + a.defenceShare))

  return {
    team,
    attackMultiplier,
    defenceMultiplier,
    // A weaker attack lowers this team's own lambda.
    logAttackShift: Math.log(attackMultiplier),
    // A weaker defence raises the *opponent's* lambda, hence the sign flip.
    logDefenceShift: -Math.log(defenceMultiplier),
    missing,
    unknown: false,
  }
}
