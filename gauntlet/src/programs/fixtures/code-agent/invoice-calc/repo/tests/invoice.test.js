'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { buildInvoice, lineTotal, renderInvoice } = require('../src');

const BASKET = [
  { sku: 'MUG', description: 'Coffee mug', price: 12.5, qty: 2 },
  { sku: 'TEA', description: 'Green tea', price: 4.99, qty: 1 },
];

test('line total multiplies unit cents by quantity', () => {
  assert.equal(lineTotal({ sku: 'PEN', price: 19.99, qty: 3 }), 5997);
});

test('invoice without a discount', () => {
  const inv = buildInvoice(BASKET, { region: 'UK', shipping: 3.95 });
  assert.equal(inv.subtotal, 2999);
  assert.equal(inv.discount, 0);
  assert.equal(inv.tax, 600);
  assert.equal(inv.shipping, 395);
  assert.equal(inv.total, 3994);
});

test('a percent discount is applied before tax', () => {
  const inv = buildInvoice(BASKET, { region: 'UK', discount: { type: 'percent', value: 10 }, shipping: 3.95 });
  assert.equal(inv.discount, 300);
  assert.equal(inv.tax, 540);
  assert.equal(inv.total, 3634);
});

test('rejects zero quantities', () => {
  assert.throws(() => buildInvoice([{ sku: 'MUG', price: 12.5, qty: 0 }], { region: 'UK' }), RangeError);
});

test('renderInvoice ends with the total', () => {
  const text = renderInvoice(buildInvoice(BASKET, { region: 'UK', shipping: 3.95 }));
  assert.match(text, /TOTAL\s+\$39\.94$/);
});
