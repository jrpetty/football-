// Hidden tests for c5. Small cases verified by brute.py (all tower sets); every case by label.py (different DP);
// uniform unit-cost cases additionally by greedy.py.
import fs from 'node:fs';
import { rng, load, python } from '../lib.mjs';
import { randTree } from './tree.mjs';
const ref = load('./c5/ref.js', 'minTowerCost');
const R = rng(5150);
const T = [];
const add = (label, par, cost, reach, need, checks) => {
  const n = par.length;
  if (!(n >= 1 && cost.length === n && reach.length === n && need.length === n)) throw new Error('shape ' + label);
  if (par.filter((p) => p === -1).length !== 1) throw new Error('root ' + label);
  // tree check: every vertex reaches the root
  for (let i = 0; i < n; i++) { let v = i, k = 0; while (par[v] !== -1) { v = par[v]; if (++k > n) throw new Error('cycle ' + label); } }
  if (!cost.every((c) => Number.isInteger(c) && c >= 1 && c <= 1e6) || !reach.every((r) => Number.isInteger(r) && r >= 0 && r <= 20) || !/^[01]+$/.test(need)) throw new Error('values ' + label);
  T.push({ label: `${label} (n=${n})`, args: [par, cost, reach, need], checks });
};
add('single vertex that needs coverage', [-1], [7], [0], '1', ['brute']);
add('single vertex, nothing needed', [-1], [7], [3], '0', ['brute']);
add('path: one long-reach tower at the far end beats two short ones', [-1, 0, 1, 2, 3, 4], [9, 5, 5, 6, 6, 8], [5, 1, 1, 1, 1, 5], '111111', ['brute']);
add('star: centre tower versus leaf towers', [-1, 0, 0, 0, 0, 0], [10, 2, 2, 2, 2, 3], [1, 2, 0, 0, 0, 0], '111111', ['brute']);
add('only leaves need coverage; build at an unneeded vertex', [-1, 0, 0, 1, 1, 2, 2], [100, 4, 4, 1, 1, 1, 1], [0, 1, 1, 0, 0, 0, 0], '0001111', ['brute']);
add('reach 0 everywhere', [3, 3, 0, -1, 1, 2], [4, 5, 6, 7, 8, 9], [0, 0, 0, 0, 0, 0], '101011', ['brute']);
add('coverage has to come through the root from another branch', [-1, 0, 1, 2, 0, 4, 5, 6], [50, 50, 50, 50, 50, 50, 50, 3], [0, 0, 0, 0, 0, 0, 0, 6], '10010001', ['brute']);
add('tower reach larger than the whole tree', [1, -1, 1, 2, 3], [3, 30, 9, 9, 9], [0, 20, 0, 0, 0], '11111', ['brute']);
add('two overlapping towers are cheaper than one big one', [-1, 0, 1, 2, 3, 4, 5, 6, 7], [40, 1, 9, 9, 9, 9, 9, 1, 9], [4, 2, 0, 0, 0, 0, 0, 2, 0], '111111111', ['brute']);
add('parent indices are not ordered', [4, 4, 6, 6, -1, 0, 4, 1, 2], [3, 3, 3, 3, 3, 1, 3, 1, 1], [1, 1, 1, 1, 1, 1, 1, 1, 1], '111111111', ['brute']);
const rnd = (n, shape, RMx, costMax, needP) => {
  const par = randTree(R, n, shape);
  const cost = Array.from({ length: n }, () => (costMax === 1 ? 1 : R.int(1, costMax)));
  const RMv = RMx;
  const reach = Array.from({ length: n }, () => (RMx < 0 ? -RMx : R.int(0, RMv)));
  const need = Array.from({ length: n }, () => (R.chance(needP) ? '1' : '0')).join('');
  return [par, cost, reach, need];
};
for (let i = 0; i < 9; i++) add(i < 3 ? `random small #${i + 1}` : `random smallish #${i - 2}`, ...rnd(i < 3 ? R.int(12, 15) : R.int(30, 60), ['rand', 'deep', 'cater', 'rand', 'deep', 'path', 'star', 'deep', 'rand'][i], [3, 4, 5, 2, 6, 3, 2, 8, 1][i], 30, [0.75, 0.75, 0.75, 0.75, 0.75, 0.75, 0.5, 0.3, 1][i]), i < 3 ? ['brute'] : []);
add('medium random tree, weighted, variable reach', ...rnd(300, 'rand', 8, 1000, 0.8), []);
add('medium deep tree, weighted, variable reach', ...rnd(500, 'deep', 12, 50, 0.6), []);
add('medium caterpillar, sparse demand', ...rnd(800, 'cater', 6, 200, 0.3), []);
add('medium uniform reach 2, unit costs', ...rnd(1000, 'rand', -2, 1, 0.9), ['greedy']);
add('medium uniform reach 3, unit costs, deep', ...rnd(1500, 'deep', -3, 1, 1), ['greedy']);
add('medium random, reach up to 20, large costs', ...rnd(1200, 'rand', 20, 1000000, 0.5), []);
add('medium star-heavy, reach up to 4', ...rnd(1500, 'star', 4, 100, 0.9), []);
add('large path, weighted, reach up to 15', ...rnd(5000, 'path', 15, 9, 0.9), []);
add('large deep tree, weighted, reach up to 20', ...rnd(5000, 'deep', 20, 9, 0.7), []);
add('large random tree, uniform reach 5, unit costs', ...rnd(5000, 'rand', -5, 1, 0.8), ['greedy']);
add('large caterpillar, weighted, reach up to 10', ...rnd(4000, 'cater', 10, 9, 0.85), []);
const tests = T.map((t) => ({ args: t.args, expected: ref(...t.args) }));
let bad = 0;
for (const kind of ['brute', 'greedy']) {
  const idx = T.map((t, i) => i).filter((i) => T[i].checks.includes(kind));
  const res = python(`./c5/${kind}.py`, idx.map((i) => T[i].args));
  idx.forEach((i, j) => { if (res[j] !== tests[i].expected) { bad++; console.log(kind, 'MISMATCH', T[i].label, tests[i].expected, res[j]); } });
}
const lab = python('./c5/label.py', T.map((t) => t.args));
T.forEach((t, i) => { if (lab[i] !== tests[i].expected) { bad++; console.log('label MISMATCH', t.label, tests[i].expected, lab[i]); } });
T.forEach((t, i) => console.log(String(i + 1).padStart(2), t.label.padEnd(72), tests[i].expected));
console.log(bad ? `${bad} PROBLEMS` : `all ${tests.length} tests verified`);
fs.writeFileSync(new URL('./tests.json', import.meta.url), JSON.stringify({ functionName: 'minTowerCost', tests }));
fs.writeFileSync(new URL('./labels.json', import.meta.url), JSON.stringify(T.map((t) => t.label)));
console.log('json bytes', JSON.stringify(tests).length);
