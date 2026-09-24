'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createLedger } = require('../src');

test('a month of savings interest', async () => {
  const ledger = createLedger();
  await ledger.open({ id: 'save', currency: 'USD', savingsRatePct: 3.65 });
  await ledger.deposit('save', 1_000_000, '2025-03-01');
  const entry = await ledger.accrueInterest('save', 2025, 3);
  assert.equal(entry.amount, 3_100);
  assert.equal(entry.date, '2025-03-31');
});

test('a mid-month deposit earns from the day it arrives', async () => {
  const ledger = createLedger();
  await ledger.open({ id: 'save', currency: 'USD', savingsRatePct: 3.65 });
  await ledger.deposit('save', 500_000, '2025-03-11');
  const entry = await ledger.accrueInterest('save', 2025, 3);
  assert.equal(entry.amount, 1_050);
});
