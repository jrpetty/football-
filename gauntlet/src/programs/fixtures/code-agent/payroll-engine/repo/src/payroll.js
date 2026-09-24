'use strict';

const { EmployeeRegistry } = require('./employees');
const { TimesheetStore } = require('./timesheets');
const { runPayroll } = require('./payrun');
const { buildPayslip } = require('./payslip');
const { periodsPerYear } = require('./config');

/** A payroll for one pay frequency, with its own staff list and timesheets. */
function createPayroll({ frequency = 'monthly' } = {}) {
  periodsPerYear(frequency);
  const employees = new EmployeeRegistry();
  const timesheets = new TimesheetStore();
  return {
    frequency,
    employees,
    timesheets,
    payslip(employeeId, period) {
      const employee = employees.get(employeeId);
      const entries = employee.type === 'hourly' ? timesheets.hoursFor(employeeId, period.start, period.end) : [];
      return buildPayslip(employee, period, frequency, entries);
    },
    run(period) {
      return runPayroll({ employees, timesheets, frequency, period });
    },
  };
}

module.exports = { createPayroll };
