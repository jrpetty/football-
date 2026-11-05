/**
 * DEMO data (?mock=1) for the people-and-money Business tests: a realistic answer for every case of the Business
 * demo run, built from the REAL answer key with the mistakes weaker models really make (counting overlapping CV
 * months twice, paying the "total now due on account" instead of the invoice, approving an invoice the policy
 * holds, missing a bureau's planted payslip error, matching a keyed-wrong payment as unmatched, ordering a case
 * too few). How many slips an answer gets follows the demo's invented score for that model and case
 * (areaDemo.ts); the answer is then marked by the REAL scorer (the json comparer, or the reorder checker), and the
 * demo result takes that score, so the picture and the number always agree. Demo data — not real results.
 */
import type { CaseResult, CaseResultLite, PromptTest, ScoreBreakdownItem, TestDefinition } from '../types.ts';
import { compareJson, forbiddenOverlap } from '../../../src/scoring/json-compare.ts';
import { scoreVerifier } from '../../../src/scoring/verifier.ts';
import { orderCost, type ReorderWorld } from '../../../src/scoring/verifiers/reorder.ts';
import { applyRubric, shortlistQuestion } from '../business/docs/people-money/model/cv.ts';

export const PEOPLE_MONEY_DEMO_NOTE = 'Demo data — not real results: a scripted answer built from the answer key, marked by the real scorer.';

const TESTS = new Set(['work.cv-read', 'work.cv-shortlist', 'work.invoice-read', 'work.invoice-matching', 'work.delivery-note', 'work.payroll-check', 'work.expense-audit', 'work.bank-reconciliation', 'work.board-report', 'work.stock-reorder']);

export function isPeopleMoneyDemoTest(testId: string | undefined): boolean {
  return !!testId && TESTS.has(testId);
}

type Json = null | boolean | number | string | Json[] | { [k: string]: Json };
type O = Record<string, Json>;

// ───────────────────────────── deterministic choices ─────────────────────────────

function hash(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 16777619);
  return h >>> 0;
}

function rng(seed: string): () => number {
  let a = hash(seed);
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const pickOf = <T>(r: () => number, xs: T[]): T => xs[Math.floor(r() * xs.length)]!;
const shuffle = <T>(r: () => number, xs: T[]): T[] =>
  xs
    .map((x) => ({ x, k: r() }))
    .sort((a, b) => a.k - b.k)
    .map((y) => y.x);
const r2 = (n: number) => Math.round(n * 100) / 100;
const clone = <T>(v: T): T => JSON.parse(JSON.stringify(v)) as T;

/** Slips for a target score: none at the top, more as the demo score falls. */
function slipsFor(target: number, max: number, r: () => number): number {
  if (target >= 0.97) return 0;
  const n = Math.round((1 - target) * max * (0.8 + r() * 0.5));
  return Math.max(1, Math.min(max, n));
}

function noteNumber(notes: string, re: RegExp): number | null {
  const m = notes.match(re);
  return m ? Number(m[1]!.replace(/,/g, '')) : null;
}

// ───────────────────────────── per test ─────────────────────────────

function cvRead(key: O, notes: string, prompt: string, n: number, r: () => number): O {
  const a = clone(key);
  const naive = noteNumber(notes, /naive sum without merging overlaps ([\d.]+)/);
  const slips: Array<() => boolean> = [
    () => {
      if (naive === null || naive === key.relevant_experience_months) return false;
      a.relevant_experience_months = naive;
      return true;
    },
    () => {
      if (key.right_to_work !== 'not stated') return false;
      a.right_to_work = 'yes';
      return true;
    },
    () => {
      if (!/promoted/i.test(prompt)) return false;
      const role = prompt.match(/\n(Payroll [^—\n]+?) — [^\n]+Present/)?.[1];
      if (!role) return false;
      a.current_role = role.trim();
      return true;
    },
    () => {
      const m = prompt.match(/Notice period: (\d+|one|two|three) (week|month)s?[^\n]*probation/i);
      if (!m) return false;
      a.notice_period = { amount: Number(m[1]) || 1, unit: `${m[2]}s` };
      return true;
    },
    () => {
      if (!/in progress|not completed/i.test(prompt)) return false;
      const up: Record<string, string> = { "Bachelor's degree": "Master's degree", "HND or foundation degree": "Bachelor's degree", 'A level': 'HND or foundation degree' };
      const v = up[String(key.highest_qualification)];
      if (!v) return false;
      a.highest_qualification = v;
      return true;
    },
    () => {
      const v = Number(key.longest_unbroken_relevant_months);
      a.longest_unbroken_relevant_months = v + pickOf(r, [-3, -1, 2, 4]);
      return true;
    },
  ];
  let done = 0;
  for (const s of slips) if (done < n && s()) done++;
  return a;
}

function cvShortlist(key: O, prompt: string, n: number, r: () => number, worst: boolean): O {
  const a = clone(key);
  const q = shortlistQuestion(prompt);
  const run = q ? applyRubric(q) : null;
  const short = (a.shortlist as string[]).slice();
  const scores = a.scores as Record<string, number>;
  const inj = (a.screener_instructions as string[]).slice();
  if (worst && inj.length) {
    // Obeys a hidden instruction: the injector goes to the top, the last real pick drops off.
    const lost = short.pop()!;
    delete scores[lost];
    short.unshift(inj[0]!);
    scores[inj[0]!] = 50;
    a.shortlist = short;
    a.screener_instructions = inj.slice(1);
    return a;
  }
  let done = 0;
  if (done < n && run) {
    // An attractive candidate who fails a must-have (a visa sponsor needed, a qualification in progress…).
    const unq = run.rows.filter((x) => !x.eligible && !x.injected).sort((x, y) => y.score - x.score)[0];
    if (unq) {
      const lost = short[short.length - 1 - Math.floor(r() * Math.min(2, short.length))]!;
      short.splice(short.indexOf(lost), 1, unq.id);
      delete scores[lost];
      scores[unq.id] = unq.score;
      done++;
    }
  }
  if (done < n && inj.length) {
    a.screener_instructions = [];
    done++;
  }
  if (done < n) {
    const id = pickOf(r, short.filter((x) => (key.shortlist as string[]).includes(x)));
    if (id) {
      scores[id] = (scores[id] ?? 0) + pickOf(r, [-4, -2, 3, 5]);
      done++;
    }
  }
  a.shortlist = short;
  a.scores = scores;
  return a;
}

function invoiceRead(key: O, notes: string, n: number, r: () => number): O {
  const a = clone(key);
  const due = notes.match(/total now due on account ([\d.]+)/i)?.[1];
  const printed = notes.match(/printed totals net ([\d.]+) vat ([\d.]+) gross ([\d.]+)/);
  const amended = /amended totals/.test(notes);
  const custVat = notes.match(/decoy: customer VAT (GB\d+)/)?.[1];
  const oldBank = notes.match(/decoy: old bank ([\d-]+) (\d+)/);
  const dn = notes.match(/delivery note (DN-\d+)/)?.[1];
  const us = notes.match(/US date (\d\d)\/(\d\d)\/(\d{4})/);
  const slips: Array<() => boolean> = [
    () => {
      if (!amended || !printed) return false;
      a.net = Number(printed[1]);
      a.vat = Number(printed[2]);
      a.gross = Number(printed[3]);
      return true;
    },
    () => {
      if (!due) return false;
      a.gross = Number(due);
      return true;
    },
    () => {
      if (!custVat) return false;
      a.supplier_vat_number = custVat;
      return true;
    },
    () => {
      if (!oldBank) return false;
      a.sort_code = oldBank[1]!;
      a.account_number = oldBank[2]!;
      a.bank_details_changed = false;
      return true;
    },
    () => {
      if (!us) return false;
      a.invoice_date = `${us[3]}-${us[2]}-${us[1]}`;
      return true;
    },
    () => {
      if (!dn) return false;
      a.invoice_number = dn;
      return true;
    },
    () => {
      a.due_date = shiftDate(String(key.due_date), pickOf(r, [-1, 1, 30]));
      return true;
    },
  ];
  let done = 0;
  for (const s of shuffle(r, slips.slice(0, 2)).concat(slips.slice(2))) if (done < n && s()) done++;
  return a;
}

function shiftDate(s: string, days: number): string {
  const d = new Date(`${s}T12:00:00Z`);
  if (Number.isNaN(d.getTime())) return s;
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** The invoice total printed on one inbox document ("Invoice total 106.80", "Total £734.40", "TOTAL DUE 274.39"). */
function docTotal(prompt: string, doc: string): number | null {
  const block = prompt.split(`[${doc}]`)[1]?.split(/\n\[DOC-|\nEMAILS RECEIVED/)[0] ?? '';
  const m = block.match(/(?:Invoice total|Credit total|Total|TOTAL DUE|TOTAL)\s*:?\s*-?£?\s*(-?[\d,]+\.\d\d)/i);
  return m ? Number(m[1]!.replace(/,/g, '')) : null;
}

function invoiceMatching(key: O, prompt: string, n: number, r: () => number): O {
  const a = clone(key);
  const docs = a.documents as Record<string, O>;
  const ids = shuffle(r, Object.keys(docs));
  const run = prompt.match(/before (?:Thursday|Friday|Wednesday|Tuesday|Monday) (\d+ \w+ \d{4})'s payment run/)?.[1];
  let total = Number(key.total_payable_this_run);
  let done = 0;
  for (const id of ids) {
    if (done >= n) break;
    const d = docs[id]!;
    if (String(d.outcome).startsWith('hold') || String(d.outcome).startsWith('reject')) {
      const t = docTotal(prompt, id);
      if (t === null || t <= 0) continue;
      // Waves it through: the price, quantity or duplicate check missed.
      d.outcome = 'approve';
      d.amount_to_pay = t;
      d.pay_by = run ? new Date(`${run} 12:00 UTC`).toISOString().slice(0, 10) : '2026-11-05';
      total += t;
      done++;
    } else if (d.outcome === 'approve' && Number(d.amount_to_pay) > 0 && r() < 0.5) {
      // Over-cautious: holds a good invoice for a price "difference" inside the tolerance.
      total -= Number(d.amount_to_pay);
      d.outcome = 'hold-price';
      d.amount_to_pay = 0;
      d.pay_by = null;
      done++;
    }
  }
  a.total_payable_this_run = r2(total);
  return a;
}

function deliveryNote(key: O, notes: string, n: number, r: () => number): O {
  const a = clone(key);
  const lines = a.lines as Record<string, O>;
  let done = 0;
  for (const [sku, l] of shuffle(r, Object.entries(lines))) {
    if (done >= n) break;
    const claimed = notes.match(new RegExp(`${sku}: ordered (\\d+), received (\\d+) \\(note claimed (\\d+)\\)`));
    if (claimed) {
      l.received = Number(claimed[3]);
      done++;
    } else if (Number(l.damaged) > 0) {
      l.damaged = 0;
      done++;
    } else if (l.substitute) {
      l.received = Number(l.received) + Number(l.short);
      l.short = 0;
      done++;
    } else if (Number(l.short) > 0 && r() < 0.6) {
      l.short = 0;
      l.received = Number(l.received) + 1;
      done++;
    }
  }
  if (done < n && (a.not_ordered as string[]).length) a.not_ordered = [];
  return a;
}

function payrollCheck(key: O, prompt: string, n: number, r: () => number): O {
  const a = clone(key);
  const wrong = (a.wrong_payslips as string[]).slice();
  const corr = a.corrected as Record<string, O>;
  let done = 0;
  const bureauNi = prompt.match(/Bureau summary: employer NI for the month £([\d,]+\.\d\d)/)?.[1];
  if (done < n && bureauNi) {
    a.employer_ni_total = Number(bureauNi.replace(/,/g, ''));
    done++;
  }
  for (const id of shuffle(r, wrong)) {
    if (done >= n) break;
    if (r() < 0.5 && wrong.length > 1) {
      // Missed: the bureau's draft accepted as it stands.
      a.wrong_payslips = (a.wrong_payslips as string[]).filter((x) => x !== id);
      delete corr[id];
    } else {
      const c = corr[id]!;
      const d = pickOf(r, [0.2, 1, 2.4, 7.6, -1.6]);
      c.tax = r2(Number(c.tax) + d);
      c.net = r2(Number(c.net) - d);
    }
    done++;
  }
  return a;
}

function expenseAudit(key: O, n: number, r: () => number): O {
  const a = clone(key);
  const nc = a.non_compliant as Record<string, O>;
  const pay = a.pay_by_employee as Record<string, number>;
  const ids = shuffle(r, Object.keys(nc));
  let done = 0;
  let delta = 0;
  for (const id of ids) {
    if (done >= n) break;
    const l = nc[id]!;
    const rule = String(l.rule);
    if (rule === 'LATE' || rule === 'DUPLICATE') {
      // Missed: the late or duplicate line is paid in full (left off the list).
      delete nc[id];
    } else if (rule === 'MEAL_CAP' || rule === 'ALCOHOL') {
      const d = pickOf(r, [2.5, 4.8, -3.2, 9.5]);
      l.pay = r2(Math.max(0, Number(l.pay) + d));
      delta += d;
    } else if (rule === 'MILEAGE_RATE') {
      const d = pickOf(r, [12.4, 25, -8.2]);
      l.pay = r2(Number(l.pay) + d);
      delta += d;
    } else {
      l.rule = pickOf(r, ['MEAL_CAP', 'NO_RECEIPT', 'HOTEL_CAP'].filter((x) => x !== rule));
    }
    done++;
  }
  if (delta) {
    const who = Object.keys(pay)[0]!;
    pay[who] = r2(pay[who]! + delta);
    a.total_pay = r2(Number(a.total_pay) + delta);
  }
  if (done < n) a.vat_reclaimable = r2(Number(a.vat_reclaimable) + pickOf(r, [3.4, -2.1, 6.75]));
  return a;
}

function bankRec(key: O, prompt: string, n: number, r: () => number): O {
  const a = clone(key);
  const bank = a.bank as Record<string, Json>;
  const ledger = a.ledger as Record<string, Json>;
  const diffs = a.differences as Record<string, O>;
  let done = 0;
  for (const b of shuffle(r, Object.keys(diffs))) {
    if (done >= n) break;
    // A keying error not recognised: the bank line called a charge, the cash-book entry left unmatched.
    const l = bank[b];
    if (typeof l !== 'string' || !ledger[l]) continue;
    bank[b] = diffs[b]!.reason === 'fx_difference' ? 'bank_charge' : 'unrecorded_direct_debit';
    ledger[l] = 'unpresented_cheque';
    delete diffs[b];
    done++;
  }
  for (const [id, v] of shuffle(r, Object.entries(ledger))) {
    if (done >= n) break;
    if (v === 'deposit_in_transit') {
      ledger[id] = 'unpresented_cheque';
      done++;
    } else if (v === 'duplicate_entry') {
      ledger[id] = 'unpresented_cheque';
      done++;
    }
  }
  if (done < n) {
    const close = prompt.match(/Cash-book closing balance £([\d,]+\.\d\d)/)?.[1];
    if (close) a.adjusted_balance = Number(close.replace(/,/g, ''));
    done++;
  }
  return a;
}

function boardReport(key: O, n: number, r: () => number): O {
  const a = clone(key);
  const keys = shuffle(r, Object.keys(a));
  for (const k of keys.slice(0, n)) {
    const v = Number(a[k]);
    if (k === 'orders' || k === 'active_customers') a[k] = v + pickOf(r, [1, 2, -1]);
    else if (/pct/.test(k)) a[k] = Math.round((v + pickOf(r, [0.4, -0.7, 1.6, 3.2])) * 10) / 10;
    else if (k === 'average_fte') a[k] = r2(v + pickOf(r, [0.33, -0.25, 0.5]));
    else if (k === 'revenue_per_fte') a[k] = Math.round(v * pickOf(r, [1.04, 0.95, 1.12]));
    else a[k] = r2(v * pickOf(r, [1.2, 1.0186, 0.9732, 1.035]));
  }
  return a;
}

interface ReorderSkuLite {
  id: string;
  caseSize: number;
  moq: number;
}

function stockReorder(key: O, n: number, r: () => number): { order: Record<string, number>; total: number } {
  const ref = key.reference as { order: Record<string, number>; total: number };
  const order = { ...ref.order };
  const skus = ((key.world as O).skus as unknown as ReorderSkuLite[]) ?? [];
  if (n <= 0) return { order, total: ref.total };
  const ordered = shuffle(
    r,
    skus.filter((s) => (order[s.id] ?? 0) > 0),
  );
  const s = ordered[0];
  if (s && n >= 3 && (order[s.id] ?? 0) - s.caseSize >= s.moq) {
    // One case too few: the item runs below its safety stock (the order breaks a rule).
    order[s.id] = order[s.id]! - s.caseSize;
    return { order, total: r2(ref.total - 0) };
  }
  if (s) {
    // One case more than needed: workable but dearer (and priced as ordered).
    order[s.id] = (order[s.id] ?? 0) + s.caseSize;
  }
  return { order, total: r2(orderCost(key.world as unknown as ReorderWorld, order).total / 100) };
}

// ───────────────────────────── marking with the real scorers ─────────────────────────────

interface Built {
  reply: string;
  score: number;
  passed: boolean;
  summary: string;
  items: ScoreBreakdownItem[];
  extracted: string;
}

const cache = new Map<string, Built>();
/** The area demo's invented score of each result before the real scorer re-marked it (run|key → target). */
const TARGETS = new Map<string, number>();

function jsonOpts(s: Record<string, unknown>) {
  return { unorderedArrays: s.unorderedArrays as boolean | undefined, numberTolerance: s.numberTolerance as number | undefined, aliases: s.aliases as Record<string, string> | undefined, lenient: s.lenient as boolean | undefined, sets: s.sets as Record<string, number> | undefined, partitions: s.partitions as string[] | undefined, weights: s.weights as Record<string, number> | undefined, aboveConstant: s.aboveConstant as string[] | undefined };
}

const INTRO: Record<string, string> = {
  'work.cv-read': 'I read the CV against each rule in turn.',
  'work.cv-shortlist': 'I applied the rubric to every CV: must-haves first, then the points.',
  'work.invoice-read': 'Here is the form filled in from the invoice.',
  'work.invoice-matching': 'I checked every document against FIN-04 in order.',
  'work.delivery-note': 'Counted in single units against the order.',
  'work.payroll-check': 'I recalculated every payslip from the records and this month’s changes.',
  'work.expense-audit': 'Every line checked against the policy, rule by rule.',
  'work.bank-reconciliation': 'Matched the statement against the cash book line by line.',
  'work.board-report': 'Worked through the memo’s definitions KPI by KPI.',
  'work.stock-reorder': 'Projected every item week by week and priced the cheapest order.',
};

/** The demo answer to one case for one model, marked by the real scorer (cached). */
export function peopleMoneyAnswer(t: PromptTest, caseId: string, contestantId: string, target: number): Built | null {
  const k = `${contestantId}|${t.id}|${caseId}|${target.toFixed(3)}`;
  const hit = cache.get(k);
  if (hit) return hit;
  const c = t.cases.find((x) => x.id === caseId);
  if (!c || !c.expected || typeof c.expected !== 'object') return null;
  const key = c.expected as O;
  const prompt = c.prompt ?? c.turns?.[0] ?? '';
  const notes = c.notes ?? '';
  const r = rng(k);
  const scorer = (c.scorer ?? t.scorer) as unknown as Record<string, unknown>;
  const worst = contestantId === 'helios-quill-flash' && t.id === 'work.cv-shortlist' && /v0[4-9]|v10/.test(caseId);
  let answer: unknown;
  switch (t.id) {
    case 'work.cv-read':
      answer = cvRead(key, notes, prompt, slipsFor(target, 4, r), r);
      break;
    case 'work.cv-shortlist':
      answer = cvShortlist(key, prompt, slipsFor(target, 3, r), r, worst);
      break;
    case 'work.invoice-read':
      answer = invoiceRead(key, notes, slipsFor(target, 4, r), r);
      break;
    case 'work.invoice-matching':
      answer = invoiceMatching(key, prompt, slipsFor(target, 4, r), r);
      break;
    case 'work.delivery-note':
      answer = deliveryNote(key, notes, slipsFor(target, 3, r), r);
      break;
    case 'work.payroll-check':
      answer = payrollCheck(key, prompt, slipsFor(target, 4, r), r);
      break;
    case 'work.expense-audit':
      answer = expenseAudit(key, slipsFor(target, 8, r), r);
      break;
    case 'work.bank-reconciliation':
      answer = bankRec(key, prompt, slipsFor(target, 4, r), r);
      break;
    case 'work.board-report':
      answer = boardReport(key, slipsFor(target, 6, r), r);
      break;
    case 'work.stock-reorder':
      answer = stockReorder(key, target >= 0.97 ? 0 : target >= 0.6 ? 1 : 3, r);
      break;
    default:
      return null;
  }
  const reply = `${INTRO[t.id] ?? 'Here is my answer.'}\n\n\`\`\`json\n${JSON.stringify(answer, null, 1)}\n\`\`\``;
  let built: Built;
  if (scorer.type === 'verifier') {
    const o = scoreVerifier(String(scorer.verifier), c.expected, reply, prompt);
    built = { reply, score: o.score, passed: o.passed, summary: o.summary, items: o.detail.items ?? [], extracted: JSON.stringify(answer).slice(0, 4000) };
  } else {
    const o = compareJson(c.expected, answer, jsonOpts(scorer));
    const banned = Array.isArray(scorer.forbidOverlap) ? forbiddenOverlap(c.expected, answer, scorer.forbidOverlap as Array<[string, string]>) : null;
    const score = banned ? 0 : Math.round(o.score * 10000) / 10000;
    const summary = banned ? `Listed ${banned.item} under "${banned.answerKey}", which the task rules out, so 0` : scorer.weights || scorer.aboveConstant ? `${o.matched}/${o.total} points` : `${o.matched}/${o.total} fields correct`;
    built = { reply, score, passed: !banned && o.matched === o.total, summary, items: o.items, extracted: JSON.stringify(answer).slice(0, 4000) };
  }
  cache.set(k, built);
  return built;
}

/** The demo run's result for a people-and-money case takes the real scorer's score of its scripted answer. */
export function decoratePeopleMoney(lite: CaseResultLite, t: TestDefinition | undefined): CaseResultLite {
  if (!t || t.kind !== 'prompt' || !isPeopleMoneyDemoTest(t.id) || lite.status !== 'ok' || typeof lite.score !== 'number') return lite;
  if (lite.scoreDetail?.integrity?.verdict === 'cheating') return lite;
  const b = peopleMoneyAnswer(t, lite.caseId, lite.contestantId, lite.score);
  if (!b) return lite;
  TARGETS.set(`${lite.runId}|${lite.key}`, lite.score);
  return { ...lite, score: b.score, passed: b.passed, summary: b.summary };
}

/** The full demo result: the scripted reply in the transcript and the real scorer's verdicts. */
export function peopleMoneyDetail(full: CaseResult, t: TestDefinition | undefined): CaseResult {
  if (!t || t.kind !== 'prompt' || !isPeopleMoneyDemoTest(t.id) || full.status !== 'ok') return full;
  if (full.scoreDetail?.integrity?.verdict === 'cheating') return full;
  const target = TARGETS.get(`${full.runId}|${full.key}`);
  if (target === undefined) return full;
  const b = peopleMoneyAnswer(t, full.caseId, full.contestantId, target);
  if (!b) return full;
  const c = t.cases.find((x) => x.id === full.caseId);
  const transcript = full.transcript.map((e, i) => (i === full.transcript.length - 1 && !e.judge ? { ...e, response: b.reply } : e));
  return { ...full, score: b.score, passed: b.passed, summary: b.summary, transcript, scoreDetail: { ...full.scoreDetail, formatOk: true, items: b.items, expected: c?.expected, extracted: b.extracted, notes: c?.notes ? `${c.notes}\n\n${PEOPLE_MONEY_DEMO_NOTE}` : PEOPLE_MONEY_DEMO_NOTE } };
}
