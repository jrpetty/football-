'use strict';

const { createStore } = require('./storage');
const { Catalog } = require('./catalog');
const { StockLedger } = require('./stock');
const { ReservationBook, UnknownOrderError } = require('./reservations');
const { createOrder, transition } = require('./orders');
const { priceOrder } = require('./pricing');
const { salesReport, lowStockReport } = require('./reports');

/** Minimal synchronous event bus. */
class EventBus {
  constructor() {
    this.handlers = new Map();
    this.history = [];
  }

  on(type, handler) {
    if (!this.handlers.has(type)) this.handlers.set(type, []);
    this.handlers.get(type).push(handler);
    return () => this.handlers.set(type, this.handlers.get(type).filter((h) => h !== handler));
  }

  emit(type, payload) {
    this.history.push({ type, payload });
    for (const handler of this.handlers.get(type) ?? []) handler(payload);
  }
}

function createService({ store = createStore() } = {}) {
  const catalog = new Catalog();
  const stock = new StockLedger(store);
  const reservations = new ReservationBook(store, stock);
  const events = new EventBus();
  const orders = new Map();

  function getOrder(id) {
    const order = orders.get(id);
    if (!order) throw new UnknownOrderError(id);
    return order;
  }

  const service = {
    catalog,
    stock,
    reservations,
    events,

    async placeOrder(id, customer, lines, options) {
      if (orders.has(id)) throw new Error(`Duplicate order id ${id}`);
      for (const line of lines) catalog.get(line.sku);
      const order = createOrder(id, customer, lines, options);
      await reservations.reserve(id, order.lines);
      order.priced = priceOrder(catalog, order.lines);
      order.totalCents = order.priced.totalCents;
      orders.set(id, order);
      events.emit('order.placed', { id, totalCents: order.totalCents });
      return order;
    },

    async cancelOrder(id) {
      const order = getOrder(id);
      transition(order, 'cancelled');
      await reservations.release(id);
      events.emit('order.cancelled', { id });
      return order;
    },

    async fulfilOrder(id) {
      const order = getOrder(id);
      transition(order, 'fulfilled');
      const lines = await reservations.release(id);
      for (const line of lines) await stock.ship(line.sku, line.qty);
      events.emit('order.fulfilled', { id });
      return order;
    },

    getOrder,

    listOrders() {
      return [...orders.values()];
    },

    salesReport() {
      return salesReport(service.listOrders(), catalog);
    },

    lowStockReport(threshold) {
      return lowStockReport(service, threshold);
    },
  };
  return service;
}

module.exports = { createService, EventBus };
