// Filler buildings for the city map, built at true scale in a lot-local
// frame: origin at the lot centre, +z toward the street, x along the
// frontage. Houses with yards, shops with signs and awnings, mixed-use blocks
// with fire escapes and water tanks, apartments with balconies, offices,
// glass towers, parking garages, warehouses with loading docks, factories
// with sawtooth roofs and chimneys, tank farms, churches and parks.
// Small repeated props go to a PropList for instancing.
import { gableRoof } from './parts.js'
import { facadeWindows, eachFace, hipRoof, flatRoof, waterTank, fireEscape, signPlane, shopSignCount, HOUSE_WALLS, HOUSE_ROOFS, PLASTER, CARS, LEAF, LEAF_AUTUMN, NEEDLE } from './citykit.js'

const TAU = Math.PI * 2
const pick = (r, a) => a[Math.floor(r() * a.length)]
export const treeColor = (r) => (r() < 0.12 ? pick(r, LEAF_AUTUMN) : pick(r, LEAF))
export const carKind = (r) => pick(r, ['sedan', 'sedan', 'sedan', 'hatch', 'hatch', 'suv', 'suv', 'pickup', 'van'])
export function putCar(P, b, x, z, ry, r, o = {}) {
  const burnt = o.burnt ?? r() < 0.1
  P.put(b, burnt ? 'car-burnt' : 'car-' + (o.kind || carKind(r)), x, z, { ry, c: burnt ? null : o.c || pick(r, CARS), rz: o.rz || 0, y: o.y || 0 })
}
export function putTree(P, b, x, z, r, kind) {
  const k = kind || pick(r, ['oak', 'oak', 'maple', 'maple', 'poplar', 'pine'])
  P.put(b, 'tree-' + k, x, z, { ry: r() * TAU, s: 0.75 + r() * 0.5, c: k === 'pine' || k === 'spruce' ? pick(r, NEEDLE) : treeColor(r) })
}
// A slab on the ground (paving, lawns, lots), lot-local.
// (lifted clear of the block paving and the terrain so distant views don't shimmer)
export function slab(b, x0, z0, x1, z1, mat, color, y = 0.03, h = 0.06) {
  b.box(x1 - x0, h, z1 - z0, { mat, color, x: (x0 + x1) / 2, y: y + 0.05, z: (z0 + z1) / 2, ao: 0, shadow: false })
}
function fenceRun(b, ax, az, bx, bz, h, o = {}) {
  const len = Math.hypot(bx - ax, bz - az)
  if (len < 0.5) return
  b.beam([ax, h / 2, az], [bx, h / 2, bz], 0.08, h, { mat: o.mat || 'planks', color: o.color || '#b09878' })
}
function chainFence(b, pts, h = 2.2) {
  for (let k = 0; k < pts.length - 1; k++) {
    const [ax, az] = pts[k]
    const [bx, bz] = pts[k + 1]
    const len = Math.hypot(bx - ax, bz - az)
    const n = Math.max(1, Math.round(len / 3))
    for (let i = 0; i <= n; i++) {
      const t = i / n
      b.cyl(0.04, 0.04, h, { mat: 'steel', color: '#8a8e90', x: ax + (bx - ax) * t, y: h / 2, z: az + (bz - az) * t, seg: 4 })
    }
    b.beam([ax, h - 0.05, az], [bx, h - 0.05, bz], 0.05, 0.05, { mat: 'steel', color: '#8a8e90' })
    b.beam([ax, h / 2, az], [bx, h / 2, bz], 0.02, h * 0.95, { mat: 'glass', color: '#9aa0a4' })
  }
}

// ---------------------------------------------------------------- house
export function house(b, o, r, P) {
  const w = o.w
  const d = o.d
  const fh = 2.8
  const base = 0.55
  const floors = o.floors
  const H = base + floors * fh
  const state = o.state || 'ok'
  const burnt = state === 'burnt'
  const wall = burnt ? '#5a524c' : o.wall
  const trim = burnt ? '#3a3634' : '#ece8e0'
  b.box(w + 0.3, base, d + 0.3, { mat: 'concrete', color: '#8e8a82', y: base / 2 })
  b.box(w, floors * fh, d, { mat: 'siding', color: wall, y: base + (floors * fh) / 2 })
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.18, floors * fh, 0.18, { mat: 'paint', color: trim, x: (sx * w) / 2, y: base + (floors * fh) / 2, z: (sz * d) / 2 })
  if (floors > 1) b.box(w + 0.06, 0.16, d + 0.06, { mat: 'paint', color: trim, y: base + fh })
  // roof
  const gableFront = o.roofType === 'gableFront'
  const rise = (gableFront ? w : d) * 0.36
  if (burnt) {
    // collapsed roof: charred rafters and half a slope
    for (let k = 0; k < 5; k++) b.beam([-w / 2 + 0.6 + k * (w / 5), H, -d / 2], [-w / 2 + 0.6 + k * (w / 5), H + rise * 0.8, 0], 0.16, 0.2, { mat: 'wood', color: '#2a2420' })
    b.box(w * 0.55, 0.12, d / 2 + 0.3, { mat: 'shingles', color: '#2a2826', x: -w * 0.2, y: H + rise * 0.45, z: -d / 4, rx: -0.65 })
    b.box(w - 0.4, 0.2, d - 0.4, { mat: 'wood', color: '#1e1a18', y: H - 0.1 })
  } else if (o.roofType === 'hip') hipRoof(b, w, d, H, rise * 0.9, { color: o.roof, soffit: trim })
  else if (gableFront) gableRoof(b, { w: d, d: w, y: H, rise, mat: 'shingles', color: o.roof, gableMat: 'siding', gableColor: wall, fascia: trim, over: 0.4, ry: Math.PI / 2 })
  else gableRoof(b, { w, d, y: H, rise, mat: 'shingles', color: o.roof, gableMat: 'siding', gableColor: wall, fascia: trim, over: 0.4 })
  if (!burnt && o.chimney) b.box(0.9, rise + 1.4, 0.8, { mat: 'brick', color: '#d8c8c0', x: o.chimney * (w / 2 - 1.0), y: H + rise / 2 + 0.3, z: -d * 0.15 })
  // doors and windows
  const doorX = o.doorX ?? 0
  const boarded = state === 'boarded' ? 0.75 : burnt ? 0.2 : 0.02
  eachFace(b, w, d, (len, face) => {
    facadeWindows(b, len, {
      floors,
      y0: base,
      fh,
      ww: 1.1,
      wh: 1.35,
      gap: face === 'front' ? 1.5 : 2.2,
      margin: 1.0,
      frame: trim,
      glass: burnt ? '#141414' : '#46525c',
      sill: burnt ? null : '#d8d4cc',
      shutters: face === 'front' && o.shutters ? o.shutters : null,
      boarded,
      broken: burnt ? 0.7 : 0.08,
      lit: o.lit || 0,
      rnd: r,
      skip: (x, f) => face === 'front' && f === 0 && Math.abs(x - doorX) < 1.3,
    })
  })
  b.box(1.0, 2.1, 0.12, { mat: state === 'boarded' ? 'planks' : 'paint', color: state === 'boarded' ? '#a8906c' : o.door || '#5a3a2a', x: doorX, y: base + 1.05, z: d / 2 + 0.04 })
  // porch
  if (o.porch && !burnt) {
    const pw = Math.min(w - 1, 6.5)
    b.box(pw, 0.35, 2.2, { mat: 'planks', color: '#b8a080', x: doorX * 0.5, y: base - 0.12, z: d / 2 + 1.1 })
    for (const sx of [-1, 1]) b.box(0.18, 2.5, 0.18, { mat: 'paint', color: trim, x: doorX * 0.5 + (sx * (pw - 0.3)) / 2, y: base + 1.25, z: d / 2 + 2.05 })
    b.box(pw + 0.4, 0.14, 2.6, { mat: 'shingles', color: o.roof, x: doorX * 0.5, y: base + 2.62, z: d / 2 + 1.2, rx: 0.16 })
    b.box(pw - 0.4, 0.8, 0.06, { mat: 'paint', color: trim, x: doorX * 0.5, y: base + 0.45, z: d / 2 + 2.15 })
  } else b.box(1.6, 0.4, 1.2, { mat: 'concrete', color: '#a8a49c', x: doorX, y: 0.2, z: d / 2 + 0.6 })
  // attached garage
  if (o.garage) {
    const gw = 6.2
    const gd = Math.min(d, 7.2)
    const gx = o.garage * (w / 2 + gw / 2)
    const gz = d / 2 - gd / 2
    b.box(gw, 3.0, gd, { mat: 'siding', color: wall, x: gx, y: 1.5, z: gz })
    if (!burnt) gableRoof(b, { w: gw, d: gd, y: 3.0, rise: 1.3, mat: 'shingles', color: o.roof, gableMat: 'siding', gableColor: wall, fascia: trim, over: 0.3, x: gx, z: gz })
    b.box(4.8, 2.3, 0.1, { mat: 'paint', color: burnt ? '#2a2826' : '#e4e0d8', x: gx, y: 1.17, z: d / 2 + 0.03 })
    for (let k = 1; k < 4; k++) b.box(4.8, 0.04, 0.12, { mat: 'paint', color: '#bcb8b0', x: gx, y: k * 0.58, z: d / 2 + 0.04 })
  }
  if (o.fire) fireAt(P, b, 0, 0, { s: 1 })
}
export function fireAt(P, b, x, z, o = {}) {
  const w = worldXZ(b, x, z)
  P.add('fire', w.x, w.z, o)
}
function worldXZ(b, x, z) {
  const e = b.top.elements
  return { x: e[0] * x + e[8] * z + e[12], z: e[2] * x + e[10] * z + e[14] }
}

export function houseLot(b, lot, r, P) {
  const bld = lot.bld
  const lw = lot.fw
  const ld = lot.fd
  const floors = bld.floors || 1
  const w = Math.min(lw - 5, floors === 2 ? 10 + r() * 3 : 11 + r() * 4)
  const d = Math.min(ld - 14, 9.5 + r() * 3.5)
  const setback = 6 + r() * 2.5
  const hz = ld / 2 - setback - d / 2
  const garage = w + 7 < lw - 2 && r() < 0.5 ? (r() < 0.5 ? -1 : 1) : 0
  const hx = garage ? -garage * 2.6 : (r() - 0.5) * 1.5
  const o = {
    w,
    d,
    floors,
    wall: pick(r, HOUSE_WALLS),
    roof: pick(r, HOUSE_ROOFS),
    roofType: r() < 0.3 ? 'hip' : r() < 0.25 ? 'gableFront' : 'gable',
    state: bld.state,
    chimney: r() < 0.55 ? (r() < 0.5 ? -1 : 1) : 0,
    porch: r() < 0.55,
    garage,
    shutters: r() < 0.35 ? pick(r, ['#3a4a3a', '#2a3440', '#5a2a2a', '#4a4440']) : null,
    door: pick(r, ['#5a3a2a', '#2a3a4a', '#6a2a2a', '#3a4a3a', '#e8e4dc']),
    doorX: (r() - 0.5) * 2,
    lit: bld.state === 'ok' && r() < 0.06 ? 0.4 : 0,
    fire: bld.fire,
  }
  b.at({ x: hx, z: hz }, () => house(b, o, r, P))
  const front = ld / 2
  const burnt = bld.state === 'burnt'
  // path to the door, driveway
  slab(b, hx + o.doorX - 0.6, hz + d / 2 + (o.porch ? 2.2 : 1.2), hx + o.doorX + 0.6, front, 'concrete', '#b4b0a6')
  const gx = garage ? hx + garage * (w / 2 + 3.1) : hx + (lw / 2 - 2) * (r() < 0.5 ? -1 : 1)
  if (garage || r() < 0.6) {
    const dx0 = Math.max(-lw / 2 + 0.3, gx - 1.8)
    const dx1 = Math.min(lw / 2 - 0.3, gx + 1.8)
    slab(b, dx0, garage ? hz + d / 2 : hz - d / 2 + 1, dx1, front, 'concrete', '#aaa69c', 0.035)
    if (r() < 0.5) putCar(P, b, (dx0 + dx1) / 2, front - 4.2 - r() * 2, r() < 0.5 ? 0 : Math.PI, r)
  }
  // back yard fence
  const back = -ld / 2 + 0.2
  const fenceZ = hz - d / 2 + 1
  const fc = r() < 0.6 ? '#b09878' : '#e8e4dc'
  fenceRun(b, -lw / 2 + 0.1, back, lw / 2 - 0.1, back, 1.8, { color: fc })
  fenceRun(b, -lw / 2 + 0.1, back, -lw / 2 + 0.1, fenceZ, 1.8, { color: fc })
  fenceRun(b, lw / 2 - 0.1, back, lw / 2 - 0.1, fenceZ, 1.8, { color: fc })
  // front: hedges or a picket fence
  const fr = r()
  if (fr < 0.3) {
    for (let x = -lw / 2 + 1; x < lw / 2 - 1; x += 1.6) if (Math.abs(x - hx - o.doorX) > 1.4 && Math.abs(x - gx) > 2.2) P.put(b, 'hedge', x, front - 0.8, { s: 1, sy: 0.9 + r() * 0.3, c: pick(r, LEAF) })
  } else if (fr < 0.5) {
    for (const [a, c] of [
      [-lw / 2 + 0.2, Math.min(hx + o.doorX - 0.8, gx - 2)],
      [Math.max(hx + o.doorX + 0.8, gx + 2), lw / 2 - 0.2],
    ])
      if (c - a > 0.5) fenceRun(b, a, front - 0.3, c, front - 0.3, 0.9, { color: '#ece8e0', mat: 'paint' })
  }
  // trees, shed, pool, toys, bins
  const nt = burnt ? 1 : 2 + Math.floor(r() * 4)
  for (let k = 0; k < nt; k++) {
    const backYard = r() < 0.6
    const tx = (r() - 0.5) * (lw - 3)
    const tz = backYard ? back + 2 + r() * Math.max(1, fenceZ - back - 4) : front - 2.5 - r() * (setback - 4)
    if (Math.abs(tx - hx) < w / 2 + 1.5 && tz > hz - d / 2 - 1.5 && tz < hz + d / 2 + 1.5) continue
    putTree(P, b, tx, tz, r, burnt ? 'dead' : null)
  }
  const yardD = fenceZ - back
  if (yardD > 6 && r() < 0.32) {
    const sx = (r() < 0.5 ? -1 : 1) * (lw / 2 - 2.4)
    b.box(3, 2.2, 2.4, { mat: 'planks', color: pick(r, ['#b8a888', '#8a9a7a', '#a87a5a', '#c8c4b4']), x: sx, y: 1.1, z: back + 1.8 })
    gableRoof(b, { w: 3, d: 2.4, y: 2.2, rise: 0.8, mat: 'roofmetal', color: '#6a5a50', over: 0.2, x: sx, z: back + 1.8, gableMat: 'planks', gableColor: '#b8a888' })
  }
  if (yardD > 9 && r() < 0.13 && !burnt) {
    const pw = Math.min(lw - 6, 7)
    const pz = back + yardD / 2
    b.box(pw + 1.2, 0.14, 4.6, { mat: 'concrete', color: '#d8d4cc', y: 0.07, z: pz })
    b.box(pw, 0.06, 3.4, { mat: 'mapGlass', color: r() < 0.6 ? '#4a6a3a' : '#3a8aa8', y: 0.12, z: pz })
  }
  if (!burnt && r() < 0.08) P.put(b, 'swing', (r() - 0.5) * (lw - 6), back + 3, { ry: r() * 0.3 })
  if (r() < 0.6) P.put(b, 'trash', hx + o.doorX + 2.2, front - 0.6, {})
  P.put(b, 'mailbox', hx + o.doorX - 1.6, front - 0.4, { c: pick(r, ['#2a4a8a', '#3a3a3a', '#8a2a2a', '#e8e4dc']) })
  if (burnt) P.put(b, 'rubble', hx + (r() - 0.5) * w, hz + d / 2 + 1.5, { ry: r() * TAU })
}

// ---------------------------------------------------------------- storefronts
function storefront(b, w, o, r) {
  // ground floor shop window band, door, sign and awning on the face (+z)
  const sh = o.sh ?? 4.2
  const board = o.boarded
  b.box(w - 0.6, 0.5, 0.14, { mat: 'paint', color: '#2a2c2e', y: 0.25, z: 0.05 })
  const panes = Math.max(2, Math.floor((w - 1) / 2.2))
  const pw = (w - 1) / panes
  for (let k = 0; k < panes; k++) {
    const x = -w / 2 + 0.5 + pw * (k + 0.5)
    const door = k === Math.floor(panes / 2)
    if (board && r() < 0.8) b.box(pw - 0.12, 2.6, 0.1, { mat: 'planks', color: '#a8906c', x, y: 1.85, z: 0.07 })
    else b.box(pw - 0.12, door ? 2.6 : 2.5, 0.1, { mat: o.lit && r() < 0.3 ? 'window' : 'mapGlass', color: r() < 0.12 ? '#121416' : '#3e4e58', x, y: door ? 1.6 : 1.85, z: 0.03 })
    b.box(0.1, 2.7, 0.16, { mat: 'paint', color: '#3a3c3e', x: x - pw / 2, y: 1.85, z: 0.05 })
  }
  // sign band
  b.box(w - 0.3, 0.9, 0.2, { mat: 'paint', color: o.band || '#3a3c3e', y: sh - 0.7, z: 0.06 })
  signPlane(b, o.sign ?? Math.floor(r() * shopSignCount()), Math.min(w - 1.2, 7.5), 0.78, { y: sh - 0.7, z: 0.17 })
  if (o.awning) {
    const aw = Math.min(w - 0.8, 9)
    const n = Math.max(3, Math.round(aw / 0.6))
    for (let k = 0; k < n; k++) b.box(aw / n, 0.08, 1.7, { mat: 'canvas', color: k % 2 ? '#e8e4dc' : o.awning, x: -aw / 2 + (aw / n) * (k + 0.5), y: sh - 1.45, z: 0.85, rx: 0.32, shadow: true })
  }
}

export function shop(b, o, r, P) {
  const { w, d } = o
  const floors = o.floors || 1
  const sh = 4.2
  const uh = 3.2
  const H = sh + (floors - 1) * uh
  const wallMat = o.brick ? 'brick' : 'plaster'
  const wall = o.wall || (o.brick ? '#ffffff' : pick(r, PLASTER))
  b.box(w, H, d, { mat: wallMat, color: o.burnt ? '#5a524c' : wall, y: H / 2 })
  b.at({ z: d / 2 }, () => storefront(b, w, { sh, awning: o.awning, boarded: o.boarded, sign: o.sign, lit: o.lit }, r))
  if (floors > 1)
    b.at({ z: d / 2 }, () => facadeWindows(b, w, { floors: floors - 1, y0: sh, fh: uh, ww: 1.2, wh: 1.6, gap: 1.4, frame: '#d8d0c0', sill: '#c8c0b0', rnd: r, broken: 0.1, lit: o.lit ? 0.15 : 0, ac: 0.15 }))
  for (const [face, len, ry, x, z] of [
    ['back', w, Math.PI, 0, -d / 2],
    ['side', d, Math.PI / 2, w / 2, 0],
    ['side', d, -Math.PI / 2, -w / 2, 0],
  ])
    b.at({ x, z, ry }, () => facadeWindows(b, len, { floors: floors - 1, y0: sh, fh: uh, ww: 1.1, wh: 1.5, gap: 2.6, frame: '#d8d0c0', rnd: r, broken: 0.15 }))
  b.box(1.0, 2.2, 0.12, { mat: 'paint', color: '#4a4c4e', x: w / 2 - 2, y: 1.1, z: -d / 2 - 0.05 })
  b.box(w + 0.2, 0.35, d + 0.2, { mat: 'concrete', color: '#b8b4aa', y: H + 0.05 })
  flatRoof(b, w, d, H, { rnd: r, parapet: 0.8, parMat: wallMat, parColor: wall, hatch: floors > 1, clutter: Math.min(4, Math.floor((w * d) / 80) + 1) })
  P.put(b, 'dumpster', -w / 2 + 2.5, -d / 2 - 1.6, { c: pick(r, ['#3a6a4a', '#2a4a6a', '#5a5a52']) })
  if (o.fire) fireAt(P, b, 0, 0, { s: 1.2 })
}

export function mixed(b, o, r, P) {
  const { w, d } = o
  const floors = o.floors
  const sh = 4.2
  const uh = 3.1
  const H = sh + (floors - 1) * uh
  const brick = o.brick ?? r() < 0.65
  const wallMat = brick ? 'brick' : 'plaster'
  const wall = o.wall || (brick ? pick(r, ['#ffffff', '#e8d8d0', '#d0c0b8', '#c8b0a0']) : pick(r, PLASTER))
  b.box(w, H, d, { mat: wallMat, color: o.burnt ? '#4a4440' : wall, y: H / 2 })
  b.at({ z: d / 2 }, () => storefront(b, w, { sh, awning: o.awning, boarded: o.boarded, sign: o.sign, band: brick ? '#2e3032' : '#4a4038' }, r))
  const lit = o.lit || 0
  eachFace(b, w, d, (len, face) => {
    facadeWindows(b, len, {
      floors: floors - 1,
      y0: sh,
      fh: uh,
      ww: 1.15,
      wh: 1.7,
      gap: face === 'front' ? 1.2 : 1.9,
      margin: 1.1,
      frame: '#e0d8c8',
      sill: '#d0c8b8',
      rnd: r,
      broken: o.burnt ? 0.6 : 0.1,
      boarded: o.boarded ? 0.3 : 0.03,
      lit,
      ac: 0.12,
    })
  })
  // cornice and floor bands
  b.box(w + 0.5, 0.45, d + 0.5, { mat: 'concrete', color: '#c8c0b0', y: H - 0.2 })
  b.box(w + 0.16, 0.22, d + 0.16, { mat: 'concrete', color: '#c8c0b0', y: sh })
  flatRoof(b, w, d, H + 0.05, { rnd: r, parapet: 0.9, parMat: wallMat, parColor: wall, tank: o.tank, clutter: 3 })
  if (o.escape) b.at({ x: o.escape > 0 ? w / 2 : -w / 2, ry: o.escape > 0 ? Math.PI / 2 : -Math.PI / 2 }, () => fireEscape(b, 0, floors, uh, sh - uh, Math.min(d - 2, 4.5)))
  P.put(b, 'dumpster', w / 2 - 2.5, -d / 2 - 1.6, { c: pick(r, ['#3a6a4a', '#2a4a6a']) })
  if (o.fire) fireAt(P, b, 0, 0, { s: 1.4 })
}

export function apartment(b, o, r, P) {
  const { w, d } = o
  const floors = o.floors
  const fh = 3.0
  const H = 0.6 + floors * fh
  const wall = o.wall || pick(r, PLASTER)
  const mat = o.brick ? 'brick' : 'plaster'
  b.box(w + 0.4, 0.6, d + 0.4, { mat: 'concrete', color: '#9a968c', y: 0.3 })
  b.box(w, floors * fh, d, { mat, color: o.burnt ? '#4a4440' : wall, y: 0.6 + (floors * fh) / 2 })
  eachFace(b, w, d, (len, face) => {
    facadeWindows(b, len, { floors, y0: 0.6, fh, ww: 1.3, wh: 1.5, gap: face === 'front' || face === 'back' ? 1.7 : 2.4, margin: 1.2, frame: '#e8e4dc', sill: '#d8d4cc', rnd: r, broken: o.burnt ? 0.6 : 0.08, boarded: o.boarded ? 0.4 : 0.04, lit: o.lit || 0, ac: 0.2, skip: (x, f) => face === 'front' && f === 0 && Math.abs(x) < 1.8 })
    // balconies on the long faces
    if ((face === 'front' || face === 'back') && o.balconies) {
      const n = Math.floor((len - 2) / 6.2)
      for (let f = 1; f < floors; f++) {
        for (let k = 0; k < n; k++) {
          const x = -((n - 1) * 6.2) / 2 + k * 6.2
          const y = 0.6 + f * fh
          b.box(2.6, 0.16, 1.2, { mat: 'concrete', color: '#c8c4bc', x, y, z: 0.6 })
          b.box(2.6, 0.9, 0.05, { mat: 'paint', color: '#4a4e52', x, y: y + 0.5, z: 1.18 })
          if (r() < 0.25) b.box(0.8, 0.6, 0.5, { mat: 'canvas', color: pick(r, ['#c84a2a', '#3a6aa8', '#e8d8a8']), x: x + 0.6, y: y + 0.4, z: 0.6 })
        }
      }
    }
  })
  // entrance canopy and door
  b.box(1.8, 2.3, 0.14, { mat: 'mapGlass', color: '#3a4650', y: 1.75, z: d / 2 + 0.03 })
  b.box(3.2, 0.2, 1.8, { mat: 'concrete', color: '#b8b4ac', y: 3.0, z: d / 2 + 0.9 })
  flatRoof(b, w, d, H, { rnd: r, parapet: 0.7, parMat: mat, parColor: wall, clutter: 4, tank: o.tank })
  if (o.escape) b.at({ x: w / 2, ry: Math.PI / 2 }, () => fireEscape(b, 0, floors, fh, 0.6, Math.min(d - 2, 4.2)))
  if (o.fire) fireAt(P, b, 0, 0, { s: 1.4 })
}

// ---------------------------------------------------------------- offices and towers
export function office(b, o, r, P) {
  const { w, d } = o
  const floors = o.floors
  const fh = 3.6
  const lobby = 4.6
  const H = lobby + (floors - 1) * fh
  const conc = o.wall || pick(r, ['#c8c4bc', '#b8b4ac', '#d0ccc0', '#a8aca8', '#c0b8a8'])
  const glass = o.glass || pick(r, ['#3a4e5a', '#2e3e48', '#4a5a50', '#5a5448'])
  // lobby
  b.box(w - 1.2, lobby, d - 1.2, { mat: 'mapGlass', color: glass, y: lobby / 2 })
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.9, lobby, 0.9, { mat: 'concrete', color: conc, x: sx * (w / 2 - 0.6), y: lobby / 2, z: sz * (d / 2 - 0.6) })
  // floors: spandrel bands + glass ribbons
  const upper = floors - 1
  const setback = floors >= 9 && o.setback !== false
  const lower = setback ? Math.ceil(upper * 0.65) : upper
  const band = (y, n, ww, dd) => {
    b.box(ww, n * fh, dd, { mat: 'mapGlass', color: glass, y: y + (n * fh) / 2 })
    for (let f = 0; f <= n; f++) b.box(ww + 0.2, 1.1, dd + 0.2, { mat: 'concrete', color: conc, y: y + f * fh + (f === n ? 0.2 : -0.1) })
    // vertical fins
    const nf = Math.floor(ww / 3)
    for (let k = 0; k <= nf; k++) {
      const x = -ww / 2 + (k * ww) / nf
      for (const sz of [-1, 1]) b.box(0.3, n * fh, 0.4, { mat: 'concrete', color: conc, x, y: y + (n * fh) / 2, z: (sz * dd) / 2 })
    }
    const nfd = Math.floor(dd / 3)
    for (let k = 1; k < nfd; k++) {
      const z = -dd / 2 + (k * dd) / nfd
      for (const sx of [-1, 1]) b.box(0.4, n * fh, 0.3, { mat: 'concrete', color: conc, x: (sx * ww) / 2, y: y + (n * fh) / 2, z })
    }
  }
  band(lobby, lower, w, d)
  let top = lobby + lower * fh
  let tw = w
  let td = d
  if (setback && upper - lower > 0) {
    tw = w - 6
    td = d - 5
    b.box(w, 0.3, d, { mat: 'roofTar', color: '#6a6866', y: top + 0.15 })
    band(top, upper - lower, tw, td)
    top += (upper - lower) * fh
  }
  b.box(w + 0.6, 0.4, 3.2, { mat: 'concrete', color: conc, y: lobby - 0.4, z: d / 2 + 1.2 })
  flatRoof(b, tw, td, top + 0.1, { rnd: r, parapet: 1.0, parColor: conc, clutter: 5 })
  // mechanical penthouse and antenna
  b.box(tw * 0.4, 3.2, td * 0.4, { mat: 'concrete', color: '#9a968e', y: top + 1.7 })
  if (r() < 0.5) {
    b.cyl(0.12, 0.2, 9, { mat: 'steel', color: '#9a9ea2', x: tw * 0.2, y: top + 3.2 + 4.5, seg: 5 })
    b.sphere(0.25, { mat: 'glowRed', color: '#ffffff', x: tw * 0.2, y: top + 12.3, ws: 6, hs: 4 })
  }
  if (o.fire) fireAt(P, b, 0, 0, { s: 1.6, y: top })
  return top
}

export function tower(b, o, r, P) {
  const { w, d } = o
  const floors = o.floors
  const podium = 9
  const conc = pick(r, ['#c8c4bc', '#b0aca4', '#d8d4c8', '#8a8e90'])
  const tint = o.tint || pick(r, ['#ffffff', '#c8e0e8', '#d8e8d8', '#e8dcc8', '#b8c8d8'])
  // podium with a glazed street front
  b.box(w, podium, d, { mat: 'concrete', color: conc, y: podium / 2 })
  b.at({ z: d / 2 }, () => b.box(w - 2, 6.2, 0.12, { mat: 'mapGlass', color: '#3a4a54', y: 3.4, z: 0.03 }))
  b.box(w - 4, 0.4, 3.6, { mat: 'concrete', color: '#d8d4cc', y: 6.6, z: d / 2 + 1.6 })
  eachFace(b, w, d, (len, face) => face !== 'front' && facadeWindows(b, len, { floors: 2, y0: 0.4, fh: 4.4, ww: 2.4, wh: 2.6, gap: 1.0, rnd: r, glass: '#3a4a54' }))
  // the shaft, in one or two stages
  const tw = w - 4
  const td = d - 4
  const H1 = Math.round(floors * (o.stages === 1 ? 1 : 0.7)) * 3
  b.box(tw, H1, td, { mat: 'curtain', color: tint, y: podium + H1 / 2 })
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.8, H1, 0.8, { mat: 'concrete', color: conc, x: sx * (tw / 2), y: podium + H1 / 2, z: sz * (td / 2) })
  let top = podium + H1
  let cw = tw
  let cd = td
  if (o.stages !== 1) {
    const H2 = (floors * 3) - H1
    cw = tw - 5
    cd = td - 5
    b.box(tw + 0.4, 0.6, td + 0.4, { mat: 'concrete', color: conc, y: top + 0.3 })
    b.box(cw, H2, cd, { mat: 'curtain', color: tint, y: top + 0.6 + H2 / 2 })
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.7, H2, 0.7, { mat: 'concrete', color: conc, x: sx * (cw / 2), y: top + 0.6 + H2 / 2, z: sz * (cd / 2) })
    top += 0.6 + H2
  }
  b.box(cw + 0.6, 1.2, cd + 0.6, { mat: 'concrete', color: conc, y: top + 0.6 })
  const crown = o.crown ?? pick(r, ['heli', 'spire', 'mech', 'mech'])
  if (crown === 'heli') {
    b.cyl(Math.min(cw, cd) * 0.45, Math.min(cw, cd) * 0.45, 0.3, { mat: 'concrete', color: '#5a5c5e', y: top + 1.35, seg: 20 })
    b.torus(Math.min(cw, cd) * 0.36, 0.18, { mat: 'paint', color: '#e8c840', y: top + 1.52, rx: Math.PI / 2, rs: 4, ts2: 24 })
    b.box(0.5, 0.05, 3.4, { mat: 'paint', color: '#e8e4dc', x: -1.0, y: top + 1.53 })
    b.box(0.5, 0.05, 3.4, { mat: 'paint', color: '#e8e4dc', x: 1.0, y: top + 1.53 })
    b.box(2.0, 0.05, 0.5, { mat: 'paint', color: '#e8e4dc', y: top + 1.53 })
  } else if (crown === 'spire') {
    b.box(cw * 0.5, 4, cd * 0.5, { mat: 'curtain', color: tint, y: top + 3.2 })
    b.cyl(0.15, 0.6, 16, { mat: 'steel', color: '#c8ccd0', y: top + 5.2 + 8, seg: 6 })
    b.sphere(0.35, { mat: 'glowRed', color: '#ffffff', y: top + 21.4, ws: 6, hs: 4 })
  } else {
    b.box(cw * 0.5, 3.6, cd * 0.45, { mat: 'concrete', color: '#9a968e', y: top + 3.0 })
    for (let k = 0; k < 3; k++) b.box(1.8, 1.0, 1.4, { mat: 'paint', color: '#b8bcbc', x: -cw * 0.3 + k * 2.4, y: top + 1.7, z: cd * 0.32 })
    b.sphere(0.3, { mat: 'glowRed', color: '#ffffff', x: cw * 0.2, y: top + 5.1, ws: 6, hs: 4 })
  }
  if (o.fire) fireAt(P, b, 0, 0, { s: 2, y: top * 0.6 })
  return top
}

export function parkingGarage(b, o, r, P) {
  const { w, d } = o
  const decks = o.floors
  const fh = 3.1
  const conc = '#b8b4ac'
  for (let f = 0; f <= decks; f++) {
    const y = f * fh
    b.box(w, 0.35, d, { mat: 'concrete', color: conc, y: y + 0.18 })
    if (f > 0) {
      for (const sz of [-1, 1]) b.box(w, 1.0, 0.25, { mat: 'concrete', color: '#c8c4bc', y: y + 0.85, z: (sz * d) / 2 - sz * 0.12 })
      for (const sx of [-1, 1]) b.box(0.25, 1.0, d, { mat: 'concrete', color: '#c8c4bc', x: (sx * w) / 2 - sx * 0.12, y: y + 0.85 })
    }
    if (f < decks) for (let x = -w / 2 + 1; x <= w / 2 - 1; x += 8) for (const z of [-d / 2 + 1, 0, d / 2 - 1]) b.box(0.6, fh, 0.6, { mat: 'concrete', color: conc, x, y: y + fh / 2, z })
    if (f > 0 && f <= decks) {
      const n = Math.floor(r() * 7)
      for (let k = 0; k < n; k++) putCar(P, b, -w / 2 + 3 + r() * (w - 6), (r() < 0.5 ? -1 : 1) * (d / 2 - 4), Math.PI / 2 + (r() < 0.5 ? 0 : Math.PI), r, { y: y + 0.35, burnt: false })
    }
  }
  b.beam([-w / 2 + 3, 0.3, 0], [-w / 2 + 3 + 14, fh + 0.2, 0], 5, 0.3, { mat: 'concrete', color: conc })
  b.box(4, 6, 4, { mat: 'concrete', color: '#a8a49c', x: w / 2 - 3, y: decks * fh + 2.5, z: -d / 2 + 3 })
}

export function plaza(b, lot, r, P) {
  const lw = lot.fw
  const ld = lot.fd
  slab(b, -lw / 2, -ld / 2, lw / 2, ld / 2, 'pavers', '#c8c0b0', 0.06, 0.12)
  // fountain
  b.cyl(5, 5.2, 0.8, { mat: 'concrete', color: '#c8c4bc', y: 0.4, seg: 24 })
  b.cyl(4.5, 4.5, 0.1, { mat: 'mapGlass', color: '#3a5a50', y: 0.75, seg: 24 })
  b.cyl(0.8, 1.0, 2.4, { mat: 'concrete', color: '#b8b4ac', y: 1.2, seg: 10 })
  b.cyl(1.8, 1.4, 0.4, { mat: 'concrete', color: '#b8b4ac', y: 2.4, seg: 14 })
  for (let k = 0; k < 8; k++) {
    const a = (k / 8) * TAU
    const rr = Math.min(lw, ld) * 0.36
    b.box(2.4, 0.8, 2.4, { mat: 'concrete', color: '#a8a49c', x: Math.cos(a) * rr, y: 0.4, z: Math.sin(a) * rr })
    putTree(P, b, Math.cos(a) * rr, Math.sin(a) * rr, r, 'maple')
    P.put(b, 'bench', Math.cos(a + 0.4) * rr, Math.sin(a + 0.4) * rr, { ry: -a + Math.PI / 2 })
  }
}

// ---------------------------------------------------------------- industry
export function warehouse(b, o, r, P) {
  const { w, d } = o
  const H = o.h || 9 + r() * 3
  const wall = o.wall || pick(r, ['#c8ccc8', '#b8c0c4', '#d0c4ac', '#a8b4b8', '#c4b8a4', '#8e9aa0'])
  b.box(w, H, d, { mat: 'corrugated', color: o.burnt ? '#4a4440' : wall, y: H / 2 })
  b.box(w + 0.1, 1.0, d + 0.1, { mat: 'concrete', color: '#a8a49c', y: 0.5 })
  // low-pitch roof with skylight strips
  gableRoof(b, { w, d, y: H, rise: 1.2, mat: 'roofmetal', color: o.roof || pick(r, ['#9aa0a4', '#8a8e8c', '#a8a49a']), gables: true, gableMat: 'corrugated', gableColor: wall, over: 0.3 })
  for (let k = 0; k < Math.floor(w / 12); k++) for (const s of [-1, 1]) b.box(2, 0.1, d / 2 - 2, { mat: 'mapGlass', color: '#8a9aa4', x: -w / 2 + 6 + k * 12, y: H + 0.68, z: (s * d) / 4, rx: s * 0.24 })
  // loading docks on the front
  const nd = Math.max(2, Math.floor((w - 22) / 6))
  for (let k = 0; k < nd; k++) {
    const x = -w / 2 + 8 + k * 6
    b.box(3.6, 4.0, 0.14, { mat: 'roofmetal', color: o.burnt ? '#2a2826' : pick(r, ['#c8c8c0', '#4a6a8a', '#8a3a2a']), x, y: 3.0, z: d / 2 + 0.05 })
    b.box(4.4, 1.2, 0.5, { mat: 'concrete', color: '#8a867e', x, y: 0.6, z: d / 2 + 0.25 })
    for (const sx of [-1, 1]) b.box(0.3, 0.6, 0.3, { mat: 'rubber', color: '#1e1e1e', x: x + sx * 1.6, y: 0.9, z: d / 2 + 0.55 })
  }
  b.box(w - 6, 0.25, 3.0, { mat: 'roofmetal', color: '#8a8e90', y: 5.6, z: d / 2 + 1.5, rx: -0.1 })
  // office annex
  const ax = w / 2 - 6
  b.box(10, 6.2, 7, { mat: 'concrete', color: '#c8c4bc', x: ax, y: 3.1, z: d / 2 + 3.5 })
  b.at({ x: ax, z: d / 2 + 7 }, () => facadeWindows(b, 10, { floors: 2, y0: 0, fh: 3, ww: 1.6, wh: 1.2, gap: 1.0, rnd: r, frame: '#8a8e90', broken: 0.15 }))
  b.at({ x: ax, z: d / 2 + 3.5 }, () => flatRoof(b, 10, 7, 6.2, { rnd: r, parapet: 0.4, clutter: 1, hatch: false }))
  // dock trucks and trailers
  for (let k = 0; k < nd; k++) if (r() < 0.45) P.put(b, r() < 0.6 ? 'semi' : 'truck', -w / 2 + 8 + k * 6, d / 2 + (r() < 0.6 ? 9.5 : 6.5), { ry: Math.PI, c: pick(r, ['#c8c4bc', '#8a2a2a', '#2a4a7a', '#e8e4dc', '#3a5a3a']) })
  if (o.fire) fireAt(P, b, 0, 0, { s: 1.8 })
}

export function factory(b, o, r, P) {
  const { w, d } = o
  const H = 8 + r() * 3
  const wall = pick(r, ['#ffffff', '#e8d8d0', '#d0c0b8'])
  b.box(w, H, d, { mat: 'brick', color: wall, y: H / 2 })
  eachFace(b, w, d, (len) => facadeWindows(b, len, { floors: 1, y0: 1.5, fh: 5, ww: 2.2, wh: 3.6, gap: 1.6, rnd: r, frame: '#3a3c3e', glass: '#5a6a70', broken: 0.3 }))
  // sawtooth roof: steep glazed faces to the north
  const teeth = Math.max(2, Math.floor(d / 6))
  const tdp = d / teeth
  for (let k = 0; k < teeth; k++) {
    const z = -d / 2 + tdp * (k + 0.5)
    b.box(w, 0.2, tdp * 1.05, { mat: 'roofmetal', color: '#7a7e7c', y: H + 1.4, z: z + 0.1, rx: -0.42 })
    b.box(w, 2.6, 0.12, { mat: 'mapGlass', color: '#6a7a80', y: H + 1.3, z: z - tdp / 2 + 0.1 })
  }
  // chimney
  const cx = -w / 2 + 4
  const cz = -d / 2 - 4
  const ch = 26 + r() * 12
  b.cyl(1.4, 2.2, ch, { mat: 'brick', color: '#e0c8b8', x: cx, y: ch / 2, z: cz, seg: 12 })
  for (const k of [0.3, 0.6, 0.97]) b.cyl(1.6 + (1 - k) * 0.7, 1.6 + (1 - k) * 0.7, 0.5, { mat: 'concrete', color: '#5a5450', x: cx, y: ch * k, z: cz, seg: 12 })
  P.add('smoke', worldXZ(b, cx, cz).x, worldXZ(b, cx, cz).z, { y: ch, s: 0.6 })
  // silos and pipes
  if (r() < 0.6) {
    for (let k = 0; k < 2; k++) {
      const sx = w / 2 - 3 - k * 6.5
      b.cyl(2.8, 2.8, 14, { mat: 'metal', color: '#c8ccd0', x: sx, y: 7, z: -d / 2 - 4, seg: 14 })
      b.cone(2.9, 2.4, { mat: 'metal', color: '#b8bcc0', x: sx, y: 15.2, z: -d / 2 - 4, seg: 14 })
    }
    b.cyl(0.5, 0.5, w * 0.6, { mat: 'rust', color: '#a88a70', x: 0, y: H - 1, z: -d / 2 - 1.2, rz: Math.PI / 2, seg: 8 })
  }
}

export function tankFarm(b, lot, r, P) {
  const lw = lot.fw
  const ld = lot.fd
  slab(b, -lw / 2 + 2, -ld / 2 + 2, lw / 2 - 2, ld / 2 - 2, 'gravel', '#a8a090', 0.03)
  const n = lw > 70 ? 4 : 2
  const rad = Math.min(9, (lw - 10) / (n * 1.15) / 2, (ld - 12) / 4)
  for (let k = 0; k < n; k++) {
    const x = -lw / 2 + 6 + rad + (k % (n / 2)) * (rad * 2 + 6)
    const z = Math.floor(k / (n / 2)) === 0 ? -ld / 4 : ld / 4 - 2
    const h = 8 + r() * 4
    const burnt = r() < 0.18
    b.cyl(rad, rad, h, { mat: burnt ? 'rust' : 'metal', color: burnt ? '#3a3230' : pick(r, ['#e0e0dc', '#d0d4d0', '#c8ccc4']), x, y: h / 2, z, seg: 18 })
    b.cyl(rad * 1.02, rad * 0.96, 0.6, { mat: 'metal', color: '#b8bcb8', x, y: h + 0.3, z, seg: 18 })
    b.beam([x + rad, 0, z], [x + rad * 0.3, h, z + rad * 0.95], 0.8, 0.1, { mat: 'paint', color: '#4a4e50' })
    // bund wall
    for (const sz of [-1, 1]) b.box(rad * 2 + 4, 1.0, 0.4, { mat: 'concrete', color: '#a8a49c', x, y: 0.5, z: z + sz * (rad + 2) })
    for (const sx of [-1, 1]) b.box(0.4, 1.0, rad * 2 + 4, { mat: 'concrete', color: '#a8a49c', x: x + sx * (rad + 2), y: 0.5, z })
    if (burnt) P.add('smoke', worldXZ(b, x, z).x, worldXZ(b, x, z).z, { y: h, s: 1.3 })
  }
  b.cyl(0.4, 0.4, lw - 12, { mat: 'rust', color: '#8a7a6a', y: 1.6, z: 0, rz: Math.PI / 2, seg: 6 })
  chainFence(b, [
    [-lw / 2 + 1, ld / 2 - 1],
    [lw / 2 - 1, ld / 2 - 1],
  ])
}

export function workYard(b, lot, r, P) {
  const lw = lot.fw
  const ld = lot.fd
  const kind = pick(r, ['containers', 'containers', 'trucks', 'scrap'])
  slab(b, -lw / 2 + 1, -ld / 2 + 1, lw / 2 - 1, ld / 2 - 1, kind === 'scrap' ? 'gravel' : 'asphalt', kind === 'scrap' ? '#9a9080' : '#8a8a86', 0.035)
  if (kind === 'containers') {
    const cols = ['#a8442a', '#2a5a8a', '#3a7a4a', '#c8a030', '#8a8a86', '#5a3a6a', '#d0ccc4', '#2a6a7a']
    for (let x = -lw / 2 + 5; x < lw / 2 - 5; x += 3.2) {
      for (let z = -ld / 2 + 9; z < ld / 2 - 9; z += 15) {
        const st = Math.floor(r() * 4)
        for (let k = 0; k < st; k++) P.put(b, 'container', x, z, { y: k * 2.62, c: pick(r, cols), ry: r() < 0.06 ? 0.08 : 0 })
      }
    }
    if (r() < 0.5) P.put(b, 'crane', lw / 2 - 6, -ld / 2 + 6, { ry: r() * TAU })
  } else if (kind === 'trucks') {
    for (let x = -lw / 2 + 5; x < lw / 2 - 5; x += 4.2) if (r() < 0.7) P.put(b, r() < 0.6 ? 'semi' : 'truck', x, (r() - 0.5) * 6, { ry: r() < 0.5 ? 0 : Math.PI, c: pick(r, ['#c8c4bc', '#8a2a2a', '#2a4a7a', '#e8e4dc']) })
  } else {
    for (let k = 0; k < Math.floor((lw * ld) / 90); k++) P.put(b, r() < 0.6 ? 'car-pile' : 'tires', (r() - 0.5) * (lw - 6), (r() - 0.5) * (ld - 6), { ry: r() * TAU })
    b.box(8, 3.2, 5, { mat: 'corrugated', color: '#8a8a7e', x: -lw / 2 + 6, y: 1.6, z: ld / 2 - 5 })
  }
  chainFence(b, [
    [-lw / 2 + 0.6, -ld / 2 + 0.6],
    [lw / 2 - 0.6, -ld / 2 + 0.6],
    [lw / 2 - 0.6, ld / 2 - 0.6],
    [6, ld / 2 - 0.6],
  ])
  chainFence(b, [
    [-6, ld / 2 - 0.6],
    [-lw / 2 + 0.6, ld / 2 - 0.6],
    [-lw / 2 + 0.6, -ld / 2 + 0.6],
  ])
}

// ---------------------------------------------------------------- civic
export function church(b, o, r, P) {
  const w = 10
  const d = 20
  const stone = '#c8c0b0'
  const H = 8
  b.box(w, H, d, { mat: 'plaster', color: stone, y: H / 2, z: -2 })
  gableRoof(b, { w: d, d: w, y: H, rise: 5.2, mat: 'shingles', color: '#3a3e44', gableMat: 'plaster', gableColor: stone, over: 0.4, ry: Math.PI / 2, z: -2 })
  for (const sx of [-1, 1])
    for (let k = 0; k < 5; k++) {
      b.box(0.1, 3.6, 1.2, { mat: 'glowAmber', color: '#6a3a5a', x: sx * (w / 2 + 0.02), y: 4, z: -9 + k * 3.6 })
      b.cyl(0.6, 0.6, 0.1, { mat: 'glowAmber', color: '#5a2a4a', x: sx * (w / 2 + 0.02), y: 5.8, z: -9 + k * 3.6, rz: Math.PI / 2, seg: 10, ts: 0, tl: Math.PI })
    }
  // bell tower with a spire
  b.box(5, 16, 5, { mat: 'plaster', color: stone, y: 8, z: d / 2 - 2 })
  b.at({ z: d / 2 - 2 }, () => {
    for (let s = 0; s < 4; s++) b.at({ ry: (s * Math.PI) / 2 }, () => b.box(1.4, 2.6, 0.2, { mat: 'plain', color: '#2a2622', y: 13, z: 2.55 }))
  })
  b.cyl(0.01, 3.9, 10, { mat: 'shingles', color: '#3a3e44', y: 21, z: d / 2 - 2, seg: 4, ry: Math.PI / 4 })
  b.box(0.2, 2.2, 0.2, { mat: 'steel', color: '#c8b070', y: 27, z: d / 2 - 2 })
  b.box(1.2, 0.2, 0.2, { mat: 'steel', color: '#c8b070', y: 27.4, z: d / 2 - 2 })
  b.box(2.2, 3.4, 0.2, { mat: 'wood', color: '#5a3a2a', y: 1.7, z: d / 2 + 0.55 })
  b.box(6, 0.5, 2.5, { mat: 'concrete', color: '#b8b4ac', y: 0.25, z: d / 2 + 1.6 })
  // graveyard behind
  for (let k = 0; k < 26; k++) P.put(b, r() < 0.25 ? 'cross' : 'tomb', -w / 2 - 3 + (k % 6) * 2.6 + r() * 0.5, -d / 2 - 6 - Math.floor(k / 6) * 2.4, { ry: (r() - 0.5) * 0.3 })
}

// ---------------------------------------------------------------- open space
export function park(b, lot, r, P, opts = {}) {
  const lw = lot.fw
  const ld = lot.fd
  const pond = lot.bld?.pond && lw > 50 && ld > 40
  // gravel paths: a loop and a cross
  const loopR = Math.min(lw, ld) * 0.36
  const segs = 28
  for (let k = 0; k < segs; k++) {
    const a = (k / segs) * TAU
    const a2 = ((k + 1) / segs) * TAU
    const ax = Math.cos(a) * loopR * (lw / Math.min(lw, ld))
    const az = Math.sin(a) * loopR * (ld / Math.min(lw, ld)) * 0.9
    const bx = Math.cos(a2) * loopR * (lw / Math.min(lw, ld))
    const bz = Math.sin(a2) * loopR * (ld / Math.min(lw, ld)) * 0.9
    b.beam([ax, 0.04, az], [bx, 0.04, bz], 2.6, 0.06, { mat: 'gravel', color: '#c0b8a4', extend: 0.4, shadow: false })
  }
  b.beam([0, 0.035, -ld / 2], [0, 0.035, ld / 2], 3, 0.06, { mat: 'gravel', color: '#c0b8a4', shadow: false })
  if (pond) {
    const pr = Math.min(lw, ld) * 0.17
    b.cyl(pr, pr, 0.1, { mat: 'mapGlass', color: '#3a5a52', x: lw * 0.18, y: 0.05, z: -ld * 0.08, seg: 26, sx: 1.4 })
    b.cyl(pr + 0.8, pr + 0.8, 0.06, { mat: 'gravel', color: '#9a9484', x: lw * 0.18, y: 0.03, z: -ld * 0.08, seg: 26, sx: 1.4 })
    for (let k = 0; k < 10; k++) {
      const a = r() * TAU
      P.put(b, 'bush', lw * 0.18 + Math.cos(a) * (pr + 1.2) * 1.4, -ld * 0.08 + Math.sin(a) * (pr + 1.2), { s: 0.6 + r() * 0.5, c: pick(r, LEAF) })
    }
  } else {
    // statue on a plinth at the crossing
    b.box(2.4, 1.6, 2.4, { mat: 'concrete', color: '#b8b4ac', y: 0.8 })
    b.capsule(0.45, 1.3, { mat: 'metal', color: '#5a7a68', y: 2.8 })
    b.sphere(0.32, { mat: 'metal', color: '#5a7a68', y: 3.95 })
  }
  // gazebo
  if (lw > 40) {
    const gx = -lw * 0.22
    const gz = ld * 0.18
    b.cyl(3.2, 3.2, 0.4, { mat: 'planks', color: '#c8b8a0', x: gx, y: 0.2, z: gz, seg: 8 })
    for (let k = 0; k < 8; k++) b.box(0.18, 2.6, 0.18, { mat: 'paint', color: '#ece8e0', x: gx + Math.cos((k / 8) * TAU) * 2.9, y: 1.7, z: gz + Math.sin((k / 8) * TAU) * 2.9 })
    b.cyl(0.01, 3.8, 1.8, { mat: 'shingles', color: '#5a4a44', x: gx, y: 3.9, z: gz, seg: 8 })
  }
  // trees in loose groves, benches along the loop, a playground
  const nt = Math.floor((lw * ld) / 140)
  for (let k = 0; k < nt; k++) {
    const x = (r() - 0.5) * (lw - 4)
    const z = (r() - 0.5) * (ld - 4)
    if (Math.abs(x) < 2.5) continue
    putTree(P, b, x, z, r, pick(r, ['oak', 'oak', 'maple', 'poplar', 'pine', 'spruce']))
  }
  for (let k = 0; k < 8; k++) {
    const a = (k / 8) * TAU + 0.2
    P.put(b, 'bench', Math.cos(a) * (loopR + 2.2) * (lw / Math.min(lw, ld)), Math.sin(a) * (loopR + 2.2) * (ld / Math.min(lw, ld)) * 0.9, { ry: -a - Math.PI / 2 })
  }
  if (r() < 0.7) {
    const px = lw * 0.28
    const pz = ld * 0.26
    slab(b, px - 6, pz - 5, px + 6, pz + 5, 'concrete', '#b07a5a', 0.05)
    P.put(b, 'swing', px - 2, pz, { ry: 0.2 })
    b.beam([px + 2, 2.2, pz - 2], [px + 4.6, 0.1, pz - 2], 0.7, 0.08, { mat: 'paint', color: '#d8b030' })
    b.box(1.6, 2.2, 1.6, { mat: 'paint', color: '#3a7ab8', x: px + 1.4, y: 1.1, z: pz - 2 })
  }
  if (opts.court && lw > 60) {
    const cx = -lw * 0.25
    const cz = -ld * 0.22
    slab(b, cx - 8, cz - 14, cx + 8, cz + 14, 'asphalt', '#7a7e7a', 0.05)
    b.box(15, 0.02, 0.12, { mat: 'plain', color: '#e8e4dc', x: cx, y: 0.15, z: cz })
  }
}

export function emptyLot(b, lot, r, P) {
  const lw = lot.fw
  const ld = lot.fd
  for (let k = 0; k < 3; k++) P.put(b, 'rubble', (r() - 0.5) * (lw - 4), (r() - 0.5) * (ld - 4), { ry: r() * TAU, s: 0.8 + r() * 0.8 })
  for (let k = 0; k < 4; k++) P.put(b, 'bush', (r() - 0.5) * (lw - 2), (r() - 0.5) * (ld - 2), { s: 0.5 + r() * 0.6, c: pick(r, ['#7a8a48', '#8a8a50', '#6a7a40']) })
  if (r() < 0.5) putCar(P, b, (r() - 0.5) * (lw - 6), (r() - 0.5) * (ld - 8), r() * TAU, r, { burnt: true })
}

export function parkingLot(b, lot, r, P) {
  const lw = lot.fw
  const ld = lot.fd
  slab(b, -lw / 2 + 0.5, -ld / 2 + 0.5, lw / 2 - 0.5, ld / 2 - 0.5, 'asphalt', '#7e7e7a', 0.035)
  const rows = Math.max(1, Math.floor((ld - 6) / 12))
  for (let row = 0; row < rows; row++) {
    const z = -ld / 2 + 6 + row * 12
    for (let x = -lw / 2 + 2; x < lw / 2 - 2; x += 2.7) {
      b.box(0.12, 0.02, 5, { mat: 'plain', color: '#e8e4d8', x, y: 0.135, z, shadow: false })
      if (r() < 0.45) putCar(P, b, x + 1.35, z, r() < 0.5 ? 0 : Math.PI, r)
    }
  }
  for (let k = 0; k < 2; k++) P.put(b, 'lamp', -lw / 4 + k * (lw / 2), 0, { ry: r() * TAU })
}

// Gas station: canopy over pumps, a small shop, a price pylon.
export function gasStation(b, o, r, P) {
  const lw = o.lw
  const ld = o.ld
  slab(b, -lw / 2 + 0.5, -ld / 2 + 0.5, lw / 2 - 0.5, ld / 2 - 0.5, 'concrete', '#b8b4aa', 0.04)
  const brand = o.brand || pick(r, ['#c8302a', '#2a6ab0', '#2a8a4a', '#e8a020'])
  // shop at the back
  const sw = Math.min(16, lw - 6)
  const sd = 10
  const sz = -ld / 2 + sd / 2 + 2
  b.at({ z: sz }, () => {
    b.box(sw, 4.2, sd, { mat: 'plaster', color: '#e0dcd4', y: 2.1 })
    b.at({ z: sd / 2 }, () => storefront(b, sw, { sh: 4.2, sign: o.sign, band: brand }, r))
    flatRoof(b, sw, sd, 4.2, { rnd: r, parapet: 0.5, clutter: 2, hatch: false })
  })
  // canopy and pumps
  const cz = sz + sd / 2 + 9
  const cw = Math.min(lw - 6, 18)
  b.box(cw, 1.0, 9, { mat: 'paint', color: '#e8e4dc', y: 5.4, z: cz })
  b.box(cw + 0.1, 0.45, 9.1, { mat: 'paint', color: brand, y: 5.2, z: cz })
  b.box(cw - 0.6, 0.06, 8.4, { mat: 'glowWhite', color: '#c8c4bc', y: 4.88, z: cz })
  for (const sx of [-1, 1]) b.box(0.5, 4.9, 0.5, { mat: 'paint', color: '#d8d4cc', x: (sx * cw) / 4, y: 2.45, z: cz })
  for (const sx of [-1, 1])
    for (const sz2 of [-1, 1]) {
      const px = (sx * cw) / 4
      const pz = cz + sz2 * 1.6
      b.box(1.6, 0.25, 1.0, { mat: 'concrete', color: '#c8c4bc', x: px, y: 0.12, z: pz })
      b.box(0.9, 1.8, 0.55, { mat: 'paint', color: '#d8d4cc', x: px, y: 1.15, z: pz })
      b.box(0.92, 0.3, 0.57, { mat: 'paint', color: brand, x: px, y: 1.9, z: pz })
    }
  // price pylon by the street
  b.box(0.5, 6.5, 0.5, { mat: 'paint', color: '#4a4e52', x: lw / 2 - 2.5, y: 3.25, z: ld / 2 - 2 })
  b.box(2.6, 3.2, 0.4, { mat: 'paint', color: brand, x: lw / 2 - 2.5, y: 6.4, z: ld / 2 - 2 })
  b.box(2.2, 1.6, 0.42, { mat: 'paint', color: '#1e1e1e', x: lw / 2 - 2.5, y: 5.9, z: ld / 2 - 2 })
  if (r() < 0.7) putCar(P, b, (r() < 0.5 ? -1 : 1) * (cw / 4) + 2, cz, r() < 0.5 ? 0 : Math.PI, r)
}

// ---------------------------------------------------------------- any filler lot
export function fillerLot(b, lot, r, P) {
  const bld = lot.bld
  const lw = lot.fw
  const ld = lot.fd
  const fire = !!bld.fire
  const k = bld.kind
  if (k === 'house') return houseLot(b, lot, r, P)
  if (k === 'park') return park(b, lot, r, P, { court: lot.fw > 90 })
  if (k === 'empty') return emptyLot(b, lot, r, P)
  if (k === 'parking') return parkingLot(b, lot, r, P)
  if (k === 'plaza') return plaza(b, lot, r, P)
  if (k === 'yard') return workYard(b, lot, r, P)
  if (k === 'tanks') return tankFarm(b, lot, r, P)
  if (k === 'gas') return gasStation(b, { lw, ld }, r, P)
  if (k === 'church') {
    b.at({ z: ld / 2 - 13 }, () => church(b, {}, r, P))
    return
  }
  const burnt = bld.state === 'burnt'
  if (k === 'shop') {
    const w = Math.min(lw - 0.8, 26)
    const d = Math.min(ld - 6, 13 + r() * 6)
    b.at({ z: ld / 2 - d / 2 - 0.4 }, () => shop(b, { w, d, floors: bld.floors, brick: r() < 0.5, awning: r() < 0.45 ? pick(r, ['#2a5a3a', '#8a2a2a', '#2a4a6a', '#c8a030', '#6a3a5a']) : null, boarded: r() < 0.15, burnt, fire, lit: r() < 0.08 }, r, P))
    return
  }
  if (k === 'mixed') {
    const w = Math.min(lw - 0.6, 30)
    const d = Math.min(ld - 6, 15 + r() * 6)
    b.at({ z: ld / 2 - d / 2 - 0.4 }, () => mixed(b, { w, d, floors: bld.floors, awning: r() < 0.35 ? pick(r, ['#2a5a3a', '#8a2a2a', '#2a4a6a', '#c8a030']) : null, boarded: r() < 0.12, escape: r() < 0.55 ? (r() < 0.5 ? 1 : -1) : 0, tank: r() < 0.5, burnt, fire, lit: r() < 0.25 ? 0.03 : 0 }, r, P))
    return
  }
  if (k === 'apartment') {
    const w = Math.min(lw - 3, 28)
    const d = Math.min(ld - 10, 16)
    b.at({ z: ld / 2 - d / 2 - 3 }, () => apartment(b, { w, d, floors: bld.floors, brick: r() < 0.4, balconies: r() < 0.6, escape: r() < 0.4, burnt, fire, lit: 0.02 }, r, P))
    return
  }
  if (k === 'office') {
    const w = Math.min(lw - 6, 42)
    const d = Math.min(ld - 8, 32)
    b.at({ z: ld / 2 - d / 2 - 5 }, () => office(b, { w, d, floors: bld.floors, fire }, r, P))
    if (lot.district === 'downtown') forecourt(b, lot, w, 5, r, P)
    return
  }
  if (k === 'tower') {
    const w = Math.min(lw - 8, 40)
    const d = Math.min(ld - 10, 38)
    b.at({ z: ld / 2 - d / 2 - 6 }, () => tower(b, { w, d, floors: bld.floors, stages: r() < 0.4 ? 1 : 2, fire }, r, P))
    forecourt(b, lot, w, 6, r, P)
    return
  }
  if (k === 'garage') {
    const w = Math.min(lw - 4, 50)
    const d = Math.min(ld - 6, 36)
    b.at({ z: ld / 2 - d / 2 - 2 }, () => parkingGarage(b, { w, d, floors: bld.floors }, r, P))
    return
  }
  if (k === 'warehouse') {
    const w = Math.min(lw - 14, 90)
    const d = Math.min(ld - 26, 60)
    if (w < 16 || d < 12) return workYard(b, lot, r, P)
    b.at({ z: ld / 2 - d / 2 - 18 }, () => warehouse(b, { w, d, burnt, fire }, r, P))
    return
  }
  if (k === 'factory') {
    const w = Math.min(lw - 14, 70)
    const d = Math.min(ld - 18, 46)
    if (w < 16 || d < 12) return workYard(b, lot, r, P)
    b.at({ z: ld / 2 - d / 2 - 6 }, () => factory(b, { w, d }, r, P))
  }
}

// Paved forecourt in front of a downtown building: planters, trees, benches.
export function forecourt(b, lot, bw, depth, r, P) {
  const ld = lot.fd
  const z = ld / 2 - depth / 2
  slab(b, -lot.fw / 2 + 0.5, ld / 2 - depth, lot.fw / 2 - 0.5, ld / 2 - 0.2, 'pavers', '#b8ae9e', 0.04)
  const n = Math.max(2, Math.floor(bw / 9))
  for (let k = 0; k < n; k++) {
    const x = -bw / 2 + 3 + (k * (bw - 6)) / Math.max(1, n - 1)
    b.box(2.2, 0.6, 2.2, { mat: 'concrete', color: '#9a968e', x, y: 0.4, z })
    P.put(b, 'tree-maple', x, z, { ry: r() * TAU, s: 0.6, y: 0.5, c: pick(r, LEAF) })
    if (k < n - 1 && r() < 0.6) P.put(b, 'bench', x + (bw - 6) / Math.max(1, n - 1) / 2, z, { ry: Math.PI })
  }
}

