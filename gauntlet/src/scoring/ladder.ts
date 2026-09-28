/**
 * The Horizon tier's scorer (`{ type: 'ladder', answer }`). Every Horizon case is one rung of a
 * difficulty ladder; this module grades a single rung. Three answer shapes:
 *
 *  - `integer`: an exact whole number of any size (compared as digit strings, so 24-digit keys are exact).
 *  - `plan`:    a sliding-tile plan (tile numbers in order). The plan is replayed on the start board:
 *               the minimum length scores 1, a longer plan that really solves the puzzle scores
 *               0.25 × minimum / length, anything illegal or unfinished scores 0.
 *  - `grid`:    a whole nonogram grid (# filled, . empty). All or nothing.
 *
 * Parsing is lenient about decoration (markdown, spaces, thousands separators) and strict about content.
 */
import type { ScoreDetail } from '../core/types.ts';
import { extractFinalAnswer } from '../core/extract.ts';

export type LadderAnswer = 'integer' | 'plan' | 'grid';

export interface PlanKey {
  rows: number;
  cols: number;
  /** Row-major start board, 0 = the empty square. */
  start: number[];
  /** Proven minimum number of moves. */
  optimal: number;
  /** One optimal plan (tile numbers), for displays. */
  plan?: number[];
}

export interface LadderOutcome {
  score: number;
  passed: boolean;
  summary: string;
  detail: ScoreDetail;
}

/** Share of full marks a correct but non-optimal plan can earn at most. */
export const SUBOPTIMAL_PLAN_CREDIT = 0.25;

function quote(s: string, max = 48): string {
  const one = s.replace(/\s+/g, ' ').trim();
  return one.length > max ? `"${one.slice(0, max - 1)}…"` : `"${one}"`;
}

/**
 * Digits of a whole-number answer, or null. Accepts "1,234,567", "1 234 567", "1_234", a trailing period,
 * a unit word ("38 ways") and a stated equation ("x = 42": the part after the last "="). Refuses answers
 * with two separate numbers, decimals and negative numbers.
 */
export function parseBigInteger(answer: string): string | null {
  let a = answer.includes('=') ? answer.slice(answer.lastIndexOf('=') + 1) : answer;
  a = a.replace(/(\d)[\s,_'’](?=\d{3}(?!\d))/g, '$1').replace(/(\d)[\s,_'’](?=\d)/g, '$1');
  if (/\d\.\d|[-−]\s*\d/.test(a)) return null;
  const runs = a.match(/\d+/g) ?? [];
  if (runs.length !== 1) return null;
  return runs[0]!.replace(/^0+(?=\d)/, '');
}

/** Tile numbers of a plan: every integer in the answer line, in order. Anything else in the line is ignored. */
export function parsePlan(answer: string): number[] {
  return (answer.match(/\d+/g) ?? []).map(Number);
}

/** Replays a plan. Returns the index of the first illegal move (or -1) and whether the goal is reached. */
export function replayPlan(key: PlanKey, plan: number[]): { illegalAt: number; solved: boolean; board: number[] } {
  const { rows, cols } = key;
  const board = key.start.slice();
  let illegalAt = -1;
  for (let i = 0; i < plan.length; i++) {
    const z = board.indexOf(0);
    const t = board.indexOf(plan[i]!);
    const adjacent = t >= 0 && plan[i] !== 0 && Math.abs(Math.floor(z / cols) - Math.floor(t / cols)) + Math.abs((z % cols) - (t % cols)) === 1;
    if (!adjacent) {
      illegalAt = i;
      break;
    }
    board[z] = plan[i]!;
    board[t] = 0;
  }
  const n = rows * cols;
  const solved = illegalAt < 0 && board.every((v, i) => v === (i === n - 1 ? 0 : i + 1));
  return { illegalAt, solved, board };
}

const FILLED = new Set(['#', 'X', 'x', '1', '■', '█', '●']);
const EMPTY = new Set(['.', '0', '-', '□', '·', '_', 'o', 'O']);

/**
 * Reads a nonogram grid written after the last "FINAL ANSWER:" marker: rows one per line, or on one line
 * separated by "/". Spaces, pipes and backticks inside a row are ignored. Returns null unless exactly
 * `rows` rows of `cols` cells are found.
 */
export function parseGrid(response: string, rows: number, cols: number): string[] | null {
  const idx = response.search(/final\s+answer\s*[:：]?(?![\s\S]*final\s+answer)/i);
  if (idx < 0) return null;
  const tail = response.slice(idx).replace(/^final\s+answer\s*[:：]?/i, '');
  const out: string[] = [];
  for (const rawLine of tail.split(/\n|\//)) {
    const line = rawLine.replace(/[\s|`*]/g, '');
    if (!line) continue;
    if (line.length !== cols || ![...line].every((ch) => FILLED.has(ch) || EMPTY.has(ch))) {
      if (out.length) break; // the grid ended
      continue; // text before the grid
    }
    out.push([...line].map((ch) => (FILLED.has(ch) ? '#' : '.')).join(''));
    if (out.length === rows) break;
  }
  return out.length === rows ? out : null;
}

export function scoreLadder(kind: LadderAnswer, expected: unknown, response: string): LadderOutcome {
  if (kind === 'integer') {
    const { answer, formatOk } = extractFinalAnswer(response);
    const want = String(expected);
    if (/\b(or|either|maybe|possibly|between)\b|±|\+\/-/i.test(answer) && (answer.match(/\d+/g) ?? []).length >= 2) {
      return { score: 0, passed: false, summary: `Hedged answer ${quote(answer)} (multiple answers are marked wrong)`, detail: { extracted: answer, expected: want, formatOk, hedged: true } };
    }
    const got = parseBigInteger(answer);
    const ok = got !== null && got === want;
    return {
      score: ok ? 1 : 0,
      passed: ok,
      summary: ok ? `Correct: ${want}` : got === null ? `No whole number in ${quote(answer)} · expected ${want}` : `Answered ${quote(got)} · expected ${want}`,
      detail: { extracted: got ?? answer, expected: want, formatOk },
    };
  }
  if (kind === 'plan') {
    const key = expected as PlanKey;
    const { answer, formatOk } = extractFinalAnswer(response);
    const plan = parsePlan(answer);
    const base = { extracted: plan.join(' '), expected: key, formatOk, optimal: key.optimal, moves: plan.length };
    if (!plan.length) return { score: 0, passed: false, summary: `No plan found in ${quote(answer)}`, detail: { ...base, verdict: 'no-plan' } };
    const r = replayPlan(key, plan);
    if (r.illegalAt >= 0) {
      return {
        score: 0,
        passed: false,
        summary: `Illegal move ${r.illegalAt + 1} (tile ${plan[r.illegalAt]} is not next to the gap) · minimum is ${key.optimal}`,
        detail: { ...base, verdict: 'illegal', illegalAt: r.illegalAt },
      };
    }
    if (!r.solved) return { score: 0, passed: false, summary: `${plan.length} legal moves, but the board is not solved · minimum is ${key.optimal}`, detail: { ...base, verdict: 'unsolved' } };
    if (plan.length <= key.optimal) return { score: 1, passed: true, summary: `Optimal: solved in ${plan.length} moves (the proven minimum)`, detail: { ...base, verdict: 'optimal' } };
    const score = Math.round(SUBOPTIMAL_PLAN_CREDIT * (key.optimal / plan.length) * 10000) / 10000;
    return { score, passed: false, summary: `Solved in ${plan.length} moves · minimum is ${key.optimal} (partial credit)`, detail: { ...base, verdict: 'suboptimal' } };
  }
  // grid
  const want = expected as string[];
  const rows = want.length;
  const cols = want[0]?.length ?? 0;
  const got = parseGrid(response, rows, cols);
  if (!got) return { score: 0, passed: false, summary: `No ${rows}×${cols} grid after FINAL ANSWER`, detail: { expected: want, formatOk: false, verdict: 'no-grid' } };
  let wrong = 0;
  for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) if (got[r]![c] !== want[r]![c]) wrong++;
  const ok = wrong === 0;
  return {
    score: ok ? 1 : 0,
    passed: ok,
    summary: ok ? `Correct: all ${rows * cols} cells right` : `${wrong} of ${rows * cols} cells wrong (the whole grid must be right)`,
    detail: { extracted: got.join('/'), expected: want, formatOk: true, wrongCells: wrong, verdict: ok ? 'correct' : 'wrong' },
  };
}
