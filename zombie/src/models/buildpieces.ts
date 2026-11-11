// REKINDLE - player-built base pieces (Carpentry). Scrappy survivor-built style:
// uneven planks, nails, cross braces; corrugated metal on welded frames; cast concrete panels.
// Each piece is built once into a template (merged per material) and cloned per call.
import * as THREE from 'three';
import { getMaterial, getTintedMaterial, type MaterialName } from '../render/materials';
import { PB, cylGeo, sphereGeo, torusGeo, extrudeGeo, cboxGeo, cardPlant, rng, type V3 } from './machinegeom';

export type BuildPieceId =
  | 'wood_foundation' | 'wood_wall' | 'wood_wall_window' | 'wood_wall_door' | 'wood_door' | 'wood_stairs' | 'wood_roof'
  | 'metal_foundation' | 'metal_wall' | 'metal_wall_door' | 'metal_door' | 'concrete_wall'
  | 'barricade_window' | 'barricade_door' | 'gate' | 'watchtower' | 'farm_plot'
  | 'crop_stage0' | 'crop_stage1' | 'crop_stage2' | 'crop_stage3';

export const BUILD_PIECE_IDS: BuildPieceId[] = [
  'wood_foundation', 'wood_wall', 'wood_wall_window', 'wood_wall_door', 'wood_door', 'wood_stairs', 'wood_roof',
  'metal_foundation', 'metal_wall', 'metal_wall_door', 'metal_door', 'concrete_wall',
  'barricade_window', 'barricade_door', 'gate', 'watchtower', 'farm_plot',
  'crop_stage0', 'crop_stage1', 'crop_stage2', 'crop_stage3',
];

const M = (n: MaterialName): THREE.MeshStandardMaterial => getMaterial(n);
const tintCache = new Map<string, THREE.MeshStandardMaterial>();
function tint(base: MaterialName, hex: number): THREE.MeshStandardMaterial {
  const key = base + ':' + hex;
  let m = tintCache.get(key);
  if (!m) {
    m = getTintedMaterial(base, hex);
    tintCache.set(key, m);
  }
  return m;
}
const woodTones = (): THREE.MeshStandardMaterial[] => [M('wood'), tint('wood', 0xd6c4a6), tint('wood', 0xb39c80), M('woodDark'), tint('wood', 0xe8dcc8)];
const woodPick = (rnd: () => number): THREE.MeshStandardMaterial => {
  const t = woodTones();
  return t[Math.floor(rnd() * t.length) % t.length];
};

function nail(b: PB, x: number, y: number, z: number): void {
  b.box(0.018, 0.018, 0.012, M('metalDark'), x, y, z);
}

/** Uneven plank (box) with small random tilt/size jitter. */
function plank(b: PB, rnd: () => number, w: number, h: number, d: number, x: number, y: number, z: number, rz = 0, mat?: THREE.Material): void {
  b.box(w * (0.985 + rnd() * 0.015), h * (0.93 + rnd() * 0.07), d, mat ?? woodPick(rnd), x + (rnd() - 0.5) * 0.01, y + (rnd() - 0.5) * 0.008, z, 0, 0, rz + (rnd() - 0.5) * 0.014);
}

type Opening = [number, number, number, number] | null; // x0, x1, y0, y1

/** Horizontal planks on one face of a 2 m wall, skipping an opening. */
function planking(b: PB, rnd: () => number, z: number, side: number, open: Opening): void {
  const bands: { ya: number; yb: number; mid: boolean }[] = open
    ? [{ ya: 0.1, yb: open[2], mid: false }, { ya: Math.max(0.1, open[2]), yb: open[3], mid: true }, { ya: open[3], yb: 2.9, mid: false }]
    : [{ ya: 0.1, yb: 2.9, mid: false }];
  for (const band of bands) {
    const span = band.yb - band.ya;
    if (span < 0.04) continue;
    const n = Math.max(1, Math.round(span / 0.27));
    const h = span / n;
    for (let i = 0; i < n; i++) {
      const yc = band.ya + (i + 0.5) * h;
      const segs: [number, number][] = band.mid && open ? [[-1, open[0]], [open[1], 1]] : [[-1, 1]];
      for (const [xa, xb] of segs) {
        if (xb - xa < 0.05) continue;
        plank(b, rnd, xb - xa - 0.01, h - 0.014, 0.035, (xa + xb) / 2, yc, z);
        if (xb - xa > 0.3) {
          nail(b, xa + 0.08, yc, z + side * 0.02);
          nail(b, xb - 0.08, yc, z + side * 0.02);
        }
      }
    }
  }
}

function woodWall(b: PB, seed: number, open: Opening, door: boolean): void {
  const rnd = rng(seed);
  const dark = M('woodDark');
  for (const sx of [-1, 1]) b.blk(0.16, 3.0, 0.13, dark, sx * 0.92, 0, 0);
  b.box(2.0, 0.12, 0.13, dark, 0, 2.94, 0);
  if (door && open) {
    b.box(1 + open[0], 0.12, 0.13, dark, (-1 + open[0]) / 2, 0.06, 0);
    b.box(1 - open[1], 0.12, 0.13, dark, (1 + open[1]) / 2, 0.06, 0);
  } else b.box(2.0, 0.12, 0.13, dark, 0, 0.06, 0);
  if (open) {
    const [x0, x1, y0, y1] = open;
    for (const x of [x0 - 0.05, x1 + 0.05]) b.blk(0.1, 2.76, 0.12, dark, x, 0.12, 0);
    b.box(x1 - x0, 0.14, 0.12, dark, (x0 + x1) / 2, y1 + 0.07, 0);
    if (y0 > 0.2) b.box(x1 - x0, 0.1, 0.12, dark, (x0 + x1) / 2, y0 - 0.05, 0);
    // face trims
    for (const s of [-1, 1]) {
      const z = s * 0.11;
      b.box(x1 - x0 + 0.24, 0.1, 0.03, M('wood'), (x0 + x1) / 2, y1 + 0.05, z, 0, 0, (rnd() - 0.5) * 0.03);
      b.box(0.1, y1 - Math.max(y0, 0), 0.03, M('wood'), x0 - 0.05, (y1 + Math.max(y0, 0)) / 2, z);
      b.box(0.1, y1 - Math.max(y0, 0), 0.03, M('wood'), x1 + 0.05, (y1 + Math.max(y0, 0)) / 2, z);
      if (y0 > 0.2) b.box(x1 - x0 + 0.3, 0.07, 0.06, M('wood'), (x0 + x1) / 2, y0 - 0.035, s * 0.12);
    }
  } else {
    b.blk(0.1, 2.76, 0.12, dark, 0, 0.12, 0);
  }
  planking(b, rnd, 0.0825, 1, open);
  planking(b, rnd, -0.0825, -1, open);
  // diagonal brace(s) on the back face
  const ang = (x0: number, y0: number, x1: number, y1: number): void => {
    const len = Math.hypot(x1 - x0, y1 - y0);
    b.box(0.12, len, 0.03, dark, (x0 + x1) / 2, (y0 + y1) / 2, -0.115, 0, 0, -Math.atan2(x1 - x0, y1 - y0));
    nail(b, x0 + 0.04, y0 + 0.1, -0.132);
    nail(b, x1 - 0.04, y1 - 0.1, -0.132);
  };
  if (!open) ang(-0.85, 0.2, 0.85, 2.8);
  else {
    ang(-0.88, 0.2, open[0] - 0.12, Math.min(2.8, open[3] - 0.1));
    ang(open[1] + 0.12, 0.2, 0.88, Math.min(2.8, open[3] - 0.1));
  }
}

function plankFoundation(b: PB, seed: number): void {
  const rnd = rng(seed);
  const dark = M('woodDark');
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.add(cylGeo(0.12, 0.13, 0.22, 8), M('bark'), sx * 0.85, 0.11, sz * 0.85, 0, rnd() * 3, 0);
  for (const s of [-1, 1]) {
    b.box(2.0, 0.2, 0.1, dark, 0, 0.15, s * 0.95);
    b.box(0.1, 0.2, 1.8, dark, s * 0.95, 0.15, 0);
  }
  for (const x of [-0.48, 0, 0.48]) b.box(0.08, 0.16, 1.8, dark, x, 0.17, 0);
  const n = 9;
  const pw = 2 / n;
  for (let i = 0; i < n; i++) {
    const z = -1 + (i + 0.5) * pw;
    const len = 2.0 - rnd() * 0.04;
    b.box(len, 0.05, pw - 0.012, woodPick(rnd), (rnd() - 0.5) * 0.03, 0.275 + (rnd() - 0.5) * 0.006, z, (rnd() - 0.5) * 0.01, (rnd() - 0.5) * 0.01, 0);
    for (const x of [-0.95, -0.48, 0.48, 0.95]) if (rnd() > 0.25) nail(b, x, 0.302, z + (rnd() - 0.5) * 0.06);
  }
  // rim facing boards
  for (const s of [-1, 1]) {
    plank(b, rnd, 2.0, 0.12, 0.025, 0, 0.19, s * 1.0 - s * 0.012);
    b.push(0, 0, 0, 0, Math.PI / 2, 0);
    plank(b, rnd, 2.0, 0.12, 0.025, 0, 0.19, s * 1.0 - s * 0.012);
    b.pop();
  }
}

function corrugated(b: PB, w: number, h: number, x: number, y: number, z: number, side: number, mat: THREE.Material): void {
  b.box(w, h, 0.01, mat, x, y, z);
  const n = Math.floor(w / 0.15);
  for (let i = 0; i <= n; i++) b.box(0.035, h, 0.02, mat, x - w / 2 + (i * w) / n, y, z + side * 0.012);
}

function metalWall(b: PB, seed: number, open: Opening): void {
  const rnd = rng(seed);
  const frame = M('metalDark');
  const sheetA = M('roofMetal');
  const sheetB = tint('roofMetal', 0x8f9a86);
  const rust = M('metalRust');
  for (const sx of [-1, 1]) b.blk(0.12, 3.0, 0.16, frame, sx * 0.94, 0, 0);
  b.box(2.0, 0.12, 0.16, frame, 0, 2.94, 0);
  b.box(2.0, 0.12, 0.16, frame, 0, 0.06, 0);
  if (!open) {
    b.box(1.76, 0.08, 0.12, frame, 0, 1.5, 0);
    corrugated(b, 1.9, 1.48, 0, 0.82, 0.085, 1, sheetA);
    corrugated(b, 1.9, 1.38, 0, 2.22, 0.088, 1, rnd() > 0.5 ? sheetB : rust);
    corrugated(b, 1.9, 2.82, 0, 1.5, -0.085, -1, sheetA);
    b.box(0.7, 0.6, 0.012, rust, 0.4, 0.7, 0.103, 0, 0, 0.05);
  } else {
    const [x0, x1, , y1] = open;
    for (const x of [x0 - 0.05, x1 + 0.05]) b.blk(0.1, 2.88, 0.14, frame, x, 0.06, 0);
    b.box(x1 - x0 + 0.2, 0.1, 0.14, frame, (x0 + x1) / 2, y1 + 0.05, 0);
    const lw = 1 + x0 - 0.1;
    const rw = 1 - x1 - 0.1;
    for (const s of [-1, 1]) {
      corrugated(b, lw, 2.82, -1 + 0.05 + lw / 2, 1.5, s * 0.085, s, s > 0 ? sheetA : sheetB);
      corrugated(b, rw, 2.82, 1 - 0.05 - rw / 2, 1.5, s * 0.085, s, s > 0 ? rust : sheetA);
      corrugated(b, x1 - x0 + 0.2, 2.88 - y1 - 0.1, (x0 + x1) / 2, (2.88 + y1 + 0.1) / 2, s * 0.085, s, sheetA);
    }
  }
  // weld beads + bolts
  for (const sx of [-1, 1]) for (let y = 0.3; y < 2.9; y += 0.6) {
    b.cyl(0.018, 0.02, M('steelBrushed'), sx * 0.88, y, 0.11, 'z', 6);
    b.cyl(0.018, 0.02, M('steelBrushed'), sx * 0.88, y, -0.11, 'z', 6);
  }
  b.box(0.03, 0.03, 0.03, M('metalDark'), (rnd() - 0.5) * 1.6, 1.5, 0.11);
}

function metalFoundation(b: PB): void {
  const frame = M('metalDark');
  const plate = M('metal');
  for (const s of [-1, 1]) {
    b.box(2.0, 0.22, 0.08, frame, 0, 0.13, s * 0.96);
    b.box(0.08, 0.22, 1.84, frame, s * 0.96, 0.13, 0);
    b.box(2.0, 0.03, 0.18, frame, 0, 0.235, s * 0.92);
    b.box(2.0, 0.03, 0.18, frame, 0, 0.035, s * 0.92);
  }
  b.box(0.08, 0.2, 1.84, frame, 0, 0.13, 0);
  b.box(1.84, 0.2, 0.08, frame, 0, 0.13, 0);
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.blk(0.24, 0.03, 0.24, M('steelBrushed'), sx * 0.86, 0, sz * 0.86);
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
    b.box(0.985, 0.05, 0.985, plate, sx * 0.5, 0.275, sz * 0.5);
    for (const ox of [-0.42, 0.42]) for (const oz of [-0.42, 0.42]) b.cyl(0.02, 0.012, M('steelBrushed'), sx * 0.5 + ox, 0.305, sz * 0.5 + oz, 'y', 6);
  }
  b.box(2.0, 0.012, 0.025, frame, 0, 0.301, 0);
  b.box(0.025, 0.012, 2.0, frame, 0, 0.301, 0);
  b.box(0.5, 0.01, 0.4, M('metalRust'), 0.35, 0.302, -0.45, 0, 0.3, 0);
}

function concreteWall(b: PB): void {
  const c = M('concrete');
  const c2 = tint('concrete', 0xd8d4cc);
  b.add(cboxGeo(1.98, 1.47, 0.2, 0.03), c, 0, 0.735, 0);
  b.add(cboxGeo(1.98, 1.47, 0.2, 0.03), c2, 0, 2.255, 0);
  b.box(1.96, 0.04, 0.16, M('metalDark'), 0, 1.495, 0);
  for (const sx of [-1, 1]) {
    b.add(torusGeo(0.05, 0.012, 4, 8, Math.PI), M('steelBrushed'), sx * 0.55, 2.99, 0, 0, Math.PI / 2, 0);
    for (const y of [0.5, 2.0]) b.cyl(0.04, 0.02, M('steelBrushed'), sx * 0.75, y, 0.105, 'z', 8);
    for (const y of [0.5, 2.0]) b.cyl(0.04, 0.02, M('steelBrushed'), sx * 0.75, y, -0.105, 'z', 8);
  }
  // chipped corner with rebar stubs
  for (const x of [-0.8, -0.25, 0.3, 0.85]) b.rod([x, 2.98, 0], [x + 0.02, 3.08, 0.01], 0.008, M('metalRust'), 4);
  b.box(0.6, 0.02, 0.21, tint('concrete', 0x8a8780), -0.4, 0.04, 0);
}

function barricade(b: PB, seed: number, w: number, h: number, yc: number, layout: [number, number, number][]): void {
  const rnd = rng(seed);
  layout.forEach(([y, rz, len], i) => {
    const z = (i % 2 ? 0.03 : 0.0) + 0.0;
    const pw = 0.17 + rnd() * 0.06;
    plank(b, rnd, len, pw, 0.035, (rnd() - 0.5) * 0.06, yc + y, z, rz);
    const c = Math.cos(rz);
    const s = Math.sin(rz);
    for (const e of [-1, 1]) {
      const ex = e * (len / 2 - 0.07);
      for (const o of [-0.04, 0.04]) nail(b, ex * c - o * s, yc + y + ex * s + o * c, z + 0.022);
    }
  });
  void w;
  void h;
}

function gate(b: PB): void {
  const rnd = rng(55);
  const bark = M('bark');
  for (const sx of [-1, 1]) {
    b.add(cylGeo(0.15, 0.17, 3.5, 9), bark, sx * 2.13, 1.75, 0);
    b.add(cylGeo(0, 0.15, 0.25, 9), M('wood'), sx * 2.13, 3.62, 0);
    for (const y of [0.9, 2.3]) {
      b.box(0.12, 0.08, 0.06, M('metalDark'), sx * 2.0, y, 0.0);
    }
  }
  b.add(cylGeo(0.12, 0.12, 4.6, 8), bark, 0, 3.35, 0, 0, 0, Math.PI / 2);
  for (const sx of [-1, 1]) b.beam([sx * 2.1, 2.9, 0], [sx * 1.7, 3.3, 0], 0.08, M('woodDark'));
  const leaf = (name: string, hx: number, dir: number): void => {
    const L = b.sub(name, hx, 0, 0);
    const n = 8;
    const pw = 1.96 / n;
    for (let i = 0; i < n; i++) {
      const x = dir * (0.02 + (i + 0.5) * pw);
      const h = 2.55 + rnd() * 0.15;
      const w = pw - 0.015;
      L.add(extrudeGeo([[-w / 2, 0], [w / 2, 0], [w / 2, h], [0, h + 0.22], [-w / 2, h]], 0.05), woodPick(rnd), x, 0.08, 0.0, 0, 0, (rnd() - 0.5) * 0.015);
    }
    for (const y of [0.45, 2.15]) {
      L.box(1.96, 0.16, 0.05, M('woodDark'), dir * 1.0, y, -0.05);
      for (let i = 0; i < n; i++) nail(L, dir * (0.02 + (i + 0.5) * pw), y, 0.032);
    }
    const len = Math.hypot(1.8, 1.6);
    L.box(0.14, len, 0.05, M('woodDark'), dir * 1.0, 1.3, -0.05, 0, 0, dir * Math.atan2(1.8, 1.6));
    L.box(0.9, 0.7, 0.015, M('roofMetal'), dir * 1.15, 1.25, 0.035, 0, 0, (rnd() - 0.5) * 0.1);
    L.box(0.5, 0.45, 0.015, M('metalRust'), dir * 0.6, 0.6, 0.036, 0, 0, (rnd() - 0.5) * 0.15);
    for (const y of [0.45, 2.15]) L.box(0.5, 0.06, 0.02, M('metalDark'), dir * 0.25, y, 0.04);
  };
  leaf('gateLeafL', -2.0, 1);
  leaf('gateLeafR', 2.0, -1);
  b.box(0.7, 0.1, 0.08, M('metalDark'), 0, 1.3, 0.09);
  b.box(0.08, 0.18, 0.1, M('metalDark'), -0.3, 1.3, 0.1);
  b.box(0.08, 0.18, 0.1, M('metalDark'), 0.3, 1.3, 0.1);
}

function watchtower(b: PB): void {
  const rnd = rng(66);
  const bark = M('bark');
  const dark = M('woodDark');
  const P = 5.0;
  const legAt = (sx: number, sz: number, y: number): V3 => {
    const t = y / P;
    const h = 0.92 - 0.12 * t;
    return [sx * h, y, sz * h];
  };
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
    b.oriented(cylGeo(0.09, 0.11, P + 2.3, 8), bark, new THREE.Vector3(...legAt(sx, sz, 0)), new THREE.Vector3(...legAt(sx, sz, P + 2.3)));
    b.add(cylGeo(0.16, 0.18, 0.2, 8), M('concrete'), sx * 0.92, 0.1, sz * 0.92);
  }
  // X braces on 4 faces, two tiers
  const faces: [number, number, number, number][] = [[-1, -1, 1, -1], [1, -1, 1, 1], [1, 1, -1, 1], [-1, 1, -1, -1]];
  for (const [ax, az, bx, bz] of faces) {
    for (const [ya, yb] of [[0.35, 2.55], [2.55, 4.75]]) {
      b.rod(legAt(ax, az, ya), legAt(bx, bz, yb), 0.045, dark, 6);
      if (!(az === 1 && bz === 1)) b.rod(legAt(bx, bz, ya), legAt(ax, az, yb), 0.045, dark, 6);
    }
    b.beam(legAt(ax, az, 2.55), legAt(bx, bz, 2.55), 0.08, dark);
  }
  // platform
  for (const x of [-0.7, 0, 0.7]) b.box(0.1, 0.16, 2.0, dark, x, P - 0.18, 0);
  for (const s of [-1, 1]) b.box(2.06, 0.18, 0.12, dark, 0, P - 0.15, s * 0.97);
  const n = 9;
  for (let i = 0; i < n; i++) {
    const z = -1 + (i + 0.5) * (2 / n);
    if (z > 0.55 && Math.abs(z) < 0.9 && i === n - 2) {
      // hatch gap near ladder
      b.box(1.2, 0.05, 2 / n - 0.012, woodPick(rnd), -0.4, P - 0.025, z);
      continue;
    }
    b.box(2.04 - rnd() * 0.05, 0.05, 2 / n - 0.012, woodPick(rnd), 0, P - 0.025, z, 0, (rnd() - 0.5) * 0.02, 0);
  }
  // railing: half-height plank walls on 3 sides, open toward the ladder (+Z) partially
  const H = 1.1;
  for (const [ax, az, bx, bz] of faces) {
    const isFront = az === 1 && bz === 1;
    const len = Math.hypot(bx - ax, bz - az);
    const cx = (ax + bx) / 2;
    const cz = (az + bz) / 2;
    const ry = Math.abs(bz - az) > 0 ? Math.PI / 2 : 0;
    b.push(cx * 0.97, P, cz * 0.97, 0, ry, 0);
    b.box(len * 0.97, 0.1, 0.1, dark, 0, H, 0);
    if (!isFront) {
      for (let k = 0; k < 4; k++) plank(b, rnd, len * 0.94, 0.17, 0.03, 0, 0.18 + k * 0.2, 0.06 * Math.sign(cz + cx || 1));
    } else {
      plank(b, rnd, 0.6, 0.17, 0.03, -0.65, 0.5, 0.06);
      plank(b, rnd, 0.6, 0.17, 0.03, 0.65, 0.5, 0.06);
      b.box(0.08, H, 0.08, dark, -0.32, H / 2, 0);
      b.box(0.08, H, 0.08, dark, 0.32, H / 2, 0);
    }
    b.pop();
  }
  // ladder on +Z face
  for (const sx of [-1, 1]) b.rod([sx * 0.26, 0, 1.02], [sx * 0.26, P + 1.0, 0.93], 0.035, dark, 6);
  for (let y = 0.3; y < P; y += 0.33) b.box(0.56, 0.05, 0.06, woodPick(rnd), 0, y, 1.02 - (y / (P + 1)) * 0.09);
  // roof
  b.box(2.3, 0.1, 0.1, dark, 0, P + 2.25, 0.85);
  b.box(2.3, 0.1, 0.1, dark, 0, P + 2.35, -0.85);
  b.push(0, P + 2.42, 0, 0.06, 0, 0);
  b.box(2.35, 0.03, 2.3, M('roofMetal'), 0, 0, 0);
  for (let i = 0; i <= 14; i++) b.box(0.035, 0.02, 2.3, M('roofMetal'), -1.15 + (i * 2.3) / 14, 0.02, 0);
  b.box(0.8, 0.032, 0.9, M('metalRust'), 0.5, 0.005, -0.3);
  b.pop();
  // sandbags + crate on platform
  for (let i = 0; i < 4; i++) b.add(sphereGeo(0.5, 7, 4), tint('fabric', 0x9a8a62), -0.6 + i * 0.4, P + 0.09, -0.75, 0, (rnd() - 0.5) * 0.3, 0, 0.42, 0.18, 0.28);
  b.blk(0.4, 0.35, 0.4, M('wood'), 0.6, P, -0.55, 0.3);
}

function farmPlot(b: PB): void {
  const rnd = rng(81);
  const soil = M('soil');
  const hw = 0.95;
  for (let l = 0; l < 2; l++) {
    const y = 0.09 + l * 0.17;
    for (const s of [-1, 1]) {
      plank(b, rnd, 1.95, 0.16, 0.05, 0, y, s * hw);
      b.push(0, 0, 0, 0, Math.PI / 2, 0);
      plank(b, rnd, 1.85, 0.16, 0.05, 0, y, s * hw);
      b.pop();
    }
  }
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
    b.blk(0.08, 0.42, 0.08, M('woodDark'), sx * (hw - 0.06), 0, sz * (hw - 0.06));
    nail(b, sx * (hw + 0.026), 0.12, sz * (hw - 0.06));
  }
  b.box(1.84, 0.28, 1.84, soil, 0, 0.16, 0);
  for (const x of [-0.58, 0, 0.58]) b.add(cylGeo(0.2, 0.2, 1.75, 8, false, Math.PI / 2, Math.PI), soil, x, 0.3, 0, Math.PI / 2, 0, 0, 1, 1, 0.3);
  for (let i = 0; i < 5; i++) b.add(new THREE.DodecahedronGeometry(0.04, 0), M('rock'), (rnd() - 0.5) * 1.6, 0.31, (rnd() - 0.5) * 1.6);
}

/** Crop rows sit on the farm_plot soil (mound tops at y~0.36). */
function crops(b: PB, stage: number): void {
  const rnd = rng(100 + stage);
  const leaf = M('leaves');
  const rows = [-0.58, 0, 0.58];
  const zs = [-0.66, -0.22, 0.22, 0.66];
  const y = 0.34;
  const tomato = tint('plasticWhite', 0xc0331e);
  const pumpkin = tint('plasticWhite', 0xd2731c);
  const cabbage = tint('fabric', 0x9cc070);
  const stem = tint('wood', 0x7a9a4a);
  rows.forEach((x, ri) => {
    for (const z0 of zs) {
      const px = x + (rnd() - 0.5) * 0.06;
      const pz = z0 + (rnd() - 0.5) * 0.06;
      const rot = rnd() * Math.PI;
      if (stage === 0) {
        cardPlant(b, leaf, px, y, pz, 0.1, 0.09, 2, rot, 0.2);
      } else if (stage === 1) {
        cardPlant(b, leaf, px, y, pz, 0.22, 0.2, 2, rot, 0.25);
      } else if (stage === 2) {
        cardPlant(b, leaf, px, y, pz, 0.4, 0.38, 3, rot, 0.3);
        if (ri === 1) b.rod([px + 0.05, y - 0.05, pz], [px + 0.05, y + 0.5, pz], 0.01, M('wood'), 4);
      } else {
        if (ri === 0) {
          b.add(sphereGeo(0.13, 9, 6), cabbage, px, y + 0.1, pz, rnd(), rnd(), 0, 1, 0.85, 1);
          cardPlant(b, leaf, px, y, pz, 0.45, 0.3, 3, rot, 0.6);
        } else if (ri === 1) {
          b.rod([px, y - 0.05, pz], [px, y + 0.75, pz], 0.012, M('wood'), 4);
          cardPlant(b, leaf, px, y, pz, 0.45, 0.7, 3, rot, 0.12);
          for (let i = 0; i < 4; i++) {
            const a = rnd() * Math.PI * 2;
            b.add(sphereGeo(0.04, 7, 5), tomato, px + Math.cos(a) * 0.12, y + 0.2 + rnd() * 0.35, pz + Math.sin(a) * 0.12);
          }
          b.rod([px - 0.02, y + 0.4, pz], [px + 0.02, y + 0.65, pz + 0.02], 0.006, stem, 3);
        } else {
          cardPlant(b, leaf, px, y, pz, 0.5, 0.35, 3, rot, 0.7);
          if ((zs.indexOf(z0) + 1) % 2 === 0) {
            const a = rnd() * Math.PI * 2;
            b.add(cylGeo(0.13, 0.13, 0.16, 10), pumpkin, px + Math.cos(a) * 0.12, y + 0.06, pz + Math.sin(a) * 0.12, 0, rnd(), 0);
            b.add(sphereGeo(0.135, 10, 5), pumpkin, px + Math.cos(a) * 0.12, y + 0.07, pz + Math.sin(a) * 0.12, 0, 0, 0, 1, 0.65, 1);
            b.cyl(0.015, 0.06, stem, px + Math.cos(a) * 0.12, y + 0.17, pz + Math.sin(a) * 0.12, 'y', 4);
          }
        }
      }
    }
  });
}

function stairs(b: PB): void {
  const rnd = rng(44);
  const dark = M('woodDark');
  for (const sx of [-1, 1]) {
    b.beam([sx * 0.86, 0.12, -0.84], [sx * 0.86, 2.86, 0.86], 0.08, dark, 0.26);
    b.blk(0.1, 0.12, 0.3, dark, sx * 0.86, 0, -0.85);
    b.blk(0.14, 3.0, 0.14, dark, sx * 0.86, 0, 0.93);
  }
  b.box(1.86, 0.12, 0.1, dark, 0, 2.9, 0.93);
  b.beam([-0.86, 0.3, 0.93], [0.86, 2.6, 0.93], 0.06, dark, 0.04);
  for (let i = 0; i < 10; i++) {
    const yTop = 0.3 * (i + 1);
    const zc = -1 + (i + 0.5) * 0.2;
    plank(b, rnd, 1.66, 0.045, 0.22, 0, yTop - 0.0225, zc, 0, woodPick(rnd));
    nail(b, -0.8, yTop + 0.003, zc);
    nail(b, 0.8, yTop + 0.003, zc);
  }
  // handrail
  b.blk(0.06, 1.0, 0.06, dark, 0.95, 0.05, -0.92);
  b.blk(0.06, 1.0, 0.06, dark, 0.95, 3.0, 0.93);
  b.beam([0.95, 1.0, -0.92], [0.95, 3.95, 0.93], 0.06, M('wood'));
}

function woodRoof(b: PB): void {
  const rnd = rng(33);
  const dark = M('woodDark');
  for (const x of [-0.92, 0, 0.92]) b.box(0.08, 0.14, 2.0, dark, x, 0.07, 0);
  for (let i = 0; i < 8; i++) {
    const z = -1 + (i + 0.5) * 0.25;
    b.box(2.0 - rnd() * 0.03, 0.03, 0.24, woodPick(rnd), 0, 0.155, z, (rnd() - 0.5) * 0.01, 0, 0);
  }
  for (const [x, m] of [[-0.5, M('roofMetal')], [0.48, tint('roofMetal', 0x9a9c8a)]] as [number, THREE.Material][]) {
    b.push(x, 0.18, 0, 0, (rnd() - 0.5) * 0.02, 0);
    b.box(1.04, 0.01, 2.04, m, 0, 0, 0);
    for (let i = 0; i <= 7; i++) b.box(0.03, 0.018, 2.04, m, -0.52 + (i * 1.04) / 7, 0.01, 0);
    for (const z of [-0.9, 0, 0.9]) for (const xx of [-0.4, 0.4]) nail(b, xx, 0.022, z);
    b.pop();
  }
  b.box(0.6, 0.012, 0.7, M('metalRust'), 0.3, 0.2, 0.5, 0, 0.2, 0);
}

function woodDoor(b: PB): void {
  const rnd = rng(22);
  const n = 5;
  const pw = 1.1 / n;
  for (let i = 0; i < n; i++) b.box(pw - 0.008, 2.2 - rnd() * 0.03, 0.05, woodPick(rnd), (i + 0.5) * pw, 1.1, 0.015);
  for (const y of [0.35, 1.85]) {
    b.box(1.04, 0.14, 0.03, M('woodDark'), 0.55, y, -0.025);
    b.box(0.4, 0.05, 0.012, M('metalDark'), 0.18, y, 0.046);
    for (let i = 0; i < n; i++) nail(b, (i + 0.5) * pw, y, 0.046);
  }
  b.box(0.12, Math.hypot(0.95, 1.4), 0.03, M('woodDark'), 0.55, 1.1, -0.025, 0, 0, Math.atan2(0.95, 1.4));
  b.cyl(0.02, 0.14, M('metalDark'), 0.95, 1.05, 0.08, 'y', 6);
  b.box(0.02, 0.02, 0.05, M('metalDark'), 0.95, 1.11, 0.06);
  b.box(0.02, 0.02, 0.05, M('metalDark'), 0.95, 0.99, 0.06);
  b.cyl(0.025, 2.2, M('metalDark'), 0.0, 1.1, 0.0, 'y', 6);
}

function metalDoor(b: PB): void {
  const frame = M('metalDark');
  const sheet = tint('metal', 0x8a9682);
  b.box(1.1, 2.2, 0.03, sheet, 0.55, 1.1, 0);
  for (const s of [-1, 1]) {
    b.box(1.1, 0.06, 0.08, frame, 0.55, 1.1 + s * 1.07, 0);
    b.box(0.06, 2.2, 0.08, frame, 0.55 + s * 0.52, 1.1, 0);
  }
  for (const y of [0.55, 1.1, 1.65]) b.box(0.98, 0.05, 0.06, frame, 0.55, y, 0);
  b.box(0.35, 0.08, 0.035, M('plasticBlack'), 0.55, 1.75, 0.025);
  b.box(0.4, 0.04, 0.04, M('metalDark'), 0.55, 1.79, 0.04);
  b.box(0.5, 0.45, 0.012, M('metalRust'), 0.4, 0.5, 0.022, 0, 0, 0.06);
  b.cyl(0.02, 0.35, M('steelBrushed'), 0.95, 1.05, 0.07, 'y', 6);
  for (const y of [0.9, 1.2]) b.box(0.02, 0.02, 0.05, M('steelBrushed'), 0.95, y, 0.05);
  for (const y of [0.3, 1.1, 1.9]) b.cyl(0.03, 0.15, frame, 0.0, y, 0.0, 'y', 8);
}

const BUILDERS: Record<BuildPieceId, (b: PB) => void> = {
  wood_foundation: (b) => plankFoundation(b, 1),
  wood_wall: (b) => woodWall(b, 2, null, false),
  wood_wall_window: (b) => woodWall(b, 3, [-0.5, 0.5, 1.0, 2.0], false),
  wood_wall_door: (b) => woodWall(b, 4, [-0.55, 0.55, 0, 2.2], true),
  wood_door: woodDoor,
  wood_stairs: stairs,
  wood_roof: woodRoof,
  metal_foundation: metalFoundation,
  metal_wall: (b) => metalWall(b, 5, null),
  metal_wall_door: (b) => metalWall(b, 6, [-0.55, 0.55, 0, 2.2]),
  metal_door: metalDoor,
  concrete_wall: concreteWall,
  barricade_window: (b) => barricade(b, 7, 1.2, 1.2, 1.5, [[-0.42, 0.08, 1.45], [-0.08, -0.12, 1.5], [0.05, 0.62, 1.6], [0.4, -0.05, 1.42]]),
  barricade_door: (b) => {
    const rnd = rng(8);
    for (const sx of [-1, 1]) plank(b, rnd, 0.16, 2.3, 0.04, sx * 0.55, 1.15, -0.035, 0, M('woodDark'));
    barricade(b, 9, 1.2, 2.3, 0, [[0.3, 0.06, 1.4], [0.75, -0.1, 1.42], [1.2, 0.04, 1.38], [1.15, 0.95, 2.2], [1.65, -0.08, 1.4], [2.05, 0.05, 1.36]]);
  },
  gate,
  watchtower,
  farm_plot: farmPlot,
  crop_stage0: (b) => crops(b, 0),
  crop_stage1: (b) => crops(b, 1),
  crop_stage2: (b) => crops(b, 2),
  crop_stage3: (b) => crops(b, 3),
};

const templates = new Map<BuildPieceId, THREE.Group>();

export function buildPiece(id: BuildPieceId): THREE.Object3D {
  let t = templates.get(id);
  if (!t) {
    const b = new PB(id);
    // crops/leaf cards are thin: no shadow casting needed for seedlings
    if (id === 'crop_stage0' || id === 'crop_stage1') b.shadows = false;
    BUILDERS[id](b);
    t = b.build();
    t.name = id;
    templates.set(id, t);
  }
  const g = t.clone(true);
  g.userData.pieceId = id;
  return g;
}
