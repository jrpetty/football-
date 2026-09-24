'use strict';

/** Number of pay periods in a year for each pay frequency. */
const PERIODS_PER_YEAR = Object.freeze({
  weekly: 52,
  biweekly: 26,
  monthly: 12,
});

/**
 * Annual income-tax bands in cents, applied progressively: each rate applies
 * only to the part of the income inside its band.
 */
const TAX_BANDS = Object.freeze([
  { upTo: 1_200_000, ratePct: 0 },
  { upTo: 5_000_000, ratePct: 20 },
  { upTo: Infinity, ratePct: 40 },
]);

const OVERTIME_THRESHOLD_HOURS = 40;
const OVERTIME_MULTIPLIER = 1.5;

// TODO(finance): the 2026 budget may move the 40% threshold; keep it in one place.

function periodsPerYear(frequency) {
  const n = PERIODS_PER_YEAR[frequency];
  if (!n) throw new Error(`Unknown pay frequency: ${frequency}`);
  return n;
}

module.exports = { PERIODS_PER_YEAR, TAX_BANDS, OVERTIME_THRESHOLD_HOURS, OVERTIME_MULTIPLIER, periodsPerYear };
