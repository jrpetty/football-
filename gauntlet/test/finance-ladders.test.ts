// The finance business ladders (verification/finance-ladders/): Invoices to Pay, Check the Payslips, Expense Policy
// Check and Reconcile the Account, ten frozen levels each. The generators reproduce the files byte for byte and the
// independent second derivations agree; every level grows in items, in chain depth (pools, months), in points and
// in prompt size, and prints at least the rule families of the level below it; every key scores 100; do-nothing,
// copy-the-prompt and random filler score 0; a careless answer fails every rung, and from level 5 so does one
// reasoning error that spreads through the chain; content mistakes cost points and format never does.
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
const hasPython = spawnSync('python3', ['--version']).status === 0;

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

test('the generators rebuild all four ladders, their scripted answers and the payroll inputs byte for byte', { timeout: 300_000 }, () => {
  const out = execFileSync(process.execPath, [join(HERE, 'build.mjs'), '--check'], { encoding: 'utf8' });
  assert.doesNotMatch(out, /DIFFERS/);
  if (!hasPython) return;
  const r = spawnSync('python3', ['build.py', '--check'], { cwd: HERE, encoding: 'utf8' });
  assert.equal(r.status, 0, r.stdout + r.stderr);
});

test('second derivation: an independent Python payroll recomputes every payslip, every month and every year-to-date figure', { timeout: 120_000 }, () => {
  if (!hasPython) return;
  const r = spawnSync('python3', ['check_payroll.py'], { cwd: HERE, encoding: 'utf8' });
  assert.equal(r.status, 0, r.stdout + r.stderr);
  assert.match(r.stdout, /payslips recomputed independently: OK/);
});

// ─── growth: items, chain depth, rule families, points and prompt size all rise level by level ───────────
const months = (c: PromptTestCase) => Number(/^\[level \d+\] (\d+) months?/.exec(c.notes!)![1]);
const SIZE: Record<string, (c: PromptTestCase) => number> = {
  'work.invoices-ladder': (c) => Number(/ (\d+) docs, total/.exec(c.notes!)![1]),
  'work.payroll-ladder': (c) => (c.prompt!.match(/ \| Payslip \| /g) ?? []).length,
  'work.expenses-ladder': (c) => Number(/months?, (\d+) claim lines/.exec(c.notes!)![1]),
  'work.reconciliation-ladder': (c) => {
    const m = /months?, (\d+) statement lines and (\d+) cash-book entries/.exec(c.notes!)!;
    return Number(m[1]) + Number(m[2]);
  },
};
/** The dependency depth: documents in pools (invoices), or the months that rest on each other. */
const DEPTH: Record<string, (c: PromptTestCase) => number> = {
  'work.invoices-ladder': (c) => {
    const m = /(\d+) pools \((\d+) documents each\)/.exec(c.notes!);
    return m ? Number(m[1]) * Number(m[2]) : 0;
  },
  'work.payroll-ladder': months,
  'work.expenses-ladder': months,
  'work.reconciliation-ladder': months,
};
const FULL_FAMILIES: Record<string, number> = { 'work.invoices-ladder': 14, 'work.payroll-ladder': 13, 'work.expenses-ladder': 13, 'work.reconciliation-ladder': 10 };

test('every level has more items, at least as deep chains and more points than the one below, and stays inside the token budget', () => {
  for (const t of tests) {
    const sizes = t.cases.map(SIZE[t.id]!);
    const depth = t.cases.map(DEPTH[t.id]!);
    const fams = t.cases.map((c) => Number(/(\d+) families/.exec(c.notes!)![1]));
    const pts = t.cases.map((c) => points(t, c));
    const toks = t.cases.map((c) => tok(c.prompt!));
    for (let i = 1; i < 10; i++) {
      assert.ok(sizes[i]! > sizes[i - 1]!, `${t.id} L${i + 1}: more items (${sizes[i - 1]} -> ${sizes[i]})`);
      assert.ok(depth[i]! >= depth[i - 1]!, `${t.id} L${i + 1}: chains at least as deep`);
      assert.ok(fams[i]! >= fams[i - 1]!, `${t.id} L${i + 1}: never fewer rule families`);
      assert.ok(pts[i]! > pts[i - 1]!, `${t.id} L${i + 1}: more points (${pts[i - 1]} -> ${pts[i]})`);
      assert.ok(toks[i]! > toks[i - 1]!, `${t.id} L${i + 1}: a longer prompt`);
    }
    assert.ok(depth[9]! >= 3 * Math.max(1, depth[2]!), `${t.id}: the top level's chains are at least three times as deep as level 3's`);
    assert.ok(fams[0]! <= 2, `${t.id}: level 1 uses at most two rule families beyond the basics`);
    for (let i = 0; i < 4; i++) assert.ok(fams[i + 1]! > fams[i]!, `${t.id}: levels 1-5 each add rule families`);
    for (let i = 4; i < 10; i++) assert.equal(fams[i], FULL_FAMILIES[t.id], `${t.id} L${i + 1}: every rule family`);
    assert.ok(toks[0]! <= 3000, `${t.id} L01 is about 3k tokens at most (${toks[0]})`);
    assert.ok(toks[9]! <= 40000, `${t.id} L10 stays within ~40k input tokens (${toks[9]})`);
  }
});

/** A phrase that only the rule of each family prints, per ladder (in the order the ladder adds them). */
const MARKERS: Record<string, [string, RegExp][]> = {
  'work.invoices-ladder': [
    ['price', /Check \d+, price\./], ['qty', /Check \d+, quantity\./], ['consume', /that you approve and that bill the same PO line/], ['vat', /Check \d+, VAT\./], ['entity', /Check \d+, entity\./],
    ['dup', /Check \d+, duplicate\./], ['bank', /Check \d+, bank details\./], ['po', /Scanned documents are OCR text/], ['credit', /Credit notes\. A credit note/],
    ['grn-emails', /"ignore my last email" puts the GRN figure back/], ['discount', /"D\/10 net 30"/], ['total-cap', /may be at most £10\.00/],
    ['amend', /Use the PO unit price as amended/], ['supplier-net', /Exception: if a supplier's documents/],
  ],
  'work.payroll-ladder': [
    ['overtime', /Hourly rate for overtime/], ['student-loans', /Student loans \(on gross pay/], ['ni-categories', /Category C \(employee aged 66/], ['tax-codes', /K codes: the code number/],
    ['months', /Several months\. Work out every month/], ['salary-sacrifice', /Salary sacrifice \(Cycle to Work\)/], ['ssp', /SSP \(company sick pay is SSP only\)/], ['starters', /Week 1\/month 1 \(M1\) basis/],
    ['late-items', /A coding notice from HMRC/], ['directors', /Company directors use the annual earnings period/], ['pension-sacrifice', /Pension by salary sacrifice \(staff marked so\)/],
    ['sunday-overtime', /Hours worked on a Sunday/], ['ssp-linking', /Linked sickness\. /],
  ],
  'work.expenses-ladder': [
    ['receipt', /NO_RECEIPT — /], ['hotel', /HOTEL_CAP — /], ['meal', /MEAL_CAP — /], ['rail', /RAIL_CLASS — /], ['alcohol', /ALCOHOL — /], ['late', /LATE — a line/], ['duplicate', /DUPLICATE — /],
    ['months', /Months\. The batches below are processed month by month/], ['entertainment', /ENTERTAINMENT_CAP — /], ['mileage', /MILEAGE_RATE — /], ['conversion', /Conversion\. Amounts in euros/],
    ['meal-uplift', /A city's limit is £20 higher/], ['advances', /Cash advances\. An advance still outstanding/],
  ],
  'work.reconciliation-ladder': [
    ['keying', /"keying_error" \(the cash book/], ['dd', /"unrecorded_direct_debit" \(/], ['duplicates', /"duplicate_payment" \(/], ['carry', /numbered through the months/],
    ['splits', /A payment or receipt can be split/], ['reversal', /"reversed_charge" \(/], ['returned', /"returned_cheque" \(/], ['batch', /the payments of one BACS run/], ['fx', /"fx_difference" \(/],
    ['instalments', /which can run over several months/],
  ],
};

test('each level prints exactly the rule families it uses, the first N of the same list', () => {
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

const block = (p: string, from: string, to: string) => p.slice(p.indexOf(from), p.indexOf(to, p.indexOf(from)));

test('from level 5 the invoice policy and the payroll rates are the current tests\' full wording (the ladders add their own clauses after it)', () => {
  const cur = (id: string) => all.find((d) => d.id === id) as PromptTest;
  const policy = (p: string) => block(p, 'ACCOUNTS PAYABLE POLICY', 'SUPPLIER MASTER FILE');
  for (const c of byId('work.invoices-ladder').cases.slice(4)) {
    const legal = /addressed \("Invoice to" \/ "Bill to"\) to ([^.]+)\./.exec(c.prompt!)![1]!;
    const same = cur('work.invoice-matching').cases.find((x) => x.prompt!.includes(`"Bill to") to ${legal}.`))!;
    assert.equal(policy(c.prompt!), policy(same.prompt!), `invoices ${c.id}`);
  }
  const ref = block(cur('work.payroll-check').cases.at(-1)!.prompt!, "THIS EXERCISE'S RATES", '\n\nEMPLOYEE RECORDS');
  for (const c of byId('work.payroll-ladder').cases.slice(4)) assert.ok(c.prompt!.includes(ref), `payroll ${c.id}`);
});

// ─── keys and the spread ─────────────────────────────────────────────────────────────────────────────
test('spread: the key scores 100 on every level; do-nothing, copy and filler 0; careless fails every rung; a spreading error fails from level 5', async () => {
  const rows = await spread();
  assert.equal(rows.length, 40);
  for (const r of rows) {
    assert.equal(r.reference, 1, `${r.test} ${r.level} reference`);
    assert.equal(r.nothing, 0, `${r.test} ${r.level} do-nothing`);
    assert.ok(r.copy <= 0.02, `${r.test} ${r.level} copy-the-prompt ${r.copy}`);
    assert.equal(r.random, 0, `${r.test} ${r.level} random filler`);
    assert.ok(r.careless < PASS_MARK - 0.2, `${r.test} ${r.level}: a careless answer fails the rung (${r.careless})`);
    assert.ok(r.slip < 1 && r.slip > r.careless, `${r.test} ${r.level}: one slip costs points but beats carelessness (${r.slip} vs ${r.careless})`);
    if (Number(r.level.slice(1)) >= 5) assert.ok(r.chain !== null && r.chain < PASS_MARK, `${r.test} ${r.level}: one error that spreads through the chain fails the rung (${r.chain})`);
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
  {
    const t = byId('work.payroll-ladder');
    const c = t.cases[4]!;
    const e = clone(c.expected) as { months: Record<string, { wrong_payslips: string[] }> };
    const first = Object.keys(e.months)[0]!;
    const right = (c.prompt!.match(/\bE\d\d\b/g) ?? []).find((id) => !e.months[first]!.wrong_payslips.includes(id))!;
    e.months[first]!.wrong_payslips.push(right);
    assert.ok((await score(t, c, asJson(e))) < 1);
  }
  {
    const t = byId('work.expenses-ladder');
    const c = t.cases[2]!;
    const e = clone(c.expected) as { non_compliant: Record<string, { rule: string; pay: number }> };
    const free = (c.prompt!.match(/\bL\d\d\b/g) ?? []).find((id) => !(id in e.non_compliant))!;
    e.non_compliant[free] = { rule: 'NO_RECEIPT', pay: 0 };
    const total = points(t, c);
    assert.ok(Math.abs((await score(t, c, asJson(e))) - total / (total + 6)) < 1e-4, 'an invented line costs 6 points');
  }
  {
    const t = byId('work.reconciliation-ladder');
    const c = t.cases[3]!;
    const e = clone(c.expected) as { bank: Record<string, unknown>; differences: Record<string, { reason: string; amount: number }> };
    const line = Object.keys(e.bank).find((b) => !(b in e.differences))!;
    e.differences[line] = { reason: 'keying_error', amount: 9 };
    assert.ok((await score(t, c, asJson(e))) < 1);
  }
});

test('format never costs points: £ strings, key case and spacing, "HOLD_PRICE", prose around the JSON', async () => {
  const money = (n: number) => `£${n.toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
  {
    const t = byId('work.invoices-ladder');
    const c = t.cases[3]!;
    const e = clone(c.expected) as { documents: Record<string, { outcome: string; amount_to_pay: number; pay_by: string | null }>; total_payable_this_run: number; remaining_received: Record<string, number> };
    const alt = {
      Documents: Object.fromEntries(Object.entries(e.documents).map(([k, d]) => [k.toLowerCase(), { Outcome: d.outcome.toUpperCase().replace(/-/g, '_'), 'Amount to pay': money(d.amount_to_pay), pay_by: d.pay_by }])),
      'total payable this run': money(e.total_payable_this_run),
      'Remaining received': Object.fromEntries(Object.entries(e.remaining_received).map(([k, v]) => [k.replace(' ', '-').toLowerCase(), String(v)])),
    };
    assert.equal(await score(t, c, `Here is the run.\n${JSON.stringify(alt)}\nThanks.`), 1);
  }
  {
    const t = byId('work.payroll-ladder');
    const c = t.cases[4]!;
    const e = clone(c.expected) as { months: Record<string, { wrong_payslips: string[]; corrected: Record<string, Record<string, number>>; employer_ni_total: number }>; year_to_date: Record<string, Record<string, number>> };
    const alt = {
      Months: Object.fromEntries(Object.entries(e.months).map(([m, v]) => [m.toUpperCase(), { 'Wrong payslips': v.wrong_payslips.map((x) => x.toLowerCase()), corrected: Object.fromEntries(Object.entries(v.corrected).map(([k, f]) => [k, Object.fromEntries(Object.entries(f).map(([n, x]) => [n.toUpperCase(), money(x)]))])), employer_ni_total: money(v.employer_ni_total) }])),
      'year to date': Object.fromEntries(Object.entries(e.year_to_date).map(([k, v]) => [k, { 'Taxable pay': money(v.taxable_pay!), Tax: money(v.tax!) }])),
    };
    assert.equal(await score(t, c, asJson(alt)), 1);
  }
  {
    const t = byId('work.expenses-ladder');
    const c = t.cases[5]!;
    const e = clone(c.expected) as { non_compliant: Record<string, { rule: string; pay: number }>; paid_out: Record<string, Record<string, number>>; advances_left: Record<string, number>; miles_to_date: Record<string, number>; vat_reclaimable: number };
    const alt = {
      'Non-compliant': Object.fromEntries(Object.entries(e.non_compliant).map(([k, v]) => [k.toLowerCase(), { Rule: v.rule.toLowerCase().replace('_', ' '), Pay: money(v.pay) }])),
      'Paid out': Object.fromEntries(Object.entries(e.paid_out).map(([m, v]) => [m, Object.fromEntries(Object.entries(v).map(([k, x]) => [k.toUpperCase(), money(x)]))])),
      advances_left: Object.fromEntries(Object.entries(e.advances_left).map(([k, x]) => [k, money(x)])),
      'miles to date': Object.fromEntries(Object.entries(e.miles_to_date).map(([k, x]) => [k, x.toLocaleString('en-GB')])),
      vat_reclaimable: String(e.vat_reclaimable),
    };
    assert.equal(await score(t, c, asJson(alt)), 1);
  }
  {
    const t = byId('work.reconciliation-ladder');
    const c = t.cases[5]!;
    const e = clone(c.expected) as { bank: Record<string, string | string[]>; ledger: Record<string, string | string[]>; adjusted_balance: Record<string, number | string> };
    for (const side of [e.bank, e.ledger]) for (const k of Object.keys(side)) side[k] = Array.isArray(side[k]) ? [...(side[k] as string[])].reverse() : /^[BL]\d/.test(side[k] as string) ? [side[k] as string] : side[k]!;
    for (const k of Object.keys(e.adjusted_balance)) e.adjusted_balance[k] = money(e.adjusted_balance[k] as number);
    assert.equal(await score(t, c, asJson(e)), 1);
  }
});
