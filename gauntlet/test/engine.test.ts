import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, cpSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';

// A fake OpenAI-compatible API that answers "What is i + i?" correctly, so the full API path is exercised offline.
const fakeApi = createServer(async (req, res) => {
  let raw = '';
  for await (const c of req) raw += c;
  const body = JSON.parse(raw || '{}') as { messages?: Array<{ content: string }> };
  const prompt = body.messages?.at(-1)?.content ?? '';
  const i = Number(prompt.match(/What is (\d+) \+/)?.[1] ?? 0);
  const chunk = (delta: unknown, finish: string | null = null) => `data: ${JSON.stringify({ id: 'x', object: 'chat.completion.chunk', created: 1, model: 'fake-1', choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  res.write(chunk({ role: 'assistant', content: `FINAL ANSWER: ${2 * i}` }));
  res.write(chunk({}, 'stop'));
  res.write(`data: ${JSON.stringify({ id: 'x', object: 'chat.completion.chunk', created: 1, model: 'fake-1', choices: [], usage: { prompt_tokens: 1000, completion_tokens: 500 } })}\n\n`);
  res.end('data: [DONE]\n\n');
});
await new Promise<void>((r) => fakeApi.listen(0, '127.0.0.1', r));
process.env.FAKE_API_KEY = 'k';

// Isolate the test library and run storage before any Gauntlet module is loaded.
const sandbox = mkdtempSync(join(tmpdir(), 'gauntlet-engine-'));
process.env.GAUNTLET_TESTS_DIR = join(sandbox, 'tests');
process.env.GAUNTLET_DATA_DIR = join(sandbox, 'data');
process.env.GAUNTLET_NO_BROWSER = '1';
// A private copy of the config with an expensive mock model (for budget-cap tests).
process.env.GAUNTLET_CONFIG_DIR = join(sandbox, 'config');
cpSync(new URL('../config', import.meta.url), join(sandbox, 'config'), { recursive: true });
{
  const file = join(sandbox, 'config', 'models.json');
  const models = JSON.parse(readFileSync(file, 'utf8'));
  models.providers.push({ id: 'fakeapi', type: 'openai-compatible', label: 'Fake API', baseUrl: `http://127.0.0.1:${(fakeApi.address() as AddressInfo).port}/v1`, apiKeyEnv: 'FAKE_API_KEY', maxConcurrency: 4 });
  models.contestants.push({ id: 'fake-api', label: 'Fake API Model', vendor: 'FakeCo', provider: 'fakeapi', model: 'fake-1', color: '#654321', enabled: true, pricing: { inputPerM: 2, outputPerM: 10, verifiedAt: '2026-01-01' } });
  models.contestants.push({ id: 'mock-priced', label: 'Priced Mock', vendor: 'Test', provider: 'baseline', model: 'random', color: '#123456', enabled: true, pricing: { inputPerM: 1_000_000, outputPerM: 0, verifiedAt: '2026-01-01' } });
  writeFileSync(file, JSON.stringify(models));
}
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
const manual = await import('../src/providers/manual.ts');
const grade = await import('../src/engine/grade.ts');

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

test('manual contestant: prompts wait in the inbox and pasted replies are graded like API replies', async () => {
  const runId = await runner.startRun({ contestantIds: ['manual-chat'], testIds: ['math.arith'], repeats: 1 });
  const seen = new Set<string>();
  // Answer every pending request correctly (the prompt is "What is i + i?").
  const deadline = Date.now() + 10_000;
  while (seen.size < 6 && Date.now() < deadline) {
    for (const req of manual.listManualRequests(runId)) {
      if (seen.has(req.id)) continue;
      seen.add(req.id);
      assert.match(req.combinedPrompt, /FINAL ANSWER: <answer>/, 'the pasteable prompt includes the answer-format instruction');
      assert.equal(req.isContinuation, false);
      const i = Number(req.latestUserMessage.match(/What is (\d+) \+/)![1]);
      assert.ok(manual.submitManual(req.id, { text: `Easy.\nFINAL ANSWER: ${2 * i}`, outputTokens: 12, costUsd: 0.001 }));
    }
    await new Promise((r) => setTimeout(r, 20));
  }
  await runner.waitForRun(runId);
  const results = store.readResults(runId);
  assert.equal(results.length, 6);
  assert.ok(results.every((r) => r.score === 1), 'correct pasted answers score 100%');
  assert.ok(results.every((r) => r.metrics.costUsd === 0.001), 'user-entered cost is recorded');
  assert.ok(results.every((r) => r.metrics.ttftMs === null), 'no fake latency numbers for manual entries');
  assert.equal(boards.runLeaderboard(runId)!.rows[0]!.manual, true);
});

test('budget cap stops a run before it overspends and resume can raise the cap', async () => {
  const runId = await runner.startRun({ contestantIds: ['mock-priced'], testIds: ['math.arith'], repeats: 2, concurrency: 1, maxCostUsd: 50 });
  await runner.waitForRun(runId);
  const m = store.readManifest(runId)!;
  assert.equal(m.status, 'cancelled');
  assert.match(m.error ?? '', /Budget cap of \$50\.00 reached/);
  const spent = store.readResults(runId).reduce((s, r) => s + r.metrics.costUsd, 0);
  assert.ok(store.readResults(runId).length < 12, 'stopped early');
  assert.ok(spent >= 50 && spent < 50 + 60, `spent ${spent}`);
  runner.resumeRun(runId, { maxCostUsd: 1_000_000 });
  await runner.waitForRun(runId);
  assert.equal(store.readResults(runId).length, 12);
  assert.equal(store.readManifest(runId)!.status, 'completed');
});

test('pasted replies can be graded directly; hedged answers are marked wrong', async () => {
  const ok = await grade.gradePasted({ testId: 'math.arith', caseId: 'c3', response: 'Two plus two.\nFINAL ANSWER: 4' });
  assert.equal(ok.outcome.score, 1);
  const hedged = await grade.gradePasted({ testId: 'math.arith', caseId: 'c3', response: 'FINAL ANSWER: 4 or 5' });
  assert.equal(hedged.outcome.score, 0);
  assert.match(hedged.outcome.summary, /Hedged/);
  await assert.rejects(grade.gradePasted({ testId: 'math.arith', caseId: 'nope', response: 'x' }), /Unknown case/);
});

test('judges never grade their own vendor when another judge is available', () => {
  const j = (id: string, vendor: string) => ({ id, label: id, vendor, provider: 'p', model: id, color: '#000000', enabled: true, pricing: { inputPerM: 1, outputPerM: 1 }, configHash: 'x' });
  const panel = [j('a1', 'Anthropic'), j('o1', 'OpenAI'), j('g1', 'Google')];
  const contestant = { ...j('a2', 'Anthropic') };
  assert.deepEqual(runner.selectJudges(panel, contestant, true).map((x) => x.id), ['o1', 'g1']);
  assert.deepEqual(runner.selectJudges([j('a1', 'Anthropic')], contestant, true).map((x) => x.id), ['a1'], 'falls back rather than having no judge');
  assert.deepEqual(runner.selectJudges(panel, j('a1', 'Anthropic'), false).map((x) => x.id), ['o1', 'g1'], 'a model never judges itself');
});

test('an API model runs end to end and calibrates cost estimates (baseline/manual results never do)', async () => {
  const before = await runner.estimateRun({ contestantIds: ['fake-api'], testIds: ['math.arith'], repeats: 1 });
  assert.equal(before.perTest[0]!.basis, 'definition', 'random-baseline and manual results are ignored');
  const runId = await runner.startRun({ contestantIds: ['fake-api'], testIds: ['math.arith'], repeats: 1 });
  await runner.waitForRun(runId);
  const results = store.readResults(runId);
  assert.ok(results.every((r) => r.score === 1 && r.status === 'ok'));
  assert.ok(results.every((r) => Math.abs(r.metrics.costUsd - (1000 * 2 + 500 * 10) / 1e6) < 1e-9), 'cost = tokens × pricing');
  const after = await runner.estimateRun({ contestantIds: ['fake-api'], testIds: ['math.arith'], repeats: 1 });
  assert.equal(after.perTest[0]!.basis, 'measured');
  assert.ok(Math.abs(after.perContestant[0]!.estCostUsd - 6 * 0.007) < 1e-4, 'estimate equals measured cost per case × cases');
  fakeApi.close();
});
