import { rng, load, python } from '../lib.mjs';
const ref = load('./c4/ref.js', 'minFleetCost');
const R = rng(Number(process.argv[2] || 1)); const N = Number(process.argv[3] || 2000);
const cases = [];
for (let i = 0; i < N; i++) {
  const n = R.int(0, Number(process.env.MAXN || 7)); const C = R.int(3, 25); const H = R.int(20, Number(process.env.H || 120));
  const jobs = [];
  for (let j = 0; j < n; j++) { const s = R.int(0, H); const e = s + R.int(1, 25); jobs.push([R.int(0, C), R.int(0, C), R.int(0, C), R.int(0, C), s, e]); }
  cases.push([[R.int(0, C), R.int(0, C)], jobs, R.pick([0, 0, 1, 3, 10, 50, R.int(0, 100)]), R.int(1, Math.max(1, n + 1))]);
}
const py = python('./c4/brute.py', cases);
let bad = 0, neg = 0;
cases.forEach((c, i) => { const r = ref(...c); if (r === -1) neg++; if (r !== py[i]) { bad++; if (bad < 4) console.log('MISMATCH', JSON.stringify(c), r, py[i]); } });
console.log(`c4 fuzz: ${N - bad}/${N} agree (infeasible: ${neg})`);
