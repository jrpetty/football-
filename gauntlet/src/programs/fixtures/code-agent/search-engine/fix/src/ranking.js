'use strict';

/**
 * Turn a docId → score map into a ranked list, best first.
 * (Array.prototype.sort is stable in every supported Node version.)
 */
function rank(scores) {
  return [...scores.entries()].map(([id, score]) => ({ id, score })).sort((a, b) => b.score - a.score || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0));
}

/** One page of a ranked list (pages are 1-based). */
function paginate(ranked, page = 1, pageSize = 10) {
  if (!(Number.isInteger(page) && page >= 1)) throw new RangeError(`Invalid page ${page}`);
  if (!(Number.isInteger(pageSize) && pageSize >= 1 && pageSize <= 100)) throw new RangeError(`Invalid page size ${pageSize}`);
  const from = (page - 1) * pageSize;
  return ranked.slice(from, from + pageSize);
}

module.exports = { rank, paginate };
