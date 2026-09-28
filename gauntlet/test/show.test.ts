/**
 * Presenter "game show" pass: running standings (bar race), best/worst test,
 * the last-to-first reveal steps, auto-play pacing and the sound settings.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { buildLeaderboard } from '../src/engine/aggregate.ts';
import { bestAndWorst, revealState, revealSteps, runningStandings } from '../src/presenter/standings.ts';
import { autoHoldMs } from '../ui/src/components/present/autoPace.ts';
import { DEFAULT_VOLUME, parseSfxPrefs } from '../ui/src/components/present/sfxPrefs.ts';
import type { CaseResult, CategoryInfo, Contestant } from '../src/core/types.ts';

function result(contestantId: string, testId: string, caseId: string, score: number | null, status: CaseResult['status'] = 'ok'): CaseResult {
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
    summary: '',
    scoreDetail: {},
    metrics: { wallMs: 1000, ttftMs: 100, apiCalls: 1, inputTokens: 100, outputTokens: 100, reasoningTokens: 0, cachedInputTokens: 0, costUsd: 0.01, judgeCostUsd: 0, outputTokensPerSec: 50, retries: 0, responseChars: 10 },
    transcript: [],
    artifacts: [],
    startedAt: '',
    finishedAt: '',
  } as CaseResult;
}

const categories: CategoryInfo[] = [
  { id: 'math', name: 'Math', description: '', color: '#000', weight: 1 },
  { id: 'coding', name: 'Coding', description: '', color: '#000', weight: 2 },
];
const tests = [
  { id: 'math.a', name: 'Alpha', category: 'math', weight: 1, version: '1', hash: 'h' },
  { id: 'coding.c', name: 'Code', category: 'coding', weight: 1, version: '1', hash: 'h' },
  { id: 'math.b', name: 'Beta', category: 'math', weight: 2, version: '1', hash: 'h' },
];
const contestant = (id: string): Contestant => ({ id, label: id.toUpperCase(), vendor: 'V', provider: 'p', model: id, color: '#123456', enabled: true, pricing: { inputPerM: 1, outputPerM: 1 } }) as Contestant;
const contestants = ['m1', 'm2', 'm3', 'random-baseline'].map(contestant);
const results = [
  // m1 starts strong, fades; m2 starts weak, surges; m3 steady; m3 has an error excluded from its mean.
  result('m1', 'math.a', 'c1', 1),
  result('m1', 'math.a', 'c2', 1),
  result('m1', 'coding.c', 'c1', 0.2),
  result('m1', 'math.b', 'c1', 0.3),
  result('m2', 'math.a', 'c1', 0.2),
  result('m2', 'math.a', 'c2', 0),
  result('m2', 'coding.c', 'c1', 1),
  result('m2', 'math.b', 'c1', 0.9),
  result('m3', 'math.a', 'c1', 0.5),
  result('m3', 'math.a', 'c2', 0.7),
  result('m3', 'coding.c', 'c1', 0.6),
  result('m3', 'coding.c', 'c2', null, 'error'),
  result('m3', 'math.b', 'c1', 0.6),
  result('random-baseline', 'math.a', 'c1', 0.25),
  result('random-baseline', 'coding.c', 'c1', 0),
  result('random-baseline', 'math.b', 'c1', 0.5),
];
const board = (ts = tests) => buildLeaderboard({ scope: { kind: 'run', runId: 'r' }, fingerprint: 'f', categories, tests: ts, contestants, results });
const BASE = new Set(['random-baseline']);

test('running standings: every step equals the leaderboard formula on the tests played so far', () => {
  const lb = board();
  const order = tests.map((t) => t.id);
  const steps = runningStandings(lb, order, BASE);
  assert.equal(steps.length, 3);
  for (let k = 1; k <= 3; k++) {
    const partial = board(tests.slice(0, k));
    const st = steps[k - 1]!;
    assert.equal(st.step, k);
    assert.equal(st.of, 3);
    assert.equal(st.testId, order[k - 1]);
    for (const r of st.rows) {
      const want = partial.rows.find((x) => x.contestantId === r.id)!.index;
      assert.ok(Math.abs((r.index ?? NaN) - (want ?? NaN)) < 0.011, `step ${k} ${r.id}: ${r.index} vs ${want}`);
    }
    const wantBase = partial.rows.find((x) => x.contestantId === 'random-baseline')!.index!;
    assert.ok(Math.abs(st.baseline! - wantBase) < 0.011);
    // The baseline is a reference line, never a ranked row.
    assert.ok(!st.rows.some((r) => r.id === 'random-baseline'));
  }
  // The last step is exactly the recorded Index and the recorded order.
  const last = steps[2]!;
  for (const r of last.rows) assert.equal(r.index, lb.rows.find((x) => x.contestantId === r.id)!.index);
  assert.deepEqual(
    last.rows.map((r) => r.id),
    lb.rows.filter((r) => r.contestantId !== 'random-baseline').map((r) => r.contestantId),
  );
});

test('running standings: leader changes and the biggest mover are called out', () => {
  const steps = runningStandings(board(), tests.map((t) => t.id), BASE);
  const [s1, s2, s3] = steps;
  assert.equal(s1!.leaderId, 'm1');
  assert.equal(s1!.newLeader, false, 'no "new leader" on the first step');
  assert.equal(s1!.mover, null);
  assert.deepEqual(s1!.rows.map((r) => r.gained), [0, 0, 0]);
  // After the (double-weighted) coding test m2 jumps from last to first.
  assert.equal(s2!.leaderId, 'm2');
  assert.equal(s2!.prevLeaderId, 'm1');
  assert.equal(s2!.newLeader, true);
  assert.deepEqual(s2!.mover, { id: 'm2', places: 2 });
  const m1 = s2!.rows.find((r) => r.id === 'm1')!;
  assert.equal(m1.prevRank, 1);
  assert.ok(m1.gained < 0);
  assert.equal(s3!.newLeader, false);
});

test('running standings: models with nothing scored yet are unranked, not zero', () => {
  const extra = [...results.filter((r) => !(r.contestantId === 'm3' && r.testId === 'math.a'))];
  const lb = buildLeaderboard({ scope: { kind: 'run', runId: 'r' }, fingerprint: 'f', categories, tests, contestants, results: extra });
  const s1 = runningStandings(lb, tests.map((t) => t.id), BASE)[0]!;
  const m3 = s1.rows.find((r) => r.id === 'm3')!;
  assert.equal(m3.index, null);
  assert.equal(m3.rank, null);
  assert.equal(s1.rows.at(-1)!.id, 'm3');
  // Unknown test ids in the episode order are skipped.
  assert.equal(runningStandings(lb, ['nope', ...tests.map((t) => t.id)], BASE).length, 3);
  // No baseline in the run: no reference line.
  assert.equal(runningStandings(lb, tests.map((t) => t.id), new Set())[0]!.baseline, null);
});

test('best and worst test per model', () => {
  const lb = board();
  const m2 = lb.rows.find((r) => r.contestantId === 'm2')!;
  const { best, worst } = bestAndWorst(m2, tests);
  assert.equal(best?.name, 'Code');
  assert.equal(best?.score, 1);
  assert.equal(worst?.name, 'Alpha');
  const only = bestAndWorst({ tests: { 'math.a': m2.tests['math.a']! } }, tests);
  assert.equal(only.worst, null, 'one scored test: no worst');
  assert.deepEqual(bestAndWorst({ tests: {} }, tests), { best: null, worst: null });
});

test('podium reveal: last place first, a drumroll, then the winner', () => {
  assert.equal(revealSteps(0), 0);
  assert.equal(revealSteps(5), 6);
  const seq = Array.from({ length: revealSteps(5) + 1 }, (_, r) => revealState(5, r));
  assert.deepEqual(seq[0], { shownFrom: 6, current: null, drumroll: false, winner: false });
  assert.deepEqual(seq.slice(1, 5).map((s) => s.current), [5, 4, 3, 2]);
  assert.equal(seq[5]!.drumroll, true);
  assert.equal(seq[5]!.shownFrom, 2, 'the winner stays hidden during the drumroll');
  assert.deepEqual(seq[6], { shownFrom: 1, current: 1, drumroll: false, winner: true });
  // A single model still gets the drumroll.
  assert.equal(revealState(1, 1).drumroll, true);
  assert.equal(revealState(1, 2).winner, true);
  // Out-of-range presses clamp.
  assert.equal(revealState(3, 99).winner, true);
  assert.equal(revealState(3, -1).current, null);
});

test('auto-play pacing: reveals hold longer than plain steps; the winner holds longest', () => {
  const trickStep = autoHoldMs({ kind: 'trick', reveal: 1, maxReveal: 2 });
  const finalRow = autoHoldMs({ kind: 'final', reveal: 1, maxReveal: 5 });
  assert.ok(trickStep > finalRow);
  const place = autoHoldMs({ kind: 'podium', reveal: 2, maxReveal: 6 });
  const drum = autoHoldMs({ kind: 'podium', reveal: 5, maxReveal: 6, drumroll: true });
  const win = autoHoldMs({ kind: 'podium', reveal: 6, maxReveal: 6, winner: true });
  assert.ok(win > place && place > drum);
  // The bar race waits for its animation (~3.5 s) plus reading time, more with more rows.
  assert.ok(autoHoldMs({ kind: 'standings', reveal: 0, maxReveal: 0, rows: 3 }) > 3500 + 3000);
  assert.ok(autoHoldMs({ kind: 'standings', reveal: 0, maxReveal: 0, rows: 10 }) > autoHoldMs({ kind: 'standings', reveal: 0, maxReveal: 0, rows: 3 }));
  assert.ok(autoHoldMs({ kind: 'unknown-kind', reveal: 0, maxReveal: 0 }) > 0);
});

test('sound settings: off by default, volume clamped, junk ignored', () => {
  assert.deepEqual(parseSfxPrefs(null, null), { on: false, volume: DEFAULT_VOLUME });
  assert.deepEqual(parseSfxPrefs('1', '0.8'), { on: true, volume: 0.8 });
  assert.equal(parseSfxPrefs('true', '7').on, false);
  assert.equal(parseSfxPrefs('1', '7').volume, 1);
  assert.equal(parseSfxPrefs('1', 'loud').volume, DEFAULT_VOLUME);
});
