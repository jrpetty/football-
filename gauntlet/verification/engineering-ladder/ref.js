// Reference solution for Engineering: Ladder (coding.engineering-ladder), every level.
//
// The level-10 specification is a superset of every lower level's (each level only adds operations), so this one
// implementation is the reference for all ten levels.
//
// Design: a "segment tree beats" (Ji Ruyi) over blocks of 64 elements. Every node keeps sum, max, strict second max
// and its count, min, strict second min and its count, and the historic max/min of its range. Elements fall into
// three classes per node (equal to the node's max, equal to its min, the others), and every update is a class-wise
// addition: chmin/chmax touch only the max (min) class whenever x lies above the second max (below the second min);
// floor division is a uniform addition whenever every element of a node changes by the same amount (floor(v/x) - v
// is monotone in v, so checking the node's min and max is enough); assign is chmin then chmax; set is assign on one
// index. Historic max/min are carried as per-class "largest/smallest prefix of the pending additions"; hreset cuts
// those prefixes (the history restarts from the current value, and a flag tells the children to drop theirs).
// The leaves are blocks of raw elements: a partially covered block is pushed into its elements, updated element by
// element and summarised again. Nodes are laid out in Euler-tour order (2 * blocks - 1 of them).
function runEngine(input) {
  // Globals copied into locals: inside the test sandbox (a vm context) every global lookup (Math, Float64Array...) is slow.
  const INF = 1 / 0, MOD = 1000000007, floor = Math.floor;
  const F64 = Float64Array, I32 = Int32Array, U8 = Uint8Array;
  const max = (x, y) => (x > y ? x : y), min = (x, y) => (x < y ? x : y);
  let A, ops = null, gen = null, n, rand = null;
  if (input.generate) {
    gen = input.generate;
    n = gen.n;
    let state = gen.seed;
    rand = (k) => (state = (state * 48271) % 2147483647) % k;
    A = new F64(n);
    for (let i = 0; i < n; i++) A[i] = gen.lo + rand(gen.hi - gen.lo + 1);
  } else {
    A = F64.from(input.values);
    n = A.length;
    ops = input.ops;
  }
  const HI = F64.from(A), LO = F64.from(A); // per-element historic max / min
  // Histories only matter if a history is ever asked for (hmax/hmin); without, they are not maintained.
  const HIST = ops ? ops.some((o) => o[0] === 'hmax' || o[0] === 'hmin') : gen.mix.some((e) => e[0] === 'hmax' || e[0] === 'hmin');

  const SH = 6, B = 1 << SH; // block size 64
  const nb = ((n - 1) >> SH) + 1;
  const M = 2 * nb;
  const sum = new F64(M), mx = new F64(M), smx = new F64(M), mn = new F64(M), smn = new F64(M);
  const cmx = new I32(M), cmn = new I32(M), hmx = new F64(M), hmn = new F64(M);
  // pending tags for the children (or the block's elements), per class (1 = max, 2 = min, 3 = others)
  const a1 = new F64(M), a2 = new F64(M), a3 = new F64(M);
  const h1 = new F64(M), h2 = new F64(M), h3 = new F64(M);
  const k1 = new F64(M), k2 = new F64(M), k3 = new F64(M);
  const rst = new U8(M), dirty = new U8(M);

  // ---------- blocks (leaves) ----------
  function blockPull(v, b) {
    const s = b << SH, e = min(n, s + B);
    let S = 0, X = -INF, SX = -INF, CX = 0, N = INF, SN = INF, CN = 0, HX = -INF, HN = INF;
    if (!HIST) {
      for (let i = s; i < e; i++) {
        const x = A[i];
        S += x;
        if (x > X) { SX = X; X = x; CX = 1; } else if (x === X) CX++; else if (x > SX) SX = x;
        if (x < N) { SN = N; N = x; CN = 1; } else if (x === N) CN++; else if (x < SN) SN = x;
      }
      sum[v] = S; mx[v] = X; smx[v] = SX; cmx[v] = CX; mn[v] = N; smn[v] = SN; cmn[v] = CN; hmx[v] = X; hmn[v] = N;
      return;
    }
    for (let i = s; i < e; i++) {
      const x = A[i];
      S += x;
      if (x > X) { SX = X; X = x; CX = 1; } else if (x === X) CX++; else if (x > SX) SX = x;
      if (x < N) { SN = N; N = x; CN = 1; } else if (x === N) CN++; else if (x < SN) SN = x;
      if (HI[i] > HX) HX = HI[i];
      if (LO[i] < HN) HN = LO[i];
    }
    sum[v] = S; mx[v] = X; smx[v] = SX; cmx[v] = CX; mn[v] = N; smn[v] = SN; cmn[v] = CN; hmx[v] = HX; hmn[v] = HN;
  }
  function blockPush(v, b) {
    if (!dirty[v]) return;
    const s = b << SH, e = min(n, s + B);
    const oldX = mx[v] - a1[v], oldN = mn[v] - a2[v], cut = rst[v] === 1;
    if (!HIST) { // no history asked for anywhere in this input: only the values matter
      const d1 = a1[v], d2 = a2[v], d3 = a3[v];
      if (d1 === d2 && d2 === d3) { if (d1 !== 0) for (let i = s; i < e; i++) A[i] += d1; }
      else for (let i = s; i < e; i++) { const x = A[i]; A[i] = x + (x === oldX ? d1 : x === oldN ? d2 : d3); }
      a1[v] = a2[v] = a3[v] = h1[v] = h2[v] = h3[v] = k1[v] = k2[v] = k3[v] = 0;
      rst[v] = 0; dirty[v] = 0;
      return;
    }
    for (let i = s; i < e; i++) {
      const x = A[i];
      let d, hh, kk;
      if (x === oldX) { d = a1[v]; hh = h1[v]; kk = k1[v]; }
      else if (x === oldN) { d = a2[v]; hh = h2[v]; kk = k2[v]; }
      else { d = a3[v]; hh = h3[v]; kk = k3[v]; }
      if (cut) { HI[i] = x + hh; LO[i] = x + kk; }
      else { if (x + hh > HI[i]) HI[i] = x + hh; if (x + kk < LO[i]) LO[i] = x + kk; }
      A[i] = x + d;
    }
    a1[v] = a2[v] = a3[v] = h1[v] = h2[v] = h3[v] = k1[v] = k2[v] = k3[v] = 0;
    rst[v] = 0; dirty[v] = 0;
  }

  // ---------- internal nodes ----------
  function pull(v, L, R) {
    sum[v] = sum[L] + sum[R];
    if (mx[L] > mx[R]) { mx[v] = mx[L]; cmx[v] = cmx[L]; smx[v] = max(smx[L], mx[R]); }
    else if (mx[L] < mx[R]) { mx[v] = mx[R]; cmx[v] = cmx[R]; smx[v] = max(mx[L], smx[R]); }
    else { mx[v] = mx[L]; cmx[v] = cmx[L] + cmx[R]; smx[v] = max(smx[L], smx[R]); }
    if (mn[L] < mn[R]) { mn[v] = mn[L]; cmn[v] = cmn[L]; smn[v] = min(smn[L], mn[R]); }
    else if (mn[L] > mn[R]) { mn[v] = mn[R]; cmn[v] = cmn[R]; smn[v] = min(mn[L], smn[R]); }
    else { mn[v] = mn[L]; cmn[v] = cmn[L] + cmn[R]; smn[v] = min(smn[L], smn[R]); }
    hmx[v] = max(hmx[L], hmx[R]);
    hmn[v] = min(hmn[L], hmn[R]);
  }
  function build(v, l, r) {
    if (l === r) { blockPull(v, l); return; }
    const m = (l + r) >> 1, L = v + 1, R = v + 2 * (m - l + 1);
    build(L, l, m); build(R, m + 1, r); pull(v, L, R);
  }
  const lenOf = (l, r) => min(n, (r + 1) << SH) - (l << SH);

  // Apply tags to node v (len elements): (d, e, f) per class = addition, largest prefix, smallest prefix; cut = the
  // history restarts (an hreset above). A one-valued node takes class 1's tags for everything.
  function apply(v, len, d1, d2, d3, e1, e2, e3, f1, f2, f3, cut) {
    if (mx[v] === mn[v]) {
      const x = mx[v];
      hmx[v] = cut ? x + e1 : max(hmx[v], x + e1);
      hmn[v] = cut ? x + f1 : min(hmn[v], x + f1);
      sum[v] += d1 * len; mx[v] = mn[v] = x + d1;
      d2 = d3 = d1; e2 = e3 = e1; f2 = f3 = f1;
    } else {
      const three = smx[v] > mn[v]; // there are elements that are neither the max nor the min
      const hc = max(max(mx[v] + e1, mn[v] + e2), three ? smx[v] + e3 : -INF);
      const lc = min(min(mn[v] + f2, mx[v] + f1), three ? smn[v] + f3 : INF);
      hmx[v] = cut ? hc : max(hmx[v], hc);
      hmn[v] = cut ? lc : min(hmn[v], lc);
      sum[v] += d1 * cmx[v] + d2 * cmn[v] + d3 * (len - cmx[v] - cmn[v]);
      smx[v] += three ? d3 : d2;
      smn[v] += smn[v] < mx[v] ? d3 : d1;
      mx[v] += d1; mn[v] += d2;
    }
    if (cut) {
      h1[v] = a1[v] + e1; h2[v] = a2[v] + e2; h3[v] = a3[v] + e3;
      k1[v] = a1[v] + f1; k2[v] = a2[v] + f2; k3[v] = a3[v] + f3;
      rst[v] = 1;
    } else {
      h1[v] = max(h1[v], a1[v] + e1); h2[v] = max(h2[v], a2[v] + e2); h3[v] = max(h3[v], a3[v] + e3);
      k1[v] = min(k1[v], a1[v] + f1); k2[v] = min(k2[v], a2[v] + f2); k3[v] = min(k3[v], a3[v] + f3);
    }
    a1[v] += d1; a2[v] += d2; a3[v] += d3;
    dirty[v] = 1;
  }
  function pushChild(c, len, pmx, pmn, v, cut) {
    if (mx[c] === mn[c]) {
      if (mx[c] === pmx) apply(c, len, a1[v], 0, 0, h1[v], 0, 0, k1[v], 0, 0, cut);
      else if (mn[c] === pmn) apply(c, len, a2[v], 0, 0, h2[v], 0, 0, k2[v], 0, 0, cut);
      else apply(c, len, a3[v], 0, 0, h3[v], 0, 0, k3[v], 0, 0, cut);
      return;
    }
    const top = mx[c] === pmx, bot = mn[c] === pmn;
    apply(c, len,
      top ? a1[v] : a3[v], bot ? a2[v] : a3[v], a3[v],
      top ? h1[v] : h3[v], bot ? h2[v] : h3[v], h3[v],
      top ? k1[v] : k3[v], bot ? k2[v] : k3[v], k3[v], cut);
  }
  function push(v, l, r) {
    if (!dirty[v]) return;
    const m = (l + r) >> 1, L = v + 1, R = v + 2 * (m - l + 1);
    const cut = rst[v] === 1;
    if (a1[v] === a2[v] && a2[v] === a3[v] && h1[v] === h2[v] && h2[v] === h3[v] && k1[v] === k2[v] && k2[v] === k3[v]) {
      applyU(L, lenOf(l, m), a1[v], h1[v], k1[v], cut);
      applyU(R, lenOf(m + 1, r), a1[v], h1[v], k1[v], cut);
    } else {
      const pmx = max(mx[L], mx[R]), pmn = min(mn[L], mn[R]);
      pushChild(L, lenOf(l, m), pmx, pmn, v, cut);
      pushChild(R, lenOf(m + 1, r), pmx, pmn, v, cut);
    }
    a1[v] = a2[v] = a3[v] = h1[v] = h2[v] = h3[v] = k1[v] = k2[v] = k3[v] = 0;
    rst[v] = 0; dirty[v] = 0;
  }
  // The same tags for every class (the common case: plain additions): the node's max/min stay its max/min.
  function applyU(v, len, d, e, f, cut) {
    hmx[v] = cut ? mx[v] + e : max(hmx[v], mx[v] + e);
    hmn[v] = cut ? mn[v] + f : min(hmn[v], mn[v] + f);
    sum[v] += d * len; mx[v] += d; mn[v] += d; smx[v] += d; smn[v] += d;
    if (cut) {
      h1[v] = a1[v] + e; h2[v] = a2[v] + e; h3[v] = a3[v] + e;
      k1[v] = a1[v] + f; k2[v] = a2[v] + f; k3[v] = a3[v] + f;
      rst[v] = 1;
    } else {
      h1[v] = max(h1[v], a1[v] + e); h2[v] = max(h2[v], a2[v] + e); h3[v] = max(h3[v], a3[v] + e);
      k1[v] = min(k1[v], a1[v] + f); k2[v] = min(k2[v], a2[v] + f); k3[v] = min(k3[v], a3[v] + f);
    }
    a1[v] += d; a2[v] += d; a3[v] += d;
    dirty[v] = 1;
  }
  const addAll = (v, len, d) => applyU(v, len, d, d > 0 ? d : 0, d < 0 ? d : 0, false);

  // ---------- updates and queries ----------
  // op codes: 0 add, 1 chmin, 2 chmax, 3 div, 4 hreset; queries 0 sum, 1 min, 2 max, 3 hmax, 4 hmin
  let ql = 0, qr = 0, qx = 0, qop = 0;
  function tryTag(v, len) { // a fully covered node: true if the update was applied as a tag (or changes nothing)
    switch (qop) {
      case 0: addAll(v, len, qx); return true;
      case 1:
        if (mx[v] <= qx) return true;
        if (smx[v] < qx) { const d = qx - mx[v]; if (mx[v] === mn[v]) addAll(v, len, d); else apply(v, len, d, 0, 0, 0, 0, 0, d, 0, 0, false); return true; }
        return false;
      case 2:
        if (mn[v] >= qx) return true;
        if (smn[v] > qx) { const d = qx - mn[v]; if (mx[v] === mn[v]) addAll(v, len, d); else apply(v, len, 0, d, 0, 0, d, 0, 0, 0, 0, false); return true; }
        return false;
      case 3: {
        const dHi = floor(mx[v] / qx) - mx[v], dLo = floor(mn[v] / qx) - mn[v];
        if (dHi === dLo) { if (dHi !== 0) addAll(v, len, dHi); return true; }
        return false;
      }
      default:
        hmx[v] = mx[v]; hmn[v] = mn[v];
        h1[v] = k1[v] = a1[v]; h2[v] = k2[v] = a2[v]; h3[v] = k3[v] = a3[v];
        rst[v] = 1; dirty[v] = 1;
        return true;
    }
  }
  function element(i) {
    const x = A[i];
    let y;
    switch (qop) {
      case 0: y = x + qx; break;
      case 1: y = x < qx ? x : qx; break;
      case 2: y = x > qx ? x : qx; break;
      case 3: y = floor(x / qx); break;
      default: HI[i] = LO[i] = x; return;
    }
    A[i] = y;
    if (HIST) { if (y > HI[i]) HI[i] = y; if (y < LO[i]) LO[i] = y; }
  }
  function update(v, l, r) {
    const s = l << SH, e = min(n, (r + 1) << SH) - 1;
    if (qr < s || e < ql) return;
    if ((qop === 1 && mx[v] <= qx) || (qop === 2 && mn[v] >= qx)) return;
    if (ql <= s && e <= qr && tryTag(v, e - s + 1)) return;
    if (l === r) {
      blockPush(v, l);
      const from = max(s, ql), to = min(e, qr);
      for (let i = from; i <= to; i++) element(i);
      blockPull(v, l);
      return;
    }
    push(v, l, r);
    const m = (l + r) >> 1, L = v + 1, R = v + 2 * (m - l + 1);
    update(L, l, m); update(R, m + 1, r); pull(v, L, R);
  }
  let qk = 0;
  function query(v, l, r) {
    const s = l << SH, e = min(n, (r + 1) << SH) - 1;
    if (ql <= s && e <= qr) return qk === 0 ? sum[v] : qk === 1 ? mn[v] : qk === 2 ? mx[v] : qk === 3 ? hmx[v] : hmn[v];
    if (l === r) {
      blockPush(v, l);
      const from = max(s, ql), to = min(e, qr);
      let acc = qk === 0 ? 0 : qk === 1 || qk === 4 ? INF : -INF;
      for (let i = from; i <= to; i++) {
        const x = qk === 3 ? HI[i] : qk === 4 ? LO[i] : A[i];
        if (qk === 0) acc += x; else if (qk === 1 || qk === 4) { if (x < acc) acc = x; } else if (x > acc) acc = x;
      }
      return acc;
    }
    push(v, l, r);
    const m = (l + r) >> 1, L = v + 1, R = v + 2 * (m - l + 1);
    const mid = (m + 1) << SH;
    if (qr < mid) return query(L, l, m);
    if (ql >= mid) return query(R, m + 1, r);
    const x = query(L, l, m), y = query(R, m + 1, r);
    return qk === 0 ? x + y : qk === 1 || qk === 4 ? min(x, y) : max(x, y);
  }

  build(0, 0, nb - 1);
  let count = 0, h = 0;
  const UPD = { add: 0, chmin: 1, chmax: 2, div: 3, hreset: 4 };
  const QRY = { sum: 0, min: 1, max: 2, hmax: 3, hmin: 4 };
  function run(op, l, r, x) {
    ql = l; qr = r; qx = x;
    if (op === 'set' || op === 'assign') { qop = 1; update(0, 0, nb - 1); qop = 2; update(0, 0, nb - 1); return; }
    const u = UPD[op];
    if (u !== undefined) { qop = u; update(0, 0, nb - 1); return; }
    qk = QRY[op];
    const ans = query(0, 0, nb - 1);
    count++;
    h = (h * 1000003 + (((ans % MOD) + MOD) % MOD)) % MOD;
  }
  if (ops) {
    for (const o of ops) {
      if (o[0] === 'set') run('set', o[1], o[1], o[2]);
      else run(o[0], o[1], o[2], o[3]);
    }
  } else {
    const mix = gen.mix;
    let W = 0;
    for (const e of mix) W += e[1];
    for (let j = 0; j < gen.q; j++) {
      let w = rand(W), e = mix[0];
      for (const c of mix) { if (w < c[1]) { e = c; break; } w -= c[1]; }
      if (e[0] === 'set') { const i = rand(n); run('set', i, i, e[2] + rand(e[3] - e[2] + 1)); continue; }
      const u = rand(n), t = rand(n);
      const l = u < t ? u : t, r = u < t ? t : u;
      run(e[0], l, r, e.length > 2 ? e[2] + rand(e[3] - e[2] + 1) : 0);
    }
  }
  return [count, h];
}
