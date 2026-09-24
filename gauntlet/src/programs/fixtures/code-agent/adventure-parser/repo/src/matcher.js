'use strict';

/**
 * Find the thing in scope that a run of words refers to.
 * scope: [{ id, names: ['brass lamp', 'lamp'] }]
 * Returns { object, phrase } | { ambiguous: [objects], phrase } | null.
 */
function findObject(words, scope) {
  // Try the longest runs of words first so "red key" beats "key".
  for (let len = words.length; len > 1; len--) {
    for (let start = 0; start + len <= words.length; start++) {
      const phrase = words.slice(start, start + len).join(' ');
      const hits = scope.filter((thing) => thing.names.includes(phrase));
      if (hits.length === 1) return { object: hits[0], phrase };
      if (hits.length > 1) return { ambiguous: hits, phrase };
    }
  }
  return null;
}

/** "brass lamp or oak chest" */
function describeChoices(things) {
  return things.map((thing) => thing.names[0]).join(' or ');
}

module.exports = { findObject, describeChoices };
