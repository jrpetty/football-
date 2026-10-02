// Station models, part four: power off the grid. A pair of salvaged solar
// panels (on a sun-tracking mast at level 2), a pedal generator with a rider
// on each bike, a biogas digester whose gas holder rises as it is fed, and a
// solar lamp post that lights itself at night. Same local frame as the other
// station models: footprint centred on the origin, the open side facing +z.
import * as THREE from 'three'
import { seeded } from './kit.js'
import { shadeHex, crate, carBattery, jerrycan, gauge, valveWheel, pipe, cinderBlocks, solarMat, bucket } from './parts.js'
import { plateMat, decal, cable } from './detail.js'

const TAU = Math.PI * 2
const FACE_BACK = Math.PI

// A panel: steel frame, cells facing up (tilt it with b.at rx).
function panelFace(b, w, h) {
  b.box(w + 0.05, 0.045, h + 0.05, { mat: 'steel', color: '#b8bcc0' })
  b.plane(w, h, { material: solarMat(), y: 0.024, rx: -Math.PI / 2 })
  // a junction box under each
  b.box(0.14, 0.04, 0.1, { mat: 'plastic', color: '#1e1e20', y: -0.04, z: -h * 0.3 })
}
// A charge controller on a post: a little box with a green light.
function controller(b, x, z, h = 1.05) {
  b.box(0.07, h, 0.07, { mat: 'wood', color: '#8a7050', x, y: h / 2, z })
  b.box(0.22, 0.18, 0.07, { mat: 'paint', color: '#2a4a7a', x, y: h - 0.12, z: z + 0.07 })
  b.box(0.06, 0.03, 0.01, { mat: 'glowGreen', color: '#ffffff', x: x - 0.05, y: h - 0.08, z: z + 0.106 })
  b.box(0.1, 0.05, 0.005, { mat: 'plain', color: '#101418', x: x + 0.04, y: h - 0.14, z: z + 0.106 })
}

// ---------------------------------------------------------------- solar panel
function panelModel(b, L, I) {
  const rnd = seeded(6100 + L)
  if (L === 1) {
    // two salvaged rooftop panels on a timber A-frame, facing the front
    b.box(1.9, 0.03, 1.9, { mat: 'gravel', color: '#a89a86', y: 0.015 })
    for (const sx of [-1, 1]) {
      const x = sx * 0.85
      b.beam([x, 0, -0.75], [x, 1.25, -0.6], 0.08, 0.08, { mat: 'wood', color: '#a08868' })
      b.beam([x, 0, 0.55], [x, 0.55, 0.45], 0.08, 0.08, { mat: 'wood', color: '#a08868' })
      b.beam([x, 0.12, 0.5], [x, 1.0, -0.62], 0.05, 0.05, { mat: 'wood', color: '#9a8262' })
      cinderBlocks(b, { x: x + 0.4, z: -0.75, n: 1 })
    }
    b.box(1.8, 0.06, 0.06, { mat: 'wood', color: '#9a8262', y: 1.2, z: -0.6 })
    b.box(1.8, 0.06, 0.06, { mat: 'wood', color: '#9a8262', y: 0.52, z: 0.45 })
    b.at({ y: 0.88, z: -0.08, rx: 0.78 }, () => {
      for (const sx of [-1, 1]) b.at({ x: sx * 0.42 }, () => panelFace(b, 0.8, 1.32))
      // one panel is older: a strip of tape over a crack
      b.box(0.04, 0.003, 0.5, { mat: 'plain', color: '#c8b070', x: -0.5, y: 0.03, z: 0.1, ry: 0.4 })
    })
    // the battery in a milk crate and the controller
    b.box(0.42, 0.28, 0.32, { mat: 'plastic', color: '#3a6a9a', x: 0.55, y: 0.14, z: 0.68 })
    carBattery(b, { x: 0.55, y: 0.06, z: 0.68 })
    controller(b, -0.6, 0.72)
    cable(b, [[-0.4, 0.75, 0.0], [-0.55, 0.45, 0.5], [-0.6, 0.9, 0.78]], { r: 0.01, sag: 0.03 })
    cable(b, [[-0.55, 0.92, 0.8], [0.0, 0.05, 0.85], [0.5, 0.28, 0.7]], { r: 0.01, sag: 0.02, color: '#c8302a' })
    I.lights.push({ x: -0.6, y: 1.0, z: 0.85, color: '#60ff80', intensity: 0.25, dist: 1.2, when: 'night' })
    return
  }
  // L2: a steel mast with a tracking head that turns with the sun
  b.cyl(0.62, 0.66, 0.16, { mat: 'concrete', color: '#a8a49c', y: 0.08, seg: 18 })
  b.cyl(0.07, 0.09, 1.25, { mat: 'steel', color: '#8a9298', y: 0.8, seg: 12 })
  for (let k = 0; k < 4; k++) {
    const a = (k / 4) * TAU + 0.4
    b.beam([Math.cos(a) * 0.5, 0.16, Math.sin(a) * 0.5], [0, 0.75, 0], 0.035, 0.035, { mat: 'steel', color: '#7a8288' })
  }
  b.pivot('track', { y: 1.42 }, (p) => {
    p.cyl(0.13, 0.13, 0.16, { mat: 'paint', color: '#3a4a5a', seg: 12 })
    p.box(0.22, 0.14, 0.14, { mat: 'paint', color: '#2a3a4a', x: 0.16, y: 0.02 })
    p.box(0.08, 0.04, 0.005, { mat: 'glowAmber', color: '#ffffff', x: 0.2, y: 0.05, z: 0.071 })
    p.box(1.9, 0.06, 0.08, { mat: 'steel', color: '#9aa0a6', y: 0.12 })
    p.at({ y: 0.2, rx: 0.62 }, () => {
      for (const sx of [-1, 1]) p.at({ x: sx * 0.46 }, () => panelFace(p, 0.86, 1.3))
      for (const sx of [-1, 1]) p.box(0.04, 0.04, 1.36, { mat: 'steel', color: '#8a9298', x: sx * 0.92, y: -0.05 })
    })
  })
  I.anims.push({ name: 'track', kind: 'sun', axis: 'y', amp: 1.05, when: 'always' })
  // battery box and an inverter on the pad
  b.box(0.5, 0.36, 0.34, { mat: 'paint', color: '#c8ccc8', x: 0.55, y: 0.34, z: 0.55, r: 0.02 })
  b.box(0.12, 0.05, 0.005, { mat: 'glowGreen', color: '#ffffff', x: 0.55, y: 0.44, z: 0.722 })
  decal(b, plateMat('12V', '#c8ccc8', '#2a2a2a'), 0.18, 0.08, { x: 0.55, y: 0.3, z: 0.722 })
  gauge(b, { x: 0.4, y: 0.42, z: 0.725 })
  cable(b, [[0, 1.3, 0.05], [0.2, 0.6, 0.35], [0.4, 0.45, 0.55]], { r: 0.012, sag: 0.04 })
  void rnd
}

// ---------------------------------------------------------------- pedal generator
// One exercise bike on a stand: frame, saddle, bars, a spinning crank and
// flywheel, a chain to an alternator. The rider sits facing +x.
function bike(b, I, x0, z0, n) {
  const frame = n ? '#2a5a8a' : '#a83a2a'
  // the stand: two cross feet and a spine
  for (const dx of [-0.42, 0.42]) b.box(0.08, 0.06, 0.62, { mat: 'steel', color: '#4a4e52', x: x0 + dx, y: 0.03, z: z0 })
  b.box(0.92, 0.06, 0.07, { mat: 'paint', color: frame, x: x0, y: 0.12, z: z0 })
  // seat post and saddle (behind), head tube and bars (in front)
  b.beam([x0 - 0.3, 0.12, z0], [x0 - 0.22, 0.86, z0], 0.06, 0.06, { mat: 'paint', color: frame, round: true })
  b.box(0.3, 0.07, 0.17, { mat: 'leather', color: '#1e1c1a', x: x0 - 0.24, y: 0.9, z: z0, r: 0.03 })
  b.beam([x0 + 0.32, 0.12, z0], [x0 + 0.28, 1.0, z0], 0.06, 0.06, { mat: 'paint', color: frame, round: true })
  b.beam([x0 - 0.22, 0.7, z0], [x0 + 0.3, 0.85, z0], 0.05, 0.05, { mat: 'paint', color: frame, round: true })
  b.box(0.06, 0.04, 0.56, { mat: 'steel', color: '#2a2a2a', x: x0 + 0.27, y: 1.02, z: z0 })
  for (const s of [-1, 1]) b.cyl(0.02, 0.02, 0.12, { mat: 'rubber', color: '#1a1a1a', x: x0 + 0.27, y: 1.02, z: z0 + s * 0.3, rx: Math.PI / 2, seg: 8 })
  // a towel over the bars and a bottle in its cage
  b.box(0.04, 0.22, 0.2, { mat: 'cloth', color: n ? '#c8a040' : '#e8e4dc', x: x0 + 0.31, y: 0.92, z: z0 + 0.18, rz: 0.1 })
  b.cyl(0.035, 0.035, 0.2, { mat: 'plastic', color: '#3a8ac8', x: x0 + 0.05, y: 0.62, z: z0 + 0.06, rz: -0.5, seg: 10 })
  // the crank, with its pedals: spins while someone rides
  b.pivot(`crank${n}`, { x: x0 - 0.02, y: 0.36, z: z0 }, (p) => {
    p.cyl(0.13, 0.13, 0.02, { mat: 'steel', color: '#b8bcc0', z: 0.06, rx: Math.PI / 2, seg: 18 })
    for (const s of [-1, 1]) {
      p.box(0.04, 0.2, 0.02, { mat: 'steel', color: '#8a8e92', y: s * 0.09, z: s * 0.1 })
      p.box(0.1, 0.03, 0.1, { mat: 'rubber', color: '#1a1a1a', y: s * 0.18, z: s * 0.16 })
    }
  })
  I.anims.push({ name: `crank${n}`, kind: 'spin', axis: 'z', speed: -5.2, when: 'active' })
  // a heavy flywheel at the front, behind a guard
  b.pivot(`fly${n}`, { x: x0 + 0.42, y: 0.34, z: z0 }, (p) => {
    p.cyl(0.26, 0.26, 0.06, { mat: 'metal', color: '#3a3e42', rx: Math.PI / 2, seg: 24 })
    for (let k = 0; k < 4; k++) p.box(0.03, 0.44, 0.07, { mat: 'steel', color: '#7a7e82', rz: (k * Math.PI) / 4 })
  })
  I.anims.push({ name: `fly${n}`, kind: 'spin', axis: 'z', speed: -11, when: 'active' })
  b.torus(0.29, 0.02, { mat: 'paint', color: frame, x: x0 + 0.42, y: 0.34, z: z0 + 0.06, arc: Math.PI })
  // the chain and the alternator it drives, behind the saddle
  b.beam([x0 - 0.02, 0.44, z0 + 0.07], [x0 - 0.5, 0.26, z0 + 0.07], 0.015, 0.015, { mat: 'steel', color: '#3a3a3a' })
  b.beam([x0 - 0.02, 0.28, z0 + 0.07], [x0 - 0.5, 0.2, z0 + 0.07], 0.015, 0.015, { mat: 'steel', color: '#3a3a3a' })
  b.cyl(0.1, 0.1, 0.16, { mat: 'metal', color: '#9aa0a6', x: x0 - 0.56, y: 0.24, z: z0, rx: Math.PI / 2, seg: 14 })
  b.pivot(`pul${n}`, { x: x0 - 0.56, y: 0.24, z: z0 + 0.09 }, (p) => {
    p.cyl(0.05, 0.05, 0.02, { mat: 'steel', color: '#c8ccd0', rx: Math.PI / 2, seg: 10 })
    p.box(0.012, 0.09, 0.025, { mat: 'plain', color: '#2a2a2a' })
  })
  I.anims.push({ name: `pul${n}`, kind: 'spin', axis: 'z', speed: -22, when: 'active' })
  // the rider's seat: the anim sits them on the saddle and pedals
  I.spots.push({ x: x0 - 0.24, z: z0, face: Math.PI / 2, anim: 'pedal', sit: 0.88 })
}
function pedalModel(b, L, I) {
  const rnd = seeded(6200 + L)
  // a plank deck on blocks
  for (let i = 0; i < 6; i++) b.box(2.8, 0.05, 0.27, { mat: 'wood', color: shadeHex('#a89070', (rnd() - 0.5) * 0.12), y: 0.09, z: -0.7 + i * 0.28 })
  for (const x of [-1.2, 0, 1.2]) for (const z of [-0.6, 0.6]) b.box(0.3, 0.07, 0.2, { mat: 'concrete', color: '#9a968e', x, y: 0.035, z })
  b.at({ y: 0.115 }, () => {
    if (L === 1) {
      bike(b, I, -0.5, 0.15, 0)
      // a bench of car batteries and a voltmeter
      b.box(0.9, 0.06, 0.5, { mat: 'wood', color: '#8a7454', x: 0.9, y: 0.45, z: -0.25 })
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.05, 0.45, 0.05, { mat: 'wood', color: '#7a6444', x: 0.9 + sx * 0.4, y: 0.22, z: -0.25 + sz * 0.2 })
      for (let i = 0; i < 3; i++) carBattery(b, { x: 0.62 + i * 0.28, y: 0.48, z: -0.28, ry: Math.PI / 2 })
      cable(b, [[-1.06, 0.3, 0.15], [-0.3, 0.02, -0.4], [0.62, 0.72, -0.3]], { r: 0.012, sag: 0.03, color: '#c8302a' })
    } else {
      bike(b, I, -0.7, 0.2, 0)
      bike(b, I, 0.75, 0.2, 1)
      // the battery rack behind them
      b.box(2.4, 0.05, 0.36, { mat: 'steel', color: '#7a7e82', y: 0.55, z: -0.62 })
      for (const x of [-1.15, 0, 1.15]) b.box(0.05, 0.55, 0.3, { mat: 'steel', color: '#6a6e72', x, y: 0.27, z: -0.62 })
      for (let i = 0; i < 6; i++) carBattery(b, { x: -0.95 + i * 0.38, y: 0.58, z: -0.62, ry: Math.PI / 2 })
      cable(b, [[-1.26, 0.3, 0.2], [-0.6, 0.02, -0.3], [-0.9, 0.8, -0.6]], { r: 0.012, sag: 0.03, color: '#c8302a' })
      cable(b, [[0.19, 0.3, 0.2], [0.4, 0.02, -0.3], [0.6, 0.8, -0.6]], { r: 0.012, sag: 0.03, color: '#c8302a' })
    }
    // a voltmeter on a post and a bulb that lights while someone rides
    b.box(0.06, 1.3, 0.06, { mat: 'wood', color: '#7a6444', x: 1.3, y: 0.65, z: 0.55 })
    b.box(0.2, 0.2, 0.06, { mat: 'paint', color: '#e8e4d8', x: 1.3, y: 1.18, z: 0.6 })
    gauge(b, { x: 1.3, y: 1.18, z: 0.64 })
    b.box(0.2, 0.06, 0.06, { mat: 'wood', color: '#7a6444', x: 1.22, y: 1.36, z: 0.55 })
    b.cyl(0.04, 0.05, 0.06, { mat: 'glowAmber', color: '#ffffff', x: 1.14, y: 1.29, z: 0.55, seg: 10 })
    decal(b, plateMat(L === 1 ? 'PEDAL POWER' : 'PEDAL POWER x2', '#e8c840', '#2a2a2a'), 0.5, 0.14, { x: -0.15, y: 0.22, z: 0.87 })
    // a crate to wait on, a water jug
    crate(b, { x: 1.05, z: 0.55, w: 0.4, h: 0.32, d: 0.32, ry: 0.3, color: '#b8a080' })
    jerrycan(b, { x: -1.25, z: -0.55, ry: 0.4, color: '#3a6a9a' })
  })
  I.lights.push({ x: 1.14, y: 1.3, z: 0.55, color: '#ffc870', intensity: 0.9, dist: 3, when: 'active' })
}

// ---------------------------------------------------------------- biogas digester
// A floating-drum digester: a ring tank sunk in the ground with a steel drum
// floating in it that rises as the gas builds (pivot 'holder'), an inlet pit
// for dung and scraps, a slurry pit behind, and a gas engine with its
// flywheel, exhaust and a lamp.
function digesterModel(b, L, I) {
  const rnd = seeded(6300 + L)
  b.box(4.9, 0.03, 3.9, { mat: 'dirt', color: '#6a5a46', y: 0.015, shadow: false })
  // the ring tank, mostly buried
  const tx = -0.75
  const tz = -0.35
  b.cyl(1.32, 1.4, 0.55, { mat: 'concrete', color: '#9a968c', x: tx, y: 0.27, z: tz, seg: 28 })
  b.cyl(1.2, 1.2, 0.02, { mat: 'plain', color: '#2a2a1e', x: tx, y: 0.54, z: tz, seg: 28, shadow: false })
  // guide frame: four posts and a crown the drum slides up
  for (let k = 0; k < 4; k++) {
    const a = (k / 4) * TAU + Math.PI / 4
    b.box(0.08, 1.85, 0.08, { mat: 'steel', color: '#6a7076', x: tx + Math.cos(a) * 1.28, y: 0.92, z: tz + Math.sin(a) * 1.28 })
  }
  for (let k = 0; k < 2; k++) b.box(2.7, 0.06, 0.06, { mat: 'steel', color: '#6a7076', x: tx, y: 1.84, z: tz, ry: Math.PI / 4 + (k * Math.PI) / 2 })
  // the drum: rises as the gas builds
  b.pivot('holder', { x: tx, y: 0.32, z: tz }, (p) => {
    p.cyl(1.12, 1.12, 0.9, { mat: L === 1 ? 'rust' : 'paint', color: L === 1 ? '#ffffff' : '#3a5a3a', y: 0.45, seg: 28 })
    p.cyl(1.14, 1.14, 0.05, { mat: 'steel', color: '#5a6066', y: 0.9, seg: 28 })
    p.lathe([[1.12, 0.92], [0.5, 1.08], [0.12, 1.12], [0.001, 1.12]], { mat: L === 1 ? 'metal' : 'paint', color: L === 1 ? '#7a7466' : '#3a5a3a', seg: 28 })
    p.cyl(0.07, 0.07, 0.26, { mat: 'steel', color: '#8a9298', y: 1.24, seg: 10 })
    valveWheel(p, { y: 1.38, r: 0.09, rx: Math.PI / 2 })
    for (let k = 0; k < 4; k++) {
      const a = (k / 4) * TAU + Math.PI / 4
      p.box(0.06, 0.12, 0.14, { mat: 'steel', color: '#4a4e52', x: Math.cos(a) * 1.18, y: 0.86, z: Math.sin(a) * 1.18, ry: -a })
    }
    p.box(0.7, 0.24, 0.01, { mat: 'paint', color: '#e8c020', y: 0.62, z: 1.125 })
  })
  I.anims.push({ name: 'holder', kind: 'fillY', amp: 0.55, when: 'always' })
  decal(b, plateMat('BIOGAS - NO FLAMES', '#e8c020', '#1a1a1a', 'flame'), 0.9, 0.22, { x: tx, y: 0.36, z: tz + 1.41 })
  // the inlet pit: a mixing box with a paddle, a bucket of dung beside it
  b.box(0.9, 0.42, 0.7, { mat: 'concrete', color: '#a09c92', x: 1.0, y: 0.21, z: -1.25 })
  b.box(0.78, 0.02, 0.58, { mat: 'dirt', color: '#4a3a24', x: 1.0, y: 0.38, z: -1.25, shadow: false })
  b.beam([1.0, 0.4, -1.25], [1.25, 1.15, -1.0], 0.04, 0.04, { mat: 'wood', color: '#9a8262', round: true })
  b.box(0.22, 0.05, 0.14, { mat: 'wood', color: '#9a8262', x: 1.0, y: 0.38, z: -1.25 })
  pipe(b, [[0.55, 0.15, -1.25], [-0.2, 0.15, -1.0]], 0.07, { color: '#5a5a52' })
  bucket(b, { x: 1.6, z: -1.0, color: '#6a5a3a' })
  b.cyl(0.13, 0.13, 0.04, { mat: 'dirt', color: '#5a4228', x: 1.6, y: 0.27, z: -1.0, seg: 12, shadow: false })
  // the slurry pit behind: what comes out goes on the farm
  b.box(1.2, 0.05, 0.7, { mat: 'dirt', color: '#3a2c1c', x: -0.75, y: 0.03, z: -1.55, shadow: false })
  b.box(1.3, 0.16, 0.08, { mat: 'concrete', color: '#9a968c', x: -0.75, y: 0.08, z: -1.17 })
  b.beam([-0.1, 0, -1.7], [0.35, 1.1, -1.5], 0.035, 0.035, { mat: 'wood', color: '#8a7050', round: true })
  b.box(0.22, 0.03, 0.26, { mat: 'metal', color: '#5a5a5a', x: -0.1, y: 0.06, z: -1.7, rx: -0.4 })
  // gas line to the engine
  pipe(b, [[tx, 1.0, tz], [tx, 2.0, tz], [0.9, 2.0, tz], [0.9, 2.0, 0.8], [1.35, 0.9, 0.8]], 0.03, { color: '#c8a020' })
  // the gas engine and its alternator on a skid
  b.at({ x: 1.6, z: 0.95 }, () => {
    b.box(1.3, 0.1, 0.8, { mat: 'steel', color: '#4a4e52', y: 0.05 })
    b.box(0.6, 0.52, 0.5, { mat: 'paint', color: L === 1 ? '#5a7a3a' : '#c8a020', x: -0.2, y: 0.36, r: 0.03 })
    b.box(0.5, 0.12, 0.36, { mat: 'metal', color: '#3a3a3a', x: -0.2, y: 0.68 })
    b.cyl(0.18, 0.18, 0.4, { mat: 'paint', color: '#3a4a5a', x: 0.35, y: 0.32, rz: Math.PI / 2, seg: 16 })
    b.pivot('engfly', { x: 0.1, y: 0.32, z: 0 }, (p) => {
      p.cyl(0.2, 0.2, 0.06, { mat: 'metal', color: '#2a2a2a', rz: Math.PI / 2, seg: 18 })
      p.box(0.07, 0.34, 0.04, { mat: 'steel', color: '#8a8e92' })
    })
    // exhaust stack with a rain cap
    b.cyl(0.04, 0.04, 0.9, { mat: 'metal', color: '#5a4a3a', x: -0.42, y: 0.95, z: -0.15, seg: 8 })
    b.cyl(0.09, 0.05, 0.05, { mat: 'metal', color: '#4a3a2a', x: -0.42, y: 1.42, z: -0.15, seg: 8 })
    gauge(b, { x: -0.2, y: 0.5, z: 0.255 })
    b.box(0.08, 0.05, 0.01, { mat: 'glowGreen', color: '#ffffff', x: 0.05, y: 0.5, z: 0.255 })
  })
  I.anims.push({ name: 'engfly', kind: 'spin', axis: 'x', speed: 14, when: 'active' })
  I.emitters.push({ kind: 'exhaust', x: 1.18, y: 1.5, z: 0.8, when: 'active' })
  I.lights.push({ x: 1.4, y: 1.2, z: 1.3, color: '#ffc070', intensity: 0.8, dist: 3.5, when: 'active' })
  if (L === 2) {
    // a second, bigger drum's worth: a gas bag on the side and a proper fence
    b.sphere(0.55, { mat: 'rubber', color: '#2a2e2a', x: -2.0, y: 0.35, z: 1.2, sy: 0.6, ws: 16, hs: 10 })
    for (let i = 0; i < 9; i++) b.box(0.05, 0.9, 0.05, { mat: 'steel', color: '#6a7076', x: -2.35 + i * 0.32, y: 0.45, z: 1.85 })
    b.box(2.6, 0.04, 0.04, { mat: 'steel', color: '#6a7076', x: -1.07, y: 0.85, z: 1.85 })
  } else {
    // a barrow of dung on its way in
    b.at({ x: 0.2, z: 1.3, ry: 0.5 }, () => {
      b.box(0.55, 0.22, 0.4, { mat: 'metal', color: '#5a7a8a', y: 0.36 })
      b.box(0.5, 0.06, 0.35, { mat: 'dirt', color: '#4a3622', y: 0.46, shadow: false })
      b.cyl(0.13, 0.13, 0.06, { mat: 'rubber', color: '#1a1a1a', x: 0.36, y: 0.13, rx: Math.PI / 2, seg: 12 })
      for (const s of [-1, 1]) b.beam([-0.2, 0.32, s * 0.18], [-0.65, 0.42, s * 0.22], 0.03, 0.03, { mat: 'wood', color: '#8a7050', round: true })
    })
  }
  void rnd
}

// ---------------------------------------------------------------- solar lamp
function sollampModel(b, L, I) {
  b.cyl(0.2, 0.24, 0.22, { mat: 'concrete', color: '#a8a49c', y: 0.11, seg: 12 })
  b.cyl(0.04, 0.055, 3.0, { mat: 'paint', color: '#3a4046', y: 1.6, seg: 10 })
  // the battery box halfway up, with a charge light
  b.box(0.18, 0.3, 0.12, { mat: 'paint', color: '#3a4046', y: 1.4, z: 0.08 })
  b.box(0.05, 0.03, 0.005, { mat: 'glowGreen', color: '#ffffff', y: 1.48, z: 0.142 })
  // its own panel on top, facing the front
  b.at({ y: 3.12, z: -0.05, rx: 0.7 }, () => {
    b.box(0.62, 0.035, 0.46, { mat: 'steel', color: '#9aa0a6' })
    b.plane(0.58, 0.42, { material: solarMat(), y: 0.02, rx: -Math.PI / 2 })
  })
  // the arm and the lamp head
  b.beam([0, 2.85, 0], [0.55, 2.95, 0.12], 0.04, 0.04, { mat: 'paint', color: '#3a4046', round: true })
  b.box(0.34, 0.08, 0.2, { mat: 'paint', color: '#2a2e32', x: 0.6, y: 2.95, z: 0.14, r: 0.02 })
  b.box(0.28, 0.01, 0.15, { mat: 'plain', color: '#3a3a32', x: 0.6, y: 2.905, z: 0.14 })
  // lit: the lens, a glowing rim you can see from above, and a soft cone of
  // light down to a pool on the ground
  b.pivot('glow', { x: 0.6, y: 2.9, z: 0.14 }, (p) => {
    p.box(0.27, 0.012, 0.14, { mat: 'glowWhite', color: '#ffffff' })
    for (const sz of [-1, 1]) p.box(0.3, 0.018, 0.012, { mat: 'glowWhite', color: '#ffffff', y: 0.012, z: sz * 0.1 })
    for (const sx of [-1, 1]) p.box(0.012, 0.018, 0.2, { mat: 'glowWhite', color: '#ffffff', x: sx * 0.165, y: 0.012 })
    p.cyl(0.13, 1.7, 2.82, { material: coneMat(), y: -1.42, seg: 20, shadow: false })
  })
  I.anims.push({ name: 'glow', kind: 'lit', when: 'always' })
  I.lights.push({ x: 0.6, y: 2.7, z: 0.14, color: '#f4f0e6', intensity: 6.5, dist: 10.5, when: 'lit' })
}

// The lamp's beam: faint, additive, never in the shadow pass.
let _cone = null
function coneMat() {
  if (!_cone) {
    _cone = new THREE.MeshBasicMaterial({ color: '#fff4dc', transparent: true, opacity: 0.06, blending: THREE.AdditiveBlending, depthWrite: false, side: THREE.DoubleSide })
    _cone.userData.noShadow = true
  }
  return _cone
}

export const STATIONS4 = {
  panel: panelModel,
  pedal: pedalModel,
  digester: digesterModel,
  sollamp: sollampModel,
}
