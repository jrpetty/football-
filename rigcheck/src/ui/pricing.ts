import newPrices from '../../data/pricing/gbp-new.json';
import usedPrices from '../../data/pricing/gbp-used.json';

export interface PriceTable {
  currency: string;
  updated: string;
  prices: Record<string, number>;
}

/**
 * Pricing is an overlay, never baked into hardware records: prices are volatile
 * and regional while the performance database is stable. An operator override
 * file wins over both, because the operator sources parts at trade prices that
 * bear no relation to retail.
 */
export function loadPrices(): { newP: PriceTable; usedP: PriceTable; override: Record<string, number> } {
  let override: Record<string, number> = {};
  try {
    const raw = localStorage.getItem('rigcheck.priceOverride');
    if (raw) override = JSON.parse(raw) as Record<string, number>;
  } catch {
    override = {};
  }
  return { newP: newPrices as PriceTable, usedP: usedPrices as PriceTable, override };
}

export function priceLookup(p: ReturnType<typeof loadPrices>, prefer: 'used' | 'new' = 'used') {
  return (id: string): number | undefined => {
    if (p.override[id] != null) return p.override[id];
    const a = prefer === 'used' ? p.usedP.prices[id] : p.newP.prices[id];
    if (a != null) return a;
    const b = prefer === 'used' ? p.newP.prices[id] : p.usedP.prices[id];
    return b;
  };
}
