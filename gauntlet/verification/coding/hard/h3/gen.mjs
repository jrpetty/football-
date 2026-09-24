import fs from 'node:fs';
import { rng, load, check, sizeKB } from '../../lib.mjs';
const ref = load('h3/ref.js', 'meridianAddDays');
const R = rng(2303);
const randDate = (ymax) => { const y = R.int(1, ymax); const r = R.next(); if (r < 0.05) return `${y}-YD`; if (r < 0.08 && ((y % 6 === 0 && y % 90 !== 0) || y % 360 === 0)) return `${y}-LD`; return `${y}-${String(R.int(1, 13)).padStart(2, '0')}-${String(R.int(1, 28)).padStart(2, '0')}`; };
const fuzz = [];
for (let t = 0; t < 4000; t++) { const d = randDate(2000); let n = R.int(-2000, 2000) * R.pick([1, 1, 1, 37, 400]); const yr = Number(d.split('-')[0]); if (yr * 365 + n < 400) n = Math.abs(n); fuzz.push({ args: [d, n], expected: ref(d, n) }); }
fs.writeFileSync('h3/fuzz.json', JSON.stringify({ tests: fuzz }));
const tests = [];
const add = (d, n) => tests.push({ args: [d, n], expected: ref(d, n) });
add('2026-03-12', 0); add('2026-03-12', 20); add('2026-06-28', 1); add('2028-06-28', 1); add('2028-LD', 1); add('2028-07-01', -1);
add('90-06-28', 1); add('360-06-28', 1); add('180-06-28', 1); add('720-06-28', 1);
add('2026-13-28', 1); add('2026-YD', 1); add('2027-01-01', -1); add('1-01-01', 364); add('1-01-01', 365);
add('5-YD', 366); add('7-03-05', -2000); add('359-YD', 2); add('719-13-28', 2);
add('2026-01-01', 1000000000000); add('999999999-YD', -123456789012); add('1-01-01', 131457); add('1-01-01', 131457 * 1000 - 1);
console.log('tests', tests.length, 'size KB', sizeKB(tests));
console.log(JSON.stringify(tests.map((t) => [t.args, t.expected])));
fs.writeFileSync('h3/tests.json', JSON.stringify({ functionName: 'meridianAddDays', tests }));
check('ref', fs.readFileSync('h3/ref.js', 'utf8'), 'meridianAddDays', tests);
