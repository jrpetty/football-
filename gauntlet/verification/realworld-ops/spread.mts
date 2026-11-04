/**
 * The expected spread of the operations & workflow pack, from scripted answers (not guesses), through the real
 * scoring pipeline (fair-play check included). For every case:
 *  - do-nothing: an empty reply, "{}", the prompt's own answer template copied back, and the prompt itself (the best of these);
 *  - careless: each scripted careless answer the generators produce (one rule misread, a late message missed, no
 *    optimising, a typical slip), averaged per case; only slips that change the answer for that case count;
 *  - careful: the reference answer;
 *  - constant (Reply Within Policy only): one policy-blind reply that names the customer and the order.
 * Run: node verification/realworld-ops/spread.mts [--detail]
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { scoreResponse } from '../../src/scoring/index.ts';
import type { ArtifactRef, ScorerSpec } from '../../src/core/types.ts';
import { WORK_DIR } from './build.mts';
import { buildReorderCases } from './reorder.mts';
import { buildRotaCases } from './rota.mts';
import { buildRoutesCases } from './routes.mts';
import { buildReplyCases } from './replies.mts';
import { buildBoardCases } from './board.mts';
import type { Careless } from './common.mts';

export interface SpreadRow {
  test: string;
  points: string;
  doNothing: number;
  careless: number;
  carelessCases: number;
  careful: number;
  constant?: number;
  perDifficulty: Record<string, { careless: number; n: number }>;
  carelessDetail: string[];
}

type CaseSlips = Array<{ id: string; careless: Careless[]; template?: Record<string, unknown> }>;

const FILES: Record<string, { file: string; slips: () => CaseSlips; points: (c: { expected: unknown }) => string }> = {
  'work.stock-reorder': { file: 'stock-reorder.json', slips: buildReorderCases, points: () => 'plan: 0, under 50, or 100' },
  'work.shift-rota': { file: 'shift-rota.json', slips: buildRotaCases, points: () => 'plan: 0, under 50, or 100' },
  'work.delivery-routes': { file: 'delivery-routes.json', slips: buildRoutesCases, points: () => 'plan: 0, under 50, or 100' },
  'work.support-replies': { file: 'support-replies.json', slips: buildReplyCases, points: () => '14–17 points' },
  'work.board-report': { file: 'board-report.json', slips: buildBoardCases, points: () => '12 KPIs, 17 points' },
};

const noJudges = { ids: [], ask: async () => [] };
const noArtifacts = (name: string): ArtifactRef => ({ name, kind: 'text', file: name, bytes: 0 });
export const asReply = (a: unknown) => 'Here is my answer.\n\n```json\n' + JSON.stringify(a, null, 2) + '\n```';

export async function markOne(scorer: ScorerSpec, expected: unknown, prompt: string, response: string): Promise<number> {
  const out = await scoreResponse({ scorer, expected, response, stopReason: 'end', taskText: prompt, judges: noJudges, saveArtifact: noArtifacts, signal: new AbortController().signal });
  return out.score ?? 0;
}

const referenceOf = (scorer: ScorerSpec, expected: unknown) => (scorer.type === 'verifier' ? (expected as { reference: unknown }).reference : expected);

export async function spread(): Promise<SpreadRow[]> {
  const rows: SpreadRow[] = [];
  for (const [id, f] of Object.entries(FILES)) {
    const def = JSON.parse(readFileSync(join(WORK_DIR, f.file), 'utf8')) as { scorer: ScorerSpec; cases: Array<{ id: string; prompt: string; expected: unknown; notes: string }> };
    const slips = new Map(f.slips().map((c) => [c.id, c]));
    let dn = 0;
    let cl = 0;
    let cf = 0;
    let nc = 0;
    let constant = 0;
    let nConst = 0;
    const per: SpreadRow['perDifficulty'] = {};
    const detail: string[] = [];
    for (const c of def.cases) {
      const template = /```json\n([\s\S]*?)\n```/.exec(c.prompt)?.[1] ?? '{}';
      const nothing = await Promise.all(['', '{}', '```json\n' + template + '\n```', c.prompt].map((r) => markOne(def.scorer, c.expected, c.prompt, r)));
      dn += Math.max(...nothing);
      const ref = referenceOf(def.scorer, c.expected);
      cf += await markOne(def.scorer, c.expected, c.prompt, asReply(ref));
      const s = slips.get(c.id)!;
      const list = s.careless.filter((k) => JSON.stringify(k.answer) !== JSON.stringify(ref));
      const scores = await Promise.all(list.map((k) => markOne(def.scorer, c.expected, c.prompt, asReply(k.answer))));
      const diff = /^\[(\w+)\]/.exec(c.notes)?.[1] ?? '?';
      if (scores.length) {
        const mean = scores.reduce((a, b) => a + b, 0) / scores.length;
        cl += mean;
        nc++;
        per[diff] = { careless: (per[diff]?.careless ?? 0) + mean, n: (per[diff]?.n ?? 0) + 1 };
      }
      list.forEach((k, i) => detail.push(`${c.id} ${k.label}: ${Math.round(scores[i]! * 100)}`));
      if (s.template) {
        constant += await markOne(def.scorer, c.expected, c.prompt, asReply(s.template));
        nConst++;
      }
    }
    const n = def.cases.length;
    rows.push({ test: id, points: `${n} cases × ${Math.round(1000 / n) / 10} points; ${f.points(def.cases[0]!)}`, doNothing: dn / n, careless: nc ? cl / nc : 1, carelessCases: nc, careful: cf / n, ...(nConst ? { constant: constant / nConst } : {}), perDifficulty: per, carelessDetail: detail });
  }
  return rows;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const pct = (x: number) => `${Math.round(x * 1000) / 10}`;
  for (const r of await spread()) {
    console.log(
      `${r.test.padEnd(22)} ${r.points.padEnd(48)} do-nothing ${pct(r.doNothing).padStart(5)}  careless ${pct(r.careless).padStart(5)} (${r.carelessCases} cases)  careful ${pct(r.careful).padStart(5)}${r.constant !== undefined ? `  constant ${pct(r.constant)}` : ''} | careless by difficulty: ${Object.entries(r.perDifficulty)
        .map(([d, v]) => `${d} ${pct(v.careless / v.n)}`)
        .join(', ')}`,
    );
    if (process.argv.includes('--detail')) for (const d of r.carelessDetail) console.log(`    ${d}`);
  }
}
