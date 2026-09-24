// Plausible slip: minimum-cost MAXIMUM matching (always links as many jobs as possible).
// Reference for c4: minimum-cost path cover of the "can follow" DAG with a fleet-size cap.
// cost = sum_j (F + d(depot, pick_j) + d(drop_j, depot)) + sum over links i->j of w_ij, w_ij = d(drop_i, pick_j) - d(drop_i, depot) - d(depot, pick_j) - F.
// Links form a bipartite matching; successive shortest augmenting paths (dense Dijkstra with potentials) give the optimal
// cost for every matching size f; path costs are nondecreasing, so we augment while f < n - K or the next path is negative.
function minFleetCost(depot, jobs, truckCost, maxTrucks) {
  const n = jobs.length;
  if (n === 0) return 0;
  const d = (ax, ay, bx, by) => Math.abs(ax - bx) + Math.abs(ay - by);
  const [DX, DY] = depot;
  let base = 0;
  for (const [px, py, qx, qy] of jobs) base += truckCost + d(DX, DY, px, py) + d(qx, qy, DX, DY);
  const INF = Infinity;
  const w = []; // w[i][j] or INF
  for (let i = 0; i < n; i++) {
    const [, , qx, qy, , ei] = jobs[i];
    const row = new Array(n).fill(INF);
    for (let j = 0; j < n; j++) {
      const [px, py, , , sj] = jobs[j];
      const dist = d(qx, qy, px, py);
      if (ei + dist <= sj) row[j] = dist - d(qx, qy, DX, DY) - d(DX, DY, px, py) - truckCost;
    }
    w.push(row);
  }
  const matchL = new Array(n).fill(-1), matchR = new Array(n).fill(-1);
  // potentials: shortest distances from the source in the initial DAG
  const potL = new Array(n).fill(0), potR = new Array(n).fill(INF);
  for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) if (w[i][j] < potR[j]) potR[j] = w[i][j];
  let f = 0, extra = 0;
  const need = n - maxTrucks;
  while (true) {
    // Dijkstra over L and R nodes with reduced costs; sources = free L nodes (distance 0)
    const distL = new Array(n).fill(INF), distR = new Array(n).fill(INF);
    const doneL = new Array(n).fill(false), doneR = new Array(n).fill(false);
    const prevR = new Array(n).fill(-1); // L node that reached R_j
    for (let i = 0; i < n; i++) if (matchL[i] === -1) distL[i] = 0;
    while (true) {
      let best = INF, bi = -1, side = 0;
      for (let i = 0; i < n; i++) if (!doneL[i] && distL[i] < best) { best = distL[i]; bi = i; side = 0; }
      for (let j = 0; j < n; j++) if (!doneR[j] && distR[j] < best) { best = distR[j]; bi = j; side = 1; }
      if (bi < 0) break;
      if (side === 0) {
        doneL[bi] = true;
        const row = w[bi];
        for (let j = 0; j < n; j++) {
          if (doneR[j] || row[j] === INF || matchL[bi] === j) continue;
          const nd = best + row[j] + potL[bi] - potR[j];
          if (nd < distR[j]) { distR[j] = nd; prevR[j] = bi; }
        }
      } else {
        doneR[bi] = true;
        const i = matchR[bi];
        if (i >= 0 && !doneL[i]) {
          const nd = best - w[i][bi] + potR[bi] - potL[i];
          if (nd < distL[i]) distL[i] = nd;
        }
      }
    }
    // best free R endpoint by true path cost
    let endJ = -1, endCost = INF;
    for (let j = 0; j < n; j++) if (matchR[j] === -1 && distR[j] < INF) {
      const c = distR[j] + potR[j]; // true cost (all sources have potential... see below)
      if (c < endCost) { endCost = c; endJ = j; }
    }
    if (endJ < 0) break;
    // true cost of the path = reduced distance + potR[end] - potL[start]; free L nodes all keep potL = 0 (never on an augmenting path's interior before being matched? they can be; handled via start lookup)
    // recover start node to get exact true cost
    let j = endJ; const path = [];
    while (true) { const i = prevR[j]; path.push([i, j]); const pj = matchL[i]; if (pj === -1) break; j = pj; }
    const startI = path[path.length - 1][0];
    const trueCost = distR[endJ] + potR[endJ] - potL[startI];
    
    for (const [i, jj] of path) { matchL[i] = jj; matchR[jj] = i; }
    f++; extra += trueCost;
    for (let i = 0; i < n; i++) if (distL[i] < INF) potL[i] += distL[i];
    for (let jj = 0; jj < n; jj++) if (distR[jj] < INF) potR[jj] += distR[jj];
  }
  if (f < need) return -1;
  return base + extra;
}
