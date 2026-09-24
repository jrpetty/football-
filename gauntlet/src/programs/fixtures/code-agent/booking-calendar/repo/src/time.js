'use strict';

/** '09:30' → 570 (minutes since midnight). '24:00' is allowed as the end of the day. */
function parseTime(text) {
  const m = /^(\d{1,2}):(\d{2})$/.exec(String(text).trim());
  if (!m) throw new Error(`Invalid time: ${text}`);
  const hours = Number(m[1]);
  const minutes = Number(m[2]);
  if (hours > 24 || minutes > 59 || (hours === 24 && minutes > 0)) throw new Error(`Invalid time: ${text}`);
  return hours * 60 + minutes;
}

/** 570 → '09:30' */
function formatTime(minutes) {
  const h = Math.floor(minutes / 60);
  const m = minutes % 60;
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`;
}

/** 90 | '1h30m' | '45m' | '2h' → minutes */
function parseDuration(value) {
  if (typeof value === 'number') {
    if (!Number.isInteger(value) || value <= 0) throw new Error(`Invalid duration: ${value}`);
    return value;
  }
  const m = /^(?:(\d+)h)?\s*(?:(\d+)m)?$/.exec(String(value).trim());
  if (!m || (m[1] === undefined && m[2] === undefined)) throw new Error(`Invalid duration: ${value}`);
  const total = Number(m[1] ?? 0) * 60 + Number(m[2] ?? 0);
  if (total <= 0) throw new Error(`Invalid duration: ${value}`);
  return total;
}

module.exports = { parseTime, formatTime, parseDuration };
