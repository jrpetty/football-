'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { parseTime, formatTime, parseDuration } = require('../src');

test('parseTime and formatTime round-trip', () => {
  assert.equal(parseTime('09:30'), 570);
  assert.equal(formatTime(570), '09:30');
  assert.equal(formatTime(parseTime('24:00')), '24:00');
  assert.throws(() => parseTime('9.30'));
});

test('parseDuration', () => {
  assert.equal(parseDuration('1h30m'), 90);
  assert.equal(parseDuration('45m'), 45);
  assert.equal(parseDuration('2h'), 120);
  assert.equal(parseDuration(15), 15);
  assert.throws(() => parseDuration('soon'));
});
