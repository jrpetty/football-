# Independent oracle for c2: every edge lies on a line x=c, y=c, x-y=c or x+y=c with integer c, so the plane is cut into
# quarter-triangles of unit squares (area 1/4 each) and each triangle is either fully covered by a polygon or not.
# For each triangle we test its centroid against every polygon with exact integer ray casting. O(area * edges): small inputs only.
import sys, json
from fractions import Fraction

def inside(px, py, poly):
    # px, py and poly scaled by 6; point is never on an edge; ray to +x
    c = 0
    n = len(poly)
    for i in range(n):
        x1, y1 = poly[i]; x2, y2 = poly[(i + 1) % n]
        if (y1 > py) != (y2 > py):
            # x at py: x1 + (py - y1) * (x2 - x1) / (y2 - y1) > px  <=>  compare with sign of (y2 - y1)
            lhs = (x1 - px) * (y2 - y1) + (py - y1) * (x2 - x1)
            if (lhs > 0) == (y2 - y1 > 0): c ^= 1
    return c == 1

def area(polys, k):
    xs = [x for p in polys for x, _ in p]; ys = [y for p in polys for _, y in p]
    P6 = [[(6 * x, 6 * y) for x, y in p] for p in polys]
    cnt = 0
    for i in range(min(xs), max(xs)):
        for j in range(min(ys), max(ys)):
            for (cx, cy) in ((6*i+3, 6*j+1), (6*i+5, 6*j+3), (6*i+3, 6*j+5), (6*i+1, 6*j+3)):
                d = sum(1 for p in P6 if inside(cx, cy, p))
                if d >= k: cnt += 1
    f = Fraction(cnt, 4)
    return f"{f.numerator}/{f.denominator}"

if __name__ == '__main__':
    print(json.dumps([area(*c) for c in json.load(sys.stdin)]))
