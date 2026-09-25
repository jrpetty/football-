import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const dir = mkdtempSync(join(tmpdir(), 'gauntlet-keys-'));
process.env.GAUNTLET_ENV_FILE = join(dir, '.env');
const keys = await import('../src/core/keys.ts');

test('saveKey writes .env, keeps comments and other keys, replaces duplicates, applies to process.env', () => {
  writeFileSync(keys.ENV_FILE, '# my notes\nOTHER=1\nTEST_API_KEY=old\nTEST_API_KEY=older\n');
  keys.saveKey('TEST_API_KEY', 'sk-new-123456');
  const text = readFileSync(keys.ENV_FILE, 'utf8');
  assert.equal(text, '# my notes\nOTHER=1\nTEST_API_KEY=sk-new-123456\n');
  assert.equal(process.env.TEST_API_KEY, 'sk-new-123456');
  assert.equal(keys.envFileValue('TEST_API_KEY'), 'sk-new-123456');
});

test('saveKey creates the file when missing; removeKey deletes only that key', () => {
  const f = keys.ENV_FILE;
  writeFileSync(f, '');
  keys.saveKey('A_API_KEY', 'aaaaaaaaaa');
  keys.saveKey('B_API_KEY', 'bbbbbbbbbb');
  keys.removeKey('A_API_KEY');
  const text = readFileSync(f, 'utf8');
  assert.ok(!text.includes('A_API_KEY'));
  assert.ok(text.includes('B_API_KEY=bbbbbbbbbb'));
  assert.equal(process.env.A_API_KEY, undefined);
  assert.ok(existsSync(f));
});

test('removeKey leaves a key that came from the system environment alone', () => {
  process.env.SYS_API_KEY = 'from-system-123';
  keys.removeKey('SYS_API_KEY');
  assert.equal(process.env.SYS_API_KEY, 'from-system-123');
  delete process.env.SYS_API_KEY;
});

test('cleanKey accepts pasted variants and rejects junk with plain-English errors', () => {
  assert.equal(keys.cleanKey('  sk-ant-abc123456  ').key, 'sk-ant-abc123456');
  assert.equal(keys.cleanKey('ANTHROPIC_API_KEY=sk-ant-abc123456').key, 'sk-ant-abc123456');
  assert.equal(keys.cleanKey('"sk-abc12345678"').key, 'sk-abc12345678');
  assert.equal(keys.cleanKey('AIzaSyD-abcdefgh=').key, 'AIzaSyD-abcdefgh=', 'a trailing = in a key is kept');
  assert.match(keys.cleanKey('').error!, /Paste/);
  assert.match(keys.cleanKey('sk-abc 123456').error!, /space/);
  assert.match(keys.cleanKey('short').error!, /too short/);
  assert.match(keys.cleanKey(undefined).error!, /Paste/);
});

test('maskKey never reveals more than a short head and the last 4 characters', () => {
  const k = 'sk-ant-api03-SECRETSECRETSECRET-9f2c';
  const m = keys.maskKey(k);
  assert.ok(m.endsWith('9f2c'));
  assert.ok(!m.includes('SECRET'));
  assert.ok(m.length < 16);
  assert.equal(keys.maskKey('abcdefgh'), '••••••••');
});

test('keyFormatWarning spots a key pasted into the wrong provider', () => {
  assert.equal(keys.keyFormatWarning('anthropic', 'sk-ant-xyz12345'), undefined);
  assert.match(keys.keyFormatWarning('google', 'sk-ant-xyz12345')!, /anthropic key/);
  assert.match(keys.keyFormatWarning('anthropic', 'zzzzzzzzzzzz')!, /usually start/);
  assert.equal(keys.keyFormatWarning('mistral', 'anything123'), undefined);
});

test('explainKeyError turns provider errors into actionable advice', () => {
  assert.match(keys.explainKeyError('401 {"error":{"type":"authentication_error","message":"invalid x-api-key"}}'), /rejected/);
  assert.match(keys.explainKeyError('429 You exceeded your current quota, please check your plan and billing details'), /credit/);
  assert.match(keys.explainKeyError('fetch failed: getaddrinfo ENOTFOUND api.openai.com'), /internet/);
  assert.match(keys.explainKeyError('429 Rate limit reached'), /rate-limiting/);
});

test('keyStatus reports source and a masked hint, never the key', () => {
  keys.saveKey('STATUS_API_KEY', 'sk-status-SECRET-7777');
  const s = keys.keyStatus({ id: 'anthropic', type: 'anthropic', label: 'Anthropic', apiKeyEnv: 'STATUS_API_KEY' }, [{ id: 'm', label: 'M' }])!;
  assert.equal(s.set, true);
  assert.equal(s.source, 'file');
  assert.ok(!JSON.stringify(s).includes('SECRET'));
  assert.match(s.getKeyUrl!, /anthropic/);
  process.env.STATUS_API_KEY = 'sk-other-value-8888';
  assert.equal(keys.keyStatus({ id: 'x', type: 'anthropic', label: 'X', apiKeyEnv: 'STATUS_API_KEY' }, [])!.source, 'system');
  assert.equal(keys.keyStatus({ id: 'x', type: 'mock', label: 'X', apiKeyEnv: null }, []), null);
});

test('isRejection separates a refused key from billing, rate-limit and network problems', () => {
  assert.equal(keys.isRejection('401 Incorrect API key provided'), true);
  assert.equal(keys.isRejection('400 API key not valid. Please pass a valid API key.'), true);
  assert.equal(keys.isRejection('429 You exceeded your current quota'), false);
  assert.equal(keys.isRejection('fetch failed'), false);
});
