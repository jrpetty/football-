'use strict';

/** Do two [start, end) intervals share any time? */
function overlaps(a, b) {
  return a.start < b.end && b.start < a.end;
}

/** Sort and merge intervals that overlap or touch. */
function mergeIntervals(intervals) {
  const sorted = [...intervals].sort((a, b) => a.start - b.start);
  const merged = [];
  for (const iv of sorted) {
    const last = merged[merged.length - 1];
    if (last && iv.start <= last.end) last.end = Math.max(last.end, iv.end);
    else merged.push({ start: iv.start, end: iv.end });
  }
  return merged;
}

/** The parts of `outer` not covered by any of `busy`. */
function subtract(outer, busy) {
  const free = [];
  let cursor = outer.start;
  for (const iv of mergeIntervals(busy)) {
    if (iv.end <= outer.start || iv.start >= outer.end) continue;
    if (iv.start > cursor) free.push({ start: cursor, end: iv.start });
    cursor = Math.max(cursor, iv.end);
  }
  if (cursor < outer.end) free.push({ start: cursor, end: outer.end });
  return free;
}

module.exports = { overlaps, mergeIntervals, subtract };
