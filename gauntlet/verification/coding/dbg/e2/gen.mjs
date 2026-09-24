import fs from 'node:fs';
import { rng, load, check, sizeKB } from '../../lib.mjs';
const ref = load('e2/ref.js', 'smartTruncate');
const R = rng(1202);
const tests = [];
const add = (t, n) => tests.push({ args: [t, n], expected: ref(t, n) });
add('hello', 5); add('hello', 10); add('hello world', 8); add('hello world', 7); add('hello world', 6);
add('', 0); add('abc', 0); add('abc', 1); add('abc', -3);
add('ab   cd', 5);                                   // trailing spaces removed before the ellipsis
add('a\t\n b', 4);
add('😀😀😀😀', 4); add('😀😀😀😀', 3);              // astral code points count as 1
add('x😀y', 2);
add('Olá, 世界! 👋🏽 bye', 12);
add('👨‍👩‍👧 family', 3);                             // ZWJ sequence: 5 code points
add('ééé', 4);                     // combining marks are separate code points
add('ab  cd', 4);                          // NBSP is whitespace for /\s/
add('ab​​cd', 4);                          // zero-width space is NOT whitespace for /\s/
add('ab　cd', 4);                                 // ideographic space is whitespace
add('   ', 2);                                       // head becomes empty -> just the ellipsis
add('😀'.repeat(4000) + 'end', 3999);    // long astral string
add('word '.repeat(4000), 19999);
console.log('tests', tests.length, 'size KB', sizeKB(tests));
console.log(JSON.stringify(tests.filter((t) => t.args[0].length < 60).map((t) => t.expected)));
fs.writeFileSync('e2/tests.json', JSON.stringify({ functionName: 'smartTruncate', tests }));
check('ref', fs.readFileSync('e2/ref.js', 'utf8'), 'smartTruncate', tests);
// naive (UTF-16 length + trimEnd) for contrast
check('naive utf16', "function smartTruncate(t,n){ if (t.length<=n) return t; if(n<1) return ''; return t.slice(0,n-1).trimEnd()+'\\u2026'; }", 'smartTruncate', tests);
