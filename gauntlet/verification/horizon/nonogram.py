"""Horizon: The Picture Logic Ladder (horizon.nonogram-ladder).

Ten nonograms (paint-by-numbers) from 8x8 to 50x50. Every puzzle has exactly ONE solution. That is proven
by Google OR-Tools CP-SAT here (each row and column is an automaton constraint; after the first solution
is found it is forbidden and the solver must prove there is no second one), and independently by
nonogram_check.mjs (JavaScript line-solving with full backtracking, counting solutions up to two).

Random pictures are used rather than drawings, so there is nothing to recognise and guess: the grid has
to be deduced cell by cell. Density is chosen so the unique-solution rate is high.

Run: python3 nonogram.py
"""
from __future__ import annotations

import json
import os
import random
import sys

from ortools.sat.python import cp_model

HERE = os.path.dirname(os.path.abspath(__file__))

# level -> (rows, cols, fill density)
# level -> (rows, cols, fill density, share of cells that pure row/column logic must LEAVE UNDECIDED)
# From level 3 on, the puzzle cannot be finished by looking at one line at a time: after every line has been
# pushed as far as it goes, at least that share of the grid is still open and needs case analysis
# (try a cell, follow the consequences to a contradiction), which is the hard part for a human too.
# Levels 4-10 were made steeper after the blind calibration (an Opus-class model solved 20x20 with a quarter of
# the grid left open by line logic): bigger grids AND more case analysis per cell from level 4 on.
LEVELS = {1: (8, 8, 0.55, 0.0), 2: (10, 10, 0.55, 0.0), 3: (12, 12, 0.52, 0.10), 4: (20, 20, 0.52, 0.3),
          5: (25, 25, 0.52, 0.4), 6: (30, 30, 0.52, 0.45), 7: (35, 35, 0.52, 0.5), 8: (40, 40, 0.52, 0.55),
          9: (45, 45, 0.52, 0.58), 10: (50, 50, 0.52, 0.6)}


def clues(line: list[int]) -> list[int]:
    out, run = [], 0
    for v in line:
        if v:
            run += 1
        elif run:
            out.append(run)
            run = 0
    if run:
        out.append(run)
    return out


def automaton(clue: list[int]):
    """DFA over {0,1} accepting exactly the lines with this clue. Returns (transitions, finals)."""
    p = '0' + '0'.join('1' * k for k in clue) + ('0' if clue else '')
    if not clue:
        return [(0, 0, 0)], [0]
    trans = []
    for i, ch in enumerate(p):
        nxt = p[i + 1] if i + 1 < len(p) else None
        if ch == '0':
            trans.append((i, 0, i))
        elif nxt == '0':
            trans.append((i, 0, i + 1))
        if nxt == '1':
            trans.append((i, 1, i + 1))
    return trans, [len(p) - 1, len(p) - 2]


def count_solutions(rows: list[list[int]], cols: list[list[int]], cap: int = 2):
    R, C = len(rows), len(cols)
    m = cp_model.CpModel()
    x = [[m.NewBoolVar(f'x{r}_{c}') for c in range(C)] for r in range(R)]
    for r in range(R):
        t, f = automaton(rows[r])
        m.AddAutomaton([x[r][c] for c in range(C)], 0, f, t)
    for c in range(C):
        t, f = automaton(cols[c])
        m.AddAutomaton([x[r][c] for r in range(R)], 0, f, t)
    found = []
    while len(found) < cap:
        s = cp_model.CpSolver()
        s.parameters.num_workers = 8
        s.parameters.max_time_in_seconds = 300
        st = s.Solve(m)
        if st not in (cp_model.OPTIMAL, cp_model.FEASIBLE):
            assert st == cp_model.INFEASIBLE, 'solver gave up'
            break
        g = [[int(s.Value(x[r][c])) for c in range(C)] for r in range(R)]
        found.append(g)
        m.AddBoolOr([x[r][c].Not() if g[r][c] else x[r][c] for r in range(R) for c in range(C)])
    return found


def line_fixpoint(rows, cols):
    """Pure line logic: repeatedly settle every cell that is the same in all arrangements of its row or column.
    Returns the number of cells still undecided at the fixpoint."""
    R, C = len(rows), len(cols)
    g = [[-1] * C for _ in range(R)]

    def settle(clue, cells):
        n, k = len(cells), len(clue)
        from functools import lru_cache

        @lru_cache(maxsize=None)
        def fits(i, j):
            if j == k:
                return all(cells[x] != 1 for x in range(i, n))
            if i < n and cells[i] != 1 and fits(i + 1, j):
                return True
            L = clue[j]
            if i + L <= n and all(cells[x] != 0 for x in range(i, i + L)) and not (i + L < n and cells[i + L] == 1):
                return fits(min(n, i + L + 1), j + 1)
            return False
        can1, can0 = [False] * n, [False] * n
        seen = set()

        def walk(i, j):
            if (i, j) in seen:
                return
            seen.add((i, j))
            if j == k:
                for x in range(i, n):
                    can0[x] = True
                return
            if i < n and cells[i] != 1 and fits(i + 1, j):
                can0[i] = True
                walk(i + 1, j)
            L = clue[j]
            if i + L <= n and all(cells[x] != 0 for x in range(i, i + L)) and not (i + L < n and cells[i + L] == 1) and fits(min(n, i + L + 1), j + 1):
                for x in range(i, i + L):
                    can1[x] = True
                if i + L < n:
                    can0[i + L] = True
                walk(min(n, i + L + 1), j + 1)
        walk(0, 0)
        return [v if v != -1 else (1 if not can0[x] else 0 if not can1[x] else -1) for x, v in enumerate(cells)]

    changed = True
    while changed:
        changed = False
        for r in range(R):
            new = settle(tuple(rows[r]), tuple(g[r]))
            if new != g[r]:
                g[r] = new
                changed = True
        for c in range(C):
            col = tuple(g[r][c] for r in range(R))
            new = settle(tuple(cols[c]), col)
            if list(new) != list(col):
                for r in range(R):
                    g[r][c] = new[r]
                changed = True
    return sum(v == -1 for row in g for v in row)


def make(level: int, rng: random.Random):
    R, C, dens, open_share = LEVELS[level]
    for attempt in range(1, 5000):
        grid = [[1 if rng.random() < dens else 0 for _ in range(C)] for _ in range(R)]
        if any(sum(row) == 0 for row in grid) or any(sum(grid[r][c] for r in range(R)) == 0 for c in range(C)):
            continue
        rows = [clues(row) for row in grid]
        cols = [clues([grid[r][c] for r in range(R)]) for c in range(C)]
        undecided = line_fixpoint(rows, cols)
        # a band, so the amount of case analysis grows with the level (and with the grid)
        if not (open_share * R * C <= undecided <= (open_share + (0.12 if open_share else 0)) * R * C):
            continue
        sols = count_solutions(rows, cols)
        if len(sols) == 1:
            assert sols[0] == grid
            return {'level': level, 'rows': R, 'cols': C, 'rowClues': rows, 'colClues': cols,
                    'solution': [''.join('#' if v else '.' for v in row) for row in grid], 'attempts': attempt,
                    'undecidedByLineLogic': undecided}
    raise RuntimeError('no unique nonogram')


def main():
    rng = random.Random(int(os.environ.get('HORIZON_SEED', '2026')) * 13 + 5)
    cases = []
    for level in range(1, 11):
        sub = random.Random(rng.random())
        c = make(level, sub)
        cases.append(c)
        print(f"L{level:02d} {c['rows']}x{c['cols']} unique after {c['attempts']} random grids; {c['undecidedByLineLogic']} cells left open by line logic", flush=True)
    with open(os.path.join(HERE, 'nonogram_cases.json'), 'w') as fh:
        json.dump(cases, fh, indent=1)


if __name__ == '__main__':
    main()
