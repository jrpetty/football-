import fs from 'node:fs';
import { rng, load, check, sizeKB } from '../../lib.mjs';
const ref = load('h1/ref.js', 'evaluate');
const R = rng(2101);
// fuzz set for python cross-check: random token strings (valid and invalid)
const fuzz = [];
const toks = ['1', '2', '3', '0', '0.5', '10', '2.25', '+', '-', '*', '/', '%', '^', '(', ')', ' '];
for (let t = 0; t < 30000; t++) { let s = ''; const L = R.int(1, 9); for (let k = 0; k < L; k++) s += R.pick(toks); fuzz.push({ args: [s], expected: ref(s) }); }
// structured valid expressions
const gen = (d) => { if (d === 0 || R.next() < 0.3) return R.pick(['1', '2', '3', '4', '0.5', '1.5', '7', '10']); const op = R.pick(['+', '-', '*', '/', '%', '^', 'u', 'p']); if (op === 'u') return '-' + gen(d - 1); if (op === 'p') return '(' + gen(d - 1) + ')'; return gen(d - 1) + R.pick(['', ' ']) + op + R.pick(['', ' ']) + gen(d - 1); };
for (let t = 0; t < 20000; t++) { const s = gen(4); fuzz.push({ args: [s], expected: ref(s) }); }
fs.writeFileSync('h1/fuzz.json', JSON.stringify({ tests: fuzz }));
const tests = [];
const add = (s) => tests.push({ args: [s], expected: ref(s) });
for (const s of ['1 + 2 * 3', '(1 + 2) * 3', '-2^2', '(-2)^2', '2^-2', '2^3^2', '-2^-2', '2 - -3', '--3', '+-+4', '7 % 3', '-7 % 3', '7.5 % -2', '10 / 4 - 0.5',
  '2 * (3 + 4) ^ 2 / 7', '100 - 2^-1^-1', '1/0', '5 % 0', '0^-1', '(-8)^(1/3)', '', '   ', '2 3', '2(3)', '(1+2', '1+2)', '()', '.5', '5.', '1e3', '2**3', '3 +', '* 4', '1 + 2 x', '007.50 * 2', '\t3\t*\t3 ', '((2))', '3 - 2 - 1', '2 / 2 / 2', '2 ^ 0.5 ^ 2', '-(3 + 4) * 2', '9 % 4 % 3', '1.25 * 8 - 10', '6 / -3 ^ 2', '0 * -5', '-0', '-(0.0) * 3', '0 / -7']) add(s);
add('1' + '0'.repeat(400)); add('1' + '0'.repeat(400) + ' - 1' + '0'.repeat(400)); add('0 * 1' + '0'.repeat(400));
add('1' + '0'.repeat(308) + ' / 1' + '0'.repeat(300)); add('0.' + '0'.repeat(400) + '1 + 2'); add('10 ^ 309'); add('2 ^ 1023 * 2');
{ let s = ''; for (let k = 0; k < 20000; k++) s += '('; s += '1.5'; for (let k = 0; k < 20000; k++) s += ')'; add(s + ' * 2'); }   // deep nesting
{ let s = ''; for (let k = 0; k < 20001; k++) s += '-'; add(s + '5'); }                                                       // long unary chain
{ let s = '0'; for (let k = 1; k <= 5000; k++) s += (k % 2 ? ' + ' : ' - ') + (k % 97) + '*' + '2^' + (k % 3); add(s); }  // long flat expression
console.log('tests', tests.length, 'size KB', sizeKB(tests));
console.log(JSON.stringify(tests.map((t) => [t.args[0].slice(0, 20), t.expected])));
fs.writeFileSync('h1/tests.json', JSON.stringify({ functionName: 'evaluate', tests }));
check('ref', fs.readFileSync('h1/ref.js', 'utf8'), 'evaluate', tests);
