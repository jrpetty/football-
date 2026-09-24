'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createService, tierFor, priceLine, createOrder, DEFAULT_OPTIONS, OutOfStockError } = require('../src');

async function setup(stock = { BOLT: 500, GLUE: 20, TAPE: 10 }) {
  const svc = createService();
  svc.catalog.add({ sku: 'BOLT', name: 'Hex bolt', category: 'hardware', priceCents: 40 });
  svc.catalog.add({ sku: 'GLUE', name: 'Wood glue', category: 'supplies', priceCents: 350 });
  svc.catalog.add({ sku: 'TAPE', name: 'Duct tape', category: 'supplies', priceCents: 333 });
  for (const [sku, qty] of Object.entries(stock)) if (qty) await svc.stock.receive(sku, qty);
  return svc;
}

async function invariants(svc, sku) {
  const onHand = await svc.stock.onHand(sku);
  const reserved = await svc.reservations.reserved(sku);
  const live = svc.listOrders().filter((o) => o.status === 'placed');
  const expected = live.reduce((n, o) => n + o.lines.filter((l) => l.sku === sku).reduce((a, l) => a + l.qty, 0), 0);
  assert.equal(reserved, expected, `${sku}: reserved counter matches the live orders`);
  assert.ok(reserved <= onHand, `${sku}: reserved ${reserved} exceeds on hand ${onHand}`);
}

test('bulk tiers by quantity', () => {
  assert.deepEqual([1, 9, 10, 49, 50, 500].map((q) => tierFor(q).discountPct), [0, 0, 5, 5, 12, 12]);
});

test('product-specific tiers', () => {
  const tiers = [
    { min: 1, discountPct: 0 },
    { min: 5, discountPct: 10 },
    { min: 20, discountPct: 25 },
  ];
  assert.equal(tierFor(4, tiers).discountPct, 0);
  assert.equal(tierFor(5, tiers).discountPct, 10);
  assert.equal(tierFor(20, tiers).discountPct, 25);
  const line = priceLine({ sku: 'X', priceCents: 1000, tiers }, 20);
  assert.equal(line.netCents, 15000);
});

test('line discounts round half away from zero', () => {
  const line = priceLine({ sku: 'TAPE', priceCents: 333, tiers: null }, 10);
  assert.equal(line.grossCents, 3330);
  assert.equal(line.discountCents, 167);
  assert.equal(line.netCents, 3163);
});

test('order totals with mixed tiers', async () => {
  const svc = await setup();
  const order = await svc.placeOrder('o-1', 'ACME', [
    { sku: 'BOLT', qty: 50 },
    { sku: 'GLUE', qty: 10 },
    { sku: 'TAPE', qty: 1 },
  ]);
  assert.equal(order.totalCents, 1760 + 3325 + 333);
  assert.equal(order.priced.discountCents, 240 + 175);
});

test('options of one order never leak into the next', () => {
  const lines = [{ sku: 'GLUE', qty: 1 }];
  const a = createOrder('a', 'ACME', lines, { priority: true, giftWrap: true });
  const b = createOrder('b', 'ACME', lines);
  assert.equal(a.priority, true);
  assert.equal(b.priority, false);
  assert.equal(b.giftWrap, false);
  assert.deepEqual(DEFAULT_OPTIONS, { priority: false, giftWrap: false, tags: [] });
});

test('the bulk tag belongs to bulk orders only', () => {
  const bulk = createOrder('a', 'ACME', [{ sku: 'BOLT', qty: 80 }]);
  const small = createOrder('b', 'ACME', [{ sku: 'BOLT', qty: 8 }]);
  assert.deepEqual(bulk.tags, ['bulk']);
  assert.deepEqual(small.tags, []);
  assert.deepEqual(DEFAULT_OPTIONS.tags, []);
});

test("a caller's tags array is not modified", () => {
  const tags = ['vip'];
  const order = createOrder('a', 'ACME', [{ sku: 'BOLT', qty: 60 }], { tags });
  assert.deepEqual(order.tags, ['vip', 'bulk']);
  assert.deepEqual(tags, ['vip']);
  assert.deepEqual(createOrder('b', 'ACME', [{ sku: 'BOLT', qty: 1 }], { tags }).tags, ['vip']);
});

test('five concurrent single-unit orders for three units', async () => {
  const svc = await setup({ GLUE: 3 });
  const results = await Promise.allSettled([1, 2, 3, 4, 5].map((i) => svc.placeOrder(`o-${i}`, 'ACME', [{ sku: 'GLUE', qty: 1 }])));
  assert.equal(results.filter((r) => r.status === 'fulfilled').length, 3);
  for (const r of results.filter((x) => x.status === 'rejected')) assert.ok(r.reason instanceof OutOfStockError);
  assert.equal(await svc.reservations.available('GLUE'), 0);
  await invariants(svc, 'GLUE');
});

test('concurrent multi-line orders are all-or-nothing', async () => {
  const svc = await setup({ BOLT: 4, GLUE: 4 });
  await Promise.allSettled([
    svc.placeOrder('o-1', 'A', [{ sku: 'BOLT', qty: 3 }, { sku: 'GLUE', qty: 1 }]),
    svc.placeOrder('o-2', 'B', [{ sku: 'BOLT', qty: 2 }, { sku: 'GLUE', qty: 2 }]),
    svc.placeOrder('o-3', 'C', [{ sku: 'GLUE', qty: 3 }]),
    svc.placeOrder('o-4', 'D', [{ sku: 'BOLT', qty: 1 }, { sku: 'GLUE', qty: 1 }]),
  ]);
  await invariants(svc, 'BOLT');
  await invariants(svc, 'GLUE');
});

test('a busy afternoon of concurrent orders and cancellations', async () => {
  const svc = await setup({ TAPE: 10 });
  await svc.placeOrder('seed-1', 'X', [{ sku: 'TAPE', qty: 2 }]);
  await svc.placeOrder('seed-2', 'X', [{ sku: 'TAPE', qty: 3 }]);
  const qty = [1, 4, 2, 3, 1, 2, 4, 1];
  await Promise.allSettled([
    ...qty.map((q, i) => svc.placeOrder(`p-${i}`, 'Y', [{ sku: 'TAPE', qty: q }])),
    svc.cancelOrder('seed-1'),
    svc.cancelOrder('seed-2'),
  ]);
  await invariants(svc, 'TAPE');
  assert.ok((await svc.reservations.available('TAPE')) >= 0);
});

test('fulfilling ships the goods and clears the reservation', async () => {
  const svc = await setup({ GLUE: 10 });
  await svc.placeOrder('o-1', 'ACME', [{ sku: 'GLUE', qty: 4 }]);
  await svc.fulfilOrder('o-1');
  assert.equal(await svc.stock.onHand('GLUE'), 6);
  assert.equal(await svc.reservations.reserved('GLUE'), 0);
  assert.equal(await svc.reservations.available('GLUE'), 6);
  await assert.rejects(svc.cancelOrder('o-1'), /cannot go from fulfilled to cancelled/);
});

test('sales report with priority, bulk and cancelled orders', async () => {
  const svc = await setup();
  await svc.placeOrder('o-1', 'A', [{ sku: 'TAPE', qty: 10 }], { priority: true });
  await svc.placeOrder('o-2', 'B', [{ sku: 'BOLT', qty: 100 }]);
  await svc.placeOrder('o-3', 'C', [{ sku: 'GLUE', qty: 1 }], { giftWrap: true });
  await svc.placeOrder('o-4', 'D', [{ sku: 'BOLT', qty: 55 }], { priority: true });
  await svc.cancelOrder('o-4');
  const report = svc.salesReport();
  assert.deepEqual(report, {
    orders: 3,
    revenueCents: 3163 + 3520 + 350,
    byCategory: { supplies: 3163 + 350, hardware: 3520 },
    priorityOrders: 1,
    bulkOrders: 1,
  });
});

test('events and low-stock report', async () => {
  const svc = await setup({ BOLT: 5, GLUE: 20, TAPE: 12 });
  const seen = [];
  svc.events.on('order.placed', (e) => seen.push(e));
  await svc.placeOrder('o-1', 'A', [{ sku: 'GLUE', qty: 2 }]);
  assert.deepEqual(seen, [{ id: 'o-1', totalCents: 700 }]);
  await svc.placeOrder('o-2', 'B', [{ sku: 'TAPE', qty: 7 }]);
  assert.deepEqual(await svc.lowStockReport(6), [
    { sku: 'BOLT', available: 5 },
    { sku: 'TAPE', available: 5 },
  ]);
});
