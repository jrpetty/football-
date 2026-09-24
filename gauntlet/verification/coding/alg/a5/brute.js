function componentsAfterCuts(n, edges, cuts) {
  const alive = edges.map(() => true); const out = [];
  for (const c of cuts) {
    alive[c] = false;
    const adj = Array.from({ length: n }, () => []);
    edges.forEach(([a, b], i) => { if (alive[i]) { adj[a].push(b); adj[b].push(a); } });
    const seen = new Array(n).fill(false); let cnt = 0;
    for (let s = 0; s < n; s++) { if (seen[s]) continue; cnt++; const st = [s]; seen[s] = true; while (st.length) { const u = st.pop(); for (const v of adj[u]) if (!seen[v]) { seen[v] = true; st.push(v); } } }
    out.push(cnt);
  }
  return out;
}
