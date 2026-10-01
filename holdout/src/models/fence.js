// Perimeter wall segments for each fence level. A segment spans one tile along
// local x (−0.5..0.5) with the outside facing −z. Each level has a few intact
// variants, a damaged variant and a broken (rubble) variant, plus corner posts
// and a three-tile gate with swinging doors.
import { Builder, seeded } from './kit.js'
import { barbedCoil, sandbags, shadeHex, sign } from './parts.js'

const TAU = Math.PI * 2
const pick = (r, a) => a[Math.floor(r() * a.length)]

function scrap(b, v, dmg, rnd) {
  // posts at the left edge of each segment (the next segment supplies the right one)
  b.box(0.11, 1.75, 0.11, { mat: 'wood', color: '#b8a890', x: -0.5, y: 0.85, rz: (rnd() - 0.5) * 0.05 })
  const tones = ['#d8c8a8', '#c0ae90', '#e0d0b4', '#a89a80', '#c8b8a0']
  if (v === 1) {
    // a corrugated sheet nailed over the rails
    if (!dmg || rnd() < 0.5) b.box(1.02, 1.35, 0.025, { mat: 'corrugated', color: pick(rnd, ['#c0b8b0', '#a8b0b4', '#c8a890']), y: 0.82, z: -0.04, rz: (rnd() - 0.5) * 0.04 })
  } else if (v === 2) {
    // a pallet stood on end
    for (let i = 0; i < 6; i++) {
      if (dmg && rnd() < 0.4) continue
      b.box(0.14, 1.2, 0.02, { mat: 'wood', color: pick(rnd, tones), x: -0.42 + i * 0.17, y: 0.65, z: -0.05 })
    }
    for (const y of [0.2, 0.65, 1.1]) b.box(1.0, 0.09, 0.07, { mat: 'wood', color: '#9a8a70', y, z: -0.0 })
  } else {
    const rows = v === 3 ? 4 : 3
    for (let i = 0; i < rows; i++) {
      if (dmg && rnd() < 0.45) continue
      const y = 0.3 + i * (1.2 / rows) + (rnd() - 0.5) * 0.06
      b.box(1.04, 0.2, 0.03, { mat: 'wood', color: pick(rnd, tones), y, z: -0.05, rz: (rnd() - 0.5) * (dmg ? 0.5 : 0.08), jitter: 0.08 })
    }
  }
  for (const y of [0.45, 1.25]) b.box(1.0, 0.07, 0.05, { mat: 'wood', color: '#8a7a64', y, z: 0.02 })
  if (!dmg) b.rope([[-0.5, 1.62, 0], [0.5, 1.62, 0]], 0.004, { mat: 'steel', color: '#8a9094', sag: 0.04, steps: 4 })
  if (v === 0 && !dmg) for (let i = 0; i < 3; i++) b.box(0.012, 0.012, 0.012, { mat: 'steel', color: '#5a5a5a', x: -0.4 + i * 0.4, y: 0.5, z: -0.07 })
}
function palisade(b, v, dmg, rnd) {
  const n = 3
  for (let i = 0; i < n; i++) {
    if (dmg && rnd() < 0.35) continue
    const x = -0.5 + (i + 0.5) / n
    const r = 0.14 + rnd() * 0.04
    const hh = 2.2 + rnd() * 0.35 - (dmg ? rnd() * 0.8 : 0)
    b.cyl(r * 0.92, r, hh, { mat: 'bark', color: pick(rnd, ['#ffffff', '#e8e0d4', '#d8d0c4']), x, y: hh / 2, seg: 8, rz: dmg ? (rnd() - 0.5) * 0.25 : 0 })
    b.cone(r * 0.92, 0.32, { mat: 'wood', color: '#d8b888', x, y: hh + 0.16, seg: 8 })
  }
  for (const y of [0.7, 1.75]) b.box(1.02, 0.14, 0.1, { mat: 'bark', color: '#c8c0b4', y, z: 0.17 })
  if (v === 1) b.box(0.22, 0.6, 0.1, { mat: 'wood', color: '#a8906a', y: 1.25, z: 0.24 })
  if (v === 2 && !dmg) b.rope([[-0.5, 1.2, -0.2], [0.5, 1.25, -0.2]], 0.012, { mat: 'cloth', color: '#a89070', sag: 0.05 })
}
function sheet(b, v, dmg, rnd) {
  b.box(0.09, 2.7, 0.09, { mat: 'paint', color: '#4a5056', x: -0.5, y: 1.35 })
  const tone = pick(rnd, ['#a8aeb2', '#b8b0a4', '#98a0a4', '#b4b8ac'])
  if (!dmg) b.box(1.02, 2.3, 0.03, { mat: 'corrugated', color: tone, y: 1.2, z: -0.03 })
  else {
    b.box(1.02, 1.0, 0.03, { mat: 'corrugated', color: tone, y: 0.55, z: -0.03, rz: (rnd() - 0.5) * 0.15 })
    b.box(0.5, 1.0, 0.03, { mat: 'corrugated', color: tone, x: -0.25, y: 1.6, z: -0.05, rz: 0.3 })
  }
  for (const y of [0.4, 1.3, 2.25]) b.box(1.0, 0.06, 0.08, { mat: 'paint', color: '#5a5048', y, z: 0.04 })
  if (!dmg) {
    barbedCoil(b, { y: 2.38, len: 1.0, r: 0.16 })
    if (v === 1) b.box(0.3, 0.42, 0.012, { mat: 'paint', color: '#e8e4dc', y: 1.4, z: -0.055 })
    if (v === 2) b.box(1.02, 0.5, 0.032, { mat: 'rust', color: '#ffffff', y: 0.3, z: -0.035 })
  }
}
function fortified(b, v, dmg, rnd) {
  if (v === 0 || v === 2) {
    // HESCO-style wire baskets filled with earth
    const hh = dmg ? 1.0 : 1.7
    b.box(1.0, hh, 0.95, { mat: 'dirt', color: '#c8b8a0', y: hh / 2, z: 0.2 })
    b.box(1.02, hh + 0.02, 0.97, { mat: 'canvas', color: '#c8bc98', y: hh / 2, z: 0.2, shadow: false })
    for (let i = 0; i <= 4; i++) {
      for (const z of [-0.29, 0.69]) b.box(0.012, hh, 0.012, { mat: 'steel', color: '#8a9094', x: -0.5 + i * 0.25, y: hh / 2, z })
      b.box(1.02, 0.012, 0.012, { mat: 'steel', color: '#8a9094', y: (i * hh) / 4, z: -0.29 })
    }
    if (!dmg) sandbags(b, { y: hh, z: 0.2, len: 1.0, rows: 2, seed: Math.floor(rnd() * 99) })
  } else {
    // pre-cast concrete T-wall
    const hh = dmg ? 1.2 : 2.4
    b.box(1.0, hh, 0.32, { mat: 'concrete', color: pick(rnd, ['#d8d4cc', '#ccc8c0', '#e0dcd4']), y: hh / 2, z: 0.0 })
    b.box(1.0, 0.35, 0.9, { mat: 'concrete', color: '#c8c4bc', y: 0.18, z: 0.2 })
    if (!dmg) b.box(0.6, 0.5, 0.01, { mat: 'paint', color: '#e8c030', y: 1.4, z: -0.165, sx: 1, rz: 0 })
  }
  if (!dmg) barbedCoil(b, { y: v === 1 || v === 3 ? 2.42 : 2.15, len: 1.0, r: 0.2, z: 0 })
}
function rubble(b, lv, rnd) {
  const m = lv === 0 ? { mat: 'wood', color: '#b8a888' } : lv === 1 ? { mat: 'bark', color: '#ffffff' } : lv === 2 ? { mat: 'corrugated', color: '#a8aeb2' } : { mat: 'concrete', color: '#c8c4bc' }
  for (let i = 0; i < 5; i++) {
    if (lv === 1) b.cyl(0.14, 0.15, 0.6 + rnd() * 1.2, { ...m, x: (rnd() - 0.5) * 0.9, y: 0.15, z: (rnd() - 0.5) * 0.9, rz: Math.PI / 2, ry: rnd() * TAU, seg: 8 })
    else if (lv === 3) b.dodeca(0.18 + rnd() * 0.15, { ...m, x: (rnd() - 0.5) * 0.9, y: 0.1, z: (rnd() - 0.5) * 0.7, sy: 0.6 })
    else b.box(0.5 + rnd() * 0.6, 0.03, 0.18 + rnd() * 0.2, { ...m, x: (rnd() - 0.5) * 0.7, y: 0.03 + i * 0.03, z: (rnd() - 0.5) * 0.8, ry: rnd() * TAU, rz: (rnd() - 0.5) * 0.3 })
  }
  b.box(0.11, lv === 3 ? 0.5 : 0.7, 0.11, { ...m, x: -0.5, y: 0.3, rz: 0.4 })
}

const builders = [scrap, palisade, sheet, fortified]
export const FENCE_VARIANTS = 4
// kind: 'v0'..'v3' | 'damaged' | 'broken' | 'corner'
export function fenceSegment(level, kind, seed = 1) {
  const rnd = seeded(seed * 31 + level * 7 + kind.length)
  const b = new Builder()
  if (kind === 'broken') rubble(b, level, rnd)
  else if (kind === 'corner') {
    if (level === 0) b.box(0.18, 1.9, 0.18, { mat: 'wood', color: '#a89878', y: 0.95 })
    else if (level === 1) b.cyl(0.24, 0.26, 2.9, { mat: 'bark', color: '#ffffff', y: 1.45, seg: 10 })
    else if (level === 2) b.box(0.2, 2.9, 0.2, { mat: 'paint', color: '#3a4046', y: 1.45 })
    else {
      b.box(1.1, 2.6, 1.1, { mat: 'concrete', color: '#d0ccc4', y: 1.3 })
      b.box(1.2, 0.15, 1.2, { mat: 'concrete', color: '#c0bcb4', y: 2.65 })
    }
  } else builders[level](b, kind === 'damaged' ? 0 : parseInt(kind.slice(1), 10), kind === 'damaged', rnd)
  return b.build()
}

// The gate: posts, a lintel with the camp sign and two door leaves (pivots doorL/doorR).
export function gateModel(level) {
  const b = new Builder()
  const W = 3
  const H = [1.9, 2.5, 2.6, 2.6][level]
  const postH = H + 1.0
  const pc = level === 0 ? { mat: 'wood', color: '#a89878' } : level === 1 ? { mat: 'bark', color: '#ffffff' } : level === 2 ? { mat: 'paint', color: '#3a4046' } : { mat: 'concrete', color: '#d0ccc4' }
  for (const sx of [-1, 1]) {
    if (level === 1) b.cyl(0.26, 0.28, postH + 0.4, { ...pc, x: sx * (W / 2 + 0.1), y: (postH + 0.4) / 2, seg: 10 })
    else if (level === 3) b.box(0.8, postH, 0.8, { ...pc, x: sx * (W / 2 + 0.4), y: postH / 2 })
    else b.box(0.22, postH, 0.22, { ...pc, x: sx * (W / 2 + 0.1), y: postH / 2 })
  }
  b.box(W + 0.6, 0.25, 0.2, { ...pc, y: postH - 0.1 })
  sign(b, 'HOLDOUT', { y: postH + 0.25, z: -0.12, w: 2.2, h: 0.42, bg: '#2a2620', fg: '#e8d8b0', ry: Math.PI })
  if (level >= 2) {
    b.sphere(0.07, { mat: 'glowRed', color: '#ffffff', x: -W / 2 - 0.1, y: postH + 0.1 })
    b.sphere(0.07, { mat: 'glowRed', color: '#ffffff', x: W / 2 + 0.1, y: postH + 0.1 })
  }
  for (const [name, sx] of [['doorL', -1], ['doorR', 1]]) {
    b.pivot(name, { x: sx * (W / 2), y: 0 }, (p) => {
      const dw = W / 2 - 0.04
      const x0 = -sx * dw / 2
      if (level === 0) {
        for (let i = 0; i < 6; i++) p.box(dw / 6 - 0.012, H, 0.04, { mat: 'wood', color: ['#d8c8a8', '#c0ae90', '#e0d0b4'][i % 3], x: x0 - dw / 2 + (dw / 6) * (i + 0.5), y: H / 2 + 0.05 })
        for (const y of [0.4, H - 0.3]) p.box(dw, 0.12, 0.05, { mat: 'wood', color: '#8a7a64', x: x0, y, z: 0.04 })
        p.beam([x0 - dw / 2 + 0.1, 0.4, 0.05], [x0 + dw / 2 - 0.1, H - 0.3, 0.05], 0.12, 0.05, { mat: 'wood', color: '#8a7a64' })
      } else if (level === 1) {
        for (let i = 0; i < 5; i++) {
          p.cyl(0.13, 0.14, H, { mat: 'bark', color: '#ffffff', x: x0 - dw / 2 + (dw / 5) * (i + 0.5), y: H / 2, seg: 8 })
          p.cone(0.13, 0.25, { mat: 'wood', color: '#d8b888', x: x0 - dw / 2 + (dw / 5) * (i + 0.5), y: H + 0.12, seg: 8 })
        }
        for (const y of [0.6, H - 0.5]) p.box(dw, 0.14, 0.12, { mat: 'bark', color: '#c8c0b4', x: x0, y, z: 0.15 })
      } else {
        p.box(dw, H, 0.06, { mat: level === 2 ? 'corrugated' : 'metal', color: level === 2 ? '#a8aeb2' : '#5a6066', x: x0, y: H / 2 + 0.05 })
        p.box(dw, 0.1, 0.1, { mat: 'paint', color: '#3a4046', x: x0, y: H, z: 0.05 })
        p.box(dw, 0.1, 0.1, { mat: 'paint', color: '#3a4046', x: x0, y: 0.15, z: 0.05 })
        if (level === 3) for (let i = 0; i < 4; i++) p.box(0.05, H - 0.2, 0.05, { mat: 'paint', color: '#3a4046', x: x0 - dw / 2 + 0.2 + i * 0.4, y: H / 2, z: 0.06 })
        p.box(0.25, 0.35, 0.02, { mat: 'paint', color: '#e8c030', x: x0, y: 1.3, z: -0.04 })
      }
    })
  }
  if (level >= 1) {
    // a lookout walkway over the gate
    b.box(W + 1.2, 0.1, 0.9, { mat: 'planks', color: '#e0d4c0', y: postH - 0.3, z: 0.6 })
    for (let i = 0; i < 9; i++) b.box(0.05, 0.8, 0.05, { mat: 'wood', color: '#b8a888', x: -W / 2 - 0.5 + i * ((W + 1) / 8), y: postH + 0.1, z: 1.0 })
    b.box(W + 1.2, 0.06, 0.08, { mat: 'wood', color: '#b8a888', y: postH + 0.5, z: 1.0 })
  }
  return b.build()
}
