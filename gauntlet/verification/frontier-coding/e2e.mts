// End-to-end check of tests/coding/frontier.json through Gauntlet's REAL scorer (src/scoring/index.ts, real sandbox):
// every reference solution must score 1.0; every plausible-but-flawed solution must score clearly lower.
// Usage (from this folder): node e2e.mts
import fs from 'node:fs';
import { scoreResponse } from '../../src/scoring/index.ts';
import { renderCase } from '../../src/core/registry.ts';
const def = JSON.parse(fs.readFileSync(new URL('../../tests/coding/frontier.json', import.meta.url), 'utf8'));
const HERE = new URL('.', import.meta.url).pathname;
const input = (scorer: any, expected: any, response: string) => ({
  scorer, expected, response, stopReason: 'end' as const, taskText: '', judges: { ids: [], ask: async () => [] },
  saveArtifact: (name: string, kind: any) => ({ name, kind, file: name, bytes: 0 }), signal: new AbortController().signal,
});
let problems = 0;
for (const c of def.cases) {
  const dir = 'c' + c.id.slice(1);
  const scorer = c.scorer ?? def.scorer;
  renderCase(def, c);
  const files = fs.readdirSync(`${HERE}/${dir}`).filter((f) => f.endsWith('.js')).sort((a, b) => (a === 'ref.js' ? -1 : b === 'ref.js' ? 1 : a.localeCompare(b)));
  const row: string[] = [];
  for (const f of files) {
    const code = fs.readFileSync(`${HERE}/${dir}/${f}`, 'utf8');
    const r = await scoreResponse(input(scorer, c.expected, 'Here is my solution.\n\n```javascript\n' + code + '\n```\n') as any);
    row.push(`${f.replace('.js', '')} ${r.summary.replace(' unit tests passed', '')} (${(r.score ?? 0).toFixed(2)})`);
    if (f === 'ref.js' && r.score !== 1) { problems++; console.log('REFERENCE NOT 1.0', c.id, r.summary); }
    if (f.startsWith('naive') && (r.score ?? 0) >= 0.9) { problems++; console.log('NAIVE TOO HIGH', c.id, f, r.summary); }
  }
  const wrong = await scoreResponse(input(scorer, c.expected, '```javascript\nfunction ' + c.expected.functionName + '() { return null; }\n```') as any);
  if ((wrong.score ?? 0) > 0) { problems++; console.log('STUB SCORED', c.id, wrong.summary); }
  console.log(`${c.id} (timeout ${scorer.timeoutMs} ms): ` + row.join(' | '));
}
console.log(problems ? `${problems} PROBLEMS` : 'e2e OK: every reference scores 1.0, every naive variant and a stub score clearly lower');
process.exit(problems ? 1 : 0);
