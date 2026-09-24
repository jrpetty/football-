import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));

/** Absolute paths of every Gauntlet directory. GAUNTLET_DATA_DIR / GAUNTLET_TESTS_DIR relocate run storage / the test library. */
export const ROOT = resolve(here, '..', '..');
export const CONFIG_DIR = process.env.GAUNTLET_CONFIG_DIR ? resolve(process.env.GAUNTLET_CONFIG_DIR) : join(ROOT, 'config');
export const TESTS_DIR = process.env.GAUNTLET_TESTS_DIR ? resolve(process.env.GAUNTLET_TESTS_DIR) : join(ROOT, 'tests');
export const CUSTOM_TESTS_DIR = join(TESTS_DIR, 'custom');
/** Held-out tests that are never committed or published (git-ignored). */
export const PRIVATE_TESTS_DIR = join(TESTS_DIR, 'private');
export const SUITES_DIR = join(ROOT, 'suites');
export const PROGRAMS_DIR = join(ROOT, 'src', 'programs');
export const UI_DIST_DIR = join(ROOT, 'ui', 'dist');
export const DATA_DIR = process.env.GAUNTLET_DATA_DIR ? resolve(process.env.GAUNTLET_DATA_DIR) : join(ROOT, 'data');
export const RUNS_DIR = join(DATA_DIR, 'runs');
