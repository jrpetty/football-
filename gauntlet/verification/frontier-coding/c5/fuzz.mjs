import { rng, load, python } from '../lib.mjs';
const ref = load('./c5/ref.js', 'minTowerCost');
const R = rng(Number(process.argv[2] || 1)); const N = Number(process.argv[3] || 2000);
export function randTree(R, n, shape) {
  const par = new Array(n).fill(-1); const perm = R.shuffle([...Array(n).keys()]);
  for (let k = 1; k < n; k++) {
    let pk;
    if (shape === 'path') pk = k - 1; else if (shape === 'star') pk = 0; else if (shape === 'cater') pk = R.chance(0.5) ? k - 1 : Math.max(0, k - R.int(1, 3)); else pk = R.int(Math.max(0, k - (shape === 'deep' ? 3 : k)), k - 1);
    par[perm[k]] = perm[pk];
  }
  return par;
}
const cases = [];
for (let i = 0; i < N; i++) {
  const n = R.int(1, Number(process.env.MAXN || 12)); const shape = R.pick(['rand', 'deep', 'path', 'star', 'cater']);
  const par = randTree(R, n, shape); const RM = R.int(0, 5);
  const cost = Array.from({ length: n }, () => R.chance(0.3) ? 1 : R.int(1, 20));
  const reach = Array.from({ length: n }, () => R.int(0, RM));
  const need = Array.from({ length: n }, () => (R.chance(0.7) ? '1' : '0')).join('');
  cases.push([par, cost, reach, need]);
}
const b = python('./c5/brute.py', cases), l = python('./c5/label.py', cases);
let bad = 0;
cases.forEach((c, i) => { const r = ref(...c); if (r !== b[i] || r !== l[i]) { bad++; if (bad < 4) console.log('MISMATCH', JSON.stringify(c), 'ref', r, 'brute', b[i], 'label', l[i]); } });
console.log(`c5 fuzz: ${N - bad}/${N} agree (ref vs brute vs label)`);
