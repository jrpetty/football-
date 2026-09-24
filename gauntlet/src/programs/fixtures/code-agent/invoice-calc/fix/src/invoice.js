'use strict';

const { toCents } = require('./money');
const { discountFor } = require('./discounts');
const { taxFor } = require('./tax');

function lineTotal(item) {
  if (!Number.isInteger(item.qty) || item.qty <= 0) {
    throw new RangeError(`Invalid quantity for ${item.sku}: ${item.qty}`);
  }
  return toCents(item.price) * item.qty;
}

/**
 * Build an invoice from basket items.
 * items: [{ sku, description?, price, qty }]
 * options: { region, discount?, shipping? }
 */
function buildInvoice(items, { region, discount = null, shipping = 0 } = {}) {
  if (!Array.isArray(items) || items.length === 0) throw new Error('An invoice needs at least one item');
  const lines = items.map((item) => ({
    sku: item.sku,
    description: item.description ?? item.sku,
    qty: item.qty,
    unitCents: toCents(item.price),
    totalCents: lineTotal(item),
  }));
  const subtotal = lines.reduce((sum, line) => sum + line.totalCents, 0);
  const discountCents = discountFor(subtotal, discount);
  const tax = taxFor(subtotal - discountCents, region);
  const shippingCents = toCents(shipping);
  const total = subtotal - discountCents + tax + shippingCents;
  return { region, lines, subtotal, discount: discountCents, tax, shipping: shippingCents, total };
}

module.exports = { lineTotal, buildInvoice };
