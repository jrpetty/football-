import fs from 'node:fs';
import { rng, load, check, sizeKB } from '../../lib.mjs';
const ref = load('a7/ref.js', 'topWords');
const R = rng(707);
const tests = [];
const add = (t, k) => tests.push({ args: [t, k], expected: ref(t, k) });
add('', 3);
add('Hello hello HELLO world', 0);
add('Hello hello HELLO world', 5);
add("Don't stop-believing: it's 2x2=4, isn't it?", 4);
add('b a c b a c', 2);                                  // all tie at 2 -> alphabetical
add('Zebra apple zebra Apple mango', 3);
add('naïve café résumé cafe', 4);                        // non-ASCII letters are separators
add('  \n\t ', 1);
add('one two two three three three', 10);
add('x'.repeat(3) + ' ' + 'xx xx xxx', 2);
add('The quick brown fox jumps over the lazy dog. THE DOG sleeps; the fox does not.', 5);
{ const vocab = []; for (let i = 0; i < 300; i++) { let w = ''; const L = R.int(1, 7); for (let j = 0; j < L; j++) w += String.fromCharCode(97 + R.int(0, 25)); vocab.push(w); }
  let t = ''; for (let i = 0; i < 16000; i++) { let w = vocab[Math.floor(Math.pow(R.next(), 2) * vocab.length)]; if (R.next() < 0.3) w = w.toUpperCase(); t += w + R.pick([' ', ', ', '. ', "'", '-', '1', '\n']); }
  add(t, 25); }
console.log('tests', tests.length, 'size KB', sizeKB(tests));
fs.writeFileSync('a7/tests.json', JSON.stringify({ functionName: 'topWords', tests }));
check('ref', fs.readFileSync('a7/ref.js', 'utf8'), 'topWords', tests);
