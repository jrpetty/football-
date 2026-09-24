'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { LRUCache, createClient, memoryTransport } = require('../src');

const USERS = {};
for (let i = 1; i <= 6; i++) USERS[`/users/${i}`] = { id: i, name: `user-${i}` };

test('LRU order follows gets, not just sets', () => {
  const cache = new LRUCache({ capacity: 3 });
  cache.set('a', 1).set('b', 2).set('c', 3);
  cache.get('a');
  cache.get('b');
  assert.deepEqual(cache.keys(), ['c', 'a', 'b']);
  cache.set('d', 4);
  assert.deepEqual(cache.keys(), ['a', 'b', 'd']);
});

test('peek and has do not refresh recency', () => {
  const cache = new LRUCache({ capacity: 2 });
  cache.set('a', 1).set('b', 2);
  assert.equal(cache.peek('a'), 1);
  assert.equal(cache.has('a'), true);
  cache.set('c', 3);
  assert.equal(cache.has('a'), false);
  assert.deepEqual(cache.keys(), ['b', 'c']);
});

test('replacing a value makes it most recent', () => {
  const cache = new LRUCache({ capacity: 2 });
  cache.set('a', 1).set('b', 2).set('a', 10).set('c', 3);
  assert.deepEqual(cache.keys(), ['a', 'c']);
  assert.equal(cache.get('a'), 10);
});

test('capacity 1 with gets', () => {
  const cache = new LRUCache({ capacity: 1 });
  cache.set('a', 1);
  assert.equal(cache.get('a'), 1);
  cache.set('b', 2);
  assert.equal(cache.get('a'), undefined);
  assert.equal(cache.get('b'), 2);
  assert.equal(cache.size, 1);
});

test('a long access pattern', () => {
  const cache = new LRUCache({ capacity: 3 });
  for (const k of ['a', 'b', 'c']) cache.set(k, k);
  for (const k of ['a', 'c', 'a', 'b']) cache.get(k);
  cache.set('d', 'd');
  cache.set('e', 'e');
  assert.deepEqual(cache.keys(), ['b', 'd', 'e']);
});

test('expired entries are gone for get, has and keys', () => {
  let t = 0;
  const cache = new LRUCache({ capacity: 3, ttlMs: 50, now: () => t });
  cache.set('a', 1);
  t = 30;
  cache.set('b', 2);
  t = 60;
  assert.equal(cache.has('a'), false);
  assert.equal(cache.get('b'), 2);
  assert.deepEqual(cache.keys(), ['b']);
});

test('three concurrent callers, one request', async () => {
  const transport = memoryTransport(USERS, { delayMs: 3 });
  const client = createClient({ transport });
  const results = await Promise.all([client.getUser(4), client.getUser(4), client.getUser(4)]);
  assert.deepEqual(results.map((u) => u.id), [4, 4, 4]);
  assert.deepEqual(transport.calls, ['/users/4']);
  assert.deepEqual(client.stats(), { requests: 1, hits: 0 });
});

test('concurrent calls for different paths are independent', async () => {
  const transport = memoryTransport(USERS, { delayMs: 2 });
  const client = createClient({ transport });
  await Promise.all([client.getUser(1), client.getUser(2), client.getUser(1), client.getUser(2)]);
  assert.deepEqual([...transport.calls].sort(), ['/users/1', '/users/2']);
});

test('getUsers de-duplicates repeated ids', async () => {
  const transport = memoryTransport(USERS, { delayMs: 2 });
  const client = createClient({ transport });
  const users = await client.getUsers([5, 1, 5, 1]);
  assert.deepEqual(users.map((u) => u.id), [5, 1, 5, 1]);
  assert.equal(transport.calls.length, 2);
});

test('a shared failure rejects every waiting caller, and the next call retries', async () => {
  const transport = memoryTransport(USERS, { delayMs: 2, failures: { '/users/3': 1 } });
  const client = createClient({ transport });
  const settled = await Promise.allSettled([client.getUser(3), client.getUser(3)]);
  assert.deepEqual(settled.map((s) => s.status), ['rejected', 'rejected']);
  assert.equal(transport.calls.length, 1);
  assert.deepEqual(await client.getUser(3), { id: 3, name: 'user-3' });
  assert.equal(transport.calls.length, 2);
});

test('a small cache keeps the users that are actually used', async () => {
  const transport = memoryTransport(USERS);
  const client = createClient({ transport, capacity: 2 });
  await client.getUser(1);
  await client.getUser(2);
  await client.getUser(1);
  await client.getUser(3);
  await client.getUser(1);
  assert.deepEqual(transport.calls, ['/users/1', '/users/2', '/users/3']);
  assert.deepEqual(client.stats(), { requests: 3, hits: 2 });
});

test('unknown users reject with 404', async () => {
  const client = createClient({ transport: memoryTransport(USERS) });
  await assert.rejects(client.getUser(99), (err) => err.status === 404);
  await assert.rejects(client.getUsers([1, 99]), /404/);
});
