import json, sys, re
def translate(p):
    out = []; i = 0
    while i < len(p):
        c = p[i]
        if c == '\\':
            out.append(re.escape(p[i+1])); i += 2; continue
        if c == '[':
            j = i + 1; neg = False
            if p[j] == '^': neg = True; j += 1
            k = p.index(']', j + 1 if p[j] == ']' else j)
            body = p[j:k]; items = []; m = 0
            while m < len(body):
                if m + 2 < len(body) and body[m+1] == '-':
                    items.append(re.escape(body[m]) + '-' + re.escape(body[m+2])); m += 3
                else:
                    items.append(re.escape(body[m])); m += 1
            out.append('[' + ('^' if neg else '') + ''.join(items) + ']'); i = k + 1; continue
        if c in '()|*+?.':
            out.append(c); i += 1; continue
        out.append(re.escape(c)); i += 1
    return ''.join(out)
bad = 0
# Pathological patterns that make Python's backtracking engine exponential; expected values follow directly from the pattern.
PATHO = {
    '((a*)*)*b': lambda s: set(s[:-1]) <= {'a'} and s.endswith('b'),
    '(a|aa)*c': lambda s: set(s[:-1]) <= {'a'} and s.endswith('c'),
    '(a?)' * 25 + 'a' * 25: lambda s: set(s) <= {'a'} and 25 <= len(s) <= 50,
    '(a|b|ab|ba)*(c|d)*': lambda s: re.fullmatch(r'[ab]*[cd]*', s) is not None,
    '.*.*.*.*.*.*.*.*.*.*x': lambda s: s.endswith('x'),
}
for f in sys.argv[1:]:
    for t in json.load(open(f))['tests']:
        pat, text = t['args']
        if len(text) > 20 and pat in PATHO:
            if PATHO[pat](text) != t['expected']:
                bad += 1; print('MISMATCH (manual)', pat, t['expected'])
            continue
        got = re.fullmatch(translate(pat), text, re.S) is not None
        if got != t['expected']:
            bad += 1; print('MISMATCH', repr(pat), repr(text[:40]), got, t['expected'])
print('python cross-check', 'FAILED' if bad else 'OK'); sys.exit(1 if bad else 0)
