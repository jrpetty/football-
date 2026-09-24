'use strict';

class StockLedger {
  constructor(store) {
    this.store = store;
  }

  async onHand(sku) {
    return (await this.store.get(`stock:${sku}`)) ?? 0;
  }

  async receive(sku, qty) {
    if (!Number.isInteger(qty) || qty <= 0) throw new RangeError(`Invalid quantity ${qty}`);
    const current = await this.onHand(sku);
    await this.store.set(`stock:${sku}`, current + qty);
    return current + qty;
  }

  async ship(sku, qty) {
    const current = await this.onHand(sku);
    if (qty > current) throw new RangeError(`Cannot ship ${qty} × ${sku}: only ${current} on hand`);
    await this.store.set(`stock:${sku}`, current - qty);
    return current - qty;
  }

  async skus() {
    const keys = await this.store.keys('stock:');
    return keys.map((k) => k.slice('stock:'.length)).sort();
  }
}

module.exports = { StockLedger };
