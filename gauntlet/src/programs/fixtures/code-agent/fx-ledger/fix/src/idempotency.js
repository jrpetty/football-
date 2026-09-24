'use strict';

/**
 * Remembers the outcome of operations by idempotency key, so a client retry
 * gets the original result instead of repeating the operation.
 */
class IdempotencyRegistry {
  constructor() {
    this.results = new Map();
  }

  /**
   * Run `operation` once per key; later calls with the same key — including
   * calls made while the first is still running — share its result. A failed
   * operation frees the key so it can be retried.
   */
  once(key, operation) {
    if (this.results.has(key)) return this.results.get(key);
    const pending = Promise.resolve().then(operation);
    this.results.set(key, pending);
    pending.catch(() => {
      if (this.results.get(key) === pending) this.results.delete(key);
    });
    return pending;
  }

  has(key) {
    return this.results.has(key);
  }

  get size() {
    return this.results.size;
  }
}

module.exports = { IdempotencyRegistry };
