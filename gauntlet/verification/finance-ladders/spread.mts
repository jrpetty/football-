// Scores scripted answers for every level of the four finance ladders with the REAL scorer and prints the spread:
// points per level, prompt tokens, and what the reference, a careful answer with one slip, a careless answer,
// do-nothing, a copy of the prompt and random filler score. Run from gauntlet/:  node verification/finance-ladders/spread.mts
import { readFileSync } from 'node:fs';
import { loadTests, caseScorer, renderCase } from '../../src/core/registry.ts';
import { scoreResponse } from '../../src/scoring/index.ts';
import { compareJson } from '../../src/scoring/json-compare.ts';
import { createRng } from '../../src/core/rng.ts';
import type { PromptTest, ScorerSpec } from '../../src/core/types.ts';

export const LADDER_IDS = ['work.invoices-ladder', 'work.payroll-ladder', 'work.expenses-ladder', 'work.reconciliation-ladder'];
/** A rung is passed at this share of its points (partial-credit ladders). */
export const PASS_MARK = 0.9;

type Scripted = Record<string, Record<string, { careless: unknown; slip: unknown; slip_kind: string } & Record<string, unknown>>>;
export const SCRIPTED: Scripted = {
  ...JSON.parse(readFileSync(new URL('./scripted-business.json', import.meta.url), 'utf8')),
  ...JSON.parse(readFileSync(new URL('./scripted-work.json', import.meta.url), 'utf8')),
};

const judges = { ids: [], ask: async () => [] };
export async function scoreReply(scorer: ScorerSpec, expected: unknown, response: string, taskText = '') {
  return scoreResponse({ scorer, expected, response, stopReason: 'end', taskText, judges, saveArtifact: (name: string) => ({ name, kind: 'text' as const, file: name, bytes: 0 }), signal: new AbortController().signal });
}

export function ladderTests(): PromptTest[] {
  const all = loadTests();
  return LADDER_IDS.map((id) => all.find((t) => t.definition.id === id)!.definition as PromptTest);
}

export function filler(seed: number): string {
  const words = 'the model result answer benchmark quickly random value system data story river light lamp order help please thanks store product price name city year'.split(' ');
  const rng = createRng(seed);
  return Array.from({ length: 20 + rng.int(0, 120) }, () => rng.pick(words)).join(' ') + '.';
}

export function points(t: PromptTest, c: PromptTest['cases'][number]): number {
  const sc = caseScorer(t, c);
  if (sc.type !== 'json') throw new Error('json scorer expected');
  return compareJson(c.expected, c.expected, { lenient: sc.lenient, sets: sc.sets, unorderedArrays: sc.unorderedArrays, weights: sc.weights }).total;
}

export interface Row {
  test: string;
  level: string;
  points: number;
  tokens: number;
  reference: number;
  slip: number;
  slipKind: string;
  /** One reasoning error that spreads through the chain (null on one-month levels without chains). */
  chain: number | null;
  careless: number;
  nothing: number;
  copy: number;
  random: number;
}

const asJson = (x: unknown) => '```json\n' + JSON.stringify(x) + '\n```';

export async function spread(): Promise<Row[]> {
  const rows: Row[] = [];
  for (const t of ladderTests()) {
    for (const c of t.cases) {
      const sc = caseScorer(t, c);
      const task = renderCase(t, c).turns.join('\n');
      const s = SCRIPTED[t.id]![c.id]!;
      const reference = (await scoreReply(sc, c.expected, asJson(c.expected), task)).score ?? 0;
      const slip = (await scoreReply(sc, c.expected, asJson(s.slip), task)).score ?? 0;
      const careless = (await scoreReply(sc, c.expected, asJson(s.careless), task)).score ?? 0;
      const chain = s.chain ? (await scoreReply(sc, c.expected, asJson(s.chain), task)).score ?? 0 : null;
      const nothing = (await scoreReply(sc, c.expected, '{}', task)).score ?? 0;
      const copy = (await scoreReply(sc, c.expected, task, task)).score ?? 0;
      let rnd = 0;
      for (let k = 0; k < 4; k++) rnd += (await scoreReply(sc, c.expected, filler(311 + k * 17 + rows.length), task)).score ?? 0;
      rows.push({ test: t.id, level: c.id, points: Math.round(points(t, c)), tokens: Math.ceil(c.prompt!.length / 3.6) + 20, reference, slip, slipKind: s.slip_kind, chain, careless, nothing, copy, random: rnd / 4 });
    }
  }
  return rows;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const rows = await spread();
  const pct = (x: number) => `${Math.round(x * 100)}%`;
  for (const id of LADDER_IDS) {
    console.log(`\n${id}\nlevel | points | input tokens | reference | slip | chain error | careless | do-nothing | copy-prompt | random | slip`);
    for (const r of rows.filter((x) => x.test === id))
      console.log(`${r.level} | ${r.points} | ${r.tokens} | ${pct(r.reference)} | ${pct(r.slip)}${r.slip >= PASS_MARK ? ' (passes)' : ''} | ${r.chain === null ? '-' : pct(r.chain)} | ${pct(r.careless)} | ${pct(r.nothing)} | ${pct(r.copy)} | ${pct(r.random)} | ${r.slipKind}`);
  }
}
