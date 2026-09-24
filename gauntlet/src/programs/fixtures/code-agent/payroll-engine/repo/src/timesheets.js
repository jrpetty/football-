'use strict';

const { toDay, weekStart } = require('./dates');

/** Simulated network latency of the time-tracking service (deterministic per employee). */
function latencyFor(employeeId) {
  let h = 0;
  for (const ch of employeeId) h = (h * 31 + ch.charCodeAt(0)) % 997;
  return h % 7;
}

class TimesheetStore {
  constructor() {
    this.entries = new Map();
  }

  /** Record `hours` worked by an employee on a date (adds to anything already recorded). */
  record(employeeId, date, hours) {
    if (!(hours >= 0 && hours <= 24)) throw new RangeError(`Invalid hours ${hours} for ${employeeId} on ${date}`);
    toDay(date);
    const key = `${employeeId}|${date}`;
    this.entries.set(key, (this.entries.get(key) ?? 0) + hours);
  }

  /** [{ date, hours }] for one employee between two dates (inclusive), in date order. */
  hoursFor(employeeId, from, to) {
    const out = [];
    for (const [key, hours] of this.entries) {
      const [id, date] = key.split('|');
      if (id !== employeeId) continue;
      if (toDay(date) < toDay(from) || toDay(date) > toDay(to)) continue;
      out.push({ date, hours });
    }
    return out.sort((a, b) => toDay(a.date) - toDay(b.date));
  }

  /** Import rows from the time clock: [{ employeeId, date, hours }]. Returns how many were recorded. */
  importRows(rows) {
    let n = 0;
    for (const row of rows) {
      if (!row || !row.employeeId) continue; // the time clock emits blank separator rows
      this.record(row.employeeId, row.date, Number(row.hours));
      n++;
    }
    return n;
  }

  /** Hours per week (keyed by the week's first day) for the manager dashboard. */
  weeklyTotals(employeeId, from, to) {
    const totals = {};
    for (const { date, hours } of this.hoursFor(employeeId, from, to)) {
      const key = weekStart(date);
      totals[key] = (totals[key] ?? 0) + hours;
    }
    return totals;
  }

  /** Same as hoursFor, but asynchronous like the real time-tracking API. */
  async fetch(employeeId, from, to) {
    await new Promise((resolve) => setTimeout(resolve, latencyFor(employeeId)));
    return this.hoursFor(employeeId, from, to);
  }
}

module.exports = { TimesheetStore, latencyFor };
