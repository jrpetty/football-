'use strict';

const { parseQuery, scoringTerms } = require('./query-parser');
const { matchesPhrase } = require('./phrase');
const { scoreDocument } = require('./bm25');
const { rank, paginate } = require('./ranking');

/** Run a query against an index + doc store. */
function runSearch({ index, store }, query, { page = 1, pageSize = 10 } = {}) {
  const parsed = parseQuery(query);
  const terms = scoringTerms(parsed);
  if (terms.length === 0) return { total: 0, page, hits: [] };

  const candidates = new Set();
  for (const term of terms) for (const docId of index.docsWith(term).keys()) candidates.add(docId);

  const scores = new Map();
  for (const docId of candidates) {
    if (parsed.excluded.some((t) => index.termFreq(t, docId) > 0)) continue;
    if (!parsed.phrases.every((p) => matchesPhrase(index, docId, p))) continue;
    scores.set(docId, scoreDocument(index, docId, terms));
  }

  const ranked = rank(scores);
  const hits = paginate(ranked, page, pageSize).map(({ id, score }) => ({ id, title: store.get(id).title, score }));
  return { total: ranked.length, page, hits };
}

module.exports = { runSearch };
