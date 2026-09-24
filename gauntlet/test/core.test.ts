import { test } from 'node:test';
import assert from 'node:assert/strict';
import { extractAllTagged, extractCodeBlock, extractFinalAnswer, extractTagged, parseJsonLoose, parseNumber } from '../src/core/extract.ts';
import { createRng } from '../src/core/rng.ts';
import { canonicalJson, contentHash } from '../src/core/hash.ts';
import { bootstrapCi, clusterMean, median } from '../src/core/stats.ts';
import { computeCost } from '../src/core/cost.ts';
import { checkConstraint, sentences, words } from '../src/scoring/constraints.ts';
import { compareJson } from '../src/scoring/json-compare.ts';
import { jsonEqual, runCodeTests } from '../src/scoring/code-sandbox.ts';
import { extractArtifact } from '../src/scoring/index.ts';
import { buildLeaderboard } from '../src/engine/aggregate.ts';
import type { CaseResult } from '../src/core/types.ts';

test('extractFinalAnswer takes the last marker and strips decoration', () => {
  assert.deepEqual(extractFinalAnswer('I think 3.\nFINAL ANSWER: 4\nwait\n**Final Answer:** `42`.'), { answer: '42', formatOk: true });
  assert.deepEqual(extractFinalAnswer('final answer: 3.5'), { answer: '3.5', formatOk: true });
  assert.deepEqual(extractFinalAnswer('The answer is\n\n17'), { answer: '17', formatOk: false });
});

test('parseNumber handles currency, separators, negatives and fractions', () => {
  assert.equal(parseNumber('$1,234.50'), 1234.5);
  assert.equal(parseNumber('−3 apples'), -3);
  assert.equal(parseNumber('3/4'), 0.75);
  assert.equal(parseNumber('about 1e3'), 1000);
  assert.equal(parseNumber('none'), null);
});

test('extractCodeBlock prefers the last block of a wanted language and tolerates unterminated fences', () => {
  const text = '```python\nprint(1)\n```\ntext\n```js\nfunction a(){}\n```\n```js\nfunction b(){}\n```';
  assert.equal(extractCodeBlock(text, ['js'])?.code.trim(), 'function b(){}');
  assert.equal(extractCodeBlock('```javascript\nfunction c(){}', ['javascript'])?.code.trim(), 'function c(){}');
});

test('extractTagged / extractAllTagged', () => {
  assert.equal(extractTagged('thinking...\nACTION: MOVE N\n**ACTION:** `GATHER`', 'ACTION'), 'GATHER');
  assert.deepEqual(extractAllTagged('A1: Paris\nA2: 42\nnoise', 'A\\d+'), ['Paris', '42']);
});

test('parseJsonLoose finds JSON inside fences and prose', () => {
  assert.deepEqual(parseJsonLoose('Here:\n```json\n{"a":1}\n```'), { a: 1 });
  assert.deepEqual(parseJsonLoose('Result: {"b":[1,2]} done'), { b: [1, 2] });
  assert.equal(parseJsonLoose('nothing'), undefined);
});

test('rng is deterministic and forks are independent', () => {
  const a = createRng(42);
  const b = createRng(42);
  const seqA = Array.from({ length: 5 }, () => a.next());
  const seqB = Array.from({ length: 5 }, () => b.next());
  assert.deepEqual(seqA, seqB);
  assert.notDeepEqual(createRng(42).fork('x').next(), createRng(42).fork('y').next());
  const r = createRng(7);
  for (let i = 0; i < 1000; i++) {
    const v = r.int(3, 5);
    assert.ok(v >= 3 && v <= 5);
  }
});

test('canonical hashing ignores key order', () => {
  assert.equal(canonicalJson({ b: 1, a: { d: 2, c: 3 } }), '{"a":{"c":3,"d":2},"b":1}');
  assert.equal(contentHash({ x: 1, y: 2 }), contentHash({ y: 2, x: 1 }));
});

test('cost uses separate cached/cache-write rates', () => {
  const cost = computeCost({ inputTokens: 1_000_000, outputTokens: 100_000, reasoningTokens: 50_000, cachedInputTokens: 1_000_000, cacheWriteTokens: 0 }, { inputPerM: 5, outputPerM: 25, cachedInputPerM: 0.5 });
  assert.equal(cost, 5 + 2.5 + 0.5);
});

test('stats: median, cluster mean and bootstrap CI', () => {
  assert.equal(median([3, 1, 2]), 2);
  assert.equal(median([4, 1, 2, 3]), 2.5);
  const clusters = [[1, 1], [0, 0], [1, 0], [1, 1]];
  assert.equal(clusterMean(clusters), 0.625);
  const ci = bootstrapCi(clusters, clusterMean, 500, 1)!;
  assert.ok(ci[0] <= 0.625 && ci[1] >= 0.625 && ci[0] >= 0 && ci[1] <= 1);
});

test('constraints: counting definitions', () => {
  assert.equal(words('Hello, world — 42 times!').length, 4);
  assert.equal(sentences('One. Two! Three? "Four." Five').length, 5);
  assert.equal(checkConstraint('apple\nbanana\ncherry', { check: 'acrostic', word: 'abc' }).passed, true);
  assert.equal(checkConstraint('- a\n- b\n* c\n1. d', { check: 'bullet_count', min: 4, max: 4 }).passed, true);
  assert.equal(checkConstraint('No e here? Wrong', { check: 'no_letter', letter: 'e' }).passed, false);
  assert.equal(checkConstraint('A Title Line\nAnother Good One', { check: 'title_case_lines' }).passed, true);
  assert.equal(checkConstraint('```json\n{"a":1,"b":2}\n```', { check: 'json_keys', keys: ['a', 'b'] }).passed, true);
  assert.equal(checkConstraint('the cat the dog', { check: 'include', text: 'the', min: 2, max: 2 }).passed, true);
  assert.equal(checkConstraint('Ends here.', { check: 'ends_with', text: 'here.' }).passed, true);
});

test('json compare: field-level partial credit, lenient numerics, unordered arrays', () => {
  const exp = { name: 'Ada Lovelace', total: 12.5, items: ['a', 'b'], missing: null, nested: { ok: true } };
  const r = compareJson(exp, { name: ' ada  lovelace ', total: '12.50', items: ['a', 'x'], nested: { ok: true } });
  // leaves: name ✓, total ✓, items length ✓, items[0] ✓, items[1] ✗, missing ✓ (absent == null), nested.ok ✓
  assert.equal(r.total, 7);
  assert.equal(r.matched, 6);
  const u = compareJson({ tags: ['x', 'y', 'z'] }, { tags: ['z', 'x', 'y'] }, { unorderedArrays: true });
  assert.equal(u.score, 1);
});

test('jsonEqual tolerance', () => {
  assert.ok(jsonEqual(0.1 + 0.2, 0.3));
  assert.ok(jsonEqual({ a: [1, { b: 2 }] }, { a: [1, { b: 2 }] }));
  assert.ok(!jsonEqual([1, 2], [2, 1]));
});

test('code sandbox: passes correct code, isolates bad code, enforces timeouts', { timeout: 60_000 }, async () => {
  const tests = [
    { args: [1, 2], expected: 3 },
    { args: [-1, 1], expected: 0 },
  ];
  const good = await runCodeTests('function add(a, b) { return a + b; }', 'add', tests);
  assert.equal(good.passed, 2);
  const arrow = await runCodeTests('export const add = (a, b) => a + b;', 'add', tests);
  assert.equal(arrow.passed, 2, 'const/arrow + export are supported');
  const loop = await runCodeTests('function add(a, b) { while (true) {} }', 'add', tests, 300);
  assert.equal(loop.passed, 0);
  assert.match(loop.items[0]!.detail ?? '', /Timed out/);
  const escape = await runCodeTests(
    "function add(){ const p = this.constructor.constructor('return process')(); return p.mainModule.require('fs').readFileSync('/etc/passwd','utf8').length; }",
    'add',
    [{ args: [], expected: 0 }],
  );
  assert.equal(escape.passed, 0, 'string code generation is disabled inside the sandbox');
  const missing = await runCodeTests('function other() {}', 'add', tests);
  assert.equal(missing.passed, 0);
  assert.match(missing.loadError ?? '', /not defined/);
});

test('artifact extraction for html and svg', () => {
  assert.match(extractArtifact('Here you go\n```html\n<!doctype html><html><body><canvas></canvas></body></html>\n```', 'html') ?? '', /^<!doctype/);
  assert.match(extractArtifact('<svg xmlns="http://www.w3.org/2000/svg"><rect/></svg>', 'svg') ?? '', /^<svg/);
  assert.equal(extractArtifact('no code here', 'html'), null);
});

function result(partial: Partial<CaseResult> & Pick<CaseResult, 'contestantId' | 'testId' | 'caseId' | 'score'>): CaseResult {
  return {
    key: `${partial.contestantId}::${partial.testId}::${partial.caseId}::r${partial.repeat ?? 0}`,
    runId: 'r',
    testVersion: '1.0.0',
    testHash: 'h',
    contestantHash: 'c',
    repeat: 0,
    status: 'ok',
    passed: (partial.score ?? 0) >= 1,
    summary: '',
    scoreDetail: {},
    metrics: { wallMs: 1000, ttftMs: 100, apiCalls: 1, inputTokens: 100, outputTokens: 100, reasoningTokens: 0, cachedInputTokens: 0, costUsd: 0.01, judgeCostUsd: 0, outputTokensPerSec: 50, retries: 0, responseChars: 10 },
    transcript: [],
    artifacts: [],
    startedAt: '',
    finishedAt: '',
    ...partial,
  };
}

test('leaderboard: index is the weighted mean of category means; medals and ranking', () => {
  const tests = [
    { id: 'math.a', name: 'A', category: 'math', weight: 1, version: '1', hash: 'h' },
    { id: 'math.b', name: 'B', category: 'math', weight: 1, version: '1', hash: 'h' },
    { id: 'coding.c', name: 'C', category: 'coding', weight: 1, version: '1', hash: 'h' },
  ];
  const contestants = [
    { id: 'm1', label: 'M1', vendor: 'V', provider: 'p', model: 'x', color: '#000000', enabled: true, pricing: { inputPerM: 1, outputPerM: 1 } },
    { id: 'm2', label: 'M2', vendor: 'V', provider: 'p', model: 'y', color: '#111111', enabled: true, pricing: { inputPerM: 1, outputPerM: 1 } },
  ];
  const results = [
    result({ contestantId: 'm1', testId: 'math.a', caseId: 'c1', score: 1 }),
    result({ contestantId: 'm1', testId: 'math.b', caseId: 'c1', score: 0 }),
    result({ contestantId: 'm1', testId: 'coding.c', caseId: 'c1', score: 1 }),
    result({ contestantId: 'm2', testId: 'math.a', caseId: 'c1', score: 0.5 }),
    result({ contestantId: 'm2', testId: 'math.b', caseId: 'c1', score: 0.5 }),
    result({ contestantId: 'm2', testId: 'coding.c', caseId: 'c1', score: 0 }),
    result({ contestantId: 'm2', testId: 'coding.c', caseId: 'c2', score: null, status: 'error' }),
  ];
  const board = buildLeaderboard({
    scope: { kind: 'run', runId: 'r' },
    fingerprint: 'f',
    categories: [
      { id: 'math', name: 'Math', description: '', color: '#000', weight: 1 },
      { id: 'coding', name: 'Coding', description: '', color: '#000', weight: 1 },
    ],
    tests,
    contestants,
    results,
  });
  const m1 = board.rows.find((r) => r.contestantId === 'm1')!;
  const m2 = board.rows.find((r) => r.contestantId === 'm2')!;
  assert.equal(m1.index, 75); // math 0.5, coding 1 → 0.75
  assert.equal(m2.index, 25); // math 0.5, coding 0 (error excluded) → 0.25
  assert.equal(m1.rank, 1);
  assert.equal(m2.totals.errors, 1);
  assert.equal(m1.medals.gold, 2); // math.a and coding.c
  assert.equal(m2.medals.gold, 1); // math.b
});
