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

const sandbox = {
  console: { log() {}, error() {}, warn() {}, info() {}, debug() {} },
  module: { exports: {} },
};
sandbox.exports = sandbox.module.exports;
const context = vm.createContext(sandbox, { codeGeneration: { strings: false, wasm: false } });

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

const call = new vm.Script('JSON.stringify(__fn(...JSON.parse(__args)))');
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
    results.push({ ok: true, output: out === undefined ? '__undefined__' : out, ms });
  } catch (e) {
    const ms = Number(process.hrtime.bigint() - started) / 1e6;
    const msg = String(e && e.message ? e.message : e);
    results.push({ ok: false, error: /timed out/i.test(msg) ? `Timed out after ${perTestTimeoutMs} ms` : msg.slice(0, 300), ms });
  }
}

process.stdout.write(JSON.stringify({ loadError, results }));
