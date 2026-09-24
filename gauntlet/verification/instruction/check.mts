import { checkConstraints } from '../../src/scoring/constraints.ts';
import fs from 'node:fs';
// usage: node check.mts cases.json  -> each case {id, expected: Constraint[], samples: {pass: string[], fail?: string[]}}
const cases = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
let bad = 0;
for (const c of cases) {
  for (const s of c.samples.pass) {
    const items = checkConstraints(s, c.expected);
    const failed = items.filter((i) => !i.passed);
    if (failed.length) { bad++; console.log(c.id, 'PASS-SAMPLE FAILS:', failed.map((f) => f.label + ' (' + (f.detail ?? '') + ')').join('; ')); }
  }
  for (const s of c.samples.fail ?? []) {
    const items = checkConstraints(s, c.expected);
    const n = items.filter((i) => !i.passed).length;
    if (n === 0) { bad++; console.log(c.id, 'FAIL-SAMPLE unexpectedly passes all'); }
  }
  console.log(c.id, 'ok', c.expected.length, 'constraints');
}
process.exit(bad ? 1 : 0);
