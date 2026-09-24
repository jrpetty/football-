'use strict';

const { minorUnits } = require('./currencies');

/** `pct`% of an integer amount, rounded half-up (away from zero). */
function percentOf(amount, pct) {
  const raw = (amount * pct) / 100;
  return Math.sign(raw) * Math.round(Math.abs(raw));
}

/** 123456 USD → "1,234.56 USD"; 1500 JPY → "1,500 JPY" */
function formatAmount(amount, code) {
  const dp = minorUnits(code);
  const sign = amount < 0 ? '-' : '';
  const abs = Math.abs(amount);
  const whole = String(Math.floor(abs / 10 ** dp)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const frac = dp ? `.${String(abs % 10 ** dp).padStart(dp, '0')}` : '';
  return `${sign}${whole}${frac} ${code}`;
}

module.exports = { percentOf, formatAmount };
