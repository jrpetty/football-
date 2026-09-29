import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Where the owner's own things live: API keys, runs and changed settings.
 *
 * They are kept OUTSIDE the app folder, so unzipping a new version (over the old folder or anywhere else) never
 * loses them:
 *   Windows        %APPDATA%\Gauntlet            (e.g. C:\Users\you\AppData\Roaming\Gauntlet)
 *   macOS / Linux  ~/.gauntlet
 *
 * Overrides, in order: GAUNTLET_USER_DIR (the whole folder), then GAUNTLET_ENV_FILE / GAUNTLET_DATA_DIR /
 * GAUNTLET_USER_SETTINGS for single pieces.
 *
 * "Portable" mode keeps everything inside the app folder as before (gauntlet/.env, gauntlet/data,
 * config/settings.json). It is on with GAUNTLET_PORTABLE=1 and automatically under the test runner
 * (node --test sets NODE_TEST_CONTEXT) or NODE_ENV=test, so `npm test` never touches the real home folder.
 */

/** The app folder (gauntlet/). Same value as paths.ts ROOT, computed here to avoid an import cycle. */
export const APP_ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..');

export function isPortable(env: NodeJS.ProcessEnv = process.env): boolean {
  return env.GAUNTLET_PORTABLE === '1' || env.NODE_ENV === 'test' || Boolean(env.NODE_TEST_CONTEXT);
}

/** The per-user folder for this computer (not taking overrides or portable mode into account). */
export function defaultUserDir(env: NodeJS.ProcessEnv = process.env, platform: string = process.platform, home: string = homedir()): string {
  if (platform === 'win32') {
    const appData = env.APPDATA || join(home, 'AppData', 'Roaming');
    return join(appData, 'Gauntlet');
  }
  return join(home, '.gauntlet');
}

/** Resolve the user folder: GAUNTLET_USER_DIR, else the app folder in portable mode, else the per-user folder. */
export function resolveUserDir(env: NodeJS.ProcessEnv = process.env, platform: string = process.platform, home: string = homedir()): string {
  if (env.GAUNTLET_USER_DIR) return resolve(env.GAUNTLET_USER_DIR);
  if (isPortable(env)) return APP_ROOT;
  return defaultUserDir(env, platform, home);
}

export const USER_DIR = resolveUserDir();

/** True when keys/runs/settings live in the app folder itself (portable mode). */
export const USER_DIR_IS_APP = USER_DIR === APP_ROOT;
