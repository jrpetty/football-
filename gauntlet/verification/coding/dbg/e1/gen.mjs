import fs from 'node:fs';
import { rng, load, check, sizeKB } from '../../lib.mjs';
const ref = load('e1/ref.js', 'addDecimalStrings');
const R = rng(1101);
const randNum = (maxI, maxF) => {
  let s = R.pick(['', '', '+', '-']);
  let ip = ''; const li = R.int(1, maxI); for (let k = 0; k < li; k++) ip += R.int(0, 9);
  s += ip;
  if (R.next() < 0.6) { let fp = ''; const lf = R.int(1, maxF); for (let k = 0; k < lf; k++) fp += R.int(0, 9); s += '.' + fp; }
  return s;
};
// random pairs for the python cross-check (not all end up as hidden tests)
const pairs = [];
for (let t = 0; t < 20000; t++) pairs.push([randNum(4, 4), randNum(4, 4)]);
for (let t = 0; t < 2000; t++) pairs.push([randNum(60, 40), randNum(60, 40)]);
fs.writeFileSync('e1/cross.json', JSON.stringify(pairs.map((p) => ({ args: p, expected: ref(...p) }))));
const tests = [];
const add = (a, b) => tests.push({ args: [a, b], expected: ref(a, b) });
add('1', '2'); add('0.1', '0.2'); add('-5', '5'); add('-0.50', '0.5'); add('007.250', '0.750'); add('+3', '-0003.000');
add('999999999999999999999999999999', '1'); add('-1', '0.000000000000000000000000000001');
add('0', '-0'); add('-0.0', '-0.00'); add('123.456', '-123.4559');
add('12345678901234567890.12345678901234567890', '98765432109876543210.98765432109876543210');
add('-99999999999999999999999999999999999999999.9', '-0.1');
add('100000000000000000000000000000000000000000000', '-0.000000000000000000000000000000000000000001');
{ let a = '', b = ''; for (let k = 0; k < 900; k++) { a += R.int(0, 9); b += R.int(0, 9); } add(a + '.' + b, '-' + b + '.' + a); }
console.log('tests', tests.length, 'size KB', sizeKB(tests)); console.log(JSON.stringify(tests.slice(0, 14).map((t) => t.expected)));
fs.writeFileSync('e1/tests.json', JSON.stringify({ functionName: 'addDecimalStrings', tests }));
check('ref', fs.readFileSync('e1/ref.js', 'utf8'), 'addDecimalStrings', tests);
