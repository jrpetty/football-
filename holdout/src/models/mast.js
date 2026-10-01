// The Signal Mast: Ashford's old broadcast tower, rebuilt in five phases.
// Level 1 is the rusted stump the camp inherits; each completed phase of the
// Signal is a level up to 6, when it stands 20 m tall with its dishes,
// amplifier cabinets and aviation lights, beacon turning. Footprint 6×6,
// centred on the origin, front (+z) toward the camp.
import { seeded } from './kit.js'
import { crate, barrel, pallet, shadeHex, cinderBlocks, sack } from './parts.js'
import { elecPanel, cable, canopy, drum, stripeMat, decal } from './detail.js'

const TAU = Math.PI * 2
const TX = 0.35 // tower centre
const TZ = -0.45
const W0 = 1.25 // half-width at the foot
const W1 = 0.3 // half-width at 20 m
const HT = 20
const halfW = (y) => W0 + (W1 - W0) * (y / HT)
const RED = '#c8342a'
const WHITE = '#d2cec4'
const RUST = '#7a5240'

// One stretch of four-legged lattice from y0 to y1. Painted sections get
// red and white aviation bands; old ones are rust.
function lattice(b, y0, y1, o = {}) {
  const old = !!o.old
  const step = 1.25
  const n = Math.max(1, Math.round((y1 - y0) / step))
  const legC = old ? RUST : '#9aa2a8'
  const legM = old ? 'rust' : 'metal'
  for (let i = 0; i < n; i++) {
    const ya = y0 + ((y1 - y0) * i) / n
    const yb = y0 + ((y1 - y0) * (i + 1)) / n
    const wa = halfW(ya)
    const wb = halfW(yb)
    const band = old ? RUST : Math.floor(yb / 3) % 2 ? WHITE : RED
    const bandM = old ? 'rust' : 'paint'
    for (const [sx, sz] of [[-1, -1], [1, -1], [1, 1], [-1, 1]]) b.beam([sx * wa, ya, sz * wa], [sx * wb, yb, sz * wb], 0.11, 0.11, { mat: bandM, color: band })
    for (const [ax, az, bx, bz] of [[-1, -1, 1, -1], [1, -1, 1, 1], [1, 1, -1, 1], [-1, 1, -1, -1]]) {
      b.beam([ax * wb, yb, az * wb], [bx * wb, yb, bz * wb], 0.06, 0.06, { mat: bandM, color: band })
      // X bracing on every face
      b.beam([ax * wa, ya, az * wa], [bx * wb, yb, bz * wb], 0.04, 0.04, { mat: legM, color: legC })
      b.beam([bx * wa, ya, bz * wa], [ax * wb, yb, az * wb], 0.04, 0.04, { mat: legM, color: legC })
    }
  }
}
// A grating platform with a railing, wrapped around the tower at height y.
function platform(b, y) {
  const w = halfW(y) + 0.55
  b.box(w * 2, 0.06, w * 2, { mat: 'metal', color: '#6a7076', y })
  for (const [x, z, sx, sz] of [[0, -w, w * 2, 0.04], [0, w, w * 2, 0.04], [-w, 0, 0.04, w * 2], [w, 0, 0.04, w * 2]]) {
    b.box(sx, 0.04, sz, { mat: 'paint', color: '#d8a820', x, y: y + 1.0, z })
    b.box(sx, 0.03, sz, { mat: 'paint', color: '#d8a820', x, y: y + 0.55, z })
  }
  for (const sx of [-1, 1]) for (const sz of [-1, 1]) b.box(0.05, 1.0, 0.05, { mat: 'paint', color: '#d8a820', x: sx * w, y: y + 0.5, z: sz * w })
}
// A ladder up one leg, from 0 to h.
function ladder(b, h) {
  const x = halfW(0) + 0.18
  b.at({ x: x * 0.98 }, () => {
    for (const s of [-0.2, 0.2]) b.beam([0, 0, s], [-(halfW(0) - halfW(h)), h, s], 0.04, 0.04, { mat: 'metal', color: '#a8aeb4' })
    for (let y = 0.3; y < h; y += 0.32) {
      const dx = -(halfW(0) - halfW(y))
      b.box(0.03, 0.03, 0.4, { mat: 'metal', color: '#a8aeb4', x: dx, y })
    }
  })
}
function avLight(b, x, y, z, glow = true) {
  b.cyl(0.07, 0.07, 0.06, { mat: 'paint', color: '#2a2a2a', x, y, z, seg: 10 })
  b.sphere(0.08, { mat: glow ? 'glowRed' : 'plastic', color: glow ? '#5a1410' : '#6a2a22', x, y: y + 0.08, z })
}
// A parabolic dish on an arm, pointing along `ry`.
function dish(b, y, ry, r = 0.75) {
  b.at({ y, ry }, () => {
    const off = halfW(y) + 0.25
    b.box(0.08, 0.08, off, { mat: 'metal', color: '#8a9096', z: off / 2 })
    b.at({ z: off + 0.1, rx: -0.12 }, () => {
      b.lathe([[0.001, 0], [r * 0.6, 0.1], [r, 0.26], [r * 1.02, 0.28]], { mat: 'paint', color: '#ecebe4', rx: Math.PI / 2, seg: 22 })
      b.lathe([[r * 1.02, 0.28], [r, 0.26], [r * 0.6, 0.1], [0.001, 0]], { mat: 'paint', color: '#c8c8c0', rx: Math.PI / 2, seg: 22 })
      for (let i = 0; i < 3; i++) {
        const a = (i / 3) * TAU
        b.beam([Math.cos(a) * r * 0.9, Math.sin(a) * r * 0.9, 0.26], [0, 0, 0.75], 0.02, 0.02, { mat: 'metal', color: '#8a9096' })
      }
      b.cyl(0.06, 0.06, 0.14, { mat: 'paint', color: '#3a3e42', z: 0.78, rx: Math.PI / 2, seg: 10 })
    })
  })
}
// Equipment cabinet with vents and a status lamp.
function cabinet(b, o) {
  b.at({ x: o.x, z: o.z, ry: o.ry || 0 }, () => {
    b.box(0.7, o.h ?? 1.8, 0.6, { mat: 'paint', color: o.color ?? '#4a5560', y: (o.h ?? 1.8) / 2, r: 0.02 })
    b.box(0.62, (o.h ?? 1.8) - 0.2, 0.01, { mat: 'paint', color: shadeHex(o.color ?? '#4a5560', 0.12), y: (o.h ?? 1.8) / 2, z: 0.305 })
    for (let i = 0; i < 5; i++) b.box(0.4, 0.02, 0.012, { mat: 'paint', color: '#22262a', y: 0.3 + i * 0.07, z: 0.312 })
    b.box(0.05, 0.05, 0.02, { mat: o.lamp ?? 'glowGreen', color: '#1a3a1a', x: 0.22, y: (o.h ?? 1.8) - 0.18, z: 0.31 })
    b.box(0.05, 0.05, 0.02, { mat: 'glowAmber', color: '#3a2a10', x: 0.12, y: (o.h ?? 1.8) - 0.18, z: 0.31 })
  })
}

export function mast(b, L, I) {
  const rnd = seeded(9000 + L)
  const phase = L - 1
  // foundation: a pad, four footings, the old fence stubs
  b.box(5.7, 0.18, 5.7, { mat: 'concrete', color: '#9a968e', y: 0.09 })
  b.box(5.9, 0.06, 5.9, { mat: 'gravel', color: '#8a8478', y: 0.03 })
  b.at({ x: TX, z: TZ }, () => {
    for (const [sx, sz] of [[-1, -1], [1, -1], [1, 1], [-1, 1]]) {
      b.box(0.62, 0.42, 0.62, { mat: 'concrete', color: '#b0aca2', x: sx * W0, y: 0.21, z: sz * W0 })
      b.box(0.36, 0.04, 0.36, { mat: 'metal', color: '#5a5e62', x: sx * W0, y: 0.44, z: sz * W0 })
    }
    // how far the tower stands in each phase
    const restored = [0, 7, 11.5, HT, HT, HT][phase]
    if (phase === 0) {
      // the stump the camp inherits: rust, a buckled top, vines
      lattice(b, 0.4, 6.8, { old: true })
      b.at({ y: 6.8 }, () => {
        const w = halfW(6.8)
        b.beam([-w, 0, -w], [-w + 0.6, 1.6, -w - 0.5], 0.1, 0.1, { mat: 'rust', color: RUST })
        b.beam([w, 0, -w], [w + 0.2, 1.3, -w - 0.9], 0.1, 0.1, { mat: 'rust', color: RUST })
        b.beam([w, 0, w], [w + 0.7, 0.9, w - 0.2], 0.1, 0.1, { mat: 'rust', color: RUST })
        b.beam([-w, 0, w], [-w + 0.4, 1.4, w - 0.6], 0.1, 0.1, { mat: 'rust', color: RUST })
      })
      for (let i = 0; i < 18; i++) {
        const y = rnd() * 3.5
        const s = rnd() < 0.5 ? -1 : 1
        const w = halfW(y)
        b.sphere(0.12 + rnd() * 0.12, { mat: 'leaf', color: rnd() < 0.5 ? '#4a6a2a' : '#5a7a34', x: s * w + (rnd() - 0.5) * 0.3, y: y + 0.4, z: (rnd() - 0.5) * w * 2, sy: 1.4 })
      }
      decal(b, stripeMat(), 1.2, 0.3, { x: 0, y: 1.6, z: halfW(1.6) + 0.06 })
    } else {
      lattice(b, 0.4, restored)
      ladder(b, restored)
      if (restored >= 7.5) platform(b, 7)
      if (restored >= 14.5) platform(b, 14)
      // a fresh cable bundle up the tower
      cable(b, [[halfW(0) - 0.1, 0.5, 0.2], [halfW(restored * 0.5) - 0.1, restored * 0.5, 0.15], [halfW(restored) - 0.05, restored - 0.3, 0.1]], { r: 0.04, sag: 0.02, color: '#1a1a1a' })
      if (restored < HT) {
        // scaffold cage and a gin pole for lifting the next sections
        const y0 = restored
        const w = halfW(y0) + 0.35
        for (const [sx, sz] of [[-1, -1], [1, -1], [1, 1], [-1, 1]]) b.cyl(0.035, 0.035, 2.2, { mat: 'steel', color: '#c8ccd0', x: sx * w, y: y0 + 1.1, z: sz * w, seg: 6 })
        for (const y of [y0 + 0.9, y0 + 2.0]) for (const [x, z, rz, rx] of [[0, -w, Math.PI / 2, 0], [0, w, Math.PI / 2, 0], [-w, 0, 0, Math.PI / 2], [w, 0, 0, Math.PI / 2]]) b.cyl(0.03, 0.03, w * 2, { mat: 'steel', color: '#c8ccd0', x, y, z, rz, rx, seg: 6 })
        b.box(w * 2, 0.05, w * 2, { mat: 'wood', color: '#c8b088', y: y0 + 0.05 })
        b.beam([halfW(y0) * 0.6, y0, 0], [-0.3, y0 + 4.2, 0.4], 0.09, 0.09, { mat: 'paint', color: '#d8a820' })
        b.rope([[-0.3, y0 + 4.2, 0.4], [-1.2, y0 + 1.2, 1.4], [-1.6, 0.4, 2.4]], 0.012, { mat: 'steel', color: '#5a5a5a', sag: 0.05, steps: 6 })
        // the next section waiting on the ground
        b.at({ x: -2.0 - TX, z: -2.0 - TZ, ry: 0.4 }, () => {
          for (const [sx, sz] of [[-1, -1], [1, -1], [1, 1], [-1, 1]]) b.beam([sx * 0.35, 0.35, sz * 0.35 - 1.1], [sx * 0.35, 0.35, sz * 0.35 + 1.1], 0.07, 0.07, { mat: 'paint', color: RED })
          for (let z = -1.1; z <= 1.1; z += 0.55) for (const [ax, ay, bx, by] of [[-0.35, 0, 0.35, 0.7], [0.35, 0, -0.35, 0.7]]) b.beam([ax, ay + 0.0, z], [bx, by, z + 0.27], 0.035, 0.035, { mat: 'metal', color: '#9aa2a8' })
          for (const z of [-0.8, 0.8]) b.box(0.9, 0.12, 0.2, { mat: 'wood', color: '#9a7a52', y: 0.06, z })
        })
      }
      if (restored >= HT) {
        // the crown: whip antenna, panel antennas, lights
        b.cyl(0.05, 0.03, 3.2, { mat: 'metal', color: '#c8ccd0', y: HT + 1.6, seg: 8 })
        b.box(W1 * 2 + 0.2, 0.08, W1 * 2 + 0.2, { mat: 'metal', color: '#6a7076', y: HT })
        for (const [x, z, ry] of [[0, -W1 - 0.12, 0], [0, W1 + 0.12, 0], [-W1 - 0.12, 0, Math.PI / 2], [W1 + 0.12, 0, Math.PI / 2]]) b.box(0.28, 1.4, 0.08, { mat: 'paint', color: '#e8e8e0', x, y: HT - 0.9, z, ry, r: 0.02 })
        avLight(b, 0, HT + 3.25, 0, phase >= 3)
        for (const y of [7.15, 14.15]) for (const [sx, sz] of [[-1, -1], [1, 1]]) avLight(b, sx * (halfW(y) + 0.5), y + 1.05, sz * (halfW(y) + 0.5), phase >= 3)
        // the dish array
        dish(b, 16.2, 0.4, 0.8)
        dish(b, 15.2, 2.5, 0.7)
        dish(b, 17.2, 4.4, 0.65)
        // guy wires to the corners of the pad
        for (const [sx, sz] of [[-1, -1], [1, -1], [1, 1], [-1, 1]]) {
          const ax = sx * 2.75 - TX
          const az = sz * 2.75 - TZ
          b.rope([[sx * halfW(13), 13, sz * halfW(13)], [ax, 0.25, az]], 0.008, { mat: 'steel', color: '#7a7a7a', sag: 0.04, steps: 5 })
          b.box(0.22, 0.25, 0.22, { mat: 'concrete', color: '#b0aca2', x: ax, y: 0.12, z: az })
        }
      }
      if (phase >= 4) {
        // horn antennas and coil housings on the upper platform
        for (const [x, z, ry] of [[0.9, 0.9, 0.8], [-0.9, 0.9, -0.8], [0, -1.1, Math.PI]]) {
          b.at({ x, y: 14.5, z, ry }, () => {
            b.box(0.3, 0.3, 0.6, { mat: 'paint', color: '#d8d8d0', y: 0.3 })
            b.box(0.5, 0.5, 0.06, { mat: 'paint', color: '#c8c8c0', y: 0.3, z: 0.32 })
          })
        }
        for (const s of [-1, 1]) b.cyl(0.18, 0.18, 0.7, { mat: 'metal', color: '#c8783e', x: s * 0.45, y: 7.5, z: 0.8, seg: 14 })
      }
      if (phase >= 5) {
        // the beacon, turning, and bunting on the guys
        b.pivot('beacon', { y: HT + 3.45 }, (p) => {
          p.cyl(0.16, 0.16, 0.2, { mat: 'glowWhite', color: '#fff8e8', seg: 14 })
          p.box(0.5, 0.12, 0.08, { mat: 'paint', color: '#2a2a2a', x: 0.18 })
          p.cone(0.25, 0.4, { mat: 'glowAmber', color: '#ffd080', x: 0.48, rz: Math.PI / 2, seg: 10 })
        })
        I.anims.push({ name: 'beacon', kind: 'spin', axis: 'y', speed: 1.4, when: 'always' })
        for (const [sx, sz] of [[-1, -1], [1, 1]]) {
          for (let i = 1; i < 8; i++) {
            const t = i / 8
            const x = sx * halfW(13) * (1 - t) + (sx * 2.75 - TX) * t
            const z = sz * halfW(13) * (1 - t) + (sz * 2.75 - TZ) * t
            const y = 13 * (1 - t) + 0.25 * t
            b.cone(0.09, 0.2, { mat: 'canvas', color: ['#d8342a', '#e8c840', '#3a7ac8', '#f0ece0'][i % 4], x, y: y - 0.12, z, rx: Math.PI, seg: 3 })
          }
        }
        I.lights.push({ x: TX, y: HT + 3.5, z: TZ, color: '#ffe8b0', intensity: 3.5, dist: 22, when: 'night' })
      }
    }
  })
  // ground level: the control shed (phase 2+), cell bank (3+), amplifier cabinets (4+)
  if (phase >= 2) {
    b.at({ x: -2.05, z: 1.7 }, () => {
      b.box(1.5, 2.3, 2.0, { mat: 'corrugated', color: '#8a9a9a', y: 1.15 })
      b.box(1.7, 0.08, 2.2, { mat: 'roofmetal', color: '#5a5e5a', y: 2.34, rz: 0.06 })
      b.box(0.03, 0.55, 0.8, { mat: 'window', color: '#20262a', x: 0.76, y: 1.45, z: 0.2 })
      b.box(0.03, 1.9, 0.8, { mat: 'paint', color: '#4a5a5a', x: 0.76, y: 0.95, z: -0.55 })
      elecPanel(b, { x: 0.82, y: 1.3, z: 0.75, ry: Math.PI / 2, color: '#8a9094' })
      // transformer
      b.box(0.8, 1.0, 0.7, { mat: 'paint', color: '#5a6a58', x: 0.15, y: 0.5, z: 1.45, r: 0.02 })
      for (let i = 0; i < 6; i++) b.box(0.04, 0.85, 0.72, { mat: 'paint', color: '#4a5a48', x: -0.22 + i * 0.09, y: 0.5, z: 1.45 })
      for (const x of [-0.1, 0.15, 0.4]) b.cyl(0.05, 0.07, 0.3, { mat: 'plastic', color: '#c87a3a', x, y: 1.15, z: 1.45, seg: 8 })
    })
    cable(b, [[-1.3, 1.9, 1.7], [-0.4, 2.2, 0.6], [TX - halfW(2.2), 2.2, TZ + 0.3]], { r: 0.035, sag: 0.25 })
    I.lights.push({ x: -1.25, y: 1.6, z: 1.9, color: '#c8e0ff', intensity: 1.5, dist: 5, when: 'night' })
  }
  if (phase >= 3) {
    b.at({ x: 1.75, z: 2.2 }, () => {
      b.box(2.0, 0.15, 0.9, { mat: 'concrete', color: '#a8a49c', y: 0.075 })
      for (let i = 0; i < 4; i++) cabinet(b, { x: -0.72 + i * 0.48, z: 0, h: 1.3, color: '#3a6a6a', lamp: 'glowBlue' })
      b.box(2.0, 0.06, 0.9, { mat: 'roofmetal', color: '#5a5e5a', y: 1.55 })
      for (const x of [-0.95, 0.95]) b.box(0.06, 1.55, 0.06, { mat: 'metal', color: '#7a7e82', x, y: 0.78, z: 0.4 })
    })
  }
  if (phase >= 4) {
    for (let i = 0; i < 3; i++) cabinet(b, { x: -2.45 + i * 0.75, z: -2.35, ry: 0, color: '#5a4a6a', lamp: 'glowAmber' })
    cable(b, [[-1.7, 1.8, -2.1], [-0.4, 2.4, -1.4], [TX - halfW(2.4), 2.4, TZ - 0.3]], { r: 0.04, sag: 0.2 })
  }
  // construction clutter: plenty early, a tidy pad at the end
  const piles = [4, 4, 3, 2, 1, 0][phase]
  const spots = [[-2.3, 2.4], [2.4, -2.2], [-2.4, -1.0], [2.5, 0.7]]
  for (let i = 0; i < piles; i++) {
    const [x, z] = spots[i]
    if (phase >= 2 && x < -1 && z > 0.5) continue
    if (phase >= 3 && x > 1 && z > 1) continue
    if (phase >= 4 && z < -1.5 && x < -1) continue
    const k = (i + phase) % 4
    if (k === 0) {
      pallet(b, { x, z, ry: 0.2 })
      for (let j = 0; j < 3; j++) crate(b, { x: x - 0.3 + (j % 2) * 0.55, y: 0.13 + Math.floor(j / 2) * 0.48, z: z + (j > 1 ? 0 : 0.1), w: 0.5, h: 0.45, d: 0.5, color: '#b8a080' })
    } else if (k === 1) {
      // a cable drum on its side
      b.at({ x, z, ry: 0.7 }, () => {
        for (const s of [-1, 1]) b.cyl(0.55, 0.55, 0.06, { mat: 'wood', color: '#a8865a', x: s * 0.3, y: 0.55, rz: Math.PI / 2, seg: 20 })
        b.cyl(0.38, 0.38, 0.56, { mat: 'rubber', color: '#2a2a2a', y: 0.55, rz: Math.PI / 2, seg: 16 })
      })
    } else if (k === 2) {
      drum(b, { x, z, color: '#3a5a7a' })
      drum(b, { x: x + 0.62, z: z + 0.1, color: '#7a3a2a' })
    } else {
      cinderBlocks(b, { x, z, n: 6, seed: 33 + phase })
      sack(b, { x: x + 0.6, z: z - 0.2 })
    }
  }
  if (phase <= 1) canopy(b, I, { x: 2.0, z: 1.6, w: 1.8, d: 1.5, hf: 2.0, hb: 1.7, color: '#4f6e4a', seed: 5, guys: false })
}
