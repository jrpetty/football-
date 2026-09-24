/**
 * Mock-mode fixtures for "Can It Be Fooled?": the real trick test files, a
 * completed demo run, and a deterministic answer model so every model gives
 * a believable one-line answer (the right one, or the tempting wrong one),
 * slow thinkers sometimes run out of time on the 30-second lightning round,
 * and the Presenter's highlight slides have real disagreements to show.
 */
import modifiedClassics from '../../../tests/trick/modified-classics.json';
import falsePremise from '../../../tests/trick/false-premise.json';
import lightningTraps from '../../../tests/trick/lightning-traps.json';
import type { CaseResultLite, PromptTest, PromptTestCase, SuiteView, TestDefinition } from '../types.ts';
import type { RunSpec } from './fixtures.ts';

export const TRICK_TESTS = [modifiedClassics, falsePremise, lightningTraps] as unknown as PromptTest[];

export const TRICK_SUITE: SuiteView = {
  id: 'trick',
  version: '1.0.0',
  name: 'Can It Be Fooled?',
  description: 'Short, punchy trick questions made for YouTube Shorts: famous riddles with one detail changed, false premises and 30-second lightning traps. Graded exactly, no judges.',
  tests: TRICK_TESTS.map((t) => ({ id: t.id })),
  repeats: 3,
  fingerprint: '7f3c1a9e22d4',
  testCount: TRICK_TESTS.length,
};

export const TRICK_RUN_SPEC: RunSpec = {
  id: 'run-2026-09-22-trick',
  name: 'Can It Be Fooled? · Shorts batch',
  status: 'completed',
  contestantIds: ['meridian-atlas-4-ultra', 'kestrel-kite-reasoner', 'helios-nova-3-pro', 'obsidian-sable-large', 'helios-quill-flash', 'random-baseline'],
  testIds: TRICK_TESTS.map((t) => t.id),
  repeats: 3,
  suiteId: 'trick',
  createdAt: '2026-09-22T14:05:00Z',
  notes: 'Three trick tests for this week’s Shorts. Open in the Presenter; add ?vertical=1 for the 1080×1920 cut.',
};

/** How easily each demo model is fooled (higher = harder to fool). */
const SHARPNESS: Record<string, number> = {
  'meridian-atlas-4-ultra': 0.86,
  'kestrel-kite-reasoner': 0.9,
  'helios-nova-3-pro': 0.74,
  'obsidian-sable-large': 0.55,
  'helios-quill-flash': 0.45,
  'manual-orbit-chat': 0.7,
  'meridian-atlas-4-mini': 0.6,
};
/** Median answer time (ms) of each demo model on a trick question. */
const SPEED: Record<string, number> = {
  'meridian-atlas-4-ultra': 9000,
  'kestrel-kite-reasoner': 21000,
  'helios-nova-3-pro': 5200,
  'obsidian-sable-large': 1900,
  'helios-quill-flash': 1100,
  'random-baseline': 180,
};

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0) / 4294967296;
}

function isTrick(t: TestDefinition): t is PromptTest {
  return t.kind === 'prompt' && t.category === 'trick';
}

/** The one-line answer a model states: the right one, or the tempting wrong one (as the model would write it). */
export function trickAnswer(t: PromptTest, c: PromptTestCase, right: boolean): string {
  const sc = c.scorer ?? t.scorer;
  if (right) {
    if (sc.type === 'regex') return c.displayAnswer ?? '';
    return String(Array.isArray(c.expected) ? c.expected[0] : c.expected);
  }
  const lure = c.lure ?? '?';
  if (sc.type === 'choice') return lure.match(/\(([A-Z])\)/)?.[1] ?? 'A';
  if (sc.type === 'number') return lure.match(/-?\d+(?:\.\d+)?/)?.[0] ?? lure;
  return lure.replace(/\s*\(.*$/, '').trim() || lure;
}

/** Full mock reply text for the Result Inspector (consistent with the lite row). */
export function trickResponse(t: TestDefinition, caseId: string, score: number | null): string | null {
  if (!isTrick(t)) return null;
  const c = t.cases.find((x) => x.id === caseId);
  if (!c) return null;
  return `FINAL ANSWER: ${trickAnswer(t, c, (score ?? 0) >= 1)}`;
}

/** Replace the generic mock outcome of a trick case with a believable one. Other tests pass through unchanged. */
export function decorateTrick(t: TestDefinition, lite: CaseResultLite): CaseResultLite {
  if (!isTrick(t)) return lite;
  const c = t.cases.find((x) => x.id === lite.caseId);
  if (!c) return lite;
  const id = lite.contestantId;
  const within = c.answerWithinSec ?? t.answerWithinSec ?? null;
  const speed = SPEED[id] ?? 6000;
  const responseMs = Math.round(speed * (0.6 + hash(`${lite.key}|t`) * 0.9));

  if (id === 'random-baseline') {
    return { ...lite, status: 'ok', score: 0, passed: false, summary: 'Answered "37" · expected something else', scoreDetail: { extracted: String(Math.floor(hash(lite.key) * 100)), formatOk: true, ...(within ? { timeLimitSec: within, responseMs } : {}) }, error: undefined };
  }
  if (within && responseMs > within * 1000) {
    return { ...lite, status: 'timeout', score: 0, passed: false, summary: `Out of time: no answer within ${within} s`, scoreDetail: { outOfTime: true, timeLimitSec: within, responseMs: within * 1000, formatOk: false }, error: undefined };
  }
  // How tempting the trap is (shared by every model) and how sharp this model is on this case.
  const trap = hash(`trap|${t.id}|${c.id}`);
  const p = Math.max(0.03, Math.min(0.98, (SHARPNESS[id] ?? 0.6) * 1.15 - trap * 0.75 + 0.12));
  const u = hash(`${id}|${t.id}|${c.id}`) * 0.8 + hash(`${lite.key}|r`) * 0.2;
  const right = u < p;
  const answer = trickAnswer(t, c, right);
  return {
    ...lite,
    status: 'ok',
    score: right ? 1 : 0,
    passed: right,
    summary: right ? `Correct: "${answer}"` : `Answered "${answer}" · expected "${c.displayAnswer ?? String(c.expected)}"`,
    scoreDetail: { extracted: answer, formatOk: true, ...(within ? { timeLimitSec: within, responseMs } : {}) },
    metrics: { ...lite.metrics, wallMs: responseMs + 40, ttftMs: Math.min(responseMs, lite.metrics.ttftMs ?? responseMs) },
    error: undefined,
  };
}
