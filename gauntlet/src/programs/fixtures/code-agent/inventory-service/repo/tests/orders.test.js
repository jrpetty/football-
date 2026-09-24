'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { OutOfStockError } = require('../src');
const { setup } = require('./helpers');

test('placing an order reserves stock', async () => {
  const svc = await setup({ glue: 20 });
  await svc.placeOrder('o-1', 'ACME', [{ sku: 'GLUE', qty: 3 }]);
  assert.equal(await svc.reservations.available('GLUE'), 17);
  assert.equal(await svc.stock.onHand('GLUE'), 20);
});

test('cancelling an order releases its reservation', async () => {
  const svc = await setup({ glue: 20 });
  await svc.placeOrder('o-1', 'ACME', [{ sku: 'GLUE', qty: 3 }]);
  await svc.cancelOrder('o-1');
  assert.equal(await svc.reservations.available('GLUE'), 20);
});

test('an order that cannot be filled reserves nothing', async () => {
  const svc = await setup({ bolts: 100, glue: 5 });
  await assert.rejects(
    svc.placeOrder('o-1', 'ACME', [
      { sku: 'BOLT', qty: 2 },
      { sku: 'GLUE', qty: 6 },
    ]),
    OutOfStockError,
  );
  assert.equal(await svc.reservations.available('BOLT'), 100);
});

test('concurrent orders cannot oversell', async () => {
  const svc = await setup({ glue: 3 });
  const results = await Promise.allSettled([
    svc.placeOrder('o-1', 'ACME', [{ sku: 'GLUE', qty: 2 }]),
    svc.placeOrder('o-2', 'Globex', [{ sku: 'GLUE', qty: 2 }]),
  ]);
  assert.equal(results.filter((r) => r.status === 'fulfilled').length, 1);
  assert.equal(await svc.reservations.available('GLUE'), 1);
});

test('small orders pay full price', async () => {
  const svc = await setup();
  const order = await svc.placeOrder('o-1', 'ACME', [{ sku: 'GLUE', qty: 3 }]);
  assert.equal(order.totalCents, 1050);
});

test('receive and ship change the quantity on hand', async () => {
  const svc = await setup({ bolts: 0, glue: 0 });
  await svc.stock.receive('BOLT', 30);
  await svc.stock.receive('BOLT', 12);
  await svc.stock.ship('BOLT', 5);
  assert.equal(await svc.stock.onHand('BOLT'), 37);
});

test('cannot ship more than is on hand', async () => {
  const svc = await setup({ bolts: 3 });
  await assert.rejects(svc.stock.ship('BOLT', 4), RangeError);
});
