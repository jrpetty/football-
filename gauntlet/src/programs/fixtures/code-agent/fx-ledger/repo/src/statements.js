'use strict';

function compareDates(a, b) {
  return a < b ? -1 : a > b ? 1 : 0;
}

/**
 * Statement for `accountId` between two dates (inclusive): opening balance,
 * every entry in the range with a running balance, and the closing balance.
 * Entries are ordered by date, then by the order they were written.
 */
function buildStatement(journal, account, from, to) {
  const entries = journal
    .forAccount(account.id)
    .slice()
    .sort((a, b) => compareDates(a.date, b.date) || a.seq - b.seq);
  let opening = 0;
  for (const e of entries) if (compareDates(e.date, from) < 0) opening += e.amount;
  let running = opening;
  const lines = [];
  for (const e of entries) {
    if (compareDates(e.date, from) < 0 || compareDates(e.date, to) > 0) continue;
    running += e.amount;
    lines.push({ date: e.date, kind: e.kind, ref: e.ref, amount: e.amount, balance: running, memo: e.memo });
  }
  return { accountId: account.id, currency: account.currency, from, to, opening, lines, closing: running };
}

module.exports = { buildStatement };
