"""Brute-force verification of math.competition answers (each with 2 independent methods where feasible)."""
from fractions import Fraction as F
from itertools import product, permutations, combinations
from math import gcd, comb, isqrt
import cmath, math, random

R = {}

# C01: n <= 3000 divisible by 4 or 6 but not by 9
bf = sum(1 for n in range(1, 3001) if (n % 4 == 0 or n % 6 == 0) and n % 9 != 0)
# inclusion-exclusion: |4 or 6| = 750+500-250 = 1000 ; minus those divisible by 9: lcm(4,9)=36 ->83, lcm(6,9)=18 ->166, lcm(12,9)=36 ->83 : 83+166-83 = 166
ie = (3000//4 + 3000//6 - 3000//12) - (3000//36 + 3000//18 - 3000//36)
assert bf == ie; R['C01'] = bf

# C02: roots of x^3 - 4x^2 + 6x - 9: prod (a^2 + 4)
coef = [1, -4, 6, -9]
roots = __import__('numpy').roots(coef)
num = 1
for r in roots:
    num *= (r * r + 4)
# exact: prod (a-2i)(a+2i) = P(2i) P(-2i) (degree 3: (-1)^3 twice)
P = lambda x: x**3 - 4*x**2 + 6*x - 9
ex = P(2j) * P(-2j)
assert abs(num - ex) < 1e-6 and abs(ex.imag) < 1e-9
R['C02'] = round(ex.real)

# C03: smallest n with 2^50 * 3^30 | n!
def v(n, p):
    s = 0; q = p
    while q <= n:
        s += n // q; q *= p
    return s
n = 1
while not (v(n, 2) >= 50 and v(n, 3) >= 30):
    n += 1
fact = 1
for k in range(1, n + 1): fact *= k
assert fact % (2**50 * 3**30) == 0
f2 = fact // n
assert f2 % (2**50 * 3**30) != 0
R['C03'] = n

# C04: monotone lattice paths (0,0)->(9,6) avoiding (3,2),(5,4),(6,1)
bad = {(3, 2), (5, 4), (6, 1)}
W, H = 9, 6
dp = [[0] * (H + 1) for _ in range(W + 1)]
for x in range(W + 1):
    for y in range(H + 1):
        if (x, y) in bad: dp[x][y] = 0; continue
        if x == 0 and y == 0: dp[x][y] = 1; continue
        dp[x][y] = (dp[x-1][y] if x else 0) + (dp[x][y-1] if y else 0)
# brute force: enumerate all C(15,6) step sequences
cnt = 0
for ups in combinations(range(W + H), H):
    x = y = 0; ok = True
    s = set(ups)
    for i in range(W + H):
        if i in s: y += 1
        else: x += 1
        if (x, y) in bad: ok = False; break
    cnt += ok
assert cnt == dp[W][H]; R['C04'] = cnt

# C05: three fair six-sided dice and one fair eight-sided die: P(sum of d6s == 2 * d8)
fav = sum(1 for a, b, c, d in product(range(1, 7), range(1, 7), range(1, 7), range(1, 9)) if a + b + c == 2 * d)
p = F(fav, 6**3 * 8)
R['C05'] = p.numerator + p.denominator
R['C05_frac'] = p

# C06: last three digits of 13^(2026^2026)
R['C06'] = pow(13, 2026**2026, 1000)
assert R['C06'] == pow(13, pow(2026, 2026, 100), 1000)  # ord divides 100 (lambda(1000)=100)

# C07: ordered pairs (a,b) positive ints with lcm = 2^3 3^4 5^2 7 and gcd = 2 * 3
L = 2**3 * 3**4 * 5**2 * 7
G = 6
divs = [d for d in range(1, L + 1) if L % d == 0]
cnt = 0
for a in divs:
    for b in divs:
        if gcd(a, b) == G and a * b // gcd(a, b) == L:
            cnt += 1
R['C07'] = cnt

# C08: integer solutions of x^2 + xy + y^2 = 2401
cnt = 0
for x in range(-60, 61):
    for y in range(-60, 61):
        if x*x + x*y + y*y == 2401: cnt += 1
R['C08'] = cnt

# C09: five-digit numbers, digits non-decreasing left to right, digit sum 20
cnt = sum(1 for n in range(10000, 100000) if list(str(n)) == sorted(str(n)) and sum(map(int, str(n))) == 20)
R['C09'] = cnt

# C10: sum of positive n < 1000 with n^2 ≡ 1 (mod 840)
R['C10'] = sum(n for n in range(1, 1000) if (n*n - 1) % 840 == 0)
R['C10_count'] = sum(1 for n in range(1, 1000) if (n*n - 1) % 840 == 0)

# C11: count n in [1, 9999] with S(n) == S(2n)
S = lambda n: sum(map(int, str(n)))
R['C11'] = sum(1 for n in range(1, 10000) if S(n) == S(2*n))
# second method: digit strings d1..d4 where sum == 9 * #(digits >= 5)
alt = sum(1 for ds in product(range(10), repeat=4) if any(ds) and sum(ds) == 9 * sum(1 for d in ds if d >= 5))
assert alt == R['C11']

# C12: subsets of {1..20} with no two elements differing by 1 or 3 (empty set counts)
cnt = 0
for m in range(1 << 20):
    if m & (m >> 1): continue
    if m & (m >> 3): continue
    cnt += 1
# DP check
from functools import lru_cache
@lru_cache(None)
def f(i, last3):  # last3 bits: whether i-1, i-2, i-3 chosen
    if i > 20: return 1
    a, b, c = last3
    res = f(i + 1, (0, a, b))
    if not a and not c:
        res += f(i + 1, (1, a, b))
    return res
assert f(1, (0, 0, 0)) == cnt
R['C12'] = cnt

# C13: walk on integers from 0; +1 w.p. 2/3, -1 w.p. 1/3; stops on reaching -2 or +4. Expected steps.
import sympy as sp
states = list(range(-1, 4))
E = {s: sp.Symbol(f'E{s+1}') for s in states}
eqs = []
for s in states:
    def val(t):
        return 0 if t in (-2, 4) else E[t]
    eqs.append(sp.Eq(E[s], 1 + sp.Rational(2, 3) * val(s + 1) + sp.Rational(1, 3) * val(s - 1)))
sol = sp.solve(eqs, list(E.values()))
e0 = sp.nsimplify(sol[E[0]])
R['C13_frac'] = e0
R['C13'] = int(sp.numer(e0) + sp.denom(e0))
# Monte Carlo sanity
rng = random.Random(1)
tot = 0; N = 200000
for _ in range(N):
    x = 0; k = 0
    while -2 < x < 4:
        x += 1 if rng.random() < 2/3 else -1; k += 1
    tot += k
R['C13_mc'] = tot / N

# C14: permutations s of {1..6} with i + s(i) never prime
primes = {2, 3, 5, 7, 11}
R['C14'] = sum(1 for p in permutations(range(1, 7)) if all((i + 1 + p[i]) not in primes for i in range(6)))

print(R)
