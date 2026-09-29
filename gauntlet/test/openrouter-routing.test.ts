/**
 * "One key for everything": a model with no key of its own runs through a (fake) OpenRouter with the right slug,
 * the run manifest records the route, the config hash differs from a direct run, and the combined leaderboard keeps
 * routed results on their own row. Also: the user settings overlay (with a real user folder, in a sandbox) and the
 * one-paste key flow.
 */
import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';

// A fake OpenRouter: the model list and a streaming chat endpoint that records what it was sent.
const seen: Array<{ path: string; auth?: string; body: Record<string, unknown> }> = [];
const fakeOpenRouter = createServer(async (req, res) => {
  let raw = '';
  for await (const c of req) raw += c;
  const body = raw ? (JSON.parse(raw) as Record<string, unknown>) : {};
  seen.push({ path: req.url ?? '', auth: req.headers.authorization, body });
  if (req.url?.endsWith('/models')) {
    if (req.headers.authorization === 'Bearer sk-or-v1-bad') {
      res.writeHead(401, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ error: { message: 'Invalid API key', code: 401 } }));
      return;
    }
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(
      JSON.stringify({
        data: [
          { id: 'fakeco/fake-model-1.5', name: 'FakeCo: Fake Model 1.5', context_length: 100000, pricing: { prompt: '0.000003', completion: '0.000015' } },
          { id: 'fakeco/other', name: 'FakeCo: Other', pricing: { prompt: '0', completion: '0' } },
        ],
      }),
    );
    return;
  }
  const chunk = (delta: unknown, finish: string | null = null) => `data: ${JSON.stringify({ id: 'x', object: 'chat.completion.chunk', created: 1, model: 'fakeco/fake-model-1.5', choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  res.write(chunk({ role: 'assistant', content: 'FINAL ANSWER: 4' }));
  res.write(chunk({}, 'stop'));
  res.write(`data: ${JSON.stringify({ id: 'x', object: 'chat.completion.chunk', created: 1, model: 'fakeco/fake-model-1.5', choices: [], usage: { prompt_tokens: 100, completion_tokens: 10 } })}\n\n`);
  res.end('data: [DONE]\n\n');
});
await new Promise<void>((r) => fakeOpenRouter.listen(0, '127.0.0.1', r));
after(() => fakeOpenRouter.close());
const port = (fakeOpenRouter.address() as AddressInfo).port;

// Sandbox: a real (non-portable) user folder, so the settings overlay behaves as on the owner's computer.
const sandbox = mkdtempSync(join(tmpdir(), 'gauntlet-openrouter-'));
process.env.GAUNTLET_USER_DIR = join(sandbox, 'user');
process.env.GAUNTLET_TESTS_DIR = join(sandbox, 'tests');
process.env.GAUNTLET_CONFIG_DIR = join(sandbox, 'config');
process.env.GAUNTLET_NO_BROWSER = '1';
delete process.env.GAUNTLET_DATA_DIR;
delete process.env.GAUNTLET_ENV_FILE;
delete process.env.FAKE_DIRECT_KEY;
cpSync(new URL('../config', import.meta.url), join(sandbox, 'config'), { recursive: true });
{
  const file = join(sandbox, 'config', 'models.json');
  const models = JSON.parse(readFileSync(file, 'utf8'));
  const or = models.providers.find((p: { id: string }) => p.id === 'openrouter');
  or.baseUrl = `http://127.0.0.1:${port}/api/v1`;
  models.providers.push({ id: 'fakedirect', type: 'openai-compatible', label: 'FakeCo', baseUrl: 'http://127.0.0.1:9/v1', apiKeyEnv: 'FAKE_DIRECT_KEY', maxConcurrency: 2 });
  models.contestants.push({ id: 'fake-model-1-5', label: 'Fake Model 1.5', vendor: 'FakeCo', provider: 'fakedirect', model: 'fake-model-1-5', color: '#123456', enabled: true, options: { effort: 'high', supportsTemperature: false }, pricing: { inputPerM: 2, outputPerM: 10, verifiedAt: '2026-01-01' }, openrouterModel: 'fakeco/fake-model-1.5' });
  writeFileSync(file, JSON.stringify(models));
}
mkdirSync(join(sandbox, 'tests', 'math'), { recursive: true });
writeFileSync(
  join(sandbox, 'tests', 'math', 'tiny.json'),
  JSON.stringify({ kind: 'prompt', id: 'math.tiny', version: '1.0.0', name: 'Tiny', category: 'math', description: 'Two plus two', difficulty: 'easy', scorer: { type: 'number' }, cases: [{ id: 'c1', prompt: 'What is 2 + 2?', expected: 4 }] }),
);
mkdirSync(join(sandbox, 'user'), { recursive: true });
writeFileSync(join(sandbox, 'user', 'migrated.json'), '{}');

const paths = await import('../src/core/paths.ts');
const config = await import('../src/core/config.ts');
const openrouter = await import('../src/core/openrouter.ts');
const keys = await import('../src/core/keys.ts');
const runner = await import('../src/engine/runner.ts');
const store = await import('../src/engine/store.ts');
const { pasteOne } = await import('../src/server/setup-routes.ts');

test('the sandbox user folder holds keys, runs and the settings overlay (not the home folder)', () => {
  assert.equal(keys.ENV_FILE, join(sandbox, 'user', '.env'));
  assert.equal(paths.DATA_DIR, join(sandbox, 'user', 'data'));
  assert.equal(paths.USER_SETTINGS_FILE, join(sandbox, 'user', 'settings.json'));
});

test('settings overlay: writes go to the user file, reads merge it over the shipped defaults', () => {
  const shippedFile = join(sandbox, 'config', 'settings.json');
  const shippedBefore = readFileSync(shippedFile, 'utf8');
  config.updateSettings({ currency: { code: 'EUR', usdPerUnit: 1.1, rateDate: '2026-09-29' } });
  config.updateSettings({ budget: { monthlyUsd: 25 } });
  config.updateSettings({ gradingOfficial: 'human' });
  assert.equal(readFileSync(shippedFile, 'utf8'), shippedBefore, 'shipped defaults never change');
  const overlay = JSON.parse(readFileSync(paths.USER_SETTINGS_FILE, 'utf8'));
  assert.deepEqual(Object.keys(overlay).sort(), ['budget', 'currency', 'gradingOfficial']);
  const s = config.loadSettings();
  assert.equal(s.currency?.code, 'EUR');
  assert.equal(s.gradingOfficial, 'human');
  assert.deepEqual(s.judges, JSON.parse(shippedBefore).judges, 'untouched settings come from the shipped file');
  // saveSettings keeps only differences; undefined resets to the shipped default.
  config.saveSettings({ ...s, gradingOfficial: undefined });
  const after2 = JSON.parse(readFileSync(paths.USER_SETTINGS_FILE, 'utf8'));
  assert.equal(after2.gradingOfficial, undefined);
  assert.equal(after2.judges, undefined, 'unchanged shipped values are not copied into the overlay');
  assert.equal(after2.currency.code, 'EUR');
  config.updateSettings({ currency: undefined, budget: undefined });
  assert.deepEqual(JSON.parse(readFileSync(paths.USER_SETTINGS_FILE, 'utf8')), {});
  // A broken overlay falls back to the defaults instead of crashing.
  writeFileSync(paths.USER_SETTINGS_FILE, '{ not json');
  const warn = console.warn;
  console.warn = () => {};
  try {
    assert.equal(config.loadSettings().currency?.code, JSON.parse(shippedBefore).currency.code);
  } finally {
    console.warn = warn;
  }
  writeFileSync(paths.USER_SETTINGS_FILE, '{}');
});

test('pasting an OpenRouter key: detected by shape, checked for free, saved, model list fetched', async () => {
  const providers = config.loadProviders();
  const bad = await pasteOne({ line: 1, key: 'sk-or-v1-bad' }, undefined, providers);
  assert.equal(bad.providerId, 'openrouter');
  assert.equal(bad.saved, false);
  assert.match(bad.error ?? '', /rejected/);
  assert.ok(!existsSync(keys.ENV_FILE) || !readFileSync(keys.ENV_FILE, 'utf8').includes('sk-or-v1-bad'));

  const r = await pasteOne({ line: 1, key: 'sk-or-v1-good0123456789' }, undefined, providers);
  assert.equal(r.providerId, 'openrouter');
  assert.equal(r.how, 'shape');
  assert.ok(r.ok && r.saved);
  assert.equal(r.masked.includes('good0123456789'), false, 'the full key is never sent back');
  assert.ok(r.ready.some((m) => m.id === 'fake-model-1-5'));
  assert.match(readFileSync(keys.ENV_FILE, 'utf8'), /OPENROUTER_API_KEY=sk-or-v1-good0123456789/);
  assert.equal(openrouter.loadCatalog()?.models.length, 2);
  assert.ok(seen.some((s) => s.path.endsWith('/models') && s.auth === 'Bearer sk-or-v1-good0123456789'));
});

test('pasteOne: unknown shapes ask which company; ambiguous shapes are told apart by the free check', async () => {
  const providers = config.loadProviders();
  const unknown = await pasteOne({ line: 1, key: 'weird_1234567890abcdef' }, undefined, providers);
  assert.equal(unknown.needsChoice, true);
  assert.equal(unknown.saved, false);
  // "sk-" + 32 hex: DeepSeek first; if DeepSeek rejects it and OpenAI accepts, it is saved as OpenAI.
  const tried: string[] = [];
  const check = async (id: string) => (tried.push(id), id === 'openai' ? { ok: true, models: 3 } : { ok: false, rejected: true, error: 'rejected' });
  const saveEnv = process.env.OPENAI_API_KEY;
  const r = await pasteOne({ line: 1, key: 'sk-0123456789abcdef0123456789abcdef' }, undefined, providers, check);
  assert.deepEqual(tried, ['deepseek', 'openai']);
  assert.equal(r.providerId, 'openai');
  assert.ok(r.saved);
  keys.removeKey('OPENAI_API_KEY');
  if (saveEnv) process.env.OPENAI_API_KEY = saveEnv;
  // A NAME= line wins over the shape.
  const named = await pasteOne({ line: 1, key: 'AbCdEfGhIjKlMnOpQrStUvWxYz012345', envName: 'MISTRAL_API_KEY' }, undefined, providers, async () => ({ ok: true, models: 1 }));
  assert.equal(named.how, 'name');
  assert.equal(named.providerId, 'mistral');
  keys.removeKey('MISTRAL_API_KEY');
});

test('routing: with only an OpenRouter key, the model shows as ready "via OpenRouter"; off switches it off', () => {
  const c = config.getContestant('fake-model-1-5');
  const view = config.toView(c);
  assert.equal(view.hasKey, true);
  assert.equal(view.via, 'openrouter');
  assert.equal(view.viaModel, 'fakeco/fake-model-1.5');
  config.updateSettings({ openrouterRouting: 'off' });
  assert.equal(config.toView(c).hasKey, false);
  config.updateSettings({ openrouterRouting: undefined });
  // A direct key always wins.
  process.env.FAKE_DIRECT_KEY = 'direct';
  assert.equal(openrouter.routeFor(c), null);
  delete process.env.FAKE_DIRECT_KEY;
  const st = openrouter.routingStatus([c]);
  assert.deepEqual(st.rows.map((r) => [r.id, r.slug, r.routed, r.openRouterPrice?.inputPerM]), [['fake-model-1-5', 'fakeco/fake-model-1.5', true, 3]]);
});

test('a run goes to OpenRouter with the right slug; the manifest records the route; the hash differs from direct', async () => {
  seen.length = 0;
  const direct = config.getContestant('fake-model-1-5');
  const plan = runner.planRun({ contestantIds: ['fake-model-1-5'], testIds: ['math.tiny'], repeats: 1 });
  assert.ok(plan.warnings.some((w) => /through OpenRouter/.test(w)));
  const id = await runner.startRun({ contestantIds: ['fake-model-1-5'], testIds: ['math.tiny'], repeats: 1 });
  await runner.waitForRun(id);
  const chat = seen.find((s) => s.path.endsWith('/chat/completions'));
  assert.ok(chat, 'the model was called through OpenRouter');
  assert.equal(chat.body.model, 'fakeco/fake-model-1.5');
  assert.equal(chat.auth, 'Bearer sk-or-v1-good0123456789');
  assert.deepEqual(chat.body.reasoning, { effort: 'high' }, 'effort passes as OpenRouter’s unified reasoning parameter');
  assert.equal(chat.body.reasoning_effort, undefined);

  const m = store.readManifest(id)!;
  assert.equal(m.status, 'completed');
  const snap = m.contestants[0]!;
  assert.equal(snap.provider, 'openrouter');
  assert.deepEqual(snap.route, { via: 'openrouter', provider: 'fakedirect', model: 'fake-model-1-5', slug: 'fakeco/fake-model-1.5', directHash: config.contestantConfigHash(direct) });
  assert.notEqual(snap.configHash, config.contestantConfigHash(direct));
  assert.equal(snap.pricing.inputPerM, 3, 'OpenRouter’s listed price is used for routed models');
  const results = store.readResults(id);
  assert.equal(results.length, 1);
  assert.equal(results[0]!.contestantHash, snap.configHash);
  assert.equal(results[0]!.score, 1);
  // Same model, same route, different hash from a hypothetical direct result: they can never pool.
  assert.notEqual(config.contestantConfigHash({ ...direct }), config.contestantConfigHash({ ...direct, route: snap.route }));
});
