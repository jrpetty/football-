/**
 * Grading Station API shapes (docs/API.md → "Grading Station"). Types only, so
 * the UI can import them without pulling in server code.
 */
import type { CaseResult, CaseResultLite, ResultStatus } from '../core/types.ts';
import type { TestExplainer } from '../core/explainers.ts';
import type { AiRole, GradingKind, GradingSpec, HumanRole } from './spec.ts';
import type { OfficialPolicy, OfficialSource } from './policy.ts';

export type { OfficialPolicy, OfficialSource };

/** Why a result is in the grading queue. */
export type GradingNeed = 'grade' | 'judge-failed' | 'arbitrate' | 'second-opinion' | 'review';

export interface QueueItem {
  runId: string;
  key: string;
  testId: string;
  testName: string;
  category: string;
  caseId: string;
  repeat: number;
  contestantId: string;
  status: ResultStatus;
  score: number | null;
  summary: string;
  kind: GradingKind;
  humanRole: HumanRole;
  aiRole: AiRole;
  need: GradingNeed;
  /** Still needs a person (or an AI grade) before it has a trustworthy score. */
  todo: boolean;
  humans: number;
  ais: number;
  disputes: number;
  official?: OfficialSource;
  hasReplay: boolean;
  artifacts: string[];
}

export interface GradingRunInfo {
  id: string;
  name: string;
  createdAt: string;
  status: string;
  results: number;
  todo: number;
  gradable: number;
}

export interface ArenaPending {
  id: string;
  name: string;
  game: string;
  gameName: string;
  pending: number;
}

export interface JudgeInfo {
  id: string;
  label: string;
  vendor: string;
  vision: boolean;
  hasKey: boolean;
}

/** The exact prompts of a prompt-test case (same shape as the registry's RenderedCase). */
export interface RenderedCaseView {
  caseId: string;
  system?: string;
  turns: string[];
  expected?: unknown;
  notes?: string;
  images?: Array<{ turn: number; file: string; path?: string }>;
}

export interface StationItem {
  result: CaseResult;
  spec: GradingSpec;
  rendered: RenderedCaseView | null;
  test: { id: string; name: string; category: string; kind: 'prompt' | 'program'; description: string; hook?: string } | null;
  contestant: { id: string; label: string; vendor: string; color: string; manual: boolean } | null;
  explainer: TestExplainer | null;
  program?: { id: string; name: string; scoring: string };
  policy: OfficialPolicy;
  official: { source?: OfficialSource; why?: string };
  /** Human vs AI agreement (0..1 scale). */
  agreement: { level: 'agree' | 'close' | 'disagree' | 'n/a'; gap: number | null };
  humanScore: number | null;
  aiScore: number | null;
  /** The AI panel the station would use for this result, or why it cannot. */
  aiPanel: { judges: JudgeInfo[]; ok: boolean; reason?: string };
}

export interface HumanGradeInput {
  runId: string;
  key: string;
  rater: string;
  /** Rubric points per criterion id. */
  criteria?: Record<string, number>;
  /** Requirement verdicts keyed "<criterion id>:<requirement id>". */
  requirements?: Record<string, 'met' | 'partial' | 'missed'>;
  /** judge-classify: the chosen label. */
  label?: string;
  /** Tests without rubric criteria (fallback): a direct 0..1 score. */
  score?: number;
  note?: string;
  blind?: boolean;
}

export interface AiEstimateItem {
  key: string;
  ok: boolean;
  reason?: string;
  judges: Array<JudgeInfo & { images: number; estUsd: number }>;
  estUsd: number;
}

export interface AiEstimate {
  items: AiEstimateItem[];
  /** Central estimate (USD). */
  totalUsd: number;
  /** Conservative upper bound (long reasoning). */
  totalUsdHigh: number;
  gradable: number;
}

export interface AiGradeOutcome {
  key: string;
  ok: boolean;
  error?: string;
  costUsd: number;
  result?: CaseResultLite;
}

export interface AiSummary {
  text: string;
  writerId: string;
  writerLabel: string;
  vendor: string;
  costUsd: number;
  at: string;
  /** Hash of the results it was written from; a different hash means the results changed since. */
  basis: string;
}

export interface RunSummaries {
  /** "contestantId|testId" → the template summary (≤ 30 words). */
  template: Record<string, string>;
  /** Cached AI-written summaries; `stale` when the results changed after it was written. */
  ai: Record<string, AiSummary & { stale: boolean }>;
}

export interface SummaryEstimate {
  pairs: Array<{ key: string; contestantId: string; testId: string; writer: JudgeInfo | null; estUsd: number; reason?: string; cached: boolean }>;
  totalUsd: number;
  totalUsdHigh: number;
}
