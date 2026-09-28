"""Horizon: The Picture Logic Ladder (horizon.nonogram-ladder).

Ten nonograms (paint-by-numbers) from 5x5 to 25x25. Every puzzle has exactly ONE solution. That is proven
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
LEVELS = {1: (5, 5, 0.6), 2: (6, 6, 0.6), 3: (8, 8, 0.6), 4: (10, 10, 0.6), 5: (12, 12, 0.6),
          6: (14, 14, 0.6), 7: (16, 16, 0.58), 8: (18, 18, 0.58), 9: (20, 20, 0.58), 10: (25, 25, 0.58)}


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


def make(level: int, rng: random.Random):
    R, C, dens = LEVELS[level]
    for attempt in range(1, 5000):
        grid = [[1 if rng.random() < dens else 0 for _ in range(C)] for _ in range(R)]
        if any(sum(row) == 0 for row in grid) or any(sum(grid[r][c] for r in range(R)) == 0 for c in range(C)):
            continue
        rows = [clues(row) for row in grid]
        cols = [clues([grid[r][c] for r in range(R)]) for c in range(C)]
        sols = count_solutions(rows, cols)
        if len(sols) == 1:
            assert sols[0] == grid
            return {'level': level, 'rows': R, 'cols': C, 'rowClues': rows, 'colClues': cols,
                    'solution': [''.join('#' if v else '.' for v in row) for row in grid], 'attempts': attempt}
    raise RuntimeError('no unique nonogram')


def main():
    rng = random.Random(int(os.environ.get('HORIZON_SEED', '2026')) * 13 + 5)
    cases = []
    for level in range(1, 11):
        sub = random.Random(rng.random())
        c = make(level, sub)
        cases.append(c)
        print(f"L{level:02d} {c['rows']}x{c['cols']} unique after {c['attempts']} random grids", flush=True)
    with open(os.path.join(HERE, 'nonogram_cases.json'), 'w') as fh:
        json.dump(cases, fh, indent=1)


if __name__ == '__main__':
    main()
