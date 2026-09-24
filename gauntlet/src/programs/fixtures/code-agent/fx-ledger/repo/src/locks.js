'use strict';

/**
 * Per-key async mutexes. `withLocks(keys, fn)` takes the locks in sorted key
 * order so two transfers in opposite directions can never deadlock.
 */
class LockManager {
  constructor() {
    this.tails = new Map();
  }

  /** Wait for `key`, then hold it until the returned release function is called. */
  async acquire(key) {
    const previous = this.tails.get(key) ?? Promise.resolve();
    let release;
    const mine = new Promise((resolve) => (release = resolve));
    const tail = previous.then(() => mine);
    this.tails.set(key, tail);
    await previous;
    return () => {
      release();
      // Forget the key once nobody is queued behind us, so the map does not grow forever.
      if (this.tails.get(key) === tail) this.tails.delete(key);
    };
  }

  async withLocks(keys, fn) {
    const ordered = [...new Set(keys)].sort();
    const releases = [];
    try {
      for (const key of ordered) releases.push(await this.acquire(key));
      return await fn();
    } finally {
      for (const release of releases.reverse()) release();
    }
  }
}

module.exports = { LockManager };
