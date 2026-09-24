'use strict';

class OutOfStockError extends Error {
  constructor(sku, wanted, available) {
    super(`Out of stock: ${sku} (wanted ${wanted}, available ${available})`);
    this.name = 'OutOfStockError';
    this.sku = sku;
    this.wanted = wanted;
    this.available = available;
  }
}

class UnknownOrderError extends Error {
  constructor(orderId) {
    super(`Unknown order ${orderId}`);
    this.name = 'UnknownOrderError';
    this.orderId = orderId;
  }
}

/** Merge lines for the same sku: [{sku:'A',qty:1},{sku:'A',qty:2}] → [{sku:'A',qty:3}] */
function mergeLines(lines) {
  const bySku = new Map();
  for (const line of lines) {
    if (!Number.isInteger(line.qty) || line.qty <= 0) throw new RangeError(`Invalid quantity for ${line.sku}: ${line.qty}`);
    bySku.set(line.sku, (bySku.get(line.sku) ?? 0) + line.qty);
  }
  return [...bySku].map(([sku, qty]) => ({ sku, qty }));
}

class ReservationBook {
  constructor(store, stock) {
    this.store = store;
    this.stock = stock;
    this.orders = new Map();
    this.queue = Promise.resolve();
  }

  /** Run reservation changes one at a time, so no check can go stale before its write. */
  _exclusive(task) {
    const run = this.queue.then(task);
    this.queue = run.catch(() => {});
    return run;
  }

  async reserved(sku) {
    return (await this.store.get(`reserved:${sku}`)) ?? 0;
  }

  async available(sku) {
    return (await this.stock.onHand(sku)) - (await this.reserved(sku));
  }

  /** Reserve every line of an order, or nothing at all. */
  reserve(orderId, lines) {
    return this._exclusive(() => this._reserve(orderId, lines));
  }

  async _reserve(orderId, lines) {
    if (this.orders.has(orderId)) throw new Error(`Order ${orderId} already has a reservation`);
    const merged = mergeLines(lines);
    for (const line of merged) {
      const available = await this.available(line.sku);
      if (available < line.qty) throw new OutOfStockError(line.sku, line.qty, available);
    }
    for (const line of merged) {
      const current = await this.reserved(line.sku);
      await this.store.set(`reserved:${line.sku}`, current + line.qty);
    }
    this.orders.set(orderId, merged);
    return merged;
  }

  release(orderId) {
    return this._exclusive(() => this._release(orderId));
  }

  async _release(orderId) {
    const lines = this.orders.get(orderId);
    if (!lines) throw new UnknownOrderError(orderId);
    for (const line of lines) {
      const current = await this.reserved(line.sku);
      await this.store.set(`reserved:${line.sku}`, current - line.qty);
    }
    this.orders.delete(orderId);
    return lines;
  }

  has(orderId) {
    return this.orders.has(orderId);
  }
}

module.exports = { ReservationBook, mergeLines, OutOfStockError, UnknownOrderError };
