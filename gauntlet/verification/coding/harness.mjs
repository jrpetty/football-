// Local stand-in for the Gauntlet code-js scorer: run `code` against tests in a fresh VM context.
// usage: node harness.mjs <tests.json> <solution.js> [timeoutMs]
import vm from 'node:vm';
import fs from 'node:fs';

export function deepEqual(a, b) {
  if (typeof a === 'number' && typeof b === 'number') {
    if (Number.isNaN(a) && Number.isNaN(b)) return true;
    return Math.abs(a - b) <= 1e-6 * Math.max(1, Math.abs(b)) || Math.abs(a - b) <= 1e-6;
  }
  if (a === null || b === null || typeof a !== 'object' || typeof b !== 'object') return a === b;
  if (Array.isArray(a) !== Array.isArray(b)) return false;
  if (Array.isArray(a)) {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) if (!deepEqual(a[i], b[i])) return false;
    return true;
  }
  const ka = Object.keys(a).sort(), kb = Object.keys(b).sort();
  if (ka.length !== kb.length) return false;
  for (let i = 0; i < ka.length; i++) if (ka[i] !== kb[i] || !deepEqual(a[ka[i]], b[kb[i]])) return false;
  return true;
}

export function runTests(code, functionName, tests, timeoutMs = 2000, verbose = false) {
  let passed = 0;
  const results = [];
  for (let i = 0; i < tests.length; i++) {
    const t = tests[i];
    const ctx = vm.createContext({});
    let ok = false, got, err, ms = 0;
    try {
      vm.runInContext(code, ctx, { timeout: timeoutMs });
      ctx.__args = JSON.parse(JSON.stringify(t.args));
      const t0 = performance.now();
      const out = vm.runInContext(`JSON.stringify(${functionName}(...__args))`, ctx, { timeout: timeoutMs });
      ms = performance.now() - t0;
      got = out === undefined ? undefined : JSON.parse(out);
      ok = deepEqual(got, t.expected);
    } catch (e) {
      err = String(e && e.message || e).slice(0, 120);
    }
    if (ok) passed++;
    results.push({ i, ok, ms: Math.round(ms), err });
    if (verbose && !ok) {
      const g = JSON.stringify(got);
      console.log(`  test ${i} FAIL ${err ? 'ERR ' + err : ''} got=${g && g.slice(0, 200)} exp=${JSON.stringify(t.expected).slice(0, 200)}`);
    }
  }
  return { passed, total: tests.length, results };
}

if (process.argv[1].endsWith('harness.mjs') && process.argv[2]) {
  const spec = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
  const code = fs.readFileSync(process.argv[3], 'utf8');
  const r = runTests(code, spec.functionName, spec.tests, Number(process.argv[4] || 2000), true);
  console.log(`${r.passed}/${r.total}`, r.results.map((x) => (x.ok ? '.' : 'X') + x.ms).join(' '));
}
