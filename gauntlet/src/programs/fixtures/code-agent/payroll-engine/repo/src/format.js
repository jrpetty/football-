'use strict';

const { formatCents } = require('./money');

function line(label, cents) {
  const amount = formatCents(cents);
  return `${label.padEnd(20)}${amount.padStart(14)}`;
}

/** Human-readable payslip (fixed width, 34 columns). */
function renderPayslip(p) {
  const rows = [`${p.name} (${p.employeeId})`, `Period ${p.period.start} to ${p.period.end}`, '-'.repeat(34)];
  if (p.regularHours || p.overtimeHours) rows.push(`Hours ${p.regularHours} + ${p.overtimeHours} overtime`);
  rows.push(line('Gross', p.gross));
  rows.push(line('Pension', -p.pension));
  rows.push(line('Income tax', -p.tax));
  if (p.unionDues) rows.push(line('Union dues', -p.unionDues));
  rows.push('-'.repeat(34));
  rows.push(line('NET PAY', p.net));
  return rows.join('\n');
}

/** One-screen summary of a payroll run for the finance team. */
function renderRunSummary(run) {
  const rows = [`Payroll ${run.period.start} to ${run.period.end} (${run.frequency})`, ''];
  for (const p of run.payslips) {
    rows.push(`${p.employeeId.padEnd(6)}${p.name.slice(0, 18).padEnd(20)}${formatCents(p.net).padStart(14)}`);
  }
  rows.push('');
  rows.push(line('Total gross', run.totals.gross));
  rows.push(line('Total pension', run.totals.pension));
  rows.push(line('Total tax', run.totals.tax));
  rows.push(line('Total net', run.totals.net));
  return rows.join('\n');
}

module.exports = { renderPayslip, renderRunSummary };
