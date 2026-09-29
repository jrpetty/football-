import { existsSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { DATA_DIR } from './paths.ts';
import { contestantConfigHash, loadProviders, loadSettings, writeJsonAtomic } from './config.ts';
import type { Contestant, ContestantRoute, Pricing, ProviderConfig } from './types.ts';

/**
 * "One key for everything" via OpenRouter.
 *
 * When a model's own company key is missing but an OpenRouter key is saved (and routing is on), the model is run
 * through OpenRouter instead: same prompts, same settings, OpenRouter's model slug and OpenRouter's listed price.
 * The routed contestant carries `route` (recorded in the run manifest) and a different config hash, so routed and
 * direct results never mix silently on a leaderboard. Direct keys stay the gold standard for published results.
 *
 * Model slugs come from OpenRouter's public model list (GET /api/v1/models), cached in data/openrouter-models.json,
 * matched by vendor + model name. `openrouterModel` in config/models.json overrides the match.
 */

export const OPENROUTER_ID = 'openrouter';

/** OpenRouter's slug prefix for each vendor name used in config/models.json. */
export const VENDOR_PREFIX: Record<string, string> = {
  anthropic: 'anthropic',
  openai: 'openai',
  google: 'google',
  xai: 'x-ai',
  deepseek: 'deepseek',
  mistral: 'mistralai',
  'mistral ai': 'mistralai',
  meta: 'meta-llama',
  qwen: 'qwen',
  alibaba: 'qwen',
  moonshot: 'moonshotai',
  'moonshot ai': 'moonshotai',
};

/** One entry of OpenRouter's model list (only the fields Gauntlet uses). Prices are USD per token, as strings. */
export interface OpenRouterModel {
  id: string;
  name?: string;
  context_length?: number;
  pricing?: { prompt?: string; completion?: string; input_cache_read?: string; input_cache_write?: string };
}

export interface OpenRouterCatalog {
  fetchedAt: string;
  models: OpenRouterModel[];
}

export const CATALOG_FILE = () => join(DATA_DIR, 'openrouter-models.json');

let memo: { mtimeMs: number; file: string; catalog: OpenRouterCatalog } | null = null;

/** The cached model list, or null when it has never been fetched. */
export function loadCatalog(): OpenRouterCatalog | null {
  const file = CATALOG_FILE();
  if (!existsSync(file)) return null;
  try {
    const mtimeMs = statSync(file).mtimeMs;
    if (memo && memo.file === file && memo.mtimeMs === mtimeMs) return memo.catalog;
    const catalog = JSON.parse(readFileSync(file, 'utf8')) as OpenRouterCatalog;
    if (!Array.isArray(catalog.models)) return null;
    memo = { mtimeMs, file, catalog };
    return catalog;
  } catch {
    return null;
  }
}

export function saveCatalog(models: OpenRouterModel[], now = new Date()): OpenRouterCatalog {
  const trimmed = models
    .filter((m) => m && typeof m.id === 'string')
    .map((m) => ({
      id: m.id,
      ...(m.name ? { name: m.name } : {}),
      ...(m.context_length ? { context_length: m.context_length } : {}),
      ...(m.pricing ? { pricing: { prompt: m.pricing.prompt, completion: m.pricing.completion, input_cache_read: m.pricing.input_cache_read, input_cache_write: m.pricing.input_cache_write } } : {}),
    }));
  const catalog = { fetchedAt: now.toISOString(), models: trimmed };
  writeJsonAtomic(CATALOG_FILE(), catalog);
  memo = null;
  return catalog;
}

function openRouterProvider(providers: ProviderConfig[] = loadProviders()): ProviderConfig | undefined {
  return providers.find((p) => p.id === OPENROUTER_ID);
}

export function openRouterKey(providers?: ProviderConfig[]): string | undefined {
  const p = openRouterProvider(providers);
  return p?.apiKeyEnv ? process.env[p.apiKeyEnv] || undefined : undefined;
}

/** Download OpenRouter's model list (free) and cache it. `apiKey` defaults to the saved OpenRouter key. */
export async function refreshCatalog(apiKey = openRouterKey(), fetchImpl: typeof fetch = fetch): Promise<OpenRouterCatalog> {
  const base = (openRouterProvider()?.baseUrl ?? 'https://openrouter.ai/api/v1').replace(/\/+$/, '');
  const res = await fetchImpl(`${base}/models`, { headers: apiKey ? { Authorization: `Bearer ${apiKey}` } : {} });
  if (!res.ok) throw new Error(`OpenRouter ${res.status} error while listing models`);
  const body = (await res.json()) as { data?: OpenRouterModel[] };
  if (!Array.isArray(body.data)) throw new Error('OpenRouter returned an unexpected model list');
  return saveCatalog(body.data);
}

/** Refresh the cached list when it is missing or older than `maxAgeMs` and a key is saved. Never throws. */
export async function ensureFreshCatalog(maxAgeMs = 24 * 3600 * 1000): Promise<OpenRouterCatalog | null> {
  const cur = loadCatalog();
  if (!openRouterKey()) return cur;
  if (cur && Date.now() - Date.parse(cur.fetchedAt) < maxAgeMs) return cur;
  try {
    return await refreshCatalog();
  } catch {
    return cur;
  }
}

// ───────────────────────────── Matching ─────────────────────────────

/** "claude-opus-4-6" and "claude-opus-4.6" both become "claude-opus-4.6": OpenRouter writes versions with dots. */
export function normalizeModelName(s: string): string {
  let t = s.toLowerCase().trim();
  t = t.replace(/^[a-z0-9-]+\//, '');
  t = t.replace(/-latest$/, '');
  let prev = '';
  while (prev !== t) {
    prev = t;
    t = t.replace(/(\d)-(\d)/g, '$1.$2');
  }
  return t;
}

export function vendorPrefix(vendor: string): string | undefined {
  return VENDOR_PREFIX[vendor.toLowerCase().trim()];
}

export interface SlugMatch {
  slug: string | null;
  source: 'override' | 'matched' | 'none';
  /** The catalog entry, when the list has been fetched and the slug is in it. */
  entry?: OpenRouterModel;
}

/**
 * Find a contestant's OpenRouter slug: the explicit `openrouterModel` override first, then an exact match of the
 * normalised model id (or label) among the vendor's models. Never guesses between near matches: no match = null.
 */
export function matchSlug(c: Contestant, catalog: OpenRouterCatalog | null): SlugMatch {
  const models = catalog?.models ?? [];
  if (c.openrouterModel) {
    const entry = models.find((m) => m.id === c.openrouterModel);
    // Without a fetched list the override is trusted; with one it must exist (else it is reported as unavailable).
    if (entry || !catalog) return { slug: c.openrouterModel, source: 'override', entry };
    return { slug: null, source: 'none' };
  }
  const prefix = vendorPrefix(c.vendor);
  if (!prefix || !catalog) return { slug: null, source: 'none' };
  const own = models.filter((m) => m.id.startsWith(`${prefix}/`) && !m.id.includes(':'));
  const wanted = new Set<string>();
  for (const n of [c.model, c.id]) {
    const v = normalizeModelName(n);
    wanted.add(v);
    if (v.endsWith('-preview')) wanted.add(v.slice(0, -'-preview'.length));
    else wanted.add(`${v}-preview`);
  }
  let entry = own.find((m) => wanted.has(normalizeModelName(m.id)));
  if (!entry) {
    // Fall back to the display name, e.g. "Anthropic: Claude Opus 5.5" ↔ label "Claude Opus 5.5".
    const label = c.label.toLowerCase().trim();
    entry = own.find((m) => (m.name ?? '').replace(/^[^:]*:\s*/, '').toLowerCase().trim() === label);
  }
  return entry ? { slug: entry.id, source: 'matched', entry } : { slug: null, source: 'none' };
}

/** OpenRouter's listed price as Gauntlet pricing (USD per 1M tokens), or undefined when not listed. */
export function openRouterPricing(entry: OpenRouterModel | undefined, fetchedAt?: string): Pricing | undefined {
  const per = (v: string | undefined) => (v !== undefined && v !== null && v !== '' && Number.isFinite(Number(v)) && Number(v) >= 0 ? Math.round(Number(v) * 1e6 * 1e6) / 1e6 : undefined);
  const input = per(entry?.pricing?.prompt);
  const output = per(entry?.pricing?.completion);
  if (input === undefined || output === undefined) return undefined;
  const day = fetchedAt?.slice(0, 10) ?? null;
  const cached = per(entry?.pricing?.input_cache_read);
  const write = per(entry?.pricing?.input_cache_write);
  return {
    inputPerM: input,
    outputPerM: output,
    ...(cached !== undefined ? { cachedInputPerM: cached } : {}),
    ...(write !== undefined ? { cacheWritePerM: write } : {}),
    source: `OpenRouter's listed price for ${entry!.id}${day ? ` (model list fetched ${day})` : ''}. OpenRouter also takes a fee when you buy credits.`,
    verifiedAt: day,
  };
}

// ───────────────────────────── Routing ─────────────────────────────

export type RoutingSetting = 'auto' | 'on' | 'off';

export function routingSetting(): RoutingSetting {
  const v = loadSettings().openrouterRouting;
  return v === 'on' || v === 'off' ? v : 'auto';
}

/**
 * Is routing through OpenRouter switched on right now? On by default as soon as an OpenRouter key is saved (so a
 * beginner with only that key can run everything); the owner can switch it off. It only ever applies to models whose
 * own company key is missing: a direct key always wins.
 */
export function routingActive(providers: ProviderConfig[] = loadProviders(), setting: RoutingSetting = routingSetting()): boolean {
  return Boolean(openRouterKey(providers)) && setting !== 'off';
}

/** Kinds of contestant that are never routed: no key needed, picture makers (a different API), OpenRouter's own. */
function routable(c: Contestant, p: ProviderConfig | undefined): boolean {
  if (!p || !p.apiKeyEnv || p.id === OPENROUTER_ID) return false;
  if (p.type === 'mock' || p.type === 'manual') return false;
  return !c.imageOnly && !c.imageOutput;
}

/** The OpenRouter version of a contestant: OpenRouter's slug and listed price, with the route recorded. */
export function routedContestant(c: Contestant, match: SlugMatch, catalog: OpenRouterCatalog | null): Contestant {
  const pricing = openRouterPricing(match.entry, catalog?.fetchedAt) ?? {
    ...c.pricing,
    source: `${c.pricing.source ? `${c.pricing.source}. ` : ''}Direct price used: OpenRouter's price wasn't fetched yet (it may differ slightly, plus a fee when buying credits).`,
  };
  const route: ContestantRoute = { via: OPENROUTER_ID, provider: c.provider, model: c.model, slug: match.slug!, directHash: contestantConfigHash(c) };
  return { ...c, provider: OPENROUTER_ID, model: match.slug!, pricing, route };
}

/**
 * The contestant to actually call: routed through OpenRouter when its own key is missing, routing is on and a slug is
 * known; otherwise null (use it directly, or it has no key).
 */
export function routeFor(c: Contestant, providers: ProviderConfig[] = loadProviders(), catalog: OpenRouterCatalog | null = loadCatalog()): Contestant | null {
  if (c.route) return null;
  const p = providers.find((x) => x.id === c.provider);
  if (!routable(c, p) || process.env[p!.apiKeyEnv!]) return null;
  if (!routingActive(providers)) return null;
  const match = matchSlug(c, catalog);
  return match.slug ? routedContestant(c, match, catalog) : null;
}

/** The contestant as it will run: routed when needed, the original otherwise. */
export function effectiveContestant(c: Contestant, providers?: ProviderConfig[], catalog?: OpenRouterCatalog | null): Contestant {
  return routeFor(c, providers, catalog) ?? c;
}

// ───────────────────────────── The mapping shown to the owner ─────────────────────────────

export interface RouteRow {
  id: string;
  label: string;
  vendor: string;
  provider: string;
  providerLabel: string;
  /** The model's own company key is saved (it runs directly). */
  directKey: boolean;
  slug: string | null;
  source: SlugMatch['source'];
  /** It will run through OpenRouter right now. */
  routed: boolean;
  /** OpenRouter's listed price, USD per 1M tokens. */
  openRouterPrice?: { inputPerM: number; outputPerM: number };
  directPrice: { inputPerM: number; outputPerM: number };
}

export interface RoutingStatus {
  hasKey: boolean;
  setting: RoutingSetting;
  active: boolean;
  catalogFetchedAt: string | null;
  catalogSize: number;
  rows: RouteRow[];
}

export function routingStatus(contestants: Contestant[], providers: ProviderConfig[] = loadProviders(), catalog: OpenRouterCatalog | null = loadCatalog()): RoutingStatus {
  const active = routingActive(providers);
  const rows: RouteRow[] = [];
  for (const c of contestants) {
    const p = providers.find((x) => x.id === c.provider);
    if (!c.enabled || !routable(c, p)) continue;
    const match = matchSlug(c, catalog);
    const price = openRouterPricing(match.entry, catalog?.fetchedAt);
    const directKey = Boolean(process.env[p!.apiKeyEnv!]);
    rows.push({
      id: c.id,
      label: c.label,
      vendor: c.vendor,
      provider: c.provider,
      providerLabel: p!.label,
      directKey,
      slug: match.slug,
      source: match.source,
      routed: active && !directKey && Boolean(match.slug),
      ...(price ? { openRouterPrice: { inputPerM: price.inputPerM, outputPerM: price.outputPerM } } : {}),
      directPrice: { inputPerM: c.pricing.inputPerM, outputPerM: c.pricing.outputPerM },
    });
  }
  return { hasKey: Boolean(openRouterKey(providers)), setting: routingSetting(), active, catalogFetchedAt: catalog?.fetchedAt ?? null, catalogSize: catalog?.models.length ?? 0, rows };
}
