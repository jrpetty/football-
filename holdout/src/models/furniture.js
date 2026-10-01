// Furniture and fittings for building interiors on supply runs: every
// lootable container (fridges, wardrobes, desks, store shelves, gun lockers,
// safes, server racks...) and the rooms' furniture (beds, sofas, tables,
// stoves, toilets, hospital beds, cell bars...). Models face +z (into the
// room) with their back against a wall; origin at the footprint centre.
// Containers expose door/drawer pivots that swing open once searched.
import { Builder, seeded } from './kit.js'
import { shelfItems, crate as crateProp, barrel, pallet as palletProp, shadeHex, lantern } from './parts.js'

const TAU = Math.PI * 2
const pick = (r, a) => a[Math.floor(r() * a.length)]

// Footprints in tiles along the wall (w) and out from it (d).
export const FOOT = { counter: [2, 1], wardrobe: [2, 1], desk: [2, 1], bookshelf: [2, 1], shelf: [2, 1], toolrack: [2, 1], gunlocker: [2, 1], chemshelf: [2, 1], pallet: [3, 1], car: [4, 2], dumpster: [2, 1], shed: [3, 3], server: [1, 1] }
export const footOf = (k) => FOOT[k] || [1, 1]

const WOOD = ['#e0ccb0', '#c8a880', '#a88460', '#d8c0a0', '#8a6a4a']
const PAINT = ['#e8e4dc', '#d8d0c0', '#c8d4d8', '#b8c4b0', '#e8dcc8']

function handle(b, x, y, z, vertical = true, len = 0.12) {
  b.box(vertical ? 0.02 : len, vertical ? len : 0.02, 0.025, { mat: 'chrome', color: '#b8bcc0', x, y, z })
}

// ---------------------------------------------------------------- containers
const C = {}
C.fridge = (b, r) => {
  const col = pick(r, ['#e8e8e4', '#d8dcd8', '#c8ccc8', '#e0d8c8', '#9aa0a4'])
  b.box(0.78, 1.82, 0.7, { mat: 'paint', color: col, y: 0.93, r: 0.04, seg: 2 })
  b.box(0.7, 0.04, 0.02, { mat: 'paint', color: shadeHex(col, -0.15), y: 1.28, z: 0.36 })
  b.box(0.7, 0.06, 0.6, { mat: 'plain', color: '#2a2a2a', y: 0.03 })
  b.pivot('door', { x: -0.38, y: 0, z: 0.36 }, (d) => {
    d.box(0.76, 1.15, 0.06, { mat: 'paint', color: col, x: 0.38, y: 0.68, r: 0.02 })
    handle(d, 0.68, 1.0, 0.05, true, 0.32)
    // a few magnets and a child's drawing
    d.box(0.16, 0.2, 0.005, { mat: 'plain', color: '#f0e8d0', x: 0.3, y: 0.95, z: 0.035 })
    d.box(0.04, 0.04, 0.01, { mat: 'plastic', color: '#c83a2a', x: 0.24, y: 1.04, z: 0.04 })
  })
  b.pivot('door2', { x: -0.38, y: 0, z: 0.36 }, (d) => {
    d.box(0.76, 0.52, 0.06, { mat: 'paint', color: col, x: 0.38, y: 1.56, r: 0.02 })
    handle(d, 0.68, 1.42, 0.05, true, 0.16)
  })
  // inside: shelves and food
  for (const y of [0.45, 0.8, 1.1]) b.box(0.66, 0.02, 0.56, { mat: 'glass', color: '#e8f0f0', y, z: 0.02 })
  for (let k = 0; k < 4; k++) b.box(0.12, 0.16, 0.1, { mat: 'plain', color: pick(r, ['#e8d040', '#c84a3a', '#e8e4dc', '#5a9a4a']), x: -0.22 + k * 0.15, y: 0.55, z: 0.05 })
}
C.cabinet = (b, r) => {
  const col = pick(r, PAINT)
  b.box(0.9, 0.86, 0.6, { mat: 'paint', color: col, y: 0.45 })
  b.box(0.94, 0.04, 0.64, { mat: 'tiles', color: '#d8d0c0', y: 0.9 })
  b.box(0.86, 0.1, 0.02, { mat: 'plain', color: '#3a3632', y: 0.05, z: 0.3 })
  for (const s of [-1, 1])
    b.pivot(s < 0 ? 'doorL' : 'doorR', { x: s * 0.44, y: 0, z: 0.31 }, (d) => {
      d.box(0.42, 0.7, 0.025, { mat: 'paint', color: col, x: -s * 0.215, y: 0.47 })
      d.box(0.34, 0.6, 0.008, { mat: 'paint', color: shadeHex(col, -0.06), x: -s * 0.215, y: 0.47, z: 0.016 })
      handle(d, -s * 0.38, 0.72, 0.03)
    })
  // upper wall cabinet
  b.box(0.9, 0.62, 0.34, { mat: 'paint', color: col, y: 1.82, z: -0.13 })
  for (const s of [-1, 1]) handle(b, s * 0.06, 1.62, 0.05)
  b.box(0.3, 0.2, 0.2, { mat: 'plain', color: '#c8a060', x: 0.2, y: 1.02, z: -0.1 })
}
C.counter = (b, r) => {
  const col = pick(r, PAINT)
  const top = pick(r, ['#d8d0c0', '#3a3a3a', '#c8b898', '#e8e4dc'])
  b.box(1.9, 0.86, 0.6, { mat: 'paint', color: col, y: 0.45 })
  b.box(1.94, 0.045, 0.64, { mat: 'tiles', color: top, y: 0.9 })
  for (let k = 0; k < 4; k++) {
    const x = -0.71 + k * 0.475
    if (k === 1 || k === 2) {
      b.pivot('door' + k, { x: x - 0.22, y: 0, z: 0.31 }, (d) => {
        d.box(0.44, 0.62, 0.025, { mat: 'paint', color: col, x: 0.22, y: 0.4 })
        handle(d, 0.38, 0.62, 0.025)
      })
    } else {
      b.box(0.44, 0.62, 0.025, { mat: 'paint', color: col, x, y: 0.4, z: 0.31 })
      handle(b, x, 0.62, 0.33, false)
    }
    b.box(0.44, 0.14, 0.025, { mat: 'paint', color: shadeHex(col, -0.05), x, y: 0.8, z: 0.31 })
  }
  // sink, kettle, a toaster
  b.box(0.5, 0.04, 0.4, { mat: 'steel', color: '#c8ccd0', x: 0.45, y: 0.925 })
  b.box(0.42, 0.12, 0.32, { mat: 'steel', color: '#8a8e92', x: 0.45, y: 0.88 })
  b.cyl(0.015, 0.015, 0.25, { mat: 'chrome', color: '#c8ccd0', x: 0.45, y: 1.05, z: -0.22, seg: 6 })
  b.beam([0.45, 1.17, -0.22], [0.45, 1.15, -0.08], 0.03, 0.03, { mat: 'chrome', color: '#c8ccd0' })
  b.box(0.26, 0.18, 0.16, { mat: 'chrome', color: '#b8bcc0', x: -0.55, y: 1.02, z: -0.1, r: 0.03 })
  b.cyl(0.08, 0.09, 0.22, { mat: 'plastic', color: pick(r, ['#c83a2a', '#2a4a8a', '#e8e4dc']), x: -0.2, y: 1.03, z: -0.12, seg: 10 })
  b.box(1.9, 0.5, 0.02, { mat: 'tiles', color: '#e8e4dc', y: 1.18, z: -0.31 })
}
C.wardrobe = (b, r) => {
  const col = pick(r, WOOD)
  b.box(1.5, 2.0, 0.62, { mat: 'wood', color: col, y: 1.02 })
  b.box(1.56, 0.06, 0.66, { mat: 'wood', color: shadeHex(col, -0.1), y: 2.05 })
  b.box(1.5, 0.08, 0.6, { mat: 'wood', color: shadeHex(col, -0.15), y: 0.04 })
  for (const s of [-1, 1])
    b.pivot(s < 0 ? 'doorL' : 'doorR', { x: s * 0.74, y: 0, z: 0.32 }, (d) => {
      d.box(0.72, 1.82, 0.03, { mat: 'wood', color: col, x: -s * 0.36, y: 1.02 })
      d.box(0.56, 1.4, 0.01, { mat: 'wood', color: shadeHex(col, 0.06), x: -s * 0.36, y: 1.06, z: 0.02 })
      handle(d, -s * 0.66, 1.05, 0.035, true, 0.16)
    })
  // hanging clothes inside
  b.cyl(0.012, 0.012, 1.4, { mat: 'chrome', color: '#a8acb0', y: 1.75, rz: Math.PI / 2, seg: 6 })
  for (let k = 0; k < 7; k++) b.box(0.08, 0.7 + r() * 0.3, 0.42, { mat: 'cloth', color: pick(r, ['#3a4a6a', '#8a3a3a', '#d8d0c0', '#4a4a4a', '#6a7a4a', '#c8a070']), x: -0.6 + k * 0.2, y: 1.3, z: 0.02 })
}
C.dresser = (b, r) => {
  const col = pick(r, WOOD)
  b.box(1.0, 0.85, 0.5, { mat: 'wood', color: col, y: 0.47 })
  b.box(1.04, 0.04, 0.54, { mat: 'wood', color: shadeHex(col, -0.1), y: 0.91 })
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.05, 0.06, 0.05, { mat: 'wood', color: shadeHex(col, -0.2), x: sx * 0.45, y: 0.03, z: sz * 0.2 })
  for (let k = 0; k < 3; k++)
    b.pivot('drawer' + k, { x: 0, y: 0.22 + k * 0.25, z: 0.25 }, (d) => {
      d.box(0.92, 0.22, 0.03, { mat: 'wood', color: shadeHex(col, 0.05), y: 0 })
      d.box(0.88, 0.16, 0.42, { mat: 'wood', color: shadeHex(col, -0.1), y: -0.01, z: -0.21 })
      for (const s of [-1, 1]) handle(d, s * 0.25, 0, 0.025, false, 0.1)
    })
  // a lamp and a framed photo on top
  b.cyl(0.08, 0.1, 0.04, { mat: 'paint', color: '#2a2a2a', x: 0.32, y: 0.95, seg: 10 })
  b.cyl(0.015, 0.015, 0.3, { mat: 'chrome', color: '#b8bcc0', x: 0.32, y: 1.1, seg: 6 })
  b.cyl(0.08, 0.14, 0.18, { mat: 'cloth', color: '#e8dcc0', x: 0.32, y: 1.3, seg: 12 })
  b.box(0.18, 0.22, 0.02, { mat: 'wood', color: '#3a2a20', x: -0.25, y: 1.03, z: -0.05, rx: -0.15 })
  b.box(0.14, 0.18, 0.005, { mat: 'plain', color: '#c8b8a0', x: -0.25, y: 1.03, z: -0.035, rx: -0.15 })
}
C.desk = (b, r) => {
  const col = pick(r, ['#a88460', '#c8a880', '#6a6e72', '#e0d8c8', '#4a3a2e'])
  const metal = col === '#6a6e72'
  b.box(1.6, 0.04, 0.75, { mat: metal ? 'paint' : 'wood', color: col, y: 0.74 })
  b.box(0.42, 0.7, 0.7, { mat: metal ? 'paint' : 'wood', color: shadeHex(col, -0.08), x: 0.56, y: 0.37 })
  for (const s of [-1]) for (const sz of [-1, 1]) b.box(0.05, 0.72, 0.05, { mat: 'steel', color: '#5a5e62', x: s * 0.76, y: 0.36, z: sz * 0.33 })
  for (let k = 0; k < 3; k++)
    b.pivot('drawer' + k, { x: 0.56, y: 0.15 + k * 0.22, z: 0.36 }, (d) => {
      d.box(0.38, 0.19, 0.025, { mat: metal ? 'paint' : 'wood', color: col, y: 0 })
      d.box(0.34, 0.15, 0.6, { mat: 'wood', color: '#8a7a68', z: -0.3 })
      handle(d, 0, 0.03, 0.02, false, 0.1)
    })
  // monitor, keyboard, papers, a mug
  b.box(0.5, 0.32, 0.04, { mat: 'plastic', color: '#2a2c2e', x: -0.15, y: 1.0, z: -0.2 })
  b.box(0.46, 0.28, 0.01, { mat: 'mapGlass', color: '#1a2228', x: -0.15, y: 1.0, z: -0.175 })
  b.box(0.06, 0.16, 0.06, { mat: 'plastic', color: '#2a2c2e', x: -0.15, y: 0.82, z: -0.22 })
  b.box(0.42, 0.02, 0.14, { mat: 'plastic', color: '#3a3c3e', x: -0.15, y: 0.77, z: 0.1 })
  for (let k = 0; k < 3; k++) b.box(0.21, 0.004, 0.29, { mat: 'plain', color: '#f0ece0', x: 0.35 + k * 0.01, y: 0.763 + k * 0.004, z: 0.08, ry: k * 0.2 })
  b.cyl(0.04, 0.035, 0.09, { mat: 'plastic', color: pick(r, ['#c83a2a', '#e8e4dc', '#2a4a8a']), x: -0.6, y: 0.81, z: 0.05, seg: 10 })
  // chair
  b.cyl(0.04, 0.04, 0.44, { mat: 'steel', color: '#3a3c3e', y: 0.24, z: 0.62, seg: 6 })
  b.box(0.48, 0.08, 0.46, { mat: 'cloth', color: '#2a2c34', y: 0.48, z: 0.62, r: 0.03 })
  b.box(0.46, 0.5, 0.06, { mat: 'cloth', color: '#2a2c34', y: 0.8, z: 0.86, r: 0.03, rx: 0.12 })
  for (let k = 0; k < 5; k++) b.box(0.3, 0.03, 0.04, { mat: 'plastic', color: '#2a2a2a', y: 0.04, z: 0.62, ry: (k / 5) * TAU, x: 0 })
}
C.filing = (b, r) => {
  const col = pick(r, ['#8a9094', '#b8b4a8', '#6a7a6a', '#a8a090'])
  b.box(0.5, 1.32, 0.62, { mat: 'paint', color: col, y: 0.66 })
  for (let k = 0; k < 4; k++)
    b.pivot('drawer' + k, { x: 0, y: 0.17 + k * 0.32, z: 0.31 }, (d) => {
      d.box(0.46, 0.29, 0.025, { mat: 'paint', color: shadeHex(col, 0.06), y: 0 })
      d.box(0.42, 0.25, 0.55, { mat: 'paint', color: shadeHex(col, -0.1), z: -0.28 })
      d.box(0.14, 0.03, 0.03, { mat: 'chrome', color: '#b8bcc0', y: 0.06, z: 0.025 })
      d.box(0.08, 0.04, 0.004, { mat: 'plain', color: '#f0ece0', y: 0.1, z: 0.015 })
    })
  b.box(0.3, 0.06, 0.24, { mat: 'plain', color: '#e8e4d8', y: 1.36, z: 0.05, ry: 0.2 })
}
C.bookshelf = (b, r) => {
  const col = pick(r, WOOD)
  const w = 1.6
  const h = 1.9
  b.box(w, h, 0.03, { mat: 'wood', color: shadeHex(col, -0.12), y: h / 2, z: -0.17 })
  for (const s of [-1, 1]) b.box(0.04, h, 0.36, { mat: 'wood', color: col, x: s * (w / 2 - 0.02), y: h / 2 })
  for (let k = 0; k < 5; k++) {
    const y = 0.06 + k * 0.45
    b.box(w - 0.06, 0.03, 0.34, { mat: 'wood', color: col, y })
    if (k === 4) break
    let x = -w / 2 + 0.08
    while (x < w / 2 - 0.12) {
      if (r() < 0.15) {
        x += 0.2
        continue
      }
      const bw = 0.03 + r() * 0.05
      const bh = 0.24 + r() * 0.12
      const tilt = r() < 0.08 ? 0.35 : 0
      b.box(bw, bh, 0.22 + r() * 0.06, { mat: 'plain', color: pick(r, ['#8a3a2a', '#2a4a6a', '#3a5a3a', '#c8b080', '#4a3a5a', '#d8d0c0', '#6a2a2a']), x: x + bw / 2, y: y + 0.015 + bh / 2, z: 0.02, rz: tilt })
      x += bw + 0.005
    }
  }
}
C.trash = (b, r) => {
  const col = pick(r, ['#3e4a3c', '#5a5e62', '#2a4a7a', '#8a8a82'])
  b.cyl(0.26, 0.22, 0.86, { mat: 'paint', color: col, y: 0.44, seg: 14 })
  b.pivot('door', { x: 0, y: 0.88, z: -0.26 }, (d) => d.cyl(0.28, 0.28, 0.06, { mat: 'paint', color: shadeHex(col, -0.1), z: 0.26, seg: 14 }))
  for (let k = 0; k < 3; k++) b.box(0.012, 0.6, 0.04, { mat: 'paint', color: shadeHex(col, -0.15), x: Math.cos(k * 2.1) * 0.25, y: 0.44, z: Math.sin(k * 2.1) * 0.25, ry: -k * 2.1 })
  b.sphere(0.12, { mat: 'plastic', color: '#1a1a1a', x: 0.05, y: 0.88, z: 0.05, ws: 8, hs: 6 })
}
C.shelf = (b, r) => {
  // a store shelving unit, stocked
  const w = 1.8
  const h = 1.75
  const d = 0.62
  b.box(w, 0.12, d, { mat: 'paint', color: '#e8e8e4', y: 0.06 })
  b.box(w, h, 0.05, { mat: 'paint', color: '#d8dcd8', y: h / 2, z: 0 })
  for (const s of [-1, 1]) b.box(0.04, h, d, { mat: 'paint', color: '#c8ccc8', x: s * (w / 2 - 0.02), y: h / 2 })
  for (let k = 0; k < 4; k++) {
    const y = 0.14 + k * 0.42
    for (const sz of [-1, 1]) {
      b.box(w - 0.08, 0.025, d / 2 - 0.04, { mat: 'paint', color: '#e8e8e4', y, z: (sz * d) / 4 })
      b.box(w - 0.08, 0.05, 0.01, { mat: 'plain', color: k === 0 ? '#c83a2a' : '#f0ece0', y: y - 0.01, z: (sz * d) / 2 - 0.005 })
      if (r() < 0.8) b.at({ z: (sz * d) / 4 }, () => shelfItems(b, r, pick(r, ['cans', 'boxes', 'jars', 'bottles', 'boxes']), w - 0.14, d / 2 - 0.08, y + 0.0125, 0.32, 0.75))
    }
  }
}
C.register = (b, r) => {
  b.box(0.9, 0.95, 0.6, { mat: 'paint', color: '#c8c0b0', y: 0.48 })
  b.box(0.94, 0.04, 0.64, { mat: 'tiles', color: '#3a3a3a', y: 0.97 })
  b.box(0.4, 0.12, 0.34, { mat: 'plastic', color: '#2a2c2e', y: 1.05, z: 0.05 })
  b.pivot('drawer0', { x: 0, y: 0.94, z: 0.24 }, (d) => d.box(0.36, 0.07, 0.3, { mat: 'plastic', color: '#3a3c3e', z: -0.12 }))
  b.box(0.3, 0.22, 0.04, { mat: 'plastic', color: '#2a2c2e', y: 1.25, z: -0.12, rx: -0.3 })
  b.box(0.26, 0.16, 0.01, { mat: 'glowGreen', color: '#123a1a', y: 1.25, z: -0.1, rx: -0.3 })
  // impulse rack
  b.box(0.2, 0.5, 0.2, { mat: 'steel', color: '#8a8e92', x: 0.55, y: 1.22, z: -0.1 })
  for (let k = 0; k < 4; k++) b.box(0.16, 0.08, 0.04, { mat: 'plain', color: pick(r, ['#c83a2a', '#e8c040', '#3a7ab8', '#5a9a4a']), x: 0.55, y: 1.05 + k * 0.11, z: 0.02 })
}
C.toolrack = (b, r) => {
  b.box(1.7, 1.2, 0.05, { mat: 'planks', color: '#c8b090', y: 1.3, z: -0.25 })
  for (let k = 0; k < 9; k++) {
    const x = -0.75 + k * 0.19
    const kind = k % 3
    if (kind === 0) {
      b.box(0.03, 0.42, 0.03, { mat: 'wood', color: '#a87a4a', x, y: 1.4, z: -0.2 })
      b.box(0.1, 0.06, 0.06, { mat: 'steel', color: '#5a5e62', x, y: 1.62, z: -0.2 })
    } else if (kind === 1) {
      b.box(0.04, 0.3, 0.015, { mat: 'chrome', color: '#b8bcc0', x, y: 1.25, z: -0.2 })
      b.box(0.05, 0.1, 0.03, { mat: 'plastic', color: pick(r, ['#c83a2a', '#e8c040', '#2a4a8a']), x, y: 1.44, z: -0.2 })
    } else b.torus(0.08, 0.012, { mat: 'rubber', color: '#2a2a2a', x, y: 1.5, z: -0.2, rs: 4, ts2: 10 })
  }
  // workbench with a vise below the board
  b.box(1.7, 0.06, 0.7, { mat: 'planks', color: '#b89a74', y: 0.86 })
  for (const s of [-1, 1]) for (const sz of [-1, 1]) b.box(0.07, 0.84, 0.07, { mat: 'wood', color: '#8a6a4a', x: s * 0.78, y: 0.42, z: sz * 0.3 })
  b.box(1.6, 0.04, 0.6, { mat: 'planks', color: '#a88a64', y: 0.2 })
  b.box(0.18, 0.12, 0.14, { mat: 'paint', color: '#3a5a8a', x: 0.6, y: 0.95, z: 0.25 })
  for (let k = 0; k < 3; k++) b.box(0.3, 0.16, 0.22, { mat: 'paint', color: pick(r, ['#c83a2a', '#3a3a3a', '#d8a020']), x: -0.6 + k * 0.4, y: 0.3, z: 0.05, r: 0.02 })
}
C.medcab = (b, r) => {
  // bathroom vanity with a mirrored cabinet above
  b.box(0.8, 0.82, 0.48, { mat: 'paint', color: '#e8e8e4', y: 0.42 })
  b.box(0.84, 0.05, 0.52, { mat: 'tiles', color: '#e0dcd4', y: 0.85 })
  b.cyl(0.18, 0.14, 0.06, { mat: 'plain', color: '#f0f0ec', y: 0.86, seg: 14 })
  b.cyl(0.015, 0.015, 0.16, { mat: 'chrome', color: '#c8ccd0', y: 0.95, z: -0.18, seg: 6 })
  b.pivot('door', { x: -0.3, y: 1.15, z: -0.12 }, (d) => {
    d.box(0.6, 0.66, 0.04, { mat: 'paint', color: '#e8e8e4', x: 0.3, y: 0.33 })
    d.box(0.54, 0.6, 0.01, { mat: 'chrome', color: '#d8e0e4', x: 0.3, y: 0.33, z: 0.025 })
  })
  b.box(0.6, 0.66, 0.16, { mat: 'paint', color: '#d8d8d4', y: 1.48, z: -0.2 })
  for (let k = 0; k < 4; k++) b.cyl(0.025, 0.025, 0.1, { mat: 'plastic', color: pick(r, ['#e8a020', '#e8e4dc', '#c83a2a', '#3a7ab8']), x: -0.2 + k * 0.12, y: 1.35, z: -0.2, seg: 8 })
}
C.locker = (b, r) => {
  const col = pick(r, ['#5a6a7a', '#7a8a6a', '#8a8a82', '#4a5a6a', '#9a6a4a'])
  b.box(0.9, 1.9, 0.5, { mat: 'paint', color: col, y: 0.95 })
  for (let k = 0; k < 3; k++) {
    const x = -0.3 + k * 0.3
    b.pivot('door' + k, { x: x - 0.14, y: 0, z: 0.255 }, (d) => {
      d.box(0.28, 1.8, 0.02, { mat: 'paint', color: shadeHex(col, 0.06), x: 0.14, y: 0.95 })
      for (let v = 0; v < 4; v++) d.box(0.16, 0.015, 0.01, { mat: 'plain', color: shadeHex(col, -0.3), x: 0.14, y: 1.6 + v * 0.04, z: 0.012 })
      d.box(0.03, 0.08, 0.02, { mat: 'chrome', color: '#c8ccd0', x: 0.24, y: 1.0, z: 0.015 })
    })
  }
  b.box(0.9, 0.06, 0.5, { mat: 'paint', color: shadeHex(col, -0.15), y: 1.93 })
}
C.gunlocker = (b, r) => {
  // steel gun safe and a glass-fronted rifle rack
  b.box(0.85, 1.8, 0.6, { mat: 'paint', color: '#2e3236', x: -0.45, y: 0.9 })
  b.pivot('door', { x: -0.87, y: 0, z: 0.31 }, (d) => {
    d.box(0.84, 1.74, 0.05, { mat: 'paint', color: '#34383c', x: 0.42, y: 0.9 })
    d.cyl(0.1, 0.1, 0.04, { mat: 'chrome', color: '#b8bcc0', x: 0.42, y: 1.0, z: 0.04, rx: Math.PI / 2, seg: 12 })
    for (let k = 0; k < 3; k++) d.box(0.16, 0.02, 0.02, { mat: 'chrome', color: '#b8bcc0', x: 0.42 + Math.cos(k * 2.1) * 0.1, y: 1.0 + Math.sin(k * 2.1) * 0.1, z: 0.06, rz: k * 2.1 })
  })
  b.box(0.85, 1.6, 0.4, { mat: 'wood', color: '#5a3a28', x: 0.45, y: 0.8, z: -0.1 })
  b.box(0.75, 1.3, 0.02, { mat: 'glass', color: '#c8d8d8', x: 0.45, y: 0.9, z: 0.11 })
  for (let k = 0; k < 4; k++) {
    b.box(0.05, 1.1, 0.08, { mat: 'paint', color: '#2a2a2a', x: 0.2 + k * 0.17, y: 0.85, z: -0.05, rz: 0.05 })
    b.box(0.07, 0.3, 0.1, { mat: 'wood', color: '#6a4a2a', x: 0.2 + k * 0.17, y: 0.35, z: -0.05 })
  }
}
C.safe = (b) => {
  b.box(0.7, 0.85, 0.65, { mat: 'paint', color: '#3a3e42', y: 0.45, r: 0.03 })
  b.box(0.7, 0.06, 0.65, { mat: 'plain', color: '#1e1e1e', y: 0.03 })
  b.pivot('door', { x: -0.33, y: 0, z: 0.33 }, (d) => {
    d.box(0.64, 0.74, 0.06, { mat: 'paint', color: '#44484c', x: 0.32, y: 0.45 })
    d.cyl(0.08, 0.08, 0.04, { mat: 'chrome', color: '#c8ccd0', x: 0.32, y: 0.5, z: 0.04, rx: Math.PI / 2, seg: 14 })
    d.box(0.2, 0.025, 0.03, { mat: 'chrome', color: '#c8ccd0', x: 0.42, y: 0.32, z: 0.04 })
    d.box(0.3, 0.06, 0.01, { mat: 'paint', color: '#b89a40', x: 0.32, y: 0.7, z: 0.035 })
  })
}
C.crate = (b, r) => {
  crateProp(b, { w: 0.85, h: 0.6, d: 0.65, y: 0, color: pick(r, ['#c8a878', '#b89868', '#d8b888']) })
  if (r() < 0.6) crateProp(b, { w: 0.6, h: 0.42, d: 0.5, y: 0.6, ry: 0.2, color: '#c8a878' })
}
C.pallet = (b, r) => {
  // a three-bay pallet rack loaded with wrapped goods
  const w = 2.8
  const H = 2.6
  for (const x of [-w / 2, 0, w / 2]) for (const z of [-0.45, 0.45]) b.box(0.08, H, 0.08, { mat: 'paint', color: '#2a5aa8', x, y: H / 2, z })
  for (const y of [0.12, 1.3, 2.45]) {
    for (const z of [-0.45, 0.45]) b.box(w, 0.1, 0.06, { mat: 'paint', color: '#e88a2a', y, z })
    for (const sx of [-1, 1]) {
      if (r() < 0.2) continue
      const px = (sx * w) / 4
      palletProp(b, { x: px, y: y + 0.05, color: '#c8a878' })
      const hh = 0.5 + r() * 0.45
      b.box(1.1, hh, 0.9, { mat: 'plain', color: pick(r, ['#b48c5a', '#c8b898', '#a8845a', '#d8d4c8']), x: px, y: y + 0.17 + hh / 2, r: 0.03 })
      b.box(1.12, hh * 0.9, 0.92, { mat: 'glass', color: '#e8f0f0', x: px, y: y + 0.17 + hh / 2 })
    }
  }
}
C.milcrate = (b, r) => {
  const col = '#5a6248'
  b.box(1.0, 0.5, 0.6, { mat: 'paint', color: col, y: 0.25, r: 0.02 })
  for (const s of [-1, 1]) b.box(0.06, 0.1, 0.2, { mat: 'steel', color: '#3a3c3a', x: s * 0.52, y: 0.32 })
  b.pivot('door', { x: 0, y: 0.5, z: -0.3 }, (d) => {
    d.box(1.02, 0.08, 0.62, { mat: 'paint', color: shadeHex(col, 0.05), z: 0.3 })
    d.box(0.5, 0.004, 0.2, { mat: 'plain', color: '#e8e4c8', y: 0.042, z: 0.3 })
  })
  for (const x of [-0.42, 0.42]) b.box(0.08, 0.14, 0.04, { mat: 'steel', color: '#8a8e8a', x, y: 0.42, z: 0.31 })
  if (r() < 0.5) {
    b.box(0.9, 0.4, 0.5, { mat: 'paint', color: shadeHex(col, -0.05), y: 0.72, z: -0.02, ry: 0.06 })
    b.box(0.5, 0.004, 0.18, { mat: 'plain', color: '#e8e4c8', y: 0.92, z: -0.02 })
  }
}
C.server = (b, r) => {
  b.box(0.62, 2.0, 0.9, { mat: 'paint', color: '#1e2024', y: 1.0 })
  b.pivot('door', { x: -0.3, y: 0, z: 0.46 }, (d) => {
    d.box(0.6, 1.92, 0.03, { mat: 'glass', color: '#3a4048', x: 0.3, y: 1.0 })
    d.box(0.03, 0.3, 0.03, { mat: 'chrome', color: '#a8acb0', x: 0.55, y: 1.0, z: 0.02 })
  })
  for (let k = 0; k < 14; k++) {
    b.box(0.52, 0.1, 0.02, { mat: 'paint', color: '#2a2e34', y: 0.2 + k * 0.125, z: 0.42 })
    if (r() < 0.6) b.box(0.03, 0.02, 0.01, { mat: pick(r, ['glowGreen', 'glowAmber', 'glowGreen', 'glowBlue']), color: '#ffffff', x: -0.2 + r() * 0.4, y: 0.2 + k * 0.125, z: 0.432 })
  }
}
C.tv = (b, r) => {
  const col = pick(r, ['#5a3a28', '#2a2a2a', '#d8d0c0'])
  b.box(1.4, 0.55, 0.45, { mat: 'wood', color: col, y: 0.28 })
  b.pivot('doorL', { x: -0.68, y: 0, z: 0.23 }, (d) => d.box(0.66, 0.42, 0.02, { mat: 'wood', color: shadeHex(col, 0.06), x: 0.33, y: 0.28 }))
  b.pivot('doorR', { x: 0.68, y: 0, z: 0.23 }, (d) => d.box(0.66, 0.42, 0.02, { mat: 'wood', color: shadeHex(col, 0.06), x: -0.33, y: 0.28 }))
  b.box(1.1, 0.64, 0.06, { mat: 'plastic', color: '#1a1a1c', y: 0.92, z: -0.05 })
  b.box(1.04, 0.58, 0.01, { mat: 'mapGlass', color: '#121418', y: 0.92, z: -0.015 })
  b.box(0.3, 0.06, 0.2, { mat: 'plastic', color: '#1a1a1c', y: 0.58, z: -0.05 })
  b.box(0.36, 0.08, 0.26, { mat: 'plastic', color: '#2a2a2e', x: -0.4, y: 0.6, z: 0.02 })
  b.box(0.16, 0.6, 0.18, { mat: 'wood', color: '#2a2422', x: 0.62, y: 0.86, z: -0.05 })
}
C.chemshelf = (b, r) => {
  const w = 1.6
  b.box(w, 1.8, 0.45, { mat: 'paint', color: '#e8e8e4', y: 0.9, z: -0.02 })
  b.box(w - 0.06, 1.7, 0.02, { mat: 'paint', color: '#d8d8d4', y: 0.92, z: -0.22 })
  for (let k = 0; k < 4; k++) {
    const y = 0.1 + k * 0.42
    b.box(w - 0.06, 0.02, 0.4, { mat: 'paint', color: '#f0f0ec', y })
    b.at({ z: 0.02 }, () => shelfItems(b, r, 'chem', w - 0.14, 0.3, y + 0.01, 0.3, 0.7))
  }
  b.box(0.3, 0.3, 0.02, { mat: 'paint', color: '#e8c020', x: 0.5, y: 1.95, z: 0.21 })
  b.pivot('doorL', { x: -w / 2 + 0.02, y: 0, z: 0.22 }, (d) => d.box(w / 2 - 0.02, 1.76, 0.015, { mat: 'glass', color: '#d0e0e0', x: w / 4, y: 0.9 }))
  b.pivot('doorR', { x: w / 2 - 0.02, y: 0, z: 0.22 }, (d) => d.box(w / 2 - 0.02, 1.76, 0.015, { mat: 'glass', color: '#d0e0e0', x: -w / 4, y: 0.9 }))
}
C.toolchest = (b, r) => {
  const col = pick(r, ['#c8302a', '#2a4a8a', '#3a3a3a'])
  b.box(0.9, 1.0, 0.5, { mat: 'paint', color: col, y: 0.56, r: 0.02 })
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.cyl(0.05, 0.05, 0.05, { mat: 'rubber', color: '#1a1a1a', x: sx * 0.38, y: 0.05, z: sz * 0.18, rz: Math.PI / 2, seg: 8 })
  for (let k = 0; k < 5; k++)
    b.pivot('drawer' + k, { x: 0, y: 0.2 + k * 0.17, z: 0.255 }, (d) => {
      d.box(0.84, 0.14, 0.02, { mat: 'paint', color: shadeHex(col, 0.06), y: 0 })
      d.box(0.6, 0.025, 0.025, { mat: 'chrome', color: '#c8ccd0', y: 0.03, z: 0.015 })
      d.box(0.8, 0.1, 0.45, { mat: 'paint', color: '#2a2a2a', y: 0, z: -0.22 })
    })
  b.box(0.92, 0.04, 0.52, { mat: 'rubber', color: '#1e1e1e', y: 1.08 })
  b.box(0.3, 0.06, 0.06, { mat: 'paint', color: '#e8c040', x: 0.2, y: 1.13, ry: 0.3 })
}
C.firelocker = C.locker
C.dumpster = (b, r) => {
  const col = pick(r, ['#3a6a4a', '#2a4a6a', '#5a5a52', '#6a3a2a'])
  b.box(1.9, 1.15, 1.1, { mat: 'paint', color: col, y: 0.68 })
  b.box(1.95, 0.1, 1.15, { mat: 'paint', color: shadeHex(col, -0.15), y: 1.2 })
  for (const s of [-1, 1]) b.box(0.08, 0.14, 0.9, { mat: 'steel', color: '#5a5e62', x: s * 1.0, y: 0.95 })
  for (const sx of [-0.8, 0.8]) for (const sz of [-0.4, 0.4]) b.cyl(0.07, 0.07, 0.06, { mat: 'rubber', color: '#1a1a1a', x: sx, y: 0.07, z: sz, rz: Math.PI / 2, seg: 8 })
  b.pivot('door', { x: 0, y: 1.25, z: -0.56 }, (d) => d.box(1.9, 0.05, 1.1, { mat: 'plastic', color: '#2a2c2a', z: 0.55, rx: 0.1 }))
  for (let k = 0; k < 4; k++) b.sphere(0.2 + r() * 0.1, { mat: 'plastic', color: '#1a1a1a', x: -0.6 + k * 0.4, y: 1.15, z: (r() - 0.5) * 0.4, ws: 8, hs: 6 })
}
C.pump = (b, r) => {
  const brand = pick(r, ['#c8302a', '#2a6ab0', '#2a8a4a', '#e8a020'])
  b.box(0.9, 0.2, 1.2, { mat: 'concrete', color: '#c8c4bc', y: 0.1 })
  b.box(0.7, 1.7, 0.5, { mat: 'paint', color: '#e8e4dc', y: 1.05 })
  b.box(0.72, 0.3, 0.52, { mat: 'paint', color: brand, y: 1.75 })
  for (const s of [-1, 1]) {
    b.box(0.4, 0.3, 0.02, { mat: 'mapGlass', color: '#1a2a20', y: 1.35, z: s * 0.26 })
    b.box(0.1, 0.24, 0.12, { mat: 'paint', color: '#2a2a2a', x: 0.2, y: 0.95, z: s * 0.3 })
    b.tube([[0.2, 0.9, s * 0.34], [0.32, 0.4, s * 0.45], [0.34, 0.3, s * 0.3]], 0.025, { mat: 'rubber', color: '#1a1a1a' })
  }
}
C.shed = (b, r) => {
  const col = pick(r, ['#b8a888', '#8a9a7a', '#a87a5a', '#c8c4b4'])
  b.box(2.6, 2.1, 2.2, { mat: 'planks', color: col, y: 1.05 })
  b.box(2.7, 0.08, 2.4, { mat: 'roofmetal', color: '#6a5a50', y: 2.25, rx: 0.08 })
  b.pivot('door', { x: -0.5, y: 0, z: 1.11 }, (d) => {
    d.box(1.0, 1.85, 0.05, { mat: 'planks', color: shadeHex(col, -0.08), x: 0.5, y: 0.95 })
    d.beam([0.08, 0.2, 0.03], [0.92, 1.7, 0.03], 0.08, 0.03, { mat: 'planks', color: shadeHex(col, -0.15) })
    handle(d, 0.88, 1.0, 0.05)
  })
  b.box(0.5, 0.4, 0.02, { mat: 'glass', color: '#9aa8b0', x: 0.8, y: 1.4, z: 1.11 })
  b.box(1.6, 0.04, 0.4, { mat: 'planks', color: '#a88a64', y: 1.2, z: -0.85 })
  b.cyl(0.25, 0.22, 0.5, { mat: 'paint', color: '#3a6a3a', x: 0.7, y: 0.25, z: -0.6, seg: 10 })
}
C.car = null // built by the vehicles kit

// ---------------------------------------------------------------- decor
const D = {}
D.bed = (b, r) => {
  const frame = pick(r, WOOD)
  const sheet = pick(r, ['#e8e4dc', '#c8d4e0', '#d8c8b8', '#b8c8b0'])
  const quilt = pick(r, ['#8a3a3a', '#3a4a6a', '#5a6a4a', '#c8a060', '#6a4a6a', '#d8d0c0'])
  b.box(1.5, 0.3, 2.05, { mat: 'wood', color: frame, y: 0.22 })
  b.box(1.56, 0.95, 0.07, { mat: 'wood', color: frame, y: 0.48, z: -1.02 })
  b.box(1.42, 0.2, 1.95, { mat: 'cloth', color: sheet, y: 0.46, r: 0.05 })
  b.box(1.46, 0.08, 1.4, { mat: 'cloth', color: quilt, y: 0.58, z: 0.28, r: 0.04 })
  b.box(1.48, 0.25, 0.04, { mat: 'cloth', color: quilt, y: 0.5, z: 0.98 })
  for (const s of [-1, 1]) b.box(0.6, 0.14, 0.38, { mat: 'cloth', color: '#f0ece4', x: s * 0.36, y: 0.62, z: -0.75, r: 0.06, rx: -0.15 })
}
D.nightstand = (b, r) => {
  const col = pick(r, WOOD)
  b.box(0.45, 0.55, 0.4, { mat: 'wood', color: col, y: 0.28 })
  b.box(0.4, 0.12, 0.02, { mat: 'wood', color: shadeHex(col, 0.06), y: 0.42, z: 0.2 })
  b.cyl(0.07, 0.08, 0.03, { mat: 'paint', color: '#2a2a2a', y: 0.57, seg: 10 })
  b.cyl(0.01, 0.01, 0.26, { mat: 'chrome', color: '#c8ccd0', y: 0.7, seg: 6 })
  b.cyl(0.07, 0.12, 0.15, { mat: 'cloth', color: '#e8dcc0', y: 0.88, seg: 12 })
}
D.sofa = (b, r) => {
  const col = pick(r, ['#6a4a3a', '#3a4a5a', '#7a7a6a', '#8a3a3a', '#4a5a3a', '#a89878'])
  b.box(2.1, 0.42, 0.9, { mat: 'cloth', color: col, y: 0.25, r: 0.08 })
  b.box(2.1, 0.62, 0.24, { mat: 'cloth', color: col, y: 0.62, z: -0.34, r: 0.1, rx: -0.1 })
  for (const s of [-1, 1]) b.box(0.24, 0.62, 0.9, { mat: 'cloth', color: shadeHex(col, -0.05), x: s * 0.96, y: 0.38, r: 0.09 })
  for (let k = 0; k < 3; k++) b.box(0.6, 0.16, 0.62, { mat: 'cloth', color: shadeHex(col, 0.06), x: -0.62 + k * 0.62, y: 0.52, z: 0.08, r: 0.07 })
  b.box(0.42, 0.42, 0.12, { mat: 'cloth', color: pick(r, ['#c8a060', '#e8e4dc', '#5a7a8a']), x: 0.6, y: 0.75, z: -0.15, rz: 0.25, r: 0.05 })
}
D.armchair = (b, r) => {
  const col = pick(r, ['#6a4a3a', '#3a4a5a', '#7a6a5a', '#8a5a3a'])
  b.box(0.9, 0.42, 0.85, { mat: 'cloth', color: col, y: 0.25, r: 0.08 })
  b.box(0.9, 0.62, 0.22, { mat: 'cloth', color: col, y: 0.62, z: -0.32, r: 0.1, rx: -0.12 })
  for (const s of [-1, 1]) b.box(0.18, 0.55, 0.85, { mat: 'cloth', color: shadeHex(col, -0.05), x: s * 0.36, y: 0.38, r: 0.08 })
  b.box(0.58, 0.14, 0.6, { mat: 'cloth', color: shadeHex(col, 0.06), y: 0.52, z: 0.08, r: 0.06 })
}
D.coffeetable = (b, r) => {
  const col = pick(r, WOOD)
  b.box(1.1, 0.05, 0.6, { mat: 'wood', color: col, y: 0.42 })
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.05, 0.4, 0.05, { mat: 'wood', color: shadeHex(col, -0.1), x: sx * 0.48, y: 0.2, z: sz * 0.24 })
  b.box(0.3, 0.02, 0.22, { mat: 'plain', color: '#e8e0d0', x: -0.2, y: 0.455, ry: 0.4 })
  b.cyl(0.05, 0.04, 0.1, { mat: 'plastic', color: '#c8302a', x: 0.3, y: 0.495, seg: 10 })
}
D.rug = (b, r) => {
  const col = pick(r, ['#8a3a3a', '#3a4a6a', '#a87a4a', '#5a6a4a', '#6a4a6a'])
  b.box(2.4, 0.015, 1.7, { mat: 'carpet', color: col, y: 0.012, ao: 0, shadow: false })
  b.box(2.1, 0.016, 1.4, { mat: 'carpet', color: shadeHex(col, 0.15), y: 0.013, ao: 0, shadow: false })
  b.box(1.6, 0.017, 0.9, { mat: 'carpet', color: col, y: 0.014, ao: 0, shadow: false })
}
D.lamp = (b) => {
  b.cyl(0.16, 0.18, 0.04, { mat: 'paint', color: '#2a2a2a', y: 0.02, seg: 12 })
  b.cyl(0.015, 0.015, 1.5, { mat: 'chrome', color: '#b8bcc0', y: 0.78, seg: 6 })
  b.cyl(0.14, 0.24, 0.3, { mat: 'cloth', color: '#e8dcc0', y: 1.55, seg: 14, open: true })
}
D.table = (b, r) => {
  const col = pick(r, WOOD)
  b.box(1.6, 0.05, 0.9, { mat: 'wood', color: col, y: 0.76 })
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.06, 0.74, 0.06, { mat: 'wood', color: shadeHex(col, -0.1), x: sx * 0.72, y: 0.37, z: sz * 0.38 })
  for (const [x, z, ry] of [
    [-0.45, -0.62, 0],
    [0.45, -0.62, 0],
    [-0.45, 0.62, Math.PI],
    [0.45, 0.62, Math.PI + 0.3],
  ])
    b.at({ x, z, ry }, () => {
      b.box(0.42, 0.04, 0.42, { mat: 'wood', color: col, y: 0.46 })
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.035, 0.46, 0.035, { mat: 'wood', color: col, x: sx * 0.18, y: 0.23, z: sz * 0.18 })
      b.box(0.42, 0.42, 0.035, { mat: 'wood', color: col, y: 0.7, z: -0.2 })
    })
  b.cyl(0.12, 0.1, 0.06, { mat: 'plain', color: '#e8e4dc', y: 0.82, seg: 12 })
  b.cyl(0.03, 0.03, 0.2, { mat: 'glass', color: '#3a6a3a', x: 0.4, y: 0.89, seg: 8 })
}
D.stove = (b, r) => {
  const col = pick(r, ['#e8e8e4', '#d8d4cc', '#3a3c3e'])
  b.box(0.76, 0.9, 0.64, { mat: 'paint', color: col, y: 0.45 })
  b.box(0.7, 0.4, 0.02, { mat: 'mapGlass', color: '#1a1a1a', y: 0.45, z: 0.32 })
  b.box(0.6, 0.03, 0.03, { mat: 'chrome', color: '#c8ccd0', y: 0.72, z: 0.34 })
  b.box(0.76, 0.02, 0.64, { mat: 'plain', color: '#1a1a1a', y: 0.91 })
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.torus(0.1, 0.012, { mat: 'steel', color: '#4a4a4a', x: sx * 0.18, y: 0.93, z: sz * 0.15, rx: Math.PI / 2, rs: 4, ts2: 12 })
  b.box(0.76, 0.12, 0.08, { mat: 'paint', color: col, y: 1.0, z: -0.28 })
  b.cyl(0.13, 0.12, 0.14, { mat: 'steel', color: '#8a8e92', x: 0.18, y: 1.0, z: 0.15, seg: 12 })
}
D.toilet = (b) => {
  b.box(0.38, 0.4, 0.5, { mat: 'plain', color: '#f0f0ec', y: 0.2, z: 0.05, r: 0.08 })
  b.box(0.4, 0.04, 0.48, { mat: 'plain', color: '#e8e8e4', y: 0.42, z: 0.06, r: 0.03 })
  b.box(0.42, 0.42, 0.18, { mat: 'plain', color: '#f0f0ec', y: 0.62, z: -0.24, r: 0.04 })
}
D.tub = (b) => {
  b.box(0.8, 0.55, 1.7, { mat: 'plain', color: '#f0f0ec', y: 0.28, r: 0.08 })
  b.box(0.62, 0.06, 1.5, { mat: 'tiles', color: '#d8e0e0', y: 0.5 })
  b.cyl(0.02, 0.02, 0.2, { mat: 'chrome', color: '#c8ccd0', y: 0.65, z: -0.78, seg: 6 })
  b.box(0.02, 1.4, 1.6, { mat: 'cloth', color: '#c8d8e0', x: 0.42, y: 1.3, ao: 0 })
}
D.sink = (b) => {
  b.cyl(0.07, 0.09, 0.8, { mat: 'plain', color: '#f0f0ec', y: 0.4, seg: 10 })
  b.box(0.56, 0.16, 0.42, { mat: 'plain', color: '#f0f0ec', y: 0.86, r: 0.06 })
  b.cyl(0.015, 0.015, 0.16, { mat: 'chrome', color: '#c8ccd0', y: 1.0, z: -0.15, seg: 6 })
  b.box(0.48, 0.6, 0.02, { mat: 'chrome', color: '#d8e0e4', y: 1.45, z: -0.22 })
}
D.chair = (b, r) => {
  const col = pick(r, ['#2a2c34', '#5a3a2a', '#3a4a5a'])
  b.cyl(0.04, 0.04, 0.42, { mat: 'steel', color: '#3a3c3e', y: 0.23, seg: 6 })
  b.box(0.48, 0.08, 0.46, { mat: 'cloth', color: col, y: 0.47, r: 0.03 })
  b.box(0.46, 0.52, 0.06, { mat: 'cloth', color: col, y: 0.8, z: -0.22, r: 0.03 })
}
D.plant = (b, r) => {
  b.cyl(0.2, 0.15, 0.36, { mat: 'paint', color: pick(r, ['#a8603a', '#e8e4dc', '#3a3a3a']), y: 0.18, seg: 12 })
  for (let k = 0; k < 7; k++) b.ico(0.18 + r() * 0.1, { mat: 'leaf', color: pick(r, ['#4a7a3a', '#5a8a3a', '#3a6a3a']), x: (r() - 0.5) * 0.3, y: 0.55 + r() * 0.6, z: (r() - 0.5) * 0.3, detail: 0, noise: 0.3 })
}
D.boxes = (b, r) => {
  for (let k = 0; k < 4; k++) {
    const w = 0.4 + r() * 0.3
    const h = 0.3 + r() * 0.25
    b.box(w, h, 0.4 + r() * 0.2, { mat: 'plain', color: pick(r, ['#b48c5a', '#a8845a', '#c09a68']), x: (r() - 0.5) * 0.5, y: (k > 1 ? 0.45 : 0) + h / 2, z: (r() - 0.5) * 0.4, ry: r() * 0.5, r: 0.01 })
  }
}
D.booth = (b, r) => {
  const col = pick(r, ['#a82a2a', '#2a7a7a', '#d8a020'])
  b.box(1.2, 0.05, 0.75, { mat: 'tiles', color: '#e8e4dc', y: 0.74 })
  b.cyl(0.06, 0.08, 0.72, { mat: 'chrome', color: '#c8ccd0', y: 0.37, seg: 8 })
  for (const s of [-1, 1]) {
    b.box(1.3, 0.45, 0.5, { mat: 'cloth', color: col, y: 0.23, z: s * 0.68, r: 0.06 })
    b.box(1.3, 0.62, 0.16, { mat: 'cloth', color: col, y: 0.75, z: s * 0.88, r: 0.06 })
  }
}
D.counterDecor = (b, r) => {
  b.box(2.6, 1.05, 0.7, { mat: 'paint', color: '#d8d4cc', y: 0.53 })
  b.box(2.7, 0.05, 0.85, { mat: 'tiles', color: '#c83a2a', y: 1.07 })
  for (let k = 0; k < 4; k++) {
    const x = -1.0 + k * 0.66
    b.cyl(0.04, 0.05, 0.72, { mat: 'chrome', color: '#c8ccd0', x, y: 0.36, z: 0.75, seg: 8 })
    b.cyl(0.2, 0.2, 0.08, { mat: 'cloth', color: '#a82a2a', x, y: 0.76, z: 0.75, seg: 14 })
  }
  b.cyl(0.14, 0.14, 0.2, { mat: 'glass', color: '#e0d8c8', x: 0.6, y: 1.2, seg: 12 })
  b.box(0.36, 0.3, 0.28, { mat: 'chrome', color: '#b8bcc0', x: -0.8, y: 1.24, z: -0.1 })
}
D.hospitalBed = (b, r) => {
  b.box(0.95, 0.12, 2.0, { mat: 'paint', color: '#e8e8e4', y: 0.6 })
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.cyl(0.025, 0.025, 0.55, { mat: 'chrome', color: '#c8ccd0', x: sx * 0.42, y: 0.3, z: sz * 0.9, seg: 6 })
  b.box(0.9, 0.16, 1.9, { mat: 'cloth', color: '#e0e8ec', y: 0.73, r: 0.04 })
  b.box(0.88, 0.1, 1.2, { mat: 'cloth', color: pick(r, ['#a8c0d0', '#c8d8c8', '#d8d0c0']), y: 0.82, z: 0.3, r: 0.04 })
  b.box(0.6, 0.12, 0.35, { mat: 'cloth', color: '#f0f0ec', y: 0.86, z: -0.75, r: 0.05, rx: -0.3 })
  b.box(0.95, 0.5, 0.04, { mat: 'paint', color: '#d8d8d4', y: 0.9, z: -1.0 })
  for (const s of [-1, 1]) b.box(0.03, 0.2, 1.1, { mat: 'chrome', color: '#c8ccd0', x: s * 0.48, y: 0.88, z: 0.1 })
  // IV stand and a dark stain
  b.cyl(0.012, 0.012, 1.9, { mat: 'chrome', color: '#c8ccd0', x: 0.65, y: 0.95, z: -0.7, seg: 6 })
  b.box(0.12, 0.2, 0.05, { mat: 'glass', color: '#d8e8e8', x: 0.65, y: 1.75, z: -0.7 })
  if (r() < 0.4) b.box(0.5, 0.004, 0.6, { mat: 'plain', color: '#4a1414', y: 0.902, z: 0.2, ao: 0, shadow: false })
}
D.curtain = (b, r) => {
  b.cyl(0.015, 0.015, 2.2, { mat: 'chrome', color: '#c8ccd0', y: 2.2, rz: Math.PI / 2, seg: 6 })
  for (let k = 0; k < 10; k++) b.box(0.24, 1.8, 0.03, { mat: 'cloth', color: '#b8d0d8', x: -1 + k * 0.22, y: 1.25, z: (k % 2) * 0.06, ao: 0 })
}
D.cellBars = (b) => {
  b.box(2.2, 0.08, 0.1, { mat: 'steel', color: '#4a4e52', y: 2.3 })
  b.box(2.2, 0.08, 0.1, { mat: 'steel', color: '#4a4e52', y: 0.04 })
  for (let k = 0; k < 14; k++) b.cyl(0.02, 0.02, 2.3, { mat: 'steel', color: '#5a5e62', x: -1.04 + k * 0.16, y: 1.15, seg: 6 })
  b.box(2.2, 0.06, 0.08, { mat: 'steel', color: '#4a4e52', y: 1.15 })
}
D.cot = (b, r) => {
  b.box(0.8, 0.06, 1.95, { mat: 'steel', color: '#5a5e52', y: 0.42 })
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.04, 0.42, 0.04, { mat: 'steel', color: '#5a5e52', x: sx * 0.36, y: 0.21, z: sz * 0.9 })
  b.box(0.74, 0.1, 1.9, { mat: 'cloth', color: pick(r, ['#5a6a4a', '#6a7458', '#4a5240']), y: 0.5, r: 0.03 })
  b.box(0.5, 0.1, 0.3, { mat: 'cloth', color: '#e8e4dc', y: 0.58, z: -0.75, r: 0.04 })
}
D.forklift = (b) => {
  b.box(1.2, 0.9, 2.0, { mat: 'paint', color: '#e8a820', y: 0.75 })
  b.box(1.1, 0.08, 1.0, { mat: 'paint', color: '#3a3a3a', y: 2.2, z: -0.3 })
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.06, 1.3, 0.06, { mat: 'steel', color: '#3a3a3a', x: sx * 0.5, y: 1.55, z: -0.3 + sz * 0.45 })
  for (const sx of [-1, 1]) b.box(0.08, 2.4, 0.1, { mat: 'steel', color: '#4a4a4a', x: sx * 0.4, y: 1.2, z: 1.05 })
  for (const sx of [-1, 1]) b.box(0.12, 0.05, 1.1, { mat: 'steel', color: '#5a5a5a', x: sx * 0.3, y: 0.1, z: 1.6 })
  for (const sx of [-1, 1]) for (const sz of [-0.6, 0.6]) b.cyl(0.28, 0.28, 0.24, { mat: 'rubber', color: '#1a1a1a', x: sx * 0.6, y: 0.28, z: sz, rz: Math.PI / 2, seg: 12 })
  b.box(0.5, 0.1, 0.5, { mat: 'cloth', color: '#2a2a2a', y: 1.25, z: -0.4 })
}
D.schoolDesks = (b, r) => {
  for (let i = 0; i < 2; i++)
    for (let j = 0; j < 2; j++) {
      const x = -0.55 + i * 1.1
      const z = -0.5 + j * 1.0
      b.box(0.7, 0.04, 0.5, { mat: 'wood', color: '#c8a878', x, y: 0.7, z })
      for (const sx of [-1, 1]) b.box(0.03, 0.7, 0.45, { mat: 'steel', color: '#3a5a8a', x: x + sx * 0.32, y: 0.35, z })
      b.box(0.4, 0.04, 0.38, { mat: 'plastic', color: '#3a5a8a', x, y: 0.44, z: z + 0.4 })
      b.box(0.4, 0.34, 0.03, { mat: 'plastic', color: '#3a5a8a', x, y: 0.66, z: z + 0.6 })
      if (r() < 0.4) b.box(0.22, 0.02, 0.3, { mat: 'plain', color: '#f0ece0', x, y: 0.73, z, ry: r() })
    }
}
D.labBench = (b, r) => {
  b.box(2.0, 0.9, 0.8, { mat: 'paint', color: '#e8e8e4', y: 0.45 })
  b.box(2.04, 0.05, 0.84, { mat: 'tiles', color: '#2a2c2e', y: 0.92 })
  b.box(0.4, 0.1, 0.3, { mat: 'steel', color: '#8a8e92', x: 0.7, y: 0.95 })
  for (let k = 0; k < 5; k++) b.lathe([[0.05, 0], [0.05, 0.08], [0.015, 0.16], [0.015, 0.22]], { mat: 'glass', color: pick(r, ['#c8e8d8', '#e8d8c8', '#d8d8f0']), x: -0.8 + k * 0.18, y: 0.945, z: (r() - 0.5) * 0.3, seg: 10 })
  b.box(0.36, 0.3, 0.3, { mat: 'paint', color: '#d8d8d4', x: -0.4, y: 1.1, z: -0.2 })
}
D.workbenchDecor = (b, r) => {
  b.box(1.8, 0.06, 0.7, { mat: 'planks', color: '#b89a74', y: 0.9 })
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.08, 0.88, 0.08, { mat: 'wood', color: '#8a6a4a', x: sx * 0.82, y: 0.44, z: sz * 0.28 })
  b.box(0.3, 0.12, 0.2, { mat: 'paint', color: '#3a5a8a', x: 0.55, y: 0.99 })
  b.box(0.5, 0.12, 0.25, { mat: 'paint', color: '#c8302a', x: -0.5, y: 0.99, r: 0.02 })
}
D.bench = (b, r) => {
  b.box(1.8, 0.06, 0.4, { mat: 'wood', color: pick(r, WOOD), y: 0.45 })
  for (const s of [-1, 1]) b.box(0.06, 0.45, 0.36, { mat: 'steel', color: '#4a4e52', x: s * 0.8, y: 0.22 })
}
D.table2 = D.table

export const CONTAINER_MODELS = C
export const DECOR_MODELS = D

const cache = new Map()
// A container's model as a fresh group (pivots resolved), sized to its footprint.
export function containerModel(kind, seed = 1) {
  const fn = C[kind] || C.crate
  const b = new Builder()
  fn(b, seeded(seed))
  const g = b.build()
  g.userData.kind = kind
  return g
}
export function decorModel(kind, seed = 1) {
  const fn = D[kind]
  if (!fn) return null
  const b = new Builder()
  fn(b, seeded(seed))
  return b.build()
}
// Add decor straight into a merged builder (static furniture).
export function addDecor(b, kind, o, seed = 1) {
  const fn = D[kind]
  if (!fn) return false
  b.at(o, () => fn(b, seeded(seed)))
  return true
}
// Depth of a container's model, for pushing it back against its wall.
export const DEPTH = { fridge: 0.7, cabinet: 0.6, counter: 0.64, wardrobe: 0.66, dresser: 0.54, desk: 0.75, filing: 0.62, bookshelf: 0.36, trash: 0.52, shelf: 0.62, register: 0.64, toolrack: 0.7, medcab: 0.52, locker: 0.5, gunlocker: 0.6, safe: 0.65, crate: 0.65, pallet: 1.0, milcrate: 0.62, server: 0.92, tv: 0.45, chemshelf: 0.45, toolchest: 0.52, firelocker: 0.5, dumpster: 1.15, pump: 1.2, shed: 2.2 }
// Open whatever opens: doors swing, drawers slide (t from 0 to 1).
export function openPivots(g, t) {
  const pv = g.userData.pivots || {}
  for (const [name, o] of Object.entries(pv)) {
    if (o.userData.base == null) o.userData.base = { x: o.position.x, y: o.position.y, z: o.position.z, ry: o.rotation.y, rx: o.rotation.x }
    const B = o.userData.base
    if (name.startsWith('drawer')) o.position.z = B.z + t * 0.32
    else if (name === 'door' && g.userData.kind === 'trash') o.rotation.x = B.rx - t * 1.6
    else if (name === 'door' && (g.userData.kind === 'milcrate' || g.userData.kind === 'dumpster')) o.rotation.x = B.rx - t * 1.4
    else if (name === 'doorR') o.rotation.y = B.ry + t * 1.9
    else o.rotation.y = B.ry - t * 1.9
  }
}
export { barrel, lantern }
