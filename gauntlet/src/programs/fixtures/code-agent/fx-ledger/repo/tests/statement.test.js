'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { renderStatement } = require('../src');
const { setup } = require('./helpers');

test('USD statement with opening and running balances', async () => {
  const ledger = await setup();
  await ledger.deposit('alice-usd', 20_000, '2025-02-27');
  await ledger.deposit('alice-usd', 5_000, '2025-03-03');
  await ledger.transfer({ from: 'alice-usd', to: 'bob-usd', amount: 7_500, date: '2025-03-10' });
  await ledger.transfer({ from: 'bob-usd', to: 'alice-usd', amount: 500, date: '2025-04-02' });
  const s = ledger.statement('alice-usd', '2025-03-01', '2025-03-31');
  assert.equal(s.opening, 20_000);
  assert.deepEqual(s.lines.map((l) => [l.date, l.amount, l.balance]), [
    ['2025-03-03', 5_000, 25_000],
    ['2025-03-10', -7_500, 17_500],
  ]);
  assert.equal(s.closing, 17_500);
  assert.match(renderStatement(s), /Closing balance 175\.00 USD/);
});

test('EUR statement for money received from a USD account', async () => {
  const ledger = await setup();
  await ledger.deposit('alice-usd', 100_000, '2025-03-01');
  await ledger.transfer({ from: 'alice-usd', to: 'carol-eur', amount: 10_000, date: '2025-03-02' });
  await ledger.transfer({ from: 'alice-usd', to: 'carol-eur', amount: 5_000, date: '2025-03-05' });
  const s = ledger.statement('carol-eur', '2025-03-01', '2025-03-31');
  assert.deepEqual(s.lines.map((l) => l.amount), [9_213, 4_607]);
  assert.equal(s.closing, 13_820);
});
