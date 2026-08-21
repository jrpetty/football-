// ---------------------------------------------------------------------------
// Numerical primitives shared by the model core.
//
// Everything here is pure and dependency-free so it runs unchanged in Node
// (the data pipeline and backtest harness) and in the browser (what-ifs).
// ---------------------------------------------------------------------------

/** Clamp `x` into [lo, hi]. */
export function clamp(x: number, lo: number, hi: number): number {
  return x < lo ? lo : x > hi ? hi : x
}

/** Logistic function. */
export function sigmoid(x: number): number {
  return 1 / (1 + Math.exp(-x))
}

/** Sum of a numeric array. */
export function sum(xs: readonly number[]): number {
  let t = 0
  for (const x of xs) t += x
  return t
}

/** Arithmetic mean; 0 for an empty array. */
export function mean(xs: readonly number[]): number {
  return xs.length === 0 ? 0 : sum(xs) / xs.length
}

/** Population standard deviation; 0 for fewer than two values. */
export function stdev(xs: readonly number[]): number {
  if (xs.length < 2) return 0
  const m = mean(xs)
  let acc = 0
  for (const x of xs) acc += (x - m) * (x - m)
  return Math.sqrt(acc / xs.length)
}

// --- Poisson -----------------------------------------------------------------

/**
 * Poisson pmf, computed iteratively rather than via factorials so it stays
 * stable for the small k (0..12) this model ever needs.
 */
export function poissonPmf(lambda: number, k: number): number {
  if (lambda <= 0) return k === 0 ? 1 : 0
  let p = Math.exp(-lambda)
  for (let i = 1; i <= k; i++) p *= lambda / i
  return p
}

/** Poisson pmf over 0..maxK, as an array. */
export function poissonVector(lambda: number, maxK: number): number[] {
  const out = new Array<number>(maxK + 1)
  let p = Math.exp(-lambda)
  out[0] = p
  for (let k = 1; k <= maxK; k++) {
    p *= lambda / k
    out[k] = p
  }
  return out
}

// --- Log-gamma and Negative Binomial ----------------------------------------

const LANCZOS_G = 7
const LANCZOS_C = [
  0.99999999999980993, 676.5203681218851, -1259.1392167224028,
  771.32342877765313, -176.61502916214059, 12.507343278686905,
  -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7,
]

/** Log of the gamma function (Lanczos approximation). */
export function logGamma(x: number): number {
  if (x < 0.5) {
    // Reflection formula, for completeness — the model only ever passes x > 0.
    return Math.log(Math.PI / Math.sin(Math.PI * x)) - logGamma(1 - x)
  }
  const z = x - 1
  let a = LANCZOS_C[0]!
  const t = z + LANCZOS_G + 0.5
  for (let i = 1; i < LANCZOS_G + 2; i++) a += LANCZOS_C[i]! / (z + i)
  return 0.5 * Math.log(2 * Math.PI) + (z + 0.5) * Math.log(t) - t + Math.log(a)
}

/**
 * Negative Binomial pmf parameterised by mean `lambda` and dispersion `r`,
 * so Var = lambda + lambda^2 / r and r -> infinity recovers the Poisson.
 *
 * This is what lets the model express parameter uncertainty (thin data,
 * missing players, promoted sides) as fatter score tails — which is where
 * honest upset probability comes from.
 */
export function negBinomialPmf(lambda: number, r: number, k: number): number {
  if (!isFinite(r) || r > 1e6) return poissonPmf(lambda, k)
  if (lambda <= 0) return k === 0 ? 1 : 0
  const logP =
    logGamma(k + r) -
    logGamma(k + 1) -
    logGamma(r) +
    r * Math.log(r / (r + lambda)) +
    k * Math.log(lambda / (r + lambda))
  return Math.exp(logP)
}

/** Negative Binomial pmf over 0..maxK, as an array. */
export function negBinomialVector(lambda: number, r: number, maxK: number): number[] {
  if (!isFinite(r) || r > 1e6) return poissonVector(lambda, maxK)
  const out = new Array<number>(maxK + 1)
  for (let k = 0; k <= maxK; k++) out[k] = negBinomialPmf(lambda, r, k)
  return out
}

// --- Determinism -------------------------------------------------------------

/**
 * Seeded PRNG (mulberry32). Used anywhere the pipeline needs randomness so
 * that re-running it produces byte-identical artifacts — otherwise every CI
 * run would create spurious git churn.
 */
export function mulberry32(seed: number): () => number {
  let a = seed >>> 0
  return function next(): number {
    a = (a + 0x6d2b79f5) >>> 0
    let t = a
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/** Round to `dp` decimal places, avoiding -0 and float dust in artifacts. */
export function round(x: number, dp: number): number {
  const f = Math.pow(10, dp)
  const r = Math.round(x * f) / f
  return r === 0 ? 0 : r
}
