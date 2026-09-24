import { rng, python } from '../lib.mjs';
const R = rng(99); const cases = [];
for (let i = 0; i < 800; i++) {
  const n = R.int(0, 7); const C = R.int(3, 25); const H = R.int(20, 150); const jobs = [];
  for (let j = 0; j < n; j++) { const s = R.int(0, H); jobs.push([R.int(0, C), R.int(0, C), R.int(0, C), R.int(0, C), s, s + R.int(1, 25)]); }
  cases.push([[R.int(0, C), R.int(0, C)], jobs, R.pick([0, 1, 5, 40, R.int(0, 100)]), R.int(1, n + 2)]);
}
const a = python('./c4/brute.py', cases), b = python('./c4/hungarian.py', cases);
console.log('hungarian.py vs brute.py:', a.filter((x, i) => x === b[i]).length, '/', cases.length);
