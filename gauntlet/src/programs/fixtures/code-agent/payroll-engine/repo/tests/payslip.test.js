'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { payrollWithStaff } = require('./helpers');

const MARCH = { start: '2025-03-01', end: '2025-03-31' };

test('monthly payslip for a salaried employee', () => {
  const p = payrollWithStaff('monthly').payslip('E01', MARCH);
  assert.equal(p.gross, 500000);
  assert.equal(p.pension, 25000);
  assert.equal(p.tax, 86667);
  assert.equal(p.net, 388333);
});

test('biweekly payslip: income tax', () => {
  const p = payrollWithStaff('biweekly').payslip('E01', { start: '2025-03-03', end: '2025-03-16' });
  assert.equal(p.tax, 40000);
  assert.equal(p.gross, 230769);
  assert.equal(p.net, 179231);
});

test('union dues come off net pay but not taxable pay', () => {
  const p = payrollWithStaff('monthly').payslip('E02', MARCH);
  assert.equal(p.taxable, 260000);
  assert.equal(p.tax, 32000);
  assert.equal(p.net, 226500);
});
