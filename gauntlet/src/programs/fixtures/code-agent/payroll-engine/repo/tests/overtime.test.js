'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { splitOvertime, renderPayslip } = require('../src');
const { payrollWithStaff, work } = require('./helpers');

const WEEK1 = ['2025-03-03', '2025-03-04', '2025-03-05', '2025-03-06', '2025-03-07'];
const WEEK2 = ['2025-03-10', '2025-03-11', '2025-03-12', '2025-03-13', '2025-03-14'];

test('hours above 40 in a week are overtime', () => {
  const r = splitOvertime(WEEK1.map((date) => ({ date, hours: 9 })));
  assert.equal(r.regularHours, 40);
  assert.equal(r.overtimeHours, 5);
});

test('each week is counted on its own', () => {
  const entries = [...WEEK1, ...WEEK2].map((date) => ({ date, hours: 8.4 }));
  const r = splitOvertime(entries);
  assert.equal(r.regularHours, 80);
  assert.ok(Math.abs(r.overtimeHours - 4) < 1e-9);
});

test('hourly payslip pays overtime at time and a half', () => {
  const payroll = payrollWithStaff('monthly');
  work(payroll, 'E03', WEEK1, 9);
  const p = payroll.payslip('E03', { start: '2025-03-01', end: '2025-03-31' });
  assert.equal(p.gross, 114000);
  assert.equal(p.pension, 3420);
  assert.match(renderPayslip(p), /Hours 40 \+ 5 overtime/);
});
