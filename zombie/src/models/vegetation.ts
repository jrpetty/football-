/**
 * Instancing-ready vegetation: conifers (Douglas-fir / cedar), birches, dead trees, bushes,
 * rocks and fallen logs. Each model = merged geometry per material at the origin, base on y=0.
 *
 * Every geometry carries a float attribute `windWeight` (0 at trunk base → 1 at branch tips / top;
 * 0 everywhere on rocks and logs) for the caller's wind-sway shader.
 * Foliage uses alpha-tested cards (pineNeedles / leaves textures: the twig enters at v=0).
 */
import * as THREE from 'three';
import { getMaterial } from '../render/materials';
import { addFloatAttr, boxProjectUV, fbm3, merge, noise3, rng, tinted, tube, type Vec3 } from './geom';
import { mergeVertices } from 'three/examples/jsm/utils/BufferGeometryUtils.js';

export interface TreeModel {
  parts: { geometry: THREE.BufferGeometry; material: THREE.MeshStandardMaterial }[];
  height: number;
  trunkRadius: number;
}

type V = THREE.Vector3;
const v3 = (x = 0, y = 0, z = 0) => new THREE.Vector3(x, y, z);
const UP = v3(0, 1, 0);

// ---------------------------------------------------------------------------
// Card accumulator (alpha-tested foliage quads), with outward "volume" normals
// ---------------------------------------------------------------------------

class Cards {
  pos: number[] = []; nor: number[] = []; uv: number[] = []; wind: number[] = []; idx: number[] = [];
  constructor(private H: number, private normalBlend = 0.75) {}

  /**
   * A card following a path of centre points (base → tip), with per-point half widths and a
   * `side` vector (across the card). UV: u across (0..1), v along (0 at base → 1 at tip).
   * windFn(point, t) supplies the wind weight; `centre` is the point used for outward normals.
   */
  strip(pts: V[], halfW: number[], side: V, axis: V, windFn: (p: V, t: number) => number, vRange: [number, number] = [0, 1]): void {
    const base = this.pos.length / 3;
    const n = pts.length;
    // face normal and winding: we want the front face to point "outward + up"
    const along = pts[n - 1].clone().sub(pts[0]).normalize();
    const fn = along.clone().cross(side).normalize(); // normal of (side, along) plane
    const centre = pts[Math.floor(n / 2)];
    const out = v3(centre.x - axis.x, 0, centre.z - axis.z);
    if (out.lengthSq() < 1e-6) out.set(side.x, 0, side.z);
    out.normalize();
    const pref = out.clone().multiplyScalar(0.8).addScaledVector(UP, 0.6);
    const flip = fn.dot(pref) < 0;
    if (flip) fn.negate();
    for (let i = 0; i < n; i++) {
      const t = i / (n - 1);
      const p = pts[i];
      const o = v3(p.x - axis.x, 0, p.z - axis.z);
      if (o.lengthSq() < 1e-6) o.copy(out); else o.normalize();
      const nn = fn.clone().multiplyScalar(1 - this.normalBlend).addScaledVector(o, this.normalBlend * 0.85).addScaledVector(UP, this.normalBlend * 0.45).normalize();
      if (nn.dot(fn) < 0.05) nn.addScaledVector(fn, 0.6).normalize();
      for (const s of [-1, 1]) {
        const q = p.clone().addScaledVector(side, s * halfW[i]);
        this.pos.push(q.x, q.y, q.z);
        this.nor.push(nn.x, nn.y, nn.z);
        this.uv.push(s < 0 ? 0 : 1, vRange[0] + (vRange[1] - vRange[0]) * t);
        this.wind.push(THREE.MathUtils.clamp(windFn(q, t), 0, 1));
      }
    }
    for (let i = 0; i < n - 1; i++) {
      const a = base + i * 2, b = a + 1, c = a + 2, d = a + 3;
      if (!flip) this.idx.push(a, b, d, a, d, c); else this.idx.push(a, d, b, a, c, d);
    }
    // verify winding against fn (cheap: first triangle)
    void this.H;
  }

  geometry(): THREE.BufferGeometry {
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.Float32BufferAttribute(this.pos, 3));
    g.setAttribute('normal', new THREE.Float32BufferAttribute(this.nor, 3));
    g.setAttribute('uv', new THREE.Float32BufferAttribute(this.uv, 2));
    g.setAttribute('windWeight', new THREE.Float32BufferAttribute(this.wind, 1));
    g.setIndex(this.idx);
    // ensure geometric winding agrees with the stored normals
    const p = g.getAttribute('position'), nA = g.getAttribute('normal');
    const A = v3(), B = v3(), C = v3(), N = v3();
    const ia = this.idx;
    for (let k = 0; k < ia.length; k += 3) {
      A.fromBufferAttribute(p, ia[k]); B.fromBufferAttribute(p, ia[k + 1]); C.fromBufferAttribute(p, ia[k + 2]);
      B.sub(A); C.sub(A); B.cross(C);
      N.fromBufferAttribute(nA, ia[k]);
      if (B.dot(N) < 0) { const t = ia[k + 1]; ia[k + 1] = ia[k + 2]; ia[k + 2] = t; }
    }
    g.setIndex(ia);
    g.computeBoundingBox();
    g.computeBoundingSphere();
    return g;
  }
}

/** Two cards crossing along `axis` direction, tilted ±tilt around it (X cross-section). */
function crossedCards(cards: Cards, pts: V[], halfW: number[], tilt: number, roll: number, axisPt: V, windFn: (p: V, t: number) => number, count = 2): void {
  const along = pts[pts.length - 1].clone().sub(pts[0]).normalize();
  let side0 = UP.clone().cross(along);
  if (side0.lengthSq() < 1e-4) side0 = v3(1, 0, 0);
  side0.normalize();
  const up0 = along.clone().cross(side0).normalize();
  for (let k = 0; k < count; k++) {
    const a = roll + (count === 1 ? 0 : (k === 0 ? -tilt : tilt));
    const side = side0.clone().multiplyScalar(Math.cos(a)).addScaledVector(up0, Math.sin(a));
    cards.strip(pts, halfW, side, axisPt, windFn);
  }
}

// ---------------------------------------------------------------------------
// Wood helpers
// ---------------------------------------------------------------------------

function woodTube(pts: Vec3[], r0: number, r1: number, tubular: number, radial: number, wind: (y: number, t: number) => number, caps = false): THREE.BufferGeometry {
  const g = tube(pts, (t) => r0 + (r1 - r0) * t, tubular, radial, { end: caps });
  // per-vertex t along the tube from the vertex ring index
  const ring = radial + 1;
  addFloatAttr(g, 'windWeight', (_x, y, _z, i) => wind(y, Math.min(1, Math.floor(i / ring) / tubular)));
  return g;
}

function trunkGeom(H: number, R: number, seed: number, flare: number, lean: number, radial = 9, segs = 12): THREE.BufferGeometry {
  const r = rng(seed);
  const pts: Vec3[] = [];
  const la = r() * Math.PI * 2;
  for (let i = 0; i <= 6; i++) {
    const t = i / 6;
    const wob = (r() - 0.5) * 0.12 * R * 4 * t;
    pts.push([Math.cos(la) * lean * t * t * H + wob, t * H, Math.sin(la) * lean * t * t * H + wob * 0.5]);
  }
  pts[0][1] = -0.15;
  const g = tube(pts, (t) => {
    const base = R * Math.pow(1 - t * 0.97, 0.85);
    return base * (1 + flare * Math.exp(-t * H / (R * 3.5)));
  }, segs, radial, { end: true });
  // bark bumps
  const pa = g.getAttribute('position');
  for (let i = 0; i < pa.count; i++) {
    const x = pa.getX(i), y = pa.getY(i), z = pa.getZ(i);
    const n = noise3(x * 3 + seed, y * 1.2, z * 3) * R * 0.08;
    const ang = Math.atan2(z, x);
    const rib = y < R * 4 ? Math.max(0, Math.cos(ang * 5 + seed)) * R * flare * 0.6 * Math.exp(-y / (R * 2.5)) : 0;
    const k = 1 + (n + rib) / Math.max(Math.hypot(x, z), 1e-3);
    pa.setXYZ(i, x * k, y, z * k);
  }
  g.computeVertexNormals();
  addFloatAttr(g, 'windWeight', (_x, y) => Math.pow(Math.max(0, y) / H, 1.6));
  return g;
}

// ---------------------------------------------------------------------------
// Conifers
// ---------------------------------------------------------------------------

interface ConiferOpts {
  H: number; R: number; seed: number;
  crownBase: number;   // fraction of H where live branches start
  crownR: number;      // max branch length (m)
  spacing: number;     // vertical distance between branch levels
  perLevel: number;    // avg branches per level
  droop: number;       // 0..1 how much branches hang
  upturn: number;      // tip lift (m per m of branch)
  cardW: number;       // card half-width factor
  needle: THREE.MeshStandardMaterial;
  bark: THREE.MeshStandardMaterial;
  shape: 'cone' | 'fir' | 'cedar';
  snags?: number;      // dead stubs on lower trunk
}

function conifer(o: ConiferOpts): TreeModel {
  const r = rng(o.seed);
  const { H, R } = o;
  const cards = new Cards(H, 0.7);
  const wood: THREE.BufferGeometry[] = [trunkGeom(H, R, o.seed, o.shape === 'cedar' ? 0.9 : 0.5, 0.004 + r() * 0.006)];
  const axis = (y: number): V => v3(0, y, 0);
  const trunkR = (y: number) => R * Math.pow(1 - (y / H) * 0.97, 0.85);
  const windF = (p: V, t: number) => (p.y / H) * 0.6 + t * 0.5;
  const y0 = H * o.crownBase;
  let az = r() * Math.PI * 2;
  for (let y = y0; y < H * 0.96; y += o.spacing * (0.75 + r() * 0.5)) {
    const t = (y - y0) / (H - y0);       // 0 crown bottom → 1 top
    // crown profile
    let prof: number;
    if (o.shape === 'cone') prof = Math.pow(1 - t, 0.85) * (t < 0.08 ? 0.75 + t * 3 : 1);
    else if (o.shape === 'fir') prof = Math.pow(1 - t, 1.0);
    else prof = Math.pow(1 - t, 0.75) * (0.8 + 0.2 * Math.sin(t * 9 + o.seed));
    const n = Math.max(1, Math.round(o.perLevel * (0.7 + r() * 0.6) * (0.6 + 0.4 * (1 - t))));
    for (let b = 0; b < n; b++) {
      az += 2.39996 + (r() - 0.5) * 0.6;
      if (o.shape === 'cone' && r() < 0.12 && t < 0.7) continue; // gaps (irregular Douglas-fir crown)
      const L = Math.max(0.45, o.crownR * prof * (0.75 + r() * 0.4));
      const dir = v3(Math.cos(az), 0, Math.sin(az));
      const rt = trunkR(y);
      const droop = o.droop * (1 - t * 0.6);
      const start = axis(y).addScaledVector(dir, rt * 0.6);
      const rise = (o.shape === 'cedar' ? 0.15 : 0.08) * L * (1 - droop) + t * 0.3 * L;
      const P = (u: number): V => {
        // out, sag in the middle, tip turns up (J-shape) for cedars / douglas
        const sag = droop * L * (0.55 * Math.sin(u * Math.PI * 0.9) + 0.25 * u);
        const lift = o.upturn * L * Math.pow(u, 3);
        return start.clone().addScaledVector(dir, L * u).add(v3(0, rise * u - sag + lift, 0));
      };
      const segs = L > 2.2 ? 4 : L > 1.2 ? 3 : 2;
      // branch wood (only for longer branches)
      if (L > 1.3) {
        const bp: Vec3[] = [0, 0.35, 0.7, 1].map((u) => P(u * 0.85).toArray() as Vec3);
        wood.push(woodTube(bp, Math.min(rt * 0.45, 0.025 + L * 0.018), 0.008, 3, 3, (yy, tt) => (yy / H) * 0.6 + tt * 0.45));
      }
      // foliage cards: overlapping segments along the branch, crossed
      for (let s = 0; s < segs; s++) {
        const u0 = 0.06 + (s / segs) * 0.92 - 0.05, u1 = 0.06 + ((s + 1) / segs) * 0.92 + 0.07;
        const pts = [P(u0), P((u0 + u1) / 2), P(Math.min(u1, 1.04))];
        const wMid = o.cardW * (0.38 + 0.32 * Math.sin(Math.PI * Math.min(1, (u0 + u1) / 2 + 0.15))) * Math.min(1.4, 0.45 + L * 0.28);
        const hw = [wMid * 0.75, wMid, wMid * 0.8];
        const tilt = o.shape === 'cedar' ? 0.75 : 0.62;
        const roll = o.shape === 'cedar' ? 0.25 * (r() - 0.5) : 0;
        crossedCards(cards, pts, hw, tilt, roll, axis(y), (p, tt) => windF(p, (u0 + (u1 - u0) * tt)));
      }
    }
  }
  // leader / spire
  const top = H * 0.94;
  for (let k = 0; k < 3; k++) {
    const a = (k / 3) * Math.PI;
    const side = v3(Math.cos(a), 0, Math.sin(a));
    const pts = [v3(0, top - 2.2, 0), v3(0, top - 0.9, 0), v3(0.02, H + 0.15, 0.01)];
    cards.strip(pts, [0.55, 0.4, 0.12], side, v3(-side.z * 3, top, side.x * 3), (p) => 0.4 + 0.6 * p.y / H);
  }
  // inner fill: vertical cards hugging the trunk through the crown (hides see-through)
  for (let y = y0 + 0.6; y < H * 0.85; y += 1.6) {
    const t = (y - y0) / (H - y0);
    const w = o.crownR * (1 - t) * 0.55 + 0.4;
    for (let k = 0; k < 2; k++) {
      const a = r() * Math.PI + k * Math.PI / 2;
      const side = v3(Math.cos(a), 0, Math.sin(a));
      cards.strip([v3(0, y - 0.2, 0), v3(0, y + 0.6, 0), v3(0, y + 1.5, 0)], [w * 0.9, w, w * 0.7], side, v3(-side.z, y, side.x), (p) => (p.y / H) * 0.6);
    }
  }
  // dead snags on the lower bare trunk
  const snags = o.snags ?? 6;
  for (let i = 0; i < snags; i++) {
    const y = 1.5 + r() * (y0 - 1.5);
    if (y > y0) continue;
    const a = r() * Math.PI * 2;
    const L = 0.3 + r() * 0.7;
    const s = v3(Math.cos(a), 0, Math.sin(a));
    const rt = trunkR(y);
    wood.push(woodTube([[s.x * rt * 0.5, y, s.z * rt * 0.5], [s.x * (rt + L), y - L * 0.25, s.z * (rt + L)]], 0.035, 0.012, 1, 3, (yy) => (yy / H) * 0.5));
  }
  return {
    parts: [
      { geometry: merge(wood), material: o.bark },
      { geometry: cards.geometry(), material: o.needle },
    ],
    height: H + 0.15,
    trunkRadius: R,
  };
}

// ---------------------------------------------------------------------------
// Broadleaf (birch) + dead trees
// ---------------------------------------------------------------------------

interface BranchSpec { from: V; dir: V; len: number; r0: number; depth: number }

function broadleaf(H: number, R: number, seed: number, opts: { leaves: THREE.MeshStandardMaterial | null; bark: THREE.MeshStandardMaterial; dead?: boolean; broken?: boolean }): TreeModel {
  const r = rng(seed);
  const wood: THREE.BufferGeometry[] = [];
  const cards = new Cards(H, 0.65);
  const trunkH = opts.broken ? H : H * 0.92;
  const lean = 0.01 + r() * 0.02;
  const la = r() * Math.PI * 2;
  const trunkPts: Vec3[] = [];
  for (let i = 0; i <= 6; i++) {
    const t = i / 6;
    trunkPts.push([Math.cos(la) * lean * H * t * t + (r() - 0.5) * 0.15 * t, t * trunkH, Math.sin(la) * lean * H * t * t + (r() - 0.5) * 0.15 * t]);
  }
  trunkPts[0][1] = -0.1;
  const trunkCurve = new THREE.CatmullRomCurve3(trunkPts.map((p) => v3(...p)));
  const topR = opts.broken ? R * 0.55 : R * 0.12;
  const tg = tube(trunkCurve, (t) => (R + (topR - R) * Math.pow(t, 0.8)) * (1 + 0.35 * Math.exp(-t * 25)), 12, 9, { end: true });
  displace(tg, (p) => {
    const n = noise3(p.x * 4 + seed, p.y * 1.5, p.z * 4) * R * 0.08;
    const d = Math.hypot(p.x, p.z);
    if (d > 1e-3) { p.x *= 1 + n / d; p.z *= 1 + n / d; }
  });
  tg.computeVertexNormals();
  addFloatAttr(tg, 'windWeight', (_x, y) => Math.pow(Math.max(0, y) / H, 1.6) * 0.8);
  wood.push(tg);
  if (opts.broken) {
    // jagged broken top: a few splinters
    const tp = trunkCurve.getPointAt(1);
    for (let i = 0; i < 5; i++) {
      const a = (i / 5) * Math.PI * 2;
      wood.push(woodTube([[tp.x + Math.cos(a) * topR * 0.6, tp.y - 0.1, tp.z + Math.sin(a) * topR * 0.6], [tp.x + Math.cos(a) * topR * 0.3, tp.y + 0.3 + r() * 0.6, tp.z + Math.sin(a) * topR * 0.3]], topR * 0.35, 0.01, 1, 3, () => 0.7));
    }
  }
  const windF = (p: V, t: number) => (p.y / H) * 0.6 + t * 0.45;
  const queue: BranchSpec[] = [];
  const nMain = opts.broken ? 7 : opts.dead ? 11 : 14;
  for (let i = 0; i < nMain; i++) {
    const t = 0.32 + (i / nMain) * (opts.broken ? 0.6 : 0.62) + (r() - 0.5) * 0.04;
    const from = trunkCurve.getPointAt(t);
    const a = i * 2.39996 + r() * 0.5;
    const up = opts.dead ? 0.35 + r() * 0.5 : 0.7 + r() * 0.5 + t * 0.4;
    const dir = v3(Math.cos(a), up, Math.sin(a)).normalize();
    const len = (opts.broken ? 1.0 + r() * 1.4 : H * (0.18 + 0.2 * (1 - t))) * (0.8 + r() * 0.4);
    queue.push({ from, dir, len, r0: R * (0.42 - t * 0.28), depth: 0 });
  }
  // crown top cluster
  if (!opts.broken) {
    const tp = trunkCurve.getPointAt(1);
    for (let k = 0; k < 3; k++) queue.push({ from: tp, dir: v3(Math.cos(k * 2.1), 2.5, Math.sin(k * 2.1)).normalize(), len: H * 0.12, r0: topR, depth: 1 });
  }
  const maxDepth = opts.dead ? 2 : 2;
  while (queue.length) {
    const b = queue.shift()!;
    const bend = v3((r() - 0.5) * 0.3, opts.dead ? -0.08 : 0.05, (r() - 0.5) * 0.3);
    const p0 = b.from, p1 = p0.clone().addScaledVector(b.dir, b.len * 0.5).addScaledVector(bend, b.len * 0.3);
    const p2 = p0.clone().addScaledVector(b.dir, b.len).addScaledVector(bend, b.len * 0.6);
    const pts: Vec3[] = [p0, p1, p2].map((p) => p.toArray() as Vec3);
    const rEnd = Math.max(0.006, b.r0 * 0.25);
    const radial = b.depth === 0 ? 5 : 3;
    const tStart = b.depth === 0 ? 0.0 : 0.4;
    wood.push(woodTube(b.depth === 2 ? [pts[0], pts[2]] : pts, Math.max(b.r0, 0.01), rEnd, b.depth === 0 ? 4 : b.depth === 1 ? 2 : 1, radial, (y, tt) => (y / H) * 0.6 + (tStart + tt * 0.6) * 0.45, false));
    const curve = new THREE.CatmullRomCurve3([p0, p1, p2]);
    if (b.depth < maxDepth) {
      const nk = b.depth === 0 ? 3 : 2;
      for (let k = 0; k < nk; k++) {
        const u = 0.35 + (k / nk) * 0.55 + (r() - 0.5) * 0.1;
        const from = curve.getPointAt(u);
        const a = r() * Math.PI * 2;
        const dir = b.dir.clone().multiplyScalar(0.8).add(v3(Math.cos(a) * 0.7, opts.dead ? -0.05 : 0.25, Math.sin(a) * 0.7)).normalize();
        queue.push({ from, dir, len: b.len * (0.45 + r() * 0.2), r0: Math.max(0.008, b.r0 * 0.45), depth: b.depth + 1 });
      }
    }
    // leaf clusters along the outer branches
    if (opts.leaves && b.depth >= 1) {
      const nc = 1;
      for (let k = 0; k < nc; k++) {
        const u = b.depth === 1 ? 0.6 : 0.85;
        const c = curve.getPointAt(Math.min(1, u));
        const s = (1.4 + r() * 0.8) * (b.depth === 1 ? 1.15 : 1.0);
        const tilt = r() * Math.PI;
        // leaf card "star": 3 cards, twig base at the branch, hanging slightly
        for (let j = 0; j < 3; j++) {
          const ang = tilt + (j / 3) * Math.PI;
          const dirL = b.dir.clone().add(v3(Math.cos(ang) * 0.6, -0.25 - r() * 0.3, Math.sin(ang) * 0.6)).normalize();
          const side = dirL.clone().cross(v3(Math.cos(ang + 1.3), 0.4, Math.sin(ang + 1.3))).normalize();
          const base = c.clone().addScaledVector(dirL, -s * 0.25);
          const ptsL = [base, base.clone().addScaledVector(dirL, s).add(v3(0, -s * 0.12, 0))];
          cards.strip(ptsL, [s * 0.45, s * 0.5], side, v3(0, c.y, 0), (p, tt) => windF(p, 0.6 + tt * 0.4));
        }
      }
    }
  }
  const parts: TreeModel['parts'] = [{ geometry: merge(wood), material: opts.bark }];
  if (opts.leaves) parts.push({ geometry: cards.geometry(), material: opts.leaves });
  return { parts, height: opts.broken ? trunkH + 0.6 : H, trunkRadius: R };
}

// ---------------------------------------------------------------------------
// Bushes
// ---------------------------------------------------------------------------

function bush(seed: number, size: number, foliage: THREE.MeshStandardMaterial, bark: THREE.MeshStandardMaterial, needle = false): TreeModel {
  const r = rng(seed);
  const cards = new Cards(size, 0.85);
  const wood: THREE.BufferGeometry[] = [];
  const H = size;
  const n = needle ? 44 : 40;
  for (let i = 0; i < n; i++) {
    // points on an upper ellipsoid, denser at the rim
    const a = i * 2.39996 + r() * 0.3;
    const el = Math.acos(1 - (i + 0.5) / n * 0.95);   // 0 top → ~π/2 rim
    const rad = size * 0.62 * (0.8 + r() * 0.35);
    const dir = v3(Math.sin(el) * Math.cos(a), Math.cos(el) * 0.85, Math.sin(el) * Math.sin(a));
    const tip = dir.clone().multiplyScalar(rad).add(v3(0, size * 0.18, 0));
    tip.y = Math.max(tip.y, size * 0.12);
    const base = v3(dir.x * 0.08, 0.02, dir.z * 0.08);
    const s = size * (needle ? 0.62 : 0.72) * (0.8 + r() * 0.4);
    const along = tip.clone().sub(base).normalize();
    const start = tip.clone().addScaledVector(along, -s * 0.85);
    const pts = [start, start.clone().addScaledVector(along, s * 0.55), start.clone().addScaledVector(along, s).add(v3(0, -s * 0.08, 0))];
    const roll = r() * Math.PI;
    crossedCards(cards, pts, [s * 0.32, s * 0.48, s * 0.38], 0.8, roll, v3(0, tip.y * 0.5, 0), (p, t) => Math.min(1, (p.y / H) * 0.5 + t * 0.6), 2);
    if (i % 3 === 0) wood.push(woodTube([base.toArray() as Vec3, start.clone().lerp(tip, 0.3).toArray() as Vec3], 0.018, 0.006, 1, 3, (y) => (y / H) * 0.5));
  }
  return {
    parts: [{ geometry: merge(wood), material: bark }, { geometry: cards.geometry(), material: foliage }],
    height: size * 1.05,
    trunkRadius: 0.05,
  };
}

// ---------------------------------------------------------------------------
// Rocks
// ---------------------------------------------------------------------------

let mossyRock: THREE.MeshStandardMaterial | null = null;
function rockMaterial(): THREE.MeshStandardMaterial {
  if (!mossyRock) {
    mossyRock = getMaterial('rock').clone();
    mossyRock.vertexColors = true;
    mossyRock.name = 'rock_mossy';
  }
  return mossyRock;
}

function rock(seed: number, size: number, kind: 'boulder' | 'slab' | 'crag'): TreeModel {
  const r = rng(seed);
  const detail = size > 2 ? 9 : 7;
  let g: THREE.BufferGeometry = new THREE.IcosahedronGeometry(1, detail);
  g.deleteAttribute('uv');
  g = mergeVertices(g);
  const sx = kind === 'slab' ? 1.5 : kind === 'crag' ? 0.8 : 1.15;
  const sy = kind === 'slab' ? 0.45 : kind === 'crag' ? 1.3 : 0.75;
  const sz = kind === 'slab' ? 1.1 : kind === 'crag' ? 0.9 : 1.0;
  const off = seed * 3.17;
  displace(g, (v) => {
    const n = fbm3(v.x * 1.4 + off, v.y * 1.4, v.z * 1.4 - off, 5);
    // terraced / faceted look: quantise part of the noise
    const q = Math.round(n * 5) / 5;
    const ridged = 1 - Math.abs(noise3(v.x * 2.5 - off, v.y * 2.5, v.z * 2.5 + off));
    const k = 1 + n * 0.32 + (q - n) * 0.25 + ridged * 0.08;
    v.multiplyScalar(k);
    v.set(v.x * sx, v.y * sy, v.z * sz);
  });
  // squash into the ground: flat-ish bottom with slight embed
  const bb = new THREE.Box3().setFromBufferAttribute(g.getAttribute('position') as THREE.BufferAttribute);
  const yCut = bb.min.y + (bb.max.y - bb.min.y) * 0.18;
  displace(g, (v) => { if (v.y < yCut) v.y = yCut - (yCut - v.y) * 0.15; });
  const s = size / Math.max(bb.max.x - bb.min.x, bb.max.z - bb.min.z);
  g.translate(0, -yCut, 0);
  g.scale(s, s, s);
  g.translate(0, -0.06 * size * 0.25, 0);
  g.computeVertexNormals();
  // blend smooth + flat normals for a chiselled-but-natural look
  const smooth = g.toNonIndexed();
  const flat = smooth.clone();
  flat.computeVertexNormals();
  const sn = smooth.getAttribute('normal'), fnA = flat.getAttribute('normal');
  for (let i = 0; i < sn.count; i++) {
    const nx = sn.getX(i) * 0.45 + fnA.getX(i) * 0.55, ny = sn.getY(i) * 0.45 + fnA.getY(i) * 0.55, nz = sn.getZ(i) * 0.45 + fnA.getZ(i) * 0.55;
    const l = Math.hypot(nx, ny, nz) || 1;
    sn.setXYZ(i, nx / l, ny / l, nz / l);
  }
  // moss on up-facing surfaces + darker grime toward the ground (vertex colour)
  const pa = smooth.getAttribute('position');
  const col = new Float32Array(pa.count * 3);
  const H = size * sy / Math.max(sx, sz);
  for (let i = 0; i < pa.count; i++) {
    const x = pa.getX(i), y = pa.getY(i), z = pa.getZ(i);
    const ny = sn.getY(i);
    const mn = fbm3(x * 1.3 + off, y * 1.3, z * 1.3, 3);
    const moss = THREE.MathUtils.smoothstep(ny + mn * 0.6 - 0.15, 0.2, 0.75) * (0.6 + 0.4 * r());
    const ground = THREE.MathUtils.clamp(1 - y / (H * 0.25 + 0.05), 0, 1) * 0.3;
    const base = 0.95 - ground + mn * 0.08;
    col[i * 3] = THREE.MathUtils.lerp(base, 0.42, moss);
    col[i * 3 + 1] = THREE.MathUtils.lerp(base, 0.52, moss);
    col[i * 3 + 2] = THREE.MathUtils.lerp(base * 0.97, 0.26, moss);
  }
  smooth.setAttribute('color', new THREE.BufferAttribute(col, 3));
  boxProjectUV(smooth, 0.7);
  addFloatAttr(smooth, 'windWeight', () => 0);
  smooth.computeBoundingBox();
  smooth.computeBoundingSphere();
  return { parts: [{ geometry: smooth, material: rockMaterial() }], height: smooth.boundingBox!.max.y, trunkRadius: size * 0.5 };
}

// ---------------------------------------------------------------------------
// Fallen logs
// ---------------------------------------------------------------------------

function log(seed: number, len: number, R: number, bark: THREE.MeshStandardMaterial): TreeModel {
  const r = rng(seed);
  const pts: Vec3[] = [];
  for (let i = 0; i <= 4; i++) {
    const t = i / 4;
    pts.push([-len / 2 + t * len, 0, (r() - 0.5) * 0.25 * Math.sin(t * Math.PI)]);
  }
  const rf = (t: number) => R * (1.12 - t * 0.35);
  const g = tube(pts, rf, 14, 10);
  displace(g, (p) => {
    const n = noise3(p.x * 2 + seed, p.y * 3, p.z * 3) * R * 0.1;
    const d = Math.hypot(p.y, p.z) || 1;
    p.y *= 1 + n / d; p.z *= 1 + n / d;
  });
  g.computeVertexNormals();
  const wood: THREE.BufferGeometry[] = [g];
  // broken branch stubs
  for (let i = 0; i < 5; i++) {
    const t = 0.15 + r() * 0.7;
    const a = r() * Math.PI * 2;
    const x = -len / 2 + t * len;
    const rr = rf(t);
    const d = v3(0.2 * (r() - 0.5), Math.cos(a), Math.sin(a)).normalize();
    if (d.y < -0.3) continue;
    const L = 0.3 + r() * 0.6;
    wood.push(tube([[x, d.y * rr * 0.6, d.z * rr * 0.6], [x + d.x * L, d.y * (rr + L), d.z * (rr + L)]], (tt) => 0.085 * (1 - tt * 0.6), 1, 5, { end: true }));
  }
  const barkG = merge(wood);
  // cut ends (end-grain discs)
  const ends: THREE.BufferGeometry[] = [];
  for (const end of [0, 1]) {
    const x = end === 0 ? -len / 2 : len / 2;
    const rr = rf(end) * 0.96;
    const disc = new THREE.CircleGeometry(rr, 12);
    disc.rotateY(end === 0 ? -Math.PI / 2 : Math.PI / 2);
    disc.translate(x + (end === 0 ? -0.002 : 0.002), 0, pts[end === 0 ? 0 : 4][2]);
    const uv = disc.getAttribute('uv');
    for (let i = 0; i < uv.count; i++) uv.setXY(i, (uv.getX(i) - 0.5) * rr * 2, (uv.getY(i) - 0.5) * rr * 2);
    ends.push(disc);
  }
  const endG = merge(ends);
  const lift = R * 1.12 * 0.85;
  for (const gg of [barkG, endG]) { gg.translate(0, lift, 0); addFloatAttr(gg, 'windWeight', () => 0); }
  return {
    parts: [{ geometry: barkG, material: bark }, { geometry: endG, material: tinted('wood', 0xc8b08a) }],
    height: lift + R * 1.2,
    trunkRadius: R,
  };
}

// local displace (avoid importing to keep the module self-contained for tree-shaking)
function displace(g: THREE.BufferGeometry, fn: (v: V) => void): void {
  const p = g.getAttribute('position');
  const v = v3();
  for (let i = 0; i < p.count; i++) {
    v.fromBufferAttribute(p, i);
    fn(v);
    p.setXYZ(i, v.x, v.y, v.z);
  }
  p.needsUpdate = true;
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

let cached: ReturnType<typeof buildAll> | null = null;

function buildAll() {
  const bark = getMaterial('bark');
  const barkRed = tinted('bark', 0xc89a7a);    // cedar: reddish, stringy
  const barkGrey = tinted('bark', 0xa8a49c);   // dead / weathered
  const needles = getMaterial('pineNeedles');
  const needlesBlue = tinted('pineNeedles', 0xc8d8d0);
  const needlesYellow = tinted('pineNeedles', 0xe8f0c8);
  const leaves = getMaterial('leaves');
  const leavesYellow = tinted('leaves', 0xf0e8b8);
  const birch = getMaterial('birchBark');

  const pines: TreeModel[] = [
    conifer({ H: 22, R: 0.42, seed: 11, crownBase: 0.34, crownR: 4.4, spacing: 0.46, perLevel: 3.0, droop: 0.42, upturn: 0.25, cardW: 1.3, needle: needles, bark, shape: 'cone' }),
    conifer({ H: 26, R: 0.5, seed: 23, crownBase: 0.42, crownR: 4.8, spacing: 0.5, perLevel: 3.0, droop: 0.45, upturn: 0.3, cardW: 1.35, needle: needlesYellow, bark, shape: 'cone' }),
    conifer({ H: 17, R: 0.32, seed: 37, crownBase: 0.26, crownR: 3.7, spacing: 0.42, perLevel: 3.0, droop: 0.38, upturn: 0.2, cardW: 1.25, needle: needles, bark, shape: 'cone' }),
  ];
  const firs: TreeModel[] = [
    conifer({ H: 19, R: 0.4, seed: 41, crownBase: 0.1, crownR: 4.0, spacing: 0.42, perLevel: 2.7, droop: 0.55, upturn: 0.35, cardW: 1.4, needle: needlesBlue, bark: barkRed, shape: 'cedar', snags: 2 }),
    conifer({ H: 15, R: 0.3, seed: 53, crownBase: 0.07, crownR: 3.3, spacing: 0.36, perLevel: 3.0, droop: 0.3, upturn: 0.15, cardW: 1.25, needle: needlesBlue, bark, shape: 'fir', snags: 1 }),
    conifer({ H: 23, R: 0.48, seed: 67, crownBase: 0.14, crownR: 4.5, spacing: 0.46, perLevel: 2.6, droop: 0.6, upturn: 0.4, cardW: 1.45, needle: needles, bark: barkRed, shape: 'cedar', snags: 3 }),
  ];
  const birches: TreeModel[] = [
    broadleaf(13, 0.17, 71, { leaves, bark: birch }),
    broadleaf(11, 0.14, 83, { leaves: leavesYellow, bark: birch }),
    broadleaf(15, 0.2, 97, { leaves, bark: birch }),
  ];
  const dead: TreeModel[] = [
    broadleaf(12, 0.28, 101, { leaves: null, bark: barkGrey, dead: true }),
    broadleaf(7.5, 0.4, 113, { leaves: null, bark: barkGrey, dead: true, broken: true }),
  ];
  const bushes: TreeModel[] = [
    bush(131, 1.5, leaves, bark),
    bush(137, 1.1, leavesYellow, bark),
    bush(139, 1.8, needlesBlue, bark, true),
  ];
  const rocks: TreeModel[] = [
    rock(151, 1.4, 'boulder'),
    rock(157, 3.6, 'slab'),
    rock(163, 0.7, 'crag'),
    rock(167, 2.4, 'boulder'),
  ];
  const logs: TreeModel[] = [
    log(171, 5.5, 0.32, bark),
    log(173, 3.8, 0.24, barkGrey),
  ];
  return { pines, firs, birches, dead, bushes, rocks, logs };
}

/**
 * Build (once, cached) all vegetation models. Call after textures/materials are ready.
 */
export function buildTreeModels(): { pines: TreeModel[]; firs: TreeModel[]; birches: TreeModel[]; dead: TreeModel[]; bushes: TreeModel[]; rocks: TreeModel[]; logs: TreeModel[] } {
  if (!cached) cached = buildAll();
  return cached;
}
