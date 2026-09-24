// Typical correct solution: generic min-cost-flow template (edge list + SPFA), augmenting one unit at a time.
function minFleetCost(depot, jobs, truckCost, maxTrucks) {
  const n = jobs.length; if (n === 0) return 0;
  const dist = (a, b, c, d) => Math.abs(a - c) + Math.abs(b - d);
  const S = 2 * n, T = 2 * n + 1, V = 2 * n + 2;
  const to = [], cap = [], cost = [], head = new Array(V).fill(-1), nxt = [];
  function addEdge(u, v, c, w) { to.push(v); cap.push(c); cost.push(w); nxt.push(head[u]); head[u] = to.length - 1; to.push(u); cap.push(0); cost.push(-w); nxt.push(head[v]); head[v] = to.length - 1; }
  let base = 0;
  for (let i = 0; i < n; i++) { const [px, py, qx, qy] = jobs[i]; base += truckCost + dist(depot[0], depot[1], px, py) + dist(qx, qy, depot[0], depot[1]); addEdge(S, i, 1, 0); addEdge(n + i, T, 1, 0); }
  for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) {
    const dd = dist(jobs[i][2], jobs[i][3], jobs[j][0], jobs[j][1]);
    if (jobs[i][5] + dd <= jobs[j][4]) addEdge(i, n + j, 1, dd - dist(jobs[i][2], jobs[i][3], depot[0], depot[1]) - dist(depot[0], depot[1], jobs[j][0], jobs[j][1]) - truckCost);
  }
  let flow = 0, total = 0; const need = n - maxTrucks;
  for (;;) {
    const d = new Array(V).fill(Infinity), inq = new Array(V).fill(false), pe = new Array(V).fill(-1);
    d[S] = 0; const q = [S]; inq[S] = true; let qh = 0;
    while (qh < q.length) { const u = q[qh++]; inq[u] = false; for (let e = head[u]; e !== -1; e = nxt[e]) if (cap[e] > 0 && d[u] + cost[e] < d[to[e]]) { d[to[e]] = d[u] + cost[e]; pe[to[e]] = e; if (!inq[to[e]]) { inq[to[e]] = true; q.push(to[e]); } } }
    if (d[T] === Infinity) break;
    if (flow >= need && d[T] >= 0) break;
    for (let v = T; v !== S; v = to[pe[v] ^ 1]) { cap[pe[v]]--; cap[pe[v] ^ 1]++; }
    flow++; total += d[T];
  }
  return flow < need ? -1 : base + total;
}
