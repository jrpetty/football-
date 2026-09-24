/**
 * Channel tools — shared shapes for the public website, New Model Day, model
 * history and the viewer challenge. Used by the engine, the server and the UI
 * (type-only import).
 */
import type { Contestant, Leaderboard } from '../core/types.ts';

// ─────────────────────────────────────────────────────────────────────────────
// Site configuration (config/site.json, optional)
// ─────────────────────────────────────────────────────────────────────────────

export interface SiteConfig {
  /** Shown in the header and page titles. */
  channelName: string;
  /** One line under the channel name. */
  tagline: string;
  /** Logo: a path relative to the gauntlet folder (copied into the site) or an https:// URL. Empty = Gauntlet mark. */
  logo: string;
  /** Link to the YouTube channel (header button). */
  youtubeUrl: string;
  /** Accent colour (#RRGGBB) used for links, buttons and highlights. */
  accentColor: string;
  /** Suites published by default, in tab order. */
  suites: string[];
  /** Public address of the site once hosted (used in the footer and share links). Optional. */
  siteUrl: string;
  /** Google Form (or any) link where viewers submit challenge questions. Empty = no "Submit a question" page. */
  submissionFormUrl: string;
  /** Current viewer-challenge season, e.g. "2026-s1". */
  season: string;
  /** Footer small print. */
  footerNote: string;
}

// ─────────────────────────────────────────────────────────────────────────────
// Public website
// ─────────────────────────────────────────────────────────────────────────────

export interface PublishOptions {
  suites?: string[];
  /** Output folder (default: gauntlet/site). */
  outDir?: string;
  /** Also write <outDir>.zip next to the folder. */
  zip?: boolean;
}

export interface PublishResult {
  outDir: string;
  zipPath?: string;
  pages: string[];
  files: number;
  bytes: number;
  suites: Array<{ id: string; name: string; models: number; tests: number }>;
  /** Tests hidden from the site (held-out) or whose prompts were withheld. */
  hiddenTests: number;
  withheldPrompts: number;
  warnings: string[];
  generatedAt: string;
}

/** Public, sanitised model entry (no provider config, no notes). */
export interface PublicModel {
  id: string;
  label: string;
  vendor: string;
  color: string;
  family?: string;
  releaseDate?: string;
  tier?: string;
  model: string;
  manual: boolean;
  baseline: boolean;
  pricing: { inputPerM: number; outputPerM: number; cachedInputPerM?: number; verifiedAt: string | null; source?: string };
}

/** Public, sanitised test entry. Never includes expected answers, notes or held-out content. */
export interface PublicTest {
  id: string;
  name: string;
  category: string;
  difficulty: string;
  description: string;
  hook?: string;
  version: string;
  hash: string;
  kind: 'prompt' | 'program';
  cases: number;
  scoring: string;
  /** Example prompt (first case), only when publishing prompts is allowed. */
  examplePrompt?: string;
  exampleSystem?: string;
  /** True for held-out tests: listed anonymously ("Held-out test 1"), nothing else published. */
  heldOut: boolean;
  promptWithheld: boolean;
}

export interface PublicSuite {
  id: string;
  name: string;
  description: string;
  version: string;
  fingerprint: string;
  leaderboard: Leaderboard;
}

export interface PublicData {
  generatedAt: string;
  harnessVersion: string;
  protocolVersion: string;
  site: SiteConfig;
  suites: PublicSuite[];
  models: PublicModel[];
  tests: PublicTest[];
  history: HistoryData;
}

// ─────────────────────────────────────────────────────────────────────────────
// Model history
// ─────────────────────────────────────────────────────────────────────────────

export interface HistoryPoint {
  contestantId: string;
  label: string;
  vendor: string;
  color: string;
  family: string;
  tier: string | null;
  releaseDate: string | null;
  index: number | null;
  indexCi95: [number, number] | null;
  categoryScores: Record<string, number | null>;
  coverage: number;
}

export interface HistoryJump {
  family: string;
  from: { contestantId: string; label: string; releaseDate: string; value: number };
  to: { contestantId: string; label: string; releaseDate: string; value: number };
  /** Change in index points (or category points × 100). */
  delta: number;
  days: number;
}

export interface HistoryData {
  suiteId: string;
  /** "index" or a category id. */
  metric: string;
  /** `points`: every dated model; `line`: what the family line joins, in date order: its flagship models when it has two or more (else all), best model per release date. */
  families: Array<{ family: string; color: string; points: HistoryPoint[]; line: HistoryPoint[] }>;
  /** Models with results but no release date (shown in a side list; owner can fill the date in). */
  undated: HistoryPoint[];
  /** Models with a release date but no scored results on this suite yet. */
  noResults: HistoryPoint[];
  jumps: HistoryJump[];
  generatedAt: string;
}

// ─────────────────────────────────────────────────────────────────────────────
// New Model Day
// ─────────────────────────────────────────────────────────────────────────────

export interface NewModelInput {
  provider: string;
  model: string;
  label?: string;
  id?: string;
  vendor?: string;
  color?: string;
  family?: string;
  tier?: 'flagship' | 'mid' | 'small';
  releaseDate?: string;
  inputPerM?: number;
  outputPerM?: number;
  cachedInputPerM?: number;
  effort?: NonNullable<Contestant['options']>['effort'];
  /** True when the prices were checked on the provider's price page today. */
  pricesVerified?: boolean;
}

export interface NewModelPrepared {
  contestant: Contestant;
  created: boolean;
  /** true = the id is in the provider's model list, false = not listed, null = discovery unavailable. */
  discovered: boolean | null;
  discoverError?: string;
  suggestions: string[];
  warnings: string[];
}

export interface NewModelSuiteCost {
  suiteId: string;
  name: string;
  tests: number;
  cases: number;
  /** Subset of the engine's RunEstimate (see docs/API.md). */
  estimate: { jobs: number; calls: number; judgeCostUsd: number; estCostUsd: number; estCostUsdHigh: number; fingerprint: string; warnings: string[] };
}

export interface NewModelHeadline {
  contestantId: string;
  label: string;
  suiteId: string;
  suiteName: string;
  index: number | null;
  indexCi95: [number, number] | null;
  rank: number | null;
  of: number;
  /** Models it beat / that beat it (by rank). */
  above: Array<{ label: string; index: number | null }>;
  below: Array<{ label: string; index: number | null }>;
  bestCategory: { id: string; name: string; score: number } | null;
  worstCategory: { id: string; name: string; score: number } | null;
  costUsd: number;
  /** Tied with the model directly above (CIs overlap). */
  tiedWithAbove: boolean;
  headline: string;
  titles: string[];
}

// ─────────────────────────────────────────────────────────────────────────────
// Viewer challenge
// ─────────────────────────────────────────────────────────────────────────────

export type ChallengeAnswerType = 'exact' | 'number' | 'choice';
export type ChallengeStatus = 'pending' | 'approved' | 'rejected';

export interface ChallengeIssue {
  level: 'error' | 'warn';
  code: 'missing-question' | 'missing-answer' | 'too-long' | 'too-short' | 'answer-in-question' | 'question-in-answer' | 'bad-number' | 'bad-choice' | 'no-options' | 'duplicate' | 'near-duplicate' | 'bad-type';
  message: string;
}

export interface ChallengeItem {
  id: string;
  question: string;
  answer: string;
  answerType: ChallengeAnswerType;
  /** Accepted alternatives for exact answers. */
  alternatives?: string[];
  viewerName: string;
  viewerHandle: string;
  notes: string;
  submittedAt: string;
  status: ChallengeStatus;
  /** Credit the viewer on screen (they may prefer to stay anonymous). */
  credit: boolean;
  issues: ChallengeIssue[];
  reviewedAt?: string;
  /** Case id once written to the test file. */
  caseId?: string;
}

export interface ChallengeQueue {
  season: string;
  items: ChallengeItem[];
  updatedAt: string;
  /** Where approved items are written. */
  testFile: string;
  testId: string;
  writtenAt?: string;
  writtenVersion?: string;
}

export interface ChallengeImportResult {
  added: number;
  skipped: number;
  errors: string[];
  queue: ChallengeQueue;
}

export interface ChallengeWriteResult {
  file: string;
  testId: string;
  version: string;
  cases: number;
  hash: string;
  errors: string[];
}

export interface ChallengeSlide {
  itemId: string;
  caseId: string | null;
  question: string;
  answer: string;
  credit: string | null;
  /** Latest graded outcome per model on this case (empty until the test has been run). */
  outcomes: Array<{ contestantId: string; label: string; color: string; score: number | null; passed: boolean | null }>;
}
