import newPrices from '../../data/pricing/gbp-new.json';
import usedPrices from '../../data/pricing/gbp-used.json';
import observedPrices from '../../data/pricing/observed.json';
import { priceOverlay, type UserData } from '../core/userdata.ts';
import { loadUserData } from './userdata.ts';

export interface PriceTable {
  currency: string;
  updated: string;
  prices: Record<string, number>;
}

export interface ObservedPrice {
  partId: string;
  condition: 'new' | 'used';
  price: number;
  observedMedian: number;
  currency: string;
  totalSamples: number;
  observations: number;
  spread: { low: number; high: number };
  newestDate: string;
  ageDays: number;
  stale: boolean;
  containsAsking: boolean;
  sources: string[];
  warnings: string[];
}

/** Where a price came from. Drives whether the UI presents it as evidence. */
export type PriceOrigin = 'operator-override' | 'observed' | 'recalled-seed' | 'none';

export interface PricedValue {
  value: number;
  origin: PriceOrigin;
  /** Present only for observed prices — the sourcing behind the figure. */
  observed?: ObservedPrice;
}

const observed = observedPrices as { prices: ObservedPrice[]; generatedAt?: string };

/**
 * Pricing is an overlay, never baked into hardware records: prices are volatile
 * and regional while the performance database is stable.
 *
 * Three tiers, best first. An operator override wins outright — someone typing
 * a number into the screen is stating what THEY can buy it for. Then observed
 * market data from data/prices-observed/, which is sourced. Last, the recalled
 * seed, which is a model's memory of mid-2026 street prices and is not
 * evidence of anything. The tier is returned alongside the number so a screen
 * can show the difference rather than presenting all three as equally solid.
 */
export function loadPrices(user?: UserData): {
  newP: PriceTable;
  usedP: PriceTable;
  /**
   * Prices the operator entered, split by condition.
   *
   * It used to be one flat map covering both, which meant a used-market figure
   * somebody typed also became the answer to "what does this cost new?" — and
   * on the Trade Desk, where the whole screen is the gap between those two
   * numbers, that quietly closed the gap to zero.
   */
  override: { new: Record<string, number>; used: Record<string, number> };
  observed: ObservedPrice[];
} {
  return {
    newP: newPrices as PriceTable,
    usedP: usedPrices as PriceTable,
    override: priceOverlay(user ?? loadUserData()),
    observed: observed.prices ?? [],
  };
}

export function findObserved(
  list: ObservedPrice[],
  id: string,
  condition: 'new' | 'used',
): ObservedPrice | undefined {
  return list.find((o) => o.partId === id && o.condition === condition);
}

/** Price with its provenance, for a screen that needs to show the difference. */
export function pricedLookup(p: ReturnType<typeof loadPrices>, prefer: 'used' | 'new' = 'used') {
  return (id: string): PricedValue => {
    // The operator's own figure for the condition being asked about wins
    // outright; their figure for the OTHER condition does not, because a used
    // price is not a new price. It falls through to the observed and seeded
    // tiers instead, which at least know which condition they describe.
    const mine = p.override[prefer][id];
    if (mine != null) return { value: mine, origin: 'operator-override' };
    const first = findObserved(p.observed, id, prefer);
    if (first) return { value: first.price, origin: 'observed', observed: first };
    const other = findObserved(p.observed, id, prefer === 'used' ? 'new' : 'used');
    if (other) return { value: other.price, origin: 'observed', observed: other };
    const a = prefer === 'used' ? p.usedP.prices[id] : p.newP.prices[id];
    if (a != null) return { value: a, origin: 'recalled-seed' };
    const b = prefer === 'used' ? p.newP.prices[id] : p.usedP.prices[id];
    if (b != null) return { value: b, origin: 'recalled-seed' };
    return { value: 0, origin: 'none' };
  };
}

/** Bare-number form, kept for callers that only need the figure. */
export function priceLookup(p: ReturnType<typeof loadPrices>, prefer: 'used' | 'new' = 'used') {
  const priced = pricedLookup(p, prefer);
  return (id: string): number | undefined => {
    const r = priced(id);
    return r.origin === 'none' ? undefined : r.value;
  };
}

/**
 * The planner takes flat id-to-number tables. Observed prices are merged over
 * the seed here so the planner needs no knowledge of provenance — it just gets
 * the best number available for each part.
 */
export function plannerTables(p: ReturnType<typeof loadPrices>): {
  newP: Record<string, number>;
  usedP: Record<string, number>;
} {
  const newP = { ...p.newP.prices };
  const usedP = { ...p.usedP.prices };
  for (const o of p.observed) {
    (o.condition === 'new' ? newP : usedP)[o.partId] = o.price;
  }
  for (const [id, v] of Object.entries(p.override.new)) newP[id] = v;
  for (const [id, v] of Object.entries(p.override.used)) usedP[id] = v;
  return { newP, usedP };
}

/** How much of the planner's price data is sourced rather than recalled. */
export function priceCoverage(p: ReturnType<typeof loadPrices>): {
  sourced: number;
  seeded: number;
  sourcedShare: number;
  staleCount: number;
  /** Parts the operator priced themselves. The strongest tier there is. */
  operator: number;
} {
  const seeded = new Set([...Object.keys(p.newP.prices), ...Object.keys(p.usedP.prices)]);
  const sourced = new Set(p.observed.map((o) => o.partId));
  const operator = new Set([...Object.keys(p.override.new), ...Object.keys(p.override.used)]);
  return {
    sourced: sourced.size,
    seeded: seeded.size,
    sourcedShare: seeded.size ? sourced.size / seeded.size : 0,
    staleCount: p.observed.filter((o) => o.stale).length,
    operator: operator.size,
  };
}

/** Every part that carries a price from any tier — what the planner can cost. */
export function pricedIds(p: ReturnType<typeof loadPrices>): Set<string> {
  return new Set([
    ...Object.keys(p.newP.prices),
    ...Object.keys(p.usedP.prices),
    ...p.observed.map((o) => o.partId),
    ...Object.keys(p.override.new),
    ...Object.keys(p.override.used),
  ]);
}
