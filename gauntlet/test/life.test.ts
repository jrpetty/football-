/**
 * The Everyday Life pack (tests/life/*.json) and the generic verifier scorer:
 *  - the generators reproduce every committed test file byte for byte;
 *  - every reference answer scores 100 through the real scoring pipeline (fair-play check included);
 *  - scripted wrong answers score as intended (broken rule 0, dearer plan by the formula, wrong total halved,
 *    hedges and two answers 0, extra transfers cost, wrong fields cost exactly their share);
 *  - presentation never changes a score (no fence, £ strings, key case, trailing text, names instead of IDs);
 *  - random filler, empty replies, "{}" and the copied answer template score 0;
 *  - scoring is deterministic, the checker agrees with the independent trip solver, and each verifier's source
 *    is part of the test hash.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { computeTestHash, loadTests, verifierSourceHash } from '../src/core/registry.ts';
import { contentHash } from '../src/core/hash.ts';
import { FAIR_PLAY_FINGERPRINT } from '../src/core/registry.ts';
import { scoreResponse } from '../src/scoring/index.ts';
import { answerCandidates, partialCredit, scoreVerifier, SUBOPTIMAL_MAX } from '../src/scoring/verifier.ts';
import { readMonth } from '../src/scoring/verifiers/fields.ts';
import { readPence } from '../src/scoring/verifiers/common.ts';
import { describeMarking } from '../src/core/test-depth.ts';
import { gradingSpecFor } from '../src/grading/spec.ts';
import { explainerFor } from '../src/core/explainers.ts';
import { remarkResult } from '../src/engine/remark.ts';
import { createRng } from '../src/core/rng.ts';
import type { ArtifactRef, CaseResult, PromptTest, TestDefinition } from '../src/core/types.ts';
import { BUILDS, LIFE_DIR } from '../verification/realworld-life/build.mts';
import { testJson } from '../verification/realworld-life/common.mts';
import { crosscheckTrip } from '../verification/realworld-life/crosscheck-trip.mts';
import { spread } from '../verification/realworld-life/spread.mts';

const IDS = ['life.trip-planner', 'life.bill-split', 'life.tariff-chooser', 'life.recipe-shopping', 'life.loan-overpayment', 'life.packing-limits'];
const all = loadTests();
const life = IDS.map((id) => {
  const t = all.find((x) => x.definition.id === id);
  assert.ok(t, `${id} is in the library`);
  return t!.definition as PromptTest;
});
const byId = (id: string) => life.find((d) => d.id === id)!;
const verifierOf = (d: PromptTest) => (d.scorer as { verifier: string }).verifier;
const reply = (a: unknown) => 'Working shown above.\n\n```json\n' + JSON.stringify(a, null, 2) + '\n```';
const ref = (d: PromptTest, i = 0) => (d.cases[i]!.expected as { reference: Record<string, unknown> }).reference;
const noJudges = { ids: [], ask: async () => [] };
const noArtifacts = (name: string): ArtifactRef => ({ name, kind: 'text', file: name, bytes: 0 });

async function full(d: PromptTest, i: number, response: string): Promise<number> {
  const c = d.cases[i]!;
  const out = await scoreResponse({ scorer: d.scorer, expected: c.expected, response, stopReason: 'end', taskText: c.prompt!, judges: noJudges, saveArtifact: noArtifacts, signal: new AbortController().signal });
  return out.score ?? 0;
}
const mark = (d: PromptTest, i: number, response: string) => scoreVerifier(verifierOf(d), d.cases[i]!.expected, response, d.cases[i]!.prompt);

test('the generators reproduce every Everyday Life test file byte for byte', () => {
  for (const [file, build] of Object.entries(BUILDS)) assert.equal(testJson(build()), readFileSync(join(LIFE_DIR, file), 'utf8'), file);
});

test('each test has 8–12 cases, a third easy to a third hard, and an explainer', () => {
  for (const d of life) {
    assert.ok(d.cases.length >= 8 && d.cases.length <= 12, d.id);
    const diff = d.cases.map((c) => /^\[(\w+)\]/.exec(c.notes ?? '')?.[1]);
    for (const level of ['easy', 'medium', 'hard']) assert.ok(diff.filter((x) => x === level).length >= 2, `${d.id}: ${level}`);
    assert.ok(explainerFor(d.id), `${d.id} explainer`);
    assert.equal(d.category, 'life');
  }
});

test('every reference answer scores 100 through the full pipeline (fair-play check included)', async () => {
  for (const d of life) for (let i = 0; i < d.cases.length; i++) assert.equal(await full(d, i, reply(ref(d, i))), 1, `${d.id}/${d.cases[i]!.id}`);
});

test('do-nothing answers score 0: empty, {}, the copied answer template, the prompt itself', () => {
  for (const d of life) {
    for (let i = 0; i < d.cases.length; i++) {
      const c = d.cases[i]!;
      const template = /```json\n([\s\S]*?)\n```/.exec(c.prompt!)![1]!;
      for (const r of ['', '{}', '```json\n{}\n```', '```json\n' + template + '\n```', c.prompt!]) assert.equal(mark(d, i, r).score, 0, `${d.id}/${c.id}: ${r.slice(0, 30)}`);
    }
  }
});

test('random filler scores at most 5% on every Everyday Life test', async () => {
  const words = 'the model result answer benchmark quickly random value system data story river light lamp order help please thanks store product price name city year'.split(' ');
  for (const d of life) {
    let sum = 0;
    let n = 0;
    for (let i = 0; i < d.cases.length; i++) {
      for (let k = 0; k < 4; k++) {
        const rng = createRng(500 + k * 17 + i);
        const filler = Array.from({ length: 30 + rng.int(0, 100) }, () => rng.pick(words)).join(' ');
        sum += await full(d, i, k % 2 ? filler : `${filler}\nFINAL ANSWER: ${rng.int(0, 999)}`);
        n++;
      }
    }
    assert.ok(sum / n <= 0.05, `${d.id}: ${sum / n}`);
  }
});

test('hedging scores 0: two different answers in one reply, or an "alternative" key', () => {
  for (const d of life) {
    const r = ref(d);
    const other = JSON.parse(JSON.stringify(r)) as Record<string, unknown>;
    other.total = 1; // a second, different answer
    if (!('total' in r)) (other as Record<string, unknown>).months = 1;
    const two = mark(d, 0, reply(r) + '\n\nOr, if you prefer:\n' + reply(other));
    assert.equal(two.score, 0, `${d.id} two answers`);
    assert.equal(two.detail.verdict, 'hedged');
    assert.equal(mark(d, 0, reply({ ...r, alternative: other })).score, 0, `${d.id} alternative key`);
  }
  // A value that says "or" is not read as either value.
  const loan = byId('life.loan-overpayment');
  const a = { ...ref(loan), clearedMonth: `${ref(loan).clearedMonth} or later` };
  assert.ok(mark(loan, 0, reply(a)).score < 1);
});

test('presentation never changes the score', () => {
  for (const d of life) {
    const r = ref(d);
    const variants = [
      JSON.stringify(r), // no code fence
      'My working... lots of it.\n' + reply(r) + '\nHope that helps! Let me know if you want a breakdown.', // trailing text
      reply(JSON.parse(JSON.stringify(r, (k, v) => (k === 'total' && typeof v === 'number' ? `£${v.toLocaleString('en-GB', { minimumFractionDigits: 2 })}` : v)))), // "£1,234.50"
      reply({ answer: r }), // one wrapper object
      reply(Object.fromEntries(Object.entries(r).map(([k, v]) => [k.toUpperCase(), v]))), // key case
      '```json\n' + JSON.stringify(r, null, 2).replace(/\n}$/, ',\n}') + '\n```', // a trailing comma
    ];
    for (const v of variants) assert.equal(mark(d, 0, v).score, 1, `${d.id}: ${v.slice(0, 60)}`);
  }
  assert.equal(readPence('£1,234.56'), 123456);
  assert.equal(readPence('-£3.20'), -320);
  assert.equal(readPence('£12 or £13'), null);
  for (const m of ['2039-08', 'Aug 2039', 'August 2039', '08/2039', '8/2039']) assert.equal(readMonth(m), '2039-08', m);
  assert.equal(readMonth('Aug or Sep 2039'), null);
});

test('plan verifiers: a broken rule scores 0, a dearer plan scores the published formula, a wrong total halves it', () => {
  // Partial-credit formula: optimal is the only 1; anything worse is under 0.5 and falls as the cost rises.
  assert.equal(partialCredit('min', 1000, 1000), 1);
  assert.ok(partialCredit('min', 1000, 1001) < SUBOPTIMAL_MAX);
  assert.ok(partialCredit('min', 1000, 1010) > partialCredit('min', 1000, 1500));
  assert.equal(partialCredit('min', 1000, 2000), 0.125);
  assert.equal(partialCredit('max', 1000, 500), 0.125);

  const trip = byId('life.trip-planner');
  const r = ref(trip, 0) as { journey: string[]; hotels: unknown[]; total: number };
  // Wrong total → halved; missing total → halved.
  assert.equal(mark(trip, 0, reply({ ...r, total: r.total + 1 })).score, 0.5);
  assert.equal(mark(trip, 0, reply({ journey: r.journey, hotels: r.hotels })).score, 0.5);
  // A step removed: the itinerary no longer works.
  assert.equal(mark(trip, 0, reply({ ...r, journey: r.journey.slice(1) })).score, 0);
  // An unknown step.
  assert.equal(mark(trip, 0, reply({ ...r, journey: [...r.journey, 'T99'] })).score, 0);

  const shop = byId('life.recipe-shopping');
  const s = ref(shop, 3) as { basket: Array<{ item: string; qty: number }>; total: number };
  // One pack fewer of the first line: short of something → 0.
  const short = { ...s, basket: s.basket.map((l, i) => (i === 0 ? { ...l, qty: l.qty - 1 } : l)).filter((l) => l.qty > 0) };
  assert.equal(mark(shop, 3, reply(short)).score, 0);
  // One extra pack: still enough, dearer → the formula (with the right total for that basket).
  const extra = { ...s, basket: s.basket.map((l, i) => (i === 0 ? { ...l, qty: l.qty + 1 } : l)) };
  const priced = mark(shop, 3, reply({ ...extra, total: 0 }));
  const value = priced.detail.value as number;
  const opt = (shop.cases[3]!.expected as { optimum: number }).optimum;
  assert.equal(mark(shop, 3, reply({ ...extra, total: value / 100 })).score, partialCredit('min', opt, value));
  // Product names instead of IDs are read too.
  const products = ((shop.cases[3]!.expected as { world: { products: Array<{ id: string; name: string }> } }).world.products);
  const named = { ...s, basket: s.basket.map((l) => ({ item: products.find((p) => p.id === l.item)!.name, qty: l.qty })) };
  assert.equal(mark(shop, 3, reply(named)).score, 1);

  const pack = byId('life.packing-limits');
  const p = ref(pack, 0) as Record<string, unknown> & { personal: string[] };
  // Leaving a must-bring item (the passport is I01) at home → 0.
  const noPassport = { ...p, personal: p.personal.filter((x) => x !== 'I01'), leave: [...(p.leave as string[]), 'I01'] };
  assert.equal(mark(pack, 0, reply(noPassport)).score, 0);
  // The same item in two places → 0.
  assert.equal(mark(pack, 0, reply({ ...p, wear: [p.personal[0]] })).score, 0);
});

test('careless scripted answers score as designed: each rule ignored on the trip planner breaks the plan', () => {
  const rows = spread();
  for (const r of rows) {
    assert.equal(r.careful, 1, r.test);
    assert.equal(r.doNothing, 0, r.test);
    assert.ok(r.careless < 0.8, `${r.test}: careless ${r.careless}`);
  }
  const t = rows.find((r) => r.test === 'life.trip-planner')!;
  for (const line of t.carelessDetail) {
    if (/ignores the|misses the late/.test(line)) assert.match(line, /: (0|\d{1,2})$/, line);
    if (/without the railcard discount/.test(line)) assert.match(line, /: 50$/, line);
  }
});

test('answer verifiers: one wrong field costs exactly its share; extra transfers always cost', () => {
  const loan = byId('life.loan-overpayment');
  const fields = (loan.cases[5]!.expected as { world: { fields: unknown[] } }).world.fields.length;
  const r = ref(loan, 5) as Record<string, number | string>;
  assert.equal(mark(loan, 5, reply({ ...r, months: (r.months as number) + 1 })).score, Math.round(((fields - 1) / fields) * 10000) / 10000);
  assert.equal(mark(loan, 5, reply({ ...r, totalInterest: (r.totalInterest as number) + 0.01 })).score, Math.round(((fields - 1) / fields) * 10000) / 10000);

  const tariff = byId('life.tariff-chooser');
  const tr = ref(tariff, 0) as { totals: Record<string, number>; cheapest: string };
  const names = (tariff.cases[0]!.expected as { world: { fields: Array<{ kind: string; choices?: Record<string, string> }> } }).world.fields.find((f) => f.kind === 'choice')!.choices!;
  assert.equal(mark(tariff, 0, reply({ ...tr, cheapest: names[tr.cheapest] })).score, 1, 'the option can be named instead of lettered');
  assert.ok(mark(tariff, 0, reply({ ...tr, cheapest: `${tr.cheapest} or A` })).score < 1);

  const bills = byId('life.bill-split');
  const br = ref(bills, 3) as { balances: Record<string, number>; transfers: Array<{ from: string; to: string; amount: number }> };
  const total = Object.keys(br.balances).length + br.transfers.length;
  // One extra transfer: one more check, failed.
  const extra = mark(bills, 3, reply({ ...br, transfers: [...br.transfers, { from: br.transfers[0]!.to, to: br.transfers[0]!.from, amount: 1 }] }));
  assert.equal(extra.score, Math.round((total / (total + 1)) * 10000) / 10000);
  // Listing every possible pair can never beat the right answer.
  const people = Object.keys(br.balances);
  const everything = people.flatMap((a) => people.filter((b) => b !== a).map((b) => ({ from: a, to: b, amount: 10 })));
  assert.ok(mark(bills, 3, reply({ balances: br.balances, transfers: everything })).score < 0.5);
  // Balances as a list of rows, names in another case, amounts as "£" strings.
  const rows = Object.entries(br.balances).map(([name, b]) => ({ Name: name.toUpperCase(), Balance: `${b < 0 ? '-' : ''}£${Math.abs(b).toFixed(2)}` }));
  assert.equal(mark(bills, 3, reply({ balances: rows, transfers: br.transfers })).score, 1);
});

test('scoring is deterministic', () => {
  for (const d of life) {
    const a = mark(d, d.cases.length - 1, reply(ref(d, d.cases.length - 1)));
    const b = mark(d, d.cases.length - 1, reply(ref(d, d.cases.length - 1)));
    assert.deepEqual(a, b);
  }
});

test('the trip checker agrees with the independent solver on every itinerary it can enumerate', () => {
  const r = crosscheckTrip(1500);
  assert.ok(r.checked > 5000);
  assert.deepEqual(r.mismatches, []);
});

test('the verifier source is part of the hash of every test that uses it', () => {
  const names = ['trip', 'shopping', 'packing', 'settle', 'fields'];
  const hashes = names.map(verifierSourceHash);
  assert.equal(new Set(hashes).size, names.length);
  assert.ok(hashes.every((h) => /^[0-9a-f]{12}$/.test(h)));
  assert.equal(verifierSourceHash('nope'), 'missing');
  for (const d of life) {
    const plain = contentHash({ def: d, fairPlay: FAIR_PLAY_FINGERPRINT });
    assert.notEqual(computeTestHash(d as TestDefinition), plain, `${d.id} hash covers the verifier`);
    assert.equal(computeTestHash(d as TestDefinition), contentHash({ def: d, fairPlay: FAIR_PLAY_FINGERPRINT, verifiers: { [verifierOf(d)]: verifierSourceHash(verifierOf(d)) } }));
  }
});

test('the app explains the verifier: depth view, Grading Station spec, and a stored answer can be re-marked', async () => {
  for (const d of life) {
    const lines = describeMarking(d.scorer);
    assert.ok(lines.length >= 2 && lines.some((l) => /JSON|data/.test(l)), d.id);
    const spec = gradingSpecFor(d, { caseId: d.cases[0]!.id });
    assert.equal(spec.kind, 'objective');
    assert.equal(spec.output, 'json');
    assert.ok(spec.checklist.length >= 2);
    assert.match(spec.answerKey!.rule, /Two answers in one reply score 0/);
  }
  // Re-marking: a stored reply is marked again from its text.
  const d = byId('life.recipe-shopping');
  const c = d.cases[0]!;
  const t = all.find((x) => x.definition.id === d.id)!;
  const stored: CaseResult = {
    key: 'k', runId: 'r', testId: d.id, testVersion: d.version, testHash: 'old', caseId: c.id, contestantId: 'm', repeat: 0, status: 'ok', score: 0.2, passed: false, summary: 'old mark',
    transcript: [{ label: 'response', system: undefined, messages: [{ role: 'user', content: c.prompt! }], response: reply(ref(d)), stopReason: 'end' }],
  } as unknown as CaseResult;
  const out = await remarkResult(stored, t, new Map(), 'test-protocol');
  assert.equal(out.kind, 'changed');
  if (out.kind === 'changed') assert.equal(out.to, 1);
});

test('answerCandidates ignores the format example and duplicates of the same answer', () => {
  const prompt = 'Answer like this:\n```json\n{"journey": ["<ID>"], "total": 0.00}\n```';
  assert.equal(answerCandidates('```json\n{"journey": ["<ID>"], "total": 0.00}\n```', ['journey'], prompt).length, 0);
  assert.equal(answerCandidates('{"journey": ["T1"], "total": 1}\n\nSo: {"Journey": ["t1"], "TOTAL": 1}', ['journey'], prompt).length, 1);
});
