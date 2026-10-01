// Factory stations, high-detail: the Fabricator (scrap in, parts and wiring
// out) and the Machine Shop (circuit boards, motors, coils, amplifiers).
// Same conventions as stationsHD.js: footprint centred on the origin, the
// working side faces +z, everything reported through I.
import { seeded } from './kit.js'
import { shadeHex, crate, barrel, pallet, scrapPile, lantern, sack, tireStack, cinderBlocks } from './parts.js'
import { slab } from './stationkit.js'
import {
  heavyBench, partsBins, benchLamp, fluoroLight, elecPanel, cable, controlPanel, beacon, drillPress, benchGrinder, metalLathe, shopPress,
  conveyor, hopper, benchClutter, stripeMat, decal, drum, componentDrawers, oscilloscope, crtMonitor, cage, pipeRun, solderIron, canopy, tinLeanTo,
} from './detail.js'
import { steelShed, timberShed, cncMill, hangBulb, stains } from './stationsHD.js'

const TAU = Math.PI * 2
const FACE_BACK = Math.PI
const COPPER = '#c8783e'

// ---------------------------------------------------------------- shared bits
// A cable drum of copper wire on a stand; the drum spins in pivot `name`.
function wireSpool(b, I, o = {}) {
  const r = o.r ?? 0.32
  const name = o.name ?? 'spool'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (const s of [-1, 1]) {
      b.box(0.06, r + 0.25, 0.06, { mat: 'paint', color: o.frame ?? '#4a5056', x: s * 0.26, y: (r + 0.25) / 2 })
      b.box(0.08, 0.04, 0.36, { mat: 'paint', color: o.frame ?? '#4a5056', x: s * 0.26, y: 0.02 })
    }
    b.pivot(name, { y: r + 0.18 }, (p) => {
      for (const s of [-1, 1]) p.cyl(r, r, 0.03, { mat: 'wood', color: '#c8a878', x: s * 0.2, rz: Math.PI / 2, seg: 18 })
      p.cyl(r * 0.82, r * 0.82, 0.38, { mat: 'metal', color: o.color ?? COPPER, rz: Math.PI / 2, seg: 18 })
      for (let i = 0; i < 4; i++) p.torus(r * 0.83, 0.008, { mat: 'metal', color: shadeHex(o.color ?? COPPER, -0.15), x: -0.15 + i * 0.1, ry: Math.PI / 2, rs: 4, ts2: 20 })
      p.cyl(0.03, 0.03, 0.62, { mat: 'steel', color: '#8a8e92', rz: Math.PI / 2, seg: 8 })
    })
    if (I) I.anims.push({ name, kind: 'spin', axis: 'x', speed: o.speed ?? 2.2, when: 'active' })
  })
}
// Bench-top arbor press with a lever that rocks while working.
function arborPress(b, I, o = {}) {
  const name = o.name ?? 'lever'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.26, 0.04, 0.3, { mat: 'paint', color: '#3a5a7a', y: 0.02 })
    b.box(0.08, 0.5, 0.1, { mat: 'paint', color: '#3a5a7a', y: 0.27, z: -0.1 })
    b.box(0.16, 0.1, 0.18, { mat: 'paint', color: '#3a5a7a', y: 0.47, z: -0.02 })
    b.cyl(0.025, 0.025, 0.22, { mat: 'chrome', color: '#c8ccd0', y: 0.33, z: 0.03, seg: 10 })
    b.pivot(name, { y: 0.48, x: 0.09, z: -0.02 }, (p) => {
      p.cyl(0.012, 0.012, 0.42, { mat: 'chrome', color: '#c8ccd0', x: 0.2, rz: Math.PI / 2 - 0.4, seg: 6 })
      p.sphere(0.03, { mat: 'plastic', color: '#1a1a1a', x: 0.39, y: 0.17 })
    })
    if (I) I.anims.push({ name, kind: 'pump', axis: 'z', amp: 0.35, speed: 2.4, when: 'active' })
  })
}
// A plastic tote full of finished goods.
function tote(b, o = {}) {
  const rnd = seeded(o.seed ?? 7)
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.56, 0.3, 0.4, { mat: 'plastic', color: o.color ?? '#3a6ab0', y: 0.15, r: 0.02 })
    b.box(0.52, 0.02, 0.36, { mat: 'plain', color: '#1a1a1a', y: 0.29 })
    for (let i = 0; i < 9; i++) {
      const x = (rnd() - 0.5) * 0.42
      const z = (rnd() - 0.5) * 0.28
      if (o.kind === 'wire') b.torus(0.07, 0.018, { mat: 'metal', color: COPPER, x, y: 0.31, z, rx: Math.PI / 2 + (rnd() - 0.5) * 0.4, rs: 5, ts2: 12 })
      else if (o.kind === 'boards') b.box(0.12, 0.012, 0.09, { mat: 'paint', color: '#2a7a4a', x, y: 0.31 + i * 0.006, z, ry: rnd() })
      else b.torus(0.035, 0.012, { mat: 'steel', color: '#a8acb0', x, y: 0.31, z, rx: Math.PI / 2, rs: 4, ts2: 8 })
    }
  })
}
// Electric motor on the floor or a bench: stator can, cooling fins, shaft.
function motor(b, I, o = {}) {
  const s = o.s ?? 1
  const name = o.name
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.32 * s, 0.05 * s, 0.24 * s, { mat: 'paint', color: '#3a3e42', y: 0.025 * s })
    b.cyl(0.14 * s, 0.14 * s, 0.34 * s, { mat: 'paint', color: o.color ?? '#3a6a8a', y: 0.18 * s, rz: Math.PI / 2, seg: 16 })
    for (let i = 0; i < 7; i++) b.torus(0.145 * s, 0.008 * s, { mat: 'paint', color: shadeHex(o.color ?? '#3a6a8a', -0.15), x: (-0.13 + i * 0.043) * s, y: 0.18 * s, ry: Math.PI / 2, rs: 4, ts2: 18 })
    b.box(0.1 * s, 0.07 * s, 0.1 * s, { mat: 'paint', color: shadeHex(o.color ?? '#3a6a8a', 0.1), y: 0.34 * s })
    if (name) {
      b.pivot(name, { x: 0.2 * s, y: 0.18 * s }, (p) => {
        p.cyl(0.02 * s, 0.02 * s, 0.1 * s, { mat: 'chrome', color: '#c8ccd0', rz: Math.PI / 2, seg: 8 })
        for (let k = 0; k < 4; k++) p.box(0.012 * s, 0.13 * s, 0.035 * s, { mat: 'paint', color: '#d8a020', x: 0.05 * s, rx: (k / 4) * TAU })
      })
      if (I) I.anims.push({ name, kind: 'spin', axis: 'x', speed: 16, when: 'active' })
    } else b.cyl(0.02 * s, 0.02 * s, 0.1 * s, { mat: 'chrome', color: '#c8ccd0', x: 0.2 * s, y: 0.18 * s, rz: Math.PI / 2, seg: 8 })
  })
}
// Coil-winding machine: a spindle turning a copper coil, a guide arm, a counter.
function coilWinder(b, I, o = {}) {
  const name = o.name ?? 'winder'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.6, 0.06, 0.32, { mat: 'paint', color: '#5a6a5a', y: 0.03 })
    b.box(0.16, 0.24, 0.2, { mat: 'paint', color: '#5a6a5a', x: -0.2, y: 0.18 })
    b.box(0.1, 0.06, 0.012, { mat: 'glowRed', color: '#5a1410', x: -0.2, y: 0.24, z: 0.106 })
    b.pivot(name, { x: 0.05, y: 0.2 }, (p) => {
      p.cyl(0.012, 0.012, 0.4, { mat: 'chrome', color: '#c8ccd0', rz: Math.PI / 2, seg: 8 })
      p.cyl(0.07, 0.07, 0.16, { mat: 'metal', color: COPPER, x: 0.08, rz: Math.PI / 2, seg: 16 })
      for (const s of [-1, 1]) p.cyl(0.09, 0.09, 0.012, { mat: 'plastic', color: '#1a1a1a', x: 0.08 + s * 0.085, rz: Math.PI / 2, seg: 16 })
    })
    if (I) I.anims.push({ name, kind: 'spin', axis: 'x', speed: 9, when: 'active' })
    b.box(0.03, 0.2, 0.03, { mat: 'steel', color: '#8a8e92', x: 0.12, y: 0.12, z: -0.12 })
    b.beam([0.12, 0.22, -0.12], [0.12, 0.22, 0.0], 0.015, 0.015, { mat: 'steel', color: '#8a8e92' })
    b.rope([[0.12, 0.22, 0.0], [0.12, 0.27, 0.0], [0.13, 0.32, -0.25]], 0.003, { mat: 'metal', color: COPPER, sag: 0.02 })
    b.cyl(0.08, 0.08, 0.1, { mat: 'metal', color: COPPER, x: 0.13, y: 0.38, z: -0.25, seg: 14 })
  })
}
// Circuit assembly mat: a few boards in progress, a magnifier lamp, parts trays.
function boardMat(b, o = {}) {
  const rnd = seeded(o.seed ?? 9)
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.7, 0.006, 0.45, { mat: 'rubber', color: '#2a4a6a', y: 0.003 })
    for (let i = 0; i < 3; i++) {
      const x = -0.2 + i * 0.2
      b.box(0.16, 0.008, 0.11, { mat: 'paint', color: '#2a7a4a', x, y: 0.01, z: -0.05 + (i % 2) * 0.12 })
      for (let k = 0; k < 5; k++) b.box(0.02, 0.012 + rnd() * 0.01, 0.015, { mat: 'plastic', color: ['#1a1a1a', '#c8a050', '#3a5ab0', '#a8a8a0'][k % 4], x: x - 0.05 + rnd() * 0.1, y: 0.02, z: -0.05 + (i % 2) * 0.12 + (rnd() - 0.5) * 0.06 })
    }
    for (let k = 0; k < 4; k++) b.box(0.08, 0.02, 0.06, { mat: 'plastic', color: '#d8d4c8', x: -0.28 + k * 0.09, y: 0.012, z: 0.17 })
  })
}

export const HD2 = {
  // ============================================================ FABRICATOR
  // Scrap goes in at one end, parts and spooled wire come out the other.
  fabricator(b, L, I) {
    const rnd = seeded(3100 + L)
    if (L === 1) {
      // a hand-fed salvage line under a tarp: hopper, belt, a bench press, a wire spool
      b.box(3.8, 0.02, 3.8, { mat: 'dirt', color: '#6a5a48', y: 0.0 })
      slab(b, I, { w: 3.0, d: 1.9, z: -0.8, color: '#a8a49a' })
      canopy(b, I, { x: 0.05, z: -0.75, w: 3.4, d: 2.1, hf: 2.4, hb: 2.0, color: '#6a6248', seed: 31 })
      hopper(b, { x: -1.25, z: -1.0, w: 0.62, h: 1.15, color: '#8a7a62', fill: '#7a6a58', fillMat: 'rust' })
      conveyor(b, I, { x: -0.15, z: -1.0, len: 1.6, w: 0.4, h: 0.72, color: '#a88a3a', name: 'line', speed: 0.22, gap: 0.32, items: (p, x, r) => (r() < 0.5 ? p.box(0.09, 0.05, 0.08, { mat: 'rust', color: '#8a6a52', x, y: 0.03, ry: r() }) : p.torus(0.04, 0.014, { mat: 'steel', color: '#9aa0a4', x, y: 0.025, rx: Math.PI / 2, rs: 4, ts2: 10 })) })
      const by = heavyBench(b, { x: 1.15, z: -0.95, w: 1.2, d: 0.65, seed: 4 })
      arborPress(b, I, { x: 1.0, y: by, z: -1.0 })
      benchClutter(b, 1.3, 1.6, -1.15, -0.8, by, rnd, ['nuts', 'bolts', 'rag'])
      partsBins(b, { x: 1.2, y: by + 0.15, z: -1.25, cols: 3, rows: 2, seed: 12 })
      wireSpool(b, I, { x: -0.9, z: 0.45, ry: 0.3, name: 'spool' })
      // the hand crank and a coil of stripped wire on a nail board
      b.box(0.06, 0.9, 0.06, { mat: 'wood', color: '#8a7050', x: -0.35, y: 0.45, z: 0.6 })
      for (let i = 0; i < 3; i++) b.torus(0.12, 0.014, { mat: 'metal', color: COPPER, x: -0.35, y: 0.75 - i * 0.12, z: 0.64, rs: 5, ts2: 16 })
      tote(b, { x: 0.55, z: 0.55, ry: -0.2, kind: 'parts', color: '#b8402a' })
      tote(b, { x: 0.1, z: 0.95, ry: 0.4, kind: 'wire', color: '#3a6ab0' })
      scrapPile(b, { x: -1.45, z: 1.2, r: 0.55, n: 8, seed: 5 })
      lantern(b, { x: 0.3, y: 1.9, z: -0.6 })
      I.lights.push({ x: 0.3, y: 1.9, z: -0.6, color: '#ffb060', intensity: 2.4, dist: 7, when: 'night' })
      I.spots.push({ x: 1.0, z: -0.3, face: FACE_BACK, anim: 'hammer' }, { x: -0.55, z: 1.05, face: -2.4, anim: 'pump' })
      return
    }
    if (L === 2) {
      // a tin shed with a powered line: hopper, belt, stamping press, a wire stripper
      slab(b, I, { color: '#a8a49c' })
      stains(b, rnd, -1.6, 1.6, -1.6, 1.6, 4, 0.09)
      tinLeanTo(b, I, { x: 0, z: -0.25, w: 3.8, d: 3.1, hf: 2.85, hb: 2.55, seed: 33 })
      hopper(b, { x: -1.45, z: -1.05, w: 0.6, h: 1.25, color: '#5a6a7a', fill: '#7a6a58', fillMat: 'rust' })
      conveyor(b, I, { x: -0.3, z: -1.05, len: 2.0, w: 0.45, h: 0.78, color: '#d8a020', name: 'line', speed: 0.42, gap: 0.3, items: (p, x, r) => p.torus(0.045, 0.016, { mat: 'steel', color: '#a8acb0', x, y: 0.025, rx: Math.PI / 2, rs: 4, ts2: 10 }) })
      motor(b, I, { x: -1.35, y: 0.0, z: -0.55, s: 0.9, color: '#3a5a7a' })
      shopPress(b, I, { x: 1.2, z: -1.1, w: 0.8, h: 1.75, color: '#3a6a5a', name: 'ram', speed: 0.9 })
      tote(b, { x: 1.2, y: 0.0, z: -0.35, kind: 'parts', color: '#b8402a' })
      // wire stripper: rollers on a cabinet, spool in, spool out
      b.at({ x: -0.6, z: 0.75 }, () => {
        b.box(0.7, 0.75, 0.45, { mat: 'paint', color: '#4a6a5a', y: 0.375, r: 0.02 })
        b.box(0.28, 0.1, 0.012, { mat: 'glowGreen', color: '#1a3a1a', x: -0.15, y: 0.62, z: 0.23 })
        for (let i = 0; i < 3; i++) b.cyl(0.045, 0.045, 0.3, { mat: 'chrome', color: '#b8bcc0', x: -0.1 + i * 0.11, y: 0.82, rx: Math.PI / 2, seg: 12 })
      })
      wireSpool(b, I, { x: -1.4, z: 0.75, ry: Math.PI / 2, name: 'spoolIn', color: '#3a3a3a', speed: 1.6 })
      wireSpool(b, I, { x: 0.25, z: 0.75, ry: Math.PI / 2, name: 'spoolOut', speed: 2.4 })
      tote(b, { x: 1.2, z: 0.9, ry: 0.2, kind: 'wire', color: '#3a6ab0' })
      elecPanel(b, { x: 1.75, y: 1.3, z: 0.1, ry: -Math.PI / 2 })
      cable(b, [[1.7, 1.2, 0.1], [1.2, 2.3, -0.3], [-1.35, 2.35, -0.55], [-1.35, 0.4, -0.55]], { sag: 0.15 })
      fluoroLight(b, { x: 0, y: 2.5, z: -0.4, len: 1.4 })
      I.lights.push({ x: 0, y: 2.4, z: -0.4, color: '#e8f0ff', intensity: 3.4, dist: 9, when: 'night' })
      I.emitters.push({ kind: 'sparks', x: 1.2, y: 0.85, z: -1.0, when: 'active' })
      I.spots.push({ x: 1.2, z: -0.45, face: FACE_BACK, anim: 'hammer' }, { x: -0.6, z: 1.35, face: FACE_BACK, anim: 'type' })
      return
    }
    // L3: an automated cell in a steel shed: twin hoppers, a long belt, a
    // caged press with a pick-and-place arm, a wire-drawing machine, and a
    // control cabinet with a beacon
    slab(b, I, { color: '#b4b0a8' })
    decal(b, stripeMat(), 3.6, 0.12, { y: 0.051, z: 0.2, rx: -Math.PI / 2, repeat: 1.6 })
    steelShed(b, I, { w: 3.8, d: 3.4, h: 3.1, hb: 2.7, color: '#4a5a66', clad: '#8e969c' })
    for (const x of [-1.5, -0.95]) hopper(b, { x, z: -1.15, w: 0.48, h: 1.35, color: '#c8a020', fill: '#7a6a58', fillMat: 'rust' })
    conveyor(b, I, { x: 0.05, z: -0.75, len: 2.9, w: 0.42, h: 0.8, color: '#d8a020', name: 'line', speed: 0.6, gap: 0.26, items: (p, x, r) => (r() < 0.4 ? p.box(0.08, 0.04, 0.08, { mat: 'steel', color: '#a8acb0', x, y: 0.022 }) : p.torus(0.045, 0.016, { mat: 'steel', color: '#b8bcc0', x, y: 0.025, rx: Math.PI / 2, rs: 4, ts2: 10 })) })
    shopPress(b, I, { x: 1.3, z: -1.2, w: 0.7, h: 1.8, color: '#c8302a', name: 'ram', speed: 1.3 })
    cage(b, 1.0, 0.8, 1.9, { x: 1.3, z: -1.15, color: '#d8a020', open: ['z1'] })
    // pick-and-place arm on a pedestal
    b.at({ x: 0.6, z: -0.2 }, () => {
      b.cyl(0.14, 0.18, 0.5, { mat: 'paint', color: '#d8a020', y: 0.25, seg: 14 })
      b.pivot('arm', { y: 0.52 }, (p) => {
        p.cyl(0.11, 0.11, 0.12, { mat: 'paint', color: '#e0b030', seg: 14 })
        p.beam([0, 0.05, 0], [0, 0.55, 0.32], 0.09, 0.09, { mat: 'paint', color: '#e0b030' })
        p.beam([0, 0.55, 0.32], [0, 0.42, 0.7], 0.07, 0.07, { mat: 'paint', color: '#e0b030' })
        p.box(0.1, 0.1, 0.06, { mat: 'metal', color: '#3a3a3a', y: 0.36, z: 0.72 })
        for (const s of [-1, 1]) p.box(0.015, 0.08, 0.02, { mat: 'chrome', color: '#c8ccd0', x: s * 0.03, y: 0.29, z: 0.74 })
      })
      I.anims.push({ name: 'arm', kind: 'yaw', speed: 1.2, swing: 1.1, when: 'active' })
    })
    // wire-drawing: a stepped capstan line and a take-up spool
    b.at({ x: -0.9, z: 0.85 }, () => {
      b.box(1.1, 0.7, 0.5, { mat: 'paint', color: '#3a5a6a', y: 0.35, r: 0.02 })
      b.box(0.3, 0.12, 0.012, { mat: 'glowGreen', color: '#1a3a1a', x: 0.3, y: 0.58, z: 0.256 })
      for (let i = 0; i < 4; i++) {
        b.pivot('cap' + i, { x: -0.38 + i * 0.22, y: 0.78 }, (p) => {
          p.cyl(0.09 - i * 0.012, 0.09 - i * 0.012, 0.06, { mat: 'chrome', color: '#c8ccd0', seg: 16 })
          p.box(0.02, 0.07, 0.15, { mat: 'metal', color: '#4a4a4a', y: 0.0 })
        })
        I.anims.push({ name: 'cap' + i, kind: 'spin', axis: 'y', speed: 4 + i * 2, when: 'active' })
      }
      b.rope([[-0.6, 0.82, 0], [-0.38, 0.82, 0.08], [0.28, 0.82, 0.08], [0.62, 0.7, 0.0]], 0.004, { mat: 'metal', color: COPPER, sag: 0.0 })
    })
    wireSpool(b, I, { x: 0.35, z: 0.95, ry: Math.PI / 2, name: 'spoolOut', speed: 3 })
    controlPanel(b, { x: 1.55, z: 0.6, ry: -Math.PI / 2, seed: 61 })
    beacon(b, I, { x: 1.3, y: 1.95, z: -0.72, name: 'beacon', color: '#ffb020' })
    pallet(b, { x: 1.35, z: 1.35, w: 1.0, d: 0.8 })
    for (let i = 0; i < 4; i++) crate(b, { x: 1.15 + (i % 2) * 0.42, y: 0.14 + Math.floor(i / 2) * 0.32, z: 1.35, w: 0.36, h: 0.3, d: 0.36, color: '#c8b08a' })
    fluoroLight(b, { x: -0.3, y: 2.65, z: -0.4, len: 1.6 })
    fluoroLight(b, { x: -0.3, y: 2.65, z: 0.6, len: 1.6 })
    I.lights.push({ x: -0.3, y: 2.55, z: 0.1, color: '#e8f0ff', intensity: 4, dist: 10, when: 'night' })
    I.emitters.push({ kind: 'sparks', x: 1.3, y: 0.9, z: -1.1, when: 'active' })
    I.spots.push({ x: 1.2, z: 0.6, face: Math.PI / 2, anim: 'type' }, { x: -0.9, z: 1.45, face: FACE_BACK, anim: 'search' })
  },

  // ============================================================ MACHINE SHOP
  // Circuit boards at the electronics end, motors at the heavy end, and the
  // Signal's coils and amplifiers on the test rig.
  assembler(b, L, I) {
    const rnd = seeded(3300 + L)
    if (L === 1) {
      // a timber shed: a long bench with a soldering station, a lathe, a
      // coil-winding jig and motors stripped down for rewinding
      timberShed(b, I, { w: 4.7, d: 3.7, seed: 41, wall: '#b8a888' })
      const by = heavyBench(b, { x: -0.6, z: -1.35, w: 2.8, d: 0.7, drawers: 2, seed: 8 })
      boardMat(b, { x: -1.4, y: by, z: -1.35 })
      solderIron(b, { x: -0.95, y: by, z: -1.45 })
      oscilloscope(b, { x: -0.55, y: by, z: -1.5 })
      componentDrawers(b, { x: -1.6, y: by + 0.02, z: -1.6, cols: 5, rows: 4 })
      coilWinder(b, I, { x: 0.25, y: by, z: -1.35 })
      motor(b, I, { x: 0.65, y: by, z: -1.4, s: 0.75, color: '#5a4a3a' })
      const lamp = benchLamp(b, { x: -1.2, y: by, z: -1.55 })
      I.lights.push({ x: lamp[0], y: lamp[1], z: lamp[2], color: '#ffd8a0', intensity: 2, dist: 5, when: 'active' })
      metalLathe(b, I, { x: 1.5, z: -0.2, ry: -Math.PI / 2, len: 1.2, name: 'chuck' })
      drillPress(b, I, { x: 1.55, z: 1.0, floor: true, ry: -Math.PI / 2, name: 'feed' })
      // motors waiting for rewinding on a pallet
      pallet(b, { x: -1.45, z: 0.85, w: 1.1, d: 0.9 })
      for (let i = 0; i < 3; i++) motor(b, null, { x: -1.75 + i * 0.3, y: 0.14, z: 0.85 + (i % 2) * 0.2, ry: rnd(), s: 0.85, color: ['#5a4a3a', '#3a5a6a', '#6a3a2a'][i] })
      tote(b, { x: -0.3, z: 1.15, kind: 'wire', color: '#3a6ab0' })
      hangBulb(b, I, -0.6, 2.35, -0.9)
      hangBulb(b, I, 0.9, 2.35, 0.3)
      I.spots.push({ x: -1.0, z: -0.75, face: FACE_BACK, anim: 'type' }, { x: 0.9, z: -0.2, face: Math.PI / 2, anim: 'search' })
      return
    }
    if (L === 2) {
      // a steel shed: the assembly line along the back, a press and lathe,
      // a coil winder and a motor test stand spinning a fan
      slab(b, I, { color: '#aeaaa2' })
      stains(b, rnd, -2.0, 2.0, -1.5, 1.5, 4, 0.09)
      steelShed(b, I, { w: 4.7, d: 3.6, h: 3.0, hb: 2.65, color: '#4e5a52', clad: '#929a96' })
      const by = heavyBench(b, { x: -1.0, z: -1.4, w: 2.4, d: 0.7, metal: true, steelTop: true, drawers: 3, seed: 9 })
      boardMat(b, { x: -1.6, y: by, z: -1.4 })
      solderIron(b, { x: -1.1, y: by, z: -1.5 })
      crtMonitor(b, { x: -0.6, y: by, z: -1.55 })
      componentDrawers(b, { x: -1.9, y: by + 0.02, z: -1.62, cols: 6, rows: 5 })
      coilWinder(b, I, { x: 0.0, y: by, z: -1.35 })
      shopPress(b, I, { x: 1.0, z: -1.3, w: 0.75, h: 1.75, color: '#3a5a7a', name: 'ram', speed: 0.8 })
      metalLathe(b, I, { x: 1.75, z: 0.1, ry: -Math.PI / 2, len: 1.3, name: 'chuck' })
      // motor test stand
      b.at({ x: 0.1, z: 0.65 }, () => {
        b.box(0.8, 0.6, 0.5, { mat: 'paint', color: '#4a4e52', y: 0.3 })
        motor(b, I, { y: 0.6, s: 1.1, color: '#3a6a8a', name: 'fan' })
        b.box(0.2, 0.25, 0.04, { mat: 'paint', color: '#d8d4c8', x: -0.25, y: 0.75, z: 0.24 })
        b.cyl(0.06, 0.06, 0.02, { mat: 'chrome', color: '#e8e4dc', x: -0.25, y: 0.78, z: 0.265, rx: Math.PI / 2, seg: 14 })
      })
      for (let i = 0; i < 2; i++) tote(b, { x: -1.6 + i * 0.65, z: 0.95, ry: (rnd() - 0.5) * 0.4, kind: i ? 'boards' : 'wire', color: i ? '#2a6a4a' : '#3a6ab0' })
      drum(b, { x: 1.8, z: 1.25, color: '#5a6a3a' })
      elecPanel(b, { x: -2.25, y: 1.3, z: 0.2, ry: Math.PI / 2 })
      cable(b, [[-2.2, 1.2, 0.2], [-1.0, 2.4, -0.4], [0.9, 2.4, -0.9], [1.0, 1.8, -1.2]], { sag: 0.15 })
      fluoroLight(b, { x: -0.6, y: 2.55, z: -0.6, len: 1.6 })
      fluoroLight(b, { x: 1.0, y: 2.55, z: 0.4, len: 1.2 })
      I.lights.push({ x: 0, y: 2.45, z: -0.2, color: '#e8f0ff', intensity: 4, dist: 10, when: 'night' })
      I.emitters.push({ kind: 'solder', x: -1.1, y: by + 0.12, z: -1.4, when: 'active' })
      I.spots.push({ x: -1.2, z: -0.8, face: FACE_BACK, anim: 'type' }, { x: 1.0, z: -0.6, face: FACE_BACK, anim: 'hammer' }, { x: 0.1, z: 1.25, face: FACE_BACK, anim: 'search' })
      return
    }
    // L3: a full machine shop with a gantry crane, a CNC mill, the assembly
    // line, and the amplifier test rig glowing at the back
    slab(b, I, { color: '#b4b0a8' })
    decal(b, stripeMat(), 4.6, 0.12, { y: 0.051, z: 0.35, rx: -Math.PI / 2, repeat: 1.6 })
    steelShed(b, I, { w: 4.8, d: 3.7, h: 3.4, hb: 3.0, color: '#3e4a56', clad: '#8a9298' })
    // gantry crane spanning the shop, the hoist sliding along it
    for (const x of [-2.15, 2.15]) {
      b.box(0.12, 2.9, 0.12, { mat: 'paint', color: '#d8a020', x, y: 1.45, z: 0.15 })
      b.box(0.5, 0.06, 0.5, { mat: 'metal', color: '#4a4a48', x, y: 0.03, z: 0.15 })
    }
    b.box(4.4, 0.18, 0.16, { mat: 'paint', color: '#d8a020', y: 2.95, z: 0.15 })
    b.pivot('hoist', { y: 2.84, z: 0.15 }, (p) => {
      p.box(0.3, 0.16, 0.24, { mat: 'paint', color: '#3a3a3a' })
      p.cyl(0.006, 0.006, 1.1, { mat: 'steel', color: '#8a8e92', y: -0.62, seg: 4 })
      p.torus(0.06, 0.015, { mat: 'steel', color: '#5a5e62', y: -1.2, rs: 5, ts2: 10 })
      motor(p, null, { x: -0.18, y: -1.55, s: 0.9, color: '#3a6a8a' })
    })
    I.anims.push({ name: 'hoist', kind: 'slide', axis: 'x', amp: 1.2, speed: 0.08, when: 'active' })
    cncMill(b, I, { x: 1.55, z: -1.15, ry: 0 })
    const by = heavyBench(b, { x: -1.15, z: -1.45, w: 2.3, d: 0.68, metal: true, steelTop: true, drawers: 3, seed: 10 })
    boardMat(b, { x: -1.7, y: by, z: -1.45 })
    crtMonitor(b, { x: -0.6, y: by, z: -1.6 })
    coilWinder(b, I, { x: -1.1, y: by, z: -1.38 })
    // the amplifier test rig: a rack of glowing tubes and meters
    b.at({ x: -0.15, z: -1.55 }, () => {
      b.box(0.7, 1.6, 0.4, { mat: 'paint', color: '#2a2e32', y: 0.8, r: 0.02 })
      for (let r = 0; r < 3; r++) {
        b.box(0.62, 0.02, 0.36, { mat: 'metal', color: '#5a5e62', y: 0.5 + r * 0.42 })
        for (let k = 0; k < 4; k++) {
          b.cyl(0.035, 0.035, 0.14, { mat: 'glass', color: '#e8e0c8', x: -0.21 + k * 0.14, y: 0.59 + r * 0.42, z: 0.05, seg: 10 })
          b.cyl(0.018, 0.018, 0.08, { mat: r === 1 ? 'glowAmber' : 'glowWarm', color: '#ffffff', x: -0.21 + k * 0.14, y: 0.58 + r * 0.42, z: 0.05, seg: 6 })
        }
        b.cyl(0.04, 0.04, 0.01, { mat: 'chrome', color: '#e8e4dc', x: 0.22, y: 0.42 + r * 0.42, z: 0.205, rx: Math.PI / 2, seg: 12 })
      }
    })
    I.lights.push({ x: -0.15, y: 1.2, z: -1.2, color: '#ffb060', intensity: 2.2, dist: 5, when: 'active' })
    motor(b, I, { x: 0.75, y: 0.0, z: 0.6, s: 1.3, color: '#3a6a8a', name: 'fan' })
    pallet(b, { x: -1.5, z: 1.0, w: 1.1, d: 0.9 })
    for (let i = 0; i < 4; i++) crate(b, { x: -1.72 + (i % 2) * 0.44, y: 0.14 + Math.floor(i / 2) * 0.34, z: 1.0, w: 0.38, h: 0.32, d: 0.38, color: '#9aa47a' })
    controlPanel(b, { x: 2.0, z: 0.9, ry: -Math.PI / 2, seed: 64 })
    beacon(b, I, { x: 2.15, y: 3.05, z: 0.15, name: 'beacon', color: '#ffb020' })
    for (const z of [-0.8, 0.8]) fluoroLight(b, { x: 0, y: 2.8, z, len: 1.8 })
    I.lights.push({ x: 0, y: 2.7, z: 0, color: '#e8f0ff', intensity: 4.5, dist: 11, when: 'night' })
    I.emitters.push({ kind: 'solder', x: -1.7, y: by + 0.1, z: -1.4, when: 'active' }, { kind: 'sparks', x: 1.55, y: 1.0, z: -0.9, when: 'active' })
    I.spots.push({ x: -1.4, z: -0.85, face: FACE_BACK, anim: 'type' }, { x: 1.5, z: -0.4, face: FACE_BACK, anim: 'search' }, { x: 1.6, z: 0.9, face: Math.PI / 2, anim: 'type' })
  },
}
