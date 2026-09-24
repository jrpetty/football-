import fs from 'node:fs';
import { rng, load, check, sizeKB } from '../../lib.mjs';
const ref = load('a8/ref.js', 'unionArea');
const brute = load('a8/brute.js', 'unionArea');
const R = rng(808);
for (let t = 0; t < 3000; t++) {
  const n = R.int(0, 6); const rs = [];
  for (let i = 0; i < n; i++) { const x = R.int(-8, 8), y = R.int(-8, 8); rs.push([x, y, x + R.int(1, 8), y + R.int(1, 8)]); }
  if (ref(rs) !== brute(rs)) { console.log('MISMATCH', JSON.stringify(rs)); process.exit(1); }
}
const tests = [];
const add = (rs) => tests.push({ args: [rs], expected: ref(rs) });
add([]);
add([[0, 0, 2, 3]]);
add([[0, 0, 2, 2], [2, 0, 4, 2]]);                     // touching edges
add([[0, 0, 4, 4], [1, 1, 2, 2]]);                     // contained
add([[0, 0, 4, 4], [0, 0, 4, 4], [0, 0, 4, 4]]);       // identical
add([[0, 0, 3, 3], [1, 1, 4, 4], [2, 2, 5, 5]]);
add([[-5, -5, 5, 5], [-1, -10, 1, 10], [-10, -1, 10, 1]]); // plus shape over a square
add([[0, 0, 1, 1000000000], [0, 0, 1000000000, 1]]);    // long thin cross
add([[-1000000000, -1000000000, 1000000000, -999999999], [999999999, -1000000000, 1000000000, 1000000000]]);
add([[0, 0, 94906265, 94906265]]);                      // area just above 2^53 / 1000 region check (exact integer)
{ const rs = []; for (let i = 0; i < 30; i++) { const x = R.int(-50, 50), y = R.int(-50, 50); rs.push([x, y, x + R.int(1, 40), y + R.int(1, 40)]); } add(rs); }
{ // large: 1500 random rectangles with coordinates up to 1e6 (area < 2^53)
  const rs = []; for (let i = 0; i < 1500; i++) { const x = R.int(-1000000, 1000000), y = R.int(-1000000, 1000000); rs.push([x, y, x + R.int(1, 200000), y + R.int(1, 200000)]); } add(rs); }
{ // large: 1500 thin strips (rules out per-cell or dense-grid marking)
  const rs = []; for (let i = 0; i < 1500; i++) { if (i % 2) { const x = R.int(0, 3000000); rs.push([x, 0, x + R.int(1, 50), 3000000]); } else { const y = R.int(0, 3000000); rs.push([0, y, 3000000, y + R.int(1, 50)]); } } add(rs); }
for (const t of tests) { const area = t.expected; if (!Number.isSafeInteger(area)) { console.log('unsafe area', area); process.exit(1); } }
for (const t of tests.slice(0, 7)) if (brute(...t.args) !== t.expected) { console.log('brute mismatch'); process.exit(1); }
console.log('tests', tests.length, 'size KB', sizeKB(tests), JSON.stringify(tests.map((t) => t.expected)));
fs.writeFileSync('a8/tests.json', JSON.stringify({ functionName: 'unionArea', tests }));
check('ref', fs.readFileSync('a8/ref.js', 'utf8'), 'unionArea', tests);
