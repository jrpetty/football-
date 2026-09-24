'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { stem, analyze, createEngine } = require('../src');
const { helpCentre } = require('./helpers');

test('phrases need the words in place', () => {
  assert.deepEqual(helpCentre().search('"tower of london"').hits.map((h) => h.id), ['kb-4']);
});

test('exclusions drop documents', () => {
  assert.deepEqual(helpCentre().search('tower -closed').hits.map((h) => h.id), ['kb-5']);
});

test('stemming rules', () => {
  assert.deepEqual(['stories', 'bridges', 'glass', 'bus', 'is', 'passkeys'].map(stem), ['story', 'bridge', 'glass', 'bus', 'is', 'passkey']);
  assert.deepEqual(analyze('The tower').map((t) => [t.term, t.position, t.stop]), [['the', 0, true], ['tower', 1, false]]);
});

test('did you mean', () => {
  const engine = helpCentre();
  assert.equal(engine.suggest('pasword reset'), 'password reset');
  assert.equal(engine.suggest('towr brige'), 'tower bridge');
  assert.equal(engine.suggest('tower'), null);
});

test('tag facets and tag filters', () => {
  const engine = createEngine();
  engine.add({ id: 'a', title: 'Login help', body: 'password reset', tags: ['Account', 'security'] });
  engine.add({ id: 'b', title: 'Passkeys', body: 'password alternative', tags: ['security', 'Security'] });
  engine.add({ id: 'c', title: 'Billing', body: 'invoice copies', tags: ['billing'] });
  assert.deepEqual(engine.facets('password'), [
    { tag: 'security', count: 2 },
    { tag: 'Account', count: 1 },
  ]);
  assert.deepEqual(engine.searchTagged('password', ['account']).hits.map((h) => h.id), ['a']);
});
