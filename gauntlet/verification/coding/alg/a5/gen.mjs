import fs from 'node:fs';
import { rng, load, check, sizeKB } from '../../lib.mjs';
const ref = load('a5/ref.js', 'componentsAfterCuts');
const brute = load('a5/brute.js', 'componentsAfterCuts');
const R = rng(505);
const shuffle = (a) => { for (let i = a.length - 1; i > 0; i--) { const j = R.int(0, i); [a[i], a[j]] = [a[j], a[i]]; } return a; };
for (let t = 0; t < 3000; t++) {
  const n = R.int(1, 8), m = R.int(0, 12); const edges = [];
  for (let i = 0; i < m; i++) edges.push([R.int(0, n - 1), R.int(0, n - 1)]);
  const cuts = shuffle([...Array(m).keys()]).slice(0, R.int(0, m));
  const a = JSON.stringify(ref(n, edges, cuts)), b = JSON.stringify(brute(n, edges, cuts));
  if (a !== b) { console.log('MISMATCH', n, JSON.stringify(edges), JSON.stringify(cuts), a, b); process.exit(1); }
}
const tests = [];
const add = (n, e, c) => tests.push({ args: [n, e, c], expected: ref(n, e, c) });
add(1, [], []);
add(3, [[0, 1], [1, 2]], [0, 1]);
add(3, [[0, 1], [0, 1], [1, 2]], [0, 1, 2]);                 // parallel edge keeps it connected
add(2, [[0, 0], [1, 1], [0, 1]], [0, 2, 1]);                 // self-loops
add(5, [[0, 1], [1, 2], [2, 0], [3, 4]], [2, 0, 3, 1]);      // cycle
add(6, [], []);
add(4, [[0, 1], [2, 3], [1, 2]], []);
add(7, [[0, 1], [1, 2], [1, 3], [3, 4], [3, 5]], [3, 0, 4, 2, 1]);   // tree, extra isolated node 6
add(3, [[2, 2], [2, 2], [0, 0]], [1, 0, 2]);                          // only self-loops
{ const n = 30, e = []; for (let i = 0; i < 45; i++) e.push([R.int(0, n - 1), R.int(0, n - 1)]); add(n, e, shuffle([...Array(45).keys()])); }
{ // large: grid-like graph 60x60 plus random edges, cut everything
  const W = 30, n = W * W, e = [];
  for (let r = 0; r < W; r++) for (let c = 0; c < W; c++) { if (c + 1 < W) e.push([r * W + c, r * W + c + 1]); if (r + 1 < W) e.push([r * W + c, (r + 1) * W + c]); }
  add(n, e, shuffle([...Array(e.length).keys()]));
}
{ // large sparse random, cut 70%
  const n = 5000, e = []; for (let i = 0; i < 6000; i++) e.push([R.int(0, n - 1), R.int(0, n - 1)]);
  add(n, e, shuffle([...Array(e.length).keys()]).slice(0, 4200));
}
for (const t of tests) if (t.args[1].length <= 50) { if (JSON.stringify(brute(...t.args)) !== JSON.stringify(t.expected)) { console.log('brute mismatch'); process.exit(1); } }
console.log('tests', tests.length, 'size KB', sizeKB(tests));
fs.writeFileSync('a5/tests.json', JSON.stringify({ functionName: 'componentsAfterCuts', tests }));
check('ref', fs.readFileSync('a5/ref.js', 'utf8'), 'componentsAfterCuts', tests);
check('brute (perf trap)', fs.readFileSync('a5/brute.js', 'utf8'), 'componentsAfterCuts', tests);
