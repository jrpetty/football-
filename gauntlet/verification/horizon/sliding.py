"""Horizon: The Sliding Ladder (horizon.sliding-ladder).

Ten sliding-tile puzzles (3x3, 3x4 and 4x4 boards) with a proven minimum number of moves that grows every
level: 8, 12, 16, 20, 24, 28 (3x3), 32, 38 (3x4), 44, 50 (4x4). The model must give a complete plan (the
tile numbers in the order they slide). The scorer replays the plan: an optimal plan scores full marks, any
other plan that really solves the puzzle scores a little.

Minimums: 3x3 by breadth-first search over all 181,440 reachable positions; larger boards by IDA* with the
Manhattan-distance + linear-conflict heuristic (admissible, so the first plan found is optimal). The
independent re-check is sliding_check.mjs (JavaScript, a different search: bidirectional BFS for 3x3,
IDA* with a plain Manhattan heuristic and its own move generator for the larger boards).

Run: python3 sliding.py
"""
from __future__ import annotations

import json
import os
import random
import sys
import time
from collections import deque

HERE = os.path.dirname(os.path.abspath(__file__))

# level -> (rows, cols, optimal length)
LEVELS = {1: (3, 3, 8), 2: (3, 3, 12), 3: (3, 3, 16), 4: (3, 3, 20), 5: (3, 3, 24), 6: (3, 3, 28),
          7: (3, 4, 32), 8: (3, 4, 38), 9: (4, 4, 44), 10: (4, 4, 50)}


def goal(r: int, c: int) -> tuple:
    return tuple(list(range(1, r * c)) + [0])


def neighbours(state: tuple, r: int, c: int):
    z = state.index(0)
    zr, zc = divmod(z, c)
    for dr, dc in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        nr, nc = zr + dr, zc + dc
        if 0 <= nr < r and 0 <= nc < c:
            t = nr * c + nc
            s = list(state)
            s[z], s[t] = s[t], 0
            yield state[t], tuple(s)


def bfs_all_3x3():
    g = goal(3, 3)
    dist = {g: 0}
    q = deque([g])
    while q:
        s = q.popleft()
        for _tile, t in neighbours(s, 3, 3):
            if t not in dist:
                dist[t] = dist[s] + 1
                q.append(t)
    return dist


def heuristic(state, r, c):
    """Manhattan distance plus linear conflicts (each conflicting pair in a row/column costs 2 extra moves)."""
    h = 0
    for i, v in enumerate(state):
        if v:
            gr, gc = divmod(v - 1, c)
            cr, cc = divmod(i, c)
            h += abs(gr - cr) + abs(gc - cc)
    # row conflicts
    for row in range(r):
        tiles = [state[row * c + k] for k in range(c)]
        own = [(k, (v - 1) % c) for k, v in enumerate(tiles) if v and (v - 1) // c == row]
        h += 2 * _conflicts([g for _k, g in own])
    for col in range(c):
        tiles = [state[k * c + col] for k in range(r)]
        own = [(k, (v - 1) // c) for k, v in enumerate(tiles) if v and (v - 1) % c == col]
        h += 2 * _conflicts([g for _k, g in own])
    return h


def _conflicts(goals: list[int]) -> int:
    """Minimum number of tiles to remove so the rest are in increasing goal order (len - LIS)."""
    if len(goals) < 2:
        return 0
    lis = []
    import bisect
    for g in goals:
        i = bisect.bisect_left(lis, g)
        if i == len(lis):
            lis.append(g)
        else:
            lis[i] = g
    return len(goals) - len(lis)


def ida_star(start, r, c, limit=None):
    g = goal(r, c)
    bound = heuristic(start, r, c)
    path = [start]
    moves: list[int] = []

    def search(gcost, bound):
        s = path[-1]
        f = gcost + heuristic(s, r, c)
        if f > bound:
            return f
        if s == g:
            return True
        best = 10 ** 9
        for tile, t in neighbours(s, r, c):
            if len(path) > 1 and t == path[-2]:
                continue
            path.append(t)
            moves.append(tile)
            res = search(gcost + 1, bound)
            if res is True:
                return True
            best = min(best, res)
            path.pop()
            moves.pop()
        return best

    sys.setrecursionlimit(10000)
    while True:
        res = search(0, bound)
        if res is True:
            return moves[:]
        bound = res
        if limit and bound > limit:
            return None


def solvable_scramble(r, c, walk, rng):
    s = goal(r, c)
    prev = None
    for _ in range(walk):
        opts = [(tile, t) for tile, t in neighbours(s, r, c) if t != prev]
        tile, t = rng.choice(opts)
        prev, s = s, t
    return s


def apply(state, r, c, plan):
    s = state
    for tile in plan:
        nxt = dict(neighbours(s, r, c))
        if tile not in nxt:
            raise ValueError(f'illegal move {tile}')
        s = nxt[tile]
    return s


def make(level, rng, dist3):
    r, c, target = LEVELS[level]
    if (r, c) == (3, 3):
        pool = sorted(s for s, d in dist3.items() if d == target)
        start = rng.choice(pool)
        plan = ida_star(start, r, c)
        assert len(plan) == target == dist3[start]
    else:
        tries = 0
        while True:
            tries += 1
            start = solvable_scramble(r, c, rng.randint(target, target * 4), rng)
            if heuristic(start, r, c) < target - 12 or heuristic(start, r, c) > target:
                continue
            t0 = time.time()
            plan = ida_star(start, r, c, limit=target)
            if plan is not None and len(plan) == target:
                print(f'  L{level}: found after {tries} scrambles, IDA* {time.time() - t0:.1f}s', flush=True)
                break
    assert apply(start, r, c, plan) == goal(r, c)
    return {'level': level, 'rows': r, 'cols': c, 'start': list(start), 'optimal': len(plan), 'plan': plan}


def main():
    rng = random.Random(int(os.environ.get('HORIZON_SEED', '2026')) * 11 + 3)
    dist3 = bfs_all_3x3()
    only = [int(x) for x in sys.argv[1:]] or list(range(1, 11))
    path = os.path.join(HERE, 'sliding_cases.json')
    cases = {c['level']: c for c in json.load(open(path))} if os.path.exists(path) else {}
    for level in range(1, 11):
        # keep the random stream identical whichever levels are rebuilt
        sub = random.Random(rng.random())
        if level in only:
            cases[level] = make(level, sub, dist3)
            print(f"L{level:02d} {cases[level]['rows']}x{cases[level]['cols']} optimal={cases[level]['optimal']}", flush=True)
            with open(path, 'w') as fh:
                json.dump([cases[k] for k in sorted(cases)], fh, indent=1)


if __name__ == '__main__':
    main()
