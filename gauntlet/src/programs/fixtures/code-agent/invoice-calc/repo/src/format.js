'use strict';

const { formatMoney } = require('./money');

function pad(text, width) {
  return text.length >= width ? text : text + ' '.repeat(width - text.length);
}

function padLeft(text, width) {
  return text.length >= width ? text : ' '.repeat(width - text.length) + text;
}

/** Plain-text rendering of an invoice (fixed-width, 40 columns). */
function renderInvoice(invoice, currency = 'USD') {
  const rows = [];
  for (const line of invoice.lines) {
    rows.push(pad(`${line.qty} x ${line.description}`, 28) + padLeft(formatMoney(line.totalCents, currency), 12));
  }
  rows.push('-'.repeat(40));
  rows.push(pad('Subtotal', 28) + padLeft(formatMoney(invoice.subtotal, currency), 12));
  if (invoice.discount) rows.push(pad('Discount', 28) + padLeft(formatMoney(-invoice.discount, currency), 12));
  rows.push(pad(`Tax (${invoice.region})`, 28) + padLeft(formatMoney(invoice.tax, currency), 12));
  if (invoice.shipping) rows.push(pad('Shipping', 28) + padLeft(formatMoney(invoice.shipping, currency), 12));
  rows.push(pad('TOTAL', 28) + padLeft(formatMoney(invoice.total, currency), 12));
  return rows.join('\n');
}

module.exports = { renderInvoice };
