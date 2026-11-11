/**
 * World props + abandoned cars (metres).
 *
 * buildProp(id): base at y=0, centred on x/z, front (doors / drawers / seat) toward +Z.
 * Geometry is cached per PropId (one merged geometry per material); every call returns a new
 * Object3D whose Meshes reference the shared geometry + materials (cheap to place hundreds).
 *
 * Note: streetlight / utility_pole keep the pole at the origin; their PROP_SIZES box is symmetric
 * and large enough to contain the arm (streetlight arm reaches toward +Z).
 */
import * as THREE from 'three';
import { RoundedBoxGeometry } from 'three/examples/jsm/geometries/RoundedBoxGeometry.js';
import { getCarPaint } from '../render/materials';
import {
  PartBuilder, type Part, type Vec2, type Vec3, alignY, anchor, bevelBox, box, boxProjectUV, boxBetween, cylinder, cylinderBetween,
  displace, extrude, fbm3, lathe, merge, partsToGroup, polyShape, polygon, rng, slab, sphere, tinted, torus, tube, xf,
} from './geom';

export type PropId =
  | 'fridge' | 'counter' | 'stove' | 'sink_counter' | 'wardrobe' | 'dresser' | 'bed' | 'sofa' | 'armchair' | 'table'
  | 'chair' | 'shelf' | 'bookshelf' | 'tv_stand' | 'toilet' | 'bathtub' | 'desk' | 'locker' | 'filing_cabinet' | 'crate'
  | 'barrel' | 'dumpster' | 'streetlight' | 'fence_wood' | 'fence_chain' | 'hydrant' | 'mailbox' | 'utility_pole' | 'bench'
  | 'tire_pile' | 'trash_bags' | 'shop_shelf' | 'checkout' | 'hospital_bed' | 'gas_pump' | 'road_barrier' | 'sandbags'
  | 'workbench' | 'campfire' | 'rain_collector' | 'sleeping_bag' | 'wood_crate_storage' | 'medical_cabinet' | 'gun_locker'
  | 'tool_rack' | 'cardboard_boxes' | 'traffic_cone' | 'pallet';

/** [width X, height Y, depth Z] in metres — each model fits inside this box (centred on x/z, base y=0). */
export const PROP_SIZES: Record<PropId, [number, number, number]> = {
  fridge: [0.76, 1.8, 0.74],
  counter: [1.2, 0.92, 0.62],
  stove: [0.76, 1.12, 0.7],
  sink_counter: [1.2, 1.12, 0.62],
  wardrobe: [1.0, 2.0, 0.62],
  dresser: [1.2, 0.86, 0.62],
  bed: [1.6, 1.0, 2.14],
  sofa: [2.1, 0.86, 0.92],
  armchair: [0.96, 0.9, 0.9],
  table: [1.6, 0.88, 0.9],
  chair: [0.46, 0.96, 0.52],
  shelf: [1.0, 1.82, 0.42],
  bookshelf: [0.9, 2.0, 0.36],
  tv_stand: [1.4, 1.16, 0.48],
  toilet: [0.42, 0.84, 0.7],
  bathtub: [1.7, 0.75, 0.8],
  desk: [1.4, 0.86, 0.74],
  locker: [0.9, 1.85, 0.56],
  filing_cabinet: [0.47, 1.32, 0.7],
  crate: [0.8, 0.6, 0.62],
  barrel: [0.62, 0.88, 0.62],
  dumpster: [1.94, 1.58, 1.3],
  streetlight: [0.6, 8.2, 4.7],
  fence_wood: [2.4, 1.87, 0.16],
  fence_chain: [2.5, 1.95, 0.12],
  hydrant: [0.5, 0.86, 0.5],
  mailbox: [0.26, 1.3, 0.56],
  utility_pole: [2.4, 9.5, 1.4],
  bench: [1.8, 0.86, 0.68],
  tire_pile: [1.45, 0.95, 1.45],
  trash_bags: [1.2, 0.68, 1.16],
  shop_shelf: [1.25, 1.82, 0.62],
  checkout: [1.8, 1.1, 0.9],
  hospital_bed: [1.0, 1.1, 2.16],
  gas_pump: [0.95, 1.92, 0.6],
  road_barrier: [3.0, 0.82, 0.62],
  sandbags: [1.6, 0.76, 0.62],
  workbench: [1.8, 1.62, 0.76],
  campfire: [1.0, 0.48, 1.0],
  rain_collector: [1.0, 1.62, 1.0],
  sleeping_bag: [0.86, 0.26, 2.12],
  wood_crate_storage: [1.3, 1.0, 0.88],
  medical_cabinet: [0.86, 1.84, 0.46],
  gun_locker: [0.62, 1.6, 0.56],
  tool_rack: [1.56, 1.82, 0.32],
  cardboard_boxes: [1.0, 0.92, 0.96],
  traffic_cone: [0.36, 0.72, 0.36],
  pallet: [1.2, 0.15, 1.0],
};

// ---------------------------------------------------------------------------
// Shared materials (memoised tints)
// ---------------------------------------------------------------------------
const M = {
  paintedWood: () => getCarPaint(0xd6cfbd),
  oldWood: () => tinted('wood', 0xf0e8dc),
  greyWood: () => tinted('wood', 0xc4beb4),
  walnut: () => tinted('wood', 0xa8805e),
  enamel: () => tinted('plasticWhite', 0xd2ccbc),
  porcelain: () => tinted('plasticWhite', 0xe0ddd2),
  laminate: () => tinted('concrete', 0xa49c8c),
  steelGrey: () => getCarPaint(0x464844),
  steelGreen: () => getCarPaint(0x3e5046),
  steelBeige: () => getCarPaint(0x6e6858),
  upholstery: () => tinted('fabric', 0x8e7a62),
  cardboard: () => tinted('fabric', 0xe2c294),
  burlap: () => tinted('fabric', 0xd4c094),
  bagPlastic: () => tinted('plasticBlack', 0x202224),
  orange: () => tinted('plasticWhite', 0xd2581c),
  redLens: () => tinted('plasticBlack', 0x8a1410),
  amberLens: () => tinted('plasticBlack', 0xb06a10),
  charred: () => tinted('woodDark', 0x1c1a18),
};

/** Horizontal bar handle on two standoffs, centred, protruding toward +Z. */
function handleBar(len: number, r = 0.006, standoff = 0.03): THREE.BufferGeometry {
  return merge([
    xf(cylinder(r, r, len, 8), 0, 0, standoff, 0, 0, Math.PI / 2),
    xf(cylinder(r * 0.8, r * 0.8, standoff, 6), -len / 2 + 0.01, 0, standoff / 2, Math.PI / 2),
    xf(cylinder(r * 0.8, r * 0.8, standoff, 6), len / 2 - 0.01, 0, standoff / 2, Math.PI / 2),
  ]);
}
/** Small round knob protruding +Z. */
const knob = (r = 0.014) => merge([xf(cylinder(r * 0.45, r * 0.45, 0.02, 6), 0, 0, 0.01, Math.PI / 2), xf(sphere(r, 8, 6), 0, 0, 0.024, 0, 0, 0, 1, 1, 0.7)]);
/** Recessed-panel door/drawer front facing +Z (frame + inset panel), centred, thickness t. */
function panelFront(w: number, h: number, t = 0.02, border = 0.06): THREE.BufferGeometry {
  return merge([
    bevelBox(w, h, t, 0.004),
    xf(bevelBox(Math.max(w - border * 2, 0.02), Math.max(h - border * 2, 0.02), t * 0.4, 0.01), 0, 0, t * 0.55),
  ]);
}
/** Noise-displaced boulder (icosahedron). */
function stone(r: number, seed: number, detail = 1): THREE.BufferGeometry {
  const g = new THREE.IcosahedronGeometry(r, detail);
  const s = seed * 1.37;
  displace(g, (v) => {
    const n = fbm3(v.x / r * 1.3 + s, v.y / r * 1.3, v.z / r * 1.3 - s, 3);
    v.multiplyScalar(1 + n * 0.35);
    v.y *= 0.7;
  });
  g.computeVertexNormals();
  return xf(g, 0, 0, 0);
}
/** Soft rounded box (cushions, mattresses, pillows) with gentle noise. */
function softBox(w: number, h: number, d: number, radius: number, seed: number, noise = 0.012, puff = 0): THREE.BufferGeometry {
  const g = new RoundedBoxGeometry(w, h, d, 2, radius);
  displace(g, (v) => {
    const n = fbm3(v.x * 6 + seed, v.y * 6, v.z * 6 - seed, 2);
    const f = 1 - Math.min(1, (Math.abs(v.x) / (w / 2)) ** 2 + (Math.abs(v.z) / (d / 2)) ** 2);
    v.y += Math.sign(v.y) * puff * f;
    v.x += n * noise; v.y += n * noise; v.z += n * noise * 0.7;
  });
  g.computeVertexNormals();
  return boxProjectUV(g);
}

/** Lumpy soft object (sphere squashed into a pillow / sack shape). */
function lump(sx: number, sy: number, sz: number, seed: number, flat = 0.75, ws = 10, hs = 7): THREE.BufferGeometry {
  const g = new THREE.SphereGeometry(1, ws, hs);
  displace(g, (v) => {
    v.y = Math.sign(v.y) * Math.min(Math.abs(v.y), flat);
    const n = fbm3(v.x * 1.7 + seed, v.y * 1.7, v.z * 1.7 + seed * 2, 3);
    v.multiplyScalar(1 + n * 0.18);
    v.set(v.x * sx, v.y * sy / flat, v.z * sz);
  });
  g.computeVertexNormals();
  return g;
}

// ---------------------------------------------------------------------------
// Prop definitions
// ---------------------------------------------------------------------------

type Anchor = { name: string; key: string; p: Vec3 };
interface PropDef { parts: Part[]; anchors: Anchor[] }

function kitchenBase(pb: PartBuilder, w: number, d: number, h: number, drawers: boolean): void {
  const carcass = M.paintedWood();
  pb.add(carcass, xf(bevelBox(w - 0.02, h - 0.14, d - 0.06, 0.006), 0, 0.1 + (h - 0.14) / 2, -0.03));
  pb.add('woodDark', xf(box(w - 0.06, 0.1, d - 0.12), 0, 0.05, -0.06));
  pb.add(M.laminate(), xf(bevelBox(w, 0.04, d, 0.008), 0, h - 0.02, 0));
  pb.add('metal', xf(box(w, 0.004, 0.006), 0, h - 0.042, d / 2 - 0.004)); // trim edge
  const fz = d / 2 - 0.05;
  const half = (w - 0.06) / 2;
  const doorH = drawers ? h - 0.36 : h - 0.2;
  const doorY = 0.11 + doorH / 2;
  for (const sx of [-1, 1]) {
    const x = sx * (half / 2 + 0.01);
    pb.add(carcass, xf(panelFront(half - 0.01, doorH, 0.02, 0.05), x, doorY, fz));
    pb.add('steelBrushed', xf(handleBar(0.1, 0.005, 0.025), x - sx * (half / 2 - 0.06), doorY + doorH / 2 - 0.08, fz + 0.01, 0, 0, Math.PI / 2));
    if (drawers) {
      const dy = h - 0.04 - 0.1;
      pb.add(carcass, xf(bevelBox(half - 0.01, 0.15, 0.02, 0.004), x, dy, fz));
      pb.add('steelBrushed', xf(handleBar(0.12, 0.005, 0.025), x, dy, fz + 0.01));
    }
  }
}

function define(id: PropId): PropDef {
  const pb = new PartBuilder();
  const anchors: Anchor[] = [];
  const [W, H, D] = PROP_SIZES[id];
  const r = rng(id.length * 7919 + id.charCodeAt(0) * 31);

  switch (id) {
    case 'fridge': {
      const en = M.enamel();
      pb.add(en, xf(bevelBox(0.74, 1.76, 0.6, 0.03), 0, 0.9, -0.06));
      pb.add(en, xf(bevelBox(0.74, 0.5, 0.07, 0.02), 0, 1.53, 0.275));
      pb.add(en, xf(bevelBox(0.74, 1.2, 0.07, 0.02), 0, 0.68, 0.275));
      pb.add('rubber', xf(box(0.72, 0.012, 0.06), 0, 1.272, 0.27));
      pb.add('plasticBlack', xf(handleBar(0.36, 0.009, 0.04), -0.31, 0.86, 0.31, 0, 0, Math.PI / 2));
      pb.add('plasticBlack', xf(handleBar(0.24, 0.009, 0.04), -0.31, 1.42, 0.31, 0, 0, Math.PI / 2));
      pb.add('grating', xf(box(0.66, 0.07, 0.01), 0, 0.045, 0.25));
      pb.add('rubber', ...[-0.32, 0.32].flatMap((x) => [-0.3, 0.2].map((z) => xf(cylinder(0.02, 0.02, 0.02, 6), x, 0.01, z))));
      pb.add('metalRust', xf(box(0.3, 0.06, 0.005), 0.12, 0.12, 0.312), xf(box(0.005, 0.12, 0.2), 0.372, 0.1, 0.0));
      pb.add(tinted('plasticWhite', 0x8a2a20), xf(box(0.05, 0.05, 0.004), 0.2, 1.1, 0.312)); // magnet
      pb.add('plasticWhite', xf(box(0.15, 0.2, 0.003), 0.15, 1.0, 0.312, 0, 0, 0.08)); // note
      break;
    }
    case 'counter': kitchenBase(pb, W, D, H, true); break;
    case 'sink_counter': {
      kitchenBase(pb, W, D, 0.92, false);
      const st = 'steelBrushed';
      pb.add(st, xf(bevelBox(0.8, 0.012, 0.46, 0.004), 0, 0.926, 0.03));
      pb.add('metalDark', xf(box(0.7, 0.004, 0.38), 0, 0.931, 0.03));
      pb.add(tinted('metalDark', 0x3a3a38), xf(box(0.004, 0.16, 0.38), -0.35, 0.86, 0.03), xf(box(0.004, 0.16, 0.38), 0.35, 0.86, 0.03));
      pb.add('chrome', tube([[0, 0.93, -0.22], [0, 1.08, -0.22], [0, 1.1, -0.12], [0, 1.03, -0.05]], 0.012, 12, 8, { end: true }));
      pb.add('chrome', xf(cylinder(0.025, 0.03, 0.02, 10), 0, 0.94, -0.22));
      for (const sx of [-1, 1]) pb.add('chrome', xf(cylinder(0.018, 0.02, 0.04, 8), sx * 0.12, 0.95, -0.22), xf(box(0.06, 0.01, 0.012), sx * 0.12, 0.975, -0.22));
      pb.add('mud', xf(box(0.3, 0.003, 0.2), 0.05, 0.9335, 0.06));
      break;
    }
    case 'stove': {
      const en = M.enamel();
      pb.add(en, xf(bevelBox(0.76, 0.88, 0.62, 0.02), 0, 0.46, -0.03));
      pb.add('plasticBlack', xf(box(0.7, 0.06, 0.55), 0, 0.03, -0.04));
      pb.add('metalDark', xf(bevelBox(0.74, 0.02, 0.6, 0.005), 0, 0.91, -0.03));
      for (const [x, z] of [[-0.18, -0.15], [0.18, -0.15], [-0.18, 0.12], [0.18, 0.12]]) {
        pb.add('metalDark', xf(cylinder(0.08, 0.085, 0.012, 14), x, 0.925, z));
        pb.add('metal', xf(cylinder(0.04, 0.04, 0.012, 10), x, 0.93, z));
        pb.add('metalDark', xf(box(0.2, 0.012, 0.012), x, 0.94, z), xf(box(0.012, 0.012, 0.2), x, 0.94, z));
      }
      pb.add(en, xf(bevelBox(0.76, 0.2, 0.05, 0.012), 0, 1.02, -0.315));
      for (let i = 0; i < 4; i++) pb.add('plasticBlack', xf(knob(0.018), -0.24 + i * 0.16, 1.02, -0.29));
      pb.add('emissiveRed', xf(box(0.01, 0.01, 0.004), 0.32, 1.05, -0.289));
      pb.add(en, xf(panelFront(0.7, 0.52, 0.03, 0.04), 0, 0.5, 0.29));
      pb.add('glassDirty', xf(box(0.48, 0.24, 0.006), 0, 0.5, 0.31));
      pb.add('steelBrushed', xf(handleBar(0.58, 0.009, 0.04), 0, 0.73, 0.3));
      pb.add(en, xf(bevelBox(0.7, 0.13, 0.03, 0.008), 0, 0.14, 0.29));
      pb.add('steelBrushed', xf(handleBar(0.3, 0.006, 0.025), 0, 0.15, 0.3));
      pb.add('metalRust', xf(box(0.2, 0.05, 0.004), -0.2, 0.25, 0.307));
      break;
    }
    case 'wardrobe': {
      const wd = M.walnut();
      pb.add(wd, xf(bevelBox(0.98, 1.86, 0.54, 0.006), 0, 1.0, -0.02));
      pb.add(wd, xf(bevelBox(1.0, 0.06, 0.58, 0.012), 0, 1.97, 0), xf(bevelBox(0.97, 0.02, 0.56, 0.006), 0, 1.93, 0));
      pb.add(wd, xf(bevelBox(1.0, 0.08, 0.56, 0.006), 0, 0.04, -0.01));
      for (const sx of [-1, 1]) {
        pb.add(wd, xf(panelFront(0.47, 1.72, 0.025, 0.07), sx * 0.24, 1.0, 0.27));
        pb.add('metalDark', xf(knob(0.012), sx * 0.04, 1.05, 0.28));
        for (const y of [0.3, 1.7]) pb.add('metalDark', xf(cylinder(0.008, 0.008, 0.06, 6), sx * 0.475, y, 0.27));
      }
      pb.add(wd, xf(panelFront(0.95, 0.12, 0.02, 0.025), 0, 0.15, 0.27));
      break;
    }
    case 'dresser': {
      const wd = M.oldWood();
      pb.add(wd, xf(bevelBox(1.16, 0.72, 0.46, 0.006), 0, 0.44, -0.01));
      pb.add(wd, xf(bevelBox(1.2, 0.035, 0.5, 0.01), 0, 0.8425, 0));
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) pb.add(wd, xf(bevelBox(0.05, 0.09, 0.05, 0.008), sx * 0.55, 0.045, sz * 0.2));
      const rows = [[0.7, 2], [0.5, 1], [0.27, 1]] as const;
      for (const [y, n] of rows) {
        const dw = (1.1 - (n - 1) * 0.01) / n;
        for (let i = 0; i < n; i++) {
          const x = -0.55 + dw / 2 + i * (dw + 0.01);
          const hh = y === 0.7 ? 0.15 : 0.2;
          const out = r() < 0.25 ? 0.035 : 0;
          pb.add(wd, xf(bevelBox(dw, hh, 0.022, 0.004), x, y, 0.235 + out));
          for (const kx of n === 1 ? [-0.3, 0.3] : [0]) pb.add('steelBrushed', xf(handleBar(0.08, 0.004, 0.02), x + kx, y, 0.245 + out));
        }
      }
      pb.add(tinted('fabric', 0xd8d0c0), xf(box(0.4, 0.004, 0.3), -0.3, 0.862, 0.0, 0, 0.1)); // doily
      break;
    }
    case 'bed': {
      const wd = M.walnut();
      // headboard (−Z) + footboard (+Z)
      for (const sx of [-1, 1]) {
        pb.add(wd, xf(bevelBox(0.07, 1.0, 0.07, 0.01), sx * 0.765, 0.5, -1.02));
        pb.add(wd, xf(bevelBox(0.07, 0.6, 0.07, 0.01), sx * 0.765, 0.3, 1.02));
        pb.add(wd, xf(bevelBox(0.04, 0.16, 1.98, 0.006), sx * 0.765, 0.3, 0));
      }
      pb.add(wd, xf(panelFront(1.46, 0.5, 0.035, 0.06), 0, 0.68, -1.02), xf(bevelBox(1.6, 0.06, 0.09, 0.01), 0, 0.97, -1.02));
      pb.add(wd, xf(panelFront(1.46, 0.3, 0.03, 0.05), 0, 0.4, 1.02, 0, Math.PI));
      pb.add(wd, xf(bevelBox(1.6, 0.05, 0.08, 0.01), 0, 0.62, 1.02));
      // mattress, pillows, rumpled blanket
      pb.add(tinted('fabric', 0xbcb2a0), xf(softBox(1.48, 0.22, 1.96, 0.06, 2, 0.01, 0.01), 0, 0.48, 0));
      pb.add('soil', xf(box(0.4, 0.003, 0.5), 0.3, 0.5905, -0.2, 0, 0.4)); // stain
      for (const sx of [-1, 1]) pb.add(tinted('fabric', 0xd0c8b8), xf(softBox(0.6, 0.1, 0.38, 0.05, sx * 3, 0.012, 0.03), sx * 0.36, 0.64, -0.78, 0.1, sx * 0.08, 0));
      const bl = new THREE.BoxGeometry(1.56, 0.06, 1.4, 12, 1, 10);
      displace(bl, (v) => {
        const ex = Math.max(0, Math.abs(v.x) - 0.7);
        v.y += fbm3(v.x * 2.2, 0, v.z * 2.2, 3) * 0.06 - ex * 1.6;
        if (ex > 0) v.x = Math.sign(v.x) * (0.7 + ex * 0.25);
      });
      bl.computeVertexNormals();
      pb.add(tinted('fabric', 0x56604e), xf(bl, 0, 0.62, 0.25));
      break;
    }
    case 'sofa':
    case 'armchair': {
      const fab = id === 'sofa' ? M.upholstery() : tinted('fabric', 0x7a6650);
      const seats = id === 'sofa' ? 3 : 1;
      const armW = 0.18;
      const innerW = W - armW * 2;
      const dd = D - 0.02;
      pb.add(fab, xf(bevelBox(innerW, 0.25, dd - 0.2, 0.03), 0, 0.22, 0.06));
      pb.add(fab, xf(bevelBox(W - 0.02, 0.62, 0.2, 0.06), 0, 0.53, -dd / 2 + 0.1));
      for (const sx of [-1, 1]) {
        pb.add(fab, xf(bevelBox(armW, 0.45, dd, 0.05), sx * (W / 2 - armW / 2 - 0.005), 0.31, 0));
        pb.add(fab, xf(cylinder(0.1, 0.1, dd - 0.02, 10), sx * (W / 2 - armW / 2 - 0.005), 0.55, 0, Math.PI / 2, 0, 0, 0.92, 1, 1));
        for (const sz of [-1, 1]) pb.add('woodDark', xf(cylinder(0.022, 0.016, 0.09, 6), sx * (W / 2 - 0.08), 0.045, sz * (dd / 2 - 0.08)));
      }
      const cw = innerW / seats;
      for (let i = 0; i < seats; i++) {
        const x = -innerW / 2 + cw * (i + 0.5);
        pb.add(fab, xf(softBox(cw - 0.02, 0.14, dd - 0.26, 0.05, i + 1, 0.01, 0.015), x, 0.415, 0.1 + (i === 1 ? 0.02 : 0), (i === 1 ? 0.03 : 0)));
        pb.add(fab, xf(softBox(cw - 0.04, 0.4, 0.15, 0.06, i + 7, 0.012, 0.0), x, 0.64, -dd / 2 + 0.27, -0.2, 0, (i - 1) * 0.03));
      }
      if (id === 'sofa') pb.add(tinted('fabric', 0x8a3a2a), xf(softBox(0.36, 0.34, 0.1, 0.05, 4, 0.01, 0.03), 0.62, 0.6, -0.12, -0.3, 0.3, 0.2));
      pb.add('soil', xf(box(0.3, 0.003, 0.25), -0.2, 0.481, 0.15));
      break;
    }
    case 'table': {
      const wd = M.oldWood();
      pb.add(wd, xf(bevelBox(1.6, 0.035, 0.9, 0.008), 0, 0.7525, 0));
      pb.add(wd, xf(box(1.44, 0.08, 0.02), 0, 0.695, 0.36), xf(box(1.44, 0.08, 0.02), 0, 0.695, -0.36));
      pb.add(wd, xf(box(0.02, 0.08, 0.66), 0.72, 0.695, 0), xf(box(0.02, 0.08, 0.66), -0.72, 0.695, 0));
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
        pb.add(wd, xf(cylinder(0.03, 0.02, 0.735, 8), sx * 0.73, 0.3675, sz * 0.37));
      }
      pb.add('plasticWhite', xf(box(0.21, 0.003, 0.3), 0.3, 0.7715, 0.1, 0, 0.3));
      pb.add(tinted('plasticWhite', 0x8a8a7a), xf(cylinder(0.04, 0.035, 0.1, 10, { open: false }), -0.4, 0.82, -0.1));
      break;
    }
    case 'chair': {
      const wd = M.oldWood();
      pb.add(wd, xf(bevelBox(0.44, 0.03, 0.42, 0.008), 0, 0.46, 0.03));
      for (const sx of [-1, 1]) {
        pb.add(wd, xf(cylinder(0.016, 0.013, 0.445, 6), sx * 0.19, 0.2225, 0.21));
        pb.add(wd, boxBetween([sx * 0.19, 0, -0.17], [sx * 0.19, 0.95, -0.235], 0.032, 0.025, 0.006));
        pb.add(wd, cylinderBetween([sx * 0.19, 0.15, -0.17], [sx * 0.19, 0.15, 0.21], 0.008, 0.008, 5));
      }
      pb.add(wd, cylinderBetween([-0.19, 0.2, 0.21], [0.19, 0.2, 0.21], 0.008, 0.008, 5));
      pb.add(wd, xf(bevelBox(0.4, 0.07, 0.02, 0.006), 0, 0.9, -0.228, -0.07));
      for (let i = -1; i <= 1; i++) pb.add(wd, xf(bevelBox(0.03, 0.36, 0.014, 0.004), i * 0.1, 0.68, -0.21, -0.07));
      break;
    }
    case 'shelf': {
      const st = M.steelGrey();
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
        pb.add(st, xf(box(0.035, 1.8, 0.003), sx * 0.48, 0.9, sz * 0.19), xf(box(0.003, 1.8, 0.035), sx * 0.495, 0.9, sz * 0.19 - sz * 0.016));
      }
      const levels = [0.08, 0.5, 0.92, 1.34, 1.76];
      for (const y of levels) {
        pb.add(st, xf(bevelBox(0.99, 0.025, 0.4, 0.004), 0, y, 0));
      }
      // stock
      const colors = [0xa8885e, 0x8a7a5a, 0x6a6a5e];
      for (let li = 0; li < 4; li++) {
        let x = -0.44;
        while (x < 0.42) {
          const kind = r();
          if (kind < 0.35) { // cardboard box
            const bw = 0.15 + r() * 0.2, bh = 0.12 + r() * 0.2;
            if (x + bw > 0.47) break;
            pb.add(tinted('fabric', colors[Math.floor(r() * 3)]), xf(bevelBox(bw, bh, 0.28, 0.004), x + bw / 2, levels[li] + 0.0125 + bh / 2, -0.03 + r() * 0.04, 0, (r() - 0.5) * 0.2));
            x += bw + 0.02;
          } else if (kind < 0.6) { // paint cans
            pb.add(M.steelGrey(), xf(cylinder(0.085, 0.085, 0.19, 10), x + 0.09, levels[li] + 0.11, 0.05));
            pb.add('metalRust', xf(cylinder(0.086, 0.086, 0.02, 10), x + 0.09, levels[li] + 0.2, 0.05));
            x += 0.2;
          } else x += 0.12 + r() * 0.15; // gap (looted)
        }
      }
      break;
    }
    case 'bookshelf': {
      const wd = M.walnut();
      for (const sx of [-1, 1]) pb.add(wd, xf(bevelBox(0.025, 2.0, 0.35, 0.004), sx * 0.4375, 1.0, 0));
      pb.add(wd, xf(box(0.85, 1.96, 0.01), 0, 1.0, -0.17));
      const lv = [0.06, 0.45, 0.84, 1.23, 1.62, 1.985];
      for (const y of lv) pb.add(wd, xf(bevelBox(0.85, 0.025, 0.34, 0.004), 0, y, 0));
      pb.add(wd, xf(box(0.85, 0.06, 0.02), 0, 0.03, 0.16));
      const bookCols = [0x6a2a22, 0x2a3a5a, 0x3a4a32, 0x8a7a5a, 0x5a4a3a, 0x7a6a4a];
      for (let li = 0; li < 5; li++) {
        let x = -0.41;
        const base = lv[li] + 0.0125;
        while (x < 0.4) {
          if (r() < 0.12) { x += 0.06 + r() * 0.1; continue; }
          const bw = 0.022 + r() * 0.03, bh = 0.2 + r() * 0.12, bd = 0.17 + r() * 0.08;
          if (x + bw > 0.42) break;
          const lean = r() < 0.08 ? 0.3 : 0;
          pb.add(tinted('fabric', bookCols[Math.floor(r() * bookCols.length)]), xf(box(bw, bh, bd), x + bw / 2 + lean * 0.05, base + bh / 2 - lean * 0.02, 0.15 - bd / 2, 0, 0, -lean));
          x += bw + 0.002 + lean * 0.1;
        }
      }
      break;
    }
    case 'tv_stand': {
      const wd = M.walnut();
      pb.add(wd, xf(bevelBox(1.4, 0.03, 0.45, 0.006), 0, 0.5, 0));
      pb.add(wd, xf(bevelBox(1.36, 0.42, 0.42, 0.006), 0, 0.27, -0.01));
      pb.add(wd, xf(bevelBox(1.4, 0.06, 0.44, 0.006), 0, 0.03, 0));
      for (const sx of [-1, 1]) pb.add(wd, xf(panelFront(0.42, 0.36, 0.02, 0.04), sx * 0.45, 0.28, 0.2), xf(knob(0.01), sx * 0.3, 0.3, 0.21));
      pb.add('glassDirty', xf(box(0.4, 0.34, 0.01), 0, 0.28, 0.2));
      pb.add('plasticBlack', xf(bevelBox(0.36, 0.08, 0.3, 0.008), 0, 0.2, 0.0));
      // CRT TV
      const tv = tinted('plasticBlack', 0x2e2c2a);
      pb.add(tv, xf(bevelBox(0.66, 0.5, 0.18, 0.025), 0, 0.765, 0.12));
      pb.add(tv, alignY(cylinder(0.12, 0.25, 0.26, 4, {}), [0, 0.77, 0.03], [0, 0.77, -0.21]));
      pb.add('glassDirty', xf(bevelBox(0.54, 0.4, 0.02, 0.02), 0, 0.78, 0.205));
      pb.add('plasticBlack', xf(box(0.3, 0.02, 0.01), -0.12, 0.555, 0.214));
      pb.add('metal', cylinderBetween([0.05, 1.015, -0.05], [-0.2, 1.15, -0.1], 0.003, 0.002, 4), cylinderBetween([0.08, 1.015, -0.05], [0.3, 1.15, -0.08], 0.003, 0.002, 4));
      pb.add(tv, xf(sphere(0.04, 8, 4, Math.PI * 2, Math.PI / 2), 0.065, 1.015, -0.05));
      break;
    }
    case 'toilet': {
      const pc = M.porcelain();
      pb.add(pc, xf(lathe([[0.1, 0], [0.12, 0.01], [0.1, 0.05], [0.09, 0.2], [0.12, 0.32], [0.18, 0.38], [0.19, 0.4], [0.16, 0.405], [0.14, 0.3], [0, 0.29]], 16), 0, 0, 0.08, 0, 0, 0, 0.95, 1, 1.35));
      pb.add(pc, xf(bevelBox(0.17, 0.38, 0.22, 0.03), 0, 0.19, -0.14));
      pb.add(tinted('plasticWhite', 0xcfc8b8), xf(lathe([[0.13, 0], [0.19, 0.0], [0.2, 0.012], [0.18, 0.025], [0.13, 0.02]], 16), 0, 0.405, 0.08, 0, 0, 0, 0.95, 1, 1.35));
      pb.add(tinted('plasticWhite', 0xcfc8b8), xf(bevelBox(0.36, 0.42, 0.025, 0.012), 0, 0.62, -0.18, -0.12));
      pb.add(pc, xf(bevelBox(0.4, 0.36, 0.17, 0.02), 0, 0.6, -0.26), xf(bevelBox(0.42, 0.03, 0.19, 0.01), 0, 0.795, -0.26));
      pb.add('chrome', xf(box(0.06, 0.012, 0.012), -0.15, 0.73, -0.17), xf(cylinder(0.012, 0.012, 0.01, 8), -0.18, 0.73, -0.172, Math.PI / 2));
      pb.add('soil', xf(box(0.16, 0.003, 0.2), 0, 0.3, 0.1));
      break;
    }
    case 'bathtub': {
      const pc = M.porcelain();
      pb.add(pc, xf(bevelBox(1.7, 0.56, 0.8, 0.03), 0, 0.3, 0, 0, 0, 0, 1, 1, 1));
      pb.add(pc, xf(bevelBox(1.68, 0.06, 0.78, 0.02), 0, 0.58, 0));
      // inner basin (darker recess + inner walls)
      const ins = tinted('plasticWhite', 0xc8c4b8);
      pb.add(ins, xf(bevelBox(1.5, 0.02, 0.6, 0.01), 0, 0.15, 0));
      for (const sx of [-1, 1]) pb.add(ins, xf(box(0.02, 0.45, 0.6), sx * 0.74, 0.39, 0, 0, 0, sx * 0.12));
      for (const sz of [-1, 1]) pb.add(ins, xf(box(1.5, 0.45, 0.02), 0, 0.39, sz * 0.29));
      pb.add(pc, xf(box(1.56, 0.01, 0.66), 0, 0.611, 0)); // rim frame (top)
      pb.add('mud', xf(box(1.44, 0.003, 0.56), 0, 0.162, 0));
      pb.add('chrome', tube([[-0.82, 0.62, 0], [-0.82, 0.72, 0], [-0.72, 0.72, 0]], 0.014, 8, 8, { end: true }));
      for (const sz of [-1, 1]) pb.add('chrome', xf(cylinder(0.02, 0.02, 0.05, 8), -0.82, 0.64, sz * 0.12));
      break;
    }
    case 'desk': {
      const wd = M.walnut();
      pb.add(wd, xf(bevelBox(1.4, 0.035, 0.7, 0.008), 0, 0.7425, 0));
      pb.add(wd, xf(bevelBox(0.42, 0.72, 0.66, 0.005), -0.48, 0.36, 0));
      pb.add(wd, xf(bevelBox(0.03, 0.72, 0.66, 0.005), 0.67, 0.36, 0));
      pb.add(wd, xf(box(0.9, 0.4, 0.015), 0.2, 0.5, -0.31));
      for (let i = 0; i < 3; i++) {
        const y = 0.6 - i * 0.22;
        const out = i === 1 ? 0.0 : 0;
        pb.add(wd, xf(panelFront(0.4, 0.2, 0.02, 0.025), -0.48, y, 0.335 + out));
        pb.add('steelBrushed', xf(handleBar(0.1, 0.004, 0.02), -0.48, y + 0.04, 0.345 + out));
      }
      pb.add('plasticWhite', xf(box(0.21, 0.003, 0.297), 0.1, 0.7615, 0.1, 0, -0.2), xf(box(0.21, 0.003, 0.297), 0.13, 0.7645, 0.08, 0, 0.15));
      pb.add(tinted('plasticWhite', 0x6a7a8a), xf(cylinder(0.04, 0.035, 0.09, 10), 0.5, 0.805, -0.15));
      pb.add('metalDark', xf(cylinder(0.07, 0.08, 0.02, 10), -0.4, 0.77, -0.2), cylinderBetween([-0.4, 0.77, -0.2], [-0.35, 0.79, -0.2], 0.008, 0.008, 4));
      break;
    }
    case 'locker': {
      const st = M.steelGreen();
      pb.add(st, xf(bevelBox(0.9, 1.8, 0.42, 0.006), 0, 0.95, -0.03));
      pb.add('metalDark', xf(box(0.88, 0.05, 0.4), 0, 0.025, -0.03));
      for (let i = 0; i < 3; i++) {
        const x = -0.3 + i * 0.3;
        const ajar = i === 2 ? 0.18 : 0;
        const g = merge([
          bevelBox(0.28, 1.72, 0.02, 0.004),
          ...[0, 1, 2, 3, 4, 5].map((k) => xf(box(0.16, 0.008, 0.004), 0, 0.72 - k * 0.022, 0.011)),
          ...[0, 1, 2, 3, 4, 5].map((k) => xf(box(0.16, 0.008, 0.004), 0, -0.6 - k * 0.022, 0.011)),
        ]);
        xf(g, 0.14, 0, 0); // hinge on left edge
        xf(g, x - 0.14, 0.94, 0.2, 0, -ajar);
        pb.add(st, g);
        pb.add('steelBrushed', xf(bevelBox(0.03, 0.1, 0.03, 0.006), 0, 0, 0).applyMatrix4(new THREE.Matrix4().makeTranslation(0.24, 0, 0.02)).applyMatrix4(new THREE.Matrix4().makeRotationY(-ajar)).translate(x - 0.14, 0.95, 0.2));
        pb.add('plasticWhite', xf(box(0.05, 0.025, 0.003), 0.14, 0, 0.012).translate(0, 0, 0).applyMatrix4(new THREE.Matrix4().makeRotationY(-ajar)).translate(x - 0.14, 1.6, 0.2));
      }
      pb.add('metalRust', xf(box(0.004, 0.3, 0.3), 0.452, 0.2, -0.05), xf(box(0.2, 0.08, 0.004), -0.25, 0.1, 0.212));
      break;
    }
    case 'filing_cabinet': {
      const st = M.steelBeige();
      pb.add(st, xf(bevelBox(0.47, 1.32, 0.58, 0.008), 0, 0.66, -0.03));
      for (let i = 0; i < 4; i++) {
        const y = 0.17 + i * 0.32;
        const out = i === 1 ? 0.04 : 0;
        pb.add(st, xf(bevelBox(0.43, 0.29, 0.025, 0.006), 0, y, 0.272 + out));
        pb.add('steelBrushed', xf(handleBar(0.12, 0.006, 0.02), 0, y + 0.03, 0.285 + out));
        pb.add('steelBrushed', xf(box(0.08, 0.03, 0.004), 0, y + 0.09, 0.286 + out));
        pb.add('plasticWhite', xf(box(0.07, 0.022, 0.003), 0, y + 0.09, 0.288 + out));
      }
      pb.add('metalRust', xf(box(0.004, 0.2, 0.35), -0.237, 0.12, 0));
      break;
    }
    case 'crate': {
      const wd = tinted('wood', 0xa48a62);
      const plank = (w: number, h: number, d: number, x: number, y: number, z: number) => pb.add(wd, xf(bevelBox(w, h, d, 0.003), x, y, z));
      for (let i = 0; i < 3; i++) {
        const y = 0.1 + i * 0.195;
        for (const sz of [-1, 1]) plank(0.76, 0.17, 0.02, 0, y, sz * 0.28);
        for (const sx of [-1, 1]) plank(0.02, 0.17, 0.54, sx * 0.37, y, 0);
      }
      for (let i = 0; i < 4; i++) plank(0.76, 0.02, 0.135, 0, 0.59, -0.21 + i * 0.14);
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
        plank(0.05, 0.58, 0.02, sx * 0.355, 0.3, sz * 0.295);
        plank(0.02, 0.58, 0.05, sx * 0.385, 0.3, sz * 0.265);
      }
      plank(0.7, 0.04, 0.02, 0, 0.3, 0.297);
      pb.add('soil', xf(box(0.3, 0.12, 0.002), 0.1, 0.3, 0.3)); // stencil
      break;
    }
    case 'barrel': {
      const prof: Vec2[] = [[0.0, 0.0], [0.27, 0.0], [0.29, 0.012], [0.285, 0.03], [0.28, 0.05], [0.28, 0.27], [0.292, 0.285], [0.292, 0.3], [0.28, 0.315],
        [0.28, 0.565], [0.292, 0.58], [0.292, 0.595], [0.28, 0.61], [0.28, 0.83], [0.285, 0.85], [0.29, 0.868], [0.275, 0.88], [0.265, 0.872], [0.0, 0.868]];
      pb.add(getCarPaint(0x2e4a66), lathe(prof, 20));
      // rust patches on the shell (thin curved plates)
      for (let i = 0; i < 6; i++) {
        const a = r() * Math.PI * 2, y = 0.1 + r() * 0.7, h = 0.06 + r() * 0.15;
        pb.add('metalRust', xf(cylinder(0.2815, 0.2815, h, 4, { open: true, thetaStart: a, thetaLength: 0.3 + r() * 0.5 }), 0, y, 0));
      }
      pb.add('metalRust', xf(cylinder(0.282, 0.288, 0.05, 20, { open: true }), 0, 0.025, 0));
      pb.add('metalDark', xf(cylinder(0.03, 0.03, 0.012, 8), 0.16, 0.874, 0.05), xf(cylinder(0.02, 0.02, 0.012, 6), -0.17, 0.874, -0.04));
      break;
    }
    case 'dumpster': {
      const st = getCarPaint(0x3c5a40);
      const prof: Vec2[] = [[-0.55, 0.1], [0.42, 0.1], [0.58, 0.55], [0.6, 1.2], [-0.55, 1.2]];
      pb.add(st, xf(extrude(polyShape(prof), 1.8, { bevel: 0.02 }), 0, 0, 0, 0, -Math.PI / 2));
      // ribs
      for (const x of [-0.9, -0.3, 0.3, 0.9]) {
        pb.add(st, xf(bevelBox(0.06, 1.08, 0.05, 0.01), x, 0.66, -0.575));
        pb.add(st, boxBetween([x, 0.12, 0.45], [x, 0.56, 0.6], 0.06, 0.05, 0.01));
        pb.add(st, xf(bevelBox(0.06, 0.65, 0.05, 0.01), x, 0.88, 0.615));
      }
      for (const sx of [-1, 1]) {
        pb.add('metalDark', xf(bevelBox(0.06, 0.16, 1.1, 0.01), sx * 0.93, 0.62, 0.02)); // fork pockets
        pb.add('metalDark', xf(bevelBox(0.06, 0.06, 0.22, 0.01), sx * 0.93, 0.62, 0.02).translate(0, 0, 0));
      }
      pb.add('metalDark', xf(box(1.7, 0.1, 0.08), 0, 0.05, -0.45), xf(box(1.7, 0.1, 0.08), 0, 0.05, 0.35));
      pb.add(st, xf(bevelBox(1.84, 0.05, 0.06, 0.01), 0, 1.2, -0.58));
      // lids (one propped open)
      const lid = tinted('plasticBlack', 0x262826);
      pb.add(lid, xf(bevelBox(0.88, 0.05, 1.22, 0.015), -0.45, 1.24, 0.02, 0.04));
      const open = bevelBox(0.88, 0.05, 1.22, 0.015);
      xf(open, 0, 0, 0.61);
      xf(open, 0.45, 1.24, -0.58, -0.25);
      pb.add(lid, open);
      pb.add('metalRust', xf(box(0.5, 0.25, 0.004), 0.4, 0.3, 0.535, 0.33), xf(box(0.004, 0.4, 0.5), 0.92, 0.35, -0.2));
      pb.add('plasticWhite', xf(box(0.3, 0.12, 0.004), -0.5, 0.95, 0.622));
      pb.add(M.bagPlastic(), xf(lump(0.3, 0.22, 0.25, 5), 0.5, 1.15, -0.1));
      break;
    }
    case 'streetlight': {
      const pole = tinted('steelBrushed', 0xb8bab4);
      pb.add('concrete', xf(cylinder(0.26, 0.29, 0.3, 12), 0, 0.15, 0));
      pb.add(pole, xf(bevelBox(0.32, 0.03, 0.32, 0.006), 0, 0.315, 0));
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
        pb.add('metalRust', xf(cylinder(0.018, 0.018, 0.05, 6), sx * 0.12, 0.35, sz * 0.12));
      }
      pb.add(pole, xf(cylinder(0.075, 0.12, 7.6, 8), 0, 0.33 + 3.8, 0));
      pb.add(pole, xf(cylinder(0.13, 0.13, 0.25, 8), 0, 0.45, 0));
      pb.add(pole, xf(cylinder(0.08, 0.08, 0.1, 8), 0, 7.95, 0));
      pb.add(pole, tube([[0, 7.6, 0], [0, 7.95, 0.15], [0, 8.05, 0.7], [0, 8.05, 1.85]], (t) => 0.055 - t * 0.02, 14, 6));
      // cobra head luminaire
      const head = lathe([[0.0, 0], [0.12, 0.02], [0.17, 0.12], [0.18, 0.3], [0.15, 0.5], [0.08, 0.62], [0.0, 0.64]], 10);
      xf(head, 0, 0, 0, 0, 0, 0, 1, 1, 1);
      displace(head, (v) => { v.z *= 0.42; if (v.z < 0) v.z *= 0.4; });
      head.computeVertexNormals();
      pb.add(getCarPaint(0x9a9c98), xf(head, 0, 8.04, 1.7, Math.PI / 2, 0, 0));
      pb.add('glassDirty', xf(sphere(0.15, 10, 6, Math.PI * 2, Math.PI / 2), 0, 7.99, 2.0, Math.PI, 0, 0, 0.9, 0.3, 1.5));
      pb.add('metalDark', xf(bevelBox(0.18, 0.25, 0.12, 0.01), 0, 2.6, -0.14));
      pb.add('metalRust', xf(cylinder(0.121, 0.121, 0.4, 8, { open: true }), 0, 0.85, 0));
      pb.add('plasticWhite', xf(box(0.1, 0.18, 0.004), 0, 3.0, 0.103)); // pole tag
      anchors.push({ name: 'lightAnchor', key: 'lightAnchor', p: [0, 7.93, 2.0] });
      break;
    }
    case 'fence_wood': {
      const wd = M.greyWood();
      for (const sx of [-1, 1]) {
        pb.add(wd, xf(bevelBox(0.09, 1.85, 0.09, 0.008), sx * 1.155, 0.925, -0.03));
      }
      for (const y of [0.3, 1.5]) pb.add(wd, xf(bevelBox(2.22, 0.09, 0.04, 0.005), 0, y, -0.03));
      const n = 16;
      const bw = 2.4 / n;
      for (let i = 0; i < n; i++) {
        const x = -1.2 + bw * (i + 0.5);
        const rr = r();
        if (rr < 0.07) continue; // missing board
        const h = rr < 0.14 ? 0.9 + r() * 0.4 : 1.72 + r() * 0.06;
        const tilt = rr > 0.93 ? (r() - 0.5) * 0.3 : 0;
        const board = merge([
          bevelBox(bw - 0.012, h, 0.018, 0.003),
          xf(polygon([[-(bw - 0.012) / 2, h / 2, 0.009], [(bw - 0.012) / 2, h / 2, 0.009], [(bw - 0.012) / 2 - 0.03, h / 2 + 0.04, 0.009], [-(bw - 0.012) / 2 + 0.03, h / 2 + 0.04, 0.009]], true), 0, 0, 0),
        ]);
        pb.add(i % 5 === 2 ? tinted('wood', 0x7a7266) : wd, xf(board, x, 0.04 + h / 2, 0.03, 0, 0, tilt));
      }
      break;
    }
    case 'fence_chain': {
      const galv = tinted('steelBrushed', 0xd8d8d4);
      for (const sx of [-1, 1]) {
        pb.add(galv, xf(cylinder(0.03, 0.03, 1.9, 8), sx * 1.22, 0.95, 0));
        pb.add(galv, xf(sphere(0.035, 8, 4, Math.PI * 2, Math.PI / 2), sx * 1.22, 1.9, 0));
      }
      pb.add(galv, xf(cylinder(0.02, 0.02, 2.44, 6), 0, 1.84, 0, 0, 0, Math.PI / 2));
      pb.add(galv, xf(cylinder(0.003, 0.003, 2.44, 3), 0, 0.06, 0, 0, 0, Math.PI / 2));
      // chain-link diamond mesh (diagonal wires clipped to panel)
      const x0 = -1.19, x1 = 1.19, y0 = 0.06, y1 = 1.82, step = 0.075;
      const wires: THREE.BufferGeometry[] = [];
      for (const dir of [1, -1]) {
        for (let c = -(y1 - y0); c < x1 - x0; c += step) {
          // line: x = x0 + c + t, y = y0 + t  (dir 1)  or mirrored
          const t0 = Math.max(0, -c), t1 = Math.min(y1 - y0, x1 - x0 - c);
          if (t1 - t0 < 0.02) continue;
          let a: Vec3 = [x0 + c + t0, y0 + t0, 0], b: Vec3 = [x0 + c + t1, y0 + t1, 0];
          if (dir < 0) { a = [-a[0], a[1], 0.004]; b = [-b[0], b[1], 0.004]; }
          wires.push(boxBetween(a, b, 0.0035, 0.0035));
        }
      }
      // sag/dent: push some wires
      const mesh = merge(wires);
      displace(mesh, (v) => { v.z += Math.exp(-((v.x - 0.4) ** 2 + (v.y - 0.7) ** 2) * 6) * 0.05; });
      pb.add(galv, mesh);
      break;
    }
    case 'hydrant': {
      const paint = tinted('metalRust', 0xd0aa3a);
      pb.add(paint, lathe([[0, 0], [0.16, 0], [0.16, 0.03], [0.13, 0.04], [0.12, 0.08], [0.12, 0.5], [0.13, 0.52], [0.14, 0.56], [0.14, 0.6], [0.12, 0.62], [0.11, 0.7], [0.07, 0.76], [0, 0.77]], 16));
      for (let i = 0; i < 6; i++) { const a = (i / 6) * Math.PI * 2; pb.add('metalDark', xf(cylinder(0.012, 0.012, 0.03, 6), Math.cos(a) * 0.145, 0.045, Math.sin(a) * 0.145)); }
      pb.add('metalDark', xf(cylinder(0.035, 0.035, 0.06, 5), 0, 0.8, 0));
      for (const sx of [-1, 1]) {
        pb.add(paint, xf(cylinder(0.045, 0.05, 0.1, 10), sx * 0.15, 0.42, 0, 0, 0, Math.PI / 2));
        pb.add('metalDark', xf(cylinder(0.05, 0.05, 0.035, 5), sx * 0.21, 0.42, 0, 0, 0, Math.PI / 2));
      }
      pb.add(paint, xf(cylinder(0.065, 0.07, 0.1, 12), 0, 0.38, 0.15, Math.PI / 2));
      pb.add('metalDark', xf(cylinder(0.07, 0.07, 0.04, 5), 0, 0.38, 0.215, Math.PI / 2));
      pb.add('metalDark', tube([[0.2, 0.4, 0.04], [0.16, 0.33, 0.12], [0.06, 0.34, 0.2]], 0.005, 8, 4));
      break;
    }
    case 'mailbox': {
      const wd = M.greyWood();
      pb.add(wd, xf(bevelBox(0.09, 1.05, 0.09, 0.008), 0, 0.525, -0.1));
      pb.add(wd, xf(bevelBox(0.14, 0.04, 0.5, 0.006), 0, 1.03, 0.0));
      pb.add(wd, boxBetween([0, 0.75, -0.1], [0, 1.01, 0.12], 0.05, 0.04, 0.005));
      const s = new THREE.Shape();
      s.moveTo(-0.085, 0); s.lineTo(0.085, 0); s.lineTo(0.085, 0.12); s.absarc(0, 0.12, 0.085, 0, Math.PI, false); s.lineTo(-0.085, 0);
      const bodyMat = getCarPaint(0x45484a);
      pb.add(bodyMat, xf(extrude(s, 0.48, { bevel: 0.006, curveSegments: 8 }), 0, 1.05, 0.0));
      pb.add(getCarPaint(0x55585a), xf(extrude(s, 0.012, { bevel: 0.003, curveSegments: 8 }), 0, 1.05, 0.245, 0, 0, 0, 1.04, 1.04, 1));
      pb.add('metalDark', xf(box(0.04, 0.012, 0.025), 0, 1.2, 0.262));
      pb.add(getCarPaint(0xa02018), xf(box(0.006, 0.2, 0.03), 0.095, 1.18, -0.1), xf(box(0.006, 0.05, 0.07), 0.095, 1.25, -0.12));
      pb.add('metalRust', xf(box(0.004, 0.06, 0.2), -0.087, 1.08, 0.05));
      break;
    }
    case 'utility_pole': {
      const wd = tinted('woodDark', 0x5a4836);
      pb.add(wd, xf(cylinder(0.12, 0.16, 9.4, 10), 0, 4.7, 0));
      pb.add(wd, xf(bevelBox(2.4, 0.1, 0.09, 0.008), 0, 8.7, 0.13));
      for (const sx of [-1, 1]) pb.add('metalDark', boxBetween([0, 8.2, 0.12], [sx * 0.6, 8.66, 0.13], 0.03, 0.006));
      for (const x of [-1.1, -0.65, 0.65, 1.1]) {
        pb.add('metalDark', xf(cylinder(0.01, 0.01, 0.12, 6), x, 8.81, 0.13));
        pb.add(tinted('glassDirty', 0x6a8a7a), xf(lathe([[0.0, 0], [0.05, 0.0], [0.055, 0.03], [0.04, 0.05], [0.05, 0.07], [0.035, 0.11], [0, 0.12]], 10), x, 8.84, 0.13));
      }
      pb.add(tinted('plasticWhite', 0xcfcfc8), xf(cylinder(0.04, 0.04, 0.12, 8), 0, 9.42, 0));
      // transformer can
      const can = getCarPaint(0x7e8480);
      pb.add(can, xf(cylinder(0.24, 0.24, 0.72, 14), 0, 7.4, -0.43));
      pb.add(can, xf(cylinder(0.25, 0.25, 0.04, 14), 0, 7.78, -0.43), xf(cylinder(0.25, 0.25, 0.04, 14), 0, 7.04, -0.43));
      pb.add('metalDark', xf(bevelBox(0.2, 0.5, 0.06, 0.01), 0, 7.4, -0.17));
      pb.add(tinted('plasticWhite', 0x55605a), xf(cylinder(0.03, 0.035, 0.14, 8), 0.1, 7.87, -0.43), xf(cylinder(0.03, 0.035, 0.14, 8), -0.1, 7.87, -0.43));
      pb.add('metalRust', xf(cylinder(0.242, 0.242, 0.3, 6, { open: true, thetaStart: 1, thetaLength: 1.2 }), 0, 7.2, -0.43));
      // step bolts
      for (let i = 0; i < 10; i++) pb.add('metalDark', xf(cylinder(0.009, 0.009, 0.2, 4), 0, 2.6 + i * 0.45, 0, 0, i * Math.PI / 2 + Math.PI / 4, Math.PI / 2));
      pb.add('metalDark', xf(box(0.08, 0.1, 0.006), 0, 1.6, 0.16));
      pb.add('plasticWhite', xf(box(0.2, 0.28, 0.004), 0, 1.7, 0.158, 0, 0, 0.05)); // stapled flyer
      break;
    }
    case 'bench': {
      const iron = tinted('metalDark', 0x262624);
      const slats = tinted('wood', 0x7a6248);
      for (const sx of [-1, 1]) {
        const s: Vec2[] = [[-0.27, 0], [-0.22, 0], [-0.18, 0.4], [0.22, 0.4], [0.24, 0], [0.29, 0], [0.26, 0.44], [0.26, 0.6], [0.22, 0.62], [0.2, 0.46], [-0.18, 0.46], [-0.26, 0.85], [-0.31, 0.85], [-0.23, 0.44]];
        pb.add(iron, xf(extrude(polyShape(s), 0.04, { bevel: 0.006 }), sx * 0.8, 0, 0, 0, -Math.PI / 2));
      }
      for (let i = 0; i < 4; i++) pb.add(slats, xf(bevelBox(1.8, 0.035, 0.085, 0.008), 0, 0.46, 0.17 - i * 0.1));
      for (let i = 0; i < 3; i++) pb.add(slats, xf(bevelBox(1.8, 0.085, 0.03, 0.008), 0, 0.55 + i * 0.1, -0.205 - i * 0.022, -0.22));
      pb.add('metalDark', xf(box(1.58, 0.03, 0.03), 0, 0.2, -0.03));
      break;
    }
    case 'tire_pile': {
      const tyre = (): THREE.BufferGeometry => {
        const t = torus(0.24, 0.1, 10, 22);
        displace(t, (v) => { v.z *= 1.0; });
        return t;
      };
      const flat = (x: number, y: number, z: number) => pb.add('rubber', xf(tyre(), x, y, z, Math.PI / 2, 0, 0, 1, 1, 0.95));
      flat(-0.37, 0.1, -0.3); flat(0.36, 0.1, -0.3); flat(0.0, 0.1, 0.36);
      flat(-0.02, 0.29, -0.1); flat(0.05, 0.48, -0.08);
      pb.add('rubber', xf(tyre(), 0.4, 0.4, 0.3, 0.1, 0.6, 0.35));
      pb.add('soil', xf(cylinder(0.14, 0.14, 0.004, 10), -0.02, 0.2, -0.1));
      break;
    }
    case 'trash_bags': {
      const bag = M.bagPlastic();
      const spots: [number, number, number, number][] = [[-0.35, -0.2, 0.3, 1], [0.25, -0.25, 0.33, 2], [0.0, 0.25, 0.28, 3], [-0.38, 0.3, 0.24, 4], [0.05, -0.05, 0.25, 5]];
      for (const [x, z, s, sd] of spots) {
        const hgt = sd === 5 ? 0.42 : 0.3 + s * 0.2;
        const g = lump(s, hgt / 2, s * 0.9, sd, 0.82);
        xf(g, x, hgt / 2 + (sd === 5 ? 0.18 : 0), z, 0, sd, 0);
        displace(g, (v) => { v.y = Math.max(v.y, 0.0); });
        pb.add(sd === 3 ? tinted('plasticBlack', 0x3a4a3a) : bag, g);
        pb.add(sd === 3 ? tinted('plasticBlack', 0x3a4a3a) : bag, xf(cylinder(0.01, 0.04, 0.08, 6), x, hgt + (sd === 5 ? 0.18 : 0) + 0.02, z, 0.2, 0, 0.3));
      }
      pb.add('mud', xf(box(0.5, 0.003, 0.3), 0.1, 0.0015, 0.35));
      break;
    }
    case 'shop_shelf': {
      const st = getCarPaint(0xc8c6be);
      pb.add(st, xf(bevelBox(1.25, 0.12, 0.6, 0.01), 0, 0.06, 0));
      pb.add(st, xf(box(1.21, 1.68, 0.04), 0, 0.96, -0.27));
      for (const sx of [-1, 1]) pb.add(st, xf(bevelBox(0.03, 1.8, 0.06, 0.006), sx * 0.61, 0.9, -0.27));
      const lv = [0.12, 0.5, 0.86, 1.22, 1.56];
      for (const y of lv.slice(1)) {
        pb.add(st, xf(bevelBox(1.2, 0.025, 0.5, 0.006), 0, y, -0.0));
        pb.add(tinted('plasticWhite', 0xb8b0a0), xf(box(1.2, 0.035, 0.005), 0, y - 0.005, 0.252));
      }
      const pal = [0xa83a2a, 0xd0b050, 0x3a5a8a, 0x5a7a3a, 0xc8c0b0];
      for (let li = 0; li < lv.length; li++) {
        let x = -0.58;
        const base = lv[li] + (li === 0 ? 0.0 : 0.0125);
        while (x < 0.56) {
          const k = r();
          if (k < 0.3) { x += 0.08 + r() * 0.15; continue; } // looted gap
          const c = tinted('plasticWhite', pal[Math.floor(r() * pal.length)]);
          if (x > 0.4) break;
          if (k < 0.6) { // cans row
            const rr = 0.035;
            for (let j = 0; j < 3; j++) pb.add(c, xf(cylinder(rr, rr, 0.11, 8), x + rr, base + 0.055, 0.17 - j * 0.08));
            x += rr * 2 + 0.01;
          } else if (k < 0.92) { // cereal boxes
            const bw = 0.06, bh = 0.2 + r() * 0.08;
            pb.add(c, xf(bevelBox(bw, bh, 0.18, 0.003), x + bw / 2, base + bh / 2, 0.12));
            x += bw + 0.008;
          } else { // knocked-over box
            pb.add(c, xf(bevelBox(0.2, 0.06, 0.15, 0.003), x + 0.1, base + 0.03, 0.15, 0, 0.3));
            x += 0.2;
          }
        }
      }
      break;
    }
    case 'checkout': {
      const lam = tinted('plasticWhite', 0x9a9488);
      pb.add(lam, xf(bevelBox(1.8, 0.84, 0.6, 0.01), 0, 0.46, 0.1));
      pb.add('plasticBlack', xf(box(1.76, 0.08, 0.56), 0, 0.04, 0.1));
      pb.add('belt', xf(bevelBox(1.0, 0.04, 0.46, 0.015), -0.36, 0.9, 0.1));
      pb.add('steelBrushed', xf(bevelBox(0.72, 0.03, 0.56, 0.006), 0.52, 0.895, 0.1));
      pb.add('glassDirty', xf(box(0.3, 0.004, 0.25), 0.5, 0.912, 0.1)); // scanner glass
      // cashier side (−Z): register
      pb.add('plasticBlack', xf(bevelBox(0.4, 0.12, 0.35, 0.01), 0.5, 0.96, -0.12));
      pb.add('plasticBlack', xf(bevelBox(0.3, 0.06, 0.12, 0.008), 0.5, 1.04, -0.2, -0.4));
      pb.add('plasticBlack', cylinderBetween([0.2, 0.9, -0.12], [0.2, 1.0, -0.12], 0.012, 0.012, 6), xf(bevelBox(0.28, 0.2, 0.03, 0.01), 0.2, 0.98, -0.12, -0.2));
      pb.add('glassDirty', xf(box(0.24, 0.16, 0.004), 0.2, 0.98, -0.103, -0.2));
      pb.add(tinted('plasticBlack', 0x3a3a3a), xf(bevelBox(0.08, 0.14, 0.05, 0.01), -0.1, 0.97, 0.36, -0.3));
      pb.add('metalDark', xf(bevelBox(0.45, 0.12, 0.38, 0.008), 0.5, 0.84, -0.13)); // cash drawer (open)
      pb.add(tinted('plasticWhite', 0x3a6a3a), xf(box(0.3, 0.004, 0.1), -0.6, 0.78, -0.21, 0.3)); // scattered bills
      break;
    }
    case 'hospital_bed': {
      const st = tinted('steelBrushed', 0xe0e0dc);
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
        pb.add(st, xf(cylinder(0.02, 0.02, 0.35, 6), sx * 0.42, 0.25, sz * 0.92));
        pb.add('rubber', xf(cylinder(0.05, 0.05, 0.035, 10), sx * 0.42, 0.05, sz * 0.92, 0, 0, Math.PI / 2));
        pb.add('metalDark', xf(box(0.03, 0.06, 0.06), sx * 0.42, 0.09, sz * 0.92));
      }
      pb.add(st, xf(bevelBox(0.9, 0.08, 1.95, 0.01), 0, 0.45, 0));
      pb.add(st, xf(box(0.8, 0.06, 1.0), 0, 0.33, 0));
      // mattress: flat foot section + raised head section
      const mat = tinted('fabric', 0x6a8494);
      pb.add(mat, xf(bevelBox(0.86, 0.13, 1.2, 0.04), 0, 0.555, 0.35));
      const headSec = bevelBox(0.86, 0.13, 0.72, 0.04);
      xf(headSec, 0, 0, -0.36);
      xf(headSec, 0, 0.555, -0.25, -0.5);
      pb.add(mat, headSec);
      pb.add(tinted('fabric', 0xd8d4c8), xf(softBox(0.56, 0.1, 0.32, 0.045, 3, 0.01, 0.03), 0, 0.84, -0.78, -0.5));
      const panel = tinted('plasticWhite', 0xd8d6cf);
      pb.add(panel, xf(bevelBox(0.96, 0.5, 0.05, 0.02), 0, 0.82, -1.05), xf(bevelBox(0.96, 0.32, 0.05, 0.02), 0, 0.7, 1.05));
      for (const sx of [-1, 1]) {
        pb.add(st, tube([[sx * 0.47, 0.62, -0.6], [sx * 0.47, 0.82, -0.55], [sx * 0.47, 0.82, 0.1], [sx * 0.47, 0.62, 0.15]], 0.012, 12, 6, {}, false));
        pb.add(st, cylinderBetween([sx * 0.47, 0.72, -0.58], [sx * 0.47, 0.72, 0.13], 0.008, 0.008, 5));
      }
      pb.add('soil', xf(box(0.3, 0.003, 0.3), 0.15, 0.622, 0.5));
      break;
    }
    case 'gas_pump': {
      const body = getCarPaint(0xd0ccc0);
      const red = getCarPaint(0x9a2a22);
      pb.add('concrete', xf(bevelBox(0.95, 0.15, 0.6, 0.02), 0, 0.075, 0));
      pb.add(body, xf(bevelBox(0.8, 1.35, 0.42, 0.02), 0, 0.825, 0));
      pb.add(red, xf(bevelBox(0.84, 0.3, 0.46, 0.02), 0, 1.65, 0));
      pb.add('plasticWhite', xf(box(0.6, 0.12, 0.004), 0, 1.66, 0.232));
      for (const sz of [-1, 1]) {
        pb.add('plasticBlack', xf(bevelBox(0.6, 0.38, 0.02, 0.01), 0, 1.22, sz * 0.215));
        pb.add('glassDirty', xf(box(0.42, 0.12, 0.004), 0, 1.3, sz * 0.227));
        for (let i = 0; i < 3; i++) pb.add('plasticWhite', xf(box(0.12, 0.05, 0.004), -0.2 + i * 0.2, 1.1, sz * 0.227));
        pb.add('metalDark', xf(bevelBox(0.62, 0.4, 0.015, 0.006), 0, 0.5, sz * 0.212));
        pb.add('metalRust', xf(box(0.2, 0.08, 0.004), 0.15, 0.3, sz * 0.221));
      }
      for (const sx of [-1, 1]) {
        pb.add('metalDark', xf(bevelBox(0.06, 0.18, 0.12, 0.01), sx * 0.43, 1.0, 0.1));
        pb.add('plasticBlack', xf(bevelBox(0.04, 0.12, 0.05, 0.01), sx * 0.44, 1.02, 0.1, 0.3));
        pb.add('steelBrushed', cylinderBetween([sx * 0.44, 1.0, 0.13], [sx * 0.44, 0.96, 0.21], 0.008, 0.006, 6));
        pb.add('rubber', tube([[sx * 0.42, 1.5, 0.0], [sx * 0.46, 1.2, 0.12], [sx * 0.46, 0.6, 0.15], [sx * 0.46, 0.4, 0.05], [sx * 0.44, 0.95, 0.06]], 0.012, 20, 6));
      }
      break;
    }
    case 'road_barrier': {
      const prof: Vec2[] = [[-0.3, 0], [0.3, 0], [0.3, 0.075], [0.18, 0.25], [0.075, 0.81], [-0.075, 0.81], [-0.18, 0.25], [-0.3, 0.075]];
      pb.add('concrete', xf(extrude(polyShape(prof), 2.98, { bevel: 0.015 }), 0, 0, 0, 0, Math.PI / 2));
      for (const x of [-0.9, 0.9]) pb.add('plasticBlack', xf(box(0.3, 0.08, 0.62), x, 0.04, 0));
      pb.add(tinted('concrete', 0x5e5a50), xf(box(2.96, 0.08, 0.004), 0, 0.04, 0.302));
      pb.add('hazard', xf(box(0.6, 0.12, 0.004), -0.6, 0.6, 0.14, -0.13), xf(box(0.6, 0.12, 0.004), 0.6, 0.6, 0.14, -0.13));
      pb.add('metalRust', xf(cylinder(0.02, 0.02, 0.02, 6), 1.48, 0.4, 0, 0, 0, Math.PI / 2));
      break;
    }
    case 'sandbags': {
      const bag = M.burlap();
      let sd = 1;
      for (let row = 0; row < 5; row++) {
        const n = row % 2 === 0 ? 3 : 2;
        const off = row % 2 === 0 ? 0 : 0.0;
        for (let i = 0; i < n; i++) for (const z of [-0.15, 0.15]) {
          if (row === 4 && z < 0) continue;
          const x = (i - (n - 1) / 2) * 0.52 + off;
          pb.add(bag, xf(lump(0.25, 0.075, 0.14, sd++, 0.6, 8, 6), x, 0.085 + row * 0.13, z + (row === 4 ? -0.1 : 0), 0, (r() - 0.5) * 0.15, 0));
        }
      }
      break;
    }
    case 'workbench': {
      const top = tinted('wood', 0xa07a50);
      const frame = M.oldWood();
      for (let i = 0; i < 6; i++) pb.add(top, xf(bevelBox(1.8, 0.05, 0.12, 0.004), 0, 0.875, -0.3 + i * 0.12 + 0.0));
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) pb.add(frame, xf(bevelBox(0.08, 0.85, 0.08, 0.006), sx * 0.82, 0.425, sz * 0.31));
      pb.add(frame, xf(bevelBox(1.66, 0.025, 0.6, 0.004), 0, 0.2, 0));
      for (const sz of [-1, 1]) pb.add(frame, xf(box(1.56, 0.08, 0.04), 0, 0.8, sz * 0.31));
      // pegboard back
      pb.add(tinted('wood', 0x8a6e4a), xf(box(1.7, 0.7, 0.012), 0, 1.25, -0.35));
      for (const sx of [-1, 1]) pb.add(frame, xf(bevelBox(0.06, 0.75, 0.04, 0.005), sx * 0.85, 1.25, -0.35));
      pb.add(frame, xf(bevelBox(1.76, 0.04, 0.04, 0.005), 0, 1.6, -0.35));
      // tools on pegboard
      for (let i = 0; i < 4; i++) {
        const x = -0.7 + i * 0.09;
        pb.add('steelBrushed', xf(bevelBox(0.018, 0.22 - i * 0.03, 0.006, 0.002), x, 1.3, -0.335), xf(torus(0.018, 0.006, 4, 8), x, 1.42 - i * 0.015, -0.335));
      }
      pb.add('woodDark', xf(cylinder(0.012, 0.012, 0.3, 6), -0.2, 1.25, -0.33)); pb.add('metalDark', xf(bevelBox(0.03, 0.03, 0.11, 0.004), -0.2, 1.41, -0.33, 0, Math.PI / 2));
      pb.add('plasticBlack', xf(bevelBox(0.04, 0.2, 0.012, 0.004), 0.1, 1.28, -0.33), xf(bevelBox(0.012, 0.012, 0.012, 0.002), 0.1, 1.38, -0.33));
      pb.add(getCarPaint(0xa02a20), xf(bevelBox(0.5, 0.14, 0.18, 0.01), 0.5, 1.2, -0.25)); // toolbox on shelf? (back left)
      pb.add('metalDark', xf(box(0.2, 0.02, 0.02), 0.5, 1.29, -0.25));
      pb.add(getCarPaint(0xa02a20), xf(box(0.6, 0.02, 0.2), 0.5, 1.12, -0.26));
      // vise
      const vise = tinted('metalDark', 0x3a4a5a);
      pb.add(vise, xf(bevelBox(0.14, 0.1, 0.18, 0.01), -0.62, 0.95, 0.22), xf(bevelBox(0.14, 0.1, 0.04, 0.01), -0.62, 0.95, 0.35));
      pb.add('steelBrushed', xf(cylinder(0.008, 0.008, 0.2, 6), -0.62, 0.93, 0.36, 0, 0, Math.PI / 2));
      pb.add(tinted('fabric', 0x8a3a2a), xf(lump(0.12, 0.02, 0.1, 9), 0.2, 0.91, 0.1));
      pb.add(getCarPaint(0x6a7a4a), xf(cylinder(0.05, 0.05, 0.12, 10), 0.75, 0.96, 0.15));
      break;
    }
    case 'campfire': {
      for (let i = 0; i < 10; i++) {
        const a = (i / 10) * Math.PI * 2 + r() * 0.2;
        const rr = 0.09 + r() * 0.04;
        pb.add('rock', xf(stone(rr, i + 3, 1), Math.cos(a) * 0.38, rr * 0.85, Math.sin(a) * 0.38, 0, r() * 3));
      }
      pb.add(tinted('dirt', 0x2e2b28), xf(cylinder(0.33, 0.36, 0.03, 14), 0, 0.015, 0));
      for (let i = 0; i < 5; i++) {
        const a = (i / 5) * Math.PI * 2 + 0.3;
        const base: Vec3 = [Math.cos(a) * 0.28, 0.04, Math.sin(a) * 0.28];
        const top: Vec3 = [Math.cos(a) * 0.03, 0.42, Math.sin(a) * 0.03];
        pb.add('bark', cylinderBetween(base, [base[0] * 0.5 + top[0] * 0.5, 0.23, base[2] * 0.5 + top[2] * 0.5], 0.04, 0.035, 7));
        pb.add(M.charred(), cylinderBetween([base[0] * 0.5 + top[0] * 0.5, 0.23, base[2] * 0.5 + top[2] * 0.5], top, 0.035, 0.02, 7));
      }
      for (let i = 0; i < 7; i++) pb.add('emissiveWarm', xf(stone(0.025 + r() * 0.02, i + 20, 0), (r() - 0.5) * 0.25, 0.035, (r() - 0.5) * 0.25));
      pb.add(M.charred(), cylinderBetween([-0.2, 0.05, 0.05], [0.18, 0.06, -0.08], 0.035, 0.03, 6));
      anchors.push({ name: 'fireAnchor', key: 'fireAnchor', p: [0, 0.06, 0] });
      break;
    }
    case 'rain_collector': {
      const wd = M.oldWood();
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
        pb.add(wd, xf(bevelBox(0.07, 1.6, 0.07, 0.006), sx * 0.46, 0.8, sz * 0.46));
      }
      for (let i = 0; i < 5; i++) pb.add(wd, xf(bevelBox(0.98, 0.03, 0.17, 0.004), 0, 0.42, -0.4 + i * 0.2));
      for (const sz of [-1, 1]) pb.add(wd, xf(box(0.86, 0.08, 0.04), 0, 0.37, sz * 0.46));
      pb.add(wd, boxBetween([-0.46, 0.05, 0.46], [0.46, 0.37, 0.46], 0.05, 0.03));
      // barrel
      const blue = tinted('plasticWhite', 0x2a5a8a);
      pb.add(blue, lathe([[0, 0.435], [0.27, 0.435], [0.29, 0.46], [0.295, 0.7], [0.29, 1.0], [0.295, 1.22], [0.27, 1.28], [0.0, 1.28]], 18));
      pb.add('steelBrushed', xf(cylinder(0.015, 0.015, 0.08, 6), 0, 0.52, 0.31, Math.PI / 2), xf(box(0.04, 0.012, 0.012), 0, 0.54, 0.34));
      // tarp funnel
      const tarp = tinted('fabric', 0x46583c);
      const c = 0.48, top = 1.56, rim = 1.3;
      const corners: Vec3[] = [[-c, top, -c], [c, top - 0.04, -c], [c, top, c], [-c, top - 0.03, c]];
      const mids = corners.map((p, i) => { const q = corners[(i + 1) % 4]; return [(p[0] + q[0]) / 2 * 0.95, (p[1] + q[1]) / 2 - 0.12, (p[2] + q[2]) / 2 * 0.95] as Vec3; });
      const hole: Vec3 = [0, rim + 0.02, 0];
      for (let i = 0; i < 4; i++) {
        pb.add(tarp, polygon([corners[i], mids[i], hole], true), polygon([mids[i], corners[(i + 1) % 4], hole], true));
      }
      for (const p of corners) pb.add(tinted('fabric', 0xa08c60), cylinderBetween(p, [Math.sign(p[0]) * 0.46, p[1] + 0.03, Math.sign(p[2]) * 0.46], 0.005, 0.005, 3));
      pb.add('metal', xf(cylinder(0.1, 0.12, 0.1, 10), 0, 1.32, 0));
      break;
    }
    case 'sleeping_bag': {
      pb.add(tinted('rubber', 0x3a4a5a), xf(bevelBox(0.62, 0.015, 1.9, 0.006), 0, 0.0075, 0));
      const bag = new THREE.BoxGeometry(0.76, 0.12, 1.86, 6, 2, 14);
      displace(bag, (v) => {
        const taper = 1 - Math.max(0, v.z) / 1.86 * 0.35;
        v.x *= taper;
        v.y += fbm3(v.x * 3, 0, v.z * 3, 3) * 0.04 + 0.02;
        v.y = Math.max(v.y, -0.05);
      });
      bag.computeVertexNormals();
      const fab = tinted('fabric', 0x5a6238);
      pb.add(fab, xf(bag, 0, 0.09, 0.08));
      pb.add(tinted('fabric', 0x8a3a24), xf(box(0.74, 0.01, 1.2), 0, 0.12, 0.35, 0.0, 0, 0.0)); // lining fold
      pb.add(fab, xf(cylinder(0.08, 0.08, 0.7, 10), 0, 0.08, -0.92, 0, 0, Math.PI / 2));
      pb.add(tinted('fabric', 0x8a8070), xf(softBox(0.44, 0.09, 0.26, 0.04, 2, 0.01, 0.02), 0, 0.17, -0.82));
      pb.add('plasticBlack', xf(box(0.01, 0.01, 1.6), 0.36, 0.13, 0.1));
      break;
    }
    case 'wood_crate_storage': {
      const wd = tinted('wood', 0x8a6a44);
      for (let i = 0; i < 4; i++) {
        const y = 0.1 + i * 0.21;
        for (const sz of [-1, 1]) pb.add(wd, xf(bevelBox(1.12, 0.2, 0.03, 0.004), 0, y, sz * 0.37));
        for (const sx of [-1, 1]) pb.add(wd, xf(bevelBox(0.03, 0.2, 0.72, 0.004), sx * 0.555, y, 0));
      }
      // lid
      for (let i = 0; i < 5; i++) pb.add(wd, xf(bevelBox(1.18, 0.04, 0.155, 0.006), 0, 0.92, -0.32 + i * 0.16));
      pb.add(wd, xf(box(1.1, 0.04, 0.06), 0, 0.88, 0.0));
      const iron = tinted('metalDark', 0x2a2826);
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
        pb.add(iron, xf(bevelBox(0.08, 0.86, 0.08, 0.006), sx * 0.56, 0.43, sz * 0.375));
        pb.add('metalRust', ...[0.1, 0.43, 0.76].map((y) => xf(sphere(0.008, 5, 3), sx * 0.601, y, sz * 0.38)));
      }
      for (const sx of [-1, 1]) {
        pb.add(iron, xf(bevelBox(1.22, 0.05, 0.004, 0.001), 0, 0.5, sx * 0.403));
        pb.add(tinted('fabric', 0x9a8460), tube([[sx * 0.575, 0.62, -0.12], [sx * 0.63, 0.56, 0], [sx * 0.575, 0.62, 0.12]], 0.012, 8, 5));
        pb.add(iron, xf(bevelBox(0.12, 0.04, 0.006, 0.002), sx * 0.35, 0.86, -0.4));
      }
      pb.add(iron, xf(bevelBox(0.06, 0.14, 0.012, 0.003), 0, 0.84, 0.405));
      pb.add('steelBrushed', xf(bevelBox(0.06, 0.07, 0.025, 0.006), 0, 0.73, 0.42), xf(torus(0.02, 0.005, 4, 10, Math.PI), 0, 0.765, 0.42));
      break;
    }
    case 'medical_cabinet': {
      const st = getCarPaint(0xd4d2ca);
      pb.add(st, xf(bevelBox(0.8, 1.72, 0.42, 0.01), 0, 0.9, -0.02));
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) pb.add('metalDark', xf(cylinder(0.02, 0.025, 0.04, 6), sx * 0.35, 0.02, sz * 0.17));
      pb.add('metalDark', xf(box(0.76, 0.04, 0.38), 0, 0.06, -0.02));
      for (const sx of [-1, 1]) {
        pb.add(st, xf(panelFront(0.38, 0.7, 0.02, 0.03), sx * 0.195, 0.45, 0.2));
        // glass door upper
        const x = sx * 0.195;
        pb.add(st, xf(box(0.38, 0.04, 0.02), x, 0.84, 0.2), xf(box(0.38, 0.04, 0.02), x, 1.72, 0.2), xf(box(0.04, 0.88, 0.02), x - sx * 0.17, 1.28, 0.2), xf(box(0.04, 0.88, 0.02), x + sx * 0.17, 1.28, 0.2));
        pb.add('glass', xf(box(0.3, 0.84, 0.006), x, 1.28, 0.2));
        pb.add('steelBrushed', xf(handleBar(0.1, 0.005, 0.02), sx * 0.04, 1.2, 0.21, 0, 0, Math.PI / 2), xf(handleBar(0.1, 0.005, 0.02), sx * 0.04, 0.7, 0.21, 0, 0, Math.PI / 2));
      }
      for (const y of [1.08, 1.36]) pb.add('glass', xf(box(0.74, 0.008, 0.34), 0, y, -0.02));
      const sup = [0xd8d4c8, 0x9a3a2a, 0x3a6a9a, 0xc89a3a];
      for (const y of [0.84, 1.084, 1.364]) {
        let x = -0.34;
        while (x < 0.32) {
          if (r() < 0.35) { x += 0.08; continue; }
          const c = tinted('plasticWhite', sup[Math.floor(r() * sup.length)]);
          if (r() < 0.5) { pb.add(c, xf(cylinder(0.025, 0.025, 0.12, 8), x + 0.025, y + 0.064, -0.05 + r() * 0.15)); x += 0.06; }
          else { const bw = 0.08 + r() * 0.06; pb.add(c, xf(bevelBox(bw, 0.1, 0.12, 0.003), x + bw / 2, y + 0.054, -0.05)); x += bw + 0.01; }
        }
      }
      const red = tinted('plasticWhite', 0xb02a20);
      pb.add(red, xf(box(0.1, 0.03, 0.004), 0, 1.78, 0.192), xf(box(0.03, 0.1, 0.004), 0, 1.78, 0.192));
      pb.add(st, xf(bevelBox(0.8, 0.1, 0.42, 0.01), 0, 1.77, -0.02));
      pb.add(red, xf(box(0.1, 0.03, 0.004), 0, 1.77, 0.192), xf(box(0.03, 0.1, 0.004), 0, 1.77, 0.192));
      break;
    }
    case 'gun_locker': {
      const st = getCarPaint(0x2a3028);
      pb.add(st, xf(bevelBox(0.6, 1.58, 0.44, 0.015), 0, 0.79, -0.03));
      pb.add(st, xf(panelFront(0.56, 1.52, 0.05, 0.05), 0, 0.8, 0.205));
      pb.add('metalDark', xf(cylinder(0.04, 0.04, 0.02, 14), 0.12, 1.0, 0.25, Math.PI / 2));
      for (let i = 0; i < 3; i++) {
        const a = (i / 3) * Math.PI * 2;
        pb.add('steelBrushed', cylinderBetween([0.12, 1.0, 0.26], [0.12 + Math.cos(a) * 0.09, 1.0 + Math.sin(a) * 0.09, 0.27], 0.006, 0.006, 5));
        pb.add('plasticBlack', xf(sphere(0.012, 6, 4), 0.12 + Math.cos(a) * 0.09, 1.0 + Math.sin(a) * 0.09, 0.27));
      }
      pb.add('metalDark', xf(cylinder(0.035, 0.035, 0.02, 14), 0.12, 1.2, 0.24, Math.PI / 2));
      pb.add('plasticWhite', xf(box(0.005, 0.012, 0.002), 0.12, 1.23, 0.251));
      for (const y of [0.3, 1.3]) pb.add('metalDark', xf(cylinder(0.015, 0.015, 0.1, 8), -0.29, y, 0.21));
      pb.add('metalRust', xf(box(0.2, 0.05, 0.004), 0.0, 0.12, 0.233), xf(box(0.004, 0.3, 0.2), 0.302, 0.25, 0.0));
      break;
    }
    case 'tool_rack': {
      const wd = M.oldWood();
      pb.add(tinted('wood', 0x8a6e4a), xf(box(1.44, 1.2, 0.015), 0, 1.15, -0.1));
      for (const sx of [-1, 1]) {
        pb.add(wd, xf(bevelBox(0.06, 1.8, 0.06, 0.006), sx * 0.72, 0.9, -0.1));
        pb.add(wd, xf(bevelBox(0.08, 0.06, 0.3, 0.006), sx * 0.72, 0.03, 0.0));
      }
      pb.add(wd, xf(bevelBox(1.5, 0.05, 0.05, 0.005), 0, 1.78, -0.1));
      // shovel
      pb.add(hickory(), xf(cylinder(0.016, 0.016, 1.0, 8), -0.5, 1.05, -0.06));
      pb.add('metalDark', xf(bevelBox(0.22, 0.28, 0.01, 0.004), -0.5, 0.42, -0.06), xf(cylinder(0.02, 0.016, 0.12, 8), -0.5, 0.6, -0.06));
      pb.add('plasticBlack', xf(bevelBox(0.12, 0.03, 0.03, 0.006), -0.5, 1.56, -0.06));
      // rake
      pb.add(hickory(), xf(cylinder(0.014, 0.014, 1.1, 8), -0.2, 1.0, -0.06));
      pb.add('metalDark', xf(bevelBox(0.35, 0.02, 0.02, 0.003), -0.2, 1.56, -0.06));
      for (let i = 0; i < 9; i++) pb.add('metalDark', xf(box(0.006, 0.07, 0.006), -0.36 + i * 0.04, 1.6, -0.06));
      // saw
      pb.add('steelBrushed', xf(polygon([[0.0, 1.5, -0.08], [0.08, 1.5, -0.08], [0.36, 1.42, -0.08], [0.36, 1.37, -0.08], [0.0, 1.4, -0.08]], true), 0, 0, 0));
      pb.add('woodDark', xf(bevelBox(0.12, 0.1, 0.02, 0.006), -0.04, 1.46, -0.08));
      // hammer, wrenches, axe
      pb.add(hickory(), xf(cylinder(0.012, 0.012, 0.32, 6), 0.2, 1.1, -0.07));
      pb.add('metalDark', xf(bevelBox(0.12, 0.03, 0.03, 0.005), 0.2, 1.27, -0.07));
      for (let i = 0; i < 3; i++) pb.add('steelBrushed', xf(bevelBox(0.016, 0.2 + i * 0.04, 0.006, 0.002), 0.36 + i * 0.06, 1.0, -0.08));
      pb.add(hickory(), xf(cylinder(0.014, 0.014, 0.7, 6), 0.6, 0.95, -0.07));
      pb.add('metalDark', xf(extrude(polyShape([[0, 0], [0.16, 0.04], [0.17, -0.07], [0.0, -0.03]]), 0.012, { bevel: 0.002 }), 0.6, 1.33, -0.07));
      break;
    }
    case 'cardboard_boxes': {
      const cb = M.cardboard();
      const tape = tinted('plasticWhite', 0xc8b088);
      const boxes: [number, number, number, number, number, number, number][] = [
        [0.55, 0.42, 0.45, -0.2, 0, -0.15, 0.05], [0.4, 0.35, 0.36, 0.28, 0, 0.18, -0.12], [0.45, 0.32, 0.38, -0.15, 0.42, -0.12, 0.2], [0.3, 0.2, 0.25, 0.3, 0, -0.25, 0.4],
      ];
      for (const [w, h, d, x, y, z, ry] of boxes) {
        const g = bevelBox(w, h, d, 0.006);
        displace(g, (v) => { v.x += fbm3(v.y * 4, v.z * 4, x, 2) * 0.01; });
        pb.add(cb, xf(g, x, y + h / 2, z, 0, ry));
        pb.add(tape, xf(box(0.05, 0.003, d + 0.004), x, y + h + 0.001, z, 0, ry));
      }
      // open flaps on the top box
      const [w, h, d, x, y, z, ry] = boxes[2];
      for (const s of [-1, 1]) {
        const f = box(w, 0.004, d / 2);
        xf(f, 0, 0, s * d / 4);
        xf(f, 0, 0, 0, s * 0.9);
        xf(f, x, y + h, z + s * d / 2, 0, ry);
        pb.add(cb, f);
      }
      break;
    }
    case 'traffic_cone': {
      pb.add(tinted('rubber', 0x262626), xf(bevelBox(0.36, 0.03, 0.36, 0.01), 0, 0.015, 0));
      pb.add(M.orange(), lathe([[0.15, 0.03], [0.14, 0.05], [0.025, 0.71], [0.0, 0.715]], 16));
      pb.add('plasticWhite', lathe([[0.104, 0.27], [0.083, 0.39]], 16), lathe([[0.065, 0.48], [0.05, 0.56]], 16));
      break;
    }
    case 'pallet': {
      const wd = tinted('wood', 0xa8946c);
      for (let i = 0; i < 7; i++) pb.add(wd, xf(bevelBox(1.2, 0.022, 0.1, 0.003), 0, 0.133, -0.45 + i * 0.15));
      for (const x of [-0.55, 0, 0.55]) {
        pb.add(wd, xf(bevelBox(0.1, 0.022, 1.0, 0.003), x, 0.111, 0));
        for (const z of [-0.45, 0, 0.45]) pb.add(wd, xf(bevelBox(0.1, 0.078, 0.145, 0.004), x, 0.061, z * (z === 0 ? 1 : 0.94)));
      }
      for (const z of [-0.45, 0, 0.45]) pb.add(wd, xf(bevelBox(1.2, 0.022, 0.1, 0.003), 0, 0.011, z));
      break;
    }
  }
  return { parts: pb.build(), anchors };
}
const hickory = () => tinted('wood', 0xb89064);

const propCache = new Map<PropId, PropDef>();

/** Build a prop (shared cached geometry). Base y=0, centred on x/z, front toward +Z. */
export function buildProp(id: PropId): THREE.Object3D {
  let def = propCache.get(id);
  if (!def) { def = define(id); propCache.set(id, def); }
  const g = partsToGroup(def.parts);
  g.name = `prop:${id}`;
  for (const a of def.anchors) {
    const o = anchor(a.name, a.p[0], a.p[1], a.p[2]);
    g.add(o);
    g.userData[a.key] = o;
  }
  return g;
}

/** Access to the cached merged parts of a prop (e.g. to build InstancedMeshes). */
export function getPropParts(id: PropId): Part[] {
  let def = propCache.get(id);
  if (!def) { def = define(id); propCache.set(id, def); }
  return def.parts;
}

// ===========================================================================
// Cars
// ===========================================================================

export type CarVariant = 'sedan' | 'hatchback' | 'pickup' | 'van' | 'police' | 'wreck';

interface CarSpec {
  L: number; W: number; wheelR: number; wheelZ: [number, number]; track: number; sill: number;
  /** body top outline, front → rear, (z, y) */
  top: Vec2[];
  /** greenhouse: A base z, roof front z, roof rear z, C base z, belt y, roof y, tumblehome */
  cabin?: { zA: number; zB: number; zC: number; zD: number; belt: number; roof: number; tumble: number; bz?: number };
  bed?: { z0: number; z1: number; top: number };
  vanWindows?: boolean;
}

const SPECS: Record<'sedan' | 'hatchback' | 'pickup' | 'van', CarSpec> = {
  sedan: {
    L: 4.7, W: 1.8, wheelR: 0.32, wheelZ: [1.42, -1.33], track: 1.52, sill: 0.3,
    top: [[2.35, 0.62], [2.33, 0.74], [2.15, 0.82], [1.2, 0.92], [0.85, 0.95], [-1.35, 0.97], [-1.95, 0.99], [-2.3, 0.95], [-2.36, 0.8], [-2.36, 0.6]],
    cabin: { zA: 0.88, zB: 0.12, zC: -0.95, zD: -1.42, belt: 0.95, roof: 1.42, tumble: 0.14 },
  },
  hatchback: {
    L: 4.1, W: 1.74, wheelR: 0.31, wheelZ: [1.3, -1.2], track: 1.48, sill: 0.3,
    top: [[2.05, 0.6], [2.03, 0.72], [1.85, 0.82], [1.1, 0.92], [0.85, 0.95], [-1.7, 0.98], [-2.04, 0.96], [-2.06, 0.6]],
    cabin: { zA: 0.86, zB: 0.08, zC: -1.55, zD: -1.95, belt: 0.95, roof: 1.46, tumble: 0.13 },
  },
  pickup: {
    L: 5.4, W: 1.95, wheelR: 0.39, wheelZ: [1.75, -1.65], track: 1.66, sill: 0.48,
    top: [[2.7, 0.75], [2.68, 0.98], [2.55, 1.1], [1.4, 1.16], [1.05, 1.17], [-0.65, 1.17], [-0.65, 0.6]],
    cabin: { zA: 1.05, zB: 0.45, zC: -0.5, zD: -0.6, belt: 1.17, roof: 1.84, tumble: 0.12 },
    bed: { z0: -0.68, z1: -2.7, top: 1.15 },
  },
  van: {
    L: 5.3, W: 2.0, wheelR: 0.36, wheelZ: [1.75, -1.75], track: 1.7, sill: 0.4,
    top: [[2.65, 0.6], [2.63, 0.9], [2.4, 1.05], [1.9, 1.14], [1.3, 1.9], [1.1, 2.02], [-2.55, 2.05], [-2.65, 1.98], [-2.65, 0.55]],
    vanWindows: true,
  },
};

function bodyShape(spec: CarSpec, zFront: number, zRear: number, topPts: Vec2[], archR: number): THREE.Shape {
  const s = new THREE.Shape();
  const y0 = spec.sill;
  s.moveTo(zRear, y0);
  const arches = [...spec.wheelZ].sort((a, b) => a - b).filter((z) => z > zRear + archR && z < zFront - archR);
  for (const z of arches) {
    const cy = spec.wheelR + 0.02;
    const dy = y0 - cy;
    const dx = Math.sqrt(Math.max(archR * archR - dy * dy, 0));
    s.lineTo(z - dx, y0);
    const a0 = Math.atan2(dy, -dx), a1 = Math.atan2(dy, dx);
    s.absarc(z, cy, archR, a0, a1, true);
  }
  s.lineTo(zFront, y0);
  for (const [z, y] of topPts) s.lineTo(z, y);
  s.lineTo(zRear, y0);
  return s;
}

/** Remnant of a smashed pane: band along bottom edge (a0→a1) toward top edge (b0→b1) with a jagged top. */
function shards(a0: Vec3, a1: Vec3, b0: Vec3, b1: Vec3, r: () => number, n = 7): THREE.BufferGeometry {
  const L = (p: Vec3, q: Vec3, t: number): Vec3 => [p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t, p[2] + (q[2] - p[2]) * t];
  const parts: THREE.BufferGeometry[] = [];
  let prevH = 0.1 + r() * 0.4;
  for (let i = 0; i < n; i++) {
    const t0 = i / n, t1 = (i + 1) / n;
    const h = (i === n - 1 ? 0.6 : 0.05) + r() * 0.45;
    const lo0 = L(a0, a1, t0), lo1 = L(a0, a1, t1);
    const hi0 = L(lo0, L(b0, b1, t0), prevH), hi1 = L(lo1, L(b0, b1, t1), h);
    parts.push(polygon([lo0, lo1, hi1, hi0], true));
    prevH = h;
  }
  return merge(parts);
}

function wheel(pb: PartBuilder, spec: CarSpec, x: number, z: number, side: number, flat: boolean, rimOnly: boolean, rimMat: THREE.Material): void {
  const R = spec.wheelR, w = 0.21;
  const g: THREE.BufferGeometry[] = [];
  if (!rimOnly) {
    const prof: Vec2[] = [[R * 0.62, -w / 2], [R * 0.86, -w / 2 - 0.005], [R * 0.97, -w / 2 + 0.01], [R, -w / 2 + 0.04], [R + 0.003, 0], [R, w / 2 - 0.04], [R * 0.97, w / 2 - 0.01], [R * 0.86, w / 2 + 0.005], [R * 0.62, w / 2]];
    const tyre = xf(lathe(prof, 22), 0, 0, 0, 0, 0, Math.PI / 2);
    if (flat) displace(tyre, (v) => { if (v.y < -R * 0.75) { const d = -R * 0.75 - v.y; v.y = -R * 0.75 - d * 0.25; v.x *= 1 + d * 2.2; } }, true);
    pb.add('rubber', xf(tyre, x, R, z));
  }
  const rr = R * 0.62;
  const rim = lathe([[0.0, 0.0], [rr * 0.25, 0.012], [rr * 0.55, 0.0], [rr * 0.9, -0.03], [rr, -0.04], [rr, -0.16], [rr * 0.95, -0.18]], 16);
  g.push(xf(rim, 0, 0, 0, 0, 0, side > 0 ? -Math.PI / 2 : Math.PI / 2));
  const yOff = rimOnly ? rr : (flat ? R - 0.06 : R);
  pb.add(rimMat, ...g.map((q) => xf(q, x + side * (w / 2 - 0.01), yOff, z)));
  for (let i = 0; i < 5; i++) {
    const a = (i / 5) * Math.PI * 2;
    pb.add('metalDark', xf(cylinder(0.009, 0.009, 0.02, 6), x + side * (w / 2 + 0.005), yOff + Math.cos(a) * 0.05, z + Math.sin(a) * 0.05, 0, 0, Math.PI / 2));
  }
}

function defineCar(variant: CarVariant, seed: number, paint: THREE.Material): Part[] {
  const base: keyof typeof SPECS = variant === 'police' || variant === 'wreck' ? 'sedan' : variant;
  const spec = SPECS[base];
  const pb = new PartBuilder();
  const r = rng(seed * 101 + variant.length * 7);
  const wreck = variant === 'wreck';
  const bodyMat: THREE.Material = wreck ? tinted('metalRust', 0x6a4632) : paint;
  const trim: THREE.Material = wreck ? M.charred() : tinted('plasticBlack', 0x1e1e1e);
  const chrome: THREE.Material = wreck ? tinted('metalRust', 0x4a3a2e) : tinted('chrome', 0x9a9a98);
  const glassM = tinted('glassDirty', 0x7a8680);
  const { L, W } = spec;
  const zF = L / 2, zR = -L / 2;
  const drop = wreck ? -(spec.wheelR - spec.wheelR * 0.62) : 0;
  const B = 0.05; // body bevel

  // ---- main body (extruded side profile, shaped)
  const topPts = spec.top.filter(([z]) => z <= zF && z >= zR);
  const bodyFrontZ = topPts[0][0], bodyRearZ = topPts[topPts.length - 1][0];
  const shape = bodyShape(spec, bodyFrontZ, bodyRearZ, topPts, spec.wheelR + 0.07);
  const body = extrude(shape, W, { bevel: B, bevelSegments: 2, curveSegments: 10 });
  xf(body, 0, 0, 0, 0, -Math.PI / 2);
  displace(body, (v) => {
    const ez = Math.max(0, Math.abs(v.z) - (L / 2 - 0.6)) / 0.6;
    v.x *= 1 - 0.07 * ez * ez;
    const sh = THREE.MathUtils.clamp((v.y - (spec.cabin ? spec.cabin.belt - 0.25 : 0.7)) / 0.3, 0, 1);
    v.x *= 1 - 0.03 * sh;
  }, true);
  pb.add(bodyMat, body);

  // ---- greenhouse
  const sideX = (y: number, c: NonNullable<CarSpec['cabin']>) => {
    const wb = W / 2 - 0.05, wr = wb - c.tumble;
    return wb + (wr - wb) * (y - c.belt) / (c.roof - c.belt);
  };
  if (spec.cabin) {
    const c = spec.cabin;
    const wb = W / 2 - 0.05, wr = wb - c.tumble;
    const P = (s: number, which: 'A' | 'B' | 'C' | 'D', inset = 0): Vec3 => {
      switch (which) {
        case 'A': return [s * (wb - inset), c.belt, c.zA - inset];
        case 'B': return [s * (wr - inset), c.roof, c.zB - inset];
        case 'C': return [s * (wr - inset), c.roof, c.zC + inset];
        default: return [s * (wb - inset), c.belt, c.zD + inset];
      }
    };
    const broken = (k: number) => !wreck && r() < 0.28 + k * 0;
    if (!wreck) {
      const ws = broken(0);
      if (!ws) pb.add(glassM, polygon([P(-1, 'A', 0.04), P(1, 'A', 0.04), P(1, 'B', 0.04), P(-1, 'B', 0.04)], true));
      else pb.add(glassM, shards(P(-1, 'A', 0.04), P(1, 'A', 0.04), P(-1, 'B', 0.04), P(1, 'B', 0.04), r, 9));
      if (!broken(1)) pb.add(glassM, polygon([P(-1, 'D', 0.04), P(1, 'D', 0.04), P(1, 'C', 0.04), P(-1, 'C', 0.04)], true));
      const zMidBelt = c.bz ?? (c.zA + c.zD) / 2 + 0.05, zMidRoof = zMidBelt - 0.08;
      for (const s of [-1, 1]) {
        const x0 = s * (wb + 0.005), x1 = s * (wr + 0.005);
        const front: Vec3[] = [[x0, c.belt + 0.02, c.zA - 0.08], [x1, c.roof - 0.03, c.zB - 0.05], [x1, c.roof - 0.03, zMidRoof + 0.04], [x0, c.belt + 0.02, zMidBelt + 0.04]];
        const rear: Vec3[] = [[x0, c.belt + 0.02, zMidBelt - 0.04], [x1, c.roof - 0.03, zMidRoof - 0.04], [x1, c.roof - 0.03, c.zC + 0.06], [x0, c.belt + 0.02, c.zD + 0.1]];
        if (!broken(2)) pb.add(glassM, polygon(front, true));
        else pb.add(glassM, shards(front[0], front[3], front[1], front[2], r, 5));
        if (!broken(3)) pb.add(glassM, polygon(rear, true));
        // pillars
        pb.add(trim, boxBetween([s * wb, c.belt, zMidBelt], [s * wr, c.roof, zMidRoof], 0.025, 0.09));
      }
    } else {
      for (const s of [-1, 1]) pb.add(bodyMat, boxBetween([s * wb, c.belt, (c.zA + c.zD) / 2 + 0.05], [s * wr, c.roof, (c.zA + c.zD) / 2 - 0.03], 0.03, 0.09));
    }
    for (const s of [-1, 1]) {
      pb.add(bodyMat, boxBetween(P(s, 'A', 0.0), P(s, 'B', 0.0), 0.07, 0.05, 0.01));
      pb.add(bodyMat, boxBetween(P(s, 'D', 0.0), P(s, 'C', 0.0), 0.07, 0.16, 0.01));
    }
    // roof
    const rb = 0.03;
    const roof = slab([[-wr - rb, c.roof + 0.02, c.zB - 0.02], [wr + rb, c.roof + 0.02, c.zB - 0.02], [wr + rb, c.roof + 0.02, c.zC + 0.0], [-wr - rb, c.roof + 0.02, c.zC + 0.0]], 0.05);
    displace(roof, (v) => { v.y += 0.03 * (1 - (v.x / (wr + rb)) ** 2) - (wreck ? Math.max(0, 0.12 - Math.abs(v.z + 0.4) * 0.3) : 0); }, true);
    pb.add(bodyMat, roof);
    // interior: seats, dash, steering wheel
    const seat = wreck ? M.charred() : tinted('fabric', 0x3a3632);
    const sx = W / 2 - 0.5;
    for (const s of [-1, 1]) {
      pb.add(seat, xf(bevelBox(0.5, 0.14, 0.5, 0.04), s * sx * 0.75, c.belt - 0.38, c.zA - 0.95));
      pb.add(seat, xf(bevelBox(0.48, 0.6, 0.12, 0.04), s * sx * 0.75, c.belt - 0.05, c.zA - 1.22, -0.2));
    }
    if (base !== 'pickup') {
      pb.add(seat, xf(bevelBox(W - 0.35, 0.14, 0.5, 0.04), 0, c.belt - 0.36, c.zA - 1.75));
      pb.add(seat, xf(bevelBox(W - 0.35, 0.55, 0.12, 0.04), 0, c.belt - 0.05, c.zA - 2.02, -0.25));
    }
    pb.add(trim, xf(bevelBox(W - 0.2, 0.2, 0.35, 0.05), 0, c.belt - 0.06, c.zA - 0.12));
    if (!wreck) pb.add(trim, xf(torus(0.18, 0.016, 5, 16), sx * 0.75, c.belt + 0.05, c.zA - 0.48, -0.35));
  }

  // ---- van windows overlay + cargo details
  if (spec.vanWindows) {
    const xS = W / 2 + 0.004;
    const ws: Vec3[] = [[-W / 2 + 0.12, 1.16, 1.88], [W / 2 - 0.12, 1.16, 1.88], [W / 2 - 0.15, 1.86, 1.33], [-W / 2 + 0.15, 1.86, 1.33]];
    pb.add(glassM, polygon(ws.map(([x, y, z]) => [x, y + 0.01, z + 0.012] as Vec3), true));
    for (const s of [-1, 1]) {
      if (s > 0 || r() > 0.4) pb.add(glassM, polygon([[s * xS, 1.2, 1.8], [s * xS, 1.85, 1.3], [s * xS, 1.85, 0.75], [s * xS, 1.2, 0.75]], true));
      pb.add(trim, xf(box(0.006, 1.4, 0.012), s * xS, 1.1, 0.7), xf(box(0.006, 0.012, 2.6), s * xS, 1.25, -1.0));
      pb.add(trim, xf(box(0.02, 0.012, 1.4), s * (W / 2 + 0.01), 1.93, -0.8));
    }
    pb.add(trim, xf(box(0.012, 1.5, 0.006), 0, 1.15, zR - 0.004));
    pb.add(chrome, xf(bevelBox(0.05, 0.12, 0.03, 0.01), 0.12, 1.1, zR - 0.01), xf(bevelBox(0.05, 0.12, 0.03, 0.01), -0.12, 1.1, zR - 0.01));
    pb.add(tinted('steelBrushed', 0x8a8a88), ...[-0.5, -0.1, 0.3, 0.7].map((x) => xf(box(0.04, 0.03, 3.4), x, 2.08, -0.8)));
    // dash + seats visible through windshield
    pb.add(trim, xf(bevelBox(W - 0.2, 0.2, 0.35, 0.05), 0, 1.05, 1.6));
    for (const s of [-1, 1]) pb.add(tinted('fabric', 0x3a3632), xf(bevelBox(0.5, 0.7, 0.14, 0.04), s * 0.45, 1.1, 0.55, -0.15));
  }

  // ---- pickup bed
  if (spec.bed) {
    const b = spec.bed;
    const len = b.z0 - b.z1;
    const zc = (b.z0 + b.z1) / 2;
    for (const s of [-1, 1]) {
      const bedSide = bodyShape({ ...spec, wheelZ: [spec.wheelZ[1], 99] }, b.z0, b.z1, [[b.z0, b.top], [b.z1, b.top]], spec.wheelR + 0.07);
      const g = xf(extrude(bedSide, 0.08, { bevel: 0.02, curveSegments: 10 }), 0, 0, 0, 0, -Math.PI / 2);
      pb.add(bodyMat, xf(g, s * (W / 2 - 0.04), 0, 0));
      pb.add(trim, xf(bevelBox(0.1, 0.03, len, 0.01), s * (W / 2 - 0.05), b.top + 0.015, zc));
      pb.add(bodyMat, xf(bevelBox(0.3, 0.25, 0.9, 0.04), s * (W / 2 - 0.28), 0.72, spec.wheelZ[1]));
    }
    pb.add(trim, xf(box(W - 0.16, 0.03, len), 0, 0.62, zc));
    for (let i = 0; i < 6; i++) pb.add(trim, xf(box(0.04, 0.012, len - 0.05), -0.7 + i * 0.28, 0.64, zc));
    pb.add(bodyMat, xf(bevelBox(W - 0.06, 0.55, 0.06, 0.02), 0, 0.88, b.z0 + 0.03));
    // tailgate (dropped open on some)
    const tg = bevelBox(W - 0.04, 0.55, 0.06, 0.02);
    if (r() < 0.5) { xf(tg, 0, -0.275, 0); xf(tg, 0, 0.88 - 0.275 + 0.0, b.z1 - 0.03, -Math.PI / 2 + 0.05); }
    else xf(tg, 0, 0.88, b.z1 - 0.03);
    pb.add(bodyMat, tg);
    pb.add(chrome, xf(bevelBox(W, 0.16, 0.12, 0.03), 0, 0.5, zR + 0.03));
    // junk in the bed
    pb.add(tinted('plasticWhite', 0x2a5a8a), xf(cylinder(0.28, 0.28, 0.85, 12), 0.4, 1.06, -1.2));
    pb.add(tinted('wood', 0x8a7a5a), xf(bevelBox(0.9, 0.1, 0.12, 0.01), -0.3, 0.7, -2.0, 0, 0.4));
  }

  // ---- front / rear details
  const frontY = base === 'pickup' ? 0.85 : base === 'van' ? 0.8 : 0.66;
  const fz = bodyFrontZ + 0.02;
  // grille & headlights
  pb.add(trim, xf(bevelBox(W * 0.42, base === 'pickup' ? 0.28 : 0.13, 0.04, 0.01), 0, frontY - 0.03, fz - 0.03));
  for (let i = 0; i < 4; i++) pb.add(chrome, xf(box(W * 0.4, 0.008, 0.012), 0, frontY - 0.08 + i * (base === 'pickup' ? 0.07 : 0.035), fz - 0.005));
  for (const s of [-1, 1]) {
    const hx = s * (W / 2 - 0.27);
    const brokenLight = wreck || r() < 0.2;
    pb.add(chrome, xf(bevelBox(0.34, 0.13, 0.05, 0.02), hx, frontY, fz - 0.05));
    if (!brokenLight) pb.add('glass', xf(bevelBox(0.32, 0.11, 0.02, 0.015), hx, frontY, fz - 0.015));
    pb.add(M.amberLens(), xf(bevelBox(0.08, 0.05, 0.03, 0.01), s * (W / 2 - 0.06), frontY - 0.04, fz - 0.1));
    // taillights
    const ty = base === 'van' ? 1.1 : base === 'pickup' ? 0.95 : 0.82;
    if (!wreck) pb.add(M.redLens(), xf(bevelBox(base === 'van' ? 0.1 : 0.34, base === 'van' ? 0.4 : 0.12, 0.04, 0.012), s * (W / 2 - (base === 'van' ? 0.08 : 0.22)), ty, bodyRearZ - 0.0 - (spec.bed ? 0 : 0.0)));
  }
  // bumpers
  const bumperMat = base === 'pickup' || base === 'van' ? chrome : trim;
  pb.add(bumperMat, xf(bevelBox(W + 0.02, 0.2, 0.14, 0.04), 0, (base === 'pickup' ? 0.55 : 0.42), fz + 0.02));
  if (!spec.bed) pb.add(bumperMat, xf(bevelBox(W + 0.02, 0.2, 0.14, 0.04), 0, 0.45, bodyRearZ - 0.03));
  // plates
  pb.add(wreck ? M.charred() : tinted('plasticWhite', 0xd8d4c0), xf(box(0.3, 0.15, 0.006), 0, base === 'pickup' ? 0.55 : 0.42, fz + 0.095));
  pb.add(wreck ? M.charred() : tinted('plasticWhite', 0xd8d4c0), xf(box(0.3, 0.15, 0.006), 0, base === 'van' ? 0.75 : base === 'pickup' ? 0.75 : 0.62, (spec.bed ? spec.bed.z1 - 0.07 : bodyRearZ - 0.01)));
  // door seams, handles, mirrors, rocker grime
  const belt = spec.cabin?.belt ?? 1.15;
  for (const s of [-1, 1]) {
    const x = s * (W / 2 + 0.001);
    const seams = spec.cabin ? (base === 'pickup' ? [spec.cabin.zA + 0.05, -0.55] : base === 'hatchback' ? [spec.cabin.zA + 0.05, -0.35, spec.cabin.zD] : [spec.cabin.zA + 0.05, -0.15, -1.25])
      : [1.9, 0.72];
    for (const z of seams) pb.add(trim, xf(box(0.004, belt - spec.sill - 0.05, 0.008), x, (belt + spec.sill) / 2, z));
    for (let i = 0; i < seams.length - 1; i++) pb.add(chrome, xf(bevelBox(0.02, 0.025, 0.14, 0.006), s * (W / 2 + 0.008), belt - 0.1, seams[i] - 0.2));
    const mz = spec.cabin ? spec.cabin.zA - 0.08 : 1.75;
    const my = spec.cabin ? spec.cabin.belt + 0.08 : 1.3;
    if (!(wreck && s > 0)) {
      pb.add(trim, boxBetween([s * (W / 2 - 0.05), my, mz], [s * (W / 2 + 0.1), my, mz], 0.05, 0.04));
      pb.add(trim, xf(bevelBox(0.06, 0.12, 0.18, 0.02), s * (W / 2 + 0.13), my + 0.03, mz - 0.02, 0, -s * 0.3));
    }
    pb.add(wreck ? M.charred() : tinted('mud', 0x5a4c3a), xf(box(0.01, 0.12, Math.abs(spec.wheelZ[0] - spec.wheelZ[1]) - spec.wheelR * 2 - 0.15), s * (W / 2 + 0.002), spec.sill + 0.06, (spec.wheelZ[0] + spec.wheelZ[1]) / 2));
    // rust / dirt patches on body sides
    for (let i = 0; i < (wreck ? 0 : 3); i++) {
      const z = zR + 0.4 + r() * (L - 0.8);
      pb.add(tinted('metalRust', 0x6a4a32), xf(box(0.006, 0.05 + r() * 0.1, 0.08 + r() * 0.2), s * (W / 2 + 0.003), spec.sill + 0.08 + r() * 0.25, z));
    }
  }
  // underbody shadow-catcher + exhaust
  pb.add(tinted('metalDark', 0x1a1a1a), xf(box(W - 0.2, 0.06, L - 0.6), 0, spec.sill - 0.03, 0));
  if (!wreck) pb.add('metalRust', xf(cylinder(0.03, 0.03, 0.25, 8), W / 2 - 0.4, 0.25, zR + 0.1, Math.PI / 2));

  // ---- wheels
  const rimMat = wreck ? tinted('metalRust', 0x5a3a28) : variant === 'police' ? tinted('metalDark', 0x2a2a2a) : tinted('steelBrushed', 0xa8a8a6);
  const flats = [r() < 0.3, r() < 0.25, r() < 0.3, r() < 0.2];
  let k = 0;
  for (const z of spec.wheelZ) for (const s of [-1, 1]) {
    wheel(pb, spec, s * spec.track / 2, z, s, flats[k++], wreck, rimMat);
  }

  // ---- police kit
  if (variant === 'police' && spec.cabin) {
    const c = spec.cabin;
    const white = tinted('plasticWhite', 0xe0ddd4);
    for (const s of [-1, 1]) {
      pb.add(white, xf(box(0.006, c.belt - spec.sill - 0.12, 1.95), s * (W / 2 + 0.003), (c.belt + spec.sill) / 2 + 0.02, -0.2));
      pb.add(tinted('plasticWhite', 0xb89a3a), xf(box(0.007, 0.18, 0.18), s * (W / 2 + 0.004), (c.belt + spec.sill) / 2 + 0.06, 0.2)); // badge
    }
    pb.add(white, xf(slab([[-0.6, c.roof + 0.047, c.zB - 0.04], [0.6, c.roof + 0.047, c.zB - 0.04], [0.6, c.roof + 0.047, c.zC + 0.02], [-0.6, c.roof + 0.047, c.zC + 0.02]], 0.01), 0, 0.01, 0));
    pb.add(trim, xf(bevelBox(1.2, 0.05, 0.3, 0.015), 0, c.roof + 0.09, -0.35));
    pb.add(tinted('plasticBlack', 0x8a1010), xf(bevelBox(0.52, 0.09, 0.26, 0.03), -0.32, c.roof + 0.15, -0.35));
    pb.add(tinted('plasticBlack', 0x10306a), xf(bevelBox(0.52, 0.09, 0.26, 0.03), 0.32, c.roof + 0.15, -0.35));
    pb.add(trim, ...[-0.3, 0.3].map((x) => cylinderBetween([x, 0.35, zF + 0.05], [x, 0.85, zF + 0.1], 0.025, 0.025, 6)));
    pb.add(trim, cylinderBetween([-0.42, 0.8, zF + 0.1], [0.42, 0.8, zF + 0.1], 0.025, 0.025, 6), cylinderBetween([-0.42, 0.55, zF + 0.09], [0.42, 0.55, zF + 0.09], 0.025, 0.025, 6));
    pb.add(chrome, xf(sphere(0.07, 10, 6), -(W / 2 - 0.02), c.belt + 0.15, c.zA - 0.1));
    pb.add('metalDark', cylinderBetween([0.4, c.roof + 0.05, c.zC + 0.1], [0.45, c.roof + 0.42, c.zC + 0.0], 0.003, 0.002, 3));
  }
  if (wreck) {
    // soot streaks + missing hood gap
    pb.add(M.charred(), xf(box(W * 0.75, 0.01, 0.9), 0, 0.94, 1.6, -0.08));
    pb.add(M.charred(), xf(box(W - 0.3, 0.3, 1.2), 0, 0.75, 1.55));
  }

  const parts = pb.build();
  if (drop !== 0) for (const p of parts) p.geometry.translate(0, drop, 0);
  return parts;
}

// paint placeholder: car geometry is cached per (variant, seed); the paint material is swapped per call
const PAINT_SLOT = new THREE.MeshStandardMaterial({ name: 'paint-slot' });
const carCache = new Map<string, Part[]>();

/**
 * Build an abandoned car. Length along Z (front = +Z), wheels on y=0, centred on x/z.
 * Geometry is cached per variant (3 damage seeds chosen from the paint colour); paint via getCarPaint.
 */
export function buildCar(variant: CarVariant, paintHex: number): { object: THREE.Object3D; size: [number, number, number] } {
  const seed = ((paintHex >>> 0) % 3);
  const key = `${variant}:${seed}`;
  let parts = carCache.get(key);
  if (!parts) { parts = defineCar(variant, seed, PAINT_SLOT); carCache.set(key, parts); }
  const paint = getCarPaint(paintHex);
  const g = partsToGroup(parts.map((p) => ({ geometry: p.geometry, material: p.material === PAINT_SLOT ? paint : p.material })));
  g.name = `car:${variant}`;
  const bb = new THREE.Box3();
  for (const p of parts) { p.geometry.computeBoundingBox(); bb.union(p.geometry.boundingBox!); }
  const size: [number, number, number] = [
    Math.max(-bb.min.x, bb.max.x) * 2, bb.max.y, Math.max(-bb.min.z, bb.max.z) * 2,
  ];
  return { object: g, size };
}
