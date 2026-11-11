/**
 * REKINDLE — procedural texture generation.
 *
 * Everything is generated at runtime on typed arrays (no image files, no network).
 * Every tileable texture is generated on a periodic (torus) domain so it wraps seamlessly.
 *
 * Conventions
 *  - Textures are THREE.DataTexture, row 0 = v 0 (flipY = false). "Up" in a texture = +v.
 *  - Architectural textures: 1 tile = 1 m. Ground textures (grass, dirt, rock, forestFloor, mud,
 *    sand, soil, asphalt): 1 tile ~= 2 m.
 *  - PBRSet.roughnessMap is a packed "ORM" texture: R = ambient occlusion (cavity),
 *    G = roughness, B = metalness. three.js reads roughness from G and metalness from B,
 *    so the same texture can also be bound as metalnessMap / aoMap.
 */
import * as THREE from 'three';

export type TexQuality = 'low' | 'medium' | 'high';
export interface PBRSet {
  map: THREE.Texture;
  normalMap: THREE.Texture;
  roughnessMap: THREE.Texture;
}
export type TexName =
  | 'grass' | 'dirt' | 'rock' | 'forestFloor' | 'mud' | 'sand' | 'asphalt' | 'sidewalk' | 'concrete'
  | 'brick' | 'brickRed' | 'siding' | 'plaster' | 'woodFloor' | 'tile' | 'roofShingle' | 'roofMetal'
  | 'wood' | 'woodDark' | 'bark' | 'birchBark' | 'metal' | 'metalRust' | 'metalDark' | 'steelBrushed'
  | 'fabric' | 'factoryPanel' | 'hazard' | 'grating' | 'belt' | 'soil';

/** Extra internal sets (used by materials.ts). */
export type ExtraTexName = 'skin' | 'carPaint' | 'glassGrime';
/** Sets whose paint layer can be recoloured (chips/peeling keep showing the under-layer). */
export type PaintableTexName = 'siding' | 'factoryPanel' | 'carPaint';

export const TEX_NAMES: readonly TexName[] = [
  'grass', 'dirt', 'rock', 'forestFloor', 'mud', 'sand', 'asphalt', 'sidewalk', 'concrete',
  'brick', 'brickRed', 'siding', 'plaster', 'woodFloor', 'tile', 'roofShingle', 'roofMetal',
  'wood', 'woodDark', 'bark', 'birchBark', 'metal', 'metalRust', 'metalDark', 'steelBrushed',
  'fabric', 'factoryPanel', 'hazard', 'grating', 'belt', 'soil',
];

// ============================================================================================
// Shared types (type-only; erased at runtime)
// ============================================================================================

type F = Float32Array;
type RGB = [number, number, number];
type Rng = () => number;
interface RawTex { data: Uint8Array; size: number; srgb: boolean; repeat: boolean; mips?: Uint8Array[] }
interface PaintLayer {
  ur: F; ug: F; ub: F; // under-layer (exposed where paint is gone)
  mask: F;             // 1 = painted
  shade: F;            // multiplier on paint colour (fading, dirt in paint)
  grime: F;            // 0..1 grime overlay amount (applied over both)
  grimeCol: RGB;
  paint: RGB;          // default paint colour
}
interface KernelOut {
  map: RawTex;
  normal?: RawTex;
  orm?: RawTex;
  paint?: PaintLayer;
  ao?: F;
  aoAlb?: number;
}

// ============================================================================================
// Generation kernel. Fully self-contained (no imports, no outer references) so it can be
// stringified into a Blob Web Worker; it is also instantiated on the main thread as a fallback
// and for canvas-based / recolour work.
// ============================================================================================

function textureKernel() {
  // ============================================================================================
  // Toolkit
  // ============================================================================================


  const TAU = Math.PI * 2;
  let S = 512;
  let SM = 511;
  let SH = 9;

  function setSize(n: number): void {
    S = n;
    SM = n - 1;
    SH = Math.round(Math.log2(n));
  }

  function rng(seed: number): Rng {
    let a = (seed * 2654435761) >>> 0;
    return () => {
      a = (a + 0x6d2b79f5) | 0;
      let t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }

  function hash(a: number, b: number, c = 0): number {
    let h = (Math.imul(a | 0, 374761393) + Math.imul(b | 0, 668265263) + Math.imul(c | 0, 1440662683)) | 0;
    h = Math.imul(h ^ (h >>> 13), 1274126177);
    h ^= h >>> 16;
    h = Math.imul(h, 2246822519);
    h ^= h >>> 13;
    return (h >>> 0) / 4294967296;
  }

  const clamp01 = (x: number): number => (x < 0 ? 0 : x > 1 ? 1 : x);
  const lerp = (a: number, b: number, t: number): number => a + (b - a) * t;
  function ss(e0: number, e1: number, x: number): number {
    const t = clamp01((x - e0) / (e1 - e0));
    return t * t * (3 - 2 * t);
  }
  const frac = (x: number): number => x - Math.floor(x);
  const hyp = (a: number, b: number): number => Math.sqrt(a * a + b * b);
  const fld = (v = 0): F => {
    const a = new Float32Array(S * S);
    if (v !== 0) a.fill(v);
    return a;
  };
  const pix = (x: number, y: number): number => ((Math.floor(y) & SM) << SH) | (Math.floor(x) & SM);
  const hex = (h: number): RGB => [((h >> 16) & 255) / 255, ((h >> 8) & 255) / 255, (h & 255) / 255];

  // --- cubic B-spline taps, cached per (n, g) --------------------------------------------------
  interface Taps { I: Int32Array; W: Float32Array }
  const tapCache = new Map<number, Taps>();
  function taps(n: number, g: number): Taps {
    const key = n * 100000 + g;
    const c = tapCache.get(key);
    if (c) return c;
    const I = new Int32Array(n * 4);
    const W = new Float32Array(n * 4);
    for (let x = 0; x < n; x++) {
      const t = (x * g) / n;
      const i = Math.floor(t);
      const f = t - i, f2 = f * f, f3 = f2 * f;
      W[x * 4] = (1 - 3 * f + 3 * f2 - f3) / 6;
      W[x * 4 + 1] = (4 - 6 * f2 + 3 * f3) / 6;
      W[x * 4 + 2] = (1 + 3 * f + 3 * f2 - 3 * f3) / 6;
      W[x * 4 + 3] = f3 / 6;
      for (let k = 0; k < 4; k++) I[x * 4 + k] = (((i - 1 + k) % g) + g) % g;
    }
    const r = { I, W };
    tapCache.set(key, r);
    return r;
  }

  function standardize(a: F, mean = 0, std = 1): F {
    let s = 0, s2 = 0;
    const n = a.length;
    for (let i = 0; i < n; i++) { s += a[i]; s2 += a[i] * a[i]; }
    const m = s / n;
    const sd = Math.sqrt(Math.max(1e-12, s2 / n - m * m));
    const k = std / sd;
    for (let i = 0; i < n; i++) a[i] = mean + (a[i] - m) * k;
    return a;
  }

  function normalize(a: F): F {
    let lo = Infinity, hi = -Infinity;
    for (let i = 0; i < a.length; i++) { const v = a[i]; if (v < lo) lo = v; if (v > hi) hi = v; }
    const k = hi > lo ? 1 / (hi - lo) : 0;
    for (let i = 0; i < a.length; i++) a[i] = (a[i] - lo) * k;
    return a;
  }

  /** Periodic value noise with gx×gy lattice cells, sampled on a rw×rh grid; mean 0, std 1 (cubic B-spline). */
  function vnoiseRes(gx: number, gy: number, rw: number, rh: number, rnd: Rng): F {
    const grid = new Float32Array(gx * gy);
    for (let i = 0; i < grid.length; i++) grid[i] = rnd() * 2 - 1;
    const tx = taps(rw, gx), ty = taps(rh, gy);
    const tmp = new Float32Array(gy * rw);
    const TI = tx.I, TW = tx.W;
    for (let j = 0; j < gy; j++) {
      const row = j * gx, o = j * rw;
      for (let x = 0; x < rw; x++) {
        const k = x * 4;
        tmp[o + x] = TW[k] * grid[row + TI[k]] + TW[k + 1] * grid[row + TI[k + 1]]
          + TW[k + 2] * grid[row + TI[k + 2]] + TW[k + 3] * grid[row + TI[k + 3]];
      }
    }
    const out = new Float32Array(rw * rh);
    for (let y = 0; y < rh; y++) {
      const k = y * 4;
      const r0 = ty.I[k] * rw, r1 = ty.I[k + 1] * rw, r2 = ty.I[k + 2] * rw, r3 = ty.I[k + 3] * rw;
      const w0 = ty.W[k], w1 = ty.W[k + 1], w2 = ty.W[k + 2], w3 = ty.W[k + 3];
      const o = y * rw;
      for (let x = 0; x < rw; x++) out[o + x] = w0 * tmp[r0 + x] + w1 * tmp[r1 + x] + w2 * tmp[r2 + x] + w3 * tmp[r3 + x];
    }
    return standardize(out);
  }

  function vnoise(gx: number, gy: number, rnd: Rng): F {
    gx = Math.max(1, Math.min(S, Math.round(gx)));
    gy = Math.max(1, Math.min(S, Math.round(gy)));
    return vnoiseRes(gx, gy, S, S, rnd);
  }

  // --- periodic Catmull-Rom resampling (for the fBm pyramid) -----------------------------------
  const crCache = new Map<number, Taps>();
  function crTaps(dn: number, sn: number): Taps {
    const key = dn * 100000 + sn;
    const c = crCache.get(key);
    if (c) return c;
    const I = new Int32Array(dn * 4), W = new Float32Array(dn * 4);
    for (let x = 0; x < dn; x++) {
      const t = (x * sn) / dn;
      const i = Math.floor(t);
      const f = t - i, f2 = f * f, f3 = f2 * f;
      W[x * 4] = 0.5 * (-f3 + 2 * f2 - f);
      W[x * 4 + 1] = 0.5 * (3 * f3 - 5 * f2 + 2);
      W[x * 4 + 2] = 0.5 * (-3 * f3 + 4 * f2 + f);
      W[x * 4 + 3] = 0.5 * (f3 - f2);
      for (let k = 0; k < 4; k++) I[x * 4 + k] = (((i - 1 + k) % sn) + sn) % sn;
    }
    const r = { I, W };
    crCache.set(key, r);
    return r;
  }

  function resample(src: F, sw: number, sh: number, dw: number, dh: number): F {
    let a = src;
    if (dw !== sw) {
      const t = crTaps(dw, sw);
      const o = new Float32Array(dw * sh);
      for (let y = 0; y < sh; y++) {
        const so = y * sw, d = y * dw;
        for (let x = 0; x < dw; x++) {
          const k = x * 4;
          o[d + x] = t.W[k] * a[so + t.I[k]] + t.W[k + 1] * a[so + t.I[k + 1]] + t.W[k + 2] * a[so + t.I[k + 2]] + t.W[k + 3] * a[so + t.I[k + 3]];
        }
      }
      a = o;
    }
    if (dh !== sh) {
      const t = crTaps(dh, sh);
      const o = new Float32Array(dw * dh);
      for (let y = 0; y < dh; y++) {
        const k = y * 4;
        const r0 = t.I[k] * dw, r1 = t.I[k + 1] * dw, r2 = t.I[k + 2] * dw, r3 = t.I[k + 3] * dw;
        const w0 = t.W[k], w1 = t.W[k + 1], w2 = t.W[k + 2], w3 = t.W[k + 3];
        const d = y * dw;
        for (let x = 0; x < dw; x++) o[d + x] = w0 * a[r0 + x] + w1 * a[r1 + x] + w2 * a[r2 + x] + w3 * a[r3 + x];
      }
      a = o;
    }
    return a;
  }

  /** Octave pyramid: each octave is synthesised at ~K samples per lattice cell, then upsampled. */
  function octaves(gx: number, gy: number, oct: number, seed: number, gain: number, K: number, shape: ((n: number) => number) | null): F {
    const rnd = rng(seed);
    let acc: F | null = null;
    let aw = 0, ah = 0, amp = 1;
    let fx = Math.max(1, Math.round(gx)), fy = Math.max(1, Math.round(gy));
    for (let o = 0; o < oct; o++) {
      if (fx > S || fy > S) break;
      const rw = Math.min(S, fx * K), rh = Math.min(S, fy * K);
      const n = vnoiseRes(fx, fy, rw, rh, rnd);
      if (acc && (rw !== aw || rh !== ah)) acc = resample(acc, aw, ah, rw, rh);
      if (!acc) acc = new Float32Array(rw * rh);
      if (shape) for (let i = 0; i < n.length; i++) acc[i] += shape(n[i]) * amp;
      else for (let i = 0; i < n.length; i++) acc[i] += n[i] * amp;
      aw = rw; ah = rh; amp *= gain; fx *= 2; fy *= 2;
    }
    if (!acc) return fld();
    if (aw !== S || ah !== S) acc = resample(acc, aw, ah, S, S);
    return acc;
  }

  /** Periodic fBm. Returns values with mean 0.5, std ~0.16 (roughly 0..1, unclamped). */
  function fbm(gx: number, gy: number, oct: number, seed: number, gain = 0.5): F {
    return standardize(octaves(gx, gy, oct, seed, gain, 4, null), 0.5, 0.16);
  }

  const ridgeShape = (n: number): number => { const r = Math.max(0, 1 - Math.abs(n) * 0.55); return r * r; };
  /** Periodic ridged multifractal, normalised 0..1 (ridges = 1). */
  function ridged(gx: number, gy: number, oct: number, seed: number, gain = 0.5): F {
    return normalize(octaves(gx, gy, oct, seed, gain, 8, ridgeShape));
  }

  interface Worley { f1: F; f2: F; id: F }
  /** Periodic Worley / cellular noise with cx×cy cells. Distances in cell units. */
  function worley(cx: number, cy: number, seed: number, jitter = 1): Worley {
    const rnd = rng(seed);
    const f1 = fld(1e9), f2 = fld(1e9), id = fld();
    const cw = S / cx, ch = S / cy; // cell size in pixels
    const bw = Math.ceil(cw * 1.5) + 1, bh = Math.ceil(ch * 1.5) + 1; // splat half-extent
    const ix = 1 / cw, iy = 1 / ch;
    const dxs = new Float32Array(2 * bw + 1);
    const xs = new Int32Array(2 * bw + 1);
    for (let j = 0; j < cy; j++) for (let i = 0; i < cx; i++) {
      const px = (i + 0.5 + (rnd() - 0.5) * jitter) * cw;
      const py = (j + 0.5 + (rnd() - 0.5) * jitter) * ch;
      const v = rnd();
      const x0 = Math.floor(px) - bw, y0 = Math.floor(py) - bh;
      const nx = 2 * bw + 1;
      for (let a = 0; a < nx; a++) {
        const x = x0 + a;
        const dx = (x + 0.5 - px) * ix;
        dxs[a] = dx * dx;
        xs[a] = x & SM;
      }
      for (let b = 0; b <= 2 * bh; b++) {
        const y = y0 + b;
        const dy = (y + 0.5 - py) * iy;
        const dy2 = dy * dy;
        if (dy2 > 2.6) continue;
        const o = (y & SM) << SH;
        for (let a = 0; a < nx; a++) {
          const d = dy2 + dxs[a];
          const p = o + xs[a];
          if (d < f2[p]) {
            if (d < f1[p]) { f2[p] = f1[p]; f1[p] = d; id[p] = v; } else f2[p] = d;
          }
        }
      }
    }
    for (let i = 0; i < f1.length; i++) { f1[i] = Math.sqrt(f1[i]); f2[i] = Math.sqrt(f2[i]); }
    return { f1, f2, id };
  }

  /** Separable box blur with wrap-around; radius in pixels, `passes` boxes ≈ gaussian. */
  function blur(src: F, radius: number, passes = 2): F {
    const r = Math.max(1, Math.round(radius));
    let a = Float32Array.from(src);
    let b = fld();
    const inv = 1 / (2 * r + 1);
    for (let p = 0; p < passes; p++) {
      for (let y = 0; y < S; y++) {
        const o = y << SH;
        let acc = 0;
        for (let k = -r; k <= r; k++) acc += a[o + (k & SM)];
        for (let x = 0; x < S; x++) {
          b[o + x] = acc * inv;
          acc += a[o + ((x + r + 1) & SM)] - a[o + ((x - r) & SM)];
        }
      }
      for (let x = 0; x < S; x++) {
        let acc = 0;
        for (let k = -r; k <= r; k++) acc += b[((k & SM) << SH) + x];
        for (let y = 0; y < S; y++) {
          a[(y << SH) + x] = acc * inv;
          acc += b[(((y + r + 1) & SM) << SH) + x] - b[(((y - r) & SM) << SH) + x];
        }
      }
    }
    b = a;
    return b;
  }

  function sample(a: F, fx: number, fy: number): number {
    const x0 = Math.floor(fx), y0 = Math.floor(fy);
    const tx = fx - x0, ty = fy - y0;
    const xa = x0 & SM, xb = (x0 + 1) & SM, ya = (y0 & SM) << SH, yb = ((y0 + 1) & SM) << SH;
    const t = a[ya + xa] + (a[ya + xb] - a[ya + xa]) * tx;
    const u = a[yb + xa] + (a[yb + xb] - a[yb + xa]) * tx;
    return t + (u - t) * ty;
  }

  /** Domain warp: sample `a` displaced by (wx-0.5, wy-0.5) * amt (amt in tile units). */
  function warp(a: F, wx: F, wy: F, amt: number): F {
    const out = fld();
    const k = amt * S;
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = (y << SH) | x;
      out[i] = sample(a, x + (wx[i] - 0.5) * k, y + (wy[i] - 0.5) * k);
    }
    return out;
  }

  function transpose(a: F): F {
    const o = fld();
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) o[(x << SH) | y] = a[(y << SH) | x];
    return o;
  }

  /** Rasterise a straight stroke (wrap-around). fn(pixelIndex, t along stroke 0..1). */
  function line(x0: number, y0: number, ang: number, len: number, w: number, fn: (p: number, t: number) => void): void {
    const dx = Math.cos(ang), dy = Math.sin(ang);
    const steps = Math.max(1, Math.ceil(len * 1.2));
    const hw = (w - 1) / 2;
    for (let s = 0; s <= steps; s++) {
      const t = s / steps;
      const x = x0 + dx * len * t, y = y0 + dy * len * t;
      if (w <= 1.01) fn(pix(x, y), t);
      else for (let o = -hw; o <= hw + 1e-6; o += 0.7) fn(pix(x - dy * o, y + dx * o), t);
    }
  }

  /** Rasterise a disk (wrap-around). fn(pixelIndex, normalised distance 0..1). Optional y-stretch. */
  function disk(cx: number, cy: number, r: number, fn: (p: number, d: number) => void, sy = 1): void {
    const rx = Math.ceil(r), ry = Math.ceil(r * sy);
    for (let y = -ry; y <= ry; y++) for (let x = -rx; x <= rx; x++) {
      const px = Math.floor(cx) + x, py = Math.floor(cy) + y;
      const dx = (px + 0.5 - cx) / r, dy = (py + 0.5 - cy) / (r * sy);
      const d = Math.sqrt(dx * dx + dy * dy);
      if (d <= 1) fn(((py & SM) << SH) | (px & SM), d);
    }
  }

  /** Wrapped distance in tile units between two coordinates in [0,1). */
  function wd(a: number, b: number): number {
    const d = Math.abs(a - b) % 1;
    return d > 0.5 ? 1 - d : d;
  }

  // --- image helpers ---------------------------------------------------------------------------
  interface Img { r: F; g: F; b: F }
  function img(c: RGB): Img {
    return { r: fld(c[0]), g: fld(c[1]), b: fld(c[2]) };
  }
  function put(im: Img, i: number, c: RGB, k = 1): void {
    im.r[i] = c[0] * k; im.g[i] = c[1] * k; im.b[i] = c[2] * k;
  }
  function mixc(im: Img, i: number, c: RGB, t: number): void {
    if (t <= 0) return;
    im.r[i] += (c[0] - im.r[i]) * t;
    im.g[i] += (c[1] - im.g[i]) * t;
    im.b[i] += (c[2] - im.b[i]) * t;
  }
  function mul(im: Img, i: number, k: number): void {
    im.r[i] *= k; im.g[i] *= k; im.b[i] *= k;
  }
  function lerpRGB(a: RGB, b: RGB, t: number): RGB {
    return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t];
  }
  function scaleRGB(a: RGB, k: number): RGB { return [a[0] * k, a[1] * k, a[2] * k]; }

  // --- outputs ---------------------------------------------------------------------------------
  interface Out {
    im: Img;
    h: F;
    rough: F;
    metal?: F;
    alpha?: F;
    depth: number;   // normal strength: height unit -> tile units
    ao?: number;     // cavity strength (default 2.5)
    aoR?: number;    // cavity blur radius in tile units (default 1/80)
    aoAlb?: number;  // how much cavity darkens albedo (default 0.55)
    paint?: PaintLayer;
    alphaMips?: boolean; // build coverage-preserving mipmaps for alpha-tested use
  }

  function composePaint(L: PaintLayer, pc: RGB, im: Img): void {
    const n = L.mask.length;
    const [gr, gg, gb] = L.grimeCol;
    for (let i = 0; i < n; i++) {
      const m = L.mask[i], s = L.shade[i], gm = L.grime[i];
      let r = L.ur[i] + (pc[0] * s - L.ur[i]) * m;
      let g = L.ug[i] + (pc[1] * s - L.ug[i]) * m;
      let b = L.ub[i] + (pc[2] * s - L.ub[i]) * m;
      r += (gr - r) * gm; g += (gg - g) * gm; b += (gb - b) * gm;
      im.r[i] = r; im.g[i] = g; im.b[i] = b;
    }
  }

  function cavity(h: F, radiusTiles: number, strength: number): F {
    const bl = blur(h, Math.max(1, radiusTiles * S), 2);
    const bl2 = blur(bl, Math.max(2, radiusTiles * S * 3), 1);
    const ao = fld();
    for (let i = 0; i < h.length; i++) {
      const d = Math.max(0, bl[i] - h[i]) * 0.65 + Math.max(0, bl2[i] - h[i]) * 0.35;
      ao[i] = Math.max(0.25, 1 - d * strength);
    }
    return ao;
  }

  function normalMapData(h: F, depth: number): Uint8Array {
    const out = new Uint8Array(S * S * 4);
    const k = (depth * S) / 8;
    for (let y = 0; y < S; y++) {
      const ym = ((y - 1) & SM) << SH, y0 = y << SH, yp = ((y + 1) & SM) << SH;
      for (let x = 0; x < S; x++) {
        const xm = (x - 1) & SM, xp = (x + 1) & SM;
        const dx = (h[ym + xp] + 2 * h[y0 + xp] + h[yp + xp]) - (h[ym + xm] + 2 * h[y0 + xm] + h[yp + xm]);
        const dy = (h[yp + xm] + 2 * h[yp + x] + h[yp + xp]) - (h[ym + xm] + 2 * h[ym + x] + h[ym + xp]);
        let nx = -dx * k, ny = -dy * k;
        const l = 1 / Math.sqrt(nx * nx + ny * ny + 1);
        nx *= l; ny *= l;
        const o = (y0 + x) * 4;
        out[o] = (nx * 0.5 + 0.5) * 255 + 0.5;
        out[o + 1] = (ny * 0.5 + 0.5) * 255 + 0.5;
        out[o + 2] = (l * 0.5 + 0.5) * 255 + 0.5;
        out[o + 3] = 255;
      }
    }
    return out;
  }

  /**
   * Mip chain whose alpha is rescaled per level so the fraction of texels passing an alpha test
   * (0.5) matches level 0 — keeps alpha-tested foliage/grating from thinning out with distance.
   * Colour is alpha-weighted to avoid dark fringes.
   */
  function alphaMips(data: Uint8Array, size: number): Uint8Array[] {
    const levels: Uint8Array[] = [data];
    const cover = (d: Uint8Array, n: number, k: number): number => {
      let c = 0;
      for (let i = 0; i < n; i++) if (d[i * 4 + 3] * k >= 127.5) c++;
      return c / n;
    };
    const target = cover(data, size * size, 1);
    let cur = data, s = size;
    while (s > 1) {
      const ns = s >> 1;
      const out = new Uint8Array(ns * ns * 4);
      for (let y = 0; y < ns; y++) for (let x = 0; x < ns; x++) {
        const a = ((2 * y) * s + 2 * x) * 4, b = a + 4, c = a + s * 4, d = c + 4;
        const aa = cur[a + 3] + cur[b + 3] + cur[c + 3] + cur[d + 3];
        const o = (y * ns + x) * 4;
        for (let ch = 0; ch < 3; ch++) {
          out[o + ch] = aa > 0
            ? (cur[a + ch] * cur[a + 3] + cur[b + ch] * cur[b + 3] + cur[c + ch] * cur[c + 3] + cur[d + ch] * cur[d + 3]) / aa
            : (cur[a + ch] + cur[b + ch] + cur[c + ch] + cur[d + ch]) / 4;
        }
        out[o + 3] = aa / 4;
      }
      const n = ns * ns;
      let lo = 0.25, hi = 16;
      for (let it = 0; it < 14; it++) {
        const mid = (lo + hi) / 2;
        if (cover(out, n, mid) < target) lo = mid; else hi = mid;
      }
      const k = (lo + hi) / 2;
      for (let i = 0; i < n; i++) out[i * 4 + 3] = Math.min(255, out[i * 4 + 3] * k);
      levels.push(out);
      cur = out; s = ns;
    }
    return levels;
  }

  function raw(data: Uint8Array, size: number, srgb: boolean, repeat = true): RawTex {
    return { data, size, srgb, repeat };
  }

  function albedoData(im: Img, ao: F | null, aoAlb: number, alpha?: F): Uint8Array {
    const n = S * S;
    const out = new Uint8Array(n * 4);
    for (let i = 0; i < n; i++) {
      const k = ao ? 1 - (1 - ao[i]) * aoAlb : 1;
      out[i * 4] = clamp01(im.r[i] * k) * 255 + 0.5;
      out[i * 4 + 1] = clamp01(im.g[i] * k) * 255 + 0.5;
      out[i * 4 + 2] = clamp01(im.b[i] * k) * 255 + 0.5;
      out[i * 4 + 3] = alpha ? clamp01(alpha[i]) * 255 + 0.5 : 255;
    }
    return out;
  }

  let ormHalf = false;

  function ormData(ao: F, rough: F, metal: F | undefined): { data: Uint8Array; size: number } {
    if (!ormHalf) {
      const out = new Uint8Array(S * S * 4);
      for (let i = 0; i < S * S; i++) {
        out[i * 4] = ao[i] * 255 + 0.5;
        out[i * 4 + 1] = clamp01(rough[i]) * 255 + 0.5;
        out[i * 4 + 2] = metal ? clamp01(metal[i]) * 255 + 0.5 : 0;
        out[i * 4 + 3] = 255;
      }
      return { data: out, size: S };
    }
    const H = S >> 1;
    const out = new Uint8Array(H * H * 4);
    for (let y = 0; y < H; y++) for (let x = 0; x < H; x++) {
      const a = ((2 * y) << SH) | (2 * x), b = a + 1, c = a + S, d = c + 1;
      const o = (y * H + x) * 4;
      out[o] = ((ao[a] + ao[b] + ao[c] + ao[d]) / 4) * 255 + 0.5;
      out[o + 1] = clamp01((rough[a] + rough[b] + rough[c] + rough[d]) / 4) * 255 + 0.5;
      out[o + 2] = metal ? clamp01((metal[a] + metal[b] + metal[c] + metal[d]) / 4) * 255 + 0.5 : 0;
      out[o + 3] = 255;
    }
    return { data: out, size: H };
  }


  function finalize(o: Out): KernelOut {
    const ao = cavity(o.h, o.aoR ?? 1 / 80, o.ao ?? 2.5);
    const aoAlb = o.aoAlb ?? 0.55;
    if (o.paint) composePaint(o.paint, o.paint.paint, o.im);
    const map = raw(albedoData(o.im, ao, aoAlb, o.alpha), S, true);
    if (o.alphaMips) map.mips = alphaMips(map.data, S);
    const normal = raw(normalMapData(o.h, o.depth), S, false);
    const orm = ormData(ao, o.rough, o.metal);
    const res: KernelOut = { map, normal, orm: raw(orm.data, orm.size, false) };
    if (o.paint) { res.paint = o.paint; res.ao = ao; res.aoAlb = aoAlb; }
    return res;
  }

  // ============================================================================================
  // Shared generators
  // ============================================================================================

  /** Wood grain running along +y. grain: 0..1 (1 = dark latewood / fibre), knot mask, colour band. */
  function woodGrain(seed: number, rings: number, knots: number, meander = 0.22): { grain: F; knot: F; band: F } {
    const rnd = rng(seed);
    const w1 = fbm(2, 1, 3, seed + 1, 0.5);
    const w2 = fbm(5, 2, 3, seed + 2, 0.5);
    const fiber = fbm(Math.max(16, S / 4), 4, 2, seed + 3, 0.55);
    const fiber2 = fbm(Math.max(8, S / 10), 2, 2, seed + 5, 0.5);
    const band = fbm(6, 1, 3, seed + 4);
    const ks: { x: number; y: number; r: number; a: number }[] = [];
    for (let i = 0; i < knots; i++) ks.push({ x: rnd(), y: rnd(), r: 0.006 + rnd() * 0.007, a: (rnd() < 0.5 ? -1 : 1) * (0.5 + rnd() * 0.5) });
    const grain = fld(), knot = fld();
    for (let y = 0; y < S; y++) {
      const v = y / S;
      for (let x = 0; x < S; x++) {
        const u = x / S, i = (y << SH) | x;
        let c = u * rings + (w1[i] - 0.5) * rings * meander + (w2[i] - 0.5) * 1.2;
        let km = 0;
        for (let q = 0; q < ks.length; q++) {
          const kn = ks[q];
          let dx = u - kn.x, dy = v - kn.y;
          dx -= Math.round(dx); dy -= Math.round(dy);
          const rr = kn.r * kn.r;
          const d2 = (dx * dx) / rr + (dy * dy) / (rr * 16);
          if (d2 < 60) {
            c += kn.a * Math.exp(-d2 * 0.25) * 2.2;
            const dk = Math.sqrt(dx * dx + dy * dy * 0.5) / kn.r;
            if (dk < 1) km = Math.max(km, 1 - ss(0.45, 1, dk));
          }
        }
        const ring = frac(c);
        const r3 = ring * ring * ring;
        grain[i] = clamp01(0.55 * r3 * ring + 0.45 * clamp01(0.5 + (fiber[i] - 0.5) * 2.2) + (fiber2[i] - 0.5) * 0.4);
        knot[i] = km;
      }
    }
    return { grain, knot, band };
  }

  /** Scratch strokes: returns mask 0..1. */
  function scratches(count: number, seed: number, lenMin: number, lenMax: number, angBias: number | null, angSpread: number): F {
    const rnd = rng(seed);
    const m = fld();
    for (let n = 0; n < count; n++) {
      const x = rnd() * S, y = rnd() * S;
      const ang = angBias === null ? rnd() * TAU : angBias + (rnd() - 0.5) * angSpread;
      const len = (lenMin + rnd() * (lenMax - lenMin)) * S;
      const str = 0.3 + rnd() * 0.7;
      const curve = (rnd() - 0.5) * 0.3;
      let px = x, py = y, a = ang;
      const segs = 4;
      for (let s = 0; s < segs; s++) {
        line(px, py, a, len / segs, 1, (p, t) => {
          const tt = (s + t) / segs;
          const fade = Math.sin(Math.PI * tt);
          m[p] = Math.max(m[p], str * fade);
        });
        px += (Math.cos(a) * len) / segs; py += (Math.sin(a) * len) / segs; a += curve;
      }
    }
    return m;
  }

  /** Random-walk cracks with branching (wrap-around). Width in px at 512; sub-pixel widths give partial intensity. */
  function crackWalks(n: number, seed: number, len: number, width: number, branch = 0.3, wobble = 0.6): F {
    const rnd = rng(seed);
    const m = fld();
    const k = S / 512;
    const walk = (x0: number, y0: number, a0: number, L: number, w0: number, depth: number): void => {
      let x = x0, y = y0, a = a0, w = w0, rem = L * S;
      while (rem > 0 && w > 0.12) {
        const sl = (2 + rnd() * 3) * k;
        const ww = w;
        line(x, y, a, sl, Math.max(1, ww), (p) => { const v = Math.min(1, ww); if (v > m[p]) m[p] = v; });
        x += Math.cos(a) * sl; y += Math.sin(a) * sl; rem -= sl;
        a += (rnd() - 0.5) * wobble + (a0 - a) * 0.04;
        w *= 0.994;
        if (depth < 2 && rnd() < branch * 0.035) {
          walk(x, y, a + (rnd() < 0.5 ? -1 : 1) * (0.5 + rnd() * 0.7), (rem * (0.15 + rnd() * 0.35)) / S, w * 0.6, depth + 1);
        }
      }
    };
    for (let i = 0; i < n; i++) walk(rnd() * S, rnd() * S, rnd() * TAU, len * (0.5 + rnd()), width * k, 0);
    return m;
  }

  /** Worley-edge crack network (for alligator cracking / dried mud). Returns 0..1. */
  function crackField(cells: number, seed: number, width: number, coverage: number, warpAmt = 0.04): F {
    const w = worley(cells, cells, seed);
    const e = fld();
    for (let i = 0; i < e.length; i++) e[i] = w.f2[i] - w.f1[i];
    const wx = fbm(cells * 2, cells * 2, 3, seed + 1), wy = fbm(cells * 2, cells * 2, 3, seed + 2);
    const ew = warp(e, wx, wy, warpAmt);
    const mask = fbm(Math.max(1, cells / 2), Math.max(1, cells / 2), 4, seed + 3);
    const out = fld();
    for (let i = 0; i < out.length; i++) {
      const m = ss(1 - coverage - 0.08, 1 - coverage + 0.08, mask[i]);
      out[i] = (1 - ss(width * 0.3, width, ew[i])) * m;
    }
    return out;
  }

  /** Fractal "chip" mask: 1 where paint is gone. `bias` pushes more chipping (edges etc.). */
  function chipMask(base: F, detail: F, i: number, threshold: number, bias: number): number {
    return ss(threshold - 0.012, threshold + 0.012, base[i] + (detail[i] - 0.5) * 0.35 + bias);
  }

  // ============================================================================================
  // Texture generators
  // ============================================================================================

  function genGrass(): Out {
    const N2 = S * S, k = S / 512, rnd = rng(1001);
    const soil = fbm(8, 8, 5, 1002), dry = fbm(5, 5, 5, 1003), hue = fbm(10, 10, 4, 1004), clov = fbm(6, 6, 4, 1005);
    const dens = fbm(14, 14, 3, 1006);
    const im = img([0, 0, 0]);
    const h = fld(-0.3), z = fld(-1), rough = fld(0.95);
    for (let i = 0; i < N2; i++) {
      const s = soil[i];
      put(im, i, [0.10 + 0.05 * s, 0.085 + 0.04 * s, 0.055 + 0.025 * s]);
    }
    const nC = Math.floor(N2 * 0.004);
    for (let n = 0; n < nC; n++) {
      const x = rnd() * S, y = rnd() * S;
      if (ss(0.6, 0.72, clov[pix(x, y)]) < rnd()) continue;
      const rr = (1.8 + rnd() * 1.5) * k, zc = 0.2 + rnd() * 0.55, rot = rnd() * TAU, sh = 0.85 + rnd() * 0.3;
      for (let l = 0; l < 3; l++) {
        const a = rot + (l * TAU) / 3;
        disk(x + Math.cos(a) * rr * 0.95, y + Math.sin(a) * rr * 0.95, rr, (p, d) => {
          const zz = zc + (1 - d * d) * 0.12;
          if (zz > z[p]) {
            z[p] = zz; h[p] = zz;
            put(im, p, [0.17, 0.26, 0.105], sh * (0.65 + 0.45 * zz) * (d > 0.8 ? 0.82 : 1));
            rough[p] = 0.7;
          }
        });
      }
    }
    const greens: RGB[] = [[0.25, 0.32, 0.12], [0.21, 0.29, 0.11], [0.29, 0.34, 0.14], [0.19, 0.26, 0.10], [0.31, 0.35, 0.17], [0.26, 0.30, 0.13]];
    const straw: RGB = [0.50, 0.44, 0.29], dead: RGB = [0.37, 0.31, 0.20];
    const nB = Math.floor(N2 * 0.3);
    const bw = Math.max(1, Math.round(k));
    for (let n = 0; n < nB; n++) {
      const x = rnd() * S, y = rnd() * S;
      const p0 = pix(x, y);
      if (rnd() < (1 - dens[p0]) * 0.35) continue;
      const dr = ss(0.6, 0.92, dry[p0] + (rnd() - 0.5) * 0.2) * 0.85;
      const len = (5 + rnd() * 11) * k * (1 - 0.3 * dr);
      const ang = rnd() * TAU;
      const zb = rnd() * 0.85;
      let c = greens[Math.floor(rnd() * greens.length)];
      c = scaleRGB(c, 0.88 + 0.3 * (hue[p0] - 0.5) + 0.22 * rnd());
      c = lerpRGB(c, rnd() < 0.5 ? straw : dead, dr * (0.6 + 0.4 * rnd()));
      if (rnd() < 0.04) c = lerpRGB(c, rnd() < 0.5 ? dead : straw, 0.75);
      line(x, y, ang, len, bw, (p, t) => {
        const zz = zb + t * 0.35;
        if (zz > z[p]) {
          z[p] = zz; h[p] = zz;
          put(im, p, c, (0.5 + 0.6 * zz) * (0.92 + 0.16 * t));
          rough[p] = 0.78 + 0.1 * dr;
        }
      });
    }
    return { im, h, rough, depth: 0.0025, ao: 3, aoR: 1 / 160, aoAlb: 0.5 };
  }

  function genDirt(seed = 2000): Out {
    const wx = fbm(4, 4, 4, seed + 1), wy = fbm(4, 4, 4, seed + 2);
    const base = warp(fbm(6, 6, 7, seed + 3), wx, wy, 0.08);
    const grit = vnoise(S / 4, S / 4, rng(seed + 4));
    const fine = vnoise(S / 2, S / 2, rng(seed + 5));
    const org = fbm(6, 6, 4, seed + 6);
    const st = worley(30, 30, seed + 7);
    const st2 = worley(90, 90, seed + 8);
    const cr = crackField(6, seed + 9, 0.04, 0.22);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld();
    for (let i = 0; i < h.length; i++) {
      const b = base[i];
      let hh = b * 0.3 + grit[i] * 0.025 + fine[i] * 0.012;
      const t = clamp01((b - 0.25) / 0.5);
      let c = lerpRGB([0.28, 0.225, 0.17], [0.38, 0.315, 0.245], t);
      c = scaleRGB(c, (1 - 0.12 * ss(0.5, 0.8, org[i])) * (1 + grit[i] * 0.07 + fine[i] * 0.05));
      put(im, i, c);
      let rgh = 0.93;
      const id = st.id[i];
      if (id < 0.2) {
        const rad = 0.18 + 0.3 * frac(id * 17.3);
        const f = st.f1[i] + fine[i] * 0.025;
        if (f < rad) {
          const dome = Math.sqrt(1 - (f / rad) * (f / rad));
          hh += dome * 0.14;
          const sc = 0.6 + 0.45 * frac(id * 31.7);
          const pc: RGB = frac(id * 7.1) < 0.5 ? [0.38, 0.36, 0.33] : [0.42, 0.35, 0.27];
          mixc(im, i, scaleRGB(lerpRGB(pc, c, 0.35), sc * (0.8 + 0.3 * dome)), ss(0, 0.3, dome));
          rgh = lerp(rgh, 0.75, ss(0, 0.3, dome));
        }
      }
      const id2 = st2.id[i];
      if (id2 < 0.18 && st2.f1[i] < 0.3) {
        const d = 1 - st2.f1[i] / 0.3;
        hh += d * 0.04;
        mixc(im, i, scaleRGB([0.40, 0.37, 0.33], 0.6 + 0.45 * frac(id2 * 13.1)), ss(0, 0.5, d) * 0.6);
      }
      hh -= cr[i] * 0.1;
      mul(im, i, 1 - 0.35 * cr[i]);
      h[i] = hh; rough[i] = rgh;
    }
    return { im, h, rough, depth: 0.02, ao: 2.5, aoR: 1 / 160 };
  }

  function genRock(): Out {
    const seed = 3000;
    const k = S / 512;
    const wx = fbm(3, 3, 5, seed + 1), wy = fbm(3, 3, 5, seed + 2);
    const rd = warp(ridged(3, 3, 6, seed + 3), wx, wy, 0.12);
    const big = fbm(2, 2, 5, seed + 4);
    const fine = fbm(24, 24, 4, seed + 5);
    const micro = vnoise(S / 3, S / 3, rng(seed + 6));
    const grains = vnoise(S / 2, S / 2, rng(seed + 11));
    const tint = fbm(3, 3, 4, seed + 7);
    const cr = crackWalks(7, seed + 8, 0.45, 2.4, 0.45, 0.2);
    const crS = blur(cr, 1.5 * k, 2);
    const lich = worley(48, 48, seed + 9);
    const mossF = fbm(5, 5, 5, seed + 10);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld();
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = (y << SH) | x;
      const strata = 0.5 + 0.5 * Math.sin(TAU * ((y / S) * 5 + (wx[i] - 0.5) * 1.6));
      const crack = Math.min(1, cr[i] + crS[i] * 0.8);
      let hh = rd[i] * 0.5 + big[i] * 0.3 + fine[i] * 0.15 + strata * 0.04 + micro[i] * 0.01 - crack * 0.25;
      let c = lerpRGB([0.36, 0.36, 0.35], [0.43, 0.40, 0.36], ss(0.35, 0.7, tint[i]));
      c = scaleRGB(c, 0.76 + (hh - 0.45) * 0.55 + micro[i] * 0.025 + strata * 0.04);
      put(im, i, c);
      if (grains[i] > 1.9) mul(im, i, 1.25);
      else if (grains[i] < -1.9) mul(im, i, 0.7);
      let rgh = 0.86 + micro[i] * 0.03;
      mul(im, i, 1 - crack * 0.42);
      const lid = lich.id[i];
      if (lid < 0.1) {
        const rad = 0.2 + 0.3 * frac(lid * 11.3);
        const f = lich.f1[i] + (fine[i] - 0.5) * 0.3 + micro[i] * 0.03;
        if (f < rad) {
          const lc: RGB = frac(lid * 5.7) < 0.85 ? [0.54, 0.56, 0.48] : [0.58, 0.42, 0.22];
          mixc(im, i, lc, 0.55 * ss(rad, rad * 0.6, f));
          hh += 0.008;
        }
      }
      const mm = ss(0.66, 0.82, mossF[i] + (0.5 - hh) * 0.5 + crS[i] * 0.3) * (0.5 + 0.5 * fine[i]);
      if (mm > 0) {
        const mc = lerpRGB([0.19, 0.23, 0.10], [0.30, 0.33, 0.16], fine[i]);
        mixc(im, i, scaleRGB(mc, 0.85 + micro[i] * 0.1), mm);
        hh += mm * 0.04 + mm * micro[i] * 0.008;
        rgh = lerp(rgh, 0.98, mm);
      }
      h[i] = hh; rough[i] = rgh;
    }
    return { im, h, rough, depth: 0.05, ao: 2.5, aoR: 1 / 60 };
  }

  function genForestFloor(): Out {
    const N2 = S * S, k = S / 512, rnd = rng(4001);
    const soil = fbm(6, 6, 6, 4002);
    const mwx = fbm(6, 6, 3, 4006), mwy = fbm(6, 6, 3, 4007);
    const mossF = warp(fbm(5, 5, 6, 4003, 0.6), mwx, mwy, 0.08);
    const mossFine = fbm(64, 64, 3, 4004);
    const micro = vnoise(S / 2, S / 2, rng(4005));
    const nd = fbm(8, 8, 4, 4008);
    const im = img([0, 0, 0]);
    const h = fld(), z = fld(-1), rough = fld(0.92);
    const mossM = fld();
    for (let i = 0; i < N2; i++) {
      const s = soil[i];
      put(im, i, [0.10 + 0.07 * s, 0.075 + 0.05 * s, 0.05 + 0.03 * s], 1 + micro[i] * 0.06);
      h[i] = s * 0.2 + micro[i] * 0.01;
      const mm = ss(0.65, 0.77, mossF[i] + (mossFine[i] - 0.5) * 0.15);
      mossM[i] = mm;
      if (mm > 0) {
        const mc = lerpRGB([0.16, 0.20, 0.08], [0.29, 0.32, 0.14], mossFine[i]);
        mixc(im, i, scaleRGB(mc, 0.9 + micro[i] * 0.12), mm);
        h[i] += mm * (0.25 + mossFine[i] * 0.2) + mm * micro[i] * 0.02;
        z[i] = h[i] - 0.1;
        rough[i] = lerp(0.92, 0.98, mm);
      }
    }
    const nL = Math.floor(N2 * 0.00004);
    for (let n = 0; n < nL; n++) {
      const x = rnd() * S, y = rnd() * S, r = (6 + rnd() * 6) * k, zz = 0.4 + rnd() * 0.3;
      const lc: RGB = lerpRGB([0.40, 0.28, 0.12], [0.24, 0.16, 0.09], rnd());
      disk(x, y, r, (p, d) => { if (zz > z[p]) { z[p] = zz; h[p] = zz + 0.05 * (1 - d); put(im, p, lc, 0.85 + 0.2 * (1 - d) + micro[p] * 0.05); rough[p] = 0.85; } }, 0.6 + rnd() * 0.3);
    }
    const needleCols: RGB[] = [[0.45, 0.30, 0.15], [0.36, 0.26, 0.17], [0.24, 0.17, 0.10], [0.50, 0.36, 0.20], [0.30, 0.22, 0.14], [0.52, 0.41, 0.26], [0.33, 0.30, 0.22]];
    const nN = Math.floor(N2 * 0.06);
    for (let n = 0; n < nN; n++) {
      const x = rnd() * S, y = rnd() * S;
      const p0 = pix(x, y);
      if (rnd() < mossM[p0] * 0.6 + (1 - nd[p0]) * 0.4) continue;
      const len = (9 + rnd() * 14) * k, ang = rnd() * TAU, zb = 0.2 + rnd() * 0.7;
      const c = scaleRGB(needleCols[Math.floor(rnd() * needleCols.length)], 0.8 + rnd() * 0.35);
      line(x, y, ang, len, Math.max(1, Math.round(k)), (p, t) => {
        const zz = zb + Math.sin(t * Math.PI) * 0.05;
        if (zz > z[p]) { z[p] = zz; h[p] = zz; put(im, p, c, 0.65 + 0.5 * zz); rough[p] = 0.8; }
      });
    }
    const nT = Math.floor(N2 * 0.0004);
    for (let n = 0; n < nT; n++) {
      let x = rnd() * S, y = rnd() * S, a = rnd() * TAU;
      const segs = 3 + Math.floor(rnd() * 4), seg = (8 + rnd() * 14) * k, w = (1.5 + rnd() * 2) * k, zb = 0.9 + rnd() * 0.3;
      const c: RGB = scaleRGB([0.30, 0.25, 0.20], 0.7 + rnd() * 0.5);
      for (let s = 0; s < segs; s++) {
        line(x, y, a, seg, w, (p, t) => {
          const zz = zb + 0.05 * t;
          if (zz > z[p]) { z[p] = zz; h[p] = zz + 0.1; put(im, p, c, 0.95 + 0.3 * micro[p] * 0.1); rough[p] = 0.88; }
        });
        x += Math.cos(a) * seg; y += Math.sin(a) * seg; a += (rnd() - 0.5) * 0.6;
      }
    }
    return { im, h, rough, depth: 0.004, ao: 3, aoR: 1 / 140, aoAlb: 0.6 };
  }

  function genMud(): Out {
    const seed = 5000;
    const wx = fbm(3, 3, 4, seed + 1), wy = fbm(3, 3, 4, seed + 2);
    const base = warp(fbm(4, 4, 7, seed + 3, 0.55), wx, wy, 0.12);
    const ruts = fbm(2, 6, 4, seed + 4);
    const micro = vnoise(S / 3, S / 3, rng(seed + 5));
    const st = worley(36, 36, seed + 6);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld();
    const WATER = 0.27;
    for (let i = 0; i < h.length; i++) {
      let hh = base[i] * 0.8 + ruts[i] * 0.25 + micro[i] * 0.008;
      const wet = 1 - ss(WATER, WATER + 0.14, hh);
      let c = lerpRGB([0.19, 0.15, 0.11], [0.34, 0.28, 0.20], ss(0.35, 0.8, hh));
      c = scaleRGB(c, 1 - wet * 0.3 + micro[i] * 0.04);
      put(im, i, c);
      let rgh = lerp(0.86, 0.32, wet);
      if (st.id[i] < 0.1 && st.f1[i] < 0.3) {
        const d = 1 - st.f1[i] / 0.3;
        hh += d * 0.05;
        mixc(im, i, scaleRGB([0.34, 0.32, 0.29], 0.6 + frac(st.id[i] * 9) * 0.4), ss(0, 0.3, d));
      }
      if (hh < WATER + 0.01) {
        const t = ss(-0.005, 0.03, WATER - hh);
        hh = lerp(hh, WATER + micro[i] * 0.0004, t);
        mixc(im, i, [0.12, 0.10, 0.075], 0.35 + 0.45 * t);
        rgh = lerp(rgh, 0.03, t);
      }
      h[i] = hh; rough[i] = rgh;
    }
    return { im, h, rough, depth: 0.03, ao: 2, aoR: 1 / 60 };
  }

  function genSand(): Out {
    const seed = 6000;
    const wx = fbm(2, 2, 4, seed + 1);
    const grain = vnoise(S / 2, S / 2, rng(seed + 2));
    const grain2 = vnoise(S / 4, S / 4, rng(seed + 3));
    const tint = fbm(4, 4, 4, seed + 4);
    const ripAmp = fbm(3, 3, 3, seed + 7);
    const st = worley(40, 40, seed + 5);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld();
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = (y << SH) | x;
      const ph = frac((y / S) * 11 + (wx[i] - 0.5) * 2.2 + (x / S) * 1);
      const rip = (ph < 0.7 ? ss(0, 0.7, ph) : 1 - ss(0.7, 1, ph)) * ss(0.3, 0.7, ripAmp[i]);
      let hh = rip * 0.22 + tint[i] * 0.3 + grain[i] * 0.02 + grain2[i] * 0.02;
      let c = lerpRGB([0.45, 0.41, 0.34], [0.57, 0.52, 0.43], ss(0.2, 0.8, rip * 0.35 + tint[i] * 0.7));
      c = scaleRGB(c, 1 + grain[i] * 0.06);
      put(im, i, c);
      if (grain[i] > 2.0) mul(im, i, 0.55);
      if (grain[i] < -2.2) mixc(im, i, [0.75, 0.72, 0.66], 0.5);
      let rgh = 0.92;
      if (st.id[i] < 0.07 && st.f1[i] < 0.28) {
        const d = 1 - st.f1[i] / 0.28;
        hh += d * 0.2;
        mixc(im, i, scaleRGB([0.38, 0.36, 0.34], 0.6 + frac(st.id[i] * 9) * 0.6), ss(0, 0.25, d));
        rgh = 0.8;
      }
      h[i] = hh; rough[i] = rgh;
    }
    return { im, h, rough, depth: 0.012, ao: 2, aoR: 1 / 100 };
  }

  function genAsphalt(): Out {
    const seed = 7000;
    const k = S / 512;
    const agg = worley(Math.min(S / 4, 110), Math.min(S / 4, 110), seed + 1);
    const fine = vnoise(S / 2, S / 2, rng(seed + 2));
    const fine2 = vnoise(S / 4, S / 4, rng(seed + 8));
    const wear = fbm(4, 4, 5, seed + 3);
    const wetF = fbm(5, 5, 6, seed + 4, 0.55);
    const cr = crackWalks(5, seed + 5, 0.6, 1.4, 0.35, 0.6);
    const allig = crackField(18, seed + 6, 0.05, 0.1, 0.02);
    const oil = fbm(6, 6, 4, seed + 7);
    const sealM = fbm(3, 3, 3, seed + 9);
    const sealW = blur(cr, 2.5 * k, 2);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld();
    for (let i = 0; i < h.length; i++) {
      const id = agg.id[i];
      const stone = 1 - ss(0.3, 0.5, agg.f1[i]);
      const worn = ss(0.35, 0.8, wear[i]);
      const sc = 0.15 + 0.22 * id;
      const binder = 0.10 + fine2[i] * 0.01;
      let v = lerp(binder, sc, stone * (0.25 + 0.45 * worn));
      v *= 1 + fine[i] * 0.05;
      v += worn * 0.035;
      put(im, i, [v * 0.98, v, v * 1.01]);
      let hh = stone * 0.2 * (0.5 + worn) + fine[i] * 0.02 + wear[i] * 0.1;
      let rgh = 0.9 - stone * worn * 0.1;
      const os = ss(0.76, 0.88, oil[i]);
      if (os > 0) { mul(im, i, 1 - os * 0.35); rgh = lerp(rgh, 0.55, os); }
      const c = Math.max(cr[i], allig[i]);
      hh -= c * 0.3;
      mul(im, i, 1 - c * 0.55);
      const seal = ss(0.12, 0.28, sealW[i]) * ss(0.5, 0.6, sealM[i]);
      if (seal > 0) { mixc(im, i, [0.045, 0.045, 0.05], seal * 0.9); rgh = lerp(rgh, 0.4, seal); hh += seal * 0.06; }
      const wet = ss(0.72, 0.8, wetF[i] + (0.5 - wear[i]) * 0.15);
      if (wet > 0) { mul(im, i, 1 - wet * 0.3); rgh = lerp(rgh, 0.25, wet); hh = lerp(hh, 0.1, wet * 0.92); }
      h[i] = hh; rough[i] = rgh;
    }
    return { im, h, rough, depth: 0.01, ao: 2.5, aoR: 1 / 200, aoAlb: 0.4 };
  }

  function genSidewalk(): Out {
    const seed = 8000;
    const k = S / 512;
    const broom = fbm(6, S / 3, 2, seed + 1, 0.6);
    const fine = vnoise(S / 2, S / 2, rng(seed + 2));
    const mott = fbm(4, 4, 5, seed + 3);
    const stain = fbm(6, 6, 5, seed + 4);
    const dirt = fbm(8, 8, 4, seed + 5);
    const cr = crackWalks(2, seed + 6, 0.55, 1.5, 0.3, 0.55);
    const crS = blur(cr, 1.2 * k, 1);
    const pores = worley(80, 80, seed + 7);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld();
    const rnd = rng(seed + 8);
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = (y << SH) | x, u = x / S, v = y / S;
      const de = Math.min(u, 1 - u, v, 1 - v);
      const joint = 1 - ss(0.003, 0.007, de + fine[i] * 0.0008);
      const border = ss(0.03, 0.04, de);
      let hh = 0.8 - joint * 0.8 + broom[i] * 0.06 * border + fine[i] * 0.01 - (1 - ss(0.007, 0.012, de)) * 0.05;
      const g = 0.56 + (mott[i] - 0.5) * 0.1 + fine[i] * 0.015 + (broom[i] - 0.5) * 0.05 * border;
      let c: RGB = [g * 1.01, g, g * 0.95];
      const ds = ss(0.55, 0.85, stain[i]) * 0.3 + (1 - ss(0.0, 0.12, de)) * 0.3 * clamp01(dirt[i] * 1.6);
      c = lerpRGB(c, [0.32, 0.30, 0.26], clamp01(ds));
      put(im, i, c);
      let rgh = 0.86 + 0.06 * border;
      if (pores.id[i] < 0.12 && pores.f1[i] < 0.25) { hh -= 0.05; mul(im, i, 0.78); }
      const crk = Math.min(1, cr[i] + crS[i] * 0.5);
      hh -= crk * 0.35; mul(im, i, 1 - crk * 0.55);
      if (joint > 0) {
        const weed = ss(0.6, 0.75, dirt[i]) * joint;
        mixc(im, i, [0.13, 0.11, 0.09], joint * 0.85);
        mixc(im, i, [0.20, 0.27, 0.10], weed * (0.5 + fine[i] * 0.5));
        rgh = lerp(rgh, 0.95, joint);
      }
      h[i] = hh; rough[i] = rgh;
    }
    // chewing gum / tar spots
    for (let n = 0; n < 10; n++) {
      const gx = rnd() * S, gy = rnd() * S, r = (3 + rnd() * 4) * k;
      disk(gx, gy, r, (p, d) => { mixc(im, p, [0.17, 0.165, 0.16], 0.85 * ss(1, 0.7, d)); h[p] += 0.02 * (1 - d); rough[p] = 0.6; });
    }
    return { im, h, rough, depth: 0.008, ao: 2, aoR: 1 / 150 };
  }

  function genConcrete(): Out {
    const seed = 9000;
    const k = S / 512;
    const mott = fbm(4, 4, 6, seed + 1);
    const fine = vnoise(S / 2, S / 2, rng(seed + 2));
    const mid = fbm(32, 32, 3, seed + 3);
    const streak = fbm(28, 2, 3, seed + 4, 0.6);
    const streakMask = fbm(3, 3, 3, seed + 5);
    const pores = worley(70, 70, seed + 6);
    const cr = crackWalks(3, seed + 7, 0.45, 1.1, 0.3, 0.6);
    const crS = blur(cr, 1.2 * k, 1);
    const eff = fbm(5, 5, 4, seed + 8);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld();
    for (let i = 0; i < h.length; i++) {
      const g = 0.50 + (mott[i] - 0.5) * 0.14 + (mid[i] - 0.5) * 0.06 + fine[i] * 0.012;
      let c: RGB = [g * 1.02, g, g * 0.95];
      const sk = ss(0.5, 0.75, streak[i]) * ss(0.4, 0.65, streakMask[i]);
      c = lerpRGB(c, [0.28, 0.27, 0.25], sk * 0.55);
      c = lerpRGB(c, [0.66, 0.65, 0.61], ss(0.72, 0.9, eff[i]) * 0.35);
      put(im, i, c);
      let hh = mid[i] * 0.15 + fine[i] * 0.012 + mott[i] * 0.1;
      let rgh = 0.88 + fine[i] * 0.02 - sk * 0.1;
      if (pores.id[i] < 0.18) {
        const rad = 0.12 + 0.2 * frac(pores.id[i] * 9.3);
        if (pores.f1[i] < rad) { const d = 1 - pores.f1[i] / rad; hh -= d * 0.12; mul(im, i, 1 - d * 0.4); rgh = 0.95; }
      }
      const crk = Math.min(1, cr[i] + crS[i] * 0.5);
      hh -= crk * 0.3; mul(im, i, 1 - crk * 0.5);
      h[i] = hh; rough[i] = rgh;
    }
    return { im, h, rough, depth: 0.012, ao: 2.5, aoR: 1 / 200 };
  }

  function genBrick(red: boolean): Out {
    const seed = red ? 10000 : 10500;
    const COLS = 5, ROWS = 15, BW = 1 / COLS, BH = 1 / ROWS, J = 0.0048;
    const edgeN = fbm(40, 40, 3, seed + 1);
    const surf = fbm(48, 48, 3, seed + 2);
    const mott = fbm(12, 12, 4, seed + 3);
    const fine = vnoise(S / 2, S / 2, rng(seed + 4));
    const pits = worley(110, 110, seed + 5);
    const grime = fbm(6, 6, 5, seed + 6);
    const streak = fbm(30, 2, 3, seed + 7, 0.6);
    const moss = fbm(6, 6, 5, seed + 8);
    const eff = fbm(6, 6, 4, seed + 9);
    const chip = worley(10, 30, seed + 10);
    const pal: RGB[] = red
      ? [[0.47, 0.19, 0.13], [0.40, 0.16, 0.11], [0.54, 0.25, 0.17], [0.36, 0.15, 0.11], [0.50, 0.22, 0.15], [0.30, 0.13, 0.10], [0.56, 0.30, 0.21]]
      : [[0.50, 0.38, 0.28], [0.44, 0.33, 0.25], [0.55, 0.43, 0.32], [0.40, 0.30, 0.23], [0.47, 0.36, 0.28], [0.36, 0.28, 0.22], [0.58, 0.47, 0.36]];
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld();
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = (y << SH) | x, u = x / S, v = y / S;
      const row = Math.floor(v * ROWS);
      const cu = u * COLS + (row & 1) * 0.5;
      const col = ((Math.floor(cu) % COLS) + COLS) % COLS;
      const lx = cu - Math.floor(cu), ly = v * ROWS - row;
      const ex = Math.min(lx, 1 - lx) * BW, ey = Math.min(ly, 1 - ly) * BH;
      const id = hash(row, col, seed);
      const chipD = chip.f1[i] < 0.25 && chip.id[i] < 0.3 ? (0.25 - chip.f1[i]) * 0.03 : 0;
      const e = Math.min(ex, ey) - J + (edgeN[i] - 0.5) * 0.006 - chipD;
      const bm = ss(0, 0.0025, e);
      const bev = ss(-0.0005, 0.006, e);
      let bc = pal[Math.floor(id * pal.length)];
      const fire = lerp(0.85, 1.12, frac(id * 13.7)) * (1 - 0.18 * ss(0.3, 1, frac(id * 7.3) > 0.5 ? lx : 1 - lx) * frac(id * 3.1));
      bc = scaleRGB(bc, fire * (0.88 + (mott[i] - 0.5) * 0.35 + fine[i] * 0.04));
      const tiltH = (frac(id * 5.9) - 0.5) * 0.06 * (lx - 0.5);
      let bh = 0.85 + (surf[i] - 0.5) * 0.16 + fine[i] * 0.01 + tiltH;
      if (pits.id[i] < 0.2 && pits.f1[i] < 0.3) { const d = 1 - pits.f1[i] / 0.3; bh -= d * 0.06; bc = scaleRGB(bc, 1 - d * 0.3); }
      const mg = 0.56 + fine[i] * 0.04 + (mott[i] - 0.5) * 0.1;
      const mc: RGB = [mg, mg * 0.97, mg * 0.9];
      const mh = 0.12 + fine[i] * 0.03 + (surf[i] - 0.5) * 0.05;
      put(im, i, lerpRGB(mc, bc, bm));
      let hh = lerp(mh, bh, bev);
      let rgh = lerp(0.95, 0.82 + fine[i] * 0.03, bm);
      mixc(im, i, [0.72, 0.70, 0.65], ss(0.7, 0.9, eff[i]) * 0.3);
      const gm = ss(0.58, 0.95, streak[i] * 0.75 + grime[i] * 0.4) * 0.32;
      mixc(im, i, [0.12, 0.10, 0.085], gm);
      const mm = ss(0.66, 0.8, moss[i]) * (1 - bm * 0.8) * (0.6 + fine[i] * 0.2 + 0.2);
      if (mm > 0) { mixc(im, i, [0.21, 0.26, 0.11], mm); hh += mm * 0.08; rgh = lerp(rgh, 1, mm); }
      h[i] = hh; rough[i] = rgh;
    }
    return { im, h, rough, depth: 0.012, ao: 2.2, aoR: 1 / 120 };
  }

  function genSiding(): Out {
    const seed = 11000;
    const BOARDS = 6;
    const grain = transpose(woodGrain(seed + 1, 30, 0, 0.12).grain);
    const peel = fbm(4, 14, 6, seed + 2, 0.6);
    const peelW = fbm(24, 48, 3, seed + 3);
    const fade = fbm(3, 3, 4, seed + 4);
    const streak = fbm(48, 2, 3, seed + 5, 0.6);
    const grime = fbm(5, 5, 5, seed + 6);
    const fine = vnoise(S / 2, S / 2, rng(seed + 7));
    const mildew = fbm(10, 10, 4, seed + 8);
    const ur = fld(), ug = fld(), ub = fld(), mask = fld(), shade = fld(), grimeA = fld();
    const h = fld(), rough = fld();
    const seams: number[] = [];
    for (let b = 0; b < BOARDS; b++) seams.push(hash(b, 7, seed), hash(b, 9, seed));
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = (y << SH) | x, u = x / S, v = y / S;
      const board = Math.floor(v * BOARDS);
      const ly = v * BOARDS - board;
      let bh = 0.25 + 0.75 * (1 - ly);
      bh -= (1 - ss(0, 0.06, ly)) * 0.12;
      bh += (grain[i] - 0.5) * 0.03 + fine[i] * 0.004;
      const sd = Math.min(wd(u, seams[board * 2]), seams[board * 2 + 1] > 0.6 ? wd(u, frac(seams[board * 2] + 0.5)) : 1);
      const seam = 1 - ss(0.0015, 0.004, sd);
      bh -= seam * 0.15;
      const wg = grain[i];
      const wc = lerpRGB([0.47, 0.43, 0.37], [0.27, 0.24, 0.21], wg);
      ur[i] = wc[0]; ug[i] = wc[1]; ub[i] = wc[2];
      const pv = peel[i] + (peelW[i] - 0.5) * 0.35 + (1 - ss(0, 0.2, ly)) * 0.1 + seam * 0.3 + (1 - ss(0, 0.02, sd)) * 0.1;
      const m = 1 - ss(0.75, 0.76, pv);
      mask[i] = m;
      const edge = ss(0.71, 0.75, pv) * m;
      bh += m * 0.03 + edge * 0.04;
      shade[i] = (0.88 + (fade[i] - 0.5) * 0.2 + fine[i] * 0.01) * (1 - 0.04 * wg) * (1 - edge * 0.1);
      const sh = ss(0.84, 1, ly);
      shade[i] *= 1 - sh * 0.2;
      ur[i] *= 1 - sh * 0.3; ug[i] *= 1 - sh * 0.3; ub[i] *= 1 - sh * 0.3;
      const gs = ss(0.55, 0.9, streak[i] * 0.6 + grime[i] * 0.55) * 0.25;
      const md = ss(0.6, 0.8, mildew[i]) * (1 - ss(0, 0.3, ly)) * 0.35;
      grimeA[i] = clamp01(gs + md + sh * 0.12 + seam * 0.35);
      h[i] = bh;
      rough[i] = lerp(0.92, 0.68 + fine[i] * 0.02, m);
    }
    const paint: PaintLayer = { ur, ug, ub, mask, shade, grime: grimeA, grimeCol: [0.20, 0.20, 0.16], paint: [0.84, 0.83, 0.78] };
    return { im: img([0, 0, 0]), h, rough, depth: 0.02, ao: 2, aoR: 1 / 100, paint };
  }

  function genPlaster(): Out {
    const seed = 12000;
    const k = S / 512;
    const bumps = fbm(S / 8, S / 8, 2, seed + 1, 0.6);
    const trowel = fbm(10, 6, 4, seed + 2);
    const mott = fbm(4, 4, 5, seed + 3);
    const stainF = fbm(3, 3, 5, seed + 4);
    const mold = fbm(14, 14, 4, seed + 5);
    const chipF = fbm(4, 4, 7, seed + 6, 0.6);
    const fine = vnoise(S / 2, S / 2, rng(seed + 7));
    const cr = crackWalks(4, seed + 8, 0.5, 1.2, 0.45, 0.8);
    const crS = blur(cr, 1.2 * k, 1);
    const streak = fbm(26, 2, 3, seed + 9, 0.6);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld();
    for (let i = 0; i < h.length; i++) {
      let hh = 0.7 + bumps[i] * 0.08 + trowel[i] * 0.12 + fine[i] * 0.006;
      const g = 0.72 + (mott[i] - 0.5) * 0.1 + fine[i] * 0.01;
      let c: RGB = [g, g * 0.965, g * 0.89];
      const sv = stainF[i];
      const inside = ss(0.64, 0.66, sv);
      const ring = ss(0.62, 0.65, sv) * (1 - ss(0.655, 0.7, sv));
      c = lerpRGB(c, [0.62, 0.57, 0.46], inside * 0.12);
      c = lerpRGB(c, [0.50, 0.43, 0.32], ring * 0.2);
      c = lerpRGB(c, [0.38, 0.36, 0.31], ss(0.55, 0.85, streak[i]) * 0.3);
      const ml = ss(0.74, 0.85, mold[i] + fine[i] * 0.05);
      c = lerpRGB(c, [0.30, 0.30, 0.25], ml * 0.14);
      put(im, i, c);
      let rgh = 0.9 + fine[i] * 0.02;
      const ch = ss(0.8, 0.81, chipF[i] + fine[i] * 0.004);
      if (ch > 0) {
        hh = lerp(hh, 0.35 + bumps[i] * 0.25 + fine[i] * 0.02, ch);
        mixc(im, i, scaleRGB([0.45, 0.43, 0.40], 0.9 + fine[i] * 0.06), ch);
        rgh = lerp(rgh, 0.97, ch);
      }
      const crk = Math.min(1, cr[i] + crS[i] * 0.4);
      hh -= crk * 0.25; mul(im, i, 1 - crk * 0.5);
      h[i] = hh; rough[i] = rgh;
    }
    return { im, h, rough, depth: 0.012, ao: 2.5, aoR: 1 / 150 };
  }

  function genWoodFloor(): Out {
    const seed = 13000;
    const ROWS = 8, PW = 1 / ROWS;
    const wg = woodGrain(seed + 1, 44, 2, 0.15);
    const grain = transpose(wg.grain), knot = transpose(wg.knot), band = transpose(wg.band);
    const wear = fbm(3, 3, 5, seed + 2);
    const fine = vnoise(S / 2, S / 2, rng(seed + 3));
    const stain = fbm(5, 5, 4, seed + 4);
    const scr = scratches(Math.floor(160 * (S / 512)), seed + 5, 0.02, 0.12, 0, 0.6);
    const tints: RGB[] = [[0.50, 0.36, 0.22], [0.44, 0.30, 0.18], [0.54, 0.41, 0.27], [0.40, 0.29, 0.20]];
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld();
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = (y << SH) | x, u = x / S, v = y / S;
      const row = Math.floor(v * ROWS);
      const o1 = hash(row, 1, seed);
      const su = frac(u - o1);
      const seg = su < 0.5 ? 0 : 1;
      const pid = hash(row, seg, seed + 1);
      const ly = v * ROWS - row;
      const ey = Math.min(ly, 1 - ly) * PW;
      const ex = Math.min(su, Math.abs(su - 0.5), 1 - su);
      const e = Math.min(ex, ey);
      const gap = 1 - ss(0.0005, 0.002, e);
      const gx = x + Math.floor(pid * S), gy = y + Math.floor(frac(pid * 7.7) * S);
      const gp = pix(gx, gy);
      const gr = grain[gp], kn = knot[gp], bd = band[gp];
      const tone = 0.85 + 0.3 * frac(pid * 3.3);
      const base = tints[Math.floor(frac(pid * 11.1) * tints.length)];
      let c = lerpRGB(base, scaleRGB(base, 0.55), gr * 0.85);
      c = scaleRGB(c, tone * (1 + fine[i] * 0.03) * (0.9 + bd * 0.2));
      c = lerpRGB(c, [0.16, 0.10, 0.06], kn * 0.8);
      const w = ss(0.45, 0.85, wear[i]);
      c = lerpRGB(c, [0.50, 0.45, 0.38], w * 0.35);
      c = lerpRGB(c, [0.20, 0.15, 0.10], ss(0.72, 0.86, stain[i]) * 0.4);
      c = lerpRGB(c, [0.62, 0.55, 0.45], scr[i] * 0.3);
      put(im, i, c);
      mixc(im, i, [0.06, 0.05, 0.04], gap);
      h[i] = 0.8 - gap * 0.8 + (gr - 0.5) * 0.015 - scr[i] * 0.02 + fine[i] * 0.003 + (frac(pid * 5.1) - 0.5) * 0.03;
      rough[i] = lerp(0.42 + gr * 0.12, 0.78, w) + scr[i] * 0.15 + gap * 0.3;
    }
    return { im, h, rough, depth: 0.01, ao: 2, aoR: 1 / 200 };
  }

  function genTile(): Out {
    const seed = 14000;
    const T = 4, G = 0.0025;
    const k = S / 512;
    const fine = vnoise(S / 2, S / 2, rng(seed + 1));
    const dirt = fbm(6, 6, 5, seed + 2);
    const stain = fbm(8, 8, 4, seed + 3);
    const cr = crackWalks(6, seed + 4, 0.18, 1.0, 0.4, 0.5);
    const glaze = fbm(16, 16, 3, seed + 5);
    void k;
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld();
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = (y << SH) | x, u = x / S, v = y / S;
      const tx = Math.floor(u * T), ty = Math.floor(v * T);
      const lx = u * T - tx, ly = v * T - ty;
      const e = Math.min(lx, 1 - lx, ly, 1 - ly) / T - G;
      const tm = ss(0, 0.0015, e);
      const id = hash(tx, ty, seed);
      const cracked = id < 0.3 ? 1 : 0;
      const tv = 0.76 + (frac(id * 9.1) - 0.5) * 0.06;
      let c: RGB = [tv, tv * 0.97, tv * 0.91];
      c = lerpRGB(c, [0.47, 0.42, 0.34], ss(0.58, 0.88, dirt[i]) * 0.3);
      c = lerpRGB(c, [0.55, 0.47, 0.35], ss(0.74, 0.86, stain[i]) * 0.25);
      const gg = 0.28 + fine[i] * 0.03 + (dirt[i] - 0.5) * 0.12;
      const gc: RGB = [gg, gg * 0.95, gg * 0.88];
      const crk = cr[i] * cracked;
      put(im, i, lerpRGB(gc, c, tm));
      mul(im, i, 1 - crk * 0.6);
      const tilt = (frac(id * 3.7) - 0.5) * 0.04 * (lx - 0.5) + (frac(id * 5.3) - 0.5) * 0.04 * (ly - 0.5);
      h[i] = lerp(0.1 + fine[i] * 0.02, 0.9 + tilt + glaze[i] * 0.01, ss(-0.0005, 0.003, e)) - crk * 0.2;
      rough[i] = lerp(0.95, 0.2 + ss(0.5, 0.85, dirt[i]) * 0.35 + glaze[i] * 0.06, tm) + crk * 0.4;
    }
    return { im, h, rough, depth: 0.01, ao: 2, aoR: 1 / 150 };
  }

  function genRoofShingle(): Out {
    const seed = 15000;
    const ROWS = 8, TABS = 3;
    const gran = vnoise(S / 2, S / 2, rng(seed + 1));
    const gran2 = vnoise(S / 3, S / 3, rng(seed + 2));
    const wear = fbm(5, 5, 5, seed + 3);
    const mossF = fbm(5, 5, 5, seed + 4);
    const mossFine = fbm(48, 48, 3, seed + 5);
    const streak = fbm(30, 2, 3, seed + 6, 0.6);
    const lich = worley(40, 40, seed + 7);
    const edgeN = fbm(32, 32, 2, seed + 8);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld();
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = (y << SH) | x, u = x / S, v = y / S;
      const row = Math.floor(v * ROWS);
      const ly = v * ROWS - row;
      const cu = u * TABS + (row & 1) * 0.5;
      const tab = ((Math.floor(cu) % TABS) + TABS) % TABS;
      const lx = cu - Math.floor(cu);
      const slot = 1 - ss(0.008, 0.016, Math.min(lx, 1 - lx) / TABS + (edgeN[i] - 0.5) * 0.002);
      const id = hash(row, tab, seed);
      let hh = 0.35 + 0.65 * (1 - ly) - (1 - ss(0, 0.04, ly)) * 0.08 + gran[i] * 0.02 + gran2[i] * 0.015;
      const tv = 0.21 + (frac(id * 7.7) - 0.5) * 0.05;
      let c: RGB = [tv * 1.02, tv, tv * 0.97];
      const gn = gran[i];
      if (gn > 1.3) c = scaleRGB(c, 1.3);
      else if (gn < -1.3) c = scaleRGB(c, 0.65);
      if (gran2[i] > 1.6) c = lerpRGB(c, [0.34, 0.27, 0.21], 0.4);
      const wl = ss(0.7, 0.82, wear[i]);
      c = lerpRGB(c, [0.11, 0.11, 0.11], wl * 0.5);
      put(im, i, c);
      let rgh = lerp(0.9, 0.7, wl);
      if (slot > 0) { hh = lerp(hh, 0.1, slot); mul(im, i, 1 - slot * 0.7); }
      mul(im, i, 1 - ss(0.88, 1, ly) * 0.4);
      mixc(im, i, [0.09, 0.09, 0.08], ss(0.55, 0.88, streak[i]) * 0.3);
      if (lich.id[i] < 0.1 && lich.f1[i] < 0.3) mixc(im, i, [0.52, 0.54, 0.45], 0.5 * ss(0.3, 0.15, lich.f1[i]));
      const mm = ss(0.64, 0.8, mossF[i] + (1 - ly) * 0.06 + slot * 0.1) * ss(0.3, 0.7, mossFine[i]);
      if (mm > 0) {
        mixc(im, i, lerpRGB([0.17, 0.21, 0.09], [0.29, 0.32, 0.14], mossFine[i]), mm);
        hh += mm * 0.25 + mm * gran[i] * 0.02; rgh = lerp(rgh, 1, mm);
      }
      h[i] = hh; rough[i] = rgh;
    }
    return { im, h, rough, depth: 0.012, ao: 2.2, aoR: 1 / 100 };
  }

  function genRoofMetal(): Out {
    const seed = 16000;
    const RIBS = 12;
    const spang = worley(48, 48, seed + 1);
    const mott = fbm(4, 4, 5, seed + 2);
    const fine = vnoise(S / 2, S / 2, rng(seed + 3));
    const streak = fbm(64, 3, 3, seed + 4, 0.6);
    const rustMask = fbm(3, 3, 5, seed + 5);
    const rustSpots = fbm(10, 10, 6, seed + 6, 0.6);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld(), metal = fld();
    const screwsV = [0.1, 0.6];
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = (y << SH) | x, v = y / S;
      const ph = (x / S) * RIBS;
      const wave = 0.5 + 0.5 * Math.cos(TAU * ph);
      let hh = wave * 0.9 + fine[i] * 0.003;
      const sp = 0.50 + (spang.id[i] - 0.5) * 0.045 + (mott[i] - 0.5) * 0.08;
      let c: RGB = [sp * 0.98, sp, sp * 1.01];
      const ox = ss(0.55, 0.8, mott[i]);
      c = lerpRGB(c, [0.34, 0.35, 0.34], ox * 0.45);
      let rgh = 0.48 + (spang.id[i] - 0.5) * 0.08 + ox * 0.25;
      let mt = 1;
      let halo = 0;
      const rib = Math.round(ph);
      if (rib % 2 === 0) {
        const du = (ph - rib) / RIBS;
        for (const sv of screwsV) {
          let dv = v - sv; dv -= Math.round(dv);
          const d = hyp(du, dv);
          if (d < 0.007) { hh += 0.06 * (1 - d / 0.007); c = [0.30, 0.30, 0.30]; rgh = 0.5; }
          if (dv < 0.004 && dv > -0.25) halo = Math.max(halo, (1 - ss(0.002, 0.01, Math.abs(du))) * (1 - ss(0, 0.25, -dv)) * (dv > 0 ? 1 - ss(0, 0.006, dv) : 1));
        }
      }
      const rm = ss(0.5, 0.8, rustMask[i]);
      const rs = rm * ss(0.45, 0.75, streak[i]) * 0.9 + ss(0.74, 0.8, rustSpots[i]) * (0.3 + rm) + halo * 0.7;
      const rust = clamp01(rs);
      if (rust > 0) {
        const rc = lerpRGB([0.42, 0.21, 0.09], [0.24, 0.12, 0.06], clamp01(fine[i] * 0.4 + 0.5));
        c = lerpRGB(c, rc, rust);
        mt = 1 - ss(0.3, 0.7, rust);
        rgh = lerp(rgh, 0.88, rust);
        hh += rust * fine[i] * 0.004;
      }
      c = lerpRGB(c, [0.2, 0.18, 0.15], (1 - wave) * 0.25 * ss(0.4, 0.7, streak[i]));
      put(im, i, c);
      h[i] = hh; rough[i] = rgh; metal[i] = mt;
    }
    return { im, h, rough, metal, depth: 0.012, ao: 1.0, aoR: 1 / 100, aoAlb: 0.3 };
  }

  function genWood(dark: boolean): Out {
    const seed = dark ? 17500 : 17000;
    const wg = woodGrain(seed + 1, dark ? 34 : 26, 2, 0.18);
    const fine = vnoise(S / 2, S / 2, rng(seed + 2));
    const silver = fbm(3, 3, 5, seed + 3);
    const checks = fbm(S / 4, 3, 2, seed + 4, 0.5);
    const checkMask = fbm(4, 2, 3, seed + 5);
    const stain = fbm(5, 5, 4, seed + 6);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld();
    for (let i = 0; i < h.length; i++) {
      const g = wg.grain[i], kn = wg.knot[i], bd = wg.band[i];
      let c: RGB;
      if (dark) {
        c = lerpRGB([0.25, 0.165, 0.10], [0.12, 0.075, 0.045], g);
        c = lerpRGB(c, [0.30, 0.25, 0.20], ss(0.6, 0.85, silver[i]) * 0.3);
      } else {
        c = lerpRGB([0.50, 0.40, 0.29], [0.31, 0.23, 0.16], g);
        c = lerpRGB(c, [0.54, 0.51, 0.46], ss(0.4, 0.8, silver[i]) * 0.55 * (1 - g * 0.5));
      }
      c = scaleRGB(c, (1 + fine[i] * 0.03) * (0.88 + bd * 0.24));
      c = lerpRGB(c, dark ? [0.07, 0.045, 0.03] : [0.18, 0.12, 0.08], kn * 0.85);
      c = lerpRGB(c, [0.15, 0.13, 0.11], ss(0.75, 0.9, stain[i]) * 0.2);
      const ck = (1 - ss(0.1, 0.18, checks[i])) * ss(0.64, 0.74, checkMask[i]);
      put(im, i, c);
      mul(im, i, 1 - ck * 0.7);
      h[i] = (1 - g) * 0.12 + fine[i] * 0.004 - ck * 0.3 + kn * 0.04;
      rough[i] = (dark ? 0.62 : 0.82) + g * 0.08 + ck * 0.1;
    }
    return { im, h, rough, depth: 0.01, ao: 2, aoR: 1 / 200 };
  }

  function genBark(): Out {
    const seed = 18000;
    const wx = fbm(3, 2, 4, seed + 1), wy = fbm(3, 2, 4, seed + 2);
    const pl = worley(9, 2, seed + 3);
    const e0 = fld();
    for (let i = 0; i < e0.length; i++) e0[i] = pl.f2[i] - pl.f1[i];
    const edge = warp(e0, wx, wy, 0.1);
    const sub = worley(12, 7, seed + 4);
    const fib = fbm(Math.max(16, S / 8), 6, 2, seed + 5, 0.55);
    const ridge = ridged(10, 2, 4, seed + 11, 0.5);
    const flake = fbm(12, 8, 4, seed + 6);
    const micro = vnoise(S / 3, S / 3, rng(seed + 7));
    const mossF = fbm(3, 3, 5, seed + 8);
    const lich = worley(30, 30, seed + 9);
    const tint = fbm(4, 3, 4, seed + 10);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld();
    for (let i = 0; i < h.length; i++) {
      // corky plates separated by deep, interlaced vertical furrows
      const plate = ss(0.0, 0.38, edge[i] + (flake[i] - 0.5) * 0.15 + (ridge[i] - 0.5) * 0.12);
      const minor = 1 - ss(0.0, 0.07, sub.f2[i] - sub.f1[i] + (fib[i] - 0.5) * 0.1);
      let hh = plate * 0.75 + ridge[i] * 0.15 * plate + (fib[i] - 0.5) * 0.1 + (flake[i] - 0.5) * 0.1 + micro[i] * 0.01 - minor * plate * 0.1;
      hh = Math.max(0, hh);
      const top = ss(0.4, 0.85, hh);
      const wall = ss(0.05, 0.4, hh) * (1 - top);
      let c: RGB = [0.11, 0.07, 0.05];
      c = lerpRGB(c, lerpRGB([0.27, 0.15, 0.09], [0.34, 0.20, 0.12], tint[i]), wall);
      c = lerpRGB(c, lerpRGB([0.32, 0.27, 0.23], [0.43, 0.39, 0.34], ss(0.3, 0.8, flake[i])), top);
      c = scaleRGB(c, (1 + micro[i] * 0.05) * (0.88 + fib[i] * 0.24) * (1 - minor * top * 0.3));
      put(im, i, c);
      let rgh = 0.93;
      if (lich.id[i] < 0.1 && lich.f1[i] < 0.35 && hh > 0.5) mixc(im, i, [0.50, 0.53, 0.45], 0.55 * ss(0.35, 0.15, lich.f1[i] + micro[i] * 0.05));
      const mm = ss(0.62, 0.78, mossF[i]) * wall * 1.2;
      if (mm > 0) { mixc(im, i, lerpRGB([0.17, 0.20, 0.08], [0.26, 0.29, 0.12], fib[i]), clamp01(mm) * 0.8); rgh = 0.98; }
      h[i] = hh; rough[i] = rgh;
    }
    return { im, h, rough, depth: 0.06, ao: 2.5, aoR: 1 / 50, aoAlb: 0.6 };
  }


  function genBirchBark(): Out {
    const seed = 19000;
    const rnd = rng(seed);
    const k = S / 512;
    const mott = fbm(4, 8, 5, seed + 1);
    const fine = vnoise(S / 2, S / 4, rng(seed + 2));
    const darkF = fbm(2, 6, 5, seed + 3, 0.55);
    const darkFine = fbm(12, 32, 3, seed + 4);
    const bandF = fbm(1, 12, 3, seed + 5);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld();
    for (let i = 0; i < h.length; i++) {
      const g = 0.80 + (mott[i] - 0.5) * 0.14 + fine[i] * 0.012 - ss(0.55, 0.8, bandF[i]) * 0.05;
      put(im, i, [g, g * 0.985, g * 0.945]);
      mixc(im, i, [0.55, 0.54, 0.51], ss(0.62, 0.82, mott[i]) * 0.4);
      h[i] = 0.5 + mott[i] * 0.05 + fine[i] * 0.004;
      rough[i] = 0.55 + fine[i] * 0.03;
    }
    const nLen = Math.floor(480 * k * k);
    for (let n = 0; n < nLen; n++) {
      const x = rnd() * S, y = rnd() * S;
      const len = (5 + rnd() * rnd() * 60) * k, w = (0.8 + rnd() * 2) * k;
      const dark = 0.12 + rnd() * 0.16;
      const warm = rnd() < 0.3;
      line(x, y, (rnd() - 0.5) * 0.06, len, w, (p, t) => {
        const f = Math.sin(Math.PI * t);
        mixc(im, p, warm ? [dark * 1.5, dark * 1.15, dark * 0.9] : [dark * 1.05, dark, dark * 0.95], 0.85 * Math.min(1, f * 3));
        h[p] = Math.min(h[p], 0.42);
        rough[p] = 0.85;
      });
    }
    const nPeel = Math.floor(18 * k);
    for (let n = 0; n < nPeel; n++) {
      const x = rnd() * S, y = rnd() * S, len = (15 + rnd() * 45) * k, w = (2 + rnd() * 4) * k;
      line(x, y, (rnd() - 0.5) * 0.1, len, w, (p, t) => {
        const f = Math.sin(Math.PI * t);
        if (f > 0.25) { mixc(im, p, [0.70, 0.55, 0.43], 0.6); h[p] = 0.46; rough[p] = 0.7; }
      });
    }
    for (let i = 0; i < h.length; i++) {
      const dk = ss(0.77, 0.81, darkF[i] + (darkFine[i] - 0.5) * 0.25);
      if (dk > 0) {
        mixc(im, i, scaleRGB([0.10, 0.09, 0.08], 0.7 + darkFine[i] * 0.6), dk);
        h[i] = lerp(h[i], 0.55 + darkFine[i] * 0.3, dk);
        rough[i] = lerp(rough[i], 0.92, dk);
      }
    }
    return { im, h, rough, depth: 0.01, ao: 2, aoR: 1 / 150 };
  }

  function genMetal(kind: 'metal' | 'dark' | 'brushed'): Out {
    const seed = kind === 'metal' ? 20000 : kind === 'dark' ? 20500 : 21000;
    const mott = fbm(4, 4, 5, seed + 1);
    const mill = fbm(16, 16, 4, seed + 8);
    const fine = vnoise(S / 2, S / 2, rng(seed + 2));
    const grime = fbm(5, 5, 5, seed + 3);
    const streak = fbm(36, 2, 3, seed + 9, 0.6);
    const spots = fbm(14, 14, 5, seed + 4, 0.6);
    const cluster = fbm(2, 2, 3, seed + 10);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld(), metal = fld(1);
    let brush: F | null = null, brush2: F | null = null;
    if (kind === 'brushed') { brush = fbm(2, S / 2, 2, seed + 5, 0.7); brush2 = fbm(8, S / 4, 2, seed + 6, 0.6); }
    const scr = scratches(Math.floor((kind === 'brushed' ? 60 : 200) * (S / 512)), seed + 7, 0.01, 0.1, null, 0);
    for (let i = 0; i < h.length; i++) {
      let base: number, rgh: number;
      if (kind === 'metal') { base = 0.50 + (mott[i] - 0.5) * 0.08 + (mill[i] - 0.5) * 0.06; rgh = 0.45 + (mott[i] - 0.5) * 0.15 + (mill[i] - 0.5) * 0.1; }
      else if (kind === 'dark') { base = 0.24 + (mott[i] - 0.5) * 0.05 + (mill[i] - 0.5) * 0.03; rgh = 0.42 + (mott[i] - 0.5) * 0.12 + (mill[i] - 0.5) * 0.08; }
      else { base = 0.62 + (brush![i] - 0.5) * 0.09 + (brush2![i] - 0.5) * 0.05; rgh = 0.3 + (brush![i] - 0.5) * 0.12 + (mott[i] - 0.5) * 0.06; }
      base += fine[i] * 0.008;
      let c: RGB = [base * 0.99, base, base * 1.015];
      const sb = base * 1.25 + 0.08;
      c = lerpRGB(c, [sb, sb, sb], scr[i] * 0.5);
      rgh -= scr[i] * 0.1;
      const gm = (ss(0.55, 0.9, grime[i]) * 0.5 + ss(0.55, 0.85, streak[i]) * 0.5) * (kind === 'brushed' ? 0.25 : 0.4);
      c = lerpRGB(c, [0.14, 0.13, 0.11], gm);
      rgh = lerp(rgh, 0.8, gm);
      let mt = 1 - gm * 0.7;
      let hh = fine[i] * 0.004 - scr[i] * 0.03 + (brush ? (brush[i] - 0.5) * 0.02 : 0);
      if (kind !== 'brushed') {
        const rs = ss(0.86, 0.9, spots[i] + (cluster[i] - 0.5) * 0.3);
        if (rs > 0) { c = lerpRGB(c, [0.26, 0.15, 0.08], rs * 0.85); mt = Math.min(mt, 1 - rs); rgh = lerp(rgh, 0.9, rs); hh += rs * 0.03 * (1 + fine[i] * 0.3); }
      }
      put(im, i, c);
      h[i] = hh; rough[i] = rgh; metal[i] = mt;
    }
    return { im, h, rough, metal, depth: 0.012, ao: 2, aoR: 1 / 200, aoAlb: 0.3 };
  }

  function genMetalRust(): Out {
    const seed = 22000;
    const wx = fbm(4, 4, 4, seed + 1), wy = fbm(4, 4, 4, seed + 2);
    const rustF = warp(fbm(4, 4, 7, seed + 3, 0.6), wx, wy, 0.08);
    const flakes = worley(40, 40, seed + 4);
    const fine = vnoise(S / 2, S / 2, rng(seed + 5));
    const mid = fbm(16, 16, 4, seed + 6, 0.55);
    const dark = fbm(6, 6, 5, seed + 10);
    const streak = fbm(40, 2, 3, seed + 7, 0.6);
    const pits = worley(120, 120, seed + 8);
    const paintF = fbm(3, 3, 7, seed + 9, 0.62);
    const paintD = fbm(32, 32, 3, seed + 11);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld(), metal = fld();
    for (let i = 0; i < h.length; i++) {
      const r = rustF[i];
      const t = clamp01(mid[i] * 0.9 - 0.2 + fine[i] * 0.05);
      let c = lerpRGB([0.45, 0.22, 0.09], [0.22, 0.11, 0.055], t);
      c = lerpRGB(c, [0.55, 0.32, 0.13], ss(0.62, 0.85, r) * 0.5);
      c = lerpRGB(c, [0.12, 0.07, 0.045], ss(0.6, 0.8, dark[i]) * 0.55);
      c = scaleRGB(c, 1 + fine[i] * 0.08);
      let hh = r * 0.3 + mid[i] * 0.12 + fine[i] * 0.012;
      let rgh = 0.9;
      let mt = 0.05;
      const fe = 1 - ss(0.02, 0.07, flakes.f2[i] - flakes.f1[i]);
      hh -= fe * 0.06 * ss(0.4, 0.7, r);
      c = scaleRGB(c, 1 - fe * 0.25);
      // remnants of old paint with fractal, rust-bled edges
      const pv = paintF[i] + (paintD[i] - 0.5) * 0.3 - r * 0.25;
      const pm = ss(0.6, 0.615, pv);
      if (pm > 0) {
        const bleed = 1 - ss(0.615, 0.68, pv);
        let pc: RGB = scaleRGB([0.25, 0.29, 0.26], 0.85 + mid[i] * 0.3);
        pc = lerpRGB(pc, [0.38, 0.24, 0.14], bleed * 0.6);
        c = lerpRGB(c, pc, pm);
        hh += pm * 0.05;
        rgh = lerp(rgh, 0.6, pm);
      }
      const bare = ss(0.28, 0.24, r) * (1 - pm);
      if (bare > 0) { c = lerpRGB(c, [0.36, 0.35, 0.34], bare); mt = lerp(mt, 1, bare); rgh = lerp(rgh, 0.55, bare); }
      if (pits.id[i] < 0.3 && pits.f1[i] < 0.3) { const d = 1 - pits.f1[i] / 0.3; hh -= d * 0.05; c = scaleRGB(c, 1 - d * 0.4); }
      c = lerpRGB(c, [0.32, 0.14, 0.05], ss(0.55, 0.85, streak[i]) * 0.35);
      put(im, i, c);
      h[i] = hh; rough[i] = rgh; metal[i] = mt;
    }
    return { im, h, rough, metal, depth: 0.015, ao: 2.5, aoR: 1 / 120 };
  }

  function genFabric(): Out {
    const seed = 23000;
    const TH = 64;
    const fuzz = vnoise(S / 2, S / 2, rng(seed + 1));
    const mott = fbm(4, 4, 5, seed + 2);
    const stainF = fbm(6, 6, 5, seed + 3);
    const dirt = fbm(8, 8, 4, seed + 4);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld();
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = (y << SH) | x;
      const cx = (x / S) * TH, cy = (y / S) * TH;
      const ix = Math.floor(cx), iy = Math.floor(cy);
      const fx = cx - ix, fy = cy - iy;
      const warpTop = ((ix + iy) & 1) === 0;
      const pw = Math.sin(Math.PI * fx), pf = Math.sin(Math.PI * fy);
      let th: number, yarn: number;
      if (warpTop) { th = 0.5 + 0.5 * pw * (0.75 + 0.25 * pf); yarn = hash(ix, 0, seed); }
      else { th = 0.5 + 0.5 * pf * (0.75 + 0.25 * pw); yarn = hash(iy, 1, seed); }
      const gap = (1 - pw) * (1 - pf);
      th -= gap * 0.4;
      const g = 0.47 + (yarn - 0.5) * 0.05 + (mott[i] - 0.5) * 0.06 + fuzz[i] * 0.02;
      let c: RGB = [g, g * 0.97, g * 0.92];
      c = scaleRGB(c, 0.8 + 0.25 * th);
      const sv = stainF[i];
      c = lerpRGB(c, [0.34, 0.30, 0.24], ss(0.7, 0.72, sv) * 0.2 + ss(0.7, 0.72, sv) * (1 - ss(0.72, 0.76, sv)) * 0.12);
      c = lerpRGB(c, [0.25, 0.23, 0.20], ss(0.58, 0.88, dirt[i]) * 0.22);
      put(im, i, c);
      h[i] = th + fuzz[i] * 0.03;
      rough[i] = 0.93 + fuzz[i] * 0.02;
    }
    return { im, h, rough, depth: 0.0035, ao: 1.5, aoR: 1 / 256, aoAlb: 0.4 };
  }

  function genFactoryPanel(): Out {
    const seed = 24000;
    const PX = 1, PY = 2;
    const fine = vnoise(S / 2, S / 2, rng(seed + 1));
    const chipF = fbm(20, 20, 4, seed + 2, 0.6);
    const chipD = fbm(64, 64, 2, seed + 7);
    const grime = fbm(5, 5, 5, seed + 3);
    const streak = fbm(40, 2, 3, seed + 4, 0.6);
    const mott = fbm(3, 3, 4, seed + 5);
    const scr = scratches(Math.floor(120 * (S / 512)), seed + 6, 0.02, 0.15, 0, 0.5);
    const ur = fld(), ug = fld(), ub = fld(), mask = fld(), shade = fld(), grimeA = fld();
    const h = fld(), rough = fld(), metal = fld();
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = (y << SH) | x, u = x / S, v = y / S;
      const lx = frac(u * PX), ly = frac(v * PY);
      const ex = Math.min(lx, 1 - lx) / PX, ey = Math.min(ly, 1 - ly) / PY;
      const e = Math.min(ex, ey);
      const seam = 1 - ss(0.003, 0.006, e);
      const bevel = ss(0.004, 0.014, e);
      const inset = 1 - ss(0.002, 0.005, Math.abs(e - 0.06));
      let hh = 0.3 + bevel * 0.6 - inset * 0.08 + fine[i] * 0.002 + (mott[i] - 0.5) * 0.02;
      let bolt = 0;
      {
        const bd = 0.03;
        const candX = ex < ey;
        const along = candX ? v : u;
        const per = 0.1;
        const ad = Math.abs(frac(along / per + 0.5) - 0.5) * per;
        const nd = Math.abs((candX ? ex : ey) - bd);
        const d = hyp(ad, nd);
        if (d < 0.008 && Math.max(ex, ey) > bd - 0.001) bolt = Math.sqrt(1 - (d / 0.008) * (d / 0.008));
      }
      hh += bolt * 0.12 - (bolt > 0 && bolt < 0.25 ? 0.02 : 0);
      hh -= seam * 0.25;
      const edgeWear = 1 - ss(0.004, 0.035, e);
      const chip = chipMask(chipF, chipD, i, 0.86, edgeWear * 0.3 + bolt * 0.22);
      const m = clamp01(1 - chip - scr[i] * 0.8);
      mask[i] = m;
      hh -= (1 - m) * 0.01;
      const rustU = ss(0.84, 0.95, chipF[i] + edgeWear * 0.2) * (1 - m);
      const uc = lerpRGB([0.40, 0.40, 0.41], [0.36, 0.20, 0.10], rustU);
      ur[i] = uc[0]; ug[i] = uc[1]; ub[i] = uc[2];
      shade[i] = (0.95 + (mott[i] - 0.5) * 0.1 + fine[i] * 0.01) * (1 - inset * 0.08) * (1 - bolt * 0.05);
      const bottom = 1 - ss(0, 0.2, ly);
      const g = ss(0.55, 0.9, grime[i]) * 0.25 + ss(0.6, 0.9, streak[i]) * 0.22 * (0.4 + bottom) + seam * 0.55 + inset * 0.1 + bottom * 0.08;
      grimeA[i] = clamp01(g);
      h[i] = hh;
      rough[i] = lerp(lerp(0.4, 0.75, rustU), 0.55 + (mott[i] - 0.5) * 0.1, m) + clamp01(g) * 0.25;
      metal[i] = (1 - m) * (1 - rustU);
    }
    const paint: PaintLayer = { ur, ug, ub, mask, shade, grime: grimeA, grimeCol: [0.17, 0.16, 0.14], paint: [0.57, 0.58, 0.57] };
    return { im: img([0, 0, 0]), h, rough, metal, depth: 0.012, ao: 2, aoR: 1 / 150, aoAlb: 0.5, paint };
  }

  function genHazard(): Out {
    const seed = 25000;
    const fine = vnoise(S / 2, S / 2, rng(seed + 1));
    const chipF = fbm(14, 14, 5, seed + 2, 0.6);
    const chipD = fbm(64, 64, 2, seed + 5);
    const wearF = fbm(3, 3, 4, seed + 6);
    const grime = fbm(5, 5, 5, seed + 3);
    const scr = scratches(Math.floor(220 * (S / 512)), seed + 4, 0.02, 0.12, null, 0);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld(), metal = fld();
    const aa = (1.5 / S) * 5;
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = (y << SH) | x;
      const s = frac(((x + y) / S) * 5);
      const black = ss(0.5 - aa, 0.5 + aa, s) * (1 - ss(1 - aa, 1, s));
      let c = lerpRGB([0.78, 0.58, 0.07], [0.06, 0.06, 0.06], black);
      c = scaleRGB(c, 1 + fine[i] * 0.03);
      const chip = chipMask(chipF, chipD, i, 0.86, ss(0.5, 0.8, wearF[i]) * 0.12);
      const m = clamp01(1 - chip - scr[i] * 0.75);
      c = lerpRGB([0.36, 0.35, 0.34], c, m);
      const g = ss(0.5, 0.88, grime[i]) * 0.45;
      c = lerpRGB(c, [0.14, 0.12, 0.09], g);
      put(im, i, c);
      h[i] = m * 0.05 + fine[i] * 0.003;
      rough[i] = lerp(0.45, 0.6, m) + g * 0.3;
      metal[i] = 1 - m;
    }
    return { im, h, rough, metal, depth: 0.012, ao: 2, aoR: 1 / 200 };
  }

  function genGrating(): Out {
    const seed = 26000;
    const BARS = 20, CROSS = 10;
    const BT = 0.009, CT = 0.007;
    const fine = vnoise(S / 2, S / 2, rng(seed + 1));
    const rust = fbm(6, 6, 5, seed + 2);
    const grime = fbm(4, 4, 4, seed + 3);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld(), metal = fld(), alpha = fld();
    const px = 1 / S;
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = (y << SH) | x, u = (x + 0.5) / S, v = (y + 0.5) / S;
      const db = Math.abs(frac(u * BARS + 0.5) - 0.5) / BARS;
      const dc = Math.abs(frac(v * CROSS + 0.5) - 0.5) / CROSS;
      const bA = ss(BT / 2 + px * 0.7, BT / 2 - px * 0.7, db);
      const cA = ss(CT / 2 + px * 0.7, CT / 2 - px * 0.7, dc);
      alpha[i] = Math.max(bA, cA);
      const bh = Math.sqrt(Math.max(0, 1 - (db / (BT / 2 + px)) ** 2));
      const chh = Math.sqrt(Math.max(0, 1 - (dc / (CT / 2 + px)) ** 2)) * 0.9;
      h[i] = Math.max(bh, chh) + fine[i] * 0.01;
      const g = 0.36 + fine[i] * 0.02;
      let c: RGB = [g, g * 1.01, g * 1.02];
      const rs = ss(0.6, 0.85, rust[i] + (cA > 0.5 && bA > 0.5 ? 0.15 : 0));
      c = lerpRGB(c, [0.36, 0.18, 0.08], rs);
      c = lerpRGB(c, [0.15, 0.13, 0.11], ss(0.5, 0.8, grime[i]) * 0.45);
      put(im, i, c);
      rough[i] = lerp(0.5, 0.9, rs);
      metal[i] = 1 - rs;
    }
    return { im, h, rough, metal, alpha, depth: 0.005, ao: 0.5, aoR: 1 / 200, aoAlb: 0.2, alphaMips: true };
  }

  function genBelt(): Out {
    const seed = 27000;
    const RIDGES = 12;
    const fine = vnoise(S / 2, S / 2, rng(seed + 1));
    const wear = fbm(2, 4, 4, seed + 2);
    const dust = fbm(10, 10, 4, seed + 3);
    const lines = fbm(Math.max(8, S / 4), 2, 2, seed + 5, 0.5);
    const scr = scratches(Math.floor(80 * (S / 512)), seed + 4, 0.03, 0.15, Math.PI / 2, 0.15);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld();
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = (y << SH) | x;
      const ph = frac((y / S) * RIDGES);
      const ridge = ss(0.0, 0.08, ph) * (1 - ss(0.3, 0.38, ph));
      const groove = 1 - ridge;
      const hh = ridge * 0.8 + fine[i] * 0.01 - scr[i] * 0.03 + (lines[i] - 0.5) * 0.02;
      const g = 0.09 + fine[i] * 0.008 + (wear[i] - 0.5) * 0.025 + (lines[i] - 0.5) * 0.03 + ridge * 0.015;
      let c: RGB = [g, g, g * 1.03];
      c = lerpRGB(c, [0.26, 0.24, 0.21], groove * ss(0.45, 0.8, dust[i]) * 0.4);
      c = lerpRGB(c, [0.2, 0.2, 0.2], scr[i] * 0.35);
      put(im, i, c);
      h[i] = hh;
      rough[i] = lerp(0.85, 0.6, ridge * ss(0.4, 0.7, wear[i])) + groove * 0.05;
    }
    return { im, h, rough, depth: 0.01, ao: 2, aoR: 1 / 100 };
  }

  function genSoil(): Out {
    const seed = 28000;
    const k = S / 512, rnd = rng(seed);
    const clods = worley(22, 22, seed + 1);
    const clods2 = worley(60, 60, seed + 2);
    const base = fbm(4, 4, 5, seed + 3);
    const fine = vnoise(S / 2, S / 2, rng(seed + 4));
    const crumb = vnoise(S / 4, S / 4, rng(seed + 5));
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld();
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = (y << SH) | x;
      const furrow = 0.5 + 0.5 * Math.cos(TAU * (x / S) * 4 + (base[i] - 0.5) * 1.5);
      const c1 = 1 - clods.f1[i] * clods.f1[i] * 1.6;
      const c2 = 1 - clods2.f1[i] * 1.3;
      const hh = furrow * 0.3 + c1 * 0.3 + c2 * 0.12 + crumb[i] * 0.03 + fine[i] * 0.015;
      const dry = ss(0.45, 0.85, hh);
      let c = lerpRGB([0.13, 0.09, 0.06], [0.30, 0.22, 0.15], dry);
      c = scaleRGB(c, (1 + fine[i] * 0.06 + crumb[i] * 0.04) * (0.88 + 0.24 * clods.id[i]));
      put(im, i, c);
      h[i] = hh;
      rough[i] = lerp(0.85, 0.95, dry);
    }
    const nS = Math.floor(260 * k * k);
    for (let n = 0; n < nS; n++) {
      const x = rnd() * S, y = rnd() * S, len = (5 + rnd() * 14) * k, a = rnd() * TAU;
      const c: RGB = scaleRGB([0.55, 0.48, 0.32], 0.55 + rnd() * 0.45);
      line(x, y, a, len, Math.max(1, Math.round(k)), (p) => { put(im, p, c); h[p] += 0.06; rough[p] = 0.8; });
    }
    for (let n = 0; n < Math.floor(90 * k * k); n++) {
      const x = rnd() * S, y = rnd() * S, r = (1.5 + rnd() * 3) * k;
      const c: RGB = scaleRGB([0.40, 0.38, 0.35], 0.6 + rnd() * 0.6);
      disk(x, y, r, (p, d) => { mixc(im, p, scaleRGB(c, 0.8 + 0.3 * (1 - d)), ss(1, 0.7, d)); h[p] += 0.08 * Math.sqrt(1 - d * d); rough[p] = 0.75; });
    }
    return { im, h, rough, depth: 0.02, ao: 2.5, aoR: 1 / 90 };
  }

  // --- extra internal sets -----------------------------------------------------------------------

  function genSkin(): Out {
    const seed = 29000;
    const mott = fbm(5, 5, 6, seed + 1, 0.55);
    const blot = fbm(10, 10, 4, seed + 2);
    const veins = ridged(4, 4, 4, seed + 3, 0.55);
    const bruise = fbm(3, 3, 5, seed + 4);
    const fine = vnoise(S / 2, S / 2, rng(seed + 5));
    const pores = worley(120, 120, seed + 6);
    const specks = fbm(24, 24, 4, seed + 7, 0.6);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld();
    for (let i = 0; i < h.length; i++) {
      let c: RGB = lerpRGB([0.56, 0.56, 0.48], [0.46, 0.47, 0.41], ss(0.3, 0.72, mott[i]));
      c = lerpRGB(c, [0.55, 0.50, 0.45], ss(0.55, 0.8, blot[i]) * 0.3);
      const vn = ss(0.9, 0.98, veins[i]);
      c = lerpRGB(c, [0.36, 0.38, 0.43], vn * 0.45);
      const br = ss(0.7, 0.85, bruise[i]);
      c = lerpRGB(c, [0.38, 0.32, 0.33], br * 0.45);
      c = lerpRGB(c, [0.30, 0.15, 0.11], ss(0.85, 0.92, specks[i]) * 0.35);
      c = scaleRGB(c, 1 + fine[i] * 0.02);
      put(im, i, c);
      let hh = mott[i] * 0.05 + fine[i] * 0.004 + vn * 0.02;
      if (pores.f1[i] < 0.15 && pores.id[i] < 0.5) hh -= 0.02;
      h[i] = hh;
      rough[i] = 0.62 + (blot[i] - 0.5) * 0.15 + br * 0.1;
    }
    return { im, h, rough, depth: 0.006, ao: 2, aoR: 1 / 200, aoAlb: 0.4 };
  }

  function genCarPaint(): Out {
    const seed = 30000;
    const fine = vnoise(S / 2, S / 2, rng(seed + 1));
    const dust = fbm(3, 3, 5, seed + 2);
    const streak = fbm(30, 2, 3, seed + 3, 0.6);
    const rustF = fbm(8, 8, 6, seed + 4, 0.6);
    const rustC = fbm(2, 2, 4, seed + 7);
    const fade = fbm(2, 2, 4, seed + 5);
    const scr = scratches(Math.floor(70 * (S / 512)), seed + 6, 0.02, 0.2, null, 0);
    const ur = fld(), ug = fld(), ub = fld(), mask = fld(), shade = fld(), grimeA = fld();
    const h = fld(), rough = fld(), metal = fld();
    for (let i = 0; i < h.length; i++) {
      const rv = rustF[i] + (rustC[i] - 0.5) * 0.45;
      const rs = ss(0.8, 0.82, rv + fine[i] * 0.006);
      const bub = ss(0.74, 0.8, rv) * (1 - rs);
      const m = clamp01(1 - rs - scr[i] * 0.7);
      mask[i] = m;
      const pr = scr[i] > 0.3 && rs < 0.1;
      const uc: RGB = pr ? [0.45, 0.45, 0.43] : lerpRGB([0.40, 0.20, 0.08], [0.22, 0.11, 0.05], clamp01(0.5 + fine[i] * 0.4));
      ur[i] = uc[0]; ug[i] = uc[1]; ub[i] = uc[2];
      shade[i] = (0.9 + (fade[i] - 0.5) * 0.3) * (1 - bub * 0.25);
      const g = ss(0.45, 0.85, dust[i]) * 0.35 + ss(0.55, 0.85, streak[i]) * 0.22 + bub * 0.25;
      grimeA[i] = clamp01(g);
      h[i] = bub * 0.1 + rs * fine[i] * 0.02 + fine[i] * 0.001 - scr[i] * 0.02;
      rough[i] = lerp(0.9, 0.3 + (fade[i] - 0.5) * 0.15, m) + clamp01(g) * 0.45;
      metal[i] = pr ? 0.8 : 0;
    }
    const paint: PaintLayer = { ur, ug, ub, mask, shade, grime: grimeA, grimeCol: [0.30, 0.26, 0.20], paint: [0.6, 0.6, 0.6] };
    return { im: img([0, 0, 0]), h, rough, metal, depth: 0.01, ao: 1.5, aoR: 1 / 150, aoAlb: 0.3, paint };
  }

  function genGlassGrime(): Out {
    const seed = 31000;
    const dirt = fbm(4, 4, 6, seed + 1);
    const streak = fbm(40, 2, 3, seed + 2, 0.6);
    const spots = worley(30, 30, seed + 3);
    const fine = vnoise(S / 2, S / 2, rng(seed + 4));
    const cr = crackWalks(2, seed + 5, 0.5, 0.9, 0.5, 0.35);
    const im = img([0, 0, 0]);
    const h = fld(), rough = fld(), alpha = fld();
    for (let i = 0; i < h.length; i++) {
      const d = ss(0.4, 0.85, dirt[i]) * 0.6 + ss(0.5, 0.85, streak[i]) * 0.35;
      const sp = spots.id[i] < 0.3 && spots.f1[i] < 0.25 ? (1 - spots.f1[i] / 0.25) * 0.3 : 0;
      alpha[i] = clamp01(0.32 + d * 0.55 + sp + cr[i] * 0.4);
      put(im, i, lerpRGB([0.62, 0.68, 0.66], [0.36, 0.33, 0.27], clamp01(d * 1.2 + sp)));
      mixc(im, i, [0.85, 0.87, 0.86], cr[i] * 0.7);
      h[i] = fine[i] * 0.002 + d * 0.01 - cr[i] * 0.05;
      rough[i] = 0.05 + d * 0.6 + sp * 0.4;
    }
    return { im, h, rough, alpha, depth: 0.01, ao: 0, aoR: 1 / 200, aoAlb: 0 };
  }

  // ============================================================================================
  // Alpha / colour-only textures
  // ============================================================================================

  function canvas2d(size: number): { c: HTMLCanvasElement; g: CanvasRenderingContext2D } {
    const c = document.createElement('canvas');
    c.width = c.height = size;
    const g = c.getContext('2d', { willReadFrequently: true });
    if (!g) throw new Error('2D canvas unavailable');
    return { c, g };
  }

  /** Fill RGB of transparent pixels with the dilated colour of nearby opaque pixels (avoids dark mip halos). */
  function bleed(data: Uint8ClampedArray, size: number): Uint8Array {
    const out = new Uint8Array(data.length);
    out.set(data);
    let sr = 0, sg = 0, sb = 0, n = 0;
    for (let i = 0; i < size * size; i++) if (data[i * 4 + 3] > 128) { sr += data[i * 4]; sg += data[i * 4 + 1]; sb += data[i * 4 + 2]; n++; }
    const ar = n ? sr / n : 0, ag = n ? sg / n : 0, ab = n ? sb / n : 0;
    for (let i = 0; i < size * size; i++) if (out[i * 4 + 3] < 8) { out[i * 4] = ar; out[i * 4 + 1] = ag; out[i * 4 + 2] = ab; }
    // a few dilation passes so edge texels pick up real neighbouring colour
    const cur = out;
    for (let pass = 0; pass < 3; pass++) {
      const src = cur.slice();
      for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) {
        const i = y * size + x;
        if (src[i * 4 + 3] >= 8) continue;
        let r = 0, g = 0, b = 0, c = 0;
        for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
          const xx = x + dx, yy = y + dy;
          if (xx < 0 || yy < 0 || xx >= size || yy >= size) continue;
          const j = yy * size + xx;
          if (src[j * 4 + 3] >= 8 || pass > 0) { r += src[j * 4]; g += src[j * 4 + 1]; b += src[j * 4 + 2]; c++; }
        }
        if (c) { cur[i * 4] = r / c; cur[i * 4 + 1] = g / c; cur[i * 4 + 2] = b / c; }
      }
    }
    return cur;
  }

  function cssRGB(c: RGB, k = 1): string {
    return `rgb(${Math.round(clamp01(c[0] * k) * 255)},${Math.round(clamp01(c[1] * k) * 255)},${Math.round(clamp01(c[2] * k) * 255)})`;
  }

  /** Pine branch card: stem base at v=0 (centre of u), tip toward v=1. */
  function genPineNeedleTex(size: number): RawTex {
    const { g } = canvas2d(size);
    const rnd = rng(40001);
    g.clearRect(0, 0, size, size);
    g.lineCap = 'round';
    const cols: RGB[] = [[0.10, 0.16, 0.08], [0.13, 0.21, 0.10], [0.16, 0.25, 0.11], [0.19, 0.28, 0.13], [0.12, 0.19, 0.11]];
    const tipCol: RGB = [0.30, 0.38, 0.16];
    const stemCol: RGB = [0.24, 0.17, 0.11];
    const nw = Math.max(1, size / 340);
    interface Tw { x: number; y: number; a: number; len: number; depth: number }
    const twigs: Tw[] = [];
    const queue: Tw[] = [{ x: size * 0.5, y: 0, a: Math.PI / 2, len: size * 0.97, depth: 0 }];
    // build skeleton
    while (queue.length) {
      const t = queue.shift()!;
      twigs.push(t);
      if (t.depth >= 2) continue;
      const nb = t.depth === 0 ? 9 : 4;
      for (let b = 0; b < nb; b++) {
        const f = 0.1 + (b / nb) * 0.8 + (rnd() - 0.5) * 0.05;
        const side = b % 2 === 0 ? 1 : -1;
        const a = t.a + side * (0.55 + rnd() * 0.35);
        const len = t.len * (t.depth === 0 ? 0.42 : 0.45) * (1 - f * 0.55);
        queue.push({ x: t.x + Math.cos(t.a) * t.len * f, y: t.y + Math.sin(t.a) * t.len * f, a, len, depth: t.depth + 1 });
      }
    }
    // needles: two layers (back darker, front lighter)
    for (let layer = 0; layer < 2; layer++) {
      for (const t of twigs) {
        const steps = Math.floor(t.len / (size * 0.009));
        for (let s = 0; s < steps; s++) {
          const f = s / steps;
          const x = t.x + Math.cos(t.a) * t.len * f, y = t.y + Math.sin(t.a) * t.len * f;
          const nl = size * (0.05 + rnd() * 0.03) * (1 - f * 0.35) * (t.depth === 0 ? 0.8 : 1);
          for (let side = -1; side <= 1; side += 2) {
            if (rnd() < 0.15) continue;
            const a = t.a + side * (0.5 + rnd() * 0.6) + (rnd() - 0.5) * 0.2;
            let c = cols[Math.floor(rnd() * cols.length)];
            c = scaleRGB(c, layer === 0 ? 0.7 : 1 + rnd() * 0.15);
            if (f > 0.85 && layer === 1) c = lerpRGB(c, tipCol, (f - 0.85) / 0.15 * 0.8);
            g.strokeStyle = cssRGB(c);
            g.lineWidth = nw * (0.9 + rnd() * 0.5);
            g.beginPath();
            g.moveTo(x, y);
            const ex = x + Math.cos(a) * nl, ey = y + Math.sin(a) * nl;
            g.quadraticCurveTo(x + Math.cos(a + 0.1) * nl * 0.5, y + Math.sin(a + 0.1) * nl * 0.5, ex, ey);
            g.stroke();
          }
        }
        if (layer === 0) {
          g.strokeStyle = cssRGB(stemCol);
          g.lineWidth = nw * (t.depth === 0 ? 3.2 : t.depth === 1 ? 2 : 1.3);
          g.beginPath(); g.moveTo(t.x, t.y); g.lineTo(t.x + Math.cos(t.a) * t.len, t.y + Math.sin(t.a) * t.len); g.stroke();
        }
      }
    }
    const id = g.getImageData(0, 0, size, size);
    { const d = bleed(id.data, size); const r = raw(d, size, true, false); r.mips = alphaMips(d, size); return r; }
  }

  /** Deciduous leaf cluster card: twigs enter from v=0. */
  function genLeafTex(size: number): RawTex {
    const { g } = canvas2d(size);
    const rnd = rng(41001);
    g.clearRect(0, 0, size, size);
    g.lineCap = 'round';
    const cols: RGB[] = [[0.22, 0.32, 0.12], [0.27, 0.38, 0.14], [0.18, 0.28, 0.10], [0.33, 0.42, 0.17], [0.25, 0.34, 0.15], [0.42, 0.44, 0.18], [0.36, 0.30, 0.13]];
    const lw = size / 512;
    const leaf = (x: number, y: number, a: number, l: number, c: RGB): void => {
      g.save();
      g.translate(x, y); g.rotate(a - Math.PI / 2);
      const w = l * (0.32 + rnd() * 0.12);
      g.fillStyle = cssRGB(c);
      g.beginPath(); g.moveTo(0, 0);
      g.bezierCurveTo(w, l * 0.15, w * 1.05, l * 0.6, 0, l);
      g.bezierCurveTo(-w * 1.05, l * 0.6, -w, l * 0.15, 0, 0);
      g.fill();
      // darker half
      g.fillStyle = cssRGB(c, 0.78);
      g.beginPath(); g.moveTo(0, 0);
      g.bezierCurveTo(-w, l * 0.15, -w * 1.05, l * 0.6, 0, l); g.lineTo(0, 0); g.fill();
      // midrib + veins
      g.strokeStyle = cssRGB(lerpRGB(c, [0.6, 0.6, 0.35], 0.35));
      g.lineWidth = Math.max(0.7, lw * 1.2);
      g.beginPath(); g.moveTo(0, 0); g.lineTo(0, l * 0.95); g.stroke();
      g.lineWidth = Math.max(0.5, lw * 0.6);
      for (let v = 1; v < 5; v++) {
        const yy = l * v * 0.18;
        g.beginPath(); g.moveTo(0, yy); g.lineTo(w * 0.7, yy + l * 0.12); g.moveTo(0, yy); g.lineTo(-w * 0.7, yy + l * 0.12); g.stroke();
      }
      // blemishes
      if (rnd() < 0.4) { g.fillStyle = cssRGB([0.35, 0.25, 0.1], 0.9); g.beginPath(); g.arc((rnd() - 0.5) * w, l * (0.3 + rnd() * 0.5), l * 0.05, 0, TAU); g.fill(); }
      g.restore();
    };
    const twigs = 5;
    for (let t = 0; t < twigs; t++) {
      let x = size * (0.2 + 0.6 * (t / (twigs - 1))) + (rnd() - 0.5) * size * 0.1, y = 0;
      let a = Math.PI / 2 + (t / (twigs - 1) - 0.5) * 1.1;
      const segs = 6;
      const seg = size * (0.12 + rnd() * 0.03);
      g.strokeStyle = cssRGB([0.25, 0.19, 0.13]);
      for (let s = 0; s < segs; s++) {
        const nx = x + Math.cos(a) * seg, ny = y + Math.sin(a) * seg;
        g.lineWidth = lw * (3.2 - s * 0.4);
        g.beginPath(); g.moveTo(x, y); g.lineTo(nx, ny); g.stroke();
        for (let side = -1; side <= 1; side += 2) {
          if (s === 0 && side < 0) continue;
          const la = a + side * (0.7 + rnd() * 0.5);
          const pl = size * 0.025;
          const px = nx + Math.cos(la) * pl, py = ny + Math.sin(la) * pl;
          g.lineWidth = lw * 1.2;
          g.beginPath(); g.moveTo(nx, ny); g.lineTo(px, py); g.stroke();
          const c = scaleRGB(cols[Math.floor(rnd() * cols.length)], 0.85 + rnd() * 0.3);
          leaf(px, py, la + (rnd() - 0.5) * 0.4, size * (0.10 + rnd() * 0.05), c);
          g.strokeStyle = cssRGB([0.25, 0.19, 0.13]);
        }
        x = nx; y = ny; a += (rnd() - 0.5) * 0.4;
      }
      const c = scaleRGB(cols[Math.floor(rnd() * cols.length)], 0.95);
      leaf(x, y, a, size * 0.11, c);
    }
    const id = g.getImageData(0, 0, size, size);
    { const d = bleed(id.data, size); const r = raw(d, size, true, false); r.mips = alphaMips(d, size); return r; }
  }

  function genBloodTex(size: number): RawTex {
    setSize(size);
    const rnd = rng(42001);
    const n1 = fbm(4, 4, 6, 42002, 0.55);
    const n2 = fbm(12, 12, 4, 42003);
    const ang = fbm(1, 8, 3, 42004);
    const a = fld(), th = fld();
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      const i = (y << SH) | x;
      const dx = (x + 0.5) / S - 0.5, dy = (y + 0.5) / S - 0.5;
      const r = hyp(dx, dy);
      const val = (0.22 - r) / 0.22 + (n1[i] - 0.5) * 1.3 + (n2[i] - 0.5) * 0.3;
      a[i] = ss(0, 0.06, val);
      th[i] = ss(0, 0.8, val);
    }
    // droplets & streaks radiating outward
    const drops = Math.floor(90);
    for (let n = 0; n < drops; n++) {
      const an = rnd() * TAU;
      const d = 0.16 + Math.pow(rnd(), 0.8) * 0.3;
      const cx = (0.5 + Math.cos(an) * d) * S, cy = (0.5 + Math.sin(an) * d) * S;
      const rr = S * (0.003 + rnd() * 0.012) * (1.2 - d * 1.5);
      if (rr < 0.8) continue;
      const elong = 1 + rnd() * 2.2;
      const steps = Math.ceil(elong * 2);
      for (let s = 0; s < steps; s++) {
        const f = s / Math.max(1, steps - 1);
        const px = cx - Math.cos(an) * rr * (elong - 1) * f * 1.5, py = cy - Math.sin(an) * rr * (elong - 1) * f * 1.5;
        const r2 = rr * (1 - f * 0.5);
        for (let yy = Math.floor(py - r2 - 2); yy <= py + r2 + 2; yy++) for (let xx = Math.floor(px - r2 - 2); xx <= px + r2 + 2; xx++) {
          if (xx < 0 || yy < 0 || xx >= S || yy >= S) continue;
          const dd = hyp(xx + 0.5 - px, yy + 0.5 - py);
          const v = ss(r2 + 1, r2 - 1, dd);
          const i = yy * S + xx;
          if (v > a[i]) a[i] = v;
          th[i] = Math.max(th[i], v * 0.6);
        }
      }
    }
    void ang;
    const out = new Uint8Array(S * S * 4);
    for (let i = 0; i < S * S; i++) {
      const t = th[i];
      const c = lerpRGB([0.36, 0.035, 0.03], [0.14, 0.008, 0.008], t);
      const nn = 1 + (n2[i] - 0.5) * 0.25;
      // fade at the image border to guarantee clean edges
      const x = i & SM, y = i >> SH;
      const edge = ss(0, 6, Math.min(x, y, S - 1 - x, S - 1 - y));
      out[i * 4] = clamp01(c[0] * nn) * 255;
      out[i * 4 + 1] = clamp01(c[1] * nn) * 255;
      out[i * 4 + 2] = clamp01(c[2] * nn) * 255;
      out[i * 4 + 3] = clamp01(a[i] * edge * (0.75 + 0.25 * t)) * 255;
    }
    return raw(out, S, true, false);
  }

  function genWaterNormalTex(size: number): RawTex {
    setSize(size);
    const a = fbm(6, 6, 5, 43001, 0.5);
    const b = fbm(12, 4, 4, 43002, 0.5);
    const c = fbm(3, 3, 3, 43003);
    const h = fld();
    for (let i = 0; i < h.length; i++) h[i] = a[i] * 0.6 + b[i] * 0.3 + c[i] * 0.3;
    return raw(normalMapData(h, 0.025), S, false);
  }

  function genCloudTex(size: number): RawTex {
    setSize(size);
    const a = fbm(4, 4, 8, 44001, 0.52);
    const b = ridged(8, 8, 5, 44002);
    const h = fld();
    for (let i = 0; i < h.length; i++) h[i] = a[i] * 0.8 + b[i] * 0.2;
    normalize(h);
    const out = new Uint8Array(S * S * 4);
    for (let i = 0; i < S * S; i++) {
      const v = h[i] * 255;
      out[i * 4] = v; out[i * 4 + 1] = v; out[i * 4 + 2] = v; out[i * 4 + 3] = 255;
    }
    return raw(out, S, false);
  }

  // ------------------------------------------------------------------------------------------
  const GENERATORS: Record<string, () => Out> = {
    grass: genGrass,
    dirt: () => genDirt(),
    rock: genRock,
    forestFloor: genForestFloor,
    mud: genMud,
    sand: genSand,
    asphalt: genAsphalt,
    sidewalk: genSidewalk,
    concrete: genConcrete,
    brick: () => genBrick(false),
    brickRed: () => genBrick(true),
    siding: genSiding,
    plaster: genPlaster,
    woodFloor: genWoodFloor,
    tile: genTile,
    roofShingle: genRoofShingle,
    roofMetal: genRoofMetal,
    wood: () => genWood(false),
    woodDark: () => genWood(true),
    bark: genBark,
    birchBark: genBirchBark,
    metal: () => genMetal('metal'),
    metalRust: genMetalRust,
    metalDark: () => genMetal('dark'),
    steelBrushed: () => genMetal('brushed'),
    fabric: genFabric,
    factoryPanel: genFactoryPanel,
    hazard: genHazard,
    grating: genGrating,
    belt: genBelt,
    soil: genSoil,
    skin: genSkin,
    carPaint: genCarPaint,
    glassGrime: genGlassGrime,
  };

  /** Generate one PBR set (or a single-texture job: 'blood' | 'water' | 'cloud' | 'pine' | 'leaf'). */
  function generate(name: string, size: number, half: boolean): KernelOut {
    ormHalf = half;
    tapCache.clear();
    crCache.clear();
    if (name === 'blood') return { map: genBloodTex(size) };
    if (name === 'water') return { map: genWaterNormalTex(size) };
    if (name === 'cloud') return { map: genCloudTex(size) };
    if (name === 'pine') return { map: genPineNeedleTex(size) };
    if (name === 'leaf') return { map: genLeafTex(size) };
    const g = GENERATORS[name];
    if (!g) throw new Error(`textures: no generator "${name}"`);
    setSize(size);
    return finalize(g());
  }

  /** Re-compose the albedo of a paintable set with a new paint colour (sRGB 0..1). */
  function recolor(L: PaintLayer, ao: F, aoAlb: number, size: number, paint: RGB): Uint8Array {
    setSize(size);
    const im = img([0, 0, 0]);
    composePaint(L, paint, im);
    return albedoData(im, ao, aoAlb);
  }

  return { generate, recolor };
}

// ============================================================================================
// Registry, worker pool & public API
// ============================================================================================

interface Stored { set: PBRSet; paint?: PaintLayer; ao?: F; aoAlb?: number; size: number }

const store = new Map<string, Stored>();
const paintedCache = new Map<string, PBRSet>();
const singles = new Map<string, THREE.Texture>();
let ready = false;
let initPromise: Promise<void> | null = null;
let baseSize = 512;
let localKernel: ReturnType<typeof textureKernel> | null = null;
const kernel = (): ReturnType<typeof textureKernel> => (localKernel ??= textureKernel());

/** Sets with metallic content packed into roughnessMap.b (bind it as metalnessMap). */
export const METALLIC_TEX: ReadonlySet<string> = new Set<string>(['roofMetal', 'metal', 'metalRust', 'metalDark', 'steelBrushed', 'factoryPanel', 'hazard', 'grating', 'carPaint']);

function makeTex(r: RawTex): THREE.DataTexture {
  const t = new THREE.DataTexture(r.data, r.size, r.size, THREE.RGBAFormat, THREE.UnsignedByteType);
  t.wrapS = t.wrapT = r.repeat ? THREE.RepeatWrapping : THREE.ClampToEdgeWrapping;
  t.magFilter = THREE.LinearFilter;
  t.minFilter = THREE.LinearMipmapLinearFilter;
  t.generateMipmaps = true;
  t.anisotropy = 8;
  t.colorSpace = r.srgb ? THREE.SRGBColorSpace : THREE.NoColorSpace;
  t.flipY = false;
  if (r.mips && r.mips.length > 1) {
    t.mipmaps = r.mips.map((data, i) => ({ data, width: Math.max(1, r.size >> i), height: Math.max(1, r.size >> i) }));
    t.generateMipmaps = false;
  }
  t.needsUpdate = true;
  return t;
}

function storeResult(name: string, size: number, r: KernelOut): void {
  if (!r.normal || !r.orm) { singles.set(name, makeTex(r.map)); return; }
  store.set(name, {
    set: { map: makeTex(r.map), normalMap: makeTex(r.normal), roughnessMap: makeTex(r.orm) },
    paint: r.paint, ao: r.ao, aoAlb: r.aoAlb, size,
  });
}

function transferList(r: KernelOut): ArrayBuffer[] {
  const l: ArrayBuffer[] = [r.map.data.buffer as ArrayBuffer];
  if (r.map.mips) for (let i = 1; i < r.map.mips.length; i++) l.push(r.map.mips[i].buffer as ArrayBuffer);
  if (r.normal) l.push(r.normal.data.buffer as ArrayBuffer);
  if (r.orm) l.push(r.orm.data.buffer as ArrayBuffer);
  if (r.ao) l.push(r.ao.buffer as ArrayBuffer);
  if (r.paint) for (const k of ['ur', 'ug', 'ub', 'mask', 'shade', 'grime'] as const) l.push(r.paint[k].buffer as ArrayBuffer);
  return l;
}

/** Spawn Blob workers running the stringified kernel. Returns [] if workers are unavailable. */
function spawnWorkers(n: number): Worker[] {
  if (typeof Worker === 'undefined' || typeof Blob === 'undefined' || typeof URL === 'undefined' || n <= 0) return [];
  try {
    const src = `const K = (${textureKernel.toString()})();
const tl = ${transferList.toString()};
self.onmessage = (e) => {
  const { id, name, size, half } = e.data;
  try { const r = K.generate(name, size, half); self.postMessage({ id, r }, tl(r)); }
  catch (err) { self.postMessage({ id, error: String(err && err.stack || err) }); }
};`;
    const url = URL.createObjectURL(new Blob([src], { type: 'text/javascript' }));
    const ws: Worker[] = [];
    for (let i = 0; i < n; i++) ws.push(new Worker(url));
    setTimeout(() => URL.revokeObjectURL(url), 10000);
    return ws;
  } catch {
    return [];
  }
}

const yieldFrame = (): Promise<void> => new Promise((r) => setTimeout(r, 0));

interface Job { name: string; size: number; label: string; main?: boolean }

export async function initTextures(quality: TexQuality, onProgress?: (p: number, label: string) => void): Promise<void> {
  if (initPromise) return initPromise;
  initPromise = (async () => {
    baseSize = quality === 'low' ? 256 : quality === 'high' ? 1024 : 512;
    const half = quality === 'high';
    const alphaSize = quality === 'low' ? 256 : 512;
    const jobs: Job[] = [];
    // heaviest first for better load balancing
    for (const name of TEX_NAMES) jobs.push({ name, size: baseSize, label: name });
    for (const name of ['skin', 'carPaint', 'glassGrime']) jobs.push({ name, size: baseSize, label: name });
    jobs.push({ name: 'blood', size: Math.min(512, alphaSize), label: 'blood' });
    jobs.push({ name: 'water', size: Math.min(512, baseSize), label: 'water' });
    jobs.push({ name: 'cloud', size: Math.min(512, baseSize), label: 'clouds' });
    jobs.push({ name: 'pine', size: alphaSize, label: 'pine needles', main: true });
    jobs.push({ name: 'leaf', size: alphaSize, label: 'leaves', main: true });
    const total = jobs.length;
    let done = 0;
    const tick = (label: string): void => { done++; onProgress?.(done / total, label); };
    onProgress?.(0, 'textures');

    const hc = typeof navigator !== 'undefined' && navigator.hardwareConcurrency ? navigator.hardwareConcurrency : 4;
    const workers = spawnWorkers(Math.max(1, Math.min(6, hc - 1)));
    const mainJobs = jobs.filter((j) => j.main || workers.length === 0);
    const workerJobs = workers.length ? jobs.filter((j) => !j.main) : [];

    const workerPhase = new Promise<void>((resolve, reject) => {
      if (!workerJobs.length) { resolve(); return; }
      let next = 0, finished = 0;
      const pending = new Map<number, Job>();
      const feed = (w: Worker): void => {
        if (next >= workerJobs.length) return;
        const id = next++;
        const j = workerJobs[id];
        pending.set(id, j);
        w.postMessage({ id, name: j.name, size: j.size, half });
      };
      for (const w of workers) {
        w.onmessage = (e: MessageEvent<{ id: number; r?: KernelOut; error?: string }>) => {
          const j = pending.get(e.data.id)!;
          pending.delete(e.data.id);
          if (e.data.error || !e.data.r) { reject(new Error(e.data.error ?? 'texture worker failed')); return; }
          storeResult(j.name, j.size, e.data.r);
          tick(j.label);
          if (++finished === workerJobs.length) resolve();
          else feed(w);
        };
        w.onerror = (ev) => { ev.preventDefault(); reject(new Error(ev.message || 'texture worker error')); };
        feed(w);
      }
    });

    try {
      // main-thread jobs (canvas-based, or everything if no workers), yielding between textures
      for (const j of mainJobs) {
        await yieldFrame();
        storeResult(j.name, j.size, kernel().generate(j.name, j.size, half));
        tick(j.label);
      }
      await workerPhase;
    } catch (err) {
      // worker failure (e.g. CSP blocks blob: workers): finish remaining jobs on the main thread
      console.warn('textures: worker generation failed, falling back to main thread', err);
      for (const j of jobs) {
        if (store.has(j.name) || singles.has(j.name)) continue;
        await yieldFrame();
        storeResult(j.name, j.size, kernel().generate(j.name, j.size, half));
        tick(j.label);
      }
    } finally {
      for (const w of workers) w.terminate();
    }
    ready = true;
    onProgress?.(1, 'done');
  })();
  return initPromise;
}

function need(): void {
  if (!ready) throw new Error('textures: initTextures() has not completed');
}

export function getTexSet(name: TexName): PBRSet {
  need();
  const s = store.get(name);
  if (!s) throw new Error(`textures: unknown texture set "${name}"`);
  return s.set;
}

export function getExtraTexSet(name: ExtraTexName): PBRSet {
  need();
  const s = store.get(name);
  if (!s) throw new Error(`textures: unknown extra set "${name}"`);
  return s.set;
}

/**
 * Same normal/roughness maps as the base set, but with the paint layer recoloured to `paintHex`
 * (sRGB). Chips, peeling and grime keep their original colours. Cached per (base, colour).
 */
export function getPaintedTexSet(base: PaintableTexName, paintHex: number): PBRSet {
  need();
  const key = `${base}:${paintHex}`;
  const c = paintedCache.get(key);
  if (c) return c;
  const st = store.get(base);
  if (!st || !st.paint || !st.ao) throw new Error(`textures: "${base}" is not paintable`);
  const rgb: RGB = [((paintHex >> 16) & 255) / 255, ((paintHex >> 8) & 255) / 255, (paintHex & 255) / 255];
  const data = kernel().recolor(st.paint, st.ao, st.aoAlb ?? 0.5, st.size, rgb);
  const map = makeTex({ data, size: st.size, srgb: true, repeat: true });
  const set: PBRSet = { map, normalMap: st.set.normalMap, roughnessMap: st.set.roughnessMap };
  paintedCache.set(key, set);
  return set;
}

function single(name: string): THREE.Texture {
  need();
  const t = singles.get(name);
  if (!t) throw new Error(`textures: missing "${name}"`);
  return t;
}

/** Pine branch card (RGBA, alpha-cut). Stem base at v=0 (centre of u), tips toward v=1. */
export function getPineNeedleTexture(): THREE.Texture { return single('pine'); }
/** Deciduous leaf cluster card (RGBA, alpha-cut). Twigs enter from v=0. */
export function getLeafTexture(): THREE.Texture { return single('leaf'); }
/** Blood splatter decal (RGBA, soft alpha edges, ClampToEdge). */
export function getBloodDecalTexture(): THREE.Texture { return single('blood'); }
/** Tileable water ripple normal map (RepeatWrapping, linear). */
export function getWaterNormalTexture(): THREE.Texture { return single('water'); }
/** Tileable greyscale fBm (value in R, also copied to G/B), RepeatWrapping, linear. */
export function getCloudNoiseTexture(): THREE.Texture { return single('cloud'); }
export function texturesReady(): boolean { return ready; }
export function getTextureBaseSize(): number { return baseSize; }

/** @internal Synchronous per-generator timing (dev tooling only). */
export function __bench(size: number): [string, number][] {
  const k = textureKernel();
  const out: [string, number][] = [];
  for (const n of [...TEX_NAMES, 'skin', 'carPaint', 'glassGrime', 'blood', 'water', 'cloud']) {
    const t = performance.now();
    k.generate(n, size, size >= 1024);
    out.push([n, performance.now() - t]);
  }
  return out;
}
