'use strict';

/**
 * Remembers the outcome of operations by idempotency key, so a client retry
 * gets the original result instead of repeating the operation.
 */
class IdempotencyRegistry {
  constructor() {
    this.results = new Map();
  }

  /** Run `operation` once per key; later calls with the same key return the first result. */
  async once(key, operation) {
    if (this.results.has(key)) return this.results.get(key);
    const result = await operation();
    this.results.set(key, result);
    return result;
  }

  has(key) {
    return this.results.has(key);
  }

  get size() {
    return this.results.size;
  }
}

module.exports = { IdempotencyRegistry };
