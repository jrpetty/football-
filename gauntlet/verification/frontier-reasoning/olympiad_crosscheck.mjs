// Independent JavaScript re-computation of the brute-forceable math.olympiad answers.
// Run: node olympiad_crosscheck.mjs
const out = {};

// o10: x^3 + y^3 + z^3 = 1 (mod 199), plain triple loop.
{
  const p = 199;
  const c = Array.from({ length: p }, (_, x) => (x * x % p) * x % p);
  let n = 0;
  for (let x = 0; x < p; x++) for (let y = 0; y < p; y++) {
    const s = (c[x] + c[y]) % p;
    for (let z = 0; z < p; z++) if ((s + c[z]) % p === 1) n++;
  }
  out.o10 = n;
}

// o03: mn | m^2 + n^2 + 35 over the full 10000 x 10000 grid (BigInt-free: values < 2^53).
{
  let n = 0;
  for (let a = 1; a <= 10000; a++) for (let b = 1; b <= 10000; b++) if ((a * a + b * b + 35) % (a * b) === 0) n++;
  out.o03 = n;
}

// o07: period of 1/n by long division (cycle of remainders), smallest n with period 2026.
{
  const period = (n) => {
    while (n % 2 === 0) n /= 2;
    while (n % 5 === 0) n /= 5;
    if (n === 1) return 1;
    let r = 10 % n, k = 1;
    while (r !== 1) { r = (r * 10) % n; k++; }
    return k;
  };
  let n = 1;
  while (period(n) !== 2026) n++;
  out.o07 = n;
}

// o05: seat individual people chair by chair (people 2c and 2c+1 are couple c); fix person 0 in chair 0
// to quotient by rotation (every rotation class contains exactly one seating with person 0 in chair 0).
{
  const N = 12, seat = new Array(N).fill(-1), used = new Array(N).fill(false);
  let cnt = 0;
  const couple = (p) => p >> 1;
  const rec = (i) => {
    if (i === N) { cnt++; return; }
    for (let p = 0; p < N; p++) {
      if (used[p]) continue;
      const c = couple(p);
      if (couple(seat[i - 1]) === c) continue;
      if (i === N - 1 && couple(seat[0]) === c) continue;
      if (i >= 6 && couple(seat[i - 6]) === c) continue;
      used[p] = true; seat[i] = p; rec(i + 1); used[p] = false; seat[i] = -1;
    }
  };
  seat[0] = 0; used[0] = true; rec(1);
  out.o05 = cnt;
}

// o08: triangulations of a 13-gon with exactly 4 ears, via memoised ear-count polynomials over
// sub-polygons given as vertex intervals [i..j] (counts ears whose two boundary sides are polygon sides).
{
  const n = 13, memo = new Map();
  const isSide = (a, b) => ((b - a + n) % n === 1) || ((a - b + n) % n === 1);
  const T = (i, j) => { // triangulations of polygon i..j (i<j) using chord (i,j) as base; map ears->count
    const key = i * 100 + j;
    if (memo.has(key)) return memo.get(key);
    const res = new Map();
    if (j - i < 2) { res.set(0, 1); memo.set(key, res); return res; }
    for (let k = i + 1; k < j; k++) {
      const sides = (isSide(i, k) ? 1 : 0) + (isSide(k, j) ? 1 : 0) + (isSide(i, j) ? 1 : 0);
      const ear = sides >= 2 ? 1 : 0;
      for (const [e1, c1] of T(i, k)) for (const [e2, c2] of T(k, j)) {
        const e = e1 + e2 + ear;
        res.set(e, (res.get(e) ?? 0) + c1 * c2);
      }
    }
    memo.set(key, res);
    return res;
  };
  out.o08 = T(0, n - 1).get(4);
}

// o02: brute force over all 6-subsets of {1..24}.
{
  let good = 0, total = 0;
  const pick = [];
  const rec = (start) => {
    if (pick.length === 6) {
      total++;
      for (let a = 0; a < 6; a++) for (let b = a + 1; b < 6; b++) {
        if (pick[b] - pick[a] === 1 || pick[a] + pick[b] === 25) return;
      }
      good++;
      return;
    }
    for (let v = start; v <= 24; v++) { pick.push(v); rec(v + 1); pick.pop(); }
  };
  rec(1);
  const g = (a, b) => (b ? g(b, a % b) : a);
  const d = g(good, total);
  out.o02 = good / d + total / d;
}

// o01: build f step by step from f(f(n)) = 3n + 2 and monotonicity (see olympiad.py for the argument).
{
  const f = new Map([[0, 1]]), forced = new Map([[1, 2]]);
  for (let n = 1; n <= 2026; n++) {
    const v = forced.has(n) ? forced.get(n) : f.get(n - 1) + 1;
    f.set(n, v);
    forced.set(v, 3 * n + 2);
  }
  for (let n = 0; n <= 600; n++) if (f.get(f.get(n)) !== 3 * n + 2) throw new Error('o01 check ' + n);
  out.o01 = f.get(2026);
}

// o11: sum floor(k sqrt 2) with BigInt integer square roots.
{
  const isqrt = (v) => { let x = BigInt(Math.floor(Math.sqrt(Number(v)))); while (x * x > v) x--; while ((x + 1n) * (x + 1n) <= v) x++; return x; };
  let s = 0n;
  for (let k = 1n; k <= 2026n; k++) s += isqrt(2n * k * k);
  out.o11 = Number(s);
}

const expected = { o10: 40806, o03: 89, o07: 12157, o05: 6589440, o08: 26208, o02: 10517, o01: 3893, o11: 2902864 };
let bad = 0;
for (const [k, v] of Object.entries(expected)) {
  const ok = out[k] === v;
  if (!ok) bad++;
  console.log(k, out[k], ok ? 'OK' : `MISMATCH (expected ${v})`);
}
process.exit(bad ? 1 : 0);
