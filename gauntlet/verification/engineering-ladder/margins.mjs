// How far over the limit the slower designs are: runs a solution on the large generated tests of each level in the
// real sandbox with a limit of 3 x the level's own, and prints each time next to the level's limit.
//   node margins.mjs ./solutions/plain.js [L05 L06 ...]
import fs from 'node:fs';
import { runCodeTests } from '../../src/scoring/code-sandbox.ts';
const [file, ...ids] = process.argv.slice(2);
const TEST = JSON.parse(fs.readFileSync(new URL('../../tests/coding/engineering-ladder.json', import.meta.url), 'utf8'));
const labels = JSON.parse(fs.readFileSync(new URL('./labels.json', import.meta.url), 'utf8'));
const code = fs.readFileSync(new URL(file, import.meta.url), 'utf8');
for (const c of TEST.cases) {
  if (ids.length && !ids.includes(c.id)) continue;
  const big = c.expected.tests.map((t, i) => [t, i]).filter(([t]) => t.args[0].generate && t.args[0].generate.n >= 100000);
  if (!big.length) continue;
  const r = await runCodeTests(code, c.expected.functionName, big.map(([t]) => t), 3 * c.scorer.timeoutMs);
  r.items.forEach((it, k) => console.log(c.id, `limit ${c.scorer.timeoutMs / 1000} s`, it.passed ? 'ok  ' : 'FAIL', String(it.detail).slice(0, 40).padEnd(30), labels[c.id][big[k][1]].slice(0, 60)));
}
