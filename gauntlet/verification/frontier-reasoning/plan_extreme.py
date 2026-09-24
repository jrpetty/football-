"""Exhaustive verification for reasoning.planning-extreme (10 cases).

Each minimum is found by exhaustive search (BFS / Dijkstra / DP / full enumeration of machine
sequences). plan_extreme_crosscheck.mjs re-computes every answer with independent JS code.
Run: python3 plan_extreme.py
"""
import heapq
import itertools
import math
from collections import deque

RESULTS = {}
PLANS = {}


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
            return dist[s], path[::-1], len(dist)
        for t in nbrs(s):
            if t not in dist:
                dist[t] = dist[s] + 1
                prev[t] = s
                q.append(t)
    return None, None, len(dist)


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
            return d, path[::-1], len(dist)
        for t, c in nbrs(s):
            nd = d + c
            if t not in dist or nd < dist[t]:
                dist[t] = nd
                prev[t] = s
                heapq.heappush(pq, (nd, t))
    return None, None, len(dist)


# ------------------------------------------------------------------ p01 capacitated lot sizing
LOT = dict(dem=[30, 50, 20, 70, 40, 10, 60, 30], fee=120, truck=40, truckcap=80, hold=1, cap=100)


def p01():
    P = LOT
    best = {0: (0, [])}
    for t, dem in enumerate(P["dem"]):
        nb = {}
        for inv, (c, plan) in best.items():
            for q in range(0, P["cap"] + dem - inv + 1):
                end = inv + q - dem
                if end < 0 or end > P["cap"]:
                    continue
                cost = c + (P["fee"] + P["truck"] * math.ceil(q / P["truckcap"]) if q else 0) + P["hold"] * end
                if end not in nb or cost < nb[end][0]:
                    nb[end] = (cost, plan + [q])
        best = nb
    cost, plan = min(best.values())
    # zero-inventory-ordering (Wagner-Whitin style) comparison, to document the trap
    zio = {0: 0}
    for t, dem in enumerate(P["dem"]):
        nb = {}
        for inv, c in zio.items():
            for q in ([0] if inv > 0 else range(0, P["cap"] + dem + 1)):
                end = inv + q - dem
                if 0 <= end <= P["cap"]:
                    cc = c + (P["fee"] + P["truck"] * math.ceil(q / P["truckcap"]) if q else 0) + P["hold"] * end
                    nb[end] = min(nb.get(end, 10 ** 9), cc)
        zio = nb
    RESULTS["p01"] = cost
    assert min(zio.values()) == 780
    PLANS["p01"] = f"sacks delivered in weeks 1-8 = {plan}"


# ------------------------------------------------------------------ p02 discrete jeep problem
def jeep(D, C, maxcache):
    start = (0, 0, tuple([0] * (D - 1)))

    def nbrs(s):
        pos, tank, cache = s
        out = []
        if pos == 0:
            out += [((0, tank + k, cache), k) for k in range(1, C - tank + 1)]
        elif pos < D:
            i = pos - 1
            for k in range(1, tank + 1):
                if cache[i] + k <= maxcache:
                    cc = list(cache); cc[i] += k
                    out.append(((pos, tank - k, tuple(cc)), 0))
            for k in range(1, min(cache[i], C - tank) + 1):
                cc = list(cache); cc[i] -= k
                out.append(((pos, tank + k, tuple(cc)), 0))
        if tank >= 1:
            if pos < D:
                out.append(((pos + 1, tank - 1, cache), 0))
            if pos > 0:
                out.append(((pos - 1, tank - 1, cache), 0))
        return out
    return dijkstra(start, lambda s: s[0] == D, nbrs)


def p02(D=9, C=5):
    d, path, n = jeep(D, C, 40)
    d2, _, _ = jeep(D, C, 80)   # the depot-size bound used to keep the search finite does not bind
    assert d == d2
    RESULTS["p02"] = d
    steps = []
    for a, b in zip(path, path[1:]):
        if b[0] != a[0]:
            steps.append(f"{'E' if b[0] > a[0] else 'W'}{b[0]}")
        elif a[0] == 0:
            steps.append(f"load{b[1] - a[1]}")
        else:
            delta = sum(b[2]) - sum(a[2])
            steps.append(f"{'drop' if delta > 0 else 'take'}{abs(delta)}@{a[0]}")
    PLANS["p02"] = "(loadN = load N cells at the base; Ek/Wk = drive east/west to marker k; dropN@k / takeN@k = leave / collect N cells at the depot at marker k) " + " ".join(steps)


# ------------------------------------------------------------------ p03 bridge, lantern, weight limit
BRIDGE = dict(names=["Ana", "Bo", "Cy", "Dee", "Ed", "Flo", "Gil", "Hal", "Ivy"],
              times=[5, 12, 13, 15, 17, 20, 21, 23, 24],
              weights=[81, 76, 84, 71, 65, 95, 91, 62, 51], cap=3, wmax=200)


def p03():
    B = BRIDGE
    n = len(B["times"]); full = (1 << n) - 1
    groups = []
    for k in range(1, B["cap"] + 1):
        for g in itertools.combinations(range(n), k):
            if sum(B["weights"][i] for i in g) <= B["wmax"]:
                groups.append((sum(1 << i for i in g), max(B["times"][i] for i in g)))

    def nbrs(s):
        left, side = s
        here = left if side == 0 else full ^ left
        return [((left ^ m, 1 - side), t) for m, t in groups if m & here == m]
    d, path, _ = dijkstra((full, 0), lambda s: s[0] == 0, nbrs)
    RESULTS["p03"] = d
    steps = []
    for a, b in zip(path, path[1:]):
        who = "+".join(B["names"][i] for i in range(n) if (a[0] ^ b[0]) >> i & 1)
        steps.append(("over " if a[1] == 0 else "back ") + who)
    PLANS["p03"] = "; ".join(steps)


# ------------------------------------------------------------------ p04 job shop with paint changeovers
SAW, DRILL, PAINT = 0, 1, 2
JOBS = {  # job: (colour, [(machine, hours), ...] in the required order)
    "A": ("R", [(DRILL, 2), (PAINT, 2), (SAW, 2)]),
    "B": ("K", [(PAINT, 4), (SAW, 4), (DRILL, 1)]),
    "C": ("W", [(DRILL, 3), (SAW, 1), (PAINT, 4)]),
    "D": ("W", [(SAW, 1), (PAINT, 3), (DRILL, 2)]),
    "E": ("K", [(SAW, 3), (DRILL, 2), (PAINT, 5)]),
}
CHANGE = {("W", "K"): 4, ("K", "W"): 6, ("W", "R"): 2, ("R", "W"): 5, ("K", "R"): 3, ("R", "K"): 2}


def schedule(seqs):
    names = list(JOBS)
    pos = [0, 0, 0]; mfree = [0, 0, 0]; colour = "W"
    nxt = {j: 0 for j in names}; ready = {j: 0 for j in names}
    times = []
    total = sum(len(JOBS[j][1]) for j in names); done = 0
    while done < total:
        prog = False
        for m in range(3):
            if pos[m] >= len(seqs[m]):
                continue
            j = seqs[m][pos[m]]
            ops = JOBS[j][1]
            o = nxt[j]
            if o >= len(ops) or ops[o][0] != m:
                continue
            su = CHANGE.get((colour, JOBS[j][0]), 0) if m == PAINT else 0
            start = max(ready[j], mfree[m] + su)
            end = start + ops[o][1]
            times.append((j, m, start, end))
            mfree[m] = end; ready[j] = end; nxt[j] += 1; pos[m] += 1; done += 1
            if m == PAINT:
                colour = JOBS[j][0]
            prog = True
        if not prog:
            return None, None
    return max(ready.values()), times


def p04():
    per_machine = [[j for j in JOBS for (m, _) in JOBS[j][1] if m == mm] for mm in range(3)]
    best = None
    for s0 in itertools.permutations(per_machine[0]):
        for s1 in itertools.permutations(per_machine[1]):
            for s2 in itertools.permutations(per_machine[2]):
                v, t = schedule([s0, s1, s2])
                if v is not None and (best is None or v < best[0]):
                    best = (v, (s0, s1, s2), t)
    # documented bounds: painting totals 18 h and any colour order needs >= 4 h of changeovers
    paint = sum(d for j in JOBS for (m, d) in JOBS[j][1] if m == PAINT)
    min_change = min(sum(CHANGE.get((a, b), 0) for a, b in zip(("W",) + order, order))
                     for order in itertools.permutations([JOBS[j][0] for j in JOBS]))
    assert (paint, min_change) == (18, 4)
    RESULTS["p04"] = best[0]
    mname = ["Saw", "Drill", "Paint"]
    PLANS["p04"] = "; ".join(f"{mname[m]}: " + " ".join(f"{j}[{s}-{e}]" for (j, mm, s, e) in sorted(best[2], key=lambda x: x[2]) if mm == m) for m in range(3))


# ------------------------------------------------------------------ p05 jealous couples, island, only wives row
def couples(rowers, places=3):
    n = 8  # 2i = husband i, 2i+1 = wife i
    goal = places - 1

    def ok(group):
        men = {p // 2 for p in group if p % 2 == 0}
        return all(not (p % 2 == 1 and p // 2 not in men and men) for p in group)

    def nbrs(s):
        pos, boat = s
        here = [p for p in range(n) if pos[p] == boat]
        out = []
        for k in (1, 2):
            for g in itertools.combinations(here, k):
                if not rowers.intersection(g) or not ok(g):
                    continue
                for dest in range(places):
                    if dest == boat:
                        continue
                    npos = list(pos)
                    for p in g:
                        npos[p] = dest
                    npos = tuple(npos)
                    if all(ok([p for p in range(n) if npos[p] == L]) for L in range(places)):
                        out.append((npos, dest))
        return out
    return bfs((tuple([0] * n), 0), lambda s: all(x == goal for x in s[0]), nbrs)


def p05():
    n = 8
    d, path, _ = couples({1, 3, 5, 7})
    # documented variants: everyone rows -> 16; only husbands row -> 21; no island -> impossible
    assert couples(set(range(8)))[0] == 16
    assert couples({0, 2, 4, 6})[0] == 21
    assert couples(set(range(8)), places=2)[0] is None
    RESULTS["p05"] = d
    nm = ["A", "a", "B", "b", "C", "c", "D", "d"]
    loc = ["W", "I", "E"]
    PLANS["p05"] = "(A/a = Alan/Amy, B/b = Ben/Bea, C/c = Carl/Cat, D/d = Dan/Dora; W/I/E = west bank/island/east bank) " + "; ".join(f"{'+'.join(nm[p] for p in range(n) if a[0][p] != b[0][p])} {loc[a[1]]}->{loc[b[1]]}" for a, b in zip(path, path[1:]))


# ------------------------------------------------------------------ p06 rush hour
RUSH = ["G.BBBE",
        "GDDD.E",
        "XXH.I.",
        "..HAIC",
        "F.KAIC",
        "F.KAJJ"]


def p06():
    cells = {}
    for r, row in enumerate(RUSH):
        for c, ch in enumerate(row):
            if ch != ".":
                cells.setdefault(ch, []).append((r, c))
    veh = []
    for ch in sorted(cells):
        cs = sorted(cells[ch])
        horiz = cs[0][0] == cs[-1][0]
        veh.append((ch, horiz, cs[0][0] if horiz else cs[0][1], len(cs), cs[0][1] if horiz else cs[0][0]))
    xi = [v[0] for v in veh].index("X")

    def nbrs(s):
        g = [[False] * 6 for _ in range(6)]
        for (ch, h, f, L, _), p in zip(veh, s):
            for k in range(L):
                if h:
                    g[f][p + k] = True
                else:
                    g[p + k][f] = True
        out = []
        for i, (ch, h, f, L, _) in enumerate(veh):
            for d in (-1, 1):
                p = s[i]
                while True:
                    p += d
                    cell = p if d < 0 else p + L - 1
                    if cell < 0 or cell > 5 or (g[f][cell] if h else g[cell][f]):
                        break
                    t = list(s); t[i] = p
                    out.append(tuple(t))
        return out
    start = tuple(v[4] for v in veh)
    d, path, n = bfs(start, lambda s: s[xi] == 4, nbrs)
    RESULTS["p06"] = d
    steps = []
    for a, b in zip(path, path[1:]):
        i = next(k for k in range(len(a)) if a[k] != b[k])
        ch, h = veh[i][0], veh[i][1]
        dd = b[i] - a[i]
        steps.append(f"{ch}{('R' if dd > 0 else 'L') if h else ('D' if dd > 0 else 'U')}{abs(dd)}")
    PLANS["p06"] = "(AU1 = vehicle A up 1 square, XR3 = X right 3, etc.) " + " ".join(steps)


# ------------------------------------------------------------------ p07 token swapping on a wheel
def p07():
    E = [(0, i) for i in range(1, 9)] + [(i, i % 8 + 1) for i in range(1, 9)]
    start = (0, 7, 8, 1, 2, 3, 4, 5, 6)  # token on spot 0..8

    def nbrs(s):
        for a, b in E:
            l = list(s); l[a], l[b] = l[b], l[a]
            yield tuple(l)
    d, path, _ = bfs(start, lambda s: s == tuple(range(9)), nbrs)
    RESULTS["p07"] = d
    PLANS["p07"] = "(swap(a,b) = swap the tokens on spots a and b) " + " ".join("swap(" + ",".join(str(k) for k in range(9) if a[k] != b[k]) + ")" for a, b in zip(path, path[1:]))


# ------------------------------------------------------------------ p08 clockwise 2x2 rotors on a 3x3 grid
ROT = {"TL": (0, 1, 4, 3), "TR": (1, 2, 5, 4), "BL": (3, 4, 7, 6), "BR": (4, 5, 8, 7)}  # clockwise cycles


def rotate(s, blk):
    a, b, c, d = ROT[blk]
    l = list(s)
    l[b], l[c], l[d], l[a] = s[a], s[b], s[c], s[d]
    return tuple(l)


def p08():
    start = (2, 1, 3, 4, 5, 6, 7, 8, 9)
    d, path, _ = bfs(start, lambda s: s == tuple(range(1, 10)), lambda s: [rotate(s, b) for b in ROT])
    RESULTS["p08"] = d
    names = []
    for a, b in zip(path, path[1:]):
        names.append(next(k for k in ROT if rotate(a, k) == b))
    PLANS["p08"] = "(TL/TR/BL/BR = turn the top-left/top-right/bottom-left/bottom-right block) " + " ".join(names)


# ------------------------------------------------------------------ p09 burnt pancakes
def p09():
    start = (1, -2, 3, -4, 5, -6)  # top to bottom; negative = burnt side up

    def nbrs(s):
        for k in range(1, 7):
            yield tuple(-x for x in reversed(s[:k])) + s[k:]
    d, path, _ = bfs(start, lambda s: s == (1, 2, 3, 4, 5, 6), nbrs)
    RESULTS["p09"] = d
    ks = []
    for a, b in zip(path, path[1:]):
        ks.append(next(k for k in range(1, 7) if tuple(-x for x in reversed(a[:k])) + a[k:] == b))
    PLANS["p09"] = "flip sizes (number of pancakes turned) " + ", ".join(map(str, ks))


# ------------------------------------------------------------------ p10 coloured sliding tiles
def p10():
    R, C = 3, 4
    start = "BRRR" "YYY_" "RBBB"
    goal = "RRRR" "YYY_" "BBBB"

    def nbrs(s):
        i = s.index("_"); r, c = divmod(i, C)
        for dr, dc in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            rr, cc = r + dr, c + dc
            if 0 <= rr < R and 0 <= cc < C:
                j = rr * C + cc; l = list(s); l[i], l[j] = l[j], l[i]
                yield "".join(l)
    d, path, _ = bfs(start, lambda s: s == goal, nbrs)
    RESULTS["p10"] = d
    # which tile moved into the gap, as a direction of the tile
    moves = []
    for a, b in zip(path, path[1:]):
        i, j = a.index("_"), b.index("_")
        moves.append(b[i] + "-" + {-C: "down", C: "up", -1: "right", 1: "left"}[j - i])
    PLANS["p10"] = "(colour of the tile moved - its direction) " + ", ".join(moves)


if __name__ == "__main__":
    for f in (p01, p02, p03, p04, p05, p06, p07, p08, p09, p10):
        f()
        k = f.__name__
        print(f"{k}: {RESULTS[k]}   plan: {PLANS[k]}")
