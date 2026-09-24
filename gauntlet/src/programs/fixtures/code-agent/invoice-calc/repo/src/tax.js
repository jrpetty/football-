'use strict';

const { percentOf } = require('./money');

/** Sales tax / VAT rates in percent, by region code. */
const RATES = {
  'US-CA': 7.25,
  'US-NY': 8.875,
  'US-OR': 0,
  UK: 20,
  DE: 19,
  FR: 20,
  JP: 10,
};

function taxRate(region) {
  if (!Object.prototype.hasOwnProperty.call(RATES, region)) {
    throw new Error(`Unknown tax region: ${region}`);
  }
  return RATES[region];
}

/** Tax in cents on an amount in cents. */
function taxFor(amountCents, region) {
  return percentOf(amountCents, taxRate(region));
}

module.exports = { RATES, taxRate, taxFor };
