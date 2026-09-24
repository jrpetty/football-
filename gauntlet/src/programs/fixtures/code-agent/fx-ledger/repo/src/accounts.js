'use strict';

const { assertCurrency } = require('./currencies');
const { UnknownAccountError } = require('./errors');

/**
 * Account metadata lives in memory; balances live in the async store so they
 * survive restarts. TODO: negative balances should be impossible?
 * (No: overdrafts are allowed down to -overdraftLimit; transfers check that.)
 */
class AccountBook {
  constructor(store) {
    this.store = store;
    this.meta = new Map();
  }

  async open({ id, currency, overdraftLimit = 0, savingsRatePct = 0, owner = null }) {
    if (!id || typeof id !== 'string') throw new Error('Account needs an id');
    if (this.meta.has(id)) throw new Error(`Account ${id} already exists`);
    assertCurrency(currency);
    if (!(Number.isInteger(overdraftLimit) && overdraftLimit >= 0)) throw new Error('overdraftLimit must be a non-negative integer');
    const account = Object.freeze({ id, currency, overdraftLimit, savingsRatePct, owner });
    this.meta.set(id, account);
    await this.store.set(`balance:${id}`, 0);
    return account;
  }

  get(id) {
    const account = this.meta.get(id);
    if (!account) throw new UnknownAccountError(id);
    return account;
  }

  list() {
    return [...this.meta.values()];
  }

  async balance(id) {
    this.get(id);
    return (await this.store.get(`balance:${id}`)) ?? 0;
  }

  /** Add `delta` minor units. Callers must hold the account's lock. */
  async adjust(id, delta) {
    const current = await this.balance(id);
    await this.store.set(`balance:${id}`, current + delta);
    return current + delta;
  }
}

module.exports = { AccountBook };
