import { test } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import type { CaseResult, CategoryInfo } from '../src/core/types.ts';

// Sandbox data + config before any module reads the paths.
const sandbox = mkdtempSync(join(tmpdir(), 'gauntlet-versus-'));
process.env.GAUNTLET_DATA_DIR = join(sandbox, 'data');
process.env.GAUNTLET_CONFIG_DIR = join(sandbox, 'config');
cpSync(resolve(import.meta.dirname, '..', 'config'), join(sandbox, 'config'), { recursive: true });

const { buildVersus, buildVersusOptions, fighterOf, quoteResponse, VersusInputError, TIE_MARGIN } = await import('../src/versus/build.ts');
const { renderVersusCardHtml, versusCardFileName } = await import('../src/versus/card.ts');
const { resultHeadline, roundVerdict, momentLine, tally } = await import('../src/versus/words.ts');
const { cornerColors } = await import('../src/versus/colors.ts');
type VersusResult = import('../src/versus/build.ts').VersusResult;

const CATS: CategoryInfo[] = [
  { id: 'math', name: 'Mathematics', description: '', color: '#0EA5E9', weight: 1 },
  { id: 'coding', name: 'Coding', description: '', color: '#10B981', weight: 1 },
];
const TESTS = [
  { id: 'math.one', name: 'Arithmetic', category: 'math', hook: 'Sums that trip people up' },
  { id: 'math.two', name: 'Algebra', category: 'math' },
  { id: 'code.one', name: 'Merge Intervals', category: 'coding' },
  { id: 'code.two', name: 'Only A ran this', category: 'coding' },
  { id: 'code.three', name: 'Nobody ran this', category: 'coding' },
  { id: 'code.four', name: 'Different cases', category: 'coding' },
];

const A = fighterOf({ id: 'alpha', label: 'Alpha Pro', vendor: 'Acme', color: '#3987e5', pricing: { inputPerM: 3, outputPerM: 15 }, contextWindow: 200000 });
const B = fighterOf({ id: 'beta', label: 'Beta Flash', vendor: 'Globex', color: '#e5733a', pricing: { inputPerM: 0.3, outputPerM: 1.2 }, contextWindow: 1000000 });

let seq = 0;
function res(p: { who: string; test: string; caseId: string; score: number | null; passed?: boolean | null; rep?: number; cost?: number; ms?: number; reply?: string; extracted?: string; expected?: unknown; status?: CaseResult['status']; prompt?: string; at?: string }): VersusResult {
  seq++;
  return {
    key: `${p.who}::${p.test}::${p.caseId}::r${p.rep ?? 0}`,
    runId: 'run-1',
    contestantId: p.who,
    testId: p.test,
    caseId: p.caseId,
    status: p.status ?? 'ok',
    score: p.score,
    passed: p.passed === undefined ? (p.score === null ? null : p.score >= 0.5) : p.passed,
    summary: p.score === 1 ? 'Correct' : 'Wrong',
    finishedAt: p.at ?? `2026-09-01T00:00:${String(seq % 60).padStart(2, '0')}Z`,
    scoreDetail: { ...(p.extracted !== undefined ? { extracted: p.extracted } : {}), ...(p.expected !== undefined ? { expected: p.expected } : {}) },
    metrics: { costUsd: p.cost ?? 0.01, wallMs: p.ms ?? 1000, outputTokens: 100, outputTokensPerSec: 50 },
    transcript: [
      {
        messages: [{ role: 'user', content: p.prompt ?? `Question ${p.caseId}: what is 6 × 7?` }],
        response: p.reply ?? `FINAL ANSWER: ${p.extracted ?? '?'}`,
        usage: { inputTokens: 10, outputTokens: 100, reasoningTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 },
        ttftMs: 100,
        totalMs: 1000,
        stopReason: 'end',
        rawStopReason: 'end',
        costUsd: p.cost ?? 0.01,
        retries: 0,
      },
      // A judge call after the model's reply must never be quoted as the model's answer.
      { judge: true, messages: [{ role: 'user', content: 'grade this' }], response: 'JUDGE SAYS 1', usage: { inputTokens: 1, outputTokens: 1, reasoningTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 }, ttftMs: 1, totalMs: 1, stopReason: 'end', rawStopReason: 'end', costUsd: 0, retries: 0 },
    ],
  };
}

function fixture(): VersusResult[] {
  return [
    // math.one: A 1+1 → 100, B 1+0 → 50. A wins; case c2 is the decisive moment (A right, B wrong).
    res({ who: 'alpha', test: 'math.one', caseId: 'c1', score: 1, extracted: '42', expected: 42 }),
    res({ who: 'alpha', test: 'math.one', caseId: 'c2', score: 1, extracted: '42', expected: 42, reply: 'Six sevens are 42.\nFINAL ANSWER: 42' }),
    res({ who: 'beta', test: 'math.one', caseId: 'c1', score: 1, extracted: '42', expected: 42 }),
    res({ who: 'beta', test: 'math.one', caseId: 'c2', score: 0, extracted: '48', expected: 42, reply: 'Six eights... FINAL ANSWER: 48' }),
    // math.two: 0.5 vs 0.51 → a draw (under 2 points apart).
    res({ who: 'alpha', test: 'math.two', caseId: 'c1', score: 0.5, passed: null }),
    res({ who: 'beta', test: 'math.two', caseId: 'c1', score: 0.51, passed: null }),
    // code.one: B wins; A repeats twice (cost per pass must not double).
    res({ who: 'alpha', test: 'code.one', caseId: 'c1', score: 0, rep: 0, cost: 0.2, ms: 4000 }),
    res({ who: 'alpha', test: 'code.one', caseId: 'c1', score: 0, rep: 1, cost: 0.4, ms: 6000 }),
    res({ who: 'beta', test: 'code.one', caseId: 'c1', score: 1, cost: 0.05, ms: 2000 }),
    // code.two: only A.
    res({ who: 'alpha', test: 'code.two', caseId: 'c1', score: 1 }),
    // code.four: no case in common.
    res({ who: 'alpha', test: 'code.four', caseId: 'x', score: 1 }),
    res({ who: 'beta', test: 'code.four', caseId: 'y', score: 1 }),
    // Other models and errors are ignored.
    res({ who: 'gamma', test: 'math.one', caseId: 'c1', score: 1 }),
    res({ who: 'beta', test: 'math.one', caseId: 'c3', score: null, status: 'error' }),
  ];
}

const build = (results = fixture(), extra: Partial<Parameters<typeof buildVersus>[0]> = {}) =>
  buildVersus({ scope: { kind: 'combined' }, a: A, b: B, tests: TESTS, categories: CATS, results, now: '2026-09-28T00:00:00Z', ...extra });

test('rounds, winners, draws and the overall result', () => {
  const d = build();
  assert.deepEqual(
    d.rounds.map((r) => [r.testId, r.winner]),
    [
      ['math.one', 'a'],
      ['math.two', 'tie'],
      ['code.one', 'b'],
    ],
  );
  assert.equal(d.tieMargin, TIE_MARGIN);
  assert.equal(d.ties, 1);
  assert.equal(d.totals.a.roundsWon, 1);
  assert.equal(d.totals.b.roundsWon, 1);
  assert.equal(d.winner, 'tie');
  assert.equal(resultHeadline(d), 'It’s a draw, 1–1');
  const r0 = d.rounds[0]!;
  assert.equal(r0.a.score, 1);
  assert.equal(r0.b.score, 0.5);
  assert.equal(r0.margin, 50);
  assert.equal(r0.hook, 'Sums that trip people up');
  assert.equal(r0.categoryName, 'Mathematics');
  assert.equal(roundVerdict(r0, d), 'Alpha Pro wins by 50 points');
  assert.equal(roundVerdict(d.rounds[1]!, d), 'Draw: less than 2 points apart');
  // The errored B result on c3 is not a shared case.
  assert.equal(r0.cases, 2);
});

test('missing results: only-one-model tests and tests without shared cases are skipped, never compared', () => {
  const d = build();
  assert.deepEqual(d.skipped, [
    { testId: 'code.two', testName: 'Only A ran this', reason: 'only-a' },
    { testId: 'code.four', testName: 'Different cases', reason: 'no-shared-cases' },
  ]);
  assert.ok(!d.rounds.some((r) => r.testId === 'code.three'));
  const swapped = buildVersus({ scope: { kind: 'combined' }, a: B, b: A, tests: TESTS, categories: CATS, results: fixture() });
  assert.equal(swapped.skipped[0]!.reason, 'only-b');
});

test('only cases both models answered are compared', () => {
  const results = [
    res({ who: 'alpha', test: 'math.one', caseId: 'c1', score: 1 }),
    res({ who: 'alpha', test: 'math.one', caseId: 'c2', score: 1 }),
    res({ who: 'alpha', test: 'math.one', caseId: 'c3', score: 1 }),
    res({ who: 'beta', test: 'math.one', caseId: 'c1', score: 0 }),
  ];
  const d = build(results);
  assert.equal(d.rounds[0]!.cases, 1);
  assert.equal(d.rounds[0]!.a.samples, 1);
});

test('cost and time are for one pass (repeats are averaged, not added)', () => {
  const d = build();
  const r = d.rounds.find((x) => x.testId === 'code.one')!;
  assert.equal(r.a.costUsd, 0.3);
  assert.equal(r.a.timeMs, 5000);
  assert.equal(r.b.costUsd, 0.05);
  assert.equal(d.totals.a.costUsd, 0.33);
  assert.equal(d.totals.a.avgScore, 50);
  assert.equal(d.totals.b.avgScore, 67);
  assert.equal(d.totals.a.value, Math.round((50 / 0.33) * 10) / 10);
  assert.equal(d.totals.a.tokensPerSec, 50);
});

test('decisive moment quotes the recorded answers of the case one got right and the other wrong', () => {
  const d = build();
  const m = d.rounds[0]!.moment!;
  assert.ok(m);
  assert.equal(m.caseId, 'c2');
  assert.equal(m.right, 'a');
  assert.equal(m.expected, '42');
  assert.equal(m.a.extracted, '42');
  assert.equal(m.b.extracted, '48');
  assert.match(m.a.quote, /Six sevens are 42/);
  assert.match(m.b.quote, /FINAL ANSWER: 48/);
  assert.ok(!m.a.quote.includes('JUDGE'));
  assert.match(m.question!, /what is 6 × 7/);
  assert.equal(momentLine(d.rounds[0]!, d), 'Same question: Alpha Pro got it right, Beta Flash got it wrong.');
  // A draw with partial scores and no pass/fail has no moment; nothing is made up.
  assert.equal(d.rounds[1]!.moment, null);
});

test('the moment prefers a case the round winner got right', () => {
  const results = [
    res({ who: 'alpha', test: 'math.one', caseId: 'c1', score: 0 }),
    res({ who: 'beta', test: 'math.one', caseId: 'c1', score: 1 }),
    res({ who: 'alpha', test: 'math.one', caseId: 'c2', score: 1 }),
    res({ who: 'beta', test: 'math.one', caseId: 'c2', score: 0 }),
    res({ who: 'alpha', test: 'math.one', caseId: 'c3', score: 1 }),
    res({ who: 'beta', test: 'math.one', caseId: 'c3', score: 0 }),
  ];
  const d = build(results);
  assert.equal(d.rounds[0]!.winner, 'a');
  assert.equal(d.rounds[0]!.moment!.right, 'a');
});

test('ties everywhere give a draw; empty data gives no rounds', () => {
  const results = [res({ who: 'alpha', test: 'math.one', caseId: 'c1', score: 0.8 }), res({ who: 'beta', test: 'math.one', caseId: 'c1', score: 0.8 })];
  const d = build(results);
  assert.equal(d.winner, 'tie');
  assert.equal(roundVerdict(d.rounds[0]!, d), 'Draw: identical scores');
  const empty = build([]);
  assert.equal(empty.rounds.length, 0);
  assert.equal(resultHeadline(empty), 'No shared tests yet');
  assert.equal(empty.totals.a.avgScore, null);
  assert.equal(empty.totals.a.value, null);
});

test('a custom draw margin changes the verdict', () => {
  const d = build(fixture(), { tieMargin: 60 });
  assert.equal(d.rounds[0]!.winner, 'tie');
});

test('same model twice is refused', () => {
  assert.throws(() => buildVersus({ scope: { kind: 'combined' }, a: A, b: A, tests: TESTS, categories: CATS, results: [] }), (e: unknown) => e instanceof VersusInputError && e.status === 400);
});

test('baseline and manual fighters have no price; value is not recorded without cost', () => {
  const base = fighterOf({ id: 'random-baseline', label: 'Random Baseline', vendor: 'Gauntlet', color: '#888', pricing: { inputPerM: 0, outputPerM: 0 }, contextWindow: undefined }, { baseline: true });
  assert.equal(base.pricing, null);
  assert.equal(base.contextWindow, null);
  assert.equal(base.baseline, true);
  const results = [res({ who: 'alpha', test: 'math.one', caseId: 'c1', score: 1 }), res({ who: 'random-baseline', test: 'math.one', caseId: 'c1', score: 0, cost: 0 })];
  const d = buildVersus({ scope: { kind: 'combined' }, a: A, b: base, tests: TESTS, categories: CATS, results });
  assert.equal(d.totals.b.value, null);
  assert.equal(resultHeadline(d), 'Alpha Pro wins 1–0');
  assert.equal(tally(d), '1–0');
});

test('quotes are short and keep the answer', () => {
  const long = `${'Let me think about this carefully. '.repeat(30)}So the total is 1234 apples.\nFINAL ANSWER: 1234\nThanks for asking, I hope this helps you with the homework.`;
  const q = quoteResponse(long, '1234');
  assert.ok(q.truncated);
  assert.ok(q.quote.length <= 240, `quote is ${q.quote.length} chars`);
  assert.match(q.quote, /FINAL ANSWER: 1234/);
  const noAnswer = quoteResponse('word '.repeat(200));
  assert.ok(noAnswer.truncated && noAnswer.quote.length <= 240);
  assert.deepEqual(quoteResponse('short', '1'), { quote: 'short', truncated: false });
});

test('options list models with results and suggest the two best that share tests', () => {
  const C = fighterOf({ id: 'gamma', label: 'Gamma', vendor: 'Initech', color: '#22c55e', pricing: { inputPerM: 1, outputPerM: 2 }, contextWindow: 8000 });
  const Z = fighterOf({ id: 'zzz', label: 'No results', vendor: 'x', color: '#000', pricing: { inputPerM: 1, outputPerM: 2 } });
  const o = buildVersusOptions({ kind: 'combined' }, [A, B, C, Z], TESTS, fixture(), []);
  assert.deepEqual(o.fighters.map((f) => f.id).sort(), ['alpha', 'beta', 'gamma']);
  assert.equal(o.fighters.find((f) => f.id === 'alpha')!.tests, 5);
  assert.ok(o.suggested);
  assert.notEqual(o.suggested![0], o.suggested![1]);
});

test('Shorts card: fixed size, escaped long names, plain-English headline', () => {
  const longA = { ...A, label: 'Extremely Long Experimental Reasoning Model <Preview> Edition 2026' };
  const d = buildVersus({ scope: { kind: 'run', runId: 'r', runName: 'September & friends' }, a: longA, b: B, tests: TESTS, categories: CATS, results: fixture().map((r) => (r.score === 0.51 ? { ...r, score: 0.9 } : r)) });
  const html = renderVersusCardHtml(d);
  assert.match(html, /width:1080px;height:1920px/);
  assert.ok(html.includes('&lt;Preview&gt;'));
  assert.ok(!html.includes('<Preview>'));
  assert.ok(html.includes('September &amp; friends'));
  assert.ok(!/<script/i.test(html));
  assert.ok(!/https?:\/\//.test(html.replace(/xmlns="http:\/\/www\.w3\.org\/2000\/svg"/g, '')));
  assert.equal(versusCardFileName(d), 'versus-extremely-long-experimental-reasoning-mo-vs-beta-flash.png');
});

test('corner colours: alike colours are pulled apart, different ones kept', () => {
  assert.deepEqual(cornerColors('#3987e5', '#e5733a'), { a: '#3987e5', b: '#e5733a' });
  const c = cornerColors('#d97757', '#d97757');
  assert.equal(c.a, '#d97757');
  assert.notEqual(c.b, '#d97757');
});

test('server: a model not in the run is a 404; combined scope skips stale results', async () => {
  const { contestantConfigHash, loadContestants } = await import('../src/core/config.ts');
  const { loadTests } = await import('../src/core/registry.ts');
  const { versusFor, versusOptions } = await import('../src/versus/server.ts');
  const [c1, c2, c3] = loadContestants();
  const t = loadTests()[0]!;
  const runId = 'run-versus-test';
  const dir = join(sandbox, 'data', 'runs', runId);
  mkdirSync(dir, { recursive: true });
  const snap = (c: typeof c1) => ({ ...c!, configHash: contestantConfigHash(c!) });
  writeFileSync(
    join(dir, 'manifest.json'),
    JSON.stringify({ id: runId, name: 'Test run', status: 'completed', createdAt: '2026-09-01T00:00:00Z', harnessVersion: 'x', node: 'x', platform: 'x', fingerprint: 'f', tests: [{ id: t.definition.id, version: t.definition.version, hash: t.hash, name: t.definition.name, category: t.definition.category, kind: t.definition.kind, caseIds: ['c1'], weight: 1 }], contestants: [snap(c1), snap(c2)], judges: [], settings: { repeats: 1, concurrency: 1, temperature: 0, protocolVersion: 'x' }, totalJobs: 2 }),
  );
  const row = (c: typeof c1, score: number, testHash = t.hash) => ({ ...res({ who: c!.id, test: t.definition.id, caseId: 'c1', score }), runId, testVersion: '1', testHash, contestantHash: contestantConfigHash(c!), repeat: 0, artifacts: [], startedAt: '2026-09-01T00:00:00Z' });
  writeFileSync(join(dir, 'results.jsonl'), [row(c1, 1), row(c2, 0)].map((r) => JSON.stringify(r)).join('\n') + '\n');

  const d = versusFor(c1!.id, c2!.id, runId);
  assert.equal(d.scope.kind, 'run');
  assert.equal(d.rounds.length, 1);
  assert.equal(d.winner, 'a');
  assert.throws(() => versusFor(c1!.id, c3!.id, runId), (e: unknown) => e instanceof VersusInputError && e.status === 404 && /not in this run/.test(e.message));
  assert.throws(() => versusFor(c1!.id, c2!.id, 'no-such-run'), (e: unknown) => e instanceof VersusInputError && e.status === 404);
  assert.throws(() => versusFor(c1!.id, 'nobody'), (e: unknown) => e instanceof VersusInputError && e.status === 404);

  // Combined scope pools current results; a result with an old test hash is ignored.
  const staleRun = 'run-versus-stale';
  mkdirSync(join(sandbox, 'data', 'runs', staleRun), { recursive: true });
  writeFileSync(join(sandbox, 'data', 'runs', staleRun, 'manifest.json'), JSON.stringify({ id: staleRun, name: 'Old', status: 'completed', createdAt: '2026-08-01T00:00:00Z', tests: [], contestants: [], judges: [], settings: {}, totalJobs: 0 }));
  writeFileSync(join(sandbox, 'data', 'runs', staleRun, 'results.jsonl'), JSON.stringify({ ...row(c2, 1, 'old-hash'), runId: staleRun, key: 'stale' }) + '\n');
  const combined = versusFor(c1!.id, c2!.id);
  assert.equal(combined.scope.kind, 'combined');
  assert.equal(combined.rounds[0]!.b.score, 0);
  const opts = versusOptions();
  assert.deepEqual(opts.suggested?.slice().sort(), [c1!.id, c2!.id].sort());
  assert.ok(opts.runs.some((r) => r.id === runId));
});
