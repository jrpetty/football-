// Spread solution "correct but naive": every rule right, one loop over the range per operation (typed arrays, so
// the loops are as fast as JavaScript gets). Fine for level 1; far too slow for the large inputs from level 2.
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
  const hi = Float64Array.from(a), lo = Float64Array.from(a);
  let count = 0, c = 0;
  const answer = (v) => { count++; c = (c * 1000003 + (((v % MOD) + MOD) % MOD)) % MOD; };
  function apply(op, l, r, x) {
    let i;
    switch (op) {
      case 'sum': { let s = 0; for (i = l; i <= r; i++) s += a[i]; answer(s); return; }
      case 'min': { let s = Infinity; for (i = l; i <= r; i++) if (a[i] < s) s = a[i]; answer(s); return; }
      case 'max': { let s = -Infinity; for (i = l; i <= r; i++) if (a[i] > s) s = a[i]; answer(s); return; }
      case 'hmax': { let s = -Infinity; for (i = l; i <= r; i++) if (hi[i] > s) s = hi[i]; answer(s); return; }
      case 'hmin': { let s = Infinity; for (i = l; i <= r; i++) if (lo[i] < s) s = lo[i]; answer(s); return; }
      case 'hreset': for (i = l; i <= r; i++) hi[i] = lo[i] = a[i]; return;
      case 'set': case 'assign': for (i = l; i <= r; i++) a[i] = x; break;
      case 'add': for (i = l; i <= r; i++) a[i] += x; break;
      case 'chmin': for (i = l; i <= r; i++) if (a[i] > x) a[i] = x; break;
      case 'chmax': for (i = l; i <= r; i++) if (a[i] < x) a[i] = x; break;
      case 'div': for (i = l; i <= r; i++) a[i] = Math.floor(a[i] / x); break;
    }
    for (i = l; i <= r; i++) { if (a[i] > hi[i]) hi[i] = a[i]; if (a[i] < lo[i]) lo[i] = a[i]; }
  }
  if (ops) {
    for (const o of ops) { if (o[0] === 'set') apply('set', o[1], o[1], o[2]); else apply(o[0], o[1], o[2], o[3]); }
  } else {
    let W = 0;
    for (const e of g.mix) W += e[1];
    for (let j = 0; j < g.q; j++) {
      let w = rand(W), e = g.mix[0];
      for (const m of g.mix) { if (w < m[1]) { e = m; break; } w -= m[1]; }
      if (e[0] === 'set') { const i = rand(n); apply('set', i, i, e[2] + rand(e[3] - e[2] + 1)); continue; }
      const u = rand(n), t = rand(n);
      apply(e[0], Math.min(u, t), Math.max(u, t), e.length > 2 ? e[2] + rand(e[3] - e[2] + 1) : 0);
    }
  }
  return [count, c];
}
