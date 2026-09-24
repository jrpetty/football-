// Typical exact solution a careful model might write: BigInt fractions everywhere, O(slabs * edges) scan per slab.
function gcd(a, b) { if (a < 0n) a = -a; if (b < 0n) b = -b; while (b) { [a, b] = [b, a % b]; } return a; }
function frac(n, d = 1n) { if (d < 0n) { n = -n; d = -d; } const g = gcd(n, d) || 1n; return { n: n / g, d: d / g }; }
function add(a, b) { return frac(a.n * b.d + b.n * a.d, a.d * b.d); }
function sub(a, b) { return frac(a.n * b.d - b.n * a.d, a.d * b.d); }
function mul(a, b) { return frac(a.n * b.n, a.d * b.d); }
function cmp(a, b) { const x = a.n * b.d - b.n * a.d; return x < 0n ? -1 : x > 0n ? 1 : 0; }
function coverageArea(polygons, k) {
  const segs = [];
  const xsMap = new Map();
  const addX = (f) => xsMap.set(f.n + '/' + f.d, f);
  polygons.forEach((p, pid) => p.forEach((a, i) => {
    const b = p[(i + 1) % p.length];
    addX(frac(BigInt(a[0])));
    segs.push({ x1: BigInt(a[0]), y1: BigInt(a[1]), x2: BigInt(b[0]), y2: BigInt(b[1]), pid });
  }));
  for (let i = 0; i < segs.length; i++) for (let j = i + 1; j < segs.length; j++) {
    const s = segs[i], t = segs[j];
    const rx = s.x2 - s.x1, ry = s.y2 - s.y1, sx = t.x2 - t.x1, sy = t.y2 - t.y1;
    const den = rx * sy - ry * sx; if (den === 0n) continue;
    const qx = t.x1 - s.x1, qy = t.y1 - s.y1;
    const tn = qx * sy - qy * sx, un = qx * ry - qy * rx;
    const inRange = (num) => den > 0n ? num >= 0n && num <= den : num <= 0n && num >= den;
    if (inRange(tn) && inRange(un)) addX(add(frac(s.x1), mul(frac(tn, den), frac(rx))));
  }
  const X = [...xsMap.values()].sort(cmp);
  let total = frac(0n);
  for (let i = 0; i + 1 < X.length; i++) {
    const xl = X[i], xr = X[i + 1], xm = mul(add(xl, xr), frac(1n, 2n));
    const cr = [];
    for (const s of segs) {
      if (s.x1 === s.x2) continue;
      const lo = s.x1 < s.x2 ? s.x1 : s.x2, hi = s.x1 < s.x2 ? s.x2 : s.x1;
      if (cmp(frac(lo), xl) <= 0 && cmp(frac(hi), xr) >= 0) cr.push({ y: add(frac(s.y1), mul(frac(s.y2 - s.y1, s.x2 - s.x1), sub(xm, frac(s.x1)))), pid: s.pid });
    }
    cr.sort((a, b) => cmp(a.y, b.y));
    const inside = new Array(polygons.length).fill(0); let depth = 0; let L = frac(0n);
    cr.forEach((c, idx) => { if (idx > 0 && depth >= k) L = add(L, sub(c.y, cr[idx - 1].y)); inside[c.pid] ^= 1; depth += inside[c.pid] ? 1 : -1; });
    total = add(total, mul(sub(xr, xl), L));
  }
  return total.n + '/' + total.d;
}
