// Independent JavaScript re-computation of every reasoning.planning-extreme answer.
// Different state encodings / algorithms from plan_extreme.py. Run: node plan_extreme_crosscheck.mjs
const out = {};

class Heap { // binary min-heap of [cost, key]
  constructor() { this.a = []; }
  push(x) { const a = this.a; a.push(x); let i = a.length - 1; while (i > 0) { const p = (i - 1) >> 1; if (a[p][0] <= a[i][0]) break; [a[p], a[i]] = [a[i], a[p]]; i = p; } }
  pop() { const a = this.a; const top = a[0]; const last = a.pop(); if (a.length) { a[0] = last; let i = 0; for (;;) { const l = 2 * i + 1, r = l + 1; let m = i; if (l < a.length && a[l][0] < a[m][0]) m = l; if (r < a.length && a[r][0] < a[m][0]) m = r; if (m === i) break; [a[m], a[i]] = [a[i], a[m]]; i = m; } } return top; }
  get size() { return this.a.length; }
}
function dijkstra(start, isGoal, nbrs) {
  const dist = new Map([[start, 0]]); const h = new Heap(); h.push([0, start]);
  while (h.size) {
    const [d, s] = h.pop();
    if (d > dist.get(s)) continue;
    if (isGoal(s)) return d;
    for (const [t, c] of nbrs(s)) { const nd = d + c; if (!dist.has(t) || nd < dist.get(t)) { dist.set(t, nd); h.push([nd, t]); } }
  }
  return null;
}
function bfs(start, isGoal, nbrs) {
  const dist = new Map([[start, 0]]); let frontier = [start];
  while (frontier.length) {
    const next = [];
    for (const s of frontier) {
      if (isGoal(s)) return dist.get(s);
      for (const t of nbrs(s)) if (!dist.has(t)) { dist.set(t, dist.get(s) + 1); next.push(t); }
    }
    frontier = next;
  }
  return null;
}

// p01: shortest path over (week, stock) nodes.
{
  const dem = [30, 50, 20, 70, 40, 10, 60, 30], fee = 120, truck = 40, cap = 80, hold = 1, W = 100;
  let layer = new Map([[0, 0]]);
  for (const d of dem) {
    const nx = new Map();
    for (const [inv, c] of layer) for (let end = 0; end <= W; end++) {
      const q = end + d - inv; if (q < 0) continue;
      const cost = c + (q > 0 ? fee + truck * Math.ceil(q / cap) : 0) + hold * end;
      if (!nx.has(end) || cost < nx.get(end)) nx.set(end, cost);
    }
    layer = nx;
  }
  out.p01 = Math.min(...layer.values());
}

// p02: discrete jeep; state string "pos|tank|depots".
{
  const D = 9, C = 5, MAXD = 60;
  const start = '0|0|' + Array(D - 1).fill(0).join(',');
  out.p02 = dijkstra(start, (s) => s.startsWith(D + '|'), (s) => {
    const [ps, ts, cs] = s.split('|'); const pos = +ps, tank = +ts, dep = cs.split(',').map(Number);
    const res = [];
    const key = (p, t, dd) => `${p}|${t}|${dd.join(',')}`;
    if (pos === 0) for (let k = 1; tank + k <= C; k++) res.push([key(0, tank + k, dep), k]);
    if (pos > 0 && pos < D) {
      for (let k = 1; k <= tank; k++) if (dep[pos - 1] + k <= MAXD) { const dd = dep.slice(); dd[pos - 1] += k; res.push([key(pos, tank - k, dd), 0]); }
      for (let k = 1; k <= dep[pos - 1] && tank + k <= C; k++) { const dd = dep.slice(); dd[pos - 1] -= k; res.push([key(pos, tank + k, dd), 0]); }
    }
    if (tank > 0) { if (pos < D) res.push([key(pos + 1, tank - 1, dep), 0]); if (pos > 0) res.push([key(pos - 1, tank - 1, dep), 0]); }
    return res;
  });
}

// p03: bridge with lantern, 3 people, 200 kg.
{
  const t = [5, 12, 13, 15, 17, 20, 21, 23, 24], w = [81, 76, 84, 71, 65, 95, 91, 62, 51], n = 9, full = (1 << n) - 1;
  const groups = [];
  for (let m = 1; m <= full; m++) {
    let cnt = 0, wt = 0, tm = 0;
    for (let i = 0; i < n; i++) if (m >> i & 1) { cnt++; wt += w[i]; tm = Math.max(tm, t[i]); }
    if (cnt <= 3 && wt <= 200) groups.push([m, tm]);
  }
  out.p03 = dijkstra(full * 2, (s) => (s >> 1) === 0, (s) => {
    const left = s >> 1, side = s & 1, here = side === 0 ? left : full ^ left;
    return groups.filter(([m]) => (m & here) === m).map(([m, tm]) => [((left ^ m) << 1) | (1 - side), tm]);
  });
}

// p04: job shop. Machine sequences enumerated; each evaluated by longest path in the disjunctive graph.
{
  const jobs = { A: ['R', [[1, 2], [2, 2], [0, 2]]], B: ['K', [[2, 4], [0, 4], [1, 1]]], C: ['W', [[1, 3], [0, 1], [2, 4]]], D: ['W', [[0, 1], [2, 3], [1, 2]]], E: ['K', [[0, 3], [1, 2], [2, 5]]] };
  const ch = { WK: 4, KW: 6, WR: 2, RW: 5, KR: 3, RK: 2 };
  const setup = (a, b) => (a === b ? 0 : ch[a + b]);
  const names = Object.keys(jobs);
  const perms = (arr) => arr.length <= 1 ? [arr] : arr.flatMap((x, i) => perms([...arr.slice(0, i), ...arr.slice(i + 1)]).map((p) => [x, ...p]));
  const onMachine = [0, 1, 2].map((m) => names.filter((j) => jobs[j][1].some(([mm]) => mm === m)));
  // nodes: op ids "j:o"; arcs with weights; source weight for first paint op = setup(W, colour)
  const evalSeq = (seqs) => {
    const nodes = []; const dur = {}; const preds = {};
    for (const j of names) jobs[j][1].forEach(([m, d], o) => { const id = j + o; nodes.push(id); dur[id] = d; preds[id] = []; if (o > 0) preds[id].push([j + (o - 1), 0]); });
    const opOf = (j, m) => j + jobs[j][1].findIndex(([mm]) => mm === m);
    const release = {}; for (const id of nodes) release[id] = 0;
    seqs.forEach((seq, m) => seq.forEach((j, k) => {
      const id = opOf(j, m);
      if (k > 0) preds[id].push([opOf(seq[k - 1], m), m === 2 ? setup(jobs[seq[k - 1]][0], jobs[j][0]) : 0]);
      else if (m === 2) release[id] = setup('W', jobs[j][0]);
    }));
    // topological longest path (cycle => infeasible)
    const start = {}; const indeg = {}; const succ = {};
    for (const id of nodes) { indeg[id] = preds[id].length; succ[id] = []; }
    for (const id of nodes) for (const [p, s] of preds[id]) succ[p].push([id, s]);
    const queue = nodes.filter((id) => indeg[id] === 0); let seen = 0;
    for (const id of nodes) start[id] = release[id];
    while (queue.length) {
      const u = queue.pop(); seen++;
      for (const [v, s] of succ[u]) { start[v] = Math.max(start[v], start[u] + dur[u] + s); if (--indeg[v] === 0) queue.push(v); }
    }
    if (seen < nodes.length) return Infinity;
    return Math.max(...nodes.map((id) => start[id] + dur[id]));
  };
  let best = Infinity;
  for (const a of perms(onMachine[0])) for (const b of perms(onMachine[1])) for (const c of perms(onMachine[2])) best = Math.min(best, evalSeq([a, b, c]));
  out.p04 = best;
}

// p05: couples with island; location of each person as a base-3 digit, boat location appended.
{
  const n = 8, wives = [1, 3, 5, 7];
  const safe = (group) => { const men = new Set(group.filter((p) => p % 2 === 0).map((p) => p >> 1)); return group.every((p) => p % 2 === 0 || men.size === 0 || men.has(p >> 1)); };
  const enc = (loc, boat) => loc.join('') + boat;
  out.p05 = bfs(enc(Array(n).fill(0), 0), (s) => s === '222222222', (s) => {
    const loc = s.slice(0, n).split('').map(Number), boat = +s[n], res = [];
    const here = [...Array(n).keys()].filter((p) => loc[p] === boat);
    const groups = [];
    for (let i = 0; i < here.length; i++) { groups.push([here[i]]); for (let j = i + 1; j < here.length; j++) groups.push([here[i], here[j]]); }
    for (const g of groups) {
      if (!g.some((p) => wives.includes(p)) || !safe(g)) continue;
      for (let dest = 0; dest < 3; dest++) {
        if (dest === boat) continue;
        const nl = loc.slice(); for (const p of g) nl[p] = dest;
        let ok = true;
        for (let L = 0; L < 3 && ok; L++) ok = safe([...Array(n).keys()].filter((p) => nl[p] === L));
        if (ok) res.push(enc(nl, dest));
      }
    }
    return res;
  });
}

// p06: rush hour on a 36-character grid string.
{
  const start = ['G.BBBE', 'GDDD.E', 'XXH.I.', '..HAIC', 'F.KAIC', 'F.KAJJ'].join('');
  out.p06 = bfs(start, (s) => s[16] === 'X' && s[17] === 'X', (s) => {
    const res = [];
    const seen = new Set();
    for (let i = 0; i < 36; i++) {
      const c = s[i]; if (c === '.' || seen.has(c)) continue; seen.add(c);
      const cells = []; for (let k = 0; k < 36; k++) if (s[k] === c) cells.push(k);
      const horiz = Math.floor(cells[0] / 6) === Math.floor(cells[cells.length - 1] / 6);
      const step = horiz ? 1 : 6;
      for (const dir of [-1, 1]) {
        let arr = s.split(''); let cur = cells.slice();
        for (;;) {
          const lead = dir < 0 ? cur[0] - step : cur[cur.length - 1] + step;
          if (lead < 0 || lead >= 36) break;
          if (horiz && Math.floor(lead / 6) !== Math.floor(cur[0] / 6)) break;
          if (arr[lead] !== '.') break;
          const tail = dir < 0 ? cur[cur.length - 1] : cur[0];
          arr[lead] = c; arr[tail] = '.';
          cur = cur.map((x) => x + dir * step);
          res.push(arr.join(''));
        }
      }
    }
    return res;
  });
}

// p07: token swapping on the wheel.
{
  const E = []; for (let i = 1; i <= 8; i++) { E.push([0, i]); E.push([i, (i % 8) + 1]); }
  out.p07 = bfs('078123456', (s) => s === '012345678', (s) => E.map(([a, b]) => { const l = s.split(''); [l[a], l[b]] = [l[b], l[a]]; return l.join(''); }));
}

// p08: clockwise rotors; searched backwards from the goal with anticlockwise moves.
{
  const blocks = [[0, 1, 4, 3], [1, 2, 5, 4], [3, 4, 7, 6], [4, 5, 8, 7]];
  const anti = (s, [a, b, c, d]) => { const l = s.split(''); l[a] = s[b]; l[b] = s[c]; l[c] = s[d]; l[d] = s[a]; return l.join(''); };
  out.p08 = bfs('123456789', (s) => s === '213456789', (s) => blocks.map((b) => anti(s, b)));
}

// p09: burnt pancakes, searched backwards from the goal (a flip is its own inverse).
{
  const flip = (s, k) => [...s.slice(0, k).reverse().map((x) => -x), ...s.slice(k)];
  const key = (a) => a.join(',');
  const target = key([1, -2, 3, -4, 5, -6]);
  out.p09 = bfs(key([1, 2, 3, 4, 5, 6]), (s) => s === target, (s) => { const a = s.split(',').map(Number); return [1, 2, 3, 4, 5, 6].map((k) => key(flip(a, k))); });
}

// p10: coloured sliding tiles, searched backwards from the goal.
{
  out.p10 = bfs('RRRRYYY_BBBB', (s) => s === 'BRRRYYY_RBBB', (s) => {
    const i = s.indexOf('_'), r = Math.floor(i / 4), c = i % 4, res = [];
    for (const [dr, dc] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
      const rr = r + dr, cc = c + dc; if (rr < 0 || rr > 2 || cc < 0 || cc > 3) continue;
      const j = rr * 4 + cc, l = s.split(''); [l[i], l[j]] = [l[j], l[i]]; res.push(l.join(''));
    }
    return res;
  });
}

const expected = { p01: 770, p02: 33, p03: 149, p04: 23, p05: 22, p06: 19, p07: 10, p08: 9, p09: 9, p10: 16 };
let bad = 0;
for (const [k, v] of Object.entries(expected)) { const ok = out[k] === v; if (!ok) bad++; console.log(k, out[k], ok ? 'OK' : `MISMATCH (expected ${v})`); }
process.exit(bad ? 1 : 0);
