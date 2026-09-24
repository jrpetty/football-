import fs from 'node:fs';
import { rng, load, check, sizeKB } from '../../lib.mjs';
const ref = load('a4/ref.js', 'countSplits');
const brute = load('a4/brute.js', 'countSplits');
const R = rng(404);
for (let t = 0; t < 4000; t++) {
  const n = R.int(1, 14); let s = ''; for (let k = 0; k < n; k++) s += R.next() < 0.2 ? '0' : String(R.int(1, 9));
  const lim = R.pick([1, 5, 9, 10, 26, 99, 100, 305, 1000, 99999, 1000000000, R.int(1, 5000)]);
  const a = ref(s, lim), b = brute(s, lim);
  if (a !== b) { console.log('MISMATCH', s, lim, a, b); process.exit(1); }
}
const tests = [];
const add = (s, l) => tests.push({ args: [s, l], expected: ref(s, l) });
add('1234', 34); add('7', 7); add('7', 6); add('0', 5); add('10', 10); add('10', 9); add('1010', 10); add('100', 1000); add('3003', 1000);
add('11111111111111111111', 11);
add('9'.repeat(40), 1000000000);
{ let s = ''; for (let k = 0; k < 40000; k++) s += '1'; add(s, 11); }                       // Fibonacci-like, needs mod
{ let s = ''; for (let k = 0; k < 40000; k++) s += String(R.int(1, 9)); add(s, 987654321); } // wide window
{ let s = ''; for (let k = 0; k < 40000; k++) s += R.next() < 0.15 ? '0' : String(R.int(1, 9)); add(s, 250); }
{ let s = '1'; for (let k = 0; k < 39998; k++) s += '2'; s += '0'; add(s, 20); }              // only '20' can absorb the final zero
for (const t of tests) if (t.args[0].length <= 20) { if (brute(...t.args) !== t.expected) { console.log('brute mismatch', t.args); process.exit(1); } }
console.log('tests', tests.length, 'size KB', sizeKB(tests), JSON.stringify(tests.map((t) => t.expected)));
fs.writeFileSync('a4/tests.json', JSON.stringify({ functionName: 'countSplits', tests }));
check('ref', fs.readFileSync('a4/ref.js', 'utf8'), 'countSplits', tests);
