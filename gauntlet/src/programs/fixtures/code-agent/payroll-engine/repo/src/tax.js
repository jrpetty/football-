'use strict';

const { TAX_BANDS, periodsPerYear } = require('./config');
const { roundHalfUp } = require('./money');

/** Progressive tax on an annual income (cents, unrounded). */
function annualTax(annualCents) {
  let tax = 0;
  let lower = 0;
  for (const band of TAX_BANDS) {
    if (annualCents <= lower) break;
    const inBand = Math.min(annualCents, band.upTo) - lower;
    tax += (inBand * band.ratePct) / 100;
    lower = band.upTo;
  }
  return tax;
}

/**
 * Tax for one pay period: annualise the period's taxable pay, apply the bands,
 * and divide back. TODO: bands are not indexed for inflation (policy, not a bug).
 */
function periodTax(taxableCents, frequency) {
  if (taxableCents <= 0) return 0;
  const n = periodsPerYear(frequency);
  return roundHalfUp(annualTax(taxableCents * n) / n);
}

module.exports = { annualTax, periodTax };
