import { copyFileSync, cpSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join, resolve } from 'node:path';

/**
 * One-time move of the owner's things out of the app folder (see userdir.ts).
 *
 * Older versions kept API keys (gauntlet/.env), runs (gauntlet/data) and changed settings (config/settings.json)
 * inside the app folder. The first time this version starts with a user folder, it copies them over from:
 *  - this app folder (when the new version was unzipped over the old one: .env and data/ are still there),
 *  - the folder the old desktop shortcut pointed at (start-gauntlet.bat passes it as GAUNTLET_OLD_FOLDER),
 *  - the usual places a zip gets extracted (Desktop, OneDrive\Desktop, Downloads, Documents → gauntlet).
 * Originals are never deleted or changed. What was copied is printed and written to migration-log.txt.
 */

export interface MigrationPaths {
  userDir: string;
  appRoot: string;
  envFile: string;
  dataDir: string;
  userSettingsFile: string;
  /** This version's config/settings.json (the shipped defaults). */
  shippedSettingsFile: string;
  /** Extra old folders to look in, most likely first (after appRoot). */
  candidates?: string[];
  /** Pieces whose location was set by an environment variable: never migrated automatically. */
  skip?: { env?: boolean; data?: boolean; settings?: boolean };
}

/** Settings the app itself writes (money, budget, grading); these are what an owner changes. */
const MIGRATED_SETTINGS = ['currency', 'budget', 'gradingOfficial', 'openrouterRouting'] as const;

export const MIGRATION_MARKER = 'migrated.json';

/** Likely places for an older Gauntlet folder on this computer. */
export function likelyOldFolders(home: string = homedir(), env: NodeJS.ProcessEnv = process.env): string[] {
  const out: string[] = [];
  if (env.GAUNTLET_OLD_FOLDER) out.push(env.GAUNTLET_OLD_FOLDER);
  const bases = ['Desktop', join('OneDrive', 'Desktop'), 'Downloads', 'Documents', join('OneDrive', 'Documents'), ''].map((b) => join(home, b));
  for (const base of bases) {
    for (const name of ['gauntlet', 'Gauntlet', 'gauntlet-main']) {
      out.push(join(base, name));
      out.push(join(base, name, 'gauntlet')); // "Extract All" often makes gauntlet\gauntlet
    }
  }
  return out;
}

function isGauntletFolder(dir: string): boolean {
  try {
    return existsSync(join(dir, 'src', 'cli.ts')) && existsSync(join(dir, 'config'));
  } catch {
    return false;
  }
}

function runCount(dataDir: string): number {
  const runs = join(dataDir, 'runs');
  if (!existsSync(runs)) return 0;
  try {
    return readdirSync(runs).filter((d) => existsSync(join(runs, d, 'manifest.json'))).length;
  } catch {
    return 0;
  }
}

function readJsonSafe(file: string): Record<string, unknown> | null {
  try {
    const v = JSON.parse(readFileSync(file, 'utf8')) as unknown;
    return v && typeof v === 'object' && !Array.isArray(v) ? (v as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/** The owner's changes in an old config/settings.json: the app-written settings that differ from this version's defaults. */
export function settingsDifferences(old: Record<string, unknown>, shipped: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {};
  for (const k of MIGRATED_SETTINGS) {
    if (old[k] === undefined) continue;
    if (k === 'currency') {
      const a = old[k] as { code?: unknown; usdPerUnit?: unknown } | null;
      const b = shipped[k] as { code?: unknown; usdPerUnit?: unknown } | null;
      if (a && b && a.code === b.code && a.usdPerUnit === b.usdPerUnit) continue; // only the date differs
    }
    if (JSON.stringify(old[k]) !== JSON.stringify(shipped[k])) out[k] = old[k];
  }
  return out;
}

/** Copy old keys, runs and settings into the user folder once. Returns plain-English lines describing what happened. */
export function migrateToUserDir(p: MigrationPaths): string[] {
  const marker = join(p.userDir, MIGRATION_MARKER);
  if (existsSync(marker)) return [];
  const seen = new Set<string>();
  const folders = [p.appRoot, ...(p.candidates ?? [])]
    .map((d) => resolve(d))
    .filter((d) => !seen.has(d) && (seen.add(d), true))
    .filter((d) => d !== resolve(p.userDir) && isGauntletFolder(d));
  const done: string[] = [];

  if (!p.skip?.env && !existsSync(p.envFile)) {
    const from = folders.map((d) => join(d, '.env')).find((f) => existsSync(f) && statSync(f).size > 0);
    if (from) {
      mkdirSync(p.userDir, { recursive: true });
      copyFileSync(from, p.envFile);
      done.push(`API keys copied from ${from}`);
    }
  }

  if (!p.skip?.data && runCount(p.dataDir) === 0) {
    const from = folders.map((d) => join(d, 'data')).find((d) => resolve(d) !== resolve(p.dataDir) && runCount(d) > 0);
    if (from) {
      mkdirSync(p.dataDir, { recursive: true });
      cpSync(from, p.dataDir, { recursive: true, force: false, errorOnExist: false });
      done.push(`${runCount(from)} saved run(s) and other results copied from ${from}`);
    }
  }

  if (!p.skip?.settings && !existsSync(p.userSettingsFile)) {
    const shipped = readJsonSafe(p.shippedSettingsFile) ?? {};
    for (const d of folders) {
      const old = readJsonSafe(join(d, 'config', 'settings.json'));
      const diff = old ? settingsDifferences(old, shipped) : {};
      if (Object.keys(diff).length) {
        mkdirSync(p.userDir, { recursive: true });
        writeFileSync(p.userSettingsFile, JSON.stringify(diff, null, 2) + '\n');
        done.push(`Your settings (${Object.keys(diff).join(', ')}) copied from ${join(d, 'config', 'settings.json')}`);
        break;
      }
    }
  }

  mkdirSync(p.userDir, { recursive: true });
  const at = new Date().toISOString();
  writeFileSync(marker, JSON.stringify({ at, copied: done, note: 'Gauntlet copied these once from an older folder. The originals were left untouched.' }, null, 2) + '\n');
  if (done.length) {
    const log = join(p.userDir, 'migration-log.txt');
    const prev = existsSync(log) ? readFileSync(log, 'utf8') : '';
    writeFileSync(log, `${prev}${at}\n${done.map((l) => `  ${l}`).join('\n')}\n  (originals were not deleted)\n`);
  }
  return done;
}
