// Plausible greedy: repeatedly cover the deepest uncovered needed vertex with the tower of best cost per newly covered vertex.
function minTowerCost(parent, cost, reach, need) {
  const n = parent.length; const adj = Array.from({ length: n }, () => []); let root = 0;
  parent.forEach((p, i) => { if (p >= 0) { adj[i].push(p); adj[p].push(i); } else root = i; });
  const depth = new Array(n).fill(-1); depth[root] = 0; const q = [root];
  for (let h = 0; h < q.length; h++) for (const w of adj[q[h]]) if (depth[w] < 0) { depth[w] = depth[q[h]] + 1; q.push(w); }
  const ball = (t, r) => { const d = new Map([[t, 0]]); const qq = [t]; for (let h = 0; h < qq.length; h++) { const u = qq[h]; if (d.get(u) === r) continue; for (const w of adj[u]) if (!d.has(w)) { d.set(w, d.get(u) + 1); qq.push(w); } } return [...d.keys()]; };
  const covered = new Array(n).fill(false); let total = 0;
  const order = [...Array(n).keys()].filter((i) => need[i] === '1').sort((a, b) => depth[b] - depth[a]);
  for (const u of order) {
    if (covered[u]) continue;
    let best = null, bestRatio = Infinity;
    for (const t of ball(u, 20)) {
      const b = ball(t, reach[t]); if (!b.includes(u)) continue;
      const gain = b.filter((x) => need[x] === '1' && !covered[x]).length; const ratio = cost[t] / gain;
      if (ratio < bestRatio) { bestRatio = ratio; best = b; best.c = cost[t]; }
    }
    total += best.c; for (const x of best) covered[x] = true;
  }
  return total;
}
