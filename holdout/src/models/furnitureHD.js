// High-detail furniture for supply-run interiors. Same contract as
// furniture.js: models face +z with their back to a wall, origin at the
// footprint centre, and doors / drawers live in pivots ('door', 'doorL',
// 'doorR', 'drawerN') that swing or slide open once a container is searched.
// Containers are hollow carcasses so their contents show when opened.
import * as THREE from 'three'
import { seeded } from './kit.js'
import { shelfItems, crate as crateProp, pallet as palletProp, shadeHex, barrel, blob, screenMat, wheelbarrow } from './parts.js'
import { canvasMat, decal, plateMat, diamondMat, posterMat, pegboard, heavyBench, partsBins, benchClutter, longGun, pistol, ammoCan, glassSet, wrench, hammer, screwdriver } from './detail.js'
import { stencilMat, drawForklift } from './vehicles.js'
import { leafClump } from './nature.js'

const TAU = Math.PI * 2
const PI = Math.PI
const pick = (r, a) => a[Math.floor(r() * a.length)]
const sh = shadeHex
const WOOD = ['#d8c0a0', '#c8a880', '#a88460', '#e0ccb0', '#8a6a4a', '#6a4a32']
const PAINT = ['#d2cec4', '#d6cebe', '#c4d0d4', '#b4c0ac', '#e4d8c4', '#8a9a9e', '#3e4a52']
const BOOK = ['#7a2e22', '#2a4466', '#34543a', '#c0a876', '#46365a', '#d4ccb8', '#5e2424', '#1e2e3e', '#8a6a2a', '#3a3a3a', '#a84a2a', '#5a7a8a']

// ---------------------------------------------------------------- materials
// Printed packaging: a bright face with a brand word and stripes.
const BRANDS = ['CRUNCH', 'OATIES', 'SUDZ', 'PRIME', 'KORN', 'BRITE', 'FRESH', 'ZESTO', 'NUTRA', 'MAXI', 'GOLDEN', 'PURE']
export function packMat(seed) {
  const k = seed % 12
  return canvasMat('pack' + k, [128, 128], (g, w, h) => {
    const r = seeded(k + 400)
    const bg = pick(r, ['#c8362a', '#d8a020', '#2a62a8', '#36903e', '#d8621e', '#70368e', '#d4ccbc', '#1a3a6a'])
    g.fillStyle = bg
    g.fillRect(0, 0, w, h)
    g.fillStyle = pick(r, ['#d8d4cc', '#1a1a1a', '#d8b838'])
    g.fillRect(0, h * 0.62, w, h * 0.1)
    g.beginPath()
    g.arc(w * 0.5, h * 0.82, w * 0.18, 0, TAU)
    g.fillStyle = pick(r, ['#c8b888', '#d0ccc0', '#c84a3a', '#58a048'])
    g.fill()
    g.fillStyle = bg === '#e8e0d0' ? '#c83a2a' : '#e8e4dc'
    g.font = '900 30px Impact, "Arial Narrow", sans-serif'
    g.textAlign = 'center'
    g.fillText(BRANDS[k], w / 2, h * 0.38, w - 10)
    g.font = '700 12px Arial, sans-serif'
    g.fillText(pick(r, ['FAMILY SIZE', 'NEW!', '100% NATURAL', 'ORIGINAL', 'EXTRA']), w / 2, h * 0.55)
  })
}
// A woven rug: patterned borders, a medallion, a field of small motifs and
// fibre noise, all in muted dyes so it sits in the room rather than glowing.
export function rugMat(seed) {
  const k = seed % 8
  return canvasMat('rug' + k, [512, 384], (g, w, h) => {
    const r = seeded(k + 700)
    const pal = [
      ['#6a2420', '#c09868', '#2a3a4a', '#8a5a2a'],
      ['#2a3450', '#b89a6a', '#7a2a24', '#3a5a5a'],
      ['#7a5a32', '#3a2a20', '#a87a4a', '#5a3a2a'],
      ['#3a4a32', '#a8905a', '#6a2a24', '#2a3020'],
      ['#5a3448', '#b8a070', '#2a3448', '#7a5a3a'],
      ['#8a6a44', '#4a2a20', '#2a3a3a', '#c0a070'],
      ['#4a4a4e', '#9a8a6a', '#6a3a2a', '#2a2a2e'],
      ['#6a3a2a', '#a8946a', '#3a4a5a', '#4a2a1e'],
    ][k]
    const [field, ink, acc, deep] = pal
    g.fillStyle = field
    g.fillRect(0, 0, w, h)
    // field: a lattice of small diamonds and flowers
    for (let y = 70; y < h - 70; y += 22) for (let x = 70; x < w - 70; x += 22) {
      const off = ((y / 22) % 2) * 11
      g.save()
      g.translate(x + off, y)
      g.rotate(PI / 4)
      g.fillStyle = (x + y) % 44 ? acc : deep
      g.fillRect(-4, -4, 8, 8)
      g.restore()
      g.fillStyle = ink
      g.fillRect(x + off - 1, y - 1, 2, 2)
    }
    // central medallion: nested lozenges with petals
    g.save()
    g.translate(w / 2, h / 2)
    for (const [sz, c] of [[96, deep], [80, ink], [64, acc], [44, field], [30, ink], [16, deep]]) {
      g.fillStyle = c
      g.beginPath()
      g.moveTo(-sz * 1.4, 0)
      g.lineTo(0, -sz)
      g.lineTo(sz * 1.4, 0)
      g.lineTo(0, sz)
      g.closePath()
      g.fill()
    }
    for (let i = 0; i < 8; i++) {
      g.rotate(PI / 4)
      g.fillStyle = acc
      g.beginPath()
      g.ellipse(0, -60, 7, 16, 0, 0, TAU)
      g.fill()
    }
    g.restore()
    // borders: bands with a running zigzag and corner blocks
    const band = (m, t, c) => {
      g.fillStyle = c
      g.fillRect(m, m, w - 2 * m, t)
      g.fillRect(m, h - m - t, w - 2 * m, t)
      g.fillRect(m, m, t, h - 2 * m)
      g.fillRect(w - m - t, m, t, h - 2 * m)
    }
    band(0, 14, deep)
    band(14, 4, ink)
    band(18, 30, acc)
    band(48, 4, ink)
    g.strokeStyle = ink
    g.lineWidth = 3
    const zig = (x0, y0, x1, y1) => {
      const n = Math.floor(Math.hypot(x1 - x0, y1 - y0) / 12)
      g.beginPath()
      for (let i = 0; i <= n; i++) {
        const t = i / n
        const x = x0 + (x1 - x0) * t
        const y = y0 + (y1 - y0) * t
        const o = i % 2 ? 9 : -9
        if (x0 === x1) g.lineTo(x + o, y)
        else g.lineTo(x, y + o)
      }
      g.stroke()
    }
    zig(30, 33, w - 30, 33)
    zig(30, h - 33, w - 30, h - 33)
    zig(33, 30, 33, h - 30)
    zig(w - 33, 30, w - 33, h - 30)
    // fibre noise and worn, faded patches
    const img = g.getImageData(0, 0, w, h)
    for (let i = 0; i < img.data.length; i += 4) {
      const n = (r() - 0.5) * 26
      img.data[i] = Math.max(0, Math.min(255, img.data[i] * 0.86 + n))
      img.data[i + 1] = Math.max(0, Math.min(255, img.data[i + 1] * 0.86 + n))
      img.data[i + 2] = Math.max(0, Math.min(255, img.data[i + 2] * 0.86 + n))
    }
    g.putImageData(img, 0, 0)
    g.globalAlpha = 0.14
    g.fillStyle = '#d8c8a8'
    for (let i = 0; i < 5; i++) {
      g.beginPath()
      g.ellipse(r() * w, r() * h, 20 + r() * 50, 10 + r() * 30, r() * PI, 0, TAU)
      g.fill()
    }
  }, { rough: 1 })
}
// Small label stickers: price tags, file labels, warning text.

// ---------------------------------------------------------------- helpers
// A printed box: plain sides, the printed face as a decal on the front.
function pack(b, w, h, d, o = {}) {
  const seed = o.seed ?? 0
  const side = ['#c83a2a', '#e8b020', '#2a6ab8', '#3a9a4a', '#e86a20', '#7a3a9a', '#e8e0d0', '#1a3a6a'][seed % 8]
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0 }, () => {
    b.box(w, h, d, { mat: 'plain', color: side, y: h / 2 })
    b.plane(w * 0.98, h * 0.98, { material: packMat(seed), y: h / 2, z: d / 2 + 0.0015 })
  })
}
// A flat label (plate material) on a face pointing +z.
function label(b, text, w, h, o = {}) {
  b.plane(w, h, { material: plateMat(text, o.bg ?? '#d6d2c6', o.fg ?? '#1a1a1a'), x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0, shadow: false })
}
// Bar pull, knob, bail or cup pull on a face pointing +z at (x, y, z).
function pull(b, x, y, z, o = {}) {
  const kind = o.kind ?? 'bar'
  const len = o.len ?? 0.12
  const col = o.color ?? '#b8bcc0'
  const m = o.mat ?? 'chrome'
  const v = !!o.vertical
  if (kind === 'knob') {
    b.cyl(0.005, 0.007, 0.016, { mat: m, color: col, x, y, z: z + 0.008, rx: PI / 2, seg: 6 })
    b.sphere(0.0135, { mat: m, color: col, x, y, z: z + 0.022, sz: 0.75, ws: 10, hs: 7 })
  } else if (kind === 'bail') {
    // brass back-plate with a swinging half-ring
    b.box(0.09, 0.03, 0.004, { mat: m, color: col, x, y: y + 0.006, z: z + 0.002, r: 0.002 })
    b.torus(0.035, 0.0035, { mat: m, color: col, x, y: y + 0.004, z: z + 0.012, rz: PI, arc: PI, rs: 4, ts2: 10 })
  } else if (kind === 'cup') {
    b.cyl(0.032, 0.032, 0.012, { mat: m, color: col, x, y, z: z + 0.006, rx: PI / 2, ts: 0, tl: PI, seg: 10, rz: PI })
  } else if (kind === 'slot') {
    b.box(len, 0.018, 0.006, { mat: 'plain', color: '#1a1a1a', x, y, z: z + 0.001 })
    b.box(len * 0.9, 0.008, 0.012, { mat: m, color: col, x, y: y - 0.004, z: z + 0.006 })
  } else {
    const a = (len / 2) * 0.86
    for (const s of [-1, 1]) b.cyl(0.0045, 0.0045, 0.022, { mat: m, color: col, x: x + (v ? 0 : s * a), y: y + (v ? s * a : 0), z: z + 0.011, rx: PI / 2, seg: 6 })
    b.cyl(0.006, 0.006, len, { mat: m, color: col, x, y, z: z + 0.023, rz: v ? 0 : PI / 2, seg: 8 })
  }
}
// Shaker / raised-panel / glazed door centred at the origin, front at +t/2.
function panelDoor(b, w, h, o = {}) {
  const t = o.t ?? 0.022
  const fr = o.frame ?? Math.min(0.065, w * 0.17, h * 0.17)
  const m = { mat: o.mat ?? 'paint', color: o.color ?? '#d2cec4' }
  for (const s of [-1, 1]) b.box(fr, h, t, { ...m, x: (s * (w - fr)) / 2 })
  for (const s of [-1, 1]) b.box(w - 2 * fr + 0.002, fr, t, { ...m, y: (s * (h - fr)) / 2 })
  const pw = w - 2 * fr
  const ph = h - 2 * fr
  if (o.glass) {
    b.box(pw + 0.004, ph + 0.004, 0.004, { mat: 'glass', color: '#dce8ec', z: -t * 0.15 })
    if (o.muntin) {
      b.box(0.012, ph, t * 0.6, { ...m, z: -t * 0.1 })
      b.box(pw, 0.012, t * 0.6, { ...m, z: -t * 0.1 })
    }
  } else {
    b.box(pw + 0.004, ph + 0.004, t * 0.5, { ...m, z: -t * 0.22, color: sh(m.color, -0.04) })
    if (o.raised) b.box(pw - 0.03, ph - 0.03, t * 0.5, { ...m, z: t * 0.05, r: 0.006 })
  }
}
// A drawer: front panel plus a shallow box behind it, opening along +z.
function drawerBox(b, w, h, d, o = {}) {
  const m = { mat: o.mat ?? 'paint', color: o.color ?? '#d2cec4' }
  if (o.shaker) panelDoor(b, w, h, { ...m, t: 0.02, frame: Math.min(0.04, h * 0.25) })
  else b.box(w, h, 0.02, { ...m, r: o.r ?? 0 })
  const ic = o.inside ?? '#b8a888'
  const bd = d - 0.04
  b.box(w - 0.04, 0.012, bd, { mat: 'wood', color: ic, y: -h / 2 + 0.03, z: -bd / 2 - 0.01 })
  for (const s of [-1, 1]) b.box(0.012, h * 0.7, bd, { mat: 'wood', color: ic, x: (s * (w - 0.05)) / 2, y: -h * 0.12, z: -bd / 2 - 0.01 })
  b.box(w - 0.04, h * 0.7, 0.012, { mat: 'wood', color: ic, y: -h * 0.12, z: -bd - 0.01 })
  if (o.fill) o.fill(w - 0.08, bd - 0.04, -h / 2 + 0.036, -bd / 2 - 0.01)
}
// A hollow carcass (open front) of width w, height h, depth d standing on y0.
function carcass(b, w, h, d, o = {}) {
  const t = o.t ?? 0.018
  const m = { mat: o.mat ?? 'paint', color: o.color ?? '#d2cec4' }
  const ins = { mat: o.insideMat ?? m.mat, color: o.inside ?? sh(m.color, 0.04) }
  const y0 = o.y0 ?? 0
  for (const s of [-1, 1]) b.box(t, h, d, { ...m, x: (s * (w - t)) / 2, y: y0 + h / 2 })
  b.box(w - 2 * t, t, d - t, { ...ins, y: y0 + t / 2, z: t / 2 })
  if (!o.noTop) b.box(w, t, d, { ...m, y: y0 + h - t / 2 })
  b.box(w - 2 * t, h - 2 * t, t, { ...ins, y: y0 + h / 2, z: -d / 2 + t / 2 })
  return { x0: -w / 2 + t, x1: w / 2 - t, y0: y0 + t, y1: y0 + h - t, z0: -d / 2 + t, z1: d / 2 }
}
// Books standing along a shelf from x0 to x1 on y, backs against zb.
function booksRow(b, r, x0, x1, y, zb, maxD, maxH, o = {}) {
  let x = x0
  let lean = 0
  while (x < x1 - 0.02) {
    if (r() < (o.gap ?? 0.07)) {
      x += 0.04 + r() * 0.1
      continue
    }
    if (r() < 0.06 && x1 - x > 0.25) {
      // a stack lying flat
      const n = 2 + Math.floor(r() * 4)
      let yy = y
      for (let i = 0; i < n; i++) {
        const bh = 0.025 + r() * 0.02
        const bw = 0.15 + r() * 0.07
        b.box(bw, bh, Math.min(maxD, 0.18 + r() * 0.05), { mat: 'paint', color: pick(r, BOOK), x: x + 0.11 + (r() - 0.5) * 0.02, y: yy + bh / 2, z: zb + 0.11, ry: (r() - 0.5) * 0.2 })
        yy += bh
      }
      x += 0.25
      continue
    }
    const bw = 0.018 + r() * 0.038
    if (x + bw > x1) break
    const bh = Math.min(maxH, 0.16 + r() * 0.12)
    const bd = Math.min(maxD, 0.12 + r() * 0.08)
    const c = pick(r, BOOK)
    const tilt = lean ? lean : 0
    b.box(bw, bh, bd, { mat: r() < 0.5 ? 'cloth' : 'paint', color: c, x: x + bw / 2 + Math.sin(tilt) * bh * 0.5, y: y + (bh / 2) * Math.cos(tilt), z: zb + bd / 2, rz: -tilt })
    // spine bands and titles
    if (r() < 0.75) {
      const bc = pick(r, ['#d8c890', '#e8e0d0', '#1a1a1a', '#c8a040'])
      b.box(bw * 1.01, 0.01, 0.003, { mat: 'plain', color: bc, x: x + bw / 2 + Math.sin(tilt) * bh * 0.5, y: y + bh * 0.82, z: zb + bd + 0.0005, rz: -tilt })
      b.box(bw * 0.5, bh * 0.35, 0.003, { mat: 'plain', color: bc, x: x + bw / 2 + Math.sin(tilt) * bh * 0.5, y: y + bh * 0.5, z: zb + bd + 0.0005, rz: -tilt })
    }
    lean = !lean && r() < 0.05 ? 0.22 : 0
    x += bw + (lean ? 0.06 : 0.002)
  }
}
function bottle(b, x, y, z, o = {}) {
  const h = o.h ?? 0.24
  const r0 = o.r ?? 0.035
  b.lathe([[r0, 0], [r0, h * 0.62], [r0 * 0.4, h * 0.82], [r0 * 0.36, h]], { mat: o.mat ?? 'glass', color: o.color ?? '#3a6a3a', x, y, z, seg: o.seg ?? 9 })
  if (o.cap) b.cyl(r0 * 0.4, r0 * 0.4, 0.02, { mat: 'plastic', color: o.cap, x, y: y + h + 0.008, z, seg: 6 })
  if (o.label) b.cyl(r0 + 0.001, r0 + 0.001, h * 0.32, { mat: 'plain', color: o.label, x, y: y + h * 0.3, z, seg: o.seg ?? 9, open: true })
}
function jar(b, x, y, z, col, o = {}) {
  const h = o.h ?? 0.12
  const r0 = o.r ?? 0.042
  b.cyl(r0, r0, h, { mat: 'glass', color: '#e8f0e8', x, y: y + h / 2, z, seg: 10 })
  b.cyl(r0 - 0.004, r0 - 0.004, h * 0.75, { mat: 'plain', color: col, x, y: y + h * 0.4, z, seg: 10 })
  b.cyl(r0 + 0.002, r0 + 0.002, 0.018, { mat: 'metal', color: o.lid ?? '#c8b860', x, y: y + h + 0.009, z, seg: 10 })
}
function mug(b, x, y, z, col, ry = 0) {
  b.cyl(0.04, 0.036, 0.09, { mat: 'plastic', color: col, x, y: y + 0.045, z, seg: 12 })
  b.cyl(0.034, 0.034, 0.002, { mat: 'plain', color: '#2a1a12', x, y: y + 0.08, z, seg: 10 })
  b.torus(0.024, 0.006, { mat: 'plastic', color: col, x: x + Math.cos(ry) * 0.045, y: y + 0.047, z: z - Math.sin(ry) * 0.045, ry, rs: 4, ts2: 8, arc: PI * 1.2, rz: -PI * 0.6 })
}
function plates(b, x, y, z, n, col = '#c6c2ba', r0 = 0.12) {
  for (let i = 0; i < n; i++) b.cyl(r0, r0 * 0.75, 0.012, { mat: 'gloss', color: col, x, y: y + 0.006 + i * 0.013, z, seg: 16 })
}
// Clothes on a hanger along a rail at height y (garment hangs in the yz plane).
function garment(b, r, x, y, z, o = {}) {
  const col = o.color ?? pick(r, ['#3a4a6a', '#8a3a3a', '#d4ccbc', '#4a4a4a', '#6a7a4a', '#c8a070', '#2a2a32', '#7a6a5a', '#a85a6a'])
  const len = o.len ?? (r() < 0.3 ? 0.95 : 0.6 + r() * 0.15)
  const wide = len > 0.85 ? 0.23 : 0.21
  // hanger
  b.torus(0.012, 0.0022, { mat: 'chrome', color: '#a8acb0', x, y: y + 0.004, z, ry: PI / 2, rs: 4, ts2: 8, arc: PI * 1.4, rz: -PI * 0.2 })
  b.beam([x, y - 0.012, z - 0.18], [x, y - 0.012, z + 0.18], 0.006, 0.006, { mat: o.hangerWood ? 'wood' : 'plastic', color: o.hangerWood ? '#a87a4a' : '#2a2a2a' })
  // garment silhouette: shoulders, body tapering to the hem
  const pts = [[-wide * 0.9, -len], [wide * 0.9, -len], [wide + 0.01, -0.12], [wide * 0.9, -0.03], [0.05, 0], [-0.05, 0], [-wide * 0.9, -0.03], [-wide - 0.01, -0.12]]
  b.profile(pts, 0.035 + r() * 0.02, { mat: 'cloth', color: col, x, y: y - 0.014, z, bevel: 0.006, bevelSeg: 1, curve: 2 })
  if (r() < 0.5) b.profile([[-0.05, -0.2], [0.05, -0.2], [0.05, 0], [-0.05, 0]], 0.04, { mat: 'cloth', color: sh(col, -0.08), x: x + 0.002, y: y - 0.02, z })
}
// A plump cushion with a darker welt seam around its top edge.
function cushion(b, w, h, d, o = {}) {
  const col = o.color ?? '#6a4a3a'
  const m = o.mat ?? 'cloth'
  b.box(w, h, d, { mat: m, color: col, x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0, rz: o.rz || 0, r: Math.min(h * 0.45, o.r ?? 0.05), seg: 3 })
  if (o.welt !== false) {
    b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0, rz: o.rz || 0 }, () => {
      b.cyl(0.006, 0.006, w - 0.06, { mat: m, color: sh(col, -0.12), y: h / 2 - 0.012, z: d / 2 - 0.004, rz: PI / 2, seg: 5 })
    })
  }
}
function pillow(b, w, h, d, o = {}) {
  b.sphere(0.5, { mat: o.mat ?? 'cloth', color: o.color ?? '#d6d2ca', x: o.x || 0, y: o.y || 0, z: o.z || 0, sx: w, sy: h, sz: d, rx: o.rx || 0, ry: o.ry || 0, rz: o.rz || 0, ws: 12, hs: 8 })
}
// Crumpled paper / food wrappers scattered on a surface.
function litter(b, r, n, x0, x1, z0, z1, y = 0) {
  for (let i = 0; i < n; i++) {
    const x = x0 + r() * (x1 - x0)
    const z = z0 + r() * (z1 - z0)
    const k = r()
    if (k < 0.5) b.ico(0.02 + r() * 0.02, { mat: 'plain', color: pick(r, ['#ccc8bc', '#c0b8a8', '#b8a888']), x, y: y + 0.015, z, detail: 0, noise: 0.5 })
    else if (k < 0.8) b.box(0.08 + r() * 0.06, 0.002, 0.1 + r() * 0.05, { mat: 'plain', color: pick(r, ['#d6d2c6', '#e8e0c8', '#d8d8d0']), x, y: y + 0.002, z, ry: r() * PI })
    else b.cyl(0.033, 0.033, 0.12, { mat: 'metal', color: pick(r, ['#c83a2a', '#2a6ab8', '#e8c040', '#c8ccd0']), x, y: y + 0.033, z, rz: PI / 2, ry: r() * PI, seg: 8 })
  }
}

// ---------------------------------------------------------------- containers
export const HC = {}

HC.fridge = (b, r) => {
  const steel = r() < 0.3
  const col = steel ? '#a8acae' : pick(r, ['#d2d2cc', '#dcd8cc', '#e0d4bc', '#c8ccc4'])
  const m = steel ? 'metal' : 'paint'
  const W = 0.78
  const H = 1.82
  const D = 0.66
  const z0 = -0.36
  // carcass with an insulated liner, open front behind the doors
  b.at({ z: z0 + D / 2 }, () => {
    const C2 = carcass(b, W, H - 0.08, D - 0.04, { mat: m, color: col, inside: '#d8dad6', insideMat: 'plastic', t: 0.04, y0: 0.08 })
    b.box(W - 0.08, 0.035, D - 0.08, { mat: 'plastic', color: '#e6e8e4', y: 1.22, z: 0.0 })
    // fridge shelves, crisper drawers and food
    for (const y of [0.56, 0.84]) {
      b.box(W - 0.1, 0.008, D - 0.14, { mat: 'glass', color: '#e0ecec', y, z: -0.02 })
      b.box(W - 0.1, 0.02, 0.012, { mat: 'plastic', color: '#d8dcd8', y, z: (D - 0.14) / 2 - 0.02 })
    }
    for (const s of [-1, 1]) {
      b.box(0.3, 0.16, D - 0.16, { mat: 'glass', color: '#dce8e4', x: s * 0.16, y: 0.25, z: -0.02 })
      for (let k = 0; k < 3; k++) b.sphere(0.04, { mat: 'plastic', color: pick(r, ['#5a9a3a', '#c83a2a', '#e8a020', '#7a9a3a']), x: s * 0.16 + (r() - 0.5) * 0.15, y: 0.22, z: (r() - 0.5) * 0.3, ws: 8, hs: 6 })
    }
    // milk jug, egg carton, leftovers, jars, cans
    b.box(0.1, 0.22, 0.1, { mat: 'plastic', color: '#d8d8d2', x: -0.24, y: 0.68, z: 0.02, r: 0.02 })
    b.cyl(0.018, 0.018, 0.025, { mat: 'plastic', color: pick(r, ['#2a6ab8', '#c83a2a', '#e8c020']), x: -0.24, y: 0.8, z: 0.02, seg: 8 })
    b.box(0.3, 0.07, 0.11, { mat: 'plain', color: '#c8b898', x: 0.12, y: 0.6, z: 0.1, r: 0.01 })
    for (let k = 0; k < 3; k++) b.box(0.14, 0.07, 0.12, { mat: 'plastic', color: pick(r, ['#e8e8e0', '#b8d0e0', '#e0c8b0']), x: -0.05 + k * 0.08, y: 0.9 + k * 0.07, z: -0.12, r: 0.012, ry: (r() - 0.5) * 0.3 })
    jar(b, 0.22, 0.85, 0.04, pick(r, ['#c8502a', '#e8c040', '#7a9a3a']))
    for (let k = 0; k < 4; k++) b.cyl(0.033, 0.033, 0.12, { mat: 'metal', color: pick(r, ['#c83a2a', '#2a6ab8', '#c8ccd0']), x: 0.08 + k * 0.07, y: 0.92, z: 0.1, seg: 8 })
    // freezer: ice trays and boxes
    b.box(0.6, 0.008, D - 0.16, { mat: 'plastic', color: '#e0e4e0', y: 1.5, z: -0.02 })
    for (let k = 0; k < 3; k++) pack(b, 0.16, 0.1 + r() * 0.06, 0.18, { seed: Math.floor(r() * 12), x: -0.2 + k * 0.2, y: 1.255, z: 0.02 })
    void C2
  })
  // kick grille
  b.box(W - 0.06, 0.07, 0.02, { mat: 'plastic', color: '#2a2a2a', y: 0.045, z: z0 + D - 0.02 })
  for (let k = 0; k < 4; k++) b.box(W - 0.12, 0.006, 0.006, { mat: 'plain', color: '#111111', y: 0.022 + k * 0.016, z: z0 + D - 0.008 })
  // doors (hinged left), each with gasket, bins on the inside, handle
  const doorZ = z0 + D + 0.002
  const door = (name, y0, h, bins, deco) =>
    b.pivot(name, { x: -W / 2, y: 0, z: doorZ }, (d) => {
      if (deco) deco(d)
      d.box(W, h, 0.055, { mat: m, color: col, x: W / 2, y: y0 + h / 2, z: 0.0275, r: 0.016, seg: 2 })
      d.box(W - 0.04, h - 0.04, 0.008, { mat: 'rubber', color: '#3a3a3a', x: W / 2, y: y0 + h / 2, z: -0.004 })
      d.box(W - 0.08, h - 0.08, 0.02, { mat: 'plastic', color: '#d6d8d4', x: W / 2, y: y0 + h / 2, z: -0.014 })
      for (const by of bins) {
        d.box(W - 0.14, 0.09, 0.08, { mat: 'glass', color: '#d8e4e4', x: W / 2, y: by, z: -0.07 })
        for (let k = 0; k < 4; k++) if (r() < 0.75) bottle(d, W / 2 - 0.24 + k * 0.15, by - 0.04, -0.07, { h: 0.2 + r() * 0.06, r: 0.03, color: pick(r, ['#3a6a3a', '#6a3a1a', '#d4d0c8', '#c8302a', '#e8c040']), mat: 'plastic', seg: 7 })
      }
      // full-height handle on the latch side
      const hx = W - 0.06
      for (const s of [-1, 1]) d.box(0.022, 0.022, 0.035, { mat: steel ? 'chrome' : 'plastic', color: steel ? '#c8ccd0' : sh(col, -0.12), x: hx, y: y0 + h / 2 + s * h * 0.32, z: 0.07 })
      d.box(0.022, h * 0.72, 0.02, { mat: steel ? 'chrome' : 'plastic', color: steel ? '#c8ccd0' : sh(col, -0.12), x: hx, y: y0 + h / 2, z: 0.09, r: 0.008 })
      return d
    })
  // notes, a calendar and a child's drawing on the fridge door
  door('door', 0.1, 1.12, [0.42, 0.78], (d) => {
    d.box(0.18, 0.24, 0.003, { mat: 'plain', color: '#dcd8c4', x: 0.3, y: 1.0, z: 0.057, rz: -0.05 })
    d.plane(0.1, 0.12, { material: posterMat(Math.floor(r() * 9) + 1), x: 0.3, y: 1.0, z: 0.06, rz: -0.05 })
    for (let k = 0; k < 4; k++) d.box(0.03, 0.03, 0.012, { mat: 'plastic', color: pick(r, ['#c83a2a', '#2a6ab8', '#e8c020', '#3a9a4a']), x: 0.15 + k * 0.11, y: 0.86 + (k % 2) * 0.32, z: 0.062, r: 0.006 })
  })
  b.pivot('door2', { x: -W / 2, y: 0, z: doorZ }, (d) => {
    d.box(W, 0.56, 0.055, { mat: m, color: col, x: W / 2, y: 1.52, z: 0.0275, r: 0.016, seg: 2 })
    d.box(W - 0.04, 0.52, 0.008, { mat: 'rubber', color: '#3a3a3a', x: W / 2, y: 1.52, z: -0.004 })
    d.box(W - 0.08, 0.48, 0.02, { mat: 'plastic', color: '#d6d8d4', x: W / 2, y: 1.52, z: -0.014 })
    d.box(0.022, 0.32, 0.02, { mat: steel ? 'chrome' : 'plastic', color: steel ? '#c8ccd0' : sh(col, -0.12), x: W - 0.06, y: 1.46, z: 0.09, r: 0.008 })
    for (const s of [-1, 1]) d.box(0.022, 0.022, 0.035, { mat: steel ? 'chrome' : 'plastic', color: steel ? '#c8ccd0' : sh(col, -0.12), x: W - 0.06, y: 1.46 + s * 0.13, z: 0.07 })
    // magnets and a postcard on the freezer door
    d.box(0.12, 0.08, 0.004, { mat: 'plain', color: pick(r, ['#e8d8b0', '#b8d0e0', '#e0c0c0']), x: 0.28, y: 1.6, z: 0.057, rz: 0.08 })
  })
  b.box(0.12, 0.022, 0.004, { mat: 'chrome', color: '#d0d4d8', x: 0, y: 1.76, z: doorZ + 0.06 })
}

HC.cabinet = (b, r) => {
  const col = pick(r, PAINT)
  const top = pick(r, ['#d8d0c0', '#2e2e30', '#c8b898', '#d4d0c8', '#8a7a68'])
  const W = 0.9
  const D = 0.6
  // base cabinet: carcass with toe kick and a shelf of dishes
  b.box(W - 0.06, 0.1, 0.02, { mat: 'plain', color: '#2a2622', y: 0.05, z: D / 2 - 0.08 })
  b.at({ z: 0 }, () => {
    carcass(b, W, 0.76, D - 0.02, { color: col, inside: '#d4cec0', y0: 0.1, noTop: true })
    b.box(W - 0.04, 0.016, D - 0.06, { mat: 'paint', color: '#d4cec0', y: 0.46, z: -0.01 })
    plates(b, -0.2, 0.47, -0.02, 6, pick(r, ['#d6d2ca', '#d8e0e8', '#e8dcc8']))
    for (let k = 0; k < 3; k++) b.cyl(0.075, 0.05, 0.06, { mat: 'gloss', color: '#d4d0c8', x: 0.18, y: 0.5 + k * 0.045, z: -0.02, seg: 12 })
    b.cyl(0.11, 0.1, 0.16, { mat: 'metal', color: '#8a8e92', x: -0.15, y: 0.19, z: -0.02, seg: 14 })
    b.cyl(0.012, 0.012, 0.16, { mat: 'plastic', color: '#1a1a1a', x: -0.15 + 0.17, y: 0.26, z: -0.02, rz: PI / 2, seg: 6 })
  })
  for (const s of [-1, 1])
    b.pivot(s < 0 ? 'doorL' : 'doorR', { x: s * (W / 2 - 0.003), y: 0, z: D / 2 - 0.004 }, (d) => {
      d.at({ x: -s * (W / 4 - 0.002), y: 0.48, z: 0.011 }, () => panelDoor(d, W / 2 - 0.008, 0.72, { color: col }))
      pull(d, -s * (W / 2 - 0.07), 0.74, 0.022, { kind: 'bar', len: 0.1, vertical: true })
    })
  // counter top with a rolled front edge and a tiled backsplash
  b.box(W + 0.04, 0.038, D + 0.03, { mat: top === '#8a7a68' ? 'wood' : 'concrete', color: top, y: 0.9, z: 0.01 })
  b.cyl(0.019, 0.019, W + 0.04, { mat: top === '#8a7a68' ? 'wood' : 'concrete', color: top, y: 0.9, z: D / 2 + 0.025, rz: PI / 2, seg: 8 })
  b.box(W + 0.04, 0.56, 0.012, { mat: 'tiles', color: pick(r, ['#d4d0c8', '#c8d8d8', '#e0d0b8']), y: 1.2, z: -D / 2 + 0.006, uv: 0.6 })
  // upper wall cabinet, one door glazed
  b.at({ y: 1.48, z: -D / 2 + 0.17 }, () => {
    carcass(b, W, 0.68, 0.32, { color: col, inside: '#d6d0c2' })
    b.box(W - 0.04, 0.014, 0.28, { mat: 'paint', color: '#d6d0c2', y: 0.34 })
    for (let k = 0; k < 4; k++) b.cyl(0.04, 0.034, 0.1, { mat: 'gloss', color: pick(r, ['#d4d0c8', '#3a5a7a', '#c8a060']), x: -0.3 + k * 0.08, y: 0.4, z: 0.0, seg: 10 })
    plates(b, 0.2, 0.03, -0.02, 5, '#d6d2ca', 0.1)
    for (const s of [-1, 1]) {
      b.at({ x: s * (W / 4 - 0.002), y: 0.34, z: 0.17 }, () => panelDoor(b, W / 2 - 0.008, 0.66, { color: col, glass: s > 0, muntin: s > 0 }))
      pull(b, s * 0.05, 0.08, 0.182, { kind: 'knob', color: '#9a9ea2' })
    }
    // under-cabinet light
    b.box(W - 0.2, 0.012, 0.03, { mat: 'nightGlow', color: '#fff0d0', y: -0.008, z: 0.1 })
  })
  // on the counter: microwave, bread box or a knife block and fruit
  const k = r()
  if (k < 0.4) {
    b.box(0.46, 0.27, 0.34, { mat: 'paint', color: pick(r, ['#d4d4ce', '#2a2a2c', '#a8acae']), x: -0.12, y: 1.055, z: -0.08, r: 0.012 })
    b.box(0.28, 0.19, 0.005, { mat: 'mapGlass', color: '#1a1e20', x: -0.17, y: 1.06, z: 0.092 })
    b.box(0.08, 0.2, 0.004, { mat: 'plastic', color: '#3a3c3e', x: 0.06, y: 1.06, z: 0.092 })
    b.box(0.05, 0.016, 0.003, { mat: 'glowGreen', color: '#123a1a', x: 0.06, y: 1.135, z: 0.095 })
  } else if (k < 0.7) {
    b.box(0.4, 0.2, 0.26, { mat: 'wood', color: '#a87a4a', x: -0.15, y: 1.02, z: -0.1, r: 0.04 })
    b.box(0.08, 0.1, 0.12, { mat: 'wood', color: '#3a2a1e', x: 0.24, y: 0.97, z: -0.16, rx: -0.3 })
    for (let i = 0; i < 4; i++) b.box(0.012, 0.06, 0.03, { mat: 'plastic', color: '#1a1a1a', x: 0.22 + i * 0.016, y: 1.05, z: -0.17, rx: -0.3 })
  } else {
    b.cyl(0.14, 0.09, 0.07, { mat: 'wood', color: '#8a5a3a', x: -0.1, y: 0.955, z: -0.05, seg: 14 })
    for (let i = 0; i < 5; i++) b.sphere(0.04, { mat: 'plastic', color: pick(r, ['#c83a2a', '#e8a020', '#7a9a3a', '#e8d040']), x: -0.1 + (r() - 0.5) * 0.12, y: 1.0 + r() * 0.03, z: -0.05 + (r() - 0.5) * 0.1, ws: 8, hs: 6 })
    b.box(0.32, 0.018, 0.22, { mat: 'wood', color: '#c8a070', x: 0.2, y: 0.928, z: 0.0, ry: 0.2 })
  }
}

HC.counter = (b, r) => {
  const col = pick(r, PAINT)
  const top = pick(r, ['#d8d0c0', '#2e2e30', '#c8b898', '#d4d0c8', '#5a5650'])
  const W = 1.9
  const D = 0.6
  b.box(W - 0.06, 0.1, 0.02, { mat: 'plain', color: '#2a2622', y: 0.05, z: D / 2 - 0.08 })
  carcass(b, W, 0.76, D - 0.02, { color: col, inside: '#d4cec0', y0: 0.1, noTop: true })
  // dividers between the bays
  for (const x of [-0.475, 0.0, 0.475]) b.box(0.018, 0.76, D - 0.04, { mat: 'paint', color: col, x, y: 0.48 })
  // left bay: three drawers
  for (let i = 0; i < 3; i++) {
    const h = i === 2 ? 0.16 : 0.24
    const y = i === 0 ? 0.24 : i === 1 ? 0.5 : 0.72
    b.pivot('drawer' + i, { x: -0.71, y, z: D / 2 + 0.002 }, (d) => {
      drawerBox(d, 0.46, h - 0.012, 0.5, {
        color: col,
        shaker: true,
        fill:
          i === 2
            ? (w, dd, y0, zc) => {
              for (let k = 0; k < 6; k++) d.box(0.012, 0.004, 0.2, { mat: 'chrome', color: '#c8ccd0', x: -w / 2 + 0.04 + k * 0.06, y: y0 + 0.004, z: zc, ry: (r() - 0.5) * 0.2 })
            }
            : (w, dd, y0, zc) => {
              for (let k = 0; k < 3; k++) d.box(0.12, 0.05, 0.16, { mat: 'plastic', color: pick(r, ['#d4d0c8', '#c8d8e0', '#3a3a3a']), x: -w / 2 + 0.07 + k * 0.14, y: y0 + 0.025, z: zc, r: 0.01 })
            },
      })
      pull(d, 0, 0.0, 0.02, { kind: 'bar', len: 0.12 })
    })
  }
  // sink bay: two doors
  for (const s of [-1, 1])
    b.pivot(s < 0 ? 'door' : 'doorR', { x: -0.475 + 0.475 / 2 + s * 0.2355 + 0.4753 * 0, y: 0, z: D / 2 + 0.002 }, (d) => {
      d.at({ x: -s * 0.1155, y: 0.48, z: 0.011 }, () => panelDoor(d, 0.229, 0.72, { color: col }))
      pull(d, -s * 0.2, 0.74, 0.022, { kind: 'bar', len: 0.08, vertical: true })
    })
  // under the sink: pipes, a bucket and cleaning bottles
  b.cyl(0.02, 0.02, 0.3, { mat: 'plastic', color: '#d8d8d4', x: -0.24, y: 0.7, z: -0.1, seg: 8 })
  b.cyl(0.11, 0.09, 0.24, { mat: 'plastic', color: pick(r, ['#2a6ab8', '#c83a2a', '#e8c020']), x: -0.3, y: 0.23, z: 0.02, seg: 12 })
  for (let k = 0; k < 3; k++) bottle(b, -0.16 + k * 0.07, 0.11, 0.1, { h: 0.26, r: 0.035, mat: 'plastic', color: pick(r, ['#e8e040', '#40a8e8', '#e85a3a', '#d8d8d2']), cap: '#1a1a1a', seg: 7 })
  // right bays: dishwasher and a door
  b.box(0.45, 0.72, 0.03, { mat: steelOr(r, col), color: sh(col, -0.03), x: 0.237, y: 0.48, z: D / 2 + 0.01 })
  b.box(0.45, 0.07, 0.035, { mat: 'plastic', color: '#2a2c2e', x: 0.237, y: 0.8, z: D / 2 + 0.012 })
  for (let k = 0; k < 4; k++) b.box(0.016, 0.01, 0.004, { mat: k === 0 ? 'glowGreen' : 'plastic', color: k === 0 ? '#123a1a' : '#5a5e62', x: 0.16 + k * 0.04, y: 0.8, z: D / 2 + 0.03 })
  b.box(0.36, 0.022, 0.03, { mat: 'chrome', color: '#c8ccd0', x: 0.237, y: 0.73, z: D / 2 + 0.035 })
  b.pivot('doorR', { x: W / 2 - 0.003, y: 0, z: D / 2 + 0.002 }, (d) => {
    d.at({ x: -0.237, y: 0.48, z: 0.011 }, () => panelDoor(d, 0.46, 0.72, { color: col }))
    pull(d, -0.42, 0.74, 0.022, { kind: 'bar', len: 0.1, vertical: true })
  })
  // worktop, sink, faucet, backsplash
  const tm = top === '#5a5650' ? 'concrete' : 'tiles'
  b.box(W + 0.04, 0.038, D + 0.03, { mat: tm, color: top, y: 0.9, z: 0.01, uv: 0.7 })
  b.cyl(0.019, 0.019, W + 0.04, { mat: tm, color: top, y: 0.9, z: D / 2 + 0.025, rz: PI / 2, seg: 8 })
  b.box(W + 0.04, 0.5, 0.012, { mat: 'tiles', color: pick(r, ['#d4d0c8', '#c8d8d8', '#e0d0b8', '#b8c8b0']), y: 1.17, z: -D / 2 + 0.006, uv: 0.6 })
  for (const s of [-1, 1]) {
    b.box(0.36, 0.006, 0.38, { mat: 'metal', color: '#b8bcc0', x: -0.24 + s * 0.2, y: 0.921, z: 0.02 })
    b.box(0.32, 0.008, 0.34, { mat: 'metal', color: '#6a6e72', x: -0.24 + s * 0.2, y: 0.922, z: 0.02 })
  }
  b.cyl(0.018, 0.022, 0.06, { mat: 'chrome', color: '#d0d4d8', x: -0.24, y: 0.95, z: -0.22, seg: 8 })
  b.tube([[-0.24, 0.97, -0.22], [-0.24, 1.2, -0.2], [-0.24, 1.22, -0.1], [-0.24, 1.13, -0.04]], 0.012, { mat: 'chrome', color: '#d0d4d8', seg: 6, tseg: 10 })
  b.box(0.012, 0.012, 0.08, { mat: 'chrome', color: '#d0d4d8', x: -0.2, y: 1.0, z: -0.22, ry: 0.4 })
  // dish rack, soap, sponge
  b.at({ x: 0.15, y: 0.92, z: -0.05 }, () => {
    b.box(0.4, 0.012, 0.3, { mat: 'plastic', color: '#d4d4ce' })
    for (let k = 0; k < 5; k++) b.cyl(0.11, 0.11, 0.008, { mat: 'gloss', color: '#d6d2ca', x: -0.12 + k * 0.05, y: 0.11, z: 0, rz: PI / 2 - 0.15, seg: 16 })
    mug(b, 0.14, 0.006, 0.08, pick(r, ['#c83a2a', '#2a4a8a', '#d4d0c8']))
  })
  bottle(b, -0.48, 0.92, -0.2, { h: 0.18, r: 0.03, mat: 'plastic', color: pick(r, ['#58c040', '#e8c030', '#40a0e8']), cap: '#d4d0c8', seg: 7 })
  b.box(0.08, 0.03, 0.05, { mat: 'cloth', color: '#e8d040', x: -0.42, y: 0.935, z: -0.12 })
  // coffee maker, toaster, paper towels, utensil crock
  b.at({ x: 0.62, y: 0.92, z: -0.13 }, () => {
    b.box(0.2, 0.32, 0.22, { mat: 'plastic', color: '#1e1e20', y: 0.16, r: 0.02 })
    b.cyl(0.065, 0.06, 0.12, { mat: 'glass', color: '#3a2a1e', y: 0.07, z: 0.04, seg: 12 })
    b.box(0.03, 0.01, 0.004, { mat: 'glowAmber', color: '#ffffff', x: 0.06, y: 0.28, z: 0.112 })
  })
  b.box(0.27, 0.17, 0.15, { mat: 'chrome', color: '#b8bcc0', x: 0.88, y: 1.005, z: -0.12, r: 0.03 })
  for (const s of [-1, 1]) b.box(0.18, 0.008, 0.03, { mat: 'plain', color: '#1a1a1a', x: 0.88, y: 1.09, z: -0.12 + s * 0.035 })
  b.cyl(0.055, 0.055, 0.26, { mat: 'plain', color: '#d8d4cc', x: 0.38, y: 1.05, z: -0.2, seg: 12 })
  b.cyl(0.006, 0.006, 0.3, { mat: 'wood', color: '#8a6a4a', x: 0.38, y: 1.06, z: -0.2, seg: 5 })
  b.cyl(0.06, 0.055, 0.15, { mat: 'gloss', color: pick(r, ['#d4d0c8', '#3a5a7a', '#a8603a']), x: -0.85, y: 0.995, z: -0.18, seg: 12 })
  for (let k = 0; k < 4; k++) b.cyl(0.006, 0.006, 0.24, { mat: k % 2 ? 'wood' : 'chrome', color: k % 2 ? '#a87a4a' : '#c8ccd0', x: -0.85 + (k - 1.5) * 0.02, y: 1.08, z: -0.18, rz: (k - 1.5) * 0.12, seg: 5 })
}
function steelOr(r, col) {
  return r() < 0.5 ? 'metal' : 'paint'
}

HC.wardrobe = (b, r) => {
  const col = pick(r, WOOD)
  const W = 1.5
  const D = 0.62
  const H = 2.0
  // plinth with bracket feet
  b.box(W - 0.02, 0.1, D - 0.02, { mat: 'wood', color: sh(col, -0.12), y: 0.07 })
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.08, 0.04, 0.08, { mat: 'wood', color: sh(col, -0.2), x: sx * (W / 2 - 0.06), y: 0.02, z: sz * (D / 2 - 0.06) })
  carcass(b, W, H - 0.12, D, { mat: 'wood', color: col, inside: sh(col, 0.08), y0: 0.12 })
  b.box(0.02, H - 0.2, D - 0.04, { mat: 'wood', color: col, x: 0.18, y: 1.06, z: -0.01 })
  // crown moulding: a stepped cornice along the front and sides
  for (const [y, o, h] of [[H + 0.01, 0.02, 0.03], [H + 0.04, 0.035, 0.03], [H + 0.065, 0.045, 0.025]]) {
    b.box(W + o * 2, h, D + o, { mat: 'wood', color: sh(col, -0.06), y, z: o / 2 })
  }
  // inside: rail with clothes on the left, shelves with folded clothes on the right
  b.cyl(0.012, 0.012, W / 2 + 0.12, { mat: 'chrome', color: '#a8acb0', x: -0.27, y: 1.78, rz: PI / 2, seg: 8 })
  let x = -W / 2 + 0.1
  while (x < 0.12) {
    garment(b, r, x, 1.79, 0.02)
    x += 0.07 + r() * 0.05
  }
  for (const y of [0.55, 1.0, 1.45]) {
    b.box(0.5, 0.02, D - 0.06, { mat: 'wood', color: sh(col, 0.06), x: 0.46, y, z: -0.01 })
    for (let k = 0; k < 2; k++) {
      let yy = y + 0.01
      for (let i = 0; i < 2 + Math.floor(r() * 3); i++) {
        const t = 0.035 + r() * 0.02
        b.box(0.2, t, 0.28, { mat: 'cloth', color: pick(r, ['#3a4a6a', '#8a3a3a', '#d4ccbc', '#4a4a4a', '#6a7a4a', '#c8a070', '#a85a6a']), x: 0.34 + k * 0.24 + (r() - 0.5) * 0.02, y: yy + t / 2, z: 0.0, r: 0.012, ry: (r() - 0.5) * 0.1 })
        yy += t
      }
    }
  }
  // shoes on the floor of the wardrobe
  for (let k = 0; k < 3; k++) {
    const c = pick(r, ['#2a2018', '#4a3020', '#1a1a1a', '#8a2a2a', '#d4d0c8'])
    for (const s of [-1, 1]) b.box(0.09, 0.07, 0.24, { mat: 'leather', color: c, x: -0.6 + k * 0.22 + s * 0.05, y: 0.165, z: 0.04, r: 0.03 })
  }
  b.box(0.36, 0.2, 0.3, { mat: 'plain', color: '#c8b898', x: 0.5, y: 1.78, z: -0.04, r: 0.01 })
  // raised-panel doors with escutcheons and turned knobs
  for (const s of [-1, 1])
    b.pivot(s < 0 ? 'doorL' : 'doorR', { x: s * (W / 2 - 0.004), y: 0, z: D / 2 + 0.003 }, (d) => {
      const dw = W / 2 - 0.01
      const cx = -s * (dw / 2)
      d.at({ x: cx, y: 0.67, z: 0.011 }, () => panelDoor(d, dw, 1.06, { mat: 'wood', color: col, raised: true, frame: 0.08 }))
      d.at({ x: cx, y: 1.55, z: 0.011 }, () => panelDoor(d, dw, 0.7, { mat: 'wood', color: col, raised: true, frame: 0.08 }))
      d.box(0.016, 0.03, 0.004, { mat: 'metal', color: '#b89a50', x: -s * (dw - 0.06), y: 1.08, z: 0.024 })
      pull(d, -s * (dw - 0.06), 1.16, 0.022, { kind: 'knob', mat: 'metal', color: '#b89a50' })
    })
}

HC.dresser = (b, r) => {
  const col = pick(r, WOOD)
  const W = 1.0
  const D = 0.5
  // turned bun feet
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.lathe([[0.03, 0], [0.04, 0.02], [0.035, 0.05], [0.04, 0.065], [0.001, 0.07]], { mat: 'wood', color: sh(col, -0.18), x: sx * (W / 2 - 0.06), z: sz * (D / 2 - 0.06), seg: 10 })
  carcass(b, W, 0.82, D, { mat: 'wood', color: col, inside: sh(col, 0.08), y0: 0.07, noTop: true })
  b.box(W + 0.05, 0.03, D + 0.04, { mat: 'wood', color: sh(col, -0.06), y: 0.905, z: 0.01 })
  b.box(W + 0.03, 0.015, D + 0.03, { mat: 'wood', color: sh(col, -0.12), y: 0.885, z: 0.01 })
  b.box(W - 0.02, 0.03, D, { mat: 'wood', color: sh(col, -0.12), y: 0.085 })
  // two half-width drawers on top, three full-width below
  const rows = [[0.68, 0.16, 2], [0.5, 0.16, 1], [0.32, 0.17, 1], [0.15, 0.13, 1]]
  let n = 0
  for (const [y, h, cols] of rows) {
    for (let c = 0; c < cols; c++) {
      const w = (W - 0.06) / cols - (cols > 1 ? 0.01 : 0)
      const x = cols > 1 ? (c - 0.5) * (w + 0.01) : 0
      b.pivot('drawer' + n++, { x, y, z: D / 2 + 0.002 }, (d) => {
        drawerBox(d, w, h, D - 0.04, {
          mat: 'wood',
          color: sh(col, 0.04),
          inside: '#c8b498',
          fill: (fw, fd, y0, zc) => {
            for (let k = 0; k < 3; k++) d.box(fw / 3 - 0.01, 0.03, fd * 0.8, { mat: 'cloth', color: pick(r, ['#d4d0c8', '#3a4a6a', '#a85a6a', '#6a6a6a', '#c8a070']), x: -fw / 2 + fw / 6 + (k * fw) / 3, y: y0 + 0.016, z: zc, r: 0.012 })
          },
        })
        d.box(w - 0.03, h - 0.03, 0.004, { mat: 'wood', color: sh(col, -0.05), z: 0.011 })
        if (cols > 1) pull(d, 0, 0, 0.012, { kind: 'bail', mat: 'metal', color: '#b89a50' })
        else for (const s of [-1, 1]) pull(d, s * w * 0.28, 0, 0.012, { kind: 'bail', mat: 'metal', color: '#b89a50' })
      })
    }
  }
  // a mirror on the wall above, a lamp, photos, perfume and a jewellery box
  b.at({ y: 1.38, z: -D / 2 + 0.015 }, () => {
    b.box(0.72, 0.56, 0.03, { mat: 'wood', color: sh(col, -0.15), r: 0.008 })
    b.box(0.64, 0.48, 0.006, { mat: 'chrome', color: '#cfd8dc', z: 0.016 })
  })
  b.at({ x: 0.34, y: 0.92 }, () => {
    b.lathe([[0.07, 0], [0.075, 0.03], [0.05, 0.08], [0.06, 0.16], [0.03, 0.24], [0.012, 0.26]], { mat: 'gloss', color: pick(r, ['#3a5a7a', '#c8b898', '#7a3a2a']), seg: 12 })
    b.cyl(0.006, 0.006, 0.1, { mat: 'chrome', color: '#c8ccd0', y: 0.3, seg: 5 })
    b.cyl(0.09, 0.15, 0.18, { mat: 'cloth', color: '#d4c8ac', y: 0.42, seg: 14, open: true })
    b.sphere(0.03, { mat: 'nightGlow', color: '#fff0d0', y: 0.34, ws: 8, hs: 6 })
  })
  for (let k = 0; k < 2; k++) {
    b.at({ x: -0.32 + k * 0.17, y: 0.92, z: -0.12 + k * 0.05, ry: (k - 0.5) * 0.4 }, () => {
      b.box(0.15, 0.2, 0.015, { mat: 'wood', color: '#2a1e16', y: 0.1, rx: -0.12 })
      b.box(0.12, 0.16, 0.003, { material: posterMat(Math.floor(r() * 9) + 20), y: 0.1, z: 0.009, rx: -0.12 })
    })
  }
  for (let k = 0; k < 3; k++) b.lathe([[0.022, 0], [0.026, 0.05], [0.012, 0.07], [0.008, 0.09]], { mat: 'glass', color: pick(r, ['#e8b8c8', '#d8c8a0', '#b8d8e8']), x: -0.05 + k * 0.05, y: 0.92, z: 0.08, seg: 8 })
  b.box(0.16, 0.08, 0.1, { mat: 'wood', color: '#5a2a2a', x: 0.1, y: 0.96, z: -0.1, r: 0.01 })
}

HC.desk = (b, r) => {
  const metal = r() < 0.3
  const col = metal ? pick(r, ['#6a6e72', '#8a8e84', '#5a6a72']) : pick(r, ['#a88460', '#c8a880', '#4a3a2e', '#d8c8a8'])
  const m = metal ? 'paint' : 'wood'
  const W = 1.6
  const D = 0.75
  // top with a dark edge band
  b.box(W, 0.032, D, { mat: metal ? 'paint' : 'wood', color: metal ? '#c8c0ac' : col, y: 0.745 })
  b.box(W + 0.004, 0.034, 0.006, { mat: 'plastic', color: '#2a2622', y: 0.745, z: D / 2 })
  // pedestal (right) with three drawers, panel leg (left), modesty panel
  b.at({ x: 0.57 }, () => carcass(b, 0.42, 0.72, D - 0.04, { mat: m, color: col, inside: sh(col, 0.06), y0: 0.01, noTop: true }))
  b.box(0.035, 0.72, D - 0.06, { mat: m, color: col, x: -W / 2 + 0.03, y: 0.37 })
  b.box(W - 0.5, 0.42, 0.018, { mat: m, color: sh(col, -0.04), x: -0.2, y: 0.52, z: -D / 2 + 0.06 })
  for (let i = 0; i < 3; i++) {
    const h = i === 0 ? 0.3 : 0.17
    const y = i === 0 ? 0.18 : i === 1 ? 0.43 : 0.62
    b.pivot('drawer' + i, { x: 0.57, y, z: D / 2 - 0.01 }, (d) => {
      drawerBox(d, 0.39, h - 0.012, D - 0.08, {
        mat: m,
        color: sh(col, 0.03),
        inside: '#b8a888',
        fill: (w, dd, y0, zc) => {
          if (i === 0) for (let k = 0; k < 6; k++) d.box(0.012, 0.22, 0.3, { mat: 'plain', color: pick(r, ['#d8c890', '#a8c0d8', '#e8a090', '#e8e4d8']), x: -w / 2 + 0.04 + k * 0.055, y: y0 + 0.11, z: zc })
          else for (let k = 0; k < 3; k++) d.box(0.21, 0.004 + k * 0.004, 0.29, { mat: 'plain', color: '#d6d2c6', y: y0 + 0.005 + k * 0.006, z: zc, ry: (r() - 0.5) * 0.3 })
        },
      })
      pull(d, 0, i === 0 ? 0.08 : 0.02, 0.012, { kind: metal ? 'slot' : 'bar', len: 0.11 })
    })
  }
  // monitor, keyboard, mouse, phone, lamp, papers, mug, plant
  const y0 = 0.762
  b.at({ x: -0.25, y: y0, z: -0.16 }, () => {
    b.box(0.22, 0.012, 0.16, { mat: 'plastic', color: '#2a2c2e', y: 0.006, r: 0.004 })
    b.box(0.04, 0.24, 0.03, { mat: 'plastic', color: '#2a2c2e', y: 0.13, z: -0.03 })
    b.box(0.58, 0.36, 0.03, { mat: 'plastic', color: '#1e2022', y: 0.42, z: -0.01, r: 0.006 })
    b.box(0.55, 0.32, 0.004, { mat: 'mapGlass', color: '#141a20', y: 0.425, z: 0.007 })
  })
  b.at({ x: -0.25, y: y0, z: 0.13 }, () => {
    b.box(0.44, 0.022, 0.15, { mat: 'plastic', color: '#2a2c2e', y: 0.011, r: 0.005 })
    for (let rr = 0; rr < 4; rr++) b.box(0.4, 0.006, 0.026, { mat: 'plastic', color: '#3e4044', y: 0.025, z: -0.05 + rr * 0.034 })
    b.box(0.2, 0.002, 0.2, { mat: 'cloth', color: '#2a3a5a', x: 0.36, y: 0.001 })
    b.box(0.06, 0.03, 0.1, { mat: 'plastic', color: '#2a2c2e', x: 0.36, y: 0.016, r: 0.012 })
  })
  b.at({ x: 0.45, y: y0, z: 0.0 }, () => {
    for (let k = 0; k < 4; k++) b.box(0.215, 0.003, 0.28, { mat: 'plain', color: k === 3 ? '#e8e0a8' : '#d8d4ca', x: (r() - 0.5) * 0.03, y: 0.002 + k * 0.003, z: (r() - 0.5) * 0.03, ry: (r() - 0.5) * 0.4 })
    b.box(0.08, 0.3, 0.26, { mat: 'plastic', color: pick(r, ['#2a4a8a', '#c83a2a', '#3a3a3a']), x: 0.24, y: 0.15, z: -0.14, r: 0.006 })
    b.box(0.08, 0.3, 0.26, { mat: 'plastic', color: pick(r, ['#2a8a4a', '#e8c020', '#3a3a3a']), x: 0.32, y: 0.15, z: -0.14, rz: 0.12, r: 0.006 })
  })
  mug(b, 0.12, y0, 0.18, pick(r, ['#c83a2a', '#d4d0c8', '#2a4a8a', '#3a3a3a']), 0.4)
  b.cyl(0.035, 0.035, 0.1, { mat: 'plastic', color: '#2a2a2a', x: 0.2, y: y0 + 0.05, z: -0.2, seg: 10 })
  for (let k = 0; k < 4; k++) b.cyl(0.004, 0.004, 0.14, { mat: 'plastic', color: pick(r, ['#2a4a8a', '#c83a2a', '#1a1a1a', '#e8c020']), x: 0.2 + (k - 1.5) * 0.01, y: y0 + 0.12, z: -0.2, rz: (k - 1.5) * 0.1, seg: 5 })
  b.at({ x: 0.68, y: y0, z: -0.2 }, () => {
    b.cyl(0.05, 0.06, 0.08, { mat: 'plain', color: '#a8603a', y: 0.04, seg: 10 })
    for (let k = 0; k < 5; k++) b.ico(0.03, { mat: 'leaf', color: '#4a7a3a', x: (r() - 0.5) * 0.06, y: 0.1 + r() * 0.04, z: (r() - 0.5) * 0.06, detail: 0, noise: 0.3 })
  })
  // office chair: five-star base, gas lift, seat and back
  b.at({ x: -0.2, z: D / 2 + 0.25, ry: (r() - 0.5) * 0.8 }, () => {
    for (let k = 0; k < 5; k++) {
      const a = (k / 5) * TAU
      b.beam([0, 0.07, 0], [Math.cos(a) * 0.3, 0.05, Math.sin(a) * 0.3], 0.035, 0.03, { mat: 'plastic', color: '#1e1e20' })
      b.sphere(0.028, { mat: 'plastic', color: '#1a1a1a', x: Math.cos(a) * 0.3, y: 0.028, z: Math.sin(a) * 0.3, ws: 8, hs: 6 })
    }
    b.cyl(0.022, 0.026, 0.32, { mat: 'chrome', color: '#a8acb0', y: 0.24, seg: 8 })
    b.box(0.3, 0.04, 0.28, { mat: 'plastic', color: '#1e1e20', y: 0.41 })
    cushion(b, 0.5, 0.08, 0.48, { color: '#2a2c34', y: 0.47, r: 0.035 })
    b.box(0.06, 0.32, 0.03, { mat: 'plastic', color: '#1e1e20', y: 0.55, z: -0.25, rx: 0.1 })
    cushion(b, 0.46, 0.5, 0.07, { color: '#2a2c34', y: 0.86, z: -0.29, rx: 0.12, r: 0.03, welt: false })
    for (const s of [-1, 1]) {
      b.box(0.03, 0.2, 0.03, { mat: 'plastic', color: '#1e1e20', x: s * 0.23, y: 0.56, z: 0.0 })
      b.box(0.06, 0.03, 0.24, { mat: 'plastic', color: '#1e1e20', x: s * 0.23, y: 0.67, z: 0.0, r: 0.01 })
    }
  })
}

HC.filing = (b, r) => {
  const col = pick(r, ['#8a9094', '#b8b4a8', '#6a7a6a', '#a8a090', '#4a5258'])
  const W = 0.5
  const D = 0.62
  carcass(b, W, 1.32, D, { color: col, inside: sh(col, -0.1), t: 0.02 })
  b.box(W - 0.02, 0.05, 0.02, { mat: 'paint', color: sh(col, -0.25), y: 0.025, z: D / 2 - 0.01 })
  b.cyl(0.011, 0.011, 0.02, { mat: 'chrome', color: '#d0d4d8', x: 0.17, y: 1.28, z: D / 2 + 0.005, rx: PI / 2, seg: 8 })
  for (let k = 0; k < 4; k++) {
    b.pivot('drawer' + k, { x: 0, y: 0.2 + k * 0.315, z: D / 2 + 0.002 }, (d) => {
      drawerBox(d, W - 0.03, 0.3, D - 0.04, {
        color: sh(col, 0.05),
        inside: sh(col, -0.15),
        fill: (w, dd, y0, zc) => {
          // hanging folders with tabs
          for (let i = 0; i < 9; i++) {
            d.box(w - 0.02, 0.22, 0.004, { mat: 'plain', color: pick(r, ['#c8b070', '#d8c890', '#a8c0a0', '#c8a0a0']), y: y0 + 0.12, z: zc - dd / 2 + 0.04 + i * (dd / 10) })
            d.box(0.06, 0.03, 0.004, { mat: 'plain', color: '#e8e4d8', x: -w / 2 + 0.05 + (i % 4) * 0.1, y: y0 + 0.245, z: zc - dd / 2 + 0.04 + i * (dd / 10) })
          }
        },
      })
      pull(d, 0, 0.05, 0.011, { kind: 'slot', len: 0.16 })
      d.box(0.1, 0.045, 0.004, { mat: 'chrome', color: '#c0c4c8', y: 0.105, z: 0.012 })
      label(d, pick(r, ['A-F', 'G-M', 'N-S', 'T-Z', 'TAX', 'HR', 'MISC', 'PAYROLL']), 0.085, 0.032, { y: 0.105, z: 0.0145 })
    })
  }
  // dents and scuffs
  for (let k = 0; k < 2; k++) blob(b, 0.05 + r() * 0.06, 0.03 + r() * 0.04, { rnd: r, color: sh(col, -0.2), x: (r() - 0.5) * 0.3, y: 0.3 + r() * 0.8, z: D / 2 + 0.014, t: 0.001 })
  // on top: folders, an in-tray and a cactus
  b.box(0.3, 0.012, 0.24, { mat: 'plastic', color: '#2a2a2a', y: 1.33, z: 0.05 })
  for (let k = 0; k < 4; k++) b.box(0.24, 0.012, 0.31, { mat: 'plain', color: pick(r, ['#c8b070', '#a8c0d8', '#e8e4d8', '#d89070']), y: 1.345 + k * 0.012, z: 0.05, ry: (r() - 0.5) * 0.2 })
  b.cyl(0.04, 0.035, 0.07, { mat: 'plain', color: '#a8603a', x: -0.15, y: 1.355, z: -0.15, seg: 10 })
  b.capsule(0.022, 0.06, { mat: 'leaf', color: '#4a7a3a', x: -0.15, y: 1.43, z: -0.15, seg: 8 })
}

HC.bookshelf = (b, r) => {
  const col = pick(r, WOOD)
  const W = 1.6
  const H = 1.9
  const D = 0.34
  b.box(W, 0.08, D - 0.02, { mat: 'wood', color: sh(col, -0.1), y: 0.04 })
  carcass(b, W, H - 0.08, D, { mat: 'wood', color: col, inside: sh(col, -0.06), y0: 0.08 })
  b.box(W + 0.04, 0.035, D + 0.03, { mat: 'wood', color: sh(col, -0.08), y: H + 0.017, z: 0.012 })
  b.box(0.02, H - 0.12, D - 0.02, { mat: 'wood', color: col, y: (H + 0.08) / 2 })
  // face frame
  for (const s of [-1, 1]) b.box(0.04, H - 0.08, 0.02, { mat: 'wood', color: sh(col, 0.04), x: s * (W / 2 - 0.02), y: (H + 0.08) / 2, z: D / 2 })
  for (let k = 0; k < 4; k++) {
    const y = 0.098 + k * 0.44
    if (k) b.box(W - 0.04, 0.022, D - 0.03, { mat: 'wood', color: col, y, z: -0.005 })
    for (const side of [-1, 1]) {
      const x0 = side < 0 ? -W / 2 + 0.04 : 0.02
      const x1 = side < 0 ? -0.02 : W / 2 - 0.04
      if (r() < 0.18) {
        // an ornament instead of books
        const ox = (x0 + x1) / 2
        const kk = r()
        if (kk < 0.35) {
          b.box(0.14, 0.18, 0.012, { mat: 'wood', color: '#2a1e16', x: ox, y: y + 0.1, z: -0.06, rx: -0.12 })
          b.box(0.11, 0.14, 0.003, { material: posterMat(Math.floor(r() * 9) + 40), x: ox, y: y + 0.1, z: -0.052, rx: -0.12 })
        } else if (kk < 0.7) {
          b.cyl(0.06, 0.05, 0.1, { mat: 'plain', color: '#a8603a', x: ox, y: y + 0.06, seg: 10 })
          for (let i = 0; i < 6; i++) b.ico(0.04, { mat: 'leaf', color: '#4a7a3a', x: ox + (r() - 0.5) * 0.1, y: y + 0.14 + r() * 0.06, z: (r() - 0.5) * 0.08, detail: 0, noise: 0.3 })
        } else {
          for (let i = 0; i < 3; i++) b.box(0.1, 0.24, 0.24, { mat: 'paint', color: pick(r, ['#d4d0c8', '#2a2a2a', '#3a5a7a']), x: ox - 0.1 + i * 0.11, y: y + 0.13, z: -0.03 })
        }
        continue
      }
      booksRow(b, r, x0, x1, y + 0.012, -D / 2 + 0.03, D - 0.06, 0.4)
    }
  }
}

HC.trash = (b, r) => {
  const steel = r() < 0.6
  const col = steel ? '#b8bcbe' : pick(r, ['#3e4a3c', '#2a4a7a', '#8a8a82', '#d4d0c8'])
  const m = steel ? 'metal' : 'plastic'
  b.cyl(0.22, 0.21, 0.04, { mat: 'plastic', color: '#1a1a1a', y: 0.02, seg: 18 })
  b.cyl(0.2, 0.19, 0.62, { mat: m, color: col, y: 0.34, seg: 18, open: true })
  b.cyl(0.185, 0.18, 0.6, { mat: 'plastic', color: '#1a1a1a', y: 0.35, seg: 14 })
  b.torus(0.2, 0.008, { mat: m, color: sh(col, -0.08), y: 0.65, rx: PI / 2, rs: 4, ts2: 20 })
  // liner bag folded over the rim
  b.torus(0.2, 0.012, { mat: 'gloss', color: '#2a2a2c', y: 0.645, rx: PI / 2, rs: 5, ts2: 18 })
  // pedal and hinge
  b.box(0.12, 0.02, 0.08, { mat: 'chrome', color: '#a8acb0', y: 0.04, z: 0.23 })
  b.box(0.08, 0.03, 0.03, { mat: m, color: sh(col, -0.15), y: 0.66, z: -0.2 })
  b.pivot('door', { x: 0, y: 0.665, z: -0.2 }, (d) => {
    d.cyl(0.205, 0.205, 0.03, { mat: m, color: col, z: 0.2, y: 0.015, seg: 18 })
    d.cyl(0.19, 0.2, 0.02, { mat: m, color: sh(col, 0.04), z: 0.2, y: 0.035, seg: 18 })
  })
  // contents and litter around it
  for (let k = 0; k < 4; k++) b.ico(0.06 + r() * 0.04, { mat: 'plain', color: pick(r, ['#c8c4b8', '#b8a888', '#c0a888']), x: (r() - 0.5) * 0.18, y: 0.6 + r() * 0.04, z: (r() - 0.5) * 0.18, detail: 0, noise: 0.5 })
  litter(b, r, 3, -0.35, 0.35, 0.1, 0.35)
}

HC.shelf = (b, r) => {
  const W = 1.8
  const H = 1.75
  const D = 0.62
  const col = pick(r, ['#d4d4ce', '#d8dcd8', '#3a3c3e', '#c8ccc8'])
  // base deck with a kick plate, uprights with slots, perforated back
  b.box(W, 0.14, D, { mat: 'paint', color: col, y: 0.07 })
  b.box(W, 0.1, 0.012, { mat: 'paint', color: sh(col, -0.2), y: 0.05, z: D / 2 + 0.004 })
  for (const s of [-1, 1]) {
    b.box(0.05, H, 0.05, { mat: 'paint', color: sh(col, -0.05), x: s * (W / 2 - 0.025), y: H / 2, z: -D / 2 + 0.05 })
    for (let k = 0; k < 30; k++) b.box(0.012, 0.025, 0.003, { mat: 'plain', color: '#1a1a1a', x: s * (W / 2 - 0.025), y: 0.2 + k * 0.05, z: -D / 2 + 0.077 })
    b.box(0.03, 0.12, D - 0.02, { mat: 'paint', color: col, x: s * (W / 2 - 0.015), y: 0.08 })
  }
  b.box(W - 0.1, H - 0.14, 0.02, { mat: 'paint', color: sh(col, -0.04), y: H / 2 + 0.07, z: -D / 2 + 0.04 })
  // the back of the gondola faces the next aisle: pegboard with bagged goods on hooks
  const perf = canvasMat('perf', [64, 64], (g, w, h) => {
    g.fillStyle = '#8a8a86'
    g.fillRect(0, 0, w, h)
    g.fillStyle = '#2a2a2a'
    for (let x = 8; x < w; x += 16) for (let y = 8; y < h; y += 16) g.fillRect(x - 2, y - 2, 4, 4)
  })
  decal(b, perf, W - 0.12, H - 0.18, { y: H / 2 + 0.07, z: -D / 2 + 0.051, repeat: 0.12 })
  decal(b, perf, W - 0.12, H - 0.18, { y: H / 2 + 0.07, z: -D / 2 + 0.029, ry: PI, repeat: 0.12 })
  for (let k = 0; k < 10; k++) {
    if (r() < 0.25) continue
    const hx = -W / 2 + 0.2 + (k % 5) * 0.35
    const hy = 0.65 + Math.floor(k / 5) * 0.55
    b.cyl(0.003, 0.003, 0.14, { mat: 'chrome', color: '#b8bcc0', x: hx, y: hy + 0.1, z: -D / 2 - 0.04, rx: PI / 2, seg: 4 })
    for (let i = 0; i < 2; i++) {
      const sd = Math.floor(r() * 12)
      b.box(0.15, 0.22, 0.04, { mat: 'plain', color: ['#c8362a', '#d8a020', '#2a62a8', '#36903e', '#d8621e', '#70368e'][sd % 6], x: hx, y: hy - 0.02, z: -D / 2 - 0.035 - i * 0.045, r: 0.01 })
      b.plane(0.14, 0.2, { material: packMat(sd), x: hx, y: hy - 0.02, z: -D / 2 - 0.058 - i * 0.045, ry: PI })
    }
  }
  const cats = ['CANNED', 'CEREAL', 'SNACKS', 'PASTA', 'HOUSEHOLD', 'PET FOOD', 'BAKING', 'DRINKS']
  // header card
  const hc = pick(r, ['#c83a2a', '#2a5ab8', '#2a8a4a', '#e8a020'])
  b.box(0.8, 0.18, 0.016, { mat: 'paint', color: hc, y: H + 0.12, z: -D / 2 + 0.06 })
  label(b, pick(r, cats), 0.78, 0.16, { bg: hc, fg: '#ffffff', y: H + 0.12, z: -D / 2 + 0.069 })
  b.box(0.02, 0.14, 0.02, { mat: 'chrome', color: '#a8acb0', y: H + 0.0, z: -D / 2 + 0.06 })
  for (let k = 0; k < 4; k++) {
    const y = 0.14 + k * 0.4
    if (k) {
      b.box(W - 0.1, 0.022, D - 0.1, { mat: 'paint', color: col, y, z: 0.0 })
      for (const s of [-1, 1]) b.beam([s * (W / 2 - 0.06), y - 0.01, -D / 2 + 0.06], [s * (W / 2 - 0.06), y - 0.12, -D / 2 + 0.12], 0.02, 0.012, { mat: 'paint', color: sh(col, -0.1) })
    }
    // price channel with tags
    b.box(W - 0.1, 0.04, 0.012, { mat: 'plastic', color: k === 0 ? '#c83a2a' : '#d4d0c8', y: y - 0.004, z: D / 2 - 0.05 })
    for (let t = 0; t < 6; t++) if (r() < 0.8) b.box(0.07, 0.028, 0.003, { mat: 'plain', color: pick(r, ['#d8d4c4', '#d8c040', '#d8d4c4']), x: -W / 2 + 0.15 + t * 0.3, y: y - 0.004, z: D / 2 - 0.042 })
    const kind = pick(r, ['cans', 'packs', 'jars', 'bottles', 'packs', 'boxes'])
    if (kind === 'packs') {
      // printed cereal / detergent boxes, faced up
      let x = -W / 2 + 0.1
      while (x < W / 2 - 0.16) {
        if (r() < 0.15) {
          x += 0.15
          continue
        }
        const bw = 0.16 + r() * 0.06
        const bh = 0.22 + r() * 0.1
        const sd = Math.floor(r() * 12)
        for (let dd = 0; dd < 2; dd++) pack(b, bw, bh, 0.07, { seed: sd, x: x + bw / 2, y: y + 0.011, z: 0.14 - dd * 0.12 })
        x += bw + 0.012
      }
    } else b.at({ z: 0.03 }, () => shelfItems(b, r, kind, W - 0.14, D - 0.2, y + 0.011, 0.3, 0.8))
  }
}

HC.register = (b, r) => {
  const brand = pick(r, ['#c83a2a', '#2a5ab8', '#2a8a4a', '#e8a020'])
  const W = 0.9
  const D = 0.62
  b.box(W, 0.06, D - 0.06, { mat: 'plain', color: '#1a1a1a', y: 0.03 })
  b.box(W, 0.86, D, { mat: 'paint', color: '#d8d0c0', y: 0.49 })
  b.box(W + 0.002, 0.12, D + 0.002, { mat: 'paint', color: brand, y: 0.78 })
  b.box(W + 0.04, 0.035, D + 0.04, { mat: 'concrete', color: '#3a3a3a', y: 0.94 })
  b.box(W + 0.044, 0.012, D + 0.044, { mat: 'chrome', color: '#b8bcc0', y: 0.925 })
  // scanner window and belt end
  b.box(0.18, 0.004, 0.14, { mat: 'mapGlass', color: '#2a1e1a', x: 0.22, y: 0.959, z: 0.12 })
  b.box(0.3, 0.012, 0.42, { mat: 'rubber', color: '#1e1e1e', x: -0.24, y: 0.962, z: 0.06 })
  b.cyl(0.02, 0.02, 0.3, { mat: 'chrome', color: '#a8acb0', x: -0.24, y: 0.962, z: 0.27, rz: PI / 2, seg: 8 })
  // POS: pole display, terminal, keypad, card reader, receipt printer
  b.at({ x: 0.0, y: 0.957, z: -0.16 }, () => {
    b.box(0.34, 0.06, 0.26, { mat: 'plastic', color: '#2a2c2e', y: 0.03, r: 0.01 })
    b.box(0.3, 0.02, 0.12, { mat: 'plastic', color: '#3e4044', y: 0.065, z: 0.05 })
    for (let k = 0; k < 12; k++) b.box(0.03, 0.006, 0.022, { mat: 'plastic', color: '#d8d8d4', x: -0.1 + (k % 6) * 0.04, y: 0.077, z: 0.03 + Math.floor(k / 6) * 0.035 })
    b.cyl(0.018, 0.018, 0.2, { mat: 'chrome', color: '#a8acb0', y: 0.16, z: -0.08, seg: 8 })
    b.box(0.3, 0.2, 0.04, { mat: 'plastic', color: '#1e2022', y: 0.32, z: -0.07, rx: -0.25, r: 0.008 })
    b.box(0.26, 0.15, 0.004, { material: screenMat('blue'), y: 0.32, z: -0.048, rx: -0.25 })
  })
  b.box(0.1, 0.06, 0.14, { mat: 'plastic', color: '#2a2c2e', x: 0.3, y: 0.99, z: -0.12, r: 0.01 })
  b.box(0.06, 0.08, 0.004, { mat: 'plain', color: '#d6d2c6', x: 0.3, y: 1.03, z: -0.05 })
  b.box(0.08, 0.025, 0.12, { mat: 'plastic', color: '#1a1a1a', x: -0.32, y: 0.97, z: -0.18, rx: 0.3 })
  // cash drawer
  b.pivot('drawer0', { x: 0, y: 0.84, z: D / 2 - 0.02 }, (d) => {
    d.box(0.44, 0.1, 0.03, { mat: 'plastic', color: '#2a2c2e', y: 0, r: 0.004 })
    d.box(0.42, 0.08, 0.36, { mat: 'plastic', color: '#1e2022', y: -0.005, z: -0.18 })
    for (let k = 0; k < 4; k++) {
      d.box(0.08, 0.005, 0.15, { mat: 'plain', color: pick(r, ['#8aa070', '#9ab080', '#7a9a6a']), x: -0.15 + k * 0.1, y: 0.03, z: -0.12 })
      d.cyl(0.03, 0.03, 0.02, { mat: 'metal', color: k % 2 ? '#c8a060' : '#b8bcc0', x: -0.15 + k * 0.1, y: 0.03, z: -0.27, seg: 10 })
    }
  })
  // impulse rack: candy, gum, batteries
  b.at({ x: W / 2 + 0.12, y: 0.0, z: 0.05 }, () => {
    b.box(0.03, 1.5, 0.03, { mat: 'chrome', color: '#a8acb0', y: 0.75, z: -0.1 })
    for (let k = 0; k < 5; k++) {
      const y = 0.3 + k * 0.25
      b.box(0.24, 0.012, 0.18, { mat: 'chrome', color: '#a8acb0', y, z: 0.0 })
      for (let i = 0; i < 5; i++) pack(b, 0.035, 0.11, 0.05, { seed: k * 5 + i, x: -0.09 + i * 0.045, y: y + 0.006, z: 0.0, rx: -0.12 })
    }
  })
  // plastic bags
  b.box(0.2, 0.25, 0.02, { mat: 'plastic', color: '#d4d4ce', x: -0.36, y: 0.6, z: D / 2 + 0.03 })
}

HC.toolrack = (b, r) => {
  heavyBench(b, { w: 1.7, d: 0.62, h: 0.88, z: 0.03, seed: Math.floor(r() * 99), color: pick(r, ['#d8b888', '#c8a070', '#b89068']) })
  pegboard(b, { w: 1.6, h: 0.82, y: 1.0, z: -0.31, seed: Math.floor(r() * 99) })
  // shelf above the pegboard with cans and jars
  b.box(1.6, 0.025, 0.24, { mat: 'wood', color: '#b89a74', y: 1.88, z: -0.2 })
  for (const s of [-1, 1]) b.beam([s * 0.7, 1.87, -0.3], [s * 0.7, 1.76, -0.3], 0.03, 0.03, { mat: 'metal', color: '#5a5e62' })
  b.at({ z: -0.2 }, () => shelfItems(b, r, pick(r, ['jars', 'cans', 'tools']), 1.5, 0.18, 1.893, 0.25, 0.7))
  // bench top clutter, a vise and a red tool box
  benchClutter(b, -0.8, 0.4, -0.15, 0.18, 0.89, r)
  b.at({ x: 0.62, y: 0.89, z: 0.1 }, () => {
    b.box(0.08, 0.06, 0.16, { mat: 'paint', color: '#3a5a8a', y: 0.03 })
    b.box(0.14, 0.07, 0.05, { mat: 'paint', color: '#3a5a8a', y: 0.1, z: 0.04 })
    b.box(0.14, 0.07, 0.05, { mat: 'paint', color: '#3a5a8a', y: 0.1, z: -0.04 })
    b.cyl(0.008, 0.008, 0.18, { mat: 'chrome', color: '#c8ccd0', y: 0.1, z: 0.12, rz: PI / 2, seg: 6 })
  })
  b.box(0.4, 0.17, 0.2, { mat: 'paint', color: pick(r, ['#c8302a', '#2a4a8a']), x: 0.2, y: 0.975, z: -0.15, r: 0.02 })
  b.box(0.3, 0.02, 0.03, { mat: 'chrome', color: '#c8ccd0', x: 0.2, y: 1.07, z: -0.15 })
  // drawers under the bench top
  for (let i = 0; i < 2; i++)
    b.pivot('drawer' + i, { x: -0.45 + i * 0.9, y: 0.78, z: 0.32 }, (d) => {
      drawerBox(d, 0.5, 0.12, 0.5, {
        mat: 'wood',
        color: '#b89068',
        fill: (w, dd, y0, zc) => {
          for (let k = 0; k < 8; k++) d.cyl(0.012, 0.012, 0.01, { mat: 'metal', color: '#8a8e92', x: (r() - 0.5) * w, y: y0 + 0.005, z: zc + (r() - 0.5) * dd * 0.6, seg: 6 })
        },
      })
      pull(d, 0, 0.0, 0.01, { kind: 'bar', len: 0.12, mat: 'metal', color: '#3a3a3a' })
    })
  // parts bins on the floor and a jerrycan
  partsBins(b, { cols: 3, rows: 2, x: -0.5, y: 0.08, z: -0.1, seed: Math.floor(r() * 99) })
}

HC.medcab = (b, r) => {
  const col = pick(r, ['#d4d4ce', '#d8d0c0', '#3a4a5a', '#8a9a8a'])
  const W = 0.8
  const D = 0.48
  b.box(W - 0.06, 0.08, 0.02, { mat: 'plain', color: '#2a2622', y: 0.04, z: D / 2 - 0.06 })
  carcass(b, W, 0.74, D, { color: col, inside: '#d4d0c8', y0: 0.08, noTop: true })
  b.cyl(0.02, 0.02, 0.4, { mat: 'chrome', color: '#c8ccd0', x: 0.0, y: 0.5, z: -0.1, seg: 8 })
  b.tube([[0, 0.3, -0.1], [0, 0.22, -0.05], [0.0, 0.3, 0.02], [0.0, 0.62, 0.0]], 0.018, { mat: 'chrome', color: '#c8ccd0', seg: 6, tseg: 10 })
  for (let k = 0; k < 3; k++) bottle(b, 0.18 + k * 0.07, 0.1, 0.06, { h: 0.22, r: 0.035, mat: 'plastic', color: pick(r, ['#e8e040', '#40a8e8', '#d8d8d2', '#e85a3a']), cap: '#d4d0c8', seg: 7 })
  b.box(0.14, 0.1, 0.14, { mat: 'cloth', color: '#d4d0c8', x: -0.2, y: 0.15, z: 0.0, r: 0.03 })
  for (const s of [-1, 1])
    b.pivot(s < 0 ? 'doorL' : 'doorR', { x: s * (W / 2 - 0.003), y: 0, z: D / 2 + 0.002 }, (d) => {
      d.at({ x: -s * (W / 4 - 0.002), y: 0.45, z: 0.011 }, () => panelDoor(d, W / 2 - 0.008, 0.68, { color: col }))
      pull(d, -s * (W / 2 - 0.06), 0.7, 0.022, { kind: 'knob', color: '#c8ccd0' })
    })
  // basin set in a stone top, faucet, soap, toothbrushes
  b.box(W + 0.03, 0.035, D + 0.03, { mat: 'concrete', color: pick(r, ['#d4d0c8', '#c8c4bc', '#3a3a3c']), y: 0.835, z: 0.01 })
  b.lathe([[0.07, 0.0], [0.15, 0.025], [0.175, 0.07], [0.18, 0.13]], { mat: 'gloss', color: '#dcdcd6', y: 0.852, seg: 20, sz: 0.85 })
  b.lathe([[0.17, 0.13], [0.165, 0.075], [0.14, 0.035], [0.001, 0.025]], { mat: 'gloss', color: '#d4d4ce', y: 0.852, seg: 20, sz: 0.85 })
  b.cyl(0.014, 0.014, 0.004, { mat: 'chrome', color: '#a8acb0', y: 0.879, seg: 8 })
  b.at({ y: 0.852, z: -0.19 }, () => {
    b.cyl(0.02, 0.024, 0.26, { mat: 'chrome', color: '#d0d4d8', y: 0.13, seg: 8 })
    b.cyl(0.011, 0.011, 0.14, { mat: 'chrome', color: '#d0d4d8', y: 0.25, z: 0.06, rx: PI / 2, seg: 6 })
    for (const s of [-1, 1]) b.cyl(0.018, 0.018, 0.03, { mat: 'chrome', color: '#d0d4d8', x: s * 0.1, y: 0.015, seg: 8 })
  })
  b.cyl(0.03, 0.03, 0.09, { mat: 'glass', color: '#d8e8e8', x: 0.28, y: 0.897, z: -0.12, seg: 8 })
  for (let k = 0; k < 2; k++) b.cyl(0.004, 0.004, 0.17, { mat: 'plastic', color: pick(r, ['#2a8ae8', '#e84a8a', '#3ac84a']), x: 0.28 + (k - 0.5) * 0.012, y: 0.95, z: -0.12, rz: (k - 0.5) * 0.3, seg: 5 })
  bottle(b, -0.28, 0.852, -0.12, { h: 0.15, r: 0.03, mat: 'plastic', color: '#e8d8c0', cap: '#d0d4d8', seg: 7 })
  // mirrored medicine cabinet on the wall with pills inside
  b.at({ y: 1.45, z: -D / 2 + 0.08 }, () => {
    carcass(b, 0.6, 0.66, 0.14, { color: '#d4d4ce', inside: '#d8d8d2', y0: -0.33 })
    for (const y of [-0.12, 0.1]) {
      b.box(0.56, 0.008, 0.12, { mat: 'glass', color: '#e0ecec', y, z: 0 })
      for (let k = 0; k < 5; k++) b.cyl(0.02, 0.02, 0.07, { mat: 'plastic', color: pick(r, ['#e8a020', '#d4d0c8', '#c83a2a', '#3a7ab8', '#58c040']), x: -0.22 + k * 0.1, y: y + 0.04, z: 0.0, seg: 7 })
    }
  })
  b.pivot('door', { x: -0.3, y: 1.45, z: -D / 2 + 0.152 }, (d) => {
    d.box(0.6, 0.66, 0.022, { mat: 'paint', color: '#e0e0dc', x: 0.3, z: 0.011 })
    d.box(0.56, 0.62, 0.004, { mat: 'chrome', color: '#d4dde2', x: 0.3, z: 0.024 })
  })
  // towel ring with a towel
  b.torus(0.06, 0.006, { mat: 'chrome', color: '#c8ccd0', x: W / 2 + 0.1, y: 1.2, z: -D / 2 + 0.04, rs: 4, ts2: 14 })
  b.box(0.16, 0.36, 0.03, { mat: 'cloth', color: pick(r, ['#d4d0c8', '#3a5a7a', '#c8a080']), x: W / 2 + 0.1, y: 1.04, z: -D / 2 + 0.05, r: 0.012 })
}

HC.locker = (b, r) => {
  const col = pick(r, ['#5a6a7a', '#7a8a6a', '#8a8a82', '#4a5a6a', '#9a6a4a', '#3a5a8a'])
  const W = 0.9
  const D = 0.48
  const H = 1.9
  b.box(W, 0.1, D - 0.02, { mat: 'paint', color: sh(col, -0.25), y: 0.05 })
  carcass(b, W, H - 0.1, D, { color: col, inside: sh(col, -0.12), t: 0.015, y0: 0.1 })
  for (const x of [-0.15, 0.15]) b.box(0.012, H - 0.12, D - 0.02, { mat: 'paint', color: col, x, y: H / 2 + 0.05 })
  b.box(W + 0.02, 0.04, D + 0.02, { mat: 'paint', color: sh(col, -0.1), y: H + 0.02 })
  for (let k = 0; k < 3; k++) {
    const x = -0.3 + k * 0.3
    // inside each: a shelf, a coat, a bag or boots
    b.box(0.27, 0.012, D - 0.04, { mat: 'paint', color: sh(col, -0.08), x, y: 1.55 })
    const kk = r()
    if (kk < 0.5) garment(b, r, x, 1.48, 0.0, { len: 0.75 })
    if (kk > 0.3) b.box(0.22, 0.2, 0.3, { mat: 'cloth', color: pick(r, ['#2a3a5a', '#3a3a3a', '#8a2a2a']), x, y: 1.66, z: 0.0, r: 0.05 })
    if (r() < 0.6) for (const s of [-1, 1]) b.box(0.08, 0.12, 0.24, { mat: 'leather', color: '#2a2018', x: x + s * 0.05, y: 0.16, z: 0.02, r: 0.03 })
    b.pivot('door' + k, { x: x - 0.143, y: 0, z: D / 2 + 0.002 }, (d) => {
      d.box(0.286, H - 0.14, 0.018, { mat: 'paint', color: sh(col, 0.05), x: 0.143, y: H / 2 + 0.05, z: 0.009 })
      // louvres top and bottom
      for (const vy of [1.62, 0.3]) for (let v = 0; v < 5; v++) d.box(0.17, 0.012, 0.012, { mat: 'paint', color: sh(col, -0.25), x: 0.143, y: vy + v * 0.03, z: 0.02, rx: -0.5 })
      // number plate, handle, padlock
      label(d, String(10 + k + Math.floor(r() * 80)), 0.05, 0.028, { bg: '#d8d4c8', x: 0.143, y: 1.4, z: 0.0195 })
      d.box(0.03, 0.12, 0.02, { mat: 'chrome', color: '#c8ccd0', x: 0.24, y: 1.0, z: 0.026 })
      if (r() < 0.4) {
        d.box(0.035, 0.04, 0.015, { mat: 'metal', color: '#b8a050', x: 0.24, y: 0.93, z: 0.04 })
        d.torus(0.012, 0.003, { mat: 'chrome', color: '#c8ccd0', x: 0.24, y: 0.955, z: 0.04, rs: 4, ts2: 8, arc: PI })
      }
    })
  }
}

HC.gunlocker = (b, r) => {
  // a steel gun safe on the left, a glazed rifle cabinet on the right
  b.at({ x: -0.45 }, () => {
    const W = 0.84
    const D = 0.58
    carcass(b, W, 1.78, D, { color: '#2e3236', inside: '#5a2a2a', insideMat: 'cloth', t: 0.06, y0: 0.02 })
    b.box(W - 0.04, 0.02, D - 0.04, { mat: 'paint', color: '#2e3236', y: 0.01 })
    b.box(W - 0.12, 0.02, D - 0.12, { mat: 'cloth', color: '#5a2a2a', y: 1.35, z: 0.0 })
    // rifles standing in the rack, barrels up, side-on to the door
    for (let k = 0; k < 4; k++) b.at({ x: -0.25 + k * 0.16, y: 0.72, z: -0.02, ry: PI / 2 }, () => longGun(b, { kind: k % 2 ? 'ar' : 'rifle', rx: -PI / 2 + 0.08 }))
    for (let k = 0; k < 2; k++) ammoCan(b, { x: -0.18 + k * 0.32, y: 1.37, z: 0.0 })
    b.pivot('door', { x: -W / 2, y: 0, z: D / 2 + 0.004 }, (d) => {
      d.box(W, 1.74, 0.07, { mat: 'paint', color: '#34383c', x: W / 2, y: 0.91, z: 0.035, r: 0.02, seg: 2 })
      d.box(W - 0.1, 1.6, 0.02, { mat: 'cloth', color: '#5a2a2a', x: W / 2, y: 0.91, z: -0.012 })
      // pistols holstered on the door panel
      for (let k = 0; k < 3; k++) pistol(d, { x: W / 2 - 0.2 + k * 0.2, y: 1.3, z: -0.04, ry: PI, rz: PI / 2 })
      // five-spoke handle, dial, logo plate
      d.cyl(0.035, 0.035, 0.04, { mat: 'chrome', color: '#c8ccd0', x: W * 0.7, y: 1.0, z: 0.09, rx: PI / 2, seg: 12 })
      for (let k = 0; k < 5; k++) {
        const a = (k / 5) * TAU
        d.beam([W * 0.7, 1.0, 0.1], [W * 0.7 + Math.cos(a) * 0.15, 1.0 + Math.sin(a) * 0.15, 0.1], 0.016, 0.016, { mat: 'chrome', color: '#c8ccd0' })
        d.sphere(0.018, { mat: 'chrome', color: '#c8ccd0', x: W * 0.7 + Math.cos(a) * 0.15, y: 1.0 + Math.sin(a) * 0.15, z: 0.1, ws: 8, hs: 6 })
      }
      d.cyl(0.05, 0.05, 0.03, { mat: 'chrome', color: '#b8bcc0', x: W * 0.7, y: 1.3, z: 0.085, rx: PI / 2, seg: 18 })
      d.box(0.3, 0.06, 0.006, { mat: 'metal', color: '#b89a40', x: W / 2, y: 1.6, z: 0.072 })
    })
    for (const y of [0.4, 1.4]) b.cyl(0.02, 0.02, 0.12, { mat: 'chrome', color: '#a8acb0', x: -W / 2 - 0.005, y, z: D / 2 + 0.01, seg: 8 })
  })
  b.at({ x: 0.45, z: -0.08 }, () => {
    const W = 0.84
    const D = 0.42
    carcass(b, W, 1.66, D, { mat: 'wood', color: '#5a3a28', inside: '#3a2618', y0: 0.0 })
    b.box(W - 0.04, 0.3, D - 0.04, { mat: 'wood', color: '#4a2e1e', y: 0.18 })
    for (let k = 0; k < 4; k++) b.at({ x: -0.27 + k * 0.18, y: 0.92, z: -0.03, ry: PI / 2 }, () => longGun(b, { kind: pick(r, ['rifle', 'rifle', 'ar']), rx: -PI / 2 + 0.06 }))
    b.box(W - 0.06, 0.06, 0.08, { mat: 'wood', color: '#3a2618', y: 1.1, z: 0.0 })
    b.pivot('doorR', { x: W / 2 - 0.01, y: 0, z: D / 2 + 0.003 }, (d) => {
      d.at({ x: -W / 2 + 0.01, y: 0.98, z: 0.011 }, () => panelDoor(d, W - 0.02, 1.32, { mat: 'wood', color: '#5a3a28', glass: true, frame: 0.06 }))
      pull(d, -W + 0.06, 0.98, 0.022, { kind: 'knob', mat: 'metal', color: '#b89a50' })
    })
    for (let k = 0; k < 2; k++) b.box(0.32, 0.22, 0.03, { mat: 'wood', color: '#4a2e1e', x: (k - 0.5) * 0.4, y: 0.18, z: D / 2 + 0.01 })
  })
}

HC.safe = (b, r) => {
  const W = 0.68
  const D = 0.62
  const H = 0.86
  b.box(W, 0.06, D, { mat: 'plain', color: '#1a1a1a', y: 0.03 })
  carcass(b, W, H - 0.06, D, { color: '#3a3e42', inside: '#5a5e62', t: 0.08, y0: 0.06 })
  b.box(W - 0.18, 0.012, D - 0.18, { mat: 'paint', color: '#5a5e62', y: 0.45, z: 0.0 })
  // cash, gold and papers
  for (let k = 0; k < 4; k++) b.box(0.16, 0.035, 0.07, { mat: 'plain', color: '#8aa070', x: -0.12 + (k % 2) * 0.17, y: 0.165 + Math.floor(k / 2) * 0.036, z: 0.02 })
  for (let k = 0; k < 3; k++) b.box(0.09, 0.03, 0.045, { mat: 'metal', color: '#d8b040', x: 0.1 + (k % 2) * 0.05, y: 0.475 + Math.floor(k / 2) * 0.03, z: -0.02 })
  b.box(0.2, 0.05, 0.28, { mat: 'plain', color: '#d8d0b0', x: -0.1, y: 0.48, z: 0.0 })
  b.pivot('door', { x: -W / 2, y: 0, z: D / 2 + 0.003 }, (d) => {
    d.box(W, H - 0.08, 0.1, { mat: 'paint', color: '#44484c', x: W / 2, y: 0.47, z: 0.05, r: 0.02, seg: 2 })
    d.box(W - 0.1, H - 0.18, 0.012, { mat: 'paint', color: sh('#44484c', -0.1), x: W / 2, y: 0.47, z: 0.105 })
    // bolts on the door edge
    for (let k = 0; k < 3; k++) d.cyl(0.02, 0.02, 0.05, { mat: 'chrome', color: '#c8ccd0', x: W - 0.01, y: 0.25 + k * 0.22, z: 0.05, rz: PI / 2, seg: 8 })
    d.cyl(0.075, 0.075, 0.03, { mat: 'chrome', color: '#c8ccd0', x: W / 2, y: 0.55, z: 0.12, rx: PI / 2, seg: 20 })
    d.cyl(0.05, 0.06, 0.02, { mat: 'paint', color: '#1a1a1a', x: W / 2, y: 0.55, z: 0.14, rx: PI / 2, seg: 20 })
    d.box(0.2, 0.026, 0.03, { mat: 'chrome', color: '#c8ccd0', x: W / 2 + 0.06, y: 0.3, z: 0.13, r: 0.008 })
    d.box(0.28, 0.06, 0.004, { mat: 'metal', color: '#b89a40', x: W / 2, y: 0.78, z: 0.112 })
  })
  for (const y of [0.2, 0.7]) b.cyl(0.025, 0.025, 0.14, { mat: 'chrome', color: '#a8acb0', x: -W / 2 - 0.005, y, z: D / 2 + 0.01, seg: 8 })
}

HC.crate = (b, r) => {
  const col = pick(r, ['#c8a878', '#b89868', '#d8b888', '#a88a5a'])
  const W = 0.86
  const H = 0.6
  const D = 0.64
  // planked sides with gaps, corner battens, metal corners and straps
  const planks = (w, h, axis) => {
    const n = Math.max(3, Math.round(h / 0.11))
    for (let i = 0; i < n; i++) {
      const ph = h / n - 0.006
      const y = -h / 2 + (i + 0.5) * (h / n)
      if (axis === 'z') b.box(w, ph, 0.018, { mat: 'wood', color: sh(col, (r() - 0.5) * 0.12), y })
      else b.box(0.018, ph, w, { mat: 'wood', color: sh(col, (r() - 0.5) * 0.12), y })
    }
  }
  b.at({ y: H / 2 }, () => {
    b.box(W - 0.04, H - 0.04, D - 0.04, { mat: 'wood', color: sh(col, -0.35) })
    for (const s of [-1, 1]) {
      b.at({ z: (s * D) / 2 }, () => planks(W, H, 'z'))
      b.at({ x: (s * W) / 2 }, () => planks(D, H, 'x'))
    }
    for (let i = 0; i < 6; i++) b.box(W / 6 - 0.006, 0.018, D, { mat: 'wood', color: sh(col, (r() - 0.5) * 0.1), x: -W / 2 + (i + 0.5) * (W / 6), y: H / 2 })
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
      b.box(0.06, H + 0.004, 0.024, { mat: 'wood', color: sh(col, -0.1), x: sx * (W / 2 - 0.03), z: sz * (D / 2 + 0.004) })
      b.box(0.024, H + 0.004, 0.06, { mat: 'wood', color: sh(col, -0.1), x: sx * (W / 2 + 0.004), z: sz * (D / 2 - 0.03) })
    }
    for (const sx of [-1, 1]) b.box(0.03, H + 0.04, D + 0.04, { mat: 'metal', color: '#5a5e62', x: sx * W * 0.28 })
  })
  b.plane(0.5, 0.12, { material: stencilMat(pick(r, ['FRAGILE', 'THIS SIDE UP', 'MEDICAL', 'SUPPLIES', 'AID', 'PARTS']), '#2a1e14'), y: H * 0.55, z: D / 2 + 0.012, shadow: false })
  b.plane(0.22, 0.06, { material: stencilMat(pick(r, ['A-14', 'LOT 7', 'NO 3', 'C-22']), '#2a1e14'), x: 0.26, y: H * 0.25, z: D / 2 + 0.012, shadow: false })
  if (r() < 0.55) crateProp(b, { w: 0.6, h: 0.4, d: 0.48, y: H, ry: 0.15 + r() * 0.2, color: sh(col, 0.05) })
}

HC.pallet = (b, r) => {
  const w = 2.8
  const H = 2.6
  const D = 0.95
  const blue = '#2a5aa8'
  // uprights with teardrop slots, beams with safety clips, wire decks
  for (const x of [-w / 2, 0, w / 2]) {
    for (const z of [-D / 2 + 0.04, D / 2 - 0.04]) {
      b.box(0.08, H, 0.07, { mat: 'paint', color: blue, x, y: H / 2, z })
      for (let k = 0; k < 24; k++) b.box(0.018, 0.04, 0.003, { mat: 'plain', color: '#0e1a2e', x, y: 0.2 + k * 0.1, z: z + 0.037 })
      b.box(0.16, 0.012, 0.14, { mat: 'paint', color: blue, x, y: 0.006, z })
    }
    for (let k = 0; k < 5; k++) b.beam([x, 0.3 + k * 0.48, -D / 2 + 0.06], [x, 0.54 + k * 0.48, D / 2 - 0.06], 0.03, 0.02, { mat: 'paint', color: blue })
  }
  for (const y of [0.14, 1.3, 2.45]) {
    for (const z of [-D / 2 + 0.04, D / 2 - 0.04]) {
      b.box(w, 0.11, 0.05, { mat: 'paint', color: '#e88a2a', y, z })
      for (const x of [-w / 2 + 0.07, -0.07, 0.07, w / 2 - 0.07]) b.box(0.02, 0.04, 0.01, { mat: 'metal', color: '#c8b040', x, y: y + 0.02, z: z + (z > 0 ? 0.03 : -0.03) })
    }
    if (y > 0.2) for (let k = 0; k < 14; k++) b.box(0.012, 0.03, D - 0.1, { mat: 'metal', color: '#9a9ea2', x: -w / 2 + 0.1 + k * 0.2, y: y + 0.07 })
    for (const sx of [-1, 1]) {
      if (r() < 0.18) continue
      const px = (sx * w) / 4
      palletProp(b, { x: px, y: y + 0.055, color: '#c8a878' })
      const kind = r()
      const top = y + 0.2
      if (kind < 0.55) {
        // boxes stacked and stretch-wrapped
        const rows = 2 + Math.floor(r() * 2)
        for (let iy = 0; iy < rows; iy++) for (let ix = 0; ix < 3; ix++) for (let iz = 0; iz < 2; iz++) b.box(0.34, 0.25, 0.4, { mat: 'plain', color: pick(r, ['#b48c5a', '#c09a68', '#a8845a']), x: px - 0.36 + ix * 0.36, y: top + 0.125 + iy * 0.255, z: -0.21 + iz * 0.42 })
        b.box(1.12, rows * 0.255 + 0.02, 0.88, { mat: 'glass', color: '#e8f0f0', x: px, y: top + (rows * 0.255) / 2 })
        b.plane(0.3, 0.2, { material: plateMat(pick(r, ['FRAGILE', 'FOOD', 'WATER', 'PAPER', 'TOOLS']), '#d6d2c6', '#1a1a1a'), x: px, y: top + 0.15, z: 0.452, shadow: false })
      } else if (kind < 0.8) {
        for (let ix = 0; ix < 2; ix++) for (let iz = 0; iz < 2; iz++) barrel(b, { x: px - 0.27 + ix * 0.54, y: top - 0.05, z: -0.22 + iz * 0.44, color: pick(r, ['#2a5a8a', '#3a6a3a', '#8a3a2a']), r: 0.24, h: 0.74 })
      } else {
        // sacks
        for (let iy = 0; iy < 3; iy++) for (let ix = 0; ix < 2; ix++) b.box(0.5, 0.14, 0.8, { mat: 'canvas', color: pick(r, ['#d8ccaa', '#c8b890', '#e0d4b8']), x: px - 0.26 + ix * 0.52, y: top + 0.07 + iy * 0.14, r: 0.06 })
      }
    }
  }
  // a pallet jack parked in front
  b.at({ x: 0.3, z: D / 2 + 0.35, ry: 0.3 }, () => {
    for (const s of [-1, 1]) b.box(0.16, 0.06, 1.0, { mat: 'paint', color: '#c83a2a', x: s * 0.2, y: 0.05, z: 0.2 })
    b.box(0.6, 0.18, 0.16, { mat: 'paint', color: '#c83a2a', y: 0.14, z: -0.35 })
    b.beam([0, 0.2, -0.38], [0, 1.1, -0.55], 0.04, 0.04, { mat: 'paint', color: '#2a2a2a' })
    b.box(0.3, 0.05, 0.05, { mat: 'rubber', color: '#1a1a1a', y: 1.12, z: -0.56 })
  })
}

HC.milcrate = (b, r) => {
  const col = pick(r, ['#5a6248', '#4e5638', '#6a6a4a'])
  const W = 1.0
  const D = 0.6
  const H = 0.48
  carcass(b, W, H, D, { color: col, inside: sh(col, -0.15), noTop: true, t: 0.025 })
  // ribs, skids, handles, latches, stencils
  for (const x of [-0.3, 0, 0.3]) b.box(0.04, H, D + 0.02, { mat: 'paint', color: sh(col, -0.06), x, y: H / 2 })
  for (const s of [-1, 1]) b.box(W - 0.1, 0.03, 0.06, { mat: 'wood', color: '#4a3a2a', y: -0.015, z: s * (D / 2 - 0.08) })
  for (const s of [-1, 1]) {
    b.box(0.03, 0.05, 0.2, { mat: 'metal', color: '#3a3c3a', x: s * (W / 2 + 0.02), y: H * 0.7 })
    b.beam([s * (W / 2 + 0.04), H * 0.7, -0.09], [s * (W / 2 + 0.04), H * 0.7, 0.09], 0.02, 0.02, { mat: 'metal', color: '#3a3c3a' })
  }
  b.plane(0.5, 0.12, { material: stencilMat(pick(r, ['US ARMY', 'RATIONS', 'MEDICAL', '5.56 MM', 'SIGNAL']), '#e8e4c8'), y: H * 0.5, z: D / 2 + 0.013, shadow: false })
  b.plane(0.3, 0.07, { material: stencilMat(pick(r, ['LOT 0442', 'NSN 8970', 'QTY 12']), '#e8e4c8'), x: 0.3, y: H * 0.22, z: D / 2 + 0.013, shadow: false })
  for (const x of [-0.42, 0.42]) {
    b.box(0.07, 0.12, 0.03, { mat: 'metal', color: '#8a8e8a', x, y: H - 0.06, z: D / 2 + 0.015 })
    b.box(0.04, 0.03, 0.03, { mat: 'metal', color: '#6a6e6a', x, y: H - 0.12, z: D / 2 + 0.03 })
  }
  // contents: ammo cans, ration boxes, a radio
  for (let k = 0; k < 3; k++) ammoCan(b, { x: -0.3 + k * 0.3, y: 0.025, z: -0.12 })
  for (let k = 0; k < 3; k++) b.box(0.26, 0.08, 0.2, { mat: 'plain', color: '#8a7a52', x: -0.3 + k * 0.3, y: 0.23, z: 0.12, r: 0.006 })
  b.pivot('door', { x: 0, y: H, z: -D / 2 }, (d) => {
    d.box(W + 0.02, 0.06, D + 0.02, { mat: 'paint', color: sh(col, 0.04), z: D / 2, y: 0.03, r: 0.01 })
    for (const x of [-0.3, 0, 0.3]) d.box(0.04, 0.02, D + 0.02, { mat: 'paint', color: sh(col, -0.04), x, y: 0.07, z: D / 2 })
    d.plane(0.5, 0.12, { material: stencilMat('KEEP DRY', '#e8e4c8'), y: 0.081, z: D / 2, rx: -PI / 2, shadow: false })
  })
  if (r() < 0.45) {
    b.at({ y: H + 0.06, ry: 0.08 }, () => {
      b.box(0.9, 0.36, 0.5, { mat: 'paint', color: sh(col, -0.04), y: 0.18, r: 0.015 })
      b.plane(0.4, 0.1, { material: stencilMat('AMMUNITION', '#e8e4c8'), y: 0.2, z: 0.252, shadow: false })
    })
  }
}

HC.server = (b, r) => {
  const W = 0.62
  const D = 0.9
  const H = 2.0
  carcass(b, W, H, D, { color: '#1e2024', inside: '#141618', t: 0.03 })
  b.box(W - 0.02, 0.06, D - 0.04, { mat: 'paint', color: '#141618', y: 0.03 })
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.cyl(0.025, 0.025, 0.03, { mat: 'rubber', color: '#1a1a1a', x: sx * 0.25, y: 0.015, z: sz * 0.38, rz: PI / 2, seg: 8 })
  // rails with servers, drive bays and status LEDs
  let y = 0.12
  while (y < H - 0.3) {
    const u = pick(r, [1, 1, 2, 2, 4])
    const hh = u * 0.0445 - 0.003
    if (r() < 0.12) {
      b.box(W - 0.08, hh, 0.01, { mat: 'paint', color: '#2a2c30', y: y + hh / 2, z: D / 2 - 0.06 })
    } else {
      b.box(W - 0.08, hh, D - 0.16, { mat: 'metal', color: '#3a3e44', y: y + hh / 2, z: 0.0 })
      b.box(W - 0.08, hh, 0.012, { mat: 'plastic', color: '#24272c', y: y + hh / 2, z: D / 2 - 0.074 })
      const bays = u >= 2 ? 8 : 4
      for (let k = 0; k < bays; k++) b.box(0.05, Math.min(0.03, hh - 0.012), 0.004, { mat: 'plastic', color: '#3a3e44', x: -W / 2 + 0.1 + k * 0.055, y: y + hh / 2, z: D / 2 - 0.066 })
      for (let k = 0; k < 3; k++) if (r() < 0.7) b.box(0.008, 0.006, 0.004, { mat: pick(r, ['glowGreen', 'glowGreen', 'glowAmber', 'glowBlue']), color: '#ffffff', x: W / 2 - 0.08 - k * 0.014, y: y + hh / 2, z: D / 2 - 0.064 })
    }
    y += u * 0.0445
  }
  // patch panel with cables looping to the side
  const py = H - 0.2
  b.box(W - 0.08, 0.045, 0.02, { mat: 'paint', color: '#2a2c30', y: py, z: D / 2 - 0.07 })
  for (let k = 0; k < 10; k++) {
    const c = pick(r, ['#2a8ae8', '#e8c030', '#e84a3a', '#3ac84a', '#d4d4ce'])
    const x = -W / 2 + 0.08 + k * 0.045
    b.tube([[x, py, D / 2 - 0.06], [x, py - 0.04, D / 2 - 0.02], [W / 2 - 0.05, py - 0.1 - k * 0.01, D / 2 - 0.03], [W / 2 - 0.04, py - 0.4, D / 2 - 0.05]], 0.004, { mat: 'plastic', color: c, seg: 4, tseg: 10 })
  }
  // perforated door
  b.pivot('door', { x: -W / 2, y: 0, z: D / 2 + 0.004 }, (d) => {
    d.box(0.04, H - 0.04, 0.025, { mat: 'paint', color: '#1e2024', x: 0.02, y: H / 2 })
    d.box(0.04, H - 0.04, 0.025, { mat: 'paint', color: '#1e2024', x: W - 0.02, y: H / 2 })
    for (const yy of [0.04, H - 0.04]) d.box(W, 0.06, 0.025, { mat: 'paint', color: '#1e2024', x: W / 2, y: yy })
    decal(d, canvasMat('mesh', [64, 64], (g, w, h) => {
      g.clearRect(0, 0, w, h)
      g.fillStyle = '#1e2024'
      g.fillRect(0, 0, w, h)
      g.globalCompositeOperation = 'destination-out'
      for (let x = 4; x < w; x += 8) for (let yy = 4; yy < h; yy += 8) {
        g.beginPath()
        g.arc(x + ((yy / 8) % 2) * 4, yy, 2.6, 0, TAU)
        g.fill()
      }
    }, { alpha: true, cutout: true, side: THREE.DoubleSide }), W - 0.06, H - 0.1, { x: W / 2, y: H / 2, z: 0.0, repeat: 0.05 })
    d.box(0.025, 0.2, 0.03, { mat: 'chrome', color: '#a8acb0', x: W - 0.05, y: 1.0, z: 0.025 })
  })
}

HC.tv = (b, r) => {
  const col = pick(r, ['#5a3a28', '#2a2a2a', '#d8d0c0', '#8a6a4a'])
  const m = col === '#2a2a2a' || col === '#d8d0c0' ? 'paint' : 'wood'
  const W = 1.4
  const D = 0.42
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.cyl(0.018, 0.012, 0.12, { mat: 'wood', color: '#3a2a1e', x: sx * (W / 2 - 0.08), y: 0.06, z: sz * (D / 2 - 0.06), seg: 8 })
  carcass(b, W, 0.44, D, { mat: m, color: col, inside: sh(col, -0.15), y0: 0.12 })
  b.box(0.02, 0.4, D - 0.04, { mat: m, color: col, y: 0.34 })
  // console, discs and boxes inside
  b.box(0.3, 0.07, 0.25, { mat: 'plastic', color: '#1a1a1c', x: -0.32, y: 0.175, z: 0.0, r: 0.01 })
  b.box(0.01, 0.004, 0.004, { mat: 'glowBlue', color: '#ffffff', x: -0.2, y: 0.19, z: 0.127 })
  for (let k = 0; k < 10; k++) b.box(0.014, 0.19, 0.135, { mat: 'plastic', color: pick(r, ['#1a1a2a', '#2a4a8a', '#8a2a2a', '#d4d0c8']), x: 0.12 + k * 0.016, y: 0.235, z: -0.02 })
  for (const s of [-1, 1])
    b.pivot(s < 0 ? 'doorL' : 'doorR', { x: s * (W / 2 - 0.005), y: 0, z: D / 2 + 0.002 }, (d) => {
      d.at({ x: -s * (W / 4 - 0.004), y: 0.34, z: 0.011 }, () => panelDoor(d, W / 2 - 0.012, 0.4, { mat: m, color: col, frame: 0.04 }))
      pull(d, -s * (W / 2 - 0.06), 0.34, 0.022, { kind: 'bar', len: 0.1, vertical: true, color: '#2a2a2a', mat: 'metal' })
    })
  // flat screen on a stand, soundbar, remote, plant
  b.at({ y: 0.56, z: -0.06 }, () => {
    b.box(0.34, 0.012, 0.18, { mat: 'metal', color: '#2a2a2c', y: 0.006 })
    b.box(0.05, 0.12, 0.03, { mat: 'metal', color: '#2a2a2c', y: 0.07, z: -0.02 })
    b.box(1.12, 0.66, 0.04, { mat: 'plastic', color: '#121214', y: 0.47, r: 0.006 })
    b.box(1.09, 0.62, 0.004, { mat: 'mapGlass', color: '#0e1216', y: 0.475, z: 0.021 })
    b.box(0.02, 0.006, 0.004, { mat: 'glowRed', color: '#ffffff', x: 0.5, y: 0.15, z: 0.022 })
  })
  b.box(0.7, 0.07, 0.09, { mat: 'plastic', color: '#1a1a1c', y: 0.6, z: 0.12, r: 0.02 })
  b.box(0.05, 0.02, 0.17, { mat: 'plastic', color: '#2a2a2c', x: 0.45, y: 0.57, z: 0.1, ry: 0.3, r: 0.008 })
  b.at({ x: -0.6, y: 0.56, z: 0.04 }, () => {
    b.cyl(0.07, 0.06, 0.12, { mat: 'gloss', color: '#d4d0c8', y: 0.06, seg: 12 })
    for (let k = 0; k < 7; k++) b.ico(0.045, { mat: 'leaf', color: pick(r, ['#4a7a3a', '#5a8a3a']), x: (r() - 0.5) * 0.12, y: 0.16 + r() * 0.1, z: (r() - 0.5) * 0.12, detail: 0, noise: 0.3 })
  })
  // tower speakers either side
  for (const s of [-1, 1]) {
    b.box(0.2, 0.9, 0.24, { mat: 'wood', color: '#2a2422', x: s * (W / 2 + 0.16), y: 0.45, z: -0.05 })
    for (const yy of [0.62, 0.78]) b.cyl(0.06, 0.06, 0.01, { mat: 'rubber', color: '#141414', x: s * (W / 2 + 0.16), y: yy, z: 0.072, rx: PI / 2, seg: 14 })
    b.cyl(0.03, 0.03, 0.01, { mat: 'metal', color: '#3a3a3a', x: s * (W / 2 + 0.16), y: 0.88, z: 0.072, rx: PI / 2, seg: 10 })
  }
}

HC.chemshelf = (b, r) => {
  const W = 1.6
  const D = 0.45
  // lower flammables cupboard
  carcass(b, W, 0.86, D, { color: '#e8c020', inside: '#d8b020', noTop: false })
  b.plane(0.36, 0.12, { material: plateMat('FLAMMABLE', '#e8c020', '#c8201a', 'flame'), y: 0.62, z: D / 2 + 0.024, shadow: false })
  for (const s of [-1, 1])
    b.pivot(s < 0 ? 'doorL' : 'doorR', { x: s * (W / 2 - 0.004), y: 0, z: D / 2 + 0.002 }, (d) => {
      d.box(W / 2 - 0.01, 0.8, 0.02, { mat: 'paint', color: '#e8c020', x: -s * (W / 4 - 0.004), y: 0.43, z: 0.01 })
      pull(d, -s * (W / 2 - 0.08), 0.5, 0.02, { kind: 'bar', len: 0.12, vertical: true, color: '#2a2a2a', mat: 'metal' })
    })
  for (let k = 0; k < 3; k++) b.cyl(0.11, 0.11, 0.32, { mat: 'paint', color: pick(r, ['#c83a2a', '#2a5a8a', '#d4d0c8']), x: -0.5 + k * 0.5, y: 0.2, seg: 12 })
  // upper glazed reagent cabinet
  b.at({ y: 0.86, z: -0.04 }, () => {
    carcass(b, W, 1.02, D - 0.08, { color: '#d8dad4', inside: '#dcdcd6' })
    for (let k = 0; k < 2; k++) {
      const yy = 0.04 + k * 0.48
      if (k) b.box(W - 0.04, 0.016, D - 0.12, { mat: 'paint', color: '#d8dad4', y: yy })
      let x = -W / 2 + 0.08
      while (x < W / 2 - 0.1) {
        const kind = r()
        if (kind < 0.45) bottle(b, x, yy + 0.008, 0, { h: 0.2 + r() * 0.06, r: 0.035, color: pick(r, ['#7a4a1a', '#e8eef0', '#7a4a1a']), cap: pick(r, ['#1a1a1a', '#c83a2a', '#2a5ab8']), label: '#d6d2c6', seg: 8 })
        else if (kind < 0.75) {
          b.box(0.1, 0.24, 0.1, { mat: 'plastic', color: pick(r, ['#d4d0c8', '#d8e0e8']), x, y: yy + 0.13, z: 0.0, r: 0.012 })
          b.cyl(0.02, 0.02, 0.03, { mat: 'plastic', color: pick(r, ['#c83a2a', '#2a5ab8', '#e8c020']), x, y: yy + 0.26, seg: 6 })
          b.plane(0.06, 0.06, { material: diamondMat(pick(r, ['flame', 'toxic', 'corrosive'])), x, y: yy + 0.14, z: 0.051, shadow: false })
        } else jar(b, x, yy + 0.008, 0, pick(r, ['#58c040', '#e8d040', '#40a8e8', '#d4d0c8']), { h: 0.14, r: 0.045, lid: '#1a1a1a' })
        x += 0.1 + r() * 0.04
      }
    }
    for (const s of [-1, 1]) {
      b.at({ x: s * (W / 4 - 0.004), y: 0.51, z: (D - 0.08) / 2 + 0.012 }, () => panelDoor(b, W / 2 - 0.012, 0.98, { color: '#d8dad6', glass: true, frame: 0.04 }))
    }
    b.plane(0.24, 0.24, { material: diamondMat('toxic'), x: W / 2 - 0.2, y: 1.08, z: (D - 0.08) / 2, shadow: false })
  })
}

HC.toolchest = (b, r) => {
  const col = pick(r, ['#c8302a', '#2a4a8a', '#3a3a3a', '#d87a1a'])
  const W = 0.86
  const D = 0.48
  carcass(b, W, 0.9, D, { color: col, inside: '#2a2a2a', y0: 0.1 })
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
    b.cyl(0.045, 0.045, 0.035, { mat: 'rubber', color: '#1a1a1a', x: sx * 0.36, y: 0.045, z: sz * 0.17, rz: PI / 2, seg: 10 })
    b.box(0.06, 0.03, 0.06, { mat: 'metal', color: '#5a5e62', x: sx * 0.36, y: 0.09, z: sz * 0.17 })
  }
  b.cyl(0.012, 0.012, D - 0.06, { mat: 'chrome', color: '#c8ccd0', x: W / 2 + 0.05, y: 0.85, rx: PI / 2, seg: 8 })
  for (const s of [-1, 1]) b.box(0.05, 0.03, 0.03, { mat: 'chrome', color: '#c8ccd0', x: W / 2 + 0.025, y: 0.85, z: s * (D / 2 - 0.05) })
  const fills = [
    (d, w, dd, y0, zc) => {
      wrench(d, { x: -0.2, y: y0 + 0.005, z: zc, rx: -PI / 2 })
      wrench(d, { x: -0.1, y: y0 + 0.005, z: zc, rx: -PI / 2, s: 0.8 })
      screwdriver(d, { x: 0.1, y: y0 + 0.016, z: zc, rz: PI / 2, color: '#d83a2a' })
    },
    (d, w, dd, y0, zc) => {
      for (let k = 0; k < 10; k++) d.cyl(0.012, 0.014, 0.05, { mat: 'chrome', color: '#c8ccd0', x: -w / 2 + 0.05 + k * 0.07, y: y0 + 0.012, z: zc, rz: PI / 2, seg: 8 })
    },
    (d, w, dd, y0, zc) => hammer(d, { x: 0, y: y0 + 0.015, z: zc, rz: PI / 2, rx: PI / 2 }),
  ]
  for (let k = 0; k < 6; k++) {
    const h = k < 3 ? 0.11 : 0.16
    const y = k < 3 ? 0.88 - k * 0.12 : 0.53 - (k - 3) * 0.17
    b.pivot('drawer' + k, { x: 0, y, z: D / 2 + 0.002 }, (d) => {
      drawerBox(d, W - 0.04, h - 0.01, D - 0.04, { color: sh(col, 0.04), inside: '#2a2a2a', fill: (w, dd, y0, zc) => fills[k % 3](d, w, dd, y0, zc) })
      d.box(W - 0.1, 0.018, 0.02, { mat: 'chrome', color: '#c8ccd0', y: h / 2 - 0.02, z: 0.016 })
    })
  }
  // lid with a rubber mat and a few things on top
  b.box(W + 0.02, 0.04, D + 0.02, { mat: 'paint', color: sh(col, -0.15), y: 1.02, r: 0.01 })
  b.box(W - 0.04, 0.008, D - 0.04, { mat: 'rubber', color: '#1e1e1e', y: 1.044 })
  b.box(0.18, 0.04, 0.012, { mat: 'metal', color: '#c8ccd0', y: 0.94, z: D / 2 + 0.006 })
  benchClutter(b, -0.4, 0.4, -0.15, 0.15, 1.048, r, ['wrench', 'screw', 'rag', 'nuts', 'can'])
}

HC.firelocker = (b, r) => {
  // open turnout-gear locker: helmet up top, coat on a hook, boots and pants below
  const col = '#8a8e92'
  const W = 0.9
  const D = 0.5
  const H = 1.95
  carcass(b, W, H, D, { color: col, inside: sh(col, -0.15), t: 0.02 })
  b.box(W - 0.04, 0.016, D - 0.04, { mat: 'paint', color: col, y: 1.6 })
  b.box(W - 0.04, 0.016, D - 0.04, { mat: 'paint', color: col, y: 0.42 })
  // helmet
  b.at({ y: 1.61, z: 0.02 }, () => {
    b.sphere(0.15, { mat: 'paint', color: pick(r, ['#1a1a1a', '#e8c020', '#c83a2a']), y: 0.08, sy: 0.75, ts: 0, tl: PI / 2, ws: 16, hs: 8 })
    b.cyl(0.2, 0.22, 0.015, { mat: 'paint', color: '#1a1a1a', y: 0.07, sz: 1.3, seg: 18 })
    b.box(0.1, 0.12, 0.012, { mat: 'metal', color: '#d8b040', y: 0.13, z: 0.15, rx: -0.4 })
  })
  // coat on a hook with reflective trim
  b.at({ y: 1.5, z: 0.0, ry: PI / 2 }, () => {
    b.box(0.04, 0.05, 0.08, { mat: 'chrome', color: '#a8acb0' })
    b.profile([[-0.24, -0.95], [0.24, -0.95], [0.26, -0.12], [0.22, -0.03], [0.06, 0.0], [-0.06, 0.0], [-0.22, -0.03], [-0.26, -0.12]], 0.12, { mat: 'cloth', color: '#a8875a', y: -0.02, z: 0.02, bevel: 0.02, bevelSeg: 2 })
    for (const y of [-0.75, -0.5]) b.box(0.13, 0.03, 0.5, { mat: 'glowWhite', color: '#c8c8a0', y, z: 0.02 })
    b.box(0.13, 0.5, 0.05, { mat: 'cloth', color: '#8a6a44', y: -0.4, z: 0.02 })
  })
  // pants pulled down over boots
  b.at({ y: 0.43 }, () => {
    for (const s of [-1, 1]) {
      b.box(0.14, 0.2, 0.3, { mat: 'leather', color: '#1a1816', x: s * 0.12, y: -0.18, z: 0.04, r: 0.04 })
      b.cyl(0.12, 0.14, 0.18, { mat: 'cloth', color: '#9a7c52', x: s * 0.12, y: -0.08, seg: 10 })
    }
    b.box(0.5, 0.03, 0.06, { mat: 'cloth', color: '#a83020', y: 0.0, z: 0.1 })
  })
  label(b, pick(r, ['ENG 7', 'LAD 3', 'RES 1', 'ENG 12']), 0.12, 0.04, { bg: '#1a1a1a', fg: '#e8c020', y: H - 0.08, z: D / 2 + 0.002 })
  // mesh door
  b.pivot('door', { x: -W / 2, y: 0, z: D / 2 + 0.004 }, (d) => {
    for (const x of [0.015, W - 0.015]) d.box(0.03, H - 0.06, 0.02, { mat: 'paint', color: col, x, y: H / 2 })
    for (const yy of [0.03, H - 0.03]) d.box(W, 0.04, 0.02, { mat: 'paint', color: col, x: W / 2, y: yy })
    for (let k = 1; k < 9; k++) d.box(0.006, H - 0.08, 0.006, { mat: 'metal', color: '#5a5e62', x: (k * W) / 9, y: H / 2 })
    for (let k = 1; k < 18; k++) d.box(W - 0.04, 0.006, 0.006, { mat: 'metal', color: '#5a5e62', x: W / 2, y: (k * H) / 18 })
  })
}

HC.dumpster = (b, r) => {
  const col = pick(r, ['#3a6a4a', '#2a4a6a', '#5a5a52', '#6a3a2a', '#4a5a3a'])
  const W = 1.9
  const D = 1.1
  // tapered steel body: the front leans out toward the top
  b.profile([[-D / 2 + 0.05, 0.14], [D / 2 - 0.12, 0.14], [D / 2, 1.18], [-D / 2, 1.18]], W, { mat: 'paint', color: col, bevel: 0.02, bevelSeg: 1 })
  for (const x of [-0.6, -0.2, 0.2, 0.6]) b.box(0.05, 1.0, 0.04, { mat: 'paint', color: sh(col, -0.08), x, y: 0.66, z: D / 2 - 0.045, rx: -0.115 })
  b.box(W + 0.04, 0.08, D + 0.04, { mat: 'paint', color: sh(col, -0.15), y: 1.2 })
  for (const s of [-1, 1]) {
    b.box(0.1, 0.16, 0.9, { mat: 'metal', color: '#5a5e62', x: s * (W / 2 + 0.05), y: 0.92 })
    b.box(0.08, 0.04, 0.9, { mat: 'metal', color: '#3a3e42', x: s * (W / 2 + 0.05), y: 0.84 })
  }
  for (const sx of [-0.8, 0.8]) for (const sz of [-0.38, 0.38]) {
    b.box(0.08, 0.06, 0.08, { mat: 'metal', color: '#3a3e42', x: sx, y: 0.12, z: sz })
    b.cyl(0.06, 0.06, 0.05, { mat: 'rubber', color: '#1a1a1a', x: sx, y: 0.06, z: sz, rz: PI / 2, seg: 10 })
  }
  b.plane(0.6, 0.14, { material: stencilMat(pick(r, ['NO DUMPING', 'CITY WASTE', 'RECYCLE', 'METRO SANITATION']), '#e8e4d8'), y: 0.85, z: D / 2 + 0.03, rx: -0.115, shadow: false })
  for (let k = 0; k < 4; k++) blob(b, 0.15 + r() * 0.2, 0.1 + r() * 0.2, { rnd: r, x: (r() - 0.5) * 1.6, y: 0.3 + r() * 0.7, z: D / 2 - 0.01 + 0.02, rx: -0.115, t: 0.002 })
  // trash bags heaped inside, cardboard poking out
  for (let k = 0; k < 6; k++) b.ico(0.18 + r() * 0.08, { mat: 'gloss', color: pick(r, ['#1a1a1c', '#2a2a2e', '#d4d4ce']), x: -0.7 + k * 0.28, y: 1.08 + r() * 0.08, z: (r() - 0.5) * 0.5, detail: 1, noise: 0.25 })
  b.box(0.5, 0.4, 0.02, { mat: 'plain', color: '#b48c5a', x: 0.3, y: 1.3, z: 0.1, rx: 0.3, rz: 0.2 })
  for (const s of [-1, 1])
    b.pivot('door', { x: s * W * 0.25, y: 1.25, z: -D / 2 }, (d) => {
      d.box(W / 2 - 0.02, 0.05, D + 0.06, { mat: 'plastic', color: '#2a2c2a', z: D / 2 + 0.03, rx: 0.08 })
      for (let k = 0; k < 3; k++) d.box(W / 2 - 0.1, 0.02, 0.03, { mat: 'plastic', color: '#1e201e', z: 0.25 + k * 0.3, y: 0.03, rx: 0.08 })
    })
  litter(b, r, 6, -1.0, 1.0, D / 2, D / 2 + 0.5)
}

HC.pump = (b, r) => {
  const brand = pick(r, ['#c8302a', '#2a6ab0', '#2a8a4a', '#e8a020'])
  // island with a yellow-painted curb and bollards
  b.box(1.0, 0.2, 1.3, { mat: 'concrete', color: '#c8c4bc', y: 0.1 })
  b.box(1.02, 0.06, 1.32, { mat: 'paint', color: '#e8c020', y: 0.17 })
  for (const s of [-1, 1]) {
    b.cyl(0.07, 0.07, 0.9, { mat: 'paint', color: '#e8c020', x: 0, y: 0.65, z: s * 0.58, seg: 12 })
    b.cyl(0.072, 0.072, 0.04, { mat: 'paint', color: '#1a1a1a', y: 0.85, z: s * 0.58, seg: 12 })
  }
  // dispenser body
  b.box(0.72, 0.18, 0.5, { mat: 'paint', color: '#3a3c3e', y: 0.29 })
  b.box(0.7, 1.26, 0.46, { mat: 'paint', color: '#e8e6e0', y: 1.0, r: 0.02 })
  b.box(0.72, 0.32, 0.5, { mat: 'paint', color: brand, y: 1.78, r: 0.03 })
  const bn = pick(r, ['FUEL', 'GAS', 'PETRO', 'GO'])
  label(b, bn, 0.5, 0.12, { bg: brand, fg: '#ffffff', y: 1.8, z: 0.252 })
  label(b, bn, 0.5, 0.12, { bg: brand, fg: '#ffffff', y: 1.8, z: -0.252, ry: PI })
  for (const s of [-1, 1]) {
    const z = s * 0.235
    // display, price strip, card reader, keypad
    b.box(0.46, 0.26, 0.012, { mat: 'plastic', color: '#1e2022', y: 1.36, z })
    b.box(0.4, 0.07, 0.004, { mat: 'glowAmber', color: '#3a2a10', y: 1.42, z: z + s * 0.007 })
    b.box(0.4, 0.07, 0.004, { mat: 'glowAmber', color: '#3a2a10', y: 1.31, z: z + s * 0.007 })
    for (let k = 0; k < 3; k++) b.box(0.13, 0.08, 0.008, { mat: 'paint', color: ['#2a8a4a', '#2a6ab0', '#c8302a'][k], x: -0.15 + k * 0.15, y: 1.13, z: z + s * 0.005 })
    b.box(0.14, 0.16, 0.03, { mat: 'plastic', color: '#2a2c2e', x: 0.22, y: 0.95, z: z + s * 0.012 })
    for (let k = 0; k < 9; k++) b.box(0.02, 0.016, 0.006, { mat: 'plastic', color: '#c8ccd0', x: 0.2 + (k % 3) * 0.025, y: 0.92 + Math.floor(k / 3) * 0.022, z: z + s * 0.028 })
    // holstered nozzles and hoses
    for (let k = 0; k < 2; k++) {
      const nx = -0.22 + k * 0.16
      b.box(0.08, 0.16, 0.06, { mat: 'paint', color: '#3a3c3e', x: nx, y: 0.92, z: z + s * 0.03 })
      b.box(0.05, 0.05, 0.16, { mat: 'rubber', color: ['#2a8a4a', '#1a1a1a'][k], x: nx, y: 0.86, z: z + s * 0.1, rx: s * 0.4 })
      b.tube([[nx, 0.82, z + s * 0.14], [nx + 0.04, 0.5, z + s * 0.32], [nx + 0.08, 0.6, z + s * 0.2], [nx + 0.1, 1.55, z + s * 0.08]], 0.016, { mat: 'rubber', color: '#1a1a1a', seg: 6, tseg: 16 })
    }
  }
  b.cyl(0.12, 0.1, 0.5, { mat: 'paint', color: '#2a4a2a', x: 0.38, y: 0.45, z: 0.0, seg: 12 })
  b.cyl(0.11, 0.11, 0.2, { mat: 'plastic', color: '#2a6ab0', x: -0.4, y: 0.3, z: 0.4, seg: 10 })
  b.cyl(0.008, 0.008, 0.4, { mat: 'plastic', color: '#1a1a1a', x: -0.4, y: 0.48, z: 0.4, rz: 0.3, seg: 5 })
}

HC.shed = (b, r) => {
  const col = pick(r, ['#b8a888', '#8a9a7a', '#a87a5a', '#c8c4b4', '#6a7a8a'])
  const W = 2.6
  const D = 2.2
  const H = 2.0
  // floor deck, board-and-batten walls with corner trim, gable roof with shingles
  b.box(W + 0.06, 0.1, D + 0.06, { mat: 'planks', color: '#8a7a64', y: 0.05 })
  const wall = (len, h, o) =>
    b.at(o, () => {
      b.box(len, h, 0.04, { mat: 'planks', color: col, y: h / 2 })
      for (let x = -len / 2 + 0.15; x < len / 2; x += 0.3) b.box(0.05, h, 0.02, { mat: 'wood', color: sh(col, -0.06), x, y: h / 2, z: 0.03 })
    })
  wall(W, H, { y: 0.1, z: -D / 2 + 0.02 })
  wall(D, H, { y: 0.1, x: -W / 2 + 0.02, ry: -PI / 2 })
  wall(D, H, { y: 0.1, x: W / 2 - 0.02, ry: PI / 2 })
  // front wall around the double doors and a window
  for (const [x, w] of [[-0.95, 0.7], [0.95, 0.7]]) b.box(w, H, 0.04, { mat: 'planks', color: col, x, y: 0.1 + H / 2, z: D / 2 - 0.02 })
  b.box(1.2, 0.2, 0.04, { mat: 'planks', color: col, y: 0.1 + H - 0.1, z: D / 2 - 0.02 })
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.08, H, 0.08, { mat: 'wood', color: sh(col, -0.12), x: sx * (W / 2 - 0.02), y: 0.1 + H / 2, z: sz * (D / 2 - 0.02) })
  // window with frame and cross muntins
  b.at({ x: 0.95, y: 1.4, z: D / 2 + 0.005 }, () => {
    b.box(0.5, 0.42, 0.01, { mat: 'glass', color: '#9aa8b0' })
    for (const s of [-1, 1]) {
      b.box(0.06, 0.48, 0.04, { mat: 'wood', color: '#d4d0c8', x: s * 0.27 })
      b.box(0.6, 0.06, 0.04, { mat: 'wood', color: '#d4d0c8', y: s * 0.24 })
    }
    b.box(0.02, 0.42, 0.03, { mat: 'wood', color: '#d4d0c8' })
    b.box(0.5, 0.02, 0.03, { mat: 'wood', color: '#d4d0c8' })
    b.box(0.62, 0.04, 0.12, { mat: 'wood', color: '#d4d0c8', y: -0.27, z: 0.04 })
  })
  // roof: two slopes with overhang, shingle rows, fascia and a ridge cap
  const pitch = 0.42
  const half = W / 2 + 0.15
  const slope = half / Math.cos(pitch)
  for (const s of [-1, 1]) {
    b.at({ x: (s * half) / 2, y: 0.1 + H + Math.tan(pitch) * (half / 2), z: 0, rz: -s * pitch }, () => {
      b.box(slope, 0.03, D + 0.3, { mat: 'wood', color: '#6a5a48' })
      for (let k = 0; k < 7; k++) b.box(slope / 7 + 0.02, 0.025, D + 0.32, { mat: 'shingles', color: pick(r, ['#5a504a', '#4a4440', '#62584e']), x: -slope / 2 + (k + 0.5) * (slope / 7) * 1.0, y: 0.025 + (k % 2) * 0.004, rz: s * 0.04 })
    })
  }
  b.box(0.12, 0.06, D + 0.34, { mat: 'shingles', color: '#3a3430', y: 0.1 + H + Math.tan(pitch) * half + 0.03 })
  for (const sz of [-1, 1]) {
    for (const s of [-1, 1]) b.beam([0, 0.1 + H + Math.tan(pitch) * half, sz * (D / 2 + 0.16)], [s * half, 0.1 + H, sz * (D / 2 + 0.16)], 0.12, 0.03, { mat: 'wood', color: '#d4d0c8', roll: 0 })
    b.extrude([[-W / 2, 0], [W / 2, 0], [0, Math.tan(pitch) * (W / 2)]], 0.04, { mat: 'planks', color: col, y: 0.1 + H, z: sz * (D / 2 - 0.02) })
  }
  // inside: shelves with paint cans, hanging tools, a mower, a wheelbarrow
  b.box(1.6, 0.03, 0.36, { mat: 'wood', color: '#a88a64', x: -0.3, y: 1.3, z: -D / 2 + 0.22 })
  b.at({ x: -0.3, z: -D / 2 + 0.22 }, () => shelfItems(b, r, pick(r, ['cans', 'jars', 'tools']), 1.5, 0.3, 1.315, 0.25, 0.75))
  for (let k = 0; k < 3; k++) {
    const x = 0.6 + k * 0.18
    b.cyl(0.014, 0.014, 1.3, { mat: 'wood', color: '#c89a60', x, y: 0.95, z: -D / 2 + 0.1, rz: 0.04, seg: 6 })
    if (k === 0) b.box(0.3, 0.03, 0.06, { mat: 'metal', color: '#6a6e72', x, y: 0.33, z: -D / 2 + 0.1 })
    else if (k === 1) b.box(0.18, 0.22, 0.02, { mat: 'metal', color: '#6a6e72', x, y: 0.35, z: -D / 2 + 0.1 })
    else b.box(0.12, 0.08, 0.02, { mat: 'metal', color: '#6a6e72', x, y: 0.32, z: -D / 2 + 0.1 })
  }
  b.at({ x: -0.7, y: 0.1, z: 0.0, ry: 0.4 }, () => {
    b.box(0.5, 0.2, 0.6, { mat: 'paint', color: '#c83a2a', y: 0.2, r: 0.04 })
    b.cyl(0.12, 0.12, 0.2, { mat: 'metal', color: '#3a3a3a', y: 0.38, seg: 12 })
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.cyl(0.09, 0.09, 0.05, { mat: 'rubber', color: '#1a1a1a', x: sx * 0.27, y: 0.09, z: sz * 0.22, rz: PI / 2, seg: 10 })
    for (const s of [-1, 1]) b.beam([s * 0.2, 0.3, -0.3], [s * 0.2, 0.9, -0.65], 0.025, 0.025, { mat: 'metal', color: '#3a3a3a' })
    b.beam([-0.2, 0.9, -0.65], [0.2, 0.9, -0.65], 0.025, 0.025, { mat: 'rubber', color: '#1a1a1a' })
  })
  wheelbarrow(b, { x: 0.6, y: 0.1, z: 0.3, ry: -0.6 })
  // double doors with Z-braces
  for (const s of [-1, 1])
    b.pivot(s < 0 ? 'door' : 'doorR', { x: s * 0.6, y: 0.1, z: D / 2 }, (d) => {
      const cx = -s * 0.3
      d.box(0.6, 1.8, 0.04, { mat: 'planks', color: sh(col, -0.06), x: cx, y: 0.9 })
      for (const y of [0.2, 1.6]) d.box(0.56, 0.1, 0.03, { mat: 'wood', color: sh(col, -0.15), x: cx, y, z: 0.03 })
      d.beam([cx - s * 0.24, 0.25, 0.03], [cx + s * 0.24, 1.55, 0.03], 0.08, 0.02, { mat: 'wood', color: sh(col, -0.15) })
      for (const y of [0.3, 1.5]) d.box(0.16, 0.03, 0.01, { mat: 'metal', color: '#2a2a2a', x: s * -0.06 + (s < 0 ? 0.05 : -0.05) * 0, y, z: 0.05 })
    })
  b.box(0.04, 0.12, 0.03, { mat: 'metal', color: '#3a3a3a', x: 0.0, y: 1.1, z: D / 2 + 0.05 })
}

// ---------------------------------------------------------------- decor
export const HDD = {}

HDD.bed = (b, r) => {
  const frame = pick(r, WOOD)
  const sheet = pick(r, ['#d8d4cc', '#b8c4d0', '#ccbcac', '#b0c0a8'])
  const quilt = pick(r, ['#7a3434', '#34446a', '#56664a', '#b89458', '#64466a', '#ccc4b4', '#3a5a5a'])
  const W = 1.52
  const L = 2.06
  // posts, rails, slatted headboard and a lower footboard
  for (const sx of [-1, 1]) {
    b.box(0.07, 1.05, 0.07, { mat: 'wood', color: sh(frame, -0.06), x: sx * (W / 2), y: 0.525, z: -L / 2 + 0.035 })
    b.sphere(0.045, { mat: 'wood', color: sh(frame, -0.06), x: sx * (W / 2), y: 1.08, z: -L / 2 + 0.035, ws: 10, hs: 7 })
    b.box(0.07, 0.6, 0.07, { mat: 'wood', color: sh(frame, -0.06), x: sx * (W / 2), y: 0.3, z: L / 2 - 0.035 })
    b.box(0.04, 0.2, L - 0.1, { mat: 'wood', color: frame, x: sx * (W / 2 - 0.01), y: 0.28 })
  }
  b.box(W, 0.1, 0.05, { mat: 'wood', color: frame, y: 1.0, z: -L / 2 + 0.035 })
  b.box(W, 0.1, 0.05, { mat: 'wood', color: frame, y: 0.5, z: -L / 2 + 0.035 })
  for (let k = 0; k < 7; k++) b.box(0.07, 0.42, 0.03, { mat: 'wood', color: sh(frame, 0.04), x: -0.54 + k * 0.18, y: 0.75, z: -L / 2 + 0.035 })
  b.box(W, 0.32, 0.04, { mat: 'wood', color: frame, y: 0.42, z: L / 2 - 0.035 })
  b.box(W, 0.05, 0.06, { mat: 'wood', color: sh(frame, -0.08), y: 0.6, z: L / 2 - 0.035 })
  // mattress with a sheet, quilted duvet folded back, pillows
  b.box(W - 0.08, 0.24, L - 0.12, { mat: 'cloth', color: sheet, y: 0.5, r: 0.06, seg: 3 })
  b.at({ y: 0.62, z: 0.24 }, () => {
    b.box(W - 0.02, 0.07, 1.36, { mat: 'cloth', color: quilt, r: 0.035, seg: 3 })
    for (const sx of [-1, 1]) b.box(0.03, 0.26, 1.36, { mat: 'cloth', color: sh(quilt, -0.06), x: sx * (W / 2 + 0.005), y: -0.12, r: 0.012, rz: sx * 0.06 })
    b.box(W - 0.04, 0.26, 0.03, { mat: 'cloth', color: sh(quilt, -0.06), y: -0.12, z: 0.69, r: 0.012 })
    // quilting lines and the folded-back top edge
    for (let k = 1; k < 5; k++) b.box(W - 0.06, 0.004, 0.006, { mat: 'cloth', color: sh(quilt, -0.15), y: 0.036, z: -0.68 + k * 0.27 })
    for (let k = 1; k < 4; k++) b.box(0.006, 0.004, 1.32, { mat: 'cloth', color: sh(quilt, -0.15), x: -W / 2 + (k * W) / 4, y: 0.036 })
    b.cyl(0.05, 0.05, W - 0.04, { mat: 'cloth', color: sh(sheet, 0.04), y: 0.03, z: -0.7, rz: PI / 2, seg: 10 })
  })
  for (const s of [-1, 1]) pillow(b, 0.6, 0.15, 0.4, { color: sh(sheet, 0.06), x: s * 0.35, y: 0.69, z: -L / 2 + 0.33, rx: -0.25 })
  pillow(b, 0.4, 0.12, 0.3, { color: quilt, x: 0.1, y: 0.73, z: -L / 2 + 0.52, rx: -0.4, rz: 0.15 })
  // a book left on the bed, a box under it
  if (r() < 0.5) b.box(0.15, 0.03, 0.22, { mat: 'paint', color: pick(r, BOOK), x: -0.3, y: 0.67, z: 0.55, ry: 0.5 })
  b.box(0.6, 0.15, 0.4, { mat: 'plain', color: '#b48c5a', x: 0.3, y: 0.08, z: 0.3 })
}

HDD.nightstand = (b, r) => {
  const col = pick(r, WOOD)
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.03, 0.12, 0.03, { mat: 'wood', color: sh(col, -0.15), x: sx * 0.2, y: 0.06, z: sz * 0.16 })
  carcass(b, 0.46, 0.44, 0.38, { mat: 'wood', color: col, inside: sh(col, -0.1), y0: 0.12 })
  b.box(0.5, 0.025, 0.42, { mat: 'wood', color: sh(col, -0.05), y: 0.575 })
  b.box(0.42, 0.16, 0.02, { mat: 'wood', color: sh(col, 0.05), y: 0.46, z: 0.19 })
  pull(b, 0, 0.46, 0.2, { kind: 'knob', mat: 'metal', color: '#b89a50' })
  booksRow(b, r, -0.2, 0.2, 0.14, -0.16, 0.3, 0.2, { gap: 0.2 })
  // lamp, alarm clock, a glass and a phone
  b.at({ x: -0.12, y: 0.59, z: -0.05 }, () => {
    b.lathe([[0.06, 0], [0.065, 0.02], [0.03, 0.06], [0.045, 0.14], [0.012, 0.2]], { mat: 'gloss', color: pick(r, ['#3a5a7a', '#c8b898', '#7a3a2a', '#2a2a2a']), seg: 12 })
    b.cyl(0.08, 0.12, 0.15, { mat: 'cloth', color: '#d4c8ac', y: 0.28, seg: 14, open: true })
    b.sphere(0.025, { mat: 'nightGlow', color: '#fff0d0', y: 0.24, ws: 8, hs: 6 })
  })
  b.box(0.11, 0.07, 0.06, { mat: 'plastic', color: '#1e1e20', x: 0.12, y: 0.625, z: 0.06, r: 0.01, ry: -0.3 })
  b.box(0.07, 0.03, 0.003, { mat: 'glowRed', color: '#3a0a0a', x: 0.12 + Math.sin(-0.3) * 0.031, y: 0.628, z: 0.06 + Math.cos(-0.3) * 0.031, ry: -0.3 })
  b.cyl(0.03, 0.026, 0.1, { mat: 'glass', color: '#d8e8e8', x: 0.16, y: 0.64, z: -0.1, seg: 10 })
  b.box(0.07, 0.008, 0.14, { mat: 'plastic', color: '#1a1a1c', x: 0.05, y: 0.594, z: 0.1, ry: 0.4 })
}

HDD.sofa = (b, r) => {
  const leather = r() < 0.25
  const col = leather ? pick(r, ['#4a2e1e', '#2a2220', '#6a3a24']) : pick(r, ['#6a4a3a', '#3a4a5a', '#7a7a6a', '#8a3a3a', '#4a5a3a', '#a89878', '#5a5a62'])
  const m = leather ? 'leather' : 'cloth'
  const W = 2.1
  // legs, base, two arms, three seat cushions, three back cushions
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.lathe([[0.025, 0], [0.02, 0.08], [0.03, 0.1]], { mat: 'wood', color: '#3a2a1e', x: sx * (W / 2 - 0.08), z: sz * 0.36, seg: 8 })
  b.box(W - 0.06, 0.24, 0.86, { mat: m, color: sh(col, -0.06), y: 0.22, r: 0.04 })
  b.box(W - 0.1, 0.56, 0.22, { mat: m, color: sh(col, -0.04), y: 0.5, z: -0.33, r: 0.07, rx: -0.06 })
  for (const s of [-1, 1]) {
    b.box(0.22, 0.42, 0.9, { mat: m, color: col, x: s * (W / 2 - 0.11), y: 0.34, r: 0.06 })
    b.cyl(0.12, 0.12, 0.9, { mat: m, color: col, x: s * (W / 2 - 0.11), y: 0.56, rx: PI / 2, seg: 14 })
  }
  for (let k = 0; k < 3; k++) {
    cushion(b, 0.54, 0.15, 0.6, { mat: m, color: sh(col, 0.03 + (r() - 0.5) * 0.04), x: -0.56 + k * 0.56, y: 0.41, z: 0.1, r: 0.06 })
    cushion(b, 0.54, 0.44, 0.17, { mat: m, color: sh(col, 0.02), x: -0.56 + k * 0.56, y: 0.7, z: -0.2, rx: -0.2, r: 0.07, welt: false })
  }
  // throw pillows and a blanket over one arm
  for (let k = 0; k < 2; k++) pillow(b, 0.38, 0.36, 0.13, { color: pick(r, ['#c8a060', '#d4d0c8', '#5a7a8a', '#a83a3a', '#3a3a3a']), x: (k ? 1 : -1) * 0.66, y: 0.66, z: -0.04, rx: -0.25, rz: (k ? -1 : 1) * 0.2 })
  if (r() < 0.6) {
    const bc = pick(r, ['#8a6a4a', '#3a5a6a', '#c8b898', '#6a3a4a'])
    b.box(0.26, 0.04, 0.7, { mat: 'knit', color: bc, x: W / 2 - 0.11, y: 0.7, z: 0.0, r: 0.015 })
    b.box(0.04, 0.32, 0.6, { mat: 'knit', color: bc, x: W / 2 + 0.01, y: 0.52, z: 0.0, r: 0.015, rz: -0.1 })
  }
}

HDD.armchair = (b, r) => {
  const col = pick(r, ['#6a4a3a', '#3a4a5a', '#7a6a5a', '#8a5a3a', '#4a5a3a', '#7a3a3a'])
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.lathe([[0.025, 0], [0.02, 0.08], [0.03, 0.1]], { mat: 'wood', color: '#3a2a1e', x: sx * 0.36, z: sz * 0.34, seg: 8 })
  b.box(0.84, 0.24, 0.82, { mat: 'cloth', color: sh(col, -0.06), y: 0.22, r: 0.04 })
  // a wingback: tall back with wings
  b.box(0.8, 0.82, 0.2, { mat: 'cloth', color: sh(col, -0.03), y: 0.62, z: -0.31, r: 0.07, rx: -0.08 })
  for (const s of [-1, 1]) {
    b.box(0.16, 0.42, 0.82, { mat: 'cloth', color: col, x: s * 0.34, y: 0.34, r: 0.05 })
    b.cyl(0.09, 0.09, 0.8, { mat: 'cloth', color: col, x: s * 0.34, y: 0.55, z: 0.01, rx: PI / 2, seg: 12 })
    b.box(0.08, 0.5, 0.3, { mat: 'cloth', color: col, x: s * 0.36, y: 0.82, z: -0.22, r: 0.04, ry: s * 0.25 })
  }
  cushion(b, 0.52, 0.15, 0.6, { color: sh(col, 0.04), y: 0.41, z: 0.08, r: 0.06 })
  pillow(b, 0.36, 0.34, 0.12, { color: pick(r, ['#c8a060', '#d4d0c8', '#5a7a8a']), y: 0.66, z: -0.12, rx: -0.2 })
}

HDD.coffeetable = (b, r) => {
  const col = pick(r, WOOD)
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.045, 0.4, 0.045, { mat: 'wood', color: sh(col, -0.1), x: sx * 0.5, y: 0.2, z: sz * 0.25 })
  b.box(1.1, 0.045, 0.6, { mat: 'wood', color: col, y: 0.42, r: 0.008 })
  b.box(1.0, 0.02, 0.5, { mat: 'wood', color: sh(col, -0.06), y: 0.12 })
  // magazines below, mugs, a remote, a bowl on top
  for (let k = 0; k < 4; k++) b.box(0.22, 0.008, 0.29, { mat: 'paint', color: pick(r, ['#c83a2a', '#2a4a8a', '#e8c040', '#3a3a3a', '#d4d0c8']), x: -0.25 + (r() - 0.5) * 0.04, y: 0.135 + k * 0.008, z: 0, ry: (r() - 0.5) * 0.4 })
  mug(b, 0.3, 0.443, 0.1, pick(r, ['#c83a2a', '#d4d0c8', '#2a4a8a']), 0.7)
  b.box(0.05, 0.02, 0.17, { mat: 'plastic', color: '#1a1a1c', x: -0.1, y: 0.453, z: 0.12, ry: -0.6, r: 0.008 })
  b.cyl(0.13, 0.08, 0.06, { mat: 'gloss', color: pick(r, ['#3a5a7a', '#a8603a', '#d4d0c8']), x: -0.3, y: 0.473, z: -0.08, seg: 14 })
  for (let k = 0; k < 2; k++) b.box(0.18, 0.03, 0.24, { mat: 'paint', color: pick(r, BOOK), x: 0.15, y: 0.458 + k * 0.03, z: -0.1, ry: k * 0.2 })
}

HDD.rug = (b, r) => {
  const seed = Math.floor(r() * 1000)
  b.box(2.4, 0.012, 1.7, { mat: 'carpet', color: '#6a4a3a', y: 0.006, ao: 0, shadow: false })
  b.plane(2.38, 1.68, { material: rugMat(seed), y: 0.0125, rx: -PI / 2, shadow: false })
  // fringe on the short ends
  for (const s of [-1, 1]) for (let k = 0; k < 28; k++) b.box(0.012, 0.004, 0.06, { mat: 'cloth', color: '#d8ccaa', x: s * 1.23, y: 0.004, z: -0.81 + k * 0.06, ry: PI / 2 + (r() - 0.5) * 0.3 })
}

HDD.lamp = (b, r) => {
  b.lathe([[0.16, 0], [0.17, 0.02], [0.14, 0.04], [0.03, 0.06], [0.02, 0.07]], { mat: 'metal', color: pick(r, ['#2a2a2a', '#b8a060', '#8a8e92']), seg: 18 })
  b.cyl(0.012, 0.012, 1.42, { mat: 'chrome', color: '#b8bcc0', y: 0.78, seg: 8 })
  b.cyl(0.012, 0.012, 0.004, { mat: 'plastic', color: '#1a1a1a', x: 0.0, y: 1.1, seg: 6 })
  b.cyl(0.14, 0.24, 0.32, { mat: 'cloth', color: pick(r, ['#d4c8ac', '#c8b898', '#b8a888']), y: 1.56, seg: 18, open: true })
  b.torus(0.24, 0.004, { mat: 'metal', color: '#8a7a50', y: 1.4, rx: PI / 2, rs: 4, ts2: 24 })
  b.sphere(0.045, { mat: 'nightGlow', color: '#fff0d0', y: 1.5, ws: 10, hs: 8 })
}

HDD.table = (b, r) => {
  const col = pick(r, WOOD)
  // turned legs, apron, plank top
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.lathe([[0.035, 0], [0.03, 0.15], [0.042, 0.3], [0.028, 0.45], [0.038, 0.6], [0.04, 0.72]], { mat: 'wood', color: sh(col, -0.1), x: sx * 0.72, z: sz * 0.37, seg: 10 })
  for (const s of [-1, 1]) {
    b.box(1.4, 0.09, 0.025, { mat: 'wood', color: sh(col, -0.05), y: 0.68, z: s * 0.37 })
    b.box(0.025, 0.09, 0.7, { mat: 'wood', color: sh(col, -0.05), x: s * 0.72, y: 0.68 })
  }
  for (let k = 0; k < 4; k++) b.box(1.64, 0.04, 0.225 - 0.004, { mat: 'wood', color: sh(col, (r() - 0.5) * 0.06), y: 0.745, z: -0.3375 + k * 0.225 })
  // runner, place settings, a vase of flowers, candles
  b.box(0.36, 0.004, 0.92, { mat: 'cloth', color: pick(r, ['#c8b898', '#8a3a3a', '#3a5a6a']), y: 0.768, ry: PI / 2 })
  for (const [x, z] of [[-0.45, -0.28], [0.45, -0.28], [-0.45, 0.28], [0.45, 0.28]]) {
    plates(b, x, 0.766, z, 2, '#c4c0b8', 0.11)
    b.box(0.015, 0.004, 0.18, { mat: 'chrome', color: '#c8ccd0', x: x - 0.15, y: 0.768, z })
    b.box(0.015, 0.004, 0.18, { mat: 'chrome', color: '#c8ccd0', x: x + 0.15, y: 0.768, z })
    b.lathe([[0.03, 0], [0.004, 0.01], [0.004, 0.08], [0.035, 0.12], [0.04, 0.18]], { mat: 'glass', color: '#e0ecec', x: x + 0.12, y: 0.768, z: z - 0.12, seg: 10 })
  }
  b.lathe([[0.05, 0], [0.07, 0.06], [0.04, 0.16], [0.05, 0.2]], { mat: 'gloss', color: pick(r, ['#3a5a7a', '#d4d0c8', '#7a3a2a']), y: 0.77, seg: 12 })
  for (let k = 0; k < 6; k++) {
    const a = (k / 6) * TAU
    b.cyl(0.003, 0.003, 0.25, { mat: 'leaf', color: '#4a6a30', x: Math.cos(a) * 0.02, y: 1.05, z: Math.sin(a) * 0.02, rx: Math.sin(a) * 0.3, rz: -Math.cos(a) * 0.3, seg: 4 })
    b.ico(0.03, { mat: 'plastic', color: pick(r, ['#c83a4a', '#e8c040', '#e86a8a', '#d4d0c8']), x: Math.cos(a) * 0.07, y: 1.17 + r() * 0.04, z: Math.sin(a) * 0.07, detail: 1, noise: 0.3 })
  }
  for (const s of [-1, 1]) b.cyl(0.012, 0.012, 0.22, { mat: 'plain', color: '#d8d0b8', x: s * 0.25, y: 0.88, seg: 8 })
  // chairs: spindle backs, one pushed out
  for (const [x, z, ry] of [[-0.45, -0.62, 0], [0.45, -0.62, 0], [-0.45, 0.62, PI], [0.45, 0.7, PI + 0.3]])
    b.at({ x, z, ry }, () => {
      for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.cyl(0.018, 0.016, 0.45, { mat: 'wood', color: sh(col, -0.12), x: sx * 0.18, y: 0.225, z: sz * 0.18, seg: 6 })
      b.box(0.44, 0.04, 0.42, { mat: 'wood', color: col, y: 0.47, r: 0.01 })
      for (const sx of [-1, 1]) b.cyl(0.018, 0.018, 0.52, { mat: 'wood', color: sh(col, -0.12), x: sx * 0.18, y: 0.74, z: -0.19, seg: 6 })
      b.box(0.42, 0.07, 0.03, { mat: 'wood', color: col, y: 0.98, z: -0.19 })
      for (let k = 0; k < 4; k++) b.cyl(0.009, 0.009, 0.42, { mat: 'wood', color: sh(col, 0.05), x: -0.12 + k * 0.08, y: 0.74, z: -0.19, seg: 5 })
    })
}

HDD.stove = (b, r) => {
  const col = pick(r, ['#d4d4ce', '#c8c4bc', '#3a3c3e', '#a8acae'])
  const steel = col === '#a8acae'
  const m = steel ? 'metal' : 'paint'
  b.box(0.76, 0.06, 0.6, { mat: 'plain', color: '#1a1a1a', y: 0.03 })
  b.box(0.76, 0.84, 0.62, { mat: m, color: col, y: 0.48, r: 0.01 })
  // oven door with window and bar handle, drawer below
  b.box(0.7, 0.5, 0.02, { mat: m, color: sh(col, 0.02), y: 0.52, z: 0.315 })
  b.box(0.48, 0.24, 0.006, { mat: 'mapGlass', color: '#121416', y: 0.52, z: 0.327 })
  b.box(0.6, 0.022, 0.022, { mat: 'chrome', color: '#c8ccd0', y: 0.74, z: 0.35 })
  for (const s of [-1, 1]) b.box(0.02, 0.02, 0.04, { mat: 'chrome', color: '#c8ccd0', x: s * 0.28, y: 0.74, z: 0.335 })
  b.box(0.7, 0.14, 0.02, { mat: m, color: sh(col, -0.03), y: 0.16, z: 0.315 })
  // control panel knobs
  for (let k = 0; k < 4; k++) b.cyl(0.022, 0.022, 0.025, { mat: 'plastic', color: '#1a1a1a', x: -0.27 + k * 0.18, y: 0.84, z: 0.33, rx: PI / 2, seg: 10 })
  // black glass cooktop with burner rings, a pot and a kettle
  b.box(0.76, 0.012, 0.62, { mat: 'mapGlass', color: '#141416', y: 0.906 })
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
    b.torus(0.085 + (sx > 0 ? 0.02 : 0), 0.004, { mat: 'plain', color: '#5a5a5a', x: sx * 0.18, y: 0.914, z: sz * 0.15, rx: PI / 2, rs: 3, ts2: 20 })
    b.torus(0.045, 0.003, { mat: 'plain', color: '#4a4a4a', x: sx * 0.18, y: 0.914, z: sz * 0.15, rx: PI / 2, rs: 3, ts2: 14 })
  }
  b.cyl(0.13, 0.12, 0.14, { mat: 'metal', color: '#9a9ea2', x: 0.18, y: 0.985, z: 0.15, seg: 16 })
  b.cyl(0.011, 0.011, 0.16, { mat: 'plastic', color: '#1a1a1a', x: 0.18 + 0.2, y: 1.03, z: 0.15, rz: PI / 2, seg: 6 })
  b.lathe([[0.08, 0], [0.095, 0.05], [0.08, 0.13], [0.03, 0.17], [0.012, 0.19]], { mat: 'gloss', color: pick(r, ['#c83a2a', '#2a4a7a', '#e8c040']), x: -0.18, y: 0.912, z: -0.15, seg: 14 })
  b.torus(0.06, 0.008, { mat: 'plastic', color: '#1a1a1a', x: -0.18, y: 1.12, z: -0.15, rs: 4, ts2: 10, arc: PI })
  // backguard with a clock, and a range hood on the wall above
  b.box(0.76, 0.14, 0.05, { mat: m, color: col, y: 0.98, z: -0.29 })
  b.box(0.12, 0.04, 0.004, { mat: 'glowGreen', color: '#123a1a', y: 0.99, z: -0.263 })
  b.at({ y: 1.62, z: -0.1 }, () => {
    b.box(0.78, 0.12, 0.48, { mat: 'metal', color: '#9a9ea2', r: 0.01 })
    b.box(0.3, 0.5, 0.24, { mat: 'metal', color: '#9a9ea2', y: 0.3, z: -0.1 })
    b.box(0.6, 0.006, 0.3, { mat: 'metal', color: '#5a5e62', y: -0.06, z: 0.03 })
    b.box(0.06, 0.008, 0.004, { mat: 'nightGlow', color: '#fff0d0', y: -0.064, z: 0.1 })
  })
}

HDD.toilet = (b, r) => {
  const pc = '#babab4'
  // bowl, pedestal, seat and lid (up or down), tank with lid and lever
  b.lathe([[0.1, 0], [0.12, 0.05], [0.1, 0.2], [0.15, 0.32], [0.18, 0.39], [0.185, 0.41]], { mat: 'gloss', color: pc, z: 0.08, seg: 18, sz: 1.3 })
  b.lathe([[0.16, 0.41], [0.13, 0.33], [0.06, 0.25], [0.001, 0.24]], { mat: 'gloss', color: sh(pc, -0.08), z: 0.08, seg: 18, sz: 1.3 })
  b.cyl(0.12, 0.12, 0.004, { mat: 'glass', color: '#b8d0d8', y: 0.27, z: 0.08, sz: 1.3, seg: 14 })
  b.torus(0.155, 0.02, { mat: 'plastic', color: pc, y: 0.425, z: 0.08, rx: PI / 2, sx: 1, sy: 1.3, rs: 6, ts2: 22 })
  const up = r() < 0.5
  b.at({ y: 0.44, z: -0.12, rx: up ? -1.45 : 0 }, () => b.cyl(0.19, 0.19, 0.02, { mat: 'plastic', color: pc, y: 0.0, z: 0.2, sz: 1.25, seg: 22 }))
  b.box(0.42, 0.36, 0.17, { mat: 'gloss', color: pc, y: 0.6, z: -0.25, r: 0.03 })
  b.box(0.44, 0.04, 0.19, { mat: 'gloss', color: pc, y: 0.79, z: -0.25, r: 0.015 })
  b.box(0.06, 0.016, 0.02, { mat: 'chrome', color: '#c8ccd0', x: -0.16, y: 0.72, z: -0.16 })
  // paper roll on the wall and a small bin
  b.cyl(0.006, 0.006, 0.16, { mat: 'chrome', color: '#c8ccd0', x: 0.35, y: 0.65, z: -0.3, rz: PI / 2, seg: 6 })
  b.cyl(0.055, 0.055, 0.11, { mat: 'plain', color: '#d8d8d0', x: 0.35, y: 0.65, z: -0.27, rz: PI / 2, seg: 14 })
  b.cyl(0.1, 0.09, 0.26, { mat: 'metal', color: '#b8bcbe', x: -0.36, y: 0.13, z: -0.2, seg: 12 })
}

HDD.tub = (b, r) => {
  const pc = '#b8b8b2'
  // tub body and apron, the inner basin, rim
  b.box(0.8, 0.52, 1.7, { mat: 'gloss', color: pc, y: 0.26, r: 0.02 })
  b.box(0.66, 0.04, 1.54, { mat: 'gloss', color: sh(pc, -0.12), y: 0.5 })
  b.box(0.62, 0.006, 1.48, { mat: 'glass', color: '#b8ccd4', y: 0.43 })
  b.box(0.06, 0.03, 1.7, { mat: 'gloss', color: pc, x: 0.37, y: 0.53, r: 0.01 })
  b.box(0.06, 0.03, 1.7, { mat: 'gloss', color: pc, x: -0.37, y: 0.53, r: 0.01 })
  // tiled wall surround, faucet, shower riser and head
  b.box(0.82, 1.5, 0.02, { mat: 'tiles', color: pick(r, ['#d4d0c8', '#b8ccd0', '#c8d0c0']), y: 1.27, z: -0.86, uv: 0.5 })
  b.at({ z: -0.8 }, () => {
    b.cyl(0.02, 0.02, 0.14, { mat: 'chrome', color: '#d0d4d8', y: 0.68, z: 0.06, rx: PI / 2, seg: 8 })
    for (const s of [-1, 1]) b.cyl(0.025, 0.025, 0.03, { mat: 'chrome', color: '#d0d4d8', x: s * 0.12, y: 0.78, z: 0.02, rx: PI / 2, seg: 10 })
    b.cyl(0.011, 0.011, 1.2, { mat: 'chrome', color: '#d0d4d8', y: 1.4, seg: 8 })
    b.cyl(0.07, 0.05, 0.03, { mat: 'chrome', color: '#d0d4d8', y: 1.98, z: 0.12, rx: -0.6, seg: 14 })
    b.beam([0, 1.99, 0], [0, 2.02, 0.1], 0.02, 0.02, { mat: 'chrome', color: '#d0d4d8', round: true })
  })
  // curtain rod and a pleated curtain, bath mat, bottles
  b.cyl(0.01, 0.01, 1.7, { mat: 'chrome', color: '#c8ccd0', x: 0.4, y: 2.0, rx: PI / 2, seg: 6 })
  const g = new THREE.PlaneGeometry(0.9, 1.5, 36, 1)
  const p = g.attributes.position
  for (let i = 0; i < p.count; i++) p.setZ(i, Math.sin(p.getX(i) * 40) * 0.03)
  g.computeVertexNormals()
  b.add(g, { mat: 'clothDS', color: pick(r, ['#a8c0c8', '#d4d0c8', '#c8a8b0']), x: 0.42, y: 1.24, z: -0.38, ry: PI / 2 })
  b.box(0.5, 0.012, 0.8, { mat: 'carpet', color: pick(r, ['#3a5a7a', '#8a6a5a', '#5a7a5a']), x: 0.75, y: 0.006 })
  for (let k = 0; k < 3; k++) bottle(b, -0.34, 0.545, -0.6 + k * 0.08, { h: 0.18, r: 0.025, mat: 'plastic', color: pick(r, ['#e8a020', '#40a8e8', '#d4d0c8', '#e85a8a']), cap: '#d4d0c8', seg: 7 })
}

HDD.sink = (b, r) => {
  const pc = '#babab4'
  b.lathe([[0.11, 0], [0.09, 0.05], [0.07, 0.3], [0.08, 0.6], [0.12, 0.7], [0.05, 0.72]], { mat: 'gloss', color: pc, seg: 16, sz: 0.8 })
  b.lathe([[0.15, 0.68], [0.25, 0.74], [0.28, 0.82], [0.27, 0.86]], { mat: 'gloss', color: pc, seg: 20, sz: 0.75 })
  b.lathe([[0.25, 0.86], [0.23, 0.8], [0.15, 0.76], [0.001, 0.75]], { mat: 'gloss', color: sh(pc, -0.06), seg: 20, sz: 0.75 })
  b.at({ z: -0.16, y: 0.86 }, () => {
    b.cyl(0.018, 0.022, 0.12, { mat: 'chrome', color: '#d0d4d8', y: 0.06, seg: 8 })
    b.cyl(0.01, 0.01, 0.1, { mat: 'chrome', color: '#d0d4d8', y: 0.1, z: 0.05, rx: PI / 2, seg: 6 })
    for (const s of [-1, 1]) b.cyl(0.016, 0.016, 0.04, { mat: 'chrome', color: '#d0d4d8', x: s * 0.1, y: 0.02, seg: 8 })
  })
  b.at({ y: 1.45, z: -0.24 }, () => {
    b.box(0.5, 0.64, 0.025, { mat: 'wood', color: pick(r, ['#3a2a1e', '#d4d0c8', '#8a6a4a']) })
    b.box(0.44, 0.58, 0.004, { mat: 'chrome', color: '#cfd8dc', z: 0.014 })
  })
  b.box(0.08, 0.03, 0.05, { mat: 'plain', color: pick(r, ['#e8c8a0', '#a8d0e0']), x: 0.17, y: 0.875, z: -0.12, r: 0.01 })
  b.cyl(0.008, 0.008, 0.5, { mat: 'chrome', color: '#c8ccd0', x: 0.45, y: 1.0, z: -0.22, rz: PI / 2, seg: 6 })
  b.box(0.3, 0.4, 0.03, { mat: 'cloth', color: pick(r, ['#d4d0c8', '#3a5a7a', '#c8a080']), x: 0.45, y: 0.82, z: -0.21, r: 0.01 })
}

HDD.chair = (b, r) => {
  const k = r()
  if (k < 0.5) {
    // plastic stacking chair on a steel frame
    const c = pick(r, ['#2a4a7a', '#c8302a', '#3a3a3a', '#d4d0c8', '#3a7a5a'])
    for (const sx of [-1, 1]) {
      b.beam([sx * 0.2, 0, 0.2], [sx * 0.2, 0.44, 0.16], 0.02, 0.02, { mat: 'chrome', color: '#a8acb0', round: true })
      b.beam([sx * 0.2, 0, -0.2], [sx * 0.2, 0.9, -0.24], 0.02, 0.02, { mat: 'chrome', color: '#a8acb0', round: true })
    }
    b.box(0.44, 0.03, 0.42, { mat: 'plastic', color: c, y: 0.46, r: 0.012 })
    b.box(0.42, 0.3, 0.025, { mat: 'plastic', color: c, y: 0.78, z: -0.23, rx: 0.12, r: 0.01 })
  } else {
    const col = pick(r, WOOD)
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.cyl(0.018, 0.016, 0.45, { mat: 'wood', color: sh(col, -0.12), x: sx * 0.18, y: 0.225, z: sz * 0.18, seg: 6 })
    b.box(0.44, 0.04, 0.42, { mat: 'wood', color: col, y: 0.47, r: 0.01 })
    for (const sx of [-1, 1]) b.cyl(0.018, 0.018, 0.52, { mat: 'wood', color: sh(col, -0.12), x: sx * 0.18, y: 0.74, z: -0.19, seg: 6 })
    b.box(0.42, 0.12, 0.03, { mat: 'wood', color: col, y: 0.95, z: -0.19 })
    b.box(0.42, 0.06, 0.03, { mat: 'wood', color: col, y: 0.72, z: -0.19 })
  }
}

// A broad leaf: a thin oval blade, cupped a little, on its own petiole.
function bigLeaf(b, x, y, z, len, wid, yaw, droop, col) {
  const pts = []
  for (let i = 0; i <= 10; i++) {
    const a = (i / 10) * PI
    pts.push([Math.sin(a) * wid * 0.5 * (1 - 0.3 * Math.cos(a)), -Math.cos(a) * len * 0.5 + len * 0.5])
  }
  for (let i = 9; i > 0; i--) pts.push([-pts[i][0], pts[i][1]])
  b.at({ x, y, z, ry: yaw, rx: -PI / 2 + droop, order: 'YXZ' }, () => {
    b.extrude(pts, 0.004, { mat: 'leaf', color: col, curve: 2 })
    b.box(0.006, len * 0.9, 0.006, { mat: 'leaf', color: shadeHex(col, 0.15), y: len * 0.48, z: 0.003 })
  })
}
HDD.plant = (b, r) => {
  const pot = pick(r, ['#a8603a', '#c8c4bc', '#3a3a3a', '#5a7a8a', '#8a5a3a'])
  b.lathe([[0.14, 0], [0.17, 0.05], [0.2, 0.34], [0.215, 0.36], [0.2, 0.37]], { mat: 'gloss', color: pot, seg: 16 })
  b.cyl(0.19, 0.19, 0.01, { mat: 'dirt', color: '#5a4030', y: 0.34, seg: 14 })
  const kind = r()
  if (kind < 0.45) {
    // rubber plant / fiddle-leaf: leaves spiralling up a few stems
    for (let s2 = 0; s2 < 3; s2++) {
      const sx = (r() - 0.5) * 0.1
      const sz = (r() - 0.5) * 0.1
      const h = 0.7 + r() * 0.6
      b.tube([[sx, 0.34, sz], [sx * 1.5, 0.34 + h * 0.5, sz * 1.5], [sx * 2, 0.34 + h, sz * 2]], 0.01, { mat: 'wood', color: '#5a4a30', seg: 5, tseg: 6 })
      const n = 6 + Math.floor(r() * 4)
      for (let i = 0; i < n; i++) {
        const t = 0.25 + (i / n) * 0.75
        bigLeaf(b, sx * (1 + t), 0.34 + h * t, sz * (1 + t), 0.2 + r() * 0.08, 0.12 + r() * 0.04, i * 2.4 + s2, 0.5 + (1 - t) * 0.5, pick(r, ['#2e5a26', '#3a6a2c', '#24481e', '#467a32']))
      }
    }
  } else if (kind < 0.75) {
    // snake plant: tall upright blades
    for (let i = 0; i < 14; i++) {
      const a = r() * TAU
      const d = r() * 0.1
      const h = 0.4 + r() * 0.45
      b.at({ x: Math.cos(a) * d, y: 0.34, z: Math.sin(a) * d, ry: r() * PI, rz: (r() - 0.5) * 0.25 }, () => {
        b.extrude([[-0.03, 0], [0.03, 0], [0.02, h * 0.8], [0, h], [-0.02, h * 0.8]], 0.006, { mat: 'leaf', color: pick(r, ['#3a5a2a', '#4a6a30', '#5a7a3a']), curve: 1 })
        b.box(0.062, h * 0.7, 0.007, { mat: 'leaf', color: '#b8a850', y: h * 0.35, z: 0.0 })
      })
    }
  } else {
    // a palm: fronds of leaflets arching out
    for (let i = 0; i < 7; i++) {
      const a = (i / 7) * TAU + r() * 0.4
      const len = 0.55 + r() * 0.25
      const tip = [Math.cos(a) * len * 0.8, 0.34 + len * 0.9, Math.sin(a) * len * 0.8]
      b.tube([[0, 0.36, 0], [tip[0] * 0.5, tip[1] + 0.1, tip[2] * 0.5], tip], 0.006, { mat: 'leaf', color: '#4a6a2c', seg: 4, tseg: 8 })
      for (let k = 1; k < 8; k++) {
        const t = k / 8
        const px = tip[0] * t
        const py = 0.36 + (tip[1] - 0.36) * t + Math.sin(t * PI) * 0.1
        const pz = tip[2] * t
        for (const s2 of [-1, 1]) bigLeaf(b, px, py, pz, 0.18 * (1 - t * 0.5), 0.035, a + s2 * 1.2 - PI / 2, 0.9, pick(r, ['#3a6a2c', '#467a32', '#2e5a26']))
      }
    }
  }
}

HDD.boxes = (b, r) => {
  const words = ['KITCHEN', 'BOOKS', 'MISC', 'FRAGILE', 'GARAGE', 'XMAS', 'TOYS', 'BEDROOM']
  const placed = []
  for (let k = 0; k < 5; k++) {
    const w = 0.4 + r() * 0.25
    const h = 0.28 + r() * 0.22
    const d = 0.36 + r() * 0.18
    const onTop = k > 2
    const x = (r() - 0.5) * 0.4
    const z = (r() - 0.5) * 0.3
    const y = onTop ? 0.45 + (r() - 0.5) * 0.04 : 0
    const ry = (r() - 0.5) * 0.6
    placed.push([x, y, z])
    b.at({ x, y, z, ry }, () => {
      b.box(w, h, d, { mat: 'plain', color: pick(r, ['#b48c5a', '#a8845a', '#c09a68', '#9a7a52']), y: h / 2, r: 0.006 })
      b.box(0.05, 0.002, d + 0.004, { mat: 'plastic', color: '#c8b080', y: h + 0.001 })
      b.box(0.05, h * 0.4, 0.002, { mat: 'plastic', color: '#c8b080', y: h * 0.8, z: d / 2 + 0.001 })
      if (r() < 0.6) label(b, pick(r, words), w * 0.5, 0.06, { bg: '#b48c5a', fg: '#2a1e14', y: h * 0.5, z: d / 2 + 0.002 })
      if (k === 2) {
        // flaps open on the top box of the bottom row
        for (const s of [-1, 1]) b.box(w, 0.004, d * 0.45, { mat: 'plain', color: '#a8845a', y: h + 0.08, z: s * (d / 2 + 0.06), rx: s * 1.0 })
      }
    })
  }
}

HDD.booth = (b, r) => {
  const col = pick(r, ['#a82a2a', '#2a7a7a', '#c88a20', '#2a4a7a'])
  // table with a chrome edge band on a pedestal
  b.lathe([[0.25, 0], [0.24, 0.03], [0.06, 0.05], [0.05, 0.7], [0.08, 0.72]], { mat: 'chrome', color: '#c8ccd0', seg: 16 })
  b.box(1.2, 0.03, 0.75, { mat: 'tiles', color: '#d4d0c8', y: 0.74, uv: 0.4 })
  b.box(1.21, 0.035, 0.76, { mat: 'chrome', color: '#c8ccd0', y: 0.725 })
  // tufted vinyl benches facing each other
  for (const s of [-1, 1]) {
    b.box(1.3, 0.4, 0.5, { mat: 'paint', color: sh(col, -0.25), y: 0.2, z: s * 0.68 })
    cushion(b, 1.28, 0.1, 0.5, { mat: 'leather', color: col, y: 0.44, z: s * 0.66, r: 0.04 })
    b.box(1.3, 0.62, 0.14, { mat: 'leather', color: col, y: 0.8, z: s * 0.9, r: 0.05 })
    for (let i = 0; i < 6; i++) for (let j = 0; j < 2; j++) b.sphere(0.008, { mat: 'leather', color: sh(col, -0.2), x: -0.5 + i * 0.2, y: 0.7 + j * 0.18, z: s * (0.9 - 0.07), ws: 6, hs: 4 })
  }
  // napkins, condiments, sugar, menus, mugs and a slice of pie
  b.box(0.1, 0.12, 0.08, { mat: 'chrome', color: '#c8ccd0', x: -0.5, y: 0.82, z: 0.0 })
  bottle(b, -0.38, 0.755, -0.05, { h: 0.17, r: 0.025, mat: 'plastic', color: '#c8202a', seg: 7 })
  bottle(b, -0.32, 0.755, 0.02, { h: 0.17, r: 0.025, mat: 'plastic', color: '#e8c020', seg: 7 })
  b.lathe([[0.03, 0], [0.035, 0.08], [0.02, 0.1], [0.015, 0.12]], { mat: 'glass', color: '#e8e8e0', x: -0.44, y: 0.755, z: 0.08, seg: 8 })
  for (const s of [-1, 1]) {
    b.box(0.22, 0.004, 0.3, { mat: 'plain', color: pick(r, ['#c83a2a', '#2a4a7a']), x: 0.3, y: 0.757, z: s * 0.2, ry: s * 0.3 })
    mug(b, 0.05, 0.755, s * 0.22, '#d4d0c8', s)
  }
  plates(b, 0.3, 0.755, 0.0, 1, '#d4d0c8', 0.09)
  b.extrude([[0, 0], [0.07, 0.03], [0.07, -0.03]], 0.04, { mat: 'plain', color: '#c89050', x: 0.3, y: 0.79, z: 0.0, rx: -PI / 2 })
}

HDD.counterDecor = (b, r) => {
  const col = pick(r, ['#c83a2a', '#2a7a7a', '#3a3a3a'])
  // a diner counter: panelled front, tiled top, stools, pie case, coffee
  b.box(2.6, 1.0, 0.62, { mat: 'paint', color: '#c8c4bc', y: 0.5, z: -0.04 })
  for (let k = 0; k < 6; k++) b.box(0.38, 0.8, 0.02, { mat: 'paint', color: sh(col, -0.05), x: -1.08 + k * 0.432, y: 0.5, z: 0.28 })
  b.box(2.6, 0.08, 0.04, { mat: 'chrome', color: '#c8ccd0', y: 0.94, z: 0.28 })
  b.box(2.7, 0.05, 0.8, { mat: 'tiles', color: col, y: 1.05, z: 0.04, uv: 0.4 })
  b.box(2.72, 0.04, 0.82, { mat: 'chrome', color: '#c8ccd0', y: 1.03, z: 0.04 })
  b.box(2.6, 0.08, 0.06, { mat: 'chrome', color: '#a8acb0', y: 0.12, z: 0.36 })
  for (let k = 0; k < 4; k++) {
    const x = -0.99 + k * 0.66
    b.at({ x, z: 0.8 }, () => {
      b.lathe([[0.2, 0], [0.19, 0.03], [0.05, 0.05], [0.04, 0.68], [0.07, 0.7]], { mat: 'chrome', color: '#c8ccd0', seg: 14 })
      b.torus(0.17, 0.008, { mat: 'chrome', color: '#c8ccd0', y: 0.3, rx: PI / 2, rs: 4, ts2: 18 })
      b.cyl(0.2, 0.19, 0.08, { mat: 'leather', color: col, y: 0.75, seg: 18 })
      b.torus(0.19, 0.015, { mat: 'chrome', color: '#c8ccd0', y: 0.72, rx: PI / 2, rs: 4, ts2: 18 })
    })
  }
  // pie case
  b.at({ x: 0.6, y: 1.075, z: 0.0 }, () => {
    b.cyl(0.2, 0.2, 0.02, { mat: 'chrome', color: '#c8ccd0', seg: 18 })
    for (let k = 0; k < 2; k++) b.cyl(0.17, 0.15, 0.06, { mat: 'plain', color: k ? '#c87a3a' : '#a83a3a', y: 0.04 + k * 0.08, seg: 16 })
    b.sphere(0.21, { mat: 'glass', color: '#e0ecec', y: 0.01, ts: 0, tl: PI / 2, sy: 0.9, ws: 18, hs: 8 })
  })
  // coffee machine with two pots, a register, napkins
  b.at({ x: -0.8, y: 1.075, z: -0.18 }, () => {
    b.box(0.5, 0.42, 0.3, { mat: 'metal', color: '#9a9ea2', y: 0.21 })
    for (const s of [-1, 1]) {
      b.cyl(0.075, 0.07, 0.16, { mat: 'glass', color: '#3a2a1e', x: s * 0.12, y: 0.09, z: 0.1, seg: 12 })
      b.cyl(0.08, 0.08, 0.02, { mat: s > 0 ? 'plastic' : 'plastic', color: s > 0 ? '#e86a1a' : '#1a1a1a', x: s * 0.12, y: 0.18, z: 0.1, seg: 12 })
    }
  })
  b.box(0.36, 0.2, 0.3, { mat: 'plastic', color: '#2a2c2e', x: 1.05, y: 1.18, z: -0.1, r: 0.02 })
  b.box(0.08, 0.1, 0.06, { mat: 'chrome', color: '#c8ccd0', x: -0.2, y: 1.125, z: 0.1 })
  for (let k = 0; k < 2; k++) mug(b, 0.1 + k * 0.5, 1.075, 0.3, '#d4d0c8', 1)
}

HDD.hospitalBed = (b, r) => {
  const frame = '#c8ccc8'
  // wheeled base, lifting frame, back section raised
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
    b.cyl(0.05, 0.05, 0.035, { mat: 'rubber', color: '#2a2a2a', x: sx * 0.38, y: 0.05, z: sz * 0.85, rz: PI / 2, seg: 10 })
    b.box(0.04, 0.08, 0.06, { mat: 'chrome', color: '#a8acb0', x: sx * 0.38, y: 0.11, z: sz * 0.85 })
  }
  b.box(0.82, 0.08, 1.9, { mat: 'paint', color: sh(frame, -0.1), y: 0.2 })
  for (const s of [-1, 1]) b.beam([0, 0.22, s * 0.5], [0, 0.55, -s * 0.3], 0.06, 0.04, { mat: 'chrome', color: '#a8acb0' })
  b.box(0.92, 0.06, 1.3, { mat: 'paint', color: frame, y: 0.58, z: 0.3 })
  b.at({ y: 0.6, z: -0.36, rx: 0.5 }, () => {
    b.box(0.92, 0.06, 0.7, { mat: 'paint', color: frame, z: -0.35 })
    b.box(0.88, 0.14, 0.7, { mat: 'cloth', color: '#c8d0d4', y: 0.1, z: -0.35, r: 0.04 })
    pillow(b, 0.56, 0.12, 0.34, { color: '#d4d4ce', y: 0.2, z: -0.5 })
  })
  b.box(0.88, 0.14, 1.28, { mat: 'cloth', color: '#c8d0d4', y: 0.68, z: 0.3, r: 0.04 })
  b.box(0.9, 0.06, 0.95, { mat: 'cloth', color: pick(r, ['#a8c0d0', '#b8c8b8', '#ccc4b4']), y: 0.76, z: 0.45, r: 0.03 })
  // head and foot boards, side rails, controls
  b.box(0.96, 0.6, 0.05, { mat: 'paint', color: sh(frame, -0.05), y: 0.85, z: -0.98, r: 0.02 })
  b.box(0.96, 0.42, 0.05, { mat: 'paint', color: sh(frame, -0.05), y: 0.76, z: 0.98, r: 0.02 })
  for (const s of [-1, 1]) {
    b.box(0.03, 0.22, 0.8, { mat: 'paint', color: frame, x: s * 0.48, y: 0.85, z: 0.25, r: 0.01 })
    for (let k = 0; k < 4; k++) b.box(0.034, 0.03, 0.8, { mat: 'chrome', color: '#a8acb0', x: s * 0.48, y: 0.77 + k * 0.05, z: 0.25 })
  }
  b.box(0.06, 0.12, 0.03, { mat: 'plastic', color: '#3a3c3e', x: 0.5, y: 0.82, z: 0.0 })
  // IV pole with a bag and line, a monitor on a stand
  b.at({ x: 0.66, z: -0.7 }, () => {
    for (let k = 0; k < 5; k++) b.beam([0, 0.05, 0], [Math.cos((k / 5) * TAU) * 0.25, 0.02, Math.sin((k / 5) * TAU) * 0.25], 0.02, 0.02, { mat: 'chrome', color: '#a8acb0' })
    b.cyl(0.012, 0.012, 1.9, { mat: 'chrome', color: '#c8ccd0', y: 0.97, seg: 6 })
    b.beam([0, 1.9, 0], [0.12, 1.88, 0], 0.012, 0.012, { mat: 'chrome', color: '#c8ccd0', round: true })
    b.box(0.1, 0.18, 0.04, { mat: 'glass', color: '#d8e8e8', x: 0.12, y: 1.74, r: 0.015 })
    b.tube([[0.12, 1.64, 0], [0.1, 1.2, 0.05], [-0.3, 0.95, 0.3], [-0.55, 0.85, 0.6]], 0.004, { mat: 'plastic', color: '#d8e8e8', seg: 4, tseg: 12 })
  })
  b.at({ x: -0.66, z: -0.8 }, () => {
    b.cyl(0.2, 0.2, 0.03, { mat: 'plastic', color: '#3a3c3e', y: 0.02, seg: 12 })
    b.cyl(0.02, 0.02, 1.2, { mat: 'chrome', color: '#a8acb0', y: 0.62, seg: 8 })
    b.box(0.32, 0.26, 0.12, { mat: 'plastic', color: '#d4d4ce', y: 1.3, r: 0.02, ry: 0.4 })
    b.box(0.26, 0.18, 0.004, { material: screenMat('green'), x: Math.sin(0.4) * 0.062, y: 1.31, z: Math.cos(0.4) * 0.062, ry: 0.4 })
  })
  if (r() < 0.45) blob(b, 0.4, 0.5, { rnd: r, color: '#4a1414', x: 0.0, y: 0.792, z: 0.3, rx: -PI / 2, t: 0.002 })
}

HDD.curtain = (b, r) => {
  b.box(2.3, 0.04, 0.06, { mat: 'metal', color: '#c8ccd0', y: 2.3 })
  const g = new THREE.PlaneGeometry(2.0, 1.9, 60, 2)
  const p = g.attributes.position
  for (let i = 0; i < p.count; i++) {
    const x = p.getX(i)
    const y = p.getY(i)
    p.setZ(i, Math.sin(x * 22) * 0.035 + Math.sin(x * 7.3) * 0.02 + (y < -0.8 ? (r() - 0.5) * 0.01 : 0))
  }
  g.computeVertexNormals()
  b.add(g, { mat: 'clothDS', color: pick(r, ['#9ab8c0', '#b8c8b0', '#c8b8c0']), y: 1.3 })
  for (let k = 0; k < 18; k++) b.torus(0.018, 0.003, { mat: 'chrome', color: '#c8ccd0', x: -1.0 + k * 0.118, y: 2.27, rs: 3, ts2: 8 })
}

HDD.cellBars = (b, r) => {
  const st = { mat: 'metal', color: '#4a4e52' }
  b.box(2.2, 0.08, 0.1, { ...st, y: 2.32 })
  b.box(2.2, 0.06, 0.1, { ...st, y: 0.03 })
  for (const y of [0.9, 1.6]) b.box(2.2, 0.05, 0.03, { ...st, y })
  for (let k = 0; k < 14; k++) {
    const x = -1.04 + k * 0.16
    b.cyl(0.018, 0.018, 2.26, { mat: 'metal', color: '#5a5e62', x, y: 1.16, seg: 8 })
    for (const y of [0.9, 1.6]) b.cyl(0.024, 0.024, 0.06, { mat: 'metal', color: '#3a3e42', x, y, rx: PI / 2, seg: 8 })
  }
  // the door section: hinge knuckles and a heavy lock box
  for (const y of [0.4, 1.9]) b.cyl(0.03, 0.03, 0.12, { mat: 'metal', color: '#3a3e42', x: -0.12, y, seg: 8 })
  b.box(0.16, 0.3, 0.1, { mat: 'metal', color: '#3a3e42', x: 0.5, y: 1.15 })
  b.box(0.05, 0.08, 0.02, { mat: 'chrome', color: '#a8acb0', x: 0.5, y: 1.2, z: 0.06 })
  for (let k = 0; k < 3; k++) blob(b, 0.08, 0.06, { rnd: r, x: -0.9 + r() * 1.8, y: 0.2 + r() * 1.8, z: 0.051 })
}

HDD.cot = (b, r) => {
  // folding cot: X legs, rails, sagging canvas, a wool blanket and pillow
  for (const z of [-0.82, 0, 0.82]) for (const s of [-1, 1]) b.beam([-0.34, 0.02, z + s * 0.05], [0.34, 0.4, z - s * 0.05], 0.025, 0.025, { mat: 'metal', color: '#6a7058', round: true })
  for (const s of [-1, 1]) b.cyl(0.018, 0.018, 1.95, { mat: 'metal', color: '#6a7058', x: s * 0.36, y: 0.42, rx: PI / 2, seg: 6 })
  b.cloth(0.72, 1.9, { mat: 'canvas', color: pick(r, ['#5a6a4a', '#6a7458', '#4a5240']), y: 0.43, corners: [0, 0, 0, 0], sag: 0.06, segX: 6, segZ: 10, wrinkle: 0.01 })
  b.box(0.7, 0.05, 1.0, { mat: 'knit', color: pick(r, ['#5a4a3a', '#4a4a4a', '#6a3a2a']), y: 0.43, z: 0.35, r: 0.02 })
  b.box(0.4, 0.04, 0.6, { mat: 'knit', color: '#5a4a3a', y: 0.47, z: -0.1, r: 0.015, ry: 0.1 })
  pillow(b, 0.44, 0.1, 0.3, { color: '#c8c4b8', y: 0.44, z: -0.72 })
  for (const s of [-1, 1]) b.box(0.1, 0.16, 0.26, { mat: 'leather', color: '#2a2018', x: 0.48 + s * 0.06, y: 0.08, z: 0.6, r: 0.04 })
}

HDD.forklift = (b, r) => {
  b.at({ ry: PI }, () => drawForklift(b))
}

HDD.schoolDesks = (b, r) => {
  for (let i = 0; i < 2; i++)
    for (let j = 0; j < 2; j++) {
      const x = -0.55 + i * 1.1
      const z = -0.5 + j * 1.0
      b.at({ x, z }, () => {
        // desk top on a tubular frame, book basket below, a chair behind
        b.box(0.66, 0.025, 0.48, { mat: 'wood', color: '#c8a878', y: 0.72, r: 0.006 })
        for (const sx of [-1, 1]) {
          b.beam([sx * 0.29, 0, 0.2], [sx * 0.29, 0.71, 0.18], 0.025, 0.025, { mat: 'metal', color: '#3a5a8a', round: true })
          b.beam([sx * 0.29, 0, -0.2], [sx * 0.29, 0.71, -0.18], 0.025, 0.025, { mat: 'metal', color: '#3a5a8a', round: true })
        }
        b.box(0.58, 0.012, 0.36, { mat: 'metal', color: '#3a5a8a', y: 0.55 })
        if (r() < 0.6) for (let k = 0; k < 3; k++) b.box(0.2, 0.025, 0.28, { mat: 'paint', color: pick(r, BOOK), y: 0.57 + k * 0.026, z: 0.0, ry: (r() - 0.5) * 0.3 })
        b.at({ z: 0.42 }, () => {
          for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.cyl(0.012, 0.012, 0.44, { mat: 'metal', color: '#3a5a8a', x: sx * 0.17, y: 0.22, z: sz * 0.15, seg: 6 })
          b.box(0.4, 0.03, 0.38, { mat: 'plastic', color: '#3a5a8a', y: 0.45, r: 0.01 })
          b.box(0.38, 0.28, 0.025, { mat: 'plastic', color: '#3a5a8a', y: 0.68, z: 0.2, rx: -0.12, r: 0.01 })
        })
        if (r() < 0.5) {
          b.box(0.21, 0.004, 0.29, { mat: 'plain', color: '#d6d2c6', y: 0.735, ry: r() })
          b.cyl(0.004, 0.004, 0.17, { mat: 'paint', color: '#e8b830', x: 0.1, y: 0.738, z: 0.1, rz: PI / 2, ry: r() * 3, seg: 6 })
        }
      })
    }
}

HDD.labBench = (b, r) => {
  const W = 2.0
  carcass(b, W, 0.86, 0.78, { color: '#d2d4d0', inside: '#c8cac6', noTop: true })
  for (let k = 0; k < 4; k++) {
    b.box(0.47, 0.6, 0.02, { mat: 'paint', color: '#c8cac6', x: -0.735 + k * 0.49, y: 0.4, z: 0.4 })
    pull(b, -0.735 + k * 0.49, 0.62, 0.41, { kind: 'bar', len: 0.12 })
    b.box(0.47, 0.18, 0.02, { mat: 'paint', color: '#c8cac6', x: -0.735 + k * 0.49, y: 0.8, z: 0.4 })
  }
  b.box(W + 0.04, 0.035, 0.84, { mat: 'concrete', color: '#2a2c2e', y: 0.88 })
  // sink with gooseneck, gas valves, a reagent shelf on posts
  b.box(0.36, 0.006, 0.3, { mat: 'metal', color: '#3a3c3e', x: 0.72, y: 0.899, z: 0.05 })
  b.tube([[0.72, 0.9, -0.3], [0.72, 1.25, -0.28], [0.72, 1.28, -0.16], [0.72, 1.12, -0.08]], 0.012, { mat: 'chrome', color: '#d0d4d8', seg: 6, tseg: 10 })
  for (let k = 0; k < 3; k++) {
    b.cyl(0.016, 0.016, 0.06, { mat: 'chrome', color: '#d0d4d8', x: -0.6 + k * 0.4, y: 0.93, z: -0.34, seg: 8 })
    b.box(0.04, 0.012, 0.01, { mat: 'plastic', color: ['#e8c020', '#2a5ab8', '#c83a2a'][k], x: -0.6 + k * 0.4, y: 0.965, z: -0.34 })
  }
  for (const s of [-1, 1]) b.box(0.04, 0.9, 0.04, { mat: 'metal', color: '#9a9ea2', x: s * 0.96, y: 1.35, z: -0.34 })
  b.box(W, 0.025, 0.24, { mat: 'paint', color: '#d2d4d0', y: 1.5, z: -0.3 })
  b.at({ z: -0.3 }, () => shelfItems(b, r, 'chem', W - 0.1, 0.2, 1.512, 0.28, 0.8))
  glassSet(b, { x: -0.3, y: 0.898, z: 0.05, seed: Math.floor(r() * 99), n: 5 })
  // a microscope and a burner
  b.at({ x: 0.2, y: 0.898, z: 0.1, ry: -0.4 }, () => {
    b.box(0.16, 0.03, 0.2, { mat: 'paint', color: '#e8e4dc', y: 0.015 })
    b.beam([0, 0.03, -0.07], [0, 0.26, -0.03], 0.04, 0.05, { mat: 'paint', color: '#e8e4dc' })
    b.box(0.1, 0.012, 0.1, { mat: 'paint', color: '#2a2a2a', y: 0.12, z: 0.0 })
    b.cyl(0.02, 0.022, 0.16, { mat: 'paint', color: '#2a2a2a', y: 0.27, z: 0.01, rx: -0.4, seg: 10 })
  })
  b.cyl(0.03, 0.04, 0.02, { mat: 'metal', color: '#3a3c3e', x: -0.75, y: 0.908, z: 0.15, seg: 10 })
  b.cyl(0.008, 0.008, 0.14, { mat: 'chrome', color: '#b8bcc0', x: -0.75, y: 0.97, z: 0.15, seg: 6 })
}

HDD.workbenchDecor = (b, r) => {
  heavyBench(b, { w: 1.8, d: 0.68, h: 0.9, seed: Math.floor(r() * 99), color: pick(r, ['#d8b888', '#c8a070']) })
  benchClutter(b, -0.85, 0.85, -0.25, 0.25, 0.9, r)
  b.at({ x: 0.7, y: 0.9, z: 0.18 }, () => {
    b.box(0.08, 0.06, 0.16, { mat: 'paint', color: '#3a5a8a', y: 0.03 })
    b.box(0.14, 0.07, 0.05, { mat: 'paint', color: '#3a5a8a', y: 0.1, z: 0.04 })
    b.box(0.14, 0.07, 0.05, { mat: 'paint', color: '#3a5a8a', y: 0.1, z: -0.04 })
  })
}

HDD.bench = (b, r) => {
  const col = pick(r, WOOD)
  for (const s of [-1, 1]) {
    b.box(0.05, 0.42, 0.05, { mat: 'metal', color: '#3a3e42', x: s * 0.8, y: 0.21, z: 0.12 })
    b.box(0.05, 0.42, 0.05, { mat: 'metal', color: '#3a3e42', x: s * 0.8, y: 0.21, z: -0.12 })
    b.box(0.05, 0.04, 0.3, { mat: 'metal', color: '#3a3e42', x: s * 0.8, y: 0.4 })
  }
  for (let k = 0; k < 3; k++) b.box(1.8, 0.035, 0.1, { mat: 'wood', color: sh(col, (r() - 0.5) * 0.08), y: 0.44, z: -0.11 + k * 0.11, r: 0.006 })
  if (r() < 0.4) b.box(0.4, 0.06, 0.25, { mat: 'cloth', color: pick(r, ['#2a3a5a', '#8a2a2a', '#3a3a3a']), x: 0.4, y: 0.49, r: 0.03 })
}
HDD.table2 = HDD.table
