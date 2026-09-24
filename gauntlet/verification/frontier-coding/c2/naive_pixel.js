// Plausible for small inputs: count covered quarter-triangles of unit squares (exact but O(area)).
function coverageArea(polygons, k) {
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  for (const p of polygons) for (const [x, y] of p) { minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
  const inside = (px, py, poly) => { let c = false; for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) { const [xi, yi] = poly[i], [xj, yj] = poly[j]; if ((yi > py) !== (yj > py) && px < ((xj - xi) * (py - yi)) / (yj - yi) + xi) c = !c; } return c; };
  let cnt = 0;
  for (let x = minX; x < maxX; x++) for (let y = minY; y < maxY; y++) for (const [dx, dy] of [[0.5, 1 / 6], [5 / 6, 0.5], [0.5, 5 / 6], [1 / 6, 0.5]]) {
    let d = 0; for (const p of polygons) if (inside(x + dx, y + dy, p)) d++;
    if (d >= k) cnt++;
  }
  const g = (a, b) => (b ? g(b, a % b) : a); const d = g(cnt, 4) || 1;
  return `${cnt / d}/${4 / d}`;
}
