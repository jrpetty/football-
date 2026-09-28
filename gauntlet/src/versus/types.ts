/**
 * Head to Head ("versus") — two models compared test by test on results that
 * are already stored. Shared by the server (src/versus/server.ts), the mock
 * API (ui/src/mock/versusMock.ts) and the UI (ui/src/versus/*).
 */

/** One side of the fight, from the model config (or the run snapshot). */
export interface VersusFighter {
  id: string;
  label: string;
  vendor: string;
  color: string;
  /** USD per million tokens, as configured. Null when the model has no price (manual / baseline). */
  pricing: { inputPerM: number; outputPerM: number } | null;
  contextWindow: number | null;
  /** The Random Baseline (a know-nothing reference player). */
  baseline?: boolean;
  /** Replies pasted by hand (Manual contestant). */
  manual?: boolean;
}

/** One model's numbers for one test ("round"). */
export interface VersusSide {
  /** Test score 0..1 over the cases both models answered (mean over cases of the mean over repeats). */
  score: number | null;
  /** Scored samples behind the score. */
  samples: number;
  /** Share of scored samples marked correct (null when the test has no pass/fail). */
  passRate: number | null;
  /** Model cost of one pass through the compared cases (mean per case, summed). */
  costUsd: number;
  /** Wall time of one pass through the compared cases (mean per case, summed). */
  timeMs: number | null;
}

/** One model's answer in a decisive moment, quoted from the recorded response. */
export interface VersusAnswer {
  /** Short quote of the recorded response (the part with the answer, when it can be found). */
  quote: string;
  /** True when `quote` was cut from a longer response. */
  truncated: boolean;
  /** The answer the scorer extracted (e.g. the FINAL ANSWER line), when recorded. */
  extracted?: string;
  /** The result's one-line summary, as recorded. */
  summary?: string;
  score: number | null;
  passed: boolean | null;
  /** Where to open the full result. */
  runId: string;
  key: string;
}

/** A case where one model got it right and the other got it wrong. */
export interface VersusMoment {
  caseId: string;
  /** Which side was right. */
  right: 'a' | 'b';
  /** The question, quoted from the recorded prompt (truncated). */
  question?: string;
  /** The recorded answer key, when it is a short plain value. */
  expected?: string;
  a: VersusAnswer;
  b: VersusAnswer;
}

export interface VersusRound {
  testId: string;
  testName: string;
  category: string;
  categoryName: string;
  categoryColor: string;
  /** One-line hook from the test definition, when it has one. */
  hook?: string;
  /** Cases both models answered (the only ones compared). */
  cases: number;
  a: VersusSide;
  b: VersusSide;
  winner: 'a' | 'b' | 'tie';
  /** |a − b| in points out of 100. */
  margin: number;
  moment: VersusMoment | null;
}

export interface VersusTotals {
  roundsWon: number;
  /** Mean of the round scores × 100 (every compared test counts the same). */
  avgScore: number | null;
  costUsd: number;
  timeMs: number | null;
  /** Output tokens per second across the compared results (null when not recorded). */
  tokensPerSec: number | null;
  /** Average score points per US dollar spent (null when no cost was recorded). */
  value: number | null;
}

/** A test only one model (or neither) has results for; not compared. */
export interface VersusSkipped {
  testId: string;
  testName: string;
  reason: 'only-a' | 'only-b' | 'no-shared-cases';
}

export type VersusScope = { kind: 'combined'; suiteId?: string } | { kind: 'run'; runId: string; runName: string };

export interface VersusData {
  scope: VersusScope;
  a: VersusFighter;
  b: VersusFighter;
  rounds: VersusRound[];
  skipped: VersusSkipped[];
  totals: { a: VersusTotals; b: VersusTotals };
  ties: number;
  /** Most rounds won; equal rounds = 'tie' (a draw). */
  winner: 'a' | 'b' | 'tie';
  /** Two scores closer than this (points out of 100) are a tied round. */
  tieMargin: number;
  generatedAt: string;
}

/** GET /api/versus/options — who can be compared in a scope. */
export interface VersusOptions {
  scope: VersusScope;
  fighters: Array<VersusFighter & { tests: number }>;
  /** Suggested pair: the two best models by average score (null with fewer than two). */
  suggested: [string, string] | null;
  runs: Array<{ id: string; name: string; createdAt: string; contestantIds: string[] }>;
}
