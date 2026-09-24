function cheapestTrip(n, roads, start, target, coupons) {
  // Bellman-Ford over states (node, coupons used)
  const INF = Infinity; const d = []; for (let i = 0; i < n; i++) d.push(new Array(coupons + 1).fill(INF));
  d[start][0] = 0;
  let changed = true;
  while (changed) {
    changed = false;
    for (const [a, b, c] of roads) for (const [u, v] of [[a, b], [b, a]]) for (let k = 0; k <= coupons; k++) {
      if (d[u][k] === INF) continue;
      if (d[u][k] + c < d[v][k]) { d[v][k] = d[u][k] + c; changed = true; }
      if (k < coupons && d[u][k] + Math.floor(c / 2) < d[v][k + 1]) { d[v][k + 1] = d[u][k] + Math.floor(c / 2); changed = true; }
    }
  }
  const best = Math.min(...d[target]);
  return best === INF ? -1 : best;
}
