'use strict';

const { daysInMonth } = require('./calendar');

const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

/** '2024-02-29' → { year: 2024, month: 2, day: 29 } */
function parseDate(str) {
  const match = DATE_RE.exec(String(str));
  if (!match) throw new RangeError(`Not a YYYY-MM-DD date: ${str}`);
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  if (month < 1 || month > 12) throw new RangeError(`Invalid month in ${str}`);
  if (day < 1 || day > daysInMonth(year, month)) throw new RangeError(`Invalid day in ${str}`);
  return { year, month, day };
}

function pad(n, width) {
  return String(n).padStart(width, '0');
}

/** { year: 2024, month: 2, day: 9 } → '2024-02-09' */
function formatDate({ year, month, day }) {
  return `${pad(year, 4)}-${pad(month, 2)}-${pad(day, 2)}`;
}

module.exports = { parseDate, formatDate };
