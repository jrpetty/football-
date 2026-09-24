# inventory-service

Order handling for a small warehouse: a product catalogue, stock levels,
reservations, pricing with bulk discounts, and sales reports. Storage is an
asynchronous key-value store (in memory here, a database in production), so
most operations are `async`.

```js
const { createService } = require('./src');

const svc = createService();
svc.catalog.add({ sku: 'BOLT', name: 'Hex bolt', category: 'hardware', priceCents: 40 });
await svc.stock.receive('BOLT', 500);

const order = await svc.placeOrder('o-1', 'ACME', [{ sku: 'BOLT', qty: 60 }], { priority: true });
order.totalCents; // 60 × 40 = 2400, minus the 12% bulk discount → 2112
```

## Rules

**Stock and reservations**

* `available(sku) = onHand(sku) − reserved(sku)`.
* Placing an order reserves every line **atomically**: either all lines are reserved or none are, and
  an order can never reserve more than is available — even when many orders are placed at the same
  time. A failed order throws `OutOfStockError` and reserves nothing.
* Cancelling releases the reservation. Fulfilling ships the goods (on hand goes down) and clears the
  reservation.

**Pricing** (`src/pricing.js`)

* Bulk tiers apply per order line, by quantity: 1–9 units full price, 10–49 units 5% off, 50+ units
  12% off. A product may define its own `tiers` (same shape, sorted by `min`); the **highest tier whose
  `min` the quantity reaches** applies.
* Line discounts are rounded half away from zero to whole cents.

**Orders** (`src/orders.js`)

* Options default to `{ priority: false, giftWrap: false, tags: [] }`. Options given for one order must
  never affect another order. Lines with 50 or more units add the tag `'bulk'` to that order.

**Reports** (`src/reports.js`)

* `salesReport(orders)` covers orders that are not cancelled: revenue per category (after
  discounts), total revenue, number of priority orders, and number of orders tagged `bulk`.
* `lowStockReport(service, threshold)` lists SKUs whose available quantity is below the threshold.

## Development

```
node --test
```
