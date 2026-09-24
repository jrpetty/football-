"""Exhaustive / BFS verification for reasoning.planning cases."""
import itertools, heapq
from collections import deque


def bfs(start, goal_fn, nbrs):
    dist = {start: 0}
    prev = {start: None}
    q = deque([start])
    while q:
        s = q.popleft()
        if goal_fn(s):
            path = []
            x = s
            while x is not None:
                path.append(x)
                x = prev[x]
            return dist[s], path[::-1]
        for t in nbrs(s):
            if t not in dist:
                dist[t] = dist[s] + 1
                prev[t] = s
                q.append(t)
    return None, None


def dijkstra(start, goal_fn, nbrs):
    dist = {start: 0}
    prev = {start: None}
    pq = [(0, start)]
    while pq:
        d, s = heapq.heappop(pq)
        if d > dist[s]:
            continue
        if goal_fn(s):
            path = []
            x = s
            while x is not None:
                path.append(x)
                x = prev[x]
            return d, path[::-1]
        for t, c in nbrs(s):
            nd = d + c
            if t not in dist or nd < dist[t]:
                dist[t] = nd
                prev[t] = s
                heapq.heappush(pq, (nd, t))
    return None, None


# ------------------------------------------------------------------ jugs
def jugs(caps, target, start=None, target_in=None):
    start = start or tuple(0 for _ in caps)
    def nb(s):
        out = []
        for i in range(len(caps)):
            if s[i] < caps[i]:
                t = list(s); t[i] = caps[i]; out.append(tuple(t))
            if s[i] > 0:
                t = list(s); t[i] = 0; out.append(tuple(t))
            for j in range(len(caps)):
                if i != j and s[i] > 0 and s[j] < caps[j]:
                    amt = min(s[i], caps[j] - s[j])
                    t = list(s); t[i] -= amt; t[j] += amt; out.append(tuple(t))
        return out
    if target_in is None:
        g = lambda s: target in s
    else:
        g = lambda s: s[target_in] == target
    return bfs(start, g, nb)


# ------------------------------------------------------------------ bridge & torch
def bridge(times, cap):
    n = len(times)
    full = frozenset(range(n))
    start = (full, 0)  # (people on start side, torch side 0=start)
    def nb(s):
        left, side = s
        out = []
        here = left if side == 0 else full - left
        for k in range(1, cap + 1):
            for grp in itertools.combinations(sorted(here), k):
                g = frozenset(grp)
                cost = max(times[i] for i in grp)
                nl = left - g if side == 0 else left | g
                out.append(((nl, 1 - side), cost))
        return out
    return dijkstra(start, lambda s: len(s[0]) == 0, nb)


# ------------------------------------------------------------------ sliding puzzles
def sliding(start, goal, rows, cols):
    def nb(s):
        z = s.index(0)
        r, c = divmod(z, cols)
        out = []
        for dr, dc in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            rr, cc = r + dr, c + dc
            if 0 <= rr < rows and 0 <= cc < cols:
                t = list(s)
                j = rr * cols + cc
                t[z], t[j] = t[j], t[z]
                out.append(tuple(t))
        return out
    return bfs(tuple(start), lambda s: s == tuple(goal), nb)


# ------------------------------------------------------------------ toggles
def toggles(n_cells, masks, start, goal):
    """Each press XORs a mask. BFS over bit states."""
    def nb(s):
        return [s ^ m for m in masks]
    return bfs(start, lambda s: s == goal, nb)


# ------------------------------------------------------------------ hanoi
def hanoi(n, start_pegs, goal_pegs, allowed=None):
    """start_pegs: tuple peg index for disk 1..n (disk 1 smallest). allowed: set of (from,to)."""
    def nb(s):
        out = []
        tops = {}
        for d in range(n):  # d=0 smallest
            p = s[d]
            if p not in tops:
                tops[p] = d
        for p, d in tops.items():
            for q in range(3):
                if q == p:
                    continue
                if allowed is not None and (p, q) not in allowed:
                    continue
                if q in tops and tops[q] < d:
                    continue
                t = list(s); t[d] = q; out.append(tuple(t))
        return out
    return bfs(tuple(start_pegs), lambda s: s == tuple(goal_pegs), nb)


# ------------------------------------------------------------------ pancakes
def pancakes(stack):
    target = tuple(sorted(stack))
    def nb(s):
        return [tuple(reversed(s[:k])) + s[k:] for k in range(2, len(s) + 1)]
    return bfs(tuple(stack), lambda s: s == target, nb)


# ------------------------------------------------------------------ maze with keys
def maze(grid):
    rows = grid
    R, C = len(rows), len(rows[0])
    for r in range(R):
        for c in range(C):
            if rows[r][c] == 'S':
                start = (r, c, frozenset())
    def nb(s):
        r, c, keys = s
        out = []
        for dr, dc in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            rr, cc = r + dr, c + dc
            if not (0 <= rr < R and 0 <= cc < C):
                continue
            ch = rows[rr][cc]
            if ch == '#':
                continue
            if ch.isupper() and ch not in 'SE' and ch.lower() not in keys:
                continue
            nk = keys | {ch} if ch.islower() else keys
            out.append((rr, cc, frozenset(nk)))
        return out
    return bfs(start, lambda s: rows[s[0]][s[1]] == 'E', nb)
