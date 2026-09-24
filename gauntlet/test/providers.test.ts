import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';
import { createAdapter } from '../src/providers/index.ts';
import { callWithRetry } from '../src/engine/recorder.ts';
import { Semaphore } from '../src/engine/semaphore.ts';
import type { Contestant, ProviderConfig } from '../src/core/types.ts';

/**
 * Each adapter is exercised against a local server that speaks the
 * provider's real streaming wire format, so parsing, usage normalisation,
 * stop reasons, TTFT and retry behaviour are verified without API keys.
 */

type Handler = (req: IncomingMessage, body: Record<string, unknown>, res: ServerResponse) => void;
let handler: Handler = () => {};
const requests: Array<{ url: string; headers: IncomingMessage['headers']; body: Record<string, unknown> }> = [];

const server = createServer(async (req, res) => {
  const chunks: Buffer[] = [];
  for await (const c of req) chunks.push(c as Buffer);
  const body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {};
  requests.push({ url: req.url ?? '', headers: req.headers, body });
  handler(req, body, res);
});
await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
after(() => server.close());

process.env.FAKE_KEY = 'test-key';

function contestant(provider: string, options: Contestant['options'] = {}): Contestant {
  return { id: 'm', label: 'M', vendor: 'V', provider, model: 'model-x', color: '#000000', enabled: true, pricing: { inputPerM: 1, outputPerM: 2 }, options };
}

async function sse(res: ServerResponse, events: string[], delayMs = 5) {
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  for (const e of events) {
    res.write(e);
    await new Promise((r) => setTimeout(r, delayMs));
  }
  res.end();
}

test('anthropic adapter: streams text, maps usage incl. cache + thinking tokens, sends effort', async () => {
  const provider: ProviderConfig = { id: 'a', type: 'anthropic', label: 'Anthropic', baseUrl: base, apiKeyEnv: 'FAKE_KEY' };
  const ev = (type: string, data: unknown) => `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`;
  handler = (_req, _body, res) =>
    void sse(res, [
      ev('message_start', { type: 'message_start', message: { id: 'msg_1', type: 'message', role: 'assistant', model: 'model-x-served', content: [], stop_reason: null, stop_sequence: null, usage: { input_tokens: 25, output_tokens: 1, cache_read_input_tokens: 100, cache_creation_input_tokens: 7 } } }),
      ev('content_block_start', { type: 'content_block_start', index: 0, content_block: { type: 'thinking', thinking: '', signature: '' } }),
      ev('content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'thinking_delta', thinking: '' } }),
      ev('content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'signature_delta', signature: 'sig' } }),
      ev('content_block_stop', { type: 'content_block_stop', index: 0 }),
      ev('content_block_start', { type: 'content_block_start', index: 1, content_block: { type: 'text', text: '' } }),
      ev('content_block_delta', { type: 'content_block_delta', index: 1, delta: { type: 'text_delta', text: 'Hello ' } }),
      ev('content_block_delta', { type: 'content_block_delta', index: 1, delta: { type: 'text_delta', text: 'world' } }),
      ev('content_block_stop', { type: 'content_block_stop', index: 1 }),
      ev('message_delta', { type: 'message_delta', delta: { stop_reason: 'end_turn', stop_sequence: null }, usage: { output_tokens: 40, output_tokens_details: { thinking_tokens: 30 } } }),
      ev('message_stop', { type: 'message_stop' }),
    ]);
  const deltas: string[] = [];
  const adapter = createAdapter(contestant('a', { effort: 'high' }), provider);
  const r = await adapter.complete({ system: 'sys', messages: [{ role: 'user', content: 'hi' }], maxOutputTokens: 1000, temperature: 0, onDelta: (d) => deltas.push(d) });
  assert.equal(r.text, 'Hello world');
  assert.deepEqual(deltas, ['Hello ', 'world']);
  assert.equal(r.stopReason, 'end');
  assert.equal(r.servedModel, 'model-x-served');
  assert.deepEqual(r.usage, { inputTokens: 25, outputTokens: 40, reasoningTokens: 30, cachedInputTokens: 100, cacheWriteTokens: 7 });
  assert.ok(r.ttftMs !== null && r.ttftMs <= r.totalMs);
  const sent = requests.at(-1)!.body;
  assert.deepEqual(sent.thinking, { type: 'adaptive' });
  assert.deepEqual(sent.output_config, { effort: 'high' });
  assert.equal(sent.temperature, undefined, 'temperature is not sent to models that do not support it');
  assert.equal(sent.system, 'sys');
  assert.equal(sent.fallbacks, undefined, 'no refusal fallbacks in benchmark mode');
});

test('anthropic adapter: 429 is retried by the engine and counted', async () => {
  const provider: ProviderConfig = { id: 'a', type: 'anthropic', label: 'Anthropic', baseUrl: base, apiKeyEnv: 'FAKE_KEY' };
  let calls = 0;
  handler = (_req, _body, res) => {
    calls++;
    if (calls === 1) {
      res.writeHead(429, { 'content-type': 'application/json', 'retry-after': '0' });
      res.end(JSON.stringify({ type: 'error', error: { type: 'rate_limit_error', message: 'slow down' } }));
      return;
    }
    const ev = (type: string, data: unknown) => `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`;
    void sse(res, [
      ev('message_start', { type: 'message_start', message: { id: 'm', type: 'message', role: 'assistant', model: 'x', content: [], stop_reason: null, stop_sequence: null, usage: { input_tokens: 1, output_tokens: 1 } } }),
      ev('content_block_start', { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } }),
      ev('content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'ok' } }),
      ev('content_block_stop', { type: 'content_block_stop', index: 0 }),
      ev('message_delta', { type: 'message_delta', delta: { stop_reason: 'max_tokens', stop_sequence: null }, usage: { output_tokens: 2 } }),
      ev('message_stop', { type: 'message_stop' }),
    ]);
  };
  const target = { contestant: contestant('a'), adapter: createAdapter(contestant('a'), provider), semaphore: new Semaphore(1) };
  const r = await callWithRetry(target, { messages: [{ role: 'user', content: 'x' }], maxOutputTokens: 10, temperature: 0 }, { maxRetries: 2, temperature: 0, defaultMaxOutputTokens: 10 }, new AbortController().signal);
  assert.equal(r.text, 'ok');
  assert.equal(r.retries, 1);
  assert.equal(r.stopReason, 'max_tokens');
});

test('anthropic adapter: 400 is not retried', async () => {
  const provider: ProviderConfig = { id: 'a', type: 'anthropic', label: 'Anthropic', baseUrl: base, apiKeyEnv: 'FAKE_KEY' };
  let calls = 0;
  handler = (_req, _body, res) => {
    calls++;
    res.writeHead(400, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ type: 'error', error: { type: 'invalid_request_error', message: 'bad' } }));
  };
  const target = { contestant: contestant('a'), adapter: createAdapter(contestant('a'), provider), semaphore: new Semaphore(1) };
  await assert.rejects(callWithRetry(target, { messages: [{ role: 'user', content: 'x' }], maxOutputTokens: 10, temperature: 0 }, { maxRetries: 3, temperature: 0, defaultMaxOutputTokens: 10 }, new AbortController().signal), /400/);
  assert.equal(calls, 1);
});

test('openai-compatible adapter: streams, normalises cached/reasoning usage, uses max_completion_tokens for OpenAI', async () => {
  const provider: ProviderConfig = { id: 'o', type: 'openai-compatible', label: 'OpenAI', baseUrl: `${base}/v1`, apiKeyEnv: 'FAKE_KEY', maxTokensParam: 'max_completion_tokens' };
  const chunk = (delta: unknown, finish: string | null = null) => `data: ${JSON.stringify({ id: 'c', object: 'chat.completion.chunk', created: 1, model: 'gpt-served', choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;
  handler = (_req, _body, res) =>
    void sse(res, [
      chunk({ role: 'assistant', content: '' }),
      chunk({ content: 'FINAL ' }),
      chunk({ content: 'ANSWER: 4' }),
      chunk({}, 'stop'),
      `data: ${JSON.stringify({ id: 'c', object: 'chat.completion.chunk', created: 1, model: 'gpt-served', choices: [], usage: { prompt_tokens: 120, completion_tokens: 50, total_tokens: 170, prompt_tokens_details: { cached_tokens: 100 }, completion_tokens_details: { reasoning_tokens: 30 } } })}\n\n`,
      'data: [DONE]\n\n',
    ]);
  const adapter = createAdapter(contestant('o', { effort: 'high', supportsTemperature: false }), provider);
  const r = await adapter.complete({ system: 'sys', messages: [{ role: 'user', content: 'hi' }], maxOutputTokens: 500, temperature: 0 });
  assert.equal(r.text, 'FINAL ANSWER: 4');
  assert.equal(r.stopReason, 'end');
  assert.equal(r.servedModel, 'gpt-served');
  assert.deepEqual(r.usage, { inputTokens: 20, outputTokens: 50, reasoningTokens: 30, cachedInputTokens: 100, cacheWriteTokens: 0 });
  const sent = requests.at(-1)!.body;
  assert.equal(sent.max_completion_tokens, 500);
  assert.equal(sent.reasoning_effort, 'high');
  assert.equal(sent.temperature, undefined);
  assert.deepEqual((sent.messages as unknown[])[0], { role: 'system', content: 'sys' });
  assert.equal(requests.at(-1)!.headers.authorization, 'Bearer test-key');
});

test('openai-compatible adapter: DeepSeek-style cache fields, reasoning_content TTFT, max_tokens elsewhere, length stop', async () => {
  const provider: ProviderConfig = { id: 'd', type: 'openai-compatible', label: 'DeepSeek', baseUrl: `${base}/v1`, apiKeyEnv: 'FAKE_KEY' };
  const chunk = (delta: unknown, finish: string | null = null) => `data: ${JSON.stringify({ id: 'c', object: 'chat.completion.chunk', created: 1, model: 'ds', choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;
  handler = (_req, _body, res) =>
    void sse(res, [
      chunk({ reasoning_content: 'thinking…' }),
      chunk({ content: 'partial' }),
      chunk({}, 'length'),
      `data: ${JSON.stringify({ id: 'c', object: 'chat.completion.chunk', created: 1, model: 'ds', choices: [], usage: { prompt_tokens: 50, completion_tokens: 10, prompt_cache_hit_tokens: 40, prompt_cache_miss_tokens: 10 } })}\n\n`,
      'data: [DONE]\n\n',
    ], 20);
  const adapter = createAdapter(contestant('d', { supportsTemperature: true }), provider);
  const r = await adapter.complete({ messages: [{ role: 'user', content: 'hi' }], maxOutputTokens: 64, temperature: 0 });
  assert.equal(r.stopReason, 'max_tokens');
  assert.equal(r.usage.cachedInputTokens, 40);
  assert.equal(r.usage.inputTokens, 10);
  assert.ok(r.ttftMs !== null && r.ttftMs < r.totalMs, 'reasoning deltas count toward TTFT');
  const sent = requests.at(-1)!.body;
  assert.equal(sent.max_tokens, 64);
  assert.equal(sent.temperature, 0);
});

test('gemini adapter: SSE parsing, thoughts excluded from text but billed as output', async () => {
  const provider: ProviderConfig = { id: 'g', type: 'gemini', label: 'Gemini', baseUrl: base, apiKeyEnv: 'FAKE_KEY' };
  const data = (x: unknown) => `data: ${JSON.stringify(x)}\r\n\r\n`;
  handler = (_req, _body, res) =>
    void sse(res, [
      data({ candidates: [{ content: { parts: [{ text: 'secret thoughts', thought: true }], role: 'model' } }], modelVersion: 'gemini-served' }),
      data({ candidates: [{ content: { parts: [{ text: 'Hello ' }], role: 'model' } }] }),
      data({ candidates: [{ content: { parts: [{ text: 'there' }], role: 'model' }, finishReason: 'STOP' }], usageMetadata: { promptTokenCount: 80, candidatesTokenCount: 12, thoughtsTokenCount: 200, cachedContentTokenCount: 30 }, responseId: 'resp-1' }),
    ]);
  const adapter = createAdapter(contestant('g', { effort: 'high', supportsTemperature: true }), provider);
  const r = await adapter.complete({ system: 'sys', messages: [{ role: 'user', content: 'hi' }, { role: 'assistant', content: 'yo' }, { role: 'user', content: 'again' }], maxOutputTokens: 900, temperature: 0 });
  assert.equal(r.text, 'Hello there');
  assert.equal(r.stopReason, 'end');
  assert.equal(r.servedModel, 'gemini-served');
  assert.equal(r.requestId, 'resp-1');
  assert.deepEqual(r.usage, { inputTokens: 50, outputTokens: 212, reasoningTokens: 200, cachedInputTokens: 30, cacheWriteTokens: 0 });
  const last = requests.at(-1)!;
  assert.match(last.url, /\/models\/model-x:streamGenerateContent\?alt=sse$/);
  assert.equal(last.headers['x-goog-api-key'], 'test-key');
  const sent = last.body as { contents: Array<{ role: string }>; systemInstruction: unknown; generationConfig: Record<string, unknown> };
  assert.deepEqual(sent.contents.map((c) => c.role), ['user', 'model', 'user']);
  assert.deepEqual(sent.systemInstruction, { parts: [{ text: 'sys' }] });
  assert.deepEqual(sent.generationConfig, { maxOutputTokens: 900, temperature: 0, thinkingConfig: { thinkingLevel: 'high' } });
});

test('gemini adapter: blocked prompts are refusals; 503 is retryable', async () => {
  const provider: ProviderConfig = { id: 'g', type: 'gemini', label: 'Gemini', baseUrl: base, apiKeyEnv: 'FAKE_KEY' };
  handler = (_req, _body, res) => void sse(res, [`data: ${JSON.stringify({ promptFeedback: { blockReason: 'SAFETY' } })}\n\n`]);
  const adapter = createAdapter(contestant('g'), provider);
  const r = await adapter.complete({ messages: [{ role: 'user', content: 'x' }], maxOutputTokens: 10 });
  assert.equal(r.stopReason, 'refusal');

  let calls = 0;
  handler = (_req, _body, res) => {
    calls++;
    if (calls === 1) {
      res.writeHead(503, { 'retry-after': '0' });
      res.end('overloaded');
      return;
    }
    void sse(res, [`data: ${JSON.stringify({ candidates: [{ content: { parts: [{ text: 'fine' }] }, finishReason: 'STOP' }], usageMetadata: { promptTokenCount: 1, candidatesTokenCount: 1 } })}\n\n`]);
  };
  const target = { contestant: contestant('g'), adapter, semaphore: new Semaphore(1) };
  const ok = await callWithRetry(target, { messages: [{ role: 'user', content: 'x' }], maxOutputTokens: 10, temperature: 0 }, { maxRetries: 2, temperature: 0, defaultMaxOutputTokens: 10 }, new AbortController().signal);
  assert.equal(ok.text, 'fine');
  assert.equal(ok.retries, 1);
});

test('mock baseline: deterministic, picks backticked actions and decision lines', async () => {
  const provider: ProviderConfig = { id: 'baseline', type: 'mock', label: 'Baseline', apiKeyEnv: null };
  const adapter = createAdapter(contestant('baseline'), provider);
  const prompt = 'You are on a beach.\nAvailable actions: `MOVE N`, `MOVE S`, `GATHER`';
  const a = await adapter.complete({ messages: [{ role: 'user', content: prompt }], maxOutputTokens: 100 });
  const b = await adapter.complete({ messages: [{ role: 'user', content: prompt }], maxOutputTokens: 100 });
  assert.equal(a.text, b.text);
  assert.match(a.text, /ACTION: (MOVE N|MOVE S|GATHER)$/);
  const d = await adapter.complete({ messages: [{ role: 'user', content: 'Decide: `PRICE: 10` `PRICE: 20` `PRODUCE: 100`' }], maxOutputTokens: 100 });
  assert.match(d.text, /^PRICE: (10|20)\nPRODUCE: 100$/);
});
