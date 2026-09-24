'use strict';

const { buildPayslip } = require('./payslip');
const { sum } = require('./money');
const { compareDates } = require('./dates');

/**
 * Pay everyone who has started by the end of the period. Timesheets are
 * fetched concurrently; the payslips come back sorted by employee id no
 * matter which fetch finishes first.
 */
async function runPayroll({ employees, timesheets, frequency, period }) {
  if (compareDates(period.start, period.end) > 0) throw new Error('Period ends before it starts');
  const staff = employees.activeIn(period);
  const payslips = await Promise.all(
    staff.map(async (employee) => {
      const entries = employee.type === 'hourly' ? await timesheets.fetch(employee.id, period.start, period.end) : [];
      return buildPayslip(employee, period, frequency, entries);
    }),
  );
  payslips.sort((a, b) => (a.employeeId < b.employeeId ? -1 : a.employeeId > b.employeeId ? 1 : 0));
  const totals = {
    gross: sum(payslips.map((p) => p.gross)),
    pension: sum(payslips.map((p) => p.pension)),
    tax: sum(payslips.map((p) => p.tax)),
    net: sum(payslips.map((p) => p.net)),
  };
  return { period, frequency, payslips, totals };
}

module.exports = { runPayroll };
