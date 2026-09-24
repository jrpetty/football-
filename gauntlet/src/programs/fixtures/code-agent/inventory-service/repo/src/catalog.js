'use strict';

class Catalog {
  constructor() {
    this.products = new Map();
  }

  add(product) {
    const { sku, name, category, priceCents } = product;
    if (!sku || typeof sku !== 'string') throw new Error('Product needs a sku');
    if (this.products.has(sku)) throw new Error(`Duplicate sku ${sku}`);
    if (!Number.isInteger(priceCents) || priceCents < 0) throw new RangeError(`Invalid price for ${sku}`);
    this.products.set(sku, { sku, name: name ?? sku, category: category ?? 'general', priceCents, tiers: product.tiers ?? null });
    return this.products.get(sku);
  }

  get(sku) {
    const product = this.products.get(sku);
    if (!product) throw new Error(`Unknown sku ${sku}`);
    return product;
  }

  has(sku) {
    return this.products.has(sku);
  }

  list(category) {
    const all = [...this.products.values()];
    return category ? all.filter((p) => p.category === category) : all;
  }
}

module.exports = { Catalog };
