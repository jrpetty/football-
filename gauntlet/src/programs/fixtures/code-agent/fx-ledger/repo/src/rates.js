'use strict';

/**
 * Exchange rates, quoted BASE/QUOTE: 1 BASE buys `rate` QUOTE.
 * Only one direction of each pair is stored; the other is derived.
 * TODO(treasury): these are end-of-day rates from 2025-02-28; wire up the feed.
 */
const RATES = Object.freeze({
  'EUR/USD': 1.08,
  'GBP/USD': 1.27,
  'USD/JPY': 150,
  'EUR/GBP': 0.85,
});

function quote(base, quoteCurrency) {
  return RATES[`${base}/${quoteCurrency}`];
}

module.exports = { RATES, quote };
