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
} from '../../src/core/types.ts';

export type * from '../../src/core/types.ts';

export interface Settings {
  judges: string[];
  defaultRepeats: number;
  defaultConcurrency: number;
  temperature: number;
  defaultMaxOutputTokens: number;
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
  source: 'builtin' | 'custom';
  file: string;
  /** Per case. */
  estimate: { inputTokens: number; outputTokens: number; calls: number };
}

export interface RenderedCase {
  caseId: string;
  system?: string;
  turns: string[];
  expected?: unknown;
  notes?: string;
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

export interface EstimateResult {
  jobs: number;
  calls: number;
  perContestant: Array<{ contestantId: string; jobs: number; estCostUsd: number }>;
  estCostUsd: number;
  fingerprint: string;
  warnings: string[];
}

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

export interface ReviewItem {
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
