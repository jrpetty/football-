import { rng, load, python } from '../lib.mjs';
import { randomPolygon, isSimple, isOcto } from './shapes.mjs';
const ref = load('./c2/ref.js', 'coverageArea');
const R = rng(Number(process.argv[2] || 1)); const N = Number(process.argv[3] || 1000);
const cases = [];
for (let i = 0; i < N; i++) {
  const m = R.int(1, Number(process.env.MAXM || 5));
  const polys = [];
  for (let j = 0; j < m; j++) {
    if (j > 0 && R.chance(0.15)) { const q = R.pick(polys); const dx = R.int(-2, 2); polys.push(R.chance(0.5) ? q.slice().reverse() : q.map(([x, y]) => [x + dx, y])); continue; } // duplicates / shifted copies share edges
    polys.push(randomPolygon(R, { maxGrid: 4, scaleMax: 2, span: Number(process.env.SPAN || 6) }));
  }
  for (const p of polys) if (!isSimple(p) || !isOcto(p)) throw new Error('invalid polygon generated');
  cases.push([polys, R.chance(0.5) ? 1 : R.int(1, m)]);
}
const py = python('./c2/brute.py', cases);
let bad = 0; let zero = 0;
cases.forEach((c, i) => { const r = ref(...c); if (r === '0/1') zero++; if (r !== py[i]) { bad++; if (bad < 4) console.log('MISMATCH', JSON.stringify(c), r, py[i]); } });
console.log(`c2 fuzz: ${N - bad}/${N} agree (zero-area answers: ${zero})`);
