import fs from 'node:fs';
import { rng, load, check, sizeKB } from '../../lib.mjs';
const ref = load('e4/ref.js', 'parseCsvRecord');
const R = rng(1404);
// random fuzz set for the python cross-check
const fuzz = [];
for (let t = 0; t < 20000; t++) { let s = ''; const L = R.int(0, 10); for (let k = 0; k < L; k++) s += R.pick(['a', 'b', ',', '"', '"', ' ', '\n', 'é']); fuzz.push({ args: [s], expected: ref(s) }); }
fs.writeFileSync('e4/fuzz.json', JSON.stringify({ functionName: 'parseCsvRecord', tests: fuzz }));
const tests = [];
const add = (s) => tests.push({ args: [s], expected: ref(s) });
add('a,b,c');
add('');
add(',');
add('a,,c,');
add(' a , b ');
add('"x,y",z');
add('"she said ""hi""",ok');
add('""');
add('"",""');
add('"multi\nline",end');
add('"unterminated,field');
add('ab"c,d');
add('"abc"d,e');
add('"abc" ,e');
add(' "abc",e');
add('"""",x');
add('a,"b""c""d",e,""""""');
add('naïve,"café, crème",😀');
{ let s = ''; for (let k = 0; k < 3000; k++) s += (k ? ',' : '') + (k % 3 === 0 ? '"f' + k + ',""q""' + '"' : 'v' + k); add(s); }
console.log('tests', tests.length, 'size KB', sizeKB(tests));
console.log(JSON.stringify(tests.slice(0, 18).map((t) => t.expected)));
fs.writeFileSync('e4/tests.json', JSON.stringify({ functionName: 'parseCsvRecord', tests }));
check('ref', fs.readFileSync('e4/ref.js', 'utf8'), 'parseCsvRecord', tests);
