import json, sys
def sim(capacity, ttl, events):
    entries = []  # list of [key, value, exp], index 0 = least recently used
    out = []
    for ev in events:
        op, t, key = ev[0], ev[1], ev[2]
        entries = [e for e in entries if e[2] > t]          # expired entries behave as absent
        idx = next((i for i, e in enumerate(entries) if e[0] == key), None)
        if op == 'get':
            if idx is None: out.append(None)
            else:
                e = entries.pop(idx); entries.append(e); out.append(e[1])
        else:
            if idx is not None: entries.pop(idx)
            elif len(entries) >= capacity: entries.pop(0)
            entries.append([key, ev[3], t + ttl])
    return out
bad = 0
for f in sys.argv[1:]:
    for t in json.load(open(f))['tests']:
        if len(t['args'][2]) > 20000: continue
        got = sim(*t['args'])
        if got != t['expected']: bad += 1; print('MISMATCH', t['args'][:2], got[:10], t['expected'][:10])
print('python cross-check', 'FAILED' if bad else 'OK'); sys.exit(1 if bad else 0)
