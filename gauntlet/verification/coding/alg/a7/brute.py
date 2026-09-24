import json, sys, re
def top(text, k):
    words = []
    cur = ''
    for ch in text:
        if ('a' <= ch <= 'z') or ('A' <= ch <= 'Z'):
            cur += ch
        else:
            if cur: words.append(cur.lower()); cur = ''
    if cur: words.append(cur.lower())
    c = {}
    for w in words: c[w] = c.get(w, 0) + 1
    items = sorted(c.items(), key=lambda t: (-t[1], t[0]))
    return [list(x) for x in items[:k]]
tests = json.load(open(sys.argv[1]))['tests']
for t in tests:
    got = top(*t['args'])
    assert got == t['expected'], (t['args'][0][:60], got[:5], t['expected'][:5])
print('python cross-check OK', len(tests))
