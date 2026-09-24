'use strict';

class InsufficientFundsError extends Error {
  constructor(accountId, balance, amount, limit) {
    super(`Insufficient funds in ${accountId}: balance ${balance}, transfer ${amount}, overdraft limit ${limit}`);
    this.name = 'InsufficientFundsError';
    this.accountId = accountId;
  }
}

class UnknownAccountError extends Error {
  constructor(accountId) {
    super(`Unknown account ${accountId}`);
    this.name = 'UnknownAccountError';
    this.accountId = accountId;
  }
}

module.exports = { InsufficientFundsError, UnknownAccountError };
