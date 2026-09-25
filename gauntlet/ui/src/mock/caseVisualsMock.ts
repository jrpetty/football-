/**
 * Mock-mode fixtures for the "answer vs truth" case visuals: real cases from
 * every test family that has a visual (caseVisualsFixtures.json, built by
 * verification/case_visual_fixtures.mjs), a completed demo run, and scripted
 * right / wrong replies. Scores and score details are produced by the real
 * scorer helpers (answer extraction, the constraint checker, the JSON
 * comparer); coding results come from actually running the scripted code.
 */
import fixtures from './caseVisualsFixtures.json';
import type { CaseResult, CaseResultLite, PromptTest, PromptTestCase, ScoreBreakdownItem, ScoreDetail, TestDefinition, TranscriptEntry } from '../types.ts';
import type { RunSpec } from './fixtures.ts';
import { extractFinalAnswer, normalize, parseJsonLoose, parseNumber } from '../../../src/core/extract.ts';
import { checkConstraints } from '../../../src/scoring/constraints.ts';
import { compareJson } from '../../../src/scoring/json-compare.ts';

interface Fixtures {
  tests: PromptTest[];
  answers: Record<string, { right: string; wrong: string[] }>;
  honesty: Record<string, { right: [string, string[]]; wrong: Array<[string, string[]]> }>;
  rationale: Record<string, string>;
  code: Record<string, Array<{ code: string; items: ScoreBreakdownItem[]; passed: number; total: number }>>;
}

const FX = fixtures as unknown as Fixtures;

export const VISUAL_TESTS: PromptTest[] = FX.tests;
const VISUAL_IDS = new Set(VISUAL_TESTS.map((t) => t.id));

export const VISUAL_RUN_SPEC: RunSpec = {
  id: 'run-2026-09-24-truth',
  name: 'Answer vs truth · visual demo',
  status: 'completed',
  contestantIds: ['meridian-atlas-4-ultra', 'kestrel-kite-reasoner', 'helios-nova-3-pro', 'obsidian-sable-large', 'helios-quill-flash', 'random-baseline'],
  testIds: VISUAL_TESTS.map((t) => t.id),
  repeats: 1,
  createdAt: '2026-09-24T10:30:00Z',
  notes: 'A few real cases from every test family with an answer-vs-truth visual. Open a cell to see it, or the Presenter with ?truth=1.',
};

/** How often each demo model gets a case right. */
const SKILL: Record<string, number> = {
  'meridian-atlas-4-ultra': 0.78,
  'kestrel-kite-reasoner': 0.84,
  'helios-nova-3-pro': 0.62,
  'obsidian-sable-large': 0.45,
  'helios-quill-flash': 0.35,
  'manual-orbit-chat': 0.5,
};

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return (h >>> 0) / 4294967296;
}

export function isVisualTest(t: TestDefinition | undefined): t is PromptTest {
  return !!t && t.kind === 'prompt' && VISUAL_IDS.has(t.id) && VISUAL_TESTS.some((x) => x === t);
}

/** -1 = the right reply; otherwise the index of the scripted wrong reply. */
function variant(contestantId: string, t: PromptTest, c: PromptTestCase, repeat: number, wrongCount: number): number {
  if (contestantId === 'random-baseline') return 0;
  const p = SKILL[contestantId] ?? 0.5;
  const u = hash(`${contestantId}|${t.id}|${c.id}`) * 0.85 + hash(`${contestantId}|${t.id}|${c.id}|${repeat}`) * 0.15;
  if (u < p) return -1;
  return Math.floor(hash(`w|${contestantId}|${c.id}`) * wrongCount) % Math.max(1, wrongCount);
}

function quote(s: string, max = 48): string {
  const one = s.replace(/\s+/g, ' ').trim();
  return one.length > max ? `"${one.slice(0, max - 1)}…"` : `"${one}"`;
}

interface Outcome {
  replies: string[];
  score: number;
  passed: boolean;
  summary: string;
  detail: ScoreDetail;
}

/** Earlier replies of a multi-turn conversation (not scored). */
function earlierReply(t: PromptTest): string {
  if (t.id === 'instruction.adversarial-system') return "[Quill] I'm happy to help with your visit. The Great Astronomical Clock stands 4.2 metres tall and still keeps time.\nYours in good time, Quill";
  if (t.id === 'instruction.system-prompt-adherence') return "Thanks for reaching out! I can help with Lumen & Loom lamps, bulbs, orders, shipping and returns.\nPip | Lumen & Loom";
  return 'Understood. Here is what I can tell you.';
}

function outcome(t: PromptTest, c: PromptTestCase, contestantId: string, repeat: number, judges: string[]): Outcome | null {
  const key = `${t.id}::${c.id}`;
  const sc = c.scorer ?? t.scorer;
  const turns = c.turns?.length ?? 1;
  const early = Array.from({ length: turns - 1 }, () => earlierReply(t));
  if (sc.type === 'code-js') {
    const runs = FX.code[key];
    if (!runs) return null;
    const v = variant(contestantId, t, c, repeat, runs.length - 1);
    const r = runs[v < 0 ? 0 : v + 1]!;
    const score = r.total ? r.passed / r.total : 0;
    return {
      replies: ['```js\n' + r.code + '```'],
      score: Math.round(score * 10000) / 10000,
      passed: r.passed === r.total,
      summary: `${r.passed}/${r.total} unit tests passed`,
      detail: { formatOk: true, items: r.items, extracted: r.code },
    };
  }
  if (sc.type === 'judge-classify') {
    const h = FX.honesty[key];
    if (!h) return null;
    const v = variant(contestantId, t, c, repeat, h.wrong.length);
    const [reply, labels] = v < 0 ? h.right : h.wrong[v]!;
    const judge = labels.map((label, i) => ({
      contestantId: judges[i] ?? `judge-${i + 1}`,
      label,
      score: sc.labels.find((l) => l.id === label)?.score ?? 0,
      rationale: FX.rationale[label] ?? '',
    }));
    const score = judge.reduce((s, j) => s + j.score, 0) / judge.length;
    const unanimous = new Set(labels).size === 1;
    return {
      replies: [...early, reply],
      score: Math.round(score * 10000) / 10000,
      passed: score >= 0.99,
      summary: `${labels[0]!.replace(/_/g, ' ').toLowerCase()} (${labels.join(' / ')})${unanimous ? '' : ' · judges disagree'}`,
      detail: unanimous ? { judge, label: labels[0] } : { judge, label: labels[0], judgeDisagreement: true },
    };
  }
  const a = FX.answers[key];
  if (!a) return null;
  const v = variant(contestantId, t, c, repeat, a.wrong.length);
  const reply = v < 0 ? a.right : a.wrong[v]!;
  const replies = [...early, reply];
  switch (sc.type) {
    case 'exact': {
      const { answer, formatOk } = extractFinalAnswer(reply);
      const accepted = (Array.isArray(c.expected) ? c.expected : [c.expected]) as string[];
      const ok = accepted.some((e) => normalize(answer, sc.normalize ?? 'lower') === normalize(e, sc.normalize ?? 'lower'));
      return { replies, score: ok ? 1 : 0, passed: ok, summary: ok ? `Correct: ${quote(answer)}` : `Answered ${quote(answer)} · expected ${quote(accepted[0] ?? '')}`, detail: { extracted: answer, expected: c.expected, formatOk } };
    }
    case 'number': {
      const { answer, formatOk } = extractFinalAnswer(reply);
      const value = parseNumber(answer);
      const exp = c.expected as number;
      const ok = value !== null && Math.abs(value - exp) <= (sc.tolerance ?? 1e-6);
      return { replies, score: ok ? 1 : 0, passed: ok, summary: ok ? `Correct: ${answer}` : `Answered ${quote(answer)} · expected ${exp}`, detail: { extracted: answer, expected: exp, formatOk, parsed: value } };
    }
    case 'constraints': {
      const items = checkConstraints(reply, c.expected as never);
      const n = items.filter((i) => i.passed).length;
      const all = n === items.length;
      const failed = items.filter((i) => !i.passed).map((i) => i.label);
      return {
        replies,
        score: sc.allOrNothing ? (all ? 1 : 0) : Math.round((n / items.length) * 10000) / 10000,
        passed: all,
        summary: all ? `All ${items.length} constraints met` : `${n}/${items.length} constraints · missed ${failed.slice(0, 2).join('; ')}${failed.length > 2 ? '…' : ''}`,
        detail: { items },
      };
    }
    case 'json': {
      const parsed = parseJsonLoose(reply);
      if (parsed === undefined) return { replies, score: 0, passed: false, summary: 'No valid JSON found', detail: { formatOk: false, expected: c.expected } };
      const r = compareJson(c.expected, parsed, { unorderedArrays: sc.unorderedArrays, numberTolerance: sc.numberTolerance, aliases: sc.aliases });
      const all = r.matched === r.total;
      return {
        replies,
        score: sc.allOrNothing ? (all ? 1 : 0) : Math.round(r.score * 10000) / 10000,
        passed: all,
        summary: sc.allOrNothing && !all ? `${r.matched}/${r.total} fields correct · all-or-nothing, so 0` : `${r.matched}/${r.total} fields correct`,
        detail: { formatOk: true, items: r.items, expected: c.expected, extracted: JSON.stringify(parsed).slice(0, 4000) },
      };
    }
    default:
      return null;
  }
}

/** Replace the generic mock outcome of a visual-demo case with one computed from its scripted reply. */
export function decorateVisual(t: TestDefinition, lite: CaseResultLite, judges: string[]): CaseResultLite {
  if (!isVisualTest(t)) return lite;
  const c = t.cases.find((x) => x.id === lite.caseId);
  if (!c) return lite;
  const o = outcome(t, c, lite.contestantId, lite.repeat, judges);
  if (!o) return lite;
  return { ...lite, status: 'ok', score: o.score, passed: o.passed, summary: o.summary, scoreDetail: o.detail, error: undefined };
}

/** Full result (with transcript) for a visual-demo case, or null for other tests. */
export function visualDetail(t: TestDefinition | undefined, lite: CaseResultLite, turns: string[], judges: string[]): CaseResult | null {
  if (!isVisualTest(t)) return null;
  const c = t.cases.find((x) => x.id === lite.caseId);
  if (!c) return null;
  const o = outcome(t, c, lite.contestantId, lite.repeat, judges);
  if (!o) return null;
  const m = lite.metrics;
  const usage = { inputTokens: Math.round(m.inputTokens / turns.length), outputTokens: Math.round(m.outputTokens / turns.length), reasoningTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 };
  const transcript: TranscriptEntry[] = [];
  const messages: TranscriptEntry['messages'] = [];
  turns.forEach((turn, i) => {
    messages.push({ role: 'user', content: turn });
    const resp = o.replies[i] ?? '';
    transcript.push({
      label: turns.length > 1 ? `turn ${i + 1}` : 'answer',
      system: t.system,
      messages: [...messages],
      response: resp,
      usage,
      ttftMs: m.ttftMs,
      totalMs: Math.round(m.wallMs / turns.length),
      stopReason: 'end',
      rawStopReason: 'stop',
      costUsd: m.costUsd / turns.length,
      retries: 0,
    });
    messages.push({ role: 'assistant', content: resp });
  });
  return { ...lite, status: 'ok', score: o.score, passed: o.passed, summary: o.summary, scoreDetail: o.detail, transcript, replay: undefined };
}
