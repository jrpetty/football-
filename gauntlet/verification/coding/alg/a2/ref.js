function cheapestTrip(n, roads, start, target, coupons) {
  if (start === target) return 0;
  const adj = Array.from({ length: n }, () => []);
  for (const [u, v, c] of roads) { adj[u].push([v, c]); adj[v].push([u, c]); }
  const K = coupons + 1;
  const dist = new Float64Array(n * K).fill(Infinity);
  // binary heap of [d, node, used]
  const heap = [];
  const push = (x) => { heap.push(x); let i = heap.length - 1; while (i > 0) { const p = (i - 1) >> 1; if (heap[p][0] <= heap[i][0]) break; [heap[p], heap[i]] = [heap[i], heap[p]]; i = p; } };
  const pop = () => { const top = heap[0]; const last = heap.pop(); if (heap.length) { heap[0] = last; let i = 0; for (;;) { const l = 2 * i + 1, r = l + 1; let m = i; if (l < heap.length && heap[l][0] < heap[m][0]) m = l; if (r < heap.length && heap[r][0] < heap[m][0]) m = r; if (m === i) break; [heap[m], heap[i]] = [heap[i], heap[m]]; i = m; } } return top; };
  dist[start * K] = 0; push([0, start, 0]);
  while (heap.length) {
    const [d, u, k] = pop();
    if (d > dist[u * K + k]) continue;
    if (u === target) return d;
    for (const [v, c] of adj[u]) {
      if (d + c < dist[v * K + k]) { dist[v * K + k] = d + c; push([d + c, v, k]); }
      if (k < coupons) { const h = Math.floor(c / 2); if (d + h < dist[v * K + k + 1]) { dist[v * K + k + 1] = d + h; push([d + h, v, k + 1]); } }
    }
  }
  return -1;
}
