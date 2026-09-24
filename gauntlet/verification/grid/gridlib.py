"""Logic-grid puzzle generator + exhaustive uniqueness solver for Gauntlet.

A puzzle has N positions (0..N-1) and K categories, each a permutation of N values.
A clue is a predicate over `inv` where inv[cat][val] = position of that value.
The solver enumerates permutations category by category, checking every clue as
soon as all categories it mentions are assigned, and counts solutions (capped).
"""
import itertools, random

class Clue:
    def __init__(self, kind, cats, fn, data):
        self.kind = kind
        self.cats = frozenset(cats)
        self.fn = fn
        self.data = data  # tuple used for rendering
    def __repr__(self):
        return f"Clue({self.kind},{self.data})"


def count_solutions(n, cats, clues, cap=2, collect=False):
    """cats: list of category names. Returns (count, solutions)."""
    perms = list(itertools.permutations(range(n)))  # perm[val] = position
    # unary filtering
    order = list(cats)
    # order categories: most-constrained first (by number of clues touching it)
    touch = {c: sum(1 for cl in clues if c in cl.cats) for c in cats}
    order.sort(key=lambda c: -touch[c])
    by_level = []
    assigned = set()
    for c in order:
        assigned.add(c)
        by_level.append([cl for cl in clues if cl.cats <= assigned and c in cl.cats])
    cand = {}
    for c in order:
        unary = [cl for cl in clues if cl.cats == frozenset([c])]
        cand[c] = [p for p in perms if all(cl.fn({c: p}) for cl in unary)]
    sols = []
    inv = {}
    count = 0
    def rec(i):
        nonlocal count
        if count >= cap:
            return
        if i == len(order):
            count += 1
            if collect:
                sols.append(dict(inv))
            return
        c = order[i]
        checks = [cl for cl in by_level[i] if cl.cats != frozenset([c])]
        for p in cand[c]:
            inv[c] = p
            ok = True
            for cl in checks:
                if not cl.fn(inv):
                    ok = False
                    break
            if ok:
                rec(i + 1)
                if count >= cap:
                    break
        del inv[c]
    rec(0)
    return count, sols


# ---------------------------------------------------------------- geometry
class Linear:
    name = 'linear'
    def __init__(self, n):
        self.n = n
    def adj(self, a, b):
        return abs(a - b) == 1
    def left(self, a, b):  # a somewhere left of b
        return a < b
    def immleft(self, a, b):
        return a + 1 == b
    def between(self, a, b, c):  # b strictly between a and c
        return min(a, c) < b < max(a, c)
    def end(self, a):
        return a == 0 or a == self.n - 1

class Circle:
    name = 'circle'
    def __init__(self, n):
        self.n = n
    def adj(self, a, b):
        return (a - b) % self.n in (1, self.n - 1)
    def immcw(self, a, b):  # b is immediately clockwise of a  (b = a+1 mod n)
        return (a + 1) % self.n == b
    def opposite(self, a, b):
        return (a - b) % self.n == self.n // 2

class Grid:
    name = 'grid'
    def __init__(self, rows, cols):
        self.rows, self.cols = rows, cols
        self.n = rows * cols
    def rc(self, p):
        return divmod(p, self.cols)
    def above(self, a, b):  # a directly above b
        ra, ca = self.rc(a); rb, cb = self.rc(b)
        return ca == cb and ra + 1 == rb
    def immleft(self, a, b):
        ra, ca = self.rc(a); rb, cb = self.rc(b)
        return ra == rb and ca + 1 == cb
    def samerow(self, a, b):
        return self.rc(a)[0] == self.rc(b)[0]
    def samecol(self, a, b):
        return self.rc(a)[1] == self.rc(b)[1]
    def adj(self, a, b):
        ra, ca = self.rc(a); rb, cb = self.rc(b)
        return abs(ra - rb) + abs(ca - cb) == 1


# ---------------------------------------------------------------- clue pool
def make_pool(sol_inv, cats, geo, numeric=None, kinds=None, rng=random):
    """sol_inv[cat][val] = pos. numeric: dict cat -> list of numeric values (by val index).
    Returns list of Clue objects all TRUE in the solution."""
    n = geo.n
    items = [(c, v) for c in cats for v in range(n)]
    P = lambda inv, it: inv[it[0]][it[1]]
    pool = []
    kinds = kinds or {}
    def add(kind, cs, fn, data):
        cl = Clue(kind, cs, fn, data)
        assert cl.fn(sol_inv), (kind, data)
        pool.append(cl)
    S = sol_inv
    for x in items:
        px = S[x[0]][x[1]]
        # position facts
        for p in range(n):
            if px == p and kinds.get('pos', 1):
                add('pos', [x[0]], (lambda x, p: lambda inv: P(inv, x) == p)(x, p), (x, p))
            elif px != p and kinds.get('notpos', 1):
                add('notpos', [x[0]], (lambda x, p: lambda inv: P(inv, x) != p)(x, p), (x, p))
        if geo.name == 'linear' and geo.end(px) and kinds.get('end', 1):
            add('end', [x[0]], (lambda x: lambda inv: geo.end(P(inv, x)))(x), (x,))
    for x, y in itertools.combinations(items, 2):
        if x[0] == y[0]:
            continue
        px, py = S[x[0]][x[1]], S[y[0]][y[1]]
        cs = [x[0], y[0]]
        if px == py:
            add('same', cs, (lambda x, y: lambda inv: P(inv, x) == P(inv, y))(x, y), (x, y))
        else:
            add('notsame', cs, (lambda x, y: lambda inv: P(inv, x) != P(inv, y))(x, y), (x, y))
    for x, y in itertools.permutations(items, 2):
        if x[0] == y[0] and x[1] == y[1]:
            continue
        px, py = S[x[0]][x[1]], S[y[0]][y[1]]
        cs = [x[0], y[0]]
        if px == py:
            continue
        if geo.name in ('linear', 'circle', 'grid') and x < y:
            if geo.adj(px, py):
                add('adj', cs, (lambda x, y: lambda inv: geo.adj(P(inv, x), P(inv, y)))(x, y), (x, y))
            else:
                add('notadj', cs, (lambda x, y: lambda inv: not geo.adj(P(inv, x), P(inv, y)))(x, y), (x, y))
        if geo.name == 'linear':
            if geo.left(px, py):
                add('left', cs, (lambda x, y: lambda inv: P(inv, x) < P(inv, y))(x, y), (x, y))
            if geo.immleft(px, py):
                add('immleft', cs, (lambda x, y: lambda inv: P(inv, x) + 1 == P(inv, y))(x, y), (x, y))
            d = abs(px - py)
            if d >= 2 and x < y:
                add('dist', cs, (lambda x, y, d: lambda inv: abs(P(inv, x) - P(inv, y)) == d)(x, y, d), (x, y, d))
        if geo.name == 'circle':
            if geo.immcw(px, py):
                add('immcw', cs, (lambda x, y: lambda inv: geo.immcw(P(inv, x), P(inv, y)))(x, y), (x, y))
            if geo.opposite(px, py) and x < y:
                add('opposite', cs, (lambda x, y: lambda inv: geo.opposite(P(inv, x), P(inv, y)))(x, y), (x, y))
        if geo.name == 'grid':
            if geo.above(px, py):
                add('above', cs, (lambda x, y: lambda inv: geo.above(P(inv, x), P(inv, y)))(x, y), (x, y))
            if geo.immleft(px, py):
                add('gimmleft', cs, (lambda x, y: lambda inv: geo.immleft(P(inv, x), P(inv, y)))(x, y), (x, y))
            if x < y and geo.samerow(px, py):
                add('samerow', cs, (lambda x, y: lambda inv: geo.samerow(P(inv, x), P(inv, y)))(x, y), (x, y))
            if x < y and geo.samecol(px, py):
                add('samecol', cs, (lambda x, y: lambda inv: geo.samecol(P(inv, x), P(inv, y)))(x, y), (x, y))
            if x < y and not geo.samerow(px, py):
                add('diffrow', cs, (lambda x, y: lambda inv: not geo.samerow(P(inv, x), P(inv, y)))(x, y), (x, y))
    # numeric relations
    if numeric:
        for nc, vals in numeric.items():
            # x's numeric value (the nc value at x's position) vs y's
            def numval(inv, it, nc=nc, vals=vals):
                pos = P(inv, it)
                # find value of category nc at pos
                arr = inv[nc]
                for v in range(n):
                    if arr[v] == pos:
                        return vals[v]
            for x, y in itertools.permutations([it for it in items if it[0] != nc], 2):
                px, py = S[x[0]][x[1]], S[y[0]][y[1]]
                if px == py:
                    continue
                vx, vy = numval(S, x), numval(S, y)
                cs = [x[0], y[0], nc]
                diff = vx - vy
                if diff > 0:
                    add('numgt', cs, (lambda x, y, numval=numval: lambda inv: numval(inv, x) > numval(inv, y))(x, y), (x, y, nc))
                    add('numdiff', cs, (lambda x, y, d, numval=numval: lambda inv: numval(inv, x) - numval(inv, y) == d)(x, y, diff), (x, y, nc, diff))
    # disjunctive: exactly one of same(x,a), same(x,b)  (x, a, b different cats or a,b same cat)
    if kinds.get('xor', 1):
        for x in items:
            for a, b in itertools.combinations(items, 2):
                if a[0] == x[0] or b[0] == x[0]:
                    continue
                t1 = S[x[0]][x[1]] == S[a[0]][a[1]]
                t2 = S[x[0]][x[1]] == S[b[0]][b[1]]
                if t1 != t2 and rng.random() < 0.08:
                    add('xor', [x[0], a[0], b[0]], (lambda x, a, b: lambda inv: (P(inv, x) == P(inv, a)) != (P(inv, x) == P(inv, b)))(x, a, b), (x, a, b))
    # of x and y, one is a and the other is b
    if kinds.get('ofxy', 1):
        for x, y in itertools.combinations(items, 2):
            px, py = S[x[0]][x[1]], S[y[0]][y[1]]
            if px == py:
                continue
            for a, b in itertools.permutations(items, 2):
                if a[0] == b[0] and a[1] == b[1]:
                    continue
                pa, pb = S[a[0]][a[1]], S[b[0]][b[1]]
                if pa == px and pb == py and len({x[0], y[0], a[0], b[0]}) >= 3 and a[0] not in (x[0], y[0]) and b[0] not in (x[0], y[0]) and a < b and rng.random() < 0.05:
                    add('ofxy', [x[0], y[0], a[0], b[0]], (lambda x, y, a, b: lambda inv: P(inv, x) != P(inv, y) and {P(inv, x), P(inv, y)} == {P(inv, a), P(inv, b)})(x, y, a, b), (x, y, a, b))
    # conditional: if same(a,b) then same(c,d)
    if kinds.get('ifthen', 1):
        for a, b in itertools.combinations(items, 2):
            if a[0] == b[0]:
                continue
            for c, d in itertools.combinations(items, 2):
                if c[0] == d[0] or {a, b} == {c, d}:
                    continue
                if rng.random() > 0.004:
                    continue
                ant = S[a[0]][a[1]] == S[b[0]][b[1]]
                con = S[c[0]][c[1]] == S[d[0]][d[1]]
                if (not ant) or con:
                    add('ifthen', [a[0], b[0], c[0], d[0]], (lambda a, b, c, d: lambda inv: (P(inv, a) != P(inv, b)) or (P(inv, c) == P(inv, d)))(a, b, c, d), (a, b, c, d))
    return pool


def generate(n, cats, geo, weights, numeric=None, seed=0, max_clues=40, kinds=None, fixed_clues=()):
    rng = random.Random(seed)
    sol = {c: tuple(rng.sample(range(n), n)) for c in cats}  # sol[c][val] = pos
    pool = make_pool(sol, cats, geo, numeric=numeric, kinds=kinds, rng=rng)
    # weighted shuffle
    keyed = [(rng.random() ** (1.0 / max(weights.get(cl.kind, 0), 1e-9)), cl) for cl in pool if weights.get(cl.kind, 0) > 0]
    keyed.sort(key=lambda t: -t[0])
    chosen = list(fixed_clues)
    for _, cl in keyed:
        chosen.append(cl)
        cnt, _ = count_solutions(n, cats, chosen, cap=2)
        if cnt == 1:
            break
        if len(chosen) > max_clues * 3:
            return None
    else:
        return None
    # minimise: try removing clues (prefer removing 'easy' kinds first)
    easy_first = sorted(range(len(chosen)), key=lambda i: (weights.get(chosen[i].kind, 0) if chosen[i].kind in ('pos', 'same') else 100, rng.random()))
    keep = list(chosen)
    for i in easy_first:
        cl = chosen[i]
        trial = [c for c in keep if c is not cl]
        cnt, _ = count_solutions(n, cats, trial, cap=2)
        if cnt == 1:
            keep = trial
    cnt, sols = count_solutions(n, cats, keep, cap=5, collect=True)
    assert cnt == 1
    return sol, keep
