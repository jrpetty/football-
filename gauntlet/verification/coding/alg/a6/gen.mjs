import fs from 'node:fs';
import { rng, load, check, sizeKB } from '../../lib.mjs';
const ref = load('a6/ref.js', 'longestWindow');
const brute = load('a6/brute.js', 'longestWindow');
const R = rng(606);
for (let t = 0; t < 5000; t++) {
  const n = R.int(0, 12); const a = []; for (let i = 0; i < n; i++) a.push(R.int(0, 6));
  const args = [a, R.int(0, 4), R.int(0, 25)];
  if (ref(...args) !== brute(...args)) { console.log('MISMATCH', JSON.stringify(args)); process.exit(1); }
}
const tests = [];
const add = (a, k, s) => tests.push({ args: [a, k, s], expected: ref(a, k, s) });
add([], 3, 10);
add([5], 1, 4);
add([5], 1, 5);
add([1, 2, 3], 0, 100);
add([0, 0, 0, 0], 1, 0);                        // zeros fit even with maxSum 0
add([4, 4, 4, 1, 1, 1, 1], 1, 100);
add([1, 2, 1, 3, 1, 2, 1], 2, 7);
add([7, 1, 1, 1, 7, 1, 1], 2, 9);
add([10000, 3, 3, 10000, 3, 3, 3], 1, 9);
add([2, 2, 3, 3, 2, 2, 9, 2, 3, 2, 2, 3, 3], 2, 22);
{ const a = []; for (let i = 0; i < 80; i++) a.push(R.int(0, 9)); add(a, 3, 60); }
{ const a = []; for (let i = 0; i < 25000; i++) a.push(R.int(0, 9)); add(a, 4, 400); }
{ const a = []; for (let i = 0; i < 25000; i++) a.push(i % 1000 === 0 ? 9 : R.int(0, 2)); add(a, 3, 60000); }
{ const a = []; for (let i = 0; i < 25000; i++) a.push(1); add(a, 1, 24999); }
for (const t of tests) if (t.args[0].length <= 100) { if (brute(...t.args) !== t.expected) { console.log('brute mismatch'); process.exit(1); } }
console.log('tests', tests.length, 'size KB', sizeKB(tests), JSON.stringify(tests.map((t) => t.expected)));
fs.writeFileSync('a6/tests.json', JSON.stringify({ functionName: 'longestWindow', tests }));
check('ref', fs.readFileSync('a6/ref.js', 'utf8'), 'longestWindow', tests);
check('brute (perf trap)', fs.readFileSync('a6/brute.js', 'utf8'), 'longestWindow', tests);
