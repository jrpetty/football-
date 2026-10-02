// Stockpiles you can see. Every store (the Storage Depot, crate stacks,
// sheds, the warehouse) has pallet spots; each spot shows one kind of goods,
// and the pile on it grows in four steps as the camp's stock of those goods
// rises toward what the stores can hold. With more stores, more kinds get a
// pile of their own, so a glance across the yard reads the camp's wealth.
import { Builder, seeded } from '../models/kit.js'
import { crate, barrel, sack, ammoCrate, log, shadeHex } from '../models/parts.js'
import { S, capOf } from '../game/state.js'
import { RES, STATIONS } from '../game/data.js'
import { stationInfo } from '../models/stations.js'

const TAU = Math.PI * 2
// Goods grouped by how they're piled, most visible first.
export const STOCK_GROUPS = [
  { id: 'wood', name: 'Wood', res: ['wood'], pile: 'logs' },
  { id: 'scrap', name: 'Scrap', res: ['scrap'], pile: 'scrap' },
  { id: 'food', name: 'Food', res: ['food'], pile: 'sacks' },
  { id: 'water', name: 'Water', res: ['water'], pile: 'water' },
  { id: 'metal', name: 'Metal', res: ['metal', 'steel', 'plates', 'beams'], pile: 'bars' },
  { id: 'fuel', name: 'Fuel and chemicals', res: ['fuel', 'chemicals'], pile: 'drums' },
  { id: 'coal', name: 'Coal', res: ['coal'], pile: 'coal' },
  { id: 'cloth', name: 'Cloth', res: ['cloth', 'wool'], pile: 'cloth' },
  { id: 'parts', name: 'Parts and components', res: ['parts', 'bolts', 'electronics', 'wiring', 'rubber', 'circuits', 'motors'], pile: 'crates' },
  { id: 'ammo', name: 'Ammunition', res: ['pammo', 'rammo', 'shells', 'gunpowder'], pile: 'ammo' },
  { id: 'meds', name: 'Medicine', res: ['meds', 'medkit', 'antiviral'], pile: 'meds' },
]
// How full a group is, 0..1, against what the stores can hold.
export function groupFill(g) {
  let have = 0
  let cap = 0
  for (const k of g.res) {
    if (!RES[k]) continue
    have += S.res[k] || 0
    cap += capOf(k)
  }
  return cap > 0 ? Math.min(1, have / cap) : 0
}
// 0 = bare pallet, 1..4 = a pile that grows.
export const fillStep = (f) => (f < 0.03 ? 0 : Math.min(4, Math.ceil(f * 4)))

// ---------------------------------------------------------------- piles
const cache = new Map()
// A pile of one kind, footprint w x d, built up from y = 0. Cached; callers clone.
export function pileModel(kind, w, d, step) {
  const key = `${kind}|${w.toFixed(2)}|${d.toFixed(2)}|${step}`
  let g = cache.get(key)
  if (g) return g
  const b = new Builder()
  const rnd = seeded(Math.floor(w * 131 + d * 71 + step * 17) + kind.length * 7)
  const long = w >= d
  const L = Math.max(w, d)
  const S0 = Math.min(w, d)
  const along = (fn) => b.at({ ry: long ? 0 : Math.PI / 2 }, fn)
  switch (kind) {
    case 'logs':
      along(() => {
        const r = Math.min(0.11, S0 / 7)
        for (let row = 0; row < step; row++) {
          const n = Math.max(1, Math.floor(S0 / (r * 2.05)) - row)
          for (let i = 0; i < n; i++) log(b, { x: (rnd() - 0.5) * 0.08, y: r + row * r * 1.72, z: (i - (n - 1) / 2) * r * 2.05, len: L * (0.86 + rnd() * 0.1), r: r * (0.85 + rnd() * 0.3) })
        }
      })
      break
    case 'scrap': {
      const R = (S0 / 2) * 0.95
      const H = 0.12 + step * 0.13
      b.sphere(R, { mat: 'dirt', color: '#7a6e60', sy: H / R, y: 0, ws: 12, hs: 6, tl: Math.PI / 2 })
      for (let i = 0; i < 3 + step * 4; i++) {
        const a = rnd() * TAU
        const dd = Math.sqrt(rnd()) * R * 0.75
        const y = Math.max(0.03, (1 - (dd / R) ** 2) * H)
        const k = rnd()
        const c = { x: Math.cos(a) * dd, y, z: Math.sin(a) * dd, rx: (rnd() - 0.5) * 1.2, ry: rnd() * TAU, rz: (rnd() - 0.5) * 1.2 }
        if (k < 0.35) b.box(0.24 + rnd() * 0.24, 0.012, 0.18 + rnd() * 0.2, { mat: 'corrugated', color: pick(rnd, ['#b8b0a8', '#a89a8a', '#8aa0a8']), ...c })
        else if (k < 0.6) b.cyl(0.02, 0.02, 0.3 + rnd() * 0.4, { mat: 'rust', color: '#a89080', ...c, seg: 6 })
        else if (k < 0.8) b.box(0.12 + rnd() * 0.12, 0.08 + rnd() * 0.1, 0.1 + rnd() * 0.12, { mat: 'metal', color: pick(rnd, ['#5a6a78', '#8a4a3a', '#6a7a5a', '#9a9a90']), ...c })
        else b.torus(0.08 + rnd() * 0.05, 0.015, { mat: 'rust', color: '#a89080', ...c, arc: Math.PI * (1 + rnd()) })
      }
      break
    }
    case 'sacks':
      along(() => {
        const sw = Math.min(0.36, S0 / 2.1)
        const sd = Math.min(0.58, L / 1.15)
        const nx = Math.max(1, Math.floor(S0 / (sw + 0.03)))
        const nz = Math.max(1, Math.floor(L / (sd + 0.04)))
        let placed = 0
        const want = [0, 2, 4, 7, 10][step]
        for (let layer = 0; layer < 3 && placed < want; layer++)
          for (let i = 0; i < nx * nz && placed < want; i++, placed++) {
            const x = ((i % nz) - (nz - 1) / 2) * (sd + 0.04)
            const z = (Math.floor(i / nz) - (nx - 1) / 2) * (sw + 0.03)
            sack(b, { x, z, y: layer * 0.19, w: sw, d: sd, h: 0.2, ry: Math.PI / 2 + (rnd() - 0.5) * 0.15, color: pick(rnd, ['#d8c8a0', '#c8b48c', '#e0d0a8', '#b8a47c']), print: rnd() < 0.3 ? '#a8402a' : null })
          }
      })
      break
    case 'water':
    case 'drums': {
      const r = Math.min(0.2, S0 / 4.4)
      const n = Math.max(1, Math.floor(S0 / (r * 2.15)))
      const m = Math.max(1, Math.floor(L / (r * 2.15)))
      const want = Math.ceil((n * m * step) / 4)
      along(() => {
        for (let i = 0; i < want; i++) {
          const x = ((i % m) - (m - 1) / 2) * r * 2.15
          const z = (Math.floor(i / m) - (n - 1) / 2) * r * 2.15
          if (kind === 'water') {
            // blue water drums and jugs
            if (rnd() < 0.7) barrel(b, { x, z, r, h: r * 3, color: pick(rnd, ['#2a5a8a', '#3a6a9a', '#2a4a7a']), rust: 0 })
            else for (let j = 0; j < 4; j++) b.cyl(r * 0.42, r * 0.42, r * 1.6, { mat: 'plastic', color: '#a8c8e0', x: x + ((j % 2) - 0.5) * r, z: z + (Math.floor(j / 2) - 0.5) * r, y: r * 0.8, seg: 10 })
          } else barrel(b, { x, z, r, h: r * 3, color: pick(rnd, ['#a8382a', '#c84a2a', '#5a6a3a', '#d8a020']), rust: 0.6, label: rnd() < 0.4 ? '#e8e4d8' : null })
        }
      })
      break
    }
    case 'bars':
      along(() => {
        // ingots, then plates, stacked crosswise in layers
        for (let layer = 0; layer < step * 2; layer++) {
          const cross = layer % 2
          const n = 5
          for (let i = 0; i < n; i++) {
            const t = (i - (n - 1) / 2) / n
            if (cross) b.box(L * 0.8, 0.06, S0 / (n + 1), { mat: 'metal', color: shadeHex('#a3adb6', (rnd() - 0.5) * 0.15), z: t * S0 * 0.95, y: 0.03 + layer * 0.065 })
            else b.box(L / (n + 1), 0.06, S0 * 0.8, { mat: 'steel', color: shadeHex('#8a96a2', (rnd() - 0.5) * 0.15), x: t * L * 0.95, y: 0.03 + layer * 0.065 })
          }
        }
        b.box(0.02, step * 0.13, S0 * 0.82, { mat: 'paint', color: '#2a5aa8', x: L * 0.42, y: (step * 0.13) / 2 })
      })
      break
    case 'coal': {
      const R = (S0 / 2) * 0.95
      const H = 0.1 + step * 0.12
      b.sphere(R, { mat: 'plain', color: '#2a2826', sy: H / R, ws: 12, hs: 6, tl: Math.PI / 2 })
      for (let i = 0; i < 6 + step * 5; i++) {
        const a = rnd() * TAU
        const dd = Math.sqrt(rnd()) * R * 0.8
        b.dodeca(0.04 + rnd() * 0.05, { mat: 'gloss', color: pick(rnd, ['#1a1a1a', '#2a2826', '#3a3632']), x: Math.cos(a) * dd, y: Math.max(0.02, (1 - (dd / R) ** 2) * H), z: Math.sin(a) * dd, rx: rnd() * 3, ry: rnd() * 3 })
      }
      break
    }
    case 'cloth':
      along(() => {
        const n = Math.max(1, Math.floor(L / 0.26))
        for (let layer = 0; layer < step; layer++)
          for (let i = 0; i < n; i++) b.cyl(0.11, 0.11, S0 * 0.86, { mat: 'cloth', color: pick(rnd, ['#cdb892', '#8a6a5a', '#5a6a8a', '#a8a090', '#7a8a5a', '#c87a5a']), x: (i - (n - 1) / 2) * 0.24 + (layer % 2) * 0.12, y: 0.11 + layer * 0.2, rx: Math.PI / 2, seg: 12 })
      })
      break
    case 'crates':
    case 'meds': {
      const cw = Math.min(0.5, S0 / 2.05)
      const nx = Math.max(1, Math.floor(L / (cw + 0.04)))
      const nz = Math.max(1, Math.floor(S0 / (cw + 0.04)))
      const per = nx * nz
      const want = Math.ceil((per * 2 * step) / 4)
      along(() => {
        for (let i = 0; i < want; i++) {
          const layer = Math.floor(i / per)
          const j = i % per
          const x = ((j % nx) - (nx - 1) / 2) * (cw + 0.04)
          const z = (Math.floor(j / nx) - (nz - 1) / 2) * (cw + 0.04)
          const h = cw * 0.8
          if (kind === 'meds') {
            b.box(cw, h, cw, { mat: 'plastic', color: '#e8e8e4', x, z, y: layer * h + h / 2, r: 0.02 })
            b.box(cw * 0.5, 0.012, cw * 0.14, { mat: 'paint', color: '#d82a2a', x, z, y: layer * h + h + 0.006 })
            b.box(cw * 0.14, 0.012, cw * 0.5, { mat: 'paint', color: '#d82a2a', x, z, y: layer * h + h + 0.006 })
          } else crate(b, { x, z, y: layer * h, w: cw, h, d: cw, ry: (rnd() - 0.5) * 0.12, color: pick(rnd, ['#e8d8be', '#d0c0a4', '#c8b48c']), stencil: rnd() < 0.4 ? '#3a3a3a' : null })
        }
      })
      break
    }
    case 'ammo': {
      const nz = Math.max(1, Math.floor(S0 / 0.36))
      const nx = Math.max(1, Math.floor(L / 0.66))
      const want = Math.ceil((nx * nz * 3 * step) / 4)
      along(() => {
        for (let i = 0; i < want; i++) {
          const layer = Math.floor(i / (nx * nz))
          const j = i % (nx * nz)
          ammoCrate(b, { x: ((j % nx) - (nx - 1) / 2) * 0.66, z: (Math.floor(j / nx) - (nz - 1) / 2) * 0.36, y: layer * 0.31, color: pick(rnd, ['#5a6440', '#4a5436', '#6a6a48']) })
        }
      })
      break
    }
  }
  g = b.build()
  g.traverse((o) => {
    if (o.isMesh) {
      o.castShadow = true
      o.receiveShadow = true
    }
  })
  cache.set(key, g)
  return g
}
const pick = (r, a) => a[Math.floor(r() * a.length)]

// ---------------------------------------------------------------- per store
// How many stock pallets a store has.
export const slotsOf = (o) => (STATIONS[o.type]?.depot && o.level > 0 ? stationInfo(o.type, o.level).stock?.length || 0 : 0)
// Which group each of a store's spots shows: spots are numbered across every
// store in the camp (in build order), so the main depot shows wood, scrap,
// food and water, and each new store takes the next kinds of goods.
export function slotGroups(st) {
  let offset = 0
  for (const o of S.stations) {
    if (o === st) break
    if (o.level > 0) offset += slotsOf(o)
  }
  const n = slotsOf(st)
  const out = []
  for (let j = 0; j < n; j++) out.push(STOCK_GROUPS[(offset + j) % STOCK_GROUPS.length])
  return out
}
// Keep a store's piles in step with the stock (called about once a second).
export function syncPiles(view, slots, groups) {
  const model = view.model
  if (!model) return
  view.piles ??= []
  for (let j = 0; j < slots.length; j++) {
    const sl = slots[j]
    const g = groups[j]
    const step = fillStep(groupFill(g))
    const key = `${g.pile}|${step}`
    const cur = view.piles[j]
    if (cur && cur.key === key && cur.parent === model) continue
    if (cur?.mesh) cur.mesh.parent?.remove(cur.mesh)
    let mesh = null
    if (step > 0) {
      mesh = pileModel(g.pile, sl.w, sl.d, step).clone()
      mesh.position.set(sl.x, sl.y || 0, sl.z)
      mesh.rotation.y = sl.ry || 0
      mesh.traverse((o) => {
        if (o.isMesh) o.userData.pick = { type: 'station', st: view.st }
      })
      model.add(mesh)
    }
    view.piles[j] = { key, mesh, parent: model, group: g, step }
  }
}
