// Per-test timings of one solution on one level in the REAL sandbox (same worker, flags and limits as scoring).
//   node sbxtime.mjs L10 ./ref.js [timeoutMs]
import fs from 'node:fs';
import { runCodeTests } from '../../src/scoring/code-sandbox.ts';
const [id, file, to] = process.argv.slice(2);
const TEST = JSON.parse(fs.readFileSync(new URL('../../tests/coding/engineering-ladder.json', import.meta.url), 'utf8'));
const labels = JSON.parse(fs.readFileSync(new URL('./labels.json', import.meta.url), 'utf8'))[id];
const c = TEST.cases.find((x) => x.id === id);
const code = fs.readFileSync(new URL(file, import.meta.url), 'utf8');
const r = await runCodeTests(code, c.expected.functionName, c.expected.tests, Number(to || c.scorer.timeoutMs));
r.items.forEach((it, i) => console.log(String(i + 1).padStart(3), it.passed ? 'ok  ' : 'FAIL', String(it.detail).slice(0, 60).padEnd(30), labels[i]));
console.log(`${file}: ${r.passed}/${r.total}`);
