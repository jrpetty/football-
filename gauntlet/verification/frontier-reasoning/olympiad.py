"""Answer-key verification for math.olympiad (12 cases).

Every answer is computed by at least two independent methods and the script
asserts that they agree. Run: python3 olympiad.py   (needs sympy + numpy)
The JS cross-checks live in olympiad_crosscheck.mjs (node olympiad_crosscheck.mjs).
"""
import math
import random
from fractions import Fraction
from functools import lru_cache
from itertools import combinations, permutations, product

import numpy as np
import sympy as sp

RESULTS = {}


def record(cid, *vals):
    assert all(v == vals[0] for v in vals), (cid, vals)
    RESULTS[cid] = vals[0]
    print(f"{cid}: {vals[0]}   (methods agree: {len(vals)})")


def mn(fr):
    fr = Fraction(fr)
    return fr.numerator + fr.denominator


# ---------------------------------------------------------------- o10 cubic sums mod 199
def o10():
    p = 199
    # Method A: direct brute force over all p^3 triples (vectorised).
    c = np.array([pow(x, 3, p) for x in range(p)], dtype=np.int64)
    xy = (c[:, None] + c[None, :]) % p                      # x^3 + y^3
    cnt_xy = np.bincount(xy.ravel(), minlength=p)           # distribution of x^3+y^3
    a = int(sum(cnt_xy[(1 - int(cz)) % p] for cz in c))
    # Method B: fully explicit triple loop over z (independent of bincount trick).
    b = 0
    for z in range(p):
        b += int(np.count_nonzero((xy + c[z]) % p == 1))
    # Method C: Gauss/Jacobi-sum formula N = p^2 + 6p - L, 4p = L^2 + 27M^2, L = 1 (mod 3).
    L = None
    for M in range(1, 20):
        r = 4 * p - 27 * M * M
        if r > 0 and math.isqrt(r) ** 2 == r:
            L = math.isqrt(r)
            L = L if L % 3 == 1 else -L
    formula = p * p + 6 * p - L
    # The formula itself is checked against brute force for five further primes p = 1 (mod 3).
    for q in (211, 223, 229, 241, 271):
        cq = np.array([pow(x, 3, q) for x in range(q)], dtype=np.int64)
        dist = np.bincount(((cq[:, None] + cq[None, :]) % q).ravel(), minlength=q)
        brute = int(sum(dist[(1 - int(z)) % q] for z in cq))
        Lq = next(math.isqrt(4 * q - 27 * M * M) for M in range(1, 20)
                  if 4 * q - 27 * M * M > 0 and math.isqrt(4 * q - 27 * M * M) ** 2 == 4 * q - 27 * M * M)
        Lq = Lq if Lq % 3 == 1 else -Lq
        assert brute == q * q + 6 * q - Lq, q
    record("o10", a, b, formula)


# ---------------------------------------------------------------- o03 mn | m^2+n^2+35
def o03():
    B, C = 10000, 35
    # Method A: n must divide m^2 + C; enumerate divisors.
    A = 0
    for m in range(1, B + 1):
        for n in sp.divisors(m * m + C):
            if n > B:
                break
            if (m * m + n * n + C) % (m * n) == 0:
                A += 1
    # Method B: full grid brute force, row by row (vectorised).
    n = np.arange(1, B + 1, dtype=np.int64)
    Bc = 0
    for m in range(1, B + 1):
        Bc += int(np.count_nonzero((m * m + n * n + C) % (m * n) == 0))
    # Method C: Vieta jumping from the base solutions (m <= n, n^2 <= m^2 + C).
    base = [(m, k) for m in range(1, 40) for k in range(m, 40)
            if k * k <= m * m + C and (m * m + k * k + C) % (m * k) == 0]
    sols = set()
    for (m0, n0) in base:
        k = (m0 * m0 + n0 * n0 + C) // (m0 * n0)
        stack = [(m0, n0)]
        while stack:
            x, y = stack.pop()
            if x > B or y > B or (x, y) in sols:
                continue
            sols.add((x, y)); sols.add((y, x))
            stack.append((x, k * x - y))      # jump y
            stack.append((k * y - x, y))      # jump x
    Cc = sum(1 for (x, y) in sols if 1 <= x <= B and 1 <= y <= B)
    record("o03", A, Bc, Cc)
    return base


# ---------------------------------------------------------------- o07 decimal period 2026
def o07():
    def period(n):
        while n % 2 == 0:
            n //= 2
        while n % 5 == 0:
            n //= 5
        return 1 if n == 1 else sp.n_order(10, n)
    # Method A: linear search with sympy's multiplicative order.
    n = 1
    while period(n) != 2026:
        n += 1
    A = n
    # Method B: number-theoretic argument. A prime power factor p^e with 1013 | ord must have
    # p = 1 (mod 1013) (or p = 1013, but ord_1013(10) | 1012). Scan such primes in order and the
    # composite candidates built from them; order computed with plain pow().
    def order(n):
        o = 1
        x = 10 % n
        while x != 1:
            x = x * 10 % n
            o += 1
        return o
    cands = []
    for q in range(1014, 30000, 1013):
        if sp.isprime(q):
            cands.append((q, order(q)))
    # candidates below 22297 = 11 * 2027
    best = min([q for q, o in cands if o == 2026] + [11 * q for q, o in cands if o == 1013])
    record("o07", A, best)
    return cands


# ---------------------------------------------------------------- o05 menage with opposite
def o05():
    N = 12
    edges = sorted({tuple(sorted((i, (i + d) % N))) for i in range(N) for d in (1, N // 2)})
    m = [0] * 7

    def rec(idx, used, k):
        if idx == len(edges):
            m[k] += 1
            return
        rec(idx + 1, used, k)
        a, b = edges[idx]
        if not (used >> a) & 1 and not (used >> b) & 1:
            rec(idx + 1, used | (1 << a) | (1 << b), k + 1)
    rec(0, 0, 0)
    # Method A: inclusion-exclusion over couples forced onto forbidden seat pairs.
    ie = sum((-1) ** k * math.comb(6, k) * m[k] * math.factorial(k) * 2 ** k * math.factorial(N - 2 * k) for k in range(7))
    # Method B: backtracking over couple patterns (x 2^6 for the order inside each couple).
    count = 0

    def bt(seat, sc, cnt):
        nonlocal count
        if seat == N:
            count += 1
            return
        for c in range(6):
            if cnt[c] == 2:
                continue
            if seat > 0 and sc[seat - 1] == c:
                continue
            if seat == N - 1 and sc[0] == c:
                continue
            if seat >= N // 2 and sc[seat - N // 2] == c:
                continue
            sc[seat] = c; cnt[c] += 1
            bt(seat + 1, sc, cnt)
            cnt[c] -= 1; sc[seat] = -1
    bt(0, [-1] * N, [0] * 6)
    brute = count * 64
    assert ie == brute
    record("o05", ie // 12, brute // 12)
    return m


# ---------------------------------------------------------------- o08 triangulations with 4 ears
def o08():
    n, k = 13, 4

    def tris(vs):
        if len(vs) < 3:
            yield []
            return
        a, b = vs[0], vs[-1]
        for i in range(1, len(vs) - 1):
            for L in tris(vs[:i + 1]):
                for R in tris(vs[i:]):
                    yield L + R + [(a, vs[i], b)]

    def ears(ts):
        e = 0
        for t in ts:
            sides = [(t[0], t[1]), (t[1], t[2]), (t[0], t[2])]
            if sum(1 for x, y in sides if (x - y) % n in (1, n - 1)) >= 2:
                e += 1
        return e
    # Method A: enumerate all Catalan(11) = 58786 triangulations.
    A = sum(1 for ts in tris(tuple(range(n))) if ears(ts) == k)

    # Method B: DP with ear-count generating polynomials (sub-polygons hang off diagonals).
    @lru_cache(None)
    def F(m):  # sub-polygon with m boundary edges whose base is a diagonal; returns {ears: count}
        if m == 1:
            return {0: 1}
        out = {}
        for j in range(1, m):
            ear = 1 if (j == 1 and m - j == 1) else 0
            for e1, c1 in F(j).items():
                for e2, c2 in F(m - j).items():
                    out[e1 + e2 + ear] = out.get(e1 + e2 + ear, 0) + c1 * c2
        return out
    top = {}
    for j in range(1, n - 1):  # apex vertex j of the triangle on boundary edge (0, n-1)
        ear = 1 if (j == 1 or j == n - 2) else 0
        for e1, c1 in F(j).items():
            for e2, c2 in F(n - 1 - j).items():
                top[e1 + e2 + ear] = top.get(e1 + e2 + ear, 0) + c1 * c2
    Bv = top.get(k, 0)
    # Method C: Hurtado-Noy closed form n/k * 2^(n-2k) * C(n-4, 2k-4) * Catalan(k-2).
    cat = lambda x: math.comb(2 * x, x) // (x + 1)
    Cv = Fraction(n, k) * 2 ** (n - 2 * k) * math.comb(n - 4, 2 * k - 4) * cat(k - 2)
    record("o08", A, Bv, int(Cv))


# ---------------------------------------------------------------- o06 icosahedron 5-colourings
def icosahedron():
    phi = (1 + 5 ** 0.5) / 2
    V = []
    for a in (-1, 1):
        for b in (-phi, phi):
            V += [(0, a, b), (a, b, 0), (b, 0, a)]
    V = np.array(V)
    D = np.linalg.norm(V[:, None] - V[None], axis=2)
    adj = [[j for j in range(12) if abs(D[i, j] - 2) < 1e-9] for i in range(12)]
    rots = set()
    b0 = adj[0][0]
    c0 = [x for x in adj[0] if x in adj[b0]][0]
    for va in range(12):
        for vb in adj[va]:
            for vc in [x for x in adj[va] if x in adj[vb]]:
                A = np.array([V[0], V[b0], V[c0]]).T
                Bm = np.array([V[va], V[vb], V[vc]]).T
                M = Bm @ np.linalg.inv(A)
                if abs(np.linalg.det(M) - 1) > 1e-6:
                    continue
                img = (M @ V.T).T
                p = tuple(int(np.argmin(np.linalg.norm(V - img[i], axis=1))) for i in range(12))
                if sorted(p) == list(range(12)):
                    rots.add(p)
    return adj, sorted(rots)


def o06():
    adj, rots = icosahedron()
    assert len(rots) == 60
    K = 5
    cols = []
    col = [-1] * 12

    def rec(v):
        if v == 12:
            cols.append(tuple(col))
            return
        for c in range(K):
            if all(col[w] != c for w in adj[v]):
                col[v] = c
                rec(v + 1)
                col[v] = -1
    rec(0)
    # Method A: Burnside.
    fixed = sum(1 for p in rots for c in cols if all(c[p[v]] == c[v] for v in range(12)))
    A = Fraction(fixed, 60)
    # Method B: count orbits directly via canonical representatives.
    reps = set()
    for c in cols:
        reps.add(min(tuple(c[p[v]] for v in range(12)) for p in rots))
    # No non-identity rotation fixes a proper colouring: each has an orbit containing two
    # adjacent vertices (so Burnside reduces to (number of proper colourings) / 60).
    for p in rots:
        if p == tuple(range(12)):
            continue
        hit = False
        for v in range(12):
            w = p[v]
            while w != v:
                if w in adj[v]:
                    hit = True
                w = p[w]
        assert hit
    # Method C: independent antiprism-band count of proper colourings, divided by 60.
    Cv = Fraction(ico_transfer(K), 60)
    record("o06", int(A), len(reps), int(Cv))
    return len(cols)


def ico_transfer(K=5):
    """Independent count of proper K-colourings of the icosahedron viewed as a pentagonal
    antiprism band capped by a top and a bottom vertex: top T ~ u0..u4, bottom B ~ l0..l4,
    u_i ~ u_(i+1), l_i ~ l_(i+1), u_i ~ l_i, u_i ~ l_(i+1)."""
    def rings(avoid):
        return [R for R in product(range(K), repeat=5)
                if avoid not in R and all(R[i] != R[(i + 1) % 5] for i in range(5))]
    top = 0                       # colour symmetry: fix the top colour, multiply by K
    U = rings(top)
    total = 0
    for bcol in range(K):         # the bottom may share the top's colour (not adjacent)
        for Lr in rings(bcol):
            for Ur in U:
                if all(Ur[i] != Lr[i] and Ur[i] != Lr[(i + 1) % 5] for i in range(5)):
                    total += 1
    return total * K


# ---------------------------------------------------------------- o09 knight 5x5 hitting time
def knight_graph(n):
    sq = [(r, c) for r in range(n) for c in range(n)]
    mv = [(1, 2), (2, 1), (-1, 2), (-2, 1), (1, -2), (2, -1), (-1, -2), (-2, -1)]
    return sq, {s: [(s[0] + a, s[1] + b) for a, b in mv if 0 <= s[0] + a < n and 0 <= s[1] + b < n] for s in sq}


def o09():
    sq, nb = knight_graph(5)
    a, b = (0, 0), (4, 4)
    # Method A: hitting-time linear system.
    unk = [s for s in sq if s != b]
    ui = {s: i for i, s in enumerate(unk)}
    M = sp.zeros(len(unk)); rhs = sp.zeros(len(unk), 1)
    for s in unk:
        i = ui[s]; d = len(nb[s]); M[i, i] = d; rhs[i] = d
        for t in nb[s]:
            if t != b:
                M[i, ui[t]] -= 1
    H = Fraction(str(M.LUsolve(rhs)[ui[a]]))
    # Method B: electrical network. Tetali: H(a,b) = 1/2 sum_v deg(v) (R_ab + R_bv - R_av).
    idx = {s: i for i, s in enumerate(sq)}
    Lap = sp.zeros(25)
    for s in sq:
        for t in nb[s]:
            Lap[idx[s], idx[s]] += 1; Lap[idx[s], idx[t]] -= 1
    Lp = (Lap + sp.ones(25, 25) / 25).inv() - sp.ones(25, 25) / 25   # Moore-Penrose pseudo-inverse
    R = lambda x, y: Lp[idx[x], idx[x]] + Lp[idx[y], idx[y]] - 2 * Lp[idx[x], idx[y]]
    H2 = sp.Rational(1, 2) * sum(len(nb[v]) * (R(a, b) + R(b, v) - R(a, v)) for v in sq)
    H2 = Fraction(str(sp.nsimplify(H2)))
    # Method C: |E| * R_eff (a and b are swapped by the 180-degree rotation of the board).
    E = sum(len(nb[s]) for s in sq) // 2
    H3 = Fraction(str(sp.nsimplify(E * R(a, b))))
    # Monte Carlo sanity check
    rng = random.Random(1)
    tot = 0; T = 20000
    for _ in range(T):
        s = a; k = 0
        while s != b:
            s = rng.choice(nb[s]); k += 1
        tot += k
    print(f"   o09 exact {H} = {float(H):.4f}; simulation {tot / T:.4f}")
    record("o09", mn(H), mn(H2), mn(H3))


# ---------------------------------------------------------------- o02 random 6-subset of {1..24}
def o02():
    N, K, S = 24, 6, 25
    # Method A: brute force.
    good = sum(1 for T in combinations(range(1, N + 1), K)
               if all(y - x != 1 and x + y != S for x, y in combinations(T, 2)))
    # Method B: the graph is the 2 x 12 ladder (pairs {i, 25-i}); transfer matrix with size polynomials.
    # column i = {i, 25-i}, i = 1..12; states: 0 none, 1 top (i), 2 bottom (25-i)
    x = sp.Symbol('x')
    vec = {0: 1, 1: x, 2: x}
    for _ in range(11):
        vec = {0: sp.expand(sum(vec.values())),
               1: sp.expand(x * (vec[0] + vec[2])),
               2: sp.expand(x * (vec[0] + vec[1]))}
    poly = sp.Poly(sp.expand(sum(vec.values())), x)
    Bv = poly.coeff_monomial(x ** K)
    pA = Fraction(good, math.comb(N, K))
    pB = Fraction(int(Bv), math.comb(N, K))
    record("o02", mn(pA), mn(pB))
    return good


# ---------------------------------------------------------------- o12 cube cover time
def solve_lin(A, b):
    n = len(A)
    M = [row[:] + [b[i]] for i, row in enumerate(A)]
    for c in range(n):
        p = next(r for r in range(c, n) if M[r][c] != 0)
        M[c], M[p] = M[p], M[c]
        pv = M[c][c]
        M[c] = [v / pv for v in M[c]]
        for r in range(n):
            if r != c and M[r][c] != 0:
                f = M[r][c]
                M[r] = [v - f * w for v, w in zip(M[r], M[c])]
    return [M[i][n] for i in range(n)]


def cover_time_exact(adj, start):
    n = len(adj); full = (1 << n) - 1
    E = {}
    for S in sorted(range(1, 1 << n), key=lambda s: -bin(s).count('1')):
        if S == full:
            continue
        vs = [v for v in range(n) if (S >> v) & 1]
        ix = {v: i for i, v in enumerate(vs)}
        A = [[Fraction(0)] * len(vs) for _ in vs]; bb = [Fraction(1)] * len(vs)
        for v in vs:
            i = ix[v]; A[i][i] += 1; d = len(adj[v])
            for w in adj[v]:
                if (S >> w) & 1:
                    A[i][ix[w]] -= Fraction(1, d)
                elif (S | (1 << w)) != full:
                    bb[i] += Fraction(1, d) * E[(w, S | (1 << w))]
        for v, val in zip(vs, solve_lin(A, bb)):
            E[(v, S)] = val
    return E[(start, 1 << start)]


def o12():
    cube = [[v ^ (1 << i) for i in range(3)] for v in range(8)]
    A = cover_time_exact(cube, 0)
    # Method B: one global floating-point linear system over all (vertex, visited-set) states.
    states = [(v, S) for S in range(1, 256) for v in range(8) if (S >> v) & 1 and S != 255]
    ix = {s: i for i, s in enumerate(states)}
    M = np.eye(len(states)); rhs = np.ones(len(states))
    for (v, S), i in ix.items():
        for w in cube[v]:
            S2 = S | (1 << w)
            if S2 != 255:
                M[i, ix[(w, S2)]] -= 1 / 3
    x = np.linalg.solve(M, rhs)[ix[(0, 1)]]
    Bf = Fraction(x).limit_denominator(1000)
    # Monte Carlo sanity
    rng = random.Random(7); tot = 0; T = 100000
    for _ in range(T):
        v, S, k = 0, 1, 0
        while S != 255:
            v = rng.choice(cube[v]); S |= 1 << v; k += 1
        tot += k
    print(f"   o12 exact {A} = {float(A):.5f}; float system {x:.8f}; simulation {tot / T:.4f}")
    record("o12", mn(A), mn(Bf))


# ---------------------------------------------------------------- o01 f(f(n)) = 3n + 2
def o01():
    # Method A: closed form of the classic h(h(m)) = 3m, then f(n) = h(n+1) - 1.
    def h(m):
        k = 0
        while 3 ** (k + 1) <= m:
            k += 1
        return m + 3 ** k if m <= 2 * 3 ** k else 3 * m - 3 ** (k + 1)
    f = lambda n: h(n + 1) - 1
    LIM = 100000
    vals = [f(n) for n in range(LIM)]
    assert all(vals[i] < vals[i + 1] for i in range(LIM - 1))
    assert all(f(f(n)) == 3 * n + 2 for n in range(LIM))
    A = f(2026)
    # Method B: greedy forced construction of f directly (no closed form): f(0) is forced to be 1
    # (f(0)=0 gives f(f(0))=0; f(0)>=2 gives f(f(0)) >= f(2) > f(1) > f(0) >= 2 but then
    # f(f(0)) = 2 is impossible). Then each value is either forced by f(f(n)) = 3n+2 or is the
    # smallest value keeping f increasing (any larger choice breaks f(f(n)) = 3n+2 later).
    g = {0: 1}
    forced = {1: 2}
    n = 1
    while n <= 2026:
        if n in forced:
            g[n] = forced[n]
        else:
            g[n] = g[n - 1] + 1
        assert g[n] > g[n - 1]
        forced[g[n]] = 3 * n + 2
        if g[n] in g:
            assert g[g[n]] == 3 * n + 2
        n += 1
    record("o01", A, g[2026])


# ---------------------------------------------------------------- o04 edge-tangent sphere
def o04():
    t = [1, 2, 3, 4]   # tangent lengths from A, B, C, D (AB = 3, AC = 4, AD = 5, BC = 5, BD = 6, CD = 7)
    d = {(i, j): t[i] + t[j] for i in range(4) for j in range(4) if i != j}
    # Method A: exact Gram-matrix computation of the radical centre O of the vertex spheres.
    G = sp.zeros(3)
    for a in range(1, 4):
        for b in range(1, 4):
            G[a - 1, b - 1] = d[(0, a)] ** 2 if a == b else sp.Rational(d[(0, a)] ** 2 + d[(0, b)] ** 2 - d[(a, b)] ** 2, 2)
    assert G.det() > 0
    rhs = sp.Matrix([(G[i, i] - t[i + 1] ** 2 + t[0] ** 2) / 2 for i in range(3)])
    X = G.LUsolve(rhs)
    r2 = sp.nsimplify((X.T * G * X)[0] - t[0] ** 2)
    A = Fraction(int(sp.fraction(r2)[0]), int(sp.fraction(r2)[1]))
    # Method B: explicit float coordinates, sphere centre by least squares, check tangency to all 6 edges.
    P = np.zeros((4, 3))
    P[1] = [3, 0, 0]
    x = (3 ** 2 + 4 ** 2 - 5 ** 2) / (2 * 3); P[2] = [x, math.sqrt(16 - x * x), 0]

    def fD(v):  # D with |DA| = 5, |DB| = 6, |DC| = 7 (Newton iteration)
        return np.array([np.linalg.norm(v - P[0]) - 5, np.linalg.norm(v - P[1]) - 6, np.linalg.norm(v - P[2]) - 7])
    v = np.array([1.0, 1.0, 3.0])
    for _ in range(100):
        J = np.zeros((3, 3)); f0 = fD(v)
        for k in range(3):
            e = np.zeros(3); e[k] = 1e-7
            J[:, k] = (fD(v + e) - f0) / 1e-7
        v = v - np.linalg.solve(J, f0)
    assert np.abs(fD(v)).max() < 1e-9
    P[3] = v
    # centre O: |O-P_i|^2 - t_i^2 equal for all i -> linear system
    Mx = np.array([2 * (P[i] - P[0]) for i in range(1, 4)])
    bx = np.array([P[i] @ P[i] - P[0] @ P[0] - t[i] ** 2 + t[0] ** 2 for i in range(1, 4)])
    O = np.linalg.solve(Mx, bx)
    dists = []
    for i, j in combinations(range(4), 2):
        u = P[j] - P[i]
        s = (O - P[i]) @ u / (u @ u)
        assert 0 < s < 1
        dists.append(np.linalg.norm(O - (P[i] + s * u)))
    assert max(dists) - min(dists) < 1e-9
    Bv = Fraction(dists[0] ** 2).limit_denominator(10000)
    # Method C: Descartes-type identity 4/r^2 = (sum 1/t)^2 - 2 sum 1/t^2 for 4 mutually tangent spheres.
    s1 = sum(Fraction(1, x) for x in t); s2 = sum(Fraction(1, x * x) for x in t)
    Cv = 4 / (s1 * s1 - 2 * s2)
    record("o04", mn(A), mn(Bv), mn(Cv))


# ---------------------------------------------------------------- o11 sum of floor(k sqrt 2)
def o11():
    n = 2026
    A = sum(math.isqrt(2 * k * k) for k in range(1, n + 1))
    # Method B: Beatty complement recursion. floor(k*sqrt2) and floor(j*(2+sqrt2)) partition the
    # positive integers, so S(n) = N(N+1)/2 - [m(m+1) + S(m)] with N = floor(n sqrt2) and
    # m = #{j : floor(j(2+sqrt2)) <= N}.
    def S(n):
        if n == 0:
            return 0
        N = math.isqrt(2 * n * n)
        # largest j with floor(j(2+sqrt2)) <= N  <=>  2j + floor(j sqrt2) <= N
        lo, hi = 0, n
        while lo < hi:
            mid = (lo + hi + 1) // 2
            if 2 * mid + math.isqrt(2 * mid * mid) <= N:
                lo = mid
            else:
                hi = mid - 1
        m = lo
        return N * (N + 1) // 2 - (m * (m + 1) + S(m))
    # Method C: high-precision decimal arithmetic (independent of isqrt).
    from decimal import Decimal, getcontext
    getcontext().prec = 60
    r2 = Decimal(2).sqrt()
    Cv = sum(int((Decimal(k) * r2).to_integral_value(rounding="ROUND_FLOOR")) for k in range(1, n + 1))
    record("o11", A, S(n), Cv)


if __name__ == "__main__":
    o01()
    o02()
    o03()
    o04()
    o05()
    o06()
    o07()
    o08()
    o09()
    o10()
    o11()
    o12()
    print(RESULTS)
