// Plausible slip: winding count that assumes every polygon is counter-clockwise.
// Reference for c2: area covered by at least k octilinear polygons, exact.
// Vertical-slab sweep. Coordinates are scaled by 4 so that every event x (vertex or crossing) is an even integer and
// every slab midpoint is an integer; inside a slab the covered length is linear in x, so slab area = width * length(mid).
function coverageArea(polygons, k) {
  const edges = []; // [xa, ya, xb, yb, slope, poly] with xa < xb (non-vertical only), scaled by 4
  const xs = new Set();
  polygons.forEach((poly, pid) => {
    for (let i = 0; i < poly.length; i++) {
      const [x1, y1] = poly[i];
      const [x2, y2] = poly[(i + 1) % poly.length];
      xs.add(4 * x1);
      if (x1 === x2) continue;
      const s = Math.sign(y2 - y1) * Math.sign(x2 - x1);
      if (x1 < x2) edges.push([4 * x1, 4 * y1, 4 * x2, 4 * y2, s, pid, 1]);
      else edges.push([4 * x2, 4 * y2, 4 * x1, 4 * y1, s, pid, -1]);
    }
  });
  // crossings of edges with different slopes
  const byStart = edges.slice().sort((a, b) => a[0] - b[0]);
  for (let i = 0; i < byStart.length; i++) {
    const e = byStart[i];
    for (let j = i + 1; j < byStart.length; j++) {
      const f = byStart[j];
      if (f[0] >= e[2]) break;
      if (e[4] === f[4]) continue;
      // y = e1 + s1 (x - e0) = f1 + s2 (x - f0)  =>  x = (f1 - e1 + s1 e0 - s2 f0) / (s1 - s2)
      const num = f[1] - e[1] + e[4] * e[0] - f[4] * f[0];
      const den = e[4] - f[4];
      const x = num / den; // exact: num is a multiple of 4, den in {±1, ±2}
      if (x >= f[0] && x <= e[2] && x >= e[0] && x <= f[2]) xs.add(x);
    }
  }
  const X = Array.from(xs).sort((a, b) => a - b);
  let total = 0n;
  let ei = 0;
  let active = [];
  const inside = new Int32Array(polygons.length);
  for (let t = 0; t + 1 < X.length; t++) {
    const xa = X[t], xb = X[t + 1];
    while (ei < byStart.length && byStart[ei][0] <= xa) active.push(byStart[ei++]);
    active = active.filter((e) => e[2] >= xb);
    if (active.length === 0) continue;
    const xm = (xa + xb) / 2;
    const cross = active.map((e) => [e[1] + e[4] * (xm - e[0]), e[5], e[6]]).sort((a, b) => a[0] - b[0]);
    const e_dir = cross.map((c) => c[2]);
    let depth = 0, len = 0;
    for (let i = 0; i < cross.length; i++) {
      const [y, pid] = cross[i];
      if (depth >= k && i > 0) len += y - cross[i - 1][0];
      depth += e_dir[i];
    }
    if (len > 0) total += BigInt(xb - xa) * BigInt(len);
  }
  // total is 16 * area
  let p = total, q = 16n;
  const g = (a, b) => { while (b) [a, b] = [b, a % b]; return a; };
  const d = g(p, q);
  return `${p / d}/${q / d}`;
}
