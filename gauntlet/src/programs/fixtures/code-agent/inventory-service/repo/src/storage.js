'use strict';

/** Simulated I/O latency: resolves on a later turn of the event loop. */
function tick() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/**
 * Asynchronous key-value store. In production this is a database; every call
 * is a round trip, so other work can interleave between a get and a set.
 */
function createStore() {
  const data = new Map();
  return {
    async get(key) {
      await tick();
      return data.has(key) ? data.get(key) : undefined;
    },
    async set(key, value) {
      await tick();
      data.set(key, value);
    },
    async delete(key) {
      await tick();
      data.delete(key);
    },
    async keys(prefix = '') {
      await tick();
      return [...data.keys()].filter((k) => k.startsWith(prefix));
    },
  };
}

module.exports = { createStore, tick };
