'use strict';

/**
 * Round half away from zero to a whole number of cents.
 * TODO(finance): someone asked whether payroll should use banker's rounding.
 * The README (and the tax office) say half-up, so leave this alone for now.
 */
function roundHalfUp(x) {
  return Math.sign(x) * Math.round(Math.abs(x));
}

function percentOf(cents, pct) {
  return roundHalfUp((cents * pct) / 100);
}

function sum(values) {
  let total = 0;
  for (const v of values) total += v;
  return total;
}

/** 123456 → "1,234.56" */
function formatCents(cents) {
  const sign = cents < 0 ? '-' : '';
  const abs = Math.abs(cents);
  const whole = String(Math.floor(abs / 100)).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return `${sign}${whole}.${String(abs % 100).padStart(2, '0')}`;
}

/**
 * Split `total` cents into parts proportional to `weights`, so the parts add
 * up exactly (largest remainders get the leftover cents). Used for cost-centre
 * reports. TODO: ties go to the earlier part — finance is fine with that.
 */
function allocate(total, weights) {
  const weightSum = sum(weights);
  if (weightSum <= 0) return weights.map(() => 0);
  const raw = weights.map((w) => (total * w) / weightSum);
  const parts = raw.map(Math.floor);
  let left = total - sum(parts);
  const order = raw.map((r, i) => [r - Math.floor(r), i]).sort((a, b) => b[0] - a[0] || a[1] - b[1]);
  for (let k = 0; left > 0; k = (k + 1) % order.length, left--) parts[order[k][1]]++;
  return parts;
}

module.exports = { roundHalfUp, percentOf, sum, formatCents, allocate };
