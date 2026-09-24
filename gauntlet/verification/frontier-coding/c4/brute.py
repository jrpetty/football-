# Brute-force oracle for c4 (n <= 8): enumerate every way to split the jobs into ordered truck routes, simulate each
# truck's timeline directly (leave the depot whenever, wait for free, be at each pickup by its start), and cost it.
import sys, json
from functools import lru_cache
def dist(a, b): return abs(a[0] - b[0]) + abs(a[1] - b[1])
def solve(depot, jobs, F, K):
    n = len(jobs)
    if n == 0: return 0
    best = None
    order = sorted(range(n), key=lambda j: jobs[j][4])
    # routes: list of lists of job ids; assign jobs in order of start time (any feasible route visits jobs in start order)
    def feasible_cost(route):
        t = None; pos = tuple(depot); cost = F
        for j in route:
            px, py, qx, qy, s, e = jobs[j]
            dd = dist(pos, (px, py)); cost += dd
            if t is not None and t + dd > s: return None
            t = e; pos = (qx, qy)
        return cost + dist(pos, depot)
    def rec(k, routes):
        nonlocal best
        if k == n:
            if len(routes) > K: return
            tot = 0
            for r in routes:
                c = feasible_cost(r)
                if c is None: return
                tot += c
            if best is None or tot < best: best = tot
            return
        j = order[k]
        for r in routes:
            r.append(j); rec(k + 1, routes); r.pop()
        routes.append([j]); rec(k + 1, routes); routes.pop()
    rec(0, [])
    return -1 if best is None else best
if __name__ == '__main__':
    print(json.dumps([solve(*c) for c in json.load(sys.stdin)]))
