'use strict';

const { createLedger } = require('../src');

/** A ledger with the standard test accounts. */
async function setup() {
  const ledger = createLedger();
  await ledger.open({ id: 'alice-usd', currency: 'USD' });
  await ledger.open({ id: 'bob-usd', currency: 'USD', overdraftLimit: 1000 });
  await ledger.open({ id: 'carol-eur', currency: 'EUR' });
  await ledger.open({ id: 'dan-jpy', currency: 'JPY' });
  await ledger.open({ id: 'erin-gbp', currency: 'GBP' });
  return ledger;
}

module.exports = { setup };
