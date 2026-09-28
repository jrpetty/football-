/**
 * Horizon tier: ladder maths and the per-rung "answer vs truth" model. Pure functions shared by the
 * Result Inspector, the Presenter and the unit tests (no Node or DOM dependencies).
 *
 * Every Horizon test is a ladder of frozen levels with case ids L01, L02, … (L01 easiest). A level
 * counts as SOLVED RELIABLY when at least two thirds of its attempts scored full marks (with one
 * attempt: that attempt). The headline number, the ladder HEIGHT, is the highest level reached without
 * falling off: every level from 1 up to it solved reliably. A lucky solve higher up is reported
 * separately (`best`), so one fluke never lifts a model past a rung it could not climb.
 */
import type { CaseVisualInput } from './common.ts';
import { replayPlan, parsePlan, parseGrid, parseBigInteger, type PlanKey } from '../../scoring/ladder.ts';
import { extractFinalAnswer } from '../../core/extract.ts';

export const HORIZON_PREFIX = 'horizon.';
export const LADDER_RELIABLE_SHARE = 2 / 3;

export function isHorizonTest(testId: string): boolean {
  return testId.startsWith(HORIZON_PREFIX);
}

/** "L07" → 7; anything else → null. */
export function ladderLevel(caseId: string): number | null {
  const m = /^L(\d{1,3})$/.exec(caseId);
  return m ? Number(m[1]) : null;
}

export interface LadderResultLike {
  contestantId: string;
  testId: string;
  caseId: string;
  score: number | null;
  status?: string;
}

export interface Rung {
  level: number;
  attempts: number;
  /** Attempts with full marks. */
  full: number;
  /** Mean score of the attempts (partial credit counts). */
  mean: number;
  reliable: boolean;
}

export interface Climb {
  contestantId: string;
  /** One entry per level 1..levels (attempts 0 = not run). */
  rungs: Rung[];
  /** Ladder height: every level from 1 up to this one solved reliably (0 = none). */
  height: number;
  /** Highest level with at least one full-marks attempt (0 = none). */
  best: number;
  /** Mean over the levels that were run: the test score. */
  score: number | null;
}

/** How far up the ladder each contestant got on one test. Contestants in first-seen order. */
export function ladderClimbs(results: LadderResultLike[], testId: string, levels = 10): Climb[] {
  const by = new Map<string, Map<number, number[]>>();
  for (const r of results) {
    if (r.testId !== testId || typeof r.score !== 'number') continue;
    if (r.status && r.status !== 'ok' && r.status !== 'timeout' && r.status !== 'refusal') continue;
    const lv = ladderLevel(r.caseId);
    if (lv === null) continue;
    let m = by.get(r.contestantId);
    if (!m) by.set(r.contestantId, (m = new Map()));
    m.set(lv, [...(m.get(lv) ?? []), r.score]);
  }
  const maxLevel = Math.max(levels, ...[...by.values()].flatMap((m) => [...m.keys()]));
  const out: Climb[] = [];
  for (const [contestantId, m] of by) {
    const rungs: Rung[] = [];
    for (let level = 1; level <= maxLevel; level++) {
      const s = m.get(level) ?? [];
      const full = s.filter((x) => x >= 0.999).length;
      const mean = s.length ? s.reduce((a, b) => a + b, 0) / s.length : 0;
      rungs.push({ level, attempts: s.length, full, mean, reliable: s.length > 0 && full / s.length >= LADDER_RELIABLE_SHARE - 1e-9 });
    }
    const run = rungs.filter((r) => r.attempts > 0);
    let height = 0;
    while (height < rungs.length && rungs[height]!.reliable) height++;
    out.push({
      contestantId,
      rungs,
      height,
      best: Math.max(0, ...rungs.filter((r) => r.full > 0).map((r) => r.level)),
      score: run.length ? run.reduce((a, r) => a + r.mean, 0) / run.length : null,
    });
  }
  return out;
}

/** A short caption of what one rung asks, read from the case's auditor notes / prompt (display only). */
export function rungCaption(testId: string, prompt: string, notes?: string): string | null {
  const n = notes ?? '';
  switch (testId) {
    case 'horizon.mind-runner': {
      const m = /(\d+) statements executed/.exec(n);
      return m ? `${Number(m[1]).toLocaleString('en-US')} steps` : null;
    }
    case 'horizon.modpow-ladder': {
      const m = /^m = (\d+)$/m.exec(prompt);
      return m ? `${m[1]!.length}-digit numbers` : null;
    }
    case 'horizon.sliding-ladder': {
      const m = /(\d+)x(\d+) board, proven minimum (\d+) moves/.exec(n);
      return m ? `${m[1]}×${m[2]} · ${m[3]} moves` : null;
    }
    case 'horizon.nonogram-ladder': {
      const m = /grid has (\d+) rows and (\d+) columns/.exec(prompt);
      return m ? `${m[1]}×${m[2]} grid` : null;
    }
    case 'horizon.tiling-count': {
      const m = /board with (\d+) rows and (\d+) columns/.exec(prompt);
      return m ? `${m[1]}×${m[2]} board` : null;
    }
    default:
      return null;
  }
}

// ─── one rung: answer vs truth ───────────────────────────────────────────────────────────────────────

export type RungVerdict = 'correct' | 'optimal' | 'suboptimal' | 'wrong' | 'illegal' | 'unsolved' | 'no-answer';

export interface IntegerCompare {
  kind: 'integer';
  want: string;
  got: string | null;
  /** Index of the first differing digit (right-aligned comparison is not used: numbers are read left to right). */
  firstDiff: number;
  /** Number of digit positions (of the key) that match. */
  sameDigits: number;
  /** Same length as the key: how many positions differ (else -1). */
  wrongDigits: number;
}

export interface PlanCompare {
  kind: 'plan';
  rows: number;
  cols: number;
  start: number[];
  optimal: number;
  optimalPlan: number[];
  moves: number[];
  illegalAt: number;
  solved: boolean;
  /** Board after the model's plan (or at the illegal move). */
  end: number[];
}

export interface GridCompare {
  kind: 'grid';
  want: string[];
  got: string[] | null;
  wrong: number;
}

export interface RungModel {
  testId: string;
  level: number;
  caption: string | null;
  verdict: RungVerdict;
  score: number | null;
  compare: IntegerCompare | PlanCompare | GridCompare;
}

const replyOf = (input: CaseVisualInput) => input.replies[input.replies.length - 1] ?? '';

export function horizonVisual(input: CaseVisualInput): RungModel | null {
  if (!isHorizonTest(input.testId)) return null;
  const level = ladderLevel(input.caseId);
  if (level === null) return null;
  const caption = rungCaption(input.testId, input.turns[0] ?? '', input.notes);
  const exp = input.expected ?? input.detail.expected;
  const reply = replyOf(input);
  if (typeof exp === 'string' && /^\d+$/.test(exp)) {
    const raw = reply ? extractFinalAnswer(reply).answer : typeof input.detail.extracted === 'string' ? input.detail.extracted : '';
    const got = raw ? parseBigInteger(raw) : null;
    let firstDiff = -1;
    let same = 0;
    if (got !== null) {
      for (let i = 0; i < Math.max(got.length, exp.length); i++) {
        if (got[i] === exp[i]) same++;
        else if (firstDiff < 0) firstDiff = i;
      }
    }
    const ok = got === exp;
    const wrongDigits = got !== null && got.length === exp.length ? got.length - same : -1;
    return { testId: input.testId, level, caption, score: input.score, verdict: got === null ? 'no-answer' : ok ? 'correct' : 'wrong', compare: { kind: 'integer', want: exp, got, firstDiff: ok ? -1 : firstDiff, sameDigits: same, wrongDigits } };
  }
  if (exp && typeof exp === 'object' && !Array.isArray(exp) && 'start' in exp) {
    const key = exp as PlanKey;
    const moves = reply ? parsePlan(extractFinalAnswer(reply).answer) : typeof input.detail.extracted === 'string' ? parsePlan(input.detail.extracted) : [];
    const r = replayPlan(key, moves);
    const verdict: RungVerdict = !moves.length ? 'no-answer' : r.illegalAt >= 0 ? 'illegal' : !r.solved ? 'unsolved' : moves.length <= key.optimal ? 'optimal' : 'suboptimal';
    return {
      testId: input.testId,
      level,
      caption,
      score: input.score,
      verdict,
      compare: { kind: 'plan', rows: key.rows, cols: key.cols, start: key.start, optimal: key.optimal, optimalPlan: key.plan ?? [], moves, illegalAt: r.illegalAt, solved: r.solved, end: r.board },
    };
  }
  if (Array.isArray(exp) && exp.every((x) => typeof x === 'string')) {
    const want = exp as string[];
    const got = reply ? parseGrid(reply, want.length, want[0]!.length) : typeof input.detail.extracted === 'string' ? input.detail.extracted.split('/') : null;
    let wrong = 0;
    if (got) for (let r = 0; r < want.length; r++) for (let c = 0; c < want[r]!.length; c++) if (got[r]?.[c] !== want[r]![c]) wrong++;
    return { testId: input.testId, level, caption, score: input.score, verdict: !got ? 'no-answer' : wrong === 0 ? 'correct' : 'wrong', compare: { kind: 'grid', want, got, wrong } };
  }
  return null;
}

/** One plain-English headline for a rung (colour comes from the verdict). */
export function rungHeadline(m: RungModel, who = 'The model'): string {
  const c = m.compare;
  switch (m.verdict) {
    case 'correct':
      if (c.kind === 'grid') return `Level ${m.level}: all ${c.want.length * c.want[0]!.length} cells right`;
      if (c.kind === 'integer') return c.want.length > 1 ? `Level ${m.level}: exactly right, all ${c.want.length} digits` : `Level ${m.level}: exactly right`;
      return `Level ${m.level}: exactly right`;
    case 'optimal':
      return `Level ${m.level}: solved in the proven minimum of ${(c as PlanCompare).optimal} moves`;
    case 'suboptimal':
      return `Level ${m.level}: solved, but in ${(c as PlanCompare).moves.length} moves, not ${(c as PlanCompare).optimal}`;
    case 'illegal':
      return `Level ${m.level}: move ${(c as PlanCompare).illegalAt + 1} is illegal`;
    case 'unsolved':
      return `Level ${m.level}: ${(c as PlanCompare).moves.length} moves, but the puzzle is not solved`;
    case 'no-answer':
      return `Level ${m.level}: ${who} gave no readable answer`;
    case 'wrong':
      if (c.kind === 'grid') return `Level ${m.level}: ${c.wrong} of ${c.want.length * c.want[0]!.length} cells wrong`;
      if (c.kind === 'integer' && c.wrongDigits > 0 && c.wrongDigits <= 3) return `Level ${m.level}: ${c.wrongDigits} of ${c.want.length} digits wrong`;
      if (c.kind === 'integer') return c.firstDiff === 0 ? `Level ${m.level}: wrong from the very first digit` : c.firstDiff === 1 ? `Level ${m.level}: right for the first digit, then wrong` : `Level ${m.level}: right for ${c.firstDiff} digits, then wrong`;
      return `Level ${m.level}: wrong`;
  }
}
