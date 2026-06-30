// ---------------------------------------------------------------------------
// Gaffer — domain model for soccer (association football) analysis
// ---------------------------------------------------------------------------
// Everything in the app is built on these types. They are intentionally
// explicit so the analytics layer, charts and pages all share one vocabulary.
// ---------------------------------------------------------------------------

/** Broad on-pitch role used for grouping and pitch placement. */
export type PositionGroup = 'GK' | 'DEF' | 'MID' | 'FWD'

/** Detailed position codes (a superset of the four groups). */
export type PositionCode =
  | 'GK'
  | 'RB' | 'RWB' | 'CB' | 'LB' | 'LWB'
  | 'CDM' | 'CM' | 'CAM' | 'RM' | 'LM'
  | 'RW' | 'LW' | 'CF' | 'ST'

export type Foot = 'Left' | 'Right' | 'Both'

export type PlayerStatus = 'Available' | 'Injured' | 'Suspended' | 'Doubtful'

/** The six FIFA-style attribute axes used for radar charts and ratings. */
export interface Attributes {
  pace: number
  shooting: number
  passing: number
  dribbling: number
  defending: number
  physical: number
}

export type AttributeKey = keyof Attributes

export interface Player {
  id: string
  name: string
  /** Shirt number. */
  number: number
  position: PositionCode
  positionGroup: PositionGroup
  /** Secondary positions the player can cover. */
  altPositions: PositionCode[]
  age: number
  heightCm: number
  weightKg: number
  foot: Foot
  nationality: string
  status: PlayerStatus
  /** Accent colour used for avatars / chart series. */
  color: string
  attributes: Attributes
  /** Coach's free-text scouting / development notes. */
  notes: string
  /** Market value in millions (purely illustrative). */
  marketValueM: number
  joinedYear: number
}

// ---------------------------------------------------------------------------
// Matches
// ---------------------------------------------------------------------------

export type Venue = 'Home' | 'Away'

export type MatchResult = 'W' | 'D' | 'L'

export type Competition = 'League' | 'Cup' | 'Friendly' | 'Continental'

export type EventType =
  | 'goal'
  | 'assist'
  | 'yellow'
  | 'red'
  | 'sub-on'
  | 'sub-off'
  | 'shot'
  | 'save'

export interface MatchEvent {
  id: string
  minute: number
  type: EventType
  playerId: string
  /** Optional related player (e.g. assist provider, sub partner). */
  relatedPlayerId?: string
  /** Pitch coordinate where the event happened, 0..100 on each axis. */
  x?: number
  y?: number
  /** For shots: did it find the net / target. */
  onTarget?: boolean
  note?: string
}

/** Per-player performance line for a single match. */
export interface PlayerMatchStat {
  playerId: string
  /** Position the player actually played in this match. */
  position: PositionCode
  minutes: number
  goals: number
  assists: number
  shots: number
  shotsOnTarget: number
  passes: number
  passesCompleted: number
  keyPasses: number
  tackles: number
  interceptions: number
  duelsWon: number
  duelsTotal: number
  /** Total distance covered, km. */
  distanceKm: number
  /** Expected goals contributed by this player in the match. */
  xg: number
  /** Coach / data rating out of 10. */
  rating: number
  yellowCards: number
  redCards: number
  /** Average position on the pitch, 0..100 on each axis (for heatmaps). */
  avgX: number
  avgY: number
  /** Sampled touch locations, 0..100 — used to render a heatmap. */
  touches: { x: number; y: number }[]
}

export interface LineupSlot {
  playerId: string
  position: PositionCode
  /** Pitch coordinate of the slot, 0..100 each axis (own half at low y). */
  x: number
  y: number
  isStarter: boolean
}

export interface Match {
  id: string
  date: string // ISO yyyy-mm-dd
  opponent: string
  venue: Venue
  competition: Competition
  formation: string // e.g. "4-3-3"
  goalsFor: number
  goalsAgainst: number
  /** Team-level possession percentage, 0..100. */
  possession: number
  xgFor: number
  xgAgainst: number
  shotsFor: number
  shotsAgainst: number
  cornersFor: number
  cornersAgainst: number
  lineup: LineupSlot[]
  events: MatchEvent[]
  playerStats: PlayerMatchStat[]
  /** Coach's post-match analysis notes. */
  notes: string
}

// ---------------------------------------------------------------------------
// Tactics
// ---------------------------------------------------------------------------

export interface FormationPreset {
  name: string // "4-3-3"
  description: string
  /** Slot positions on a 0..100 pitch, own goal at bottom (y small). */
  slots: { position: PositionCode; x: number; y: number }[]
}

/** A saved tactical setup (formation + assigned players). */
export interface Lineup {
  id: string
  name: string
  formation: string
  slots: LineupSlot[]
  notes: string
}

// ---------------------------------------------------------------------------
// Training
// ---------------------------------------------------------------------------

export type DrillCategory =
  | 'Warm-up'
  | 'Technical'
  | 'Possession'
  | 'Attacking'
  | 'Defending'
  | 'Set Pieces'
  | 'Physical'
  | 'Finishing'
  | 'Transition'

export type Intensity = 'Low' | 'Medium' | 'High'

export interface Drill {
  id: string
  name: string
  category: DrillCategory
  durationMin: number
  players: string // "8-12"
  intensity: Intensity
  description: string
  objectives: string[]
  equipment: string[]
  /** Attribute axes this drill is designed to develop. */
  focus: AttributeKey[]
}

export interface TrainingSession {
  id: string
  date: string
  title: string
  focus: string
  drillIds: string[]
  notes: string
}

// ---------------------------------------------------------------------------
// Video analysis
// ---------------------------------------------------------------------------

/** Where the footage comes from. */
export type VideoSource = 'youtube' | 'url' | 'file'

/** Analysis categories used to tag moments in a clip. */
export type TagCategory =
  | 'Goal'
  | 'Chance'
  | 'Assist'
  | 'Key Pass'
  | 'Build-up'
  | 'Press'
  | 'Turnover'
  | 'Defensive Error'
  | 'Set Piece'
  | 'Transition'
  | 'Duel'
  | 'Save'
  | 'Skill'
  | 'Other'

/** A timestamped, categorised moment within a video. */
export interface VideoTag {
  id: string
  /** Start time in seconds. */
  time: number
  /** Optional clip end time in seconds (for a window rather than a point). */
  endTime?: number
  category: TagCategory
  title: string
  note: string
  /** Player the moment is about (optional). */
  playerId?: string
  /** 'us' = our team, 'them' = opposition. */
  team: 'us' | 'them'
}

export interface VideoClip {
  id: string
  title: string
  source: VideoSource
  /**
   * For 'youtube': the video id or any YouTube URL.
   * For 'url': a direct video URL (mp4/webm).
   * For 'file': the file name (the blob URL only lives for the session).
   */
  url: string
  /** Linked match, if this is match footage. */
  matchId?: string
  opponent?: string
  date?: string
  /** Known duration in seconds, if available. */
  durationSec?: number
  tags: VideoTag[]
  notes: string
}

// ---------------------------------------------------------------------------
// Team / club
// ---------------------------------------------------------------------------

export interface TeamInfo {
  name: string
  season: string
  coach: string
  primaryColor: string
  secondaryColor: string
}

// ---------------------------------------------------------------------------
// Root persisted state
// ---------------------------------------------------------------------------

export interface AppData {
  team: TeamInfo
  players: Player[]
  matches: Match[]
  drills: Drill[]
  sessions: TrainingSession[]
  lineups: Lineup[]
  videos: VideoClip[]
  /** Schema version for forward-compatible migrations. */
  version: number
}
