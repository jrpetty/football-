/**
 * Live view (engine side): streamed text is display-only. Watching a run must
 * never change what is sent to models, what is recorded or how it is scored.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, cpSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import type { CaseResult, CompletionRequest, Contestant, RunEvent } from '../src/core/types.ts';

// A fake OpenAI-compatible API that streams its answer in several chunks.
const fakeApi = createServer(async (req, res) => {
  let raw = '';
  for await (const c of req) raw += c;
  const body = JSON.parse(raw || '{}') as { messages?: Array<{ content: string }> };
  const prompt = body.messages?.at(-1)?.content ?? '';
  const i = Number(prompt.match(/What is (\d+) \+/)?.[1] ?? 0);
  const chunk = (delta: unknown, finish: string | null = null) => `data: ${JSON.stringify({ id: 'x', object: 'chat.completion.chunk', created: 1, model: 'fake-1', choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  const answer = `Let me add ${i} and ${i}.\nThat makes ${2 * i}.\nFINAL ANSWER: ${i === 3 ? 7 : 2 * i}`;
  for (const piece of answer.match(/[\s\S]{1,7}/g) ?? []) {
    res.write(chunk({ role: 'assistant', content: piece }));
    await new Promise((r) => setTimeout(r, 3));
  }
  res.write(chunk({}, 'stop'));
  res.write(`data: ${JSON.stringify({ id: 'x', object: 'chat.completion.chunk', created: 1, model: 'fake-1', choices: [], usage: { prompt_tokens: 100, completion_tokens: 20 } })}\n\n`);
  res.end('data: [DONE]\n\n');
});
await new Promise<void>((r) => fakeApi.listen(0, '127.0.0.1', r));
process.env.FAKE_LIVE_KEY = 'k';

const sandbox = mkdtempSync(join(tmpdir(), 'gauntlet-live-'));
process.env.GAUNTLET_TESTS_DIR = join(sandbox, 'tests');
process.env.GAUNTLET_DATA_DIR = join(sandbox, 'data');
process.env.GAUNTLET_NO_BROWSER = '1';
process.env.GAUNTLET_CONFIG_DIR = join(sandbox, 'config');
cpSync(new URL('../config', import.meta.url), join(sandbox, 'config'), { recursive: true });
{
  const file = join(sandbox, 'config', 'models.json');
  const models = JSON.parse(readFileSync(file, 'utf8'));
  models.providers.push({ id: 'fakelive', type: 'openai-compatible', label: 'Fake', baseUrl: `http://127.0.0.1:${(fakeApi.address() as AddressInfo).port}/v1`, apiKeyEnv: 'FAKE_LIVE_KEY', maxConcurrency: 4 });
  models.contestants.push({ id: 'fake-live', label: 'Fake Live', vendor: 'FakeCo', provider: 'fakelive', model: 'fake-1', color: '#654321', enabled: true, pricing: { inputPerM: 2, outputPerM: 10, verifiedAt: '2026-01-01' } });
  writeFileSync(file, JSON.stringify(models));
}
mkdirSync(join(sandbox, 'tests', 'math'), { recursive: true });
writeFileSync(
  join(sandbox, 'tests', 'math', 'arith.json'),
  JSON.stringify({
    kind: 'prompt',
    id: 'math.arith',
    version: '1.0.0',
    name: 'Arithmetic',
    category: 'math',
    description: 'Tiny arithmetic',
    difficulty: 'easy',
    scorer: { type: 'number' },
    cases: Array.from({ length: 5 }, (_, i) => ({ id: `c${i + 1}`, prompt: `What is ${i} + ${i}?`, expected: 2 * i })),
  }),
);

const runner = await import('../src/engine/runner.ts');
const store = await import('../src/engine/store.ts');
const { createRecorder } = await import('../src/engine/recorder.ts');
const { Semaphore } = await import('../src/engine/semaphore.ts');
const { ProviderError } = await import('../src/providers/types.ts');

/** Everything that is recorded, minus wall-clock timings and the run id. */
function normalise(results: CaseResult[]) {
  return results
    .map((r) => ({
      ...r,
      runId: '',
      startedAt: '',
      finishedAt: '',
      metrics: { ...r.metrics, wallMs: 0, ttftMs: 0, outputTokensPerSec: 0 },
      transcript: r.transcript.map((e) => ({ ...e, ttftMs: 0, totalMs: 0 })),
    }))
    .sort((a, b) => a.key.localeCompare(b.key));
}

test('recorded results are identical with and without a live listener', async () => {
  const req = { contestantIds: ['fake-live', 'random-baseline'], testIds: ['math.arith'], repeats: 1, concurrency: 2 };
  const quiet = await runner.startRun(req);
  await runner.waitForRun(quiet);

  const watched = await runner.startRun(req);
  const events: RunEvent[] = [];
  const unsub = runner.subscribe(watched, (e) => events.push(e));
  await runner.waitForRun(watched);
  unsub();

  const a = normalise(store.readResults(quiet));
  const b = normalise(store.readResults(watched));
  assert.equal(a.length, 10);
  assert.deepEqual(b, a, 'watching changes nothing that is recorded');
  assert.equal(a.find((r) => r.key === 'fake-live::math.arith::c4::r0')!.score, 0, 'the scripted wrong answer is still wrong');

  // The live stream carries the model's text exactly as recorded, and the verdict with `passed`.
  const streamed = new Map<string, string>();
  for (const e of events) if (e.type === 'job.delta') streamed.set(e.key, (streamed.get(e.key) ?? '') + e.text);
  for (const r of store.readResults(watched)) assert.equal(streamed.get(r.key), r.transcript.map((t) => t.response).join(''), `stream of ${r.key}`);
  const fin = events.filter((e): e is Extract<RunEvent, { type: 'job.finished' }> => e.type === 'job.finished');
  assert.equal(fin.length, 10);
  assert.ok(fin.every((e) => typeof e.passed === 'boolean'));
  assert.ok(events.filter((e) => e.type === 'job.delta' && e.key.startsWith('fake-live')).length >= 5, 'streamed in pieces');
  fakeApi.close();
});

// ── Recorder: fallback for providers that cannot stream, and retries ──

const contestant: Contestant = { id: 'x', label: 'X', vendor: 'V', provider: 'p', model: 'm', color: '#000000', enabled: true, pricing: { inputPerM: 1, outputPerM: 1 } };
const usage = { inputTokens: 10, outputTokens: 5, reasoningTokens: 0, cachedInputTokens: 0, cacheWriteTokens: 0 };
const reply = (text: string) => ({ text, usage, startedAt: 0, ttftMs: 1, totalMs: 2, stopReason: 'end' as const, rawStopReason: 'end', servedModel: 'm' });
const policy = { maxRetries: 2, temperature: 0, defaultMaxOutputTokens: 100 };

function makeRecorder(adapter: { complete(req: CompletionRequest): Promise<ReturnType<typeof reply>> }, live?: { deltas: string[]; resets: string[] }) {
  return createRecorder({
    target: { contestant, adapter, semaphore: new Semaphore(1) },
    policy,
    signal: new AbortController().signal,
    maxOutputTokens: 100,
    onDelta: live ? (t) => live.deltas.push(t) : undefined,
    onDeltaReset: live ? (label) => live.resets.push(label) : undefined,
  });
}

const stripTimes = <T extends { transcript: Array<{ ttftMs: number | null; totalMs: number }> }>(r: T) => r.transcript.map((e) => ({ ...e, ttftMs: 0, totalMs: 0 }));

test('a provider that cannot stream still shows its whole answer live, once', async () => {
  const silent = { complete: async () => reply('FINAL ANSWER: 42') };
  const live = { deltas: [] as string[], resets: [] as string[] };
  const watched = makeRecorder(silent, live);
  const plain = makeRecorder(silent);
  const a = await watched.handle.complete({ messages: [{ role: 'user', content: 'q' }], label: 'response' });
  const b = await plain.handle.complete({ messages: [{ role: 'user', content: 'q' }], label: 'response' });
  assert.deepEqual(live.deltas, ['FINAL ANSWER: 42']);
  assert.deepEqual(a, b);
  assert.deepEqual(stripTimes(watched), stripTimes(plain));
});

test('a streaming provider is not echoed twice', async () => {
  const streaming = {
    complete: async (req: CompletionRequest) => {
      req.onDelta?.('FINAL ');
      req.onDelta?.('ANSWER: 7');
      return reply('FINAL ANSWER: 7');
    },
  };
  const live = { deltas: [] as string[], resets: [] as string[] };
  await makeRecorder(streaming, live).handle.complete({ messages: [{ role: 'user', content: 'q' }] });
  assert.deepEqual(live.deltas, ['FINAL ', 'ANSWER: 7']);
});

test('text from a failed attempt is reset in the live view; the record keeps only the final answer', async () => {
  const flaky = () => {
    let n = 0;
    return {
      complete: async (req: CompletionRequest) => {
        n++;
        if (n === 1) {
          req.onDelta?.('half an ans');
          throw new ProviderError('overloaded', { status: 529, retryable: true, retryAfterMs: 0 });
        }
        req.onDelta?.('FINAL ANSWER: 9');
        return reply('FINAL ANSWER: 9');
      },
    };
  };
  const live = { deltas: [] as string[], resets: [] as string[] };
  const watched = makeRecorder(flaky(), live);
  const plain = makeRecorder(flaky());
  const a = await watched.handle.complete({ messages: [{ role: 'user', content: 'q' }], label: 'turn 1' });
  const b = await plain.handle.complete({ messages: [{ role: 'user', content: 'q' }], label: 'turn 1' });
  assert.deepEqual(live.resets, ['turn 1']);
  assert.deepEqual(live.deltas, ['half an ans', 'FINAL ANSWER: 9']);
  assert.equal(a.text, 'FINAL ANSWER: 9');
  assert.deepEqual(a, b);
  assert.deepEqual(stripTimes(watched), stripTimes(plain));
  assert.equal(watched.retries, 1);
});
