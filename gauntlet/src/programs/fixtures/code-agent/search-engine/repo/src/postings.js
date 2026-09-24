'use strict';

/**
 * Inverted index: term → (docId → positions[]), plus each document's length
 * (number of indexed terms).
 */
class InvertedIndex {
  constructor() {
    this.postings = new Map();
    this.lengths = new Map();
  }

  add(docId, tokens) {
    if (this.lengths.has(docId)) this.remove(docId);
    this.lengths.set(docId, tokens.length);
    for (const t of tokens) {
      let docs = this.postings.get(t.term);
      if (!docs) this.postings.set(t.term, (docs = new Map()));
      let positions = docs.get(docId);
      if (!positions) docs.set(docId, (positions = []));
      positions.push(t.position);
    }
  }

  remove(docId) {
    if (!this.lengths.has(docId)) return false;
    this.lengths.delete(docId);
    for (const [term, docs] of this.postings) {
      docs.delete(docId);
      if (docs.size === 0) this.postings.delete(term);
    }
    return true;
  }

  /** docId → positions for a term (empty map when unknown). */
  docsWith(term) {
    return this.postings.get(term) ?? new Map();
  }

  docFreq(term) {
    return this.docsWith(term).size;
  }

  termFreq(term, docId) {
    return this.docsWith(term).get(docId)?.length ?? 0;
  }

  docLength(docId) {
    return this.lengths.get(docId) ?? 0;
  }

  get docCount() {
    return this.lengths.size;
  }

  /** Every indexed term, alphabetically (for suggestions and the admin page). */
  vocabulary() {
    return [...this.postings.keys()].sort();
  }

  /** Index size figures for the admin page. */
  stats() {
    let postings = 0;
    for (const docs of this.postings.values()) postings += docs.size;
    return { documents: this.lengths.size, terms: this.postings.size, postings, averageLength: this.averageLength() };
  }

  averageLength() {
    if (this.lengths.size === 0) return 0;
    let total = 0;
    for (const n of this.lengths.values()) total += n;
    return total / this.lengths.size;
  }
}

module.exports = { InvertedIndex };
