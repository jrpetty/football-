'use strict';

/** Decimal places of each currency's minor unit. */
const MINOR_UNITS = Object.freeze({ USD: 2, EUR: 2, GBP: 2, JPY: 0 });

function assertCurrency(code) {
  if (!Object.prototype.hasOwnProperty.call(MINOR_UNITS, code)) throw new Error(`Unsupported currency ${code}`);
  return code;
}

function minorUnits(code) {
  return MINOR_UNITS[assertCurrency(code)];
}

/** Minor units → major units (e.g. 1234 cents → 12.34). */
function toMajor(amount, code) {
  return amount / 10 ** minorUnits(code);
}

/** Major units → minor units, rounded half-up (away from zero). */
function toMinor(major, code) {
  const scaled = major * 10 ** minorUnits(code);
  // Nudge by a tiny epsilon so 1.005 * 100 = 100.49999… still rounds to 101.
  return Math.sign(scaled) * Math.round(Math.abs(scaled) + 1e-9);
}

module.exports = { MINOR_UNITS, assertCurrency, minorUnits, toMajor, toMinor };
