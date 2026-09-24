'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { payrollWithStaff, work } = require('./helpers');

const PERIOD = { start: '2025-03-03', end: '2025-03-16' };

test('biweekly payroll run', async () => {
  const payroll = payrollWithStaff('biweekly');
  work(payroll, 'E03', ['2025-03-03', '2025-03-04', '2025-03-05', '2025-03-06', '2025-03-07'], 8);
  work(payroll, 'E03', ['2025-03-08', '2025-03-09'], 5);
  work(payroll, 'E03', ['2025-03-10', '2025-03-11', '2025-03-12', '2025-03-13'], 9);
  work(payroll, 'E03', ['2025-03-16'], 6);
  const run = await payroll.run(PERIOD);
  assert.equal(run.totals.net, 467541);
  assert.deepEqual(run.payslips.map((p) => p.employeeId), ['E01', 'E02', 'E03']);
  const cy = run.payslips[2];
  assert.equal(cy.overtimeHours, 12);
});

test('payslips come back in employee order even though timesheets arrive out of order', async () => {
  const payroll = payrollWithStaff('weekly');
  for (const id of ['H9', 'H1', 'H5', 'H3', 'H7']) {
    payroll.employees.add({ id, name: id, type: 'hourly', hourlyRateCents: 1500, startDate: '2024-01-01' });
    work(payroll, id, ['2025-03-03'], 7);
  }
  // Latency differs per employee, so the fetches finish in a different order every time the ids change.
  const run = await payroll.run({ start: '2025-03-03', end: '2025-03-09' });
  assert.deepEqual(run.payslips.map((p) => p.employeeId), ['E01', 'E02', 'E03', 'H1', 'H3', 'H5', 'H7', 'H9']);
  assert.equal(run.payslips[3].gross, 10500);
});

test('employees who start after the period are not paid', async () => {
  const payroll = payrollWithStaff('monthly');
  payroll.employees.add({ id: 'E04', name: 'Dee', type: 'salaried', annualSalaryCents: 4_800_000, startDate: '2025-04-01' });
  const run = await payroll.run({ start: '2025-03-01', end: '2025-03-31' });
  assert.deepEqual(run.payslips.map((p) => p.employeeId), ['E01', 'E02', 'E03']);
});
