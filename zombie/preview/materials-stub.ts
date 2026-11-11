// Preview-only stand-in for src/render/materials.ts (procedural canvas textures).
import * as THREE from 'three';

export type MaterialName = 'grass'|'dirt'|'rock'|'forestFloor'|'mud'|'sand'|'asphalt'|'sidewalk'|'concrete'|'brick'|'brickRed'|'siding'|'plaster'|'woodFloor'|'tile'|'roofShingle'|'roofMetal'|'wood'|'woodDark'|'bark'|'birchBark'|'metal'|'metalRust'|'metalDark'|'steelBrushed'|'fabric'|'factoryPanel'|'hazard'|'grating'|'belt'|'soil'|'glass'|'glassDirty'|'rubber'|'plasticBlack'|'plasticWhite'|'chrome'|'emissiveWarm'|'emissiveCool'|'emissiveRed'|'emissiveGreen'|'factoryOrange'|'factoryGrey'|'sidingWhite'|'sidingBlue'|'sidingGreen'|'sidingYellow'|'pineNeedles'|'leaves'|'skinPale'|'clothDenim'|'clothDark';

type Def = { c: number; r: number; m?: number; tex?: 'grain' | 'noise' | 'stripes' | 'bark' | 'birch' | 'needles' | 'leaves' | 'hazard'; e?: number; t?: number };
const DEFS: Record<MaterialName, Def> = {
  grass: { c: 0x4d5a2e, r: 0.95, tex: 'noise' }, dirt: { c: 0x5a4632, r: 1, tex: 'noise' }, rock: { c: 0x7a776f, r: 0.9, tex: 'noise' },
  forestFloor: { c: 0x3e3324, r: 1, tex: 'noise' }, mud: { c: 0x3b3022, r: 0.8, tex: 'noise' }, sand: { c: 0xa89a78, r: 1, tex: 'noise' },
  asphalt: { c: 0x333333, r: 0.95, tex: 'noise' }, sidewalk: { c: 0x8a8780, r: 0.9, tex: 'noise' }, concrete: { c: 0x8c8a84, r: 0.92, tex: 'noise' },
  brick: { c: 0x7a6a5a, r: 0.9, tex: 'noise' }, brickRed: { c: 0x7a3a2a, r: 0.9, tex: 'noise' }, siding: { c: 0x9a9480, r: 0.8, tex: 'stripes' },
  plaster: { c: 0xb8b0a0, r: 0.9, tex: 'noise' }, woodFloor: { c: 0x6a4a2a, r: 0.7, tex: 'grain' }, tile: { c: 0xc8c8c0, r: 0.4, tex: 'noise' },
  roofShingle: { c: 0x3a3836, r: 0.95, tex: 'noise' }, roofMetal: { c: 0x6a6e70, r: 0.5, m: 0.7, tex: 'stripes' },
  wood: { c: 0x8a6a45, r: 0.75, tex: 'grain' }, woodDark: { c: 0x4a3420, r: 0.75, tex: 'grain' }, bark: { c: 0x4a3a2c, r: 1, tex: 'bark' },
  birchBark: { c: 0xd8d4c8, r: 0.9, tex: 'birch' }, metal: { c: 0x8a8a88, r: 0.45, m: 0.9, tex: 'noise' },
  metalRust: { c: 0x7a4a2a, r: 0.85, m: 0.4, tex: 'noise' }, metalDark: { c: 0x3a3a3c, r: 0.5, m: 0.8, tex: 'noise' },
  steelBrushed: { c: 0xb0b0b0, r: 0.3, m: 1, tex: 'grain' }, fabric: { c: 0x6a6258, r: 1, tex: 'noise' },
  factoryPanel: { c: 0x7a7c78, r: 0.6, m: 0.5, tex: 'stripes' }, hazard: { c: 0xffffff, r: 0.7, tex: 'hazard' },
  grating: { c: 0x555555, r: 0.6, m: 0.8, tex: 'stripes' }, belt: { c: 0x222222, r: 0.9, tex: 'stripes' }, soil: { c: 0x3a2a1a, r: 1, tex: 'noise' },
  glass: { c: 0x9ab0b8, r: 0.05, m: 0.1, t: 0.35 }, glassDirty: { c: 0x6a7470, r: 0.3, m: 0.1, t: 0.6 },
  rubber: { c: 0x1e1e1e, r: 0.9, tex: 'noise' }, plasticBlack: { c: 0x1a1a1a, r: 0.6 }, plasticWhite: { c: 0xd8d8d0, r: 0.5 },
  chrome: { c: 0xdddddd, r: 0.12, m: 1 }, emissiveWarm: { c: 0xffd9a0, r: 0.5, e: 0xffb060 }, emissiveCool: { c: 0xd0e8ff, r: 0.5, e: 0x9fd0ff },
  emissiveRed: { c: 0xff3020, r: 0.5, e: 0xff2010 }, emissiveGreen: { c: 0x40ff60, r: 0.5, e: 0x20ff40 },
  factoryOrange: { c: 0xc8641e, r: 0.6, m: 0.3, tex: 'noise' }, factoryGrey: { c: 0x6a6c6a, r: 0.6, m: 0.4, tex: 'noise' },
  sidingWhite: { c: 0xc8c4b8, r: 0.8, tex: 'stripes' }, sidingBlue: { c: 0x5a6e80, r: 0.8, tex: 'stripes' },
  sidingGreen: { c: 0x5a6e50, r: 0.8, tex: 'stripes' }, sidingYellow: { c: 0xb8a060, r: 0.8, tex: 'stripes' },
  pineNeedles: { c: 0xffffff, r: 0.9, tex: 'needles' }, leaves: { c: 0xffffff, r: 0.85, tex: 'leaves' },
  skinPale: { c: 0xc8a890, r: 0.7 }, clothDenim: { c: 0x3a4a60, r: 1, tex: 'noise' }, clothDark: { c: 0x2a2a2a, r: 1, tex: 'noise' },
};

function rnd(seed: number) { let s = seed; return () => (s = (s * 16807) % 2147483647) / 2147483647; }

function makeTex(kind: NonNullable<Def['tex']>): THREE.Texture {
  const S = kind === 'needles' || kind === 'leaves' ? 256 : 128;
  const cv = document.createElement('canvas'); cv.width = cv.height = S;
  const g = cv.getContext('2d')!;
  const r = rnd(1234 + kind.length * 77);
  if (kind === 'needles') {
    g.clearRect(0, 0, S, S);
    // a branch spray: central twig from bottom to top with needle tufts
    for (let k = 0; k < 7; k++) {
      const x0 = S * (0.2 + 0.6 * r());
      g.strokeStyle = '#3a2a1a'; g.lineWidth = 2;
      g.beginPath(); g.moveTo(S / 2, S); g.lineTo(x0, 0); g.stroke();
      for (let i = 0; i < 260; i++) {
        const t = r(); const x = S / 2 + (x0 - S / 2) * t, y = S * (1 - t);
        const a = (r() - 0.5) * 2.6, l = 6 + r() * 14;
        const gr = 60 + r() * 60;
        g.strokeStyle = `rgb(${gr * 0.45},${gr},${gr * 0.4})`; g.lineWidth = 1.5;
        g.beginPath(); g.moveTo(x, y); g.lineTo(x + Math.sin(a) * l, y - Math.cos(a) * l * 0.6); g.stroke();
      }
    }
  } else if (kind === 'leaves') {
    g.clearRect(0, 0, S, S);
    for (let i = 0; i < 160; i++) {
      const x = S * (0.1 + 0.8 * r()), y = S * (0.1 + 0.8 * r());
      const gr = 80 + r() * 90;
      g.fillStyle = `rgb(${gr * 0.6},${gr},${gr * 0.35})`;
      g.beginPath(); g.ellipse(x, y, 5 + r() * 6, 3 + r() * 3, r() * 3, 0, Math.PI * 2); g.fill();
    }
  } else {
    g.fillStyle = '#ffffff'; g.fillRect(0, 0, S, S);
    const img = g.getImageData(0, 0, S, S);
    for (let y = 0; y < S; y++) for (let x = 0; x < S; x++) {
      let v = 0.82 + r() * 0.18;
      if (kind === 'grain') v *= 0.85 + 0.15 * Math.sin(y * 0.6 + Math.sin(x * 0.05) * 6);
      if (kind === 'stripes') v *= y % 16 < 2 ? 0.6 : 1;
      if (kind === 'bark') v *= 0.7 + 0.3 * Math.abs(Math.sin(x * 0.35 + Math.sin(y * 0.07) * 3));
      if (kind === 'birch') v *= (Math.sin(y * 0.3) > 0.93 && Math.sin(x * 0.11 + y) > 0) ? 0.25 : 1;
      if (kind === 'hazard') { const s = ((x + y) % 64) < 32; img.data[(y * S + x) * 4] = s ? 230 : 30; img.data[(y * S + x) * 4 + 1] = s ? 180 : 30; img.data[(y * S + x) * 4 + 2] = s ? 20 : 30; continue; }
      const i = (y * S + x) * 4; img.data[i] = img.data[i + 1] = img.data[i + 2] = v * 255;
    }
    g.putImageData(img, 0, 0);
  }
  const t = new THREE.CanvasTexture(cv);
  t.wrapS = t.wrapT = THREE.RepeatWrapping;
  t.colorSpace = THREE.SRGBColorSpace;
  t.anisotropy = 4;
  return t;
}

const cache = new Map<string, THREE.MeshStandardMaterial>();
export function getMaterial(name: MaterialName): THREE.MeshStandardMaterial {
  let m = cache.get(name);
  if (m) return m;
  const d = DEFS[name];
  m = new THREE.MeshStandardMaterial({ color: d.c, roughness: d.r, metalness: d.m ?? 0 });
  if (d.tex) m.map = makeTex(d.tex);
  if (d.e) { m.emissive = new THREE.Color(d.e); m.emissiveIntensity = 1.5; }
  if (d.t) { m.transparent = true; m.opacity = d.t; }
  if (name === 'pineNeedles' || name === 'leaves') { m.alphaTest = 0.4; m.side = THREE.DoubleSide; }
  m.name = name;
  cache.set(name, m);
  return m;
}
export function getCarPaint(hex: number): THREE.MeshStandardMaterial {
  const k = 'paint' + hex; let m = cache.get(k);
  if (!m) { m = new THREE.MeshStandardMaterial({ color: hex, roughness: 0.55, metalness: 0.3 }); cache.set(k, m); }
  return m;
}
export function getTintedMaterial(base: MaterialName, hex: number): THREE.MeshStandardMaterial {
  const k = base + '#' + hex; let m = cache.get(k);
  if (!m) { m = getMaterial(base).clone(); m.color.setHex(hex); cache.set(k, m); }
  return m;
}
