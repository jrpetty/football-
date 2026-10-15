import { spawn } from 'node:child_process';
import { availableParallelism } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import type { ScoreBreakdownItem } from '../core/types.ts';

const WORKER = join(dirname(fileURLToPath(import.meta.url)), 'sandbox-worker.mjs');

export interface CodeTest {
  args: unknown[];
  expected: unknown;
}

export interface CodeRunResult {
  passed: number;
  total: number;
  items: ScoreBreakdownItem[];
  loadError: string | null;
  /** Each test's return value as serialised by the sandbox (null when the call failed). */
  outputs: Array<string | null>;
}

/**
 * Deep equality for JSON values. Numbers must be equal (0 and -0 count as equal); `tol` allows a relative
 * difference, but the coding tests use exact comparison (tol 0): their specifications define exact results,
 * and a relative tolerance would wave through wrong large integers (an off-by-one 32-bit register, a
 * coordinate off by thousands at 10^12) and floating-point results whose last bits are the point of the test.
 */
export function jsonEqual(a: unknown, b: unknown, tol = 0): boolean {
  if (typeof a === 'number' && typeof b === 'number') {
    if (Number.isNaN(a) || Number.isNaN(b)) return false;
    return a === b || (tol > 0 && Math.abs(a - b) <= tol * Math.max(1, Math.abs(b)));
  }
  if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') return a === b;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  if (Array.isArray(a) && Array.isArray(b)) {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) if (!jsonEqual(a[i], b[i], tol)) return false;
    return true;
  }
  const ka = Object.keys(a as object);
  const kb = Object.keys(b as object);
  if (ka.length !== kb.length) return false;
  return ka.every((k) => Object.prototype.hasOwnProperty.call(b, k) && jsonEqual((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k], tol));
}

/** A value the sandbox marked as `undefined` inside an array (never equal to anything expected). */
const UNDEFINED_MARK = Object.freeze({ toJSON: () => '«undefined»' });

/** Parse a sandbox result, restoring Infinity/NaN and in-array undefined from their markers. */
function parseOutput(output: string): unknown {
  return JSON.parse(output, (_k, v) => {
    if (v && typeof v === 'object' && !Array.isArray(v)) {
      const keys = Object.keys(v);
      if (keys.length === 1 && keys[0] === '__gauntletNonFinite__') return Number((v as Record<string, string>).__gauntletNonFinite__);
      if (keys.length === 1 && keys[0] === '__gauntletUndefined__') return UNDEFINED_MARK;
    }
    return v;
  });
}

function preview(v: unknown): string {
  const s = typeof v === 'string' ? v : typeof v === 'number' && !Number.isFinite(v) ? String(v) : JSON.stringify(v, (_k, x) => (typeof x === 'number' && !Number.isFinite(x) ? String(x) : x));
  if (s === undefined) return 'undefined';
  return s.length > 80 ? s.slice(0, 77) + '…' : s;
}

/**
 * Argument lists of the example calls shown in a coding prompt, e.g. `charAt("ab3(c2(d))e", 5)  // returns "c"`,
 * as canonical JSON (the same form as `JSON.stringify(test.args)`). Examples that are not plain JSON literals are skipped.
 */
export function promptExampleArgs(taskText: string, functionName: string): Set<string> {
  const out = new Set<string>();
  const esc = functionName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  const re = new RegExp(`\\b${esc}\\(`, 'g');
  let m: RegExpExecArray | null;
  while ((m = re.exec(taskText)) !== null) {
    const start = m.index + m[0].length;
    let depth = 1;
    let inString = false;
    let i = start;
    for (; i < taskText.length && depth > 0; i++) {
      const ch = taskText[i];
      if (inString) {
        if (ch === '\\') i++;
        else if (ch === '"') inString = false;
      } else if (ch === '"') inString = true;
      else if (ch === '(' || ch === '[' || ch === '{') depth++;
      else if (ch === ')' || ch === ']' || ch === '}') depth--;
    }
    if (depth !== 0) continue;
    try {
      out.add(JSON.stringify(JSON.parse(`[${taskText.slice(start, i - 1)}]`)));
    } catch {
      /* the signature line or prose, not an example call */
    }
  }
  return out;
}

/**
 * Anti-gaming gate for coding tests. Credit needs at least one passed hidden test that a trivial program
 * could not have passed: a test that is not one of the prompt's worked examples and whose expected value is
 * neither the solution's most frequent return value nor its first argument returned unchanged. Without one,
 * the code is a stub (`return null`, `return false`), an echo of its input, or a table of the visible
 * examples, and it scores 0. Every real attempt that gets one genuine hidden test right keeps its normal
 * fraction-of-tests score. Returns null when the gate passes, else the reason.
 */
export function trivialSolutionReason(tests: CodeTest[], run: CodeRunResult, exampleArgs: Set<string>): string | null {
  if (run.passed === 0) return null;
  const counts = new Map<string, number>();
  for (const o of run.outputs) if (o !== null) counts.set(o, (counts.get(o) ?? 0) + 1);
  const modal = [...counts.entries()].sort((a, b) => b[1] - a[1])[0]?.[0];
  const modalValue = modal === undefined || modal === '__undefined__' || modal === '__invalid__' ? Symbol('none') : parseOutput(modal);
  let informative = 0;
  for (let i = 0; i < tests.length; i++) {
    const t = tests[i]!;
    if (exampleArgs.has(JSON.stringify(t.args))) continue;
    if (jsonEqual(t.expected, modalValue)) continue;
    if (t.args.length > 0 && jsonEqual(t.expected, t.args[0])) continue;
    informative++;
    if (run.items[i]?.passed) return null;
  }
  if (informative === 0) return null;
  return modal !== undefined && (counts.get(modal) ?? 0) === run.outputs.length
    ? 'returns the same value for every input'
    : 'passes only the worked examples or tests that a constant or an unchanged input also passes';
}

/**
 * Execute model-written JavaScript against unit tests in an isolated child
 * process (Node permission model, 256 MB heap, per-test VM timeout, hard kill).
 */
export async function runCodeTests(code: string, functionName: string, tests: CodeTest[], perTestTimeoutMs = 2000, signal?: AbortSignal): Promise<CodeRunResult> {
  // Per-test time limits are wall-clock, so sandboxes running side by side (the engine runs several jobs at
  // once) would slow each other down and the same code could pass on one run and time out on the next.
  // At most half the CPU cores run hidden unit tests at a time.
  await acquireSandboxSlot();
  try {
    if (signal?.aborted) {
      return { passed: 0, total: tests.length, loadError: 'Sandbox aborted', items: tests.map((_, i) => ({ label: `test ${i + 1}`, passed: false, detail: 'aborted' })), outputs: tests.map(() => null) };
    }
    return await runCodeTestsNow(code, functionName, tests, perTestTimeoutMs, signal);
  } finally {
    releaseSandboxSlot();
  }
}

const SANDBOX_SLOTS = Math.max(1, Math.floor(availableParallelism() / 2));
let sandboxesRunning = 0;
const sandboxQueue: Array<() => void> = [];

function acquireSandboxSlot(): Promise<void> {
  if (sandboxesRunning < SANDBOX_SLOTS) {
    sandboxesRunning++;
    return Promise.resolve();
  }
  return new Promise((resolve) => sandboxQueue.push(resolve));
}

function releaseSandboxSlot(): void {
  const next = sandboxQueue.shift();
  if (next) next();
  else sandboxesRunning--;
}

async function runCodeTestsNow(code: string, functionName: string, tests: CodeTest[], perTestTimeoutMs: number, signal?: AbortSignal): Promise<CodeRunResult> {
  const hardLimitMs = 10_000 + tests.length * (perTestTimeoutMs + 250);
  const child = spawn(
    process.execPath,
    ['--permission', `--allow-fs-read=${WORKER}`, '--disallow-code-generation-from-strings', '--max-old-space-size=256', '--stack-size=4000', WORKER],
    { stdio: ['pipe', 'pipe', 'pipe'], env: {}, windowsHide: true },
  );
  let stdout = '';
  let stderr = '';
  child.stdout.setEncoding('utf8').on('data', (d: string) => (stdout += d));
  child.stderr.setEncoding('utf8').on('data', (d: string) => (stderr += d.slice(0, 4000)));
  const killer = setTimeout(() => child.kill('SIGKILL'), hardLimitMs);
  const onAbort = () => child.kill('SIGKILL');
  signal?.addEventListener('abort', onAbort, { once: true });
  child.stdin.on('error', () => {});
  child.stdin.end(JSON.stringify({ code, functionName, tests: tests.map((t) => ({ args: t.args })), perTestTimeoutMs }));
  const exitCode: number | null = await new Promise((resolve) => child.on('close', resolve));
  clearTimeout(killer);
  signal?.removeEventListener('abort', onAbort);

  let parsed: { loadError: string | null; results: Array<{ ok: boolean; output?: string; error?: string; ms?: number }> } | null = null;
  try {
    parsed = JSON.parse(stdout);
  } catch {
    parsed = null;
  }
  if (!parsed) {
    const reason = exitCode === null ? 'killed (time or memory limit)' : `crashed (exit ${exitCode}) ${stderr.split('\n').find((l) => /Error/.test(l)) ?? ''}`;
    return {
      passed: 0,
      total: tests.length,
      loadError: `Sandbox ${reason}`.trim(),
      items: tests.map((_, i) => ({ label: `test ${i + 1}`, passed: false, detail: `sandbox ${reason}` })),
      outputs: tests.map(() => null),
    };
  }

  let passed = 0;
  const outputs: Array<string | null> = [];
  const items: ScoreBreakdownItem[] = tests.map((t, i) => {
    const r = parsed!.results?.[i];
    const label = `test ${i + 1}: ${functionName}(${preview(t.args).replace(/^\[|\]$/g, '')})`;
    outputs.push(r?.ok && typeof r.output === 'string' ? r.output : null);
    if (!r || !r.ok) return { label, passed: false, detail: r?.error ?? 'no result' };
    let actual: unknown;
    try {
      actual = r.output === '__undefined__' ? undefined : parseOutput(String(r.output));
    } catch {
      return { label, passed: false, detail: 'the return value could not be read' };
    }
    const ok = jsonEqual(actual, t.expected);
    if (ok) passed++;
    return { label, passed: ok, detail: ok ? `${(r.ms ?? 0).toFixed(1)} ms` : `expected ${preview(t.expected)}, got ${preview(actual)}` };
  });
  return { passed, total: tests.length, items, loadError: parsed.loadError ?? null, outputs };
}

/**
 * Run any sandbox worker script (e.g. the "Fix the Bug" project worker) with
 * the same isolation as `runCodeTests`: Node permission model with read access
 * to the worker file only, code generation from strings disabled, a 256 MB
 * heap, an empty environment and a hard kill after `hardLimitMs`.
 * Returns the raw stdout/stderr; the caller parses its own protocol.
 */
export async function runSandboxWorker(
  workerFile: string,
  input: unknown,
  opts: { hardLimitMs: number; signal?: AbortSignal },
): Promise<{ stdout: string; stderr: string; exitCode: number | null; killed: boolean }> {
  const child = spawn(
    process.execPath,
    ['--permission', `--allow-fs-read=${workerFile}`, '--disallow-code-generation-from-strings', '--max-old-space-size=256', '--stack-size=4000', workerFile],
    { stdio: ['pipe', 'pipe', 'pipe'], env: {}, windowsHide: true },
  );
  let stdout = '';
  let stderr = '';
  let killed = false;
  child.stdout.setEncoding('utf8').on('data', (d: string) => {
    if (stdout.length < 8_000_000) stdout += d;
  });
  child.stderr.setEncoding('utf8').on('data', (d: string) => {
    if (stderr.length < 4000) stderr += d.slice(0, 4000);
  });
  const kill = () => {
    killed = true;
    child.kill('SIGKILL');
  };
  const killer = setTimeout(kill, opts.hardLimitMs);
  opts.signal?.addEventListener('abort', kill, { once: true });
  child.stdin.on('error', () => {});
  child.stdin.end(JSON.stringify(input));
  const exitCode: number | null = await new Promise((resolve) => child.on('close', resolve));
  clearTimeout(killer);
  opts.signal?.removeEventListener('abort', kill);
  return { stdout, stderr, exitCode, killed };
}
