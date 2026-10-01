// Shared props and sub-assemblies: crates, barrels, sacks, pallets, tarps,
// roofs, lights, furniture, tools, crops, tanks and pipes. Every helper takes
// a Builder and an options object ({ x, y, z, ry, s, ... }) and adds parts in
// the builder's current space. Colours tint the textures (white = natural).
import * as THREE from 'three'
import { seeded } from './kit.js'
import { signTexture } from '../render/texgen.js'

const TAU = Math.PI * 2

// ---------------------------------------------------------------- palette
export const COL = {
  wood: '#ffffff',
  woodGrey: '#bdb5aa',
  woodDark: '#8c7864',
  woodRed: '#b8705e',
  woodGreen: '#7e8a6a',
  plank: '#f0e6d8',
  bark: '#ffffff',
  steel: '#9aa1a6',
  iron: '#55595d',
  black: '#24272a',
  rubber: '#1d1d1d',
  canvas: '#d8c49c',
  canvasOlive: '#7c8158',
  tarpBlue: '#3f74b0',
  tarpGreen: '#4f6e4a',
  tarpOrange: '#cf6e2c',
  rope: '#b49c74',
  red: '#a8342a',
  yellow: '#d8a92a',
  white: '#e8e6e0',
  cardboard: '#b48c5a',
}
const pick = (r, a) => a[Math.floor(r() * a.length)]

// ---------------------------------------------------------------- special materials
const special = new Map()
function cachedMat(key, make) {
  if (!special.has(key)) special.set(key, make())
  return special.get(key)
}
export function signMat(text, opts = {}) {
  const key = 'sign:' + text + JSON.stringify(opts)
  return cachedMat(key, () => {
    const t = signTexture(text, opts)
    return new THREE.MeshStandardMaterial({ map: t, roughness: 0.75, metalness: 0.05 })
  })
}
export function solarMat() {
  return cachedMat('solar', () => {
    const c = document.createElement('canvas')
    c.width = 256
    c.height = 384
    const g = c.getContext('2d')
    g.fillStyle = '#c8ccd0'
    g.fillRect(0, 0, 256, 384)
    const cw = 256 / 6
    const ch = 384 / 10
    for (let i = 0; i < 6; i++) {
      for (let j = 0; j < 10; j++) {
        const x = i * cw + 3
        const y = j * ch + 3
        const gr = g.createLinearGradient(x, y, x + cw, y + ch)
        gr.addColorStop(0, '#1b2c55')
        gr.addColorStop(0.5, '#0f1a38')
        gr.addColorStop(1, '#16264a')
        g.fillStyle = gr
        g.fillRect(x, y, cw - 6, ch - 6)
        g.fillStyle = 'rgba(180,190,210,0.35)'
        for (let k = 1; k < 3; k++) g.fillRect(x + ((cw - 6) * k) / 3, y, 1, ch - 6)
        g.fillRect(x, y + (ch - 6) / 2, cw - 6, 1)
      }
    }
    const t = new THREE.CanvasTexture(c)
    t.colorSpace = THREE.SRGBColorSpace
    t.anisotropy = 8
    return new THREE.MeshStandardMaterial({ map: t, roughness: 0.18, metalness: 0.35 })
  })
}
// Screen glow (monitors, radios, oscilloscopes)
export function screenMat(kind = 'green') {
  return cachedMat('screen:' + kind, () => {
    const c = document.createElement('canvas')
    c.width = 128
    c.height = 96
    const g = c.getContext('2d')
    g.fillStyle = kind === 'green' ? '#03120a' : kind === 'amber' ? '#140a02' : '#020a14'
    g.fillRect(0, 0, 128, 96)
    const col = kind === 'green' ? '#38ff7a' : kind === 'amber' ? '#ffb030' : '#58c8ff'
    g.strokeStyle = col
    g.fillStyle = col
    g.lineWidth = 2
    if (kind === 'green') {
      g.beginPath()
      for (let x = 0; x < 128; x++) g.lineTo(x, 48 + Math.sin(x * 0.18) * 22 * Math.sin(x * 0.03))
      g.stroke()
      g.globalAlpha = 0.25
      for (let x = 0; x < 128; x += 16) g.fillRect(x, 0, 1, 96)
      for (let y = 0; y < 96; y += 16) g.fillRect(0, y, 128, 1)
    } else {
      g.font = '10px monospace'
      for (let i = 0; i < 8; i++) g.fillText(['> SCAN 147.2', 'SIG ######..', 'CH 7  OK', '> LINK UP', 'PWR 12.4V', 'TMP 41C', '> ...', 'RX -82dB'][i], 6, 12 + i * 11)
    }
    const t = new THREE.CanvasTexture(c)
    t.colorSpace = THREE.SRGBColorSpace
    return new THREE.MeshStandardMaterial({ map: t, emissiveMap: t, emissive: '#ffffff', emissiveIntensity: 2.2, roughness: 0.3 })
  })
}

// ---------------------------------------------------------------- containers
export function crate(b, o = {}) {
  const w = o.w ?? 0.6
  const h = o.h ?? 0.5
  const d = o.d ?? 0.6
  const c = o.color ?? '#e8d8be'
  const m = { mat: 'wood', color: c }
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(w - 0.03, h - 0.03, d - 0.03, { ...m, y: h / 2, color: '#8a7058' })
    const n = Math.max(2, Math.round(h / 0.17))
    const bh = h / n
    for (let i = 0; i < n; i++) {
      const y = bh * i + bh / 2
      b.box(w, bh - 0.012, 0.018, { ...m, y, z: d / 2 - 0.009, jitter: 0.06 })
      b.box(w, bh - 0.012, 0.018, { ...m, y, z: -d / 2 + 0.009, jitter: 0.06 })
      b.box(0.018, bh - 0.012, d - 0.04, { ...m, y, x: w / 2 - 0.009, jitter: 0.06 })
      b.box(0.018, bh - 0.012, d - 0.04, { ...m, y, x: -w / 2 + 0.009, jitter: 0.06 })
    }
    // corner battens and frame
    for (const sx of [-1, 1]) {
      for (const sz of [-1, 1]) {
        b.box(0.05, h, 0.024, { ...m, color: shadeHex(c, -0.1), x: sx * (w / 2 - 0.025), y: h / 2, z: sz * (d / 2 + 0.006) })
        b.box(0.024, h, 0.05, { ...m, color: shadeHex(c, -0.1), x: sx * (w / 2 + 0.006), y: h / 2, z: sz * (d / 2 - 0.025) })
      }
      b.box(0.024, 0.05, d, { ...m, color: shadeHex(c, -0.1), x: sx * (w / 2 + 0.006), y: h - 0.025 })
      b.box(w, 0.05, 0.024, { ...m, color: shadeHex(c, -0.1), z: sx * (d / 2 + 0.006), y: h - 0.025 })
    }
    if (o.lid !== false) {
      const nl = Math.max(2, Math.round(w / 0.15))
      for (let i = 0; i < nl; i++) b.box(w / nl - 0.01, 0.02, d, { ...m, x: -w / 2 + (w / nl) * (i + 0.5), y: h + 0.01, jitter: 0.05 })
    }
    if (o.stencil) b.box(w * 0.5, h * 0.25, 0.002, { mat: 'plain', color: o.stencil, y: h * 0.55, z: d / 2 + 0.021, ao: 0 })
  })
}
export function shadeHex(hex, k) {
  const c = new THREE.Color(hex)
  if (k < 0) c.multiplyScalar(1 + k)
  else c.lerp(new THREE.Color('#ffffff'), k)
  return '#' + c.getHexString()
}
export function ammoCrate(b, o = {}) {
  const c = o.color ?? '#5a6440'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.62, 0.3, 0.32, { mat: 'paint', color: c, y: 0.15, r: 0.012 })
    b.box(0.64, 0.04, 0.34, { mat: 'paint', color: shadeHex(c, -0.15), y: 0.29, r: 0.008 })
    for (const sx of [-1, 1]) {
      b.box(0.03, 0.06, 0.02, { mat: 'steel', color: '#9a9a90', x: sx * 0.22, y: 0.2, z: 0.17 })
      b.torus(0.04, 0.008, { mat: 'steel', color: '#7a7a72', x: sx * 0.33, y: 0.18, rz: Math.PI / 2, ry: Math.PI / 2 })
    }
    b.box(0.3, 0.06, 0.002, { mat: 'plain', color: '#e8d870', y: 0.15, z: 0.161, ao: 0 })
  })
}
export function barrel(b, o = {}) {
  const r = o.r ?? 0.29
  const h = o.h ?? 0.88
  const c = o.color ?? '#3a5878'
  const rnd = seeded(o.seed ?? Math.floor((o.x || 0) * 97 + (o.z || 0) * 13))
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0, rx: o.rx || 0, rz: o.rz || 0 }, () => {
    b.cyl(r, r, h, { mat: 'paint', color: c, y: h / 2, seg: 20 })
    for (const y of [h * 0.33, h * 0.66]) b.torus(r + 0.004, 0.011, { mat: 'paint', color: shadeHex(c, -0.12), y, rx: Math.PI / 2, ts2: 28 })
    for (const y of [0.012, h - 0.012]) b.torus(r - 0.004, 0.014, { mat: 'paint', color: shadeHex(c, -0.2), y, rx: Math.PI / 2, ts2: 28 })
    if (o.open) {
      b.cyl(r - 0.01, r - 0.01, 0.01, { mat: 'plain', color: o.contents || '#1a1612', y: h * (o.fill ?? 0.8) })
    } else {
      b.cyl(r - 0.012, r - 0.012, 0.01, { mat: 'paint', color: shadeHex(c, -0.08), y: h - 0.015 })
      b.cyl(0.03, 0.03, 0.02, { mat: 'steel', color: '#8a8a84', y: h - 0.005, x: r * 0.55 })
      b.cyl(0.018, 0.018, 0.02, { mat: 'steel', color: '#8a8a84', y: h - 0.005, x: -r * 0.55, z: 0.06 })
    }
    if ((o.rust ?? 0.5) > 0) {
      const n = 1 + Math.floor(rnd() * 3)
      for (let i = 0; i < n; i++) {
        const tl = 0.6 + rnd() * 1.4
        const hh = 0.15 + rnd() * h * 0.5
        b.cyl(r + 0.003, r + 0.003, hh, { mat: 'rust', color: '#ffffff', y: hh / 2 + rnd() * (h - hh) * 0.4, ts: rnd() * TAU, tl, open: true, seg: 14, shadow: false })
      }
    }
    if (o.label) b.cyl(r + 0.002, r + 0.002, 0.18, { mat: 'plain', color: o.label, y: h * 0.5, ts: 0.3, tl: 1.2, open: true, seg: 10, shadow: false })
  })
}
export function jerrycan(b, o = {}) {
  const c = o.color ?? '#a8382a'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.17, 0.44, 0.34, { mat: 'paint', color: c, y: 0.22, r: 0.03 })
    for (const sx of [-1, 1]) b.box(0.006, 0.26, 0.04, { mat: 'paint', color: shadeHex(c, -0.2), x: sx * 0.086, y: 0.22, rx: 0.6 })
    for (const dz of [-0.09, 0, 0.09]) b.box(0.03, 0.05, 0.03, { mat: 'paint', color: c, y: 0.46, z: dz - 0.03 })
    b.box(0.03, 0.02, 0.22, { mat: 'paint', color: c, y: 0.49, z: -0.03 })
    b.cyl(0.024, 0.024, 0.05, { mat: 'paint', color: '#2a2a2a', y: 0.46, z: 0.12, rx: -0.5 })
  })
}
export function sack(b, o = {}) {
  const c = o.color ?? '#cdb88e'
  const w = o.w ?? 0.42
  const d = o.d ?? 0.7
  const h = o.h ?? 0.26
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0, rz: o.rz || 0 }, () => {
    b.box(w, h, d, { mat: 'cloth', color: c, y: h / 2, r: Math.min(h / 2 - 0.01, 0.12), seg: 3, jitter: 0.08 })
    b.cyl(0.05, 0.08, 0.08, { mat: 'cloth', color: c, z: d / 2 + 0.02, y: h / 2, rx: Math.PI / 2 })
    b.torus(0.045, 0.01, { mat: 'cloth', color: COL.rope, z: d / 2 + 0.035, y: h / 2 })
    if (o.print) b.box(w * 0.5, 0.003, d * 0.3, { mat: 'plain', color: o.print, y: h + 0.001, ao: 0 })
  })
}
export function sandbags(b, o = {}) {
  const len = o.len ?? 2
  const rows = o.rows ?? 3
  const rnd = seeded(o.seed ?? 11)
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (let r = 0; r < rows; r++) {
      const n = Math.max(1, Math.round(len / 0.5))
      const off = r % 2 ? 0.25 : 0
      for (let i = 0; i < n - (r % 2 ? 1 : 0); i++) {
        const x = -len / 2 + 0.25 + i * 0.5 + off
        const tone = pick(rnd, ['#bfa97c', '#b09a6c', '#c8b488', '#a89470', '#9aa078'])
        b.box(0.5, 0.15, 0.3, { mat: 'cloth', color: tone, x, y: 0.075 + r * 0.14, z: (rnd() - 0.5) * 0.04, ry: (rnd() - 0.5) * 0.12, r: 0.06, seg: 2 })
      }
    }
  })
}
export function pallet(b, o = {}) {
  const w = o.w ?? 1.2
  const d = o.d ?? 1.0
  const c = o.color ?? '#d8c8ae'
  const m = { mat: 'wood', color: c, jitter: 0.08 }
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (const x of [-w / 2 + 0.05, 0, w / 2 - 0.05]) b.box(0.09, 0.09, d, { ...m, x, y: 0.035 + 0.025, color: shadeHex(c, -0.1) })
    const nt = 7
    for (let i = 0; i < nt; i++) b.box(w, 0.022, 0.1, { ...m, y: 0.116, z: -d / 2 + 0.05 + (i * (d - 0.1)) / (nt - 1) })
    for (const z of [-d / 2 + 0.05, 0, d / 2 - 0.05]) b.box(w, 0.02, 0.1, { ...m, y: 0.01, z })
  })
  return 0.127
}
export function tireStack(b, o = {}) {
  const n = o.n ?? 3
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0 }, () => {
    for (let i = 0; i < n; i++) {
      b.torus(0.3, 0.11, { mat: 'rubber', color: '#202020', y: 0.11 + i * 0.205, rx: Math.PI / 2, x: (i % 2) * 0.03, rs: 10, ts2: 24 })
      b.cyl(0.21, 0.21, 0.02, { mat: 'plain', color: '#0e0e0e', y: 0.11 + i * 0.205, shadow: false })
    }
  })
}
export function tire(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0, rx: o.rx ?? Math.PI / 2, rz: o.rz || 0 }, () => {
    b.torus(0.3, 0.11, { mat: 'rubber', color: '#202020', rs: 10, ts2: 24 })
    if (o.rim) b.cyl(0.2, 0.2, 0.16, { mat: 'steel', color: '#8a8e90', rx: Math.PI / 2, seg: 16 })
  })
}

// ---------------------------------------------------------------- timber
export function log(b, o = {}) {
  const len = o.len ?? 2
  const r = o.r ?? 0.14
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0, rz: o.rz ?? Math.PI / 2, rx: o.rx || 0 }, () => {
    b.cyl(r * 0.96, r, len, { mat: 'bark', color: o.color ?? '#ffffff', seg: 10 })
    b.cyl(r * 0.9, r * 0.9, 0.012, { mat: 'wood', color: '#f0d8b0', y: len / 2 + 0.002, seg: 10 })
    b.cyl(r * 0.94, r * 0.94, 0.012, { mat: 'wood', color: '#f0d8b0', y: -len / 2 - 0.002, seg: 10 })
  })
}
export function logPile(b, o = {}) {
  const len = o.len ?? 2.4
  const rows = o.rows ?? 3
  const rnd = seeded(o.seed ?? 5)
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    const r = 0.15
    for (let row = 0; row < rows; row++) {
      const n = rows - row + 1
      for (let i = 0; i < n; i++) {
        const z = (i - (n - 1) / 2) * r * 2.02
        log(b, { x: (rnd() - 0.5) * 0.2, y: r + row * r * 1.75, z, len: len * (0.9 + rnd() * 0.15), r: r * (0.85 + rnd() * 0.3), rz: Math.PI / 2 })
      }
    }
    // chocks
    for (const sx of [-1, 1]) b.box(0.12, 0.3, 0.12, { mat: 'wood', color: COL.woodGrey, x: sx * len * 0.42, y: 0.15, z: ((rows + 1) * r * 2.02) / 2 + 0.05 })
  })
}
export function plankStack(b, o = {}) {
  const len = o.len ?? 2.4
  const w = o.w ?? 0.9
  const layers = o.layers ?? 5
  const rnd = seeded(o.seed ?? 9)
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    let y = 0
    for (let L = 0; L < layers; L++) {
      // spacer sticks
      for (const sx of [-0.4, 0, 0.4]) b.box(0.04, 0.04, w, { mat: 'wood', color: COL.woodDark, x: sx * len, y: y + 0.02 })
      y += 0.04
      const n = Math.floor(w / 0.16)
      for (let i = 0; i < n; i++) b.box(len * (0.94 + rnd() * 0.06), 0.03, 0.15, { mat: 'wood', color: shadeHex('#f4e4c8', -rnd() * 0.12), x: (rnd() - 0.5) * 0.06, y: y + 0.015, z: -w / 2 + 0.08 + i * 0.16 })
      y += 0.03
    }
  })
}
export function firewood(b, o = {}) {
  const len = o.len ?? 1.4
  const h = o.h ?? 0.8
  const rnd = seeded(o.seed ?? 3)
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (const sx of [-1, 1]) b.box(0.07, h + 0.15, 0.07, { mat: 'wood', color: COL.woodGrey, x: sx * (len / 2 + 0.04), y: (h + 0.15) / 2 })
    const rows = Math.floor(h / 0.12)
    for (let r = 0; r < rows; r++) {
      const n = Math.floor(len / 0.13)
      for (let i = 0; i < n; i++) {
        const ts = rnd() * TAU
        b.cyl(0.075, 0.075, 0.38, { mat: 'bark', color: '#ffffff', x: -len / 2 + 0.065 + i * 0.13, y: 0.07 + r * 0.12, z: (rnd() - 0.5) * 0.04, rx: Math.PI / 2, ts, tl: Math.PI * (1 + rnd() * 0.6), seg: 7 })
        b.box(0.11, 0.1, 0.005, { mat: 'wood', color: '#f0d0a0', x: -len / 2 + 0.065 + i * 0.13, y: 0.07 + r * 0.12, z: 0.19, ry: 0, rz: rnd(), ao: 0 })
      }
    }
    if (o.roof !== false) b.box(len + 0.3, 0.03, 0.55, { mat: 'corrugated', color: '#a8a8a0', y: h + 0.17, rx: 0.12 })
  })
}
export function stump(b, o = {}) {
  const r = o.r ?? 0.28
  const h = o.h ?? 0.45
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.cyl(r, r * 1.15, h, { mat: 'bark', color: '#ffffff', y: h / 2, seg: 11 })
    b.cyl(r * 0.94, r * 0.94, 0.015, { mat: 'wood', color: '#e8cfa0', y: h + 0.002, seg: 11 })
    for (let i = 0; i < 4; i++) {
      const a = (i / 4) * TAU + 0.4
      b.box(0.09, 0.12, r * 0.9, { mat: 'bark', color: '#ffffff', x: Math.cos(a) * r * 1.05, y: 0.05, z: Math.sin(a) * r * 1.05, ry: -a + Math.PI / 2, rx: 0.5 })
    }
  })
}
export function scrapPile(b, o = {}) {
  const rad = o.r ?? 1.2
  const rnd = seeded(o.seed ?? 21)
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    // mound under the junk
    b.sphere(rad, { mat: 'dirt', color: '#8a7a68', sy: 0.28, y: -0.05, ws: 12, hs: 6, tl: Math.PI / 2 })
    const n = o.n ?? 16
    for (let i = 0; i < n; i++) {
      const a = rnd() * TAU
      const d = Math.sqrt(rnd()) * rad * 0.8
      const x = Math.cos(a) * d
      const z = Math.sin(a) * d
      const y = (1 - d / rad) * rad * 0.25 + 0.05
      const k = rnd()
      if (k < 0.3) b.box(0.6 + rnd() * 0.7, 0.015, 0.5 + rnd() * 0.4, { mat: 'corrugated', color: pick(rnd, ['#b8b0a8', '#a89a8a', '#8aa0a8', '#c0a890']), x, y: y + 0.1, z, rx: (rnd() - 0.5) * 0.9, ry: rnd() * TAU, rz: (rnd() - 0.5) * 0.9 })
      else if (k < 0.5) b.cyl(0.03 + rnd() * 0.04, 0.03 + rnd() * 0.04, 0.6 + rnd() * 1, { mat: 'rust', color: '#ffffff', x, y: y + 0.1, z, rx: Math.PI / 2 + (rnd() - 0.5) * 0.6, rz: rnd() * TAU, seg: 8 })
      else if (k < 0.62) tire(b, { x, y: y + 0.08, z, rx: Math.PI / 2 + (rnd() - 0.5) * 0.8, rz: rnd() })
      else if (k < 0.75) b.box(0.3 + rnd() * 0.3, 0.2 + rnd() * 0.3, 0.25 + rnd() * 0.3, { mat: 'metal', color: pick(rnd, ['#5a6a78', '#8a4a3a', '#6a7a5a', '#9a9a90']), x, y: y + 0.15, z, rx: (rnd() - 0.5) * 0.5, ry: rnd() * TAU, rz: (rnd() - 0.5) * 0.5 })
      else if (k < 0.85) b.torus(0.15 + rnd() * 0.12, 0.025, { mat: 'rust', color: '#ffffff', x, y: y + 0.1, z, rx: rnd() * 3, ry: rnd() * 3, arc: Math.PI * (1 + rnd()) })
      else b.box(0.08, 0.08, 0.8 + rnd() * 0.8, { mat: 'rust', color: '#ffffff', x, y: y + 0.1, z, rx: (rnd() - 0.5) * 0.6, ry: rnd() * TAU })
    }
  })
}

// ---------------------------------------------------------------- shelter
// A tarp strung between poles with guy ropes. corners: heights [NW, NE, SE, SW].
export function tarp(b, o = {}) {
  const w = o.w ?? 3
  const d = o.d ?? 2.5
  const corners = o.corners ?? [2.2, 2.2, 1.8, 1.8]
  const color = o.color ?? COL.tarpBlue
  const rb = o.rb || b
  rb.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => rb.cloth(w, d, { mat: 'canvas', color, corners, sag: o.sag ?? 0.14, seed: o.seed ?? 3, wrinkle: 0.05 }))
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    const P = [
      [-w / 2, corners[0], -d / 2],
      [w / 2, corners[1], -d / 2],
      [w / 2, corners[2], d / 2],
      [-w / 2, corners[3], d / 2],
    ]
    P.forEach(([x, y, z], i) => {
      if (o.poles !== false && !(o.skipPoles || []).includes(i)) {
        b.cyl(0.035, 0.04, y + 0.06, { mat: o.metalPoles ? 'steel' : 'wood', color: o.metalPoles ? '#8a9096' : COL.woodGrey, x, y: (y + 0.06) / 2, z, seg: 7 })
        if (o.guys !== false) {
          const gx = x + Math.sign(x) * 0.9
          const gz = z + Math.sign(z) * 0.6
          b.rope([[x, y, z], [gx, 0.03, gz]], 0.008, { mat: 'cloth', color: COL.rope, sag: 0.02, steps: 3 })
          b.box(0.03, 0.12, 0.03, { mat: 'wood', color: COL.woodDark, x: gx, y: 0.03, z: gz, rx: 0.3 })
        }
      }
      b.torus(0.025, 0.006, { mat: 'steel', color: '#c8c8c0', x, y: y - 0.01, z, rx: Math.PI / 2 })
    })
  })
}
// Corrugated sheet roof on an x-y plane, sloping down towards +z.
export function corrRoof(b, o = {}) {
  const w = o.w ?? 3
  const d = o.d ?? 2.5
  const y0 = o.y ?? 2.4
  const drop = o.drop ?? 0.35
  const rnd = seeded(o.seed ?? 4)
  const slope = Math.atan2(drop, d)
  const len = Math.hypot(d, drop)
  const n = Math.max(1, Math.round(w / 0.8))
  b.at({ x: o.x || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (let i = 0; i < n; i++) {
      const x = -w / 2 + (w / n) * (i + 0.5)
      const tone = o.color ?? pick(rnd, ['#8e8a84', '#7e8488', '#948a7e', '#86827a', '#7a7a74'])
      b.box(w / n + 0.08, 0.025, len + 0.12, { mat: 'corrugated', color: tone, x, y: y0 - drop / 2 + (i % 2) * 0.012, rx: slope, jitter: 0.05 })
    }
    if (o.gutter) b.cyl(0.06, 0.06, w, { mat: 'steel', color: '#8a9094', x: 0, y: y0 - drop - 0.05, z: d / 2 + 0.05, rz: Math.PI / 2, ts: 0, tl: Math.PI, open: true, seg: 10 })
  })
}
// A simple gabled roof (two sloped planes) for cabins. Ridge runs along x.
export function gableRoof(b, o = {}) {
  const w = o.w ?? 4
  const d = o.d ?? 3
  const y0 = o.y ?? 2.4
  const rise = o.rise ?? 1
  const over = o.over ?? 0.3
  const mat = o.mat ?? 'roofmetal'
  const color = o.color ?? '#8a3a2a'
  const run = d / 2 + over
  const drop = rise * (run / (d / 2))
  const len = Math.hypot(run, drop)
  const slope = Math.atan2(rise, d / 2)
  b.at({ x: o.x || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (const s of [-1, 1]) b.box(w + over * 2, 0.06, len, { mat, color, y: y0 + rise - drop / 2 + 0.03, z: (s * run) / 2, rx: s * slope })
    b.box(w + over * 2 + 0.02, 0.08, 0.18, { mat: 'metal', color: shadeHex(color, -0.25), y: y0 + rise + 0.06 })
    if (o.gables !== false) for (const sx of [-1, 1]) b.wedge(d, rise, 0.05, { mat: o.gableMat || 'planks', color: o.gableColor || '#e8dcc8', x: (sx * w) / 2, y: y0, ry: Math.PI / 2 })
    // fascia boards under the eaves
    for (const s of [-1, 1]) b.box(w + over * 2, 0.12, 0.04, { mat: 'wood', color: o.fascia || '#e8dcc8', y: y0 + rise - drop - 0.02, z: s * run })
  })
}

// ---------------------------------------------------------------- lights
// Returns world-space light anchors the scene can use for point lights.
export function lantern(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0 }, () => {
    b.cyl(0.07, 0.08, 0.04, { mat: 'paint', color: '#2a2e2a', y: 0.02 })
    b.cyl(0.055, 0.055, 0.14, { mat: 'glass', color: '#f8e8c8', y: 0.11 })
    b.sphere(0.035, { mat: o.glow ?? 'nightGlow', color: '#ffffff', y: 0.11, shadow: false })
    for (let i = 0; i < 4; i++) b.box(0.008, 0.15, 0.008, { mat: 'paint', color: '#2a2e2a', x: Math.cos((i * TAU) / 4) * 0.06, z: Math.sin((i * TAU) / 4) * 0.06, y: 0.11 })
    b.cone(0.08, 0.06, { mat: 'paint', color: '#2a2e2a', y: 0.21, seg: 10 })
    b.torus(0.04, 0.006, { mat: 'steel', color: '#5a5a5a', y: 0.27 })
  })
  return [o.x || 0, (o.y || 0) + 0.11, o.z || 0]
}
export function lampPost(b, o = {}) {
  const h = o.h ?? 2.4
  b.at({ x: o.x || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.09, h, 0.09, { mat: 'wood', color: COL.woodGrey, y: h / 2 })
    b.box(0.5, 0.06, 0.06, { mat: 'wood', color: COL.woodGrey, y: h - 0.1, x: 0.22 })
    b.beam([0.02, h - 0.45, 0], [0.32, h - 0.12, 0], 0.04, 0.04, { mat: 'wood', color: COL.woodGrey })
    b.box(0.006, 0.12, 0.006, { mat: 'steel', color: '#444', x: 0.44, y: h - 0.19 })
  })
  return lantern(b, { x: (o.x || 0) + Math.cos(o.ry || 0) * 0.44, y: h - 0.52, z: (o.z || 0) - Math.sin(o.ry || 0) * 0.44 })
}
export function bulb(b, o = {}) {
  b.sphere(o.r ?? 0.04, { mat: o.glow ?? 'nightGlow', color: '#ffffff', x: o.x || 0, y: o.y || 0, z: o.z || 0, shadow: false, ws: 8, hs: 6 })
  b.cyl(0.018, 0.018, 0.04, { mat: 'paint', color: '#222', x: o.x || 0, y: (o.y || 0) + (o.r ?? 0.04) + 0.015, z: o.z || 0, seg: 6 })
}
// Festoon lights hanging between points.
export function stringLights(b, pts, o = {}) {
  const sag = o.sag ?? 0.25
  b.rope(pts, 0.008, { mat: 'rubber', color: '#1a1a1a', sag, steps: 10 })
  const out = []
  for (let i = 0; i < pts.length - 1; i++) {
    const A = pts[i]
    const B = pts[i + 1]
    const len = Math.hypot(B[0] - A[0], B[2] - A[2])
    const n = Math.max(2, Math.round(len / (o.spacing ?? 0.6)))
    for (let k = 1; k < n; k++) {
      const t = k / n
      const y = A[1] + (B[1] - A[1]) * t - Math.sin(t * Math.PI) * sag * Math.max(0.3, len / 3)
      const p = [A[0] + (B[0] - A[0]) * t, y - 0.05, A[2] + (B[2] - A[2]) * t]
      bulb(b, { x: p[0], y: p[1], z: p[2], r: 0.03, glow: o.glow })
      out.push(p)
    }
  }
  return out
}

// ---------------------------------------------------------------- furniture
export function table(b, o = {}) {
  const w = o.w ?? 1.6
  const d = o.d ?? 0.8
  const h = o.h ?? 0.78
  const c = o.color ?? '#e8d8c0'
  const m = { mat: o.mat ?? 'wood', color: c }
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    const n = Math.max(2, Math.round(d / 0.2))
    for (let i = 0; i < n; i++) b.box(w, 0.045, d / n - 0.008, { ...m, y: h - 0.022, z: -d / 2 + (d / n) * (i + 0.5), jitter: 0.06 })
    for (const sx of [-1, 1]) {
      for (const sz of [-1, 1]) b.box(0.07, h - 0.045, 0.07, { ...m, color: shadeHex(c, -0.08), x: sx * (w / 2 - 0.08), y: (h - 0.045) / 2, z: sz * (d / 2 - 0.08) })
      b.box(0.04, 0.09, d - 0.16, { ...m, color: shadeHex(c, -0.12), x: sx * (w / 2 - 0.08), y: h - 0.1 })
    }
    for (const sz of [-1, 1]) b.box(w - 0.16, 0.09, 0.04, { ...m, color: shadeHex(c, -0.12), z: sz * (d / 2 - 0.08), y: h - 0.1 })
    if (o.stretcher !== false) b.box(w - 0.2, 0.05, 0.05, { ...m, color: shadeHex(c, -0.12), y: 0.18 })
    if (o.shelf) b.box(w - 0.16, 0.025, d - 0.16, { ...m, y: 0.2 })
  })
}
export function stool(b, o = {}) {
  const h = o.h ?? 0.5
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.cyl(0.18, 0.18, 0.04, { mat: 'wood', color: o.color ?? '#e8d0b0', y: h - 0.02, seg: 12 })
    for (let i = 0; i < 3; i++) {
      const a = (i / 3) * TAU
      b.beam([Math.cos(a) * 0.1, h - 0.03, Math.sin(a) * 0.1], [Math.cos(a) * 0.17, 0, Math.sin(a) * 0.17], 0.035, 0.035, { mat: 'wood', color: COL.woodDark })
    }
  })
}
export function bench(b, o = {}) {
  const len = o.len ?? 1.6
  const c = o.color ?? '#e0ccae'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (const dz of [-0.1, 0.1]) b.box(len, 0.04, 0.17, { mat: 'wood', color: c, y: 0.45, z: dz, jitter: 0.05 })
    for (const sx of [-1, 1]) {
      b.box(0.06, 0.43, 0.32, { mat: 'wood', color: shadeHex(c, -0.12), x: sx * (len / 2 - 0.12), y: 0.215 })
    }
  })
}
export function logBench(b, o = {}) {
  const len = o.len ?? 2
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (const sx of [-1, 1]) stump(b, { x: sx * (len / 2 - 0.3), r: 0.17, h: 0.3 })
    b.cyl(0.2, 0.2, len, { mat: 'bark', color: '#ffffff', y: 0.36, rz: Math.PI / 2, ts: Math.PI, tl: Math.PI, seg: 12 })
    b.box(len, 0.012, 0.38, { mat: 'wood', color: '#e0c49a', y: 0.36 })
  })
}
export function campChair(b, o = {}) {
  const c = o.color ?? '#3a5a7a'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (const sx of [-1, 1]) {
      b.beam([sx * 0.25, 0, -0.22], [sx * 0.25, 0.45, 0.2], 0.02, 0.02, { mat: 'steel', color: '#7a8086', round: true })
      b.beam([sx * 0.25, 0, 0.22], [sx * 0.25, 0.45, -0.2], 0.02, 0.02, { mat: 'steel', color: '#7a8086', round: true })
      b.beam([sx * 0.25, 0.42, -0.2], [sx * 0.25, 0.85, -0.3], 0.02, 0.02, { mat: 'steel', color: '#7a8086', round: true })
      b.box(0.06, 0.03, 0.4, { mat: 'plastic', color: '#2a2a2a', x: sx * 0.27, y: 0.62 })
    }
    b.cloth(0.48, 0.42, { mat: 'cloth', color: c, corners: [0.44, 0.44, 0.44, 0.44], sag: 0.05, segX: 4, segZ: 4 })
    b.box(0.48, 0.42, 0.01, { mat: 'cloth', color: c, y: 0.66, z: -0.25, rx: -0.2 })
  })
}
export function cot(b, o = {}) {
  const c = o.blanket ?? '#5a6a4a'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (const sx of [-1, 1]) b.cyl(0.018, 0.018, 1.9, { mat: 'steel', color: '#5a6250', x: sx * 0.33, y: 0.42, rx: Math.PI / 2 })
    for (const sz of [-0.85, 0.85]) {
      b.beam([-0.33, 0.42, sz], [0.33, 0.05, sz], 0.025, 0.025, { mat: 'steel', color: '#5a6250', round: true })
      b.beam([0.33, 0.42, sz], [-0.33, 0.05, sz], 0.025, 0.025, { mat: 'steel', color: '#5a6250', round: true })
    }
    b.box(0.66, 0.03, 1.9, { mat: 'canvas', color: '#6a7450', y: 0.43 })
    b.box(0.6, 0.07, 1.2, { mat: 'cloth', color: c, y: 0.48, z: 0.25, r: 0.03 })
    b.box(0.42, 0.09, 0.28, { mat: 'cloth', color: '#e8e4dc', y: 0.5, z: -0.72, r: 0.04 })
  })
  return { lie: [o.x || 0, (o.y || 0) + 0.5, o.z || 0] }
}
export function bedroll(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.7, 0.06, 1.8, { mat: 'cloth', color: o.color ?? '#4a5a6a', y: 0.03, r: 0.03 })
    b.cyl(0.13, 0.13, 0.7, { mat: 'cloth', color: shadeHex(o.color ?? '#4a5a6a', -0.15), y: 0.13, z: -0.85, rz: Math.PI / 2 })
    b.box(0.4, 0.08, 0.25, { mat: 'cloth', color: '#d8d0c0', y: 0.1, z: -0.6, r: 0.04 })
  })
}
export function shelf(b, o = {}) {
  const w = o.w ?? 1.2
  const h = o.h ?? 1.8
  const d = o.d ?? 0.4
  const levels = o.levels ?? 4
  const rnd = seeded(o.seed ?? 17)
  const kind = o.kind ?? 'mixed'
  const metal = o.metal
  const fm = metal ? { mat: 'paint', color: o.color ?? '#5a6068' } : { mat: 'wood', color: o.color ?? '#e0ccb0' }
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.045, h, 0.045, { ...fm, x: sx * (w / 2 - 0.022), y: h / 2, z: sz * (d / 2 - 0.022) })
    for (let L = 0; L < levels; L++) {
      const y = 0.12 + (L * (h - 0.2)) / (levels - 1)
      b.box(w, 0.025, d, { ...fm, y })
      if (L === levels - 1 && o.topEmpty) continue
      if (rnd() > (o.fill ?? 0.8) + 0.15) continue
      shelfItems(b, rnd, kind, w - 0.08, d - 0.06, y + 0.0125, L === levels - 1 ? 0.3 : (h - 0.2) / (levels - 1) - 0.05, o.fill ?? 0.8)
    }
    if (o.back) b.box(w, h, 0.012, { mat: metal ? 'metal' : 'wood', color: metal ? '#7a8088' : '#c8b498', y: h / 2, z: -d / 2 + 0.006 })
  })
}
export function shelfItems(b, rnd, kind, w, d, y, maxH, fill = 0.8) {
  let x = -w / 2
  while (x < w / 2 - 0.05) {
    const k = kind === 'mixed' ? pick(rnd, ['cans', 'jars', 'boxes', 'bottles', 'boxes']) : kind
    let iw = 0.1
    if (rnd() > fill) {
      x += 0.12 + rnd() * 0.15
      continue
    }
    if (k === 'cans') {
      const col = pick(rnd, ['#c84a3a', '#d8b03a', '#3a7ab8', '#5a9a4a', '#e8e0d0'])
      const n = 1 + Math.floor(rnd() * 3)
      for (let i = 0; i < n && i * 0.08 < d; i++) {
        b.cyl(0.035, 0.035, 0.1, { mat: 'metal', color: '#c8ccd0', x: x + 0.04, y: y + 0.05, z: -d / 2 + 0.05 + i * 0.08, seg: 10 })
        b.cyl(0.036, 0.036, 0.065, { mat: 'plain', color: col, x: x + 0.04, y: y + 0.05, z: -d / 2 + 0.05 + i * 0.08, seg: 10, open: true })
      }
      iw = 0.085
    } else if (k === 'jars') {
      const col = pick(rnd, ['#c8502a', '#e8c040', '#7a9a3a', '#a83050', '#d88a3a'])
      b.cyl(0.045, 0.045, 0.14, { mat: 'glass', color: '#e8f0e8', x: x + 0.05, y: y + 0.07, z: 0, seg: 10 })
      b.cyl(0.04, 0.04, 0.1, { mat: 'plain', color: col, x: x + 0.05, y: y + 0.055, z: 0, seg: 10 })
      b.cyl(0.047, 0.047, 0.02, { mat: 'metal', color: '#c8b860', x: x + 0.05, y: y + 0.15, z: 0, seg: 10 })
      iw = 0.11
    } else if (k === 'bottles') {
      const col = pick(rnd, ['#3a6a3a', '#6a3a1a', '#d8d8e0', '#3a5a8a', '#c8e0c8'])
      b.lathe([[0.035, 0], [0.035, 0.14], [0.012, 0.19], [0.012, 0.23]], { mat: 'glass', color: col, x: x + 0.04, y, seg: 10 })
      iw = 0.085
    } else if (k === 'boxes') {
      const bw = Math.min(0.2 + rnd() * 0.2, w / 2 - x)
      const bh = Math.min(maxH, 0.12 + rnd() * 0.2)
      b.box(bw, bh, d * (0.7 + rnd() * 0.25), { mat: 'cloth', color: pick(rnd, ['#b48c5a', '#a8845a', '#c09a68', '#9a7a52']), x: x + bw / 2, y: y + bh / 2, z: 0, r: 0.01 })
      b.box(bw + 0.002, 0.03, 0.002, { mat: 'plain', color: '#d8c8a0', x: x + bw / 2, y: y + bh * 0.6, z: (d * 0.7) / 2 + 0.01, ao: 0 })
      iw = bw + 0.02
    } else if (k === 'fabric') {
      const col = pick(rnd, ['#8a3a3a', '#3a5a8a', '#5a6a3a', '#c8b898', '#4a4a4a', '#a87a3a'])
      b.cyl(0.07, 0.07, d * 0.9, { mat: 'cloth', color: col, x: x + 0.07, y: y + 0.07, rx: Math.PI / 2, seg: 10 })
      iw = 0.15
    } else if (k === 'tools') {
      const c2 = pick(rnd, ['#b8302a', '#2a5ab8', '#3a3a3a'])
      b.box(0.3, 0.12, 0.18, { mat: 'paint', color: c2, x: x + 0.15, y: y + 0.06, r: 0.01 })
      iw = 0.32
    } else if (k === 'chem') {
      const col = pick(rnd, ['#5ac83a', '#e8e040', '#3ab8e8', '#e85a3a', '#d8d8d8'])
      b.lathe([[0.05, 0], [0.05, 0.16], [0.02, 0.2], [0.02, 0.24]], { mat: 'plastic', color: col, x: x + 0.05, y, seg: 10 })
      iw = 0.11
    } else if (k === 'parts') {
      b.box(0.18, 0.1, d * 0.85, { mat: 'plastic', color: pick(rnd, ['#d84a2a', '#2a6ad8', '#e8c030', '#3a3a3a']), x: x + 0.09, y: y + 0.05, r: 0.008 })
      iw = 0.2
    }
    x += iw + 0.01
  }
}
export function toolWall(b, o = {}) {
  const w = o.w ?? 1.4
  const h = o.h ?? 0.9
  const rnd = seeded(o.seed ?? 31)
  b.at({ x: o.x || 0, y: o.y ?? 1.0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(w, h, 0.02, { mat: 'wood', color: '#c8a878', y: h / 2 })
    b.box(w + 0.06, 0.05, 0.04, { mat: 'wood', color: COL.woodDark, y: h + 0.02 })
    let x = -w / 2 + 0.12
    while (x < w / 2 - 0.1) {
      const k = Math.floor(rnd() * 6)
      const y = h * (0.3 + rnd() * 0.45)
      const z = 0.02
      if (k === 0) {
        // hammer
        b.box(0.025, 0.28, 0.02, { mat: 'wood', color: '#c89a60', x, y, z })
        b.box(0.11, 0.035, 0.03, { mat: 'steel', color: '#4a4e52', x, y: y + 0.15, z })
      } else if (k === 1) {
        // hand saw
        b.box(0.1, 0.42, 0.004, { mat: 'steel', color: '#b8bcc0', x: x + 0.02, y: y - 0.04, z, rz: 0.08 })
        b.box(0.1, 0.12, 0.025, { mat: 'wood', color: '#a8582a', x: x + 0.02, y: y + 0.22, z })
      } else if (k === 2) {
        // wrench
        b.box(0.025, 0.25, 0.008, { mat: 'chrome', color: '#d0d4d8', x, y, z })
        b.torus(0.03, 0.01, { mat: 'chrome', color: '#d0d4d8', x, y: y + 0.13, z, arc: Math.PI * 1.6 })
      } else if (k === 3) {
        // screwdrivers
        for (let i = 0; i < 3; i++) {
          b.cyl(0.012, 0.012, 0.1, { mat: 'plastic', color: pick(rnd, ['#d83a2a', '#e8c030', '#2a6ad8']), x: x + i * 0.04, y: y + 0.08, z })
          b.cyl(0.004, 0.004, 0.12, { mat: 'chrome', color: '#d0d4d8', x: x + i * 0.04, y: y - 0.03, z })
        }
      } else if (k === 4) {
        // pliers
        b.box(0.02, 0.2, 0.01, { mat: 'plastic', color: '#d83a2a', x: x - 0.015, y, z, rz: 0.1 })
        b.box(0.02, 0.2, 0.01, { mat: 'plastic', color: '#d83a2a', x: x + 0.015, y, z, rz: -0.1 })
      } else {
        // coil of wire / tape
        b.torus(0.06, 0.02, { mat: 'plastic', color: pick(rnd, ['#e8c030', '#3a3a3a', '#c86a2a']), x, y, z: z + 0.01 })
      }
      x += 0.12 + rnd() * 0.08
    }
  })
}
export function vise(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.16, 0.05, 0.18, { mat: 'paint', color: '#3a5a8a', y: 0.025 })
    b.box(0.14, 0.12, 0.08, { mat: 'paint', color: '#3a5a8a', y: 0.1, z: -0.03 })
    b.box(0.14, 0.12, 0.06, { mat: 'paint', color: '#3a5a8a', y: 0.1, z: 0.07 })
    b.cyl(0.012, 0.012, 0.3, { mat: 'chrome', color: '#d0d4d8', y: 0.1, z: 0.12, rx: Math.PI / 2 })
    b.cyl(0.008, 0.008, 0.2, { mat: 'chrome', color: '#d0d4d8', y: 0.1, z: 0.26, rz: Math.PI / 2 })
  })
}
export function anvil(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    if (o.stump !== false) stump(b, { r: 0.26, h: 0.5 })
    const y = o.stump !== false ? 0.5 : 0
    b.box(0.3, 0.06, 0.22, { mat: 'steel', color: '#3a3e42', y: y + 0.03 })
    b.box(0.16, 0.14, 0.12, { mat: 'steel', color: '#3a3e42', y: y + 0.13 })
    b.box(0.42, 0.09, 0.16, { mat: 'steel', color: '#55595d', y: y + 0.245 })
    b.cone(0.07, 0.2, { mat: 'steel', color: '#55595d', x: 0.3, y: y + 0.25, rz: -Math.PI / 2, seg: 10, sz: 0.8 })
    b.box(0.43, 0.004, 0.15, { mat: 'chrome', color: '#a8b0b8', y: y + 0.292, ao: 0 })
  })
  return (o.y || 0) + (o.stump !== false ? 0.79 : 0.29)
}
export function sawhorse(b, o = {}) {
  const len = o.len ?? 1
  const c = o.color ?? '#e0c8a0'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(len, 0.09, 0.09, { mat: 'wood', color: c, y: 0.72 })
    for (const sx of [-1, 1]) {
      for (const sz of [-1, 1]) b.beam([sx * (len / 2 - 0.1), 0.7, 0], [sx * (len / 2 - 0.02), 0, sz * 0.3], 0.05, 0.08, { mat: 'wood', color: shadeHex(c, -0.08) })
      b.box(0.02, 0.06, 0.5, { mat: 'wood', color: shadeHex(c, -0.08), x: sx * (len / 2 - 0.06), y: 0.3 })
    }
  })
}
export function ladder(b, o = {}) {
  const h = o.h ?? 3
  const lean = o.lean ?? 0.2
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0, rx: -lean }, () => {
    for (const sx of [-1, 1]) b.box(0.05, h, 0.07, { mat: o.mat ?? 'wood', color: o.color ?? COL.woodGrey, x: sx * 0.22, y: h / 2 })
    const n = Math.floor(h / 0.3)
    for (let i = 1; i <= n; i++) b.cyl(0.018, 0.018, 0.44, { mat: o.mat ?? 'wood', color: o.color ?? COL.woodGrey, y: i * 0.3 - 0.05, rz: Math.PI / 2 })
  })
}
export function toolbox(b, o = {}) {
  const c = o.color ?? '#b8302a'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.5, 0.2, 0.22, { mat: 'paint', color: c, y: 0.1, r: 0.01 })
    b.box(0.5, 0.012, 0.22, { mat: 'paint', color: shadeHex(c, -0.25), y: 0.15 })
    b.box(0.3, 0.025, 0.03, { mat: 'steel', color: '#8a8e92', y: 0.25 })
    for (const sx of [-1, 1]) b.box(0.025, 0.05, 0.03, { mat: 'steel', color: '#8a8e92', x: sx * 0.14, y: 0.22 })
  })
}
export function bucket(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.lathe([[0.1, 0], [0.13, 0.28], [0.135, 0.29]], { mat: o.mat ?? 'metal', color: o.color ?? '#a8aeb4', seg: 14 })
    b.cyl(0.098, 0.098, 0.01, { mat: 'plain', color: '#0a0a0a', y: 0.01 })
    if (o.water) b.cyl(0.12, 0.12, 0.005, { mat: 'water', color: '#5a7a8a', y: 0.22 })
    b.torus(0.13, 0.005, { mat: 'steel', color: '#7a7a7a', y: 0.29, rx: 0, rz: Math.PI / 2, arc: Math.PI, ry: o.handle ?? 0.3 })
  })
}
export function wheelbarrow(b, o = {}) {
  const c = o.color ?? '#3a7a4a'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.lathe([[0.18, 0], [0.34, 0.25]], { mat: 'paint', color: c, y: 0.35, sz: 1.5, seg: 4, ry: Math.PI / 4 })
    b.torus(0.17, 0.05, { mat: 'rubber', color: '#202020', z: 0.55, y: 0.18, ry: Math.PI / 2, rs: 8, ts2: 18 })
    for (const sx of [-1, 1]) {
      b.beam([sx * 0.16, 0.3, 0.55], [sx * 0.25, 0.55, -0.8], 0.03, 0.03, { mat: 'steel', color: '#5a5e62', round: true })
      b.beam([sx * 0.18, 0.32, -0.2], [sx * 0.2, 0, -0.32], 0.025, 0.025, { mat: 'steel', color: '#5a5e62', round: true })
    }
  })
}
export function hayBale(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.95, 0.45, 0.48, { mat: 'cloth', color: '#d8b860', y: 0.225, r: 0.06, jitter: 0.06 })
    for (const sx of [-0.25, 0.25]) b.box(0.012, 0.46, 0.49, { mat: 'cloth', color: '#8a6a3a', x: sx, y: 0.225 })
  })
}
export function gasBottle(b, o = {}) {
  const c = o.color ?? '#3a6a3a'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0 }, () => {
    b.capsule(0.11, 0.9, { mat: 'paint', color: c, y: 0.56, seg: 12 })
    b.cyl(0.03, 0.04, 0.08, { mat: 'chrome', color: '#d8c890', y: 1.16 })
    b.torus(0.04, 0.008, { mat: 'chrome', color: '#d8d8d0', y: 1.22, rx: Math.PI / 2 })
  })
}
export function cableReel(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (const sx of [-1, 1]) b.cyl(0.45, 0.45, 0.04, { mat: 'wood', color: '#d8b888', x: sx * 0.25, y: 0.45, rz: Math.PI / 2, seg: 16 })
    b.cyl(0.32, 0.32, 0.46, { mat: 'rubber', color: o.color ?? '#202020', y: 0.45, rz: Math.PI / 2, seg: 16 })
    b.cyl(0.08, 0.08, 0.6, { mat: 'wood', color: '#c8a878', y: 0.45, rz: Math.PI / 2, seg: 8 })
  })
}
export function sign(b, text, o = {}) {
  const w = o.w ?? 1.2
  const h = o.h ?? 0.32
  b.at({ x: o.x || 0, y: o.y ?? 1.6, z: o.z || 0, ry: o.ry || 0, rz: o.tilt || 0 }, () => {
    b.box(w + 0.04, h + 0.04, 0.03, { mat: 'wood', color: o.frame ?? COL.woodDark })
    b.plane(w, h, { material: signMat(text, { bg: o.bg ?? '#2a2e26', fg: o.fg ?? '#e8dcc0', w: 512, h: Math.round((512 * h) / w), font: o.font }), z: 0.017 })
  })
  if (o.post) {
    b.box(0.07, (o.y ?? 1.6) + 0.05, 0.07, { mat: 'wood', color: COL.woodGrey, x: (o.x || 0) - Math.cos(o.ry || 0) * (w / 2 - 0.1), y: ((o.y ?? 1.6) + 0.05) / 2, z: (o.z || 0) + Math.sin(o.ry || 0) * (w / 2 - 0.1) - 0.04 })
    b.box(0.07, (o.y ?? 1.6) + 0.05, 0.07, { mat: 'wood', color: COL.woodGrey, x: (o.x || 0) + Math.cos(o.ry || 0) * (w / 2 - 0.1), y: ((o.y ?? 1.6) + 0.05) / 2, z: (o.z || 0) - Math.sin(o.ry || 0) * (w / 2 - 0.1) - 0.04 })
  }
}

// ---------------------------------------------------------------- water & pipes
export function ibcTote(b, o = {}) {
  const level = o.level ?? 0.7
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    const py = pallet(b, { w: 1.2, d: 1.0, color: '#c8c8c0' })
    const H = 1.0
    b.box(1.12, H, 0.94, { mat: 'plastic', color: '#e8e6dc', y: py + H / 2, r: 0.06 })
    b.box(1.13, H * level, 0.95, { mat: 'plain', color: '#7a98a8', y: py + (H * level) / 2, r: 0.05, shadow: false })
    for (let i = 0; i <= 4; i++) {
      const y = py + 0.02 + (i * H) / 4
      b.box(1.18, 0.022, 0.022, { mat: 'steel', color: '#a8aeb2', y, z: 0.5 })
      b.box(1.18, 0.022, 0.022, { mat: 'steel', color: '#a8aeb2', y, z: -0.5 })
      b.box(0.022, 0.022, 1.0, { mat: 'steel', color: '#a8aeb2', y, x: 0.6 })
      b.box(0.022, 0.022, 1.0, { mat: 'steel', color: '#a8aeb2', y, x: -0.6 })
    }
    for (let i = 0; i <= 5; i++) {
      const x = -0.6 + (i * 1.2) / 5
      for (const z of [0.5, -0.5]) b.box(0.022, H, 0.022, { mat: 'steel', color: '#a8aeb2', x, y: py + H / 2, z })
    }
    for (let i = 1; i < 5; i++) for (const x of [0.6, -0.6]) b.box(0.022, H, 0.022, { mat: 'steel', color: '#a8aeb2', x, y: py + H / 2, z: -0.5 + (i * 1.0) / 5 })
    b.cyl(0.11, 0.11, 0.06, { mat: 'plastic', color: '#3a3a3a', y: py + H + 0.03, seg: 14 })
    b.cyl(0.05, 0.05, 0.14, { mat: 'plastic', color: '#3a3a3a', y: py + 0.12, z: 0.55, rx: Math.PI / 2 })
    b.box(0.08, 0.04, 0.04, { mat: 'paint', color: '#d83a2a', y: py + 0.17, z: 0.62 })
  })
}
export function waterTank(b, o = {}) {
  const r = o.r ?? 0.7
  const h = o.h ?? 1.8
  const c = o.color ?? '#3a5a3e'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0 }, () => {
    const prof = [[r, 0]]
    const ribs = 6
    for (let i = 1; i <= ribs; i++) {
      const y = (i * h) / (ribs + 1)
      prof.push([r, y - 0.05], [r + 0.03, y - 0.02], [r + 0.03, y + 0.02], [r, y + 0.05])
    }
    prof.push([r, h], [r * 0.85, h + 0.12], [0.2, h + 0.18], [0.2, h + 0.24], [0.001, h + 0.24])
    b.lathe(prof, { mat: 'plastic', color: c, seg: 22 })
    b.cyl(0.04, 0.04, 0.2, { mat: 'steel', color: '#8a8e90', y: 0.15, z: r + 0.08, rx: Math.PI / 2 })
    b.box(0.06, 0.06, 0.06, { mat: 'paint', color: '#d8a030', y: 0.15, z: r + 0.2 })
  })
}
export function pipe(b, pts, r, o = {}) {
  const m = { mat: o.mat ?? 'metal', color: o.color ?? '#8a9094' }
  for (let i = 0; i < pts.length - 1; i++) {
    b.beam(pts[i], pts[i + 1], r * 2, r * 2, { ...m, round: true })
    if (i > 0) b.sphere(r * 1.08, { ...m, x: pts[i][0], y: pts[i][1], z: pts[i][2], ws: 8, hs: 6 })
  }
  if (o.flanges) {
    for (const p of [pts[0], pts[pts.length - 1]]) b.sphere(r * 1.5, { ...m, x: p[0], y: p[1], z: p[2], ws: 8, hs: 6, sy: 0.6 })
  }
}
export function valveWheel(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, rx: o.rx || 0, ry: o.ry || 0 }, () => {
    b.torus(o.r ?? 0.1, 0.012, { mat: 'paint', color: o.color ?? '#c83a2a' })
    for (let i = 0; i < 3; i++) b.box(0.012, (o.r ?? 0.1) * 2, 0.012, { mat: 'paint', color: o.color ?? '#c83a2a', rz: (i * Math.PI) / 3 })
    b.cyl(0.02, 0.02, 0.05, { mat: 'steel', color: '#8a8a8a', rx: Math.PI / 2 })
  })
}
export function gauge(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.cyl(0.05, 0.05, 0.025, { mat: 'chrome', color: '#d0d4d8', rx: Math.PI / 2, seg: 14 })
    b.cyl(0.042, 0.042, 0.005, { mat: 'plain', color: '#f0ece0', z: 0.013, rx: Math.PI / 2, seg: 14, ao: 0 })
    b.box(0.003, 0.035, 0.002, { mat: 'plain', color: '#c82a1a', z: 0.017, rz: -0.6, y: 0.008, ao: 0 })
  })
}

// ---------------------------------------------------------------- electrics
export function carBattery(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.3, 0.2, 0.18, { mat: 'plastic', color: '#2a2a2e', y: 0.1, r: 0.01 })
    b.box(0.3, 0.02, 0.18, { mat: 'plastic', color: '#3a3a40', y: 0.21 })
    b.cyl(0.015, 0.015, 0.03, { mat: 'plain', color: '#c83a2a', x: 0.1, y: 0.235 })
    b.cyl(0.015, 0.015, 0.03, { mat: 'plain', color: '#2a2a2a', x: -0.1, y: 0.235 })
  })
}
export function radioSet(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.42, 0.18, 0.28, { mat: 'paint', color: '#3a4a3a', y: 0.09, r: 0.01 })
    b.plane(0.16, 0.08, { material: screenMat('amber'), x: -0.08, y: 0.1, z: 0.141 })
    for (let i = 0; i < 3; i++) b.cyl(0.018, 0.018, 0.02, { mat: 'plastic', color: '#1a1a1a', x: 0.06 + i * 0.05, y: 0.1, z: 0.145, rx: Math.PI / 2 })
    b.cyl(0.006, 0.006, 0.5, { mat: 'chrome', color: '#c8c8c8', x: 0.17, y: 0.43, z: -0.1, rz: -0.15 })
    b.box(0.06, 0.1, 0.03, { mat: 'plastic', color: '#1a1a1a', x: -0.26, y: 0.07, z: 0.06, ry: 0.4 })
  })
}
export function monitor(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.42, 0.32, 0.3, { mat: 'plastic', color: '#c8c4b8', y: 0.24, r: 0.02 })
    b.box(0.18, 0.06, 0.14, { mat: 'plastic', color: '#c8c4b8', y: 0.03 })
    b.plane(0.34, 0.25, { material: screenMat(o.kind ?? 'green'), y: 0.25, z: 0.151 })
  })
}
export function solarPanel(b, o = {}) {
  const w = o.w ?? 1.0
  const h = o.h ?? 1.6
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0, rx: o.tilt ?? -0.55 }, () => {
    b.box(w + 0.04, 0.04, h + 0.04, { mat: 'steel', color: '#b8bcc0' })
    b.plane(w, h, { material: solarMat(), y: 0.021, rx: -Math.PI / 2 })
  })
}

// ---------------------------------------------------------------- clothing & cloth
export function clothesline(b, a, c, o = {}) {
  const rnd = seeded(o.seed ?? 41)
  for (const p of [a, c]) b.box(0.07, p[1] + 0.05, 0.07, { mat: 'wood', color: COL.woodGrey, x: p[0], y: (p[1] + 0.05) / 2, z: p[2] })
  b.rope([a, c], 0.006, { mat: 'cloth', color: '#d8d0c0', sag: 0.08, steps: 10 })
  const len = Math.hypot(c[0] - a[0], c[2] - a[2])
  const n = Math.floor(len / 0.5)
  const ang = Math.atan2(c[2] - a[2], c[0] - a[0])
  for (let i = 1; i < n; i++) {
    if (rnd() < 0.2) continue
    const t = i / n
    const y = a[1] + (c[1] - a[1]) * t - Math.sin(t * Math.PI) * 0.08 * Math.max(0.3, len / 3)
    const x = a[0] + (c[0] - a[0]) * t
    const z = a[2] + (c[2] - a[2]) * t
    const col = pick(rnd, ['#8a3a3a', '#3a5a8a', '#e8e4dc', '#5a6a3a', '#c8a050', '#4a4a52', '#a85a7a'])
    const k = rnd()
    const cw = k < 0.4 ? 0.45 : k < 0.7 ? 0.3 : 0.55
    const ch = k < 0.4 ? 0.55 : k < 0.7 ? 0.7 : 0.4
    b.box(cw, ch, 0.01, { mat: 'cloth', color: col, x, y: y - ch / 2, z, ry: -ang, rx: (rnd() - 0.5) * 0.15, ao: 0 })
    if (k < 0.4) for (const sx of [-1, 1]) b.box(0.14, 0.2, 0.01, { mat: 'cloth', color: col, x: x + Math.cos(ang) * sx * (cw / 2 + 0.06), y: y - 0.12, z: z + Math.sin(ang) * sx * (cw / 2 + 0.06), ry: -ang, rz: sx * 0.5, ao: 0 })
  }
}
export function mannequin(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.cyl(0.18, 0.2, 0.03, { mat: 'paint', color: '#2a2a2a', y: 0.015 })
    b.cyl(0.015, 0.015, 0.9, { mat: 'chrome', color: '#b8bcc0', y: 0.45 })
    b.box(0.38, 0.62, 0.24, { mat: 'cloth', color: o.color ?? '#d8ccb0', y: 1.22, r: 0.1, seg: 3 })
    b.sphere(0.08, { mat: 'paint', color: '#d8ccb0', y: 1.6, sy: 0.7 })
    if (o.vest) b.box(0.42, 0.48, 0.28, { mat: 'cloth', color: o.vest, y: 1.26, r: 0.09, seg: 3 })
  })
}

// ---------------------------------------------------------------- crops
export function cropRow(b, o = {}) {
  const kind = o.kind ?? 'cabbage'
  const len = o.len ?? 4
  const rnd = seeded(o.seed ?? 51)
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    const n = Math.floor(len / (kind === 'corn' ? 0.35 : 0.42))
    for (let i = 0; i < n; i++) {
      const x = -len / 2 + 0.2 + (i * (len - 0.4)) / Math.max(1, n - 1)
      const s = 0.8 + rnd() * 0.4
      if (kind === 'cabbage') {
        b.ico(0.14 * s, { mat: 'leaf', color: pick(rnd, ['#6a9a48', '#7aa858', '#5a8a3e']), x, y: 0.1 * s, sy: 0.75, detail: 1, noise: 0.25 })
        for (let k = 0; k < 4; k++) b.sphere(0.13 * s, { mat: 'leaf', color: '#4a7a34', x: x + Math.cos(k * 1.6) * 0.1, y: 0.04, z: Math.sin(k * 1.6) * 0.1, sy: 0.25, ws: 6, hs: 4, ry: k })
      } else if (kind === 'corn') {
        const h = (1.3 + rnd() * 0.4) * s
        b.cyl(0.018, 0.025, h, { mat: 'leaf', color: '#7a9a48', x, y: h / 2, seg: 5 })
        for (let k = 0; k < 5; k++) {
          const a = k * 2.3 + rnd()
          b.box(0.06, 0.008, 0.55, { mat: 'leaf', color: pick(rnd, ['#6a9a3e', '#8aa850', '#9aa858']), x: x + Math.cos(a) * 0.15, y: h * (0.3 + k * 0.12), z: Math.sin(a) * 0.15, ry: -a + Math.PI / 2, rx: 0.7 })
        }
        if (rnd() < 0.7) b.capsule(0.035, 0.14, { mat: 'leaf', color: '#d8c050', x: x + 0.05, y: h * 0.6, rz: 0.4, seg: 6 })
        b.cone(0.03, 0.18, { mat: 'leaf', color: '#c8a858', x, y: h + 0.05, seg: 5 })
      } else if (kind === 'tomato') {
        b.box(0.025, 1.2, 0.025, { mat: 'wood', color: COL.woodGrey, x, y: 0.6 })
        for (let k = 0; k < 4; k++) b.ico(0.13 * s, { mat: 'leaf', color: pick(rnd, ['#4a7a34', '#5a8a3e']), x: x + (rnd() - 0.5) * 0.15, y: 0.3 + k * 0.22, z: (rnd() - 0.5) * 0.15, detail: 0, noise: 0.3 })
        for (let k = 0; k < 5; k++) b.sphere(0.035, { mat: 'gloss', color: rnd() < 0.7 ? '#d8301a' : '#8ab040', x: x + (rnd() - 0.5) * 0.25, y: 0.3 + rnd() * 0.6, z: (rnd() - 0.5) * 0.25, ws: 8, hs: 6 })
      } else if (kind === 'potato') {
        b.ico(0.17 * s, { mat: 'leaf', color: pick(rnd, ['#5a8a3e', '#6a9a48']), x, y: 0.12, sy: 0.7, detail: 0, noise: 0.4 })
      } else if (kind === 'carrot') {
        for (let k = 0; k < 4; k++) b.cone(0.035, 0.22, { mat: 'leaf', color: '#6aa040', x: x + (rnd() - 0.5) * 0.08, y: 0.11, z: (rnd() - 0.5) * 0.08, rx: (rnd() - 0.5) * 0.5, rz: (rnd() - 0.5) * 0.5, seg: 4 })
      } else if (kind === 'beans') {
        b.beam([x - 0.15, 0, 0], [x, 1.5, 0], 0.025, 0.025, { mat: 'wood', color: COL.woodGrey, round: true })
        b.beam([x + 0.15, 0, 0], [x, 1.5, 0], 0.025, 0.025, { mat: 'wood', color: COL.woodGrey, round: true })
        for (let k = 0; k < 6; k++) b.ico(0.1, { mat: 'leaf', color: pick(rnd, ['#5a9a3e', '#6aa848']), x: x + (rnd() - 0.5) * 0.2, y: 0.2 + k * 0.22, z: (rnd() - 0.5) * 0.12, detail: 0, noise: 0.35 })
      }
    }
  })
}
export function scarecrow(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.06, 1.9, 0.06, { mat: 'wood', color: COL.woodGrey, y: 0.95 })
    b.box(1.2, 0.05, 0.05, { mat: 'wood', color: COL.woodGrey, y: 1.45 })
    b.box(0.42, 0.55, 0.22, { mat: 'cloth', color: '#7a4a3a', y: 1.3, r: 0.06 })
    for (const sx of [-1, 1]) {
      b.box(0.38, 0.14, 0.14, { mat: 'cloth', color: '#7a4a3a', x: sx * 0.38, y: 1.45, r: 0.05 })
      for (let k = 0; k < 3; k++) b.cone(0.03, 0.12, { mat: 'cloth', color: '#d8b860', x: sx * (0.6 + k * 0.01), y: 1.45 + (k - 1) * 0.04, rz: sx * Math.PI / 2, seg: 4 })
    }
    b.sphere(0.15, { mat: 'cloth', color: '#d8c8a0', y: 1.74 })
    b.cyl(0.3, 0.3, 0.02, { mat: 'cloth', color: '#c8a050', y: 1.84, seg: 14 })
    b.cyl(0.14, 0.16, 0.14, { mat: 'cloth', color: '#c8a050', y: 1.9, seg: 12 })
    for (const sx of [-1, 1]) b.sphere(0.018, { mat: 'plain', color: '#1a1a1a', x: sx * 0.05, y: 1.76, z: 0.135 })
  })
}
export function fruitTree(b, o = {}) {
  const rnd = seeded(o.seed ?? 61)
  const s = o.s ?? 1
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0, s }, () => {
    b.cyl(0.08, 0.12, 1.3, { mat: 'bark', color: '#ffffff', y: 0.65, rz: (rnd() - 0.5) * 0.2 })
    for (let i = 0; i < 4; i++) {
      const a = (i / 4) * TAU + rnd()
      b.beam([0, 1.1, 0], [Math.cos(a) * 0.6, 1.8 + rnd() * 0.3, Math.sin(a) * 0.6], 0.06, 0.06, { mat: 'bark', color: '#ffffff', round: true })
    }
    for (let i = 0; i < 9; i++) {
      const a = rnd() * TAU
      const d = rnd() * 0.75
      b.ico(0.45 + rnd() * 0.3, { mat: 'leaf', color: pick(rnd, ['#4f7a36', '#5a8a3e', '#6a9444']), x: Math.cos(a) * d, y: 1.9 + rnd() * 0.6, z: Math.sin(a) * d, detail: 1, noise: 0.35 })
    }
    if (o.fruit !== false) for (let i = 0; i < 14; i++) {
      const a = rnd() * TAU
      const d = 0.4 + rnd() * 0.6
      b.sphere(0.05, { mat: 'gloss', color: o.fruitColor ?? pick(rnd, ['#c83a2a', '#d8602a', '#b8c040']), x: Math.cos(a) * d, y: 1.6 + rnd() * 0.9, z: Math.sin(a) * d, ws: 7, hs: 5 })
    }
  })
}

// ---------------------------------------------------------------- misc
export function cinderBlocks(b, o = {}) {
  const n = o.n ?? 4
  const rnd = seeded(o.seed ?? 71)
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (let i = 0; i < n; i++) {
      const row = Math.floor(i / 3)
      b.box(0.39, 0.19, 0.19, { mat: 'concrete', color: '#c8c4bc', x: (i % 3) * 0.4 - 0.4 + (row % 2) * 0.2, y: 0.095 + row * 0.19, ry: (rnd() - 0.5) * 0.08 })
    }
  })
}
export function bricks(b, o = {}) {
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    pallet(b, { w: 1.0, d: 0.8 })
    for (let r = 0; r < (o.rows ?? 5); r++) for (let i = 0; i < 4; i++) for (let j = 0; j < 3; j++) b.box(0.22, 0.065, 0.105, { mat: 'brick', color: '#ffffff', x: -0.36 + i * 0.235, y: 0.16 + r * 0.068, z: -0.24 + j * 0.115 + (r % 2) * 0.03 })
  })
}
export function rock(b, o = {}) {
  const rnd = seeded(o.seed ?? 81)
  const s = o.s ?? 0.5
  const col = o.color ?? pick(rnd, ['#9a948a', '#8a8478', '#a39c90', '#7e786e', '#958d80'])
  b.dodeca(s, { mat: o.mat ?? 'concrete', color: col, x: o.x || 0, y: (o.y || 0) + s * 0.28, z: o.z || 0, sy: 0.5 + rnd() * 0.25, sx: 0.85 + rnd() * 0.35, sz: 0.8 + rnd() * 0.3, ry: rnd() * 3, rx: (rnd() - 0.5) * 0.3, detail: 1 })
}
export function bones(b, o = {}) {
  // a cow skull on a post: camp decor
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.box(0.18, 0.12, 0.3, { mat: 'plain', color: '#e8e0cc', r: 0.05 })
    for (const sx of [-1, 1]) b.cone(0.03, 0.25, { mat: 'plain', color: '#e8e0cc', x: sx * 0.15, y: 0.03, z: -0.08, rz: sx * -1.2, seg: 6 })
  })
}
export function barbedCoil(b, o = {}) {
  const len = o.len ?? 1
  const r = o.r ?? 0.25
  const turns = Math.round(len / 0.12)
  const pts = []
  for (let i = 0; i <= turns * 10; i++) {
    const t = i / 10
    pts.push([-len / 2 + (t / turns) * len, r + Math.sin(t * TAU) * r, Math.cos(t * TAU) * r])
  }
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    b.tube(pts, 0.005, { mat: 'steel', color: '#8a9094', seg: 4, tseg: pts.length, tension: 0.5 })
  })
}
export function generatorSmall(b, o = {}) {
  const c = o.color ?? '#d8a020'
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0 }, () => {
    for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.cyl(0.015, 0.015, 0.55, { mat: 'steel', color: '#2a2a2a', x: sx * 0.32, z: sz * 0.22, y: 0.28 })
    for (const sz of [-1, 1]) b.cyl(0.015, 0.015, 0.66, { mat: 'steel', color: '#2a2a2a', z: sz * 0.22, y: 0.55, rz: Math.PI / 2 })
    b.box(0.5, 0.3, 0.36, { mat: 'paint', color: c, y: 0.25, r: 0.03 })
    b.box(0.3, 0.12, 0.3, { mat: 'paint', color: shadeHex(c, -0.1), y: 0.46, x: -0.1, r: 0.03 })
    b.cyl(0.05, 0.05, 0.03, { mat: 'paint', color: '#1a1a1a', y: 0.53, x: -0.1 })
    b.box(0.1, 0.12, 0.02, { mat: 'paint', color: '#2a2a2a', x: 0.22, y: 0.3, z: 0.19 })
    b.cyl(0.03, 0.03, 0.14, { mat: 'steel', color: '#4a4a4a', x: 0.3, y: 0.22, z: -0.12, rz: Math.PI / 2 })
  })
  return [(o.x || 0) + 0.38, (o.y || 0) + 0.22, (o.z || 0) - 0.12]
}
