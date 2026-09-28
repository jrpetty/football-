/**
 * The Gallery self-check: two Painted-in-Code paintings written by hand from the rendered prompts
 * (verification/gallery/selfcheck/commission-2.svg and commission-8.svg) go through the REAL pipeline:
 * startRun → program → SVG sanitising → headless Chromium rendering → the judge panel → scoring → stored results.
 * The contestant and the two judges are a local fake OpenAI-compatible server (no API keys, no cost): the
 * contestant replies with the hand-written SVG for the commission it is asked for; the "mock judges" reply
 * with fixed, valid verdicts (they do not look at the picture, so their numbers prove the plumbing, not taste).
 * The Random Baseline paints both tests too, and a fake image model paints The Gallery Masterpiece with the
 * same pictures rendered to PNG, so the image test runs end to end as well.
 *
 *   node verification/gallery/selfcheck.mts [output folder]
 *
 * Prints the scores and leaves the run (and a config sandbox) in the output folder, so the dashboard can be
 * started on it: GAUNTLET_CONFIG_DIR=<out>/config GAUNTLET_DATA_DIR=<out>/data node src/cli.ts serve
 */
import { cpSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { join, resolve } from 'node:path';
import type { AddressInfo } from 'node:net';

const here = new URL('.', import.meta.url);
const root = resolve(new URL('../..', import.meta.url).pathname);
const out = resolve(process.argv[2] ?? join(root, 'data', 'gallery-selfcheck'));
const SVG: Record<string, string> = {
  2: readFileSync(new URL('selfcheck/commission-2.svg', here), 'utf8'),
  8: readFileSync(new URL('selfcheck/commission-8.svg', here), 'utf8'),
};

// Gauntlet reads these when its modules load, so set them before importing any of it.
process.env.GAUNTLET_CONFIG_DIR = join(out, 'config');
process.env.GAUNTLET_DATA_DIR = join(out, 'data');
process.env.GAUNTLET_SUITES_DIR = join(out, 'suites');

// Render the hand-made SVGs once to PNG: these are also what the fake image model "paints".
const { rasterizeSvg } = await import('../../src/programs/lib/gallery-render.ts');
const PNG: Record<string, Buffer> = {};
for (const [n, svg] of Object.entries(SVG)) {
  const r = await rasterizeSvg(svg, 1536, 1024);
  if (!r?.ok) throw new Error(`Chromium could not render commission ${n}: ${r?.error ?? 'no browser'}`);
  PNG[n] = r.png;
}

const commission = (text: string) => /COMMISSION No\. (\d+)/.exec(text)?.[1] ?? '';
const ITEMS = ['E1', 'E2', 'E3', 'E4', 'E5', 'E6', 'N1', 'N2', 'N3'];
function verdict(judge: string, n: string, big: boolean): string {
  // Fixed mock verdicts: a full painting passes most lines; a tiny picture (the baseline's circle/noise) fails.
  const partly = judge === 'mock-judge-b' ? (n === '2' ? 'E6' : 'E4') : 'E3';
  const checklist = ITEMS.map((id) => ({ id, verdict: !big && id.startsWith('E') ? 'no' : id === partly ? 'partly' : 'yes', reason: big ? (id === partly ? 'present but small' : id.startsWith('N') ? 'respected' : 'clearly there') : id.startsWith('N') ? 'respected' : 'nothing recognisable' }));
  const base = big ? (judge === 'mock-judge-a' ? 7 : 6) : 1;
  const artistry = Object.fromEntries(['composition', 'light', 'colour', 'craft', 'style', 'gallery'].map((c, i) => [c, { score: Math.min(10, base + (i % 2)), reason: 'mock judge' }]));
  return JSON.stringify({ checklist, artistry, summary: big ? 'A warm, loosely brushed scene that follows most of the brief.' : 'No recognisable painting.' });
}

const server = createServer(async (req, res) => {
  const chunks: Buffer[] = [];
  for await (const c of req) chunks.push(c as Buffer);
  const body = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
  if (req.url?.endsWith('/images/generations')) {
    const png = PNG[commission(body.prompt)] ?? PNG['8']!;
    res.writeHead(200, { 'content-type': 'application/json' });
    return res.end(JSON.stringify({ data: [{ b64_json: png.toString('base64') }], usage: { input_tokens: 480, output_tokens: 6000 } }));
  }
  const messages = body.messages as Array<{ content: unknown }>;
  const last = messages.at(-1)!.content;
  const text = typeof last === 'string' ? last : (last as Array<{ type: string; text?: string; image_url?: { url: string } }>).map((p) => p.text ?? '').join('\n');
  let content: string;
  if (String(body.model).startsWith('mock-judge')) {
    const img = Array.isArray(last) ? (last as Array<{ image_url?: { url: string } }>).find((p) => p.image_url)?.image_url?.url ?? '' : '';
    content = verdict(String(body.model), commission(text), img.length > 900_000);
  } else {
    content = '```svg\n' + (SVG[commission(text)] ?? '') + '\n```';
  }
  const chunk = (delta: unknown, finish: string | null = null) => `data: ${JSON.stringify({ id: 'x', object: 'chat.completion.chunk', created: 1, model: body.model, choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  res.write(chunk({ role: 'assistant', content }));
  res.write(chunk({}, 'stop'));
  res.write(`data: ${JSON.stringify({ id: 'x', object: 'chat.completion.chunk', created: 1, model: body.model, choices: [], usage: { prompt_tokens: 1800, completion_tokens: Math.ceil(content.length / 4) } })}\n\n`);
  res.end('data: [DONE]\n\n');
});
await new Promise<void>((r) => server.listen(0, '127.0.0.1', r));
const base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;

mkdirSync(join(out, 'suites'), { recursive: true });
cpSync(join(root, 'config'), join(out, 'config'), { recursive: true });
const modelsFile = join(out, 'config', 'models.json');
const models = JSON.parse(readFileSync(modelsFile, 'utf8'));
models.providers = models.providers.filter((p: { id: string }) => p.id !== 'selfcheck');
models.providers.push({ id: 'selfcheck', type: 'openai-compatible', label: 'Self-check (local)', baseUrl: `${base}/v1`, apiKeyEnv: null, maxConcurrency: 4 });
const free = { inputPerM: 0, outputPerM: 0, verifiedAt: '2026-09-28', source: 'local self-check server' };
models.contestants = models.contestants.filter((c: { provider: string }) => c.provider !== 'selfcheck');
models.contestants.push(
  { id: 'selfcheck-coder', label: 'Hand-painted SVG (self-check)', vendor: 'Self-check', provider: 'selfcheck', model: 'selfcheck-coder', color: '#c9a24a', enabled: true, pricing: free },
  { id: 'selfcheck-painter', label: 'Fake image model (self-check)', vendor: 'Self-check', provider: 'selfcheck', model: 'selfcheck-painter', color: '#5aa9e6', enabled: true, imageOutput: true, imageOnly: true, imageOptions: { size: null, quality: null, responseFormat: 'b64_json' }, pricing: free, imagePricing: { perImage: { '*': { '*': 0 } } } },
  { id: 'mock-judge-a', label: 'Mock judge A', vendor: 'Mock A', provider: 'selfcheck', model: 'mock-judge-a', color: '#7fb2ff', enabled: true, vision: true, pricing: free },
  { id: 'mock-judge-b', label: 'Mock judge B', vendor: 'Mock B', provider: 'selfcheck', model: 'mock-judge-b', color: '#d58bff', enabled: true, vision: true, pricing: free },
);
writeFileSync(modelsFile, JSON.stringify(models, null, 2));
const settingsFile = join(out, 'config', 'settings.json');
writeFileSync(settingsFile, JSON.stringify({ ...JSON.parse(readFileSync(settingsFile, 'utf8')), judges: ['mock-judge-a', 'mock-judge-b'] }, null, 2));
writeFileSync(join(out, 'suites', 'gallery-selfcheck.json'), JSON.stringify({ id: 'gallery-selfcheck', version: '1.0.0', name: 'Gallery self-check', description: 'Commissions 2 and 8 of both Gallery tests.', repeats: 1, tests: [{ id: 'art.gallery-masterpiece', cases: ['seed-2', 'seed-8'] }, { id: 'art.gallery-painted-in-code', cases: ['seed-2', 'seed-8'] }] }, null, 2));

const runner = await import('../../src/engine/runner.ts');
const store = await import('../../src/engine/store.ts');
const { closeBrowser } = await import('../../src/scoring/browser.ts');
const runId = await runner.startRun({ name: 'Gallery self-check · hand-painted SVGs', suiteId: 'gallery-selfcheck', contestantIds: ['selfcheck-painter', 'selfcheck-coder', 'random-baseline'], repeats: 1 });
await runner.waitForRun(runId);
let failed = 0;
for (const r of store.readResults(runId).sort((a, b) => a.key.localeCompare(b.key))) {
  const g = r.scoreDetail.gallery as { followed?: number; total?: number; artistry?: number } | undefined;
  console.log(`${r.contestantId.padEnd(20)} ${r.testId.padEnd(28)} ${r.caseId}  ${r.status.padEnd(8)} score ${r.score === null ? '—' : r.score.toFixed(3)}  ${r.summary}`);
  if (r.status === 'error') failed++;
  if (r.contestantId === 'selfcheck-coder' && r.testId === 'art.gallery-painted-in-code' && !(g && g.total === 9 && r.artifacts.some((a) => a.name === 'painting.png'))) failed++;
}
console.log(`\nRun ${runId} stored in ${join(out, 'data')}`);
await closeBrowser();
server.close();
if (failed) {
  console.error(`${failed} problem(s)`);
  process.exit(1);
}
