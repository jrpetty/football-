/**
 * Runs a virtual (in-memory) JavaScript project's tests in the Gauntlet
 * sandbox: a separate Node process with the permission model (see
 * runSandboxWorker in src/scoring/code-sandbox.ts) executing
 * code-agent-worker.mjs. Nothing touches the real disk.
 */
import { fileURLToPath } from 'node:url';
import { runSandboxWorker } from '../../scoring/code-sandbox.ts';

const WORKER = fileURLToPath(new URL('./code-agent-worker.mjs', import.meta.url));

export interface ProjectTest {
  file: string;
  name: string;
  ok: boolean;
  error: string | null;
  ms: number;
}

export interface ProjectRun {
  tests: ProjectTest[];
  /** Test files that failed to load (syntax error, missing module, …): file → message. */
  loadErrors: Array<{ file: string; error: string }>;
  passed: number;
  total: number;
  /** console output from the project (truncated). */
  log: string;
  /** Set when the sandbox itself died (time or memory limit). */
  crashed: string | null;
}

export async function runProjectTests(
  files: Record<string, string>,
  testFiles: string[],
  opts: { perTestTimeoutMs?: number; signal?: AbortSignal; maxLogChars?: number } = {},
): Promise<ProjectRun> {
  const perTestTimeoutMs = opts.perTestTimeoutMs ?? 2000;
  const hardLimitMs = 15_000 + testFiles.length * 4000 + 60 * (perTestTimeoutMs + 100);
  const res = await runSandboxWorker(WORKER, { files, run: testFiles, perTestTimeoutMs, maxLogChars: opts.maxLogChars ?? 3000 }, { hardLimitMs, signal: opts.signal });
  if (opts.signal?.aborted) throw Object.assign(new Error('Aborted'), { name: 'AbortError' });
  let parsed: { results: Array<{ file: string; loadError: string | null; tests: Array<{ name: string; ok: boolean; error: string | null; ms: number }> }>; log: string } | null = null;
  try {
    parsed = JSON.parse(res.stdout);
  } catch {
    parsed = null;
  }
  if (!parsed || !Array.isArray(parsed.results)) {
    const why = res.killed ? 'the test run was stopped (time limit — an endless loop or a promise that never settles?)' : `the sandbox crashed (exit ${res.exitCode}) ${res.stderr.split('\n').find((l) => /Error|error/.test(l)) ?? ''}`.trim();
    return { tests: [], loadErrors: testFiles.map((file) => ({ file, error: why })), passed: 0, total: 0, log: '', crashed: why };
  }
  const tests: ProjectTest[] = [];
  const loadErrors: Array<{ file: string; error: string }> = [];
  for (const r of parsed.results) {
    if (r.loadError) loadErrors.push({ file: r.file, error: r.loadError });
    for (const t of r.tests) tests.push({ file: r.file, name: t.name, ok: t.ok, error: t.error, ms: t.ms });
  }
  const passed = tests.filter((t) => t.ok).length;
  return { tests, loadErrors, passed, total: tests.length, log: parsed.log ?? '', crashed: null };
}

/** Stable identity of a test across runs. */
export function testKey(t: { file: string; name: string }): string {
  return `${t.file} › ${t.name}`;
}

/**
 * Count how many of a reference set of tests pass in `run`. Tests that did
 * not run (their file failed to load) count as failing, so a syntax error can
 * never shrink the denominator.
 */
export function passedOf(run: ProjectRun, reference: readonly string[]): number {
  const ok = new Set(run.tests.filter((t) => t.ok).map(testKey));
  return reference.filter((k) => ok.has(k)).length;
}
