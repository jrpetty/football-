'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { toCents, percentOf, formatMoney } = require('../src/money');

test('toCents converts whole amounts', () => {
  assert.equal(toCents(12), 1200);
  assert.equal(toCents(0), 0);
});

test('toCents rounds to the nearest cent', () => {
  assert.equal(toCents(19.99), 1999);
  assert.equal(toCents(0.29), 29);
});

test('percentOf rounds half away from zero', () => {
  assert.equal(percentOf(250, 10), 25);
  assert.equal(percentOf(5, 50), 3);
  assert.equal(percentOf(-5, 50), -3);
});

test('formatMoney groups thousands', () => {
  assert.equal(formatMoney(123456), '$1,234.56');
  assert.equal(formatMoney(5, 'GBP'), '£0.05');
});
