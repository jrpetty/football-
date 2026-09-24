'use strict';

const { isLeapYear } = require('./leap');

const MONTH_LENGTHS = [31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];

/** Number of days in a month (month is 1-12). */
function daysInMonth(year, month) {
  if (!Number.isInteger(month) || month < 1 || month > 12) throw new RangeError(`Invalid month: ${month}`);
  if (month === 2 && isLeapYear(year)) return 29;
  return MONTH_LENGTHS[month - 1];
}

/** 1-based day number within the year. */
function dayOfYearParts(year, month, day) {
  let total = day;
  for (let m = 1; m < month; m++) total += daysInMonth(year, m);
  return total;
}

module.exports = { MONTH_LENGTHS, daysInMonth, dayOfYearParts };
