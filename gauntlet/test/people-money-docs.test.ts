/**
 * The people-and-money Business visuals (ui/src/business/docs/people-money/): every case of the 14 tests must
 * read into its documents (CVs, invoices, POs and GRNs, payslips, claims, statement and cash book, exports, stock
 * list), the answer key given back as the model's answer must come out right everywhere, and a planted mistake
 * must be found and described from the case data. The CV shortlist ticks are shown only where re-applying the
 * rubric gives exactly the answer key: it must do so for every case.
 */
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import type { PromptTest } from '../src/core/types.ts';
import type { CaseVisualInput } from '../src/presenter/visuals/common.ts';
import { cvReadRows, cvReadSentence, readCvQuestion, relevantMonths, shortlistAnswer, shortlistQuestion, shortlistSentence, verifiedRubric } from '../ui/src/business/docs/people-money/model/cv.ts';
import { invoiceDoc, invoiceRows } from '../ui/src/business/docs/people-money/model/invoiceRead.ts';
import { apAnswer, apImpact, apQuestion, docMatch } from '../ui/src/business/docs/people-money/model/ap.ts';
import { deliveryAnswer, deliveryQuestion } from '../ui/src/business/docs/people-money/model/delivery.ts';
import { payrollAnswer, payrollQuestion, payrollSentence } from '../ui/src/business/docs/people-money/model/payroll.ts';
import { expensesAnswer, expensesQuestion } from '../ui/src/business/docs/people-money/model/expenses.ts';
import { recAnswer, recQuestion } from '../ui/src/business/docs/people-money/model/bankrec.ts';
import { boardAnswer, boardQuestion, reorderAnswer, reorderQuestion } from '../ui/src/business/docs/people-money/model/opsdata.ts';

const load = (name: string): PromptTest => JSON.parse(readFileSync(new URL(`../tests/work/${name}.json`, import.meta.url), 'utf8')) as PromptTest;

function input(t: PromptTest, i: number, answer?: unknown): CaseVisualInput {
  const c = t.cases[i]!;
  return {
    testId: t.id,
    caseId: c.id,
    turns: [c.prompt ?? ''],
    replies: answer === undefined ? [] : [`Here it is.\n\n\`\`\`json\n${JSON.stringify(answer)}\n\`\`\``],
    expected: c.expected,
    notes: c.notes,
    detail: {},
    score: answer === undefined ? null : 1,
    passed: null,
    status: answer === undefined ? 'question' : 'ok',
  };
}
const clone = <T>(x: T): T => JSON.parse(JSON.stringify(x)) as T;

test('Read a CV: every CV read, months recounted from the roles equal the key, the key scores every box', () => {
  const t = load('cv-read');
  t.cases.forEach((c, i) => {
    const cv = readCvQuestion(c.prompt!, c.notes);
    assert.ok(cv, c.id);
    const m = relevantMonths(cv)!;
    const key = c.expected as Record<string, number>;
    assert.equal(m.total, key.relevant_experience_months, `${c.id} months`);
    assert.equal(m.longest, key.longest_unbroken_relevant_months, `${c.id} longest`);
    const { rows, answered } = cvReadRows(cv, input(t, i, c.expected));
    assert.ok(answered);
    assert.ok(rows.every((r) => r.ok), c.id);
  });
  // The overlap trap: the naive sum is named as counting overlapping months twice.
  const i = t.cases.findIndex((c) => /naive sum without merging overlaps (\d+)/.test(c.notes ?? '') && Number(c.notes!.match(/overlaps ([\d.]+)/)![1]) !== (c.expected as Record<string, number>).relevant_experience_months);
  const c = t.cases[i]!;
  const naive = Number(c.notes!.match(/overlaps ([\d.]+)/)![1]);
  const a = clone(c.expected) as Record<string, unknown>;
  a.relevant_experience_months = naive;
  const res = cvReadRows(readCvQuestion(c.prompt!, c.notes)!, input(t, i, a));
  const row = res.rows.find((r) => r.key === 'relevant_experience_months')!;
  assert.equal(row.ok, false);
  assert.match(row.why!, /overlapping months twice/);
  assert.match(cvReadSentence(res.rows, true), /6 of 7 boxes/);
});

test('Shortlist: the rubric re-applied gives exactly the key on every case; a lost candidate and an obeyed injection are named', () => {
  const t = load('cv-shortlist');
  t.cases.forEach((c, i) => {
    const q = shortlistQuestion(c.prompt!);
    assert.ok(q, c.id);
    assert.ok(verifiedRubric(q, c.expected as Record<string, unknown>), `${c.id}: rubric disagrees with the key`);
    const s = shortlistAnswer(q, input(t, i, c.expected), verifiedRubric(q, c.expected as Record<string, unknown>));
    assert.ok(s.calls.every((x) => x.right), c.id);
  });
  const c = t.cases[0]!;
  const q = shortlistQuestion(c.prompt!)!;
  const run = verifiedRubric(q, c.expected as Record<string, unknown>);
  const key = c.expected as { shortlist: string[]; scores: Record<string, number>; screener_instructions: string[] };
  const unq = run!.rows.filter((r) => !r.eligible && !r.injected).sort((a, b) => b.score - a.score)[0]!;
  const lost = key.shortlist[2]!;
  const a = { shortlist: [key.shortlist[0], key.shortlist[1], unq.id], scores: { ...key.scores, [unq.id]: unq.score }, screener_instructions: key.screener_instructions };
  const s = shortlistAnswer(q, input(t, 0, a), run);
  assert.equal(s.calls.find((x) => x.id === lost)!.tag, 'Good candidate lost');
  assert.equal(s.calls.find((x) => x.id === unq.id)!.tag, 'Unqualified candidate shortlisted');
  const inj = { ...key, shortlist: [key.screener_instructions[0], ...key.shortlist.slice(0, 2)], screener_instructions: [] };
  const s2 = shortlistAnswer(q, input(t, 0, inj), run);
  assert.equal(s2.obeyedInjection, key.screener_instructions[0]);
  assert.match(shortlistSentence(q, s2), /scores 0/);
});

test('Read an Invoice: every invoice read; the "total now due on account" trap is named', () => {
  const t = load('invoice-read');
  t.cases.forEach((c, i) => {
    const doc = invoiceDoc(c.prompt!);
    assert.ok(doc, c.id);
    assert.ok(invoiceRows(doc, input(t, i, c.expected)).rows.every((r) => r.ok), c.id);
  });
  const i = t.cases.findIndex((c) => /total now due on account/.test(c.notes ?? ''));
  const c = t.cases[i]!;
  const a = clone(c.expected) as Record<string, unknown>;
  a.gross = Number(c.notes!.match(/total now due on account ([\d.]+)/)![1]);
  const row = invoiceRows(invoiceDoc(c.prompt!)!, input(t, i, a)).rows.find((r) => r.path === 'gross')!;
  assert.equal(row.ok, false);
  assert.match(row.why!, /earlier balance/);
});

test('Invoices to Pay and its ladder: every document matched to its PO and GRNs; paying a held invoice is priced from the answer', () => {
  for (const name of ['invoice-matching', 'invoices-ladder']) {
    const t = load(name);
    t.cases.forEach((c, i) => {
      const ap = apQuestion(c.prompt!);
      assert.ok(ap, `${name} ${c.id}`);
      const key = c.expected as { documents: Record<string, unknown> };
      assert.deepEqual(ap.docs.map((d) => d.id).sort(), Object.keys(key.documents).sort(), `${name} ${c.id} documents`);
      for (const d of ap.docs) assert.ok(d.items.length > 0 && d.total !== null, `${name} ${c.id} ${d.id}`);
      assert.ok(ap.docs.filter((d) => !docMatch(ap, d).po).length <= 1, `${name} ${c.id} POs`);
      assert.ok(apAnswer(input(t, i, c.expected)).rows.every((r) => r.right), `${name} ${c.id}`);
    });
  }
  const t = load('invoice-matching');
  const c = t.cases[0]!;
  const a = clone(c.expected) as { documents: Record<string, { outcome: string; amount_to_pay: number; pay_by: string | null }> };
  const held = Object.keys(a.documents).find((k) => a.documents[k]!.outcome !== 'approve')!;
  a.documents[held] = { outcome: 'approve', amount_to_pay: 274.39, pay_by: '2026-11-05' };
  const r = apAnswer(input(t, 0, a)).rows.find((x) => x.id === held)!;
  const x = apImpact(r, apQuestion(c.prompt!)!.docs.find((d) => d.id === held));
  assert.equal(x!.paidWrongly, 274.39);
  assert.match(x!.text, /Would have paid £274\.39/);
});

test('Check the Delivery: every order line has its delivery-note lines; the key counts right', () => {
  const t = load('delivery-note');
  t.cases.forEach((c, i) => {
    const q = deliveryQuestion(c.prompt!);
    assert.ok(q, c.id);
    assert.deepEqual(q.po.map((p) => p.sku).sort(), Object.keys((c.expected as { lines: object }).lines).sort());
    assert.ok(deliveryAnswer(q, input(t, i, c.expected)).rows.every((r) => r.right), c.id);
  });
});

test('Payslips and the payroll ladder: every draft payslip read month by month; a missed planted error is priced', () => {
  for (const name of ['payroll-check', 'payroll-ladder']) {
    const t = load(name);
    t.cases.forEach((c, i) => {
      const q = payrollQuestion(c.prompt!);
      assert.ok(q, `${name} ${c.id}`);
      for (const m of q.months) for (const s of m.slips) assert.ok(Object.values(s.draft).every((v) => v !== null), `${name} ${c.id} ${s.id}`);
      const a = payrollAnswer(q, input(t, i, c.expected));
      assert.ok(a.months.every((m) => m.slips.every((s) => s.right) && m.niOk), `${name} ${c.id}`);
    });
  }
  const t = load('payroll-check');
  const c = t.cases[0]!;
  const a = { wrong_payslips: [], corrected: {}, employer_ni_total: (c.expected as { employer_ni_total: number }).employer_ni_total };
  const res = payrollAnswer(payrollQuestion(c.prompt!)!, input(t, 0, a));
  const missed = res.months[0]!.slips.find((s) => s.keyWrong)!;
  assert.equal(missed.flagged, false);
  assert.equal(missed.netGap, 64.8);
  assert.match(payrollSentence(res), /caught 0 of the 1 wrong payslips/);
});

test('Expenses, reconciliation, board and reorder: every case read and the key judged right', () => {
  for (const name of ['expense-audit', 'expenses-ladder']) {
    const t = load(name);
    t.cases.forEach((c, i) => {
      const q = expensesQuestion(c.prompt!);
      assert.ok(q, `${name} ${c.id}`);
      const a = expensesAnswer(q, input(t, i, c.expected));
      const listed = Object.keys((c.expected as { non_compliant: object }).non_compliant);
      assert.equal(a.lines.filter((l) => l.key).length, listed.length, `${name} ${c.id} every flagged line found`);
      assert.ok(a.lines.every((l) => l.right) && a.totals.every((x) => x.ok), `${name} ${c.id}`);
    });
  }
  for (const name of ['bank-reconciliation', 'reconciliation-ladder']) {
    const t = load(name);
    t.cases.forEach((c, i) => {
      const q = recQuestion(c.prompt!);
      assert.ok(q, `${name} ${c.id}`);
      const a = recAnswer(q, input(t, i, c.expected));
      assert.ok([...a.bank.values(), ...a.book.values()].every((v) => v.key && v.ok), `${name} ${c.id}`);
      assert.ok(a.balances.every((b) => b.ok) && a.diffs.every((d) => d.ok), `${name} ${c.id} balances`);
    });
  }
  const b = load('board-report');
  b.cases.forEach((c, i) => {
    assert.ok(boardQuestion(c.prompt!)?.grids.length, c.id);
    assert.ok(boardAnswer(input(b, i, c.expected)).rows.every((r) => r.ok), c.id);
  });
  const s = load('stock-reorder');
  s.cases.forEach((c, i) => {
    assert.ok(reorderQuestion(c.prompt!)?.items.length, c.id);
    const ref = (c.expected as { reference: unknown }).reference;
    const a = reorderAnswer(input(s, i, ref))!;
    assert.ok(a.rows.every((r) => r.got === r.best && !r.broken), c.id);
    assert.ok(a.statedOk, `${c.id} reference total`);
  });
});
