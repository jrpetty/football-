// Station models, part two: forge, still, power, chemistry, the crafting
// benches and the defences.
import { seeded } from './kit.js'
import {
  COL, shadeHex, crate, ammoCrate, barrel, jerrycan, sack, sandbags, pallet, tireStack, log, plankStack, stump, scrapPile, tarp,
  corrRoof, lantern, lampPost, bulb, stringLights, table, stool, bench, shelf, toolWall, vise, anvil, sawhorse, ladder, toolbox, bucket,
  gasBottle, cableReel, sign, ibcTote, pipe, valveWheel, gauge, carBattery, radioSet, monitor, solarPanel, clothesline, mannequin,
  cinderBlocks, rock, barbedCoil, generatorSmall, screenMat, hayBale,
} from './parts.js'
import { slab, deck, boardWall, windowPane, door, frame, hangingTools, roof } from './stationkit.js'

const TAU = Math.PI * 2
const FACE_BACK = Math.PI
const COPPER = '#e09a68'

// Simplified long gun and pistol silhouettes for racks and benches.
function rifleProp(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0, rz: o.rz || 0 }, () => {
    b.box(0.04, 0.12, 0.38, { mat: 'wood', color: o.wood ?? '#8a5a3a', z: -0.42 })
    b.box(0.035, 0.07, 0.3, { mat: 'metal', color: '#2a2c2e', z: -0.08 })
    b.cyl(0.012, 0.012, 0.55, { mat: 'steel', color: '#3a3e42', z: 0.33, y: 0.02, rx: Math.PI / 2 })
    b.box(0.03, 0.04, 0.32, { mat: 'wood', color: o.wood ?? '#8a5a3a', z: 0.2, y: -0.02 })
    if (o.scope) b.cyl(0.018, 0.018, 0.22, { mat: 'paint', color: '#1a1a1a', y: 0.07, z: -0.05, rx: Math.PI / 2 })
    if (o.mag) b.box(0.03, 0.12, 0.05, { mat: 'paint', color: '#1a1a1a', y: -0.08, z: 0.0 })
  })
}
function pistolProp(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0, rz: o.rz ?? Math.PI / 2 }, () => {
    b.box(0.03, 0.04, 0.18, { mat: 'metal', color: '#2a2c2e' })
    b.box(0.028, 0.11, 0.045, { mat: 'rubber', color: '#1a1a1a', y: -0.06, z: -0.06, rx: 0.2 })
  })
}
function sewingMachine(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.42, 0.06, 0.18, { mat: 'paint', color: o.color ?? '#1a1a1a', y: 0.03 })
    b.box(0.08, 0.22, 0.14, { mat: 'paint', color: o.color ?? '#1a1a1a', x: 0.15, y: 0.17 })
    b.box(0.36, 0.08, 0.12, { mat: 'paint', color: o.color ?? '#1a1a1a', x: 0.0, y: 0.3 })
    b.cyl(0.012, 0.012, 0.12, { mat: 'chrome', color: '#d0d4d8', x: -0.15, y: 0.2 })
    b.cyl(0.07, 0.07, 0.03, { mat: 'chrome', color: '#c0c4c8', x: 0.22, y: 0.25, rz: Math.PI / 2 })
    b.box(0.2, 0.02, 0.003, { mat: 'plain', color: '#d8b040', y: 0.3, z: 0.062, ao: 0 })
  })
}
function glassware(b, x, y, z, rnd, n = 6) {
  for (let i = 0; i < n; i++) {
    const k = rnd()
    const px = x + (i % 3) * 0.16 - 0.16
    const pz = z + Math.floor(i / 3) * 0.14 - 0.07
    const col = ['#58e070', '#e8d040', '#40b8e8', '#e85a40', '#c060e0'][Math.floor(rnd() * 5)]
    if (k < 0.4) {
      b.lathe([[0.06, 0], [0.065, 0.06], [0.02, 0.15], [0.02, 0.2]], { mat: 'glass', color: '#e8f0f0', x: px, y, z: pz, seg: 12 })
      b.sphere(0.055, { mat: 'glowGreen', color: col, x: px, y: y + 0.04, z: pz, sy: 0.6, ws: 10, hs: 6 })
    } else if (k < 0.7) {
      b.cyl(0.045, 0.045, 0.12, { mat: 'glass', color: '#e8f0f0', x: px, y: y + 0.06, z: pz, seg: 12, open: true })
      b.cyl(0.042, 0.042, 0.06, { mat: 'plain', color: col, x: px, y: y + 0.03, z: pz, seg: 12 })
    } else {
      b.cyl(0.015, 0.015, 0.2, { mat: 'glass', color: '#e8f0f0', x: px, y: y + 0.1, z: pz, seg: 8 })
      b.cyl(0.013, 0.013, 0.12, { mat: 'plain', color: col, x: px, y: y + 0.06, z: pz, seg: 8 })
    }
  }
}
function hazardSign(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y ?? 1.4, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.32, 0.32, 0.01, { mat: 'paint', color: '#e8c020', rz: Math.PI / 4 })
    b.box(0.26, 0.26, 0.012, { mat: 'paint', color: '#1a1a1a', rz: Math.PI / 4, sx: 0.12, sy: 0.5, y: 0.02 })
    b.box(0.04, 0.04, 0.012, { mat: 'paint', color: '#1a1a1a', y: -0.08 })
  })
}

export const STATIONS2 = {
  forge(b, L, I) {
    const rnd = seeded(401)
    if (L === 1) {
      // clay furnace, leather bellows, anvil on a stump, quench barrel
      b.at({ x: -0.8, z: -0.7 }, () => {
        b.lathe([[0.55, 0], [0.55, 0.5], [0.42, 1.1], [0.22, 1.4], [0.2, 1.55]], { mat: 'brick', color: '#d8a888', seg: 14 })
        b.cyl(0.24, 0.24, 0.05, { mat: 'plain', color: '#1a1410', y: 1.55, seg: 12 })
        b.box(0.32, 0.26, 0.1, { mat: 'embers', color: '#ffffff', y: 0.32, z: 0.5 })
        b.box(0.4, 0.06, 0.2, { mat: 'brick', color: '#c89878', y: 0.48, z: 0.52 })
        for (let i = 0; i < 6; i++) b.sphere(0.07, { mat: 'embers', color: '#ffffff', x: (rnd() - 0.5) * 0.3, y: 0.24, z: 0.62 + rnd() * 0.1, ws: 6, hs: 4 })
      })
      I.lights.push({ x: -0.8, y: 0.5, z: -0.1, color: '#ff7a2a', intensity: 4, dist: 7, flicker: true, when: 'active' })
      I.emitters.push({ kind: 'smoke', x: -0.8, y: 1.6, z: -0.7, when: 'active' }, { kind: 'embers', x: -0.8, y: 1.55, z: -0.7, when: 'active' })
      // bellows
      b.at({ x: -1.6, z: 0.1, ry: 0.6 }, () => {
        b.box(0.12, 0.5, 0.12, { mat: 'wood', color: COL.woodGrey, y: 0.25 })
        b.pivot('bellows', { y: 0.55 }, (p) => {
          p.wedge(0.5, 0.12, 0.7, { mat: 'cloth', color: '#7a4a2a', rx: Math.PI })
          p.box(0.5, 0.03, 0.7, { mat: 'wood', color: '#a8784a', y: 0.0 })
          p.box(0.05, 0.05, 0.3, { mat: 'wood', color: '#a8784a', z: -0.45 })
        })
        I.anims.push({ name: 'bellows', kind: 'pump', axis: 'x', amp: 0.15, speed: 3, when: 'active' })
        b.cyl(0.04, 0.04, 0.6, { mat: 'metal', color: '#3a3a3a', z: 0.45, y: 0.5, rx: Math.PI / 2 })
      })
      const ay = anvil(b, { x: 0.7, z: 0.4, ry: 0.3 })
      barrel(b, { x: 1.55, z: -0.3, color: '#4a4a48', open: true, contents: '#2a3a40', fill: 0.85 })
      b.ico(0.45, { mat: 'plain', color: '#1e1c1a', x: -1.5, y: 0.08, z: -1.4, sy: 0.35, detail: 1, noise: 0.5 })
      b.at({ x: 1.6, z: 1.3 }, () => {
        for (const sx of [-1, 1]) b.box(0.06, 1.1, 0.06, { mat: 'wood', color: COL.woodGrey, x: sx * 0.4, y: 0.55 })
        b.box(0.9, 0.06, 0.06, { mat: 'wood', color: COL.woodGrey, y: 1.05 })
        for (let i = 0; i < 4; i++) b.box(0.025, 0.55, 0.02, { mat: 'steel', color: '#3a3a3a', x: -0.3 + i * 0.2, y: 0.75, rz: (i % 2) * 0.1 })
      })
      for (let i = 0; i < 6; i++) b.box(0.4 + rnd() * 0.4, 0.04, 0.05, { mat: 'rust', color: '#ffffff', x: 0.4 + rnd(), y: 0.03, z: -1.3 + rnd() * 0.5, ry: rnd() * 3 })
      I.spots.push({ x: 0.7, z: 1.05, face: FACE_BACK - 0.3, anim: 'hammer' }, { x: -1.6, z: 0.75, face: FACE_BACK + 0.6, anim: 'pump' })
      return
    }
    if (L === 2) {
      // brick hearth with a hood and chimney, crank blower, grinding wheel
      b.at({ x: -0.6, z: -0.9 }, () => {
        b.box(1.6, 0.85, 1.1, { mat: 'brick', color: '#ffffff', y: 0.43 })
        b.box(1.7, 0.08, 1.2, { mat: 'concrete', color: '#b8b0a8', y: 0.88 })
        b.cyl(0.32, 0.25, 0.1, { mat: 'plain', color: '#1a1410', y: 0.9 })
        for (let i = 0; i < 9; i++) b.sphere(0.07, { mat: 'embers', color: '#ffffff', x: (rnd() - 0.5) * 0.4, y: 0.94, z: (rnd() - 0.5) * 0.4, ws: 6, hs: 4 })
        b.lathe([[0.85, 0], [0.3, 0.7], [0.25, 0.75]], { mat: 'metal', color: '#3a3a3a', y: 1.75, seg: 4, ry: Math.PI / 4, sz: 0.75 })
        b.box(0.5, 2.6, 0.5, { mat: 'brick', color: '#d8c8c0', y: 3.0 })
        b.box(0.6, 0.1, 0.6, { mat: 'concrete', color: '#b8b0a8', y: 4.32 })
        b.at({ x: 1.0, y: 0.6 }, () => {
          b.cyl(0.2, 0.2, 0.15, { mat: 'paint', color: '#3a5a3a', rz: Math.PI / 2 })
          b.pivot('crank', { x: 0.1 }, (p) => {
            p.box(0.02, 0.25, 0.03, { mat: 'steel', color: '#3a3a3a', y: 0.1 })
            p.cyl(0.015, 0.015, 0.12, { mat: 'wood', color: '#8a5a3a', y: 0.22, x: 0.06, rz: Math.PI / 2 })
          })
          I.anims.push({ name: 'crank', kind: 'spin', axis: 'x', speed: 5, when: 'active' })
        })
      })
      I.lights.push({ x: -0.6, y: 1.2, z: -0.5, color: '#ff7a2a', intensity: 5, dist: 8, flicker: true, when: 'active' })
      I.emitters.push({ kind: 'smoke', x: -0.6, y: 4.45, z: -0.9, when: 'active' }, { kind: 'embers', x: -0.6, y: 1.0, z: -0.9, when: 'active' })
      anvil(b, { x: 0.8, z: 0.5, ry: -0.2 })
      b.at({ x: 1.5, z: -0.9 }, () => {
        b.box(0.5, 0.75, 0.6, { mat: 'metal', color: '#4a4e52', y: 0.38 })
        b.box(0.5, 0.02, 0.6, { mat: 'water', color: '#3a4a50', y: 0.7 })
      })
      // bar stock rack
      b.at({ x: -1.6, z: 0.9, ry: Math.PI / 2 }, () => {
        for (const sx of [-1, 1]) b.box(0.06, 0.8, 0.06, { mat: 'steel', color: '#4a4e52', x: sx * 0.6, y: 0.4 })
        for (let i = 0; i < 8; i++) b.box(1.5, 0.03, 0.03, { mat: 'rust', color: '#ffffff', y: 0.45 + (i % 4) * 0.08, z: (Math.floor(i / 4) - 0.5) * 0.1 })
      })
      // grinding wheel
      b.at({ x: 1.4, z: 1.3 }, () => {
        b.box(0.08, 0.8, 0.08, { mat: 'wood', color: COL.woodGrey, y: 0.4 })
        b.pivot('wheel', { y: 0.9 }, (p) => p.cyl(0.25, 0.25, 0.06, { mat: 'concrete', color: '#c8c0b0', rz: Math.PI / 2, seg: 18 }))
        I.anims.push({ name: 'wheel', kind: 'spin', axis: 'x', speed: 9, when: 'active' })
      })
      I.spots.push({ x: 0.8, z: 1.15, face: FACE_BACK + 0.2, anim: 'hammer' }, { x: -0.6, z: -0.1, face: FACE_BACK, anim: 'stir' })
      return
    }
    // L3: oil-fired furnace, power hammer, ingot molds
    slab(b, I, { h: 0.12 })
    b.at({ x: -0.9, z: -0.8 }, () => {
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.08, 0.6, 0.08, { mat: 'steel', color: '#3a3e42', x: sx * 0.45, y: 0.3, z: sz * 0.45 })
      b.cyl(0.62, 0.62, 1.0, { mat: 'metal', color: '#5a6066', y: 1.1, seg: 18 })
      b.cyl(0.64, 0.64, 0.06, { mat: 'steel', color: '#3a3e42', y: 1.62, seg: 18 })
      b.cyl(0.3, 0.3, 0.02, { mat: 'embers', color: '#ffffff', y: 1.64, seg: 14 })
      b.cyl(0.12, 0.12, 0.6, { mat: 'metal', color: '#3a3e42', x: 0.55, y: 0.9, rz: Math.PI / 2 })
      b.cyl(0.16, 0.16, 2.6, { mat: 'metal', color: '#4a4e52', x: -0.4, y: 2.9, z: -0.3 })
      b.cyl(0.25, 0.2, 0.1, { mat: 'metal', color: '#4a4e52', x: -0.4, y: 4.25, z: -0.3 })
      gauge(b, { x: 0.3, y: 1.3, z: 0.6 })
    })
    I.lights.push({ x: -0.9, y: 2.0, z: -0.8, color: '#ff7020', intensity: 6, dist: 9, flicker: true, when: 'active' })
    I.emitters.push({ kind: 'smoke', x: -1.3, y: 4.35, z: -1.1, when: 'active' }, { kind: 'embers', x: -0.9, y: 1.7, z: -0.8, when: 'active' })
    // power hammer
    b.at({ x: 1.0, z: -0.9 }, () => {
      b.box(0.7, 0.4, 0.7, { mat: 'paint', color: '#3a5a7a', y: 0.2 })
      b.box(0.35, 2.0, 0.35, { mat: 'paint', color: '#3a5a7a', y: 1.2, z: -0.25 })
      b.box(0.5, 0.4, 0.6, { mat: 'paint', color: '#3a5a7a', y: 2.1, z: 0 })
      b.box(0.3, 0.2, 0.3, { mat: 'steel', color: '#5a5e62', y: 0.5 })
      b.pivot('ram', { y: 1.35 }, (p) => {
        p.box(0.25, 0.4, 0.25, { mat: 'steel', color: '#4a4e52' })
        p.cyl(0.05, 0.05, 0.6, { mat: 'chrome', color: '#c8ccd0', y: 0.45 })
      })
      I.anims.push({ name: 'ram', kind: 'press', axis: 'y', amp: 0.55, speed: 3.5, when: 'active' })
    })
    // ingot molds, some still glowing
    b.at({ x: 0.3, z: 0.9 }, () => {
      table(b, { w: 1.6, d: 0.7, h: 0.7, mat: 'metal', color: '#7a7e82' })
      for (let i = 0; i < 6; i++) {
        b.box(0.22, 0.06, 0.12, { mat: 'steel', color: '#3a3e42', x: -0.6 + i * 0.24, y: 0.73 })
        b.box(0.18, 0.04, 0.08, { mat: i < 3 ? 'embers' : 'chrome', color: i < 3 ? '#ffffff' : '#c8ccd0', x: -0.6 + i * 0.24, y: 0.76 })
      }
    })
    for (let i = 0; i < 10; i++) b.box(0.18, 0.06, 0.08, { mat: 'chrome', color: '#b8bcc0', x: 1.6 + (i % 2) * 0.1, y: 0.03 + Math.floor(i / 2) * 0.06, z: 1.3 + (i % 2) * 0.02, ry: (i % 2) * Math.PI / 2 })
    gasBottle(b, { x: -1.7, z: 1.4, color: '#c83a2a' })
    I.spots.push({ x: 1.0, z: -0.15, face: FACE_BACK, anim: 'hammer' }, { x: 0.3, z: 1.45, face: FACE_BACK, anim: 'stir' })
  },

  still(b, L, I) {
    if (L === 1) {
      // copper pot still on a barrel stove, coil in a water barrel
      b.at({ x: -0.9, z: -0.3 }, () => {
        barrel(b, { color: '#3a3a38', h: 0.7, rust: 0.6 })
        b.box(0.24, 0.18, 0.04, { mat: 'embers', color: '#ffffff', y: 0.22, z: 0.29 })
        b.lathe([[0.32, 0], [0.36, 0.1], [0.36, 0.4], [0.2, 0.62], [0.08, 0.72], [0.08, 0.8]], { mat: 'metal', color: COPPER, y: 0.7, seg: 18 })
        b.tube([[0, 1.48, 0], [0.2, 1.55, 0], [0.7, 1.35, 0], [1.1, 1.05, 0]], 0.03, { mat: 'metal', color: COPPER, seg: 6, tseg: 16 })
      })
      I.flames.push({ x: -0.9, y: 0.1, z: -0.02, w: 0.3, h: 0.25, when: 'active' })
      I.lights.push({ x: -0.9, y: 0.4, z: 0.2, color: '#ff8030', intensity: 2, dist: 5, flicker: true, when: 'active' })
      barrel(b, { x: 0.35, z: -0.3, color: '#5a6a7a', open: true, contents: '#3a4a50', fill: 0.85 })
      const coil = []
      for (let i = 0; i <= 40; i++) coil.push([0.35 + Math.cos(i * 0.6) * 0.18, 0.95 - i * 0.018, -0.3 + Math.sin(i * 0.6) * 0.18])
      b.tube(coil, 0.015, { mat: 'metal', color: COPPER, seg: 5, tseg: 60 })
      b.lathe([[0.1, 0], [0.12, 0.2], [0.05, 0.3], [0.03, 0.34]], { mat: 'glass', color: '#e8d8a0', x: 0.75, z: -0.3, seg: 12 })
      for (let i = 0; i < 3; i++) {
        bucket(b, { x: -1.6 + i * 0.4, z: 0.95, mat: 'plastic', color: '#e8e4dc' })
        b.cyl(0.015, 0.015, 0.12, { mat: 'glass', color: '#e8f0e8', x: -1.6 + i * 0.4, y: 0.35, z: 0.95 })
      }
      for (let i = 0; i < 3; i++) jerrycan(b, { x: 1.1 + i * 0.22, z: 0.9, ry: 0.1 })
      sack(b, { x: 1.6, z: -0.6, ry: 0.5, color: '#c8a870' })
      I.emitters.push({ kind: 'steam', x: -0.9, y: 1.5, z: -0.3, when: 'active' })
      I.spots.push({ x: -0.9, z: 0.45, face: FACE_BACK, anim: 'stir' }, { x: 0.4, z: 0.45, face: FACE_BACK, anim: 'search' })
      return
    }
    if (L === 2) {
      // column still: boiler drum over a firebox, copper column, condenser, fermenters
      b.at({ x: -1.0, z: -0.4 }, () => {
        b.box(0.9, 0.5, 0.7, { mat: 'brick', color: '#ffffff', y: 0.25 })
        b.box(0.3, 0.2, 0.02, { mat: 'embers', color: '#ffffff', y: 0.22, z: 0.36 })
        b.cyl(0.38, 0.38, 0.8, { mat: 'metal', color: COPPER, y: 0.9, seg: 18 })
        b.sphere(0.38, { mat: 'metal', color: COPPER, y: 1.3, tl: Math.PI / 2, seg: 18 })
        b.cyl(0.13, 0.13, 1.6, { mat: 'metal', color: COPPER, y: 2.3, seg: 14 })
        for (let i = 0; i < 4; i++) b.torus(0.135, 0.012, { mat: 'metal', color: shadeHex(COPPER, -0.2), y: 1.7 + i * 0.4, rx: Math.PI / 2 })
        for (let i = 0; i < 3; i++) b.cyl(0.04, 0.04, 0.06, { mat: 'glass', color: '#e8e0b0', x: 0.13, y: 1.9 + i * 0.4, rz: Math.PI / 2 })
        b.tube([[0, 3.1, 0], [0.3, 3.25, 0], [0.9, 2.9, 0], [1.25, 2.2, 0]], 0.035, { mat: 'metal', color: COPPER, seg: 6, tseg: 14 })
        gauge(b, { y: 1.1, z: 0.39 })
      })
      I.lights.push({ x: -1.0, y: 0.4, z: 0.1, color: '#ff8030', intensity: 2, dist: 5, flicker: true, when: 'active' })
      b.at({ x: 0.25, z: -0.4 }, () => {
        b.cyl(0.18, 0.18, 1.2, { mat: 'metal', color: '#b8bcc0', y: 1.6 })
        for (const sx of [-1, 1]) b.box(0.05, 1.0, 0.05, { mat: 'steel', color: '#4a4e52', x: sx * 0.16, y: 0.5 })
        pipe(b, [[0, 1.0, 0], [0, 0.55, 0.3]], 0.025, { color: COPPER })
        jerrycan(b, { z: 0.35 })
      })
      for (let i = 0; i < 3; i++) {
        barrel(b, { x: 1.0 + (i % 2) * 0.62, z: -0.7 + Math.floor(i / 2) * 0.62 + (i === 2 ? 0 : 0), color: ['#5a4a3a', '#6a5a3a', '#4a3a2a'][i], rust: 0.3 })
        b.cyl(0.02, 0.02, 0.15, { mat: 'glass', color: '#e8f0e8', x: 1.0 + (i % 2) * 0.62, y: 0.95, z: -0.7 + Math.floor(i / 2) * 0.62 })
      }
      for (let i = 0; i < 4; i++) jerrycan(b, { x: -1.6 + i * 0.22, z: 1.0, color: i % 2 ? '#a8382a' : '#3a5a3a' })
      I.emitters.push({ kind: 'steam', x: -1.0, y: 3.2, z: -0.4, when: 'active' }, { kind: 'smoke', x: -1.0, y: 0.6, z: 0.0, when: 'active' })
      I.spots.push({ x: -1.0, z: 0.5, face: FACE_BACK, anim: 'stir' }, { x: 1.2, z: 0.5, face: FACE_BACK, anim: 'search' })
      return
    }
    // L3: steel fermenters, distillation column, fuel tank with hose
    slab(b, I, { h: 0.12 })
    for (let i = 0; i < 2; i++) {
      b.at({ x: -1.4 + i * 0.95, z: -0.6 }, () => {
        for (let k = 0; k < 3; k++) b.box(0.05, 0.6, 0.05, { mat: 'steel', color: '#8a8e92', x: Math.cos(k * 2.1) * 0.3, y: 0.3, z: Math.sin(k * 2.1) * 0.3 })
        b.capsule(0.38, 1.4, { mat: 'chrome', color: '#c8ccd0', y: 1.55, seg: 16 })
        b.cyl(0.08, 0.08, 0.1, { mat: 'chrome', color: '#a8acb0', y: 2.5 })
        gauge(b, { y: 1.5, z: 0.39 })
        valveWheel(b, { y: 0.75, z: 0.42, r: 0.07 })
      })
    }
    b.at({ x: 0.7, z: -0.7 }, () => {
      b.cyl(0.15, 0.15, 2.8, { mat: 'chrome', color: '#c0c4c8', y: 1.5 })
      for (let i = 0; i < 6; i++) b.torus(0.16, 0.015, { mat: 'steel', color: '#8a8e92', y: 0.4 + i * 0.45, rx: Math.PI / 2 })
      pipe(b, [[0, 2.8, 0], [0.4, 2.9, 0], [0.4, 0.8, 0.4]], 0.04, { flanges: true, color: '#a8acb0' })
      ladder(b, { x: 0.25, z: 0.25, h: 2.8, lean: 0, mat: 'steel', color: '#8a8e92', ry: Math.PI / 4 })
    })
    pipe(b, [[-1.4, 2.4, -0.6], [-0.45, 2.4, -0.6], [0.55, 2.4, -0.7]], 0.04, { flanges: true })
    b.at({ x: 1.0, z: 0.85 }, () => {
      for (const sx of [-1, 1]) b.box(0.2, 0.35, 0.8, { mat: 'concrete', color: '#b8b4ac', x: sx * 0.6, y: 0.18 })
      b.capsule(0.42, 1.2, { mat: 'paint', color: '#c83a2a', y: 0.8, rz: Math.PI / 2, seg: 16 })
      b.box(0.5, 0.02, 0.3, { mat: 'plain', color: '#e8e4dc', y: 1.2, z: 0.0, ao: 0 })
      b.rope([[-0.9, 0.8, 0.3], [-1.3, 0.3, 0.6], [-1.5, 0.9, 0.5]], 0.025, { mat: 'rubber', color: '#1a1a1a', sag: 0.2 })
    })
    I.emitters.push({ kind: 'steam', x: 0.7, y: 3.0, z: -0.7, when: 'active' })
    I.lights.push({ x: 0, y: 2.6, z: 0.4, color: '#fff0d0', intensity: 2.5, dist: 7, when: 'night' })
    I.spots.push({ x: -0.9, z: 0.3, face: FACE_BACK, anim: 'type' }, { x: 0.3, z: 0.6, face: FACE_BACK, anim: 'search' })
  },

  generator(b, L, I) {
    if (L === 1) {
      pallet(b, { x: -0.3, z: -0.1 })
      b.pivot('engine', { x: -0.3, y: 0.127, z: -0.1 }, (p) => generatorSmall(p, {}))
      I.anims.push({ name: 'engine', kind: 'shake', amp: 0.01, when: 'active' })
      I.emitters.push({ kind: 'exhaust', x: 0.1, y: 0.35, z: -0.22, when: 'active' })
      for (let i = 0; i < 4; i++) jerrycan(b, { x: 0.8 + (i % 2) * 0.22, z: -0.6 + Math.floor(i / 2) * 0.4, color: i % 3 ? '#a8382a' : '#3a5a3a' })
      cableReel(b, { x: -1.2, z: 0.6, ry: 0.4, color: '#d8a020' })
      b.rope([[-0.6, 0.35, -0.1], [-1.5, 0.05, -0.8], [-1.95, 0.05, -1.4]], 0.015, { mat: 'rubber', color: '#d8a020', sag: 0 })
      b.box(0.4, 0.5, 0.25, { mat: 'paint', color: '#5a6066', x: 1.6, y: 0.25, z: 0.6 })
      b.box(0.06, 0.06, 0.02, { mat: 'glowGreen', color: '#ffffff', x: 1.55, y: 0.38, z: 0.73 })
      I.spots.push({ x: -0.3, z: 0.75, face: FACE_BACK, anim: 'search' })
      return
    }
    if (L === 2) {
      // open-frame diesel genset on skids
      b.at({ x: -0.2, z: -0.2 }, () => {
        for (const sz of [-1, 1]) b.box(2.6, 0.15, 0.12, { mat: 'paint', color: '#2a2a2a', y: 0.08, z: sz * 0.5 })
        b.box(2.4, 0.3, 1.0, { mat: 'paint', color: '#3a3e42', y: 0.3 })
        b.pivot('engine', { y: 0.45 }, (p) => {
          p.box(1.0, 0.7, 0.7, { mat: 'paint', color: '#2a6a3a', x: -0.3, y: 0.35 })
          p.box(0.6, 0.6, 0.6, { mat: 'paint', color: '#3a7a4a', x: 0.6, y: 0.3 })
          for (let i = 0; i < 4; i++) p.box(0.12, 0.1, 0.12, { mat: 'steel', color: '#5a5e62', x: -0.65 + i * 0.22, y: 0.75 })
          p.cyl(0.25, 0.25, 0.08, { mat: 'steel', color: '#5a5e62', x: -0.85, y: 0.35, rz: Math.PI / 2 })
        })
        I.anims.push({ name: 'engine', kind: 'shake', amp: 0.008, when: 'active' })
        b.box(0.1, 0.9, 0.9, { mat: 'paint', color: '#2a2a2a', x: -1.15, y: 0.9 })
        for (let i = 0; i < 8; i++) b.box(0.02, 0.8, 0.02, { mat: 'steel', color: '#5a5e62', x: -1.2, y: 0.9, z: -0.35 + i * 0.1 })
        b.cyl(0.06, 0.06, 1.4, { mat: 'metal', color: '#4a4a4a', x: 0.2, y: 1.6, z: -0.3 })
        b.box(0.5, 0.6, 0.15, { mat: 'paint', color: '#d8d8d0', x: 1.0, y: 1.1, z: 0.4 })
        for (let i = 0; i < 3; i++) gauge(b, { x: 0.85 + i * 0.15, y: 1.2, z: 0.48 })
        b.box(0.05, 0.05, 0.02, { mat: 'glowGreen', color: '#ffffff', x: 0.9, y: 0.95, z: 0.48 })
        b.box(0.05, 0.05, 0.02, { mat: 'glowRed', color: '#ffffff', x: 1.05, y: 0.95, z: 0.48 })
      })
      I.emitters.push({ kind: 'exhaust', x: 0.0, y: 2.35, z: -0.5, when: 'active' })
      ibcTote(b, { x: 1.3, z: -0.35, ry: Math.PI / 2, level: 0.5 })
      for (let i = 0; i < 2; i++) barrel(b, { x: -1.5 + i * 0.62, z: 1.0, color: '#a8382a' })
      b.box(0.12, 2.6, 0.12, { mat: 'wood', color: COL.woodGrey, x: 1.7, y: 1.3, z: 1.2 })
      b.rope([[0.8, 1.1, 0.2], [1.7, 2.4, 1.2], [2.6, 2.6, 2.2]], 0.015, { mat: 'rubber', color: '#1a1a1a', sag: 0.2 })
      I.spots.push({ x: 0.9, z: 0.75, face: FACE_BACK, anim: 'type' })
      return
    }
    // L3: enclosed industrial genset, transformer, fuel tank
    slab(b, I, { h: 0.15 })
    b.at({ x: -0.5, z: -0.25 }, () => {
      b.pivot('engine', { y: 0.15 }, (p) => {
        p.box(2.6, 1.5, 1.2, { mat: 'paint', color: '#e0a820', y: 0.75 })
        for (let i = 0; i < 9; i++) p.box(0.02, 0.6, 0.02, { mat: 'paint', color: '#8a6a10', x: -1.3, y: 0.8, z: -0.4 + i * 0.1 })
        for (let i = 0; i < 6; i++) p.box(1.0, 0.03, 0.02, { mat: 'paint', color: '#a87a10', x: 0.4, y: 0.5 + i * 0.12, z: 0.61 })
        for (const x of [-0.8, -0.1]) p.box(0.6, 1.2, 0.02, { mat: 'paint', color: '#d8a018', x, y: 0.75, z: 0.61 })
        p.box(0.25, 0.04, 0.02, { mat: 'steel', color: '#5a5a5a', x: -0.8, y: 0.85, z: 0.63 })
        p.box(0.25, 0.04, 0.02, { mat: 'steel', color: '#5a5a5a', x: -0.1, y: 0.85, z: 0.63 })
        p.box(1.0, 0.25, 0.02, { mat: 'paint', color: '#1a1a1a', x: 0.0, y: 1.62, z: 0.45, rx: -0.6 })
      })
      I.anims.push({ name: 'engine', kind: 'shake', amp: 0.004, when: 'active' })
      b.cyl(0.1, 0.1, 1.5, { mat: 'metal', color: '#5a5a5a', x: 1.0, y: 2.3, z: -0.3 })
      b.cyl(0.16, 0.12, 0.1, { mat: 'metal', color: '#5a5a5a', x: 1.0, y: 3.1, z: -0.3 })
      b.box(0.55, 0.7, 0.12, { mat: 'paint', color: '#d8d8d0', x: 0.9, y: 1.0, z: 0.68 })
      b.plane(0.3, 0.2, { material: screenMat('green'), x: 0.9, y: 1.15, z: 0.745 })
      b.box(0.05, 0.05, 0.02, { mat: 'glowGreen', color: '#ffffff', x: 0.8, y: 0.85, z: 0.75 })
      b.box(0.05, 0.05, 0.02, { mat: 'glowAmber', color: '#ffffff', x: 0.95, y: 0.85, z: 0.75 })
    })
    I.emitters.push({ kind: 'exhaust', x: 0.5, y: 3.2, z: -0.55, when: 'active' })
    b.at({ x: 1.5, z: -0.6 }, () => {
      b.box(0.8, 1.1, 0.7, { mat: 'paint', color: '#5a6a5a', y: 0.7 })
      for (let i = 0; i < 6; i++) b.box(0.06, 0.9, 0.04, { mat: 'paint', color: '#4a5a4a', x: -0.4, y: 0.7, z: -0.25 + i * 0.1 })
      for (let i = 0; i < 3; i++) b.cyl(0.05, 0.06, 0.25, { mat: 'plain', color: '#7a5a3a', x: -0.2 + i * 0.2, y: 1.38 })
      hazardSign(b, { y: 0.9, z: 0.36 })
    })
    b.at({ x: -0.6, z: 1.1 }, () => {
      for (const sx of [-1, 1]) b.box(0.15, 0.3, 0.7, { mat: 'paint', color: '#3a3a3a', x: sx * 0.7, y: 0.15 })
      b.capsule(0.35, 1.3, { mat: 'paint', color: '#e8e4dc', y: 0.62, rz: Math.PI / 2, seg: 14 })
      b.box(0.5, 0.12, 0.002, { mat: 'plain', color: '#c83a2a', y: 0.62, z: 0.351, ao: 0 })
    })
    I.lights.push({ x: 0.3, y: 2.4, z: 0.6, color: '#fff0d0', intensity: 3, dist: 8, when: 'night' })
    bulb(b, { x: 0.3, y: 2.0, z: 0.4, r: 0.06 })
    b.box(0.08, 2.1, 0.08, { mat: 'steel', color: '#5a5e62', x: 0.3, y: 1.05, z: 0.3 })
    I.spots.push({ x: 0.4, z: 0.95, face: FACE_BACK, anim: 'type' })
  },

  solar(b, L, I) {
    if (L === 1) {
      for (let i = 0; i < 4; i++) {
        const x = -1.6 + (i % 2) * 1.2 + Math.floor(i / 2) * 2.0
        b.at({ x, z: -0.5 }, () => {
          for (const sx of [-1, 1]) {
            b.beam([sx * 0.45, 0, -0.55], [sx * 0.45, 1.25, 0.1], 0.06, 0.06, { mat: 'wood', color: COL.woodGrey })
            b.beam([sx * 0.45, 0, 0.6], [sx * 0.45, 0.62, 0.55], 0.06, 0.06, { mat: 'wood', color: COL.woodGrey })
          }
          solarPanel(b, { y: 0.95, z: 0.05, w: 0.95, h: 1.45, tilt: -0.6 })
        })
      }
      b.at({ x: 1.5, z: 1.3 }, () => {
        b.box(0.9, 0.35, 0.55, { mat: 'wood', color: '#c8b498', y: 0.18 })
        for (let i = 0; i < 3; i++) carBattery(b, { x: -0.3 + i * 0.3, y: 0.36, z: 0, ry: Math.PI / 2 })
        b.box(0.06, 1.2, 0.06, { mat: 'wood', color: COL.woodGrey, x: -0.6, y: 0.6 })
        b.box(0.22, 0.3, 0.08, { mat: 'paint', color: '#2a4a8a', x: -0.6, y: 1.0, z: 0.06 })
        b.box(0.04, 0.04, 0.02, { mat: 'glowGreen', color: '#ffffff', x: -0.6, y: 1.08, z: 0.11 })
      })
      b.rope([[-1.6, 0.4, -0.2], [-0.4, 0.05, 0.8], [0.9, 0.36, 1.3]], 0.012, { mat: 'rubber', color: '#1a1a1a', sag: 0 })
      return
    }
    // L2: eight panels on a steel rack, battery cabinet and inverter
    slab(b, I, { h: 0.1 })
    for (let row = 0; row < 2; row++) {
      const z = -1.2 + row * 1.5
      for (const x of [-2.0, -0.7, 0.6, 1.9]) {
        b.box(0.08, 0.8, 0.08, { mat: 'steel', color: '#9aa0a6', x, y: 0.4, z: z - 0.4 })
        b.box(0.08, 1.4, 0.08, { mat: 'steel', color: '#9aa0a6', x, y: 0.7, z: z + 0.35 })
      }
      b.box(4.1, 0.06, 0.06, { mat: 'steel', color: '#9aa0a6', y: 0.8, z: z - 0.4 })
      b.box(4.1, 0.06, 0.06, { mat: 'steel', color: '#9aa0a6', y: 1.4, z: z + 0.35 })
      for (let i = 0; i < 4; i++) solarPanel(b, { x: -1.55 + i * 1.03, y: 1.15, z: z - 0.02, w: 0.98, h: 0.95, tilt: 0.55 })
    }
    b.at({ x: 2.05, z: 1.5 }, () => {
      b.box(0.8, 1.1, 0.5, { mat: 'paint', color: '#c8ccd0', y: 0.6 })
      for (let i = 0; i < 4; i++) b.box(0.02, 0.9, 0.02, { mat: 'steel', color: '#8a8e92', x: -0.2 + i * 0.13, y: 0.6, z: 0.26 })
      b.box(0.05, 0.05, 0.02, { mat: 'glowGreen', color: '#ffffff', x: 0.3, y: 1.0, z: 0.26 })
      hazardSign(b, { y: 0.8, z: 0.26, x: 0.25 })
    })
  },

  chemlab(b, L, I) {
    const rnd = seeded(501)
    if (L === 1) {
      roof(b, I, (r) => tarp(b, { rb: r, w: 4.2, d: 3.0, corners: [2.2, 2.2, 1.95, 1.95], color: '#8a7a5a', seed: 17 }))
      table(b, { x: -0.6, z: -0.5, w: 1.8, d: 0.8, h: 0.78, color: '#d8d0c0' })
      glassware(b, -0.9, 0.8, -0.5, rnd, 6)
      b.at({ x: -0.15, y: 0.8, z: -0.4 }, () => {
        b.cyl(0.08, 0.1, 0.1, { mat: 'paint', color: '#3a6a3a', y: 0.05 })
        b.cone(0.035, 0.08, { mat: 'glowBlue', color: '#ffffff', y: 0.14, seg: 8 })
        b.lathe([[0.08, 0], [0.09, 0.05], [0.03, 0.14], [0.03, 0.2]], { mat: 'glass', color: '#e8f0f0', y: 0.18, seg: 12 })
      })
      I.lights.push({ x: -0.6, y: 1.2, z: -0.3, color: '#90ff90', intensity: 0.8, dist: 3, when: 'active' })
      for (let i = 0; i < 5; i++) b.lathe([[0.1, 0], [0.1, 0.32], [0.04, 0.38], [0.04, 0.44]], { mat: 'plastic', color: ['#e8e4dc', '#3a6ab0', '#e8c030', '#c83a2a', '#e8e4dc'][i], x: 1.0 + (i % 3) * 0.26, z: -0.9 + Math.floor(i / 3) * 0.3, seg: 10 })
      barrel(b, { x: 1.4, z: 0.6, color: '#3a6a3a', open: true, contents: '#5a7a2a' })
      b.beam([1.4, 0.6, 0.6], [1.55, 1.2, 0.7], 0.03, 0.03, { mat: 'wood', color: '#c8a070', round: true })
      hazardSign(b, { x: -1.9, y: 1.5, z: -1.4 })
      b.box(0.04, 1.5, 0.04, { mat: 'wood', color: COL.woodGrey, x: -1.9, y: 0.75, z: -1.45 })
      b.capsule(0.08, 0.3, { mat: 'paint', color: '#c82a24', x: -1.7, y: 0.25, z: 0.9 })
      I.spots.push({ x: -0.6, z: 0.2, face: FACE_BACK, anim: 'stir' }, { x: 1.4, z: 1.2, face: FACE_BACK, anim: 'stir' })
      return
    }
    if (L === 2) {
      // wooden lab hut with an open front, a vent fan and shelves of reagents
      b.box(4.8, 0.1, 3.6, { mat: 'planks', color: '#e0d6c4', y: 0.05 })
      boardWall(b, -2.4, 2.4, -1.75, 2.6, { y0: 0.1, seed: 31, color: '#d0c8b4' })
      b.at({ ry: Math.PI / 2 }, () => {
        boardWall(b, -1.8, 1.4, 2.35, 2.6, { y0: 0.1, seed: 32, color: '#d0c8b4' })
        boardWall(b, -1.8, 1.4, -2.35, 2.6, { y0: 0.1, seed: 33, color: '#d0c8b4', holes: [[-0.5, 0.3, 1.2, 1.9]] })
      })
      frame(b, 4.7, 3.4, 2.7, { braces: false, z: -0.1 })
      roof(b, I, (r) => corrRoof(r, { w: 5.0, d: 3.8, y: 2.95, drop: 0.35, z: -0.1 }))
      for (let i = 0; i < 2; i++) shelf(b, { x: -1.4 + i * 1.5, z: -1.45, w: 1.35, h: 1.9, d: 0.4, seed: 90 + i, kind: 'chem', fill: 0.9 })
      table(b, { x: 0.3, z: 0.1, w: 2.2, d: 0.8, h: 0.85, color: '#c8c4bc' })
      glassware(b, 0.0, 0.87, 0.1, rnd, 6)
      // distillation train on the table
      b.at({ x: 0.9, y: 0.87, z: 0.1 }, () => {
        b.sphere(0.1, { mat: 'glass', color: '#e8f0f0', y: 0.25 })
        b.sphere(0.085, { mat: 'glowGreen', color: '#7af090', y: 0.22, sy: 0.6 })
        b.cyl(0.006, 0.006, 0.5, { mat: 'steel', color: '#8a8e92', x: -0.15, y: 0.25 })
        b.tube([[0, 0.35, 0], [0.15, 0.42, 0], [0.4, 0.3, 0]], 0.012, { mat: 'glass', color: '#e8f0f0', tseg: 10 })
      })
      I.lights.push({ x: 0.6, y: 1.3, z: 0.3, color: '#80ff90', intensity: 1, dist: 4, when: 'active' })
      // vent fan in the side wall
      b.at({ x: 2.38, y: 1.6, z: -0.5 }, () => {
        b.box(0.06, 0.7, 0.7, { mat: 'metal', color: '#5a6066' })
        b.pivot('fan', { x: 0.05 }, (p) => {
          for (let i = 0; i < 4; i++) p.box(0.01, 0.55, 0.12, { mat: 'metal', color: '#8a8e92', rx: (i * Math.PI) / 4 + 0.3 })
        })
        I.anims.push({ name: 'fan', kind: 'spin', axis: 'x', speed: 8, when: 'active' })
      })
      for (let i = 0; i < 3; i++) barrel(b, { x: 1.9, z: 0.6 + i * 0.0 - 0.0 + i * 0.6 - 0.6, color: ['#e8c030', '#3a6ab0', '#c83a2a'][i], label: '#1a1a1a' })
      hazardSign(b, { x: -2.38, y: 1.7, z: 0.6, ry: Math.PI / 2 })
      lantern(b, { x: 0.3, y: 2.2, z: 0.1 })
      I.lights.push({ x: 0.3, y: 2.3, z: 0.1, color: '#ffe0b0', intensity: 2.5, dist: 7, when: 'night' })
      I.spots.push({ x: 0.0, z: 0.85, face: FACE_BACK, anim: 'stir' }, { x: 0.9, z: 0.85, face: FACE_BACK, anim: 'stir' })
      return
    }
    // L3: steel lab: reactor vessel, fume hood, duct fan, safety shower
    slab(b, I, { h: 0.12 })
    b.box(4.8, 2.8, 0.1, { mat: 'corrugated', color: '#c8d0d4', y: 1.5, z: -1.8 })
    b.box(0.1, 2.8, 3.4, { mat: 'corrugated', color: '#c8d0d4', x: -2.35, y: 1.5, z: -0.1 })
    frame(b, 4.7, 3.4, 2.9, { braces: false, metal: true, color: '#8a9096', z: -0.1 })
    roof(b, I, (r) => corrRoof(r, { w: 5.0, d: 3.8, y: 3.15, drop: 0.3, z: -0.1, color: '#b8c0c4' }))
    b.at({ x: -1.2, z: -0.9 }, () => {
      for (let k = 0; k < 4; k++) b.box(0.06, 0.8, 0.06, { mat: 'steel', color: '#8a8e92', x: k % 2 ? 0.4 : -0.4, y: 0.4, z: k < 2 ? 0.4 : -0.4 })
      b.capsule(0.5, 0.9, { mat: 'chrome', color: '#d0d4d8', y: 1.45, seg: 18 })
      b.cyl(0.52, 0.52, 0.6, { mat: 'paint', color: '#3a6ab0', y: 1.3, seg: 18 })
      b.cyl(0.07, 0.07, 0.6, { mat: 'chrome', color: '#a8acb0', y: 2.3 })
      gauge(b, { y: 1.75, z: 0.5 })
      gauge(b, { y: 1.75, x: 0.25, z: 0.45, ry: 0.5 })
      valveWheel(b, { y: 0.95, z: 0.55, r: 0.09 })
    })
    pipe(b, [[-1.2, 2.6, -0.9], [-1.2, 2.75, -1.5], [1.5, 2.75, -1.5]], 0.06, { flanges: true })
    // fume hood
    b.at({ x: 1.1, z: -1.3 }, () => {
      b.box(1.8, 0.9, 0.8, { mat: 'steel', color: '#c8ccd0', y: 0.45 })
      b.box(1.8, 1.2, 0.05, { mat: 'steel', color: '#b8bcc0', y: 1.5, z: -0.38 })
      for (const sx of [-1, 1]) b.box(0.05, 1.2, 0.8, { mat: 'steel', color: '#b8bcc0', x: sx * 0.88, y: 1.5 })
      b.box(1.8, 0.3, 0.8, { mat: 'steel', color: '#b8bcc0', y: 2.25 })
      b.box(1.7, 0.6, 0.02, { mat: 'glass', color: '#e8f4f4', y: 1.6, z: 0.38 })
      glassware(b, 1.1 - 1.1 - 0.3, 0.9, 0, rnd, 6)
      b.box(0.5, 0.6, 0.5, { mat: 'metal', color: '#8a9096', y: 2.7 })
    })
    I.lights.push({ x: 1.1, y: 1.8, z: -1.0, color: '#d0fff0', intensity: 1.5, dist: 4, when: 'active' })
    b.at({ x: 2.3, y: 2.4, z: 0.6 }, () => {
      b.box(0.08, 0.8, 0.8, { mat: 'metal', color: '#5a6066' })
      b.pivot('fan', { x: 0.06 }, (p) => {
        for (let i = 0; i < 5; i++) p.box(0.01, 0.6, 0.12, { mat: 'metal', color: '#9aa0a6', rx: (i * TAU) / 5 })
      })
      I.anims.push({ name: 'fan', kind: 'spin', axis: 'x', speed: 10, when: 'active' })
    })
    // safety shower
    b.at({ x: -2.0, z: 1.2 }, () => {
      b.cyl(0.03, 0.03, 2.4, { mat: 'paint', color: '#2a9a4a', y: 1.2 })
      b.cyl(0.03, 0.03, 0.4, { mat: 'paint', color: '#2a9a4a', y: 2.4, x: 0.2, rz: Math.PI / 2 })
      b.cone(0.12, 0.1, { mat: 'paint', color: '#2a9a4a', x: 0.4, y: 2.3, seg: 12 })
      b.box(0.25, 0.25, 0.01, { mat: 'paint', color: '#2a9a4a', y: 1.8, z: 0.04 })
    })
    for (let i = 0; i < 3; i++) barrel(b, { x: 1.5 + (i % 2) * 0.6, z: 0.6 + Math.floor(i / 2) * 0.62, color: ['#3a6ab0', '#e8c030', '#2a2a2a'][i], label: '#e8e4dc', rust: 0.1 })
    I.lights.push({ x: 0, y: 2.6, z: 0.2, color: '#f0fff8', intensity: 3.5, dist: 9, when: 'night' })
    I.spots.push({ x: -1.2, z: 0.1, face: FACE_BACK, anim: 'type' }, { x: 1.1, z: -0.55, face: FACE_BACK, anim: 'stir' })
  },

  workbench(b, L, I) {
    const rnd = seeded(601)
    if (L === 1) {
      table(b, { x: -0.3, z: -0.55, w: 2.2, d: 0.8, h: 0.86, color: '#d8c4a4', shelf: true })
      vise(b, { x: 0.65, y: 0.86, z: -0.2 })
      b.at({ y: 0.86 }, () => {
        b.box(0.03, 0.03, 0.28, { mat: 'wood', color: '#c89a60', x: -0.6, y: 0.015, z: -0.4, ry: 0.3 })
        b.box(0.1, 0.035, 0.03, { mat: 'steel', color: '#4a4e52', x: -0.57, y: 0.02, z: -0.27, ry: 0.3 })
        for (let i = 0; i < 4; i++) b.box(0.6, 0.025, 0.12, { mat: 'wood', color: '#f0dcb8', x: -0.1 + rnd() * 0.2, y: 0.013 + i * 0.026, z: -0.7, ry: rnd() * 0.1 })
        for (let i = 0; i < 8; i++) b.cyl(0.004, 0.004, 0.05, { mat: 'steel', color: '#8a8e92', x: 0.2 + rnd() * 0.3, y: 0.004, z: -0.3 + rnd() * 0.2, rz: Math.PI / 2, ry: rnd() * 3 })
      })
      for (const sx of [-1, 1]) b.box(0.08, 2.0, 0.08, { mat: 'wood', color: COL.woodGrey, x: -0.3 + sx * 1.0, y: 1.0, z: -1.15 })
      toolWall(b, { x: -0.3, y: 1.0, z: -1.13, w: 2.0, h: 0.85, seed: 3 })
      sawhorse(b, { x: 1.4, z: 0.6, len: 0.9, ry: Math.PI / 2 })
      toolbox(b, { x: -1.5, z: 0.4, ry: 0.5 })
      for (let i = 0; i < 5; i++) b.box(0.9 + rnd() * 0.6, 0.03, 0.12, { mat: 'wood', color: '#e8d4b0', x: -1.4, y: 0.4 + i * 0.03, z: -0.2 + i * 0.02, rz: 1.2, ry: 0.1 * i })
      I.spots.push({ x: 0.3, z: 0.15, face: FACE_BACK, anim: 'hammer' }, { x: 1.4, z: 1.15, face: FACE_BACK, anim: 'saw' })
      return
    }
    if (L === 2) {
      frame(b, 3.8, 2.6, 2.6, { hFront: 2.8, braces: true, z: -0.1 })
      roof(b, I, (r) => corrRoof(r, { w: 4.1, d: 2.9, y: 2.95, drop: 0.3, z: -0.1 }))
      b.box(3.8, 0.04, 2.6, { mat: 'planks', color: '#e0d6c4', y: 0.02, z: -0.1 })
      table(b, { x: -0.2, z: -0.85, w: 3.2, d: 0.8, h: 0.9, color: '#c8b494', shelf: true })
      toolWall(b, { x: -0.2, y: 1.05, z: -1.33, w: 3.2, h: 1.0, seed: 7 })
      vise(b, { x: 1.1, y: 0.9, z: -0.55 })
      // drill press
      b.at({ x: -0.6, y: 0.9, z: -0.9 }, () => {
        b.box(0.25, 0.04, 0.3, { mat: 'paint', color: '#2a5a3a', y: 0.02 })
        b.cyl(0.03, 0.03, 0.8, { mat: 'chrome', color: '#c8ccd0', y: 0.4, z: -0.1 })
        b.box(0.18, 0.2, 0.32, { mat: 'paint', color: '#2a5a3a', y: 0.75 })
        b.box(0.12, 0.08, 0.12, { mat: 'paint', color: '#2a5a3a', y: 0.35, z: 0.04 })
        b.cyl(0.006, 0.006, 0.12, { mat: 'chrome', color: '#c8ccd0', y: 0.58, z: 0.08 })
      })
      // bench grinder with a spinning wheel and sparks
      b.at({ x: 0.3, y: 0.9, z: -0.9 }, () => {
        b.box(0.18, 0.12, 0.14, { mat: 'paint', color: '#4a6a9a', y: 0.08 })
        b.pivot('grinder', { y: 0.1 }, (p) => {
          for (const sx of [-1, 1]) p.cyl(0.08, 0.08, 0.03, { mat: 'concrete', color: '#a8a098', x: sx * 0.13, rz: Math.PI / 2, seg: 14 })
        })
        I.anims.push({ name: 'grinder', kind: 'spin', axis: 'x', speed: 25, when: 'active' })
      })
      I.emitters.push({ kind: 'sparks', x: 0.43, y: 1.0, z: -0.82, when: 'active' })
      shelf(b, { x: 1.7, z: 0.0, ry: -Math.PI / 2, w: 1.4, h: 1.8, d: 0.45, seed: 22, kind: 'parts', metal: true, color: '#5a6a7a' })
      lantern(b, { x: -0.2, y: 2.25, z: -0.5 })
      I.lights.push({ x: -0.2, y: 2.3, z: -0.4, color: '#ffd8a0', intensity: 2.5, dist: 7, when: 'night' })
      plankStack(b, { x: -1.3, z: 1.05, len: 1.4, w: 0.5, layers: 3 })
      I.spots.push({ x: 0.9, z: -0.1, face: FACE_BACK, anim: 'hammer' }, { x: -0.6, z: -0.15, face: FACE_BACK, anim: 'saw' })
      return
    }
    // L3: a proper workshop: lathe, welding cart, drill press, shelving, lights
    slab(b, I, { h: 0.12 })
    b.box(4.0, 2.9, 0.08, { mat: 'corrugated', color: '#b8c0c4', y: 1.55, z: -1.45 })
    b.box(0.08, 2.9, 2.9, { mat: 'corrugated', color: '#b8c0c4', x: -1.95, y: 1.55 })
    frame(b, 3.9, 2.9, 3.0, { braces: false, metal: true, color: '#5a6066' })
    roof(b, I, (r) => corrRoof(r, { w: 4.3, d: 3.2, y: 3.15, drop: 0.25, color: '#a8b0b4' }))
    // lathe
    b.at({ x: -0.6, z: -1.0 }, () => {
      for (const sx of [-1, 1]) b.box(0.3, 0.8, 0.4, { mat: 'paint', color: '#3a5a7a', x: sx * 0.7, y: 0.4 })
      b.box(1.8, 0.15, 0.35, { mat: 'paint', color: '#3a5a7a', y: 0.88 })
      b.box(0.4, 0.4, 0.4, { mat: 'paint', color: '#3a5a7a', x: -0.75, y: 1.15 })
      b.pivot('chuck', { x: -0.48, y: 1.15 }, (p) => {
        p.cyl(0.12, 0.12, 0.12, { mat: 'chrome', color: '#c8ccd0', rz: Math.PI / 2, seg: 12 })
        for (let i = 0; i < 3; i++) p.box(0.05, 0.05, 0.05, { mat: 'steel', color: '#5a5e62', y: Math.cos((i * TAU) / 3) * 0.1, z: Math.sin((i * TAU) / 3) * 0.1, x: 0.07 })
        p.cyl(0.03, 0.03, 0.6, { mat: 'chrome', color: '#d8dce0', x: 0.35, rz: Math.PI / 2 })
      })
      I.anims.push({ name: 'chuck', kind: 'spin', axis: 'x', speed: 20, when: 'active' })
      b.box(0.25, 0.25, 0.3, { mat: 'paint', color: '#3a5a7a', x: 0.6, y: 1.08 })
    })
    // welding cart
    b.at({ x: 1.3, z: -0.8 }, () => {
      b.box(0.5, 0.04, 0.6, { mat: 'steel', color: '#5a5e62', y: 0.3 })
      b.box(0.5, 0.5, 0.55, { mat: 'paint', color: '#2a4a8a', y: 0.6 })
      gasBottle(b, { x: -0.1, z: -0.3, color: '#3a7a3a' })
      gasBottle(b, { x: 0.15, z: -0.3, color: '#c8c8c0' })
      b.rope([[0.2, 0.7, 0.3], [-0.2, 0.3, 0.7], [-0.5, 0.9, 0.5]], 0.02, { mat: 'rubber', color: '#c83a2a', sag: 0.1 })
    })
    I.emitters.push({ kind: 'weld', x: 0.8, y: 0.95, z: -0.2, when: 'active' })
    I.lights.push({ x: 0.8, y: 1.0, z: -0.2, color: '#a0d0ff', intensity: 3, dist: 4, flicker: true, when: 'active' })
    table(b, { x: 0.8, z: -0.2, w: 1.0, d: 0.6, h: 0.85, mat: 'metal', color: '#6a6e72' })
    shelf(b, { x: -1.6, z: 0.5, ry: Math.PI / 2, w: 1.6, h: 2.2, d: 0.45, seed: 24, kind: 'parts', metal: true, color: '#c8a020' })
    toolWall(b, { x: 0.7, y: 1.3, z: -1.4, w: 1.8, h: 0.9, seed: 11 })
    for (const x of [-0.8, 0.8]) {
      b.box(0.9, 0.06, 0.15, { mat: 'paint', color: '#e8e8e0', x, y: 2.85, z: 0 })
      b.box(0.8, 0.02, 0.08, { mat: 'glowWhite', color: '#ffffff', x, y: 2.81, z: 0 })
    }
    I.lights.push({ x: 0, y: 2.6, z: 0, color: '#f0f4ff', intensity: 4, dist: 8, when: 'night' })
    I.spots.push({ x: -0.6, z: -0.45, face: FACE_BACK, anim: 'type' }, { x: 0.8, z: 0.35, face: FACE_BACK, anim: 'hammer' })
  },

  weapons(b, L, I) {
    const rnd = seeded(701)
    if (L === 1) {
      table(b, { x: -0.6, z: -0.4, w: 2.2, d: 0.8, h: 0.86, color: '#c8b498' })
      vise(b, { x: 0.2, y: 0.86, z: -0.3 })
      rifleProp(b, { x: 0.2, y: 1.02, z: -0.3, ry: Math.PI / 2 })
      for (let i = 0; i < 3; i++) b.cyl(0.006, 0.006, 0.8, { mat: 'steel', color: '#8a8e92', x: -1.2 + i * 0.05, y: 0.88, z: -0.6, rz: Math.PI / 2 })
      for (let i = 0; i < 3; i++) ammoCrate(b, { x: -1.3 + i * 0.08, y: 0.86 + i * 0.0, z: -0.25 + i * 0.0, ry: 0.1 * i })
      pistolProp(b, { x: -0.5, y: 0.88, z: -0.3 })
      // gun rack
      b.at({ x: 1.6, z: -0.6, ry: -Math.PI / 2 }, () => {
        b.box(1.4, 0.15, 0.3, { mat: 'wood', color: '#a8845a', y: 0.08 })
        b.box(1.4, 0.1, 0.12, { mat: 'wood', color: '#a8845a', y: 1.15, z: -0.1 })
        for (const sx of [-1, 1]) b.box(0.08, 1.3, 0.08, { mat: 'wood', color: '#a8845a', x: sx * 0.66, y: 0.65, z: -0.12 })
        for (let i = 0; i < 4; i++) rifleProp(b, { x: -0.45 + i * 0.3, y: 0.62, z: 0.02, rx: -Math.PI / 2 + 0.12, scope: i === 2 })
      })
      I.spots.push({ x: 0.2, z: 0.35, face: FACE_BACK, anim: 'hammer' })
      return
    }
    if (L === 2) {
      frame(b, 4.6, 2.6, 2.6, { hFront: 2.8, z: -0.1 })
      roof(b, I, (r) => corrRoof(r, { w: 4.9, d: 2.9, y: 2.95, drop: 0.3, z: -0.1 }))
      boardWall(b, -2.3, 2.3, -1.35, 2.6, { seed: 41, color: '#c8bca8' })
      table(b, { x: -0.8, z: -0.85, w: 2.4, d: 0.8, h: 0.9, color: '#b8a488', shelf: true })
      vise(b, { x: 0.1, y: 0.9, z: -0.6 })
      rifleProp(b, { x: 0.1, y: 1.06, z: -0.6, ry: Math.PI / 2, scope: true })
      // wall rack of rifles and pistols
      b.at({ x: -0.8, y: 1.2, z: -1.3 }, () => {
        b.box(2.2, 1.2, 0.04, { mat: 'wood', color: '#6a4a32', y: 0.5 })
        for (let i = 0; i < 5; i++) rifleProp(b, { x: -0.85 + i * 0.42, y: 0.55, z: 0.06, rx: -Math.PI / 2, mag: i % 2 === 1, scope: i === 4 })
        for (let i = 0; i < 4; i++) pistolProp(b, { x: -0.8 + i * 0.5, y: 1.0, z: 0.06, ry: Math.PI / 2, rz: 0 })
      })
      // reloading press on the right
      b.at({ x: 1.4, z: -0.85 }, () => {
        table(b, { w: 1.2, d: 0.7, h: 0.9, color: '#b8a488' })
        b.box(0.12, 0.35, 0.12, { mat: 'paint', color: '#c83a2a', y: 1.08 })
        b.beam([0, 1.2, 0.05], [0.2, 1.45, 0.35], 0.02, 0.02, { mat: 'chrome', color: '#c8ccd0', round: true })
        for (let i = 0; i < 2; i++) ammoCrate(b, { x: -0.3, y: 0.9 + i * 0.3, z: 0.0, ry: 0.2 })
      })
      toolbox(b, { x: -2.0, z: 0.6, ry: 0.3, color: '#2a4a8a' })
      // target paper pinned to the post
      b.box(0.4, 0.55, 0.01, { mat: 'paint', color: '#f0f0e8', x: 2.25, y: 1.6, z: 1.2, ry: -Math.PI / 2 })
      b.cyl(0.12, 0.12, 0.012, { mat: 'plain', color: '#1a1a1a', x: 2.24, y: 1.65, z: 1.2, rz: Math.PI / 2, seg: 14 })
      lantern(b, { x: -0.5, y: 2.2, z: -0.4 })
      I.lights.push({ x: -0.5, y: 2.3, z: -0.3, color: '#ffd8a0', intensity: 2.5, dist: 7, when: 'night' })
      I.spots.push({ x: 0.1, z: -0.05, face: FACE_BACK, anim: 'hammer' }, { x: 1.4, z: -0.2, face: FACE_BACK, anim: 'pump' })
      return
    }
    // L3: armory workshop: steel cabinets, milling machine, test barrel
    slab(b, I, { h: 0.12 })
    b.box(4.8, 2.9, 0.08, { mat: 'corrugated', color: '#8a9088', y: 1.55, z: -1.45 })
    frame(b, 4.8, 2.9, 3.0, { braces: false, metal: true, color: '#4a5048' })
    roof(b, I, (r) => corrRoof(r, { w: 5.1, d: 3.2, y: 3.15, drop: 0.25, color: '#9aa098' }))
    for (let i = 0; i < 3; i++) {
      b.at({ x: -1.8 + i * 0.7, z: -1.15 }, () => {
        b.box(0.65, 1.9, 0.5, { mat: 'paint', color: '#4a5a4a', y: 0.95 })
        b.box(0.62, 1.8, 0.02, { mat: 'steel', color: '#5a6a5a', y: 0.95, z: 0.26 })
        b.box(0.03, 0.2, 0.03, { mat: 'chrome', color: '#c8ccd0', x: 0.22, y: 1.0, z: 0.28 })
        for (let k = 0; k < 6; k++) b.box(0.4, 0.008, 0.01, { mat: 'plain', color: '#2a3a2a', y: 1.6 - k * 0.04, z: 0.272, ao: 0 })
      })
    }
    // milling machine
    b.at({ x: 0.9, z: -0.9 }, () => {
      b.box(0.7, 0.9, 0.6, { mat: 'paint', color: '#3a6a8a', y: 0.45 })
      b.box(0.4, 1.4, 0.4, { mat: 'paint', color: '#3a6a8a', y: 1.6, z: -0.15 })
      b.box(0.5, 0.4, 0.6, { mat: 'paint', color: '#3a6a8a', y: 2.0, z: 0.15 })
      b.pivot('mill', { y: 1.65, z: 0.25 }, (p) => {
        p.cyl(0.05, 0.05, 0.2, { mat: 'chrome', color: '#d0d4d8', seg: 10 })
        p.cyl(0.015, 0.015, 0.15, { mat: 'chrome', color: '#d0d4d8', y: -0.15 })
      })
      I.anims.push({ name: 'mill', kind: 'spin', axis: 'y', speed: 18, when: 'active' })
      b.box(0.9, 0.06, 0.35, { mat: 'steel', color: '#8a8e92', y: 0.95, z: 0.2 })
    })
    I.emitters.push({ kind: 'sparks', x: 0.9, y: 1.4, z: -0.65, when: 'active' })
    // test-fire sand barrel
    barrel(b, { x: 2.05, z: 0.9, color: '#3a3a38', open: true, contents: '#c8b088', fill: 0.95 })
    table(b, { x: -0.3, z: 0.3, w: 1.8, d: 0.8, h: 0.9, mat: 'metal', color: '#7a7e82' })
    rifleProp(b, { x: -0.3, y: 0.95, z: 0.3, ry: Math.PI / 2, scope: true, mag: true })
    for (let i = 0; i < 4; i++) ammoCrate(b, { x: -2.1 + (i % 2) * 0.66, y: Math.floor(i / 2) * 0.3, z: 1.15, ry: 0 })
    for (const x of [-1, 1]) {
      b.box(1.0, 0.06, 0.15, { mat: 'paint', color: '#e8e8e0', x, y: 2.85, z: 0 })
      b.box(0.9, 0.02, 0.08, { mat: 'glowWhite', color: '#ffffff', x, y: 2.81, z: 0 })
    }
    I.lights.push({ x: 0, y: 2.6, z: 0, color: '#f0f4ff', intensity: 4, dist: 8, when: 'night' })
    I.spots.push({ x: -0.3, z: 0.95, face: FACE_BACK, anim: 'hammer' }, { x: 0.9, z: -0.15, face: FACE_BACK, anim: 'type' })
  },

  ammo(b, L, I) {
    const rnd = seeded(801)
    const brass = (x, y, z, n = 10) => {
      for (let i = 0; i < n; i++) b.cyl(0.008, 0.008, 0.035, { mat: 'chrome', color: '#d8b050', x: x + (rnd() - 0.5) * 0.15, y: y + 0.018, z: z + (rnd() - 0.5) * 0.1, seg: 6, rz: rnd() < 0.3 ? Math.PI / 2 : 0, shadow: false })
    }
    if (L === 1) {
      table(b, { x: -0.3, z: -0.5, w: 2.4, d: 0.8, h: 0.9, color: '#c8b498', shelf: true })
      b.at({ x: -0.6, y: 0.9, z: -0.55 }, () => {
        b.box(0.14, 0.04, 0.2, { mat: 'paint', color: '#c83a2a', y: 0.02 })
        b.box(0.08, 0.32, 0.08, { mat: 'paint', color: '#c83a2a', y: 0.2, z: -0.05 })
        b.box(0.1, 0.1, 0.12, { mat: 'paint', color: '#c83a2a', y: 0.38 })
        b.pivot('lever', { y: 0.35, z: 0.05 }, (p) => p.beam([0, 0, 0], [0.0, 0.25, 0.35], 0.02, 0.02, { mat: 'chrome', color: '#c8ccd0', round: true }))
        I.anims.push({ name: 'lever', kind: 'pump', axis: 'x', amp: 0.5, speed: 3, when: 'active' })
      })
      b.at({ x: 0.2, y: 0.9, z: -0.6 }, () => {
        b.box(0.18, 0.06, 0.14, { mat: 'paint', color: '#2a2a2a', y: 0.03 })
        b.cyl(0.06, 0.06, 0.01, { mat: 'chrome', color: '#c8ccd0', y: 0.07 })
      })
      brass(0.6, 0.9, -0.4, 14)
      for (let i = 0; i < 3; i++) b.box(0.12, 0.04, 0.08, { mat: 'paint', color: ['#2a6a3a', '#c8a020', '#2a4a8a'][i], x: 0.6 + i * 0.15, y: 0.92, z: -0.7 })
      bucket(b, { x: 1.3, z: 0.4, color: '#c8a050', mat: 'metal' })
      for (let i = 0; i < 4; i++) ammoCrate(b, { x: -1.5 + (i % 2) * 0.66, y: Math.floor(i / 2) * 0.3, z: 0.7, ry: 0 })
      I.spots.push({ x: -0.6, z: 0.15, face: FACE_BACK, anim: 'pump' }, { x: 0.5, z: 0.15, face: FACE_BACK, anim: 'type' })
      return
    }
    if (L === 2) {
      frame(b, 3.8, 2.6, 2.6, { hFront: 2.8, z: -0.1 })
      roof(b, I, (r) => corrRoof(r, { w: 4.1, d: 2.9, y: 2.95, drop: 0.3, z: -0.1 }))
      table(b, { x: -0.3, z: -0.75, w: 2.8, d: 0.8, h: 0.9, color: '#b8a488', shelf: true })
      // progressive press with a primer tube
      b.at({ x: -0.8, y: 0.9, z: -0.75 }, () => {
        b.box(0.25, 0.06, 0.3, { mat: 'paint', color: '#2a5a9a', y: 0.03 })
        for (const sx of [-1, 1]) b.cyl(0.02, 0.02, 0.45, { mat: 'chrome', color: '#c8ccd0', x: sx * 0.08, y: 0.25 })
        b.cyl(0.11, 0.11, 0.05, { mat: 'paint', color: '#2a5a9a', y: 0.32 })
        b.cyl(0.015, 0.015, 0.6, { mat: 'glass', color: '#e8e0b0', x: 0.1, y: 0.75 })
        b.cyl(0.05, 0.03, 0.15, { mat: 'glass', color: '#d8d0c0', x: -0.1, y: 0.55 })
        b.pivot('lever', { y: 0.3, x: 0.12, z: 0.1 }, (p) => p.beam([0, 0, 0], [0.05, 0.2, 0.4], 0.02, 0.02, { mat: 'chrome', color: '#c8ccd0', round: true }))
        I.anims.push({ name: 'lever', kind: 'pump', axis: 'x', amp: 0.55, speed: 3.5, when: 'active' })
      })
      // case tumbler
      b.at({ x: 0.6, y: 0.9, z: -0.8 }, () => {
        b.box(0.35, 0.08, 0.3, { mat: 'paint', color: '#3a3a3a', y: 0.04 })
        b.pivot('drum', { y: 0.25 }, (p) => p.cyl(0.14, 0.14, 0.3, { mat: 'plastic', color: '#c8a020', rz: Math.PI / 2, seg: 8 }))
        I.anims.push({ name: 'drum', kind: 'spin', axis: 'x', speed: 4, when: 'active' })
      })
      brass(0.0, 0.9, -0.55, 18)
      for (let i = 0; i < 6; i++) ammoCrate(b, { x: 1.3 + (i % 2) * 0.0 - 0.0, y: Math.floor(i / 2) * 0.3, z: 0.2 + (i % 2) * 0.36, ry: Math.PI / 2 })
      shelf(b, { x: -1.6, z: 0.5, ry: Math.PI / 2, w: 1.2, h: 1.6, d: 0.4, seed: 51, kind: 'boxes', metal: true })
      lantern(b, { x: -0.3, y: 2.2, z: -0.5 })
      I.lights.push({ x: -0.3, y: 2.3, z: -0.4, color: '#ffd8a0', intensity: 2.5, dist: 7, when: 'night' })
      I.spots.push({ x: -0.8, z: -0.05, face: FACE_BACK, anim: 'pump' }, { x: 0.4, z: -0.05, face: FACE_BACK, anim: 'type' })
      return
    }
    // L3: an industrial press with a conveyor
    slab(b, I, { h: 0.12 })
    b.at({ x: -0.9, z: -0.6 }, () => {
      b.box(1.2, 0.9, 1.0, { mat: 'paint', color: '#4a5a6a', y: 0.45 })
      for (const sx of [-1, 1]) b.box(0.18, 1.8, 0.18, { mat: 'paint', color: '#4a5a6a', x: sx * 0.45, y: 1.6 })
      b.box(1.2, 0.4, 0.6, { mat: 'paint', color: '#4a5a6a', y: 2.6 })
      b.pivot('ram', { y: 1.6 }, (p) => {
        p.box(0.7, 0.35, 0.5, { mat: 'steel', color: '#5a5e62' })
        p.cyl(0.08, 0.08, 0.7, { mat: 'chrome', color: '#c8ccd0', y: 0.5 })
      })
      I.anims.push({ name: 'ram', kind: 'press', axis: 'y', amp: 0.35, speed: 3, when: 'active' })
      b.box(0.3, 0.4, 0.1, { mat: 'paint', color: '#e8e8e0', x: 0.62, y: 1.2, z: 0.3 })
      b.box(0.06, 0.06, 0.02, { mat: 'glowRed', color: '#ffffff', x: 0.62, y: 1.3, z: 0.36 })
      b.box(0.06, 0.06, 0.02, { mat: 'glowGreen', color: '#ffffff', x: 0.62, y: 1.18, z: 0.36 })
      hazardSign(b, { x: -0.62, y: 1.4, z: 0.42 })
    })
    // conveyor with shells riding along
    b.at({ x: 0.7, z: -0.6 }, () => {
      for (const sz of [-1, 1]) b.box(1.8, 0.1, 0.06, { mat: 'steel', color: '#8a8e92', y: 0.8, z: sz * 0.22 })
      for (let i = 0; i < 4; i++) b.box(0.06, 0.8, 0.06, { mat: 'steel', color: '#5a5e62', x: -0.8 + (i % 2) * 1.6, y: 0.4, z: i < 2 ? 0.22 : -0.22 })
      b.box(1.8, 0.04, 0.4, { mat: 'rubber', color: '#1a1a1a', y: 0.82 })
      b.pivot('belt', { y: 0.86 }, (p) => {
        for (let i = 0; i < 14; i++) p.cyl(0.012, 0.012, 0.05, { mat: 'chrome', color: '#d8b050', x: -0.8 + i * 0.12, z: (i % 3 - 1) * 0.08, seg: 6 })
      })
      I.anims.push({ name: 'belt', kind: 'slide', axis: 'x', amp: 0.12, speed: 0.6, when: 'active' })
    })
    for (let i = 0; i < 8; i++) ammoCrate(b, { x: 1.4 + (i % 2) * 0.0, y: Math.floor(i / 2) * 0.3, z: 0.65 + (i % 2) * 0.36, ry: Math.PI / 2, color: i % 3 ? '#5a6440' : '#4a5434' })
    for (let i = 0; i < 6; i++) ammoCrate(b, { x: -1.5 + (i % 3) * 0.0, y: Math.floor(i / 2) * 0.3, z: 0.6 + (i % 2) * 0.36, ry: Math.PI / 2 })
    b.box(0.5, 0.06, 0.15, { mat: 'paint', color: '#e8e8e0', x: 0, y: 2.6, z: 0 })
    b.box(0.08, 2.6, 0.08, { mat: 'steel', color: '#5a5e62', x: 0, y: 1.3, z: -0.05 })
    I.lights.push({ x: 0, y: 2.5, z: 0.3, color: '#f0f4ff', intensity: 3.5, dist: 8, when: 'night' })
    I.spots.push({ x: -0.9, z: 0.2, face: FACE_BACK, anim: 'type' }, { x: 0.7, z: 0.0, face: FACE_BACK, anim: 'search' })
  },

  tailor(b, L, I) {
    const rnd = seeded(901)
    if (L === 1) {
      roof(b, I, (r) => tarp(b, { rb: r, w: 4.4, d: 3.4, corners: [2.2, 2.2, 1.9, 1.9], color: '#a86a4a', seed: 19 }))
      table(b, { x: -0.8, z: -0.6, w: 1.6, d: 0.8, h: 0.78, color: '#d8c8b0' })
      sewingMachine(b, { x: -0.9, y: 0.78, z: -0.65 })
      b.box(0.5, 0.02, 0.4, { mat: 'cloth', color: '#5a6a8a', x: -0.3, y: 0.79, z: -0.5, ry: 0.3 })
      stool(b, { x: -0.9, z: 0.0 })
      mannequin(b, { x: 0.8, z: -0.8, vest: '#4a4a3a' })
      for (let i = 0; i < 5; i++) b.cyl(0.08, 0.08, 1.1, { mat: 'cloth', color: ['#8a3a3a', '#3a5a8a', '#5a6a3a', '#c8b898', '#4a4a4a'][i], x: 1.6 + (i % 2) * 0.05, y: 0.08 + i * 0.16, z: -0.2 + (i % 2) * 0.05, rz: Math.PI / 2, ry: Math.PI / 2 })
      clothesline(b, [-2.0, 1.75, 1.3], [2.0, 1.75, 1.3], { seed: 9 })
      I.spots.push({ x: -0.9, z: 0.0, face: FACE_BACK, anim: 'type', sit: 0.5 }, { x: 0.8, z: -0.15, face: FACE_BACK, anim: 'search' })
      return
    }
    if (L === 2) {
      b.box(4.8, 0.1, 3.8, { mat: 'planks', color: '#e8dcc6', y: 0.05 })
      frame(b, 4.6, 3.6, 2.7, { braces: true })
      roof(b, I, (r) => corrRoof(r, { w: 5.0, d: 4.0, y: 3.0, drop: 0.35 }))
      boardWall(b, -2.3, 2.3, -1.8, 2.7, { y0: 0.1, seed: 51, color: '#d8ccb6' })
      for (let i = 0; i < 2; i++) {
        table(b, { x: -1.5 + i * 1.5, z: -0.9, w: 1.2, d: 0.7, h: 0.78, color: '#d8c8b0' })
        sewingMachine(b, { x: -1.6 + i * 1.5, y: 0.78, z: -0.95, color: i ? '#2a3a5a' : '#1a1a1a' })
        stool(b, { x: -1.6 + i * 1.5, z: -0.3 })
      }
      table(b, { x: 1.4, z: 0.6, w: 1.6, d: 1.0, h: 0.85, color: '#e8dcc8' })
      b.box(1.2, 0.02, 0.7, { mat: 'cloth', color: '#3a4a3a', x: 1.4, y: 0.86, z: 0.6 })
      b.box(0.25, 0.01, 0.05, { mat: 'chrome', color: '#c8ccd0', x: 1.2, y: 0.875, z: 0.4, ry: 0.4 })
      shelf(b, { x: 1.6, z: -1.5, w: 1.3, h: 2.0, d: 0.4, seed: 61, kind: 'fabric', fill: 1 })
      mannequin(b, { x: -2.0, z: 0.9, vest: '#2a3a2a' })
      mannequin(b, { x: -1.2, z: 1.2, vest: '#5a4a3a', ry: 0.4 })
      // hanging armor pieces on a rail
      b.box(2.4, 0.04, 0.04, { mat: 'steel', color: '#8a8e92', x: 0, y: 2.3, z: 1.6 })
      for (let i = 0; i < 4; i++) b.box(0.42, 0.55, 0.12, { mat: 'cloth', color: ['#3a3a2a', '#4a5a3a', '#2a2a2a', '#5a4a3a'][i], x: -0.9 + i * 0.6, y: 1.95, z: 1.6, r: 0.04 })
      lantern(b, { x: 0, y: 2.3, z: 0 })
      I.lights.push({ x: 0, y: 2.4, z: 0, color: '#ffd8a0', intensity: 2.5, dist: 8, when: 'night' })
      I.spots.push({ x: -1.6, z: -0.3, face: FACE_BACK, anim: 'type', sit: 0.5 }, { x: -0.1, z: -0.3, face: FACE_BACK, anim: 'type', sit: 0.5 })
      return
    }
    // L3: shop front with a heat press and armor displays
    slab(b, I, { h: 0.12 })
    b.at({ z: -1.2 }, () => {
      b.box(4.8, 2.9, 1.4, { mat: 'brick', color: '#e8dcd0', y: 1.55 })
      b.box(4.9, 0.12, 1.5, { mat: 'paint', color: '#5a3a2a', y: 3.05 })
      b.box(1.6, 1.4, 0.04, { mat: 'glass', color: '#e8f0f0', x: -1.2, y: 1.5, z: 0.71 })
      b.box(1.7, 1.5, 0.06, { mat: 'wood', color: '#5a3a2a', x: -1.2, y: 1.5, z: 0.69 })
      mannequin(b, { x: -1.4, z: 0.4, vest: '#3a3a2a', y: 0.12 })
      mannequin(b, { x: -0.8, z: 0.4, vest: '#2a3a4a', y: 0.12 })
      door(b, { x: 0.6, z: 0.71, y: 0.12, color: '#5a3a2a' })
      sign(b, 'OUTFITTERS', { x: 0.5, y: 2.7, z: 0.72, w: 2.4, h: 0.4, bg: '#2a1e18', fg: '#f0d8a0' })
      roof(b, I, (r) => r.cloth(4.8, 1.0, { mat: 'canvas', color: '#7a2a2a', corners: [2.5, 2.5, 2.1, 2.1], sag: 0.05, z: 1.15 }))
    })
    for (let i = 0; i < 2; i++) {
      table(b, { x: -1.5 + i * 1.6, z: 0.6, w: 1.3, d: 0.7, h: 0.8, mat: 'metal', color: '#9aa0a6' })
      sewingMachine(b, { x: -1.6 + i * 1.6, y: 0.8, z: 0.55, color: '#e8e4dc' })
      stool(b, { x: -1.6 + i * 1.6, z: 1.2 })
    }
    // heat press
    b.at({ x: 1.8, z: 0.7 }, () => {
      b.box(0.8, 0.9, 0.7, { mat: 'paint', color: '#3a3a3a', y: 0.45 })
      b.pivot('ram', { y: 1.3 }, (p) => p.box(0.7, 0.15, 0.6, { mat: 'steel', color: '#8a8e92' }))
      I.anims.push({ name: 'ram', kind: 'press', axis: 'y', amp: 0.3, speed: 1.2, when: 'active' })
      b.box(0.2, 0.9, 0.2, { mat: 'paint', color: '#3a3a3a', y: 1.35, z: -0.25 })
    })
    I.emitters.push({ kind: 'steam', x: 1.8, y: 1.3, z: 0.9, when: 'active' })
    I.lights.push({ x: 0, y: 2.0, z: 0.0, color: '#ffe0b0', intensity: 3, dist: 8, when: 'night' })
    I.spots.push({ x: -1.6, z: 1.2, face: FACE_BACK, anim: 'type', sit: 0.5 }, { x: 0.0, z: 1.2, face: FACE_BACK, anim: 'type', sit: 0.5 })
  },

  electronics(b, L, I) {
    const rnd = seeded(1001)
    const boards = (x, y, z, n = 4) => {
      for (let i = 0; i < n; i++) {
        b.box(0.18, 0.006, 0.12, { mat: 'plastic', color: '#2a6a3a', x: x + (rnd() - 0.5) * 0.4, y: y + 0.003 + i * 0.006, z: z + (rnd() - 0.5) * 0.2, ry: rnd() * 3 })
        for (let k = 0; k < 4; k++) b.box(0.02, 0.012, 0.02, { mat: 'plastic', color: '#1a1a1a', x: x + (rnd() - 0.5) * 0.4, y: y + 0.012 + i * 0.006, z: z + (rnd() - 0.5) * 0.2 })
      }
    }
    if (L === 1) {
      table(b, { x: -0.2, z: -0.5, w: 2.0, d: 0.8, h: 0.78, color: '#c8bca8' })
      boards(-0.3, 0.78, -0.5, 4)
      radioSet(b, { x: 0.5, y: 0.78, z: -0.65, ry: -0.3 })
      // soldering station and lamp
      b.at({ x: -0.8, y: 0.78, z: -0.6 }, () => {
        b.box(0.15, 0.08, 0.12, { mat: 'paint', color: '#3a3a3a', y: 0.04 })
        b.box(0.04, 0.02, 0.02, { mat: 'glowRed', color: '#ffffff', y: 0.07, z: 0.061 })
        b.cyl(0.008, 0.012, 0.18, { mat: 'chrome', color: '#c8ccd0', x: 0.12, y: 0.1, rz: 0.8 })
        b.cyl(0.07, 0.08, 0.02, { mat: 'paint', color: '#2a2a2a', x: -0.15, z: 0.15 })
        b.beam([-0.15, 0.02, 0.15], [-0.1, 0.4, 0.1], 0.015, 0.015, { mat: 'paint', color: '#2a2a2a', round: true })
        b.beam([-0.1, 0.4, 0.1], [0.05, 0.42, 0.2], 0.015, 0.015, { mat: 'paint', color: '#2a2a2a', round: true })
        b.cone(0.06, 0.08, { mat: 'paint', color: '#2a4a8a', x: 0.06, y: 0.38, z: 0.21, rx: Math.PI, seg: 10 })
      })
      I.emitters.push({ kind: 'solder', x: -0.68, y: 0.95, z: -0.5, when: 'active' })
      I.lights.push({ x: -0.75, y: 1.1, z: -0.35, color: '#ffe0b0', intensity: 1.2, dist: 3, when: 'night' })
      carBattery(b, { x: 1.2, z: -0.3, ry: 0.4 })
      for (let i = 0; i < 3; i++) b.box(0.4, 0.25, 0.3, { mat: 'plastic', color: '#2a2a2e', x: -1.5 + i * 0.1, y: 0.13 + i * 0.25, z: 0.6, ry: i * 0.2 })
      stool(b, { x: -0.5, z: 0.1 })
      I.spots.push({ x: -0.5, z: 0.1, face: FACE_BACK, anim: 'type', sit: 0.5 })
      return
    }
    if (L === 2) {
      frame(b, 3.8, 2.6, 2.6, { hFront: 2.8, z: -0.1 })
      roof(b, I, (r) => corrRoof(r, { w: 4.1, d: 2.9, y: 2.95, drop: 0.3, z: -0.1 }))
      table(b, { x: -0.3, z: -0.8, w: 2.6, d: 0.8, h: 0.78, color: '#c8c4bc' })
      monitor(b, { x: -0.9, y: 0.78, z: -0.95, kind: 'green' })
      monitor(b, { x: 0.0, y: 0.78, z: -0.95, kind: 'blue' })
      boards(0.6, 0.78, -0.75, 5)
      // parts drawers
      b.at({ x: -0.3, y: 1.6, z: -1.15 }, () => {
        b.box(2.4, 0.6, 0.25, { mat: 'paint', color: '#4a5a6a', y: 0.3 })
        for (let i = 0; i < 12; i++) for (let j = 0; j < 3; j++) b.box(0.16, 0.14, 0.02, { mat: 'plastic', color: '#e8e4dc', x: -1.08 + i * 0.196, y: 0.12 + j * 0.18, z: 0.13 })
      })
      // server rack with blinking LEDs
      b.at({ x: 1.5, z: 0.2 }, () => {
        b.box(0.6, 1.9, 0.7, { mat: 'paint', color: '#1e2024', y: 0.95 })
        for (let i = 0; i < 8; i++) {
          b.box(0.52, 0.16, 0.02, { mat: 'metal', color: '#3a3e42', y: 0.3 + i * 0.2, z: 0.36 })
          b.box(0.03, 0.02, 0.01, { mat: i % 3 ? 'glowGreen' : 'glowAmber', color: '#ffffff', x: 0.2, y: 0.3 + i * 0.2, z: 0.375 })
        }
      })
      I.blink = [{ x: 1.7, y: 1.0, z: 0.6, color: 'green' }]
      stool(b, { x: -0.5, z: -0.15 })
      stool(b, { x: 0.4, z: -0.15 })
      b.rope([[1.3, 1.8, 0.2], [0.5, 2.5, -0.5], [-1.2, 2.5, -1.0]], 0.015, { mat: 'rubber', color: '#1a1a1a', sag: 0.1 })
      I.lights.push({ x: -0.4, y: 1.4, z: -0.5, color: '#80ffb0', intensity: 1.2, dist: 4, when: 'active' })
      I.spots.push({ x: -0.5, z: -0.15, face: FACE_BACK, anim: 'type', sit: 0.5 }, { x: 0.4, z: -0.15, face: FACE_BACK, anim: 'type', sit: 0.5 })
      return
    }
    // L3: a clean booth with monitors, a 3D printer and test racks
    slab(b, I, { h: 0.12 })
    b.box(3.9, 2.8, 0.08, { mat: 'siding', color: '#e8ecec', y: 1.5, z: -1.45 })
    b.box(0.08, 2.8, 2.9, { mat: 'siding', color: '#e8ecec', x: -1.95, y: 1.5 })
    frame(b, 3.9, 2.9, 2.9, { braces: false, metal: true, color: '#c8ccd0' })
    roof(b, I, (r) => corrRoof(r, { w: 4.2, d: 3.2, y: 3.05, drop: 0.25, color: '#d8dcdc' }))
    table(b, { x: -0.4, z: -0.95, w: 2.6, d: 0.8, h: 0.78, mat: 'metal', color: '#c8ccd0' })
    for (let i = 0; i < 3; i++) monitor(b, { x: -1.2 + i * 0.6, y: 0.78, z: -1.1, ry: (1 - i) * 0.2, kind: i === 1 ? 'green' : 'blue' })
    boards(0.6, 0.78, -0.85, 6)
    // 3D printer
    b.at({ x: 1.4, z: -0.95 }, () => {
      b.box(0.6, 0.78, 0.6, { mat: 'paint', color: '#2a2a2e', y: 0.39 })
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.03, 0.6, 0.03, { mat: 'steel', color: '#8a8e92', x: sx * 0.27, y: 1.08, z: sz * 0.27 })
      b.box(0.6, 0.04, 0.6, { mat: 'steel', color: '#8a8e92', y: 1.38 })
      b.box(0.45, 0.01, 0.45, { mat: 'plain', color: '#c8302a', y: 0.8 })
      b.box(0.12, 0.08, 0.12, { mat: 'plastic', color: '#e88a2a', y: 0.82 })
      b.pivot('head', { y: 1.0 }, (p) => {
        p.box(0.55, 0.02, 0.02, { mat: 'steel', color: '#c8ccd0' })
        p.box(0.08, 0.08, 0.08, { mat: 'plastic', color: '#3a7ab0' })
      })
      I.anims.push({ name: 'head', kind: 'slide', axis: 'x', amp: 0.18, speed: 1.6, when: 'active' })
    })
    for (let i = 0; i < 2; i++) {
      b.at({ x: -1.4 + i * 0.7, z: 0.9 }, () => {
        b.box(0.6, 1.9, 0.7, { mat: 'paint', color: '#e8ecec', y: 0.95 })
        for (let k = 0; k < 7; k++) {
          b.box(0.52, 0.16, 0.02, { mat: 'metal', color: '#c8ccd0', y: 0.3 + k * 0.22, z: 0.36 })
          b.box(0.03, 0.02, 0.01, { mat: k % 2 ? 'glowBlue' : 'glowGreen', color: '#ffffff', x: 0.2, y: 0.3 + k * 0.22, z: 0.375 })
        }
      })
    }
    for (const x of [-0.8, 0.8]) {
      b.box(0.9, 0.06, 0.15, { mat: 'paint', color: '#e8e8e0', x, y: 2.8, z: 0 })
      b.box(0.8, 0.02, 0.08, { mat: 'glowWhite', color: '#ffffff', x, y: 2.76, z: 0 })
    }
    stool(b, { x: -1.0, z: -0.3 })
    stool(b, { x: 0.1, z: -0.3 })
    I.lights.push({ x: 0, y: 2.6, z: 0, color: '#e8f4ff', intensity: 4, dist: 8, when: 'night' })
    I.spots.push({ x: -1.0, z: -0.3, face: FACE_BACK, anim: 'type', sit: 0.5 }, { x: 0.1, z: -0.3, face: FACE_BACK, anim: 'type', sit: 0.5 })
  },

  // ---------------------------------------------------------------- defense
  watchtower(b, L, I) {
    const H = [3.6, 5.0, 6.5][L - 1]
    I.platformY = H + (L === 3 ? 0.1 : 0.06)
    if (L < 3) {
      const leg = 1.15
      const c = L === 1 ? COL.woodGrey : '#c8bca8'
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.beam([sx * (leg + 0.15), 0, sz * (leg + 0.15)], [sx * leg * 0.9, H + 1.1, sz * leg * 0.9], 0.16, 0.16, { mat: 'wood', color: c })
      const levels = L === 1 ? [1.2, 2.4] : [1.3, 2.6, 3.9]
      for (const y of levels) {
        const r = leg + 0.15 - (0.15 + leg * 0.1) * (y / (H + 1.1))
        for (const [ax, az, bx, bz] of [[-1, -1, 1, -1], [1, -1, 1, 1], [1, 1, -1, 1], [-1, 1, -1, -1]]) b.beam([ax * r, y, az * r], [bx * r, y, bz * r], 0.08, 0.12, { mat: 'wood', color: c })
        for (const [ax, az, bx, bz] of [[-1, -1, 1, -1], [1, 1, -1, 1]]) b.beam([ax * r, y - 1.1, az * r], [bx * r, y, bz * r], 0.06, 0.1, { mat: 'wood', color: shadeHex(c, -0.1) })
      }
      // platform
      b.box(2.9, 0.12, 2.9, { mat: 'planks', color: '#e8dcc6', y: H })
      for (const sx of [-1, 1]) b.box(3.0, 0.15, 0.12, { mat: 'wood', color: c, y: H - 0.12, z: sx * 1.42 })
      if (L === 1) {
        for (const [ax, az, len, ry] of [[0, -1.4, 2.9, 0], [0, 1.4, 2.9, 0], [-1.4, 0, 2.9, Math.PI / 2], [1.4, 0, 2.9, Math.PI / 2]]) {
          b.box(len, 0.1, 0.06, { mat: 'wood', color: c, x: ax, y: H + 1.0, z: az, ry })
          b.box(len, 0.1, 0.06, { mat: 'wood', color: c, x: ax, y: H + 0.55, z: az, ry })
        }
        roof(b, I, (r) => r.cloth(3.2, 3.2, { mat: 'canvas', color: COL.canvasOlive, corners: [H + 2.2, H + 2.2, H + 1.9, H + 1.9], sag: 0.12 }))
        for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.08, 2.1, 0.08, { mat: 'wood', color: c, x: sx * 1.4, y: H + 1.05, z: sz * 1.4 })
        ladder(b, { x: 0.3, z: 1.6, h: H + 0.9, lean: 0.18 })
      } else {
        sandbags(b, { x: 0, y: H + 0.06, z: -1.25, len: 2.6, rows: 4, seed: 3 })
        sandbags(b, { x: 0, y: H + 0.06, z: 1.25, len: 2.6, rows: 4, seed: 4 })
        sandbags(b, { x: -1.25, y: H + 0.06, z: 0, len: 2.0, rows: 4, ry: Math.PI / 2, seed: 5 })
        sandbags(b, { x: 1.25, y: H + 0.06, z: 0.35, len: 1.2, rows: 4, ry: Math.PI / 2, seed: 6 })
        for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.1, 2.3, 0.1, { mat: 'wood', color: c, x: sx * 1.4, y: H + 1.15, z: sz * 1.4 })
        roof(b, I, (r) => corrRoof(r, { w: 3.3, d: 3.3, y: H + 2.4, drop: 0.3 }))
        ladder(b, { x: 1.62, z: -0.5, h: H + 0.9, lean: 0.15, ry: -Math.PI / 2 })
        lantern(b, { x: 0, y: H + 1.9, z: 0 })
        I.lights.push({ x: 0, y: H + 2.0, z: 0, color: '#ffb060', intensity: 3, dist: 9, when: 'night' })
      }
      I.spots.push({ x: -0.4, z: 0.5, y: I.platformY, face: Math.PI / 4, anim: 'lookout', tower: true }, { x: 0.5, z: -0.4, y: I.platformY, face: -Math.PI / 4 * 3, anim: 'lookout', tower: true })
      return
    }
    // L3: steel tower with an enclosed cab and a searchlight
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.beam([sx * 1.25, 0, sz * 1.25], [sx * 1.05, H, sz * 1.05], 0.16, 0.16, { mat: 'paint', color: '#4a5048' })
    for (let y = 1.2; y < H; y += 1.6) {
      for (const [ax, az, bx, bz] of [[-1, -1, 1, -1], [1, -1, 1, 1], [1, 1, -1, 1], [-1, 1, -1, -1]]) {
        b.beam([ax * 1.18, y, az * 1.18], [bx * 1.18, y, bz * 1.18], 0.06, 0.06, { mat: 'steel', color: '#7a8078' })
        b.beam([ax * 1.2, y - 1.2, az * 1.2], [bx * 1.18, y, bz * 1.18], 0.04, 0.04, { mat: 'steel', color: '#7a8078' })
      }
    }
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.5, 0.3, 0.5, { mat: 'concrete', color: '#b8b4ac', x: sx * 1.25, y: 0.15, z: sz * 1.25 })
    b.box(3.0, 0.15, 3.0, { mat: 'steel', color: '#5a6058', y: H })
    // cab
    b.box(3.0, 1.0, 3.0, { mat: 'corrugated', color: '#7a8470', y: H + 0.55, sx: 1, sz: 1 })
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.1, 2.2, 0.1, { mat: 'paint', color: '#4a5048', x: sx * 1.45, y: H + 1.1, z: sz * 1.45 })
    for (const [ax, az, ry] of [[0, 1.48, 0], [0, -1.48, 0], [1.48, 0, Math.PI / 2], [-1.48, 0, Math.PI / 2]]) b.box(2.8, 0.9, 0.03, { mat: 'glass', color: '#d8e8e8', x: ax, y: H + 1.5, z: az, ry })
    roof(b, I, (r) => {
      r.box(3.4, 0.12, 3.4, { mat: 'paint', color: '#4a5048', y: H + 2.25 })
      r.cone(2.2, 0.5, { mat: 'paint', color: '#4a5048', y: H + 2.55, seg: 4, ry: Math.PI / 4 })
    })
    // searchlight on the roof
    b.pivot('search', { y: H + 2.75 }, (p) => {
      p.box(0.3, 0.2, 0.3, { mat: 'paint', color: '#2a2a2a', y: 0.1 })
      p.cyl(0.22, 0.26, 0.45, { mat: 'paint', color: '#2a2a2a', y: 0.35, z: 0.1, rx: Math.PI / 2 - 0.4 })
      p.cyl(0.2, 0.2, 0.02, { mat: 'glowWhite', color: '#ffffff', y: 0.45, z: 0.33, rx: Math.PI / 2 - 0.4 })
    })
    I.anims.push({ name: 'search', kind: 'yaw', speed: 0.35, swing: 1.4, when: 'night' })
    I.searchlight = { name: 'search', y: H + 3.1 }
    // stairs zig-zag up one side
    for (let i = 0; i < Math.floor(H / 0.3); i++) {
      const y = 0.3 + i * 0.3
      const flight = Math.floor(i / 7) % 2
      const k = i % 7
      b.box(0.8, 0.05, 0.3, { mat: 'steel', color: '#8a9088', x: flight ? 1.7 - k * 0.32 : -0.6 + k * 0.32, y, z: 1.65, ry: Math.PI / 2 })
    }
    I.spots.push({ x: -0.5, z: 0.5, y: I.platformY, face: Math.PI / 4, anim: 'lookout', tower: true }, { x: 0.5, z: -0.5, y: I.platformY, face: -Math.PI / 4 * 3, anim: 'lookout', tower: true })
  },

  turret(b, L, I) {
    if (L === 1) {
      for (let i = 0; i < 3; i++) {
        const a = (i / 3) * TAU
        b.beam([Math.cos(a) * 0.6, 0, Math.sin(a) * 0.6], [0, 0.9, 0], 0.05, 0.05, { mat: 'steel', color: '#4a4e52', round: true })
      }
      I.turretY = 0.95
    } else if (L === 2) {
      for (let i = 0; i < 8; i++) {
        const a = (i / 8) * TAU
        sandbags(b, { x: Math.cos(a) * 0.75, z: Math.sin(a) * 0.75, len: 0.55, rows: 3, ry: -a + Math.PI / 2, seed: i })
      }
      b.cyl(0.2, 0.25, 0.9, { mat: 'paint', color: '#4a5a4a', y: 0.45 })
      I.turretY = 0.95
    } else {
      b.cyl(0.8, 0.9, 0.5, { mat: 'concrete', color: '#b8b4ac', y: 0.25, seg: 8 })
      b.cyl(0.4, 0.45, 0.6, { mat: 'paint', color: '#3a4a3a', y: 0.8, seg: 12 })
      I.turretY = 1.15
    }
    b.pivot('head', { y: I.turretY }, (p) => {
      if (L === 1) {
        p.box(0.16, 0.16, 0.5, { mat: 'paint', color: '#2a2c2e', y: 0.08 })
        p.cyl(0.025, 0.025, 0.6, { mat: 'steel', color: '#3a3e42', y: 0.1, z: 0.5, rx: Math.PI / 2 })
        p.box(0.12, 0.18, 0.1, { mat: 'paint', color: '#4a5a3a', x: 0.15, y: 0.0, z: 0.05 })
        p.box(0.08, 0.06, 0.1, { mat: 'paint', color: '#1a1a1a', y: 0.2, z: 0.1 })
        p.box(0.03, 0.03, 0.01, { mat: 'glowRed', color: '#ffffff', y: 0.2, z: 0.16 })
      } else if (L === 2) {
        p.box(0.6, 0.45, 0.06, { mat: 'paint', color: '#4a5a4a', y: 0.2, z: 0.3 })
        p.box(0.22, 0.22, 0.6, { mat: 'paint', color: '#2a2c2e', y: 0.12 })
        p.cyl(0.035, 0.035, 0.8, { mat: 'steel', color: '#3a3e42', y: 0.15, z: 0.6, rx: Math.PI / 2 })
        p.cyl(0.05, 0.05, 0.15, { mat: 'steel', color: '#3a3e42', y: 0.15, z: 0.95, rx: Math.PI / 2 })
        p.box(0.15, 0.2, 0.25, { mat: 'paint', color: '#4a5a3a', x: -0.2, y: 0.05 })
        p.box(0.1, 0.08, 0.1, { mat: 'paint', color: '#1a1a1a', x: 0.18, y: 0.32, z: 0.1 })
        p.box(0.03, 0.03, 0.01, { mat: 'glowRed', color: '#ffffff', x: 0.18, y: 0.32, z: 0.16 })
      } else {
        p.box(0.75, 0.5, 0.7, { mat: 'paint', color: '#4a5a4a', y: 0.25, r: 0.06 })
        for (const sx of [-1, 1]) {
          p.cyl(0.04, 0.04, 0.9, { mat: 'steel', color: '#2a2e32', x: sx * 0.15, y: 0.25, z: 0.75, rx: Math.PI / 2 })
          p.cyl(0.06, 0.06, 0.12, { mat: 'steel', color: '#2a2e32', x: sx * 0.15, y: 0.25, z: 1.15, rx: Math.PI / 2 })
        }
        p.cyl(0.1, 0.1, 0.12, { mat: 'paint', color: '#1a1a1a', y: 0.56 })
        p.lathe([[0.001, 0], [0.12, 0.03], [0.22, 0.09]], { mat: 'paint', color: '#e8e8e4', y: 0.65, seg: 14 })
        p.box(0.04, 0.04, 0.01, { mat: 'glowRed', color: '#ffffff', y: 0.4, z: 0.36 })
      }
    })
    I.anims.push({ name: 'head', kind: 'turret', when: 'always' })
    I.muzzle = { name: 'head', z: L === 3 ? 1.25 : L === 2 ? 1.05 : 0.8, y: L === 3 ? 0.25 : 0.12 }
    ammoCrate(b, { x: -0.55, z: 0.6, ry: 0.4 })
    b.rope([[0, 0.4, -0.2], [0.3, 0.02, -0.8], [0.9, 0.02, -1.0]], 0.012, { mat: 'rubber', color: '#1a1a1a', sag: 0 })
  },

  floodlight(b, L, I) {
    b.box(0.3, 0.2, 0.3, { mat: 'concrete', color: '#b8b4ac', y: 0.1 })
    b.cyl(0.05, 0.06, 3.4, { mat: 'steel', color: '#7a8086', y: 1.8 })
    b.box(0.25, 0.35, 0.15, { mat: 'paint', color: '#5a6066', y: 1.2, z: 0.08 })
    b.pivot('lamp', { y: 3.5 }, (p) => {
      p.box(0.6, 0.06, 0.06, { mat: 'steel', color: '#5a5e62' })
      for (const sx of [-1, 1]) {
        p.box(0.25, 0.25, 0.18, { mat: 'paint', color: '#2a2a2a', x: sx * 0.22, y: 0.1, z: 0.05, rx: 0.5 })
        p.box(0.21, 0.21, 0.02, { mat: 'glowWhite', color: '#ffffff', x: sx * 0.22, y: 0.14, z: 0.14, rx: 0.5 })
      }
    })
    I.flood = { name: 'lamp', y: 3.55 }
  },
}
