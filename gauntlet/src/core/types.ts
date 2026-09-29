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
  /**
   * The model's own hard limit on output tokens per reply (the API rejects larger requests). Requests above it are
   * lowered to it, so a test asking for more (e.g. The Game Jam's 64k) still runs instead of failing. Optional, and
   * not part of the config hash: it only turns would-be API errors into valid calls, never changes a successful one.
   */
  maxOutputTokens?: number;
  /** Where maxOutputTokens comes from, e.g. "platform.claude.com model page, checked 2026-09-28" or "unverified: …". Display only. */
  maxOutputTokensSource?: string;
  /** Can generate images (The Gallery Masterpiece). Missing = no; models without it are skipped on image-output tests. */
  imageOutput?: boolean;
  /** Makes pictures only (e.g. gpt-image-1): every text test is skipped for it instead of failing. */
  imageOnly?: boolean;
  /** Per-image prices for image generation (see src/core/image-output.ts). */
  imagePricing?: ImagePricing;
  /** Image-generation request options (size, quality, …); see src/providers/image-gen.ts. */
  imageOptions?: ImageOptions;
}

/**
 * USD per generated image. `perImage[size][quality]`; "*" matches any size or quality (e.g. Gemini's flat price).
 * Sizes are "WIDTHxHEIGHT" as sent to the API. Prompt text is billed on top at `pricing.inputPerM`.
 */
export interface ImagePricing {
  perImage: Record<string, Record<string, number>>;
  source?: string;
  verifiedAt?: string | null;
}

export interface ImageOptions {
  /** OpenAI-style size sent to /images/generations (null = don't send one, e.g. xAI). Default "1536x1024" on api.openai.com. */
  size?: string | null;
  /** OpenAI-style quality (low | medium | high; null = don't send). Default "high" on api.openai.com. */
  quality?: string | null;
  /** OpenAI-compatible only: response_format to request (xAI and DALL·E need "b64_json"; gpt-image models always return base64). */
  responseFormat?: string | null;
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

/** One image-generation call (The Gallery Masterpiece). */
export interface ImageGenRequest {
  prompt: string;
  /** Wanted aspect ratio, e.g. "3:2" (mapped to each API's size / aspect setting). */
  aspectRatio: string;
  signal?: AbortSignal;
  callContext?: CompletionRequest['callContext'];
}

export interface GeneratedImage {
  mediaType: 'image/png' | 'image/jpeg';
  /** Base64 bytes. */
  data: string;
  width: number;
  height: number;
  bytes: number;
}

export interface ImageGenResult {
  /** Empty when the provider refused or returned no picture. */
  images: GeneratedImage[];
  /** Any text that came back (a refusal, a caption, a revised prompt). */
  text: string;
  usage: TokenUsage;
  startedAt: number;
  totalMs: number;
  stopReason: StopReason;
  rawStopReason: string;
  servedModel: string;
  requestId?: string;
  /** Size and quality that were requested (they set the per-image price). */
  size?: string;
  quality?: string;
  /** Explicit cost (manual entries). */
  costUsd?: number;
  manual?: boolean;
}

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
  /**
   * Max output tokens per model call (default 16000). "model-max" asks for each model's own maximum
   * (Contestant.maxOutputTokens, or MODEL_MAX_FALLBACK when a model does not declare one): no artificial cap.
   */
  maxOutputTokens?: number | 'model-max';
  /** Wall-clock limit per case in seconds (default 600). */
  timeLimitSec?: number;
  /**
   * Retries for this test's model calls after a network or server error (default: settings.maxRetries). Very long
   * generations (The Game Jam) use fewer, because each retry starts a paid hour-long reply from scratch.
   */
  maxRetries?: number;
  /** Rough per-case token estimate used for pre-run cost estimates. */
  estimate?: {
    inputTokens: number;
    outputTokens: number;
    calls?: number;
    /** Judge-scored tests: input tokens each judge reads per case (default: a fixed prompt plus half the reply). */
    judgeInputTokens?: number;
    /** Judge-scored tests: output tokens each judge writes per case (default 1,500). */
    judgeOutputTokens?: number;
  };
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
  /**
   * Extracts an HTML/SVG artifact, runs automated checks, optionally judges it. With `playtest` (The Game Jam), the
   * game is also played by a scripted player for its genre (case.expected.genre) and the judges grade a numbered
   * requirement checklist plus fixed criteria, seeing the playtest screenshots (see src/scoring/game-jam.ts).
   */
  | {
      type: 'artifact';
      format: 'html' | 'svg';
      checks?: ArtifactCheck[];
      rubric?: string;
      judgeWeight?: number;
      /** The judges' 0/10 ("automatic zero" in the rubric) zeroes the whole score, so passing hygiene checks alone earns nothing. */
      zeroIfJudgedZero?: boolean;
      playtest?: PlaytestSpec;
    }
  /** Scored by humans in the Blind Review screen. */
  | { type: 'human'; rubric: string };

/** Genre playtest + checklist judging for game artifacts (The Game Jam). */
export interface PlaytestSpec {
  /** Version of the playtest script and judge format in src/scoring/game-jam.ts; must match the code (bump both together). */
  protocol: number;
}

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
  /** Set when a spend limit lowered this call's output limit ('spend-limit' = the run's cap, 'per-answer' = the per-answer cap). */
  outputLimitBy?: 'spend-limit' | 'per-answer';
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
  /** Image generation (only for contestants with image output; see ProgramDefinition.requiresImageOutput). */
  generateImage?(req: { prompt: string; aspectRatio?: string; label?: string }): Promise<ImageReply>;
}

export interface ImageReply {
  /** The first returned picture, or null (refused / none returned). */
  image: GeneratedImage | null;
  text: string;
  stopReason: StopReason;
  totalMs: number;
  costUsd: number;
}

/** Judge models for programs that declare `judges` (see ProgramDefinition). Images go to every judge in the same message. */
export interface ProgramJudges {
  /** Judge contestant ids on the panel (already filtered by the program's judge rules). */
  ids: string[];
  ask(system: string, user: string, label: string, images?: ChatImage[]): Promise<Array<{ judgeId: string; text: string; error?: string }>>;
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
  /**
   * Optional test-specific data for a richer replay view (e.g. needle passages, the whispers fact trace).
   * Display only: never sent to the model or used for scoring. Older results lack it and fall back to the frames.
   */
  visual?: { kind: string; data: Record<string, unknown> };
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
  /** Save a binary artifact (e.g. a generated picture). Optional: older harnesses and test helpers lack it. */
  artifactBytes?(name: string, kind: ArtifactKind, content: Uint8Array): void;
  /** The judge panel, for programs that declare `judges` (undefined otherwise). */
  judges?: ProgramJudges;
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
  /** "refusal": the model refused (score 0, status refusal). "pending-human": not scorable yet (e.g. too few judges); a person rates it in Blind Review. */
  status?: 'refusal' | 'pending-human';
}

export interface ProgramDefinition {
  id: string;
  name: string;
  description: string;
  /** Human-readable description of how the score is computed. */
  scoring: string;
  /** The program sends images: contestants without image input are skipped (like vision prompt tests). */
  requiresVision?: boolean;
  /** The program asks the model for pictures (ModelHandle.generateImage): contestants without image output are skipped. */
  requiresImageOutput?: boolean;
  /**
   * The program is judged by the judge panel (ctx.judges). `vision`: only judges that accept images;
   * `strictVendor`: never a judge from the contestant's vendor (no fallback). `perCase` is the per-judge,
   * per-case usage for cost estimates.
   */
  judges?: { vision?: boolean; strictVendor?: boolean; perCase: { inputTokens: number; outputTokens: number; images?: number; imageSize?: { width: number; height: number } } };
  /** Pictures generated per case (image-output programs), for cost estimates. */
  imagesPerCase?: number;
  /** Default config merged under the test's config. */
  defaults?: Record<string, unknown>;
  run(ctx: ProgramContext): Promise<ProgramResult>;
}

// ─────────────────────────────────────────────────────────────────────────────
// Results
// ─────────────────────────────────────────────────────────────────────────────

export type ArtifactKind = 'html' | 'svg' | 'text' | 'png' | 'json' | 'jpg';

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
  /** A spend limit lowered this call's output limit (to maxOutputTokens). */
  outputLimitBy?: 'spend-limit' | 'per-answer';
  maxOutputTokens?: number;
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
  /** Human grades (Blind Review, Grading Station). One entry per rater; a rater re-grading replaces their entry. */
  humanScores?: HumanScore[];
  /** Grading Station: AI judges run after the fact, one entry per judge verdict (see src/grading/station.ts). */
  aiGrades?: AiGrade[];
  /** Grading Station: a person disputes an objective (answer-key / machine) score. The score itself never changes. */
  disputes?: Array<{ rater: string; note: string; at: string }>;
}

/** A human grade. Optional fields come from the Grading Station's rubric (older entries have only rater/score/at/note). */
export interface HumanScore {
  rater: string;
  /** 0..1 */
  score: number;
  at: string;
  note?: string;
  /** Rubric points per criterion id (see src/grading/spec.ts). */
  criteria?: Record<string, number>;
  /** Per-requirement verdicts for requirement criteria, keyed "<criterion id>:<requirement id>". */
  requirements?: Record<string, 'met' | 'partial' | 'missed'>;
  /** judge-classify tests: the label the person picked. */
  label?: string;
  /** True when model names were hidden while grading. */
  blind?: boolean;
}

/** One AI judge's verdict from the Grading Station's AI mode. */
export interface AiGrade {
  judgeId: string;
  judgeLabel: string;
  vendor: string;
  /** 0..1 */
  score: number;
  label?: string;
  rationale: string;
  at: string;
  /** Batch id: every judge asked in one "grade with AI" action shares it. */
  batch: string;
  costUsd: number;
  /** Images (test pictures, screenshots) attached for a vision judge. */
  images: number;
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
  /** Hard spending cap in USD (contestant + judge cost). No call may start whose worst case the money left cannot pay for. */
  maxCostUsd?: number;
  /** Optional per-answer cap and output-limit choice (see RunLimits). */
  limits?: Pick<RunLimits, 'perAnswerUsd' | 'sameOutputTokens' | 'currency'>;
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
    /** The spend and output limits this run used (absent on runs started before limits existed: model maximum, no caps). */
    limits?: RunLimits;
  };
  totalJobs: number;
  notes?: string;
  error?: string;
  /** Why an unfinished run stopped when it was not a failure: 'spend-limit' = the run's spend limit was reached. */
  stopReason?: 'spend-limit';
  /** Money spent on cases that were stopped half-way by the spend limit (not in any result; counted towards the cap on resume). */
  unrecordedCostUsd?: number;
}

/** The limits a run was started with, recorded in its manifest so a video can disclose them. All money in USD. */
export interface RunLimits {
  /** Whole-run cap (contestants + judges); same value as settings.maxCostUsd. */
  maxCostUsd?: number;
  /** Per-answer cap: each model's output limit per reply is what this buys at its own prices (not token-fair). */
  perAnswerUsd?: number;
  /** The fair alternative: every model gets this many output tokens on "model's maximum" tests (or its own maximum, if lower). */
  sameOutputTokens?: number;
  /** The display currency and rate the limits were typed in, e.g. { code: 'GBP', usdPerUnit: 1.33 }. */
  currency?: { code: string; usdPerUnit: number };
  /** Set when the monthly budget's hard stop lowered the whole-run limit, e.g. "Limited to £12.40: what's left of your £50 monthly budget." */
  budgetNote?: string;
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
    /** Of `skipped`: cases skipped because the model can't make pictures (image-output tests), or is picture-only. */
    skippedImageOutput?: number;
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
  /** Streamed answer text (display only). `reset`: drop what this job's current call streamed so far (a failed attempt is being retried), then append `text`. */
  | { type: 'job.delta'; runId: string; key: string; contestantId: string; text: string; label?: string; reset?: boolean }
  | { type: 'job.step'; runId: string; key: string; contestantId: string; label: string; frame?: ReplayFrame }
  | { type: 'job.finished'; runId: string; key: string; contestantId: string; testId: string; caseId: string; repeat: number; status: ResultStatus; score: number | null; passed?: boolean | null; summary: string; metrics: CaseMetrics; at: string }
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
  /** "image": the reply is a picture (upload or paste it; The Gallery Masterpiece). Missing = text. */
  expects?: 'text' | 'image';
  /** Image replies: the wanted aspect ratio, e.g. "3:2". */
  aspectRatio?: string;
}

export interface ManualSubmission {
  text: string;
  /** Image reply (requests with expects "image"). */
  image?: GeneratedImage;
  inputTokens?: number;
  outputTokens?: number;
  reasoningTokens?: number;
  costUsd?: number;
}
