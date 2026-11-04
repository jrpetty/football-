/**
 * The operations & workflow pack (Business Ladder): What Do We Reorder?, Build the Rota, Plan the Van Routes,
 * Reply Within Policy and Numbers for the Board.
 *  - the generators reproduce every committed test file byte for byte;
 *  - every reference answer scores 100 through the full scoring pipeline (fair-play check included);
 *  - do-nothing answers (empty, "{}", the copied template, the prompt) and random filler score at most 5%;
 *  - scripted wrong answers score as designed (a broken rule 0, a dearer plan by the published formula, a wrong
 *    total halved, a wrong KPI costs exactly its weight, a reply-rule slip costs its point);
 *  - presentation never changes a score; hedges score 0; scoring is deterministic;
 *  - the exact solvers agree with brute force on small worlds; each new checker's source is in the test hash.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { computeTestHash, FAIR_PLAY_FINGERPRINT, loadTests, verifierSourceHash } from '../src/core/registry.ts';
import { contentHash } from '../src/core/hash.ts';
import { explainerFor } from '../src/core/explainers.ts';
import { partialCredit, scoreVerifier } from '../src/scoring/verifier.ts';
import { createRng } from '../src/core/rng.ts';
import type { PromptTest, ScorerSpec, TestDefinition } from '../src/core/types.ts';
import { BUILDS, WORK_DIR } from '../verification/realworld-ops/build.mts';
import { testJson } from '../verification/realworld-ops/common.mts';
import { spread, markOne, asReply } from '../verification/realworld-ops/spread.mts';
import { crosscheckRota } from '../verification/realworld-ops/rota.mts';
import { crosscheckRoutes } from '../verification/realworld-ops/routes.mts';
import { bruteForceReorder, solveReorder } from '../verification/realworld-ops/reorder.mts';
import { amountsIn } from '../src/scoring/verifiers/reply.ts';
import { readMinutes } from '../src/scoring/verifiers/routes.ts';
import { readShift } from '../src/scoring/verifiers/rota.ts';
import type { ReorderWorld } from '../src/scoring/verifiers/reorder.ts';
import type { RotaWorld } from '../src/scoring/verifiers/rota.ts';
import type { RoutesWorld } from '../src/scoring/verifiers/routes.ts';

const IDS = ['work.stock-reorder', 'work.shift-rota', 'work.delivery-routes', 'work.support-replies', 'work.board-report'];
const all = loadTests();
const pack = IDS.map((id) => {
  const t = all.find((x) => x.definition.id === id);
  assert.ok(t, `${id} is in the library`);
  return t!.definition as PromptTest;
});
const byId = (id: string) => pack.find((d) => d.id === id)!;
const isVerifier = (d: PromptTest) => d.scorer.type === 'verifier';
const verifierOf = (d: PromptTest) => (d.scorer as { verifier: string }).verifier;
const ref = (d: PromptTest, i = 0) => (isVerifier(d) ? (d.cases[i]!.expected as { reference: Record<string, unknown> }).reference : (d.cases[i]!.expected as Record<string, unknown>));
const world = <W>(d: PromptTest, i = 0) => (d.cases[i]!.expected as { world: W }).world;
const full = (d: PromptTest, i: number, response: string) => markOne(d.scorer as ScorerSpec, d.cases[i]!.expected, d.cases[i]!.prompt!, response);
const mark = (d: PromptTest, i: number, response: string) => scoreVerifier(verifierOf(d), d.cases[i]!.expected, response, d.cases[i]!.prompt);
const clone = <T>(x: T): T => JSON.parse(JSON.stringify(x)) as T;

test('the generators reproduce every operations test file byte for byte', () => {
  for (const [file, build] of Object.entries(BUILDS)) assert.equal(testJson(build()), readFileSync(join(WORK_DIR, file), 'utf8'), file);
});

test('each test: 8–12 cases, at least two easy, medium and hard, category work, an explainer, an estimate', () => {
  for (const d of pack) {
    assert.ok(d.cases.length >= 8 && d.cases.length <= 12, d.id);
    const diff = d.cases.map((c) => /^\[(\w+)\]/.exec(c.notes ?? '')?.[1]);
    for (const level of ['easy', 'medium', 'hard']) assert.ok(diff.filter((x) => x === level).length >= 3, `${d.id}: ${level}`);
    assert.equal(d.category, 'work');
    assert.ok(explainerFor(d.id), `${d.id} explainer`);
    assert.ok((d.estimate?.inputTokens ?? 0) > 500 && (d.estimate?.outputTokens ?? 0) > 1000, `${d.id} estimate`);
    for (const c of d.cases) assert.ok(c.prompt!.length / 3.6 < 4200, `${d.id}/${c.id}: prompt about ${Math.round(c.prompt!.length / 3.6)} tokens`);
    for (const c of d.cases) assert.match(c.prompt!, /two different answers|two numbers/, `${d.id}/${c.id}: anti-hedging sentence`);
  }
});

test('every reference answer scores 100 through the full pipeline (fair-play check included)', async () => {
  for (const d of pack) for (let i = 0; i < d.cases.length; i++) assert.equal(await full(d, i, asReply(ref(d, i))), 1, `${d.id}/${d.cases[i]!.id}`);
});

test('do-nothing answers score 0: empty, {}, the copied answer template, the prompt itself', async () => {
  for (const d of pack) {
    for (let i = 0; i < d.cases.length; i++) {
      const c = d.cases[i]!;
      const template = /```json\n([\s\S]*?)\n```/.exec(c.prompt!)![1]!;
      for (const r of ['', '{}', '```json\n{}\n```', '```json\n' + template + '\n```', c.prompt!]) assert.equal(await full(d, i, r), 0, `${d.id}/${c.id}: ${r.slice(0, 30)}`);
    }
  }
});

test('random filler scores at most 5% on every test', async () => {
  const words = 'the order stock rota van route refund policy total revenue shift driver break customer price case week please thanks'.split(' ');
  for (const d of pack) {
    let sum = 0;
    let n = 0;
    for (let i = 0; i < d.cases.length; i++) {
      for (let k = 0; k < 4; k++) {
        const rng = createRng(700 + k * 13 + i);
        const filler = Array.from({ length: 30 + rng.int(0, 100) }, () => rng.pick(words)).join(' ');
        sum += await full(d, i, k % 2 ? filler : `${filler}\nFINAL ANSWER: ${rng.int(0, 999)}`);
        n++;
      }
    }
    assert.ok(sum / n <= 0.05, `${d.id}: ${sum / n}`);
  }
});

test('presentation never changes the score', async () => {
  for (const d of pack) {
    const r = ref(d, d.cases.length - 1);
    const i = d.cases.length - 1;
    const variants = [
      JSON.stringify(r), // no code fence
      'My working… quite a lot of it.\n' + asReply(r) + '\nHope that helps!', // text around the JSON
      asReply(Object.fromEntries(Object.entries(r).map(([k, v]) => [k.toUpperCase(), v]))), // key case
      // A trailing comma: the plan/answer checkers tolerate it; the shared json scorer reads strict JSON.
      ...(isVerifier(d) ? ['```json\n' + JSON.stringify(r, null, 2).replace(/\n}$/, ',\n}') + '\n```'] : []),
    ];
    for (const v of variants) assert.equal(await full(d, i, v), 1, `${d.id}: ${v.slice(0, 50)}`);
  }
  // Totals as "£1,234.56" strings, minutes as "14 h 5 min", KPIs as "12.3%" and "£1,234.56".
  const stock = byId('work.stock-reorder');
  const sr = ref(stock, 0) as { order: Record<string, number>; total: number };
  assert.equal(mark(stock, 0, asReply({ ...sr, total: `£${sr.total.toLocaleString('en-GB', { minimumFractionDigits: 2 })}` })).score, 1);
  const routes = byId('work.delivery-routes');
  const rr = ref(routes, 0) as { routes: Record<string, string[]>; total_minutes: number };
  const hm = `${Math.floor(rr.total_minutes / 60)} h ${rr.total_minutes % 60} min`;
  assert.equal(mark(routes, 0, asReply({ ...rr, total_minutes: hm })).score, 1);
  assert.equal(readMinutes('14 h 5 min'), 845);
  assert.equal(readMinutes('845 minutes'), 845);
  assert.equal(readMinutes('14 or 15 h'), null);
  const board = byId('work.board-report');
  const br = ref(board, 0) as Record<string, number>;
  const styled = { ...br, net_revenue: `£${br.net_revenue!.toLocaleString('en-GB', { minimumFractionDigits: 2 })}`, gross_margin_pct: `${br.gross_margin_pct}%` };
  assert.equal(await full(board, 0, asReply(styled)), 1);
});

test('hedging scores 0 on the checked tests: two different answers, or an alternative key', () => {
  for (const d of pack.filter(isVerifier)) {
    const r = ref(d);
    const other = clone(r);
    if ('total' in other) other.total = 1;
    else if ('total_minutes' in other) other.total_minutes = 1;
    else other.decision = 'REFUSE';
    assert.equal(mark(d, 0, asReply(r) + '\n\nOr, if you prefer:\n' + asReply(other)).score, 0, `${d.id} two answers`);
    assert.equal(mark(d, 0, asReply({ ...r, alternative: other })).score, 0, `${d.id} alternative key`);
  }
});

test('What Do We Reorder?: short, over the shelf, part cases, MOQ, dearer plans and the quoted total', () => {
  const d = byId('work.stock-reorder');
  const i = d.cases.findIndex((c) => /\[medium\]/.test(c.notes!));
  const w = world<ReorderWorld>(d, i);
  const r = ref(d, i) as { order: Record<string, number>; total: number };
  const opt = (d.cases[i]!.expected as { optimum: number }).optimum;
  const ordered = Object.keys(r.order);
  const sku = w.skus.find((s) => s.id === ordered[0])!;
  // One case fewer of an item that had to be ordered: it runs below safety stock (or under its MOQ) → 0.
  const fewer = { ...r, order: { ...r.order, [sku.id]: r.order[sku.id]! - sku.caseSize } };
  assert.equal(mark(d, i, asReply(fewer)).score, 0);
  // Not a whole number of cases → 0.
  assert.equal(mark(d, i, asReply({ ...r, order: { ...r.order, [sku.id]: r.order[sku.id]! + 1 } })).score, 0);
  // Every item filled to the shelf: over the cash limit → 0.
  const fill = Object.fromEntries(w.skus.map((s) => [s.id, Math.floor((s.max - s.start) / s.caseSize) * s.caseSize]));
  assert.equal(mark(d, i, asReply({ order: fill, total: 0 })).score, 0);
  // A workable but dearer order (one extra case of the cheapest item with room): the published formula.
  const extra = w.skus.filter((s) => r.order[s.id] && s.max - s.start > (r.order[s.id] ?? 0) + 2 * s.caseSize).sort((a, b) => a.casePrice - b.casePrice)[0]!;
  const dearer = { ...r.order, [extra.id]: r.order[extra.id]! + extra.caseSize };
  const priced = mark(d, i, asReply({ order: dearer, total: 0 }));
  if (priced.detail.verdict !== 'infeasible') {
    const value = priced.detail.value as number;
    assert.ok(value >= opt);
    assert.equal(mark(d, i, asReply({ order: dearer, total: value / 100 })).score, partialCredit('min', opt, value));
  }
  // Wrong or missing total halves the score.
  assert.equal(mark(d, i, asReply({ ...r, total: r.total + 1 })).score, 0.5);
  assert.equal(mark(d, i, asReply({ order: r.order })).score, 0.5);
  // Quantities as {"cases": n} and SKUs by name are read too.
  const asCases = Object.fromEntries(Object.entries(r.order).map(([id, q]) => [w.skus.find((s) => s.id === id)!.name, { cases: q / w.skus.find((s) => s.id === id)!.caseSize }]));
  assert.equal(mark(d, i, asReply({ order: asCases, total: r.total })).score, 1);
  // An unknown SKU → 0.
  assert.equal(mark(d, i, asReply({ ...r, order: { ...r.order, Z99: 12 } })).score, 0);
});

test('Build the Rota: rest, under-18s, cover, skills, fairness, either layout, and the quoted total', () => {
  const d = byId('work.shift-rota');
  const i = d.cases.findIndex((c) => c.id === 'r07');
  const w = world<RotaWorld>(d, i);
  const r = ref(d, i) as { rota: Record<string, string[]>; total: number };
  // Someone dropped from a shift: cover broken → 0.
  const short = clone(r);
  short.rota['Mon-E'] = short.rota['Mon-E']!.slice(1);
  assert.equal(mark(d, i, asReply(short)).score, 0);
  // Chloe (17) put on a night shift in place of someone → 0.
  const night = clone(r);
  night.rota['Mon-N'] = ['Chloe'];
  assert.equal(mark(d, i, asReply(night)).score, 0);
  // Person-keyed layout and "Monday Early" style shift names are read too.
  const byPerson: Record<string, string[]> = {};
  const DAYS = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];
  for (const [sid, ppl] of Object.entries(r.rota)) {
    const s = w.shifts.find((x) => x.id === sid)!;
    for (const p of ppl) (byPerson[p] ??= []).push(`${DAYS[s.day]} ${s.name}`);
  }
  assert.equal(mark(d, i, asReply({ rota: byPerson, total: r.total })).score, 1);
  assert.equal(readShift('Tues late shift', w.shifts), 'Tue-L');
  assert.equal(readShift('MON-E', w.shifts), 'Mon-E');
  assert.equal(readShift('Mon or Tue early', w.shifts), null);
  // Wrong total halves it.
  assert.equal(mark(d, i, asReply({ ...r, total: r.total - 84 })).score, 0.5);
  // A pub case: a close then the next morning's open breaks the 11-hour rest → 0.
  const p = byId('work.shift-rota');
  const j = p.cases.findIndex((c) => c.id === 'r01');
  const pr = clone(ref(p, j) as { rota: Record<string, string[]> });
  const closer = pr.rota['Mon-C']![0]!;
  if (!pr.rota['Tue-O']!.includes(closer)) {
    pr.rota['Tue-O'] = [closer, ...pr.rota['Tue-O']!.slice(1)];
    const out = mark(p, j, asReply(pr));
    assert.equal(out.score, 0);
  }
});

test('Plan the Van Routes: windows, breaks, every drop once, either van naming, and the quoted total', () => {
  const d = byId('work.delivery-routes');
  const i = d.cases.findIndex((c) => /[1-9] breaks?\)/.test(c.notes!));
  const w = world<RoutesWorld>(d, i);
  const r = ref(d, i) as { routes: Record<string, string[]>; total_minutes: number };
  // Every BREAK removed: someone drives over 4 h 30 min → 0.
  const noBreak = { ...r, routes: Object.fromEntries(Object.entries(r.routes).map(([v, s]) => [v, s.filter((x) => x !== 'BREAK')])) };
  assert.equal(mark(d, i, asReply(noBreak)).score, 0);
  // A drop left out → 0; a drop twice → 0.
  const [v0, s0] = Object.entries(r.routes).find(([, s]) => s.length > 1)!;
  const drop = s0.find((x) => x !== 'BREAK')!;
  assert.equal(mark(d, i, asReply({ ...r, routes: { ...r.routes, [v0]: s0.filter((x) => x !== drop) } })).score, 0);
  const other = Object.keys(r.routes).find((v) => v !== v0)!;
  assert.equal(mark(d, i, asReply({ ...r, routes: { ...r.routes, [other]: [...r.routes[other]!, drop] } })).score, 0);
  // "Van 1" keys, "Depot" at both ends and drops with their town are read the same.
  const named = Object.fromEntries(Object.entries(r.routes).map(([v, s]) => [w.vans.find((x) => x.id === v)!.name, ['Depot', ...s.map((x) => (x === 'BREAK' ? 'Break (45 min)' : `${x} ${w.drops.find((y) => y.id === x)!.name}`)), 'Depot']]));
  assert.equal(mark(d, i, asReply({ routes: named, total_minutes: r.total_minutes })).score, 1);
  // The total quoted as driving time only → halved.
  assert.equal(mark(d, i, asReply({ ...r, total_minutes: r.total_minutes - 100 })).score, 0.5);
});

test('Reply Within Policy: each slip costs its points, the header is exact, the never-rules need a usable reply', () => {
  const d = byId('work.support-replies');
  const i = d.cases.findIndex((c) => c.id === 's08');
  const r = ref(d, i) as { decision: string; refund: number; next_step: string; reply: string };
  const total = (mark(d, i, asReply(r)).detail.items as Array<{ weight?: number }>).reduce((a, x) => a + (x.weight ?? 1), 0);
  const frac = (lost: number) => Math.round(((total - lost) / total) * 10000) / 10000;
  // A banned word costs 1 point.
  assert.equal(mark(d, i, asReply({ ...r, reply: r.reply.replace('Kind regards', 'We have added a voucher for your trouble. Kind regards') })).score, frac(1));
  // Naming the carrier costs 1 point.
  assert.equal(mark(d, i, asReply({ ...r, reply: r.reply.replace('Kind regards', 'Evri should have been more careful. Kind regards') })).score, frac(1));
  // Another amount in the text (the list price) costs the money rule.
  assert.equal(mark(d, i, asReply({ ...r, reply: r.reply.replace('Kind regards', 'The Dutch oven was £89.00. Kind regards') })).score, frac(1));
  // A wrong refund: 3 points, the money rule, and every never-rule (no longer a usable reply).
  const never = (mark(d, i, asReply(r)).detail.items as Array<{ label: string }>).filter((x) => /^no |does not|at most/.test(x.label)).length;
  assert.equal(never, 3);
  const wrong = { ...r, refund: 101, reply: r.reply.replace('£85.85', '£101.00') };
  assert.equal(mark(d, i, asReply(wrong)).score, frac(3 + 1 + never));
  // The right refund under the wrong decision earns no refund points.
  assert.equal(mark(d, i, asReply({ ...r, decision: 'REPLACE' })).score, frac(3 + 3 + never));
  // Codes are read in any case/spacing; two codes in one value are wrong.
  assert.equal(mark(d, i, asReply({ ...r, decision: 'Refund', next_step: 'collection' })).score, 1);
  assert.ok(mark(d, i, asReply({ ...r, decision: 'REFUND or REPLACE' })).score < 1);
  // Other customer's details (s06, s10) cost their point.
  for (const id of ['s06', 's10']) {
    const k = d.cases.findIndex((c) => c.id === id);
    const rk = ref(d, k) as { reply: string };
    const w = world<{ others: string[] }>(d, k);
    const t = (mark(d, k, asReply(rk)).detail.items as Array<{ weight?: number }>).reduce((a, x) => a + (x.weight ?? 1), 0);
    assert.equal(mark(d, k, asReply({ ...rk, reply: rk.reply.replace('Kind regards', `I also checked ${w.others[0]}'s order. Kind regards`) })).score, Math.round(((t - 1) / t) * 10000) / 10000, id);
  }
  // The money reader: £ amounts, GBP, pounds; none in an order number.
  assert.deepEqual(amountsIn('a refund of £85.85, not £1,234 or 12.50 GBP, order KC-204518'), [8585, 123400, 1250]);
});

test('Numbers for the Board: one wrong KPI costs exactly its weight; a hedge is wrong', async () => {
  const d = byId('work.board-report');
  const weights = (d.scorer as { weights: Record<string, number> }).weights;
  const r = ref(d, 4) as Record<string, number>;
  const total = Object.keys(r).reduce((a, k) => a + (weights[k] ?? 1), 0);
  for (const k of ['net_revenue', 'orders', 'average_fte']) {
    const got = await full(d, 4, asReply({ ...r, [k]: r[k]! + 1 }));
    assert.equal(got, Math.round(((total - (weights[k] ?? 1)) / total) * 10000) / 10000, k);
  }
  assert.ok((await full(d, 4, asReply({ ...r, net_revenue: `${r.net_revenue} or ${r.net_revenue! + 1}` }))) < 1);
  // Off by a penny is wrong.
  assert.ok((await full(d, 4, asReply({ ...r, net_revenue: Math.round(r.net_revenue! * 100 + 1) / 100 }))) < 1);
});

test('the expected spread: careful 100, do-nothing 0, careless well below, constant replies low', async () => {
  const rows = await spread();
  for (const r of rows) {
    assert.equal(r.careful, 1, r.test);
    assert.ok(r.doNothing <= 0.01, `${r.test}: do-nothing ${r.doNothing}`);
    assert.ok(r.careless < 0.8, `${r.test}: careless ${r.careless}`);
    assert.ok(r.carelessCases >= 8, `${r.test}: careless cases ${r.carelessCases}`);
  }
  const replies = rows.find((r) => r.test === 'work.support-replies')!;
  assert.ok((replies.constant ?? 1) <= 0.3, `constant reply ${replies.constant}`);
  // Plans: a rule ignored that changes the plan is usually fatal; the plan tests' careless mean stays under 30.
  for (const id of ['work.stock-reorder', 'work.shift-rota', 'work.delivery-routes']) assert.ok(rows.find((r) => r.test === id)!.careless < 0.3, id);
});

test('scoring is deterministic', async () => {
  for (const d of pack) {
    const i = d.cases.length - 1;
    const a = await full(d, i, asReply(ref(d, i)));
    const b = await full(d, i, asReply(ref(d, i)));
    assert.equal(a, b);
  }
});

test('the exact solvers agree with brute force on small worlds', () => {
  const rota = crosscheckRota(20, 99);
  assert.ok(rota.feasible >= 5, `rota feasible ${rota.feasible}`);
  assert.deepEqual(rota.mismatches, []);
  const routes = crosscheckRoutes(40, 5);
  assert.ok(routes.feasible >= 8, `routes feasible ${routes.feasible}`);
  assert.deepEqual(routes.mismatches, []);
  // Stock: the DP and a plain enumeration agree on every supplier small enough to enumerate.
  const d = byId('work.stock-reorder');
  let checked = 0;
  for (let i = 0; i < d.cases.length; i++) {
    const w = world<ReorderWorld>(d, i);
    const bf = bruteForceReorder(w);
    if (bf === null) continue;
    checked++;
    assert.equal(solveReorder(w).optimum, bf, d.cases[i]!.id);
    assert.equal((d.cases[i]!.expected as { optimum: number }).optimum, bf, d.cases[i]!.id);
  }
  assert.ok(checked >= 5, `stock cases brute-forced: ${checked}`);
});

test('each new checker’s source is part of the hash of the tests that use it', () => {
  for (const name of ['reorder', 'rota', 'routes', 'reply']) assert.match(verifierSourceHash(name), /^[0-9a-f]{12}$/);
  for (const d of pack.filter(isVerifier)) {
    const plain = contentHash({ def: d, fairPlay: FAIR_PLAY_FINGERPRINT });
    assert.notEqual(computeTestHash(d as TestDefinition), plain, d.id);
  }
});
