// Normalised shapes every source adapter must produce. Downstream code sees
// only these, never a raw CSV column, so adding a source (or switching the
// live API in for the mirror) cannot ripple past this boundary.

/** A completed or scheduled match, with canonical club codes. */
export interface NormMatch {
  season: string
  /** Gameweek/round where known. */
  round: number
  /** Epoch ms kickoff. */
  date: number
  home: string
  away: string
  /** null when not yet played. */
  homeGoals: number | null
  awayGoals: number | null
  /** Team-level xG, summed from player xG. null outside the xG era. */
  homeXg: number | null
  awayXg: number | null
  finished: boolean
  /** Source fixture id, for joining player rows. */
  fixtureId?: number
}

export type PositionCode = 'GK' | 'DEF' | 'MID' | 'FWD'

/** Season-to-date aggregate for one player. */
export interface NormPlayer {
  /** FPL element id, unique within a season. */
  id: number
  name: string
  fullName: string
  team: string
  position: PositionCode
  minutes: number
  starts: number
  goals: number
  assists: number
  yellowCards: number
  redCards: number
  saves: number
  cleanSheets: number
  xg: number
  xa: number
  xgConceded: number
  /** FPL availability flag: a/i/d/s/u. */
  status: string
  news: string
  /** Percent chance of featuring next round; null when unstated. */
  chanceNextRound: number | null
  /** Price in millions, a decent proxy for perceived quality. */
  cost: number
}

/** One player's line in one match. */
export interface NormPlayerGw {
  /** Fixture ids repeat across seasons (they run 1-380 every year), so a row
   *  is only identifiable with its season attached. */
  season: string
  element: number
  round: number
  fixtureId: number
  team: string
  opponent: string
  wasHome: boolean
  minutes: number
  starts: number
  goals: number
  assists: number
  yellowCards: number
  redCards: number
  saves: number
  goalsConceded: number
  xg: number
  xa: number
  xgConceded: number
  date: number
}

export interface NormSeason {
  season: string
  /** FPL team id -> canonical club code. */
  teamsById: Record<number, string>
  matches: NormMatch[]
  players: NormPlayer[]
  playerGws: NormPlayerGw[]
  /** True when this season's feed carries expected-goals columns. */
  hasXg: boolean
}
