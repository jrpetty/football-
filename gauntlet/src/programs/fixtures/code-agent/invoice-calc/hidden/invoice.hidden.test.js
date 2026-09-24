'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { toCents, fromCents, formatMoney, buildInvoice, lineTotal, renderInvoice } = require('../src');

test('toCents: amounts that are not exact in binary', () => {
  assert.equal(toCents(1.15), 115);
  assert.equal(toCents(4.35), 435);
  assert.equal(toCents(8.2), 820);
  assert.equal(toCents(0.07), 7);
});

test('toCents/fromCents round trip', () => {
  for (const x of [19.99, 0.29, 1.15, 4.35, 8.2, 100]) assert.equal(fromCents(toCents(x)), x);
});

test('lineTotal with awkward prices', () => {
  assert.equal(lineTotal({ sku: 'A', price: 1.15, qty: 4 }), 460);
  assert.equal(lineTotal({ sku: 'B', price: 0.29, qty: 7 }), 203);
});

test('fixed discount with a non-binary amount', () => {
  const inv = buildInvoice([{ sku: 'X', price: 20, qty: 1 }], { region: 'US-OR', discount: { type: 'fixed', value: 4.35 } });
  assert.equal(inv.discount, 435);
  assert.equal(inv.total, 1565);
});

test('a fixed discount larger than the subtotal leaves nothing to tax', () => {
  const inv = buildInvoice([{ sku: 'X', price: 10, qty: 1 }], { region: 'UK', discount: { type: 'fixed', value: 50 }, shipping: 2.5 });
  assert.equal(inv.discount, 1000);
  assert.equal(inv.tax, 0);
  assert.equal(inv.total, 250);
});

test('tax on the discounted subtotal in New York', () => {
  const inv = buildInvoice([{ sku: 'LAMP', price: 80, qty: 1 }], { region: 'US-NY', discount: { type: 'percent', value: 10 } });
  assert.equal(inv.discount, 800);
  assert.equal(inv.tax, 639);
  assert.equal(inv.total, 7839);
});

test('a 100% discount means no tax', () => {
  const inv = buildInvoice([{ sku: 'GIFT', price: 25, qty: 2 }], { region: 'DE', discount: { type: 'percent', value: 100 }, shipping: 4 });
  assert.equal(inv.tax, 0);
  assert.equal(inv.total, 400);
});

test('zero-rate region with a discount', () => {
  const inv = buildInvoice([{ sku: 'BOOK', price: 30, qty: 1 }], { region: 'US-OR', discount: { type: 'percent', value: 25 } });
  assert.equal(inv.tax, 0);
  assert.equal(inv.total, 2250);
});

test('shipping is never taxed', () => {
  const inv = buildInvoice([{ sku: 'CUP', price: 10, qty: 1 }], { region: 'UK', shipping: 5 });
  assert.equal(inv.tax, 200);
  assert.equal(inv.total, 1700);
});

test('mixed basket in Germany', () => {
  const inv = buildInvoice(
    [
      { sku: 'CLIP', price: 0.29, qty: 3 },
      { sku: 'TAPE', price: 1.15, qty: 2 },
      { sku: 'FILE', price: 8.2, qty: 1 },
    ],
    { region: 'DE' },
  );
  assert.equal(inv.subtotal, 1137);
  assert.equal(inv.tax, 216);
  assert.equal(inv.total, 1353);
});

test('discount, tax and shipping together (the README example)', () => {
  const inv = buildInvoice(
    [
      { sku: 'MUG', description: 'Coffee mug', price: 12.5, qty: 2 },
      { sku: 'TEA', description: 'Green tea', price: 4.99, qty: 1 },
    ],
    { region: 'UK', discount: { type: 'percent', value: 10 }, shipping: 3.95 },
  );
  const text = renderInvoice(inv);
  assert.match(text, /Discount\s+-\$3\.00/);
  assert.match(text, /Tax \(UK\)\s+\$5\.40/);
  assert.match(text, /TOTAL\s+\$36\.34$/);
});

test('invalid discounts and regions are rejected', () => {
  assert.throws(() => buildInvoice([{ sku: 'X', price: 1, qty: 1 }], { region: 'UK', discount: { type: 'percent', value: 120 } }), RangeError);
  assert.throws(() => buildInvoice([{ sku: 'X', price: 1, qty: 1 }], { region: 'MARS' }), /Unknown tax region/);
});

test('formatMoney negative amounts and other currencies', () => {
  assert.equal(formatMoney(-1999, 'EUR'), '-€19.99');
  assert.equal(formatMoney(100000000), '$1,000,000.00');
});
