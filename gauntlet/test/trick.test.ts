import { test } from 'node:test';
import assert from 'node:assert/strict';
import { loadSuites, loadTests, renderCase, caseScorer, validateTest } from '../src/core/registry.ts';
import { scoreResponse } from '../src/scoring/index.ts';
import { displayQuestion, disagreementOf, matchesLure, modelLines, selectTrickHighlights, type TrickResultLike } from '../src/presenter/trick-highlights.ts';
import type { ArtifactRef, PromptTest, ScorerSpec } from '../src/core/types.ts';

/**
 * "Can It Be Fooled?" (category trick): answer keys, lures, time-pressure
 * rendering and the Presenter's highlight selection.
 */

const trick = loadTests()
  .map((t) => t.definition)
  .filter((d): d is PromptTest => d.kind === 'prompt' && d.category === 'trick');

const noJudges = { ids: [], ask: async () => [] };
const noArtifacts = (name: string): ArtifactRef => ({ name, kind: 'text', file: name, bytes: 0 });
const score = (scorer: ScorerSpec, expected: unknown, response: string) =>
  scoreResponse({ scorer, expected, response, stopReason: 'end', taskText: '', judges: noJudges, saveArtifact: noArtifacts, signal: new AbortController().signal });

test('the trick category has at least 3 tests and 60+ cases, all valid, each with a lure, a shown answer and a justification', () => {
  assert.ok(trick.length >= 3);
  const cases = trick.flatMap((d) => d.cases.map((c) => ({ d, c })));
  assert.ok(cases.length >= 60, `${cases.length} cases`);
  const all = loadTests();
  for (const d of trick) assert.deepEqual(validateTest(d, all, { selfFile: all.find((t) => t.definition.id === d.id)!.file }), [], d.id);
  for (const { d, c } of cases) {
    assert.ok(c.lure && c.lure.trim(), `${d.id}/${c.id} lure`);
    assert.ok(c.displayAnswer && c.displayAnswer.trim(), `${d.id}/${c.id} displayAnswer`);
    assert.ok(c.notes && c.notes.length > 20, `${d.id}/${c.id} notes`);
  }
  const suite = loadSuites().find((s) => s.id === 'trick');
  assert.ok(suite, 'trick suite exists');
  assert.equal(suite!.repeats, 3);
  assert.deepEqual(suite!.tests.map((t) => t.id).sort(), trick.map((d) => d.id).sort());
});

test('the correct answer scores 1 and the tempting wrong answer scores 0 on every trick case', async () => {
  const failures: string[] = [];
  for (const d of trick) {
    for (const c of d.cases) {
      const sc = caseScorer(d, c);
      const right = sc.type === 'regex' ? `FINAL ANSWER: ${c.displayAnswer}` : `FINAL ANSWER: ${Array.isArray(c.expected) ? c.expected[0] : c.expected}`;
      const good = await score(sc, c.expected, right);
      if (good.score !== 1) failures.push(`${d.id}/${c.id}: correct answer scored ${good.score} (${good.summary})`);
      const bad = await score(sc, c.expected, `FINAL ANSWER: ${c.lure}`);
      if (bad.score !== 0) failures.push(`${d.id}/${c.id}: lure "${c.lure}" scored ${bad.score}`);
    }
  }
  assert.deepEqual(failures, []);
});

test('lightning traps: one line only — working, hedges and extra lines score zero; markdown and a full stop are fine', async () => {
  const lt = trick.find((d) => d.id === 'trick.lightning-traps')!;
  for (const c of lt.cases) {
    const sc = caseScorer(lt, c);
    assert.equal((await score(sc, c.expected, `**FINAL ANSWER:** ${c.displayAnswer}.`)).score, 1, `${c.id} markdown`);
    assert.equal((await score(sc, c.expected, `FINAL ANSWER: ${c.displayAnswer}\n`)).score, 1, `${c.id} trailing newline`);
    const verbose = await score(sc, c.expected, `Let me think about this.\nFINAL ANSWER: ${c.displayAnswer}`);
    assert.equal(verbose.score, 0, `${c.id} working`);
    assert.equal(verbose.detail.oneLine, false);
    assert.match(verbose.summary, /not a single line/);
    assert.equal((await score(sc, c.expected, `FINAL ANSWER: ${c.displayAnswer} or ${c.lure}`)).score, 0, `${c.id} hedge`);
    assert.equal((await score(sc, c.expected, `${c.displayAnswer}`)).score, 0, `${c.id} missing marker`);
  }
});

test('time pressure is stated in the prompt of time-limited cases only, before the answer-format instruction', () => {
  const lt = trick.find((d) => d.id === 'trick.lightning-traps')!;
  assert.equal(lt.answerWithinSec, 30);
  for (const c of lt.cases) assert.match(renderCase(lt, c).turns[0]!, /TIME LIMIT: you must answer within 30 seconds\. A reply that arrives later scores zero\.$/);
  const mc = trick.find((d) => d.id === 'trick.modified-classics')!;
  const r = renderCase(mc, mc.cases[0]!).turns[0]!;
  assert.doesNotMatch(r, /TIME LIMIT/);
  // A per-case limit overrides the test's and sits before FINAL ANSWER.
  const withCase = renderCase(mc, { ...mc.cases[0]!, answerWithinSec: 1 }).turns[0]!;
  assert.match(withCase, /within 1 second\. A reply[\s\S]*FINAL ANSWER: <answer>$/);
  // Existing tests are untouched: no other category has a time notice.
  for (const t of loadTests()) {
    const d = t.definition;
    if (d.kind !== 'prompt' || d.category === 'trick') continue;
    for (const c of d.cases) assert.doesNotMatch(renderCase(d, c).turns.join('\n'), /TIME LIMIT: you must answer/, `${d.id}/${c.id}`);
  }
});

test('answerWithinSec is validated', () => {
  const base = trick.find((d) => d.id === 'trick.lightning-traps')!;
  assert.ok(validateTest({ ...base, id: 'trick.x', answerWithinSec: 0 }).some((e) => /answerWithinSec/.test(e)));
  assert.ok(validateTest({ ...base, id: 'trick.x', cases: [{ ...base.cases[0]!, answerWithinSec: -3 }] }).some((e) => /answerWithinSec/.test(e)));
  assert.ok(!validateTest({ ...base, id: 'trick.x', answerWithinSec: 45 }).some((e) => /answerWithinSec/.test(e)));
});

test('the Random Baseline answer shape scores near zero on the trick tests', async () => {
  // The mock adapter answers "FINAL ANSWER: <0..100>" or a random letter.
  let sum = 0;
  let n = 0;
  for (const d of trick)
    for (const c of d.cases)
      for (const guess of ['0', '1', '2', '7', '42', '99', 'A', 'B', 'C', 'D']) {
        sum += (await score(caseScorer(d, c), c.expected, `FINAL ANSWER: ${guess}`)).score ?? 0;
        n++;
      }
  assert.ok(sum / n <= 0.1, `${((sum / n) * 100).toFixed(1)}%`);
});

// ───────────────────────── Presenter highlights ─────────────────────────

const R = (contestantId: string, caseId: string, repeat: number, s: number | null, extracted: string, status: TrickResultLike['status'] = 'ok', ms = 1000): TrickResultLike & { repeat: number } => ({
  contestantId,
  testId: 'trick.modified-classics',
  caseId,
  repeat,
  status,
  score: s,
  scoreDetail: { extracted, responseMs: ms },
});

const contenders = [
  { id: 'a', label: 'Alpha', color: '#f00' },
  { id: 'b', label: 'Beta', color: '#0f0' },
  { id: 'c', label: 'Gamma', color: '#00f' },
  { id: 'rb', label: 'Random Baseline', color: '#888', baseline: true },
];

test('model lines: majority verdict, most common answer, bait detection, out of time', () => {
  const lines = modelLines(
    [
      R('a', 'm03', 0, 1, '10'),
      R('a', 'm03', 1, 1, '10 cents'),
      R('a', 'm03', 2, 0, '5'),
      R('b', 'm03', 0, 0, '5'),
      R('b', 'm03', 1, 0, '5', 'ok', 3000),
      R('b', 'm03', 2, 1, '10'),
      R('c', 'm03', 0, 0, '', 'timeout', 30000),
      R('c', 'm03', 1, 0, '', 'timeout', 30000),
      R('c', 'm03', 2, 1, '10'),
    ],
    contenders.slice(0, 3),
    '5 cents',
  );
  const [a, b, c] = lines;
  assert.equal(a!.verdict, 'correct');
  assert.equal(a!.answer, '10');
  assert.equal(a!.correct, 2);
  assert.equal(a!.attempts, 3);
  assert.equal(a!.tookBait, false);
  assert.equal(b!.verdict, 'fooled');
  assert.equal(b!.answer, '5');
  assert.equal(b!.tookBait, true);
  assert.equal(b!.responseMs, 1000);
  assert.equal(c!.verdict, 'out-of-time');
  assert.equal(c!.answer, '');
  assert.ok(matchesLure('A', '(A) his mother'));
  assert.ok(!matchesLure('50', '5 cents'));
  assert.ok(matchesLure('66.67', '66.7% (always switch)'));
});

test('highlights pick the cases with the most disagreement, ignore the baseline and skip cases nobody got wrong', () => {
  const mc = trick.find((d) => d.id === 'trick.modified-classics')!;
  const results: TrickResultLike[] = [];
  const put = (caseId: string, pattern: Record<string, number>) => {
    for (const [id, correctReps] of Object.entries(pattern))
      for (let r = 0; r < 3; r++) results.push(R(id, caseId, r, r < correctReps ? 1 : 0, r < correctReps ? 'right' : 'wrong'));
  };
  put('m01', { a: 3, b: 3, c: 3, rb: 0 }); // everyone right (baseline wrong): not a highlight
  put('m02', { a: 3, b: 0, c: 3 }); // 2 v 1
  put('m03', { a: 3, b: 0, c: 0 }); // 1 v 2, same disagreement as m02, more fooled
  put('m04', { a: 0, b: 0, c: 0 }); // everyone fooled: zero disagreement but still fun
  put('m05', { a: 3, b: 2, c: 3 }); // everyone right by majority, small disagreement
  const hs = selectTrickHighlights({ tests: [{ definition: mc }], results, contenders, perTest: 3 });
  assert.deepEqual(hs.map((h) => h.caseId), ['m03', 'm02', 'm05']);
  assert.ok(!hs.some((h) => h.caseId === 'm01'), 'baseline failures do not make a case divisive');
  assert.equal(hs[0]!.fooled, 2);
  assert.equal(hs[0]!.correct, '10 cents');
  assert.equal(hs[0]!.lure, '5 cents');
  assert.equal(hs[0]!.question, 'A bat and a ball cost $1.10 in total. The bat costs $1.00. How much does the ball cost?');
  const all = selectTrickHighlights({ tests: [{ definition: mc }], results, contenders, perTest: 10 });
  assert.deepEqual(all.map((h) => h.caseId), ['m03', 'm02', 'm05', 'm04']);
  // Non-trick tests and unselected cases are ignored.
  assert.equal(selectTrickHighlights({ tests: [{ definition: { ...mc, category: 'math', cases: mc.cases.map(({ lure: _l, ...c }) => c) } }], results, contenders }).length, 0);
  assert.deepEqual(selectTrickHighlights({ tests: [{ definition: mc, caseIds: ['m02'] }], results, contenders }).map((h) => h.caseId), ['m02']);
});

test('highlight helpers: disagreement, question clean-up and rules', () => {
  assert.equal(disagreementOf([1, 1, 1]), 0);
  assert.equal(disagreementOf([1, 0]), 1);
  assert.equal(disagreementOf([1]), 0);
  assert.equal(displayQuestion('Is it?\n\nGive exactly one answer. If you give more than one answer, it will be marked wrong.'), 'Is it?');
  const fp = trick.find((d) => d.id === 'trick.false-premise')!;
  const lt = trick.find((d) => d.id === 'trick.lightning-traps')!;
  const res = (d: PromptTest) => d.cases.slice(0, 2).flatMap((c) => [
    { contestantId: 'a', testId: d.id, caseId: c.id, status: 'ok' as const, score: 1, scoreDetail: { extracted: 'x' } },
    { contestantId: 'b', testId: d.id, caseId: c.id, status: 'timeout' as const, score: 0, scoreDetail: { outOfTime: true, responseMs: 30000 } },
  ]);
  const [h1] = selectTrickHighlights({ tests: [{ definition: fp }], results: res(fp), contenders });
  assert.match(h1!.rule!, /FALSE PREMISE/);
  const [h2] = selectTrickHighlights({ tests: [{ definition: lt }], results: res(lt), contenders });
  assert.match(h2!.rule!, /one-line answers only · 30-second limit/i);
  assert.equal(h2!.answerWithinSec, 30);
  assert.equal(h2!.models.find((m) => m.id === 'b')!.verdict, 'out-of-time');
});
