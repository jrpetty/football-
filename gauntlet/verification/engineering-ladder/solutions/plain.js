// Spread solution "plain, model-style": what a competent engineer writes without the specialist technique. A
// standard lazy segment tree (typed arrays, add and assign tags, sum/min/max) that is fast for set/add/assign and all
// queries; chmin, chmax and div descend until a node is unaffected or holds one value (then it becomes an assign),
// so they cost as much as the number of elements they change; historic max/min are kept per element, so every update
// descends to the elements it touches. Every rule is right: it scores 100% wherever the inputs let it finish in time.
function runEngine(input) {
  const MOD = 1000000007;
  let a, ops = null, n, rand = null, g = input.generate;
  if (g) {
    n = g.n;
    let state = g.seed;
    rand = (k) => (state = (state * 48271) % 2147483647) % k;
    a = new Float64Array(n);
    for (let i = 0; i < n; i++) a[i] = g.lo + rand(g.hi - g.lo + 1);
  } else {
    a = Float64Array.from(input.values);
    n = a.length;
    ops = input.ops;
  }
  const histNeeded = ops ? ops.some((o) => o[0] === 'hmax' || o[0] === 'hmin') : g.mix.some((e) => e[0] === 'hmax' || e[0] === 'hmin');
  const size = 4 * n;
  const sum = new Float64Array(size), mx = new Float64Array(size), mn = new Float64Array(size);
  const hx = new Float64Array(size), hn = new Float64Array(size); // historic max/min of the range (exact at leaves)
  const add = new Float64Array(size), asg = new Float64Array(size), hasAsg = new Uint8Array(size);

  function pull(v) {
    const L = 2 * v, R = L + 1;
    sum[v] = sum[L] + sum[R];
    mx[v] = Math.max(mx[L], mx[R]); mn[v] = Math.min(mn[L], mn[R]);
    hx[v] = Math.max(hx[L], hx[R]); hn[v] = Math.min(hn[L], hn[R]);
  }
  function build(v, l, r) {
    if (l === r) { sum[v] = mx[v] = mn[v] = hx[v] = hn[v] = a[l]; return; }
    const m = (l + r) >> 1;
    build(2 * v, l, m); build(2 * v + 1, m + 1, r); pull(v);
  }
  function applyAssign(v, len, x) { sum[v] = x * len; mx[v] = mn[v] = x; asg[v] = x; hasAsg[v] = 1; add[v] = 0; }
  function applyAdd(v, len, x) { sum[v] += x * len; mx[v] += x; mn[v] += x; if (hasAsg[v]) asg[v] += x; else add[v] += x; }
  function push(v, l, r) {
    const m = (l + r) >> 1, L = 2 * v, R = L + 1;
    if (hasAsg[v]) { applyAssign(L, m - l + 1, asg[v]); applyAssign(R, r - m, asg[v]); hasAsg[v] = 0; }
    if (add[v] !== 0) { applyAdd(L, m - l + 1, add[v]); applyAdd(R, r - m, add[v]); add[v] = 0; }
  }
  function leafSet(v, y) { sum[v] = mx[v] = mn[v] = y; if (y > hx[v]) hx[v] = y; if (y < hn[v]) hn[v] = y; }

  // kind: 0 add, 1 assign, 2 chmin, 3 chmax, 4 div, 5 hreset
  function update(v, l, r, ql, qr, kind, x) {
    if (qr < l || r < ql) return;
    if (kind === 2 && mx[v] <= x) return;
    if (kind === 3 && mn[v] >= x) return;
    if (ql <= l && r <= qr && !histNeeded) {
      const len = r - l + 1;
      if (kind === 0) { applyAdd(v, len, x); return; }
      if (kind === 1) { applyAssign(v, len, x); return; }
      if (kind !== 5 && mx[v] === mn[v]) {
        const y = kind === 2 ? Math.min(mx[v], x) : kind === 3 ? Math.max(mx[v], x) : Math.floor(mx[v] / x);
        applyAssign(v, len, y); return;
      }
    }
    if (l === r) {
      if (kind === 5) { hx[v] = hn[v] = mx[v]; return; }
      const y = kind === 0 ? mx[v] + x : kind === 1 ? x : kind === 2 ? Math.min(mx[v], x) : kind === 3 ? Math.max(mx[v], x) : Math.floor(mx[v] / x);
      leafSet(v, y);
      return;
    }
    push(v, l, r);
    const m = (l + r) >> 1;
    update(2 * v, l, m, ql, qr, kind, x); update(2 * v + 1, m + 1, r, ql, qr, kind, x); pull(v);
  }
  // kind: 0 sum, 1 min, 2 max, 3 hmax, 4 hmin
  function query(v, l, r, ql, qr, kind) {
    if (ql <= l && r <= qr) return kind === 0 ? sum[v] : kind === 1 ? mn[v] : kind === 2 ? mx[v] : kind === 3 ? hx[v] : hn[v];
    push(v, l, r);
    const m = (l + r) >> 1;
    if (qr <= m) return query(2 * v, l, m, ql, qr, kind);
    if (ql > m) return query(2 * v + 1, m + 1, r, ql, qr, kind);
    const x = query(2 * v, l, m, ql, qr, kind), y = query(2 * v + 1, m + 1, r, ql, qr, kind);
    return kind === 0 ? x + y : kind === 1 || kind === 4 ? Math.min(x, y) : Math.max(x, y);
  }

  build(1, 0, n - 1);
  let count = 0, c = 0;
  const U = { add: 0, assign: 1, chmin: 2, chmax: 3, div: 4, hreset: 5 }, Q = { sum: 0, min: 1, max: 2, hmax: 3, hmin: 4 };
  function run(op, l, r, x) {
    if (op === 'set') { update(1, 0, n - 1, l, l, 1, x); return; }
    if (op in U) { update(1, 0, n - 1, l, r, U[op], x); return; }
    const v = query(1, 0, n - 1, l, r, Q[op]);
    count++;
    c = (c * 1000003 + (((v % MOD) + MOD) % MOD)) % MOD;
  }
  if (ops) {
    for (const o of ops) { if (o[0] === 'set') run('set', o[1], o[1], o[2]); else run(o[0], o[1], o[2], o[3]); }
  } else {
    let W = 0;
    for (const e of g.mix) W += e[1];
    for (let j = 0; j < g.q; j++) {
      let w = rand(W), e = g.mix[0];
      for (const m of g.mix) { if (w < m[1]) { e = m; break; } w -= m[1]; }
      if (e[0] === 'set') { const i = rand(n); run('set', i, i, e[2] + rand(e[3] - e[2] + 1)); continue; }
      const u = rand(n), t = rand(n);
      run(e[0], Math.min(u, t), Math.max(u, t), e.length > 2 ? e[2] + rand(e[3] - e[2] + 1) : 0);
    }
  }
  return [count, c];
}
