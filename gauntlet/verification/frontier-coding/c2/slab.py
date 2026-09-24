# Second independent oracle for c2 (usable at any coordinate size): generic exact vertical-slab integration with Fractions.
# Event x-coordinates are all vertices and all pairwise segment intersection points (generic Cramer's rule, no slope tricks).
import sys, json
from fractions import Fraction as F

def seg_x_intersections(a, b, c, d):
    # returns x of intersection points of segments ab and cd (non-parallel proper/improper crossing), or []
    r = (b[0]-a[0], b[1]-a[1]); s = (d[0]-c[0], d[1]-c[1])
    den = r[0]*s[1] - r[1]*s[0]
    if den == 0: return []
    qp = (c[0]-a[0], c[1]-a[1])
    t = F(qp[0]*s[1] - qp[1]*s[0], den); u = F(qp[0]*r[1] - qp[1]*r[0], den)
    if 0 <= t <= 1 and 0 <= u <= 1: return [a[0] + t*r[0]]
    return []

def area(polys, k):
    segs = []
    xs = set()
    for pid, p in enumerate(polys):
        n = len(p)
        for i in range(n):
            a = tuple(p[i]); b = tuple(p[(i+1) % n])
            xs.add(F(a[0]))
            segs.append((a, b, pid))
    for i in range(len(segs)):
        a, b, _ = segs[i]
        for j in range(i+1, len(segs)):
            c, d, _ = segs[j]
            if max(a[0], b[0]) < min(c[0], d[0]) or max(c[0], d[0]) < min(a[0], b[0]): continue
            if max(a[1], b[1]) < min(c[1], d[1]) or max(c[1], d[1]) < min(a[1], b[1]): continue
            for x in seg_x_intersections(a, b, c, d): xs.add(x)
    X = sorted(xs)
    total = F(0)
    for i in range(len(X) - 1):
        xl, xr = X[i], X[i+1]; xm = (xl + xr) / 2
        cr = []
        for a, b, pid in segs:
            if a[0] == b[0]: continue
            lo, hi = min(a[0], b[0]), max(a[0], b[0])
            if lo <= xl and hi >= xr:
                y = a[1] + F(b[1]-a[1], b[0]-a[0]) * (xm - a[0])
                cr.append((y, pid))
        cr.sort()
        inside = [0]*len(polys); depth = 0; L = F(0)
        for idx, (y, pid) in enumerate(cr):
            if idx > 0 and depth >= k: L += y - cr[idx-1][0]
            inside[pid] ^= 1; depth += 1 if inside[pid] else -1
        total += (xr - xl) * L
    return f"{total.numerator}/{total.denominator}"

if __name__ == '__main__':
    print(json.dumps([area(*c) for c in json.load(sys.stdin)]))
