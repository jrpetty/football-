import { test, after } from 'node:test';
import assert from 'node:assert/strict';
import { cpSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { createServer } from 'node:http';
import type { AddressInfo } from 'node:net';
import type { WizardIO } from '../src/channel/newmodel-cli.ts';

// ── A fake OpenAI-compatible API: model list + streaming chat that answers "What is N + N?" correctly ──
const chatRequests: string[] = [];
const fakeApi = createServer(async (req, res) => {
  let raw = '';
  for await (const c of req) raw += c;
  if (req.method === 'GET' && req.url?.endsWith('/models')) {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ object: 'list', data: [{ id: 'fake-1', object: 'model' }, { id: 'fake-6', object: 'model' }] }));
    return;
  }
  const body = JSON.parse(raw || '{}') as { model?: string; messages?: Array<{ content: string }> };
  chatRequests.push(body.model ?? '');
  const prompt = body.messages?.at(-1)?.content ?? '';
  const i = Number(prompt.match(/What is (\d+) \+/)?.[1] ?? 0);
  const text = /pong/.test(prompt) ? 'pong' : `FINAL ANSWER: ${2 * i}`;
  const chunk = (delta: unknown, finish: string | null = null) => `data: ${JSON.stringify({ id: 'x', object: 'chat.completion.chunk', created: 1, model: body.model, choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  res.write(chunk({ role: 'assistant', content: text }));
  res.write(chunk({}, 'stop'));
  res.write(`data: ${JSON.stringify({ id: 'x', object: 'chat.completion.chunk', created: 1, model: body.model, choices: [], usage: { prompt_tokens: 100, completion_tokens: 20 } })}\n\n`);
  res.end('data: [DONE]\n\n');
});
await new Promise<void>((r) => fakeApi.listen(0, '127.0.0.1', r));
after(() => fakeApi.close());
process.env.FAKE_API_KEY = 'k';

// ── Sandbox: config, a tiny test library (public, prompts-withheld, held-out) and suites ──
const sandbox = mkdtempSync(join(tmpdir(), 'gauntlet-channel-'));
process.env.GAUNTLET_TESTS_DIR = join(sandbox, 'tests');
process.env.GAUNTLET_DATA_DIR = join(sandbox, 'data');
process.env.GAUNTLET_SUITES_DIR = join(sandbox, 'suites');
process.env.GAUNTLET_CONFIG_DIR = join(sandbox, 'config');
process.env.GAUNTLET_NO_BROWSER = '1';
cpSync(new URL('../config', import.meta.url), join(sandbox, 'config'), { recursive: true });
{
  const file = join(sandbox, 'config', 'models.json');
  const models = JSON.parse(readFileSync(file, 'utf8'));
  models.providers.push({ id: 'fakeapi', type: 'openai-compatible', label: 'FakeAI', baseUrl: `http://127.0.0.1:${(fakeApi.address() as AddressInfo).port}/v1`, apiKeyEnv: 'FAKE_API_KEY', maxConcurrency: 4 });
  // A secret in model notes must never reach the website.
  models.contestants.find((c: { id: string }) => c.id === 'claude-opus-5-5').notes = 'SECRET-MODEL-NOTE';
  writeFileSync(file, JSON.stringify(models));
  writeFileSync(join(sandbox, 'config', 'site.json'), JSON.stringify({ channelName: 'Test Channel', youtubeUrl: 'https://youtube.com/@test', accentColor: '#FF0055', suites: ['core', 'frontier'], submissionFormUrl: 'https://forms.gle/abc', season: 'test-s1' }));
}
for (const d of ['math', 'reasoning', 'private']) mkdirSync(join(sandbox, 'tests', d), { recursive: true });
mkdirSync(join(sandbox, 'suites'), { recursive: true });
const arith = {
  kind: 'prompt', id: 'math.arith', version: '1.0.0', name: 'Arithmetic', category: 'math', description: 'Tiny arithmetic', difficulty: 'easy',
  scorer: { type: 'number' },
  cases: Array.from({ length: 4 }, (_, i) => ({ id: `c${i + 1}`, prompt: `What is ${i + 1} + ${i + 1}?`, expected: 2 * (i + 1), notes: 'SECRET-CASE-NOTE' })),
};
const withheld = {
  kind: 'prompt', id: 'math.withheld', version: '1.0.0', name: 'Withheld prompts', category: 'math', description: 'Prompts are not published', difficulty: 'medium', publishPrompts: false,
  scorer: { type: 'exact' },
  cases: [{ id: 'c1', prompt: 'WITHHELD-PROMPT-TEXT: what is the capital of France?', expected: 'SECRET-ANSWER-PARIS' }],
};
const heldOut = {
  kind: 'prompt', id: 'reasoning.secret', version: '1.0.0', name: 'SECRET-TEST-NAME', category: 'reasoning', description: 'SECRET-DESCRIPTION', difficulty: 'hard',
  scorer: { type: 'exact' },
  cases: [{ id: 'c1', prompt: 'SECRET-PROMPT-TEXT: name the colour of the sky', expected: 'SECRET-ANSWER-BLUE' }],
};
writeFileSync(join(sandbox, 'tests', 'math', 'arith.json'), JSON.stringify(arith));
writeFileSync(join(sandbox, 'tests', 'math', 'withheld.json'), JSON.stringify(withheld));
writeFileSync(join(sandbox, 'tests', 'private', 'secret.json'), JSON.stringify(heldOut));
const suite = (id: string, tests: Array<{ id: string; cases?: string[] }>, extra = {}) => writeFileSync(join(sandbox, 'suites', `${id}.json`), JSON.stringify({ id, version: '1.0.0', name: `Suite ${id}`, description: `The ${id} suite`, repeats: 1, tests, ...extra }));
suite('quick', [{ id: 'math.arith', cases: ['c1', 'c2'] }]);
suite('core', [{ id: 'math.arith' }, { id: 'math.withheld' }, { id: 'reasoning.secret' }]);
suite('frontier', [{ id: 'math.arith' }]);

const registry = await import('../src/core/registry.ts');
const config = await import('../src/core/config.ts');
const { writeSyntheticRun } = await import('./helpers/channel-fixtures.ts');
const siteExport = await import('../src/channel/site-export.ts');
const history = await import('../src/channel/history.ts');
const newmodel = await import('../src/channel/newmodel.ts');
const wizard = await import('../src/channel/newmodel-cli.ts');
const challenge = await import('../src/channel/challenge.ts');
const boards = await import('../src/engine/leaderboards.ts');

const skill = { 'claude-opus-5-5': 0.9, 'gpt-5': 0.6, 'gpt-4o': 0.3, 'claude-haiku-4-5': 0.5, 'random-baseline': 0.05 };
writeSyntheticRun({ runId: 'syn-core', suiteId: 'core', skill });
writeSyntheticRun({ runId: 'syn-frontier', suiteId: 'frontier', skill });

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((n) => (statSync(join(dir, n)).isDirectory() ? walk(join(dir, n)) : [join(dir, n)]));
}

// ───────────────────────────── Public website ─────────────────────────────

test('publishPrompts is not part of the test hash (toggling it keeps results valid)', () => {
  const def = structuredClone(arith) as Parameters<typeof registry.computeTestHash>[0];
  assert.equal(registry.computeTestHash(def), registry.computeTestHash({ ...def, publishPrompts: false }));
  assert.notEqual(registry.computeTestHash(def), registry.computeTestHash({ ...def, name: 'changed' }));
});

test('site export: pages, valid JSON, resolvable links, no private tests or answer keys', () => {
  const out = join(sandbox, 'site');
  const r = siteExport.exportSite({ outDir: out, zip: true });
  assert.equal(r.suites.length, 2);
  assert.equal(r.hiddenTests, 1);
  assert.equal(r.withheldPrompts, 1);
  const files = walk(out);
  const rel = files.map((f) => relative(out, f).split('\\').join('/'));
  for (const must of ['index.html', 'models.html', 'tests.html', 'history.html', 'methodology.html', 'challenge.html', 'assets/site.css', 'assets/site.js', 'data/site.json', 'models/claude-opus-5-5.html', 'tests/math.arith.html', 'tests/heldout-1.html', '.nojekyll'])
    assert.ok(rel.includes(must), `missing ${must}`);
  assert.ok(!rel.some((f) => f.includes('reasoning.secret')), 'no page is named after the held-out test');

  const forbidden = ['SECRET-', 'WITHHELD-PROMPT-TEXT', 'reasoning.secret', 'SECRET-ANSWER-KEY'];
  for (const f of files) {
    const text = readFileSync(f, 'utf8');
    for (const bad of forbidden) assert.ok(!text.includes(bad), `${relative(out, f)} leaks "${bad}"`);
    if (f.endsWith('.json')) assert.doesNotThrow(() => JSON.parse(text), `${f} is valid JSON`);
    if (f.endsWith('.html')) {
      assert.match(text, /^<!doctype html>/);
      for (const m of text.matchAll(/(?:href|src)="([^"]+)"/g)) {
        const link = m[1]!.replace(/&amp;/g, '&');
        if (/^(https?:|mailto:|#)/.test(link)) continue;
        const target = resolve(dirname(f), link.split('#')[0]!);
        assert.ok(existsSync(target), `${relative(out, f)} links to missing ${link}`);
      }
    }
  }
  // Public test pages do show the exact prompt (answers never).
  assert.match(readFileSync(join(out, 'tests', 'math.arith.html'), 'utf8'), /What is 1 \+ 1\?/);
  assert.match(readFileSync(join(out, 'tests', 'math.withheld.html'), 'utf8'), /not published/);
  assert.match(readFileSync(join(out, 'tests', 'heldout-1.html'), 'utf8'), /Held-out test 1/);
  const index = readFileSync(join(out, 'index.html'), 'utf8');
  assert.match(index, /Test Channel/);
  assert.match(index, /https:\/\/youtube\.com\/@test/);
  assert.match(readFileSync(join(out, 'assets', 'site.css'), 'utf8'), /--accent: #FF0055/);
  const data = JSON.parse(readFileSync(join(out, 'data', 'site.json'), 'utf8'));
  assert.ok(data.suites[0].leaderboard.rows.length >= 4);
  for (const row of data.suites[0].leaderboard.rows) for (const agg of Object.values(row.tests) as Array<{ summary?: string }>) assert.equal(agg.summary, undefined);

  // Zip: valid end-of-central-directory record with one entry per file.
  const zip = readFileSync(r.zipPath!);
  const eocd = zip.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
  assert.ok(eocd > 0);
  assert.equal(zip.readUInt16LE(eocd + 10), r.files);
});

test('site export: re-export removes stale pages; refuses to write into a foreign folder', () => {
  const out = join(sandbox, 'site2');
  siteExport.exportSite({ outDir: out });
  writeFileSync(join(out, 'models', 'stale-model.html'), 'old');
  const marker = JSON.parse(readFileSync(join(out, 'gauntlet-site.json'), 'utf8'));
  marker.files.push('models/stale-model.html');
  writeFileSync(join(out, 'gauntlet-site.json'), JSON.stringify(marker));
  siteExport.exportSite({ outDir: out });
  assert.ok(!existsSync(join(out, 'models', 'stale-model.html')));
  const foreign = join(sandbox, 'my-documents');
  mkdirSync(foreign);
  writeFileSync(join(foreign, 'thesis.docx'), 'precious');
  assert.throws(() => siteExport.exportSite({ outDir: foreign }), /not empty/);
  assert.equal(readFileSync(join(foreign, 'thesis.docx'), 'utf8'), 'precious');
});

// ───────────────────────────── History ─────────────────────────────

test('history: family lines by release date, biggest jumps, undated and no-result models', () => {
  const board = boards.combinedLeaderboard('core');
  const contestants = config.loadContestants();
  const h = history.buildHistory(board, contestants, { exclude: new Set(['random-baseline']) });
  const gpt = h.families.find((f) => f.family === 'GPT')!;
  assert.deepEqual(gpt.points.map((p) => p.contestantId), ['gpt-4o', 'gpt-5']);
  assert.ok(h.families.some((f) => f.family === 'Claude'));
  assert.ok(h.undated.some((p) => p.contestantId === 'claude-opus-5-5'), 'models with results but no date are listed separately');
  assert.ok(h.noResults.some((p) => p.contestantId === 'gemini-2.5-pro'), 'dated models without results are listed, not plotted');
  assert.ok(!h.families.flatMap((f) => f.points).some((p) => p.contestantId === 'random-baseline'));
  for (const j of h.jumps) assert.ok(j.delta > 0 && j.days >= 0);
  // Tier filter: only small models.
  const small = history.buildHistory(board, contestants, { tiers: ['small'] });
  assert.ok(small.families.flatMap((f) => f.points).every((p) => p.tier === 'small'));
  // Category metric and an empty board are handled.
  const cat = history.buildHistory(board, contestants, { metric: 'math' });
  assert.ok(cat.families.length > 0);
  const empty = history.buildHistory({ ...board, rows: [] }, contestants);
  assert.equal(empty.families.length, 0);
  assert.equal(empty.jumps.length, 0);
});

test('history: same-day siblings stay dots; the line and jumps follow the best model', () => {
  const board = boards.combinedLeaderboard('core');
  const cs = config.loadContestants().map((c) => (c.id === 'gpt-5' || c.id === 'gpt-4o' ? c : { ...c, releaseDate: undefined }));
  const withMini = cs.map((c) => (c.id === 'claude-haiku-4-5' ? { ...c, family: 'GPT', releaseDate: '2025-08-07' } : c));
  const h = history.buildHistory(board, withMini, {});
  const gpt = h.families.find((f) => f.family === 'GPT')!;
  assert.equal(gpt.points.length, 3);
  assert.equal(gpt.line.length, 2);
  assert.equal(h.jumps[0]!.from.contestantId, 'gpt-4o');
});

// ───────────────────────────── New Model Day ─────────────────────────────

const flags = { provider: 'fakeapi', model: 'fake-6', label: 'Fake 6', 'input-price': '2', 'output-price': '8', suites: 'quick' };
function scriptedIO(answers: { confirm: boolean }): WizardIO & { lines: string[] } {
  const lines: string[] = [];
  return { interactive: true, lines, ask: async (_q, def) => def ?? '', confirm: async () => answers.confirm, log: (l) => lines.push(l) };
}

test('newmodel: nothing is spent without confirmation (non-interactive, no --yes)', async () => {
  chatRequests.length = 0;
  const io = { interactive: false, ask: async (_q: string, d?: string) => d ?? '', confirm: async () => false, log: () => {} };
  const out = await wizard.runNewModelWizard({ ...flags }, io);
  assert.equal(chatRequests.length, 0, 'no model call was made');
  assert.deepEqual(out.runIds, []);
  assert.match(out.stopped!, /--yes/);
  const c = config.getContestant('fake-6');
  assert.equal(c.pricing.verifiedAt, null, 'wizard prices are unverified');
  assert.equal(c.family, 'Fake');
});

test('newmodel: declining the confirm prompt stops before any call', async () => {
  chatRequests.length = 0;
  const io = scriptedIO({ confirm: false });
  const out = await wizard.runNewModelWizard({ ...flags }, io);
  assert.equal(chatRequests.length, 0);
  assert.equal(out.runIds.length, 0);
});

test('newmodel: with --yes it discovers, pings, prices, runs under a cap and ranks the model', async () => {
  chatRequests.length = 0;
  const io = scriptedIO({ confirm: false });
  const out = await wizard.runNewModelWizard({ ...flags, yes: true, 'max-cost': '1' }, io);
  assert.equal(out.runIds.length, 1);
  assert.ok(chatRequests.length >= 3, 'ping + 2 quick cases');
  const text = io.lines.join('\n');
  assert.match(text, /is listed by FakeAI/);
  assert.match(text, /quick .*~ *\$/);
  assert.match(text, /hard spending cap \$1\.00/);
  const h = out.headlines[0]!;
  assert.equal(h.suiteId, 'quick');
  assert.equal(h.index, 100, 'the fake model answers every quick case correctly');
  assert.equal(h.rank, 1);
  assert.ok(h.titles.length >= 2);
  const none = newmodel.newModelHeadline('gpt-5-mini', 'core');
  assert.equal(none.rank, null, 'a model without results is handled gracefully');
  assert.match(none.headline, /no scored results/);
  // Its quick answers also count on the combined Core leaderboard (same test, same hash).
  assert.ok(newmodel.newModelHeadline('fake-6', 'core').rank !== null);
});

test('newmodel: prepare flags unknown model ids and refuses to overwrite a different model', async () => {
  const p = await newmodel.prepareNewModel({ provider: 'fakeapi', model: 'fake-66', inputPerM: 1, outputPerM: 1 });
  assert.equal(p.discovered, false);
  assert.ok(p.warnings.some((w) => /not in FakeAI/.test(w)));
  await assert.rejects(() => newmodel.prepareNewModel({ provider: 'fakeapi', model: 'other', id: 'fake-66', inputPerM: 1, outputPerM: 1 }), /different model/);
  await assert.rejects(() => newmodel.prepareNewModel({ provider: 'fakeapi', model: 'x' } as never), /price/);
  assert.equal(newmodel.slugId('anthropic/Claude Opus 6!'), 'claude-opus-6');
  assert.equal(newmodel.guessTier('GPT-6 mini'), 'small');
});

// ───────────────────────────── Viewer challenge ─────────────────────────────

const FORM_CSV = [
  'Timestamp,Your question,The correct answer,Answer type,Your name,YouTube handle,Notes (how do you know?),Credit me on screen?',
  '2026/09/20 10:00:00,"How many letters ""r"" are in the word ""strawberry""? Give a number.",3,Number,Ada,@ada,"Counted, twice",Yes',
  '2026/09/20 10:05:00,"Which is heavier?\nA) a kilo of feathers\nB) a kilo of steel\nC) neither",c,Multiple choice,Bob,bob_yt,,No',
  '2026/09/20 10:06:00,What is the capital of Australia? One word.,,Exact,Cy,@cy,,Yes',
  '2026/09/20 10:07:00,What is the capital of Australia? One word.,Canberra,Exact,Dee,@dee,,Yes',
  '2026/09/20 10:08:00,Name the largest planet.,Name the largest planet. Jupiter,Exact,Eve,@eve,,Yes',
  '2026/09/20 10:09:00,What is 1 + 1?,two,Number,Fay,@fay,,Yes',
].join('\r\n');

test('challenge: CSV parsing handles quotes, embedded newlines and CRLF', () => {
  const rows = challenge.parseCsv('a,b\r\n"x, y","line1\nline2"\r\n"say ""hi""",z\n');
  assert.deepEqual(rows, [['a', 'b'], ['x, y', 'line1\nline2'], ['say "hi"', 'z']]);
  assert.equal(challenge.mapHeader('Answer type'), 'answerType');
  assert.equal(challenge.mapHeader('The correct answer'), 'answer');
  assert.equal(challenge.mapHeader('YouTube handle'), 'handle');
  assert.equal(challenge.mapHeader('Your question'), 'question');
  assert.ok(challenge.similarity('What is the capital of Australia?', 'what is the capital city of australia') > 0.6);
  assert.ok(challenge.similarity('How many legs does a spider have?', 'Name the largest planet.') < 0.2);
});

test('challenge: import, checks, dedupe, review and the generated private test validates', () => {
  const r = challenge.importSubmissions('test-s1', FORM_CSV);
  assert.equal(r.added, 6);
  const again = challenge.importSubmissions('test-s1', FORM_CSV);
  assert.equal(again.added, 0, 're-importing the same export adds nothing');
  const items = r.queue.items;
  const by = (h: string) => items.find((i) => i.viewerHandle === h)!;
  assert.equal(by('@ada').answerType, 'number');
  assert.equal(by('@bob_yt').answerType, 'choice');
  assert.equal(by('@bob_yt').answer, 'C');
  assert.equal(by('@bob_yt').credit, false);
  assert.ok(by('@cy').issues.some((i) => i.code === 'missing-answer'));
  assert.ok(by('@dee').issues.some((i) => i.code === 'duplicate'));
  assert.ok(by('@eve').issues.some((i) => i.code === 'question-in-answer'));
  assert.ok(by('@fay').issues.some((i) => i.code === 'bad-number'));
  // Near-duplicate of an existing library test.
  const nd = challenge.importSubmissions('test-s1', JSON.stringify([{ question: 'What is 3 + 3?', answer: '6', answer_type: 'number', handle: 'gus' }]));
  assert.ok(nd.queue.items.find((i) => i.viewerHandle === '@gus')!.issues.some((i) => /math\.arith/.test(i.message)));

  assert.throws(() => challenge.updateItem('test-s1', by('@eve').id, { status: 'approved' }), /Fix these/);
  challenge.updateItem('test-s1', by('@eve').id, { answer: 'Jupiter', status: 'approved' });
  challenge.updateItem('test-s1', by('@ada').id, { status: 'approved' });
  challenge.updateItem('test-s1', by('@bob_yt').id, { status: 'approved' });
  challenge.updateItem('test-s1', by('@fay').id, { status: 'rejected' });

  const w = challenge.writeChallengeTest('test-s1');
  assert.deepEqual(w.errors, []);
  assert.equal(w.cases, 3);
  assert.equal(w.testId, 'reasoning.viewer-challenge-test-s1');
  const file = join(sandbox, 'tests', 'private', 'viewer-challenge-test-s1.json');
  const def = JSON.parse(readFileSync(file, 'utf8'));
  assert.deepEqual(registry.validateTest(def), []);
  assert.equal(def.publishPrompts, false);
  const ada = def.cases.find((c: { prompt: string }) => /strawberry/.test(c.prompt));
  assert.equal(ada.expected, 3);
  assert.deepEqual(ada.scorer, { type: 'number' });
  assert.match(ada.notes, /submitted by @ada \(Ada\)/);
  assert.equal(def.cases.find((c: { prompt: string }) => /heavier/.test(c.prompt)).expected, 'C');
  const loaded = registry.getTest(w.testId)!;
  assert.equal(loaded.source, 'private', 'lives in the git-ignored held-out folder');

  // Writing again without changes keeps the version; a change bumps it and keeps case ids stable.
  assert.equal(challenge.writeChallengeTest('test-s1').version, '1.0.0');
  challenge.updateItem('test-s1', by('@dee').id, { status: 'approved' });
  const w2 = challenge.writeChallengeTest('test-s1');
  assert.equal(w2.version, '1.0.1');
  const def2 = JSON.parse(readFileSync(file, 'utf8'));
  assert.deepEqual(def2.cases.slice(0, 3).map((c: { id: string }) => c.id), def.cases.map((c: { id: string }) => c.id));

  // Slides: approved items, credit only when the viewer agreed.
  const slides = challenge.challengeSlides('test-s1');
  assert.equal(slides.length, 4);
  assert.equal(slides.find((s) => /heavier/.test(s.question))!.credit, null);
  assert.equal(slides.find((s) => /strawberry/.test(s.question))!.credit, '@ada');
});

test('challenge: the private test is never published on the website', () => {
  const core = JSON.parse(readFileSync(join(sandbox, 'suites', 'core.json'), 'utf8'));
  core.tests.push({ id: 'reasoning.viewer-challenge-test-s1' });
  writeFileSync(join(sandbox, 'suites', 'core.json'), JSON.stringify(core));
  const { files } = siteExport.buildSiteFiles({ suites: ['core'] });
  for (const [name, content] of files) {
    const text = String(content);
    assert.ok(!/strawberry|viewer-challenge|@ada/.test(text), `${name} leaks the viewer challenge`);
  }
});

test('challenge: malformed input is reported, not thrown', () => {
  assert.match(challenge.parseSubmissions('not json [', 'json').errors[0]!, /JSON/);
  assert.match(challenge.parseSubmissions('foo,bar\n1,2').errors[0]!, /question column/);
  assert.match(challenge.parseSubmissions('').errors[0]!, /empty/);
  assert.throws(() => challenge.seasonSlug('!!!'), /Season/);
});
