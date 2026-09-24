// Hidden tests for c4. Small cases verified by brute.py (route enumeration + direct simulation); every case by
// hungarian.py (assignment formulation). Trap cases were found by search where plausible wrong strategies differ.
import fs from 'node:fs';
import { rng, load, python } from '../lib.mjs';
const ref = load('./c4/ref.js', 'minFleetCost');
const R = rng(31337);
const T = [];
const add = (label, depot, jobs, F, K, check) => {
  for (const j of jobs) if (!(j.length === 6 && j[4] < j[5] && j.every(Number.isInteger))) throw new Error('bad job ' + label);
  T.push({ label, args: [depot, jobs, F, K], check });
};
add('no jobs', [5, 5], [], 100, 3, 'brute');
add('one job', [0, 0], [[3, 4, 10, 4, 100, 150]], 1000, 1, 'brute');
add('chain allowed when arrival equals the start exactly', [0, 0], [[0, 10, 5, 10, 20, 30], [8, 10, 20, 0, 33, 40]], 50, 2, 'brute');
add('one time unit too late: two trucks needed', [0, 0], [[0, 10, 5, 10, 20, 30], [8, 10, 20, 0, 32, 40]], 50, 2, 'brute');
add('one time unit too late with a single truck: impossible', [0, 0], [[0, 10, 5, 10, 20, 30], [8, 10, 20, 0, 32, 40]], 50, 1, 'brute');
add('waiting is free: long gaps', [100, 100], [[0, 0, 10, 0, 0, 5], [500, 500, 400, 400, 100000, 100001], [0, 0, 0, 0, 5000, 5001]], 7, 5, 'brute');
add('unsorted jobs, equal start times, zero-length drives', [2, 2], [[2, 2, 2, 2, 9, 10], [2, 2, 2, 2, 0, 9], [2, 2, 7, 7, 9, 12], [7, 7, 2, 2, 12, 20], [2, 2, 2, 2, 20, 21]], 3, 5, 'brute');
add('cap equals the minimum fleet; zero-cost chains', [0, 0], [[0, 0, 50, 0, 0, 10], [0, 0, 0, 50, 0, 10], [50, 0, 0, 0, 200, 210], [0, 50, 0, 0, 200, 210]], 0, 2, 'brute');
add('cap larger than the number of jobs', [0, 0], [[1, 1, 2, 2, 0, 3], [9, 9, 1, 1, 10, 20]], 0, 10, 'brute');
// traps found by search (see search_traps.mjs)
const traps = JSON.parse(fs.readFileSync(new URL('./traps.json', import.meta.url), 'utf8'));
for (const [kind, list] of Object.entries(traps)) list.forEach(([depot, jobs, F, K], i) => add(`trap for ${kind} #${i + 1}`, depot, jobs, F, K, 'brute'));
// random small (n = 8)
for (let t = 0; t < 4; t++) {
  const jobs = []; const C = 30; for (let j = 0; j < 8; j++) { const s = R.int(0, 150); jobs.push([R.int(0, C), R.int(0, C), R.int(0, C), R.int(0, C), s, s + R.int(1, 30)]); }
  const probe = ref([15, 15], jobs, [0, 3, 10, 25][t], 8);
  let K = 8; while (K > 1 && ref([15, 15], jobs, [0, 3, 10, 25][t], K - 1) !== -1) K--; // tightest feasible cap for half of them
  add(`random n=8 #${t + 1}`, [15, 15], jobs, [0, 3, 10, 25][t], t % 2 ? K : 8, 'brute');
  void probe;
}
// large
function bigJobs(n, C, H, dmin, dmax) {
  const jobs = []; for (let j = 0; j < n; j++) { const s = R.int(0, H); jobs.push([R.int(0, C), R.int(0, C), R.int(0, C), R.int(0, C), s, s + R.int(dmin, dmax)]); }
  return jobs;
}
const large = [
  ['n=60 dense, F=0', 60, 1000, 20000, 1, 800, 0, 'free'],
  ['n=120 medium density, small F', 120, 10000, 100000, 1, 20000, 500, 'min'],
  ['n=200 sparse', 200, 100000, 1000000, 10000, 200000, 20000, 'min'],
  ['n=250 large F', 250, 1000000, 100000000, 1000, 5000000, 1000000000, 'free'],
  ['n=300 F=0', 300, 50000, 2000000, 1000, 60000, 0, 'min'],
  ['n=300 cap one below the minimum fleet', 300, 50000, 2000000, 1000, 60000, 300, 'infeasible'],
  ['n=350 moderate F', 350, 200000, 5000000, 5000, 400000, 150000, 'min'],
  ['n=400 F=0', 400, 100000, 4000000, 1000, 150000, 0, 'free'],
  ['n=400 small F', 400, 100000, 4000000, 1000, 150000, 2000, 'min'],
  ['n=350 dense, large F', 350, 1000000, 1000000000, 1, 2000000, 999999999, 'loose'],
];
for (const [label, n, C, H, dmin, dmax, F, mode] of large) {
  const jobs = bigJobs(n, C, H, dmin, dmax); const depot = [R.int(0, C), R.int(0, C)];
  // minimum feasible fleet via binary search on the reference
  let lo = 1, hi = n; while (lo < hi) { const mid = (lo + hi) >> 1; if (ref(depot, jobs, F, mid) !== -1) hi = mid; else lo = mid + 1; }
  const minK = lo;
  const K = Math.max(1, mode === 'free' ? n : mode === 'tight' ? minK + Math.floor((n - minK) * 0.05) : mode === 'min' ? minK : mode === 'infeasible' ? minK - 1 : minK + Math.floor((n - minK) * 0.6));
  const r = ref(depot, jobs, F, K), free = ref(depot, jobs, F, n);
  const status = r === -1 ? 'infeasible' : r !== free ? 'cap binding' : 'cap not binding';
  add(`large ${label} (min fleet ${minK}, cap ${K}, ${status})`, depot, jobs, F, K, 'hung');
}
const tests = T.map((t) => ({ args: t.args, expected: ref(...t.args) }));
let bad = 0;
const bi = T.map((t, i) => i).filter((i) => T[i].check === 'brute');
const b = python('./c4/brute.py', bi.map((i) => T[i].args));
bi.forEach((i, j) => { if (b[j] !== tests[i].expected) { bad++; console.log('BRUTE MISMATCH', T[i].label, tests[i].expected, b[j]); } });
const h = python('./c4/hungarian.py', T.map((t) => t.args));
T.forEach((t, i) => { if (h[i] !== tests[i].expected) { bad++; console.log('HUNGARIAN MISMATCH', t.label, tests[i].expected, h[i]); } });
T.forEach((t, i) => console.log(String(i + 1).padStart(2), t.label.padEnd(70), tests[i].expected));
console.log(bad ? `${bad} PROBLEMS` : `all ${tests.length} tests verified`);
fs.writeFileSync(new URL('./tests.json', import.meta.url), JSON.stringify({ functionName: 'minFleetCost', tests }));
fs.writeFileSync(new URL('./labels.json', import.meta.url), JSON.stringify(T.map((t) => t.label)));
console.log('json bytes', JSON.stringify(tests).length);
