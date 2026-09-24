'use strict';

/** Gregorian leap-year rule. */
function isLeapYear(year) {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
}

module.exports = { isLeapYear };
