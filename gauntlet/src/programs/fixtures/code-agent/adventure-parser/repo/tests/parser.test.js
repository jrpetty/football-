'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { parse, tokenize } = require('../src');

const ROOM = [
  { id: 'lamp', names: ['brass lamp', 'lamp'] },
  { id: 'red-key', names: ['red key', 'key'] },
  { id: 'blue-key', names: ['blue key', 'key'] },
  { id: 'chest', names: ['oak chest', 'chest', 'box'] },
  { id: 'note', names: ['note', 'scrap of paper'] },
];

test('tokenize lowercases and drops punctuation and articles', () => {
  assert.deepEqual(tokenize('Take THE Lamp!'), ['take', 'lamp']);
});

test('take a two-word object', () => {
  const c = parse('take the brass lamp', ROOM);
  assert.equal(c.verb, 'take');
  assert.equal(c.object, 'lamp');
  assert.equal(c.error, null);
});

test('verb synonyms and one-word names', () => {
  const c = parse('get lamp', ROOM);
  assert.equal(c.verb, 'take');
  assert.equal(c.object, 'lamp');
});

test('put something in something', () => {
  const c = parse('put the red key in the oak chest', ROOM);
  assert.deepEqual(c, { verb: 'put', object: 'red-key', target: 'chest', preposition: 'in', direction: null, error: null });
});

test('a bare direction moves the player', () => {
  const c = parse('n', ROOM);
  assert.equal(c.verb, 'go');
  assert.equal(c.direction, 'north');
});

test('an ambiguous name asks which one', () => {
  const c = parse('take key', ROOM);
  assert.equal(c.object, null);
  assert.match(c.error, /Which do you mean/);
});

test('each command starts from scratch', () => {
  parse('take brass lamp', ROOM);
  const c = parse('look', ROOM);
  assert.equal(c.verb, 'look');
  assert.equal(c.object, null);
});

test('unknown verbs are reported', () => {
  const c = parse('dance wildly', ROOM);
  assert.equal(c.verb, null);
  assert.match(c.error, /don't know the verb "dance"/);
});
