import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';

/**
 * Vision tests: image plumbing (hashing, validation, provider wire formats), the skip rule for text-only models,
 * cost estimates with image tokens, the Manual Inbox and the Random Baseline on the real vision library.
 */

const REPO_TESTS = new URL('../tests/', import.meta.url);
const PNG_A = readFileSync(new URL('vision/images/hand-h01.png', REPO_TESTS));
const PNG_B = readFileSync(new URL('vision/images/hand-h03.png', REPO_TESTS));
const sha = (b: Buffer) => createHash('sha256').update(b).digest('hex');

// One fake server speaks all three wire formats; every request body is recorded.
type Handler = (req: IncomingMessage, body: Record<string, unknown>, res: ServerResponse) => void;
const requests: Array<{ url: string; body: Record<string, unknown> }> = [];
let handler: Handler = () => {};
const openaiReply: Handler = (_req, body, res) => {
  const messages = body.messages as Array<{ content: unknown }>;
  const last = messages.at(-1)!.content;
  const sawImage = Array.isArray(last) && last.some((p: { type?: string }) => p.type === 'image_url');
  const chunk = (delta: unknown, finish: string | null = null) => `data: ${JSON.stringify({ id: 'x', object: 'chat.completion.chunk', created: 1, model: 'fake-v', choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  res.write(chunk({ role: 'assistant', content: sawImage ? 'I can see it.\nFINAL ANSWER: 149' : 'No image.\nFINAL ANSWER: 0' }));
  res.write(chunk({}, 'stop'));
  res.write(`data: ${JSON.stringify({ id: 'x', object: 'chat.completion.chunk', created: 1, model: 'fake-v', choices: [], usage: { prompt_tokens: 1300, completion_tokens: 40 } })}\n\n`);
  res.end('data: [DONE]\n\n');
};
const server = createServer(async (req, res) => {
  const chunks: Buffer[] = [];
  for await (const c of req) chunks.push(c as Buffer);
  const body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {};
  requests.push({ url: req.url ?? '', body });
  handler(req, body, res);
});
await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
after(() => server.close());
process.env.FAKE_KEY = 'k';

// Isolated library, config and run storage (set before any Gauntlet module loads).
const sandbox = mkdtempSync(join(tmpdir(), 'gauntlet-vision-'));
process.env.GAUNTLET_TESTS_DIR = join(sandbox, 'tests');
process.env.GAUNTLET_DATA_DIR = join(sandbox, 'data');
process.env.GAUNTLET_CONFIG_DIR = join(sandbox, 'config');
process.env.GAUNTLET_SUITES_DIR = join(sandbox, 'suites');
process.env.GAUNTLET_NO_BROWSER = '1';
cpSync(new URL('../config', import.meta.url), join(sandbox, 'config'), { recursive: true });
mkdirSync(join(sandbox, 'suites'), { recursive: true });
{
  const file = join(sandbox, 'config', 'models.json');
  const models = JSON.parse(readFileSync(file, 'utf8'));
  models.providers.push({ id: 'fakeapi', type: 'openai-compatible', label: 'Fake API', baseUrl: `${base}/v1`, apiKeyEnv: 'FAKE_KEY', maxConcurrency: 4 });
  const m = { vendor: 'FakeCo', provider: 'fakeapi', model: 'fake-v', color: '#654321', enabled: true, pricing: { inputPerM: 2, outputPerM: 10, verifiedAt: '2026-01-01' } };
  models.contestants.push({ ...m, id: 'fake-eyes', label: 'Fake Vision', vision: true });
  models.contestants.push({ ...m, id: 'fake-blind', label: 'Fake Text-only', vision: false });
  models.contestants.push({ ...m, id: 'fake-unknown', label: 'Fake Unknown' });
  writeFileSync(file, JSON.stringify(models));
}
const visionDir = join(sandbox, 'tests', 'vision');
mkdirSync(join(visionDir, 'images'), { recursive: true });
mkdirSync(join(sandbox, 'tests', 'math'), { recursive: true });
writeFileSync(join(visionDir, 'images', 'sum.png'), PNG_A);
const visionTest = {
  kind: 'prompt',
  id: 'vision.sum',
  version: '1.0.0',
  name: 'Handwritten sum',
  category: 'vision',
  description: 'Read a handwritten sum',
  difficulty: 'easy',
  scorer: { type: 'number' },
  cases: [
    { id: 'img', prompt: 'Work out the handwritten sum.', images: ['images/sum.png'], expected: 149 },
    { id: 'txt', prompt: 'What is 100 + 49?', expected: 149 },
  ],
};
const textTest = { kind: 'prompt', id: 'math.plain', version: '1.0.0', name: 'Plain', category: 'math', description: 'Text only', difficulty: 'easy', scorer: { type: 'number' }, cases: [{ id: 'c1', prompt: 'What is 2 + 2?', expected: 4 }] };
writeFileSync(join(visionDir, 'sum.json'), JSON.stringify(visionTest));
writeFileSync(join(sandbox, 'tests', 'math', 'plain.json'), JSON.stringify(textTest));

const registry = await import('../src/core/registry.ts');
const vision = await import('../src/core/vision.ts');
const { contentHash } = await import('../src/core/hash.ts');
const runner = await import('../src/engine/runner.ts');
const store = await import('../src/engine/store.ts');
const boards = await import('../src/engine/leaderboards.ts');
const manual = await import('../src/providers/manual.ts');
const { createAdapter } = await import('../src/providers/index.ts');
const { scoreResponse } = await import('../src/scoring/index.ts');
const { createMockAdapter } = await import('../src/providers/mock.ts');

const image = () => vision.loadTestImage(visionDir, 'images/sum.png');

// ─────────────────────────────────────────────────────────────────────────────
// Hashing, validation, rendering
// ─────────────────────────────────────────────────────────────────────────────

test('text-only tests hash exactly as before; vision tests hash their image bytes', () => {
  assert.equal(registry.computeTestHash(textTest as never, join(sandbox, 'tests', 'math')), contentHash(textTest), 'no images: the old hash is unchanged');
  const before = registry.computeTestHash(visionTest as never, visionDir);
  assert.notEqual(before, contentHash(visionTest), 'image digests are part of the hash');
  writeFileSync(join(visionDir, 'images', 'sum.png'), PNG_B);
  const changed = registry.computeTestHash(visionTest as never, visionDir);
  writeFileSync(join(visionDir, 'images', 'sum.png'), PNG_A);
  assert.notEqual(changed, before, 'replacing the image changes the hash');
  assert.equal(registry.computeTestHash(visionTest as never, visionDir), before, 'same bytes, same hash');
  const loaded = registry.getTest('vision.sum')!;
  assert.equal(loaded.hash, before, 'loadTests resolves images next to the JSON file');
});

test('validation catches missing, escaping and non-image files; rendering lists images per turn', () => {
  const bad = (images: unknown) => registry.validateTest({ ...visionTest, cases: [{ id: 'c1', prompt: 'x', expected: 1, images }] } as never, undefined, { baseDir: visionDir });
  assert.deepEqual(bad(['images/sum.png']), []);
  assert.match(bad(['images/nope.png']).join(';'), /not found/);
  assert.match(bad(['../../../etc/passwd.png']).join(';'), /inside the tests folder/);
  assert.match(bad(['sum.json']).join(';'), /\.png\/\.jpg/);
  assert.match(bad([{ file: 'images/sum.png', turn: 3 }]).join(';'), /turn 3/);
  writeFileSync(join(visionDir, 'images', 'fake.png'), 'not really a png');
  assert.match(bad(['images/fake.png']).join(';'), /not a PNG or JPEG/);
  const r = registry.renderCase(visionTest as never, visionTest.cases[0] as never, visionDir);
  assert.deepEqual(r.images, [{ file: 'images/sum.png', turn: 0, path: 'vision/images/sum.png' }]);
  assert.equal(registry.summarize(registry.getTest('vision.sum')!).imageCases, 1);
});

test('image headers: PNG and JPEG sizes are read from the file itself', () => {
  assert.deepEqual(vision.imageInfo(PNG_A), { mediaType: 'image/png', width: 1200, height: 560 });
  // Minimal JPEG: SOI, an APP0 segment, then SOF0 with height 300 and width 500.
  const jpeg = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x04, 0x00, 0x00, 0xff, 0xc0, 0x00, 0x11, 0x08, 0x01, 0x2c, 0x01, 0xf4, 0x03, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
  assert.deepEqual(vision.imageInfo(jpeg), { mediaType: 'image/jpeg', width: 500, height: 300 });
  assert.equal(vision.imageInfo(Buffer.from('GIF89a')), null);
});

test('image token estimates follow each vendor formula', () => {
  assert.equal(vision.estimateImageTokens(1200, 800, 'anthropic'), 1280); // 1200×800 / 750
  assert.equal(vision.estimateImageTokens(4000, 3000, 'anthropic'), Math.ceil((1238 * 929) / 750)); // capped at ~1.15 MP
  assert.equal(vision.estimateImageTokens(1200, 800, 'openai-compatible'), 85 + 170 * 6); // 1152×768 → 3×2 tiles
  assert.equal(vision.estimateImageTokens(1200, 800, 'gemini', 'gemini-3.1-pro-preview'), 1120);
  assert.equal(vision.estimateImageTokens(1200, 800, 'gemini', 'gemini-2.5-pro'), 4 * 258);
  assert.equal(vision.estimateImageTokens(300, 300, 'gemini', 'gemini-2.5-flash'), 258);
  assert.equal(vision.estimateImageTokens(1200, 800, 'mock'), 0);
});

test('vision capability: explicit flag wins; baseline and manual always see images; unknown API models do not', () => {
  assert.equal(vision.supportsVision({ vision: true }, 'anthropic'), true);
  assert.equal(vision.supportsVision({ vision: false }, 'anthropic'), false);
  assert.equal(vision.supportsVision({}, 'openai-compatible'), false);
  assert.equal(vision.supportsVision({}, 'mock'), true);
  assert.equal(vision.supportsVision({}, 'manual'), true);
});

// ─────────────────────────────────────────────────────────────────────────────
// Provider wire formats
// ─────────────────────────────────────────────────────────────────────────────

function contestant(provider: string) {
  return { id: 'm', label: 'M', vendor: 'V', provider, model: 'model-x', color: '#000000', enabled: true, pricing: { inputPerM: 1, outputPerM: 2 } };
}

test('anthropic: base64 image blocks before the text; text-only messages stay plain strings', async () => {
  const ev = (type: string, data: unknown) => `event: ${type}\ndata: ${JSON.stringify(data)}\n\n`;
  handler = (_req, _body, res) => {
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    res.end(
      [
        ev('message_start', { type: 'message_start', message: { id: 'm', type: 'message', role: 'assistant', model: 'x', content: [], stop_reason: null, stop_sequence: null, usage: { input_tokens: 1300, output_tokens: 1 } } }),
        ev('content_block_start', { type: 'content_block_start', index: 0, content_block: { type: 'text', text: '' } }),
        ev('content_block_delta', { type: 'content_block_delta', index: 0, delta: { type: 'text_delta', text: 'ok' } }),
        ev('content_block_stop', { type: 'content_block_stop', index: 0 }),
        ev('message_delta', { type: 'message_delta', delta: { stop_reason: 'end_turn', stop_sequence: null }, usage: { output_tokens: 2 } }),
        ev('message_stop', { type: 'message_stop' }),
      ].join(''),
    );
  };
  const adapter = createAdapter(contestant('a'), { id: 'a', type: 'anthropic', label: 'A', baseUrl: base, apiKeyEnv: 'FAKE_KEY' });
  const r = await adapter.complete({ messages: [{ role: 'user', content: 'Read this', images: [image()] }, { role: 'assistant', content: 'ok' }, { role: 'user', content: 'again' }], maxOutputTokens: 100, temperature: 0 });
  assert.equal(r.text, 'ok');
  const msgs = requests.at(-1)!.body.messages as Array<{ content: unknown }>;
  const first = msgs[0]!.content as Array<{ type: string; source?: { type: string; media_type: string; data: string }; text?: string }>;
  assert.equal(first[0]!.type, 'image');
  assert.equal(first[0]!.source!.type, 'base64');
  assert.equal(first[0]!.source!.media_type, 'image/png');
  assert.equal(sha(Buffer.from(first[0]!.source!.data, 'base64')), sha(PNG_A), 'exact image bytes are sent');
  assert.deepEqual(first[1], { type: 'text', text: 'Read this' });
  assert.equal(msgs[1]!.content, 'ok');
  assert.equal(msgs[2]!.content, 'again', 'messages without images keep the plain-string wire format');
});

test('openai-compatible: image_url data URLs (detail high) before the text', async () => {
  handler = openaiReply;
  const adapter = createAdapter(contestant('o'), { id: 'o', type: 'openai-compatible', label: 'O', baseUrl: `${base}/v1`, apiKeyEnv: 'FAKE_KEY' });
  const r = await adapter.complete({ system: 'sys', messages: [{ role: 'user', content: 'Read this', images: [image()] }], maxOutputTokens: 100, temperature: 0 });
  assert.match(r.text, /FINAL ANSWER: 149/);
  const msgs = requests.at(-1)!.body.messages as Array<{ role: string; content: unknown }>;
  assert.deepEqual(msgs[0], { role: 'system', content: 'sys' });
  const parts = msgs[1]!.content as Array<{ type: string; image_url?: { url: string; detail: string }; text?: string }>;
  assert.equal(parts[0]!.type, 'image_url');
  assert.equal(parts[0]!.image_url!.detail, 'high');
  assert.ok(parts[0]!.image_url!.url.startsWith('data:image/png;base64,'));
  assert.equal(sha(Buffer.from(parts[0]!.image_url!.url.split(',')[1]!, 'base64')), sha(PNG_A));
  assert.deepEqual(parts[1], { type: 'text', text: 'Read this' });
});

test('gemini: inline_data parts before the text part', async () => {
  handler = (_req, _body, res) => {
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    res.end(`data: ${JSON.stringify({ candidates: [{ content: { parts: [{ text: 'ok' }], role: 'model' }, finishReason: 'STOP' }], usageMetadata: { promptTokenCount: 1200, candidatesTokenCount: 1 } })}\r\n\r\n`);
  };
  const adapter = createAdapter(contestant('g'), { id: 'g', type: 'gemini', label: 'G', baseUrl: base, apiKeyEnv: 'FAKE_KEY' });
  const r = await adapter.complete({ messages: [{ role: 'user', content: 'Read this', images: [image()] }, { role: 'assistant', content: 'ok' }], maxOutputTokens: 100, temperature: 0 });
  assert.equal(r.text, 'ok');
  const contents = requests.at(-1)!.body.contents as Array<{ role: string; parts: Array<Record<string, unknown>> }>;
  const inline = contents[0]!.parts[0]!.inline_data as { mime_type: string; data: string };
  assert.equal(inline.mime_type, 'image/png');
  assert.equal(sha(Buffer.from(inline.data, 'base64')), sha(PNG_A));
  assert.deepEqual(contents[0]!.parts[1], { text: 'Read this' });
  assert.deepEqual(contents[1], { role: 'model', parts: [{ text: 'ok' }] });
});

// ─────────────────────────────────────────────────────────────────────────────
// Runs: skip rule, force, transcripts, leaderboard, estimates, manual inbox
// ─────────────────────────────────────────────────────────────────────────────

test('text-only models are skipped on image cases (not scored as 0) and excluded from means; vision models get the image', async () => {
  handler = openaiReply;
  const runId = await runner.startRun({ contestantIds: ['fake-eyes', 'fake-blind', 'fake-unknown', 'random-baseline'], testIds: ['vision.sum'], repeats: 1 });
  await runner.waitForRun(runId);
  const results = store.readResults(runId);
  const get = (c: string, k: string) => results.find((r) => r.contestantId === c && r.caseId === k)!;
  assert.equal(get('fake-eyes', 'img').score, 1, 'the vision model saw the image');
  const entry = get('fake-eyes', 'img').transcript[0]!;
  assert.equal(entry.messages[0]!.images![0]!.name, 'sum.png');
  assert.equal(entry.messages[0]!.images![0]!.data, undefined, 'image bytes are not stored in transcripts');
  assert.equal(entry.messages[0]!.images![0]!.path, 'vision/images/sum.png');
  for (const c of ['fake-blind', 'fake-unknown']) {
    const r = get(c, 'img');
    assert.equal(r.status, 'skipped');
    assert.equal(r.score, null);
    assert.equal(r.metrics.apiCalls, 0);
    assert.match(r.summary, /no image input/);
    assert.equal(get(c, 'txt').score, 0, 'text cases of the same test still run');
  }
  assert.equal(get('random-baseline', 'img').status, 'ok', 'the Random Baseline still answers image cases');
  const board = boards.runLeaderboard(runId)!;
  const blind = board.rows.find((r) => r.contestantId === 'fake-blind')!;
  assert.equal(blind.tests['vision.sum']!.skipped, 1);
  assert.equal(blind.tests['vision.sum']!.n, 1, 'only the text case is in the mean');
  assert.equal(blind.tests['vision.sum']!.score, 0);
  assert.equal(blind.totals.skipped, 1);
  assert.equal(board.rows.find((r) => r.contestantId === 'fake-eyes')!.tests['vision.sum']!.skipped, undefined);
  // Resume treats skipped results as done.
  runner.resumeRun(runId);
  await runner.waitForRun(runId);
  assert.equal(store.readResults(runId).length, results.length);
});

test('forceVision sends image cases to models not marked as vision-capable', async () => {
  handler = openaiReply;
  const est = await runner.estimateRun({ contestantIds: ['fake-blind'], testIds: ['vision.sum'], repeats: 1 });
  assert.match(est.warnings.join('\n'), /no image input/);
  const runId = await runner.startRun({ contestantIds: ['fake-blind'], testIds: ['vision.sum'], repeats: 1, forceVision: true });
  await runner.waitForRun(runId);
  assert.equal(store.readManifest(runId)!.settings.forceVision, true);
  const img = store.readResults(runId).find((r) => r.caseId === 'img')!;
  assert.equal(img.status, 'ok');
  assert.equal(img.score, 1);
});

test('cost estimates add image tokens for vision models and nothing for skipped cases', async () => {
  const est = await runner.estimateRun({ contestantIds: ['fake-eyes', 'fake-blind'], testIds: ['vision.sum'], repeats: 1 });
  const eyes = est.perContestant.find((p) => p.contestantId === 'fake-eyes')!;
  const blind = est.perContestant.find((p) => p.contestantId === 'fake-blind')!;
  assert.equal(eyes.jobs, 2);
  assert.equal(blind.jobs, 2, 'skipped cases still count as jobs');
  assert.ok(eyes.estCostUsd > blind.estCostUsd, `vision model pays for image tokens (${eyes.estCostUsd} vs ${blind.estCostUsd})`);
  // Definition-based estimate for the vision model: (text + half the image tokens per case) × price.
  const text = registry.testEstimate(visionTest as never);
  const imageTokens = vision.estimateImageTokens(1200, 560, 'openai-compatible') / 2;
  const expected = (2 * ((text.inputTokens + imageTokens) * 2 + text.outputTokens * 10)) / 1e6;
  assert.ok(Math.abs(eyes.estCostUsd - expected) < 1e-3 || est.perTest[0]!.basis !== 'definition', `${eyes.estCostUsd} ≈ ${expected}`);
});

test('manual contestants: the inbox request carries the image; listings leave the bytes out', async () => {
  const runId = await runner.startRun({ contestantIds: ['manual-chat'], testIds: ['vision.sum'], repeats: 1 });
  const deadline = Date.now() + 10_000;
  let answered = 0;
  while (answered < 2 && Date.now() < deadline) {
    for (const req of manual.listManualRequests(runId)) {
      const imgs = req.messages.at(-1)!.images ?? [];
      if (req.caseId === 'img') {
        assert.equal(imgs.length, 1);
        assert.equal(imgs[0]!.data, undefined, 'listing has no base64 payload');
        assert.equal(imgs[0]!.width, 1200);
        const full = manual.manualImage(req.id, req.messages.length - 1, 0)!;
        assert.equal(sha(Buffer.from(full.data!, 'base64')), sha(PNG_A));
      } else assert.equal(imgs.length, 0);
      manual.submitManual(req.id, { text: 'FINAL ANSWER: 149' });
      answered++;
    }
    await new Promise((r) => setTimeout(r, 20));
  }
  await runner.waitForRun(runId);
  const results = store.readResults(runId);
  assert.equal(results.length, 2);
  assert.ok(results.every((r) => r.score === 1));
  const img = results.find((r) => r.caseId === 'img')!;
  assert.ok(img.metrics.inputTokens > 500, 'estimated manual input tokens include the image');
});

// ─────────────────────────────────────────────────────────────────────────────
// The real vision library
// ─────────────────────────────────────────────────────────────────────────────

test('Random Baseline scores at most 10% on every built-in vision test, and never errors on images', async () => {
  const baseline = createMockAdapter({ provider: { id: 'baseline', type: 'mock', label: 'B', apiKeyEnv: null }, contestant: { ...contestant('baseline'), id: 'random-baseline' }, apiKey: undefined });
  for (const f of ['read-the-chart', 'spot-the-difference', 'handwritten-maths', 'count-and-locate']) {
    const def = JSON.parse(readFileSync(new URL(`vision/${f}.json`, REPO_TESTS), 'utf8'));
    let sum = 0;
    for (const c of def.cases) {
      const rendered = registry.renderCase(def, c);
      const reply = await baseline.complete({ messages: [{ role: 'user', content: rendered.turns[0]!, images: [{ name: 'x.png', mediaType: 'image/png', data: 'AA==' }] }], maxOutputTokens: 100 });
      const out = await scoreResponse({ scorer: c.scorer ?? def.scorer, expected: c.expected, response: reply.text, stopReason: 'end', taskText: '', judges: { ids: [], ask: async () => [] }, saveArtifact: (name) => ({ name, kind: 'text', file: name, bytes: 0 }), signal: new AbortController().signal });
      sum += out.score ?? 0;
    }
    assert.ok(sum / def.cases.length <= 0.1, `${def.id}: baseline scored ${((sum / def.cases.length) * 100).toFixed(1)}%`);
  }
});

test('every built-in vision case attaches exactly one committed image and states its answer format', () => {
  for (const f of ['read-the-chart', 'spot-the-difference', 'handwritten-maths', 'count-and-locate']) {
    const def = JSON.parse(readFileSync(new URL(`vision/${f}.json`, REPO_TESTS), 'utf8'));
    assert.equal(def.category, 'vision');
    for (const c of def.cases) {
      assert.equal(c.images.length, 1, `${def.id}/${c.id}`);
      const buf = readFileSync(new URL(`vision/${c.images[0]}`, REPO_TESTS));
      assert.equal(vision.imageInfo(buf)?.mediaType, 'image/png');
      assert.match(c.prompt, /JSON object|single integer|single number|separated by a space/, `${def.id}/${c.id} says what form the answer takes`);
      assert.match(c.notes, /\[(standard|hard)\]/);
    }
  }
});
