"""Generator for reasoning.plans-ladder: four-peg Tower of Hanoi from a scattered position, ten levels.

One rule set on every level (four pegs A-D, move the top disk of any peg onto any other peg, never onto a smaller
disk, get every disk onto peg D). Only the number of disks grows, and with it the state space (4^n positions, from
4^4 = 256 to 4^13 = 67,108,864) and the proven minimum (6 moves up to 70).

Proof of each minimum: a breadth-first search over EVERY position (numpy, vectorised, from the goal outwards) gives
the exact distance of every position to the goal; the start of a level is drawn at random (fixed seed) among the
positions at the wanted distance. The searcher first re-proves the known optimal four-peg perfect-tower numbers
(Frame-Stewart, proven optimal by Bousch 2014): 1, 3, 5, 9, 13, 17, 25, 33, 41, 49, 65, 81, 97 for 1..13 disks.
verify.mjs re-proves every minimum independently (forward BFS in JavaScript from the start parsed out of the prompt).

    python3 hanoi.py            # writes cases.json (about 3 minutes, ~1.5 GB RAM at 13 disks)
"""
from __future__ import annotations

import json
import os
import random
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
PEGS = 'ABCD'
GOAL_PEG = 3
FRAME_STEWART = [0, 1, 3, 5, 9, 13, 17, 25, 33, 41, 49, 65, 81, 97]

# level, disks, proven minimum wanted, seed
PLAN = [
    (1, 4, 6, 4101),
    (2, 5, 10, 4102),
    (3, 6, 14, 4103),
    (4, 7, 19, 4104),
    (5, 8, 25, 4105),
    (6, 9, 32, 4106),
    (7, 10, 40, 4107),
    (8, 11, 48, 4108),
    (9, 12, 58, 4109),
    (10, 13, 70, 4110),
]

MOVES = [(a, b) for a in range(4) for b in range(4) if a != b]


def tops(codes: np.ndarray, n: int) -> np.ndarray:
    """Smallest disk index on each peg (n = empty) for every state code; disk d sits on peg (code >> 2d) & 3."""
    t = np.full((codes.shape[0], 4), n, dtype=np.int64)
    for d in range(n - 1, -1, -1):
        peg = (codes >> (2 * d)) & 3
        t[np.arange(codes.shape[0]), peg] = d
    return t


def neighbours(codes: np.ndarray, n: int) -> np.ndarray:
    t = tops(codes, n)
    out = []
    for a, b in MOVES:
        ok = t[:, a] < t[:, b]  # peg a has a top disk and it is smaller than peg b's top (or b is empty)
        if ok.any():
            c = codes[ok]
            out.append(c + (b - a) * (np.int64(1) << (2 * t[ok, a])))
    return np.concatenate(out) if out else np.empty(0, dtype=np.int64)


def distances(n: int, goal: int) -> np.ndarray:
    """Exact distance (moves) of every one of the 4^n positions to the goal position (BFS; uint8, 255 = unreached)."""
    size = 4 ** n
    dist = np.full(size, 255, dtype=np.uint8)
    dist[goal] = 0
    frontier = np.array([goal], dtype=np.int64)
    d = 0
    while frontier.size:
        nxt = []
        for i in range(0, frontier.size, 1 << 20):
            nb = neighbours(frontier[i:i + (1 << 20)], n)
            nb = nb[dist[nb] == 255]
            nb = np.unique(nb)
            dist[nb] = d + 1
            nxt.append(nb)
        frontier = np.unique(np.concatenate(nxt)) if nxt else np.empty(0, dtype=np.int64)
        d += 1
        assert d < 255
    assert (dist != 255).all(), 'every position is reachable'
    return dist


def all_on(peg: int, n: int) -> int:
    return sum(peg << (2 * d) for d in range(n))


def pegs_of(code: int, n: int) -> list[list[int]]:
    """Disks (1-based sizes) on each peg, bottom to top."""
    p = [[] for _ in range(4)]
    for d in range(n - 1, -1, -1):
        p[(code >> (2 * d)) & 3].append(d + 1)
    return p


def plan_from(code: int, n: int, dist: np.ndarray) -> list[tuple[int, int, int]]:
    """One optimal plan by steepest descent on the exact distance table: (disk, from peg, to peg) per move."""
    out = []
    while dist[code] != 0:
        t = tops(np.array([code], dtype=np.int64), n)[0]
        for a, b in MOVES:
            if t[a] < t[b]:
                nc = code + (b - a) * (1 << (2 * int(t[a])))
                if dist[nc] == dist[code] - 1:
                    out.append((int(t[a]) + 1, a, b))
                    code = nc
                    break
        else:
            raise AssertionError('no descending move')
    return out


def self_test():
    for n in range(1, 11):
        dist = distances(n, all_on(GOAL_PEG, n))
        assert int(dist[all_on(0, n)]) == FRAME_STEWART[n], (n, int(dist[all_on(0, n)]))
    print('self-test: four-peg perfect-tower minima for 1..10 disks match Frame-Stewart (proven optimal)', flush=True)


def build(path=os.path.join(HERE, "cases.json")):
    self_test()
    out = []
    for level, n, want, seed in PLAN:
        goal = all_on(GOAL_PEG, n)
        dist = distances(n, goal)
        hist = np.bincount(dist, minlength=int(dist.max()) + 1)
        ecc = int(dist.max())
        # perfect-tower check on the big boards too (11..13 disks), from the same table
        assert int(dist[all_on(0, n)]) == FRAME_STEWART[n]
        cand = np.nonzero(dist == want)[0]
        rng = random.Random(seed)
        chosen = None
        for _ in range(5000):
            code = int(cand[rng.randrange(cand.size)])
            p = pegs_of(code, n)
            # the largest disk is not already home, and at least three pegs hold disks (a genuinely scattered start)
            if n in p[GOAL_PEG] or sum(1 for x in p if x) < 3:
                continue
            chosen = code
            break
        assert chosen is not None, (level, n, want)
        plan = plan_from(chosen, n, dist)
        assert len(plan) == want
        out.append(dict(level=level, disks=n, states=4 ** n, minimum=want, seed=seed, start=pegs_of(chosen, n),
                        code=chosen, plan=[[d, PEGS[a], PEGS[b]] for d, a, b in plan], eccentricity=ecc,
                        at_distance=int(hist[want]), perfect=FRAME_STEWART[n]))
        print(f'L{level:02d}: {n} disks, {4 ** n:,} positions, minimum {want} ({int(hist[want]):,} positions at that distance,'
              f' farthest {ecc}); start {pegs_of(chosen, n)}', flush=True)
        del dist
    with open(path, "w") as fh:
        json.dump(out, fh, indent=1)
        fh.write('\n')


if __name__ == '__main__':
    build(sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else os.path.join(HERE, "cases.json"))
