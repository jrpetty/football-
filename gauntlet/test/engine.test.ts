import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

// Isolate the test library and run storage before any Gauntlet module is loaded.
const sandbox = mkdtempSync(join(tmpdir(), 'gauntlet-engine-'));
process.env.GAUNTLET_TESTS_DIR = join(sandbox, 'tests');
process.env.GAUNTLET_DATA_DIR = join(sandbox, 'data');
process.env.GAUNTLET_NO_BROWSER = '1';
mkdirSync(join(sandbox, 'tests', 'math'), { recursive: true });
mkdirSync(join(sandbox, 'tests', 'instruction'), { recursive: true });
mkdirSync(join(sandbox, 'tests', 'creative'), { recursive: true });

const numberTest = {
  kind: 'prompt',
  id: 'math.arith',
  version: '1.0.0',
  name: 'Arithmetic',
  category: 'math',
  description: 'Tiny arithmetic',
  difficulty: 'easy',
  scorer: { type: 'number' },
  cases: Array.from({ length: 6 }, (_, i) => ({ id: `c${i + 1}`, prompt: `What is ${i} + ${i}?`, expected: 2 * i })),
};
const constraintsTest = {
  kind: 'prompt',
  id: 'instruction.short',
  version: '1.0.0',
  name: 'Short',
  category: 'instruction',
  description: 'Constraint test',
  difficulty: 'easy',
  scorer: { type: 'constraints' },
  cases: [{ id: 'c1', prompt: 'Write one short sentence.', expected: [{ check: 'word_count', max: 200 }, { check: 'no_letter', letter: 'q' }] }],
};
const humanTest = {
  kind: 'prompt',
  id: 'creative.poem',
  version: '1.0.0',
  name: 'Poem',
  category: 'creative',
  description: 'Human-rated poem',
  difficulty: 'easy',
  scorer: { type: 'human', rubric: 'Is it a good poem?' },
  cases: [{ id: 'c1', prompt: 'Write a haiku about rain.' }],
};
writeFileSync(join(sandbox, 'tests', 'math', 'arith.json'), JSON.stringify(numberTest));
writeFileSync(join(sandbox, 'tests', 'instruction', 'short.json'), JSON.stringify(constraintsTest));
writeFileSync(join(sandbox, 'tests', 'creative', 'poem.json'), JSON.stringify(humanTest));

const runner = await import('../src/engine/runner.ts');
const store = await import('../src/engine/store.ts');
const boards = await import('../src/engine/leaderboards.ts');
const review = await import('../src/engine/review.ts');
const registry = await import('../src/core/registry.ts');

test('a full run completes, persists a reproducible manifest and scores every job', async () => {
  const runId = await runner.startRun({ contestantIds: ['random-baseline'], suiteId: 'all', repeats: 2, name: 'engine test' });
  await runner.waitForRun(runId);
  const manifest = store.readManifest(runId)!;
  assert.equal(manifest.status, 'completed');
  assert.equal(manifest.totalJobs, (6 + 1 + 1) * 2);
  assert.equal(manifest.tests.length, 3);
  assert.ok(manifest.fingerprint.length === 12);
  assert.equal(manifest.contestants[0]!.pricing.inputPerM, 0, 'pricing snapshot is stored with the run');
  const results = store.readResults(runId);
  assert.equal(results.length, manifest.totalJobs);
  for (const r of results) {
    assert.ok(r.transcript.length >= 1, 'every case has a transcript');
    assert.ok(r.metrics.wallMs > 0);
    if (r.testId === 'creative.poem') assert.equal(r.status, 'pending-human');
    else assert.equal(typeof r.score, 'number');
  }
  const board = boards.runLeaderboard(runId)!;
  assert.equal(board.rows.length, 1);
  assert.ok(board.rows[0]!.index !== null);
});

test('same fingerprint for the same tests; changing a prompt changes the hash and marks old results stale', async () => {
  const before = registry.fingerprint(registry.resolveTests({ suiteId: 'all' }));
  assert.equal(before, registry.fingerprint(registry.resolveTests({ suiteId: 'all' })));
  const combined1 = boards.combinedLeaderboard('all');
  assert.equal(combined1.staleExcluded, 0);

  const modified = { ...numberTest, version: '1.0.1', cases: numberTest.cases.map((c) => ({ ...c, prompt: c.prompt + ' Think.' })) };
  writeFileSync(join(sandbox, 'tests', 'math', 'arith.json'), JSON.stringify(modified));
  const after = registry.fingerprint(registry.resolveTests({ suiteId: 'all' }));
  assert.notEqual(before, after);
  const combined2 = boards.combinedLeaderboard('all');
  assert.ok(combined2.staleExcluded >= 12, 'results for the old prompt no longer count');
  writeFileSync(join(sandbox, 'tests', 'math', 'arith.json'), JSON.stringify(numberTest));
});

test('cancel then resume finishes exactly the missing jobs', async () => {
  const runId = await runner.startRun({ contestantIds: ['random-baseline'], testIds: ['math.arith'], repeats: 5, concurrency: 1 });
  // Let a few jobs finish, then cancel.
  await new Promise<void>((resolve) => {
    let n = 0;
    runner.subscribe(runId, (e) => {
      if (e.type === 'job.finished' && ++n === 3) resolve();
    });
  });
  runner.cancelRun(runId);
  await runner.waitForRun(runId);
  const partial = store.readResults(runId).length;
  assert.equal(store.readManifest(runId)!.status, 'cancelled');
  assert.ok(partial >= 3 && partial < 30);

  runner.resumeRun(runId);
  await runner.waitForRun(runId);
  const final = store.readResults(runId);
  assert.equal(store.readManifest(runId)!.status, 'completed');
  assert.equal(final.length, 30);
  assert.equal(new Set(final.map((r) => r.key)).size, 30, 'no duplicate keys');
});

test('resume refuses when a test changed since the run started', async () => {
  const runId = await runner.startRun({ contestantIds: ['random-baseline'], testIds: ['math.arith'], repeats: 1 });
  await runner.waitForRun(runId);
  const original = readFileSync(join(sandbox, 'tests', 'math', 'arith.json'), 'utf8');
  writeFileSync(join(sandbox, 'tests', 'math', 'arith.json'), JSON.stringify({ ...numberTest, description: 'changed' }));
  assert.throws(() => runner.resumeRun(runId), /changed since the run started/);
  writeFileSync(join(sandbox, 'tests', 'math', 'arith.json'), original);
});

test('human review turns a pending result into a scored one', async () => {
  const queue = review.reviewQueue('creative.poem');
  assert.ok(queue.length > 0);
  const item = queue.find((q) => q.status === 'pending-human')!;
  const updated = review.submitHumanScore({ runId: item.runId, key: item.key, score: 0.8, rater: 'alice' });
  assert.equal(updated.status, 'ok');
  assert.equal(updated.score, 0.8);
  const again = review.submitHumanScore({ runId: item.runId, key: item.key, score: 0.6, rater: 'bob' });
  assert.equal(again.score, 0.7);
  const replaced = review.submitHumanScore({ runId: item.runId, key: item.key, score: 1, rater: 'bob' });
  assert.equal(replaced.score, 0.9, 'a rater re-scoring replaces their previous score');
});

test('validateTest reports actionable errors', () => {
  const errors = registry.validateTest({ ...numberTest, id: 'Bad Id', version: '1', category: 'nope', cases: [{ id: 'c1', prompt: 'x', expected: 'not a number' }] } as never);
  assert.ok(errors.some((e) => /id must look like/.test(e)));
  assert.ok(errors.some((e) => /version must be semantic/.test(e)));
  assert.ok(errors.some((e) => /category "nope"/.test(e)));
  assert.ok(errors.some((e) => /numeric expected/.test(e)));
});

test('estimates scale with repeats and include per-model costs', async () => {
  const one = await runner.estimateRun({ contestantIds: ['random-baseline', 'claude-opus-5'], suiteId: 'all', repeats: 1 });
  const three = await runner.estimateRun({ contestantIds: ['random-baseline', 'claude-opus-5'], suiteId: 'all', repeats: 3 });
  assert.equal(three.jobs, one.jobs * 3);
  assert.ok(Math.abs(three.estCostUsd - one.estCostUsd * 3) < 1e-3, "estimates are linear in repeats (up to rounding)");
  assert.equal(one.perContestant.find((p) => p.contestantId === 'random-baseline')!.estCostUsd, 0);
});
