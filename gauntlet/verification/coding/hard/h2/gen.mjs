import fs from 'node:fs';
import { rng, load, check, sizeKB } from '../../lib.mjs';
const ref = load('h2/ref.js', 'regexMatch');
const R = rng(2202);
// fuzz: random valid patterns over a small alphabet
const lit = () => R.pick(['a', 'b', 'c', '.', '\\.', '\\*', '[ab]', '[^a]', '[a-c]', '[-a]', '[b-]', '{', '^', '$']);
function pat(d) {
  if (d === 0) return lit();
  const r = R.next();
  if (r < 0.35) return pat(d - 1) + pat(d - 1);
  if (r < 0.5) return '(' + pat(d - 1) + '|' + (R.next() < 0.2 ? '' : pat(d - 1)) + ')';
  if (r < 0.75) { const a = pat(d - 1); return (a.length === 1 ? a : '(' + a + ')') + R.pick(['*', '+', '?']); }
  return lit();
}
const fuzz = [];
for (let t = 0; t < 20000; t++) { const p = pat(3); let s = ''; const L = R.int(0, 6); for (let k = 0; k < L; k++) s += R.pick(['a', 'b', 'c', '.', '*', '-', '{', '^', '$', '\n']); fuzz.push({ args: [p, s], expected: ref(p, s) }); }
fs.writeFileSync('h2/fuzz.json', JSON.stringify({ tests: fuzz }));
const tests = [];
const add = (p, s) => tests.push({ args: [p, s], expected: ref(p, s) });
add('abc', 'abc'); add('abc', 'abcd'); add('a.c', 'a\nc'); add('a*', ''); add('a+', ''); add('colou?r', 'color'); add('colou?r', 'colouur');
add('(ab|cd)*e', 'abcdabe'); add('(ab|cd)*e', 'abce'); add('x(|y)z', 'xz'); add('[a-cx-z]+', 'abzyx'); add('[^0-9]*', 'no digits!'); add('[^0-9]*', 'one1');
add('\\.\\*\\\\', '.*\\'); add('[-+]?[0-9]+', '-42'); add('[a-]', '-'); add('a{2}', 'a{2}'); add('^a$', '^a$'); add('(a|b)*abb', 'abababb');
add('abc', 'ab'); add('[^a]', 'a'); add('(ab|cd)*e', 'abcd'); add('a.c', 'ac'); add('[a-c]+', 'abd'); add('x(|y)z', 'xyyz'); add('\\.', 'a'); add('colou?r', 'colr'); add('(a|b)*abb', 'ababab'); add('[-+]?[0-9]+', '+-5');
add('((a*)*)*b', 'a'.repeat(40) + 'b'); add('((a*)*)*b', 'a'.repeat(40));
add('(a|aa)*c', 'a'.repeat(5000)); add('(a?)'.repeat(25) + 'a'.repeat(25), 'a'.repeat(25));
add('(a|b|ab|ba)*(c|d)*', 'ab'.repeat(3000) + 'cd'.repeat(3000));
add('.*.*.*.*.*.*.*.*.*.*x', 'y'.repeat(4000));
console.log('tests', tests.length, 'size KB', sizeKB(tests));
console.log(JSON.stringify(tests.map((t) => t.expected)));
fs.writeFileSync('h2/tests.json', JSON.stringify({ functionName: 'regexMatch', tests }));
check('ref', fs.readFileSync('h2/ref.js', 'utf8'), 'regexMatch', tests);
