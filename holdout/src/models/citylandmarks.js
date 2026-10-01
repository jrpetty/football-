// The supply-run locations on the city map, each built to read at a glance
// from the air: the hospital's helipad and triage tents, the police station's
// radio mast and barricades, the fire station's red bay doors and hose tower,
// the supermarket's car park, the military checkpoint astride the highway.
// Built in the lot-local frame (+z toward the street) on the location's site,
// with the same footprint the supply-run level uses.
import { gableRoof } from './parts.js'
import { facadeWindows, eachFace, flatRoof, signPlane, addSign, LEAF } from './citykit.js'
import { house, apartment, shop, office, warehouse, slab, putCar, putTree } from './citybuildings.js'

const TAU = Math.PI * 2
const pick = (r, a) => a[Math.floor(r() * a.length)]

function lotGround(b, s, mat, color) {
  slab(b, -s.lw / 2 + 0.3, -s.ld / 2 + 0.3, s.lw / 2 - 0.3, s.ld / 2 - 0.3, mat, color, 0.04)
}
function parkingRows(b, s, z0, z1, r, P, fill = 0.5, opts = {}) {
  const lw = s.lw
  slab(b, -lw / 2 + 0.6, z0, lw / 2 - 0.6, z1, 'asphalt', '#7a7a76', 0.05)
  const rows = Math.max(1, Math.floor((z1 - z0 - 2) / 11))
  for (let row = 0; row < rows; row++) {
    const z = z0 + 6 + row * 11
    for (let x = -lw / 2 + 2.4; x < lw / 2 - 2.4; x += 2.7) {
      b.box(0.12, 0.02, 4.8, { mat: 'plain', color: '#e8e4d8', x, y: 0.15, z, shadow: false })
      if (r() < fill) putCar(P, b, x + 1.35, z, r() < 0.5 ? 0 : Math.PI, r, opts)
    }
  }
}
function sideLot(b, s, r, P, n = 3) {
  if (!s.side) return
  const x0 = s.side > 0 ? s.bx + s.bw / 2 + 1 : -s.lw / 2 + 0.6
  const x1 = s.side > 0 ? s.lw / 2 - 0.6 : s.bx - s.bw / 2 - 1
  if (x1 - x0 < 4) return
  slab(b, x0, -s.ld / 2 + 2, x1, s.ld / 2 - 0.3, 'asphalt', '#7a7a76', 0.05)
  for (let k = 0; k < n; k++) if (r() < 0.75) putCar(P, b, x0 + 2 + r() * (x1 - x0 - 4), -s.ld / 2 + 6 + r() * (s.ld - 12), r() < 0.5 ? 0 : Math.PI, r)
}
function bigSign(b, text, w, h, o, bg, fg) {
  signPlane(b, addSign(text, bg, fg), w, h, o)
}

const L = {}

L.house = (b, loc, s, r, P) => {
  b.at({ x: s.bx, z: s.bz }, () => house(b, { w: s.bw, d: s.bd, floors: 2, wall: pick(r, ['#d6c8a8', '#a8b6ba', '#c9ad8c', '#e0d8c4']), roof: pick(r, ['#4a4644', '#5a4a40', '#3e4446']), roofType: 'gable', state: 'ok', chimney: 1, porch: true, garage: 0, shutters: '#3a4a3a', door: '#6a2a2a', doorX: -2 }, r, P))
  slab(b, s.bx - 2.6, s.bz + s.bd / 2 + 2.2, s.bx - 1.4, s.ld / 2, 'concrete', '#b4b0a6')
  slab(b, s.lw / 2 - 4.5, s.bz - s.bd / 2, s.lw / 2 - 0.8, s.ld / 2, 'concrete', '#aaa69c', 0.035)
  putCar(P, b, s.lw / 2 - 2.6, s.ld / 2 - 4.5, Math.PI, r)
  for (let k = 0; k < 3; k++) putTree(P, b, -s.lw / 2 + 2 + r() * 4, -s.ld / 2 + 3 + r() * (s.ld - 8), r)
  P.put(b, 'mailbox', s.bx - 3.2, s.ld / 2 - 0.5, { c: '#2a4a8a' })
}

L.apartment = (b, loc, s, r, P) => {
  lotGround(b, s, 'concrete', '#b8b4aa')
  b.at({ x: s.bx, z: s.bz }, () => apartment(b, { w: s.bw, d: s.bd, floors: 4, brick: true, wall: '#e8d8d0', balconies: true, escape: true, tank: true, lit: 0.05 }, r, P))
  P.put(b, 'dumpster', s.bx - s.bw / 2 + 2, s.bz - s.bd / 2 - 1.8, { c: '#3a6a4a' })
  P.put(b, 'dumpster', s.bx - s.bw / 2 + 4.2, s.bz - s.bd / 2 - 1.8, { c: '#3a6a4a' })
}

L.store = (b, loc, s, r, P) => {
  lotGround(b, s, 'concrete', '#b8b4aa')
  const sign = addSign(loc.name.toUpperCase(), pick(r, ['#c8302a', '#2a6a3a', '#2a4a8a']), '#fff8e0')
  b.at({ x: s.bx, z: s.bz }, () => shop(b, { w: s.bw, d: s.bd, floors: 1, brick: true, awning: '#c8302a', sign }, r, P))
  sideLot(b, s, r, P, 3)
  // ice chest and propane cage by the door
  b.box(1.6, 1.2, 0.8, { mat: 'paint', color: '#e8eef0', x: s.bx + s.bw / 2 - 1.5, y: 0.6, z: s.bz + s.bd / 2 + 0.6 })
  b.box(1.4, 1.4, 0.9, { mat: 'steel', color: '#8a9094', x: s.bx - s.bw / 2 + 1.5, y: 0.7, z: s.bz + s.bd / 2 + 0.6 })
}

L.office = (b, loc, s, r, P) => {
  lotGround(b, s, 'pavers', '#c8c0b0')
  b.at({ x: s.bx, z: s.bz }, () => office(b, { w: s.bw, d: s.bd, floors: 5, setback: false }, r, P))
  b.at({ x: s.bx, z: s.bz + s.bd / 2 + 2.2 }, () => bigSign(b, loc.name.toUpperCase(), 9, 1.1, { y: 0.8 }, '#2a3a4a', '#e8e8e8'))
  sideLot(b, s, r, P, 4)
}

L.diner = (b, loc, s, r, P) => {
  lotGround(b, s, 'asphalt', '#7a7a76')
  const accent = pick(r, ['#c8302a', '#2a8a8a', '#d86a8a'])
  b.at({ x: s.bx, z: s.bz }, () => {
    const w = s.bw
    const d = s.bd
    // stainless steel railcar body
    b.box(w, 4.2, d, { mat: 'metal', color: '#d8dcdc', y: 2.1, r: 0.9, seg: 3 })
    b.box(w + 0.1, 0.5, d + 0.1, { mat: 'paint', color: accent, y: 1.0 })
    b.box(w + 0.1, 0.35, d + 0.1, { mat: 'paint', color: accent, y: 3.9 })
    b.at({ z: d / 2 }, () => b.box(w - 2, 1.6, 0.12, { mat: 'mapGlass', color: '#4a5a64', y: 2.3, z: 0.06 }))
    b.at({ x: w / 2, ry: Math.PI / 2 }, () => b.box(d - 3, 1.6, 0.12, { mat: 'mapGlass', color: '#4a5a64', y: 2.3, z: 0.06 }))
    b.box(2.4, 2.8, 2.4, { mat: 'paint', color: '#e8e4dc', x: -w / 4, y: 1.4, z: d / 2 + 1.0 })
    flatRoof(b, w - 1, d - 1, 4.2, { rnd: r, parapet: 0, clutter: 3, hatch: false })
    // roof sign
    b.box(0.3, 2.2, 0.3, { mat: 'steel', color: '#5a5e60', x: 0, y: 5.3, z: d / 2 - 2 })
    bigSign(b, loc.name.toUpperCase(), 8, 1.4, { y: 6.4, z: d / 2 - 1.85 }, accent, '#fff4d0')
  })
  // pylon sign at the street
  b.box(0.5, 7, 0.5, { mat: 'paint', color: '#5a5e60', x: -s.lw / 2 + 2.5, y: 3.5, z: s.ld / 2 - 2 })
  b.box(3.4, 2.2, 0.5, { mat: 'paint', color: accent, x: -s.lw / 2 + 2.5, y: 7.4, z: s.ld / 2 - 2 })
  bigSign(b, 'EAT', 3, 1.4, { x: -s.lw / 2 + 2.5, y: 7.4, z: s.ld / 2 - 1.74 }, '#1e1e1e', '#ffd860')
  sideLot(b, s, r, P, 4)
}

L.gas = (b, loc, s, r, P) => {
  lotGround(b, s, 'concrete', '#b8b4aa')
  const brand = pick(r, ['#c8302a', '#2a6ab0', '#2a8a4a', '#e8a020'])
  const sign = addSign(loc.name.toUpperCase(), brand, '#ffffff')
  b.at({ x: s.bx, z: s.bz }, () => {
    const w = s.bw
    const d = s.bd
    b.box(w, 4.2, d, { mat: 'plaster', color: '#e0dcd4', y: 2.1 })
    b.at({ z: d / 2 }, () => {
      b.box(w - 1.2, 2.5, 0.1, { mat: 'mapGlass', color: '#3e4e58', y: 1.85, z: 0.03 })
      b.box(w - 0.3, 0.9, 0.2, { mat: 'paint', color: brand, y: 3.5, z: 0.06 })
      signPlane(b, sign, Math.min(w - 1.2, 8), 0.78, { y: 3.5, z: 0.17 })
    })
    flatRoof(b, w, d, 4.2, { rnd: r, parapet: 0.5, clutter: 2, hatch: false })
    // service bay on the side
    b.box(5, 4.6, d, { mat: 'concrete', color: '#c8c4bc', x: w / 2 + 2.5, y: 2.3 })
    b.box(3.6, 3.6, 0.12, { mat: 'roofmetal', color: '#c8c8c0', x: w / 2 + 2.5, y: 1.8, z: d / 2 + 0.05 })
  })
  // canopy over two pump islands
  const cz = s.bz + s.bd / 2 + 9.5
  const cw = Math.min(s.lw - 4, 16)
  b.box(cw, 1.0, 9, { mat: 'paint', color: '#e8e4dc', y: 5.4, z: cz })
  b.box(cw + 0.1, 0.45, 9.1, { mat: 'paint', color: brand, y: 5.2, z: cz })
  for (const sx of [-1, 1]) b.box(0.5, 4.9, 0.5, { mat: 'paint', color: '#d8d4cc', x: (sx * cw) / 4, y: 2.45, z: cz })
  for (const sx of [-1, 1])
    for (const sz of [-1, 1]) {
      const px = (sx * cw) / 4
      b.box(1.6, 0.25, 1.0, { mat: 'concrete', color: '#c8c4bc', x: px, y: 0.12, z: cz + sz * 1.6 })
      b.box(0.9, 1.8, 0.55, { mat: 'paint', color: '#d8d4cc', x: px, y: 1.15, z: cz + sz * 1.6 })
      b.box(0.92, 0.3, 0.57, { mat: 'paint', color: brand, x: px, y: 1.9, z: cz + sz * 1.6 })
    }
  b.box(0.5, 7, 0.5, { mat: 'paint', color: '#4a4e52', x: s.lw / 2 - 2, y: 3.5, z: s.ld / 2 - 1.5 })
  b.box(2.8, 3.4, 0.4, { mat: 'paint', color: brand, x: s.lw / 2 - 2, y: 7.0, z: s.ld / 2 - 1.5 })
  putCar(P, b, cw / 4 + 2.2, cz, 0, r)
  if (r() < 0.6) putCar(P, b, -cw / 4 - 2.2, cz + 1, Math.PI, r, { burnt: true })
}

L.hardware = (b, loc, s, r, P) => {
  lotGround(b, s, 'asphalt', '#7a7a76')
  const sign = addSign(loc.name.toUpperCase(), '#c8501a', '#ffffff')
  b.at({ x: s.bx, z: s.bz }, () => {
    const w = s.bw
    const d = s.bd
    b.box(w, 6.4, d, { mat: 'brick', color: '#e8d8d0', y: 3.2 })
    b.at({ z: d / 2 }, () => {
      b.box(8, 3.0, 0.12, { mat: 'mapGlass', color: '#3e4e58', y: 1.6, z: 0.04 })
      b.box(w - 2, 1.6, 0.25, { mat: 'paint', color: '#c8501a', y: 5.2, z: 0.1 })
      signPlane(b, sign, Math.min(w - 3, 14), 1.4, { y: 5.2, z: 0.24 })
    })
    flatRoof(b, w, d, 6.4, { rnd: r, parapet: 0.7, parMat: 'brick', parColor: '#e8d8d0', clutter: 5 })
  })
  // garden centre and lumber yard
  if (s.side) {
    const gx = s.bx + s.side * (s.bw / 2 + 6.5)
    for (let k = 0; k < 4; k++) b.box(4.4, 0.6 + r() * 0.6, 1.2, { mat: 'wood', color: '#d8c0a0', x: gx, y: 0.5, z: s.bz - s.bd / 2 + 3 + k * 2.6 })
    for (let k = 0; k < 3; k++) P.put(b, 'pallets', gx + (r() - 0.5) * 6, s.bz + s.bd / 2 - 2 - k * 2.2, { ry: r() })
    for (let k = 0; k < 6; k++) P.put(b, 'bush', gx + (r() - 0.5) * 8, s.bz + (r() - 0.5) * s.bd, { s: 0.5, c: pick(r, LEAF) })
  }
  parkingRows(b, s, s.bz + s.bd / 2 + 2, s.ld / 2 - 0.5, r, P, 0.35)
}

L.pharmacy = (b, loc, s, r, P) => {
  lotGround(b, s, 'asphalt', '#7a7a76')
  const sign = addSign(loc.name.toUpperCase(), '#ffffff', '#2a8a4a')
  b.at({ x: s.bx, z: s.bz }, () => {
    const w = s.bw
    const d = s.bd
    b.box(w, 5.4, d, { mat: 'plaster', color: '#e8e8e4', y: 2.7 })
    b.at({ z: d / 2 }, () => {
      b.box(w - 2, 3.0, 0.12, { mat: 'mapGlass', color: '#3e4e58', y: 1.7, z: 0.04 })
      signPlane(b, sign, Math.min(w - 4, 11), 1.1, { y: 4.3, z: 0.08 })
    })
    // green cross
    b.at({ x: w / 2 - 1.2, z: d / 2 + 0.6 }, () => {
      b.box(0.3, 0.3, 1.0, { mat: 'paint', color: '#8a8e90', y: 4.6, z: -0.3 })
      b.box(1.6, 0.5, 0.2, { mat: 'glowGreen', color: '#2a8a4a', y: 4.6, z: 0.2 })
      b.box(0.5, 1.6, 0.2, { mat: 'glowGreen', color: '#2a8a4a', y: 4.6, z: 0.2 })
    })
    flatRoof(b, w, d, 5.4, { rnd: r, parapet: 0.6, parMat: 'plaster', parColor: '#e8e8e4', clutter: 3 })
    // drive-through canopy
    b.box(4, 0.4, d * 0.7, { mat: 'paint', color: '#2a8a4a', x: -w / 2 - 2, y: 3.6 })
    b.box(0.3, 3.6, 0.3, { mat: 'paint', color: '#d8d4cc', x: -w / 2 - 3.8, y: 1.8 })
  })
  sideLot(b, s, r, P, 3)
}

L.supermarket = (b, loc, s, r, P) => {
  lotGround(b, s, 'asphalt', '#7a7a76')
  const sign = addSign(loc.name.toUpperCase(), '#2a7a3a', '#fff4d0')
  b.at({ x: s.bx, z: s.bz }, () => {
    const w = s.bw
    const d = s.bd
    b.box(w, 7.2, d, { mat: 'plaster', color: '#d8d0c0', y: 3.6 })
    b.box(w + 0.2, 1.2, d + 0.2, { mat: 'paint', color: '#2a7a3a', y: 6.8 })
    b.at({ z: d / 2 }, () => {
      b.box(w * 0.6, 3.2, 0.12, { mat: 'mapGlass', color: '#3e4e58', y: 1.7, z: 0.04 })
      b.box(9, 4.6, 3.4, { mat: 'mapGlass', color: '#4a5a64', y: 2.3, z: 1.7 })
      b.box(9.6, 0.4, 4.0, { mat: 'paint', color: '#2a7a3a', y: 4.8, z: 1.7 })
      signPlane(b, sign, Math.min(w - 6, 16), 1.8, { y: 6.0, z: 0.14 })
    })
    flatRoof(b, w, d, 7.2, { rnd: r, parapet: 0.6, clutter: 8 })
    // loading bay at the back
    b.box(10, 4, 0.14, { mat: 'roofmetal', color: '#c8c8c0', x: w / 4, y: 2.2, z: -d / 2 - 0.05 })
  })
  parkingRows(b, s, s.bz + s.bd / 2 + 3.4, s.ld / 2 - 0.5, r, P, 0.42)
  // cart corrals and stray carts
  for (let k = 0; k < 3; k++) b.box(0.8, 1.0, 5, { mat: 'steel', color: '#a8b0b4', x: -s.lw / 3 + k * (s.lw / 3), y: 0.5, z: s.ld / 2 - 7 })
}

L.garage = (b, loc, s, r, P) => {
  lotGround(b, s, 'concrete', '#a8a49a')
  const sign = addSign(loc.name.toUpperCase(), '#1e2a3a', '#e8c040')
  b.at({ x: s.bx, z: s.bz }, () => {
    const w = s.bw
    const d = s.bd
    b.box(w, 6, d, { mat: 'corrugated', color: '#b8bcb8', y: 3 })
    gableRoof(b, { w, d, y: 6, rise: 1.0, mat: 'roofmetal', color: '#8a8e8c', gableMat: 'corrugated', gableColor: '#b8bcb8', over: 0.3 })
    b.at({ z: d / 2 }, () => {
      for (let k = 0; k < 3; k++) {
        const x = -w / 2 + 4 + k * 6
        const open = r() < 0.5
        b.box(4.4, 4.2, 0.14, { mat: open ? 'plain' : 'roofmetal', color: open ? '#1e1e1e' : '#c8c8c0', x, y: 2.1, z: 0.05 })
      }
      b.box(w - 4, 1.0, 0.2, { mat: 'paint', color: '#1e2a3a', x: 0, y: 5.0, z: 0.1 })
      signPlane(b, sign, Math.min(w - 5, 13), 0.9, { y: 5.0, z: 0.22 })
    })
  })
  for (let k = 0; k < 6; k++) putCar(P, b, -s.lw / 2 + 3 + r() * (s.lw - 6), s.bz + s.bd / 2 + 3 + r() * Math.max(1, s.ld / 2 - s.bz - s.bd / 2 - 6), r() * TAU, r, { burnt: r() < 0.2 })
  for (let k = 0; k < 4; k++) P.put(b, 'tires', s.bx - s.bw / 2 - 1.5, s.bz - s.bd / 2 + 2 + k * 1.1, { ry: r() })
  sideLot(b, s, r, P, 3)
}

L.school = (b, loc, s, r, P) => {
  lotGround(b, s, 'grass', '#7a9a52')
  const sign = addSign(loc.name.toUpperCase(), '#2a3a6a', '#f0e8c8')
  b.at({ x: s.bx, z: s.bz }, () => {
    const w = s.bw
    const d = s.bd
    b.box(w, 7.4, d, { mat: 'brick', color: '#e8d8d0', y: 3.7 })
    eachFace(b, w, d, (len, face) => facadeWindows(b, len, { floors: 2, y0: 0.4, fh: 3.5, ww: 2.2, wh: 1.8, gap: 0.9, frame: '#e8e4dc', sill: '#d8d4cc', rnd: r, broken: 0.15, skip: (x, f) => face === 'front' && f === 0 && Math.abs(x) < 3 }))
    b.at({ z: d / 2 }, () => {
      for (const sx of [-1, 1]) b.box(0.6, 5, 0.6, { mat: 'concrete', color: '#e8e4dc', x: sx * 2.6, y: 2.5, z: 1.6 })
      b.box(6.4, 0.6, 3.4, { mat: 'concrete', color: '#e8e4dc', y: 5.2, z: 1.4 })
      b.box(3.2, 2.6, 0.12, { mat: 'mapGlass', color: '#3e4e58', y: 1.3, z: 0.05 })
      signPlane(b, sign, 6, 0.6, { y: 5.85, z: 3.12 })
    })
    flatRoof(b, w, d, 7.4, { rnd: r, parapet: 0.7, parMat: 'brick', parColor: '#e8d8d0', clutter: 6 })
  })
  // flagpole, buses, playground and court behind
  b.cyl(0.08, 0.1, 10, { mat: 'steel', color: '#c8ccd0', x: s.bx + 6, y: 5, z: s.bz + s.bd / 2 + 6, seg: 6 })
  b.box(0.04, 1.2, 1.9, { mat: 'canvas', color: '#a83a32', x: s.bx + 6, y: 9.2, z: s.bz + s.bd / 2 + 7 })
  slab(b, -s.lw / 2 + 2, s.bz + s.bd / 2 + 2, s.lw / 2 - 2, s.ld / 2 - 0.5, 'asphalt', '#7a7a76', 0.05)
  for (let k = 0; k < 2; k++) P.put(b, 'bus-school', -s.lw / 2 + 6 + k * 4, s.ld / 2 - 8, { ry: 0, c: '#e8b020' })
  slab(b, s.bx - 8, -s.ld / 2 + 2, s.bx + 8, s.bz - s.bd / 2 - 1.5, 'asphalt', '#8a7e74', 0.05)
  P.put(b, 'swing', s.bx + 4, -s.ld / 2 + 5, {})
}

L.hospital = (b, loc, s, r, P) => {
  lotGround(b, s, 'concrete', '#b8b4aa')
  const sign = addSign('EMERGENCY', '#c8302a', '#ffffff')
  const sign2 = addSign(loc.name.toUpperCase(), '#ffffff', '#2a4a7a')
  b.at({ x: s.bx, z: s.bz }, () => {
    const w = s.bw
    const d = s.bd
    // low wing with the ER and a five-storey ward tower
    b.box(w, 5.2, d, { mat: 'concrete', color: '#e4e2dc', y: 2.6 })
    eachFace(b, w, d, (len, face) => facadeWindows(b, len, { floors: 1, y0: 0.6, fh: 4.4, ww: 2.0, wh: 2.0, gap: 1.4, rnd: r, glass: '#3e5a6a', broken: 0.2, skip: (x) => face === 'front' && Math.abs(x) < 6 }))
    const tw = w * 0.62
    const td = d * 0.7
    const th = 30
    const top = 5.2 + th
    b.box(tw, th, td, { mat: 'concrete', color: '#ecebe6', x: -w * 0.15, y: 5.2 + th / 2, z: -d * 0.1 })
    b.at({ x: -w * 0.15, z: -d * 0.1 }, () => {
      eachFace(b, tw, td, (len) => {
        for (let f = 0; f < 8; f++) b.box(len - 1.6, 1.6, 0.12, { mat: 'mapGlass', color: '#3e5a6a', y: 5.2 + 1.8 + f * 3.6, z: 0.05 })
      })
      flatRoof(b, tw, td, top, { rnd: r, parapet: 0.9, clutter: 2 })
      // helipad
      b.cyl(Math.min(tw, td) * 0.36, Math.min(tw, td) * 0.36, 0.3, { mat: 'concrete', color: '#5a5c5e', y: top + 0.3, x: tw * 0.12, seg: 22 })
      b.torus(Math.min(tw, td) * 0.3, 0.16, { mat: 'paint', color: '#e8e4dc', y: top + 0.47, x: tw * 0.12, rx: Math.PI / 2, rs: 4, ts2: 22 })
      b.box(0.5, 0.05, 3.6, { mat: 'paint', color: '#e8e4dc', x: tw * 0.12 - 1.1, y: top + 0.48 })
      b.box(0.5, 0.05, 3.6, { mat: 'paint', color: '#e8e4dc', x: tw * 0.12 + 1.1, y: top + 0.48 })
      b.box(2.2, 0.05, 0.5, { mat: 'paint', color: '#e8e4dc', x: tw * 0.12, y: top + 0.48 })
      // red crosses high on the tower
      b.at({ z: td / 2 + 0.1 }, () => {
        b.box(3.6, 1.1, 0.2, { mat: 'glowRed', color: '#c8302a', y: top - 3 })
        b.box(1.1, 3.6, 0.2, { mat: 'glowRed', color: '#c8302a', y: top - 3 })
      })
    })
    flatRoof(b, w, d, 5.2, { rnd: r, parapet: 0.6, clutter: 4, hatch: false })
    // ER canopy and sign
    b.at({ z: d / 2 }, () => {
      b.box(12, 0.5, 6, { mat: 'concrete', color: '#e8e6e0', y: 4.3, z: 3 })
      for (const sx of [-1, 1]) b.box(0.5, 4.1, 0.5, { mat: 'concrete', color: '#d8d6d0', x: sx * 5.5, y: 2.05, z: 5.6 })
      b.box(10, 3.2, 0.12, { mat: 'mapGlass', color: '#3e5a6a', y: 1.7, z: 0.05 })
      signPlane(b, sign, 6, 0.8, { y: 4.3, z: 6.02 })
      signPlane(b, sign2, 10, 1.0, { y: 5.9, z: 0.14 })
    })
  })
  const front = s.bz + s.bd / 2
  parkingRows(b, s, front + 6.5, s.ld / 2 - 0.5, r, P, 0.3)
  P.put(b, 'ambulance', s.bx - 3, front + 3.2, { ry: Math.PI / 2, c: '#ffffff' })
  P.put(b, 'ambulance', s.bx + 3.4, front + 3.6, { ry: Math.PI / 2 + 0.3, c: '#ffffff' })
  // triage tents and barricades out front
  for (let k = 0; k < 3; k++) P.put(b, 'tent', -s.lw / 2 + 6 + k * 6, s.ld / 2 - 8, { ry: Math.PI / 2, c: '#e8e4dc' })
  for (let k = 0; k < 4; k++) P.put(b, 'barrier', -s.lw / 2 + 3 + k * 3.1, s.ld / 2 - 1.2, { ry: Math.PI / 2 })
}

L.firestation = (b, loc, s, r, P) => {
  lotGround(b, s, 'concrete', '#b8b4aa')
  const sign = addSign(loc.name.toUpperCase(), '#7a1e1a', '#f0d890')
  b.at({ x: s.bx, z: s.bz }, () => {
    const w = s.bw
    const d = s.bd
    b.box(w, 7.6, d, { mat: 'brick', color: '#ffffff', y: 3.8 })
    b.at({ z: d / 2 }, () => {
      const bays = 3
      for (let k = 0; k < bays; k++) {
        const x = -w / 2 + 4.4 + k * 5.6
        const open = k === 1
        b.box(4.2, 4.6, 0.14, { mat: open ? 'plain' : 'paint', color: open ? '#1a1a1a' : '#b02a22', x, y: 2.3, z: 0.05 })
        if (!open) for (let j = 0; j < 3; j++) b.box(3.4, 0.9, 0.16, { mat: 'mapGlass', color: '#3a4650', x, y: 3.2 - j * 1.1, z: 0.07 })
      }
      b.box(w - 2, 1.0, 0.2, { mat: 'concrete', color: '#e8e4dc', y: 5.4, z: 0.1 })
      signPlane(b, sign, Math.min(w - 3, 12), 0.9, { y: 5.4, z: 0.22 })
      facadeWindows(b, w, { floors: 1, y0: 5.0, fh: 3, ww: 1.2, wh: 1.4, gap: 1.5, frame: '#e8e4dc', rnd: r })
    })
    flatRoof(b, w, d, 7.6, { rnd: r, parapet: 0.8, parMat: 'brick', parColor: '#ffffff', clutter: 3 })
    // hose tower
    b.box(4, 16, 4, { mat: 'brick', color: '#ffffff', x: w / 2 - 2, y: 8, z: -d / 2 + 2 })
    gableRoof(b, { w: 4.4, d: 4.4, y: 16, rise: 1.6, mat: 'roofmetal', color: '#4a4e50', over: 0.2, x: w / 2 - 2, z: -d / 2 + 2, gableMat: 'brick', gableColor: '#ffffff' })
  })
  const front = s.bz + s.bd / 2
  P.put(b, 'firetruck', s.bx - s.bw / 2 + 4.4 + 5.6, front + 4.8, { ry: 0, c: '#b02a22' })
  P.put(b, 'firetruck', s.bx - s.bw / 2 + 4.4, front + 6.5, { ry: 0.25, c: '#b02a22' })
  b.cyl(0.08, 0.1, 9, { mat: 'steel', color: '#c8ccd0', x: s.bx + s.bw / 2 - 1, y: 4.5, z: front + 3, seg: 6 })
  sideLot(b, s, r, P, 2)
}

L.gunstore = (b, loc, s, r, P) => {
  lotGround(b, s, 'asphalt', '#7a7a76')
  const sign = addSign(loc.name.toUpperCase(), '#1e1e1e', '#d8b040')
  b.at({ x: s.bx, z: s.bz }, () => {
    const w = s.bw
    const d = s.bd
    b.box(w, 5, d, { mat: 'concrete', color: '#a8a49a', y: 2.5 })
    b.at({ z: d / 2 }, () => {
      b.box(w - 3, 2.2, 0.1, { mat: 'mapGlass', color: '#2e3a42', y: 1.6, z: 0.04 })
      for (let x = -w / 2 + 2; x <= w / 2 - 2; x += 0.5) b.box(0.06, 2.4, 0.06, { mat: 'steel', color: '#3a3c3e', x, y: 1.6, z: 0.14 })
      b.box(w - 1, 1.1, 0.2, { mat: 'paint', color: '#1e1e1e', y: 3.9, z: 0.1 })
      signPlane(b, sign, Math.min(w - 2, 11), 1.0, { y: 3.9, z: 0.22 })
    })
    flatRoof(b, w, d, 5, { rnd: r, parapet: 0.5, clutter: 2, hatch: false })
    // target logo on the side wall
    b.at({ x: w / 2 + 0.06, ry: Math.PI / 2 }, () => {
      b.cyl(1.6, 1.6, 0.06, { mat: 'paint', color: '#e8e4dc', y: 2.6, rx: Math.PI / 2, seg: 18 })
      b.cyl(1.1, 1.1, 0.07, { mat: 'paint', color: '#b02a22', y: 2.6, rx: Math.PI / 2, seg: 18, z: 0.01 })
      b.cyl(0.5, 0.5, 0.08, { mat: 'paint', color: '#e8e4dc', y: 2.6, rx: Math.PI / 2, seg: 14, z: 0.02 })
    })
  })
  sideLot(b, s, r, P, 2)
}

L.warehouse = (b, loc, s, r, P) => {
  lotGround(b, s, 'asphalt', '#7e7e7a')
  b.at({ x: s.bx, z: s.bz }, () => warehouse(b, { w: s.bw, d: s.bd, h: 10, wall: '#b8c0c4' }, r, P))
  b.at({ x: s.bx, z: s.bz + s.bd / 2 + 2 }, () => bigSign(b, loc.name.toUpperCase(), 12, 1.2, { y: 8.6, z: -1.9 }, '#2a3a4a', '#e8e8e8'))
  if (s.side) for (let k = 0; k < 6; k++) P.put(b, 'container', s.bx + s.side * (s.bw / 2 + 5 + (k % 2) * 3), -s.ld / 2 + 8 + Math.floor(k / 2) * 0.1, { y: Math.floor(k / 2) * 2.62, c: pick(r, ['#a8442a', '#2a5a8a', '#3a7a4a', '#c8a030']) })
}

L.police = (b, loc, s, r, P) => {
  lotGround(b, s, 'concrete', '#b8b4aa')
  const sign = addSign('POLICE', '#1e3a6a', '#ffffff')
  const sign2 = addSign(loc.name.toUpperCase(), '#d8d8d4', '#1e3a6a')
  b.at({ x: s.bx, z: s.bz }, () => {
    const w = s.bw
    const d = s.bd
    b.box(w, 11, d, { mat: 'concrete', color: '#c4c2bc', y: 5.5 })
    b.box(w + 0.1, 0.8, d + 0.1, { mat: 'paint', color: '#1e3a6a', y: 3.9 })
    eachFace(b, w, d, (len, face) => {
      for (let f = 0; f < 3; f++) b.box(len - 2, 1.3, 0.12, { mat: 'mapGlass', color: '#2e3e4a', y: 1.9 + f * 3.4 + (f > 0 ? 0.6 : 0), z: 0.05 })
      if (face === 'front') {
        b.box(6, 0.5, 4, { mat: 'concrete', color: '#d8d6d0', y: 3.4, z: 2 })
        signPlane(b, sign, 5, 1.1, { y: 9.6, z: 0.12 })
        signPlane(b, sign2, 6, 0.6, { y: 3.4, z: 4.02 })
      }
    })
    flatRoof(b, w, d, 11, { rnd: r, parapet: 0.8, clutter: 3 })
    // radio mast
    b.cyl(0.15, 0.35, 18, { mat: 'steel', color: '#c8ccd0', x: w / 2 - 3, y: 11 + 9, z: -d / 2 + 3, seg: 6 })
    for (let k = 0; k < 3; k++) b.box(1.6, 0.08, 0.08, { mat: 'steel', color: '#c8ccd0', x: w / 2 - 3, y: 15 + k * 4, z: -d / 2 + 3 })
    b.sphere(0.3, { mat: 'glowRed', color: '#ffffff', x: w / 2 - 3, y: 29.2, z: -d / 2 + 3, ws: 6, hs: 4 })
  })
  const front = s.bz + s.bd / 2
  parkingRows(b, s, front + 5, s.ld / 2 - 0.5, r, P, 0.0)
  for (let k = 0; k < 5; k++) P.put(b, 'car-police', -s.lw / 2 + 5 + k * 3.2, front + 11, { ry: r() < 0.7 ? 0 : Math.PI, c: '#e8e8e4' })
  for (let k = 0; k < 6; k++) P.put(b, r() < 0.5 ? 'barrier' : 'policeline', -s.lw / 2 + 3 + k * 3.4, s.ld / 2 - 1.3, { ry: Math.PI / 2 })
  P.put(b, 'sandbags', s.bx - 3, front + 1.2, {})
  P.put(b, 'sandbags', s.bx + 3, front + 1.2, {})
}

// The military checkpoint: a HESCO-walled compound beside the highway.
L.military = (b, loc, s, r, P) => {
  const lw = s.lw
  const ld = s.ld
  lotGround(b, s, 'gravel', '#a49a84')
  // perimeter
  const per = []
  for (let x = -lw / 2 + 1; x <= lw / 2 - 1; x += 1.55) {
    per.push([x, -ld / 2 + 1])
    if (Math.abs(x) > 6) per.push([x, ld / 2 - 1])
  }
  for (let z = -ld / 2 + 2.6; z <= ld / 2 - 2.6; z += 1.55) {
    per.push([-lw / 2 + 1, z])
    per.push([lw / 2 - 1, z])
  }
  for (const [x, z] of per) P.put(b, 'hesco', x, z, {})
  // guard towers at the corners
  for (const [sx, sz] of [
    [-1, -1],
    [1, -1],
    [-1, 1],
    [1, 1],
  ]) {
    const x = sx * (lw / 2 - 4)
    const z = sz * (ld / 2 - 4)
    for (const [dx, dz] of [
      [-1.2, -1.2],
      [1.2, -1.2],
      [1.2, 1.2],
      [-1.2, 1.2],
    ])
      b.box(0.25, 6, 0.25, { mat: 'wood', color: '#8a7a64', x: x + dx, y: 3, z: z + dz })
    b.box(3.2, 0.25, 3.2, { mat: 'planks', color: '#a89070', x, y: 6, z })
    b.box(3.2, 1.2, 3.2, { mat: 'canvas', color: '#a89a74', x, y: 6.7, z })
    b.box(3.6, 0.15, 3.6, { mat: 'roofmetal', color: '#6a6e60', x, y: 8.4, z })
    for (const [dx, dz] of [
      [-1.6, -1.6],
      [1.6, -1.6],
      [1.6, 1.6],
      [-1.6, 1.6],
    ])
      b.box(0.1, 1.2, 0.1, { mat: 'wood', color: '#8a7a64', x: x + dx, y: 7.8, z: z + dz })
  }
  // command post
  b.at({ x: s.bx, z: s.bz }, () => {
    const w = s.bw
    const d = s.bd
    b.box(w * 0.5, 3.2, d * 0.5, { mat: 'corrugated', color: '#8a9070', y: 1.6 })
    gableRoof(b, { w: w * 0.5, d: d * 0.5, y: 3.2, rise: 0.8, mat: 'roofmetal', color: '#6a6e60', gableMat: 'corrugated', gableColor: '#8a9070', over: 0.2 })
    b.cyl(0.1, 0.12, 12, { mat: 'steel', color: '#8a9094', x: w * 0.2, y: 6, z: -d * 0.2, seg: 5 })
  })
  // tents in rows, a command tent, supply stacks and sandbag rings
  for (let k = 0; k < 12; k++) P.put(b, 'tent', -lw / 2 + 11 + (k % 6) * 6.2, -ld / 2 + 12 + Math.floor(k / 6) * 9, { ry: Math.PI / 2, s: 1.35, c: pick(r, ['#7c8158', '#8a9070', '#6e7450']) })
  b.box(14, 3.4, 8, { mat: 'canvas', color: '#7c8158', x: -lw / 2 + 16, y: 1.7, z: ld / 2 - 22 })
  gableRoof(b, { w: 14, d: 8, y: 3.4, rise: 1.6, mat: 'canvas', color: '#6e7450', x: -lw / 2 + 16, z: ld / 2 - 22, gableMat: 'canvas', gableColor: '#7c8158', over: 0.3 })
  for (let k = 0; k < 10; k++) P.put(b, 'pallets', 8 + (k % 5) * 2.2, -6 + Math.floor(k / 5) * 2.4, { ry: 0 })
  for (let a = 0; a < 10; a++) P.put(b, 'sandbags', lw / 2 - 26 + Math.cos((a / 10) * TAU) * 6, 12 + Math.sin((a / 10) * TAU) * 6, { ry: -(a / 10) * TAU + Math.PI / 2 })
  b.cyl(2.6, 2.6, 1.6, { mat: 'rubber', color: '#2a2c2a', x: lw / 2 - 26, y: 0.8, z: 12, seg: 14 })
  // helipad with a helicopter
  b.cyl(7, 7, 0.2, { mat: 'concrete', color: '#8a8a84', x: lw / 2 - 14, y: 0.1, z: -ld / 2 + 14, seg: 20 })
  P.put(b, 'heli', lw / 2 - 14, -ld / 2 + 14, { ry: 0.6, c: '#5a6248' })
  for (let k = 0; k < 4; k++) P.put(b, k < 2 ? 'humvee' : 'mtruck', -lw / 2 + 10 + k * 5, ld / 2 - 10, { ry: 0, c: '#6a7052' })
  for (let k = 0; k < 6; k++) P.put(b, 'sandbags', -lw / 2 + 14 + k * 4, 2, { ry: 0 })
}

export function locationModel(b, loc, r, P) {
  const fn = L[loc.type] || L.store
  fn(b, loc, loc.site, r, P)
}
