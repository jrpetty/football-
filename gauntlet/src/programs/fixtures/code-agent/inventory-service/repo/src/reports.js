'use strict';

const { sum } = require('./money');

/** Revenue and order counts over orders that were not cancelled. */
function salesReport(orders, catalog) {
  const counted = orders.filter((order) => order.status !== 'cancelled');
  const byCategory = {};
  for (const order of counted) {
    for (const line of order.priced.lines) {
      const category = catalog.get(line.sku).category;
      byCategory[category] = (byCategory[category] ?? 0) + line.netCents;
    }
  }
  return {
    orders: counted.length,
    revenueCents: sum(counted.map((order) => order.totalCents)),
    byCategory,
    priorityOrders: counted.filter((order) => order.priority).length,
    bulkOrders: counted.filter((order) => order.tags.includes('bulk')).length,
  };
}

/** SKUs whose available quantity is below `threshold`, lowest first. */
async function lowStockReport(service, threshold) {
  const rows = [];
  for (const product of service.catalog.list()) {
    const available = await service.reservations.available(product.sku);
    if (available < threshold) rows.push({ sku: product.sku, available });
  }
  return rows.sort((a, b) => a.available - b.available || a.sku.localeCompare(b.sku));
}

module.exports = { salesReport, lowStockReport };
