import { readFileSync, writeFileSync, renameSync } from 'node:fs';
import { join } from 'node:path';
import { CONFIG_DIR } from './paths.ts';
import { contentHash } from './hash.ts';
import type { CategoryInfo, Contestant, ContestantSnapshot, ContestantView, ProviderConfig } from './types.ts';

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
  const tmp = `${file}.${process.pid}.tmp`;
  writeFileSync(tmp, JSON.stringify(value, null, 2) + '\n');
  renameSync(tmp, file);
}

export function loadCategories(): CategoryInfo[] {
  return readJson<CategoryInfo[]>(CATEGORIES_FILE);
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
  };
  return { ...defaults, ...readJson<Partial<Settings>>(SETTINGS_FILE) };
}

export function saveSettings(settings: Settings): void {
  writeJsonAtomic(SETTINGS_FILE, settings);
}

function loadModelsFile(): ModelsFile {
  return readJson<ModelsFile>(MODELS_FILE);
}

export function loadProviders(): ProviderConfig[] {
  return loadModelsFile().providers;
}

export function loadContestants(): Contestant[] {
  return loadModelsFile().contestants;
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
  return contentHash({ provider: c.provider, model: c.model, options: c.options ?? {}, pricing: { i: c.pricing.inputPerM, o: c.pricing.outputPerM, c: c.pricing.cachedInputPerM, w: c.pricing.cacheWritePerM } });
}

export function hasApiKey(provider: ProviderConfig): boolean {
  if (provider.type === 'mock' || provider.type === 'manual' || provider.apiKeyEnv === null) return true;
  return Boolean(process.env[provider.apiKeyEnv]);
}

export function toView(c: Contestant, providers = loadProviders()): ContestantView {
  const p = providers.find((x) => x.id === c.provider);
  return {
    ...c,
    configHash: contestantConfigHash(c),
    hasKey: p ? hasApiKey(p) : false,
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
  if (c.options?.extraBody !== undefined && (typeof c.options.extraBody !== 'object' || Array.isArray(c.options.extraBody))) errors.push('options.extraBody must be an object');
  return errors;
}

export function upsertContestant(c: Contestant): void {
  const file = loadModelsFile();
  const idx = file.contestants.findIndex((x) => x.id === c.id);
  if (idx >= 0) file.contestants[idx] = c;
  else file.contestants.push(c);
  writeJsonAtomic(MODELS_FILE, file);
}

export function deleteContestant(id: string): boolean {
  const file = loadModelsFile();
  const before = file.contestants.length;
  file.contestants = file.contestants.filter((x) => x.id !== id);
  if (file.contestants.length === before) return false;
  writeJsonAtomic(MODELS_FILE, file);
  return true;
}
