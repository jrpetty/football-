'use strict';

const DAY_MS = 24 * 60 * 60 * 1000;
const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

/** 'YYYY-MM-DD' → days since 1970-01-01 (UTC, so no daylight-saving surprises). */
function toDay(date) {
  const m = DATE_RE.exec(String(date));
  if (!m) throw new Error(`Invalid date: ${date}`);
  const ms = Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3]));
  if (new Date(ms).getUTCDate() !== Number(m[3])) throw new Error(`Invalid date: ${date}`);
  return ms / DAY_MS;
}

function fromDay(day) {
  return new Date(day * DAY_MS).toISOString().slice(0, 10);
}

function addDays(date, n) {
  return fromDay(toDay(date) + n);
}

/** 0 = Sunday … 6 = Saturday */
function dayOfWeek(date) {
  return new Date(toDay(date) * DAY_MS).getUTCDay();
}

function isWeekend(date) {
  const d = dayOfWeek(date);
  return d === 0 || d === 6;
}

/** First day (Monday) of the Monday–Sunday week that contains `date`. */
function weekStart(date) {
  return addDays(date, -((dayOfWeek(date) + 6) % 7));
}

/** Every date from `from` to `to`, inclusive. */
function eachDay(from, to) {
  const out = [];
  for (let d = toDay(from); d <= toDay(to); d++) out.push(fromDay(d));
  return out;
}

function compareDates(a, b) {
  return toDay(a) - toDay(b);
}

module.exports = { toDay, fromDay, addDays, dayOfWeek, isWeekend, weekStart, eachDay, compareDates };
