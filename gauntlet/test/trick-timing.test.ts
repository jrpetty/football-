import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, cpSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';

/**
 * Time pressure end to end: a fake OpenAI-compatible API that is slow on
 * purpose. Late replies must score 0 with status "timeout"; queueing and
 * retry back-off must not count against the model.
 */

const seen: string[] = [];
let retryHits = 0;
const fakeApi = createServer(async (req, res) => {
  let raw = '';
  for await (const c of req) raw += c;
  const body = JSON.parse(raw || '{}') as { messages?: Array<{ content: string }> };
  const prompt = body.messages?.at(-1)?.content ?? '';
  seen.push(prompt);
  const delay = Number(prompt.match(/DELAY=(\d+)/)?.[1] ?? 0);
  if (/RETRY-ONCE/.test(prompt) && retryHits++ === 0) {
    res.writeHead(429, { 'content-type': 'application/json', 'retry-after': '1' });
    res.end(JSON.stringify({ error: { message: 'slow down', type: 'rate_limit' } }));
    return;
  }
  let closed = false;
  res.on('close', () => (closed = true));
  await new Promise((r) => setTimeout(r, delay));
  if (closed) return;
  const chunk = (delta: unknown, finish: string | null = null) => `data: ${JSON.stringify({ id: 'x', object: 'chat.completion.chunk', created: 1, model: 'fake-1', choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  res.write(chunk({ role: 'assistant', content: 'FINAL ANSWER: 12' }));
  res.write(chunk({}, 'stop'));
  res.write(`data: ${JSON.stringify({ id: 'x', object: 'chat.completion.chunk', created: 1, model: 'fake-1', choices: [], usage: { prompt_tokens: 100, completion_tokens: 5 } })}\n\n`);
  res.end('data: [DONE]\n\n');
});
await new Promise<void>((r) => fakeApi.listen(0, '127.0.0.1', r));
process.env.FAKE_TRICK_KEY = 'k';

const sandbox = mkdtempSync(join(tmpdir(), 'gauntlet-trick-'));
process.env.GAUNTLET_TESTS_DIR = join(sandbox, 'tests');
process.env.GAUNTLET_DATA_DIR = join(sandbox, 'data');
process.env.GAUNTLET_NO_BROWSER = '1';
process.env.GAUNTLET_CONFIG_DIR = join(sandbox, 'config');
cpSync(new URL('../config', import.meta.url), join(sandbox, 'config'), { recursive: true });
{
  const file = join(sandbox, 'config', 'models.json');
  const models = JSON.parse(readFileSync(file, 'utf8'));
  models.providers.push({ id: 'slowapi', type: 'openai-compatible', label: 'Slow API', baseUrl: `http://127.0.0.1:${(fakeApi.address() as AddressInfo).port}/v1`, apiKeyEnv: 'FAKE_TRICK_KEY', maxConcurrency: 1 });
  models.contestants.push({ id: 'slow-api', label: 'Slow Model', vendor: 'SlowCo', provider: 'slowapi', model: 'fake-1', color: '#654321', enabled: true, pricing: { inputPerM: 1, outputPerM: 1, verifiedAt: '2026-01-01' } });
  writeFileSync(file, JSON.stringify(models));
}
mkdirSync(join(sandbox, 'tests', 'trick'), { recursive: true });
writeFileSync(
  join(sandbox, 'tests', 'trick', 'timed.json'),
  JSON.stringify({
    kind: 'prompt',
    id: 'trick.timed',
    version: '1.0.0',
    name: 'Timed',
    category: 'trick',
    description: 'Time-limit fixture',
    difficulty: 'easy',
    answerWithinSec: 1,
    scorer: { type: 'number' },
    cases: [
      { id: 'fast', prompt: 'What is 3 + 3 x 3? DELAY=0', expected: 12 },
      { id: 'slow', prompt: 'What is 3 + 3 x 3? DELAY=2500', expected: 12 },
      // One provider slot: 'queued' waits behind the others; the wait must not count.
      { id: 'queued', prompt: 'What is 3 + 3 x 3? DELAY=200', expected: 12 },
      // A 429 with a 1 s back-off, then a 700 ms answer: 1.7 s in total, but only 0.7 s is the model's.
      { id: 'retry', prompt: 'What is 3 + 3 x 3? RETRY-ONCE DELAY=700', expected: 12 },
      // A per-case limit overrides the test's.
      { id: 'roomy', prompt: 'What is 3 + 3 x 3? DELAY=1500', expected: 12, answerWithinSec: 5 },
    ],
  }),
);

const runner = await import('../src/engine/runner.ts');
const store = await import('../src/engine/store.ts');
const boards = await import('../src/engine/leaderboards.ts');

test('a slow model runs out of time: score 0, status timeout, "Out of time"; fast, queued, retried and per-case-limited answers count', async () => {
  const runId = await runner.startRun({ contestantIds: ['slow-api'], testIds: ['trick.timed'], repeats: 1, concurrency: 5 });
  await runner.waitForRun(runId);
  const byCase = new Map(store.readResults(runId).map((r) => [r.caseId, r]));
  const slow = byCase.get('slow')!;
  assert.equal(slow.status, 'timeout');
  assert.equal(slow.score, 0);
  assert.equal(slow.passed, false);
  assert.match(slow.summary, /^Out of time: no answer within 1 s/);
  assert.equal(slow.scoreDetail.outOfTime, true);
  assert.equal(slow.scoreDetail.timeLimitSec, 1);
  assert.ok((slow.scoreDetail.responseMs as number) >= 1000 && (slow.scoreDetail.responseMs as number) < 2400, 'aborted at the limit, not when the reply finally came');
  assert.equal(slow.transcript[0]!.rawStopReason, 'out_of_time');

  for (const id of ['fast', 'queued', 'retry', 'roomy']) {
    const r = byCase.get(id)!;
    assert.equal(r.status, 'ok', `${id}: ${r.summary}`);
    assert.equal(r.score, 1, id);
    assert.equal(typeof r.scoreDetail.responseMs, 'number');
  }
  assert.equal(byCase.get('fast')!.scoreDetail.timeLimitSec, 1);
  assert.equal(byCase.get('roomy')!.scoreDetail.timeLimitSec, 5);
  assert.equal(byCase.get('retry')!.metrics.retries, 1);
  assert.ok(byCase.get('retry')!.metrics.wallMs > 1000, 'the case took longer than the limit overall');

  // The limit is shown to the model.
  assert.ok(seen.some((p) => p.includes('TIME LIMIT: you must answer within 1 second. A reply that arrives later scores zero.')));
  assert.ok(seen.some((p) => p.includes('within 5 seconds')));

  // A timeout is a scored zero, not an excluded error.
  const row = boards.runLeaderboard(runId)!.rows[0]!;
  assert.equal(row.tests['trick.timed']!.score, 0.8);
  assert.equal(row.tests['trick.timed']!.errors, 0);
  fakeApi.close();
});
