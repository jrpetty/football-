// The finance business ladders (verification/finance-ladders/): Invoices to Pay, Check the Payslips, Expense Policy
// Check and Reconcile the Account, ten frozen levels each. The generators reproduce the files byte for byte; every
// level grows in volume, in points and in prompt size and prints at least the rule families of the level below it
// (word for word the current tests' wording); every key scores 100; do-nothing, copy-the-prompt and random filler
// score 0; a careless answer fails every rung; specific content mistakes cost points and format never does.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync, spawnSync } from 'node:child_process';
import { join } from 'node:path';
import { loadTests, caseScorer, renderCase } from '../src/core/registry.ts';
import { LADDER_IDS, PASS_MARK, SCRIPTED, ladderTests, points, scoreReply, spread } from '../verification/finance-ladders/spread.mts';
import type { PromptTest, PromptTestCase } from '../src/core/types.ts';

const tests = ladderTests();
const byId = (id: string) => tests.find((t) => t.id === id)!;
const all = loadTests().map((t) => t.definition);
const asJson = (x: unknown) => '```json\n' + JSON.stringify(x, null, 2) + '\n```';
const clone = <T>(x: T): T => JSON.parse(JSON.stringify(x));
const tok = (s: string) => Math.ceil(s.length / 3.6) + 20;
const HERE = join(import.meta.dirname, '..', 'verification', 'finance-ladders');

async function score(t: PromptTest, c: PromptTestCase, reply: string): Promise<number> {
  return (await scoreReply(caseScorer(t, c), c.expected, reply, renderCase(t, c).turns.join('\n'))).score ?? 0;
}

test('four ladders in the work category, ten frozen levels L01-L10 each, partial-credit json scoring', () => {
  assert.deepEqual(tests.map((t) => t.id), LADDER_IDS);
  for (const t of tests) {
    assert.equal(t.category, 'work');
    assert.deepEqual(t.cases.map((c) => c.id), ['L01', 'L02', 'L03', 'L04', 'L05', 'L06', 'L07', 'L08', 'L09', 'L10']);
    t.cases.forEach((c, i) => assert.match(c.notes ?? '', new RegExp(`^\\[level ${i + 1}\\] `), `${t.id} ${c.id} notes`));
    assert.equal(t.scorer.type, 'json');
    assert.ok(t.scorer.type === 'json' && t.scorer.lenient, `${t.id} is format-tolerant`);
    assert.ok(t.tags?.includes('ladder'), `${t.id} is tagged as a ladder`);
    assert.equal(t.answerWithinSec, undefined, 'no answer-time limit');
    assert.ok(t.estimate && t.estimate.inputTokens > 5000 && t.estimate.outputTokens >= 4000, `${t.id} has a realistic cost estimate`);
  }
});

test('the generators rebuild all four ladders and their scripted answers byte for byte', { timeout: 240_000 }, () => {
  const out = execFileSync(process.execPath, [join(HERE, 'build.mjs'), '--check'], { encoding: 'utf8' });
  assert.doesNotMatch(out, /DIFFERS/);
  const py = spawnSync('python3', ['--version']);
  if (py.status !== 0) return; // no Python on this machine
  const r = spawnSync('python3', ['build.py', '--check'], { cwd: HERE, encoding: 'utf8' });
  assert.equal(r.status, 0, r.stdout + r.stderr);
});

// ─── growth: volume, rule families, points and prompt size all rise level by level ───────────────────
const SIZE: Record<string, (c: PromptTestCase) => number> = {
  'work.invoices-ladder': (c) => Number(/ (\d+) docs, total/.exec(c.notes!)![1]),
  'work.payroll-ladder': (c) => Number(/ (\d+) staff, wrong/.exec(c.notes!)![1]),
  'work.expenses-ladder': (c) => Number(/^\[level \d+\] (\d+) claim lines/.exec(c.notes!)![1]),
  'work.reconciliation-ladder': (c) => {
    const m = /^\[level \d+\] (\d+) statement lines and (\d+) cash-book entries/.exec(c.notes!)!;
    return Number(m[1]) + Number(m[2]);
  },
};
const FULL_FAMILIES: Record<string, number> = { 'work.invoices-ladder': 14, 'work.payroll-ladder': 11, 'work.expenses-ladder': 11, 'work.reconciliation-ladder': 9 };

test('every level is strictly bigger than the one below, stacks rule families, and stays inside the token budget', () => {
  for (const t of tests) {
    const sizes = t.cases.map(SIZE[t.id]!);
    const fams = t.cases.map((c) => Number(/(\d+) families/.exec(c.notes!)![1]));
    const pts = t.cases.map((c) => points(t, c));
    const toks = t.cases.map((c) => tok(c.prompt!));
    for (let i = 1; i < 10; i++) {
      assert.ok(sizes[i]! > sizes[i - 1]!, `${t.id} L${i + 1}: more items (${sizes[i - 1]} -> ${sizes[i]})`);
      assert.ok(fams[i]! >= fams[i - 1]!, `${t.id} L${i + 1}: never fewer rule families`);
      assert.ok(pts[i]! > pts[i - 1]!, `${t.id} L${i + 1}: more points`);
      assert.ok(toks[i]! > toks[i - 1]!, `${t.id} L${i + 1}: a longer prompt`);
    }
    assert.ok(fams[0]! <= 2, `${t.id}: level 1 uses at most two rule families beyond the basics`);
    for (let i = 0; i < 4; i++) assert.ok(fams[i + 1]! > fams[i]!, `${t.id}: levels 1-5 each add rule families`);
    for (let i = 4; i < 10; i++) assert.equal(fams[i], FULL_FAMILIES[t.id], `${t.id} L${i + 1}: the full policy`);
    assert.ok(toks[0]! <= 3000, `${t.id} L01 is about 3k tokens at most (${toks[0]})`);
    assert.ok(toks[9]! <= 40000, `${t.id} L10 stays within ~40k input tokens (${toks[9]})`);
    assert.ok(sizes[9]! >= 4 * sizes[4]! / 2, `${t.id}: levels 6-10 go well beyond level 5 in volume`);
  }
});

/** A phrase that only the rule of each family prints, per ladder (in the order the ladder adds them). */
const MARKERS: Record<string, [string, RegExp][]> = {
  'work.invoices-ladder': [
    ['price', /Check \d+, price\./], ['qty', /Check \d+, quantity\./], ['entity', /Check \d+, entity\./], ['vat', /Check \d+, VAT\./], ['dup', /Check \d+, duplicate\./],
    ['bank', /Check \d+, bank details\./], ['po', /Scanned documents are OCR text/], ['discount', /"D\/10 net 30"/], ['credit', /Credit notes\. A credit note/],
    ['grn-emails', /"ignore my last email" puts the GRN figure back/], ['consume', /that you approve and that bill the same PO line/], ['total-cap', /may be at most £10\.00/],
    ['amend', /Use the PO unit price as amended/], ['supplier-net', /Exception: if a supplier's documents/],
  ],
  'work.payroll-ladder': [
    ['overtime', /Hourly rate for overtime/], ['student-loans', /Student loans \(on gross pay/], ['ni-categories', /Category C \(employee aged 66/], ['tax-codes', /K codes: the code number/],
    ['salary-sacrifice', /Salary sacrifice \(Cycle to Work\)/], ['ssp', /SSP \(company sick pay is SSP only\)/], ['starters', /Week 1\/month 1 \(M1\) basis/], ['late-items', /A coding notice from HMRC/],
    ['directors', /Company directors use the annual earnings period/], ['pension-sacrifice', /Pension by salary sacrifice \(staff marked so\)/], ['sunday-overtime', /Hours worked on a Sunday/],
  ],
  'work.expenses-ladder': [
    ['receipt', /NO_RECEIPT — /], ['hotel', /HOTEL_CAP — /], ['meal', /MEAL_CAP — /], ['rail', /RAIL_CLASS — /], ['alcohol', /ALCOHOL — /], ['late', /LATE — a line/], ['duplicate', /DUPLICATE — /],
    ['entertainment', /ENTERTAINMENT_CAP — /], ['mileage', /MILEAGE_RATE — /], ['conversion', /Conversion\. Amounts in euros/], ['meal-uplift', /A city's limit is £20 higher/],
  ],
  'work.reconciliation-ladder': [
    ['keying', /"keying_error" \(the cash book/], ['dd', /"unrecorded_direct_debit" \(/], ['duplicates', /"duplicate_payment" \(/], ['splits', /A payment or receipt can be split/],
    ['reversal', /"reversed_charge" \(/], ['batch', /the payments of one BACS run/], ['returned', /"returned_cheque" \(/], ['fx', /"fx_difference" \(/], ['prior', /6\. Items outstanding at the end of last month/],
  ],
};

test('each level prints exactly the rule families it uses, the first N of the same list, in the same words', () => {
  for (const t of tests) {
    const marks = MARKERS[t.id]!;
    for (const c of t.cases) {
      const n = Number(/(\d+) families/.exec(c.notes!)![1]);
      const names = /families \(([^)]*)\)/.exec(c.notes!)![1]!.split(', ');
      assert.deepEqual(names, marks.slice(0, n).map(([f]) => f), `${t.id} ${c.id}: families in ladder order`);
      marks.forEach(([f, re], i) => assert.equal(re.test(c.prompt!), i < n, `${t.id} ${c.id}: rule ${f} ${i < n ? 'printed' : 'not printed yet'}`));
    }
  }
});

/** The rules block of a prompt, between two markers. */
const block = (p: string, from: string, to: string) => p.slice(p.indexOf(from), p.indexOf(to, p.indexOf(from)));

test('from level 5 the rules are the full policy of the current test, word for word', () => {
  const cur = (id: string) => all.find((d) => d.id === id) as PromptTest;
  const pairs: [string, string, string, string, (c: PromptTestCase) => string][] = [
    ['work.expenses-ladder', 'work.expense-audit', 'Expenses policy', '\n\n', (c) => c.prompt!],
    ['work.reconciliation-ladder', 'work.bank-reconciliation', 'How to reconcile', '\n\n', (c) => c.prompt!],
    ['work.payroll-ladder', 'work.payroll-check', "THIS EXERCISE'S RATES", 'EMPLOYEE RECORDS', (c) => c.prompt!],
  ];
  for (const [ladder, base, from, to] of pairs) {
    const ref = block(cur(base).cases.at(-1)!.prompt!, from, to);
    for (const c of byId(ladder).cases.slice(4)) {
      // the expense policy block runs up to the rate table / register; compare the policy itself
      const got = block(c.prompt!, from, to === '\n\n' && ladder === 'work.expenses-ladder' ? '\n\nRate table' : to);
      const want = ladder === 'work.expenses-ladder' ? block(cur(base).cases.at(-1)!.prompt!, from, '\n\nRate table') : ref;
      assert.equal(got, want, `${ladder} ${c.id}`);
    }
  }
  // invoices: the policy names the business, so compare with a current case for the same business
  const inv = byId('work.invoices-ladder');
  const policy = (p: string) => block(p, 'ACCOUNTS PAYABLE POLICY', 'SUPPLIER MASTER FILE');
  for (const c of inv.cases.slice(4)) {
    const legal = /addressed \("Invoice to" \/ "Bill to"\) to ([^.]+)\./.exec(c.prompt!)![1]!;
    const same = cur('work.invoice-matching').cases.find((x) => x.prompt!.includes(`"Bill to") to ${legal}.`))!;
    assert.equal(policy(c.prompt!), policy(same.prompt!), `invoices ${c.id}`);
  }
});

// ─── keys and the spread ─────────────────────────────────────────────────────────────────────────────
test('spread: the key scores 100 on every level; do-nothing, copy-the-prompt and random filler 0; a careless answer fails every rung', async () => {
  const rows = await spread();
  assert.equal(rows.length, 40);
  for (const r of rows) {
    assert.equal(r.reference, 1, `${r.test} ${r.level} reference`);
    assert.equal(r.nothing, 0, `${r.test} ${r.level} do-nothing`);
    assert.ok(r.copy <= 0.02, `${r.test} ${r.level} copy-the-prompt ${r.copy}`);
    assert.equal(r.random, 0, `${r.test} ${r.level} random filler`);
    assert.ok(r.careless < PASS_MARK - 0.2, `${r.test} ${r.level}: a careless answer fails the rung (${r.careless})`);
    assert.ok(r.slip < 1 && r.slip > r.careless, `${r.test} ${r.level}: one slip costs points but beats carelessness (${r.slip} vs ${r.careless})`);
  }
  for (const id of LADDER_IDS) {
    const rs = rows.filter((r) => r.test === id);
    assert.ok(rs.reduce((a, r) => a + r.careless, 0) / rs.length <= 0.55, `${id}: careless answers lose at least 45% on average`);
  }
});

test('scoring is deterministic', async () => {
  for (const t of tests) {
    const c = t.cases[6]!;
    const reply = asJson(SCRIPTED[t.id]![c.id]!.careless);
    assert.equal(await score(t, c, reply), await score(t, c, reply));
  }
});

test('content mistakes cost points: a wrong outcome, a hedge, an extra payslip, an extra expense line, an invented difference', async () => {
  // invoices: a held document approved, and a hedged outcome
  {
    const t = byId('work.invoices-ladder');
    const c = t.cases[4]!;
    const e = clone(c.expected) as { documents: Record<string, { outcome: string; amount_to_pay: number; pay_by: string | null }> };
    const [held] = Object.entries(e.documents).find(([, d]) => d.outcome.startsWith('hold'))!;
    const wrong = clone(e);
    wrong.documents[held]!.outcome = 'approve';
    const s1 = await score(t, c, asJson(wrong));
    assert.ok(s1 < 1 && Math.abs(s1 - (1 - 4 / points(t, c))) < 1e-4, `one wrong outcome costs exactly its 4 points (${s1})`);
    const hedge = clone(e);
    hedge.documents[held]!.outcome = `${e.documents[held]!.outcome} or approve`;
    assert.equal(await score(t, c, asJson(hedge)), s1, 'a hedged outcome is as wrong as a wrong one');
  }
  // payroll: listing a correct payslip as wrong costs as much as missing one
  {
    const t = byId('work.payroll-ladder');
    const c = t.cases[3]!;
    const e = clone(c.expected) as { wrong_payslips: string[] };
    const right = (c.prompt!.match(/\bE\d\d\b/g) ?? []).find((id) => !e.wrong_payslips.includes(id))!;
    const extra = clone(e);
    extra.wrong_payslips.push(right);
    assert.ok((await score(t, c, asJson(extra))) < 1);
  }
  // expenses: a compliant line listed costs two fields at 3 points
  {
    const t = byId('work.expenses-ladder');
    const c = t.cases[2]!;
    const e = clone(c.expected) as { non_compliant: Record<string, { rule: string; pay: number }> };
    const free = (c.prompt!.match(/\bL\d\d\b/g) ?? []).find((id) => !(id in e.non_compliant))!;
    const extra = clone(e);
    extra.non_compliant[free] = { rule: 'NO_RECEIPT', pay: 0 };
    const total = points(t, c);
    const s = await score(t, c, asJson(extra));
    assert.ok(Math.abs(s - total / (total + 6)) < 1e-4, `an invented line costs 6 points (${s})`);
  }
  // reconciliation: an invented difference
  {
    const t = byId('work.reconciliation-ladder');
    const c = t.cases[1]!;
    const e = clone(c.expected) as { bank: Record<string, unknown>; differences: Record<string, { reason: string; amount: number }> };
    const line = Object.keys(e.bank).find((b) => !(b in e.differences))!;
    const extra = clone(e);
    extra.differences[line] = { reason: 'keying_error', amount: 9 };
    assert.ok((await score(t, c, asJson(extra))) < 1);
  }
});

test('format never costs points: £ strings, key case and spacing, "HOLD_PRICE", prose around the JSON', async () => {
  const money = (n: number) => `£${n.toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
  {
    const t = byId('work.invoices-ladder');
    const c = t.cases[3]!;
    const e = clone(c.expected) as { documents: Record<string, { outcome: string; amount_to_pay: number; pay_by: string | null }>; total_payable_this_run: number };
    const alt = {
      Documents: Object.fromEntries(Object.entries(e.documents).map(([k, d]) => [k.toLowerCase(), { Outcome: d.outcome.toUpperCase().replace(/-/g, '_'), 'Amount to pay': money(d.amount_to_pay), pay_by: d.pay_by }])),
      'total payable this run': money(e.total_payable_this_run),
    };
    assert.equal(await score(t, c, `Here is the run.\n${JSON.stringify(alt)}\nThanks.`), 1);
  }
  {
    const t = byId('work.payroll-ladder');
    const c = t.cases[2]!;
    const e = clone(c.expected) as { wrong_payslips: string[]; corrected: Record<string, Record<string, number>>; employer_ni_total: number };
    const alt = { 'Wrong payslips': e.wrong_payslips.map((x) => x.toLowerCase()), corrected: Object.fromEntries(Object.entries(e.corrected).map(([k, v]) => [k, Object.fromEntries(Object.entries(v).map(([f, n]) => [f.toUpperCase(), money(n)]))])), employer_ni_total: money(e.employer_ni_total) };
    assert.equal(await score(t, c, asJson(alt)), 1);
  }
  {
    const t = byId('work.expenses-ladder');
    const c = t.cases[5]!;
    const e = clone(c.expected) as { non_compliant: Record<string, { rule: string; pay: number }>; pay_by_employee: Record<string, number>; total_pay: number; vat_reclaimable: number };
    const alt = {
      'Non-compliant': Object.fromEntries(Object.entries(e.non_compliant).map(([k, v]) => [k.toLowerCase(), { Rule: v.rule.toLowerCase().replace('_', ' '), Pay: money(v.pay) }])),
      pay_by_employee: Object.fromEntries(Object.entries(e.pay_by_employee).map(([k, v]) => [k.toUpperCase(), money(v)])),
      'total pay': money(e.total_pay),
      vat_reclaimable: String(e.vat_reclaimable),
    };
    assert.equal(await score(t, c, asJson(alt)), 1);
  }
  {
    const t = byId('work.reconciliation-ladder');
    const c = t.cases[5]!;
    const e = clone(c.expected) as { bank: Record<string, string | string[]>; ledger: Record<string, string | string[]> };
    for (const side of [e.bank, e.ledger]) for (const k of Object.keys(side)) side[k] = Array.isArray(side[k]) ? [...(side[k] as string[])].reverse() : /^[BL]\d/.test(side[k] as string) ? [side[k] as string] : side[k]!;
    assert.equal(await score(t, c, asJson(e)), 1);
  }
});
