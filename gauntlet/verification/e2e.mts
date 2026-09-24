// End-to-end check of every deterministic case through Gauntlet's real scorer (src/scoring/index.ts),
// including the real code sandbox for code-js cases.
import fs from 'node:fs';
import path from 'node:path';
import { scoreResponse } from '../src/scoring/index.ts';
import { renderCase, needsFinalAnswer } from '../src/core/registry.ts';

const ROOT = new URL('../tests', import.meta.url).pathname;
const SCR = new URL('.', import.meta.url).pathname.replace(/\/$/, '');
const mine = ['reasoning/deduction-grid', 'reasoning/truth-tellers', 'reasoning/deduction-grid-extreme', 'reasoning/truth-tellers-extreme', 'reasoning/planning', 'math/competition', 'math/word-problems',
  'coding/algorithms', 'coding/debug-and-edge-cases', 'coding/hard', 'instruction/precision-formatting', 'instruction/system-prompt-adherence',
  'extraction/structured-json'];
const refDir: Record<string, string> = { 'coding.algorithms': 'coding/alg', 'coding.debug-and-edge-cases': 'coding/dbg', 'coding.hard': 'coding/hard' };
const samples: Record<string, Record<string, string[]>> = {
  'instruction.precision-formatting': Object.fromEntries(JSON.parse(fs.readFileSync(`${SCR}/instruction/precision_samples.json`, 'utf8')).map((c: any) => [c.id, c.samples.pass])),
  'instruction.system-prompt-adherence': Object.fromEntries(JSON.parse(fs.readFileSync(`${SCR}/instruction/sys_samples.json`, 'utf8')).map((c: any) => [c.id, c.samples.pass])),
};
const input = (scorer: any, expected: any, response: string) => ({
  scorer, expected, response, stopReason: 'end' as const, taskText: '', judges: { ids: [], ask: async () => [] },
  saveArtifact: (name: string, kind: any, content: any) => ({ name, kind, file: name, bytes: 0 }), signal: new AbortController().signal,
});
let bad = 0, n = 0;
for (const rel of mine) {
  const def = JSON.parse(fs.readFileSync(path.join(ROOT, rel + '.json'), 'utf8'));
  let ok = 0;
  for (const c of def.cases) {
    const scorer = c.scorer ?? def.scorer;
    const rendered = renderCase(def, c);
    const last = rendered.turns[rendered.turns.length - 1];
    if (needsFinalAnswer(scorer) && !last.includes('FINAL ANSWER: <answer>')) { console.log('missing FINAL ANSWER instruction', def.id, c.id); bad++; }
    let good = '', wrong: string | null = null;
    if (scorer.type === 'exact') { const e = Array.isArray(c.expected) ? c.expected[0] : c.expected; good = `Let me work through it.\n\n**FINAL ANSWER: ${e}**`; wrong = `FINAL ANSWER: ${e.split(', ').reverse().join(', ')}x`; }
    else if (scorer.type === 'number') { good = `Some working...\nFINAL ANSWER: ${c.expected}`; wrong = `FINAL ANSWER: ${c.expected + 1}`; }
    else if (scorer.type === 'json') { good = '```json\n' + JSON.stringify(c.expected, null, 2) + '\n```'; wrong = '{"nothing": true}'; }
    else if (scorer.type === 'constraints') { good = samples[def.id][c.id][0]; wrong = 'Sure! Here is my answer, as requested.'; }
    else if (scorer.type === 'code-js') { good = 'Here is the solution:\n```javascript\n' + fs.readFileSync(`${SCR}/${refDir[def.id]}/${c.id}/ref.js`, 'utf8') + '\n```'; wrong = '```javascript\nfunction ' + c.expected.functionName + '() { return 42; }\n```'; }
    const r = await scoreResponse(input(scorer, c.expected, good) as any);
    n++;
    if (r.score !== 1) { bad++; console.log('GOOD ANSWER NOT FULL SCORE', def.id, c.id, r.score, r.summary); } else ok++;
    if (wrong !== null) {
      const w = await scoreResponse(input(scorer, c.expected, wrong) as any);
      if ((w.score ?? 0) >= 1) { bad++; console.log('WRONG ANSWER SCORED FULL', def.id, c.id, w.summary); }
    }
  }
  console.log(`${def.id}: ${ok}/${def.cases.length} reference answers score 1.0`);
}
console.log(bad ? `${bad} problems` : `all ${n} deterministic cases verified end-to-end`);
process.exit(bad ? 1 : 0);
