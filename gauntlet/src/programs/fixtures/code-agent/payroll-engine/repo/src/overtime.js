'use strict';

const { weekStart } = require('./dates');
const { OVERTIME_THRESHOLD_HOURS } = require('./config');

/**
 * Split timesheet entries into regular and overtime hours: within each week,
 * hours above the threshold are overtime.
 */
function splitOvertime(entries) {
  const weeks = new Map();
  for (const { date, hours } of entries) {
    const key = weekStart(date);
    weeks.set(key, (weeks.get(key) ?? 0) + hours);
  }
  let regularHours = 0;
  let overtimeHours = 0;
  for (const total of weeks.values()) {
    regularHours += Math.min(total, OVERTIME_THRESHOLD_HOURS);
    overtimeHours += Math.max(0, total - OVERTIME_THRESHOLD_HOURS);
  }
  return { regularHours, overtimeHours, weeks: weeks.size };
}

module.exports = { splitOvertime };
