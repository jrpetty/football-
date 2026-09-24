// usage: node timecase.mjs <dir> <solution.js> [timeoutMs]   -- runs dir/tests.json in the REAL sandbox worker, prints per-test ms and pass/fail
import fs from 'node:fs';
import { runRaw } from './sbx.mjs';
import { deepEqual } from './lib.mjs';
const [dir, sol, to] = process.argv.slice(2);
const spec = JSON.parse(fs.readFileSync(`${dir}/tests.json`, 'utf8'));
const labels = fs.existsSync(`${dir}/labels.json`) ? JSON.parse(fs.readFileSync(`${dir}/labels.json`, 'utf8')) : [];
const code = fs.readFileSync(sol, 'utf8');
const timeout = Number(to || 2000);
const r = await runRaw(code, spec.functionName, spec.tests.map((t) => t.args), timeout);
if (r.loadError) console.log('LOAD ERROR', r.loadError);
let pass = 0, maxMs = 0;
r.results.forEach((x, i) => {
  const ok = x.ok && deepEqual(x.output === '__undefined__' ? undefined : JSON.parse(x.output), spec.tests[i].expected);
  if (ok) pass++;
  maxMs = Math.max(maxMs, x.ms || 0);
  if (process.env.V || !ok) console.log(String(i + 1).padStart(3), ok ? 'ok  ' : 'FAIL', String((x.ms || 0).toFixed(0)).padStart(6), 'ms', (labels[i] || '').slice(0, 60), ok ? '' : (x.error || '').slice(0, 80));
});
console.log(`${sol}: ${pass}/${spec.tests.length} passed, max ${maxMs.toFixed(0)} ms (timeout ${timeout})`);
