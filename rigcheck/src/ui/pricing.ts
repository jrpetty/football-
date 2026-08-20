import newPrices from '../../data/pricing/gbp-new.json';
import usedPrices from '../../data/pricing/gbp-used.json';
import observedPrices from '../../data/pricing/observed.json';

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
export function loadPrices(): {
  newP: PriceTable;
  usedP: PriceTable;
  override: Record<string, number>;
  observed: ObservedPrice[];
} {
  let override: Record<string, number> = {};
  try {
    const raw = localStorage.getItem('rigcheck.priceOverride');
    if (raw) override = JSON.parse(raw) as Record<string, number>;
  } catch {
    override = {};
  }
  return {
    newP: newPrices as PriceTable,
    usedP: usedPrices as PriceTable,
    override,
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
    if (p.override[id] != null) return { value: p.override[id], origin: 'operator-override' };
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
  for (const [id, v] of Object.entries(p.override)) {
    newP[id] = v;
    usedP[id] = v;
  }
  return { newP, usedP };
}

/** How much of the planner's price data is sourced rather than recalled. */
export function priceCoverage(p: ReturnType<typeof loadPrices>): {
  sourced: number;
  seeded: number;
  sourcedShare: number;
  staleCount: number;
} {
  const seeded = new Set([...Object.keys(p.newP.prices), ...Object.keys(p.usedP.prices)]);
  const sourced = new Set(p.observed.map((o) => o.partId));
  return {
    sourced: sourced.size,
    seeded: seeded.size,
    sourcedShare: seeded.size ? sourced.size / seeded.size : 0,
    staleCount: p.observed.filter((o) => o.stale).length,
  };
}
