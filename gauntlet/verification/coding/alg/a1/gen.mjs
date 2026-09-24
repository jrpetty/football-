import fs from 'node:fs';
import { rng, load, check, sizeKB } from '../../lib.mjs';
const ref = load('a1/ref.js', 'coverageProfile');
const brute = load('a1/brute.js', 'coverageProfile');
const R = rng(101);
// cross-check on random small inputs
for (let t = 0; t < 3000; t++) {
  const n = R.int(0, 8); const iv = [];
  for (let i = 0; i < n; i++) { const s = R.int(-10, 15); const e = s + R.int(1, 8); iv.push([s, e, R.int(1, 4)]); }
  const a = JSON.stringify(ref(iv)), b = JSON.stringify(brute(iv));
  if (a !== b) { console.log('MISMATCH', JSON.stringify(iv), a, b); process.exit(1); }
}
const tests = [];
const add = (args) => tests.push({ args, expected: ref(...args) });
add([[]]);
add([[[1, 4, 3]]]);
add([[[0, 5, 2], [5, 9, 2]]]);                       // touching, equal weight -> merge
add([[[0, 5, 2], [5, 9, 3]]]);                       // touching, different weight
add([[[0, 10, 1], [2, 4, 5], [2, 4, 5]]]);          // nested + duplicate
add([[[0, 3, 1], [7, 9, 1]]]);                       // gap
add([[[0, 4, 2], [4, 6, 1], [4, 8, 1]]]);           // 2 | 2 -> merge after touching
add([[[-20, -5, 4], [-10, 3, 1], [3, 12, 5]]]);     // negatives, re-merge 5 | 5
add([[[1000000000000, 1000000000005, 7], [999999999998, 1000000000001, 7], [1000000000001, 1000000000003, 1]]]); // huge coords
add([[[5, 6, 1], [4, 5, 1], [3, 4, 1], [2, 3, 1]]]); // unsorted chain collapses to one
add([[[0, 100, 3], [10, 20, 2], [20, 30, 2], [30, 40, 2]]]);
{ // random medium
  const iv = []; for (let i = 0; i < 60; i++) { const s = R.int(-50, 50); iv.push([s, s + R.int(1, 30), R.int(1, 3)]); } add([iv]);
}
{ // large, huge coordinate range (rules out per-coordinate arrays)
  const iv = []; for (let i = 0; i < 1200; i++) { const s = R.int(-1e12, 1e12); iv.push([s, s + R.int(1, 2e9), R.int(1, 9)]); } add([iv]);
}
{ // large, many touching equal-weight blocks that must be merged
  const iv = []; let x = 0; for (let i = 0; i < 4000; i++) { const len = R.int(1, 5); iv.push([x, x + len, 2]); x += len; } for (let i = 0; i < 4000; i += 2) { const t = iv[i]; iv[i] = iv[3999 - i]; iv[3999 - i] = t; } add([iv]);
}
for (const t of tests.slice(0, 11)) { const b = brute(...t.args); if (JSON.stringify(b) !== JSON.stringify(t.expected) && t.args[0].every((x) => Math.abs(x[0]) < 1e6)) { console.log('brute mismatch on fixed test'); process.exit(1); } }
console.log('tests', tests.length, 'size KB', sizeKB(tests), 'last outputs', tests[12].expected.length, tests[13].expected.length);
fs.writeFileSync('a1/tests.json', JSON.stringify({ functionName: 'coverageProfile', tests }));
check('ref', fs.readFileSync('a1/ref.js', 'utf8'), 'coverageProfile', tests);
