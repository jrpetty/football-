'use strict';

/**
 * Least-recently-used cache. A Map keeps insertion order, so the first key is
 * always the least recently used one as long as every "use" re-inserts it.
 */
class LRUCache {
  constructor({ capacity = 100, ttlMs = 0, now = () => Date.now() } = {}) {
    if (!Number.isInteger(capacity) || capacity < 1) throw new RangeError(`capacity must be a positive integer, got ${capacity}`);
    this.capacity = capacity;
    this.ttlMs = ttlMs;
    this.now = now;
    this.map = new Map();
  }

  get size() {
    return this.map.size;
  }

  _expired(entry) {
    return this.ttlMs > 0 && this.now() - entry.storedAt >= this.ttlMs;
  }

  _live(key) {
    const entry = this.map.get(key);
    if (entry === undefined) return undefined;
    if (this._expired(entry)) {
      this.map.delete(key);
      return undefined;
    }
    return entry;
  }

  get(key) {
    const entry = this._live(key);
    if (entry === undefined) return undefined;
    this.map.delete(key);
    this.map.set(key, entry);
    return entry.value;
  }

  peek(key) {
    const entry = this._live(key);
    return entry === undefined ? undefined : entry.value;
  }

  has(key) {
    return this._live(key) !== undefined;
  }

  set(key, value) {
    if (this.map.has(key)) this.map.delete(key);
    this.map.set(key, { value, storedAt: this.now() });
    while (this.map.size > this.capacity) {
      const oldest = this.map.keys().next().value;
      this.map.delete(oldest);
    }
    return this;
  }

  delete(key) {
    return this.map.delete(key);
  }

  clear() {
    this.map.clear();
  }

  /** Keys from least to most recently used. */
  keys() {
    return [...this.map.keys()].filter((key) => this.has(key));
  }
}

module.exports = { LRUCache };
