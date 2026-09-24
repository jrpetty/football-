'use strict';

/** Simulated database round trip: resolves on a later turn of the event loop. */
function roundTrip() {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/** Asynchronous key-value store (a database in production). */
function createStore() {
  const data = new Map();
  return {
    async get(key) {
      await roundTrip();
      return data.get(key);
    },
    async set(key, value) {
      await roundTrip();
      data.set(key, value);
    },
    async keys(prefix = '') {
      await roundTrip();
      return [...data.keys()].filter((k) => k.startsWith(prefix)).sort();
    },
  };
}

module.exports = { createStore, roundTrip };
