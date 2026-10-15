// Gauntlet code sandbox worker.
//
// Runs in a separate Node process started with the permission model
// (--permission, read access to this file only, no child processes, no
// workers, no addons) and a small heap. Model-generated code executes inside
// a fresh VM context with no require/import/process, and every call has a
// hard timeout. Input arrives as JSON on stdin; results go to stdout as JSON.
import vm from 'node:vm';

const input = await new Promise((resolve) => {
  let data = '';
  process.stdin.setEncoding('utf8');
  process.stdin.on('data', (c) => (data += c));
  process.stdin.on('end', () => resolve(JSON.parse(data)));
});

const { code, functionName, tests, perTestTimeoutMs } = input;

function sanitize(src) {
  return src
    .replace(/^\s*export\s+default\s+/gm, '')
    .replace(/^\s*export\s+(?=(async\s+)?function|const|let|var|class)/gm, '')
    .replace(/^\s*import\s+[^;\n]*;?\s*$/gm, '');
}

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

const results = [];
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

const call = new vm.Script('__serialize(__fn(...JSON.parse(__args)))');
for (const t of tests) {
  if (loadError) {
    results.push({ ok: false, error: loadError });
    continue;
  }
  context.__args = JSON.stringify(t.args);
  const started = process.hrtime.bigint();
  try {
    const out = call.runInContext(context, { timeout: perTestTimeoutMs });
    const ms = Number(process.hrtime.bigint() - started) / 1e6;
    results.push({ ok: true, output: typeof out === 'string' ? out : out === undefined ? '__undefined__' : '__invalid__', ms });
  } catch (e) {
    const ms = Number(process.hrtime.bigint() - started) / 1e6;
    const msg = String(e && e.message ? e.message : e);
    results.push({ ok: false, error: /timed out/i.test(msg) ? `Timed out after ${perTestTimeoutMs} ms` : msg.slice(0, 300), ms });
  }
}

process.stdout.write(JSON.stringify({ loadError, results }));
