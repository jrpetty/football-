'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createLedger, convert, renderReceipt, InsufficientFundsError } = require('../src');

async function setup() {
  const ledger = createLedger();
  await ledger.open({ id: 'alice-usd', currency: 'USD' });
  await ledger.open({ id: 'bob-usd', currency: 'USD', overdraftLimit: 1000 });
  await ledger.open({ id: 'carol-eur', currency: 'EUR' });
  await ledger.open({ id: 'dan-jpy', currency: 'JPY' });
  await ledger.open({ id: 'erin-gbp', currency: 'GBP' });
  return ledger;
}

test('conversion with a directly quoted pair', () => {
  assert.equal(convert(10_000, 'EUR', 'USD'), 10_800);
  assert.equal(convert(1_000, 'USD', 'JPY'), 1_500);
  assert.equal(convert(10_000, 'GBP', 'USD'), 12_700);
  assert.equal(convert(10_000, 'EUR', 'GBP'), 8_500);
});

test('conversion against the quoted direction divides', () => {
  assert.equal(convert(10_000, 'USD', 'EUR'), 9_259);
  assert.equal(convert(1_500, 'JPY', 'USD'), 1_000);
  assert.equal(convert(12_700, 'USD', 'GBP'), 10_000);
  assert.equal(convert(8_500, 'GBP', 'EUR'), 10_000);
});

test('cross conversion through USD, rounded once', () => {
  assert.equal(convert(10_000, 'GBP', 'JPY'), 19_050);
  assert.equal(convert(19_050, 'JPY', 'GBP'), 10_000);
  assert.equal(convert(10_000, 'EUR', 'JPY'), 16_200);
  assert.equal(convert(16_200, 'JPY', 'EUR'), 10_000);
  assert.equal(convert(7, 'JPY', 'EUR'), 4);
});

test('the FX fee is 0.5% of the converted amount', async () => {
  const ledger = await setup();
  await ledger.deposit('alice-usd', 100_000, '2025-03-01');
  const t = await ledger.transfer({ from: 'alice-usd', to: 'dan-jpy', amount: 1_000, date: '2025-03-02' });
  assert.equal(t.fee, 8);
  assert.equal(t.received, 1_492);
  assert.equal(renderReceipt(t, 'USD', 'JPY'), `${t.ref}: sent 10.00 USD, received 1,492 JPY (fee 8 JPY)`);
  await ledger.deposit('carol-eur', 50_000, '2025-03-01');
  const u = await ledger.transfer({ from: 'carol-eur', to: 'alice-usd', amount: 10_000, date: '2025-03-03' });
  assert.equal(u.fee, 54);
  assert.equal(u.received, 10_746);
});

test('money received in euros', async () => {
  const ledger = await setup();
  await ledger.deposit('alice-usd', 100_000, '2025-03-01');
  await ledger.transfer({ from: 'alice-usd', to: 'carol-eur', amount: 10_000, date: '2025-03-02' });
  await ledger.transfer({ from: 'alice-usd', to: 'carol-eur', amount: 5_000, date: '2025-03-05' });
  await ledger.transfer({ from: 'erin-gbp', to: 'carol-eur', amount: 1, date: '2025-03-06' }).catch(() => null);
  const s = ledger.statement('carol-eur', '2025-03-01', '2025-03-31');
  assert.deepEqual(s.lines.map((l) => l.amount), [9_213, 4_607]);
  assert.equal(await ledger.balance('carol-eur'), 13_820);
  assert.equal(await ledger.balance('alice-usd'), 85_000);
});

test('a round trip loses exactly the two fees', async () => {
  const ledger = await setup();
  await ledger.deposit('erin-gbp', 10_000, '2025-03-01');
  const out = await ledger.transfer({ from: 'erin-gbp', to: 'dan-jpy', amount: 10_000, date: '2025-03-02' });
  assert.equal(out.received, 19_050 - 95);
  const back = await ledger.transfer({ from: 'dan-jpy', to: 'erin-gbp', amount: out.received, date: '2025-03-03' });
  assert.equal(back.received, 9_950 - 50);
});

test('a retry that arrives while the first attempt is running happens once', async () => {
  const ledger = await setup();
  await ledger.deposit('alice-usd', 50_000, '2025-03-01');
  const request = { from: 'alice-usd', to: 'bob-usd', amount: 2_000, date: '2025-03-02', idempotencyKey: 'k-1' };
  const [a, b] = await Promise.all([ledger.transfer(request), ledger.transfer(request)]);
  assert.deepEqual(a, b);
  assert.equal(await ledger.balance('alice-usd'), 48_000);
  assert.equal(await ledger.balance('bob-usd'), 2_000);
  assert.equal(ledger.journal.byRef(a.ref).length, 2);
});

test('three concurrent retries and a late one', async () => {
  const ledger = await setup();
  await ledger.deposit('alice-usd', 50_000, '2025-03-01');
  const request = { from: 'alice-usd', to: 'carol-eur', amount: 10_000, date: '2025-03-02', idempotencyKey: 'k-2' };
  const results = await Promise.all([ledger.transfer(request), ledger.transfer(request), ledger.transfer(request)]);
  const late = await ledger.transfer(request);
  assert.equal(new Set([...results, late].map((r) => r.ref)).size, 1);
  assert.equal(await ledger.balance('alice-usd'), 40_000);
});

test('a concurrent retry must not be refused for lack of funds', async () => {
  const ledger = await setup();
  await ledger.deposit('alice-usd', 1_500, '2025-03-01');
  const request = { from: 'alice-usd', to: 'bob-usd', amount: 1_000, date: '2025-03-02', idempotencyKey: 'k-3' };
  const settled = await Promise.allSettled([ledger.transfer(request), ledger.transfer(request)]);
  assert.deepEqual(settled.map((s) => s.status), ['fulfilled', 'fulfilled']);
  assert.equal(await ledger.balance('alice-usd'), 500);
});

test('different keys are different transfers', async () => {
  const ledger = await setup();
  await ledger.deposit('alice-usd', 50_000, '2025-03-01');
  await Promise.all([
    ledger.transfer({ from: 'alice-usd', to: 'bob-usd', amount: 1_000, date: '2025-03-02', idempotencyKey: 'x' }),
    ledger.transfer({ from: 'alice-usd', to: 'bob-usd', amount: 1_000, date: '2025-03-02', idempotencyKey: 'y' }),
  ]);
  assert.equal(await ledger.balance('bob-usd'), 2_000);
});

test('concurrent retries of a failing transfer both fail, then the key is free again', async () => {
  const ledger = await setup();
  const request = { from: 'alice-usd', to: 'bob-usd', amount: 3_000, date: '2025-03-02', idempotencyKey: 'k-4' };
  const settled = await Promise.allSettled([ledger.transfer(request), ledger.transfer(request)]);
  assert.deepEqual(settled.map((s) => s.status), ['rejected', 'rejected']);
  assert.ok(settled[0].reason instanceof InsufficientFundsError);
  await ledger.deposit('alice-usd', 3_000, '2025-03-03');
  await ledger.transfer(request);
  assert.equal(await ledger.balance('bob-usd'), 3_000);
});

test('interest on a balance that changes during the month', async () => {
  const ledger = createLedger();
  await ledger.open({ id: 'save', currency: 'EUR', savingsRatePct: 3.65 });
  await ledger.open({ id: 'cur', currency: 'EUR' });
  await ledger.deposit('save', 1_000_000, '2025-02-01');
  await ledger.transfer({ from: 'save', to: 'cur', amount: 500_000, date: '2025-02-15' });
  const entry = await ledger.accrueInterest('save', 2025, 2);
  assert.equal(entry.amount, 14 * 100 + 14 * 50);
  assert.equal(entry.date, '2025-02-28');
});
