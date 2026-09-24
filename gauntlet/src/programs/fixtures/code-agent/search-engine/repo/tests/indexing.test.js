'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createEngine } = require('../src');
const { HELP_CENTRE, helpCentre } = require('./helpers');

// addAsync waits a different amount of time for every document, so the
// documents land in the index in a different order than they were sent.
// The results must not depend on that order.
test('indexing concurrently gives the same results as indexing in order', async () => {
  const engine = createEngine();
  await Promise.all(HELP_CENTRE.map((doc) => engine.addAsync(doc)));
  const sequential = helpCentre();
  for (const q of ['password', 'tower london', 'account data', '"tower bridge"']) {
    assert.deepEqual(engine.search(q), sequential.search(q), q);
  }
});

test('removed documents disappear from results', () => {
  const engine = helpCentre();
  engine.remove('kb-6');
  assert.deepEqual(engine.search('password').hits.map((h) => h.id), ['kb-1']);
  assert.equal(engine.size, 5);
});
