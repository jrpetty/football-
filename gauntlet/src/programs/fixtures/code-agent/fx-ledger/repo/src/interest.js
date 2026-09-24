'use strict';

/** Days in a month (month is 1-12). */
function daysInMonth(year, month) {
  return new Date(Date.UTC(year, month, 0)).getUTCDate();
}

function iso(year, month, day) {
  return `${year}-${String(month).padStart(2, '0')}-${String(day).padStart(2, '0')}`;
}

/**
 * Interest earned in a month on daily end-of-day balances, Actual/365 Fixed.
 * TODO: leap years? — No: Actual/365 *Fixed* divides by 365 even in leap years.
 * Returns the unrounded amount in minor units.
 */
function monthlyInterest(entries, ratePct, year, month) {
  const n = daysInMonth(year, month);
  let balance = 0;
  const sorted = entries.slice().sort((a, b) => (a.date < b.date ? -1 : a.date > b.date ? 1 : a.seq - b.seq));
  let i = 0;
  let total = 0;
  for (let day = 1; day <= n; day++) {
    const date = iso(year, month, day);
    while (i < sorted.length && sorted[i].date <= date) balance += sorted[i++].amount;
    if (balance > 0) total += (balance * ratePct) / 100 / 365;
  }
  return total;
}

module.exports = { daysInMonth, monthlyInterest, iso };
