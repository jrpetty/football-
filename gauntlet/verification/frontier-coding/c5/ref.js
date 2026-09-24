// Reference for c5. Bottom-up DP; the state of a processed subtree T(v) is either
//   P[r] (r = -1..RM): every needed vertex of T(v) is covered from inside, and the best inside tower reaches r more steps
//        beyond v (r = -1: no inside tower even reaches v), or
//   D[d] (d = 0..RM): some needed vertex of T(v) is still uncovered, the deepest one at depth d below v.
// When a deficit exists the inside reach is irrelevant (whatever covers the deficit from outside covers more), so these
// states are exact. Merging: P[a]+P[b]=P[max]; P[a]+D[d]= a>=d ? P[a] : D[d]; D[d]+D[e]=D[max].
// A deficit of depth d can later be fixed only by a tower at distance >= 1 above v, which needs reach >= d+1, so D[RM] is
// discarded once v is finished (but not before v's own tower has been considered). Iterative (BFS order).
function minTowerCost(parent, cost, reach, need) {
  const n = parent.length;
  let RM = 0; for (let i = 0; i < n; i++) if (reach[i] > RM) RM = reach[i];
  const NP = RM + 2;          // P states: index r+1 for r = -1..RM
  const S = NP + RM + 1;      // D states: index NP + d for d = 0..RM
  const kids = Array.from({ length: n }, () => []);
  let root = -1;
  for (let i = 0; i < n; i++) if (parent[i] === -1) root = i; else kids[parent[i]].push(i);
  const order = [root];
  for (let h = 0; h < order.length; h++) for (const c of kids[order[h]]) order.push(c);
  const INF = Infinity;
  const combine = (x, y) => { // x, y state indices -> merged state index
    const xp = x < NP, yp = y < NP;
    if (xp === yp) return Math.max(x, y);
    const p = xp ? x : y, dd = xp ? y : x;
    return p - 1 >= dd - NP ? p : dd;
  };
  const merge = (A, B) => {
    const C = new Array(S).fill(INF);
    for (let x = 0; x < S; x++) {
      if (A[x] === INF) continue;
      for (let y = 0; y < S; y++) {
        if (B[y] === INF) continue;
        const z = combine(x, y), val = A[x] + B[y];
        if (val < C[z]) C[z] = val;
      }
    }
    return C;
  };
  const dp = new Array(n);
  for (let t = n - 1; t >= 0; t--) {
    const v = order[t];
    let cur = new Array(S).fill(INF);
    cur[0] = 0;                                                  // no tower at v: P[-1]
    cur[reach[v] + 1] = Math.min(cur[reach[v] + 1], cost[v]);    // tower at v: P[reach[v]]
    for (const c of kids[v]) {
      const ch = dp[c];
      const up = new Array(S).fill(INF);                         // child's states seen from v
      for (let x = 0; x < S; x++) {
        if (ch[x] === INF) continue;
        let z;
        if (x < NP) z = Math.max(x - 1, 0);                      // reach r -> r-1 (clamped at -1)
        else { const d = x - NP + 1; if (d > RM) continue; z = NP + d; }
        if (ch[x] < up[z]) up[z] = ch[x];
      }
      cur = merge(cur, up);
      dp[c] = null;
    }
    if (need[v] === '1') {
      const dem = new Array(S).fill(INF); dem[NP] = 0;           // D[0]
      cur = merge(cur, dem);
    }
    cur[NP + RM] = INF;                                          // D[RM] can no longer be covered
    dp[v] = cur;
  }
  let best = INF;
  for (let x = 0; x < NP; x++) if (dp[root][x] < best) best = dp[root][x];
  return best;
}
