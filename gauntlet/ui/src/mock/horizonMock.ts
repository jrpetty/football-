/**
 * Mock-mode fixtures for the Horizon tier (?mock=1): the five real Horizon tests (tests/horizon/*.json),
 * a completed demo run where the demo models climb to different heights, and scripted replies that are
 * graded by the REAL ladder scorer (src/scoring/ladder.ts). The demo models are fictional; their heights
 * are made up for the demo and say nothing about any real model (see docs/AUDIT.md for real calibration).
 */
import mindRunner from '../../../tests/horizon/mind-runner.json';
import modpow from '../../../tests/horizon/modpow-ladder.json';
import sliding from '../../../tests/horizon/sliding-ladder.json';
import nonogram from '../../../tests/horizon/nonogram-ladder.json';
import tilings from '../../../tests/horizon/tiling-count.json';
import type { CaseResult, CaseResultLite, PromptTest, SuiteView, TestDefinition, TranscriptEntry } from '../types.ts';
import type { RunSpec } from './fixtures.ts';
import { scoreLadder, type LadderAnswer, type PlanKey } from '../../../src/scoring/ladder.ts';
import { ladderLevel } from '../../../src/presenter/visuals/horizon.ts';

export const HORIZON_TESTS: PromptTest[] = [mindRunner, modpow, sliding, nonogram, tilings] as unknown as PromptTest[];
const IDS = new Set(HORIZON_TESTS.map((t) => t.id));

export const HORIZON_RUN_SPEC: RunSpec = {
  id: 'run-2026-09-28-horizon',
  name: 'Horizon ladders · demo climb',
  status: 'completed',
  contestantIds: ['kestrel-kite-reasoner', 'meridian-atlas-4-ultra', 'helios-nova-3-pro', 'helios-quill-flash', 'random-baseline'],
  testIds: HORIZON_TESTS.map((t) => t.id),
  repeats: 1,
  suiteId: 'horizon',
  createdAt: '2026-09-28T09:00:00Z',
  notes: 'Demo data: fictional models climbing the five Horizon ladders. Open a cell for the ladder and each level’s answer vs truth; the Presenter has a “How far up the ladder” slide per test.',
};

export const HORIZON_SUITE: SuiteView = {
  id: 'horizon',
  version: '1.0.0',
  name: 'Horizon: Tests Built for Future Models',
  description: 'Five ten-level difficulty ladders built so today’s best models stall low and future ones can climb. Every answer machine-checked against a double-proven key.',
  tests: HORIZON_TESTS.map((t) => ({ id: t.id })),
  repeats: 1,
  fingerprint: 'h0r1z0n0f00d',
  testCount: HORIZON_TESTS.length,
};

/** Demo heights: the highest level each fictional model solves on each test (plus one lucky higher rung). */
const HEIGHT: Record<string, Record<string, [number, number?]>> = {
  'kestrel-kite-reasoner': { 'horizon.mind-runner': [3, 5], 'horizon.modpow-ladder': [4], 'horizon.sliding-ladder': [2, 4], 'horizon.nonogram-ladder': [3], 'horizon.tiling-count': [3, 5] },
  'meridian-atlas-4-ultra': { 'horizon.mind-runner': [2], 'horizon.modpow-ladder': [3, 5], 'horizon.sliding-ladder': [1], 'horizon.nonogram-ladder': [2, 4], 'horizon.tiling-count': [2] },
  'helios-nova-3-pro': { 'horizon.mind-runner': [1, 3], 'horizon.modpow-ladder': [1], 'horizon.sliding-ladder': [0], 'horizon.nonogram-ladder': [1], 'horizon.tiling-count': [1] },
  'helios-quill-flash': { 'horizon.mind-runner': [0], 'horizon.modpow-ladder': [0, 1], 'horizon.sliding-ladder': [0], 'horizon.nonogram-ladder': [0, 1], 'horizon.tiling-count': [0] },
};

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0) / 4294967296;
}

export function isHorizonMockTest(t: TestDefinition | undefined): t is PromptTest {
  return !!t && t.kind === 'prompt' && IDS.has(t.id);
}

const WORKING: Record<LadderAnswer, string> = {
  integer: 'Working through it step by step and keeping every intermediate value exact.',
  plan: 'Searching outward from the start position, and checking that no shorter sequence reaches the goal.',
  grid: 'Settling the forced cells line by line, then testing the remaining cells by case analysis.',
};

/** A plausible wrong reply of each kind (a digit slip, a detour or an illegal slide, a few wrong cells). */
function wrongReply(kind: LadderAnswer, expected: unknown, u: number): string {
  if (kind === 'integer') {
    const key = String(expected);
    const at = Math.min(key.length - 1, Math.floor(u * key.length));
    const d = (Number(key[at]) + 1 + Math.floor(u * 7)) % 10;
    return `FINAL ANSWER: ${key.slice(0, at)}${d}${key.slice(at + 1)}`;
  }
  if (kind === 'plan') {
    const k = expected as PlanKey;
    const plan = k.plan ?? [];
    if (u < 0.5) {
      // a detour: slide one tile out and straight back (legal, but two moves too many)
      const i = Math.floor(u * 2 * plan.length);
      return `FINAL ANSWER: ${[...plan.slice(0, i + 1), plan[i]!, plan[i]!, ...plan.slice(i + 1)].join(' ')}`;
    }
    // loses track of the board: slides a tile that is not next to the gap
    const i = 3 + Math.floor((u - 0.5) * 2 * Math.max(1, plan.length - 6));
    const far = [...Array(k.rows * k.cols).keys()].slice(1).find((t) => t !== plan[i] && t !== plan[i - 1] && t !== plan[i + 1]) ?? 1;
    return `FINAL ANSWER: ${[...plan.slice(0, i), far, ...plan.slice(i + 1)].join(' ')}`;
  }
  const want = (expected as string[]).map((r) => [...r]);
  const n = 2 + Math.floor(u * 6);
  const seen = new Set<number>();
  let x = Math.floor(u * 2147483647) || 1;
  while (seen.size < n) {
    x = (x * 48271) % 2147483647; // Park–Miller: distinct, well-spread cells
    seen.add(x % (want.length * want[0]!.length));
  }
  for (const k of seen) {
    const row = want[Math.floor(k / want[0]!.length)]!;
    const col = k % want[0]!.length;
    row[col] = row[col] === '#' ? '.' : '#';
  }
  return `FINAL ANSWER:\n${want.map((r) => r.join('')).join('\n')}`;
}

function rightReply(kind: LadderAnswer, expected: unknown): string {
  if (kind === 'integer') return `FINAL ANSWER: ${expected}`;
  if (kind === 'plan') return `FINAL ANSWER: ${((expected as PlanKey).plan ?? []).join(' ')}`;
  return `FINAL ANSWER:\n${(expected as string[]).join('\n')}`;
}

function replyFor(t: PromptTest, caseId: string, contestantId: string): string | null {
  const c = t.cases.find((x) => x.id === caseId);
  const level = ladderLevel(caseId);
  if (!c || level === null) return null;
  const kind = (t.scorer as { answer: LadderAnswer }).answer;
  if (contestantId === 'random-baseline') return `FINAL ANSWER: ${Math.floor(hash(`rb|${t.id}|${caseId}`) * 100)}`;
  const [h, lucky] = HEIGHT[contestantId]?.[t.id] ?? [0];
  const ok = level <= h || level === lucky;
  const u = hash(`${contestantId}|${t.id}|${caseId}`);
  return `${WORKING[kind]}\n\n${ok ? rightReply(kind, c.expected) : level > h + 3 && u < 0.4 ? 'I could not finish this reliably by hand, so I will not guess.' : wrongReply(kind, c.expected, u)}`;
}

/** Replace the generic mock outcome of a Horizon case with the real ladder scorer's verdict on a scripted reply. */
export function decorateHorizon(t: TestDefinition, lite: CaseResultLite): CaseResultLite {
  if (!isHorizonMockTest(t)) return lite;
  const reply = replyFor(t, lite.caseId, lite.contestantId);
  const c = t.cases.find((x) => x.id === lite.caseId);
  if (reply === null || !c) return lite;
  const o = scoreLadder((t.scorer as { answer: LadderAnswer }).answer, c.expected, reply);
  return { ...lite, status: 'ok', score: o.score, passed: o.passed, summary: o.summary, scoreDetail: o.detail, error: undefined };
}

/** Full result (with transcript) for a Horizon demo case, or null for other tests. */
export function horizonDetail(t: TestDefinition | undefined, lite: CaseResultLite, turns: string[]): CaseResult | null {
  if (!isHorizonMockTest(t)) return null;
  const reply = replyFor(t, lite.caseId, lite.contestantId);
  const c = t.cases.find((x) => x.id === lite.caseId);
  if (reply === null || !c) return null;
  const o = scoreLadder((t.scorer as { answer: LadderAnswer }).answer, c.expected, reply);
  const m = lite.metrics;
  const transcript: TranscriptEntry[] = [
    {
      label: 'answer',
      messages: [{ role: 'user', content: turns[0] ?? '' }],
      response: reply,
      usage: { inputTokens: m.inputTokens, outputTokens: m.outputTokens, reasoningTokens: m.reasoningTokens, cachedInputTokens: 0, cacheWriteTokens: 0 },
      ttftMs: m.ttftMs,
      totalMs: m.wallMs,
      stopReason: 'end',
      rawStopReason: 'stop',
      costUsd: m.costUsd,
      retries: 0,
    },
  ];
  return { ...lite, status: 'ok', score: o.score, passed: o.passed, summary: o.summary, scoreDetail: o.detail, transcript, artifacts: [], replay: undefined };
}
