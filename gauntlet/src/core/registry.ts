import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, unlinkSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { CUSTOM_TESTS_DIR, PRIVATE_TESTS_DIR, PROGRAMS_DIR, ROOT, SUITES_DIR, TESTS_DIR } from './paths.ts';
import { contentHash, sha256 } from './hash.ts';
import { FINAL_ANSWER_INSTRUCTION } from './extract.ts';
import { PROTOCOL_VERSION } from './version.ts';
import { writeJsonAtomic, loadCategories } from './config.ts';
import { JUDGE_PROMPT_FINGERPRINT } from '../scoring/judge-prompts.ts';
import { PROGRAMS } from '../programs/index.ts';
import { caseImageRefs, resolveTestImage, testBaseDir, testHasImages, testImageDigests, testsRelativePath, validateCaseImages } from './vision.ts';
import type { PromptTest, PromptTestCase, ScorerSpec, Suite, TestDefinition } from './types.ts';

export interface LoadedTest {
  definition: TestDefinition;
  hash: string;
  file: string;
  source: 'builtin' | 'custom' | 'private';
}

export interface TestSummary {
  id: string;
  version: string;
  hash: string;
  name: string;
  category: string;
  kind: 'prompt' | 'program';
  difficulty: string;
  description: string;
  hook?: string;
  tags: string[];
  caseCount: number;
  scorerType: string;
  source: 'builtin' | 'custom' | 'private';
  file: string;
  estimate: { inputTokens: number; outputTokens: number; calls: number };
  /** Number of cases that show the model an image (vision tests). */
  imageCases?: number;
}

export interface RenderedCase {
  caseId: string;
  system?: string;
  turns: string[];
  expected?: unknown;
  notes?: string;
  /** Images shown with the given (0-based) turn; `path` is relative to the tests folder (UI: /api/test-files/<path>). */
  images?: Array<{ turn: number; file: string; path?: string }>;
}

function walkJson(dir: string): string[] {
  if (!existsSync(dir)) return [];
  const out: string[] = [];
  for (const name of readdirSync(dir).sort()) {
    const full = join(dir, name);
    if (statSync(full).isDirectory()) out.push(...walkJson(full));
    else if (name.endsWith('.json')) out.push(full);
  }
  return out;
}

/** Hash a program module plus every relative module it imports (transitively). */
const programHashCache = new Map<string, { mtimeKey: string; hash: string }>();
export function programSourceHash(programId: string): string {
  const entry = join(PROGRAMS_DIR, `${programId}.ts`);
  if (!existsSync(entry)) return 'missing';
  const files = new Set<string>();
  const visit = (file: string) => {
    if (files.has(file) || !existsSync(file)) return;
    files.add(file);
    const src = readFileSync(file, 'utf8');
    for (const m of src.matchAll(/from\s+['"](\.{1,2}\/[^'"]+)['"]/g)) {
      const target = resolve(dirname(file), m[1]!);
      // Only hash program code, not the shared core (core changes are covered by the protocol version).
      if (target.startsWith(PROGRAMS_DIR)) visit(target);
    }
  };
  visit(entry);
  const sorted = [...files].sort();
  const mtimeKey = sorted.map((f) => `${f}:${statSync(f).mtimeMs}`).join('|');
  const cached = programHashCache.get(programId);
  if (cached && cached.mtimeKey === mtimeKey) return cached.hash;
  const hash = sha256(sorted.map((f) => relative(ROOT, f) + '\n' + readFileSync(f, 'utf8')).join('\n---\n')).slice(0, 12);
  programHashCache.set(programId, { mtimeKey, hash });
  return hash;
}

/**
 * Content hash of a test: definition + (for programs) program source code + (for vision tests) the bytes of every
 * image. `baseDir` is the folder of the test file (image paths are relative to it; default: tests/custom).
 */
export function computeTestHash(def: TestDefinition, baseDir?: string): string {
  if (def.kind === 'program') return contentHash({ def, program: programSourceHash(def.program) });
  if (testHasImages(def)) return contentHash({ def, images: testImageDigests(def, baseDir ?? testBaseDir()) });
  return contentHash(def);
}

export function loadTests(): LoadedTest[] {
  const out: LoadedTest[] = [];
  for (const file of walkJson(TESTS_DIR)) {
    let definition: TestDefinition;
    try {
      definition = JSON.parse(readFileSync(file, 'utf8')) as TestDefinition;
    } catch (err) {
      console.warn(`[gauntlet] Skipping unparsable test file ${relative(ROOT, file)}: ${(err as Error).message}`);
      continue;
    }
    out.push({
      definition,
      hash: computeTestHash(definition, dirname(file)),
      file: relative(ROOT, file),
      source: file.startsWith(CUSTOM_TESTS_DIR) ? 'custom' : file.startsWith(PRIVATE_TESTS_DIR) ? 'private' : 'builtin',
    });
  }
  return out;
}

export function getTest(id: string): LoadedTest | undefined {
  return loadTests().find((t) => t.definition.id === id);
}

export function needsFinalAnswer(scorer: ScorerSpec): boolean {
  return scorer.type === 'exact' || scorer.type === 'number' || scorer.type === 'choice' || (scorer.type === 'regex' && !scorer.fullText);
}

export function caseScorer(test: PromptTest, c: PromptTestCase): ScorerSpec {
  return c.scorer ?? test.scorer;
}

/** The exact prompts sent to every model for a prompt-test case. `baseDir` (the test file's folder) adds image paths. */
export function renderCase(test: PromptTest, c: PromptTestCase, baseDir?: string): RenderedCase {
  const turns = c.turns ? c.turns.slice() : [c.prompt ?? ''];
  if (test.preamble) turns[0] = `${test.preamble}\n\n${turns[0]}`;
  if (needsFinalAnswer(caseScorer(test, c))) turns[turns.length - 1] = `${turns[turns.length - 1]}\n\n${FINAL_ANSWER_INSTRUCTION}`;
  const out: RenderedCase = { caseId: c.id, system: test.system, turns, expected: c.expected, notes: c.notes };
  const refs = caseImageRefs(c);
  if (refs.length) {
    out.images = refs.map((r) => {
      const abs = baseDir ? resolveTestImage(baseDir, r.file) : null;
      return abs ? { ...r, path: testsRelativePath(abs) } : { ...r };
    });
  }
  return out;
}

export function caseIds(def: TestDefinition): string[] {
  return def.kind === 'prompt' ? def.cases.map((c) => c.id) : def.seeds.map((s) => `seed-${s}`);
}

function approxTokens(text: string): number {
  return Math.ceil(text.length / 3.8);
}

export function testEstimate(def: TestDefinition): { inputTokens: number; outputTokens: number; calls: number } {
  if (def.estimate) return { calls: 1, ...def.estimate };
  if (def.kind === 'prompt') {
    let input = 0;
    let turns = 0;
    for (const c of def.cases) {
      const r = renderCase(def, c);
      input += approxTokens((r.system ?? '') + r.turns.join('\n'));
      turns += r.turns.length;
    }
    const n = Math.max(1, def.cases.length);
    return { inputTokens: Math.ceil(input / n) + 50, outputTokens: 1500, calls: Math.max(1, Math.round(turns / n)) };
  }
  return { inputTokens: 20000, outputTokens: 8000, calls: 20 };
}

export function summarize(t: LoadedTest): TestSummary {
  const d = t.definition;
  return {
    id: d.id,
    version: d.version,
    hash: t.hash,
    name: d.name,
    category: d.category,
    kind: d.kind,
    difficulty: d.difficulty,
    description: d.description,
    hook: d.hook,
    tags: d.tags ?? [],
    caseCount: d.kind === 'prompt' ? d.cases.length : d.seeds.length,
    scorerType: d.kind === 'prompt' ? d.scorer.type : `program:${d.program}`,
    source: t.source,
    file: t.file,
    estimate: testEstimate(d),
    ...(testHasImages(d) && d.kind === 'prompt' ? { imageCases: d.cases.filter((c) => caseImageRefs(c).length > 0).length } : {}),
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// Validation
// ─────────────────────────────────────────────────────────────────────────────

const TEST_ID_RE = /^[a-z0-9][a-z0-9-]*\.[a-z0-9][a-z0-9.-]*$/;
const VERSION_RE = /^\d+\.\d+\.\d+$/;
const SCORER_TYPES = new Set(['exact', 'number', 'choice', 'regex', 'contains', 'constraints', 'json', 'code-js', 'judge', 'judge-classify', 'artifact', 'human']);
const CONSTRAINT_CHECKS = new Set([
  'word_count', 'sentence_count', 'paragraph_count', 'line_count', 'bullet_count', 'include', 'exclude', 'no_letter',
  'starts_with', 'ends_with', 'all_lowercase', 'all_uppercase', 'no_commas', 'json', 'json_keys', 'regex',
  'max_word_length', 'acrostic', 'each_line_starts_with', 'title_case_lines',
]);

function validateScorer(s: ScorerSpec | undefined, where: string, errors: string[]): void {
  if (!s || typeof s !== 'object') {
    errors.push(`${where}: scorer is required`);
    return;
  }
  if (!SCORER_TYPES.has(s.type)) {
    errors.push(`${where}: unknown scorer type "${(s as { type: string }).type}"`);
    return;
  }
  if (s.type === 'regex' && s.pattern !== undefined) {
    try {
      new RegExp(s.pattern, s.flags);
    } catch (e) {
      errors.push(`${where}: invalid regex (${(e as Error).message})`);
    }
  }
  if (s.type === 'judge' && !s.rubric?.trim()) errors.push(`${where}: judge scorer needs a rubric`);
  if (s.type === 'human' && !s.rubric?.trim()) errors.push(`${where}: human scorer needs a rubric`);
  if (s.type === 'judge-classify') {
    if (!s.instructions?.trim()) errors.push(`${where}: judge-classify needs instructions`);
    if (!Array.isArray(s.labels) || s.labels.length < 2) errors.push(`${where}: judge-classify needs at least 2 labels`);
    for (const l of s.labels ?? []) {
      if (!/^[A-Z][A-Z0-9_]*$/.test(l.id ?? '')) errors.push(`${where}: label id "${l.id}" must be UPPER_SNAKE_CASE`);
      if (!(l.score >= 0 && l.score <= 1)) errors.push(`${where}: label "${l.id}" score must be within 0..1`);
    }
  }
  if (s.type === 'artifact') {
    if (s.format !== 'html' && s.format !== 'svg') errors.push(`${where}: artifact format must be html or svg`);
    if (s.judgeWeight !== undefined && !(s.judgeWeight >= 0 && s.judgeWeight <= 1)) errors.push(`${where}: judgeWeight must be within 0..1`);
    if ((s.judgeWeight ?? 0) > 0 && !s.rubric) errors.push(`${where}: judgeWeight > 0 requires a rubric`);
  }
}

function validateExpected(s: ScorerSpec, expected: unknown, where: string, errors: string[]): void {
  switch (s.type) {
    case 'exact':
      if (!(typeof expected === 'string' || (Array.isArray(expected) && expected.length > 0 && expected.every((x) => typeof x === 'string'))))
        errors.push(`${where}: exact scorer needs expected string or string[]`);
      break;
    case 'number':
      if (typeof expected !== 'number' || !Number.isFinite(expected)) errors.push(`${where}: number scorer needs a numeric expected`);
      break;
    case 'choice':
      if (typeof expected !== 'string' || !/^[A-Z]$/i.test(expected)) errors.push(`${where}: choice scorer needs a single letter`);
      break;
    case 'regex':
      if (s.pattern === undefined) {
        if (typeof expected !== 'string') errors.push(`${where}: regex scorer needs a pattern (scorer.pattern or case expected)`);
        else {
          try {
            new RegExp(expected, s.flags);
          } catch (e) {
            errors.push(`${where}: invalid regex in expected (${(e as Error).message})`);
          }
        }
      }
      break;
    case 'constraints':
      if (!Array.isArray(expected) || expected.length === 0) errors.push(`${where}: constraints scorer needs a non-empty Constraint[]`);
      else
        for (const c of expected as Array<{ check?: string }>) {
          if (!c || !CONSTRAINT_CHECKS.has(c.check ?? '')) errors.push(`${where}: unknown constraint "${c?.check}"`);
        }
      break;
    case 'json':
      if (!expected || typeof expected !== 'object') errors.push(`${where}: json scorer needs an expected object`);
      break;
    case 'code-js': {
      const e = expected as { functionName?: unknown; tests?: unknown };
      if (!e || typeof e.functionName !== 'string' || !/^[A-Za-z_$][\w$]*$/.test(e.functionName)) errors.push(`${where}: code-js needs expected.functionName`);
      if (!e || !Array.isArray(e.tests) || e.tests.length === 0) errors.push(`${where}: code-js needs expected.tests[]`);
      else
        (e.tests as Array<{ args?: unknown }>).forEach((t, i) => {
          if (!Array.isArray(t?.args)) errors.push(`${where}: code-js test #${i + 1} needs args[]`);
          if (!('expected' in (t as object))) errors.push(`${where}: code-js test #${i + 1} needs expected`);
        });
      break;
    }
    case 'contains':
      if (!s.all && !s.any && !s.none) {
        const e = expected as { all?: unknown; any?: unknown; none?: unknown } | undefined;
        if (!e || (!e.all && !e.any && !e.none)) errors.push(`${where}: contains scorer needs all/any/none lists`);
      }
      break;
    default:
      break;
  }
}

export function validateTest(def: TestDefinition, allTests?: LoadedTest[], opts: { selfFile?: string; baseDir?: string } = {}): string[] {
  const errors: string[] = [];
  if (!def || typeof def !== 'object') return ['Test definition must be a JSON object'];
  if (!TEST_ID_RE.test(def.id ?? '')) errors.push('id must look like "<category>.<slug>" (lowercase letters, digits, hyphens)');
  if (!VERSION_RE.test(def.version ?? '')) errors.push('version must be semantic (e.g. 1.0.0)');
  if (!def.name?.trim()) errors.push('name is required');
  if (!def.description?.trim()) errors.push('description is required');
  const categories = loadCategories();
  if (!categories.some((c) => c.id === def.category)) errors.push(`category "${def.category}" is not defined in config/categories.json`);
  if (def.id && def.category && !def.id.startsWith(`${def.category}.`)) errors.push(`id should start with "${def.category}."`);
  if (!['easy', 'medium', 'hard', 'extreme'].includes(def.difficulty)) errors.push('difficulty must be easy | medium | hard | extreme');
  if (def.maxOutputTokens !== undefined && !(Number.isInteger(def.maxOutputTokens) && def.maxOutputTokens >= 16 && def.maxOutputTokens <= 256000))
    errors.push('maxOutputTokens must be an integer between 16 and 256000');
  if (def.timeLimitSec !== undefined && !(def.timeLimitSec > 0 && def.timeLimitSec <= 7200)) errors.push('timeLimitSec must be within 1..7200');

  if (def.kind === 'prompt') {
    validateScorer(def.scorer, 'scorer', errors);
    if (!Array.isArray(def.cases) || def.cases.length === 0) errors.push('cases must be a non-empty array');
    const ids = new Set<string>();
    (def.cases ?? []).forEach((c, i) => {
      const where = `case ${c?.id ?? `#${i + 1}`}`;
      if (!c || !/^[A-Za-z0-9_-]{1,40}$/.test(c.id ?? '')) errors.push(`${where}: id must be 1-40 chars of letters, digits, _ or -`);
      if (ids.has(c?.id)) errors.push(`${where}: duplicate case id`);
      ids.add(c?.id);
      const hasPrompt = typeof c?.prompt === 'string' && c.prompt.trim().length > 0;
      const hasTurns = Array.isArray(c?.turns) && c.turns.length > 0 && c.turns.every((t) => typeof t === 'string' && t.trim());
      if (hasPrompt === hasTurns) errors.push(`${where}: provide exactly one of prompt or turns`);
      const scorer = c?.scorer ?? def.scorer;
      if (c?.scorer) validateScorer(c.scorer, where, errors);
      if (scorer && SCORER_TYPES.has(scorer.type)) validateExpected(scorer, c?.expected, where, errors);
      if (c) validateCaseImages(c, opts.baseDir ?? testBaseDir(opts.selfFile), where, errors);
    });
  } else if (def.kind === 'program') {
    if (!PROGRAMS[def.program]) errors.push(`program "${def.program}" is not registered (see src/programs/index.ts)`);
    if (!Array.isArray(def.seeds) || def.seeds.length === 0 || !def.seeds.every((s) => Number.isInteger(s) && s >= 0))
      errors.push('seeds must be a non-empty array of non-negative integers');
    else if (new Set(def.seeds).size !== def.seeds.length) errors.push('seeds must be unique');
  } else {
    errors.push('kind must be "prompt" or "program"');
  }

  if (allTests) {
    const dup = allTests.find((t) => t.definition.id === def.id && t.file !== opts.selfFile);
    if (dup) errors.push(`id "${def.id}" is already used by ${dup.file}`);
  }
  return errors;
}

// ─────────────────────────────────────────────────────────────────────────────
// Custom tests (created from the UI / API)
// ─────────────────────────────────────────────────────────────────────────────

export function customTestPath(id: string): string {
  return join(CUSTOM_TESTS_DIR, `${id}.json`);
}

export function saveCustomTest(def: TestDefinition): LoadedTest {
  mkdirSync(CUSTOM_TESTS_DIR, { recursive: true });
  writeJsonAtomic(customTestPath(def.id), def);
  const loaded = getTest(def.id);
  if (!loaded) throw new Error('Failed to reload saved test');
  return loaded;
}

export function deleteCustomTest(id: string): boolean {
  const file = customTestPath(id);
  if (!existsSync(file)) return false;
  unlinkSync(file);
  return true;
}

export function bumpPatch(version: string): string {
  const [maj, min, pat] = version.split('.').map(Number);
  return `${maj}.${min}.${(pat ?? 0) + 1}`;
}

// ─────────────────────────────────────────────────────────────────────────────
// Suites
// ─────────────────────────────────────────────────────────────────────────────

export function loadSuites(): Suite[] {
  const suites = walkJson(SUITES_DIR).map((f) => JSON.parse(readFileSync(f, 'utf8')) as Suite);
  if (!suites.some((s) => s.id === 'all')) {
    suites.push({ id: 'all', version: '1.0.0', name: 'Everything', description: 'Every test in the library, including custom tests.', tests: [{ id: '*' }] });
  }
  return suites;
}

export function getSuite(id: string): Suite | undefined {
  return loadSuites().find((s) => s.id === id);
}

export type ResolvedTest = LoadedTest & { weight: number; caseFilter?: string[] };

/** Case ids that a resolved test will run (the suite's subset, if any, in the test's own order). */
export function selectedCaseIds(t: { definition: TestDefinition; caseFilter?: string[] }): string[] {
  const ids = caseIds(t.definition);
  return t.caseFilter ? ids.filter((id) => t.caseFilter!.includes(id)) : ids;
}

/** Resolve a suite (or explicit ids) to loaded tests with weights and optional case subsets. */
export function resolveTests(opts: { suiteId?: string; testIds?: string[] }, all = loadTests()): ResolvedTest[] {
  const byId = new Map(all.map((t) => [t.definition.id, t]));
  if (opts.testIds && opts.testIds.length > 0) {
    return opts.testIds.map((id) => {
      const t = byId.get(id);
      if (!t) throw new Error(`Unknown test "${id}"`);
      const entry = opts.suiteId ? getSuite(opts.suiteId)?.tests.find((x) => x.id === id) : undefined;
      return { ...t, weight: entry?.weight ?? 1, caseFilter: entry?.cases };
    });
  }
  const suite = getSuite(opts.suiteId ?? 'core');
  if (!suite) throw new Error(`Unknown suite "${opts.suiteId}"`);
  const out: ResolvedTest[] = [];
  const seen = new Set<string>();
  for (const entry of suite.tests) {
    if (entry.id === '*') {
      for (const t of all) if (!seen.has(t.definition.id)) {
        seen.add(t.definition.id);
        out.push({ ...t, weight: 1 });
      }
      continue;
    }
    const t = byId.get(entry.id);
    if (!t) {
      console.warn(`[gauntlet] Suite "${suite.id}" references missing test "${entry.id}"`);
      continue;
    }
    if (seen.has(entry.id)) continue;
    seen.add(entry.id);
    out.push({ ...t, weight: entry.weight ?? 1, caseFilter: entry.cases });
  }
  return out;
}

/** Fingerprint of a set of tests under the current protocol and judge prompts. */
export function fingerprint(tests: Array<{ definition: TestDefinition; hash: string; weight?: number; caseFilter?: string[] }>): string {
  return contentHash({
    protocol: PROTOCOL_VERSION,
    judge: JUDGE_PROMPT_FINGERPRINT,
    tests: tests.map((t) => (t.caseFilter ? [t.definition.id, t.hash, t.weight ?? 1, [...t.caseFilter].sort()] : [t.definition.id, t.hash, t.weight ?? 1])).sort(),
  });
}
