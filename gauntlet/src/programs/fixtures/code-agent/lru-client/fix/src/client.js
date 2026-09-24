'use strict';

const { LRUCache } = require('./lru');

class ApiClient {
  constructor({ transport, capacity = 100, ttlMs = 0, now } = {}) {
    if (typeof transport !== 'function') throw new TypeError('transport must be a function');
    this.transport = transport;
    this.cache = new LRUCache({ capacity, ttlMs, now });
    this.inflight = new Map();
    this.counters = { requests: 0, hits: 0 };
  }

  async getJson(path) {
    const cached = this.cache.get(path);
    if (cached !== undefined) {
      this.counters.hits++;
      return cached;
    }
    if (this.inflight.has(path)) return this.inflight.get(path);

    this.counters.requests++;
    const request = (async () => {
      try {
        const value = await this.transport(path);
        this.cache.set(path, value);
        return value;
      } finally {
        this.inflight.delete(path);
      }
    })();
    this.inflight.set(path, request);
    return request;
  }

  getUser(id) {
    return this.getJson(`/users/${id}`);
  }

  getUsers(ids) {
    return Promise.all(ids.map((id) => this.getUser(id)));
  }

  stats() {
    return { ...this.counters };
  }
}

function createClient(options) {
  return new ApiClient(options);
}

module.exports = { ApiClient, createClient };
