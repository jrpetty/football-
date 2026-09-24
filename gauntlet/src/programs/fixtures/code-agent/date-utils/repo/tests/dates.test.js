'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const d = require('../src');

test('leap years', () => {
  assert.equal(d.isLeapYear(2024), true);
  assert.equal(d.isLeapYear(2023), false);
});

test('century years', () => {
  assert.equal(d.isLeapYear(1900), false);
  assert.equal(d.isLeapYear(2000), true);
});

test('parseDate validates the day of the month', () => {
  assert.deepEqual(d.parseDate('2024-02-29'), { year: 2024, month: 2, day: 29 });
  assert.throws(() => d.parseDate('2023-02-29'), RangeError);
});

test('addDays crosses months and years', () => {
  assert.equal(d.addDays('2024-02-28', 1), '2024-02-29');
  assert.equal(d.addDays('2024-12-31', 1), '2025-01-01');
  assert.equal(d.addDays('2024-03-01', -1), '2024-02-29');
});

test('addMonths clamps to the end of the month', () => {
  assert.equal(d.addMonths('2024-01-31', 1), '2024-02-29');
});

test('daysBetween', () => {
  assert.equal(d.daysBetween('2024-01-01', '2024-03-01'), 60);
  assert.equal(d.daysBetween('2024-03-01', '2024-01-01'), -60);
});

test('weekday', () => {
  assert.equal(d.weekday('2024-09-24'), 'Tuesday');
});

test('business days in a working week', () => {
  assert.equal(d.businessDaysBetween('2024-09-23', '2024-09-28'), 5);
});
