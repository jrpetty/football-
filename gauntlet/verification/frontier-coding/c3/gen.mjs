// Hidden tests for c3. Small cases: brute.py; medium: brute2.py; every case: oracle2.py (reversed-string eertree matching).
import fs from 'node:fs';
import { rng, load, deepEqual, python } from '../lib.mjs';
const ref = load('./c3/ref.js', 'palindromeRepeats');
const R = rng(424242);
const T = [];
const add = (label, parts, check) => {
  const n = parts.reduce((s, [t, k]) => s + t.length * k, 0);
  if (n < 1 || n > 200000) throw new Error('bad length ' + label);
  for (const [t, k] of parts) if (!/^[a-z]+$/.test(t) || !(Number.isInteger(k) && k >= 1)) throw new Error('bad part ' + label);
  T.push({ label: `${label} (n=${n})`, args: [parts], check });
};
const rand = (len, alpha) => { let s = ''; for (let i = 0; i < len; i++) s += alpha[R.int(0, alpha.length - 1)]; return s; };
add('single character', [['q', 1]], 'brute');
add('two equal characters', [['zz', 1]], 'brute');
add('three equal characters: aa overlaps itself', [['aaa', 1]], 'brute');
add('alternating: aba occurrences overlap', [['ab', 3]], 'brute');
add('same palindrome separated by one letter', [['abcba', 1], ['x', 1], ['abcba', 1]], 'brute');
add('adjacent copies touch but do not overlap', [['racecar', 2]], 'brute');
add('run-length parts with long runs', [['a', 5], ['b', 1], ['a', 5], ['c', 2], ['a', 4]], 'brute');
add('no repeated palindrome at all', [['abcdefghij', 1]], 'brute');
add('nested palindromes', [['abacabadabacaba', 1], ['x', 1], ['abacaba', 1]], 'brute');
add('even palindromes only repeat', [['abba', 1], ['c', 1], ['abba', 1], ['dd', 1]], 'brute');
for (let i = 0; i < 4; i++) {
  const alpha = ['ab', 'abc', 'ab', 'aab'][i];
  const parts = []; for (let j = 0; j < R.int(2, 5); j++) parts.push([rand(R.int(1, 8), alpha), R.int(1, 4)]);
  add(`random small ${i + 1}`, parts, 'brute');
}
for (const [len, alpha] of [[2000, 'ab'], [3000, 'abc'], [2500, 'aab'], [1500, 'abcdefghijklmnopqrstuvwxyz']]) add(`random medium over "${alpha.slice(0, 5)}"`, [[rand(len, alpha), 1]], 'brute2');
add('medium periodic with noise', [['abaaba', 150], ['b', 1], ['abaaba', 149], ['c', 1], ['aba', 100]], 'brute2');
// large, compactly described
add('all the same letter', [['a', 200000]], 'o2');
add('alternating two letters', [['ab', 100000]], 'o2');
add('palindromic period', [['abcba', 40000]], 'o2');
add('huge palindrome with a single different centre', [['a', 99999], ['b', 1], ['a', 100000]], 'o2');
add('two long runs and a separator', [['ab', 40000], ['c', 1], ['ba', 40000], ['a', 30000]], 'o2');
add('non-palindromic period', [['aab', 66666]], 'o2');
// large literal words
let fa = 'a', fb = 'ab'; while (fb.length < 90000) [fa, fb] = [fb, fb + fa];
add('Fibonacci word', [[fb.slice(0, 90000), 1]], 'o2');
let z = ''; for (let k = 0; k < 15; k++) z = z + 'abcdefghijklmnopqrstuvwxyz'[k] + z;
add('Zimin word over 15 letters', [[z, 1]], 'o2');
let tm = 'a'; while (tm.length < 32768) tm = tm + tm.replace(/[ab]/g, (c) => (c === 'a' ? 'b' : 'a'));
add('Thue-Morse word, then repeated', [[tm, 1], ['ab', 50000]], 'o2');
add('random binary string', [[rand(40000, 'ab'), 1]], 'o2');
add('random blocks of repeats', Array.from({ length: 12 }, () => [rand(R.int(1, 5), 'abc'), R.int(1000, 12000)]).filter((p, i, arr) => arr.slice(0, i + 1).reduce((s, [t, k]) => s + t.length * k, 0) <= 200000), 'o2');
const tests = T.map((t) => ({ args: t.args, expected: ref(...t.args) }));
let bad = 0;
for (const kind of ['brute', 'brute2']) {
  const idx = T.map((t, i) => i).filter((i) => T[i].check === kind);
  const res = python(`./c3/${kind}.py`, idx.map((i) => T[i].args));
  idx.forEach((i, j) => { if (!deepEqual(res[j], tests[i].expected)) { bad++; console.log(kind, 'MISMATCH', T[i].label, tests[i].expected, res[j]); } });
}
const o2 = python('./c3/oracle2.py', T.map((t) => t.args));
T.forEach((t, i) => { if (!deepEqual(o2[i], tests[i].expected)) { bad++; console.log('oracle2 MISMATCH', t.label, tests[i].expected, o2[i]); } });
// closed forms
const eq = (i, v) => { if (!deepEqual(tests[i].expected, v)) { bad++; console.log('CLOSED FORM MISMATCH', T[i].label, tests[i].expected, v); } };
eq(T.findIndex((t) => t.label.startsWith('all the same letter')), [100000, 100000, 15000050000]); // sum_{L=1..100000} (200001-L)
T.forEach((t, i) => console.log(String(i + 1).padStart(2), t.label.padEnd(58), JSON.stringify(tests[i].expected)));
console.log(bad ? `${bad} PROBLEMS` : `all ${tests.length} tests verified`);
fs.writeFileSync(new URL('./tests.json', import.meta.url), JSON.stringify({ functionName: 'palindromeRepeats', tests }));
fs.writeFileSync(new URL('./labels.json', import.meta.url), JSON.stringify(T.map((t) => t.label)));
console.log('json bytes', JSON.stringify(tests).length);
