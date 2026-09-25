import { existsSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { ROOT } from './paths.ts';
import type { ProviderConfig } from './types.ts';

/**
 * API keys, managed from the dashboard (API Keys page) or `node src/cli.ts keys`.
 *
 * Keys live in gauntlet/.env (git-ignored), one `NAME=value` line each, and are
 * applied to process.env immediately, so a saved key works without a restart.
 * The full key is never sent back to the browser: only a masked hint.
 */

export const ENV_FILE = process.env.GAUNTLET_ENV_FILE ?? join(ROOT, '.env');

/** Where to get a key, and what a key usually looks like (a hint, never a hard rule). */
export const KEY_HELP: Record<string, { url: string; prefix?: string; steps: string }> = {
  anthropic: { url: 'https://console.anthropic.com/settings/keys', prefix: 'sk-ant-', steps: 'Sign in → Settings → API Keys → Create Key. Add credit under Billing.' },
  openai: { url: 'https://platform.openai.com/api-keys', prefix: 'sk-', steps: 'Sign in → API keys → Create new secret key. Add credit under Billing.' },
  google: { url: 'https://aistudio.google.com/apikey', prefix: 'AIza', steps: 'Sign in with Google → Get API key → Create API key.' },
  xai: { url: 'https://console.x.ai', prefix: 'xai-', steps: 'Sign in → API Keys → Create API key. Add credit under Billing.' },
  deepseek: { url: 'https://platform.deepseek.com/api_keys', prefix: 'sk-', steps: 'Sign in → API keys → Create new API key. Top up under Billing.' },
  mistral: { url: 'https://console.mistral.ai/api-keys', steps: 'Sign in → API Keys → Create new key.' },
  openrouter: { url: 'https://openrouter.ai/keys', prefix: 'sk-or-', steps: 'Sign in → Keys → Create Key. One key reaches models from many companies.' },
  groq: { url: 'https://console.groq.com/keys', prefix: 'gsk_', steps: 'Sign in → API Keys → Create API Key.' },
  together: { url: 'https://api.together.ai/settings/api-keys', steps: 'Sign in → Settings → API Keys.' },
};

/** Parse .env text into ordered lines, keeping comments and blank lines as they are. */
function readLines(): string[] {
  if (!existsSync(ENV_FILE)) return [];
  return readFileSync(ENV_FILE, 'utf8').replace(/\r\n/g, '\n').split('\n');
}

function lineName(line: string): string | null {
  const m = /^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=/.exec(line);
  return m ? m[1]! : null;
}

function unquote(v: string): string {
  const t = v.trim();
  if ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith("'") && t.endsWith("'"))) return t.slice(1, -1);
  return t;
}

/** Value of NAME in the .env file (not process.env), or undefined. */
export function envFileValue(name: string): string | undefined {
  for (const line of readLines()) if (lineName(line) === name) return unquote(line.slice(line.indexOf('=') + 1));
  return undefined;
}

/** Write the file atomically (temp file + rename) so a crash never leaves a half-written .env. */
function writeLines(lines: string[]): void {
  while (lines.length && lines[lines.length - 1] === '') lines.pop();
  const tmp = `${ENV_FILE}.tmp-${process.pid}`;
  writeFileSync(tmp, lines.join('\n') + '\n', { encoding: 'utf8', mode: 0o600 });
  renameSync(tmp, ENV_FILE);
}

/** Check a pasted key: returns the cleaned key or an error message a non-programmer understands. */
export function cleanKey(raw: unknown): { key?: string; error?: string } {
  if (typeof raw !== 'string') return { error: 'Paste the key into the box first.' };
  let key = raw.trim();
  // People often paste the whole line from a .env file or a quoted value.
  const m = /^(?:[A-Z][A-Z0-9]*_[A-Z0-9_]*\s*=\s*)?["']?(.*?)["']?$/.exec(key);
  if (m) key = m[1]!.trim();
  if (!key) return { error: 'Paste the key into the box first.' };
  if (/\s/.test(key)) return { error: 'The key contains a space or line break. Copy it again with the copy button on the provider’s site.' };
  if (key.length < 8) return { error: 'That looks too short to be an API key.' };
  if (key.length > 500) return { error: 'That looks too long to be an API key.' };
  if (!/^[\x21-\x7e]+$/.test(key)) return { error: 'The key contains unusual characters. Copy it again with the copy button on the provider’s site.' };
  return { key };
}

/** A soft warning when a key doesn't look like this provider's usual format (e.g. an OpenAI key pasted into Gemini). */
export function keyFormatWarning(providerId: string, key: string): string | undefined {
  const prefix = KEY_HELP[providerId]?.prefix;
  if (!prefix || key.startsWith(prefix)) return undefined;
  for (const [id, h] of Object.entries(KEY_HELP)) {
    if (id !== providerId && h.prefix && h.prefix.length > 3 && key.startsWith(h.prefix)) return `This looks like a${/^[aeiou]/i.test(id) ? 'n' : ''} ${id} key, not a ${providerId} key.`;
  }
  return `Keys for this provider usually start with “${prefix}”. Saved anyway; press Test to check.`;
}

/** Save (or replace) NAME=key in .env and apply it to process.env right away. */
export function saveKey(name: string, key: string): void {
  if (!/^[A-Z_][A-Z0-9_]*$/.test(name)) throw new Error(`Invalid variable name ${name}`);
  const lines = readLines();
  const at = lines.findIndex((l) => lineName(l) === name);
  const line = `${name}=${key}`;
  if (at >= 0) {
    lines[at] = line;
    for (let i = lines.length - 1; i > at; i--) if (lineName(lines[i]!) === name) lines.splice(i, 1);
  } else {
    if (!lines.length) lines.push('# API keys for Gauntlet. Managed by the API Keys page; never share or commit this file.');
    lines.push(line);
  }
  writeLines(lines);
  process.env[name] = key;
}

/** Remove NAME from .env and from process.env (only the value that came from .env). */
export function removeKey(name: string): void {
  const fromFile = envFileValue(name);
  const lines = readLines().filter((l) => lineName(l) !== name);
  if (existsSync(ENV_FILE)) writeLines(lines);
  if (fromFile !== undefined && process.env[name] === fromFile) delete process.env[name];
}

/** "sk-ant-…a1b2": enough to recognise a key, useless to anyone who sees the screen. */
export function maskKey(key: string): string {
  if (key.length <= 10) return '•'.repeat(key.length);
  const head = key.slice(0, Math.min(7, Math.floor(key.length / 4)));
  return `${head}…${key.slice(-4)}`;
}

export interface KeyStatus {
  providerId: string;
  label: string;
  env: string;
  set: boolean;
  /** Where the key comes from: the .env file (editable here) or the system environment (set outside Gauntlet). */
  source: 'file' | 'system' | null;
  masked: string | null;
  getKeyUrl?: string;
  steps?: string;
  /** Enabled models that use this key. */
  models: Array<{ id: string; label: string }>;
}

export function keyStatus(p: ProviderConfig, models: Array<{ id: string; label: string }>): KeyStatus | null {
  if (!p.apiKeyEnv) return null;
  const value = process.env[p.apiKeyEnv];
  const fileValue = envFileValue(p.apiKeyEnv);
  const source = !value ? null : fileValue !== undefined && fileValue === value ? 'file' : 'system';
  const help = KEY_HELP[p.id];
  return { providerId: p.id, label: p.label, env: p.apiKeyEnv, set: Boolean(value), source, masked: value ? maskKey(value) : null, getKeyUrl: help?.url, steps: help?.steps, models };
}

/** True when the provider refused the key itself (wrong or revoked), as opposed to billing, rate limits or network trouble. */
export function isRejection(message: string): boolean {
  const m = message.toLowerCase();
  return /\b401\b|unauthori[sz]ed|invalid.{0,20}(api[ _-]?key|x-api-key|authentication)|incorrect api key|api key not valid/.test(m);
}

/** Turn a provider error into advice a non-programmer can act on. */
export function explainKeyError(message: string): string {
  const m = message.toLowerCase();
  if (isRejection(message)) return 'The provider rejected this key. Check you copied all of it, or create a new one.';
  if (/\b402\b|insufficient|quota|billing|credit|payment/.test(m)) return 'The key works but the account has no credit. Add a payment method or credit on the provider’s billing page.';
  if (/\b403\b|permission|forbidden/.test(m)) return 'The key is valid but not allowed to do this. Check the key’s permissions or project settings on the provider’s site.';
  if (/\b429\b|rate.?limit/.test(m)) return 'The key works but the provider is rate-limiting it right now. Try again in a minute.';
  if (/enotfound|econnrefused|etimedout|fetch failed|network|socket/.test(m)) return 'Couldn’t reach the provider. Check your internet connection (or firewall) and try again.';
  return message.length > 240 ? message.slice(0, 237) + '…' : message;
}
