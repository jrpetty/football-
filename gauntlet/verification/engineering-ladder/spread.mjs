// Scores every scripted solution on every level through Gauntlet's real scorer (fair-play check, the code-js
// sandbox with each level's time limit, the stub gate) and records the reference's slowest hidden test (best of
// three runs per test). Writes measurements.json (read by gen.mjs for the case notes) and prints the spread table.
//
//   node spread.mjs [solution ...]      # default: all of them
import fs from 'node:fs';
import os from 'node:os';
import { scoreResponse } from '../../src/scoring/index.ts';

const TEST = JSON.parse(fs.readFileSync(new URL('../../tests/coding/engineering-ladder.json', import.meta.url), 'utf8'));
export const SOLUTIONS = [
  ['ref', './ref.js', 'reference'],
  ['slips', './solutions/slips.js', 'careful with slips'],
  ['plain', './solutions/plain.js', 'plain model-style'],
  ['careless', './solutions/careless.js', 'careless'],
  ['naive', './solutions/naive.js', 'correct but naive'],
  ['stub', './solutions/stub.js', 'do nothing ([0, 0])'],
  ['empty', './solutions/empty.js', 'do nothing ([])'],
];
const pick = process.argv.slice(2);
const chosen = pick.length ? SOLUTIONS.filter(([n]) => pick.includes(n)) : SOLUTIONS;

const noJudges = { ids: [], ask: async () => [] };
const art = (name) => ({ name, kind: 'text', file: name, bytes: 0 });
const results = {};
const msOf = (its) => its.map((i) => Number(/^([\d.]+) ms$/.exec(i.detail ?? '')?.[1] ?? NaN));
for (const c of TEST.cases) {
  results[c.id] = {};
  for (const [name, file] of chosen) {
    const code = fs.readFileSync(new URL(file, import.meta.url), 'utf8');
    const score = () => scoreResponse({ scorer: c.scorer, expected: c.expected, response: '```javascript\n' + code + '\n```', stopReason: 'end', taskText: c.prompt, judges: noJudges, saveArtifact: art, signal: new AbortController().signal });
    const t0 = Date.now();
    const out = await score();
    const items = out.detail.items ?? [];
    let perTest = msOf(items);
    if (name === 'ref') for (let k = 0; k < 2; k++) perTest = msOf((await score()).detail.items ?? []).map((x, i) => Math.min(x, perTest[i]));
    const ms = perTest.filter((x) => !Number.isNaN(x));
    const slow = items.filter((i) => /Timed out|stopped|memory/.test(i.detail ?? '')).length;
    results[c.id][name] = { score: out.score, passed: items.filter((i) => i.passed).length, total: items.length, maxMs: ms.length ? Math.round(Math.max(...ms)) : null, slow, wall: Date.now() - t0 };
    const r = results[c.id][name];
    console.log(`${c.id} ${name.padEnd(8)} ${String(Math.round((r.score ?? 0) * 100)).padStart(3)}%  ${r.passed}/${r.total}  max ${r.maxMs} ms  out of time ${r.slow}  (${(r.wall / 1000).toFixed(1)} s)`);
  }
}

if (!pick.length) {
  const meas = {};
  for (const c of TEST.cases) {
    const r = results[c.id];
    const pct = (n) => `${Math.round(r[n].score * 100)}%`;
    meas[c.id] = {
      platform: `${os.platform()} ${os.cpus()[0]?.model?.trim() ?? ''}`.trim(),
      ref: { maxMs: r.ref.maxMs },
      spread: Object.fromEntries(SOLUTIONS.map(([n]) => [n, { score: r[n].score, passed: r[n].passed, total: r[n].total, outOfTime: r[n].slow, maxMs: r[n].maxMs }])),
      summary: `slips.js (careful with slips) ${pct('slips')}, plain.js (plain lazy segment tree) ${pct('plain')}${r.plain.slow ? ` (${r.plain.slow} out of time)` : ''}, careless.js ${pct('careless')}, naive.js (a loop per operation) ${pct('naive')}${r.naive.slow ? ` (${r.naive.slow} out of time)` : ''}, do-nothing stubs ${pct('stub')}/${pct('empty')}`,
    };
  }
  fs.writeFileSync(new URL('./measurements.json', import.meta.url), JSON.stringify(meas, null, 1) + '\n');
  console.log('\nlevel   ' + SOLUTIONS.map(([n]) => n.padStart(9)).join(''));
  for (const c of TEST.cases) console.log(`${c.id}     ` + SOLUTIONS.map(([n]) => `${Math.round(results[c.id][n].score * 100)}%`.padStart(9)).join('') + `   ref max ${results[c.id].ref.maxMs} ms / limit ${c.scorer.timeoutMs} ms`);
}
