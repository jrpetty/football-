/**
 * The Arena — head-to-head games between models.
 *
 * Shapes shared by the match engine, the tournament scheduler, the server and
 * the UI (which imports this file type-only, like src/core/types.ts).
 */
import type { ContestantSnapshot, ManualRequest, Rng, RunStatus, TranscriptEntry } from '../core/types.ts';

/** Seat index: 0 moves first (Red / White), 1 moves second (Yellow / Black). */
export type Side = 0 | 1;

export interface SideInfo {
  /** "White", "Red", … */
  name: string;
  /** Hex colour of this side's pieces on the board. */
  color: string;
}

export interface GameOutcome {
  /** Winning seat, or null for a draw. */
  winner: Side | null;
  /** Plain-English reason, e.g. "Four in a row", "Checkmate", "Threefold repetition". */
  reason: string;
}

/** Game-level options (stored in the tournament manifest, part of its fingerprint). */
export interface GameConfig {
  /** Hard cap on plies (half-moves); the game is then decided by `adjudicate`. */
  maxPlies: number;
  /** List every legal move in each prompt (also what lets the Random Baseline play). */
  listLegalMoves: boolean;
  /**
   * How a match is scored: 'points' (default: win 1, draw ½) or 'margin' (the sum of each game's
   * `margin`, e.g. poker chips; the higher total wins the match).
   */
  scoring?: 'points' | 'margin';
  [key: string]: unknown;
}

/** A game-specific option on the New tournament page (e.g. hands per match, motion). */
export interface ArenaOptionSpec {
  key: string;
  label: string;
  hint?: string;
  choices: Array<{ value: string; label: string; hint?: string }>;
  default: string;
}

/** Context the 'turns' engine passes to `ArenaGame.prompt`. */
export interface TurnPromptContext {
  config: GameConfig;
  strikes: [number, number];
  maxStrikes: number;
}

/** A judged game's rubric criterion (scored 1–10 per side by every judge). */
export interface RubricItem {
  key: string;
  label: string;
  /** What the judge should look for, in one sentence. */
  help: string;
}

/**
 * Judge step (generic; used by debate and courtroom). The engine blinds the
 * transcript ("Side A"/"Side B", random per judge, model names redacted),
 * asks every eligible judge for rubric scores and a winner, and decides by
 * majority.
 */
export interface JudgeSpec<S = unknown> {
  rubric: RubricItem[];
  /** Judge system prompt. */
  system: string;
  /** The blinded material for the judges. `sideA` is the seat shown as "Side A". Never include model names. */
  material(state: S, sideA: Side): string;
  /** Extra judging instructions, e.g. "the verdict turns on the use of the exhibits". */
  instructions: string;
  /** Per-judge token estimate for one game. */
  estimate: { inputTokens: number; outputTokens: number };
}

export type ParsedMove = { ok: true; move: string } | { ok: false; error: string };

/**
 * A pluggable two-seat, turn-based game. Every function is pure: `play`
 * returns a NEW state and never mutates its input, so the engine can keep a
 * snapshot of every position and replays are exact.
 *
 * Hidden-information games (poker) show each seat its own view through
 * `view(state, side)`; chance (the deck) is drawn once from the seeded rng in
 * `setup`, so the same seed deals the same cards to both colour-swapped games.
 */
export interface ArenaGame<S = unknown> {
  id: string;
  name: string;
  /** Bump on any change to rules, prompts or scoring (it is also part of the game hash). */
  version: string;
  /** One-liner for cards and overlays. */
  tagline: string;
  /** What the game tests, in two or three plain sentences. */
  description: string;
  sides: [SideInfo, SideInfo];
  /** Full rules, sent verbatim in every prompt. Never use backticks (they mark legal moves). */
  rules: string;
  /** How to write a move, e.g. 'the column number (1–7), e.g. "MOVE: 4"'. */
  moveHelp: string;
  defaults: GameConfig;
  /** Per-game token estimates used before any game has been measured. */
  estimate: { pliesPerGame: number; inputTokensPerMove: number; outputTokensPerMove: number };
  /** How the move cap is adjudicated, in one plain sentence (shown in the UI and the prompt). */
  capRule: string;

  setup(rng: Rng, config: GameConfig): S;
  toMove(state: S): Side;
  /** Canonical move ids. Must be non-empty while the game is not over (used for the random fallback). */
  legalMoves(state: S): string[];
  /** Turn the model's move text (after "MOVE:") into a canonical legal move, or explain why not. */
  parseMove(state: S, text: string): ParsedMove;
  play(state: S, move: string): S;
  /** null while the game continues. */
  outcome(state: S): GameOutcome | null;
  /** Decide a game that hit the move cap. */
  adjudicate(state: S): GameOutcome;
  /** Human-readable name of a move in this position (SAN for chess). Call BEFORE playing it. */
  label(state: S, move: string): string;
  /** Move history for the prompt, e.g. "1. e4 e5 2. Nf3". */
  formatHistory(labels: string[]): string;
  /** The position as text for the seat to move (may hide the opponent's private information). */
  view(state: S, side: Side): string;
  /** JSON the dashboard draws the board from. */
  snapshot(state: S): unknown;

  // ── Optional extensions (all backwards compatible) ─────────────────────────
  /** Source file under src/arena/games/ when it is not `<id>.ts` (several games in one module). */
  module?: string;
  /**
   * Which match engine plays the game: 'board' (match.ts, the default), 'turns' (turns.ts: the game
   * writes its own prompt, hidden information, custom answer keyword, e.g. poker) or 'debate'
   * (debate.ts: free-text speeches, then the judge step).
   */
  engine?: 'board' | 'turns' | 'debate';
  /** Allowed games per pairing (default [2, 4, 6]). */
  gamesPerMatchOptions?: number[];
  /** Sudden-death games make sense for this game (default true). */
  suddenDeath?: boolean;
  /** Game-specific options shown on the New tournament page. */
  options?: ArenaOptionSpec[];
  /** Apply the chosen options to a copy of the config; throw a plain-English Error when invalid. */
  configure?(config: GameConfig, options: Record<string, string>): GameConfig;
  /** Token estimate for a given config (e.g. more hands = more decisions). Falls back to `estimate`. */
  estimateFor?(config: GameConfig): ArenaGame['estimate'];
  /** 'turns' engine: the whole prompt for the seat to act (only what that seat may see). */
  prompt?(state: S, side: Side, ctx: TurnPromptContext): string;
  /** 'turns' engine: the answer keyword, e.g. "ACTION" (default "MOVE"). */
  answerKey?: string;
  /** 'turns' engine: the move played after two failed attempts (default: a seeded-random legal move). */
  fallbackMove?(state: S): string;
  /** False: strikes never lose the game (the fallback move is the penalty). Default true. */
  strikesLose?: boolean;
  /** Per-seat score of a finished game for 'margin' scoring (e.g. chips won). */
  margin?(state: S): [number, number];
  /** Unit of `margin`, e.g. "chips". */
  unit?: string;
  /** Judged games: the judge step (debate engine). */
  judge?: JudgeSpec<S>;
}

// ─────────────────────────────────────────────────────────────────────────────
// Games & moves
// ─────────────────────────────────────────────────────────────────────────────

export interface MoveAttempt {
  /** The model's reply (truncated for storage; the full text is in the transcript). */
  text: string;
  /** What followed "MOVE:" (null when the line was missing). */
  extracted: string | null;
  /** Why the attempt was rejected (absent when accepted). */
  error?: string;
  ms: number;
}

export interface ArenaMove {
  /** 1-based half-move number. */
  ply: number;
  side: Side;
  /** Canonical move id (UCI for chess, column for Connect Four). */
  move: string;
  /** Display form (SAN for chess). */
  label: string;
  attempts: MoveAttempt[];
  /** True when both attempts failed and a random legal move was played instead (a strike). */
  forfeit: boolean;
  /** Played by the harness before the models start (random opening of a sudden-death game). */
  opening?: boolean;
  /** Wall time the seat spent on this move (all attempts). */
  ms: number;
  costUsd: number;
  inputTokens: number;
  outputTokens: number;
  /** Board after the move. */
  snapshot: unknown;
  /** The model's one-line reason for the move ("REASON:" line), when given. */
  note?: string;
  /** 'verdict' = the judges' decision (judged games), not a player move. */
  kind?: 'verdict';
}

export interface SideMetrics {
  costUsd: number;
  inputTokens: number;
  outputTokens: number;
  reasoningTokens: number;
  apiCalls: number;
  retries: number;
  /** Total thinking time. */
  ms: number;
}

/** 'awaiting-judges': a judged game whose play is finished but no judge could decide it yet. */
export type GameStatus = 'ok' | 'error' | 'cancelled' | 'awaiting-judges';

export interface JudgeScores {
  [criterion: string]: number;
}

/** One judge's decision, already mapped back from "Side A/B" to seats. */
export interface JudgeVerdict {
  judgeId: string;
  judgeLabel: string;
  vendor: string;
  /** The seat this judge saw as "Side A" (randomised per judge). */
  sideA: Side;
  /** Winning seat, or null for a draw / no decision. */
  winner: Side | null;
  /** Rubric scores per seat (1–10 each). */
  scores?: [JudgeScores, JudgeScores];
  rationale: string;
  error?: string;
  costUsd: number;
  /** Judged by a person on the human judging screen. */
  human?: boolean;
}

export interface ArenaJudging {
  status: 'judged' | 'awaiting-human';
  verdicts: JudgeVerdict[];
  /** Majority winner (seat), or null for a draw. */
  winner: Side | null;
  /** Judge votes per seat. */
  votes: [number, number];
  /** "Unanimous decision", "Split decision", "Majority decision", "Draw", "Awaiting human judging". */
  decision: string;
  split: boolean;
  /** Why the game is waiting for a human (no eligible judges, all judges failed, …). */
  note?: string;
  /** Vendors kept off the panel because a debater comes from them. */
  excludedVendors: string[];
  costUsd: number;
  metrics: SideMetrics;
  /** Every judge prompt and reply (blinded), for auditing. */
  transcript: TranscriptEntry[];
}

export interface ArenaGameRecord {
  /** `${matchId}-g${gameNo}` */
  key: string;
  tournamentId: string;
  matchId: string;
  /** 1-based; games beyond `gamesPerMatch` are sudden-death tie-breakers. */
  gameNo: number;
  seed: number;
  /** Contestant id per seat: players[0] moves first. */
  players: [string, string];
  status: GameStatus;
  winner: Side | null;
  reason: string;
  moves: ArenaMove[];
  initial: unknown;
  /** Random moves played for each seat after two failed attempts. */
  strikes: [number, number];
  /** Rejected attempts (illegal or unreadable) per seat. */
  illegal: [number, number];
  metrics: [SideMetrics, SideMetrics];
  transcripts: [TranscriptEntry[], TranscriptEntry[]];
  error?: string;
  startedAt: string;
  finishedAt: string;
  /** 'margin' games: per-seat result (e.g. chips won; sums to zero in poker). */
  margin?: [number, number];
  /** Judged games: the panel's decision (its cost counts towards the spending cap). */
  judging?: ArenaJudging;
  /** This line re-states an earlier record of the same game (a human verdict was added); its cost was already counted. */
  amends?: boolean;
}

/** Record without transcripts and board snapshots, for lists. */
export type ArenaGameLite = Omit<ArenaGameRecord, 'transcripts' | 'moves'> & { plies: number; lastSnapshot: unknown };

// ─────────────────────────────────────────────────────────────────────────────
// Tournaments
// ─────────────────────────────────────────────────────────────────────────────

export type ArenaFormat = 'knockout' | 'round-robin';
export type ArenaSeeding = 'index' | 'manual';

export interface ArenaRequest {
  name?: string;
  game: string;
  contestantIds: string[];
  format?: ArenaFormat;
  /** 'index' = by current Gauntlet Index (combined core leaderboard), 'manual' = the order given. */
  seeding?: ArenaSeeding;
  /** Games per pairing, sides swapped each game: 2, 4 or 6. */
  gamesPerMatch?: number;
  /** Knockout only: extra sudden-death games when a match is level (default 2). */
  suddenDeath?: number;
  /** Rejected-move strikes that lose a game (default 3). */
  maxStrikes?: number;
  maxPlies?: number;
  listLegalMoves?: boolean;
  /** Games played at the same time (default 2). */
  concurrency?: number;
  /** Hard spending cap in USD. */
  maxCostUsd?: number;
  seed?: number;
  notes?: string;
  /** Game-specific options (see `ArenaGame.options`), e.g. { hands: '20' } or { motion: 'random' }. */
  options?: Record<string, string>;
}

export type MatchSource = { entrant: string } | { winnerOf: string } | { bye: true };

export interface MatchSpec {
  id: string;
  round: number;
  roundName: string;
  slot: number;
  a: MatchSource;
  b: MatchSource;
}

export interface ArenaEntrant extends ContestantSnapshot {
  /** 1 = top seed. */
  seed: number;
  /** Gauntlet Index used for seeding (null when unknown or seeded manually). */
  index: number | null;
  manual?: boolean;
  baseline?: boolean;
}

export interface ArenaSettings {
  format: ArenaFormat;
  seeding: ArenaSeeding;
  gamesPerMatch: number;
  suddenDeath: number;
  maxStrikes: number;
  concurrency: number;
  temperature: number;
  maxOutputTokens: number;
  maxCostUsd?: number;
  game: GameConfig;
  protocolVersion: string;
}

export interface TournamentManifest {
  id: string;
  name: string;
  status: RunStatus;
  createdAt: string;
  startedAt?: string;
  finishedAt?: string;
  harnessVersion: string;
  gitCommit?: string;
  node: string;
  platform: string;
  game: { id: string; name: string; version: string; hash: string };
  /** Hash of game code + prompt template + settings: identical fingerprints = identical conditions. */
  fingerprint: string;
  seed: number;
  entrants: ArenaEntrant[];
  settings: ArenaSettings;
  matches: MatchSpec[];
  notes?: string;
  error?: string;
}

export type MatchDecision = 'games' | 'margin' | 'sudden-death' | 'fewer illegal moves' | 'lower cost' | 'higher seed' | 'bye' | 'walkover';

export interface GameSlot {
  key: string;
  gameNo: number;
  seed: number;
  players: [string, string];
  suddenDeath: boolean;
  game?: ArenaGameLite;
}

export interface MatchState {
  id: string;
  round: number;
  roundName: string;
  slot: number;
  /** Contestant ids (null = still to be decided by an earlier match). */
  players: [string | null, string | null];
  status: 'waiting' | 'ready' | 'playing' | 'done' | 'bye';
  games: GameSlot[];
  /** Game points (win 1, draw ½). */
  score: [number, number];
  illegal: [number, number];
  cost: [number, number];
  winner: string | null;
  decidedBy?: MatchDecision;
  /** One-line result, e.g. "Claude wins 1½–½". */
  summary: string;
  /** Set for 'margin' games: `score` holds the margin totals (e.g. chips) in this unit. */
  unit?: string;
  /** Games waiting for (human) judges. */
  awaiting?: number;
}

export interface StandingRow {
  contestantId: string;
  seed: number;
  played: number;
  wins: number;
  draws: number;
  losses: number;
  points: number;
  illegal: number;
  costUsd: number;
  rank: number;
  /** 'margin' games: total margin (e.g. chips won); `points` then holds the same number. */
  margin?: number;
}

export interface TournamentState {
  matches: MatchState[];
  standings: StandingRow[];
  champion: string | null;
  runnerUp: string | null;
  complete: boolean;
  gamesDone: number;
  /** Planned games (sudden-death games are added as they become necessary). */
  gamesTotal: number;
  costUsd: number;
  /** Judged games waiting for a human verdict. */
  awaitingJudges?: number;
}

export interface TournamentListItem {
  id: string;
  name: string;
  status: RunStatus;
  createdAt: string;
  finishedAt?: string;
  game: TournamentManifest['game'];
  format: ArenaFormat;
  entrants: Array<{ id: string; label: string; color: string; seed: number }>;
  champion: string | null;
  gamesDone: number;
  gamesTotal: number;
  costUsd: number;
  fingerprint: string;
}

export interface TournamentDetail {
  manifest: TournamentManifest;
  state: TournamentState;
  games: ArenaGameLite[];
  active: boolean;
  /** Games in progress right now (live board). */
  live: LiveGame[];
}

export interface LiveGame {
  key: string;
  matchId: string;
  gameNo: number;
  players: [string, string];
  moves: ArenaMove[];
  initial: unknown;
  toMove: Side;
  thinking: string;
  strikes: [number, number];
  metrics: [SideMetrics, SideMetrics];
  startedAt: string;
  /** When the seat to move started thinking. */
  turnStartedAt: string;
  /** e.g. 'judging' while the judges deliberate (judged games). */
  phase?: string;
}

export interface ArenaEstimate {
  games: number;
  maxGames: number;
  moves: number;
  /** Central estimate (USD) and a conservative upper bound. */
  estCostUsd: number;
  estCostUsdHigh: number;
  perGame: Array<{ a: string; b: string; estCostUsd: number }>;
  perContestant: Array<{ contestantId: string; perMoveUsd: number; perGameUsd: number; basis: 'measured' | 'definition'; manual: boolean }>;
  fingerprint: string;
  warnings: string[];
  entrants: Array<{ id: string; seed: number; index: number | null }>;
  matches: MatchSpec[];
  /** Judged games: judge calls and their share of `estCostUsd`. */
  judgeCalls?: number;
  judgeCostUsd?: number;
  /** The judge panel (before per-match vendor exclusion). */
  judges?: Array<{ id: string; label: string; vendor: string }>;
}

export type ArenaEvent =
  | { type: 'tournament.status'; tournamentId: string; status: RunStatus; at: string; error?: string }
  | { type: 'tournament.progress'; tournamentId: string; gamesDone: number; gamesTotal: number; costUsd: number; at: string }
  | { type: 'game.started'; tournamentId: string; game: LiveGame; at: string }
  | { type: 'game.thinking'; tournamentId: string; key: string; side: Side; text: string; attempt: number }
  | { type: 'game.turn'; tournamentId: string; key: string; side: Side; at: string }
  | { type: 'game.phase'; tournamentId: string; key: string; phase: string; at: string }
  | { type: 'game.move'; tournamentId: string; key: string; move: ArenaMove; strikes: [number, number]; metrics: [SideMetrics, SideMetrics]; at: string }
  | { type: 'game.finished'; tournamentId: string; game: ArenaGameLite; at: string }
  | { type: 'match.finished'; tournamentId: string; match: MatchState; at: string }
  | { type: 'log'; tournamentId: string; level: 'info' | 'warn' | 'error'; message: string; at: string }
  | { type: 'manual.request'; tournamentId: string; request: ManualRequest }
  | { type: 'manual.resolved'; tournamentId: string; requestId: string };
