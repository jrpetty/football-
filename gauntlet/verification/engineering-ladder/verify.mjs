// Re-checks every expected result in tests/coding/engineering-ladder.json with programs other than the reference
// that produced it:
//  - brute.c (C, 64-bit integers, one loop per operation) on EVERY hidden test and example, including the largest;
//  - brute.py (Python integers) on every example and every hidden test small enough for it;
//  - the prompt's generator example: brute.py's expansion equals the one printed in the prompt;
//  - the test file regenerates byte for byte (gen.mjs --out).
//   node verify.mjs
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { cBrute, python, same } from './lib.mjs';

const FILE = new URL('../../tests/coding/engineering-ladder.json', import.meta.url);
const text = fs.readFileSync(FILE, 'utf8');
const dry = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'eng-ladder-')), 't.json');
execFileSync(process.execPath, [new URL('./gen.mjs', import.meta.url).pathname, '--out', dry], { stdio: 'ignore' });
if (fs.readFileSync(dry, 'utf8') !== text) { console.log('gen.mjs does NOT reproduce the test file byte for byte'); process.exit(1); }
console.log('gen.mjs reproduces the test file byte for byte');

const TEST = JSON.parse(text);
let bad = 0, viaC = 0, viaPy = 0;
const size = (inp) => (inp.generate ? inp.generate.n * inp.generate.q : inp.values.length * inp.ops.length);
for (const c of TEST.cases) {
  const ex = [...c.prompt.matchAll(/^runEngine\((.*)\)\n\/\/ returns (\[[^\]]*\])/gm)].map((m) => [JSON.parse(m[1]), JSON.parse(m[2])]);
  if (!ex.length) { bad++; console.log(`${c.id}: no examples found in the prompt`); }
  const pyEx = python(ex.map(([a]) => a));
  ex.forEach(([a, e], i) => { if (!same(pyEx[i], e) || !same(cBrute(a), e)) { bad++; console.log(`${c.id}: example ${i + 1} disagrees`); } });
  const small = [];
  c.expected.tests.forEach((t, i) => {
    const got = cBrute(t.args[0]);
    viaC++;
    if (got === 'OVERFLOW') { bad++; console.log(`${c.id}: hidden test ${i + 1} leaves the promised safe range`); }
    else if (!same(got, t.expected)) { bad++; console.log(`${c.id}: hidden test ${i + 1} disagrees with brute.c: ${JSON.stringify(got)} vs ${JSON.stringify(t.expected)}`); }
    if (size(t.args[0]) <= 2e7) small.push(i);
  });
  const want = python(small.map((i) => c.expected.tests[i].args[0]));
  small.forEach((i, j) => { viaPy++; if (!same(want[j], c.expected.tests[i].expected)) { bad++; console.log(`${c.id}: hidden test ${i + 1} disagrees with brute.py`); } });
  console.log(`${c.id}: ${ex.length} examples and ${c.expected.tests.length} hidden tests checked by brute.c (${small.length} also by brute.py)`);
}
// the generator example printed in the prompt, re-expanded by brute.py's own expansion
const gm = /Example: `(\{"generate".*?\})` produces the array (\[.*?\]) and then the operations (\[\[.*?\]\]) /.exec(TEST.cases[1].prompt);
const pyExpand = JSON.parse(execFileSync('python3', ['-B', '-c', 'import json,sys; sys.path.insert(0, sys.argv[1]); import brute; v, o = brute.expand(json.loads(sys.argv[2])); print(json.dumps([v, o]))', path.dirname(new URL('./brute.py', import.meta.url).pathname), gm[1]]).toString());
if (!same(pyExpand, [JSON.parse(gm[2]), JSON.parse(gm[3])])) { bad++; console.log('the generator example in the prompt disagrees with brute.py'); } else console.log('the generator example in the prompt matches brute.py');
console.log(bad ? `${bad} PROBLEMS` : `all good: ${viaC} hidden tests agree with brute.c, ${viaPy} also with brute.py`);
process.exit(bad ? 1 : 0);
