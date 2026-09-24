# Third check for c5, valid only when every reach equals R and every cost is 1: the classic greedy (take the deepest
# uncovered needed vertex, build at its R-th ancestor or the root) is optimal for distance-R covering on trees.
import sys, json
from collections import deque
def solve(parent, cost, reach, need):
    n = len(parent); R = reach[0]
    assert all(r == R for r in reach) and all(c == 1 for c in cost)
    kids = [[] for _ in range(n)]; root = -1
    for i, p in enumerate(parent):
        if p < 0: root = i
        else: kids[p].append(i)
    depth = [0] * n; order = [root]
    for v in order:
        for c in kids[v]: depth[c] = depth[v] + 1; order.append(c)
    adj = [[] for _ in range(n)]
    for i, p in enumerate(parent):
        if p >= 0: adj[i].append(p); adj[p].append(i)
    covered = [False] * n; towers = 0
    for u in sorted(range(n), key=lambda v: -depth[v]):
        if need[u] != '1' or covered[u]: continue
        a = u
        for _ in range(R):
            if parent[a] < 0: break
            a = parent[a]
        towers += 1
        dist = {a: 0}; q = deque([a])
        while q:
            x = q.popleft(); covered[x] = True
            if dist[x] == R: continue
            for y in adj[x]:
                if y not in dist: dist[y] = dist[x] + 1; q.append(y)
    return towers
if __name__ == '__main__':
    print(json.dumps([solve(*c) for c in json.load(sys.stdin)]))
