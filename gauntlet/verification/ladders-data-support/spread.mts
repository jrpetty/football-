// Scores scripted answers level by level with the REAL scorers and prints each ladder's spread:
//   node verification/ladders-data-support/spread.mts
// JSON ladders (customer list, support queue, contract): the key, do-nothing, and every scripted answer the
// generators keep (scripted.json, contract-scripted.json). SQL ladder: the reference query, each careless query
// (one realistic slip) and SELECT * from the first table, run in the scorer's sandbox on all four databases.
import { readFileSync } from 'node:fs';
import { loadTests, renderCase } from '../../src/core/registry.ts';
import { scoreResponse } from '../../src/scoring/index.ts';
import type { PromptTest, SqlExpected } from '../../src/core/types.ts';
import { LADDER_QUESTIONS } from './sql-ladder.mts';

const scripted = {
  ...(JSON.parse(readFileSync(new URL('./scripted.json', import.meta.url), 'utf8')) as Record<string, Record<string, Record<string, unknown>>>),
  ...(JSON.parse(readFileSync(new URL('./contract-scripted.json', import.meta.url), 'utf8')) as Record<string, Record<string, Record<string, unknown>>>),
};
const tests = new Map(loadTests().map((t) => [t.definition.id, t.definition as PromptTest]));

export async function scoreReply(def: PromptTest, caseId: string, response: string): Promise<number> {
  const c = def.cases.find((x) => x.id === caseId)!;
  const out = await scoreResponse({
    scorer: c.scorer ?? def.scorer,
    expected: c.expected,
    response,
    stopReason: 'end',
    taskText: renderCase(def, c).turns.join('\n'),
    judges: { ids: [], ask: async () => [] },
    saveArtifact: (name) => ({ name, kind: 'text', file: name, bytes: 0 }),
    signal: new AbortController().signal,
  });
  return out.score ?? 0;
}

const pct = (x: number) => `${Math.round(x * 100)}`.padStart(4);
const json = (v: unknown) => '```json\n' + JSON.stringify(v) + '\n```';

const isMain = process.argv[1] && import.meta.url.endsWith(process.argv[1].split('/').pop()!);
if (isMain) {
  for (const id of ['work.crm-ladder', 'work.support-ladder', 'work.contract-ladder']) {
    const def = tests.get(id)!;
    const kinds = ['reference', 'do-nothing', ...new Set(Object.values(scripted[id]!).flatMap((c) => Object.keys(c)))];
    console.log(`\n${id}  (score per level, %)`);
    console.log(`  ${'answer'.padEnd(34)}${def.cases.map((c) => c.id.padStart(4)).join('')}`);
    for (const kind of kinds) {
      const row: number[] = [];
      for (const c of def.cases) {
        const ans = kind === 'reference' ? c.expected : kind === 'do-nothing' ? {} : scripted[id]![c.id]![kind];
        row.push(ans === undefined ? NaN : await scoreReply(def, c.id, json(ans)));
      }
      console.log(`  ${kind.padEnd(34)}${row.map((x) => (Number.isNaN(x) ? '   -' : pct(x))).join('')}`);
    }
  }
  const def = tests.get('work.sql-ladder')!;
  console.log(`\nwork.sql-ladder  (score per level, %)`);
  console.log(`  ${'answer'.padEnd(34)}${def.cases.map((c) => c.id.padStart(4)).join('')}`);
  const fence = (sql: string) => '```sql\n' + sql + '\n```';
  const ref: number[] = [];
  const star: number[] = [];
  const slipBest: number[] = [];
  const slipWorst: number[] = [];
  for (const [i, c] of def.cases.entries()) {
    ref.push(await scoreReply(def, c.id, fence((c.expected as SqlExpected).reference)));
    star.push(await scoreReply(def, c.id, fence('SELECT * FROM dim_customer')));
    const slips = await Promise.all(LADDER_QUESTIONS[i]!.careless.map((s) => scoreReply(def, c.id, fence(s.sql))));
    slipBest.push(Math.max(...slips));
    slipWorst.push(Math.min(...slips));
  }
  for (const [k, row] of [['reference', ref], ['best single slip', slipBest], ['worst single slip', slipWorst], ['SELECT * (do-nothing)', star]] as const)
    console.log(`  ${k.padEnd(34)}${row.map(pct).join('')}`);
}
