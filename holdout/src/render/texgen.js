// Procedural, seamlessly tiling PBR textures generated at startup:
// albedo + normal + roughness for every surface in the game.
import * as THREE from 'three'

// ---------------------------------------------------------------- noise
function hash(ix, iy, seed) {
  let h = Math.imul(ix, 374761393) ^ Math.imul(iy, 668265263) ^ Math.imul(seed, 1442695041)
  h = Math.imul(h ^ (h >>> 13), 1274126177)
  h ^= h >>> 16
  return (h >>> 0) / 4294967296
}
const sm = (t) => t * t * (3 - 2 * t)
// Value noise on a lattice that wraps every `p` cells, so textures tile.
function vnoise(x, y, p, seed) {
  const x0 = Math.floor(x)
  const y0 = Math.floor(y)
  const fx = sm(x - x0)
  const fy = sm(y - y0)
  const a = ((x0 % p) + p) % p
  const b = ((y0 % p) + p) % p
  const a1 = (a + 1) % p
  const b1 = (b + 1) % p
  const v00 = hash(a, b, seed)
  const v10 = hash(a1, b, seed)
  const v01 = hash(a, b1, seed)
  const v11 = hash(a1, b1, seed)
  return v00 + (v10 - v00) * fx + (v01 - v00) * fy + (v00 - v10 - v01 + v11) * fx * fy
}
// Value noise tiling every px cells across and py cells down (anisotropic).
function vn2(x, y, px, py, seed) {
  const x0 = Math.floor(x)
  const y0 = Math.floor(y)
  const fx = sm(x - x0)
  const fy = sm(y - y0)
  const a = ((x0 % px) + px) % px
  const b = ((y0 % py) + py) % py
  const a1 = (a + 1) % px
  const b1 = (b + 1) % py
  const v00 = hash(a, b, seed)
  const v10 = hash(a1, b, seed)
  const v01 = hash(a, b1, seed)
  const v11 = hash(a1, b1, seed)
  return v00 + (v10 - v00) * fx + (v01 - v00) * fy + (v00 - v10 - v01 + v11) * fx * fy
}
// Fractal noise in [0,1]. u,v in [0,1); base = cells across at octave 0.
function fbm(u, v, base, oct, seed, gain = 0.5) {
  let sum = 0
  let amp = 1
  let norm = 0
  let f = base
  for (let o = 0; o < oct; o++) {
    sum += amp * vnoise(u * f, v * f, f, seed + o * 17)
    norm += amp
    amp *= gain
    f *= 2
  }
  return sum / norm
}
// Tiling Worley noise: returns [F1, F2, cellId] with distances in cell units.
function worley(u, v, cells, seed, out) {
  const x = u * cells
  const y = v * cells
  const xi = Math.floor(x)
  const yi = Math.floor(y)
  let f1 = 9
  let f2 = 9
  let id = 0
  for (let dy = -1; dy <= 1; dy++) {
    for (let dx = -1; dx <= 1; dx++) {
      const cx = xi + dx
      const cy = yi + dy
      const wx = ((cx % cells) + cells) % cells
      const wy = ((cy % cells) + cells) % cells
      const px = cx + hash(wx, wy, seed)
      const py = cy + hash(wx, wy, seed + 7)
      const d = Math.hypot(px - x, py - y)
      if (d < f1) {
        f2 = f1
        f1 = d
        id = hash(wx, wy, seed + 13)
      } else if (d < f2) f2 = d
    }
  }
  out[0] = f1
  out[1] = f2
  out[2] = id
  return out
}
const clamp01 = (v) => (v < 0 ? 0 : v > 1 ? 1 : v)
const mix = (a, b, t) => a + (b - a) * t
const hex = (h) => {
  const n = parseInt(h.slice(1), 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}
const mixc = (a, b, t, o) => {
  o[0] = a[0] + (b[0] - a[0]) * t
  o[1] = a[1] + (b[1] - a[1]) * t
  o[2] = a[2] + (b[2] - a[2]) * t
  return o
}

// ---------------------------------------------------------------- canvas
class Tex {
  constructor(n, alpha = false) {
    this.n = n
    this.rgb = new Float32Array(n * n * 3)
    this.h = new Float32Array(n * n)
    this.r = new Float32Array(n * n).fill(0.8)
    this.a = alpha ? new Float32Array(n * n) : null
  }
  set(i, c, height, rough) {
    this.rgb[i * 3] = c[0]
    this.rgb[i * 3 + 1] = c[1]
    this.rgb[i * 3 + 2] = c[2]
    this.h[i] = height
    if (rough !== undefined) this.r[i] = rough
  }
}

// Turn a generated Tex into three.js textures.
function finish(t, { normalStrength = 2, rough = true } = {}) {
  const n = t.n
  const albedo = new Uint8Array(n * n * 4)
  const normal = new Uint8Array(n * n * 4)
  const rmap = new Uint8Array(n * n * 4)
  const H = t.h
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      const i = y * n + x
      albedo[i * 4] = Math.max(0, Math.min(255, t.rgb[i * 3]))
      albedo[i * 4 + 1] = Math.max(0, Math.min(255, t.rgb[i * 3 + 1]))
      albedo[i * 4 + 2] = Math.max(0, Math.min(255, t.rgb[i * 3 + 2]))
      albedo[i * 4 + 3] = t.a ? Math.round(clamp01(t.a[i]) * 255) : 255
      const xl = (x - 1 + n) % n
      const xr = (x + 1) % n
      const yu = (y - 1 + n) % n
      const yd = (y + 1) % n
      const dx = (H[y * n + xr] - H[y * n + xl]) * normalStrength
      const dy = (H[yd * n + x] - H[yu * n + x]) * normalStrength
      const len = Math.hypot(dx, dy, 1)
      normal[i * 4] = ((-dx / len) * 0.5 + 0.5) * 255
      normal[i * 4 + 1] = ((dy / len) * 0.5 + 0.5) * 255
      normal[i * 4 + 2] = ((1 / len) * 0.5 + 0.5) * 255
      normal[i * 4 + 3] = 255
      const r = clamp01(t.r[i]) * 255
      rmap[i * 4] = r
      rmap[i * 4 + 1] = r
      rmap[i * 4 + 2] = r
      rmap[i * 4 + 3] = 255
    }
  }
  const mk = (data, srgb) => {
    const tx = new THREE.DataTexture(data, n, n, THREE.RGBAFormat)
    tx.wrapS = tx.wrapT = THREE.RepeatWrapping
    tx.magFilter = THREE.LinearFilter
    tx.minFilter = THREE.LinearMipmapLinearFilter
    tx.generateMipmaps = true
    tx.anisotropy = 8
    tx.colorSpace = srgb ? THREE.SRGBColorSpace : THREE.NoColorSpace
    tx.needsUpdate = true
    return tx
  }
  return { map: mk(albedo, true), normalMap: mk(normal, false), roughnessMap: rough ? mk(rmap, false) : null }
}


// A cluster of leaves rasterised into an alpha card (used by leafcard*).
function foliageCard(n, seed, o) {
  const t = new Tex(n, true)
  let s2 = seed
  const rnd = () => ((s2 = (s2 * 16807) % 2147483647) / 2147483647)
  const cols = o.cols.map(hex)
  const twig = hex('#5a4630')
  // twigs from the centre outwards
  for (let k = 0; k < 9; k++) {
    const a = rnd() * Math.PI * 2
    const L = 0.2 + rnd() * 0.18
    for (let j = 0; j < 80; j++) {
      const u = 0.5 + Math.cos(a) * L * (j / 80)
      const v = 0.5 + Math.sin(a) * L * (j / 80)
      const i = Math.floor(v * n) * n + Math.floor(u * n)
      if (i >= 0 && i < n * n) {
        t.rgb[i * 3] = twig[0]
        t.rgb[i * 3 + 1] = twig[1]
        t.rgb[i * 3 + 2] = twig[2]
        t.a[i] = 1
        t.h[i] = 0.2
      }
    }
  }
  const leaves = []
  for (let k = 0; k < o.leaves; k++) {
    // denser towards the middle
    const a = rnd() * Math.PI * 2
    const d = Math.pow(rnd(), 0.5) * o.radius * (0.85 + 0.15 * Math.sin(a * 5))
    leaves.push({ x: 0.5 + Math.cos(a) * d, y: 0.5 + Math.sin(a) * d, ang: a + (rnd() - 0.5) * 1.2, len: o.len[0] + rnd() * (o.len[1] - o.len[0]), wid: o.wid[0] + rnd() * (o.wid[1] - o.wid[0]), c: cols[Math.floor(rnd() * cols.length)], depth: rnd() })
  }
  leaves.sort((p, q) => p.depth - q.depth)
  for (const L of leaves) {
    const ca = Math.cos(L.ang)
    const sa = Math.sin(L.ang)
    const r = L.len
    const x0 = Math.max(0, Math.floor((L.x - r) * n))
    const x1 = Math.min(n - 1, Math.ceil((L.x + r) * n))
    const y0 = Math.max(0, Math.floor((L.y - r) * n))
    const y1 = Math.min(n - 1, Math.ceil((L.y + r) * n))
    const shade = 0.75 + L.depth * 0.45
    for (let py = y0; py <= y1; py++) for (let px = x0; px <= x1; px++) {
      const dx = px / n - L.x
      const dy = py / n - L.y
      // leaf frame: u along the blade (0 at stem, 1 at tip), v across
      const u = (dx * ca + dy * sa) / L.len
      const v = (-dx * sa + dy * ca) / L.wid
      if (u < 0 || u > 1) continue
      const halfW = Math.sin(Math.PI * Math.pow(u, 0.8)) * 0.5
      if (Math.abs(v) > halfW) continue
      const i = py * n + px
      const edge = Math.abs(v) / (halfW + 1e-4)
      const rib = Math.abs(v) < 0.04 ? 0.82 : 1
      const k = shade * rib * (1.05 - edge * 0.25)
      t.rgb[i * 3] = L.c[0] * k
      t.rgb[i * 3 + 1] = L.c[1] * k
      t.rgb[i * 3 + 2] = L.c[2] * k
      t.h[i] = 0.5 + L.depth * 0.5 - edge * edge * 0.3
      t.r[i] = 0.6 + edge * 0.2
      t.a[i] = 1
    }
  }
  bleed(t, cols[0])
  return finish(t, { normalStrength: 4 })
}
// Give transparent texels a sensible colour so filtering doesn't darken edges.
function bleed(t, c) {
  for (let i = 0; i < t.n * t.n; i++) {
    if (t.a[i] > 0) continue
    t.rgb[i * 3] = c[0]
    t.rgb[i * 3 + 1] = c[1]
    t.rgb[i * 3 + 2] = c[2]
    t.h[i] = 0
  }
}

// ---------------------------------------------------------------- generators
const W = [0, 0, 0]
const C = [0, 0, 0]

const GEN = {

  leafcard(n) {
    // a cluster of broad leaves on twigs, alpha-cut, for tree and bush cards
    return foliageCard(n, 911, { leaves: 520, len: [0.035, 0.06], wid: [0.016, 0.028], cols: ['#4e7a2e', '#5e8a36', '#6a9a3e', '#46702a', '#78a446', '#3e6224'], radius: 0.46 })
  },
  leafcardAutumn(n) {
    return foliageCard(n, 913, { leaves: 520, len: [0.035, 0.06], wid: [0.016, 0.028], cols: ['#c8782a', '#d8962e', '#b05a22', '#e0a838', '#9a4a1e', '#c8862a'], radius: 0.46 })
  },
  needlecard(n) {
    // a conifer spray: a twig with sub-twigs, each bristling with needles
    const t = new Tex(n, true)
    const rnd = (() => {
      let s2 = 7
      return () => ((s2 = (s2 * 16807) % 2147483647) / 2147483647)
    })()
    const cols = ['#34502a', '#3e5a2c', '#466434', '#2e4624', '#506e3a'].map(hex)
    const twig = hex('#4a3a2a')
    const put = (x, y, c, h) => {
      const xi = Math.round(x * n)
      const yi = Math.round(y * n)
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
        const px = xi + dx
        const py = yi + dy
        if (px < 0 || py < 0 || px >= n || py >= n) continue
        const i = py * n + px
        if (t.a[i] < 1 || h > t.h[i]) {
          t.rgb[i * 3] = c[0]
          t.rgb[i * 3 + 1] = c[1]
          t.rgb[i * 3 + 2] = c[2]
          t.h[i] = h
          t.a[i] = 1
          t.r[i] = 0.8
        }
      }
    }
    const line = (x0, y0, x1, y1, c, h0, h1, step = 0.6 / n) => {
      const len = Math.hypot(x1 - x0, y1 - y0)
      const k = Math.max(2, Math.ceil(len / step))
      for (let j = 0; j <= k; j++) put(x0 + ((x1 - x0) * j) / k, y0 + ((y1 - y0) * j) / k, c, h0 + ((h1 - h0) * j) / k)
    }
    // a fern-shaped spray: a main twig from the base (left) to the tip (right),
    // side twigs angled forwards, shorter towards the tip, all bristling with needles
    const needles = (x0, y0, x1, y1, L) => {
      const len = Math.hypot(x1 - x0, y1 - y0)
      const k = Math.max(2, Math.ceil(len / (2.2 / n)))
      const ux = (x1 - x0) / len
      const uy = (y1 - y0) / len
      for (let j = 0; j <= k; j++) {
        const x = x0 + ((x1 - x0) * j) / k
        const y = y0 + ((y1 - y0) * j) / k
        const fade = 1 - (j / k) * 0.5
        for (const sgn of [-1, 1]) {
          // needle direction: rotate the twig direction forwards by ~50 degrees
          const ang = Math.atan2(uy, ux) + sgn * (0.75 + rnd() * 0.35)
          const nl = L * fade * (0.8 + rnd() * 0.4)
          const c = cols[Math.floor(rnd() * cols.length)]
          line(x, y, x + Math.cos(ang) * nl, y + Math.sin(ang) * nl, c, 0.55, 1.0)
        }
      }
      line(x0, y0, x1, y1, twig, 0.35, 0.35)
    }
    const y0 = 0.5
    for (let j = 0; j < 15; j++) {
      const t = j / 15
      const bx = 0.05 + t * 0.85
      const by = y0 + t * 0.03
      const side = j % 2 ? 1 : -1
      const L = (0.42 - t * 0.36) * (0.85 + rnd() * 0.3)
      const ang = side * (0.75 + rnd() * 0.25)
      needles(bx, by, bx + Math.cos(ang) * L, by + Math.sin(ang) * L * 0.95, 0.035)
      if (j % 3 === 0) needles(bx, by, bx + Math.cos(-ang) * L * 0.8, by + Math.sin(-ang) * L * 0.75, 0.032)
    }
    needles(0.04, y0, 0.97, y0 + 0.035, 0.045)
    // bleed colour into transparent texels to avoid dark fringes
    bleed(t, hex('#2e4a26'))
    return finish(t, { normalStrength: 3 })
  },
  dirt(n) {
    // packed earth: broad tonal patches, fine grit, faint cracks and sparse small stones
    const t = new Tex(n)
    const a = hex('#5e4c38')
    const b = hex('#86704f')
    const dry = hex('#9a8664')
    const dark = hex('#3e3226')
    const stone = hex('#8a8276')
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const base = fbm(u, v, 3, 5, 11)
        const mid = fbm(u, v, 12, 3, 15)
        const fine = fbm(u, v, 48, 3, 12)
        mixc(a, b, clamp01(base * 1.5 - 0.25), C)
        if (mid > 0.58) mixc(C, dry, clamp01((mid - 0.58) * 2.5) * 0.6, C)
        let h = base * 0.4 + fine * 0.35
        let rough = 0.93
        // faint hairline cracks in the driest patches only
        worley(u, v, 14, 17, W)
        const crack = W[1] - W[0] + (fine - 0.5) * 0.04
        if (crack < 0.018 && mid > 0.64) {
          mixc(C, dark, 0.3 * (1 - crack / 0.018) * clamp01((mid - 0.64) * 6), C)
          h -= 0.2
        }
        // trampled, darker earth
        const tr = fbm(u, v, 6, 3, 19)
        if (tr < 0.4) mixc(C, dark, (0.4 - tr) * 0.6, C)
        // sparse dark grit
        const grit = vnoise(u * 300, v * 300, 300, 18)
        if (grit > 0.86) {
          mixc(C, dark, (grit - 0.86) * 2.5, C)
          h += (grit - 0.86) * 1.5
        }
        const k = 0.82 + fine * 0.36
        C[0] *= k
        C[1] *= k
        C[2] *= k
        t.set(i, C, h, rough)
      }
    }
    return finish(t, { normalStrength: 3 })
  },
  forest(n) {
    // forest floor: needles, leaf litter and dark humus
    const t = new Tex(n)
    const humus = hex('#3a2e22')
    const needle = hex('#7a5a36')
    const leafA = hex('#8a6a3a')
    const leafB = hex('#5a5a2e')
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const base = fbm(u, v, 4, 4, 241)
        mixc(humus, needle, clamp01(base * 1.3 - 0.2), C)
        let h = base * 0.4
        const n1 = vnoise(u * 160, v * 20, 160, 242)
        const n2 = vnoise(u * 20, v * 160, 20, 243)
        const nd = Math.max(n1, n2)
        if (nd > 0.72) {
          mixc(C, needle, (nd - 0.72) * 2.5, C)
          h += (nd - 0.72) * 1.5
        }
        worley(u, v, 26, 244, W)
        if (W[2] > 0.55 && W[0] < 0.32) {
          mixc(C, W[2] > 0.78 ? leafA : leafB, 0.7, C)
          h += 0.4 - W[0]
        }
        t.set(i, C, h, 0.95)
      }
    }
    return finish(t, { normalStrength: 3 })
  },
  foliage(n) {
    // soft leaf clusters: low-contrast mottling and fine speckle (tinted per part)
    const t = new Tex(n)
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const m = fbm(u, v, 8, 4, 261)
        const f = vnoise(u * 72, v * 72, 72, 262)
        const g = vnoise(u * 150, v * 150, 150, 263)
        const c = 168 + (m - 0.5) * 90 + (f - 0.5) * 46 + (g - 0.5) * 26
        C[0] = c * 0.97
        C[1] = c
        C[2] = c * 0.9
        t.set(i, C, m * 0.6 + f * 0.3 + g * 0.1, 0.78)
      }
    }
    return finish(t, { normalStrength: 2.2 })
  },
  needles(n) {
    // conifer needles: fine streaks along v with darker gaps
    const t = new Tex(n)
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const s1 = vnoise(u * 120, v * 10, 120, 271)
        const s2 = vnoise(u * 60 + 0.3, v * 22, 60, 272)
        const m = fbm(u, v, 6, 3, 273)
        const c = 150 + (s1 - 0.5) * 70 + (s2 - 0.5) * 40 + (m - 0.5) * 60
        C[0] = c * 0.95
        C[1] = c
        C[2] = c * 0.88
        t.set(i, C, s1 * 0.6 + m * 0.4, 0.82)
      }
    }
    return finish(t, { normalStrength: 2.5 })
  },
  noise(n) {
    // grey macro noise used by shaders to break up tiling
    const t = new Tex(n)
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const f = fbm(u, v, 4, 5, 251)
        const g = fbm(u, v, 9, 4, 252)
        C[0] = f * 255
        C[1] = g * 255
        C[2] = fbm(u, v, 2, 3, 253) * 255
        t.set(y * n + x, C, 0, 1)
      }
    }
    const res = finish(t, { normalStrength: 0, rough: false })
    res.map.colorSpace = THREE.NoColorSpace
    return res
  },
  grass(n) {
    const t = new Tex(n)
    const g1 = hex('#4d5c2e')
    const g2 = hex('#6f7a3c')
    const dry = hex('#8a7f4c')
    const soil = hex('#5a4a34')
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const p = fbm(u, v, 3, 5, 21)
        const blades = vnoise(u * 128, v * 24, 128, 22) * 0.5 + vnoise(u * 24, v * 128, 24, 23) * 0.5
        mixc(g1, g2, clamp01(p * 1.6 - 0.3), C)
        const d = fbm(u, v, 6, 3, 24)
        if (d > 0.62) mixc(C, dry, clamp01((d - 0.62) * 4), C)
        const s = fbm(u, v, 10, 3, 25)
        if (s < 0.3) mixc(C, soil, clamp01((0.3 - s) * 5), C)
        const k = 0.86 + blades * 0.24
        C[0] *= k
        C[1] *= k
        C[2] *= k
        t.set(i, C, blades * 0.35 + p * 0.3, 0.95)
      }
    }
    return finish(t, { normalStrength: 2.5 })
  },
  gravel(n) {
    const t = new Tex(n)
    const tones = ['#7a7266', '#8e8676', '#635c52', '#9a9180', '#81796b', '#6e665a'].map(hex)
    const grout = hex('#3a3229')
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        worley(u, v, 48, 31, W)
        const edge = clamp01((W[1] - W[0]) * 5)
        const tone = tones[Math.floor(W[2] * tones.length)]
        mixc(grout, tone, edge, C)
        const k = 0.85 + fbm(u, v, 64, 2, 32) * 0.3
        C[0] *= k
        C[1] *= k
        C[2] *= k
        t.set(i, C, edge * 0.8 + (1 - W[0]) * 0.3, 0.9)
      }
    }
    return finish(t, { normalStrength: 4 })
  },
  asphalt(n, worn = false) {
    const t = new Tex(n)
    const a = hex(worn ? '#5c5d5c' : '#35373a')
    const b = hex(worn ? '#706f6c' : '#45474a')
    const patch = hex(worn ? '#4a4b4c' : '#2b2d30')
    const light = hex(worn ? '#959490' : '#6c6e70')
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const base = fbm(u, v, 4, 4, 41)
        mixc(a, b, base, C)
        const pt = fbm(u, v, 3, 3, 42)
        if (pt > 0.66) mixc(C, patch, 0.7, C)
        const agg = vnoise(u * 200, v * 200, 200, 43)
        if (agg > 0.78) mixc(C, light, (agg - 0.78) * 3, C)
        else if (agg < 0.2) mixc(C, patch, 0.4, C)
        worley(u, v, 6, 44, W)
        const crack = W[1] - W[0]
        let h = agg * 0.25 + base * 0.2
        const cw = 0.025 + fbm(u, v, 8, 2, 45) * 0.03
        if (crack < cw && fbm(u, v, 5, 3, 46) > 0.6) {
          mixc(C, [24, 24, 26], worn ? 0.38 : 0.55, C)
          h -= 0.45
        }
        t.set(i, C, h, 0.82 + agg * 0.1)
      }
    }
    return finish(t, { normalStrength: 3 })
  },
  asphaltWorn(n) {
    return GEN.asphalt(n, true)
  },
  concrete(n) {
    const t = new Tex(n)
    const a = hex('#8f8c85')
    const b = hex('#a6a39b')
    const stain = hex('#6e6a60')
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const base = fbm(u, v, 3, 5, 51)
        mixc(a, b, base, C)
        const s = fbm(u, v, 5, 4, 52)
        if (s > 0.6) mixc(C, stain, (s - 0.6) * 1.6, C)
        const pore = vnoise(u * 180, v * 180, 180, 53)
        let h = base * 0.3 + pore * 0.1
        if (pore < 0.12) {
          mixc(C, stain, 0.5, C)
          h -= 0.3
        }
        // formwork seams
        if (x % (n / 2) < 2 || y % (n / 2) < 2) {
          mixc(C, stain, 0.45, C)
          h -= 0.5
        }
        t.set(i, C, h, 0.88)
      }
    }
    return finish(t, { normalStrength: 2 })
  },
  pavers(n) {
    const t = new Tex(n)
    const tones = ['#9c978c', '#a8a397', '#918b80', '#b0aa9e'].map(hex)
    const grout = hex('#5c574e')
    const cell = n / 4
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const cx = Math.floor(x / cell)
        const cy = Math.floor(y / cell)
        const lx = (x % cell) / cell
        const ly = (y % cell) / cell
        const e = Math.min(lx, ly, 1 - lx, 1 - ly) * cell
        const tone = tones[Math.floor(hash(cx, cy, 61) * tones.length)]
        const bevel = clamp01(e / 4)
        mixc(grout, tone, bevel, C)
        const k = 0.85 + fbm(u, v, 24, 3, 62) * 0.3
        C[0] *= k
        C[1] *= k
        C[2] *= k
        t.set(i, C, bevel * 0.8 + fbm(u, v, 40, 2, 63) * 0.1, 0.85)
      }
    }
    return finish(t, { normalStrength: 3 })
  },
  planks(n) {
    const t = new Tex(n)
    const tones = ['#8a6644', '#7b5a3b', '#97724b', '#6e5034', '#86613f'].map(hex)
    const boards = 6
    const bw = n / boards
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const bi = Math.floor(y / bw)
        const off = hash(bi, 0, 71) * n
        const seg = Math.floor(((x + off) % n) / (n / 2))
        const id = hash(bi, seg, 72)
        const tone = tones[Math.floor(id * tones.length)]
        const ly = (y % bw) / bw
        const grain = vnoise(u * 6 + id * 10, v * 160, 6, 73) * 0.6 + vnoise(u * 24, v * 400, 24, 74) * 0.4
        const ring = Math.sin((v * 40 + grain * 3 + id * 20) * Math.PI) * 0.5 + 0.5
        mixc(tone, [tone[0] * 0.7, tone[1] * 0.68, tone[2] * 0.66], ring * 0.35 + grain * 0.3, C)
        let h = 0.6 + grain * 0.15
        const lx = ((x + off) % (n / 2)) / (n / 2)
        if (ly < 0.035 || ly > 0.965 || lx < 0.006) {
          mixc(C, [30, 22, 15], 0.75, C)
          h = 0
        }
        t.set(i, C, h, 0.62 + grain * 0.2)
      }
    }
    return finish(t, { normalStrength: 2.5 })
  },
  wood(n) {
    // continuous grain for individual boards and timbers (no board seams), light so it tints well
    const t = new Tex(n)
    const base = hex('#caa880')
    const dark = hex('#8e6c4a')
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const warp = vnoise(u * 3, v * 6, 3, 84) * 2.5
        const grain = vnoise(u * 3 + warp * 0.2, v * 70 + warp, 3, 82) * 0.55 + vnoise(u * 12, v * 260, 12, 83) * 0.45
        const ring = Math.sin((v * 18 + warp * 3 + grain * 2) * Math.PI) * 0.5 + 0.5
        mixc(base, dark, clamp01(ring * 0.35 + grain * 0.45), C)
        let h = 0.5 + grain * 0.3
        // occasional knots
        const kx = (u * 2 + 0.3) % 1
        const ky = (v * 3 + 0.1) % 1
        const kd = Math.hypot((kx - 0.5) * 2.2, (ky - 0.5) * 1.2)
        const kid = hash(Math.floor(u * 2 + 0.3), Math.floor(v * 3 + 0.1), 85)
        if (kid > 0.6 && kd < 0.12) {
          mixc(C, [72, 50, 32], 1 - kd / 0.12, C)
          h -= 0.25
        }
        const weather = fbm(u, v, 4, 3, 86)
        const k = 0.88 + weather * 0.2
        C[0] *= k
        C[1] *= k
        C[2] *= k
        t.set(i, C, h, 0.8)
      }
    }
    return finish(t, { normalStrength: 2.2 })
  },
  siding(n) {
    const t = new Tex(n)
    const rows = 8
    const rh = n / rows
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const ly = (y % rh) / rh
        const k = 0.86 + ly * 0.14
        const dirt = fbm(u, v, 4, 4, 91)
        const streak = vnoise(u * 40, v * 2, 40, 92)
        let c = 232 * k - dirt * 30 - (streak > 0.7 ? (streak - 0.7) * 60 : 0)
        C[0] = c
        C[1] = c * 0.99
        C[2] = c * 0.96
        const grain = vnoise(u * 60, v * 400, 60, 93) * 0.08
        t.set(i, C, ly * 0.8 + grain, 0.7)
      }
    }
    return finish(t, { normalStrength: 3 })
  },
  brick(n) {
    const t = new Tex(n)
    const tones = ['#8e4a36', '#7d3f2e', '#9a5640', '#a0603f', '#73392b'].map(hex)
    const mortar = hex('#b3aa99')
    const bh = n / 16
    const bwid = n / 8
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const row = Math.floor(y / bh)
        const ox = row % 2 ? bwid / 2 : 0
        const col = Math.floor(((x + ox) % n) / bwid)
        const lx = ((x + ox) % bwid) / bwid
        const ly = (y % bh) / bh
        const e = Math.min(lx * bwid, (1 - lx) * bwid, ly * bh, (1 - ly) * bh)
        const tone = tones[Math.floor(hash(col, row, 101) * tones.length)]
        const noise = fbm(u, v, 32, 3, 102)
        const m = clamp01((e - 1.2) / 1.5)
        mixc(mortar, tone, m, C)
        const k = 0.8 + noise * 0.35
        C[0] *= k
        C[1] *= k
        C[2] *= k
        t.set(i, C, m * 0.7 + noise * 0.2, 0.9)
      }
    }
    return finish(t, { normalStrength: 3 })
  },
  plaster(n) {
    const t = new Tex(n)
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const f = fbm(u, v, 8, 4, 111)
        const g = fbm(u, v, 3, 3, 112)
        const c = 226 + f * 22 - g * 26
        C[0] = c
        C[1] = c * 0.985
        C[2] = c * 0.95
        t.set(i, C, f * 0.4, 0.92)
      }
    }
    return finish(t, { normalStrength: 1.5 })
  },
  // Wallpaper, pale so the room's colour tints it: broad stripes with a
  // fine pinstripe at each edge, a little uneven and stained.
  paperStripe(n) {
    const t = new Tex(n)
    const S = 6
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const p = (u * S) % 1
        let c = p < 0.5 ? 238 : 214
        if (Math.abs(p - 0.5) < 0.014 || p < 0.014) c = 186
        else if (Math.abs(p - 0.25) < 0.006) c = 226
        const f = fbm(u, v, 5, 3, 301)
        const st = fbm(u, v, 2, 3, 302)
        c += (f - 0.5) * 10 - Math.max(0, st - 0.6) * 70
        C[0] = c
        C[1] = c * 0.985
        C[2] = c * 0.95
        t.set(i, C, 0.2 + f * 0.1, 0.88)
      }
    }
    return finish(t, { normalStrength: 0.8 })
  },
  // Wallpaper with a small repeating flower on a half-drop lattice.
  paperFloral(n) {
    const t = new Tex(n)
    const S = 6
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const row = Math.floor(v * S)
        let lx = ((u * S + (row % 2) * 0.5) % 1) - 0.5
        let ly = ((v * S) % 1) - 0.5
        const d = Math.hypot(lx, ly)
        const a = Math.atan2(ly, lx)
        const petal = 0.17 * (0.55 + 0.45 * Math.abs(Math.cos(2 * a)))
        let c = 236
        if (d < 0.045) c = 168
        else if (d < petal) c = 200
        // a stem-and-leaf flourish between the flowers
        const q = Math.abs(lx + ly * 0.3 - 0.34)
        if (q < 0.012 && ly > -0.2 && ly < 0.2) c = 214
        const f = fbm(u, v, 5, 3, 311)
        const st = fbm(u, v, 2, 3, 312)
        c += (f - 0.5) * 8 - Math.max(0, st - 0.62) * 70
        C[0] = c
        C[1] = c * 0.98
        C[2] = c * 0.94
        t.set(i, C, 0.2 + f * 0.1, 0.9)
      }
    }
    return finish(t, { normalStrength: 0.6 })
  },
  tiles(n) {
    const t = new Tex(n)
    const cell = n / 8
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const lx = (x % cell) / cell
        const ly = (y % cell) / cell
        const e = Math.min(lx, ly, 1 - lx, 1 - ly) * cell
        const g = clamp01((e - 1) / 1.5)
        const id = hash(Math.floor(x / cell), Math.floor(y / cell), 121)
        const dirt = fbm(u, v, 6, 4, 122)
        const c = 236 - id * 14 - dirt * 34
        mixc([118, 112, 100], [c, c * 0.985, c * 0.96], g, C)
        t.set(i, C, g * 0.6, g > 0.5 ? 0.22 + dirt * 0.3 : 0.85)
      }
    }
    return finish(t, { normalStrength: 2.5 })
  },
  checker(n) {
    const t = new Tex(n)
    const cell = n / 8
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const cx = Math.floor(x / cell)
        const cy = Math.floor(y / cell)
        const dark = (cx + cy) % 2
        const lx = (x % cell) / cell
        const ly = (y % cell) / cell
        const e = Math.min(lx, ly, 1 - lx, 1 - ly) * cell
        const g = clamp01((e - 0.8) / 1.2)
        const dirt = fbm(u, v, 6, 4, 132)
        const c = dark ? 60 - dirt * 14 : 232 - dirt * 38
        mixc([100, 96, 88], [c, c * 0.98, c * 0.96], g, C)
        t.set(i, C, g * 0.5, 0.3 + dirt * 0.25)
      }
    }
    return finish(t, { normalStrength: 2 })
  },
  carpet(n) {
    const t = new Tex(n)
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const f = vnoise(u * 256, v * 256, 256, 141) * 0.5 + vnoise(u * 128, v * 128, 128, 142) * 0.5
        const stain = fbm(u, v, 4, 3, 143)
        const c = 200 + f * 40 - (stain > 0.62 ? (stain - 0.62) * 160 : 0)
        C[0] = C[1] = C[2] = c
        t.set(i, C, f * 0.6, 1)
      }
    }
    return finish(t, { normalStrength: 1.5 })
  },
  linoleum(n) {
    const t = new Tex(n)
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const fleck = vnoise(u * 160, v * 160, 160, 151)
        const wear = fbm(u, v, 5, 4, 152)
        let c = 214 - wear * 30
        if (fleck > 0.8) c -= 50
        else if (fleck < 0.15) c += 18
        C[0] = c
        C[1] = c * 0.99
        C[2] = c * 0.94
        t.set(i, C, fleck * 0.1, 0.45 + wear * 0.3)
      }
    }
    return finish(t, { normalStrength: 1 })
  },
  corrugated(n) {
    const t = new Tex(n)
    const rust = hex('#8a5232')
    const rust2 = hex('#5e3622')
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const ridge = Math.sin(u * Math.PI * 2 * 16) * 0.5 + 0.5
        const grime = fbm(u, v, 4, 4, 161)
        const c = 196 + ridge * 34 - grime * 30
        C[0] = c
        C[1] = c
        C[2] = c * 1.02
        let rough = 0.45 + grime * 0.2
        // rust runs down in thin streaks, heavier towards the lower edge
        const streak = vnoise(u * 48, v * 3, 48, 162) * 0.7 + fbm(u, v, 6, 3, 163) * 0.3
        const lower = Math.max(0, v - 0.55) * 1.6
        const r = streak * (0.55 + lower) + (1 - ridge) * 0.08
        if (r > 0.62) {
          mixc(C, r > 0.78 ? rust2 : rust, clamp01((r - 0.62) * 3.5), C)
          rough = 0.88
        }
        t.set(i, C, ridge, rough)
      }
    }
    return finish(t, { normalStrength: 6 })
  },
  metal(n) {
    // worked bare steel: brushed grain, mill scale, scratches, a little grime
    const t = new Tex(n)
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const broad = fbm(u, v, 3, 4, 171)
        const brush = vnoise(u * 2, v * 260, 2, 174) * 0.6 + vnoise(u * 5, v * 520, 5, 175) * 0.4
        const grime = fbm(u, v, 7, 4, 176)
        let c = 214 + (brush - 0.5) * 22 - Math.max(0, grime - 0.55) * 70 - (broad - 0.5) * 18
        let rough = 0.36 + broad * 0.16 + Math.max(0, grime - 0.55) * 0.6
        const scratch = vnoise(u * 360, v * 5, 360, 172)
        if (scratch > 0.935) {
          c += 18
          rough = 0.22
        }
        C[0] = c
        C[1] = c
        C[2] = c * 1.015
        const pit = vnoise(u * 90, v * 90, 90, 173)
        if (pit > 0.86 && grime > 0.5) {
          mixc(C, [118, 84, 58], (pit - 0.86) * 4, C)
          rough = 0.85
        }
        t.set(i, C, brush * 0.08 - Math.max(0, pit - 0.86) * 0.6, rough)
      }
    }
    return finish(t, { normalStrength: 1.2 })
  },
  paint(n) {
    // factory paint on sheet metal (white, tinted per part): near-flat with a
    // soft orange peel, fine scratches, and a few chips through to primer and rust
    const t = new Tex(n)
    const primer = [168, 164, 154]
    const rustC = [104, 66, 42]
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const broad = fbm(u, v, 3, 4, 401)
        const peel = fbm(u, v, 48, 2, 402)
        let c = 230 + (broad - 0.5) * 12 + (peel - 0.5) * 5
        let rough = 0.4 + broad * 0.16
        let h = peel * 0.12
        const s1 = vnoise(u * 420, v * 6, 420, 403)
        const s2 = vnoise(v * 380, u * 8, 380, 404)
        if (s1 > 0.945 || s2 > 0.96) {
          c += 10
          rough = 0.32
          h -= 0.04
        }
        C[0] = c
        C[1] = c
        C[2] = c
        // rare small chips, clustered where the broad wear is highest
        const chip = fbm(u, v, 28, 3, 405) * 0.7 + fbm(u, v, 5, 2, 406) * 0.3
        if (chip > 0.765) {
          const k = clamp01((chip - 0.765) * 18)
          mixc(C, k > 0.6 ? rustC : primer, Math.min(1, k * 2.5), C)
          rough = k > 0.6 ? 0.9 : 0.7
          h -= 0.25
        }
        t.set(i, C, h, rough)
      }
    }
    return finish(t, { normalStrength: 1.4 })
  },
  rust(n) {
    const t = new Tex(n)
    const a = hex('#6e3a20')
    const b = hex('#a2582c')
    const c2 = hex('#3d2416')
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const f = fbm(u, v, 6, 6, 181)
        const g = vnoise(u * 120, v * 120, 120, 182)
        mixc(a, b, f, C)
        if (g < 0.25) mixc(C, c2, 0.6, C)
        t.set(i, C, f * 0.5 + g * 0.3, 0.95)
      }
    }
    return finish(t, { normalStrength: 3 })
  },
  fabric(n) {
    const t = new Tex(n)
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const wx = Math.sin(u * Math.PI * 2 * 96)
        const wy = Math.sin(v * Math.PI * 2 * 96)
        const weave = (wx * 0.5 + 0.5) * ((Math.floor(v * 96 * 2) % 2) ? 1 : 0.6) + (wy * 0.5 + 0.5) * ((Math.floor(u * 96 * 2) % 2) ? 0.6 : 1)
        const dirt = fbm(u, v, 5, 4, 191)
        const c = 214 + weave * 16 - dirt * 40
        C[0] = c
        C[1] = c * 0.99
        C[2] = c * 0.95
        t.set(i, C, weave * 0.4, 0.95)
      }
    }
    return finish(t, { normalStrength: 1.5 })
  },
  shingles(n) {
    const t = new Tex(n)
    const tones = ['#4a4643', '#55504b', '#3f3c3a', '#5c5650'].map(hex)
    const rows = 10
    const rh = n / rows
    const tw = n / 8
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const row = Math.floor(y / rh)
        const ox = row % 2 ? tw / 2 : 0
        const col = Math.floor(((x + ox) % n) / tw)
        const ly = (y % rh) / rh
        const lx = ((x + ox) % tw) / tw
        const tone = tones[Math.floor(hash(col, row, 201) * tones.length)]
        const grit = vnoise(u * 220, v * 220, 220, 202)
        mixc(tone, [tone[0] * 1.2, tone[1] * 1.2, tone[2] * 1.2], grit * 0.6, C)
        let h = ly * 0.8 + grit * 0.1
        if (lx < 0.03 || ly > 0.94) {
          mixc(C, [20, 20, 20], 0.6, C)
          h = 0
        }
        t.set(i, C, h, 0.9)
      }
    }
    return finish(t, { normalStrength: 3 })
  },
  bark(n) {
    const t = new Tex(n)
    const a = hex('#3e3226')
    const b = hex('#6a5640')
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const r = vnoise(u * 24, v * 3, 24, 211) * 0.6 + vnoise(u * 64, v * 8, 64, 212) * 0.4
        mixc(a, b, r, C)
        t.set(i, C, r, 0.95)
      }
    }
    return finish(t, { normalStrength: 5 })
  },
  roofmetal(n) {
    // standing-seam roof panels
    const t = new Tex(n)
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const lx = (u * 8) % 1
        const seam = lx < 0.05 ? 1 : 0
        const grime = fbm(u, v, 4, 5, 221)
        const c = 210 - grime * 60 - seam * 30
        C[0] = c
        C[1] = c
        C[2] = c * 1.03
        t.set(i, C, seam, 0.45 + grime * 0.35)
      }
    }
    return finish(t, { normalStrength: 4 })
  },
  curtain(n) {
    // glass curtain wall: 4 panels across and 2 storeys per repeat, opaque
    // spandrel bands at the floor slabs, bright mullions
    const t = new Tex(n)
    const glassA = hex('#1e2c38')
    const glassB = hex('#5a7488')
    const spand = hex('#5c6268')
    const mull = hex('#c4c8cc')
    const pw = n / 4
    const ph = n / 2
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const i = y * n + x
        const col = Math.floor(x / pw)
        const row = Math.floor(y / ph)
        const lx = x - col * pw
        const ly = y - row * ph
        const fy = ly / ph
        const edgeX = Math.min(lx, pw - lx)
        const edgeY = Math.min(ly, ph - ly)
        const spandrel = fy < 0.27
        const slabLine = Math.abs(fy - 0.27) * ph < 1.6
        if (edgeX < 2.2 || edgeY < 1.6 || slabLine) {
          C[0] = mull[0]
          C[1] = mull[1]
          C[2] = mull[2]
          t.set(i, C, 1, 0.35)
        } else if (spandrel) {
          const k = 0.92 + hash(col, row, 211) * 0.12 + fbm(x / n, y / n, 16, 2, 212) * 0.06
          C[0] = spand[0] * k
          C[1] = spand[1] * k
          C[2] = spand[2] * k
          t.set(i, C, 0.55, 0.42)
        } else {
          // each pane catches a slightly different sky reflection
          const tone = hash(col, row, 213) * 0.5 + (fy - 0.27) * 0.45
          mixc(glassA, glassB, clamp01(tone), C)
          t.set(i, C, 0.3, 0.06 + hash(col, row, 214) * 0.06)
        }
      }
    }
    return finish(t, { normalStrength: 2 })
  },
  cloth2(n) {
    // fine plain weave with slubbed threads: shirts, trousers, jackets
    const t = new Tex(n)
    const F = 128
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const cx = Math.floor(u * F)
        const cy = Math.floor(v * F)
        const tx = (u * F) % 1
        const ty = (v * F) % 1
        const over = (cx + cy) % 2 === 0
        const h = over ? 0.25 + Math.sin(tx * Math.PI) * 0.75 : 0.25 + Math.sin(ty * Math.PI) * 0.75
        const tone = over ? hash(cx, 0, 402) : hash(0, cy, 403)
        const dirt = fbm(u, v, 4, 4, 404)
        const c = 226 + (tone - 0.5) * 16 + h * 10 - dirt * 20
        C[0] = c
        C[1] = c * 0.99
        C[2] = c * 0.975
        t.set(i, C, h * 0.55 + tone * 0.25, 0.9 + tone * 0.06)
      }
    }
    return finish(t, { normalStrength: 1.1 })
  },
  denim(n) {
    // 3/1 twill: diagonal ribs of indigo warp with white weft flecks (tinted by vertex colour)
    const t = new Tex(n)
    const F = 112
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const cx = Math.floor(u * F)
        const cy = Math.floor(v * F)
        const tx = (u * F) % 1
        const ty = (v * F) % 1
        const warpOn = (((cx + cy) % 4) + 4) % 4 !== 0
        const prof = warpOn ? Math.sin(tx * Math.PI) : Math.sin(ty * Math.PI)
        const slub = vn2(u * F, v * 6, F, 6, 411)
        const fade = fbm(u, v, 3, 4, 412)
        const c = warpOn ? 176 + slub * 46 + fade * 22 + prof * 12 : 238 + fade * 12
        C[0] = c
        C[1] = c
        C[2] = c
        t.set(i, C, prof * 0.7, 0.9)
      }
    }
    return finish(t, { normalStrength: 1.3 })
  },
  leather(n) {
    // pebbled hide with a few creases
    const t = new Tex(n)
    const W = [0, 0, 0]
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        worley(u, v, 48, 421, W)
        const peb = Math.min(1, (W[1] - W[0]) * 3)
        const cr = Math.abs(fbm(u, v, 3, 4, 422) - 0.5)
        const crease = cr < 0.02 ? 1 - cr / 0.02 : 0
        const blot = fbm(u, v, 5, 4, 423)
        const c = 196 + peb * 22 + blot * 30 - crease * 50
        C[0] = c
        C[1] = c * 0.97
        C[2] = c * 0.93
        t.set(i, C, peb * 0.6 - crease * 0.5, 0.48 + blot * 0.25 + crease * 0.2)
      }
    }
    return finish(t, { normalStrength: 2.2 })
  },
  hairTex(n) {
    // strands running down the texture
    const t = new Tex(n)
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const s1 = vn2(u * 220, v * 5, 220, 5, 431)
        const s2 = vn2(u * 60, v * 3, 60, 3, 432)
        const s3 = vn2(u * 14, v * 2, 14, 2, 433)
        const s = s1 * 0.5 + s2 * 0.3 + s3 * 0.2
        const c = 128 + s * 127
        C[0] = c
        C[1] = c
        C[2] = c
        t.set(i, C, s, 0.95 - s * 0.12)
      }
    }
    return finish(t, { normalStrength: 2.5 })
  },
  knit(n) {
    // stockinette: columns of V stitches, for beanies, cuffs and jumpers
    const t = new Tex(n)
    const F = 32
    const R = 40
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const lx = ((u * F) % 1) - 0.5
        const ly = (v * R) % 1
        const d = Math.abs(Math.abs(lx) - (0.08 + ly * 0.36))
        const h = clamp01(1 - d / 0.2) * (0.6 + 0.4 * Math.sin(ly * Math.PI))
        const tone = hash(Math.floor(u * F), Math.floor(v * R), 441)
        const c = 196 + h * 46 + (tone - 0.5) * 10
        C[0] = c
        C[1] = c
        C[2] = c * 0.98
        t.set(i, C, h, 0.95)
      }
    }
    return finish(t, { normalStrength: 2.4 })
  },
  skinTex(n) {
    // subtle pores/blotches for characters (white base)
    const t = new Tex(n)
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        const u = x / n
        const v = y / n
        const i = y * n + x
        const f = fbm(u, v, 16, 3, 231)
        const c = 236 + f * 18
        C[0] = c
        C[1] = c * 0.98
        C[2] = c * 0.97
        t.set(i, C, f * 0.3, 0.7)
      }
    }
    return finish(t, { normalStrength: 1 })
  },
}

const cache = new Map()
const SIZES = { dirt: 1024, grass: 1024, asphalt: 1024, asphaltWorn: 512, concrete: 512, forest: 512, noise: 256, leafcard: 512, leafcardAutumn: 512, needlecard: 512 }
// Get (and lazily generate) a texture set by name.
export function texSet(name) {
  if (!cache.has(name)) {
    const n = SIZES[name] || 512
    cache.set(name, GEN[name](n))
  }
  return cache.get(name)
}
export const TEX_NAMES = Object.keys(GEN)

// Generate everything up front, yielding between textures so a loading bar can update.
export async function pregenerate(onProgress) {
  const names = TEX_NAMES
  for (let i = 0; i < names.length; i++) {
    texSet(names[i])
    onProgress?.((i + 1) / names.length, names[i])
    await new Promise((r) => setTimeout(r, 0))
  }
}

// ---------------------------------------------------------------- canvas helpers
// Text signs, decals and labels drawn on canvas.
export function signTexture(text, { w = 512, h = 128, bg = '#20231e', fg = '#e8e0c8', font = '700 72px "Saira Condensed", "Arial Narrow", sans-serif', border = null, worn = true } = {}) {
  const c = document.createElement('canvas')
  c.width = w
  c.height = h
  const g = c.getContext('2d')
  g.fillStyle = bg
  g.fillRect(0, 0, w, h)
  if (border) {
    g.strokeStyle = border
    g.lineWidth = h * 0.06
    g.strokeRect(h * 0.05, h * 0.05, w - h * 0.1, h - h * 0.1)
  }
  g.fillStyle = fg
  g.font = font
  g.textAlign = 'center'
  g.textBaseline = 'middle'
  g.fillText(text, w / 2, h / 2 + 4, w * 0.9)
  if (worn) {
    // weathering: scratches and grime
    for (let i = 0; i < 260; i++) {
      g.fillStyle = `rgba(0,0,0,${Math.random() * 0.18})`
      g.fillRect(Math.random() * w, Math.random() * h, Math.random() * 12 + 1, Math.random() * 3 + 1)
    }
    const gr = g.createLinearGradient(0, 0, 0, h)
    gr.addColorStop(0, 'rgba(0,0,0,0)')
    gr.addColorStop(1, 'rgba(40,25,10,0.35)')
    g.fillStyle = gr
    g.fillRect(0, 0, w, h)
  }
  const t = new THREE.CanvasTexture(c)
  t.colorSpace = THREE.SRGBColorSpace
  t.anisotropy = 8
  return t
}
