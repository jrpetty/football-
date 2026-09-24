import json, sys
from decimal import Decimal, getcontext
getcontext().prec = 5000
def canon(d):
    if d == 0: return '0'
    s = format(d, 'f')
    if '.' in s: s = s.rstrip('0').rstrip('.')
    return s
bad = 0
for f in sys.argv[1:]:
    data = json.load(open(f))
    tests = data['tests'] if isinstance(data, dict) else data
    for t in tests:
        a, b = t['args']
        exp = canon(Decimal(a) + Decimal(b))
        if exp != t['expected']:
            bad += 1; print('MISMATCH', a, b, exp, t['expected'])
    print(f, 'checked', len(tests))
sys.exit(1 if bad else 0)
