'use strict';

const DAY_MS = 24 * 60 * 60 * 1000;

function toUtc(date) {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(String(date));
  if (!m) throw new Error(`Invalid date: ${date}`);
  const ms = Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  if (new Date(ms).getUTCDate() !== Number(m[3])) throw new Error(`Invalid date: ${date}`);
  return ms;
}

function addDays(date, n) {
  return new Date(toUtc(date) + n * DAY_MS).toISOString().slice(0, 10);
}

/** firstDate, firstDate + 7 days, … (`count` dates). */
function weeklyDates(firstDate, count) {
  const dates = [];
  for (let i = 0; i < count; i++) dates.push(addDays(firstDate, 7 * i));
  return dates;
}

module.exports = { toUtc, addDays, weeklyDates };
