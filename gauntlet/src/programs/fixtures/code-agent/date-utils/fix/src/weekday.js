'use strict';

const { toEpochDay, addDays } = require('./arith');

const DAY_NAMES = ['Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday', 'Sunday'];

/** English name of the day of the week. */
function weekday(str) {
  const date = new Date(toEpochDay(str) * 24 * 60 * 60 * 1000);
  return DAY_NAMES[(date.getUTCDay() + 6) % 7];
}

function isWeekend(str) {
  const name = weekday(str);
  return name === 'Saturday' || name === 'Sunday';
}

/** Monday-Friday dates d with a <= d < b. */
function businessDaysBetween(a, b) {
  let count = 0;
  for (let d = a; toEpochDay(d) < toEpochDay(b); d = addDays(d, 1)) {
    if (!isWeekend(d)) count++;
  }
  return count;
}

module.exports = { DAY_NAMES, weekday, isWeekend, businessDaysBetween };
