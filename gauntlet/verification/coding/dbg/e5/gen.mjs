import fs from 'node:fs';
import { rng, load, check, sizeKB } from '../../lib.mjs';
const ref = load('e5/ref.js', 'romanToInt');
const tests = [];
const add = (s) => tests.push({ args: [s], expected: ref(s) });
for (const s of ['I', 'III', 'IV', 'IX', 'XIV', 'XIX', 'XL', 'XLIX', 'LVIII', 'XC', 'XCIX', 'CXC', 'CCCXC', 'CD', 'DXL', 'CM', 'MCMXCIV', 'MMMCMXCIX', 'MMXXVI', 'CDXLIV', 'DCCCLXXXVIII', 'MMMDCCCLXXXVIII',
  '', 'IIII', 'VV', 'VX', 'IL', 'IC', 'IIV', 'MMMM', 'IXI', 'XCX', 'iv', 'Mcm', 'XIIII', 'VIV', 'MMMMCMXCIX', ' X', 'IVX', 'XXC', 'CCD']) add(s);
// Also: all 3999 valid numerals in one bulk-check test would be large; instead sample systematically
console.log('tests', tests.length, 'size KB', sizeKB(tests));
console.log(JSON.stringify(tests.map((t) => [t.args[0], t.expected])));
fs.writeFileSync('e5/tests.json', JSON.stringify({ functionName: 'romanToInt', tests }));
check('ref', fs.readFileSync('e5/ref.js', 'utf8'), 'romanToInt', tests);
check('lenient (classic LeetCode) version', "function romanToInt(s){const v={I:1,V:5,X:10,L:50,C:100,D:500,M:1000};let t=0;for(let i=0;i<s.length;i++){const a=v[s[i]],b=v[s[i+1]]||0;t+=a<b?-a:a;}return t;}", 'romanToInt', tests);
