'use strict';

const { percentOf } = require('./money');

const FX_FEE_PCT = 0.5;

/**
 * Fee for a transfer, in the receiver's currency.
 * `sent` is the amount debited (sender's currency); `converted` is the same
 * amount in the receiver's currency, before the fee.
 */
function transferFee({ sent, converted, fromCurrency, toCurrency }) {
  if (fromCurrency === toCurrency) return 0;
  return percentOf(converted, FX_FEE_PCT);
}

module.exports = { FX_FEE_PCT, transferFee };
