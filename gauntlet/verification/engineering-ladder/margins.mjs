// How far over the limit the slow designs are: runs a solution on the large hidden tests of each level in the real
// sandbox with a generous time limit (default 60 s) and prints each time next to the level's limit.
//   node margins.mjs ./solutions/naive.js [limitMs] [L04 L05 ...]
import fs from 'node:fs';
import { runCodeTests } from '../../src/scoring/code-sandbox.ts';
const [file, to = '60000', ...ids] = process.argv.slice(2);
const TEST = JSON.parse(fs.readFileSync(new URL('../../tests/coding/engineering-ladder.json', import.meta.url), 'utf8'));
const labels = JSON.parse(fs.readFileSync(new URL('./labels.json', import.meta.url), 'utf8'));
const code = fs.readFileSync(new URL(file, import.meta.url), 'utf8');
for (const c of TEST.cases) {
  if (ids.length && !ids.includes(c.id)) continue;
  const big = c.expected.tests.map((t, i) => [t, i]).filter(([t]) => t.args[0].length >= 2000);
  if (!big.length) continue;
  const r = await runCodeTests(code, c.expected.functionName, big.map(([t]) => t), Number(to));
  r.items.forEach((it, k) => console.log(c.id, `limit ${c.scorer.timeoutMs} ms`, it.passed ? 'ok  ' : 'FAIL', String(it.detail).slice(0, 40).padEnd(28), labels[c.id][big[k][1]]));
}
