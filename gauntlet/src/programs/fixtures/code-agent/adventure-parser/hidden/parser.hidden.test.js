'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { parse } = require('../src');

const ROOM = [
  { id: 'lamp', names: ['brass lamp', 'lamp'] },
  { id: 'red-key', names: ['red key', 'key'] },
  { id: 'blue-key', names: ['blue key', 'key'] },
  { id: 'chest', names: ['oak chest', 'chest', 'box'] },
  { id: 'note', names: ['note', 'scrap of paper'] },
  { id: 'rope', names: ['rope'] },
];

const blank = { verb: null, object: null, target: null, preposition: null, direction: null, error: null };

test('one-word names with every kind of verb', () => {
  assert.deepEqual(parse('x note', ROOM), { ...blank, verb: 'examine', object: 'note' });
  assert.deepEqual(parse('grab rope', ROOM), { ...blank, verb: 'take', object: 'rope' });
  assert.deepEqual(parse('open box', ROOM), { ...blank, verb: 'open', object: 'chest' });
});

test('look at X means examine X', () => {
  assert.deepEqual(parse('look at the lamp', ROOM), { ...blank, verb: 'examine', object: 'lamp' });
});

test('pick up X means take X', () => {
  assert.deepEqual(parse('pick up the oak chest', ROOM), { ...blank, verb: 'take', object: 'chest' });
  assert.deepEqual(parse('pick up rope', ROOM), { ...blank, verb: 'take', object: 'rope' });
});

test('three-word names', () => {
  assert.deepEqual(parse('read the scrap of paper', ROOM), { ...blank, verb: 'read', object: 'note' });
});

test('one-word object and target', () => {
  assert.deepEqual(parse('unlock chest with blue key', ROOM), { ...blank, verb: 'unlock', object: 'chest', target: 'blue-key', preposition: 'with' });
  assert.deepEqual(parse('put rope in box', ROOM), { ...blank, verb: 'put', object: 'rope', target: 'chest', preposition: 'in' });
});

test('ambiguous one-word names ask which one', () => {
  const c = parse('drop key', ROOM);
  assert.equal(c.verb, 'drop');
  assert.equal(c.object, null);
  assert.equal(c.error, 'Which do you mean: the red key or blue key?');
});

test('the longest name wins', () => {
  assert.equal(parse('take the red key', ROOM).object, 'red-key');
  assert.equal(parse('take blue key', ROOM).object, 'blue-key');
});

test('every call returns a new object', () => {
  const a = parse('take brass lamp', ROOM);
  const b = parse('n', ROOM);
  assert.notEqual(a, b);
  assert.equal(a.verb, 'take');
  assert.equal(a.object, 'lamp');
  assert.equal(b.object, null);
});

test('an earlier error does not leak into the next command', () => {
  parse('frobnicate the lamp', ROOM);
  assert.deepEqual(parse('take brass lamp', ROOM), { ...blank, verb: 'take', object: 'lamp' });
});

test('changing a returned command does not affect later ones', () => {
  const c = parse('take brass lamp', ROOM);
  c.target = 'hacked';
  c.direction = 'nowhere';
  assert.deepEqual(parse('inventory', ROOM), { ...blank, verb: 'inventory' });
});

test('an earlier direction does not leak', () => {
  parse('go sw', ROOM);
  assert.deepEqual(parse('examine oak chest', ROOM), { ...blank, verb: 'examine', object: 'chest' });
});

test('movement', () => {
  assert.deepEqual(parse('go sw', ROOM), { ...blank, verb: 'go', direction: 'southwest' });
  assert.deepEqual(parse('Walk North.', ROOM), { ...blank, verb: 'go', direction: 'north' });
  assert.equal(parse('go', ROOM).error, 'Go where?');
});

test('things that are not here', () => {
  assert.equal(parse('take lantern', ROOM).error, "You can't see any lantern here.");
  assert.equal(parse('take lamp', []).error, "You can't see any lamp here.");
  assert.equal(parse('   ', ROOM).error, 'Say something.');
});
