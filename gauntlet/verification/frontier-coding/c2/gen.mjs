// Hidden tests for c2. Small/medium cases are verified by brute.py (quarter-triangle counting); large-coordinate cases by
// slab.py (generic Fraction slab integration) and by scaling identities; every case also by typical.js (generic BigInt fractions).
import fs from 'node:fs';
import { rng, load, python } from '../lib.mjs';
import { randomPolygon, polyomino, cutCorners, transform, map45, isSimple, isOcto } from './shapes.mjs';
const ref = load('./c2/ref.js', 'coverageArea');
const typ = load('./c2/typical.js', 'coverageArea');
const R = rng(20260924);
const T = []; // {label, args, verify: 'brute'|'slab', scaleFrom?}
const add = (label, polys, k, verify) => {
  for (const p of polys) if (!isSimple(p) || !isOcto(p)) throw new Error('invalid polygon in ' + label);
  for (const p of polys) for (const [x, y] of p) if (Math.abs(x) > 1e9 || Math.abs(y) > 1e9) throw new Error('coordinate out of bounds in ' + label);
  T.push({ label, args: [polys, k], verify });
};
const sq = (x, y, s) => [[x, y], [x + s, y], [x + s, y + s], [x, y + s]];
// ---- hand-made cases ----
add('two crossing right triangles (quarter units)', [[[0, 0], [5, 0], [0, 5]], [[1, -1], [4, 2], [1, 5]]], 1, 'brute');
add('two crossing right triangles, overlap only', [[[0, 0], [5, 0], [0, 5]], [[1, -1], [4, 2], [1, 5]]], 2, 'brute');
add('diamond grid: overlap of two diamonds offset by one', [[[0, 0], [3, 3], [0, 6], [-3, 3]], [[1, 0], [4, 3], [1, 6], [-2, 3]]], 2, 'brute');
add('nested: diamond inside an octagon, k=2', [[[2, 0], [8, 0], [10, 2], [10, 8], [8, 10], [2, 10], [0, 8], [0, 2]], [[5, 2], [8, 5], [5, 8], [2, 5]]], 2, 'brute');
add('nested: diamond inside an octagon, k=1', [[[2, 0], [8, 0], [10, 2], [10, 8], [8, 10], [2, 10], [0, 8], [0, 2]], [[5, 2], [8, 5], [5, 8], [2, 5]]], 1, 'brute');
add('coincident polygons with opposite orientation', [[[0, 0], [6, 0], [6, 2], [4, 4], [0, 4]], [[0, 4], [4, 4], [6, 2], [6, 0], [0, 0]], [[1, 1], [2, 1], [2, 2], [1, 2]]], 2, 'brute');
add('squares sharing an edge, k=1', [sq(0, 0, 3), sq(3, 0, 3), sq(0, 3, 3)], 1, 'brute');
add('squares sharing an edge, k=2 is empty', [sq(0, 0, 3), sq(3, 0, 3), sq(0, 3, 3)], 2, 'brute');
add('squares touching only at corners', [sq(0, 0, 2), sq(2, 2, 2), sq(4, 0, 2), sq(2, -2, 2)], 1, 'brute');
add('collinear vertices, clockwise order', [[[0, 0], [0, 2], [0, 5], [3, 5], [5, 3], [5, 0], [2, 0]], [[2, 1], [6, 1], [6, 2], [4, 4], [2, 4]]], 1, 'brute');
add('non-convex polygon with a notch and cut corners', [[[0, 0], [9, 0], [9, 7], [8, 8], [6, 8], [6, 3], [3, 3], [3, 8], [1, 8], [0, 7]], [[2, 2], [7, 2], [7, 6], [2, 6]]], 1, 'brute');
add('non-convex polygon with a notch, k=2', [[[0, 0], [9, 0], [9, 7], [8, 8], [6, 8], [6, 3], [3, 3], [3, 8], [1, 8], [0, 7]], [[2, 2], [7, 2], [7, 6], [2, 6]]], 2, 'brute');
add('five copies of a triangle, k=5', [0, 1, 2, 3, 4].map((i) => (i % 2 ? [[0, 0], [7, 0], [0, 7]] : [[0, 7], [7, 0], [0, 0]])), 5, 'brute');
add('three diamonds, region covered by all three', [[[0, 0], [4, 4], [0, 8], [-4, 4]], [[2, 1], [5, 4], [2, 7], [-1, 4]], [[1, 3], [3, 5], [1, 7], [-1, 5]]], 3, 'brute');
add('huge square: area needs more than 53 bits', [[[-1000000000, -1000000000], [1000000000, -1000000000], [1000000000, 1000000000], [-1000000000, 1000000000]]], 1, 'slab');
add('huge right triangle with odd legs', [[[-999999999, -999999998], [999999998, -999999998], [-999999999, 999999999]]], 1, 'slab');
add('huge diamond and rectangle overlap', [[[1, -999999999], [1000000000, 0], [1, 999999999], [-999999998, 0]], [[-7, -3], [999999999, -3], [999999999, 1000000000], [-7, 1000000000]]], 2, 'slab');
add('huge diamond and rectangle union', [[[1, -999999999], [1000000000, 0], [1, 999999999], [-999999998, 0]], [[-7, -3], [999999999, -3], [999999999, 1000000000], [-7, 1000000000]]], 1, 'slab');
// ---- random medium cases (small coordinates, verified by brute force) ----
const mediums = []; const mediumIdx = [];
for (let i = 0; i < 6; i++) {
  const m = [4, 6, 8, 10, 7, 12][i];
  const k = [1, 2, 3, 2, 4, 3][i];
  let polys;
  do { polys = []; for (let j = 0; j < m; j++) polys.push(randomPolygon(R, { maxGrid: 5, scaleMax: 2, span: 2 })); } while (ref(polys, k) === '0/1' || ref(polys, k) === ref(polys, k + 1));
  mediums.push([polys, k]); mediumIdx.push(T.length);
  add(`random medium m=${m} k=${k}`, polys, k, 'brute');
}
// ---- scaled copies of the medium cases (coordinates near 1e9): area scales by s^2 ----
for (let i = 0; i < 4; i++) {
  const [polys, k] = mediums[i];
  const maxAbs = Math.max(...polys.flat(2).map(Math.abs));
  const s = Math.floor(940000000 / maxAbs) - [0, 1, 2, 3][i];
  const ox = R.int(-50000000, 50000000), oy = R.int(-50000000, 50000000);
  add(`medium ${i + 1} scaled by ${s}`, polys.map((p) => p.map(([x, y]) => [x * s + ox, y * s + oy])), k, 'slab');
}
// ---- large random cases ----
function bigPoly(grid, scale, span) {
  for (;;) {
    let p = polyomino(R, grid, grid, R.int(Math.floor(grid * grid / 3), Math.floor(grid * grid * 0.8)));
    p = cutCorners(p.map(([x, y]) => [x * 3, y * 3]), R, 0.6, 3);
    if (R.chance(0.35)) p = map45(p);
    p = transform(p, R, scale, R.int(-span, span), R.int(-span, span));
    if (isSimple(p) && isOcto(p)) return p;
  }
}
for (const [m, grid, k, spanMul] of [[25, 7, 1, 1], [30, 8, 3, 0.6], [45, 8, 2, 1], [60, 8, 5, 0.5], [70, 8, 1, 1], [70, 8, 8, 0.4]]) {
  const polys = []; for (let j = 0; j < m; j++) polys.push(bigPoly(grid, R.int(9000000, 12000000), Math.floor(150000000 * spanMul)));
  add(`large m=${m} (${polys.reduce((s, p) => s + p.length, 0)} vertices) k=${k}`, polys, k, 'slab');
}
const tests = T.map((t) => ({ args: t.args, expected: ref(...t.args) }));
let bad = 0;
const bi = T.map((t, i) => i).filter((i) => T[i].verify === 'brute');
const bres = python('./c2/brute.py', bi.map((i) => T[i].args));
bi.forEach((i, j) => { if (bres[j] !== tests[i].expected) { bad++; console.log('BRUTE MISMATCH', T[i].label, tests[i].expected, bres[j]); } });
const si = T.map((t, i) => i).filter((i) => T[i].verify === 'slab' && !T[i].label.startsWith('large'));
const sres = python('./c2/slab.py', si.map((i) => T[i].args));
si.forEach((i, j) => { if (sres[j] !== tests[i].expected) { bad++; console.log('SLAB MISMATCH', T[i].label, tests[i].expected, sres[j]); } });
// scaling identity for the scaled mediums
for (let i = 0; i < 4; i++) {
  const idx = T.findIndex((t) => t.label.startsWith(`medium ${i + 1} scaled`));
  const s = BigInt(T[idx].label.split(' ').pop());
  const [pn, pd] = tests[mediumIdx[i]].expected.split('/').map(BigInt);
  const [qn, qd] = tests[idx].expected.split('/').map(BigInt);
  if (qn * pd !== pn * s * s * qd) { bad++; console.log('SCALING MISMATCH', i); }
}
T.forEach((t, i) => { const y = typ(...t.args); if (y !== tests[i].expected) { bad++; console.log('TYPICAL MISMATCH', t.label, tests[i].expected, y); } });
T.forEach((t, i) => console.log(String(i + 1).padStart(2), t.label.padEnd(58), tests[i].expected));
console.log(bad ? `${bad} PROBLEMS` : `all ${tests.length} verified (brute/slab/scaling/typical); large cases checked by slab_large step`);
fs.writeFileSync(new URL('./tests.json', import.meta.url), JSON.stringify({ functionName: 'coverageArea', tests }));
fs.writeFileSync(new URL('./labels.json', import.meta.url), JSON.stringify(T.map((t) => t.label)));
console.log('json bytes', JSON.stringify(tests).length);
