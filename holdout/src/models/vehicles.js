// Vehicles and big props: cars (intact and wrecked), the camp's van, a bus,
// a pickup, shipping containers and a forklift. Cars face +z.
import { Builder, seeded } from './kit.js'
import { tire, shadeHex } from './parts.js'

const TAU = Math.PI * 2
const pick = (r, a) => a[Math.floor(r() * a.length)]
export const CAR_COLORS = ['#6e3430', '#3a4e62', '#c8c4bc', '#3a3e42', '#5a6650', '#9a958e', '#a88a48', '#4e3a4a', '#2a3440', '#7a5a3a']

function wheel(b, x, y, z, rnd, missing) {
  if (missing) {
    b.box(0.22, 0.28, 0.22, { mat: 'concrete', color: '#a8a49c', x, y: 0.14, z })
    return
  }
  b.at({ x, y, z, rz: Math.PI / 2 }, () => {
    b.cyl(0.33, 0.33, 0.22, { mat: 'rubber', color: '#1c1c1c', seg: 18 })
    b.cyl(0.21, 0.21, 0.23, { mat: 'chrome', color: '#a8acb0', seg: 14 })
    b.cyl(0.08, 0.08, 0.24, { mat: 'steel', color: '#4a4e52', seg: 8 })
  })
}
// Sedan / hatchback. o: { color, wreck (0..1), seed, kind: 'sedan'|'hatch'|'wagon' }
export function carModel(o = {}) {
  const rnd = seeded(o.seed ?? 3)
  const b = new Builder()
  const c = o.color ?? pick(rnd, CAR_COLORS)
  const wreck = o.wreck ?? 0
  const kind = o.kind ?? pick(rnd, ['sedan', 'sedan', 'hatch', 'wagon'])
  const L = kind === 'hatch' ? 3.8 : 4.4
  const W = 1.8
  const lift = wreck > 0.6 ? 0.12 : 0.3
  const body = { mat: 'paint', color: c }
  const dark = { mat: 'paint', color: '#1e1e20' }
  // lower body with wheel arches suggested by dark boxes
  b.box(W, 0.55, L, { ...body, y: lift + 0.36, r: 0.1, seg: 3 })
  b.box(W + 0.02, 0.14, L + 0.02, { ...dark, y: lift + 0.14 })
  // bonnet and boot slopes
  b.box(W - 0.06, 0.12, 1.2, { ...body, y: lift + 0.66, z: L / 2 - 0.62, rx: 0.06, r: 0.05 })
  if (kind !== 'hatch') b.box(W - 0.06, 0.12, 0.8, { ...body, y: lift + 0.66, z: -L / 2 + 0.42, rx: -0.05, r: 0.05 })
  // cabin
  const cabL = kind === 'wagon' ? 2.5 : kind === 'hatch' ? 2.0 : 2.0
  const cabZ = kind === 'wagon' ? -0.45 : kind === 'hatch' ? -0.35 : -0.15
  b.box(W - 0.14, 0.52, cabL, { ...body, y: lift + 0.98, z: cabZ, r: 0.12, seg: 3 })
  b.box(W - 0.1, 0.42, cabL - 0.25, { mat: 'glass', color: '#2e3a42', y: lift + 0.98, z: cabZ })
  b.box(W - 0.3, 0.4, 0.04, { mat: 'glass', color: '#2e3a42', y: lift + 0.96, z: cabZ + cabL / 2 + 0.05, rx: -0.5 })
  // pillars and roof rails
  for (const sx of [-1, 1]) {
    b.box(0.06, 0.5, 0.08, { ...body, x: sx * (W / 2 - 0.08), y: lift + 0.98, z: cabZ + 0.05 })
    b.box(0.02, 0.1, 0.5, { mat: 'chrome', color: '#a8acb0', x: sx * (W / 2 + 0.005), y: lift + 0.5, z: cabZ + 0.3 })
    b.box(0.05, 0.06, 0.14, { mat: 'paint', color: '#1a1a1a', x: sx * (W / 2 + 0.05), y: lift + 0.82, z: cabZ + cabL / 2 - 0.05 })
  }
  if (kind === 'wagon') for (const sx of [-1, 1]) b.box(0.04, 0.04, cabL - 0.3, { mat: 'chrome', color: '#a8acb0', x: sx * 0.6, y: lift + 1.27, z: cabZ })
  // lights, grille, bumpers, plates
  for (const sx of [-1, 1]) {
    b.box(0.32, 0.12, 0.04, { mat: wreck > 0.3 && rnd() < 0.5 ? 'plain' : 'glass', color: '#f0ead0', x: sx * 0.62, y: lift + 0.5, z: L / 2 + 0.005 })
    b.box(0.3, 0.12, 0.04, { mat: 'glowRed', color: '#5a1a1a', x: sx * 0.62, y: lift + 0.55, z: -L / 2 - 0.005 })
  }
  b.box(0.7, 0.16, 0.03, { mat: 'paint', color: '#1a1a1a', y: lift + 0.48, z: L / 2 + 0.01 })
  b.box(W, 0.16, 0.12, { mat: 'chrome', color: '#9a9ea2', y: lift + 0.24, z: L / 2 + 0.02 })
  b.box(W, 0.16, 0.12, { mat: 'chrome', color: '#9a9ea2', y: lift + 0.24, z: -L / 2 - 0.02 })
  b.box(0.42, 0.11, 0.01, { mat: 'paint', color: '#e8e4d0', y: lift + 0.36, z: -L / 2 - 0.085 })
  // wheels
  const wz = L / 2 - 0.85
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) wheel(b, sx * (W / 2 - 0.1), 0.33, sz * wz, rnd, wreck > 0.5 && rnd() < wreck)
  // weathering: rust patches, dirt, a cracked window, open door
  if (wreck > 0) {
    const n = Math.floor(2 + wreck * 6)
    for (let i = 0; i < n; i++) {
      const side = rnd() < 0.5 ? -1 : 1
      b.box(0.012, 0.15 + rnd() * 0.3, 0.3 + rnd() * 0.8, { mat: 'rust', color: '#a89888', x: side * (W / 2 + 0.006), y: lift + 0.3 + rnd() * 0.3, z: (rnd() - 0.5) * (L - 0.6), shadow: false, ao: 0 })
    }
    b.box(W - 0.2, 0.012, 1.0, { mat: 'rust', color: '#a89888', y: lift + 0.73, z: L / 2 - 0.6, shadow: false })
    if (rnd() < wreck) b.box(W - 0.3, 0.012, 0.6, { mat: 'plain', color: '#5a5048', y: lift + 1.25, z: cabZ, shadow: false })
    if (rnd() < wreck * 0.6) {
      // a door hanging open
      const sx = rnd() < 0.5 ? -1 : 1
      b.box(0.06, 0.8, 1.0, { ...body, x: sx * (W / 2 + 0.45), y: lift + 0.6, z: cabZ + 0.5, ry: sx * 0.9 })
    }
  }
  return b.build()
}
// The camp's supply van (also used on runs as the stash).
export function vanModel(o = {}) {
  const b = new Builder()
  const c = o.color ?? '#d8d4c8'
  const L = 5.2
  const W = 2.0
  const body = { mat: 'paint', color: c }
  b.box(W, 1.75, L - 0.9, { ...body, y: 1.3, z: -0.45, r: 0.12, seg: 3 })
  b.box(W, 1.0, 1.2, { ...body, y: 0.95, z: L / 2 - 0.6, r: 0.14, seg: 3 })
  b.box(W - 0.15, 0.6, 0.1, { mat: 'glass', color: '#2e3a42', y: 1.75, z: L / 2 - 1.12, rx: -0.45 })
  for (const sx of [-1, 1]) b.box(0.04, 0.5, 0.75, { mat: 'glass', color: '#2e3a42', x: sx * (W / 2 + 0.005), y: 1.6, z: L / 2 - 1.5 })
  b.box(W + 0.02, 0.18, L + 0.02, { mat: 'paint', color: '#2a2a2a', y: 0.35 })
  b.box(W, 0.2, 0.15, { mat: 'paint', color: '#2a2a2a', y: 0.45, z: L / 2 + 0.02 })
  b.box(0.9, 0.25, 0.03, { mat: 'paint', color: '#1a1a1a', y: 0.8, z: L / 2 + 0.005 })
  for (const sx of [-1, 1]) b.box(0.32, 0.16, 0.04, { mat: 'glass', color: '#f0ead0', x: sx * 0.65, y: 0.85, z: L / 2 + 0.01 })
  // armor plating and a roof rack: this van has seen things
  for (const sx of [-1, 1]) {
    b.box(0.03, 0.55, 2.4, { mat: 'rust', color: '#ffffff', x: sx * (W / 2 + 0.02), y: 0.85, z: -0.6 })
    for (let i = 0; i < 5; i++) b.cyl(0.015, 0.015, 0.03, { mat: 'steel', color: '#5a5a5a', x: sx * (W / 2 + 0.04), y: 1.05, z: -1.7 + i * 0.55, rz: Math.PI / 2 })
  }
  b.box(0.06, 0.06, L - 1.2, { mat: 'steel', color: '#4a4e52', x: -0.8, y: 2.25, z: -0.5 })
  b.box(0.06, 0.06, L - 1.2, { mat: 'steel', color: '#4a4e52', x: 0.8, y: 2.25, z: -0.5 })
  for (let i = 0; i < 4; i++) b.box(1.7, 0.05, 0.05, { mat: 'steel', color: '#4a4e52', y: 2.27, z: -2.4 + i * 1.1 })
  b.box(0.6, 0.4, 0.8, { mat: 'canvas', color: '#5a6040', x: -0.3, y: 2.45, z: -1.3, r: 0.08 })
  b.box(0.5, 0.25, 0.5, { mat: 'paint', color: '#a8382a', x: 0.45, y: 2.4, z: -0.2 })
  // bull bar
  b.box(1.6, 0.06, 0.06, { mat: 'steel', color: '#3a3e42', y: 0.7, z: L / 2 + 0.2 })
  b.box(1.6, 0.06, 0.06, { mat: 'steel', color: '#3a3e42', y: 0.45, z: L / 2 + 0.2 })
  for (const sx of [-0.6, 0.6]) b.box(0.06, 0.55, 0.06, { mat: 'steel', color: '#3a3e42', x: sx, y: 0.6, z: L / 2 + 0.2 })
  // rear doors
  b.box(0.02, 1.4, 0.02, { mat: 'paint', color: '#8a8a84', y: 1.2, z: -L / 2 + 0.01 })
  b.box(0.8, 0.4, 0.02, { mat: 'glass', color: '#2e3a42', x: -0.45, y: 1.7, z: -L / 2 - 0.0 })
  b.box(0.8, 0.4, 0.02, { mat: 'glass', color: '#2e3a42', x: 0.45, y: 1.7, z: -L / 2 - 0.0 })
  const rnd = seeded(5)
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) wheel(b, sx * (W / 2 - 0.12), 0.38, sz * (L / 2 - 1.0), rnd, false)
  return b.build()
}
export function busModel(o = {}) {
  const b = new Builder()
  const rnd = seeded(o.seed ?? 9)
  const c = o.color ?? '#d8a828'
  const L = 10
  const W = 2.5
  b.box(W, 2.4, L, { mat: 'paint', color: c, y: 1.65, r: 0.15, seg: 2 })
  b.box(W + 0.02, 0.3, L + 0.02, { mat: 'paint', color: '#1a1a1a', y: 0.5 })
  for (const sx of [-1, 1]) {
    for (let i = 0; i < 8; i++) b.box(0.03, 0.8, 0.95, { mat: 'glass', color: '#2a343a', x: sx * (W / 2 + 0.005), y: 2.2, z: -L / 2 + 1.3 + i * 1.12 })
    b.box(0.03, 0.12, L - 0.6, { mat: 'paint', color: '#1a1a1a', x: sx * (W / 2 + 0.01), y: 1.55 })
  }
  b.box(W - 0.2, 1.0, 0.03, { mat: 'glass', color: '#2a343a', y: 2.15, z: L / 2 + 0.005 })
  b.box(W - 0.3, 0.2, 0.03, { mat: 'paint', color: '#1a1a1a', y: 2.85, z: L / 2 + 0.01 })
  for (let i = 0; i < 5; i++) b.box(0.012, 0.6, 1.5, { mat: 'rust', color: '#ffffff', x: (rnd() < 0.5 ? -1 : 1) * (W / 2 + 0.01), y: 1.1, z: (rnd() - 0.5) * 8, shadow: false })
  for (const sx of [-1, 1]) for (const z of [-L / 2 + 1.8, L / 2 - 2.0]) wheel(b, sx * (W / 2 - 0.15), 0.48, z, rnd, rnd() < 0.3)
  return b.build()
}
export function pickupModel(o = {}) {
  const b = new Builder()
  const rnd = seeded(o.seed ?? 4)
  const c = o.color ?? pick(rnd, CAR_COLORS)
  const L = 5.2
  const W = 1.95
  const body = { mat: 'paint', color: c }
  b.box(W, 0.7, L, { ...body, y: 0.75, r: 0.08 })
  b.box(W - 0.1, 0.65, 1.7, { ...body, y: 1.45, z: 0.4, r: 0.1 })
  b.box(W - 0.06, 0.5, 1.5, { mat: 'glass', color: '#2e3a42', y: 1.45, z: 0.4 })
  b.box(W - 0.2, 0.05, 2.1, { mat: 'paint', color: '#2a2a2a', y: 1.08, z: -1.4 })
  for (const sx of [-1, 1]) b.box(0.06, 0.4, 2.2, { ...body, x: sx * (W / 2 - 0.03), y: 1.28, z: -1.4 })
  b.box(W, 0.4, 0.06, { ...body, y: 1.28, z: -L / 2 + 0.03 })
  b.box(W + 0.04, 0.15, L + 0.04, { mat: 'paint', color: '#1a1a1a', y: 0.42 })
  for (const sx of [-1, 1]) b.box(0.3, 0.14, 0.04, { mat: 'glass', color: '#f0ead0', x: sx * 0.62, y: 0.9, z: L / 2 + 0.005 })
  if (o.cargo !== false) for (let i = 0; i < 3; i++) b.box(0.5, 0.35, 0.5, { mat: 'wood', color: '#d8c8a8', x: -0.4 + i * 0.4, y: 1.3, z: -1.6 + (i % 2) * 0.5, ry: rnd() })
  for (const sx of [-1, 1]) for (const z of [L / 2 - 1.0, -L / 2 + 1.0]) wheel(b, sx * (W / 2 - 0.08), 0.38, z, rnd, (o.wreck ?? 0) > rnd())
  return b.build()
}
// 20-foot shipping container. o.open: doors swung open.
export function containerModel(o = {}) {
  const b = new Builder()
  const rnd = seeded(o.seed ?? 7)
  const c = o.color ?? pick(rnd, ['#4a6a7e', '#7a4436', '#4e6a48', '#a8823e', '#6a6e72', '#6e3a40'])
  const L = 6.0
  const W = 2.4
  const H = 2.6
  for (const sx of [-1, 1]) b.box(0.04, H - 0.2, L - 0.2, { mat: 'corrugated', color: c, x: sx * (W / 2 - 0.02), y: H / 2 })
  b.box(W - 0.08, 0.04, L - 0.2, { mat: 'paint', color: shadeHex(c, -0.1), y: H - 0.04 })
  b.box(W - 0.2, H - 0.2, 0.04, { mat: 'corrugated', color: c, z: L / 2 - 0.02, y: H / 2 })
  // frame
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.15, H, 0.15, { mat: 'paint', color: shadeHex(c, -0.25), x: sx * (W / 2 - 0.07), y: H / 2, z: sz * (L / 2 - 0.07) })
  for (const y of [0.08, H - 0.08]) {
    for (const sx of [-1, 1]) b.box(0.15, 0.16, L, { mat: 'paint', color: shadeHex(c, -0.25), x: sx * (W / 2 - 0.07), y })
    for (const sz of [-1, 1]) b.box(W, 0.16, 0.15, { mat: 'paint', color: shadeHex(c, -0.25), y, z: sz * (L / 2 - 0.07) })
  }
  // doors on -z
  if (o.open) {
    for (const sx of [-1, 1]) b.box(0.05, H - 0.25, W / 2, { mat: 'corrugated', color: c, x: sx * (W / 2 + 0.05), z: -L / 2 - W / 4, y: H / 2 })
    b.box(W - 0.3, 0.02, L - 0.3, { mat: 'planks', color: '#a89070', y: 0.17 })
  } else {
    b.box(W - 0.2, H - 0.25, 0.04, { mat: 'corrugated', color: c, z: -L / 2 + 0.02, y: H / 2 })
    for (const x of [-0.75, -0.35, 0.35, 0.75]) b.cyl(0.02, 0.02, H - 0.4, { mat: 'steel', color: '#8a8e92', x, y: H / 2, z: -L / 2 - 0.03 })
  }
  for (let i = 0; i < 3; i++) b.box(0.012, 0.3 + rnd() * 0.6, 0.6 + rnd() * 1.2, { mat: 'rust', color: '#a89888', x: (rnd() < 0.5 ? -1 : 1) * (W / 2 + 0.003), y: 0.4 + rnd() * 1.2, z: (rnd() - 0.5) * 4, shadow: false, ao: 0 })
  b.box(0.012, 0.3, 1.4, { mat: 'paint', color: '#e8e4dc', x: W / 2 + 0.004, y: H - 0.5, z: 1.4, ao: 0 })
  return b.build()
}
export function forkliftModel() {
  const b = new Builder()
  b.box(1.1, 0.7, 2.0, { mat: 'paint', color: '#d8a020', y: 0.6, r: 0.06 })
  b.box(1.1, 0.5, 0.6, { mat: 'paint', color: '#3a3a3a', y: 0.9, z: -0.8 })
  for (const sx of [-1, 1]) {
    b.box(0.06, 2.0, 0.06, { mat: 'steel', color: '#3a3a3a', x: sx * 0.5, y: 1.9, z: -0.3 })
    b.box(0.08, 2.4, 0.1, { mat: 'paint', color: '#3a3a3a', x: sx * 0.35, y: 1.3, z: 1.1 })
    b.box(0.1, 0.05, 1.1, { mat: 'steel', color: '#5a5e62', x: sx * 0.25, y: 0.15, z: 1.65 })
  }
  b.box(1.1, 0.06, 1.0, { mat: 'steel', color: '#3a3a3a', y: 2.9, z: 0.2 })
  b.box(0.3, 0.05, 0.3, { mat: 'paint', color: '#1a1a1a', y: 1.1, z: -0.1 })
  const rnd = seeded(2)
  for (const sx of [-1, 1]) {
    wheel(b, sx * 0.5, 0.3, 0.6, rnd, false)
    wheel(b, sx * 0.5, 0.25, -0.7, rnd, false)
  }
  return b.build()
}
