/**
 * The data & support Business ladders (verification/ladders-data-support/):
 *   work.crm-ladder       Clean the Customer List: Ladder
 *   work.contract-ladder  What Changed in the Contract?: Ladder
 *   work.sql-ladder       Ask the Database: Ladder
 *   work.support-ladder   Customer Support Queue: Ladder
 *
 * Checks: ten frozen levels L01..L10 each; the generators reproduce every file byte for byte; every level is
 * strictly bigger or deeper than the one below along its documented parameter; L01 stays small and L10 under ~40k
 * input tokens; every key scores 100 and format never costs marks; specific wrong answers lose what they should
 * (a wrong merge, an invented change, a hedge, a hard-coded SQL answer on the hidden copies); filler, do-nothing and
 * copied-prompt answers score (close to) nothing; the scripted spread per level; scoring is deterministic.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { getTest, renderCase } from '../src/core/registry.ts';
import { scoreResponse } from '../src/scoring/index.ts';
import { referenceResults } from '../src/scoring/sql.ts';
import { createRng } from '../src/core/rng.ts';
import type { PromptTest, SqlExpected, SqlScorerSpec } from '../src/core/types.ts';
import { buildLadder, DEPTH, LADDER_FILE, LADDER_QUESTIONS } from '../verification/ladders-data-support/sql-ladder.mts';

const IDS = ['work.crm-ladder', 'work.contract-ladder', 'work.sql-ladder', 'work.support-ladder'] as const;
const JSON_IDS = ['work.crm-ladder', 'work.contract-ladder', 'work.support-ladder'] as const;
const LEVELS = ['L01', 'L02', 'L03', 'L04', 'L05', 'L06', 'L07', 'L08', 'L09', 'L10'];
const DEFS = new Map<string, PromptTest>(IDS.map((id) => [id, getTest(id)!.definition as PromptTest]));
const def = (id: string) => DEFS.get(id)!;
const HERE = new URL('../verification/ladders-data-support/', import.meta.url);
const scripted = {
  ...(JSON.parse(readFileSync(new URL('scripted.json', HERE), 'utf8')) as Record<string, Record<string, Record<string, unknown>>>),
  ...(JSON.parse(readFileSync(new URL('contract-scripted.json', HERE), 'utf8')) as Record<string, Record<string, Record<string, unknown>>>),
};
const growth = JSON.parse(readFileSync(new URL('growth.json', HERE), 'utf8')) as Record<string, Array<Record<string, number | string>>>;
const tok = (s: string) => Math.ceil(s.length / 3.6) + 20;
const inputTokens = (id: string, caseId: string) => {
  const d = def(id);
  const c = d.cases.find((x) => x.id === caseId)!;
  return tok(renderCase(d, c).turns.join('\n'));
};

async function score(id: string, caseId: string, response: string): Promise<number> {
  const d = def(id);
  const c = d.cases.find((x) => x.id === caseId)!;
  const out = await scoreResponse({
    scorer: c.scorer ?? d.scorer,
    expected: c.expected,
    response,
    stopReason: 'end',
    taskText: renderCase(d, c).turns.join('\n'),
    judges: { ids: [], ask: async () => [] },
    saveArtifact: (name) => ({ name, kind: 'text', file: name, bytes: 0 }),
    signal: new AbortController().signal,
  });
  return out.score ?? 0;
}
const asJson = (v: unknown) => '```json\n' + JSON.stringify(v, null, 2) + '\n```';
const fenced = (sql: string) => 'Here is the query.\n\n```sql\n' + sql + '\n```';
const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v));
const exp = (id: string, caseId: string) => clone(def(id).cases.find((c) => c.id === caseId)!.expected) as Record<string, any>;
const increasing = (xs: number[], what: string, strict = true) => {
  for (let i = 1; i < xs.length; i++) assert.ok(strict ? xs[i]! > xs[i - 1]! : xs[i]! >= xs[i - 1]!, `${what}: L${String(i + 1).padStart(2, '0')} (${xs[i]}) vs L${String(i).padStart(2, '0')} (${xs[i - 1]})`);
};

test('four ladders in the work category, each exactly ten levels L01..L10, no answer-time limit', () => {
  for (const id of IDS) {
    const d = def(id);
    assert.equal(d.category, 'work', id);
    assert.deepEqual(d.cases.map((c) => c.id), LEVELS, id);
    assert.equal(d.answerWithinSec, undefined, id);
    assert.equal(d.maxOutputTokens, 32000, id);
    d.cases.forEach((c, i) => assert.match(c.notes ?? '', new RegExp(`^\\[level ${i + 1}\\]`), `${id}/${c.id}`));
    assert.ok(d.estimate && d.estimate.inputTokens > 1000 && d.estimate.outputTokens >= 5000, id);
  }
  assert.equal(def('work.sql-ladder').scorer.type, 'sql');
  assert.deepEqual((def('work.sql-ladder').scorer as SqlScorerSpec).databases.map((x) => x.id), ['live', 'A', 'B', 'C']);
});

test('the generators reproduce every ladder file byte for byte', () => {
  const js = execFileSync(process.execPath, [fileURLToPath(new URL('build.mjs', HERE)), '--check'], { encoding: 'utf8' });
  assert.doesNotMatch(js, /DIFFERS/);
  const py = execFileSync('python3', [fileURLToPath(new URL('contract_ladder.py', HERE)), '--check'], { encoding: 'utf8' });
  assert.doesNotMatch(py, /DIFFERS/);
  assert.equal(buildLadder().text, readFileSync(LADDER_FILE, 'utf8'));
});

test('input size: level 1 is small, level 10 stays under ~40k tokens', () => {
  for (const id of IDS) {
    assert.ok(inputTokens(id, 'L01') <= 3500, `${id}: L01 is ${inputTokens(id, 'L01')} tokens`);
    assert.ok(inputTokens(id, 'L10') <= 40000, `${id}: L10 is ${inputTokens(id, 'L10')} tokens`);
  }
});

test('growth: every level is strictly bigger or deeper than the one below', () => {
  // Customer list: more records every level; from level 5 more pairs that only a chain joins, more planted look-alikes, chains no shorter.
  const crm = growth['work.crm-ladder']!;
  increasing(def('work.crm-ladder').cases.map((c) => Number(/: (\d+) records\./.exec(c.prompt!)![1])), 'crm records');
  increasing(crm.map((g) => Number(g.chainOnlyPairs)).slice(4), 'crm chain-only pairs');
  increasing(crm.map((g) => Number(g.distractors)), 'crm distractors', false);
  increasing(crm.map((g) => Number(g.chainDepth)), 'crm chain depth', false);
  assert.ok(Number(crm[9]!.chainDepth) >= 6 && Number(crm[4]!.chainDepth) >= 2);
  // Support queue: more tickets every level, rule families never fewer, more trap tickets.
  const sup = growth['work.support-ladder']!;
  increasing(def('work.support-ladder').cases.map((c) => Object.keys((c.expected as { tickets: object }).tickets).length), 'support tickets');
  increasing(sup.map((g) => Number(g.families)), 'support rule families', false);
  increasing(sup.map((g) => Number(g.trapTickets)).slice(3), 'support trap tickets');
  assert.equal(sup[0]!.families, 2);
  // Contract: longer drafts, more changes, more renumbering, more re-pointed references.
  const k = def('work.contract-ladder').cases;
  increasing(k.map((c) => c.prompt!.length), 'contract length');
  increasing(k.map((c) => Object.keys((c.expected as { changes: object }).changes).length), 'contract changes');
  const types = (c: (typeof k)[number]) => Object.values((c.expected as { changes: Record<string, { type: string }> }).changes).map((x) => x.type);
  increasing(k.map((c) => new Set(types(c)).size), 'contract change kinds', false);
  increasing(k.map((c) => types(c).filter((t) => t === 'cross_reference').length).slice(3), 'contract re-pointed references');
  // SQL: more tables (never fewer), more rules and memo items every level, longer questions from level 5.
  const s = def('work.sql-ladder').cases;
  increasing(DEPTH.map((d) => d.rules), 'sql rules');
  increasing(DEPTH.map((d) => d.tables), 'sql tables', false);
  const tables = ['dim_product', 'dim_customer', 'dim_product_category_history', 'dim_price_history', 'fact_orders', 'fact_order_lines', 'promotions', 'promotion_products', 'events_raw', 'subscription_status_daily', 'wallet_ledger'];
  s.forEach((c, i) => {
    const used = tables.filter((t) => new RegExp(`\\b${t}\\b`).test((c.expected as SqlExpected).reference)).length;
    assert.equal(used, DEPTH[i]!.tables, `${c.id}: the reference reads ${used} tables`);
  });
  increasing(s.map((c) => c.prompt!.length).slice(4), 'sql question length');
});

test('every reference answer scores 100, fenced or not, with words around it', async () => {
  for (const id of JSON_IDS)
    for (const c of def(id).cases) {
      assert.equal(await score(id, c.id, asJson(c.expected)), 1, `${id}/${c.id}`);
      assert.equal(await score(id, c.id, `My answer:\n${JSON.stringify(c.expected)}\nDone.`), 1, `${id}/${c.id} unfenced`);
    }
  for (const c of def('work.sql-ladder').cases) assert.equal(await score('work.sql-ladder', c.id, fenced((c.expected as SqlExpected).reference)), 1, c.id);
});

test('format never costs marks: id case and spacing, a "Clause" prefix, ISO date-time separators', async () => {
  const crm = exp('work.crm-ladder', 'L03');
  crm.clusters = (crm.clusters as string[][]).map((g) => g.map((x) => x.toLowerCase().replace('-', ' ')));
  assert.equal(await score('work.crm-ladder', 'L03', asJson(crm)), 1);
  const k = exp('work.contract-ladder', 'L04');
  k.changes = Object.fromEntries(Object.entries(k.changes as Record<string, unknown>).map(([n, v]) => [`Clause ${n}`, v]));
  assert.equal(await score('work.contract-ladder', 'L04', asJson(k)), 1);
  const sup = exp('work.support-ladder', 'L05');
  for (const t of Object.values(sup.tickets as Record<string, { sla_due: string; priority: string }>)) {
    t.sla_due = t.sla_due.replace(' ', 'T') + ':00';
    t.priority = t.priority.toLowerCase();
  }
  assert.equal(await score('work.support-ladder', 'L05', asJson(sup)), 1);
});

test('specific wrong answers lose what they should', async () => {
  // Customer list: merging everything or nothing scores about 0; one wrong merge of two groups costs.
  for (const lv of ['L01', 'L05', 'L10']) {
    const s = scripted['work.crm-ladder']![lv]!;
    assert.ok((await score('work.crm-ladder', lv, asJson(s.merge_everything))) <= 0.05, lv);
    assert.equal(await score('work.crm-ladder', lv, asJson(s.merge_nothing)), 0, lv);
  }
  const crm = exp('work.crm-ladder', 'L06');
  const [a, b] = crm.clusters as string[][];
  crm.clusters = [[...a!, ...b!], ...(crm.clusters as string[][]).slice(2)];
  const merged = await score('work.crm-ladder', 'L06', asJson(crm));
  assert.ok(merged < 1 && merged > 0.9, `one wrong merge: ${merged}`);
  // Contract: an invented change costs as much as a missed trap field set; a missed change costs.
  const k = exp('work.contract-ladder', 'L07');
  const invented = { ...k, changes: { ...k.changes, '1.1': { type: 'amount', old: 1, new: 2 } } };
  const missing = { changes: Object.fromEntries(Object.entries(k.changes).slice(1)) };
  assert.ok((await score('work.contract-ladder', 'L07', asJson(invented))) < 1);
  assert.ok((await score('work.contract-ladder', 'L07', asJson(missing))) < 1);
  // Support: a hedged decision is wrong, the rest of the ticket still counts.
  const sup = exp('work.support-ladder', 'L02');
  const first = Object.keys(sup.tickets)[0]!;
  sup.tickets[first].decision = `${sup.tickets[first].decision} or decline`;
  const hedged = await score('work.support-ladder', 'L02', asJson(sup));
  assert.ok(hedged < 1 && hedged > 0.7, `hedge: ${hedged}`);
});

test('SQL: a hard-coded live answer earns (almost) nothing beyond the live database, on every level', async () => {
  const d = def('work.sql-ladder');
  for (const c of d.cases) {
    const [live] = await referenceResults(d.scorer as SqlScorerSpec, (c.expected as SqlExpected).reference);
    const lit = (v: unknown) => (v === null ? 'NULL' : typeof v === 'number' ? String(v) : `'${String(v).replace(/'/g, "''")}'`);
    const sql = live!.rows.map((r) => `SELECT ${r.map(lit).join(', ')}`).join('\nUNION ALL\n');
    const s = await score('work.sql-ladder', c.id, fenced(sql));
    assert.ok(s <= 0.325 + 1e-9, `${c.id}: hard-coded answer scored ${s}`);
  }
});

test('SQL: every careless query (one realistic slip) loses marks, and SELECT * scores 0', async () => {
  for (const [i, q] of LADDER_QUESTIONS.entries()) {
    assert.ok(q.careless.length >= 2, `${q.id}: at least two slips`);
    for (const slip of q.careless) {
      const s = await score('work.sql-ladder', LEVELS[i]!, fenced(slip.sql));
      assert.ok(s < 1, `${q.id} "${slip.why}" scored ${s}`);
    }
    assert.equal(await score('work.sql-ladder', LEVELS[i]!, fenced('SELECT * FROM dim_customer')), 0);
  }
});

test('do-nothing, copied-prompt and random-filler answers score at most 5% on every ladder', async () => {
  const words = 'the model result answer benchmark quickly random value system data story river light lamp order help please thanks store product price name city year'.split(' ');
  for (const id of IDS) {
    let sum = 0;
    let n = 0;
    for (const c of def(id).cases) {
      const filler = [0, 1, 2, 3].map((j) => {
        const rng = createRng(1000 + j * 31 + n);
        const f = Array.from({ length: 20 + rng.int(0, 120) }, () => rng.pick(words)).join(' ') + '.';
        return j % 2 ? f : `${f}\nFINAL ANSWER: ${rng.int(0, 99)}`;
      });
      for (const r of [...filler, '```json\n{}\n```', '']) {
        sum += await score(id, c.id, r);
        n++;
      }
      if (id !== 'work.sql-ladder') assert.ok((await score(id, c.id, c.prompt!)) <= 0.05, `${id}/${c.id}: copying the prompt`);
    }
    assert.ok(sum / n <= 0.05, `${id}: filler averages ${((sum / n) * 100).toFixed(1)}%`);
  }
});

test('the spread per level: careless answers fail every rung, careful answers with one slip stay close to the pass mark', async () => {
  const at = async (id: string, kind: string) => Promise.all(LEVELS.map(async (lv) => score(id, lv, asJson(scripted[id]![lv]![kind]))));
  const crmCareless = await at('work.crm-ladder', 'careless');
  const crmSlip = await at('work.crm-ladder', 'careful_human_slips');
  const supCareless = await at('work.support-ladder', 'careless');
  const supSlip = await at('work.support-ladder', 'careful_human_slips');
  const kCareless = await at('work.contract-ladder', 'careless');
  const kSlip = await at('work.contract-ladder', 'slip');
  for (const [i, lv] of LEVELS.entries()) {
    assert.ok(crmCareless[i]! < 0.6, `crm ${lv} careless ${crmCareless[i]}`);
    assert.ok(supCareless[i]! < 0.9, `support ${lv} careless ${supCareless[i]}`);
    assert.ok(kCareless[i]! < 0.65, `contract ${lv} careless ${kCareless[i]}`);
    assert.ok(crmSlip[i]! >= 0.85 && crmSlip[i]! <= 1, `crm ${lv} slip ${crmSlip[i]}`);
    assert.ok(supSlip[i]! >= 0.9, `support ${lv} slip ${supSlip[i]}`);
    assert.ok(kSlip[i]! >= 0.9, `contract ${lv} slip ${kSlip[i]}`);
  }
  // The careless readers get worse as the levels grow.
  assert.ok(kCareless[9]! < kCareless[0]! && crmCareless[9]! < crmCareless[1]! && supCareless[9]! < supCareless[0]!);
  // From level 5 on, the strict-name reader (no nicknames, Mac/Mc or typos) fails every rung of the customer list.
  const strict = await at('work.crm-ladder', 'flawed_strict_names');
  for (let i = 4; i < 10; i++) assert.ok(strict[i]! < 0.9, `crm L${i + 1} strict names ${strict[i]}`);
});

test('scoring is deterministic', async () => {
  for (const id of JSON_IDS) {
    const ans = asJson(scripted[id]!.L05!.careless);
    assert.equal(await score(id, 'L05', ans), await score(id, 'L05', ans));
  }
  const slip = fenced(LADDER_QUESTIONS[7]!.careless[0]!.sql);
  assert.equal(await score('work.sql-ladder', 'L08', slip), await score('work.sql-ladder', 'L08', slip));
});
