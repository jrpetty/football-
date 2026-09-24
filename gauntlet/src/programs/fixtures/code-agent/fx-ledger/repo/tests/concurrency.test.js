'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createLedger } = require('../src');

// These tests fire many transfers at once. The storage layer yields between
// every read and write, so without the per-account locks they would lose
// updates. They look timing-dependent, but the locks make them deterministic.

test('transfers in both directions at once conserve money', async () => {
  const ledger = createLedger();
  await ledger.open({ id: 'a', currency: 'USD' });
  await ledger.open({ id: 'b', currency: 'USD' });
  await ledger.deposit('a', 10_000, '2025-03-01');
  await ledger.deposit('b', 10_000, '2025-03-01');
  const jobs = [];
  for (let i = 0; i < 20; i++) {
    jobs.push(i % 2 ? ledger.transfer({ from: 'a', to: 'b', amount: 700, date: '2025-03-02' }) : ledger.transfer({ from: 'b', to: 'a', amount: 500, date: '2025-03-02' }));
  }
  const results = await Promise.allSettled(jobs);
  const ok = results.filter((r) => r.status === 'fulfilled').length;
  const a = await ledger.balance('a');
  const b = await ledger.balance('b');
  assert.equal(a + b, 20_000);
  assert.ok(a >= 0 && b >= 0);
  assert.equal(ledger.journal.entries.length, 2 + 2 * ok);
});

test('concurrent spending never overdraws', async () => {
  const ledger = createLedger();
  await ledger.open({ id: 'a', currency: 'USD' });
  await ledger.open({ id: 'b', currency: 'USD' });
  await ledger.deposit('a', 1_000, '2025-03-01');
  const results = await Promise.allSettled(Array.from({ length: 5 }, () => ledger.transfer({ from: 'a', to: 'b', amount: 300, date: '2025-03-02' })));
  assert.equal(results.filter((r) => r.status === 'fulfilled').length, 3);
  assert.equal(await ledger.balance('a'), 100);
});
