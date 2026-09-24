# Second independent oracle for c4 (large inputs): a 2n x 2n assignment problem solved with the O(N^3) Hungarian
# algorithm (numpy-vectorised, exact int64).
#   rows: job i as predecessor (n) | K' "truck start" rows | n-K' filler rows      (K' = min(K, n))
#   cols: job j as successor (n)   | n "no successor" columns
#   real row i -> real col j: w_ij if j can follow i, else FORBID;  real row -> dummy col: 0
#   start row -> any col: 0;  filler row -> real col: FORBID, -> dummy col: 0
# Filler rows can only absorb dummy columns, so at most K' jobs lack a predecessor, i.e. at most K' trucks.
import sys, json
import numpy as np
FORBID = 10**13
def dist(a, b): return abs(a[0] - b[0]) + abs(a[1] - b[1])
def hungarian(a):
    n, m = a.shape
    INF = np.int64(4 * 10**18)
    u = np.zeros(n + 1, dtype=np.int64); v = np.zeros(m + 1, dtype=np.int64)
    p = np.zeros(m + 1, dtype=np.int64); way = np.zeros(m + 1, dtype=np.int64)
    for i in range(1, n + 1):
        p[0] = i; j0 = 0
        minv = np.full(m + 1, INF, dtype=np.int64); used = np.zeros(m + 1, dtype=bool)
        while True:
            used[j0] = True
            i0 = p[j0]
            cur = a[i0 - 1, :] - u[i0] - v[1:]
            free = ~used[1:]
            upd = free & (cur < minv[1:])
            mv = minv[1:]; wy = way[1:]
            mv[upd] = cur[upd]; wy[upd] = j0
            tmp = np.where(free, mv, INF)
            j1 = int(np.argmin(tmp)) + 1; delta = tmp[j1 - 1]
            uj = np.nonzero(used)[0]
            u[p[uj]] += delta; v[uj] -= delta
            mv[free] -= delta
            j0 = j1
            if p[j0] == 0: break
        while True:
            j1 = way[j0]; p[j0] = p[j1]; j0 = j1
            if j0 == 0: break
    return int(-v[0])
def solve(depot, jobs, F, K):
    n = len(jobs)
    if n == 0: return 0
    D = tuple(depot)
    base = sum(F + dist(D, (j[0], j[1])) + dist((j[2], j[3]), D) for j in jobs)
    Kp = min(K, n); N = 2 * n
    a = np.zeros((N, N), dtype=np.int64)
    for i in range(n):
        qi = (jobs[i][2], jobs[i][3]); ei = jobs[i][5]
        for j in range(n):
            pj = (jobs[j][0], jobs[j][1])
            dd = dist(qi, pj)
            a[i, j] = dd - dist(qi, D) - dist(D, pj) - F if ei + dd <= jobs[j][4] else FORBID
    a[n + Kp:, :n] = FORBID
    c = hungarian(a)
    return -1 if c >= FORBID // 2 else base + c
if __name__ == '__main__':
    print(json.dumps([solve(*c) for c in json.load(sys.stdin)]))
