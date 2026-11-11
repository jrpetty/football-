// Low-poly vegetation: faceted conifers, autumn birches, snags, bushes, boulders and logs.
// Same TreeModel contract as models/vegetation.ts (windWeight attribute, base on y=0).
import * as THREE from 'three';
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js';
import type { TreeModel } from './vegetation';
import { getFlatMaterial } from '../render/materials';

function rng(seed: number) { let s = seed >>> 0 || 1; return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296; }; }

/** finalise a part: non-indexed, jittered, flat normals, windWeight from height */
function prep(g: THREE.BufferGeometry, r: () => number, jitter: number, height: number, windBase = 0): THREE.BufferGeometry {
  const ng = g.index ? g.toNonIndexed() : g;
  ng.deleteAttribute('uv');
  const p = ng.attributes.position as THREE.BufferAttribute;
  // jitter shared vertices consistently (hash by rounded position) so faces stay closed
  const off = new Map<string, [number, number, number]>();
  for (let i = 0; i < p.count; i++) {
    const k = `${p.getX(i).toFixed(3)},${p.getY(i).toFixed(3)},${p.getZ(i).toFixed(3)}`;
    let o = off.get(k);
    if (!o) { o = [(r() - 0.5) * jitter, (r() - 0.5) * jitter * 0.6, (r() - 0.5) * jitter]; off.set(k, o); }
    if (p.getY(i) > 0.05) p.setXYZ(i, p.getX(i) + o[0], p.getY(i) + o[1], p.getZ(i) + o[2]);
  }
  ng.computeVertexNormals();
  const w = new Float32Array(p.count);
  for (let i = 0; i < p.count; i++) w[i] = Math.min(1, windBase + Math.max(0, p.getY(i)) / height);
  ng.setAttribute('windWeight', new THREE.BufferAttribute(w, 1));
  return ng;
}
function merge(list: THREE.BufferGeometry[]) { const g = mergeGeometries(list, false)!; g.computeBoundingSphere(); return g; }

function conifer(seed: number, h: number, tiers: number, radius: number, leaf: number, trunk: number, slim: number): TreeModel {
  const r = rng(seed);
  const trunkR = 0.18 + h * 0.012;
  const tg = new THREE.CylinderGeometry(trunkR * 0.55, trunkR, h * 0.55, 6, 1); tg.translate(0, h * 0.275, 0);
  const foliage: THREE.BufferGeometry[] = [];
  const start = h * (0.18 + r() * 0.06);
  for (let i = 0; i < tiers; i++) {
    const t = i / tiers;
    const rad = radius * (1 - t * 0.78) * (0.9 + r() * 0.2);
    const ch = (h - start) / tiers * (1.9 - slim * 0.4);
    const y = start + (h - start) * t * 0.92;
    const c = new THREE.ConeGeometry(rad, ch, 7 + (i % 2), 1);
    c.rotateY(r() * Math.PI); c.translate((r() - 0.5) * 0.3, y + ch / 2, (r() - 0.5) * 0.3);
    foliage.push(prep(c, r, rad * 0.18, h, 0.1));
  }
  const top = new THREE.ConeGeometry(radius * 0.18, h * 0.16, 6, 1); top.translate(0, h * 0.94, 0); foliage.push(prep(top, r, 0.1, h));
  return {
    parts: [{ geometry: prep(tg, r, 0.04, h), material: getFlatMaterial(trunk, 0.95) }, { geometry: merge(foliage), material: getFlatMaterial(leaf, 0.9) }],
    height: h, trunkRadius: trunkR,
  };
}

function birch(seed: number, h: number, leaf: number): TreeModel {
  const r = rng(seed);
  const parts: THREE.BufferGeometry[] = [];
  const tg = new THREE.CylinderGeometry(0.13, 0.24, h * 0.8, 6, 2); tg.translate(0, h * 0.4, 0); parts.push(prep(tg, r, 0.05, h));
  // a couple of forking branches
  for (let i = 0; i < 3; i++) {
    const b = new THREE.CylinderGeometry(0.04, 0.07, h * 0.3, 5, 1); b.translate(0, h * 0.15, 0);
    b.rotateZ((r() - 0.5) * 1.2); b.rotateY(r() * 6.28); b.translate(0, h * (0.45 + i * 0.1), 0);
    parts.push(prep(b, r, 0.02, h));
  }
  const crowns: THREE.BufferGeometry[] = [];
  const n = 4 + Math.floor(r() * 3);
  for (let i = 0; i < n; i++) {
    const s = h * (0.13 + r() * 0.08);
    const g = new THREE.IcosahedronGeometry(s, 0); g.scale(1, 0.85, 1);
    const a = r() * 6.28, d = i === 0 ? 0 : h * (0.08 + r() * 0.12);
    g.translate(Math.cos(a) * d, h * (0.55 + r() * 0.3), Math.sin(a) * d);
    crowns.push(prep(g, r, s * 0.25, h, 0.2));
  }
  return { parts: [{ geometry: merge(parts), material: getFlatMaterial(0xd6d1c4, 0.9) }, { geometry: merge(crowns), material: getFlatMaterial(leaf, 0.85) }], height: h, trunkRadius: 0.18 };
}

function snag(seed: number, h: number): TreeModel {
  const r = rng(seed);
  const parts: THREE.BufferGeometry[] = [];
  const tg = new THREE.CylinderGeometry(0.08, 0.26, h, 6, 3); tg.translate(0, h / 2, 0); parts.push(prep(tg, r, 0.08, h));
  for (let i = 0; i < 6; i++) {
    const L = h * (0.15 + r() * 0.2);
    const b = new THREE.CylinderGeometry(0.02, 0.06, L, 4, 1); b.translate(0, L / 2, 0);
    b.rotateZ(0.7 + r() * 0.8); b.rotateY(r() * 6.28); b.translate(0, h * (0.35 + r() * 0.55), 0);
    parts.push(prep(b, r, 0.03, h));
  }
  return { parts: [{ geometry: merge(parts), material: getFlatMaterial(0x6a5a4a, 0.95) }], height: h, trunkRadius: 0.26 };
}

function bush(seed: number, s: number, col: number): TreeModel {
  const r = rng(seed); const g: THREE.BufferGeometry[] = [];
  for (let i = 0; i < 3 + Math.floor(r() * 2); i++) {
    const rr = s * (0.35 + r() * 0.25);
    const b = new THREE.IcosahedronGeometry(rr, 0); b.scale(1, 0.75, 1);
    b.translate((r() - 0.5) * s * 0.7, rr * 0.6, (r() - 0.5) * s * 0.7);
    g.push(prep(b, r, rr * 0.3, s * 1.2, 0.2));
  }
  return { parts: [{ geometry: merge(g), material: getFlatMaterial(col, 0.9) }], height: s, trunkRadius: 0 };
}

function rock(seed: number, s: number, flat: number, col: number): TreeModel {
  const r = rng(seed);
  const g = new THREE.IcosahedronGeometry(s, 1); g.scale(1 + r() * 0.4, flat, 0.9 + r() * 0.3); g.translate(0, s * flat * 0.45, 0);
  const pg = prep(g, r, s * 0.35, 1e9);
  // moss tint on top faces via a second material: split by normal
  const top: THREE.BufferGeometry[] = [], side: THREE.BufferGeometry[] = [];
  const pos = pg.attributes.position as THREE.BufferAttribute, nor = pg.attributes.normal as THREE.BufferAttribute;
  const tA: number[] = [], sA: number[] = [], tN: number[] = [], sN: number[] = [];
  for (let i = 0; i < pos.count; i += 3) {
    const ny = (nor.getY(i) + nor.getY(i + 1) + nor.getY(i + 2)) / 3;
    const dst = ny > 0.72 ? tA : sA, dn = ny > 0.72 ? tN : sN;
    for (let k = 0; k < 3; k++) { dst.push(pos.getX(i + k), pos.getY(i + k), pos.getZ(i + k)); dn.push(nor.getX(i + k), nor.getY(i + k), nor.getZ(i + k)); }
  }
  const mk = (a: number[], n: number[]) => { const b = new THREE.BufferGeometry(); b.setAttribute('position', new THREE.Float32BufferAttribute(a, 3)); b.setAttribute('normal', new THREE.Float32BufferAttribute(n, 3)); b.setAttribute('windWeight', new THREE.Float32BufferAttribute(new Float32Array(a.length / 3), 1)); b.computeBoundingSphere(); return b; };
  void top; void side;
  const parts = [{ geometry: mk(sA, sN), material: getFlatMaterial(col, 0.9) }];
  if (tA.length) parts.push({ geometry: mk(tA, tN), material: getFlatMaterial(0x5e6a3a, 0.95) });
  return { parts, height: s * flat, trunkRadius: s * 0.8 };
}

function log(seed: number, L: number): TreeModel {
  const r = rng(seed);
  const g = new THREE.CylinderGeometry(0.28, 0.32, L, 7, 2); g.rotateZ(Math.PI / 2); g.translate(0, 0.28, 0);
  return { parts: [{ geometry: prep(g, r, 0.06, 1e9), material: getFlatMaterial(0x5a4434, 0.95) }], height: 0.6, trunkRadius: 0 };
}

type LPSet = Record<'pines' | 'firs' | 'birches' | 'dead' | 'bushes' | 'rocks' | 'logs', TreeModel[]>;
let cached: LPSet | null = null;
export function buildLowPolyTreeModels(): LPSet {
  if (cached) return cached;
  cached = {
    pines: [conifer(11, 17, 5, 3.2, 0x3e5a3c, 0x5a4434, 0), conifer(12, 22, 6, 3.8, 0x37533a, 0x5a4434, 0.2), conifer(13, 26, 7, 4.2, 0x41603f, 0x4f3c2e, 0.1)],
    firs: [conifer(21, 15, 6, 2.6, 0x2f4a36, 0x4f3c2e, 1), conifer(22, 19, 7, 2.9, 0x2c4433, 0x4f3c2e, 1), conifer(23, 23, 8, 3.2, 0x324f3a, 0x4a382a, 1)],
    birches: [birch(31, 11, 0xc89a3a), birch(32, 13, 0xa8a440), birch(33, 15, 0xb8642e)],
    dead: [snag(41, 12), snag(42, 7.5)],
    bushes: [bush(51, 1.3, 0x55703a), bush(52, 1.7, 0x4a6436), bush(53, 1.1, 0x8a6a34)],
    rocks: [rock(61, 0.8, 0.7, 0x85847c), rock(62, 1.6, 0.6, 0x7d7b74), rock(63, 2.6, 0.5, 0x8c8a82), rock(64, 3.6, 0.55, 0x78766f)],
    logs: [log(71, 4), log(72, 6)],
  };
  return cached;
}

/** Portable gasoline generator (survival station), ~1.0 x 0.7 x 0.6 m. */
export function buildGeneratorModel(): THREE.Group {
  const g = new THREE.Group();
  const add = (geo: THREE.BufferGeometry, hex: number, x: number, y: number, z: number, rough = 0.7, metal = 0.2) => { const m = new THREE.Mesh(geo, getFlatMaterial(hex, rough, metal)); m.position.set(x, y, z); m.castShadow = true; m.receiveShadow = true; g.add(m); return m; };
  // tubular frame
  for (const sx of [-0.45, 0.45]) for (const sz of [-0.28, 0.28]) add(new THREE.CylinderGeometry(0.025, 0.025, 0.62, 6), 0x2a2c2e, sx, 0.31, sz);
  for (const sz of [-0.28, 0.28]) { const r = add(new THREE.CylinderGeometry(0.025, 0.025, 0.92, 6), 0x2a2c2e, 0, 0.62, sz); r.rotation.z = Math.PI / 2; }
  add(new THREE.BoxGeometry(0.62, 0.34, 0.42), 0xc8a020, -0.08, 0.3, 0);       // engine block (yellow)
  add(new THREE.BoxGeometry(0.3, 0.16, 0.38), 0xb89018, 0.25, 0.52, 0);        // fuel tank
  add(new THREE.CylinderGeometry(0.05, 0.05, 0.05, 8), 0x1a1a1a, 0.25, 0.62, 0); // cap
  add(new THREE.CylinderGeometry(0.06, 0.06, 0.32, 8), 0x3a3a3a, -0.4, 0.26, 0.12).rotation.x = Math.PI / 2; // muffler
  add(new THREE.BoxGeometry(0.12, 0.12, 0.05), 0x222222, 0.3, 0.3, 0.23);      // panel
  const lamp = add(new THREE.SphereGeometry(0.02, 6, 4), 0x30ff60, 0.3, 0.33, 0.26, 0.4, 0);
  lamp.name = 'genLamp';
  for (const sz of [-0.3, 0.3]) { const w = add(new THREE.CylinderGeometry(0.08, 0.08, 0.06, 8), 0x151515, -0.42, 0.08, sz, 0.9, 0); w.rotation.x = Math.PI / 2; }
  return g;
}
