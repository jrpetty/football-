/**
 * Copy & paste models: where things are kept.
 *
 *   config/model-catalog.json                              the shipped catalogue (data only)
 *   <user folder>/data/manual-models/contestants.json      contestants made from the catalogue
 *   <user folder>/data/manual-models/custom-catalog.json   "Suggest a model not in the list" entries
 *   <user folder>/data/manual-models/reassign-log.jsonl    every result moved to a named model
 *   <user folder>/data/manual-models/pending-choices.json  models picked in the Inbox for replies not graded yet
 *
 * The user folder (%APPDATA%\Gauntlet on Windows, see src/core/userdir.ts) survives unzipping a new version,
 * so the owner's copy & paste models and their history are never lost on update.
 *
 * This file must not import core/config.ts (config.ts imports it to merge these contestants into the model list).
 */
import { appendFileSync, existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { CONFIG_DIR, DATA_DIR } from '../core/paths.ts';
import type { Contestant } from '../core/types.ts';
import { validateCatalog, validateCatalogModel, type CatalogModel, type ModelCatalog } from './identity.ts';

export const CATALOG_FILE = join(CONFIG_DIR, 'model-catalog.json');
export const MANUAL_DIR = join(DATA_DIR, 'manual-models');
export const CONTESTANTS_FILE = join(MANUAL_DIR, 'contestants.json');
export const CUSTOM_CATALOG_FILE = join(MANUAL_DIR, 'custom-catalog.json');
export const REASSIGN_LOG = join(MANUAL_DIR, 'reassign-log.jsonl');
export const PENDING_CHOICES_FILE = join(MANUAL_DIR, 'pending-choices.json');

/** Atomic JSON write (temp file + rename) so a crash never leaves half a file. */
export function writeJson(file: string, value: unknown): void {
  mkdirSync(MANUAL_DIR, { recursive: true });
  const tmp = `${file}.${process.pid}.tmp`;
  writeFileSync(tmp, JSON.stringify(value, null, 2) + '\n');
  renameSync(tmp, file);
}

export function readJson<T>(file: string, fallback: T): T {
  if (!existsSync(file)) return fallback;
  try {
    return JSON.parse(readFileSync(file, 'utf8')) as T;
  } catch (e) {
    console.warn(`Could not read ${file}: ${(e as Error).message}`);
    return fallback;
  }
}

export function appendLog(file: string, entry: unknown): void {
  mkdirSync(MANUAL_DIR, { recursive: true });
  appendFileSync(file, JSON.stringify(entry) + '\n');
}

// ───────────────────────────── Catalogue ─────────────────────────────

/** The shipped catalogue only (validated by `node src/cli.ts validate` and the tests). */
export function loadShippedCatalog(): ModelCatalog {
  return JSON.parse(readFileSync(CATALOG_FILE, 'utf8')) as ModelCatalog;
}

export function loadCustomModels(): CatalogModel[] {
  const list = readJson<CatalogModel[]>(CUSTOM_CATALOG_FILE, []);
  return Array.isArray(list) ? list.map((m) => ({ ...m, custom: true })) : [];
}

/** Shipped catalogue plus the owner's own additions (theirs never replace a shipped entry). */
export function loadCatalog(): ModelCatalog {
  const shipped = loadShippedCatalog();
  const ids = new Set(shipped.models.map((m) => m.id));
  return { ...shipped, models: [...shipped.models, ...loadCustomModels().filter((m) => !ids.has(m.id))] };
}

export function catalogProblems(): string[] {
  try {
    return validateCatalog(loadShippedCatalog());
  } catch (e) {
    return [`config/model-catalog.json: ${(e as Error).message}`];
  }
}

const slug = (s: string) =>
  s
    .toLowerCase()
    .replace(/[^a-z0-9.]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 34);

/** "Suggest a model not in the list": adds an unverified entry to the owner's own catalogue file. */
export function addCustomModel(input: { label?: unknown; vendor?: unknown; released?: unknown; family?: unknown; notes?: unknown; vision?: unknown; reasoning?: unknown }): CatalogModel {
  const label = typeof input.label === 'string' ? input.label.trim().slice(0, 80) : '';
  if (!label) throw new Error('Give the model a name, e.g. "Claude 2.1"');
  const vendor = typeof input.vendor === 'string' && input.vendor.trim() ? input.vendor.trim().slice(0, 40) : 'Other';
  const released = typeof input.released === 'string' ? input.released.trim() : '';
  if (!/^\d{4}-(0[1-9]|1[0-2])$/.test(released)) throw new Error('Release month must look like 2024-03');
  const catalog = loadCatalog();
  let id = `custom-${slug(label)}` || 'custom-model';
  for (let n = 2; catalog.models.some((m) => m.id === id); n++) id = `custom-${slug(label)}-${n}`;
  const model: CatalogModel = {
    id,
    label,
    vendor,
    family: typeof input.family === 'string' && input.family.trim() ? input.family.trim().slice(0, 40) : vendor,
    released,
    status: 'unverified',
    access: { api: 'unknown', chatApp: 'unknown' },
    vision: input.vision === true,
    reasoning: input.reasoning === true,
    ...(typeof input.notes === 'string' && input.notes.trim() ? { notes: input.notes.trim().slice(0, 300) } : {}),
    source: 'Added by you in Gauntlet (not verified)',
    verifiedAt: null,
    custom: true,
  };
  const vendors = catalog.vendors.some((v) => v.id === vendor) ? catalog.vendors : [...catalog.vendors, { id: vendor, chatApp: `${vendor} chat app`, color: '#64748B' }];
  const problems = validateCatalogModel(model, vendors);
  if (problems.length) throw new Error(problems.join('; '));
  const { custom: _c, ...stored } = model;
  writeJson(CUSTOM_CATALOG_FILE, [...readJson<CatalogModel[]>(CUSTOM_CATALOG_FILE, []), stored]);
  return model;
}

// ───────────────────────────── Contestants ─────────────────────────────

/** Copy & paste contestants the owner made from the catalogue (merged into the model list by core/config.ts). */
export function loadUserManualContestants(): Contestant[] {
  const file = readJson<{ contestants?: Contestant[] }>(CONTESTANTS_FILE, {});
  return Array.isArray(file.contestants) ? file.contestants : [];
}

export function isUserManualContestant(id: string): boolean {
  return loadUserManualContestants().some((c) => c.id === id);
}

export function saveUserManualContestant(c: Contestant): void {
  const list = loadUserManualContestants();
  const i = list.findIndex((x) => x.id === c.id);
  if (i >= 0) list[i] = c;
  else list.push(c);
  writeJson(CONTESTANTS_FILE, { contestants: list });
}

export function deleteUserManualContestant(id: string): boolean {
  const list = loadUserManualContestants();
  const next = list.filter((c) => c.id !== id);
  if (next.length === list.length) return false;
  writeJson(CONTESTANTS_FILE, { contestants: next });
  return true;
}
