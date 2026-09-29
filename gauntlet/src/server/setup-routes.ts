import type { IncomingMessage, ServerResponse } from 'node:http';
import { canCall, loadContestants, loadProviders, updateSettings } from '../core/config.ts';
import { ENV_FILE, cleanKey, explainKeyError, isRejection, keyFormatWarning, maskKey, saveKey } from '../core/keys.ts';
import { ENV_NAME_PROVIDER, detectProvider, parsePastedKeys } from '../core/key-detect.ts';
import { KEY_GUIDES } from '../core/key-guides.ts';
import { OPENROUTER_ID, ensureFreshCatalog, loadCatalog, refreshCatalog, routingStatus, type RoutingSetting } from '../core/openrouter.ts';
import { DATA_DIR, USER_SETTINGS_FILE } from '../core/paths.ts';
import { USER_DIR, USER_DIR_IS_APP } from '../core/userdir.ts';
import { discoverModels } from '../providers/index.ts';
import type { ProviderConfig } from '../core/types.ts';

/**
 * Easy setup: one paste box for any key, first-run welcome, where things are stored, and "one key for everything".
 *  GET  /api/setup                        where keys/runs/settings are stored, the "get a key" guides, whether any key is saved, models ready
 *  POST /api/keys/paste  {text, provider?}  detect the company of each pasted key, check it for free and save it
 *  GET  /api/openrouter                   routing on/off and each model's OpenRouter slug ("not available via OpenRouter" when none)
 *  POST /api/openrouter/refresh           re-download OpenRouter's model list (free)
 *  PUT  /api/openrouter/routing {setting} "auto" | "on" | "off"
 * Writes only from this computer (loopback), like the other key routes. Keys are never sent back, only masked.
 */

type Handler = (ctx: { req: IncomingMessage; res: ServerResponse; params: Record<string, string>; query: URLSearchParams; body: () => Promise<unknown> }) => unknown | Promise<unknown>;

interface Deps {
  route: (method: string, path: string, handler: Handler) => void;
  httpError: (status: number, message: string) => Error;
}

function isLocal(req: IncomingMessage): boolean {
  const a = req.socket.remoteAddress ?? '';
  return a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1';
}

export interface PasteResult {
  line: number;
  masked: string;
  providerId?: string;
  label?: string;
  /** How the company was found: from the key's shape, a NAME= line, the owner's choice, or not at all. */
  how: 'shape' | 'name' | 'chosen' | 'unknown';
  ok: boolean;
  saved: boolean;
  /** Unknown shape: ask "Which company is this key from?" and send it again with `provider`. */
  needsChoice?: boolean;
  error?: string;
  warning?: string;
  /** Gauntlet models this key makes ready (for OpenRouter: every model it can reach). */
  ready: Array<{ id: string; label: string; vendor: string }>;
  /** The key works but it couldn't be checked (network trouble): saved anyway. */
  unchecked?: boolean;
}

export function storageInfo() {
  return {
    dir: USER_DIR,
    envFile: ENV_FILE,
    dataDir: DATA_DIR,
    settingsFile: USER_SETTINGS_FILE,
    portable: USER_DIR_IS_APP,
    windows: process.platform === 'win32',
  };
}

async function freeCheck(providerId: string, key: string): Promise<{ ok: boolean; models?: number; error?: string; rejected?: boolean }> {
  try {
    const ids = await discoverModels(providerId, key);
    return { ok: true, models: ids.length };
  } catch (err) {
    const message = (err as Error).message;
    return { ok: false, error: explainKeyError(message), rejected: isRejection(message) };
  }
}

/** Models a provider's key makes ready. */
function readyModels(providerId: string): PasteResult['ready'] {
  const all = loadContestants().filter((c) => c.enabled);
  if (providerId === OPENROUTER_ID) {
    const st = routingStatus(all);
    const ids = new Set(st.rows.filter((r) => r.slug).map((r) => r.id));
    return all.filter((c) => ids.has(c.id) || c.provider === OPENROUTER_ID).map((c) => ({ id: c.id, label: c.label, vendor: c.vendor }));
  }
  return all.filter((c) => c.provider === providerId && !c.imageOnly).map((c) => ({ id: c.id, label: c.label, vendor: c.vendor }));
}

/** Detect, check (free) and save one pasted key. A key the company rejects is never saved. */
export async function pasteOne(
  item: { line: number; key: string; envName?: string },
  chosen: string | undefined,
  providers: ProviderConfig[],
  check: (providerId: string, key: string) => Promise<{ ok: boolean; models?: number; error?: string; rejected?: boolean }> = freeCheck,
): Promise<PasteResult> {
  const { key, error } = cleanKey(item.key);
  const masked = maskKey(item.key.trim());
  if (!key) return { line: item.line, masked, how: 'unknown', ok: false, saved: false, error, ready: [] };
  const keyed = providers.filter((p) => p.apiKeyEnv);
  const byId = (id: string | undefined) => keyed.find((p) => p.id === id);

  let how: PasteResult['how'] = 'unknown';
  let candidates: string[] = [];
  let note: string | undefined;
  if (chosen) {
    if (!byId(chosen)) return { line: item.line, masked, how: 'chosen', ok: false, saved: false, error: 'That company doesn’t use an API key in Gauntlet.', ready: [] };
    how = 'chosen';
    candidates = [chosen];
  } else if (item.envName && (keyed.find((p) => p.apiKeyEnv === item.envName) || byId(ENV_NAME_PROVIDER[item.envName]))) {
    how = 'name';
    candidates = [(keyed.find((p) => p.apiKeyEnv === item.envName) ?? byId(ENV_NAME_PROVIDER[item.envName]))!.id];
  } else {
    const d = detectProvider(key);
    note = d.note;
    candidates = d.candidates.filter((id) => byId(id));
    how = candidates.length ? 'shape' : 'unknown';
  }
  if (!candidates.length) {
    return { line: item.line, masked, how: 'unknown', ok: false, saved: false, needsChoice: true, ready: [], error: 'We couldn’t tell which company this key is from.' };
  }

  // Shapes shared by two companies (e.g. "sk-…"): the free check tells them apart.
  let picked = candidates[0]!;
  let result = await check(picked, key);
  for (const other of candidates.slice(1)) {
    if (result.ok || !result.rejected) break;
    const r = await check(other, key);
    if (r.ok || !r.rejected) {
      picked = other;
      result = r;
    }
  }
  const p = byId(picked)!;
  const base = { line: item.line, masked, providerId: p.id, label: p.label, how };
  if (!result.ok && result.rejected) {
    return { ...base, ok: false, saved: false, error: `${result.error ?? `${p.label} rejected this key.`} It was not saved.`, ...(note ? { warning: note } : {}), ready: [] };
  }
  saveKey(p.apiKeyEnv!, key);
  if (p.id === OPENROUTER_ID) await ensureFreshCatalog(0).catch(() => null);
  const warning = note ?? (how === 'chosen' ? keyFormatWarning(p.id, key) : undefined);
  return {
    ...base,
    ok: result.ok,
    saved: true,
    ...(result.ok ? {} : { unchecked: true, error: result.error }),
    ...(warning ? { warning } : {}),
    ready: readyModels(p.id),
  };
}

export function registerSetupRoutes({ route, httpError }: Deps): void {
  route('GET', '/api/setup', () => {
    const providers = loadProviders();
    const withKey = providers.filter((p) => p.apiKeyEnv && process.env[p.apiKeyEnv]);
    const ready = loadContestants().filter((c) => {
      const p = providers.find((x) => x.id === c.provider);
      return c.enabled && p && p.apiKeyEnv && canCall(c, providers);
    }).length;
    return { storage: storageInfo(), guides: KEY_GUIDES, anyKey: withKey.length > 0, keys: withKey.map((p) => p.id), ready };
  });

  route('POST', '/api/keys/paste', async ({ req, body }) => {
    if (!isLocal(req)) throw httpError(403, 'For safety, keys can only be changed from the computer running Gauntlet.');
    const b = ((await body()) ?? {}) as { text?: unknown; provider?: unknown };
    const text = typeof b.text === 'string' ? b.text : '';
    const items = parsePastedKeys(text);
    if (!items.length) {
      const { error } = cleanKey(text);
      throw httpError(400, error ?? 'Paste the key into the box first.');
    }
    if (items.length > 20) throw httpError(400, 'That’s more than 20 keys at once. Paste fewer.');
    const providers = loadProviders();
    const chosen = typeof b.provider === 'string' && b.provider ? b.provider : undefined;
    const results: PasteResult[] = [];
    for (const item of items) results.push(await pasteOne(item, chosen, providers));
    return { results, storage: storageInfo() };
  });

  route('GET', '/api/openrouter', async () => {
    await ensureFreshCatalog();
    return routingStatus(loadContestants());
  });

  route('POST', '/api/openrouter/refresh', async ({ req }) => {
    if (!isLocal(req)) throw httpError(403, 'Only from the computer running Gauntlet.');
    try {
      await refreshCatalog();
    } catch (e) {
      throw httpError(502, explainKeyError((e as Error).message));
    }
    return routingStatus(loadContestants());
  });

  route('PUT', '/api/openrouter/routing', async ({ req, body }) => {
    if (!isLocal(req)) throw httpError(403, 'Only from the computer running Gauntlet.');
    const b = ((await body()) ?? {}) as { setting?: unknown };
    if (b.setting !== 'auto' && b.setting !== 'on' && b.setting !== 'off') throw httpError(400, 'setting must be auto, on or off');
    updateSettings({ openrouterRouting: b.setting === 'auto' ? undefined : (b.setting as RoutingSetting) });
    return routingStatus(loadContestants(), loadProviders(), loadCatalog());
  });
}
