# date-utils

Calendar arithmetic on plain `YYYY-MM-DD` strings (proleptic Gregorian
calendar, no time zones, no times of day).

```js
const d = require('./src');

d.addMonths('2024-01-31', 1);                    // '2024-02-29'
d.weekday('2024-09-24');                         // 'Tuesday'
d.businessDaysBetween('2024-09-23', '2024-09-30'); // 5
```

## API

| function | behaviour |
|---|---|
| `isLeapYear(year)` | Gregorian rule: divisible by 4, except centuries, except every 400 years (2000 is a leap year, 1900 is not). |
| `daysInMonth(year, month)` | `month` is 1–12. |
| `dayOfYear(date)` | 1 for January 1st. |
| `parseDate(str)` | `{ year, month, day }`; throws `RangeError` for impossible dates such as `2023-02-29`. |
| `formatDate({ year, month, day })` | zero-padded `YYYY-MM-DD`. |
| `addDays(date, n)` | `n` may be negative. |
| `addMonths(date, n)` | `n` may be negative. When the day does not exist in the target month, the result is **clamped to the last day of that month** (`2024-03-31` + 1 month = `2024-04-30`). |
| `daysBetween(a, b)` | whole days from `a` to `b` (negative if `b` is earlier). |
| `weekday(date)` | English day name, `'Monday'` … `'Sunday'`. |
| `isWeekend(date)` | Saturday or Sunday. |
| `businessDaysBetween(a, b)` | number of Monday–Friday dates `d` with `a <= d < b`. |

## Development

```
node --test
```
