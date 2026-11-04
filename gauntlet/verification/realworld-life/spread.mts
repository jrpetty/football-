/**
 * The expected spread of the Everyday Life pack, from scripted answers (not guesses): for every case, the score of
 *  - do-nothing: an empty reply, "{}", and the prompt's own answer template copied back;
 *  - careless: each scripted careless answer the generators produce (a rule ignored, a late message missed,
 *    no optimising, a typical slip), averaged per case;
 *  - careful: the reference answer.
 * Run: node verification/realworld-life/spread.mts
 */
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { scoreVerifier } from '../../src/scoring/verifier.ts';
import { LIFE_DIR } from './build.mts';
import { buildTripCases } from './trip.mts';
import { buildShoppingCases } from './shopping.mts';
import { buildPackingCases } from './packing.mts';
import { buildBillCases } from './bills.mts';
import { buildTariffCases } from './tariff.mts';
import { buildLoanCases } from './loan.mts';

interface Row {
  test: string;
  points: string;
  doNothing: number;
  /** Mean over the cases that have at least one scripted slip that changes the answer. */
  careless: number;
  carelessCases: number;
  careful: number;
  perDifficulty: Record<string, { careless: number; n: number }>;
  carelessDetail: string[];
}

const FILES: Record<string, { file: string; verifier: string; careless: () => Array<{ id: string; careless: Array<{ label: string; answer: Record<string, unknown> }> }> }> = {
  'life.trip-planner': { file: 'trip-planner.json', verifier: 'trip', careless: buildTripCases },
  'life.bill-split': { file: 'bill-split.json', verifier: 'settle', careless: buildBillCases },
  'life.tariff-chooser': { file: 'tariff-chooser.json', verifier: 'fields', careless: buildTariffCases },
  'life.recipe-shopping': { file: 'recipe-shopping.json', verifier: 'shopping', careless: buildShoppingCases },
  'life.loan-overpayment': { file: 'loan-overpayment.json', verifier: 'fields', careless: buildLoanCases },
  'life.packing-limits': { file: 'packing-limits.json', verifier: 'packing', careless: buildPackingCases },
};

const asReply = (a: unknown) => 'Here is my answer.\n\n```json\n' + JSON.stringify(a, null, 2) + '\n```';

export function spread(): Row[] {
  const rows: Row[] = [];
  for (const [id, f] of Object.entries(FILES)) {
    const def = JSON.parse(readFileSync(join(LIFE_DIR, f.file), 'utf8')) as { cases: Array<{ id: string; prompt: string; expected: { reference: unknown }; notes: string }> };
    const careless = new Map(f.careless().map((c) => [c.id, c.careless]));
    let dn = 0;
    let cl = 0;
    let cf = 0;
    let nc = 0;
    const per: Row['perDifficulty'] = {};
    const detail: string[] = [];
    for (const c of def.cases) {
      const template = /```json\n([\s\S]*?)\n```/.exec(c.prompt)?.[1] ?? '{}';
      const nothing = ['', '{}', '```json\n' + template + '\n```'].map((r) => scoreVerifier(f.verifier, c.expected, r, c.prompt).score);
      dn += Math.max(...nothing);
      cf += scoreVerifier(f.verifier, c.expected, asReply(c.expected.reference), c.prompt).score;
      // Only slips that change the answer for this case count (a "forgot VAT" slip on a case with no VAT is no slip).
      const list = (careless.get(c.id) ?? []).filter((k) => JSON.stringify(k.answer) !== JSON.stringify(c.expected.reference));
      const scores = list.map((k) => scoreVerifier(f.verifier, c.expected, asReply(k.answer), c.prompt).score);
      const diff = /^\[(\w+)\]/.exec(c.notes)?.[1] ?? '?';
      if (scores.length) {
        const mean = scores.reduce((a, b) => a + b, 0) / scores.length;
        cl += mean;
        nc++;
        per[diff] = { careless: (per[diff]?.careless ?? 0) + mean, n: (per[diff]?.n ?? 0) + 1 };
      }
      list.forEach((k, i) => detail.push(`${c.id} ${k.label}: ${Math.round(scores[i]! * 100)}`));
    }
    const n = def.cases.length;
    rows.push({ test: id, points: `${n} cases × ${Math.round(1000 / n) / 10} points`, doNothing: dn / n, careless: nc ? cl / nc : 1, carelessCases: nc, careful: cf / n, perDifficulty: per, carelessDetail: detail });
  }
  return rows;
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const pct = (x: number) => `${Math.round(x * 1000) / 10}`;
  for (const r of spread()) {
    console.log(`${r.test.padEnd(24)} ${r.points.padEnd(22)} do-nothing ${pct(r.doNothing).padStart(5)}  careless ${pct(r.careless).padStart(5)} (${r.carelessCases} cases)  careful ${pct(r.careful).padStart(5)}  | careless by difficulty: ${Object.entries(r.perDifficulty).map(([d, v]) => `${d} ${pct(v.careless / v.n)}`).join(', ')}`);
    if (process.argv.includes('--detail')) for (const d of r.carelessDetail) console.log(`    ${d}`);
  }
}
