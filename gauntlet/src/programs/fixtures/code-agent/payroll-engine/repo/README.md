# payroll-engine

Computes payslips for a small company's salaried and hourly staff, runs a
whole payroll for a pay period, and exports it as CSV.

```js
const { createPayroll } = require('./src');

const payroll = createPayroll({ frequency: 'monthly' });
payroll.employees.add({ id: 'E01', name: 'Ada', type: 'salaried', annualSalaryCents: 6_000_000, startDate: '2023-01-01', pensionPct: 5 });
const run = await payroll.run({ start: '2025-03-01', end: '2025-03-31' });
run.totals.net; // cents
```

## Pay periods

* A pay period is `{ start, end }` (dates `YYYY-MM-DD`, both inclusive). Its frequency is set when the
  payroll is created: `weekly` (52 periods a year), `biweekly` (every two weeks, **26** periods a year)
  or `monthly` (12 periods a year).
* **Working days** are Monday–Friday, excluding the company holidays listed in `src/calendar.js`.
* Weeks run **Monday to Sunday**.

## Gross pay

* **Salaried:** `annual salary ÷ periods per year × proration factor`, rounded half-up to the cent once,
  at the end.
  * The proration factor is 1 for anyone who started on or before the first day of the period.
  * For someone who starts during the period, it is
    `working days from the start date to the period end ÷ working days in the whole period`
    (both counts inclusive). Starting on a weekend or holiday simply means the first paid day is the next
    working day.
* **Hourly:** hours come from timesheets. Within each Monday–Sunday week, the first 40 hours are regular and
  the rest are overtime, paid at 1.5× the hourly rate. Only the days inside the pay period count (a week that
  straddles the period boundary is split, and each part is counted on its own). Gross =
  `regular hours × rate + overtime hours × rate × 1.5`, rounded half-up to the cent. Hourly staff are not
  prorated.

## Deductions

* **Pension:** `gross × pensionPct ÷ 100`, rounded half-up.
* **Income tax** is progressive on annualised taxable pay, where taxable = gross − pension. The annual bands
  are in `src/config.js` (0% up to 12,000.00; 20% up to 50,000.00; 40% above). Per-period tax =
  `annualTax(taxable × periods per year) ÷ periods per year`, rounded half-up.
* **Union dues:** an optional fixed amount per period (`unionDuesCents`).
* `net = gross − pension − tax − union dues`.

## Payroll runs

`payroll.run(period)` pays every employee whose start date is on or before the period end, returns payslips
sorted by employee id and totals for gross, pension, tax and net. `toCsv(run)` exports one row per payslip.

## Development

```
node --test
```
