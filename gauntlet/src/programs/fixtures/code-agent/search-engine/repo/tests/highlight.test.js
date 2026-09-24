'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createEngine } = require('../src');

test('matches are marked and everything is escaped', () => {
  const engine = createEngine();
  engine.add({ id: 'menu', title: '', body: 'Fish & chips <b>with</b> peas' });
  assert.equal(engine.highlight('menu', 'fish peas'), '<mark>Fish</mark> &amp; chips &lt;b&gt;with&lt;/b&gt; <mark>peas</mark>');
});

test('words with apostrophes are marked whole', () => {
  const engine = createEngine();
  engine.add({ id: 'pet', title: '', body: "It's the dog's bone" });
  assert.equal(engine.highlight('pet', 'dog'), "It&#39;s the <mark>dog&#39;s</mark> bone");
});
