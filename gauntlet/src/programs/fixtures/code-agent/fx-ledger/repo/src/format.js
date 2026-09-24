'use strict';

const { formatAmount } = require('./money');

/** Plain-text statement, one line per entry. */
function renderStatement(s) {
  const rows = [`Statement ${s.accountId} (${s.currency}) ${s.from} to ${s.to}`, `Opening balance ${formatAmount(s.opening, s.currency)}`];
  for (const l of s.lines) {
    rows.push(`${l.date}  ${l.kind.padEnd(13)} ${(l.ref ?? '').padEnd(7)} ${formatAmount(l.amount, s.currency).padStart(18)} ${formatAmount(l.balance, s.currency).padStart(18)}`);
  }
  rows.push(`Closing balance ${formatAmount(s.closing, s.currency)}`);
  return rows.join('\n');
}

/** One-line receipt for a completed transfer. */
function renderReceipt(t, fromCurrency, toCurrency) {
  const fee = t.fee ? ` (fee ${formatAmount(t.fee, toCurrency)})` : '';
  return `${t.ref}: sent ${formatAmount(t.sent, fromCurrency)}, received ${formatAmount(t.received, toCurrency)}${fee}`;
}

/** Statement as CSV for spreadsheet users (amounts in major units, no thousands separators). */
function statementCsv(s, toMajorUnits) {
  const rows = ['date,kind,ref,amount,balance,memo'];
  for (const l of s.lines) {
    const memo = /[",\n]/.test(l.memo ?? '') ? `"${String(l.memo).replace(/"/g, '""')}"` : l.memo ?? '';
    rows.push([l.date, l.kind, l.ref ?? '', toMajorUnits(l.amount), toMajorUnits(l.balance), memo].join(','));
  }
  return rows.join('\n') + '\n';
}

module.exports = { renderStatement, renderReceipt, statementCsv };
