'use strict';

const { toDay } = require('./dates');
const { workingDaysBetween } = require('./calendar');

/**
 * Share of a period's salary earned by someone who started during it.
 * 1 when they started on or before the first day, 0 when they start after it ends.
 */
function prorationFactor(startDate, period) {
  const start = toDay(startDate);
  const first = toDay(period.start);
  const last = toDay(period.end);
  if (start <= first) return 1;
  if (start > last) return 0;
  // Working days from the start date to the period end, over working days in the period (inclusive).
  const total = workingDaysBetween(period.start, period.end);
  return total === 0 ? 0 : workingDaysBetween(startDate, period.end) / total;
}

module.exports = { prorationFactor };
