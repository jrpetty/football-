// Detailed weapon models. Guns point along +Z with the grip at the origin;
// melee weapons extend along +Y from the hand. Mods show on the model.
import { Builder } from './kit.js'

const cache = new Map()
export function weaponModel(id, mods = []) {
  const key = id + ':' + mods.join(',')
  if (cache.has(key)) return cache.get(key).clone()
  const b = new Builder()
  const fn = W[id] || W.none
  fn(b, mods)
  const g = b.build({ receive: false })
  g.traverse((o) => {
    if (o.isMesh) o.castShadow = true
  })
  cache.set(key, g)
  return g.clone()
}
export const holdStyle = (id, kind) => (kind === 'gun' ? (['pistol', 'revolver'].includes(id) ? 'pistol' : 'rifle') : id === 'fists' ? 'none' : 'melee')

const STEEL = { mat: 'steel', color: '#8e959b' }
const BLACK = { mat: 'paint', color: '#1e2124' }
const GUNMETAL = { mat: 'metal', color: '#3a3f44' }
const WOOD = { mat: 'wood', color: '#7a4e2c' }
const DWOOD = { mat: 'wood', color: '#5a3820' }
const GRIP = { mat: 'rubber', color: '#1a1a1a' }
const TAPE = { mat: 'cloth', color: '#2a2a2a' }

function gunMods(b, mods, { barrelZ, barrelY = 0.06, railY = 0.1, railZ = 0.15 }) {
  if (mods.includes('suppressor')) {
    b.cyl(0.024, 0.024, 0.2, { ...BLACK, z: barrelZ + 0.1, y: barrelY, rx: Math.PI / 2, seg: 14 })
    for (let i = 0; i < 4; i++) b.cyl(0.025, 0.025, 0.006, { ...GUNMETAL, z: barrelZ + 0.03 + i * 0.045, y: barrelY, rx: Math.PI / 2, seg: 14 })
  }
  if (mods.includes('scope')) {
    b.cyl(0.02, 0.02, 0.22, { ...BLACK, y: railY + 0.045, z: railZ, rx: Math.PI / 2, seg: 14 })
    b.cyl(0.027, 0.022, 0.05, { ...BLACK, y: railY + 0.045, z: railZ + 0.12, rx: Math.PI / 2, seg: 14 })
    b.cyl(0.025, 0.02, 0.04, { ...BLACK, y: railY + 0.045, z: railZ - 0.11, rx: Math.PI / 2, seg: 14 })
    b.cyl(0.023, 0.023, 0.004, { mat: 'glass', color: '#5a8aa8', y: railY + 0.045, z: railZ + 0.147, rx: Math.PI / 2 })
    for (const dz of [-0.05, 0.05]) b.box(0.02, 0.035, 0.02, { ...BLACK, y: railY + 0.015, z: railZ + dz })
    b.cyl(0.012, 0.012, 0.03, { ...BLACK, y: railY + 0.075, z: railZ })
  }
  if (mods.includes('extmag')) b.box(0.028, 0.16, 0.05, { ...BLACK, y: -0.14, z: 0.1, rx: 0.15, r: 0.006 })
  if (mods.includes('reinforced')) for (const dz of [0.05, 0.18]) b.box(0.05, 0.016, 0.02, { ...STEEL, y: barrelY - 0.02, z: dz })
}

const W = {
  none() {},
  fists() {},
  bat(b, mods) {
    b.lathe([[0.016, 0], [0.018, 0.02], [0.016, 0.03], [0.016, 0.24], [0.022, 0.4], [0.031, 0.58], [0.034, 0.74], [0.032, 0.8], [0.02, 0.815], [0.001, 0.82]], { ...WOOD, color: '#a8784a', y: -0.08 })
    b.cyl(0.018, 0.018, 0.16, { ...TAPE, y: 0.02 })
    if (mods.includes('spiked')) for (let i = 0; i < 8; i++) b.cyl(0.003, 0.003, 0.08, { ...STEEL, y: 0.5 + (i % 4) * 0.06, ry: i, rz: Math.PI / 2, x: 0, z: 0 })
  },
  nailbat(b, mods) {
    W.bat(b, mods)
    for (let i = 0; i < 12; i++) {
      const a = i * 2.1
      b.cyl(0.0025, 0.0025, 0.07, { ...STEEL, x: Math.cos(a) * 0.028, y: 0.45 + (i % 6) * 0.05, z: Math.sin(a) * 0.028, rz: Math.cos(a) * Math.PI / 2, rx: Math.sin(a) * Math.PI / 2 })
    }
  },
  pipe(b, mods) {
    b.cyl(0.018, 0.018, 0.72, { ...STEEL, color: '#7a7e80', y: 0.26, seg: 10 })
    b.cyl(0.026, 0.026, 0.06, { ...STEEL, color: '#6a6e70', y: 0.6, seg: 8 })
    b.cyl(0.022, 0.022, 0.05, { ...STEEL, color: '#6a6e70', y: -0.07, seg: 8 })
    b.cyl(0.02, 0.02, 0.14, { ...TAPE, color: '#3a3020', y: 0.02 })
  },
  crowbar(b) {
    b.tube([[0, -0.08, 0], [0, 0.4, 0], [0, 0.5, 0.02], [0, 0.53, 0.07], [0, 0.52, 0.11]], 0.012, { mat: 'paint', color: '#a82a20', seg: 6 })
    b.box(0.03, 0.01, 0.04, { ...STEEL, y: -0.08, z: 0.01 })
  },
  machete(b, mods) {
    b.box(0.024, 0.13, 0.032, { mat: 'rubber', color: '#2a2018', y: 0.0, r: 0.008 })
    b.box(0.05, 0.012, 0.04, { ...GUNMETAL, y: 0.07 })
    b.extrude([[-0.02, 0], [0.024, 0], [0.042, 0.3], [0.03, 0.44], [0.0, 0.5], [-0.02, 0.46]], 0.004, { ...STEEL, color: '#b8bec2', y: 0.075, ry: Math.PI / 2 })
  },
  axe(b, mods) {
    b.lathe([[0.016, 0], [0.017, 0.2], [0.015, 0.7], [0.017, 0.82], [0.012, 0.84]], { ...WOOD, color: '#9a6a3a', y: -0.1 })
    b.extrude([[0, -0.05], [0.13, -0.08], [0.16, 0.0], [0.13, 0.08], [0, 0.05]], 0.022, { mat: 'paint', color: '#b0281e', y: 0.62, ry: Math.PI / 2 })
    b.extrude([[0, -0.03], [-0.1, -0.005], [-0.1, 0.005], [0, 0.03]], 0.02, { mat: 'paint', color: '#b0281e', y: 0.62, ry: Math.PI / 2 })
    b.box(0.006, 0.12, 0.03, { ...STEEL, color: '#c8ccce', y: 0.62, z: 0.158 })
  },
  sledge(b, mods) {
    b.cyl(0.018, 0.02, 0.86, { ...WOOD, color: '#a07040', y: 0.33 })
    b.box(0.08, 0.08, 0.2, { ...GUNMETAL, y: 0.78, r: 0.012 })
  },
  katana(b, mods) {
    b.box(0.026, 0.2, 0.034, { mat: 'cloth', color: '#1a1a24', y: 0.02, r: 0.01 })
    for (let i = 0; i < 6; i++) b.box(0.028, 0.012, 0.036, { mat: 'cloth', color: '#d8d0b8', y: -0.06 + i * 0.03, ry: 0.78 })
    b.cyl(0.04, 0.04, 0.01, { mat: 'steel', color: '#b8962a', y: 0.125, sz: 1.2 })
    b.extrude([[-0.006, 0], [0.012, 0], [0.018, 0.4], [0.028, 0.66], [0.034, 0.72], [0.02, 0.76], [0.0, 0.7], [-0.008, 0.4]], 0.0035, { ...STEEL, color: '#d8dde2', y: 0.13, ry: Math.PI / 2 })
  },
  spear(b) {
    b.cyl(0.015, 0.016, 1.5, { ...WOOD, color: '#8a6038', y: 0.45 })
    b.extrude([[0, 0], [0.025, 0.04], [0.0, 0.2], [-0.025, 0.04]], 0.006, { ...STEEL, y: 1.19, ry: Math.PI / 2 })
    b.cyl(0.02, 0.02, 0.05, { ...TAPE, y: 1.18 })
  },
  pistol(b, mods) {
    b.box(0.032, 0.11, 0.05, { ...GRIP, y: -0.04, z: -0.01, rx: -0.2, r: 0.008 })
    b.box(0.03, 0.035, 0.19, { ...BLACK, y: 0.035, z: 0.06, r: 0.006 })
    b.box(0.028, 0.025, 0.16, { ...GUNMETAL, y: 0.012, z: 0.055, r: 0.005 })
    b.torus(0.018, 0.004, { ...BLACK, y: -0.008, z: 0.03, ry: Math.PI / 2, arc: Math.PI })
    b.box(0.006, 0.012, 0.006, { ...STEEL, y: 0.057, z: 0.145 })
    b.box(0.014, 0.01, 0.008, { ...STEEL, y: 0.057, z: -0.025 })
    gunMods(b, mods, { barrelZ: 0.15, barrelY: 0.035, railY: 0.05, railZ: 0.06 })
  },
  revolver(b, mods) {
    b.box(0.032, 0.11, 0.045, { mat: 'wood', color: '#6a3e22', y: -0.045, z: -0.015, rx: -0.3, r: 0.01 })
    b.box(0.03, 0.05, 0.08, { ...STEEL, color: '#9aa0a4', y: 0.02, z: 0.02, r: 0.006 })
    b.cyl(0.026, 0.026, 0.05, { ...STEEL, color: '#8a9094', y: 0.025, z: 0.045, rx: Math.PI / 2, seg: 6 })
    b.cyl(0.011, 0.012, 0.17, { ...STEEL, color: '#9aa0a4', y: 0.04, z: 0.15, rx: Math.PI / 2 })
    b.box(0.01, 0.014, 0.17, { ...STEEL, color: '#9aa0a4', y: 0.052, z: 0.15 })
    b.box(0.008, 0.02, 0.012, { ...STEEL, y: 0.04, z: -0.02, rx: -0.6 })
    gunMods(b, mods, { barrelZ: 0.235, barrelY: 0.04, railY: 0.06, railZ: 0.08 })
  },
  smg(b, mods) {
    b.box(0.032, 0.1, 0.045, { ...GRIP, y: -0.05, z: 0.0, rx: -0.15, r: 0.008 })
    b.box(0.045, 0.07, 0.3, { ...BLACK, y: 0.035, z: 0.1, r: 0.01 })
    b.box(0.028, 0.14, 0.04, { ...BLACK, y: -0.06, z: 0.12, r: 0.006 })
    b.cyl(0.012, 0.012, 0.1, { ...GUNMETAL, y: 0.04, z: 0.29, rx: Math.PI / 2 })
    b.box(0.02, 0.02, 0.2, { ...BLACK, y: 0.02, z: -0.12, r: 0.004 })
    b.box(0.02, 0.06, 0.02, { ...BLACK, y: -0.0, z: -0.21 })
    b.box(0.012, 0.02, 0.012, { ...STEEL, y: 0.08, z: 0.22 })
    gunMods(b, mods, { barrelZ: 0.34, barrelY: 0.04, railY: 0.07, railZ: 0.1 })
  },
  shotgun(b, mods) {
    b.box(0.04, 0.07, 0.3, { ...WOOD, y: -0.01, z: -0.16, rx: 0.12, r: 0.014 })
    b.box(0.036, 0.06, 0.06, { ...GRIP, y: -0.04, z: 0.0, r: 0.01 })
    b.box(0.044, 0.06, 0.16, { ...GUNMETAL, y: 0.03, z: 0.08, r: 0.008 })
    b.cyl(0.016, 0.016, 0.52, { ...BLACK, y: 0.05, z: 0.42, rx: Math.PI / 2 })
    b.cyl(0.014, 0.014, 0.42, { ...BLACK, y: 0.02, z: 0.36, rx: Math.PI / 2 })
    b.cyl(0.024, 0.024, 0.13, { ...WOOD, color: '#6a4026', y: 0.02, z: 0.33, rx: Math.PI / 2, seg: 10 })
    for (let i = 0; i < 5; i++) b.torus(0.024, 0.002, { ...DWOOD, y: 0.02, z: 0.28 + i * 0.025 })
    b.box(0.006, 0.01, 0.006, { ...STEEL, y: 0.07, z: 0.67 })
    gunMods(b, mods, { barrelZ: 0.68, barrelY: 0.05, railY: 0.065, railZ: 0.12 })
  },
  crossbow(b, mods) {
    b.box(0.04, 0.05, 0.6, { ...WOOD, y: 0.0, z: 0.1, r: 0.012 })
    b.box(0.034, 0.09, 0.05, { ...GRIP, y: -0.06, z: 0.0, rx: -0.2, r: 0.008 })
    b.extrude([[-0.32, 0.02], [-0.3, 0.0], [0, -0.02], [0.3, 0.0], [0.32, 0.02], [0, 0.0]], 0.03, { ...BLACK, y: 0.02, z: 0.38, rx: -Math.PI / 2 })
    b.tube([[-0.31, 0.03, 0.4], [0, 0.03, 0.17], [0.31, 0.03, 0.4]], 0.0025, { mat: 'plain', color: '#e8e0c8', tension: 0 })
    b.cyl(0.005, 0.005, 0.4, { mat: 'wood', color: '#c8b088', y: 0.035, z: 0.32, rx: Math.PI / 2 })
    b.cone(0.012, 0.04, { ...STEEL, y: 0.035, z: 0.54, rx: Math.PI / 2 })
    gunMods(b, mods, { barrelZ: 0.4, barrelY: 0.03, railY: 0.03, railZ: 0.08 })
  },
  rifle(b, mods) {
    b.extrude([[-0.38, -0.07], [-0.1, -0.03], [-0.05, -0.08], [0.0, -0.08], [0.02, -0.02], [0.32, -0.015], [0.32, 0.025], [-0.38, 0.03]], 0.04, { ...WOOD, ry: -Math.PI / 2, z: 0, y: 0.0 })
    b.box(0.03, 0.04, 0.24, { ...GUNMETAL, y: 0.045, z: 0.06, r: 0.006 })
    b.cyl(0.011, 0.013, 0.46, { ...BLACK, y: 0.05, z: 0.45, rx: Math.PI / 2 })
    b.cyl(0.008, 0.008, 0.05, { ...STEEL, y: 0.06, z: 0.0, rz: Math.PI / 2, x: 0.03 })
    b.sphere(0.01, { ...STEEL, x: 0.055, y: 0.06 })
    if (!mods.includes('scope')) gunMods(b, ['scope'], { barrelZ: 0.68, barrelY: 0.05, railY: 0.07, railZ: 0.08 })
    gunMods(b, mods.filter((m) => m !== 'scope'), { barrelZ: 0.68, barrelY: 0.05, railY: 0.07, railZ: 0.08 })
  },
  ar(b, mods) {
    b.box(0.034, 0.1, 0.045, { ...GRIP, y: -0.05, z: 0.0, rx: -0.25, r: 0.008 })
    b.box(0.05, 0.075, 0.26, { ...BLACK, y: 0.035, z: 0.08, r: 0.008 })
    b.box(0.03, 0.15, 0.06, { ...BLACK, y: -0.075, z: 0.1, rx: 0.2, r: 0.008 })
    b.cyl(0.03, 0.03, 0.24, { ...BLACK, y: 0.04, z: 0.33, rx: Math.PI / 2, seg: 10 })
    for (let i = 0; i < 4; i++) b.box(0.062, 0.008, 0.03, { ...GUNMETAL, y: 0.04, z: 0.24 + i * 0.05 })
    b.cyl(0.01, 0.01, 0.12, { ...BLACK, y: 0.04, z: 0.5, rx: Math.PI / 2 })
    b.box(0.03, 0.06, 0.2, { ...BLACK, y: 0.02, z: -0.17, r: 0.01 })
    b.box(0.03, 0.08, 0.04, { ...GRIP, y: 0.0, z: -0.28, r: 0.008 })
    b.box(0.012, 0.03, 0.014, { ...BLACK, y: 0.1, z: 0.42 })
    b.box(0.02, 0.012, 0.25, { ...GUNMETAL, y: 0.08, z: 0.1 })
    gunMods(b, mods, { barrelZ: 0.56, barrelY: 0.04, railY: 0.085, railZ: 0.1 })
  },
}

// Throwables shown in the hand during a throw.
export function throwableModel(id) {
  const b = new Builder()
  if (id === 'molotov') {
    b.lathe([[0.03, 0], [0.034, 0.02], [0.034, 0.12], [0.014, 0.16], [0.012, 0.2]], { mat: 'glass', color: '#6a8a5a' })
    b.cyl(0.013, 0.01, 0.06, { mat: 'cloth', color: '#d8c8a0', y: 0.22 })
  } else if (id === 'pipebomb') {
    b.cyl(0.025, 0.025, 0.16, { mat: 'metal', color: '#8a8e90', y: 0.08 })
    for (const y of [0, 0.16]) b.cyl(0.03, 0.03, 0.025, { mat: 'metal', color: '#6a6e70', y })
    b.tube([[0, 0.17, 0], [0.02, 0.22, 0.01], [0.04, 0.24, 0]], 0.003, { mat: 'plain', color: '#c8a030' })
  } else {
    b.box(0.06, 0.04, 0.09, { mat: 'plastic', color: '#3a3a3a', y: 0.02, r: 0.01 })
    b.sphere(0.008, { mat: 'glowRed', color: '#fff', y: 0.045, z: 0.03 })
  }
  return b.build()
}

// Work tools held during camp jobs. Same convention as melee weapons (+Y from the grip).
const toolCache = new Map()
export function toolModel(kind) {
  if (toolCache.has(kind)) return toolCache.get(kind).clone()
  const b = new Builder()
  const handle = (len, c = '#c8945a', r = 0.016) => b.cyl(r, r, len, { mat: 'wood', color: c, y: len / 2 - 0.08, seg: 8 })
  if (kind === 'hammer') {
    handle(0.34)
    b.box(0.13, 0.04, 0.045, { mat: 'steel', color: '#4a4e52', y: 0.25 })
  } else if (kind === 'saw') {
    b.box(0.05, 0.12, 0.1, { mat: 'wood', color: '#a8582a', y: 0.0 })
    b.box(0.004, 0.5, 0.12, { mat: 'steel', color: '#c0c4c8', y: 0.3, z: 0.02 })
  } else if (kind === 'hoe') {
    handle(1.3)
    b.box(0.18, 0.12, 0.012, { mat: 'steel', color: '#5a5e62', y: 1.2, z: 0.06, rx: 1.2 })
  } else if (kind === 'axe') {
    handle(0.8, '#b8844a', 0.02)
    b.box(0.03, 0.12, 0.2, { mat: 'steel', color: '#6a6e72', y: 0.66, z: 0.08 })
  } else if (kind === 'shovel') {
    handle(1.0)
    b.box(0.2, 0.26, 0.02, { mat: 'steel', color: '#7a7e82', y: 1.02, z: 0.0 })
  } else if (kind === 'wrench') {
    b.box(0.03, 0.3, 0.012, { mat: 'chrome', color: '#d0d4d8', y: 0.08 })
    b.torus(0.035, 0.012, { mat: 'chrome', color: '#d0d4d8', y: 0.25, arc: Math.PI * 1.6 })
  } else if (kind === 'ladle') {
    handle(0.5, '#d8c8b0', 0.012)
    b.sphere(0.06, { mat: 'steel', color: '#b8bcc0', y: 0.42, tl: Math.PI / 2, rx: Math.PI })
  } else if (kind === 'rifle') {
    // a training rifle stand-in
    return weaponModel('rifle')
  }
  const g = b.build({ receive: false })
  g.traverse((o) => o.isMesh && (o.castShadow = true))
  toolCache.set(kind, g)
  return g.clone()
}
