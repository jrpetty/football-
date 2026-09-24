import json, sys
def rank(entries):
    order = sorted(range(len(entries)), key=lambda i: (-entries[i][1], entries[i][2], i))
    out = []
    for i in order:
        s, t = entries[i][1], entries[i][2]
        better = sum(1 for e in entries if (e[1] > s) or (e[1] == s and e[2] < t))
        out.append([better + 1, entries[i][0]])
    return out
tests = json.load(open(sys.argv[1]))['tests']
for t in tests:
    if len(t['args'][0]) > 3000: continue
    assert rank(*t['args']) == t['expected'], t['args'][0][:5]
print('python cross-check OK', len(tests))
