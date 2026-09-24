// Proves every sample answer against the REAL checker (src/scoring/constraints.ts).
// usage: node check.mts cases.json
//   cases.json: [{ id, expected: Constraint[], samples: { pass: string[], fail: string[] } }]
// Every pass sample must satisfy ALL constraints (score 1.0 under allOrNothing);
// every fail sample (a plausible near miss) must break at least one constraint.
import { checkConstraints } from '../../src/scoring/constraints.ts';
import fs from 'node:fs';

const cases = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'));
let bad = 0;
for (const c of cases) {
  for (const s of c.samples.pass) {
    const failed = checkConstraints(s, c.expected).filter((i) => !i.passed);
    if (failed.length) {
      bad++;
      console.log(`${c.id} PASS-SAMPLE FAILS: ${failed.map((f) => `${f.label} (${f.detail ?? ''})`).join('; ')}`);
    }
  }
  for (const s of c.samples.fail ?? []) {
    const failed = checkConstraints(s, c.expected).filter((i) => !i.passed);
    if (failed.length === 0) {
      bad++;
      console.log(`${c.id} FAIL-SAMPLE unexpectedly passes every constraint`);
    } else {
      console.log(`${c.id} near miss correctly rejected by: ${failed.map((f) => `${f.label} (${f.detail ?? ''})`).join('; ')}`);
    }
  }
  console.log(`${c.id} checked: ${c.expected.length} constraints, ${c.samples.pass.length} passing sample(s)`);
}
process.exit(bad ? 1 : 0);
