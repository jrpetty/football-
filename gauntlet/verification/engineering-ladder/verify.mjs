// Re-checks every expected reply in tests/coding/engineering-ladder.json against implementations other than the
// reference that produced it:
//  - alt.js (second JavaScript implementation, different data structures) on EVERY hidden test and example;
//  - the Python oracle (literal model) on every example and every hidden test small enough for it
//    (commands x store size up to 6e7), which is all of them except the largest few per level;
//  - the test file regenerates byte-identically (gen.mjs) and every prompt example is reproduced.
//   node verify.mjs
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { load, oracle, same } from './lib.mjs';

const FILE = new URL('../../tests/coding/engineering-ladder.json', import.meta.url);
const after = fs.readFileSync(FILE, 'utf8');
const dry = join(mkdtempSync(join(tmpdir(), 'eng-ladder-')), 't.json');
execFileSync(process.execPath, [new URL('./gen.mjs', import.meta.url).pathname, '--out', dry], { stdio: 'ignore' });
if (fs.readFileSync(dry, 'utf8') !== after) { console.log('gen.mjs does NOT reproduce the test file byte for byte'); process.exit(1); }
console.log('gen.mjs reproduces the test file byte for byte');

const TEST = JSON.parse(after);
const alt = load('./alt.js');
let bad = 0, viaOracle = 0, viaAlt = 0;
for (const c of TEST.cases) {
  // examples, as printed in the prompt
  const ex = [...c.prompt.matchAll(/^runStore\((.*)\)\n\/\/ returns (.*)$/gm)].map((m) => [JSON.parse(m[1]), JSON.parse(m[2])]);
  if (!ex.length) { console.log(`${c.id}: no examples found in the prompt`); bad++; }
  const exWant = oracle(ex.map(([a]) => a));
  ex.forEach(([a, e], i) => { if (!same(exWant[i], e) || !same(alt(a), e)) { bad++; console.log(`${c.id}: example ${i + 1} disagrees`); } });
  const small = [];
  c.expected.tests.forEach((t, i) => {
    const cmds = t.args[0];
    if (!same(alt(cmds), t.expected)) { bad++; console.log(`${c.id}: hidden test ${i + 1} disagrees with alt.js`); }
    viaAlt++;
    const store = cmds.reduce((s, x) => s + (x[0] === 'POPULATE' ? Number(x[2]) : 1), 0);
    if (cmds.length * store <= 6e7) small.push(i);
  });
  const want = oracle(small.map((i) => c.expected.tests[i].args[0]));
  small.forEach((i, j) => { viaOracle++; if (!same(want[j], c.expected.tests[i].expected)) { bad++; console.log(`${c.id}: hidden test ${i + 1} disagrees with the Python oracle`); } });
  console.log(`${c.id}: ${ex.length} examples and ${c.expected.tests.length} hidden tests checked (${small.length} also by the Python oracle)`);
}
console.log(bad ? `${bad} PROBLEMS` : `all good: ${viaAlt} hidden tests agree with alt.js, ${viaOracle} also with the Python oracle`);
process.exit(bad ? 1 : 0);
