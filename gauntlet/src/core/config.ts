import { existsSync, mkdirSync, readFileSync, writeFileSync, renameSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { CONFIG_DIR, USER_SETTINGS_FILE } from './paths.ts';
import { contentHash } from './hash.ts';
import { normalizeCurrency, type CurrencySettings } from './currency.ts';
import { routeFor } from './openrouter.ts';
import type { CategoryInfo, Contestant, ContestantSnapshot, ContestantView, ProviderConfig } from './types.ts';
import { deleteUserManualContestant, isUserManualContestant, loadUserManualContestants, saveUserManualContestant } from '../manual-models/store.ts';

export interface Settings {
  judges: string[];
  defaultRepeats: number;
  defaultConcurrency: number;
  temperature: number;
  defaultMaxOutputTokens: number;
  defaultTimeLimitSec: number;
  maxRetries: number;
  /** Never let a judge grade a model from its own vendor (when another judge is available). */
  judgeExcludeSameVendor: boolean;
  /** Reasoning effort used for judge calls (overrides the judge model's own effort; null keeps it). */
  judgeEffort: 'low' | 'medium' | 'high' | null;
  /** Display currency and exchange rate (GBP by default); every cost is still measured and stored in USD. */
  currency?: CurrencySettings;
  /** Which grade counts when a person and/or AI judges grade a result (src/grading/policy.ts). Default "methodology". */
  gradingOfficial?: 'methodology' | 'human' | 'ai' | 'average';
  /**
   * "One key for everything": run a model through OpenRouter when its own company's key is missing (src/core/openrouter.ts).
   * "auto" (default) and "on" = on whenever an OpenRouter key is saved; "off" = never. A direct key always wins.
   */
  openrouterRouting?: 'auto' | 'on' | 'off';
}

interface ModelsFile {
  providers: ProviderConfig[];
  contestants: Contestant[];
}

const MODELS_FILE = join(CONFIG_DIR, 'models.json');
const SETTINGS_FILE = join(CONFIG_DIR, 'settings.json');
const CATEGORIES_FILE = join(CONFIG_DIR, 'categories.json');

function readJson<T>(file: string): T {
  return JSON.parse(readFileSync(file, 'utf8')) as T;
}

/** Atomic write (temp file + rename) so a crash never leaves half a config file. */
export function writeJsonAtomic(file: string, value: unknown): void {
  mkdirSync(dirname(file), { recursive: true });
  const tmp = `${file}.${process.pid}.tmp`;
  writeFileSync(tmp, JSON.stringify(value, null, 2) + '\n');
  renameSync(tmp, file);
}

export function loadCategories(): CategoryInfo[] {
  return readJson<CategoryInfo[]>(CATEGORIES_FILE);
}

/** The shipped defaults (config/settings.json). Never written to unless running portable (see userdir.ts). */
export function loadShippedSettings(): Record<string, unknown> {
  return readJson<Record<string, unknown>>(SETTINGS_FILE);
}

/** The owner's own changes (<user folder>/settings.json), merged over the shipped defaults. Empty when none. */
export function loadUserSettings(): Record<string, unknown> {
  if (USER_SETTINGS_FILE === SETTINGS_FILE || !existsSync(USER_SETTINGS_FILE)) return {};
  try {
    const v = readJson<unknown>(USER_SETTINGS_FILE);
    return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : {};
  } catch (e) {
    console.warn(`Could not read your settings file ${USER_SETTINGS_FILE}: ${(e as Error).message}. Using the defaults.`);
    return {};
  }
}

export function loadSettings(): Settings {
  const defaults: Settings = {
    judges: [],
    defaultRepeats: 3,
    defaultConcurrency: 6,
    temperature: 0,
    defaultMaxOutputTokens: 16000,
    defaultTimeLimitSec: 1800,
    maxRetries: 4,
    judgeExcludeSameVendor: true,
    judgeEffort: 'medium',
  };
  const stored = { ...loadShippedSettings(), ...loadUserSettings() } as Partial<Settings>;
  return { ...defaults, ...stored, currency: normalizeCurrency(stored.currency) };
}

/**
 * Change some settings. Every settings write in Gauntlet goes through here: the change is saved in the owner's
 * settings file (outside the app folder), so unzipping a new version never resets it. `undefined` removes a key
 * (back to the shipped default). In portable mode the file is config/settings.json itself, as before.
 */
export function updateSettings(patch: Record<string, unknown>): void {
  const current = USER_SETTINGS_FILE === SETTINGS_FILE ? loadShippedSettings() : loadUserSettings();
  const next: Record<string, unknown> = { ...current };
  for (const [k, v] of Object.entries(patch)) {
    if (v === undefined) delete next[k];
    else next[k] = v;
  }
  writeJsonAtomic(USER_SETTINGS_FILE, next);
}

/** Save a whole Settings object: only what differs from the shipped defaults is kept in the owner's file. */
export function saveSettings(settings: Settings): void {
  if (USER_SETTINGS_FILE === SETTINGS_FILE) {
    writeJsonAtomic(SETTINGS_FILE, settings);
    return;
  }
  const shipped = loadShippedSettings();
  const patch: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(settings)) patch[k] = JSON.stringify(v) === JSON.stringify(shipped[k]) ? undefined : v;
  updateSettings(patch);
}

function loadModelsFile(): ModelsFile {
  return readJson<ModelsFile>(MODELS_FILE);
}

export function loadProviders(): ProviderConfig[] {
  return loadModelsFile().providers;
}

export function loadContestants(): Contestant[] {
  const shipped = loadModelsFile().contestants;
  // Copy & paste models made from the model catalogue live in the user folder (src/manual-models/store.ts).
  const ids = new Set(shipped.map((c) => c.id));
  return [...shipped, ...loadUserManualContestants().filter((c) => !ids.has(c.id))];
}

export function getProvider(id: string): ProviderConfig {
  const p = loadProviders().find((x) => x.id === id);
  if (!p) throw new Error(`Unknown provider "${id}"`);
  return p;
}

export function getContestant(id: string): Contestant {
  const c = loadContestants().find((x) => x.id === id);
  if (!c) throw new Error(`Unknown contestant "${id}"`);
  return c;
}

/** Hash of everything that affects model behaviour or cost (not label/colour). */
export function contestantConfigHash(c: Contestant): string {
  const direct = { provider: c.provider, model: c.model, options: c.options ?? {}, pricing: { i: c.pricing.inputPerM, o: c.pricing.outputPerM, c: c.pricing.cachedInputPerM, w: c.pricing.cacheWritePerM } };
  // A routed contestant (via OpenRouter) hashes its route too, so it can never share a hash with a direct run.
  const base = c.route ? { ...direct, route: { via: c.route.via, provider: c.route.provider, model: c.route.model } } : direct;
  // Picture settings only count for picture-making contestants, so every other contestant keeps its hash.
  return contentHash(c.imageOutput ? { ...base, image: { options: c.imageOptions ?? {}, perImage: c.imagePricing?.perImage ?? null } } : base);
}

export function hasApiKey(provider: ProviderConfig): boolean {
  if (provider.type === 'mock' || provider.type === 'manual' || provider.apiKeyEnv === null) return true;
  return Boolean(process.env[provider.apiKeyEnv]);
}

/** Can this contestant be called right now: its own key, no key needed, or routed through OpenRouter. */
export function canCall(c: Contestant, providers = loadProviders()): boolean {
  const p = providers.find((x) => x.id === c.provider);
  if (!p) return false;
  return hasApiKey(p) || routeFor(c, providers) !== null;
}

export function toView(c: Contestant, providers = loadProviders()): ContestantView {
  const p = providers.find((x) => x.id === c.provider);
  const direct = p ? hasApiKey(p) : false;
  const routed = !direct && p ? routeFor(c, providers) : null;
  return {
    ...c,
    configHash: contestantConfigHash(c),
    hasKey: direct || routed !== null,
    ...(routed ? { via: 'openrouter' as const, viaModel: routed.model } : {}),
    providerLabel: p?.label ?? c.provider,
    providerType: p?.type ?? 'openai-compatible',
  };
}

export function snapshotContestant(c: Contestant): ContestantSnapshot {
  return { ...structuredClone(c), configHash: contestantConfigHash(c) };
}

const ID_RE = /^[a-z0-9][a-z0-9._-]{0,63}$/;

export function validateContestant(c: Contestant): string[] {
  const errors: string[] = [];
  if (!c || typeof c !== 'object') return ['Contestant must be an object'];
  if (!ID_RE.test(c.id ?? '')) errors.push('id must be lowercase letters, digits, ".", "_" or "-" (max 64 chars)');
  if (!c.label?.trim()) errors.push('label is required');
  if (!c.model?.trim()) errors.push('model is required');
  if (!loadProviders().some((p) => p.id === c.provider)) errors.push(`provider "${c.provider}" does not exist`);
  if (!/^#[0-9a-fA-F]{6}$/.test(c.color ?? '')) errors.push('color must be a #RRGGBB hex colour');
  const pr = c.pricing;
  if (!pr || !(pr.inputPerM >= 0) || !(pr.outputPerM >= 0)) errors.push('pricing.inputPerM and pricing.outputPerM must be numbers ≥ 0');
  if (c.vision !== undefined && typeof c.vision !== 'boolean') errors.push('vision must be true or false');
  if (c.imageOutput !== undefined && typeof c.imageOutput !== 'boolean') errors.push('imageOutput must be true or false');
  if (c.imageOnly !== undefined && typeof c.imageOnly !== 'boolean') errors.push('imageOnly must be true or false');
  if (c.imagePricing !== undefined) {
    const t = c.imagePricing?.perImage;
    const ok = t && typeof t === 'object' && Object.values(t).every((row) => row && typeof row === 'object' && Object.values(row).every((v) => typeof v === 'number' && v >= 0));
    if (!ok) errors.push('imagePricing.perImage must map sizes to { quality: USD per image } (use "*" for any size or quality)');
  }
  if (c.releaseDate !== undefined && c.releaseDate !== '' && !/^\d{4}-\d{2}-\d{2}$/.test(c.releaseDate)) errors.push('releaseDate must be YYYY-MM-DD');
  if (c.tier !== undefined && !['flagship', 'mid', 'small'].includes(c.tier)) errors.push('tier must be flagship, mid or small');
  if (c.maxOutputTokens !== undefined && !(Number.isInteger(c.maxOutputTokens) && c.maxOutputTokens > 0)) errors.push('maxOutputTokens must be a positive whole number');
  if (c.openrouterModel !== undefined && !(typeof c.openrouterModel === 'string' && /^[a-z0-9._-]+\/[A-Za-z0-9._:-]+$/.test(c.openrouterModel))) errors.push('openrouterModel must be an OpenRouter slug such as "anthropic/claude-opus-4.6"');
  if (c.options?.extraBody !== undefined && (typeof c.options.extraBody !== 'object' || Array.isArray(c.options.extraBody))) errors.push('options.extraBody must be an object');
  return errors;
}

export function upsertContestant(c: Contestant): void {
  if (c.manualModel || isUserManualContestant(c.id)) {
    if (!loadModelsFile().contestants.some((x) => x.id === c.id)) return saveUserManualContestant(c);
  }
  const file = loadModelsFile();
  const idx = file.contestants.findIndex((x) => x.id === c.id);
  if (idx >= 0) file.contestants[idx] = c;
  else file.contestants.push(c);
  writeJsonAtomic(MODELS_FILE, file);
}

export function deleteContestant(id: string): boolean {
  if (deleteUserManualContestant(id)) return true;
  const file = loadModelsFile();
  const before = file.contestants.length;
  file.contestants = file.contestants.filter((x) => x.id !== id);
  if (file.contestants.length === before) return false;
  writeJsonAtomic(MODELS_FILE, file);
  return true;
}
