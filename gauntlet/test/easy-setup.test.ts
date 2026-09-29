/**
 * Easy setup: "paste any key" detection, pasted-text parsing, the per-user folder (and test isolation),
 * the one-time migration from an old in-folder install, and OpenRouter model matching.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { detectProvider, parsePastedKeys } from '../src/core/key-detect.ts';
import { KEY_GUIDES } from '../src/core/key-guides.ts';
import { APP_ROOT, USER_DIR, defaultUserDir, isPortable, resolveUserDir } from '../src/core/userdir.ts';
import { MIGRATION_MARKER, likelyOldFolders, migrateToUserDir, settingsDifferences } from '../src/core/migrate.ts';
import { matchSlug, normalizeModelName, openRouterPricing, type OpenRouterCatalog } from '../src/core/openrouter.ts';
import type { Contestant } from '../src/core/types.ts';

// ───────────── provider detection ─────────────

test('detectProvider: unique prefixes are sure', () => {
  const cases: Array<[string, string]> = [
    ['sk-ant-api03-AbCdEf1234567890abcdef', 'anthropic'],
    ['sk-or-v1-0123456789abcdef0123456789abcdef', 'openrouter'],
    ['sk-proj-AbCdEfGh1234567890', 'openai'],
    ['sk-svcacct-AbCdEfGh1234567890', 'openai'],
    ['xai-AbCdEf1234567890', 'xai'],
    ['gsk_AbCdEf1234567890', 'groq'],
    ['AIzaSyA1234567890abcdefghijklmnopqrstu', 'google'],
    ['tgp_v1_AbCdEf1234567890', 'together'],
  ];
  for (const [key, id] of cases) {
    const d = detectProvider(key);
    assert.equal(d.providerId, id, key);
    assert.equal(d.confidence, 'sure', key);
  }
});

test('detectProvider: OpenRouter and Anthropic keys are never mistaken for OpenAI (all start with sk-)', () => {
  assert.equal(detectProvider('sk-or-v1-abc123abc123abc123').providerId, 'openrouter');
  assert.equal(detectProvider('sk-ant-api03-abc123abc123').providerId, 'anthropic');
});

test('detectProvider: shared "sk-" shapes are likely, with both companies to try', () => {
  const ds = detectProvider('sk-0123456789abcdef0123456789abcdef'); // DeepSeek: sk- + 32 hex
  assert.equal(ds.confidence, 'likely');
  assert.deepEqual(ds.candidates, ['deepseek', 'openai']);
  const legacy = detectProvider('sk-AbCdEfGhIjKlMnOpQrStUvWxYz0123456789AbCdEfGhIjKl'); // old OpenAI
  assert.deepEqual(legacy.candidates, ['openai', 'deepseek']);
  assert.deepEqual(detectProvider('AbCdEfGhIjKlMnOpQrStUvWxYz012345').candidates, ['mistral']);
  assert.deepEqual(detectProvider('a'.repeat(64)).candidates, ['together']);
});

test('detectProvider: admin keys get a plain-English note; unknown shapes ask', () => {
  assert.match(detectProvider('sk-ant-admin01-abcdefabcdef').note ?? '', /Admin key/);
  assert.match(detectProvider('sk-admin-abcdefabcdefabcdef').note ?? '', /Admin key/);
  const u = detectProvider('hf_totallyUnknownShape123');
  assert.equal(u.providerId, null);
  assert.equal(u.confidence, 'unknown');
  assert.deepEqual(u.candidates, []);
});

test('parsePastedKeys: bare keys, .env lines, quotes, comments, several at once', () => {
  const text = [
    '# my keys',
    'ANTHROPIC_API_KEY=sk-ant-api03-aaaaaaaaaaaa',
    'export OPENAI_API_KEY="sk-proj-bbbbbbbbbbbb"',
    '',
    '  AIzaSyCcccccccccccccccccccccccccccccccc  ',
    "GEMINI_API_KEY='AIzaSyDddddddddddddddddddddddddddddddd' # comment",
    'Anthropic: sk-ant-api03-eeeeeeeeeeee',
    'short',
  ].join('\r\n');
  const got = parsePastedKeys(text);
  assert.deepEqual(got, [
    { line: 2, key: 'sk-ant-api03-aaaaaaaaaaaa', envName: 'ANTHROPIC_API_KEY' },
    { line: 3, key: 'sk-proj-bbbbbbbbbbbb', envName: 'OPENAI_API_KEY' },
    { line: 5, key: 'AIzaSyCcccccccccccccccccccccccccccccccc' },
    { line: 6, key: 'AIzaSyDddddddddddddddddddddddddddddddd', envName: 'GEMINI_API_KEY' },
    { line: 7, key: 'sk-ant-api03-eeeeeeeeeeee' },
  ]);
  assert.deepEqual(parsePastedKeys(''), []);
  assert.deepEqual(parsePastedKeys('   \n# only a comment'), []);
});

test('every guide has a key page, numbered steps and a note about credit', () => {
  for (const g of KEY_GUIDES) {
    assert.match(g.url, /^https:\/\//, g.providerId);
    assert.ok(g.steps.length >= 2, g.providerId);
    assert.ok(g.credit.length > 10, g.providerId);
  }
  assert.equal(KEY_GUIDES[0]!.providerId, 'openrouter');
  assert.ok(KEY_GUIDES[0]!.recommended);
});

// ───────────── the user folder ─────────────

test('the test run is isolated: portable under node --test, so keys/runs/settings stay in the app folder', () => {
  assert.ok(isPortable());
  assert.equal(USER_DIR, APP_ROOT);
  assert.notEqual(USER_DIR, defaultUserDir());
});

test('user folder: %APPDATA%\\Gauntlet on Windows, ~/.gauntlet elsewhere; env vars and portable mode override', () => {
  assert.equal(defaultUserDir({ APPDATA: 'C:\\Users\\jr\\AppData\\Roaming' }, 'win32', 'C:\\Users\\jr'), join('C:\\Users\\jr\\AppData\\Roaming', 'Gauntlet'));
  assert.equal(defaultUserDir({}, 'win32', '/h'), join('/h', 'AppData', 'Roaming', 'Gauntlet'));
  assert.equal(defaultUserDir({}, 'darwin', '/Users/jr'), join('/Users/jr', '.gauntlet'));
  assert.equal(resolveUserDir({}, 'linux', '/home/jr'), join('/home/jr', '.gauntlet'));
  assert.equal(resolveUserDir({ GAUNTLET_PORTABLE: '1' }, 'linux', '/home/jr'), APP_ROOT);
  assert.equal(resolveUserDir({ NODE_ENV: 'test' }, 'linux', '/home/jr'), APP_ROOT);
  assert.equal(resolveUserDir({ NODE_TEST_CONTEXT: 'child-v8' }, 'linux', '/home/jr'), APP_ROOT);
  assert.equal(resolveUserDir({ GAUNTLET_USER_DIR: '/tmp/x', NODE_TEST_CONTEXT: 'child' }, 'linux', '/home/jr'), '/tmp/x');
});

test('likelyOldFolders: the old shortcut folder first, then Desktop / OneDrive Desktop / Downloads', () => {
  const list = likelyOldFolders('/home/jr', { GAUNTLET_OLD_FOLDER: '/old/place' });
  assert.equal(list[0], '/old/place');
  assert.ok(list.includes(join('/home/jr', 'Desktop', 'gauntlet')));
  assert.ok(list.includes(join('/home/jr', 'OneDrive', 'Desktop', 'gauntlet')));
  assert.ok(list.includes(join('/home/jr', 'Downloads', 'gauntlet', 'gauntlet')));
});

// ───────────── migration ─────────────

function fakeOldInstall(root: string, settings: Record<string, unknown>): void {
  mkdirSync(join(root, 'src'), { recursive: true });
  mkdirSync(join(root, 'config'), { recursive: true });
  writeFileSync(join(root, 'src', 'cli.ts'), '// old');
  writeFileSync(join(root, 'config', 'settings.json'), JSON.stringify(settings));
  writeFileSync(join(root, '.env'), 'ANTHROPIC_API_KEY=sk-ant-old\n');
  mkdirSync(join(root, 'data', 'runs', 'run-1', 'artifacts'), { recursive: true });
  writeFileSync(join(root, 'data', 'runs', 'run-1', 'manifest.json'), '{"id":"run-1"}');
  writeFileSync(join(root, 'data', 'runs', 'run-1', 'artifacts', 'a.txt'), 'hello');
}

test('settingsDifferences: only the settings the owner changed, not shipped-default drift', () => {
  const shipped = { judges: ['new-judge'], currency: { code: 'GBP', usdPerUnit: 1.33, rateDate: '2026-09-28' } };
  assert.deepEqual(settingsDifferences({ judges: ['old-judge'], currency: { code: 'GBP', usdPerUnit: 1.33, rateDate: '2026-01-01' } }, shipped), {});
  assert.deepEqual(settingsDifferences({ currency: { code: 'EUR', usdPerUnit: 1.1, rateDate: '2026-01-01' }, budget: { monthlyUsd: 20 }, gradingOfficial: 'human' }, shipped), {
    currency: { code: 'EUR', usdPerUnit: 1.1, rateDate: '2026-01-01' },
    budget: { monthlyUsd: 20 },
    gradingOfficial: 'human',
  });
});

test('migrateToUserDir copies keys, runs and changed settings once, never deleting the originals', () => {
  const tmp = mkdtempSync(join(tmpdir(), 'gauntlet-migrate-'));
  const app = join(tmp, 'new-app');
  const old = join(tmp, 'Desktop', 'gauntlet');
  const user = join(tmp, 'user');
  mkdirSync(join(app, 'config'), { recursive: true });
  mkdirSync(join(app, 'src'), { recursive: true });
  writeFileSync(join(app, 'src', 'cli.ts'), '// new');
  writeFileSync(join(app, 'config', 'settings.json'), JSON.stringify({ judges: ['a'], currency: { code: 'GBP', usdPerUnit: 1.33 } }));
  fakeOldInstall(old, { judges: ['a'], currency: { code: 'GBP', usdPerUnit: 1.33 }, budget: { monthlyUsd: 30, hardStop: true } });
  const paths = {
    userDir: user,
    appRoot: app,
    envFile: join(user, '.env'),
    dataDir: join(user, 'data'),
    userSettingsFile: join(user, 'settings.json'),
    shippedSettingsFile: join(app, 'config', 'settings.json'),
    candidates: [join(tmp, 'missing'), old],
  };
  const done = migrateToUserDir(paths);
  assert.equal(done.length, 3, done.join('\n'));
  assert.equal(readFileSync(join(user, '.env'), 'utf8'), 'ANTHROPIC_API_KEY=sk-ant-old\n');
  assert.equal(readFileSync(join(user, 'data', 'runs', 'run-1', 'artifacts', 'a.txt'), 'utf8'), 'hello');
  assert.deepEqual(JSON.parse(readFileSync(join(user, 'settings.json'), 'utf8')), { budget: { monthlyUsd: 30, hardStop: true } });
  assert.ok(existsSync(join(user, MIGRATION_MARKER)));
  assert.match(readFileSync(join(user, 'migration-log.txt'), 'utf8'), /API keys copied from/);
  // Originals untouched.
  assert.ok(existsSync(join(old, '.env')));
  assert.ok(existsSync(join(old, 'data', 'runs', 'run-1', 'manifest.json')));
  // Only once.
  writeFileSync(join(user, '.env'), 'OPENAI_API_KEY=sk-proj-new\n');
  assert.deepEqual(migrateToUserDir(paths), []);
  assert.equal(readFileSync(join(user, '.env'), 'utf8'), 'OPENAI_API_KEY=sk-proj-new\n');
});

test('migrateToUserDir: unzipped over the old folder (keys and runs still in the app folder) → copied from there', () => {
  const tmp = mkdtempSync(join(tmpdir(), 'gauntlet-migrate-'));
  const app = join(tmp, 'gauntlet');
  const user = join(tmp, 'user');
  fakeOldInstall(app, { judges: ['a'] });
  const done = migrateToUserDir({ userDir: user, appRoot: app, envFile: join(user, '.env'), dataDir: join(user, 'data'), userSettingsFile: join(user, 'settings.json'), shippedSettingsFile: join(app, 'config', 'settings.json') });
  assert.equal(done.length, 2); // keys + runs; the settings match the shipped ones
  assert.ok(existsSync(join(user, '.env')));
  assert.ok(!existsSync(join(user, 'settings.json')));
});

test('migrateToUserDir: nothing old anywhere → nothing copied, marker written, env-var locations skipped', () => {
  const tmp = mkdtempSync(join(tmpdir(), 'gauntlet-migrate-'));
  const app = join(tmp, 'gauntlet');
  fakeOldInstall(app, {});
  const user = join(tmp, 'user');
  const done = migrateToUserDir({ userDir: user, appRoot: app, envFile: join(user, '.env'), dataDir: join(user, 'data'), userSettingsFile: join(user, 'settings.json'), shippedSettingsFile: join(app, 'config', 'settings.json'), skip: { env: true, data: true, settings: true } });
  assert.deepEqual(done, []);
  assert.ok(existsSync(join(user, MIGRATION_MARKER)));
  assert.ok(!existsSync(join(user, '.env')));
});

// ───────────── OpenRouter matching ─────────────

const CATALOG: OpenRouterCatalog = {
  fetchedAt: '2026-09-29T10:00:00.000Z',
  models: [
    { id: 'anthropic/claude-opus-4.6', name: 'Anthropic: Claude Opus 4.6', pricing: { prompt: '0.000005', completion: '0.000025', input_cache_read: '0.0000005' } },
    { id: 'anthropic/claude-opus-5.5', name: 'Anthropic: Claude Opus 5.5', pricing: { prompt: '0.000004', completion: '0.00002' } },
    { id: 'anthropic/claude-haiku-4.5:thinking', name: 'Anthropic: Claude Haiku 4.5 (thinking)' },
    { id: 'google/gemini-3.1-pro-preview', name: 'Google: Gemini 3.1 Pro Preview', pricing: { prompt: '0.000002', completion: '0.000012' } },
    { id: 'openai/gpt-5-mini', name: 'OpenAI: GPT-5 Mini', pricing: { prompt: '0.00000025', completion: '0.000002' } },
    { id: 'x-ai/grok-4.7', name: 'xAI: Grok 4.7' },
    { id: 'deepseek/deepseek-v4-flash:free', name: 'DeepSeek: V4 Flash (free)' },
    { id: 'someone-else/claude-opus-4.6', name: 'A copy' },
  ],
};

const model = (over: Partial<Contestant>): Contestant => ({ id: 'x', label: 'X', vendor: 'Anthropic', provider: 'anthropic', model: 'x', color: '#000000', enabled: true, pricing: { inputPerM: 1, outputPerM: 1 }, ...over });

test('normalizeModelName: dashes between version digits become dots, vendor prefixes drop', () => {
  assert.equal(normalizeModelName('claude-opus-4-6'), 'claude-opus-4.6');
  assert.equal(normalizeModelName('anthropic/claude-opus-4.6'), 'claude-opus-4.6');
  assert.equal(normalizeModelName('claude-3-5-sonnet-latest'), 'claude-3.5-sonnet');
  assert.equal(normalizeModelName('gpt-5-mini'), 'gpt-5-mini');
});

test('matchSlug: vendor + name, preview suffixes, no guessing between near names', () => {
  assert.equal(matchSlug(model({ id: 'claude-opus-4-6', model: 'claude-opus-4-6' }), CATALOG).slug, 'anthropic/claude-opus-4.6');
  assert.equal(matchSlug(model({ id: 'claude-opus-5-5', model: 'claude-opus-5-5' }), CATALOG).slug, 'anthropic/claude-opus-5.5');
  assert.equal(matchSlug(model({ id: 'gemini-3.1-pro', vendor: 'Google', provider: 'google', model: 'gemini-3.1-pro-preview' }), CATALOG).slug, 'google/gemini-3.1-pro-preview');
  assert.equal(matchSlug(model({ id: 'gpt-5-mini', vendor: 'OpenAI', model: 'gpt-5-mini' }), CATALOG).slug, 'openai/gpt-5-mini');
  assert.equal(matchSlug(model({ id: 'grok-4.7', vendor: 'xAI', model: 'grok-4.7' }), CATALOG).slug, 'x-ai/grok-4.7');
  // Near misses never match: Opus 5 is not Opus 5.5; ":thinking" / ":free" variants are skipped.
  assert.equal(matchSlug(model({ id: 'claude-opus-5', model: 'claude-opus-5' }), CATALOG).slug, null);
  assert.equal(matchSlug(model({ id: 'claude-haiku-4-5', model: 'claude-haiku-4-5' }), CATALOG).slug, null);
  assert.equal(matchSlug(model({ id: 'deepseek-v4-flash', vendor: 'DeepSeek', model: 'deepseek-v4-flash' }), CATALOG).slug, null);
  // Unknown vendor: nothing.
  assert.equal(matchSlug(model({ vendor: 'NoSuchCo', model: 'claude-opus-4-6' }), CATALOG).slug, null);
});

test('matchSlug: display-name fallback and explicit overrides', () => {
  assert.equal(matchSlug(model({ id: 'opus-alias', label: 'Claude Opus 4.6', model: 'opus-alias' }), CATALOG).slug, 'anthropic/claude-opus-4.6');
  const o = matchSlug(model({ id: 'anything', model: 'anything', openrouterModel: 'x-ai/grok-4.7' }), CATALOG);
  assert.deepEqual([o.slug, o.source], ['x-ai/grok-4.7', 'override']);
  // An override that isn't in the fetched list is reported as unavailable; with no list yet it is trusted.
  assert.equal(matchSlug(model({ openrouterModel: 'anthropic/gone' }), CATALOG).slug, null);
  assert.equal(matchSlug(model({ openrouterModel: 'anthropic/gone' }), null).slug, 'anthropic/gone');
  assert.equal(matchSlug(model({ id: 'claude-opus-4-6', model: 'claude-opus-4-6' }), null).slug, null);
});

test('openRouterPricing: per-token strings become per-million prices, with the source noted', () => {
  const p = openRouterPricing(CATALOG.models[0], CATALOG.fetchedAt)!;
  assert.equal(p.inputPerM, 5);
  assert.equal(p.outputPerM, 25);
  assert.equal(p.cachedInputPerM, 0.5);
  assert.equal(p.verifiedAt, '2026-09-29');
  assert.match(p.source ?? '', /OpenRouter/);
  assert.equal(openRouterPricing(CATALOG.models[2], CATALOG.fetchedAt), undefined);
});
