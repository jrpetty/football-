/**
 * Cheapest Deal (life.tariff-chooser): energy tariffs and phone contracts, generator + exact calculator.
 *
 * Energy: each month's bill = kWh × unit rate + days × standing charge (rates in pence to two decimals, so the
 * sums are exact in hundredths of a penny), plus VAT when a supplier quotes without it, rounded to the nearest
 * penny (halves up); the months are added, then exit fees, cashback and discounts. Phones: every monthly charge in
 * pence, price rises from each April bill, promotional months, roaming days, the handset and any upfront cost.
 * The generator refuses any case with a tie for cheapest or a half-penny rounding.
 */
import { Q, Rng, money, pounds, ONE_ANSWER } from './common.mts';
import type { FieldSpec, FieldsWorld } from '../../src/scoring/verifiers/fields.ts';

const MONTH = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const MDAYS = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];

/** Round a Q of pence to whole pence, halves up; refuse exact halves only where the prompt's rule would be ambiguous (it is not: halves up). */
function rp(q: Q): number {
  return Number(q.round());
}

/** Pence with two decimals, from a number like 24.86 (p/kWh): as Q in pence. */
function pq(pence2dp: number): Q {
  return new Q(Math.round(pence2dp * 100), 100);
}

// ───────────────────────────── Energy ─────────────────────────────

interface EnergyTariff {
  id: string;
  name: string;
  /** Per quarter (or a single set): rates by month index 0–11. */
  elec: (m: number) => { unit: number; standing: number; night?: number };
  gas?: (m: number) => { unit: number; standing: number };
  vatIncluded: boolean;
  exitFee?: number;
  /** Fixed term in months from the start; exit fees apply when leaving before it ends. */
  termMonths?: number;
  cashback?: { amount: number; ifStayMonths: number };
  monthlyDiscount?: number;
  text: string[];
}

interface EnergySpec {
  id: string;
  difficulty: 'easy' | 'medium' | 'hard';
  seed: number;
  intro: string;
  startMonth: number;
  months: number;
  /** Household moves out at the end of this many months (exit fees on fixed deals with a longer term). */
  leaveAfter?: number;
  elecKwh: number[];
  gasKwh?: number[];
  /** Economy 7: share of electricity used at night, per month (percent). */
  nightPct?: number[];
  tariffs: (rng: Rng) => EnergyTariff[];
  late?: string;
}

function energyTotal(spec: EnergySpec, t: EnergyTariff): number {
  let total = 0;
  for (let i = 0; i < spec.months; i++) {
    const m = (spec.startMonth - 1 + i) % 12;
    const days = MDAYS[m]!;
    const e = t.elec(m);
    let bill = new Q(0);
    const kwh = spec.elecKwh[i]!;
    if (e.night !== undefined && spec.nightPct) {
      const night = new Q(kwh * spec.nightPct[i]!, 100);
      const day = new Q(kwh).sub(night);
      bill = bill.add(day.mul(pq(e.unit))).add(night.mul(pq(e.night)));
    } else bill = bill.add(new Q(kwh).mul(pq(e.unit)));
    bill = bill.add(new Q(days).mul(pq(e.standing)));
    if (t.gas && spec.gasKwh) {
      const g = t.gas(m);
      bill = bill.add(new Q(spec.gasKwh[i]!).mul(pq(g.unit))).add(new Q(days).mul(pq(g.standing)));
    }
    if (!t.vatIncluded) bill = bill.mul(new Q(105, 100));
    total += rp(bill) - (t.monthlyDiscount ?? 0);
  }
  if (t.exitFee && t.termMonths && spec.months < t.termMonths) total += t.exitFee;
  if (t.cashback && spec.months >= t.cashback.ifStayMonths) total -= t.cashback.amount;
  return total;
}

const jit = (rng: Rng, x: number, spread = 0.04) => Math.round(x * (1 - spread + rng.next() * 2 * spread) * 100) / 100;

function flat(unit: number, standing: number) {
  return () => ({ unit, standing });
}

function capQuarters(rng: Rng, start: [number, number], steps: number[]): (m: number) => { unit: number; standing: number } {
  // Price-cap style: rates change on 1 Jan, 1 Apr, 1 Jul, 1 Oct by the given percentages.
  const q: Array<{ unit: number; standing: number }> = [];
  let u = start[0];
  let s = start[1];
  for (let i = 0; i < 4; i++) {
    q.push({ unit: Math.round(u * 100) / 100, standing: Math.round(s * 100) / 100 });
    u = u * (1 + steps[i]! / 100);
    s = s * (1 + (steps[i]! / 100) * 0.5);
  }
  void rng;
  return (m: number) => q[Math.floor(m / 3)]!;
}

function rateText(name: string, f: (m: number) => { unit: number; standing: number; night?: number }, months: number[]): string {
  const seen = new Map<string, number[]>();
  for (const m of months) {
    const r = f(m);
    const k = r.night !== undefined ? `day ${r.unit.toFixed(2)}p/kWh, night ${r.night.toFixed(2)}p/kWh, standing charge ${r.standing.toFixed(2)}p/day` : `${r.unit.toFixed(2)}p/kWh, standing charge ${r.standing.toFixed(2)}p/day`;
    seen.set(k, [...(seen.get(k) ?? []), m]);
  }
  if (seen.size === 1) return `${name}: ${[...seen.keys()][0]}`;
  return `${name}: ${[...seen.entries()].map(([k, ms]) => `${MONTH[ms[0]!]!.slice(0, 3)}–${MONTH[ms[ms.length - 1]!]!.slice(0, 3)} ${k}`).join('; ')}`;
}

const USAGE_E = [310, 280, 265, 230, 205, 180, 175, 185, 205, 245, 285, 320];
const USAGE_G = [1450, 1300, 1100, 760, 520, 310, 260, 270, 420, 820, 1150, 1480];

function usage(rng: Rng, base: number[], start: number, months: number, scale: number): number[] {
  return Array.from({ length: months }, (_, i) => Math.round(base[(start - 1 + i) % 12]! * scale * (0.9 + rng.next() * 0.2)));
}

const ENERGY: EnergySpec[] = [
  {
    id: 'e01', difficulty: 'easy', seed: 1101, startMonth: 1, months: 12,
    intro: 'I live alone in a one-bed flat with electricity only (no gas). I want the cheapest electricity deal for the next 12 months, January to December 2027.',
    elecKwh: [], tariffs: (rng) => [
      { id: 'A', name: 'BrightSpark Fix 12', elec: flat(jit(rng, 24.5), jit(rng, 53.1)), vatIncluded: true, text: ['Fixed for 12 months.'] },
      { id: 'B', name: 'Volt Simple Saver', elec: flat(jit(rng, 22.9), jit(rng, 64.8)), vatIncluded: true, text: ['Fixed for 12 months.'] },
      { id: 'C', name: 'Northern Lights Basic', elec: flat(jit(rng, 26.1), jit(rng, 39.5)), vatIncluded: true, text: ['Fixed for 12 months.'] },
    ],
  },
  {
    id: 'e02', difficulty: 'easy', seed: 1102, startMonth: 1, months: 12,
    intro: 'We are a family of four in a semi with gas central heating. Which dual-fuel deal is cheapest for January to December 2027?',
    elecKwh: [], gasKwh: [], tariffs: (rng) => [
      { id: 'A', name: 'HomeHeat Fixed', elec: flat(jit(rng, 25.2), jit(rng, 52.4)), gas: flat(jit(rng, 6.4), jit(rng, 30.8)), vatIncluded: true, text: ['Fixed for 12 months.'] },
      { id: 'B', name: 'GreenGrid Online', elec: flat(jit(rng, 23.7), jit(rng, 60.1)), gas: flat(jit(rng, 6.9), jit(rng, 33.5)), vatIncluded: true, text: ['Fixed for 12 months.'] },
      { id: 'C', name: 'Thrift Energy Value', elec: flat(jit(rng, 24.1), jit(rng, 49.9)), gas: flat(jit(rng, 6.6), jit(rng, 34.9)), vatIncluded: false, text: ['Prices shown EXCLUDE VAT; add VAT at 5% to each monthly bill.'] },
    ],
  },
  {
    id: 'e03', difficulty: 'medium', seed: 1103, startMonth: 1, months: 12,
    intro: 'Two of us, gas and electricity, January to December 2027. One tariff follows the price cap, which changes every quarter; I’ve put in the forecast cap rates.',
    elecKwh: [], gasKwh: [], tariffs: (rng) => [
      { id: 'A', name: 'Fixed Freedom 12', elec: flat(jit(rng, 25.9), jit(rng, 51.0)), gas: flat(jit(rng, 6.7), jit(rng, 31.2)), vatIncluded: true, text: ['Fixed for 12 months.'] },
      { id: 'B', name: 'Standard Variable (price cap)', elec: capQuarters(rng, [jit(rng, 24.6), jit(rng, 53.7)], [3.5, -2.0, 4.5, 0]), gas: capQuarters(rng, [jit(rng, 6.2), jit(rng, 31.6)], [2.0, -4.0, 6.0, 0]), vatIncluded: true, text: ['Rates change with the price cap on 1 April, 1 July and 1 October (see the table).'] },
      { id: 'C', name: 'Tracker Plus', elec: capQuarters(rng, [jit(rng, 23.4), jit(rng, 58.2)], [6.0, 1.0, 5.0, 0]), gas: capQuarters(rng, [jit(rng, 6.0), jit(rng, 35.1)], [5.0, 2.0, 7.0, 0]), vatIncluded: true, text: ['Tracker: rates change every quarter (see the table).'] },
      { id: 'D', name: 'Loyal Customer Fix', elec: flat(jit(rng, 26.4), jit(rng, 47.3)), gas: flat(jit(rng, 6.8), jit(rng, 29.9)), vatIncluded: true, cashback: { amount: 4000, ifStayMonths: 12 }, text: ['Fixed for 12 months, with £40 cashback credited at the end if you stay all 12 months.'] },
    ],
  },
  {
    id: 'e04', difficulty: 'medium', seed: 1104, startMonth: 4, months: 9, leaveAfter: 9,
    intro: 'We are moving abroad at the end of December 2027, so we only need energy from April to December 2027 (9 months). Some deals are 12-month fixes with an exit fee if you leave early.',
    elecKwh: [], gasKwh: [], tariffs: (rng) => [
      { id: 'A', name: 'Fix & Forget 12', elec: flat(jit(rng, 23.8), jit(rng, 50.6)), gas: flat(jit(rng, 6.1), jit(rng, 30.2)), vatIncluded: true, termMonths: 12, exitFee: 15000, text: ['12-month fix. Exit fee £75 per fuel (£150 in total) if you leave before the 12 months are up.'] },
      { id: 'B', name: 'Flexi Variable', elec: capQuarters(rng, [jit(rng, 24.9), jit(rng, 54.2)], [0, -3.0, 4.0, 0]), gas: capQuarters(rng, [jit(rng, 6.5), jit(rng, 31.9)], [0, -2.0, 5.0, 0]), vatIncluded: true, text: ['No exit fee. Rates change on 1 July and 1 October (see the table).'] },
      { id: 'C', name: 'Fix 9 Smart', elec: flat(jit(rng, 25.6), jit(rng, 52.0)), gas: flat(jit(rng, 6.6), jit(rng, 33.0)), vatIncluded: true, termMonths: 9, exitFee: 6000, text: ['9-month fix. Exit fee £30 per fuel if you leave before the 9 months are up.'] },
    ],
  },
  {
    id: 'e05', difficulty: 'hard', seed: 1105, startMonth: 1, months: 12,
    intro: 'Electric car + storage heaters, so I’m on Economy 7 meters (cheaper night rate). Electricity only, January to December 2027. I’ve listed what share of my electricity I use at night each month.',
    elecKwh: [], nightPct: [], tariffs: (rng) => {
      const a = { unit: jit(rng, 30.1), night: jit(rng, 13.2), standing: jit(rng, 55.4) };
      const b = flat(jit(rng, 24.3), jit(rng, 48.9));
      const c = { unit: jit(rng, 31.8), night: jit(rng, 9.9), standing: jit(rng, 61.2) };
      return [
      { id: 'A', name: 'Night Owl E7 Fix', elec: () => a, vatIncluded: true, text: ['Economy 7, fixed for 12 months.'] },
      { id: 'B', name: 'Single Rate Saver', elec: b, vatIncluded: true, text: ['One rate all day and night, fixed for 12 months.'] },
      { id: 'C', name: 'EV Smart Charge', elec: () => c, vatIncluded: false, text: ['Economy 7 style day/night rates. Prices EXCLUDE VAT: add 5% to each monthly bill.'] },
      { id: 'D', name: 'Cap Tracker E7', elec: (m: number) => [{ unit: 28.4, night: 12.1, standing: 53.0 }, { unit: 29.6, night: 12.7, standing: 53.8 }, { unit: 28.1, night: 11.9, standing: 54.2 }, { unit: 30.2, night: 13.0, standing: 54.9 }][Math.floor(m / 3)]!, vatIncluded: true, monthlyDiscount: 150, text: ['Economy 7 tracker: rates change every quarter (see the table). £1.50 off every monthly bill for paying by direct debit.'] },
      ];
    },
  },
];

// ───────────────────────────── Phones ─────────────────────────────

interface PhoneOption {
  id: string;
  name: string;
  text: string[];
  total: (s: PhoneSpec) => number;
}

interface Trip {
  where: string;
  month: number; // 1..24 within the contract
  days: number;
  zone: 'eu' | 'us' | 'row';
}

interface PhoneSpec {
  id: string;
  difficulty: 'easy' | 'medium' | 'hard';
  seed: number;
  intro: string;
  startMonth: number; // calendar month of the first bill
  startYear: number;
  months: number;
  trips: Trip[];
  options: (rng: Rng) => PhoneOption[];
  late?: string;
}

/** Monthly charges for months 1..n with a rise of `rise` pence from every April bill after the start. */
function monthlyWithRises(s: PhoneSpec, base: (k: number) => number, rise: number): number {
  let total = 0;
  let extra = 0;
  for (let k = 1; k <= s.months; k++) {
    const cal = ((s.startMonth - 1 + (k - 1)) % 12) + 1;
    if (cal === 4 && k > 1) extra += rise;
    total += base(k) + extra;
  }
  return total;
}

function roaming(s: PhoneSpec, perDay: Partial<Record<Trip['zone'], number>>): number {
  return s.trips.reduce((t, tr) => t + tr.days * (perDay[tr.zone] ?? 0), 0);
}

const PHONES: PhoneSpec[] = [
  {
    id: 'm01', difficulty: 'easy', seed: 1201, startMonth: 1, startYear: 2027, months: 24,
    intro: 'I want the new Pixelon 9 phone and a 24-month deal starting with the January 2027 bill. I don’t travel. Which option is cheapest over the 24 months, including the phone?',
    trips: [],
    options: () => [
      { id: 'A', name: 'Contract with the phone', text: ['£42.00 a month for 24 months, £30.00 upfront for the phone. No price rises.'], total: (s) => 3000 + 24 * 4200 * (s.months / 24) },
      { id: 'B', name: 'Buy the phone + SIM-only', text: ['Phone £699.00 from the shop. SIM-only £12.00 a month for 24 months, no price rises.'], total: (s) => 69900 + s.months * 1200 },
      { id: 'C', name: 'Phone on 0% credit + SIM', text: ['Phone £719.00 paid in 24 equal monthly instalments (0% interest). SIM-only £10.50 a month, no price rises.'], total: (s) => 71900 + s.months * 1050 },
    ],
  },
  {
    id: 'm02', difficulty: 'medium', seed: 1202, startMonth: 11, startYear: 2026, months: 24,
    intro: 'Phone upgrade time. The 24 months start with the November 2026 bill and end with October 2028. Networks put prices up every April — the April bill is the first one at the higher price. Which deal costs least in total, phone included?',
    trips: [],
    options: () => [
      { id: 'A', name: 'Network One handset deal', text: ['£38.00 a month, £49.00 upfront. Goes up by £1.80 a month from each April bill.'], total: (s) => 4900 + monthlyWithRises(s, () => 3800, 180) },
      { id: 'B', name: 'SIM-only 24 + phone outright', text: ['Phone £629.00. SIM-only £15.00 a month, half price (£7.50) for the first 6 months. Goes up by £1.50 a month from each April bill.'], total: (s) => 62900 + monthlyWithRises(s, (k) => (k <= 6 ? 750 : 1500), 150) },
      { id: 'C', name: 'Refurbished phone + rolling SIM', text: ['Refurbished phone £489.00. Rolling 30-day SIM £19.00 a month, no price rises.'], total: (s) => 48900 + s.months * 1900 },
    ],
  },
  {
    id: 'm03', difficulty: 'medium', seed: 1203, startMonth: 3, startYear: 2027, months: 24,
    intro: 'I travel for work. 24-month deal from the March 2027 bill. Roaming matters: here are my planned trips. Which option is cheapest in total over the 24 months?',
    trips: [
      { where: 'Paris', month: 3, days: 4, zone: 'eu' },
      { where: 'Madrid', month: 7, days: 5, zone: 'eu' },
      { where: 'New York', month: 9, days: 6, zone: 'us' },
      { where: 'Berlin', month: 15, days: 3, zone: 'eu' },
      { where: 'Chicago', month: 20, days: 5, zone: 'us' },
    ],
    options: () => [
      { id: 'A', name: 'Unlimited Plus (phone included)', text: ['£54.00 a month, £0 upfront. EU and USA roaming included. Goes up £2.00 a month from each April bill.'], total: (s) => monthlyWithRises(s, () => 5400, 200) },
      { id: 'B', name: 'Value plan (phone included)', text: ['£41.00 a month, £99.00 upfront. EU roaming £2.25 a day, USA £6.00 a day. Goes up £1.50 a month from each April bill.'], total: (s) => 9900 + monthlyWithRises(s, () => 4100, 150) + roaming(s, { eu: 225, us: 600 }) },
      { id: 'C', name: 'SIM-only + phone outright', text: ['Phone £749.00. SIM £14.00 a month; EU roaming included, USA £5.00 a day. Goes up £1.00 a month from each April bill.'], total: (s) => 74900 + monthlyWithRises(s, () => 1400, 100) + roaming(s, { us: 500 }) },
      { id: 'D', name: 'Budget SIM + phone outright', text: ['Phone £749.00. SIM £8.00 a month; EU roaming £2.00 a day, USA £7.50 a day. No price rises.'], total: (s) => 74900 + s.months * 800 + roaming(s, { eu: 200, us: 750 }) },
    ],
  },
  {
    id: 'm04', difficulty: 'hard', seed: 1204, startMonth: 9, startYear: 2026, months: 24,
    intro: 'Family of three: we each need a phone line, but only I need a new phone. Contracts start with the September 2026 bill, 24 months (ending August 2028). Price rises come from each April bill. Work out the total for the whole family over the 24 months for each option.',
    trips: [
      { where: 'Majorca (all three of us)', month: 11, days: 7, zone: 'eu' },
      { where: 'Florida (all three of us)', month: 21, days: 10, zone: 'us' },
    ],
    options: () => [
      { id: 'A', name: 'Family bundle', text: ['My line with the new phone £45.00 a month + two SIM-only lines at £9.00 each, all with 20% off the total monthly bill (before any price rises) for being a family. £60.00 upfront. Price rise: each line goes up £1.50 a month from each April bill (the 20% discount does not apply to the rises). EU roaming included; USA £5.00 a day per person.'], total: (s) => 6000 + monthlyWithRises(s, () => Math.round((4500 + 900 + 900) * 0.8), 450) + roaming(s, { us: 500 }) * 3 },
      { id: 'B', name: 'Three separate SIMs + phone outright', text: ['Phone £799.00. Three SIM-only lines at £11.00 a month each, the first 6 months at half price. Price rise: £1.00 per line per month from each April bill. EU and USA roaming included.'], total: (s) => 79900 + monthlyWithRises(s, (k) => 3 * (k <= 6 ? 550 : 1100), 300) },
      { id: 'C', name: 'Big Data Family', text: ['Phone included. £78.00 a month for all three lines together, £0 upfront, no price rises for the 24 months. EU roaming £2.50 a day per person, USA £6.00 a day per person.'], total: (s) => s.months * 7800 + roaming(s, { eu: 250, us: 600 }) * 3 },
    ],
    late: 'Note: we will NOT go to Florida after all — we are going to Lisbon instead (same dates, 10 days, all three of us).',
  },
  {
    id: 'm05', difficulty: 'hard', seed: 1205, startMonth: 2, startYear: 2027, months: 24,
    intro: 'Choosing between keeping my current phone and upgrading. 24 months from the February 2027 bill. Please include everything: trade-in, upfront, price rises from each April bill, promos, roaming, insurance.',
    trips: [
      { where: 'Dublin', month: 2, days: 3, zone: 'eu' },
      { where: 'Toronto', month: 8, days: 9, zone: 'row' },
      { where: 'Rome', month: 14, days: 4, zone: 'eu' },
      { where: 'Dubai', month: 22, days: 5, zone: 'row' },
    ],
    options: () => [
      { id: 'A', name: 'Upgrade on contract', text: ['£47.00 a month, £29.00 upfront, minus £120.00 trade-in credit for my old phone (paid back to me once). Phone insurance £7.00 a month (no rises). Price rise: £2.00 a month from each April bill on the plan only. EU roaming included; rest of world £6.00 a day.'], total: (s) => 2900 - 12000 + monthlyWithRises(s, () => 4700, 200) + s.months * 700 + roaming(s, { row: 600 }) },
      { id: 'B', name: 'Keep my phone + SIM-only', text: ['SIM-only £11.00 a month, the first 3 months free. Price rise £1.00 a month from each April bill. I would need a new battery for my old phone: £89.00 once. EU roaming £2.00 a day; rest of world £5.00 a day.'], total: (s) => 8900 + monthlyWithRises(s, (k) => (k <= 3 ? 0 : 1100), 100) + roaming(s, { eu: 200, row: 500 }) },
      { id: 'C', name: 'Buy outright + SIM with roaming pack', text: ['Phone £679.00, and I sell my old phone for £150.00. SIM £13.00 a month, no price rises, roaming everywhere included.'], total: (s) => 67900 - 15000 + s.months * 1300 },
      { id: 'D', name: 'Contract, no upfront', text: ['£52.00 a month, £0 upfront, no trade-in. Price rise £1.50 a month from each April bill. Roaming everywhere included for the first 12 months, then EU £2.00 a day and rest of world £6.00 a day.'], total: (s) => monthlyWithRises(s, () => 5200, 150) + s.trips.filter((t) => t.month > 12).reduce((a, t) => a + t.days * (t.zone === 'eu' ? 200 : 600), 0) },
    ],
  },
];

// ───────────────────────────── Building ─────────────────────────────

export interface TariffCase {
  id: string;
  difficulty: 'easy' | 'medium' | 'hard';
  world: FieldsWorld;
  prompt: string;
  notes: string;
  reference: Record<string, unknown>;
  careless: Array<{ label: string; answer: Record<string, unknown> }>;
}

function fieldsFor(totals: Record<string, number>, names: Record<string, string>): FieldsWorld {
  const ids = Object.keys(totals);
  const best = ids.slice().sort((a, b) => totals[a]! - totals[b]!);
  if (totals[best[0]!] === totals[best[1]!]) throw new Error('tie for cheapest');
  const fields: FieldSpec[] = ids.map((id) => ({ path: `totals.${id}`, label: `total for ${id} (${names[id]})`, kind: 'money', value: totals[id]! }));
  fields.push({ path: 'cheapest', aliases: ['best', 'choice', 'winner', 'cheapestoption'], label: 'the cheapest option', kind: 'choice', value: best[0]!, choices: names });
  return { fields };
}

function answerFrom(totals: Record<string, number>): Record<string, unknown> {
  const ids = Object.keys(totals).sort((a, b) => totals[a]! - totals[b]!);
  return { totals: Object.fromEntries(Object.entries(totals).map(([k, v]) => [k, pounds(v)])), cheapest: ids[0] };
}

const ANSWER_FMT = (ids: string[]) => [
  'Answer in this JSON format, with every option’s exact total in pounds (to the penny) and the letter of the cheapest:',
  '```json',
  `{"totals": {${ids.map((id) => `"${id}": 0.00`).join(', ')}}, "cheapest": "<letter>"}`,
  '```',
  ONE_ANSWER,
];

function energyCase(spec: EnergySpec): TariffCase {
  const rng = new Rng(spec.seed);
  const s: EnergySpec = { ...spec };
  s.elecKwh = usage(rng, USAGE_E, spec.startMonth, spec.months, spec.gasKwh ? 1.15 : 0.85);
  if (spec.gasKwh) s.gasKwh = usage(rng, USAGE_G, spec.startMonth, spec.months, 1);
  if (spec.nightPct) {
    s.elecKwh = usage(rng, USAGE_E, spec.startMonth, spec.months, 1.9);
    s.nightPct = Array.from({ length: spec.months }, (_, i) => 35 + ((i * 7 + rng.int(0, 20)) % 30));
  }
  const tariffs = spec.tariffs(rng);
  const totals: Record<string, number> = {};
  const names: Record<string, string> = {};
  for (const t of tariffs) {
    totals[t.id] = energyTotal(s, t);
    names[t.id] = t.name;
  }
  const monthsIdx = Array.from({ length: s.months }, (_, i) => (s.startMonth - 1 + i) % 12);
  const L: string[] = [spec.intro, '', 'MY USAGE (from my smart meter, kWh per month)'];
  L.push(`Month | Electricity${s.gasKwh ? ' | Gas' : ''}${s.nightPct ? ' | Share used at night' : ''}`);
  monthsIdx.forEach((m, i) => L.push(`${MONTH[m]} 2027 (${MDAYS[m]} days) | ${s.elecKwh[i]}${s.gasKwh ? ` | ${s.gasKwh[i]}` : ''}${s.nightPct ? ` | ${s.nightPct[i]}%` : ''}`));
  L.push('', 'THE TARIFFS');
  for (const t of tariffs) {
    L.push(`${t.id}. ${t.name}. ${t.text.join(' ')}`);
    L.push(`   ${rateText('Electricity', t.elec, monthsIdx)}`);
    if (t.gas) L.push(`   ${rateText('Gas', t.gas, monthsIdx)}`);
  }
  L.push(
    '',
    'HOW TO WORK IT OUT: for each tariff, work out each month’s bill = kWh × unit rate + days in the month × standing charge (for Economy 7: the night share of that month’s kWh at the night rate and the rest at the day rate), add VAT at 5% where a tariff’s prices exclude it, and round each monthly bill to the nearest penny (halves round up). Then add up the months, subtract any monthly discounts and cashback that apply, and add any exit fee that applies.',
  );
  if (spec.late) L.push('', spec.late);
  L.push('', ...ANSWER_FMT(Object.keys(totals)));
  const careless: TariffCase['careless'] = [];
  // Careless: forgets VAT on the ex-VAT tariff(s), and ignores exit fees / cashback.
  const noVat: Record<string, number> = {};
  for (const t of tariffs) noVat[t.id] = energyTotal(s, { ...t, vatIncluded: true });
  careless.push({ label: 'forgets VAT where prices exclude it', answer: answerFrom(noVat) });
  const noFees: Record<string, number> = {};
  for (const t of tariffs) noFees[t.id] = energyTotal(s, { ...t, exitFee: undefined, cashback: undefined, monthlyDiscount: undefined });
  careless.push({ label: 'ignores exit fees, cashback and discounts', answer: answerFrom(noFees) });
  const world = fieldsFor(totals, names);
  return {
    id: spec.id,
    difficulty: spec.difficulty,
    world,
    prompt: L.join('\n'),
    reference: answerFrom(totals),
    careless,
    notes: `[${spec.difficulty}] Exact totals (monthly bills rounded to the penny, halves up): ${Object.entries(totals).map(([k, v]) => `${k} ${money(v)}`).join(', ')}. Cheapest: ${(world.fields[world.fields.length - 1]!.value as string)}.`,
  };
}

function phoneCase(spec: PhoneSpec): TariffCase {
  const rng = new Rng(spec.seed);
  const options = spec.options(rng);
  const world0 = { ...spec };
  if (spec.id === 'm04') world0.trips = spec.trips.map((t) => (t.zone === 'us' ? { ...t, where: 'Lisbon (all three of us)', zone: 'eu' as const } : t));
  const totals: Record<string, number> = {};
  const names: Record<string, string> = {};
  for (const o of options) {
    totals[o.id] = Math.round(o.total(world0));
    names[o.id] = o.name;
  }
  const L: string[] = [spec.intro, ''];
  if (spec.trips.length) {
    L.push('MY TRIPS (days abroad; each day abroad counts once per person)');
    for (const t of spec.trips) {
      const cal = ((spec.startMonth - 1 + t.month - 1) % 12) + 1;
      const year = spec.startYear + Math.floor((spec.startMonth - 1 + t.month - 1) / 12);
      L.push(`- ${t.where}: ${t.days} days in ${MONTH[cal - 1]} ${year} (contract month ${t.month}; ${t.zone === 'eu' ? 'EU' : t.zone === 'us' ? 'USA' : 'rest of the world'})`);
    }
    L.push('');
  }
  L.push('THE OPTIONS');
  for (const o of options) L.push(`${o.id}. ${o.name}: ${o.text.join(' ')}`);
  L.push(
    '',
    `HOW TO WORK IT OUT: the total for each option is everything paid over the ${spec.months} months (${MONTH[spec.startMonth - 1]} ${spec.startYear} to the end of the contract): every monthly bill, upfront costs, the phone, roaming and extras, minus any trade-in or sale money. A price rise applies to every bill from that April onwards (they add up: the second April adds another rise on top). Everything is exact to the penny.`,
  );
  if (spec.late) L.push('', spec.late);
  L.push('', ...ANSWER_FMT(Object.keys(totals)));
  const careless: TariffCase['careless'] = [];
  // Careless: ignores the April price rises.
  const s2: PhoneSpec = { ...world0, startMonth: 5 }; // with no April inside the first 12 months the rises shift (a typical slip)
  const noRise: Record<string, number> = {};
  for (const o of options) noRise[o.id] = Math.round(o.total(s2));
  careless.push({ label: 'gets the April price rises wrong', answer: answerFrom(noRise) });
  if (spec.late) {
    const orig: Record<string, number> = {};
    for (const o of options) orig[o.id] = Math.round(o.total(spec));
    careless.push({ label: 'misses the late message', answer: answerFrom(orig) });
  }
  const world = fieldsFor(totals, names);
  return {
    id: spec.id,
    difficulty: spec.difficulty,
    world,
    prompt: L.join('\n'),
    reference: answerFrom(totals),
    careless,
    notes: `[${spec.difficulty}] Exact totals: ${Object.entries(totals).map(([k, v]) => `${k} ${money(v)}`).join(', ')}. Cheapest: ${(world.fields[world.fields.length - 1]!.value as string)}.`,
  };
}

export function buildTariffCases(): TariffCase[] {
  const e = ENERGY.map(energyCase);
  const m = PHONES.map(phoneCase);
  // Easy, medium, hard order: interleave so the test runs from approachable to hard.
  const order = ['e01', 'm01', 'e02', 'm02', 'e03', 'e04', 'm03', 'e05', 'm04', 'm05'];
  const all = [...e, ...m];
  return order.map((id) => all.find((c) => c.id === id)!);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  for (const c of buildTariffCases()) {
    console.log(c.id, c.notes);
    for (const k of c.careless) console.log('   careless', k.label, JSON.stringify(k.answer));
    if (process.argv.includes('--prompt')) console.log(c.prompt);
  }
}
