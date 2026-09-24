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
    const request = this.transport(path);
    const value = await request;
    this.inflight.set(path, request);
    this.cache.set(path, value);
    this.inflight.delete(path);
    return value;
  }

  getUser(id) {
    return this.getJson(`/users/${id}`);
  }

  getUsers(ids) {
    return Promise.all(ids.map(this.getUser));
  }

  stats() {
    return { ...this.counters };
  }
}

function createClient(options) {
  return new ApiClient(options);
}

module.exports = { ApiClient, createClient };
