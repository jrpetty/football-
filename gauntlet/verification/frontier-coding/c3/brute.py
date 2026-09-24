# Brute-force oracle for c3: enumerate every substring (small inputs only).
import sys, json
def solve(parts):
    s = ''.join(t * k for t, k in parts)
    n = len(s)
    occ = {}
    for i in range(n):
        for j in range(i + 1, n + 1):
            t = s[i:j]
            if t == t[::-1]:
                occ.setdefault(t, []).append(i)
    good = [t for t, st in occ.items() if max(st) - min(st) >= len(t)]
    return [len(good), max((len(t) for t in good), default=0), sum(len(occ[t]) for t in good)]
if __name__ == '__main__':
    print(json.dumps([solve(*c) for c in json.load(sys.stdin)]))
