'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { LRUCache } = require('../src/lru');

test('evicts the least recently inserted entry when full', () => {
  const cache = new LRUCache({ capacity: 2 });
  cache.set('a', 1).set('b', 2).set('c', 3);
  assert.equal(cache.has('a'), false);
  assert.deepEqual(cache.keys(), ['b', 'c']);
});

test('get marks an entry as recently used', () => {
  const cache = new LRUCache({ capacity: 2 });
  cache.set('a', 1);
  cache.set('b', 2);
  assert.equal(cache.get('a'), 1);
  cache.set('c', 3);
  assert.equal(cache.has('a'), true);
  assert.equal(cache.has('b'), false);
});

test('entries expire after ttlMs', () => {
  let t = 1000;
  const cache = new LRUCache({ capacity: 5, ttlMs: 100, now: () => t });
  cache.set('a', 1);
  t = 1099;
  assert.equal(cache.get('a'), 1);
  t = 1100;
  assert.equal(cache.get('a'), undefined);
});
