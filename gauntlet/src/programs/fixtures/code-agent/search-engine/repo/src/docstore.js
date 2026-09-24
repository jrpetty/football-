'use strict';

/** The original documents, by id. */
class DocStore {
  constructor() {
    this.docs = new Map();
  }

  put(doc) {
    if (!doc || typeof doc.id !== 'string' || !doc.id) throw new Error('Document needs a string id');
    const stored = Object.freeze({ id: doc.id, title: String(doc.title ?? ''), body: String(doc.body ?? ''), tags: Object.freeze([...(doc.tags ?? [])]) });
    this.docs.set(doc.id, stored);
    return stored;
  }

  get(id) {
    const doc = this.docs.get(id);
    if (!doc) throw new Error(`Unknown document ${id}`);
    return doc;
  }

  has(id) {
    return this.docs.has(id);
  }

  delete(id) {
    return this.docs.delete(id);
  }

  /** Title and body as one text, title first (this is what gets indexed and highlighted). */
  fullText(id) {
    const d = this.get(id);
    return d.title ? `${d.title}\n\n${d.body}` : d.body;
  }

  /**
   * Import many documents at once (the nightly export). Rows without an id are
   * skipped, not rejected: the export contains section headers. Returns the ids stored.
   */
  putMany(rows) {
    const ids = [];
    for (const row of rows) {
      if (!row || !row.id) continue;
      ids.push(this.put(row).id);
    }
    return ids;
  }

  ids() {
    return [...this.docs.keys()].sort();
  }

  get size() {
    return this.docs.size;
  }
}

module.exports = { DocStore };
