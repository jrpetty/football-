// Exact but exponential: try all tower subsets (fine for tiny trees only).
function minTowerCost(parent, cost, reach, need) {
  const n = parent.length; const adj = Array.from({ length: n }, () => []);
  parent.forEach((p, i) => { if (p >= 0) { adj[i].push(p); adj[p].push(i); } });
  const cov = [];
  for (let t = 0; t < n; t++) { const d = new Array(n).fill(-1); d[t] = 0; const q = [t]; const s = new Set(); for (let h = 0; h < q.length; h++) { const u = q[h]; if (d[u] <= reach[t]) s.add(u); else continue; for (const w of adj[u]) if (d[w] < 0) { d[w] = d[u] + 1; q.push(w); } } cov.push(s); }
  const needed = [...Array(n).keys()].filter((i) => need[i] === '1');
  let best = Infinity;
  const chosen = new Array(n).fill(false);
  const rec = (i, c) => {
    if (c >= best) return;
    if (i === n) { if (needed.every((u) => cov.some((s, t) => chosen[t] && s.has(u)))) best = c; return; }
    chosen[i] = false; rec(i + 1, c); chosen[i] = true; rec(i + 1, c + cost[i]); chosen[i] = false;
  };
  rec(0, 0);
  return best;
}
