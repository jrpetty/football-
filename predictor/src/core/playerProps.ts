// ---------------------------------------------------------------------------
// Per-player match probabilities.
//
// Player rates are calibrated against the team's predicted lambda rather than
// used raw. If the model expects a side to score 1.2 goals but their players'
// season rates sum to 1.9 per 90, quoting those raw rates would contradict the
// match forecast. Scaling them to reconcile keeps the player numbers and the
// scoreline numbers telling the same story.
// ---------------------------------------------------------------------------

import { clamp, round } from './math.ts'
import type { PlayerRates } from './availability.ts'
import { playProbability } from './availability.ts'

export interface PlayerProp {
  id: number
  name: string
  team: string
  position: string
  /** Expected minutes on the day, after availability. */
  expectedMinutes: number
  /** Probability of featuring at all. */
  playProbability: number
  goal: number
  /** Probability of scoring two or more. */
  brace: number
  assist: number
  /** Probability of a goal or an assist. */
  involvement: number
  yellow: number
  red: number
  /** Only meaningful for goalkeepers and defenders. */
  cleanSheet: number
  /** True when the player is flagged out or heavily doubtful. */
  doubtful: boolean
  news: string
}

/**
 * Expected minutes for a player.
 *
 * Recent minute share is the base; availability scales it. A player at 50%
 * chance of playing is modelled as half their usual minutes rather than as a
 * coin flip on their full allocation, because that is what the expected value
 * of their contribution actually is.
 */
export function expectedMinutes(p: PlayerRates): number {
  const base = clamp(p.minuteShare, 0, 1) * 90
  return round(base * playProbability(p), 1)
}

/** P(at least one) for a Poisson count with the given rate. */
function atLeastOne(rate: number): number {
  return 1 - Math.exp(-Math.max(0, rate))
}

/** P(at least two) for a Poisson count. */
function atLeastTwo(rate: number): number {
  const r = Math.max(0, rate)
  return 1 - Math.exp(-r) - r * Math.exp(-r)
}

export interface PlayerPropsInput {
  squad: readonly PlayerRates[]
  /** The team's predicted goals for this fixture. */
  teamLambda: number
  /** Probability the team keeps a clean sheet. */
  cleanSheetProb: number
  /** Share of a team's goals that come with a recorded assist (league-wide). */
  assistRate?: number
}

/** Typical share of goals carrying an assist in the Premier League. */
export const DEFAULT_ASSIST_RATE = 0.74

/**
 * Build per-player probabilities for one team in one fixture.
 *
 * Only players with meaningful expected minutes are returned — quoting a
 * scoring probability for a player who will not be on the pitch is noise.
 */
export function playerProps(input: PlayerPropsInput): PlayerProp[] {
  const { squad, teamLambda, cleanSheetProb } = input
  const assistRate = input.assistRate ?? DEFAULT_ASSIST_RATE

  const withMinutes = squad
    .map((p) => ({ p, minutes: expectedMinutes(p) }))
    .filter((x) => x.minutes >= 15)

  // Raw expected goals from each player's own rate over their expected minutes.
  const rawGoals = withMinutes.map((x) => Math.max(0, x.p.xgi90) * (x.minutes / 90))
  const rawTotal = rawGoals.reduce((s, g) => s + g, 0)

  // xGI counts assists as well as goals, so the raw sum overstates goals;
  // reconcile the whole set to the team's predicted lambda.
  const scale = rawTotal > 0 ? teamLambda / rawTotal : 0

  const out: PlayerProp[] = []
  for (let i = 0; i < withMinutes.length; i++) {
    const { p, minutes } = withMinutes[i]!
    const involvementRate = rawGoals[i]! * scale
    // Split involvement into goals and assists. Forwards skew toward goals,
    // midfielders toward assists; keepers and defenders score rarely.
    const goalShare =
      p.position === 'FWD' ? 0.72 : p.position === 'MID' ? 0.55 : p.position === 'DEF' ? 0.45 : 0.1
    const goalRate = involvementRate * goalShare
    const assistRateForPlayer = involvementRate * (1 - goalShare) * assistRate

    const yellowRate = Math.max(0, p.yellow90) * (minutes / 90)
    const redRate = Math.max(0, p.red90) * (minutes / 90)

    out.push({
      id: p.id,
      name: p.name,
      team: p.team,
      position: p.position,
      expectedMinutes: minutes,
      playProbability: round(playProbability(p), 3),
      goal: round(atLeastOne(goalRate), 4),
      brace: round(atLeastTwo(goalRate), 4),
      assist: round(atLeastOne(assistRateForPlayer), 4),
      involvement: round(atLeastOne(goalRate + assistRateForPlayer), 4),
      yellow: round(atLeastOne(yellowRate), 4),
      red: round(atLeastOne(redRate), 4),
      // A clean sheet is a team event; credit it to players likely to be on
      // the pitch for it.
      cleanSheet:
        p.position === 'GK' || p.position === 'DEF'
          ? round(cleanSheetProb * clamp(minutes / 90, 0, 1), 4)
          : 0,
      doubtful: playProbability(p) < 0.9,
      news: p.news,
    })
  }

  out.sort((a, b) => b.involvement - a.involvement)
  return out
}

/** The most likely scorers across both teams, for the fixture summary. */
export function topScorers(home: readonly PlayerProp[], away: readonly PlayerProp[], n = 5): PlayerProp[] {
  return [...home, ...away].sort((a, b) => b.goal - a.goal).slice(0, n)
}

/** Players most at risk of a booking, for the card watch. */
export function topCardRisks(home: readonly PlayerProp[], away: readonly PlayerProp[], n = 5): PlayerProp[] {
  return [...home, ...away].sort((a, b) => b.yellow - a.yellow).slice(0, n)
}
