// confirm slab.py agrees with brute.py (triangle counting) on random small cases
import { rng, python } from '../lib.mjs';
import { randomPolygon } from './shapes.mjs';
const R = rng(77); const cases = [];
for (let i = 0; i < 600; i++) { const m = R.int(1, 6); const P = []; for (let j = 0; j < m; j++) P.push(randomPolygon(R, { maxGrid: 4, scaleMax: 2, span: 2 })); cases.push([P, R.int(1, m)]); }
const a = python('./c2/brute.py', cases), b = python('./c2/slab.py', cases);
console.log('slab.py vs brute.py:', a.filter((x, i) => x === b[i]).length, '/', cases.length);
