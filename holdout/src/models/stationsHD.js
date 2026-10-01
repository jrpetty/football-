// Camp stations, high-detail edition. Each station is designed around what it
// does: level 1 is improvised from scavenged junk, level 2 is a built
// workshop, level 3 is a powered, partly automated facility. Local frame:
// footprint centred on the origin, the working side faces +z (the camera's
// usual side), FACE_BACK spots face the bench at the back.
// Every model reports worker spots, animated pivots, lights, flames and
// particle emitters through I (see stations.js for the field list).
import { seeded } from './kit.js'
import {
  COL, shadeHex, crate, barrel, jerrycan, sack, pallet, plankStack, stump, scrapPile, tarp, corrRoof, lantern, bulb,
  stringLights, table, stool, shelf, vise, anvil, sawhorse, toolbox, bucket, gasBottle, sign, rock, cinderBlocks, blob, logPile, firewood, tireStack,
} from './parts.js'
import { slab, boardWall, frame, roof } from './stationkit.js'
import {
  hammer, wrench, handSaw, heavyBench, pegboard, toolChest, partsBins, benchLamp, fluoroLight, elecPanel, cable, controlPanel,
  beacon, drillPress, benchGrinder, metalLathe, shopPress, conveyor, hopper, benchClutter, stripeMat, plateMat, posterMat, decal,
  drum, ammoCan, cClamp, drill, pegMat as pegMatX, targetMat, reloadingPress, progressivePress, tumbler, keg, sewingHead, treadleTable, fabricBolt,
  dressForm, hideFrame, oscilloscope, crtMonitor, componentDrawers, serverRack, glassSet, fumeHood, reactor, pump, campStove,
  longGun, pistol, gunRack, brassBin, cage, pipeRun, solderIron, diamondMat, camoNetMat, patchworkMat, canopy, umbrella, tinLeanTo,
  cropRowHD, chicken, heartMat,
} from './detail.js'
import { solarMat as solarMatX, carBattery as carBat, campChair as campChairX, lampPost as lampPostX, gableRoof as gableRoofX, bench as benchX } from './parts.js'
import { windowPane as windowFrame, door as doorX, deck as deckX } from './stationkit.js'
import { containerModel as containerModelX, forkliftModel as forkliftModelX } from './vehicles.js'
import { scarecrow, wheelbarrow, waterTank, tire as tireP, log as logP, ladder, ladder as ladderX } from './parts.js'
import { carModel, wheelHD } from './vehicles.js'
import { sandbags, hayBale, clothesline, mannequin, carBattery, radioSet, cableReel, solarPanel, ibcTote, valveWheel, gauge, sign as signBoard } from './parts.js'

const TAU = Math.PI * 2
const FACE_BACK = Math.PI
const pick = (r, a) => a[Math.floor(r() * a.length)]
const CAR_TONES = ['#6e3430', '#3a4e62', '#8a8478', '#3a3e42', '#5a6650', '#a88a48', '#4e3a4a', '#2a3440', '#7a5a3a']

// ---------------------------------------------------------------- shared bits
// Wood chips, sawdust and offcuts scattered on the ground.
function shavings(b, rnd, x0, x1, z0, z1, n = 14, color = '#c8a878') {
  for (let i = 0; i < n; i++) blob(b, 0.15 + rnd() * 0.5, 0.1 + rnd() * 0.35, { rnd, mat: 'plain', color: shadeHex(color, (rnd() - 0.5) * 0.15), x: x0 + rnd() * (x1 - x0), y: 0.004 + i * 0.0004, z: z0 + rnd() * (z1 - z0), rx: -Math.PI / 2, rz: rnd() * TAU, t: 0.004 })
  for (let i = 0; i < n / 2; i++) b.box(0.05 + rnd() * 0.25, 0.02, 0.04 + rnd() * 0.06, { mat: 'wood', color: shadeHex(color, -rnd() * 0.2), x: x0 + rnd() * (x1 - x0), y: 0.01, z: z0 + rnd() * (z1 - z0), ry: rnd() * TAU })
}
// A rough post made from a stripped log.
function pole(b, x, z, h, o = {}) {
  b.cyl(o.r ?? 0.06, (o.r ?? 0.06) * 1.15, h, { mat: 'wood', color: o.color ?? '#a89070', x, y: h / 2, z, seg: 8, rz: o.lean ?? 0 })
}
// Oil and grease stains on a floor.
function stains(b, rnd, x0, x1, z0, z1, n = 4, y = 0.004) {
  for (let i = 0; i < n; i++) blob(b, 0.2 + rnd() * 0.5, 0.15 + rnd() * 0.35, { rnd, mat: 'plain', color: '#4a4238', x: x0 + rnd() * (x1 - x0), y: y + i * 0.0005, z: z0 + rnd() * (z1 - z0), rx: -Math.PI / 2, rz: rnd() * TAU, t: 0.002 })
}
// Steel portal frame building: posts, eaves beams, roof purlins and a sloped
// corrugated roof (in a fading roof pivot). Back wall clad in sheet metal.
function steelShed(b, I, o) {
  const { w, d, h, hb } = o
  const m = { mat: 'paint', color: o.color ?? '#4a5056' }
  const xs = o.xs ?? [-w / 2, w / 2]
  const zf = d / 2
  const zb = -d / 2
  for (const x of xs) {
    b.box(0.1, h, 0.1, { ...m, x, y: h / 2, z: zf })
    b.box(0.1, hb, 0.1, { ...m, x, y: hb / 2, z: zb })
    b.beam([x, h, zf], [x, hb, zb], 0.12, 0.08, m)
    // knee braces
    b.beam([x, h - 0.5, zf], [x, h - 0.05, zf - 0.45], 0.05, 0.05, m)
    for (const p of [[x, 0.01, zf], [x, 0.01, zb]]) b.box(0.22, 0.02, 0.22, { mat: 'metal', color: '#5a5c5e', x: p[0], y: p[1], z: p[2] })
  }
  b.box(w + 0.1, 0.12, 0.08, { ...m, y: h - 0.06, z: zf })
  b.box(w + 0.1, 0.12, 0.08, { ...m, y: hb - 0.06, z: zb })
  if (o.backWall !== false) {
    const n = Math.round(w / 0.9)
    for (let i = 0; i < n; i++) b.box(w / n + 0.04, hb - 0.1, 0.03, { mat: 'corrugated', color: o.clad ?? pick(seeded(i + 5), ['#9aa0a4', '#8e9498', '#a4a8aa']), x: -w / 2 + (w / n) * (i + 0.5), y: (hb - 0.1) / 2 + 0.05, z: zb - 0.05 })
  }
  roof(b, I, (r) => {
    const drop = hb - h
    const len = Math.hypot(d + 0.5, drop)
    const slope = Math.atan2(h - hb, d)
    const n = Math.round((w + 0.5) / 0.85)
    for (let i = 0; i < n; i++) r.box((w + 0.5) / n + 0.06, 0.03, len, { mat: 'corrugated', color: pick(seeded(i * 3 + 1), ['#a8aeb2', '#9aa0a4', '#b0b4b6', '#9a968e']), x: -(w + 0.5) / 2 + ((w + 0.5) / n) * (i + 0.5), y: (h + hb) / 2 + 0.08 + (i % 2) * 0.01, rx: -slope })
    for (let k = 0; k < 4; k++) {
      const t = (k + 0.5) / 4
      r.box(w + 0.2, 0.06, 0.06, { ...m, y: hb + (h - hb) * t + 0.03, z: zb + d * t })
    }
  })
}

// Timber workshop shell: plank floor, board back wall (and optional left
// wall), posts and a corrugated roof that is high at the open front.
function timberShed(b, I, o) {
  const { w, d } = o
  const rnd = seeded(o.seed ?? 3)
  b.box(w, 0.1, d, { mat: 'planks', color: o.floor ?? '#d8c8a8', y: 0.05 })
  stains(b, rnd, -w / 2 + 0.3, w / 2 - 0.3, -d / 2 + 0.3, d / 2 - 0.3, 3, 0.102)
  boardWall(b, -w / 2, w / 2, -d / 2 + 0.03, o.h ?? 2.5, { y0: 0.1, seed: (o.seed ?? 3) + 1, color: o.wall ?? '#c8b494' })
  if (o.left !== false) b.at({ ry: Math.PI / 2 }, () => boardWall(b, -d / 2, d / 2 - (o.leftOpen ?? 1.0), -w / 2 + 0.03, o.h ?? 2.5, { y0: 0.1, seed: (o.seed ?? 3) + 2, color: o.wall ?? '#c8b494' }))
  frame(b, w - 0.1, d - 0.1, 2.6, { hFront: 2.85, braces: true, color: '#b8a080' })
  roof(b, I, (r) => corrRoof(r, { w: w + 0.4, d: d + 0.5, y: 2.98, drop: 0.4, gutter: true, seed: (o.seed ?? 3) + 4, ry: Math.PI }))
}
// A hanging bare bulb with its cord; registers a night light.
function hangBulb(b, I, x, y, z, o = {}) {
  bulb(b, { x, y, z, r: 0.05 })
  b.cyl(0.004, 0.004, o.cord ?? 0.4, { mat: 'rubber', color: '#1a1a1a', x, y: y + 0.07 + (o.cord ?? 0.4) / 2, z, seg: 4 })
  I.lights.push({ x, y: y - 0.05, z, color: o.color ?? '#ffc880', intensity: o.intensity ?? 3, dist: o.dist ?? 8, when: 'night' })
}

// Board for hanging pistols (same perforated hardboard as the pegboards).
function pegMat2() {
  return pegMatX()
}
// CNC mill: an enclosed machining centre with a window, spindle (pivot
// 'spindle', slides), a pendant screen, coolant tank and a chip bin.
function cncMill(b, I, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(1.3, 1.7, 0.95, { mat: 'paint', color: '#d8dcd8', y: 0.85, r: 0.03 })
    b.box(1.32, 0.3, 0.97, { mat: 'paint', color: '#3a4a5a', y: 0.15 })
    b.box(0.7, 0.6, 0.02, { mat: 'glass', color: '#b8d0d8', x: -0.15, y: 1.05, z: 0.48 })
    b.box(0.74, 0.66, 0.01, { mat: 'paint', color: '#3a4a5a', x: -0.15, y: 1.05, z: 0.475 })
    b.box(0.03, 0.4, 0.05, { mat: 'chrome', color: '#b8bcc0', x: 0.25, y: 1.05, z: 0.5 })
    b.pivot('spindle', { x: -0.15, y: 1.15, z: 0.1 }, (p) => {
      p.box(0.18, 0.3, 0.2, { mat: 'paint', color: '#3a4a5a' })
      p.cyl(0.03, 0.02, 0.15, { mat: 'chrome', color: '#c8ccd0', y: -0.22, seg: 10 })
    })
    I.anims.push({ name: 'spindle', kind: 'slide', axis: 'x', amp: 0.18, speed: 0.7, when: 'active' })
    // pendant
    b.beam([0.66, 1.6, 0.3], [0.85, 1.45, 0.55], 0.05, 0.05, { mat: 'paint', color: '#3a4a5a' })
    b.at({ x: 0.85, y: 1.3, z: 0.6, ry: -0.6 }, () => {
      b.box(0.36, 0.42, 0.1, { mat: 'paint', color: '#3a4a5a', r: 0.015 })
      b.box(0.26, 0.18, 0.01, { mat: 'glowBlue', color: '#103050', y: 0.08, z: 0.051 })
      for (let i = 0; i < 8; i++) b.box(0.03, 0.025, 0.012, { mat: 'plastic', color: '#d8d8d0', x: -0.11 + (i % 4) * 0.07, y: -0.09 - Math.floor(i / 4) * 0.05, z: 0.052 })
    })
    b.box(0.45, 0.35, 0.3, { mat: 'paint', color: '#2a5a8a', x: 0.45, y: 0.175, z: -0.62 })
    b.box(0.4, 0.3, 0.35, { mat: 'metal', color: '#6a6e72', x: -0.85, y: 0.15, z: 0.0 })
    for (let i = 0; i < 6; i++) b.torus(0.03, 0.004, { mat: 'chrome', color: '#d8c890', x: -0.85 + (Math.random() - 0.5) * 0.25, y: 0.31, z: (Math.random() - 0.5) * 0.25, rx: Math.random() * 3, rs: 3, ts2: 8 })
    for (const [i, g] of ['glowRed', 'glowAmber', 'glowGreen'].entries()) b.cyl(0.035, 0.035, 0.06, { mat: g, color: ['#5a1410', '#6a4410', '#104a1a'][i], x: 0.55, y: 1.78 + (2 - i) * 0.065, z: 0.35, seg: 12 })
    decal(b, plateMat('HAAS VF-1', '#3a4a5a', '#e8e4d8'), 0.3, 0.1, { x: -0.15, y: 1.5, z: 0.481 })
  })
}

// Merge a built model group (vehicles, etc.) into a builder.
function mergeModel(b, g, o = {}) {
  g.updateMatrixWorld(true)
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0, rx: o.rx || 0, rz: o.rz || 0 }, () => {
    g.traverse((m) => {
      if (!m.isMesh) return
      const geo = m.geometry.clone()
      geo.applyMatrix4(m.matrixWorld)
      b.add(geo, { material: m.material, keepColor: true, shadow: m.castShadow })
    })
  })
}
// A mounded, tilled bed of soil along x.
function soilRow(b, len, w, o = {}) {
  b.at({ x: o.x || 0, z: o.z || 0 }, () => {
    b.cyl(w / 2, w / 2, len, { mat: 'dirt', color: o.color ?? '#5a4636', y: -w * 0.32, rz: Math.PI / 2, seg: 14, sx: 1, sz: 1 })
    for (let i = 0; i < 3; i++) b.box(len - 0.1, 0.012, 0.03, { mat: 'dirt', color: '#3e3026', y: w * 0.17 - 0.004, z: -w * 0.2 + i * w * 0.2, ao: 0, shadow: false })
  })
}
// An IBC tote: white tank in a steel cage on a pallet, with a tap.
function tote(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(1.0, 0.12, 1.2, { mat: 'metal', color: '#6a6e72', y: 0.06 })
    b.box(0.92, 0.86, 1.1, { mat: 'plastic', color: o.color ?? '#bcbcb4', y: 0.56, r: 0.05 })
    if (o.fill !== false) b.box(0.88, 0.55 * (o.level ?? 0.75), 0.002, { mat: 'plain', color: '#7a9aa8', y: 0.15 + 0.55 * (o.level ?? 0.75) / 2 + 0.04, z: 0.551, ao: 0 })
    for (let i = 0; i <= 4; i++) {
      for (const s of [-1, 1]) b.box(0.02, 0.92, 0.02, { mat: 'steel', color: '#9aa0a4', x: s * 0.48, y: 0.58, z: -0.55 + i * 0.275 })
      for (const s of [-1, 1]) b.box(0.02, 0.92, 0.02, { mat: 'steel', color: '#9aa0a4', x: -0.48 + i * 0.24, y: 0.58, z: s * 0.56 })
    }
    for (const y of [0.35, 0.65, 1.0]) {
      for (const s of [-1, 1]) b.box(0.02, 0.02, 1.12, { mat: 'steel', color: '#9aa0a4', x: s * 0.48, y })
      for (const s of [-1, 1]) b.box(0.96, 0.02, 0.02, { mat: 'steel', color: '#9aa0a4', y, z: s * 0.56 })
    }
    b.cyl(0.08, 0.08, 0.04, { mat: 'plastic', color: '#2a2a2a', y: 1.0, seg: 12 })
    b.cyl(0.04, 0.04, 0.12, { mat: 'plastic', color: '#2a2a2a', y: 0.2, z: 0.62, rx: Math.PI / 2, seg: 10 })
    b.box(0.03, 0.08, 0.03, { mat: 'plastic', color: '#c8302a', y: 0.27, z: 0.66 })
  })
}

// Copper still pot (onion), with a swan neck heading to +x. y = base.
function potStill(b, o = {}) {
  const c = o.color ?? '#c87a48'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.lathe([[0.001, 0], [0.32, 0.02], [0.42, 0.22], [0.4, 0.48], [0.26, 0.66], [0.12, 0.76], [0.1, 0.92], [0.11, 0.95]], { mat: 'metal', color: c, seg: 20 })
    b.torus(0.42, 0.012, { mat: 'metal', color: shadeHex(c, -0.2), y: 0.3, rx: Math.PI / 2, rs: 4, ts2: 24 })
    b.tube([[0, 0.94, 0], [0.12, 1.08, 0], [0.36, 1.1, 0], [0.7, 0.95, 0], [o.reach ?? 1.0, 0.8, 0]], 0.04, { mat: 'metal', color: c, tseg: 20, seg: 8 })
    b.cyl(0.03, 0.03, 0.06, { mat: 'chrome', color: '#d8dce0', x: 0.1, y: 0.7, z: 0.2, rx: Math.PI / 2, seg: 8 })
  })
}
// A worm-tub condenser: barrel of water with a copper coil showing at the top.
function wormTub(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0 }, () => {
    barrel(b, { color: o.color ?? '#5a5a4a', open: true, contents: '#3a4a50', fill: 0.92 })
    for (let k = 0; k < 2; k++) b.torus(0.16, 0.02, { mat: 'metal', color: '#c87a48', y: 0.84 - k * 0.04, rx: Math.PI / 2, rs: 5, ts2: 18 })
    b.cyl(0.02, 0.02, 0.2, { mat: 'metal', color: '#c87a48', x: 0.25, y: 0.12, z: 0.18, rz: Math.PI / 2, seg: 6 })
  })
}
// Diesel engine with radiator, fan (pivot o.fan), alternator and exhaust.
function dieselEngine(b, I, o = {}) {
  const c = o.color ?? '#3a5a3a'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    // skid
    for (const s of [-1, 1]) b.box(2.2, 0.12, 0.1, { mat: 'paint', color: '#2a2a2a', y: 0.06, z: s * 0.42 })
    b.box(2.2, 0.03, 0.9, { mat: 'metal', color: '#3a3a3a', y: 0.13 })
    // engine block, head and rocker cover
    b.box(0.85, 0.55, 0.6, { mat: 'paint', color: c, x: 0.1, y: 0.44 })
    b.box(0.8, 0.14, 0.5, { mat: 'paint', color: shadeHex(c, 0.08), x: 0.1, y: 0.79 })
    b.box(0.76, 0.08, 0.32, { mat: 'paint', color: '#a8302a', x: 0.1, y: 0.9 })
    for (let i = 0; i < 4; i++) b.cyl(0.012, 0.012, 0.25, { mat: 'chrome', color: '#c8ccd0', x: -0.2 + i * 0.2, y: 0.88, z: 0.22, rx: Math.PI / 2, seg: 5 })
    b.cyl(0.13, 0.13, 0.3, { mat: 'paint', color: '#2a2a2a', x: 0.2, y: 1.0, z: -0.15, rz: Math.PI / 2, seg: 12 })
    // radiator and fan at the front (-x)
    b.box(0.12, 0.75, 0.75, { mat: 'paint', color: '#2a2a2a', x: -0.62, y: 0.55 })
    for (let i = 0; i < 9; i++) b.box(0.13, 0.012, 0.66, { mat: 'metal', color: '#6a6e72', x: -0.62, y: 0.25 + i * 0.075 })
    b.pivot(o.fan ?? 'fan', { x: -0.48, y: 0.55 }, (p) => {
      p.cyl(0.05, 0.05, 0.06, { mat: 'metal', color: '#3a3a3a', rz: Math.PI / 2, seg: 8 })
      for (let i = 0; i < 6; i++) p.box(0.02, 0.28, 0.08, { mat: 'paint', color: '#d8a020', y: Math.cos((i / 6) * TAU) * 0.14, z: Math.sin((i / 6) * TAU) * 0.14, rx: (i / 6) * TAU, ry: 0.3 })
    })
    I.anims.push({ name: o.fan ?? 'fan', kind: 'spin', axis: 'x', speed: 18, when: 'active' })
    // alternator at the back (+x)
    b.cyl(0.32, 0.32, 0.6, { mat: 'paint', color: shadeHex(c, -0.1), x: 0.82, y: 0.48, rz: Math.PI / 2, seg: 18 })
    for (let i = 0; i < 8; i++) b.box(0.5, 0.02, 0.04, { mat: 'paint', color: shadeHex(c, -0.2), x: 0.82, y: 0.48 + Math.cos((i / 8) * TAU) * 0.32, z: Math.sin((i / 8) * TAU) * 0.32, rx: (i / 8) * TAU })
    b.box(0.3, 0.3, 0.25, { mat: 'paint', color: shadeHex(c, -0.1), x: 0.9, y: 0.9, z: 0.0 })
    b.plane(0.2, 0.1, { material: plateMat('400V 3PH', '#e8e4d8', '#1a1a1a'), x: 0.9, y: 0.92, z: 0.126, shadow: false })
    // exhaust: manifold, muffler and stack
    pipeRun(b, [[0.1, 0.75, -0.32], [0.1, 0.75, -0.5], [0.1, 1.15, -0.5]], 0.04, { color: '#4a4440', flanges: false })
    b.cyl(0.13, 0.13, 0.55, { mat: 'rust', color: '#9a8a7a', x: 0.1, y: 1.25, z: -0.5, rz: Math.PI / 2, seg: 14 })
    b.cyl(0.05, 0.05, o.stack ?? 0.9, { mat: 'metal', color: '#3a3836', x: 0.42, y: 1.25 + (o.stack ?? 0.9) / 2, z: -0.5, seg: 10 })
  })
  return [(o.x || 0) + 0.42, (o.y || 0) + 1.25 + (o.stack ?? 0.9), (o.z || 0) - 0.5]
}

// A canvas wall tent: short side walls, a pitched roof (fading pivot), a
// ridge pole, rolled-up door flaps, guy ropes and stakes. Door faces +z.
function wallTent(b, I, o = {}) {
  const w = o.w ?? 2.4
  const d = o.d ?? 3.0
  const wall = o.wall ?? 0.9
  const ridge = o.ridge ?? 2.1
  const c = o.color ?? '#b8a47c'
  b.at({ x: o.x || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(w, 0.02, d, { mat: 'canvas', color: shadeHex(c, -0.35), y: 0.01 })
    for (const s of [-1, 1]) b.box(0.02, wall, d, { mat: 'canvas', color: shadeHex(c, -0.05), x: (s * w) / 2, y: wall / 2 })
    const half = w / 2
    const rise = ridge - wall
    const len = Math.hypot(half + 0.15, rise + 0.08)
    const slope = Math.atan2(rise, half)
    roof(b, I, (r) => {
      for (const s of [-1, 1]) r.box(len, 0.02, d + 0.2, { mat: 'canvas', color: c, x: (s * half) / 2, y: wall + rise / 2 - 0.02, rz: -s * slope })
    })
    // back wall and gable
    b.box(w, wall, 0.02, { mat: 'canvas', color: shadeHex(c, -0.08), y: wall / 2, z: -d / 2 })
    b.wedge(w, rise, 0.02, { mat: 'canvas', color: shadeHex(c, -0.08), y: wall, z: -d / 2 })
    // front: gable above an open doorway, flaps rolled and tied
    b.wedge(w, rise, 0.02, { mat: 'canvas', color: shadeHex(c, -0.04), y: wall, z: d / 2 })
    for (const s of [-1, 1]) {
      b.box(w * 0.22, wall, 0.02, { mat: 'canvas', color: shadeHex(c, -0.04), x: s * (w / 2 - w * 0.11), y: wall / 2, z: d / 2 })
      b.cyl(0.07, 0.07, wall + rise * 0.6, { mat: 'canvas', color: shadeHex(c, -0.12), x: s * w * 0.22, y: (wall + rise * 0.6) / 2, z: d / 2 + 0.04, seg: 8 })
    }
    b.cyl(0.03, 0.03, ridge + 0.1, { mat: 'wood', color: '#9a8466', y: (ridge + 0.1) / 2, z: d / 2 + 0.02, seg: 6 })
    b.cyl(0.03, 0.03, ridge + 0.1, { mat: 'wood', color: '#9a8466', y: (ridge + 0.1) / 2, z: -d / 2 - 0.02, seg: 6 })
    b.cyl(0.025, 0.025, d + 0.3, { mat: 'wood', color: '#9a8466', y: ridge + 0.03, rx: Math.PI / 2, seg: 6 })
    for (const s of [-1, 1]) for (const z of [-d / 2, 0, d / 2]) {
      b.rope([[s * (w / 2), wall, z], [s * (w / 2 + 0.7), 0.03, z]], 0.006, { mat: 'cloth', color: '#b49c74', sag: 0.01, steps: 2 })
      b.box(0.03, 0.12, 0.03, { mat: 'wood', color: '#6a5038', x: s * (w / 2 + 0.7), y: 0.03, z, rz: s * 0.3 })
    }
    if (o.cross) {
      for (const s of [-1, 1]) b.at({ x: s * (half / 2 + 0.02), y: wall + rise / 2 + 0.02, rz: -s * slope }, () => {
        b.box(0.5, 0.012, 0.14, { mat: 'paint', color: '#c8201a', ao: 0 })
        b.box(0.14, 0.012, 0.5, { mat: 'paint', color: '#c8201a', ao: 0 })
      })
    }
  })
}
// Steel-frame bunk bed with mattresses, pillows and rumpled blankets.
function bunkBed(b, o = {}) {
  const rnd = o.rnd ?? seeded(3)
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    const fr = { mat: 'paint', color: o.frame ?? '#5a6a5a' }
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.05, o.single ? 0.7 : 1.75, 0.05, { ...fr, x: sx * 0.45, y: (o.single ? 0.7 : 1.75) / 2, z: sz * 1.0 })
    for (const y of o.single ? [0.38] : [0.38, 1.3]) {
      for (const sz of [-1, 1]) b.box(0.94, 0.05, 0.04, { ...fr, y, z: sz * 1.0 })
      for (const sx of [-1, 1]) b.box(0.04, 0.05, 2.0, { ...fr, x: sx * 0.45, y })
      b.box(0.86, 0.12, 1.95, { mat: 'cloth', color: '#d8d0c0', y: y + 0.08, r: 0.04 })
      b.box(0.5, 0.1, 0.3, { mat: 'cloth', color: '#e8e4dc', y: y + 0.18, z: -0.78, r: 0.04 })
      b.cloth(0.88, 1.3, { mat: 'cloth', color: pick(rnd, ['#5a6a8a', '#8a4a3a', '#4a6a4a', '#7a6a4a', '#5a4a6a']), y: y + 0.16, z: 0.25, sag: -0.06, segX: 5, segZ: 6, wrinkle: 0.08, seed: Math.floor(rnd() * 99) })
    }
    if (!o.single) for (let i = 0; i < 4; i++) b.box(0.03, 0.03, 0.4, { ...fr, x: 0.45, y: 0.6 + i * 0.22, z: 0.75 })
  })
}
// Hospital bed with rails, sheets and an IV stand.
function hospitalBed(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.9, 0.08, 2.0, { mat: 'paint', color: '#c8ccc8', y: 0.55 })
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
      b.box(0.04, 0.55, 0.04, { mat: 'chrome', color: '#c8ccd0', x: sx * 0.42, y: 0.275, z: sz * 0.95 })
      b.cyl(0.04, 0.04, 0.03, { mat: 'rubber', color: '#1a1a1a', x: sx * 0.42, y: 0.04, z: sz * 0.95, rz: Math.PI / 2, seg: 8 })
    }
    b.box(0.9, 0.4, 0.04, { mat: 'paint', color: '#c8ccc8', y: 0.8, z: -1.0 })
    b.box(0.86, 0.14, 1.92, { mat: 'cloth', color: '#e8eef0', y: 0.66, r: 0.04 })
    b.box(0.5, 0.1, 0.3, { mat: 'cloth', color: '#f0f4f4', y: 0.77, z: -0.75, rx: 0.3, r: 0.04 })
    b.cloth(0.9, 1.2, { mat: 'cloth', color: o.sheet ?? '#a8c8d8', y: 0.74, z: 0.3, sag: -0.08, segX: 5, segZ: 6, wrinkle: 0.05, seed: 3 })
    for (const sx of [-1, 1]) b.box(0.02, 0.12, 0.9, { mat: 'chrome', color: '#c8ccd0', x: sx * 0.46, y: 0.8, z: -0.3 })
    if (o.iv !== false) b.at({ x: 0.6, z: -0.8 }, () => {
      for (let i = 0; i < 4; i++) b.beam([0, 0.02, 0], [Math.cos(i * 1.57) * 0.22, 0.02, Math.sin(i * 1.57) * 0.22], 0.02, 0.02, { mat: 'chrome', color: '#c8ccd0' })
      b.cyl(0.012, 0.012, 1.9, { mat: 'chrome', color: '#c8ccd0', y: 0.95, seg: 6 })
      b.box(0.3, 0.012, 0.012, { mat: 'chrome', color: '#c8ccd0', y: 1.88 })
      b.box(0.1, 0.16, 0.04, { mat: 'glass', color: '#e8e8d8', x: 0.1, y: 1.75, r: 0.01 })
      b.rope([[0.1, 1.66, 0], [0.0, 1.2, 0.2], [-0.4, 0.85, 0.35]], 0.004, { mat: 'glass', color: '#e8eef0', sag: 0.1 })
    })
  })
}
// Picnic table with attached benches.
function picnicTable(b, o = {}) {
  const c = o.color ?? '#b89a70'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (let i = 0; i < 4; i++) b.box(1.8, 0.04, 0.17, { mat: 'wood', color: shadeHex(c, (i % 2) * 0.05), y: 0.76, z: -0.27 + i * 0.18, jitter: 0.05 })
    for (const s of [-1, 1]) {
      b.box(1.8, 0.04, 0.26, { mat: 'wood', color: c, y: 0.45, z: s * 0.68 })
      for (const x of [-0.7, 0.7]) {
        b.beam([x, 0, s * 0.7], [x, 0.74, s * 0.05], 0.07, 0.05, { mat: 'wood', color: shadeHex(c, -0.12) })
        b.box(0.05, 0.06, 1.55, { mat: 'wood', color: shadeHex(c, -0.12), x, y: 0.42 })
      }
    }
  })
}
// A split-log bench on two stumps.
function logSeat(b, o = {}) {
  const L = o.len ?? 1.5
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (const x of [-L / 2 + 0.25, L / 2 - 0.25]) b.cyl(0.16, 0.18, 0.32, { mat: 'bark', color: '#6a5a48', x, y: 0.16, seg: 9 })
    b.cyl(0.2, 0.2, L, { mat: 'bark', color: '#7a6a58', y: 0.32, rz: Math.PI / 2, seg: 12, ts: Math.PI, tl: Math.PI, sz: 0.9 })
    b.box(L, 0.012, 0.36, { mat: 'wood', color: '#d8b888', y: 0.33 })
  })
}

// ---------------------------------------------------------------- the stations
export const HD = {
  // ============================================================ WORKBENCH
  // The crafting table: parts, melee weapons and tools, melee mods, repairs.
  workbench(b, L, I) {
    const rnd = seeded(500 + L)
    if (L === 1) {
      // an old door on two sawhorses under a tarp lean-to, tools nailed to a board
      shavings(b, rnd, -1.6, 1.4, -1.0, 0.9, 12)
      canopy(b, I, { x: -0.1, z: -0.65, w: 2.9, d: 1.5, hf: 2.3, hb: 1.85, color: '#5e6a4c', seed: 21 })
      const tz = -0.55
      for (const x of [-0.75, 0.55]) sawhorse(b, { x: x - 0.1, z: tz, len: 0.8, ry: Math.PI / 2 + (rnd() - 0.5) * 0.15, color: '#c8b088' })
      // the door: panels, a knob hole, flaking paint
      b.at({ x: -0.1, y: 0.79, z: tz }, () => {
        b.box(2.0, 0.045, 0.82, { mat: 'paint', color: '#6e7f6a', y: 0.0225 })
        for (const x of [-0.5, 0.5]) for (const z of [-0.18, 0.18]) b.box(0.8, 0.012, 0.28, { mat: 'paint', color: '#66766a', x, y: 0.05, z })
        b.cyl(0.03, 0.03, 0.05, { mat: 'plain', color: '#1a1a1a', x: 0.9, y: 0.03, z: 0.3, seg: 10 })
        for (let i = 0; i < 6; i++) blob(b, 0.1 + rnd() * 0.25, 0.06 + rnd() * 0.12, { rnd, mat: 'wood', color: '#b89870', x: (rnd() - 0.5) * 1.8, y: 0.047, z: (rnd() - 0.5) * 0.7, rx: -Math.PI / 2, rz: rnd() * TAU })
      })
      const ty = 0.835
      vise(b, { x: 0.78, y: ty, z: tz + 0.3, ry: 0 })
      // a bat taking shape in the vise, a saw, hammer and a tin of nails
      b.lathe([[0.02, 0], [0.025, 0.25], [0.035, 0.45], [0.045, 0.62], [0.035, 0.66], [0.001, 0.67]], { mat: 'wood', color: '#d8b07a', x: 0.78, y: ty + 0.1, z: tz + 0.66, rx: Math.PI / 2, seg: 12 })
      handSaw(b, { x: -0.3, y: ty + 0.012, z: tz + 0.05, rx: -Math.PI / 2, rz: 1.2 })
      hammer(b, { x: 0.25, y: ty + 0.015, z: tz - 0.15, rx: -Math.PI / 2, rz: 0.4 })
      b.cyl(0.06, 0.06, 0.1, { mat: 'metal', color: '#9a9488', x: -0.75, y: ty + 0.05, z: tz - 0.2, seg: 12 })
      for (let i = 0; i < 6; i++) b.cyl(0.003, 0.003, 0.07, { mat: 'steel', color: '#8a8e92', x: -0.75 + (rnd() - 0.5) * 0.06, y: ty + 0.1, z: tz - 0.2 + (rnd() - 0.5) * 0.06, rx: (rnd() - 0.5) * 0.6, seg: 4 })
      benchClutter(b, -0.9, -0.4, tz + 0.1, tz + 0.3, ty, rnd, ['nuts', 'rag', 'cup'])
      // tool board on two log posts behind
      for (const x of [-1.0, 0.8]) pole(b, x, -1.12, 1.9, { r: 0.055 })
      pegboard(b, { x: -0.1, y: 0.95, z: -1.08, w: 1.75, h: 0.8, seed: 35, shelf: false, kinds: ['saw', 'hammer', 'wrench', 'pliers', 'clamp', 'screw', 'hacksaw'] })
      lantern(b, { x: 0.1, y: 1.85, z: -0.35 })
      b.rope([[0.1, 2.1, -0.35], [0.1, 2.12, -0.75]], 0.005, { mat: 'cloth', color: COL.rope, sag: 0 })
      I.lights.push({ x: 0.1, y: 1.95, z: -0.35, color: '#ffb060', intensity: 2.6, dist: 7, when: 'night' })
      // the wood pile, the chopping stump, the scrap
      plankStack(b, { x: -1.45, z: 0.75, len: 1.3, w: 0.55, layers: 4, ry: 0.2 })
      stump(b, { x: 1.35, z: 0.55, r: 0.24, h: 0.42 })
      b.at({ x: 1.38, y: 0.42, z: 0.55, rz: -0.5, ry: 0.4 }, () => {
        b.cyl(0.02, 0.022, 0.62, { mat: 'wood', color: '#b8844a', y: 0.28, seg: 8 })
        b.box(0.025, 0.13, 0.18, { mat: 'metal', color: '#6a6e72', y: 0.0, z: 0.05 })
      })
      toolbox(b, { x: 1.4, z: -0.3, ry: -0.5, color: '#a8302a' })
      bucket(b, { x: -1.5, z: -0.2, color: '#8a8e92' })
      scrapPile(b, { x: 1.5, z: -1.0, r: 0.5, n: 7, seed: 23 })
      I.spots.push({ x: -0.25, z: 0.12, face: FACE_BACK, anim: 'saw' }, { x: 1.15, z: 0.85, face: -2.3, anim: 'hammer' })
      return
    }
    if (L === 2) {
      // a timber workshop: board walls, a long bench, pegboard, small machines
      const W = 3.9
      const D = 2.7
      b.box(W, 0.1, D, { mat: 'planks', color: '#d8c8a8', y: 0.05 })
      stains(b, rnd, -1.6, 1.6, -1.0, 1.0, 3, 0.102)
      boardWall(b, -W / 2, W / 2, -D / 2 + 0.03, 2.5, { y0: 0.1, seed: 14, color: '#c8b494' })
      b.at({ ry: Math.PI / 2 }, () => boardWall(b, -D / 2, D / 2 - 0.9, -W / 2 + 0.03, 2.5, { y0: 0.1, seed: 15, color: '#c8b494' }))
      frame(b, W - 0.1, D - 0.1, 2.6, { hFront: 2.85, braces: true, color: '#b8a080' })
      roof(b, I, (r) => corrRoof(r, { w: W + 0.4, d: D + 0.5, y: 2.98, drop: 0.4, gutter: true, seed: 6, ry: Math.PI }))
      // the long bench with drawers, pegboard above
      const by = heavyBench(b, { x: -0.35, z: -0.88, w: 2.7, d: 0.72, drawers: 3, seed: 2 })
      pegboard(b, { x: -0.35, y: 1.12, z: -1.3, w: 2.6, h: 0.95, seed: 36 })
      vise(b, { x: 0.82, y: by, z: -0.62 })
      benchGrinder(b, { x: -1.4, y: by, z: -0.95 })
      drillPress(b, I, { x: -0.75, y: by, z: -0.98, name: 'feed' })
      benchClutter(b, -0.4, 0.55, -1.1, -0.65, by, rnd, ['wrench', 'screw', 'nuts', 'cup', 'bolts', 'rag'])
      // machete blank and file on the bench
      b.box(0.5, 0.006, 0.07, { mat: 'metal', color: '#9aa0a4', x: 0.1, y: by + 0.004, z: -0.72, ry: 0.15 })
      const lamp = benchLamp(b, { x: 0.3, y: by, z: -1.08 })
      I.lights.push({ x: lamp[0], y: lamp[1], z: lamp[2], color: '#ffd8a0', intensity: 2.2, dist: 5, when: 'active' })
      // lumber rack on the left wall
      for (const z of [-0.9, 0.0]) for (const y of [0.9, 1.4, 1.9]) b.box(0.32, 0.04, 0.06, { mat: 'metal', color: '#3a3a3a', x: -W / 2 + 0.2, y, z })
      for (let i = 0; i < 9; i++) b.box(0.08 + rnd() * 0.08, 0.04 + rnd() * 0.06, 1.5 + rnd() * 0.4, { mat: 'wood', color: shadeHex('#d8b888', (rnd() - 0.5) * 0.2), x: -W / 2 + 0.12 + (i % 3) * 0.1, y: 0.95 + Math.floor(i / 3) * 0.5, z: -0.45 })
      // right side: tool chest and parts bins, anvil on a stump
      toolChest(b, { x: 1.5, z: -0.95, ry: -0.15 })
      partsBins(b, { x: 1.4, y: 1.42, z: -1.2, cols: 3, rows: 3, seed: 7 })
      anvil(b, { x: 1.2, z: 0.7, ry: 0.4 })
      b.cyl(0.18, 0.2, 0.42, { mat: 'metal', color: '#4a4e52', x: 1.62, y: 0.21, z: 0.85, seg: 14 })
      b.cyl(0.17, 0.17, 0.01, { mat: 'water', color: '#3a4a50', x: 1.62, y: 0.39, z: 0.85, seg: 14 })
      // sawhorses with a plank half cut
      for (const x of [-1.2, -0.4]) sawhorse(b, { x, z: 0.65, len: 0.6, ry: Math.PI / 2, color: '#c8b088' })
      b.box(1.5, 0.05, 0.25, { mat: 'wood', color: '#e0c898', x: -0.75, y: 0.79, z: 0.65 })
      shavings(b, rnd, -1.5, 0.0, 0.3, 1.1, 8)
      // a bare bulb and a poster
      bulb(b, { x: 0.3, y: 2.4, z: -0.2, r: 0.05 })
      b.cyl(0.004, 0.004, 0.45, { mat: 'rubber', color: '#1a1a1a', x: 0.3, y: 2.68, z: -0.2, seg: 4 })
      I.lights.push({ x: 0.3, y: 2.35, z: -0.2, color: '#ffc880', intensity: 3, dist: 8, when: 'night' })
      decal(b, posterMat(3), 0.32, 0.44, { x: 0.8, y: 1.85, z: -1.31 })
      I.emitters.push({ kind: 'sparks', x: -1.25, y: by + 0.18, z: -0.82, when: 'active' })
      I.spots.push({ x: -0.5, z: -0.2, face: FACE_BACK, anim: 'saw' }, { x: 1.0, z: 1.15, face: -2.6, anim: 'hammer' })
      return
    }
    // L3: a steel machine shop with a lathe, drill press, hydraulic press and
    // a little press line that stamps parts onto a conveyor (automation)
    const W = 3.9
    const D = 2.8
    slab(b, I, { w: W + 0.05, d: D + 0.05, h: 0.12, seed: 9 })
    stains(b, rnd, -1.7, 1.7, -1.1, 1.2, 5, 0.102)
    // yellow walkway lines
    for (const z of [-0.25]) b.plane(W - 0.3, 0.07, { material: stripeMat(), y: 0.102, z, rx: -Math.PI / 2, shadow: false })
    steelShed(b, I, { w: W, d: D, h: 3.0, hb: 2.7 })
    // back bench with drawers, steel top, pegboard and a vise
    const by = heavyBench(b, { x: -0.85, z: -0.95, w: 2.0, d: 0.7, drawers: 4, metal: true, steelTop: true, frame: '#3a5a7a', seed: 3 })
    pegboard(b, { x: -0.85, y: 1.15, z: -1.36, w: 1.95, h: 0.9, seed: 37, kinds: ['drill', 'grinder', 'wrench', 'wrench', 'screw', 'pliers', 'clamp', 'hammer', 'level'] })
    vise(b, { x: -0.05, y: by, z: -0.7 })
    benchGrinder(b, { x: -1.6, y: by, z: -1.0, color: '#3a6a8a' })
    benchClutter(b, -1.3, -0.2, -1.15, -0.7, by, rnd, ['wrench', 'nuts', 'bolts', 'cup', 'can', 'board'])
    drill(b, { x: -0.45, y: by, z: -1.05, ry: 0.6 })
    const lamp = benchLamp(b, { x: -1.05, y: by, z: -1.15, color: '#1a1a1a' })
    I.lights.push({ x: lamp[0], y: lamp[1], z: lamp[2], color: '#fff0d8', intensity: 2.2, dist: 5, when: 'active' })
    // metal lathe along the left wall, floor drill press at the back
    metalLathe(b, I, { x: -1.55, z: 0.45, ry: Math.PI / 2, len: 1.3, name: 'chuck' })
    drillPress(b, I, { x: 0.5, z: -1.05, floor: true, name: 'feed', color: '#3a5a7a' })
    gasBottle(b, { x: 1.0, z: -1.22, color: '#2a6a3a' })
    gasBottle(b, { x: 1.2, z: -1.28, color: '#a82a22' })
    b.rope([[1.0, 1.2, -1.22], [0.6, 0.5, -0.5], [-0.1, by + 0.02, -0.75]], 0.012, { mat: 'rubber', color: '#2a6a3a', sag: 0.1 })
    // the press line: hopper of scrap -> press -> conveyor -> parts crate
    hopper(b, { x: 1.55, z: -0.75, w: 0.42, h: 1.25, color: '#7a8086', fill: '#7a7a74' })
    shopPress(b, I, { x: 1.5, z: 0.3, ry: -Math.PI / 2, w: 0.7, h: 1.7, color: '#3a5a7a', name: 'ram', amp: 0.14, speed: 0.8 })
    conveyor(b, I, {
      x: 0.5, z: 0.3, len: 1.3, w: 0.4, h: 0.62, name: 'belt', gap: 0.32, speed: 0.4,
      items: (p, x, r) => {
        // stamped gears and brackets riding the belt
        if (r() < 0.5) p.torus(0.05, 0.015, { mat: 'metal', color: '#a8acb0', x, y: 0.015, z: (r() - 0.5) * 0.15, rx: Math.PI / 2, rs: 4, ts2: 12 })
        else p.box(0.12, 0.02, 0.08, { mat: 'metal', color: '#9aa0a4', x, y: 0.01, z: (r() - 0.5) * 0.15, ry: r() * 3 })
      },
    })
    crate(b, { x: -0.45, z: 0.3, w: 0.5, h: 0.42, d: 0.5, lid: false, color: '#c8b898' })
    for (let i = 0; i < 6; i++) b.torus(0.05, 0.015, { mat: 'metal', color: '#a8acb0', x: -0.45 + (rnd() - 0.5) * 0.3, y: 0.38 + rnd() * 0.03, z: 0.3 + (rnd() - 0.5) * 0.3, rx: Math.PI / 2 + (rnd() - 0.5), rs: 4, ts2: 12 })
    controlPanel(b, { x: 0.9, z: 0.95, ry: -0.5 })
    beacon(b, I, { x: 1.5, y: 1.92, z: 0.3 })
    toolChest(b, { x: 1.62, z: 1.12, ry: -Math.PI / 2, w: 0.55 })
    elecPanel(b, { x: 1.55, y: 1.35, z: -1.42, conduit: 1.2 })
    cable(b, [[1.45, 1.1, -1.4], [1.7, 0.2, -0.6], [1.7, 0.3, 0.2]], { sag: 0.05 })
    for (const x of [-0.7, 0.8]) fluoroLight(b, { x, y: 2.35, z: -0.2, len: 1.2, chain: 0.3 })
    I.lights.push({ x: -0.7, y: 2.3, z: -0.2, color: '#e8f0ff', intensity: 3.2, dist: 8, when: 'night' }, { x: 0.8, y: 2.3, z: -0.2, color: '#e8f0ff', intensity: 3.2, dist: 8, when: 'night' })
    decal(b, plateMat('EYE PROTECTION', '#2a5a9a', '#e8e4d8'), 0.42, 0.21, { x: 0.35, y: 1.95, z: -1.37 })
    I.emitters.push({ kind: 'sparks', x: -1.45, y: by + 0.18, z: -0.88, when: 'active' }, { kind: 'sparks', x: 1.25, y: 0.7, z: 0.3, when: 'active' })
    I.spots.push({ x: -0.95, z: -0.22, face: FACE_BACK, anim: 'saw' }, { x: -1.0, z: 0.75, face: -Math.PI / 2, anim: 'type' })
  },

  // ============================================================ WEAPONS STATION
  // Gunsmithing: builds firearms, fits suppressors, scopes and magazines, repairs guns.
  weapons(b, L, I) {
    const rnd = seeded(600 + L)
    if (L === 1) {
      // a cleaning table under a camo tarp, a rack of rifles, and a short
      // zeroing lane: sandbag rest at one end, hay bales and a target at the other
      for (let i = 0; i < 14; i++) b.cyl(0.006, 0.006, 0.025, { mat: 'gloss', color: '#c8a050', x: 0.8 + rnd() * 1.4, y: 0.006, z: 0.3 + rnd() * 1.0, rz: Math.PI / 2, ry: rnd() * TAU, seg: 6 })
      canopy(b, I, { x: 0.1, z: -0.25, w: 4.4, d: 2.5, hf: 2.4, hb: 2.1, material: camoNetMat(), sag: 0.18, seed: 31 })
      table(b, { x: -0.6, z: -0.62, w: 2.0, d: 0.8, h: 0.78, color: '#a8957a', stretcher: true })
      const ty = 0.78
      b.cloth(1.1, 0.5, { mat: 'cloth', color: '#c8b898', x: -0.7, y: ty + 0.006, z: -0.6, sag: -0.005, segX: 6, segZ: 4, seed: 4 })
      // a rifle stripped for cleaning: stock, receiver, barrel, bolt
      b.box(0.04, 0.1, 0.36, { mat: 'wood', color: '#7a4a2a', x: -1.05, y: ty + 0.06, z: -0.6, rx: Math.PI / 2, ry: 0.15 })
      b.box(0.3, 0.06, 0.05, { mat: 'metal', color: '#2a2c2e', x: -0.65, y: ty + 0.035, z: -0.58 })
      b.cyl(0.011, 0.011, 0.6, { mat: 'metal', color: '#3a3e42', x: -0.42, y: ty + 0.02, z: -0.72, rz: Math.PI / 2, ry: 0.1, seg: 8 })
      b.cyl(0.01, 0.01, 0.16, { mat: 'chrome', color: '#b8bcc0', x: -0.7, y: ty + 0.015, z: -0.42, rz: Math.PI / 2, seg: 8 })
      for (let i = 0; i < 2; i++) b.cyl(0.004, 0.004, 0.8, { mat: 'steel', color: '#c8ccd0', x: -0.4, y: ty + 0.01, z: -0.88 + i * 0.03, rz: Math.PI / 2, seg: 4 })
      b.cyl(0.03, 0.035, 0.12, { mat: 'plastic', color: '#d8b020', x: 0.15, y: ty + 0.06, z: -0.78, seg: 10 })
      b.cyl(0.006, 0.004, 0.08, { mat: 'plastic', color: '#d82a1a', x: 0.15, y: ty + 0.16, z: -0.78, seg: 6 })
      pistol(b, { x: 0.05, y: ty + 0.02, z: -0.45, rx: -Math.PI / 2, rz: 0.8 })
      ammoCan(b, { x: 0.4, y: ty, z: -0.75, ry: 0.2 })
      benchClutter(b, -0.15, 0.3, -0.55, -0.35, ty, rnd, ['cup', 'bolts', 'rag'])
      // the rack and the stock of salvaged guns
      gunRack(b, { x: 1.3, z: -1.15, n: 4, seed: 6 })
      for (let i = 0; i < 3; i++) ammoCan(b, { x: 2.05, y: i === 2 ? 0.21 : 0, z: -1.15 + (i === 1 ? 0.15 : 0), ry: 0.1 * i })
      crate(b, { x: 2.0, z: -0.35, w: 0.75, h: 0.4, d: 0.5, lid: false, color: '#a8a078', ry: -0.1 })
      longGun(b, { x: 2.0, y: 0.42, z: -0.35, ry: Math.PI / 2 + 0.1, kind: 'shotgun' })
      longGun(b, { x: 1.95, y: 0.45, z: -0.28, ry: Math.PI / 2 - 0.15, kind: 'rifle' })
      // the zeroing lane
      sandbags(b, { x: 1.55, z: 0.9, len: 0.9, rows: 2, seed: 4, ry: Math.PI / 2 })
      longGun(b, { x: 1.5, y: 0.35, z: 0.9, ry: -Math.PI / 2, kind: 'rifle', scope: true })
      for (const y of [0, 0.45]) hayBale(b, { x: -2.15, y, z: 0.85, ry: Math.PI / 2 })
      decal(b, targetMat(), 0.32, 0.4, { x: -1.9, y: 0.68, z: 0.85, ry: Math.PI / 2 })
      lantern(b, { x: -0.6, y: 1.9, z: -0.5 })
      I.lights.push({ x: -0.6, y: 2.0, z: -0.5, color: '#ffb060', intensity: 2.6, dist: 7, when: 'night' })
      I.spots.push({ x: -0.6, z: 0.05, face: FACE_BACK, anim: 'type' }, { x: 1.95, z: 0.9, face: -Math.PI / 2, anim: 'aim' })
      return
    }
    if (L === 2) {
      // the gunsmith's shed: a lathe for barrels, a long bench with a gun vise,
      // pistols on a board, a rifle rack and a sand-filled clearing barrel
      timberShed(b, I, { w: 4.9, d: 2.9, seed: 41, leftOpen: 1.2 })
      metalLathe(b, I, { x: -1.65, z: -0.98, len: 1.25, name: 'chuck', color: '#5a5a4a' })
      const by = heavyBench(b, { x: 0.6, z: -1.0, w: 2.6, d: 0.72, drawers: 3, seed: 5, color: '#c8a878' })
      // gun vise holding a rifle, scope and parts trays
      b.box(0.5, 0.06, 0.12, { mat: 'paint', color: '#c83a2a', x: 0.1, y: by + 0.03, z: -0.85 })
      for (const x of [-0.08, 0.28]) b.box(0.08, 0.12, 0.1, { mat: 'paint', color: '#c83a2a', x, y: by + 0.12, z: -0.85 })
      longGun(b, { x: 0.12, y: by + 0.2, z: -0.86, ry: Math.PI / 2, kind: 'rifle', scope: true })
      for (let i = 0; i < 3; i++) {
        b.box(0.22, 0.03, 0.15, { mat: 'metal', color: '#8a8e92', x: 0.8 + i * 0.26, y: by + 0.015, z: -0.75 })
        for (let k = 0; k < 5; k++) b.box(0.03, 0.015, 0.02, { mat: 'metal', color: pick(rnd, ['#2a2c2e', '#9aa0a4', '#c8a050']), x: 0.72 + i * 0.26 + rnd() * 0.16, y: by + 0.035, z: -0.8 + rnd() * 0.1 })
      }
      pistol(b, { x: 1.55, y: by + 0.02, z: -0.8, rx: -Math.PI / 2, rz: 2.2 })
      const lamp = benchLamp(b, { x: 1.7, y: by, z: -1.2 })
      I.lights.push({ x: lamp[0], y: lamp[1], z: lamp[2], color: '#ffd8a0', intensity: 2.2, dist: 5, when: 'active' })
      // pistol board above
      b.box(1.6, 0.7, 0.02, { mat: 'wood', color: '#6a5038', x: 0.6, y: 1.45, z: -1.38 })
      decal(b, pegMat2(), 1.55, 0.65, { x: 0.6, y: 1.45, z: -1.365, repeat: 0.4 })
      for (let i = 0; i < 6; i++) pistol(b, { x: 0.0 + i * 0.24, y: 1.55, z: -1.32, rx: 0, ry: Math.PI / 2, rz: Math.PI / 2 })
      for (let i = 0; i < 6; i++) b.box(0.03, 0.12, 0.05, { mat: 'paint', color: '#1e1e1e', x: 0.0 + i * 0.24, y: 1.25, z: -1.33 })
      // rifle rack on the left wall, ammo and a crate
      gunRack(b, { x: -2.25, z: 0.25, ry: Math.PI / 2, n: 5, seed: 7 })
      for (let i = 0; i < 4; i++) ammoCan(b, { x: 1.95 + (i % 2) * 0.3, y: Math.floor(i / 2) * 0.21, z: 0.85, ry: 0.05 })
      // clearing barrel full of sand and a short test lane
      drum(b, { x: -1.9, z: 0.95, color: '#5a5a4a' })
      b.cyl(0.27, 0.27, 0.02, { mat: 'dirt', color: '#c8b080', x: -1.9, y: 0.86, z: 0.95, seg: 18 })
      b.box(0.5, 0.5, 0.03, { mat: 'metal', color: '#3a3a3a', x: -1.9, y: 1.2, z: 0.75, rx: -0.4 })
      sandbags(b, { x: 0.9, z: 0.95, len: 0.8, rows: 2, seed: 6, ry: Math.PI / 2 })
      hangBulb(b, I, 0.6, 2.3, -0.3)
      decal(b, plateMat('CHECK YOUR CHAMBER', '#e8e4d8', '#8a1a12', 'warn'), 0.5, 0.25, { x: -1.0, y: 1.9, z: -1.36 })
      I.emitters.push({ kind: 'sparks', x: -1.35, y: 1.0, z: -1.0, when: 'active' })
      I.spots.push({ x: 0.3, z: -0.3, face: FACE_BACK, anim: 'type' }, { x: -1.6, z: -0.35, face: FACE_BACK, anim: 'type' })
      return
    }
    // L3: the armory workshop: a CNC mill machining receivers onto a
    // conveyor, a caged gun locker, a gunsmith bench and a test bay
    const W = 4.9
    const D = 2.9
    slab(b, I, { w: W + 0.05, d: D + 0.05, h: 0.12, seed: 13 })
    stains(b, rnd, -2.0, 2.0, -1.1, 1.2, 4, 0.102)
    steelShed(b, I, { w: W, d: D, h: 3.0, hb: 2.7, color: '#3e4448' })
    // caged locker with racks inside
    cage(b, 1.5, 0.65, 2.0, { x: -1.6, z: -1.05, open: [] })
    gunRack(b, { x: -1.95, z: -1.18, n: 3, metal: true, seed: 8 })
    gunRack(b, { x: -1.3, z: -1.18, n: 3, metal: true, seed: 9 })
    b.box(0.2, 0.25, 0.04, { mat: 'metal', color: '#3a3a3a', x: -0.85, y: 1.0, z: -0.72 })
    // CNC mill
    cncMill(b, I, { x: 0.1, z: -0.92 })
    controlPanel(b, { x: 0.85, z: -0.55, ry: -0.5 })
    conveyor(b, I, {
      x: 0.95, z: 0.35, len: 1.4, w: 0.36, h: 0.7, ry: -Math.PI / 2, name: 'belt', gap: 0.35, speed: 0.3,
      items: (p, x, r) => {
        p.box(0.2, 0.05, 0.06, { mat: 'metal', color: '#2a2c2e', x, y: 0.025, z: (r() - 0.5) * 0.1 })
        p.box(0.03, 0.06, 0.04, { mat: 'metal', color: '#2a2c2e', x: x - 0.05, y: 0.05, z: (r() - 0.5) * 0.1 })
      },
    })
    crate(b, { x: 0.95, z: 1.25, w: 0.55, h: 0.45, d: 0.45, lid: false, color: '#9aa070' })
    // gunsmith bench on the right
    const by = heavyBench(b, { x: 1.75, z: -1.0, w: 1.3, d: 0.7, drawers: 3, metal: true, steelTop: true, frame: '#3e4448', seed: 6 })
    vise(b, { x: 1.4, y: by, z: -0.75 })
    longGun(b, { x: 1.85, y: by + 0.05, z: -0.95, ry: Math.PI / 2, kind: 'ar' })
    drillPress(b, I, { x: 2.15, y: by, z: -1.1, name: 'feed', color: '#3e4448' })
    const lamp = benchLamp(b, { x: 1.2, y: by, z: -1.2, color: '#1a1a1a' })
    I.lights.push({ x: lamp[0], y: lamp[1], z: lamp[2], color: '#fff0d8', intensity: 2.2, dist: 5, when: 'active' })
    // test bay: bullet trap and a shooting bench
    b.at({ x: -2.15, z: 0.75 }, () => {
      b.box(0.5, 1.2, 0.9, { mat: 'paint', color: '#3a3e42', y: 0.6 })
      b.box(0.04, 0.9, 0.8, { mat: 'metal', color: '#6a6e72', x: 0.27, y: 0.6, rz: -0.35 })
      decal(b, targetMat(), 0.28, 0.35, { x: 0.36, y: 0.75, ry: Math.PI / 2 })
    })
    table(b, { x: -0.5, z: 0.95, w: 0.9, d: 0.6, h: 0.8, color: '#8a8e92', mat: 'metal' })
    sandbags(b, { x: -0.6, y: 0.8, z: 0.95, len: 0.5, rows: 1, seed: 7, ry: Math.PI / 2 })
    longGun(b, { x: -0.65, y: 0.98, z: 0.95, ry: -Math.PI / 2, kind: 'rifle', scope: true })
    b.plane(W - 0.4, 0.07, { material: stripeMat(), x: 0, y: 0.102, z: 0.35, rx: -Math.PI / 2, shadow: false })
    elecPanel(b, { x: 0.9, y: 1.35, z: -1.42, conduit: 1.2 })
    for (const x of [-1.0, 1.0]) fluoroLight(b, { x, y: 2.35, z: -0.2, len: 1.2, chain: 0.3 })
    I.lights.push({ x: -1.0, y: 2.3, z: -0.2, color: '#e8f0ff', intensity: 3.2, dist: 8, when: 'night' }, { x: 1.0, y: 2.3, z: -0.2, color: '#e8f0ff', intensity: 3.2, dist: 8, when: 'night' })
    beacon(b, I, { x: 0.1, y: 2.05, z: -0.92 })
    I.emitters.push({ kind: 'sparks', x: 0.1, y: 1.1, z: -0.7, when: 'active' })
    I.spots.push({ x: 0.85, z: -0.1, face: -2.6, anim: 'type' }, { x: 1.6, z: -0.3, face: FACE_BACK, anim: 'type' })
  },

  // ============================================================ AMMO PRESS
  // Presses metal and gunpowder into rounds.
  ammo(b, L, I) {
    const rnd = seeded(700 + L)
    if (L === 1) {
      // hand reloading at a heavy bench, lead melting on a camp stove
      tinLeanTo(b, I, { x: 0.2, z: -0.75, w: 3.4, d: 1.3, hf: 2.25, hb: 1.9, seed: 33 })
      const by = heavyBench(b, { x: -0.35, z: -0.8, w: 2.2, d: 0.7, h: 0.86, seed: 8, shelf: true })
      reloadingPress(b, I, { x: 0.15, y: by, z: -0.75, name: 'lever' })
      brassBin(b, { x: -0.55, y: by, z: -0.85, color: '#2a5ab8' })
      brassBin(b, { x: -1.05, y: by, z: -0.85, color: '#c8302a', fill: '#b87840' })
      // a loading block of primed cases
      b.box(0.2, 0.04, 0.12, { mat: 'plastic', color: '#3a3a3a', x: 0.6, y: by + 0.02, z: -0.7 })
      for (let i = 0; i < 18; i++) b.cyl(0.007, 0.007, 0.035, { mat: 'gloss', color: '#d8b060', x: 0.52 + (i % 6) * 0.032, y: by + 0.055, z: -0.73 + Math.floor(i / 6) * 0.03, seg: 6 })
      // beam scale and funnel
      b.box(0.2, 0.03, 0.08, { mat: 'paint', color: '#2a4a3a', x: 0.85, y: by + 0.015, z: -0.95 })
      b.beam([0.75, by + 0.1, -0.95], [0.95, by + 0.12, -0.95], 0.01, 0.01, { mat: 'chrome', color: '#c8ccd0' })
      b.cyl(0.03, 0.03, 0.01, { mat: 'chrome', color: '#c8ccd0', x: 0.75, y: by + 0.1, z: -0.95, seg: 10 })
      b.cyl(0.004, 0.035, 0.05, { mat: 'plastic', color: '#d8d0b8', x: 0.6, y: by + 0.03, z: -0.95, seg: 10 })
      ammoCan(b, { x: -0.3, y: by, z: -1.0, ry: 0.1 })
      // lead pot on a camp stove; moulds and ingots
      table(b, { x: 1.35, z: -0.85, w: 0.8, d: 0.6, h: 0.7, color: '#8a8e92', mat: 'metal', stretcher: false })
      campStove(b, I, { x: 1.35, y: 0.7, z: -0.85, flame: true })
      b.lathe([[0.08, 0], [0.1, 0.02], [0.11, 0.12], [0.115, 0.13]], { mat: 'metal', color: '#3a3a3a', x: 1.47, y: 0.81, z: -0.85, seg: 14 })
      b.cyl(0.1, 0.1, 0.005, { mat: 'chrome', color: '#d8dce0', x: 1.47, y: 0.92, z: -0.85, seg: 14 })
      for (let i = 0; i < 4; i++) b.box(0.12, 0.03, 0.04, { mat: 'metal', color: '#7a7e82', x: 1.0 + i * 0.13, y: 0.715, z: -0.6 })
      for (const s of [0, 1]) b.at({ x: 1.6 + s * 0.08, y: 1.0, z: -1.12 }, () => {
        b.box(0.04, 0.04, 0.05, { mat: 'metal', color: '#5a5e62' })
        b.cyl(0.008, 0.008, 0.3, { mat: 'wood', color: '#8a5a3a', y: -0.16, seg: 6 })
      })
      // powder kegs, ammo cans
      keg(b, { x: -1.55, z: 0.35, ry: 0.4 })
      keg(b, { x: -1.15, z: 0.55, ry: -0.3, color: '#7a4a2a' })
      for (let i = 0; i < 3; i++) ammoCan(b, { x: 1.55, y: i === 2 ? 0.21 : 0, z: 0.55 + (i === 1 ? 0.15 : 0), ry: -0.3 })
      lantern(b, { x: -0.35, y: 1.85, z: -0.55 })
      I.lights.push({ x: -0.35, y: 1.95, z: -0.55, color: '#ffb060', intensity: 2.6, dist: 7, when: 'night' })
      I.emitters.push({ kind: 'steam', x: 1.47, y: 0.95, z: -0.85, when: 'active' })
      I.spots.push({ x: 0.15, z: -0.12, face: FACE_BACK, anim: 'pump' }, { x: 1.35, z: -0.25, face: FACE_BACK, anim: 'stir' })
      return
    }
    if (L === 2) {
      // two progressive presses on a steel bench, a tumbler, powder and primers
      timberShed(b, I, { w: 3.9, d: 2.9, seed: 51 })
      const by = heavyBench(b, { x: -0.2, z: -1.0, w: 2.8, d: 0.7, drawers: 2, metal: true, steelTop: true, frame: '#3a4a5a', seed: 9 })
      progressivePress(b, I, { x: -0.8, y: by, z: -0.95, name: 'pA' })
      progressivePress(b, I, { x: 0.25, y: by, z: -0.95, name: 'pB', color: '#8a2a22' })
      brassBin(b, { x: 0.8, y: by, z: -1.05, color: '#2a5ab8' })
      for (let i = 0; i < 6; i++) b.box(0.08, 0.05, 0.06, { mat: 'paint', color: pick(rnd, ['#c8302a', '#2a5ab8', '#d8a020']), x: -1.4 + (i % 3) * 0.1, y: by + 0.025 + Math.floor(i / 3) * 0.05, z: -0.75 })
      shelf(b, { x: 1.55, z: -1.15, w: 0.75, h: 1.9, d: 0.4, seed: 44, kind: 'boxes', fill: 0.9 })
      tumbler(b, I, { x: 1.4, z: 0.4, name: 'tumble' })
      for (let i = 0; i < 2; i++) keg(b, { x: -1.6 + i * 0.4, z: 0.75, ry: i })
      pallet(b, { x: -0.2, z: 0.85, w: 1.0, d: 0.8 })
      for (let i = 0; i < 6; i++) ammoCan(b, { x: -0.45 + (i % 2) * 0.32, y: 0.13 + Math.floor(i / 2) * 0.21, z: 0.85, ry: 0 })
      hangBulb(b, I, -0.2, 2.3, -0.4)
      decal(b, plateMat('NO SMOKING', '#c8302a', '#e8e4d8', 'flame'), 0.44, 0.22, { x: 1.3, y: 2.05, z: -1.36 })
      I.spots.push({ x: -0.75, z: -0.3, face: FACE_BACK, anim: 'pump' }, { x: 0.3, z: -0.3, face: FACE_BACK, anim: 'pump' })
      return
    }
    // L3: the ammo line. Brass hopper -> conveyor -> press -> conveyor ->
    // finished cans on a pallet. A powder hopper feeds the press from above.
    const W = 3.9
    const D = 2.9
    slab(b, I, { w: W + 0.05, d: D + 0.05, h: 0.12, seed: 15 })
    stains(b, rnd, -1.7, 1.7, -1.1, 1.2, 4, 0.102)
    steelShed(b, I, { w: W, d: D, h: 3.0, hb: 2.7, color: '#4a4a42' })
    hopper(b, { x: -1.55, z: -0.95, w: 0.45, h: 1.3, color: '#8a8e92', fill: '#c8a050', fillMat: 'metal' })
    conveyor(b, I, {
      x: -0.55, z: -0.95, len: 1.4, w: 0.34, h: 0.72, name: 'beltA', gap: 0.12, speed: 0.25,
      items: (p, x) => p.cyl(0.012, 0.012, 0.05, { mat: 'gloss', color: '#d8b060', x, y: 0.025, seg: 6 }),
    })
    shopPress(b, I, { x: 0.65, z: -0.95, w: 0.7, h: 1.8, color: '#4a5a3a', name: 'ram', amp: 0.16, speed: 1.1 })
    hopper(b, { x: 0.65, y: 1.25, z: -0.55, w: 0.3, h: 0.7, color: '#2a2a2a', fill: '#3a3a3a' })
    pipeRun(b, [[0.65, 1.7, -0.55], [0.65, 1.75, -0.8], [0.65, 1.95, -0.9]], 0.04, { color: '#3a3a3a' })
    conveyor(b, I, {
      x: 1.45, z: -0.1, len: 1.6, w: 0.36, h: 0.72, ry: -Math.PI / 2, name: 'beltB', gap: 0.3, speed: 0.3,
      items: (p, x) => {
        p.box(0.1, 0.03, 0.08, { mat: 'paint', color: '#c8a050', x, y: 0.015 })
        for (let k = 0; k < 4; k++) p.cyl(0.008, 0.008, 0.04, { mat: 'metal', color: '#c87a40', x: x - 0.03 + k * 0.02, y: 0.05, seg: 6 })
      },
    })
    pallet(b, { x: 1.45, z: 1.15, w: 0.9, d: 0.7 })
    for (let i = 0; i < 8; i++) ammoCan(b, { x: 1.3 + (i % 2) * 0.3, y: 0.13 + Math.floor(i / 2) * 0.21, z: 1.15, ry: Math.PI / 2 })
    controlPanel(b, { x: -0.3, z: 0.3, ry: 0.3 })
    beacon(b, I, { x: 0.65, y: 2.0, z: -0.95 })
    for (let i = 0; i < 3; i++) keg(b, { x: -1.7 + i * 0.4, z: 1.1, ry: i })
    b.plane(W - 0.4, 0.07, { material: stripeMat(), x: 0, y: 0.102, z: -0.35, rx: -Math.PI / 2, shadow: false })
    elecPanel(b, { x: -0.6, y: 1.4, z: -1.42, conduit: 1.1 })
    cable(b, [[-0.4, 1.0, -1.4], [0.2, 0.25, -1.3], [0.55, 0.25, -1.2]], { sag: 0.03 })
    for (const x of [-0.8, 0.9]) fluoroLight(b, { x, y: 2.35, z: -0.2, len: 1.2, chain: 0.3 })
    I.lights.push({ x: -0.8, y: 2.3, z: -0.2, color: '#e8f0ff', intensity: 3.2, dist: 8, when: 'night' }, { x: 0.9, y: 2.3, z: -0.2, color: '#e8f0ff', intensity: 3.2, dist: 8, when: 'night' })
    decal(b, plateMat('EXPLOSIVES', '#e8a020', '#1a1a1a', 'warn'), 0.44, 0.22, { x: 1.4, y: 2.0, z: -1.37 })
    I.spots.push({ x: -0.3, z: 0.85, face: FACE_BACK, anim: 'type' }, { x: 0.95, z: 1.15, face: Math.PI / 2, anim: 'carry' })
  },

  // ============================================================ TAILOR STATION
  // Clothing, armor and backpacks, armor mods and armor repairs.
  tailor(b, L, I) {
    const rnd = seeded(800 + L)
    if (L === 1) {
      // a treadle machine under a tarp, a cutting table, clothes drying
      canopy(b, I, { x: -0.3, z: -1.05, w: 3.9, d: 1.7, hf: 2.3, hb: 1.9, material: patchworkMat(7), seed: 35 })
      treadleTable(b, { x: -1.4, z: -1.2 })
      sewingHead(b, I, { x: -1.4, y: 0.78, z: -1.22, name: 'wheel' })
      b.cloth(0.5, 0.4, { mat: 'cloth', color: '#4a5a6a', x: -1.25, y: 0.81, z: -1.0, sag: -0.01, segX: 4, segZ: 4, seed: 2 })
      stool(b, { x: -1.4, z: -0.6 })
      // cutting table with a pattern, scissors and chalk
      table(b, { x: 0.6, z: -1.1, w: 1.8, d: 0.9, h: 0.82, color: '#b89a78' })
      b.cloth(1.3, 0.75, { mat: 'cloth', color: '#5a4030', x: 0.55, y: 0.826, z: -1.1, sag: -0.005, segX: 6, segZ: 4, seed: 5 })
      b.box(0.5, 0.004, 0.35, { mat: 'plain', color: '#e8e4d0', x: 0.5, y: 0.834, z: -1.05, ry: 0.2 })
      for (const s of [-1, 1]) b.box(0.012, 0.004, 0.16, { mat: 'chrome', color: '#d0d4d8', x: 1.05 + s * 0.012, y: 0.84, z: -0.95, ry: 0.4 + s * 0.15 })
      fabricBolt(b, { x: 1.15, y: 0.82, z: -1.3, len: 0.9, color: '#3a4a5a' })
      // a mannequin made from a pole, wearing a patched jacket
      dressForm(b, { x: 1.85, z: -0.3, ry: -0.5, wear: 'jacket', color: '#5a3a2a' })
      clothesline(b, [-2.1, 1.75, 0.95], [1.6, 1.75, 1.35], { seed: 6 })
      crate(b, { x: -0.6, z: 0.3, w: 0.6, h: 0.42, d: 0.5, lid: false, color: '#b8a888' })
      for (let i = 0; i < 4; i++) b.cloth(0.3, 0.25, { mat: 'cloth', color: pick(rnd, ['#8a3a3a', '#3a5a8a', '#5a6a3a', '#c8b898']), x: -0.6 + (rnd() - 0.5) * 0.3, y: 0.44 + i * 0.02, z: 0.3 + (rnd() - 0.5) * 0.2, sag: -0.04, segX: 3, segZ: 3, seed: i })
      lantern(b, { x: -0.3, y: 1.85, z: -0.8 })
      I.lights.push({ x: -0.3, y: 1.95, z: -0.8, color: '#ffb060', intensity: 2.6, dist: 7, when: 'night' })
      I.spots.push({ x: -1.4, z: -0.55, face: FACE_BACK, anim: 'type', sit: 0.46 }, { x: 0.6, z: -0.4, face: FACE_BACK, anim: 'saw' })
      return
    }
    if (L === 2) {
      // workshop: two machines, a cutting table, bolts of cloth, forms, a hide frame
      timberShed(b, I, { w: 4.9, d: 3.9, seed: 61, leftOpen: 1.6 })
      for (const x of [-1.6, -0.5]) {
        table(b, { x, z: -1.5, w: 1.0, d: 0.6, h: 0.76, color: '#a8957a' })
        sewingHead(b, I, { x, y: 0.76, z: -1.52, name: 'wheel' + (x < -1 ? 'A' : 'B'), color: x < -1 ? '#1e1e1e' : '#3a2a22' })
        stool(b, { x, z: -0.95 })
      }
      table(b, { x: 1.2, z: -0.4, w: 2.0, d: 1.0, h: 0.86, color: '#c8b090' })
      b.cloth(1.6, 0.85, { mat: 'cloth', color: '#4a5a3a', x: 1.15, y: 0.866, z: -0.4, sag: -0.005, segX: 6, segZ: 4, seed: 7 })
      b.box(0.6, 0.004, 0.4, { mat: 'plain', color: '#e8e0c8', x: 1.0, y: 0.875, z: -0.35, ry: -0.15 })
      fabricBolt(b, { x: 1.4, y: 0.86, z: -0.75, len: 1.1, color: '#8a3a3a' })
      // bolt rack on the back wall
      for (const y of [0.5, 1.0, 1.5]) b.box(1.6, 0.04, 0.35, { mat: 'wood', color: '#9a7a5a', x: 1.2, y, z: -1.68 })
      for (let i = 0; i < 12; i++) fabricBolt(b, { x: 1.2, y: 0.52 + Math.floor(i / 4) * 0.5, z: -1.72 + (i % 4) * 0.0, len: 1.4, r: 0.07, color: pick(rnd, ['#8a3a3a', '#3a5a8a', '#5a6a3a', '#c8b898', '#4a4a4a', '#a87a3a', '#6a4a6a']) })
      dressForm(b, { x: 2.1, z: 0.6, ry: -0.8, wear: 'vest' })
      dressForm(b, { x: 1.4, z: 1.05, ry: -0.3, wear: 'jacket', color: '#3a3020' })
      hideFrame(b, { x: -1.8, z: 0.9, ry: 0.6, seed: 4 })
      // ironing board and a steam iron
      b.at({ x: -0.3, z: 0.6, ry: 0.2 }, () => {
        b.box(1.1, 0.03, 0.34, { mat: 'cloth', color: '#d8d0c0', y: 0.85, r: 0.01 })
        for (const s of [-1, 1]) b.beam([s * 0.3, 0.84, 0], [-s * 0.25, 0, 0], 0.03, 0.03, { mat: 'chrome', color: '#b8bcc0' })
        b.box(0.22, 0.08, 0.11, { mat: 'plastic', color: '#d8d0c8', x: 0.3, y: 0.91, r: 0.03 })
      })
      hangBulb(b, I, 0.0, 2.3, -0.6)
      I.emitters.push({ kind: 'steam', x: -0.0, y: 1.0, z: 0.66, when: 'active' })
      I.spots.push({ x: -1.6, z: -0.95, face: FACE_BACK, anim: 'type', sit: 0.46 }, { x: 1.2, z: 0.25, face: FACE_BACK, anim: 'saw' })
      return
    }
    // L3: armor works: industrial machines, a plate press, racks of finished gear
    const W = 4.9
    const D = 3.9
    slab(b, I, { w: W + 0.05, d: D + 0.05, h: 0.12, seed: 17 })
    stains(b, rnd, -2.0, 2.0, -1.6, 1.6, 3, 0.102)
    steelShed(b, I, { w: W, d: D, h: 3.0, hb: 2.7, color: '#4a4448' })
    for (const x of [-1.8, -0.8]) {
      heavyBench(b, { x, z: -1.5, w: 0.95, d: 0.6, h: 0.76, metal: true, frame: '#5a5458', shelf: false, seed: 11 })
      b.box(0.95, 0.04, 0.6, { mat: 'wood', color: '#c8b898', x, y: 0.74, z: -1.5 })
      sewingHead(b, I, { x, y: 0.76, z: -1.52, name: 'wheel' + (x < -1 ? 'A' : 'B'), color: '#d8d4c8' })
      b.cyl(0.08, 0.08, 0.18, { mat: 'paint', color: '#3a3a3a', x: x + 0.25, y: 0.5, z: -1.5, rz: Math.PI / 2, seg: 12 })
      stool(b, { x, z: -0.95, h: 0.55 })
    }
    shopPress(b, I, { x: 0.45, z: -1.45, w: 0.8, h: 1.8, color: '#4a5a6a', name: 'ram' })
    for (let i = 0; i < 4; i++) b.box(0.32, 0.012, 0.28, { mat: 'metal', color: '#7a7e82', x: 1.1, y: 0.02 + i * 0.014, z: -1.4, ry: i * 0.1 })
    // racks of armor
    b.box(2.2, 0.05, 0.05, { mat: 'chrome', color: '#b8bcc0', x: 1.2, y: 1.75, z: -0.1 })
    for (const s of [-1, 1]) b.box(0.05, 1.75, 0.05, { mat: 'chrome', color: '#b8bcc0', x: 1.2 + s * 1.1, y: 0.875, z: -0.1 })
    for (let i = 0; i < 6; i++) {
      const x = 0.3 + i * 0.36
      b.torus(0.05, 0.008, { mat: 'chrome', color: '#b8bcc0', x, y: 1.7, z: -0.1, arc: Math.PI, rs: 4, ts2: 8 })
      const vc = pick(rnd, ['#3a4038', '#2a2e34', '#5a6040', '#3a3a3a'])
      b.box(0.34, 0.5, 0.12, { mat: 'cloth', color: vc, x, y: 1.35, z: -0.1, r: 0.04 })
      b.box(0.24, 0.18, 0.02, { mat: 'cloth', color: shadeHex(vc, 0.1), x, y: 1.4, z: -0.03 })
    }
    dressForm(b, { x: 2.1, z: 1.1, ry: -0.7, wear: 'armor' })
    dressForm(b, { x: 1.45, z: 1.45, ry: -0.3, wear: 'riot' })
    // big roll stand and a cutting table with a gantry
    for (let i = 0; i < 3; i++) b.cyl(0.18, 0.18, 1.2, { mat: 'cloth', color: ['#3a4038', '#2a2a2e', '#a89070'][i], x: -1.6, y: 0.4 + i * 0.42, z: 0.3, rx: Math.PI / 2, seg: 16 })
    for (const s of [-1, 1]) b.box(0.06, 1.5, 0.06, { mat: 'paint', color: '#4a4e52', x: -1.6, y: 0.75, z: 0.3 + s * 0.65 })
    b.at({ x: -0.3, z: 0.8 }, () => {
      b.box(1.6, 0.06, 1.1, { mat: 'plastic', color: '#3a5a3a', y: 0.88 })
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.06, 0.86, 0.06, { mat: 'paint', color: '#4a4e52', x: sx * 0.72, y: 0.43, z: sz * 0.48 })
      b.cloth(1.3, 0.9, { mat: 'cloth', color: '#5a6040', y: 0.915, sag: -0.003, segX: 6, segZ: 4, seed: 8 })
      b.pivot('gantry', { y: 0.92 }, (p) => {
        p.box(0.08, 0.2, 1.2, { mat: 'paint', color: '#d8a020', y: 0.15 })
        p.box(0.12, 0.12, 0.14, { mat: 'paint', color: '#2a2a2a', y: 0.08, z: 0.1 })
      })
      for (const s of [-1, 1]) b.box(1.6, 0.04, 0.04, { mat: 'metal', color: '#8a8e92', y: 0.93, z: s * 0.58 })
    })
    I.anims.push({ name: 'gantry', kind: 'slide', axis: 'x', amp: 0.6, speed: 0.25, when: 'active' })
    controlPanel(b, { x: 0.65, z: 1.3, ry: 0.4 })
    for (const x of [-1.0, 1.0]) fluoroLight(b, { x, y: 2.35, z: -0.3, len: 1.2, chain: 0.3 })
    I.lights.push({ x: -1.0, y: 2.3, z: -0.3, color: '#e8f0ff', intensity: 3.2, dist: 8, when: 'night' }, { x: 1.0, y: 2.3, z: -0.3, color: '#e8f0ff', intensity: 3.2, dist: 8, when: 'night' })
    I.spots.push({ x: -1.8, z: -0.95, face: FACE_BACK, anim: 'type', sit: 0.55 }, { x: -0.3, z: 1.55, face: FACE_BACK, anim: 'type' })
  },

  // ============================================================ ELECTRONICS BENCH
  // Flashlights, radios, noise makers, night vision and automation modules.
  electronics(b, L, I) {
    const rnd = seeded(900 + L)
    if (L === 1) {
      // a door-on-crates desk, a car battery, a soldering iron and gutted sets
      umbrella(b, I, { x: 0.95, z: -0.3, r: 1.35, h: 2.3, c1: '#d8d0b8', c2: '#b8402a' })
      for (const x of [-1.0, 0.6]) crate(b, { x, z: -0.85, w: 0.55, h: 0.7, d: 0.6, color: '#b8a888' })
      b.box(2.1, 0.045, 0.75, { mat: 'paint', color: '#8a7a68', x: -0.2, y: 0.72, z: -0.85 })
      const ty = 0.745
      carBattery(b, { x: -0.9, y: ty, z: -0.95 })
      solderIron(b, { x: -0.35, y: ty, z: -0.95 })
      radioSet(b, { x: 0.25, y: ty, z: -1.0 })
      b.box(0.2, 0.15, 0.002, { mat: 'plain', color: '#2a6a3a', x: 0.25, y: ty + 0.1, z: -0.8 })
      benchClutter(b, -0.7, 0.6, -0.75, -0.6, ty, rnd, ['board', 'board', 'screw', 'nuts', 'cup'])
      cable(b, [[-0.9, ty + 0.2, -0.95], [-0.6, ty + 0.05, -0.8], [-0.35, ty + 0.06, -0.9]], { r: 0.006, color: '#c8302a', sag: 0.02 })
      const lamp = benchLamp(b, { x: 0.65, y: ty, z: -1.05, color: '#3a3a3a' })
      I.lights.push({ x: lamp[0], y: lamp[1], z: lamp[2], color: '#ffd8a0', intensity: 2.2, dist: 5, when: 'always' })
      // junk to strip: an old telly, a microwave, a box of wire
      b.at({ x: 1.4, z: 0.4, ry: -0.6 }, () => {
        b.box(0.55, 0.45, 0.45, { mat: 'plastic', color: '#3a2e26', y: 0.225, r: 0.02 })
        b.box(0.42, 0.32, 0.02, { mat: 'plain', color: '#1a1e1e', y: 0.24, z: 0.22 })
      })
      b.box(0.5, 0.3, 0.36, { mat: 'paint', color: '#b8b4a8', x: 1.5, y: 0.6, z: 0.42, ry: -0.4 })
      b.box(0.3, 0.2, 0.01, { mat: 'plain', color: '#1a1e1e', x: 1.44, y: 0.6, z: 0.6, ry: -0.4 })
      crate(b, { x: -1.4, z: 0.5, w: 0.5, h: 0.35, d: 0.45, lid: false, color: '#a89878' })
      for (let i = 0; i < 4; i++) b.torus(0.1, 0.02, { mat: 'rubber', color: pick(rnd, ['#c8302a', '#1a1a1a', '#2a5ab8', '#d8a020']), x: -1.4 + (rnd() - 0.5) * 0.2, y: 0.38 + i * 0.03, z: 0.5, rx: Math.PI / 2 + (rnd() - 0.5) * 0.4, rs: 5, ts2: 14 })
      solarPanel(b, { x: -1.6, y: 0.45, z: -0.35, ry: 0.8, w: 0.55, h: 0.85, tilt: -1.1 })
      stool(b, { x: -0.3, z: -0.25 })
      I.emitters.push({ kind: 'solder', x: -0.3, y: ty + 0.2, z: -0.9, when: 'active' })
      I.spots.push({ x: -0.3, z: -0.25, face: FACE_BACK, anim: 'type', sit: 0.5 })
      return
    }
    if (L === 2) {
      // the radio shack bench: scope, supply, drawers, a CRT, and an aerial
      timberShed(b, I, { w: 3.9, d: 2.9, seed: 71 })
      const by = heavyBench(b, { x: -0.2, z: -1.0, w: 2.6, d: 0.7, drawers: 3, seed: 12, color: '#c8b090' })
      b.box(2.4, 0.01, 0.55, { mat: 'rubber', color: '#2a5a7a', x: -0.3, y: by + 0.005, z: -0.95 })
      oscilloscope(b, { x: -0.95, y: by, z: -1.1 })
      crtMonitor(b, { x: 0.65, y: by, z: -1.1, ry: -0.2 })
      solderIron(b, { x: -0.3, y: by, z: -0.9 })
      b.box(0.24, 0.12, 0.2, { mat: 'paint', color: '#2a2a2a', x: 0.1, y: by + 0.06, z: -1.1 })
      for (let i = 0; i < 2; i++) b.box(0.03, 0.02, 0.002, { mat: 'glowRed', color: '#ffffff', x: 0.05 + i * 0.08, y: by + 0.09, z: -0.999 })
      benchClutter(b, -0.6, 0.4, -0.85, -0.7, by, rnd, ['board', 'board', 'screw', 'pliers', 'bolts'])
      componentDrawers(b, { x: -0.2, y: 1.2, z: -1.33, cols: 8, rows: 6 })
      componentDrawers(b, { x: 0.75, y: 1.3, z: -1.33, cols: 4, rows: 5, color: '#5a3a3a' })
      shelf(b, { x: 1.55, z: -1.15, w: 0.7, h: 1.8, d: 0.4, seed: 47, kind: 'parts', metal: true })
      cableReel(b, { x: 1.4, z: 0.6, ry: 0.3 })
      for (let i = 0; i < 3; i++) radioSet(b, { x: -1.5, y: 0.0 + i * 0.25, z: 0.6, ry: 0.4 })
      stool(b, { x: -0.3, z: -0.35, h: 0.55 })
      // the aerial on the roof
      b.cyl(0.03, 0.035, 3.0, { mat: 'metal', color: '#8a8e92', x: 1.85, y: 1.5 + 1.4, z: -1.35, seg: 8 })
      for (let i = 0; i < 4; i++) b.cyl(0.008, 0.008, 1.0 - i * 0.18, { mat: 'metal', color: '#8a8e92', x: 1.85, y: 3.5 + i * 0.2, z: -1.35, rz: Math.PI / 2, seg: 4 })
      cable(b, [[1.85, 3.3, -1.35], [1.0, 2.2, -1.36], [0.4, 1.0, -1.3]], { r: 0.006, sag: 0.15 })
      hangBulb(b, I, -0.2, 2.3, -0.4)
      I.emitters.push({ kind: 'solder', x: -0.25, y: by + 0.2, z: -0.85, when: 'active' })
      I.spots.push({ x: -0.3, z: -0.35, face: FACE_BACK, anim: 'type', sit: 0.55 }, { x: 0.7, z: -0.4, face: FACE_BACK, anim: 'type' })
      return
    }
    // L3: the module lab: screens, a server rack, a pick-and-place machine
    // assembling automation modules onto a conveyor
    const W = 3.9
    const D = 2.9
    slab(b, I, { w: W + 0.05, d: D + 0.05, h: 0.12, color: '#c8ccc8', seed: 19 })
    steelShed(b, I, { w: W, d: D, h: 3.0, hb: 2.7, color: '#3a4a5a' })
    const by = heavyBench(b, { x: -0.9, z: -1.0, w: 2.0, d: 0.7, drawers: 4, metal: true, steelTop: true, frame: '#3a4a5a', seed: 13 })
    b.box(1.9, 0.01, 0.6, { mat: 'rubber', color: '#2a5a7a', x: -0.9, y: by + 0.005, z: -0.95 })
    oscilloscope(b, { x: -1.55, y: by, z: -1.1 })
    crtMonitor(b, { x: -0.85, y: by, z: -1.1, color: '#2a2a2a' })
    solderIron(b, { x: -0.3, y: by, z: -0.9 })
    benchClutter(b, -1.5, -0.3, -0.85, -0.7, by, rnd, ['board', 'board', 'board', 'screw', 'pliers'])
    componentDrawers(b, { x: -0.9, y: 1.25, z: -1.33, cols: 10, rows: 6 })
    serverRack(b, I, { x: 1.55, z: -0.95, seed: 4 })
    // pick-and-place: gantry over a bed of boards, output to a short belt
    b.at({ x: 0.55, z: -0.85 }, () => {
      b.box(0.9, 0.8, 0.7, { mat: 'paint', color: '#d8dcd8', y: 0.4 })
      b.box(0.86, 0.36, 0.66, { mat: 'glass', color: '#d8e8e8', y: 0.98 })
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.03, 0.38, 0.03, { mat: 'paint', color: '#d8dcd8', x: sx * 0.44, y: 0.98, z: sz * 0.34 })
      b.box(0.9, 0.03, 0.7, { mat: 'paint', color: '#d8dcd8', y: 1.17 })
      b.box(0.7, 0.012, 0.4, { mat: 'plain', color: '#2a6a3a', y: 0.81 })
      b.pivot('head', { y: 1.05 }, (p) => {
        p.box(0.06, 0.04, 0.6, { mat: 'metal', color: '#3a3a3a' })
        p.box(0.08, 0.1, 0.08, { mat: 'paint', color: '#2a5a8a', y: -0.05 })
      })
      b.box(0.3, 0.2, 0.012, { mat: 'glowGreen', color: '#0a3a1a', x: 0.25, y: 0.55, z: 0.356 })
    })
    I.anims.push({ name: 'head', kind: 'slide', axis: 'x', amp: 0.25, speed: 0.9, when: 'active' })
    conveyor(b, I, {
      x: 0.55, z: 0.45, len: 1.3, w: 0.34, h: 0.7, ry: -Math.PI / 2, name: 'belt', gap: 0.32, speed: 0.3,
      items: (p, x) => {
        p.box(0.14, 0.05, 0.1, { mat: 'plastic', color: '#3a8ac8', x, y: 0.025 })
        p.box(0.03, 0.01, 0.03, { mat: 'glowGreen', color: '#ffffff', x: x + 0.03, y: 0.055 })
      },
    })
    crate(b, { x: 0.55, z: 1.25, w: 0.5, h: 0.4, d: 0.42, lid: false, color: '#9ab0c0' })
    // UPS batteries and an AC unit
    for (let i = 0; i < 3; i++) carBattery(b, { x: 1.35 + (i % 2) * 0.28, y: Math.floor(i / 2) * 0.24, z: 0.1 })
    b.box(0.8, 0.5, 0.3, { mat: 'paint', color: '#e8e8e0', x: -1.2, y: 2.2, z: -1.3, r: 0.02 })
    for (let i = 0; i < 5; i++) b.box(0.7, 0.012, 0.01, { mat: 'plain', color: '#8a8e92', x: -1.2, y: 2.05 + i * 0.05, z: -1.145 })
    stool(b, { x: -0.9, z: -0.35, h: 0.55 })
    for (const x of [-0.8, 0.9]) fluoroLight(b, { x, y: 2.35, z: -0.2, len: 1.2, chain: 0.3 })
    I.lights.push({ x: -0.8, y: 2.3, z: -0.2, color: '#e8f0ff', intensity: 3.2, dist: 8, when: 'night' }, { x: 0.9, y: 2.3, z: -0.2, color: '#e8f0ff', intensity: 3.2, dist: 8, when: 'night' })
    elecPanel(b, { x: 0.4, y: 1.5, z: -1.42, conduit: 1.0 })
    I.emitters.push({ kind: 'solder', x: -0.3, y: by + 0.2, z: -0.85, when: 'active' })
    I.spots.push({ x: -0.9, z: -0.35, face: FACE_BACK, anim: 'type', sit: 0.55 }, { x: 0.95, z: -0.2, face: -2.4, anim: 'type' })
  },

  // ============================================================ CHEMISTRY LAB
  // Gunpowder for the Ammo Press, molotovs and pipe bombs, refined chemicals.
  chemlab(b, L, I) {
    const rnd = seeded(1000 + L)
    if (L === 1) {
      // a folding table of jars and a camp stove, drums, a mortar of powder
      canopy(b, I, { x: -0.5, z: -1.1, w: 2.6, d: 1.4, hf: 2.2, hb: 1.85, color: '#7a7a4a', seed: 39 })
      table(b, { x: -0.6, z: -1.1, w: 2.0, d: 0.8, h: 0.78, color: '#a8a090', mat: 'metal' })
      glassSet(b, { x: -1.0, y: 0.78, z: -1.15, n: 6, stand: true, seed: 2 })
      campStove(b, I, { x: 0.15, y: 0.78, z: -1.15, flame: true })
      b.sphere(0.09, { mat: 'glass', color: '#e8f0f0', x: 0.27, y: 1.0, z: -1.15 })
      b.sphere(0.075, { mat: 'glowGreen', color: '#88ff40', x: 0.27, y: 0.98, z: -1.15, ws: 10, hs: 6 })
      b.cyl(0.02, 0.02, 0.15, { mat: 'glass', color: '#e8f0f0', x: 0.27, y: 1.14, z: -1.15, seg: 8 })
      for (let i = 0; i < 3; i++) drum(b, { x: 1.4 + (i % 2) * 0.62, z: -1.4 + Math.floor(i / 2) * 0.62, color: pick(rnd, ['#2a5a8a', '#c8a020', '#3a6a3a']), hazard: pick(rnd, ['flame', 'toxic', 'corrosive']) })
      // gunpowder: mortar and pestle with heaps of charcoal, sulfur, saltpetre
      table(b, { x: 0.5, z: 0.4, w: 1.2, d: 0.6, h: 0.75, color: '#b8a080' })
      b.lathe([[0.1, 0], [0.13, 0.05], [0.14, 0.12], [0.11, 0.13], [0.06, 0.08]], { mat: 'concrete', color: '#c8c4bc', x: 0.3, y: 0.75, z: 0.4, seg: 14 })
      b.beam([0.3, 0.8, 0.4], [0.38, 0.97, 0.45], 0.035, 0.035, { mat: 'concrete', color: '#c8c4bc', round: true })
      for (const [x, c] of [[0.6, '#1a1a1a'], [0.78, '#e8d040'], [0.95, '#e8e8e0']]) b.cone(0.07, 0.07, { mat: 'gravel', color: c, x, y: 0.785, z: 0.38, seg: 10 })
      // molotovs: a crate of bottles with rags
      crate(b, { x: -1.6, z: 0.6, w: 0.6, h: 0.32, d: 0.45, lid: false, color: '#a89878' })
      for (let i = 0; i < 6; i++) {
        const x = -1.78 + (i % 3) * 0.17
        const z = 0.5 + Math.floor(i / 3) * 0.18
        b.lathe([[0.035, 0], [0.035, 0.16], [0.012, 0.22], [0.012, 0.26]], { mat: 'glass', color: pick(rnd, ['#4a7a3a', '#7a4a1a', '#c8d8d0']), x, y: 0.2, z, seg: 10 })
        b.box(0.03, 0.08, 0.02, { mat: 'cloth', color: '#c8b898', x, y: 0.5, z, rz: 0.4 })
      }
      for (let i = 0; i < 2; i++) jerrycan(b, { x: -0.6 + i * 0.25, z: 0.65, ry: 0.3, color: '#a8382a' })
      bucket(b, { x: 1.5, z: 0.6 })
      lantern(b, { x: -0.6, y: 1.85, z: -0.8 })
      I.lights.push({ x: -0.6, y: 1.95, z: -0.8, color: '#ffb060', intensity: 2.6, dist: 7, when: 'night' })
      I.emitters.push({ kind: 'steam', x: 0.27, y: 1.25, z: -1.15, when: 'active' })
      I.spots.push({ x: -0.4, z: -0.45, face: FACE_BACK, anim: 'stir' }, { x: 0.5, z: 1.0, face: FACE_BACK, anim: 'stir' })
      return
    }
    if (L === 2) {
      // a lab shed: fume hood, glassware bench, a ball mill for powder, drums
      timberShed(b, I, { w: 4.9, d: 3.9, seed: 81, leftOpen: 1.6 })
      fumeHood(b, { x: -1.6, z: -1.5, w: 1.2, duct: 0.6 })
      glassSet(b, { x: -1.75, y: 0.88, z: -1.5, n: 3, stand: false, seed: 5 })
      const by = heavyBench(b, { x: 0.45, z: -1.55, w: 2.2, d: 0.7, drawers: 2, seed: 14, color: '#c8c0b0' })
      glassSet(b, { x: 0.15, y: by, z: -1.55, n: 6, stand: true, seed: 6 })
      shelf(b, { x: 1.95, z: -1.6, w: 0.8, h: 1.9, d: 0.4, seed: 49, kind: 'chem', fill: 0.95 })
      // ball mill: a drum spinning on rollers, driven by a motor
      b.at({ x: 0.4, z: 0.2 }, () => {
        b.box(1.2, 0.08, 0.6, { mat: 'paint', color: '#4a4e52', y: 0.3 })
        for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.06, 0.3, 0.06, { mat: 'paint', color: '#4a4e52', x: sx * 0.55, y: 0.15, z: sz * 0.25 })
        for (const z of [-0.12, 0.12]) b.cyl(0.03, 0.03, 1.1, { mat: 'chrome', color: '#b8bcc0', y: 0.38, z, rz: Math.PI / 2, seg: 8 })
        b.pivot('mill', { y: 0.52 }, (p) => {
          p.cyl(0.16, 0.16, 0.7, { mat: 'rubber', color: '#2a2a2a', rz: Math.PI / 2, seg: 18 })
          for (const x of [-0.25, 0.25]) p.torus(0.165, 0.012, { mat: 'metal', color: '#8a8e92', x, ry: Math.PI / 2, rs: 4, ts2: 20 })
          p.box(0.05, 0.04, 0.3, { mat: 'metal', color: '#8a8e92', y: 0.16 })
        })
        b.cyl(0.1, 0.1, 0.22, { mat: 'paint', color: '#2a5a8a', x: 0.75, y: 0.25, rz: Math.PI / 2, seg: 12 })
      })
      I.anims.push({ name: 'mill', kind: 'spin', axis: 'x', speed: 3, when: 'active' })
      for (let i = 0; i < 3; i++) drum(b, { x: -1.9 + i * 0.62, z: 1.35, color: pick(rnd, ['#2a5a8a', '#c8a020', '#3a6a3a', '#8a2a22']), hazard: pick(rnd, ['flame', 'toxic', 'corrosive']) })
      gasBottle(b, { x: 2.05, z: 0.3, color: '#d8d8d0' })
      gasBottle(b, { x: 2.05, z: 0.6, color: '#2a5a8a' })
      // eyewash station
      b.cyl(0.03, 0.03, 1.1, { mat: 'paint', color: '#2a8a3a', x: -0.6, y: 0.55, z: -1.75, seg: 8 })
      b.cyl(0.12, 0.12, 0.05, { mat: 'paint', color: '#2a8a3a', x: -0.6, y: 1.12, z: -1.6, seg: 12 })
      hangBulb(b, I, 0.3, 2.3, -0.8)
      decal(b, plateMat('TOXIC FUMES', '#e8c020', '#1a1a1a', 'warn'), 0.44, 0.22, { x: 0.6, y: 2.0, z: -1.86 })
      I.emitters.push({ kind: 'steam', x: -1.6, y: 3.0, z: -1.5, when: 'active' })
      I.spots.push({ x: -1.6, z: -0.9, face: FACE_BACK, anim: 'stir' }, { x: 0.45, z: -0.9, face: FACE_BACK, anim: 'stir' })
      return
    }
    // L3: a small chemical plant: two stirred reactors, a still column,
    // pumps, valves and pipe runs, drums on spill pallets
    const W = 4.9
    const D = 3.9
    slab(b, I, { w: W + 0.05, d: D + 0.05, h: 0.12, seed: 21 })
    stains(b, rnd, -2.0, 2.0, -1.6, 1.6, 4, 0.102)
    steelShed(b, I, { w: W, d: D, h: 3.2, hb: 2.9, color: '#4a5048' })
    const rt = reactor(b, I, { x: -1.55, z: -1.15, r: 0.5, h: 1.2, name: 'fanA', label: 'NITRATION' })
    reactor(b, I, { x: -0.3, z: -1.2, r: 0.42, h: 1.05, name: 'fanB', motor: '#8a2a22', label: 'SOLVENT' })
    // distillation column with a ladder
    b.at({ x: 1.0, z: -1.3 }, () => {
      b.cyl(0.22, 0.22, 2.6, { mat: 'metal', color: '#a8acb0', y: 1.35, seg: 18 })
      b.sphere(0.22, { mat: 'metal', color: '#a8acb0', y: 2.65, tl: Math.PI / 2, ws: 18, hs: 6 })
      for (let i = 0; i < 6; i++) b.torus(0.225, 0.012, { mat: 'metal', color: '#8a8e92', y: 0.3 + i * 0.4, rx: Math.PI / 2, rs: 4, ts2: 24 })
      for (const s of [-1, 1]) b.box(0.03, 2.4, 0.03, { mat: 'paint', color: '#d8a020', x: s * 0.15, y: 1.2, z: 0.3 })
      for (let i = 0; i < 9; i++) b.box(0.3, 0.025, 0.025, { mat: 'paint', color: '#d8a020', y: 0.2 + i * 0.26, z: 0.3 })
    })
    pipeRun(b, [[-1.55, rt + 0.1, -1.15], [-1.55, 2.3, -1.15], [1.0, 2.3, -1.15], [1.0, 2.3, -1.3]], 0.045)
    pipeRun(b, [[-0.3, 0.9, -0.78], [-0.3, 0.3, -0.4], [0.45, 0.3, -0.4], [0.45, 0.3, -0.75]], 0.04, { color: '#3a6a9a' })
    pump(b, { x: 0.45, z: -0.6, ry: Math.PI / 2 })
    for (const [x, y, z] of [[-1.55, 2.0, -1.15], [0.2, 2.3, -1.15], [-0.3, 0.6, -0.55]]) valveWheel(b, { x, y, z: z + 0.08 })
    gauge(b, { x: -0.9, y: 1.4, z: -0.72 })
    // drums on spill pallets, hazmat locker
    for (let i = 0; i < 4; i++) {
      if (i % 2 === 0) b.box(1.3, 0.15, 0.7, { mat: 'plastic', color: '#d8a020', x: -1.6 + (i / 2) * 1.4, y: 0.075, z: 1.3 })
      drum(b, { x: -1.9 + (i % 2) * 0.62 + Math.floor(i / 2) * 1.4, y: 0.15, z: 1.3, color: pick(rnd, ['#2a5a8a', '#c8a020', '#3a6a3a', '#8a2a22']), hazard: pick(rnd, ['flame', 'toxic', 'corrosive']) })
    }
    b.box(0.9, 1.8, 0.5, { mat: 'paint', color: '#d8a020', x: 1.9, y: 0.9, z: 0.2 })
    for (const s of [-1, 1]) b.box(0.43, 1.7, 0.01, { mat: 'paint', color: '#e0b030', x: 1.9 + s * 0.22, y: 0.9, z: 0.456 })
    decal(b, plateMat('FLAMMABLE', '#d8a020', '#c8201a', 'flame'), 0.5, 0.25, { x: 1.9, y: 1.4, z: 0.462 })
    controlPanel(b, { x: 0.3, z: 0.4, ry: 0.2 })
    beacon(b, I, { x: 1.0, y: 2.9, z: -1.3 })
    b.plane(W - 0.4, 0.07, { material: stripeMat(), x: 0, y: 0.102, z: 0.75, rx: -Math.PI / 2, shadow: false })
    for (const x of [-1.0, 1.2]) fluoroLight(b, { x, y: 2.5, z: -0.2, len: 1.2, chain: 0.4 })
    I.lights.push({ x: -1.0, y: 2.45, z: -0.2, color: '#e8f0ff', intensity: 3.2, dist: 8, when: 'night' }, { x: 1.2, y: 2.45, z: -0.2, color: '#e8f0ff', intensity: 3.2, dist: 8, when: 'night' })
    I.emitters.push({ kind: 'steam', x: 1.0, y: 2.9, z: -1.3, when: 'active' }, { kind: 'steam', x: -1.55, y: 2.0, z: -0.9, when: 'active' })
    I.spots.push({ x: 0.3, z: 0.95, face: FACE_BACK, anim: 'type' }, { x: -1.0, z: -0.45, face: FACE_BACK, anim: 'stir' })
  },


  // ============================================================ FARM PLOT
  // Grows food from water. Crops sit in 'crop<n>' pivots that grow with the cycle.
  farm(b, L, I) {
    const rnd = seeded(1100 + L)
    const crop = (name, kind, x, z, len, y = 0.12) => {
      b.pivot(name, { x, y, z }, (p) => cropRowHD(p, { kind, len, seed: Math.floor(rnd() * 999) }))
      I.anims.push({ name, kind: 'grow', when: 'always' })
    }
    if (L === 1) {
      // a dug garden: mounded rows, a compost heap, a scarecrow made of junk
      b.box(5.8, 0.03, 5.6, { mat: 'dirt', color: '#6a5442', y: 0.0 })
      const kinds = ['cabbage', 'potato', 'tomato', 'carrot']
      for (let i = 0; i < 4; i++) {
        const z = -2.1 + i * 1.25
        soilRow(b, 4.6, 0.7, { x: -0.3, z })
        crop('crop' + i, kinds[i], -0.3, z, 4.4, 0.1)
        // edging from whatever was lying around
        if (i % 2 === 0) for (const s of [-1, 1]) b.box(4.6, 0.12, 0.04, { mat: 'wood', color: '#a8957a', x: -0.3, y: 0.06, z: z + s * 0.38, ry: (rnd() - 0.5) * 0.02 })
        else for (let k = 0; k < 12; k++) for (const s of [-1, 1]) rock(b, { x: -2.5 + k * 0.4 + (rnd() - 0.5) * 0.1, y: -0.03, z: z + s * 0.4, s: 0.09 + rnd() * 0.04, seed: k * 7 + (s > 0 ? 3 : 0), color: shadeHex('#8a867e', -rnd() * 0.25) })
      }
      // compost heap with a fork in it
      b.sphere(0.65, { mat: 'dirt', color: '#5a4a32', x: 2.45, y: -0.1, z: -2.2, sy: 0.55, ws: 12, hs: 8 })
      for (let i = 0; i < 10; i++) b.box(0.12 + rnd() * 0.15, 0.01, 0.05, { mat: 'leaf', color: pick(rnd, ['#7a8a3a', '#9a8a4a', '#6a7a3a']), x: 2.45 + (rnd() - 0.5) * 0.8, y: 0.25 + rnd() * 0.08, z: -2.2 + (rnd() - 0.5) * 0.8, ry: rnd() * 3 })
      b.cyl(0.015, 0.015, 1.2, { mat: 'wood', color: '#c8a070', x: 2.4, y: 0.75, z: -2.1, rz: 0.3, seg: 6 })
      scarecrow(b, { x: 2.5, z: 1.9, ry: -0.6 })
      // rain barrel, watering can, barrow, tools stuck in the soil
      barrel(b, { x: -2.6, z: -2.55, color: '#2a5a8a', open: true, contents: '#3a5a6a', fill: 0.85 })
      b.at({ x: -2.55, z: 2.55, ry: 0.4 }, () => {
        b.lathe([[0.1, 0], [0.12, 0.2], [0.08, 0.24]], { mat: 'metal', color: '#3a6a4a', seg: 12 })
        b.beam([0.1, 0.18, 0], [0.42, 0.3, 0], 0.025, 0.025, { mat: 'metal', color: '#3a6a4a', round: true })
        b.torus(0.08, 0.012, { mat: 'metal', color: '#3a6a4a', y: 0.24, x: -0.05, rz: Math.PI / 2, arc: Math.PI })
      })
      wheelbarrow(b, { x: 2.3, z: 0.3, ry: -0.8 })
      for (const [x, z, r] of [[1.95, 2.55, 0.15], [-2.0, 2.6, -0.1]]) b.at({ x, z }, () => {
        b.cyl(0.016, 0.016, 1.2, { mat: 'wood', color: '#c8a070', y: 0.6, rz: r, seg: 6 })
        b.box(0.2, 0.25, 0.02, { mat: 'metal', color: '#7a7a74', x: -r * 0.1, y: 0.05, rz: r })
      })
      I.spots.push({ x: -1.4, z: -1.5, face: FACE_BACK, anim: 'hoe' }, { x: 0.8, z: 0.95, face: FACE_BACK, anim: 'hoe' }, { x: -0.6, z: 2.4, face: FACE_BACK, anim: 'search' })
      return
    }
    if (L === 2) {
      // raised beds behind a picket fence, a hen coop, a tool shed, drip lines
      b.box(5.9, 0.03, 5.9, { mat: 'gravel', color: '#a89a88', y: 0.0 })
      const beds = [[-1.35, -1.6, 'corn'], [1.35, -1.6, 'beans'], [-1.35, 0.4, 'cabbage'], [1.35, 0.4, 'tomato']]
      beds.forEach(([x, z, kind], i) => {
        b.at({ x, z }, () => {
          for (const s of [-1, 1]) b.box(2.35, 0.36, 0.06, { mat: 'wood', color: '#a8906a', y: 0.18, z: s * 0.62 })
          for (const s of [-1, 1]) b.box(0.06, 0.36, 1.3, { mat: 'wood', color: '#a8906a', x: s * 1.18, y: 0.18 })
          for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.08, 0.42, 0.08, { mat: 'wood', color: '#8a7050', x: sx * 1.15, y: 0.21, z: sz * 0.6 })
          b.box(2.3, 0.3, 1.2, { mat: 'dirt', color: '#5a4636', y: 0.16 })
          b.cyl(0.008, 0.008, 2.2, { mat: 'rubber', color: '#1a1a1a', y: 0.33, z: 0.25, rz: Math.PI / 2, seg: 4 })
          b.cyl(0.008, 0.008, 2.2, { mat: 'rubber', color: '#1a1a1a', y: 0.33, z: -0.25, rz: Math.PI / 2, seg: 4 })
        })
        crop('crop' + i, kind, x, z, 2.2, 0.31)
      })
      // tote with a hose to the drip lines
      tote(b, { x: 2.3, z: 2.1, ry: -0.2 })
      b.rope([[2.3, 0.2, 1.5], [1.6, 0.05, 1.2], [1.35, 0.33, 0.9]], 0.012, { mat: 'rubber', color: '#2a5a2a', sag: 0.02 })
      // hen coop with a run
      b.at({ x: -2.2, z: 2.1 }, () => {
        for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.07, 0.95, 0.07, { mat: 'wood', color: '#8a7050', x: sx * 0.45, y: 0.47, z: sz * 0.4 })
        b.box(1.0, 0.55, 0.9, { mat: 'planks', color: '#b8584a', y: 0.75 })
        b.wedge(1.1, 0.3, 1.0, { mat: 'roofmetal', color: '#5a5a52', y: 1.02 })
        b.beam([0.2, 0.5, 0.45], [0.45, 0.0, 0.95], 0.25, 0.03, { mat: 'wood', color: '#a8906a' })
        b.box(0.3, 0.32, 0.02, { mat: 'plain', color: '#1a1410', y: 0.7, z: 0.451 })
        for (let i = 0; i < 3; i++) chicken(b, { x: -0.3 + i * 0.4, z: 0.8 + (i % 2) * 0.2, ry: rnd() * TAU, color: pick(rnd, ['#e8dcc8', '#a8582a', '#3a3028']) })
      })
      // tool shed
      b.at({ x: 2.3, z: -0.1 + -2.5 + 0.6 }, () => {
        b.box(1.1, 1.8, 0.9, { mat: 'planks', color: '#7a8a6a', y: 0.9 })
        b.box(1.3, 0.05, 1.1, { mat: 'roofmetal', color: '#6a6a62', y: 1.85, rx: 0.12 })
        b.box(0.6, 1.5, 0.03, { mat: 'plain', color: '#1a1410', x: -0.15, y: 0.78, z: 0.451 })
        b.at({ x: -0.45, y: 0.78, z: 0.46, ry: -1.1 }, () => b.box(0.6, 1.5, 0.04, { mat: 'planks', color: '#7a8a6a', x: 0.3 }))
        for (let i = 0; i < 3; i++) b.cyl(0.015, 0.015, 1.3, { mat: 'wood', color: '#c8a070', x: -0.3 + i * 0.12, y: 0.7, z: 0.3, rz: 0.1, seg: 6 })
      })
      // picket fence with a gap at the front
      for (const [ax, az, bx, bz] of [[-2.9, -2.9, 2.9, -2.9], [-2.9, -2.9, -2.9, 2.9], [2.9, -2.9, 2.9, 2.9], [-2.9, 2.9, -0.6, 2.9], [0.6, 2.9, 2.9, 2.9]]) {
        const len = Math.hypot(bx - ax, bz - az)
        const n = Math.round(len / 0.14)
        for (let i = 0; i <= n; i++) {
          const t = i / n
          b.box(0.06, 0.62, 0.02, { mat: 'paint', color: '#d8d4c8', x: ax + (bx - ax) * t, y: 0.31, z: az + (bz - az) * t, ry: az === bz ? 0 : Math.PI / 2 })
          if (i % 2 === 0) b.cone(0.04, 0.06, { mat: 'paint', color: '#d8d4c8', x: ax + (bx - ax) * t, y: 0.65, z: az + (bz - az) * t, seg: 4 })
        }
        for (const y of [0.18, 0.48]) b.beam([ax, y, az], [bx, y, bz], 0.04, 0.06, { mat: 'paint', color: '#c8c4b8' })
      }
      scarecrow(b, { x: 0.0, z: -0.6, ry: 0.3 })
      I.spots.push({ x: -1.35, z: -0.75, face: FACE_BACK, anim: 'hoe' }, { x: 1.35, z: -0.75, face: FACE_BACK, anim: 'hoe' }, { x: 0.0, z: 1.4, face: FACE_BACK, anim: 'hoe' })
      return
    }
    // L3: a polytunnel with hydroponic tables and grow lights, beds outside,
    // a water tank, a solar pump and a sprinkler
    b.box(5.9, 0.03, 5.9, { mat: 'gravel', color: '#a89a88', y: 0.0 })
    const gz = -1.35
    const gl = 5.4
    const gr = 1.5
    b.box(gl, 0.06, gr * 2, { mat: 'concrete', color: '#b8b4ac', z: gz, y: 0.03 })
    // hoops, purlins and the film (front half of the film left off so you can see in)
    for (let i = 0; i <= 6; i++) {
      const x = -gl / 2 + (i / 6) * gl
      b.torus(gr, 0.025, { mat: 'steel', color: '#c8ccd0', x, y: 0.05, z: gz, ry: Math.PI / 2, arc: Math.PI, rs: 5, ts2: 24 })
    }
    for (const a of [0.5, 1.0, 1.57, 2.1, 2.6]) b.cyl(0.018, 0.018, gl, { mat: 'steel', color: '#c8ccd0', x: 0, y: 0.05 + Math.sin(a) * gr, z: gz - Math.cos(a) * gr, rz: Math.PI / 2, seg: 5 })
    b.cyl(gr + 0.01, gr + 0.01, gl, { mat: 'glass', color: '#e8f0e8', y: 0.05, z: gz, rz: Math.PI / 2, ts: Math.PI / 2, tl: Math.PI * 0.62, open: true, seg: 20 })
    b.cyl(gr + 0.01, gr + 0.01, 0.02, { mat: 'glass', color: '#e8f0e8', x: -gl / 2, y: 0.05, z: gz, rz: Math.PI / 2, ts: 0, tl: Math.PI, seg: 20 })
    // hydroponic tables with greens and grow lights
    for (const z of [gz - 0.55, gz + 0.45]) {
      b.box(4.6, 0.08, 0.7, { mat: 'plastic', color: '#e8e8e0', y: 0.82, z })
      for (const x of [-2.1, -0.7, 0.7, 2.1]) for (const s of [-1, 1]) b.box(0.05, 0.8, 0.05, { mat: 'metal', color: '#8a8e92', x, y: 0.4, z: z + s * 0.3 })
      b.cyl(0.02, 0.02, 4.6, { mat: 'plastic', color: '#d8d8d0', y: 0.88, z: z + 0.36, rz: Math.PI / 2, seg: 6 })
      const name = 'cropg' + (z < gz ? 0 : 1)
      b.pivot(name, { y: 0.86, z }, (p) => {
        for (const dz of [-0.18, 0.18]) cropRowHD(p, { kind: 'lettuce', len: 4.4, z: dz, seed: Math.floor(rnd() * 999) })
      })
      I.anims.push({ name, kind: 'grow', when: 'always' })
      fluoroLight(b, { x: 0, y: 1.9, z, len: 4.0, chain: 0.15, glow: 'glowPurple' })
    }
    I.lights.push({ x: 0, y: 1.7, z: gz, color: '#c070ff', intensity: 4, dist: 7, when: 'night' })
    // beds outside
    const beds = [[-1.4, 1.35, 'corn'], [1.4, 1.35, 'pumpkin']]
    beds.forEach(([x, z, kind], i) => {
      b.at({ x, z }, () => {
        for (const s of [-1, 1]) b.box(2.4, 0.4, 0.08, { mat: 'concrete', color: '#a8a49c', y: 0.2, z: s * 0.65 })
        for (const s of [-1, 1]) b.box(0.08, 0.4, 1.38, { mat: 'concrete', color: '#a8a49c', x: s * 1.2, y: 0.2 })
        b.box(2.32, 0.34, 1.22, { mat: 'dirt', color: '#5a4636', y: 0.18 })
      })
      crop('crop' + i, kind, x, z, 2.2, 0.35)
    })
    // water: tank, solar pump, sprinkler
    waterTank(b, { x: 2.35, z: 2.35, r: 0.5, h: 1.4, color: '#3a6a4a' })
    b.at({ x: 0, z: 2.55 }, () => {
      b.cyl(0.03, 0.03, 0.5, { mat: 'metal', color: '#8a8e92', y: 0.25, seg: 6 })
      b.pivot('sprinkler', { y: 0.52 }, (p) => {
        p.box(0.5, 0.025, 0.025, { mat: 'metal', color: '#c8ccd0' })
        for (const s of [-1, 1]) p.cyl(0.015, 0.015, 0.06, { mat: 'metal', color: '#c8ccd0', x: s * 0.25, y: 0.02, seg: 6 })
      })
    })
    I.anims.push({ name: 'sprinkler', kind: 'spin', speed: 2.5, when: 'active' })
    I.emitters.push({ kind: 'mist', x: 0, y: 0.7, z: 2.55, when: 'active' })
    pump(b, { x: 1.6, z: 2.55, ry: Math.PI })
    b.at({ x: -2.3, z: 2.4 }, () => {
      b.cyl(0.04, 0.04, 1.6, { mat: 'metal', color: '#8a8e92', y: 0.8, seg: 6 })
      b.at({ y: 1.65, rx: -0.6 }, () => {
        b.box(0.9, 0.04, 0.6, { mat: 'steel', color: '#b8bcc0' })
        b.plane(0.86, 0.56, { material: solarMatX(), y: 0.021, rx: -Math.PI / 2 })
      })
    })
    cable(b, [[-2.3, 1.3, 2.4], [-0.5, 0.05, 2.7], [1.5, 0.3, 2.55]], { sag: 0.02, r: 0.008 })
    pipeRun(b, [[2.35, 0.2, 1.9], [2.35, 0.2, 1.5], [1.6, 0.2, 1.5]], 0.025, { color: '#2a5a3a', flanges: false })
    I.spots.push({ x: -1.2, z: gz + 0.0, face: Math.PI / 2, anim: 'hoe' }, { x: -1.4, z: 2.2, face: FACE_BACK, anim: 'hoe' }, { x: 1.4, z: 2.2, face: FACE_BACK, anim: 'hoe' })
  },

  // ============================================================ RAIN COLLECTOR
  collector(b, L, I) {
    const rnd = seeded(1200 + L)
    if (L === 1) {
      // a tarp funnel on four poles draining into a barrel
      for (const [x, z] of [[-1.2, -1.2], [1.2, -1.2], [1.2, 1.2], [-1.2, 1.2]]) pole(b, x, z, 1.9, { r: 0.045 })
      roof(b, I, (r) => r.cloth(2.5, 2.5, { mat: 'canvas', color: '#3f74b0', corners: [1.85, 1.85, 1.8, 1.8], sag: 0.65, seed: 5, segX: 12, segZ: 12, y: 0 }))
      b.cyl(0.05, 0.05, 0.3, { mat: 'plastic', color: '#2a4a7a', y: 1.05, seg: 8 })
      barrel(b, { x: 0, z: 0, color: '#2a5a8a', open: true, contents: '#3a5a6a', fill: 0.8 })
      barrel(b, { x: 0.85, z: 0.75, color: '#3a6a4a', open: true, contents: '#3a5a6a', fill: 0.5 })
      bucket(b, { x: -0.7, z: 0.8, water: true })
      jerrycan(b, { x: -0.8, z: -0.3, ry: 0.5, color: '#2a5a8a' })
      I.spots.push({ x: 0.5, z: 0.6, face: FACE_BACK, anim: 'search' })
      return
    }
    if (L === 2) {
      // a little catchment roof with gutters feeding two totes
      b.box(2.8, 0.4, 1.5, { mat: 'planks', color: '#a8906a', y: 0.2, z: -0.4 })
      for (const x of [-0.7, 0.7]) tote(b, { x, y: 0.4, z: -0.4, level: 0.7 })
      for (const [x, z, h] of [[-1.35, -1.25, 2.9], [1.35, -1.25, 2.9], [1.35, 1.1, 2.5], [-1.35, 1.1, 2.5]]) b.box(0.09, h, 0.09, { mat: 'wood', color: '#9a8466', x, y: h / 2, z })
      roof(b, I, (r) => corrRoof(r, { w: 2.9, d: 2.5, y: 2.95, drop: 0.45, z: -0.1, gutter: false, seed: 9 }))
      b.cyl(0.07, 0.07, 2.9, { mat: 'paint', color: '#8a9094', y: 2.42, z: 1.18, rz: Math.PI / 2, ts: 0, tl: Math.PI, open: true, seg: 10 })
      for (const x of [-0.7, 0.7]) pipeRun(b, [[x, 2.4, 1.2], [x, 1.6, 0.6], [x, 1.45, 0.0]], 0.035, { color: '#8a9094', flanges: false })
      for (const x of [-0.7, 0.7]) bucket(b, { x, z: 0.55 })
      I.spots.push({ x: 0.0, z: 0.9, face: FACE_BACK, anim: 'search' })
      return
    }
    // L3: a steel water tower with a catchment funnel
    const H = 1.7
    for (const [x, z] of [[-0.9, -0.9], [0.9, -0.9], [0.9, 0.9], [-0.9, 0.9]]) {
      b.box(0.1, H, 0.1, { mat: 'paint', color: '#4a5056', x, y: H / 2, z })
      b.box(0.3, 0.06, 0.3, { mat: 'concrete', color: '#a8a49c', x, y: 0.03, z })
    }
    for (const y of [0.6, H]) for (const [ax, az, bx, bz] of [[-0.9, -0.9, 0.9, -0.9], [0.9, -0.9, 0.9, 0.9], [0.9, 0.9, -0.9, 0.9], [-0.9, 0.9, -0.9, -0.9]]) b.beam([ax, y, az], [bx, y, bz], 0.06, 0.06, { mat: 'paint', color: '#4a5056' })
    for (const [ax, az, bx, bz] of [[-0.9, -0.9, 0.9, -0.9], [0.9, 0.9, -0.9, 0.9]]) b.beam([ax, 0.6, az], [bx, H, bz], 0.04, 0.04, { mat: 'paint', color: '#4a5056' })
    b.cyl(1.05, 1.05, 1.5, { mat: 'corrugated', color: '#a8b0b4', y: H + 0.75, seg: 28 })
    for (const y of [H + 0.02, H + 1.48]) b.torus(1.06, 0.03, { mat: 'metal', color: '#7a7e82', y, rx: Math.PI / 2, rs: 5, ts2: 32 })
    roof(b, I, (r) => {
      r.lathe([[0.15, H + 1.5], [1.6, H + 2.1], [1.62, H + 2.14]], { mat: 'corrugated', color: '#9aa0a4', seg: 24 })
      r.lathe([[1.6, H + 2.12], [0.16, H + 1.52]], { mat: 'metal', color: '#6a6e72', seg: 24 })
    })
    b.cyl(0.15, 0.15, 0.1, { mat: 'metal', color: '#3a3a3a', y: H + 1.52, seg: 12 })
    // level gauge, ladder, outlet, overflow and a tap stand
    b.cyl(0.02, 0.02, 1.3, { mat: 'glass', color: '#a8c8d8', x: 0.0, y: H + 0.75, z: 1.08, seg: 6 })
    b.box(0.04, 0.7, 0.012, { mat: 'paint', color: '#e8e4d8', x: 0.05, y: H + 0.5, z: 1.075 })
    ladder(b, { x: 0.65, z: 1.05, h: H + 1.6, lean: 0.0, mat: 'metal', color: '#6a6e72' })
    pipeRun(b, [[-0.4, H, 0.0], [-0.4, 0.5, 0.0], [-0.4, 0.5, 1.2]], 0.045, { color: '#3a5a8a' })
    b.cyl(0.05, 0.05, 0.9, { mat: 'paint', color: '#3a5a8a', x: -0.4, y: 0.45, z: 1.25, seg: 10 })
    valveWheel(b, { x: -0.4, y: 0.75, z: 1.33, r: 0.07 })
    bucket(b, { x: -0.4, z: 1.38 })
    I.spots.push({ x: -0.4, z: 1.75, face: FACE_BACK, anim: 'search' })
  },

  // ============================================================ WATER FILTER
  filter(b, L, I) {
    const rnd = seeded(1300 + L)
    if (L === 1) {
      // a stone well with a hand pump, a bucket filter stack, a boiling pot
      b.at({ x: -1.0, z: -0.4 }, () => {
        for (let i = 0; i < 14; i++) {
          const a = (i / 14) * TAU
          for (let k = 0; k < 2; k++) rock(b, { x: Math.cos(a) * 0.62, y: k * 0.2, z: Math.sin(a) * 0.62, s: 0.17, seed: 50 + i + k * 20, color: shadeHex('#9a948a', -rnd() * 0.2) })
        }
        b.cyl(0.55, 0.55, 0.32, { mat: 'concrete', color: '#8a867e', y: 0.2, seg: 16 })
        b.cyl(0.5, 0.5, 0.02, { mat: 'water', color: '#2a3a40', y: 0.3, seg: 16 })
        b.box(1.2, 0.08, 0.3, { mat: 'wood', color: '#8a7050', y: 0.44 })
        // the pump
        b.cyl(0.06, 0.07, 0.8, { mat: 'paint', color: '#2a4a3a', y: 0.85, seg: 10 })
        b.cyl(0.03, 0.03, 0.3, { mat: 'paint', color: '#2a4a3a', y: 0.95, z: 0.18, rx: Math.PI / 2 - 0.4, seg: 8 })
        b.pivot('handle', { y: 1.22, z: -0.04 }, (p) => {
          p.beam([0, 0, 0], [0, 0.18, -0.55], 0.03, 0.03, { mat: 'paint', color: '#2a4a3a', round: true })
          p.sphere(0.035, { mat: 'paint', color: '#2a4a3a', y: 0.18, z: -0.55 })
        })
        I.anims.push({ name: 'handle', kind: 'pump', axis: 'x', amp: 0.35, speed: 4, when: 'active' })
        bucket(b, { y: 0.48, z: 0.3, water: true })
      })
      // stacked bucket filter: gravel, sand, charcoal
      b.at({ x: 0.6, z: -0.6 }, () => {
        b.box(0.5, 0.5, 0.5, { mat: 'wood', color: '#a89070', y: 0.25 })
        for (let i = 0; i < 3; i++) {
          bucket(b, { y: 0.5 + i * 0.26, color: ['#c8302a', '#d8a020', '#2a5a8a'][i] })
          b.cyl(0.115, 0.115, 0.01, { mat: 'gravel', color: ['#2a2a2a', '#d8c898', '#8a8478'][i], y: 0.5 + i * 0.26 + 0.24, seg: 12 })
        }
      })
      b.at({ x: 1.35, z: 0.45 }, () => {
        for (let i = 0; i < 8; i++) {
          const a = (i / 8) * TAU
          rock(b, { x: Math.cos(a) * 0.32, z: Math.sin(a) * 0.32, s: 0.11, seed: 80 + i })
        }
        for (let i = 0; i < 5; i++) b.beam([Math.cos(i) * 0.25, 0.03, Math.sin(i) * 0.25], [0, 0.25, 0], 0.06, 0.06, { mat: 'bark', color: '#5a4a3a', round: true })
        for (let i = 0; i < 3; i++) {
          const a = (i / 3) * TAU
          b.beam([Math.cos(a) * 0.4, 0, Math.sin(a) * 0.4], [0, 0.9, 0], 0.03, 0.03, { mat: 'metal', color: '#3a3a3a', round: true })
        }
        b.lathe([[0.15, 0], [0.18, 0.04], [0.19, 0.26], [0.18, 0.28]], { mat: 'metal', color: '#4a4a48', y: 0.45, seg: 14 })
        b.rope([[0, 0.9, 0], [0, 0.74, 0]], 0.006, { mat: 'steel', color: '#3a3a3a', sag: 0, steps: 2 })
      })
      I.flames.push({ x: 1.35, y: 0.05, z: 0.45, w: 0.4, h: 0.4, when: 'active' })
      I.emitters.push({ kind: 'steam', x: 1.35, y: 0.8, z: 0.45, when: 'active' })
      for (let i = 0; i < 3; i++) jerrycan(b, { x: -0.1 + i * 0.25, z: 0.7, ry: 0.2, color: '#2a5a8a' })
      I.spots.push({ x: -1.0, z: 0.55, face: FACE_BACK, anim: 'pump' }, { x: 1.0, z: 1.0, face: -2.4, anim: 'stir' })
      return
    }
    if (L === 2) {
      // a barrel cascade sand filter fed by a hand pump
      b.box(3.6, 0.08, 2.4, { mat: 'planks', color: '#a8906a', y: 0.04, z: -0.2 })
      const steps = [[-1.1, 0.9], [0.0, 0.5], [1.1, 0.1]]
      steps.forEach(([x, y], i) => {
        b.box(0.75, y, 0.75, { mat: 'wood', color: '#9a8466', x, y: y / 2 + 0.08, z: -0.7 })
        barrel(b, { x, y: y + 0.08, z: -0.7, color: ['#2a5a8a', '#2a5a8a', '#3a6a9a'][i], open: i === 2, contents: '#4a6a7a' })
        b.plane(0.3, 0.15, { material: plateMat(['GRAVEL', 'SAND', 'CHARCOAL'][i], '#e8e4d8', '#1a1a1a'), x, y: y + 0.55, z: -0.405, shadow: false })
        if (i < 2) {
          const yn = steps[i + 1][1]
          pipeRun(b, [[x + 0.28, y + 0.3, -0.7], [x + 0.5, y + 0.3, -0.7], [x + 0.5, yn + 1.1, -0.7], [x + 1.1, yn + 1.1, -0.7], [x + 1.1, yn + 0.98, -0.7]], 0.025, { color: '#d8d8d0', flanges: false })
        }
      })
      // hand pump on a casing
      b.at({ x: -1.4, z: 0.6 }, () => {
        b.cyl(0.12, 0.12, 0.3, { mat: 'concrete', color: '#8a867e', y: 0.15, seg: 12 })
        b.cyl(0.06, 0.07, 0.9, { mat: 'paint', color: '#2a4a8a', y: 0.75, seg: 10 })
        b.pivot('handle', { y: 1.12 }, (p) => p.beam([0, 0, 0], [0, 0.18, -0.55], 0.03, 0.03, { mat: 'paint', color: '#2a4a8a', round: true }))
        I.anims.push({ name: 'handle', kind: 'pump', axis: 'x', amp: 0.35, speed: 4, when: 'active' })
      })
      cable(b, [[-1.4, 1.0, 0.6], [-1.3, 0.1, 0.0], [-1.1, 1.2, -0.6]], { r: 0.02, color: '#2a5a2a', sag: 0.1 })
      // clean water barrel with a tap
      barrel(b, { x: 1.3, z: 0.65, color: '#3a8a6a' })
      b.cyl(0.02, 0.02, 0.12, { mat: 'chrome', color: '#c8ccd0', x: 1.3, y: 0.2, z: 0.98, rx: Math.PI / 2, seg: 6 })
      bucket(b, { x: 1.3, z: 1.1, water: true })
      decal(b, plateMat('DRINKING WATER', '#2a5a8a', '#e8e4d8'), 0.4, 0.2, { x: 1.3, y: 0.62, z: 0.945 })
      I.spots.push({ x: -1.4, z: 1.15, face: FACE_BACK, anim: 'pump' }, { x: 0.6, z: 0.4, face: FACE_BACK, anim: 'search' })
      return
    }
    // L3: three pressure filters, a UV unit, a pump skid and a clean tank
    slab(b, I, { h: 0.12, seed: 23 })
    for (let i = 0; i < 3; i++) {
      const x = -1.3 + i * 0.75
      b.lathe([[0.001, 0.12], [0.24, 0.16], [0.3, 0.3], [0.3, 1.45], [0.24, 1.6], [0.08, 1.66], [0.001, 1.66]], { mat: 'paint', color: '#2a5a9a', x, z: -0.8, seg: 18 })
      for (let k = 0; k < 3; k++) {
        const a = (k / 3) * TAU
        b.box(0.05, 0.3, 0.05, { mat: 'paint', color: '#3a3e42', x: x + Math.cos(a) * 0.22, y: 0.27, z: -0.8 + Math.sin(a) * 0.22 })
      }
      gauge(b, { x, y: 1.75, z: -0.8 })
      b.cyl(0.03, 0.03, 0.12, { mat: 'metal', color: '#8a8e92', x, y: 1.7, z: -0.8, seg: 8 })
    }
    pipeRun(b, [[-1.6, 1.25, -0.42], [0.4, 1.25, -0.42]], 0.04, { color: '#c8ccd0' })
    pipeRun(b, [[-1.6, 0.45, -0.42], [0.4, 0.45, -0.42], [0.8, 0.45, -0.42]], 0.04, { color: '#c8ccd0' })
    for (let i = 0; i < 3; i++) {
      const x = -1.3 + i * 0.75
      for (const y of [1.25, 0.45]) {
        b.beam([x, y, -0.42], [x, y, -0.55], 0.06, 0.06, { mat: 'metal', color: '#c8ccd0', round: true })
        valveWheel(b, { x: x + 0.12, y, z: -0.36, r: 0.06, color: i === 1 ? '#2a8a3a' : '#c83a2a' })
      }
    }
    // UV steriliser and pump skid
    b.at({ x: 1.0, z: -0.45 }, () => {
      b.box(0.5, 0.9, 0.35, { mat: 'paint', color: '#d8dcd8', y: 0.57 })
      b.box(0.3, 0.12, 0.01, { mat: 'glowBlue', color: '#1a3a6a', y: 0.8, z: 0.176 })
      decal(b, plateMat('UV', '#d8dcd8', '#2a4a8a'), 0.16, 0.08, { y: 0.55, z: 0.177 })
    })
    pump(b, { x: 0.9, z: 0.45, ry: Math.PI / 2 })
    // clean water tank
    b.cyl(0.6, 0.6, 1.4, { mat: 'plastic', color: '#3a6a5a', x: -1.3, y: 0.82, z: 0.75, seg: 20 })
    for (let k = 0; k < 4; k++) b.torus(0.605, 0.015, { mat: 'plastic', color: '#2e5a4a', x: -1.3, y: 0.35 + k * 0.32, z: 0.75, rx: Math.PI / 2, rs: 4, ts2: 24 })
    b.cyl(0.18, 0.18, 0.06, { mat: 'plastic', color: '#2e5a4a', x: -1.3, y: 1.55, z: 0.75, seg: 12 })
    pipeRun(b, [[0.9, 0.3, 0.75], [0.9, 0.3, 1.1], [-0.7, 0.3, 1.1], [-0.7, 0.3, 0.75]], 0.035, { color: '#3a6a9a', flanges: false })
    controlPanel(b, { x: 1.5, z: 1.05, ry: -0.5 })
    I.emitters.push({ kind: 'mist', x: -0.5, y: 0.3, z: -0.42, when: 'active' })
    I.spots.push({ x: 0.0, z: 0.0, face: FACE_BACK, anim: 'type' }, { x: 1.2, z: 1.3, face: -2.5, anim: 'type' })
  },

  // ============================================================ LUMBER YARD
  lumber(b, L, I) {
    const rnd = seeded(1400 + L)
    if (L === 1) {
      // a log pile, a sawbuck with a bow saw, the chopping block, split wood
      shavings(b, rnd, -2.5, 2.5, -2.0, 2.0, 16)
      logPile(b, { x: -1.6, z: -1.4, len: 2.4, n: 9, seed: 3 })
      for (const x of [-2.85, -0.35]) for (const z of [-1.85, -0.95]) b.cyl(0.04, 0.05, 1.0, { mat: 'wood', color: '#8a7050', x, y: 0.5, z, seg: 6 })
      // sawbuck: two X frames and a log, a bow saw biting into it
      b.at({ x: 0.9, z: -1.3 }, () => {
        for (const x of [-0.5, 0.5]) for (const s of [-1, 1]) b.beam([x, 0, s * 0.35], [x, 0.95, -s * 0.35], 0.07, 0.07, { mat: 'wood', color: '#a89070' })
        b.box(1.1, 0.06, 0.06, { mat: 'wood', color: '#a89070', y: 0.25 })
        logP(b, { y: 0.62, len: 2.0, r: 0.17 })
        b.at({ x: 0.2, y: 0.82, z: 0.02 }, () => {
          b.torus(0.32, 0.016, { mat: 'paint', color: '#c8302a', arc: Math.PI, rs: 5, ts2: 16 })
          b.box(0.64, 0.04, 0.004, { mat: 'chrome', color: '#c8ccd0', y: -0.0 })
        })
      })
      stump(b, { x: -0.3, z: 0.6, r: 0.3, h: 0.45 })
      b.at({ x: -0.25, y: 0.45, z: 0.62, rz: 0.55 }, () => {
        b.cyl(0.02, 0.024, 0.7, { mat: 'wood', color: '#b8844a', y: 0.3, seg: 8 })
        b.box(0.025, 0.14, 0.2, { mat: 'metal', color: '#6a6e72', y: 0.0, z: 0.05 })
      })
      for (let i = 0; i < 6; i++) b.cyl(0.08, 0.08, 0.35, { mat: 'wood', color: '#c8a878', x: -0.3 + (rnd() - 0.5) * 1.2, y: 0.08, z: 1.0 + (rnd() - 0.5) * 0.6, rz: Math.PI / 2, ry: rnd() * 3, seg: 3, ts: 0, tl: Math.PI })
      firewood(b, { x: 1.7, z: 1.3, ry: Math.PI / 2, len: 1.6, h: 1.0 })
      wheelbarrow(b, { x: 2.3, z: -0.1, ry: -1.0 })
      I.emitters.push({ kind: 'sawdust', x: 1.1, y: 0.7, z: -1.3, when: 'active' })
      I.spots.push({ x: 1.1, z: -0.65, face: FACE_BACK, anim: 'saw' }, { x: -0.3, z: 1.2, face: FACE_BACK, anim: 'swing' })
      return
    }
    if (L === 2) {
      // an open shed with a table saw, a log deck and racked planks
      b.box(3.4, 0.1, 2.4, { mat: 'planks', color: '#c8b494', x: 0.9, y: 0.05, z: -0.9 })
      frame(b, 3.3, 2.3, 2.6, { hFront: 2.85, braces: true, color: '#9a8466' })
      roof(b, I, (r) => corrRoof(r, { w: 3.7, d: 2.8, y: 2.98, drop: 0.4, x: 0.9, z: -0.9, seed: 12, ry: Math.PI }))
      // table saw
      b.at({ x: 0.9, z: -0.8 }, () => {
        b.box(0.7, 0.75, 0.6, { mat: 'paint', color: '#4a6a5a', y: 0.375 })
        b.box(1.4, 0.05, 0.9, { mat: 'metal', color: '#9aa0a4', y: 0.775 })
        b.box(0.04, 0.06, 0.9, { mat: 'paint', color: '#d8a020', x: 0.25, y: 0.83 })
        b.pivot('blade', { y: 0.8 }, (p) => {
          p.cyl(0.2, 0.2, 0.004, { mat: 'chrome', color: '#d0d4d8', rx: Math.PI / 2, seg: 20 })
          for (let i = 0; i < 12; i++) p.box(0.02, 0.02, 0.006, { mat: 'chrome', color: '#a8acb0', x: Math.cos((i / 12) * TAU) * 0.2, y: Math.sin((i / 12) * TAU) * 0.2, rz: (i / 12) * TAU })
        })
        b.box(0.12, 0.1, 0.012, { mat: 'paint', color: '#c8302a', x: 0.2, y: 0.45, z: 0.31 })
        b.box(0.8, 0.04, 0.22, { mat: 'wood', color: '#e0c898', x: -0.3, y: 0.82, z: 0.15 })
      })
      I.anims.push({ name: 'blade', kind: 'spin', axis: 'z', speed: 30, when: 'active' })
      // plank racks along the back
      for (const x of [-0.3, 0.9, 2.1]) for (const y of [0.4, 0.9, 1.4]) b.box(0.06, 0.04, 0.55, { mat: 'metal', color: '#3a3a3a', x, y, z: -1.85 })
      for (let i = 0; i < 12; i++) b.box(2.5, 0.04 + rnd() * 0.03, 0.12 + rnd() * 0.08, { mat: 'wood', color: shadeHex('#e0c898', (rnd() - 0.5) * 0.2), x: 0.9, y: 0.45 + Math.floor(i / 4) * 0.5, z: -2.0 + (i % 4) * 0.13 })
      // log deck on skids with a cant hook
      for (const x of [-2.4, -0.6]) b.box(0.15, 0.12, 1.6, { mat: 'wood', color: '#6a5a48', x, y: 0.06, z: 1.1 })
      for (let i = 0; i < 4; i++) logP(b, { x: -1.5, y: 0.3 + (i === 3 ? 0.32 : 0), z: i === 3 ? 0.98 : 0.62 + i * 0.38, len: 2.3, r: 0.17 + rnd() * 0.04 })
      b.beam([-0.4, 0, 1.9], [-0.1, 1.2, 1.7], 0.04, 0.04, { mat: 'wood', color: '#c8a070', round: true })
      for (const x of [0.6, 1.6]) sawhorse(b, { x, z: 1.2, len: 0.6, ry: Math.PI / 2 })
      b.box(1.6, 0.05, 0.25, { mat: 'wood', color: '#e0c898', x: 1.1, y: 0.79, z: 1.2 })
      // sawdust pile by the saw
      b.sphere(0.5, { mat: 'plain', color: '#d8b888', x: 2.2, y: -0.15, z: -0.3, sy: 0.5, ws: 12, hs: 6 })
      shavings(b, rnd, -0.5, 2.6, -0.4, 0.6, 10)
      I.emitters.push({ kind: 'sawdust', x: 0.9, y: 0.95, z: -0.6, when: 'active' })
      I.spots.push({ x: 0.9, z: -0.15, face: FACE_BACK, anim: 'saw' }, { x: -1.5, z: 2.0, face: FACE_BACK, anim: 'carry' }, { x: 1.1, z: 1.75, face: FACE_BACK, anim: 'saw' })
      return
    }
    // L3: a band sawmill on rails; the head runs along the log. Log deck in,
    // a conveyor out to stickered stacks of fresh lumber.
    slab(b, I, { w: 5.9, d: 2.0, h: 0.1, z: -1.4, seed: 25 })
    for (const z of [-1.75, -1.05]) b.box(5.6, 0.1, 0.1, { mat: 'metal', color: '#4a4e52', y: 0.15, z })
    for (let i = 0; i < 6; i++) b.box(0.12, 0.16, 0.9, { mat: 'metal', color: '#3a3a3a', x: -2.5 + i, y: 0.12, z: -1.4 })
    logP(b, { x: -0.2, y: 0.55, z: -1.4, len: 4.2, r: 0.28 })
    b.box(3.0, 0.06, 0.5, { mat: 'wood', color: '#e0c898', x: 1.3, y: 0.78, z: -1.4 })
    b.pivot('head', { x: 0.6, y: 0.2, z: -1.4 }, (p) => {
      for (const s of [-1, 1]) p.box(0.14, 1.5, 0.14, { mat: 'paint', color: '#d8a020', z: s * 0.65, y: 0.75 })
      p.box(0.3, 0.2, 1.6, { mat: 'paint', color: '#d8a020', y: 1.5 })
      for (const s of [-1, 1]) p.cyl(0.38, 0.38, 0.22, { mat: 'paint', color: '#d8a020', y: 1.0, z: s * 0.55, rz: Math.PI / 2, seg: 18 })
      p.box(0.02, 0.06, 1.1, { mat: 'chrome', color: '#d0d4d8', y: 0.68 })
      p.box(0.6, 0.45, 0.5, { mat: 'paint', color: '#3a3a3a', x: -0.4, y: 1.5, z: -0.6 })
      p.cyl(0.04, 0.04, 0.5, { mat: 'metal', color: '#3a3a3a', x: -0.4, y: 1.95, z: -0.75, seg: 8 })
      p.box(0.3, 0.3, 0.12, { mat: 'paint', color: '#2a2a2a', x: 0.0, y: 1.1, z: 0.85 })
    })
    I.anims.push({ name: 'head', kind: 'slide', axis: 'x', amp: 1.4, speed: 0.12, when: 'active' })
    // log deck feeding the mill
    for (const x of [-2.4, -1.0]) b.box(0.15, 0.6, 1.4, { mat: 'metal', color: '#4a4e52', x, y: 0.3, z: -0.1 })
    for (let i = 0; i < 4; i++) logP(b, { x: -1.7, y: 0.78 + (i === 3 ? 0.34 : 0), z: i === 3 ? -0.2 : -0.55 + i * 0.38, len: 2.0, r: 0.18 })
    // out-feed conveyor and stickered stacks
    conveyor(b, I, {
      x: 1.6, z: 0.15, len: 2.2, w: 0.5, h: 0.62, name: 'belt', gap: 0.75, speed: 0.4,
      items: (p, x) => p.box(0.6, 0.05, 0.16, { mat: 'wood', color: '#e0c898', x, y: 0.025 }),
    })
    for (let k = 0; k < 2; k++) b.at({ x: 0.3 + k * 2.0, z: 1.55 }, () => {
      for (let i = 0; i < 5; i++) {
        for (let j = 0; j < 6; j++) b.box(1.6, 0.05, 0.14, { mat: 'wood', color: shadeHex('#e0c898', (rnd() - 0.5) * 0.15), y: 0.12 + i * 0.09, z: -0.4 + j * 0.16 })
        if (i < 4) for (const x of [-0.6, 0, 0.6]) b.box(0.04, 0.04, 0.95, { mat: 'wood', color: '#8a7050', x, y: 0.165 + i * 0.09 })
      }
      for (const x of [-0.6, 0, 0.6]) b.box(0.1, 0.1, 1.0, { mat: 'wood', color: '#6a5a48', x, y: 0.05 })
    })
    b.sphere(0.8, { mat: 'plain', color: '#d8b888', x: 2.4, y: -0.25, z: -1.4, sy: 0.55, ws: 14, hs: 6 })
    controlPanel(b, { x: -0.3, z: -0.2, ry: 0.3 })
    I.emitters.push({ kind: 'sawdust', x: 0.6, y: 0.8, z: -1.4, when: 'active' }, { kind: 'exhaust', x: 0.2, y: 2.4, z: -2.15, when: 'active' })
    I.spots.push({ x: -0.3, z: 0.35, face: FACE_BACK, anim: 'type' }, { x: 2.0, z: 0.75, face: Math.PI / 2, anim: 'carry' }, { x: -1.7, z: 1.1, face: FACE_BACK, anim: 'carry' })
  },

  // ============================================================ SCRAP YARD
  scrapyard(b, L, I) {
    const rnd = seeded(1500 + L)
    if (L === 1) {
      // a wreck to strip, the junk pile, sorted heaps and an oil-drum brazier
      b.box(5.8, 0.02, 4.8, { mat: 'dirt', color: '#6a5a4a', y: 0.0 })
      stains(b, rnd, -2.5, 2.5, -2.0, 2.0, 5)
      mergeModel(b, carModel({ kind: 'sedan', seed: 31, wreck: 0.95, color: '#6a5040' }), { x: -1.1, z: -0.9, ry: 0.35 })
      scrapPile(b, { x: 1.7, z: -1.3, r: 0.9, n: 14, seed: 41 })
      tireStack(b, { x: 2.4, z: 0.6, n: 4 })
      for (let i = 0; i < 5; i++) b.box(0.9 + rnd() * 0.3, 0.012, 0.6 + rnd() * 0.2, { mat: 'corrugated', color: pick(rnd, ['#a8a098', '#9aa4a8', '#b8a890']), x: 0.8, y: 0.02 + i * 0.03, z: 1.4, ry: (rnd() - 0.5) * 0.3 })
      for (let i = 0; i < 10; i++) b.cyl(0.015, 0.015, 1.4 + rnd() * 0.4, { mat: 'rust', color: '#a89080', x: -1.5 + rnd() * 0.3, y: 0.03 + (i % 3) * 0.03, z: 1.5 + (rnd() - 0.5) * 0.3, rz: Math.PI / 2, ry: (rnd() - 0.5) * 0.2, seg: 5 })
      // the brazier and a sledgehammer
      drum(b, { x: 0.3, z: 0.3, color: '#4a3a2e' })
      b.cyl(0.27, 0.27, 0.02, { mat: 'embers', color: '#ffffff', x: 0.3, y: 0.86, z: 0.3, seg: 14 })
      I.flames.push({ x: 0.3, y: 0.88, z: 0.3, w: 0.45, h: 0.55, when: 'always' })
      I.lights.push({ x: 0.3, y: 1.3, z: 0.3, color: '#ff9040', intensity: 3, dist: 8, flicker: true, when: 'always' })
      b.at({ x: 0.75, y: 0.0, z: 0.55, rz: 1.3, ry: 0.4 }, () => {
        b.cyl(0.02, 0.022, 0.9, { mat: 'wood', color: '#b8844a', y: 0.45, seg: 8 })
        b.box(0.08, 0.08, 0.22, { mat: 'metal', color: '#3a3a3a', y: 0.9 })
      })
      signBoard(b, 'SCRAP', { x: 2.4, y: 1.4, z: -2.1, w: 0.9, h: 0.3, bg: '#a8382a', tilt: 0.06 })
      for (const x of [2.0, 2.8]) b.cyl(0.04, 0.04, 1.5, { mat: 'wood', color: '#8a7050', x, y: 0.75, z: -2.15, seg: 6 })
      I.emitters.push({ kind: 'smoke', x: 0.3, y: 1.4, z: 0.3, when: 'always' })
      I.spots.push({ x: -0.3, z: -0.3, face: -2.0, anim: 'hammer' }, { x: 1.2, z: -0.5, face: FACE_BACK, anim: 'search' })
      return
    }
    if (L === 2) {
      // stripping cars properly: an engine hoist, an engine stand, sorting cages
      b.box(5.8, 0.02, 4.8, { mat: 'gravel', color: '#8a8478', y: 0.0 })
      stains(b, rnd, -2.5, 2.5, -2.0, 2.0, 6, 0.012)
      mergeModel(b, carModel({ kind: 'suv', seed: 32, wreck: 0.7, color: '#3a4e62' }), { x: -1.2, z: -1.0, ry: 0.0 })
      // hoist over the engine bay
      b.at({ x: -1.2, z: 1.0 }, () => {
        for (const s of [-1, 1]) b.beam([s * 0.6, 0, 0.3], [0, 2.6, 0.0], 0.08, 0.08, { mat: 'paint', color: '#c83a2a' })
        for (const s of [-1, 1]) b.beam([s * 0.6, 0, -0.9], [0, 2.6, 0.0], 0.08, 0.08, { mat: 'paint', color: '#c83a2a' })
        b.box(0.12, 0.12, 0.3, { mat: 'paint', color: '#2a2a2a', y: 2.5 })
        b.cyl(0.008, 0.008, 1.4, { mat: 'steel', color: '#5a5a5a', y: 1.8, seg: 4 })
        b.box(0.55, 0.45, 0.6, { mat: 'metal', color: '#3a3a3a', y: 0.9 })
        b.box(0.5, 0.15, 0.55, { mat: 'metal', color: '#5a5a5a', y: 1.2 })
      })
      // engine on a stand
      b.at({ x: 0.6, z: 0.8 }, () => {
        b.box(0.08, 0.7, 0.08, { mat: 'paint', color: '#c83a2a', y: 0.35 })
        for (const s of [-1, 1]) b.box(0.6, 0.06, 0.08, { mat: 'paint', color: '#c83a2a', y: 0.04, z: s * 0.25, ry: s * 0.3 })
        b.box(0.5, 0.4, 0.55, { mat: 'metal', color: '#4a4a48', x: 0.3, y: 0.75 })
        b.box(0.45, 0.12, 0.5, { mat: 'paint', color: '#8a8e92', x: 0.3, y: 1.0 })
        for (let i = 0; i < 4; i++) b.cyl(0.03, 0.03, 0.1, { mat: 'metal', color: '#2a2a2a', x: 0.15 + i * 0.1, y: 1.1, z: 0.18, seg: 6 })
      })
      // sorting cages
      const sorts = [['COPPER', '#c87a40'], ['ALU', '#c8ccd0'], ['STEEL', '#6a6e72']]
      sorts.forEach(([label, c], i) => {
        const x = 0.9 + i * 0.75
        cage(b, 0.65, 0.65, 0.8, { x, z: -1.6, color: '#4a4e52' })
        for (let k = 0; k < 10; k++) {
          if (label === 'COPPER') b.torus(0.08, 0.015, { mat: 'metal', color: c, x: x + (rnd() - 0.5) * 0.4, y: 0.1 + rnd() * 0.4, z: -1.6 + (rnd() - 0.5) * 0.4, rx: rnd() * 3, ry: rnd() * 3, rs: 4, ts2: 10 })
          else b.box(0.12 + rnd() * 0.15, 0.05, 0.1 + rnd() * 0.1, { mat: 'metal', color: c, x: x + (rnd() - 0.5) * 0.4, y: 0.1 + rnd() * 0.45, z: -1.6 + (rnd() - 0.5) * 0.4, rx: rnd() * 3, ry: rnd() * 3 })
        }
        decal(b, plateMat(label, '#e8e4d8', '#1a1a1a'), 0.3, 0.12, { x, y: 0.65, z: -1.27 })
      })
      // cutting torch cart and a tyre stack
      b.at({ x: 2.3, z: 0.6, ry: -0.6 }, () => {
        b.box(0.5, 0.06, 0.35, { mat: 'paint', color: '#2a2a2a', y: 0.2 })
        for (const s of [-1, 1]) b.cyl(0.08, 0.08, 0.04, { mat: 'rubber', color: '#1a1a1a', x: s * 0.25, y: 0.08, rz: Math.PI / 2, seg: 10 })
        gasBottle(b, { x: -0.1, y: 0.1, color: '#2a6a3a' })
        gasBottle(b, { x: 0.12, y: 0.1, color: '#a82a22' })
        b.rope([[0, 1.3, 0], [0.3, 0.8, 0.4], [0.2, 0.3, 0.7]], 0.01, { mat: 'rubber', color: '#2a6a3a', sag: 0.1 })
      })
      tireStack(b, { x: 2.5, z: -0.4, n: 5 })
      scrapPile(b, { x: 1.5, z: 1.7, r: 0.7, n: 9, seed: 43 })
      I.emitters.push({ kind: 'sparks', x: -1.2, y: 0.9, z: 0.7, when: 'active' })
      I.spots.push({ x: -0.4, z: 1.1, face: -Math.PI / 2, anim: 'hammer' }, { x: 0.9, z: 0.3, face: Math.PI, anim: 'hammer' }, { x: 1.6, z: -0.9, face: FACE_BACK, anim: 'search' })
      return
    }
    // L3: a car crusher, cubes of flattened cars, and a magnet crane
    slab(b, I, { h: 0.1, seed: 27 })
    stains(b, rnd, -2.5, 2.5, -2.0, 2.0, 6, 0.102)
    // crusher: a deep yellow box with a lid that presses down
    b.at({ x: -1.0, z: -1.1 }, () => {
      b.box(3.0, 0.9, 1.6, { mat: 'paint', color: '#d8a020', y: 0.55 })
      b.box(2.7, 0.7, 1.3, { mat: 'metal', color: '#3a3a3a', y: 0.72 })
      for (const s of [-1, 1]) b.box(0.25, 2.2, 0.25, { mat: 'paint', color: '#d8a020', x: s * 1.6, y: 1.1 })
      b.box(3.5, 0.3, 0.4, { mat: 'paint', color: '#d8a020', y: 2.3 })
      for (const s of [-1, 1]) b.cyl(0.1, 0.1, 0.6, { mat: 'chrome', color: '#c8ccd0', x: s * 0.8, y: 1.95, seg: 10 })
      b.pivot('lid', { y: 1.6 }, (p) => {
        p.box(2.8, 0.25, 1.4, { mat: 'paint', color: '#c89018' })
        for (const s of [-1, 1]) p.cyl(0.06, 0.06, 0.5, { mat: 'chrome', color: '#d8dce0', x: s * 0.8, y: 0.35, seg: 10 })
      })
      b.plane(2.9, 0.12, { material: stripeMat(), y: 0.95, z: 0.81, shadow: false })
      b.box(0.5, 0.6, 0.4, { mat: 'paint', color: '#2a2a2a', x: 1.9, y: 0.3, z: 0.3 })
    })
    I.anims.push({ name: 'lid', kind: 'press', axis: 'y', amp: 0.75, speed: 0.4, when: 'active' })
    // cubes of crushed cars: squashed layers of paint, rust, rubber and glass
    for (let i = 0; i < 7; i++) {
      const x = 1.0 + (i % 3) * 0.78
      const y = Math.floor(i / 3) * 0.58
      const c = pick(rnd, CAR_TONES)
      b.at({ x, y, z: 1.3, ry: (rnd() - 0.5) * 0.2 }, () => {
        for (let k = 0; k < 5; k++) {
          const lc = k === 1 ? '#1e1e1e' : k === 3 ? '#7e6656' : c
          b.box(0.66 + (rnd() - 0.5) * 0.08, 0.1, 0.66 + (rnd() - 0.5) * 0.08, { mat: k === 3 ? 'rust' : k === 1 ? 'rubber' : 'paint', color: lc, x: (rnd() - 0.5) * 0.05, y: 0.06 + k * 0.105, z: (rnd() - 0.5) * 0.05, rz: (rnd() - 0.5) * 0.08, rx: (rnd() - 0.5) * 0.08 })
        }
        b.box(0.5, 0.02, 0.012, { mat: 'chrome', color: '#a8acb0', y: 0.25, z: 0.34, rz: (rnd() - 0.5) * 0.3 })
        b.box(0.3, 0.06, 0.01, { mat: 'glass', color: '#3a4a50', x: 0.1, y: 0.42, z: 0.34, rz: 0.2 })
        if (rnd() < 0.6) b.torus(0.12, 0.04, { mat: 'rubber', color: '#1a1a1a', x: -0.2, y: 0.15, z: 0.33, rs: 5, ts2: 12, arc: Math.PI })
      })
    }
    // magnet crane: column, slewing boom (yaw anim), magnet on a cable
    b.at({ x: 1.6, z: -0.9 }, () => {
      b.box(0.6, 0.3, 0.6, { mat: 'concrete', color: '#a8a49c', y: 0.15 })
      b.cyl(0.18, 0.22, 2.6, { mat: 'paint', color: '#d8a020', y: 1.6, seg: 12 })
      b.pivot('boom', { y: 2.9 }, (p) => {
        p.box(0.6, 0.5, 0.6, { mat: 'paint', color: '#d8a020', y: 0.1 })
        p.box(0.4, 0.35, 0.3, { mat: 'glass', color: '#3a4a50', y: 0.2, z: 0.31 })
        p.beam([0, 0.2, 0], [0, 0.6, 2.4], 0.18, 0.22, { mat: 'paint', color: '#d8a020' })
        p.box(0.7, 0.4, 0.5, { mat: 'paint', color: '#3a3a3a', y: 0.1, z: -0.5 })
        p.cyl(0.008, 0.008, 1.6, { mat: 'steel', color: '#2a2a2a', y: -0.2, z: 2.4, seg: 4 })
        p.cyl(0.32, 0.32, 0.18, { mat: 'paint', color: '#2a2a2a', y: -1.0, z: 2.4, seg: 16 })
        p.box(0.6, 0.06, 0.4, { mat: 'metal', color: '#6a6e72', y: -1.15, z: 2.4, rz: 0.2 })
      })
    })
    I.anims.push({ name: 'boom', kind: 'yaw', speed: 0.25, swing: 1.0, when: 'active' })
    controlPanel(b, { x: 0.6, z: 0.1, ry: 0.4 })
    beacon(b, I, { x: 0.6, y: 2.55, z: -1.1 })
    scrapPile(b, { x: -1.8, z: 1.4, r: 0.8, n: 10, seed: 45 })
    I.emitters.push({ kind: 'dust', x: -1.0, y: 1.0, z: -1.1, when: 'active' }, { kind: 'exhaust', x: 0.9, y: 0.8, z: -0.8, when: 'active' })
    I.spots.push({ x: 0.6, z: 0.6, face: FACE_BACK, anim: 'type' }, { x: -1.0, z: 0.3, face: FACE_BACK, anim: 'search' }, { x: 2.3, z: 0.4, face: Math.PI, anim: 'carry' })
  },


  // ============================================================ FORGE
  // Smelts scrap into usable metal, fired with wood.
  forge(b, L, I) {
    const rnd = seeded(1600 + L)
    if (L === 1) {
      // a clay furnace with leather bellows, an anvil on a stump, a quench barrel
      b.box(3.8, 0.02, 3.8, { mat: 'dirt', color: '#5a4a3a', y: 0.0 })
      b.at({ x: -0.8, z: -0.9 }, () => {
        b.lathe([[0.6, 0], [0.62, 0.3], [0.52, 0.8], [0.32, 1.15], [0.2, 1.3], [0.18, 1.42]], { mat: 'plaster', color: '#b88a6a', seg: 16 })
        b.cyl(0.2, 0.2, 0.04, { mat: 'plain', color: '#1a1410', y: 1.42, seg: 12 })
        b.box(0.36, 0.3, 0.2, { mat: 'embers', color: '#ffffff', y: 0.32, z: 0.52 })
        b.box(0.48, 0.08, 0.26, { mat: 'brick', color: '#a87860', y: 0.52, z: 0.54 })
        for (let i = 0; i < 6; i++) b.sphere(0.07, { mat: 'embers', color: '#ffffff', x: (rnd() - 0.5) * 0.3, y: 0.22, z: 0.66 + rnd() * 0.08, ws: 6, hs: 4 })
        for (let i = 0; i < 5; i++) blob(b, 0.2 + rnd() * 0.2, 0.1 + rnd() * 0.15, { rnd, mat: 'plaster', color: '#8a6a52', x: Math.cos(i * 1.3) * 0.58, y: 0.4 + rnd() * 0.5, z: Math.sin(i * 1.3) * 0.58, ry: -i * 1.3 + Math.PI / 2, t: 0.02 })
      })
      I.flames.push({ x: -0.8, y: 1.42, z: -0.9, w: 0.3, h: 0.5, when: 'active' }, { x: -0.8, y: 0.25, z: -0.32, w: 0.3, h: 0.25, when: 'active' })
      I.lights.push({ x: -0.8, y: 0.6, z: -0.2, color: '#ff8030', intensity: 4, dist: 8, flicker: true, when: 'active' })
      I.emitters.push({ kind: 'smoke', x: -0.8, y: 1.6, z: -0.9, when: 'active' }, { kind: 'embers', x: -0.8, y: 1.5, z: -0.9, when: 'active' })
      // bellows on a stand, pipe into the furnace
      b.at({ x: -1.55, z: 0.1, ry: -0.8 }, () => {
        b.box(0.2, 0.35, 0.2, { mat: 'wood', color: '#8a7050', y: 0.18 })
        b.pivot('bellows', { y: 0.42 }, (p) => {
          p.extrude([[-0.15, 0], [0.15, 0], [0.12, 0.55], [0.03, 0.7], [-0.03, 0.7], [-0.12, 0.55]], 0.03, { mat: 'wood', color: '#8a6a48', rx: -Math.PI / 2, y: 0.12 })
          p.sphere(0.2, { mat: 'cloth', color: '#5a3a22', y: 0.06, z: -0.3, sy: 0.35, sz: 1.5 })
        })
        b.extrude([[-0.15, 0], [0.15, 0], [0.12, 0.55], [0.03, 0.7], [-0.03, 0.7], [-0.12, 0.55]], 0.03, { mat: 'wood', color: '#8a6a48', rx: -Math.PI / 2, y: 0.4 })
      })
      I.anims.push({ name: 'bellows', kind: 'pump', axis: 'x', amp: 0.18, speed: 3, when: 'active' })
      pipeRun(b, [[-1.3, 0.45, -0.25], [-1.0, 0.35, -0.45]], 0.035, { color: '#4a4440', flanges: false })
      const ay = anvil(b, { x: 0.65, z: -0.1, ry: 0.3 })
      b.box(0.2, 0.025, 0.04, { mat: 'embers', color: '#ffffff', x: 0.65, y: ay + 0.012, z: -0.1, ry: 0.3 })
      barrel(b, { x: 1.35, z: 0.6, color: '#4a4a44', open: true, contents: '#2a3036', fill: 0.88 })
      // tool rack: tongs and hammers
      b.at({ x: 1.3, z: -1.2 }, () => {
        for (const s of [-1, 1]) b.box(0.06, 1.3, 0.06, { mat: 'wood', color: '#8a7050', x: s * 0.5, y: 0.65 })
        b.box(1.1, 0.06, 0.06, { mat: 'wood', color: '#8a7050', y: 1.2 })
        for (let i = 0; i < 6; i++) {
          if (i % 2) hammer(b, { x: -0.38 + i * 0.15, y: 1.0, z: 0.05 })
          else for (const s of [-1, 1]) b.box(0.015, 0.5, 0.015, { mat: 'metal', color: '#3a3a3a', x: -0.38 + i * 0.15 + s * 0.015, y: 0.95, z: 0.04, rz: s * 0.06 })
        }
      })
      // charcoal and firewood
      b.sphere(0.45, { mat: 'gravel', color: '#2a2622', x: -1.4, y: -0.12, z: 1.2, sy: 0.45, ws: 12, hs: 6 })
      firewood(b, { x: 0.2, z: 1.45, len: 1.2, h: 0.6 })
      I.spots.push({ x: 0.65, z: 0.5, face: FACE_BACK, anim: 'hammer' }, { x: -1.55, z: 0.65, face: 2.4, anim: 'pump' })
      return
    }
    if (L === 2) {
      // a brick forge under a hood, a power hammer, tongs, a slack tub, coal bin
      b.box(3.8, 0.08, 3.8, { mat: 'concrete', color: '#8a867e', y: 0.04 })
      tinLeanTo(b, I, { x: 0, z: -0.3, w: 3.6, d: 2.6, hf: 2.9, hb: 2.6, seed: 61 })
      b.at({ x: -0.9, z: -1.0 }, () => {
        b.box(1.3, 0.8, 0.95, { mat: 'brick', color: '#c89078', y: 0.44 })
        b.box(1.35, 0.06, 1.0, { mat: 'metal', color: '#3a3a3a', y: 0.87 })
        b.cyl(0.22, 0.2, 0.06, { mat: 'embers', color: '#ffffff', y: 0.88, seg: 14 })
        for (let i = 0; i < 8; i++) b.dodeca(0.04, { mat: 'gravel', color: '#1a1a1a', x: (rnd() - 0.5) * 0.5, y: 0.9, z: (rnd() - 0.5) * 0.4 })
        b.lathe([[0.75, 1.45], [0.25, 1.95], [0.22, 2.0]], { mat: 'metal', color: '#5a5650', seg: 4, ry: Math.PI / 4 })
        b.lathe([[0.22, 2.0], [0.25, 1.95], [0.75, 1.45]], { mat: 'metal', color: '#3a3836', seg: 4, ry: Math.PI / 4 })
        for (const sx of [-1, 1]) b.box(0.05, 0.6, 0.05, { mat: 'metal', color: '#3a3836', x: sx * 0.5, y: 1.17, z: 0.0 })
        b.cyl(0.18, 0.18, 1.6, { mat: 'metal', color: '#4a4640', y: 2.8, seg: 12 })
        b.cyl(0.26, 0.26, 0.05, { mat: 'metal', color: '#3a3836', y: 3.62, seg: 12 })
        // hand-crank blower
        b.cyl(0.16, 0.16, 0.12, { mat: 'paint', color: '#2a4a3a', x: 0.85, y: 0.6, rz: Math.PI / 2, seg: 14 })
        b.torus(0.12, 0.012, { mat: 'metal', color: '#3a3a3a', x: 0.93, y: 0.6, ry: Math.PI / 2 })
      })
      I.flames.push({ x: -0.9, y: 0.9, z: -1.0, w: 0.35, h: 0.35, when: 'active' })
      I.lights.push({ x: -0.9, y: 1.2, z: -0.6, color: '#ff8030', intensity: 4, dist: 8, flicker: true, when: 'active' })
      I.emitters.push({ kind: 'chimney', x: -0.9, y: 3.7, z: -1.0, when: 'active' }, { kind: 'embers', x: -0.9, y: 1.0, z: -1.0, when: 'active' })
      // power hammer: C frame, ram on a pivot
      b.at({ x: 1.1, z: -1.05 }, () => {
        b.box(0.7, 0.15, 0.7, { mat: 'concrete', color: '#8a867e', y: 0.075 })
        b.box(0.35, 1.9, 0.4, { mat: 'paint', color: '#3a4a3a', y: 1.1, z: -0.2 })
        b.box(0.35, 0.3, 0.6, { mat: 'paint', color: '#3a4a3a', y: 1.9, z: 0.0 })
        b.box(0.3, 0.45, 0.3, { mat: 'metal', color: '#3a3a3a', y: 0.38, z: 0.15 })
        b.cyl(0.25, 0.25, 0.08, { mat: 'metal', color: '#4a4a48', x: 0.22, y: 2.0, z: -0.15, rz: Math.PI / 2, seg: 16 })
        b.pivot('ram', { y: 1.45, z: 0.15 }, (p) => {
          p.box(0.16, 0.5, 0.16, { mat: 'metal', color: '#5a5e62' })
          p.box(0.2, 0.08, 0.2, { mat: 'metal', color: '#3a3a3a', y: -0.28 })
        })
      })
      I.anims.push({ name: 'ram', kind: 'press', axis: 'y', amp: 0.32, speed: 1.6, when: 'active' })
      const ay = anvil(b, { x: 0.05, z: 0.35, ry: 0.2 })
      b.box(0.3, 0.03, 0.05, { mat: 'embers', color: '#ffffff', x: 0.05, y: ay + 0.015, z: 0.35, ry: 0.2 })
      barrel(b, { x: -1.4, z: 0.7, color: '#4a4a44', open: true, contents: '#2a3036', fill: 0.88 })
      b.at({ x: 1.4, z: 0.75 }, () => {
        b.box(0.8, 0.5, 0.6, { mat: 'wood', color: '#6a5a48', y: 0.25 })
        b.box(0.75, 0.1, 0.55, { mat: 'gravel', color: '#1e1c1a', y: 0.46 })
      })
      // stack of finished bars and a tong rack
      for (let i = 0; i < 9; i++) b.box(0.6, 0.06, 0.08, { mat: 'metal', color: '#8a8e92', x: -0.4 + (i % 3) * 0.0, y: 0.1 + Math.floor(i / 3) * 0.065, z: 1.4 + (i % 3) * 0.1, ry: Math.floor(i / 3) % 2 ? 0.05 : -0.05 })
      for (let i = 0; i < 5; i++) for (const s of [-1, 1]) b.box(0.015, 0.55, 0.015, { mat: 'metal', color: '#3a3a3a', x: -1.75, y: 0.9, z: -0.2 + i * 0.12 + s * 0.015, rx: s * 0.06 })
      b.box(0.05, 0.05, 0.7, { mat: 'metal', color: '#3a3a3a', x: -1.75, y: 1.2, z: 0.05 })
      I.spots.push({ x: 0.05, z: 0.95, face: FACE_BACK, anim: 'hammer' }, { x: 1.1, z: -0.35, face: FACE_BACK, anim: 'hammer' })
      return
    }
    // L3: a cupola smelter charged by a skip conveyor, pouring into ingot moulds
    slab(b, I, { h: 0.12, seed: 29 })
    stains(b, rnd, -1.7, 1.7, -1.7, 1.7, 5, 0.122)
    b.at({ x: -0.8, z: -0.8 }, () => {
      for (let i = 0; i < 4; i++) {
        const a = (i / 4) * TAU + Math.PI / 4
        b.box(0.1, 0.6, 0.1, { mat: 'paint', color: '#3a3a3a', x: Math.cos(a) * 0.48, y: 0.42, z: Math.sin(a) * 0.48 })
      }
      b.cyl(0.6, 0.6, 0.14, { mat: 'metal', color: '#3a3a3a', y: 0.76, seg: 18 })
      b.cyl(0.52, 0.55, 2.3, { mat: 'paint', color: '#5a5a54', y: 1.98, seg: 18 })
      for (let k = 0; k < 6; k++) b.torus(0.555, 0.02, { mat: 'metal', color: '#3a3a3a', y: 1.0 + k * 0.4, rx: Math.PI / 2, rs: 4, ts2: 24 })
      b.cyl(0.35, 0.4, 0.8, { mat: 'metal', color: '#3a3836', y: 3.5, seg: 14 })
      b.box(0.5, 0.5, 0.2, { mat: 'plain', color: '#1a1410', y: 2.6, z: 0.5 })
      b.box(0.36, 0.3, 0.05, { mat: 'embers', color: '#ffffff', y: 2.58, z: 0.6 })
      // tap spout and a stream of metal into a ladle
      b.box(0.14, 0.08, 0.4, { mat: 'metal', color: '#3a3a3a', y: 0.95, z: 0.65, rx: 0.2 })
      b.cyl(0.03, 0.025, 0.42, { mat: 'embers', color: '#ffffff', y: 0.68, z: 0.88, seg: 8 })
      b.lathe([[0.001, 0], [0.22, 0.02], [0.26, 0.38], [0.27, 0.4]], { mat: 'metal', color: '#2a2a2a', y: 0.08, z: 0.95, seg: 14 })
      b.cyl(0.23, 0.23, 0.02, { mat: 'embers', color: '#ffffff', y: 0.42, z: 0.95, seg: 14 })
    })
    I.flames.push({ x: -0.8, y: 3.9, z: -0.8, w: 0.4, h: 0.5, when: 'active' })
    I.lights.push({ x: -0.8, y: 1.0, z: 0.4, color: '#ff8030', intensity: 6, dist: 10, flicker: true, when: 'active' })
    I.emitters.push({ kind: 'chimney', x: -0.8, y: 4.0, z: -0.8, when: 'active' }, { kind: 'embers', x: -0.8, y: 0.8, z: 0.1, when: 'active' }, { kind: 'sparks', x: -0.8, y: 2.6, z: -0.25, when: 'active' })
    // skip conveyor carrying scrap up to the charging door
    b.at({ x: 0.6, z: -0.65, ry: Math.PI / 2 }, () => {
      b.at({ rz: 0.55 }, () => conveyor(b, I, {
        len: 2.4, w: 0.42, h: 0.1, name: 'skip', gap: 0.4, speed: 0.25, color: '#5a5a54',
        items: (p, x, r) => p.box(0.16, 0.08, 0.14, { mat: 'rust', color: '#9a8070', x, y: 0.04, ry: r() * 3 }),
      }))
      for (const s of [-1, 1]) b.box(0.08, 1.5, 0.08, { mat: 'paint', color: '#4a4e52', x: 0.9, y: 0.75, z: s * 0.25 })
    })
    // ingot moulds: a line of glowing and cooled bars
    b.at({ x: 0.85, z: 0.75 }, () => {
      b.box(1.5, 0.7, 0.6, { mat: 'paint', color: '#3a3a3a', y: 0.35 })
      for (let i = 0; i < 6; i++) {
        b.box(0.2, 0.06, 0.4, { mat: 'metal', color: '#2a2a2a', x: -0.6 + i * 0.24, y: 0.72 })
        b.box(0.15, 0.04, 0.32, { mat: i < 2 ? 'embers' : 'metal', color: i < 2 ? '#ffffff' : '#9aa0a4', x: -0.6 + i * 0.24, y: 0.75 })
      }
    })
    pallet(b, { x: 1.35, z: -1.4, w: 0.9, d: 0.7 })
    for (let i = 0; i < 16; i++) b.box(0.3, 0.08, 0.12, { mat: 'metal', color: '#9aa0a4', x: 1.15 + (i % 4) * 0.13 * 0 + (i % 2) * 0.32, y: 0.17 + Math.floor(i / 4) * 0.085, z: -1.6 + Math.floor(i % 4 / 2) * 0.35 + (Math.floor(i / 4) % 2) * 0.05, ry: Math.floor(i / 4) % 2 ? Math.PI / 2 : 0 })
    controlPanel(b, { x: 0.2, z: 1.3, ry: 0.2 })
    beacon(b, I, { x: 0.05, y: 2.4, z: -0.6 })
    b.plane(3.4, 0.07, { material: stripeMat(), y: 0.122, z: 0.2, rx: -Math.PI / 2, shadow: false })
    I.spots.push({ x: 0.2, z: 1.75, face: FACE_BACK, anim: 'type' }, { x: -0.3, z: 0.4, face: -2.5, anim: 'stir' })
  },

  // ============================================================ BIOFUEL STILL
  still(b, L, I) {
    const rnd = seeded(1700 + L)
    if (L === 1) {
      // mash barrels, a pressure-cooker still on a fire, a bucket condenser
      for (let i = 0; i < 2; i++) {
        barrel(b, { x: -1.4 + i * 0.65, z: -0.85, color: '#c8c4b4', open: true, contents: '#6a5a2a', fill: 0.8 })
        for (let k = 0; k < 4; k++) b.sphere(0.03, { mat: 'gloss', color: '#a89a5a', x: -1.4 + i * 0.65 + (rnd() - 0.5) * 0.3, y: 0.72, z: -0.85 + (rnd() - 0.5) * 0.3, sy: 0.5, ws: 6, hs: 4 })
      }
      b.at({ x: 0.3, z: -0.7 }, () => {
        for (let i = 0; i < 8; i++) {
          const a = (i / 8) * TAU
          rock(b, { x: Math.cos(a) * 0.32, z: Math.sin(a) * 0.32, s: 0.11, seed: 90 + i })
        }
        for (let i = 0; i < 4; i++) b.beam([Math.cos(i * 1.6) * 0.25, 0.03, Math.sin(i * 1.6) * 0.25], [0, 0.2, 0], 0.06, 0.06, { mat: 'bark', color: '#5a4a3a', round: true })
        b.box(0.6, 0.04, 0.6, { mat: 'metal', color: '#2a2a2a', y: 0.36 })
        for (const s of [-1, 1]) for (const t of [-1, 1]) b.box(0.04, 0.36, 0.04, { mat: 'metal', color: '#2a2a2a', x: s * 0.26, y: 0.18, z: t * 0.26 })
        b.lathe([[0.001, 0], [0.2, 0.01], [0.22, 0.05], [0.22, 0.3], [0.2, 0.33], [0.001, 0.36]], { mat: 'metal', color: '#b8bcc0', y: 0.38, seg: 16 })
        b.cyl(0.03, 0.03, 0.08, { mat: 'metal', color: '#3a3a3a', y: 0.78, seg: 8 })
        b.tube([[0, 0.8, 0], [0.1, 0.95, 0], [0.5, 0.9, 0.0], [0.85, 0.6, 0.1]], 0.015, { mat: 'metal', color: '#c87a48', tseg: 14, seg: 6 })
      })
      I.flames.push({ x: 0.3, y: 0.05, z: -0.7, w: 0.35, h: 0.3, when: 'active' })
      I.lights.push({ x: 0.3, y: 0.4, z: -0.5, color: '#ff8030', intensity: 2.5, dist: 6, flicker: true, when: 'active' })
      bucket(b, { x: 1.2, z: -0.6, water: true })
      for (let k = 0; k < 3; k++) b.torus(0.09, 0.012, { mat: 'metal', color: '#c87a48', x: 1.2, y: 0.24 - k * 0.04, z: -0.6, rx: Math.PI / 2, rs: 4, ts2: 14 })
      b.cyl(0.012, 0.012, 0.2, { mat: 'metal', color: '#c87a48', x: 1.36, y: 0.1, z: -0.5, rz: 1.2, seg: 5 })
      jerrycan(b, { x: 1.5, z: -0.2, ry: -0.4, color: '#a8382a' })
      for (let i = 0; i < 4; i++) sack(b, { x: -1.3 + (i % 2) * 0.45, y: Math.floor(i / 2) * 0.24, z: 0.55, ry: Math.PI / 2, color: '#c8b080' })
      I.emitters.push({ kind: 'steam', x: 0.3, y: 0.9, z: -0.7, when: 'active' }, { kind: 'smoke', x: 0.3, y: 0.4, z: -0.7, when: 'active' })
      I.spots.push({ x: 0.3, z: -0.05, face: FACE_BACK, anim: 'stir' }, { x: -1.1, z: -0.25, face: FACE_BACK, anim: 'stir' })
      return
    }
    if (L === 2) {
      // a copper pot still over a brick firebox, a worm tub, two fermenters
      b.box(3.8, 0.08, 2.8, { mat: 'concrete', color: '#8a867e', y: 0.04 })
      b.box(0.9, 0.55, 0.9, { mat: 'brick', color: '#b88068', x: -0.6, y: 0.35, z: -0.7 })
      b.box(0.3, 0.22, 0.04, { mat: 'embers', color: '#ffffff', x: -0.6, y: 0.24, z: -0.24 })
      potStill(b, { x: -0.6, y: 0.62, z: -0.7, reach: 1.1 })
      wormTub(b, { x: 0.55, z: -0.7 })
      b.cyl(0.015, 0.015, 0.25, { mat: 'metal', color: '#c87a48', x: 0.85, y: 0.15, z: -0.52, rz: 1.1, seg: 5 })
      b.lathe([[0.001, 0], [0.12, 0.01], [0.14, 0.2], [0.05, 0.3], [0.04, 0.36]], { mat: 'glass', color: '#d8c890', x: 1.05, z: -0.45, seg: 12 })
      I.flames.push({ x: -0.6, y: 0.15, z: -0.22, w: 0.25, h: 0.2, when: 'active' })
      I.lights.push({ x: -0.6, y: 0.5, z: 0.0, color: '#ff8030', intensity: 2.5, dist: 6, flicker: true, when: 'active' })
      b.cyl(0.08, 0.08, 1.6, { mat: 'metal', color: '#3a3836', x: -0.6, y: 1.4, z: -1.12, seg: 10 })
      for (const x of [-1.5]) tote(b, { x, z: 0.65, ry: 0.1, color: '#c8c0a0', level: 0.6 })
      b.rope([[-1.5, 1.1, 0.4], [-1.1, 0.9, -0.2], [-0.75, 1.3, -0.7]], 0.02, { mat: 'rubber', color: '#c8b070', sag: 0.1 })
      pallet(b, { x: 0.9, z: 0.8, w: 1.0, d: 0.8 })
      for (let i = 0; i < 6; i++) jerrycan(b, { x: 0.65 + (i % 3) * 0.22, y: 0.13, z: 0.65 + Math.floor(i / 3) * 0.36, ry: Math.PI / 2, color: pick(rnd, ['#a8382a', '#a8382a', '#3a6a3a']) })
      decal(b, plateMat('FUEL - NO FLAMES', '#c8302a', '#e8e4d8', 'flame'), 0.5, 0.25, { x: 1.6, y: 1.2, z: 1.3, ry: -0.8 })
      b.box(0.06, 1.3, 0.06, { mat: 'wood', color: '#8a7050', x: 1.62, y: 0.65, z: 1.28 })
      I.emitters.push({ kind: 'steam', x: -0.6, y: 2.25, z: -1.12, when: 'active' }, { kind: 'smoke', x: -0.6, y: 2.2, z: -1.12, when: 'active' })
      I.spots.push({ x: -0.6, z: 0.05, face: FACE_BACK, anim: 'stir' }, { x: 0.55, z: -0.1, face: FACE_BACK, anim: 'search' })
      return
    }
    // L3: column still, stainless fermenters, a fuel tank with a dispenser
    slab(b, I, { h: 0.12, seed: 31 })
    reactor(b, I, { x: -1.45, z: -0.85, r: 0.38, h: 1.2, name: 'fanA', label: 'MASH 1' })
    reactor(b, I, { x: -0.6, z: -0.95, r: 0.38, h: 1.2, name: 'fanB', label: 'MASH 2' })
    b.at({ x: 0.45, z: -1.0 }, () => {
      b.cyl(0.18, 0.18, 2.8, { mat: 'metal', color: '#a8acb0', y: 1.55, seg: 16 })
      for (let i = 0; i < 6; i++) {
        b.torus(0.185, 0.012, { mat: 'metal', color: '#7a7e82', y: 0.5 + i * 0.42, rx: Math.PI / 2, rs: 4, ts2: 20 })
        b.cyl(0.04, 0.04, 0.03, { mat: 'glass', color: '#d8c890', y: 0.7 + i * 0.42, z: 0.18, rx: Math.PI / 2, seg: 10 })
      }
      b.lathe([[0.001, 0.1], [0.35, 0.12], [0.38, 0.2], [0.38, 0.55], [0.3, 0.6], [0.001, 0.6]], { mat: 'metal', color: '#9aa0a4', seg: 18 })
      b.cyl(0.12, 0.12, 0.5, { mat: 'metal', color: '#a8acb0', x: 0.45, y: 2.7, rz: Math.PI / 2, seg: 12 })
    })
    pipeRun(b, [[-1.45, 1.9, -0.85], [-1.45, 2.25, -0.85], [0.45, 2.25, -0.85]], 0.04)
    pipeRun(b, [[0.9, 2.6, -1.0], [1.2, 2.6, -1.0], [1.2, 0.9, -1.0], [1.2, 0.9, -0.4]], 0.035, { color: '#c87a48' })
    // fuel tank and a forecourt pump
    b.at({ x: 0.4, z: 0.75 }, () => {
      for (const x of [-0.7, 0.7]) b.box(0.15, 0.35, 0.9, { mat: 'concrete', color: '#a8a49c', x, y: 0.18 })
      b.cyl(0.5, 0.5, 2.2, { mat: 'paint', color: '#c8302a', y: 0.85, rz: Math.PI / 2, seg: 20 })
      for (const s of [-1, 1]) b.sphere(0.5, { mat: 'paint', color: '#c8302a', x: s * 1.1, y: 0.85, sx: 0.25, ws: 16, hs: 10 })
      b.plane(0.9, 0.3, { material: plateMat('BIODIESEL', '#c8302a', '#e8e4d8', 'flame'), y: 0.85, z: 0.505, shadow: false })
      b.cyl(0.08, 0.08, 0.2, { mat: 'metal', color: '#3a3a3a', y: 1.42, x: 0.5, seg: 10 })
    })
    b.at({ x: 1.55, z: 0.15 }, () => {
      b.box(0.5, 1.4, 0.32, { mat: 'paint', color: '#e8e4d8', y: 0.82, r: 0.03 })
      b.box(0.52, 0.2, 0.34, { mat: 'paint', color: '#c8302a', y: 1.45 })
      b.box(0.3, 0.16, 0.01, { mat: 'glowGreen', color: '#0a2a10', y: 1.15, z: 0.166 })
      b.box(0.08, 0.2, 0.1, { mat: 'plastic', color: '#1a1a1a', x: 0.27, y: 0.95, z: 0.05 })
      b.rope([[0.3, 0.95, 0.05], [0.4, 0.4, 0.2], [0.27, 0.85, 0.12]], 0.015, { mat: 'rubber', color: '#1a1a1a', sag: 0.05 })
      b.box(0.6, 0.12, 0.42, { mat: 'concrete', color: '#a8a49c', y: 0.06 })
    })
    pump(b, { x: -1.0, z: 0.4, ry: 0.2 })
    controlPanel(b, { x: -1.6, z: 1.05, ry: 0.4 })
    I.emitters.push({ kind: 'steam', x: 0.45, y: 3.0, z: -1.0, when: 'active' })
    I.spots.push({ x: -1.6, z: 1.45, face: FACE_BACK, anim: 'type' }, { x: 0.0, z: -0.2, face: FACE_BACK, anim: 'search' })
  },

  // ============================================================ GENERATOR
  generator(b, L, I) {
    const rnd = seeded(1800 + L)
    if (L === 1) {
      // a portable set on a pallet, cords on reels, a battery bank
      pallet(b, { x: -0.3, z: -0.5, w: 1.2, d: 1.0 })
      b.at({ x: -0.3, y: 0.13, z: -0.5 }, () => {
        for (const s of [-1, 1]) for (const t of [-1, 1]) b.cyl(0.02, 0.02, 0.55, { mat: 'paint', color: '#2a2a2a', x: s * 0.38, y: 0.28, z: t * 0.28, seg: 6 })
        for (const y of [0.04, 0.55]) for (const t of [-1, 1]) b.cyl(0.02, 0.02, 0.8, { mat: 'paint', color: '#2a2a2a', y, z: t * 0.28, rz: Math.PI / 2, seg: 6 })
        b.box(0.32, 0.3, 0.32, { mat: 'paint', color: '#c8302a', x: -0.15, y: 0.22 })
        b.cyl(0.13, 0.13, 0.32, { mat: 'paint', color: '#3a3a3a', x: 0.2, y: 0.22, rz: Math.PI / 2, seg: 14 })
        b.box(0.62, 0.16, 0.4, { mat: 'paint', color: '#c8302a', y: 0.5, r: 0.04 })
        b.cyl(0.04, 0.04, 0.04, { mat: 'paint', color: '#1a1a1a', x: 0.15, y: 0.6, seg: 10 })
        b.box(0.18, 0.12, 0.02, { mat: 'paint', color: '#2a2a2a', x: 0.25, y: 0.3, z: 0.29 })
        for (let i = 0; i < 2; i++) b.box(0.03, 0.04, 0.01, { mat: 'plastic', color: '#e8e4dc', x: 0.2 + i * 0.07, y: 0.3, z: 0.3 })
        b.cyl(0.05, 0.05, 0.2, { mat: 'metal', color: '#4a4440', x: -0.4, y: 0.25, z: 0.15, rz: Math.PI / 2, seg: 8 })
      })
      cable(b, [[0.0, 0.45, -0.2], [0.5, 0.02, 0.3], [1.0, 0.05, 0.8], [1.9, 0.02, 1.2]], { r: 0.012, color: '#d8a020', sag: 0.01 })
      b.at({ x: 1.2, z: -0.7, ry: 0.4 }, () => {
        for (const s of [-1, 1]) b.cyl(0.25, 0.25, 0.03, { mat: 'plastic', color: '#d8a020', x: s * 0.13, y: 0.25, rz: Math.PI / 2, seg: 14 })
        b.cyl(0.17, 0.17, 0.25, { mat: 'rubber', color: '#d8a020', y: 0.25, rz: Math.PI / 2, seg: 14 })
        b.box(0.04, 0.5, 0.04, { mat: 'plastic', color: '#2a2a2a', x: 0.15, y: 0.25 })
      })
      for (let i = 0; i < 4; i++) carBattery(b, { x: -1.5 + (i % 2) * 0.34, y: Math.floor(i / 2) * 0.23, z: 0.5 })
      jerrycan(b, { x: -1.4, z: -0.6, ry: 0.4 })
      jerrycan(b, { x: -1.15, z: -0.8, ry: 0.2, color: '#3a6a3a' })
      I.emitters.push({ kind: 'exhaust', x: -0.75, y: 0.4, z: -0.35, when: 'active' })
      I.spots.push({ x: -0.3, z: 0.4, face: FACE_BACK, anim: 'search' })
      return
    }
    if (L === 2) {
      // a diesel genset on a skid, a fuel tank on saddles, a breaker post
      b.box(3.8, 0.08, 2.8, { mat: 'concrete', color: '#8a867e', y: 0.04 })
      stains(b, rnd, -1.5, 1.5, -1.1, 1.1, 4, 0.082)
      const ex = dieselEngine(b, I, { x: -0.25, y: 0.08, z: -0.55, color: '#3a5a3a', stack: 0.8 })
      I.emitters.push({ kind: 'exhaust', x: ex[0], y: ex[1], z: ex[2], when: 'active' })
      b.at({ x: -0.2, z: 0.75 }, () => {
        for (const x of [-0.6, 0.6]) b.box(0.12, 0.3, 0.7, { mat: 'paint', color: '#3a3a3a', x, y: 0.23 })
        b.cyl(0.38, 0.38, 1.7, { mat: 'paint', color: '#c8c4b4', y: 0.6, rz: Math.PI / 2, seg: 18 })
        for (const s of [-1, 1]) b.sphere(0.38, { mat: 'paint', color: '#c8c4b4', x: s * 0.85, y: 0.6, sx: 0.2, ws: 14, hs: 8 })
        b.plane(0.6, 0.2, { material: plateMat('DIESEL', '#c8c4b4', '#8a1a12'), y: 0.6, z: 0.385, shadow: false })
        gauge(b, { x: 0.4, y: 0.99, z: 0.0, ry: 0 })
        b.cyl(0.06, 0.06, 0.1, { mat: 'metal', color: '#3a3a3a', x: -0.4, y: 1.0, seg: 10 })
      })
      pipeRun(b, [[0.5, 0.35, 0.75], [0.7, 0.35, 0.3], [0.7, 0.4, -0.3]], 0.02, { color: '#2a2a2a', flanges: false })
      b.at({ x: 1.55, z: -0.9 }, () => {
        b.box(0.12, 2.0, 0.12, { mat: 'wood', color: '#8a7050', y: 1.0 })
        elecPanel(b, { y: 1.2, z: 0.13, conduit: 0.5 })
      })
      cable(b, [[0.65, 0.95, -0.55], [1.2, 0.6, -0.7], [1.5, 0.75, -0.75]], { r: 0.025, sag: 0.05 })
      cable(b, [[1.55, 1.9, -0.85], [1.75, 1.3, 0.2], [1.9, 0.05, 1.3]], { r: 0.02, sag: 0.1 })
      I.spots.push({ x: 0.5, z: 0.05, face: FACE_BACK, anim: 'search' })
      return
    }
    // L3: an acoustic canopy genset with its doors open, a day tank,
    // a transformer and a battery cabinet
    slab(b, I, { h: 0.12, seed: 33 })
    b.at({ x: -0.3, z: -0.55 }, () => {
      const cw = 2.8
      const cd = 1.2
      const ch = 1.6
      b.box(cw, 0.1, cd, { mat: 'paint', color: '#2a2a2a', y: 0.17 })
      b.box(cw, ch, 0.04, { mat: 'paint', color: '#c8a030', y: 0.22 + ch / 2, z: -cd / 2 })
      for (const s of [-1, 1]) b.box(0.04, ch, cd, { mat: 'paint', color: '#c8a030', x: s * (cw / 2), y: 0.22 + ch / 2 })
      b.box(cw + 0.06, 0.06, cd + 0.06, { mat: 'paint', color: '#b89020', y: 0.22 + ch + 0.03 })
      for (let i = 0; i < 8; i++) b.box(0.012, 0.6, 0.012, { mat: 'paint', color: '#8a6a18', x: cw / 2 + 0.03, y: 0.9 + 0.0, z: -0.4 + i * 0.11 })
      // doors swung open on the camera side
      for (const k of [0, 1]) b.at({ x: -cw / 2 + 0.05 + k * (cw / 2), z: cd / 2, ry: -1.3 }, () => b.box(cw / 2 - 0.1, ch - 0.1, 0.04, { mat: 'paint', color: '#c8a030', x: (cw / 2 - 0.1) / 2, y: 0.22 + ch / 2 }))
      decal(b, plateMat('CAUTION AUTO START', '#e8c020', '#1a1a1a', 'warn'), 0.5, 0.25, { x: 0.9, y: 1.4, z: -cd / 2 + 0.025 })
    })
    const ex = dieselEngine(b, I, { x: -0.3, y: 0.22, z: -0.55, color: '#2a3a4a', stack: 1.6 })
    I.emitters.push({ kind: 'exhaust', x: ex[0], y: ex[1], z: ex[2], when: 'active' })
    // day tank, transformer, battery cabinet
    b.box(0.8, 0.7, 0.5, { mat: 'paint', color: '#8a8e92', x: 1.45, y: 0.47, z: -1.05 })
    gauge(b, { x: 1.45, y: 0.65, z: -0.79 })
    b.at({ x: 1.45, z: 0.6 }, () => {
      b.box(0.8, 1.0, 0.6, { mat: 'paint', color: '#4a6a4a', y: 0.62 })
      for (let i = 0; i < 6; i++) b.box(0.03, 0.8, 0.62, { mat: 'paint', color: '#3e5e3e', x: -0.35 + i * 0.14, y: 0.6 })
      for (let i = 0; i < 3; i++) {
        b.cyl(0.05, 0.06, 0.25, { mat: 'plastic', color: '#c87a48', x: -0.25 + i * 0.25, y: 1.25, seg: 10 })
        for (let k = 0; k < 3; k++) b.cyl(0.07, 0.07, 0.02, { mat: 'plastic', color: '#a86038', x: -0.25 + i * 0.25, y: 1.17 + k * 0.07, seg: 10 })
      }
      decal(b, plateMat('11 kV', '#e8c020', '#1a1a1a', 'bolt'), 0.3, 0.15, { y: 0.8, z: 0.305 })
    })
    b.at({ x: -1.5, z: 0.8 }, () => {
      b.box(0.7, 1.5, 0.5, { mat: 'paint', color: '#d8d8d0', y: 0.87 })
      b.box(0.62, 1.4, 0.01, { mat: 'glass', color: '#3a4a50', y: 0.87, z: 0.256 })
      for (let i = 0; i < 4; i++) b.box(0.55, 0.25, 0.4, { mat: 'paint', color: '#2a5a8a', y: 0.3 + i * 0.32 })
      for (let i = 0; i < 4; i++) b.box(0.04, 0.02, 0.01, { mat: 'glowGreen', color: '#ffffff', x: 0.2, y: 0.35 + i * 0.32, z: 0.24 })
    })
    cable(b, [[0.9, 0.9, -0.55], [1.2, 0.3, 0.1], [1.2, 1.1, 0.5]], { r: 0.03, sag: 0.05 })
    controlPanel(b, { x: 0.2, z: 1.05, ry: 0.2 })
    beacon(b, I, { x: -1.4, y: 1.85, z: -0.55 })
    I.spots.push({ x: 0.2, z: 1.45, face: FACE_BACK, anim: 'type' })
  },

  // ============================================================ SOLAR ARRAY
  solar(b, L, I) {
    const rnd = seeded(1900 + L)
    const panel = (x, z, tilt, w = 1.0, h = 1.6) => b.at({ x, y: 0, z }, () => {
      b.at({ y: 0.9, rx: tilt }, () => {
        b.box(w + 0.04, 0.04, h + 0.04, { mat: 'metal', color: '#b8bcc0' })
        b.plane(w, h, { material: solarMatX(), y: 0.021, rx: -Math.PI / 2 })
      })
    })
    if (L === 1) {
      // three panels on timber A-frames, a battery crate and a controller
      for (let i = 0; i < 3; i++) {
        const x = -1.5 + i * 1.3
        panel(x, -0.6, 0.55)
        for (const s of [-1, 1]) {
          b.beam([x + s * 0.45, 0, -1.25], [x + s * 0.45, 1.3, -1.25], 0.07, 0.07, { mat: 'wood', color: '#a89070' })
          b.beam([x + s * 0.45, 0, 0.05], [x + s * 0.45, 0.55, 0.05], 0.07, 0.07, { mat: 'wood', color: '#a89070' })
          b.beam([x + s * 0.45, 0.1, 0.05], [x + s * 0.45, 1.0, -1.25], 0.05, 0.05, { mat: 'wood', color: '#a89070' })
        }
      }
      crate(b, { x: 1.7, z: 0.85, w: 0.7, h: 0.4, d: 0.5, lid: false, color: '#b8a888' })
      for (let i = 0; i < 3; i++) carBattery(b, { x: 1.5 + i * 0.2, y: 0.12, z: 0.85, ry: Math.PI / 2 })
      b.at({ x: 0.9, z: 0.9 }, () => {
        b.box(0.08, 1.2, 0.08, { mat: 'wood', color: '#8a7050', y: 0.6 })
        b.box(0.25, 0.2, 0.08, { mat: 'paint', color: '#2a4a7a', y: 1.0, z: 0.08 })
        b.box(0.1, 0.05, 0.01, { mat: 'glowGreen', color: '#ffffff', y: 1.05, z: 0.125 })
      })
      cable(b, [[-1.5, 0.5, -0.3], [-0.2, 0.05, 0.3], [0.9, 0.9, 0.95]], { r: 0.01, sag: 0.02 })
      cable(b, [[0.9, 0.9, 0.95], [1.4, 0.3, 0.85]], { r: 0.01, sag: 0.02, color: '#c8302a' })
      I.spots.push({ x: 0.9, z: 1.4, face: FACE_BACK, anim: 'search' })
      return
    }
    // L2: two rows of panels on galvanised racks, an inverter and a battery bank
    b.box(4.8, 0.03, 3.8, { mat: 'gravel', color: '#a89a88', y: 0.0 })
    for (const z of [-1.3, 0.1]) {
      for (let i = 0; i < 4; i++) panel(-1.65 + i * 1.1, z, 0.6, 1.0, 1.5)
      for (let i = 0; i < 5; i++) {
        const x = -2.2 + i * 1.1
        b.box(0.06, 1.25, 0.06, { mat: 'metal', color: '#b8bcc0', x, y: 0.62, z: z - 0.5 })
        b.box(0.06, 0.6, 0.06, { mat: 'metal', color: '#b8bcc0', x, y: 0.3, z: z + 0.5 })
        b.beam([x, 1.2, z - 0.5], [x, 0.55, z + 0.5], 0.05, 0.05, { mat: 'metal', color: '#b8bcc0' })
      }
    }
    b.at({ x: 1.6, z: 1.35 }, () => {
      b.box(1.0, 1.4, 0.45, { mat: 'paint', color: '#b8bcb8', y: 0.7 })
      for (let i = 0; i < 3; i++) b.box(0.9, 0.36, 0.01, { mat: 'paint', color: '#a8aca8', y: 0.3 + i * 0.42, z: 0.226 })
      b.box(0.2, 0.1, 0.01, { mat: 'glowBlue', color: '#103050', y: 1.25, z: 0.228 })
    })
    b.box(0.5, 0.7, 0.25, { mat: 'paint', color: '#3a3e42', x: 0.6, y: 0.9, z: 1.45 })
    b.box(0.3, 0.12, 0.01, { mat: 'glowGreen', color: '#0a2a10', x: 0.6, y: 1.05, z: 1.576 })
    b.box(0.06, 1.3, 0.06, { mat: 'metal', color: '#b8bcc0', x: 0.6, y: 0.65, z: 1.32 })
    cable(b, [[-1.6, 0.6, 0.6], [0.0, 0.05, 1.0], [0.6, 0.6, 1.45]], { r: 0.015, sag: 0.02 })
    I.spots.push({ x: 0.6, z: 1.9, face: FACE_BACK, anim: 'type' })
  },


  // ============================================================ CAMPFIRE
  campfire(b, L, I) {
    const rnd = seeded(2000)
    b.cyl(1.35, 1.45, 0.03, { mat: 'dirt', color: '#6a5642', y: 0.0, seg: 28, shadow: false })
    b.cyl(0.62, 0.64, 0.035, { mat: 'plain', color: '#2a2622', y: 0.02, seg: 20 })
    for (let i = 0; i < 18; i++) {
      const a = (i / 18) * TAU + rnd() * 0.12
      rock(b, { x: Math.cos(a) * 0.76, z: Math.sin(a) * 0.76, s: 0.13 + rnd() * 0.06, seed: i + 3, color: shadeHex('#8a8680', -rnd() * 0.25) })
    }
    // a teepee of split logs, charred at the base, embers in the heart
    for (let i = 0; i < 8; i++) {
      const a = (i / 8) * TAU
      b.beam([Math.cos(a) * 0.48, 0.05, Math.sin(a) * 0.48], [Math.cos(a) * 0.05, 0.66, Math.sin(a) * 0.05], 0.1, 0.1, { mat: 'bark', color: i % 2 ? '#5a4a3a' : '#2e2620', round: true })
    }
    for (let i = 0; i < 12; i++) b.box(0.12, 0.05, 0.08, { mat: 'embers', color: '#ffffff', x: (rnd() - 0.5) * 0.6, y: 0.06, z: (rnd() - 0.5) * 0.6, ry: rnd() * 3, shadow: false })
    I.flames.push({ x: 0, y: 0.06, z: 0, w: 0.95, h: 1.35, when: 'always' })
    I.lights.push({ x: 0, y: 0.9, z: 0, color: '#ff8a3a', intensity: 7, dist: 15, flicker: true, when: 'always' })
    I.emitters.push({ kind: 'campfire', x: 0, y: 0.4, z: 0, when: 'always' })
    // a grill on legs and a kettle, a pot on a tripod
    for (let i = 0; i < 3; i++) {
      const a = (i / 3) * TAU + 0.5
      b.beam([Math.cos(a) * 0.95, 0, Math.sin(a) * 0.95], [0, 1.6, 0], 0.035, 0.035, { mat: 'metal', color: '#2a2a2a', round: true })
    }
    b.rope([[0, 1.6, 0], [0, 1.05, 0]], 0.006, { mat: 'steel', color: '#3a3a3a', sag: 0, steps: 2 })
    b.lathe([[0.15, 0], [0.2, 0.06], [0.21, 0.22], [0.2, 0.25]], { mat: 'metal', color: '#2e2e2c', y: 0.78, seg: 16 })
    b.torus(0.2, 0.006, { mat: 'steel', color: '#3a3a3a', y: 1.02, rz: Math.PI / 2, arc: Math.PI })
    b.at({ x: 0.5, y: 0.0, z: 0.45 }, () => {
      for (let i = 0; i < 6; i++) b.box(0.42, 0.012, 0.012, { mat: 'metal', color: '#2a2a2a', y: 0.32, z: -0.15 + i * 0.06 })
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.015, 0.32, 0.015, { mat: 'metal', color: '#2a2a2a', x: sx * 0.2, y: 0.16, z: sz * 0.15 })
      b.lathe([[0.001, 0], [0.09, 0.0], [0.1, 0.1], [0.06, 0.15], [0.02, 0.17]], { mat: 'paint', color: '#3a5a7a', y: 0.33, seg: 12 })
      b.beam([0.08, 0.42, 0], [0.16, 0.48, 0], 0.02, 0.02, { mat: 'paint', color: '#3a5a7a', round: true })
    })
    // split-log seats round the fire, gap on the gate side
    const seats = [[0, -1.65, 0], [-1.65, 0, Math.PI / 2], [1.65, 0, Math.PI / 2], [-1.2, 1.2, -Math.PI / 4]]
    for (const [x, z, ry] of seats) logSeat(b, { x, z, ry, len: 1.5 })
    for (const [x, z, ry] of seats) {
      for (const k of [-0.42, 0.42]) {
        const sx = x + Math.cos(ry) * k
        const sz = z - Math.sin(ry) * k
        I.seats.push({ x: sx, z: sz, face: Math.atan2(-sx, -sz), y: 0.36 - 0.46 + 0.02 })
      }
    }
    campChairX(b, { x: 1.2, z: 1.25, ry: Math.PI + Math.PI / 4 + 0.3, color: '#7a3a2a' })
    I.seats.push({ x: 1.2, z: 1.2, face: Math.atan2(-1.2, -1.2), y: 0 })
    // a cooler, mugs, a radio, a guitar, firewood
    b.box(0.5, 0.32, 0.32, { mat: 'plastic', color: '#2a5a8a', x: 1.7, y: 0.16, z: -1.45, ry: 0.4, r: 0.03 })
    b.box(0.52, 0.06, 0.34, { mat: 'plastic', color: '#e8e4dc', x: 1.7, y: 0.34, z: -1.45, ry: 0.4, r: 0.02 })
    for (let i = 0; i < 3; i++) b.lathe([[0.035, 0], [0.04, 0.09], [0.038, 0.09]], { mat: 'gloss', color: pick(rnd, ['#e8e4dc', '#3a5a8a', '#a83a2a']), x: 1.62 + i * 0.09, y: 0.37, z: -1.42, seg: 10 })
    radioSet(b, { x: -1.65, y: 0.0, z: -1.55, ry: 0.6 })
    b.at({ x: -1.66, y: 0.32, z: 0.55, rx: -0.35, ry: 0.4 }, () => {
      b.sphere(0.17, { mat: 'wood', color: '#c8884a', sz: 0.35, y: 0.18 })
      b.sphere(0.13, { mat: 'wood', color: '#c8884a', sz: 0.35, y: 0.42 })
      b.cyl(0.04, 0.04, 0.005, { mat: 'plain', color: '#1a1410', y: 0.32, z: 0.06, rx: Math.PI / 2 })
      b.box(0.05, 0.5, 0.025, { mat: 'wood', color: '#3a2418', y: 0.75 })
      b.box(0.07, 0.14, 0.03, { mat: 'wood', color: '#3a2418', y: 1.06 })
    })
    firewood(b, { x: -1.55, z: -1.5, ry: Math.PI / 4, len: 0.9, h: 0.6, roof: false })
    // festoon lights on four poles over the fire circle
    const P = [[-1.85, -1.85], [1.85, -1.85], [1.85, 1.85], [-1.85, 1.85]]
    for (const [x, z] of P) pole(b, x, z, 2.7, { r: 0.04 })
    const lp = []
    for (let i = 0; i < 4; i++) lp.push(...stringLights(b, [[P[i][0], 2.6, P[i][1]], [P[(i + 1) % 4][0], 2.6, P[(i + 1) % 4][1]]], { sag: 0.18, spacing: 0.5 }))
    I.lights.push({ x: 0, y: 2.3, z: -1.85, color: '#ffc070', intensity: 2, dist: 7, when: 'night' }, { x: 0, y: 2.3, z: 1.85, color: '#ffc070', intensity: 2, dist: 7, when: 'night' })
  },

  // ============================================================ BUNKHOUSE
  bunkhouse(b, L, I) {
    const rnd = seeded(2100 + L)
    if (L === 1) {
      // two wall tents with cots, a clothesline, a lantern post, camp chairs
      for (const [tx, color] of [[-1.5, '#7c8158'], [1.5, '#b8a47c']]) {
        wallTent(b, I, { x: tx, z: -0.4, w: 2.4, d: 3.0, color })
        for (const s of [-0.5, 0.5]) {
          b.at({ x: tx + s, z: -0.55 }, () => {
            for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.beam([sx * 0.3, 0, sz * 0.9], [sx * 0.33, 0.35, sz * 0.8], 0.03, 0.03, { mat: 'metal', color: '#4a5a3a' })
            b.box(0.62, 0.05, 1.9, { mat: 'canvas', color: '#5a6040', y: 0.36 })
            b.cloth(0.6, 1.2, { mat: 'cloth', color: pick(rnd, ['#4a5a6a', '#6a3a3a', '#3a4a3a', '#5a5a6a']), y: 0.42, z: 0.25, sag: -0.06, segX: 4, segZ: 5, wrinkle: 0.08, seed: Math.floor(rnd() * 99) })
            b.box(0.4, 0.1, 0.25, { mat: 'cloth', color: '#e8e4dc', y: 0.44, z: -0.7, r: 0.04 })
          })
          I.beds.push({ x: tx + s, y: 0.42, z: -0.55, face: 0 })
        }
        crate(b, { x: tx, z: -1.6, w: 0.4, h: 0.35, d: 0.35, color: '#b8a888' })
      }
      clothesline(b, [-2.7, 1.7, 1.9], [2.7, 1.7, 1.9], { seed: 4 })
      lampPostX(b, { x: 0.05, z: 1.3, h: 2.2 })
      I.lights.push({ x: 0.45, y: 1.6, z: 1.3, color: '#ffb060', intensity: 2.2, dist: 7, when: 'night' })
      campChairX(b, { x: 2.3, z: 1.0, ry: Math.PI + 0.6, color: '#3a6a5a' })
      campChairX(b, { x: -0.6, z: 1.1, ry: Math.PI - 0.4, color: '#7a3a2a' })
      I.seats.push({ x: 2.3, z: 0.95, face: Math.PI + 0.6, y: 0 })
      return
    }
    if (L === 2) {
      // a log cabin with a porch: bunks inside, a stove pipe, firewood under the eaves
      const cw = 5.4
      const cd = 3.4
      const h = 2.4
      const z0 = -0.65
      b.at({ z: z0 }, () => {
        b.box(cw + 0.2, 0.3, cd + 0.2, { mat: 'concrete', color: '#9a968e', y: 0.15 })
        b.box(cw, 0.08, cd, { mat: 'planks', color: '#c8b494', y: 0.34 })
        // log walls: stacked logs with notched corners, door and window gaps
        const logs = Math.round(h / 0.24)
        for (let i = 0; i < logs; i++) {
          const y = 0.42 + i * 0.24
          for (const sz of [-1, 1]) {
            if (sz > 0 && y > 0.5 && y < 2.2) {
              for (const [x0, x1] of [[-cw / 2 - 0.15, -2.0], [-1.1, -0.48], [0.48, 1.1], [2.0, cw / 2 + 0.15]]) b.cyl(0.13, 0.13, x1 - x0, { mat: 'wood', color: shadeHex('#b88a5a', (rnd() - 0.5) * 0.12), x: (x0 + x1) / 2, y, z: (sz * cd) / 2, rz: Math.PI / 2, seg: 8 })
              if (y < 1.2 || y > 1.95) for (const [x0, x1] of [[-2.0, -1.1], [1.1, 2.0]]) b.cyl(0.13, 0.13, x1 - x0, { mat: 'wood', color: shadeHex('#b88a5a', (rnd() - 0.5) * 0.12), x: (x0 + x1) / 2, y, z: (sz * cd) / 2, rz: Math.PI / 2, seg: 8 })
            } else b.cyl(0.13, 0.13, cw + 0.3, { mat: 'wood', color: shadeHex('#b88a5a', (rnd() - 0.5) * 0.12), y, z: (sz * cd) / 2, rz: Math.PI / 2, seg: 8 })
          }
          for (const sx of [-1, 1]) b.cyl(0.13, 0.13, cd + 0.3, { mat: 'wood', color: shadeHex('#a87a4a', (rnd() - 0.5) * 0.12), x: (sx * cw) / 2, y: y + 0.12, rx: Math.PI / 2, seg: 8 })
        }
        for (const x of [-1.55, 1.55]) windowFrame(b, { x, y: 1.58, z: cd / 2 + 0.06, w: 0.8, h: 0.68, shutters: '#5a6a4a' })
        doorX(b, { z: cd / 2 + 0.04, y: 0.38, w: 0.92, h: 1.95, color: '#6a4a32' })
        roof(b, I, (r) => gableRoofX(r, { w: cw + 0.3, d: cd, y: h + 0.4, rise: 1.15, over: 0.4, mat: 'roofmetal', color: '#6a3a2a', gableColor: '#a88a68' }))
        b.cyl(0.08, 0.08, 1.7, { mat: 'metal', color: '#3a3a3a', x: 1.8, y: h + 1.4, z: -0.6, seg: 10 })
        b.cyl(0.14, 0.1, 0.12, { mat: 'metal', color: '#3a3a3a', x: 1.8, y: h + 2.3, z: -0.6, seg: 10 })
        I.emitters.push({ kind: 'chimney', x: 1.8, y: h + 2.4, z: z0 - 0.6, when: 'night' })
        // inside: three bunk beds and a stove
        for (let i = 0; i < 3; i++) bunkBed(b, { x: -1.7 + i * 1.7, y: 0.38, z: -0.55, ry: 0, rnd, frame: '#6a5a4a' })
        b.cyl(0.25, 0.28, 0.7, { mat: 'metal', color: '#2a2a2a', x: 1.8, y: 0.73, z: -0.6, seg: 12 })
      })
      // porch with a bench, a rocker and firewood
      deckX(b, cw, 1.3, 0.34, { z: z0 + cd / 2 + 0.65, color: '#c8b494' })
      for (const sx of [-1, 1]) b.box(0.12, 2.3, 0.12, { mat: 'bark', color: '#7a5a3a', x: sx * (cw / 2 - 0.1), y: 1.45, z: z0 + cd / 2 + 1.2 })
      b.box(cw + 0.2, 0.025, 1.6, { mat: 'corrugated', color: '#8a8478', y: 2.6, z: z0 + cd / 2 + 0.75, rx: 0.18 })
      benchX(b, { x: -1.5, y: 0.34, z: z0 + cd / 2 + 0.35, len: 1.4 })
      I.seats.push({ x: -1.85, z: z0 + cd / 2 + 0.4, face: 0, y: 0.34 }, { x: -1.15, z: z0 + cd / 2 + 0.4, face: 0, y: 0.34 })
      lantern(b, { x: 0.65, y: 2.05, z: z0 + cd / 2 + 1.1 })
      I.lights.push({ x: 0.65, y: 2.15, z: z0 + cd / 2 + 1.1, color: '#ffb060', intensity: 2.5, dist: 8, when: 'night' })
      barrel(b, { x: cw / 2 + 0.25, z: z0 - cd / 2 + 0.4, color: '#3a5a7a', open: true, contents: '#3a5a6a', fill: 0.9 })
      firewood(b, { x: -cw / 2 - 0.15, z: z0 - 0.2, ry: Math.PI / 2, len: 1.8, h: 1.0 })
      for (let i = 0; i < 3; i++) I.beds.push({ x: -1.7 + i * 1.7, y: 0.85, z: z0 - 0.55, face: 0 }, { x: -1.7 + i * 1.7, y: 1.77, z: z0 - 0.55, face: 0 })
      return
    }
    // L3: a two-storey barracks: block ground floor, timber upper floor with
    // an outside stair and balcony, bunks on both floors
    const cw = 5.6
    const cd = 3.3
    const z0 = -0.75
    const fh = 2.6
    b.at({ z: z0 }, () => {
      slab(b, I, { w: cw + 0.3, d: cd + 0.3, h: 0.15 })
      // ground floor: block walls
      b.box(cw, fh - 0.1, 0.2, { mat: 'concrete', color: '#b8b4aa', y: 0.15 + (fh - 0.1) / 2, z: -cd / 2 + 0.1 })
      for (const sx of [-1, 1]) b.box(0.2, fh - 0.1, cd, { mat: 'concrete', color: '#b8b4aa', x: sx * (cw / 2 - 0.1), y: 0.15 + (fh - 0.1) / 2 })
      for (const [x0, x1] of [[-cw / 2, -2.4], [-1.7, -0.5], [0.5, 1.7], [2.4, cw / 2]]) b.box(x1 - x0, fh - 0.1, 0.2, { mat: 'concrete', color: '#b8b4aa', x: (x0 + x1) / 2, y: 0.15 + (fh - 0.1) / 2, z: cd / 2 - 0.1 })
      for (const x of [-2.05, 2.05]) {
        b.box(0.7, 0.9, 0.2, { mat: 'concrete', color: '#b8b4aa', x, y: 0.6, z: cd / 2 - 0.1 })
        b.box(0.7, 0.4, 0.2, { mat: 'concrete', color: '#b8b4aa', x, y: 2.35, z: cd / 2 - 0.1 })
        windowFrame(b, { x, y: 1.55, z: cd / 2 + 0.01, w: 0.66, h: 0.8, frame: '#3a4a3a' })
      }
      for (const x of [-1.1, 1.1]) {
        b.box(1.2, 0.9, 0.2, { mat: 'concrete', color: '#b8b4aa', x, y: 0.6, z: cd / 2 - 0.1 })
        b.box(1.2, 0.4, 0.2, { mat: 'concrete', color: '#b8b4aa', x, y: 2.35, z: cd / 2 - 0.1 })
        windowFrame(b, { x, y: 1.55, z: cd / 2 + 0.01, w: 1.1, h: 0.8, frame: '#3a4a3a' })
      }
      b.box(1.0, 2.1, 0.1, { mat: 'paint', color: '#4a5a4a', y: 1.2, z: cd / 2 - 0.12 })
      // upper floor: timber framed, clad in siding
      b.box(cw + 0.1, 0.18, cd + 0.1, { mat: 'concrete', color: '#a8a49a', y: fh + 0.09 })
      b.box(cw, fh - 0.15, cd, { mat: 'siding', color: '#8a9a8a', y: fh + 0.18 + (fh - 0.15) / 2 })
      for (const x of [-2.05, -0.7, 0.7, 2.05]) windowFrame(b, { x, y: fh + 1.5, z: cd / 2 + 0.02, w: 0.7, h: 0.8, frame: '#e8e4dc' })
      for (const z of [-0.6, 0.6]) windowFrame(b, { x: cw / 2 + 0.02, y: fh + 1.5, z, w: 0.6, h: 0.75, ry: Math.PI / 2, frame: '#e8e4dc' })
      doorX(b, { x: -1.4, z: cd / 2 + 0.02, y: fh + 0.18, color: '#4a5a6a', w: 0.85 })
      roof(b, I, (r) => gableRoofX(r, { w: cw, d: cd, y: fh * 2 + 0.05, rise: 0.95, over: 0.3, mat: 'shingles', color: '#ffffff', gableColor: '#8a9a8a', gableMat: 'siding' }))
      b.at({ x: 1.6, y: fh * 2 + 0.6, z: -0.6, rx: -0.4 }, () => {
        b.box(1.2, 0.06, 0.9, { mat: 'metal', color: '#b8bcc0' })
        b.plane(1.15, 0.85, { material: solarMatX(), y: 0.031, rx: -Math.PI / 2 })
      })
      b.box(0.7, 0.45, 0.4, { mat: 'paint', color: '#d8d8d0', x: cw / 2 + 0.25, y: fh + 0.6, z: -0.8, r: 0.02 })
      I.emitters.push({ kind: 'chimney', x: -2, y: fh * 2 + 1.4, z: z0 - 0.5, when: 'night' })
      b.cyl(0.07, 0.07, 1.2, { mat: 'metal', color: '#4a4a4a', x: -2, y: fh * 2 + 0.8, z: -0.5, seg: 8 })
      // bunks inside, both floors
      for (let f = 0; f < 2; f++) for (let i = 0; i < (f ? 3 : 2); i++) {
        const x = f ? -1.75 + i * 1.75 : -1.8 + i * 3.6
        bunkBed(b, { x, y: 0.15 + f * (fh + 0.03), z: -0.5, rnd, frame: '#4a5a6a' })
      }
    })
    // balcony and the outside stair
    const bz = z0 + cd / 2 + 0.6
    b.box(cw, 0.1, 1.2, { mat: 'planks', color: '#a8957a', y: 0.15 + fh, z: bz })
    for (const x of [-cw / 2 + 0.1, -0.9, 0.9, cw / 2 - 0.1]) b.box(0.12, fh + 0.1, 0.12, { mat: 'paint', color: '#4a5a4a', x, y: (fh + 0.25) / 2, z: bz + 0.5 })
    for (let i = 0; i <= 22; i++) b.box(0.03, 0.9, 0.03, { mat: 'paint', color: '#4a5a4a', x: -cw / 2 + 0.12 + i * ((cw - 0.24) / 22), y: 0.15 + fh + 0.5, z: bz + 0.56 })
    b.box(cw, 0.06, 0.08, { mat: 'paint', color: '#4a5a4a', y: 0.15 + fh + 0.97, z: bz + 0.56 })
    const steps = 12
    for (let i = 0; i < steps; i++) b.box(0.9, 0.05, 0.3, { mat: 'metal', color: '#7a7e78', x: cw / 2 - 0.5, y: 0.2 + (i * fh) / steps, z: bz + 1.75 - i * 0.12 - 0.3 })
    for (const dx of [-0.05, -0.95]) b.beam([cw / 2 + dx, 0.1, bz + 1.6], [cw / 2 + dx, fh + 0.15, bz + 0.2], 0.06, 0.2, { mat: 'paint', color: '#4a5a4a' })
    b.beam([cw / 2 - 0.02, 1.0, bz + 1.6], [cw / 2 - 0.02, fh + 1.05, bz + 0.2], 0.04, 0.04, { mat: 'paint', color: '#4a5a4a', round: true })
    for (const x of [-2.05, 0.7]) {
      b.box(0.7, 0.18, 0.2, { mat: 'wood', color: '#6a4a32', x, y: 0.15 + fh + 0.12, z: bz + 0.45 })
      for (let k = 0; k < 4; k++) b.ico(0.1, { mat: 'leaf', color: k % 2 ? '#c83a5a' : '#5a8a3e', x: x - 0.25 + k * 0.17, y: 0.15 + fh + 0.28, z: bz + 0.45, detail: 0 })
    }
    stringLights(b, [[-cw / 2 + 0.1, fh + 0.0, bz + 0.5], [cw / 2 - 0.1, fh + 0.0, bz + 0.5]], { sag: 0.12, spacing: 0.45 })
    I.lights.push({ x: 0, y: fh - 0.2, z: bz + 0.5, color: '#ffc070', intensity: 3, dist: 9, when: 'night' })
    benchX(b, { x: -1.2, z: bz + 0.1, len: 1.4 })
    I.seats.push({ x: -1.5, z: bz + 0.15, face: 0, y: 0 }, { x: -0.9, z: bz + 0.15, face: 0, y: 0 })
    for (let f = 0; f < 2; f++) for (let i = 0; i < (f ? 3 : 2); i++) {
      const x = f ? -1.75 + i * 1.75 : -1.8 + i * 3.6
      const y0 = 0.15 + f * (fh + 0.03)
      I.beds.push({ x, y: y0 + 0.47, z: z0 - 0.5, face: 0 }, { x, y: y0 + 1.39, z: z0 - 0.5, face: 0 })
    }
  },

  // ============================================================ STORAGE DEPOT
  storage(b, L, I) {
    const rnd = seeded(2200 + L)
    if (L === 1) {
      // stacks of salvage under a tarp lean-to
      canopy(b, I, { x: 0, z: -0.3, w: 4.4, d: 3.0, hf: 2.3, hb: 1.9, color: '#3f6a9a', seed: 7 })
      pallet(b, { x: -1.3, z: -0.8 })
      for (let i = 0; i < 3; i++) crate(b, { x: -1.6 + (i % 2) * 0.62, y: 0.13 + Math.floor(i / 2) * 0.5, z: -0.8 + (i % 2) * 0.05, ry: i * 0.1 })
      pallet(b, { x: 0.1, z: -0.8 })
      for (let i = 0; i < 6; i++) sack(b, { x: -0.1 + (i % 2) * 0.45, y: 0.13 + Math.floor(i / 2) * 0.24, z: -0.8, ry: Math.PI / 2 + (i % 3) * 0.05, color: i % 3 ? '#d8c8a0' : '#c8b48c' })
      barrel(b, { x: 1.4, z: -1.0, color: '#3a5878' })
      barrel(b, { x: 1.55, z: -0.35, color: '#7a3a2a' })
      jerrycan(b, { x: 0.9, z: 0.4, ry: 0.4 })
      crate(b, { x: 1.4, z: 0.6, ry: -0.2, w: 0.55, h: 0.45, d: 0.55 })
      for (let i = 0; i < 6; i++) b.cyl(0.04, 0.04, 0.12, { mat: 'paint', color: pick(rnd, ['#c8302a', '#d8a020', '#3a6a3a', '#8a8e92']), x: -0.6 + (i % 3) * 0.1, y: 0.06, z: 0.55 + Math.floor(i / 3) * 0.1, seg: 10 })
      signBoard(b, 'STORES', { x: 0, y: 1.25, z: 1.0, w: 0.9, h: 0.24, bg: '#2a2e26' })
      for (const x of [-0.42, 0.42]) b.box(0.05, 1.3, 0.05, { mat: 'wood', color: '#8a7050', x, y: 0.65, z: 1.02 })
      return
    }
    if (L === 2) {
      // an open-front shed full of shelving, a hand truck, a scale
      timberShed(b, I, { w: 4.6, d: 3.2, seed: 91, leftOpen: 0.0 })
      for (let i = 0; i < 3; i++) shelf(b, { x: -1.5 + i * 1.5, z: -1.3, w: 1.35, h: 2.0, d: 0.5, seed: 30 + i, kind: i === 1 ? 'jars' : 'mixed', fill: 0.9 })
      shelf(b, { x: -2.0, z: 0.0, ry: Math.PI / 2, w: 1.4, h: 1.8, d: 0.45, seed: 40, kind: 'boxes' })
      b.at({ x: 1.6, z: 0.9, ry: -0.3 }, () => {
        for (const sx of [-1, 1]) b.box(0.03, 1.2, 0.03, { mat: 'paint', color: '#c83a2a', x: sx * 0.2, y: 0.6, z: -0.1, rx: 0.15 })
        b.box(0.45, 0.02, 0.25, { mat: 'metal', color: '#8a8e92', y: 0.02 })
        for (const sx of [-1, 1]) b.torus(0.1, 0.035, { mat: 'rubber', color: '#1a1a1a', x: sx * 0.26, y: 0.1, z: -0.2, ry: Math.PI / 2 })
        sack(b, { y: 0.03, w: 0.38, d: 0.55, h: 0.22 })
        sack(b, { y: 0.25, w: 0.38, d: 0.55, h: 0.22, color: '#c8b48c' })
      })
      b.at({ x: 0.2, z: 0.55 }, () => {
        b.box(0.6, 0.08, 0.5, { mat: 'metal', color: '#4a4e52', y: 0.12 })
        b.box(0.05, 0.9, 0.05, { mat: 'metal', color: '#4a4e52', y: 0.55, z: -0.22 })
        b.cyl(0.14, 0.14, 0.05, { mat: 'paint', color: '#e8e4d8', y: 1.0, z: -0.2, rx: Math.PI / 2, seg: 14 })
        crate(b, { y: 0.16, w: 0.45, h: 0.35, d: 0.4, color: '#c8b898' })
      })
      for (let i = 0; i < 4; i++) barrel(b, { x: 1.55 + (i % 2) * 0.6 - 0.3, z: -0.25 + Math.floor(i / 2) * 0.62 - 0.3, color: pick(rnd, ['#3a5878', '#7a3a2a', '#3a6a3a']) })
      hangBulb(b, I, 0.0, 2.3, -0.3)
      return
    }
    // L3: a converted shipping container depot with pallet racking and a forklift
    slab(b, I, { h: 0.12, seed: 37 })
    mergeModel(b, containerModelX({ seed: 3, color: '#2f6a8a', open: true }), { x: -0.2, y: 0.12, z: -0.9, ry: Math.PI / 2 })
    for (let i = 0; i < 3; i++) shelf(b, { x: -1.6 + i * 1.3, z: -1.4, w: 1.15, h: 1.9, d: 0.4, seed: 60 + i, kind: i === 2 ? 'chem' : 'mixed', metal: true, fill: 0.95 })
    // pallet racking in front
    b.at({ x: 0.0, z: 1.05 }, () => {
      for (const x of [-2.2, -0.75, 0.75, 2.2]) for (const z of [-0.45, 0.45]) b.box(0.08, 2.2, 0.08, { mat: 'paint', color: '#2a5aa8', x, y: 1.1, z })
      for (const y of [0.15, 1.15]) for (const s of [-1, 1]) b.box(4.5, 0.1, 0.06, { mat: 'paint', color: '#d86a20', y, z: s * 0.45 })
      for (let bay = 0; bay < 3; bay++) for (let lvl = 0; lvl < 2; lvl++) {
        const x = -1.45 + bay * 1.45
        const y = 0.2 + lvl * 1.0
        pallet(b, { x, y, w: 1.2, d: 0.8 })
        if (rnd() < 0.85) {
          const k = rnd()
          if (k < 0.4) for (let c = 0; c < 4; c++) crate(b, { x: x - 0.28 + (c % 2) * 0.56, y: y + 0.13 + Math.floor(c / 2) * 0.38, w: 0.52, h: 0.36, d: 0.7, color: pick(rnd, ['#e8d8be', '#d0c0a4']) })
          else if (k < 0.7) for (let c = 0; c < 4; c++) barrel(b, { x: x - 0.28 + (c % 2) * 0.56, y: y + 0.13, z: (Math.floor(c / 2) - 0.5) * 0.4, r: 0.2, h: 0.7, color: pick(rnd, ['#3a5878', '#7a3a2a']) })
          else for (let c = 0; c < 6; c++) sack(b, { x: x - 0.25 + (c % 2) * 0.5, y: y + 0.13 + Math.floor(c / 2) * 0.22, ry: Math.PI / 2, w: 0.38, d: 0.6, h: 0.2, color: '#d8c8a0' })
        }
      }
    })
    mergeModel(b, forkliftModelX(), { x: 2.0, y: 0.12, z: -0.3, ry: -Math.PI / 2 - 0.3 })
    b.plane(4.4, 0.08, { material: stripeMat(), y: 0.122, z: 0.45, rx: -Math.PI / 2, shadow: false })
    for (const x of [-1.2, 1.2]) fluoroLight(b, { x, y: 2.45, z: 1.05, len: 1.2, chain: 0.1 })
    I.lights.push({ x: 0, y: 2.3, z: 1.0, color: '#fff0d0', intensity: 3, dist: 8, when: 'night' })
    for (const x of [-2.3, 2.3]) b.box(0.08, 2.5, 0.08, { mat: 'paint', color: '#4a4e52', x, y: 1.25, z: 1.9 })
    b.box(4.7, 0.08, 0.08, { mat: 'paint', color: '#4a4e52', y: 2.5, z: 1.9 })
  },

  // ============================================================ COOKHOUSE
  kitchen(b, L, I) {
    const rnd = seeded(2300 + L)
    if (L === 1) {
      // a stone hearth with a grill and a pot, a prep table, sacks and a barrel
      canopy(b, I, { x: 0, z: -0.2, w: 4.2, d: 2.8, hf: 2.3, hb: 1.95, color: '#4f6e4a', seed: 13 })
      b.at({ x: -1.1, z: -0.5 }, () => {
        for (let i = 0; i < 14; i++) {
          const a = (i / 14) * TAU
          rock(b, { x: Math.cos(a) * 0.55, z: Math.sin(a) * 0.4, s: 0.16, seed: 40 + i })
        }
        b.cyl(0.5, 0.5, 0.05, { mat: 'plain', color: '#2a2420', y: 0.04, sz: 0.75 })
        for (let i = 0; i < 9; i++) b.box(0.03, 0.02, 0.8, { mat: 'metal', color: '#2a2a2a', x: -0.4 + i * 0.1, y: 0.42 })
        for (const sz of [-1, 1]) b.box(0.9, 0.03, 0.03, { mat: 'metal', color: '#2a2a2a', y: 0.42, z: sz * 0.4 })
        b.lathe([[0.2, 0], [0.24, 0.04], [0.25, 0.3], [0.24, 0.32]], { mat: 'metal', color: '#3a3a38', y: 0.44, x: -0.12, seg: 16 })
        b.cyl(0.14, 0.14, 0.04, { mat: 'metal', color: '#2a2a2a', y: 0.46, x: 0.25, z: 0.1 })
        b.box(0.04, 0.02, 0.25, { mat: 'wood', color: '#5a3a2a', y: 0.47, x: 0.25, z: 0.33 })
        for (let i = 0; i < 6; i++) b.box(0.12, 0.05, 0.08, { mat: 'embers', color: '#ffffff', x: (rnd() - 0.5) * 0.6, y: 0.08, z: (rnd() - 0.5) * 0.4, ry: rnd() * 3, shadow: false })
      })
      I.flames.push({ x: -1.1, y: 0.05, z: -0.5, w: 0.6, h: 0.5, when: 'active' })
      I.lights.push({ x: -1.1, y: 0.7, z: -0.5, color: '#ff9040', intensity: 3, dist: 7, flicker: true, when: 'active' })
      I.emitters.push({ kind: 'steam', x: -1.22, y: 0.8, z: -0.5, when: 'active' }, { kind: 'smoke', x: -1.1, y: 0.6, z: -0.5, when: 'active' })
      table(b, { x: 0.9, z: -0.4, w: 1.5, d: 0.75, h: 0.82 })
      b.box(0.45, 0.03, 0.3, { mat: 'wood', color: '#e8d0a8', x: 0.7, y: 0.84, z: -0.4 })
      for (let i = 0; i < 6; i++) b.sphere(0.045, { mat: 'gloss', color: ['#d8401a', '#e8a020', '#6a9a3a', '#c8b060', '#8a3a6a', '#d8401a'][i], x: 1.1 + (i % 3) * 0.1, y: 0.87, z: -0.5 + Math.floor(i / 3) * 0.12, ws: 8, hs: 6 })
      b.box(0.2, 0.004, 0.03, { mat: 'chrome', color: '#d0d4d8', x: 0.75, y: 0.86, z: -0.3, ry: 0.3 })
      for (let i = 0; i < 3; i++) sack(b, { x: 1.6 + (i % 2) * 0.2, y: Math.floor(i / 2) * 0.24, z: 0.7, ry: 0.4 + i * 0.2, color: '#c8a870' })
      barrel(b, { x: -1.9, z: 0.8, color: '#3a5878', open: true, contents: '#3a5a6a' })
      bucket(b, { x: 0.2, z: 0.5, water: true })
      // pots and pans hanging from a rail
      b.box(1.6, 0.04, 0.04, { mat: 'metal', color: '#3a3a3a', x: 0.9, y: 1.75, z: -0.85 })
      for (let i = 0; i < 5; i++) {
        b.cyl(0.004, 0.004, 0.15, { mat: 'metal', color: '#3a3a3a', x: 0.3 + i * 0.3, y: 1.66, z: -0.85, seg: 4 })
        b.lathe([[0.001, 0], [0.1 + (i % 2) * 0.03, 0.0], [0.11 + (i % 2) * 0.03, 0.08]], { mat: 'metal', color: pick(rnd, ['#3a3a3a', '#b87a48', '#8a8e92']), x: 0.3 + i * 0.3, y: 1.48, z: -0.85, rx: Math.PI / 2, seg: 12 })
      }
      for (const x of [0.15, 1.65]) b.box(0.05, 1.8, 0.05, { mat: 'wood', color: '#8a7050', x, y: 0.9, z: -0.88 })
      I.spots.push({ x: -1.1, z: 0.25, face: FACE_BACK, anim: 'stir' }, { x: 0.9, z: 0.25, face: FACE_BACK, anim: 'saw' })
      return
    }
    if (L === 2) {
      // a pavilion: a wood range with a flue, shelves of jars, a long table
      b.box(4.8, 0.1, 3.8, { mat: 'planks', color: '#c8b494', y: 0.05 })
      frame(b, 4.6, 3.6, 2.6, { hFront: 2.85, braces: true, color: '#9a8466' })
      roof(b, I, (r) => corrRoof(r, { w: 5.0, d: 4.0, y: 2.98, drop: 0.4, gutter: true, seed: 22, ry: Math.PI }))
      boardWall(b, -2.3, 2.3, -1.8, 2.5, { y0: 0.1, seed: 23, color: '#b8a080' })
      b.at({ x: -1.2, z: -1.35 }, () => {
        b.box(1.4, 0.8, 0.7, { mat: 'metal', color: '#2a2a2c', y: 0.5 })
        b.box(1.44, 0.04, 0.74, { mat: 'metal', color: '#5a5a5a', y: 0.92 })
        for (const sx of [-0.35, 0.35]) b.cyl(0.18, 0.18, 0.02, { mat: 'metal', color: '#1a1a1a', x: sx, y: 0.94 })
        b.box(0.5, 0.35, 0.02, { mat: 'metal', color: '#1a1a1a', y: 0.45, z: 0.36 })
        b.box(0.3, 0.12, 0.02, { mat: 'embers', color: '#ffffff', y: 0.32, z: 0.37 })
        b.cyl(0.08, 0.08, 2.5, { mat: 'metal', color: '#3a3a3a', x: 0.5, y: 2.1, z: -0.15 })
        b.lathe([[0.22, 0], [0.26, 0.05], [0.26, 0.32], [0.25, 0.34]], { mat: 'metal', color: '#7a7a78', x: -0.35, y: 0.95, seg: 16 })
        b.lathe([[0.001, 0], [0.18, 0.0], [0.19, 0.06]], { mat: 'metal', color: '#2a2a2a', x: 0.35, y: 0.95, seg: 14 })
      })
      I.flames.push({ x: -1.2, y: 0.3, z: -0.97, w: 0.25, h: 0.12, when: 'active' })
      I.emitters.push({ kind: 'steam', x: -1.55, y: 1.35, z: -1.35, when: 'active' }, { kind: 'chimney', x: -0.7, y: 3.4, z: -1.5, when: 'active' })
      for (let i = 0; i < 2; i++) shelf(b, { x: 0.8 + i * 1.2, z: -1.55, w: 1.1, h: 1.8, d: 0.4, seed: 70 + i, kind: 'jars', fill: 0.9 })
      // long table and benches
      table(b, { x: 0.3, z: 0.8, w: 3.0, d: 0.9, h: 0.78, color: '#b89a70' })
      for (const s of [-1, 1]) benchX(b, { x: 0.3, z: 0.8 + s * 0.75, len: 2.8 })
      for (let i = 0; i < 6; i++) {
        const x = -0.9 + i * 0.48
        const z = 0.8 + (i % 2 ? 0.25 : -0.25)
        b.cyl(0.11, 0.09, 0.03, { mat: 'gloss', color: '#e8e4dc', x, y: 0.795, z, seg: 14 })
        b.lathe([[0.035, 0], [0.04, 0.09], [0.038, 0.09]], { mat: 'metal', color: '#8a8e92', x: x + 0.15, y: 0.78, z, seg: 10 })
      }
      for (let k = 0; k < 6; k++) I.seats.push({ x: -0.7 + (k % 3) * 1.0, z: 0.8 + (k < 3 ? -0.72 : 0.72), face: k < 3 ? 0 : Math.PI, y: 0 })
      // herbs drying
      for (let i = 0; i < 6; i++) {
        b.cyl(0.003, 0.003, 0.25, { mat: 'cloth', color: '#b49c74', x: -2.0 + i * 0.2, y: 2.3, z: 0.2, seg: 3 })
        b.cone(0.06, 0.25, { mat: 'leaf', color: pick(rnd, ['#6a8a4a', '#8a9a5a', '#5a7a3a']), x: -2.0 + i * 0.2, y: 2.1, z: 0.2, rx: Math.PI, seg: 6 })
      }
      decal(b, plateMat('TODAY: STEW', '#1a2a1a', '#e8e4d8'), 0.5, 0.3, { x: 2.0, y: 1.6, z: -1.77 })
      lantern(b, { x: 0.3, y: 2.15, z: 0.8 })
      I.lights.push({ x: 0.3, y: 2.25, z: 0.8, color: '#ffb060', intensity: 3, dist: 8, when: 'night' })
      I.spots.push({ x: -1.2, z: -0.6, face: FACE_BACK, anim: 'stir' })
      return
    }
    // L3: the mess hall: a brick cookhouse with a serving hatch, picnic tables,
    // umbrellas and festoon lights
    b.at({ z: -1.15 }, () => {
      b.box(4.8, 0.15, 1.7, { mat: 'concrete', color: '#a8a49c', y: 0.075 })
      b.box(4.8, 2.6, 0.2, { mat: 'brick', color: '#c89078', y: 1.4, z: -0.75 })
      for (const sx of [-1, 1]) b.box(0.2, 2.6, 1.7, { mat: 'brick', color: '#c89078', x: sx * 2.3, y: 1.4 })
      for (const [x0, x1] of [[-2.4, -1.6], [1.6, 2.4]]) b.box(x1 - x0, 2.6, 0.2, { mat: 'brick', color: '#c89078', x: (x0 + x1) / 2, y: 1.4, z: 0.75 })
      b.box(3.2, 0.9, 0.2, { mat: 'brick', color: '#c89078', y: 0.6, z: 0.75 })
      b.box(3.2, 0.6, 0.2, { mat: 'brick', color: '#c89078', y: 2.4, z: 0.75 })
      b.box(3.3, 0.06, 0.45, { mat: 'metal', color: '#b8bcc0', y: 1.08, z: 0.8 })
      roof(b, I, (r) => {
        r.box(5.2, 0.14, 2.2, { mat: 'roofTar', color: '#5a5a54', y: 2.78 })
        r.box(0.6, 0.6, 0.6, { mat: 'metal', color: '#9aa0a4', x: 1.5, y: 3.15, z: -0.2 })
      })
      // awning over the hatch
      for (let i = 0; i < 7; i++) b.box(0.48, 0.02, 0.9, { mat: 'canvas', color: i % 2 ? '#e8e4dc' : '#c8302a', x: -1.45 + i * 0.48, y: 2.25, z: 1.2, rx: 0.35 })
      // kitchen inside: range, steel counters, a fridge, pots
      b.box(1.6, 0.9, 0.6, { mat: 'metal', color: '#b8bcc0', x: -1.0, y: 0.6, z: -0.35 })
      b.box(1.0, 0.9, 0.6, { mat: 'metal', color: '#2a2a2c', x: 0.6, y: 0.6, z: -0.35 })
      b.box(0.7, 1.9, 0.6, { mat: 'metal', color: '#c8ccd0', x: 1.75, y: 1.1, z: -0.35 })
      for (let i = 0; i < 3; i++) b.lathe([[0.15, 0], [0.18, 0.04], [0.18, 0.25], [0.17, 0.26]], { mat: 'metal', color: '#9aa0a4', x: 0.35 + i * 0.25, y: 1.05, z: -0.35, seg: 14 })
      signBoard(b, 'MESS HALL', { x: 0, y: 2.55, z: 0.9, w: 1.6, h: 0.32, bg: '#2a2e26' })
    })
    I.emitters.push({ kind: 'steam', x: 0.6, y: 1.5, z: -1.5, when: 'active' }, { kind: 'chimney', x: 1.5, y: 3.5, z: -1.35, when: 'active' })
    for (const [x, z, ry] of [[-1.3, 0.85, 0], [1.3, 0.85, 0]]) {
      picnicTable(b, { x, z, ry })
      for (let k = 0; k < 4; k++) I.seats.push({ x: x - 0.45 + (k % 2) * 0.9, z: z + (k < 2 ? -0.68 : 0.68), face: k < 2 ? 0 : Math.PI, y: 0 })
    }
    umbrella(b, I, { x: -1.3, z: 0.85, r: 1.1, h: 2.3, c1: '#e8e4dc', c2: '#2a6a5a' })
    stringLights(b, [[-2.4, 2.4, 0.0], [-0.0, 2.6, 1.8], [2.4, 2.4, 0.0]], { sag: 0.15, spacing: 0.45 })
    for (const [x, z] of [[-2.4, 0.0], [2.4, 0.0], [0.0, 1.8]]) b.cyl(0.04, 0.04, 2.6, { mat: 'metal', color: '#4a4e52', x, y: 1.3, z, seg: 6 })
    I.lights.push({ x: 0, y: 2.2, z: 0.8, color: '#ffc070', intensity: 3, dist: 9, when: 'night' })
    I.spots.push({ x: -0.6, z: -0.8, face: FACE_BACK, anim: 'stir' }, { x: 0.6, z: -0.8, face: FACE_BACK, anim: 'stir' })
  },

  // ============================================================ INFIRMARY
  infirmary(b, L, I) {
    const rnd = seeded(2400 + L)
    if (L === 1) {
      // a medical tent with cots, a stretcher, a first aid box and an IV on a coat stand
      wallTent(b, I, { x: -0.8, z: -0.3, w: 3.0, d: 3.0, color: '#b0aca0', ridge: 2.2, cross: true })
      for (const x of [-1.45, -0.15]) {
        b.at({ x, z: -0.5 }, () => {
          for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.beam([sx * 0.3, 0, sz * 0.9], [sx * 0.33, 0.4, sz * 0.8], 0.03, 0.03, { mat: 'metal', color: '#6a6e72' })
          b.box(0.62, 0.05, 1.9, { mat: 'canvas', color: '#5a6040', y: 0.41 })
          b.cloth(0.6, 1.1, { mat: 'cloth', color: '#e8e4dc', y: 0.46, z: 0.3, sag: -0.06, segX: 4, segZ: 5, wrinkle: 0.06, seed: Math.floor(x * 10) })
          b.box(0.4, 0.1, 0.25, { mat: 'cloth', color: '#e8e4dc', y: 0.49, z: -0.7, r: 0.04 })
        })
        I.beds.push({ x, y: 0.46, z: -0.5, face: 0 })
      }
      b.at({ x: 1.6, z: 0.4, ry: 0.4 }, () => {
        for (const s of [-1, 1]) b.cyl(0.02, 0.02, 2.0, { mat: 'wood', color: '#8a7050', x: s * 0.28, y: 0.15, rx: Math.PI / 2, seg: 6 })
        b.box(0.56, 0.02, 1.7, { mat: 'canvas', color: '#5a6040', y: 0.16 })
      })
      b.at({ x: 1.5, z: -1.1 }, () => {
        b.box(0.45, 0.3, 0.3, { mat: 'paint', color: '#e8e4dc', y: 0.15 })
        b.box(0.12, 0.04, 0.002, { mat: 'paint', color: '#c8201a', y: 0.2, z: 0.151 })
        b.box(0.04, 0.12, 0.002, { mat: 'paint', color: '#c8201a', y: 0.2, z: 0.151 })
      })
      clothesline(b, [1.0, 1.6, 1.6], [2.8, 1.6, 0.6], { seed: 9 })
      bucket(b, { x: 0.8, z: 1.2 })
      lantern(b, { x: -0.8, y: 1.9, z: 1.0 })
      I.lights.push({ x: -0.8, y: 2.0, z: 1.0, color: '#ffb060', intensity: 2.5, dist: 7, when: 'night' })
      I.spots.push({ x: -0.8, z: 0.4, face: FACE_BACK, anim: 'search' })
      return
    }
    if (L === 2) {
      // a clinic shed: three hospital beds, a medicine cabinet, a desk
      timberShed(b, I, { w: 5.9, d: 3.9, seed: 95, leftOpen: 0.0, wall: '#d8d4c8', floor: '#d8d0c0' })
      for (let i = 0; i < 3; i++) {
        hospitalBed(b, { x: -2.0 + i * 1.6, z: -0.75, iv: i !== 1 })
        I.beds.push({ x: -2.0 + i * 1.6, y: 0.72, z: -0.75, face: 0 })
        if (i < 2) for (let k = 0; k < 6; k++) b.box(0.02, 1.7, 0.25, { mat: 'cloth', color: '#c8d8d8', x: -1.2 + i * 1.6, y: 0.95, z: -1.6 + k * 0.22, ry: (k % 2) * 0.25 })
      }
      b.at({ x: 2.2, z: -1.6 }, () => {
        b.box(0.9, 1.9, 0.4, { mat: 'paint', color: '#e8e8e0', y: 0.95 })
        for (const s of [-1, 1]) b.box(0.43, 1.0, 0.02, { mat: 'glass', color: '#d8e8e8', x: s * 0.22, y: 1.4, z: 0.21 })
        for (let i = 0; i < 9; i++) b.cyl(0.03, 0.03, 0.1, { mat: 'plastic', color: pick(rnd, ['#e8e4dc', '#d8a020', '#c8302a', '#3a6a9a']), x: -0.3 + (i % 3) * 0.3, y: 1.1 + Math.floor(i / 3) * 0.32, z: 0.05, seg: 8 })
        b.box(0.2, 0.06, 0.002, { mat: 'paint', color: '#c8201a', y: 1.75, z: 0.205 })
      })
      table(b, { x: 1.9, z: 0.6, w: 1.0, d: 0.6, h: 0.76, color: '#c8b898' })
      b.box(0.3, 0.02, 0.22, { mat: 'plain', color: '#e8e4d0', x: 1.8, y: 0.775, z: 0.6 })
      stool(b, { x: 1.9, z: 1.1 })
      gasBottle(b, { x: -2.6, z: 0.6, color: '#e8e8e0' })
      signBoard(b, '+ CLINIC', { x: 0, y: 2.3, z: -1.8, w: 1.2, h: 0.3, bg: '#e8e4dc' })
      hangBulb(b, I, 0.0, 2.35, -0.4)
      I.spots.push({ x: -0.4, z: 0.3, face: FACE_BACK, anim: 'search' }, { x: 1.9, z: 1.05, face: FACE_BACK, anim: 'type', sit: 0.5 })
      return
    }
    // L3: a field hospital in a prefab: an operating table under a surgical
    // lamp, monitors, five beds, a medical fridge, a red cross on the roof
    slab(b, I, { h: 0.12, color: '#d8dcd8', seed: 41 })
    steelShed(b, I, { w: 5.9, d: 3.9, h: 3.0, hb: 2.8, color: '#e8e8e0', clad: '#d8dcd8' })
    for (let i = 0; i < 4; i++) {
      hospitalBed(b, { x: -2.25 + i * 1.15, z: -0.95, iv: i % 2 === 0, sheet: '#8ab8c8' })
      I.beds.push({ x: -2.25 + i * 1.15, y: 0.72, z: -0.95, face: 0 })
    }
    // operating table with a lamp and a monitor
    b.at({ x: 1.0, z: 0.85 }, () => {
      b.box(0.6, 0.08, 1.9, { mat: 'paint', color: '#3a5a7a', y: 0.92 })
      b.cyl(0.12, 0.2, 0.85, { mat: 'chrome', color: '#c8ccd0', y: 0.45, seg: 12 })
      b.box(0.55, 0.05, 1.85, { mat: 'cloth', color: '#5a8aa8', y: 0.98 })
      b.cyl(0.04, 0.04, 2.3, { mat: 'chrome', color: '#c8ccd0', x: 0.6, y: 1.15, z: -1.0, seg: 8 })
      b.beam([0.6, 2.3, -1.0], [0.0, 2.2, -0.1], 0.04, 0.04, { mat: 'chrome', color: '#c8ccd0', round: true })
      b.lathe([[0.001, 0.1], [0.3, 0.05], [0.33, 0.0], [0.001, -0.02]], { mat: 'paint', color: '#e8e8e0', y: 2.1, z: -0.1, seg: 16 })
      b.cyl(0.25, 0.25, 0.01, { mat: 'glowWhite', color: '#ffffff', y: 2.09, z: -0.1, seg: 16 })
      b.at({ x: -0.7, z: -0.6 }, () => {
        b.cyl(0.03, 0.03, 1.3, { mat: 'chrome', color: '#c8ccd0', y: 0.65, seg: 6 })
        b.box(0.4, 0.3, 0.1, { mat: 'plastic', color: '#2a2a2a', y: 1.35, rx: -0.2 })
        b.box(0.34, 0.24, 0.01, { material: heartMat(), y: 1.35, z: 0.052, rx: -0.2 })
      })
    })
    I.lights.push({ x: 1.0, y: 2.0, z: 0.75, color: '#f0f8ff', intensity: 4, dist: 6, when: 'active' })
    b.at({ x: -2.3, z: 1.2 }, () => {
      b.box(0.7, 1.7, 0.6, { mat: 'paint', color: '#e8e8e0', y: 0.85 })
      b.box(0.6, 1.0, 0.01, { mat: 'glass', color: '#d8e8f0', y: 1.1, z: 0.301 })
      for (let i = 0; i < 6; i++) b.box(0.12, 0.08, 0.1, { mat: 'plastic', color: pick(rnd, ['#c8302a', '#3a6a9a', '#e8e4dc']), x: -0.18 + (i % 3) * 0.18, y: 0.8 + Math.floor(i / 3) * 0.3, z: 0.2 })
    })
    b.at({ x: -0.9, z: 1.35 }, () => {
      b.cyl(0.2, 0.2, 0.5, { mat: 'chrome', color: '#c8ccd0', y: 0.95, rz: Math.PI / 2, seg: 16 })
      b.box(0.7, 0.7, 0.5, { mat: 'paint', color: '#d8dcd8', y: 0.35 })
    })
    hospitalBed(b, { x: 2.35, z: -0.2, ry: Math.PI / 2, iv: false })
    I.beds.push({ x: 2.35, y: 0.72, z: -0.2, face: Math.PI / 2 })
    roof(b, I, (r) => {
      r.box(1.0, 0.02, 0.3, { mat: 'paint', color: '#c8201a', y: 3.02, z: -0.3, rx: 0.08 })
      r.box(0.3, 0.02, 1.0, { mat: 'paint', color: '#c8201a', y: 3.02, z: -0.3, rx: 0.08 })
    })
    I.spots.push({ x: 1.0, z: 0.1, face: 0, anim: 'search' }, { x: -1.0, z: 0.4, face: FACE_BACK, anim: 'search' })
  },

  // ============================================================ TRAINING YARD
  training(b, L, I) {
    const rnd = seeded(2500 + L)
    if (L === 1) {
      // hay-bale targets, a punching sack, a wooden dummy, a tyre to flip
      b.box(5.8, 0.02, 4.8, { mat: 'dirt', color: '#7a6450', y: 0.0 })
      for (let i = 0; i < 2; i++) {
        const x = 0.9 + i * 1.2
        for (const y of [0, 0.45]) hayBale(b, { x, y, z: -1.9 })
        decal(b, targetMat(), 0.32, 0.4, { x, y: 0.68, z: -1.65 })
      }
      // punching sack on a gallows frame
      b.at({ x: -1.9, z: -0.8 }, () => {
        b.box(0.12, 2.3, 0.12, { mat: 'wood', color: '#8a7050', y: 1.15 })
        b.box(1.0, 0.1, 0.1, { mat: 'wood', color: '#8a7050', x: 0.45, y: 2.25 })
        b.beam([0, 1.7, 0], [0.4, 2.2, 0], 0.07, 0.07, { mat: 'wood', color: '#8a7050' })
        b.pivot('bag', { x: 0.85, y: 2.2 }, (p) => {
          p.cyl(0.006, 0.006, 0.4, { mat: 'steel', color: '#5a5a5a', y: -0.2, seg: 4 })
          p.capsule(0.18, 0.55, { mat: 'cloth', color: '#7a5a3a', y: -0.82, seg: 12 })
        })
      })
      I.anims.push({ name: 'bag', kind: 'pump', axis: 'z', amp: 0.18, speed: 3, when: 'active' })
      // a wing-chun style dummy
      b.at({ x: -0.4, z: -1.0 }, () => {
        b.cyl(0.13, 0.13, 1.5, { mat: 'wood', color: '#8a5a3a', y: 0.95, seg: 12 })
        for (const [y, a] of [[1.35, 0.4], [1.35, -0.4], [1.05, 0]]) b.cyl(0.03, 0.03, 0.45, { mat: 'wood', color: '#6a4a2a', y, z: 0.25, ry: a, rx: Math.PI / 2 - 0.2, seg: 8 })
        for (const s of [-1, 1]) b.box(0.08, 0.6, 0.08, { mat: 'wood', color: '#8a7050', x: s * 0.4, y: 0.95, z: -0.05 })
        b.box(0.9, 0.08, 0.08, { mat: 'wood', color: '#8a7050', y: 1.7, z: -0.05 })
      })
      tireP(b, { x: 0.5, y: 0.11, z: 1.2, rx: Math.PI / 2 })
      tireP(b, { x: -1.5, y: 0.11, z: 1.4, rx: Math.PI / 2 })
      tireP(b, { x: -0.9, y: 0.11, z: 1.6, rx: Math.PI / 2 })
      // concrete-bucket barbell
      b.at({ x: 2.2, z: 1.0 }, () => {
        b.cyl(0.02, 0.02, 1.4, { mat: 'metal', color: '#6a6e72', y: 0.15, rz: Math.PI / 2, seg: 6 })
        for (const s of [-1, 1]) bucket(b, { x: s * 0.62, y: 0.0, color: '#c8c4bc' })
      })
      b.box(0.9, 0.05, 0.05, { mat: 'wood', color: '#c8a070', x: -2.2, y: 0.03, z: 1.8, ry: 0.4 })
      I.spots.push({ x: -1.05, z: -0.45, face: -Math.PI / 2, anim: 'punch', train: 'melee' }, { x: -0.4, z: -0.35, face: FACE_BACK, anim: 'swing', train: 'melee' }, { x: 0.9, z: 1.0, face: FACE_BACK, anim: 'aim', train: 'ranged' }, { x: 2.1, z: 1.0, face: FACE_BACK, anim: 'aim', train: 'ranged' })
      return
    }
    if (L === 2) {
      // a proper yard: a pull-up frame with bags, a sparring mat, a three-lane range
      b.box(5.8, 0.02, 4.8, { mat: 'gravel', color: '#9a8c78', y: 0.0 })
      b.at({ x: -1.6, z: -1.1 }, () => {
        for (const s of [-1, 1]) b.box(0.12, 2.5, 0.12, { mat: 'wood', color: '#8a7050', x: s * 1.0, y: 1.25 })
        b.box(2.2, 0.12, 0.12, { mat: 'wood', color: '#8a7050', y: 2.45 })
        b.cyl(0.02, 0.02, 0.8, { mat: 'metal', color: '#8a8e92', x: 0.45, y: 2.25, rz: Math.PI / 2, seg: 6 })
        for (const [x, name] of [[-0.55, 'bagA'], [0.0, 'bagB']]) {
          b.pivot(name, { x, y: 2.39 }, (p) => {
            p.cyl(0.006, 0.006, 0.35, { mat: 'steel', color: '#5a5a5a', y: -0.18, seg: 4 })
            p.capsule(0.17, 0.6, { mat: 'rubber', color: name === 'bagA' ? '#8a2a22' : '#2a2a2a', y: -0.8, seg: 12 })
          })
          I.anims.push({ name, kind: 'pump', axis: 'z', amp: 0.16, speed: 2.6 + (x < 0 ? 0.4 : 0), when: 'active' })
        }
      })
      b.box(2.0, 0.06, 2.0, { mat: 'plastic', color: '#2a4a7a', x: -1.6, y: 0.03, z: 0.9 })
      b.box(1.6, 0.065, 1.6, { mat: 'plastic', color: '#c8302a', x: -1.6, y: 0.03, z: 0.9 })
      // range lanes with dividers and targets on a berm
      b.box(3.0, 0.6, 0.8, { mat: 'dirt', color: '#6a5442', x: 1.1, y: 0.0, z: -1.95, r: 0.25 })
      for (let i = 0; i < 3; i++) {
        const x = 0.2 + i * 0.9
        b.box(0.04, 1.2, 0.04, { mat: 'wood', color: '#8a7050', x: x - 0.18, y: 0.6, z: -1.55 })
        b.box(0.04, 1.2, 0.04, { mat: 'wood', color: '#8a7050', x: x + 0.18, y: 0.6, z: -1.55 })
        decal(b, targetMat(), 0.34, 0.45, { x, y: 0.95, z: -1.53 })
        if (i < 2) b.box(0.04, 1.0, 1.5, { mat: 'wood', color: '#a8957a', x: x + 0.45, y: 0.5, z: 1.1 })
      }
      b.box(2.8, 0.06, 0.4, { mat: 'wood', color: '#a8957a', x: 1.1, y: 0.95, z: 1.6 })
      for (let i = 0; i < 3; i++) longGun(b, { x: 0.2 + i * 0.9, y: 0.99, z: 1.55, ry: Math.PI, kind: 'rifle' })
      gunRack(b, { x: 2.6, z: 0.4, ry: -Math.PI / 2, n: 3, seed: 12 })
      I.spots.push({ x: -2.15, z: -0.5, face: FACE_BACK, anim: 'punch', train: 'melee' }, { x: -1.6, z: -0.5, face: FACE_BACK, anim: 'punch', train: 'melee' }, { x: 0.2, z: 2.0, face: FACE_BACK, anim: 'aim', train: 'ranged' }, { x: 1.1, z: 2.0, face: FACE_BACK, anim: 'aim', train: 'ranged' })
      return
    }
    // L3: obstacle course and a covered range with pop-up targets
    b.box(5.8, 0.02, 4.8, { mat: 'gravel', color: '#9a8c78', y: 0.0 })
    // climbing wall with holds
    b.at({ x: -1.9, z: -1.6 }, () => {
      b.box(1.6, 2.4, 0.15, { mat: 'planks', color: '#a8957a', y: 1.2 })
      for (let i = 0; i < 18; i++) b.dodeca(0.05, { mat: 'plastic', color: pick(rnd, ['#c8302a', '#2a6ad8', '#d8a020', '#3a8a3a']), x: (rnd() - 0.5) * 1.4, y: 0.3 + rnd() * 2.0, z: 0.1 })
      for (const s of [-1, 1]) b.beam([s * 0.7, 2.4, -0.05], [s * 0.7, 0, -0.9], 0.08, 0.08, { mat: 'wood', color: '#8a7050' })
    })
    // cargo net frame
    b.at({ x: -0.3, z: -1.5 }, () => {
      for (const s of [-1, 1]) b.box(0.12, 2.6, 0.12, { mat: 'wood', color: '#8a7050', x: s * 0.7, y: 1.3 })
      b.box(1.5, 0.12, 0.12, { mat: 'wood', color: '#8a7050', y: 2.55 })
      for (let i = 0; i < 7; i++) b.rope([[-0.65 + i * 0.217, 2.5, 0], [-0.65 + i * 0.217, 0.05, 0.6]], 0.012, { mat: 'cloth', color: '#b49c74', sag: 0.05, steps: 6 })
      for (let k = 0; k < 6; k++) b.rope([[-0.65, 2.5 - k * 0.4, k * 0.1], [0.65, 2.5 - k * 0.4, k * 0.1]], 0.012, { mat: 'cloth', color: '#b49c74', sag: 0.05, steps: 6 })
    })
    // covered firing range with pop-up targets
    b.at({ x: 1.6, z: -0.2 }, () => {
      for (let i = 0; i < 3; i++) {
        const z = -1.2 + i * 0.75
        b.box(0.25, 0.08, 0.5, { mat: 'metal', color: '#4a4e52', x: -1.0 + 2.4, y: 0.04, z })
        b.pivot('pop' + i, { x: 1.4, y: 0.08, z }, (p) => {
          p.box(0.03, 0.6, 0.35, { mat: 'paint', color: '#8a8e72', y: 0.3 })
          p.plane(0.3, 0.4, { material: targetMat(), x: -0.02, y: 0.35, ry: -Math.PI / 2, shadow: false })
        })
        I.anims.push({ name: 'pop' + i, kind: 'pump', axis: 'z', amp: 0.7, speed: 1.2 + i * 0.3, when: 'active' })
      }
      b.box(0.5, 1.4, 2.4, { mat: 'dirt', color: '#6a5442', x: 1.95, y: 0.4, z: -0.45, r: 0.2 })
      for (let i = 0; i < 2; i++) b.box(2.0, 1.1, 0.04, { mat: 'concrete', color: '#a8a49c', x: 0.3, y: 0.55, z: -0.82 + i * 0.75 })
      b.box(0.5, 0.9, 2.4, { mat: 'concrete', color: '#a8a49c', x: -0.9, y: 0.45, z: -0.45 })
      sandbags(b, { x: -0.9, y: 0.9, z: -0.45, len: 2.2, rows: 1, ry: Math.PI / 2, seed: 9 })
    })
    gunRack(b, { x: 2.6, z: 1.6, ry: -Math.PI / 2, n: 4, metal: true, seed: 13 })
    // a sparring ring with corner posts
    b.at({ x: -1.4, z: 1.0 }, () => {
      b.box(2.2, 0.2, 2.2, { mat: 'plastic', color: '#2a4a7a', y: 0.1 })
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.cyl(0.04, 0.04, 1.1, { mat: 'paint', color: '#c8302a', x: sx * 1.0, y: 0.75, z: sz * 1.0, seg: 8 })
      for (const y of [0.55, 0.85, 1.15]) for (const [ax, az, bx, bz] of [[-1, -1, 1, -1], [1, -1, 1, 1], [1, 1, -1, 1], [-1, 1, -1, -1]]) b.rope([[ax, y, az], [bx, y, bz]], 0.015, { mat: 'cloth', color: '#e8e4dc', sag: 0.01, steps: 3 })
    })
    signBoard(b, 'RANGE', { x: 0.4, y: 1.9, z: 1.6, w: 0.9, h: 0.28, bg: '#c8302a' })
    for (const x of [0.0, 0.8]) b.box(0.05, 1.9, 0.05, { mat: 'metal', color: '#4a4e52', x, y: 0.95, z: 1.62 })
    I.spots.push({ x: -1.8, z: 0.9, y: 0.2, face: Math.PI / 2, anim: 'punch', train: 'melee' }, { x: -1.0, z: 0.9, y: 0.2, face: -Math.PI / 2, anim: 'punch', train: 'melee' }, { x: 0.35, z: -0.6, face: Math.PI / 2, anim: 'aim', train: 'ranged' }, { x: 0.35, z: 0.15, face: Math.PI / 2, anim: 'aim', train: 'ranged' })
  },


  // ============================================================ RADIO TOWER
  radio(b, L, I) {
    const rnd = seeded(2600 + L)
    if (L === 1) {
      // a salvaged mast with wire aerials, a set on a crate under a little tarp
      b.cyl(0.05, 0.07, 5.0, { mat: 'metal', color: '#8a8e92', y: 2.5, seg: 8 })
      for (let i = 0; i < 3; i++) {
        const a = (i / 3) * TAU + 0.3
        b.rope([[0, 4.6, 0], [Math.cos(a) * 1.5, 0.05, Math.sin(a) * 1.5]], 0.005, { mat: 'steel', color: '#5a5a5a', sag: 0.02, steps: 4 })
        b.box(0.04, 0.15, 0.04, { mat: 'metal', color: '#4a4a4a', x: Math.cos(a) * 1.5, y: 0.05, z: Math.sin(a) * 1.5 })
      }
      b.rope([[0, 4.9, 0], [-1.4, 3.0, 1.2]], 0.004, { mat: 'rubber', color: '#2a2a2a', sag: 0.08 })
      b.rope([[0, 4.9, 0], [1.4, 3.0, -1.2]], 0.004, { mat: 'rubber', color: '#2a2a2a', sag: 0.08 })
      canopy(b, I, { x: 0.3, z: 0.6, w: 1.6, d: 1.2, hf: 1.9, hb: 1.6, color: '#5a5a3a', seed: 3, guys: false })
      crate(b, { x: 0.3, z: 0.6, w: 0.6, h: 0.65, d: 0.45, color: '#b8a888' })
      radioSet(b, { x: 0.3, y: 0.66, z: 0.55 })
      carBattery(b, { x: -0.25, z: 0.55 })
      stool(b, { x: 0.3, z: 1.15 })
      cable(b, [[0, 1.0, 0.05], [0.2, 0.7, 0.4]], { r: 0.006, sag: 0.05 })
      I.spots.push({ x: 0.3, z: 1.15, face: FACE_BACK, anim: 'type', sit: 0.5 })
      return
    }
    // L2/L3: a lattice tower with a radio shack at its foot
    const H = L === 2 ? 7.5 : 10.5
    const lattice = (h, w0, w1) => {
      const n = Math.round(h / 1.0)
      for (const [sx, sz] of [[-1, -1], [1, -1], [1, 1], [-1, 1]]) b.beam([sx * w0, 0, sz * w0], [sx * w1, h, sz * w1], 0.06, 0.06, { mat: 'paint', color: '#c8302a' })
      for (let i = 0; i < n; i++) {
        const y0 = (i / n) * h
        const y1 = ((i + 1) / n) * h
        const wa = w0 + (w1 - w0) * (i / n)
        const wb = w0 + (w1 - w0) * ((i + 1) / n)
        for (const [ax, az, bx, bz] of [[-1, -1, 1, -1], [1, -1, 1, 1], [1, 1, -1, 1], [-1, 1, -1, -1]]) {
          b.beam([ax * wb, y1, az * wb], [bx * wb, y1, bz * wb], 0.03, 0.03, { mat: 'paint', color: i % 2 ? '#e8e4dc' : '#c8302a' })
          b.beam([ax * wa, y0, az * wa], [bx * wb, y1, bz * wb], 0.025, 0.025, { mat: 'metal', color: '#9aa0a4' })
        }
      }
    }
    b.at({ x: 0.55, z: -0.55 }, () => {
      for (const [sx, sz] of [[-1, -1], [1, -1], [1, 1], [-1, 1]]) b.box(0.3, 0.15, 0.3, { mat: 'concrete', color: '#a8a49c', x: sx * 0.45, y: 0.07, z: sz * 0.45 })
      lattice(H, 0.45, 0.18)
      // antennas: a yagi and whips
      b.cyl(0.03, 0.03, 1.5, { mat: 'metal', color: '#9aa0a4', y: H + 0.7, seg: 6 })
      b.at({ y: H - 0.4 }, () => {
        b.box(1.4, 0.04, 0.04, { mat: 'metal', color: '#c8ccd0', x: 0.6 })
        for (let i = 0; i < 6; i++) b.box(0.03, 0.03, 0.7 - i * 0.07, { mat: 'metal', color: '#c8ccd0', x: 0.1 + i * 0.22 })
      })
      b.cyl(0.04, 0.04, 0.12, { mat: 'glowRed', color: '#5a1410', y: H + 1.5, seg: 10 })
      if (L === 3) {
        b.pivot('dish', { y: H * 0.72 }, (p) => {
          p.box(0.12, 0.12, 0.3, { mat: 'metal', color: '#8a8e92', z: 0.2 })
          p.lathe([[0.001, 0], [0.4, 0.08], [0.6, 0.2], [0.62, 0.22]], { mat: 'paint', color: '#e8e8e0', z: 0.45, rx: Math.PI / 2, seg: 18 })
          p.lathe([[0.62, 0.22], [0.6, 0.2], [0.4, 0.08], [0.001, 0]], { mat: 'paint', color: '#c8c8c0', z: 0.45, rx: Math.PI / 2, seg: 18 })
          p.cyl(0.02, 0.02, 0.5, { mat: 'metal', color: '#8a8e92', z: 0.7, rx: Math.PI / 2, seg: 5 })
        })
        I.anims.push({ name: 'dish', kind: 'yaw', speed: 0.25, swing: 1.2, when: 'active' })
        for (const y of [H * 0.4, H * 0.85]) b.box(0.25, 0.4, 0.15, { mat: 'paint', color: '#d8d8d0', x: 0.2, y, z: 0.2 })
      }
      for (let i = 0; i < 3; i++) {
        const a = (i / 3) * TAU + 0.6
        b.rope([[0, H * 0.75, 0], [Math.cos(a) * 2.2, 0.05, Math.sin(a) * 2.2]], 0.006, { mat: 'steel', color: '#7a7a7a', sag: 0.03, steps: 4 })
      }
    })
    // the shack
    b.at({ x: -0.6, z: 0.55 }, () => {
      b.box(1.7, 2.1, 1.6, { mat: 'corrugated', color: L === 3 ? '#8a9aa0' : '#a8a49a', y: 1.05 })
      b.box(1.9, 0.08, 1.8, { mat: 'roofmetal', color: '#5a5a54', y: 2.15, rx: 0.05 })
      b.box(0.8, 0.5, 0.03, { mat: 'window', color: '#20262a', y: 1.35, z: 0.81 })
      b.box(0.86, 0.04, 0.06, { mat: 'paint', color: '#3a3a3a', y: 1.08, z: 0.82 })
      b.box(0.6, 0.4, 0.3, { mat: 'paint', color: '#3a4a3a', y: 1.05, z: 0.6 })
      b.box(0.04, 1.9, 0.75, { mat: 'paint', color: '#5a6a5a', x: 0.86, y: 0.95, z: 0.2 })
      b.box(0.5, 0.3, 0.3, { mat: 'paint', color: '#d8d8d0', x: 0.6, y: 1.9, z: -0.9 })
    })
    cable(b, [[0.55, 2.0, -0.55], [0.0, 1.9, 0.0], [-0.6, 2.1, 0.0]], { r: 0.02, sag: 0.1 })
    stool(b, { x: -0.6, z: 1.65 })
    I.lights.push({ x: -0.6, y: 1.5, z: 1.45, color: '#ffc070', intensity: 2, dist: 6, when: 'night' })
    I.spots.push({ x: -0.6, z: 1.65, face: FACE_BACK, anim: 'type', sit: 0.5 })
  },

  // ============================================================ WATCHTOWER
  watchtower(b, L, I) {
    const rnd = seeded(2700 + L)
    const H = [3.6, 5.0, 6.5][L - 1]
    I.platformY = H + (L === 3 ? 0.1 : 0.06)
    if (L < 3) {
      const leg = 1.15
      const c = L === 1 ? '#9a8a74' : '#8a6a4a'
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
        if (L === 1) b.beam([sx * (leg + 0.15), 0, sz * (leg + 0.15)], [sx * leg * 0.9, H + 1.1, sz * leg * 0.9], 0.16, 0.16, { mat: 'wood', color: '#a88a66', round: true })
        else b.beam([sx * (leg + 0.15), 0, sz * (leg + 0.15)], [sx * leg * 0.9, H + 1.1, sz * leg * 0.9], 0.18, 0.18, { mat: 'wood', color: c })
        b.box(0.4, 0.2, 0.4, { mat: 'concrete', color: '#9a968e', x: sx * (leg + 0.15), y: 0.1, z: sz * (leg + 0.15) })
      }
      const levels = L === 1 ? [1.2, 2.4] : [1.3, 2.6, 3.9]
      for (const y of levels) {
        const r = leg + 0.15 - (0.15 + leg * 0.1) * (y / (H + 1.1))
        for (const [ax, az, bx, bz] of [[-1, -1, 1, -1], [1, -1, 1, 1], [1, 1, -1, 1], [-1, 1, -1, -1]]) b.beam([ax * r, y, az * r], [bx * r, y, bz * r], 0.08, 0.12, { mat: 'wood', color: c })
        for (const [ax, az, bx, bz] of [[-1, -1, 1, -1], [1, 1, -1, 1], [1, -1, 1, 1], [-1, 1, -1, -1]]) b.beam([ax * r, y - 1.1, az * r], [bx * r, y, bz * r], 0.06, 0.1, { mat: 'wood', color: shadeHex(c, -0.1) })
      }
      b.box(2.9, 0.12, 2.9, { mat: 'planks', color: '#c8b494', y: H })
      for (const sx of [-1, 1]) b.box(3.0, 0.15, 0.12, { mat: 'wood', color: c, y: H - 0.12, z: sx * 1.42 })
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.1, 2.3, 0.1, { mat: 'wood', color: c, x: sx * 1.4, y: H + 1.15, z: sz * 1.4 })
      if (L === 1) {
        for (const [ax, az, len, ry] of [[0, -1.4, 2.9, 0], [0, 1.4, 2.9, 0], [-1.4, 0, 2.9, Math.PI / 2], [1.4, 0, 2.9, Math.PI / 2]]) {
          b.box(len, 0.1, 0.06, { mat: 'wood', color: c, x: ax, y: H + 1.0, z: az, ry })
          b.box(len, 0.1, 0.06, { mat: 'wood', color: c, x: ax, y: H + 0.55, z: az, ry })
        }
        // scrap sheets nailed on as a breastwork, a canvas roof
        for (const [x, z, ry] of [[0, -1.45, 0], [-1.45, 0, Math.PI / 2]]) b.box(2.6, 0.8, 0.03, { mat: 'corrugated', color: pick(rnd, ['#a8a098', '#9aa4a8']), x, y: H + 0.5, z, ry, rz: (rnd() - 0.5) * 0.05 })
        roof(b, I, (r) => r.cloth(3.2, 3.2, { mat: 'canvas', color: '#6a6e4a', corners: [H + 2.2, H + 2.2, H + 1.95, H + 1.95], sag: 0.12, y: 0 }))
        ladderX(b, { x: 0.3, z: 1.6, h: H + 0.9, lean: 0.18 })
        b.rope([[1.4, H + 1.9, 1.4], [1.4, H + 0.8, 1.4]], 0.004, { mat: 'cloth', color: '#b49c74', sag: 0 })
        lantern(b, { x: 1.4, y: H + 0.6, z: 1.4 })
        I.lights.push({ x: 1.4, y: H + 0.7, z: 1.4, color: '#ffb060', intensity: 2.5, dist: 9, when: 'night' })
      } else {
        sandbags(b, { x: 0, y: H + 0.06, z: -1.25, len: 2.6, rows: 4, seed: 3 })
        sandbags(b, { x: 0, y: H + 0.06, z: 1.25, len: 2.6, rows: 4, seed: 4 })
        sandbags(b, { x: -1.25, y: H + 0.06, z: 0, len: 2.0, rows: 4, ry: Math.PI / 2, seed: 5 })
        sandbags(b, { x: 1.25, y: H + 0.06, z: 0.35, len: 1.2, rows: 4, ry: Math.PI / 2, seed: 6 })
        roof(b, I, (r) => corrRoof(r, { w: 3.3, d: 3.3, y: H + 2.4, drop: 0.3 }))
        ladderX(b, { x: 1.62, z: -0.5, h: H + 0.9, lean: 0.15, ry: -Math.PI / 2 })
        lantern(b, { x: 0, y: H + 1.9, z: 0 })
        I.lights.push({ x: 0, y: H + 2.0, z: 0, color: '#ffb060', intensity: 3, dist: 9, when: 'night' })
        longGun(b, { x: 0.6, y: H + 0.68, z: -1.1, ry: Math.PI, kind: 'rifle', scope: true })
        ammoCan(b, { x: -0.8, y: H + 0.06, z: -0.8 })
        b.cyl(0.05, 0.04, 0.12, { mat: 'paint', color: '#2a2a2a', x: 0.9, y: H + 1.3, z: 1.0, rx: 0.4, seg: 10 })
      }
      I.spots.push({ x: -0.4, z: 0.5, y: I.platformY, face: Math.PI / 4, anim: 'lookout', tower: true }, { x: 0.5, z: -0.4, y: I.platformY, face: (-Math.PI / 4) * 3, anim: 'lookout', tower: true })
      return
    }
    // L3: a steel tower with an enclosed cab, a searchlight and switchback stairs
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.beam([sx * 1.25, 0, sz * 1.25], [sx * 1.05, H, sz * 1.05], 0.16, 0.16, { mat: 'paint', color: '#4a5048' })
    for (let y = 1.2; y < H; y += 1.6) {
      for (const [ax, az, bx, bz] of [[-1, -1, 1, -1], [1, -1, 1, 1], [1, 1, -1, 1], [-1, 1, -1, -1]]) {
        b.beam([ax * 1.18, y, az * 1.18], [bx * 1.18, y, bz * 1.18], 0.06, 0.06, { mat: 'metal', color: '#7a8078' })
        b.beam([ax * 1.2, y - 1.2, az * 1.2], [bx * 1.18, y, bz * 1.18], 0.04, 0.04, { mat: 'metal', color: '#7a8078' })
      }
    }
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.5, 0.3, 0.5, { mat: 'concrete', color: '#b8b4ac', x: sx * 1.25, y: 0.15, z: sz * 1.25 })
    b.box(3.0, 0.15, 3.0, { mat: 'metal', color: '#5a6058', y: H })
    b.box(3.0, 1.0, 3.0, { mat: 'corrugated', color: '#7a8470', y: H + 0.55 })
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.1, 2.2, 0.1, { mat: 'paint', color: '#4a5048', x: sx * 1.45, y: H + 1.1, z: sz * 1.45 })
    for (const [ax, az, ry] of [[0, 1.48, 0], [0, -1.48, 0], [1.48, 0, Math.PI / 2], [-1.48, 0, Math.PI / 2]]) {
      b.box(2.8, 0.9, 0.03, { mat: 'glass', color: '#d8e8e8', x: ax, y: H + 1.5, z: az, ry })
      for (let k = 0; k < 4; k++) b.box(0.03, 0.9, 0.04, { mat: 'paint', color: '#4a5048', x: ax + (az !== 0 ? -0.95 + k * 0.63 : 0), y: H + 1.5, z: az + (ax !== 0 ? -0.95 + k * 0.63 : 0), ry })
    }
    roof(b, I, (r) => {
      r.box(3.4, 0.12, 3.4, { mat: 'paint', color: '#4a5048', y: H + 2.25 })
      r.cone(2.2, 0.5, { mat: 'paint', color: '#4a5048', y: H + 2.55, seg: 4, ry: Math.PI / 4 })
    })
    b.pivot('search', { y: H + 2.75 }, (p) => {
      p.box(0.3, 0.2, 0.3, { mat: 'paint', color: '#2a2a2a', y: 0.1 })
      p.cyl(0.22, 0.26, 0.45, { mat: 'paint', color: '#2a2a2a', y: 0.35, z: 0.1, rx: Math.PI / 2 - 0.4 })
      p.cyl(0.2, 0.2, 0.02, { mat: 'glowWhite', color: '#ffffff', y: 0.45, z: 0.33, rx: Math.PI / 2 - 0.4 })
    })
    I.anims.push({ name: 'search', kind: 'yaw', speed: 0.35, swing: 1.4, when: 'night' })
    I.searchlight = { name: 'search', y: H + 3.1 }
    for (let i = 0; i < Math.floor(H / 0.3); i++) {
      const y = 0.3 + i * 0.3
      const flight = Math.floor(i / 7) % 2
      const k = i % 7
      b.box(0.8, 0.05, 0.3, { mat: 'metal', color: '#8a9088', x: flight ? 1.7 - k * 0.32 : -0.6 + k * 0.32, y, z: 1.65, ry: Math.PI / 2 })
    }
    for (let f = 0; f < Math.ceil(H / 2.1); f++) b.box(0.9, 0.05, 0.9, { mat: 'metal', color: '#8a9088', x: f % 2 ? -0.95 : 2.0, y: 2.1 + f * 2.1, z: 1.65 })
    b.cyl(0.03, 0.03, 1.2, { mat: 'metal', color: '#9aa0a4', x: 1.3, y: H + 2.8, z: -1.3, seg: 5 })
    b.box(0.6, 0.35, 0.03, { mat: 'paint', color: '#c8302a', x: 1.6, y: H + 3.2, z: -1.3 })
    I.spots.push({ x: -0.5, z: 0.5, y: I.platformY, face: Math.PI / 4, anim: 'lookout', tower: true }, { x: 0.5, z: -0.5, y: I.platformY, face: (-Math.PI / 4) * 3, anim: 'lookout', tower: true })
  },

  // ============================================================ AUTO-TURRET
  turret(b, L, I) {
    if (L === 1) {
      for (let i = 0; i < 3; i++) {
        const a = (i / 3) * TAU
        b.beam([Math.cos(a) * 0.6, 0, Math.sin(a) * 0.6], [0, 0.9, 0], 0.05, 0.05, { mat: 'metal', color: '#3a3e42', round: true })
        b.box(0.1, 0.02, 0.1, { mat: 'metal', color: '#2a2a2a', x: Math.cos(a) * 0.6, y: 0.01, z: Math.sin(a) * 0.6 })
      }
      ammoCan(b, { x: 0.5, z: 0.45, ry: 0.4 })
      carBattery(b, { x: -0.55, z: 0.45, ry: -0.3 })
      I.turretY = 0.95
    } else if (L === 2) {
      for (let i = 0; i < 8; i++) {
        const a = (i / 8) * TAU
        sandbags(b, { x: Math.cos(a) * 0.78, z: Math.sin(a) * 0.78, len: 0.6, rows: 3, ry: -a + Math.PI / 2, seed: i })
      }
      b.cyl(0.2, 0.25, 0.9, { mat: 'paint', color: '#4a5a4a', y: 0.45, seg: 12 })
      I.turretY = 0.95
    } else {
      b.cyl(0.85, 0.95, 0.5, { mat: 'concrete', color: '#a8a49c', y: 0.25, seg: 8 })
      b.cyl(0.4, 0.45, 0.6, { mat: 'paint', color: '#3a4a3a', y: 0.8, seg: 12 })
      for (let i = 0; i < 8; i++) b.box(0.08, 0.05, 0.08, { mat: 'metal', color: '#2a2a2a', x: Math.cos((i / 8) * TAU) * 0.42, y: 1.08, z: Math.sin((i / 8) * TAU) * 0.42 })
      I.turretY = 1.15
    }
    b.pivot('head', { y: I.turretY }, (p) => {
      if (L === 1) {
        // a hunting rifle on a servo, a webcam and a battery
        p.box(0.14, 0.12, 0.3, { mat: 'paint', color: '#2a2c2e', y: 0.06 })
        longGun(p, { y: 0.16, z: 0.1, kind: 'rifle', scope: true })
        p.box(0.06, 0.05, 0.06, { mat: 'plastic', color: '#1a1a1a', x: 0.1, y: 0.3, z: 0.1 })
        p.box(0.02, 0.02, 0.01, { mat: 'glowRed', color: '#ffffff', x: 0.1, y: 0.3, z: 0.135 })
        p.rope([[0.0, 0.05, -0.15], [-0.3, -0.4, 0.0]], 0.006, { mat: 'rubber', color: '#c8302a', sag: 0.05 })
      } else if (L === 2) {
        // a light machine gun behind a shield plate, an ammo box feeding it
        p.box(0.2, 0.14, 0.6, { mat: 'paint', color: '#2a2c2e', y: 0.1 })
        p.cyl(0.03, 0.03, 0.7, { mat: 'metal', color: '#3a3e42', y: 0.12, z: 0.6, rx: Math.PI / 2, seg: 10 })
        p.cyl(0.045, 0.045, 0.3, { mat: 'metal', color: '#2a2a2a', y: 0.12, z: 0.45, rx: Math.PI / 2, seg: 10 })
        p.box(0.7, 0.45, 0.04, { mat: 'paint', color: '#4a5a3a', y: 0.25, z: 0.28 })
        p.box(0.12, 0.05, 0.05, { mat: 'plain', color: '#0a0a0a', y: 0.22, z: 0.3 })
        p.box(0.2, 0.18, 0.14, { mat: 'paint', color: '#4e5a3a', x: -0.22, y: 0.05, z: 0.05 })
        p.box(0.06, 0.04, 0.03, { mat: 'glowRed', color: '#ffffff', x: 0.18, y: 0.32, z: 0.31 })
      } else {
        // an armoured mount with twin guns, a camera pod and a lamp
        p.cyl(0.38, 0.42, 0.35, { mat: 'paint', color: '#3a4a3a', y: 0.18, seg: 10 })
        p.box(0.6, 0.35, 0.5, { mat: 'paint', color: '#3a4a3a', y: 0.45, z: 0.05, r: 0.03 })
        for (const s of [-1, 1]) {
          p.cyl(0.035, 0.035, 0.9, { mat: 'metal', color: '#2a2a2a', x: s * 0.18, y: 0.45, z: 0.75, rx: Math.PI / 2, seg: 10 })
          p.cyl(0.05, 0.05, 0.15, { mat: 'metal', color: '#2a2a2a', x: s * 0.18, y: 0.45, z: 1.15, rx: Math.PI / 2, seg: 10 })
        }
        p.box(0.16, 0.14, 0.2, { mat: 'paint', color: '#1a1a1a', y: 0.7, z: 0.15 })
        p.cyl(0.04, 0.04, 0.02, { mat: 'glass', color: '#3a5a8a', y: 0.7, z: 0.26, rx: Math.PI / 2, seg: 10 })
        p.box(0.12, 0.1, 0.06, { mat: 'glowWhite', color: '#ffffff', x: 0.28, y: 0.62, z: 0.3 })
        p.box(0.03, 0.03, 0.01, { mat: 'glowRed', color: '#ffffff', x: -0.25, y: 0.62, z: 0.31 })
      }
    })
    I.anims.push({ name: 'head', kind: 'turret', when: 'always' })
    I.muzzle = { name: 'head', z: L === 3 ? 1.25 : L === 2 ? 1.0 : 0.85, y: L === 3 ? 0.45 : L === 2 ? 0.12 : 0.18 }
  },

  // ============================================================ FLOODLIGHT
  floodlight(b, L, I) {
    b.box(0.4, 0.25, 0.4, { mat: 'concrete', color: '#a8a49c', y: 0.12 })
    for (let i = 0; i < 4; i++) b.cyl(0.015, 0.015, 0.05, { mat: 'metal', color: '#5a5a5a', x: Math.cos(i * 1.57 + 0.78) * 0.14, y: 0.27, z: Math.sin(i * 1.57 + 0.78) * 0.14, seg: 6 })
    b.cyl(0.05, 0.07, 3.4, { mat: 'metal', color: '#7a8086', y: 1.9, seg: 10 })
    b.box(0.25, 0.35, 0.15, { mat: 'paint', color: '#5a6066', y: 1.2, z: 0.1 })
    b.plane(0.14, 0.07, { material: plateMat('400W', '#e8c020', '#1a1a1a', 'bolt'), y: 1.25, z: 0.176, shadow: false })
    cable(b, [[0, 1.05, 0.12], [0.1, 0.4, 0.3], [0.3, 0.02, 0.6]], { r: 0.012, sag: 0.05 })
    b.pivot('lamp', { y: 3.6 }, (p) => {
      p.box(0.75, 0.06, 0.06, { mat: 'metal', color: '#4a4e52' })
      for (const sx of [-1, 1]) {
        p.box(0.3, 0.28, 0.16, { mat: 'paint', color: '#2a2a2a', x: sx * 0.26, y: 0.1, z: 0.06, rx: 0.5 })
        for (let k = 0; k < 4; k++) p.box(0.3, 0.01, 0.05, { mat: 'metal', color: '#1a1a1a', x: sx * 0.26, y: 0.2 - k * 0.05, z: -0.02, rx: 0.5 })
        p.box(0.26, 0.24, 0.02, { mat: 'glowWhite', color: '#ffffff', x: sx * 0.26, y: 0.15, z: 0.14, rx: 0.5 })
      }
    })
    I.flood = { name: 'lamp', y: 3.65 }
  },

}

// shared with stationsHD2.js
export { steelShed, timberShed, cncMill, hangBulb, shavings, stains, pole }
