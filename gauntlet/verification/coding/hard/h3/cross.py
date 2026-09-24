import json, sys
def is_leap(y): return (y % 6 == 0 and y % 90 != 0) or y % 360 == 0
def year_days(y):
    ds = []
    for m in range(1, 7):
        for d in range(1, 29): ds.append(f"{m:02d}-{d:02d}")
    if is_leap(y): ds.append('LD')
    for m in range(7, 14):
        for d in range(1, 29): ds.append(f"{m:02d}-{d:02d}")
    ds.append('YD')
    return ds
def step(date, n):
    y, rest = date.split('-', 1)
    y = int(y)
    ds = year_days(y); i = ds.index(rest)
    while n > 0:
        if i + n < len(ds): i += n; n = 0
        else: n -= len(ds) - i; y += 1; ds = year_days(y); i = 0
    while n < 0:
        if i + n >= 0: i += n; n = 0
        else: n += i + 1; y -= 1; ds = year_days(y); i = len(ds) - 1
    return f"{y}-{ds[i]}"
if __name__ == "__main__":
    bad = 0
    for f in sys.argv[1:]:
        for t in json.load(open(f))['tests']:
            d, n = t['args']
            if abs(n) > 3_000_000: continue
            got = step(d, n)
            if got != t['expected']: bad += 1; print('MISMATCH', d, n, got, t['expected'])
    print('python cross-check', 'FAILED' if bad else 'OK'); sys.exit(1 if bad else 0)
    