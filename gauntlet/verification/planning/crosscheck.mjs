// Independent JS re-verification of planning optima (different code from plan.py).
function bfs(start, key, isGoal, next) {
  const seen = new Map([[key(start), 0]]);
  let frontier = [start], d = 0;
  while (frontier.length) {
    for (const s of frontier) if (isGoal(s)) return d;
    const nf = [];
    for (const s of frontier) for (const t of next(s)) { const k = key(t); if (!seen.has(k)) { seen.set(k, d + 1); nf.push(t); } }
    frontier = nf; d++;
  }
  return null;
}
const out = {};
// p01 jugs 4,9 target 6
out.p01 = bfs([0, 0], String, (s) => s.includes(6), ([a, b]) => {
  const C = [4, 9], r = [];
  r.push([4, b], [a, 9], [0, b], [a, 0]);
  const x = Math.min(a, 9 - b); r.push([a - x, b + x]);
  const y = Math.min(b, 4 - a); r.push([a + y, b - y]);
  return r;
});
// p02 coins
out.p02 = bfs('HTTHHTTTHT', String, (s) => !s.includes('T'), (s) => {
  const r = [];
  for (let i = 0; i + 3 <= s.length; i++) r.push(s.slice(0, i) + [...s.slice(i, i + 3)].map((c) => (c === 'H' ? 'T' : 'H')).join('') + s.slice(i + 3));
  return r;
});
// bridge via Dijkstra (array-based PQ)
function bridge(times, cap) {
  const n = times.length, FULL = (1 << n) - 1;
  const dist = new Map(); const start = FULL * 2; dist.set(start, 0);
  const pq = [[0, start]];
  while (pq.length) {
    pq.sort((a, b) => a[0] - b[0]);
    const [d, s] = pq.shift();
    if (d > dist.get(s)) continue;
    const left = s >> 1, side = s & 1;
    if (left === 0) return d;
    const here = side === 0 ? left : FULL & ~left;
    for (let sub = here; sub; sub = (sub - 1) & here) {
      let cnt = 0, mx = 0;
      for (let i = 0; i < n; i++) if (sub >> i & 1) { cnt++; mx = Math.max(mx, times[i]); }
      if (cnt > cap) continue;
      const nl = side === 0 ? left & ~sub : left | sub;
      const t = nl * 2 + (1 - side);
      if (!dist.has(t) || d + mx < dist.get(t)) { dist.set(t, d + mx); pq.push([d + mx, t]); }
    }
  }
}
out.p03 = bridge([2, 3, 7, 9, 12, 20], 2);
out.p09 = bridge([2, 5, 7, 11, 13, 16, 20, 24], 3);
// p04 gondola
{
  const W = [92, 81, 68, 57, 44, 29], n = 6, FULL = 63;
  out.p04 = bfs([FULL, 0], String, ([l]) => l === 0, ([l, side]) => {
    const here = side === 0 ? l : FULL & ~l, r = [];
    for (let sub = here; sub; sub = (sub - 1) & here) {
      let c = 0, w = 0; for (let i = 0; i < n; i++) if (sub >> i & 1) { c++; w += W[i]; }
      if (c > 3 || w > 130 || sub === 32) continue; // 32 = Fifi alone
      r.push([side === 0 ? l & ~sub : l | sub, 1 - side]);
    }
    return r;
  });
}
// hanoi: state = array of pegs, each array bottom->top
function hanoi(start, goalPeg, allowed) {
  const key = (s) => s.map((p) => p.join('.')).join('|');
  const n = start.flat().length;
  return bfs(start, key, (s) => s[goalPeg].length === n, (s) => {
    const r = [];
    for (let a = 0; a < 3; a++) for (let b = 0; b < 3; b++) {
      if (a === b || !s[a].length) continue;
      if (allowed && !allowed(a, b)) continue;
      const d = s[a][s[a].length - 1];
      if (s[b].length && s[b][s[b].length - 1] < d) continue;
      const t = s.map((p) => p.slice()); t[a].pop(); t[b].push(d); r.push(t);
    }
    return r;
  });
}
out.p05 = hanoi([[5, 3], [4, 2, 1], []], 2);
out.p14 = hanoi([[4, 1], [], [3, 2]], 2, (a, b) => Math.abs(a - b) === 1);
// sliding
function sliding(start, goal, R, C) {
  return bfs(start, (s) => s.join(','), (s) => s.join(',') === goal.join(','), (s) => {
    const z = s.indexOf(0), r = Math.floor(z / C), c = z % C, res = [];
    for (const [dr, dc] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      const rr = r + dr, cc = c + dc; if (rr < 0 || rr >= R || cc < 0 || cc >= C) continue;
      const t = s.slice(); t[z] = t[rr * C + cc]; t[rr * C + cc] = 0; res.push(t);
    }
    return res;
  });
}
out.p06 = sliding([5, 2, 0, 4, 3, 1], [1, 2, 3, 4, 5, 0], 2, 3);
out.p13 = sliding([1, 4, 0, 7, 2, 6, 5, 8, 3], [1, 2, 3, 4, 5, 6, 7, 8, 0], 3, 3);
// lights 3x4
{
  const g = ['0110', '0110', '1000'].map((r) => [...r].map(Number));
  let best = Infinity;
  for (let m = 0; m < 1 << 12; m++) {
    const s = g.map((r) => r.slice());
    let cnt = 0;
    for (let i = 0; i < 12; i++) if (m >> i & 1) {
      cnt++; const r = Math.floor(i / 4), c = i % 4;
      for (const [dr, dc] of [[0, 0], [1, 0], [-1, 0], [0, 1], [0, -1]]) { const rr = r + dr, cc = c + dc; if (rr >= 0 && rr < 3 && cc >= 0 && cc < 4) s[rr][cc] ^= 1; }
    }
    if (s.every((r) => r.every((x) => x === 0))) best = Math.min(best, cnt);
  }
  out.p07 = best; // presses commute & pressing twice cancels, so subsets suffice
}
// p08 pour-only
{
  const C = [7, 11, 18];
  out.p08 = bfs([0, 0, 18], String, (s) => s.filter((x) => x === 9).length === 2, (s) => {
    const r = [];
    for (let i = 0; i < 3; i++) for (let j = 0; j < 3; j++) if (i !== j && s[i] > 0 && s[j] < C[j]) { const a = Math.min(s[i], C[j] - s[j]); const t = s.slice(); t[i] -= a; t[j] += a; r.push(t); }
    return r;
  });
}
// p10 pancakes (flip top k, k>=1 allowed here: k=1 is a no-op so it cannot shorten anything)
out.p10 = bfs([4, 1, 6, 3, 7, 2, 5], String, (s) => s.join() === '1,2,3,4,5,6,7', (s) => {
  const r = []; for (let k = 1; k <= s.length; k++) r.push(s.slice(0, k).reverse().concat(s.slice(k))); return r;
});
// p12 maze
{
  const g = ['###############', '#S....#.....#E#', '#.###.#.###.#B#', '#.#a#...#b#...#', '#.#.#####.#####', '#...#...A.....#', '###.#.#######.#', '#.....#.......#', '###############'];
  let sr, sc; g.forEach((row, r) => { const c = row.indexOf('S'); if (c >= 0) { sr = r; sc = c; } });
  out.p12 = bfs([sr, sc, ''], (s) => s.join(':'), ([r, c]) => g[r][c] === 'E', ([r, c, k]) => {
    const res = [];
    for (const [dr, dc] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      const rr = r + dr, cc = c + dc, ch = g[rr]?.[cc];
      if (!ch || ch === '#') continue;
      if ('AB'.includes(ch) && !k.includes(ch.toLowerCase())) continue;
      let nk = k; if ('ab'.includes(ch) && !k.includes(ch)) nk = [...k, ch].sort().join('');
      res.push([rr, cc, nk]);
    }
    return res;
  });
}
console.log(out);
const expected = { p01: 8, p02: 6, p03: 48, p04: 9, p05: 23, p06: 15, p07: 7, p08: 17, p09: 58, p10: 7, p12: 60, p13: 16, p14: 56 };
for (const [k, v] of Object.entries(expected)) if (out[k] !== v) { console.log('MISMATCH', k, out[k], v); process.exitCode = 1; }
console.log('done');
