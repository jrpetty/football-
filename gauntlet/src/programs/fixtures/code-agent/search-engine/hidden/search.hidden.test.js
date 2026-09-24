'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createEngine, analyze, idf, stem } = require('../src');

/** BM25 straight from the README, for checking exact scores. */
function readmeScore({ N, df, tf, dl, avgdl }) {
  const i = Math.log(1 + (N - df + 0.5) / (df + 0.5));
  return (i * tf * 2.2) / (tf + 1.2 * (1 - 0.75 + (0.75 * dl) / avgdl));
}

function engineWith(docs) {
  const engine = createEngine();
  for (const d of docs) engine.add(d);
  return engine;
}

test('analyzer offsets cover the original word, apostrophes included', () => {
  const text = "Don't stop: rock'n'roll's back";
  const tokens = analyze(text);
  assert.deepEqual(tokens.map((t) => text.slice(t.start, t.end)), ["Don't", 'stop', "rock'n'roll's", 'back']);
  assert.deepEqual(tokens.map((t) => [t.term, t.position]), [['dont', 0], ['stop', 1], ['rocknroll', 2], ['back', 3]]);
});

test('highlighting several words with apostrophes', () => {
  const engine = engineWith([{ id: 'q', title: "Driver's guide", body: "Don't forget the driver's licence. Drivers' rights apply." }]);
  assert.equal(engine.highlight('q', 'driver'), '<mark>Driver&#39;s</mark> guide\n\nDon&#39;t forget the <mark>driver&#39;s</mark> licence. <mark>Drivers</mark>&#39; rights apply.');
});

test('snippets never cut a word in half', () => {
  const engine = engineWith([{ id: 's', title: '', body: "We can't reset it for you, but support's team will help." }]);
  assert.equal(engine.snippet('s', 'reset', 2), 'We can&#39;t…');
  assert.equal(engine.snippet('s', 'reset support', 8), 'We can&#39;t <mark>reset</mark> it for you, but <mark>support&#39;s</mark>…');
});

test('idf follows the README formula and is never negative', () => {
  assert.ok(Math.abs(idf(6, 2) - Math.log(2.8)) < 1e-12);
  assert.ok(Math.abs(idf(5, 4) - Math.log(1 + 1.5 / 4.5)) < 1e-12);
  assert.ok(idf(10, 10) > 0);
});

const DATA = [
  { id: 'd1', title: 'Data', body: 'data data about the weather' },
  { id: 'd2', title: 'Notes', body: 'some data on mining trucks' },
  { id: 'd3', title: 'Gold', body: 'mining for gold in the hills' },
  { id: 'd4', title: 'Records', body: 'raw data files' },
  { id: 'd5', title: 'Charts', body: 'charts of data' },
];

test('exact BM25 scores', () => {
  const engine = engineWith(DATA);
  // Indexed lengths: d1 5 (data data data about weather), d2 5, d3 4, d4 4, d5 3 → avgdl 4.2.
  const r = engine.search('mining');
  assert.deepEqual(r.hits.map((h) => h.id), ['d3', 'd2']);
  assert.ok(Math.abs(r.hits[0].score - readmeScore({ N: 5, df: 2, tf: 1, dl: 4, avgdl: 4.2 })) < 1e-9);
  assert.ok(Math.abs(r.hits[1].score - readmeScore({ N: 5, df: 2, tf: 1, dl: 5, avgdl: 4.2 })) < 1e-9);
});

test('a word in most documents still counts in favour of a document', () => {
  const engine = engineWith(DATA);
  const r = engine.search('data');
  assert.equal(r.hits[0].id, 'd1');
  assert.ok(r.hits.every((h) => h.score > 0));
  assert.ok(Math.abs(r.hits[0].score - readmeScore({ N: 5, df: 4, tf: 3, dl: 5, avgdl: 4.2 })) < 1e-9);
});

test('matching more query words ranks higher', () => {
  const engine = engineWith(DATA);
  const r = engine.search('data mining');
  assert.equal(r.hits[0].id, 'd2');
  assert.deepEqual(r.hits.map((h) => h.id).slice(0, 2), ['d2', 'd3']);
});

test('ties are ordered by id on every page', () => {
  const engine = createEngine();
  for (const id of ['t4', 't2', 't5', 't1', 't3']) engine.add({ id, title: 'Same', body: 'identical text' });
  const pages = [1, 2, 3].map((page) => engine.search('identical', { page, pageSize: 2 }).hits.map((h) => h.id));
  assert.deepEqual(pages, [['t1', 't2'], ['t3', 't4'], ['t5']]);
});

test('ties do not depend on indexing order', async () => {
  const engine = createEngine();
  await Promise.all(['b', 'e', 'a', 'd', 'c'].map((id) => engine.addAsync({ id, title: 'Status', body: 'service outage' })));
  assert.deepEqual(engine.search('outage').hits.map((h) => h.id), ['a', 'b', 'c', 'd', 'e']);
});

test('phrases count stop words and apostrophe words', () => {
  const engine = engineWith([
    { id: 'p1', title: '', body: "The heart of the matter is what's left" },
    { id: 'p2', title: '', body: 'The heart matter is left' },
  ]);
  assert.deepEqual(engine.search('"heart of the matter"').hits.map((h) => h.id), ['p1']);
  assert.deepEqual(engine.search('"what\'s left"').hits.map((h) => h.id), ['p1']);
});

test('the documented stemmer, news included', () => {
  assert.deepEqual(['news', 'glasses', 'status', 'series', 'flies', 'dies'].map(stem), ['new', 'glasse', 'status', 'sery', 'fly', 'die']);
});
