'use strict';

const { percentOf, sum } = require('./money');

/** Bulk tiers, sorted by `min`. */
const DEFAULT_TIERS = [
  { min: 1, discountPct: 0 },
  { min: 10, discountPct: 5 },
  { min: 50, discountPct: 12 },
];

/** The tier that applies to a quantity. */
function tierFor(qty, tiers = DEFAULT_TIERS) {
  return tiers.find((tier) => qty >= tier.min) ?? { min: 0, discountPct: 0 };
}

/** Price one order line. */
function priceLine(product, qty) {
  const gross = product.priceCents * qty;
  const tier = tierFor(qty, product.tiers ?? DEFAULT_TIERS);
  const discount = percentOf(gross, tier.discountPct);
  return { sku: product.sku, qty, unitCents: product.priceCents, grossCents: gross, discountPct: tier.discountPct, discountCents: discount, netCents: gross - discount };
}

/** Price every line of an order. */
function priceOrder(catalog, lines) {
  const priced = lines.map((line) => priceLine(catalog.get(line.sku), line.qty));
  return { lines: priced, totalCents: sum(priced.map((l) => l.netCents)), discountCents: sum(priced.map((l) => l.discountCents)) };
}

module.exports = { DEFAULT_TIERS, tierFor, priceLine, priceOrder };
