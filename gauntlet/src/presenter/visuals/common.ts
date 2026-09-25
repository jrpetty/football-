/**
 * "Answer vs truth" case visuals: the shared input every visual reads, and
 * small helpers. Pure functions with no Node or DOM dependencies, shared by
 * the UI (Result Inspector, Presenter) and the unit tests.
 *
 * A visual only ever reads data that was recorded or published: the prompt
 * the model saw, its reply, the answer key, the scorer's detail and the
 * case's auditor notes. Parsers return null when anything is unexpected, so
 * the UI falls back to the plain view instead of guessing.
 */
import type { ScoreDetail, ResultStatus } from '../../core/types.ts';
import { extractFinalAnswer } from '../../core/extract.ts';

export interface CaseVisualInput {
  testId: string;
  caseId: string;
  /** Scorer type of the case (e.g. 'exact', 'constraints'), when known. */
  scorerType?: string;
  /** Test category (e.g. 'math'), when known. */
  category?: string;
  system?: string;
  /** User turns exactly as the model saw them (the first includes any preamble). */
  turns: string[];
  /** Model replies, one per user turn when recorded. The last one is scored. */
  replies: string[];
  expected?: unknown;
  notes?: string;
  detail: ScoreDetail;
  score: number | null;
  passed: boolean | null;
  status: ResultStatus | string;
  /** Model label (for headlines such as "Nova said 9"). */
  modelLabel?: string;
  /** Presentation-only fields from the test definition (never sent to the model). */
  lure?: string;
  displayAnswer?: string;
  answerWithinSec?: number;
}

/** Minimal slice of a stored case result the visuals need (CaseResult fits). */
export interface ResultLike {
  testId: string;
  caseId: string;
  status: ResultStatus | string;
  score: number | null;
  passed: boolean | null;
  scoreDetail?: ScoreDetail;
  transcript?: Array<{ label?: string; system?: string; messages: Array<{ role: string; content: string }>; response: string; judge?: boolean }>;
}

/** Minimal slice of a rendered test case (RenderedCase fits). */
export interface RenderedLike {
  caseId: string;
  system?: string;
  turns: string[];
  expected?: unknown;
  notes?: string;
}

/**
 * Build the visual input from a stored result and (optionally) the rendered
 * case from the current test file. The rendered case's key and notes are used
 * only when its prompt is exactly the prompt the model saw, so an edited test
 * file can never be passed off as the key of an older run.
 */
export function buildCaseInput(r: ResultLike, rendered?: RenderedLike | null, extra?: { scorerType?: string; category?: string; modelLabel?: string; lure?: string; displayAnswer?: string; answerWithinSec?: number }): CaseVisualInput {
  const calls = (r.transcript ?? []).filter((t) => !t.judge);
  const last = calls[calls.length - 1];
  const userTurns = last ? last.messages.filter((m) => m.role === 'user').map((m) => m.content) : [];
  const replies: string[] = [];
  for (const c of calls) replies.push(c.response);
  const turns = userTurns.length ? userTurns : rendered?.turns ?? [];
  const sameCase = !!rendered && rendered.caseId === r.caseId && (userTurns.length === 0 || (rendered.turns[0] ?? '') === (userTurns[0] ?? ''));
  const detail = r.scoreDetail ?? {};
  return {
    testId: r.testId,
    caseId: r.caseId,
    scorerType: extra?.scorerType,
    category: extra?.category,
    system: last?.system ?? (sameCase ? rendered?.system : undefined),
    turns,
    replies,
    expected: detail.expected !== undefined ? detail.expected : sameCase ? rendered?.expected : undefined,
    notes: sameCase ? rendered?.notes : undefined,
    detail,
    score: r.score,
    passed: r.passed,
    status: r.status,
    modelLabel: extra?.modelLabel,
    lure: sameCase ? extra?.lure : undefined,
    displayAnswer: sameCase ? extra?.displayAnswer : undefined,
    answerWithinSec: extra?.answerWithinSec,
  };
}

/** The scored (final) reply, or ''. */
export function finalReply(input: CaseVisualInput): string {
  return input.replies[input.replies.length - 1] ?? '';
}

/** The last user turn (the one the scored reply answers). */
export function finalTurn(input: CaseVisualInput): string {
  return input.turns[input.turns.length - 1] ?? '';
}

/** The answer the scorer read: detail.extracted, else the FINAL ANSWER line of the reply. */
export function statedAnswer(input: CaseVisualInput): { answer: string; formatOk: boolean } | null {
  if (typeof input.detail.extracted === 'string') return { answer: input.detail.extracted, formatOk: input.detail.formatOk !== false };
  const reply = finalReply(input);
  if (!reply.trim()) return null;
  return extractFinalAnswer(reply);
}

export const alnum = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, '');

const BOILERPLATE = [
  /When you are finished, write your final answer on its own line, exactly in the form:\s*FINAL ANSWER: <answer>/gi,
  /Give exactly one answer\.?/gi,
  /If you give more than one answer, it will be marked wrong\.?/gi,
  /Give (?:the|your) (?:final )?answer as [^\n]*/gi,
];

/** A prompt as a viewer should read it: answer-format boilerplate removed, whitespace tidied. */
export function viewerText(prompt: string): string {
  let q = prompt;
  for (const re of BOILERPLATE) q = q.replace(re, '');
  return q
    .split('\n')
    .map((l) => l.replace(/[ \t]+$/g, ''))
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

/** Split "a, b and c" / "a, b, c" into items. */
export function splitList(s: string): string[] {
  return s
    .replace(/\.$/, '')
    .split(/,\s*|\s+and\s+/)
    .map((x) => x.trim())
    .filter(Boolean);
}

const NUM_WORDS: Record<string, number> = { one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10, eleven: 11, twelve: 12 };

/** "three" → 3, "12" → 12, else null. */
export function numberWord(s: string): number | null {
  const t = s.trim().toLowerCase();
  if (/^\d+$/.test(t)) return Number(t);
  return NUM_WORDS[t] ?? null;
}

/** Parse a number the way a person writes it ("1,234.50", "$26.52"), or null. */
export function plainNumber(s: string): number | null {
  const m = s.replace(/[−–]/g, '-').replace(/,(?=\d{3}\b)/g, '').match(/-?\d+(?:\.\d+)?/);
  if (!m) return null;
  const v = Number(m[0]);
  return Number.isFinite(v) ? v : null;
}
