import fs from 'node:fs';
import { rng, load, check, sizeKB } from '../../lib.mjs';
const ref = load('a2/ref.js', 'cheapestTrip');
const brute = load('a2/brute.js', 'cheapestTrip');
const R = rng(202);
for (let t = 0; t < 3000; t++) {
  const n = R.int(1, 7); const m = R.int(0, 10); const roads = [];
  for (let i = 0; i < m; i++) roads.push([R.int(0, n - 1), R.int(0, n - 1), R.int(1, 30)]);
  const args = [n, roads, R.int(0, n - 1), R.int(0, n - 1), R.int(0, 3)];
  const a = ref(...args), b = brute(...args);
  if (a !== b) { console.log('MISMATCH', JSON.stringify(args), a, b); process.exit(1); }
}
const tests = [];
const add = (...args) => tests.push({ args, expected: ref(...args) });
add(1, [], 0, 0, 0);
add(3, [[0, 1, 5]], 0, 2, 2);                                      // unreachable
add(2, [[0, 1, 7]], 0, 1, 1);                                      // floor(7/2)=3
add(2, [[0, 1, 7]], 1, 0, 0);                                      // undirected, no coupon
add(4, [[0, 1, 10], [1, 2, 10], [2, 3, 10], [0, 3, 35]], 0, 3, 1); // coupon on the long road wins: 17
add(4, [[0, 1, 10], [1, 2, 10], [2, 3, 10], [0, 3, 35]], 0, 3, 3); // 15 vs 17
add(3, [[0, 1, 9], [0, 1, 4], [1, 1, 1], [1, 2, 3]], 0, 2, 0);    // parallel + self loop
add(5, [[0, 1, 1], [1, 2, 1], [2, 3, 1], [3, 4, 1]], 4, 0, 10);    // cost 1 roads become 0
add(6, [[0, 1, 100], [1, 5, 100], [0, 2, 30], [2, 3, 30], [3, 4, 30], [4, 5, 30]], 0, 5, 2);
{ // medium random
  const n = 40, roads = []; for (let i = 0; i < 120; i++) roads.push([R.int(0, n - 1), R.int(0, n - 1), R.int(1, 1000)]);
  add(n, roads, 0, n - 1, 2); add(n, roads, 3, 17, 0);
}
{ // large: 4000 nodes, 12000 roads, k = 6
  const n = 2000, roads = []; for (let i = 1; i < n; i++) roads.push([i, R.int(Math.max(0, i - 50), i - 1), R.int(1, 1000000)]);
  for (let i = 0; i < 3000; i++) roads.push([R.int(0, n - 1), R.int(0, n - 1), R.int(1, 1000000)]);
  add(n, roads, 0, n - 1, 6);
}
{ // large chain where many coupons matter, disconnected tail
  const n = 1500, roads = []; for (let i = 0; i + 1 < 1200; i++) roads.push([i, i + 1, R.int(1, 999)]);
  add(n, roads, 0, 1199, 10); add(n, roads, 0, 1499, 10);
}
// cross-check medium tests against brute
for (const t of tests) if (t.args[0] <= 40) { if (brute(...t.args) !== t.expected) { console.log('brute mismatch', JSON.stringify(t.args).slice(0, 80)); process.exit(1); } }
console.log('tests', tests.length, 'size KB', sizeKB(tests), tests.map((t) => t.expected).join(' '));
fs.writeFileSync('a2/tests.json', JSON.stringify({ functionName: 'cheapestTrip', tests }));
check('ref', fs.readFileSync('a2/ref.js', 'utf8'), 'cheapestTrip', tests);
