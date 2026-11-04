// Seeded randomness and coherent noise. Everything in the world that is
// "random" flows through these so a world seed reproduces the same planet.

export class Rng {
  private s: number
  constructor(seed: number) {
    this.s = seed >>> 0 || 0x9e3779b9
  }
  /** mulberry32 — fast, decent quality, 32-bit state (serialisable). */
  next(): number {
    let t = (this.s = (this.s + 0x6d2b79f5) >>> 0)
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
  range(a: number, b: number): number {
    return a + (b - a) * this.next()
  }
  int(a: number, b: number): number {
    return Math.floor(this.range(a, b + 1))
  }
  chance(p: number): boolean {
    return this.next() < p
  }
  pick<T>(arr: readonly T[]): T {
    return arr[Math.floor(this.next() * arr.length)]
  }
  /** Approximately normal (Irwin–Hall, 4 samples). */
  gauss(mean = 0, sd = 1): number {
    const u = this.next() + this.next() + this.next() + this.next() - 2
    return mean + u * sd * 1.732
  }
  get state(): number {
    return this.s
  }
  set state(v: number) {
    this.s = v >>> 0
  }
}

export function hashString(str: string): number {
  let h = 2166136261
  for (let i = 0; i < str.length; i++) {
    h ^= str.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}

/** Cheap deterministic hash of integer coordinates → [0,1). */
export function hash2(x: number, y: number, seed = 0): number {
  let h = Math.imul(x | 0, 374761393) + Math.imul(y | 0, 668265263) + Math.imul(seed | 0, 2246822519)
  h = Math.imul(h ^ (h >>> 13), 1274126177)
  h ^= h >>> 16
  return (h >>> 0) / 4294967296
}

// --- 2D simplex noise (Gustavson), seeded permutation ----------------------

const GRAD = [
  [1, 1], [-1, 1], [1, -1], [-1, -1], [1, 0], [-1, 0], [0, 1], [0, -1],
]

export class Noise2D {
  private perm = new Uint8Array(512)
  constructor(seed: number) {
    const rng = new Rng(seed)
    const p = new Uint8Array(256)
    for (let i = 0; i < 256; i++) p[i] = i
    for (let i = 255; i > 0; i--) {
      const j = Math.floor(rng.next() * (i + 1))
      const t = p[i]
      p[i] = p[j]
      p[j] = t
    }
    for (let i = 0; i < 512; i++) this.perm[i] = p[i & 255]
  }

  noise(xin: number, yin: number): number {
    const F2 = 0.5 * (Math.sqrt(3) - 1)
    const G2 = (3 - Math.sqrt(3)) / 6
    const s = (xin + yin) * F2
    const i = Math.floor(xin + s)
    const j = Math.floor(yin + s)
    const t = (i + j) * G2
    const x0 = xin - (i - t)
    const y0 = yin - (j - t)
    const i1 = x0 > y0 ? 1 : 0
    const j1 = x0 > y0 ? 0 : 1
    const x1 = x0 - i1 + G2
    const y1 = y0 - j1 + G2
    const x2 = x0 - 1 + 2 * G2
    const y2 = y0 - 1 + 2 * G2
    const ii = i & 255
    const jj = j & 255
    const p = this.perm
    let n0 = 0, n1 = 0, n2 = 0
    let t0 = 0.5 - x0 * x0 - y0 * y0
    if (t0 > 0) {
      const g = GRAD[p[ii + p[jj]] & 7]
      t0 *= t0
      n0 = t0 * t0 * (g[0] * x0 + g[1] * y0)
    }
    let t1 = 0.5 - x1 * x1 - y1 * y1
    if (t1 > 0) {
      const g = GRAD[p[ii + i1 + p[jj + j1]] & 7]
      t1 *= t1
      n1 = t1 * t1 * (g[0] * x1 + g[1] * y1)
    }
    let t2 = 0.5 - x2 * x2 - y2 * y2
    if (t2 > 0) {
      const g = GRAD[p[ii + 1 + p[jj + 1]] & 7]
      t2 *= t2
      n2 = t2 * t2 * (g[0] * x2 + g[1] * y2)
    }
    return 70 * (n0 + n1 + n2) // ~[-1, 1]
  }

  /** Fractal Brownian motion, normalised to ~[-1, 1]. */
  fbm(x: number, y: number, octaves = 5, lacunarity = 2, gain = 0.5): number {
    let amp = 1, freq = 1, sum = 0, norm = 0
    for (let o = 0; o < octaves; o++) {
      sum += amp * this.noise(x * freq, y * freq)
      norm += amp
      amp *= gain
      freq *= lacunarity
    }
    return sum / norm
  }

  /** Ridged multifractal — sharp mountain ridges. Returns ~[0, 1]. */
  ridged(x: number, y: number, octaves = 5): number {
    let amp = 0.5, freq = 1, sum = 0, norm = 0
    for (let o = 0; o < octaves; o++) {
      const n = 1 - Math.abs(this.noise(x * freq, y * freq))
      sum += amp * n * n
      norm += amp
      amp *= 0.5
      freq *= 2
    }
    return sum / norm
  }
}

export function clamp(v: number, a: number, b: number): number {
  return v < a ? a : v > b ? b : v
}
export function lerp(a: number, b: number, t: number): number {
  return a + (b - a) * t
}
export function smoothstep(a: number, b: number, v: number): number {
  const t = clamp((v - a) / (b - a), 0, 1)
  return t * t * (3 - 2 * t)
}
