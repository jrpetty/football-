function componentsAfterCuts(n, edges, cuts) {
  const parent = Array.from({ length: n }, (_, i) => i);
  const find = (x) => { while (parent[x] !== x) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; };
  let comps = n;
  const union = (a, b) => { a = find(a); b = find(b); if (a !== b) { parent[a] = b; comps--; } };
  const removed = new Uint8Array(edges.length);
  for (const c of cuts) removed[c] = 1;
  for (let i = 0; i < edges.length; i++) if (!removed[i]) union(edges[i][0], edges[i][1]);
  const out = new Array(cuts.length);
  for (let k = cuts.length - 1; k >= 0; k--) {
    out[k] = comps;
    const [a, b] = edges[cuts[k]];
    union(a, b);
  }
  return out;
}
