/**
 * "Why these scores?": the plain-English breakdown must use exactly the numbers the leaderboard shows, say how
 * errors / untested tests are treated, and explain the Random Baseline.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildLeaderboard } from '../src/engine/aggregate.ts';
import { averageFormula, explainScore, questionBreakdown, RANDOM_BASELINE_EXPLAINER, toScoreAnswer, type ScoreAnswer } from '../src/core/score-explain.ts';
import type { CaseResult, CategoryInfo, Contestant } from '../src/core/types.ts';

function result(contestantId: string, testId: string, caseId: string, score: number | null, status: CaseResult['status'] = 'ok', summary = ''): CaseResult {
  return {
    key: `${contestantId}::${testId}::${caseId}::r0`,
    runId: 'r',
    contestantId,
    testId,
    caseId,
    testVersion: '1.0.0',
    testHash: 'h',
    contestantHash: 'c',
    repeat: 0,
    status,
    score,
    passed: (score ?? 0) >= 1,
    summary,
    scoreDetail: {},
    metrics: { wallMs: 1000, ttftMs: 100, apiCalls: 1, inputTokens: 100, outputTokens: 100, reasoningTokens: 0, cachedInputTokens: 0, costUsd: 0.01, judgeCostUsd: 0, outputTokensPerSec: 50, retries: 0, responseChars: 10 },
    transcript: [],
    artifacts: [],
    startedAt: '',
    finishedAt: '',
  } as CaseResult;
}

const categories: CategoryInfo[] = [
  { id: 'math', name: 'Mathematics', description: '', color: '#0EA5E9', weight: 1 },
  { id: 'coding', name: 'Coding', description: '', color: '#10B981', weight: 1 },
  { id: 'social', name: 'Social Deduction', description: '', color: '#EC4899', weight: 1 },
];
const tests = [
  { id: 'math.a', name: 'Alpha', category: 'math', weight: 1, version: '1', hash: 'h' },
  { id: 'math.b', name: 'Beta', category: 'math', weight: 1, version: '1', hash: 'h' },
  { id: 'coding.c', name: 'Code', category: 'coding', weight: 1, version: '1', hash: 'h' },
  { id: 'social.d', name: 'Liars', category: 'social', weight: 1, version: '1', hash: 'h' },
];
const contestant = (id: string, label: string): Contestant => ({ id, label, vendor: 'V', provider: 'p', model: id, color: '#123456', enabled: true, pricing: { inputPerM: 1, outputPerM: 1 } }) as Contestant;
const contestants = [contestant('m1', 'Model One'), contestant('m2', 'Model Two'), contestant('random-baseline', 'Random Baseline')];
const results = [
  result('m1', 'math.a', 'c1', 1, 'ok', 'Got every step right.'),
  result('m1', 'math.a', 'c2', 0.8),
  result('m1', 'math.b', 'c1', 0.2),
  result('m1', 'math.b', 'c2', null, 'error'),
  result('m1', 'coding.c', 'c1', 0.5),
  // m1 never ran social.d.
  result('m2', 'math.a', 'c1', 0.5),
  result('m2', 'math.b', 'c1', 0.5),
  result('m2', 'coding.c', 'c1', 1),
  result('m2', 'social.d', 'c1', 0.4),
  result('random-baseline', 'math.a', 'c1', 0.25),
  result('random-baseline', 'math.b', 'c1', 0),
  result('random-baseline', 'coding.c', 'c1', 0),
  result('random-baseline', 'social.d', 'c1', 0),
];
const lb = buildLeaderboard({ scope: { kind: 'run', runId: 'r' }, fingerprint: 'f', categories, tests, contestants, results });

test('category and index numbers are exactly the leaderboard’s, with the sum written out', () => {
  for (const row of lb.rows) {
    const ex = explainScore(lb, row.contestantId)!;
    assert.equal(ex.index, row.index);
    for (const c of ex.categories) {
      const v = row.categoryScores[c.id];
      assert.equal(c.score, typeof v === 'number' ? v * 100 : null, `${row.contestantId} ${c.id}`);
    }
  }
  const m1 = explainScore(lb, 'm1')!;
  const math = m1.categories.find((c) => c.id === 'math')!;
  // Alpha = (100 + 80) / 2 = 90; Beta = 20 (the error is left out); Mathematics = (90 + 20) / 2 = 55.
  assert.equal(math.tests[0]!.score, 90);
  assert.equal(math.tests[1]!.score, 20);
  assert.equal(math.formula, '(90 + 20) ÷ 2 = 55');
  assert.match(math.headline, /average of its 2 tests: Alpha 90, Beta 20/);
  assert.ok(math.points.some((p) => /Strongest: Alpha \(90\)\. Weakest: Beta \(20\)/.test(p)));
  assert.ok(math.points.some((p) => /Random guessing scored 12\.5 here/.test(p)));
  assert.equal(math.tests[0]!.summary, 'Got every step right.');
  assert.ok(math.tests[1]!.notes.some((n) => /error.*left out of the average \(not counted as 0\)/.test(n)));
  assert.match(m1.indexFormula!, /^\(55 \+ 50\) ÷ 2 = 52\.5$/);
  assert.match(m1.headline, /Gauntlet Index of 52\.5 is the average of its 2 category scores/);
});

test('a test that was not run is explained as not counting, not as a zero', () => {
  const social = explainScore(lb, 'm1')!.categories.find((c) => c.id === 'social')!;
  assert.equal(social.score, null);
  assert.equal(social.tests[0]!.status, 'not-run');
  assert.match(social.tests[0]!.notes[0]!, /does not count \(it is not a 0\)/);
  assert.match(social.headline, /left out of the Gauntlet Index/);
  assert.ok(explainScore(lb, 'm1')!.points.some((p) => /1 category had no markable answers/.test(p)));
});

test('category leader and the gap to random guessing are named', () => {
  const m1 = explainScore(lb, 'm1')!;
  const coding = m1.categories.find((c) => c.id === 'coding')!;
  assert.ok(coding.points.some((p) => p === 'Best in this category: Model Two with 100.'));
  const m2 = explainScore(lb, 'm2')!;
  assert.ok(m2.categories.find((c) => c.id === 'coding')!.points.some((p) => /top Coding score on the board/.test(p)));
  assert.ok(m2.points.some((p) => /Random guessing scored .* overall, so this model is .* points above pure luck/.test(p)));
});

test('the Random Baseline explains itself instead of being compared with itself', () => {
  const b = explainScore(lb, 'random-baseline')!;
  assert.equal(b.isBaseline, true);
  assert.match(b.headline, /^This is not an AI\. The Random Baseline answers every question by guessing/);
  for (const c of b.categories) assert.ok(!c.points.some((p) => /Random guessing scored/.test(p)), c.id);
  assert.ok(RANDOM_BASELINE_EXPLAINER.short.length > 40);
  assert.ok(RANDOM_BASELINE_EXPLAINER.how.length >= 4 && RANDOM_BASELINE_EXPLAINER.why.length >= 3);
});

test('weighted sums show the weights; unknown models return null', () => {
  assert.equal(averageFormula([{ value: 80, weight: 2 }, { value: 50, weight: 1 }], 70), '(80×2 + 50×1) ÷ 3 = 70');
  assert.equal(averageFormula([{ value: 42.25, weight: 1 }], 42.25), '42.3 (the only one, so it is the score)');
  assert.equal(averageFormula([], 0), null);
  assert.equal(explainScore(lb, 'nobody'), null);
});

const ans = (caseId: string, score: number | null, extra: Partial<ScoreAnswer> = {}): ScoreAnswer => ({
  key: `m::t::${caseId}::r${extra.repeat ?? 0}`,
  runId: 'run-1',
  contestantId: 'm',
  caseId,
  repeat: 0,
  status: 'ok',
  score,
  passed: score === 1,
  summary: '',
  ...extra,
});

test('question by question: marks, reasons and the sum that makes the test score', () => {
  const b = questionBreakdown([
    ans('c10', 0, { modelSaid: '7', correct: '12' }),
    ans('c2', 1, { modelSaid: '42', correct: '42', summary: 'Correct' }),
    ans('c1', 0.5, { checks: { passed: 2, total: 4 }, summary: 'Half the hidden tests passed' }),
    ans('c3', null, { status: 'error', error: 'HTTP 529 overloaded' }),
    ans('c4', null, { status: 'pending-human' }),
    ans('c5', 0, { status: 'refusal' }),
  ]);
  assert.deepEqual(b.questions.map((q) => q.caseId), ['c1', 'c2', 'c3', 'c4', 'c5', 'c10'], 'natural order');
  assert.equal(b.score, (50 + 100 + 0 + 0) / 4);
  assert.equal(b.formula, '(50 + 100 + 0 + 0) ÷ 4 = 37.5');
  assert.deepEqual([b.right, b.partly, b.wrong, b.leftOut, b.counted], [1, 1, 2, 2, 4]);
  const why = (id: string) => b.questions.find((q) => q.caseId === id)!.tries[0]!.why;
  assert.equal(why('c2'), 'Answered “42”; the correct answer is “42” — right.');
  assert.equal(why('c10'), 'Answered “7”; the correct answer is “12” — wrong.');
  assert.equal(why('c1'), 'Passed 2 of 4 checks. Half the hidden tests passed.');
  assert.match(why('c3'), /^Failed with an error \(HTTP 529 overloaded\).*not counted as 0\.$/);
  assert.match(why('c4'), /Waiting for your grade/);
  assert.match(why('c5'), /refused to answer, which scores 0/);
  assert.equal(b.questions.find((q) => q.caseId === 'c3')!.verdict, 'out');
});

test('repeats are averaged inside a question first, and long tests show a short sum', () => {
  const b = questionBreakdown([ans('c1', 1), ans('c1', 0, { repeat: 1, key: 'k2' }), ans('c1', null, { repeat: 2, key: 'k3', status: 'error' }), ans('c2', 1)]);
  assert.equal(b.questions[0]!.score, 50);
  assert.equal(b.questions[0]!.triesSum, 'Asked 3 times: 100, 0, left out → average 50');
  assert.equal(b.score, 75);
  const many = questionBreakdown(Array.from({ length: 20 }, (_, i) => ans(`c${i + 1}`, i < 15 ? 1 : 0)));
  assert.equal(many.formula, '1500 (all 20 question scores added up) ÷ 20 = 75');
  assert.equal(questionBreakdown([]).score, null);
});

test('recorded results are trimmed: short answers and answer keys kept, long ones dropped', () => {
  const r = results[0]!;
  const a = toScoreAnswer({ ...r, scoreDetail: { extracted: ' 42 ', expected: 42, items: [{ label: 'x', passed: true }, { label: 'y', passed: false }] } });
  assert.equal(a.modelSaid, '42');
  assert.equal(a.correct, '42');
  assert.deepEqual(a.checks, { passed: 1, total: 2 });
  const long = toScoreAnswer({ ...r, scoreDetail: { extracted: 'x'.repeat(500), expected: { deep: 1 } } });
  assert.equal(long.modelSaid, undefined);
  assert.equal(long.correct, undefined);
});
