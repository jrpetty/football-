/**
 * Mock mode for "Watch it think": makes the simulated live run convincing.
 * The streamed text agrees with the verdict the case will get, reasoning
 * models "think" before they type, and graded answers carry the same kind of
 * summary the real grader writes ("Answered "0.5" · expected "0.375""), so the
 * commentary can say what the model said and what the answer was.
 */
import type { CaseResultLite, TestDefinition } from '../types.ts';
import { responseFor, rngFrom } from './fixtures.ts';

/** Reasoning-style working per test (made-up demo text, like everything in mock mode). */
const WORKING: Record<string, string[]> = {
  'reasoning.knights-knaves': [
    'Knights always tell the truth; knaves always lie. Try each assignment in turn.',
    'Suppose A is a knight. Then "B is a knave" is true, so B is a knave.',
    'B lies, so "C and A are the same type" is false: C is not a knight like A, so C is a knave.',
    'But C says "I am a knight" — a knave would say that too, so no contradiction yet.',
    'Now suppose A is a knave instead. Then B is a knight, and B’s statement must hold…',
    'Checking every case against all three statements, only one option survives.',
  ],
  'math.probability-traps': [
    'Careful — this is the kind of question where intuition misleads.',
    'Define the sample space explicitly before counting anything.',
    'Condition on the information we are actually given, not on what it seems to say.',
    'Count favourable outcomes over the conditioned space, then simplify the fraction.',
    'Sanity check with a quick mental simulation: the number should be below one half.',
  ],
  'math.competition-mix': [
    'Set up the counting carefully and look for a symmetry to exploit.',
    'Total paths first, then subtract the ones that pass through the forbidden point.',
    'Use the multiplication principle on each half of the route.',
    'Double-check the arithmetic: C(12,6) = 924, and the forbidden routes are C(6,3)².',
  ],
};

/** A plausible wrong answer for a question with a known key (the classic traps first). */
function wrongAnswer(expected: unknown, scorer: string, seed: string): string {
  const r = rngFrom(`wrong|${seed}`);
  const exp = Array.isArray(expected) ? expected[0] : expected;
  if (scorer === 'choice') return r.pick(['A', 'B', 'C', 'D'].filter((x) => x !== String(exp)));
  if (typeof exp === 'number') {
    if (!Number.isInteger(exp)) return r.pick(['0.5', '0.333', (Math.round(exp * 1.5 * 1000) / 1000).toString()]);
    return String(r.pick([exp + 1, exp - 1, exp * 2, Math.round(exp * 0.9)]));
  }
  return '11';
}

const BINARY = new Set(['exact', 'number', 'choice']);

function scorerOf(t: TestDefinition, caseId: string): string {
  if (t.kind !== 'prompt') return 'program';
  return t.cases.find((c) => c.id === caseId)?.scorer?.type ?? t.scorer.type;
}

/** The text a model "types" for this case, agreeing with the verdict it will get. */
export function liveAnswerText(t: TestDefinition, caseId: string, lite: CaseResultLite | null, seed: string): string {
  const score = lite?.score ?? 0;
  const base = responseFor(t, caseId, score);
  if (t.kind !== 'prompt') return base;
  const tc = t.cases.find((c) => c.id === caseId);
  const type = scorerOf(t, caseId);
  const working = WORKING[t.id];
  if (!BINARY.has(type) || tc?.expected === undefined) return base;
  const exp = Array.isArray(tc.expected) ? tc.expected[0] : tc.expected;
  const final = score >= 0.5 ? String(exp) : wrongAnswer(tc.expected, type, seed);
  const body = working ? working.slice(0, 3 + (seed.length % Math.max(1, working.length - 2))).join('\n\n') : base.replace(/\n*FINAL ANSWER:.*$/s, '');
  return `${body}\n\nFINAL ANSWER: ${final}`;
}

/** Finish a simulated case: the real grader's summary wording, and the clock the viewer actually saw. */
export function finalizeLive(lite: CaseResultLite, t: TestDefinition, text: string, startedMs: number): CaseResultLite {
  const now = Date.now();
  const out: CaseResultLite = { ...lite, startedAt: new Date(startedMs).toISOString(), finishedAt: new Date(now).toISOString(), metrics: { ...lite.metrics, wallMs: now - startedMs } };
  if (t.kind !== 'prompt' || lite.status !== 'ok' || typeof lite.score !== 'number') return out;
  const tc = t.cases.find((c) => c.id === lite.caseId);
  const type = scorerOf(t, lite.caseId);
  if (!BINARY.has(type) || tc?.expected === undefined) return out;
  const said = /FINAL ANSWER:\s*(.+)\s*$/.exec(text)?.[1]?.trim() ?? '';
  const exp = String(Array.isArray(tc.expected) ? tc.expected[0] : tc.expected);
  const ok = lite.score >= 0.5;
  out.summary = type === 'choice' ? (ok ? `Correct: ${said}` : `Chose ${said} · expected ${exp}`) : ok ? `Correct: "${said}"` : `Answered "${said}" · expected "${exp}"`;
  return out;
}

/** Ticks (110 ms each) a model spends "thinking" before its first word. */
export function thinkTicks(contestantId: string, u: number): number {
  if (/reasoner|kite/.test(contestantId)) return 12 + Math.round(u * 10);
  if (/ultra|atlas-4-ultra/.test(contestantId)) return 5 + Math.round(u * 5);
  if (/random|baseline/.test(contestantId)) return 0;
  return 1 + Math.round(u * 4);
}
