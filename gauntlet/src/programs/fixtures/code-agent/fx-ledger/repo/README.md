# fx-ledger

A small multi-currency ledger: accounts in several currencies, transfers with
currency conversion, idempotent retries, monthly statements and savings
interest. Storage is asynchronous (a database in production), so most calls
are `async`.

```js
const { createLedger } = require('./src');

const ledger = createLedger();
await ledger.open({ id: 'alice-usd', currency: 'USD' });
await ledger.open({ id: 'bob-eur', currency: 'EUR' });
await ledger.deposit('alice-usd', 50_000, '2025-03-01');
await ledger.transfer({ from: 'alice-usd', to: 'bob-eur', amount: 10_000, date: '2025-03-02', idempotencyKey: 'inv-7' });
```

## Money

* Amounts are integers in the currency's **minor unit**: cents for USD, EUR and GBP (2 decimals), whole yen
  for JPY (0 decimals). See `src/currencies.js`.

## Exchange rates

* Rates live in `src/rates.js` and are quoted `BASE/QUOTE = r`, meaning **1 BASE buys r QUOTE**
  (`EUR/USD = 1.08`: one euro buys 1.08 dollars).
* To convert from A to B: if `A/B` is quoted, multiply by it; if only `B/A` is quoted, divide by it;
  otherwise convert A → USD → B using the same two rules. Work in major units and round **once**, at the end,
  half-up to B's minor unit (no rounding in the middle of a cross conversion).

## Transfers

* `transfer({ from, to, amount, date, idempotencyKey? })`: the sender is debited exactly `amount` (in the
  sender's currency). The receiver gets the converted amount minus the fee.
* **Fee:** same-currency transfers are free. Cross-currency transfers pay **0.5% of the converted amount**
  (in the receiver's currency, rounded half-up to its minor unit), deducted from what the receiver gets.
* A transfer is rejected with `InsufficientFundsError` if it would take the sender below minus its overdraft
  limit (default 0). Concurrent transfers are safe: every transfer locks both accounts.
* **Idempotency:** transfers with the same `idempotencyKey` happen at most once. A retry returns the original
  result — **also when the retry arrives while the first attempt is still being processed**. A transfer that
  failed does not use up its key: it can be retried with the same key.

## Statements and interest

* `statement(accountId, from, to)` lists journal entries dated within the range with a running balance,
  plus opening and closing balances.
* Savings accounts (`{ savingsRatePct }`) earn interest daily on the end-of-day balance at
  `rate ÷ 365` (Actual/365 Fixed, also in leap years); the month's total is rounded half-up once and credited
  on the month's last day by `accrueInterest(accountId, year, month)`.

## Development

```
node --test
```
