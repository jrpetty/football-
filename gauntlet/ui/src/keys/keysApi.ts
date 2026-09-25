/**
 * Client for the API Keys page (/api/keys, docs/API.md → "API keys").
 * In mock mode (?mock=1) an in-memory stand-in answers, so the page can be demoed with no server.
 */
import { MOCK, request } from '../api.ts';

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

type Method = 'GET' | 'POST' | 'PUT' | 'DELETE';

async function call<T>(method: Method, path: string, body?: unknown): Promise<T> {
  if (MOCK) return mockKeys(method, path, body) as T;
  return request<T>(method, path, body);
}

export const keysApi = {
  list: () => call<{ file: string; keys: KeyStatus[] }>('GET', '/api/keys'),
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

function mockKeys(method: Method, path: string, body?: unknown): unknown {
  const m = /^\/api\/keys\/([^/]+)(\/test)?$/.exec(path);
  const k = m ? MOCK_KEYS.find((x) => x.providerId === decodeURIComponent(m[1]!)) : undefined;
  if (method === 'GET') return { file: 'gauntlet/.env', keys: MOCK_KEYS.map((x) => ({ ...x })) };
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
