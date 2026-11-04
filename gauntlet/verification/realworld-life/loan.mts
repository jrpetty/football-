/**
 * Pay It Off Faster (life.loan-overpayment): loan schedule generator + exact month-by-month simulator.
 *
 * Every month: interest = balance × annual rate ÷ 12, rounded to the nearest penny (halves up), added to the
 * balance; then the payment (or what is left, if less), then any overpayment (capped at what is left). A payment
 * recalculated when a fix ends is the annuity payment over the remaining term, rounded UP to the next penny. During
 * the early-repayment-charge period, overpayments above 10% of the balance at the start of each loan year cost the
 * stated percentage of the excess (rounded to the nearest penny). Money is in whole pence throughout.
 */
import { Rng, money, pounds, ONE_ANSWER } from './common.mts';
import type { FieldSpec, FieldsWorld } from '../../src/scoring/verifiers/fields.ts';

const MONTH_LONG = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

export interface LoanSpec {
  id: string;
  difficulty: 'easy' | 'medium' | 'hard';
  seed: number;
  kind: 'car loan' | 'personal loan' | 'mortgage';
  intro: string;
  principal: number;
  /** First payment month. */
  startYear: number;
  startMonth: number;
  /** Term in months (for recalculation). */
  term: number;
  /** Annual rate (percent, 2 dp) from month k (1-based) onwards. */
  rates: Array<{ from: number; rate: number }>;
  /** The payment for month 1 onwards (pence). */
  payment: number;
  /** Months at whose start the payment is recalculated over the remaining term. */
  recalcAt?: number[];
  /** Overpayments: regular and one-off. */
  regular?: Array<{ from: number; to: number; amount: number }>;
  lumps?: Array<{ month: number; amount: number }>;
  /** Payment holiday months (no payment; interest still added). */
  holiday?: { from: number; to: number };
  /** ERC: applies to months 1..ercUntil; percent by loan year (1-based); free allowance 10% of balance at the start of each loan year. */
  erc?: { until: number; pctByYear: number[]; allowancePct: number };
  /** Late correction text and the world's real rates if it changes them. */
  late?: string;
  shownRates?: Array<{ from: number; rate: number }>;
}

interface RunResult {
  months: number;
  lastMonth: number;
  interest: number;
  erc: number;
}

function rateAt(rates: LoanSpec['rates'], k: number): number {
  let r = rates[0]!.rate;
  for (const x of rates) if (k >= x.from) r = x.rate;
  return r;
}

function halfUp(n: number): number {
  return Math.floor(n + 0.5 + 1e-9);
}

/** Annuity payment over n months at annual rate r%, rounded UP to the next penny. */
function annuity(balance: number, ratePct: number, n: number): number {
  const i = ratePct / 1200;
  const p = (balance * i) / (1 - Math.pow(1 + i, -n));
  const frac = p - Math.floor(p);
  if (frac < 1e-6 || frac > 1 - 1e-6) throw new Error('annuity payment is too close to a whole penny to be safe');
  return Math.ceil(p);
}

export function runLoan(s: LoanSpec, withOverpayments: boolean, rates = s.rates): RunResult {
  let bal = s.principal;
  let pay = s.payment;
  let interest = 0;
  let erc = 0;
  let k = 0;
  let yearStartBal = bal;
  let overThisYear = 0;
  while (bal > 0) {
    k++;
    if (k > 1000) throw new Error('loan never clears');
    if ((k - 1) % 12 === 0) {
      yearStartBal = bal;
      overThisYear = 0;
    }
    if (s.recalcAt?.includes(k)) pay = annuity(bal, rateAt(rates, k), s.term - (k - 1));
    const r = rateAt(rates, k);
    // Interest in exact integer arithmetic: bal (pence) × rate (hundredths of a percent) / (12 × 10000).
    const num = bal * Math.round(r * 100);
    const den = 120000;
    const intr = Math.floor((2 * num + den) / (2 * den));
    interest += intr;
    bal += intr;
    const onHoliday = s.holiday && k >= s.holiday.from && k <= s.holiday.to;
    if (!onHoliday) bal -= Math.min(pay, bal);
    if (withOverpayments && bal > 0) {
      let over = 0;
      for (const o of s.regular ?? []) if (k >= o.from && k <= o.to) over += o.amount;
      for (const l of s.lumps ?? []) if (l.month === k) over += l.amount;
      over = Math.min(over, bal);
      if (over > 0 && s.erc && k <= s.erc.until) {
        const allowance = halfUp((yearStartBal * s.erc.allowancePct) / 100);
        const before = Math.max(0, allowance - overThisYear);
        const excess = Math.max(0, over - before);
        const pct = s.erc.pctByYear[Math.floor((k - 1) / 12)] ?? 0;
        erc += halfUp((excess * pct) / 100);
        overThisYear += over;
      }
      bal -= over;
    }
  }
  return { months: k, lastMonth: k, interest, erc };
}

function monthOf(s: LoanSpec, k: number): { y: number; m: number } {
  const idx = s.startMonth - 1 + (k - 1);
  return { y: s.startYear + Math.floor(idx / 12), m: (idx % 12) + 1 };
}

function ym(s: LoanSpec, k: number): string {
  const { y, m } = monthOf(s, k);
  return `${y}-${String(m).padStart(2, '0')}`;
}

function monthName(s: LoanSpec, k: number): string {
  const { y, m } = monthOf(s, k);
  return `${MONTH_LONG[m - 1]} ${y}`;
}

const SPECS: LoanSpec[] = [
  {
    id: 'l01', difficulty: 'easy', seed: 1301, kind: 'car loan', principal: 900000, startYear: 2027, startMonth: 1, term: 36,
    intro: 'I took out a car loan of £9,000 at 7.90% a year. My first payment is in January 2027 and I pay £281.62 a month. I want to add £60 a month on top, from the very first month.',
    rates: [{ from: 1, rate: 7.9 }], payment: 28162, regular: [{ from: 1, to: 999, amount: 6000 }],
  },
  {
    id: 'l02', difficulty: 'easy', seed: 1302, kind: 'personal loan', principal: 650000, startYear: 2026, startMonth: 12, term: 48,
    intro: 'Personal loan for a new kitchen: £6,500 at 6.40% a year, first payment December 2026, £153.89 a month. I’ll overpay £100 every month from month 7 (when my pay rise starts), and put in a £500 bonus in month 12.',
    rates: [{ from: 1, rate: 6.4 }], payment: 15389, regular: [{ from: 7, to: 999, amount: 10000 }], lumps: [{ month: 12, amount: 50000 }],
  },
  {
    id: 'l03', difficulty: 'easy', seed: 1303, kind: 'personal loan', principal: 1200000, startYear: 2027, startMonth: 3, term: 60,
    intro: 'Consolidation loan of £12,000 at 9.90% a year, £254.37 a month, first payment March 2027. I’m going to pay an extra £150 a month from the start, and stop the extra after month 24 (the loan carries on at the normal payment).',
    rates: [{ from: 1, rate: 9.9 }], payment: 25437, regular: [{ from: 1, to: 24, amount: 15000 }],
  },
  {
    id: 'l04', difficulty: 'medium', seed: 1304, kind: 'mortgage', principal: 18500000, startYear: 2027, startMonth: 1, term: 300,
    intro: 'Mortgage: £185,000 over 25 years (300 months), first payment January 2027. A 2-year fix at 4.29% with a payment of £1,006.62 a month, then the lender’s standard variable rate of 7.49%, at which point the payment is recalculated. I’ll overpay £250 a month from the start, all the way through. Overpayments of up to 10% of the balance each loan year are free during the fix (I’m well inside that).',
    rates: [{ from: 1, rate: 4.29 }, { from: 25, rate: 7.49 }], payment: 100662, recalcAt: [25], regular: [{ from: 1, to: 9999, amount: 25000 }], erc: { until: 24, pctByYear: [3, 2], allowancePct: 10 },
  },
  {
    id: 'l05', difficulty: 'medium', seed: 1305, kind: 'mortgage', principal: 14250000, startYear: 2026, startMonth: 10, term: 240,
    intro: 'Mortgage of £142,500 over 20 years (240 months), first payment October 2026. 3-year fix at 3.95% with a payment of £859.13, then 6.99% with the payment recalculated over the remaining term. Every March during the fix I want to pay a £4,000 lump sum from my bonus; no other overpayments.',
    rates: [{ from: 1, rate: 3.95 }, { from: 37, rate: 6.99 }], payment: 85913, recalcAt: [37], lumps: [{ month: 6, amount: 400000 }, { month: 18, amount: 400000 }, { month: 30, amount: 400000 }], erc: { until: 36, pctByYear: [3, 2, 1], allowancePct: 10 },
  },
  {
    id: 'l06', difficulty: 'medium', seed: 1306, kind: 'mortgage', principal: 12000000, startYear: 2027, startMonth: 4, term: 180,
    intro: 'Remortgage of £120,000 over 15 years (180 months), first payment April 2027. 5-year fix at 3.89%, payment £880.15. After 5 years the rate goes to 6.74% and the payment is recalculated. Plan: overpay £400 a month from the start, plus a £15,000 inheritance in month 30. The fix has an early repayment charge: 3% in years 1–2, 2% in years 3–5, on any overpayment above 10% of the balance at the start of that loan year.',
    rates: [{ from: 1, rate: 3.89 }, { from: 61, rate: 6.74 }], payment: 88015, recalcAt: [61], regular: [{ from: 1, to: 9999, amount: 40000 }], lumps: [{ month: 30, amount: 1500000 }], erc: { until: 60, pctByYear: [3, 3, 2, 2, 2], allowancePct: 10 },
  },
  {
    id: 'l07', difficulty: 'hard', seed: 1307, kind: 'mortgage', principal: 22000000, startYear: 2026, startMonth: 11, term: 300,
    intro: 'Tracker mortgage: £220,000 over 25 years, first payment November 2026. The rate is the Bank Rate plus 1.10%. Bank Rate is 3.75% now; the lender has told us it will be 4.00% from month 5 and 3.50% from month 17. The payment is recalculated at the start of month 5 and month 17 when the rate changes (first payment £1,258.43). No early repayment charges on this one. We’ll overpay £300 a month from month 3 and £10,000 in month 12.',
    rates: [{ from: 1, rate: 4.85 }, { from: 5, rate: 5.1 }, { from: 17, rate: 4.6 }], payment: 125843, recalcAt: [5, 17], regular: [{ from: 3, to: 9999, amount: 30000 }], lumps: [{ month: 12, amount: 1000000 }],
    late: 'CORRECTION (lender’s letter): the second change is a cut to 3.25% Bank Rate, not 3.50%, still from month 17. So from month 17 the rate is 3.25% + 1.10%.',
    shownRates: [{ from: 1, rate: 4.85 }, { from: 5, rate: 5.1 }, { from: 17, rate: 4.6 }],
  },
  {
    id: 'l08', difficulty: 'hard', seed: 1308, kind: 'mortgage', principal: 16800000, startYear: 2027, startMonth: 2, term: 264,
    intro: 'Our mortgage: £168,000 over 22 years (264 months), first payment February 2027. 2-year fix at 4.59%, payment £1,002.47; after that 7.24% with the payment recalculated over the remaining term. We’re taking a 3-month payment holiday in months 8–10 (baby on the way): no payments, but interest is still added each month. From month 13 we’ll overpay £350 a month, and £9,000 in month 20. During the fix, overpayments above 10% of the balance at the start of each loan year cost 2%.',
    rates: [{ from: 1, rate: 4.59 }, { from: 25, rate: 7.24 }], payment: 100247, recalcAt: [25], holiday: { from: 8, to: 10 }, regular: [{ from: 13, to: 9999, amount: 35000 }], lumps: [{ month: 20, amount: 900000 }], erc: { until: 24, pctByYear: [2, 2], allowancePct: 10 },
  },
  {
    id: 'l09', difficulty: 'hard', seed: 1309, kind: 'mortgage', principal: 25500000, startYear: 2026, startMonth: 9, term: 360,
    intro: 'First-time buyers: £255,000 over 30 years (360 months), first payment September 2026. 5-year fix at 4.79%, payment £1,336.09, then 7.19% with the payment recalculated over the remaining term. ERC during the fix: 5% in year 1, 4% in year 2, 3% in year 3, 2% in year 4, 1% in year 5, on overpayments above 10% of the balance at the start of each loan year. Plan: £500 a month extra from month 1, plus lump sums of £20,000 in month 6, £12,000 in month 18 and £30,000 in month 49.',
    rates: [{ from: 1, rate: 4.79 }, { from: 61, rate: 7.19 }], payment: 133609, recalcAt: [61], regular: [{ from: 1, to: 9999, amount: 50000 }], lumps: [{ month: 6, amount: 2000000 }, { month: 18, amount: 1200000 }, { month: 49, amount: 3000000 }], erc: { until: 60, pctByYear: [5, 4, 3, 2, 1], allowancePct: 10 },
  },
  {
    id: 'l10', difficulty: 'hard', seed: 1310, kind: 'mortgage', principal: 19900000, startYear: 2027, startMonth: 1, term: 300,
    intro: 'Mortgage of £199,000 over 25 years, first payment January 2027. 3-year fix at 4.15%, payment £1,067.28, then two years at a 5.49% product (payment recalculated over the remaining term at the start of month 37), then the 7.29% standard rate (payment recalculated again at the start of month 61). ERC only in the 3-year fix: 3% on overpayments above 10% of the balance at the start of each loan year. We will overpay £600 a month in months 1–36 and £200 a month after that, and put in £25,000 in month 24.',
    rates: [{ from: 1, rate: 4.15 }, { from: 37, rate: 5.49 }, { from: 61, rate: 7.29 }], payment: 106728, recalcAt: [37, 61], regular: [{ from: 1, to: 36, amount: 60000 }, { from: 37, to: 9999, amount: 20000 }], lumps: [{ month: 24, amount: 2500000 }], erc: { until: 36, pctByYear: [3, 3, 3], allowancePct: 10 },
  },
];

const LATE_RATES: Record<string, LoanSpec['rates']> = { l07: [{ from: 1, rate: 4.85 }, { from: 5, rate: 5.1 }, { from: 17, rate: 4.35 }] };

export interface LoanCase {
  id: string;
  difficulty: 'easy' | 'medium' | 'hard';
  world: FieldsWorld;
  prompt: string;
  notes: string;
  reference: Record<string, unknown>;
  careless: Array<{ label: string; answer: Record<string, unknown> }>;
}

function answerFor(s: LoanSpec, rates: LoanSpec['rates'], ignoreErc = false): Record<string, unknown> {
  const withO = runLoan(ignoreErc ? { ...s, erc: undefined } : s, true, rates);
  const base = runLoan(s, false, rates);
  return {
    clearedMonth: ym(s, withO.lastMonth),
    months: withO.months,
    totalInterest: pounds(withO.interest),
    interestSaved: pounds(base.interest - withO.interest),
    ...(s.erc ? { earlyRepaymentCharges: pounds(withO.erc) } : {}),
  };
}

export function buildLoanCases(): LoanCase[] {
  return SPECS.map((s) => {
    void new Rng(s.seed);
    const rates = LATE_RATES[s.id] ?? s.rates;
    const withO = runLoan(s, true, rates);
    const base = runLoan(s, false, rates);
    const fields: FieldSpec[] = [
      { path: 'clearedMonth', aliases: ['cleared', 'finalmonth', 'lastpayment', 'paidoff', 'payoffmonth'], label: 'the month of the last payment', kind: 'month', value: ym(s, withO.lastMonth) },
      { path: 'months', aliases: ['numberofpayments', 'payments', 'monthstaken'], label: 'the number of monthly payments', kind: 'int', value: withO.months },
      { path: 'totalInterest', aliases: ['interest', 'interestpaid'], label: 'total interest paid (with the overpayments)', kind: 'money', value: withO.interest },
      { path: 'interestSaved', aliases: ['saved', 'saving', 'interestsaving'], label: 'interest saved compared with no overpayments', kind: 'money', value: base.interest - withO.interest },
    ];
    if (s.erc) fields.push({ path: 'earlyRepaymentCharges', aliases: ['erc', 'ercs', 'charges', 'earlyrepaymentcharge', 'penalties'], label: 'total early repayment charges', kind: 'money', value: withO.erc });
    const L: string[] = [s.intro, ''];
    L.push('THE RULES MY LENDER USES (and you must use)');
    L.push('- Every month, interest = the balance × the annual rate ÷ 12, rounded to the nearest penny (halves round up), and is added to the balance. Then the monthly payment is taken (or the whole balance, if that is less). Then any overpayment for that month is taken (never more than what is left).');
    L.push('- When the payment is recalculated at the start of a month, it becomes the fixed monthly payment that would clear the balance (before that month’s interest) over the months left in the original term at the new rate: payment = B × i ÷ (1 − (1 + i)^−n), with i = annual rate ÷ 1200 and n = months left including that month, rounded UP to the next penny. It then stays the same until the next recalculation.');
    if (s.erc)
      L.push('- Early repayment charges (ERC) apply only during the period stated. In each loan year (months 1–12, 13–24, …) the first 10% of the balance at the start of that loan year (rounded to the nearest penny) can be overpaid free; on every pound overpaid beyond that in the same loan year the ERC percentage for that year is charged (rounded to the nearest penny for each month’s overpayment). ERC is paid separately: it does not reduce the balance and it is not interest.');
    if (s.holiday) L.push('- In a payment-holiday month no payment is taken, but interest is still added.');
    L.push(`- The months are counted from the first payment (month 1 = ${monthName(s, 1)}).`);
    if (s.late) L.push('', s.late);
    L.push(
      '',
      `With the overpayments, in which month is the last payment made, how many monthly payments is that, how much interest is paid in total, and how much interest does that save compared with making no overpayments at all (same rates and recalculations, normal payments only)${s.erc ? '? And how much ERC is paid in total?' : '?'}`,
      'Answer in this JSON format (money in pounds to the penny; the month as YYYY-MM):',
      '```json',
      `{"clearedMonth": "YYYY-MM", "months": 0, "totalInterest": 0.00, "interestSaved": 0.00${s.erc ? ', "earlyRepaymentCharges": 0.00' : ''}}`,
      '```',
      ONE_ANSWER,
    );
    const careless: LoanCase['careless'] = [];
    if (s.erc) careless.push({ label: 'forgets the early repayment charges (and their cap on free overpayments)', answer: { ...answerFor(s, rates, true), earlyRepaymentCharges: 0 } });
    if (s.late) careless.push({ label: 'misses the late correction', answer: answerFor(s, s.shownRates ?? s.rates) });
    // Careless: no recalculation when the fix ends (keeps the old payment).
    if (s.recalcAt?.length) {
      try {
        careless.push({ label: 'does not recalculate the payment when the rate changes', answer: answerFor({ ...s, recalcAt: [] }, rates) });
      } catch {
        /* the old payment would never clear the loan at the new rate */
      }
    }
    return {
      id: s.id,
      difficulty: s.difficulty,
      world: { fields },
      prompt: L.join('\n'),
      reference: answerFor(s, rates),
      careless,
      notes: `[${s.difficulty}] Simulated month by month in whole pence. With overpayments: last payment ${monthName(s, withO.lastMonth)} (${withO.months} payments), interest ${money(withO.interest)}, ERC ${money(withO.erc)}. Without overpayments: ${base.months} payments, interest ${money(base.interest)}. Saved ${money(base.interest - withO.interest)}.`,
    };
  });
}

if (import.meta.url === `file://${process.argv[1]}`) {
  for (const c of buildLoanCases()) {
    console.log(c.id, c.notes);
    for (const k of c.careless) console.log('   careless', k.label, JSON.stringify(k.answer));
    if (process.argv.includes('--prompt')) console.log(c.prompt);
  }
}
