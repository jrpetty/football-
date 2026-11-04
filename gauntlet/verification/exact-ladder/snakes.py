"""Exact Maths: Ladder (math.exact-ladder) - the ten boards and their exact keys.

Every level is the same one-player snakes-and-ladders game with traps: a fair spinner numbered 1 to d, a track of
squares 0..N, reaching N or beyond wins, landing on a trap loses. The question is always the exact probability of
winning. Level by level the track gets longer and gets more ladders, traps and snakes (and a bigger spinner at the
start of the ladder). Snakes are what make it hard: they send the counter back, so the probabilities are defined by a
system of linear equations with one unknown per snake loop instead of a plain backward recursion, and the exact
fractions grow to dozens of digits.

Key (method 1, here): the full linear system "P(s) = (1/d) * sum over spins of the value of the landing square",
one equation per square the counter can rest on, solved by Gauss-Jordan elimination over Python Fractions.
Method 2 (verify.mjs): re-reads every board from the rendered prompt text and recomputes the probability in
JavaScript with BigInt rationals by a different algorithm (backward recursion that carries every square's value as
an affine function of the snake-tail values, then a small elimination for those unknowns).

Run: python3 snakes.py [--out DIR]   (writes snakes_cases.json; deterministic, fixed seeds)
"""
from __future__ import annotations

import json
import os
import random
import sys
from fractions import Fraction

HERE = os.path.dirname(os.path.abspath(__file__))

# level -> (N = finish square, d = spinner size, ladders, snakes, traps)
LEVELS = {
    1: (10, 2, 0, 0, 2),
    2: (12, 3, 1, 0, 2),
    3: (14, 4, 1, 1, 2),
    4: (18, 4, 2, 1, 3),
    5: (22, 6, 2, 2, 3),
    6: (28, 6, 3, 2, 4),
    7: (34, 6, 3, 3, 5),
    8: (42, 6, 4, 4, 5),
    9: (52, 6, 5, 5, 6),
    10: (64, 6, 6, 6, 8),
}
SEED_BASE = 20261104


def landing(t: int, N: int, ladders: dict, snakes: dict, traps: set):
    """Where a move that ends on square t leaves the counter: 'W' (won), 'L' (lost) or the square it rests on."""
    if t >= N:
        return 'W'
    if t in traps:
        return 'L'
    if t in ladders:
        return ladders[t]
    if t in snakes:
        return snakes[t]
    return t


def win_probability(N: int, d: int, ladders: dict, snakes: dict, traps: set) -> Fraction:
    """Method 1: one equation per resting square, Gauss-Jordan elimination over exact fractions."""
    rest = [s for s in range(N) if s not in traps and s not in ladders and s not in snakes]
    idx = {s: i for i, s in enumerate(rest)}
    n = len(rest)
    A = [[Fraction(0)] * (n + 1) for _ in range(n)]
    for s in rest:
        i = idx[s]
        A[i][i] += 1
        for r in range(1, d + 1):
            t = landing(s + r, N, ladders, snakes, traps)
            if t == 'W':
                A[i][n] += Fraction(1, d)
            elif t != 'L':
                A[i][idx[t]] -= Fraction(1, d)
    for c in range(n):
        p = next((r for r in range(c, n) if A[r][c] != 0), None)
        if p is None:
            raise ValueError('singular system: the game can go on forever')
        A[c], A[p] = A[p], A[c]
        pv = A[c][c]
        A[c] = [x / pv for x in A[c]]
        for r in range(n):
            if r != c and A[r][c] != 0:
                f = A[r][c]
                A[r] = [x - f * y for x, y in zip(A[r], A[c])]
    return A[idx[0]][n]


def board(level: int, attempt: int):
    N, d, nl, ns, nt = LEVELS[level]
    rng = random.Random(SEED_BASE + 1000 * level + attempt)
    squares = list(range(1, N))
    rng.shuffle(squares)
    traps = set(squares.pop() for _ in range(nt))
    special = set(traps)
    ladders, snakes = {}, {}
    for _ in range(nl):
        foot = squares.pop()
        tops = [x for x in range(foot + 2, N) if x not in special]
        if not tops:
            return None
        ladders[foot] = rng.choice(tops)
        special.add(foot)
    for _ in range(ns):
        head = squares.pop()
        tails = [x for x in range(0, head - 1) if x not in special]
        if not tails:
            return None
        snakes[head] = rng.choice(tails)
        special.add(head)
    ends = set(ladders.values()) | set(snakes.values())
    if ends & special or len(ends) != nl + ns:
        return None  # a ladder top or snake tail must be an ordinary square, and all different
    # no run of d specials in a row (keeps every board playable and the system well-conditioned for hand work)
    run = 0
    for s in range(1, N):
        run = run + 1 if s in special else 0
        if run >= d:
            return None
    return {'N': N, 'd': d, 'ladders': ladders, 'snakes': snakes, 'traps': traps}


def main():
    out = []
    prev_digits = 0
    for level in sorted(LEVELS):
        attempt = 0
        while True:
            b = board(level, attempt)
            if b is not None:
                try:
                    p = win_probability(b['N'], b['d'], b['ladders'], b['snakes'], b['traps'])
                except ValueError:
                    p = None
                # an interesting probability, and every snake and ladder actually matters
                if p is not None and Fraction(1, 10) <= p <= Fraction(9, 10) and len(str(p.denominator)) > prev_digits:
                    matters = True
                    for kind in ('ladders', 'snakes'):
                        for k in list(b[kind]):
                            reduced = dict(b[kind])
                            del reduced[k]
                            other = dict(b['ladders'] if kind == 'snakes' else b['snakes'])
                            args = (reduced, other) if kind == 'ladders' else (other, reduced)
                            try:
                                if win_probability(b['N'], b['d'], args[0], args[1], b['traps']) == p:
                                    matters = False
                            except ValueError:
                                pass
                    if matters:
                        break
            attempt += 1
        rest = b['N'] - len(b['traps']) - len(b['ladders']) - len(b['snakes'])
        # tempting wrong answers (for the spread and the regression tests): forgetting the snakes or the ladders
        lures = {}
        if b['snakes']:
            q = win_probability(b['N'], b['d'], b['ladders'], {}, b['traps'])
            lures['ignoresSnakes'] = f'{q.numerator}/{q.denominator}'
        if b['ladders']:
            try:
                q = win_probability(b['N'], b['d'], {}, b['snakes'], b['traps'])
                lures['ignoresLadders'] = f'{q.numerator}/{q.denominator}'
            except ValueError:
                pass
        out.append({
            'level': level, 'seed': SEED_BASE + 1000 * level + attempt, 'attempt': attempt,
            'N': b['N'], 'd': b['d'],
            'ladders': sorted([f, t] for f, t in b['ladders'].items()),
            'snakes': sorted([h, t] for h, t in b['snakes'].items()),
            'traps': sorted(b['traps']),
            'restingSquares': rest,
            'answer': f'{p.numerator}/{p.denominator}',
            'digits': [len(str(p.numerator)), len(str(p.denominator))],
            'lures': lures,
        })
        prev_digits = len(str(p.denominator))
        print(level, b['N'], b['d'], out[-1]['answer'], float(p))
    out_dir = sys.argv[sys.argv.index('--out') + 1] if '--out' in sys.argv else HERE
    with open(os.path.join(out_dir, 'snakes_cases.json'), 'w') as fh:
        json.dump(out, fh, indent=1)
        fh.write('\n')


if __name__ == '__main__':
    main()
