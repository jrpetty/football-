'use strict';

const { roundHalfUp } = require('./money');
const { periodsPerYear, OVERTIME_MULTIPLIER } = require('./config');
const { prorationFactor } = require('./proration');
const { splitOvertime } = require('./overtime');

function salariedGross(employee, period, frequency) {
  const factor = prorationFactor(employee.startDate, period);
  return roundHalfUp((employee.annualSalaryCents / periodsPerYear(frequency)) * factor);
}

function hourlyGross(employee, entries) {
  const { regularHours, overtimeHours } = splitOvertime(entries);
  const rate = employee.hourlyRateCents;
  const gross = roundHalfUp(regularHours * rate + overtimeHours * rate * OVERTIME_MULTIPLIER);
  return { gross, regularHours, overtimeHours };
}

/** Gross pay for one employee and period. `entries` are the timesheet rows inside the period. */
function grossPay(employee, period, frequency, entries = []) {
  if (employee.type === 'salaried') return { gross: salariedGross(employee, period, frequency), regularHours: 0, overtimeHours: 0 };
  return hourlyGross(employee, entries);
}

module.exports = { grossPay, salariedGross, hourlyGross };
