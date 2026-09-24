# Brute-force oracle for c5 (n <= 15): try every set of towers.
import sys, json
from collections import deque
def solve(parent, cost, reach, need):
    n = len(parent)
    adj = [[] for _ in range(n)]
    for i, p in enumerate(parent):
        if p >= 0: adj[i].append(p); adj[p].append(i)
    cover = []
    for t in range(n):
        dist = [-1] * n; dist[t] = 0; q = deque([t]); m = 0
        while q:
            u = q.popleft()
            if dist[u] <= reach[t]: m |= 1 << u
            for w in adj[u]:
                if dist[w] < 0: dist[w] = dist[u] + 1; q.append(w)
        cover.append(m)
    needm = sum(1 << i for i in range(n) if need[i] == '1')
    best = None
    for s in range(1 << n):
        m = 0; c = 0
        for t in range(n):
            if s >> t & 1: m |= cover[t]; c += cost[t]
        if m & needm == needm and (best is None or c < best): best = c
    return best
if __name__ == '__main__':
    print(json.dumps([solve(*c) for c in json.load(sys.stdin)]))
