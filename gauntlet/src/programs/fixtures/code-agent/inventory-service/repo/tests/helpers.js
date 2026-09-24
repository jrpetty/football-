'use strict';

const { createService } = require('../src');

/** A service with two products and some stock. */
async function setup({ bolts = 500, glue = 20 } = {}) {
  const svc = createService();
  svc.catalog.add({ sku: 'BOLT', name: 'Hex bolt', category: 'hardware', priceCents: 40 });
  svc.catalog.add({ sku: 'GLUE', name: 'Wood glue', category: 'supplies', priceCents: 350 });
  if (bolts) await svc.stock.receive('BOLT', bolts);
  if (glue) await svc.stock.receive('GLUE', glue);
  return svc;
}

module.exports = { setup };
