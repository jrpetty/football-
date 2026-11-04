// Scores scripted answers for every Real-World Work case with the real scorer and prints the spread:
// points a case can earn, and what a do-nothing, a copy-the-prompt, random filler, a careless and a careful
// (reference) answer score. Run from gauntlet/:  node verification/realworld-work/spread.mts
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { loadTests, caseScorer, renderCase } from '../../src/core/registry.ts';
import { scoreResponse } from '../../src/scoring/index.ts';
import { createRng } from '../../src/core/rng.ts';
import type { PromptTest, ScorerSpec } from '../../src/core/types.ts';

export const WORK_IDS = ['work.inbox-triage', 'work.meeting-scheduler', 'work.expense-audit', 'work.bank-reconciliation', 'work.contract-redline'];
const HERE = dirname(fileURLToPath(import.meta.url));
export const SCRIPTED: Record<string, Record<string, { careless: unknown }>> = JSON.parse(readFileSync(join(HERE, 'scripted.json'), 'utf8'));

const judges = { ids: [], ask: async () => [] };

export async function scoreReply(scorer: ScorerSpec, expected: unknown, response: string, taskText = '') {
  return scoreResponse({ scorer, expected, response, stopReason: 'end', taskText, judges, saveArtifact: (name: string) => ({ name, kind: 'text' as const, file: name, bytes: 0 }), signal: new AbortController().signal });
}

export function workTests(): PromptTest[] {
  const all = loadTests();
  return WORK_IDS.map((id) => all.find((t) => t.definition.id === id)!.definition as PromptTest);
}

export function filler(seed: number): string {
  const words = 'the model result answer benchmark quickly random value system data story river light lamp order help please thanks store product price name city year'.split(' ');
  const rng = createRng(seed);
  return Array.from({ length: 20 + rng.int(0, 120) }, () => rng.pick(words)).join(' ') + '.';
}

export interface Row {
  test: string;
  caseId: string;
  points: number;
  careful: number;
  careless: number;
  nothing: number;
  copy: number;
  random: number;
}

export async function spread(): Promise<Row[]> {
  const rows: Row[] = [];
  for (const t of workTests()) {
    for (const c of t.cases) {
      const sc = caseScorer(t, c);
      const task = renderCase(t, c).turns.join('\n');
      const careful = await scoreReply(sc, c.expected, '```json\n' + JSON.stringify(c.expected, null, 1) + '\n```', task);
      const careless = await scoreReply(sc, c.expected, '```json\n' + JSON.stringify(SCRIPTED[t.id]![c.id]!.careless) + '\n```', task);
      const nothing = await scoreReply(sc, c.expected, '{}', task);
      const copy = await scoreReply(sc, c.expected, task, task);
      let rnd = 0;
      for (let k = 0; k < 4; k++) rnd += (await scoreReply(sc, c.expected, filler(77 + k * 13 + rows.length), task)).score ?? 0;
      const items = (careful.detail.items ?? []).length;
      rows.push({ test: t.id, caseId: c.id, points: items, careful: careful.score ?? 0, careless: careless.score ?? 0, nothing: nothing.score ?? 0, copy: copy.score ?? 0, random: rnd / 4 });
    }
  }
  return rows;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const rows = await spread();
  const pct = (x: number) => `${Math.round(x * 100)}%`;
  console.log('test | case | points | careful | careless | do-nothing | copy-prompt | random');
  for (const r of rows) console.log(`${r.test} | ${r.caseId} | ${r.points} | ${pct(r.careful)} | ${pct(r.careless)} | ${pct(r.nothing)} | ${pct(r.copy)} | ${pct(r.random)}`);
  for (const id of WORK_IDS) {
    const rs = rows.filter((r) => r.test === id);
    const m = (f: (r: Row) => number) => pct(rs.reduce((a, r) => a + f(r), 0) / rs.length);
    console.log(`MEAN ${id}: points/case ${Math.min(...rs.map((r) => r.points))}-${Math.max(...rs.map((r) => r.points))}, careful ${m((r) => r.careful)}, careless ${m((r) => r.careless)}, do-nothing ${m((r) => r.nothing)}, copy ${m((r) => r.copy)}, random ${m((r) => r.random)}`);
  }
}
