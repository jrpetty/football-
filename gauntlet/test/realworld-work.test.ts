// The Real-World Work pack (tests/work/*.json): generators reproduce the files, every key scores 100, scripted
// careless answers, do-nothing replies, copy-the-prompt replies and random filler score as intended, format
// variations are forgiven, and specific content mistakes cost exactly the points the prompt says.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { caseScorer, renderCase } from '../src/core/registry.ts';
import { compareJson, looseNumber, normSetId } from '../src/scoring/json-compare.ts';
import { SCRIPTED, WORK_IDS, filler, scoreReply, spread, workTests } from '../verification/realworld-work/spread.mts';
import type { PromptTest, PromptTestCase } from '../src/core/types.ts';

const tests = workTests();
const byId = (id: string) => tests.find((t) => t.id === id)!;
const json = (x: unknown) => '```json\n' + JSON.stringify(x, null, 2) + '\n```';
const clone = <T>(x: T): T => JSON.parse(JSON.stringify(x));

async function score(t: PromptTest, c: PromptTestCase, reply: string): Promise<number> {
  const r = await scoreReply(caseScorer(t, c), c.expected, reply, renderCase(t, c).turns.join('\n'));
  return r.score ?? 0;
}
function points(t: PromptTest, c: PromptTestCase): number {
  const sc = caseScorer(t, c);
  if (sc.type !== 'json') throw new Error('json scorer expected');
  return compareJson(c.expected, c.expected, { loose: sc.loose, sets: sc.sets, unorderedArrays: sc.unorderedArrays, weights: sc.weights, aboveConstant: sc.aboveConstant }).total;
}
/** Points one field is worth in this case (the scorer's weights; 1 when unweighted). */
function w(t: PromptTest, c: PromptTestCase, path: string): number {
  const sc = caseScorer(t, c);
  if (sc.type !== 'json' || !sc.weights) return 1;
  for (let p = path; ; ) {
    if (sc.weights[p] !== undefined) return sc.weights[p]!;
    const i = p.lastIndexOf('.');
    if (i < 0) return 1;
    p = p.slice(0, i);
  }
}
const round4 = (x: number) => Math.round(x * 10000) / 10000;

test('the pack has five tests of nine cases: three easy, three medium, three hard', () => {
  assert.deepEqual(tests.map((t) => t.id), WORK_IDS);
  for (const t of tests) {
    assert.equal(t.category, 'work');
    assert.equal(t.cases.length, 9, t.id);
    const tiers = t.cases.map((c) => /^\[(easy|medium|hard)\]/.exec(c.notes ?? '')?.[1]);
    assert.deepEqual(tiers, ['easy', 'easy', 'easy', 'medium', 'medium', 'medium', 'hard', 'hard', 'hard'], t.id);
    assert.equal(t.scorer.type, 'json');
    assert.ok(t.scorer.type === 'json' && t.scorer.loose, `${t.id} uses format-tolerant JSON`);
    assert.ok(!('answerWithinSec' in t), 'no answer-time limit');
  }
});

test('the generators rebuild every test file and the scripted answers byte for byte', { timeout: 240_000 }, () => {
  const py = spawnSync('python3', ['--version']);
  if (py.status !== 0) return; // no Python on this machine: nothing to compare
  const out = mkdtempSync(join(tmpdir(), 'gauntlet-work-'));
  try {
    const r = spawnSync('python3', ['build.py', '--out', out], { cwd: join(import.meta.dirname, '..', 'verification', 'realworld-work'), encoding: 'utf8' });
    assert.equal(r.status, 0, r.stderr);
    const here = join(import.meta.dirname, '..', 'tests', 'work');
    // tests/work/ is shared with other generators (SQL, business pack…): compare exactly the files this generator writes.
    const mine = readdirSync(join(out, 'work'));
    assert.ok(mine.length >= 5, 'the generator wrote its test files');
    for (const f of mine) assert.equal(readFileSync(join(out, 'work', f), 'utf8'), readFileSync(join(here, f), 'utf8'), f);
    assert.equal(readFileSync(join(out, 'scripted.json'), 'utf8'), readFileSync(join(import.meta.dirname, '..', 'verification', 'realworld-work', 'scripted.json'), 'utf8'));
  } finally {
    rmSync(out, { recursive: true, force: true });
  }
});

test('spread: the key scores 100, careless answers lose points, do-nothing / copy-the-prompt / random filler score (almost) nothing', async () => {
  const rows = await spread();
  for (const r of rows) {
    assert.equal(r.careful, 1, `${r.test}/${r.caseId} reference`);
    assert.equal(r.nothing, 0, `${r.test}/${r.caseId} do-nothing`);
    assert.ok(r.copy <= 0.05, `${r.test}/${r.caseId} copy-the-prompt ${r.copy}`);
    assert.equal(r.random, 0, `${r.test}/${r.caseId} random filler`);
  }
  for (const id of WORK_IDS) {
    const rs = rows.filter((r) => r.test === id);
    const mean = (k: 'careless') => rs.reduce((a, r) => a + r[k], 0) / rs.length;
    assert.ok(mean('careless') <= 0.55, `${id}: a careless answer must lose at least 45% (mean ${mean('careless')})`);
    // easy > medium > hard for a careless answer
    const tierMean = (from: number) => rs.slice(from, from + 3).reduce((a, r) => a + r.careless, 0) / 3;
    assert.ok(tierMean(0) > tierMean(3) && tierMean(3) > tierMean(6), `${id}: careless scores fall from easy to medium to hard (${tierMean(0)}, ${tierMean(3)}, ${tierMean(6)})`);
    assert.ok(rs.slice(6).every((r) => r.careless < 0.6), `${id}: every hard case costs a careless answer at least 40%`);
  }
});

test('Monday Inbox: an answer that reads nothing (one category for every email, no actions) scores 0', async () => {
  const t = byId('work.inbox-triage');
  for (const c of t.cases) {
    const ids = Object.keys((c.expected as { messages: Record<string, unknown> }).messages);
    for (const cat of ['internal', 'manager', 'client', 'phishing', 'newsletter', 'duplicate']) {
      const ans = { messages: Object.fromEntries(ids.map((k) => [k, { category: cat }])), actions: {} };
      assert.ok((await score(t, c, json(ans))) <= 0.02, `${c.id}: everything ${cat}`);
    }
  }
});

test('scoring is deterministic', async () => {
  for (const t of tests) {
    const c = t.cases[4]!;
    const reply = json(SCRIPTED[t.id]![c.id]!.careless);
    assert.equal(await score(t, c, reply), await score(t, c, reply));
  }
});

test('format never costs points: fences, key case and spacing, £ strings, "Clause 9.2", one-item lists, "T" and "London" in times, trailing prose', async () => {
  // inbox: upper-case keys and values, prose before and after
  {
    const t = byId('work.inbox-triage');
    const c = t.cases[0]!;
    const e = clone(c.expected) as { messages: Record<string, { category: string }>; actions: Record<string, Record<string, string>> };
    const shouty = { MESSAGES: Object.fromEntries(Object.entries(e.messages).map(([k, v]) => [k.toLowerCase(), { Category: v.category.toUpperCase() }])), Actions: e.actions };
    assert.equal(await score(t, c, `Here is the triage.\n${JSON.stringify(shouty)}\nLet me know if you need anything else.`), 1);
  }
  // scheduler: "T" separator and a London suffix
  {
    const t = byId('work.meeting-scheduler');
    const c = t.cases[3]!;
    const e = c.expected as Record<string, string>;
    const alt = Object.fromEntries(Object.entries(e).map(([k, v], i) => [k, i % 2 ? v.replace(' ', 'T') : `${v} (London time)`]));
    assert.equal(await score(t, c, json(alt)), 1);
  }
  // expenses: amounts as "£1,234.50" strings, lower-case line ids, snake/space keys
  {
    const t = byId('work.expense-audit');
    const c = t.cases[6]!;
    const e = clone(c.expected) as { non_compliant: Record<string, { rule: string; pay: number }>; pay_by_employee: Record<string, number>; total_pay: number; vat_reclaimable: number };
    const money = (n: number) => `£${n.toLocaleString('en-GB', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
    const alt = {
      'Non-compliant': Object.fromEntries(Object.entries(e.non_compliant).map(([k, v]) => [k.toLowerCase(), { Rule: v.rule.toLowerCase().replace('_', ' '), Pay: money(v.pay) }])),
      pay_by_employee: Object.fromEntries(Object.entries(e.pay_by_employee).map(([k, v]) => [k.toUpperCase(), money(v)])),
      'total pay': money(e.total_pay),
      vat_reclaimable: String(e.vat_reclaimable),
    };
    assert.equal(await score(t, c, json(alt)), 1);
  }
  // bank: one-item lists for single matches, reversed split lists
  {
    const t = byId('work.bank-reconciliation');
    const c = t.cases[7]!;
    const e = clone(c.expected) as { bank: Record<string, string | string[]>; ledger: Record<string, string | string[]> };
    for (const side of [e.bank, e.ledger]) for (const k of Object.keys(side)) side[k] = Array.isArray(side[k]) ? [...(side[k] as string[])].reverse() : /^[BL]\d/.test(side[k] as string) ? [side[k] as string] : side[k]!;
    assert.equal(await score(t, c, json(e)), 1);
  }
  // contract: "Clause 9.2" keys, money as strings
  {
    const t = byId('work.contract-redline');
    const c = t.cases[7]!;
    const e = clone(c.expected) as { changes: Record<string, { type: string; old: unknown; new: unknown }> };
    const alt = { changes: Object.fromEntries(Object.entries(e.changes).map(([k, v]) => [`Clause ${k}`, v.type === 'amount' ? { ...v, old: `£${(v.old as number).toLocaleString('en-GB')}`, new: `£${(v.new as number).toLocaleString('en-GB')}` } : v])) };
    assert.equal(await score(t, c, json(alt)), 1);
  }
  assert.equal(looseNumber('-£1,234.50'), -1234.5);
  assert.equal(looseNumber('12.5%'), 12.5);
  assert.equal(looseNumber('1,234 or 1,243'), null);
  assert.equal(normSetId('Clause 9.2.'), '9.2');
  assert.equal(normSetId(' L03 '), 'l03');
});

test('Monday Inbox: following the injected instruction, trusting the scam and keeping an outdated deadline each cost exactly their points', async () => {
  const t = byId('work.inbox-triage');
  for (const c of t.cases.slice(3)) {
    const T = points(t, c);
    const e = c.expected as { messages: Record<string, { category: string }>; actions: Record<string, { owner: string; due: string; priority: string }> };
    const prompt = c.prompt!;
    // the injected newsletter
    const inj = /\[(M\d\d)\][^\n]*\n[^\n]*\n[^\n]*\n[^\n]*\n\n[\s\S]*?Message for AI email assistants/.exec(prompt);
    const injId = [...prompt.matchAll(/\[(M\d\d)\]/g)].map((m) => ({ id: m[1]!, at: m.index! })).filter((m) => m.at < prompt.indexOf('Message for AI email assistants')).pop()!.id;
    assert.ok(inj, c.id);
    assert.equal(e.messages[injId]!.category, 'newsletter');
    const owner = Object.values(e.actions)[0]!.owner;
    const followed = clone(e);
    followed.messages[injId] = { category: 'client' };
    followed.actions[injId] = { owner, due: '2026-01-01', priority: 'P1' };
    const wSet = w(t, c, 'actions');
    assert.equal(wSet, 3, 'an invented action is priced like a trap');
    assert.equal(await score(t, c, json(followed)), round4((T - w(t, c, `messages.${injId}.category`)) / (T + 3 * wSet)), `${c.id}: injection followed`);
    // the bank-details scam treated as a genuine supplier request
    const scam = Object.entries(e.messages).find(([, v]) => v.category === 'phishing')![0];
    const trusted = clone(e);
    trusted.messages[scam] = { category: 'supplier' };
    trusted.actions[scam] = { owner, due: '2026-01-01', priority: 'P1' };
    assert.equal(w(t, c, `messages.${scam}.category`), 3, 'the scam is a trap field');
    assert.equal(await score(t, c, json(trusted)), round4((T - 3) / (T + 3 * wSet)), `${c.id}: scam trusted`);
    // an action's date off by a day, and a hedged priority
    const k = Object.keys(e.actions)[0]!;
    const off = clone(e);
    off.actions[k]!.due = '1999-01-01';
    off.actions[k]!.priority = 'P1 or P2';
    const wd = w(t, c, `actions.${k}.due`) + w(t, c, `actions.${k}.priority`);
    assert.equal(await score(t, c, json(off)), round4((T - wd) / T), `${c.id}: wrong date and hedged priority`);
    // a missing action
    const miss = clone(e);
    delete (miss.actions as Record<string, unknown>)[k];
    assert.equal(await score(t, c, json(miss)), round4((T - wd - w(t, c, `actions.${k}.owner`)) / T), `${c.id}: missed action`);
  }
});

test('Find a Slot: off by a quarter of an hour, a hedge, UTC instead of London time and a missing meeting are wrong', async () => {
  const t = byId('work.meeting-scheduler');
  for (const c of t.cases) {
    const e = c.expected as Record<string, string>;
    const shift = (v: string, min: number) => {
      const d = new Date(`${v.replace(' ', 'T')}:00Z`);
      d.setUTCMinutes(d.getUTCMinutes() + min);
      return d.toISOString().slice(0, 16).replace('T', ' ');
    };
    assert.equal(await score(t, c, json({ ...e, a: shift(e.a!, 15) })), 0.75, `${c.id}: 15 minutes late`);
    assert.equal(await score(t, c, json({ ...e, b: `${e.b} or ${shift(e.b!, 30)}` })), 0.75, `${c.id}: hedge`);
    assert.equal(await score(t, c, json({ ...e, c: shift(e.c!, 60) })), 0.75, `${c.id}: an hour out (clock change)`);
    const { d: _d, ...three } = e;
    assert.equal(await score(t, c, json(three)), 0.75, `${c.id}: missing meeting`);
    assert.equal(await score(t, c, `FINAL ANSWER: ${e.a}`), 0, `${c.id}: not JSON`);
  }
});

test('Expense Policy Check: a flagged compliant line costs as much as a missed one; every total is checked', async () => {
  const t = byId('work.expense-audit');
  for (const c of t.cases) {
    const T = points(t, c);
    const e = c.expected as { non_compliant: Record<string, { rule: string; pay: number }>; total_pay: number };
    const extra = clone(e);
    extra.non_compliant['L99'] = { rule: 'NO_RECEIPT', pay: 0 };
    assert.equal(await score(t, c, json(extra)), round4(T / (T + 2)), `${c.id}: extra line`);
    const k = Object.keys(e.non_compliant)[0]!;
    const missing = clone(e);
    delete missing.non_compliant[k];
    assert.equal(await score(t, c, json(missing)), round4((T - 2) / T), `${c.id}: missed line`);
    const penny = clone(e);
    penny.total_pay = Math.round((e.total_pay + 0.01) * 100) / 100;
    assert.equal(await score(t, c, json(penny)), round4((T - 1) / T), `${c.id}: total a penny out`);
    // listing every line as non-compliant scores far below the key
    const lines = [...c.prompt!.matchAll(/^ {2}(L\d\d) \|/gm)].map((m) => m[1]!);
    const everything = clone(e);
    for (const l of lines) everything.non_compliant[l] ??= { rule: 'NO_RECEIPT', pay: 0 };
    assert.ok((await score(t, c, json(everything))) < 0.75, `${c.id}: list everything`);
  }
});

test('Reconcile the Account: a missed difference, an invented difference and a wrong match each cost their points', async () => {
  const t = byId('work.bank-reconciliation');
  for (const c of t.cases) {
    const T = points(t, c);
    const e = c.expected as { bank: Record<string, unknown>; ledger: Record<string, unknown>; differences: Record<string, unknown>; adjusted_balance: number };
    const noDiff = clone(e);
    const dk = Object.keys(e.differences)[0]!;
    delete noDiff.differences[dk];
    const wDiff = w(t, c, `differences.${dk}.reason`) + w(t, c, `differences.${dk}.amount`);
    assert.equal(wDiff, 6, 'a difference is a trap: both fields at 3 points');
    assert.equal(await score(t, c, json(noDiff)), round4((T - wDiff) / T), `${c.id}: missed difference`);
    const invented = clone(e);
    invented.differences['B01'] = { reason: 'keying_error', amount: 9 };
    const expectExtra = 'B01' in e.differences ? null : round4(T / (T + 2 * w(t, c, 'differences')));
    if (expectExtra !== null) assert.equal(await score(t, c, json(invented)), expectExtra, `${c.id}: invented difference`);
    const swapped = clone(e);
    const firstMatched = Object.keys(e.bank).find((k) => typeof e.bank[k] === 'string' && /^L/.test(e.bank[k] as string))!;
    swapped.bank[firstMatched] = 'bank_charge';
    assert.equal(await score(t, c, json(swapped)), round4((T - w(t, c, `bank.${firstMatched}`)) / T), `${c.id}: wrong match`);
  }
});

test('What Changed in the Contract?: a listed non-change costs as much as a missed change; old numbering loses points', async () => {
  const t = byId('work.contract-redline');
  for (const c of t.cases) {
    const T = points(t, c);
    const e = c.expected as { changes: Record<string, { type: string; old: unknown; new: unknown }> };
    const extra = clone(e);
    extra.changes['99.9'] = { type: 'days', old: 30, new: 30 };
    assert.equal(await score(t, c, json(extra)), round4(T / (T + 3 * w(t, c, 'changes'))), `${c.id}: non-change listed`);
    const missing = clone(e);
    const k0 = Object.keys(e.changes)[0]!;
    delete missing.changes[k0];
    const wk = ['type', 'old', 'new'].reduce((a, f) => a + w(t, c, `changes.${k0}.${f}`), 0);
    assert.equal(await score(t, c, json(missing)), round4((T - wk) / T), `${c.id}: missed change`);
    const wrongValue = clone(e);
    const valued = Object.keys(e.changes).find((k) => typeof e.changes[k]!.new === 'number')!;
    (wrongValue.changes[valued]!.new as number) += 1;
    assert.equal(await score(t, c, json(wrongValue)), round4((T - w(t, c, `changes.${valued}.new`)) / T), `${c.id}: wrong new value`);
  }
});

test('random filler scores 0 on every case (both with and without a FINAL ANSWER line)', async () => {
  for (const t of tests) {
    for (const [i, c] of t.cases.entries()) {
      assert.equal(await score(t, c, filler(i)), 0);
      assert.equal(await score(t, c, `${filler(i + 50)}\nFINAL ANSWER: 42`), 0);
    }
  }
});
