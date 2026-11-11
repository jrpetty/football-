// REKINDLE - factory machine models.
// Each model is built ONCE per id into a template (static parts merged per
// material), then buildMachineModel() returns a cheap clone that shares
// geometry/materials, with per-instance cloned materials for status lights,
// glows and lamps, and a per-instance animate() closure.
import * as THREE from 'three';
import { getMaterial, getTintedMaterial, type MaterialName } from '../render/materials';
import { getPaintedTexSet } from '../render/textures';
import { PB, boxGeo, cylGeo, sphereGeo, torusGeo, hopperGeo, quadGeo, extrudeGeo, cboxGeo, rng, mergedGeo, type V3 } from './machinegeom';

export type MachineModelId =
  | 'hub' | 'miner' | 'miner2' | 'smelter' | 'foundry' | 'constructor' | 'assembler' | 'lab' | 'storage'
  | 'splitter' | 'merger' | 'belt' | 'belt2' | 'coal_gen' | 'biomass' | 'solar' | 'wind_turbine' | 'battery'
  | 'power_pole' | 'floodlight' | 'turret' | 'electric_fence' | 'spike_trap' | 'radio_tower' | 'greenhouse' | 'uplink';

// eslint-disable-next-line @typescript-eslint/no-empty-interface
export interface MachineModel extends THREE.Group {}

export type MachineAnimate = (dt: number, running: boolean, time: number) => void;

const TAU = Math.PI * 2;
/** Height of the top of the foundation slab used by most machines. */
const S = 0.18;

// ---------------------------------------------------------------------------
// Materials
// ---------------------------------------------------------------------------
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
/**
 * Tone down the texture's paint chipping: diffuse = mix(paint colour, texel, wear).
 * Shares one shader program between all paint colours (per-material uniform).
 */
function softenWear(m: THREE.MeshStandardMaterial, hex: number, wear: number): void {
  const col = new THREE.Color(hex);
  m.onBeforeCompile = (sh) => {
    sh.uniforms.uPaint = { value: col };
    sh.uniforms.uWear = { value: wear };
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>', '#include <common>\nuniform vec3 uPaint;\nuniform float uWear;')
      .replace('#include <map_fragment>', `#ifdef USE_MAP
  vec4 sampledDiffuseColor = texture2D( map, vMapUv );
  diffuseColor.rgb *= mix( uPaint, sampledDiffuseColor.rgb, uWear );
#endif`);
  };
  m.customProgramCacheKey = () => 'rekindle-paintwear';
}

/** Painted factory panel in a given paint colour (chips/grime keep their colours). */
function paint(hex: number): THREE.MeshStandardMaterial {
  const key = 'paint:' + hex;
  let m = tintCache.get(key);
  if (!m) {
    try {
      const set = getPaintedTexSet('factoryPanel', hex);
      m = M('factoryPanel').clone();
      m.map = set.map;
      m.color.setHex(0xffffff);
      m.name = 'factoryPanel_paint_' + hex.toString(16);
      softenWear(m, hex, 0.45);
    } catch {
      m = tint('factoryPanel', hex);
    }
    tintCache.set(key, m);
  }
  return m;
}

const ownCache = new Map<string, THREE.MeshStandardMaterial>();
function own(key: string, make: () => THREE.MeshStandardMaterial): THREE.MeshStandardMaterial {
  let m = ownCache.get(key);
  if (!m) {
    m = make();
    ownCache.set(key, m);
  }
  return m;
}
/** Furnace glow (template material; cloned per instance by name 'glow'). */
function glowMat(): THREE.MeshStandardMaterial {
  return own('glow', () => {
    const m = M('emissiveWarm').clone();
    m.color.setHex(0x2a0c02);
    m.emissive.setHex(0xff5a12);
    m.emissiveIntensity = 2.4;
    return m;
  });
}
function windowLitMat(): THREE.MeshStandardMaterial {
  return own('winlit', () => {
    const m = M('emissiveCool').clone();
    m.color.setHex(0x1a2a30);
    m.emissive.setHex(0x9fd8e8);
    m.emissiveIntensity = 0.55;
    return m;
  });
}
function solarCellMat(): THREE.MeshStandardMaterial {
  return own('solar', () => new THREE.MeshStandardMaterial({ color: 0x15264a, metalness: 0.55, roughness: 0.22 }));
}
function growMat(): THREE.MeshStandardMaterial {
  return own('grow', () => {
    const m = M('emissiveCool').clone();
    m.color.setHex(0x301030);
    m.emissive.setHex(0xe050ff);
    m.emissiveIntensity = 2.0;
    return m;
  });
}
function screenMat(): THREE.MeshStandardMaterial {
  return own('screen', () => {
    const m = M('emissiveGreen').clone();
    m.color.setHex(0x05140a);
    m.emissive.setHex(0x46ff9a);
    m.emissiveIntensity = 1.4;
    return m;
  });
}

interface Kit {
  dark: THREE.MeshStandardMaterial; metal: THREE.MeshStandardMaterial; rust: THREE.MeshStandardMaterial;
  steel: THREE.MeshStandardMaterial; chrome: THREE.MeshStandardMaterial; hazard: THREE.MeshStandardMaterial;
  grating: THREE.MeshStandardMaterial; rubber: THREE.MeshStandardMaterial; black: THREE.MeshStandardMaterial;
  white: THREE.MeshStandardMaterial; conc: THREE.MeshStandardMaterial; glass: THREE.MeshStandardMaterial;
  glassD: THREE.MeshStandardMaterial; wood: THREE.MeshStandardMaterial; woodD: THREE.MeshStandardMaterial;
  brick: THREE.MeshStandardMaterial; orange: THREE.MeshStandardMaterial; grey: THREE.MeshStandardMaterial;
  fabric: THREE.MeshStandardMaterial; rock: THREE.MeshStandardMaterial; eCool: THREE.MeshStandardMaterial;
  eWarm: THREE.MeshStandardMaterial; eRed: THREE.MeshStandardMaterial; eGreen: THREE.MeshStandardMaterial;
  yellow: THREE.MeshStandardMaterial; roofMetal: THREE.MeshStandardMaterial; leaves: THREE.MeshStandardMaterial;
  bark: THREE.MeshStandardMaterial; sandbag: THREE.MeshStandardMaterial; panel: THREE.MeshStandardMaterial;
  porcelain: THREE.MeshStandardMaterial; dirt: THREE.MeshStandardMaterial;
}
let K: Kit | null = null;
function kit(): Kit {
  if (K) return K;
  K = {
    dark: M('metalDark'), metal: M('metal'), rust: M('metalRust'), steel: M('steelBrushed'), chrome: M('chrome'),
    hazard: M('hazard'), grating: M('grating'), rubber: M('rubber'), black: M('plasticBlack'), white: M('plasticWhite'),
    conc: M('concrete'), glass: M('glass'), glassD: M('glassDirty'), wood: M('wood'), woodD: M('woodDark'),
    brick: M('brickRed'), orange: paint(0xd0601c), grey: M('factoryGrey'), fabric: M('fabric'), rock: M('rock'),
    eCool: M('emissiveCool'), eWarm: M('emissiveWarm'), eRed: M('emissiveRed'), eGreen: M('emissiveGreen'),
    yellow: paint(0xd9a21b), roofMetal: M('roofMetal'), leaves: M('leaves'), bark: M('bark'),
    sandbag: tint('fabric', 0x9a8a62), panel: M('factoryPanel'), porcelain: tint('plasticWhite', 0x7a4a2c),
    dirt: M('dirt'),
  };
  return K;
}

// ---------------------------------------------------------------------------
// Industrial kit-bash helpers
// ---------------------------------------------------------------------------

/** Concrete foundation slab covering a W x D tile footprint, steel edging, hazard border. */
function slab(b: PB, W: number, D: number, hazardBorder = true): void {
  const k = kit();
  const hx = W - 0.06;
  const hz = D - 0.06;
  b.blk(hx * 2, S - 0.02, hz * 2, k.conc, 0, 0, 0);
  // steel edge angle
  b.blk(hx * 2 + 0.04, 0.05, 0.06, k.dark, 0, S - 0.05, hz);
  b.blk(hx * 2 + 0.04, 0.05, 0.06, k.dark, 0, S - 0.05, -hz);
  b.blk(0.06, 0.05, hz * 2, k.dark, hx, S - 0.05, 0);
  b.blk(0.06, 0.05, hz * 2, k.dark, -hx, S - 0.05, 0);
  if (hazardBorder) {
    const t = 0.14;
    b.blk(hx * 2 - 0.2, 0.012, t, k.hazard, 0, S - 0.02, hz - 0.12);
    b.blk(hx * 2 - 0.2, 0.012, t, k.hazard, 0, S - 0.02, -hz + 0.12);
    b.blk(t, 0.012, hz * 2 - 0.5, k.hazard, hx - 0.12, S - 0.02, 0);
    b.blk(t, 0.012, hz * 2 - 0.5, k.hazard, -hx + 0.12, S - 0.02, 0);
  }
  // corner anchor plates + bolts
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
    const x = sx * (hx - 0.3);
    const z = sz * (hz - 0.3);
    b.blk(0.34, 0.03, 0.34, k.steel, x, S - 0.01, z);
    for (const ox of [-0.11, 0.11]) for (const oz of [-0.11, 0.11]) b.cyl(0.022, 0.04, k.dark, x + ox, S + 0.03, z + oz, 'y', 6);
  }
}

/**
 * Belt port: framed hatch on a footprint edge. Local frame: outer face at z=0, tunnel extends
 * to z=-len, facing +Z. Opening 1.1 m wide spanning y=0.3..1.02 (belt top 0.6 passes through).
 */
function port(b: PB, x: number, z: number, ry: number, len: number, kind: 'in' | 'out', body: THREE.Material, jw = 0.26): void {
  const k = kit();
  b.at(x, 0, z, ry, () => {
    const L = Math.max(len, 0.2);
    const zc = -L / 2 - 0.07;
    const top = 1.45;
    const ox = 0.56 + jw / 2;
    b.box(jw, top - S, L, body, -ox, (top + S) / 2, zc);
    b.box(jw, top - S, L, body, ox, (top + S) / 2, zc);
    b.box(1.12, top - 1.02, L, body, 0, (top + 1.02) / 2, zc);
    b.box(1.12, 0.3 - S, L, k.dark, 0, (0.3 + S) / 2, zc);
    // dark throat
    const td = Math.min(L, 0.6);
    b.box(1.12, 0.74, 0.03, k.black, 0, 0.66, -td + 0.02);
    b.box(1.12, 0.02, td, k.black, 0, 1.01, -td / 2);
    // intake roller
    b.cyl(0.05, 1.08, k.steel, 0, 0.54, -0.18, 'x', 8);
    // collar frame
    b.box(1.12 + jw * 2, 0.13, 0.07, k.hazard, 0, 1.09, -0.03);
    b.box(0.09, 0.82, 0.07, k.dark, -0.6, 0.66, -0.03);
    b.box(0.09, 0.82, 0.07, k.dark, 0.6, 0.66, -0.03);
    b.box(1.3, 0.05, 0.12, k.steel, 0, 0.275, -0.06);
    b.box(1.12 + jw * 2 + 0.04, 0.06, L, k.dark, 0, top + 0.03, zc);
    // rubber curtain
    for (let i = -2; i <= 2; i++) b.box(0.205, 0.24, 0.012, k.rubber, i * 0.215, 0.89, -0.1);
    // direction marker
    b.box(0.46, 0.06, 0.025, kind === 'in' ? k.eCool : k.eWarm, 0, 1.26, -0.01);
    for (const sx of [-1, 1]) {
      b.cyl(0.022, 0.02, k.steel, sx * 0.6, 1.0, -0.005, 'z', 6);
      b.cyl(0.022, 0.02, k.steel, sx * 0.6, 0.33, -0.005, 'z', 6);
    }
  });
}

/** Andon stack light on a short pole. Lamp mesh named 'statusLight'. */
function stackLight(b: PB, x: number, y: number, z: number): void {
  const k = kit();
  b.cyl(0.06, 0.06, k.dark, x, y + 0.03, z, 'y', 8);
  b.cyl(0.018, 0.34, k.steel, x, y + 0.23, z, 'y', 6);
  b.cyl(0.075, 0.05, k.dark, x, y + 0.42, z, 'y', 10);
  b.mesh('statusLight', cylGeo(0.068, 0.068, 0.16, 10), k.eGreen, x, y + 0.525, z);
  b.cyl(0.078, 0.04, k.dark, x, y + 0.625, z, 'y', 10);
  b.cyl(0.05, 0.03, k.dark, x, y + 0.66, z, 'y', 10);
}

/** Small dome lamp on a face; local +Z out of the face. */
function panelLamp(b: PB, x: number, y: number, z: number, ry: number): void {
  const k = kit();
  b.at(x, y, z, ry, () => {
    b.box(0.17, 0.17, 0.04, k.dark, 0, 0, 0.02);
    b.mesh('statusLight', sphereGeo(0.055, 8, 4, TAU, Math.PI / 2), k.eGreen, 0, 0, 0.04, Math.PI / 2, 0, 0);
  });
}

/** Painted housing with corner angle posts, skirt and top rim. */
function housing(b: PB, w: number, h: number, d: number, mat: THREE.Material, x: number, y0: number, z: number, ry = 0): void {
  const k = kit();
  b.at(x, y0, z, ry, () => {
    b.cblk(w, h, d, mat, 0, 0, 0, 0.07);
    b.blk(w + 0.04, 0.16, d + 0.04, k.dark, 0, 0, 0);
    b.blk(w + 0.06, 0.07, d + 0.06, k.dark, 0, h - 0.035, 0);
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.blk(0.09, h, 0.09, k.dark, sx * (w / 2 - 0.01), 0, sz * (d / 2 - 0.01));
  });
}

/** Louvred vent on a face; local +Z out of the face, centred at (x,y,z). */
function vent(b: PB, x: number, y: number, z: number, ry: number, w: number, h: number): void {
  const k = kit();
  b.at(x, y, z, ry, () => {
    b.box(w + 0.08, h + 0.08, 0.03, k.dark, 0, 0, 0.015);
    const n = Math.max(2, Math.floor(h / 0.09));
    for (let i = 0; i < n; i++) b.box(w, 0.012, 0.09, k.steel, 0, -h / 2 + (i + 0.5) * (h / n), 0.05, -0.6, 0, 0);
  });
}

/** Vertical ladder. Local frame: ladder faces +Z (climber side), wall behind at -Z. */
function ladder(b: PB, x: number, z: number, y0: number, y1: number, ry: number, cage = false, standoff = 0.15): void {
  const k = kit();
  const mat = k.yellow;
  b.at(x, 0, z, ry, () => {
    b.rod([-0.22, y0, 0], [-0.22, y1 + 0.9, 0], 0.025, mat, 6);
    b.rod([0.22, y0, 0], [0.22, y1 + 0.9, 0], 0.025, mat, 6);
    for (let y = y0 + 0.3; y < y1 + 0.05; y += 0.3) b.rod([-0.22, y, 0], [0.22, y, 0], 0.016, k.steel, 5);
    for (let y = y0 + 0.6; y < y1; y += 1.5) {
      b.box(0.04, 0.04, standoff, mat, -0.22, y, -standoff / 2);
      b.box(0.04, 0.04, standoff, mat, 0.22, y, -standoff / 2);
    }
    if (cage && y1 - y0 > 3) {
      for (let y = y0 + 2.4; y < y1 + 0.8; y += 0.9) b.add(torusGeo(0.36, 0.015, 4, 10, Math.PI), mat, 0, y, 0.0, Math.PI / 2, 0, 0);
      for (const a of [0.5, 1.57, 2.64]) {
        const cx = Math.cos(a) * 0.36;
        const cz = Math.sin(a) * 0.36;
        b.rod([cx, y0 + 2.4, cz], [cx, y1 + 0.8, cz], 0.012, mat, 4);
      }
    }
  });
}

/** Handrail along a polyline (x,z) at deck height y. */
function railing(b: PB, pts: [number, number][], y: number, closed = false, h = 1.05, toe = true): void {
  const k = kit();
  const mat = k.yellow;
  const P = closed ? [...pts, pts[0]] : pts;
  for (let i = 0; i < P.length - 1; i++) {
    const [x0, z0] = P[i];
    const [x1, z1] = P[i + 1];
    const len = Math.hypot(x1 - x0, z1 - z0);
    const n = Math.max(1, Math.ceil(len / 1.4));
    for (let j = 0; j <= n; j++) {
      if (j === n && i < P.length - 2) continue;
      if (j === n && closed) continue;
      const t = j / n;
      const px = x0 + (x1 - x0) * t;
      const pz = z0 + (z1 - z0) * t;
      b.rod([px, y, pz], [px, y + h, pz], 0.025, mat, 6);
    }
    b.rod([x0, y + h, z0], [x1, y + h, z1], 0.025, mat, 6);
    b.rod([x0, y + h * 0.5, z0], [x1, y + h * 0.5, z1], 0.018, mat, 5);
    if (toe) b.beam([x0, y + 0.05, z0], [x1, y + 0.05, z1], 0.012, k.yellow, 0.1);
  }
}

function barrel(b: PB, x: number, y0: number, z: number, mat: THREE.Material, tipped = false): void {
  const k = kit();
  if (tipped) {
    b.add(cylGeo(0.29, 0.29, 0.88, 12), mat, x, y0 + 0.29, z, 0, 0, Math.PI / 2);
    return;
  }
  b.cyl(0.29, 0.88, mat, x, y0 + 0.44, z, 'y', 12);
  b.cyl(0.302, 0.03, k.dark, x, y0 + 0.3, z, 'y', 12, undefined, true);
  b.cyl(0.302, 0.03, k.dark, x, y0 + 0.6, z, 'y', 12, undefined, true);
}

function crate(b: PB, x: number, y0: number, z: number, s: number, ry = 0): void {
  const k = kit();
  b.at(x, y0, z, ry, () => {
    b.blk(s, s, s, k.wood, 0, 0, 0);
    const t = 0.06;
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.blk(t, s + 0.01, t, k.woodD, sx * (s / 2), 0, sz * (s / 2));
    for (const sz of [-1, 1]) {
      b.box(s, t, t, k.woodD, 0, s, sz * (s / 2));
      b.box(s, t, t, k.woodD, 0, 0.03, sz * (s / 2));
      b.box(t, t, s, k.woodD, sz * (s / 2), s, 0);
    }
    b.box(s * 1.3, t * 0.8, 0.02, k.woodD, 0, s / 2, s / 2 + 0.01, 0, 0, Math.PI / 4);
  });
}

function sandbag(b: PB, x: number, y: number, z: number, ry: number): void {
  b.add(sphereGeo(0.5, 7, 4), kit().sandbag, x, y + 0.09, z, 0, ry, 0, 0.62, 0.2, 0.36);
}

/** Row of stacked sandbags from (x0,z0) to (x1,z1). */
function sandbagWall(b: PB, x0: number, z0: number, x1: number, z1: number, y0: number, layers: number): void {
  const len = Math.hypot(x1 - x0, z1 - z0);
  const n = Math.max(1, Math.round(len / 0.56));
  const ry = -Math.atan2(z1 - z0, x1 - x0);
  for (let l = 0; l < layers; l++) {
    const off = l % 2 ? 0.5 : 0;
    for (let i = 0; i < n - (l % 2); i++) {
      const t = (i + 0.5 + off) / n;
      sandbag(b, x0 + (x1 - x0) * t, y0 + l * 0.17, z0 + (z1 - z0) * t, ry + ((i * 7 + l * 3) % 5 - 2) * 0.04);
    }
  }
}

/** Tall chimney with flanges and rain cap. */
function chimney(b: PB, x: number, y0: number, z: number, r: number, h: number): void {
  const k = kit();
  b.cyl(r + 0.12, 0.3, k.dark, x, y0 + 0.15, z, 'y', 12);
  b.cyl(r, h - 0.6, k.rust, x, y0 + (h - 0.6) / 2, z, 'y', 12, undefined, true);
  b.cyl(r * 1.02, 0.6, k.dark, x, y0 + h - 0.3, z, 'y', 12, undefined, true);
  for (let y = y0 + 1.4; y < y0 + h - 0.4; y += 1.6) b.cyl(r + 0.05, 0.08, k.dark, x, y, z, 'y', 12, undefined, true);
  b.add(torusGeo(r, 0.04, 4, 12), k.dark, x, y0 + h, z, Math.PI / 2, 0, 0);
  for (let i = 0; i < 3; i++) {
    const a = (i / 3) * TAU;
    b.rod([x + Math.cos(a) * r, y0 + h, z + Math.sin(a) * r], [x + Math.cos(a) * r * 0.6, y0 + h + 0.35, z + Math.sin(a) * r * 0.6], 0.02, k.dark, 4);
  }
  b.add(cylGeo(0, r * 1.5, 0.3, 12), k.dark, x, y0 + h + 0.5, z);
}

/** Spinning fan blades into a sub-builder (spins about local Z). */
function fanBlades(f: PB, r: number, n: number, mat: THREE.Material, hubMat: THREE.Material): void {
  f.cyl(r * 0.22, 0.12, hubMat, 0, 0, 0, 'z', 10);
  for (let i = 0; i < n; i++) {
    f.push(0, 0, 0, 0, 0, (i / n) * TAU);
    f.box(r * 0.34, r * 0.74, 0.015, mat, 0, r * 0.22 + r * 0.37, 0, 0, 0.45, 0);
    f.pop();
  }
}

/** Round fan guard ring + cross bars (static) facing +Z at local origin. */
function fanGuard(b: PB, r: number): void {
  const k = kit();
  b.add(torusGeo(r, 0.025, 4, 16), k.dark, 0, 0, 0);
  b.add(torusGeo(r * 0.55, 0.012, 3, 12), k.dark, 0, 0, 0.02);
  b.box(r * 2, 0.025, 0.025, k.dark, 0, 0, 0.03);
  b.box(0.025, r * 2, 0.025, k.dark, 0, 0, 0.03);
}

function controlPanel(b: PB, x: number, z: number, ry: number, mat: THREE.Material, y0 = S): void {
  const k = kit();
  b.at(x, y0, z, ry, () => {
    b.blk(0.08, 0.7, 0.08, k.dark, 0, 0, 0);
    b.cblk(0.75, 0.55, 0.32, mat, 0, 0.7, 0, 0.03);
    b.box(0.79, 0.04, 0.4, k.dark, 0, 1.27, 0.02);
    b.add(quadGeo(0.42, 0.24), screenMat(), -0.08, 1.02, 0.165);
    b.box(0.5, 0.3, 0.02, k.black, -0.08, 1.02, 0.158);
    b.cyl(0.035, 0.04, k.eRed, 0.24, 1.08, 0.17, 'z', 8);
    b.cyl(0.035, 0.04, k.eGreen, 0.24, 0.95, 0.17, 'z', 8);
    b.cyl(0.05, 0.03, k.steel, 0.24, 0.82, 0.17, 'z', 8);
  });
}

function sign(b: PB, x: number, y: number, z: number, ry: number, w = 0.5, h = 0.35): void {
  const k = kit();
  b.at(x, y, z, ry, () => {
    b.box(w, h, 0.02, k.hazard, 0, 0, 0.01);
    b.box(w * 0.55, h * 0.45, 0.022, k.black, 0, 0, 0.012);
    b.box(w * 0.08, h * 0.3, 0.024, k.yellow, 0, 0, 0.013, 0, 0, 0.5);
  });
}

function rocks(b: PB, rnd: () => number, n: number, cx: number, cz: number, rad: number, y = 0, mat?: THREE.Material): void {
  const m = mat ?? kit().rock;
  for (let i = 0; i < n; i++) {
    const a = rnd() * TAU;
    const d = rad * (0.4 + rnd() * 0.6);
    const s = 0.12 + rnd() * 0.25;
    b.add(new THREE.DodecahedronGeometry(1, 0), m, cx + Math.cos(a) * d, y + s * 0.3, cz + Math.sin(a) * d, rnd() * 3, rnd() * 3, rnd() * 3, s * (0.8 + rnd() * 0.6), s * 0.7, s * (0.8 + rnd() * 0.5));
  }
}

/** Gauge dial facing +Z. */
function gauge(b: PB, x: number, y: number, z: number, ry: number, r = 0.08): void {
  const k = kit();
  b.at(x, y, z, ry, () => {
    b.cyl(r, 0.05, k.chrome, 0, 0, 0.025, 'z', 10);
    b.add(cylGeo(r * 0.85, r * 0.85, 0.01, 10), k.white, 0, 0, 0.052, Math.PI / 2, 0, 0);
    b.box(0.008, r * 0.75, 0.005, k.eRed, r * 0.2, r * 0.2, 0.06, 0, 0, -0.8);
  });
}

/** Thick power cable running along a polyline with sag between points. */
function cableRun(b: PB, pts: V3[], r = 0.035, sag = 0.08): void {
  const k = kit();
  for (let i = 0; i < pts.length - 1; i++) b.cable(pts[i], pts[i + 1], sag, r, k.rubber, 6);
}

/** Lattice tower (4 legs). Legs go from base half-width hb at y0 to top half-width ht at y1. */
function lattice4(b: PB, hb: number, ht: number, y0: number, y1: number, panels: number, legW: number, braceW: number, legMat: THREE.Material, braceMat: THREE.Material): void {
  const at = (sx: number, sz: number, y: number): V3 => {
    const t = (y - y0) / (y1 - y0);
    const h = hb + (ht - hb) * t;
    return [sx * h, y, sz * h];
  };
  const corners: [number, number][] = [[-1, -1], [1, -1], [1, 1], [-1, 1]];
  for (const [sx, sz] of corners) b.beam(at(sx, sz, y0), at(sx, sz, y1), legW, legMat);
  for (let p = 0; p <= panels; p++) {
    const y = y0 + ((y1 - y0) * p) / panels;
    for (let c = 0; c < 4; c++) {
      const [ax, az] = corners[c];
      const [bx, bz] = corners[(c + 1) % 4];
      if (p > 0) b.beam(at(ax, az, y), at(bx, bz, y), braceW, braceMat);
      if (p < panels) {
        const yn = y0 + ((y1 - y0) * (p + 1)) / panels;
        b.beam(at(ax, az, y), at(bx, bz, yn), braceW * 0.8, braceMat);
        b.beam(at(bx, bz, y), at(ax, az, yn), braceW * 0.8, braceMat);
      }
    }
  }
}

/** Stacked-disc insulator, base at y0 along +Y. */
function insulator(b: PB, x: number, y0: number, z: number, n: number, r = 0.09, mat?: THREE.Material): void {
  const k = kit();
  const m = mat ?? k.porcelain;
  b.cyl(r * 0.35, n * 0.1 + 0.06, m, x, y0 + (n * 0.1 + 0.06) / 2, z, 'y', 8);
  for (let i = 0; i < n; i++) b.cyl(r, 0.035, m, x, y0 + 0.06 + i * 0.1, z, 'y', 7, r * 0.7);
  b.cyl(r * 0.4, 0.06, k.steel, x, y0 + n * 0.1 + 0.09, z, 'y', 8);
}

/** Hydraulic ram (static housing). */
function ram(b: PB, a: V3, c: V3, r: number): void {
  const k = kit();
  const mid: V3 = [(a[0] + c[0]) / 2, (a[1] + c[1]) / 2, (a[2] + c[2]) / 2];
  b.rod(a, mid, r, k.yellow, 10);
  b.rod(mid, c, r * 0.55, k.chrome, 8);
}

/** Smoothly approach target speed. */
function smoother(rate = 1.5): (dt: number, target: number) => number {
  let v = 0;
  return (dt, target) => {
    v += (target - v) * Math.min(1, dt * rate);
    return v;
  };
}

// ---------------------------------------------------------------------------
// Templates
// ---------------------------------------------------------------------------
interface RigCtx {
  get(name: string): THREE.Object3D;
  mat(name: string): THREE.MeshStandardMaterial | null;
  root: THREE.Group;
}
type Rig = (c: RigCtx) => MachineAnimate;
const NOOP: MachineAnimate = () => {};

interface Template {
  root: THREE.Group;
  fp: [number, number];
  rig: Rig;
}

/** Inclined conveyor between two points (decorative). */
function incline(b: PB, a: V3, c: V3, w = 0.6, legs = true): void {
  const k = kit();
  const up = (p: V3, d: number): V3 => [p[0], p[1] + d, p[2]];
  b.beam(a, c, w + 0.14, k.dark, 0.16);
  b.beam(up(a, 0.09), up(c, 0.09), w, k.black, 0.03);
  for (const sx of [-1, 1]) {
    const o = sx * (w / 2 + 0.07);
    b.beam([a[0] + o, a[1] + 0.13, a[2]], [c[0] + o, c[1] + 0.13, c[2]], 0.03, k.yellow, 0.1);
  }
  if (legs) {
    for (const t of [0.35, 0.8]) {
      const p: V3 = [a[0] + (c[0] - a[0]) * t, a[1] + (c[1] - a[1]) * t, a[2] + (c[2] - a[2]) * t];
      for (const sx of [-1, 1]) b.beam([p[0] + sx * (w / 2), S, p[2]], [p[0] + sx * (w / 2), p[1] - 0.08, p[2]], 0.06, k.dark);
    }
  }
}

/** Short flat conveyor along Z at belt height (top 0.6), decorative. */
function flatBelt(b: PB, x: number, z0: number, z1: number, w = 1.0): void {
  const k = kit();
  const L = Math.abs(z1 - z0);
  const zc = (z0 + z1) / 2;
  b.blk(w, 0.03, L, k.black, x, 0.57, zc);
  for (const sx of [-1, 1]) {
    b.blk(0.05, 0.2, L, k.dark, x + sx * (w / 2 + 0.03), 0.45, zc);
    b.blk(0.02, 0.05, L, k.yellow, x + sx * (w / 2 + 0.065), 0.55, zc);
  }
  for (let z = Math.min(z0, z1) + 0.3; z < Math.max(z0, z1); z += 0.8) {
    for (const sx of [-1, 1]) b.blk(0.06, 0.45 - S, 0.06, k.dark, x + sx * (w / 2), S, z);
  }
}

// ---------------------------------------------------------------- HUB
function buildHub(b: PB): Rig {
  const k = kit();
  const body = paint(0x56603e);
  const rust = k.rust;
  slab(b, 3, 3);
  const L = 5.4;
  const H = 2.5;
  const D = 2.4;
  const cz = -1.0;
  const y0 = S + 0.22;
  const zf = cz + D / 2;
  const zb = cz - D / 2;
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.blk(0.55, 0.22, 0.55, k.conc, sx * (L / 2 - 0.35), S, cz + sz * (D / 2 - 0.3));
  b.blk(L - 0.1, H - 0.1, D - 0.1, body, 0, y0 + 0.05, cz);
  b.blk(L - 0.1, 0.03, D - 0.1, tint('roofMetal', 0x9aa088), 0, y0 + H - 0.06, cz);
  // corrugation ribs (skip door/window cut-outs on the front)
  for (let x = -L / 2 + 0.3; x <= L / 2 - 0.25; x += 0.3) {
    if (!(x > -1.85 && x < -0.75) && !(x > 0.3 && x < 1.7)) b.blk(0.12, H - 0.25, 0.05, body, x, y0 + 0.12, zf + 0.0);
    b.blk(0.12, H - 0.25, 0.05, body, x, y0 + 0.12, zb);
  }
  for (const z of [zf, zb]) {
    b.box(L + 0.04, 0.14, 0.14, k.dark, 0, y0 + H - 0.07, z);
    b.box(L + 0.04, 0.16, 0.14, k.dark, 0, y0 + 0.08, z);
  }
  for (const x of [-L / 2, L / 2]) {
    b.box(0.14, 0.14, D, k.dark, x, y0 + H - 0.07, cz);
    b.box(0.14, 0.16, D, k.dark, x, y0 + 0.08, cz);
    for (const z of [zf, zb]) {
      b.box(0.16, H, 0.16, k.dark, x, y0 + H / 2, z);
      b.box(0.2, 0.18, 0.2, rust, x, y0 + H - 0.05, z);
      b.box(0.2, 0.18, 0.2, rust, x, y0 + 0.07, z);
    }
  }
  // cargo doors at +X end with locking bars
  b.box(0.04, H - 0.3, D - 0.2, body, L / 2 + 0.02, y0 + H / 2, cz);
  b.box(0.05, H - 0.3, 0.03, k.dark, L / 2 + 0.04, y0 + H / 2, cz);
  for (const z of [-0.75, -0.35, 0.35, 0.75]) {
    b.rod([L / 2 + 0.08, y0 + 0.2, cz + z], [L / 2 + 0.08, y0 + H - 0.2, cz + z], 0.02, k.steel, 6);
    b.box(0.06, 0.12, 0.08, k.dark, L / 2 + 0.08, y0 + 1.1, cz + z);
  }
  // welded front door + window with bars
  b.box(1.0, 2.1, 0.06, rust, -1.3, y0 + 1.07, zf + 0.04);
  b.box(1.12, 0.08, 0.08, k.dark, -1.3, y0 + 2.14, zf + 0.05);
  b.box(0.08, 2.1, 0.08, k.dark, -1.84, y0 + 1.07, zf + 0.05);
  b.box(0.08, 2.1, 0.08, k.dark, -0.76, y0 + 1.07, zf + 0.05);
  b.box(0.6, 0.3, 0.02, k.dark, -1.3, y0 + 1.7, zf + 0.075);
  b.cyl(0.03, 0.12, k.steel, -0.95, y0 + 1.05, zf + 0.1, 'z', 6);
  b.add(quadGeo(1.2, 0.7), windowLitMat(), 1.0, y0 + 1.55, zf + 0.03);
  b.box(1.36, 0.08, 0.1, k.dark, 1.0, y0 + 1.94, zf + 0.06);
  b.box(1.36, 0.08, 0.14, k.dark, 1.0, y0 + 1.16, zf + 0.07);
  for (let i = 0; i < 6; i++) b.rod([0.45 + i * 0.22, y0 + 1.2, zf + 0.09], [0.45 + i * 0.22, y0 + 1.9, zf + 0.09], 0.015, k.steel, 5);
  b.box(1.3, 0.7, 0.04, rust, 1.0, y0 + 2.2, zf + 0.35, -1.05, 0, 0);
  // armour patches
  b.box(0.9, 0.7, 0.03, rust, -2.3, y0 + 0.8, zf + 0.06, 0, 0, 0.08);
  b.box(0.7, 0.5, 0.03, k.metal, 2.2, y0 + 0.6, zf + 0.06, 0, 0, -0.1);
  // ladder on -X end
  ladder(b, -L / 2 - 0.17, cz + 0.6, S, y0 + H, -Math.PI / 2);
  // roof: sandbag nest + railing
  const yr = y0 + H;
  sandbagWall(b, 0.3, zf - 0.25, 2.55, zf - 0.25, yr, 2);
  sandbagWall(b, 2.5, zf - 0.3, 2.5, zb + 0.4, yr, 2);
  sandbagWall(b, 0.3, zf - 0.3, 0.3, zf - 1.0, yr, 2);
  railing(b, [[-2.6, zf - 0.1], [-2.6, zb + 0.1], [0.0, zb + 0.1]], yr, false, 1.0, false);
  // roof spotlight
  b.at(2.2, yr + 0.35, zf - 0.75, 0, () => {
    b.rod([0, 0, 0], [0, 0.6, 0], 0.04, k.dark, 6);
    b.box(0.4, 0.3, 0.3, k.dark, 0, 0.75, 0.05, 0.3, 0, 0);
    b.add(quadGeo(0.32, 0.22), k.eWarm, 0, 0.71, 0.21, 0.3, 0, 0);
  });
  // antenna mast with radar
  const mx = -2.1;
  const mz = zb + 0.45;
  b.rod([mx, yr, mz], [mx, yr + 6.5, mz], 0.06, k.steel, 8);
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.rod([mx + sx * 0.45, yr, mz + sz * 0.3], [mx, yr + 2.2, mz], 0.015, k.dark, 4);
  for (const [y, w] of [[yr + 3.6, 1.2], [yr + 4.6, 0.9], [yr + 5.5, 0.6]] as [number, number][]) b.rod([mx - w / 2, y, mz], [mx + w / 2, y, mz], 0.02, k.steel, 5);
  b.rod([mx, yr + 6.5, mz], [mx, yr + 8.0, mz], 0.015, k.steel, 4);
  const radar = b.sub('radar', mx, yr + 6.3, mz);
  radar.cyl(0.08, 0.15, k.dark, 0, 0, 0, 'y', 8);
  radar.box(1.1, 0.16, 0.05, k.steel, 0, 0.12, 0.05);
  radar.box(1.1, 0.03, 0.12, k.dark, 0, 0.21, 0.0);
  b.mesh('beacon', sphereGeo(0.07, 8, 6), k.eRed, mx, yr + 8.05, mz);
  // satellite dish
  b.at(-0.9, yr, zb + 0.6, 0.6, () => {
    b.rod([0, 0, 0], [0, 0.7, 0], 0.04, k.dark, 6);
    b.add(sphereGeo(0.55, 12, 4, TAU, 0.75), k.white, 0, 0.75, 0.2, -1.0, 0, 0, 1, 0.45, 1);
    b.rod([0, 0.85, 0.15], [0, 1.0, 0.75], 0.015, k.dark, 4);
  });
  // awning + terminal console
  const az = 1.55;
  for (const x of [-0.2, 2.4]) b.rod([x, S, az], [x, 2.35, az], 0.05, k.wood, 6);
  b.box(2.9, 0.06, 0.08, k.woodD, 1.1, 2.37, az);
  b.box(2.9, 0.03, 1.55, k.roofMetal, 1.1, y0 + H - 0.25, (zf + az) / 2 + 0.05, -0.18, 0, 0);
  b.at(1.15, S, 1.0, 0, () => {
    b.blk(1.3, 0.75, 0.6, k.woodD, 0, 0, 0);
    b.blk(1.4, 0.05, 0.7, k.wood, 0, 0.75, 0);
    b.cblk(0.62, 0.45, 0.3, k.grey, -0.15, 0.8, -0.1, 0.03);
    b.mesh('screen', quadGeo(0.5, 0.33), screenMat(), -0.15, 1.025, 0.055, -0.12, 0, 0);
    b.box(0.5, 0.04, 0.2, k.black, -0.15, 0.82, 0.18, 0.1, 0, 0);
    b.box(0.2, 0.25, 0.25, k.dark, 0.42, 0.8 + 0.125, -0.05);
    b.cyl(0.02, 0.4, k.steel, 0.42, 1.25, -0.1, 'y', 4);
  });
  panelLamp(b, 0.25, y0 + 1.75, zf + 0.04, 0);
  stackLight(b, 0.45, yr, zb + 0.3);
  // sandbag wall in front
  sandbagWall(b, -2.7, 2.45, -0.75, 2.45, S, 3);
  sandbagWall(b, 0.75, 2.45, 2.7, 2.45, S, 3);
  sandbagWall(b, 2.7, 2.2, 2.7, 1.0, S, 2);
  // flag
  const fx = -2.6;
  const fz = 1.7;
  b.cyl(0.18, 0.3, k.conc, fx, S + 0.15, fz, 'y', 8);
  b.rod([fx, S, fz], [fx, 6.2, fz], 0.035, k.steel, 6);
  b.add(sphereGeo(0.06, 6, 4), k.chrome, fx, 6.24, fz);
  const flagMat = tint('fabric', 0x8a2a1c);
  const f1 = b.sub('flag1', fx, 5.7, fz);
  f1.box(0.45, 0.8, 0.012, flagMat, 0.25, 0, 0);
  const f2 = f1.sub('flag2', 0.475, 0, 0);
  f2.box(0.45, 0.8, 0.012, flagMat, 0.225, 0, 0);
  const f3 = f2.sub('flag3', 0.45, 0, 0);
  f3.box(0.45, 0.8, 0.012, flagMat, 0.225, 0, 0);
  f3.box(0.1, 0.8, 0.014, tint('fabric', 0xd8c9a0), 0.42, 0, 0);
  // props
  barrel(b, -2.55, S, -2.6, k.rust);
  barrel(b, -1.95, S, -2.65, paint(0x2f4f6f));
  crate(b, 2.3, S, -2.55, 0.7, 0.2);
  crate(b, 2.3, S + 0.7, -2.55, 0.5, -0.3);
  crate(b, -2.2, S, 0.75, 0.6, 0.4);
  port(b, 0, -3, Math.PI, 3 + zb, 'in', body);
  cableRun(b, [[mx, yr + 0.05, mz + 0.1], [-1.0, yr + 0.05, zb + 0.15], [0.0, yr + 0.05, zb + 0.15], [0.4, yr + 0.3, zb + 0.3]], 0.025, 0.03);
  return (c) => {
    const radarO = c.get('radar');
    const a = c.get('flag1');
    const f2o = c.get('flag2');
    const f3o = c.get('flag3');
    const scr = c.mat('screen');
    const bea = c.mat('beacon');
    return (dt, running, t) => {
      radarO.rotation.y += dt * (running ? 1.6 : 0.3);
      a.rotation.y = Math.sin(t * 2.1) * 0.25;
      f2o.rotation.y = Math.sin(t * 2.1 - 1.1) * 0.35;
      f3o.rotation.y = Math.sin(t * 2.1 - 2.2) * 0.45;
      if (scr) scr.emissiveIntensity = running ? 1.3 + Math.sin(t * 23) * 0.08 : 0.35;
      if (bea) bea.emissiveIntensity = Math.sin(t * 2.5) > 0.7 ? 3 : 0.2;
    };
  };
}

// ---------------------------------------------------------------- MINER
function buildMiner(b: PB, mk2: boolean): Rig {
  const k = kit();
  const body = paint(mk2 ? 0x2f6690 : 0xc9952a);
  const trim = mk2 ? k.yellow : k.dark;
  const H = mk2 ? 10 : 8;
  const rnd = rng(mk2 ? 22 : 21);
  const fp = 2.25;
  // footings + ring beams
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
    b.cblk(1.1, 0.45, 1.1, k.conc, sx * fp, 0, sz * fp, 0.06);
    b.blk(0.6, 0.06, 0.6, k.steel, sx * fp, 0.45, sz * fp);
    for (const ox of [-0.22, 0.22]) for (const oz of [-0.22, 0.22]) b.cyl(0.03, 0.08, k.dark, sx * fp + ox, 0.53, sz * fp + oz, 'y', 6);
  }
  for (const s of [-1, 1]) {
    b.blk(fp * 2, 0.3, 0.1, k.dark, 0, 0.3, s * fp);
    b.blk(fp * 2, 0.04, 0.3, k.dark, 0, 0.6, s * fp);
    b.blk(0.1, 0.3, fp * 2, k.dark, s * fp, 0.3, 0);
    b.blk(0.3, 0.04, fp * 2, k.dark, s * fp, 0.6, 0);
    b.blk(fp * 2 - 1.2, 0.012, 0.3, k.hazard, 0, 0.64, s * fp);
  }
  // bore collar + rubble
  b.cyl(0.95, 0.5, k.dark, 0, 0.25, 0, 'y', 16);
  b.add(torusGeo(0.95, 0.07, 4, 16), k.steel, 0, 0.5, 0, Math.PI / 2, 0, 0);
  b.cyl(1.25, 0.08, k.dark, 0, 0.04, 0, 'y', 16);
  for (let i = 0; i < 8; i++) {
    const a = (i / 8) * TAU;
    b.box(0.12, 0.35, 0.3, k.dark, Math.cos(a) * 1.0, 0.2, Math.sin(a) * 1.0, 0, -a, 0);
  }
  rocks(b, rnd, mk2 ? 18 : 14, 0, 0, 2.0);
  // tower
  const hb = 2.05;
  const ht = 0.65;
  const y0 = 0.62;
  const hw = (y: number): number => hb + (ht - hb) * ((y - y0) / (H - y0));
  lattice4(b, hb, ht, y0, H, mk2 ? 4 : 3, mk2 ? 0.28 : 0.24, 0.09, body, trim);
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
    b.blk(0.42, 0.04, 0.42, k.dark, sx * hb, y0 - 0.02, sz * hb);
  }
  // platform
  const P = mk2 ? 5.2 : 4.3;
  const W = hw(P) + 0.45;
  b.blk(W * 2, 0.06, W - 0.6, k.grating, 0, P - 0.06, (W + 0.6) / 2);
  b.blk(W * 2, 0.06, W - 0.6, k.grating, 0, P - 0.06, -(W + 0.6) / 2);
  b.blk(W - 0.6, 0.06, 1.2, k.grating, (W + 0.6) / 2, P - 0.06, 0);
  b.blk(W - 0.6, 0.06, 1.2, k.grating, -(W + 0.6) / 2, P - 0.06, 0);
  for (const s of [-1, 1]) {
    b.blk(W * 2, 0.16, 0.08, k.dark, 0, P - 0.22, s * W);
    b.blk(0.08, 0.16, W * 2, k.dark, s * W, P - 0.22, 0);
    b.blk(W * 2, 0.12, 0.08, k.dark, 0, P - 0.2, s * 0.6);
  }
  railing(b, [[-W, -W], [W, -W], [W, W], [-W, W]], P, true);
  ladder(b, W + 0.25, 0, 0.0, P, Math.PI / 2, mk2, 0.25);
  // crown
  b.cblk(1.9, 0.7, 1.9, body, 0, H, 0, 0.06);
  b.blk(2.0, 0.08, 2.0, k.dark, 0, H - 0.04, 0);
  for (const sz of [-0.35, 0.35]) {
    b.cyl(0.38, 0.12, k.steel, 0, H + 1.0, sz, 'x', 14);
    b.box(0.08, 0.5, 0.08, k.dark, -0.15, H + 0.8, sz);
    b.box(0.08, 0.5, 0.08, k.dark, 0.15, H + 0.8, sz);
  }
  b.rod([0, H + 1.0, -0.35], [0, H + 1.0, 0.35], 0.05, k.dark, 6);
  // guide rails & static rams
  for (const sx of [-1, 1]) {
    b.blk(0.12, H - P, 0.18, k.steel, sx * 0.78, P, 0);
    ram(b, [0, P + 0.05, sx * 0.75], [0, H - 0.05, sx * 0.75], 0.11);
  }
  // drill head (bobs) + spinning auger
  const head = b.sub('drillHead');
  const gy = P + 1.0;
  head.cblk(1.3, 0.9, 1.3, body, 0, gy, 0, 0.05);
  head.blk(1.4, 0.08, 1.4, k.dark, 0, gy - 0.04, 0);
  for (const sx of [-1, 1]) head.blk(0.12, 0.6, 0.28, k.dark, sx * 0.7, gy + 0.15, 0);
  head.cyl(0.38, 0.9, k.dark, 0, gy + 1.35, 0, 'y', 12);
  for (let i = 0; i < 5; i++) head.cyl(0.42, 0.04, k.steel, 0, gy + 1.0 + i * 0.16, 0, 'y', 12, undefined, true);
  head.cyl(0.25, 0.12, k.steel, 0, gy + 1.86, 0, 'y', 10);
  if (mk2) {
    for (const sx of [-1, 1]) {
      head.cyl(0.24, 0.7, k.dark, sx * 0.48, gy + 0.55, 0.62, 'x', 10);
      head.cyl(0.26, 0.05, k.yellow, sx * 0.2, gy + 0.55, 0.62, 'x', 10);
    }
  }
  head.box(0.5, 0.25, 0.02, k.hazard, 0, gy + 0.5, 0.66);
  const spin = head.sub('drillSpin');
  spin.box(0.3, gy + 0.6, 0.3, k.steel, 0, (gy - 0.6) / 2, 0);
  spin.cyl(0.24, 0.4, k.dark, 0, gy - 0.3, 0, 'y', 8);
  spin.cyl(0.3, 0.12, k.dark, 0, 1.8, 0, 'y', 8);
  const ar = mk2 ? 0.75 : 0.62;
  const N = mk2 ? 30 : 24;
  for (let i = 0; i < N; i++) {
    const a = i * 0.55;
    spin.push(0, -0.3 + i * 0.085, 0, 0, a, 0);
    spin.box(ar - 0.12, 0.035, 0.22, k.metal, 0.12 + (ar - 0.12) / 2, 0, 0, 0.3, 0, 0);
    spin.pop();
  }
  spin.cyl(0.18, 0.5, k.dark, 0, -0.55, 0, 'y', 8, 0.18);
  spin.add(cylGeo(0.18, 0, 0.4, 8), k.chrome, 0, -1.0, 0);
  // ore elevator to hopper and output
  incline(b, [0, 0.7, 0.85], [0, 1.95, 1.75], 0.5, false);
  b.rod([-0.3, S, 1.5], [-0.3, 1.5, 1.5], 0.04, k.dark, 6);
  b.rod([0.3, S, 1.5], [0.3, 1.5, 1.5], 0.04, k.dark, 6);
  b.add(hopperGeo(1.25, 0.5, 0.8), body, 0, 1.95, 2.2);
  b.blk(1.32, 0.12, 1.32, k.dark, 0, 2.3, 2.2);
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.beam([sx * 0.55, S, 2.2 + sz * 0.5], [sx * 0.45, 1.75, 2.2 + sz * 0.4], 0.07, k.dark);
  port(b, 0, 3, 0, 0.75, 'out', body);
  // engine house at back
  b.blk(3.4, 0.2, 1.5, k.conc, 0, 0, -2.2);
  housing(b, 3.0, 2.0, 1.3, body, 0, 0.2, -2.25);
  vent(b, -0.8, 1.2, -1.6, 0, 0.9, 0.6);
  vent(b, 2.0 - 0.49, 1.1, -2.25, Math.PI / 2, 0.8, 0.8);
  vent(b, -1.51, 1.1, -2.25, -Math.PI / 2, 0.8, 0.8);
  controlPanel(b, 0.75, -1.45, 0, body, 0.2);
  panelLamp(b, 1.2, 1.8, -1.6, 0);
  b.blk(1.0, 0.25, 0.8, k.dark, -0.6, 2.2, -2.3);
  b.add(torusGeo(0.25, 0.03, 4, 12), k.dark, -0.6, 2.48, -2.3, Math.PI / 2, 0, 0);
  b.pipe([[0.9, 2.2, -2.5], [0.9, 3.8, -2.5]], 0.11, k.rust, 8, false);
  b.add(cylGeo(0, 0.2, 0.15, 8), k.dark, 0.9, 3.95, -2.5);
  if (mk2) b.pipe([[1.25, 2.2, -2.5], [1.25, 3.5, -2.5]], 0.09, k.rust, 8, false);
  stackLight(b, -1.25, 2.2, -1.75);
  cableRun(b, [[-1.3, 2.1, -2.2], [-hw(2.2) + 0.05, 2.25, -hw(2.2) + 0.05], [-hw(P) + 0.1, P + 0.3, -hw(P) + 0.1], [-0.5, H, -0.5]], 0.04, 0.15);
  if (mk2) {
    for (const sx of [-1, 1]) {
      b.cyl(0.32, 1.7, paint(0xd8d2c0), sx * 2.6, 0.2 + 0.85, 0.2, 'y', 12);
      b.add(sphereGeo(0.32, 10, 4, TAU, Math.PI / 2), paint(0xd8d2c0), sx * 2.6, 1.9, 0.2);
      b.cyl(0.35, 0.2, k.dark, sx * 2.6, 0.1, 0.2, 'y', 12);
      b.cyl(0.34, 0.06, k.yellow, sx * 2.6, 1.3, 0.2, 'y', 12, undefined, true);
      b.pipe([[sx * 2.6, 1.6, 0.52], [sx * 2.6, 1.6, 0.9], [sx * 0.6, 1.0, 0.9]], 0.04, k.steel, 6);
    }
  }
  return (c) => {
    const headO = c.get('drillHead');
    const spinO = c.get('drillSpin');
    const sp = smoother(1.2);
    let ph = 0;
    return (dt, running, t) => {
      const v = sp(dt, running ? 1 : 0);
      spinO.rotation.y += dt * v * (mk2 ? 7 : 5.5);
      ph += dt * v * 1.6;
      headO.position.y = (-0.18 + Math.sin(ph) * 0.18) * Math.min(1, v * 3) + Math.sin(t * 30) * 0.004 * v;
    };
  };
}

// ---------------------------------------------------------------- SMELTER
function buildSmelter(b: PB): Rig {
  const k = kit();
  const body = paint(0x7a3423);
  const glow = glowMat();
  slab(b, 3, 3);
  housing(b, 3.6, 1.7, 3.0, body, 0, S, -0.3);
  const yb = S + 1.7;
  b.blk(3.2, 1.7, 2.6, k.brick, 0, yb, -0.3);
  for (const y of [yb + 0.25, yb + 0.95, yb + 1.55]) b.blk(3.28, 0.1, 2.68, k.dark, 0, y, -0.3);
  for (const x of [-1.35, -0.75, 0.75, 1.35]) {
    b.blk(0.14, 1.75, 0.1, k.dark, x, yb, 1.03);
    b.blk(0.14, 1.75, 0.1, k.dark, x, yb, -1.63);
  }
  const yt = yb + 1.7;
  b.blk(3.4, 0.15, 2.8, k.dark, 0, yt, -0.3);
  // furnace mouth
  const my = yb + 0.75;
  b.mesh('glow', quadGeo(1.2, 0.8), glow, 0, my, 1.006);
  b.box(1.6, 0.18, 0.25, k.dark, 0, my + 0.49, 1.1);
  b.box(0.2, 1.0, 0.25, k.dark, -0.7, my, 1.1);
  b.box(0.2, 1.0, 0.25, k.dark, 0.7, my, 1.1);
  b.box(1.6, 0.12, 0.42, k.steel, 0, my - 0.46, 1.18);
  b.box(2.0, 0.08, 0.9, k.dark, 0, my + 0.95, 1.38, -0.35, 0, 0);
  for (const sx of [-1, 1]) b.box(0.06, 0.5, 0.8, k.dark, sx * 0.98, my + 0.8, 1.38);
  // pour trough to the output tunnel
  b.beam([0, my - 0.52, 1.3], [0, 1.5, 2.3], 0.5, k.dark, 0.12);
  for (const sx of [-1, 1]) b.beam([sx * 0.24, my - 0.42, 1.3], [sx * 0.24, 1.6, 2.3], 0.04, k.dark, 0.2);
  b.mesh('glow', mergedGeo((p, m) => p.beam([0, my - 0.45, 1.3], [0, 1.57, 2.3], 0.36, m, 0.02)), glow);
  port(b, 0, 3, 0, 1.8, 'out', body);
  vent(b, 0, 1.49, 1.95, 0, 0.9, 0.4);
  port(b, 0, -3, Math.PI, 1.2, 'in', body);
  // glow slits on the brick sides
  for (const sx of [-1, 1]) for (let i = 0; i < 3; i++) b.mesh('glow', quadGeo(0.5, 0.07), glow, sx * 1.605, yb + 0.55 + i * 0.3, -0.3, 0, (sx * Math.PI) / 2, 0);
  // bucket elevator + top hopper
  b.blk(0.7, 4.7, 0.7, body, 1.25, S, -2.35);
  for (let y = S + 0.8; y < S + 4.6; y += 1.2) b.blk(0.74, 0.12, 0.74, k.hazard, 1.25, y, -2.35);
  b.cblk(0.9, 0.6, 1.0, body, 1.25, S + 4.7, -2.3, 0.05);
  b.beam([1.15, S + 4.8, -1.85], [0.55, yt + 0.85, -1.0], 0.3, k.dark, 0.25);
  b.add(hopperGeo(1.0, 0.45, 0.65), k.dark, 0.5, yt + 0.5, -0.9);
  ladder(b, 1.25, -1.82, S, S + 4.6, 0, false, 0.1);
  // chimney + flue
  chimney(b, -1.7, S, -2.3, 0.38, 9);
  b.pipe([[-0.8, yt, -1.0], [-0.8, yt + 0.8, -1.0], [-1.7, yt + 0.8, -2.0]], 0.24, k.rust, 10);
  // blower on the right
  b.blk(0.8, 0.8, 0.8, k.dark, 2.35, S, 0.3);
  b.cyl(0.55, 0.45, k.grey, 2.35, 1.55, 0.3, 'x', 14);
  b.cyl(0.25, 0.5, k.dark, 2.35, 1.55, -0.3, 'z', 10);
  b.at(2.6, 1.55, 0.3, Math.PI / 2, () => fanGuard(b, 0.48));
  const fan = b.sub('fan', 2.6, 1.55, 0.3, 0, Math.PI / 2, 0);
  fanBlades(fan, 0.46, 6, k.steel, k.dark);
  b.pipe([[2.35, 2.0, 0.3], [2.35, 2.55, 0.3], [1.8, 2.55, 0.3]], 0.17, k.rust, 8);
  // left: gas cylinders + panel
  for (let i = 0; i < 3; i++) {
    const z = 0.15 + i * 0.32;
    b.cyl(0.13, 1.4, paint(i === 1 ? 0x2c5a34 : 0x6a6e72), -2.35, S + 0.7, z, 'y', 10);
    b.add(sphereGeo(0.13, 8, 4, TAU, Math.PI / 2), paint(i === 1 ? 0x2c5a34 : 0x6a6e72), -2.35, S + 1.4, z);
    b.cyl(0.03, 0.14, k.chrome, -2.35, S + 1.58, z, 'y', 6);
  }
  b.box(0.06, 0.06, 1.2, k.dark, -2.52, S + 1.0, 0.47);
  b.blk(0.1, 1.2, 0.1, k.dark, -2.55, S, -0.1);
  b.blk(0.1, 1.2, 0.1, k.dark, -2.55, S, 1.05);
  controlPanel(b, -1.95, 2.1, 0.3, body);
  ladder(b, -1.97, -1.0, S, yt + 0.15, -Math.PI / 2, false, 0.17);
  railing(b, [[-1.7, -1.7], [1.7, -1.7], [1.7, 1.1], [-1.7, 1.1]], yt + 0.15, true, 1.0, true);
  stackLight(b, 1.45, yt + 0.15, 0.85);
  panelLamp(b, 1.3, 1.25, 1.2, 0);
  gauge(b, -1.0, 1.3, 1.2, 0);
  gauge(b, -0.7, 1.3, 1.2, 0, 0.06);
  // ingots on a pallet
  b.blk(1.0, 0.12, 0.8, k.wood, 2.2, S, 2.2);
  for (let l = 0; l < 3; l++) for (let i = 0; i < 4; i++) b.blk(0.2, 0.09, 0.6, k.steel, 1.85 + i * 0.23, S + 0.12 + l * 0.09, 2.2, l % 2 ? Math.PI / 2 : 0);
  return (c) => {
    const g = c.mat('glow');
    const fanO = c.get('fan');
    const sp = smoother(1.0);
    return (dt, running, t) => {
      const v = sp(dt, running ? 1 : 0);
      if (g) g.emissiveIntensity = 0.25 + v * (2.0 + Math.sin(t * 9.1) * 0.25 + Math.sin(t * 23.7) * 0.15);
      fanO.rotation.z += dt * v * 14;
    };
  };
}

// ---------------------------------------------------------------- FOUNDRY
function buildFoundry(b: PB): Rig {
  const k = kit();
  const body = paint(0x4b5157);
  const glow = glowMat();
  slab(b, 3, 3);
  const cz = -0.5;
  for (const sx of [-1, 1]) {
    const x = sx * 1.55;
    b.cblk(2.3, 0.3, 2.3, body, x, S, cz, 0.05);
    const y0 = S + 0.3;
    b.cyl(1.05, 2.5, k.dark, x, y0 + 1.25, cz, 'y', 18);
    for (const y of [0.35, 1.25, 2.1]) b.cyl(1.1, 0.14, k.rust, x, y0 + y, cz, 'y', 18, undefined, true);
    b.cyl(1.0, 0.25, k.brick, x, y0 + 2.62, cz, 'y', 18);
    b.mesh('glow', cylGeo(0.8, 0.8, 0.02, 16), glow, x, y0 + 2.76, cz);
    b.add(torusGeo(0.88, 0.09, 4, 18), k.dark, x, y0 + 2.76, cz, Math.PI / 2, 0, 0);
    // open lid
    b.add(cylGeo(0.95, 0.95, 0.12, 16), k.dark, x, y0 + 3.3, cz - 0.95, -1.2, 0, 0);
    b.box(0.2, 0.5, 0.2, k.dark, x, y0 + 2.9, cz - 1.0);
    // tapping spout to centre
    b.beam([x - sx * 0.95, y0 + 2.2, cz + 0.5], [sx * 0.55, y0 + 1.9, 0.75], 0.22, k.dark, 0.12);
    // buttresses
    for (let i = 0; i < 4; i++) {
      const a = (i / 4) * TAU + Math.PI / 4;
      b.box(0.16, 1.6, 0.3, k.dark, x + Math.cos(a) * 1.08, y0 + 0.8, cz + Math.sin(a) * 1.08, 0, -a, 0);
    }
    // feed conveyor from back port
    port(b, sx * 2, -3, Math.PI, 1.0, 'in', body);
    incline(b, [sx * 2.0, 1.55, -2.3], [sx * 1.7, y0 + 3.05, -1.25], 0.5, true);
    // flue + chimney (front-side corners)
    b.pipe([[sx * 0.75, y0 + 1.9, cz - 0.75], [sx * 0.6, y0 + 1.9, -2.3], [sx * 0.3, y0 + 1.9, -2.4]], 0.2, k.rust, 10);
  }
  chimney(b, 0, S, -2.4, 0.42, 10);
  // ladle gantry
  for (const sx of [-1, 1]) b.blk(0.2, 3.3, 0.3, k.yellow, sx * 0.75, S, 0.9);
  b.blk(1.7, 0.25, 0.35, k.yellow, 0, S + 3.3, 0.9);
  const ladle = b.sub('ladle', 0, 2.5, 0.9);
  ladle.cyl(0.45, 0.7, k.rust, 0, 0, 0, 'y', 14, 0.5, false);
  ladle.add(torusGeo(0.5, 0.05, 4, 14), k.dark, 0, 0.35, 0, Math.PI / 2, 0, 0);
  ladle.mesh('glow', cylGeo(0.44, 0.44, 0.02, 14), glow, 0, 0.3, 0);
  ladle.box(0.2, 0.1, 0.3, k.dark, 0, 0.32, 0.5);
  ladle.cyl(0.07, 1.2, k.steel, 0, 0.05, 0, 'x', 8);
  // casting line
  for (const sx of [-1, 1]) b.blk(0.08, 0.2, 1.2, k.dark, sx * 0.5, 0.6, 1.75);
  for (let i = 0; i < 5; i++) b.cyl(0.05, 1.0, k.steel, 0, 0.75, 1.25 + i * 0.25, 'x', 8);
  for (const sx of [-1, 1]) for (const z of [1.3, 2.2]) b.blk(0.08, 0.42, 0.08, k.dark, sx * 0.5, S, z);
  for (let i = 0; i < 3; i++) {
    b.blk(0.7, 0.18, 0.32, k.dark, 0, 0.8, 1.35 + i * 0.36);
    b.mesh('glow', quadGeo(0.5, 0.18), glow, 0, 0.985, 1.35 + i * 0.36, -Math.PI / 2, 0, 0);
  }
  port(b, 0, 3, 0, 0.8, 'out', body);
  // gantry crane
  for (const sx of [-1, 1]) {
    for (const z of [-1.5, 1.25]) b.blk(0.22, 5.2, 0.22, k.yellow, sx * 2.8, S, z);
    b.blk(0.26, 0.3, 3.2, k.yellow, sx * 2.8, S + 5.2, -0.12);
    b.beam([sx * 2.8, S + 3.6, -1.5], [sx * 2.8, S + 5.0, -0.2], 0.08, k.dark);
    b.blk(0.06, 0.4, 0.3, k.hazard, sx * 2.8, S + 1.3, 1.25);
  }
  const bridge = b.sub('bridge', 0, S + 5.5, 0);
  bridge.box(5.9, 0.35, 0.3, k.yellow, 0, 0, 0);
  bridge.box(5.9, 0.04, 0.5, k.dark, 0, 0.19, 0);
  for (const sx of [-1, 1]) bridge.box(0.4, 0.3, 0.6, k.dark, sx * 2.8, -0.05, 0);
  bridge.cblk(0.5, 0.45, 0.55, k.dark, 0.6, -0.65, 0, 0.04);
  bridge.rod([0.6, -0.65, 0], [0.6, -2.2, 0], 0.02, k.steel, 4);
  bridge.add(torusGeo(0.1, 0.03, 4, 8, Math.PI * 1.5), k.dark, 0.6, -2.3, 0);
  stackLight(b, 2.8, S + 5.5, 1.25);
  panelLamp(b, -1.55, S + 0.15, cz + 1.16, 0);
  panelLamp(b, 1.55, S + 0.15, cz + 1.16, 0);
  controlPanel(b, 2.0, 1.7, -0.4, body);
  return (c) => {
    const g = c.mat('glow');
    const ladleO = c.get('ladle');
    const bridgeO = c.get('bridge');
    const sp = smoother(0.8);
    let ph = 0;
    return (dt, running, t) => {
      const v = sp(dt, running ? 1 : 0);
      ph += dt * v;
      if (g) g.emissiveIntensity = 0.3 + v * (2.1 + Math.sin(t * 7.3) * 0.25 + Math.sin(t * 19.1) * 0.15);
      const cyc = (ph * 0.35) % 1;
      ladleO.rotation.x = cyc < 0.5 ? Math.sin(cyc * 2 * Math.PI) * 0.75 : 0;
      bridgeO.position.z = Math.sin(ph * 0.4) * 1.0;
    };
  };
}

// ---------------------------------------------------------------- CONSTRUCTOR
function buildConstructor(b: PB): Rig {
  const k = kit();
  const body = k.orange;
  const grey = paint(0x50565c);
  slab(b, 3, 3);
  housing(b, 4.0, 1.1, 3.2, grey, 0, S, -0.2);
  const yb = S + 1.1;
  b.blk(2.4, 0.12, 1.5, k.steel, 0, yb, 0);
  for (let i = -2; i <= 2; i++) b.blk(2.2, 0.01, 0.03, k.dark, 0, yb + 0.12, i * 0.25);
  b.blk(1.1, 0.06, 0.8, k.metal, 0, yb + 0.12, 0);
  vent(b, -1.2, S + 0.6, 1.41, 0, 1.0, 0.5);
  vent(b, 1.2, S + 0.6, 1.41, 0, 1.0, 0.5);
  // columns + crown
  for (const sx of [-1, 1]) {
    housing(b, 0.75, 4.0, 1.1, body, sx * 1.55, yb, 0);
    b.box(0.77, 0.5, 0.02, k.hazard, sx * 1.55, yb + 0.6, 0.56);
    b.blk(0.2, 3.6, 0.1, k.steel, sx * 1.12, yb + 0.2, 0);
  }
  const yc = yb + 4.0;
  housing(b, 4.1, 1.15, 1.5, body, 0, yc, 0);
  b.box(2.0, 0.35, 0.02, k.hazard, 0, yc + 0.6, 0.76);
  b.cyl(0.46, 1.4, k.dark, 0, yc - 0.7, 0, 'y', 16);
  b.cyl(0.52, 0.12, k.steel, 0, yc - 1.4, 0, 'y', 16);
  b.cyl(0.4, 0.4, k.dark, 0, yc + 1.35, 0, 'y', 14);
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.rod([sx * 0.9, yb + 0.12, sz * 0.42], [sx * 0.9, yc, sz * 0.42], 0.05, k.chrome, 8);
  // ram (animated)
  const ramS = b.sub('ram');
  const py = yb + 1.3;
  ramS.cblk(2.4, 0.5, 1.2, body, 0, py, 0, 0.05);
  ramS.blk(1.1, 0.25, 0.8, k.steel, 0, py - 0.25, 0);
  ramS.cyl(0.24, 2.0, k.chrome, 0, py + 0.5 + 1.0, 0, 'y', 12);
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) ramS.cyl(0.09, 0.56, k.dark, sx * 0.9, py + 0.25, sz * 0.42, 'y', 8);
  ramS.box(2.42, 0.12, 0.02, k.hazard, 0, py + 0.25, 0.61);
  // hydraulic power unit + cabinet at back
  b.cblk(1.4, 0.8, 0.8, grey, -1.65, S, -2.4, 0.04);
  b.cyl(0.22, 0.6, k.dark, -1.9, S + 1.0, -2.4, 'x', 10);
  b.cyl(0.15, 0.3, k.steel, -1.45, S + 1.0, -2.4, 'x', 8);
  b.pipe([[-1.4, S + 0.9, -2.2], [-1.4, yc + 0.6, -2.2], [-1.4, yc + 0.6, -0.76]], 0.05, k.rubber, 6);
  b.pipe([[-1.2, S + 0.9, -2.25], [-1.2, yc + 0.3, -2.25], [-1.2, yc + 0.3, -0.76]], 0.04, k.rubber, 6);
  housing(b, 1.0, 1.8, 0.6, grey, 1.75, S, -2.45);
  b.blk(0.02, 1.5, 0.02, k.dark, 1.75, S + 0.15, -2.14);
  vent(b, 1.75, S + 0.5, -2.14, 0, 0.6, 0.3);
  panelLamp(b, 1.95, S + 1.55, -2.13, 0);
  cableRun(b, [[1.75, S + 1.8, -2.45], [1.75, yc + 0.2, -2.2], [1.4, yc + 0.5, -0.76]], 0.04, 0.05);
  // light curtain posts
  for (const sx of [-1, 1]) {
    b.blk(0.12, 1.9, 0.12, k.yellow, sx * 1.3, yb - 0.5 + 0.5, 1.58);
    b.box(0.02, 1.6, 0.05, k.eRed, sx * 1.235, yb + 1.0, 1.58);
  }
  controlPanel(b, 2.3, 2.0, -0.3, grey);
  port(b, 0, -3, Math.PI, 1.2, 'in', grey);
  port(b, 0, 3, 0, 1.6, 'out', grey);
  stackLight(b, 1.7, yc + 1.15, 0.5);
  sign(b, -1.55, yb + 2.5, 0.56, 0);
  return (c) => {
    const r = c.get('ram');
    const sp = smoother(2);
    let ph = 0;
    return (dt, running) => {
      const v = sp(dt, running ? 1 : 0);
      ph += dt * 0.75 * v;
      const p = ph % 1;
      let d: number;
      if (p < 0.18) d = p / 0.18;
      else if (p < 0.3) d = 1;
      else if (p < 0.75) d = 1 - (p - 0.3) / 0.45;
      else d = 0;
      d = d * d * (3 - 2 * d);
      r.position.y = -d * 0.98 * Math.min(1, v * 2);
    };
  };
}

// ---------------------------------------------------------------- ASSEMBLER
function robotArm(b: PB, name: string, x: number, z: number, yaw: number): void {
  const k = kit();
  const arm = k.orange;
  b.cyl(0.45, 0.25, k.dark, x, S + 0.125, z, 'y', 14);
  b.cyl(0.36, 0.3, arm, x, S + 0.4, z, 'y', 14);
  const y = b.sub(name, x, S + 0.55, z, 0, yaw, 0);
  y.cblk(0.62, 0.45, 0.62, arm, 0, 0, 0, 0.05);
  y.cyl(0.16, 0.3, k.dark, 0.38, 0.25, -0.1, 'x', 10);
  const s = y.sub(name + '_s', 0, 0.5, 0);
  s.cyl(0.24, 0.66, k.dark, 0, 0, 0, 'x', 12);
  s.cblk(0.32, 1.4, 0.34, arm, 0, 0, 0, 0.05);
  s.box(0.04, 1.0, 0.24, k.dark, 0.18, 0.7, 0);
  s.cyl(0.14, 0.3, k.dark, -0.3, 0.1, 0, 'x', 10);
  const e = s.sub(name + '_e', 0, 1.4, 0);
  e.cyl(0.18, 0.5, k.dark, 0, 0, 0, 'x', 12);
  e.add(cboxGeo(0.26, 0.26, 1.15, 0.04), arm, 0, 0, 0.5);
  e.cyl(0.035, 1.0, k.rubber, 0.16, 0.08, 0.45, 'z', 5);
  const w = e.sub(name + '_w', 0, 0, 1.1);
  w.cyl(0.13, 0.22, k.dark, 0, 0, 0, 'z', 10);
  w.box(0.3, 0.12, 0.12, k.steel, 0, 0, 0.16);
  w.box(0.04, 0.06, 0.2, k.steel, -0.12, 0, 0.3);
  w.box(0.04, 0.06, 0.2, k.steel, 0.12, 0, 0.3);
  w.add(sphereGeo(0.035, 6, 4), k.eCool, 0, 0, 0.3);
}

function buildAssembler(b: PB): Rig {
  const k = kit();
  const body = paint(0x3f6b5c);
  slab(b, 3, 3);
  // cage frame
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
    b.blk(0.24, 4.4, 0.24, body, sx * 2.7, S, sz * 2.7);
    b.blk(0.4, 0.06, 0.4, k.dark, sx * 2.7, S, sz * 2.7);
  }
  for (const s of [-1, 1]) {
    b.blk(5.64, 0.26, 0.24, body, 0, S + 4.4, s * 2.7);
    b.blk(0.24, 0.26, 5.64, body, s * 2.7, S + 4.4, 0);
    b.blk(5.2, 0.2, 0.16, k.dark, 0, S + 4.25, s * 0.9);
    b.blk(3.6, 0.08, 0.2, k.dark, 0, S + 3.9, s * 0.9);
    b.box(3.4, 0.03, 0.12, k.eCool, 0, S + 3.88, s * 0.9);
    for (const x of [-1.6, 1.6]) b.rod([x, S + 3.95, s * 0.9], [x, S + 4.25, s * 0.9], 0.012, k.dark, 4);
    // side mesh panels
    const x = s * 2.7;
    b.blk(0.03, 1.9, 5.2, k.grating, x, S + 0.3, 0);
    b.blk(0.06, 0.06, 5.2, k.yellow, x, S + 2.2, 0);
    b.blk(0.06, 0.06, 5.2, k.yellow, x, S + 0.27, 0);
    for (const z of [-0.9, 0.9]) b.blk(0.06, 1.95, 0.06, k.yellow, x, S + 0.3, z);
    b.box(5.4, 0.2, 0.03, k.hazard, 0, S + 4.53, s * 2.83);
  }
  // turntable
  b.cyl(0.55, 0.7, k.dark, 0, S + 0.35, 0.2, 'y', 14);
  b.cyl(0.7, 0.08, k.steel, 0, S + 0.04, 0.2, 'y', 14);
  const tt = b.sub('turntable', 0, S + 0.7, 0.2);
  tt.cyl(1.0, 0.15, k.steel, 0, 0.075, 0, 'y', 20);
  tt.add(torusGeo(1.0, 0.03, 4, 20), k.hazard, 0, 0.15, 0, Math.PI / 2, 0, 0);
  for (let i = 0; i < 3; i++) {
    const a = (i / 3) * TAU;
    tt.blk(0.35, 0.12, 0.35, k.dark, Math.cos(a) * 0.6, 0.15, Math.sin(a) * 0.6);
    tt.cblk(0.3, 0.25, 0.3, k.orange, Math.cos(a) * 0.6, 0.27, Math.sin(a) * 0.6, 0.03);
  }
  tt.cyl(0.18, 0.4, k.metal, 0, 0.35, 0, 'y', 10);
  robotArm(b, 'arm1', -1.55, -0.3, Math.PI / 2);
  robotArm(b, 'arm2', 1.55, 0.9, -Math.PI / 2);
  // conveyors
  for (const sx of [-1, 1]) {
    port(b, sx * 2, -3, Math.PI, 0.6, 'in', body);
    flatBelt(b, sx * 2, -2.4, -1.1);
  }
  flatBelt(b, 0, 1.3, 2.4);
  port(b, 0, 3, 0, 0.6, 'out', body);
  // control cabinet
  housing(b, 1.4, 2.0, 0.6, paint(0x50565c), 0, S, -2.5);
  b.blk(0.02, 1.7, 0.02, k.dark, 0, S + 0.15, -2.19);
  vent(b, -0.35, S + 0.5, -2.19, 0, 0.45, 0.4);
  vent(b, 0.35, S + 0.5, -2.19, 0, 0.45, 0.4);
  panelLamp(b, -0.4, S + 1.7, -2.19, 0);
  stackLight(b, 0.5, S + 2.0, -2.5);
  b.blk(0.4, 0.08, 4.6, k.dark, 0, S + 4.15, -0.3);
  cableRun(b, [[0, S + 2.0, -2.5], [0, S + 4.15, -2.4]], 0.05, 0.02);
  cableRun(b, [[-0.1, S + 4.15, -0.3], [-1.55, S + 0.6, -0.3]], 0.03, 0.3);
  cableRun(b, [[0.1, S + 4.15, 0.9], [1.55, S + 0.6, 0.9]], 0.03, 0.3);
  return (c) => {
    const a1 = [c.get('arm1'), c.get('arm1_s'), c.get('arm1_e'), c.get('arm1_w')];
    const a2 = [c.get('arm2'), c.get('arm2_s'), c.get('arm2_e'), c.get('arm2_w')];
    const tto = c.get('turntable');
    const sp = smoother(1.5);
    let ph = 0;
    return (dt, running) => {
      const v = sp(dt, running ? 1 : 0);
      ph += dt * v;
      const pose = (a: THREE.Object3D[], base: number, o: number): void => {
        a[0].rotation.y = base + Math.sin(ph * 1.1 + o) * 0.55;
        a[1].rotation.x = 0.45 + Math.sin(ph * 1.7 + o) * 0.3;
        a[2].rotation.x = 0.7 + Math.sin(ph * 1.7 + o + 1.2) * 0.35;
        a[3].rotation.z += dt * v * 3;
      };
      pose(a1, Math.PI / 2, 0);
      pose(a2, -Math.PI / 2, 2.1);
      tto.rotation.y = Math.floor(ph * 0.5) * (TAU / 3) + Math.min(1, ((ph * 0.5) % 1) * 4) * (TAU / 3);
    };
  };
}

// ---------------------------------------------------------------- LAB
function buildLab(b: PB): Rig {
  const k = kit();
  const wall = paint(0xdcdcd2);
  const accent = paint(0x2a8a86);
  slab(b, 3, 3);
  const cz = -0.3;
  const W = 5.0;
  const D = 3.6;
  const yf = S + 0.55;
  const H = 2.9;
  const zf = cz + D / 2;
  for (const sx of [-1, 0, 1]) for (const sz of [-1, 1]) b.blk(0.25, 0.32, 0.25, k.dark, sx * 2.2, S, cz + sz * 1.5);
  b.blk(W + 0.2, 0.25, D + 0.2, k.dark, 0, S + 0.3, cz);
  housing(b, W, H, D, wall, 0, yf, cz);
  b.blk(W + 0.04, 0.22, D + 0.04, accent, 0, yf + 0.55, cz);
  b.blk(W + 0.14, 0.12, D + 0.14, k.dark, 0, yf + H, cz);
  // front windows
  const win = (x: number, y: number, z: number, ry: number, w: number, h: number): void => {
    b.at(x, y, z, ry, () => {
      b.add(quadGeo(w, h), windowLitMat(), 0, 0, 0.006);
      for (let i = 0; i < 3; i++) b.cyl(0.035, 0.18, i === 1 ? k.eCool : k.eGreen, -w * 0.3 + i * 0.12, -h / 2 + 0.12, 0.03, 'y', 6);
      b.box(w * 0.9, 0.025, 0.06, k.dark, 0, -h / 2 + 0.02, 0.03);
      b.add(quadGeo(w, h), k.glass, 0, 0, 0.07);
      b.box(w + 0.12, 0.07, 0.12, k.black, 0, h / 2 + 0.035, 0.05);
      b.box(w + 0.12, 0.07, 0.18, k.black, 0, -h / 2 - 0.035, 0.07);
      b.box(0.06, h, 0.12, k.black, -w / 2 - 0.03, 0, 0.05);
      b.box(0.06, h, 0.12, k.black, w / 2 + 0.03, 0, 0.05);
      b.box(0.04, h, 0.1, k.black, 0, 0, 0.06);
    });
  };
  for (const x of [-1.6, 0, 1.6]) win(x, yf + 1.55, zf, 0, 1.0, 0.8);
  win(2.5, yf + 1.55, cz - 0.6, Math.PI / 2, 1.2, 0.8);
  win(-2.5, yf + 1.55, cz - 0.8, -Math.PI / 2, 1.0, 0.8);
  // red cross sign
  b.box(0.7, 0.7, 0.03, k.white, 0, yf + 2.45, zf + 0.02);
  b.box(0.45, 0.14, 0.035, tint('plasticWhite', 0xb3261e), 0, yf + 2.45, zf + 0.03);
  b.box(0.14, 0.45, 0.035, tint('plasticWhite', 0xb3261e), 0, yf + 2.45, zf + 0.03);
  // side door + steps
  b.box(0.06, 2.0, 0.95, paint(0x3c4a52), -2.53, yf + 1.0, cz + 1.05);
  b.cyl(0.025, 0.12, k.chrome, -2.58, yf + 1.0, cz + 0.7, 'x', 6);
  b.blk(0.45, yf - S - 0.02, 1.2, k.grating, -2.73, S, cz + 1.05);
  railing(b, [[-2.95, cz + 0.45], [-2.95, cz + 1.65]], yf, false, 0.9, false);
  panelLamp(b, -2.5, yf + 2.2, cz + 0.4, -Math.PI / 2);
  // roof gear
  const yr = yf + H + 0.12;
  for (const [x, z, n] of [[-1.3, cz - 0.6, 'fan1'], [0.6, cz - 0.6, 'fan2']] as [number, number, string][]) {
    b.cblk(1.1, 0.55, 0.9, k.grey, x, yr, z, 0.04);
    b.at(x, yr + 0.56, z, 0, () => fanGuard(b, 0.34), -Math.PI / 2);
    const f = b.sub(n, x, yr + 0.5, z, -Math.PI / 2, 0, 0);
    fanBlades(f, 0.32, 5, k.steel, k.dark);
    vent(b, x, yr + 0.27, z + 0.45, 0, 0.8, 0.3);
  }
  b.cyl(0.16, 1.7, k.steel, 1.9, yr + 0.85, cz - 1.3, 'y', 10);
  b.add(cylGeo(0, 0.3, 0.2, 10), k.dark, 1.9, yr + 1.9, cz - 1.3);
  b.rod([1.9, yr + 1.7, cz - 1.3], [1.9, yr + 1.82, cz - 1.3], 0.03, k.dark, 4);
  b.rod([-2.1, yr, cz - 1.5], [-2.1, yr + 2.6, cz - 1.5], 0.03, k.steel, 5);
  b.rod([-2.4, yr + 2.2, cz - 1.5], [-1.8, yr + 2.2, cz - 1.5], 0.015, k.steel, 4);
  b.rod([-2.3, yr + 1.8, cz - 1.5], [-1.9, yr + 1.8, cz - 1.5], 0.015, k.steel, 4);
  stackLight(b, 2.2, yr, cz + 1.4);
  railing(b, [[-2.45, zf - 0.05], [2.45, zf - 0.05]], yr, false, 0.7, false);
  // vial cabinet (front-left)
  b.at(-1.75, S, 2.3, 0, () => {
    b.blk(1.2, 0.35, 0.55, k.dark, 0, 0, 0);
    b.blk(1.2, 1.3, 0.04, wall, 0, 0.35, -0.25);
    for (const sx of [-1, 1]) b.blk(0.05, 1.3, 0.55, wall, sx * 0.58, 0.35, 0);
    b.blk(1.2, 0.08, 0.55, wall, 0, 1.65, 0);
    for (let s = 0; s < 3; s++) b.blk(1.12, 0.025, 0.45, k.steel, 0, 0.45 + s * 0.4, -0.02);
    b.add(quadGeo(1.1, 1.25), k.glass, 0, 1.0, 0.27);
  });
  b.mesh('vial', mergedGeo((p, m) => {
    for (let s = 0; s < 3; s++) for (let i = 0; i < 6; i++) if ((i + s) % 3 !== 0) p.cyl(0.04, 0.22, m, -1.75 - 0.42 + i * 0.17, S + 0.59 + s * 0.4, 2.3 - 0.05, 'y', 6);
  }), tint('emissiveGreen', 0xffffff));
  b.mesh('vial2', mergedGeo((p, m) => {
    for (let s = 0; s < 3; s++) for (let i = 0; i < 6; i++) if ((i + s) % 3 === 0) p.cyl(0.04, 0.22, m, -1.75 - 0.42 + i * 0.17, S + 0.59 + s * 0.4, 2.3 - 0.05, 'y', 6);
  }), tint('emissiveCool', 0xffffff));
  // chemical tank + gas bottles
  b.cyl(0.5, 1.3, k.white, 2.1, S + 0.75, 2.25, 'y', 14);
  b.add(sphereGeo(0.5, 12, 4, TAU, Math.PI / 2), k.white, 2.1, S + 1.4, 2.25, 0, 0, 0, 1, 0.35, 1);
  b.cyl(0.55, 0.12, k.dark, 2.1, S + 0.06, 2.25, 'y', 14);
  b.cyl(0.52, 0.05, accent, 2.1, S + 0.9, 2.25, 'y', 14, undefined, true);
  sign(b, 2.1, S + 1.0, 2.77, 0, 0.35, 0.35);
  b.pipe([[2.1, S + 0.3, 1.75], [2.1, S + 0.3, 1.4], [1.6, S + 0.9, 1.4]], 0.04, k.steel, 6);
  for (let i = 0; i < 3; i++) {
    const m = paint(i === 1 ? 0x8a8a2a : 0x2c5a34);
    b.cyl(0.12, 1.3, m, 2.75, S + 0.65, cz - 1.5 + i * 0.3, 'y', 10);
    b.add(sphereGeo(0.12, 8, 4, TAU, Math.PI / 2), m, 2.75, S + 1.3, cz - 1.5 + i * 0.3);
  }
  b.box(0.04, 0.04, 1.0, k.dark, 2.88, S + 1.0, cz - 1.2);
  // ports
  for (const sx of [-1, 1]) port(b, sx * 2, -3, Math.PI, 3 + (cz - D / 2), 'in', wall);
  port(b, 0, 3, 0, 3 - zf, 'out', wall);
  return (c) => {
    const f1 = c.get('fan1');
    const f2 = c.get('fan2');
    const v1 = c.mat('vial');
    const v2 = c.mat('vial2');
    const sp = smoother(1.5);
    return (dt, running, t) => {
      const v = sp(dt, running ? 1 : 0);
      f1.rotation.z += dt * (3 + v * 9);
      f2.rotation.z += dt * (3.5 + v * 9);
      if (v1) v1.emissiveIntensity = 0.4 + v * (1.4 + Math.sin(t * 2.2) * 0.6);
      if (v2) v2.emissiveIntensity = 0.4 + v * (1.4 + Math.sin(t * 2.2 + 2) * 0.6);
    };
  };
}

// ---------------------------------------------------------------- STORAGE
function buildStorage(b: PB): Rig {
  const k = kit();
  const body = paint(0x55663f);
  for (const sx of [-1, 1]) b.blk(0.16, 0.14, 1.94, k.dark, sx * 0.68, 0, 0);
  b.blk(1.86, 0.06, 1.86, k.dark, 0, 0.14, 0);
  b.cblk(1.76, 1.55, 1.6, body, 0, 0.2, 0, 0.05);
  for (const sx of [-1, 1]) {
    for (let i = 0; i < 5; i++) b.blk(0.05, 1.35, 0.1, body, sx * 0.9, 0.3, -0.6 + i * 0.3);
    b.blk(0.04, 0.3, 0.6, k.white, sx * 0.93, 0.95, 0.0);
    b.blk(0.045, 0.2, 0.45, k.dark, sx * 0.935, 1.0, 0.0);
    for (const sz of [-1, 1]) b.blk(0.1, 1.6, 0.1, k.dark, sx * 0.86, 0.18, sz * 0.78);
  }
  b.cblk(1.86, 0.12, 1.72, body, 0, 1.75, 0, 0.03);
  b.blk(1.9, 0.04, 1.76, k.dark, 0, 1.74, 0);
  for (const sx of [-1, 1]) b.box(0.3, 0.04, 0.04, k.steel, sx * 0.5, 1.92, 0.5);
  b.cyl(0.04, 1.7, k.dark, 0, 1.78, -0.86, 'x', 6);
  b.box(1.0, 0.07, 0.02, k.hazard, 0, 1.58, 0.81);
  port(b, 0, 1, 0, 0.2, 'out', body, 0.16);
  port(b, 0, -1, Math.PI, 0.2, 'in', body, 0.16);
  stackLight(b, 0.68, 1.87, -0.6);
  return () => NOOP;
}

// ---------------------------------------------------------------- SPLITTER / MERGER
function buildJunction(b: PB, merger: boolean): Rig {
  const k = kit();
  const body = paint(merger ? 0xc8641e : 0xd4a017);
  b.blk(1.9, 0.1, 1.9, k.dark, 0, 0, 0);
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.cyl(0.03, 0.04, k.steel, sx * 0.85, 0.12, sz * 0.85, 'y', 6);
  b.cblk(1.3, 1.3, 1.3, body, 0, 0.1, 0, 0.06);
  b.blk(1.34, 0.14, 1.34, k.dark, 0, 1.4, 0);
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.blk(0.1, 1.38, 0.1, k.dark, sx * 0.64, 0.1, sz * 0.64);
  const side: 'in' | 'out' = merger ? 'in' : 'out';
  port(b, 0, 1, 0, 0.36, 'out', body, 0.12);
  port(b, 0, -1, Math.PI, 0.36, 'in', body, 0.12);
  port(b, 1, 0, Math.PI / 2, 0.36, side, body, 0.12);
  port(b, -1, 0, -Math.PI / 2, 0.36, side, body, 0.12);
  const sp = b.sub('spinner', 0, 1.54, 0);
  sp.cyl(0.42, 0.06, k.dark, 0, 0.03, 0, 'y', 16);
  sp.add(torusGeo(0.42, 0.03, 4, 16), k.hazard, 0, 0.06, 0, Math.PI / 2, 0, 0);
  for (let i = 0; i < 3; i++) {
    sp.push(0, 0.1, 0, 0, (i / 3) * TAU, 0);
    sp.box(0.08, 0.08, 0.36, body, 0, 0, 0.2);
    sp.add(cylGeo(0, 0.09, 0.14, 3), body, 0, 0, 0.42, Math.PI / 2, 0, 0);
    sp.pop();
  }
  sp.cyl(0.1, 0.12, k.steel, 0, 0.12, 0, 'y', 8);
  stackLight(b, 0.52, 1.54, -0.52);
  return (c) => {
    const s = c.get('spinner');
    const v = smoother(2);
    return (dt, running) => {
      s.rotation.y += dt * v(dt, running ? 1 : 0) * (merger ? -2.5 : 2.5);
    };
  };
}

// ---------------------------------------------------------------- BELT
const beltMats = new Map<string, THREE.MeshStandardMaterial>();
function beltMaterial(tier: string): THREE.MeshStandardMaterial {
  let m = beltMats.get(tier);
  if (m) return m;
  m = M('belt').clone();
  m.name = 'beltSurface_' + tier;
  const map = m.map ? m.map.clone() : null;
  if (map) {
    map.wrapS = map.wrapT = THREE.RepeatWrapping;
    map.needsUpdate = true;
    m.map = map;
  }
  // Every other texture shares the colour map's offset so scrolling map.offset.y moves them all.
  const slots = ['normalMap', 'roughnessMap', 'metalnessMap', 'aoMap', 'bumpMap'] as const;
  for (const s of slots) {
    const t = m[s];
    if (t) {
      const c = t.clone();
      c.wrapS = c.wrapT = THREE.RepeatWrapping;
      c.needsUpdate = true;
      if (map) c.offset = map.offset;
      m[s] = c;
    }
  }
  beltMats.set(tier, m);
  return m;
}

function beltSurfaceGeo(): THREE.BufferGeometry {
  // top (y=0.6, facing up) and return run (y=0.49, facing down); u across X, v along Z (1 per metre)
  const hw = 0.55;
  const pos: number[] = [];
  const nrm: number[] = [];
  const uv: number[] = [];
  const idx: number[] = [];
  const quad = (y: number, ny: number): void => {
    const b0 = pos.length / 3;
    const corners: [number, number][] = [[-hw, -1], [hw, -1], [hw, 1], [-hw, 1]];
    for (const [x, z] of corners) {
      pos.push(x, y, z);
      nrm.push(0, ny, 0);
      uv.push(x + hw, z + 1);
    }
    if (ny > 0) idx.push(b0, b0 + 2, b0 + 1, b0, b0 + 3, b0 + 2);
    else idx.push(b0, b0 + 1, b0 + 2, b0, b0 + 2, b0 + 3);
  };
  quad(0.6, 1);
  quad(0.49, -1);
  const g = new THREE.BufferGeometry();
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute('normal', new THREE.Float32BufferAttribute(nrm, 3));
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2));
  g.setIndex(idx);
  g.computeBoundingSphere();
  return g;
}

function buildBelt(b: PB, mk2: boolean): Rig {
  const k = kit();
  const trim = mk2 ? paint(0x2f6db5) : paint(0xd99a1e);
  const L = 2.0;
  for (const sx of [-1, 1]) {
    const x = sx * 0.6;
    b.box(0.04, 0.25, L, k.dark, x, 0.565, 0);
    b.box(0.11, 0.025, L, k.metal, x - sx * 0.035, 0.69, 0);
    b.box(0.09, 0.025, L, k.dark, x - sx * 0.025, 0.44, 0);
    b.box(0.006, 0.07, L, trim, x + sx * 0.023, 0.58, 0);
    for (const z of [-0.94, 0.94]) b.box(0.02, 0.05, 0.05, k.steel, x + sx * 0.03, 0.5, z);
  }
  for (const z of [-0.67, 0, 0.67]) b.cyl(0.045, 1.16, k.steel, 0, 0.545, z, 'x', 8, undefined, true);
  b.box(1.2, 0.05, 0.06, k.dark, 0, 0.41, 0);
  for (const sx of [-1, 1]) {
    b.box(0.06, 0.4, 0.06, k.dark, sx * 0.56, 0.2, 0);
    b.box(0.17, 0.02, 0.17, k.dark, sx * 0.56, 0.01, 0);
  }
  b.box(1.08, 0.04, 0.04, k.dark, 0, 0.16, 0);
  if (mk2) {
    for (const sx of [-1, 1]) b.box(0.012, 0.025, L, k.yellow, sx * 0.627, 0.64, 0);
  }
  b.mesh('beltSurface', beltSurfaceGeo(), beltMaterial(mk2 ? 'mk2' : 'mk1'), 0, 0, 0, 0, 0, 0, true);
  return () => NOOP;
}

// ---------------------------------------------------------------- COAL GENERATOR
function buildCoalGen(b: PB): Rig {
  const k = kit();
  const body = paint(0x3d434a);
  const accent = paint(0x8f2a1c);
  const gen = paint(0x34566e);
  const glow = glowMat();
  slab(b, 3, 3);
  const ez = 0.3;
  for (const s of [-1, 1]) {
    b.blk(5.3, 0.22, 0.16, k.dark, 0, S, ez + s * 0.6);
    b.blk(5.3, 0.03, 0.4, k.dark, 0, S + 0.22, ez + s * 0.6);
  }
  const ex0 = -1.85;
  const ex1 = 0.95;
  const ecx = (ex0 + ex1) / 2;
  const ew = ex1 - ex0;
  b.cblk(ew, 1.25, 1.3, body, ecx, S + 0.25, ez, 0.08);
  b.blk(ew + 0.1, 0.12, 1.4, k.dark, ecx, S + 0.25, ez);
  b.blk(ew + 0.06, 0.06, 1.36, k.dark, ecx, S + 1.4, ez);
  for (let i = 0; i < 3; i++) {
    b.box(0.6, 0.45, 0.04, k.dark, ex0 + 0.5 + i * 0.9, S + 0.75, ez + 0.66);
    for (const sx of [-1, 1]) for (const sy of [-1, 1]) b.cyl(0.02, 0.03, k.steel, ex0 + 0.5 + i * 0.9 + sx * 0.25, S + 0.75 + sy * 0.18, ez + 0.69, 'z', 6);
  }
  b.cyl(0.1, 0.3, k.orange, ex0 + 0.3, S + 0.95, ez + 0.75, 'z', 10);
  const yt = S + 1.46;
  for (let i = 0; i < 6; i++) {
    const x = ex0 + 0.27 + i * 0.45;
    b.cblk(0.4, 0.3, 1.0, accent, x, yt, ez, 0.04);
    b.box(0.26, 0.05, 0.85, k.dark, x, yt + 0.33, ez);
    b.pipe([[x, yt + 0.15, ez - 0.5], [x, yt + 0.15, ez - 0.78]], 0.06, k.rust, 6, false);
    b.rod([x + 0.1, yt + 0.3, ez + 0.3], [x + 0.1, yt + 0.05, ez + 0.7], 0.015, k.chrome, 4);
  }
  b.cyl(0.1, ew - 0.2, k.rust, ecx, yt + 0.15, ez - 0.8, 'x', 8);
  b.pipe([[ex1 - 0.1, yt + 0.15, ez - 0.8], [1.6, yt + 0.15, ez - 0.8], [1.6, yt + 0.15, -2.3], [2.35, yt + 0.15, -2.3]], 0.12, k.rust, 8);
  chimney(b, 1.6, S, -2.3, 0.22, 6.2);
  chimney(b, 2.4, S, -2.3, 0.2, 5.4);
  // flywheel + generator
  b.blk(0.34, 0.8, 0.5, k.dark, 1.85, S + 0.25, ez);
  b.cyl(0.11, 1.5, k.steel, 1.5, 1.27, ez, 'x', 10);
  const fw = b.sub('flywheel', 1.3, 1.27, ez);
  fw.add(torusGeo(0.86, 0.12, 6, 24), k.dark, 0, 0, 0, 0, Math.PI / 2, 0);
  fw.cyl(0.2, 0.32, k.steel, 0, 0, 0, 'x', 12);
  for (let i = 0; i < 6; i++) {
    fw.push(0, 0, 0, (i / 6) * TAU, 0, 0);
    fw.box(0.08, 0.72, 0.12, k.dark, 0, 0.48, 0);
    fw.pop();
  }
  fw.box(0.26, 0.14, 0.1, k.hazard, 0, 0.86, 0);
  b.cyl(0.62, 1.0, gen, 2.4, 1.27, ez, 'x', 16);
  for (let i = 0; i < 5; i++) b.cyl(0.66, 0.05, k.dark, 2.0 + i * 0.2, 1.27, ez, 'x', 16, undefined, true);
  b.cyl(0.3, 0.06, k.dark, 2.92, 1.27, ez, 'x', 12);
  b.blk(1.0, 0.45, 0.9, k.dark, 2.4, S + 0.22, ez);
  b.cblk(0.4, 0.3, 0.4, k.grey, 2.4, 1.86, ez, 0.03);
  // radiator + fan (left end)
  b.cblk(0.35, 1.5, 1.4, k.dark, -2.3, S + 0.25, ez, 0.03);
  b.at(-2.5, S + 1.0, ez, -Math.PI / 2, () => {
    b.add(torusGeo(0.6, 0.06, 4, 18), k.dark, 0, 0, -0.02);
    fanGuard(b, 0.56);
  });
  const fan = b.sub('fan', -2.47, S + 1.0, ez, 0, -Math.PI / 2, 0);
  fanBlades(fan, 0.54, 6, k.steel, k.dark);
  b.pipe([[-2.3, S + 1.6, ez - 0.4], [-2.0, S + 1.6, ez - 0.4], [-2.0, yt, ez - 0.4]], 0.06, k.rubber, 6);
  // firebox + coal hopper
  housing(b, 1.2, 1.3, 1.1, body, -1.7, S, -1.95);
  b.mesh('glow', quadGeo(0.5, 0.36), glow, -2.306, S + 0.65, -1.95, 0, -Math.PI / 2, 0);
  b.box(0.06, 0.5, 0.66, k.dark, -2.32, S + 0.65, -1.95);
  b.at(-2.33, S + 0.65, -1.62, -Math.PI / 2, () => b.box(0.55, 0.42, 0.04, k.dark, -0.27, 0, 0), 0, 0);
  chimney(b, -1.7, S + 1.3, -2.1, 0.17, 3.6);
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.beam([sx * 0.55, S, -2.05 + sz * 0.5], [sx * 0.45, S + 1.75, -2.05 + sz * 0.4], 0.07, k.dark);
  b.add(hopperGeo(1.35, 0.45, 0.9), body, 0, S + 2.2, -2.05);
  b.blk(1.4, 0.1, 1.4, k.dark, 0, S + 2.62, -2.05);
  b.rod([0, S + 1.75, -2.05], [-1.1, S + 1.0, -1.95], 0.13, k.rust, 10);
  b.cyl(0.16, 0.3, k.dark, 0.2, S + 1.85, -2.05, 'x', 10);
  port(b, 0, -3, Math.PI, 0.55, 'in', body);
  // switchboard
  housing(b, 1.3, 1.9, 0.5, k.grey, 1.9, S, 2.4);
  gauge(b, 1.6, S + 1.45, 2.65, 0);
  gauge(b, 1.9, S + 1.45, 2.65, 0);
  gauge(b, 2.2, S + 1.45, 2.65, 0);
  b.box(0.9, 0.5, 0.02, k.black, 1.9, S + 0.8, 2.655);
  for (let i = 0; i < 4; i++) b.box(0.05, 0.16, 0.06, k.eRed, 1.6 + i * 0.2, S + 0.8, 2.67);
  panelLamp(b, 2.35, S + 1.75, 2.65, 0);
  stackLight(b, 1.45, S + 1.9, 2.3);
  cableRun(b, [[2.4, 2.0, ez], [2.4, 2.1, 1.6], [2.0, S + 1.9, 2.3]], 0.045, 0.15);
  rocks(b, rng(31), 18, -2.0, 2.0, 0.75, S, tint('rock', 0x262626));
  barrel(b, -0.9, S, 2.45, k.rust);
  barrel(b, -0.3, S, 2.55, paint(0x2e4b2a));
  return (c) => {
    const fwO = c.get('flywheel');
    const fanO = c.get('fan');
    const g = c.mat('glow');
    const sp = smoother(0.8);
    return (dt, running, t) => {
      const v = sp(dt, running ? 1 : 0);
      fwO.rotation.x += dt * v * 7;
      fanO.rotation.z += dt * v * 16;
      if (g) g.emissiveIntensity = 0.15 + v * (2.0 + Math.sin(t * 11) * 0.3 + Math.sin(t * 27) * 0.2);
    };
  };
}

// ---------------------------------------------------------------- BIOMASS BURNER
function buildBiomass(b: PB): Rig {
  const k = kit();
  const body = paint(0x6b4f33);
  const glow = glowMat();
  slab(b, 2, 2);
  housing(b, 1.5, 1.1, 1.3, k.brick, 0, S, 0.45);
  const zf = 1.1;
  b.mesh('glow', quadGeo(0.6, 0.42), glow, 0, S + 0.58, zf + 0.006);
  b.box(0.78, 0.08, 0.08, k.dark, 0, S + 0.83, zf + 0.04);
  b.box(0.78, 0.08, 0.08, k.dark, 0, S + 0.33, zf + 0.04);
  b.box(0.08, 0.58, 0.08, k.dark, -0.35, S + 0.58, zf + 0.04);
  b.box(0.08, 0.58, 0.08, k.dark, 0.35, S + 0.58, zf + 0.04);
  b.at(-0.36, S + 0.58, zf + 0.09, -1.15, () => {
    b.box(0.64, 0.48, 0.05, k.dark, 0.32, 0, 0);
    b.box(0.4, 0.06, 0.06, k.steel, 0.4, 0, 0.05);
    b.cyl(0.03, 0.5, k.steel, 0, 0, 0, 'y', 6);
  });
  vent(b, 0, S + 0.2, zf + 0.01, 0, 0.5, 0.12);
  // drum
  const dz = 0.3;
  const dy = 1.95;
  for (const sx of [-1, 1]) b.blk(0.2, 0.3, 1.0, k.dark, sx * 0.6, S + 1.1, dz);
  b.cyl(0.6, 2.2, k.rust, 0, dy, dz, 'x', 16);
  for (const sx of [-1, 1]) {
    b.add(sphereGeo(0.6, 14, 4, TAU, Math.PI / 2), k.rust, sx * 1.1, dy, dz, 0, 0, -sx * Math.PI / 2, 1, 0.35, 1);
    b.cyl(0.63, 0.07, k.dark, sx * 0.85, dy, dz, 'x', 16, undefined, true);
  }
  b.cyl(0.63, 0.07, k.dark, 0, dy, dz, 'x', 16, undefined, true);
  b.cyl(0.06, 0.3, k.chrome, 0.4, dy + 0.7, dz, 'y', 8);
  b.cyl(0.09, 0.1, k.chrome, 0.4, dy + 0.88, dz, 'y', 8);
  b.cyl(0.05, 0.4, k.steel, -0.3, dy + 0.75, dz, 'y', 6);
  gauge(b, 1.32, dy + 0.1, dz, Math.PI / 2, 0.1);
  // chimney & flue
  chimney(b, -1.2, S, -1.25, 0.2, 4.2);
  b.pipe([[-0.5, S + 0.8, -0.2], [-0.5, S + 0.8, -0.8], [-1.0, S + 0.8, -1.2]], 0.15, k.rust, 8);
  // dynamo with flywheel
  b.blk(0.9, 0.5, 0.7, k.dark, 1.2, S, -1.1);
  b.cyl(0.28, 0.6, body, 1.05, S + 0.82, -1.1, 'x', 12);
  for (let i = 0; i < 3; i++) b.cyl(0.3, 0.04, k.dark, 0.85 + i * 0.2, S + 0.82, -1.1, 'x', 12, undefined, true);
  b.cyl(0.05, 0.6, k.steel, 1.45, S + 0.82, -1.1, 'x', 6);
  const wheel = b.sub('wheel', 1.6, S + 0.82, -1.1);
  wheel.add(torusGeo(0.42, 0.06, 4, 18), k.dark, 0, 0, 0, 0, Math.PI / 2, 0);
  for (let i = 0; i < 5; i++) {
    wheel.push(0, 0, 0, (i / 5) * TAU, 0, 0);
    wheel.box(0.05, 0.4, 0.06, k.dark, 0, 0.2, 0);
    wheel.pop();
  }
  wheel.box(0.1, 0.1, 0.07, k.hazard, 0, 0.42, 0);
  b.pipe([[0.6, dy + 0.3, dz], [1.1, dy + 0.3, dz], [1.1, S + 1.15, -1.1]], 0.05, k.steel, 6);
  cableRun(b, [[1.2, S + 0.5, -1.45], [1.75, S + 0.2, -1.8]], 0.03, 0.05);
  // log pile + stump & axe
  const logs: [number, number][] = [[-1.6, 0], [-1.35, 0], [-1.1, 0], [-1.47, 1], [-1.23, 1], [-1.35, 2]];
  for (const [x, l] of logs) b.cyl(0.12, 1.0, k.bark, x, S + 0.12 + l * 0.21, 1.25, 'z', 7);
  b.cyl(0.22, 0.4, k.bark, 1.4, S + 0.2, 1.35, 'y', 9);
  b.cyl(0.2, 0.01, k.wood, 1.4, S + 0.405, 1.35, 'y', 9);
  b.rod([1.42, S + 0.45, 1.33], [1.6, S + 1.05, 1.45], 0.022, k.wood, 5);
  b.box(0.04, 0.14, 0.2, k.steel, 1.42, S + 0.47, 1.36, 0, 0.5, 0.3);
  panelLamp(b, 0.76, S + 0.85, 0.75, Math.PI / 2);
  stackLight(b, -0.55, S + 1.1, 1.0);
  return (c) => {
    const g = c.mat('glow');
    const w = c.get('wheel');
    const sp = smoother(0.9);
    return (dt, running, t) => {
      const v = sp(dt, running ? 1 : 0);
      if (g) g.emissiveIntensity = 0.12 + v * (2.0 + Math.sin(t * 8.3) * 0.35 + Math.sin(t * 21) * 0.2);
      w.rotation.x += dt * v * 6;
    };
  };
}

// ---------------------------------------------------------------- SOLAR
function buildSolar(b: PB): Rig {
  const k = kit();
  const frameM = k.steel;
  for (const sx of [-1, 1]) {
    b.cblk(0.6, 0.35, 2.4, k.conc, sx * 1.2, 0, 0, 0.04);
    b.blk(0.14, 1.05, 0.14, k.dark, sx * 1.2, 0.35, 0);
    b.blk(0.3, 0.03, 0.3, k.steel, sx * 1.2, 0.35, 0);
    b.beam([sx * 1.2, 0.35, -1.0], [sx * 1.2, 1.85, -0.7], 0.07, k.dark);
    b.beam([sx * 1.2, 0.35, 1.0], [sx * 1.2, 1.2, 0.75], 0.06, k.dark);
    b.cyl(0.08, 0.2, k.dark, sx * 1.2, 1.38, 0, 'x', 8);
  }
  b.push(0, 1.5, 0, 0.45, 0, 0);
  b.box(3.0, 0.1, 0.1, k.dark, 0, -0.12, 0);
  for (const z of [-0.6, 0.6]) b.box(3.7, 0.06, 0.08, frameM, 0, -0.06, z);
  for (let i = 0; i < 4; i++) {
    for (const zr of [-1, 1]) {
      const x = -1.35 + i * 0.9;
      const z = zr * 0.575;
      b.box(0.86, 0.04, 1.1, frameM, x, 0, z);
      b.box(0.82, 0.012, 1.06, solarCellMat(), x, 0.022, z);
      for (const ox of [-0.205, 0.205]) b.box(0.006, 0.014, 1.04, k.steel, x + ox, 0.024, z);
      for (const oz of [-0.26, 0, 0.26]) b.box(0.8, 0.014, 0.005, k.steel, x, 0.024, z + oz);
    }
  }
  b.pop();
  // inverter
  b.cblk(0.45, 0.55, 0.2, k.grey, -1.2, 0.6, 0.19, 0.03);
  vent(b, -1.2, 0.75, 0.29, 0, 0.3, 0.15);
  panelLamp(b, -1.08, 1.02, 0.29, 0);
  cableRun(b, [[-1.2, 1.15, 0.2], [-1.2, 1.4, 0.3]], 0.025, 0.02);
  b.rod([-1.2, 0.6, 0.2], [-1.2, 0.35, 0.2], 0.03, k.dark, 6);
  b.rod([-1.2, 0.36, 0.3], [-1.2, 0.36, 0.9], 0.03, k.rubber, 5);
  return () => NOOP;
}

// ---------------------------------------------------------------- WIND TURBINE
function buildWindTurbine(b: PB): Rig {
  const k = kit();
  const tower = paint(0x7a6a52);
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
    b.cblk(0.4, 0.3, 0.4, k.conc, sx * 0.75, 0, sz * 0.75, 0.03);
    b.blk(0.24, 0.03, 0.24, k.steel, sx * 0.75, 0.3, sz * 0.75);
  }
  const top = 14.6;
  lattice4(b, 0.75, 0.24, 0.3, top, 7, 0.11, 0.05, tower, k.dark);
  b.blk(1.1, 0.06, 1.1, k.grating, 0, top, 0);
  railing(b, [[-0.55, -0.55], [0.55, -0.55], [0.55, 0.55], [-0.55, 0.55]], top + 0.06, true, 0.8, false);
  ladder(b, 0, 0, 0.3, top, 0, false, 0.01);
  // nacelle
  const ny = top + 0.75;
  b.cyl(0.28, 0.25, k.dark, 0, top + 0.18, 0, 'y', 12);
  b.cyl(0.43, 1.6, k.rust, 0, ny, 0.05, 'z', 14);
  for (const z of [-0.45, 0.55]) b.cyl(0.45, 0.06, k.dark, 0, ny, z, 'z', 14, undefined, true);
  b.cyl(0.43, 0.04, k.dark, 0, ny, -0.76, 'z', 14);
  b.cblk(0.55, 0.55, 0.45, k.dark, 0, ny - 0.27, 0.95, 0.04);
  b.cyl(0.07, 0.4, k.steel, 0, ny, 1.3, 'z', 8);
  b.blk(0.4, 0.25, 0.5, paint(0x5a5f63), 0, ny + 0.4, -0.2);
  b.beam([0, ny + 0.1, -0.7], [0, ny + 0.3, -2.6], 0.09, k.dark);
  b.beam([0, ny - 0.2, -0.6], [0, ny + 0.2, -2.3], 0.05, k.dark);
  b.box(0.03, 1.0, 1.3, paint(0x8a2a1c), 0, ny + 0.45, -2.75);
  b.box(0.035, 0.18, 1.3, k.hazard, 0, ny + 0.85, -2.75);
  // rotor
  const r = b.sub('rotor', 0, ny, 1.5);
  r.cyl(0.24, 0.3, k.dark, 0, 0, 0, 'z', 12);
  r.add(cylGeo(0, 0.24, 0.35, 12), paint(0xd8d2c0), 0, 0, 0.32, Math.PI / 2, 0, 0);
  r.cyl(0.42, 0.06, k.steel, 0, 0, -0.1, 'z', 12);
  const blade = paint(0xd8d2c0);
  for (let i = 0; i < 3; i++) {
    r.push(0, 0, 0, 0, 0, (i / 3) * TAU);
    r.box(0.12, 0.6, 0.06, k.dark, 0, 0.45, -0.05);
    r.push(0, 0.35 + 1.75, -0.38, 0, 0.35, 0);
    r.add(cylGeo(0.42, 0.42, 3.5, 5, true, -0.5, 1.0), blade, 0, 0, 0);
    r.add(cylGeo(0.425, 0.425, 0.3, 5, true, -0.5, 1.0), paint(0x8a2a1c), 0, 1.6, 0);
    r.pop();
    r.pop();
  }
  // power drop + junction box
  cableRun(b, [[0.1, top, 0.1], [0.12, 0.9, 0.12]], 0.03, 0.02);
  b.blk(0.08, 0.9, 0.08, k.dark, 0, 0, 0.92);
  b.cblk(0.45, 0.5, 0.22, k.grey, 0, 0.75, 0.82, 0.03);
  panelLamp(b, 0.1, 1.05, 0.93, 0);
  b.box(0.2, 0.15, 0.02, k.hazard, -0.08, 0.9, 0.935);
  return (c) => {
    const rot = c.get('rotor');
    const sp = smoother(0.4);
    return (dt, running) => {
      rot.rotation.z += dt * (0.5 + sp(dt, running ? 1 : 0) * 2.6);
    };
  };
}

// ---------------------------------------------------------------- BATTERY
function buildBattery(b: PB): Rig {
  const k = kit();
  const body = paint(0x59626b);
  const accent = paint(0x2e6a9a);
  slab(b, 2, 2);
  const cz = -0.85;
  for (let i = 0; i < 3; i++) {
    const x = -1.25 + i * 1.25;
    housing(b, 1.15, 2.1, 0.9, body, x, S, cz);
    const zf = cz + 0.45;
    b.blk(0.02, 1.8, 0.02, k.dark, x, S + 0.15, zf + 0.01);
    for (const sx of [-1, 1]) b.box(0.03, 0.2, 0.04, k.chrome, x + sx * 0.08, S + 1.1, zf + 0.03);
    vent(b, x - 0.27, S + 0.45, zf, 0, 0.36, 0.3);
    vent(b, x + 0.27, S + 0.45, zf, 0, 0.36, 0.3);
    b.box(0.5, 0.12, 0.02, accent, x - 0.27, S + 1.7, zf + 0.01);
    b.box(0.3, 0.2, 0.02, k.white, x + 0.27, S + 1.6, zf + 0.01);
    const fn = 'fan' + (i + 1);
    b.at(x, S + 2.11, cz, 0, () => fanGuard(b, 0.3), -Math.PI / 2);
    const f = b.sub(fn, x, S + 2.08, cz, -Math.PI / 2, 0, 0);
    fanBlades(f, 0.28, 5, k.steel, k.dark);
  }
  sign(b, 0, S + 1.25, cz + 0.46, 0, 0.36, 0.26);
  b.blk(3.6, 0.08, 0.3, k.dark, 0, S + 2.1, cz - 0.3);
  // charge display
  const dz = 0.75;
  b.blk(0.25, 0.85, 0.25, k.dark, 0, S, dz);
  b.at(0, S + 1.25, dz, 0, () => {
    b.cblk(1.3, 0.75, 0.25, body, 0, -0.375, 0, 0.04);
    b.box(1.04, 0.42, 0.02, k.black, -0.02, 0, 0.13);
    b.box(1.12, 0.06, 0.03, k.white, -0.02, 0.24, 0.135);
    b.box(1.12, 0.06, 0.03, k.white, -0.02, -0.24, 0.135);
    b.box(0.05, 0.54, 0.03, k.white, -0.56, 0, 0.135);
    b.box(0.05, 0.54, 0.03, k.white, 0.52, 0, 0.135);
    b.box(0.08, 0.2, 0.03, k.white, 0.585, 0, 0.135);
    for (let i = 0; i < 6; i++) b.mesh('chargeSeg', boxGeo(0.14, 0.34, 0.02), k.eGreen, -0.45 + i * 0.165, 0, 0.145);
  }, -0.25);
  // HV junction with insulators
  housing(b, 0.7, 0.9, 0.6, k.grey, 1.4, S, 0.7);
  insulator(b, 1.25, S + 0.9, 0.7, 3, 0.07);
  insulator(b, 1.55, S + 0.9, 0.7, 3, 0.07);
  sign(b, 1.4, S + 0.55, 1.01, 0, 0.3, 0.22);
  cableRun(b, [[1.25, S + 2.1, cz - 0.3], [1.5, S + 1.6, 0.0], [1.55, S + 1.3, 0.7]], 0.05, 0.1);
  cableRun(b, [[-1.25, S + 2.15, cz - 0.3], [1.25, S + 2.15, cz - 0.3]], 0.05, 0.05);
  cableRun(b, [[0, S + 0.3, cz + 0.45], [0, S + 0.05, 0.3], [0, S + 0.4, dz - 0.13]], 0.03, 0.05);
  stackLight(b, 0, S + 2.18, cz + 0.25);
  return (c) => {
    const fans = [c.get('fan1'), c.get('fan2'), c.get('fan3')];
    const sp = smoother(1.2);
    return (dt, running) => {
      const v = sp(dt, running ? 1 : 0);
      for (let i = 0; i < 3; i++) fans[i].rotation.z += dt * v * (11 + i);
    };
  };
}

// ---------------------------------------------------------------- POWER POLE
function buildPowerPole(b: PB): Rig {
  const k = kit();
  b.cyl(0.34, 0.22, k.conc, 0, 0.11, 0, 'y', 10);
  rocks(b, rng(5), 6, 0, 0, 0.6);
  b.cyl(0.14, 6.3, k.woodD, 0, 3.15, 0, 'y', 10, 0.115);
  b.cyl(0.12, 0.05, k.dark, 0, 6.32, 0, 'y', 10);
  const ya = 5.75;
  b.box(1.9, 0.13, 0.13, k.wood, 0, ya, 0.0);
  for (const sx of [-1, 1]) b.beam([sx * 0.65, ya - 0.06, 0.08], [0, ya - 0.6, 0.14], 0.04, k.steel, 0.01);
  b.cyl(0.02, 0.4, k.steel, 0, ya, 0, 'z', 6);
  const insM = tint('plasticWhite', 0x6f9f8a);
  insulator(b, -0.8, ya + 0.065, 0, 3, 0.07, insM);
  insulator(b, 0.8, ya + 0.065, 0, 3, 0.07, insM);
  insulator(b, 0, 6.345, 0, 3, 0.07, insM);
  b.anchor('cableAnchor', 0, 6.345 + 0.42, 0);
  b.anchor('cableAnchorL', -0.8, ya + 0.065 + 0.42, 0);
  b.anchor('cableAnchorR', 0.8, ya + 0.065 + 0.42, 0);
  // transformer can
  b.box(0.08, 0.5, 0.2, k.dark, 0, 4.6, 0.17);
  b.cyl(0.24, 0.7, k.grey, 0, 4.6, 0.42, 'y', 12);
  b.cyl(0.26, 0.05, k.dark, 0, 4.97, 0.42, 'y', 12);
  insulator(b, -0.1, 4.97, 0.42, 1, 0.04);
  insulator(b, 0.1, 4.97, 0.42, 1, 0.04);
  b.cable([-0.1, 5.15, 0.42], [-0.8, ya + 0.3, 0.0], 0.05, 0.01, k.rubber, 4);
  b.cable([0.1, 5.15, 0.42], [0.8, ya + 0.3, 0.0], 0.05, 0.01, k.rubber, 4);
  for (let i = 0; i < 6; i++) {
    const y = 2.0 + i * 0.45;
    const a = i % 2 ? Math.PI / 2 : -Math.PI / 2;
    b.rod([0, y, 0], [Math.cos(a + Math.PI / 2) * 0.0 + (i % 2 ? 0.3 : -0.3), y, 0], 0.014, k.steel, 4);
  }
  b.box(0.12, 0.18, 0.01, k.yellow, 0, 2.0, 0.13);
  b.rod([0, 0.2, -0.13], [0, 4.4, -0.125], 0.01, k.dark, 4);
  return () => NOOP;
}

// ---------------------------------------------------------------- FLOODLIGHT
function buildFloodlight(b: PB): Rig {
  const k = kit();
  const mast = paint(0x5a5f63);
  b.cblk(0.9, 0.3, 0.9, k.conc, 0, 0, 0, 0.05);
  b.blk(0.5, 0.04, 0.5, k.steel, 0, 0.3, 0);
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.cyl(0.025, 0.08, k.dark, sx * 0.18, 0.36, sz * 0.18, 'y', 6);
  b.cyl(0.085, 6.6, mast, 0, 0.34 + 3.3, 0, 'y', 10, 0.06);
  for (let i = 0; i < 4; i++) {
    b.push(0, 0.34, 0, 0, (i / 4) * TAU + Math.PI / 4, 0);
    b.add(extrudeGeo([[0, 0], [0.18, 0], [0, 0.35]], 0.015), mast, 0.07, 0, 0);
    b.pop();
  }
  for (let i = 0; i < 8; i++) b.rod([0, 1.8 + i * 0.5, 0], [i % 2 ? 0.22 : -0.22, 1.8 + i * 0.5, 0], 0.014, k.steel, 4);
  b.cblk(0.32, 0.42, 0.18, k.grey, 0, 0.95, 0.16, 0.03);
  panelLamp(b, 0.0, 1.5, 0.12, 0);
  cableRun(b, [[0.06, 1.37, 0.1], [0.07, 6.7, 0.07]], 0.018, 0.02);
  const yt = 6.95;
  b.cyl(0.1, 0.1, k.dark, 0, yt, 0, 'y', 10);
  b.box(0.1, 0.1, 0.55, k.dark, 0, yt, 0.25);
  for (const sx of [-1, 1]) b.box(0.04, 0.5, 0.12, k.dark, sx * 0.62, yt - 0.2, 0.5);
  b.box(1.28, 0.06, 0.12, k.dark, 0, yt + 0.03, 0.5);
  b.push(0, yt - 0.25, 0.5, 0.65, 0, 0);
  b.cblk(1.15, 0.62, 0.3, k.dark, 0, -0.31, 0, 0.03);
  for (let i = 0; i < 7; i++) b.box(0.025, 0.5, 0.12, k.dark, -0.45 + i * 0.15, 0, -0.2);
  b.box(1.2, 0.03, 0.3, k.dark, 0, 0.33, 0.22, -0.25, 0, 0);
  b.box(1.0, 0.04, 0.03, k.steel, 0, 0.27, 0.16);
  b.box(1.0, 0.04, 0.03, k.steel, 0, -0.27, 0.16);
  b.mesh('lamp', quadGeo(1.0, 0.5), k.eWarm, 0, 0, 0.152);
  b.box(0.02, 0.5, 0.02, k.dark, -0.17, 0, 0.165);
  b.box(0.02, 0.5, 0.02, k.dark, 0.17, 0, 0.165);
  b.anchor('lightAnchor', 0, 0, 0.2);
  b.pop();
  return () => NOOP;
}

// ---------------------------------------------------------------- TURRET
function buildTurret(b: PB): Rig {
  const k = kit();
  const body = paint(0x4d5536);
  b.blk(1.5, 0.08, 1.5, k.dark, 0, 0, 0);
  for (let i = 0; i < 3; i++) {
    const a = (i / 3) * TAU + Math.PI / 6;
    b.rod([Math.cos(a) * 0.8, 0.1, Math.sin(a) * 0.8], [0, 0.95, 0], 0.045, k.dark, 6);
    b.blk(0.2, 0.04, 0.2, k.steel, Math.cos(a) * 0.8, 0.08, Math.sin(a) * 0.8);
  }
  b.cyl(0.16, 1.25, k.dark, 0, 0.08 + 0.625, 0, 'y', 12);
  b.cyl(0.3, 0.1, k.steel, 0, 1.33, 0, 'y', 16);
  for (let i = 0; i < 9; i++) {
    const a = (i / 9) * TAU;
    if (i === 2) continue;
    sandbag(b, Math.cos(a) * 0.9, 0.0, Math.sin(a) * 0.9, -a + Math.PI / 2);
    if (i % 2 === 0) sandbag(b, Math.cos(a + 0.35) * 0.88, 0.17, Math.sin(a + 0.35) * 0.88, -a + Math.PI / 2 - 0.35);
  }
  b.cblk(0.45, 0.3, 0.32, paint(0x5a5a30), -0.45, 0.08, -0.45, 0.03);
  b.box(0.3, 0.06, 0.06, k.dark, -0.45, 0.4, -0.45);
  b.cblk(0.32, 0.42, 0.2, k.grey, 0.0, 0.25, -0.45, 0.03);
  panelLamp(b, 0.0, 0.53, -0.555, Math.PI);
  cableRun(b, [[0, 0.5, -0.4], [0, 1.0, -0.15]], 0.025, 0.03);
  // head (yaw about Y)
  const head = b.sub('head', 0, 1.4, 0);
  head.cblk(0.7, 0.42, 0.8, body, 0, 0, -0.05, 0.06);
  head.box(0.74, 0.36, 0.05, body, 0, 0.26, 0.36, -0.7, 0, 0);
  for (const sx of [-1, 1]) {
    head.box(0.05, 0.55, 0.85, body, sx * 0.4, 0.3, -0.02);
    head.box(0.055, 0.12, 0.6, k.hazard, sx * 0.4, 0.5, -0.05);
    head.cyl(0.04, 0.07, k.steel, sx * 0.44, 0.28, 0.25, 'x', 6);
  }
  head.cblk(0.3, 0.34, 0.5, paint(0x5a5a30), -0.6, 0.05, -0.05, 0.03);
  head.box(0.18, 0.06, 0.3, k.dark, -0.47, 0.42, 0.05, 0, 0, 0.6);
  head.cblk(0.22, 0.15, 0.22, k.dark, 0.2, 0.42, -0.1, 0.03);
  head.add(cylGeo(0.045, 0.045, 0.03, 10), k.eRed, 0.2, 0.495, 0.015, Math.PI / 2, 0, 0);
  head.rod([-0.2, 0.42, -0.35], [-0.2, 1.2, -0.38], 0.01, k.dark, 4);
  const radar = head.sub('radar', 0.1, 0.57, -0.3);
  radar.cyl(0.03, 0.1, k.dark, 0, 0, 0, 'y', 6);
  radar.box(0.32, 0.08, 0.02, k.steel, 0, 0.07, 0.02);
  // barrel (pitch about X), points +Z
  const bar = head.sub('barrel', 0, 0.28, 0.2);
  bar.cyl(0.06, 0.92, k.steel, 0, 0, 0, 'x', 8);
  bar.cblk(0.26, 0.24, 0.62, k.dark, 0, -0.12, 0.12, 0.03);
  bar.cyl(0.075, 0.9, k.dark, 0, 0, 0.88, 'z', 10);
  for (let i = 0; i < 4; i++) bar.cyl(0.082, 0.04, k.metal, 0, 0, 0.55 + i * 0.22, 'z', 10, undefined, true);
  bar.cyl(0.035, 0.38, k.metal, 0, 0, 1.5, 'z', 8);
  bar.cyl(0.058, 0.14, k.dark, 0, 0, 1.72, 'z', 8);
  bar.box(0.13, 0.025, 0.06, k.dark, 0, 0, 1.72);
  bar.cyl(0.035, 0.32, k.dark, 0.0, 0.16, 0.2, 'z', 8);
  bar.anchor('muzzle', 0, 0, 1.8);
  return (c) => {
    const r = c.get('radar');
    const sp = smoother(2);
    return (dt, running) => {
      r.rotation.y += dt * sp(dt, running ? 1 : 0) * 4;
    };
  };
}

// ---------------------------------------------------------------- ELECTRIC FENCE
function buildFence(b: PB): Rig {
  const k = kit();
  const heights = [0.3, 0.65, 1.0, 1.35, 1.7, 2.05];
  const insM = tint('plasticWhite', 0xe0d8c0);
  for (const sx of [-1, 1]) {
    const x = sx * 0.95;
    b.cyl(0.14, 0.25, k.conc, x, 0.125, 0, 'y', 8);
    b.blk(0.08, 2.3, 0.08, k.rust, x, 0.1, 0);
    b.beam([x, 2.3, 0], [x, 2.6, 0.3], 0.05, k.rust);
    b.cyl(0.05, 0.03, k.dark, x, 2.41, 0, 'y', 6);
    for (const h of heights) b.cyl(0.03, 0.08, insM, x, h, 0.07, 'z', 6);
    b.cyl(0.03, 0.08, insM, x, 2.52, 0.24, 'z', 6);
  }
  b.mesh('wires', mergedGeo((p, m) => {
    for (const h of heights) p.rod([-1.0, h, 0.1], [1.0, h, 0.1], 0.009, m, 5, true);
    p.rod([-1.0, 2.52, 0.3], [1.0, 2.52, 0.3], 0.009, m, 5, true);
    for (let i = 0; i < 10; i++) p.rod([-0.9 + i * 0.2, 2.52, 0.27], [-0.9 + i * 0.2, 2.52, 0.33], 0.006, m, 3, true);
  }), M('steelBrushed'), 0, 0, 0, 0, 0, 0, false);
  sign(b, 0.15, 1.18, 0.11, 0, 0.42, 0.3);
  b.cblk(0.22, 0.3, 0.14, k.grey, -0.75, 1.0, -0.07, 0.02);
  panelLamp(b, -0.75, 1.2, 0.0, 0);
  return (c) => {
    const w = c.mat('wires');
    if (w) w.emissive.setHex(0x7fc8ff);
    let next = 0;
    let flash = 0;
    return (dt, running, t) => {
      if (!w) return;
      if (running && t > next) {
        flash = 0.12;
        next = t + 0.6 + Math.random() * 2.5;
      }
      flash = Math.max(0, flash - dt);
      w.emissiveIntensity = flash > 0 ? 1.5 : 0;
    };
  };
}

// ---------------------------------------------------------------- SPIKE TRAP
function buildSpikes(b: PB): Rig {
  const k = kit();
  const rnd = rng(77);
  b.add(cylGeo(0.1, 0.1, 2.2, 8), k.bark, 0, 0.1, 0, 0, Math.PI / 4, Math.PI / 2);
  b.add(cylGeo(0.1, 0.1, 2.2, 8), k.bark, 0, 0.1, 0, 0, -Math.PI / 4, Math.PI / 2);
  for (const s of [-1, 1]) {
    b.box(1.7, 0.08, 0.16, k.woodD, 0, 0.04, s * 0.8, 0, (rnd() - 0.5) * 0.06, 0);
    b.box(0.16, 0.08, 1.7, k.woodD, s * 0.8, 0.04, 0, 0, (rnd() - 0.5) * 0.06, 0);
  }
  for (let i = 0; i < 18; i++) {
    const a = rnd() * TAU;
    const d = Math.sqrt(rnd()) * 0.75;
    const x = Math.cos(a) * d;
    const z = Math.sin(a) * d;
    const tilt = 0.15 + rnd() * 0.5;
    const ta = a + (rnd() - 0.5) * 0.8;
    const len = 0.55 + rnd() * 0.45;
    const base: V3 = [x, 0.05, z];
    const dir = new THREE.Vector3(Math.cos(ta) * Math.sin(tilt), Math.cos(tilt), Math.sin(ta) * Math.sin(tilt));
    const mid: V3 = [x + dir.x * len, 0.05 + dir.y * len, z + dir.z * len];
    const tip: V3 = [x + dir.x * (len + 0.2), 0.05 + dir.y * (len + 0.2), z + dir.z * (len + 0.2)];
    const r = 0.035 + rnd() * 0.02;
    b.rod(base, mid, r, k.wood, 6);
    b.oriented(cylGeo(0, r, 0.2, 6), k.wood, new THREE.Vector3(...mid), new THREE.Vector3(...tip));
  }
  for (let i = 0; i < 9; i++) {
    const a = rnd() * TAU;
    const d = 0.2 + rnd() * 0.65;
    const x = Math.cos(a) * d;
    const z = Math.sin(a) * d;
    const h = 0.4 + rnd() * 0.45;
    const bend: V3 = [x + (rnd() - 0.5) * 0.15, h, z + (rnd() - 0.5) * 0.15];
    b.rod([x, 0.02, z], bend, 0.013, k.rust, 4);
    b.rod(bend, [bend[0] + (rnd() - 0.5) * 0.25, h + 0.15 + rnd() * 0.15, bend[2] + (rnd() - 0.5) * 0.25], 0.013, k.rust, 4);
  }
  for (let i = 0; i < 4; i++) b.add(torusGeo(0.2, 0.008, 3, 10), k.rust, -0.7 + i * 0.46, 0.2, 0.72, 0.2, Math.PI / 2 + (i % 2) * 0.3, 0);
  return () => NOOP;
}

// ---------------------------------------------------------------- RADIO TOWER
function buildRadioTower(b: PB): Rig {
  const k = kit();
  const red = paint(0xb03a26);
  const white = paint(0xdedad0);
  b.cblk(1.5, 0.25, 1.5, k.conc, 0, 0, 0, 0.05);
  const R = 0.45;
  const ang = [Math.PI / 2, Math.PI / 2 + TAU / 3, Math.PI / 2 + (2 * TAU) / 3];
  const P = (i: number, y: number): V3 => [Math.cos(ang[i % 3]) * R, y, Math.sin(ang[i % 3]) * R];
  const y0 = 0.25;
  const secH = 2.45;
  const n = 10;
  const yTop = y0 + secH * n;
  for (let s = 0; s < n; s++) {
    const m = s % 2 ? white : red;
    const ya = y0 + s * secH;
    const yb = ya + secH;
    for (let i = 0; i < 3; i++) {
      b.rod(P(i, ya), P(i, yb), 0.035, m, 6, true);
      b.rod(P(i, yb), P(i + 1, yb), 0.016, m, 4, true);
      b.rod(P(i, ya), P(i + 1, ya + secH / 2), 0.013, m, 4, true);
      b.rod(P(i + 1, ya + secH / 2), P(i, yb), 0.013, m, 4, true);
    }
  }
  b.cyl(0.55, 0.05, k.dark, 0, yTop, 0, 'y', 3);
  b.rod([0, yTop, 0], [0, yTop + 3.0, 0], 0.025, k.steel, 5);
  for (const [y, w] of [[yTop - 1.5, 1.8], [yTop - 3.5, 1.4]] as [number, number][]) {
    b.rod([-w / 2, y, 0], [w / 2, y, 0], 0.02, k.steel, 4);
    b.rod([0, y, -w / 2], [0, y, w / 2], 0.02, k.steel, 4);
  }
  for (let i = 0; i < 3; i++) {
    const a = ang[i] + Math.PI / 3;
    b.at(Math.cos(a) * 0.4, yTop - 5.0, Math.sin(a) * 0.4, -a + Math.PI / 2, () => {
      b.box(0.3, 1.2, 0.1, k.white, 0, 0, 0.05);
    });
  }
  b.at(0, 18.5, 0.45, 0, () => {
    b.rod([0, 0, -0.1], [0, 0, 0.2], 0.04, k.dark, 6);
    b.add(sphereGeo(0.6, 12, 4, TAU, 0.8), k.white, 0, 0, 0.45, -Math.PI / 2, 0, 0, 1, 0.45, 1);
  });
  b.mesh('beacon', sphereGeo(0.16, 10, 8), k.eRed, 0, yTop + 0.2, 0);
  b.add(torusGeo(0.18, 0.012, 3, 10), k.dark, 0, yTop + 0.2, 0, Math.PI / 2, 0, 0);
  b.mesh('beacon', sphereGeo(0.1, 8, 6), k.eRed, 0.0, y0 + secH * 5, R + 0.08);
  // guy wires to 3 anchors
  const aa = [-Math.PI / 2, -Math.PI / 2 + TAU / 3, -Math.PI / 2 + (2 * TAU) / 3];
  for (let i = 0; i < 3; i++) {
    const ax = Math.cos(aa[i]) * 1.85;
    const az = Math.sin(aa[i]) * 1.85;
    b.cblk(0.35, 0.25, 0.35, k.conc, ax, 0, az, 0.03);
    b.cyl(0.04, 0.12, k.steel, ax, 0.31, az, 'y', 6);
    for (const h of [8, 16, 23.5]) b.rod([ax, 0.36, az], [Math.cos(aa[i]) * R * 0.8, h, Math.sin(aa[i]) * R * 0.8], 0.008, k.steel, 3, true);
  }
  // equipment cabinet
  b.cblk(1.1, 0.12, 0.7, k.conc, 0, 0, 1.35, 0.03);
  housing(b, 0.9, 1.5, 0.55, k.grey, 0, 0.12, 1.35);
  vent(b, -0.2, 0.55, 1.63, 0, 0.35, 0.3);
  panelLamp(b, 0.25, 1.3, 1.63, 0);
  stackLight(b, 0.3, 1.62, 1.25);
  b.beam([0, 1.45, 1.08], [0, 3.0, 0.42], 0.15, k.dark, 0.03);
  cableRun(b, [[-0.1, 1.62, 1.2], [-0.1, 3.0, 0.45], [-0.05, 18.0, 0.42]], 0.03, 0.05);
  return (c) => {
    const bea = c.mat('beacon');
    return (dt, running, t) => {
      if (bea) bea.emissiveIntensity = running ? ((t % 1.6) < 0.25 ? 4 : 0.15) : 0.05;
    };
  };
}

// ---------------------------------------------------------------- GREENHOUSE
function plant(b: PB, rnd: () => number, x: number, y: number, z: number, s: number, fruit: THREE.Material | null): void {
  const k = kit();
  b.add(new THREE.IcosahedronGeometry(0.2 * s, 0), k.leaves, x, y + 0.14 * s, z, rnd() * 3, rnd() * 3, rnd() * 3, 1.1, 0.8, 1.1);
  b.add(new THREE.IcosahedronGeometry(0.14 * s, 0), k.leaves, x + (rnd() - 0.5) * 0.15, y + 0.3 * s, z + (rnd() - 0.5) * 0.15, rnd() * 3, rnd() * 3, 0);
  b.rod([x, y, z], [x, y + 0.35 * s, z], 0.012, k.leaves, 3);
  if (fruit) for (let i = 0; i < 3; i++) {
    const a = rnd() * TAU;
    b.add(sphereGeo(0.045 * s, 6, 4), fruit, x + Math.cos(a) * 0.17 * s, y + (0.1 + rnd() * 0.15) * s, z + Math.sin(a) * 0.17 * s);
  }
}

function buildGreenhouse(b: PB): Rig {
  const k = kit();
  const frame = paint(0xcfd2cc);
  const rnd = rng(91);
  slab(b, 3, 3, false);
  const hx = 2.3;
  const hz = 2.3;
  const y0 = S + 0.5;
  const ye = S + 2.55;
  const yr = ye + 1.25;
  // brick knee wall
  b.blk(hx * 2 + 0.2, 0.5, 0.2, k.brick, 0, S, hz);
  b.blk(hx * 2 + 0.2, 0.5, 0.2, k.brick, 0, S, -hz);
  b.blk(0.2, 0.5, hz * 2, k.brick, hx, S, 0);
  b.blk(0.2, 0.5, hz * 2, k.brick, -hx, S, 0);
  b.blk(hx * 2 + 0.26, 0.04, 0.26, k.conc, 0, y0, hz);
  b.blk(hx * 2 + 0.26, 0.04, 0.26, k.conc, 0, y0, -hz);
  b.blk(0.26, 0.04, hz * 2, k.conc, hx, y0, 0);
  b.blk(0.26, 0.04, hz * 2, k.conc, -hx, y0, 0);
  // frame
  const zs = [-hz, -hz / 2, 0, hz / 2, hz];
  for (const z of zs) {
    for (const sx of [-1, 1]) {
      b.blk(0.07, ye - y0, 0.07, frame, sx * hx, y0, z);
      b.beam([sx * hx, ye, z], [0, yr, z], 0.07, frame);
    }
  }
  for (const sx of [-1, 1]) {
    b.box(0.08, 0.08, hz * 2 + 0.08, frame, sx * hx, ye, 0);
    b.box(0.05, 0.05, hz * 2, frame, sx * hx, y0 + 1.0, 0);
    b.box(0.05, 0.05, hz * 2, frame, sx * hx / 2, (ye + yr) / 2, 0);
    for (const z of [-hz, hz]) {
      b.blk(0.06, ye - y0 + (yr - ye) * 0.5, 0.06, frame, sx * hx / 2, y0, z);
    }
  }
  b.box(0.1, 0.1, hz * 2 + 0.1, frame, 0, yr, 0);
  for (const z of [-hz, hz]) b.box(hx * 2, 0.06, 0.06, frame, 0, ye, z);
  // glass
  const slope = Math.hypot(hx, yr - ye);
  const ang = Math.atan2(yr - ye, hx);
  for (const sx of [-1, 1]) {
    b.box(0.015, ye - y0, hz * 2, k.glass, sx * hx, (ye + y0) / 2, 0);
    b.box(slope, 0.015, hz * 2, k.glass, sx * hx / 2, (ye + yr) / 2, 0, 0, 0, -sx * ang);
  }
  for (const z of [-hz, hz]) {
    b.box(hx * 2, ye - y0, 0.015, k.glass, 0, (ye + y0) / 2, z);
    b.add(extrudeGeo([[-hx, 0], [hx, 0], [0, yr - ye]], 0.015), k.glass, 0, ye, z);
  }
  // door on front gable
  b.blk(0.9, 2.0, 0.04, frame, 1.5, y0 - 0.5 + 0.02, hz + 0.05);
  b.blk(0.8, 1.85, 0.03, k.glassD, 1.5, y0 - 0.45, hz + 0.08);
  b.cyl(0.02, 0.12, k.chrome, 1.15, y0 + 0.5, hz + 0.1, 'y', 6);
  // hydroponic troughs + plants
  const fruitM = tint('plasticWhite', 0xc0392b);
  for (const x of [-1.45, 0, 1.45]) {
    for (const z of [-1.6, 0, 1.6]) for (const sx of [-1, 1]) b.blk(0.05, 0.75, 0.05, k.steel, x + sx * 0.25, S, z);
    b.blk(0.62, 0.22, 3.9, k.white, x, S + 0.75, 0);
    b.blk(0.52, 0.02, 3.8, M('soil'), x, S + 0.96, 0);
    b.rod([x + 0.33, S + 0.85, -1.95], [x + 0.33, S + 0.85, 1.95], 0.025, k.steel, 5);
    for (let i = 0; i < 8; i++) plant(b, rnd, x + (rnd() - 0.5) * 0.12, S + 0.97, -1.7 + i * 0.485, 0.9 + rnd() * 0.4, (i + Math.round(x)) % 3 === 0 ? fruitM : null);
    b.box(0.26, 0.06, 3.6, k.dark, x, ye - 0.3, 0);
    for (const z of [-1.4, 1.4]) b.rod([x, ye - 0.27, z], [x, ye, z], 0.01, k.dark, 3);
  }
  b.mesh('growLight', mergedGeo((p, m) => {
    for (const x of [-1.45, 0, 1.45]) p.box(0.18, 0.02, 3.5, m, x, ye - 0.34, 0);
  }), growMat());
  // exhaust fan on back gable
  b.at(0, ye + 0.45, -hz - 0.03, Math.PI, () => {
    b.box(0.8, 0.8, 0.06, frame, 0, 0, -0.03);
    fanGuard(b, 0.34);
  });
  const fan = b.sub('fan', 0, ye + 0.45, -hz - 0.02, 0, Math.PI, 0);
  fanBlades(fan, 0.32, 5, k.steel, k.dark);
  // water tank + pipes
  b.cyl(0.33, 1.6, tint('plasticWhite', 0x3a6fa0), -2.62, S + 0.8, -2.62, 'y', 12);
  b.cyl(0.34, 0.06, k.dark, -2.62, S + 1.6, -2.62, 'y', 12);
  b.pipe([[-2.62, S + 0.2, -2.29], [-2.62, S + 0.2, -1.9], [-2.0, S + 0.85, -1.9]], 0.035, k.steel, 6);
  port(b, 0, -3, Math.PI, 3 - hz - 0.1, 'in', frame);
  port(b, 0, 3, 0, 3 - hz - 0.1, 'out', frame);
  stackLight(b, hx - 0.15, ye + 0.04, hz - 0.1);
  panelLamp(b, -1.4, S + 0.3, hz + 0.1, 0);
  return (c) => {
    const f = c.get('fan');
    const g = c.mat('growLight');
    const sp = smoother(1.0);
    return (dt, running, t) => {
      const v = sp(dt, running ? 1 : 0);
      f.rotation.z += dt * v * 10;
      if (g) g.emissiveIntensity = 0.05 + v * (2.0 + Math.sin(t * 0.7) * 0.1);
    };
  };
}

// ---------------------------------------------------------------- GRID UPLINK
function buildUplink(b: PB): Rig {
  const k = kit();
  const tf = paint(0x6f7d70);
  const grey = paint(0x5a5f63);
  slab(b, 3, 3, false);
  b.blk(5.6, 0.03, 5.6, tint('rock', 0x9a968c), 0, S - 0.01, 0);
  // perimeter fence
  const F = 2.7;
  const fenceSeg = (x0: number, z0: number, x1: number, z1: number): void => {
    const len = Math.hypot(x1 - x0, z1 - z0);
    const n = Math.max(1, Math.round(len / 1.4));
    for (let i = 0; i <= n; i++) {
      const x = x0 + ((x1 - x0) * i) / n;
      const z = z0 + ((z1 - z0) * i) / n;
      b.rod([x, S, z], [x, S + 2.1, z], 0.035, k.steel, 6);
      const ox = Math.abs(x) > Math.abs(z) ? Math.sign(x) * 0.25 : 0;
      const oz = Math.abs(z) >= Math.abs(x) ? Math.sign(z) * 0.25 : 0;
      b.rod([x, S + 2.1, z], [x + ox, S + 2.4, z + oz], 0.02, k.steel, 4);
    }
    const cx = (x0 + x1) / 2;
    const cz = (z0 + z1) / 2;
    const ry = -Math.atan2(z1 - z0, x1 - x0);
    b.box(len, 1.9, 0.02, k.grating, cx, S + 1.05, cz, 0, ry, 0);
    b.rod([x0, S + 2.05, z0], [x1, S + 2.05, z1], 0.02, k.steel, 4);
    const nx = Math.abs(cx) > Math.abs(cz) ? Math.sign(cx) : 0;
    const nz = Math.abs(cz) >= Math.abs(cx) ? Math.sign(cz) : 0;
    for (let j = 1; j <= 3; j++) {
      const o = j * 0.08;
      b.rod([x0 + nx * o, S + 2.1 + j * 0.08, z0 + nz * o], [x1 + nx * o, S + 2.1 + j * 0.08, z1 + nz * o], 0.006, k.steel, 3, true);
    }
  };
  fenceSeg(-F, -F, F, -F);
  fenceSeg(F, -F, F, F);
  fenceSeg(-F, -F, -F, F);
  fenceSeg(-F, F, -1.0, F);
  fenceSeg(1.0, F, F, F);
  for (const sx of [-1, 1]) b.box(0.9, 1.9, 0.03, k.grating, sx * 0.72, S + 1.05, F - 0.38, 0, -sx * 1.1, 0);
  sign(b, -1.9, S + 1.4, F + 0.03, 0, 0.45, 0.32);
  sign(b, 1.9, S + 1.4, F + 0.03, 0, 0.45, 0.32);
  // transformers
  for (const sx of [-1, 1]) {
    const x = sx * 1.35;
    const z = -0.15;
    b.cblk(1.5, 0.2, 1.2, k.conc, x, S, z, 0.03);
    b.cblk(1.2, 1.5, 0.95, tf, x, S + 0.2, z, 0.05);
    b.blk(1.26, 0.06, 1.0, k.dark, x, S + 1.7, z);
    for (let i = 0; i < 8; i++) {
      b.blk(0.32, 1.15, 0.03, tf, x + sx * 0.76, S + 0.35, z - 0.42 + i * 0.12);
    }
    b.box(0.06, 0.06, 1.0, k.dark, x + sx * 0.76, S + 1.45, z);
    b.box(0.06, 0.06, 1.0, k.dark, x + sx * 0.76, S + 0.45, z);
    b.cyl(0.2, 0.95, tf, x, S + 2.25, z - 0.32, 'x', 12);
    for (const ox of [-0.3, 0.3]) b.blk(0.06, 0.45, 0.06, k.dark, x + ox, S + 1.76, z - 0.32);
    for (let i = -1; i <= 1; i++) insulator(b, x + i * 0.35, S + 1.76, z + 0.2, 5, 0.08);
    for (let i = -1; i <= 1; i++) insulator(b, x + i * 0.25, S + 1.76, z - 0.05, 2, 0.05);
    sign(b, x, S + 1.0, z + 0.48, 0, 0.36, 0.26);
    b.pipe([[x - sx * 0.2, S + 1.75, z - 0.32], [x - sx * 0.2, S + 2.1, z - 0.32]], 0.03, k.steel, 6, false);
  }
  // pylon at back
  const pz = -2.15;
  b.push(0, 0, pz);
  lattice4(b, 0.62, 0.24, S, 10.5, 4, 0.11, 0.045, k.steel, k.steel);
  b.pop();
  return buildUplinkRest(b, pz, grey);
}

function buildUplinkRest(b: PB, pz: number, grey: THREE.Material): Rig {
  const k = kit();
  for (const [y, w] of [[9.1, 3.6], [10.4, 2.6]] as [number, number][]) {
    b.box(w, 0.12, 0.12, k.steel, 0, y, pz);
    for (const sx of [-1, 1]) {
      b.beam([sx * w / 2, y, pz], [sx * 0.3, y - 0.8, pz], 0.06, k.steel);
      insulator(b, sx * (w / 2 - 0.1), y - 0.8, pz, 5, 0.07);
    }
  }
  for (const sx of [-1, 1]) {
    b.cable([sx * 1.7, 8.1, pz], [sx * 1.35 + sx * 0.35, S + 2.5, 0.05], 0.4, 0.018, k.dark, 8);
    b.cable([sx * 1.2, 9.4, pz], [sx * 1.35, S + 2.5, 0.05], 0.4, 0.018, k.dark, 8);
    b.cable([sx * 1.7, 8.1, pz], [sx * 1.7, 7.8, -3.0], 0.15, 0.018, k.dark, 4);
  }
  b.add(sphereGeo(0.3, 10, 4, TAU, 0.9), k.white, 0, 11.0, pz + 0.1, -Math.PI / 2 + 0.6, 0, 0, 1, 0.45, 1);
  b.rod([0, 10.5, pz], [0, 11.0, pz], 0.04, k.dark, 6);
  b.mesh('beacon', sphereGeo(0.1, 8, 6), k.eRed, 0, 10.62, pz);
  // central sync coil
  const cz = 0.95;
  b.cblk(0.9, 0.35, 0.9, k.conc, 0, S, cz, 0.04);
  insulator(b, 0, S + 0.35, cz, 9, 0.11);
  b.mesh('coil', mergedGeo((p, m) => {
    p.add(torusGeo(0.42, 0.1, 8, 20), m, 0, S + 1.75, cz, Math.PI / 2, 0, 0);
    p.add(torusGeo(0.26, 0.05, 6, 16), m, 0, S + 1.35, cz, Math.PI / 2, 0, 0);
  }), k.eCool);
  b.cyl(0.1, 0.3, k.chrome, 0, S + 1.85, cz, 'y', 8);
  for (const sx of [-1, 1]) b.rod([0, S + 1.9, cz], [sx * 1.35, S + 2.42, 0.05], 0.02, k.steel, 4);
  // kiosks
  housing(b, 1.1, 2.0, 0.8, grey, 2.0, S, 1.95, Math.PI);
  b.box(0.7, 1.7, 0.02, k.dark, 2.0, S + 1.0, 1.54);
  vent(b, 2.0, S + 1.6, 1.54, Math.PI, 0.5, 0.25);
  b.cblk(0.5, 0.5, 0.35, k.grey, 2.62, S + 0.6, 1.95, 0.03);
  stackLight(b, 2.2, S + 2.0, 2.05);
  panelLamp(b, 1.7, S + 1.7, 1.54, Math.PI);
  housing(b, 1.0, 1.4, 0.6, grey, -2.0, S, 1.95, Math.PI);
  for (let i = 0; i < 3; i++) {
    b.box(0.08, 0.25, 0.05, k.black, -2.25 + i * 0.25, S + 0.9, 1.62);
    b.box(0.06, 0.06, 0.12, k.eRed, -2.25 + i * 0.25, S + 1.05, 1.6);
  }
  panelLamp(b, -1.7, S + 1.2, 1.64, Math.PI);
  cableRun(b, [[-2.0, S + 1.4, 1.95], [-1.0, S + 0.05, 1.2], [-0.45, S + 0.2, cz]], 0.04, 0.05);
  cableRun(b, [[2.0, S + 2.0, 1.95], [1.0, S + 0.05, 1.2], [0.45, S + 0.2, cz]], 0.04, 0.05);
  return (c) => {
    const coil = c.mat('coil');
    const bea = c.mat('beacon');
    const sp = smoother(0.8);
    return (dt, running, t) => {
      const v = sp(dt, running ? 1 : 0);
      if (coil) coil.emissiveIntensity = 0.1 + v * (1.6 + Math.sin(t * 4.0) * 0.6 + (Math.random() < 0.03 ? 1.5 : 0));
      if (bea) bea.emissiveIntensity = (t % 2) < 0.3 ? 3.5 : 0.1;
    };
  };
}

// ---------------------------------------------------------------------------
// Registry / public API
// ---------------------------------------------------------------------------
const DEFS: Record<MachineModelId, { fp: [number, number]; build: (b: PB) => Rig }> = {
  hub: { fp: [3, 3], build: buildHub },
  miner: { fp: [3, 3], build: (b) => buildMiner(b, false) },
  miner2: { fp: [3, 3], build: (b) => buildMiner(b, true) },
  smelter: { fp: [3, 3], build: buildSmelter },
  foundry: { fp: [3, 3], build: buildFoundry },
  constructor: { fp: [3, 3], build: buildConstructor },
  assembler: { fp: [3, 3], build: buildAssembler },
  lab: { fp: [3, 3], build: buildLab },
  storage: { fp: [1, 1], build: buildStorage },
  splitter: { fp: [1, 1], build: (b) => buildJunction(b, false) },
  merger: { fp: [1, 1], build: (b) => buildJunction(b, true) },
  belt: { fp: [1, 1], build: (b) => buildBelt(b, false) },
  belt2: { fp: [1, 1], build: (b) => buildBelt(b, true) },
  coal_gen: { fp: [3, 3], build: buildCoalGen },
  biomass: { fp: [2, 2], build: buildBiomass },
  solar: { fp: [2, 2], build: buildSolar },
  wind_turbine: { fp: [1, 1], build: buildWindTurbine },
  battery: { fp: [2, 2], build: buildBattery },
  power_pole: { fp: [1, 1], build: buildPowerPole },
  floodlight: { fp: [1, 1], build: buildFloodlight },
  turret: { fp: [1, 1], build: buildTurret },
  electric_fence: { fp: [1, 1], build: buildFence },
  spike_trap: { fp: [1, 1], build: buildSpikes },
  radio_tower: { fp: [2, 2], build: buildRadioTower },
  greenhouse: { fp: [3, 3], build: buildGreenhouse },
  uplink: { fp: [3, 3], build: buildUplink },
};

export const MACHINE_MODEL_IDS = Object.keys(DEFS) as MachineModelId[];

/** Footprint (W, D) in 2 m tiles without building the model. */
export function machineFootprint(id: MachineModelId): [number, number] {
  const f = DEFS[id].fp;
  return [f[0], f[1]];
}

const templates = new Map<MachineModelId, Template>();
/** Names whose meshes get ONE per-instance material clone (shared by same-named meshes in that instance). */
const PER_INSTANCE = new Set(['glow', 'lamp', 'beacon', 'wires', 'vial', 'vial2', 'coil', 'growLight', 'screen']);

function template(id: MachineModelId): Template {
  let t = templates.get(id);
  if (!t) {
    const d = DEFS[id];
    const b = new PB(id);
    const rig = d.build(b);
    const root = b.build();
    root.name = id;
    t = { root, fp: d.fp, rig };
    templates.set(id, t);
  }
  return t;
}

/** Pre-build templates (e.g. during a loading screen). */
export function warmMachineModels(ids: MachineModelId[] = MACHINE_MODEL_IDS): void {
  for (const id of ids) template(id);
}

export function buildMachineModel(id: MachineModelId): THREE.Group {
  const t = template(id);
  const g = t.root.clone(true);
  const lights: THREE.Mesh[] = [];
  const charge: THREE.Mesh[] = [];
  const shared = new Map<string, THREE.MeshStandardMaterial>();
  g.traverse((o) => {
    const m = o as THREE.Mesh;
    if (!m.isMesh) return;
    if (m.name === 'statusLight' || m.name === 'chargeSeg') {
      m.material = (m.material as THREE.MeshStandardMaterial).clone();
      (m.name === 'statusLight' ? lights : charge).push(m);
    } else if (PER_INSTANCE.has(m.name)) {
      let c = shared.get(m.name);
      if (!c) {
        c = (m.material as THREE.MeshStandardMaterial).clone();
        shared.set(m.name, c);
      }
      m.material = c;
    }
  });
  const ctx: RigCtx = {
    root: g,
    get: (n) => {
      const o = g.getObjectByName(n);
      if (!o) console.warn(`machines: '${id}' has no part '${n}'`);
      return o ?? new THREE.Object3D();
    },
    mat: (n) => shared.get(n) ?? null,
  };
  const ud = g.userData;
  ud.machineId = id;
  ud.statusLights = lights;
  ud.footprint = [t.fp[0], t.fp[1]] as [number, number];
  ud.animate = t.rig(ctx);
  switch (id) {
    case 'power_pole':
      ud.cableAnchor = g.getObjectByName('cableAnchor');
      ud.cableAnchors = ['cableAnchorL', 'cableAnchor', 'cableAnchorR'].map((n) => g.getObjectByName(n));
      break;
    case 'floodlight':
      ud.lightAnchor = g.getObjectByName('lightAnchor');
      break;
    case 'turret':
      ud.muzzle = g.getObjectByName('muzzle');
      ud.head = g.getObjectByName('head');
      ud.barrel = g.getObjectByName('barrel');
      break;
    case 'belt':
    case 'belt2':
      ud.beltSurface = g.getObjectByName('beltSurface');
      break;
    case 'battery': {
      charge.sort((a, b2) => a.position.x - b2.position.x);
      const setCharge = (f: number): void => {
        const lit = Math.round(Math.max(0, Math.min(1, f)) * charge.length);
        charge.forEach((m, i) => {
          const mat = m.material as THREE.MeshStandardMaterial;
          mat.emissiveIntensity = i < lit ? 1.6 : 0.04;
        });
      };
      ud.chargeSegments = charge;
      ud.setCharge = setCharge;
      setCharge(0.6);
      break;
    }
    default:
      break;
  }
  return g;
}
