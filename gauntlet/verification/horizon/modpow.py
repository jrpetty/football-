"""Horizon: No Calculator (horizon.modpow-ladder).

Ten modular exponentiations a^e mod m with growing numbers. Level N uses a modulus and an exponent with
more digits than level N-1, so the pencil-and-paper work (about log2(e) modular multiplications of
m-sized numbers) grows every level. The modulus is odd and composite (from 6 digits on, with no prime factor below 200), and
e < m, so no theorem shortcuts the arithmetic: it has to be done digit by digit.

Key: Python's built-in pow(a, e, m). Cross-check: modpow_check.mjs, an independent BigInt
square-and-multiply in JavaScript, plus a left-to-right binary method here.

Run: python3 modpow.py
"""
from __future__ import annotations

import json
import math
import os
import random

HERE = os.path.dirname(os.path.abspath(__file__))

# level -> (digits of m, digits of e)
DIGITS = {1: (3, 2), 2: (4, 3), 3: (5, 4), 4: (6, 5), 5: (8, 6), 6: (10, 8), 7: (12, 10), 8: (15, 12), 9: (18, 15), 10: (24, 20)}
SMALL_PRIMES = [p for p in range(3, 200) if all(p % q for q in range(2, int(p ** 0.5) + 1))]


def is_probable_prime(n: int) -> bool:
    if n < 2:
        return False
    for p in [2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37]:
        if n % p == 0:
            return n == p
    d, s = n - 1, 0
    while d % 2 == 0:
        d //= 2
        s += 1
    for a in [2, 3, 5, 7, 11, 13, 17, 19, 23, 29, 31, 37]:
        x = pow(a, d, n)
        if x in (1, n - 1):
            continue
        for _ in range(s - 1):
            x = x * x % n
            if x == n - 1:
                break
        else:
            return False
    return True


def ltr(a: int, e: int, m: int) -> int:
    r = 1
    for bit in bin(e)[2:]:
        r = r * r % m
        if bit == '1':
            r = r * a % m
    return r


def make(level: int, rng: random.Random):
    dm, de = DIGITS[level]
    while True:
        m = rng.randrange(10 ** (dm - 1), 10 ** dm) | 1
        if is_probable_prime(m) or (dm >= 6 and any(m % p == 0 for p in SMALL_PRIMES)):
            continue
        a = rng.randrange(10 ** (dm - 2) if dm > 2 else 2, m)
        e = rng.randrange(10 ** (de - 1), 10 ** de)
        key = pow(a, e, m)
        # No degenerate cases: a must be a unit, the answer must not be 0, 1 or a itself, and the powers of a
        # must not cycle quickly (a short cycle would be a shortcut past the arithmetic).
        if math.gcd(a, m) == 1 and key not in (0, 1, a) and all(pow(a, k, m) != 1 for k in range(1, min(200, m // 8))):
            break
    assert key == ltr(a, e, m)
    return {'level': level, 'a': a, 'e': e, 'm': m, 'answer': key, 'bits': e.bit_length()}


def main():
    rng = random.Random(int(os.environ.get('HORIZON_SEED', '2026')) * 7 + 1)
    cases = [make(level, rng) for level in range(1, 11)]
    for c in cases:
        print(f"L{c['level']:02d} {c['a']}^{c['e']} mod {c['m']} = {c['answer']}")
    with open(os.path.join(HERE, 'modpow_cases.json'), 'w') as fh:
        json.dump(cases, fh, indent=1)


if __name__ == '__main__':
    main()
