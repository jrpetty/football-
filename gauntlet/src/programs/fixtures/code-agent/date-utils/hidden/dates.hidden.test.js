'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const d = require('../src');

test('the 400-year rule', () => {
  for (const y of [1600, 2000, 2400]) assert.equal(d.isLeapYear(y), true, String(y));
  for (const y of [1700, 1800, 1900, 2100, 2200]) assert.equal(d.isLeapYear(y), false, String(y));
  for (const y of [1996, 2004, 2024, 2028]) assert.equal(d.isLeapYear(y), true, String(y));
  for (const y of [1999, 2001, 2023]) assert.equal(d.isLeapYear(y), false, String(y));
});

test('February lengths', () => {
  assert.equal(d.daysInMonth(2000, 2), 29);
  assert.equal(d.daysInMonth(1900, 2), 28);
  assert.equal(d.daysInMonth(2023, 2), 28);
  assert.equal(d.daysInMonth(2023, 12), 31);
});

test('29 February 2000 exists', () => {
  assert.deepEqual(d.parseDate('2000-02-29'), { year: 2000, month: 2, day: 29 });
  assert.equal(d.addDays('2000-02-29', 1), '2000-03-01');
  assert.throws(() => d.parseDate('1900-02-29'), RangeError);
});

test('dayOfYear', () => {
  assert.equal(d.dayOfYear('2023-12-31'), 365);
  assert.equal(d.dayOfYear('2000-12-31'), 366);
  assert.equal(d.dayOfYear('2024-03-01'), 61);
});

test('addMonths clamping in both directions', () => {
  assert.equal(d.addMonths('2024-03-31', 1), '2024-04-30');
  assert.equal(d.addMonths('2023-01-31', 1), '2023-02-28');
  assert.equal(d.addMonths('2000-01-31', 1), '2000-02-29');
  assert.equal(d.addMonths('2024-02-29', 12), '2025-02-28');
  assert.equal(d.addMonths('2024-03-31', -1), '2024-02-29');
  assert.equal(d.addMonths('2024-10-31', -1), '2024-09-30');
});

test('addMonths without clamping', () => {
  assert.equal(d.addMonths('2024-01-31', 2), '2024-03-31');
  assert.equal(d.addMonths('2024-05-15', -3), '2024-02-15');
  assert.equal(d.addMonths('2024-11-30', 3), '2025-02-28');
  assert.equal(d.addMonths('2024-12-15', 1), '2025-01-15');
  assert.equal(d.addMonths('2025-01-15', -1), '2024-12-15');
});

test('weekday for known dates', () => {
  assert.equal(d.weekday('1970-01-01'), 'Thursday');
  assert.equal(d.weekday('2000-01-01'), 'Saturday');
  assert.equal(d.weekday('2000-02-29'), 'Tuesday');
  assert.equal(d.weekday('2024-02-29'), 'Thursday');
  assert.equal(d.weekday('2100-03-01'), 'Monday');
  assert.equal(d.weekday('2026-09-27'), 'Sunday');
});

test('isWeekend', () => {
  assert.equal(d.isWeekend('2024-09-28'), true);
  assert.equal(d.isWeekend('2024-09-29'), true);
  assert.equal(d.isWeekend('2024-09-27'), false);
  assert.equal(d.isWeekend('2024-09-30'), false);
});

test('business days over longer ranges', () => {
  assert.equal(d.businessDaysBetween('2024-02-26', '2024-03-11'), 10);
  assert.equal(d.businessDaysBetween('2024-09-28', '2024-09-30'), 0);
  assert.equal(d.businessDaysBetween('2024-09-27', '2024-09-28'), 1);
  assert.equal(d.businessDaysBetween('2000-02-25', '2000-03-03'), 5);
  assert.equal(d.businessDaysBetween('2024-09-24', '2024-09-24'), 0);
});

test('daysBetween across a leap day', () => {
  assert.equal(d.daysBetween('1999-12-31', '2000-03-01'), 61);
  assert.equal(d.daysBetween('2023-01-01', '2024-01-01'), 365);
});

test('formatDate pads', () => {
  assert.equal(d.formatDate({ year: 987, month: 3, day: 7 }), '0987-03-07');
});
