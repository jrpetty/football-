// Everything around the camp: the painted terrain, grass, the themed land of
// each expansion (woods, wrecks, meadow, yard, orchard, freight yard, roadside),
// the wilderness beyond, the road with its power lines, and ruins on the horizon.
// Features inside the fence disappear as the camp grows.
import * as THREE from 'three'
import { Splat, Terrain, GrassField, Scatter } from '../render/terrain.js'
import { CullView } from '../render/instcull.js'
import { pineModel, broadleafModel, deadTreeModel, bushModel, boulderModel, stumpModel, fallenLogModel, flowersModel, reedsModel, lowDetail } from '../models/nature.js'
import { carModel, busModel, containerModel, forkliftModel, pickupModel, vanModel, CAR_COLORS } from '../models/vehicles.js'
import { Builder, seeded } from '../models/kit.js'
import { fruitTree, pallet, crate, tireStack, scrapPile, hayBale, barrel, logPile, cableReel, sign, shadeHex, cinderBlocks, plankStack, sack, lampPost, sandbags, tarp, jerrycan, firewood, wheelbarrow } from '../models/parts.js'
import { windowPane } from '../models/stationkit.js'
import { S, BASE, bounds, stationSize, gateTiles } from '../game/state.js'
import { EXPANSIONS, STATIONS } from '../game/data.js'
import { BLOCK } from '../core/grid.js'

const pick = (r, a) => a[Math.floor(r() * a.length)]
export const MAXB = { x0: 20, z0: 20, x1: 92, z1: 92 }
export const ROAD_Z = 104
const GATE_X = 56.5

function zoneAt(x, z) {
  const inMax = x >= MAXB.x0 && x <= MAXB.x1 && z >= MAXB.z0 && z <= MAXB.z1
  if (!inMax) {
    if (z < MAXB.z0) return 'forest'
    if (z > MAXB.z1) return 'south'
    if (x < MAXB.x0) return 'fields'
    return 'scrub'
  }
  if (z < 40) return z < 30 ? 'ridge' : 'woods'
  if (z > 72) return z > 82 ? 'lot' : 'yard'
  if (x > 72) return x > 82 ? 'containers' : 'wrecks'
  if (x < 40) return x < 30 ? 'orchard' : 'meadow'
  return 'camp'
}

// Feature kinds: model factory, instance budget, footprint radius (tiles blocked), wind.
const KINDS = {
  pine0: { make: () => pineModel(11), lod: () => lowDetail(() => pineModel(11)), max: 700, block: 0.6, wind: true },
  pine1: { make: () => pineModel(23, { h: 9.5 }), lod: () => lowDetail(() => pineModel(23, { h: 9.5 })), max: 700, block: 0.6, wind: true },
  pine2: { make: () => pineModel(37, { h: 6.5 }), lod: () => lowDetail(() => pineModel(37, { h: 6.5 })), max: 700, block: 0.6, wind: true },
  broad0: { make: () => broadleafModel(5), lod: () => lowDetail(() => broadleafModel(5)), max: 260, block: 0.6, wind: true },
  broad1: { make: () => broadleafModel(17), lod: () => lowDetail(() => broadleafModel(17)), max: 260, block: 0.6, wind: true },
  broad2: { make: () => broadleafModel(29, { autumn: true }), lod: () => lowDetail(() => broadleafModel(29, { autumn: true })), max: 120, block: 0.6, wind: true },
  dead: { make: () => deadTreeModel(3), max: 120, block: 0.5 },
  bush0: { make: () => bushModel(3), max: 500, wind: true },
  bush1: { make: () => bushModel(9, { berries: '#a82a3a' }), max: 300, wind: true },
  bush2: { make: () => bushModel(15, { colors: ['#6a7a3a', '#7a8a44', '#5a6a32'], s: 0.8 }), max: 400, wind: true },
  boulder0: { make: () => boulderModel(4, { s: 0.9 }), max: 200, block: 0.9 },
  boulder1: { make: () => boulderModel(8, { s: 0.55 }), max: 300, block: 0.5 },
  stump: { make: () => stumpModel(2), max: 200 },
  log: { make: () => fallenLogModel(6), max: 120, block: 0.5 },
  flowers0: { make: () => flowersModel(1), max: 600, shadow: false },
  flowers1: { make: () => flowersModel(4, { colors: ['#f0f0e8', '#e8d040'] }), max: 600, shadow: false },
  reeds: { make: () => reedsModel(2), max: 200, shadow: false, wind: true },
  fruit0: { make: () => modelOf((b) => fruitTree(b, { seed: 3 })), max: 80, block: 0.5, wind: true },
  fruit1: { make: () => modelOf((b) => fruitTree(b, { seed: 8, fruitColor: '#e8a020' })), max: 80, block: 0.5, wind: true },
  car0: { make: () => carModel({ color: CAR_COLORS[0], wreck: 0.8, seed: 1 }), max: 60, block: 1.4 },
  car1: { make: () => carModel({ color: CAR_COLORS[1], wreck: 0.6, seed: 2, kind: 'wagon' }), max: 60, block: 1.4 },
  car2: { make: () => carModel({ color: CAR_COLORS[2], wreck: 0.9, seed: 3, kind: 'hatch' }), max: 60, block: 1.4 },
  car3: { make: () => carModel({ color: CAR_COLORS[5], wreck: 0.7, seed: 4 }), max: 60, block: 1.4 },
  car4: { make: () => carModel({ color: CAR_COLORS[6], wreck: 0.5, seed: 5 }), max: 60, block: 1.4 },
  pickup: { make: () => pickupModel({ seed: 7, wreck: 0.6 }), max: 30, block: 1.5 },
  bus: { make: () => busModel({ seed: 2 }), max: 6, block: 2.4 },
  cont0: { make: () => containerModel({ seed: 1, color: '#4a6a7e' }), max: 40, block: 2.2 },
  cont1: { make: () => containerModel({ seed: 2, color: '#7a4436' }), max: 40, block: 2.2 },
  cont2: { make: () => containerModel({ seed: 3, color: '#4e6a48' }), max: 40, block: 2.2 },
  cont3: { make: () => containerModel({ seed: 4, color: '#a8823e', open: true }), max: 40, block: 2.2 },
  forklift: { make: () => forkliftModel(), max: 8, block: 1 },
  pallets: { make: () => modelOf((b) => { for (let i = 0; i < 5; i++) pallet(b, { y: i * 0.13, ry: (i % 2) * 0.06 }) }), max: 80, block: 0.6 },
  crates: { make: () => modelOf((b) => { pallet(b, {}); for (let i = 0; i < 4; i++) crate(b, { x: -0.3 + (i % 2) * 0.6, y: 0.13 + Math.floor(i / 2) * 0.5, z: 0, w: 0.56, h: 0.48, d: 0.56 }) }), max: 80, block: 0.7 },
  tires: { make: () => modelOf((b) => { tireStack(b, { n: 4 }); tireStack(b, { x: 0.7, n: 2 }); tireStack(b, { x: 0.3, z: 0.65, n: 3 }) }), max: 60, block: 0.6 },
  scrap: { make: () => modelOf((b) => scrapPile(b, { r: 1.4, seed: 4, n: 18 })), max: 50, block: 1.0 },
  hay: { make: () => modelOf((b) => { hayBale(b, {}); hayBale(b, { x: 1.0, ry: 0.1 }); hayBale(b, { x: 0.5, y: 0.45, ry: -0.1 }) }), max: 60, block: 0.7 },
  barrels: { make: () => modelOf((b) => { barrel(b, { color: '#3a5878', seed: 1 }); barrel(b, { x: 0.62, color: '#7a3a2a', seed: 2 }); barrel(b, { x: 0.3, z: 0.55, color: '#4a5a3a', seed: 3, rz: 1.5, y: 0.29 }) }), max: 60, block: 0.6 },
  logs: { make: () => modelOf((b) => logPile(b, { len: 3.2, rows: 3 })), max: 40, block: 1.0 },
  reel: { make: () => modelOf((b) => cableReel(b, {})), max: 30, block: 0.5 },
  planks: { make: () => modelOf((b) => plankStack(b, { len: 2.6, w: 1.0, layers: 4 })), max: 30, block: 0.7 },
}
function modelOf(fn) {
  const b = new Builder()
  fn(b)
  return b.build()
}

// ---------------------------------------------------------------- feature generation
function generate(seed) {
  const rnd = seeded(seed)
  const F = []
  const add = (kind, x, z, o = {}) => F.push({ kind, x, z, ry: o.ry ?? rnd() * Math.PI * 2, s: o.s ?? 1, sy: o.sy ?? 1, y: o.y ?? 0, h: rnd(), zone: zoneAt(x, z), salvage: o.salvage })
  const gateLane = (x, z) => Math.abs(x - GATE_X) < 4.5 && z > 70
  const roadBand = (z) => Math.abs(z - ROAD_Z) < 6.5
  // Poisson-ish fill: jittered grid with a per-zone density
  const fill = (x0, z0, x1, z1, step, fn) => {
    for (let z = z0; z < z1; z += step) {
      for (let x = x0; x < x1; x += step) {
        const px = x + rnd() * step
        const pz = z + rnd() * step
        if (gateLane(px, pz) || roadBand(pz)) continue
        fn(px, pz)
      }
    }
  }
  const pine = (x, z, s = 1) => add(['pine0', 'pine1', 'pine2'][Math.floor(rnd() * 3)], x, z, { s: s * (0.8 + rnd() * 0.45) })
  const broad = (x, z) => add(rnd() < 0.15 ? 'broad2' : rnd() < 0.5 ? 'broad0' : 'broad1', x, z, { s: 0.85 + rnd() * 0.35 })
  const bush = (x, z) => add(['bush0', 'bush1', 'bush2'][Math.floor(rnd() * 3)], x, z, { s: 0.7 + rnd() * 0.6 })
  const camp0 = { x0: BASE.START[0] - 1.5, z0: BASE.START[0] - 1.5, x1: BASE.START[1] + 1.5, z1: BASE.START[1] + 1.5 }
  const inStart = (x, z) => x > camp0.x0 && x < camp0.x1 && z > camp0.z0 && z < camp0.z1
  // north: woods, ridge and forest
  fill(-40, -40, 150, 40, 2.6, (x, z) => {
    if (inStart(x, z)) return
    const zn = zoneAt(x, z)
    if (zn !== 'woods' && zn !== 'ridge' && zn !== 'forest') return
    const k = rnd()
    const dens = zn === 'forest' ? 0.78 : zn === 'ridge' ? 0.7 : 0.55
    if (k < dens) pine(x, z, zn === 'ridge' ? 1.05 : 1)
    else if (k < dens + 0.08) broad(x, z)
    else if (k < dens + 0.16) bush(x, z)
    else if (k < dens + 0.19) add(rnd() < 0.5 ? 'boulder0' : 'boulder1', x, z, { s: 0.8 + rnd() * 0.5 })
    else if (k < dens + 0.22) add('log', x, z)
    else if (k < dens + 0.25) add('stump', x, z)
  })
  // west: meadow, orchard, fields
  fill(-40, 40, 40, 100, 3.2, (x, z) => {
    if (inStart(x, z)) return
    const zn = zoneAt(x, z)
    const k = rnd()
    if (zn === 'meadow') {
      if (k < 0.18) add(rnd() < 0.5 ? 'flowers0' : 'flowers1', x, z)
      else if (k < 0.26) bush(x, z)
      else if (k < 0.29) broad(x, z)
      else if (k < 0.31) add('boulder1', x, z)
    } else if (zn === 'fields') {
      if (k < 0.1) add('hay', x, z)
      else if (k < 0.2) add(rnd() < 0.5 ? 'flowers0' : 'flowers1', x, z)
      else if (k < 0.24) broad(x, z)
    }
  })
  // orchard rows
  for (let z = 43; z < 70; z += 4.2) for (let x = 22; x < 29; x += 4) add(rnd() < 0.5 ? 'fruit0' : 'fruit1', x + rnd() * 0.6, z + rnd() * 0.6, { s: 0.9 + rnd() * 0.3, salvage: true })
  // hedgerows along the west fields
  for (let z = -30; z < 100; z += 1.6) {
    if (rnd() < 0.85) bush(4 + rnd(), z)
    if (rnd() < 0.7) bush(-14 + rnd(), z)
  }
  for (let x = -40; x < 18; x += 1.7) if (rnd() < 0.8) bush(x, 58 + rnd())
  // east: wrecks and containers
  for (let z = 42; z < 70; z += 5.5) {
    for (let x = 74; x < 81; x += 3.5) {
      const k = rnd()
      if (k < 0.62) add(`car${Math.floor(rnd() * 5)}`, x + rnd(), z + rnd(), { ry: (rnd() < 0.5 ? 0 : Math.PI) + (rnd() - 0.5) * 0.5 })
      else if (k < 0.75) add('tires', x, z)
      else if (k < 0.88) add('scrap', x, z)
      else add('pickup', x, z, { ry: rnd() * 6 })
    }
  }
  for (let z = 42; z < 70; z += 7) {
    for (const x of [84.5, 89]) {
      const kind = `cont${Math.floor(rnd() * 4)}`
      add(kind, x, z + 2.8, { ry: (rnd() < 0.5 ? 0 : Math.PI) + (rnd() - 0.5) * 0.08 })
      if (rnd() < 0.45) add(`cont${Math.floor(rnd() * 3)}`, x + (rnd() - 0.5) * 0.3, z + 2.8, { y: 2.6, ry: (rnd() - 0.5) * 0.1 })
    }
  }
  add('forklift', 86.8, 70, { ry: 0.5 })
  // east scrub beyond
  fill(92, 20, 150, 100, 3.4, (x, z) => {
    const k = rnd()
    if (k < 0.1) add('dead', x, z, { s: 0.8 + rnd() * 0.4 })
    else if (k < 0.3) add('bush2', x, z, { s: 0.6 + rnd() * 0.6 })
    else if (k < 0.36) add('boulder1', x, z)
    else if (k < 0.42) broad(x, z)
    else if (k < 0.45) add(`car${Math.floor(rnd() * 5)}`, x, z)
  })
  // south: loading yard, roadside lot, then the road and beyond
  for (let z = 74; z < 81; z += 3) {
    for (let x = 22; x < 91; x += 3.4) {
      if (gateLane(x, z)) continue
      const k = rnd()
      if (k < 0.18) add('pallets', x, z)
      else if (k < 0.33) add('crates', x, z)
      else if (k < 0.42) add('barrels', x, z)
      else if (k < 0.48) add('reel', x, z)
      else if (k < 0.55) add('planks', x, z)
    }
  }
  add('forklift', 34, 77, { ry: 1.2 })
  for (let z = 84; z < 91; z += 3.5) {
    for (let x = 22; x < 91; x += 4) {
      if (gateLane(x, z)) continue
      const k = rnd()
      if (k < 0.25) add(`car${Math.floor(rnd() * 5)}`, x, z, { ry: Math.PI / 2 + (rnd() - 0.5) * 0.6 })
      else if (k < 0.33) add('tires', x, z)
      else if (k < 0.4) add('barrels', x, z)
      else if (k < 0.5) bush(x, z)
    }
  }
  // the main road: abandoned traffic
  for (let x = -40; x < 150; x += 7 + rnd() * 9) {
    const lane = rnd() < 0.5 ? -1.8 : 1.8
    if (rnd() < 0.55) add(rnd() < 0.08 ? 'bus' : rnd() < 0.2 ? 'pickup' : `car${Math.floor(rnd() * 5)}`, x, ROAD_Z + lane, { ry: (lane < 0 ? Math.PI / 2 : -Math.PI / 2) + (rnd() - 0.5) * 0.7 })
  }
  fill(-40, 110, 150, 150, 3.5, (x, z) => {
    const k = rnd()
    if (k < 0.12) broad(x, z)
    else if (k < 0.3) add('bush2', x, z, { s: 0.6 + rnd() * 0.6 })
    else if (k < 0.34) add('dead', x, z)
  })
  // a few trees and bushes scattered on the edges of every zone
  fill(-40, 40, 150, 100, 6, (x, z) => {
    const zn = zoneAt(x, z)
    if (zn === 'camp' || inStart(x, z)) return
    if (zn === 'meadow' || zn === 'fields' || zn === 'scrub') if (rnd() < 0.08) broad(x, z)
  })
  return F
}

// ---------------------------------------------------------------- static scenery
function roadModel() {
  const b = new Builder()
  const len = 260
  const cx = 56
  b.box(len, 0.06, 7.4, { mat: 'asphalt', color: '#ffffff', x: cx, y: 0.0, z: ROAD_Z, ao: 0 })
  for (const sz of [-1, 1]) b.box(len, 0.07, 0.15, { mat: 'paint', color: '#d8d4c4', x: cx, y: 0.005, z: ROAD_Z + sz * 3.4, ao: 0 })
  for (let x = cx - len / 2; x < cx + len / 2; x += 6) b.box(3, 0.07, 0.14, { mat: 'paint', color: '#d8b040', x: x + 1.5, y: 0.006, z: ROAD_Z, ao: 0 })
  // power poles along the north verge with sagging lines
  const poles = []
  for (let x = cx - len / 2 + 6; x < cx + len / 2; x += 28) {
    const z = ROAD_Z - 5.2
    b.cyl(0.14, 0.18, 9, { mat: 'wood', color: '#9a8a74', x, y: 4.5, z, seg: 8 })
    b.box(2.2, 0.14, 0.14, { mat: 'wood', color: '#8a7a64', x, y: 8.4, z })
    for (const dx of [-0.9, -0.3, 0.9]) b.cyl(0.04, 0.05, 0.18, { mat: 'glass', color: '#6a8a6a', x: x + dx, y: 8.56, z })
    poles.push([x, z])
  }
  for (let i = 0; i < poles.length - 1; i++) {
    for (const dx of [-0.9, -0.3, 0.9]) b.rope([[poles[i][0] + dx, 8.62, poles[i][1]], [poles[i + 1][0] + dx, 8.62, poles[i + 1][1]]], 0.015, { mat: 'rubber', color: '#1a1a1a', sag: 0.35, steps: 12 })
  }
  // a faded billboard
  b.at({ x: 18, z: ROAD_Z + 8, ry: Math.PI }, () => {
    for (const sx of [-1, 1]) b.box(0.25, 5, 0.25, { mat: 'wood', color: '#8a7a64', x: sx * 2.6, y: 2.5 })
    b.box(6.4, 2.8, 0.15, { mat: 'wood', color: '#c8c0b0', y: 4.6 })
    sign(b, 'SUNNYVALE  ·  A GREAT PLACE TO LIVE', { y: 4.6, z: 0.08, w: 6, h: 2.4, bg: '#7a9ab0', fg: '#f8f4e8', font: '700 64px "Saira Condensed", sans-serif' })
  })
  return b.build()
}
function ruinsModel(seed) {
  const rnd = seeded(seed)
  const b = new Builder()
  const house = (x, z, ry, w, d, hh, broken) => {
    b.at({ x, z, ry }, () => {
      const col = ['#c8b8a0', '#a8b0b0', '#c8a888', '#b0a890', '#d0c8b0'][Math.floor(rnd() * 5)]
      const mat = rnd() < 0.5 ? 'siding' : 'brick'
      b.box(w, 0.4, d, { mat: 'concrete', color: '#b8b4ac', y: 0.2 })
      for (const sz of [-1, 1]) b.box(w, hh, 0.2, { mat, color: col, y: 0.4 + hh / 2 - (broken && sz > 0 ? hh * 0.3 : 0), z: (sz * (d - 0.2)) / 2, sy: broken && sz > 0 ? 0.4 : 1 })
      for (const sx of [-1, 1]) b.box(0.2, hh, d, { mat, color: col, x: (sx * (w - 0.2)) / 2, y: 0.4 + hh / 2 })
      for (let i = 0; i < Math.floor(w / 2.4); i++) windowPane(b, { x: -w / 2 + 1.4 + i * 2.4, y: 0.4 + hh * 0.55, z: -d / 2 - 0.02, w: 0.9, h: 0.9, ry: Math.PI, boards: rnd() < 0.5 })
      if (!broken) {
        const slope = 0.5
        const rl = d / 2 / Math.cos(slope) + 0.4
        for (const s of [-1, 1]) b.box(w + 0.6, 0.12, rl, { mat: 'shingles', color: '#ffffff', y: 0.4 + hh + Math.tan(slope) * d / 4, z: (s * d) / 4, rx: s * slope })
      } else {
        b.box(w * 0.6, 0.12, d * 0.5, { mat: 'shingles', color: '#ffffff', y: 0.4 + hh * 0.6, z: -d / 4, rx: -0.5, x: -w * 0.15 })
        for (let i = 0; i < 6; i++) b.box(0.3 + rnd(), 0.15, 0.2, { mat: 'wood', color: '#6a5a48', x: (rnd() - 0.5) * w, y: 0.5, z: (rnd() - 0.5) * d, ry: rnd() * 3, rz: (rnd() - 0.5) * 0.5 })
        for (let i = 0; i < 4; i++) b.box(0.4, 0.06, 2 + rnd() * 2, { mat: 'wood', color: '#5a4a3a', x: (rnd() - 0.5) * w * 0.8, y: 0.4 + hh * 0.5 + rnd(), z: (rnd() - 0.5) * d * 0.6, rx: rnd() - 0.5, rz: rnd() - 0.5, ry: rnd() * 3 })
      }
    })
  }
  for (let i = 0; i < 9; i++) house(-20 + i * 18 + rnd() * 6, ROAD_Z + 18 + rnd() * 6, Math.PI + (rnd() - 0.5) * 0.2, 9 + rnd() * 4, 7 + rnd() * 2, 3.2 + rnd() * 1.6, rnd() < 0.45)
  for (let i = 0; i < 4; i++) house(118 + rnd() * 10, 20 + i * 22, Math.PI / 2 + (rnd() - 0.5) * 0.3, 9, 7, 3.5, rnd() < 0.5)
  // water tower on the hill to the east
  b.at({ x: 130, z: -10 }, () => {
    for (let i = 0; i < 4; i++) {
      const a = (i / 4) * Math.PI * 2 + Math.PI / 4
      b.beam([Math.cos(a) * 3, 0, Math.sin(a) * 3], [Math.cos(a) * 2.2, 14, Math.sin(a) * 2.2], 0.25, 0.25, { mat: 'paint', color: '#8a8e92' })
    }
    b.cyl(3.2, 3.2, 4, { mat: 'paint', color: '#c8ccd0', y: 16, seg: 20 })
    b.cone(3.4, 1.6, { mat: 'paint', color: '#a8acb0', y: 18.8, seg: 20 })
    b.cyl(3.21, 3.21, 1.2, { mat: 'paint', color: '#7a9ab0', y: 16.5, seg: 20, ts: 0.2, tl: 2, open: true })
  })
  return b.build()
}

// ---------------------------------------------------------------- world
// Four bicycles in a rack, for the motor pool by the gate.
function bikeRack() {
  const b = new Builder()
  b.box(2.6, 0.06, 0.08, { mat: 'steel', color: '#8a8e92', y: 0.45 })
  for (const x of [-1.25, 1.25]) b.box(0.06, 0.45, 0.06, { mat: 'steel', color: '#8a8e92', x, y: 0.22 })
  for (let i = 0; i < 4; i++) {
    b.at({ x: -0.95 + i * 0.63, ry: Math.PI / 2, rz: 0.05 * (i - 1.5) }, () => {
      const col = ['#c8302a', '#2a5a9a', '#3a7a4a', '#d8a020'][i]
      for (const s of [-0.5, 0.5]) b.torus(0.33, 0.025, { mat: 'rubber', color: '#1a1a1a', y: 0.35, z: s, ry: Math.PI / 2, rs: 6, ts2: 18 })
      b.beam([0, 0.35, -0.5], [0, 0.75, 0.05], 0.04, 0.04, { mat: 'paint', color: col })
      b.beam([0, 0.35, 0.5], [0, 0.75, 0.05], 0.04, 0.04, { mat: 'paint', color: col })
      b.beam([0, 0.75, 0.05], [0, 0.85, 0.45], 0.03, 0.03, { mat: 'steel', color: '#8a8e92' })
      b.box(0.5, 0.025, 0.025, { mat: 'steel', color: '#8a8e92', y: 0.88, z: 0.45 })
      b.box(0.12, 0.05, 0.22, { mat: 'leather', color: '#1a1a1a', y: 0.82, z: -0.12 })
    })
  }
  return b.build()
}

export class BaseWorld {
  constructor(base, opts = {}) {
    this.base = base
    const scene = base.scene
    this.scene = scene
    this.splat = new Splat(-44, -44, 200, 0.5)
    this.terrain = new Terrain(scene, { cx: 56, cz: 56, size: 470, segs: 235, flat: { x0: 4, z0: 4, x1: 108, z1: 112 }, splat: this.splat })
    this.features = generate(opts.seed ?? 1234)
    this.scatters = {}
    for (const [k, K] of Object.entries(KINDS)) {
      if (!this.features.some((f) => f.kind === k)) continue
      this.scatters[k] = new Scatter(scene, K.make(), K.max, { wind: K.wind, shadow: K.shadow !== false, lod: K.lod ? K.lod() : null })
    }
    this.statics = new THREE.Group()
    this.statics.add(roadModel(), ruinsModel(77))
    this.statics.traverse((o) => {
      if (o.isMesh) {
        o.castShadow = true
        o.receiveShadow = true
      }
    })
    for (const o of this.statics.children) o.traverse((m) => m.isMesh && (m.position.y += 0))
    scene.add(this.statics)
    this.grassCount = opts.grass ?? 40000
    this.grass = null
    this.lastKey = ''
    // only what the camera (and the sun's shadow box) can reach is drawn
    this.cullView = new CullView()
    this.culled = false
  }
  // Pack the scenery in view into the instance buffers (a few times a
  // second at most: when the camera or sun has moved enough, or the scenery
  // itself changed).
  cull(camera, sun, focus) {
    const V = this.cullView
    if (this.culled && !V.moved(camera, sun, focus)) return
    this.culled = true
    V.capture(camera, sun, focus)
    for (const sc of Object.values(this.scatters)) sc.cull(V, focus)
    this.grass?.cull(V, focus)
    if (this.nearOn) this.nearGrass.cull(V, focus)
  }
  // Which features are visible: outside the fence, and not yet cleared by an expansion in progress.
  visibleFeatures() {
    const b = bounds()
    const ex = S.expanding ? EXPANSIONS.find((e) => e.id === S.expanding.id) : null
    const exRect = ex ? this.expansionLand(ex.id) : null
    const prog = S.expanding ? 1 - S.expanding.left / S.expanding.total : 0
    return this.features.filter((f) => {
      if (f.x > b.x0 - 1.6 && f.x < b.x1 + 2.6 && f.z > b.z0 - 1.6 && f.z < b.z1 + 2.6) return false
      if (exRect && f.x > exRect.x0 - 1 && f.x < exRect.x1 + 1 && f.z > exRect.z0 - 1 && f.z < exRect.z1 + 1 && f.h < prog) return false
      if (Math.abs(f.x - this.gateX()) < 4 && f.z > b.z1 && f.z < ROAD_Z - 4) return false
      return true
    })
  }
  gateX() {
    const g = gateTiles()
    return g[1] + 0.5
  }
  expansionLand(id) {
    const X = EXPANSIONS.find((e) => e.id === id)
    const b = bounds()
    const d = 10
    if (X.side === 'n') return { x0: b.x0, x1: b.x1, z0: b.z0 - d, z1: b.z0 }
    if (X.side === 's') return { x0: b.x0, x1: b.x1, z0: b.z1, z1: b.z1 + d }
    if (X.side === 'w') return { x0: b.x0 - d, x1: b.x0, z0: b.z0, z1: b.z1 }
    return { x0: b.x1, x1: b.x1 + d, z0: b.z0, z1: b.z1 }
  }
  // Rebuild instance lists and grid blocking (call when the camp's shape changes).
  refresh(force = false) {
    const prog = S.expanding ? Math.floor((1 - S.expanding.left / S.expanding.total) * 20) : -1
    const b = bounds()
    const key = `${b.x0},${b.x1},${b.z0},${b.z1}|${S.expanding?.id || ''}|${prog}`
    if (!force && key === this.lastKey) return false
    this.lastKey = key
    this.culled = false
    const vis = this.visibleFeatures()
    const by = {}
    for (const f of vis) (by[f.kind] ||= []).push(f)
    for (const [k, sc] of Object.entries(this.scatters)) sc.set((by[k] || []).map((f) => ({ x: f.x, y: f.y + this.terrain.heightAt(f.x, f.z), z: f.z, ry: f.ry, s: f.s, sy: f.sy })))
    // grid: block tiles under trunks, cars, containers
    const g = this.base.grid
    for (const t of this.blocked || []) if (g.owner[g.i(t[0], t[1])] === 'world') g.set(t[0], t[1], 0, 0, null)
    this.blocked = []
    for (const f of vis) {
      const r = KINDS[f.kind]?.block
      if (!r) continue
      const tx = Math.floor(f.x)
      const tz = Math.floor(f.z)
      const rr = Math.ceil(r - 0.5)
      for (let dx = -rr; dx <= rr; dx++) {
        for (let dz = -rr; dz <= rr; dz++) {
          const x = tx + dx
          const z = tz + dz
          if (!g.inb(x, z) || g.owner[g.i(x, z)]) continue
          if (Math.hypot(dx, dz) > r + 0.3) continue
          g.set(x, z, BLOCK, 1, 'world')
          this.blocked.push([x, z])
        }
      }
    }
    return true
  }
  // Paint the ground from the camp's current state.
  repaint() {
    const sp = this.splat
    sp.clear()
    const b = bounds()
    // forest floor under the woods
    sp.rect(-50, -50, 160, 37, 3, 0.85, 4, 2)
    sp.rect(-50, -50, 160, 22, 3, 1, 3, 2)
    // field soil to the far west, scrub to the east
    sp.rect(-50, 62, 2, 100, 0, 0.35, 3, 2)
    sp.rect(94, 20, 160, 100, 0, 0.3, 4, 2)
    // loading yard and roadside gravel
    sp.rect(20, 73, 92, 82, 1, 0.55, 1.5, 1)
    sp.rect(20, 83, 92, 92, 0, 0.45, 1.5, 1)
    // freight yard gravel, wrecking lot dirt
    sp.rect(82, 40, 93, 72, 1, 0.7, 1.5, 1)
    sp.rect(72, 40, 82, 72, 0, 0.6, 1.5, 1)
    // the camp
    sp.rect(b.x0, b.z0, b.x1 + 1, b.z1 + 1, 0, 1, 1.4, 0.8)
    // a muddy strip along the inside of the fence
    sp.paint(b.x0, b.z0, b.x1 + 1, b.z1 + 1, (x, z) => {
      const inside = Math.min(x - b.x0, b.x1 + 1 - x, z - b.z0, b.z1 + 1 - z)
      return inside < 0 ? 1 - inside : Math.abs(inside - 1.0) - 0.9
    }, 2, 0.35, 0.8, 0.5)
    // clearing in progress
    if (S.expanding) {
      const r = this.expansionLand(S.expanding.id)
      const prog = 1 - S.expanding.left / S.expanding.total
      sp.rect(r.x0, r.z0, r.x1, r.z1, 0, 0.35 + prog * 0.5, 2, 1.2)
    }
    // paths: gate → fire → every station
    const gx = this.gateX()
    const fire = S.stations.find((s) => s.type === 'campfire')
    const fc = fire ? [fire.x + 2, fire.z + 2] : [56, 56]
    sp.line([[gx, b.z1 + 12], [gx, b.z1 - 2], fc], 2.4, 2, 0.55, 1, 0.5)
    for (const st of S.stations) {
      if (st.type === 'campfire') continue
      const [w, d] = stationSize(st)
      const front = st.rot ? [st.x + w + 0.6, st.z + d / 2] : [st.x + w / 2, st.z + d + 0.6]
      sp.line([fc, front], 1.3, 2, 0.4, 0.8, 0.4)
      if (st.level >= 2) sp.rect(st.x - 0.3, st.z - 0.3, st.x + w + 0.3, st.z + d + 0.3, 1, st.level >= 3 ? 0.85 : 0.6, 0.8, 0.4)
      if (st.type === 'collector' || st.type === 'filter') sp.circle(st.x + w / 2, st.z + d / 2, Math.max(w, d) * 0.6, 2, 0.6, 1.5)
      if (st.type === 'farm') sp.rect(st.x, st.z, st.x + w, st.z + d, 2, 0.5, 0.8, 0.3)
    }
    // the dirt track to the road, with ruts
    sp.line([[gx, b.z1 + 1], [gx, ROAD_Z - 3.7]], 3.6, 0, 0.9, 1.2, 0.5)
    for (const dx of [-0.85, 0.85]) sp.line([[gx + dx, b.z1 + 1], [gx + dx, ROAD_Z - 3.7]], 0.5, 2, 0.6, 0.4, 0.15)
    sp.rect(-80, ROAD_Z - 5.5, 200, ROAD_Z + 5.5, 1, 0.75, 1.5, 1)
    // orchard and meadow tree bases
    for (const f of this.features) if (f.kind.startsWith('fruit')) sp.circle(f.x, f.z, 1.1, 0, 0.45, 0.8)
    sp.upload()
  }
  // Clutter in the strip just inside the wall, where nothing can be built:
  // what's left of the lumber yard, plus the camp's supply van by the gate.
  buildDecor() {
    if (this.decor) {
      this.scene.remove(this.decor)
      this.decor.traverse((o) => o.isMesh && o.geometry.dispose())
    }
    const b = bounds()
    const rnd = seeded(4242 + b.x0 * 3 + b.z1 * 7)
    const B = new Builder()
    this.decorLights = []
    const gx = this.gateX()
    const pts = []
    const step = 4.2
    const inset = 0.95
    for (let x = b.x0 + 3; x < b.x1 - 2; x += step) {
      pts.push({ x: x + rnd() * 1.5, z: b.z0 + 1 + inset, ry: 0 })
      if (Math.abs(x - gx) > 6.5) pts.push({ x: x + rnd() * 1.5, z: b.z1 - inset + 0.1, ry: Math.PI })
    }
    for (let z = b.z0 + 3; z < b.z1 - 2; z += step) {
      pts.push({ x: b.x0 + 1 + inset, z: z + rnd() * 1.5, ry: Math.PI / 2 })
      pts.push({ x: b.x1 - inset + 0.1, z: z + rnd() * 1.5, ry: -Math.PI / 2 })
    }
    let lamp = 0
    for (const p of pts) {
      const k = rnd()
      B.at({ x: p.x, z: p.z, ry: p.ry + (rnd() - 0.5) * 0.3 }, () => {
        if (k < 0.16) plankStack(B, { len: 2.2, w: 0.7, layers: 3 + Math.floor(rnd() * 3), seed: Math.floor(rnd() * 99), ry: Math.PI / 2 })
        else if (k < 0.27) logPile(B, { len: 2.4, rows: 2, seed: Math.floor(rnd() * 99), ry: Math.PI / 2 })
        else if (k < 0.37) {
          pallet(B, {})
          for (let i = 0; i < 2 + Math.floor(rnd() * 3); i++) crate(B, { x: -0.3 + (i % 2) * 0.6, y: 0.13 + Math.floor(i / 2) * 0.5, w: 0.55, h: 0.46, d: 0.55, ry: rnd() * 0.2 })
        } else if (k < 0.46) {
          barrel(B, { x: -0.35, color: pick(rnd, ['#3a5878', '#7a3a2a', '#4a5a3a', '#5a5a5a']), seed: Math.floor(rnd() * 99) })
          barrel(B, { x: 0.3, z: 0.1, color: pick(rnd, ['#3a5878', '#7a3a2a', '#4a5a3a']), seed: Math.floor(rnd() * 99) })
          if (rnd() < 0.5) jerrycan(B, { x: 0.75, ry: 0.4 })
        } else if (k < 0.53) tireStack(B, { n: 2 + Math.floor(rnd() * 3) })
        else if (k < 0.6) {
          firewood(B, { len: 1.6, h: 0.9, roof: true, seed: Math.floor(rnd() * 99) })
        } else if (k < 0.67) {
          for (let i = 0; i < 4; i++) pallet(B, { y: i * 0.13, ry: (rnd() - 0.5) * 0.15 })
        } else if (k < 0.72) {
          sandbags(B, { len: 2.0, rows: 2, seed: Math.floor(rnd() * 99) })
        } else if (k < 0.78 && lamp < 14) {
          lamp++
          lampPost(B, { h: 2.4, ry: 0 })
          this.decorLights.push({ x: p.x, z: p.z })
        } else if (k < 0.84) {
          B.cloth(2.0, 1.4, { mat: 'canvas', color: pick(rnd, ['#3f74b0', '#4f6e4a', '#8a7a5a']), corners: [0.7, 0.75, 0.55, 0.6], sag: -0.25, seed: Math.floor(rnd() * 99) })
        } else if (k < 0.88) wheelbarrow(B, { ry: rnd() * 3 })
        else if (k < 0.93) cinderBlocks(B, { n: 3 + Math.floor(rnd() * 4), seed: Math.floor(rnd() * 99) })
      })
    }
    const g = new THREE.Group()
    g.add(B.build())
    g.traverse((o) => {
      if (o.isMesh) {
        o.castShadow = true
        o.receiveShadow = true
      }
    })
    this.decor = g
    this.scene.add(g)
    // the van blocks its tiles
    const grid = this.base.grid
    for (let dx = -1; dx <= 1; dx++) for (let dz = -3; dz <= 2; dz++) {
      const x = Math.floor(gx - 5.2) + dx
      const z = Math.floor(b.z1 - 3.2) + dz
      if (grid.inb(x, z) && !grid.owner[grid.i(x, z)]) grid.set(x, z, BLOCK, 1, 'decor')
    }
    this.vanPos = { x: gx - 5.2, z: b.z1 - 3.2 }
    this.buildVehicles()
  }
  // The motor pool: the van just inside the gate (on blocks until it is
  // repaired), anything else parked outside by the road. Vehicles out on a
  // run are missing.
  buildVehicles() {
    if (this.vehGroup) {
      this.scene.remove(this.vehGroup)
      this.vehGroup.traverse((o) => o.isMesh && o.geometry.dispose())
    }
    const g = new THREE.Group()
    const b = bounds()
    const gx = this.gateX()
    let slot = 0
    for (const v of S.vehicles || []) {
      if (v.out) continue
      let m = null
      if (v.kind === 'van') {
        m = vanModel({ color: '#cfc8b4', broken: !!v.broken })
        m.position.set(gx - 5.2, 0, b.z1 - 3.2)
        m.rotation.y = Math.PI * 0.92
      } else {
        const x = gx + 5 + slot * 3.4
        const z = b.z1 + 4.2
        slot++
        if (v.kind === 'car') m = carModel({ seed: v.look || 3, wreck: 0, kind: 'sedan' })
        else if (v.kind === 'truck') m = vanModel({ color: '#5a6248', low: '#3a3e30' })
        else m = bikeRack()
        m.position.set(x, 0, z)
        m.rotation.y = Math.PI / 2 + 0.08 * (slot % 2 ? 1 : -1)
      }
      m.traverse((o) => {
        if (o.isMesh) {
          o.castShadow = true
          o.receiveShadow = true
        }
      })
      m.userData.pick = { type: 'vehicle', id: v.id }
      g.add(m)
    }
    this.vehGroup = g
    this.scene.add(g)
  }
  // First person: a denser patch of grass round the player's feet (the
  // field is sized for a camera forty metres up, and reads as bare at eye
  // level). It is laid again when they have walked a dozen metres on.
  setNearGrass(on, x = 0, z = 0) {
    if (!on || !this.grassCount) {
      if (this.nearGrass && this.nearOn) this.nearGrass.setVisible(false)
      this.nearOn = false
      return
    }
    const R = 26
    if (!this.nearGrass) {
      const b = bounds()
      this.nearGrass = new GrassField(this.scene, this.splat, {
        count: 0,
        seed: 91,
        heightFn: (gx, gz) => this.terrain.heightAt(gx, gz),
        avoid: (gx, gz) => (gx > b.x0 - 0.5 && gx < b.x1 + 1.5 && gz > b.z0 - 0.5 && gz < b.z1 + 1.5) || Math.abs(gz - ROAD_Z) < 3.9,
        cap: 16000,
      })
      this.nearAt = null
    }
    const N = this.nearGrass
    if (!this.nearAt || Math.hypot(x - this.nearAt.x, z - this.nearAt.z) > 11) {
      this.nearAt = { x, z }
      const b = bounds()
      N.avoid = (gx, gz) => (gx > b.x0 - 0.5 && gx < b.x1 + 1.5 && gz > b.z0 - 0.5 && gz < b.z1 + 1.5) || Math.abs(gz - ROAD_Z) < 3.9
      N.area = { x0: x - R, z0: z - R, x1: x + R, z1: z + R }
      N.seed = 91 + Math.floor(x) * 7 + Math.floor(z) * 13
      N.rebuild()
      this.culled = false
    }
    if (!this.nearOn) {
      N.setVisible(true)
      this.nearOn = true
      this.culled = false
    }
  }
  rebuildGrass(count = this.grassCount) {
    if (this.grass) this.grass.dispose()
    this.grass = null
    this.culled = false
    if (count <= 0) return
    const b = bounds()
    this.grass = new GrassField(this.scene, this.splat, {
      count,
      area: { x0: -20, z0: -10, x1: 135, z1: 130 },
      heightFn: (x, z) => this.terrain.heightAt(x, z),
      avoid: (x, z) => (x > b.x0 - 0.5 && x < b.x1 + 1.5 && z > b.z0 - 0.5 && z < b.z1 + 1.5) || Math.abs(z - ROAD_Z) < 3.9,
    })
  }
}
