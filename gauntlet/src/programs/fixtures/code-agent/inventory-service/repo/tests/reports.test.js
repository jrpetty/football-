'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { setup } = require('./helpers');

async function busyDay() {
  const svc = await setup({ bolts: 500, glue: 20 });
  await svc.placeOrder('o-1', 'ACME', [{ sku: 'BOLT', qty: 60 }], { priority: true });
  await svc.placeOrder('o-2', 'Globex', [{ sku: 'GLUE', qty: 2 }]);
  await svc.placeOrder('o-3', 'Initech', [{ sku: 'BOLT', qty: 12 }]);
  await svc.placeOrder('o-4', 'Umbrella', [{ sku: 'GLUE', qty: 1 }]);
  await svc.cancelOrder('o-4');
  return svc;
}

test('sales report: revenue by category after bulk discounts', async () => {
  const report = (await busyDay()).salesReport();
  assert.equal(report.orders, 3);
  assert.deepEqual(report.byCategory, { hardware: 2568, supplies: 700 });
  assert.equal(report.revenueCents, 3268);
});

test('sales report: priority and bulk order counts', async () => {
  const report = (await busyDay()).salesReport();
  assert.equal(report.priorityOrders, 1);
  assert.equal(report.bulkOrders, 1);
});

test('low stock report lists the scarcest items first', async () => {
  const svc = await setup({ bolts: 8, glue: 20 });
  await svc.placeOrder('o-1', 'ACME', [{ sku: 'GLUE', qty: 15 }]);
  assert.deepEqual(await svc.lowStockReport(10), [
    { sku: 'GLUE', available: 5 },
    { sku: 'BOLT', available: 8 },
  ]);
});
