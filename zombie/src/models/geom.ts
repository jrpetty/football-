/**
 * Shared geometry helpers for REKINDLE models.
 *
 * Conventions
 *  - Units are metres. Textures from `getMaterial` tile at ~1 UV unit per metre,
 *    so every primitive here generates metre-scaled UVs.
 *  - Primitives are centred on the origin unless stated otherwise. Use `xf()` to place them.
 *  - `PartBuilder` collects geometry per material and merges it into one geometry per material.
 *
 * Exports are kept stable: other modules (machines.ts, buildpieces.ts) import them.
 */
import * as THREE from 'three';
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js';
import { getMaterial, getTintedMaterial, type MaterialName } from '../render/materials';

export type Vec3 = [number, number, number];
export type Vec2 = [number, number];

// ---------------------------------------------------------------------------
// Random / noise
// ---------------------------------------------------------------------------

/** Deterministic seeded RNG (mulberry32) returning floats in [0,1). */
export function rng(seed: number): () => number {
  let a = (seed >>> 0) || 0x9e3779b9;
  return () => {
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Stable 32-bit hash of a string (FNV-1a). */
export function hashStr(s: string): number {
  let h = 2166136261;
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i);
    h = Math.imul(h, 16777619);
  }
  return h >>> 0;
}

function hash3(x: number, y: number, z: number): number {
  let h = Math.imul(x | 0, 374761393) ^ Math.imul(y | 0, 668265263) ^ Math.imul(z | 0, 2147483647);
  h = Math.imul(h ^ (h >>> 13), 1274126177);
  h ^= h >>> 16;
  return (h >>> 0) / 4294967295;
}

const smooth = (t: number) => t * t * (3 - 2 * t);

/** 3D value noise in [-1,1]. */
export function noise3(x: number, y: number, z: number): number {
  const xi = Math.floor(x), yi = Math.floor(y), zi = Math.floor(z);
  const xf = smooth(x - xi), yf = smooth(y - yi), zf = smooth(z - zi);
  const l = THREE.MathUtils.lerp;
  const c = (dx: number, dy: number, dz: number) => hash3(xi + dx, yi + dy, zi + dz);
  const v = l(
    l(l(c(0, 0, 0), c(1, 0, 0), xf), l(c(0, 1, 0), c(1, 1, 0), xf), yf),
    l(l(c(0, 0, 1), c(1, 0, 1), xf), l(c(0, 1, 1), c(1, 1, 1), xf), yf),
    zf,
  );
  return v * 2 - 1;
}

/** Fractal value noise, roughly in [-1,1]. */
export function fbm3(x: number, y: number, z: number, octaves = 4): number {
  let sum = 0, amp = 0.5, f = 1, norm = 0;
  for (let i = 0; i < octaves; i++) {
    sum += noise3(x * f, y * f, z * f) * amp;
    norm += amp;
    amp *= 0.5;
    f *= 2.03;
  }
  return sum / norm;
}

// ---------------------------------------------------------------------------
// Transforms
// ---------------------------------------------------------------------------

const _m = new THREE.Matrix4();
const _q = new THREE.Quaternion();
const _e = new THREE.Euler();
const _v = new THREE.Vector3();
const _s = new THREE.Vector3();

/**
 * Transform a geometry in place: scale, then rotate (Euler XYZ, radians), then translate.
 * Returns the same geometry for chaining.
 */
export function xf<T extends THREE.BufferGeometry>(
  g: T, x = 0, y = 0, z = 0, rx = 0, ry = 0, rz = 0, sx = 1, sy = 1, sz = 1,
): T {
  _q.setFromEuler(_e.set(rx, ry, rz));
  _m.compose(_v.set(x, y, z), _q, _s.set(sx, sy, sz));
  g.applyMatrix4(_m);
  return g;
}

/** Apply an arbitrary matrix (in place). */
export function xfm<T extends THREE.BufferGeometry>(g: T, m: THREE.Matrix4): T {
  g.applyMatrix4(m);
  return g;
}

/**
 * Orient a geometry built along +Y (centred on origin, length = |b-a|) so it spans from a to b.
 * The geometry is NOT rescaled; build it with the right length first (see `cylinderBetween`).
 */
export function alignY<T extends THREE.BufferGeometry>(g: T, a: Vec3, b: Vec3): T {
  const A = new THREE.Vector3(...a), B = new THREE.Vector3(...b);
  const dir = B.clone().sub(A);
  const len = dir.length();
  if (len < 1e-9) return g;
  dir.divideScalar(len);
  _q.setFromUnitVectors(new THREE.Vector3(0, 1, 0), dir);
  _m.compose(A.add(B).multiplyScalar(0.5), _q, _s.set(1, 1, 1));
  g.applyMatrix4(_m);
  return g;
}

// ---------------------------------------------------------------------------
// UV helpers
// ---------------------------------------------------------------------------

/**
 * Box-project UVs (metre scale) using each vertex's normal to pick the projection plane.
 * Works for any geometry; call before translating if you want UVs relative to the part.
 */
export function boxProjectUV<T extends THREE.BufferGeometry>(g: T, scale = 1, ox = 0, oy = 0): T {
  const p = g.getAttribute('position');
  if (!g.getAttribute('normal')) g.computeVertexNormals();
  const n = g.getAttribute('normal');
  const uv = new Float32Array(p.count * 2);
  for (let i = 0; i < p.count; i++) {
    const ax = Math.abs(n.getX(i)), ay = Math.abs(n.getY(i)), az = Math.abs(n.getZ(i));
    const x = p.getX(i), y = p.getY(i), z = p.getZ(i);
    let u: number, v: number;
    if (ax >= ay && ax >= az) { u = n.getX(i) > 0 ? -z : z; v = y; }
    else if (ay >= az) { u = x; v = n.getY(i) > 0 ? -z : z; }
    else { u = n.getZ(i) > 0 ? x : -x; v = y; }
    uv[i * 2] = u * scale + ox;
    uv[i * 2 + 1] = v * scale + oy;
  }
  g.setAttribute('uv', new THREE.BufferAttribute(uv, 2));
  return g;
}

/** Multiply / offset existing UVs. */
export function scaleUV<T extends THREE.BufferGeometry>(g: T, su: number, sv: number, ou = 0, ov = 0): T {
  const uv = g.getAttribute('uv');
  if (!uv) return g;
  for (let i = 0; i < uv.count; i++) uv.setXY(i, uv.getX(i) * su + ou, uv.getY(i) * sv + ov);
  uv.needsUpdate = true;
  return g;
}

// ---------------------------------------------------------------------------
// Primitives (metre UVs)
// ---------------------------------------------------------------------------

/** Plain box, centred, metre UVs. */
export function box(w: number, h: number, d: number): THREE.BufferGeometry {
  const g = new THREE.BoxGeometry(w, h, d);
  return boxProjectUV(g);
}

/**
 * Chamfered box (44 triangles) with "soft bevel" normals: the flat faces keep their face
 * normal and the chamfer strips interpolate between neighbouring faces, so edges catch light
 * like a real rounded edge. `b` = chamfer size in metres.
 */
export function bevelBox(w: number, h: number, d: number, b = 0.01): THREE.BufferGeometry {
  const H = [w / 2, h / 2, d / 2];
  b = Math.max(0, Math.min(b, H[0] * 0.49, H[1] * 0.49, H[2] * 0.49));
  if (b <= 1e-5) return box(w, h, d);
  const pos: number[] = [], nor: number[] = [], uvs: number[] = [];
  type Vt = { p: number[]; n: number[] };
  const vert = (c: number[], a: number): Vt => {
    const p = [0, 0, 0], n = [0, 0, 0];
    for (let k = 0; k < 3; k++) p[k] = k === a ? c[k] * H[k] : c[k] * (H[k] - b);
    n[a] = c[a];
    return { p, n };
  };
  const emit = (v: Vt) => {
    pos.push(v.p[0], v.p[1], v.p[2]);
    nor.push(v.n[0], v.n[1], v.n[2]);
    const a = v.n[0] !== 0 ? 0 : v.n[1] !== 0 ? 1 : 2;
    const s = v.n[a];
    if (a === 0) uvs.push(s > 0 ? -v.p[2] : v.p[2], v.p[1]);
    else if (a === 1) uvs.push(v.p[0], s > 0 ? -v.p[2] : v.p[2]);
    else uvs.push(s > 0 ? v.p[0] : -v.p[0], v.p[1]);
  };
  const tri = (a: Vt, bb: Vt, c: Vt) => {
    const e1 = [bb.p[0] - a.p[0], bb.p[1] - a.p[1], bb.p[2] - a.p[2]];
    const e2 = [c.p[0] - a.p[0], c.p[1] - a.p[1], c.p[2] - a.p[2]];
    const cr = [e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]];
    const cen = [a.p[0] + bb.p[0] + c.p[0], a.p[1] + bb.p[1] + c.p[1], a.p[2] + bb.p[2] + c.p[2]];
    if (cr[0] * cen[0] + cr[1] * cen[1] + cr[2] * cen[2] < 0) { emit(a); emit(c); emit(bb); }
    else { emit(a); emit(bb); emit(c); }
  };
  const quadV = (q: Vt[]) => { tri(q[0], q[1], q[2]); tri(q[0], q[2], q[3]); };
  for (let a = 0; a < 3; a++) {
    const u = (a + 1) % 3, v = (a + 2) % 3;
    for (const s of [-1, 1]) {
      const cs = [[-1, -1], [1, -1], [1, 1], [-1, 1]].map(([su, sv]) => {
        const c = [0, 0, 0]; c[a] = s; c[u] = su; c[v] = sv; return vert(c, a);
      });
      quadV(cs);
    }
  }
  for (let e = 0; e < 3; e++) {
    const a = (e + 1) % 3, bb = (e + 2) % 3;
    for (const sa of [-1, 1]) for (const sb of [-1, 1]) {
      const c0 = [0, 0, 0], c1 = [0, 0, 0];
      c0[a] = c1[a] = sa; c0[bb] = c1[bb] = sb; c0[e] = -1; c1[e] = 1;
      quadV([vert(c0, a), vert(c1, a), vert(c1, bb), vert(c0, bb)]);
    }
  }
  for (const sx of [-1, 1]) for (const sy of [-1, 1]) for (const sz of [-1, 1]) {
    const c = [sx, sy, sz];
    tri(vert(c, 0), vert(c, 1), vert(c, 2));
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('normal', new THREE.Float32BufferAttribute(nor, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uvs, 2));
  return g;
}

/** Alias kept for readability: rounded-looking box via soft-bevel chamfer. */
export const roundedBox = bevelBox;

export interface CylOpts { open?: boolean; hSeg?: number; thetaStart?: number; thetaLength?: number }

/** Cylinder along Y, centred, metre UVs (u around circumference, v along height). */
export function cylinder(rTop: number, rBot: number, h: number, radial = 12, o: CylOpts = {}): THREE.BufferGeometry {
  const g = new THREE.CylinderGeometry(rTop, rBot, h, radial, o.hSeg ?? 1, o.open ?? false,
    o.thetaStart ?? 0, o.thetaLength ?? Math.PI * 2);
  const uv = g.getAttribute('uv');
  const idx = g.getIndex()!;
  const circ = Math.max(rTop, rBot) * (o.thetaLength ?? Math.PI * 2);
  const done = new Uint8Array(uv.count);
  g.groups.forEach((gr, gi) => {
    for (let k = gr.start; k < gr.start + gr.count; k++) {
      const i = idx.getX(k);
      if (done[i]) continue;
      done[i] = 1;
      if (gi === 0) uv.setXY(i, uv.getX(i) * circ, uv.getY(i) * h);
      else {
        const r = gi === 1 ? rTop : rBot;
        uv.setXY(i, (uv.getX(i) - 0.5) * 2 * r, (uv.getY(i) - 0.5) * 2 * r);
      }
    }
  });
  g.clearGroups();
  return g;
}

/** Cylinder spanning from a to b (radii r0 at a, r1 at b). */
export function cylinderBetween(a: Vec3, b: Vec3, r0: number, r1 = r0, radial = 8, o: CylOpts = {}): THREE.BufferGeometry {
  const len = Math.hypot(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
  return alignY(cylinder(r1, r0, len, radial, o), a, b);
}

/** Box spanning from a to b (cross-section w × d). */
export function boxBetween(a: Vec3, b: Vec3, w: number, d: number, bevel = 0): THREE.BufferGeometry {
  const len = Math.hypot(b[0] - a[0], b[1] - a[1], b[2] - a[2]);
  return alignY(bevel > 0 ? bevelBox(w, len, d, bevel) : box(w, len, d), a, b);
}

/** Sphere with metre UVs. */
export function sphere(r: number, ws = 12, hs = 8, phiLen = Math.PI * 2, thetaLen = Math.PI): THREE.BufferGeometry {
  const g = new THREE.SphereGeometry(r, ws, hs, 0, phiLen, 0, thetaLen);
  return scaleUV(g, Math.PI * 2 * r, Math.PI * r);
}

/** Torus in the XY plane (axis Z) with metre UVs. */
export function torus(R: number, r: number, radial = 8, tubular = 16, arc = Math.PI * 2): THREE.BufferGeometry {
  const g = new THREE.TorusGeometry(R, r, radial, tubular, arc);
  return scaleUV(g, arc * R, Math.PI * 2 * r);
}

/**
 * Lathe around Y from a profile of [radius, y] points (bottom to top), metre UVs:
 * u = angle × max radius, v = cumulative profile length.
 */
export function lathe(profile: Vec2[], segments = 16, phiStart = 0, phiLength = Math.PI * 2): THREE.BufferGeometry {
  const pts = profile.map(([r, y]) => new THREE.Vector2(Math.max(r, 1e-5), y));
  const g = new THREE.LatheGeometry(pts, segments, phiStart, phiLength);
  const uv = g.getAttribute('uv');
  const n = pts.length;
  const cum: number[] = [0];
  for (let j = 1; j < n; j++) cum[j] = cum[j - 1] + pts[j].distanceTo(pts[j - 1]);
  const maxR = Math.max(...profile.map((p) => p[0]));
  for (let i = 0; i <= segments; i++) {
    for (let j = 0; j < n; j++) {
      uv.setXY(i * n + j, (i / segments) * phiLength * maxR, cum[j]);
    }
  }
  return g;
}

/**
 * Tapered tube along a polyline / curve. `radius` may be a constant or a function of t∈[0,1].
 * Metre UVs (u around, v along arc length). Optional end caps.
 * Adds nothing else; vertex count = (tubular+1)*(radial+1) (+caps).
 */
export function tube(
  path: Vec3[] | THREE.Curve<THREE.Vector3>,
  radius: number | ((t: number) => number),
  tubular = 12,
  radial = 6,
  caps: { start?: boolean; end?: boolean } = {},
  smoothPath = true,
): THREE.BufferGeometry {
  let curve: THREE.Curve<THREE.Vector3>;
  if (Array.isArray(path)) {
    const v = path.map((p) => new THREE.Vector3(...p));
    if (v.length === 2) curve = new THREE.LineCurve3(v[0], v[1]);
    else if (smoothPath) curve = new THREE.CatmullRomCurve3(v, false, 'centripetal');
    else {
      const cp = new THREE.CurvePath<THREE.Vector3>();
      for (let i = 0; i < v.length - 1; i++) cp.add(new THREE.LineCurve3(v[i], v[i + 1]));
      curve = cp;
    }
  } else curve = path;
  const rf = typeof radius === 'number' ? () => radius : radius;
  const frames = curve.computeFrenetFrames(tubular, false);
  const len = curve.getLength();
  const pos: number[] = [], nor: number[] = [], uv: number[] = [], index: number[] = [];
  const P = new THREE.Vector3(), N = new THREE.Vector3();
  const rAvg = (rf(0) + rf(1) + rf(0.5)) / 3;
  for (let i = 0; i <= tubular; i++) {
    const t = i / tubular;
    curve.getPointAt(t, P);
    const r = rf(t);
    for (let j = 0; j <= radial; j++) {
      const a = (j / radial) * Math.PI * 2;
      const c = Math.cos(a), s = Math.sin(a);
      N.copy(frames.normals[i]).multiplyScalar(c).addScaledVector(frames.binormals[i], s).normalize();
      pos.push(P.x + N.x * r, P.y + N.y * r, P.z + N.z * r);
      nor.push(N.x, N.y, N.z);
      uv.push((j / radial) * Math.PI * 2 * rAvg, t * len);
    }
  }
  for (let i = 0; i < tubular; i++) {
    for (let j = 0; j < radial; j++) {
      const a = i * (radial + 1) + j, b = (i + 1) * (radial + 1) + j;
      index.push(a, b, a + 1, b, b + 1, a + 1);
    }
  }
  const cap = (i: number, dirSign: number) => {
    const t = i / tubular;
    curve.getPointAt(t, P);
    const T = frames.tangents[i].clone().multiplyScalar(dirSign);
    const r = rf(t);
    const centre = pos.length / 3;
    pos.push(P.x, P.y, P.z); nor.push(T.x, T.y, T.z); uv.push(0, 0);
    for (let j = 0; j <= radial; j++) {
      const a = (j / radial) * Math.PI * 2;
      const c = Math.cos(a), s = Math.sin(a);
      N.copy(frames.normals[i]).multiplyScalar(c).addScaledVector(frames.binormals[i], s).normalize();
      pos.push(P.x + N.x * r, P.y + N.y * r, P.z + N.z * r);
      nor.push(T.x, T.y, T.z);
      uv.push(c * r, s * r);
    }
    for (let j = 0; j < radial; j++) {
      if (dirSign > 0) index.push(centre, centre + 1 + j, centre + 2 + j);
      else index.push(centre, centre + 2 + j, centre + 1 + j);
    }
  };
  if (caps.start) cap(0, -1);
  if (caps.end) cap(tubular, 1);
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('normal', new THREE.Float32BufferAttribute(nor, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  g.setIndex(index);
  // Fix winding if the frame handedness produced inward faces.
  fixWindingByNormals(g);
  return g;
}

/** Flip triangle winding where it disagrees with the stored vertex normals (majority per triangle). */
export function fixWindingByNormals(g: THREE.BufferGeometry): void {
  const p = g.getAttribute('position'), n = g.getAttribute('normal');
  const idx = g.getIndex();
  if (!idx || !n) return;
  const a = new THREE.Vector3(), b = new THREE.Vector3(), c = new THREE.Vector3();
  const nn = new THREE.Vector3();
  const arr = idx.array as Uint16Array | Uint32Array;
  for (let k = 0; k < arr.length; k += 3) {
    const i0 = arr[k], i1 = arr[k + 1], i2 = arr[k + 2];
    a.fromBufferAttribute(p, i0); b.fromBufferAttribute(p, i1); c.fromBufferAttribute(p, i2);
    b.sub(a); c.sub(a); b.cross(c);
    nn.set(n.getX(i0) + n.getX(i1) + n.getX(i2), n.getY(i0) + n.getY(i1) + n.getY(i2), n.getZ(i0) + n.getZ(i1) + n.getZ(i2));
    if (b.dot(nn) < 0) { arr[k + 1] = i2; arr[k + 2] = i1; }
  }
  idx.needsUpdate = true;
}

export interface ExtrudeOpts { bevel?: number; bevelSegments?: number; curveSegments?: number; uvScale?: number }

/**
 * Extrude a 2D shape (XY plane) along Z, centred on Z (from -depth/2 to +depth/2, bevel included).
 * Box-projected metre UVs.
 */
export function extrude(shape: THREE.Shape | THREE.Shape[], depth: number, o: ExtrudeOpts = {}): THREE.BufferGeometry {
  const bevel = o.bevel ?? 0;
  const inner = Math.max(depth - bevel * 2, 0.0001);
  const g = new THREE.ExtrudeGeometry(shape, {
    depth: inner,
    bevelEnabled: bevel > 0,
    bevelThickness: bevel,
    bevelSize: bevel,
    bevelOffset: -bevel,
    bevelSegments: o.bevelSegments ?? 1,
    curveSegments: o.curveSegments ?? 6,
  });
  g.translate(0, 0, -inner / 2);
  g.clearGroups();
  g.computeVertexNormals();
  return boxProjectUV(g, o.uvScale ?? 1);
}

/** Polygon shape from [x,y] points. */
export function polyShape(pts: Vec2[]): THREE.Shape {
  const s = new THREE.Shape();
  pts.forEach(([x, y], i) => (i === 0 ? s.moveTo(x, y) : s.lineTo(x, y)));
  s.closePath();
  return s;
}

/** Rounded rectangle shape centred at origin. */
export function roundRectShape(w: number, h: number, r: number): THREE.Shape {
  r = Math.min(r, w / 2, h / 2);
  const x = -w / 2, y = -h / 2;
  const s = new THREE.Shape();
  s.moveTo(x + r, y);
  s.lineTo(x + w - r, y);
  s.quadraticCurveTo(x + w, y, x + w, y + r);
  s.lineTo(x + w, y + h - r);
  s.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
  s.lineTo(x + r, y + h);
  s.quadraticCurveTo(x, y + h, x, y + h - r);
  s.lineTo(x, y + r);
  s.quadraticCurveTo(x, y, x + r, y);
  return s;
}

/** Flat polygon (convex, fan-triangulated) from 3D points, optional double-sided. Box UVs. */
export function polygon(pts: Vec3[], doubleSided = false): THREE.BufferGeometry {
  const pos: number[] = [];
  const push = (a: Vec3, b: Vec3, c: Vec3) => pos.push(...a, ...b, ...c);
  for (let i = 1; i < pts.length - 1; i++) {
    push(pts[0], pts[i], pts[i + 1]);
    if (doubleSided) push(pts[0], pts[i + 1], pts[i]);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.computeVertexNormals();
  return boxProjectUV(g);
}

/**
 * Thin flat slab from a convex planar polygon (two faces + rim), useful for glass panels.
 */
export function slab(pts: Vec3[], thickness: number): THREE.BufferGeometry {
  const P = pts.map((p) => new THREE.Vector3(...p));
  const nrm = new THREE.Vector3().subVectors(P[1], P[0]).cross(new THREE.Vector3().subVectors(P[2], P[0])).normalize();
  const off = nrm.clone().multiplyScalar(thickness / 2);
  const top = P.map((p) => p.clone().add(off).toArray() as Vec3);
  const bot = P.map((p) => p.clone().sub(off).toArray() as Vec3);
  const parts = [polygon(top), polygon([...bot].reverse())];
  for (let i = 0; i < P.length; i++) {
    const j = (i + 1) % P.length;
    parts.push(polygon([bot[i], bot[j], top[j], top[i]]));
  }
  return merge(parts);
}

// ---------------------------------------------------------------------------
// Vertex helpers
// ---------------------------------------------------------------------------

/** Add (or replace) a float vertex attribute computed per vertex from its position. */
export function addFloatAttr<T extends THREE.BufferGeometry>(
  g: T, name: string, fn: (x: number, y: number, z: number, i: number) => number,
): T {
  const p = g.getAttribute('position');
  const a = new Float32Array(p.count);
  for (let i = 0; i < p.count; i++) a[i] = fn(p.getX(i), p.getY(i), p.getZ(i), i);
  g.setAttribute(name, new THREE.BufferAttribute(a, 1));
  return g;
}

/** Set a constant vertex colour (adds `color` attribute). */
export function setColor<T extends THREE.BufferGeometry>(g: T, r: number, gg: number, b: number): T {
  const p = g.getAttribute('position');
  const a = new Float32Array(p.count * 3);
  for (let i = 0; i < p.count; i++) { a[i * 3] = r; a[i * 3 + 1] = gg; a[i * 3 + 2] = b; }
  g.setAttribute('color', new THREE.BufferAttribute(a, 3));
  return g;
}

/** Displace vertices with a function returning an offset (in place). Recomputes normals if asked. */
export function displace<T extends THREE.BufferGeometry>(
  g: T, fn: (v: THREE.Vector3, i: number) => void, recomputeNormals = false,
): T {
  const p = g.getAttribute('position');
  const v = new THREE.Vector3();
  for (let i = 0; i < p.count; i++) {
    v.fromBufferAttribute(p, i);
    fn(v, i);
    p.setXYZ(i, v.x, v.y, v.z);
  }
  p.needsUpdate = true;
  if (recomputeNormals) g.computeVertexNormals();
  return g;
}

/** Triangle count of a geometry or object hierarchy (InstancedMesh counts one instance). */
export function triCount(o: THREE.Object3D | THREE.BufferGeometry): number {
  const geomTris = (g: THREE.BufferGeometry) =>
    (g.getIndex() ? g.getIndex()!.count : g.getAttribute('position').count) / 3;
  if (o instanceof THREE.BufferGeometry) return geomTris(o);
  let n = 0;
  o.traverse((c) => {
    const m = c as THREE.Mesh;
    if (m.isMesh && m.geometry) n += geomTris(m.geometry);
  });
  return n;
}

// ---------------------------------------------------------------------------
// Merging
// ---------------------------------------------------------------------------

/**
 * Robust merge: makes every input indexed, unions attributes (missing `color` → white,
 * other missing attributes → 0), drops groups/morphs. Returns a new geometry; inputs untouched.
 */
export function merge(geoms: THREE.BufferGeometry[]): THREE.BufferGeometry {
  const list = geoms.filter((g) => g && g.getAttribute('position') && g.getAttribute('position').count > 0);
  if (list.length === 0) {
    const e = new THREE.BufferGeometry();
    e.setAttribute('position', new THREE.Float32BufferAttribute([], 3));
    e.setAttribute('normal', new THREE.Float32BufferAttribute([], 3));
    e.setAttribute('uv', new THREE.Float32BufferAttribute([], 2));
    return e;
  }
  const sizes = new Map<string, number>([['position', 3], ['normal', 3], ['uv', 2]]);
  for (const g of list) for (const k of Object.keys(g.attributes)) {
    if (!sizes.has(k)) sizes.set(k, g.getAttribute(k).itemSize);
  }
  const prepared = list.map((src) => {
    const g = new THREE.BufferGeometry();
    const count = src.getAttribute('position').count;
    if (!src.getAttribute('normal')) src.computeVertexNormals();
    for (const [k, size] of sizes) {
      const a = src.getAttribute(k) as THREE.BufferAttribute | undefined;
      if (a && a.itemSize === size && !(a as unknown as THREE.InterleavedBufferAttribute).isInterleavedBufferAttribute) {
        g.setAttribute(k, new THREE.BufferAttribute(new Float32Array(a.array as ArrayLike<number>), size, a.normalized));
      } else if (a) {
        const arr = new Float32Array(count * size);
        for (let i = 0; i < count; i++) for (let c = 0; c < size; c++) arr[i * size + c] = c < a.itemSize ? a.getComponent(i, c) : 0;
        g.setAttribute(k, new THREE.BufferAttribute(arr, size));
      } else {
        const arr = new Float32Array(count * size);
        if (k === 'color') arr.fill(1);
        g.setAttribute(k, new THREE.BufferAttribute(arr, size));
      }
    }
    if (src.getIndex()) g.setIndex(Array.from(src.getIndex()!.array as ArrayLike<number>));
    else {
      const idx = new Array<number>(count);
      for (let i = 0; i < count; i++) idx[i] = i;
      g.setIndex(idx);
    }
    return g;
  });
  const out = mergeGeometries(prepared, false);
  if (!out) throw new Error('geom.merge: mergeGeometries failed');
  out.computeBoundingBox();
  out.computeBoundingSphere();
  return out;
}

// ---------------------------------------------------------------------------
// Part builder
// ---------------------------------------------------------------------------

export interface Part { geometry: THREE.BufferGeometry; material: THREE.Material }

/** Resolve a material by name or pass through an existing material. */
export function mat(m: MaterialName | THREE.Material): THREE.Material {
  return typeof m === 'string' ? getMaterial(m) : m;
}

const _tintCache = new Map<string, THREE.MeshStandardMaterial>();
/** Memoised `getTintedMaterial` (guarantees one shared instance per base+hex). */
export function tinted(base: MaterialName, hex: number): THREE.MeshStandardMaterial {
  const k = base + ':' + hex;
  let m = _tintCache.get(k);
  if (!m) { m = getTintedMaterial(base, hex); _tintCache.set(k, m); }
  return m;
}

/**
 * Collects geometry per material, then merges into one geometry per material.
 *   const pb = new PartBuilder();
 *   pb.add('wood', bevelBox(1, 0.1, 1), xf(box(...), 0, 1, 0));
 *   const parts = pb.build();
 */
export class PartBuilder {
  private map = new Map<THREE.Material, THREE.BufferGeometry[]>();

  add(m: MaterialName | THREE.Material, ...geoms: THREE.BufferGeometry[]): this {
    const k = mat(m);
    let arr = this.map.get(k);
    if (!arr) { arr = []; this.map.set(k, arr); }
    arr.push(...geoms);
    return this;
  }

  /** Apply a transform to everything added so far (in place). */
  transformAll(m: THREE.Matrix4): this {
    for (const arr of this.map.values()) for (const g of arr) g.applyMatrix4(m);
    return this;
  }

  isEmpty(): boolean { return this.map.size === 0; }

  build(): Part[] {
    const out: Part[] = [];
    for (const [material, geoms] of this.map) out.push({ geometry: merge(geoms), material });
    return out;
  }
}

/** Create a group with one Mesh per part (geometries/materials shared, not cloned). */
export function partsToGroup(parts: Part[], castShadow = true, receiveShadow = true): THREE.Group {
  const g = new THREE.Group();
  for (const p of parts) {
    const m = new THREE.Mesh(p.geometry, p.material);
    m.castShadow = castShadow;
    m.receiveShadow = receiveShadow;
    g.add(m);
  }
  return g;
}

/** Create an empty anchor Object3D at a position. */
export function anchor(name: string, x: number, y: number, z: number): THREE.Object3D {
  const o = new THREE.Object3D();
  o.name = name;
  o.position.set(x, y, z);
  return o;
}
