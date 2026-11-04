/**
 * Splitting the Bills (life.bill-split): flat-share generator + exact solver.
 *
 * Every share is an exact fraction (Q): rent and broadband are shared day by day (the month's charge ÷ days in the
 * month), rent by room size and broadband equally; energy bills day by day over their own bill period, equally;
 * Netflix per monthly charge among the subscribers living there on the 1st; council tax per day among the
 * non-students (25% off on a day with exactly one non-student, nothing when all are students); the boiler repair
 * (after the landlord's contribution) by the person who caused it. Balances are rounded to the penny at the very
 * end. The settle-up follows the stated method; the generator checks that it uses the fewest possible transfers
 * and that the rounded balances add up to exactly zero (otherwise the case is regenerated with the next seed).
 */
import { Q, Rng, money, pounds, ONE_ANSWER, FIRST_NAMES } from './common.mts';
import type { SettleWorld } from '../../src/scoring/verifiers/settle.ts';

const MONTH_DAYS = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31]; // 2026
const MONTH = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];
const WEEKDAY = ['Thu', 'Fri', 'Sat', 'Sun', 'Mon', 'Tue', 'Wed']; // 1 Jan 2026 is a Thursday

/** Day of 2026 (0 = 1 Jan) from month (1–12) and day. */
function doy(m: number, d: number): number {
  let n = 0;
  for (let i = 0; i < m - 1; i++) n += MONTH_DAYS[i]!;
  return n + d - 1;
}
function md(day: number): { m: number; d: number } {
  let m = 0;
  let d = day;
  while (d >= MONTH_DAYS[m]!) d -= MONTH_DAYS[m++]!;
  return { m: m + 1, d: d + 1 };
}
export function dateText(day: number): string {
  const { m, d } = md(day);
  return `${WEEKDAY[day % 7]} ${d} ${MONTH[m - 1]}`;
}

interface Person {
  name: string;
  room: number;
  from: number;
  to: number;
  student: boolean;
  netflix: boolean;
}

interface FlatSpec {
  id: string;
  difficulty: 'easy' | 'medium' | 'hard';
  seed: number;
  start: number;
  end: number;
  people: number;
  /** Move-in / move-out changes and students: decided per case. */
  setup: (rng: Rng, names: string[], s: { start: number; end: number }) => { people: Person[]; extra?: string[] };
  rent: { monthly: number; paidBy: number };
  broadband?: { monthly: number; paidBy: number };
  netflix?: { monthly: number; paidBy: number; rise?: { month: number; monthly: number } };
  councilTax?: { daily: number; paidBy: number };
  energy: Array<{ label: string; start: number; end: number; amount: number; paidBy: number }>;
  boiler?: { invoice: number; landlord: number; causedBy: number; paidBy: number };
  /** Transfers already made between flatmates: [from, to, pence, note]. */
  sent?: Array<[number, number, number, string]>;
  /** Notes added after the bills (corrections); `fix` edits the spec's real values. */
  late?: string;
  distractors?: string[];
}

interface Outcome {
  balances: Record<string, number>;
  transfers: SettleWorld['transfers'];
  shares: Record<string, Q>;
}

function residents(people: Person[], day: number): Person[] {
  return people.filter((p) => p.from <= day && day <= p.to);
}

/** The exact fair shares and payments, from the rules in the prompt. */
function compute(spec: FlatSpec, people: Person[], energy: FlatSpec['energy']): Outcome {
  const share = new Map<string, Q>(people.map((p) => [p.name, new Q(0)]));
  const paid = new Map<string, Q>(people.map((p) => [p.name, new Q(0)]));
  const add = (m: Map<string, Q>, k: string, q: Q) => m.set(k, m.get(k)!.add(q));
  const name = (i: number) => people[i]!.name;
  for (let day = spec.start; day <= spec.end; day++) {
    const here = residents(people, day);
    if (!here.length) throw new Error(`${spec.id}: nobody lives there on ${dateText(day)}`);
    const { m } = md(day);
    const dim = MONTH_DAYS[m - 1]!;
    // Rent by room size.
    const area = here.reduce((s, p) => s + p.room, 0);
    for (const p of here) add(share, p.name, new Q(spec.rent.monthly * p.room, dim * area));
    if (spec.broadband) for (const p of here) add(share, p.name, new Q(spec.broadband.monthly, dim * here.length));
    if (spec.councilTax) {
      const payers = here.filter((p) => !p.student);
      if (payers.length) {
        const daily = payers.length === 1 ? new Q(spec.councilTax.daily * 3, 4) : new Q(spec.councilTax.daily);
        for (const p of payers) add(share, p.name, daily.div(new Q(payers.length)));
        add(paid, name(spec.councilTax.paidBy), daily);
      }
    }
    if (spec.netflix && md(day).d === 1) {
      const subs = here.filter((p) => p.netflix);
      if (!subs.length) throw new Error(`${spec.id}: no Netflix subscriber on ${dateText(day)}`);
      const nf = spec.netflix.rise && md(day).m >= spec.netflix.rise.month ? spec.netflix.rise.monthly : spec.netflix.monthly;
      for (const p of subs) add(share, p.name, new Q(nf, subs.length));
      add(paid, name(spec.netflix.paidBy), new Q(nf));
    }
  }
  // Rent and broadband are paid monthly in full; the others' standing orders to the rent payer count as sent money.
  const orders = standingOrders(spec, people);
  for (let mo = md(spec.start).m; mo <= md(spec.end).m; mo++) {
    add(paid, name(spec.rent.paidBy), new Q(spec.rent.monthly));
    for (const pr of residents(people, doy(mo, 1))) {
      const amt = orders[pr.name];
      if (!amt) continue;
      add(paid, pr.name, new Q(amt));
      add(paid, name(spec.rent.paidBy), new Q(-amt));
    }
    if (spec.broadband) add(paid, name(spec.broadband.paidBy), new Q(spec.broadband.monthly));
  }
  for (const b of energy) {
    const days = b.end - b.start + 1;
    for (let day = b.start; day <= b.end; day++) {
      const here = residents(people, day);
      for (const p of here) add(share, p.name, new Q(b.amount, days * here.length));
    }
    add(paid, name(b.paidBy), new Q(b.amount));
  }
  if (spec.boiler) {
    const net = spec.boiler.invoice - spec.boiler.landlord;
    add(share, name(spec.boiler.causedBy), new Q(net));
    add(paid, name(spec.boiler.paidBy), new Q(net));
  }
  for (const [f, t, amt] of spec.sent ?? []) {
    add(paid, name(f), new Q(amt));
    add(paid, name(t), new Q(-amt));
  }
  const balances: Record<string, number> = {};
  for (const p of people) {
    const b = paid.get(p.name)!.sub(share.get(p.name)!);
    if (b.isHalfway()) throw new Error(`${spec.id}: ${p.name}'s balance ends in exactly half a penny`);
    balances[p.name] = Number(b.round());
  }
  const sum = Object.values(balances).reduce((s, x) => s + x, 0);
  if (sum !== 0) throw new Error(`${spec.id}: rounded balances add up to ${sum}p, not 0`);
  return { balances, transfers: settleUp(balances), shares: Object.fromEntries(share) };
}

/**
 * Monthly standing orders to the rent payer: each other flatmate pays their room's share of the rent among the
 * people living here at the start, rounded to the nearest £10 (later arrivals: among the people there when they
 * arrive). They are paid on the 1st of every month the person lives here on that day.
 */
function standingOrders(spec: FlatSpec, people: Person[]): Record<string, number> {
  const out: Record<string, number> = {};
  for (const pr of people) {
    if (pr.name === people[spec.rent.paidBy]!.name) continue;
    const at = residents(people, Math.max(pr.from, spec.start));
    const area = at.reduce((s2, x) => s2 + x.room, 0);
    out[pr.name] = Math.round((spec.rent.monthly * pr.room) / area / 1000) * 1000;
  }
  return out;
}

/** The stated method: the person who owes most pays the person owed most; ties alphabetical. */
export function settleUp(balances: Record<string, number>): SettleWorld['transfers'] {
  const b = { ...balances };
  const out: SettleWorld['transfers'] = [];
  for (;;) {
    const names = Object.keys(b).sort();
    const debtor = names.filter((n) => b[n]! < 0).sort((x, y) => b[x]! - b[y]! || x.localeCompare(y))[0];
    const creditor = names.filter((n) => b[n]! > 0).sort((x, y) => b[y]! - b[x]! || x.localeCompare(y))[0];
    if (!debtor || !creditor) break;
    const amt = Math.min(-b[debtor]!, b[creditor]!);
    out.push({ from: debtor, to: creditor, amount: amt });
    b[debtor]! += amt;
    b[creditor]! -= amt;
  }
  return out;
}

/** Fewest transfers that settle these balances: people − (most disjoint groups that each sum to zero). */
export function minTransfers(balances: Record<string, number>): number {
  const v = Object.values(balances).filter((x) => x !== 0);
  const n = v.length;
  const sums = new Array<number>(1 << n).fill(0);
  for (let m = 1; m < 1 << n; m++) {
    const low = m & -m;
    sums[m] = sums[m ^ low]! + v[Math.log2(low)]!;
  }
  const dp = new Array<number>(1 << n).fill(0);
  for (let m = 1; m < 1 << n; m++) {
    let best = 0;
    for (let i = 0; i < n; i++) if (m & (1 << i)) best = Math.max(best, dp[m ^ (1 << i)]!);
    dp[m] = best + (sums[m] === 0 ? 1 : 0);
  }
  return n - dp[(1 << n) - 1]!;
}

const p = (_rng: Rng, name: string, room: number, from: number, to: number, student = false, netflix = true): Person => ({ name, room, from, to, student, netflix });

const SPECS: FlatSpec[] = [
  {
    id: 'b01', difficulty: 'easy', seed: 901, start: doy(4, 1), end: doy(6, 30), people: 3,
    setup: (rng, n, s) => ({ people: [p(rng, n[0]!, 12, s.start, s.end), p(rng, n[1]!, 12, s.start, s.end), p(rng, n[2]!, 12, s.start, s.end)] }),
    rent: { monthly: 165000, paidBy: 0 },
    broadband: { monthly: 3600, paidBy: 1 },
    energy: [{ label: 'Electricity and gas, 1 Apr – 30 Jun', start: doy(4, 1), end: doy(6, 30), amount: 41870, paidBy: 2 }],
  },
  {
    id: 'b02', difficulty: 'easy', seed: 902, start: doy(1, 1), end: doy(3, 31), people: 3,
    setup: (rng, n, s) => ({ people: [p(rng, n[0]!, 16, s.start, s.end), p(rng, n[1]!, 11, s.start, s.end), p(rng, n[2]!, 9, s.start, s.end)] }),
    rent: { monthly: 189000, paidBy: 1 },
    netflix: { monthly: 1899, paidBy: 2 },
    energy: [{ label: 'Electricity and gas, 1 Jan – 31 Mar', start: doy(1, 1), end: doy(3, 31), amount: 63415, paidBy: 0 }],
  },
  {
    id: 'b03', difficulty: 'easy', seed: 903, start: doy(5, 1), end: doy(7, 31), people: 4,
    setup: (rng, n, s) => ({ people: [p(rng, n[0]!, 14, s.start, s.end), p(rng, n[1]!, 12, s.start, s.end), p(rng, n[2]!, 12, s.start, s.end), p(rng, n[3]!, 10, s.start, doy(6, 30))] }),
    rent: { monthly: 236000, paidBy: 0 },
    broadband: { monthly: 4200, paidBy: 2 },
    energy: [{ label: 'Electricity and gas, 1 May – 31 Jul', start: doy(5, 1), end: doy(7, 31), amount: 38220, paidBy: 1 }],
    sent: [[3, 0, 50000, 'a one-off payment before moving out']],
  },
  {
    id: 'b04', difficulty: 'medium', seed: 904, start: doy(1, 1), end: doy(6, 30), people: 4,
    setup: (rng, n, s) => ({
      people: [p(rng, n[0]!, 15, s.start, s.end, false, true), p(rng, n[1]!, 12, s.start, s.end, true, false), p(rng, n[2]!, 12, s.start, doy(3, 20), false, true), p(rng, n[3]!, 13, doy(3, 21), s.end, true, true)],
    }),
    rent: { monthly: 248000, paidBy: 0 },
    netflix: { monthly: 1299, paidBy: 3 },
    councilTax: { daily: 520, paidBy: 0 },
    energy: [
      { label: 'Electricity and gas, 1 Jan – 31 Mar', start: doy(1, 1), end: doy(3, 31), amount: 71240, paidBy: 2 },
      { label: 'Electricity and gas, 1 Apr – 30 Jun', start: doy(4, 1), end: doy(6, 30), amount: 40118, paidBy: 1 },
    ],
  },
  {
    id: 'b05', difficulty: 'medium', seed: 905, start: doy(3, 1), end: doy(8, 31), people: 4,
    setup: (rng, n, s) => ({
      people: [p(rng, n[0]!, 11, s.start, s.end, false, false), p(rng, n[1]!, 14, s.start, s.end, false, true), p(rng, n[2]!, 10, s.start, doy(5, 15), false, true), p(rng, n[3]!, 10, doy(5, 16), s.end, false, true)],
    }),
    rent: { monthly: 212000, paidBy: 1 },
    broadband: { monthly: 3900, paidBy: 0 },
    councilTax: { daily: 480, paidBy: 1 },
    energy: [
      { label: 'Electricity, 1 Mar – 31 May', start: doy(3, 1), end: doy(5, 31), amount: 28950, paidBy: 3 },
      { label: 'Electricity, 1 Jun – 31 Aug', start: doy(6, 1), end: doy(8, 31), amount: 19720, paidBy: 3 },
    ],
    boiler: { invoice: 31800, landlord: 18000, causedBy: 2, paidBy: 1 },
    distractors: ['(The £300 deposit each of us paid is held by the landlord and has nothing to do with this.)'],
  },
  {
    id: 'b06', difficulty: 'medium', seed: 906, start: doy(7, 1), end: doy(12, 31), people: 3,
    setup: (rng, n, s) => ({
      people: [p(rng, n[0]!, 13, s.start, s.end, true, true), p(rng, n[1]!, 13, s.start, doy(9, 30), false, true), p(rng, n[2]!, 9, s.start, s.end, true, false), p(rng, n[3]!, 13, doy(10, 1), s.end, false, true)],
    }),
    rent: { monthly: 171000, paidBy: 0 },
    netflix: { monthly: 1099, paidBy: 1 },
    councilTax: { daily: 560, paidBy: 2 },
    energy: [
      { label: 'Electricity and gas, 1 Jul – 30 Sep', start: doy(7, 1), end: doy(9, 30), amount: 22870, paidBy: 3 },
      { label: 'Electricity and gas, 1 Oct – 31 Dec', start: doy(10, 1), end: doy(12, 31), amount: 58430, paidBy: 0 },
    ],
    sent: [[1, 0, 85500, 'a one-off payment towards the bills'], [2, 0, 60000, 'a one-off payment towards the bills']],
  },
  {
    id: 'b07', difficulty: 'hard', seed: 907, start: doy(1, 1), end: doy(12, 31), people: 5,
    setup: (rng, n, s) => ({
      people: [
        p(rng, n[0]!, 16, s.start, s.end, false, true),
        p(rng, n[1]!, 12, s.start, doy(6, 30), true, false),
        p(rng, n[2]!, 12, s.start, s.end, true, true),
        p(rng, n[3]!, 10, doy(2, 14), s.end, false, false),
        p(rng, n[4]!, 12, doy(7, 1), s.end, false, true),
      ],
    }),
    rent: { monthly: 287500, paidBy: 0 },
    broadband: { monthly: 4500, paidBy: 2 },
    netflix: { monthly: 1899, paidBy: 0 },
    councilTax: { daily: 600, paidBy: 3 },
    energy: [
      { label: 'Electricity and gas, 1 Jan – 31 Mar', start: doy(1, 1), end: doy(3, 31), amount: 92310, paidBy: 2 },
      { label: 'Electricity and gas, 1 Apr – 30 Jun', start: doy(4, 1), end: doy(6, 30), amount: 41260, paidBy: 3 },
      { label: 'Electricity and gas, 1 Jul – 30 Sep', start: doy(7, 1), end: doy(9, 30), amount: 27340, paidBy: 4 },
      { label: 'Electricity and gas, 1 Oct – 31 Dec', start: doy(10, 1), end: doy(12, 31), amount: 80105, paidBy: 0 },
    ],
    boiler: { invoice: 42000, landlord: 25000, causedBy: 3, paidBy: 0 },
    sent: [[1, 0, 120000, 'a one-off payment towards the bills'], [4, 2, 4000, 'towards broadband']],
    late: 'CORRECTION from the energy company: the Apr–Jun bill was re-issued after a meter reading. The real amount is £412.60 (not the £438.90 on the first bill), and that is what was taken from the card.',
  },
  {
    id: 'b08', difficulty: 'hard', seed: 908, start: doy(2, 1), end: doy(9, 30), people: 5,
    setup: (rng, n, s) => ({
      people: [
        p(rng, n[0]!, 14, s.start, s.end, true, true),
        p(rng, n[1]!, 14, s.start, s.end, true, true),
        p(rng, n[2]!, 11, s.start, doy(5, 31), false, false),
        p(rng, n[3]!, 9, s.start, s.end, true, false),
        p(rng, n[4]!, 11, doy(6, 1), s.end, false, true),
      ],
    }),
    rent: { monthly: 260000, paidBy: 1 },
    broadband: { monthly: 3300, paidBy: 4 },
    netflix: { monthly: 1599, paidBy: 0 },
    councilTax: { daily: 540, paidBy: 2 },
    energy: [
      { label: 'Gas and electricity, 1 Feb – 30 Apr', start: doy(2, 1), end: doy(4, 30), amount: 68920, paidBy: 3 },
      { label: 'Gas and electricity, 1 May – 31 Jul', start: doy(5, 1), end: doy(7, 31), amount: 33115, paidBy: 0 },
      { label: 'Gas and electricity, 1 Aug – 30 Sep', start: doy(8, 1), end: doy(9, 30), amount: 18760, paidBy: 4 },
    ],
    boiler: { invoice: 26500, landlord: 10000, causedBy: 0, paidBy: 3 },
    sent: [[2, 1, 75000, 'a one-off payment towards the bills'], [3, 1, 52000, 'a one-off payment towards the bills']],
    distractors: ['My friend Wen stayed on the sofa for two weeks in April. She was a guest, not a flatmate, so she pays nothing and is not part of the split.'],
    late: 'Just confirming the boiler figures above are right: the £265.00 invoice already includes VAT, and the landlord paid £100.00 of it.',
  },
  {
    id: 'b09', difficulty: 'hard', seed: 909, start: doy(1, 1), end: doy(12, 31), people: 6,
    setup: (rng, n, s) => ({
      people: [
        p(rng, n[0]!, 15, s.start, s.end, false, true),
        p(rng, n[1]!, 12, s.start, doy(4, 30), false, true),
        p(rng, n[2]!, 12, s.start, s.end, true, false),
        p(rng, n[3]!, 11, s.start, doy(8, 31), true, true),
        p(rng, n[4]!, 12, doy(5, 1), s.end, false, false),
        p(rng, n[5]!, 11, doy(9, 15), s.end, true, true),
      ],
    }),
    rent: { monthly: 310000, paidBy: 0 },
    broadband: { monthly: 5000, paidBy: 4 },
    netflix: { monthly: 1899, paidBy: 3 },
    councilTax: { daily: 640, paidBy: 0 },
    energy: [
      { label: 'Energy, 1 Jan – 28 Feb', start: doy(1, 1), end: doy(2, 28), amount: 71890, paidBy: 1 },
      { label: 'Energy, 1 Mar – 31 May', start: doy(3, 1), end: doy(5, 31), amount: 60120, paidBy: 2 },
      { label: 'Energy, 1 Jun – 31 Aug', start: doy(6, 1), end: doy(8, 31), amount: 30515, paidBy: 3 },
      { label: 'Energy, 1 Sep – 31 Dec', start: doy(9, 1), end: doy(12, 31), amount: 98460, paidBy: 5 },
    ],
    boiler: { invoice: 38800, landlord: 20000, causedBy: 2, paidBy: 4 },
    sent: [[1, 0, 140000, 'a one-off payment towards the bills'], [2, 0, 70000, 'a one-off payment towards the bills'], [5, 4, 9000, 'broadband, Sep–Dec']],
    late: 'Correction: the Sep–Dec energy bill above is a typo. The final bill was £948.60, and that is what was paid.',
  },
  {
    id: 'b10', difficulty: 'hard', seed: 910, start: doy(4, 1), end: doy(11, 30), people: 5,
    setup: (rng, n, s) => ({
      people: [
        p(rng, n[0]!, 13, s.start, s.end, true, true),
        p(rng, n[1]!, 13, s.start, doy(7, 12), false, true),
        p(rng, n[2]!, 10, s.start, s.end, true, true),
        p(rng, n[3]!, 15, s.start, doy(9, 3), true, false),
        p(rng, n[4]!, 12, doy(7, 13), s.end, false, false),
      ],
    }),
    rent: { monthly: 254000, paidBy: 2 },
    broadband: { monthly: 3800, paidBy: 0 },
    netflix: { monthly: 1099, paidBy: 1, rise: { month: 7, monthly: 1299 } },
    councilTax: { daily: 590, paidBy: 4 },
    energy: [
      { label: 'Energy, 1 Apr – 30 Jun', start: doy(4, 1), end: doy(6, 30), amount: 45210, paidBy: 3 },
      { label: 'Energy, 1 Jul – 30 Sep', start: doy(7, 1), end: doy(9, 30), amount: 26880, paidBy: 1 },
      { label: 'Energy, 1 Oct – 30 Nov', start: doy(10, 1), end: doy(11, 30), amount: 39745, paidBy: 4 },
    ],
    boiler: { invoice: 29900, landlord: 15000, causedBy: 4, paidBy: 0 },
    sent: [[3, 2, 30000, 'a one-off top-up towards rent'], [1, 0, 2500, 'broadband, July']],
    distractors: ['Ignore the £45 takeaway on 3 September: that was a leaving do and we sorted it out on the night.'],
  },
];

/** Corrections applied to the world (what the late message says), by case id. */
const LATE_FIX: Record<string, (s: FlatSpec) => FlatSpec> = {
  b07: (s) => ({ ...s, energy: s.energy.map((e, i) => (i === 1 ? { ...e, amount: 41260 } : e)) }),
  b09: (s) => ({ ...s, energy: s.energy.map((e, i) => (i === 3 ? { ...e, amount: 94860 } : e)) }),
};
/** What the bill list in the prompt shows before the correction. */
const SHOWN_AMOUNT: Record<string, Record<number, number>> = { b07: { 1: 43890 }, b09: { 3: 98460 } };

function promptFor(spec: FlatSpec, people: Person[], extra: string[]): string {
  const name = (i: number) => people[i]!.name;
  const L: string[] = [];
  L.push(`Hi! Our flat needs to settle up for ${dateText(spec.start)} to ${dateText(spec.end)} 2026 (both days included) and I want it exactly right. Here’s everything.`, '');
  L.push('FLATMATES');
  L.push('Name | Room size | Lived here | Student? | In the Netflix share?');
  for (const pr of people) L.push(`${pr.name} | ${pr.room} m² | ${dateText(pr.from)} – ${dateText(pr.to)} | ${pr.student ? 'yes (full-time)' : 'no'} | ${spec.netflix ? (pr.netflix ? 'yes' : 'no, opted out') : '-'}`);
  L.push('A day counts for someone if they lived here that day (first and last days included).');
  L.push('', 'HOW WE SPLIT THINGS (agreed rules)');
  L.push(`- Rent is ${money(spec.rent.monthly)} a calendar month for the whole flat. Each day’s rent (that month’s rent ÷ the number of days in the month) is shared by the people living here that day, in proportion to their room sizes.`);
  if (spec.broadband) L.push(`- Broadband is ${money(spec.broadband.monthly)} a calendar month. Each day’s broadband (month ÷ days in the month) is shared equally by the people living here that day.`);
  if (spec.netflix) L.push(`- Netflix is ${money(spec.netflix.monthly)}, charged on the 1st of every month. Each charge is shared equally by the people in the Netflix share who live here on that 1st.`);
  if (spec.netflix?.rise) L.push(`  (Netflix put the price up to ${money(spec.netflix.rise.monthly)} from the ${MONTH[spec.netflix.rise.month - 1]} charge onwards.)`);
  if (spec.councilTax)
    L.push(`- Council tax is ${money(spec.councilTax.daily)} a day for the flat. Full-time students don’t count and pay none of it. On a day when exactly one non-student lives here, that day’s council tax is 25% less (single person discount) and that person pays it; on a day with no non-students it is £0. Otherwise each day’s council tax is shared equally by the non-students living here that day.`);
  L.push('- Each energy bill covers the dates shown. Each day of a bill (bill ÷ days it covers) is shared equally by the people living here that day.');
  if (spec.boiler) L.push(`- The boiler repair is paid in full by whoever caused it (after the landlord’s contribution).`);
  L.push('', 'BILLS AND WHO PAID THEM');
  L.push(`- ${name(spec.rent.paidBy)} paid the landlord the rent for every month.`);
  const orders = standingOrders(spec, people);
  L.push(`- Everyone else pays ${name(spec.rent.paidBy)} a standing order on the 1st of every month on which they live here: ${Object.entries(orders).map(([n, a]) => `${n} ${money(a)}`).join(', ')}.`);
  if (spec.broadband) L.push(`- ${name(spec.broadband.paidBy)} paid the broadband every month.`);
  if (spec.netflix) L.push(`- Netflix is on ${name(spec.netflix.paidBy)}’s card (every charge).`);
  if (spec.councilTax) L.push(`- ${name(spec.councilTax.paidBy)} paid the council tax in full.`);
  spec.energy.forEach((e, i) => L.push(`- ${e.label}: ${money(SHOWN_AMOUNT[spec.id]?.[i] ?? e.amount)}, paid by ${name(e.paidBy)}.`));
  if (spec.boiler) L.push(`- Boiler repair: the invoice was ${money(spec.boiler.invoice)}; the landlord covered ${money(spec.boiler.landlord)} and ${name(spec.boiler.paidBy)} paid the engineer the rest. ${name(spec.boiler.causedBy)} admits it was their fault (they turned the pressure valve the wrong way).`);
  for (const [f, t, amt, note] of spec.sent ?? []) L.push(`- ${name(f)} already sent ${name(t)} ${money(amt)} (${note}).`);
  if (spec.distractors?.length) L.push('', ...spec.distractors);
  if (extra.length) L.push('', ...extra);
  if (spec.late) L.push('', spec.late);
  L.push(
    '',
    'Work out each person’s balance: everything they paid (to the landlord, suppliers or engineer, plus money they sent to flatmates) minus their fair share of everything, minus money flatmates sent them. A positive balance means they are owed money. Keep exact amounts throughout and round each balance to the nearest penny only at the end.',
    'Then settle up with as few bank transfers as possible, using our method (it makes the answer unique): the person who owes the most pays the person who is owed the most, as much as possible (the smaller of the two amounts); repeat until everyone is square. If two people owe (or are owed) exactly the same, take them in alphabetical order.',
    '',
    'Answer in this JSON format (amounts in pounds, two decimals; list the transfers in the order the method makes them):',
    '```json',
    `{"balances": {"<name>": 0.00}, "transfers": [{"from": "<name>", "to": "<name>", "amount": 0.00}]}`,
    '```',
    ONE_ANSWER,
  );
  return L.join('\n');
}

export interface BillCase {
  id: string;
  difficulty: 'easy' | 'medium' | 'hard';
  world: SettleWorld;
  prompt: string;
  notes: string;
  reference: Record<string, unknown>;
  careless: Array<{ label: string; answer: Record<string, unknown> }>;
}

function answerOf(o: { balances: Record<string, number>; transfers: SettleWorld['transfers'] }): Record<string, unknown> {
  return { balances: Object.fromEntries(Object.entries(o.balances).map(([k, v]) => [k, pounds(v)])), transfers: o.transfers.map((t) => ({ ...t, amount: pounds(t.amount) })) };
}

export function buildBillCases(): BillCase[] {
  return SPECS.map((spec0) => {
    for (let attempt = 0; attempt < 400; attempt++) {
      const rng = new Rng(spec0.seed + attempt * 7919);
      const names = rng.sample(FIRST_NAMES, 6);
      const { people, extra = [] } = spec0.setup(rng, names, { start: spec0.start, end: spec0.end });
      // Energy amounts move by a few pence per attempt (never the corrected bill, whose amount the message states).
      const fixedIdx = new Set(Object.keys(SHOWN_AMOUNT[spec0.id] ?? {}).map(Number));
      const jittered: FlatSpec = attempt === 0 ? spec0 : { ...spec0, energy: spec0.energy.map((e, i) => (fixedIdx.has(i) ? e : { ...e, amount: e.amount + rng.int(-40, 40) })) };
      const spec = LATE_FIX[spec0.id] ? LATE_FIX[spec0.id]!(jittered) : jittered;
      let out: Outcome;
      try {
        out = compute(spec, people, spec.energy);
      } catch (err) {
        if (process.env.DEBUG_BILLS) console.error(spec0.id, attempt, (err as Error).message);
        continue; // rounding or coverage problem: try the next names / seed
      }
      if (out.transfers.length !== minTransfers(out.balances)) continue;
      if (Object.values(out.balances).some((b) => b === 0)) continue;
      const world: SettleWorld = { people: people.map((x) => x.name), balances: out.balances, transfers: out.transfers };
      const careless: BillCase['careless'] = [];
      // Careless 1: splits everything equally per day (ignores room sizes).
      try {
        careless.push({ label: 'ignores room sizes (rent split equally)', answer: answerOf(compute(spec, people.map((x) => ({ ...x, room: 1 })), spec.energy)) });
      } catch {
        /* rounding: skip */
      }
      // Careless 2: council tax shared by everyone (forgets the student rule).
      if (spec.councilTax) {
        try {
          careless.push({ label: 'forgets the student and single-person council tax rules', answer: answerOf(compute(spec, people.map((x) => ({ ...x, student: false })), spec.energy)) });
        } catch {
          /* skip */
        }
      }
      // Careless 3: uses the uncorrected bill.
      if (SHOWN_AMOUNT[spec0.id]) {
        try {
          const shownEnergy = spec.energy.map((e, i) => ({ ...e, amount: SHOWN_AMOUNT[spec0.id]?.[i] ?? e.amount }));
          careless.push({ label: 'uses the bill before the correction', answer: answerOf(compute(spec, people, shownEnergy)) });
        } catch {
          /* skip */
        }
      }
      const reference = answerOf(out);
      return {
        id: spec0.id,
        difficulty: spec0.difficulty,
        world,
        prompt: promptFor({ ...spec, energy: jittered.energy }, people, extra),
        reference,
        careless,
        notes: `[${spec0.difficulty}] Exact shares (fractions) rounded only at the end: ${Object.entries(out.balances).map(([k, v]) => `${k} ${money(v)}`).join(', ')} (sum £0.00). Settle-up by the stated method, ${out.transfers.length} transfers = the proven minimum: ${out.transfers.map((t) => `${t.from}→${t.to} ${money(t.amount)}`).join('; ')}. Generator attempt ${attempt}.`,
      };
    }
    throw new Error(`${spec0.id}: no valid flat after 400 attempts`);
  });
}

if (import.meta.url === `file://${process.argv[1]}`) {
  for (const c of buildBillCases()) {
    console.log(c.id, c.notes);
    if (process.argv.includes('--prompt')) console.log(c.prompt);
  }
}
