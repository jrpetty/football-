'use strict';

/**
 * Does the document contain the phrase? The phrase is a list of
 * { term, offset } where offset is the word distance from the phrase's first
 * word (stop words included), so "tower of london" is tower@0, london@2.
 */
function matchesPhrase(index, docId, phrase) {
  const first = index.docsWith(phrase[0].term).get(docId);
  if (!first) return false;
  const rest = phrase.slice(1).map((p) => ({ offset: p.offset, positions: new Set(index.docsWith(p.term).get(docId) ?? []) }));
  return first.some((start) => rest.every((r) => r.positions.has(start + r.offset)));
}

module.exports = { matchesPhrase };
