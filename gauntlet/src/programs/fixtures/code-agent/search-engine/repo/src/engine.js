'use strict';

const { DocStore } = require('./docstore');
const { InvertedIndex } = require('./postings');
const { indexTerms, analyze } = require('./analyzer');
const { runSearch } = require('./search');
const { parseQuery, scoringTerms } = require('./query-parser');
const { highlightText, snippet } = require('./highlight');
const { suggestTerm } = require('./suggest');
const { tagFacets, filterByTags } = require('./facets');

/** Simulated fetch latency of the content service (deterministic per id). */
function latency(id) {
  let h = 7;
  for (const ch of id) h = (h * 33 + ch.charCodeAt(0)) % 1009;
  return h % 9;
}

function createEngine() {
  const store = new DocStore();
  const index = new InvertedIndex();

  const engine = {
    add(doc) {
      const stored = store.put(doc);
      index.add(stored.id, indexTerms(store.fullText(stored.id)));
      return stored;
    },
    /** Index a document fetched from the content service (asynchronous). */
    async addAsync(doc) {
      await new Promise((resolve) => setTimeout(resolve, latency(doc.id)));
      return engine.add(doc);
    },
    remove(id) {
      index.remove(id);
      return store.delete(id);
    },
    search(query, options) {
      return runSearch({ index, store }, query, options);
    },
    highlight(id, query) {
      return highlightText(store.fullText(id), scoringTerms(parseQuery(query)));
    },
    snippet(id, query, maxWords) {
      return snippet(store.fullText(id), scoringTerms(parseQuery(query)), maxWords);
    },
    /** "Did you mean": the query with each unknown word replaced by its closest indexed term, or null. */
    suggest(query) {
      let changed = false;
      const words = String(query)
        .split(/\s+/)
        .filter(Boolean)
        .map((word) => {
          if (word.startsWith('-') || word.includes('"')) return word;
          const tokens = indexTerms(word);
          if (tokens.length !== 1) return word;
          const better = suggestTerm(index, tokens[0].term);
          if (!better) return word;
          changed = true;
          return better;
        });
      return changed ? words.join(' ') : null;
    },
    /** Tag counts over every result of a query (not just one page). */
    facets(query, limit) {
      const all = runSearch({ index, store }, query, { page: 1, pageSize: 100 });
      return tagFacets(store, all.hits.map((h) => h.id), limit);
    },
    /** Search, keeping only results that carry every tag. */
    searchTagged(query, tags, options) {
      const all = runSearch({ index, store }, query, { page: 1, pageSize: 100 });
      const ids = new Set(filterByTags(store, all.hits.map((h) => h.id), tags));
      const hits = all.hits.filter((h) => ids.has(h.id));
      const { page = 1, pageSize = 10 } = options ?? {};
      return { total: hits.length, page, hits: hits.slice((page - 1) * pageSize, page * pageSize) };
    },
    stats: () => index.stats(),
    analyze,
    get size() {
      return store.size;
    },
  };
  return engine;
}

module.exports = { createEngine };
