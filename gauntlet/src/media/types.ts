/**
 * Studio — shapes shared by the highlight finder, the script generator, the
 * thumbnail / Shorts cards and the OBS overlays. Everything here is plain data
 * so the same pure modules run on the server and in the UI's mock mode.
 */
import type { CaseResult, CategoryInfo, Leaderboard, ReplayData, RunManifest } from '../core/types.ts';

/** What the Studio knows about each test of a run (from the manifest + the test library). */
export interface StudioTestInfo {
  id: string;
  name: string;
  category: string;
  kind: 'prompt' | 'program';
  /** One-line broadcast hook from the test definition. */
  hook?: string;
  description?: string;
  /** Program id for simulations (e.g. "survival-island"). */
  program?: string;
  /** Scorer type, e.g. "exact", "judge-classify", "program:escape-room". */
  scorerType?: string;
}

/** A stored result without its transcript (transcripts are never needed here and can be large). */
export type StudioResult = Omit<CaseResult, 'transcript' | 'replay'> & { replay?: ReplayData; hasReplay?: boolean };

export interface StudioInput {
  manifest: RunManifest;
  leaderboard: Leaderboard | null;
  results: StudioResult[];
  /** Info for every test in the manifest (missing library entries fall back to the manifest name). */
  tests: StudioTestInfo[];
  /** Canonical category order (config/categories.json), used for the Presenter's running order. */
  categories: CategoryInfo[];
  /** Contestants answered by a human (copy & paste): their cost and speed are not API measurements. */
  manualIds?: string[];
}

export type HighlightType =
  | 'upset'
  | 'close-race'
  | 'clean-sweep'
  | 'catastrophe'
  | 'confident-wrong'
  | 'fastest-correct'
  | 'expensive-wrong'
  | 'best-value'
  | 'inconsistent'
  | 'big-win';

/** One number behind a pick, with the exact text used on screen. */
export interface HighlightEvidence {
  label: string;
  value: number;
  display: string;
}

export interface Highlight {
  /** Stable id (type + subject), e.g. "upset:math.competition:cheap:dear". */
  id: string;
  type: HighlightType;
  /** One-line title for the video, e.g. "Budget model beats the flagship on Escape Room". */
  title: string;
  /** Why it matters, in plain words. */
  why: string;
  /** 0..100 — how dramatic the moment is (used for ranking). */
  drama: number;
  contestantIds: string[];
  testId?: string;
  caseId?: string;
  /** Result key of the exact case to show. */
  key?: string;
  repeat?: number;
  /** Replay step to jump to (simulations). */
  replayStep?: number;
  /** Dashboard deep link (hash route), e.g. "#/runs/<id>?tab=matrix&test=…&c=…&key=…&step=14". */
  link: string;
  /** Editing cue for the script, e.g. "[Replay: Escape Room, Opus, step 14]". */
  cue: string;
  /** Suggested clip length in seconds. */
  clipSec: number;
  evidence: HighlightEvidence[];
  /** The rule that picked this moment (shown so every pick is explainable). */
  rule: string;
}

/** One slide of the Presenter deck, numbered exactly as the Presenter numbers them. */
export interface SlideRef {
  n: number;
  kind: 'title' | 'how' | 'explainer' | 'result' | 'final' | 'scatter' | 'medals' | 'outro';
  testId?: string;
}

export interface ScriptSection {
  id: string;
  title: string;
  /** Spoken lines; cues such as "[Slide 7]" are kept inline so an editor can cut to them. */
  lines: string[];
  /** Estimated read time in seconds (150 words per minute, cues excluded). */
  estSec: number;
}

export interface VideoScript {
  title: string;
  runId: string;
  sections: ScriptSection[];
  wordCount: number;
  estSec: number;
}

// ─────────────────────────────── Cards (thumbnails & Shorts) ───────────────────────────────

export type CardKind = 'thumbnail' | 'short-standings' | 'short-test' | 'short-question' | 'short-highlight';
export type CardStyle = 'versus' | 'bold' | 'clean' | 'neon';

export interface CardSpec {
  kind: CardKind;
  style: CardStyle;
  /** Custom headline (thumbnail / cards); defaults are generated from the data. */
  headline?: string;
  /** For per-test and question cards. */
  testId?: string;
  /** For highlight cards. */
  highlightId?: string;
  /** Thumbnail match-up (defaults to the top two). */
  a?: string;
  b?: string;
}

export interface CardContestant {
  id: string;
  label: string;
  vendor: string;
  color: string;
  /** Gauntlet Index 0..100 (or mean score × 100 when there is no Index). */
  score: number | null;
  baseline: boolean;
}

export interface CardTest {
  id: string;
  name: string;
  hook?: string;
  categoryName: string;
  categoryColor: string;
  /** Mean score 0..100 per contestant, best first. */
  results: Array<{ id: string; label: string; color: string; score: number }>;
}

export interface CardData {
  runId: string;
  runName: string;
  /** Standings, best first; the baseline (if any) last. */
  contestants: CardContestant[];
  tests: CardTest[];
  highlights: Highlight[];
}

// ─────────────────────────────── Overlays ───────────────────────────────

export interface OverlayData {
  runId: string;
  runName: string;
  status: string;
  /** "index" = final Gauntlet Index; "average" = mean score so far (live runs). */
  scoreKind: 'index' | 'average';
  standings: Array<{ id: string; label: string; color: string; score: number | null; done: number; total: number }>;
  ticker: Array<{ key: string; contestantId: string; label: string; color: string; testName: string; score: number | null; status: string; summary: string; at: string }>;
  /** Best guess at the test being played now (the UI refines it from live job.started events). */
  now: { testId: string; testName: string; hook?: string; contestants: Array<{ id: string; label: string; color: string }> } | null;
  tests: Array<{ id: string; name: string; winner: { id: string; label: string; color: string; score: number } | null; done: number; total: number }>;
  progress: { completed: number; total: number };
}

// ─────────────────────────────── API payloads ───────────────────────────────

/** GET /api/studio/:runId */
export interface StudioPayload {
  runId: string;
  runName: string;
  status: string;
  createdAt: string;
  highlights: Highlight[];
  slides: SlideRef[];
  script: VideoScript;
  markdown: string;
  text: string;
  cardData: CardData;
  /** Every number the run's data can back up (for checking an edited or polished script). */
  allowedNumbers: string[];
  browser: { available: boolean; hint?: string };
  /** Where "Export all" writes (inside the run's folder). */
  exportDir: string;
}

/** POST /api/studio/:runId/polish/estimate */
export interface PolishEstimate {
  modelId: string;
  modelLabel: string;
  inputTokens: number;
  outputTokens: number;
  costUsd: number;
  costUsdHigh: number;
  maxOutputTokens: number;
}

/** POST /api/studio/:runId/polish */
export interface PolishResult {
  text: string;
  costUsd: number;
  /** Numbers in the polished text that the run's data cannot back up. */
  unverified: string[];
  stopReason: string;
}

/** POST /api/studio/:runId/export */
export interface ExportResult {
  folder: string;
  files: string[];
  pngs: number;
  browser: boolean;
  hint?: string;
}

/** POST /api/studio/:runId/render */
export interface RenderResult {
  png: string;
  width: number;
  height: number;
  fileName: string;
}
