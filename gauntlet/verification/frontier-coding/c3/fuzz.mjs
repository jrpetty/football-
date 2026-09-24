import { rng, load, deepEqual, python } from '../lib.mjs';
const ref = load('./c3/ref.js', 'palindromeRepeats');
const R = rng(Number(process.argv[2] || 1)); const N = Number(process.argv[3] || 3000);
const cases = [];
for (let i = 0; i < N; i++) {
  const alpha = 'abcdefghijklmnopqrstuvwxyz'.slice(0, R.int(1, 4));
  const parts = []; const np = R.int(1, 4); let tot = 0;
  for (let j = 0; j < np; j++) {
    const len = R.int(1, 6); let t = ''; for (let q = 0; q < len; q++) t += R.pick(alpha);
    const times = R.int(1, Math.max(1, Math.floor(Number(process.env.MAXN || 40) / 3 / len)));
    if (tot + len * times > Number(process.env.MAXN || 40)) break;
    parts.push([t, times]); tot += len * times;
  }
  if (!parts.length) parts.push(['a', 1]);
  cases.push([parts]);
}
const b = python('./c3/brute.py', cases), o = python('./c3/oracle2.py', cases);
let bad = 0;
cases.forEach((c, i) => { const r = ref(...c); if (!deepEqual(r, b[i]) || !deepEqual(r, o[i])) { bad++; if (bad < 4) console.log('MISMATCH', JSON.stringify(c), r, b[i], o[i]); } });
console.log(`c3 fuzz: ${N - bad}/${N} agree (ref vs brute vs oracle2)`);
