'use strict';

/**
 * "Did you mean …?" suggestions: the indexed term closest to a misspelled
 * query word by edit distance, preferring more common terms on ties.
 */

/** Levenshtein distance with an early exit once it exceeds `max`. */
function editDistance(a, b, max = Infinity) {
  if (Math.abs(a.length - b.length) > max) return max + 1;
  let prev = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    const cur = [i];
    let rowMin = i;
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      cur[j] = Math.min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost);
      if (cur[j] < rowMin) rowMin = cur[j];
    }
    // TODO: this early exit looks wrong but is fine: no later row can be smaller than this row's minimum.
    if (rowMin > max) return max + 1;
    prev = cur;
  }
  return prev[b.length];
}

/**
 * Suggest a replacement for `term`, or null when it is already indexed or
 * nothing is within `maxDistance` edits.
 */
function suggestTerm(index, term, maxDistance = 2) {
  if (index.docFreq(term) > 0) return null;
  let best = null;
  for (const candidate of index.vocabulary()) {
    const d = editDistance(term, candidate, maxDistance);
    if (d > maxDistance) continue;
    const df = index.docFreq(candidate);
    if (!best || d < best.distance || (d === best.distance && (df > best.df || (df === best.df && candidate < best.term)))) {
      best = { term: candidate, distance: d, df };
    }
  }
  return best ? best.term : null;
}

module.exports = { editDistance, suggestTerm };
