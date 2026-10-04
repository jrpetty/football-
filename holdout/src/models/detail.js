// High-detail workshop kit for the camp: hand tools, heavy benches, shop
// machines, conveyors and presses, electrics, lights and markings. Same
// conventions as parts.js: every helper takes a Builder and an options object
// ({ x, y, z, ry, ... }) and builds in the builder's current space. Wall
// items lie in the local xy plane facing +z; bench items stand on y = 0.
import * as THREE from 'three'
import { seeded } from './kit.js'
import { roof } from './stationkit.js'
import { shadeHex, COL } from './parts.js'

const TAU = Math.PI * 2
const pick = (r, a) => a[Math.floor(r() * a.length)]

// ---------------------------------------------------------------- canvas materials
const cmats = new Map()
export function canvasMat(key, size, draw, opts = {}) {
  if (cmats.has(key)) return cmats.get(key)
  const c = document.createElement('canvas')
  c.width = size[0]
  c.height = size[1]
  // painted on the CPU (some read their pixels back): a GPU canvas would
  // stall on every read
  draw(c.getContext('2d', { willReadFrequently: true }), c.width, c.height)
  const t = new THREE.CanvasTexture(c)
  t.colorSpace = THREE.SRGBColorSpace
  t.anisotropy = 8
  t.wrapS = t.wrapT = THREE.RepeatWrapping
  const m = new THREE.MeshStandardMaterial({ map: t, roughness: opts.rough ?? 0.8, metalness: opts.metal ?? 0, transparent: !!opts.alpha && !opts.cutout, alphaTest: opts.alpha ? (opts.cutout ? 0.5 : 0.3) : 0, polygonOffset: !!opts.decal, polygonOffsetFactor: opts.decal ? -2 : 0, side: opts.side ?? THREE.FrontSide })
  // painted boards and labels skip the shared weathering shader, and with it
  // the damping of the sky's reflection on rough paint: take less of it, or
  // a sunlit sign glares white up close
  if (!opts.emissive) m.envMapIntensity = 0.45
  if (opts.emissive) {
    m.emissiveMap = t
    m.emissive = new THREE.Color('#ffffff')
    m.emissiveIntensity = opts.emissive
  }
  cmats.set(key, m)
  return m
}
// Perforated hardboard, 2.5 cm hole pitch; one texture repeat = 0.4 m.
export function pegMat() {
  return canvasMat('peg', [256, 256], (g, w, h) => {
    g.fillStyle = '#9a7a56'
    g.fillRect(0, 0, w, h)
    for (let i = 0; i < 900; i++) {
      g.fillStyle = `rgba(${60 + Math.random() * 40},${40 + Math.random() * 30},20,${Math.random() * 0.12})`
      g.fillRect(Math.random() * w, Math.random() * h, 2 + Math.random() * 30, 1 + Math.random() * 3)
    }
    g.fillStyle = '#2a1e14'
    for (let y = 8; y < h; y += 16) for (let x = 8; x < w; x += 16) {
      g.beginPath()
      g.arc(x, y, 3.2, 0, TAU)
      g.fill()
    }
  })
}
// Yellow and black hazard stripes; one repeat = 0.5 m.
export function stripeMat() {
  return canvasMat('stripe', [256, 64], (g, w, h) => {
    g.fillStyle = '#d8a818'
    g.fillRect(0, 0, w, h)
    g.fillStyle = '#1a1a18'
    for (let x = -h; x < w + h; x += 64) {
      g.beginPath()
      g.moveTo(x, h)
      g.lineTo(x + 32, h)
      g.lineTo(x + 32 + h, 0)
      g.lineTo(x + h, 0)
      g.fill()
    }
    for (let i = 0; i < 300; i++) {
      g.fillStyle = `rgba(60,50,40,${Math.random() * 0.35})`
      g.fillRect(Math.random() * w, Math.random() * h, 1 + Math.random() * 8, 1 + Math.random() * 2)
    }
  }, { rough: 0.7 })
}
// A small printed label or warning plate (opaque).
export function plateMat(text, bg = '#e8e4d8', fg = '#1a1a1a', icon = null) {
  return canvasMat('plate:' + text + bg + fg + icon, [256, 128], (g, w, h) => {
    g.fillStyle = bg
    g.fillRect(0, 0, w, h)
    g.strokeStyle = fg
    g.lineWidth = 6
    g.strokeRect(6, 6, w - 12, h - 12)
    g.fillStyle = fg
    let x0 = w / 2
    if (icon === 'bolt' || icon === 'flame' || icon === 'skull' || icon === 'warn') {
      x0 = w / 2 + 34
      g.save()
      g.translate(52, h / 2)
      g.beginPath()
      g.moveTo(0, -38)
      g.lineTo(40, 32)
      g.lineTo(-40, 32)
      g.closePath()
      g.lineWidth = 7
      g.stroke()
      g.font = '900 44px sans-serif'
      g.textAlign = 'center'
      g.textBaseline = 'middle'
      g.fillText(icon === 'bolt' ? 'ϟ' : icon === 'flame' ? '▲' : '!', 0, 8)
      g.restore()
    }
    g.font = '800 40px "Saira Condensed", "Arial Narrow", sans-serif'
    g.textAlign = 'center'
    g.textBaseline = 'middle'
    g.fillText(text, x0, h / 2 + 2, w - (icon ? 100 : 30))
    for (let i = 0; i < 160; i++) {
      g.fillStyle = `rgba(60,40,20,${Math.random() * 0.2})`
      g.fillRect(Math.random() * w, Math.random() * h, 1 + Math.random() * 10, 1 + Math.random() * 3)
    }
  }, { rough: 0.6 })
}
// Hazard diamond (NFPA style) for chemical drums.
export function diamondMat(kind = 'flame') {
  return canvasMat('dia:' + kind, [128, 128], (g, w, h) => {
    g.clearRect(0, 0, w, h)
    g.save()
    g.translate(w / 2, h / 2)
    g.rotate(Math.PI / 4)
    g.fillStyle = kind === 'flame' ? '#d83a2a' : kind === 'toxic' ? '#f0f0e8' : kind === 'corrosive' ? '#f0f0e8' : '#e8c020'
    g.fillRect(-42, -42, 84, 84)
    g.strokeStyle = '#1a1a1a'
    g.lineWidth = 4
    g.strokeRect(-38, -38, 76, 76)
    g.restore()
    g.fillStyle = '#1a1a1a'
    g.font = '900 40px sans-serif'
    g.textAlign = 'center'
    g.textBaseline = 'middle'
    g.fillText(kind === 'flame' ? '🔥' : kind === 'toxic' ? '☠' : kind === 'corrosive' ? '⚠' : '!', w / 2, h / 2 + 2)
  }, { alpha: true, decal: true, rough: 0.6 })
}
// Shop poster or chart: a few blocks and lines that read as print from afar.
export function posterMat(seed = 1) {
  return canvasMat('poster' + seed, [128, 176], (g, w, h) => {
    const r = seeded(seed)
    g.fillStyle = pick(r, ['#e8e0c8', '#d8d0b8', '#c8d0d0'])
    g.fillRect(0, 0, w, h)
    g.fillStyle = pick(r, ['#b83a2a', '#2a4a7a', '#3a5a3a', '#1a1a1a'])
    g.fillRect(8, 8, w - 16, 30)
    g.fillStyle = '#2a2a2a'
    for (let y = 48; y < h - 12; y += 9) g.fillRect(10, y, (w - 20) * (0.5 + r() * 0.5), 3)
    g.strokeStyle = '#2a2a2a'
    g.lineWidth = 2
    g.strokeRect(14, 96, 44, 44)
    g.beginPath()
    g.moveTo(14, 140)
    g.lineTo(58, 96)
    g.stroke()
  }, { rough: 0.9 })
}
// Paste a canvas-material quad (decals, labels) facing +z.
export function decal(b, mat, w, h, o = {}) {
  b.plane(w, h, { material: mat, x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0, rz: o.rz || 0, shadow: false })
  if (o.repeat) {
    const uv = b.last.attributes.uv
    for (let i = 0; i < uv.count; i++) uv.setXY(i, (uv.getX(i) * w) / o.repeat, (uv.getY(i) * h) / o.repeat)
  }
}

// ---------------------------------------------------------------- hand tools
// All lie in the xy plane (hanging on a wall, +z out) unless rotated.
export function hammer(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0, rz: o.rz || 0 }, () => {
    b.box(0.028, 0.3, 0.022, { mat: 'wood', color: o.handle ?? '#c89a60', y: 0, r: 0.008 })
    b.box(0.032, 0.09, 0.026, { mat: 'rubber', color: '#1e1e1e', y: -0.1, r: 0.01 })
    b.box(0.1, 0.034, 0.03, { mat: 'metal', color: '#4a4e52', x: 0.02, y: 0.155 })
    b.cyl(0.018, 0.018, 0.03, { mat: 'metal', color: '#5a5e62', x: 0.075, y: 0.155, rz: Math.PI / 2, seg: 8 })
    b.beam([-0.03, 0.155, 0], [-0.085, 0.13, 0], 0.03, 0.022, { mat: 'metal', color: '#4a4e52' })
  })
}
export function wrench(b, o = {}) {
  const L = o.len ?? 0.24
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0, rz: o.rz || 0 }, () => {
    b.box(0.02, L, 0.007, { mat: 'chrome', color: '#c8ccd0' })
    b.torus(L * 0.12, 0.009, { mat: 'chrome', color: '#c8ccd0', y: L / 2 + L * 0.1, arc: Math.PI * 1.5, rz: -Math.PI * 0.25, rs: 5, ts2: 12 })
    b.torus(L * 0.09, 0.008, { mat: 'chrome', color: '#c8ccd0', y: -L / 2 - L * 0.07, rs: 5, ts2: 14 })
  })
}
export function screwdriver(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0, rz: o.rz || 0 }, () => {
    b.cyl(0.013, 0.015, 0.1, { mat: 'plastic', color: o.color ?? '#d83a2a', y: 0.05, seg: 8 })
    b.cyl(0.016, 0.016, 0.012, { mat: 'rubber', color: '#1a1a1a', y: 0.085, seg: 8 })
    b.cyl(0.0035, 0.0035, 0.12, { mat: 'chrome', color: '#d0d4d8', y: -0.06, seg: 6 })
  })
}
export function pliers(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0, rz: o.rz || 0 }, () => {
    for (const s of [-1, 1]) {
      b.box(0.018, 0.13, 0.012, { mat: 'plastic', color: o.color ?? '#c83a2a', x: s * 0.02, y: -0.04, rz: -s * 0.12, r: 0.005 })
      b.box(0.012, 0.07, 0.01, { mat: 'metal', color: '#5a5e62', x: s * 0.006, y: 0.07, rz: s * 0.08 })
    }
    b.cyl(0.012, 0.012, 0.016, { mat: 'metal', color: '#5a5e62', y: 0.03, rx: Math.PI / 2, seg: 8 })
  })
}
export function handSaw(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0, rz: o.rz || 0 }, () => {
    b.extrude([[-0.05, -0.24], [0.06, -0.24], [0.08, 0.18], [-0.03, 0.18]], 0.002, { mat: 'chrome', color: '#b8bcc0', curve: 1 })
    b.extrude([[-0.05, 0.16], [0.09, 0.16], [0.1, 0.32], [-0.04, 0.32]], 0.022, { mat: 'wood', color: o.handle ?? '#a8582a', holes: [[[-0.01, 0.2], [0.06, 0.2], [0.06, 0.28], [-0.01, 0.28]]] })
  })
}
export function hacksaw(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0, rz: o.rz || 0 }, () => {
    b.box(0.012, 0.3, 0.012, { mat: 'paint', color: o.color ?? '#2a5ab8', x: -0.08 })
    b.box(0.17, 0.012, 0.012, { mat: 'paint', color: o.color ?? '#2a5ab8', y: 0.15, x: -0.0 })
    b.box(0.012, 0.08, 0.012, { mat: 'paint', color: o.color ?? '#2a5ab8', x: 0.08, y: 0.11 })
    b.box(0.003, 0.28, 0.012, { mat: 'chrome', color: '#c8ccd0', x: -0.065, y: -0.0 })
    b.box(0.03, 0.09, 0.022, { mat: 'rubber', color: '#1a1a1a', x: -0.08, y: -0.18, r: 0.008 })
  })
}
export function cClamp(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0, rz: o.rz || 0 }, () => {
    b.torus(0.06, 0.012, { mat: 'paint', color: o.color ?? '#c8302a', arc: Math.PI * 1.25, rz: Math.PI * 0.375, rs: 5, ts2: 12 })
    b.cyl(0.006, 0.006, 0.13, { mat: 'chrome', color: '#c8ccd0', x: 0.03, y: -0.02, rz: Math.PI / 2, seg: 6 })
    b.box(0.012, 0.05, 0.012, { mat: 'chrome', color: '#c8ccd0', x: 0.1, y: -0.02 })
  })
}
export function level(b, o = {}) {
  const L = o.len ?? 0.6
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0, rz: o.rz || 0 }, () => {
    b.box(L, 0.05, 0.024, { mat: 'paint', color: o.color ?? '#d8a820' })
    for (const x of [-L * 0.35, 0, L * 0.35]) b.box(0.05, 0.018, 0.026, { mat: 'glowGreen', color: '#3a8a3a', x })
  })
}
export function tapeMeasure(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0, rz: o.rz || 0 }, () => {
    b.box(0.07, 0.07, 0.035, { mat: 'plastic', color: '#d8b020', r: 0.015 })
    b.box(0.03, 0.006, 0.02, { mat: 'chrome', color: '#c8ccd0', x: 0.045, y: -0.03 })
  })
}
// Cordless drill, standing on its battery (bench) or hanging.
export function drill(b, o = {}) {
  const c = o.color ?? '#d8a020'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0, rz: o.rz || 0 }, () => {
    b.box(0.07, 0.05, 0.09, { mat: 'plastic', color: '#1e1e1e', y: 0.025, r: 0.012 })
    b.box(0.04, 0.13, 0.05, { mat: 'plastic', color: c, y: 0.11, z: -0.005, rx: 0.15, r: 0.012 })
    b.box(0.05, 0.07, 0.16, { mat: 'plastic', color: c, y: 0.2, z: 0.03, r: 0.02 })
    b.cyl(0.018, 0.016, 0.05, { mat: 'metal', color: '#2a2a2a', y: 0.2, z: 0.13, rx: Math.PI / 2, seg: 10 })
    b.cyl(0.003, 0.003, 0.06, { mat: 'chrome', color: '#c8ccd0', y: 0.2, z: 0.18, rx: Math.PI / 2, seg: 5 })
  })
}
export function angleGrinder(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0, rz: o.rz || 0 }, () => {
    b.cyl(0.032, 0.03, 0.22, { mat: 'plastic', color: o.color ?? '#3a6ab0', y: 0.03, rz: Math.PI / 2, seg: 10 })
    b.box(0.06, 0.05, 0.06, { mat: 'metal', color: '#5a5e62', x: 0.13, y: 0.03 })
    b.cyl(0.058, 0.058, 0.004, { mat: 'plain', color: '#3a3836', x: 0.14, y: 0.0, seg: 18 })
    b.cyl(0.062, 0.062, 0.02, { mat: 'metal', color: '#5a5e62', x: 0.14, y: 0.016, seg: 18, ts: 0, tl: Math.PI })
  })
}
export function solderIron(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.12, 0.06, 0.1, { mat: 'plastic', color: '#2a2a2a', y: 0.03, r: 0.01 })
    b.box(0.05, 0.02, 0.003, { mat: 'glowRed', color: '#5a1410', x: -0.02, y: 0.04, z: 0.051 })
    b.torus(0.022, 0.004, { mat: 'chrome', color: '#c8ccd0', x: 0.03, y: 0.1, z: 0.0, rx: 0.6, rs: 4, ts2: 10 })
    b.beam([0.03, 0.065, 0], [0.03, 0.14, 0.06], 0.012, 0.012, { mat: 'chrome', color: '#c8ccd0', round: true })
    b.beam([0.03, 0.14, 0.06], [0.03, 0.21, 0.13], 0.016, 0.016, { mat: 'plastic', color: '#1e6ab8', round: true })
    b.cyl(0.04, 0.04, 0.01, { mat: 'metal', color: '#c8a858', x: -0.1, y: 0.005, seg: 12 })
  })
}
// A spray of small tools lying on a bench top (y = top surface).
export function benchClutter(b, x0, x1, z0, z1, y, rnd, kinds = ['wrench', 'screw', 'pliers', 'nuts', 'rag', 'can', 'cup']) {
  let x = x0 + 0.05
  while (x < x1 - 0.08) {
    const k = pick(rnd, kinds)
    const z = z0 + rnd() * (z1 - z0)
    const ry = rnd() * TAU
    if (k === 'wrench') wrench(b, { x, y: y + 0.005, z, rx: -Math.PI / 2, rz: ry })
    else if (k === 'screw') screwdriver(b, { x, y: y + 0.016, z, rz: Math.PI / 2, ry, color: pick(rnd, ['#d83a2a', '#e8c030', '#2a6ad8']) })
    else if (k === 'pliers') pliers(b, { x, y: y + 0.008, z, rx: -Math.PI / 2, rz: ry })
    else if (k === 'nuts') for (let i = 0; i < 5; i++) b.cyl(0.01, 0.01, 0.008, { mat: 'metal', color: '#8a8e92', x: x + (rnd() - 0.5) * 0.08, y: y + 0.004, z: z + (rnd() - 0.5) * 0.08, seg: 6 })
    else if (k === 'rag') b.cloth(0.18, 0.14, { mat: 'cloth', color: pick(rnd, ['#a83a2a', '#c8b898', '#3a5a8a', '#6a6a5a']), x, y: y + 0.012, z, ry, sag: -0.01, segX: 4, segZ: 4, wrinkle: 0.04, seed: Math.floor(rnd() * 99) })
    else if (k === 'can') {
      b.cyl(0.03, 0.03, 0.1, { mat: 'paint', color: pick(rnd, ['#c8302a', '#3a6a3a', '#d8a020', '#3a4a8a']), x, y: y + 0.05, z, seg: 10 })
      b.cyl(0.006, 0.006, 0.03, { mat: 'plastic', color: '#1a1a1a', x, y: y + 0.115, z, seg: 6 })
    } else if (k === 'cup') {
      b.lathe([[0.03, 0], [0.035, 0.09], [0.033, 0.09]], { mat: 'gloss', color: pick(rnd, ['#e8e4dc', '#3a5a8a', '#a83a2a']), x, y, z, seg: 12 })
      b.cyl(0.029, 0.029, 0.005, { mat: 'plain', color: '#2a1a10', x, y: y + 0.07, z, seg: 10 })
    } else if (k === 'bolts') for (let i = 0; i < 4; i++) b.cyl(0.006, 0.006, 0.05, { mat: 'metal', color: '#9a9ea2', x: x + (rnd() - 0.5) * 0.1, y: y + 0.006, z: z + (rnd() - 0.5) * 0.06, rz: Math.PI / 2, ry: rnd() * 3, seg: 6 })
    else if (k === 'board') b.box(0.12, 0.004, 0.09, { mat: 'plain', color: '#2a6a3a', x, y: y + 0.004, z, ry })
    x += 0.12 + rnd() * 0.14
  }
}

// ---------------------------------------------------------------- benches
// A heavy workbench: butcher-block top, square legs, aprons, lower shelf,
// optional drawer bank on the right and a steel top. Front faces +z.
export function heavyBench(b, o = {}) {
  const w = o.w ?? 1.8
  const d = o.d ?? 0.75
  const h = o.h ?? 0.9
  const rnd = seeded(o.seed ?? 21)
  const c = o.color ?? '#d8b888'
  const leg = o.metal ? { mat: 'paint', color: o.frame ?? '#4a5056' } : { mat: 'wood', color: shadeHex(c, -0.15) }
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    if (o.steelTop) {
      b.box(w, 0.05, d, { mat: 'metal', color: '#9aa0a4', y: h - 0.025 })
      b.box(w + 0.01, 0.06, 0.02, { mat: 'metal', color: '#8a9094', y: h - 0.03, z: d / 2 })
    } else {
      const n = Math.max(4, Math.round(d / 0.065))
      for (let i = 0; i < n; i++) b.box(w, 0.07, d / n - 0.003, { mat: 'wood', color: shadeHex(c, (rnd() - 0.5) * 0.12), y: h - 0.035, z: -d / 2 + (d / n) * (i + 0.5), jitter: 0.04 })
    }
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.08, h - 0.07, 0.08, { ...leg, x: sx * (w / 2 - 0.07), y: (h - 0.07) / 2, z: sz * (d / 2 - 0.07) })
    for (const sz of [-1, 1]) b.box(w - 0.1, 0.1, 0.03, { ...leg, y: h - 0.12, z: sz * (d / 2 - 0.05) })
    for (const sx of [-1, 1]) b.box(0.03, 0.1, d - 0.1, { ...leg, x: sx * (w / 2 - 0.05), y: h - 0.12 })
    // lower shelf
    if (o.shelf !== false) {
      for (let i = 0; i < 4; i++) b.box(w - 0.12, 0.022, (d - 0.14) / 4 - 0.01, { mat: 'wood', color: shadeHex(c, -0.08 - rnd() * 0.08), y: 0.16, z: -d / 2 + 0.07 + ((d - 0.14) / 4) * (i + 0.5) })
      for (const sz of [-1, 1]) b.box(w - 0.1, 0.05, 0.04, { ...leg, y: 0.14, z: sz * (d / 2 - 0.07) })
    }
    if (o.drawers) {
      const dw = o.drawerW ?? Math.min(0.55, w * 0.32)
      const dx = w / 2 - 0.08 - dw / 2
      const dh = h - 0.2
      b.box(dw, dh, d - 0.12, { mat: o.metal ? 'paint' : 'wood', color: o.metal ? (o.frame ?? '#4a5056') : shadeHex(c, -0.1), x: dx, y: dh / 2 + 0.05 })
      const nd = o.drawers
      for (let i = 0; i < nd; i++) {
        const y = 0.08 + (dh - 0.04) * ((i + 0.5) / nd)
        b.box(dw - 0.04, (dh - 0.04) / nd - 0.02, 0.02, { mat: o.metal ? 'paint' : 'wood', color: o.metal ? shadeHex(o.frame ?? '#4a5056', 0.12) : shadeHex(c, -0.04), x: dx, y, z: d / 2 - 0.05 })
        b.box(0.12, 0.018, 0.02, { mat: 'chrome', color: '#a8acb0', x: dx, y: y + 0.02, z: d / 2 - 0.03 })
      }
    }
  })
  return h
}
// Pegboard on a wall or frame with a full set of outlined tools.
export function pegboard(b, o = {}) {
  const w = o.w ?? 1.4
  const h = o.h ?? 0.9
  const rnd = seeded(o.seed ?? 33)
  b.at({ x: o.x || 0, y: o.y ?? 1.0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(w + 0.04, h + 0.04, 0.02, { mat: 'wood', color: '#6a5038', y: h / 2, z: -0.012 })
    decal(b, pegMat(), w, h, { y: h / 2, z: 0.001, repeat: 0.4 })
    // tool silhouettes painted behind each tool, the old-fashioned way
    let x = -w / 2 + 0.1
    const kinds = o.kinds ?? ['hammer', 'saw', 'wrench', 'wrench', 'screw', 'pliers', 'hacksaw', 'clamp', 'level', 'tape', 'drill', 'grinder']
    let i = 0
    while (x < w / 2 - 0.08) {
      const k = kinds[i % kinds.length]
      const y = h * (0.42 + rnd() * 0.18)
      const z = 0.022
      let step = 0.14
      if (k === 'hammer') hammer(b, { x, y, z })
      else if (k === 'saw') {
        handSaw(b, { x: x + 0.02, y: y - 0.02, z: 0.012 })
        step = 0.18
      } else if (k === 'wrench') {
        for (let j = 0; j < 3; j++) wrench(b, { x: x + j * 0.035, y: y + 0.02, z, len: 0.16 + j * 0.04 })
        step = 0.14
      } else if (k === 'screw') {
        for (let j = 0; j < 4; j++) screwdriver(b, { x: x + j * 0.03, y: y + 0.06, z, color: ['#d83a2a', '#e8c030', '#2a6ad8', '#1a1a1a'][j] })
        step = 0.14
      } else if (k === 'pliers') pliers(b, { x, y, z })
      else if (k === 'hacksaw') {
        hacksaw(b, { x: x + 0.04, y, z })
        step = 0.2
      } else if (k === 'clamp') cClamp(b, { x, y, z })
      else if (k === 'level') {
        level(b, { x: x + 0.15, y: h * 0.86, z, len: 0.5 })
        step = 0.02
      } else if (k === 'tape') tapeMeasure(b, { x, y: y - 0.1, z: 0.03 })
      else if (k === 'drill') {
        drill(b, { x, y: y - 0.12, z: 0.06, ry: Math.PI / 2 })
        step = 0.18
      } else if (k === 'grinder') {
        angleGrinder(b, { x: x - 0.04, y: y - 0.05, z: 0.04, rx: Math.PI / 2 })
        step = 0.26
      }
      // pegs
      b.cyl(0.004, 0.004, 0.04, { mat: 'steel', color: '#c8ccd0', x, y: y + 0.13, z: 0.02, rx: Math.PI / 2, seg: 4 })
      x += step + rnd() * 0.03
      i++
    }
    // a shelf of jars along the bottom
    if (o.shelf !== false) {
      b.box(w, 0.025, 0.14, { mat: 'wood', color: '#b89870', y: 0.06, z: 0.07 })
      for (let k = 0; k < Math.floor(w / 0.11); k++) {
        const jx = -w / 2 + 0.06 + k * 0.11
        b.cyl(0.035, 0.035, 0.09, { mat: 'glass', color: '#d8e0d8', x: jx, y: 0.12, z: 0.07, seg: 10 })
        b.cyl(0.03, 0.03, 0.05, { mat: 'metal', color: pick(rnd, ['#8a8e92', '#b8a070', '#6a6e72']), x: jx, y: 0.1, z: 0.07, seg: 8 })
        b.cyl(0.037, 0.037, 0.02, { mat: 'paint', color: pick(rnd, ['#c8302a', '#2a5a9a', '#d8a020']), x: jx, y: 0.175, z: 0.07, seg: 10 })
      }
    }
  })
}
// Rolling tool chest: top box with lid and drawers over a drawer cabinet.
export function toolChest(b, o = {}) {
  const c = o.color ?? '#b8302a'
  const w = o.w ?? 0.7
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(w, 0.72, 0.46, { mat: 'paint', color: c, y: 0.44, r: 0.01 })
    b.box(w - 0.02, 0.32, 0.42, { mat: 'paint', color: c, y: 0.98, r: 0.01 })
    b.box(w + 0.01, 0.04, 0.46, { mat: 'paint', color: shadeHex(c, -0.25), y: 1.16, r: 0.01 })
    const rows = [[0.14, 5], [0.06, 1], [0.06, 1]]
    for (let i = 0; i < 6; i++) {
      const y = 0.16 + i * 0.11
      b.box(w - 0.06, 0.095, 0.012, { mat: 'paint', color: shadeHex(c, 0.06), y, z: 0.235 })
      b.box(w - 0.12, 0.016, 0.02, { mat: 'chrome', color: '#b8bcc0', y: y + 0.03, z: 0.25 })
    }
    for (let i = 0; i < 3; i++) {
      const y = 0.88 + i * 0.075
      b.box(w - 0.08, 0.06, 0.012, { mat: 'paint', color: shadeHex(c, 0.06), y, z: 0.215 })
      b.box(w - 0.16, 0.012, 0.02, { mat: 'chrome', color: '#b8bcc0', y: y + 0.015, z: 0.23 })
    }
    void rows
    for (const sx of [-1, 1]) {
      b.box(0.02, 0.03, 0.3, { mat: 'chrome', color: '#b8bcc0', x: sx * (w / 2 + 0.03), y: 0.7 })
      for (const sz of [-1, 1]) {
        b.cyl(0.04, 0.04, 0.025, { mat: 'rubber', color: '#1a1a1a', x: sx * (w / 2 - 0.06), y: 0.04, z: sz * 0.17, rz: Math.PI / 2, seg: 10 })
        b.box(0.04, 0.03, 0.05, { mat: 'metal', color: '#3a3a3a', x: sx * (w / 2 - 0.06), y: 0.08, z: sz * 0.17 })
      }
    }
    decal(b, plateMat('TOOLS', shadeHex(c, -0.3), '#e8e4d8'), 0.2, 0.06, { y: 1.08, z: 0.212 })
  })
}
// Wall rack of stacking parts bins.
export function partsBins(b, o = {}) {
  const cols = o.cols ?? 4
  const rows = o.rows ?? 4
  const rnd = seeded(o.seed ?? 41)
  const cw = 0.16
  const rh = 0.12
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(cols * (cw + 0.01) + 0.04, rows * (rh + 0.01) + 0.04, 0.02, { mat: 'paint', color: '#6a6e72', y: (rows * (rh + 0.01)) / 2, z: -0.1 })
    for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) {
      const x = -((cols - 1) * (cw + 0.01)) / 2 + c * (cw + 0.01)
      const y = 0.02 + r * (rh + 0.01) + rh / 2
      const col = o.color ?? pick(rnd, ['#2a5ab8', '#d8a020', '#c8302a', '#2a8a4a'])
      b.box(cw, rh, 0.18, { mat: 'plastic', color: col, x, y })
      b.box(cw - 0.02, rh * 0.5, 0.02, { mat: 'plastic', color: shadeHex(col, -0.15), x, y: y + rh * 0.1, z: 0.09, rx: -0.4 })
      b.box(cw - 0.03, 0.02, 0.003, { mat: 'plain', color: '#e8e4d8', x, y: y - rh * 0.25, z: 0.092, ao: 0 })
      // contents peeking out
      if (rnd() < 0.8) b.box(cw - 0.04, 0.02, 0.12, { mat: 'metal', color: pick(rnd, ['#8a8e92', '#b8a070', '#6a6e72']), x, y: y + rh * 0.35, z: -0.01 })
    }
  })
}
// Articulated bench lamp; returns the bulb position for a light anchor.
export function benchLamp(b, o = {}) {
  const c = o.color ?? '#2a4a3a'
  let tip = [0, 0, 0]
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.cyl(0.07, 0.08, 0.025, { mat: 'paint', color: c, y: 0.012, seg: 14 })
    b.beam([0, 0.02, 0], [0.04, 0.42, -0.06], 0.018, 0.018, { mat: 'paint', color: c, round: true })
    b.beam([0.04, 0.42, -0.06], [0.06, 0.55, 0.22], 0.016, 0.016, { mat: 'paint', color: c, round: true })
    b.sphere(0.022, { mat: 'chrome', color: '#a8acb0', x: 0.04, y: 0.42, z: -0.06 })
    b.at({ x: 0.06, y: 0.55, z: 0.22, rx: 0.6 }, () => {
      b.lathe([[0.02, 0.06], [0.05, 0.02], [0.08, -0.06], [0.082, -0.07]], { mat: 'paint', color: c, seg: 14 })
      b.sphere(0.03, { mat: o.glow ?? 'glowWarm', color: '#ffffff', y: -0.04, shadow: false, ws: 8, hs: 6 })
    })
  })
  tip = [(o.x || 0) + 0.06, (o.y || 0) + 0.5, (o.z || 0) + 0.24]
  return tip
}
// Hanging fluorescent fixture (two tubes) on chains.
export function fluoroLight(b, o = {}) {
  const L = o.len ?? 1.2
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(L, 0.06, 0.16, { mat: 'paint', color: '#d8d8d0', y: 0.03 })
    for (const z of [-0.04, 0.04]) b.cyl(0.014, 0.014, L - 0.06, { mat: o.glow ?? 'nightGlow', color: '#ffffff', z, y: -0.005, rz: Math.PI / 2, seg: 8, shadow: false })
    for (const x of [-L / 2 + 0.1, L / 2 - 0.1]) b.cyl(0.003, 0.003, o.chain ?? 0.5, { mat: 'steel', color: '#6a6a6a', x, y: 0.06 + (o.chain ?? 0.5) / 2, seg: 4 })
  })
}
// Electrical panel with breakers, a conduit run up and a warning plate.
export function elecPanel(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y ?? 1.0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.4, 0.55, 0.14, { mat: 'paint', color: o.color ?? '#8a9094', r: 0.01 })
    b.box(0.36, 0.5, 0.01, { mat: 'paint', color: shadeHex(o.color ?? '#8a9094', 0.08), z: 0.072 })
    b.box(0.03, 0.08, 0.02, { mat: 'metal', color: '#3a3a3a', x: 0.15, z: 0.08 })
    decal(b, plateMat('DANGER 240V', '#e8c020', '#1a1a1a', 'bolt'), 0.2, 0.1, { y: 0.14, z: 0.078 })
    b.cyl(0.02, 0.02, o.conduit ?? 1.2, { mat: 'metal', color: '#8a8e92', y: 0.275 + (o.conduit ?? 1.2) / 2, x: -0.12, seg: 8 })
    b.cyl(0.02, 0.02, 0.4, { mat: 'metal', color: '#8a8e92', y: -0.475, x: 0.1, seg: 8 })
  })
}
// A sagging cable through points [[x,y,z],...].
export function cable(b, pts, o = {}) {
  b.rope(pts, o.r ?? 0.012, { mat: 'rubber', color: o.color ?? '#1a1a1a', sag: o.sag ?? 0.12, steps: o.steps ?? 8 })
}
// Straight pipe run with flanged joints and elbows at corners.
export function pipeRun(b, pts, r, o = {}) {
  const m = { mat: o.mat ?? 'metal', color: o.color ?? '#8a8e92' }
  for (let i = 0; i < pts.length - 1; i++) b.beam(pts[i], pts[i + 1], r * 2, r * 2, { ...m, round: true })
  for (let i = 0; i < pts.length; i++) {
    const p = pts[i]
    if (i > 0 && i < pts.length - 1) b.sphere(r * 1.15, { ...m, x: p[0], y: p[1], z: p[2], ws: 10, hs: 8 })
    if (o.flanges !== false && (i === 0 || i === pts.length - 1)) {
      const q = pts[i === 0 ? 1 : i - 1]
      const t = 0.02 / Math.max(0.01, Math.hypot(q[0] - p[0], q[1] - p[1], q[2] - p[2]))
      b.beam([p[0] + (q[0] - p[0]) * t * 0, p[1] + (q[1] - p[1]) * t * 0, p[2] + (q[2] - p[2]) * t * 0], [p[0] + (q[0] - p[0]) * t * 2, p[1] + (q[1] - p[1]) * t * 2, p[2] + (q[2] - p[2]) * t * 2], r * 3.2, r * 3.2, { ...m, round: true })
    }
  }
}
// Industrial push-button station on a pedestal, with a status lamp.
export function controlPanel(b, o = {}) {
  const rnd = seeded(o.seed ?? 51)
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.08, 0.95, 0.08, { mat: 'paint', color: '#4a4e52', y: 0.475 })
    b.box(0.36, 0.06, 0.3, { mat: 'paint', color: '#4a4e52', y: 0.03 })
    b.at({ y: 1.05, rx: -0.5 }, () => {
      b.box(0.42, 0.3, 0.14, { mat: 'paint', color: o.color ?? '#d8c8a0', r: 0.01 })
      b.box(0.38, 0.26, 0.01, { mat: 'plain', color: '#2a2a2a', z: 0.072 })
      b.box(0.14, 0.1, 0.012, { mat: 'glowGreen', color: '#0a3a1a', x: -0.1, y: 0.05, z: 0.075 })
      for (let i = 0; i < 4; i++) b.cyl(0.018, 0.018, 0.025, { mat: 'plastic', color: ['#2a9a3a', '#c8302a', '#d8a020', '#e8e4dc'][i], x: 0.04 + (i % 2) * 0.07, y: 0.06 - Math.floor(i / 2) * 0.07, z: 0.08, rx: Math.PI / 2, seg: 10 })
      b.cyl(0.035, 0.03, 0.04, { mat: 'plastic', color: '#c8201a', x: -0.1, y: -0.06, z: 0.085, rx: Math.PI / 2, seg: 14 })
      b.cyl(0.04, 0.04, 0.01, { mat: 'paint', color: '#e8c020', x: -0.1, y: -0.06, z: 0.076, rx: Math.PI / 2, seg: 14 })
      void rnd
    })
    // stack light
    b.cyl(0.012, 0.012, 0.2, { mat: 'steel', color: '#8a8e92', x: 0.16, y: 1.3, z: -0.05, seg: 6 })
    for (const [i, g] of ['glowRed', 'glowAmber', 'glowGreen'].entries()) b.cyl(0.035, 0.035, 0.06, { mat: g, color: ['#5a1410', '#6a4410', '#104a1a'][i], x: 0.16, y: 1.43 + (2 - i) * 0.065, z: -0.05, seg: 12 })
  })
}
// Rotating amber beacon in a pivot named o.name (spin anim).
export function beacon(b, I, o = {}) {
  const name = o.name ?? 'beacon'
  b.cyl(0.05, 0.06, 0.04, { mat: 'paint', color: '#2a2a2a', x: o.x || 0, y: o.y || 0, z: o.z || 0, seg: 12 })
  b.pivot(name, { x: o.x || 0, y: (o.y || 0) + 0.02, z: o.z || 0 }, (p) => {
    p.cyl(0.045, 0.045, 0.09, { mat: 'glass', color: '#ffb040', y: 0.05, seg: 12 })
    p.box(0.06, 0.05, 0.02, { mat: 'glowAmber', color: '#ffffff', y: 0.05, z: 0.015 })
  })
  I.anims.push({ name, kind: 'spin', speed: 5, when: o.when ?? 'active' })
}

// ---------------------------------------------------------------- shop machines
// Benchtop / floor drill press facing +z. o.floor: tall column on the ground.
export function drillPress(b, I, o = {}) {
  const c = o.color ?? '#3a6a5a'
  const H = o.floor ? 1.7 : 0.75
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.34, 0.05, 0.44, { mat: 'paint', color: shadeHex(c, -0.15), y: 0.025, z: 0.02 })
    b.cyl(0.04, 0.04, H, { mat: 'chrome', color: '#b8bcc0', y: H / 2, z: -0.12, seg: 12 })
    // table on the column
    b.cyl(0.13, 0.13, 0.03, { mat: 'paint', color: shadeHex(c, -0.1), y: H * 0.55, z: 0.06, seg: 18 })
    b.box(0.08, 0.08, 0.16, { mat: 'paint', color: shadeHex(c, -0.1), y: H * 0.55 - 0.05, z: -0.06 })
    // head with belt guard and motor behind
    b.box(0.22, 0.24, 0.42, { mat: 'paint', color: c, y: H + 0.06, z: 0.02, r: 0.03 })
    b.box(0.26, 0.08, 0.5, { mat: 'paint', color: shadeHex(c, 0.08), y: H + 0.21, z: 0.02, r: 0.03 })
    b.cyl(0.08, 0.08, 0.22, { mat: 'paint', color: shadeHex(c, -0.2), y: H + 0.04, z: -0.28, seg: 14 })
    b.cyl(0.03, 0.025, 0.12, { mat: 'chrome', color: '#c8ccd0', y: H - 0.12, z: 0.12, seg: 10 })
    b.cyl(0.006, 0.006, 0.1, { mat: 'chrome', color: '#d0d4d8', y: H - 0.23, z: 0.12, seg: 6 })
    // feed handles
    b.pivot(o.name ?? 'feed', { x: 0.13, y: H + 0.02, z: 0.06 }, (p) => {
      for (let i = 0; i < 3; i++) {
        const a = (i / 3) * TAU
        p.beam([0, 0, 0], [0.0, Math.cos(a) * 0.18, Math.sin(a) * 0.18], 0.012, 0.012, { mat: 'chrome', color: '#c8ccd0', round: true })
        p.sphere(0.022, { mat: 'plastic', color: '#1a1a1a', y: Math.cos(a) * 0.18, z: Math.sin(a) * 0.18 })
      }
      p.cyl(0.03, 0.03, 0.04, { mat: 'chrome', color: '#c8ccd0', rz: Math.PI / 2, seg: 10 })
    })
    if (I) I.anims.push({ name: o.name ?? 'feed', kind: 'pump', axis: 'x', amp: 0.5, speed: 1.6, when: 'active' })
    b.box(0.003, 0.05, 0.08, { mat: 'plain', color: '#e8e4d8', x: 0.111, y: H + 0.06, z: 0.12, ao: 0 })
  })
}
// Bench grinder: motor with two guarded wheels and spark guards. Faces +z.
export function benchGrinder(b, o = {}) {
  const c = o.color ?? '#5a7a8a'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.18, 0.04, 0.16, { mat: 'paint', color: shadeHex(c, -0.2), y: 0.02 })
    b.cyl(0.07, 0.08, 0.1, { mat: 'paint', color: shadeHex(c, -0.2), y: 0.07, seg: 12 })
    b.cyl(0.075, 0.075, 0.22, { mat: 'paint', color: c, y: 0.18, rz: Math.PI / 2, seg: 16 })
    for (const s of [-1, 1]) {
      b.cyl(0.11, 0.11, 0.05, { mat: 'paint', color: shadeHex(c, -0.08), x: s * 0.16, y: 0.18, rz: Math.PI / 2, seg: 18, ts: Math.PI * 0.75, tl: Math.PI * 1.5 })
      b.cyl(0.095, 0.095, 0.03, { mat: 'concrete', color: s > 0 ? '#8a8a84' : '#b8a888', x: s * 0.16, y: 0.18, rz: Math.PI / 2, seg: 18 })
      b.box(0.06, 0.008, 0.05, { mat: 'metal', color: '#4a4a48', x: s * 0.16, y: 0.13, z: 0.1 })
      b.box(0.09, 0.05, 0.003, { mat: 'glass', color: '#d8e0e0', x: s * 0.16, y: 0.3, z: 0.08, rx: 0.4 })
    }
  })
}
// Small metal lathe on a cabinet stand. Faces +z; o.name: chuck pivot (spin).
export function metalLathe(b, I, o = {}) {
  const c = o.color ?? '#4a6a7a'
  const L = o.len ?? 1.3
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    // stand: two cabinets and a chip tray
    for (const s of [-1, 1]) b.box(0.32, 0.72, 0.42, { mat: 'paint', color: shadeHex(c, -0.2), x: s * (L / 2 - 0.2), y: 0.36 })
    b.box(L, 0.04, 0.5, { mat: 'metal', color: '#6a6e72', y: 0.74 })
    // bed with ways
    b.box(L - 0.1, 0.12, 0.22, { mat: 'paint', color: c, y: 0.82, z: -0.04 })
    for (const z of [-0.12, 0.04]) b.box(L - 0.12, 0.02, 0.04, { mat: 'chrome', color: '#b8bcc0', y: 0.89, z })
    // headstock
    b.box(0.32, 0.3, 0.3, { mat: 'paint', color: c, x: -L / 2 + 0.2, y: 0.99, z: -0.04, r: 0.02 })
    b.box(0.3, 0.12, 0.012, { mat: 'plain', color: '#d8d4c8', x: -L / 2 + 0.2, y: 1.04, z: 0.112, ao: 0 })
    for (let i = 0; i < 3; i++) b.cyl(0.018, 0.018, 0.04, { mat: 'plastic', color: '#1a1a1a', x: -L / 2 + 0.12 + i * 0.08, y: 0.9, z: 0.12, rx: Math.PI / 2, seg: 8 })
    b.pivot(o.name ?? 'chuck', { x: -L / 2 + 0.4, y: 1.0, z: -0.04 }, (p) => {
      p.cyl(0.09, 0.09, 0.08, { mat: 'chrome', color: '#a8acb0', rz: Math.PI / 2, seg: 18 })
      for (let i = 0; i < 3; i++) {
        const a = (i / 3) * TAU
        p.box(0.05, 0.03, 0.03, { mat: 'metal', color: '#6a6e72', x: 0.05, y: Math.cos(a) * 0.06, z: Math.sin(a) * 0.06, rx: a })
      }
      p.cyl(0.03, 0.03, 0.3, { mat: 'metal', color: '#c8b070', x: 0.2, rz: Math.PI / 2, seg: 12 })
    })
    if (I) I.anims.push({ name: o.name ?? 'chuck', kind: 'spin', axis: 'x', speed: 14, when: 'active' })
    // tailstock and carriage
    b.box(0.18, 0.2, 0.18, { mat: 'paint', color: c, x: L / 2 - 0.16, y: 0.98, z: -0.04, r: 0.015 })
    b.cyl(0.025, 0.025, 0.16, { mat: 'chrome', color: '#c8ccd0', x: L / 2 - 0.3, y: 1.0, z: -0.04, rz: Math.PI / 2, seg: 10 })
    b.torus(0.06, 0.008, { mat: 'chrome', color: '#c8ccd0', x: L / 2 - 0.02, y: 1.0, z: -0.04, ry: Math.PI / 2 })
    b.box(0.26, 0.1, 0.34, { mat: 'paint', color: shadeHex(c, 0.08), x: 0.05, y: 0.94, z: 0.02 })
    b.box(0.08, 0.06, 0.08, { mat: 'metal', color: '#4a4a48', x: 0.05, y: 1.02, z: 0.02 })
    b.torus(0.07, 0.008, { mat: 'chrome', color: '#c8ccd0', x: 0.05, y: 0.94, z: 0.21, rs: 5, ts2: 16 })
    b.box(0.012, 0.12, 0.012, { mat: 'chrome', color: '#c8ccd0', x: 0.12, y: 0.94, z: 0.21 })
    // swarf curls in the tray
    for (let i = 0; i < 6; i++) b.torus(0.02, 0.003, { mat: 'chrome', color: '#d8c890', x: -0.2 + i * 0.09, y: 0.77, z: 0.12, rx: i, rs: 3, ts2: 8 })
  })
}
// Hydraulic shop press: H-frame, cylinder on the crown, ram in pivot o.name.
export function shopPress(b, I, o = {}) {
  const c = o.color ?? '#c8302a'
  const w = o.w ?? 0.9
  const H = o.h ?? 1.9
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (const s of [-1, 1]) {
      b.box(0.1, H, 0.12, { mat: 'paint', color: c, x: s * (w / 2), y: H / 2 })
      b.box(0.3, 0.05, 0.5, { mat: 'paint', color: shadeHex(c, -0.2), x: s * (w / 2), y: 0.025 })
      b.beam([s * (w / 2), 0.05, 0.22], [s * (w / 2), 0.6, 0.0], 0.06, 0.06, { mat: 'paint', color: shadeHex(c, -0.2) })
      b.beam([s * (w / 2), 0.05, -0.22], [s * (w / 2), 0.6, 0.0], 0.06, 0.06, { mat: 'paint', color: shadeHex(c, -0.2) })
    }
    b.box(w + 0.12, 0.16, 0.16, { mat: 'paint', color: c, y: H - 0.08 })
    b.box(w - 0.06, 0.1, 0.24, { mat: 'paint', color: shadeHex(c, -0.1), y: H * 0.42 })
    b.box(w * 0.5, 0.04, 0.2, { mat: 'metal', color: '#7a7e82', y: H * 0.42 + 0.07 })
    b.cyl(0.08, 0.08, 0.3, { mat: 'paint', color: '#2a2a2a', y: H + 0.12, seg: 14 })
    b.pivot(o.name ?? 'ram', { y: H - 0.16 }, (p) => {
      p.cyl(0.04, 0.04, 0.5, { mat: 'chrome', color: '#d0d4d8', y: -0.25, seg: 12 })
      p.box(0.18, 0.06, 0.16, { mat: 'metal', color: '#5a5e62', y: -0.52 })
    })
    if (I) I.anims.push({ name: o.name ?? 'ram', kind: 'press', axis: 'y', amp: o.amp ?? 0.18, speed: o.speed ?? 0.7, when: 'active' })
    // pump and gauge
    b.box(0.2, 0.2, 0.14, { mat: 'paint', color: '#2a2a2a', x: w / 2 + 0.2, y: 0.95 })
    b.cyl(0.05, 0.05, 0.02, { mat: 'chrome', color: '#e8e4dc', x: w / 2 + 0.2, y: 1.12, z: 0.06, rx: Math.PI / 2, seg: 14 })
    b.rope([[w / 2 + 0.2, 1.05, 0], [w / 2 + 0.08, 1.6, 0.05], [0.05, H + 0.25, 0.05]], 0.012, { mat: 'rubber', color: '#1a1a1a', sag: 0.05 })
    b.box(0.2, 0.006, 0.15, { material: stripeMat(), y: H * 0.42 + 0.051, x: -w * 0.32, ao: 0 })
  })
}
// Conveyor: rollers, belt and side rails on legs, running along +x. Items
// ride in pivot o.name (conveyor anim slides them along and wraps).
export function conveyor(b, I, o = {}) {
  const L = o.len ?? 2
  const W = o.w ?? 0.45
  const H = o.h ?? 0.75
  const rnd = seeded(o.seed ?? 61)
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (const s of [-1, 1]) {
      b.box(L, 0.1, 0.03, { mat: 'paint', color: o.color ?? '#d8a020', y: H, z: s * (W / 2 + 0.015) })
      b.box(L, 0.02, 0.05, { mat: 'paint', color: o.color ?? '#d8a020', y: H + 0.05, z: s * (W / 2 + 0.03) })
    }
    b.box(L - 0.06, 0.025, W - 0.02, { mat: 'rubber', color: '#262626', y: H + 0.03 })
    for (const x of [-L / 2 + 0.03, L / 2 - 0.03]) b.cyl(0.045, 0.045, W, { mat: 'metal', color: '#6a6e72', x, y: H, rx: Math.PI / 2, seg: 12 })
    const nl = Math.max(2, Math.round(L / 1.1) + 1)
    for (let i = 0; i < nl; i++) {
      const x = -L / 2 + 0.12 + (i / (nl - 1)) * (L - 0.24)
      for (const s of [-1, 1]) b.box(0.05, H - 0.05, 0.05, { mat: 'paint', color: '#4a4e52', x, y: (H - 0.05) / 2, z: s * (W / 2 + 0.01) })
      b.box(0.04, 0.04, W, { mat: 'paint', color: '#4a4e52', x, y: 0.25 })
      for (const s of [-1, 1]) b.box(0.1, 0.02, 0.1, { mat: 'metal', color: '#3a3a3a', x, y: 0.01, z: s * (W / 2 + 0.01) })
    }
    if (o.items) {
      const gap = o.gap ?? 0.5
      b.pivot(o.name ?? 'belt', { y: H + 0.045 }, (p) => {
        for (let x = -L / 2 + 0.2; x < L / 2 - 0.2; x += gap) o.items(p, x, rnd)
      })
      if (I) I.anims.push({ name: o.name ?? 'belt', kind: 'conveyor', axis: 'x', amp: gap, speed: o.speed ?? 0.35, when: 'active' })
    }
  })
}
// Hopper (inverted pyramid) on legs, open top, with contents.
export function hopper(b, o = {}) {
  const w = o.w ?? 0.7
  const H = o.h ?? 1.3
  const c = o.color ?? '#8a9094'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.lathe([[0.08, H - 0.45], [w * 0.7, H], [w * 0.72, H + 0.02]], { mat: 'paint', color: c, seg: 4, ry: Math.PI / 4 })
    b.lathe([[w * 0.7, H + 0.02], [w * 0.68, H + 0.01], [0.09, H - 0.43]], { mat: 'paint', color: shadeHex(c, -0.3), seg: 4, ry: Math.PI / 4 })
    b.cyl(0.08, 0.08, 0.2, { mat: 'paint', color: c, y: H - 0.55, seg: 10 })
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.05, H, 0.05, { mat: 'paint', color: '#4a4e52', x: sx * w * 0.45, y: H / 2, z: sz * w * 0.45 })
    if (o.fill) b.lathe([[0.001, H - 0.08], [w * 0.62, H - 0.08], [w * 0.4, H - 0.02], [0.001, H + 0.05]], { mat: o.fillMat ?? 'gravel', color: o.fill, seg: 4, ry: Math.PI / 4 })
  })
}
// A guard cage of square tube and mesh around a machine.
export function cage(b, w, d, h, o = {}) {
  const m = { mat: 'paint', color: o.color ?? '#3a3e42' }
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.05, h, 0.05, { ...m, x: (sx * w) / 2, y: h / 2, z: (sz * d) / 2 })
    for (const y of [0.1, h]) {
      for (const sz of [-1, 1]) b.box(w, 0.04, 0.04, { ...m, y, z: (sz * d) / 2 })
      for (const sx of [-1, 1]) b.box(0.04, 0.04, d, { ...m, x: (sx * w) / 2, y })
    }
    const skip = o.open ?? []
    const sides = [['z', 1, w], ['z', -1, w], ['x', 1, d], ['x', -1, d]]
    for (const [ax, s, len] of sides) {
      if (skip.includes(ax + s)) continue
      const n = Math.round(len / 0.1)
      for (let i = 1; i < n; i++) {
        const t = -len / 2 + (i / n) * len
        if (ax === 'z') b.box(0.008, h - 0.12, 0.008, { ...m, x: t, y: h / 2 + 0.05, z: (s * d) / 2 })
        else b.box(0.008, h - 0.12, 0.008, { ...m, x: (s * w) / 2, y: h / 2 + 0.05, z: t })
      }
    }
  })
}

// ---------------------------------------------------------------- stock and storage
// Steel drum with a hazard diamond and stencil.
export function drum(b, o = {}) {
  const c = o.color ?? '#2a5a8a'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0, rz: o.rz || 0, rx: o.rx || 0 }, () => {
    b.cyl(0.29, 0.29, 0.88, { mat: 'paint', color: c, y: 0.44, seg: 20 })
    for (const y of [0.29, 0.59]) b.torus(0.292, 0.012, { mat: 'paint', color: shadeHex(c, -0.15), y, rx: Math.PI / 2, ts2: 28, rs: 5 })
    for (const y of [0.012, 0.868]) b.torus(0.284, 0.014, { mat: 'paint', color: shadeHex(c, -0.25), y, rx: Math.PI / 2, ts2: 28, rs: 5 })
    b.cyl(0.03, 0.03, 0.02, { mat: 'metal', color: '#8a8e92', y: 0.885, x: 0.16, seg: 10 })
    b.cyl(0.02, 0.02, 0.02, { mat: 'metal', color: '#8a8e92', y: 0.885, x: -0.16, z: 0.05, seg: 8 })
    if (o.hazard) b.plane(0.2, 0.2, { material: diamondMat(o.hazard), y: 0.5, z: 0.296, shadow: false })
  })
}
// Olive ammo can with a lid latch and stencil.
export function ammoCan(b, o = {}) {
  const c = o.color ?? '#4e5a3a'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.28, 0.18, 0.13, { mat: 'paint', color: c, y: 0.09, r: 0.008 })
    b.box(0.29, 0.03, 0.14, { mat: 'paint', color: shadeHex(c, -0.12), y: 0.18 })
    b.box(0.1, 0.012, 0.02, { mat: 'metal', color: '#3a3a36', y: 0.2 })
    b.box(0.03, 0.06, 0.012, { mat: 'metal', color: '#3a3a36', x: 0.12, y: 0.15, z: 0.07 })
    b.box(0.16, 0.04, 0.002, { mat: 'plain', color: '#d8d0a8', y: 0.1, z: 0.066, ao: 0 })
  })
}
// A heap of brass casings (or bullets) in an open bin.
export function brassBin(b, o = {}) {
  const rnd = seeded(o.seed ?? 71)
  const w = o.w ?? 0.4
  const d = o.d ?? 0.3
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(w, 0.16, 0.012, { mat: 'plastic', color: o.color ?? '#2a5ab8', y: 0.08, z: d / 2 })
    b.box(w, 0.16, 0.012, { mat: 'plastic', color: o.color ?? '#2a5ab8', y: 0.08, z: -d / 2 })
    for (const s of [-1, 1]) b.box(0.012, 0.16, d, { mat: 'plastic', color: o.color ?? '#2a5ab8', x: (s * w) / 2, y: 0.08 })
    b.box(w, 0.01, d, { mat: 'plastic', color: o.color ?? '#2a5ab8', y: 0.005 })
    b.box(w - 0.02, 0.1, d - 0.02, { mat: 'metal', color: o.fill ?? '#c8a050', y: 0.06 })
    for (let i = 0; i < 18; i++) b.cyl(0.006, 0.006, 0.03, { mat: 'gloss', color: o.fill ?? '#d8b060', x: (rnd() - 0.5) * (w - 0.06), y: 0.12, z: (rnd() - 0.5) * (d - 0.06), rx: rnd() * 3, rz: rnd() * 3, seg: 6 })
  })
}
// Simple long gun lying or racked; muzzle towards +z.
export function longGun(b, o = {}) {
  const wood = o.wood ?? '#7a4a2a'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0, rz: o.rz || 0 }, () => {
    if (o.kind === 'ar') {
      b.box(0.045, 0.1, 0.28, { mat: 'plastic', color: '#1e1e1e', z: -0.42, y: -0.01 })
      b.box(0.05, 0.08, 0.36, { mat: 'metal', color: '#2a2c2e', z: -0.1 })
      b.box(0.04, 0.12, 0.05, { mat: 'metal', color: '#2a2c2e', y: -0.08, z: -0.02, rx: 0.25 })
      b.box(0.045, 0.05, 0.26, { mat: 'plastic', color: '#1e1e1e', z: 0.18 })
      b.cyl(0.011, 0.011, 0.3, { mat: 'metal', color: '#2a2c2e', z: 0.42, rx: Math.PI / 2, seg: 8 })
      b.box(0.012, 0.06, 0.03, { mat: 'metal', color: '#2a2c2e', y: 0.05, z: 0.26 })
      b.box(0.02, 0.04, 0.1, { mat: 'metal', color: '#2a2c2e', y: 0.06, z: -0.12 })
    } else {
      b.extrude([[-0.62, -0.06], [-0.3, -0.02], [-0.3, 0.04], [-0.62, 0.05]], 0.04, { mat: 'wood', color: wood, ry: Math.PI / 2, curve: 1 })
      b.box(0.038, 0.07, 0.3, { mat: 'metal', color: '#2a2c2e', z: -0.14 })
      b.box(0.034, 0.045, 0.42, { mat: 'wood', color: wood, z: 0.2, y: -0.025 })
      b.cyl(0.011, 0.011, 0.66, { mat: 'metal', color: '#3a3e42', z: 0.3, y: 0.02, rx: Math.PI / 2, seg: 8 })
      if (o.scope) {
        b.cyl(0.02, 0.02, 0.26, { mat: 'paint', color: '#1a1a1a', y: 0.075, z: -0.08, rx: Math.PI / 2, seg: 10 })
        for (const z of [-0.22, 0.06]) b.cyl(0.026, 0.026, 0.04, { mat: 'paint', color: '#1a1a1a', y: 0.075, z, rx: Math.PI / 2, seg: 10 })
      }
      if (o.kind === 'shotgun') b.cyl(0.016, 0.016, 0.5, { mat: 'metal', color: '#3a3e42', z: 0.25, y: -0.02, rx: Math.PI / 2, seg: 8 })
    }
  })
}
export function pistol(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0, rz: o.rz || 0 }, () => {
    b.box(0.032, 0.045, 0.19, { mat: 'metal', color: o.color ?? '#2a2c2e', y: 0.02 })
    b.box(0.03, 0.12, 0.05, { mat: 'rubber', color: '#1a1a1a', y: -0.06, z: -0.06, rx: 0.25, r: 0.006 })
    b.torus(0.022, 0.005, { mat: 'metal', color: '#2a2c2e', y: -0.02, z: 0.0, ry: Math.PI / 2, arc: Math.PI, rz: Math.PI, rs: 4, ts2: 8 })
  })
}
// Rifle rack against a wall (+z out) holding n guns upright.
export function gunRack(b, o = {}) {
  const n = o.n ?? 4
  const w = n * 0.16 + 0.12
  const rnd = seeded(o.seed ?? 81)
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    const m = o.metal ? { mat: 'paint', color: '#3a3e42' } : { mat: 'wood', color: '#8a6a4a' }
    b.box(w, 0.08, 0.22, { ...m, y: 0.04 })
    b.box(w, 0.06, 0.06, { ...m, y: 1.05, z: -0.06 })
    for (const s of [-1, 1]) b.box(0.05, 1.1, 0.05, { ...m, x: (s * w) / 2, y: 0.55, z: -0.08 })
    for (let i = 0; i < n; i++) {
      const x = -w / 2 + 0.14 + i * 0.16
      b.box(0.03, 0.06, 0.04, { ...m, x: x - 0.04, y: 1.05, z: -0.01 })
      if (rnd() < (o.fill ?? 0.85)) longGun(b, { x, y: 0.62, z: -0.02, rx: -Math.PI / 2 + 0.1, ry: Math.PI, kind: pick(rnd, ['rifle', 'rifle', 'ar', 'shotgun']), scope: rnd() < 0.3 })
    }
  })
}

// ---------------------------------------------------------------- range and reloading
// Paper target with rings and a few holes (canvas), facing +z.
export function targetMat() {
  return canvasMat('target', [128, 160], (g, w, h) => {
    g.fillStyle = '#ece6d4'
    g.fillRect(0, 0, w, h)
    g.strokeStyle = '#1a1a1a'
    for (let r = 56; r > 6; r -= 10) {
      g.beginPath()
      g.arc(w / 2, h / 2 - 6, r, 0, TAU)
      g.lineWidth = 2
      g.stroke()
    }
    g.fillStyle = '#1a1a1a'
    g.beginPath()
    g.arc(w / 2, h / 2 - 6, 18, 0, TAU)
    g.fill()
    g.fillStyle = '#c8302a'
    g.beginPath()
    g.arc(w / 2, h / 2 - 6, 6, 0, TAU)
    g.fill()
    for (let i = 0; i < 9; i++) {
      g.fillStyle = '#2a2a2a'
      g.beginPath()
      g.arc(w / 2 + (Math.random() - 0.5) * 70, h / 2 - 6 + (Math.random() - 0.5) * 70, 2.5, 0, TAU)
      g.fill()
    }
  })
}
// Single-stage reloading press bolted to a bench (y = bench top). Lever in pivot o.name.
export function reloadingPress(b, I, o = {}) {
  const c = o.color ?? '#a82a22'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.16, 0.03, 0.14, { mat: 'paint', color: c, y: 0.015 })
    b.extrude([[-0.05, 0], [0.05, 0], [0.05, 0.08], [0.035, 0.26], [0.06, 0.3], [0.06, 0.36], [-0.06, 0.36], [-0.06, 0.3], [-0.035, 0.26], [-0.05, 0.08]], 0.07, { mat: 'paint', color: c, holes: [[[-0.025, 0.1], [0.025, 0.1], [0.02, 0.24], [-0.02, 0.24]]], ry: Math.PI / 2, z: -0.02, bevel: 0.006 })
    b.cyl(0.018, 0.018, 0.16, { mat: 'chrome', color: '#d0d4d8', y: 0.14, z: 0.02, seg: 10 })
    b.cyl(0.022, 0.022, 0.08, { mat: 'chrome', color: '#b8bcc0', y: 0.4, z: 0.02, seg: 10 })
    b.cyl(0.026, 0.026, 0.012, { mat: 'metal', color: '#3a3a3a', y: 0.37, z: 0.02, seg: 6 })
    b.pivot(o.name ?? 'lever', { y: 0.1, z: -0.05 }, (p) => {
      p.beam([0, 0, 0], [0.0, 0.28, 0.26], 0.018, 0.018, { mat: 'chrome', color: '#c8ccd0', round: true })
      p.sphere(0.03, { mat: 'plastic', color: '#1a1a1a', y: 0.28, z: 0.26 })
    })
    if (I) I.anims.push({ name: o.name ?? 'lever', kind: 'pump', axis: 'x', amp: 0.55, speed: 2.4, when: 'active' })
    // powder measure on its own arm
    if (o.measure !== false) {
      b.cyl(0.035, 0.035, 0.16, { mat: 'glass', color: '#d8d0b0', x: 0.12, y: 0.5, z: 0.02, seg: 12 })
      b.cyl(0.03, 0.03, 0.09, { mat: 'plain', color: '#3a3a30', x: 0.12, y: 0.47, z: 0.02, seg: 10 })
      b.box(0.06, 0.06, 0.06, { mat: 'paint', color: '#2a2a2a', x: 0.12, y: 0.4, z: 0.02 })
      b.beam([0.12, 0.37, 0.02], [0.03, 0.37, 0.02], 0.02, 0.02, { mat: 'metal', color: '#3a3a3a' })
    }
  })
}
// Progressive press: toolhead with five dies, rotating shell plate (pivot o.name+'Plate'),
// case feeder tube full of brass, bullet tray and lever.
export function progressivePress(b, I, o = {}) {
  const c = o.color ?? '#2a4a8a'
  const name = o.name ?? 'prog'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.22, 0.04, 0.2, { mat: 'paint', color: c, y: 0.02 })
    for (const s of [-1, 1]) b.cyl(0.016, 0.016, 0.42, { mat: 'chrome', color: '#c8ccd0', x: s * 0.07, y: 0.25, z: -0.05, seg: 8 })
    b.box(0.22, 0.05, 0.18, { mat: 'paint', color: c, y: 0.46, z: -0.02 })
    b.cyl(0.075, 0.075, 0.035, { mat: 'paint', color: shadeHex(c, 0.1), y: 0.42, z: 0.02, seg: 18 })
    for (let i = 0; i < 5; i++) {
      const a = (i / 5) * TAU
      b.cyl(0.016, 0.016, 0.12, { mat: 'chrome', color: i === 2 ? '#c8a050' : '#b8bcc0', x: Math.cos(a) * 0.055, y: 0.5, z: 0.02 + Math.sin(a) * 0.055, seg: 8 })
      b.cyl(0.02, 0.02, 0.012, { mat: 'metal', color: '#2a2a2a', x: Math.cos(a) * 0.055, y: 0.53, z: 0.02 + Math.sin(a) * 0.055, seg: 6 })
    }
    b.pivot(name + 'Plate', { y: 0.24, z: 0.02 }, (p) => {
      p.cyl(0.07, 0.07, 0.02, { mat: 'chrome', color: '#a8acb0', seg: 18 })
      for (let i = 0; i < 5; i++) {
        const a = (i / 5) * TAU
        p.cyl(0.007, 0.007, 0.04, { mat: 'gloss', color: '#d8b060', x: Math.cos(a) * 0.055, y: 0.03, z: Math.sin(a) * 0.055, seg: 6 })
      }
    })
    if (I) I.anims.push({ name: name + 'Plate', kind: 'spin', speed: 1.2, when: 'active' })
    // case feeder tube
    b.cyl(0.02, 0.02, 0.6, { mat: 'glass', color: '#d8e0e0', x: 0.06, y: 0.85, z: 0.02, seg: 10 })
    b.cyl(0.015, 0.015, 0.55, { mat: 'metal', color: '#c8a050', x: 0.06, y: 0.82, z: 0.02, seg: 8 })
    b.lathe([[0.02, 0], [0.12, 0.12], [0.13, 0.14]], { mat: 'plastic', color: '#3a3a3a', x: 0.06, y: 1.14, z: 0.02, seg: 14 })
    b.pivot(name + 'Lever', { x: 0.12, y: 0.16, z: -0.02 }, (p) => {
      p.beam([0, 0, 0], [0.12, 0.26, 0.22], 0.018, 0.018, { mat: 'chrome', color: '#c8ccd0', round: true })
      p.sphere(0.03, { mat: 'plastic', color: '#1a1a1a', x: 0.12, y: 0.26, z: 0.22 })
    })
    if (I) I.anims.push({ name: name + 'Lever', kind: 'pump', axis: 'x', amp: 0.5, speed: 2.6, when: 'active' })
    // bullet tray and a bin catching finished rounds
    b.box(0.12, 0.02, 0.1, { mat: 'paint', color: '#2a2a2a', x: -0.14, y: 0.35, z: 0.06 })
    for (let i = 0; i < 6; i++) b.sphere(0.008, { mat: 'metal', color: '#c87a40', x: -0.17 + (i % 3) * 0.03, y: 0.365, z: 0.04 + Math.floor(i / 3) * 0.03, ws: 6, hs: 4 })
    b.box(0.1, 0.06, 0.1, { mat: 'plastic', color: '#3a3a3a', x: -0.05, y: 0.06, z: 0.15 })
  })
}
// Vibratory case tumbler: bowl on a motor base (shake anim on pivot o.name).
export function tumbler(b, I, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0 }, () => {
    b.cyl(0.18, 0.2, 0.18, { mat: 'plastic', color: '#2a2a2a', y: 0.09, seg: 18 })
    b.pivot(o.name ?? 'tumble', { y: 0.18 }, (p) => {
      p.lathe([[0.08, 0], [0.22, 0.06], [0.24, 0.2], [0.23, 0.21], [0.21, 0.08], [0.07, 0.02]], { mat: 'plastic', color: o.color ?? '#3a6a9a', seg: 20 })
      p.cyl(0.21, 0.21, 0.02, { mat: 'gravel', color: '#c8b080', y: 0.14, seg: 20 })
      p.cyl(0.24, 0.24, 0.02, { mat: 'glass', color: '#d8e0e0', y: 0.22, seg: 20 })
    })
    if (I) I.anims.push({ name: o.name ?? 'tumble', kind: 'shake', amp: 0.012, when: 'active' })
  })
}
// Small powder keg.
export function keg(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0, rz: o.rz || 0 }, () => {
    b.lathe([[0.16, 0], [0.19, 0.12], [0.2, 0.22], [0.19, 0.32], [0.16, 0.44], [0.001, 0.44]], { mat: 'wood', color: o.color ?? '#8a5a3a', seg: 16 })
    for (const y of [0.05, 0.16, 0.28, 0.39]) b.torus(0.185 + (y > 0.1 && y < 0.35 ? 0.012 : 0), 0.01, { mat: 'metal', color: '#3a3a36', y, rx: Math.PI / 2, rs: 4, ts2: 20 })
    b.plane(0.16, 0.08, { material: plateMat('POWDER', '#d8c8a0', '#8a1a12'), y: 0.24, z: 0.205, shadow: false })
  })
}

// ---------------------------------------------------------------- sewing
// A classic sewing machine head (y = table top). Wheel in pivot o.name.
export function sewingHead(b, I, o = {}) {
  const c = o.color ?? '#1e1e1e'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.42, 0.04, 0.2, { mat: 'gloss', color: c, y: 0.02, r: 0.01 })
    b.box(0.1, 0.24, 0.13, { mat: 'gloss', color: c, x: 0.14, y: 0.15, r: 0.02 })
    b.box(0.38, 0.09, 0.12, { mat: 'gloss', color: c, x: 0.0, y: 0.3, r: 0.03 })
    b.box(0.07, 0.15, 0.1, { mat: 'gloss', color: c, x: -0.16, y: 0.25, r: 0.015 })
    b.cyl(0.008, 0.008, 0.09, { mat: 'chrome', color: '#d0d4d8', x: -0.16, y: 0.13, seg: 6 })
    b.box(0.3, 0.03, 0.004, { mat: 'plain', color: '#c8a040', y: 0.3, z: 0.062, ao: 0 })
    b.cyl(0.02, 0.02, 0.05, { mat: 'plastic', color: o.thread ?? '#c8302a', x: 0.05, y: 0.37, seg: 8 })
    b.cyl(0.004, 0.004, 0.06, { mat: 'steel', color: '#c8ccd0', x: 0.05, y: 0.37, seg: 4 })
    b.pivot(o.name ?? 'wheel', { x: 0.21, y: 0.28 }, (p) => {
      p.cyl(0.07, 0.07, 0.025, { mat: 'chrome', color: '#b8bcc0', rz: Math.PI / 2, seg: 16 })
      p.box(0.03, 0.12, 0.012, { mat: 'chrome', color: '#a8acb0', x: 0.015 })
    })
    if (I) I.anims.push({ name: o.name ?? 'wheel', kind: 'spin', axis: 'x', speed: 9, when: 'active' })
  })
}
// Cast-iron treadle stand with a wooden top for a sewing machine.
export function treadleTable(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.9, 0.04, 0.45, { mat: 'wood', color: '#8a5a3a', y: 0.76 })
    for (const s of [-1, 1]) {
      b.extrude([[-0.2, 0], [0.2, 0], [0.12, 0.08], [0.05, 0.6], [0.18, 0.74], [-0.18, 0.74], [-0.05, 0.6], [-0.12, 0.08]], 0.03, { mat: 'metal', color: '#2a2a2a', x: s * 0.38, ry: Math.PI / 2, holes: [[[-0.07, 0.15], [0.07, 0.15], [0.03, 0.5], [-0.03, 0.5]]] })
    }
    b.box(0.76, 0.03, 0.03, { mat: 'metal', color: '#2a2a2a', y: 0.12 })
    b.box(0.4, 0.02, 0.2, { mat: 'metal', color: '#2a2a2a', y: 0.1, z: 0.08, rx: 0.2 })
    b.torus(0.2, 0.015, { mat: 'metal', color: '#2a2a2a', x: 0.3, y: 0.38, ry: Math.PI / 2, rs: 4, ts2: 22 })
  })
}
// Bolt of fabric lying on its side, or standing (o.up).
export function fabricBolt(b, o = {}) {
  const L = o.len ?? 1.1
  const r = o.r ?? 0.09
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    if (o.up) {
      b.cyl(r, r, L, { mat: 'cloth', color: o.color ?? '#8a3a3a', y: L / 2, seg: 12 })
      b.cyl(0.02, 0.02, L + 0.04, { mat: 'plain', color: '#b8986a', y: L / 2, seg: 6 })
    } else {
      b.cyl(r, r, L, { mat: 'cloth', color: o.color ?? '#8a3a3a', y: r, rz: Math.PI / 2, seg: 12 })
      b.cyl(0.02, 0.02, L + 0.04, { mat: 'plain', color: '#b8986a', y: r, rz: Math.PI / 2, seg: 6 })
    }
  })
}
// Dress form on a tripod stand wearing o.wear: 'jacket' | 'vest' | 'armor' | 'riot' | null.
export function dressForm(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (let i = 0; i < 3; i++) {
      const a = (i / 3) * TAU
      b.beam([0, 0.3, 0], [Math.cos(a) * 0.25, 0.0, Math.sin(a) * 0.25], 0.025, 0.025, { mat: 'wood', color: '#5a3a2a', round: true })
    }
    b.cyl(0.015, 0.015, 0.8, { mat: 'chrome', color: '#b8bcc0', y: 0.6, seg: 8 })
    b.lathe([[0.001, 0.95], [0.14, 0.97], [0.17, 1.1], [0.14, 1.25], [0.19, 1.42], [0.16, 1.52], [0.06, 1.58], [0.04, 1.65], [0.001, 1.66]], { mat: 'cloth', color: '#d8c8a8', seg: 16, sz: 0.7 })
    const w = o.wear
    const c = o.color
    if (w === 'jacket') {
      b.lathe([[0.001, 1.0], [0.16, 1.0], [0.19, 1.12], [0.16, 1.26], [0.21, 1.44], [0.17, 1.55], [0.07, 1.58]], { mat: 'cloth', color: c ?? '#5a3a2a', seg: 16, sz: 0.74 })
      for (const s of [-1, 1]) b.capsule(0.055, 0.3, { mat: 'cloth', color: c ?? '#5a3a2a', x: s * 0.22, y: 1.3, rz: s * 0.25 })
    } else if (w === 'vest' || w === 'armor' || w === 'riot') {
      const vc = c ?? (w === 'riot' ? '#2a2e34' : w === 'armor' ? '#5a6040' : '#3a4038')
      b.lathe([[0.001, 1.05], [0.17, 1.05], [0.2, 1.2], [0.2, 1.4], [0.17, 1.5], [0.08, 1.52]], { mat: 'cloth', color: vc, seg: 14, sz: 0.78 })
      for (let i = 0; i < 3; i++) b.box(0.08, 0.1, 0.03, { mat: 'cloth', color: shadeHex(vc, -0.1), x: -0.1 + i * 0.1, y: 1.2, z: 0.15 })
      if (w === 'armor' || w === 'riot') {
        b.box(0.26, 0.22, 0.04, { mat: w === 'riot' ? 'plastic' : 'cloth', color: shadeHex(vc, 0.05), y: 1.33, z: 0.14, r: 0.02 })
        for (const s of [-1, 1]) b.sphere(0.09, { mat: w === 'riot' ? 'plastic' : 'cloth', color: vc, x: s * 0.2, y: 1.48, sy: 0.6 })
      }
    }
  })
}
// Animal hide stretched on a timber frame to dry.
export function hideFrame(b, o = {}) {
  const rnd = seeded(o.seed ?? 3)
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (const s of [-1, 1]) b.beam([s * 0.6, 0, 0.25], [s * 0.6, 1.6, -0.05], 0.06, 0.06, { mat: 'wood', color: '#a89070' })
    for (const y of [0.3, 1.5]) b.box(1.3, 0.05, 0.05, { mat: 'wood', color: '#a89070', y, z: 0.25 - (y / 1.6) * 0.3 })
    b.at({ y: 0.9, z: 0.12, rx: -0.19 }, () => {
      const pts = []
      for (let i = 0; i < 16; i++) {
        const a = (i / 16) * TAU
        const r = 0.45 + Math.sin(a * 4) * 0.08 + rnd() * 0.05
        pts.push([Math.cos(a) * r * 1.1, Math.sin(a) * r])
      }
      b.extrude(pts, 0.012, { mat: 'cloth', color: o.color ?? '#a87a52', curve: 1 })
      for (let i = 0; i < 8; i++) {
        const a = (i / 8) * TAU
        b.rope([[Math.cos(a) * 0.5, Math.sin(a) * 0.46, 0], [Math.sign(Math.cos(a)) * 0.6, Math.sin(a) * 0.55, 0.02]], 0.004, { mat: 'cloth', color: '#c8b088', sag: 0, steps: 2 })
      }
    })
  })
}

// ---------------------------------------------------------------- electronics
export function oscilloscope(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.36, 0.2, 0.3, { mat: 'paint', color: o.color ?? '#c8c4b4', y: 0.1, r: 0.01 })
    b.box(0.16, 0.12, 0.01, { material: screenMatGreen(), x: -0.07, y: 0.11, z: 0.151 })
    for (let i = 0; i < 6; i++) b.cyl(0.012, 0.012, 0.02, { mat: 'plastic', color: '#1a1a1a', x: 0.06 + (i % 3) * 0.04, y: 0.15 - Math.floor(i / 3) * 0.06, z: 0.155, rx: Math.PI / 2, seg: 8 })
    for (let i = 0; i < 2; i++) b.cyl(0.008, 0.008, 0.02, { mat: 'chrome', color: '#c8a050', x: 0.06 + i * 0.05, y: 0.03, z: 0.155, rx: Math.PI / 2, seg: 6 })
  })
}
const _scr = {}
function screenMatGreen() {
  if (_scr.g) return _scr.g
  _scr.g = canvasMat('scr-green', [128, 96], (g, w, h) => {
    g.fillStyle = '#03120a'
    g.fillRect(0, 0, w, h)
    g.strokeStyle = 'rgba(56,255,122,0.25)'
    for (let x = 0; x < w; x += 16) g.strokeRect(x, 0, 0, h)
    for (let y = 0; y < h; y += 16) g.strokeRect(0, y, w, 0)
    g.strokeStyle = '#38ff7a'
    g.lineWidth = 2
    g.beginPath()
    for (let x = 0; x < w; x++) g.lineTo(x, h / 2 + Math.sin(x * 0.2) * 22)
    g.stroke()
  }, { emissive: 2.2, rough: 0.3 })
  return _scr.g
}
export function crtMonitor(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.4, 0.34, 0.36, { mat: 'plastic', color: o.color ?? '#d8d0c0', y: 0.2, r: 0.02 })
    b.box(0.3, 0.24, 0.2, { mat: 'plastic', color: o.color ?? '#d8d0c0', y: 0.2, z: -0.2, r: 0.04 })
    b.box(0.32, 0.25, 0.01, { material: o.screen ?? screenMatGreen(), y: 0.21, z: 0.181 })
    b.box(0.22, 0.03, 0.2, { mat: 'plastic', color: o.color ?? '#d8d0c0', y: 0.015 })
  })
}
// Rack of small component drawers (resistors, caps, screws).
export function componentDrawers(b, o = {}) {
  const cols = o.cols ?? 5
  const rows = o.rows ?? 6
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(cols * 0.075 + 0.03, rows * 0.05 + 0.03, 0.16, { mat: 'paint', color: o.color ?? '#3a4a5a', y: (rows * 0.05 + 0.03) / 2 })
    for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++) {
      const x = -((cols - 1) * 0.075) / 2 + c * 0.075
      const y = 0.03 + r * 0.05
      b.box(0.066, 0.042, 0.01, { mat: 'glass', color: '#e8eef0', x, y, z: 0.081 })
      b.box(0.04, 0.025, 0.012, { mat: 'plain', color: ['#c8302a', '#d8a020', '#3a8a4a', '#2a5ab8', '#8a8e92'][(r * 3 + c) % 5], x, y, z: 0.07 })
      b.box(0.02, 0.006, 0.02, { mat: 'plastic', color: '#1a1a1a', x, y: y - 0.012, z: 0.09 })
    }
  })
}
// 19-inch rack with blinking status LEDs (registered in I.blink).
export function serverRack(b, I, o = {}) {
  const rnd = seeded(o.seed ?? 91)
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.6, 1.9, 0.7, { mat: 'paint', color: '#1e2022', y: 0.95 })
    b.box(0.52, 1.78, 0.01, { mat: 'glass', color: '#3a4a50', y: 0.97, z: 0.356 })
    for (let i = 0; i < 12; i++) {
      const y = 0.2 + i * 0.13
      b.box(0.48, 0.1, 0.02, { mat: 'metal', color: i % 3 === 0 ? '#3a3e42' : '#2a2c2e', y, z: 0.33 })
      for (let k = 0; k < 3; k++) {
        const g = pick(rnd, ['glowGreen', 'glowGreen', 'glowAmber', 'glowBlue'])
        b.box(0.012, 0.012, 0.01, { mat: g, color: '#ffffff', x: -0.2 + k * 0.03, y, z: 0.345, shadow: false })
      }
    }
    for (const sx of [-1, 1]) b.box(0.03, 1.86, 0.03, { mat: 'metal', color: '#4a4e52', x: sx * 0.27, y: 0.95, z: 0.34 })
  })
}

// ---------------------------------------------------------------- chemistry
// Glassware set on a bench: flasks, beakers, a retort stand with a condenser.
export function glassSet(b, o = {}) {
  const rnd = seeded(o.seed ?? 101)
  const cols = ['#58e070', '#e8d040', '#40b8e8', '#e85a40', '#c060e0', '#e8e8e0']
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (let i = 0; i < (o.n ?? 6); i++) {
      const px = -0.3 + (i % 3) * 0.17 + (rnd() - 0.5) * 0.04
      const pz = -0.06 + Math.floor(i / 3) * 0.15
      const col = pick(rnd, cols)
      const k = rnd()
      if (k < 0.4) {
        b.lathe([[0.065, 0], [0.07, 0.05], [0.025, 0.15], [0.022, 0.22], [0.026, 0.23]], { mat: 'glass', color: '#e8f0f0', x: px, z: pz, seg: 14 })
        b.lathe([[0.062, 0.004], [0.066, 0.045], [0.04, 0.09], [0.001, 0.09]], { mat: 'glowGreen', color: col, x: px, z: pz, seg: 12 })
      } else if (k < 0.75) {
        b.cyl(0.045, 0.045, 0.13, { mat: 'glass', color: '#e8f0f0', x: px, y: 0.065, z: pz, seg: 12, open: true })
        b.cyl(0.042, 0.042, 0.07, { mat: 'plain', color: col, x: px, y: 0.035, z: pz, seg: 12 })
      } else {
        for (let t = 0; t < 4; t++) {
          b.cyl(0.012, 0.012, 0.16, { mat: 'glass', color: '#e8f0f0', x: px - 0.045 + t * 0.03, y: 0.1, z: pz, seg: 8 })
          b.cyl(0.011, 0.011, 0.08, { mat: 'plain', color: pick(rnd, cols), x: px - 0.045 + t * 0.03, y: 0.06, z: pz, seg: 8 })
        }
        b.box(0.14, 0.03, 0.04, { mat: 'wood', color: '#c8a070', x: px, y: 0.015, z: pz })
      }
    }
    if (o.stand !== false) {
      // retort stand with a round flask and a condenser running down to a beaker
      b.box(0.16, 0.012, 0.22, { mat: 'metal', color: '#3a3a3a', x: 0.36, y: 0.006 })
      b.cyl(0.008, 0.008, 0.55, { mat: 'chrome', color: '#c8ccd0', x: 0.36, y: 0.28, z: -0.08, seg: 6 })
      b.sphere(0.07, { mat: 'glass', color: '#e8f0f0', x: 0.36, y: 0.22, z: 0.0 })
      b.sphere(0.06, { mat: 'glowAmber', color: '#a86020', x: 0.36, y: 0.2, z: 0.0, ws: 10, hs: 6 })
      b.beam([0.36, 0.3, 0.0], [0.6, 0.12, 0.05], 0.022, 0.022, { mat: 'glass', color: '#d8e8f0', round: true })
      b.cyl(0.04, 0.04, 0.09, { mat: 'glass', color: '#e8f0f0', x: 0.62, y: 0.045, z: 0.05, seg: 10, open: true })
      b.cyl(0.05, 0.06, 0.04, { mat: 'metal', color: '#3a3a3a', x: 0.36, y: 0.04, z: 0.0, seg: 10 })
    }
  })
}
// Fume hood: cabinet with a glass sash and a duct running up (o.duct m).
export function fumeHood(b, o = {}) {
  const w = o.w ?? 1.2
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(w, 0.86, 0.7, { mat: 'paint', color: '#d8dcd8', y: 0.43 })
    b.box(w - 0.1, 0.02, 0.6, { mat: 'plain', color: '#2a2e2e', y: 0.87 })
    for (const s of [-1, 1]) b.box(0.06, 1.1, 0.7, { mat: 'paint', color: '#d8dcd8', x: (s * (w - 0.06)) / 2, y: 1.42 })
    b.box(w, 0.06, 0.7, { mat: 'paint', color: '#d8dcd8', y: 2.0 })
    b.box(w, 0.6, 0.06, { mat: 'paint', color: '#d8dcd8', y: 1.6, z: -0.32 })
    b.box(w - 0.14, 0.62, 0.015, { mat: 'glass', color: '#d8e8e8', y: 1.48, z: 0.33 })
    b.box(w - 0.12, 0.04, 0.04, { mat: 'paint', color: '#c8a020', y: 1.17, z: 0.34 })
    b.lathe([[w * 0.36, 2.03], [0.15, 2.3], [0.15, 2.32]], { mat: 'metal', color: '#b8bcc0', seg: 4, ry: Math.PI / 4 })
    b.cyl(0.15, 0.15, o.duct ?? 0.8, { mat: 'metal', color: '#b8bcc0', y: 2.32 + (o.duct ?? 0.8) / 2, seg: 14 })
    for (let k = 0; k < 4; k++) b.torus(0.152, 0.01, { mat: 'metal', color: '#9a9ea2', y: 2.4 + k * 0.2, rx: Math.PI / 2, rs: 4, ts2: 16 })
  })
}
// Stirred reaction vessel on legs: dished heads, agitator motor, manway, nozzles.
export function reactor(b, I, o = {}) {
  const r = o.r ?? 0.45
  const h = o.h ?? 1.2
  const c = o.color ?? '#a8acb0'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    const y0 = o.leg ?? 0.5
    for (let i = 0; i < 4; i++) {
      const a = (i / 4) * TAU + Math.PI / 4
      b.box(0.06, y0 + 0.2, 0.06, { mat: 'paint', color: '#4a4e52', x: Math.cos(a) * r * 0.85, y: (y0 + 0.2) / 2, z: Math.sin(a) * r * 0.85 })
      b.box(0.14, 0.02, 0.14, { mat: 'metal', color: '#3a3a3a', x: Math.cos(a) * r * 0.85, y: 0.01, z: Math.sin(a) * r * 0.85 })
    }
    b.lathe([[0.001, y0], [r * 0.6, y0 + 0.04], [r * 0.95, y0 + 0.15], [r, y0 + 0.25], [r, y0 + h - 0.25], [r * 0.95, y0 + h - 0.15], [r * 0.6, y0 + h - 0.04], [0.001, y0 + h]], { mat: 'metal', color: c, seg: 24 })
    for (const yy of [y0 + h * 0.35, y0 + h * 0.65]) b.torus(r + 0.004, 0.012, { mat: 'metal', color: '#8a8e92', y: yy, rx: Math.PI / 2, rs: 4, ts2: 32 })
    b.cyl(0.11, 0.11, 0.18, { mat: 'paint', color: o.motor ?? '#2a5a8a', y: y0 + h + 0.12, seg: 14 })
    b.cyl(0.07, 0.07, 0.1, { mat: 'metal', color: '#5a5e62', y: y0 + h + 0.02, seg: 10 })
    b.cyl(0.13, 0.13, 0.05, { mat: 'metal', color: '#8a8e92', x: r * 0.5, y: y0 + h - 0.06, z: 0.0, seg: 14 })
    b.pivot(o.name ?? 'fan', { y: y0 + h + 0.23 }, (p) => {
      p.cyl(0.1, 0.1, 0.02, { mat: 'paint', color: '#1a1a1a', seg: 12 })
      p.box(0.18, 0.012, 0.03, { mat: 'metal', color: '#8a8e92', y: 0.015 })
    })
    if (I) I.anims.push({ name: o.name ?? 'fan', kind: 'spin', speed: 6, when: 'active' })
    // sight glass and a level gauge
    b.cyl(0.06, 0.06, 0.03, { mat: 'glass', color: '#a8d8c8', x: 0, y: y0 + h * 0.55, z: r, rx: Math.PI / 2, seg: 12 })
    b.torus(0.06, 0.012, { mat: 'chrome', color: '#c8ccd0', y: y0 + h * 0.55, z: r + 0.01, rs: 4, ts2: 14 })
    if (o.label) b.plane(0.3, 0.15, { material: plateMat(o.label, '#e8e4d8', '#1a1a1a'), y: y0 + h * 0.35, z: r + 0.012, shadow: false })
  })
  return (o.leg ?? 0.5) + h
}
// Centrifugal pump on a skid with a motor.
export function pump(b, o = {}) {
  const c = o.color ?? '#2a5a8a'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.7, 0.06, 0.3, { mat: 'paint', color: '#3a3e42', y: 0.03 })
    b.cyl(0.13, 0.13, 0.36, { mat: 'paint', color: c, x: -0.15, y: 0.2, rz: Math.PI / 2, seg: 14 })
    for (let i = 0; i < 6; i++) b.box(0.3, 0.012, 0.012, { mat: 'paint', color: shadeHex(c, -0.15), x: -0.15, y: 0.2 + Math.cos(i) * 0.135, z: Math.sin(i) * 0.135 })
    b.cyl(0.15, 0.15, 0.12, { mat: 'paint', color: shadeHex(c, 0.08), x: 0.2, y: 0.2, rz: Math.PI / 2, seg: 16 })
    b.cyl(0.05, 0.05, 0.2, { mat: 'metal', color: '#8a8e92', x: 0.2, y: 0.38, seg: 10 })
    b.cyl(0.05, 0.05, 0.18, { mat: 'metal', color: '#8a8e92', x: 0.34, y: 0.2, rz: Math.PI / 2, seg: 10 })
  })
}
// Camping stove (two burners) with an optional pot; flame anchor returned.
export function campStove(b, I, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.5, 0.09, 0.3, { mat: 'paint', color: o.color ?? '#3a6a3a', y: 0.045 })
    b.box(0.5, 0.25, 0.012, { mat: 'paint', color: o.color ?? '#3a6a3a', y: 0.2, z: -0.15 })
    for (const s of [-1, 1]) b.box(0.012, 0.18, 0.3, { mat: 'paint', color: o.color ?? '#3a6a3a', x: s * 0.25, y: 0.16 })
    for (const x of [-0.12, 0.12]) {
      b.cyl(0.06, 0.06, 0.02, { mat: 'metal', color: '#2a2a2a', x, y: 0.1, seg: 12 })
      for (let i = 0; i < 4; i++) b.box(0.14, 0.008, 0.008, { mat: 'metal', color: '#1a1a1a', x, y: 0.115, ry: (i / 4) * Math.PI })
    }
    b.cyl(0.06, 0.06, 0.2, { mat: 'paint', color: '#c8302a', x: 0.36, y: 0.1, seg: 12 })
  })
  if (I && o.flame) I.flames.push({ x: (o.x || 0) + (o.flameX ?? 0.12), y: (o.y || 0) + 0.11, z: o.z || 0, w: 0.1, h: 0.08, when: 'active' })
}

// ---------------------------------------------------------------- shelters
// Camouflage netting: leafy blotches with open holes (cutout), double sided.
export function camoNetMat() {
  return canvasMat('camonet', [256, 256], (g, w, h) => {
    g.clearRect(0, 0, w, h)
    const cols = ['#4a5236', '#5e5a3a', '#3a4030', '#6a6444', '#2e3428']
    for (let i = 0; i < 260; i++) {
      g.fillStyle = cols[i % cols.length]
      g.beginPath()
      const x = Math.random() * w
      const y = Math.random() * h
      const r = 6 + Math.random() * 14
      for (let k = 0; k < 7; k++) {
        const a = (k / 7) * TAU
        const rr = r * (0.6 + Math.random() * 0.6)
        g.lineTo(x + Math.cos(a) * rr, y + Math.sin(a) * rr)
      }
      g.fill()
    }
    // the net itself shows through the gaps
    g.strokeStyle = '#2a2a22'
    g.lineWidth = 1.5
    for (let x = 0; x < w; x += 16) {
      g.beginPath()
      g.moveTo(x, 0)
      g.lineTo(x + h, h)
      g.stroke()
      g.beginPath()
      g.moveTo(x, 0)
      g.lineTo(x - h, h)
      g.stroke()
    }
  }, { alpha: true, cutout: true, rough: 0.95, side: THREE.DoubleSide })
}
// A tarp sewn from mismatched patches.
export function patchworkMat(seed = 1) {
  return canvasMat('patch' + seed, [256, 256], (g, w, h) => {
    const r = seeded(seed)
    const cols = ['#8a5a3a', '#5a6a4a', '#3a5a7a', '#a88a5a', '#6a3a32', '#7a7a6a', '#4a4a3a']
    for (let y = 0; y < h; ) {
      const rh = 30 + r() * 60
      for (let x = 0; x < w; ) {
        const rw = 40 + r() * 80
        g.fillStyle = pick(r, cols)
        g.fillRect(x, y, rw, rh)
        g.strokeStyle = 'rgba(30,24,16,0.6)'
        g.setLineDash([3, 3])
        g.lineWidth = 2
        g.strokeRect(x + 2, y + 2, rw - 4, rh - 4)
        x += rw
      }
      y += rh
    }
    g.setLineDash([])
    for (let i = 0; i < 400; i++) {
      g.fillStyle = `rgba(20,16,10,${Math.random() * 0.12})`
      g.fillRect(Math.random() * w, Math.random() * h, 2 + Math.random() * 20, 1 + Math.random() * 4)
    }
  }, { rough: 0.95, side: THREE.DoubleSide })
}
// A sloped cloth canopy on four poles: high at the front (+z), low at the back.
// o: { x, z, w, d, hf (front height), hb (back height), mat|material, color, sag, seed, guys }
export function canopy(b, I, o) {
  const { w, d } = o
  const hf = o.hf ?? 2.35
  const hb = o.hb ?? 1.9
  const x0 = o.x || 0
  const z0 = o.z || 0
  roof(b, I, (r) => r.cloth(w, d, { mat: o.mat ?? 'canvas', material: o.material, color: o.color ?? '#ffffff', x: x0, z: z0, corners: [hb, hb, hf, hf], sag: o.sag ?? 0.1, seed: o.seed ?? 3, wrinkle: 0.05, segX: 12, segZ: 9 }))
  const P = [[-w / 2, hb, -d / 2], [w / 2, hb, -d / 2], [w / 2, hf, d / 2], [-w / 2, hf, d / 2]]
  for (const [x, y, z] of P) {
    b.cyl(0.04, 0.05, y + 0.08, { mat: o.metalPoles ? 'metal' : 'wood', color: o.metalPoles ? '#6a6e72' : '#a89070', x: x0 + x, y: (y + 0.08) / 2, z: z0 + z, seg: 7 })
    if (o.guys !== false) {
      const gx = x0 + x + Math.sign(x) * 0.6
      const gz = z0 + z + Math.sign(z) * 0.5
      b.rope([[x0 + x, y, z0 + z], [gx, 0.03, gz]], 0.007, { mat: 'cloth', color: '#b49c74', sag: 0.02, steps: 3 })
      b.box(0.03, 0.12, 0.03, { mat: 'wood', color: '#6a5038', x: gx, y: 0.03, z: gz, rx: 0.3 })
    }
  }
}
// Patio umbrella with a weighted base (canopy in a fading roof pivot).
export function umbrella(b, I, o = {}) {
  const R = o.r ?? 1.3
  const H = o.h ?? 2.3
  const x = o.x || 0
  const z = o.z || 0
  b.cyl(0.25, 0.28, 0.08, { mat: 'concrete', color: '#9a968e', x, y: 0.04, z, seg: 16 })
  b.cyl(0.022, 0.022, H, { mat: 'metal', color: '#c8ccd0', x, y: H / 2, z, seg: 8 })
  roof(b, I, (r) => {
    const n = 8
    for (let i = 0; i < n; i++) {
      const col = i % 2 ? (o.c1 ?? '#d8d0b8') : (o.c2 ?? '#2a6a5a')
      r.cyl(0.02, R, 0.36, { mat: 'canvas', color: col, x, y: H + 0.14, z, seg: 1, open: true, ts: (i / n) * TAU, tl: TAU / n })
      r.beam([x, H + 0.3, z], [x + Math.sin((i / n) * TAU) * R, H - 0.05, z + Math.cos((i / n) * TAU) * R], 0.012, 0.012, { mat: 'metal', color: '#c8ccd0', round: true })
    }
    r.sphere(0.04, { mat: 'metal', color: '#c8ccd0', x, y: H + 0.36, z })
  })
}
// A few corrugated sheets nailed onto rails on four poles (a tin lean-to).
export function tinLeanTo(b, I, o) {
  const { w, d } = o
  const hf = o.hf ?? 2.3
  const hb = o.hb ?? 1.95
  const x0 = o.x || 0
  const z0 = o.z || 0
  const rnd = seeded(o.seed ?? 5)
  for (const [x, z, h] of [[-w / 2, -d / 2, hb], [w / 2, -d / 2, hb], [w / 2, d / 2, hf], [-w / 2, d / 2, hf]]) b.cyl(0.05, 0.06, h, { mat: 'wood', color: '#9a8466', x: x0 + x, y: h / 2, z: z0 + z, seg: 7 })
  for (const s of [-1, 1]) b.beam([x0 + (s * w) / 2, hb, z0 - d / 2], [x0 + (s * w) / 2, hf, z0 + d / 2], 0.07, 0.08, { mat: 'wood', color: '#9a8466' })
  roof(b, I, (r) => {
    const n = Math.round(w / 0.75)
    const slope = Math.atan2(hf - hb, d)
    const len = Math.hypot(d + 0.3, hf - hb)
    for (let i = 0; i < n; i++) r.box(w / n + 0.08, 0.02, len, { mat: 'corrugated', color: pick(rnd, ['#9a948a', '#8a8e90', '#a89a88', '#7e7a72']), x: x0 - w / 2 + (w / n) * (i + 0.5), y: (hf + hb) / 2 + 0.06 + (i % 2) * 0.01, z: z0, rx: -slope, rz: (rnd() - 0.5) * 0.03 })
  })
}

// ---------------------------------------------------------------- crops
// A leaf blade (lanceolate) lying in the local xy plane, base at the origin, tip up +y.
function leafBlade(b, len, wid, o = {}) {
  const pts = [[0, 0], [wid * 0.5, len * 0.25], [wid * 0.45, len * 0.6], [0, len], [-wid * 0.45, len * 0.6], [-wid * 0.5, len * 0.25]]
  b.extrude(pts, 0.006, { mat: 'leaf', color: o.color ?? '#5a8a3e', x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0, rz: o.rz || 0, curve: 1, order: 'YXZ' })
}
// A row of one crop along x. kinds: cabbage, lettuce, tomato, corn, potato,
// carrot, beans, pumpkin, sunflower.
export function cropRowHD(b, o = {}) {
  const kind = o.kind ?? 'cabbage'
  const len = o.len ?? 4
  const rnd = seeded(o.seed ?? 51)
  const spacing = { corn: 0.32, sunflower: 0.45, pumpkin: 0.9, tomato: 0.5, beans: 0.6, lettuce: 0.3, carrot: 0.18 }[kind] ?? 0.42
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    const n = Math.max(1, Math.floor((len - 0.2) / spacing))
    for (let i = 0; i < n; i++) {
      const x = -len / 2 + 0.1 + spacing / 2 + i * spacing + (rnd() - 0.5) * 0.04
      const s = 0.8 + rnd() * 0.4
      const z = (rnd() - 0.5) * 0.06
      if (kind === 'cabbage' || kind === 'lettuce') {
        const c = kind === 'lettuce' ? pick(rnd, ['#8ab84a', '#9ac858', '#7aa840']) : pick(rnd, ['#6a9a58', '#7aa868', '#5a8a50'])
        for (let k = 0; k < 7; k++) {
          const a = (k / 7) * TAU + rnd()
          b.sphere(0.12 * s, { mat: 'leaf', color: shadeHex(c, -0.12), x: x + Math.cos(a) * 0.09 * s, y: 0.035, z: z + Math.sin(a) * 0.09 * s, sy: 0.28, ws: 8, hs: 4, rx: Math.sin(a) * 0.5, rz: -Math.cos(a) * 0.5 })
        }
        b.sphere((kind === 'lettuce' ? 0.09 : 0.12) * s, { mat: 'leaf', color: kind === 'lettuce' ? shadeHex(c, 0.1) : '#a8c888', x, y: 0.1 * s, z, sy: 0.85, ws: 10, hs: 8 })
      } else if (kind === 'corn') {
        const h = (1.4 + rnd() * 0.5) * s
        b.cyl(0.016, 0.026, h, { mat: 'leaf', color: '#7a9a48', x, y: h / 2, z, seg: 5 })
        for (let k = 0; k < 7; k++) {
          const a = k * 2.4 + rnd()
          const y = h * (0.15 + k * 0.11)
          leafBlade(b, 0.55 + rnd() * 0.2, 0.07, { color: pick(rnd, ['#6a9a3e', '#7aa848', '#8aa850']), x, y, z, ry: a, rx: 1.0 + rnd() * 0.4 })
        }
        if (rnd() < 0.8) {
          b.capsule(0.032, 0.13, { mat: 'leaf', color: '#9ab060', x: x + 0.04, y: h * 0.55, z, rz: -0.35, seg: 6 })
          b.cone(0.02, 0.07, { mat: 'cloth', color: '#a87a48', x: x + 0.09, y: h * 0.55 + 0.12, z, rz: -0.35, seg: 4 })
        }
        for (let k = 0; k < 4; k++) b.cyl(0.004, 0.004, 0.2, { mat: 'leaf', color: '#c8a858', x: x + Math.cos(k * 1.6) * 0.03, y: h + 0.08, z: z + Math.sin(k * 1.6) * 0.03, rz: Math.cos(k * 1.6) * 0.4, rx: Math.sin(k * 1.6) * 0.4, seg: 3 })
      } else if (kind === 'tomato') {
        b.box(0.024, 1.25, 0.024, { mat: 'wood', color: '#a89070', x, y: 0.62, z })
        for (let k = 0; k < 9; k++) {
          const a = rnd() * TAU
          const y = 0.15 + k * 0.11
          b.ico(0.075 * s, { mat: 'leaf', color: pick(rnd, ['#4a7a34', '#5a8a3e', '#3e6e2c']), x: x + Math.cos(a) * 0.1, y, z: z + Math.sin(a) * 0.1, detail: 0, noise: 0.3, sy: 0.6 })
        }
        for (let k = 0; k < 6; k++) b.sphere(0.034 + rnd() * 0.01, { mat: 'gloss', color: pick(rnd, ['#d8301a', '#c82818', '#e85a20', '#8ab040']), x: x + (rnd() - 0.5) * 0.22, y: 0.3 + rnd() * 0.65, z: z + (rnd() - 0.5) * 0.22, ws: 8, hs: 6 })
        b.rope([[x, 0.4, z], [x + 0.06, 0.7, z], [x, 0.95, z]], 0.004, { mat: 'cloth', color: '#d8c8a0', sag: 0 })
      } else if (kind === 'potato') {
        for (let k = 0; k < 6; k++) {
          const a = (k / 6) * TAU
          b.ico(0.1 * s, { mat: 'leaf', color: pick(rnd, ['#5a8a3e', '#6a9a48', '#4e7e36']), x: x + Math.cos(a) * 0.1, y: 0.14 + rnd() * 0.08, z: z + Math.sin(a) * 0.1, detail: 0, noise: 0.35 })
        }
        if (rnd() < 0.5) for (let k = 0; k < 3; k++) b.sphere(0.012, { mat: 'plain', color: '#e8e0f0', x: x + (rnd() - 0.5) * 0.15, y: 0.3, z: z + (rnd() - 0.5) * 0.15, ws: 5, hs: 4 })
      } else if (kind === 'carrot') {
        for (let k = 0; k < 5; k++) leafBlade(b, 0.18 + rnd() * 0.08, 0.04, { color: '#6aa040', x: x + (rnd() - 0.5) * 0.04, y: 0.01, z, ry: k * 1.25, rx: 0.25 + rnd() * 0.2 })
        b.cone(0.022, 0.03, { mat: 'plain', color: '#e87a2a', x, y: 0.01, z, rx: Math.PI, seg: 6 })
      } else if (kind === 'beans') {
        b.beam([x - 0.16, 0, z], [x, 1.7, z], 0.025, 0.025, { mat: 'wood', color: '#a89070', round: true })
        b.beam([x + 0.16, 0, z], [x, 1.7, z], 0.025, 0.025, { mat: 'wood', color: '#a89070', round: true })
        for (let k = 0; k < 12; k++) b.ico(0.065, { mat: 'leaf', color: pick(rnd, ['#5a9a3e', '#6aa848', '#4a8a34']), x: x + (rnd() - 0.5) * 0.24, y: 0.15 + k * 0.12, z: z + (rnd() - 0.5) * 0.12, detail: 0, noise: 0.35, sy: 0.6 })
        for (let k = 0; k < 6; k++) b.capsule(0.008, 0.1, { mat: 'leaf', color: '#7ab04a', x: x + (rnd() - 0.5) * 0.2, y: 0.4 + rnd() * 1.0, z: z + (rnd() - 0.5) * 0.1, seg: 4 })
      } else if (kind === 'pumpkin') {
        for (let k = 0; k < 6; k++) {
          const a = rnd() * TAU
          b.cyl(0.13, 0.13, 0.01, { mat: 'leaf', color: pick(rnd, ['#4a7a34', '#5a8a3e']), x: x + Math.cos(a) * 0.3, y: 0.12 + rnd() * 0.05, z: z + Math.sin(a) * 0.3, rx: (rnd() - 0.5) * 0.6, rz: (rnd() - 0.5) * 0.6, seg: 7 })
        }
        b.rope([[x - 0.4, 0.03, z], [x, 0.05, z + 0.1], [x + 0.4, 0.03, z - 0.1]], 0.01, { mat: 'leaf', color: '#5a7a3a', sag: 0 })
        const pr = (0.14 + rnd() * 0.06) * s
        b.lathe([[0.001, 0], [pr * 0.8, pr * 0.15], [pr, pr * 0.7], [pr * 0.8, pr * 1.3], [0.001, pr * 1.4]], { mat: 'gloss', color: pick(rnd, ['#d8701a', '#e88020', '#c86018']), x: x + 0.1, z: z + 0.15, seg: 14, sy: 0.9 })
        b.cyl(0.015, 0.02, 0.06, { mat: 'plain', color: '#5a6a3a', x: x + 0.1, y: pr * 1.35, z: z + 0.15, seg: 5 })
      } else if (kind === 'sunflower') {
        const h = (1.7 + rnd() * 0.5) * s
        b.cyl(0.02, 0.028, h, { mat: 'leaf', color: '#6a8a3a', x, y: h / 2, z, seg: 6 })
        for (let k = 0; k < 4; k++) b.cyl(0.1, 0.1, 0.008, { mat: 'leaf', color: '#5a8a3e', x: x + Math.cos(k * 2) * 0.12, y: h * (0.3 + k * 0.15), z: z + Math.sin(k * 2) * 0.12, rx: Math.sin(k * 2) * 0.7, rz: -Math.cos(k * 2) * 0.7, seg: 7 })
        b.at({ x, y: h, z, rx: 0.5 }, () => {
          b.cyl(0.1, 0.1, 0.04, { mat: 'plain', color: '#4a3018', seg: 14 })
          for (let k = 0; k < 14; k++) {
            const a = (k / 14) * TAU
            b.box(0.05, 0.008, 0.1, { mat: 'leaf', color: '#e8b820', x: Math.cos(a) * 0.14, y: 0.0, z: Math.sin(a) * 0.14, ry: -a + Math.PI / 2 })
          }
        })
      }
    }
  })
}
// A hen: body, tail, head, comb and beak, standing on y = 0.
export function chicken(b, o = {}) {
  const c = o.color ?? '#e8dcc8'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.sphere(0.12, { mat: 'cloth', color: c, y: 0.2, sx: 0.85, sz: 1.2, ws: 10, hs: 8 })
    b.cone(0.07, 0.14, { mat: 'cloth', color: shadeHex(c, -0.1), y: 0.3, z: -0.12, rx: -0.6, seg: 6 })
    b.sphere(0.06, { mat: 'cloth', color: c, y: 0.34, z: 0.11, ws: 8, hs: 6 })
    b.box(0.015, 0.04, 0.06, { mat: 'plain', color: '#c8201a', y: 0.4, z: 0.11 })
    b.cone(0.015, 0.04, { mat: 'plain', color: '#d8a020', y: 0.33, z: 0.18, rx: Math.PI / 2, seg: 4 })
    for (const s of [-1, 1]) b.cyl(0.007, 0.007, 0.1, { mat: 'plain', color: '#d8a020', x: s * 0.04, y: 0.05, seg: 4 })
  })
}

// Patient monitor screen: heart trace and numbers (emissive).
export function heartMat() {
  return canvasMat('ecg', [128, 96], (g, w, h) => {
    g.fillStyle = '#020a06'
    g.fillRect(0, 0, w, h)
    g.strokeStyle = '#40ff80'
    g.lineWidth = 2
    g.beginPath()
    for (let x = 0; x < w; x++) {
      const t = x % 40
      const y = t === 12 ? 18 : t === 13 ? 70 : t === 14 ? 40 : 48 + Math.sin(x * 0.3) * 2
      g.lineTo(x, y)
    }
    g.stroke()
    g.fillStyle = '#40ff80'
    g.font = 'bold 16px monospace'
    g.fillText('HR 72', 6, 90)
    g.fillStyle = '#40c8ff'
    g.fillText('98%', 80, 90)
  }, { emissive: 2, rough: 0.3 })
}
