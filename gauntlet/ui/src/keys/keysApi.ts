/**
 * Client for the API Keys page (/api/keys, docs/API.md → "API keys").
 * In mock mode (?mock=1) an in-memory stand-in answers, so the page can be demoed with no server.
 */
import { MOCK, request } from '../api.ts';
import { detectProvider, parsePastedKeys } from '../../../src/core/key-detect.ts';
import { KEY_GUIDES, type KeyGuide } from '../../../src/core/key-guides.ts';

export type { KeyGuide };

export interface KeyStatus {
  providerId: string;
  label: string;
  env: string;
  set: boolean;
  source: 'file' | 'system' | null;
  masked: string | null;
  getKeyUrl?: string;
  steps?: string;
  models: Array<{ id: string; label: string }>;
}
export interface KeyCheck {
  ok: boolean;
  models?: number;
  error?: string;
  /** Present when a test message was sent. */
  text?: string;
  model?: string;
  costUsd?: number;
  totalMs?: number;
}
export interface SaveResult {
  ok: boolean;
  /** false when the provider rejected the key (it was not saved). */
  saved?: boolean;
  warning?: string;
  check: KeyCheck;
  status: KeyStatus;
}

/** Where the owner's keys, runs and settings are kept (outside the app folder, so updates never delete them). */
export interface Storage {
  dir: string;
  envFile: string;
  dataDir: string;
  settingsFile: string;
  /** Portable mode: everything inside the app folder (GAUNTLET_PORTABLE=1). */
  portable: boolean;
  windows: boolean;
}
export interface SetupInfo {
  storage: Storage;
  guides: KeyGuide[];
  anyKey: boolean;
  keys: string[];
  /** Models that can run right now (own key or via OpenRouter). */
  ready?: number;
}
export interface PasteResult {
  line: number;
  masked: string;
  providerId?: string;
  label?: string;
  how: 'shape' | 'name' | 'chosen' | 'unknown';
  ok: boolean;
  saved: boolean;
  needsChoice?: boolean;
  error?: string;
  warning?: string;
  ready: Array<{ id: string; label: string; vendor: string }>;
  unchecked?: boolean;
}
export interface RouteRow {
  id: string;
  label: string;
  vendor: string;
  provider: string;
  providerLabel: string;
  directKey: boolean;
  slug: string | null;
  source: 'override' | 'matched' | 'none';
  routed: boolean;
  openRouterPrice?: { inputPerM: number; outputPerM: number };
  directPrice: { inputPerM: number; outputPerM: number };
}
export interface RoutingStatus {
  hasKey: boolean;
  setting: 'auto' | 'on' | 'off';
  active: boolean;
  catalogFetchedAt: string | null;
  catalogSize: number;
  rows: RouteRow[];
}

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE';

async function call<T>(method: Method, path: string, body?: unknown): Promise<T> {
  if (MOCK) return mockKeys(method, path, body) as T;
  return request<T>(method, path, body);
}

export const keysApi = {
  list: () => call<{ file: string; storage?: Storage; keys: KeyStatus[] }>('GET', '/api/keys'),
  setup: () => call<SetupInfo>('GET', '/api/setup'),
  /** Paste any key (or several, or .env lines): the company is detected, the key checked for free and saved. */
  paste: (text: string, provider?: string) => call<{ results: PasteResult[]; storage: Storage }>('POST', '/api/keys/paste', { text, ...(provider ? { provider } : {}) }),
  openrouter: () => call<RoutingStatus>('GET', '/api/openrouter'),
  openrouterRefresh: () => call<RoutingStatus>('POST', '/api/openrouter/refresh'),
  openrouterRouting: (setting: 'auto' | 'on' | 'off') => call<RoutingStatus>('PUT', '/api/openrouter/routing', { setting }),
  save: (provider: string, key: string) => call<SaveResult>('PUT', `/api/keys/${encodeURIComponent(provider)}`, { key }),
  remove: (provider: string) => call<{ ok: boolean; status: KeyStatus }>('DELETE', `/api/keys/${encodeURIComponent(provider)}`),
  test: (provider: string, send = false) => call<KeyCheck>('POST', `/api/keys/${encodeURIComponent(provider)}/test`, { send }),
};

// ── mock mode ────────────────────────────────────────────────────────────────

const MOCK_KEYS: KeyStatus[] = [
  { providerId: 'anthropic', label: 'Anthropic', env: 'ANTHROPIC_API_KEY', set: true, source: 'file', masked: 'sk-ant-…9f2c', getKeyUrl: 'https://console.anthropic.com/settings/keys', steps: 'Sign in → Settings → API Keys → Create Key. Add credit under Billing.', models: [{ id: 'claude-opus-5-5', label: 'Claude Opus 5.5' }, { id: 'claude-sonnet-5', label: 'Claude Sonnet 5' }, { id: 'claude-haiku-4-5', label: 'Claude Haiku 4.5' }] },
  { providerId: 'openai', label: 'OpenAI', env: 'OPENAI_API_KEY', set: true, source: 'file', masked: 'sk-proj…71ab', getKeyUrl: 'https://platform.openai.com/api-keys', steps: 'Sign in → API keys → Create new secret key. Add credit under Billing.', models: [{ id: 'gpt-5.6-sol', label: 'GPT-5.6 Sol' }, { id: 'gpt-5.6-terra', label: 'GPT-5.6 Terra' }, { id: 'gpt-5.6-luna', label: 'GPT-5.6 Luna' }] },
  { providerId: 'google', label: 'Google Gemini', env: 'GEMINI_API_KEY', set: false, source: null, masked: null, getKeyUrl: 'https://aistudio.google.com/apikey', steps: 'Sign in with Google → Get API key → Create API key.', models: [{ id: 'gemini-3.1-pro', label: 'Gemini 3.1 Pro' }, { id: 'gemini-3.5-flash', label: 'Gemini 3.5 Flash' }] },
  { providerId: 'xai', label: 'xAI', env: 'XAI_API_KEY', set: false, source: null, masked: null, getKeyUrl: 'https://console.x.ai', steps: 'Sign in → API Keys → Create API key. Add credit under Billing.', models: [{ id: 'grok-4.7', label: 'Grok 4.7' }] },
  { providerId: 'deepseek', label: 'DeepSeek', env: 'DEEPSEEK_API_KEY', set: false, source: null, masked: null, getKeyUrl: 'https://platform.deepseek.com/api_keys', steps: 'Sign in → API keys → Create new API key. Top up under Billing.', models: [{ id: 'deepseek-v4-flash', label: 'DeepSeek V4 Flash' }] },
  { providerId: 'mistral', label: 'Mistral', env: 'MISTRAL_API_KEY', set: false, source: null, masked: null, getKeyUrl: 'https://console.mistral.ai/api-keys', steps: 'Sign in → API Keys → Create new key.', models: [] },
  { providerId: 'openrouter', label: 'OpenRouter', env: 'OPENROUTER_API_KEY', set: false, source: null, masked: null, getKeyUrl: 'https://openrouter.ai/keys', steps: 'Sign in → Keys → Create Key. One key reaches models from many companies.', models: [] },
  { providerId: 'groq', label: 'Groq', env: 'GROQ_API_KEY', set: false, source: null, masked: null, getKeyUrl: 'https://console.groq.com/keys', steps: 'Sign in → API Keys → Create API Key.', models: [] },
  { providerId: 'together', label: 'Together AI', env: 'TOGETHER_API_KEY', set: false, source: null, masked: null, getKeyUrl: 'https://api.together.ai/settings/api-keys', steps: 'Sign in → Settings → API Keys.', models: [] },
];

/** `#/keys?fresh=1` in mock mode shows the first-run welcome (no keys saved yet). */
const MOCK_FRESH = typeof window !== 'undefined' && /[?&]fresh=1/.test(window.location.hash + window.location.search);
if (MOCK_FRESH) for (const k of MOCK_KEYS) Object.assign(k, { set: false, source: null, masked: null });

const MOCK_STORAGE: Storage = {
  dir: 'C:\\Users\\you\\AppData\\Roaming\\Gauntlet',
  envFile: 'C:\\Users\\you\\AppData\\Roaming\\Gauntlet\\.env',
  dataDir: 'C:\\Users\\you\\AppData\\Roaming\\Gauntlet\\data',
  settingsFile: 'C:\\Users\\you\\AppData\\Roaming\\Gauntlet\\settings.json',
  portable: false,
  windows: true,
};

/** Every model OpenRouter reaches in the demo, with its slug (null = not available via OpenRouter). */
const MOCK_ROUTES: Array<[string, string, string, string, string | null, number, number]> = [
  ['claude-opus-5-5', 'Claude Opus 5.5', 'Anthropic', 'anthropic', 'anthropic/claude-opus-5.5', 4, 20],
  ['claude-sonnet-5', 'Claude Sonnet 5', 'Anthropic', 'anthropic', 'anthropic/claude-sonnet-5', 2, 10],
  ['claude-haiku-4-5', 'Claude Haiku 4.5', 'Anthropic', 'anthropic', 'anthropic/claude-haiku-4.5', 1, 5],
  ['gpt-5.6-sol', 'GPT-5.6 Sol', 'OpenAI', 'openai', 'openai/gpt-5.6-sol', 5, 30],
  ['gpt-5.6-terra', 'GPT-5.6 Terra', 'OpenAI', 'openai', 'openai/gpt-5.6-terra', 2.5, 15],
  ['gpt-5-mini', 'GPT-5 mini', 'OpenAI', 'openai', 'openai/gpt-5-mini', 0.25, 2],
  ['gemini-3.1-pro', 'Gemini 3.1 Pro', 'Google', 'google', 'google/gemini-3.1-pro-preview', 2, 12],
  ['gemini-3.5-flash', 'Gemini 3.5 Flash', 'Google', 'google', 'google/gemini-3.5-flash', 1.5, 9],
  ['grok-4.7', 'Grok 4.7', 'xAI', 'xai', 'x-ai/grok-4.7', 2, 6],
  ['deepseek-v4-flash', 'DeepSeek V4 Flash', 'DeepSeek', 'deepseek', 'deepseek/deepseek-v4-flash', 0.3, 1.2],
  ['claude-fable-5-1', 'Claude Fable 5.1', 'Anthropic', 'anthropic', null, 10, 50],
];
let mockRouting: 'auto' | 'on' | 'off' = 'auto';

function mockRoutingStatus(): RoutingStatus {
  const orKey = MOCK_KEYS.find((k) => k.providerId === 'openrouter')!;
  const active = orKey.set && mockRouting !== 'off';
  return {
    hasKey: orKey.set,
    setting: mockRouting,
    active,
    catalogFetchedAt: orKey.set ? '2026-09-29T09:12:00.000Z' : null,
    catalogSize: orKey.set ? 412 : 0,
    rows: MOCK_ROUTES.map(([id, label, vendor, provider, slug, i, o]) => {
      const p = MOCK_KEYS.find((k) => k.providerId === provider);
      const directKey = Boolean(p?.set);
      return {
        id,
        label,
        vendor,
        provider,
        providerLabel: p?.label ?? vendor,
        directKey,
        slug,
        source: slug ? 'matched' : 'none',
        routed: active && !directKey && Boolean(slug),
        ...(slug ? { openRouterPrice: { inputPerM: i, outputPerM: o } } : {}),
        directPrice: { inputPerM: i, outputPerM: o },
      } satisfies RouteRow;
    }),
  };
}

function mockPaste(body: unknown): { results: PasteResult[]; storage: Storage } {
  const b = (body ?? {}) as { text?: string; provider?: string };
  const items = parsePastedKeys(b.text ?? '');
  if (!items.length) throw new Error('Paste the key into the box first.');
  const results = items.map((item): PasteResult => {
    const masked = `${item.key.slice(0, 7)}…${item.key.slice(-4)}`;
    const id = b.provider ?? detectProvider(item.key).providerId;
    const k = MOCK_KEYS.find((x) => x.providerId === id);
    if (!k) return { line: item.line, masked, how: 'unknown', ok: false, saved: false, needsChoice: true, ready: [], error: 'We couldn’t tell which company this key is from.' };
    const how = b.provider ? 'chosen' : 'shape';
    if (/bad|wrong/i.test(item.key)) return { line: item.line, masked, providerId: k.providerId, label: k.label, how, ok: false, saved: false, ready: [], error: 'The provider rejected this key. Check you copied all of it, or create a new one. It was not saved.' };
    if (/nocredit/i.test(item.key)) {
      Object.assign(k, { set: true, source: 'file', masked });
      return { line: item.line, masked, providerId: k.providerId, label: k.label, how, ok: false, saved: true, unchecked: true, ready: k.models.map((m) => ({ ...m, vendor: k.label })), error: 'The key works but the account has no credit. Add a payment method or credit on the provider’s billing page.' };
    }
    Object.assign(k, { set: true, source: 'file', masked });
    const ready = k.providerId === 'openrouter' ? MOCK_ROUTES.filter((r) => r[4]).map(([rid, label, vendor]) => ({ id: rid, label, vendor })) : k.models.map((m) => ({ ...m, vendor: k.label }));
    return { line: item.line, masked, providerId: k.providerId, label: k.label, how, ok: true, saved: true, ready };
  });
  return { results, storage: MOCK_STORAGE };
}

function mockKeys(method: Method, path: string, body?: unknown): unknown {
  if (path === '/api/setup') {
    const routed = mockRoutingStatus().rows.filter((r) => r.routed).length;
    const ready = MOCK_KEYS.filter((k) => k.set).reduce((n, k) => n + k.models.length, 0) + routed;
    return { storage: MOCK_STORAGE, guides: KEY_GUIDES, anyKey: MOCK_KEYS.some((k) => k.set), keys: MOCK_KEYS.filter((k) => k.set).map((k) => k.providerId), ready };
  }
  if (path === '/api/keys/paste') return mockPaste(body);
  if (path === '/api/openrouter' || path === '/api/openrouter/refresh') return mockRoutingStatus();
  if (path === '/api/openrouter/routing') {
    mockRouting = ((body as { setting?: 'auto' | 'on' | 'off' })?.setting ?? 'auto');
    return mockRoutingStatus();
  }
  const m = /^\/api\/keys\/([^/]+)(\/test)?$/.exec(path);
  const k = m ? MOCK_KEYS.find((x) => x.providerId === decodeURIComponent(m[1]!)) : undefined;
  if (method === 'GET') return { file: MOCK_STORAGE.envFile, storage: MOCK_STORAGE, keys: MOCK_KEYS.map((x) => ({ ...x })) };
  if (!k) throw new Error('Unknown provider');
  if (method === 'PUT') {
    const key = String((body as { key?: string })?.key ?? '').trim();
    if (key.length < 8) throw new Error('That looks too short to be an API key.');
    Object.assign(k, { set: true, source: 'file', masked: `${key.slice(0, 6)}…${key.slice(-4)}` });
    return { ok: true, check: { ok: true, models: 42 }, status: { ...k } };
  }
  if (method === 'DELETE') {
    Object.assign(k, { set: false, source: null, masked: null });
    return { ok: true, status: { ...k } };
  }
  if (!k.set) return { ok: false, error: 'No key saved for this provider yet.' };
  if ((body as { send?: boolean })?.send) return { ok: true, text: 'pong', model: k.models[k.models.length - 1]?.id, costUsd: 0.0004, totalMs: 820 };
  return { ok: true, models: 42 };
}
