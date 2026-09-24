'use strict';

const { grossPay } = require('./gross');
const { deductions } = require('./deductions');

/**
 * One employee's payslip for one period.
 * `entries` are that employee's timesheet rows inside the period (hourly staff only).
 */
function buildPayslip(employee, period, frequency, entries = []) {
  const { gross, regularHours, overtimeHours } = grossPay(employee, period, frequency, entries);
  const { pension, taxable, tax, unionDues } = deductions(employee, gross, frequency);
  return {
    employeeId: employee.id,
    name: employee.name ?? employee.id,
    period: { start: period.start, end: period.end },
    regularHours,
    overtimeHours,
    gross,
    pension,
    taxable,
    tax,
    unionDues,
    net: gross - pension - tax - unionDues,
  };
}

module.exports = { buildPayslip };
