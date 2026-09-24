function simulateCache(capacity, ttl, events) {
  const map = new Map(); // key -> {value, exp}; insertion order = recency (oldest first)
  const heap = [];       // [exp, key, stamp]
  let stamp = 0;
  const push = (x) => { heap.push(x); let i = heap.length - 1; while (i > 0) { const p = (i - 1) >> 1; if (heap[p][0] <= heap[i][0]) break; [heap[p], heap[i]] = [heap[i], heap[p]]; i = p; } };
  const pop = () => { const top = heap[0]; const last = heap.pop(); if (heap.length) { heap[0] = last; let i = 0; for (;;) { const l = 2 * i + 1, r = l + 1; let m = i; if (l < heap.length && heap[l][0] < heap[m][0]) m = l; if (r < heap.length && heap[r][0] < heap[m][0]) m = r; if (m === i) break; [heap[m], heap[i]] = [heap[i], heap[m]]; i = m; } } return top; };
  const purge = (t) => { while (heap.length && heap[0][0] <= t) { const [, k, s] = pop(); const e = map.get(k); if (e && e.stamp === s) map.delete(k); } };
  const out = [];
  for (const ev of events) {
    const [op, t, key] = ev;
    purge(t);
    if (op === 'get') {
      const e = map.get(key);
      if (!e) { out.push(null); continue; }
      map.delete(key); map.set(key, e); out.push(e.value);
    } else {
      const value = ev[3];
      if (map.has(key)) map.delete(key);
      else if (map.size >= capacity) { const lru = map.keys().next().value; map.delete(lru); }
      const e = { value, exp: t + ttl, stamp: ++stamp };
      map.set(key, e); push([e.exp, key, e.stamp]);
    }
  }
  return out;
}
