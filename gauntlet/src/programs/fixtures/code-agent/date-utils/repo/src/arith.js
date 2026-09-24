'use strict';

const { parseDate, formatDate } = require('./parse');
const { dayOfYearParts } = require('./calendar');

const MS_PER_DAY = 24 * 60 * 60 * 1000;

/** Days since 1970-01-01 (UTC, so there are no daylight-saving surprises). */
function toEpochDay(str) {
  const { year, month, day } = parseDate(str);
  return Date.UTC(year, month - 1, day) / MS_PER_DAY;
}

function fromEpochDay(n) {
  const d = new Date(n * MS_PER_DAY);
  return formatDate({ year: d.getUTCFullYear(), month: d.getUTCMonth() + 1, day: d.getUTCDate() });
}

function addDays(str, n) {
  return fromEpochDay(toEpochDay(str) + n);
}

function addMonths(str, n) {
  const { year, month, day } = parseDate(str);
  const total = year * 12 + (month - 1) + n;
  const targetYear = Math.floor(total / 12);
  const targetMonth = (total % 12) + 1;
  return formatDate({ year: targetYear, month: targetMonth, day });
}

function daysBetween(a, b) {
  return toEpochDay(b) - toEpochDay(a);
}

function dayOfYear(str) {
  const { year, month, day } = parseDate(str);
  return dayOfYearParts(year, month, day);
}

module.exports = { toEpochDay, fromEpochDay, addDays, addMonths, daysBetween, dayOfYear };
