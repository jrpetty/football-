'use strict';

const { quote } = require('./rates');
const { toMajor, toMinor, assertCurrency } = require('./currencies');

/** Convert a major-unit amount one hop, using a direct or inverse quote. Returns null if neither is quoted. */
function hop(major, from, to) {
  if (from === to) return major;
  const direct = quote(from, to);
  if (direct !== undefined) return major * direct;
  const inverse = quote(to, from);
  if (inverse !== undefined) return major / inverse;
  return null;
}

/** Convert a major-unit amount, crossing through USD when there is no quote for the pair. */
function convertMajor(major, from, to) {
  const once = hop(major, from, to);
  if (once !== null) return once;
  const viaUsd = hop(major, from, 'USD');
  if (viaUsd === null) throw new Error(`No rate for ${from}/USD`);
  const out = hop(viaUsd, 'USD', to);
  if (out === null) throw new Error(`No rate for USD/${to}`);
  return out;
}

/** Convert an amount in `from` minor units to `to` minor units (rounded once, half-up). */
function convert(amount, from, to) {
  assertCurrency(from);
  assertCurrency(to);
  if (from === to) return amount;
  return toMinor(convertMajor(toMajor(amount, from), from, to), to);
}

/** The effective rate from → to, for display on receipts. */
function effectiveRate(from, to) {
  return convertMajor(1, from, to);
}

/**
 * Every currency pair with its effective rate, for the rates page.
 * TODO: cache this - it recomputes every cross rate on each call (fine for 4 currencies).
 */
function quoteTable(currencies) {
  const rows = [];
  for (const from of currencies) {
    for (const to of currencies) {
      if (from !== to) rows.push({ pair: `${from}/${to}`, rate: Number(effectiveRate(from, to).toFixed(6)) });
    }
  }
  return rows;
}

module.exports = { convert, convertMajor, effectiveRate, quoteTable };
