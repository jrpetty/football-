"""Oracle 2 for Engineering: Ladder: the most literal model of the specification, in Python.

    python3 brute.py < inputs.json      # stdin: a JSON list of runEngine inputs; stdout: a JSON list of [count, checksum]

A plain list of values plus two plain lists of historic maximum and minimum; every operation loops over its range.
Python integers, so nothing can overflow. Far too slow for the large hidden tests (brute.c checks those).
"""
import json
import sys

MOD = 1000000007


def expand(inp):
    """The initial values and the list of operations, expanding a generator description."""
    if 'generate' not in inp:
        return list(inp['values']), [list(o) for o in inp['ops']]
    g = inp['generate']
    state = g['seed']

    def rand(k):
        nonlocal state
        state = state * 48271 % 2147483647
        return state % k

    n = g['n']
    values = [g['lo'] + rand(g['hi'] - g['lo'] + 1) for _ in range(n)]
    total = sum(e[1] for e in g['mix'])
    ops = []
    for _ in range(g['q']):
        w = rand(total)
        for e in g['mix']:
            if w < e[1]:
                break
            w -= e[1]
        if e[0] == 'set':
            i = rand(n)
            ops.append(['set', i, e[2] + rand(e[3] - e[2] + 1)])
            continue
        u, t = rand(n), rand(n)
        l, r = min(u, t), max(u, t)
        ops.append([e[0], l, r] + ([e[2] + rand(e[3] - e[2] + 1)] if len(e) > 2 else []))
    return values, ops


def run(inp):
    a, ops = expand(inp)
    hi = list(a)
    lo = list(a)
    count = 0
    h = 0
    for o in ops:
        op = o[0]
        if op in ('sum', 'min', 'max', 'hmax', 'hmin'):
            l, r = o[1], o[2]
            seg = {'sum': a, 'min': a, 'max': a, 'hmax': hi, 'hmin': lo}[op][l:r + 1]
            v = sum(seg) if op == 'sum' else min(seg) if op in ('min', 'hmin') else max(seg)
            count += 1
            h = (h * 1000003 + v % MOD) % MOD
            continue
        if op == 'set':
            idx = [o[1]]
            x = o[2]
        else:
            idx = range(o[1], o[2] + 1)
            x = o[3] if len(o) > 3 else None
        for i in idx:
            if op in ('set', 'assign'):
                a[i] = x
            elif op == 'add':
                a[i] += x
            elif op == 'chmin':
                a[i] = min(a[i], x)
            elif op == 'chmax':
                a[i] = max(a[i], x)
            elif op == 'div':
                a[i] = a[i] // x  # Python // rounds toward negative infinity
            elif op == 'hreset':
                hi[i] = lo[i] = a[i]
                continue
            else:
                raise ValueError(op)
            hi[i] = max(hi[i], a[i])
            lo[i] = min(lo[i], a[i])
    return [count, h]


if __name__ == '__main__':
    json.dump([run(x) for x in json.load(sys.stdin)], sys.stdout)
