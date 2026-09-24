# invoice-calc

A tiny library that turns a shopping basket into an invoice. All money is
handled internally as **integer cents** so that totals never drift.

## Usage

```js
const { buildInvoice, renderInvoice } = require('./src');

const invoice = buildInvoice(
  [
    { sku: 'MUG', description: 'Coffee mug', price: 12.5, qty: 2 },
    { sku: 'TEA', description: 'Green tea', price: 4.99, qty: 1 },
  ],
  { region: 'UK', discount: { type: 'percent', value: 10 }, shipping: 3.95 },
);
console.log(renderInvoice(invoice));
```

## Rules

* Prices are decimal amounts in the invoice currency (e.g. `19.99`). They are
  converted to cents by rounding to the **nearest** cent.
* A line total is `unit price in cents × quantity`. Quantities must be positive
  integers.
* The **subtotal** is the sum of the line totals.
* A discount is optional and is either `{ type: 'percent', value: 0..100 }` or
  `{ type: 'fixed', value: <amount> }`. A fixed discount can never exceed the
  subtotal.
* **Tax is charged on the discounted subtotal** (subtotal minus discount), using
  the rate of the invoice's region (see `src/tax.js`). Percentages of an amount
  are rounded half away from zero to whole cents.
* Shipping is added last and is **not** taxed.
* `total = subtotal - discount + tax + shipping`.

## Development

```
node --test
```

Tests live in `tests/` and use `node:test` with `node:assert/strict`.
