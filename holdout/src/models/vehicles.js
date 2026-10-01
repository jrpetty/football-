// Vehicles and big props: cars (intact and wrecked), the camp's van, a bus,
// a pickup, shipping containers and a forklift. Cars face +z.
import * as THREE from 'three'
import { Builder, seeded } from './kit.js'
import { tire, shadeHex, blob } from './parts.js'

const TAU = Math.PI * 2
const pick = (r, a) => a[Math.floor(r() * a.length)]
export const CAR_COLORS = ['#6e3430', '#3a4e62', '#c8c4bc', '#3a3e42', '#5a6650', '#9a958e', '#a88a48', '#4e3a4a', '#2a3440', '#7a5a3a']

function wheel(b, x, y, z, rnd, missing, o = {}) {
  if (missing) {
    // up on blocks
    b.box(0.22, 0.26, 0.3, { mat: 'concrete', color: '#a8a49c', x, y: 0.13, z, ry: rnd() * 0.4 })
    b.cyl(0.11, 0.11, 0.16, { mat: 'rust', color: '#8a7466', x, y: 0.3, z, rz: Math.PI / 2, seg: 10 })
    return
  }
  wheelHD(b, { x, y: o.r ?? 0.33, z, r: o.r ?? 0.33, w: o.w ?? 0.22, side: Math.sign(x) || 1, rim: o.rim ?? '#9a9ea2', tread: o.tread ?? 0, lite: true })
}

// Car bodies are built from side profiles: a lower body extruded full width,
// a slightly narrower glasshouse in tinted glass, then a painted roof skin,
// pillars, lamps, bumpers, trim and wear on top.
const CAR_SPEC = {
  // L, W, wheel r, axles [rear, front], lower profile, glasshouse profile, roof [z0, z1, y]
  sedan: {
    W: 1.8, r: 0.32, ax: [-1.38, 1.42],
    lower: [[-2.3, 0.62], [-2.33, 0.34], [-2.0, 0.3], [2.04, 0.3], [2.32, 0.36], [2.34, 0.66], [2.26, 0.79], [1.6, 0.86], [0.95, 0.9], [-1.6, 0.94], [-2.22, 0.93]],
    glass: [[0.98, 0.88], [0.26, 1.36], [-0.78, 1.38], [-1.58, 0.92]],
    roof: [-0.78, 0.24, 1.385], belt: 0.9, front: 2.34, back: -2.33,
  },
  hatch: {
    W: 1.74, r: 0.31, ax: [-1.22, 1.28],
    lower: [[-1.92, 0.66], [-1.95, 0.34], [-1.7, 0.3], [1.86, 0.3], [2.04, 0.36], [2.06, 0.66], [1.98, 0.8], [1.45, 0.87], [0.85, 0.91], [-1.9, 0.95]],
    glass: [[0.88, 0.89], [0.18, 1.38], [-1.3, 1.4], [-1.86, 1.0], [-1.9, 0.94]],
    roof: [-1.32, 0.16, 1.405], belt: 0.91, front: 2.06, back: -1.95,
  },
  wagon: {
    W: 1.82, r: 0.32, ax: [-1.45, 1.42],
    lower: [[-2.38, 0.66], [-2.4, 0.34], [-2.1, 0.3], [2.04, 0.3], [2.32, 0.36], [2.34, 0.66], [2.26, 0.79], [1.6, 0.86], [0.95, 0.9], [-2.36, 0.95]],
    glass: [[0.98, 0.88], [0.26, 1.38], [-2.2, 1.4], [-2.34, 1.28], [-2.36, 0.94]],
    roof: [-2.22, 0.24, 1.405], belt: 0.9, front: 2.34, back: -2.4, rails: true,
  },
  pickup: {
    W: 1.92, r: 0.38, ax: [-1.62, 1.5],
    lower: [[-2.62, 0.74], [-2.64, 0.44], [-2.3, 0.4], [2.3, 0.4], [2.6, 0.46], [2.64, 0.82], [2.56, 1.0], [1.6, 1.07], [1.0, 1.1], [-0.36, 1.12], [-0.36, 0.78], [-2.62, 0.78]],
    glass: [[1.02, 1.08], [0.4, 1.64], [-0.26, 1.66], [-0.34, 1.1]],
    roof: [-0.28, 0.38, 1.665], belt: 1.1, front: 2.64, back: -2.64, bed: true, seams: [1.0, -0.3],
  },
  suv: {
    W: 1.9, r: 0.38, ax: [-1.4, 1.4],
    lower: [[-2.28, 0.74], [-2.32, 0.42], [-2.0, 0.38], [2.02, 0.38], [2.3, 0.44], [2.34, 0.78], [2.26, 0.95], [1.6, 1.0], [1.0, 1.04], [-2.26, 1.08]],
    glass: [[1.02, 1.02], [0.38, 1.66], [-2.12, 1.7], [-2.26, 1.56], [-2.28, 1.06]],
    roof: [-2.14, 0.36, 1.71], belt: 1.04, front: 2.34, back: -2.32, rails: true,
  },
}
// Sedan / hatchback / wagon / SUV. o: { color, wreck (0..1), seed, kind }
export function carModel(o = {}) {
  const rnd = seeded(o.seed ?? 3)
  const b = new Builder()
  const c = o.color ?? pick(rnd, CAR_COLORS)
  const wreck = o.wreck ?? 0
  const kind = o.kind ?? pick(rnd, ['sedan', 'sedan', 'hatch', 'wagon', 'suv'])
  const S = CAR_SPEC[kind] || CAR_SPEC.sedan
  const W = S.W
  const body = { mat: 'paint', color: c }
  const trim = { mat: 'plastic', color: '#1c1d1e' }
  const chrome = { mat: 'chrome', color: '#a8acb0' }
  // wheel arches cut into the lower profile
  const Ra = S.r + 0.07
  const ay = S.r + 0.02
  const cut = (pts) => {
    const out = []
    for (let i = 0; i < pts.length; i++) {
      const p = pts[i]
      out.push(p)
      const q = pts[i + 1]
      if (!q || p[1] > 0.45 || q[1] > 0.45 || q[0] <= p[0]) continue
      // bottom edge running forward: insert arches for axles inside it
      for (const az of S.ax) {
        if (az - Ra > p[0] && az + Ra < q[0]) {
          const y0 = p[1]
          const a0 = Math.asin(Math.min(1, Math.max(-1, (y0 - ay) / Ra)))
          for (let k = 0; k <= 12; k++) {
            const a = Math.PI - a0 - (k / 12) * (Math.PI - 2 * a0)
            out.push([az + Math.cos(a) * Ra, ay + Math.sin(a) * Ra])
          }
        }
      }
    }
    return out
  }
  b.profile(cut(S.lower), W, { ...body, bevel: 0.05, bevelSeg: 2 })
  const broken = wreck > 0.3
  b.profile(S.glass, W - 0.16, { mat: broken && rnd() < 0.5 ? 'plain' : 'carGlass', color: broken ? '#1e1e1c' : '#141a1e', y: -0.005, bevel: 0.03 })
  // roof skin and pillars
  const [rz0, rz1, ry] = S.roof
  b.box(W - 0.2, 0.045, rz1 - rz0, { ...body, y: ry + 0.005, z: (rz0 + rz1) / 2, r: 0.02 })
  const g = S.glass
  for (const sx of [-1, 1]) {
    const x = sx * (W / 2 - 0.1)
    b.beam([x, g[0][1], g[0][0]], [x, g[1][1], g[1][0]], 0.07, 0.06, body)
    if (!S.bed) b.beam([x, S.belt, (rz0 + rz1) / 2 - 0.05], [x, ry, (rz0 + rz1) / 2 - 0.05], 0.06, 0.1, { mat: 'paint', color: '#1e1e20' })
    const last = g.length - 1
    b.beam([x, g[last][1], g[last][0]], [x, g[last - 1][1], g[last - 1][0]], 0.07, kind === 'sedan' ? 0.12 : 0.08, body)
    if (kind === 'wagon' || kind === 'suv') b.beam([x, S.belt, rz0 + 0.55], [x, ry, rz0 + 0.55], 0.06, 0.1, body)
    // belt trim, door seams, handles, mirror
    b.box(0.012, 0.03, (g[0][0] - g[last][0]) * 0.96, { mat: 'chrome', color: '#8a8e92', x: sx * (W / 2 - 0.045), y: S.belt - 0.005, z: (g[0][0] + g[last][0]) / 2 })
    for (const z of S.seams || [g[0][0] - 0.02, (rz0 + rz1) / 2 - 0.05, kind === 'hatch' ? null : (rz0 + rz1) / 2 - 1.0]) {
      if (z == null) continue
      b.box(0.006, S.belt - 0.36, 0.01, { mat: 'plain', color: '#151515', x: sx * (W / 2 + 0.002), y: (S.belt + 0.36) / 2, z, ao: 0 })
    }
    b.box(0.03, 0.03, 0.14, { ...chrome, x: sx * (W / 2 + 0.012), y: S.belt - 0.1, z: (rz0 + rz1) / 2 - 0.25 })
    if (!S.bed) b.box(0.03, 0.03, 0.14, { ...chrome, x: sx * (W / 2 + 0.012), y: S.belt - 0.1, z: (rz0 + rz1) / 2 + 0.55 })
    b.box(0.1, 0.1, 0.16, { ...body, x: sx * (W / 2 + 0.06), y: S.belt + 0.06, z: g[0][0] - 0.12, r: 0.02 })
    b.box(0.006, 0.08, 0.13, { mat: 'chrome', color: '#c8ccd0', x: sx * (W / 2 + 0.06), y: S.belt + 0.06, z: g[0][0] - 0.205 })
    // side skirt and arch lips
    b.box(0.03, 0.08, Math.abs(S.ax[1] - S.ax[0]) - Ra * 2 - 0.05, { ...trim, x: sx * (W / 2 - 0.02), y: 0.33, z: (S.ax[0] + S.ax[1]) / 2 })
  }
  if (S.bed) {
    // the load bed: walls, tailgate, ribbed liner, wheel tubs, rails
    const z0 = S.back + 0.02
    const z1 = -0.4
    for (const sx of [-1, 1]) {
      b.box(0.06, 0.36, z1 - z0, { ...body, x: sx * (W / 2 - 0.03), y: 0.96, z: (z0 + z1) / 2 })
      b.box(0.09, 0.03, z1 - z0, { mat: 'plastic', color: '#1e1e1e', x: sx * (W / 2 - 0.035), y: 1.145, z: (z0 + z1) / 2 })
      b.box(0.28, 0.2, 0.9, { mat: 'plastic', color: '#222222', x: sx * (W / 2 - 0.2), y: 0.88, z: S.ax[0] })
      b.box(0.05, 0.2, 0.06, { mat: 'glowRed', color: '#5a1410', x: sx * (W / 2 - 0.03), y: 0.96, z: z0 - 0.015 })
    }
    b.box(W - 0.12, 0.34, 0.06, { ...body, y: 0.96, z: z0 + 0.03 })
    b.box(0.3, 0.04, 0.02, { mat: 'chrome', color: '#a8acb0', y: 1.04, z: z0 - 0.002 })
    b.box(W - 0.12, 0.02, z1 - z0, { mat: 'plastic', color: '#262626', y: 0.79, z: (z0 + z1) / 2 })
    for (let i = 0; i < 7; i++) b.box(0.05, 0.02, z1 - z0 - 0.1, { mat: 'plastic', color: '#2e2e2e', x: -W / 2 + 0.3 + i * ((W - 0.6) / 6), y: 0.805, z: (z0 + z1) / 2 })
    b.box(W - 0.06, 0.08, 0.08, { ...body, y: 1.11, z: z1 + 0.02 })
    if (o.cargo !== false) {
      for (let i = 0; i < 3; i++) b.box(0.5, 0.34, 0.45, { mat: 'wood', color: '#c8b898', x: -0.45 + i * 0.42, y: 0.98, z: -1.6 + (i % 2) * 0.55, ry: (rnd() - 0.5) * 0.4 })
      b.cloth(1.1, 0.9, { mat: 'canvas', color: '#5a6646', y: 1.18, z: -2.0, corners: [0.0, 0.1, -0.2, -0.3], sag: 0.05, seed: 3 })
    }
  }
  if (S.rails) for (const sx of [-0.62, 0.62]) b.box(0.04, 0.05, rz1 - rz0 - 0.2, { mat: 'metal', color: '#3a3c3e', x: sx, y: ry + 0.05, z: (rz0 + rz1) / 2 })
  // nose: lamps, grille, bumper, plate
  const zf = S.front + 0.04
  const lampY = S.lower.find((p) => p[0] > 2 && p[1] > 0.6)?.[1] ?? 0.66
  for (const sx of [-1, 1]) {
    b.box(0.34, 0.11, 0.06, { mat: broken && rnd() < 0.5 ? 'plain' : 'glass', color: '#e8e6dc', x: sx * (W / 2 - 0.26), y: lampY - 0.04, z: zf - 0.04, ry: sx * -0.12 })
    b.box(0.36, 0.13, 0.04, { ...chrome, x: sx * (W / 2 - 0.26), y: lampY - 0.04, z: zf - 0.07, ry: sx * -0.12 })
    b.box(0.14, 0.05, 0.03, { mat: 'glowAmber', color: '#8a6420', x: sx * (W / 2 - 0.16), y: lampY - 0.16, z: zf - 0.03 })
  }
  b.box(W * 0.42, 0.13, 0.04, { mat: 'plain', color: '#101112', y: lampY - 0.04, z: zf - 0.03 })
  for (let i = 0; i < 3; i++) b.box(W * 0.4, 0.012, 0.03, { mat: 'chrome', color: '#8a8e92', y: lampY - 0.09 + i * 0.045, z: zf - 0.01 })
  b.box(W + 0.04, 0.16, 0.14, { ...(rnd() < 0.5 ? chrome : trim), y: 0.38, z: zf, r: 0.04 })
  b.box(0.42, 0.1, 0.01, { mat: 'paint', color: '#e8e4d0', y: 0.4, z: zf + 0.075 })
  // tail: lamps, bumper, plate, exhaust
  const zb = S.back - 0.04
  if (!S.bed)
    for (const sx of [-1, 1]) {
      b.box(0.32, 0.12, 0.04, { mat: 'glowRed', color: '#5a1410', x: sx * (W / 2 - 0.22), y: 0.74, z: zb + 0.02 })
      b.box(0.1, 0.12, 0.042, { mat: 'glowAmber', color: '#7a5212', x: sx * (W / 2 - 0.44), y: 0.74, z: zb + 0.02 })
    }
  b.box(W + 0.04, 0.16, 0.14, { ...trim, y: 0.38, z: zb, r: 0.04 })
  b.box(0.42, 0.1, 0.01, { mat: 'paint', color: '#e8e4d0', y: S.bed ? 0.88 : 0.6, z: zb - 0.005 })
  b.cyl(0.035, 0.035, 0.2, { mat: 'metal', color: '#3a3836', x: -W / 2 + 0.4, y: 0.24, z: zb + 0.02, rx: Math.PI / 2, seg: 8 })
  // underbody shadow and wheels
  b.box(W - 0.2, 0.12, S.front - S.back - 0.6, { mat: 'metal', color: '#1a1a1a', y: 0.26, z: (S.front + S.back) / 2 })
  for (const sx of [-1, 1]) for (const az of S.ax) {
    const miss = wreck > 0.5 && rnd() < wreck * 0.7
    wheel(b, sx * (W / 2 - 0.13), S.r, az, rnd, miss, { r: S.r, w: kind === 'suv' ? 0.26 : 0.21, rim: kind === 'suv' ? '#5a5e60' : '#a8acb0' })
    if (!miss && wreck > 0.2 && rnd() < 0.4) b.box(0.2, 0.06, 0.32, { mat: 'rubber', color: '#1a1a1a', x: sx * (W / 2 - 0.13), y: 0.03, z: az })
  }
  // wear: dust, rust, broken glass, a door hanging open
  if (wreck > 0) {
    const n = Math.floor(2 + wreck * 6)
    for (let i = 0; i < n; i++) {
      const sx = rnd() < 0.5 ? -1 : 1
      // rust eats along the sills and the arch edges
      const z = pick(rnd, [...S.ax.map((a) => a + (rnd() < 0.5 ? -1 : 1) * (Ra + 0.1)), (rnd() - 0.5) * (S.front - S.back - 1.4)])
      blob(b, 0.12 + rnd() * 0.3, 0.06 + rnd() * 0.12, { rnd, x: sx * (W / 2 + 0.002), y: 0.42 + rnd() * 0.14, z, ry: (sx * Math.PI) / 2, color: shadeHex('#7e6656', (rnd() - 0.5) * 0.2) })
    }
    if (rnd() < wreck) blob(b, 0.5 + rnd() * 0.4, 0.3 + rnd() * 0.3, { rnd, x: (rnd() - 0.5) * 0.5, y: (S.lower.find((p) => p[0] > 1.5 && p[0] < 1.7)?.[1] ?? 0.86) + 0.012, z: S.front - 0.7, rx: -Math.PI / 2 + 0.07 })
    if (broken) {
      // glass crumbs on the ground
      for (let i = 0; i < 10; i++) b.box(0.05 + rnd() * 0.06, 0.006, 0.04 + rnd() * 0.05, { mat: 'glass', color: '#a8c0c8', x: (rnd() < 0.5 ? -1 : 1) * (W / 2 + 0.1 + rnd() * 0.6), y: 0.005, z: (rnd() - 0.5) * 2.5, ry: rnd() * 3, shadow: false })
    }
    if (rnd() < wreck * 0.7) {
      const sx = rnd() < 0.5 ? -1 : 1
      const hz = g[0][0] - 0.05
      b.at({ x: sx * (W / 2), y: 0.62, z: hz, ry: sx * 0.95 }, () => {
        b.box(0.07, 0.56, 1.0, { ...body, x: sx * 0.035, z: -0.5, r: 0.02 })
        b.box(0.03, 0.42, 0.8, { mat: 'plain', color: '#3a3632', x: -sx * 0.01, y: 0.02, z: -0.5 })
      })
    }
  }
  return b.build()
}
// ---------------------------------------------------------------- shared parts
// A detailed road wheel. Local frame: the axle runs along x; side = +1 for a
// wheel on the right (+x) of the vehicle, -1 for the left, so the rim always
// faces outwards. o: { x, y, z, r, w, side, rim, tread, dish }
export function wheelHD(b, o = {}) {
  const R = o.r ?? 0.4
  const w = o.w ?? 0.26
  const side = o.side ?? 1
  const rim = o.rim ?? '#8a8e90'
  b.at({ x: o.x || 0, y: o.y ?? R, z: o.z || 0, ry: o.ry ?? (side * Math.PI) / 2, rx: o.rx || 0 }, () => {
    // in here the axle is local z and +z faces out
    const tr = R * 0.3
    const lite = !!o.lite
    b.torus(R - tr, tr, { mat: 'rubber', color: '#1e1e1e', rs: lite ? 8 : 12, ts2: lite ? 26 : 36, sz: (w * 0.5) / tr })
    // chunky tread blocks, staggered
    const n = o.tread ?? 22
    for (let i = 0; i < n; i++) {
      const a = (i / n) * Math.PI * 2
      for (const half of [-1, 1]) {
        const off = (i % 2 ? 0.5 : 0) * ((Math.PI * 2) / n)
        const aa = a + (half > 0 ? off : 0)
        b.box(0.016, ((Math.PI * 2 * R) / n) * 0.5, w * 0.36, { mat: 'rubber', color: '#262626', x: Math.cos(aa) * (R - 0.002), y: Math.sin(aa) * (R - 0.002), z: half * w * 0.2, rz: aa, ao: 0 })
      }
    }
    // steel wheel: face, conical dish, hub, lug nuts and a centre cap
    const zf = w * 0.24
    b.cyl(R * 0.62, R * 0.62, w * 0.8, { mat: 'paint', color: shadeHex(rim, -0.25), rx: Math.PI / 2, seg: 24 })
    b.cyl(R * 0.6, R * 0.6, 0.03, { mat: 'paint', color: rim, rx: Math.PI / 2, z: zf, seg: 24 })
    b.torus(R * 0.6, 0.014, { mat: 'paint', color: shadeHex(rim, 0.08), z: zf + 0.012, rs: 6, ts2: 28 })
    b.cyl(R * 0.3, R * 0.52, 0.05, { mat: 'paint', color: shadeHex(rim, -0.05), rx: Math.PI / 2, z: zf + 0.035, seg: 20 })
    for (let i = 0; i < 6; i++) {
      const a = (i / 6) * Math.PI * 2 + 0.26
      b.cyl(0.032, 0.032, 0.012, { mat: 'plain', color: '#141414', x: Math.cos(a) * R * 0.44, y: Math.sin(a) * R * 0.44, z: zf + 0.042, rx: Math.PI / 2 + Math.sin(a) * 0.5, rz: 0, seg: 10, ao: 0 })
    }
    b.cyl(R * 0.2, R * 0.24, 0.04, { mat: 'steel', color: '#6a6e72', rx: Math.PI / 2, z: zf + 0.075, seg: 16 })
    for (let i = 0; i < 6; i++) {
      const a = (i / 6) * Math.PI * 2
      b.cyl(0.016, 0.016, 0.03, { mat: 'steel', color: '#9a9ea2', x: Math.cos(a) * R * 0.15, y: Math.sin(a) * R * 0.15, z: zf + 0.1, rx: Math.PI / 2, seg: 6 })
    }
    b.cyl(0.035, 0.05, 0.035, { mat: 'chrome', color: '#b8bcc0', rx: Math.PI / 2, z: zf + 0.1, seg: 12 })
  })
}

// Stencilled lettering with drips, for vehicles and walls (transparent).
const decals = new Map()
export function stencilMat(text, color = '#1e1e1a') {
  const key = text + color
  if (decals.has(key)) return decals.get(key)
  const c = document.createElement('canvas')
  c.width = 512
  c.height = 128
  const g = c.getContext('2d', { willReadFrequently: true })
  g.fillStyle = color
  g.font = '800 92px "Saira Condensed", "Arial Narrow", Impact, sans-serif'
  g.textAlign = 'center'
  g.textBaseline = 'middle'
  g.fillText(text, 256, 60, 490)
  // stencil bridges and spray drips
  const img = g.getImageData(0, 0, 512, 128)
  for (let x = 0; x < 512; x += 1) {
    if (Math.random() < 0.03) {
      for (let y = 20; y < 110; y++) {
        const i = (y * 512 + x) * 4 + 3
        if (img.data[i] > 0 && Math.random() < 0.02) {
          const len = 6 + Math.random() * 22
          for (let k = 0; k < len && y + k < 128; k++) img.data[((y + k) * 512 + x) * 4 + 3] = Math.max(img.data[((y + k) * 512 + x) * 4 + 3], 200 - k * 6)
          break
        }
      }
    }
  }
  for (let i = 0; i < img.data.length; i += 4) {
    if (img.data[i + 3] > 0) {
      img.data[i] = parseInt(color.slice(1, 3), 16)
      img.data[i + 1] = parseInt(color.slice(3, 5), 16)
      img.data[i + 2] = parseInt(color.slice(5, 7), 16)
      img.data[i + 3] *= 0.75 + Math.random() * 0.25
    }
  }
  g.putImageData(img, 0, 0)
  g.globalCompositeOperation = 'destination-out'
  for (let x = 30; x < 512; x += 46 + Math.random() * 20) g.fillRect(x, 0, 3, 128)
  for (let i = 0; i < 400; i++) {
    g.fillStyle = `rgba(0,0,0,${Math.random() * 0.6})`
    g.fillRect(Math.random() * 512, Math.random() * 128, 1 + Math.random() * 6, 1 + Math.random() * 3)
  }
  const t = new THREE.CanvasTexture(c)
  t.colorSpace = THREE.SRGBColorSpace
  t.anisotropy = 8
  const m = new THREE.MeshStandardMaterial({ map: t, transparent: true, alphaTest: 0.25, roughness: 0.8, polygonOffset: true, polygonOffsetFactor: -2 })
  decals.set(key, m)
  return m
}

// Bolted steel plate on a vehicle or wall: plate, edge strip, bolt heads and
// a little rust bleeding from the bolts. Plate lies in the local xy plane, +z out.
function plate(b, w, h, o = {}) {
  const c = o.color ?? '#5a5c4c'
  const rnd = o.rnd ?? Math.random
  b.at({ x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0, rz: o.rz || 0, rx: o.rx || 0 }, () => {
    b.box(w, h, 0.012, { mat: 'paint', color: c, ao: 0 })
    const nb = Math.max(2, Math.round(w / 0.32))
    for (let i = 0; i < nb; i++) {
      const x = -w / 2 + 0.06 + (i / (nb - 1)) * (w - 0.12)
      for (const y of [h / 2 - 0.05, -h / 2 + 0.05]) {
        b.cyl(0.018, 0.02, 0.014, { mat: 'steel', color: '#7a7e78', x, y, z: 0.009, rx: Math.PI / 2, seg: 6, ao: 0 })
        if (rnd() < 0.3) b.box(0.01, 0.04 + rnd() * 0.1, 0.002, { mat: 'rust', color: '#8a7466', x, y: y - 0.045, z: 0.0065, ao: 0, shadow: false })
      }
    }
    if (o.weld !== false) b.box(w + 0.01, 0.012, 0.016, { mat: 'metal', color: '#4a4844', y: h / 2, ao: 0 })
  })
}

// The camp's supply van: a lifted, armoured cargo van that has done a
// hundred supply runs. Faces +z; also parked on runs as the loot stash.
export function vanModel(o = {}) {
  const b = new Builder()
  const rnd = seeded(o.seed ?? 11)
  const top = o.color ?? '#a69a78'
  const low = o.low ?? '#4e5142'
  const W = 2.0
  const BV = 0.035
  const zr = -1.55
  const zf = 1.78
  const R = 0.41
  const Ra = 0.5
  const ay = 0.44
  const arch = (zc) => {
    const pts = []
    for (let k = 0; k <= 12; k++) {
      const a = Math.PI - 0.16 - (k / 12) * (Math.PI - 0.32)
      pts.push([zc + Math.cos(a) * Ra, ay + Math.sin(a) * Ra])
    }
    return pts
  }
  const body = { mat: 'paint', color: top }
  b.profile(
    [[-2.86, 0.52], ...arch(zr), ...arch(zf), [2.72, 0.52], [2.86, 0.6], [2.9, 1.0], [2.84, 1.12], [2.6, 1.18], [1.72, 1.31], [1.62, 1.37], [0.98, 2.16], [0.86, 2.26], [0.5, 2.3], [-2.7, 2.3], [-2.82, 2.25], [-2.88, 2.14], [-2.88, 0.62]],
    W,
    { ...body, bevel: BV, bevelSeg: 2 },
  )
  const sx2 = W / 2 + 0.004
  // undercarriage, axles, tank and exhaust
  b.box(W - 0.3, 0.2, 5.2, { mat: 'metal', color: '#2a2a28', y: 0.42, z: 0 })
  for (const z of [zr, zf]) b.cyl(0.055, 0.055, W - 0.25, { mat: 'metal', color: '#2a2a28', y: R, z, rz: Math.PI / 2, seg: 10 })
  b.lathe([[0.001, -0.14], [0.16, -0.1], [0.18, 0], [0.16, 0.1], [0.001, 0.14]], { mat: 'metal', color: '#2e2e2c', y: R, z: zr, rz: Math.PI / 2, seg: 14 })
  b.box(0.7, 0.32, 0.9, { mat: 'metal', color: '#33332f', x: -0.35, y: 0.42, z: 0.15 })
  b.cyl(0.045, 0.045, 2.6, { mat: 'metal', color: '#3a3836', x: -0.62, y: 0.33, z: -1.6, rx: Math.PI / 2, seg: 10 })
  b.cyl(0.05, 0.045, 0.14, { mat: 'rust', color: '#ffffff', x: -0.62, y: 0.33, z: -2.94, rx: Math.PI / 2, seg: 10 })
  // wheels and fender flares; a dead van sits on blocks with two wheels off
  const gone = o.broken ? new Set([`-1:${zf}`, `1:${zr}`]) : null
  for (const side of [-1, 1]) {
    for (const z of [zr, zf]) {
      if (gone?.has(`${side}:${z}`)) {
        for (let k = 0; k < 2; k++) b.box(0.39, 0.19, 0.19, { mat: 'concrete', color: '#c8c4bc', x: side * 0.7, y: 0.095 + k * 0.19, z: z + (k ? 0.05 : -0.04), ry: k * 0.35 })
        b.cyl(0.05, 0.05, 0.3, { mat: 'metal', color: '#3a3836', x: side * 0.78, y: R, z, rz: Math.PI / 2, seg: 8 })
      } else wheelHD(b, { x: side * 0.86, z, r: R, w: 0.28, side, rim: '#4a4c44' })
      b.torus(Ra - 0.02, 0.055, { mat: 'rubber', color: '#262624', x: side * (W / 2 + 0.01), y: ay, z, ry: Math.PI / 2, rz: 0.16, arc: Math.PI - 0.32, rs: 6, ts2: 18, sx: 0.8 })
      b.box(0.02, 0.36, 0.3, { mat: 'rubber', color: '#1a1a1a', x: side * 0.86, y: 0.36, z: z - 0.56 })
    }
  }
  // windscreen: glass, rubber frame, wipers and a welded mesh guard
  const ws = { z: 1.3, y: 1.765, a: -0.675 }
  b.at({ z: ws.z, y: ws.y, rx: ws.a }, () => {
    b.box(W - 0.24, 0.88, 0.03, { mat: 'glass', color: '#3a4a52', z: 0.006 })
    b.box(W - 0.2, 0.05, 0.03, { mat: 'rubber', color: '#1a1a1a', y: 0.46 })
    b.box(W - 0.2, 0.05, 0.03, { mat: 'rubber', color: '#1a1a1a', y: -0.46 })
    for (const s of [-1, 1]) {
      b.box(0.05, 0.94, 0.03, { mat: 'rubber', color: '#1a1a1a', x: s * (W / 2 - 0.1) })
      b.beam([s * 0.55 - 0.3, -0.4, 0.04], [s * 0.55 + 0.25, -0.2, 0.04], 0.015, 0.012, { mat: 'rubber', color: '#141414' })
    }
    // guard frame 7 cm proud of the glass
    const gz = 0.08
    for (const s of [-1, 1]) b.box(0.035, 0.98, 0.035, { mat: 'metal', color: '#3c3e38', x: s * (W / 2 - 0.12), z: gz })
    for (const y of [-0.47, 0, 0.47]) b.box(W - 0.2, 0.03, 0.03, { mat: 'metal', color: '#3c3e38', y, z: gz })
    for (let i = 1; i < 9; i++) b.box(0.014, 0.94, 0.014, { mat: 'metal', color: '#4a4a44', x: -W / 2 + 0.12 + (i / 9) * (W - 0.24), z: gz - 0.005 })
    for (const s of [-1, 1]) b.box(0.04, 0.06, 0.1, { mat: 'metal', color: '#3c3e38', x: s * (W / 2 - 0.12), y: -0.5, z: 0.04 })
  })
  // cab doors: windows, seams, handles, mirrors, steps
  for (const side of [-1, 1]) {
    const x = side * sx2
    b.box(0.03, 0.6, 0.78, { mat: 'glass', color: '#3a4a52', x: side * (W / 2 + 0.006), y: 1.76, z: 0.92 })
    b.box(0.03, 0.04, 0.84, { mat: 'rubber', color: '#1a1a1a', x: side * (W / 2 + 0.008), y: 2.07, z: 0.92 })
    b.box(0.03, 0.04, 0.84, { mat: 'rubber', color: '#1a1a1a', x: side * (W / 2 + 0.008), y: 1.45, z: 0.92 })
    b.box(0.03, 0.66, 0.04, { mat: 'rubber', color: '#1a1a1a', x: side * (W / 2 + 0.008), y: 1.76, z: 0.51 })
    // mesh over the door glass
    for (let i = 0; i < 6; i++) b.box(0.012, 0.6, 0.012, { mat: 'metal', color: '#3c3e38', x: side * (W / 2 + 0.04), y: 1.76, z: 0.58 + i * 0.13 })
    for (const y of [1.5, 2.02]) b.box(0.02, 0.025, 0.8, { mat: 'metal', color: '#3c3e38', x: side * (W / 2 + 0.04), y, z: 0.92 })
    b.box(0.006, 1.6, 0.012, { mat: 'plain', color: '#22221e', x: side * (W / 2 + 0.002), y: 1.3, z: 0.46, ao: 0 })
    b.box(0.006, 0.9, 0.012, { mat: 'plain', color: '#22221e', x: side * (W / 2 + 0.002), y: 0.98, z: 1.62, ao: 0 })
    b.box(0.03, 0.04, 0.16, { mat: 'chrome', color: '#a8acb0', x: side * (W / 2 + 0.02), y: 1.36, z: 0.62 })
    // mirror on an arm
    b.beam([side * (W / 2), 1.72, 1.5], [side * (W / 2 + 0.24), 1.86, 1.46], 0.025, 0.025, { mat: 'metal', color: '#2a2a28', round: true })
    b.beam([side * (W / 2), 1.5, 1.52], [side * (W / 2 + 0.24), 1.62, 1.46], 0.025, 0.025, { mat: 'metal', color: '#2a2a28', round: true })
    b.box(0.09, 0.34, 0.2, { mat: 'paint', color: '#232320', x: side * (W / 2 + 0.28), y: 1.74, z: 1.45, r: 0.02 })
    b.box(0.07, 0.3, 0.005, { mat: 'chrome', color: '#c8ccd0', x: side * (W / 2 + 0.28), y: 1.74, z: 1.346 })
    b.box(0.26, 0.04, 0.5, { mat: 'metal', color: '#3a3a36', x: side * (W / 2 + 0.06), y: 0.5, z: 1.0 })
    // amber side marker
    b.box(0.02, 0.05, 0.1, { mat: 'glowAmber', color: '#8a5a1a', x, y: 1.1, z: 2.5 })
    b.box(0.02, 0.05, 0.1, { mat: 'glowRed', color: '#6a1a1a', x, y: 1.0, z: -2.7 })
  }
  // cargo sides: welded armour plates over the lower body (olive, hand-done)
  const plates = [
    [-2.84, -2.08, 0.6, 1.25],
    [-2.06, -1.04, 0.98, 1.25],
    [-1.02, 0.08, 0.6, 1.3],
    [0.1, 1.24, 0.6, 1.26],
    [1.3, 2.26, 0.98, 1.22],
    [2.28, 2.8, 0.62, 1.12],
  ]
  for (const side of [-1, 1]) {
    for (const [z0, z1, y0, y1] of plates) {
      const zc = (z0 + z1) / 2 + (rnd() - 0.5) * 0.02
      plate(b, z1 - z0, y1 - y0, { color: shadeHex(low, (rnd() - 0.5) * 0.12), x: side * (W / 2 + 0.008), y: (y0 + y1) / 2, z: zc, ry: (side * Math.PI) / 2, rnd, rz: (rnd() - 0.5) * 0.015 })
    }
    // rear windows on the cargo box, plated over with a firing slit
    b.at({ x: side * (W / 2 + 0.014), y: 1.8, z: -1.9, ry: (side * Math.PI) / 2 }, () => {
      b.box(0.95, 0.55, 0.012, { mat: 'paint', color: shadeHex(low, 0.05), ao: 0 })
      b.box(0.45, 0.06, 0.016, { mat: 'plain', color: '#0c0c0c', y: 0.08, ao: 0 })
      for (const x of [-0.42, 0.42]) for (const y of [-0.22, 0.22]) b.cyl(0.018, 0.02, 0.014, { mat: 'steel', color: '#7a7e78', x, y, z: 0.009, rx: Math.PI / 2, seg: 6, ao: 0 })
    })
    // stencil down the side
    b.plane(1.55, 0.4, { material: stencilMat(side > 0 ? 'SUPPLY RUN' : 'HOLDOUT'), x: side * (W / 2 + 0.006), y: 1.78, z: -0.52, ry: (side * Math.PI) / 2, shadow: false })
  }
  // sliding door seams and rail on the right
  b.box(0.006, 1.62, 0.012, { mat: 'plain', color: '#22221e', x: sx2, y: 1.36, z: -0.98, ao: 0 })
  b.box(0.006, 1.62, 0.012, { mat: 'plain', color: '#22221e', x: sx2, y: 1.36, z: 0.42, ao: 0 })
  b.box(0.02, 0.03, 2.1, { mat: 'metal', color: '#2a2a28', x: W / 2 + 0.01, y: 2.12, z: -1.6 })
  b.box(0.03, 0.05, 0.2, { mat: 'chrome', color: '#a8acb0', x: W / 2 + 0.02, y: 1.38, z: -0.84 })
  // fuel filler
  b.cyl(0.07, 0.07, 0.01, { mat: 'paint', color: shadeHex(top, -0.1), x: -sx2, y: 1.2, z: -2.2, rz: Math.PI / 2, seg: 14 })
  // nose: grille, lamps, bumper, bull bar, winch
  const zn = 2.94
  b.box(1.22, 0.3, 0.04, { mat: 'plain', color: '#151515', y: 0.9, z: zn - 0.02 })
  for (let i = 0; i < 5; i++) b.box(1.18, 0.022, 0.03, { mat: 'chrome', color: '#8a8e92', y: 0.78 + i * 0.058, z: zn })
  b.box(1.3, 0.38, 0.03, { mat: 'chrome', color: '#9a9ea2', y: 0.9, z: zn - 0.03 })
  for (const s of [-1, 1]) {
    b.cyl(0.115, 0.115, 0.05, { mat: 'chrome', color: '#b0b4b8', x: s * 0.76, y: 0.94, z: zn - 0.02, rx: Math.PI / 2, seg: 18 })
    b.cyl(0.095, 0.095, 0.03, { mat: 'glass', color: '#f0ead8', x: s * 0.76, y: 0.94, z: zn + 0.008, rx: Math.PI / 2, seg: 18 })
    b.box(0.16, 0.06, 0.03, { mat: 'glowAmber', color: '#9a6a22', x: s * 0.76, y: 0.78, z: zn - 0.01 })
  }
  b.box(W + 0.08, 0.2, 0.18, { mat: 'metal', color: '#2e2e2a', y: 0.6, z: zn + 0.08, r: 0.02 })
  b.box(0.44, 0.12, 0.01, { mat: 'paint', color: '#e8e2c8', y: 0.6, z: zn + 0.175 })
  b.box(0.4, 0.02, 0.012, { mat: 'paint', color: '#3a5a8a', y: 0.645, z: zn + 0.177 })
  // bull bar
  const bz = zn + 0.24
  const bar = { mat: 'metal', color: '#2a2b28', round: true }
  for (const s of [-1, 1]) {
    b.beam([s * 0.48, 0.5, bz - 0.12], [s * 0.48, 1.26, bz], 0.07, 0.07, bar)
    b.beam([s * 0.48, 1.26, bz], [s * 0.96, 1.16, bz - 0.3], 0.06, 0.06, bar)
    b.beam([s * 0.96, 1.16, bz - 0.3], [s * 1.02, 0.66, bz - 0.24], 0.06, 0.06, bar)
    for (let i = 0; i < 3; i++) b.beam([s * (0.6 + i * 0.13), 0.7, bz - 0.08 - i * 0.05], [s * (0.6 + i * 0.13), 1.16, bz - 0.04 - i * 0.07], 0.03, 0.03, bar)
  }
  b.beam([-0.48, 1.26, bz], [0.48, 1.26, bz], 0.07, 0.07, bar)
  b.beam([-0.48, 0.98, bz], [0.48, 0.98, bz], 0.05, 0.05, bar)
  b.beam([-0.48, 0.74, bz], [0.48, 0.74, bz], 0.05, 0.05, bar)
  b.cyl(0.11, 0.11, 0.5, { mat: 'paint', color: '#2a2a2a', y: 0.52, z: zn + 0.24, rz: Math.PI / 2, seg: 14 })
  b.cyl(0.1, 0.1, 0.36, { mat: 'steel', color: '#5a5a54', y: 0.52, z: zn + 0.24, rz: Math.PI / 2, seg: 14 })
  b.torus(0.04, 0.012, { mat: 'paint', color: '#c8302a', y: 0.4, z: zn + 0.38, ry: Math.PI / 2 })
  for (const s of [-1, 1]) b.torus(0.04, 0.014, { mat: 'paint', color: '#c8302a', x: s * 0.82, y: 0.5, z: zn + 0.19, rx: Math.PI / 2 })
  // roof rack and its load
  const ry0 = 2.34
  for (const x of [-0.86, 0.86]) for (const z of [-2.5, -1.2, 0.1, 0.8]) b.box(0.08, 0.1, 0.12, { mat: 'metal', color: '#2a2a28', x, y: ry0 + 0.05, z })
  for (const x of [-0.88, 0.88]) b.beam([x, ry0 + 0.14, -2.65], [x, ry0 + 0.14, 0.95], 0.045, 0.045, bar)
  for (const x of [-0.88, 0.88]) b.beam([x, ry0 + 0.32, -2.55], [x, ry0 + 0.32, 0.85], 0.035, 0.035, bar)
  for (const z of [-2.62, 0.92]) b.beam([-0.88, ry0 + 0.14, z], [0.88, ry0 + 0.14, z], 0.045, 0.045, bar)
  for (let i = 0; i < 8; i++) {
    const z = -2.4 + i * 0.44
    b.beam([-0.88, ry0 + 0.14, z], [0.88, ry0 + 0.14, z], 0.03, 0.03, bar)
    if (i % 2 === 0) for (const x of [-0.88, 0.88]) b.beam([x, ry0 + 0.14, z], [x, ry0 + 0.32, z], 0.025, 0.025, bar)
  }
  // light bar with four spots
  b.beam([-0.9, ry0 + 0.42, 1.0], [0.9, ry0 + 0.42, 1.0], 0.05, 0.05, bar)
  for (const x of [-0.6, -0.2, 0.2, 0.6]) {
    b.cyl(0.085, 0.07, 0.14, { mat: 'paint', color: '#1e1e1e', x, y: ry0 + 0.5, z: 1.04, rx: Math.PI / 2, seg: 14 })
    b.cyl(0.07, 0.07, 0.01, { mat: 'glass', color: '#e8e8e0', x, y: ry0 + 0.5, z: 1.115, rx: Math.PI / 2, seg: 14 })
  }
  // the load: spare wheel, jerrycans, a strapped tarp bundle, an ammo box
  wheelHD(b, { x: 0.15, y: ry0 + 0.32, z: -2.0, r: 0.38, w: 0.26, rx: Math.PI / 2, ry: 0, rim: '#4a4c44' })
  const cans = ['#8a2a22', '#4e5a3a', '#8a2a22', '#3e4a3a']
  for (let i = 0; i < 4; i++) {
    b.at({ x: -0.62 + i * 0.2, y: ry0 + 0.18, z: -0.9, ry: Math.PI / 2 }, () => {
      b.box(0.17, 0.44, 0.34, { mat: 'paint', color: cans[i], y: 0.22, r: 0.03 })
      for (const dz of [-0.09, 0, 0.09]) b.box(0.03, 0.05, 0.03, { mat: 'paint', color: cans[i], y: 0.46, z: dz - 0.03 })
      b.cyl(0.024, 0.024, 0.05, { mat: 'paint', color: '#2a2a2a', y: 0.46, z: 0.12, rx: -0.5 })
    })
  }
  b.box(0.95, 0.42, 0.85, { mat: 'canvas', color: '#6a6a4a', x: 0.2, y: ry0 + 0.38, z: 0.2, r: 0.12, seg: 3 })
  for (const z of [-0.05, 0.45]) b.box(1.0, 0.44, 0.04, { mat: 'cloth', color: '#c8a23a', x: 0.2, y: ry0 + 0.38, z, r: 0.02 })
  b.box(0.5, 0.26, 0.3, { mat: 'paint', color: '#4a5236', x: -0.55, y: ry0 + 0.3, z: 0.55 })
  b.box(0.52, 0.04, 0.32, { mat: 'paint', color: '#3e4630', x: -0.55, y: ry0 + 0.44, z: 0.55 })
  // CB whip antenna
  b.cyl(0.03, 0.04, 0.08, { mat: 'metal', color: '#2a2a2a', x: -0.92, y: ry0 + 0.04, z: 1.1 })
  b.cyl(0.006, 0.008, 1.3, { mat: 'steel', color: '#1e1e1e', x: -0.92, y: ry0 + 0.72, z: 1.1, rx: -0.12 })
  // rear: doors, seams, windows behind mesh, lamps, bumper, ladder, spare
  const zb = -2.92
  b.box(0.012, 1.6, 0.006, { mat: 'plain', color: '#22221e', y: 1.36, z: zb, ao: 0 })
  for (const s of [-1, 1]) {
    b.box(0.62, 0.42, 0.02, { mat: 'glass', color: '#3a4a52', x: s * 0.42, y: 1.82, z: zb - 0.004 })
    for (let i = 0; i < 5; i++) b.box(0.012, 0.44, 0.012, { mat: 'metal', color: '#3c3e38', x: s * (0.16 + i * 0.13), y: 1.82, z: zb - 0.03 })
    b.box(0.66, 0.025, 0.02, { mat: 'metal', color: '#3c3e38', x: s * 0.42, y: 2.05, z: zb - 0.03 })
    b.box(0.66, 0.025, 0.02, { mat: 'metal', color: '#3c3e38', x: s * 0.42, y: 1.6, z: zb - 0.03 })
    for (const y of [0.85, 1.95]) b.box(0.08, 0.14, 0.04, { mat: 'metal', color: '#2a2a28', x: s * 0.97, y, z: zb - 0.01 })
    b.box(0.1, 0.42, 0.04, { mat: 'glowRed', color: '#5a1612', x: s * 0.93, y: 1.0, z: zb - 0.01 })
    b.box(0.1, 0.1, 0.045, { mat: 'glowAmber', color: '#7a5212', x: s * 0.93, y: 0.74, z: zb - 0.01 })
  }
  b.box(0.04, 0.16, 0.05, { mat: 'chrome', color: '#a8acb0', x: 0.1, y: 1.3, z: zb - 0.02 })
  b.box(W + 0.04, 0.18, 0.2, { mat: 'metal', color: '#2e2e2a', y: 0.56, z: zb - 0.08 })
  b.box(0.5, 0.03, 0.25, { mat: 'metal', color: '#4a4a44', x: 0.3, y: 0.67, z: zb - 0.1 })
  b.box(0.44, 0.12, 0.01, { mat: 'paint', color: '#e8e2c8', x: -0.2, y: 0.95, z: zb - 0.012 })
  b.cyl(0.035, 0.035, 0.12, { mat: 'chrome', color: '#a8acb0', y: 0.5, z: zb - 0.26, seg: 10 })
  b.sphere(0.04, { mat: 'chrome', color: '#a8acb0', y: 0.58, z: zb - 0.26 })
  // ladder on the right door
  for (const x of [0.62, 0.86]) b.beam([x, 0.7, zb - 0.07], [x, 2.36, zb - 0.07], 0.03, 0.03, bar)
  for (let i = 0; i < 7; i++) b.beam([0.62, 0.8 + i * 0.22, zb - 0.07], [0.86, 0.8 + i * 0.22, zb - 0.07], 0.022, 0.022, bar)
  for (const y of [1.0, 2.1]) b.box(0.3, 0.03, 0.06, { mat: 'metal', color: '#2a2a28', x: 0.74, y, z: zb - 0.035 })
  // spare wheel on a swing-out carrier, left door
  b.box(0.1, 0.9, 0.05, { mat: 'metal', color: '#2a2a28', x: -0.46, y: 1.25, z: zb - 0.04 })
  wheelHD(b, { x: -0.46, y: 1.25, z: zb - 0.2, r: 0.38, w: 0.26, ry: Math.PI, rim: '#4a4c44' })
  // rust where the paint is thinnest, mud splashed behind the wheels
  // rust weeping from the roof seams
  for (let i = 0; i < 10; i++) {
    const side = rnd() < 0.5 ? -1 : 1
    const h = 0.1 + rnd() * 0.35
    b.box(0.003, h, 0.015 + rnd() * 0.03, { mat: 'rust', color: '#8a7466', x: side * (W / 2 + 0.003), y: 2.28 - h / 2, z: -2.6 + rnd() * 2.9, ao: 0, shadow: false })
  }
  // hood seams and latch
  b.box(0.012, 0.004, 1.2, { mat: 'plain', color: '#2a2822', x: 0.82, y: 1.25, z: 2.2, rx: -0.13, ao: 0 })
  b.box(0.012, 0.004, 1.2, { mat: 'plain', color: '#2a2822', x: -0.82, y: 1.25, z: 2.2, rx: -0.13, ao: 0 })
  b.box(0.14, 0.03, 0.05, { mat: 'metal', color: '#3a3a36', y: 1.17, z: 2.84 })
  if (o.broken) {
    // bonnet propped open, an engine part on the ground and a flat tyre
    b.at({ y: 1.3, z: 1.75, rx: -1.05 }, () => b.box(1.7, 0.035, 1.15, { mat: 'paint', color: top, z: 0.57 }))
    b.beam([0.6, 1.25, 2.4], [0.55, 2.05, 2.05], 0.02, 0.02, { mat: 'steel', color: '#5a5a5a' })
    b.torus(0.3, 0.1, { mat: 'rubber', color: '#1e1e1e', x: -1.65, y: 0.11, z: 1.2, rx: Math.PI / 2, rs: 6, ts2: 18, sz: 0.7 })
    b.box(0.35, 0.25, 0.28, { mat: 'rust', color: '#8a7a6a', x: 1.55, y: 0.13, z: 2.3, ry: 0.5 })
  }
  return b.build()
}
// School bus (yellow, conventional nose) or city transit bus. o: { color, seed, wreck, kind }
export function busModel(o = {}) {
  const b = new Builder()
  const rnd = seeded(o.seed ?? 9)
  const kind = o.kind ?? 'school'
  const school = kind === 'school'
  const c = o.color ?? (school ? '#d89a1c' : '#d8d4c8')
  const wreck = o.wreck ?? 0.4
  const W = 2.5
  const R = 0.5
  const body = { mat: 'paint', color: c }
  const black = { mat: 'paint', color: '#1a1a1a' }
  const zf = school ? 3.4 : 5.6
  const zb = -5.6
  const axF = school ? 4.1 : 3.9
  const axR = -3.2
  const Ra = R + 0.08
  const archAt = (zc) => {
    const out = []
    for (let k = 0; k <= 12; k++) {
      const a = Math.PI - 0.2 - (k / 12) * (Math.PI - 0.4)
      out.push([zc + Math.cos(a) * Ra, R + 0.05 + Math.sin(a) * Ra])
    }
    return out
  }
  // passenger box
  const box = [[zb, 0.62], ...archAt(axR), ...(school ? [[zf, 0.62]] : archAt(axF)), [zf, 0.62], [zf + 0.06, 1.2], [zf + 0.04, 2.95], [zf - 0.12, 3.12], [zb + 0.12, 3.12], [zb - 0.02, 2.98], [zb, 0.62]]
  b.profile(box, W, { ...body, bevel: 0.05, bevelSeg: 2 })
  if (school) {
    // conventional nose and hood
    b.profile([[zf - 0.1, 0.62], ...archAt(axF), [zf + 1.5, 0.62], [zf + 1.6, 0.7], [zf + 1.62, 1.35], [zf + 1.5, 1.55], [zf + 0.05, 1.7], [zf - 0.1, 1.7]], W - 0.3, { ...body, bevel: 0.05, bevelSeg: 2 })
    b.box(1.3, 0.62, 0.04, { mat: 'plain', color: '#141414', y: 1.05, z: zf + 1.63 })
    for (let i = 0; i < 9; i++) b.box(1.24, 0.025, 0.03, { mat: 'chrome', color: '#9a9ea2', y: 0.8 + i * 0.065, z: zf + 1.65 })
    for (const s2 of [-1, 1]) {
      b.cyl(0.12, 0.12, 0.08, { mat: 'chrome', color: '#b0b4b8', x: s2 * 0.88, y: 1.18, z: zf + 1.54, rx: Math.PI / 2, seg: 16 })
      b.cyl(0.1, 0.1, 0.03, { mat: 'glass', color: '#f0ead8', x: s2 * 0.88, y: 1.18, z: zf + 1.58, rx: Math.PI / 2, seg: 16 })
    }
    b.box(W + 0.1, 0.25, 0.2, { ...black, y: 0.6, z: zf + 1.72 })
    b.box(0.6, 0.3, 0.02, { mat: 'paint', color: '#1a1a1a', y: 2.88, z: zf + 0.07 })
    b.plane(1.6, 0.34, { material: stencilMat('SCHOOL BUS', '#1a1a1a'), y: 2.86, z: zf + 0.09, shadow: false })
    // stop arm on the driver's side
    b.cyl(0.22, 0.22, 0.03, { mat: 'paint', color: '#b82a22', x: -W / 2 - 0.05, y: 1.85, z: zf - 0.6, rz: Math.PI / 2, seg: 8 })
  } else {
    b.box(W - 0.2, 1.2, 0.04, { mat: 'carGlass', color: '#141a1e', y: 2.15, z: zf + 0.07 })
    b.box(W - 0.3, 0.26, 0.03, { mat: 'plain', color: '#0a0a0a', y: 2.92, z: zf + 0.07 })
    b.plane(1.8, 0.22, { material: stencilMat('NOT IN SERVICE', '#ffa020'), y: 2.92, z: zf + 0.09, shadow: false })
    for (const s2 of [-1, 1]) b.box(0.3, 0.14, 0.05, { mat: 'glass', color: '#f0ead8', x: s2 * 0.9, y: 0.95, z: zf + 0.07 })
    b.box(W + 0.06, 0.3, 0.16, { ...black, y: 0.66, z: zf + 0.06 })
  }
  // windscreen (school) and the window band
  if (school) b.box(W - 0.24, 0.9, 0.04, { mat: 'carGlass', color: '#141a1e', y: 2.25, z: zf + 0.07 })
  const nWin = school ? 9 : 8
  for (const s2 of [-1, 1]) {
    const x = s2 * (W / 2 + 0.01)
    const z0 = zb + 0.5
    const z1 = zf - (school ? 0.25 : 1.6)
    const step = (z1 - z0) / nWin
    for (let i = 0; i < nWin; i++) {
      const z = z0 + (i + 0.5) * step
      const smashed = rnd() < wreck * 0.35
      b.box(0.03, school ? 0.72 : 0.95, step - 0.12, { mat: smashed ? 'plain' : 'carGlass', color: smashed ? '#101010' : '#141a1e', x, y: school ? 2.32 : 2.2, z })
      b.box(0.04, school ? 0.78 : 1.0, 0.07, { ...black, x, y: school ? 2.32 : 2.2, z: z - step / 2 })
    }
    b.box(0.04, 0.05, z1 - z0, { ...black, x, y: school ? 1.92 : 1.68, z: (z0 + z1) / 2 })
    // rub rails
    for (const y of school ? [1.0, 1.42, 1.72] : [1.0]) b.box(0.045, 0.07, zf - zb - 0.4, { ...black, x, y, z: (zf + zb) / 2 })
    b.box(0.05, 0.2, 0.3, { ...black, x: s2 * (W / 2 + 0.28), y: 2.3, z: zf + (school ? 1.3 : 0.1) })
    b.beam([s2 * (W / 2), 2.4, zf + (school ? 0.1 : -0.1)], [s2 * (W / 2 + 0.28), 2.4, zf + (school ? 1.3 : 0.1)], 0.03, 0.03, { ...black, round: true })
  }
  // entry door (right, front)
  const dz = school ? zf - 0.55 : zf - 0.9
  b.box(0.04, 2.2, 0.9, { mat: 'carGlass', color: '#1a2228', x: W / 2 + 0.015, y: 1.75, z: dz })
  for (const z of [dz - 0.45, dz, dz + 0.45]) b.box(0.05, 2.25, 0.06, { ...black, x: W / 2 + 0.02, y: 1.75, z })
  // rear: emergency door, lamps, bumper
  b.box(0.95, 2.2, 0.04, { ...body, y: 1.7, z: zb - 0.06 })
  b.box(0.75, 0.6, 0.03, { mat: 'carGlass', color: '#141a1e', y: 2.35, z: zb - 0.085 })
  for (const s2 of [-1, 1]) {
    b.cyl(0.11, 0.11, 0.04, { mat: 'glowRed', color: '#5a1410', x: s2 * 0.95, y: 1.2, z: zb - 0.06, rx: Math.PI / 2, seg: 14 })
    b.cyl(0.1, 0.1, 0.04, { mat: 'glowAmber', color: '#7a5212', x: s2 * 0.95, y: 1.5, z: zb - 0.06, rx: Math.PI / 2, seg: 14 })
    for (const z of [zb + 0.05, zf - 0.05]) b.cyl(0.08, 0.08, 0.05, { mat: z > 0 ? 'glowAmber' : 'glowRed', color: '#6a3a12', x: s2 * 0.75, y: 2.98, z, rx: Math.PI / 2, seg: 12 })
  }
  b.box(W + 0.1, 0.26, 0.2, { ...black, y: 0.62, z: zb - 0.1 })
  b.box(1.0, 0.04, 1.0, { mat: 'paint', color: shadeHex(c, -0.1), y: 3.16, z: -1.5 })
  b.box(1.0, 0.04, 1.0, { mat: 'paint', color: shadeHex(c, -0.1), y: 3.16, z: 1.2 })
  b.box(W - 0.3, 0.22, zf - zb - 1.5, { mat: 'metal', color: '#1a1a1a', y: 0.45, z: (zf + zb) / 2 })
  for (const s2 of [-1, 1]) {
    wheel(b, s2 * (W / 2 - 0.2), R, axR, rnd, false, { r: R, w: 0.3, rim: '#b8bcc0' })
    wheel(b, s2 * (W / 2 - 0.36), R, axR, rnd, false, { r: R, w: 0.3, rim: '#b8bcc0' })
    wheel(b, s2 * (W / 2 - 0.2), R, axF, rnd, false, { r: R, w: 0.3, rim: '#b8bcc0' })
  }
  for (let i = 0; i < 5 + wreck * 8; i++) {
    const s2 = rnd() < 0.5 ? -1 : 1
    blob(b, 0.15 + rnd() * 0.35, 0.08 + rnd() * 0.18, { rnd, x: s2 * (W / 2 + 0.003), y: 0.75 + rnd() * 0.6, z: zb + 0.6 + rnd() * (zf - zb - 1.2), ry: (s2 * Math.PI) / 2, drip: true })
  }
  return b.build()
}
export function pickupModel(o = {}) {
  return carModel({ ...o, kind: 'pickup' })
}
// 20-foot shipping container. o.open: doors swung open.
export function containerModel(o = {}) {
  const b = new Builder()
  const rnd = seeded(o.seed ?? 7)
  const c = o.color ?? pick(rnd, ['#4a6a7e', '#7a4436', '#4e6a48', '#a8823e', '#6a6e72', '#6e3a40'])
  const L = 6.06
  const W = 2.44
  const H = 2.59
  const frame = { mat: 'paint', color: shadeHex(c, -0.22) }
  // corrugated walls and roof
  for (const sx of [-1, 1]) b.box(0.05, H - 0.32, L - 0.3, { mat: 'corrugated', color: c, x: sx * (W / 2 - 0.04), y: H / 2 })
  b.box(W - 0.2, 0.05, L - 0.3, { mat: 'corrugated', color: shadeHex(c, -0.04), y: H - 0.06 })
  b.box(W - 0.3, H - 0.32, 0.05, { mat: 'corrugated', color: c, z: L / 2 - 0.05, y: H / 2 })
  // frame: posts, rails and the eight corner castings
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) {
    b.box(0.16, H - 0.2, 0.16, { ...frame, x: sx * (W / 2 - 0.08), y: H / 2, z: sz * (L / 2 - 0.08) })
    for (const y of [0.09, H - 0.09]) {
      b.box(0.18, 0.18, 0.18, { mat: 'metal', color: shadeHex(c, -0.35), x: sx * (W / 2 - 0.09), y, z: sz * (L / 2 - 0.09) })
      b.box(0.1, 0.06, 0.012, { mat: 'plain', color: '#0e0e0e', x: sx * (W / 2 - 0.09), y, z: sz * (L / 2 + 0.002) })
    }
  }
  for (const y of [0.1, H - 0.1]) {
    for (const sx of [-1, 1]) b.box(0.14, 0.2, L - 0.36, { ...frame, x: sx * (W / 2 - 0.07), y })
    for (const sz of [-1, 1]) b.box(W - 0.36, 0.2, 0.14, { ...frame, y, z: sz * (L / 2 - 0.07) })
  }
  // doors on -z: locking bars, cams, handles, seal
  if (o.open) {
    for (const sx of [-1, 1]) {
      b.at({ x: sx * (W / 2 - 0.05), z: -L / 2, ry: sx * -1.9 }, () => {
        b.box(W / 2 - 0.08, H - 0.4, 0.05, { mat: 'corrugated', color: c, x: sx * -(W / 4), y: H / 2 })
        for (const x of [0.25, 0.75]) b.cyl(0.022, 0.022, H - 0.5, { mat: 'steel', color: '#8a8e92', x: sx * -x, y: H / 2, z: -0.05 })
      })
    }
    b.box(W - 0.3, 0.02, L - 0.4, { mat: 'planks', color: '#a89070', y: 0.21 })
    b.box(W - 0.32, H - 0.45, 0.02, { mat: 'paint', color: shadeHex(c, -0.3), y: H / 2, z: L / 2 - 0.1 })
  } else {
    b.box(W - 0.3, H - 0.38, 0.05, { mat: 'corrugated', color: c, z: -L / 2 + 0.05, y: H / 2 })
    b.box(0.03, H - 0.4, 0.03, { mat: 'rubber', color: '#1a1a1a', z: -L / 2 + 0.01, y: H / 2 })
    for (const x of [-0.85, -0.38, 0.38, 0.85]) {
      b.cyl(0.024, 0.024, H - 0.42, { mat: 'steel', color: '#8a8e92', x, y: H / 2, z: -L / 2 - 0.03, seg: 8 })
      for (const y of [0.32, H - 0.32]) b.box(0.08, 0.1, 0.06, { mat: 'metal', color: '#5a5c5e', x, y, z: -L / 2 - 0.03 })
      b.box(0.04, 0.03, 0.28, { mat: 'steel', color: '#8a8e92', x: x + 0.06, y: 1.2, z: -L / 2 - 0.16 })
    }
    b.plane(1.0, 0.25, { material: stencilMat('HLXU 31422 5', '#e8e4dc'), x: 0.55, y: H - 0.42, z: -L / 2 - 0.004, ry: Math.PI, shadow: false })
  }
  b.plane(1.6, 0.4, { material: stencilMat(pick(rnd, ['MAERSK', 'HAPAG', 'TRITON', 'CMA CGM', 'EVERGREEN', 'SEACO']), '#e8e4dc'), x: W / 2 + 0.03, y: H - 0.6, z: 0.8, ry: Math.PI / 2, shadow: false })
  for (let i = 0; i < 4; i++) {
    const sx = rnd() < 0.5 ? -1 : 1
    blob(b, 0.15 + rnd() * 0.4, 0.1 + rnd() * 0.3, { rnd, x: sx * (W / 2 + 0.005), y: 0.4 + rnd() * 1.6, z: (rnd() - 0.5) * 4.6, ry: (sx * Math.PI) / 2, drip: true })
  }
  return b.build()
}
// Draw a forklift into any builder (decor merges it into a room).
export function drawForklift(b) {
  const rnd = seeded(2)
  const yel = { mat: 'paint', color: '#d89a1a' }
  const blk = { mat: 'paint', color: '#222222' }
  // chassis, counterweight and engine cover
  b.profile([[-1.0, 0.28], [0.75, 0.28], [0.8, 0.62], [0.55, 0.9], [-0.3, 0.92], [-0.55, 1.12], [-1.05, 1.12], [-1.12, 0.9], [-1.1, 0.4]], 1.1, { ...yel, bevel: 0.04, bevelSeg: 2 })
  b.box(1.12, 0.36, 0.42, { mat: 'metal', color: '#2a2a2a', y: 0.62, z: -1.0, r: 0.06 })
  for (let i = 0; i < 4; i++) b.box(0.6, 0.02, 0.05, { ...blk, y: 1.02, z: -0.6 - i * 0.1 })
  // overhead guard
  for (const sx of [-1, 1]) {
    b.beam([sx * 0.5, 0.9, 0.5], [sx * 0.48, 2.12, 0.42], 0.06, 0.06, blk)
    b.beam([sx * 0.5, 1.1, -0.75], [sx * 0.48, 2.12, -0.6], 0.06, 0.06, blk)
  }
  b.box(1.05, 0.05, 1.12, { ...blk, y: 2.14, z: -0.09 })
  for (let i = 0; i < 6; i++) b.box(1.0, 0.04, 0.03, { ...blk, y: 2.18, z: -0.55 + i * 0.18 })
  b.box(0.12, 0.08, 0.1, { mat: 'glowAmber', color: '#9a5a12', y: 2.24, z: -0.5 })
  // seat, wheel and levers
  b.box(0.48, 0.1, 0.44, { mat: 'plastic', color: '#1a1a1a', y: 1.0, z: -0.25, r: 0.04 })
  b.box(0.48, 0.5, 0.1, { mat: 'plastic', color: '#1a1a1a', y: 1.26, z: -0.48, rx: -0.15, r: 0.04 })
  b.cyl(0.04, 0.04, 0.5, { ...blk, y: 1.05, z: 0.38, rx: 0.55 })
  b.torus(0.16, 0.02, { mat: 'plastic', color: '#1a1a1a', y: 1.3, z: 0.25, rx: 0.55 - Math.PI / 2 })
  for (let i = 0; i < 3; i++) b.cyl(0.012, 0.012, 0.3, { mat: 'steel', color: '#5a5a5a', x: 0.3 + i * 0.06, y: 1.05, z: 0.45, rx: 0.3 })
  // mast with chains, carriage and forks
  for (const sx of [-1, 1]) {
    b.box(0.1, 2.5, 0.12, { mat: 'metal', color: '#2e2e2e', x: sx * 0.36, y: 1.35, z: 0.86 })
    b.box(0.08, 2.4, 0.1, { mat: 'metal', color: '#3a3a3a', x: sx * 0.26, y: 1.45, z: 0.92 })
    b.box(0.02, 2.2, 0.02, { mat: 'steel', color: '#1a1a1a', x: sx * 0.12, y: 1.4, z: 0.9 })
    b.box(0.12, 0.05, 1.15, { mat: 'metal', color: '#3a3a38', x: sx * 0.26, y: 0.08, z: 1.6 })
    b.box(0.12, 0.6, 0.05, { mat: 'metal', color: '#3a3a38', x: sx * 0.26, y: 0.36, z: 1.03 })
  }
  for (const y of [0.25, 0.6, 2.55]) b.box(0.84, 0.08, 0.08, { mat: 'metal', color: '#2e2e2e', y, z: 0.98 })
  b.cyl(0.05, 0.05, 1.2, { mat: 'steel', color: '#b8bcc0', y: 0.8, z: 0.78 })
  for (const sx of [-1, 1]) {
    wheel(b, sx * 0.48, 0.3, 0.55, rnd, false, { r: 0.3, w: 0.24, rim: '#c88a1a' })
    wheel(b, sx * 0.48, 0.24, -0.75, rnd, false, { r: 0.24, w: 0.18, rim: '#c88a1a' })
  }
  for (const sx of [-1, 1]) b.box(0.18, 0.06, 0.1, { mat: 'glass', color: '#f0ead8', x: sx * 0.42, y: 1.9, z: 0.5 })
  blob(b, 0.4, 0.2, { rnd, x: 0.552, y: 0.6, z: -0.3, ry: Math.PI / 2, drip: true })
}
export function forkliftModel() {
  const b = new Builder()
  drawForklift(b)
  return b.build()
}
