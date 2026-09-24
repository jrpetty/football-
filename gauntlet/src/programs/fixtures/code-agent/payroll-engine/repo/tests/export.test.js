'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { toCsv, createPayroll } = require('../src');

test('CSV export quotes names with commas and quotes', async () => {
  const payroll = createPayroll({ frequency: 'monthly' });
  payroll.employees.add({ id: 'E07', name: 'Smith, "Jo"', type: 'salaried', annualSalaryCents: 1_200_000, startDate: '2020-01-01' });
  const csv = toCsv(await payroll.run({ start: '2025-03-01', end: '2025-03-31' }));
  const [header, row] = csv.trim().split('\n');
  assert.equal(header, 'employee,name,period_start,period_end,regular_hours,overtime_hours,gross,pension,tax,union_dues,net');
  assert.equal(row, 'E07,"Smith, ""Jo""",2025-03-01,2025-03-31,0,0,1000.00,0.00,0.00,0.00,1000.00');
});
