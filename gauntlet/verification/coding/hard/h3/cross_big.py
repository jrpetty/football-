import json, sys
from cross import is_leap, year_days  # reuse only the calendar definition helpers
CYC_LEN = sum(365 + (1 if is_leap(y) else 0) for y in range(1, 361))
assert CYC_LEN == 131457
PREFIX = [0]
for y in range(1, 361): PREFIX.append(PREFIX[-1] + 365 + (1 if is_leap(y) else 0))
def days_before(y):  # days in years 1..y-1
    full, rem = divmod(y - 1, 360)
    return full * CYC_LEN + PREFIX[rem]
def to_abs(date):
    y, rest = date.split('-', 1); y = int(y)
    return days_before(y) + year_days(y).index(rest)
def from_abs(a):
    full, rem = divmod(a, CYC_LEN)
    y = 1 + 360 * full
    k = 0
    while PREFIX[k + 1] <= rem: k += 1
    y += k; rem -= PREFIX[k]
    return f"{y}-{year_days(y)[rem]}"
bad = 0
for t in json.load(open(sys.argv[1]))['tests']:
    d, n = t['args']
    got = from_abs(to_abs(d) + n)
    if got != t['expected']: bad += 1; print('MISMATCH', d, n, got, t['expected'])
print('python big-offset cross-check', 'FAILED' if bad else 'OK'); sys.exit(1 if bad else 0)
