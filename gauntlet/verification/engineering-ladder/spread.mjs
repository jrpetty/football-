// Scores every scripted solution on every level through Gauntlet's real scorer (fair-play check, the code-js
// sandbox with each level's time limit, the stub gate), one level at a time, and records the reference's slowest
// hidden test (best of three runs per test). Writes measurements.json (read by gen.mjs for the case notes) and prints the spread table.
//
//   node spread.mjs [solution ...]      # default: all of them
import fs from 'node:fs';
import os from 'node:os';
import { scoreResponse } from '../../src/scoring/index.ts';

const TEST = JSON.parse(fs.readFileSync(new URL('../../tests/coding/engineering-ladder.json', import.meta.url), 'utf8'));
const SOLUTIONS = [
  ['ref', './ref.js', 'reference'],
  ['alt', './alt.js', 'careful, simpler structures'],
  ['slips', './solutions/slips.js', 'careful with slips'],
  ['naive', './solutions/naive.js', 'correct but naive'],
  ['careless', './solutions/careless.js', 'careless'],
  ['stub', './solutions/stub.js', 'do nothing (OK to all)'],
  ['empty', './solutions/empty.js', 'do nothing (no replies)'],
];
const pick = process.argv.slice(2);
const chosen = pick.length ? SOLUTIONS.filter(([n]) => pick.includes(n)) : SOLUTIONS;

const noJudges = { ids: [], ask: async () => [] };
const art = (name) => ({ name, kind: 'text', file: name, bytes: 0 });
const results = {};
for (const c of TEST.cases) {
  results[c.id] = {};
  for (const [name, file] of chosen) {
    const code = fs.readFileSync(new URL(file, import.meta.url), 'utf8');
    const t0 = Date.now();
    const out = await scoreResponse({
      scorer: c.scorer, expected: c.expected, response: '```javascript\n' + code + '\n```', stopReason: 'end', taskText: c.prompt,
      judges: noJudges, saveArtifact: art, signal: new AbortController().signal,
    });
    const items = out.detail.items ?? [];
    const msOf = (its) => its.map((i) => Number(/^([\d.]+) ms$/.exec(i.detail ?? '')?.[1] ?? NaN));
    let perTest = msOf(items);
    if (name === 'ref') {
      // the reference's time per test is the best of three runs, so a busy machine does not inflate it
      for (let k = 0; k < 2; k++) {
        const again = await scoreResponse({ scorer: c.scorer, expected: c.expected, response: '```javascript\n' + code + '\n```', stopReason: 'end', taskText: c.prompt, judges: noJudges, saveArtifact: art, signal: new AbortController().signal });
        perTest = msOf(again.detail.items ?? []).map((x, i) => Math.min(x, perTest[i]));
      }
    }
    const ms = perTest.filter((x) => !Number.isNaN(x));
    const timeouts = items.filter((i) => /Timed out|stopped/.test(i.detail ?? '')).length;
    results[c.id][name] = { score: out.score, passed: items.filter((i) => i.passed).length, total: items.length, maxMs: ms.length ? Math.round(Math.max(...ms)) : null, timeouts, summary: out.summary, wall: Date.now() - t0 };
    const r = results[c.id][name];
    console.log(`${c.id} ${name.padEnd(8)} ${String(Math.round((r.score ?? 0) * 100)).padStart(3)}%  ${r.passed}/${r.total}  max ${r.maxMs} ms  timeouts ${r.timeouts}  (${(r.wall / 1000).toFixed(1)} s)${r.passed && !r.score ? '  ' + r.summary : ''}`);
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
      spread: Object.fromEntries(SOLUTIONS.map(([n]) => [n, { score: r[n].score, passed: r[n].passed, total: r[n].total, timeouts: r[n].timeouts, maxMs: r[n].maxMs }])),
      summary: `alt.js (careful, simpler structures) ${pct('alt')}, slips.js (careful with slips) ${pct('slips')}, naive.js (correct, copies and scans) ${pct('naive')}${r.naive.timeouts ? ` (${r.naive.timeouts} time-outs)` : ''}, careless.js ${pct('careless')}, do-nothing stubs ${pct('stub')}/${pct('empty')}`,
    };
  }
  fs.writeFileSync(new URL('./measurements.json', import.meta.url), JSON.stringify(meas, null, 1) + '\n');
  console.log('\nlevel   ' + SOLUTIONS.map(([n]) => n.padStart(8)).join(''));
  for (const c of TEST.cases) console.log(`${c.id}     ` + SOLUTIONS.map(([n]) => `${Math.round(results[c.id][n].score * 100)}%`.padStart(8)).join('') + `   ref max ${results[c.id].ref.maxMs} ms / limit ${c.scorer.timeoutMs} ms`);
}
