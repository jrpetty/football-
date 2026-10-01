// Power and fuel stations: the steam engine, the charcoal kiln, the wind
// turbine and the battery bank. Same conventions as stationsHD.js: footprint
// centred on the origin, working side to +z, three looks per station from
// improvised to engineered, and every model reports worker spots, pivots,
// lights, flames and emitters through I.
import { seeded } from './kit.js'
import { shadeHex, barrel, crate, pallet, sack, firewood, logPile, wheelbarrow, bucket, gauge, valveWheel, carBattery, rock, cinderBlocks, ladder, blob, jerrycan } from './parts.js'
import { slab, roof } from './stationkit.js'
import { pipeRun, cable, controlPanel, beacon, elecPanel, plateMat, decal, stripeMat, tinLeanTo, drum } from './detail.js'

const TAU = Math.PI * 2
const FACE_BACK = Math.PI

// A riveted horizontal boiler drum along x, with a smokebox at -x.
function boilerDrum(b, o = {}) {
  const r = o.r ?? 0.55
  const L = o.len ?? 2.2
  const c = o.color ?? '#5a524a'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0 }, () => {
    b.cyl(r, r, L, { mat: o.mat ?? 'rust', color: c, rz: Math.PI / 2, seg: 22 })
    for (let i = 0; i <= 4; i++) b.torus(r + 0.008, 0.014, { mat: 'metal', color: shadeHex(c, -0.25), x: -L / 2 + (i * L) / 4, ry: Math.PI / 2, rs: 4, ts2: 26 })
    for (let i = 0; i < 18; i++) {
      const a = (i / 18) * TAU
      b.sphere(0.018, { mat: 'metal', color: shadeHex(c, -0.3), x: -L / 2 + L * 0.25, y: Math.cos(a) * (r + 0.01), z: Math.sin(a) * (r + 0.01), ws: 4, hs: 3 })
    }
    // smokebox door at -x
    b.cyl(r * 0.9, r * 0.9, 0.08, { mat: 'metal', color: '#2a2826', x: -L / 2 - 0.04, rz: Math.PI / 2, seg: 20 })
    b.torus(r * 0.55, 0.02, { mat: 'metal', color: '#4a4846', x: -L / 2 - 0.09, ry: Math.PI / 2, rs: 4, ts2: 18 })
    // safety valve, steam dome and a whistle
    b.cyl(0.16, 0.18, 0.3, { mat: 'metal', color: '#8a6a3a', x: L * 0.1, y: r + 0.1, seg: 14 })
    b.sphere(0.16, { mat: 'metal', color: '#8a6a3a', x: L * 0.1, y: r + 0.25, sy: 0.6, ws: 12, hs: 6 })
    b.cyl(0.03, 0.03, 0.25, { mat: 'chrome', color: '#c8a868', x: L * 0.3, y: r + 0.12, seg: 8 })
    gauge(b, { x: L / 2 - 0.2, y: r * 0.4, z: r + 0.01 })
  })
}
// A spoked flywheel on a pivot that spins about x.
function flywheel(b, I, name, o = {}) {
  const R = o.r ?? 0.6
  b.pivot(name, { x: o.x || 0, y: o.y || 0, z: o.z || 0 }, (p) => {
    p.torus(R, 0.06, { mat: 'metal', color: o.color ?? '#3a3a3a', ry: Math.PI / 2, rs: 6, ts2: 32 })
    p.cyl(R + 0.01, R + 0.01, 0.12, { mat: 'metal', color: o.color ?? '#3a3a3a', rz: Math.PI / 2, seg: 32, open: true })
    for (let i = 0; i < 6; i++) p.box(0.05, R * 2 - 0.08, 0.06, { mat: 'metal', color: shadeHex(o.color ?? '#3a3a3a', 0.1), rx: (i / 6) * Math.PI })
    p.cyl(0.1, 0.1, 0.2, { mat: 'metal', color: '#6a6a66', rz: Math.PI / 2, seg: 12 })
    p.box(0.08, 0.1, 0.08, { mat: 'paint', color: '#c8302a', y: R - 0.06 })
  })
  I.anims.push({ name, kind: 'spin', axis: 'x', speed: o.speed ?? 2.4, when: 'active' })
}
// A dynamo: a ribbed drum with brass terminals, belt-driven from a pulley.
function dynamo(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0 }, () => {
    b.box(0.6, 0.12, 0.5, { mat: 'paint', color: '#2a2a2a', y: 0.06 })
    b.cyl(0.26, 0.26, 0.55, { mat: 'paint', color: o.color ?? '#2a4a3a', y: 0.38, rz: Math.PI / 2, seg: 18 })
    for (let i = 0; i < 10; i++) b.box(0.5, 0.025, 0.03, { mat: 'paint', color: shadeHex(o.color ?? '#2a4a3a', -0.2), y: 0.38 + Math.cos((i / 10) * TAU) * 0.26, z: Math.sin((i / 10) * TAU) * 0.26, rx: (i / 10) * TAU })
    for (const s of [-1, 1]) b.cyl(0.02, 0.02, 0.08, { mat: 'chrome', color: '#d8a848', x: s * 0.1, y: 0.68, seg: 6 })
    b.cyl(0.12, 0.12, 0.08, { mat: 'metal', color: '#4a4a4a', x: -0.32, y: 0.38, rz: Math.PI / 2, seg: 14 })
  })
}
// A tall stack with banding, a rain cap and guy wires.
function stack(b, o = {}) {
  const h = o.h ?? 5
  const r = o.r ?? 0.14
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0 }, () => {
    b.cyl(r, r * 1.15, h, { mat: o.mat ?? 'metal', color: o.color ?? '#3a3634', y: h / 2, seg: 14 })
    for (let k = 1; k < 4; k++) b.torus(r + 0.01, 0.015, { mat: 'metal', color: '#2a2826', y: (k * h) / 4, rx: Math.PI / 2, rs: 4, ts2: 16 })
    if (o.bands) for (const y of [h - 0.6, h - 1.1]) b.cyl(r + 0.012, r + 0.012, 0.22, { mat: 'paint', color: '#c8302a', y, seg: 14 })
    b.cone(r * 2, 0.22, { mat: 'metal', color: '#2a2826', y: h + 0.25, seg: 12 })
    for (let i = 0; i < 3; i++) b.cyl(0.012, 0.012, 0.18, { mat: 'metal', color: '#2a2826', x: Math.cos((i / 3) * TAU) * r, y: h + 0.08, z: Math.sin((i / 3) * TAU) * r, seg: 4 })
    if (o.guys) for (let i = 0; i < 3; i++) {
      const a = (i / 3) * TAU + 0.5
      b.rope([[0, h * 0.75, 0], [Math.cos(a) * h * 0.45, 0.02, Math.sin(a) * h * 0.45]], 0.006, { mat: 'steel', color: '#5a5a5a', sag: 0.02, steps: 2 })
    }
  })
  return [o.x || 0, (o.y || 0) + h + 0.3, o.z || 0]
}
// A heap of coal (or charcoal) on the ground.
function coalHeap(b, rnd, o = {}) {
  b.at({ x: o.x || 0, z: o.z || 0 }, () => {
    b.sphere(o.r ?? 0.6, { mat: 'gravel', color: o.color ?? '#242220', y: -(o.r ?? 0.6) * 0.55, sy: 0.6, ws: 14, hs: 8 })
    for (let i = 0; i < 14; i++) b.dodeca(0.05 + rnd() * 0.05, { mat: 'gloss', color: '#1a1a1a', x: (rnd() - 0.5) * (o.r ?? 0.6) * 1.6, y: 0.04 + rnd() * 0.12, z: (rnd() - 0.5) * (o.r ?? 0.6) * 1.6 })
  })
}
// Three-legged lattice tower (base half-width w0, top w1, height h).
function lattice(b, h, w0, w1, o = {}) {
  const m = { mat: o.mat ?? 'paint', color: o.color ?? '#9aa0a4' }
  const legs = [0, 1, 2].map((i) => (i / 3) * TAU + Math.PI / 2)
  const at = (a, y) => {
    const w = w0 + (w1 - w0) * (y / h)
    return [Math.cos(a) * w, y, Math.sin(a) * w]
  }
  for (const a of legs) b.beam(at(a, 0), at(a, h), 0.08, 0.08, m)
  const n = o.bays ?? Math.round(h / 1.4)
  for (let k = 0; k < n; k++) {
    const y0 = (k * h) / n
    const y1 = ((k + 1) * h) / n
    for (let i = 0; i < 3; i++) {
      const a = legs[i]
      const c = legs[(i + 1) % 3]
      b.beam(at(a, y1), at(c, y1), 0.04, 0.04, m)
      b.beam(at(a, y0), at(c, y1), 0.03, 0.03, m)
      b.beam(at(c, y0), at(a, y1), 0.03, 0.03, m)
    }
  }
  for (const a of legs) {
    const p = at(a, 0)
    b.box(0.4, 0.25, 0.4, { mat: 'concrete', color: '#b0aca4', x: p[0], y: 0.12, z: p[2] })
  }
}
// Turbine head: nacelle and tail on a yaw pivot, rotor on a spin pivot.
function turbineHead(b, I, o = {}) {
  const R = o.r ?? 1.8
  b.pivot('head', { y: o.y }, (p) => {
    p.box(0.42, 0.42, 1.0, { mat: 'paint', color: o.color ?? '#d8d8d0', r: 0.08 })
    p.box(0.44, 0.06, 1.02, { mat: 'paint', color: '#c8302a', y: 0.08 })
    p.beam([0, 0.05, -0.4], [0, 0.15, -1.9], 0.06, 0.06, { mat: 'paint', color: '#9aa0a4' })
    p.extrude([[0, 0], [0, 0.9], [-0.9, 0.55], [-0.9, 0.1]], 0.02, { mat: 'paint', color: o.color ?? '#d8d8d0', x: 0.01, y: -0.2, z: -1.7, ry: Math.PI / 2 })
    p.cone(0.18, 0.32, { mat: 'paint', color: '#e8e8e0', z: 0.68, rx: Math.PI / 2, seg: 14 })
    p.pivot('rotor', { z: 0.6 }, (q) => {
      q.cyl(0.12, 0.12, 0.2, { mat: 'metal', color: '#6a6e72', rx: Math.PI / 2, seg: 12 })
      for (let i = 0; i < 3; i++) {
        const a = (i / 3) * TAU
        q.at({ rz: a }, () => {
          q.extrude([[-0.12, 0.1], [0.14, 0.1], [0.08, R * 0.55], [0.02, R], [-0.04, R], [-0.1, R * 0.4]], 0.035, { mat: 'paint', color: o.blade ?? '#e8e8e0', z: -0.02 })
          q.box(0.03, 0.12, 0.04, { mat: 'paint', color: '#c8302a', y: R - 0.08, z: 0.0 })
        })
      }
    })
  })
  I.anims.push({ name: 'rotor', kind: 'spin', axis: 'z', speed: o.speed ?? 3, when: 'active' }, { name: 'head', kind: 'yaw', speed: 0.05, swing: 0.4, when: 'always' })
}
// A rack of power cells: tall cylinders in a steel frame with charge lamps.
function cellRack(b, o = {}) {
  const n = o.n ?? 6
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (const s of [-1, 1]) for (const t of [-1, 1]) b.box(0.04, 1.6, 0.04, { mat: 'steel', color: '#7a8086', x: (s * (n * 0.17 + 0.05)) / 2, y: 0.8, z: t * 0.2 })
    for (const y of [0.05, 0.8, 1.58]) b.box(n * 0.17 + 0.1, 0.03, 0.44, { mat: 'steel', color: '#7a8086', y })
    for (let r = 0; r < 2; r++) for (let i = 0; i < n; i++) {
      const x = -n * 0.085 + 0.085 + i * 0.17
      b.cyl(0.07, 0.07, 0.62, { mat: 'plastic', color: o.color ?? '#4a8ab0', x, y: 0.4 + r * 0.76, seg: 12 })
      b.cyl(0.072, 0.072, 0.06, { mat: 'plastic', color: '#1a1a1a', x, y: 0.7 + r * 0.76, seg: 12 })
      b.cyl(0.02, 0.02, 0.04, { mat: 'chrome', color: '#d8a848', x, y: 0.75 + r * 0.76, seg: 6 })
      b.box(0.03, 0.03, 0.01, { mat: i % 4 === 3 ? 'glowAmber' : 'glowGreen', color: '#ffffff', x, y: 0.45 + r * 0.76, z: 0.07, shadow: false })
    }
    cable(b, [[-n * 0.085, 1.5, 0.2], [0, 1.45, 0.25], [n * 0.085, 1.5, 0.2]], { r: 0.015, sag: 0.04 })
  })
}

export const POWER = {
  // ============================================================ STEAM ENGINE
  boiler(b, L, I) {
    const rnd = seeded(4100 + L)
    if (L === 1) {
      // a scrap boiler on bricks, a flywheel engine and a car dynamo
      b.box(4.8, 0.02, 3.8, { mat: 'dirt', color: '#5a4a3a', y: 0.0 })
      for (const x of [-1.3, -0.1]) b.box(0.6, 0.45, 1.1, { mat: 'brick', color: '#b07a62', x, y: 0.22, z: -0.9 })
      boilerDrum(b, { x: -0.7, y: 1.0, z: -0.9, r: 0.5, len: 2.0, color: '#6a5a4a' })
      // firebox under the drum: an open grate glowing
      b.box(1.0, 0.4, 0.8, { mat: 'plain', color: '#1a1410', x: -0.7, y: 0.22, z: -0.9 })
      b.box(0.5, 0.25, 0.04, { mat: 'embers', color: '#ffffff', x: -0.7, y: 0.22, z: -0.48 })
      for (let i = 0; i < 6; i++) b.sphere(0.06, { mat: 'embers', color: '#ffffff', x: -0.9 + rnd() * 0.4, y: 0.12, z: -0.42 + rnd() * 0.08, ws: 6, hs: 4 })
      I.flames.push({ x: -0.7, y: 0.12, z: -0.46, w: 0.35, h: 0.28, when: 'active' })
      I.lights.push({ x: -0.7, y: 0.5, z: -0.1, color: '#ff8030', intensity: 4, dist: 7, flicker: true, when: 'active' })
      const top = stack(b, { x: -1.6, y: 0.4, z: -0.9, h: 3.4, r: 0.12, guys: true })
      I.emitters.push({ kind: 'chimney', x: top[0], y: top[1], z: top[2], when: 'active' }, { kind: 'steam', x: -0.55, y: 1.85, z: -0.9, when: 'active' })
      // engine: cylinder, piston rod and the flywheel
      pipeRun(b, [[0.25, 1.45, -0.9], [0.9, 1.45, -0.9], [0.9, 0.85, -0.6]], 0.05, { color: '#5a4a3a' })
      b.at({ x: 1.1, z: -0.5 }, () => {
        b.box(1.3, 0.3, 0.5, { mat: 'metal', color: '#3a3a3a', y: 0.15 })
        b.cyl(0.18, 0.18, 0.55, { mat: 'metal', color: '#6a5a4a', x: -0.3, y: 0.55, rz: Math.PI / 2, seg: 14 })
        b.pivot('rod', { x: 0.05, y: 0.55 }, (p) => p.box(0.5, 0.05, 0.05, { mat: 'chrome', color: '#c8ccd0' }))
        I.anims.push({ name: 'rod', kind: 'slide', axis: 'x', amp: 0.12, speed: 0.38, when: 'active' })
      })
      flywheel(b, I, 'fly', { x: 1.65, y: 0.75, z: -0.5, r: 0.62 })
      dynamo(b, { x: 1.7, z: 0.55, color: '#5a3a2a' })
      b.rope([[1.65, 0.75, -0.42], [1.5, 0.4, 0.55], [1.38, 0.38, 0.55]], 0.012, { mat: 'rubber', color: '#1a1a1a', sag: 0 })
      cable(b, [[1.8, 0.7, 0.55], [2.0, 0.1, 1.0], [2.3, 0.02, 1.6]], { r: 0.012, color: '#d8a020', sag: 0.02 })
      // fuel: a split-wood stack and a heap of coal, a barrow and a bucket
      firewood(b, { x: -1.6, z: 0.75, len: 1.3, h: 0.7 })
      coalHeap(b, rnd, { x: -0.3, z: 1.1, r: 0.45 })
      wheelbarrow(b, { x: 0.6, z: 1.2, ry: -0.6 })
      bucket(b, { x: 0.15, z: 0.25, water: true })
      I.spots.push({ x: -0.7, z: 0.05, face: FACE_BACK, anim: 'pump' })
      return
    }
    if (L === 2) {
      // a brick-set boiler under a tin roof, twin flywheels, a coal bunker
      b.box(4.8, 0.08, 3.8, { mat: 'concrete', color: '#8a867e', y: 0.04 })
      tinLeanTo(b, I, { x: 0, z: -0.45, w: 4.6, d: 2.6, hf: 3.1, hb: 2.7, seed: 41 })
      b.box(2.6, 1.0, 1.3, { mat: 'brick', color: '#b88068', x: -0.9, y: 0.58, z: -0.95 })
      boilerDrum(b, { x: -0.9, y: 1.5, z: -0.95, r: 0.6, len: 2.4, color: '#4a5a52', mat: 'paint' })
      for (const x of [-1.6, -0.6]) {
        b.box(0.42, 0.34, 0.05, { mat: 'metal', color: '#2a2826', x, y: 0.45, z: -0.28 })
        b.box(0.3, 0.2, 0.02, { mat: 'embers', color: '#ffffff', x, y: 0.44, z: -0.25 })
      }
      I.flames.push({ x: -1.1, y: 0.35, z: -0.3, w: 0.6, h: 0.25, when: 'active' })
      I.lights.push({ x: -1.1, y: 0.6, z: 0.2, color: '#ff8030', intensity: 5, dist: 8, flicker: true, when: 'active' })
      // brick chimney, square and tapering
      b.at({ x: -2.05, z: -1.45 }, () => {
        b.lathe([[0.42, 0], [0.4, 2], [0.32, 4.6], [0.36, 4.7], [0.36, 4.9]], { mat: 'brick', color: '#a86a52', seg: 4, ry: Math.PI / 4 })
      })
      I.emitters.push({ kind: 'chimney', x: -2.05, y: 5.0, z: -1.45, when: 'active' }, { kind: 'steam', x: -0.8, y: 2.35, z: -0.95, when: 'active' })
      pipeRun(b, [[0.3, 2.0, -0.95], [0.8, 2.0, -0.95], [0.8, 1.0, -0.7]], 0.06, { color: '#4a5a52' })
      valveWheel(b, { x: 0.8, y: 1.6, z: -0.82, r: 0.12 })
      b.at({ x: 1.2, z: -0.65 }, () => {
        b.box(1.6, 0.35, 0.6, { mat: 'metal', color: '#2a2a2a', y: 0.18 })
        b.cyl(0.22, 0.22, 0.6, { mat: 'paint', color: '#4a5a52', x: -0.35, y: 0.62, rz: Math.PI / 2, seg: 16 })
        b.pivot('rod', { x: 0.1, y: 0.62 }, (p) => p.box(0.6, 0.06, 0.06, { mat: 'chrome', color: '#c8ccd0' }))
        I.anims.push({ name: 'rod', kind: 'slide', axis: 'x', amp: 0.15, speed: 0.45, when: 'active' })
      })
      flywheel(b, I, 'fly', { x: 1.95, y: 0.85, z: -0.65, r: 0.72, color: '#2a3a32' })
      dynamo(b, { x: 1.85, z: 0.55, color: '#2a4a3a' })
      b.rope([[1.95, 0.85, -0.55], [1.7, 0.4, 0.55]], 0.014, { mat: 'rubber', color: '#1a1a1a', sag: 0 })
      b.at({ x: 2.15, z: 1.2 }, () => {
        b.box(0.12, 1.8, 0.12, { mat: 'wood', color: '#8a7050', y: 0.9 })
        elecPanel(b, { y: 1.1, z: 0.07, conduit: 0.4 })
      })
      // coal bunker of planks, heaped full
      b.at({ x: -0.6, z: 1.1 }, () => {
        for (const s of [-1, 1]) b.box(0.06, 0.6, 1.0, { mat: 'planks', color: '#8a7050', x: s * 0.7, y: 0.3 })
        b.box(1.46, 0.6, 0.06, { mat: 'planks', color: '#8a7050', y: 0.3, z: -0.5 })
        coalHeap(b, rnd, { r: 0.6 })
      })
      logPile(b, { x: -1.9, z: 0.8, len: 1.4, rows: 3, ry: Math.PI / 2 })
      gauge(b, { x: 0.2, y: 1.1, z: -0.29 })
      I.spots.push({ x: -1.1, z: 0.3, face: FACE_BACK, anim: 'pump' }, { x: 1.3, z: 0.2, face: FACE_BACK, anim: 'search' })
      return
    }
    // L3: a turbine hall: twin boilers, a turbine and alternator, a steel stack
    slab(b, I, { h: 0.12, seed: 43 })
    for (const z of [-1.25, -0.25]) boilerDrum(b, { x: -1.2, y: 0.85, z, r: 0.42, len: 2.0, color: '#3a4a5a', mat: 'paint' })
    for (const z of [-1.25, -0.25]) for (const x of [-1.9, -0.5]) b.box(0.15, 0.5, 0.5, { mat: 'concrete', color: '#a8a49c', x, y: 0.37, z })
    b.box(2.0, 0.4, 0.45, { mat: 'brick', color: '#a86a52', x: -1.2, y: 0.32, z: 0.42 })
    b.box(1.4, 0.18, 0.02, { mat: 'embers', color: '#ffffff', x: -1.2, y: 0.3, z: 0.66 })
    I.flames.push({ x: -1.2, y: 0.25, z: 0.6, w: 0.9, h: 0.2, when: 'active' })
    I.lights.push({ x: -1.2, y: 0.6, z: 1.0, color: '#ff8030', intensity: 5, dist: 8, flicker: true, when: 'active' })
    const top = stack(b, { x: -2.15, y: 0.12, z: -1.6, h: 6.2, r: 0.22, bands: true, guys: true })
    ladder(b, { x: -2.15, y: 0.12, z: -1.34, h: 5.6, lean: 0, mat: 'steel', color: '#5a5e62' })
    I.emitters.push({ kind: 'chimney', x: top[0], y: top[1], z: top[2], when: 'active' }, { kind: 'steam', x: 0.3, y: 1.6, z: -0.75, when: 'active' })
    // turbine casing and alternator on a common bed
    b.at({ x: 1.0, z: -0.75 }, () => {
      b.box(2.4, 0.3, 0.9, { mat: 'paint', color: '#2a2a2a', y: 0.27 })
      b.lathe([[0.001, -0.6], [0.32, -0.6], [0.42, -0.2], [0.5, 0.2], [0.5, 0.45], [0.001, 0.45]], { mat: 'paint', color: '#3a5a7a', x: -0.6, y: 0.85, rz: Math.PI / 2, seg: 20 })
      for (let i = 0; i < 3; i++) b.torus(0.46 + i * 0.02, 0.025, { mat: 'metal', color: '#2a3a4a', x: -0.6 - 0.3 + i * 0.3, y: 0.85, ry: Math.PI / 2, rs: 4, ts2: 24 })
      b.cyl(0.42, 0.42, 0.9, { mat: 'paint', color: '#d8a020', x: 0.45, y: 0.85, rz: Math.PI / 2, seg: 20 })
      for (let i = 0; i < 12; i++) b.box(0.8, 0.03, 0.05, { mat: 'paint', color: '#b88a18', x: 0.45, y: 0.85 + Math.cos((i / 12) * TAU) * 0.42, z: Math.sin((i / 12) * TAU) * 0.42, rx: (i / 12) * TAU })
      b.pivot('shaft', { x: 1.0, y: 0.85 }, (p) => {
        p.cyl(0.18, 0.18, 0.12, { mat: 'metal', color: '#5a5e62', rz: Math.PI / 2, seg: 12 })
        for (let i = 0; i < 4; i++) p.box(0.04, 0.34, 0.06, { mat: 'paint', color: '#c8302a', rx: (i / 4) * Math.PI })
      })
      I.anims.push({ name: 'shaft', kind: 'spin', axis: 'x', speed: 14, when: 'active' })
      decal(b, plateMat('TURBINE No.2', '#3a5a7a', '#e8e4d8'), 0.4, 0.12, { x: -0.6, y: 1.36, z: 0.0, rx: -Math.PI / 2 })
    })
    pipeRun(b, [[-0.2, 1.25, -1.25], [0.1, 1.25, -1.25], [0.1, 1.25, -0.75], [0.35, 0.95, -0.75]], 0.07, { color: '#c8c4b8' })
    pipeRun(b, [[-0.2, 1.25, -0.25], [0.1, 1.25, -0.25], [0.1, 1.25, -0.75]], 0.07, { color: '#c8c4b8' })
    valveWheel(b, { x: 0.1, y: 1.55, z: -0.75, r: 0.14 })
    for (const x of [-0.3, 0.55]) gauge(b, { x, y: 1.6, z: -0.25 })
    // coal hopper with a feed chute to the grate
    b.at({ x: 0.9, z: 1.15 }, () => {
      for (const s of [-1, 1]) for (const t of [-1, 1]) b.box(0.08, 1.2, 0.08, { mat: 'paint', color: '#4a4e52', x: s * 0.45, y: 0.6, z: t * 0.35 })
      b.lathe([[0.2, 0], [0.62, 0.6], [0.62, 0.9]], { mat: 'metal', color: '#5a5e62', y: 1.0, seg: 4, ry: Math.PI / 4 })
      coalHeap(b, rnd, { r: 0.5 })
      b.at({ y: 1.85 }, () => b.sphere(0.5, { mat: 'gravel', color: '#1e1c1a', y: -0.12, sy: 0.35, ws: 12, hs: 6 }))
    })
    b.beam([0.6, 1.0, 1.0], [-0.6, 0.45, 0.7], 0.2, 0.12, { mat: 'metal', color: '#5a5e62' })
    controlPanel(b, { x: 2.0, z: 0.75, ry: -0.5 })
    beacon(b, I, { x: 2.15, y: 1.9, z: -1.6 })
    b.plane(4.4, 0.07, { material: stripeMat(), y: 0.122, z: 0.15, rx: -Math.PI / 2, shadow: false })
    I.spots.push({ x: -1.2, z: 1.15, face: FACE_BACK, anim: 'pump' }, { x: 2.3, z: 1.2, face: -2.2, anim: 'type' })
  },

  // ============================================================ CHARCOAL KILN
  kiln(b, L, I) {
    const rnd = seeded(4200 + L)
    if (L === 1) {
      // an earth clamp: logs stacked in a dome under turf, smoking from vents
      b.box(3.8, 0.02, 3.8, { mat: 'dirt', color: '#4e4236', y: 0.0 })
      b.at({ x: -0.3, z: -0.4 }, () => {
        b.sphere(1.25, { mat: 'dirt', color: '#9a8466', y: -0.25, sy: 0.75, ws: 20, hs: 10 })
        for (let i = 0; i < 26; i++) {
          const a = rnd() * TAU
          const r = 0.5 + rnd() * 0.65
          blob(b, 0.25 + rnd() * 0.2, 0.18 + rnd() * 0.12, { rnd, mat: 'grass', color: shadeHex('#7a8a4a', (rnd() - 0.5) * 0.25), x: Math.cos(a) * r, y: 0.55 - r * 0.35, z: Math.sin(a) * r, ry: -a + Math.PI / 2, rx: -0.6, t: 0.03 })
        }
        for (let i = 0; i < 4; i++) {
          const a = (i / 4) * TAU + 0.4
          b.cyl(0.07, 0.07, 0.12, { mat: 'plain', color: '#1a1410', x: Math.cos(a) * 0.8, y: 0.38, z: Math.sin(a) * 0.8, seg: 8 })
          I.emitters.push({ kind: 'smoke', x: -0.3 + Math.cos(a) * 0.8, y: 0.5, z: -0.4 + Math.sin(a) * 0.8, when: 'active' })
        }
        b.cyl(0.12, 0.12, 0.2, { mat: 'plain', color: '#1a1410', y: 0.72, seg: 10 })
        b.box(0.3, 0.2, 0.05, { mat: 'embers', color: '#ffffff', y: 0.1, z: 1.15 })
      })
      I.emitters.push({ kind: 'chimney', x: -0.3, y: 0.9, z: -0.4, when: 'active' })
      I.lights.push({ x: -0.3, y: 0.3, z: 0.9, color: '#ff7030', intensity: 2, dist: 5, flicker: true, when: 'active' })
      logPile(b, { x: 1.35, z: -0.8, len: 1.5, rows: 3, ry: Math.PI / 2 })
      for (let i = 0; i < 5; i++) sack(b, { x: 0.9 + (i % 3) * 0.38, y: Math.floor(i / 3) * 0.22, z: 1.25, ry: 0.2 * i, color: '#3a3632' })
      coalHeap(b, rnd, { x: 1.4, z: 0.4, r: 0.35, color: '#2a2622' })
      barrel(b, { x: -1.5, z: 1.2, color: '#4a4a44', open: true, contents: '#2a3036', fill: 0.85 })
      for (let i = 0; i < 2; i++) b.box(0.05, 1.3, 0.05, { mat: 'wood', color: '#8a7050', x: -1.6 + i * 0.12, y: 0.62, z: 0.5, rz: 0.3 - i * 0.6 })
      I.spots.push({ x: -0.3, z: 1.4, face: FACE_BACK, anim: 'stir' })
      return
    }
    // L2: two brick beehive kilns with iron doors and a shared flue
    b.box(3.8, 0.08, 3.8, { mat: 'concrete', color: '#8a867e', y: 0.04 })
    for (const x of [-0.9, 0.9]) {
      b.at({ x, z: -0.6 }, () => {
        b.lathe([[0.85, 0], [0.86, 0.6], [0.78, 1.0], [0.55, 1.4], [0.25, 1.62], [0.15, 1.65]], { mat: 'brick', color: '#b07a5e', y: 0.08, seg: 22 })
        for (let k = 0; k < 3; k++) b.torus(0.86 - k * 0.04, 0.02, { mat: 'metal', color: '#3a3a3a', y: 0.35 + k * 0.3, rx: Math.PI / 2, rs: 4, ts2: 28 })
        b.box(0.5, 0.6, 0.06, { mat: 'metal', color: '#3a3836', y: 0.42, z: 0.84 })
        b.box(0.36, 0.06, 0.02, { mat: 'embers', color: '#ffffff', y: 0.14, z: 0.87 })
        b.cyl(0.03, 0.03, 0.14, { mat: 'metal', color: '#2a2a2a', x: 0.18, y: 0.45, z: 0.9, rx: Math.PI / 2, seg: 6 })
        b.cyl(0.14, 0.16, 0.5, { mat: 'metal', color: '#3a3634', y: 1.9, seg: 10 })
      })
      I.emitters.push({ kind: 'smoke', x, y: 2.2, z: -0.6, when: 'active' })
      I.lights.push({ x, y: 0.3, z: 0.4, color: '#ff7030', intensity: 2.5, dist: 5, flicker: true, when: 'active' })
    }
    logPile(b, { x: -1.4, z: 1.2, len: 1.6, rows: 3 })
    pallet(b, { x: 1.1, z: 1.15, w: 1.1, d: 0.9 })
    for (let i = 0; i < 6; i++) sack(b, { x: 0.85 + (i % 3) * 0.28, y: 0.13 + Math.floor(i / 3) * 0.2, z: 1.15, ry: 0.15 * i, color: '#2e2a26' })
    coalHeap(b, rnd, { x: 0, z: 0.75, r: 0.4, color: '#2a2622' })
    decal(b, plateMat('CHARCOAL', '#3a3632', '#e8e4d8', 'flame'), 0.5, 0.18, { x: 1.1, y: 1.0, z: 1.62 })
    b.box(0.06, 1.2, 0.06, { mat: 'wood', color: '#8a7050', x: 1.1, y: 0.6, z: 1.6 })
    I.spots.push({ x: -0.9, z: 0.75, face: FACE_BACK, anim: 'stir' }, { x: 0.9, z: 0.75, face: FACE_BACK, anim: 'search' })
  },

  // ============================================================ WIND TURBINE
  wind(b, L, I) {
    const H = L === 1 ? 8.5 : 12
    b.box(2.8, 0.02, 2.8, { mat: 'gravel', color: '#9a9488', y: 0.0 })
    lattice(b, H, L === 1 ? 1.0 : 1.2, 0.22, { color: L === 1 ? '#8a9096' : '#aeb2b6', mat: L === 1 ? 'rust' : 'paint', bays: L === 1 ? 6 : 8 })
    b.box(0.7, 0.08, 0.7, { mat: 'steel', color: '#7a8086', y: H })
    for (let i = 0; i < 4; i++) b.box(0.04, 0.4, 0.04, { mat: 'steel', color: '#7a8086', x: Math.cos(i * 1.571 + 0.785) * 0.45, y: H + 0.2, z: Math.sin(i * 1.571 + 0.785) * 0.45 })
    turbineHead(b, I, { y: H + 0.35, r: L === 1 ? 1.6 : 2.3, color: L === 1 ? '#a8a49a' : '#c4c4bc', blade: L === 1 ? '#b8b0a2' : '#cacac4', speed: L === 1 ? 3.4 : 2.6 })
    ladder(b, { x: 0, y: 0, z: 0.45, h: H - 0.4, lean: 0.05, mat: 'steel', color: '#6a7076' })
    cable(b, [[0, H, -0.1], [0.12, H * 0.5, 0.0], [0.6, 0.9, 0.9]], { r: 0.014, sag: 0.01 })
    b.at({ x: 0.75, z: 0.95 }, () => {
      b.box(0.1, 1.1, 0.1, { mat: 'metal', color: '#7a8086', y: 0.55 })
      b.box(0.45, 0.55, 0.2, { mat: 'paint', color: L === 1 ? '#5a6a5a' : '#d8d8d0', y: 0.95, z: 0.12 })
      b.box(0.12, 0.05, 0.01, { mat: 'glowGreen', color: '#ffffff', y: 1.1, z: 0.225, shadow: false })
      decal(b, plateMat('400V', '#e8c020', '#1a1a1a', 'bolt'), 0.2, 0.1, { y: 0.85, z: 0.225 })
    })
    if (L === 1) for (let i = 0; i < 3; i++) carBattery(b, { x: -0.9 + i * 0.32, z: 1.0, ry: 0.1 })
    else beacon(b, I, { x: 0, y: H + 0.85, z: -0.3 })
    I.spots.push({ x: 0.75, z: 1.45, face: FACE_BACK, anim: 'type' })
  },

  // ============================================================ BATTERY BANK
  battery(b, L, I) {
    const rnd = seeded(4400 + L)
    if (L === 1) {
      // car batteries on shelves under a tarp roof, an inverter on a post
      b.box(3.8, 0.02, 2.8, { mat: 'dirt', color: '#6a5a48', y: 0.0 })
      tinLeanTo(b, I, { x: 0, z: -0.4, w: 3.6, d: 1.8, hf: 2.3, hb: 2.0, seed: 44 })
      for (const x of [-1.0, 0.4]) {
        b.at({ x, z: -0.7 }, () => {
          for (const s of [-1, 1]) b.box(0.05, 1.5, 0.5, { mat: 'wood', color: '#9a8466', x: s * 0.6, y: 0.75 })
          for (const y of [0.1, 0.6, 1.1]) {
            b.box(1.25, 0.04, 0.5, { mat: 'planks', color: '#a8906a', y })
            for (let i = 0; i < 4; i++) carBattery(b, { x: -0.45 + i * 0.3, y: y + 0.02, ry: (rnd() - 0.5) * 0.2 })
          }
          for (const y of [0.4, 0.9]) cable(b, [[-0.5, y, 0.1], [0, y - 0.06, 0.14], [0.5, y, 0.1]], { r: 0.008, color: '#c8302a', sag: 0.02 })
        })
      }
      b.at({ x: 1.45, z: 0.6 }, () => {
        b.box(0.1, 1.5, 0.1, { mat: 'wood', color: '#8a7050', y: 0.75 })
        b.box(0.45, 0.35, 0.18, { mat: 'paint', color: '#2a4a7a', y: 1.15, z: 0.1 })
        for (let i = 0; i < 3; i++) b.box(0.05, 0.05, 0.01, { mat: i ? 'glowGreen' : 'glowAmber', color: '#ffffff', x: -0.12 + i * 0.12, y: 1.25, z: 0.195, shadow: false })
      })
      cable(b, [[0.9, 0.8, -0.5], [1.2, 0.2, 0.3], [1.45, 1.0, 0.65]], { r: 0.015, sag: 0.03 })
      I.lights.push({ x: 1.45, y: 1.2, z: 0.8, color: '#80ff9a', intensity: 0.6, dist: 3, when: 'night' })
      I.spots.push({ x: 1.0, z: 0.9, face: FACE_BACK, anim: 'search' })
      return
    }
    if (L === 2) {
      // a corrugated shed, open at the front, with two racks of cells
      b.box(3.8, 0.08, 2.8, { mat: 'concrete', color: '#8a867e', y: 0.04 })
      tinLeanTo(b, I, { x: 0, z: -0.3, w: 3.7, d: 2.2, hf: 2.6, hb: 2.3, seed: 45 })
      cellRack(b, { x: -0.9, y: 0.08, z: -0.8, n: 5 })
      cellRack(b, { x: 0.9, y: 0.08, z: -0.8, n: 5 })
      b.at({ x: 1.6, z: 0.6 }, () => {
        b.box(0.5, 1.4, 0.4, { mat: 'paint', color: '#d8d8d0', y: 0.78 })
        b.box(0.3, 0.16, 0.01, { mat: 'glowBlue', color: '#103050', y: 1.2, z: 0.205 })
        for (let i = 0; i < 4; i++) b.box(0.04, 0.04, 0.01, { mat: i % 2 ? 'glowGreen' : 'glowAmber', color: '#ffffff', x: -0.12 + i * 0.08, y: 0.95, z: 0.205, shadow: false })
        decal(b, plateMat('CHARGE CTRL', '#d8d8d0', '#1a1a1a', 'bolt'), 0.3, 0.1, { y: 0.6, z: 0.205 })
      })
      cable(b, [[0, 1.6, -0.6], [0.9, 1.0, 0.2], [1.6, 1.1, 0.4]], { r: 0.02, sag: 0.06 })
      I.lights.push({ x: 0, y: 2.0, z: -0.2, color: '#a8d8ff', intensity: 1.5, dist: 5, when: 'night' })
      I.spots.push({ x: 1.6, z: 1.1, face: FACE_BACK, anim: 'type' })
      return
    }
    // L3: a row of white battery cabinets, a cooling unit and a transformer
    slab(b, I, { h: 0.12, seed: 47 })
    for (let i = 0; i < 4; i++) {
      b.at({ x: -1.35 + i * 0.75, z: -0.85 }, () => {
        b.box(0.7, 1.9, 0.75, { mat: 'paint', color: '#e8e8e2', y: 1.07, r: 0.02 })
        b.box(0.02, 1.8, 0.01, { mat: 'paint', color: '#b8b8b0', y: 1.07, z: 0.38 })
        for (let k = 0; k < 6; k++) b.box(0.08, 0.025, 0.01, { mat: k < 4 ? 'glowGreen' : 'glowAmber', color: '#ffffff', x: -0.2, y: 1.6 - k * 0.07, z: 0.381, shadow: false })
        b.box(0.18, 0.1, 0.01, { mat: 'glowBlue', color: '#103050', x: 0.15, y: 1.65, z: 0.381, shadow: false })
        for (let k = 0; k < 5; k++) b.box(0.5, 0.012, 0.01, { mat: 'paint', color: '#c8c8c0', y: 0.4 + k * 0.05, z: 0.381 })
      })
    }
    decal(b, plateMat('DANGER 800V DC', '#e8c020', '#1a1a1a', 'bolt'), 0.5, 0.18, { x: -0.6, y: 2.2, z: -0.47 })
    b.at({ x: 1.6, z: -0.8 }, () => {
      b.box(0.8, 0.9, 0.8, { mat: 'paint', color: '#9aa0a4', y: 0.57 })
      b.cyl(0.32, 0.32, 0.04, { mat: 'metal', color: '#3a3a3a', y: 1.04, seg: 18 })
      b.pivot('fan', { y: 1.06 }, (p) => {
        for (let i = 0; i < 5; i++) p.box(0.28, 0.01, 0.08, { mat: 'paint', color: '#2a2a2a', x: Math.cos((i / 5) * TAU) * 0.14, z: Math.sin((i / 5) * TAU) * 0.14, ry: -(i / 5) * TAU, rx: 0.3 })
      })
      I.anims.push({ name: 'fan', kind: 'spin', axis: 'y', speed: 9, when: 'always' })
    })
    b.at({ x: 1.45, z: 0.8 }, () => {
      b.box(0.9, 1.0, 0.6, { mat: 'paint', color: '#4a6a4a', y: 0.62 })
      for (let i = 0; i < 6; i++) b.box(0.03, 0.8, 0.62, { mat: 'paint', color: '#3e5e3e', x: -0.38 + i * 0.15, y: 0.6 })
      for (let i = 0; i < 3; i++) b.cyl(0.05, 0.06, 0.25, { mat: 'plastic', color: '#c87a48', x: -0.25 + i * 0.25, y: 1.25, seg: 10 })
    })
    for (let i = 0; i < 4; i++) cable(b, [[-1.35 + i * 0.75, 2.02, -0.85], [-0.6 + i * 0.4, 2.2, -0.4], [1.45, 1.4, 0.5]], { r: 0.018, sag: 0.08 })
    controlPanel(b, { x: -0.6, z: 0.9, ry: 0.1 })
    b.plane(3.6, 0.07, { material: stripeMat(), y: 0.122, z: -0.25, rx: -Math.PI / 2, shadow: false })
    I.lights.push({ x: -0.3, y: 2.4, z: 0.0, color: '#a8d8ff', intensity: 2, dist: 6, when: 'night' })
    I.spots.push({ x: -0.6, z: 1.35, face: FACE_BACK, anim: 'type' })
  },
}
