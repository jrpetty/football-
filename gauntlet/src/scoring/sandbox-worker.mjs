// Gauntlet code sandbox worker.
//
// Runs in a separate Node process started with the permission model
// (--permission, read access to this file only, no child processes, no
// addons, code generation from strings disabled) and a small heap. The
// model-generated code runs in a worker thread of that process, inside a
// fresh VM context with no require/import/process, and every call has a
// hard timeout.
//
// Why a worker thread: its stack size is set explicitly (STACK_MB), so deep
// recursion hits the same limit (a catchable RangeError) on every operating
// system. A main thread's stack is fixed by the OS (1 MB on Windows, 8 MB on
// Linux), and raising V8's limit above it crashes the process instead.
//
// Protocol: input arrives as JSON on stdin ({ code, functionName, tests,
// perTestTimeoutMs, from }); results go to stdout as one JSON line per event:
//   {"loadError": string|null}           once, after the code was loaded
//   {"i": n, "ok": ..., "output"|"error", "ms"}   one per test, in order
// If the process dies mid-way (a crash, out of memory), the host keeps the
// lines it got, fails the test that was running and starts again after it.
import vm from 'node:vm';
import { Worker, isMainThread, parentPort, workerData } from 'node:worker_threads';

/** Stack size of the thread that runs the solution, in MB (identical on every OS). */
const STACK_MB = 4;

if (isMainThread) {
  const input = await new Promise((resolve) => {
    let data = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (c) => (data += c));
    process.stdin.on('end', () => resolve(JSON.parse(data)));
  });
  const worker = new Worker(new URL(import.meta.url), {
    workerData: input,
    resourceLimits: { stackSizeMb: STACK_MB, maxOldGenerationSizeMb: 256 },
    stdout: true,
    stderr: true,
  });
  let done = false;
  worker.on('message', (m) => {
    if (m === 'done') {
      done = true;
      return;
    }
    process.stdout.write(JSON.stringify(m) + '\n');
  });
  worker.on('error', (e) => process.stdout.write(JSON.stringify({ crash: String((e && e.message) || e).slice(0, 200) }) + '\n'));
  worker.on('exit', () => {
    // A worker that died before finishing (e.g. out of memory) ends the process with a failure code:
    // the host fails the running test and continues after it. (exitCode, not exit(): pending output is flushed.)
    process.exitCode = done ? 0 : 70;
  });
} else {
  runTests(workerData);
}

function sanitize(src) {
  return src
    .replace(/^\s*export\s+default\s+/gm, '')
    .replace(/^\s*export\s+(?=(async\s+)?function|const|let|var|class)/gm, '')
    .replace(/^\s*import\s+[^;\n]*;?\s*$/gm, '');
}

function runTests({ code, functionName, tests, perTestTimeoutMs, from = 0 }) {
  // Everything the solution can touch is created inside the context: no host-realm object or function is
  // handed in (a host function's .constructor is the host's Function, a classic way out of a vm context).
  // The process also runs with --disallow-code-generation-from-strings, so even a host Function found some
  // other way cannot compile code.
  const context = vm.createContext(Object.create(null), { codeGeneration: { strings: false, wasm: false } });
  new vm.Script(
    'globalThis.console = { log() {}, error() {}, warn() {}, info() {}, debug() {} };\n' +
      'globalThis.module = { exports: {} };\n' +
      'globalThis.exports = globalThis.module.exports;\n' +
      // Results are serialised with markers for values JSON would silently turn into null (Infinity, NaN,
      // undefined in an array), so they can never match an expected null.
      "globalThis.__serialize = (v) => JSON.stringify(v, function (k, x) { if (typeof x === 'number' && !Number.isFinite(x)) return { __gauntletNonFinite__: String(x) }; if (x === undefined && Array.isArray(this)) return { __gauntletUndefined__: true }; return x; });",
  ).runInContext(context);

  let loadError = null;
  try {
    new vm.Script(sanitize(code), { filename: 'solution.js' }).runInContext(context, { timeout: 5000 });
    const resolveFn = new vm.Script(
      `(() => { try { if (typeof ${functionName} === 'function') return ${functionName}; } catch (e) {}\n` +
        `  const m = module.exports; if (typeof m === 'function') return m; if (m && typeof m.${functionName} === 'function') return m.${functionName};\n` +
        `  return undefined; })()`,
    ).runInContext(context, { timeout: 1000 });
    if (typeof resolveFn !== 'function') loadError = `Function "${functionName}" is not defined`;
    else context.__fn = resolveFn;
  } catch (e) {
    loadError = `Code failed to load: ${String(e && e.message ? e.message : e).slice(0, 300)}`;
  }
  parentPort.postMessage({ loadError });

  const call = new vm.Script('__serialize(__fn(...JSON.parse(__args)))');
  for (let i = from; i < tests.length; i++) {
    const t = tests[i];
    if (loadError) {
      parentPort.postMessage({ i, ok: false, error: loadError });
      continue;
    }
    context.__args = JSON.stringify(t.args);
    const started = process.hrtime.bigint();
    try {
      const out = call.runInContext(context, { timeout: perTestTimeoutMs });
      const ms = Number(process.hrtime.bigint() - started) / 1e6;
      parentPort.postMessage({ i, ok: true, output: typeof out === 'string' ? out : out === undefined ? '__undefined__' : '__invalid__', ms });
    } catch (e) {
      const ms = Number(process.hrtime.bigint() - started) / 1e6;
      let msg;
      try {
        msg = String(e && e.message ? e.message : e);
      } catch {
        msg = 'threw an unreadable error';
      }
      parentPort.postMessage({ i, ok: false, error: /timed out/i.test(msg) ? `Timed out after ${perTestTimeoutMs} ms` : /call stack/i.test(msg) ? `Stack overflow (${STACK_MB} MB stack): ${msg.slice(0, 200)}` : msg.slice(0, 300), ms });
    }
  }
  parentPort.postMessage('done');
}
