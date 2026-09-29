import { join } from 'node:path';
import { CONFIG_DIR, DATA_DIR, USER_SETTINGS_FILE } from './paths.ts';
import { ENV_FILE } from './keys.ts';
import { APP_ROOT, USER_DIR, USER_DIR_IS_APP } from './userdir.ts';
import { likelyOldFolders, migrateToUserDir } from './migrate.ts';

/**
 * Runs once at start-up (cli.ts), before the API keys are loaded: copies keys, runs and settings from an older
 * in-folder install into the user folder the first time (see migrate.ts). Never throws: a failed copy only warns.
 */
export function prepareUserDir(log: (line: string) => void = (l) => console.log(l)): void {
  if (USER_DIR_IS_APP) return;
  try {
    const done = migrateToUserDir({
      userDir: USER_DIR,
      appRoot: APP_ROOT,
      envFile: ENV_FILE,
      dataDir: DATA_DIR,
      userSettingsFile: USER_SETTINGS_FILE,
      shippedSettingsFile: join(CONFIG_DIR, 'settings.json'),
      candidates: likelyOldFolders(),
      skip: { env: Boolean(process.env.GAUNTLET_ENV_FILE), data: Boolean(process.env.GAUNTLET_DATA_DIR), settings: Boolean(process.env.GAUNTLET_USER_SETTINGS) },
    });
    if (done.length) {
      log(`Gauntlet now keeps your keys, runs and settings in ${USER_DIR} (updating Gauntlet never deletes them).`);
      for (const d of done) log(`  ✓ ${d}`);
      log('  The old copies were left where they were.');
    }
  } catch (e) {
    log(`Note: couldn't copy your old keys/runs/settings into ${USER_DIR}: ${(e as Error).message}`);
  }
}
