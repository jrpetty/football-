import { spawn } from 'node:child_process';
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
}

/** Deep equality for JSON values with a relative numeric tolerance. */
export function jsonEqual(a: unknown, b: unknown, tol = 1e-6): boolean {
  if (typeof a === 'number' && typeof b === 'number') {
    if (Number.isNaN(a) || Number.isNaN(b)) return false;
    return a === b || Math.abs(a - b) <= tol * Math.max(1, Math.abs(b));
  }
  if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') return a === b;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  if (Array.isArray(a) && Array.isArray(b)) return a.length === b.length && a.every((v, i) => jsonEqual(v, b[i], tol));
  const ka = Object.keys(a as object);
  const kb = Object.keys(b as object);
  if (ka.length !== kb.length) return false;
  return ka.every((k) => Object.prototype.hasOwnProperty.call(b, k) && jsonEqual((a as Record<string, unknown>)[k], (b as Record<string, unknown>)[k], tol));
}

function preview(v: unknown): string {
  const s = typeof v === 'string' ? v : JSON.stringify(v);
  if (s === undefined) return 'undefined';
  return s.length > 80 ? s.slice(0, 77) + '…' : s;
}

/**
 * Execute model-written JavaScript against unit tests in an isolated child
 * process (Node permission model, 256 MB heap, per-test VM timeout, hard kill).
 */
export async function runCodeTests(code: string, functionName: string, tests: CodeTest[], perTestTimeoutMs = 2000, signal?: AbortSignal): Promise<CodeRunResult> {
  const hardLimitMs = 10_000 + tests.length * (perTestTimeoutMs + 250);
  const child = spawn(
    process.execPath,
    ['--permission', `--allow-fs-read=${WORKER}`, '--max-old-space-size=256', '--stack-size=4000', WORKER],
    { stdio: ['pipe', 'pipe', 'pipe'], env: {} },
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
    };
  }

  let passed = 0;
  const items: ScoreBreakdownItem[] = tests.map((t, i) => {
    const r = parsed!.results[i];
    const label = `test ${i + 1}: ${functionName}(${preview(t.args).replace(/^\[|\]$/g, '')})`;
    if (!r || !r.ok) return { label, passed: false, detail: r?.error ?? 'no result' };
    const actual = r.output === '__undefined__' ? undefined : JSON.parse(r.output!);
    const ok = jsonEqual(actual, t.expected);
    if (ok) passed++;
    return { label, passed: ok, detail: ok ? `${(r.ms ?? 0).toFixed(1)} ms` : `expected ${preview(t.expected)}, got ${preview(actual)}` };
  });
  return { passed, total: tests.length, items, loadError: parsed.loadError };
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
