# Second independent oracle for c5 (any size): a different DP. Label every vertex with its coverage slack
# L(v) = max over towers t of reach[t] - dist(t, v), clamped below at -1. A labelling is realisable iff neighbouring labels
# differ by at most 1, a tower at v has reach[v] <= L(v), and every L(v) >= 0 is "justified": a tower at v with reach
# exactly L(v), or a neighbour labelled L(v) + 1. Needed vertices need L >= 0. DP over (label, justified-from-below).
import sys, json
def solve(parent, cost, reach, need):
    n = len(parent); RM = max(reach)
    kids = [[] for _ in range(n)]; root = -1
    for i, p in enumerate(parent):
        if p < 0: root = i
        else: kids[p].append(i)
    order = [root]
    for v in order: order.extend(kids[v])
    INF = float('inf')
    H = list(range(-1, RM + 1))
    dp = [None] * n   # dp[v][(L, j)] ; j=1 justified inside T(v) (or L=-1), j=0 must be justified by parent label L+1
    for v in reversed(order):
        res = {}
        for L in H:
            for tower in (0, 1):
                if tower and reach[v] > L: continue
                if need[v] == '1' and L < 0: continue
                base = cost[v] if tower else 0
                just0 = (L == -1) or (tower and reach[v] == L)
                # states over children: best[flag]
                best = {just0: base}
                if not just0: best[True] = INF
                best.setdefault(True, INF); best.setdefault(False, INF)
                for c in kids[v]:
                    ch = dp[c]
                    opt_plain = INF; opt_just = INF
                    for (Lc, jc), val in ch.items():
                        if abs(Lc - L) > 1: continue
                        if jc == 0 and L != Lc + 1: continue
                        if Lc == L + 1 and L >= 0: opt_just = min(opt_just, val)
                        else: opt_plain = min(opt_plain, val)
                    nb = {False: INF, True: INF}
                    for f, val in best.items():
                        if val == INF: continue
                        nb[f] = min(nb[f], val + opt_plain)
                        nb[True] = min(nb[True], val + opt_just)
                        nb[f] = min(nb[f], val + opt_just)
                    best = nb
                for f, val in best.items():
                    if val == INF: continue
                    if f: key = (L, 1)
                    else:
                        if L + 1 > RM: continue
                        key = (L, 0)
                    if val < res.get(key, INF): res[key] = val
        dp[v] = res
        for c in kids[v]: dp[c] = None
    return min(val for (L, j), val in dp[root].items() if j == 1)
if __name__ == '__main__':
    print(json.dumps([solve(*c) for c in json.load(sys.stdin)]))
