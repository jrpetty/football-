'use strict';

/**
 * Tag facets for the sidebar: how many documents in a result set carry each
 * tag, most common first (ties alphabetical). Tags are compared
 * case-insensitively but shown as first written.
 */
function tagFacets(store, docIds, limit = 10) {
  const counts = new Map();
  for (const id of docIds) {
    const seen = new Set();
    for (const tag of store.get(id).tags) {
      const key = tag.toLowerCase();
      if (seen.has(key)) continue; // a document counts once per tag
      seen.add(key);
      const entry = counts.get(key) ?? { tag, count: 0 };
      entry.count += 1;
      counts.set(key, entry);
    }
  }
  return [...counts.values()]
    .sort((a, b) => b.count - a.count || a.tag.toLowerCase().localeCompare(b.tag.toLowerCase()))
    .slice(0, limit);
}

/** Keep only documents that carry every one of `tags`. */
function filterByTags(store, docIds, tags) {
  const wanted = tags.map((t) => t.toLowerCase());
  return docIds.filter((id) => {
    const have = new Set(store.get(id).tags.map((t) => t.toLowerCase()));
    return wanted.every((t) => have.has(t));
  });
}

module.exports = { tagFacets, filterByTags };
