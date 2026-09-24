'use strict';

module.exports = {
  ...require('./money'),
  ...require('./storage'),
  ...require('./catalog'),
  ...require('./stock'),
  ...require('./reservations'),
  ...require('./pricing'),
  ...require('./orders'),
  ...require('./reports'),
  ...require('./service'),
};
