// Station models, part three: the priority splitter, the buffer hopper and
// the stores (crate stack, shed, warehouse). Stores report `I.stock`: pallet
// spots the camp fills with logs, scrap, sacks and drums as stock rises
// (see scenes/basestock.js). Same local frame as the other station models:
// footprint centred on the origin, the open side facing +z.
import { seeded } from './kit.js'
import { shadeHex, crate, barrel, sack, pallet, bulb, shelf, gauge, cinderBlocks, jerrycan, ladder, tireStack, carBattery, scrapPile, toolbox, corrRoof, hayBale } from './parts.js'
import { slab, boardWall, frame, roof } from './stationkit.js'
import { plateMat, stripeMat, decal, fluoroLight, beacon, canopy, elecPanel, conveyor, controlPanel, hopper as hopperBin } from './detail.js'
import { forkliftModel } from './vehicles.js'
import { beltNode } from './stations2.js'

const TAU = Math.PI * 2
const pick = (r, a) => a[Math.floor(r() * a.length)]

// Merge a built model group (vehicles, etc.) into a builder.
function mergeModel(b, g, o = {}) {
  g.updateMatrixWorld(true)
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    g.traverse((m) => {
      if (!m.isMesh) return
      const geo = m.geometry.clone()
      geo.applyMatrix4(m.matrixWorld)
      b.add(geo, { material: m.material, keepColor: true, shadow: m.castShadow })
    })
  })
}
// A row of rivets along x.
function rivets(b, x0, x1, y, z, n, color) {
  for (let i = 0; i < n; i++) b.sphere(0.014, { mat: 'steel', color, x: x0 + ((x1 - x0) * (i + 0.5)) / n, y, z, ws: 6, hs: 4 })
}

// ---------------------------------------------------------------- hopper
// A square tank on a steel cabinet. Belts meet the cabinet's hatches at
// waist height; the tank above is open at the top so you can see the goods
// rise and fall (pivot 'surface', tinted to what it holds), and a sight glass
// on the front shows the same level (pivot 'level').
function hopperModel(b, L, I) {
  const rnd = seeded(5100 + L)
  const body = L === 1 ? '#7a6e5e' : '#d8a820'
  const trim = L === 1 ? '#4e463c' : '#2a2a2a'
  b.box(1.96, 0.08, 1.96, { mat: 'concrete', color: '#a09c92', y: 0.04 })
  // the cabinet, with a hatch on each half of every side (belts plug in at 1 m)
  b.box(1.84, 1.22, 1.84, { mat: L === 1 ? 'rust' : 'paint', color: L === 1 ? '#ffffff' : '#4a5258', y: 0.08 + 0.61 })
  b.box(1.88, 0.06, 1.88, { mat: 'steel', color: '#6a7178', y: 1.31 })
  for (let k = 0; k < 4; k++) {
    const a = (k * Math.PI) / 2
    for (const off of [-0.5, 0.5]) {
      b.at({ x: Math.sin(a) * 0.925 + Math.cos(a) * off, y: 1.0, z: Math.cos(a) * 0.925 - Math.sin(a) * off, ry: a }, () => {
        b.box(0.46, 0.34, 0.03, { mat: 'steel', color: '#8a9298' })
        b.box(0.36, 0.24, 0.035, { mat: 'plain', color: '#121212' })
      })
    }
    // corner legs
    b.box(0.12, 1.3, 0.12, { mat: 'steel', color: trim, x: (k < 2 ? -1 : 1) * 0.88, y: 0.65, z: (k % 2 ? -1 : 1) * 0.88 })
  }
  // funnel up to the tank
  const fy0 = 1.34
  const fy1 = 1.8
  b.lathe([[0.42, fy0], [1.2, fy1], [1.22, fy1 + 0.01]], { mat: 'paint', color: shadeHex(body, -0.1), seg: 4, ry: Math.PI / 4 })
  // the tank walls: four panels with rivet seams, open at the top
  const ty0 = fy1
  const ty1 = 2.74
  const W = 1.7
  const th = ty1 - ty0
  for (let k = 0; k < 4; k++) {
    const a = (k * Math.PI) / 2
    b.at({ x: Math.sin(a) * (W / 2), y: ty0 + th / 2, z: Math.cos(a) * (W / 2), ry: a }, () => {
      if (L === 1) {
        // patched from scrap sheet: three tones
        for (let i = 0; i < 3; i++) b.box(W / 3 + 0.02, th, 0.04, { mat: i === 1 ? 'rust' : 'metal', color: i === 1 ? '#ffffff' : pick(rnd, ['#8a8478', '#7a7e80', '#9a8a70']), x: -W / 3 + (i * W) / 3, z: (i % 2) * 0.01 })
        for (const x of [-W / 6, W / 6]) for (let i = 0; i < 6; i++) b.sphere(0.014, { mat: 'steel', color: '#5a5a54', x, y: -th / 2 + 0.1 + i * 0.15, z: 0.03, ws: 6, hs: 4 })
      } else {
        b.box(W, th, 0.04, { mat: 'paint', color: body })
        b.box(W + 0.02, 0.08, 0.06, { mat: 'paint', color: trim, y: th / 2 - 0.04 })
        b.box(W + 0.02, 0.08, 0.06, { mat: 'paint', color: trim, y: -th / 2 + 0.04 })
        rivets(b, -W / 2 + 0.05, W / 2 - 0.05, th / 2 - 0.12, 0.025, 12, '#6a5a20')
      }
    })
  }
  // inner lining (dark walls and a floor, no lid), so the open top reads as
  // a tank and the goods show inside
  for (let k = 0; k < 4; k++) {
    const a = (k * Math.PI) / 2
    b.box(W - 0.1, th - 0.05, 0.02, { mat: 'plain', color: '#1a1a18', x: Math.sin(a) * (W / 2 - 0.06), y: ty0 + th / 2 + 0.02, z: Math.cos(a) * (W / 2 - 0.06), ry: a, shadow: false })
  }
  b.box(W - 0.1, 0.02, W - 0.1, { mat: 'plain', color: '#141412', y: ty0 + 0.03, shadow: false })
  // the rim and a walkway grate on one side
  for (let k = 0; k < 4; k++) {
    const a = (k * Math.PI) / 2
    b.box(W + 0.08, 0.06, 0.08, { mat: 'steel', color: '#8a9298', x: Math.sin(a) * (W / 2), y: ty1 + 0.03, z: Math.cos(a) * (W / 2), ry: a })
  }
  // the goods inside: a heap that rises with the fill (white, tinted live)
  b.pivot('surface', { y: ty0 + 0.06 }, (p) => {
    // grey-based so the tint reads in full sun, rough so it doesn't shine
    p.box(W - 0.14, 0.05, W - 0.14, { mat: 'gravel', color: '#8c8c8c', y: 0 })
    p.sphere(0.72, { mat: 'gravel', color: '#9a9a9a', sy: 0.18, y: 0.02, ws: 14, hs: 6, tl: Math.PI / 2 })
    for (let i = 0; i < 11; i++) p.box(0.14 + rnd() * 0.14, 0.07 + rnd() * 0.05, 0.12 + rnd() * 0.12, { mat: 'rust', color: pick(rnd, ['#a8a8a8', '#7a7a7a', '#909090']), x: (rnd() - 0.5) * 1.2, y: 0.06 + rnd() * 0.05, z: (rnd() - 0.5) * 1.2, ry: rnd() * TAU, rx: (rnd() - 0.5) * 0.4 })
  })
  I.anims.push({ name: 'surface', kind: 'fillY', amp: th - 0.14, tint: true, when: 'always' })
  // sight glass on the front
  b.at({ x: 0.5, z: W / 2 + 0.06 }, () => {
    b.box(0.16, th - 0.04, 0.04, { mat: 'steel', color: '#5a6066', y: ty0 + th / 2 })
    b.box(0.1, th - 0.14, 0.02, { mat: 'plain', color: '#101010', y: ty0 + th / 2, z: 0.02 })
    b.pivot('level', { y: ty0 + 0.07, z: 0.03 }, (p) => p.box(0.07, th - 0.14, 0.012, { mat: 'glowWhite', color: '#ffffff', y: (th - 0.14) / 2 }))
    I.anims.push({ name: 'level', kind: 'fill', axis: 'y', tint: true, when: 'always' })
    b.cyl(0.03, 0.03, th - 0.1, { mat: 'glass', color: '#e0f0f0', y: ty0 + th / 2, z: 0.045, seg: 10 })
    for (let i = 0; i <= 4; i++) b.box(0.05, 0.012, 0.01, { mat: 'paint', color: i === 4 ? '#e83a2a' : '#e8e4d8', x: -0.1, y: ty0 + 0.07 + (i * (th - 0.14)) / 4, z: 0.025 })
  })
  decal(b, plateMat(L === 1 ? 'HOPPER' : 'BUFFER 80', L === 1 ? '#d8c8a0' : '#1a1a1a', L === 1 ? '#2a2a2a' : '#f0c020'), 0.5, 0.2, { x: -0.35, y: ty0 + th * 0.6, z: W / 2 + 0.03 })
  // a ladder up the back to the rim
  ladder(b, { x: -0.4, z: -W / 2 - 0.2, h: ty1, ry: Math.PI, lean: 0.06, mat: 'steel', color: '#6a7178' })
  if (L === 1) {
    // a sack draped over the rim, a jerrycan propping the cabinet door
    sack(b, { x: 0.55, y: ty1 - 0.08, z: -W / 2 + 0.08, rz: -0.5, ry: Math.PI / 2, w: 0.3, h: 0.16, d: 0.5 })
    jerrycan(b, { x: 0.75, z: 1.05, ry: 0.3, color: '#5a6a3a' })
  } else {
    // vibrator motor on the funnel shakes the goods loose while it works
    b.pivot('vib', { x: 0, y: 1.6, z: 0.75 }, (p) => {
      p.box(0.32, 0.22, 0.18, { mat: 'paint', color: '#2a5a8a', r: 0.02 })
      p.cyl(0.08, 0.08, 0.06, { mat: 'steel', color: '#8a9298', z: 0.12, rx: Math.PI / 2, seg: 12 })
    })
    I.anims.push({ name: 'vib', kind: 'shake', amp: 0.015, when: 'active' })
    beacon(b, I, { x: 0.78, y: ty1 + 0.06, z: 0.78, name: 'beacon' })
    I.anims.push({ name: 'beacon', kind: 'spin', speed: 5, when: 'active' })
    elecPanel(b, { x: -0.6, y: 0.95, z: 0.93, conduit: 0.3 })
    b.plane(1.6, 0.08, { material: stripeMat(), y: 0.081, z: 0.97, rx: -Math.PI / 2, shadow: false })
  }
  I.lights.push({ x: 0.5, y: ty0 + 0.5, z: W / 2 + 0.2, color: '#ffe8a0', intensity: 0.6, dist: 2.5, when: 'night' })
}

// ---------------------------------------------------------------- stores
// Crate Stack: a pallet yard under a tarp. Mostly room for the stock.
function cratesModel(b, L, I) {
  const rnd = seeded(5200)
  b.box(1.94, 0.04, 1.94, { mat: 'gravel', color: '#b0a890', y: 0.02 })
  canopy(b, I, { x: 0.15, z: -0.1, w: 1.95, d: 1.7, hf: 2.05, hb: 1.75, color: '#5a7a4a', seed: 41 })
  // the corner posts and a stencilled board
  for (const [x, z] of [[-0.82, -0.95], [1.12, -0.95], [-0.82, 0.75], [1.12, 0.75]]) b.cyl(0.04, 0.05, 2.0, { mat: 'wood', color: '#9a8466', x, y: 1.0, z, seg: 7 })
  b.at({ x: -0.88, y: 0, z: 0.1, ry: Math.PI / 2 }, () => {
    b.box(0.9, 0.5, 0.03, { mat: 'wood', color: '#c8b494', y: 1.15 })
    decal(b, plateMat('STORES', '#c8b494', '#2a2a2a'), 0.6, 0.22, { y: 1.15, z: 0.02 })
  })
  // a few odds and ends that are always here
  crate(b, { x: -0.6, z: -0.62, w: 0.42, h: 0.36, d: 0.42, ry: 0.2, color: '#c8b48c' })
  b.cyl(0.11, 0.11, 0.26, { mat: 'paint', color: '#3a5878', x: -0.55, y: 0.13, z: 0.66, seg: 12 })
  // four pallet spots for the stock
  for (const [x, z] of [[0.45, -0.45], [0.45, 0.45], [-0.25, -0.05]]) pallet(b, { x, z, w: 0.82, d: 0.78, color: pick(rnd, ['#d8c8ae', '#c8b89e']) })
  I.stock = [
    { x: 0.45, y: 0.13, z: -0.45, w: 0.78, d: 0.74 },
    { x: 0.45, y: 0.13, z: 0.45, w: 0.78, d: 0.74 },
    { x: -0.25, y: 0.13, z: -0.05, w: 0.78, d: 0.74 },
  ]
}
// Storage Shed: plank walls, shelving at the back, a raised roller door at
// the front and floor space for stock.
function shedModel(b, L, I) {
  const rnd = seeded(5300)
  b.box(2.9, 0.1, 2.9, { mat: 'planks', color: '#c8b494', y: 0.05 })
  boardWall(b, -1.45, 1.45, -1.42, 2.4, { y0: 0.1, seed: 5301, color: '#b89870' })
  b.at({ ry: Math.PI / 2 }, () => boardWall(b, -1.45, 1.45, -1.42, 2.4, { y0: 0.1, seed: 5302, color: '#b89870' }))
  b.at({ ry: -Math.PI / 2 }, () => boardWall(b, -1.45, 1.45, -1.42, 2.4, { y0: 0.1, seed: 5303, color: '#b89870' }))
  frame(b, 2.85, 2.85, 2.5, { hFront: 2.7, braces: true, color: '#9a8466' })
  // front: a header beam and a rolled-up corrugated door
  b.box(2.9, 0.3, 0.12, { mat: 'wood', color: '#8a7050', y: 2.45, z: 1.4 })
  b.cyl(0.16, 0.16, 2.7, { mat: 'corrugated', color: '#9aa0a4', y: 2.2, z: 1.32, rz: Math.PI / 2, seg: 14 })
  for (const sx of [-1, 1]) b.box(0.06, 2.3, 0.08, { mat: 'steel', color: '#6a7178', x: sx * 1.36, y: 1.15, z: 1.38 })
  roof(b, I, (r) => {
    r.box(3.3, 0.04, 3.3, { mat: 'roofmetal', color: '#8a8e90', y: 2.78, rx: -0.12 })
    r.box(3.3, 0.1, 0.1, { mat: 'wood', color: '#6a5440', y: 2.62, z: 1.65 })
  })
  // shelving along the back wall
  shelf(b, { x: -0.7, z: -1.15, w: 1.3, h: 2.0, d: 0.45, seed: 5310, kind: 'boxes', fill: 0.85 })
  shelf(b, { x: 0.7, z: -1.15, w: 1.3, h: 2.0, d: 0.45, seed: 5311, kind: 'jars', fill: 0.8 })
  // hand truck by the door, a bulb
  b.at({ x: 1.15, z: 1.0, ry: -0.4 }, () => {
    for (const sx of [-1, 1]) b.box(0.03, 1.15, 0.03, { mat: 'paint', color: '#c83a2a', x: sx * 0.18, y: 0.58, z: -0.08, rx: 0.15 })
    b.box(0.4, 0.02, 0.22, { mat: 'metal', color: '#8a8e92', y: 0.02 })
    for (const sx of [-1, 1]) b.torus(0.09, 0.03, { mat: 'rubber', color: '#1a1a1a', x: sx * 0.24, y: 0.09, z: -0.18, ry: Math.PI / 2 })
  })
  bulb(b, { x: 0, y: 2.2, z: 0.2, r: 0.05 })
  I.lights.push({ x: 0, y: 2.1, z: 0.2, color: '#ffc880', intensity: 2.5, dist: 6, when: 'night' })
  for (const [x, z] of [[-0.85, 0.1], [0.15, 0.1], [-0.85, 0.95], [0.15, 0.95]]) pallet(b, { x, z, w: 0.86, d: 0.76, color: pick(rnd, ['#d8c8ae', '#c8b89e']) })
  I.stock = [
    { x: -0.85, y: 0.23, z: 0.1, w: 0.82, d: 0.72 },
    { x: 0.15, y: 0.23, z: 0.1, w: 0.82, d: 0.72 },
    { x: -0.85, y: 0.23, z: 0.95, w: 0.82, d: 0.72 },
    { x: 0.15, y: 0.23, z: 0.95, w: 0.82, d: 0.72 },
  ]
}
// Warehouse: a steel-framed hall, pallet racking down the back on two
// levels, a forklift and floor bays at the front.
function warehouseModel(b, L, I) {
  const W = 6.9
  const D = 4.9
  slab(b, I, { h: 0.12, seed: 5401 })
  const m = { mat: 'paint', color: '#3a5a7a' }
  const H = 3.6
  const xs = [-W / 2 + 0.1, -W / 6, W / 6, W / 2 - 0.1]
  for (const x of xs) {
    for (const z of [-D / 2 + 0.1, D / 2 - 0.1]) b.box(0.14, H, 0.14, { ...m, x, y: H / 2, z })
    b.box(0.12, 0.16, D, { ...m, x, y: H - 0.08 })
  }
  for (const z of [-D / 2 + 0.1, D / 2 - 0.1]) b.box(W, 0.16, 0.12, { ...m, y: H - 0.08, z })
  // clad back and sides
  const clad = (x0, x1, z, ry) =>
    b.at({ ry }, () => {
      const n = Math.round((x1 - x0) / 0.9)
      for (let i = 0; i < n; i++) b.box((x1 - x0) / n + 0.03, H - 0.3, 0.03, { mat: 'corrugated', color: pick(seeded(i + 54), ['#9aa0a4', '#a4a8aa', '#8e9498']), x: x0 + ((x1 - x0) / n) * (i + 0.5), y: (H - 0.3) / 2 + 0.12, z })
    })
  clad(-W / 2, W / 2, -D / 2 + 0.03, 0)
  clad(-D / 2, D / 2, -W / 2 + 0.03, Math.PI / 2)
  clad(-D / 2, D / 2, -W / 2 + 0.03, -Math.PI / 2)
  roof(b, I, (r) => {
    const n = 9
    for (let i = 0; i < n; i++) r.box(W / n + 0.05, 0.04, D + 0.5, { mat: 'corrugated', color: pick(seeded(i * 7 + 3), ['#a8aeb2', '#9aa0a4', '#b0b4b6']), x: -W / 2 + (W / n) * (i + 0.5), y: H + 0.12 + (i % 2) * 0.01 })
    for (let k = 0; k < 3; k++) r.box(W + 0.2, 0.08, 0.08, { mat: 'paint', color: '#3a5a7a', y: H + 0.06, z: -D / 2 + (D * (k + 0.5)) / 3 })
  })
  // pallet racking: three bays, two levels, along the back
  const rz = -D / 2 + 0.75
  for (const x of [-2.6, -0.9, 0.9, 2.6]) for (const z of [rz - 0.45, rz + 0.45]) b.box(0.08, 2.5, 0.08, { mat: 'paint', color: '#2a5aa8', x, y: 1.25, z })
  for (const y of [0.2, 1.3, 2.4]) for (const z of [rz - 0.45, rz + 0.45]) b.box(5.3, 0.1, 0.06, { mat: 'paint', color: '#e07020', y, z })
  I.stock = []
  for (const x of [-1.75, 0, 1.75])
    for (const y of [0.25, 1.35]) {
      pallet(b, { x, y, z: rz, w: 1.4, d: 0.86 })
      I.stock.push({ x, y: y + 0.13, z: rz, w: 1.36, d: 0.82 })
    }
  // floor bays at the front, painted lines
  for (const x of [-2.2, -0.7, 0.8]) {
    for (const z of [0.55, 1.6]) {
      b.box(1.3, 0.004, 0.03, { mat: 'plain', color: '#e8c030', x, y: 0.122, z: z - 0.5, ao: 0, shadow: false })
      pallet(b, { x, y: 0.12, z, w: 1.2, d: 0.9 })
      I.stock.push({ x, y: 0.25, z, w: 1.16, d: 0.86 })
    }
  }
  mergeModel(b, forkliftModel(), { x: 2.45, y: 0.12, z: 0.9, ry: -Math.PI / 2 - 0.2 })
  for (const x of [-2, 0, 2]) fluoroLight(b, { x, y: H - 0.5, z: 0.2, len: 1.4, chain: 0.3 })
  I.lights.push({ x: 0, y: H - 0.7, z: 0.2, color: '#fff0d0', intensity: 4, dist: 10, when: 'night' })
  decal(b, plateMat('WAREHOUSE 2', '#2a2a2a', '#e8e4d8'), 1.6, 0.4, { x: 0, y: H - 0.35, z: D / 2 - 0.02 })
  cinderBlocks(b, { x: -W / 2 + 0.6, z: D / 2 - 0.4, n: 4 })
  b.plane(W - 0.4, 0.1, { material: stripeMat(), y: 0.123, z: D / 2 - 0.2, rx: -Math.PI / 2, shadow: false })
}

// ---------------------------------------------------------------- recycler
// A shredder: junk goes into a hopper over two toothed rotors (pivot
// 'rotors'), what comes out rides a short belt past a magnet into sorting
// bins. Level 1 is a car engine bolted to a frame; level 2 a proper
// powered line with a control desk.
function shredder(b, I, o) {
  const c = o.color
  b.at({ x: o.x, z: o.z }, () => {
    // frame and housing
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.1, 1.1, 0.1, { mat: 'paint', color: '#3a3e42', x: sx * 0.55, y: 0.55, z: sz * 0.45 })
    b.box(1.2, 0.5, 1.0, { mat: 'paint', color: c, y: 1.25, r: 0.02 })
    b.box(1.24, 0.06, 1.04, { mat: 'paint', color: shadeHex(c, -0.25), y: 1.02 })
    // the throat: two rotors of cutting discs, seen through the top
    b.box(0.9, 0.04, 0.7, { mat: 'plain', color: '#141414', y: 1.51 })
    for (const sz of [-0.15, 0.15]) {
      b.pivot('rotor' + (sz < 0 ? 'A' : 'B'), { y: 1.42, z: sz }, (p) => {
        p.cyl(0.04, 0.04, 0.92, { mat: 'steel', color: '#5a5e62', rz: Math.PI / 2, seg: 8 })
        for (let i = 0; i < 8; i++) {
          const x = -0.4 + i * 0.115
          p.cyl(0.12, 0.12, 0.035, { mat: 'steel', color: '#8a9298', x, rz: Math.PI / 2, seg: 8 })
          for (let t = 0; t < 3; t++) p.box(0.034, 0.06, 0.05, { mat: 'steel', color: '#c8ccd0', x, y: Math.cos((t * TAU) / 3 + i) * 0.13, z: Math.sin((t * TAU) / 3 + i) * 0.13, rx: (t * TAU) / 3 + i })
        }
      })
      I.anims.push({ name: 'rotor' + (sz < 0 ? 'A' : 'B'), kind: 'spin', axis: 'x', speed: sz < 0 ? 6 : -6, when: 'active' })
    }
    // the feed hopper over it
    b.lathe([[0.38, 1.52], [0.72, 2.05], [0.74, 2.07]], { mat: 'metal', color: shadeHex(c, 0.05), seg: 4, ry: Math.PI / 4 })
    b.lathe([[0.72, 2.07], [0.7, 2.06], [0.39, 1.54]], { mat: 'plain', color: '#1a1a18', seg: 4, ry: Math.PI / 4 })
    // a chute out the front onto the belt
    b.box(0.5, 0.06, 0.5, { mat: 'metal', color: '#6a6e72', y: 0.86, z: 0.62, rx: 0.4 })
    decal(b, plateMat('DANGER · KEEP HANDS OUT', '#e8c020', '#1a1a1a'), 0.6, 0.12, { y: 1.3, z: 0.51 })
  })
  I.emitters.push({ kind: 'sparks', x: o.x, y: 1.55, z: o.z, when: 'active' }, { kind: 'dust', x: o.x, y: 1.6, z: o.z, when: 'active' })
}
function recyclerModel(b, L, I) {
  const rnd = seeded(5500 + L)
  if (L === 1) {
    b.box(4.9, 0.04, 3.9, { mat: 'gravel', color: '#a8a090', y: 0.02 })
    canopy(b, I, { x: -0.6, z: -0.4, w: 3.4, d: 2.6, hf: 2.7, hb: 2.4, color: '#7a6a4a', seed: 55 })
    shredder(b, I, { x: -1.1, z: -0.6, color: '#8a5a3a' })
    // a car engine on a stand drives it by a belt
    b.at({ x: -2.0, z: -0.6 }, () => {
      b.box(0.5, 0.4, 0.6, { mat: 'metal', color: '#4a4e52', y: 0.75, r: 0.03 })
      b.box(0.08, 0.6, 0.08, { mat: 'paint', color: '#2a2a2a', y: 0.3 })
      b.box(0.5, 0.06, 0.6, { mat: 'paint', color: '#3a3e42', y: 0.52 })
      b.pivot('fly', { x: 0.28, y: 0.75 }, (p) => p.cyl(0.16, 0.16, 0.06, { mat: 'steel', color: '#6a6e72', rz: Math.PI / 2, seg: 14 }))
      I.anims.push({ name: 'fly', kind: 'spin', axis: 'x', speed: 9, when: 'active' })
      jerrycan(b, { x: 0.1, z: 0.55, ry: 0.4 })
    })
    I.emitters.push({ kind: 'exhaust', x: -2.15, y: 1.0, z: -0.95, when: 'active' })
    // sorting bins: barrels cut in half
    for (const [i, col] of ['#5a6a78', '#8a5a2a', '#c8b48c'].entries()) barrel(b, { x: 0.2 + i * 0.62, z: 0.1, r: 0.27, h: 0.55, color: col, open: true, contents: ['#8a9298', '#c87a30', '#d8c8a0'][i], fill: 0.7 })
    conveyor(b, I, { x: 0.15, z: -0.75, len: 2.0, h: 0.62, seed: 5501, color: '#8a6a3a', items: (p, x, r) => p.box(0.12 + r() * 0.1, 0.05, 0.12 + r() * 0.1, { mat: 'metal', color: pick(r, ['#8a9298', '#7a6a5a', '#a87a40']), x, z: (r() - 0.5) * 0.2, ry: r() * 3 }), gap: 0.45 })
    // the junk waiting to go in
    tireStack(b, { x: 1.9, z: 1.2, n: 3 })
    tireStack(b, { x: 1.4, z: 1.45, n: 2 })
    for (let i = 0; i < 3; i++) carBattery(b, { x: -2.0 + i * 0.36, z: 1.4, ry: rnd() * 0.4 })
    scrapPile(b, { x: 1.7, z: -1.2, r: 0.7, n: 8, seed: 5502 })
    toolbox(b, { x: -0.3, z: 1.0, ry: 0.3 })
    I.spots.push({ x: -1.1, z: 0.35, face: Math.PI, anim: 'hammer' })
    return
  }
  slab(b, I, { h: 0.12, seed: 5510 })
  b.at({ y: 0.12 }, () => {
    shredder(b, I, { x: -1.15, z: -0.7, color: '#2a6a5a' })
    // magnet on a gantry over the out-belt
    for (const x of [-0.2, 1.8]) b.box(0.1, 2.3, 0.1, { mat: 'paint', color: '#d8a020', x, y: 1.15, z: -0.7 })
    b.box(2.1, 0.12, 0.12, { mat: 'paint', color: '#d8a020', x: 0.8, y: 2.3, z: -0.7 })
    b.pivot('magnet', { x: 0.8, y: 2.25, z: -0.7 }, (p) => {
      p.box(0.24, 0.1, 0.18, { mat: 'paint', color: '#3a3e42', y: -0.05 })
      p.cyl(0.006, 0.006, 0.7, { mat: 'steel', color: '#5a5a5a', y: -0.45, seg: 4 })
      p.cyl(0.22, 0.22, 0.12, { mat: 'paint', color: '#c83a2a', y: -0.86, seg: 16 })
    })
    I.anims.push({ name: 'magnet', kind: 'slide', axis: 'x', amp: 0.7, speed: 0.25, when: 'active' })
    conveyor(b, I, { x: 0.8, z: -0.7, len: 2.4, h: 0.62, seed: 5511, items: (p, x, r) => p.box(0.12 + r() * 0.1, 0.05, 0.12 + r() * 0.1, { mat: 'metal', color: pick(r, ['#8a9298', '#7a6a5a', '#a87a40', '#3a3a3a']), x, z: (r() - 0.5) * 0.2, ry: r() * 3 }), gap: 0.4 })
    // sorted bins along the front
    for (const [i, [col, txt]] of [['#5a7a9a', 'METAL'], ['#c87a30', 'PARTS'], ['#6aa060', 'WIRE'], ['#3a3a3a', 'RUBBER']].entries()) {
      b.at({ x: -0.2 + i * 0.75, z: 0.75 }, () => {
        b.box(0.66, 0.6, 0.6, { mat: 'paint', color: col, y: 0.3 })
        b.box(0.58, 0.02, 0.52, { mat: 'metal', color: '#8a8478', y: 0.55 })
        decal(b, plateMat(txt, '#e8e4d8', '#1a1a1a'), 0.4, 0.12, { y: 0.4, z: 0.301 })
      })
    }
    controlPanel(b, { x: -2.1, z: 0.6, ry: 0.4, seed: 5512 })
    hopperBin(b, { x: 1.9, z: 0.9, w: 0.6, h: 1.0, color: '#6a7a8a', fill: '#3a3a3a', fillMat: 'rubber' })
    tireStack(b, { x: 2.1, z: -1.5, n: 4 })
    for (let i = 0; i < 4; i++) carBattery(b, { x: -2.1 + (i % 2) * 0.36, z: -1.6 + Math.floor(i / 2) * 0.24 })
    elecPanel(b, { x: -2.35, y: 1.0, z: -1.3, ry: Math.PI / 2 })
    beacon(b, I, { x: -0.6, y: 2.12, z: -0.25, name: 'beacon' })
    I.anims.push({ name: 'beacon', kind: 'spin', speed: 5, when: 'active' })
  })
  fluoroLight(b, { x: 0.3, y: 2.6, z: 0.4, len: 1.4, chain: 0.15 })
  I.lights.push({ x: 0.3, y: 2.4, z: 0.4, color: '#fff0d0', intensity: 3, dist: 8, when: 'night' })
  I.spots.push({ x: -2.0, z: 1.0, face: Math.PI - 0.4, anim: 'type' })
}

// ---------------------------------------------------------------- livestock
// The animals and the fence are added live (scenes/baseanimals.js); the
// models are the houses, troughs and the run they wander (I.pen) and the
// door they go in by at night (I.door).
function strawFloor(b, rnd, x0, x1, z0, z1, n) {
  b.box(x1 - x0, 0.02, z1 - z0, { mat: 'dirt', color: '#8a7a58', x: (x0 + x1) / 2, y: 0.01, z: (z0 + z1) / 2 })
  for (let i = 0; i < n; i++) b.box(0.18 + rnd() * 0.2, 0.006, 0.012, { mat: 'plain', color: pick(rnd, ['#e0c878', '#c8b060', '#d8c070']), x: x0 + rnd() * (x1 - x0), y: 0.024, z: z0 + rnd() * (z1 - z0), ry: rnd() * TAU, ao: 0, shadow: false })
}
function trough(b, o) {
  b.at({ x: o.x, z: o.z, ry: o.ry || 0 }, () => {
    b.box(o.len || 1.0, 0.22, 0.32, { mat: 'wood', color: '#7a5a3a', y: 0.16 })
    b.box((o.len || 1.0) - 0.08, 0.02, 0.24, { mat: o.water ? 'water' : 'plain', color: o.water ? '#5a8aa8' : '#c8a860', y: 0.25 })
    for (const s of [-1, 1]) b.box(0.06, 0.1, 0.3, { mat: 'wood', color: '#5a4430', x: s * ((o.len || 1.0) / 2 - 0.06), y: 0.05 })
  })
}
function coopModel(b, L, I) {
  const rnd = seeded(5600 + L)
  strawFloor(b, rnd, -2.4, 2.4, -1.9, 1.9, 60)
  // the henhouse on legs, a ramp down, nest boxes on the side
  const hx = -1.35
  const hz = -1.25
  const hw = L === 1 ? 1.6 : 2.1
  const hd = 1.2
  b.at({ x: hx, z: hz }, () => {
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.08, 0.6, 0.08, { mat: 'wood', color: '#6a5440', x: sx * (hw / 2 - 0.05), y: 0.3, z: sz * (hd / 2 - 0.05) })
    b.box(hw, 0.06, hd, { mat: 'planks', color: '#b8a080', y: 0.6 })
    boardWall(b, -hw / 2, hw / 2, -hd / 2, 1.0, { y0: 0.62, seed: 5601, color: L === 1 ? '#a87a50' : '#b84a32' })
    boardWall(b, -hw / 2, hw / 2, hd / 2, 0.9, { y0: 0.62, seed: 5602, color: L === 1 ? '#a87a50' : '#b84a32', holes: [[-0.2, 0.2, 0, 0.36]] })
    b.at({ ry: Math.PI / 2 }, () => {
      boardWall(b, -hd / 2, hd / 2, -hw / 2, 0.95, { y0: 0.62, seed: 5603, color: L === 1 ? '#a87a50' : '#b84a32' })
      boardWall(b, -hd / 2, hd / 2, hw / 2, 0.95, { y0: 0.62, seed: 5604, color: L === 1 ? '#a87a50' : '#b84a32' })
    })
    roof(b, I, (r) => {
      r.box(hw + 0.3, 0.04, hd + 0.4, { mat: L === 1 ? 'corrugated' : 'shingles', color: L === 1 ? '#9aa0a4' : '#5a4a42', y: 1.62, rx: -0.18 })
    })
    // nest boxes along the east wall, with straw and an egg or two
    b.at({ x: hw / 2 + 0.18, y: 0.75 }, () => {
      const n = L === 1 ? 2 : 3
      for (let i = 0; i < n; i++) {
        const z = (i - (n - 1) / 2) * 0.36
        b.box(0.34, 0.3, 0.32, { mat: 'wood', color: '#9a7a52', z, y: 0.15 })
        b.box(0.28, 0.06, 0.26, { mat: 'plain', color: '#e0c878', z, y: 0.07, x: 0.02 })
        if (rnd() < 0.7) b.sphere(0.035, { mat: 'gloss', color: '#f4e8d0', z: z + 0.04, y: 0.1, x: 0.06, sy: 1.3, ws: 8, hs: 6 })
      }
      b.box(0.4, 0.03, 0.36 * (L === 1 ? 2 : 3) + 0.04, { mat: 'wood', color: '#7a5a3a', y: 0.32, rz: 0.2 })
    })
    // the ramp and its slats
    b.at({ z: hd / 2 + 0.45, y: 0.3 }, () => {
      b.box(0.36, 0.03, 1.0, { mat: 'wood', color: '#a89070', rx: 0.62 })
      for (let i = 0; i < 6; i++) b.box(0.34, 0.02, 0.025, { mat: 'wood', color: '#6a5440', y: 0.28 - i * 0.11, z: -0.4 + i * 0.16 })
    })
    if (L === 2) {
      b.at({ x: -hw / 2 - 0.01, y: 1.05, ry: -Math.PI / 2 }, () => b.box(0.4, 0.3, 0.03, { mat: 'glass', color: '#b8d0d8' }))
      hangLamp(b, I, 0, 1.45, hd / 2 + 0.12)
    }
  })
  I.door = { x: hx, z: hz + hd / 2 + 0.95 }
  // feeder and water
  b.at({ x: 0.6, z: 0.2 }, () => {
    b.cyl(0.16, 0.2, 0.42, { mat: 'metal', color: '#9aa0a4', y: 0.32, seg: 12 })
    b.cyl(0.3, 0.3, 0.06, { mat: 'metal', color: '#7a8086', y: 0.1, seg: 16 })
    b.cyl(0.26, 0.26, 0.02, { mat: 'plain', color: '#d8b860', y: 0.13, seg: 16 })
  })
  trough(b, { x: 1.7, z: -1.1, len: 0.9, water: true, ry: 0.2 })
  // grain sacks and an egg crate by the gate
  for (let i = 0; i < 2; i++) sack(b, { x: 1.8 + i * 0.1, y: i * 0.22, z: 1.5, ry: 0.3, w: 0.36, h: 0.22, d: 0.55, color: '#d8c8a0', print: '#3a6a3a' })
  crate(b, { x: -2.0, z: 1.5, w: 0.5, h: 0.3, d: 0.4, color: '#c8b48c' })
  for (let i = 0; i < 6; i++) b.sphere(0.035, { mat: 'gloss', color: pick(rnd, ['#f4e8d0', '#e8c89a']), x: -2.12 + (i % 3) * 0.12, y: 0.33, z: 1.42 + Math.floor(i / 3) * 0.13, sy: 1.3, ws: 8, hs: 6 })
  I.pen = { x0: -0.2, x1: 2.1, z0: -1.0, z1: 1.4 }
  I.spots.push({ x: 0.25, z: 0.6, face: Math.PI / 2, anim: 'search' })
}
function goatpenModel(b, L, I) {
  const rnd = seeded(5700 + L)
  b.box(5.9, 0.02, 4.9, { mat: 'grass', color: '#7a8a52', y: 0.01 })
  for (let i = 0; i < 30; i++) b.box(0.4 + rnd() * 0.6, 0.004, 0.3 + rnd() * 0.5, { mat: 'dirt', color: '#7a6a50', x: (rnd() - 0.5) * 5, y: 0.022, z: (rnd() - 0.5) * 4, ry: rnd() * TAU, ao: 0, shadow: false })
  // a three-sided shelter at the back, hay inside
  const sx = -1.3
  const sz = -1.6
  const sw = L === 1 ? 2.8 : 3.4
  const sd = 1.6
  b.at({ x: sx, z: sz }, () => {
    b.box(sw, 0.06, sd, { mat: 'planks', color: '#b8a080', y: 0.03 })
    boardWall(b, -sw / 2, sw / 2, -sd / 2, 1.8, { y0: 0.05, seed: 5701, color: '#8a6a4a' })
    b.at({ ry: Math.PI / 2 }, () => {
      boardWall(b, -sd / 2, sd / 2, -sw / 2, 1.7, { y0: 0.05, seed: 5702, color: '#8a6a4a' })
      boardWall(b, -sd / 2, sd / 2, sw / 2, 1.7, { y0: 0.05, seed: 5703, color: '#8a6a4a' })
    })
    for (const x of [-sw / 2, sw / 2]) b.box(0.1, 1.9, 0.1, { mat: 'wood', color: '#6a5440', x, y: 0.95, z: sd / 2 })
    roof(b, I, (r) => r.box(sw + 0.4, 0.04, sd + 0.6, { mat: 'corrugated', color: L === 1 ? '#a89a8a' : '#6a7a6a', y: 1.98, z: 0.1, rx: -0.14 }))
    for (let i = 0; i < 2 + L; i++) hayBale(b, { x: -sw / 2 + 0.5 + i * 0.75, z: -0.35, ry: rnd() * 0.3 })
    for (let i = 0; i < 18; i++) b.box(0.2, 0.006, 0.012, { mat: 'plain', color: '#e0c878', x: (rnd() - 0.5) * sw, y: 0.07, z: (rnd() - 0.5) * sd, ry: rnd() * TAU, ao: 0, shadow: false })
  })
  I.door = { x: sx + 0.4, z: sz }
  // hay rack, water, the milking stand, a log to climb on
  b.at({ x: 1.6, z: -1.7 }, () => {
    for (const s of [-1, 1]) b.box(0.06, 1.1, 0.06, { mat: 'wood', color: '#6a5440', x: s * 0.6, y: 0.55 })
    for (let i = 0; i < 10; i++) b.box(0.025, 0.6, 0.025, { mat: 'wood', color: '#8a6a4a', x: -0.55 + i * 0.122, y: 0.75, z: 0.08, rx: -0.3 })
    b.box(1.2, 0.3, 0.3, { mat: 'plain', color: '#d8c070', y: 0.85, z: -0.05 })
  })
  trough(b, { x: 2.2, z: 0.3, len: 1.2, water: true, ry: Math.PI / 2 })
  b.at({ x: -2.2, z: 1.2, ry: 0.3 }, () => {
    b.box(1.0, 0.06, 0.4, { mat: 'wood', color: '#9a7a52', y: 0.45 })
    for (const sx2 of [-1, 1]) for (const sz2 of [-1, 1]) b.box(0.05, 0.45, 0.05, { mat: 'wood', color: '#6a5440', x: sx2 * 0.45, y: 0.22, z: sz2 * 0.16 })
    b.box(0.05, 0.8, 0.05, { mat: 'wood', color: '#6a5440', x: 0.48, y: 0.85, z: -0.08 })
    b.box(0.05, 0.8, 0.05, { mat: 'wood', color: '#6a5440', x: 0.48, y: 0.85, z: 0.08 })
    b.cyl(0.12, 0.1, 0.24, { mat: 'metal', color: '#c8ccd0', x: 0.1, y: 0.12, seg: 12 })
    b.box(0.25, 0.25, 0.25, { mat: 'wood', color: '#8a6a4a', x: -0.7, y: 0.12 })
  })
  b.at({ x: 0.2, z: 0.9, ry: 0.4 }, () => b.cyl(0.22, 0.24, 1.6, { mat: 'bark', color: '#ffffff', y: 0.22, rz: Math.PI / 2, seg: 10 }))
  b.box(0.2, 0.12, 0.2, { mat: 'plain', color: '#e8dcd0', x: -0.4, y: 0.08, z: -0.2 })
  if (L === 2) {
    // a shearing bench and wool sacks
    b.at({ x: 2.1, z: 1.6 }, () => {
      b.box(0.9, 0.06, 0.5, { mat: 'wood', color: '#9a7a52', y: 0.6 })
      for (const s of [-1, 1]) b.box(0.06, 0.6, 0.45, { mat: 'wood', color: '#6a5440', x: s * 0.38, y: 0.3 })
      for (let i = 0; i < 2; i++) sack(b, { x: -0.1 + i * 0.45, y: 0.66, ry: Math.PI / 2, w: 0.36, h: 0.3, d: 0.5, color: '#efe8d8' })
    })
    hangLamp(b, I, sx + 0.6, 1.7, sz + 0.55)
  }
  I.pen = { x0: -2.3, x1: 2.0, z0: -0.5, z1: 2.0 }
  I.spots.push({ x: -1.8, z: 1.5, face: -Math.PI / 2 - 0.3, anim: 'stir' })
}
// A lamp under the eaves for after dark.
function hangLamp(b, I, x, y, z) {
  bulb(b, { x, y, z, r: 0.05 })
  I.lights.push({ x, y: y - 0.05, z, color: '#ffc880', intensity: 2, dist: 6, when: 'night' })
}

export const STATIONS3 = {
  coop: coopModel,
  goatpen: goatpenModel,
  recycler: recyclerModel,
  priority(b, L, I) {
    beltNode(b, I, false, { color: '#a8452c', light: '#ffd060' })
    // a gold "1" disc on the lid and a plate on the front
    b.cyl(0.13, 0.13, 0.02, { mat: 'paint', color: '#f2c230', y: 0.84, z: 0.25, seg: 16 })
    b.box(0.03, 0.012, 0.13, { mat: 'paint', color: '#1a1a1a', y: 0.856, z: 0.25 })
    decal(b, plateMat('PRIORITY', '#f2c230', '#1a1a1a'), 0.34, 0.1, { y: 0.3, z: 0.377 })
  },
  hopper: hopperModel,
  crates: cratesModel,
  shed: shedModel,
  warehouse: warehouseModel,
}
