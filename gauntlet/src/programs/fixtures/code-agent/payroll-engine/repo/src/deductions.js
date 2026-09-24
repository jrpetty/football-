'use strict';

const { percentOf } = require('./money');
const { periodTax } = require('./tax');

/** Pension, tax and union dues for one period's gross pay. */
function deductions(employee, gross, frequency) {
  const pension = percentOf(gross, employee.pensionPct ?? 0);
  const taxable = gross - pension;
  const tax = periodTax(taxable, frequency);
  const unionDues = employee.unionDuesCents ?? 0;
  return { pension, taxable, tax, unionDues };
}

module.exports = { deductions };
