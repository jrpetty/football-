'use strict';

/** `percent`% of an amount in cents, rounded half away from zero. */
function percentOf(cents, percent) {
  const raw = (cents * percent) / 100;
  return Math.sign(raw) * Math.round(Math.abs(raw));
}

/** 123456 → "1,234.56" */
function formatCents(cents) {
  const sign = cents < 0 ? '-' : '';
  const abs = Math.abs(cents);
  const whole = String(Math.floor(abs / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${sign}${whole}.${String(abs % 100).padStart(2, '0')}`;
}

function sum(values) {
  return values.reduce((a, b) => a + b, 0);
}

module.exports = { percentOf, formatCents, sum };
