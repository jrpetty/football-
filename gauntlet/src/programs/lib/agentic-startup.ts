/**
 * The Startup — hidden market model, monthly simulation, the "autopilot"
 * baseline and the oracle reference policy.
 *
 * Demand in month m:
 *   D = base × season(m) × (price / competitorPrice)^(−elasticity)
 *         × (0.2 + 0.8 × awareness) × reputation × noise(m) × eventFactor
 * Awareness is a marketing stock with diminishing returns and decay:
 *   A' = A(1−δ) + (1 − A(1−δ)) × (1 − e^(−spend/κ))
 * Production is capped by staff capacity and cash; unsold stock costs
 * holding fees; stockouts lose the sale and dent reputation. All numbers
 * are seeded; nothing depends on the model except its own decisions.
 */
import type { Rng } from '../../core/types.ts';
import { clamp, money, round } from './agentic-common.ts';

export const MONTH_NAMES = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];

export interface Market {
  months: number;
  startCalendar: number;
  product: string;
  unitCost: number;
  refPrice: number;
  elasticity: number;
  baseDemand: number;
  awareness0: number;
  decay: number;
  kappa: number;
  seasonAmp: number;
  seasonPeak: number;
  capacityPerStaff: number;
  salary: number;
  rent: number;
  holdingCost: number;
  hireFee: number;
  severance: number;
  staff0: number;
  cash0: number;
  /** Multiplicative demand noise, index 1..months. */
  noise: number[];
  priceWar: { start: number; months: number; factor: number };
  supplierSpike: { start: number; months: number; factor: number };
  viral: { month: number; factor: number; boost: number };
  loan: { month: number; principal: number; rate: number };
  /** The pre-launch plan (the autopilot repeats it every month). */
  opening: Decisions;
}

export interface Decisions {
  price: number;
  produce: number;
  marketing: number;
  hire: number;
  loan: boolean;
}

export interface MonthReport {
  month: number;
  calendar: string;
  price: number;
  produceRequested: number;
  produced: number;
  marketingRequested: number;
  marketing: number;
  hires: number;
  staff: number;
  capacity: number;
  unitCost: number;
  demand: number;
  sold: number;
  missed: number;
  revenue: number;
  cogs: number;
  salaries: number;
  rent: number;
  holding: number;
  staffFees: number;
  loanIn: number;
  loanPayment: number;
  net: number;
  cash: number;
  inventory: number;
  equity: number;
  awareness: number;
  reputation: number;
  adjustments: string[];
}

export interface Firm {
  /** Next month to play (1-based); > months when finished. */
  month: number;
  cash: number;
  inventory: number;
  staff: number;
  awareness: number;
  reputation: number;
  loanBalance: number;
  loanPayment: number;
  loanTaken: boolean;
  bankrupt: boolean;
  history: MonthReport[];
  last: Decisions;
}

const PRODUCTS = ['insulated water bottle', 'desk lamp', 'yoga mat', 'coffee grinder', 'phone stand', 'backpack', 'scented candle set', 'board game'];

/**
 * A seeded market. Markets where even the oracle cannot build a clear lead
 * over the autopilot are rejected (deterministically) so every case is
 * worth playing.
 */
export function generateMarket(root: Rng, months = 12): Market {
  let fallback: Market | null = null;
  for (let attempt = 0; attempt < 40; attempt++) {
    const market = drawMarket(root.fork(`startup:market:${attempt}`), months);
    fallback ??= market;
    const o = oracle(market);
    const base = Math.max(0, equityOf(market, simulate(market, autopilot)));
    if (!o.firm.bankrupt && o.equity >= 40000 && o.equity - base >= 20000) return market;
  }
  return fallback!;
}

function drawMarket(r: Rng, months: number): Market {
  const pick2 = (lo: number, hi: number, step: number): number => round(lo + Math.floor(r.next() * ((hi - lo) / step + 1)) * step, 2);
  const unitCost = pick2(6, 12, 0.5);
  const refPrice = pick2(unitCost * 2.8, unitCost * 4.2, 0.5);
  const elasticity = round(1.3 + r.next() * 1.5, 2);
  const baseDemand = r.int(18, 28) * 50;
  const noise = [1];
  for (let m = 1; m <= months; m++) noise.push(round(0.93 + r.next() * 0.14, 3));
  const market: Market = {
    months,
    startCalendar: r.int(0, 11),
    product: r.pick(PRODUCTS),
    unitCost,
    refPrice,
    elasticity,
    baseDemand,
    awareness0: 0.25,
    decay: round(0.12 + r.next() * 0.16, 3),
    kappa: r.int(25, 60) * 100,
    seasonAmp: round(0.1 + r.next() * 0.3, 3),
    seasonPeak: r.int(0, 11),
    capacityPerStaff: r.int(22, 32) * 10,
    salary: r.int(26, 34) * 100,
    rent: 1200,
    holdingCost: pick2(0.4, 1.2, 0.1),
    hireFee: 1000,
    severance: 1500,
    staff0: 2,
    cash0: 10000,
    noise,
    priceWar: { start: r.int(4, 9), months: 3, factor: 0.8 },
    supplierSpike: { start: r.int(3, 10), months: 3, factor: round(1.3 + r.next() * 0.2, 2) },
    viral: { month: r.int(4, 11), factor: 1.8, boost: 0.2 },
    loan: { month: r.int(2, 6), principal: r.pick([10000, 15000, 20000]), rate: r.pick([0.01, 0.02, 0.04]) },
    opening: { price: 0, produce: 0, marketing: 1000, hire: 0, loan: false },
  };
  // The pre-launch plan: price at the market level, produce the first month's likely demand.
  const price = Math.max(1, Math.round(refPrice) - 0.01);
  const firm = newFirm(market);
  const a1 = nextAwareness(market, firm.awareness, 1000);
  const est = demandFor(market, 1, price, a1, 1, false);
  market.opening = { price, produce: Math.max(100, Math.round(est / 50) * 50), marketing: 1000, hire: 0, loan: false };
  return market;
}

export function newFirm(market: Market): Firm {
  return {
    month: 1,
    cash: market.cash0,
    inventory: 0,
    staff: market.staff0,
    awareness: market.awareness0,
    reputation: 1,
    loanBalance: 0,
    loanPayment: 0,
    loanTaken: false,
    bankrupt: false,
    history: [],
    last: { ...market.opening },
  };
}

export function calendarName(market: Market, month: number): string {
  return MONTH_NAMES[(market.startCalendar + month - 1) % 12]!;
}

export function season(market: Market, month: number): number {
  const cal = (market.startCalendar + month - 1) % 12;
  return 1 + market.seasonAmp * Math.cos((2 * Math.PI * (cal - market.seasonPeak)) / 12);
}

export function priceWarActive(market: Market, month: number): boolean {
  return month >= market.priceWar.start && month < market.priceWar.start + market.priceWar.months;
}

export function spikeActive(market: Market, month: number): boolean {
  return month >= market.supplierSpike.start && month < market.supplierSpike.start + market.supplierSpike.months;
}

export function competitorPrice(market: Market, month: number): number {
  return market.refPrice * (priceWarActive(market, month) ? market.priceWar.factor : 1);
}

export function unitCostAt(market: Market, month: number): number {
  return round(market.unitCost * (spikeActive(market, month) ? market.supplierSpike.factor : 1), 2);
}

export function nextAwareness(market: Market, a: number, spend: number): number {
  const kept = a * (1 - market.decay);
  return Math.min(1, kept + (1 - kept) * (1 - Math.exp(-Math.max(0, spend) / market.kappa)));
}

export function demandFor(market: Market, month: number, price: number, awareness: number, reputation: number, withNoise = true): number {
  const rel = Math.max(0.05, price / competitorPrice(market, month));
  let d = market.baseDemand * season(market, month) * rel ** -market.elasticity * (0.2 + 0.8 * awareness) * reputation;
  if (withNoise) d *= market.noise[month] ?? 1;
  if (month === market.viral.month) d *= market.viral.factor;
  return Math.max(0, Math.round(d));
}

export function loanPaymentFor(market: Market): number {
  const n = market.months - market.loan.month + 1;
  const r = market.loan.rate;
  return round((market.loan.principal * r) / (1 - (1 + r) ** -n), 2);
}

export function equityOf(market: Market, firm: Firm): number {
  return round(firm.cash + firm.inventory * market.unitCost - firm.loanBalance, 2);
}

/** Clamp raw decisions into the legal range (and report adjustments). */
export function sanitize(market: Market, firm: Firm, d: Decisions): { d: Decisions; notes: string[] } {
  const notes: string[] = [];
  const price = round(clamp(Number.isFinite(d.price) ? d.price : firm.last.price, 1, 250), 2);
  if (price !== round(d.price, 2)) notes.push(`price clamped to ${money(price)}`);
  const hireRaw = Math.round(Number.isFinite(d.hire) ? d.hire : 0);
  const hire = clamp(hireRaw, -(firm.staff - 1), 5);
  if (hire !== hireRaw) notes.push(`hiring clamped to ${hire >= 0 ? '+' : ''}${hire} (staff must stay between 1 and ${firm.staff + 5})`);
  const produce = Math.max(0, Math.round(Number.isFinite(d.produce) ? d.produce : 0));
  const marketing = Math.max(0, Math.round(Number.isFinite(d.marketing) ? d.marketing : 0));
  const loan = d.loan && market.loan.month === firm.month && !firm.loanTaken;
  return { d: { price, produce, marketing, hire, loan }, notes };
}

/** Play one month. Mutates `firm` and returns the month's report. */
export function playMonth(market: Market, firm: Firm, raw: Decisions): MonthReport {
  const m = firm.month;
  const { d, notes } = sanitize(market, firm, raw);
  const unitCost = unitCostAt(market, m);
  let cash = firm.cash;
  let loanIn = 0;

  if (d.loan) {
    loanIn = market.loan.principal;
    cash += loanIn;
    firm.loanTaken = true;
    firm.loanBalance = market.loan.principal;
    firm.loanPayment = loanPaymentFor(market);
  }

  const staffFees = d.hire > 0 ? d.hire * market.hireFee : -d.hire * market.severance;
  firm.staff += d.hire;
  cash -= staffFees;

  const marketing = Math.min(d.marketing, Math.max(0, Math.floor(cash)));
  if (marketing < d.marketing) notes.push(`marketing cut to ${money(marketing)} (not enough cash)`);
  cash -= marketing;

  const capacity = firm.staff * market.capacityPerStaff;
  let produced = Math.min(d.produce, capacity);
  if (produced < d.produce) notes.push(`production capped at staff capacity (${capacity} units)`);
  const affordable = Math.max(0, Math.floor(cash / unitCost));
  if (produced > affordable) {
    produced = affordable;
    notes.push(`production cut to ${affordable} units (not enough cash)`);
  }
  const cogs = round(produced * unitCost, 2);
  cash -= cogs;

  firm.awareness = nextAwareness(market, firm.awareness, marketing);
  if (m === market.viral.month) firm.awareness = Math.min(1, firm.awareness + market.viral.boost);

  const demand = demandFor(market, m, d.price, firm.awareness, firm.reputation);
  const available = firm.inventory + produced;
  const sold = Math.min(demand, available);
  const missed = demand - sold;
  firm.inventory = available - sold;
  const revenue = round(sold * d.price, 2);

  const salaries = firm.staff * market.salary;
  const holding = round(firm.inventory * market.holdingCost, 2);
  let loanPayment = 0;
  if (firm.loanTaken && firm.loanBalance > 0.005) {
    loanPayment = Math.min(firm.loanPayment, round(firm.loanBalance * (1 + market.loan.rate), 2));
    const interest = firm.loanBalance * market.loan.rate;
    firm.loanBalance = Math.max(0, round(firm.loanBalance + interest - loanPayment, 2));
  }
  cash += revenue - salaries - market.rent - holding - loanPayment;
  const net = round(cash - firm.cash - loanIn, 2);
  firm.cash = round(cash, 2);

  if (demand > 0 && missed > 0) firm.reputation = Math.max(0.6, firm.reputation - 0.3 * (missed / demand));
  else firm.reputation = Math.min(1.1, firm.reputation + 0.03);
  if (d.price > 1.6 * competitorPrice(market, m)) firm.reputation = Math.max(0.6, firm.reputation - 0.03);

  if (firm.cash < 0) firm.bankrupt = true;
  firm.last = { ...d, loan: false };
  const report: MonthReport = {
    month: m,
    calendar: calendarName(market, m),
    price: d.price,
    produceRequested: d.produce,
    produced,
    marketingRequested: d.marketing,
    marketing,
    hires: d.hire,
    staff: firm.staff,
    capacity,
    unitCost,
    demand,
    sold,
    missed,
    revenue,
    cogs,
    salaries,
    rent: market.rent,
    holding,
    staffFees,
    loanIn,
    loanPayment,
    net,
    cash: firm.cash,
    inventory: firm.inventory,
    equity: 0,
    awareness: firm.awareness,
    reputation: firm.reputation,
    adjustments: notes,
  };
  firm.month++;
  report.equity = equityOf(market, firm);
  firm.history.push(report);
  return report;
}

export type Policy = (market: Market, firm: Firm) => Decisions;

export function simulate(market: Market, policy: Policy): Firm {
  const firm = newFirm(market);
  while (firm.month <= market.months && !firm.bankrupt) playMonth(market, firm, policy(market, firm));
  return firm;
}

/** Final equity of a simulated run (bankrupt runs keep their negative equity). */
export function finalEquity(market: Market, firm: Firm): number {
  return equityOf(market, firm);
}

/** The autopilot: repeats the pre-launch plan every month. */
export const autopilot: Policy = (market) => ({ ...market.opening });

// ─────────────────────────────────────────────────────────────────────────────
// Oracle
// ─────────────────────────────────────────────────────────────────────────────

interface OracleParams {
  priceMult: number[];
  marketing: number[];
  loan: boolean;
  prebuy: boolean;
  fireSlack: number;
}

function oraclePolicy(p: OracleParams): Policy {
  return (market, firm) => {
    const m = firm.month;
    const price = round(p.priceMult[m]! * competitorPrice(market, m), 2);
    const marketing = p.marketing[m]!;
    const a = nextAwareness(market, firm.awareness, marketing) + (m === market.viral.month ? market.viral.boost : 0);
    let need = Math.max(0, demandFor(market, m, price, Math.min(1, a), firm.reputation) - firm.inventory);
    if (p.prebuy && m + 1 === market.supplierSpike.start) {
      // Stock up before the cost spike (rough forecast of the spike months' demand).
      for (let k = 1; k <= market.supplierSpike.months && m + k <= market.months; k++) {
        need += demandFor(market, m + k, round(p.priceMult[m + k]! * competitorPrice(market, m + k), 2), Math.min(1, a), firm.reputation, false);
      }
    }
    const staffNeeded = Math.max(1, Math.ceil(need / market.capacityPerStaff));
    let hire = staffNeeded - firm.staff;
    if (hire < 0 && -hire <= p.fireSlack) hire = 0;
    hire = clamp(hire, -(firm.staff - 1), 5);
    return { price, produce: need, marketing, hire, loan: p.loan && m === market.loan.month };
  };
}

export interface OracleResult {
  equity: number;
  firm: Firm;
  plan: Array<{ month: number; price: number; produce: number; marketing: number; hire: number; loan: boolean }>;
}

/**
 * A strong reference: grid search plus per-month coordinate descent over a
 * policy family, evaluated by full simulation with complete knowledge of the
 * hidden market (elasticity, noise, events). Deterministic.
 */
export function oracle(market: Market): OracleResult {
  const n = market.months;
  const flat = (v: number): number[] => Array.from({ length: n + 1 }, () => v);
  const evalP = (p: OracleParams): number => {
    const f = simulate(market, oraclePolicy(p));
    return f.bankrupt ? -1e9 + equityOf(market, f) : equityOf(market, f);
  };
  let best: OracleParams | null = null;
  let bestV = -Infinity;
  for (const pm of [0.75, 0.85, 0.95, 1.05, 1.15, 1.3, 1.45, 1.6, 1.8])
    for (const mk of [0, 500, 1000, 2000, 3500, 5000, 8000])
      for (const loan of [false, true])
        for (const prebuy of [false, true]) {
          const p: OracleParams = { priceMult: flat(pm), marketing: flat(mk), loan, prebuy, fireSlack: 1 };
          const v = evalP(p);
          if (v > bestV) {
            bestV = v;
            best = p;
          }
        }
  const p = best!;
  for (const slack of [0, 2, 99]) {
    const v = evalP({ ...p, fireSlack: slack });
    if (v > bestV) {
      bestV = v;
      p.fireSlack = slack;
    }
  }
  for (let pass = 0; pass < 3; pass++) {
    let improved = false;
    for (let m = 1; m <= n; m++) {
      for (const f of [0.85, 0.93, 0.97, 1.03, 1.07, 1.15]) {
        const old = p.priceMult[m]!;
        p.priceMult[m] = round(old * f, 4);
        const v = evalP(p);
        if (v > bestV + 0.5) {
          bestV = v;
          improved = true;
        } else p.priceMult[m] = old;
      }
      for (const delta of [-2000, -1000, -500, 500, 1000, 2000, 4000]) {
        const old = p.marketing[m]!;
        const nv = Math.max(0, old + delta);
        if (nv === old) continue;
        p.marketing[m] = nv;
        const v = evalP(p);
        if (v > bestV + 0.5) {
          bestV = v;
          improved = true;
        } else p.marketing[m] = old;
      }
    }
    if (!improved) break;
  }
  const firm = simulate(market, oraclePolicy(p));
  return {
    equity: equityOf(market, firm),
    firm,
    plan: firm.history.map((h) => ({ month: h.month, price: h.price, produce: h.produced, marketing: h.marketing, hire: h.hires, loan: h.loanIn > 0 })),
  };
}
