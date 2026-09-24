'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createEngine } = require('../src');
const { helpCentre } = require('./helpers');

test('plural queries find singular words', () => {
  const r = helpCentre().search('bridges');
  assert.deepEqual(r.hits.map((h) => h.id), ['kb-5']);
});

test('documents that mention a word more often rank higher', () => {
  const r = helpCentre().search('password');
  assert.deepEqual(r.hits.map((h) => h.id), ['kb-6', 'kb-1']);
  assert.ok(r.hits[0].score > r.hits[1].score);
});

test('identical FAQ entries: top hit is highlighted and ties are ordered by id', () => {
  const engine = createEngine();
  for (const id of ['faq-3', 'faq-1', 'faq-2']) engine.add({ id, title: 'Towel day', body: "Don't panic." });
  const r = engine.search("don't panic");
  assert.equal(engine.highlight(r.hits[0].id, "don't panic"), 'Towel day\n\n<mark>Don&#39;t</mark> <mark>panic</mark>.');
  assert.deepEqual(r.hits.map((h) => h.id), ['faq-1', 'faq-2', 'faq-3']);
});

test('pagination', () => {
  const engine = helpCentre();
  const all = engine.search('password tower');
  const second = engine.search('password tower', { page: 2, pageSize: 1 });
  assert.equal(second.total, all.total);
  assert.deepEqual(second.hits.map((h) => h.id), [all.hits[1].id]);
});
