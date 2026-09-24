// Random simple octilinear polygons (integer vertices; edges horizontal, vertical or at 45 degrees).
export function polyomino(R, W, H, target) {
  const g = Array.from({ length: H + 2 }, () => new Array(W + 2).fill(0));
  let cx = R.int(1, W), cy = R.int(1, H); g[cy][cx] = 1; let cnt = 1; const cells = [[cx, cy]];
  let guard = 0;
  while (cnt < target && guard++ < 10000) {
    const [x, y] = R.pick(cells); const [dx, dy] = R.pick([[1, 0], [-1, 0], [0, 1], [0, -1]]);
    const nx = x + dx, ny = y + dy;
    if (nx < 1 || ny < 1 || nx > W || ny > H || g[ny][nx]) continue;
    g[ny][nx] = 1; cells.push([nx, ny]); cnt++;
  }
  for (let changed = true; changed;) {
    changed = false;
    // fill holes
    const seen = g.map((r) => r.map(() => 0)); const st = [[0, 0]]; seen[0][0] = 1;
    while (st.length) { const [x, y] = st.pop(); for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) { const nx = x + dx, ny = y + dy; if (nx < 0 || ny < 0 || nx > W + 1 || ny > H + 1 || seen[ny][nx] || g[ny][nx]) continue; seen[ny][nx] = 1; st.push([nx, ny]); } }
    for (let y = 1; y <= H; y++) for (let x = 1; x <= W; x++) if (!g[y][x] && !seen[y][x]) { g[y][x] = 1; changed = true; }
    // remove pinches (diagonal-only contact)
    for (let y = 0; y <= H; y++) for (let x = 0; x <= W; x++) {
      const a = g[y][x], b = g[y][x + 1], c = g[y + 1][x], d = g[y + 1][x + 1];
      if (a && d && !b && !c) { if (x + 1 <= W && y >= 1) g[y][x + 1] = 1; else g[y + 1][x] = 1; changed = true; }
      else if (b && c && !a && !d) { if (x >= 1 && y >= 1 && x <= W && y <= H) g[y][x] = 1; else g[y + 1][x + 1] = 1; changed = true; }
    }
  }
  // directed boundary edges with the filled cell on the left (CCW)
  const next = new Map(); const key = (x, y) => x * 1000 + y;
  for (let y = 1; y <= H; y++) for (let x = 1; x <= W; x++) if (g[y][x]) {
    if (!g[y - 1][x]) next.set(key(x, y), [x + 1, y]);          // bottom edge, left to right
    if (!g[y][x + 1]) next.set(key(x + 1, y), [x + 1, y + 1]);  // right edge, upward
    if (!g[y + 1][x]) next.set(key(x + 1, y + 1), [x, y + 1]);  // top edge, right to left
    if (!g[y][x - 1]) next.set(key(x, y + 1), [x, y]);          // left edge, downward
  }
  const start = next.keys().next().value; const pts = [];
  let cur = [Math.floor(start / 1000), start % 1000];
  do { pts.push(cur); cur = next.get(key(cur[0], cur[1])); } while (key(cur[0], cur[1]) !== start);
  if (pts.length !== next.size) throw new Error('boundary not a single cycle');
  return simplifyCollinear(pts);
}
export function simplifyCollinear(pts) {
  let out = pts.filter((p, i) => { const q = pts[(i + 1) % pts.length]; return p[0] !== q[0] || p[1] !== q[1]; });
  let changed = true;
  while (changed && out.length > 3) {
    changed = false;
    for (let i = 0; i < out.length; i++) {
      const a = out[(i - 1 + out.length) % out.length], b = out[i], c = out[(i + 1) % out.length];
      const cr = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]);
      const dot = (b[0] - a[0]) * (c[0] - b[0]) + (b[1] - a[1]) * (c[1] - b[1]);
      if (cr === 0 && dot > 0) { out.splice(i, 1); changed = true; break; }
    }
  }
  return out;
}
// Cut convex corners (CCW polygon) by 45-degree cuts of size in [1, maxCut]; edges must be long enough (checked).
export function cutCorners(pts, R, prob, maxCut) {
  const n = pts.length; const res = [];
  const len = (a, b) => Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1]));
  const cuts = pts.map(() => 0);
  for (let i = 0; i < n; i++) {
    const a = pts[(i - 1 + n) % n], b = pts[i], c = pts[(i + 1) % n];
    const d1 = [Math.sign(b[0] - a[0]), Math.sign(b[1] - a[1])], d2 = [Math.sign(c[0] - b[0]), Math.sign(c[1] - b[1])];
    const cr = d1[0] * d2[1] - d1[1] * d2[0];
    const axis = (d) => d[0] === 0 || d[1] === 0;
    if (cr > 0 && axis(d1) && axis(d2) && R.chance(prob)) cuts[i] = R.int(1, maxCut);
  }
  // make sure cuts fit: for each edge, cut at both ends <= length
  for (let i = 0; i < n; i++) {
    const j = (i + 1) % n; const L = len(pts[i], pts[j]);
    while (cuts[i] + cuts[j] > L) { if (cuts[i] >= cuts[j]) cuts[i]--; else cuts[j]--; }
  }
  for (let i = 0; i < n; i++) {
    const a = pts[(i - 1 + n) % n], b = pts[i], c = pts[(i + 1) % n];
    if (!cuts[i]) { res.push(b); continue; }
    const d1 = [Math.sign(b[0] - a[0]), Math.sign(b[1] - a[1])], d2 = [Math.sign(c[0] - b[0]), Math.sign(c[1] - b[1])];
    res.push([b[0] - d1[0] * cuts[i], b[1] - d1[1] * cuts[i]]);
    res.push([b[0] + d2[0] * cuts[i], b[1] + d2[1] * cuts[i]]);
  }
  return simplifyCollinear(res);
}
export function convexOcta(R, w, h, maxCutFrac) {
  const c = [0, 0, 0, 0].map(() => R.int(0, Math.floor(Math.min(w, h) * maxCutFrac)));
  const pts = [[c[0], 0], [w - c[1], 0], [w, c[1]], [w, h - c[2]], [w - c[2], h], [c[3], h], [0, h - c[3]], [0, c[0]]];
  return simplifyCollinear(pts);
}
export const map45 = (pts) => pts.map(([x, y]) => [x - y, x + y]);
export function transform(pts, R, scale, ox, oy) {
  let p = pts.map(([x, y]) => [x * scale, y * scale]);
  const t = R.int(0, 7);
  p = p.map(([x, y]) => { let a = x, b = y; if (t & 1) a = -a; if (t & 2) b = -b; if (t & 4) [a, b] = [b, a]; return [a, b]; });
  p = p.map(([x, y]) => [x + ox, y + oy]);
  if (R.chance(0.5)) p.reverse();
  const r = R.int(0, p.length - 1);
  return p.slice(r).concat(p.slice(0, r));
}
// exact simplicity check for integer polygons
export function isSimple(p) {
  const n = p.length;
  const orient = (a, b, c) => Math.sign((b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]));
  const onSeg = (a, b, c) => Math.min(a[0], b[0]) <= c[0] && c[0] <= Math.max(a[0], b[0]) && Math.min(a[1], b[1]) <= c[1] && c[1] <= Math.max(a[1], b[1]);
  const inter = (a, b, c, d) => {
    const o1 = orient(a, b, c), o2 = orient(a, b, d), o3 = orient(c, d, a), o4 = orient(c, d, b);
    if (o1 !== o2 && o3 !== o4 && o1 * o2 <= 0 && o3 * o4 <= 0 && !(o1 === 0 || o2 === 0 || o3 === 0 || o4 === 0)) return true;
    if (o1 === 0 && onSeg(a, b, c)) return true; if (o2 === 0 && onSeg(a, b, d)) return true;
    if (o3 === 0 && onSeg(c, d, a)) return true; if (o4 === 0 && onSeg(c, d, b)) return true;
    return false;
  };
  for (let i = 0; i < n; i++) { const a = p[i], b = p[(i + 1) % n]; if (a[0] === b[0] && a[1] === b[1]) return false; }
  for (let i = 0; i < n; i++) for (let j = i + 1; j < n; j++) {
    const a = p[i], b = p[(i + 1) % n], c = p[j], d = p[(j + 1) % n];
    if (j === i + 1 || (i === 0 && j === n - 1)) {
      // adjacent: must only share the common vertex (no overlap back along the same line)
      const shared = j === i + 1 ? b : a; const other1 = j === i + 1 ? a : b; const other2 = j === i + 1 ? d : c;
      if (orient(other1, shared, other2) === 0) { const dot = (shared[0] - other1[0]) * (other2[0] - shared[0]) + (shared[1] - other1[1]) * (other2[1] - shared[1]); if (dot <= 0) return false; }
      continue;
    }
    if (inter(a, b, c, d)) return false;
  }
  return true;
}
export function isOcto(p) {
  return p.every((a, i) => { const b = p[(i + 1) % p.length]; const dx = Math.abs(b[0] - a[0]), dy = Math.abs(b[1] - a[1]); return dx === 0 || dy === 0 || dx === dy; });
}
export function randomPolygon(R, opts) {
  const { maxGrid = 6, scaleMax = 1, cutProb = 0.4, span = 10 } = opts;
  for (;;) {
    let p; const kind = R.next();
    if (kind < 0.45) {
      const W = R.int(1, maxGrid), H = R.int(1, maxGrid);
      p = polyomino(R, W, H, R.int(1, W * H));
      const s = R.int(2, 3); p = p.map(([x, y]) => [x * s, y * s]);
      p = cutCorners(p, R, cutProb, s);
    } else if (kind < 0.7) {
      p = convexOcta(R, R.int(1, maxGrid * 2), R.int(1, maxGrid * 2), 0.5);
    } else if (kind < 0.8) {
      const a = R.int(1, maxGrid); p = [[0, 0], [a, a], [0, 2 * a], [-a, a]]; // diamond
    } else if (kind < 0.9) {
      const a = R.int(1, maxGrid * 2); p = R.pick([[[0, 0], [a, 0], [0, a]], [[0, 0], [2 * a, 0], [a, a]]]); // right isosceles triangles
    } else {
      const W = R.int(1, Math.max(1, maxGrid - 2)), H = R.int(1, Math.max(1, maxGrid - 2));
      p = polyomino(R, W, H, R.int(1, W * H)); p = cutCorners(p.map(([x, y]) => [2 * x, 2 * y]), R, cutProb, 2); p = map45(p);
    }
    const sc = R.int(1, scaleMax);
    p = transform(p, R, sc, R.int(-span, span), R.int(-span, span));
    if (p.length >= 3 && isSimple(p) && isOcto(p)) return p;
  }
}
