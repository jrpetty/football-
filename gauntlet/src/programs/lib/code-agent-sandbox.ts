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
  /** Set when the sandbox could not start at all (an infrastructure failure; a crash mid-run only fails the running test). */
  crashed: string | null;
}

type WorkerEvent =
  | { t: 'start' }
  | { t: 'ready' }
  | { t: 'file'; fi: number; loadError: string | null; names: string[] }
  | { t: 'test'; fi: number; ti: number; ok: boolean; error: string | null; ms: number }
  | { t: 'log'; s: string }
  | { t: 'crash'; reason: string }
  | { t: 'done' };

function parseEvents(stdout: string): WorkerEvent[] {
  const out: WorkerEvent[] = [];
  for (const line of stdout.split('\n')) {
    if (!line.trim()) continue;
    try {
      const m = JSON.parse(line) as WorkerEvent;
      if (m && typeof m === 'object' && typeof m.t === 'string') out.push(m);
    } catch {
      /* a line torn by a crash */
    }
  }
  return out;
}

/**
 * Run the test files. Project code runs in a worker thread with its own 4 MB stack and 256 MB heap
 * (code-agent-worker.mjs), so deep recursion throws the same catchable error on every OS. If the sandbox
 * dies anyway (out of memory, the hard time limit), the results it already reported stand, the test that
 * was running fails with the reason, and a fresh process continues with the next test: a crash never
 * counts as a pass and never throws away the tests around it.
 */
export async function runProjectTests(
  files: Record<string, string>,
  testFiles: string[],
  opts: { perTestTimeoutMs?: number; signal?: AbortSignal; maxLogChars?: number } = {},
): Promise<ProjectRun> {
  const perTestTimeoutMs = opts.perTestTimeoutMs ?? 2000;
  const maxLogChars = opts.maxLogChars ?? 3000;
  const perFile: Array<{ loadError: string | null; names: string[] | null; tests: Map<number, { ok: boolean; error: string | null; ms: number }> }> = testFiles.map(() => ({
    loadError: null,
    names: null,
    tests: new Map(),
  }));
  let log = '';
  let start = { file: 0, test: 0 };
  while (start.file < testFiles.length) {
    const remaining = testFiles.length - start.file;
    const hardLimitMs = 15_000 + remaining * 4000 + 60 * (perTestTimeoutMs + 100);
    const res = await runSandboxWorker(WORKER, { files, run: testFiles, perTestTimeoutMs, maxLogChars, start }, { hardLimitMs, signal: opts.signal });
    if (opts.signal?.aborted) throw Object.assign(new Error('Aborted'), { name: 'AbortError' });
    const events = parseEvents(res.stdout);
    if (!events.some((e) => e.t === 'ready')) {
      // The sandbox itself could not start: an infrastructure failure, not something the project did.
      const why = res.killed ? 'the sandbox did not start within its time limit' : `the sandbox could not start (exit ${res.exitCode}) ${res.stderr.split('\n').find((l) => /Error|error/.test(l)) ?? ''}`.trim();
      return { tests: [], loadErrors: testFiles.map((file) => ({ file, error: why })), passed: 0, total: 0, log: '', crashed: why };
    }
    let crash: string | null = null;
    let done = false;
    let current = start.file;
    for (const e of events) {
      if (e.t === 'file' && e.fi >= 0 && e.fi < testFiles.length) {
        perFile[e.fi]!.loadError = e.loadError;
        perFile[e.fi]!.names = e.names;
        current = e.fi;
      } else if (e.t === 'test' && e.fi >= 0 && e.fi < testFiles.length) perFile[e.fi]!.tests.set(e.ti, { ok: e.ok === true, error: e.error, ms: e.ms });
      else if (e.t === 'log' && log.length <= maxLogChars) log += e.s;
      else if (e.t === 'crash') crash = e.reason;
      else if (e.t === 'done') done = true;
    }
    if (done) break;
    // The process ended early. Whatever was running failed; continue after it in a fresh process.
    const reason = `the sandbox crashed: ${crash ?? (res.killed ? 'stopped at its time limit (an endless loop or a never-ending chain of timers?)' : `exit ${res.exitCode}`)}`;
    const f = perFile[current]!;
    if (!f.names) {
      // It died while loading the file (before its tests were listed).
      f.loadError = `Loading the file failed: ${reason}`;
      f.names = [];
      start = { file: current + 1, test: 0 };
      continue;
    }
    let next = current === start.file ? start.test : 0;
    while (next < f.names.length && f.tests.has(next)) next++;
    if (next < f.names.length) {
      f.tests.set(next, { ok: false, error: reason, ms: 0 });
      start = next + 1 < f.names.length ? { file: current, test: next + 1 } : { file: current + 1, test: 0 };
    } else start = { file: current + 1, test: 0 };
  }
  const tests: ProjectTest[] = [];
  const loadErrors: Array<{ file: string; error: string }> = [];
  testFiles.forEach((file, fi) => {
    const f = perFile[fi]!;
    if (f.loadError) loadErrors.push({ file, error: f.loadError });
    (f.names ?? []).forEach((name, ti) => {
      const r = f.tests.get(ti);
      // A test that never reported (it can only be one skipped after a crash) fails: never a pass.
      tests.push({ file, name, ok: r?.ok === true, error: r ? (r.ok ? null : r.error || 'failed') : 'not run (the sandbox crashed)', ms: r?.ms ?? 0 });
    });
  });
  const passed = tests.filter((t) => t.ok).length;
  return { tests, loadErrors, passed, total: tests.length, log, crashed: null };
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
