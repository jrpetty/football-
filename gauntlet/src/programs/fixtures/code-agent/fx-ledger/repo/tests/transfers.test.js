'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { InsufficientFundsError } = require('../src');
const { setup } = require('./helpers');

test('a same-currency transfer moves the money and is free', async () => {
  const ledger = await setup();
  await ledger.deposit('alice-usd', 50_000, '2025-03-01');
  const t = await ledger.transfer({ from: 'alice-usd', to: 'bob-usd', amount: 12_345, date: '2025-03-02' });
  assert.equal(t.fee, 0);
  assert.equal(await ledger.balance('alice-usd'), 37_655);
  assert.equal(await ledger.balance('bob-usd'), 12_345);
});

test('transfers cannot go past the overdraft limit', async () => {
  const ledger = await setup();
  await ledger.deposit('alice-usd', 1_000, '2025-03-01');
  await assert.rejects(ledger.transfer({ from: 'alice-usd', to: 'bob-usd', amount: 1_500, date: '2025-03-02' }), InsufficientFundsError);
  await ledger.transfer({ from: 'bob-usd', to: 'alice-usd', amount: 1_000, date: '2025-03-02' });
  assert.equal(await ledger.balance('bob-usd'), -1_000);
  await assert.rejects(ledger.transfer({ from: 'bob-usd', to: 'alice-usd', amount: 1, date: '2025-03-02' }), InsufficientFundsError);
});

test('a retried transfer with the same idempotency key happens once', async () => {
  const ledger = await setup();
  await ledger.deposit('alice-usd', 50_000, '2025-03-01');
  const request = { from: 'alice-usd', to: 'bob-usd', amount: 2_000, date: '2025-03-02', idempotencyKey: 'order-81' };
  const first = await ledger.transfer(request);
  const again = await ledger.transfer(request);
  assert.deepEqual(again, first);
  assert.equal(await ledger.balance('alice-usd'), 48_000);
});

test('a failed transfer can be retried with the same key', async () => {
  const ledger = await setup();
  const request = { from: 'alice-usd', to: 'bob-usd', amount: 2_000, date: '2025-03-02', idempotencyKey: 'order-82' };
  await assert.rejects(ledger.transfer(request), InsufficientFundsError);
  await ledger.deposit('alice-usd', 5_000, '2025-03-02');
  await ledger.transfer(request);
  assert.equal(await ledger.balance('bob-usd'), 2_000);
});
