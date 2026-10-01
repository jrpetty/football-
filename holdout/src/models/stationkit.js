// Building blocks shared by the station models: slabs, decks, board walls,
// windows, doors, timber frames and car wrecks.
import { seeded } from './kit.js'
import { COL, shadeHex, tire } from './parts.js'

// Roofs go into their own pivots so the camp can fade them to show the work underneath.
export function roof(b, I, fn) {
  I.roofN = (I.roofN || 0) + 1
  const name = 'roof' + I.roofN
  b.pivot(name, {}, fn)
  I.roofs.push(name)
}
export function slab(b, I, o = {}) {
  const w = o.w ?? I.w - 0.15
  const d = o.d ?? I.d - 0.15
  b.box(w, o.h ?? 0.1, d, { mat: 'concrete', color: o.color ?? '#d0ccc4', x: o.x || 0, z: o.z || 0, y: (o.h ?? 0.1) / 2 - 0.02 })
  // cracks and stains
  const rnd = seeded(o.seed ?? 3)
  for (let i = 0; i < 4; i++) b.box(0.02, 0.003, 0.6 + rnd(), { mat: 'plain', color: '#3a3632', x: (o.x || 0) + (rnd() - 0.5) * w * 0.8, z: (o.z || 0) + (rnd() - 0.5) * d * 0.6, y: (o.h ?? 0.1) - 0.018, ry: rnd() * 3, ao: 0, shadow: false })
}
export function deck(b, w, d, h, o = {}) {
  const c = o.color ?? '#e8dcc8'
  const rnd = seeded(o.seed ?? 5)
  b.at({ x: o.x || 0, z: o.z || 0 }, () => {
    // joists and posts
    for (const sx of [-1, 1]) for (const sz of [-1, 0, 1]) b.box(0.1, h, 0.1, { mat: 'wood', color: COL.woodDark, x: sx * (w / 2 - 0.08), y: h / 2, z: sz * (d / 2 - 0.08) })
    for (const sz of [-1, 1]) b.box(w, 0.12, 0.05, { mat: 'wood', color: shadeHex(c, -0.15), y: h - 0.08, z: sz * (d / 2 - 0.02) })
    for (const sx of [-1, 1]) b.box(0.05, 0.12, d, { mat: 'wood', color: shadeHex(c, -0.15), x: sx * (w / 2 - 0.02), y: h - 0.08 })
    const n = Math.round(w / 0.15)
    for (let i = 0; i < n; i++) b.box(w / n - 0.008, 0.03, d, { mat: 'wood', color: shadeHex(c, -rnd() * 0.1), x: -w / 2 + (w / n) * (i + 0.5), y: h - 0.015 })
  })
  return h
}
// A wall of vertical boards between two x positions at z, with optional openings.
export function boardWall(b, x0, x1, z, h, o = {}) {
  const rnd = seeded(o.seed ?? 9)
  const c = o.color ?? '#e6d8c2'
  const y0 = o.y0 ?? 0
  const len = x1 - x0
  const n = Math.max(1, Math.round(len / 0.2))
  const bw = len / n
  for (let i = 0; i < n; i++) {
    const x = x0 + bw * (i + 0.5)
    const hole = (o.holes || []).find((hh) => x > hh[0] && x < hh[1])
    if (hole) {
      // board above and below the opening
      if (hole[3] < h) b.box(bw - 0.012, h - hole[3], 0.025, { mat: 'wood', color: shadeHex(c, -rnd() * 0.15), x, y: y0 + (h + hole[3]) / 2, z, rz: (rnd() - 0.5) * 0.01 })
      if (hole[2] > 0) b.box(bw - 0.012, hole[2], 0.025, { mat: 'wood', color: shadeHex(c, -rnd() * 0.15), x, y: y0 + hole[2] / 2, z })
      continue
    }
    const hh = h - rnd() * (o.ragged ?? 0.04)
    b.box(bw - 0.012, hh, 0.025, { mat: 'wood', color: shadeHex(c, -rnd() * 0.15), x, y: y0 + hh / 2, z, rz: (rnd() - 0.5) * 0.012 })
  }
  // horizontal rails behind
  for (const y of [0.3, h - 0.3]) b.box(len, 0.08, 0.04, { mat: 'wood', color: COL.woodDark, x: (x0 + x1) / 2, y: y0 + y, z: z - Math.sign(o.inside ?? 1) * 0.03 })
}
export function windowPane(b, o = {}) {
  const w = o.w ?? 0.8
  const h = o.h ?? 0.6
  b.at({ x: o.x || 0, y: o.y ?? 1.4, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(w, h, 0.02, { mat: 'window', color: '#20262a', ao: 0 })
    b.box(w + 0.1, 0.06, 0.06, { mat: 'wood', color: o.frame ?? '#e8e2d4', y: -h / 2 - 0.03 })
    b.box(w + 0.1, 0.06, 0.06, { mat: 'wood', color: o.frame ?? '#e8e2d4', y: h / 2 + 0.03 })
    for (const sx of [-1, 1]) b.box(0.06, h, 0.06, { mat: 'wood', color: o.frame ?? '#e8e2d4', x: sx * (w / 2 + 0.02) })
    b.box(0.03, h, 0.03, { mat: 'wood', color: o.frame ?? '#e8e2d4', z: 0.02 })
    b.box(w, 0.03, 0.03, { mat: 'wood', color: o.frame ?? '#e8e2d4', z: 0.02 })
    if (o.shutters) for (const sx of [-1, 1]) b.box(w / 2, h + 0.08, 0.03, { mat: 'wood', color: o.shutters, x: sx * (w * 0.75 + 0.06), z: 0.03 })
    if (o.boards) {
      b.box(w + 0.2, 0.12, 0.025, { mat: 'wood', color: '#c8b494', z: 0.05, rz: 0.35 })
      b.box(w + 0.2, 0.12, 0.025, { mat: 'wood', color: '#b8a484', z: 0.06, rz: -0.3, y: -0.1 })
    }
  })
}
export function door(b, o = {}) {
  const w = o.w ?? 0.9
  const h = o.h ?? 2.0
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    const n = 5
    for (let i = 0; i < n; i++) b.box(w / n - 0.006, h, 0.04, { mat: 'wood', color: o.color ?? '#b89470', x: -w / 2 + (w / n) * (i + 0.5), y: h / 2 })
    for (const y of [0.3, h - 0.3]) b.box(w - 0.04, 0.12, 0.03, { mat: 'wood', color: o.color ?? '#b89470', y, z: 0.03 })
    b.beam([-w / 2 + 0.1, 0.36, 0.035], [w / 2 - 0.1, h - 0.36, 0.035], 0.1, 0.03, { mat: 'wood', color: o.color ?? '#b89470' })
    b.cyl(0.02, 0.02, 0.04, { mat: 'steel', color: '#5a5a5a', x: w / 2 - 0.12, y: 1.0, z: 0.06, rx: Math.PI / 2 })
    b.box(w + 0.12, 0.08, 0.06, { mat: 'wood', color: COL.woodDark, y: h + 0.04 })
    for (const sx of [-1, 1]) b.box(0.06, h, 0.06, { mat: 'wood', color: COL.woodDark, x: sx * (w / 2 + 0.03), y: h / 2 })
  })
}
// Four corner posts with top beams: frames for lean-tos and pavilions.
export function frame(b, w, d, h, o = {}) {
  const c = o.color ?? COL.woodGrey
  const m = o.metal ? { mat: 'paint', color: o.color ?? '#5a6066' } : { mat: 'wood', color: c }
  const ps = o.post ?? 0.1
  const xs = o.xs ?? [-w / 2, w / 2]
  const zs = o.zs ?? [-d / 2, d / 2]
  const hAt = (z) => (o.hFront != null ? h + ((o.hFront - h) * (z - zs[0])) / (zs[zs.length - 1] - zs[0] || 1) : h)
  for (const x of xs) for (const z of zs) b.box(ps, hAt(z), ps, { ...m, x, y: hAt(z) / 2, z })
  for (const z of zs) b.box(w + ps, ps, ps, { ...m, y: hAt(z) - ps / 2, z })
  for (const x of xs) b.beam([x, hAt(zs[0]) - ps / 2, zs[0]], [x, hAt(zs[zs.length - 1]) - ps / 2, zs[zs.length - 1]], ps, ps, m)
  if (o.braces !== false)
    for (const x of [xs[0], xs[xs.length - 1]])
      for (const z of [zs[0], zs[zs.length - 1]]) {
        const sz = z > 0 ? -1 : 1
        b.beam([x, hAt(z) - 0.6, z], [x, hAt(z) - ps, z + sz * 0.5], 0.06, 0.06, m)
      }
}
export function hangingTools(b, x, y, z) {
  b.box(0.03, 0.4, 0.03, { mat: 'wood', color: '#c8a070', x, y: y - 0.2, z, rz: 0.1 })
  b.box(0.18, 0.03, 0.06, { mat: 'steel', color: '#5a5e62', x: x + 0.02, y: y - 0.42, z })
}

// A rusting car body, optionally half-stripped (wheels, doors, bonnet gone).
export function carWreck(b, o = {}) {
  const c = o.color ?? '#6a7a8a'
  const st = o.stripped ?? 0.4
  const rnd = seeded(o.seed ?? Math.floor((o.x || 0) * 31 + (o.z || 0) * 7))
  b.at({ x: o.x || 0, z: o.z || 0, ry: o.ry || 0, y: o.y || 0 }, () => {
    const body = { mat: 'paint', color: c }
    const lift = st > 0.5 ? 0.18 : 0.32
    b.box(1.75, 0.55, 4.1, { ...body, y: lift + 0.32, r: 0.08 })
    b.box(1.6, 0.5, 2.0, { ...body, y: lift + 0.82, z: -0.2, r: 0.12 })
    b.box(1.5, 0.42, 1.9, { mat: 'glass', color: '#3a4a50', y: lift + 0.84, z: -0.2, sx: 1.04 })
    b.box(1.76, 0.25, 4.12, { mat: 'rust', color: '#ffffff', y: lift + 0.18 })
    b.box(1.6, 0.05, 1.0, { mat: 'rust', color: '#ffffff', y: lift + 0.6, z: 1.45, shadow: false })
    if (st < 0.5) b.box(1.7, 0.04, 1.2, { ...body, y: lift + 0.62, z: 1.45, rx: -0.05 })
    else b.box(1.2, 0.5, 0.8, { mat: 'metal', color: '#3a3a3a', y: lift + 0.55, z: 1.45 })
    for (const sx of [-1, 1]) {
      for (const sz of [-1.3, 1.3]) {
        if (rnd() < st) b.box(0.25, 0.3, 0.25, { mat: 'concrete', color: '#a8a49c', x: sx * 0.65, y: 0.15, z: sz })
        else tire(b, { x: sx * 0.8, y: 0.34, z: sz, rz: Math.PI / 2, rx: 0, rim: true })
      }
      b.box(0.02, 0.2, 0.06, { mat: 'chrome', color: '#c8ccd0', x: sx * 0.88, y: lift + 0.5, z: 0.1 })
    }
    for (const sx of [-1, 1]) b.box(0.25, 0.14, 0.04, { mat: 'glass', color: '#f0e8c8', x: sx * 0.6, y: lift + 0.45, z: 2.06 })
    b.box(1.6, 0.18, 0.08, { mat: 'chrome', color: '#a8acb0', y: lift + 0.2, z: 2.08 })
    b.box(1.6, 0.18, 0.08, { mat: 'chrome', color: '#a8acb0', y: lift + 0.2, z: -2.08 })
    for (const sx of [-1, 1]) b.box(0.3, 0.12, 0.04, { mat: 'glowRed', color: '#5a1a1a', x: sx * 0.62, y: lift + 0.48, z: -2.07 })
  })
}

