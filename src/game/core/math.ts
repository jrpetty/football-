// Scalar helpers shared across the simulation.

export const clamp = (v: number, lo: number, hi: number): number =>
  v < lo ? lo : v > hi ? hi : v

export const clamp01 = (v: number): number => (v < 0 ? 0 : v > 1 ? 1 : v)

export const lerp = (a: number, b: number, t: number): number => a + (b - a) * t

// Move `current` toward `target` by at most `maxDelta` — framerate-independent easing.
export const moveToward = (current: number, target: number, maxDelta: number): number => {
  const d = target - current
  if (Math.abs(d) <= maxDelta) return target
  return current + Math.sign(d) * maxDelta
}

export const sign = (v: number): number => (v > 0 ? 1 : v < 0 ? -1 : 0)

// Smallest signed difference between two angles, in (-PI, PI].
export const angleDelta = (from: number, to: number): number => {
  let d = (to - from) % (Math.PI * 2)
  if (d > Math.PI) d -= Math.PI * 2
  if (d < -Math.PI) d += Math.PI * 2
  return d
}

// Smoothstep easing on [0,1].
export const smoothstep = (t: number): number => {
  const x = clamp01(t)
  return x * x * (3 - 2 * x)
}

// A small, fast, deterministic PRNG (mulberry32). Seeded so matches can be
// replayed and physics stays reproducible when we want it to be.
export class Rng {
  private s: number
  constructor(seed = 0x9e3779b9) {
    this.s = seed >>> 0
  }
  next(): number {
    this.s = (this.s + 0x6d2b79f5) | 0
    let t = Math.imul(this.s ^ (this.s >>> 15), 1 | this.s)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
  // Uniform in [lo, hi).
  range(lo: number, hi: number): number {
    return lo + this.next() * (hi - lo)
  }
  // Symmetric noise in [-mag, mag).
  noise(mag: number): number {
    return (this.next() * 2 - 1) * mag
  }
  pick<T>(arr: readonly T[]): T {
    return arr[Math.floor(this.next() * arr.length) % arr.length]
  }
}
