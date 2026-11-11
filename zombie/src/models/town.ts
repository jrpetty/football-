// REKINDLE - town-builder buildings: salvaged, hand-built post-collapse settlement structures.
// Each model is built ONCE per id into a template (static parts merged per material via PB),
// then buildTownModel() returns a cheap clone sharing geometry/materials, with a per-instance
// status-light material, per-instance flag geometry and a per-instance animate() closure.
//
// Conventions: group origin = footprint centre at ground level, entrance faces +Z, footprint in
// 2 m tiles (TOWN_FOOT), everything stays ~0.05 m inside the footprint.
import * as THREE from 'three';
import { getMaterial, getTintedMaterial, getFlatMaterial, type MaterialName } from '../render/materials';
import { PB, boxGeo, cylGeo, sphereGeo, torusGeo, quadGeo, extrudeGeo, latheGeo, rng, type V3 } from './machinegeom';

export type TownModelId = 'shelter_house' | 'bunkhouse' | 'mess_hall' | 'water_tower' | 'guard_post' | 'lumber_yard' | 'workshop' | 'hub';
export const TOWN_FOOT: Record<TownModelId, [number, number]> = {
  shelter_house: [2, 2], bunkhouse: [3, 3], mess_hall: [3, 3], water_tower: [2, 2], guard_post: [1, 1], lumber_yard: [3, 3], workshop: [3, 3], hub: [3, 3],
};
export const TOWN_MODEL_IDS = Object.keys(TOWN_FOOT) as TownModelId[];

type Anim = (dt: number, running: boolean, t: number) => void;
interface Parts { get(name: string): THREE.Object3D | undefined; all(name: string): THREE.Object3D[] }
type Rig = (p: Parts) => Anim;

const TAU = Math.PI * 2;
type Mat = THREE.MeshStandardMaterial;

// ---------------------------------------------------------------------------
// Materials
// ---------------------------------------------------------------------------
const M = (n: MaterialName): Mat => getMaterial(n);
const tint = (n: MaterialName, hex: number): Mat => getTintedMaterial(n, hex);
const flat = (hex: number, r = 0.9, m = 0): Mat => getFlatMaterial(hex, r, m);
const own = new Map<string, Mat>();
function ownMat(key: string, make: () => Mat): Mat {
  let m = own.get(key);
  if (!m) { m = make(); own.set(key, m); }
  return m;
}
function emissiveVariant(key: string, base: MaterialName, col: number, emi: number, intensity: number): Mat {
  return ownMat(key, () => {
    const m = M(base).clone();
    m.color.setHex(col); m.emissive.setHex(emi); m.emissiveIntensity = intensity;
    m.name = 'town_' + key;
    return m;
  });
}
/** Shared warm window glass for every town building (exposed as userData.windowMaterial). */
function windowMat(): Mat {
  return ownMat('window', () => {
    const m = M('emissiveWarm').clone();
    m.name = 'town_window';
    m.color.setHex(0x221a12); m.emissive.setHex(0xffa452); m.emissiveIntensity = 0.55;
    m.roughness = 0.25; m.metalness = 0.1;
    return m;
  });
}

interface Kit {
  wood: Mat; woodD: Mat; floor: Mat; roof: Mat; rust: Mat; dark: Mat; metal: Mat; steel: Mat; conc: Mat; brick: Mat;
  rock: Mat; rustSheet: Mat; sidB: Mat; sidG: Mat; sidY: Mat; sidW: Mat; tinRed: Mat; tinGreen: Mat; tarp: Mat; canvas: Mat; sandbag: Mat;
  rope: Mat; black: Mat; rubber: Mat; bark: Mat; endGrain: Mat; win: Mat; flame: Mat; bulb: Mat; ember: Mat; soot: Mat;
  water: Mat; slab: Mat; mortar: Mat; block: Mat; status: Mat; dirt: Mat; white: Mat;
}
let KIT: Kit | null = null;
function kit(): Kit {
  if (KIT) return KIT;
  KIT = {
    wood: M('wood'), woodD: M('woodDark'), floor: M('woodFloor'), roof: M('roofMetal'), rust: M('metalRust'),
    dark: M('metalDark'), metal: M('metal'), steel: M('steelBrushed'), conc: M('concrete'), brick: M('brickRed'),
    rock: M('rock'), sidB: M('sidingBlue'), sidG: M('sidingGreen'), sidY: M('sidingYellow'), sidW: M('sidingWhite'),
    tinRed: tint('roofMetal', 0xb47c64), tinGreen: tint('roofMetal', 0x86a07c), rustSheet: tint('metalRust', 0xa89484),
    tarp: tint('fabric', 0x6f9cd0), canvas: tint('fabric', 0xe0d2aa), sandbag: tint('fabric', 0xc4b080),
    rope: flat(0x8e7c58, 0.95, 0), black: M('plasticBlack'), rubber: M('rubber'), bark: M('bark'),
    endGrain: flat(0xb8925e, 0.9, 0), win: windowMat(),
    flame: emissiveVariant('flame', 'emissiveWarm', 0x5a2a08, 0xffa040, 3.2),
    bulb: emissiveVariant('bulb', 'emissiveWarm', 0x6a4a20, 0xffc070, 2.2),
    ember: emissiveVariant('ember', 'emissiveWarm', 0x2a0c02, 0xff5a12, 1.4),
    soot: flat(0x1e1b18, 0.95, 0),
    water: ownMat('water', () => new THREE.MeshStandardMaterial({ name: 'town_water', color: 0x2a3c40, roughness: 0.06, metalness: 0.2 })),
    mortar: flat(0x6c675e, 0.95, 0), block: tint('concrete', 0xc4c0b4), status: M('emissiveGreen'),
    dirt: tint('sand', 0xc8b494), white: M('plasticWhite'), slab: tint('concrete', 0x9c988e),
  };
  return KIT;
}

// ---------------------------------------------------------------------------
// Hand-painted canvas textures (signs, notice board, flag)
// ---------------------------------------------------------------------------
type Draw = (g: CanvasRenderingContext2D, W: number, H: number, r: () => number) => void;
function canvasMat(key: string, aspect: number, fallback: number, draw: Draw, px = 512): Mat {
  return ownMat('cv:' + key, () => {
    if (typeof document === 'undefined') return flat(fallback);
    const W = px, H = Math.max(16, Math.round(px / aspect));
    const cv = document.createElement('canvas'); cv.width = W; cv.height = H;
    const g = cv.getContext('2d');
    if (!g) return flat(fallback);
    draw(g, W, H, rng(key.length * 7919 + key.charCodeAt(0)));
    const tex = new THREE.CanvasTexture(cv);
    tex.colorSpace = THREE.SRGBColorSpace; tex.anisotropy = 4;
    return new THREE.MeshStandardMaterial({ name: 'town_' + key, map: tex, roughness: 0.88, metalness: 0 });
  });
}
function rgb(c: [number, number, number], k = 1): string {
  return `rgb(${Math.max(0, Math.min(255, c[0] * k)) | 0},${Math.max(0, Math.min(255, c[1] * k)) | 0},${Math.max(0, Math.min(255, c[2] * k)) | 0})`;
}
/** horizontal weathered planks */
function paintPlanks(g: CanvasRenderingContext2D, W: number, H: number, r: () => number, base: [number, number, number], n: number): void {
  const ph = H / n;
  for (let i = 0; i < n; i++) {
    const k = 0.82 + r() * 0.3;
    g.fillStyle = rgb(base, k); g.fillRect(0, i * ph, W, ph);
    g.globalAlpha = 0.18;
    for (let s = 0; s < 14; s++) {
      g.strokeStyle = rgb(base, r() < 0.5 ? 0.6 : 1.25); g.lineWidth = 1 + r() * 2;
      const y = i * ph + r() * ph; g.beginPath(); g.moveTo(0, y);
      g.bezierCurveTo(W * 0.3, y + (r() - 0.5) * 6, W * 0.7, y + (r() - 0.5) * 6, W, y + (r() - 0.5) * 4); g.stroke();
    }
    g.globalAlpha = 1;
    g.fillStyle = 'rgba(15,10,6,0.85)'; g.fillRect(0, i * ph, W, Math.max(2, H * 0.012));
    g.fillStyle = 'rgba(30,26,22,0.9)';
    for (const x of [W * 0.04, W * 0.96]) { g.beginPath(); g.arc(x, i * ph + ph / 2, Math.max(2, H * 0.012), 0, TAU); g.fill(); }
  }
}
function paintText(g: CanvasRenderingContext2D, r: () => number, text: string, cx: number, cy: number, size: number, color: string, spacing = 0.06, maxFrac = 0.88): void {
  const font = (sz: number): string => `900 ${sz}px "Arial Black", Impact, "DejaVu Sans", "Liberation Sans", sans-serif`;
  g.font = font(size);
  g.textBaseline = 'middle'; g.textAlign = 'center';
  const measure = (): number[] => [...text].map((c) => g.measureText(c).width + size * spacing);
  let ws = measure();
  const maxW = g.canvas.width * maxFrac;
  const total0 = ws.reduce((a, b) => a + b, 0);
  if (total0 > maxW) { size = Math.floor(size * maxW / total0); g.font = font(size); ws = measure(); }
  const total = ws.reduce((a, b) => a + b, 0);
  let x = cx - total / 2;
  [...text].forEach((c, i) => {
    g.save();
    g.translate(x + ws[i] / 2, cy + (r() - 0.5) * size * 0.08);
    g.rotate((r() - 0.5) * 0.09);
    g.fillStyle = color; g.fillText(c, 0, 0);
    if (c !== ' ' && r() < 0.45) { // paint drip
      g.fillRect((r() - 0.5) * size * 0.4, size * 0.3, Math.max(2, size * 0.045), size * (0.12 + r() * 0.3));
    }
    g.restore();
    x += ws[i];
  });
}
/** scuff the paint: speckles of the under-colour */
function wear(g: CanvasRenderingContext2D, W: number, H: number, r: () => number, col: string, n: number): void {
  g.fillStyle = col;
  for (let i = 0; i < n; i++) {
    g.globalAlpha = 0.25 + r() * 0.5;
    g.beginPath(); g.ellipse(r() * W, r() * H, 1 + r() * W * 0.012, 1 + r() * H * 0.02, r() * 3, 0, TAU); g.fill();
  }
  g.globalAlpha = 1;
}
function signMess(): Mat {
  return canvasMat('sign_mess', 3.2, 0x4a3a2a, (g, W, H, r) => {
    paintPlanks(g, W, H, r, [92, 70, 50], 3);
    paintText(g, r, 'MESS', W / 2, H * 0.47, H * 0.68, '#eadfc4', 0.1);
    g.fillStyle = '#a8321e'; g.fillRect(W * 0.14, H * 0.84, W * 0.72, H * 0.05);
    wear(g, W, H, r, 'rgb(70,52,36)', 160);
  });
}
function signHall(): Mat {
  return canvasMat('sign_hall', 4.4, 0x3a4630, (g, W, H, r) => {
    paintPlanks(g, W, H, r, [64, 78, 58], 2);
    paintText(g, r, 'SETTLEMENT HALL', W / 2, H * 0.52, H * 0.42, '#efe6cc', 0.03, 0.7);
    wear(g, W, H, r, 'rgb(96,74,52)', 220);
  }, 768);
}
function signWorkshop(): Mat {
  return canvasMat('sign_workshop', 4.6, 0x7a4a30, (g, W, H, r) => {
    // rusty sheet metal with a painted white panel
    g.fillStyle = 'rgb(120,74,46)'; g.fillRect(0, 0, W, H);
    for (let i = 0; i < 260; i++) { g.fillStyle = r() < 0.5 ? 'rgba(80,44,24,0.5)' : 'rgba(160,100,60,0.35)'; g.fillRect(r() * W, r() * H, 2 + r() * 18, 2 + r() * 10); }
    g.fillStyle = 'rgb(214,206,186)'; g.fillRect(W * 0.03, H * 0.1, W * 0.94, H * 0.8);
    paintText(g, r, 'REPAIRS', W / 2, H * 0.52, H * 0.6, '#a02a1a', 0.08);
    wear(g, W, H, r, 'rgb(130,80,48)', 260);
  });
}
function noticeBoard(): Mat {
  return canvasMat('notice', 1.35, 0x8a6a48, (g, W, H, r) => {
    paintPlanks(g, W, H, r, [120, 92, 64], 5);
    const papers: [number, number, number, number, string][] = [
      [0.06, 0.08, 0.36, 0.4, '#e8e2d0'], [0.46, 0.06, 0.28, 0.32, '#e6d8a4'], [0.76, 0.12, 0.2, 0.3, '#dcd8cc'],
      [0.1, 0.54, 0.3, 0.38, '#d8d0b8'], [0.44, 0.44, 0.3, 0.46, '#ece8dc'], [0.78, 0.5, 0.18, 0.26, '#c8b088'],
    ];
    for (const [x, y, w, h, c] of papers) {
      g.save(); g.translate((x + w / 2) * W, (y + h / 2) * H); g.rotate((r() - 0.5) * 0.12);
      g.fillStyle = 'rgba(0,0,0,0.25)'; g.fillRect(-w * W / 2 + 3, -h * H / 2 + 4, w * W, h * H);
      g.fillStyle = c; g.fillRect(-w * W / 2, -h * H / 2, w * W, h * H);
      g.fillStyle = 'rgba(40,36,30,0.75)';
      g.fillRect(-w * W * 0.38, -h * H * 0.36, w * W * 0.6, Math.max(3, h * H * 0.07));
      for (let l = 0; l < 6; l++) g.fillRect(-w * W * 0.38, -h * H * 0.18 + l * h * H * 0.1, w * W * (0.45 + r() * 0.3), Math.max(1.5, h * H * 0.025));
      g.fillStyle = r() < 0.5 ? '#c02818' : '#2848a0'; g.beginPath(); g.arc(0, -h * H * 0.44, 5, 0, TAU); g.fill();
      g.restore();
    }
  }, 384);
}
function flagMat(): Mat {
  const m = canvasMat('flag', 1.6, 0x9a3a1e, (g, W, H, r) => {
    g.fillStyle = 'rgb(150,48,26)'; g.fillRect(0, 0, W, H);
    g.fillStyle = 'rgb(196,120,40)'; g.fillRect(0, H * 0.78, W, H * 0.1);
    // rekindled flame emblem
    const cx = W * 0.42, cy = H * 0.44, s = H * 0.3;
    g.fillStyle = 'rgb(236,222,190)'; g.beginPath(); g.arc(cx, cy, s * 1.05, 0, TAU); g.fill();
    g.fillStyle = 'rgb(150,48,26)';
    g.beginPath(); g.moveTo(cx, cy - s * 0.85);
    g.bezierCurveTo(cx + s * 0.55, cy - s * 0.2, cx + s * 0.6, cy + s * 0.45, cx, cy + s * 0.7);
    g.bezierCurveTo(cx - s * 0.6, cy + s * 0.45, cx - s * 0.45, cy - s * 0.05, cx - s * 0.1, cy - s * 0.3);
    g.bezierCurveTo(cx - s * 0.05, cy, cx + s * 0.05, cy - s * 0.4, cx, cy - s * 0.85); g.fill();
    g.fillStyle = 'rgb(214,140,48)';
    g.beginPath(); g.moveTo(cx, cy - s * 0.1);
    g.bezierCurveTo(cx + s * 0.3, cy + s * 0.2, cx + s * 0.25, cy + s * 0.55, cx, cy + s * 0.6);
    g.bezierCurveTo(cx - s * 0.25, cy + s * 0.55, cx - s * 0.25, cy + s * 0.25, cx, cy - s * 0.1); g.fill();
    wear(g, W, H, r, 'rgba(90,30,16,1)', 90);
    // frayed fly end
    g.fillStyle = 'rgba(40,20,12,0.5)'; g.fillRect(W * 0.96, 0, W * 0.04, H);
  }, 256);
  m.side = THREE.DoubleSide;
  return m;
}

// ---------------------------------------------------------------------------
// Geometry helpers
// ---------------------------------------------------------------------------
/** box whose texture grain (v) runs along X */
function hbox(w: number, h: number, d: number): THREE.BufferGeometry { const g = boxGeo(h, w, d); g.rotateZ(Math.PI / 2); return g; }
/** box whose texture grain (v) runs along Z */
function zbox(w: number, h: number, d: number): THREE.BufferGeometry { const g = boxGeo(w, d, h); g.rotateX(Math.PI / 2); return g; }
function hb(b: PB, w: number, h: number, d: number, m: THREE.Material, x: number, y: number, z: number, rx = 0, ry = 0, rz = 0): void { b.add(hbox(w, h, d), m, x, y, z, rx, ry, rz); }
function zb(b: PB, w: number, h: number, d: number, m: THREE.Material, x: number, y: number, z: number, rx = 0, ry = 0, rz = 0): void { b.add(zbox(w, h, d), m, x, y, z, rx, ry, rz); }

/** Vertical salvaged boards on a wall (local frame: face +Z at z). */
function vBoards(b: PB, m: THREE.Material, x0: number, x1: number, y0: number, y1: number, z: number, r: () => number, bw = 0.17, t = 0.03): void {
  const n = Math.max(1, Math.round((x1 - x0) / bw));
  const w = (x1 - x0) / n;
  for (let i = 0; i < n; i++) {
    const dh = (r() - 0.5) * 0.06;
    b.box(w - 0.012, y1 - y0 + dh, t, m, x0 + (i + 0.5) * w, (y0 + y1) / 2 + dh / 2, z + (r() - 0.5) * 0.012, 0, (r() - 0.5) * 0.03, (r() - 0.5) * 0.012);
  }
}
/** Horizontal lap boards (local frame: face +Z at z). */
function hBoards(b: PB, m: THREE.Material, x0: number, x1: number, y0: number, y1: number, z: number, r: () => number, bh = 0.2, t = 0.03): void {
  const n = Math.max(1, Math.round((y1 - y0) / bh));
  const h = (y1 - y0) / n;
  for (let i = 0; i < n; i++) {
    const dx = (r() - 0.5) * 0.08;
    hb(b, x1 - x0 + dx, h - 0.01, t, m, (x0 + x1) / 2 + dx / 2, y0 + (i + 0.5) * h, z + (r() - 0.5) * 0.01, (r() - 0.5) * 0.05, 0, (r() - 0.5) * 0.01);
  }
}
/** Painted lap siding (texture carries the boards): a couple of salvaged panels, slightly offset. */
function siding(b: PB, m: THREE.Material, x0: number, x1: number, y0: number, y1: number, z: number, r: () => number, t = 0.03): void {
  const n = Math.max(1, Math.round((x1 - x0) / 1.3));
  const w = (x1 - x0) / n;
  for (let i = 0; i < n; i++) {
    const dy = (r() - 0.5) * 0.04;
    b.box(w - 0.01, y1 - y0 + dy, t, m, x0 + (i + 0.5) * w, (y0 + y1) / 2 + dy / 2, z + (i % 2) * 0.006, 0, 0, (r() - 0.5) * 0.012);
  }
  for (let i = 1; i < n; i++) b.box(0.07, y1 - y0, t + 0.012, M('woodDark'), x0 + i * w, (y0 + y1) / 2, z + 0.006);
}
/** Corrugated sheet panel (ribs vertical) on a wall, slightly askew. */
function tin(b: PB, m: THREE.Material, x0: number, x1: number, y0: number, y1: number, z: number, r: () => number, t = 0.025): void {
  b.box(x1 - x0, y1 - y0, t, m, (x0 + x1) / 2, (y0 + y1) / 2, z, 0, 0, (r() - 0.5) * 0.03);
  // screw rows
  for (const y of [y0 + 0.08, y1 - 0.08]) for (let x = x0 + 0.1; x < x1 - 0.05; x += 0.3) b.box(0.025, 0.025, 0.02, M('metalDark'), x, y, z + t / 2 + 0.005);
}

/** Window in a wall (local frame: face +Z at z=0 of the push). */
function windowAt(b: PB, x: number, y: number, z: number, ry: number, w: number, h: number, frame: THREE.Material, opts: { shutters?: THREE.Material; boarded?: boolean; cross?: boolean } = {}): void {
  const k = kit();
  b.at(x, y, z, ry, () => {
    b.add(quadGeo(w, h), k.win, 0, 0, 0.012);
    const f = 0.07;
    hb(b, w + f * 2, f, 0.07, frame, 0, h / 2 + f / 2, 0.035);
    hb(b, w + f * 2 + 0.12, 0.05, 0.1, frame, 0, -h / 2 - 0.025, 0.05);
    b.box(f, h, 0.07, frame, -w / 2 - f / 2, 0, 0.035);
    b.box(f, h, 0.07, frame, w / 2 + f / 2, 0, 0.035);
    if (opts.cross !== false) {
      b.box(0.035, h, 0.04, frame, 0, 0, 0.03);
      hb(b, w, 0.035, 0.04, frame, 0, 0, 0.03);
    }
    if (opts.boarded) {
      hb(b, w + 0.2, 0.12, 0.025, frame, 0, h * 0.18, 0.09, 0, 0, 0.18);
      hb(b, w + 0.25, 0.12, 0.025, frame, 0, -h * 0.22, 0.095, 0, 0, -0.12);
    }
    if (opts.shutters) {
      const sw = w / 2 + 0.03;
      for (const s of [-1, 1]) {
        b.at(s * (w / 2 + f + sw / 2 + 0.02), 0, 0.04, 0, () => {
          for (let i = 0; i < 3; i++) b.box(sw / 3 - 0.01, h + 0.04, 0.025, opts.shutters!, -sw / 2 + (i + 0.5) * sw / 3, 0, 0);
          hb(b, sw, 0.07, 0.02, opts.shutters!, 0, h * 0.3, 0.02);
          hb(b, sw, 0.07, 0.02, opts.shutters!, 0, -h * 0.3, 0.02);
        }, 0, s * 0.04);
      }
    }
  });
}
/** Ledged-and-braced plank door (local frame face +Z). y0 = threshold. */
function doorAt(b: PB, x: number, y0: number, z: number, ry: number, w: number, h: number, planks: THREE.Material, frame: THREE.Material): void {
  const k = kit();
  b.at(x, y0, z, ry, () => {
    const n = Math.max(3, Math.round(w / 0.15));
    for (let i = 0; i < n; i++) b.blk(w / n - 0.008, h, 0.045, planks, -w / 2 + (i + 0.5) * w / n, 0, 0.03);
    hb(b, w - 0.08, 0.13, 0.03, frame, 0, h * 0.18, 0.068);
    hb(b, w - 0.08, 0.13, 0.03, frame, 0, h * 0.82, 0.068);
    const a: V3 = [-w / 2 + 0.08, h * 0.22, 0.068], c: V3 = [w / 2 - 0.08, h * 0.78, 0.068];
    b.beam(a, c, 0.11, frame, 0.03);
    b.box(0.09, h + 0.09, 0.08, frame, -w / 2 - 0.045, h / 2, 0.04);
    b.box(0.09, h + 0.09, 0.08, frame, w / 2 + 0.045, h / 2, 0.04);
    hb(b, w + 0.18, 0.09, 0.08, frame, 0, h + 0.045, 0.04);
    b.cyl(0.02, 0.1, k.dark, w / 2 - 0.12, h * 0.5, 0.1, 'z', 6);
    for (const y of [h * 0.18, h * 0.82]) hb(b, 0.32, 0.04, 0.012, k.dark, -w / 2 + 0.16, y, 0.09);
  });
}
/** Hanging/standing lantern (flame mesh named 'flame' flickers). */
function lantern(b: PB, x: number, y: number, z: number, hook = true): void {
  const k = kit();
  b.box(0.15, 0.025, 0.15, k.dark, x, y - 0.12, z);
  b.add(cylGeo(0.025, 0.1, 0.08, 6), k.dark, x, y + 0.13, z);
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.015, 0.22, 0.015, k.dark, x + sx * 0.065, y, z + sz * 0.065);
  if (hook) b.add(torusGeo(0.04, 0.008, 4, 8), k.dark, x, y + 0.2, z);
  b.mesh('flame', sphereGeo(0.05, 8, 6), k.flame, x, y - 0.03, z).scale.set(1, 1.5, 1);
}
/** Ladder from (x,y0,z) to (x2,y1,z2) with rungs, local width along X. */
function ladder(b: PB, a: V3, c: V3, m: THREE.Material, w = 0.44): void {
  const len = Math.hypot(c[0] - a[0], c[1] - a[1], c[2] - a[2]);
  for (const s of [-1, 1]) b.beam([a[0] + s * w / 2, a[1], a[2]], [c[0] + s * w / 2, c[1], c[2]], 0.06, m, 0.045);
  for (let d = 0.28; d < len - 0.1; d += 0.3) {
    const t = d / len;
    const p: V3 = [a[0] + (c[0] - a[0]) * t, a[1] + (c[1] - a[1]) * t, a[2] + (c[2] - a[2]) * t];
    b.rod([p[0] - w / 2, p[1], p[2]], [p[0] + w / 2, p[1], p[2]], 0.02, m, 5);
  }
}
/** Gable roof with the ridge along local X at (0, ridgeY, cz). hd = half wall depth, ov = overhang, L = length along X. */
function gableRoof(b: PB, ridgeY: number, cz: number, hd: number, rise: number, ov: number, L: number, mats: THREE.Material[], r: () => number, t = 0.04): number {
  const a = Math.atan2(rise, hd);
  const run = (hd + ov) / Math.cos(a);
  for (const s of [1, -1]) {
    b.push(0, ridgeY, cz, 0, s > 0 ? 0 : Math.PI, 0); b.push(0, 0, 0, a, 0, 0);
    // sheets: overlapping strips of mismatched panels
    let x = -L / 2;
    let i = 0;
    while (x < L / 2 - 0.01) {
      const w = Math.min(L / 2 - x, 0.9 + r() * 0.9);
      const m = mats[(i + (s > 0 ? 0 : 1)) % mats.length];
      b.box(w + 0.04, t, run + 0.02, m, x + w / 2, t / 2 + (i % 2) * 0.008, run / 2, (r() - 0.5) * 0.01, 0, 0);
      x += w; i++;
    }
    b.pop(); b.pop();
  }
  return a;
}
/** Put fn's content in the plane of a roof slope (origin at ridge, +Z down-slope, +Y normal). */
function onSlope(b: PB, ridgeY: number, cz: number, a: number, s: number, fn: () => void): void {
  b.push(0, ridgeY, cz, 0, s > 0 ? 0 : Math.PI, 0); b.push(0, 0, 0, a, 0, 0); fn(); b.pop(); b.pop();
}
/** Triangular gable infill in the local Y-Z plane at x (for a ridge along X). */
function gableTri(b: PB, m: THREE.Material, x: number, y: number, cz: number, hd: number, rise: number, t = 0.05): void {
  const g = extrudeGeo([[-hd, 0], [hd, 0], [0, rise]], t);
  g.rotateY(Math.PI / 2);
  b.add(g, m, x, y, cz);
}
/** Gable triangle in the local X-Y plane at z (for a ridge along Z). */
function gableTriZ(b: PB, m: THREE.Material, z: number, y: number, cx: number, hw: number, rise: number, t = 0.05): void {
  b.add(extrudeGeo([[-hw, 0], [hw, 0], [0, rise]], t), m, cx, y, z);
}
function barrel(b: PB, m: THREE.Material, x: number, y0: number, z: number, r = 0.28, h = 0.86): void {
  const k = kit();
  b.cyl(r, h, m, x, y0 + h / 2, z, 'y', 14);
  for (const f of [0.3, 0.68]) b.cyl(r + 0.012, 0.03, k.dark, x, y0 + h * f, z, 'y', 14, undefined, true);
  b.cyl(r * 0.96, 0.012, k.dark, x, y0 + h + 0.006, z, 'y', 14);
}
function crate(b: PB, x: number, y0: number, z: number, s: number, ry = 0): void {
  const k = kit();
  b.at(x, y0, z, ry, () => {
    b.blk(s - 0.02, s - 0.02, s - 0.02, k.wood, 0, 0.01, 0);
    const t = 0.05;
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.blk(t, s, t, k.woodD, sx * (s / 2 - t / 2), 0, sz * (s / 2 - t / 2));
    for (const sz of [-1, 1]) { hb(b, s, t, t, k.woodD, 0, s - t / 2, sz * (s / 2 - t / 2)); hb(b, s, t, t, k.woodD, 0, t / 2, sz * (s / 2 - t / 2)); }
  });
}
function sack(b: PB, x: number, y0: number, z: number, ry: number): void {
  const k = kit();
  b.add(sphereGeo(0.5, 8, 5), k.canvas, x, y0 + 0.17, z, 0, ry, 0, 0.36, 0.36, 0.26);
  b.add(sphereGeo(0.5, 6, 4), k.canvas, x, y0 + 0.38, z, 0, ry, 0, 0.18, 0.14, 0.14);
}
/** Log along an axis with bark sides and end-grain caps. */
function log(b: PB, a: V3, c: V3, r: number): void {
  const k = kit();
  b.rod(a, c, r, k.bark, 9, true);
  const d = new THREE.Vector3(c[0] - a[0], c[1] - a[1], c[2] - a[2]).normalize().multiplyScalar(0.004);
  b.rod(a, [a[0] + d.x, a[1] + d.y, a[2] + d.z], r * 0.985, k.endGrain, 9);
  b.rod([c[0] - d.x, c[1] - d.y, c[2] - d.z], c, r * 0.985, k.endGrain, 9);
}
/** Catenary of string lights with glowing bulbs. */
function stringLights(b: PB, a: V3, c: V3, sag: number, n: number): void {
  const k = kit();
  b.cable(a, c, sag, 0.007, k.black, 12);
  const va = new THREE.Vector3(...a), vc = new THREE.Vector3(...c);
  const mid = va.clone().add(vc).multiplyScalar(0.5); mid.y -= sag;
  const curve = new THREE.QuadraticBezierCurve3(va, mid, vc);
  for (let i = 1; i < n; i++) {
    const p = curve.getPoint(i / n);
    b.cyl(0.012, 0.04, k.black, p.x, p.y - 0.02, p.z, 'y', 5);
    b.add(sphereGeo(0.032, 6, 4), k.bulb, p.x, p.y - 0.06, p.z, 0, 0, 0, 1, 1.25, 1);
  }
}
function sandbagRow(b: PB, x0: number, z0: number, x1: number, z1: number, y0: number, layers: number, r: () => number): void {
  const k = kit();
  const len = Math.hypot(x1 - x0, z1 - z0);
  const n = Math.max(1, Math.round(len / 0.48));
  const ry = -Math.atan2(z1 - z0, x1 - x0);
  for (let l = 0; l < layers; l++) {
    const off = l % 2 ? 0.5 : 0;
    for (let i = 0; i < n - (l % 2); i++) {
      const t = (i + 0.5 + off) / n;
      b.add(sphereGeo(0.5, 8, 5), k.sandbag, x0 + (x1 - x0) * t, y0 + 0.08 + l * 0.15, z0 + (z1 - z0) * t, (r() - 0.5) * 0.08, ry + (r() - 0.5) * 0.15, 0, len / n + 0.06, 0.19, 0.34);
    }
  }
}
function statusLight(b: PB, x: number, y: number, z: number): void {
  b.box(0.12, 0.12, 0.04, kit().dark, x, y, z - 0.03);
  b.mesh('statusLight', sphereGeo(0.06, 8, 6), kit().status, x, y, z);
}

/** Cinder-block wall along local X, outer face at z=0 (extends to -t). holes: [xa,xb,ya,yb]. */
function blockWall(b: PB, xa: number, xb: number, y0: number, y1: number, holes: [number, number, number, number][], t = 0.22): void {
  const k = kit();
  b.box(xb - xa, y1 - y0, t - 0.1, k.mortar, (xa + xb) / 2, (y0 + y1) / 2, -t / 2 - 0.05);
  const BH = 0.2, BW = 0.4, G = 0.012;
  const rows = Math.round((y1 - y0) / BH);
  for (let row = 0; row < rows; row++) {
    const ya = y0 + row * BH, yb = ya + BH;
    const cuts = holes.filter((h) => Math.min(yb, h[3]) - Math.max(ya, h[2]) > 0.05).map((h) => [h[0], h[1]] as [number, number]).sort((p, q) => p[0] - q[0]);
    const segs: [number, number][] = [];
    let cur = xa;
    for (const [ha, hbb] of cuts) { if (ha > cur) segs.push([cur, ha]); cur = Math.max(cur, hbb); }
    if (cur < xb) segs.push([cur, xb]);
    for (const [sa, sb] of segs) {
      let x = sa - ((row % 2) ? BW / 2 : 0);
      while (x < sb - 0.02) {
        const a = Math.max(x, sa), c = Math.min(x + BW, sb);
        if (c - a > 0.04) b.box(c - a - G, BH - G, 0.11, k.block, (a + c) / 2, ya + BH / 2, -0.055);
        x += BW;
      }
    }
  }
  // lintels
  for (const h of holes) if (h[3] < y1 - 0.1) b.box(h[1] - h[0] + 0.3, 0.18, t, k.block, (h[0] + h[1]) / 2, h[3] + 0.09, -t / 2 + 0.005);
}

// ---------------------------------------------------------------------------
// SHELTER HOUSE (4 x 4 m) - small salvaged family home
// ---------------------------------------------------------------------------
function buildShelterHouse(b: PB): Rig {
  const k = kit();
  const r = rng(101);
  const FY = 0.42, EY = 2.5, X = 1.5, ZB = -1.68, ZF = 1.0;
  const cz = (ZB + ZF) / 2, hd = (ZF - ZB) / 2, rise = 1.05;
  // piers + floor platform (incl. porch)
  for (const x of [-1.42, 0, 1.42]) for (const z of [ZB + 0.12, cz, ZF - 0.1, 1.62]) b.blk(0.3, 0.3, 0.3, k.block, x, 0, z, (r() - 0.5) * 0.2);
  b.blk(3.1, 0.12, ZF - ZB, k.woodD, 0, 0.3, cz);
  for (let z = ZF + 0.07; z < 1.74; z += 0.14) hb(b, 3.1 + (r() - 0.5) * 0.06, 0.05, 0.13, k.wood, (r() - 0.5) * 0.03, 0.395, z);
  hb(b, 3.1, 0.12, 0.06, k.woodD, 0, 0.3, 1.74); // porch rim
  for (const x of [-1.52, 1.52]) zb(b, 0.06, 0.12, 0.76, k.woodD, x, 0.3, 1.36);
  // step
  hb(b, 1.0, 0.06, 0.22, k.wood, -0.55, 0.21, 1.83);
  b.blk(0.9, 0.18, 0.2, k.block, -0.55, 0, 1.83);
  // wall core + corner posts + plates
  b.blk(X * 2 - 0.06, EY - FY, ZF - ZB - 0.06, k.woodD, 0, FY, cz);
  for (const sx of [-1, 1]) for (const z of [ZB, ZF]) b.blk(0.11, EY - FY + 0.05, 0.11, k.wood, sx * X, FY - 0.05, z);
  hb(b, X * 2 + 0.1, 0.1, 0.1, k.wood, 0, EY - 0.05, ZF); hb(b, X * 2 + 0.1, 0.1, 0.1, k.wood, 0, EY - 0.05, ZB);
  zb(b, 0.1, 0.1, ZF - ZB, k.wood, -X, EY - 0.05, cz); zb(b, 0.1, 0.1, ZF - ZB, k.wood, X, EY - 0.05, cz);
  // FRONT: blue lap siding left, red-oxide tin right
  b.at(0, 0, ZF, 0, () => {
    siding(b, k.sidB, -X + 0.05, 0.25, FY, EY - 0.1, 0.0, r);
    tin(b, k.tinRed, 0.2, X - 0.05, FY + 0.02, EY - 0.12, 0.02, r);
    b.box(0.5, 0.45, 0.03, k.rust, 1.15, FY + 0.45, 0.04, 0, 0, 0.12);
  });
  doorAt(b, -0.55, FY, ZF + 0.02, 0, 0.8, 1.88, k.woodD, k.wood);
  windowAt(b, 0.82, FY + 1.18, ZF + 0.035, 0, 0.66, 0.62, k.wood);
  // RIGHT (+X): galvanised tin, a white siding patch
  b.at(X, 0, cz, Math.PI / 2, () => {
    tin(b, k.roof, -hd + 0.06, hd - 0.06, FY + 0.02, EY - 0.1, 0.0, r);
    siding(b, k.sidW, -0.6, 0.5, FY + 0.15, FY + 1.05, 0.03, r);
  });
  // LEFT (-X): green siding + shuttered window
  b.at(-X, 0, cz, -Math.PI / 2, () => {
    siding(b, k.sidG, -hd + 0.06, hd - 0.06, FY, EY - 0.1, 0.0, r);
  });
  windowAt(b, -X - 0.03, FY + 1.2, cz + 0.1, -Math.PI / 2, 0.55, 0.55, k.woodD, { shutters: k.wood });
  // BACK: vertical boards + tin patch + boarded window
  b.at(0, 0, ZB, Math.PI, () => {
    vBoards(b, k.wood, -X + 0.05, X - 0.05, FY, EY - 0.1, 0.0, r);
    tin(b, k.tinGreen, -1.2, -0.2, FY + 0.1, FY + 1.2, 0.035, r);
  });
  windowAt(b, -0.75, FY + 1.25, ZB - 0.04, Math.PI, 0.5, 0.5, k.woodD, { boarded: true });
  // gables + roof
  const RY = EY + rise;
  for (const sx of [-1, 1]) {
    gableTri(b, k.wood, sx * (X - 0.02), EY, cz, hd + 0.02, rise, 0.05);
  }
  const ov = 0.22, RL = X * 2 + 0.55;
  const a = gableRoof(b, RY, cz, hd, rise, ov, RL, [k.roof, k.roof, k.tinGreen, k.roof, k.rustSheet], r);
  onSlope(b, RY, cz, a, 1, () => {
    b.box(0.7, 0.02, 0.5, k.rustSheet, -0.6, 0.06, 0.9, 0, 0.2, 0);
    for (const sx of [-1, 1]) b.box(0.12, 0.12, (hd + ov) / Math.cos(a), k.woodD, sx * (RL / 2 - 0.05), -0.04, (hd + ov) / Math.cos(a) / 2);
  });
  onSlope(b, RY, cz, a, -1, () => {
    for (const sx of [-1, 1]) b.box(0.12, 0.12, (hd + ov) / Math.cos(a), k.woodD, sx * (RL / 2 - 0.05), -0.04, (hd + ov) / Math.cos(a) / 2);
    b.box(0.9, 0.02, 0.6, k.tinRed, 0.4, 0.065, 1.0, 0, -0.15, 0);
  });
  hb(b, RL + 0.05, 0.13, 0.13, k.dark, 0, RY + 0.03, cz, Math.PI / 4);
  // stovepipe through the back slope
  const sxp = 0.9, szp = cz - 0.7;
  b.cyl(0.085, 1.9, k.dark, sxp, RY - 0.2, szp, 'y', 10);
  b.cyl(0.2, 0.12, k.rust, sxp, RY - 0.7 * Math.tan(a) + 0.05, szp, 'y', 10, 0.1);
  b.add(cylGeo(0, 0.2, 0.14, 10), k.dark, sxp, RY + 0.85, szp);
  for (let i = 0; i < 3; i++) { const an = i / 3 * TAU; b.rod([sxp + Math.cos(an) * 0.05, RY + 0.7, szp + Math.sin(an) * 0.05], [sxp + Math.cos(an) * 0.14, RY + 0.79, szp + Math.sin(an) * 0.14], 0.008, k.dark, 4); }
  // salvaged TV aerial on the ridge
  b.rod([-1.1, RY, cz], [-1.1, RY + 1.05, cz], 0.02, k.steel, 5);
  for (const [y, w] of [[0.65, 0.7], [0.82, 0.55], [0.98, 0.4]] as [number, number][]) b.rod([-1.1 - w / 2, RY + y, cz], [-1.1 + w / 2, RY + y, cz + 0.02], 0.009, k.steel, 4);
  b.cable([-1.1, RY + 0.6, cz], [-1.5, EY - 0.2, ZF + 0.05], 0.15, 0.008, k.black, 6);
  // porch awning over the door
  for (const x of [-1.2, 0.12]) b.blk(0.09, 2.3 - FY, 0.09, k.wood, x, FY, 1.66);
  hb(b, 1.55, 0.1, 0.1, k.woodD, -0.54, 2.32, 1.66);
  b.box(1.75, 0.025, 0.95, k.roof, -0.54, 2.45, 1.33, 0.2, 0, 0.02);
  b.box(1.75, 0.08, 0.06, k.woodD, -0.54, 2.53, ZF + 0.04);
  // lantern on a bracket by the door
  b.box(0.04, 0.04, 0.22, k.dark, 0.05, 2.12, ZF + 0.1);
  lantern(b, 0.05, 1.9, ZF + 0.2);
  b.anchor('lightAnchor', 0.05, 1.9, ZF + 0.45);
  statusLight(b, -1.05, 2.2, ZF + 0.07);
  // gutter + downpipe into a rain barrel
  const ez = ZF + ov - 0.04, ey = EY - ov * Math.tan(a) - 0.06;
  b.cyl(0.05, 1.5, k.metal, 0.98, ey, ez, 'x', 8, undefined, true);
  b.pipe([[1.7, ey, ez], [1.7, 1.25, ez], [1.7, 1.05, 0.72]], 0.035, k.metal, 6);
  b.blk(0.4, 0.15, 0.4, k.block, 1.7, 0, 0.72);
  b.cyl(0.22, 0.78, flat(0x2f5a80, 0.5, 0), 1.7, 0.15 + 0.39, 0.72, 'y', 14);
  for (const f of [0.35, 0.75]) b.cyl(0.226, 0.03, flat(0x264a6a, 0.5, 0), 1.7, 0.15 + 0.78 * f, 0.72, 'y', 14, undefined, true);
  // firewood stack under the left eave
  for (let row = 0; row < 5; row++) {
    for (let i = 0; i < 12; i++) {
      const z = ZB + 0.25 + i * 0.15 + (row % 2) * 0.075;
      if (z > 0.55) continue;
      const rr = 0.065 + r() * 0.015;
      log(b, [-1.9, 0.07 + row * 0.13, z], [-1.58 + r() * 0.02, 0.07 + row * 0.13, z], rr);
    }
  }
  // bucket + chopping stump by the steps
  b.cyl(0.13, 0.25, k.metal, 1.2, FY + 0.125, 1.45, 'y', 10, 0.15, true);
  return (p) => () => {};
}

// ---------------------------------------------------------------------------
// BUNKHOUSE (6 x 6 m) - container + cinder-block base, timber upper floor, external stair
// ---------------------------------------------------------------------------
function buildBunkhouse(b: PB): Rig {
  const k = kit();
  const r = rng(202);
  const X0 = -2.75, X1 = 1.7, XC = (X0 + X1) / 2, LEN = X1 - X0;
  const containerPaint = tint('factoryPanel', 0x4f7d78);
  // slab
  b.blk(4.7, 0.12, 4.85, k.slab, XC, 0, -0.5);
  const Y0 = 0.12;
  // --- container (back half of ground floor)
  const CZ0 = -2.85, CZ1 = -0.45, CH = 2.5, ccz = (CZ0 + CZ1) / 2;
  b.blk(LEN - 0.1, CH - 0.1, CZ1 - CZ0 - 0.1, containerPaint, XC, Y0 + 0.05, ccz);
  for (let x = X0 + 0.2; x < X1 - 0.1; x += 0.28) b.box(0.13, CH - 0.3, 0.06, containerPaint, x, Y0 + CH / 2, CZ0 + 0.02);
  for (let z = CZ0 + 0.25; z < CZ1 - 0.1; z += 0.28) b.box(0.06, CH - 0.3, 0.13, containerPaint, X1 - 0.02, Y0 + CH / 2, z);
  for (const x of [X0, X1]) for (const z of [CZ0, CZ1]) {
    b.blk(0.16, CH, 0.16, k.dark, x + (x < 0 ? 0.08 : -0.08), Y0, z + (z < -1 ? 0.08 : -0.08));
  }
  for (const y of [Y0 + 0.07, Y0 + CH - 0.07]) {
    b.box(LEN, 0.14, 0.12, k.dark, XC, y, CZ0 + 0.06);
    b.box(0.12, 0.14, CZ1 - CZ0, k.dark, X0 + 0.06, y, ccz);
    b.box(0.12, 0.14, CZ1 - CZ0, k.dark, X1 - 0.06, y, ccz);
  }
  // container end doors (-X) with locking bars
  b.box(0.04, CH - 0.35, CZ1 - CZ0 - 0.3, containerPaint, X0 - 0.01, Y0 + CH / 2, ccz);
  b.box(0.05, CH - 0.35, 0.025, k.dark, X0 - 0.03, Y0 + CH / 2, ccz);
  for (const z of [-0.85, -0.45, 0.45, 0.85]) {
    b.rod([X0 - 0.07, Y0 + 0.2, ccz + z], [X0 - 0.07, Y0 + CH - 0.2, ccz + z], 0.018, k.steel, 6);
    b.box(0.06, 0.1, 0.07, k.dark, X0 - 0.07, Y0 + 1.1, ccz + z);
  }
  b.box(0.03, 0.6, 0.7, k.rust, X0 - 0.04, Y0 + 0.6, ccz + 0.55, 0.1, 0, 0);
  // --- cinder-block front extension
  const FZ = 1.5, BH = 2.4;
  b.at(XC, Y0, FZ, 0, () => blockWall(b, -LEN / 2, LEN / 2, 0, BH, [[-1.85, -0.95, 0.8, 1.6], [-0.15, 0.75, 0, 2.0], [1.05, 1.85, 0.8, 1.6]]));
  b.at(X0, Y0, (CZ1 + FZ) / 2 - 0.1, -Math.PI / 2, () => blockWall(b, -1.0, 0.95, 0, BH, [[-0.4, 0.4, 0.8, 1.6]]));
  b.at(X1, Y0, (CZ1 + FZ) / 2 - 0.1, Math.PI / 2, () => blockWall(b, -0.95, 1.0, 0, BH, []));
  // openings (recessed)
  for (const x of [XC - 1.4, XC + 1.45]) windowAt(b, x, Y0 + 1.2, FZ - 0.095, 0, 0.76, 0.68, k.woodD, { cross: true });
  windowAt(b, X0 + 0.095, Y0 + 1.2, (CZ1 + FZ) / 2 - 0.1, -Math.PI / 2, 0.66, 0.66, k.woodD);
  doorAt(b, XC + 0.3, Y0, FZ - 0.095, 0, 0.72, 1.9, k.rust, k.woodD);
  // --- floor band + upper storey
  const U0 = 2.62, U1 = 4.85, UZ0 = -2.82, UZ1 = 1.82, ucz = (UZ0 + UZ1) / 2, uhd = (UZ1 - UZ0) / 2;
  b.blk(LEN + 0.1, 0.2, UZ1 - UZ0, k.woodD, XC, U0 - 0.1, ucz);
  for (let x = X0 + 0.15; x < X1; x += 0.45) b.box(0.08, 0.16, 0.12, k.woodD, x, U0 - 0.2, UZ1 - 0.02);
  for (const x of [X0 + 0.08, XC + 0.3 + 0.75, X1 - 0.08]) b.blk(0.14, U0 - 0.2 - Y0, 0.14, k.wood, x, Y0, UZ1 - 0.1);
  b.blk(LEN - 0.04, U1 - U0 - 0.1, UZ1 - UZ0 - 0.04, k.woodD, XC, U0 + 0.1, ucz);
  for (const x of [X0, X1]) for (const z of [UZ0, UZ1]) b.blk(0.12, U1 - U0 - 0.1, 0.12, k.wood, x + (x < 0 ? 0.04 : -0.04), U0 + 0.1, z + (z < 0 ? 0.04 : -0.04));
  b.at(XC, 0, UZ1, 0, () => {
    siding(b, k.sidY, -LEN / 2 + 0.06, 0.6, U0 + 0.12, U1, 0.0, r);
    siding(b, k.sidB, 0.6, LEN / 2 - 0.06, U0 + 0.12, U1, 0.0, r);
  });
  for (const x of [XC - 1.5, XC, XC + 1.5]) windowAt(b, x, U0 + 1.25, UZ1 + 0.035, 0, 0.7, 0.72, k.wood);
  b.at(X0, 0, ucz, -Math.PI / 2, () => vBoards(b, k.wood, -uhd + 0.06, uhd - 0.06, U0 + 0.12, U1, 0.0, r));
  for (const z of [ucz - 1.2, ucz + 1.2]) windowAt(b, X0 - 0.035, U0 + 1.25, z, -Math.PI / 2, 0.66, 0.7, k.woodD, { shutters: k.sidB });
  b.at(X1, 0, ucz, Math.PI / 2, () => {
    vBoards(b, k.wood, -uhd + 0.06, uhd - 0.06, U0 + 0.12, U1, 0.0, r);
    tin(b, k.tinRed, -0.6, 1.5, U0 + 0.2, U0 + 1.2, 0.035, r);
  });
  b.at(XC, 0, UZ0, Math.PI, () => {
    tin(b, k.roof, -LEN / 2 + 0.06, LEN / 2 - 0.06, U0 + 0.12, U1, 0.0, r);
  });
  windowAt(b, XC - 0.8, U0 + 1.25, UZ0 - 0.03, Math.PI, 0.66, 0.66, k.woodD);
  // upper door on the +X side, landing + stair
  doorAt(b, X1 + 0.03, U0 + 0.1, -1.75, Math.PI / 2, 0.8, 1.9, k.woodD, k.wood);
  const LX0 = X1 + 0.08, LX1 = 2.88, LZ0 = -2.55, LZ1 = -1.0;
  for (let x = LX0 + 0.07; x < LX1; x += 0.14) zb(b, 0.13, 0.05, LZ1 - LZ0, k.wood, x, U0 + 0.075, (LZ0 + LZ1) / 2);
  b.box(LX1 - LX0, 0.14, 0.08, k.woodD, (LX0 + LX1) / 2, U0 - 0.02, LZ0 + 0.04);
  b.box(LX1 - LX0, 0.14, 0.08, k.woodD, (LX0 + LX1) / 2, U0 - 0.02, LZ1 - 0.04);
  for (const z of [LZ0 + 0.06, LZ1 - 0.06]) b.blk(0.1, U0 - 0.05, 0.1, k.wood, LX1 - 0.06, 0, z);
  b.beam([LX1 - 0.06, 0.3, LZ0 + 0.06], [LX1 - 0.06, U0 - 0.15, LZ1 - 0.06], 0.07, k.wood);
  // rail around landing
  for (const z of [LZ0 + 0.06, -1.78]) b.blk(0.06, 1.0, 0.06, k.wood, LX1 - 0.06, U0 + 0.1, z);
  b.box(0.06, 0.06, LZ1 - LZ0, k.wood, LX1 - 0.06, U0 + 1.1, (LZ0 + LZ1) / 2);
  hb(b, LX1 - LX0, 0.06, 0.06, k.wood, (LX0 + LX1) / 2, U0 + 1.1, LZ0 + 0.06);
  // stair down to the front
  const SZ0 = LZ1, SZ1 = 2.85, SX0 = X1 + 0.18, SX1 = 2.86;
  const steps = 14;
  for (const x of [SX0 + 0.03, SX1 - 0.03]) b.beam([x, 0.13, SZ1 - 0.08], [x, U0 + 0.02, SZ0], 0.06, k.woodD, 0.22);
  for (let i = 0; i < steps; i++) {
    const t = (i + 0.5) / steps;
    hb(b, SX1 - SX0 - 0.02, 0.045, 0.25, k.wood, (SX0 + SX1) / 2, U0 * (1 - t) + 0.06, SZ0 + (SZ1 - SZ0) * t);
  }
  for (const z of [SZ1 - 0.1, (SZ0 + SZ1) / 2]) {
    const yy = U0 * (1 - (z - SZ0) / (SZ1 - SZ0));
    b.blk(0.05, 1.0, 0.05, k.wood, SX1 - 0.03, yy, z);
  }
  b.beam([SX1 - 0.03, 1.05, SZ1 - 0.1], [SX1 - 0.03, U0 + 1.1, SZ0], 0.05, k.wood);
  // roof
  const rise = 0.85, ov = 0.02, RY = U1 + rise;
  for (const sx of [X0, X1]) gableTri(b, k.wood, sx, U1, ucz, uhd, rise);
  b.push(XC, 0, 0);
  const a = gableRoof(b, RY, ucz, uhd, rise, ov, LEN + 0.22, [k.roof, k.tinGreen, k.roof, k.rustSheet, k.roof], r);
  b.pop();
  hb(b, LEN + 0.26, 0.13, 0.13, k.dark, XC, RY + 0.03, ucz, Math.PI / 4);
  b.cyl(0.08, 1.5, k.dark, XC - 1.3, RY - 0.1, ucz - 1.0, 'y', 10);
  b.add(cylGeo(0, 0.18, 0.14, 10), k.dark, XC - 1.3, RY + 0.72, ucz - 1.0);
  void a;
  // caged bulb over the ground door + status light
  b.box(0.05, 0.05, 0.25, k.dark, XC + 0.3, 2.3, FZ + 0.1);
  b.mesh('bulb', sphereGeo(0.06, 8, 6), k.bulb, XC + 0.3, 2.2, FZ + 0.2);
  b.add(cylGeo(0.05, 0.12, 0.08, 8, true), k.dark, XC + 0.3, 2.28, FZ + 0.2);
  b.anchor('lightAnchor', XC + 0.3, 2.15, FZ + 0.45);
  statusLight(b, XC + 0.85, Y0 + 2.0, FZ + 0.03);
  // bench + water drum by the front wall
  b.at(XC - 1.4, 0, FZ + 0.32, 0, () => {
    hb(b, 1.3, 0.05, 0.3, k.wood, 0, 0.45, 0);
    for (const sx of [-0.55, 0.55]) b.blk(0.08, 0.42, 0.26, k.woodD, sx, 0.0, 0);
  });
  barrel(b, containerPaint, X0 + 0.3, 0.12, FZ + 0.35, 0.26, 0.82);
  // washing line
  const LY = 1.85, lz = 2.55, la = X0 + 0.15, lb = XC + 1.6;
  for (const x of [la, lb]) {
    b.blk(0.08, LY + 0.1, 0.08, k.woodD, x, 0, lz);
    hb(b, 0.08, 0.06, 0.4, k.woodD, x, LY + 0.05, lz);
  }
  const ln = b.sub('laundry', (la + lb) / 2, LY, lz);
  ln.cable([la - (la + lb) / 2, 0, 0], [lb - (la + lb) / 2, 0, 0], 0.12, 0.006, k.rope, 10);
  const cloths: [number, number, number, Mat][] = [
    [-1.45, 0.42, 0.6, k.canvas], [-0.85, 0.36, 0.42, tint('fabric', 0x8aa0c8)], [-0.28, 0.6, 0.85, k.canvas],
    [0.35, 0.34, 0.46, tint('fabric', 0xb85a48)], [0.9, 0.44, 0.38, tint('fabric', 0x8aa0c8)], [1.4, 0.3, 0.5, tint('fabric', 0xb85a48)],
  ];
  const half = (lb - la) / 2;
  for (const [x, w, h, m] of cloths) {
    const tt = (x + half) / (2 * half);
    const sag = 0.24 * tt * (1 - tt);
    ln.box(w, h, 0.012, m, x, -sag - h / 2 - 0.01, 0, 0, (r() - 0.5) * 0.2, (r() - 0.5) * 0.05);
    for (const s of [-1, 1]) ln.box(0.015, 0.06, 0.025, k.woodD, x + s * w * 0.4, -sag, 0);
  }
  return (p) => {
    const l = p.get('laundry');
    return (_dt, _run, t) => { if (l) l.rotation.x = Math.sin(t * 1.4) * 0.09 + Math.sin(t * 3.1) * 0.03; };
  };
}

// ---------------------------------------------------------------------------
// MESS HALL (6 x 6 m) - open-sided timber hall, tarp awning, brick hearth, string lights
// ---------------------------------------------------------------------------
function buildMessHall(b: PB): Rig {
  const k = kit();
  const r = rng(303);
  const FY = 0.3, PX = 2.55, EY = 2.75, rise = 1.15, RY = EY + rise;
  const ZB = -2.62, ZF = 1.8, cz = (ZB + ZF) / 2;
  // deck on block piers
  for (const x of [-2.5, -0.8, 0.8, 2.5]) for (const z of [-2.6, -1.1, 0.4, 1.8]) b.blk(0.28, FY - 0.06, 0.28, k.block, x, 0, z);
  b.add(boxGeo(5.6, 0.07, 4.65), k.floor, 0, FY - 0.035, -0.4);
  hb(b, 5.62, 0.16, 0.07, k.woodD, 0, FY - 0.08, 1.9);
  // posts + plates + tie beams + rafters
  const postZ = [ZB, -0.4, ZF];
  for (const sx of [-1, 1]) for (const z of postZ) {
    b.blk(0.18, EY - FY, 0.18, k.wood, sx * PX, FY, z);
    b.beam([sx * PX, EY - 0.7, z + (z > ZB ? -0.55 : 0.55)], [sx * PX, EY - 0.12, z], 0.08, k.woodD);
    if (z !== ZB && z !== ZF) b.beam([sx * PX, EY - 0.7, z + 0.55], [sx * PX, EY - 0.12, z], 0.08, k.woodD);
  }
  for (const sx of [-1, 1]) zb(b, 0.16, 0.18, ZF - ZB + 0.3, k.woodD, sx * PX, EY - 0.09, cz);
  for (const z of postZ) {
    hb(b, PX * 2 + 0.2, 0.18, 0.14, k.woodD, 0, EY - 0.1, z);
    b.blk(0.12, rise - 0.05, 0.12, k.wood, 0, EY, z);
    for (const sx of [-1, 1]) b.beam([sx * PX * 0.92, EY, z], [0, RY - 0.1, z], 0.1, k.wood);
  }
  zb(b, 0.14, 0.16, ZF - ZB + 0.3, k.woodD, 0, RY - 0.12, cz);
  // roof (ridge along Z): build in a rotated frame
  const hd = PX, ov = 0.28;
  b.push(0, 0, cz, 0, Math.PI / 2, 0);
  gableRoof(b, RY, 0, hd, rise, ov, ZF - ZB + 0.45, [k.roof, k.roof, k.tinRed, k.roof, k.rustSheet], r);
  b.pop();
  // back wall + gable
  b.at(0, 0, ZB - 0.11, Math.PI, () => vBoards(b, k.wood, -PX, PX, FY, EY - 0.18, 0.0, r, 0.2));
  gableTriZ(b, k.wood, ZB - 0.1, EY - 0.02, 0, PX + 0.05, rise + 0.02);
  // side half walls
  for (const sx of [-1, 1]) {
    b.at(sx * (PX + 0.1), 0, -1.5, sx * Math.PI / 2, () => hBoards(b, k.woodD, -1.0, 1.0, FY, FY + 0.95, 0, r, 0.19));
    zb(b, 0.14, 0.05, 2.1, k.wood, sx * (PX + 0.08), FY + 0.97, -1.5);
  }
  // brick hearth + chimney (back-left)
  const hx = -1.75, hz = -2.22;
  b.blk(1.4, 0.85, 0.8, k.brick, hx, FY, hz);
  b.blk(1.5, 0.06, 0.9, k.dark, hx, FY + 0.85, hz);
  b.box(0.6, 0.38, 0.05, k.black, hx - 0.2, FY + 0.42, hz + 0.39);
  b.box(0.5, 0.12, 0.04, k.ember, hx - 0.2, FY + 0.3, hz + 0.39);
  for (let i = 0; i < 7; i++) b.rod([hx - 0.6 + i * 0.1, FY + 0.95, hz - 0.3], [hx - 0.6 + i * 0.1, FY + 0.95, hz + 0.3], 0.01, k.dark, 4);
  b.box(0.7, 0.03, 0.03, k.dark, hx - 0.3, FY + 0.95, hz - 0.3);
  b.box(0.7, 0.03, 0.03, k.dark, hx - 0.3, FY + 0.95, hz + 0.3);
  b.cyl(0.22, 0.32, k.dark, hx + 0.35, FY + 1.09, hz, 'y', 14);
  b.add(torusGeo(0.22, 0.015, 4, 14), k.dark, hx + 0.35, FY + 1.25, hz, Math.PI / 2, 0, 0);
  b.cyl(0.1, 0.16, k.metal, hx - 0.35, FY + 1.0, hz + 0.1, 'y', 10, 0.07);
  b.blk(0.56, RY + 0.6 - FY - 0.85, 0.56, k.brick, -2.05, FY + 0.85, -2.35);
  b.blk(0.66, 0.1, 0.66, k.dark, -2.05, RY + 0.6, -2.35);
  // prep counter + shelf with tins along the back wall
  b.blk(2.0, 0.85, 0.55, k.woodD, 0.2, FY, -2.35);
  hb(b, 2.1, 0.05, 0.62, k.wood, 0.2, FY + 0.875, -2.33);
  hb(b, 2.1, 0.04, 0.26, k.wood, 0.2, FY + 1.6, -2.5);
  hb(b, 2.1, 0.04, 0.26, k.wood, 0.2, FY + 2.05, -2.5);
  for (let i = 0; i < 11; i++) {
    const shelfY = i % 2 ? FY + 1.62 : FY + 2.07;
    const rr = 0.04 + r() * 0.03;
    b.cyl(rr, 0.1 + r() * 0.12, i % 3 ? k.metal : k.white, -0.7 + i * 0.18, shelfY + 0.08, -2.48, 'y', 8);
  }
  b.cyl(0.18, 0.3, k.metal, 0.8, FY + 1.05, -2.3, 'y', 12);
  hb(b, 0.5, 0.03, 0.3, k.wood, -0.3, FY + 0.915, -2.3);
  // long tables + benches
  for (const tx of [-1.05, 1.25]) {
    const tz0 = -1.45, tz1 = 1.35, tcz = (tz0 + tz1) / 2, TL = tz1 - tz0;
    zb(b, 0.82, 0.06, TL, k.wood, tx, FY + 0.75, tcz);
    for (const z of [tz0 + 0.3, tz1 - 0.3]) {
      b.blk(0.08, 0.72, 0.08, k.woodD, tx - 0.3, FY, z); b.blk(0.08, 0.72, 0.08, k.woodD, tx + 0.3, FY, z);
      hb(b, 0.6, 0.06, 0.06, k.woodD, tx, FY + 0.18, z);
    }
    zb(b, 0.06, 0.06, TL - 0.6, k.woodD, tx, FY + 0.18, tcz);
    for (const s of [-1, 1]) {
      zb(b, 0.3, 0.05, TL - 0.1, k.wood, tx + s * 0.68, FY + 0.45, tcz);
      for (const z of [tz0 + 0.3, tz1 - 0.3]) b.blk(0.24, 0.43, 0.06, k.woodD, tx + s * 0.68, FY, z);
    }
    for (let i = 0; i < 6; i++) {
      const z = tz0 + 0.3 + i * 0.44;
      for (const s of [-1, 1]) {
        if (r() < 0.25) continue;
        b.cyl(0.1, 0.015, k.white, tx + s * 0.24, FY + 0.79, z, 'y', 12);
        if (r() < 0.6) b.cyl(0.035, 0.08, k.metal, tx + s * 0.12, FY + 0.82, z + 0.12, 'y', 8);
      }
    }
  }
  lantern(b, -1.05, FY + 0.92, -0.05, false);
  // front tarp awning on poles
  const AZ0 = ZF + 0.12, AZ1 = 2.85;
  for (const sx of [-1, 1]) {
    b.cyl(0.045, 2.25, k.wood, sx * 2.3, 1.125, AZ1 - 0.05, 'y', 7);
    b.rod([sx * 2.3, 2.2, AZ1 - 0.05], [sx * 2.85, 0.02, AZ1 - 0.02], 0.008, k.rope, 4);
  }
  b.box(4.85, 0.02, AZ1 - AZ0 + 0.1, k.tarp, 0, (EY - 0.08 + 2.22) / 2, (AZ0 + AZ1) / 2, Math.atan2(EY - 0.08 - 2.22, AZ1 - AZ0), 0, 0);
  b.cyl(0.035, 4.75, k.tarp, 0, 2.23, AZ1 - 0.05, 'x', 6);
  // hanging MESS sign (swings)
  const CY = EY + 0.72;
  hb(b, 2 * PX * (1 - (CY - EY) / rise) + 0.1, 0.14, 0.1, k.woodD, 0, CY, ZF + 0.1);
  const sg = b.sub('sign', 0, CY - 0.07, ZF + 0.12);
  for (const sx of [-0.55, 0.55]) sg.rod([sx, 0, 0], [sx, -0.14, 0], 0.008, k.dark, 4);
  sg.box(1.42, 0.46, 0.05, k.woodD, 0, -0.37, 0);
  sg.add(new THREE.PlaneGeometry(1.36, 0.42), signMess(), 0, -0.37, 0.027);
  // string lights criss-crossing under the roof + along the awning
  const SY = EY - 0.22;
  stringLights(b, [-PX, SY, ZF], [PX, SY, -0.4], 0.45, 14);
  stringLights(b, [PX, SY, ZF], [-PX, SY, -0.4], 0.45, 14);
  stringLights(b, [-PX, SY, -0.4], [PX, SY, ZB + 0.1], 0.4, 12);
  stringLights(b, [-2.3, 2.15, AZ1 - 0.08], [2.3, 2.15, AZ1 - 0.08], 0.25, 13);
  // supplies: barrel, crates, sacks, firewood
  barrel(b, k.metal, 2.2, FY, -2.25);
  crate(b, 1.65, FY, -2.35, 0.45, 0.2);
  crate(b, 1.7, FY + 0.45, -2.35, 0.36, -0.15);
  sack(b, 2.25, FY, -1.6, 0.4); sack(b, 2.0, FY, -1.35, 1.2);
  for (let i = 0; i < 9; i++) log(b, [-2.48, FY + 0.06 + Math.floor(i / 3) * 0.12, -1.55 + (i % 3) * 0.13 + (Math.floor(i / 3) % 2) * 0.06], [-1.95, FY + 0.06 + Math.floor(i / 3) * 0.12, -1.55 + (i % 3) * 0.13 + (Math.floor(i / 3) % 2) * 0.06], 0.06);
  b.anchor('lightAnchor', 0, EY - 0.4, -0.4);
  statusLight(b, PX - 0.05, 1.6, ZF + 0.1);
  return (p) => {
    const s = p.get('sign');
    return (_dt, _run, t) => { if (s) s.rotation.x = Math.sin(t * 1.3) * 0.05 + Math.sin(t * 2.9) * 0.015; };
  };
}

// ---------------------------------------------------------------------------
// WATER TOWER (4 x 4 m) - wooden stave tank on a braced timber frame
// ---------------------------------------------------------------------------
function buildWaterTower(b: PB): Rig {
  const k = kit();
  const r = rng(404);
  const G = 1.62, T = 1.22, DY = 4.2;
  const legAt = (sx: number, sz: number, y: number): V3 => { const f = y / DY; const c = G + (T - G) * f; return [sx * c, y, sz * c]; };
  // ground: muddy patch
  b.add(cylGeo(1.2, 1.25, 0.03, 16), M('mud'), 0.0, 0.015, 1.2, 0, 0, 0, 1, 1, 0.55);
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
    b.blk(0.45, 0.3, 0.45, k.conc, sx * (G - 0.05), 0, sz * (G - 0.05));
    b.beam(legAt(sx, sz, 0.25), legAt(sx, sz, DY + 0.05), 0.2, k.woodD);
  }
  // girts + cross bracing on each face
  const levels = [0.9, 2.55, DY - 0.05];
  for (const y of levels) {
    for (const s of [-1, 1]) {
      b.beam(legAt(-1, s, y), legAt(1, s, y), 0.12, k.woodD);
      b.beam(legAt(s, -1, y), legAt(s, 1, y), 0.12, k.woodD);
    }
  }
  for (let i = 0; i < levels.length - 1; i++) {
    const y0 = levels[i] + 0.05, y1 = levels[i + 1] - 0.05;
    for (const s of [-1, 1]) {
      b.beam(legAt(-1, s, y0), legAt(1, s, y1), 0.08, k.wood);
      b.beam(legAt(1, s, y0), legAt(-1, s, y1), 0.08, k.wood);
      b.beam(legAt(s, -1, y0), legAt(s, 1, y1), 0.08, k.wood);
      b.beam(legAt(s, 1, y0), legAt(s, -1, y1), 0.08, k.wood);
    }
  }
  // platform + catwalk railing
  for (let x = -1.42; x < 1.43; x += 0.142) zb(b, 0.135, 0.06, 2.9 + (r() - 0.5) * 0.05, k.wood, x, DY + 0.08, 0);
  for (const s of [-1, 1]) { hb(b, 2.95, 0.14, 0.1, k.woodD, 0, DY + 0.0, s * 1.42); zb(b, 0.1, 0.14, 2.95, k.woodD, s * 1.42, DY + 0.0, 0); }
  const RY = DY + 0.11;
  for (const [x, z] of [[-1.4, -1.4], [1.4, -1.4], [-1.4, 1.4], [1.4, 1.4], [0, -1.4], [-1.4, 0], [1.4, 0]] as [number, number][]) b.blk(0.06, 0.95, 0.06, k.wood, x, RY, z);
  hb(b, 2.85, 0.06, 0.06, k.wood, 0, RY + 0.95, -1.4);
  zb(b, 0.06, 0.06, 2.85, k.wood, -1.4, RY + 0.95, 0);
  zb(b, 0.06, 0.06, 2.85, k.wood, 1.4, RY + 0.95, 0);
  hb(b, 1.1, 0.06, 0.06, k.wood, -0.85, RY + 0.95, 1.4); // front rail with gap for ladder
  b.blk(0.06, 0.95, 0.06, k.wood, -0.3, RY, 1.4);
  hb(b, 0.35, 0.06, 0.06, k.wood, 1.22, RY + 0.95, 1.4);
  b.blk(0.06, 0.95, 0.06, k.wood, 1.05, RY, 1.4);
  // stave tank
  const TR = 1.18, TH = 2.15, T0 = RY + 0.02;
  for (let i = 0; i < 5; i++) zb(b, 0.14, 0.14, 2.5, k.woodD, -1.0 + i * 0.5, T0 + 0.07, 0);
  b.cyl(TR, TH, k.wood, 0, T0 + 0.14 + TH / 2, 0, 'y', 26);
  for (let i = 0; i < 5; i++) b.add(torusGeo(TR + 0.012, 0.02, 4, 26), k.dark, 0, T0 + 0.35 + i * (TH - 0.4) / 4, 0, Math.PI / 2, 0, 0);
  b.box(0.36, 0.5, 0.03, k.woodD, 0.3, T0 + 1.0, TR - 0.01, 0, 0.25, 0.04);
  const CY = T0 + 0.14 + TH;
  b.add(cylGeo(0.06, TR + 0.2, 0.78, 26), k.roof, 0, CY + 0.39, 0);
  b.add(torusGeo(TR + 0.19, 0.03, 4, 26), k.dark, 0, CY + 0.01, 0, Math.PI / 2, 0, 0);
  b.add(cylGeo(0.0, 0.07, 0.14, 8), k.dark, 0, CY + 0.85, 0);
  // hatch + tank ladder
  b.box(0.4, 0.04, 0.35, k.roof, 0.55, CY + 0.15, 0.55, 0.47, 0.78, 0);
  ladder(b, [0.55, T0 + 0.1, TR + 0.06], [0.55, CY + 0.15, TR - 0.12], k.dark, 0.36);
  // windvane on top
  const v = b.sub('vane', 0, CY + 0.9, 0);
  v.rod([0, 0, 0], [0, 0.55, 0], 0.012, k.dark, 5);
  v.box(0.62, 0.02, 0.02, k.dark, 0, 0.42, 0);
  v.add(cylGeo(0, 0.06, 0.16, 4), k.dark, 0.35, 0.42, 0, 0, 0, -Math.PI / 2);
  v.box(0.18, 0.14, 0.008, k.dark, -0.3, 0.45, 0, 0, 0, 0);
  v.box(0.02, 0.02, 0.4, k.dark, 0, 0.3, 0);
  // access ladder (front)
  ladder(b, [0.6, 0.0, 1.78], [0.6, RY + 0.9, 1.44], k.woodD, 0.44);
  // outlet pipe down to the tap + trough
  b.pipe([[-0.25, T0 + 0.1, -0.25], [-0.25, 0.95, -0.25], [-0.25, 0.95, 1.2], [-0.25, 0.85, 1.38]], 0.06, k.dark, 8);
  for (const y of [1.0, 2.6]) b.box(0.08, 0.08, 0.5, k.dark, -0.25, y, -0.55);
  b.add(torusGeo(0.1, 0.015, 4, 10), k.rust, -0.25, 1.07, 1.1, 0, 0, 0);
  b.rod([-0.25, 0.95, 1.1], [-0.25, 1.07, 1.1], 0.012, k.dark, 4);
  b.blk(0.1, 0.95, 0.1, k.woodD, -0.25, 0, 1.08);
  // trough (planked) with water
  b.at(-0.25, 0, 1.6, 0, () => {
    b.blk(1.3, 0.06, 0.55, k.woodD, 0, 0.12, 0);
    for (const s of [-1, 1]) { hb(b, 1.3, 0.38, 0.05, k.wood, 0, 0.33, s * 0.25); zb(b, 0.05, 0.38, 0.55, k.wood, s * 0.63, 0.33, 0); }
    for (const s of [-0.5, 0.5]) b.blk(0.08, 0.12, 0.6, k.woodD, s, 0, 0);
    b.box(1.2, 0.01, 0.45, k.water, 0, 0.44, 0);
    b.add(torusGeo(0.18, 0.012, 4, 10, Math.PI), k.dark, 0.85, 0.36, 0.15, 0, 0, 0);
  });
  b.cyl(0.13, 0.26, k.metal, 0.75, 0.13, 1.25, 'y', 10, 0.15, true);
  // lantern on the front-left leg
  const lp = legAt(-1, 1, 2.0);
  b.box(0.04, 0.04, 0.3, k.dark, lp[0] + 0.05, lp[1] + 0.25, lp[2] + 0.18);
  lantern(b, lp[0] + 0.05, lp[1], lp[2] + 0.3);
  b.anchor('lightAnchor', lp[0] + 0.1, lp[1], lp[2] + 0.5);
  statusLight(b, -0.25, 0.75, 1.14);
  return (p) => {
    const vane = p.get('vane');
    let ang = 0;
    return (dt, _run, t) => { if (!vane) return; ang += dt * 0.15; vane.rotation.y = Math.sin(ang) * 1.2 + Math.sin(t * 1.7) * 0.08; };
  };
}

// ---------------------------------------------------------------------------
// GUARD POST (2 x 2 m) - raised sandbagged lookout with spotlight
// ---------------------------------------------------------------------------
const GUARD_STAND_Y = 2.2;
function buildGuardPost(b: PB): Rig {
  const k = kit();
  const r = rng(505);
  const P = 0.8, DY = GUARD_STAND_Y, TF = 3.55, TB = 3.3;
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
    b.blk(0.3, 0.15, 0.3, k.conc, sx * P, 0, sz * P);
    b.blk(0.14, (sz > 0 ? TF : TB) - 0.1, 0.14, k.woodD, sx * P, 0.1, sz * P);
  }
  // bracing
  for (const s of [-1, 1]) {
    b.beam([-P, 0.3, s * P], [P, DY - 0.25, s * P], 0.07, k.wood);
    b.beam([s * P, 0.3, -P], [s * P, DY - 0.25, P], 0.07, k.wood);
    hb(b, 1.75, 0.1, 0.06, k.wood, 0, 0.35, s * (P + 0.09));
  }
  // platform
  for (const s of [-1, 1]) { hb(b, 1.86, 0.16, 0.08, k.woodD, 0, DY - 0.15, s * (P + 0.1)); zb(b, 0.08, 0.16, 1.86, k.woodD, s * (P + 0.1), DY - 0.15, 0); }
  for (let x = -0.86; x < 0.87; x += 0.143) zb(b, 0.135, 0.05, 1.86, k.wood, x, DY - 0.025, 0);
  // sandbag ring (gap at the back for the ladder)
  const S = 0.66;
  sandbagRow(b, -S + 0.12, S, S - 0.12, S, DY, 4, r);
  sandbagRow(b, S, S - 0.12, S, -S + 0.12, DY, 4, r);
  sandbagRow(b, -S, -S + 0.12, -S, S - 0.12, DY, 4, r);
  sandbagRow(b, -S + 0.1, -S, -0.3, -S, DY, 4, r);
  sandbagRow(b, 0.3, -S, S - 0.1, -S, DY, 4, r);
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) for (let l = 0; l < 4; l++) b.add(sphereGeo(0.5, 8, 5), k.sandbag, sx * S, DY + 0.08 + l * 0.15, sz * S, 0, Math.PI / 4 + l * 0.3, 0, 0.42, 0.19, 0.32);
  // roof
  hb(b, 1.8, 0.12, 0.12, k.woodD, 0, TF - 0.06, P);
  hb(b, 1.8, 0.12, 0.12, k.woodD, 0, TB - 0.06, -P);
  const ra = Math.atan2(TF - TB, 2 * P);
  b.box(1.84, 0.03, 1.84, k.roof, 0, (TF + TB) / 2 + 0.02, 0, -ra, 0, 0);
  b.box(0.9, 0.025, 0.7, k.rustSheet, 0.4, (TF + TB) / 2 + 0.045, 0.4, -ra, 0.2, 0);
  for (const s of [-1, 1]) b.box(0.06, 0.06, 1.84, k.woodD, s * 0.55, (TF + TB) / 2 + 0.05, 0, -ra, 0, 0);
  // ladder at the back
  ladder(b, [0, 0.0, -0.9], [0, DY + 0.75, -0.83], k.woodD, 0.42);
  // spotlight on a post at the front-right corner
  b.rod([0.45, DY, 0.45], [0.45, DY + 0.75, 0.45], 0.035, k.dark, 6);
  const sp = b.sub('spot', 0.45, DY + 0.8, 0.45);
  sp.box(0.36, 0.04, 0.06, k.dark, 0, 0, 0);
  for (const s of [-1, 1]) sp.box(0.03, 0.22, 0.05, k.dark, s * 0.17, 0.1, 0);
  sp.cyl(0.13, 0.3, k.dark, 0, 0.16, 0.0, 'z', 14);
  sp.cyl(0.15, 0.04, k.dark, 0, 0.16, 0.16, 'z', 14, undefined, true);
  const lens = emissiveVariant('spotLens', 'emissiveWarm', 0x806a4a, 0xfff0d0, 2.2);
  sp.add(cylGeo(0.125, 0.125, 0.01, 14), lens, 0, 0.16, 0.152, Math.PI / 2, 0, 0);
  sp.cable([0, 0.05, -0.15], [0.05, -0.6, -0.1], 0.1, 0.01, k.black, 5);
  b.anchor('lightAnchor', 0.45, DY + 0.96, 0.75);
  // alarm: hanging rail-iron + striker
  b.cable([-0.55, TB + 0.1, -0.55], [-0.55, TB - 0.45, -0.55], 0.0, 0.006, k.dark, 2);
  b.cyl(0.03, 0.4, k.rust, -0.55, TB - 0.62, -0.55, 'y', 6);
  b.rod([-0.48, TB - 0.5, -0.5], [-0.42, TB - 0.85, -0.45], 0.012, k.dark, 4);
  statusLight(b, -P, 1.5, P + 0.1);
  return (p) => {
    const s = p.get('spot');
    let a = 0;
    return (dt, run, t) => {
      if (!s) return;
      const target = run ? Math.sin(t * 0.45) * 1.1 : 0;
      a += (target - a) * Math.min(1, dt * 2);
      s.rotation.y = a;
      s.rotation.x = run ? 0.12 + Math.sin(t * 0.31) * 0.06 : 0.1;
    };
  };
}

// ---------------------------------------------------------------------------
// LUMBER YARD (6 x 6 m) - open sawmill with lean-to, saw bench, log pile, plank stacks
// ---------------------------------------------------------------------------
function buildLumberYard(b: PB): Rig {
  const k = kit();
  const r = rng(606);
  b.blk(5.85, 0.03, 5.85, k.dirt, 0, 0, 0);
  const Y = 0.03;
  // lean-to: high at the front (open side), low at the back
  const ZB = -2.78, ZF = -0.3, HB = 2.55, HF = 3.15;
  const xs = [-2.78, -0.95, 0.95, 2.78];
  for (const x of xs) {
    b.blk(0.16, HB - Y, 0.16, k.woodD, x, Y, ZB);
    b.blk(0.16, HF - Y, 0.16, k.woodD, x, Y, ZF);
    b.beam([x, HF - 0.75, ZF], [x, HF - 0.1, ZF + 0.0 - 0.55], 0.08, k.wood);
  }
  hb(b, 5.75, 0.18, 0.14, k.woodD, 0, HB - 0.05, ZB);
  hb(b, 5.75, 0.18, 0.14, k.woodD, 0, HF - 0.05, ZF);
  const ra = Math.atan2(HF - HB, ZF - ZB);
  for (const x of [-2.4, -1.5, -0.5, 0.5, 1.5, 2.4]) b.box(0.07, 0.12, 2.75, k.wood, x, (HB + HF) / 2 + 0.07, (ZB + ZF) / 2, -ra, 0, 0);
  // roof sheets, mismatched
  {
    const L = 2.75 / Math.cos(ra), zc = -1.525, yc = (HB + HF) / 2 + 0.15 + (zc - (ZB + ZF) / 2) * Math.tan(ra);
    let x = -2.9; let i = 0;
    const mats = [k.roof, k.tinRed, k.roof, k.rustSheet, k.roof];
    while (x < 2.9) {
      const w = Math.min(2.9 - x, 0.9 + r() * 0.8);
      b.box(w + 0.03, 0.03, L, mats[i % mats.length], x + w / 2, yc + (i % 2) * 0.008, zc, -ra, 0, (r() - 0.5) * 0.01);
      x += w; i++;
    }
  }
  // back wind-break planks (part height) + hanging saws
  b.at(0, 0, ZB - 0.1, Math.PI, () => vBoards(b, k.wood, -2.7, 0.9, Y, 1.5, 0, r, 0.22));
  b.box(1.2, 0.18, 0.006, k.steel, -1.6, 1.25, ZB + 0.05);
  b.box(0.08, 0.1, 0.05, k.wood, -2.25, 1.25, ZB + 0.06); b.box(0.08, 0.1, 0.05, k.wood, -0.95, 1.25, ZB + 0.06);
  b.add(torusGeo(0.25, 0.012, 4, 14, Math.PI), k.rust, -0.2, 1.05, ZB + 0.06, 0, 0, 0);
  b.box(0.5, 0.06, 0.005, k.steel, -0.2, 1.06, ZB + 0.06);
  // saw bench
  const BX = -0.35, BZ = -1.45, BY = 0.88;
  b.at(BX, Y, BZ, 0, () => {
    hb(b, 2.6, 0.08, 0.38, k.woodD, 0, BY - 0.04, -0.27);
    hb(b, 2.6, 0.08, 0.38, k.woodD, 0, BY - 0.04, 0.27);
    for (const sx of [-1.15, 1.15]) for (const sz of [-0.38, 0.38]) b.blk(0.12, BY - 0.08, 0.12, k.woodD, sx, 0, sz);
    for (const sz of [-0.38, 0.38]) hb(b, 2.3, 0.08, 0.06, k.wood, 0, 0.25, sz);
    for (const sx of [-1.15, 1.15]) zb(b, 0.06, 0.08, 0.76, k.wood, sx, 0.25, 0);
    // engine block driving the arbor via a belt
    b.blk(0.62, 0.48, 0.5, flat(0x4a5a3c, 0.6, 0.3), 0.55, 0.06, 0);
    for (let i = 0; i < 5; i++) b.box(0.03, 0.4, 0.5, k.dark, 0.32 + i * 0.12, 0.5, 0);
    b.cyl(0.06, 0.22, k.dark, 0.85, 0.48, 0.0, 'x', 8);
    b.cyl(0.1, 0.06, k.dark, 0.25, 0.32, 0.12, 'z', 12);
    b.beam([0.25, 0.32, 0.17], [0.0, BY, 0.17], 0.03, k.rubber, 0.02);
    b.add(boxGeo(0.12, 0.1, 0.06), k.dark, 0.95, 0.66, 0.0);
    b.cyl(0.035, 0.6, k.rust, 0.95, 1.0, 0, 'y', 6);
    // riving knife + table fence
    b.box(0.08, 0.32, 0.01, k.dark, -0.45, BY + 0.12, 0);
    hb(b, 2.4, 0.08, 0.05, k.wood, 0, BY + 0.04, -0.4);
    // log being fed + offcut plank
    log(b, [-1.25, BY + 0.15, 0.05], [-0.42, BY + 0.15, 0.05], 0.14);
    hb(b, 0.9, 0.05, 0.22, k.wood, 0.85, BY + 0.025, 0.04, 0, 0.04, 0);
  });
  const bl = b.sub('blade', BX, Y + BY, BZ);
  bl.cyl(0.38, 0.008, k.steel, 0, 0, 0, 'z', 28);
  bl.cyl(0.07, 0.03, k.dark, 0, 0, 0, 'z', 10);
  for (let i = 0; i < 24; i++) {
    const an = (i / 24) * TAU;
    bl.box(0.045, 0.03, 0.009, k.steel, Math.cos(an) * 0.39, Math.sin(an) * 0.39, 0, 0, 0, an + 0.4);
  }
  // sawdust heap under the blade
  b.add(sphereGeo(0.5, 10, 5, TAU, Math.PI / 2), M('sand'), BX + 0.15, Y, BZ, 0, 0, 0, 0.9, 0.22, 0.7);
  // plank stack under the roof (right)
  const stack = (x0: number, z0: number, layers: number, along: 'x' | 'z', L: number, n: number): void => {
    for (let l = 0; l < layers; l++) {
      const y = Y + 0.1 + l * 0.085;
      for (let i = 0; i < n; i++) {
        const off = (i - (n - 1) / 2) * 0.24;
        if (along === 'z') zb(b, 0.22, 0.05, L + (r() - 0.5) * 0.1, k.wood, x0 + off, y + 0.025, z0 + (r() - 0.5) * 0.06);
        else hb(b, L + (r() - 0.5) * 0.1, 0.05, 0.22, k.wood, x0 + (r() - 0.5) * 0.06, y + 0.025, z0 + off);
      }
      // stickers
      for (const t of [-0.42, 0, 0.42]) {
        if (along === 'z') hb(b, n * 0.24, 0.035, 0.04, k.woodD, x0, y + 0.068, z0 + t * L);
        else zb(b, 0.04, 0.035, n * 0.24, k.woodD, x0 + t * L, y + 0.068, z0);
      }
    }
    if (along === 'z') for (const t of [-0.42, 0, 0.42]) hb(b, n * 0.24 + 0.1, 0.1, 0.1, k.woodD, x0, Y + 0.05, z0 + t * L);
    else for (const t of [-0.42, 0, 0.42]) zb(b, 0.1, 0.1, n * 0.24 + 0.1, k.woodD, x0 + t * L, Y + 0.05, z0);
  };
  stack(2.0, -1.55, 6, 'z', 2.2, 5);
  stack(1.75, 1.95, 4, 'x', 2.2, 4);
  // log pile (front-left)
  const LR = 0.17;
  const rows = [4, 3, 2, 1];
  rows.forEach((n, row) => {
    for (let i = 0; i < n; i++) {
      const z = 1.65 + (i - (n - 1) / 2) * LR * 2.02;
      const y = Y + LR + row * LR * 1.74;
      const rr = LR * (0.92 + r() * 0.1);
      log(b, [-2.82 + r() * 0.12, y, z], [-0.45 - r() * 0.15, y, z], rr);
    }
  });
  for (const x of [-2.6, -0.65]) for (const s of [-1, 1]) b.rod([x, Y, 1.65 + s * 0.78], [x, Y + 0.65, 1.65 + s * 0.74], 0.04, k.woodD, 6);
  for (const x of [-2.6, -0.65]) b.beam([x, Y + 0.04, 0.85], [x, Y + 0.04, 2.45], 0.08, k.woodD);
  // stump with an axe + split firewood
  const SX = 0.35, SZ = 1.55;
  b.cyl(0.3, 0.45, k.bark, SX, Y + 0.225, SZ, 'y', 12, 0.27, true);
  b.cyl(0.27, 0.01, k.endGrain, SX, Y + 0.45, SZ, 'y', 12);
  b.at(SX + 0.02, Y + 0.45, SZ, 0.5, () => {
    b.box(0.2, 0.1, 0.03, k.dark, 0, 0.0, 0, 0, 0, 0.0);
    b.rod([0.06, 0.04, 0], [0.55, 0.62, 0], 0.022, k.wood, 6);
  });
  for (let i = 0; i < 9; i++) {
    const an = r() * TAU, d = 0.45 + r() * 0.35;
    b.box(0.09, 0.09, 0.32, k.endGrain, SX + Math.cos(an) * d, Y + 0.045, SZ + Math.sin(an) * d * 0.7, 0, an, 0);
  }
  // sawhorse with a log (right)
  b.at(1.75, Y, 0.55, 0, () => {
    for (const sx of [-0.55, 0.55]) for (const s of [-1, 1]) b.beam([sx, 0, s * 0.3], [sx, 0.75, -s * 0.05], 0.07, k.wood);
    hb(b, 1.4, 0.08, 0.08, k.woodD, 0, 0.72, 0);
    log(b, [-0.9, 0.93, 0.02], [0.9, 0.93, 0.02], 0.13);
  });
  // lantern on the front beam
  lantern(b, -1.9, HF - 0.42, ZF + 0.08);
  b.box(0.04, 0.2, 0.04, k.dark, -1.9, HF - 0.22, ZF + 0.08);
  b.anchor('lightAnchor', -1.9, HF - 0.45, ZF + 0.35);
  statusLight(b, BX + 0.55 + 0.32, Y + 0.45, BZ + 0.26);
  return (p) => {
    const blade = p.get('blade');
    let w = 0;
    return (dt, run) => {
      if (!blade) return;
      w += ((run ? 22 : 0) - w) * Math.min(1, dt * 1.5);
      blade.rotation.z -= w * dt;
    };
  };
}

// ---------------------------------------------------------------------------
// WORKSHOP (6 x 6 m) - open-fronted corrugated repair shed
// ---------------------------------------------------------------------------
function buildWorkshop(b: PB): Rig {
  const k = kit();
  const r = rng(707);
  const RED = flat(0xa83a26, 0.5, 0.3), BLUE = flat(0x2a5a8a, 0.5, 0.3);
  b.blk(5.85, 0.12, 5.85, k.slab, 0, 0, 0);
  const Y = 0.12, X = 2.75, ZB = -2.75, ZF = 1.35, HF = 3.3, HB = 2.65, XW = X + 0.07;
  // frame: posts, header, rafters
  for (const sx of [-1, 1]) {
    b.blk(0.16, HF - Y, 0.16, k.woodD, sx * X, Y, ZF);
    b.blk(0.16, HB - Y, 0.16, k.woodD, sx * X, Y, ZB);
    b.blk(0.14, (HF + HB) / 2 - Y, 0.14, k.woodD, sx * X, Y, (ZF + ZB) / 2);
  }
  hb(b, X * 2 + 0.2, 0.26, 0.16, k.dark, 0, HF - 0.2, ZF);
  hb(b, X * 2 + 0.2, 0.16, 0.14, k.woodD, 0, HB - 0.1, ZB);
  const ra = Math.atan2(HF - HB, ZF - ZB);
  for (let x = -2.3; x <= 2.31; x += 0.92) b.box(0.08, 0.14, ZF - ZB + 0.2, k.wood, x, (HF + HB) / 2 - 0.02, (ZF + ZB) / 2, -ra, 0, 0);
  // roof
  {
    const L = (ZF - ZB + 0.55) / Math.cos(ra);
    let x = -2.92; let i = 0;
    const mats = [k.roof, k.roof, k.tinRed, k.roof, k.rustSheet];
    while (x < 2.92) {
      const w = Math.min(2.92 - x, 0.8 + r() * 0.9);
      b.box(w + 0.03, 0.03, L, mats[i % mats.length], x + w / 2, (HF + HB) / 2 + 0.08 + 0.17 * Math.tan(ra) + (i % 2) * 0.008, (ZF + ZB) / 2 + 0.17, -ra, 0, (r() - 0.5) * 0.01);
      x += w; i++;
    }
  }
  // walls: trapezoid sides (corrugated) + back
  for (const sx of [-1, 1]) {
    const pts: [number, number][] = [[ZB, Y], [ZF, Y], [ZF, HF - 0.05], [ZB, HB - 0.05]];
    const g = extrudeGeo(pts.map(([z, y]) => [-z, y] as [number, number]), 0.03);
    g.rotateY(Math.PI / 2);
    b.add(g, sx < 0 ? k.roof : k.tinGreen, sx * XW, 0, 0);
    // girts inside
    for (const y of [1.0, 2.0]) zb(b, 0.06, 0.12, ZF - ZB, k.woodD, sx * (X + 0.04), y, (ZF + ZB) / 2);
  }
  b.at(0, 0, ZB - 0.1, Math.PI, () => {
    tin(b, k.roof, -X - 0.05, -0.6, Y, HB - 0.08, 0, r);
    tin(b, k.tinRed, -0.62, 1.2, Y, HB - 0.08, 0.005, r);
    tin(b, k.roof, 1.18, X + 0.05, Y, HB - 0.08, 0, r);
  });
  for (const y of [1.0, 2.0]) hb(b, X * 2, 0.12, 0.06, k.woodD, 0, y, ZB - 0.03);
  // patch + small window on the left wall
  windowAt(b, -XW - 0.02, 1.7, -0.9, -Math.PI / 2, 0.8, 0.5, k.woodD);
  b.box(0.02, 0.7, 0.9, k.rust, XW + 0.03, 0.7, 0.2, 0.05, 0, 0);
  // rolled tarp door above the opening + sign
  b.cyl(0.1, X * 2 - 0.1, k.canvas, 0, HF - 0.43, ZF + 0.13, 'x', 10);
  for (const x of [-1.8, 0, 1.8]) b.add(torusGeo(0.11, 0.012, 4, 10), k.rope, x, HF - 0.43, ZF + 0.13, 0, Math.PI / 2, 0);
  const sgY = HF + 0.48, sgZ = ZF + 0.32;
  for (const x of [-0.9, 0.9]) b.beam([x, HF + 0.05, sgZ - 0.35], [x, sgY + 0.1, sgZ - 0.03], 0.05, k.dark);
  b.box(2.3, 0.52, 0.04, k.rust, 0, sgY, sgZ);
  b.add(new THREE.PlaneGeometry(2.24, 0.48), signWorkshop(), 0, sgY, sgZ + 0.022);
  // workbench + tool wall
  b.at(-0.9, Y, ZB + 0.38, 0, () => {
    hb(b, 3.0, 0.08, 0.66, k.woodD, 0, 0.9, 0);
    for (const sx of [-1.4, 1.4]) for (const sz of [-0.27, 0.27]) b.blk(0.1, 0.86, 0.1, k.woodD, sx, 0, sz);
    hb(b, 2.9, 0.04, 0.55, k.wood, 0, 0.3, 0);
    // vise
    b.blk(0.16, 0.14, 0.2, k.dark, 1.2, 0.94, 0.25);
    b.box(0.2, 0.08, 0.04, k.dark, 1.2, 1.12, 0.33);
    b.rod([1.2, 1.02, 0.38], [1.2, 1.02, 0.52], 0.012, k.steel, 5);
    // toolbox, battery, parts
    b.blk(0.5, 0.24, 0.24, RED, -1.0, 0.94, -0.12);
    b.box(0.3, 0.03, 0.03, k.dark, -1.0, 1.21, -0.12);
    b.blk(0.26, 0.2, 0.17, k.black, -0.35, 0.94, 0.1);
    b.cyl(0.08, 0.12, k.rust, 0.3, 1.0, 0.05, 'y', 10);
    b.cyl(0.13, 0.03, k.dark, 0.6, 0.955, -0.1, 'y', 14);
    // stuff on the lower shelf
    b.blk(0.4, 0.3, 0.3, k.rust, -0.9, 0.32, 0); crate(b, 0.5, 0.32, 0, 0.36, 0.1);
  });
  // pegboard + tools
  const PZ = ZB + 0.02;
  b.box(3.0, 1.15, 0.025, k.wood, -0.9, 1.75, PZ);
  const tools = (x: number, y: number): void => {
    b.box(0.03, 0.3, 0.02, k.wood, x, y, PZ + 0.03);
    b.box(0.12, 0.05, 0.035, k.dark, x, y + 0.15, PZ + 0.035);
  };
  for (const x of [-2.2, -2.05, -1.9]) tools(x, 1.6);
  for (let i = 0; i < 6; i++) b.box(0.025, 0.14 + i * 0.035, 0.012, k.steel, -1.55 + i * 0.07, 1.85, PZ + 0.025);
  b.box(0.5, 0.14, 0.006, k.steel, -0.8, 1.55, PZ + 0.02, 0, 0, 0.0);
  b.box(0.12, 0.12, 0.025, k.wood, -0.5, 1.55, PZ + 0.025);
  b.add(torusGeo(0.15, 0.025, 5, 14), k.rubber, -0.15, 1.9, PZ + 0.04);
  b.cyl(0.17, 0.005, k.steel, 0.3, 1.85, PZ + 0.02, 'z', 20);
  for (let i = 0; i < 4; i++) b.box(0.025, 0.28, 0.025, i % 2 ? k.dark : RED, 0.0 + i * 0.07, 1.45, PZ + 0.03);
  // gas bottles chained to the back wall + welder cart
  for (const [x, m] of [[1.05, RED], [1.42, k.dark]] as [number, Mat][]) {
    b.cyl(0.15, 1.15, m, x, Y + 0.575, ZB + 0.25, 'y', 14);
    b.add(sphereGeo(0.15, 14, 6, TAU, Math.PI / 2), m, x, Y + 1.15, ZB + 0.25);
    b.cyl(0.04, 0.1, k.steel, x, Y + 1.33, ZB + 0.25, 'y', 8);
    b.cyl(0.05, 0.05, k.dark, x, Y + 1.4, ZB + 0.25, 'y', 8);
  }
  b.cable([0.85, 1.0, ZB + 0.1], [1.62, 1.0, ZB + 0.1], 0.0, 0.01, k.dark, 2);
  b.cable([1.05, 1.42, ZB + 0.25], [1.95, 0.85, ZB + 0.55], 0.25, 0.012, k.black, 8);
  b.at(2.1, Y, ZB + 0.55, -0.3, () => {
    b.blk(0.45, 0.5, 0.6, BLUE, 0, 0.12, 0);
    for (const sz of [-0.22, 0.22]) b.cyl(0.08, 0.05, k.rubber, -0.2, 0.08, sz, 'x', 10);
    b.box(0.3, 0.15, 0.02, k.dark, 0, 0.45, 0.31);
  });
  // anvil on a stump
  b.cyl(0.26, 0.55, k.bark, -1.3, Y + 0.275, -0.6, 'y', 12, 0.24, true);
  b.cyl(0.24, 0.01, k.endGrain, -1.3, Y + 0.55, -0.6, 'y', 12);
  b.at(-1.3, Y + 0.56, -0.6, 0.3, () => {
    b.blk(0.36, 0.1, 0.26, k.dark, 0, 0, 0);
    b.blk(0.2, 0.14, 0.15, k.dark, 0, 0.1, 0);
    b.blk(0.5, 0.12, 0.18, k.dark, -0.04, 0.24, 0);
    b.add(cylGeo(0.02, 0.09, 0.3, 10), k.dark, 0.36, 0.31, 0, 0, 0, -Math.PI / 2);
  });
  b.box(0.04, 0.04, 0.35, k.wood, -1.15, Y + 0.89, -0.62, 0, 0.7, 0);
  // engine block on the floor under a chain hoist
  const EX = 1.3, EZ = -0.75;
  b.blk(0.7, 0.5, 0.5, k.dark, EX, Y + 0.12, EZ);
  b.blk(0.6, 0.2, 0.42, k.rust, EX, Y + 0.62, EZ);
  for (const sz of [-0.27, 0.27]) for (let i = 0; i < 3; i++) b.cyl(0.05, 0.08, k.metal, EX - 0.2 + i * 0.2, Y + 0.6, EZ + sz, 'z', 8);
  b.blk(0.8, 0.12, 0.6, k.woodD, EX, Y, EZ);
  hb(b, 1.0, 0.14, 0.14, k.dark, EX, HB + 0.15, EZ, 0, Math.PI / 2, 0);
  b.box(0.18, 0.22, 0.12, RED, EX, HB - 0.2, EZ);
  b.rod([EX, HB - 0.3, EZ], [EX, Y + 1.15, EZ], 0.012, k.dark, 4);
  b.add(torusGeo(0.05, 0.012, 4, 8), k.dark, EX, Y + 1.1, EZ);
  // hanging work lamp
  b.rod([-0.3, HF - 0.2, -0.6], [-0.3, 2.65, -0.6], 0.006, k.black, 3);
  b.add(cylGeo(0.05, 0.22, 0.14, 12, true), k.dark, -0.3, 2.6, -0.6);
  b.mesh('bulb', sphereGeo(0.05, 8, 6), k.bulb, -0.3, 2.52, -0.6);
  b.anchor('lightAnchor', -0.3, 2.4, -0.6);
  // welding spark at the vise (flickers while running)
  const spark = emissiveVariant('spark', 'emissiveCool', 0x203040, 0xd8f0ff, 6);
  b.mesh('spark', sphereGeo(0.04, 6, 4), spark, 0.3, Y + 1.12, ZB + 0.63);
  // tyre stack + oil drum by the front left
  for (let i = 0; i < 3; i++) b.add(torusGeo(0.3, 0.12, 6, 14), k.rubber, -2.3, Y + 0.12 + i * 0.22, 1.95, Math.PI / 2, 0, i * 0.4);
  barrel(b, BLUE, -1.6, Y, 2.2, 0.27, 0.84);
  // scrap pile (front right)
  for (let i = 0; i < 16; i++) {
    const x = 1.35 + r() * 1.15, z = 1.75 + r() * 0.85, s = 0.15 + r() * 0.25;
    const m = [k.rust, k.dark, k.metal, k.rust][i % 4];
    if (i % 5 === 0) b.add(cylGeo(0.05, 0.05, 0.5 + r() * 0.4, 6), m, x, Y + 0.1 + r() * 0.2, z, 0, r() * 3, Math.PI / 2);
    else b.box(s, s * (0.2 + r() * 0.6), s * (0.5 + r()), m, x, Y + s * 0.2 + r() * 0.15, z, (r() - 0.5) * 0.8, r() * 3, (r() - 0.5) * 0.8);
  }
  b.box(1.0, 0.7, 0.02, k.tinRed, 2.25, Y + 0.38, 2.3, 0.2, -0.6, 0.3);
  b.add(torusGeo(0.32, 0.12, 6, 14), k.rubber, 1.85, Y + 0.13, 2.45, Math.PI / 2, 0, 0);
  barrel(b, k.rust, 2.55, Y, 1.75, 0.27, 0.84);
  statusLight(b, X - 0.08, 1.7, ZF + 0.1);
  return (p) => {
    const sp = p.get('spark');
    return (_dt, run) => {
      if (!sp) return;
      const on = run && Math.random() < 0.7;
      sp.visible = on;
      if (on) sp.scale.setScalar(0.5 + Math.random() * 1.6);
    };
  };
}

// ---------------------------------------------------------------------------
// HUB - SETTLEMENT HALL (6 x 6 m) - log hall, porch, flag, bell, notice board, fire pit
// ---------------------------------------------------------------------------
function buildHub(b: PB): Rig {
  const k = kit();
  const r = rng(808);
  const X = 2.2, ZB = -2.68, ZF = 0.35, FY = 0.5, EY = 3.05, rise = 1.55, RY = EY + rise;
  const cz = (ZB + ZF) / 2;
  // stone plinth
  b.blk(X * 2 + 0.2, FY, ZF - ZB + 0.2, k.rock, 0, 0, cz);
  b.box(X * 2 + 0.26, 0.06, ZF - ZB + 0.26, k.woodD, 0, FY - 0.03, cz);
  // core + log walls
  b.blk(X * 2 - 0.2, EY - FY, ZF - ZB - 0.2, k.woodD, 0, FY, cz);
  const LR = 0.12, ROW = 0.235;
  const nRows = Math.round((EY - FY) / ROW);
  const holesF: [number, number, number, number][] = [[-0.68, 0.68, FY, FY + 2.15], [-1.75, -0.95, FY + 0.85, FY + 1.75], [0.95, 1.75, FY + 0.85, FY + 1.75]];
  const holesS: [number, number, number, number][] = [[cz - 0.45, cz + 0.45, FY + 0.85, FY + 1.75]];
  const runLogs = (y: number, a0: number, a1: number, holes: [number, number, number, number][], put: (p: number, q: number) => void): void => {
    const cuts = holes.filter((h) => y + LR > h[2] && y - LR < h[3]).sort((p, q) => p[0] - q[0]);
    let cur = a0;
    for (const h of cuts) { if (h[0] > cur + 0.05) put(cur, h[0]); cur = Math.max(cur, h[1]); }
    if (cur < a1 - 0.05) put(cur, a1);
  };
  for (let i = 0; i < nRows; i++) {
    const yA = FY + LR + i * ROW, yB = yA + ROW / 2;
    const rr = LR * (0.95 + r() * 0.08);
    // front/back (logs along X)
    runLogs(yA, -X - 0.18, X + 0.18, holesF, (p, q) => b.add(cylGeo(rr, rr, q - p, 9), k.wood, (p + q) / 2, yA, ZF - 0.02, 0, 0, Math.PI / 2));
    b.add(cylGeo(rr, rr, X * 2 + 0.36, 9), k.wood, 0, yA, ZB + 0.02, 0, 0, Math.PI / 2);
    // sides (logs along Z), offset half a row: interlocking corners
    if (yB < EY - 0.05) for (const sx of [-1, 1]) {
      runLogs(yB, ZB - 0.18, ZF + 0.18, holesS, (p, q) => b.add(cylGeo(rr, rr, q - p, 9), k.wood, sx * (X - 0.02), yB, (p + q) / 2, Math.PI / 2, 0, 0));
    }
  }
  for (const sx of [-1, 1]) for (const z of [ZF - 0.02, ZB + 0.02]) {
    // end-grain caps on the corner crossings
    for (let i = 0; i < nRows; i++) b.add(cylGeo(LR * 0.97, LR * 0.97, 0.01, 9), k.endGrain, sx * (X + 0.185), FY + LR + i * ROW, z, 0, 0, Math.PI / 2);
  }
  // door + windows on the front
  b.at(0, FY, ZF + 0.06, 0, () => {
    for (const s of [-1, 1]) b.at(s * 0.32, 0, 0, 0, () => {
      for (let i = 0; i < 4; i++) b.blk(0.155, 2.1, 0.05, k.woodD, -0.24 + i * 0.16, 0, 0);
      hb(b, 0.6, 0.12, 0.03, k.wood, 0, 0.4, 0.04); hb(b, 0.6, 0.12, 0.03, k.wood, 0, 1.7, 0.04);
      b.beam([-0.25, 0.45, 0.04], [0.25, 1.65, 0.04], 0.1, k.wood, 0.03);
      b.cyl(0.03, 0.12, k.dark, -s * 0.22, 1.05, 0.08, 'z', 6);
    });
    hb(b, 1.6, 0.16, 0.14, k.woodD, 0, 2.2, 0.0);
    b.add(torusGeo(0.06, 0.012, 4, 10), k.dark, -0.12, 1.1, 0.1);
    b.add(torusGeo(0.06, 0.012, 4, 10), k.dark, 0.12, 1.1, 0.1);
  });
  for (const x of [-1.35, 1.35]) windowAt(b, x, FY + 1.3, ZF + 0.05, 0, 0.66, 0.76, k.woodD);
  windowAt(b, X + 0.05, FY + 1.3, cz, Math.PI / 2, 0.76, 0.76, k.woodD);
  windowAt(b, -X - 0.05, FY + 1.3, cz, -Math.PI / 2, 0.76, 0.76, k.woodD);
  // gables + sign
  gableTriZ(b, k.wood, ZF - 0.08, EY - 0.02, 0, X + 0.12, rise + 0.02, 0.06);
  gableTriZ(b, k.wood, ZB + 0.06, EY - 0.02, 0, X + 0.12, rise + 0.02, 0.06);
  for (let x = -1.6; x <= 1.61; x += 0.4) {
    const h = (rise) * (1 - Math.abs(x) / (X + 0.12)) - 0.08;
    if (h > 0.1) b.box(0.05, h, 0.03, k.woodD, x, EY + h / 2, ZF - 0.03);
  }
  b.box(2.08, 0.52, 0.05, k.woodD, 0, EY + 0.5, ZF);
  b.add(new THREE.PlaneGeometry(2.0, 0.455), signHall(), 0, EY + 0.5, ZF + 0.028);
  // roof (ridge along Z)
  const ov = 0.3;
  const RL = 3.47;
  b.push(0, 0, -1.185, 0, Math.PI / 2, 0);
  const a = gableRoof(b, RY, 0, X, rise, ov, RL, [k.roof, k.roof, k.tinRed, k.roof, k.rustSheet], r, 0.045);
  for (const sl of [1, -1]) onSlope(b, RY, 0, a, sl, () => { for (const s of [-1, 1]) b.box(0.12, 0.14, (X + ov) / Math.cos(a), k.woodD, s * (RL / 2 - 0.05), -0.05, (X + ov) / Math.cos(a) / 2); });
  hb(b, RL + 0.04, 0.14, 0.14, k.dark, 0, RY + 0.035, 0, Math.PI / 4);
  b.pop();
  // stone chimney on the right side
  b.blk(0.6, 1.9, 0.78, k.rock, X + 0.4, 0, -1.7);
  b.add(cylGeo(0.32, 0.425, 0.4, 4).rotateY(Math.PI / 4), k.rock, X + 0.4, 2.1, -1.7, 0, 0, 0, 1, 1, 1.2);
  b.blk(0.42, RY + 0.35 - 2.3, 0.48, k.brick, X + 0.4, 2.3, -1.7);
  b.blk(0.52, 0.08, 0.58, k.rock, X + 0.4, RY + 0.35, -1.7);
  b.blk(0.3, 0.12, 0.36, k.brick, X + 0.4, RY + 0.43, -1.7);
  // porch deck + steps + posts + roof + railing
  const PZ0 = ZF + 0.12, PZ1 = 1.62;
  for (const x of [-2.25, -0.75, 0.75, 2.25]) b.blk(0.24, FY - 0.08, 0.24, k.rock, x, 0, PZ1 - 0.12);
  for (let z = PZ0 + 0.07; z < PZ1; z += 0.14) hb(b, X * 2 + 0.2 + (r() - 0.5) * 0.04, 0.05, 0.13, k.wood, 0, FY - 0.025, z);
  hb(b, X * 2 + 0.24, 0.14, 0.08, k.woodD, 0, FY - 0.12, PZ1);
  for (let i = 0; i < 3; i++) {
    const y = FY - (i + 1) * 0.165, z = PZ1 + 0.13 + i * 0.25;
    hb(b, 1.4, 0.05, 0.27, k.wood, 0, y + 0.14, z);
    b.blk(1.36, y + 0.12, 0.25, k.woodD, 0, 0, z);
  }
  const PH = 2.7;
  for (const x of [-2.25, -0.75, 0.75, 2.25]) {
    b.blk(0.15, PH - FY, 0.15, k.wood, x, FY, PZ1 - 0.12);
    b.beam([x, PH - 0.55, PZ1 - 0.12], [x + (x < 0 ? 0.4 : -0.4), PH - 0.1, PZ1 - 0.12], 0.07, k.woodD);
  }
  hb(b, X * 2 + 0.3, 0.16, 0.14, k.woodD, 0, PH, PZ1 - 0.12);
  const pa = Math.atan2(EY - 0.1 - PH - 0.08, PZ1 + 0.1 - ZF);
  b.box(X * 2 + 0.5, 0.03, (PZ1 + 0.18 - ZF) / Math.cos(pa), k.roof, 0, (EY - 0.1 + PH + 0.08) / 2 + 0.03, (ZF + PZ1 + 0.18) / 2, pa, 0, 0);
  for (const [x0, x1] of [[-2.25, -0.75], [0.75, 2.25]] as [number, number][]) {
    hb(b, x1 - x0, 0.07, 0.07, k.wood, (x0 + x1) / 2, FY + 0.92, PZ1 - 0.12);
    for (let x = x0 + 0.15; x < x1 - 0.1; x += 0.15) b.blk(0.04, 0.88, 0.04, k.woodD, x, FY, PZ1 - 0.12);
  }
  for (const sx of [-1, 1]) {
    zb(b, 0.07, 0.07, PZ1 - PZ0, k.wood, sx * 2.25, FY + 0.92, (PZ0 + PZ1) / 2 - 0.06);
    for (let z = PZ0 + 0.1; z < PZ1 - 0.15; z += 0.15) b.blk(0.04, 0.88, 0.04, k.woodD, sx * 2.25, FY, z);
  }
  // lanterns on the inner posts + bench on the porch
  for (const x of [-0.75, 0.75]) { b.box(0.04, 0.04, 0.2, k.dark, x, 2.32, PZ1 - 0.02); lantern(b, x, 2.12, PZ1 + 0.06); }
  b.at(-1.5, FY, ZF + 0.4, 0, () => {
    hb(b, 1.2, 0.05, 0.32, k.wood, 0, 0.44, 0); hb(b, 1.2, 0.12, 0.03, k.wood, 0, 0.75, -0.17);
    for (const sx of [-0.5, 0.5]) { b.blk(0.06, 0.42, 0.06, k.woodD, sx, 0, 0.12); b.blk(0.06, 0.82, 0.06, k.woodD, sx, 0, -0.14); }
  });
  crate(b, 1.75, FY, ZF + 0.38, 0.42, 0.3);
  b.anchor('lightAnchor', 0, 2.3, 1.0);
  statusLight(b, 0.85, FY + 2.0, ZF + 0.08);
  // fire pit (unlit)
  const FX = -1.35, FZ = 2.42;
  for (let i = 0; i < 11; i++) {
    const an = (i / 11) * TAU;
    b.add(sphereGeo(0.5, 6, 4), k.rock, FX + Math.cos(an) * 0.42, 0.07, FZ + Math.sin(an) * 0.42, r(), r() * 3, r(), 0.2 + r() * 0.06, 0.14 + r() * 0.06, 0.17);
  }
  b.add(cylGeo(0.38, 0.4, 0.04, 14), k.soot, FX, 0.02, FZ);
  for (let i = 0; i < 5; i++) {
    const an = (i / 5) * TAU + 0.3;
    b.rod([FX + Math.cos(an) * 0.3, 0.04, FZ + Math.sin(an) * 0.3], [FX + Math.cos(an) * 0.03, 0.48, FZ + Math.sin(an) * 0.03], 0.045, i % 2 ? k.soot : k.bark, 6);
  }
  log(b, [FX + 0.62, 0.13, FZ - 0.42], [FX + 0.75, 0.13, FZ + 0.4], 0.13);
  // notice board
  b.at(1.2, 0, 2.6, -0.15, () => {
    for (const sx of [-0.5, 0.5]) b.blk(0.09, 1.95, 0.09, k.woodD, sx, 0, 0);
    b.box(0.98, 0.72, 0.05, k.woodD, 0, 1.4, 0.02);
    b.add(new THREE.PlaneGeometry(0.92, 0.66), noticeBoard(), 0, 1.4, 0.047);
    b.box(1.2, 0.025, 0.4, k.roof, 0, 1.98, 0.05, 0.35, 0, 0);
  });
  // bell on a post (left of the porch)
  const BXp = -2.62, BZp = 1.05;
  b.blk(0.16, 2.6, 0.16, k.woodD, BXp, 0, BZp);
  b.blk(0.3, 0.12, 0.3, k.rock, BXp, 0, BZp);
  zb(b, 0.1, 0.12, 0.7, k.woodD, BXp, 2.48, BZp + 0.27);
  b.beam([BXp, 2.0, BZp + 0.05], [BXp, 2.42, BZp + 0.45], 0.07, k.woodD);
  const bell = b.sub('bell', BXp, 2.42, BZp + 0.5);
  const bronze = flat(0x8c6a36, 0.38, 0.85);
  bell.add(torusGeo(0.04, 0.01, 4, 8), k.dark, 0, -0.02, 0, 0, Math.PI / 2, 0);
  bell.add(latheGeo([[0.0, -0.06], [0.06, -0.06], [0.08, -0.1], [0.1, -0.22], [0.15, -0.36], [0.16, -0.38], [0.0, -0.38]], 14), bronze, 0, 0, 0);
  bell.rod([0, -0.3, 0], [0, -0.42, 0], 0.012, k.dark, 4);
  bell.add(sphereGeo(0.03, 6, 4), k.dark, 0, -0.43, 0);
  bell.rod([0, -0.43, 0], [0, -1.55, 0.05], 0.008, k.rope, 4);
  // flagpole + waving flag (geometry cloned per instance, vertices animated)
  const FPX = 2.6, FPZ = 2.6, FPH = 6.6;
  b.blk(0.36, 0.25, 0.36, k.rock, FPX, 0, FPZ);
  b.cyl(0.055, FPH, k.steel, FPX, FPH / 2, FPZ, 'y', 8, 0.035);
  b.add(sphereGeo(0.07, 8, 6), bronze, FPX, FPH + 0.04, FPZ);
  b.rod([FPX + 0.05, 1.2, FPZ], [FPX + 0.05, FPH - 0.05, FPZ], 0.006, k.rope, 3);
  b.box(0.04, 0.1, 0.04, k.dark, FPX + 0.07, 1.2, FPZ);
  const fg = new THREE.PlaneGeometry(1.5, 0.94, 12, 4);
  fg.translate(-0.75, 0, 0);
  // u: 0 at the pole -> 1 at the fly end (pole on the right of the canvas => mirror)
  const uv = fg.attributes.uv as THREE.BufferAttribute;
  for (let i = 0; i < uv.count; i++) uv.setX(i, 1 - uv.getX(i));
  b.mesh('flag', fg, flagMat(), FPX - 0.06, FPH - 0.55, FPZ, 0, 0, 0, true);
  return (p) => {
    const bl = p.get('bell');
    const f = p.get('flag') as THREE.Mesh | undefined;
    const pos = f ? (f.geometry.attributes.position as THREE.BufferAttribute) : null;
    const base = pos ? Float32Array.from(pos.array as Float32Array) : null;
    let acc = 1;
    return (dt, _run, t) => {
      if (bl) { bl.rotation.x = Math.sin(t * 1.1) * 0.04; bl.rotation.z = Math.sin(t * 0.8) * 0.02; }
      if (!f || !pos || !base) return;
      acc += dt;
      if (acc < 1 / 30) return; // ~30 Hz is plenty for cloth
      acc = 0;
      for (let i = 0; i < pos.count; i++) {
        const x = base[i * 3], y = base[i * 3 + 1];
        const d = -x; // distance from the pole
        const w = Math.sin(d * 4.2 - t * 5.5 + y * 0.8) * 0.11 * Math.min(1, d * 0.9) + Math.sin(d * 7 - t * 8.3) * 0.025 * d;
        pos.setXYZ(i, x + Math.abs(w) * 0.25, y - d * d * 0.03, w);
      }
      pos.needsUpdate = true;
      f.geometry.computeVertexNormals();
    };
  };
}

// ---------------------------------------------------------------------------
// Registry / public API
// ---------------------------------------------------------------------------
const BUILDERS: Record<TownModelId, (b: PB) => Rig> = {
  shelter_house: buildShelterHouse, bunkhouse: buildBunkhouse, mess_hall: buildMessHall, water_tower: buildWaterTower,
  guard_post: buildGuardPost, lumber_yard: buildLumberYard, workshop: buildWorkshop, hub: buildHub,
};
interface Template { root: THREE.Group; rig: Rig }
const templates = new Map<TownModelId, Template>();
function template(id: TownModelId): Template {
  let t = templates.get(id);
  if (!t) {
    const b = new PB('town_' + id);
    const rig = BUILDERS[id](b);
    const root = b.build();
    t = { root, rig };
    templates.set(id, t);
  }
  return t;
}

/** Pre-build templates (e.g. during a loading screen). Requires initTextures() to have completed. */
export function warmTownModels(ids: TownModelId[] = TOWN_MODEL_IDS): void { for (const id of ids) template(id); }

/** Shared warm window material of all town buildings (scale emissiveIntensity for day/night). */
export function townWindowMaterial(): THREE.MeshStandardMaterial { return windowMat(); }

export function buildTownModel(id: TownModelId): THREE.Group {
  const t = template(id);
  const g = t.root.clone(true);
  const lights: THREE.Mesh[] = [];
  const named = new Map<string, THREE.Object3D[]>();
  g.traverse((o) => {
    if (!o.name) return;
    let l = named.get(o.name);
    if (!l) { l = []; named.set(o.name, l); }
    l.push(o);
    const m = o as THREE.Mesh;
    if (m.isMesh && o.name === 'statusLight') { m.material = getMaterial('emissiveGreen').clone(); lights.push(m); }
    if (m.isMesh && o.name === 'flag') m.geometry = m.geometry.clone();
  });
  const parts: Parts = { get: (n) => named.get(n)?.[0], all: (n) => named.get(n) ?? [] };
  const own = t.rig(parts);
  const flames = parts.all('flame');
  const seed = Math.random() * 100;
  const ud = g.userData;
  ud.townModelId = id;
  ud.footprint = [TOWN_FOOT[id][0], TOWN_FOOT[id][1]] as [number, number];
  ud.statusLights = lights;
  ud.lightAnchor = parts.get('lightAnchor') ?? (() => { const o = new THREE.Object3D(); o.position.set(0, 2, 0); g.add(o); return o; })();
  ud.windowMaterial = windowMat();
  if (id === 'guard_post') ud.standY = GUARD_STAND_Y;
  ud.animate = ((dt: number, running: boolean, time: number) => {
    own(dt, running, time);
    if (flames.length) {
      const tt = time + seed;
      const f = 0.86 + Math.sin(tt * 13.1) * 0.06 + Math.sin(tt * 7.3) * 0.05 + Math.sin(tt * 23.7) * 0.03;
      for (const fl of flames) fl.scale.set(f, f * 1.5, f);
    }
  }) as Anim;
  return g;
}
