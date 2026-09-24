/**
 * "Fix the Bug" fixture repositories.
 *
 * Each repo is a committed folder under src/programs/fixtures/code-agent/<id>/:
 *   repo/    what the model sees (README, package.json, src/, tests/) — with the planted bugs
 *   hidden/  hidden test files, never shown to the model
 *   fix/     the reference fix (changed files only) — used by unit tests only
 *   meta.json  title, issue text, par action count and the answer key (bug list)
 *
 * The folder is referenced below with `new URL(..., import.meta.url)`, so the
 * registry includes every fixture file in the test hash.
 */
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const FIXTURES_DIR = fileURLToPath(new URL('../fixtures/code-agent/', import.meta.url));

export interface RepoBug {
  file: string;
  kind: string;
  summary: string;
  /** How the bug shows itself: "visible, misleading", "visible only after the first fix (masked)", "hidden tests only". */
  visibility?: string;
  /** What the failing test suggests, and why that is misleading. */
  symptom?: string;
  /** Where the README/spec defines the correct behaviour (fairness note). */
  spec?: string;
}

export interface RepoMeta {
  id: string;
  title: string;
  tier: 'standard' | 'hard';
  /** Actions a competent engineer needs (explore, fix, re-run, submit) — the efficiency reference. */
  par: number;
  /** The bug report shown to the model. */
  issue: string;
  /** Answer key. Never sent to the model. */
  bugs: RepoBug[];
  /** Reference-fix files of the bugs that ONLY the hidden tests catch (the visible suite passes without them). */
  hiddenOnly?: string[];
  /** Deliberate red herrings in the repo (documentation only). */
  distractors?: string[];
}

export interface FixtureRepo {
  meta: RepoMeta;
  /** Files the model starts with (path → content, LF line endings). */
  files: Record<string, string>;
  /** Hidden test files, keyed by the path they are mounted at for the hidden run (tests/<name>.test.js). */
  hidden: Record<string, string>;
  /** Reference fix: changed files only. */
  fix: Record<string, string>;
}

function walk(dir: string, rel = ''): string[] {
  const out: string[] = [];
  for (const name of readdirSync(join(dir, rel)).sort()) {
    const r = rel ? `${rel}/${name}` : name;
    if (statSync(join(dir, r)).isDirectory()) out.push(...walk(dir, r));
    else out.push(r);
  }
  return out;
}

function readTree(dir: string): Record<string, string> {
  const out: Record<string, string> = {};
  if (!existsSync(dir)) return out;
  for (const rel of walk(dir)) out[rel] = readFileSync(join(dir, rel), 'utf8').replace(/\r\n?/g, '\n');
  return out;
}

/** Mount path of a hidden test: hidden/foo.hidden.test.js → tests/foo.test.js (same place as the visible tests). */
export function hiddenMountPath(rel: string): string {
  const base = rel.split('/').pop()!.replace(/\.hidden(?=\.test\.js$)/, '');
  return `tests/${base}`;
}

const cache = new Map<string, FixtureRepo>();

export function listRepoIds(): string[] {
  if (!existsSync(FIXTURES_DIR)) return [];
  return readdirSync(FIXTURES_DIR)
    .filter((d) => existsSync(join(FIXTURES_DIR, d, 'meta.json')))
    .sort();
}

export function loadRepo(id: string): FixtureRepo {
  const hit = cache.get(id);
  if (hit) return hit;
  if (!/^[a-z0-9-]+$/.test(id) || !existsSync(join(FIXTURES_DIR, id, 'meta.json'))) throw new Error(`Unknown code-agent repo "${id}"`);
  const meta = JSON.parse(readFileSync(join(FIXTURES_DIR, id, 'meta.json'), 'utf8')) as RepoMeta;
  const hidden: Record<string, string> = {};
  for (const [rel, content] of Object.entries(readTree(join(FIXTURES_DIR, id, 'hidden')))) hidden[hiddenMountPath(rel)] = content;
  const repo: FixtureRepo = {
    meta,
    files: readTree(join(FIXTURES_DIR, id, 'repo')),
    hidden,
    fix: readTree(join(FIXTURES_DIR, id, 'fix')),
  };
  cache.set(id, repo);
  return repo;
}

/** Visible test files: tests/**\/*.test.js, sorted. */
export function testFilesOf(files: Record<string, string>): string[] {
  return Object.keys(files)
    .filter((p) => p.startsWith('tests/') && p.endsWith('.test.js'))
    .sort();
}

/** The file set for the hidden run: the model's files with the visible tests swapped for the hidden ones. */
export function hiddenRunFiles(files: Record<string, string>, repo: FixtureRepo): { files: Record<string, string>; tests: string[] } {
  const out: Record<string, string> = {};
  for (const [p, c] of Object.entries(files)) if (!(p.startsWith('tests/') && p.endsWith('.test.js'))) out[p] = c;
  // Test helpers are read-only for the model, so the originals are used.
  for (const [p, c] of Object.entries(repo.files)) if (p.startsWith('tests/') && !p.endsWith('.test.js')) out[p] = c;
  for (const [p, c] of Object.entries(repo.hidden)) out[p] = c;
  return { files: out, tests: Object.keys(repo.hidden).sort() };
}
