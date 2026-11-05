/**
 * The Level close-up: one level of a difficulty ladder told as three beats (the question, the model's answer,
 * the result). Pure functions shared by the UI (ui/src/components/viz/LevelCloseUp.tsx) and the unit tests.
 *
 * Every parser reads only what was published or recorded (the prompt, the answer key, the auditor notes, the
 * recorded reply and the scorer's detail) and returns null when anything is unexpected, so the close-up falls
 * back to the plain text instead of guessing. "Question" models never read the answer key.
 */
import { extractFinalAnswer } from '../../core/extract.ts';
import { passesRung } from '../../core/ladders.ts';
import { parseFraction } from '../../scoring/ladder.ts';
import { finalReply, statedAnswer, splitList, type CaseVisualInput } from './common.ts';
import { parseGridCategories, positionLabels, type GridCategory, type GridVisual } from './grid.ts';
import type { IslandVisual } from './truth.ts';

// ───────────────────────────── which drawing ─────────────────────────────

export type CloseUpKind = 'grid' | 'islanders' | 'pegs' | 'snakes' | 'engine' | 'business' | 'sql' | 'horizon' | 'text';

export function closeUpKind(testId: string): CloseUpKind {
  switch (testId) {
    case 'reasoning.grid-ladder':
      return 'grid';
    case 'reasoning.truth-ladder':
      return 'islanders';
    case 'reasoning.plans-ladder':
      return 'pegs';
    case 'math.exact-ladder':
      return 'snakes';
    case 'coding.engineering-ladder':
      return 'engine';
    case 'work.sql-ladder':
      return 'sql';
  }
  if (testId.startsWith('work.')) return 'business';
  if (testId.startsWith('horizon.')) return 'horizon';
  return 'text';
}

/** "L06" for level 6. */
export function levelCaseId(level: number): string {
  return `L${String(level).padStart(2, '0')}`;
}

/** The visual input of a level with no answer (question-only views and "Reveal the answer"). */
export function questionInput(testId: string, caseId: string, rendered: { turns: string[]; system?: string; expected?: unknown; notes?: string } | null | undefined, extra?: { scorerType?: string; category?: string }): CaseVisualInput | null {
  if (!rendered || !rendered.turns.length) return null;
  return {
    testId,
    caseId,
    scorerType: extra?.scorerType,
    category: extra?.category,
    system: rendered.system,
    turns: rendered.turns,
    replies: [],
    expected: rendered.expected,
    notes: rendered.notes,
    detail: {},
    score: null,
    passed: null,
    status: 'question',
  };
}

// ───────────────────────────── the verdict ─────────────────────────────

export interface LevelVerdict {
  tone: 'pass' | 'fail' | 'partial' | 'none';
  mark: string;
  /** "Passed", "Failed", "88% — needs 90%", "Not scored". */
  title: string;
  /** "Full marks" / "94% of the marks" etc., or null. */
  scoreText: string | null;
}

const pct = (x: number) => Math.round(x * 100);

export function levelVerdict(score: number | null, passMark: number, status?: string): LevelVerdict {
  if (status === 'error' || status === 'cancelled' || status === 'skipped' || score === null) {
    return { tone: 'none', mark: '–', title: status === 'error' ? 'No result (the call failed)' : 'Not scored', scoreText: null };
  }
  const scoreText = score >= 0.9995 ? 'Full marks' : score <= 0 ? 'No marks' : `${pct(score)}% of the marks`;
  if (passesRung(score, passMark)) return { tone: 'pass', mark: '✓', title: 'Passed', scoreText };
  if (passMark < 1 && score > 0) return { tone: 'partial', mark: '◐', title: `${pct(score)}% — needs ${pct(passMark)}%`, scoreText };
  return { tone: 'fail', mark: '✕', title: 'Failed', scoreText };
}

/** The pass rule in words ("full marks needed" / "90% needed"). */
export function passRuleText(passMark: number): string {
  return passMark >= 1 ? 'full marks needed' : `${pct(passMark)}% needed`;
}

/** ", so the level is passed" / ", so the level counts as failed (full marks needed)". */
function soWhat(score: number | null, passMark: number, needs = passRuleText(passMark)): string {
  if (score === null) return '';
  return passesRung(score, passMark) ? ', so the level is passed.' : `, so the level counts as failed (${needs}).`;
}

// ───────────────────────────── Deduction Grid ─────────────────────────────

export interface GridClue {
  n: number;
  text: string;
  /** Either/or, exactly-k-of, if-then, between and ordering-chain clues tie several facts together. */
  compound: boolean;
}

export interface GridQuestion {
  setting: string;
  positions: string[];
  noun: string;
  categories: GridCategory[];
  /** Categories the question asks for (indexes into categories). */
  asked: number[];
  clues: GridClue[];
  question: string;
}

/** Clues that tie three or more facts together (the generator's "compound" kinds: either/or, exactly-k, if-then, between, chains). */
const COMPOUND = /^(?:Exactly (?:one|two) of these|If |Of |Either |From left to right)|\(a\)|somewhere between/;

export function gridQuestion(prompt: string): GridQuestion | null {
  const categories = parseGridCategories(prompt);
  if (!categories) return null;
  const n = categories[0]!.values.length;
  const question = (prompt.match(/Question:\s*([^\n]+)/) ?? [])[1] ?? '';
  const { labels, noun } = positionLabels(prompt, question, n);
  const block = prompt.split(/\nClues:\s*\n/)[1]?.split(/\n\s*\n/)[0] ?? '';
  const clues: GridClue[] = [];
  for (const line of block.split('\n')) {
    const m = line.match(/^\s*(\d+)\.\s+(.+)$/);
    if (m) clues.push({ n: Number(m[1]), text: m[2]!.trim(), compound: COMPOUND.test(m[2]!) });
  }
  if (!clues.length) return null;
  const q = question.toLowerCase();
  const asked = categories.map((c, i) => (q.includes(c.name.toLowerCase().replace(/\s*\(.*\)$/, '')) ? i : -1)).filter((i) => i >= 0);
  const setting = (prompt.split(/\n/)[0] ?? '').split(/(?<=\.)\s/)[0] ?? '';
  return { setting, positions: labels, noun, categories, asked, clues, question };
}

export function gridSentence(v: GridVisual, score: number | null, passMark: number): string {
  if (!v.answered) return 'Gave no grid we could read, so the level counts as failed.';
  const names = v.asked.map((ci) => v.categories[ci]!.name.toLowerCase());
  if (v.right === v.total) return `Put every one of the ${v.total} asked values (${names.join(' and ')}) in the right ${v.noun}${soWhat(score, passMark)}`;
  const wrong = v.total - v.right;
  let how: string;
  if (v.swaps.length && v.swaps.length * 2 >= wrong) {
    const parts = v.swaps.slice(0, 2).map((s) => {
      const ci = v.categories.findIndex((c) => c.name === s.category);
      const a = v.rows[ci]?.[s.a]?.model ?? '';
      const b = v.rows[ci]?.[s.b]?.model ?? '';
      return a && b ? `swapped ${a} and ${b}` : `swapped two ${s.category.toLowerCase()}`;
    });
    how = parts.join(' and ') + (v.swaps.length > 2 ? ` (and ${v.swaps.length - 2} more swaps)` : '');
  } else {
    const first: string[] = [];
    v.asked.forEach((ci) =>
      v.rows[ci]!.forEach((cell, i) => {
        if (cell.ok === false && first.length < 2) first.push(cell.model ? `put ${cell.model} where ${cell.truth} belongs (${v.noun} ${v.positions[i]})` : `left ${v.noun} ${v.positions[i]} empty`);
      }),
    );
    how = first.join(' and ') + (wrong > first.length ? ` (${wrong} cells wrong in all)` : '');
  }
  return `Got ${v.right} of ${v.total} cells right, but ${how}${soWhat(score, passMark)}`;
}

// ───────────────────────────── Knights & Knaves ─────────────────────────────

export function islandSentence(v: IslandVisual, score: number | null, passMark: number): string {
  if (!v.answered) return 'Gave no verdict we could read, so the level counts as failed.';
  const wrong = v.people.filter((p) => !p.ok);
  const n = v.people.length;
  if (!wrong.length) return `Named the type of all ${n} islanders correctly${soWhat(score, passMark, 'every islander needed')}`;
  const shown = wrong.slice(0, 2).map((p) => (p.model ? `called ${p.name} a ${p.model} (${p.name} is a ${p.truth})` : `gave no type for ${p.name} (a ${p.truth})`));
  const more = wrong.length > 2 ? ` and misjudged ${wrong.length - 2} more` : '';
  return `Named ${v.right} of ${n} islanders correctly, but ${shown.join(' and ')}${more}${soWhat(score, passMark, 'every islander needed')}`;
}

// ───────────────────────────── Shortest Plans (pegs) ─────────────────────────────

export function pegsSentence(claimed: number | null, optimum: number, score: number | null, passMark: number): string {
  if (claimed === null) return 'Gave no number of moves, so the level counts as failed.';
  if (claimed === optimum) return `Said ${claimed} moves: exactly the shortest possible plan${soWhat(score, passMark, 'only the exact minimum scores')}`;
  if (claimed > optimum) return `Said ${claimed} moves, but the shortest plan takes ${optimum}${soWhat(score, passMark, 'only the exact minimum scores')}`;
  return `Said ${claimed} moves, but no plan that short exists: the minimum is ${optimum}${soWhat(score, passMark, 'only the exact minimum scores')}`;
}

// ───────────────────────────── Exact Maths: snakes and ladders ─────────────────────────────

export interface SnakesBoard {
  /** The finishing square (reach it or pass it to win). */
  finish: number;
  /** Spinner numbers 1..faces. */
  faces: number;
  ladders: Array<[number, number]>;
  snakes: Array<[number, number]>;
  traps: number[];
}

function pairs(line: string | undefined): Array<[number, number]> | null {
  if (line === undefined) return null;
  if (/^\s*none\.?\s*$/i.test(line)) return [];
  const out = [...line.matchAll(/(\d+)\s*(?:→|->)\s*(\d+)/g)].map((m) => [Number(m[1]), Number(m[2])] as [number, number]);
  return out.length ? out : null;
}

export function snakesBoard(prompt: string): SnakesBoard | null {
  const track = prompt.match(/track of squares numbered 0 to (\d+)/);
  const spin = prompt.match(/spinner numbered 1 (?:to|and) (\d+)/);
  if (!track || !spin) return null;
  const ladders = pairs(prompt.match(/^- Ladders[^:]*:\s*(.+)$/m)?.[1]);
  const snakes = pairs(prompt.match(/^- Snakes[^:]*:\s*(.+)$/m)?.[1]);
  const trapLine = prompt.match(/^- Traps:\s*(.+)$/m)?.[1];
  if (!ladders || !snakes || trapLine === undefined) return null;
  const traps = /^\s*none/i.test(trapLine) ? [] : [...trapLine.matchAll(/\d+/g)].map((m) => Number(m[0]));
  const finish = Number(track[1]);
  if (!(finish > 1 && finish < 400)) return null;
  return { finish, faces: Number(spin[1]), ladders, snakes, traps };
}

export interface Fraction {
  p: bigint;
  q: bigint;
}

/** `digits` decimal places of p/q (truncated), e.g. "0.227133". */
export function fractionDecimal(f: Fraction, digits = 8): string {
  if (f.q === 0n) return '—';
  const neg = f.p < 0n !== f.q < 0n;
  let p = f.p < 0n ? -f.p : f.p;
  const q = f.q < 0n ? -f.q : f.q;
  const whole = p / q;
  p = p % q;
  let s = '';
  for (let i = 0; i < digits; i++) {
    p *= 10n;
    s += String(p / q);
    p = p % q;
  }
  return `${neg ? '-' : ''}${whole}.${s}`;
}

function gcd(a: bigint, b: bigint): bigint {
  a = a < 0n ? -a : a;
  b = b < 0n ? -b : b;
  while (b) [a, b] = [b, a % b];
  return a;
}

export function lowestTerms(f: Fraction): Fraction {
  const g = gcd(f.p, f.q) || 1n;
  return { p: f.p / g, q: f.q / g };
}

export interface FractionCompare {
  key: Fraction;
  keyText: string;
  given: Fraction | null;
  givenText: string;
  /** The reply's final answer line as written. */
  stated: string;
  equal: boolean;
  keyDecimal: string;
  givenDecimal: string | null;
  /** Leading decimal places on which the two values agree. */
  sameDecimals: number;
}

export function fractionCompare(input: CaseVisualInput): FractionCompare | null {
  const want = typeof input.expected === 'string' ? input.expected : typeof input.detail.expected === 'string' ? input.detail.expected : null;
  const key = want ? parseFraction(want) : null;
  if (!key) return null;
  const reply = finalReply(input);
  const stated = reply ? extractFinalAnswer(reply).answer : typeof input.detail.extracted === 'string' ? input.detail.extracted : '';
  const given = stated ? parseFraction(stated) : null;
  const equal = !!given && given.p * key.q === key.p * given.q;
  const keyDecimal = fractionDecimal(key, 16);
  const givenDecimal = given ? fractionDecimal(given, 16) : null;
  let sameDecimals = 0;
  if (givenDecimal) {
    const a = keyDecimal.split('.')[1] ?? '';
    const b = givenDecimal.split('.')[1] ?? '';
    if (keyDecimal.split('.')[0] === givenDecimal.split('.')[0]) while (sameDecimals < a.length && a[sameDecimals] === b[sameDecimals]) sameDecimals++;
  }
  return { key, keyText: `${key.p}/${key.q}`, given, givenText: given ? `${given.p}/${given.q}` : '', stated, equal, keyDecimal, givenDecimal, sameDecimals };
}

export function snakesSentence(c: FractionCompare, score: number | null, passMark: number): string {
  if (!c.given) return c.stated ? `Answered “${c.stated.slice(0, 40)}”, which is not an exact fraction${soWhat(score ?? 0, passMark, 'only the exact fraction scores')}` : 'Gave no answer, so the level counts as failed.';
  if (c.equal) return `Gave exactly the right probability, about ${c.keyDecimal.slice(0, 8)}${soWhat(score, passMark, 'only the exact fraction scores')}`;
  const close = c.sameDecimals >= 3 ? `: right to ${c.sameDecimals} decimal places, but not the exact value` : '';
  // Enough decimals to show where the two values part.
  const n = Math.max(8, c.sameDecimals + 4);
  return `Gave a probability of about ${c.givenDecimal!.slice(0, n)}, but the true value is about ${c.keyDecimal.slice(0, n)}${close}${soWhat(score, passMark, 'only the exact fraction scores')}`;
}

// ───────────────────────────── Engineering: the array engine ─────────────────────────────

export interface EngineOp {
  level: number;
  name: string;
  /** e.g. ["chmax", l, r, x] */
  signature: string;
  meaning: string;
  query: boolean;
  /** Added at this very level. */
  isNew: boolean;
}

export interface EngineQuestion {
  level: number;
  functionName: string | null;
  ops: EngineOp[];
  maxN: string | null;
  maxOps: string | null;
  timeLimitSec: number | null;
  hiddenTests: number | null;
}

export function engineQuestion(prompt: string, notes?: string): EngineQuestion | null {
  const lv = prompt.match(/this task is level (\d+)/);
  if (!lv) return null;
  const level = Number(lv[1]);
  const ops: EngineOp[] = [];
  const re = /\*\*Level (\d+)\*\*\s*\n((?:- [^\n]+\n?)+)/g;
  for (const m of prompt.matchAll(re)) {
    const at = Number(m[1]);
    for (const line of m[2]!.split('\n')) {
      const b = line.match(/^- `(\[[^`]+\])`\s*(\(query\))?:\s*(.+)$/);
      if (!b) continue;
      const name = (b[1]!.match(/"([^"]+)"/) ?? [])[1] ?? b[1]!;
      ops.push({ level: at, name, signature: b[1]!, meaning: b[3]!.trim(), query: !!b[2], isNew: at === level });
    }
  }
  if (!ops.length) return null;
  const fn = (prompt.match(/function `(\w+)\(/) ?? [])[1] ?? null;
  const maxN = (prompt.match(/n is at most ([\d,]+)/) ?? [])[1] ?? null;
  const maxOps = (prompt.match(/at most ([\d,]+) operations/) ?? [])[1] ?? null;
  const t = prompt.match(/time limit of about (\d+) seconds/);
  const h = notes?.match(/(\d+) hidden tests/);
  return { level, functionName: fn, ops, maxN, maxOps, timeLimitSec: t ? Number(t[1]) : null, hiddenTests: h ? Number(h[1]) : null };
}

export interface EngineTestOutcome {
  index: number;
  name: string;
  status: 'pass' | 'fail' | 'timeout' | 'error';
}

export function engineSentence(tests: EngineTestOutcome[], score: number | null, passMark: number, loadError?: string | null): string {
  if (loadError) return 'The code did not even load, so every hidden test failed.';
  if (!tests.length) return 'No code we could run was found in the reply, so the level counts as failed.';
  const passed = tests.filter((t) => t.status === 'pass').length;
  if (passed === tests.length) return `Passed all ${tests.length} hidden tests, including the largest ones, in time${soWhat(score, passMark, 'every test needed')}`;
  const failed = tests.filter((t) => t.status !== 'pass');
  const slow = failed.filter((t) => t.status === 'timeout');
  let how: string;
  if (slow.length === failed.length) how = `${slow.length === 1 ? `‘${slow[0]!.name}’ ran` : `${slow.length} tests ran`} out of time`;
  else if (failed.length === 1) how = `failed ‘${failed[0]!.name}’`;
  else how = `failed ${failed.length}, starting with ‘${failed[0]!.name}’`;
  return `Passed ${passed} of ${tests.length} hidden tests, but ${how}${soWhat(score, passMark, 'every test needed')}`;
}

// ───────────────────────────── small helpers for the UI ─────────────────────────────

/** The model's stated final answer line, or ''. */
export function statedLine(input: CaseVisualInput): string {
  return statedAnswer(input)?.answer ?? '';
}

/** "Ada, Bruno and Cyra" → ['Ada', 'Bruno', 'Cyra'] (re-exported for the UI). */
export { splitList };
