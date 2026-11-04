// Method 2 for Exact Maths: Ladder: parse a board out of the prompt text and solve it exactly in BigInt rationals
// (backward recursion with every square's value an affine function of the snake-tail unknowns, then a small
// Gauss-Jordan elimination for those unknowns). Used by verify.mjs and by the regression tests.
// ── exact rationals (BigInt) ───────────────────────────────────────────────────────────────────────
const gcd = (a, b) => { a = a < 0n ? -a : a; b = b < 0n ? -b : b; while (b) [a, b] = [b, a % b]; return a; };
const R = (p, q = 1n) => { if (q < 0n) { p = -p; q = -q; } const g = gcd(p, q) || 1n; return { p: p / g, q: q / g }; };
const add = (x, y) => R(x.p * y.q + y.p * x.q, x.q * y.q);
const sub = (x, y) => R(x.p * y.q - y.p * x.q, x.q * y.q);
const mul = (x, y) => R(x.p * y.p, x.q * y.q);
const div = (x, y) => { if (y.p === 0n) throw new Error('division by zero'); return R(x.p * y.q, x.q * y.p); };
const ZERO = R(0n), ONE = R(1n);
const isZero = (x) => x.p === 0n;

// ── the board, parsed from the prompt ──────────────────────────────────────────────────────────────
export function parseBoard(prompt) {
  const N = Number(/squares numbered 0 to (\d+)\./.exec(prompt)[1]);
  const d = /numbered 1 and 2/.test(prompt) ? 2 : Number(/spinner numbered 1 to (\d+)/.exec(prompt)[1]);
  const pairs = (label) => {
    const line = new RegExp(`- ${label} \\([a-z]+ → [a-z]+\\): (.*)`).exec(prompt)[1].trim();
    return line === 'none' ? [] : line.split(',').map((s) => s.split('→').map((x) => Number(x.trim())));
  };
  const traps = /- Traps: (.*)/.exec(prompt)[1].split(',').map((x) => Number(x.trim()));
  return { N, d, ladders: new Map(pairs('Ladders')), snakes: new Map(pairs('Snakes')), traps: new Set(traps) };
}

// ── method 2: affine backward recursion ────────────────────────────────────────────────────────────
export function winProbability({ N, d, ladders, snakes, traps }) {
  const tails = [...new Set(snakes.values())].sort((a, b) => a - b);
  const k = tails.length;
  const unknown = new Map(tails.map((t, i) => [t, i]));
  // value[s] = [c0, c1..ck]: P(s) = c0 + sum ci * x_i, where x_i = P(tail_i)
  const value = new Map();
  const constant = (c) => [c, ...Array(k).fill(ZERO)];
  const variable = (i) => { const v = constant(ZERO); v[1 + i] = ONE; return v; };
  const restingValue = (s) => (unknown.has(s) ? variable(unknown.get(s)) : value.get(s));
  const landing = (t) => {
    if (t >= N) return constant(ONE);
    if (traps.has(t)) return constant(ZERO);
    if (ladders.has(t)) return restingValue(ladders.get(t));
    if (snakes.has(t)) return variable(unknown.get(snakes.get(t)));
    return restingValue(t);
  };
  const equations = [];
  const inv = R(1n, BigInt(d));
  for (let s = N - 1; s >= 0; s--) {
    if (traps.has(s) || ladders.has(s) || snakes.has(s)) continue;
    let v = constant(ZERO);
    for (let r = 1; r <= d; r++) {
      const w = landing(s + r);
      if (!w) throw new Error(`square ${s + r} used before it was computed`);
      v = v.map((c, i) => add(c, mul(inv, w[i])));
    }
    value.set(s, v);
    if (unknown.has(s)) equations.push([unknown.get(s), v]);
  }
  // x_i = c0 + sum cj x_j  ->  x_i - sum cj x_j = c0
  const A = equations.map(([i, v]) => {
    const row = Array.from({ length: k }, (_, j) => (j === i ? sub(ONE, v[1 + j]) : sub(ZERO, v[1 + j])));
    return [...row, v[0]];
  });
  for (let c = 0; c < k; c++) {
    const p = A.findIndex((row, r) => r >= c && !isZero(row[c]));
    if (p < 0) throw new Error('singular system');
    [A[c], A[p]] = [A[p], A[c]];
    const pv = A[c][c];
    A[c] = A[c].map((x) => div(x, pv));
    for (let r = 0; r < k; r++) if (r !== c && !isZero(A[r][c])) { const f = A[r][c]; A[r] = A[r].map((x, j) => sub(x, mul(f, A[c][j]))); }
  }
  // after Gauss-Jordan, row c holds the value of unknown c
  const solved = A.map((row) => row[k]);
  const v0 = restingValue(0);
  let p = v0[0];
  for (let i = 0; i < k; i++) p = add(p, mul(v0[1 + i], solved[i]));
  return p;
}

