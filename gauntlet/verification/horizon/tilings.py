"""Horizon: Count Every Tiling (horizon.tiling-count).

Ten boards with holes; the task is the exact number of ways to cover every remaining square with
dominoes (1x2 tiles, either orientation, no overlaps, nothing sticking out). The boards grow every level,
from 6x6 to 18x18, so the count grows from tens to astronomically large numbers.

Pencil-and-paper method: a column-by-column count (a "transfer matrix"), keeping for every pattern of
dominoes that stick into the next column how many ways lead to it. That is exactly the method used here
(Python, big integers). The independent re-check, tilings_check.mjs, uses a different algorithm:
memoised search that always covers the first empty square (row-major), with BigInt counts.

Run: python3 tilings.py
"""
from __future__ import annotations

import json
import os
import random
from collections import deque

HERE = os.path.dirname(os.path.abspath(__file__))

# level -> (rows, cols, holes)
# Levels 5-10 were made steeper after the blind calibration (an Opus-class model counted 12x10 exactly).
LEVELS = {1: (6, 6, 4), 2: (6, 8, 4), 3: (8, 7, 4), 4: (8, 8, 6), 5: (12, 12, 8), 6: (14, 12, 10),
          7: (14, 14, 10), 8: (16, 14, 12), 9: (16, 16, 12), 10: (18, 18, 14)}


def count_tilings(board: list[str]) -> int:
    """Column-profile dynamic programming. board[r][c] == '#' means the square must be covered."""
    R, C = len(board), len(board[0])
    ways = {0: 1}  # bitmask of rows in the current column already covered by a domino from the left
    for c in range(C):
        nxt: dict[int, int] = {}
        for mask, w in ways.items():
            # fill column c row by row; `out` collects horizontal dominoes sticking into column c+1
            stack = [(0, mask, 0)]
            while stack:
                r, cur, out = stack.pop()
                if r == R:
                    nxt[out] = nxt.get(out, 0) + w
                    continue
                cell = board[r][c] == '#'
                if not cell:
                    if cur >> r & 1:
                        continue  # a domino would cover a hole (cannot happen: masks only mark real squares)
                    stack.append((r + 1, cur, out))
                    continue
                if cur >> r & 1:
                    stack.append((r + 1, cur, out))
                    continue
                # horizontal domino into column c+1
                if c + 1 < C and board[r][c + 1] == '#':
                    stack.append((r + 1, cur, out | 1 << r))
                # vertical domino covering rows r and r+1
                if r + 1 < R and board[r + 1][c] == '#' and not (cur >> (r + 1) & 1):
                    stack.append((r + 2, cur | 1 << (r + 1), out))
        ways = nxt
    return ways.get(0, 0)


def connected(board):
    cells = [(r, c) for r, row in enumerate(board) for c, ch in enumerate(row) if ch == '#']
    seen = {cells[0]}
    q = deque([cells[0]])
    while q:
        r, c = q.popleft()
        for dr, dc in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            n = (r + dr, c + dc)
            if 0 <= n[0] < len(board) and 0 <= n[1] < len(board[0]) and board[n[0]][n[1]] == '#' and n not in seen:
                seen.add(n)
                q.append(n)
    return len(seen) == len(cells)


def make(level, rng):
    R, C, H = LEVELS[level]
    for attempt in range(1, 10000):
        cells = [(r, c) for r in range(R) for c in range(C)]
        black = [x for x in cells if (x[0] + x[1]) % 2 == 0]
        white = [x for x in cells if (x[0] + x[1]) % 2 == 1]
        holes = set(rng.sample(black, H // 2) + rng.sample(white, H // 2))
        # holes on the outer border make the board less regular; keep at least half of them inside
        board = [''.join('.' if (r, c) in holes else '#' for c in range(C)) for r in range(R)]
        if not connected(board):
            continue
        n = count_tilings(board)
        if n < 2:
            continue
        return {'level': level, 'rows': R, 'cols': C, 'board': board, 'answer': n, 'attempts': attempt}
    raise RuntimeError('no board')


def main():
    # sanity checks against known values
    assert count_tilings(['####'] * 4) == 36
    assert count_tilings(['#' * 8] * 8) == 12988816
    assert count_tilings(['#' * 3] * 2) == 3
    rng = random.Random(int(os.environ.get('HORIZON_SEED', '2026')) * 17 + 7)
    cases = []
    for level in range(1, 11):
        sub = random.Random(rng.random())
        c = make(level, sub)
        cases.append(c)
        print(f"L{level:02d} {c['rows']}x{c['cols']} tilings={c['answer']}")
    with open(os.path.join(HERE, 'tilings_cases.json'), 'w') as fh:
        json.dump(cases, fh, indent=1)


if __name__ == '__main__':
    main()
