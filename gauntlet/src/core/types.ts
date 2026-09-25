/**
 * Gauntlet — core type contracts.
 *
 * Everything that crosses a module boundary (test definitions, model calls,
 * results, API payloads) is defined here so the engine, the programs, the
 * server and the UI all agree on one shape.
 */
import type { SimFrame, SimWorld } from './sim-replay.ts';
export type * from './sim-replay.ts';

// ─────────────────────────────────────────────────────────────────────────────
// Categories
// ─────────────────────────────────────────────────────────────────────────────

export interface CategoryInfo {
  id: string;
  name: string;
  description: string;
  /** Hex colour used consistently for this category across the UI. */
  color: string;
  /** Default weight of the category in the Gauntlet Index. */
  weight: number;
}

// ─────────────────────────────────────────────────────────────────────────────
// Models (providers + contestants)
// ─────────────────────────────────────────────────────────────────────────────

export type ProviderType = 'anthropic' | 'openai-compatible' | 'gemini' | 'mock' | 'manual';

export interface ProviderConfig {
  id: string;
  type: ProviderType;
  label: string;
  /** Base URL for openai-compatible / gemini providers. */
  baseUrl?: string;
  /** Name of the environment variable holding the API key (null = no key needed). */
  apiKeyEnv: string | null;
  /** Max concurrent in-flight requests to this provider across a run. */
  maxConcurrency?: number;
  /** Extra HTTP headers sent with every request (e.g. OpenRouter attribution). */
  headers?: Record<string, string>;
  /** openai-compatible only: name of the output-token limit field (default: max_completion_tokens for api.openai.com, max_tokens elsewhere). */
  maxTokensParam?: 'max_tokens' | 'max_completion_tokens';
}

export interface Pricing {
  /** USD per 1M input tokens (uncached). */
  inputPerM: number;
  /** USD per 1M output tokens (includes reasoning/thinking tokens). */
  outputPerM: number;
  /** USD per 1M cached input tokens (cache reads). Defaults to inputPerM. */
  cachedInputPerM?: number;
  /** USD per 1M cache-write tokens. Defaults to inputPerM. */
  cacheWritePerM?: number;
  /** Where the price came from. */
  source?: string;
  /** ISO date the price was last verified by a human. */
  verifiedAt?: string | null;
}

export interface ContestantOptions {
  /** Reasoning / thinking effort, mapped per provider (Anthropic effort, OpenAI reasoning_effort, Gemini thinkingLevel). */
  effort?: 'none' | 'minimal' | 'low' | 'medium' | 'high' | 'xhigh' | 'max';
  /** Temperature, only sent when supportsTemperature is true. */
  temperature?: number;
  /** Whether the model accepts a temperature parameter. */
  supportsTemperature?: boolean;
  /** Hard cap on output tokens per call (overrides test value if lower). */
  maxOutputTokensCap?: number;
  /** Provider-specific request fields deep-merged into every request body. */
  extraBody?: Record<string, unknown>;
}

/** A "contestant" is one model + one fixed configuration. */
export interface Contestant {
  id: string;
  label: string;
  vendor: string;
  provider: string;
  /** Model identifier sent to the provider API. */
  model: string;
  color: string;
  enabled: boolean;
  pricing: Pricing;
  options?: ContestantOptions;
  contextWindow?: number;
  notes?: string;
  /** Accepts image input (vision tests). Missing = unknown, treated as text-only for API models. */
  vision?: boolean;
  /** Model family for the History page, e.g. "Claude", "GPT", "Gemini" (optional). */
  family?: string;
  /** Public release date (YYYY-MM-DD) for the History page (optional). */
  releaseDate?: string;
  /** Size tier within its family (optional). */
  tier?: 'flagship' | 'mid' | 'small';
}

/** Contestant plus derived runtime info (never includes secrets). */
export interface ContestantView extends Contestant {
  configHash: string;
  hasKey: boolean;
  providerLabel: string;
  providerType: ProviderType;
}

// ─────────────────────────────────────────────────────────────────────────────
// Model calls
// ─────────────────────────────────────────────────────────────────────────────

/** An image attached to a user message (vision tests). */
export interface ChatImage {
  /** Display / file name, e.g. "chart-01.png". */
  name: string;
  mediaType: 'image/png' | 'image/jpeg';
  /** Base64 bytes. Present when sending; stripped from stored transcripts to keep results small. */
  data?: string;
  sha256?: string;
  bytes?: number;
  width?: number;
  height?: number;
  /** Path relative to the tests folder when the image comes from a test file (lets the UI show it). */
  path?: string;
}

export interface ChatMessage {
  role: 'user' | 'assistant';
  content: string;
  /** Images sent with this (user) message, before its text. */
  images?: ChatImage[];
}

export interface CompletionRequest {
  system?: string;
  messages: ChatMessage[];
  maxOutputTokens: number;
  /** Harness default temperature; adapters drop it when unsupported. */
  temperature?: number;
  signal?: AbortSignal;
  /** Streaming text callback (visible text only, never reasoning). */
  onDelta?: (text: string) => void;
  /** Where this call comes from (used by the manual copy & paste provider to label requests). */
  callContext?: { runId: string; key: string; testId: string; testName: string; caseId: string; label: string };
}

export interface TokenUsage {
  inputTokens: number;
  outputTokens: number;
  /** Reasoning / thinking tokens (already included in outputTokens when the provider bills them as output). */
  reasoningTokens: number;
  cachedInputTokens: number;
  cacheWriteTokens: number;
}

export type StopReason = 'end' | 'max_tokens' | 'refusal' | 'content_filter' | 'other';

export interface CompletionResult {
  text: string;
  usage: TokenUsage;
  /** Epoch ms when the request was sent. */
  startedAt: number;
  /** ms from request start to first streamed token (visible or reasoning). */
  ttftMs: number | null;
  /** ms from request start to completion. */
  totalMs: number;
  stopReason: StopReason;
  rawStopReason: string;
  /** Model name reported back by the API. */
  servedModel: string;
  requestId?: string;
  retries: number;
  /** Explicit cost (manual entries where the user knows the real cost). Overrides pricing × usage. */
  costUsd?: number;
  /** True when the reply was pasted in by a human (manual contestant). Token counts may be estimates. */
  manual?: boolean;
}

// ─────────────────────────────────────────────────────────────────────────────
// Test definitions
// ─────────────────────────────────────────────────────────────────────────────

export type Difficulty = 'easy' | 'medium' | 'hard' | 'extreme';

export interface TestBase {
  /** Globally unique, stable id, e.g. "reasoning.river-crossing". */
  id: string;
  /** Semantic version. Bump whenever prompts, cases, answers or scoring change. */
  version: string;
  name: string;
  category: string;
  /** What the test measures and why it separates models. */
  description: string;
  difficulty: Difficulty;
  tags?: string[];
  /** One-line "hook" for broadcast / YouTube overlays. */
  hook?: string;
  /** Max output tokens per model call (default 16000). */
  maxOutputTokens?: number;
  /** Wall-clock limit per case in seconds (default 600). */
  timeLimitSec?: number;
  /** Rough per-case token estimate used for pre-run cost estimates. */
  estimate?: { inputTokens: number; outputTokens: number; calls?: number };
  author?: string;
  createdAt?: string;
  /**
   * Public website: false hides this test's example prompt (the test is still listed). Held-out tests in
   * tests/private/ are never published. Not part of the test hash, so toggling it keeps old results valid.
   */
  publishPrompts?: boolean;
}

export type NormalizeMode = 'none' | 'trim' | 'lower' | 'alnum';

export type Constraint =
  | { check: 'word_count'; min?: number; max?: number }
  | { check: 'sentence_count'; min?: number; max?: number }
  | { check: 'paragraph_count'; min?: number; max?: number }
  | { check: 'line_count'; min?: number; max?: number }
  | { check: 'bullet_count'; min?: number; max?: number }
  | { check: 'include'; text: string; caseSensitive?: boolean; min?: number; max?: number }
  | { check: 'exclude'; text: string; caseSensitive?: boolean }
  | { check: 'no_letter'; letter: string }
  | { check: 'starts_with'; text: string; caseSensitive?: boolean }
  | { check: 'ends_with'; text: string; caseSensitive?: boolean }
  | { check: 'all_lowercase' }
  | { check: 'all_uppercase' }
  | { check: 'no_commas' }
  | { check: 'json' }
  | { check: 'json_keys'; keys: string[] }
  | { check: 'regex'; pattern: string; flags?: string; shouldMatch?: boolean }
  | { check: 'max_word_length'; max: number }
  | { check: 'acrostic'; word: string }
  | { check: 'each_line_starts_with'; text: string }
  | { check: 'title_case_lines' };

export interface JudgeLabel {
  id: string;
  description: string;
  score: number;
}

export type ArtifactCheck =
  | { check: 'parses' }
  | { check: 'contains'; text: string }
  | { check: 'max_bytes'; bytes: number }
  | { check: 'no_external_requests' }
  | { check: 'runs_without_errors' }
  | { check: 'has_canvas_or_svg' }
  | { check: 'responds_to_input' };

export type ScorerSpec =
  /** case.expected: string | string[] (any accepted). Uses the FINAL ANSWER line. */
  | { type: 'exact'; normalize?: NormalizeMode }
  /** case.expected: number. Uses the FINAL ANSWER line. */
  | { type: 'number'; tolerance?: number; relative?: boolean }
  /** case.expected: "A" | "B" | ... Uses the FINAL ANSWER line. */
  | { type: 'choice' }
  /** case.expected (or pattern) is a regex tested against the FINAL ANSWER line (or full text if fullText). */
  | { type: 'regex'; pattern?: string; flags?: string; fullText?: boolean }
  /** Keyword presence over the full response. Fields may also come from case.expected. */
  | { type: 'contains'; all?: string[]; any?: string[]; none?: string[]; caseSensitive?: boolean }
  /** case.expected: Constraint[]. Partial credit = fraction of constraints satisfied. */
  | { type: 'constraints'; allOrNothing?: boolean }
  /** case.expected: object. Partial credit = fraction of expected leaf fields matched. */
  | { type: 'json'; unorderedArrays?: boolean; numberTolerance?: number; allOrNothing?: boolean; aliases?: Record<string, string> }
  /** case.expected: { functionName, tests: [{ args, expected }] }. Generated code is executed in a sandbox. */
  | { type: 'code-js'; timeoutMs?: number }
  /** LLM-judge (or judge panel) grading against a rubric; case.expected is an optional reference answer. */
  | { type: 'judge'; rubric: string; passThreshold?: number }
  /** LLM-judge classifies the response into one of the labels; each label maps to a score. */
  | { type: 'judge-classify'; instructions: string; labels: JudgeLabel[] }
  /** Extracts an HTML/SVG artifact, runs automated checks, optionally judges it. */
  | { type: 'artifact'; format: 'html' | 'svg'; checks?: ArtifactCheck[]; rubric?: string; judgeWeight?: number }
  /** Scored by humans in the Blind Review screen. */
  | { type: 'human'; rubric: string };

export interface PromptTestCase {
  id: string;
  /** User message. For multi-turn cases use `turns` instead. */
  prompt?: string;
  /** Sequential user turns; the model's replies are kept in the conversation. The final reply is scored. */
  turns?: string[];
  expected?: unknown;
  /** Per-case scorer override. */
  scorer?: ScorerSpec;
  weight?: number;
  /** Auditor notes: how the expected answer was derived, sources, etc. Never sent to the model. */
  notes?: string;
  /**
   * PNG/JPEG files shown to the model (vision tests), relative to the test JSON's folder
   * (e.g. "images/chart-01.png"). Attached to the first user turn unless `turn` (0-based) says otherwise.
   * The image bytes are part of the test hash.
   */
  images?: Array<string | { file: string; turn?: number }>;
  /** Answer-time limit for this case in seconds (overrides the test's answerWithinSec). Shown to the model and enforced per model call. */
  answerWithinSec?: number;
  /** The tempting wrong answer, for presentations ("Can It Be Fooled?" slides). Never sent to the model. */
  lure?: string;
  /** Human-readable correct answer for presentations when `expected` is not display-friendly (e.g. a regex). Never sent to the model. */
  displayAnswer?: string;
}

export interface PromptTest extends TestBase {
  kind: 'prompt';
  system?: string;
  /** Text prepended (with a blank line) to every case prompt / first turn. */
  preamble?: string;
  scorer: ScorerSpec;
  cases: PromptTestCase[];
  /**
   * Time pressure: every case must be answered within this many seconds. The limit is stated in the
   * prompt and enforced per model call (queueing and retry back-off excluded); a late reply scores 0
   * with status "timeout" ("Out of time"). Not enforced for manual (copy & paste) contestants.
   */
  answerWithinSec?: number;
}

export interface ProgramTest extends TestBase {
  kind: 'program';
  /** Registered program id (see src/programs/index.ts). */
  program: string;
  config?: Record<string, unknown>;
  /** Each seed is one case; identical seeds produce identical worlds for every model. */
  seeds: number[];
}

export type TestDefinition = PromptTest | ProgramTest;

export interface Suite {
  id: string;
  version: string;
  name: string;
  description: string;
  /**
   * Test ids with optional weight (default 1) and an optional subset of case ids
   * (or "seed-<n>" for programs) — e.g. a cheap "quick" suite. Use [{ "id": "*" }] for every test.
   */
  tests: Array<{ id: string; weight?: number; cases?: string[] }>;
  /** Optional per-category weight overrides for the Gauntlet Index. */
  categoryWeights?: Record<string, number>;
  /** Default repeats per case when running this suite. */
  repeats?: number;
  /** Public website: false hides the example prompts of every test in this suite. */
  publishPrompts?: boolean;
}

// ─────────────────────────────────────────────────────────────────────────────
// Programs (environments / multi-step pipelines)
// ─────────────────────────────────────────────────────────────────────────────

export interface Rng {
  /** Float in [0, 1). */
  next(): number;
  /** Integer in [min, max] inclusive. */
  int(min: number, max: number): number;
  pick<T>(items: readonly T[]): T;
  shuffle<T>(items: readonly T[]): T[];
  chance(p: number): boolean;
  /** Derive an independent child stream (stable for a given label). */
  fork(label: string): Rng;
}

export interface ModelReply {
  text: string;
  /** Normalised stop reason of this call. */
  stopReason: StopReason;
  totalMs: number;
  ttftMs: number | null;
  outputTokens: number;
}

export interface ChatSession {
  /** Send a user message and get the assistant reply; history is kept. */
  send(userText: string, opts?: { maxOutputTokens?: number; label?: string; images?: ChatImage[] }): Promise<ModelReply>;
  /** Current conversation history. */
  readonly history: readonly ChatMessage[];
}

export interface ModelHandle {
  /** Stateless call with an explicit conversation. Usage/timing/transcript are recorded automatically. */
  complete(req: { system?: string; messages: ChatMessage[]; maxOutputTokens?: number; label?: string }): Promise<ModelReply>;
  /** Stateful chat with a fixed system prompt. */
  chat(system?: string): ChatSession;
}

export interface ReplayFrame {
  step: number;
  /** Short heading, e.g. "Day 12 · Morning". */
  label?: string;
  /** What the model saw this step (may be abbreviated). */
  observation?: string;
  /** The action the model chose (parsed). */
  action?: string;
  /** Result of the action. */
  outcome?: string;
  /** Gauges / counters to display, e.g. { health: 80, food: 3 }. Numbers 0-100 render as bars when listed in replay.gauges. */
  stats?: Record<string, number | string>;
  /** Optional grid rendering. Each character in rows maps through legend. */
  grid?: { rows: string[]; legend?: Record<string, { label: string; color?: string; emoji?: string }> };
  tone?: 'good' | 'bad' | 'neutral';
  /** Optional coding-agent view ("Fix the Bug"): file tree, diff and test bar. */
  code?: CodeReplayFrame;
  /** Optional simulation view (island, escape room, startup, liar's table); see sim-replay.ts. */
  sim?: SimFrame;
}

/** One step of a coding-agent replay (see src/programs/code-agent.ts). */
export interface CodeReplayFrame {
  /** What happened this step. */
  kind: 'start' | 'list' | 'read' | 'search' | 'edit' | 'tests' | 'submit' | 'invalid' | 'rejected' | 'final';
  /** Every file with its line count and state. */
  files: Array<{ path: string; lines: number; state: 'clean' | 'modified' | 'added' | 'readonly' }>;
  /** File(s) this step looked at or changed. */
  touched?: string[];
  /** Lines shown by a READ (first line number + text). */
  view?: { path: string; start: number; lines: string[] };
  /** Diff of an edit (' ' context, '+' added, '-' removed, '@' hunk header). */
  diff?: { path: string; added: number; removed: number; rows: Array<{ op: ' ' | '+' | '-' | '@'; text: string; n?: number }> };
  /** Latest visible test result (carried forward until the next run). */
  tests?: { passed: number; total: number; failing: string[]; ranThisStep: boolean };
  /** Hidden test result (final frame only). */
  hidden?: { passed: number; total: number; before: number };
  actions: { used: number; budget: number };
  tokens: { used: number; budget: number };
}

export interface ReplayData {
  title: string;
  /** Stat keys (from frame.stats) rendered as 0-100 gauges. */
  gauges?: string[];
  frames: ReplayFrame[];
  /** Optional side-by-side SVG comparison (e.g. Draw It Blind). */
  svgCompare?: { left: { title: string; svg: string }; right: { title: string; svg: string } };
  /** Optional series to chart (e.g. fact survival per round, cash per month). */
  series?: Array<{ name: string; points: Array<{ x: number; y: number }> }>;
  /** Static world for the simulation view (pairs with ReplayFrame.sim). */
  sim?: SimWorld;
}

export interface ProgramContext {
  seed: number;
  rng: Rng;
  config: Record<string, unknown>;
  model: ModelHandle;
  /** Max output tokens per call from the test definition. */
  maxOutputTokens: number;
  signal: AbortSignal;
  /** Save a file artifact (HTML/SVG/text) shown in the UI. */
  artifact(name: string, kind: ArtifactKind, content: string): void;
}

export interface ProgramResult {
  /** 0..1 */
  score: number;
  passed?: boolean;
  /** One-line result for tables and broadcast overlays, e.g. "Escaped in 23 moves". */
  summary: string;
  /** Structured metrics specific to the program (shown in the UI detail panel). */
  detail: Record<string, unknown>;
  replay?: ReplayData;
}

export interface ProgramDefinition {
  id: string;
  name: string;
  description: string;
  /** Human-readable description of how the score is computed. */
  scoring: string;
  /** The program sends images: contestants without image input are skipped (like vision prompt tests). */
  requiresVision?: boolean;
  /** Default config merged under the test's config. */
  defaults?: Record<string, unknown>;
  run(ctx: ProgramContext): Promise<ProgramResult>;
}

// ─────────────────────────────────────────────────────────────────────────────
// Results
// ─────────────────────────────────────────────────────────────────────────────

export type ArtifactKind = 'html' | 'svg' | 'text' | 'png' | 'json';

export interface ArtifactRef {
  name: string;
  kind: ArtifactKind;
  /** Path relative to the run's artifacts directory. */
  file: string;
  bytes: number;
}

export interface TranscriptEntry {
  /** Which call in the case this belongs to, e.g. "turn 3", "judge", "describe". */
  label?: string;
  system?: string;
  messages: ChatMessage[];
  response: string;
  usage: TokenUsage;
  ttftMs: number | null;
  totalMs: number;
  stopReason: StopReason;
  rawStopReason: string;
  costUsd: number;
  retries: number;
  error?: string;
  /** True for judge calls (billed to judgeCostUsd, not the contestant). */
  judge?: boolean;
}

/** `skipped`: the case needs image input and the model has none; not scored, excluded from means. */
export type ResultStatus = 'ok' | 'error' | 'timeout' | 'refusal' | 'pending-human' | 'cancelled' | 'skipped';

export interface ScoreBreakdownItem {
  label: string;
  passed: boolean;
  score?: number;
  detail?: string;
}

export interface ScoreDetail {
  /** Extracted answer (FINAL ANSWER line, code block, etc.). */
  extracted?: string;
  expected?: unknown;
  /** Whether the response followed the required answer format. */
  formatOk?: boolean;
  items?: ScoreBreakdownItem[];
  judge?: Array<{ contestantId: string; score: number; label?: string; rationale: string }>;
  notes?: string;
  [key: string]: unknown;
}

export interface CaseMetrics {
  wallMs: number;
  /** TTFT of the first model call in the case. */
  ttftMs: number | null;
  apiCalls: number;
  inputTokens: number;
  outputTokens: number;
  reasoningTokens: number;
  cachedInputTokens: number;
  costUsd: number;
  judgeCostUsd: number;
  /** Visible output tokens per second across all calls (outputTokens / generation seconds). */
  outputTokensPerSec: number | null;
  retries: number;
  responseChars: number;
}

export interface CaseResult {
  /** Unique id: `${contestantId}::${testId}::${caseId}::r${repeat}` */
  key: string;
  runId: string;
  contestantId: string;
  testId: string;
  testVersion: string;
  testHash: string;
  contestantHash: string;
  caseId: string;
  repeat: number;
  seed?: number;
  status: ResultStatus;
  /** 0..1, null while pending human review or on error. */
  score: number | null;
  passed: boolean | null;
  /** One-line human summary. */
  summary: string;
  scoreDetail: ScoreDetail;
  metrics: CaseMetrics;
  transcript: TranscriptEntry[];
  artifacts: ArtifactRef[];
  replay?: ReplayData;
  error?: string;
  startedAt: string;
  finishedAt: string;
  humanScores?: Array<{ rater: string; score: number; at: string; note?: string }>;
}

/** Lightweight result row (no transcript / replay) for tables. */
export type CaseResultLite = Omit<CaseResult, 'transcript' | 'replay'> & { hasReplay: boolean };

// ─────────────────────────────────────────────────────────────────────────────
// Runs
// ─────────────────────────────────────────────────────────────────────────────

export type RunStatus = 'queued' | 'running' | 'completed' | 'cancelled' | 'failed' | 'interrupted';

export interface RunRequest {
  name?: string;
  suiteId?: string;
  /** Explicit test ids (overrides suite selection when non-empty). */
  testIds?: string[];
  contestantIds: string[];
  repeats?: number;
  concurrency?: number;
  /** Harness temperature for models that accept it (default 0). */
  temperature?: number;
  /** Judge contestant ids (default: settings.judges). */
  judgeIds?: string[];
  /** Hard spending cap in USD (contestant + judge cost). The run stops starting new cases once reached. */
  maxCostUsd?: number;
  notes?: string;
  /** Send image cases to models not marked `vision: true` instead of skipping them. */
  forceVision?: boolean;
}

export interface TestSnapshot {
  id: string;
  version: string;
  hash: string;
  name: string;
  category: string;
  kind: 'prompt' | 'program';
  caseIds: string[];
  weight: number;
}

export interface ContestantSnapshot extends Contestant {
  configHash: string;
}

export interface RunManifest {
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
  suiteId?: string;
  suiteVersion?: string;
  /** Hash over every test hash + judge prompts + harness protocol version. Identical fingerprints = identical test conditions. */
  fingerprint: string;
  tests: TestSnapshot[];
  contestants: ContestantSnapshot[];
  judges: ContestantSnapshot[];
  settings: {
    repeats: number;
    concurrency: number;
    temperature: number;
    protocolVersion: string;
    maxCostUsd?: number;
    judgeExcludeSameVendor?: boolean;
    forceVision?: boolean;
  };
  totalJobs: number;
  notes?: string;
  error?: string;
}

export interface RunListItem {
  id: string;
  name: string;
  status: RunStatus;
  createdAt: string;
  finishedAt?: string;
  suiteId?: string;
  fingerprint: string;
  contestants: Array<{ id: string; label: string; color: string }>;
  testCount: number;
  totalJobs: number;
  completedJobs: number;
  costUsd: number;
}

// ─────────────────────────────────────────────────────────────────────────────
// Aggregates / leaderboard
// ─────────────────────────────────────────────────────────────────────────────

export interface TestAggregate {
  testId: string;
  /** Mean score 0..1 over all cases × repeats (null when no scored results). */
  score: number | null;
  ci95: [number, number] | null;
  n: number;
  passRate: number | null;
  costUsd: number;
  medianCaseMs: number | null;
  /** Mean per-case std-dev across repeats (0 = perfectly consistent). */
  repeatStdDev: number | null;
  errors: number;
  pendingHuman: number;
  /** Cases skipped because the model has no image input (not scored, excluded from the mean). */
  skipped?: number;
  /** Best one-line summary (for program tests: of the median case). */
  summary?: string;
}

export interface LeaderboardRow {
  contestantId: string;
  label: string;
  vendor: string;
  color: string;
  rank: number;
  /** Gauntlet Index 0..100 (weighted mean of category scores). */
  index: number | null;
  indexCi95: [number, number] | null;
  categoryScores: Record<string, number | null>;
  tests: Record<string, TestAggregate>;
  /** Fraction of suite tests that have at least one scored result. */
  coverage: number;
  medals: { gold: number; silver: number; bronze: number };
  totals: {
    costUsd: number;
    judgeCostUsd: number;
    inputTokens: number;
    outputTokens: number;
    reasoningTokens: number;
    apiCalls: number;
    cases: number;
    errors: number;
    refusals: number;
    wallMs: number;
    /** Vision cases skipped (no image input); excluded from every mean. */
    skipped?: number;
  };
  speed: {
    medianTtftMs: number | null;
    medianCaseMs: number | null;
    outputTokensPerSec: number | null;
  };
  /** USD spent per Gauntlet Index point (lower is better). */
  costPerPoint: number | null;
  reliability: {
    errorRate: number;
    refusalRate: number;
    formatCompliance: number | null;
  };
  /** Mean std-dev of scores across repeats of the same case (lower = more consistent). */
  consistency: number | null;
  /** True when this contestant's results were entered manually (copy & paste); speed/cost figures are not API measurements. */
  manual?: boolean;
}

export interface MedalEntry {
  testId: string;
  gold?: string;
  silver?: string;
  bronze?: string;
}

export interface Leaderboard {
  generatedAt: string;
  scope: { kind: 'run'; runId: string } | { kind: 'combined'; suiteId: string };
  fingerprint: string;
  categories: CategoryInfo[];
  categoryWeights: Record<string, number>;
  tests: Array<{ id: string; name: string; category: string; weight: number; version: string; hash: string }>;
  rows: LeaderboardRow[];
  medals: MedalEntry[];
  /** Results excluded because their test or model config changed since they were produced. */
  staleExcluded: number;
}

// ─────────────────────────────────────────────────────────────────────────────
// Live events (Server-Sent Events on /api/runs/:id/events)
// ─────────────────────────────────────────────────────────────────────────────

export type RunEvent =
  | { type: 'run.status'; runId: string; status: RunStatus; at: string; error?: string }
  | { type: 'run.progress'; runId: string; completed: number; total: number; costUsd: number; at: string }
  | { type: 'job.started'; runId: string; key: string; contestantId: string; testId: string; caseId: string; repeat: number; at: string }
  | { type: 'job.delta'; runId: string; key: string; contestantId: string; text: string; label?: string }
  | { type: 'job.step'; runId: string; key: string; contestantId: string; label: string; frame?: ReplayFrame }
  | { type: 'job.finished'; runId: string; key: string; contestantId: string; testId: string; caseId: string; repeat: number; status: ResultStatus; score: number | null; summary: string; metrics: CaseMetrics; at: string }
  | { type: 'log'; runId: string; level: 'info' | 'warn' | 'error'; message: string; at: string }
  | { type: 'manual.request'; runId: string; request: ManualRequest }
  | { type: 'manual.resolved'; runId: string; requestId: string };

// ─────────────────────────────────────────────────────────────────────────────
// Manual (copy & paste) contestants
// ─────────────────────────────────────────────────────────────────────────────

/** A model call waiting for a human to paste the reply from any chatbot. */
export interface ManualRequest {
  id: string;
  runId: string;
  key: string;
  contestantId: string;
  contestantLabel: string;
  testId: string;
  testName: string;
  caseId: string;
  /** Call label, e.g. "response", "turn 2", "Day 3". */
  label: string;
  system?: string;
  messages: ChatMessage[];
  /** Everything above as one block, ready to paste into a chat UI that has no system-prompt field. */
  combinedPrompt: string;
  /** Only the newest user message (for chat UIs where the earlier turns are already in the conversation). */
  latestUserMessage: string;
  /** True when earlier turns exist: paste into the SAME conversation, or use combinedPrompt in a NEW one. */
  isContinuation: boolean;
  createdAt: string;
}

export interface ManualSubmission {
  text: string;
  inputTokens?: number;
  outputTokens?: number;
  reasoningTokens?: number;
  costUsd?: number;
}
