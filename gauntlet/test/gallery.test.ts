import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { createServer as createNetServer, type AddressInfo } from 'node:net';

/**
 * The Gallery Masterpiece: image-generation wire formats (OpenAI Images, Gemini) against fake servers, judge
 * parsing (including malformed replies), scoring, the skip rules, cost estimates, the owner's artistry override,
 * blind votes and the Manual Inbox picture upload.
 */

// ─────────────────────────────── Fake provider server ───────────────────────────────

type Handler = (req: IncomingMessage, body: Record<string, unknown>, res: ServerResponse) => void;
const requests: Array<{ url: string; body: Record<string, unknown> }> = [];
let imageMode: 'ok' | 'refuse' | 'badsize' | 'empty' | 'url' = 'ok';
let geminiMode: 'ok' | 'safety' | 'text' | 'badaspect' | 'blocked' = 'ok';
let judgeMode: 'good' | 'b-garbage' | 'split' = 'good';
let coderReply = '';
const judgeSawImage: string[] = [];

const { encodePng } = await import('../src/core/png.ts');
function testPng(w: number, h: number, seed = 1): Buffer {
  const rgb = new Uint8Array(w * h * 3);
  for (let i = 0; i < rgb.length; i++) rgb[i] = (i * 31 + seed * 17) % 256;
  return encodePng(w, h, rgb);
}
const PAINTING = testPng(96, 64);
const JPEG_HEADER = Buffer.from('/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCABAAGABAREA/8QAFAABAAAAAAAAAAAAAAAAAAAACf/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AKp//2Q==', 'base64');

const ITEMS = ['E1', 'E2', 'E3', 'E4', 'E5', 'E6', 'N1', 'N2', 'N3'];
function verdictJson(model: string): string {
  const b = model === 'judge-b';
  if (judgeMode === 'b-garbage' && b) return 'I think it is lovely! 9/10';
  const checklist = ITEMS.map((id) => ({ id, verdict: id === 'E6' ? (b ? (judgeMode === 'split' ? 'yes' : 'no') : judgeMode === 'split' ? 'no' : 'partly') : 'yes', reason: `${id} looks right` }));
  const score = b ? 7 : judgeMode === 'split' ? 10 : 8;
  const artistry = Object.fromEntries(['composition', 'light', 'colour', 'craft', 'style', 'gallery'].map((c) => [c, { score: b && judgeMode === 'split' ? 5 : score, reason: `${c} ok` }]));
  const obj = { checklist, artistry, summary: 'A calm, luminous interior.' };
  return b ? '```json\n' + JSON.stringify(obj) + '\n```' : JSON.stringify(obj);
}

function sse(res: ServerResponse, content: string) {
  const chunk = (delta: unknown, finish: string | null = null) => `data: ${JSON.stringify({ id: 'x', object: 'chat.completion.chunk', created: 1, model: 'fake', choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  res.write(chunk({ role: 'assistant', content }));
  res.write(chunk({}, 'stop'));
  res.write(`data: ${JSON.stringify({ id: 'x', object: 'chat.completion.chunk', created: 1, model: 'fake', choices: [], usage: { prompt_tokens: 2000, completion_tokens: 300 } })}\n\n`);
  res.end('data: [DONE]\n\n');
}

const handler: Handler = (req, body, res) => {
  const url = req.url ?? '';
  if (url.endsWith('/images/generations')) {
    if (imageMode === 'refuse') {
      res.writeHead(400, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ error: { message: 'Your request was rejected by the safety system.', type: 'image_generation_user_error', code: 'moderation_blocked' } }));
    }
    if (imageMode === 'badsize') {
      res.writeHead(400, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ error: { message: "Invalid value: '1536x1024'. Supported values are: '1024x1024'.", param: 'size', code: 'invalid_value' } }));
    }
    res.writeHead(200, { 'content-type': 'application/json' });
    if (imageMode === 'empty') return res.end(JSON.stringify({ created: 1, data: [] }));
    if (imageMode === 'url') return res.end(JSON.stringify({ created: 1, data: [{ url: `${base}/files/p.jpg` }] }));
    return res.end(JSON.stringify({ created: 1, data: [{ b64_json: PAINTING.toString('base64') }], usage: { input_tokens: 500, output_tokens: 6000 } }));
  }
  if (url.startsWith('/files/')) {
    res.writeHead(200, { 'content-type': 'image/jpeg' });
    return res.end(JPEG_HEADER);
  }
  if (url.includes(':generateContent')) {
    if (geminiMode === 'badaspect') {
      res.writeHead(400, { 'content-type': 'application/json' });
      return res.end(JSON.stringify({ error: { code: 400, message: 'Unsupported aspect ratio: 3:2', status: 'INVALID_ARGUMENT' } }));
    }
    res.writeHead(200, { 'content-type': 'application/json' });
    const usageMetadata = { promptTokenCount: 480, candidatesTokenCount: 1290 };
    if (geminiMode === 'blocked') return res.end(JSON.stringify({ promptFeedback: { blockReason: 'PROHIBITED_CONTENT' }, usageMetadata }));
    if (geminiMode === 'safety') return res.end(JSON.stringify({ candidates: [{ finishReason: 'IMAGE_SAFETY', content: { parts: [] } }], usageMetadata }));
    if (geminiMode === 'text') return res.end(JSON.stringify({ candidates: [{ finishReason: 'STOP', content: { parts: [{ text: 'Here is a description of the painting instead.' }] } }], usageMetadata }));
    return res.end(JSON.stringify({ candidates: [{ finishReason: 'STOP', content: { parts: [{ text: 'Here it is.' }, { inlineData: { mimeType: 'image/png', data: testPng(120, 80, 3).toString('base64') } }] } }], usageMetadata, modelVersion: 'fake-gem-image' }));
  }
  if (url.endsWith('/chat/completions')) {
    const model = String(body.model);
    if (model.startsWith('judge')) {
      const last = (body.messages as Array<{ content: unknown }>).at(-1)!.content;
      if (Array.isArray(last) && last.some((p: { type?: string }) => p.type === 'image_url')) judgeSawImage.push(model);
      return sse(res, verdictJson(model));
    }
    return sse(res, coderReply);
  }
  res.writeHead(404);
  res.end();
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

// ─────────────────────────────── Sandbox ───────────────────────────────

const sandbox = mkdtempSync(join(tmpdir(), 'gauntlet-gallery-'));
process.env.GAUNTLET_TESTS_DIR = join(sandbox, 'tests');
process.env.GAUNTLET_DATA_DIR = join(sandbox, 'data');
process.env.GAUNTLET_CONFIG_DIR = join(sandbox, 'config');
process.env.GAUNTLET_SUITES_DIR = join(sandbox, 'suites');
process.env.GAUNTLET_NO_BROWSER = '1';
cpSync(new URL('../config', import.meta.url), join(sandbox, 'config'), { recursive: true });
cpSync(new URL('../tests/art', import.meta.url), join(sandbox, 'tests', 'art'), { recursive: true });
mkdirSync(join(sandbox, 'tests', 'math'), { recursive: true });
writeFileSync(
  join(sandbox, 'tests', 'math', 'one.json'),
  JSON.stringify({ kind: 'prompt', id: 'math.one', version: '1.0.0', name: 'One', category: 'math', description: 'One plus one', difficulty: 'easy', scorer: { type: 'number' }, cases: [{ id: 'c1', prompt: 'What is 1 + 1?', expected: 2 }] }),
);
mkdirSync(join(sandbox, 'suites'), { recursive: true });
writeFileSync(join(sandbox, 'suites', 'gal.json'), JSON.stringify({ id: 'gal', version: '1.0.0', name: 'Gallery (one brief)', description: 'x', repeats: 1, tests: [{ id: 'art.gallery-masterpiece', cases: ['seed-1'] }, { id: 'art.gallery-painted-in-code', cases: ['seed-1'] }] }));
{
  const file = join(sandbox, 'config', 'models.json');
  const models = JSON.parse(readFileSync(file, 'utf8'));
  models.providers.push({ id: 'fakeoa', type: 'openai-compatible', label: 'Fake OpenAI', baseUrl: `${base}/v1`, apiKeyEnv: 'FAKE_KEY', maxConcurrency: 4 });
  models.providers.push({ id: 'fakegem', type: 'gemini', label: 'Fake Gemini', baseUrl: `${base}/gem`, apiKeyEnv: 'FAKE_KEY', maxConcurrency: 4 });
  const price = { inputPerM: 5, outputPerM: 40, verifiedAt: '2026-01-01' };
  models.contestants.push(
    { id: 'painter', label: 'Fake Painter', vendor: 'PaintCo', provider: 'fakeoa', model: 'painter-1', color: '#123456', enabled: true, imageOutput: true, imageOnly: true, imageOptions: { size: '1536x1024', quality: 'high' }, pricing: price, imagePricing: { perImage: { '1536x1024': { high: 0.25, medium: 0.063 } } } },
    { id: 'gem-painter', label: 'Fake Gemini Painter', vendor: 'GemCo', provider: 'fakegem', model: 'gem-image', color: '#223344', enabled: true, imageOutput: true, imageOnly: true, pricing: { inputPerM: 0.3, outputPerM: 30, verifiedAt: '2026-01-01' }, imagePricing: { perImage: { '*': { '*': 0.039 } } } },
    { id: 'coder', label: 'Fake Coder', vendor: 'CodeCo', provider: 'fakeoa', model: 'coder-1', color: '#334455', enabled: true, pricing: { inputPerM: 2, outputPerM: 10, verifiedAt: '2026-01-01' } },
    { id: 'judge-a', label: 'Judge A', vendor: 'JudgeA', provider: 'fakeoa', model: 'judge-a', color: '#445566', enabled: true, vision: true, pricing: { inputPerM: 1, outputPerM: 4, verifiedAt: '2026-01-01' } },
    { id: 'judge-b', label: 'Judge B', vendor: 'JudgeB', provider: 'fakeoa', model: 'judge-b', color: '#556677', enabled: true, vision: true, pricing: { inputPerM: 1, outputPerM: 4, verifiedAt: '2026-01-01' } },
    { id: 'judge-paintco', label: 'Judge PaintCo', vendor: 'PaintCo', provider: 'fakeoa', model: 'judge-paintco', color: '#667788', enabled: true, vision: true, pricing: { inputPerM: 1, outputPerM: 4, verifiedAt: '2026-01-01' } },
    { id: 'judge-blind', label: 'Judge Blind', vendor: 'BlindCo', provider: 'fakeoa', model: 'judge-blind', color: '#778899', enabled: true, vision: false, pricing: { inputPerM: 1, outputPerM: 4, verifiedAt: '2026-01-01' } },
  );
  writeFileSync(file, JSON.stringify(models));
  const settingsFile = join(sandbox, 'config', 'settings.json');
  const settings = JSON.parse(readFileSync(settingsFile, 'utf8'));
  settings.judges = ['judge-a', 'judge-b', 'judge-paintco', 'judge-blind'];
  settings.maxRetries = 0;
  writeFileSync(settingsFile, JSON.stringify(settings));
}

const { openAIImageGenerate, geminiImageGenerate, baselineImage, toGeneratedImage } = await import('../src/providers/image-gen.ts');
const { imageCallCost, imagePrice, supportsImageOutput, SKIP_NO_IMAGE_OUTPUT, SKIP_PICTURE_ONLY } = await import('../src/core/image-output.ts');
const judge = await import('../src/programs/lib/gallery-judge.ts');
const briefs = await import('../src/programs/lib/gallery-briefs.ts');
const runner = await import('../src/engine/runner.ts');
const store = await import('../src/engine/store.ts');
const review = await import('../src/engine/review.ts');
const manual = await import('../src/providers/manual.ts');
const registry = await import('../src/core/registry.ts');
const { ProviderError } = await import('../src/providers/types.ts');
const { loadContestants, loadProviders, contestantConfigHash } = await import('../src/core/config.ts');
const { sanitizePaintingSvg } = await import('../src/programs/lib/gallery-render.ts');
const { program: codeProgram } = await import('../src/programs/gallery-code.ts');

const contestant = (id: string) => loadContestants().find((c) => c.id === id)!;
const providerOf = (id: string) => loadProviders().find((p) => p.id === contestant(id).provider)!;
const ctxFor = (id: string) => ({ provider: providerOf(id), contestant: contestant(id), apiKey: 'k' });
const req = { prompt: 'Paint a lighthouse.', aspectRatio: '3:2' };

// ─────────────────────────────── Wire formats ───────────────────────────────

test('OpenAI Images: success sends size and quality, decodes the picture and bills the per-image price', async () => {
  imageMode = 'ok';
  requests.length = 0;
  const r = await openAIImageGenerate(ctxFor('painter'), req);
  const sent = requests.find((x) => x.url.endsWith('/images/generations'))!;
  assert.equal(sent.body.model, 'painter-1');
  assert.equal(sent.body.size, '1536x1024');
  assert.equal(sent.body.quality, 'high');
  assert.equal(sent.body.response_format, 'b64_json', 'not api.openai.com: ask for bytes');
  assert.equal(r.images.length, 1);
  assert.equal(r.images[0]!.width, 96);
  assert.equal(r.images[0]!.mediaType, 'image/png');
  assert.equal(r.stopReason, 'end');
  // 0.25 per image + 500 prompt tokens at $5/M; the 6000 image tokens are covered by the per-image price.
  assert.equal(imageCallCost(contestant('painter'), r), 0.2525);
});

test('OpenAI Images: a content-policy refusal is a refusal result, not an error', async () => {
  imageMode = 'refuse';
  const r = await openAIImageGenerate(ctxFor('painter'), req);
  assert.equal(r.stopReason, 'refusal');
  assert.equal(r.images.length, 0);
  assert.match(r.text, /safety system/);
  assert.equal(imageCallCost(contestant('painter'), r), 0, 'refused pictures cost nothing');
});

test('OpenAI Images: an unsupported size is a clear, non-retryable error naming the setting to change', async () => {
  imageMode = 'badsize';
  await assert.rejects(
    () => openAIImageGenerate(ctxFor('painter'), req),
    (e: unknown) => e instanceof ProviderError && !e.retryable && /imageOptions/.test(e.message) && /1536x1024/.test(e.message),
  );
});

test('OpenAI Images: no picture in a 200 reply → no image (scored 0 later); a URL reply is downloaded', async () => {
  imageMode = 'empty';
  const r = await openAIImageGenerate(ctxFor('painter'), req);
  assert.equal(r.images.length, 0);
  assert.equal(r.stopReason, 'other');
  assert.equal(r.rawStopReason, 'no_image');
  imageMode = 'url';
  const u = await openAIImageGenerate(ctxFor('painter'), req);
  assert.equal(u.images[0]?.mediaType, 'image/jpeg');
  imageMode = 'ok';
});

test('OpenAI Images: size/quality can be left out (xAI-style) and api.openai.com gets no response_format', async () => {
  requests.length = 0;
  const c = { ...contestant('painter'), imageOptions: { size: null, quality: null, responseFormat: 'b64_json' } };
  await openAIImageGenerate({ provider: providerOf('painter'), contestant: c, apiKey: 'k' }, req);
  const sent = requests.at(-1)!.body;
  assert.equal(sent.size, undefined);
  assert.equal(sent.quality, undefined);
  assert.equal(sent.response_format, 'b64_json');
  requests.length = 0;
  await openAIImageGenerate({ provider: providerOf('painter'), contestant: { ...contestant('painter'), imageOptions: undefined }, apiKey: 'k' }, req).catch(() => null);
  const openaiLike = { ...providerOf('painter'), baseUrl: `${base}/v1/api.openai.com` };
  requests.length = 0;
  await openAIImageGenerate({ provider: openaiLike, contestant: { ...contestant('painter'), imageOptions: undefined }, apiKey: 'k' }, req).catch(() => null);
  const oa = requests.at(-1)!.body;
  assert.equal(oa.response_format, undefined, 'gpt-image models reject response_format');
  assert.equal(oa.size, '1536x1024', 'default size on api.openai.com');
  assert.equal(oa.quality, 'high', 'default quality on api.openai.com');
});

test('Gemini: asks for TEXT+IMAGE with the aspect ratio and reads inline image data', async () => {
  geminiMode = 'ok';
  requests.length = 0;
  const r = await geminiImageGenerate(ctxFor('gem-painter'), req);
  const sent = requests.at(-1)!;
  assert.match(sent.url, /\/models\/gem-image:generateContent$/);
  const gc = sent.body.generationConfig as { responseModalities: string[]; imageConfig: { aspectRatio: string } };
  assert.deepEqual(gc.responseModalities, ['TEXT', 'IMAGE']);
  assert.equal(gc.imageConfig.aspectRatio, '3:2');
  assert.equal(r.images[0]!.width, 120);
  assert.equal(r.text, 'Here it is.');
  assert.equal(r.servedModel, 'fake-gem-image');
  assert.equal(imageCallCost(contestant('gem-painter'), r), 0.039 + (480 * 0.3) / 1e6);
});

test('Gemini: IMAGE_SAFETY and prompt blocks are refusals; text-only is "no picture"; a bad aspect ratio throws', async () => {
  geminiMode = 'safety';
  assert.equal((await geminiImageGenerate(ctxFor('gem-painter'), req)).stopReason, 'refusal');
  geminiMode = 'blocked';
  const blocked = await geminiImageGenerate(ctxFor('gem-painter'), req);
  assert.equal(blocked.stopReason, 'refusal');
  assert.match(blocked.rawStopReason, /PROHIBITED/);
  geminiMode = 'text';
  const t = await geminiImageGenerate(ctxFor('gem-painter'), req);
  assert.equal(t.images.length, 0);
  assert.equal(t.stopReason, 'other');
  assert.match(t.text, /description/);
  geminiMode = 'badaspect';
  await assert.rejects(() => geminiImageGenerate(ctxFor('gem-painter'), req), (e: unknown) => e instanceof ProviderError && !e.retryable && /aspect ratio/i.test(e.message));
  geminiMode = 'ok';
});

test('Random Baseline paints deterministic noise; only PNG/JPEG bytes count as pictures', async () => {
  const c = { ...contestant('random-baseline') };
  const ctx = { provider: loadProviders().find((p) => p.id === c.provider)!, contestant: c, apiKey: undefined };
  const a = await baselineImage(ctx, req);
  const b = await baselineImage(ctx, req);
  assert.equal(a.images[0]!.data, b.images[0]!.data);
  assert.equal(a.images[0]!.width, 384);
  assert.notEqual((await baselineImage(ctx, { ...req, prompt: 'other' })).images[0]!.data, a.images[0]!.data);
  assert.equal(toGeneratedImage(Buffer.from('GIF89a......').toString('base64')), null);
  assert.equal(toGeneratedImage(''), null);
});

test('image-output capability and per-image prices', () => {
  assert.equal(supportsImageOutput({}, 'mock'), true);
  assert.equal(supportsImageOutput({}, 'manual'), true);
  assert.equal(supportsImageOutput({}, 'openai-compatible'), false);
  assert.equal(supportsImageOutput({ imageOutput: false }, 'manual'), false);
  const p = { perImage: { '1024x1024': { low: 0.011, high: 0.167 }, '1536x1024': { high: 0.25 } } };
  assert.equal(imagePrice(p, '1024x1024', 'low'), 0.011);
  assert.equal(imagePrice(p, '1024x1024', 'ultra'), 0.167, 'unknown quality: most expensive in the row');
  assert.equal(imagePrice(p, '9x9', 'low'), 0.25, 'unknown size: most expensive overall');
  assert.equal(imagePrice({ perImage: { '*': { '*': 0.039 } } }, undefined, undefined), 0.039);
  assert.equal(imagePrice(undefined, 'x', 'y'), null);
});

test('adding picture settings never changes the config hash of other contestants', () => {
  const c = contestant('coder');
  assert.equal(contestantConfigHash({ ...c, imageOptions: { size: '1x1' } }), contestantConfigHash(c));
  const p = contestant('painter');
  assert.notEqual(contestantConfigHash({ ...p, imageOptions: { size: '1024x1024' } }), contestantConfigHash(p));
});

// ─────────────────────────────── Judge parsing ───────────────────────────────

const B1 = briefs.briefForSeed(1);
const good = JSON.parse(verdictJson('judge-a'));

test('judge replies: plain JSON, fenced JSON, map-shaped checklists, synonyms and "8/10" strings all parse', () => {
  const a = judge.parseJudgeReply('a', JSON.stringify(good), B1);
  assert.ok(a.ok);
  const fenced = judge.parseJudgeReply('b', 'Here is my verdict:\n```json\n' + JSON.stringify(good) + '\n```\nThanks', B1);
  assert.ok(fenced.ok);
  const map = judge.parseJudgeReply('c', JSON.stringify({ checklist: Object.fromEntries(ITEMS.map((id) => [id.toLowerCase(), id === 'E2' ? 'Partially' : 'Present'])), artistry: { composition: '8/10', light: 7, color: { score: '6' }, craft: 9, style: 8, gallery: { score: 7.5, reason: 'nice' } } }), B1);
  assert.ok(map.ok, !map.ok ? map.error : '');
  if (map.ok) {
    assert.equal(map.verdict.checklist.E2!.verdict, 'partly');
    assert.equal(map.verdict.checklist.E1!.verdict, 'yes');
    assert.equal(map.verdict.artistry.colour.score, 6, '"color" is accepted for "colour"');
    assert.equal(map.verdict.artistry.composition.score, 8);
  }
});

test('judge replies: malformed ones are rejected with a reason, never guessed', () => {
  const cases: Array<[string, RegExp]> = [
    ['Beautiful painting, 9 out of 10!', /no JSON/],
    ['[1, 2, 3]', /no JSON object/],
    [JSON.stringify({ ...good, checklist: good.checklist.slice(0, 7) }), /incomplete/],
    [JSON.stringify({ ...good, artistry: { ...good.artistry, craft: { score: 11 } } }), /craft/],
    [JSON.stringify({ ...good, artistry: { ...good.artistry, light: { score: 'excellent' } } }), /light/],
    [JSON.stringify({ checklist: good.checklist.map((x: object) => ({ ...x, verdict: 'maybe' })), artistry: good.artistry }), /incomplete/],
  ];
  for (const [text, re] of cases) {
    const r = judge.parseJudgeReply('j', text, B1);
    assert.equal(r.ok, false, text.slice(0, 60));
    if (!r.ok) assert.match(r.error, re);
  }
  // One missing checklist line is tolerated: that line simply has no verdict from this judge.
  const one = judge.parseJudgeReply('j', JSON.stringify({ ...good, checklist: good.checklist.slice(0, 8) }), B1);
  assert.ok(one.ok);
  if (one.ok) assert.equal(one.verdict.checklist.N3, undefined);
});

test('aggregation: medians per line and across judges, the 50/50 score, spread and disagreement', () => {
  const v = (id: string, e6: 'yes' | 'partly' | 'no', art: number) => {
    const r = judge.parseJudgeReply(id, JSON.stringify({ checklist: ITEMS.map((x) => ({ id: x, verdict: x === 'E6' ? e6 : 'yes' })), artistry: Object.fromEntries(judge.ARTISTRY_CRITERIA.map((c) => [c.id, art])) }), B1);
    assert.ok(r.ok);
    return (r as { verdict: Parameters<typeof judge.aggregate>[1][number] }).verdict;
  };
  const agg = judge.aggregate(B1, [v('a', 'partly', 8), v('b', 'no', 7)], [{ judgeId: 'c', error: 'timeout' }]);
  assert.equal(agg.items.find((i) => i.id === 'E6')!.consensus, 0.25);
  assert.equal(agg.followed, 8);
  assert.equal(agg.total, 9);
  assert.equal(agg.adherence, 0.917);
  assert.equal(agg.artistry, 7.5);
  assert.equal(agg.spread, 1);
  assert.equal(agg.disagreement, false);
  assert.equal(agg.judges.length, 3);
  assert.equal(agg.judges[2]!.error, 'timeout');
  assert.equal(judge.galleryScore(agg.adherence, agg.artistry), 0.834);
  const split = judge.aggregate(B1, [v('a', 'yes', 9), v('b', 'no', 6)], []);
  assert.equal(split.disagreement, true, 'yes vs no on one line, and a 3-point artistry spread');
  assert.equal(split.items.find((i) => i.id === 'E6')!.split, true);
  const three = judge.aggregate(B1, [v('a', 'yes', 9), v('b', 'no', 3), v('c', 'yes', 8)], []);
  assert.equal(three.artistry, 8, 'median, so one outlier judge cannot drag the score');
  assert.equal(three.items.find((i) => i.id === 'E6')!.consensus, 1);
});

test('the briefs: 8 commissions, 6 elements and 3 rules each, identical prompts every time, and the judge prompt never names an artist', () => {
  assert.equal(briefs.BRIEFS.length, 8);
  for (const b of briefs.BRIEFS) {
    assert.equal(b.elements.length, 6, b.id);
    assert.equal(b.avoid.length, 3, b.id);
    assert.equal(briefs.imagePrompt(b), briefs.imagePrompt(briefs.briefForSeed(b.n)));
    assert.match(briefs.imagePrompt(b), /aspect ratio 3:2/);
    assert.match(briefs.codePrompt(b), /width="1536" height="1024"/);
    const jp = judge.judgePrompt(b);
    for (const i of briefs.briefItems(b)) assert.ok(jp.includes(`${i.id} (`), `${b.id}: judge prompt lists ${i.id}`);
  }
  assert.throws(() => briefs.briefForSeed(9), /seeds 1–8/);
});

test('Painted in Code: sanitising removes scripts, handlers, external and embedded images', () => {
  const dirty = '<svg width="10" height="10"><script>alert(1)</script><rect onload="x()" width="5" height="5"/><image href="https://evil/x.png"/><use href="data:image/svg+xml;base64,AAA"/><foreignObject><div>hi</div></foreignObject><style>@import url(https://x/y.css);</style></svg>';
  const s = sanitizePaintingSvg(dirty);
  assert.doesNotMatch(s, /script|onload|<image|foreignObject|https:|data:/);
  assert.match(s, /xmlns="http:\/\/www.w3.org\/2000\/svg"/);
});

// ─────────────────────────────── Runs ───────────────────────────────

async function run(contestantIds: string[], suiteId = 'gal', testIds?: string[]) {
  const id = await runner.startRun({ contestantIds, suiteId, testIds, repeats: 1 });
  await runner.waitForRun(id);
  return { id, results: store.readResults(id), manifest: store.readManifest(id)! };
}

test('a Gallery run: paints, judges with two vision judges from other vendors, scores 50/50, skips non-painters', async () => {
  imageMode = 'ok';
  judgeMode = 'good';
  judgeSawImage.length = 0;
  coderReply = 'No SVG today, sorry.';
  const { results } = await run(['painter', 'gem-painter', 'coder', 'random-baseline']);
  const get = (c: string, t: string) => results.find((r) => r.contestantId === c && r.testId === t)!;
  const p = get('painter', 'art.gallery-masterpiece');
  assert.equal(p.status, 'ok', p.error);
  assert.equal(p.score, 0.834);
  assert.equal(p.summary, 'Brief followed 8/9 · Artistry 7.5/10');
  const g = p.scoreDetail.gallery as { judges: Array<{ judgeId: string }>; painting: { name: string }; brief: { title: string }; mode: string };
  assert.deepEqual(g.judges.map((j) => j.judgeId).sort(), ['judge-a@judge', 'judge-b@judge'], 'never the artist’s vendor, never a judge that cannot see images');
  assert.equal(g.painting.name, 'painting.png');
  assert.equal(g.brief.title, 'The Keeper’s Daughter');
  assert.equal(g.mode, 'image');
  assert.ok(p.artifacts.some((a) => a.name === 'painting.png' && a.kind === 'png'));
  assert.ok(judgeSawImage.includes('judge-a') && judgeSawImage.includes('judge-b'), 'judges were sent the picture');
  const judgeEntries = p.transcript.filter((e) => e.judge);
  assert.equal(judgeEntries.length, 2);
  for (const e of judgeEntries) {
    assert.equal(e.messages[0]!.images?.[0]?.name, 'painting.png', 'anonymised file name');
    assert.equal(e.messages[0]!.images?.[0]?.data, undefined, 'no image bytes in stored transcripts');
    assert.doesNotMatch(JSON.stringify(e.messages), /Fake Painter|painter-1|PaintCo/, 'nothing about the artist reaches the judges');
  }
  assert.equal(p.metrics.costUsd, 0.2525);
  assert.ok(p.metrics.judgeCostUsd > 0);

  const gem = get('gem-painter', 'art.gallery-masterpiece');
  assert.equal(gem.status, 'ok', gem.error);
  assert.equal(gem.metrics.costUsd, Math.round((0.039 + (480 * 0.3) / 1e6) * 1e8) / 1e8);

  const base = get('random-baseline', 'art.gallery-masterpiece');
  assert.equal(base.status, 'ok', base.error);
  assert.equal(base.metrics.costUsd, 0);

  const skipped = get('coder', 'art.gallery-masterpiece');
  assert.equal(skipped.status, 'skipped');
  assert.equal(skipped.summary, SKIP_NO_IMAGE_OUTPUT);
  assert.equal(skipped.score, null);
  assert.equal(skipped.transcript.length, 0);

  const pictureOnly = get('painter', 'art.gallery-painted-in-code');
  assert.equal(pictureOnly.status, 'skipped');
  assert.equal(pictureOnly.summary, SKIP_PICTURE_ONLY);

  const coded = get('coder', 'art.gallery-painted-in-code');
  assert.equal(coded.status, 'ok');
  assert.equal(coded.score, 0);
  assert.match(coded.summary, /No SVG painting returned/);
});

test('Painted in Code without a browser is an error that says what to install (never a silent 0)', async () => {
  coderReply = '```svg\n<svg xmlns="http://www.w3.org/2000/svg" width="1536" height="1024" viewBox="0 0 1536 1024"><rect width="1536" height="1024" fill="#123"/></svg>\n```';
  const { results } = await run(['coder'], 'gal');
  const r = results.find((x) => x.testId === 'art.gallery-painted-in-code')!;
  assert.equal(r.status, 'error');
  assert.match(r.error ?? '', /headless Chromium/);
  assert.ok(r.artifacts.some((a) => a.name === 'painting.svg'), 'the SVG is kept for a later look');
});

test('refusals and missing pictures score 0 without calling the judges', async () => {
  imageMode = 'refuse';
  const refused = (await run(['painter'], 'gal', ['art.gallery-masterpiece'])).results.find((r) => r.caseId === 'seed-1')!;
  assert.equal(refused.status, 'refusal');
  assert.equal(refused.score, 0);
  assert.equal(refused.transcript.filter((e) => e.judge).length, 0);
  imageMode = 'empty';
  const none = (await run(['painter'], 'gal', ['art.gallery-masterpiece'])).results[0]!;
  assert.equal(none.status, 'ok');
  assert.equal(none.score, 0);
  assert.match(none.summary, /No picture returned/);
  imageMode = 'badsize';
  const bad = (await run(['painter'], 'gal', ['art.gallery-masterpiece'])).results[0]!;
  assert.equal(bad.status, 'error');
  assert.match(bad.error ?? '', /imageOptions/);
  imageMode = 'ok';
});

test('judge disagreement is flagged and sent to Blind Review; too few valid judges waits for the owner', async () => {
  judgeMode = 'split';
  const split = (await run(['painter'], 'gal', ['art.gallery-masterpiece'])).results[0]!;
  assert.equal(split.status, 'ok');
  assert.equal(split.scoreDetail.judgeDisagreement, true);
  assert.match(split.summary, /judges disagree/);
  assert.ok(review.reviewQueue('art.gallery-masterpiece').some((i) => i.key === split.key && i.reason === 'judge-disagreement'));

  judgeMode = 'b-garbage';
  const { id, results } = await run(['painter'], 'gal', ['art.gallery-masterpiece']);
  const pending = results[0]!;
  assert.equal(pending.status, 'pending-human');
  assert.equal(pending.score, null);
  assert.match(pending.summary, /only 1 of 2 judges/);
  assert.match(String(pending.scoreDetail.notes), /unparsable verdict/);
  // The owner rates the artistry 9/10 in Blind Review: brief adherence stays as judged (one judge), artistry is the owner's.
  const lite = review.submitHumanScore({ runId: id, key: pending.key, score: 0.9, rater: 'owner' });
  assert.equal(lite.status, 'ok');
  const g = lite.scoreDetail.gallery as { artistry: number; owner: { artistry: number }; adherence: number };
  assert.equal(g.owner.artistry, 9);
  assert.equal(lite.score, Math.round((0.5 * g.adherence + 0.45) * 1000) / 1000);
  assert.match(lite.summary, /Artistry 9\.0\/10 \(owner\)/);
  judgeMode = 'good';
});

test('owner override and blind votes on a judged painting', async () => {
  const { id, results } = await run(['painter'], 'gal', ['art.gallery-masterpiece']);
  const r = results[0]!;
  assert.equal(r.score, 0.834);
  // A blind vote is recorded as a human score but never changes the score.
  const voted = review.submitHumanScore({ runId: id, key: r.key, score: 1, rater: 'Blind vote', note: '7 of 12 votes · 1st of 3' });
  assert.equal(voted.score, 0.834);
  assert.deepEqual((voted.scoreDetail.gallery as { votes: unknown }).votes, { votes: 7, of: 12, share: 0.583 });
  // The owner's artistry rating replaces the judges' artistry (7.5) and keeps the checklist (0.917).
  const owned = review.submitHumanScore({ runId: id, key: r.key, score: 0.6, rater: 'owner' });
  assert.equal(owned.score, Math.round((0.5 * 0.917 + 0.3) * 1000) / 1000);
  assert.equal((owned.scoreDetail.gallery as { owner: { judgeArtistry: number } }).owner.judgeArtistry, 7.5);
  assert.equal(owned.humanScores?.length, 2);
  assert.ok(review.reviewQueue().some((i) => i.key === r.key), 'every painting can be rated in Blind Review');
});

test('estimates: per-image prices for painters, nothing for skipped models, judges with image tokens', async () => {
  const est = await runner.estimateRun({ suiteId: 'gal', contestantIds: ['painter', 'gem-painter', 'coder', 'random-baseline', 'manual-chat'], repeats: 1 });
  const per = (id: string) => est.perContestant.find((p) => p.contestantId === id)!;
  const gm = est.perTest.find((t) => t.testId === 'art.gallery-masterpiece')!;
  if (gm.basis === 'definition') {
    assert.equal(gm.perContestant.painter, Math.round((0.25 + (480 * 5) / 1e6) * 10000) / 10000);
    assert.equal(gm.perContestant['gem-painter'], Math.round((0.039 + (480 * 0.3) / 1e6) * 10000) / 10000);
  }
  assert.equal(gm.perContestant.coder, undefined, 'no image output: skipped, so no cost cell (shown as "skipped")');
  assert.equal(gm.perContestant['manual-chat'], 0);
  const code = est.perTest.find((t) => t.testId === 'art.gallery-painted-in-code')!;
  assert.equal(code.perContestant.painter, undefined, 'picture-only: skipped on the code test');
  assert.ok(code.perContestant.coder! > 0);
  assert.ok(est.judgeCostUsd > 0);
  assert.ok(per('painter').estCostUsd > 0.25);
  assert.ok(est.warnings.some((w) => /Fake Coder.*no image output/.test(w)), est.warnings.join('\n'));
  assert.ok(est.warnings.some((w) => /picture-only/.test(w)));
});

test('the Random Baseline’s noise painting is judged like any painting (the visible floor)', async () => {
  const { results } = await run(['random-baseline'], 'gal', ['art.gallery-masterpiece']);
  const r = results[0]!;
  assert.equal(r.status, 'ok');
  assert.ok(r.artifacts.some((a) => a.name === 'painting.png'));
  assert.equal((r.scoreDetail.gallery as { painting: { width: number } }).painting.width, 384);
});

test('manual contestants: the inbox asks for a picture, and the upload route accepts PNG/JPEG only', async () => {
  const { startServer } = await import('../src/server/index.ts');
  const port = await new Promise<number>((resolve) => {
    const probe = createNetServer().listen(0, '127.0.0.1', () => {
      const p = (probe.address() as AddressInfo).port;
      probe.close(() => resolve(p));
    });
  });
  const srv = await startServer({ port, host: '127.0.0.1' });
  try {
    const runId = await runner.startRun({ contestantIds: ['manual-chat'], suiteId: 'gal', testIds: ['art.gallery-masterpiece'], repeats: 1 });
    let pending = manual.listManualRequests(runId);
    for (let i = 0; i < 100 && !pending.length; i++) {
      await new Promise((r) => setTimeout(r, 20));
      pending = manual.listManualRequests(runId);
    }
    const reqItem = pending[0]!;
    assert.equal(reqItem.expects, 'image');
    assert.equal(reqItem.aspectRatio, '3:2');
    assert.match(reqItem.combinedPrompt, /COMMISSION No\. 1/);
    const post = (path: string, body: unknown) => fetch(`${srv.url}${path}`, { method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify(body) });
    // A text reply to a picture request is refused (400), and so is a GIF or a thumbnail.
    assert.equal((await post(`/api/manual/${reqItem.id}`, { text: 'here you go' })).status, 400);
    assert.equal((await post(`/api/manual/${reqItem.id}/image`, { data: Buffer.from('GIF89a').toString('base64') })).status, 400);
    assert.equal((await post(`/api/manual/${reqItem.id}/image`, { data: testPng(32, 32).toString('base64') })).status, 400);
    assert.equal((await post(`/api/manual/${reqItem.id}/image`, { data: `data:image/png;base64,${PAINTING.toString('base64')}`, costUsd: -1 })).status, 400);
    const ok = await post(`/api/manual/${reqItem.id}/image`, { data: `data:image/png;base64,${PAINTING.toString('base64')}`, costUsd: 0.04, note: 'Made in a chat app' });
    assert.equal(ok.status, 200, await ok.clone().text());
    assert.equal((await post(`/api/manual/${reqItem.id}/image`, { data: PAINTING.toString('base64') })).status, 404, 'already answered');
    await runner.waitForRun(runId);
    const r = store.readResults(runId)[0]!;
    assert.equal(r.status, 'ok', r.error);
    assert.equal(r.metrics.costUsd, 0.04);
    assert.ok(r.artifacts.some((a) => a.name === 'painting.png'));
    // Vendor "Manual": all three vision judges sit on the panel (median of 8, 7, 8 = 8; E6 median = partly).
    assert.equal(r.score, 0.872);
    assert.equal((r.scoreDetail.gallery as { judges: unknown[] }).judges.length, 3);
    // The painting is served with an image content type.
    const art = r.artifacts.find((a) => a.name === 'painting.png')!;
    const res = await fetch(`${srv.url}/api/runs/${runId}/artifacts/${art.file}`);
    assert.equal(res.headers.get('content-type'), 'image/png');
  } finally {
    await srv.close();
  }
});

test('the program source is hashed into the test: the briefs and judge prompt are part of the test identity', () => {
  const t = registry.getTest('art.gallery-masterpiece')!;
  assert.ok(t.hash);
  assert.equal(registry.programSourceHash('gallery-masterpiece').length, 12);
  assert.notEqual(registry.programSourceHash('gallery-masterpiece'), registry.programSourceHash('gallery-code'));
  assert.equal(codeProgram.requiresImageOutput, undefined, 'Painted in Code is for text models');
});
