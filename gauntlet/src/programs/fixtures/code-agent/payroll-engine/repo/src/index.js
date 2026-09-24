'use strict';

module.exports = {
  ...require('./config'),
  ...require('./money'),
  ...require('./dates'),
  ...require('./calendar'),
  ...require('./employees'),
  ...require('./timesheets'),
  ...require('./overtime'),
  ...require('./proration'),
  ...require('./gross'),
  ...require('./tax'),
  ...require('./deductions'),
  ...require('./payslip'),
  ...require('./payrun'),
  ...require('./export-csv'),
  ...require('./format'),
  ...require('./payroll'),
};
