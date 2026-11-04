/** Reads config/business-ladder.json (server side; the pure rules are in business-ladder.ts). */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { CONFIG_DIR } from './paths.ts';
import type { LadderDefinition } from './business-ladder.ts';

export const BUSINESS_LADDER_FILE = join(CONFIG_DIR, 'business-ladder.json');

export function loadBusinessLadder(file = BUSINESS_LADDER_FILE): LadderDefinition {
  return JSON.parse(readFileSync(file, 'utf8')) as LadderDefinition;
}
