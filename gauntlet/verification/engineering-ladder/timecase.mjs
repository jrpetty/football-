// Times one solution on every hidden test of one level, in process (no sandbox), to find slow tests quickly.
//   node timecase.mjs L09 ./ref.js
import fs from 'node:fs';
import { load, same } from './lib.mjs';
const [id, file] = process.argv.slice(2);
const TEST = JSON.parse(fs.readFileSync(new URL('../../tests/coding/engineering-ladder.json', import.meta.url), 'utf8'));
const labels = JSON.parse(fs.readFileSync(new URL('./labels.json', import.meta.url), 'utf8'))[id];
const c = TEST.cases.find((x) => x.id === id);
const f = load(file);
c.expected.tests.forEach((t, i) => {
  const t0 = performance.now();
  const got = f(...t.args);
  const ms = performance.now() - t0;
  console.log(String(i + 1).padStart(3), same(got, t.expected) ? 'ok  ' : 'FAIL', ms.toFixed(0).padStart(6), 'ms', t.args[0].length, 'cmds', labels[i]);
});
