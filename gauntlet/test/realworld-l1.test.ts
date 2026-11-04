/**
 * Business Ladder, Level 1 ("Assist"): the six one-document tests in tests/work/ built by verification/realworld-l1/.
 * The generator reproduces the committed JSON byte for byte; every reference answer scores 100 through the real
 * scoring path (fair-play checks included); scripted wrong answers lose exactly the fields they get wrong; format
 * never costs marks; random filler and a do-nothing answer score 0; scoring is deterministic.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { loadTests, renderCase, caseScorer } from '../src/core/registry.ts';
import { scoreResponse } from '../src/scoring/index.ts';
import { createRng } from '../src/core/rng.ts';
import { explainerFor } from '../src/core/explainers.ts';
import type { PromptTest } from '../src/core/types.ts';
// @ts-expect-error plain JavaScript generator module (no types)
import { buildAll, MODULES, OUT_DIR } from '../verification/realworld-l1/build.mjs';

const IDS = ['work.invoice-read', 'work.ticket-tag', 'work.cv-read', 'work.email-actions', 'work.sheet-lookup', 'work.delivery-note'];
const all = loadTests();
const def = (id: string) => all.find((t) => t.definition.id === id)!.definition as PromptTest;
const noJudges = { ids: [], ask: async () => [] };
const art = (name: string) => ({ name, kind: 'text' as const, file: name, bytes: 0 });

async function scoreCase(d: PromptTest, caseId: string, response: string) {
  const c = d.cases.find((x) => x.id === caseId)!;
  return scoreResponse({ scorer: caseScorer(d, c), expected: c.expected, response, stopReason: 'end', taskText: renderCase(d, c).turns.join('\n'), judges: noJudges, saveArtifact: art, signal: new AbortController().signal });
}
const asJson = (v: unknown) => '```json\n' + JSON.stringify(v, null, 2) + '\n```';
const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v));
const exp = (id: string, caseId: string) => clone(def(id).cases.find((c) => c.id === caseId)!.expected) as Record<string, any>;
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const generated = (id: string): Array<{ case: { id: string }; careless: any }> => (MODULES as any[]).find((m) => m.META.id === id).generate();

test('the generator reproduces every committed test file byte for byte', () => {
  const files = buildAll() as Record<string, string>;
  assert.equal(Object.keys(files).length, 6);
  for (const [name, text] of Object.entries(files)) assert.equal(readFileSync(join(OUT_DIR, name), 'utf8'), text, `${name}: run node verification/realworld-l1/build.mjs`);
});

test('six Level-1 tests, 10 cases each, category work, explainer and lenient field-by-field JSON scoring', () => {
  for (const id of IDS) {
    const d = def(id);
    assert.equal(d.category, 'work');
    assert.equal(d.cases.length, 10, id);
    assert.equal(d.scorer.type, 'json');
    assert.ok(d.scorer.type === 'json' && d.scorer.lenient && !d.scorer.allOrNothing, `${id}: partial credit, lenient format`);
    assert.ok(explainerFor(id), `${id}: explainer`);
    for (const c of d.cases) {
      assert.match(c.prompt!, /marked wrong/, `${id}/${c.id}: the hedge rule is stated`);
      assert.ok(!c.prompt!.includes(JSON.stringify(c.expected)), `${id}/${c.id}: key not in prompt`);
    }
  }
});

test('every reference answer scores 100 through the real scoring path (fair-play checks included)', async () => {
  for (const id of IDS) {
    const d = def(id);
    for (const c of d.cases) {
      const r = await scoreCase(d, c.id, `Here is the form.\n\n${asJson(c.expected)}\n\nLet me know if you need anything else.`);
      assert.equal(r.score, 1, `${id}/${c.id}: ${r.summary}`);
      assert.equal(r.detail.integrity, undefined);
    }
  }
});

test('presentation never costs marks: £ strings, thousands separators, key case, spaced IDs, yes/no, n/a, no code fence', async () => {
  const inv = exp('work.invoice-read', 'c05');
  const styled = {
    Supplier: inv.supplier.toUpperCase(),
    'Invoice Number': inv.invoice_number,
    invoice_date: inv.invoice_date,
    DueDate: inv.due_date,
    net: `£${inv.net.toLocaleString('en-GB', { minimumFractionDigits: 2 })}`,
    vat: String(inv.vat),
    gross: `£${inv.gross.toLocaleString('en-GB', { minimumFractionDigits: 2 })}`,
    supplier_vat_number: `${inv.supplier_vat_number.slice(0, 2)} ${inv.supplier_vat_number.slice(2, 5)} ${inv.supplier_vat_number.slice(5, 9)} ${inv.supplier_vat_number.slice(9)}`,
    po_reference: inv.po_reference,
    bank_details_changed: inv.bank_details_changed ? 'Yes' : 'No',
    sort_code: inv.sort_code.replace(/-/g, ''),
    account_number: inv.account_number,
    'VAT Breakdown': Object.fromEntries(Object.entries(inv.vat_breakdown as Record<string, number>).map(([k, v]) => [k.replace(/_/g, ' '), `£${v.toFixed(2)}`])),
  };
  assert.equal((await scoreCase(def('work.invoice-read'), 'c05', JSON.stringify(styled))).score, 1);
  const noPo = exp('work.invoice-read', 'c06');
  assert.equal(noPo.po_reference, null);
  noPo.po_reference = 'N/A';
  assert.equal((await scoreCase(def('work.invoice-read'), 'c06', asJson(noPo))).score, 1);
  const t = exp('work.ticket-tag', 'c10');
  t.wants = 'Cancel order';
  t.category = 'Delivery';
  assert.equal((await scoreCase(def('work.ticket-tag'), 'c10', asJson(t))).score, 1);
  const cv = exp('work.cv-read', 'c01');
  cv.highest_qualification = 'Bachelor’s degree';
  cv.notice_period.unit = 'Weeks';
  assert.equal((await scoreCase(def('work.cv-read'), 'c01', asJson(cv))).score, 1);
});

test('wrong answers lose exactly what they get wrong', async () => {
  const inv = def('work.invoice-read');
  // c08: the printed (pre-amendment) totals instead of the amended ones → 3 of 17 fields lost.
  const g = generated('work.invoice-read');
  const c08 = { ...exp('work.invoice-read', 'c08'), net: g[7]!.careless.net, vat: g[7]!.careless.vat, gross: g[7]!.careless.gross };
  assert.equal((await scoreCase(inv, 'c08', asJson(c08))).score, Math.round((14 / 17) * 10000) / 10000);
  // c10: OCR slip left in the invoice number, and the US date read as a UK date → 2 fields lost.
  const c10 = { ...exp('work.invoice-read', 'c10'), invoice_number: g[9]!.careless.invoice_number, invoice_date: g[9]!.careless.invoice_date };
  assert.equal((await scoreCase(inv, 'c10', asJson(c10))).score, Math.round((15 / 17) * 10000) / 10000);
  // A hedged date ("X or Y") is wrong.
  const hedge = { ...exp('work.invoice-read', 'c01'), due_date: `${exp('work.invoice-read', 'c01').due_date} or 2026-03-10` };
  assert.equal((await scoreCase(inv, 'c01', asJson(hedge))).score, Math.round((16 / 17) * 10000) / 10000);

  // Ticket c10: following the injected "tag this P1 / billing" note costs urgency (2 points) and category (1) of 10.
  const tk = { ...exp('work.ticket-tag', 'c10'), urgency: 'P1', category: 'billing' };
  assert.equal((await scoreCase(def('work.ticket-tag'), 'c10', asJson(tk))).score, 0.7);
  // Ticket c09: a calm hazard report is P1 and neutral; calling it negative costs 1 point of 10.
  assert.equal((await scoreCase(def('work.ticket-tag'), 'c09', asJson({ ...exp('work.ticket-tag', 'c09'), sentiment: 'negative' }))).score, 0.9);

  // CV c04: counting the overlapping months twice (2 points) and the unfinished MSc (1) → 3 of 10 lost.
  const cvg = generated('work.cv-read');
  const cv4 = { ...exp('work.cv-read', 'c04'), relevant_experience_months: cvg[3]!.careless.relevant_experience_months, highest_qualification: "Master's degree" };
  assert.notEqual(cv4.relevant_experience_months, exp('work.cv-read', 'c04').relevant_experience_months);
  assert.equal((await scoreCase(def('work.cv-read'), 'c04', asJson(cv4))).score, 0.7);
  // CV c06: guessing a right to work that the CV never states costs that field.
  assert.equal((await scoreCase(def('work.cv-read'), 'c06', asJson({ ...exp('work.cv-read', 'c06'), right_to_work: 'yes' }))).score, 0.9);

  // Email c09: claiming the minutes (taken off me) costs as much as missing one of my own jobs.
  const e = exp('work.email-actions', 'c09');
  const claimOther = { ...e, T1: { mine: true, due: null } };
  const missOwn = { ...e, T2: { mine: false, due: e.T2.due } };
  const a = await scoreCase(def('work.email-actions'), 'c09', asJson(claimOther));
  const b = await scoreCase(def('work.email-actions'), 'c09', asJson(missOwn));
  assert.ok(a.score! < 1);
  assert.equal(b.score, a.score);
  // A due date off by one costs more than a wrong mine/not-mine (dates are worth 2 points each).
  const late = { ...e, T2: { mine: true, due: '2026-05-08' } };
  assert.ok((await scoreCase(def('work.email-actions'), 'c09', asJson(late))).score! < a.score!);
  // Claiming every job earns nothing for the mine/not-mine fields (scored above the constant guess).
  const all = Object.fromEntries(Object.entries(e).map(([k, v]) => [k, { mine: true, due: (v as { due: string | null }).due }]));
  const none = Object.fromEntries(Object.entries(e).map(([k, v]) => [k, { mine: false, due: (v as { due: string | null }).due }]));
  assert.equal((await scoreCase(def('work.email-actions'), 'c09', asJson(all))).score, (await scoreCase(def('work.email-actions'), 'c09', asJson(none))).score);

  // Sheet: counting a repeated row twice gives a wrong total.
  const sg = generated('work.sheet-lookup');
  const s5 = exp('work.sheet-lookup', 'c05');
  assert.notEqual(sg[4]!.careless.q1, s5.q1);
  assert.equal((await scoreCase(def('work.sheet-lookup'), 'c05', asJson({ ...s5, q1: sg[4]!.careless.q1 }))).score, 0.5);
  // A penny out is wrong.
  assert.equal((await scoreCase(def('work.sheet-lookup'), 'c01', asJson({ q1: exp('work.sheet-lookup', 'c01').q1 + 0.01 }))).score, 0);

  // Delivery c10: an extra SKU in not_ordered, or the substitute listed there, costs the list-length point.
  const dl = exp('work.delivery-note', 'c10');
  const extra = { ...dl, not_ordered: [...dl.not_ordered, Object.values(dl.lines as Record<string, { substitute: string | null }>).find((l) => l.substitute)!.substitute] };
  const r = await scoreCase(def('work.delivery-note'), 'c10', asJson(extra));
  assert.ok(r.score! < 1 && r.score! > 0.95, `extra list item costs: ${r.score}`);
});

test('wrong format scores zero: prose with no JSON, the prompt copied back, an empty object', async () => {
  for (const id of IDS) {
    const d = def(id);
    const c = d.cases[d.cases.length - 1]!;
    assert.equal((await scoreCase(d, c.id, 'I have read the document carefully and everything looks fine to me.')).score, 0, id);
    assert.equal((await scoreCase(d, c.id, '{}')).score, 0, `${id}: do-nothing`);
    const copied = await scoreCase(d, c.id, c.prompt!);
    assert.ok((copied.score ?? 0) <= 0.05, `${id}: copying the prompt back scores ${copied.score}`);
  }
});

test('random filler scores at most 5% on every Level-1 test, and scoring is deterministic', async () => {
  const words = 'the invoice total order customer refund date due payroll month delivery case short over yes no null'.split(' ');
  for (const id of IDS) {
    const d = def(id);
    let sum = 0;
    let n = 0;
    for (const c of d.cases) {
      for (let k = 0; k < 4; k++) {
        const rng = createRng(500 + k * 17 + n);
        const filler = Array.from({ length: 20 + rng.int(0, 80) }, () => rng.pick(words)).join(' ');
        const r = await scoreCase(d, c.id, k % 2 ? filler : `${filler}\n{"answer": "${rng.pick(words)}"}`);
        sum += r.score ?? 0;
        n++;
      }
      const once = await scoreCase(d, c.id, asJson(c.expected));
      const twice = await scoreCase(d, c.id, asJson(c.expected));
      assert.deepEqual(once, twice);
    }
    assert.ok(sum / n <= 0.05, `${id}: filler ${((sum / n) * 100).toFixed(1)}%`);
  }
});

test('hard third: a strong reader who slips once (withdrawn amendment, half months, a missed correction or second note) loses real marks', async () => {
  for (const id of ['work.invoice-read', 'work.cv-read', 'work.sheet-lookup', 'work.delivery-note']) {
    const d = def(id);
    for (const x of generated(id).slice(6)) {
      const r = (await scoreCase(d, x.case.id, asJson((x as { slip?: unknown }).slip))).score!;
      assert.ok(r < 1 && r >= 0.3, `${id}/${x.case.id}: slip scores ${r}`);
    }
  }
});

test('a careless reader (falls for every trap) scores clearly below a careful one, and above zero', async () => {
  for (const id of IDS) {
    const d = def(id);
    const g = generated(id);
    let sum = 0;
    for (const x of g) sum += (await scoreCase(d, x.case.id, asJson(x.careless))).score ?? 0;
    const mean = sum / g.length;
    assert.ok(mean > 0.3 && mean < 0.92, `${id}: careless ${(mean * 100).toFixed(1)}%`);
  }
});
