/**
 * UI-side view of the Gauntlet contracts. Core shapes come straight from
 * src/core/types.ts (type-only); API envelope shapes documented in docs/API.md
 * are declared here.
 */
import type {
  CaseMetrics,
  CaseResultLite,
  CategoryInfo,
  Difficulty,
  Leaderboard,
  ProviderConfig,
  RunManifest,
  Suite,
  TestDefinition,
  TokenUsage,
  ResultStatus,
  ScoreDetail,
  TranscriptEntry,
  ArtifactRef,
} from '../../src/core/types.ts';

export type * from '../../src/core/types.ts';
export type * from '../../src/media/types.ts';

export interface Settings {
  judges: string[];
  defaultRepeats: number;
  defaultConcurrency: number;
  temperature: number;
  defaultMaxOutputTokens: number;
  defaultTimeLimitSec?: number;
  maxRetries?: number;
  judgeExcludeSameVendor?: boolean;
}

export interface ProgramInfo {
  id: string;
  name: string;
  description: string;
  scoring: string;
}

export type ProviderView = ProviderConfig & { hasKey: boolean };

export interface Meta {
  harnessVersion: string;
  protocolVersion: string;
  categories: CategoryInfo[];
  providers: ProviderView[];
  settings: Settings;
  programs: ProgramInfo[];
  browserChecks: boolean;
}

export interface TestSummary {
  id: string;
  version: string;
  hash: string;
  name: string;
  category: string;
  kind: 'prompt' | 'program';
  difficulty: Difficulty;
  description: string;
  hook?: string;
  tags: string[];
  caseCount: number;
  /** e.g. 'exact', 'code-js', or 'program:survival-island' */
  scorerType: string;
  /** private = held-out tests in git-ignored tests/private/ (never published). */
  source: 'builtin' | 'custom' | 'private';
  file: string;
  /** Per case. */
  estimate: { inputTokens: number; outputTokens: number; calls: number };
  /** Number of cases that show the model an image (vision tests). */
  imageCases?: number;
}

export interface RenderedCase {
  caseId: string;
  system?: string;
  turns: string[];
  expected?: unknown;
  notes?: string;
  /** Images shown with the given (0-based) turn; `path` is relative to the tests folder. */
  images?: Array<{ turn: number; file: string; path?: string }>;
}

export interface TestDetail {
  definition: TestDefinition;
  summary: TestSummary;
  rendered: RenderedCase[];
  program?: ProgramInfo;
}

export interface ValidateResult {
  ok: boolean;
  errors: string[];
  hash?: string;
}

export type SuiteView = Suite & { fingerprint: string; testCount: number };

export type EstimateBasis = 'measured' | 'measured-other-models' | 'definition';

export interface RunEstimate {
  jobs: number;
  calls: number;
  perContestant: Array<{ contestantId: string; jobs: number; estCostUsd: number; estCostUsdHigh: number; manual: boolean }>;
  perTest: Array<{
    testId: string;
    name: string;
    category: string;
    cases: number;
    /** USD for all cases × repeats, keyed by contestant id. */
    perContestant: Record<string, number>;
    judgeUsd: number;
    basis: EstimateBasis;
  }>;
  judgeCostUsd: number;
  /** Central estimate. */
  estCostUsd: number;
  /** Conservative upper bound. */
  estCostUsdHigh: number;
  fingerprint: string;
  warnings: string[];
}

/** @deprecated alias kept for older call sites. */
export type EstimateResult = RunEstimate;

export interface RunProgress {
  completed: number;
  total: number;
  costUsd: number;
}

export interface RunDetail {
  manifest: RunManifest;
  leaderboard: Leaderboard;
  results: CaseResultLite[];
  progress: RunProgress;
  active: boolean;
}

export interface PingResult {
  ok: boolean;
  text?: string;
  totalMs?: number;
  ttftMs?: number | null;
  usage?: TokenUsage;
  costUsd?: number;
  error?: string;
}

export type ReviewReason = 'human-scored' | 'judge-disagreement' | 'second-opinion';

export interface ReviewItem {
  reason?: ReviewReason;
  runId: string;
  key: string;
  testId: string;
  caseId: string;
  contestantId: string;
  status: ResultStatus;
  score: number | null;
  humanScores?: Array<{ rater: string; score: number; at: string; note?: string }>;
}

export interface ReviewScoreRequest {
  runId: string;
  key: string;
  score: number;
  rater: string;
  note?: string;
}

/** Shape used by the Live Arena for finished cases. */
export interface FinishedCase {
  key: string;
  testId: string;
  caseId: string;
  repeat: number;
  status: ResultStatus;
  score: number | null;
  summary: string;
  metrics?: CaseMetrics;
}

export interface ManualReply {
  text: string;
  inputTokens?: number;
  outputTokens?: number;
  reasoningTokens?: number;
  costUsd?: number;
}

export interface GradeRequest {
  testId: string;
  caseId: string;
  response: string;
  vendor?: string;
  judgeIds?: string[];
}

export interface GradeResult {
  outcome: { score: number | null; passed: boolean | null; summary: string; detail: ScoreDetail; pendingHuman?: boolean };
  rendered: RenderedCase;
  judgeCostUsd: number;
  judgeTranscript: TranscriptEntry[];
  artifacts: ArtifactRef[];
  gradeId: string;
}
