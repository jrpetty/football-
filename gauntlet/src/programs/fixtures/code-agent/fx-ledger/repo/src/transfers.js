'use strict';

const { convert, effectiveRate } = require('./fx');
const { transferFee } = require('./fees');
const { InsufficientFundsError } = require('./errors');

class TransferService {
  constructor({ accounts, journal, locks, idempotency, audit }) {
    this.accounts = accounts;
    this.journal = journal;
    this.locks = locks;
    this.idempotency = idempotency;
    this.audit = audit;
  }

  /** Move `amount` (sender's minor units) from one account to another. */
  transfer(request) {
    const { from, to, amount, date } = request;
    if (from === to) return Promise.reject(new Error('Cannot transfer to the same account'));
    if (!Number.isInteger(amount) || amount <= 0) return Promise.reject(new RangeError(`Invalid amount ${amount}`));
    if (!/^\d{4}-\d{2}-\d{2}$/.test(String(date))) return Promise.reject(new Error(`Invalid date ${date}`));
    const run = () => this.execute(request);
    return request.idempotencyKey ? this.idempotency.once(request.idempotencyKey, run) : run();
  }

  async execute({ from, to, amount, date, memo = '' }) {
    const src = this.accounts.get(from);
    const dst = this.accounts.get(to);
    return this.locks.withLocks([from, to], async () => {
      const balance = await this.accounts.balance(from);
      if (balance - amount < -src.overdraftLimit) {
        this.audit.record('transfer.rejected', { from, to, amount, reason: 'insufficient funds' });
        throw new InsufficientFundsError(from, balance, amount, src.overdraftLimit);
      }
      const converted = convert(amount, src.currency, dst.currency);
      const fee = transferFee({ sent: amount, converted, fromCurrency: src.currency, toCurrency: dst.currency });
      const received = converted - fee;
      await this.accounts.adjust(from, -amount);
      await this.accounts.adjust(to, received);
      const ref = this.journal.nextId('T');
      this.journal.append({ date, accountId: from, amount: -amount, kind: 'transfer-out', ref, memo });
      this.journal.append({ date, accountId: to, amount: received, kind: 'transfer-in', ref, memo });
      const result = Object.freeze({ ref, from, to, sent: amount, received, fee, rate: effectiveRate(src.currency, dst.currency), date });
      this.audit.record('transfer.completed', result);
      return result;
    });
  }
}

module.exports = { TransferService };
