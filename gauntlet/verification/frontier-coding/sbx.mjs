// Run code in the REAL Gauntlet sandbox worker (same flags as src/scoring/code-sandbox.ts) and return raw results incl. timings.
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const WORKER = fileURLToPath(new URL('../../src/scoring/sandbox-worker.mjs', import.meta.url));
export function runRaw(code, functionName, argsList, perTestTimeoutMs = 2000) {
  return new Promise((resolve) => {
    const child = spawn(process.execPath, ['--permission', `--allow-fs-read=${WORKER}`, '--max-old-space-size=256', '--stack-size=4000', WORKER], { stdio: ['pipe', 'pipe', 'pipe'], env: {} });
    let out = '', err = '';
    child.stdout.setEncoding('utf8').on('data', (d) => (out += d));
    child.stderr.setEncoding('utf8').on('data', (d) => (err += d));
    child.stdin.end(JSON.stringify({ code, functionName, tests: argsList.map((a) => ({ args: a })), perTestTimeoutMs }));
    child.on('close', (codeExit) => {
      try { resolve(JSON.parse(out)); } catch { resolve({ loadError: 'crash ' + codeExit + ' ' + err.slice(0, 500), results: [] }); }
    });
  });
}
