'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createPayroll, periodTax, annualTax, splitOvertime, prorationFactor, toCsv } = require('../src');
const { estimateTax2019 } = require('../src/legacy-tax');

function staff(frequency) {
  const payroll = createPayroll({ frequency });
  payroll.employees.add({ id: 'E01', name: 'Ada Lovelace', type: 'salaried', annualSalaryCents: 6_000_000, startDate: '2023-01-01', pensionPct: 5 });
  payroll.employees.add({ id: 'E02', name: 'Bo Diaz', type: 'salaried', annualSalaryCents: 3_120_000, startDate: '2024-06-01', unionDuesCents: 1500 });
  payroll.employees.add({ id: 'E03', name: 'Cy Park', type: 'hourly', hourlyRateCents: 2400, startDate: '2024-01-01', pensionPct: 3 });
  return payroll;
}
const days = (dates, hours) => dates.map((date) => ({ date, hours }));

test('biweekly salary is a 26th of the annual salary', () => {
  const p = staff('biweekly').payslip('E02', { start: '2025-03-17', end: '2025-03-30' });
  assert.equal(p.gross, 120000);
  assert.equal(p.tax, 14769);
  assert.equal(p.net, 103731);
});

test('period tax for every frequency', () => {
  assert.equal(periodTax(100000, 'weekly'), 16154);
  assert.equal(periodTax(100000, 'biweekly'), 10769);
  assert.equal(periodTax(100000, 'monthly'), 0);
  assert.equal(periodTax(0, 'biweekly'), 0);
});

test('annual tax bands are progressive', () => {
  assert.equal(annualTax(1_200_000), 0);
  assert.equal(annualTax(5_000_000), 760_000);
  assert.equal(annualTax(6_000_000), 1_160_000);
});

test('Sunday belongs to the week that started on the Monday before', () => {
  const r = splitOvertime([...days(['2025-03-03', '2025-03-04', '2025-03-05', '2025-03-06', '2025-03-07'], 8), ...days(['2025-03-09'], 6)]);
  assert.equal(r.regularHours, 40);
  assert.equal(r.overtimeHours, 6);
});

test('a weekend before a full week does not create overtime', () => {
  const r = splitOvertime([...days(['2025-03-01', '2025-03-02'], 12), ...days(['2025-03-03', '2025-03-04', '2025-03-05', '2025-03-06', '2025-03-07'], 8)]);
  assert.equal(r.overtimeHours, 0);
  assert.equal(r.regularHours, 64);
});

test('a week that straddles the period end is split', () => {
  const payroll = staff('monthly');
  for (const d of ['2025-03-24', '2025-03-25', '2025-03-26', '2025-03-27', '2025-03-28']) payroll.timesheets.record('E03', d, 9);
  payroll.timesheets.record('E03', '2025-03-31', 10);
  payroll.timesheets.record('E03', '2025-04-01', 10);
  const p = payroll.payslip('E03', { start: '2025-03-01', end: '2025-03-31' });
  assert.equal(p.regularHours, 50);
  assert.equal(p.overtimeHours, 5);
  assert.equal(p.gross, 138000);
});

test('a mid-month starter is paid for working days', () => {
  const payroll = staff('monthly');
  payroll.employees.add({ id: 'E05', name: 'Eve', type: 'salaried', annualSalaryCents: 4_800_000, startDate: '2025-03-17' });
  assert.equal(prorationFactor('2025-03-17', { start: '2025-03-01', end: '2025-03-31' }), 11 / 21);
  assert.equal(payroll.payslip('E05', { start: '2025-03-01', end: '2025-03-31' }).gross, 209524);
});

test('company holidays are not working days when prorating', () => {
  const payroll = staff('monthly');
  payroll.employees.add({ id: 'E06', name: 'Fay', type: 'salaried', annualSalaryCents: 4_800_000, startDate: '2025-04-14' });
  assert.equal(payroll.payslip('E06', { start: '2025-04-01', end: '2025-04-30' }).gross, 220000);
});

test('starting on a weekend pays from the next working day', () => {
  const payroll = staff('monthly');
  payroll.employees.add({ id: 'E07', name: 'Gus', type: 'salaried', annualSalaryCents: 4_800_000, startDate: '2025-03-15' });
  assert.equal(payroll.payslip('E07', { start: '2025-03-01', end: '2025-03-31' }).gross, 209524);
});

test('starting on or before the first day means a full period', () => {
  const period = { start: '2025-03-01', end: '2025-03-31' };
  assert.equal(prorationFactor('2025-03-01', period), 1);
  assert.equal(prorationFactor('2020-01-01', period), 1);
  assert.equal(prorationFactor('2025-04-01', period), 0);
});

test('a biweekly starter around Easter', () => {
  const payroll = createPayroll({ frequency: 'biweekly' });
  payroll.employees.add({ id: 'E08', name: 'Hal', type: 'salaried', annualSalaryCents: 5_200_000, startDate: '2025-04-22' });
  const p = payroll.payslip('E08', { start: '2025-04-14', end: '2025-04-27' });
  assert.equal(p.gross, 100000);
  assert.equal(p.tax, 10769);
});

test('an hourly starter is paid for hours worked, not prorated', () => {
  const payroll = staff('monthly');
  payroll.employees.add({ id: 'E09', name: 'Ivy', type: 'hourly', hourlyRateCents: 2000, startDate: '2025-03-20' });
  payroll.timesheets.record('E09', '2025-03-20', 8);
  payroll.timesheets.record('E09', '2025-03-21', 8);
  assert.equal(payroll.payslip('E09', { start: '2025-03-01', end: '2025-03-31' }).gross, 32000);
});

test('a monthly payroll run with a new starter', async () => {
  const payroll = staff('monthly');
  payroll.employees.add({ id: 'E04', name: 'Dee', type: 'salaried', annualSalaryCents: 4_800_000, startDate: '2025-03-17' });
  const run = await payroll.run({ start: '2025-03-01', end: '2025-03-31' });
  assert.deepEqual(run.payslips.map((p) => [p.employeeId, p.gross, p.net]), [
    ['E01', 500000, 388333],
    ['E02', 260000, 226500],
    ['E03', 0, 0],
    ['E04', 209524, 187619],
  ]);
  assert.equal(run.totals.net, 802452);
  const csv = toCsv(run).trim().split('\n');
  assert.equal(csv[4], 'E04,Dee,2025-03-01,2025-03-31,0,0,2095.24,0.00,219.05,0.00,1876.19');
});

test('biweekly run with overtime around weekends', async () => {
  const payroll = staff('biweekly');
  for (const d of ['2025-03-03', '2025-03-04', '2025-03-05', '2025-03-06', '2025-03-07']) payroll.timesheets.record('E03', d, 8);
  payroll.timesheets.record('E03', '2025-03-09', 4);
  payroll.timesheets.record('E03', '2025-03-15', 3);
  payroll.timesheets.record('E03', '2025-03-16', 3);
  const run = await payroll.run({ start: '2025-03-03', end: '2025-03-16' });
  const cy = run.payslips.find((p) => p.employeeId === 'E03');
  assert.equal(cy.regularHours, 46);
  assert.equal(cy.overtimeHours, 4);
  assert.equal(cy.gross, 124800);
});

test('the 2019 audit estimate is left exactly as filed', () => {
  assert.equal(estimateTax2019(100000), 22000);
});
