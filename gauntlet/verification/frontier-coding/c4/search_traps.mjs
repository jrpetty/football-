// Find small instances (n <= 7) where plausible wrong strategies differ from the optimum. Verified by brute.py later.
import { rng, load } from '../lib.mjs';
const ref = load('./c4/ref.js', 'minFleetCost'), mc = load('./c4/naive_maxcard.js', 'minFleetCost'), ik = load('./c4/naive_ignoreK.js', 'minFleetCost'), gr = load('./c4/naive_greedy.js', 'minFleetCost');
const R = rng(Number(process.argv[2] || 5));
const found = { maxcard: [], ignoreK: [], greedy: [] };
for (let it = 0; it < 200000 && (found.maxcard.length < 3 || found.ignoreK.length < 3 || found.greedy.length < 3); it++) {
  const n = R.int(3, 6); const C = 20; const jobs = [];
  for (let j = 0; j < n; j++) { const s = R.int(0, 60); jobs.push([R.int(0, C), R.int(0, C), R.int(0, C), R.int(0, C), s, s + R.int(1, 15)]); }
  const depot = [R.int(0, C), R.int(0, C)];
  const F = R.pick([0, 0, 1, 2, 5]);
  const K = R.int(1, n);
  const r = ref(depot, jobs, F, K);
  if (r < 0) continue;
  if (K === n && mc(depot, jobs, F, K) !== r && found.maxcard.length < 3) found.maxcard.push([depot, jobs, F, K, r, mc(depot, jobs, F, K)]);
  if (ik(depot, jobs, F, K) !== r && found.ignoreK.length < 3) found.ignoreK.push([depot, jobs, F, K, r, ik(depot, jobs, F, K)]);
  if (gr(depot, jobs, F, K) !== r && found.greedy.length < 3) found.greedy.push([depot, jobs, F, K, r, gr(depot, jobs, F, K)]);
}
console.log(JSON.stringify(found));
