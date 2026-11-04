// Tuning aid: runs solutions on one generated input per level in the REAL sandbox (60 s cap) and prints the times.
//   node calibrate.mjs <n> <q> <levels comma-separated> <variant 0|1|2> <solution files...>
import fs from 'node:fs';
import { runCodeTests } from '../../src/scoring/code-sandbox.ts';
import { largeMix } from './cases.mjs';
const [n, q, levels, variant, ...files] = process.argv.slice(2);
for (const level of levels.split(',').map(Number)) {
  const v = Number(variant);
  const input = { generate: { seed: 4242 + level, n: Number(n), lo: v === 2 ? -1000 : -1000000000, hi: v === 2 ? 1000 : 1000000000, q: Number(q), mix: largeMix(level, v) } };
  let first = null;
  for (const f of files) {
    const code = fs.readFileSync(new URL(f, import.meta.url), 'utf8');
    const t0 = Date.now();
    const r = await runCodeTests(code, 'runEngine', [{ args: [input], expected: first ?? [0, 0] }], 60000);
    const out = r.outputs[0];
    if (!first && out) first = JSON.parse(out);
    const ok = first && out === JSON.stringify(first);
    console.log(`L${String(level).padStart(2, '0')}`, f.padEnd(24), String(r.items[0].detail).slice(0, 40).padEnd(40), ok ? 'same' : 'DIFF', `${Date.now() - t0} ms wall`);
  }
}
