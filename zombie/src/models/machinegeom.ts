// Geometry kit for factory machines & build pieces.
// - Primitive builders whose UVs are in metres (1 UV unit per metre) so tiling
//   textures from render/materials keep a consistent texel density.
// - PB ("part builder"): accumulates primitives with transforms, then merges
//   everything that shares a material into ONE mesh (few draw calls per model).
//   Child pivots (sub()) build their own merged meshes so they can be animated.
import * as THREE from 'three';
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js';

export type V3 = [number, number, number];
export type Axis = 'x' | 'y' | 'z';

const TAU = Math.PI * 2;

// ---------------------------------------------------------------------------
// Primitive geometry (metre UVs)
// ---------------------------------------------------------------------------

export function boxGeo(w: number, h: number, d: number): THREE.BufferGeometry {
  const g = new THREE.BoxGeometry(w, h, d);
  const uv = g.attributes.uv as THREE.BufferAttribute;
  const dims: [number, number][] = [[d, h], [d, h], [w, d], [w, d], [w, h], [w, h]];
  for (let f = 0; f < 6; f++) {
    for (let i = 0; i < 4; i++) {
      const k = f * 4 + i;
      uv.setXY(k, uv.getX(k) * dims[f][0], uv.getY(k) * dims[f][1]);
    }
  }
  return g;
}

/** Cylinder / cone along Y, centred. Side UV: u = arc length, v = height; caps: planar x/z. */
export function cylGeo(rTop: number, rBot: number, h: number, seg = 12, open = false, thetaStart = 0, thetaLen = TAU): THREE.BufferGeometry {
  const g = new THREE.CylinderGeometry(rTop, rBot, h, seg, 1, open, thetaStart, thetaLen);
  const uv = g.attributes.uv as THREE.BufferAttribute;
  const n = g.attributes.normal as THREE.BufferAttribute;
  const p = g.attributes.position as THREE.BufferAttribute;
  const circ = thetaLen * Math.max(rTop, rBot);
  for (let i = 0; i < uv.count; i++) {
    if (Math.abs(n.getY(i)) > 0.999) {
      uv.setXY(i, p.getX(i), p.getZ(i));
    } else {
      uv.setXY(i, uv.getX(i) * circ, uv.getY(i) * h);
    }
  }
  return g;
}

export function sphereGeo(r: number, ws = 10, hs = 8, phiLen = TAU, thetaLen = Math.PI): THREE.BufferGeometry {
  const g = new THREE.SphereGeometry(r, ws, hs, 0, phiLen, 0, thetaLen);
  scaleUV(g, phiLen * r, thetaLen * r);
  return g;
}

export function torusGeo(R: number, r: number, radial = 6, tubular = 16, arc = TAU): THREE.BufferGeometry {
  const g = new THREE.TorusGeometry(R, r, radial, tubular, arc);
  scaleUV(g, arc * R, TAU * r);
  return g;
}

export function tubeGeo(curve: THREE.Curve<THREE.Vector3>, segs: number, r: number, radial = 6): THREE.BufferGeometry {
  const g = new THREE.TubeGeometry(curve, segs, r, radial, false);
  scaleUV(g, curve.getLength(), TAU * r);
  return g;
}

export function latheGeo(pts: [number, number][], seg = 12): THREE.BufferGeometry {
  const v = pts.map((p) => new THREE.Vector2(p[0], p[1]));
  const g = new THREE.LatheGeometry(v, seg);
  let maxR = 0;
  let len = 0;
  for (let i = 0; i < v.length; i++) {
    maxR = Math.max(maxR, v[i].x);
    if (i > 0) len += v[i].distanceTo(v[i - 1]);
  }
  scaleUV(g, TAU * maxR, len);
  return g;
}

/** Extrude a 2D polygon (x,y) along Z, centred on z. World-space UVs (metres). */
export function extrudeGeo(pts: [number, number][], depth: number, bevel = 0, holes: [number, number][][] = []): THREE.BufferGeometry {
  const s = new THREE.Shape(pts.map((p) => new THREE.Vector2(p[0], p[1])));
  for (const h of holes) s.holes.push(new THREE.Path(h.map((p) => new THREE.Vector2(p[0], p[1]))));
  const g = new THREE.ExtrudeGeometry(s, {
    depth: Math.max(0.001, depth - bevel * 2),
    bevelEnabled: bevel > 0,
    bevelThickness: bevel,
    bevelSize: bevel,
    bevelOffset: 0,
    bevelSegments: 1,
    curveSegments: 4,
  });
  g.translate(0, 0, -(depth - bevel * 2) / 2);
  return g;
}

/** Box with chamfered edges (chunky industrial panels). */
export function cboxGeo(w: number, h: number, d: number, c = 0.05): THREE.BufferGeometry {
  c = Math.min(c, w / 3, h / 3, d / 3);
  const hw = w / 2 - c;
  const hh = h / 2 - c;
  return extrudeGeo([[-hw, -hh], [hw, -hh], [hw, hh], [-hw, hh]], d, c);
}

/** Square frustum (hopper), axis Y, centred. top/bottom are full widths. */
export function hopperGeo(topW: number, botW: number, h: number, open = false): THREE.BufferGeometry {
  const g = cylGeo(topW / Math.SQRT2, botW / Math.SQRT2, h, 4, open);
  g.rotateY(Math.PI / 4);
  return g;
}

/** Simple flat quad facing +Z, centred, metre UVs. */
export function quadGeo(w: number, h: number): THREE.BufferGeometry {
  const g = new THREE.PlaneGeometry(w, h);
  scaleUV(g, w, h);
  return g;
}

export function scaleUV(g: THREE.BufferGeometry, su: number, sv: number): void {
  const uv = g.attributes.uv as THREE.BufferAttribute | undefined;
  if (!uv) return;
  for (let i = 0; i < uv.count; i++) uv.setXY(i, uv.getX(i) * su, uv.getY(i) * sv);
}

// ---------------------------------------------------------------------------
// Deterministic RNG
// ---------------------------------------------------------------------------
export function rng(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// ---------------------------------------------------------------------------
// Part builder
// ---------------------------------------------------------------------------
interface Item {
  geo: THREE.BufferGeometry;
  mat: THREE.Material;
  m: THREE.Matrix4;
}

const _q = new THREE.Quaternion();
const _e = new THREE.Euler();
const _v = new THREE.Vector3();
const _s = new THREE.Vector3();
const UP = new THREE.Vector3(0, 1, 0);

function localMatrix(x: number, y: number, z: number, rx: number, ry: number, rz: number, sx = 1, sy = 1, sz = 1): THREE.Matrix4 {
  _e.set(rx, ry, rz);
  _q.setFromEuler(_e);
  return new THREE.Matrix4().compose(_v.set(x, y, z), _q, _s.set(sx, sy, sz));
}

export class PB {
  readonly name: string;
  private items: Item[] = [];
  private kids: { pb: PB; m: THREE.Matrix4 }[] = [];
  private extras: { obj: THREE.Object3D; m: THREE.Matrix4 }[] = [];
  private stack: THREE.Matrix4[] = [new THREE.Matrix4()];
  shadows = true;

  constructor(name = '') {
    this.name = name;
  }

  get M(): THREE.Matrix4 {
    return this.stack[this.stack.length - 1];
  }

  push(x = 0, y = 0, z = 0, rx = 0, ry = 0, rz = 0, s = 1): this {
    this.stack.push(this.M.clone().multiply(localMatrix(x, y, z, rx, ry, rz, s, s, s)));
    return this;
  }

  pop(): this {
    if (this.stack.length > 1) this.stack.pop();
    return this;
  }

  /** Run fn inside a local transform. */
  at(x: number, y: number, z: number, ry: number, fn: () => void, rx = 0, rz = 0): this {
    this.push(x, y, z, rx, ry, rz);
    fn();
    this.pop();
    return this;
  }

  add(geo: THREE.BufferGeometry, mat: THREE.Material, x = 0, y = 0, z = 0, rx = 0, ry = 0, rz = 0, sx = 1, sy = 1, sz = 1): this {
    this.items.push({ geo, mat, m: this.M.clone().multiply(localMatrix(x, y, z, rx, ry, rz, sx, sy, sz)) });
    return this;
  }

  /** Box centred at (x,y,z). */
  box(w: number, h: number, d: number, mat: THREE.Material, x = 0, y = 0, z = 0, rx = 0, ry = 0, rz = 0): this {
    return this.add(boxGeo(w, h, d), mat, x, y, z, rx, ry, rz);
  }

  /** Box with its base at y0. */
  blk(w: number, h: number, d: number, mat: THREE.Material, x: number, y0: number, z: number, ry = 0): this {
    return this.add(boxGeo(w, h, d), mat, x, y0 + h / 2, z, 0, ry, 0);
  }

  /** Chamfered box with base at y0. */
  cblk(w: number, h: number, d: number, mat: THREE.Material, x: number, y0: number, z: number, c = 0.06, ry = 0): this {
    return this.add(cboxGeo(w, h, d, c), mat, x, y0 + h / 2, z, 0, ry, 0);
  }

  /** Cylinder centred at (x,y,z) along axis. */
  cyl(r: number, h: number, mat: THREE.Material, x = 0, y = 0, z = 0, axis: Axis = 'y', seg = 12, rTop?: number, open = false): this {
    const g = cylGeo(rTop ?? r, r, h, seg, open);
    const rx = axis === 'z' ? Math.PI / 2 : 0;
    const rz = axis === 'x' ? -Math.PI / 2 : 0;
    return this.add(g, mat, x, y, z, rx, 0, rz);
  }

  /** Round strut between two points. */
  rod(a: V3, b: V3, r: number, mat: THREE.Material, seg = 6, open = false): this {
    const va = new THREE.Vector3(...a);
    const vb = new THREE.Vector3(...b);
    const len = va.distanceTo(vb);
    if (len < 1e-5) return this;
    const g = cylGeo(r, r, len, seg, open);
    return this.oriented(g, mat, va, vb);
  }

  /** Square-section beam between two points. */
  beam(a: V3, b: V3, w: number, mat: THREE.Material, d = w): this {
    const va = new THREE.Vector3(...a);
    const vb = new THREE.Vector3(...b);
    const len = va.distanceTo(vb);
    if (len < 1e-5) return this;
    return this.oriented(boxGeo(w, len, d), mat, va, vb);
  }

  /** Add a Y-axis geometry stretched/oriented so its axis runs from a to b (centred between). */
  oriented(g: THREE.BufferGeometry, mat: THREE.Material, va: THREE.Vector3, vb: THREE.Vector3): this {
    const dir = vb.clone().sub(va).normalize();
    const q = new THREE.Quaternion().setFromUnitVectors(UP, dir);
    const mid = va.clone().add(vb).multiplyScalar(0.5);
    const m = new THREE.Matrix4().compose(mid, q, new THREE.Vector3(1, 1, 1));
    this.items.push({ geo: g, mat, m: this.M.clone().multiply(m) });
    return this;
  }

  /** Pipe through a polyline, with joint spheres at the corners. */
  pipe(pts: V3[], r: number, mat: THREE.Material, seg = 8, joints = true): this {
    for (let i = 0; i < pts.length - 1; i++) this.rod(pts[i], pts[i + 1], r, mat, seg, true);
    if (joints) for (let i = 1; i < pts.length - 1; i++) this.add(sphereGeo(r * 1.15, seg, 5), mat, ...pts[i]);
    return this;
  }

  /** Sagging cable between two points (thin tube). */
  cable(a: V3, b: V3, sag: number, r: number, mat: THREE.Material, segs = 8): this {
    const va = new THREE.Vector3(...a);
    const vb = new THREE.Vector3(...b);
    const mid = va.clone().add(vb).multiplyScalar(0.5);
    mid.y -= sag;
    const curve = new THREE.QuadraticBezierCurve3(va, mid, vb);
    return this.add(tubeGeo(curve, segs, r, 4), mat);
  }

  /** A separate (non-merged) named mesh, transformed by the current matrix. */
  mesh(name: string, geo: THREE.BufferGeometry, mat: THREE.Material, x = 0, y = 0, z = 0, rx = 0, ry = 0, rz = 0, shadow = false): THREE.Mesh {
    const mesh = new THREE.Mesh(geo, mat);
    mesh.name = name;
    mesh.castShadow = shadow;
    mesh.receiveShadow = shadow;
    this.extras.push({ obj: mesh, m: this.M.clone().multiply(localMatrix(x, y, z, rx, ry, rz)) });
    return mesh;
  }

  /** Empty named Object3D (attachment anchor). */
  anchor(name: string, x = 0, y = 0, z = 0, rx = 0, ry = 0, rz = 0): THREE.Object3D {
    const o = new THREE.Object3D();
    o.name = name;
    this.extras.push({ obj: o, m: this.M.clone().multiply(localMatrix(x, y, z, rx, ry, rz)) });
    return o;
  }

  /** Child pivot builder (named group) - for animated parts. */
  sub(name: string, x = 0, y = 0, z = 0, rx = 0, ry = 0, rz = 0): PB {
    const pb = new PB(name);
    pb.shadows = this.shadows;
    this.kids.push({ pb, m: this.M.clone().multiply(localMatrix(x, y, z, rx, ry, rz)) });
    return pb;
  }

  build(): THREE.Group {
    const g = new THREE.Group();
    g.name = this.name;
    const buckets = new Map<THREE.Material, THREE.BufferGeometry[]>();
    const sources = new Set<THREE.BufferGeometry>();
    for (const it of this.items) {
      sources.add(it.geo);
      const c = prep(it.geo, it.m);
      let list = buckets.get(it.mat);
      if (!list) {
        list = [];
        buckets.set(it.mat, list);
      }
      list.push(c);
    }
    for (const [mat, list] of buckets) {
      const anyNonIndexed = list.some((x) => x.index === null);
      const ready = anyNonIndexed ? list.map((x) => (x.index ? x.toNonIndexed() : x)) : list;
      const merged = ready.length === 1 ? ready[0] : mergeGeometries(ready, false);
      if (!merged) continue;
      merged.computeBoundingSphere();
      const mesh = new THREE.Mesh(merged, mat);
      mesh.castShadow = this.shadows;
      mesh.receiveShadow = this.shadows;
      g.add(mesh);
      for (const x of ready) if (x !== merged) x.dispose();
    }
    for (const s of sources) s.dispose();
    for (const k of this.kids) {
      const child = k.pb.build();
      k.m.decompose(child.position, child.quaternion, child.scale);
      g.add(child);
    }
    for (const e of this.extras) {
      e.m.decompose(e.obj.position, e.obj.quaternion, e.obj.scale);
      g.add(e.obj);
    }
    return g;
  }
}

function prep(src: THREE.BufferGeometry, m: THREE.Matrix4): THREE.BufferGeometry {
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', src.attributes.position.clone());
  if (src.attributes.normal) g.setAttribute('normal', src.attributes.normal.clone());
  else {
    g.setAttribute('normal', new THREE.BufferAttribute(new Float32Array(src.attributes.position.count * 3), 3));
  }
  if (src.attributes.uv) g.setAttribute('uv', src.attributes.uv.clone());
  else g.setAttribute('uv', new THREE.BufferAttribute(new Float32Array(src.attributes.position.count * 2), 2));
  if (src.index) g.setIndex(src.index.clone());
  g.applyMatrix4(m);
  if (!src.attributes.normal) g.computeVertexNormals();
  return g;
}

/** Count triangles in an object hierarchy. */
export function countTris(o: THREE.Object3D): number {
  let n = 0;
  o.traverse((c) => {
    const m = c as THREE.Mesh;
    if (m.isMesh && m.geometry) {
      const g = m.geometry;
      n += g.index ? g.index.count / 3 : g.attributes.position.count / 3;
    }
  });
  return n;
}

/** Build a single merged geometry from primitives added to a temporary builder (all with `m`). */
export function mergedGeo(fn: (pb: PB, m: THREE.Material) => void): THREE.BufferGeometry {
  const pb = new PB();
  const m = new THREE.MeshBasicMaterial();
  fn(pb, m);
  const g = pb.build();
  const mesh = g.children.find((c) => (c as THREE.Mesh).isMesh) as THREE.Mesh | undefined;
  m.dispose();
  return mesh ? mesh.geometry : new THREE.BufferGeometry();
}

/**
 * Crossed alpha-cut "cards" (for leaf-cluster textures whose twig enters at v=0).
 * n cards of w x h, base at (x,y,z), rotated about Y starting at `rot`, tilted outward by `tilt`.
 */
export function cardPlant(pb: PB, mat: THREE.Material, x: number, y: number, z: number, w: number, h: number, n: number, rot = 0, tilt = 0.15): void {
  for (let i = 0; i < n; i++) {
    const g = new THREE.PlaneGeometry(w, h);
    g.translate(0, h / 2, 0);
    const a = rot + (i / n) * Math.PI;
    pb.push(x, y, z, 0, a, 0);
    pb.add(g, mat, 0, 0, 0, i % 2 ? tilt : -tilt, 0, 0);
    pb.pop();
  }
}
