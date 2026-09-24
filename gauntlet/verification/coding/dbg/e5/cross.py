import json, sys, re
PAT = re.compile(r'M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})')
V = {'I': 1, 'V': 5, 'X': 10, 'L': 50, 'C': 100, 'D': 500, 'M': 1000}
def conv(s):
    if s == '' or not PAT.fullmatch(s): return None
    t = 0
    for i, ch in enumerate(s):
        v = V[ch]; nx = V.get(s[i+1], 0) if i + 1 < len(s) else 0
        t += -v if v < nx else v
    return t
for t in json.load(open(sys.argv[1]))['tests']:
    assert conv(t['args'][0]) == t['expected'], (t['args'], t['expected'])
print('python cross-check OK')
