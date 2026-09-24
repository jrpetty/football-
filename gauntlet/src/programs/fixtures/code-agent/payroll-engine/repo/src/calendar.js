'use strict';

const { eachDay, isWeekend, addDays } = require('./dates');

/**
 * Company holidays. TODO: load these from the HR system instead of hard-coding
 * them every December.
 */
const HOLIDAYS = new Set([
  '2025-01-01',
  '2025-04-18',
  '2025-04-21',
  '2025-05-05',
  '2025-05-26',
  '2025-08-25',
  '2025-12-25',
  '2025-12-26',
  '2026-01-01',
  '2026-04-03',
  '2026-04-06',
  '2026-05-04',
]);

function isHoliday(date) {
  return HOLIDAYS.has(date);
}

function isWorkingDay(date) {
  return !isWeekend(date) && !isHoliday(date);
}

/** Working days from `from` to `to`, both inclusive. */
function workingDaysBetween(from, to) {
  return eachDay(from, to).filter(isWorkingDay).length;
}

/** The first working day on or after `date`. */
function nextWorkingDay(date) {
  let d = date;
  for (let i = 0; i < 14 && !isWorkingDay(d); i++) d = addDays(d, 1);
  return d;
}

/** Working days in a calendar month (month is 1-12). */
function workingDaysInMonth(year, month) {
  const first = `${year}-${String(month).padStart(2, '0')}-01`;
  const next = month === 12 ? `${year + 1}-01-01` : `${year}-${String(month + 1).padStart(2, '0')}-01`;
  return workingDaysBetween(first, addDays(next, -1));
}

module.exports = { HOLIDAYS, isHoliday, isWorkingDay, workingDaysBetween, nextWorkingDay, workingDaysInMonth };
