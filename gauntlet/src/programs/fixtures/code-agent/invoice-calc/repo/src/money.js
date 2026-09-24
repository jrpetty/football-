'use strict';

const SYMBOLS = { USD: '$', EUR: '€', GBP: '£' };

/** Convert a decimal amount (e.g. 19.99) to integer cents. */
function toCents(amount) {
  if (typeof amount !== 'number' || !Number.isFinite(amount)) {
    throw new TypeError(`Invalid amount: ${amount}`);
  }
  return Math.floor(amount * 100);
}

/** Convert integer cents back to a decimal amount. */
function fromCents(cents) {
  return cents / 100;
}

/** `percent`% of an amount in cents, rounded half away from zero to whole cents. */
function percentOf(cents, percent) {
  const raw = (cents * percent) / 100;
  return Math.sign(raw) * Math.round(Math.abs(raw));
}

/** 123456 → "$1,234.56" */
function formatMoney(cents, currency = 'USD') {
  const symbol = SYMBOLS[currency] ?? `${currency} `;
  const sign = cents < 0 ? '-' : '';
  const abs = Math.abs(cents);
  const whole = Math.floor(abs / 100)
    .toString()
    .replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  const frac = String(abs % 100).padStart(2, '0');
  return `${sign}${symbol}${whole}.${frac}`;
}

module.exports = { toCents, fromCents, percentOf, formatMoney };
