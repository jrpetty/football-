'use strict';

const { createPayroll } = require('../src');

/** A payroll with the three standard test employees. */
function payrollWithStaff(frequency) {
  const payroll = createPayroll({ frequency });
  payroll.employees.add({ id: 'E01', name: 'Ada Lovelace', type: 'salaried', annualSalaryCents: 6_000_000, startDate: '2023-01-01', pensionPct: 5 });
  payroll.employees.add({ id: 'E02', name: 'Bo Diaz', type: 'salaried', annualSalaryCents: 3_120_000, startDate: '2024-06-01', unionDuesCents: 1500 });
  payroll.employees.add({ id: 'E03', name: 'Cy Park', type: 'hourly', hourlyRateCents: 2400, startDate: '2024-01-01', pensionPct: 3 });
  return payroll;
}

/** Record the same number of hours on each date. */
function work(payroll, id, dates, hours) {
  for (const d of dates) payroll.timesheets.record(id, d, hours);
}

module.exports = { payrollWithStaff, work };
