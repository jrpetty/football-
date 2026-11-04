/** Reads config/ladders.json (server side; the pure rules and the validator are in ladders.ts). */
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { CONFIG_DIR } from './paths.ts';
import { ladderRules, type LadderRule, type LaddersConfig } from './ladders.ts';

export const LADDERS_FILE = join(CONFIG_DIR, 'ladders.json');

/** The ladder config. A missing file (a sandboxed config folder) is an empty list, so leaderboards still work. */
export function loadLadders(file = LADDERS_FILE): LaddersConfig {
  if (!existsSync(file)) return { version: '0.0.0', title: 'Difficulty ladders', levels: 10, tests: [] };
  return JSON.parse(readFileSync(file, 'utf8')) as LaddersConfig;
}

/** Height rules for every built ladder test. */
export function loadLadderRules(file = LADDERS_FILE): Map<string, LadderRule> {
  return ladderRules(loadLadders(file));
}
