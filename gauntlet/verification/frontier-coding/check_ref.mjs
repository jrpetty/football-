// usage: node check_ref.mjs c1  -- the reference must reproduce every example and every hidden test (in-process VM).
import fs from 'node:fs';
import { load, deepEqual } from './lib.mjs';
const cid = process.argv[2];
const EX = JSON.parse(fs.readFileSync(new URL('./examples.json', import.meta.url), 'utf8'))[cid];
const spec = JSON.parse(fs.readFileSync(new URL(`./${cid}/tests.json`, import.meta.url), 'utf8'));
const f = load(`./${cid}/ref.js`, EX.fn);
let bad = 0;
for (const [a, e] of EX.examples) if (!deepEqual(f(...a), e)) { bad++; console.log('example mismatch', JSON.stringify(a).slice(0, 100)); }
spec.tests.forEach((t, i) => { if (!deepEqual(f(...t.args), t.expected)) { bad++; console.log('test mismatch', i); } });
console.log(`${cid}: reference reproduces ${EX.examples.length} examples and ${spec.tests.length} hidden tests${bad ? ` -- ${bad} PROBLEMS` : ''}`);
process.exit(bad ? 1 : 0);
