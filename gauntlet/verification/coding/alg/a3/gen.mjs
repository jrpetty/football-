import fs from 'node:fs';
import { rng, load, check, sizeKB } from '../../lib.mjs';
const ref = load('a3/ref.js', 'charAt');
const brute = load('a3/brute.js', 'charAt');
const R = rng(303);
function randPat(depth, maxLen) {
  let s = ''; const parts = R.int(1, 3);
  for (let p = 0; p < parts; p++) {
    if (depth > 0 && R.next() < 0.45) s += R.int(1, 4) + '(' + randPat(depth - 1, maxLen) + ')';
    else { const L = R.int(1, maxLen); for (let k = 0; k < L; k++) s += String.fromCharCode(97 + R.int(0, 5)); }
  }
  return s;
}
for (let t = 0; t < 4000; t++) {
  const p = randPat(3, 3);
  const full = (() => { let len = 0; for (let k = 0; k < 400; k++) if (brute(p, k) === '') { len = k; break; } return len; })();
  for (let q = 0; q < 6; q++) { const idx = R.int(0, full + 2); const a = ref(p, idx), b = brute(p, idx); if (a !== b) { console.log('MISMATCH', p, idx, a, b); process.exit(1); } }
}
const tests = [];
const add = (p, i) => tests.push({ args: [p, i], expected: ref(p, i) });
add('abc', 0); add('abc', 2); add('abc', 3);
add('ab3(c2(d))e', 5); add('ab3(c2(d))e', 11); add('ab3(c2(d))e', 12);
add('1(x)', 0);
add('10(ab)', 19);
add('2(a)3(b)', 4);
add('xy1000000000(1000000000(1000000000(abc)))z', 9007199254740990);   // inside a ~3e27-long group
add('xy1000000000(1000000000(1000000000(abc)))z', 1);
add('7(ab12(cd)e)99(f3(gh))', 700); add('7(ab12(cd)e)99(f3(gh))', 882);
{ // deep nesting 900 levels of 2(...) around a short core: length 3*2^900
  let p = 'q'; for (let d = 0; d < 900; d++) p = '2(' + p + (d % 3 === 0 ? 'z' : '') + ')'; add(p, 9007199254740991 - 17);
}
{ // long flat pattern ~ 60k chars with many groups; decoded length ~ 1e14
  let p = ''; for (let k = 0; k < 6000; k++) p += String.fromCharCode(97 + (k % 26)) + (k % 7 + 2) + '(' + 'mn' + (k % 5 + 1) + '(o))';
  p = '500000000(' + p + ')';
  add(p, 43210987654321);
}
{ // huge exact arithmetic: position just below and at the end of a 4e18-long string
  const p = '2000000000(2000000000(a)b)'; // length 2e9*(2e9+1)
  add(p, 2000000000); add(p, 4000000001);
}
for (const t of tests) { if (t.args[0].length < 40 && t.args[1] < 5000) { const b = brute(...t.args); if (b !== t.expected) { console.log('brute mismatch', t.args); process.exit(1); } } }
console.log('tests', tests.length, 'size KB', sizeKB(tests), JSON.stringify(tests.map((t) => t.expected)));
fs.writeFileSync('a3/tests.json', JSON.stringify({ functionName: 'charAt', tests }));
check('ref', fs.readFileSync('a3/ref.js', 'utf8'), 'charAt', tests);
