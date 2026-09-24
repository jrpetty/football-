'use strict';

const { toCents, percentOf } = require('./money');

/**
 * Discount in cents for a subtotal (in cents).
 * discount: null | { type: 'percent', value: 0..100 } | { type: 'fixed', value: amount }
 */
function discountFor(subtotalCents, discount) {
  if (!discount) return 0;
  if (discount.type === 'percent') {
    if (!(discount.value >= 0 && discount.value <= 100)) {
      throw new RangeError(`Percent discount must be within 0..100, got ${discount.value}`);
    }
    return percentOf(subtotalCents, discount.value);
  }
  if (discount.type === 'fixed') {
    if (!(discount.value >= 0)) throw new RangeError(`Fixed discount must be positive, got ${discount.value}`);
    return Math.min(toCents(discount.value), subtotalCents);
  }
  throw new Error(`Unknown discount type: ${discount.type}`);
}

module.exports = { discountFor };
