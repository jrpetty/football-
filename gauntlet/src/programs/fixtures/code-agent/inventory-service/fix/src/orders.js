'use strict';

const DEFAULT_OPTIONS = { priority: false, giftWrap: false, tags: [] };

const BULK_QTY = 50;

/** Build a new order record (not yet reserved or priced). */
function createOrder(id, customer, lines, options = {}) {
  if (!id) throw new Error('Order needs an id');
  if (!Array.isArray(lines) || lines.length === 0) throw new Error(`Order ${id} has no lines`);
  const opts = { ...DEFAULT_OPTIONS, ...options };
  opts.tags = [...(opts.tags ?? [])];
  if (lines.some((line) => line.qty >= BULK_QTY) && !opts.tags.includes('bulk')) opts.tags.push('bulk');
  return {
    id,
    customer,
    lines: lines.map((line) => ({ sku: line.sku, qty: line.qty })),
    priority: opts.priority,
    giftWrap: opts.giftWrap,
    tags: opts.tags,
    status: 'placed',
    totalCents: 0,
  };
}

const TRANSITIONS = {
  placed: ['fulfilled', 'cancelled'],
  fulfilled: [],
  cancelled: [],
};

function transition(order, status) {
  if (!TRANSITIONS[order.status].includes(status)) {
    throw new Error(`Order ${order.id} cannot go from ${order.status} to ${status}`);
  }
  order.status = status;
  return order;
}

module.exports = { DEFAULT_OPTIONS, BULK_QTY, createOrder, transition };
