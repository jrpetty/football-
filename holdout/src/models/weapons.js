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
  if (MELEE_AT[id]) meleeMods(b, mods, id, MELEE_AT[id])
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

function gunMods(b, mods, { barrelZ, barrelY = 0.06, railY = 0.1, railZ = 0.15, butt = null, top = 0.03 }) {
  // a longer barrel with a muzzle brake and its own front post
  if (mods.includes('longBarrel')) {
    b.cyl(0.012, 0.012, 0.15, { ...GUNMETAL, z: barrelZ + 0.075, y: barrelY, rx: Math.PI / 2, seg: 10 })
    b.cyl(0.017, 0.017, 0.035, { ...BLACK, z: barrelZ + 0.16, y: barrelY, rx: Math.PI / 2, seg: 10 })
    for (const dz of [0.15, 0.17]) b.box(0.036, 0.004, 0.006, { ...GUNMETAL, z: barrelZ + dz, y: barrelY })
    b.box(0.004, 0.014, 0.006, { ...BLACK, z: barrelZ + 0.13, y: barrelY + 0.018 })
  }
  // a red dot: a squat housing on a low mount, red glass at the front
  if (mods.includes('redDot') && !mods.includes('scope')) {
    b.box(0.022, 0.008, 0.05, { ...BLACK, y: railY + 0.004, z: railZ })
    b.box(0.03, 0.03, 0.04, { ...BLACK, y: railY + 0.025, z: railZ, r: 0.005 })
    b.box(0.022, 0.02, 0.002, { mat: 'glass', color: '#c84a3a', y: railY + 0.027, z: railZ + 0.021 })
    b.box(0.022, 0.02, 0.002, { mat: 'glass', color: '#5a6a78', y: railY + 0.027, z: railZ - 0.021 })
  }
  // a stock: a wire one clipped behind a short gun's grip; on a long gun a
  // cheek riser and a thick recoil pad
  if (mods.includes('stock')) {
    if (butt == null) {
      for (const y of [0.0, -0.065]) b.cyl(0.0055, 0.0055, 0.25, { ...STEEL, y, z: -0.15, rx: Math.PI / 2, seg: 6 })
      b.box(0.03, 0.1, 0.014, { ...GRIP, y: -0.032, z: -0.28 })
      b.box(0.01, 0.07, 0.01, { ...STEEL, y: -0.032, z: -0.03 })
    } else {
      b.box(0.03, 0.022, 0.15, { ...BLACK, y: top + 0.012, z: butt + 0.13, r: 0.004 })
      b.box(0.05, 0.15, 0.03, { ...GRIP, y: top - 0.06, z: butt - 0.01, r: 0.006 })
    }
  }
  // a flared magwell: quicker to find in a hurry
  if (mods.includes('magwell')) b.box(0.05, 0.022, 0.07, { ...GUNMETAL, y: -0.07, z: 0.08, rx: 0.15, r: 0.004 })
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
  if (mods.includes('reinforcedGun')) for (const dz of [0.05, 0.18]) b.box(0.05, 0.016, 0.02, { ...STEEL, y: barrelY - 0.02, z: dz })
}

// Where on a club or blade its mods go: the striking end (y), its
// radius, and the handle's top.
const MELEE_AT = {
  bat: { y: 0.6, r: 0.033, grip: 0.1 },
  nailbat: { y: 0.6, r: 0.033, grip: 0.1 },
  pipe: { y: 0.52, r: 0.019, grip: 0.09 },
  crowbar: { y: 0.36, r: 0.012, grip: 0.08 },
  pan: { y: 0.31, r: 0.12, grip: 0.08, flat: true },
  sledge: { y: 0.6, r: 0.02, grip: 0.12 },
  machete: { y: 0.3, r: 0.02, grip: 0.06, blade: true },
  axe: { y: 0.5, r: 0.017, grip: 0.08 },
  katana: { y: 0.4, r: 0.012, grip: 0.1, blade: true },
  spear: { y: 1.0, r: 0.014, grip: 0.12 },
}
function meleeMods(b, mods, id, A) {
  // spikes, unless the model drew its own
  if (mods.includes('spiked') && !['bat', 'nailbat', 'pan'].includes(id) && !A.blade)
    for (let i = 0; i < 8; i++) {
      const a = i * 2.3
      b.cyl(0.0025, 0.0025, 0.06, { ...STEEL, x: Math.cos(a) * A.r, y: A.y - 0.1 + (i % 4) * 0.045, z: Math.sin(a) * A.r, rz: Math.cos(a) * Math.PI / 2, rx: Math.sin(a) * Math.PI / 2 })
    }
  // barbed wire wound round the business end
  if (mods.includes('barbed')) {
    if (A.flat) for (let i = 0; i < 3; i++) b.torus(0.105 - i * 0.03, 0.0025, { ...STEEL, color: '#8a8a84', y: A.y, z: 0.012, rs: 4, ts2: 22 })
    else
      for (let i = 0; i < 7; i++) {
        b.torus(A.r + 0.006, 0.0022, { ...STEEL, color: '#8a8a84', y: A.y - 0.15 + i * 0.03, rx: Math.PI / 2 + (i % 2 ? 0.25 : -0.25), rs: 4, ts2: 12 })
        b.cyl(0.0018, 0.0018, 0.022, { ...STEEL, color: '#8a8a84', x: A.r + 0.008, y: A.y - 0.15 + i * 0.03, rz: Math.PI / 2 })
      }
  }
  // a steel collar weighting the head (a pan gets a riveted plate)
  if (mods.includes('heavyHead')) {
    if (A.flat) b.cyl(0.08, 0.08, 0.016, { ...GUNMETAL, color: '#4a4c50', y: A.y, z: -0.01, rx: Math.PI / 2, seg: 18 })
    else {
      b.cyl(A.r + 0.012, A.r + 0.012, 0.12, { ...GUNMETAL, color: '#4a4c50', y: A.y, seg: 12 })
      for (const dy of [-0.05, 0.05]) b.torus(A.r + 0.013, 0.004, { ...STEEL, y: A.y + dy, rx: Math.PI / 2, rs: 4, ts2: 14 })
    }
  }
  // a balanced grip: fresh tape, a lanyard loop at the end
  if (mods.includes('balanced')) {
    b.cyl(0.021, 0.021, A.grip, { mat: 'cloth', color: '#7a2a24', y: A.grip / 2 - 0.02, seg: 10 })
    b.torus(0.022, 0.003, { mat: 'cloth', color: '#2a2a2a', y: -0.06, rs: 4, ts2: 10 })
  }
  // reinforced: steel bands along the shaft
  if (mods.includes('reinforcedMelee') && !A.blade && !A.flat) for (const f of [0.35, 0.6]) b.cyl(A.r * 0.7 + 0.01, A.r * 0.7 + 0.01, 0.025, { ...STEEL, y: A.y * f, seg: 10 })
}

const W = {
  none() {},
  fists() {},
  bat(b, mods) {
    b.lathe([[0.016, 0], [0.018, 0.02], [0.016, 0.03], [0.016, 0.24], [0.022, 0.4], [0.031, 0.58], [0.034, 0.74], [0.032, 0.8], [0.02, 0.815], [0.001, 0.82]], { ...WOOD, color: '#a8784a', y: -0.08 })
    b.cyl(0.018, 0.018, 0.16, { ...TAPE, y: 0.02 })
    if (mods.includes('spiked')) for (let i = 0; i < 8; i++) b.cyl(0.003, 0.003, 0.08, { ...STEEL, y: 0.5 + (i % 4) * 0.06, ry: i, rz: Math.PI / 2, x: 0, z: 0 })
  },
  // a cast-iron skillet: wrapped handle up from the hand, the pan's face square on
  pan(b, mods) {
    b.cyl(0.013, 0.015, 0.2, { ...GUNMETAL, color: '#2a2a2c', y: 0.08 })
    b.cyl(0.016, 0.016, 0.1, { ...TAPE, color: '#5a3a2a', y: 0.03 })
    b.lathe([[0.001, 0], [0.11, 0], [0.12, 0.012], [0.128, 0.038], [0.12, 0.042], [0.11, 0.012], [0.001, 0.012]], { ...GUNMETAL, color: '#232325', y: 0.31, z: -0.02, rx: Math.PI / 2, seg: 22 })
    if (mods.includes('spiked')) for (let i = 0; i < 6; i++) b.cyl(0.003, 0.003, 0.05, { ...STEEL, x: Math.cos(i) * 0.11, y: 0.31 + Math.sin(i) * 0.11, z: 0.0, rx: Math.PI / 2 })
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
    // the head (its outline turned so the blade is on -z): a blade flaring
    // to a long straight edge, a spike behind
    b.extrude([[0, -0.045], [0.1, -0.06], [0.145, -0.088], [0.152, -0.084], [0.152, 0.084], [0.145, 0.088], [0.1, 0.06], [0, 0.045]], 0.022, { mat: 'paint', color: '#b0281e', y: 0.62, ry: Math.PI / 2 })
    b.extrude([[0, -0.03], [-0.1, -0.005], [-0.1, 0.005], [0, 0.03]], 0.02, { mat: 'paint', color: '#b0281e', y: 0.62, ry: Math.PI / 2 })
    // the ground steel of the edge
    b.box(0.024, 0.172, 0.016, { ...STEEL, color: '#c8ccce', y: 0.62, z: -0.156 })
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
  // Guns are side profiles extruded across x (see Builder.profile), so each
  // reads with a true silhouette: slide and frame, receiver and magwell,
  // stock with comb and butt pad, trigger guard with a real opening.
  pistol(b, mods) {
    // polymer frame and grip, steel slide with serrations and sights
    b.profile([[-0.044, -0.095], [0.002, -0.098], [0.012, -0.03], [0.02, 0.0], [0.02, 0.016], [-0.032, 0.016], [-0.04, 0.0], [-0.048, -0.04]], 0.03, { ...GRIP, bevel: 0.004, bevelSeg: 1 })
    b.profile([[-0.02, 0.0], [0.14, 0.0], [0.142, 0.016], [-0.03, 0.018]], 0.026, { ...BLACK })
    b.profile([[0.0, 0.004], [0.062, 0.004], [0.06, -0.012], [0.05, -0.03], [0.012, -0.034], [0.004, -0.02]], 0.014, { ...BLACK, holes: [[[0.008, -0.004], [0.052, -0.004], [0.044, -0.024], [0.014, -0.027]]] })
    b.box(0.006, 0.022, 0.008, { ...GUNMETAL, y: -0.012, z: 0.024, rx: 0.3 })
    b.profile([[-0.034, 0.016], [0.152, 0.016], [0.158, 0.022], [0.158, 0.046], [0.15, 0.052], [-0.028, 0.052], [-0.036, 0.044]], 0.027, { ...GUNMETAL, bevel: 0.002 })
    for (let i = 0; i < 6; i++) b.box(0.0285, 0.026, 0.002, { mat: 'plain', color: '#141618', y: 0.034, z: -0.026 + i * 0.006 })
    b.box(0.0285, 0.012, 0.026, { mat: 'plain', color: '#141618', y: 0.044, z: 0.05 })
    b.box(0.006, 0.008, 0.008, { ...STEEL, y: 0.057, z: 0.148 })
    b.box(0.018, 0.008, 0.008, { ...STEEL, y: 0.057, z: -0.024 })
    b.cyl(0.0065, 0.0065, 0.004, { mat: 'plain', color: '#0e0e0e', y: 0.034, z: 0.159, rx: Math.PI / 2, seg: 8 })
    b.box(0.032, 0.014, 0.04, { ...GRIP, y: -0.094, z: -0.021, rx: -0.2 })
    gunMods(b, mods, { barrelZ: 0.16, barrelY: 0.034, railY: 0.052, railZ: 0.06 })
  },
  revolver(b, mods) {
    // wood grip, steel frame with top strap, fluted cylinder, barrel with ejector rod
    b.profile([[-0.048, -0.1], [-0.004, -0.104], [0.006, -0.04], [0.004, 0.0], [-0.03, 0.008], [-0.05, -0.03]], 0.032, { mat: 'wood', color: '#6a3e22', bevel: 0.005, bevelSeg: 2 })
    b.profile([[-0.03, 0.0], [0.07, 0.0], [0.07, 0.056], [0.0, 0.058], [-0.026, 0.044], [-0.034, 0.02]], 0.026, { ...STEEL, color: '#9aa0a4' })
    b.profile([[0.0, 0.004], [0.05, 0.004], [0.046, -0.014], [0.034, -0.03], [0.008, -0.032], [0.0, -0.016]], 0.012, { ...STEEL, color: '#8a9094', holes: [[[0.006, -0.002], [0.042, -0.002], [0.032, -0.022], [0.01, -0.025]]] })
    b.cyl(0.024, 0.024, 0.048, { ...STEEL, color: '#8a9094', y: 0.03, z: 0.036, rx: Math.PI / 2, seg: 12 })
    for (let i = 0; i < 6; i++) {
      const a = (i / 6) * Math.PI * 2
      b.cyl(0.004, 0.004, 0.05, { mat: 'plain', color: '#3a3e42', x: Math.cos(a) * 0.022, y: 0.03 + Math.sin(a) * 0.022, z: 0.036, rx: Math.PI / 2, seg: 5 })
    }
    b.cyl(0.011, 0.012, 0.16, { ...STEEL, color: '#9aa0a4', y: 0.044, z: 0.15, rx: Math.PI / 2, seg: 10 })
    b.box(0.012, 0.014, 0.16, { ...STEEL, color: '#9aa0a4', y: 0.056, z: 0.15 })
    b.cyl(0.006, 0.006, 0.11, { ...STEEL, color: '#8a9094', y: 0.024, z: 0.13, rx: Math.PI / 2, seg: 6 })
    b.box(0.004, 0.012, 0.006, { ...STEEL, y: 0.066, z: 0.226 })
    b.box(0.008, 0.026, 0.016, { ...STEEL, color: '#8a9094', y: 0.054, z: -0.03, rx: -0.6 })
    b.box(0.005, 0.02, 0.006, { ...GUNMETAL, y: -0.012, z: 0.022, rx: 0.25 })
    gunMods(b, mods, { barrelZ: 0.23, barrelY: 0.044, railY: 0.064, railZ: 0.09 })
  },
  smg(b, mods) {
    // round receiver, pistol grip, curved magazine, cocking tube, folding stock
    b.cyl(0.024, 0.024, 0.3, { ...BLACK, y: 0.04, z: 0.1, rx: Math.PI / 2, seg: 12 })
    b.cyl(0.012, 0.012, 0.17, { ...BLACK, y: 0.066, z: 0.15, rx: Math.PI / 2, seg: 8 })
    b.profile([[-0.034, -0.1], [0.008, -0.1], [0.018, -0.02], [0.03, 0.02], [-0.02, 0.02], [-0.03, 0.0]], 0.03, { ...GRIP, bevel: 0.004 })
    b.profile([[0.0, 0.016], [0.08, 0.016], [0.07, -0.012], [0.05, -0.03], [0.014, -0.034], [0.002, -0.016]], 0.014, { ...BLACK, holes: [[[0.008, 0.004], [0.06, 0.004], [0.046, -0.02], [0.014, -0.025]]] })
    b.profile([[0.1, 0.02], [0.134, 0.02], [0.13, -0.06], [0.112, -0.15], [0.086, -0.14], [0.1, -0.06]], 0.024, { ...BLACK, bevel: 0.002 })
    b.profile([[0.22, 0.02], [0.32, 0.02], [0.32, -0.02], [0.22, -0.02]], 0.05, { ...BLACK, bevel: 0.006 })
    b.cyl(0.011, 0.011, 0.08, { ...GUNMETAL, y: 0.04, z: 0.29, rx: Math.PI / 2, seg: 8 })
    b.torus(0.016, 0.003, { ...BLACK, y: 0.075, z: 0.25, ry: Math.PI / 2, arc: Math.PI })
    b.box(0.014, 0.016, 0.018, { ...BLACK, y: 0.072, z: -0.03 })
    for (const s of [-1, 1]) b.beam([s * 0.018, 0.04, -0.05], [s * 0.018, 0.02, -0.25], 0.008, 0.012, { ...BLACK })
    b.box(0.05, 0.08, 0.015, { ...GRIP, y: 0.02, z: -0.255, r: 0.004 })
    gunMods(b, mods, { barrelZ: 0.33, barrelY: 0.04, railY: 0.07, railZ: 0.1 })
  },
  shotgun(b, mods) {
    // wood stock with a comb and rubber pad, receiver, barrel over the magazine tube, ribbed pump
    b.profile([[-0.44, -0.11], [-0.4, -0.115], [-0.06, -0.04], [-0.02, -0.06], [0.01, -0.055], [0.0, -0.01], [0.02, 0.02], [-0.06, 0.03], [-0.42, 0.02], [-0.45, 0.0]], 0.042, { ...WOOD, bevel: 0.008, bevelSeg: 2 })
    b.profile([[-0.46, -0.115], [-0.44, -0.112], [-0.43, 0.022], [-0.45, 0.02]], 0.044, { mat: 'rubber', color: '#1a1a1a' })
    b.profile([[0.0, -0.01], [0.17, -0.01], [0.17, 0.06], [0.03, 0.064], [-0.01, 0.04]], 0.044, { ...GUNMETAL, bevel: 0.004 })
    b.profile([[0.02, -0.01], [0.09, -0.01], [0.08, -0.03], [0.06, -0.044], [0.03, -0.046], [0.02, -0.03]], 0.014, { ...BLACK, holes: [[[0.028, -0.016], [0.078, -0.016], [0.066, -0.036], [0.034, -0.038]]] })
    b.box(0.03, 0.012, 0.06, { ...STEEL, y: 0.03, z: 0.1, x: 0.022 })
    b.cyl(0.0165, 0.0165, 0.54, { ...BLACK, y: 0.046, z: 0.44, rx: Math.PI / 2, seg: 12 })
    b.cyl(0.014, 0.014, 0.42, { ...BLACK, y: 0.012, z: 0.38, rx: Math.PI / 2, seg: 10 })
    b.cyl(0.026, 0.026, 0.15, { ...WOOD, color: '#6a4026', y: 0.016, z: 0.33, rx: Math.PI / 2, seg: 12 })
    for (let i = 0; i < 7; i++) b.torus(0.0262, 0.0022, { ...DWOOD, y: 0.016, z: 0.27 + i * 0.02, rs: 3, ts2: 12 })
    b.box(0.008, 0.004, 0.5, { ...GUNMETAL, y: 0.064, z: 0.44 })
    b.sphere(0.004, { mat: 'chrome', color: '#e8e0c0', y: 0.068, z: 0.7 })
    gunMods(b, mods, { barrelZ: 0.71, barrelY: 0.046, railY: 0.066, railZ: 0.1, butt: -0.45, top: 0.02 })
  },
  crossbow(b, mods) {
    b.profile([[-0.24, -0.08], [-0.2, -0.085], [-0.06, -0.03], [-0.02, -0.07], [0.02, -0.065], [0.03, -0.01], [0.48, -0.01], [0.48, 0.02], [-0.2, 0.025], [-0.25, 0.0]], 0.04, { ...WOOD, bevel: 0.006, bevelSeg: 2 })
    b.box(0.03, 0.006, 0.4, { ...GUNMETAL, y: 0.023, z: 0.26 })
    // recurved limbs, string, bolt and foot stirrup
    for (const s of [-1, 1]) b.tube([[0, 0.0, 0.42], [s * 0.14, 0.0, 0.43], [s * 0.26, 0.0, 0.39], [s * 0.31, 0.0, 0.42]], 0.009, { ...BLACK, seg: 6, tseg: 10 })
    b.tube([[-0.31, 0.012, 0.42], [-0.12, 0.024, 0.22], [0, 0.026, 0.15], [0.12, 0.024, 0.22], [0.31, 0.012, 0.42]], 0.0022, { mat: 'plain', color: '#d8d0b8', seg: 4, tension: 0.2 })
    b.cyl(0.004, 0.004, 0.4, { mat: 'wood', color: '#c8b088', y: 0.03, z: 0.34, rx: Math.PI / 2, seg: 6 })
    b.cone(0.009, 0.035, { ...STEEL, y: 0.03, z: 0.555, rx: Math.PI / 2, seg: 6 })
    for (const s of [-1, 1]) b.box(0.002, 0.012, 0.03, { mat: 'plain', color: '#c83a2a', x: s * 0.004, y: 0.032, z: 0.16 })
    b.torus(0.045, 0.006, { ...STEEL, y: 0.0, z: 0.5, ry: 0, rx: 0, arc: Math.PI, rz: Math.PI })
    gunMods(b, mods, { barrelZ: 0.45, barrelY: 0.03, railY: 0.03, railZ: 0.1 })
  },
  rifle(b, mods) {
    // bolt-action hunting rifle: walnut stock with pistol grip and cheek comb
    b.profile([[-0.42, -0.09], [-0.38, -0.095], [-0.08, -0.03], [-0.04, -0.08], [0.0, -0.08], [0.01, -0.02], [0.36, -0.012], [0.38, 0.0], [0.36, 0.02], [0.0, 0.024], [-0.1, 0.036], [-0.38, 0.03], [-0.43, 0.01]], 0.044, { ...WOOD, bevel: 0.008, bevelSeg: 2 })
    b.profile([[-0.44, -0.095], [-0.42, -0.09], [-0.41, 0.032], [-0.43, 0.03]], 0.046, { mat: 'rubber', color: '#1a1a1a' })
    b.profile([[0.0, 0.0], [0.04, -0.035], [0.008, -0.04]], 0.02, { ...BLACK })
    b.profile([[0.0, 0.004], [0.07, 0.004], [0.062, -0.016], [0.046, -0.03], [0.016, -0.032], [0.004, -0.018]], 0.014, { ...BLACK, holes: [[[0.008, -0.002], [0.058, -0.002], [0.046, -0.022], [0.016, -0.025]]] })
    b.cyl(0.018, 0.018, 0.24, { ...GUNMETAL, y: 0.04, z: 0.06, rx: Math.PI / 2, seg: 12 })
    b.cyl(0.011, 0.014, 0.5, { ...BLACK, y: 0.042, z: 0.43, rx: Math.PI / 2, seg: 10 })
    b.cyl(0.006, 0.006, 0.06, { ...STEEL, y: 0.052, x: 0.03, z: -0.02, rz: Math.PI / 2, seg: 6 })
    b.sphere(0.01, { ...STEEL, x: 0.062, y: 0.052, z: -0.02 })
    if (!mods.includes('scope')) gunMods(b, ['scope'], { barrelZ: 0.68, barrelY: 0.042, railY: 0.06, railZ: 0.06 })
    gunMods(b, mods.filter((m) => m !== 'scope' && m !== 'redDot'), { barrelZ: 0.68, barrelY: 0.042, railY: 0.06, railZ: 0.06, butt: -0.43, top: 0.03 })
    for (const z of [-0.3, 0.3]) b.torus(0.012, 0.0025, { ...STEEL, y: -0.03 + (z > 0 ? 0.01 : -0.02), z, ry: Math.PI / 2, rs: 3, ts2: 8 })
  },
  ar(b, mods) {
    // lower receiver with magwell, pistol grip, curved mag, buffer tube and stock
    b.profile([[-0.02, -0.01], [0.13, -0.01], [0.13, -0.04], [0.09, -0.06], [0.04, -0.06], [0.02, -0.03], [-0.03, -0.02]], 0.03, { ...BLACK, bevel: 0.003 })
    b.profile([[-0.05, -0.12], [-0.01, -0.124], [0.012, -0.03], [0.016, 0.0], [-0.03, 0.0], [-0.04, -0.03]], 0.032, { ...GRIP, bevel: 0.004 })
    b.profile([[0.0, -0.012], [0.05, -0.012], [0.046, -0.04], [0.032, -0.05], [0.006, -0.05], [0.0, -0.03]], 0.012, { ...BLACK, holes: [[[0.006, -0.018], [0.044, -0.018], [0.034, -0.042], [0.008, -0.043]]] })
    b.profile([[0.06, -0.06], [0.1, -0.06], [0.11, -0.12], [0.13, -0.2], [0.095, -0.21], [0.075, -0.13]], 0.028, { ...BLACK, bevel: 0.002 })
    // upper receiver, flat-top rail, charging handle, forward assist
    b.profile([[-0.04, -0.01], [0.17, -0.01], [0.17, 0.05], [-0.04, 0.05]], 0.034, { ...BLACK, bevel: 0.003 })
    for (let i = 0; i < 12; i++) b.box(0.024, 0.006, 0.008, { ...GUNMETAL, y: 0.056, z: -0.03 + i * 0.016 })
    b.box(0.022, 0.012, 0.04, { ...BLACK, y: 0.046, z: -0.055 })
    b.cyl(0.008, 0.008, 0.03, { ...GUNMETAL, x: 0.022, y: 0.03, z: 0.02, rz: Math.PI / 2, seg: 6 })
    // free-float handguard with vents, gas block, barrel and flash hider
    b.cyl(0.026, 0.026, 0.26, { ...BLACK, y: 0.022, z: 0.3, rx: Math.PI / 2, seg: 8 })
    for (let i = 0; i < 6; i++) for (const s of [-1, 1]) b.box(0.003, 0.012, 0.024, { mat: 'plain', color: '#0e0e10', x: s * 0.0255, y: 0.022, z: 0.2 + i * 0.04 })
    b.box(0.022, 0.006, 0.26, { ...GUNMETAL, y: 0.05, z: 0.3 })
    b.cyl(0.009, 0.01, 0.16, { ...BLACK, y: 0.022, z: 0.5, rx: Math.PI / 2, seg: 8 })
    b.cyl(0.011, 0.011, 0.04, { ...GUNMETAL, y: 0.022, z: 0.59, rx: Math.PI / 2, seg: 6 })
    b.box(0.012, 0.03, 0.014, { ...BLACK, y: 0.068, z: 0.42 })
    b.box(0.022, 0.02, 0.016, { ...BLACK, y: 0.066, z: -0.03 })
    // buffer tube and collapsible stock
    b.cyl(0.015, 0.015, 0.2, { ...BLACK, y: 0.026, z: -0.13, rx: Math.PI / 2, seg: 10 })
    b.profile([[-0.3, -0.07], [-0.26, -0.07], [-0.16, 0.0], [-0.16, 0.048], [-0.3, 0.05]], 0.038, { ...BLACK, bevel: 0.004 })
    b.profile([[-0.31, -0.072], [-0.3, -0.072], [-0.3, 0.052], [-0.31, 0.052]], 0.04, { ...GRIP })
    gunMods(b, mods, { barrelZ: 0.61, barrelY: 0.022, railY: 0.06, railZ: 0.06, butt: -0.31, top: 0.05 })
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
