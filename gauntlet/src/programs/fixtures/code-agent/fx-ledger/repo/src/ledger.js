'use strict';

const { createStore } = require('./store');
const { LockManager } = require('./locks');
const { AccountBook } = require('./accounts');
const { Journal } = require('./journal');
const { IdempotencyRegistry } = require('./idempotency');
const { TransferService } = require('./transfers');
const { AuditLog } = require('./audit');
const { buildStatement } = require('./statements');
const { monthlyInterest, daysInMonth, iso } = require('./interest');

function createLedger({ store = createStore() } = {}) {
  const accounts = new AccountBook(store);
  const journal = new Journal();
  const locks = new LockManager();
  const idempotency = new IdempotencyRegistry();
  const audit = new AuditLog();
  const transfers = new TransferService({ accounts, journal, locks, idempotency, audit });

  async function credit(accountId, amount, date, kind, memo = '') {
    return locks.withLocks([accountId], async () => {
      await accounts.adjust(accountId, amount);
      return journal.append({ date, accountId, amount, kind, ref: journal.nextId(kind === 'interest' ? 'I' : 'D'), memo });
    });
  }

  return {
    accounts,
    journal,
    audit,
    open: (spec) => accounts.open(spec),
    balance: (id) => accounts.balance(id),
    deposit(accountId, amount, date, memo) {
      accounts.get(accountId);
      if (!Number.isInteger(amount) || amount <= 0) return Promise.reject(new RangeError(`Invalid amount ${amount}`));
      return credit(accountId, amount, date, 'deposit', memo);
    },
    transfer: (request) => transfers.transfer(request),
    statement(accountId, from, to) {
      return buildStatement(journal, accounts.get(accountId), from, to);
    },
    /** Credit a savings account's interest for a month (on the month's last day). */
    async accrueInterest(accountId, year, month) {
      const account = accounts.get(accountId);
      if (!account.savingsRatePct) return null;
      const raw = monthlyInterest(journal.forAccount(accountId), account.savingsRatePct, year, month);
      const amount = Math.round(raw);
      if (amount <= 0) return null;
      return credit(accountId, amount, iso(year, month, daysInMonth(year, month)), 'interest');
    },
  };
}

module.exports = { createLedger };
