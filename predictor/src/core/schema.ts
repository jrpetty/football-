// ---------------------------------------------------------------------------
// The shape of everything written to public/data and read by the browser.
//
// This is the contract between the weekly pipeline and the site. Versioning it
// means a future schema change fails loudly at load instead of rendering a
// half-empty page.
// ---------------------------------------------------------------------------

import type { Evidence } from './evidence.ts'
import type { UpsetAnalysis } from './upset.ts'
import type { LambdaTerm } from './predict.ts'
import type { PlayerProp } from './playerProps.ts'
import type { MissingPlayer } from './availability.ts'
import type { PredictedLineup } from './lineup.ts'
import type { PlayerRates } from './availability.ts'
import type { RestProfile } from './congestion.ts'
import type { ClubShape } from './formation.ts'
import type { RosterSnapshot, TransferRecord } from './transfers.ts'
import type { Scoreline } from './dixonColes.ts'
import type { CalibrationBin, MetricSummary, Probs } from './metrics.ts'

export const SCHEMA_VERSION = 1

/** A club's record at one venue: home or away. */
export interface VenueRecord {
  played: number
  won: number
  drawn: number
  lost: number
  goalsFor: number
  goalsAgainst: number
  /**
   * True when these figures come from a trailing window rather than this
   * season — which is the whole table before a ball is kicked, and still the
   * honest source for a club with three home matches played.
   */
  trailing: boolean
}

/** Everything the site knows about how a club's results split by venue. */
export interface TeamVenue {
  home: VenueRecord
  away: VenueRecord
  /**
   * Fitted extra scoring at home, log scale, over and above the league-wide
   * home advantage. Unlike the raw record above this is controlled for who the
   * opponents were, so it is what the forecast actually uses.
   */
  homeAttackDev: number
  /** Fitted extra suppression of visitors at home, log scale. */
  homeDefenceDev: number
}

export interface TeamSummary {
  code: string
  name: string
  short: string
  color: string
  promoted: boolean
  played: number
  won: number
  drawn: number
  lost: number
  goalsFor: number
  goalsAgainst: number
  points: number
  /** Blended attack rating, log scale. */
  attack: number
  /** Blended defence rating, log scale. */
  defence: number
  /** Rating uncertainty; high for promoted and thinly-played sides. */
  ratingSd: number
  /** Recent results, newest first. */
  form: string[]
  /** Home and away records, and the venue effect fitted from them. */
  venue: TeamVenue
}

export interface SeasonArtifact {
  schemaVersion: number
  season: string
  generatedAt: number
  /** Gameweek the site should open on. */
  currentGameweek: number
  /** Gameweeks that have at least one finished fixture. */
  completedGameweeks: number[]
  /** Every gameweek with fixtures. */
  allGameweeks: number[]
  teams: TeamSummary[]
  /** Model hyperparameters actually used, for the report card. */
  params: Record<string, number>
  /** Notes the UI shows about data currency and known limitations. */
  notes: string[]
}

export interface FixtureArtifact {
  id: number
  gameweek: number
  kickoff: number
  home: string
  away: string
  finished: boolean
  homeGoals: number | null
  awayGoals: number | null

  // --- prediction ---
  probs: Probs
  lambdaHome: number
  lambdaAway: number
  expectedGoalsHome: number
  expectedGoalsAway: number
  btts: number
  over25: number
  cleanSheetHome: number
  cleanSheetAway: number
  topScorelines: Scoreline[]
  modalScore: { home: Scoreline; draw: Scoreline; away: Scoreline }
  dispersion: number
  rho: number
  confidence: number
  terms: LambdaTerm[]

  // --- reasoning ---
  evidence: Evidence[]
  upset: UpsetAnalysis

  // --- players ---
  homePlayers: PlayerProp[]
  awayPlayers: PlayerProp[]
  /** Players flagged out or heavily doubtful, with the value they carry. */
  homeMissing: MissingPlayer[]
  awayMissing: MissingPlayer[]
  /**
   * Predicted starting elevens. Confirmed team sheets only appear about an
   * hour before kickoff and this publishes days ahead, so these are the sides
   * the model expects — the same expected-minutes judgement its scoring rates
   * already rest on, made visible.
   */
  homeLineup: PredictedLineup
  awayLineup: PredictedLineup

  /**
   * Everything needed to re-run this fixture in the browser.
   *
   * The model core is deliberately free of Node and DOM dependencies, so the
   * identical code that produced these numbers can recompute them client-side
   * when a reader edits a squad. Shipping the inputs — rather than trying to
   * approximate the result — is what makes "remove this player and see what
   * happens" show the model's real answer instead of a plausible-looking
   * interpolation.
   */
  recompute: RecomputeInputs

  /** Players one booking from a suspension, either side. */
  cardWatch: CardWatchEntry[]

  /** Set once the match is played and scored. */
  result?: {
    outcome: 'home' | 'draw' | 'away'
    correctOutcome: boolean
    correctScore: boolean
    rps: number
    logLoss: number
    brier: number
  }
}

/** One side's non-squad inputs to a prediction. */
export interface RecomputeSide {
  attack: number
  defence: number
  ratingSd: number
  matchesPlayed: number
  promoted: boolean
  rest: RestProfile
  shape: ClubShape
}

export interface RecomputeInputs {
  intercept: number
  homeAdvantage: number
  /**
   * The host club's own venue deviations, fitted from its home record. Only
   * the home side's matter, so they travel as two scalars rather than a map.
   * Carried for the same reason as `availabilityStrength` below: the browser
   * has to reproduce the published lambdas exactly before it can show a reader
   * what their edit changed.
   */
  homeAttackDev: number
  homeDefenceDev: number
  rho: number
  /**
   * The availability damping the pipeline used. Carried rather than defaulted
   * so the browser recomputes with the identical setting — without it, tuning
   * this parameter would silently make every what-if disagree with the
   * published forecast it is shown against.
   */
  availabilityStrength: number
  home: RecomputeSide
  away: RecomputeSide
}

/**
 * Per-club squad rates, shared by every fixture rather than duplicated into
 * each one — the same twenty squads back all ten matches in a gameweek.
 */
export interface SquadsArtifact {
  schemaVersion: number
  season: string
  generatedAt: number
  /** Club code -> that club's rated players. */
  squads: Record<string, PlayerRates[]>
}

/** Squad movement, diffed between runs. */
export interface TransfersArtifact {
  schemaVersion: number
  season: string
  generatedAt: number
  /** Where everyone was at this run, for the next run to diff against. */
  roster: RosterSnapshot
  /** Most recent first. */
  records: TransferRecord[]
}

export interface CardWatchEntry {
  playerId: number
  name: string
  team: string
  yellows: number
  yellowsToBan: number
  banMatches: number
  bookingProbability: number
  banProbability: number
}

export interface GameweekArtifact {
  schemaVersion: number
  season: string
  gameweek: number
  generatedAt: number
  /** Set when every fixture has been played and scored. */
  scoredAt?: number
  fixtures: FixtureArtifact[]
  /** Metrics for this gameweek, once played. */
  metrics?: MetricSummary
}

export interface PlayerArtifact {
  id: number
  name: string
  fullName: string
  team: string
  position: string
  minutes: number
  starts: number
  goals: number
  assists: number
  /** This season's bookings — the count that drives suspensions. */
  yellowCards: number
  redCards: number
  /** Last season's totals, kept for context early in a campaign. */
  priorYellowCards: number
  priorRedCards: number
  xg: number
  xa: number
  xgi90: number
  yellow90: number
  status: string
  news: string
  chanceNextRound: number | null
  cost: number
  /** Suspension tracking. */
  yellowsToBan: number | null
  onBrink: boolean
  /** ISO date he joined this club, per the feed. */
  joinedAt: string | null
  /**
   * Share of his club's attacking value, 0-1 — minutes weighted by expected
   * goal involvement. Without it an availability list sorts a fringe forward
   * above a first-choice centre-half, and the absences that actually move a
   * forecast are lost among the ones that do not.
   */
  impact: number
}

export interface PlayersArtifact {
  schemaVersion: number
  season: string
  generatedAt: number
  /**
   * True when the goals/assists/minutes shown are carried over from last
   * season because this one has not produced enough matches yet. Card counts
   * are never carried over — those reset every season.
   */
  statsFromPriorSeason: boolean
  players: PlayerArtifact[]
}

/** One immutable record of a prediction made before kickoff. */
export interface LedgerEntry {
  season: string
  gameweek: number
  fixtureId: number
  home: string
  away: string
  kickoff: number
  /** When this forecast was first recorded. */
  predictedAt: number
  /**
   * When it was last revised. A forecast stays open to revision until kickoff
   * — the model refits every week, and freezing the first version would score
   * a model that no longer exists. It is sealed the moment the match starts,
   * which is what makes the record honest; `revisions` shows how often it
   * moved so a reader can see that it was.
   */
  updatedAt?: number
  revisions?: number
  probs: Probs
  lambdaHome: number
  lambdaAway: number
  predictedScore: { home: number; away: number }
  /** Filled in after the match. */
  actual?: {
    homeGoals: number
    awayGoals: number
    outcome: 'home' | 'draw' | 'away'
    rps: number
    logLoss: number
    brier: number
    correctOutcome: boolean
    correctScore: boolean
  }
}

export interface LedgerArtifact {
  schemaVersion: number
  generatedAt: number
  entries: LedgerEntry[]
}

export interface AccuracyArtifact {
  schemaVersion: number
  generatedAt: number
  /** All scored predictions, overall. */
  overall: MetricSummary
  /** Baseline for comparison: fixed league-average probabilities. */
  baseline: MetricSummary
  /** Per-gameweek history, for the trend chart. */
  byGameweek: { gameweek: number; season: string; metrics: MetricSummary }[]
  calibration: CalibrationBin[]
  expectedCalibrationError: number
  /** Best and worst calls, for the report card. */
  bestCalls: LedgerEntry[]
  worstCalls: LedgerEntry[]
}
