'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createClient, memoryTransport } = require('../src');

const USERS = {
  '/users/1': { id: 1, name: 'Ada' },
  '/users/2': { id: 2, name: 'Grace' },
  '/users/3': { id: 3, name: 'Linus' },
};

test('responses are cached', async () => {
  const transport = memoryTransport(USERS);
  const client = createClient({ transport });
  assert.deepEqual(await client.getUser(1), { id: 1, name: 'Ada' });
  assert.deepEqual(await client.getUser(1), { id: 1, name: 'Ada' });
  assert.equal(transport.calls.length, 1);
  assert.deepEqual(client.stats(), { requests: 1, hits: 1 });
});

test('concurrent calls for the same path share one request', async () => {
  const transport = memoryTransport(USERS, { delayMs: 5 });
  const client = createClient({ transport });
  const [a, b] = await Promise.all([client.getUser(2), client.getUser(2)]);
  assert.deepEqual(a, { id: 2, name: 'Grace' });
  assert.deepEqual(b, { id: 2, name: 'Grace' });
  assert.equal(transport.calls.length, 1);
});

test('getUsers keeps the order of ids', async () => {
  const client = createClient({ transport: memoryTransport(USERS) });
  const users = await client.getUsers([3, 1]);
  assert.deepEqual(users.map((u) => u.name), ['Linus', 'Ada']);
});

test('failures are not cached', async () => {
  const transport = memoryTransport(USERS, { failures: { '/users/1': 1 } });
  const client = createClient({ transport });
  await assert.rejects(client.getUser(1), /503/);
  assert.deepEqual(await client.getUser(1), { id: 1, name: 'Ada' });
  assert.equal(transport.calls.length, 2);
});
