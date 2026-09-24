import json, sys, re
sys.setrecursionlimit(1000000)
PRI = {'#CYCLE!': 3, '#REF!': 2, '#DIV/0!': 1}
def solve(cells):
    def tokenize(s):
        return re.findall(r'SUM|[A-Z]\d+|\d+(?:\.\d+)?|[-+*/():]', s)
    parsed = {}
    for name, raw in cells.items():
        if not raw.startswith('='):
            parsed[name] = ('num', float(raw)); continue
        toks = tokenize(raw[1:]); pos = [0]
        def nxt(): t = toks[pos[0]]; pos[0] += 1; return t
        def peek(): return toks[pos[0]] if pos[0] < len(toks) else None
        def e():
            n = t_()
            while peek() in ('+', '-'): op = nxt(); n = ('bin', op, n, t_())
            return n
        def t_():
            n = u()
            while peek() in ('*', '/'): op = nxt(); n = ('bin', op, n, u())
            return n
        def u():
            if peek() == '-': nxt(); return ('neg', u())
            return p()
        def p():
            tk = nxt()
            if tk == '(':
                n = e(); nxt(); return n
            if tk == 'SUM':
                nxt(); a = nxt(); nxt(); b = nxt(); nxt(); return ('sum', a, b)
            if tk[0].isalpha(): return ('ref', tk)
            return ('num', float(tk))
        parsed[name] = e()
    def members(a, b):
        c1, c2 = sorted([a[0], b[0]]); r1, r2 = sorted([int(a[1:]), int(b[1:])])
        return [n for n in cells if c1 <= n[0] <= c2 and r1 <= int(n[1:]) <= r2]
    def deps_of(node, acc, miss):
        k = node[0]
        if k == 'bin': deps_of(node[2], acc, miss); deps_of(node[3], acc, miss)
        elif k == 'neg': deps_of(node[1], acc, miss)
        elif k == 'ref':
            if node[1] in cells: acc.add(node[1])
            else: miss[0] = True
        elif k == 'sum': acc.update(members(node[1], node[2]))
    deps = {}; missing = {}
    for n in cells:
        acc = set(); miss = [False]; deps_of(parsed[n], acc, miss); deps[n] = acc; missing[n] = miss[0]
    def reaches_self(c):
        seen = set(); st = list(deps[c])
        while st:
            x = st.pop()
            if x == c: return True
            if x in seen: continue
            seen.add(x); st.extend(deps[x])
        return False
    cyc = {n: reaches_self(n) for n in cells}
    memo = {}
    def val(c):
        if c in memo: return memo[c]
        if cyc[c]: memo[c] = '#CYCLE!'; return memo[c]
        errs = ['#REF!'] if missing[c] else []
        for d in deps[c]:
            v = val(d)
            if isinstance(v, str): errs.append(v)
        if errs:
            memo[c] = max(errs, key=lambda e: PRI[e]); return memo[c]
        def ev(node):
            k = node[0]
            if k == 'num': return node[1]
            if k == 'ref': return val(node[1])
            if k == 'sum': return sum(val(m) for m in members(node[1], node[2]))
            if k == 'neg': return -ev(node[1])
            a = ev(node[2]); b = ev(node[3])
            if node[1] == '+': return a + b
            if node[1] == '-': return a - b
            if node[1] == '*': return a * b
            if b == 0: raise ZeroDivisionError
            return a / b
        try: memo[c] = ev(parsed[c])
        except ZeroDivisionError: memo[c] = '#DIV/0!'
        return memo[c]
    return {n: val(n) for n in cells}
bad = 0
for f in sys.argv[1:]:
    for t in json.load(open(f))['tests']:
        got = solve(t['args'][0]); exp = t['expected']
        ok = set(got) == set(exp) and all((isinstance(got[k], str) and got[k] == exp[k]) or (not isinstance(got[k], str) and not isinstance(exp[k], str) and abs(got[k] - exp[k]) <= 1e-9 * max(1, abs(exp[k]))) for k in got)
        if not ok:
            bad += 1; print('MISMATCH', json.dumps(t['args'][0])[:300]); print('  py', {k: got[k] for k in list(got)[:8]}); print('  js', {k: exp[k] for k in list(exp)[:8]})
print('python cross-check', 'FAILED' if bad else 'OK'); sys.exit(1 if bad else 0)
