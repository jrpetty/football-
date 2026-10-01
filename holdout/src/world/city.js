// City layout: a street grid cut by a river and a rail line, districts,
// parcels with buildings, and the supply-run locations. Pure data, seeded so
// a camp always sees the same city. The 3D city map draws it, and a supply
// run builds the chosen location together with its real street and
// neighbours from the same data.
import { LOCATIONS } from '../game/data.js'
import { seeded } from '../models/kit.js'

export const SIDEWALK = 3.6
export const RIVER_W = 56
export const HIGHWAY_Z = 18
export const HIGHWAY_W = 26
export const FACE_ROT = { s: 0, n: Math.PI, e: Math.PI / 2, w: -Math.PI / 2 }

// How many of each location the city holds.
const COUNTS = { house: 4, apartment: 3, store: 3, office: 2, diner: 2, gas: 2, hardware: 2, pharmacy: 2, supermarket: 2, garage: 2, school: 1, hospital: 1, firestation: 1, gunstore: 2, warehouse: 2, police: 1 }
// Districts each location type belongs in, best first.
const PREF = {
  house: ['residential'],
  apartment: ['residential', 'commercial'],
  store: ['residential', 'commercial'],
  office: ['downtown', 'commercial'],
  diner: ['commercial', 'residential'],
  gas: ['commercial', 'industrial'],
  hardware: ['commercial', 'industrial'],
  pharmacy: ['commercial', 'downtown'],
  supermarket: ['commercial', 'residential'],
  garage: ['industrial', 'commercial'],
  school: ['residential', 'commercial'],
  hospital: ['downtown', 'commercial'],
  firestation: ['commercial', 'downtown', 'residential'],
  gunstore: ['commercial', 'industrial'],
  warehouse: ['industrial'],
  police: ['downtown', 'commercial'],
}
// Yard kinds: extra frontage, front setback (to the sidewalk), rear margin,
// and an optional side yard.
export const SITE = {
  house: { fx: 5, front: 7, back: 7 },
  street: { fx: 2, front: 1.2, back: 3 },
  lot: { fx: 16, front: 3, back: 4, side: 13 },
  forecourt: { fx: 8, front: 19, back: 4 },
  parking: { fx: 10, front: 20, back: 3 },
  loading: { fx: 22, front: 7, back: 5, side: 19 },
  checkpoint: { fx: 40, front: 20, back: 20 },
}
const CHURCH_NAMES = ['St. Jude', 'Grace Chapel', 'First Baptist', 'Holy Trinity']

export function genCity(seed) {
  const R = seeded(((seed >>> 0) ^ 0x5bd1e995) + 17)
  const rr = (a, b) => a + R() * (b - a)
  const ri = (a, b) => Math.floor(a + R() * (b - a + 1))
  const chance = (p) => R() < p
  const pickR = (a) => a[Math.floor(R() * a.length)]

  // ---------------------------------------------------------------- grid
  const xs = [40]
  for (;;) {
    const n = xs[xs.length - 1] + rr(132, 166)
    if (n > 1460) break
    xs.push(n)
  }
  const NX = xs.length
  const jr = 3 // the river runs through this row of blocks
  const zs = [72]
  for (;;) {
    const j = zs.length - 1
    const n = zs[j] + (j === jr ? rr(214, 228) : rr(106, 128))
    if (n > 1070) break
    zs.push(n)
  }
  const NZ = zs.length
  const W = xs[NX - 1] + 40
  const H = zs[NZ - 1] + 40
  const avX = (i) => i % 3 === 1
  const avZ = (j) => j % 3 === 1
  const iR = NX - 2 // the rail line replaces this north-south street
  const vW = (i) => (avX(i) ? 20 : 13)
  const hW = (j) => (avZ(j) ? 20 : 13)

  // ---------------------------------------------------------------- river
  const zc = (zs[jr] + zs[jr + 1]) / 2
  const rowH = zs[jr + 1] - zs[jr]
  const aIn = Math.max(8, (rowH - RIVER_W - 2 * 50) / 2)
  const p1 = R() * 6.28
  const p2 = R() * 6.28
  const riverZ = (x) => {
    const out = Math.max(0, xs[0] - 30 - x, x - (W + 10))
    const k = Math.min(1, out / 260)
    const a = aIn + (110 - aIn) * k * k * (3 - 2 * k)
    return zc + a * (0.72 * Math.sin(x * 0.0058 + p1) + 0.28 * Math.sin(x * 0.0161 + p2))
  }
  const river = { w: RIVER_W, z: riverZ, zc, x0: -900, x1: W + 900 }
  const inRiver = (x, z, pad = 0) => Math.abs(z - riverZ(x)) < RIVER_W / 2 + pad

  // ---------------------------------------------------------------- rail
  const railHalf = 9
  const yardJ0 = Math.min(NZ - 2, jr + 2)
  const yardJ1 = Math.min(NZ - 2, jr + 3)
  const yard = { z0: zs[yardJ0] + 12, z1: zs[yardJ1 + 1] - 12, half: 30 }
  const railHalfAt = (z0, z1) => (z1 > yard.z0 && z0 < yard.z1 ? yard.half : railHalf)
  const rail = { x: xs[iR], half: railHalf, yard, z0: -900, z1: H + 900 }

  // ---------------------------------------------------------------- edges
  const hE = []
  for (let j = 0; j < NZ; j++) {
    hE.push([])
    for (let i = 0; i < NX - 1; i++) hE[j].push({ dir: 'h', i, j, on: true, w: hW(j), kind: avZ(j) ? 'avenue' : 'street', x0: xs[i], x1: xs[i + 1], z: zs[j] })
  }
  const vE = []
  for (let i = 0; i < NX; i++) {
    vE.push([])
    for (let j = 0; j < NZ - 1; j++) vE[i].push({ dir: 'v', i, j, on: i !== iR, w: vW(i), kind: avX(i) ? 'avenue' : 'street', x: xs[i], z0: zs[j], z1: zs[j + 1] })
  }
  // river crossings: avenues get bridges, streets end at the bank
  const bridges = []
  for (let i = 0; i < NX; i++) {
    const e = vE[i][jr]
    const rz = riverZ(xs[i])
    const n0 = rz - RIVER_W / 2 - 7
    const s0 = rz + RIVER_W / 2 + 7
    if (i === iR) {
      bridges.push({ kind: 'rail', x: xs[i], z0: n0 - 8, z1: s0 + 8, w: 14 })
      continue
    }
    if (avX(i)) {
      e.bridge = { z0: n0, z1: s0 }
      bridges.push({ kind: 'road', x: xs[i], z0: n0, z1: s0, w: e.w + 2 * SIDEWALK })
    } else {
      e.on = false
      e.stubs = [
        [zs[jr], n0 + 2],
        [s0 - 2, zs[jr + 1]],
      ]
    }
  }
  const camp = { x: xs[1] - 64, z: H + 110, gate: null }
  camp.gate = { x: camp.x + 36, z: camp.z }
  const maxD = Math.hypot(W - camp.x, camp.z)

  // ---------------------------------------------------------------- superblocks
  const merged = new Map() // cell key -> block id
  const key = (i, j) => i + ',' + j
  const merges = []
  const cellFree = (i, j) => i >= 0 && j >= 0 && i < NX - 1 && j < NZ - 1 && j !== jr && i !== iR - 1 && i !== iR && !merged.has(key(i, j))
  const tryMerge = (i, j, horiz) => {
    const i2 = horiz ? i + 1 : i
    const j2 = horiz ? j : j + 1
    if (!cellFree(i, j) || !cellFree(i2, j2)) return false
    if (horiz && avX(i + 1)) return false
    if (!horiz && avZ(j + 1)) return false
    const id = merges.length
    merged.set(key(i, j), id)
    merged.set(key(i2, j2), id)
    merges.push({ i0: i, j0: j, i1: i2, j1: j2 })
    if (horiz) vE[i + 1][j].on = false
    else hE[j + 1][i].on = false
    return true
  }
  // the big park sits between camp and downtown
  for (let t = 0; t < 40; t++) if (tryMerge(ri(2, NX - 5), ri(jr + 1, NZ - 3), true)) break
  const parkMerge = merges.length ? 0 : -1
  for (let t = 0; t < 60 && merges.length < 5; t++) tryMerge(ri(0, NX - 3), ri(0, NZ - 3), chance(0.5))

  // ---------------------------------------------------------------- blocks
  const DT = { x: xs[Math.min(NX - 2, Math.round(NX * 0.6))], z: (zs[0] + zs[jr]) / 2 + 20 }
  const blocks = []
  const sideRoad = (blk) => {
    // which sides have a road, and how wide it is
    const s = { n: 0, s: 0, w: 0, e: 0, nRail: false }
    for (let i = blk.i0; i <= blk.i1; i++) {
      const en = hE[blk.j0][i]
      const es = hE[blk.j1 + 1][i]
      if (en.on) s.n = Math.max(s.n, en.w)
      if (es.on) s.s = Math.max(s.s, es.w)
    }
    for (let j = blk.j0; j <= blk.j1; j++) {
      const ew = vE[blk.i0][j]
      const ee = vE[blk.i1 + 1][j]
      if (ew.on || ew.stubs) s.w = Math.max(s.w, ew.w)
      if (ee.on || ee.stubs) s.e = Math.max(s.e, ee.w)
    }
    return s
  }
  for (let j = 0; j < NZ - 1; j++) {
    for (let i = 0; i < NX - 1; i++) {
      const m = merged.get(key(i, j))
      if (m != null) {
        const M = merges[m]
        if (M.i0 !== i || M.j0 !== j) continue
        blocks.push({ i0: M.i0, j0: M.j0, i1: M.i1, j1: M.j1, park: m === parkMerge })
      } else blocks.push({ i0: i, j0: j, i1: i, j1: j })
    }
  }
  for (const blk of blocks) {
    blk.cx0 = xs[blk.i0]
    blk.cx1 = xs[blk.i1 + 1]
    blk.cz0 = zs[blk.j0]
    blk.cz1 = zs[blk.j1 + 1]
    const s = sideRoad(blk)
    blk.roads = s
    // road edge -> sidewalk -> parcels
    let x0 = blk.cx0 + (blk.i0 === iR ? railHalfAt(blk.cz0, blk.cz1) + 4 : s.w / 2)
    let x1 = blk.cx1 - (blk.i1 + 1 === iR ? railHalfAt(blk.cz0, blk.cz1) + 4 : s.e / 2)
    let z0 = blk.cz0 + s.n / 2
    let z1 = blk.cz1 - s.s / 2
    blk.ox0 = x0
    blk.ox1 = x1
    blk.oz0 = z0
    blk.oz1 = z1
    blk.walk = { n: s.n > 0, s: s.s > 0, w: s.w > 0 && blk.i0 !== iR, e: s.e > 0 && blk.i1 + 1 !== iR }
    if (blk.walk.w) x0 += SIDEWALK
    if (blk.walk.e) x1 -= SIDEWALK
    if (blk.walk.n) z0 += SIDEWALK
    if (blk.walk.s) z1 -= SIDEWALK
    blk.x0 = x0
    blk.x1 = x1
    blk.z0 = z0
    blk.z1 = z1
    blk.riverRow = blk.j0 === jr
  }
  // river-row blocks split into a north and a south strip along the banks
  const all = []
  for (const blk of blocks) {
    if (!blk.riverRow) {
      all.push(blk)
      continue
    }
    let northEdge = Infinity
    let southEdge = -Infinity
    for (let x = blk.ox0; x <= blk.ox1; x += 4) {
      northEdge = Math.min(northEdge, riverZ(x) - RIVER_W / 2 - 14)
      southEdge = Math.max(southEdge, riverZ(x) + RIVER_W / 2 + 14)
    }
    const N = { ...blk, z1: northEdge, oz1: northEdge, walk: { ...blk.walk, s: false }, bank: 's' }
    const S2 = { ...blk, z0: southEdge, oz0: southEdge, walk: { ...blk.walk, n: false }, bank: 'n' }
    if (N.z1 - N.z0 > 16) all.push(N)
    if (S2.z1 - S2.z0 > 16) all.push(S2)
  }
  // districts
  for (const blk of all) {
    const cx = (blk.x0 + blk.x1) / 2
    const cz = (blk.z0 + blk.z1) / 2
    blk.cx = cx
    blk.cz = cz
    blk.dCamp = Math.hypot(cx - camp.x, cz - camp.z) / maxD
    const dd = Math.hypot((cx - DT.x) * 0.85, cz - DT.z)
    let d
    if (blk.park) d = 'park'
    else if (blk.i0 === iR - 1 || blk.i0 === iR) d = 'industrial'
    else if (blk.riverRow) d = cx > W * 0.52 ? 'industrial' : blk.z1 - blk.z0 < 22 ? 'park' : dd < 300 ? 'commercial' : 'residential'
    else if (dd < 235) d = 'downtown'
    else if (dd < 390) d = chance(0.8) ? 'commercial' : 'residential'
    else if (cx > W * 0.8) d = 'industrial'
    else if (blk.dCamp < 0.52) d = chance(0.86) ? 'residential' : 'commercial'
    else d = chance(0.5) ? 'commercial' : 'residential'
    blk.district = d
  }
  // a few neighbourhood parks
  const parkable = all.filter((b) => (b.district === 'residential' || b.district === 'commercial') && !b.riverRow)
  for (let k = 0; k < 1 && parkable.length; k++) {
    const b = parkable.splice(Math.floor(R() * parkable.length), 1)[0]
    b.district = 'park'
  }

  // ---------------------------------------------------------------- lots
  const lots = []
  let lotId = 0
  const mkLot = (blk, x0, z0, x1, z1, face, extra = {}) => {
    const l = { id: lotId++, block: blk, x0, z0, x1, z1, face, district: blk.district, seed: Math.floor(R() * 1e9), ...extra }
    l.cx = (x0 + x1) / 2
    l.cz = (z0 + z1) / 2
    l.fw = face === 'n' || face === 's' ? x1 - x0 : z1 - z0 // frontage
    l.fd = face === 'n' || face === 's' ? z1 - z0 : x1 - x0 // depth
    lots.push(l)
    return l
  }
  // rows of parcels facing the north and south streets
  const rowsOf = (blk, fmin, fmax) => {
    const depth = blk.z1 - blk.z0
    const rows = []
    const faceN = blk.walk.n
    const faceS = blk.walk.s
    if (depth >= 54 && faceN && faceS) {
      const mid = blk.z0 + depth * rr(0.4, 0.6)
      rows.push({ face: 'n', z0: blk.z0, z1: mid })
      rows.push({ face: 's', z0: mid, z1: blk.z1 })
    } else rows.push({ face: faceS || !faceN ? 's' : 'n', z0: blk.z0, z1: blk.z1 })
    for (const row of rows) {
      row.lots = []
      let x = blk.x0
      while (blk.x1 - x > fmin * 0.75) {
        let w = rr(fmin, fmax)
        if (blk.x1 - x - w < fmin * 0.75) w = blk.x1 - x
        row.lots.push(mkLot(blk, x, row.z0, x + w, row.z1, row.face, { row }))
        x += w
      }
      row.lots.forEach((l, k) => {
        l.rowIndex = k
        l.corner = k === 0 ? 'w' : k === row.lots.length - 1 ? 'e' : null
      })
    }
    blk.rows = rows
  }
  for (const blk of all) {
    const d = blk.district
    if (d === 'residential') rowsOf(blk, 17, 22)
    else if (d === 'commercial') rowsOf(blk, 15, 30)
    else if (d === 'downtown') {
      // a dense grid of parcels: two rows deep, two to four across
      const w = blk.x1 - blk.x0
      const dep = blk.z1 - blk.z0
      const cols = Math.max(1, Math.min(4, Math.round(w / 38)))
      const sz = dep > 56 ? blk.z0 + dep * rr(0.44, 0.56) : blk.z1
      const parts = []
      const cuts = [blk.x0]
      for (let k = 1; k < cols; k++) cuts.push(blk.x0 + (w * k) / cols + rr(-4, 4))
      cuts.push(blk.x1)
      for (let k = 0; k < cols; k++) {
        parts.push([cuts[k], blk.z0, cuts[k + 1], sz, 'n'])
        if (sz < blk.z1) parts.push([cuts[k], sz, cuts[k + 1], blk.z1, 's'])
      }
      blk.rows = [{ face: 'n', lots: [] }]
      for (const p of parts.filter((p) => p[2] - p[0] > 8 && p[3] - p[1] > 8)) {
        const face = p[4] === 'n' && !blk.walk.n ? 's' : p[4] === 's' && !blk.walk.s ? 'n' : p[4]
        blk.rows[0].lots.push(mkLot(blk, p[0], p[1], p[2], p[3], face, { big: true }))
      }
    } else if (d === 'industrial') {
      const w = blk.x1 - blk.x0
      const n = w > 110 ? 2 : 1
      blk.rows = [{ face: 's', lots: [] }]
      const dep = blk.z1 - blk.z0
      const split = dep > 80 && chance(0.6)
      for (let k = 0; k < n; k++) {
        const a = blk.x0 + (w * k) / n
        const b = blk.x0 + (w * (k + 1)) / n
        if (split) {
          const mz = blk.z0 + dep * rr(0.42, 0.58)
          if (blk.walk.n) blk.rows[0].lots.push(mkLot(blk, a, blk.z0, b, mz, 'n', { big: true }))
          blk.rows[0].lots.push(mkLot(blk, a, mz, b, blk.z1, blk.walk.s ? 's' : 'n', { big: true }))
        } else blk.rows[0].lots.push(mkLot(blk, a, blk.z0, b, blk.z1, blk.walk.s ? 's' : 'n', { big: true }))
      }
    } else {
      blk.rows = [{ face: 's', lots: [mkLot(blk, blk.x0, blk.z0, blk.x1, blk.z1, 's', { big: true })] }]
    }
  }

  // ---------------------------------------------------------------- locations
  const locs = []
  const types = []
  for (const [t, n] of Object.entries(COUNTS)) for (let k = 0; k < n; k++) types.push(t)
  // biggest sites first so they get the space they need
  const need = (t) => {
    const L = LOCATIONS[t]
    const S = SITE[L.yard] || SITE.lot
    return { fw: L.size[0] + S.fx, fd: L.size[1] + S.front + S.back }
  }
  types.sort((a, b) => need(b).fw * need(b).fd - need(a).fw * need(a).fd)
  const target = (lvl) => 0.1 + (lvl - 1) * 0.19
  const usedNames = {}
  for (const t of types) {
    const L = LOCATIONS[t]
    const nd = need(t)
    const pref = PREF[t]
    let best = null
    for (const blk of all) {
      const pi = pref.indexOf(blk.district)
      if (pi < 0) continue
      for (const row of blk.rows || []) {
        const rl = row.lots
        for (let a = 0; a < rl.length; a++) {
          if (rl[a].loc || rl[a].taken) continue
          let fw = 0
          let b = a
          for (; b < rl.length; b++) {
            if (rl[b].loc || rl[b].taken || rl[b].face !== rl[a].face) break
            fw += rl[b].fw
            if (fw >= nd.fw) break
          }
          if (fw < nd.fw || b >= rl.length) continue
          const fd = Math.min(...rl.slice(a, b + 1).map((l) => l.fd))
          if (fd < nd.fd) continue
          const cx = (rl[a].x0 + rl[b].x1) / 2
          const cz = (rl[a].z0 + rl[b].z1) / 2
          if (locs.some((o) => Math.hypot(o.x - cx, o.z - cz) < 70)) continue
          const dc = Math.hypot(cx - camp.x, cz - camp.z) / maxD
          const score = Math.abs(dc - target(L.level)) * 3 + pi * 0.25 + (fw - nd.fw) / 120 + R() * 0.12
          if (!best || score < best.score) best = { score, row, a, b, blk }
        }
      }
    }
    if (!best) continue
    const lotsUsed = best.row.lots.slice(best.a, best.b + 1)
    const first = lotsUsed[0]
    const last = lotsUsed[lotsUsed.length - 1]
    const x0 = Math.min(...lotsUsed.map((l) => l.x0))
    const x1 = Math.max(...lotsUsed.map((l) => l.x1))
    const z0 = Math.max(...lotsUsed.map((l) => l.z0))
    const z1 = Math.min(...lotsUsed.map((l) => l.z1))
    // merge into one lot
    const lot = mkLot(best.blk, x0, z0, x1, z1, first.face, { row: best.row, big: first.big, corner: first.corner || last.corner })
    for (const l of lotsUsed) l.taken = true
    best.row.lots.splice(best.a, lotsUsed.length, lot)
    const names = L.names
    usedNames[t] = (usedNames[t] || 0) + 1
    const nm = names[(usedNames[t] - 1) % names.length]
    const name = usedNames[t] > names.length ? `${nm} ${Math.ceil(usedNames[t] / names.length)}` : nm
    const loc = { id: 'loc' + locs.length, type: t, level: L.level, name, lot }
    lot.loc = loc
    locs.push(loc)
  }
  // the military checkpoint blocks the highway out of town
  {
    const x = W + 150
    const lot = { id: lotId++, x0: x - 50, z0: HIGHWAY_Z - 16 - 92, x1: x + 50, z1: HIGHWAY_Z - 16, face: 's', district: 'military', seed: Math.floor(R() * 1e9), big: true, special: true }
    lot.cx = x
    lot.cz = (lot.z0 + lot.z1) / 2
    lot.fw = 100
    lot.fd = lot.z1 - lot.z0
    lots.push(lot)
    const loc = { id: 'loc' + locs.length, type: 'military', level: 5, name: LOCATIONS.military.names[0], lot }
    lot.loc = loc
    locs.push(loc)
  }
  // sites: where the building stands inside its lot, and the door on the street
  for (const loc of locs) {
    const L = LOCATIONS[loc.type]
    const lot = loc.lot
    const S = SITE[L.yard] || SITE.lot
    const [bw, bd] = L.size
    const lw = lot.fw
    const ld = lot.fd
    const side = S.side ? (chance(0.5) ? 1 : -1) : 0
    let bx = side ? -side * (lw / 2 - bw / 2 - 2.5) : 0
    if (Math.abs(bx) + bw / 2 > lw / 2 - 1) bx = 0
    const bz = ld / 2 - S.front - bd / 2
    loc.site = { lw, ld, bw, bd, bx, bz, side, yard: L.yard, rot: FACE_ROT[lot.face] }
    const p = lotToWorld(lot, bx, ld / 2 + SIDEWALK + 4)
    loc.x = lotToWorld(lot, bx, bz).x
    loc.z = lotToWorld(lot, bx, bz).z
    loc.door = p
  }
  // levels and danger by distance (a location's type fixes its level)
  for (const loc of locs) loc.dCamp = Math.hypot(loc.x - camp.x, loc.z - camp.z) / maxD

  // ---------------------------------------------------------------- filler buildings
  let churches = 0
  for (const blk of all) {
    for (const row of blk.rows || []) {
      for (const l of row.lots) {
        if (l.loc || l.taken) continue
        l.bld = fillerFor(l, blk)
      }
    }
  }
  function fillerFor(l, blk) {
    const d = l.district
    const avenueFront = (l.face === 'n' && hE[blk.j0][Math.min(blk.i0, NX - 2)].kind === 'avenue') || (l.face === 's' && hE[Math.min(NZ - 1, blk.j1 + 1)][Math.min(blk.i0, NX - 2)].kind === 'avenue')
    const r = R()
    if (d === 'residential') {
      if (!churches && l.fw > 22 && r < 0.03) {
        churches++
        return { kind: 'church', name: pickR(CHURCH_NAMES) }
      }
      if (avenueFront && r < 0.55) return r < 0.4 ? { kind: 'shop', floors: ri(1, 2) } : { kind: 'mixed', floors: ri(3, 4) }
      if (r < 0.05) return { kind: 'empty' }
      if (r < 0.1 && l.fw > 22) return { kind: 'apartment', floors: ri(3, 4) }
      return { kind: 'house', floors: chance(0.55) ? 2 : 1, state: chance(0.12) ? 'burnt' : chance(0.25) ? 'boarded' : 'ok' }
    }
    if (d === 'commercial') {
      if (r < 0.12) return { kind: 'parking' }
      if (r < 0.16 && l.corner) return { kind: 'gas' }
      if (r < 0.48) return { kind: 'shop', floors: ri(1, 2) }
      if (r < 0.82) return { kind: 'mixed', floors: ri(3, 5) }
      return { kind: 'office', floors: ri(4, 7) }
    }
    if (d === 'downtown') {
      if (r < 0.04 && l.fw > 30) return { kind: 'plaza' }
      if (r < 0.12) return { kind: 'garage', floors: ri(4, 6) }
      if (r < 0.58) return { kind: 'tower', floors: ri(12, 30) }
      return { kind: 'office', floors: ri(6, 14) }
    }
    if (d === 'industrial') {
      if (r < 0.18) return { kind: 'yard' }
      if (r < 0.3) return { kind: 'tanks' }
      if (r < 0.52) return { kind: 'factory' }
      return { kind: 'warehouse' }
    }
    if (d === 'park') return { kind: 'park', pond: l.fw > 160 || (blk.park && l.fw > 100) }
    return { kind: 'empty' }
  }

  // ---------------------------------------------------------------- fires
  const fires = []
  for (let k = 0; k < 7; k++) {
    const cands = lots.filter((l) => l.bld && ['house', 'shop', 'mixed', 'office', 'warehouse', 'apartment'].includes(l.bld.kind) && !l.bld.fire)
    if (!cands.length) break
    const l = pickR(cands)
    l.bld.fire = true
    l.bld.state = 'burnt'
    fires.push({ x: l.cx, z: l.cz, s: l.fw > 40 ? 1.6 : 1 })
  }

  // ---------------------------------------------------------------- road graph
  const nodes = new Map()
  const node = (id, x, z) => {
    if (!nodes.has(id)) nodes.set(id, { id, x, z, links: [] })
    return nodes.get(id)
  }
  const link = (a, b, len, road) => {
    a.links.push({ to: b.id, len, road })
    b.links.push({ to: a.id, len, road })
  }
  for (let j = 0; j < NZ; j++) for (let i = 0; i < NX; i++) node(`${i},${j}`, xs[i], zs[j])
  for (const row of hE) for (const e of row) if (e.on) link(nodes.get(`${e.i},${e.j}`), nodes.get(`${e.i + 1},${e.j}`), e.x1 - e.x0, e)
  for (const col of vE) for (const e of col) if (e.on) link(nodes.get(`${e.i},${e.j}`), nodes.get(`${e.i},${e.j + 1}`), e.z1 - e.z0, e)
  // highway along the north edge, ramps down the avenues
  const hwNodes = []
  for (let i = 0; i < NX; i++) {
    if (!avX(i)) continue
    const hn = node(`hw${i}`, xs[i], HIGHWAY_Z)
    link(hn, nodes.get(`${i},0`), zs[0] - HIGHWAY_Z, { dir: 'v', ramp: true })
    if (hwNodes.length) link(hwNodes[hwNodes.length - 1], hn, xs[i] - hwNodes[hwNodes.length - 1].x, { dir: 'h', highway: true })
    hwNodes.push(hn)
  }
  const exit = node('hwE', W + 150, HIGHWAY_Z)
  link(hwNodes[hwNodes.length - 1], exit, exit.x - hwNodes[hwNodes.length - 1].x, { dir: 'h', highway: true })
  // the camp road comes up from the south-west
  const campNode = node('camp', camp.gate.x, camp.gate.z)
  const gateNode = node('campJ', xs[1], camp.z)
  link(campNode, gateNode, Math.abs(xs[1] - camp.gate.x), { dir: 'h', dirt: true })
  link(gateNode, nodes.get(`1,${NZ - 1}`), camp.z - zs[NZ - 1], { dir: 'v', countryRoad: true })
  // each location joins the graph at its door
  for (const loc of locs) {
    const lot = loc.lot
    const d = loc.door
    let a
    let b
    if (lot.special) {
      a = exit
      b = exit
    } else if (lot.face === 'n' || lot.face === 's') {
      const j = lot.face === 'n' ? lot.block.j0 : lot.block.j1 + 1
      const z = zs[j]
      let i = 0
      while (i < NX - 2 && xs[i + 1] < d.x) i++
      a = nodes.get(`${i},${j}`)
      b = nodes.get(`${i + 1},${j}`)
      d.z = z
    } else {
      const i = lot.face === 'w' ? lot.block.i0 : lot.block.i1 + 1
      let j = 0
      while (j < NZ - 2 && zs[j + 1] < d.z) j++
      a = nodes.get(`${i},${j}`)
      b = nodes.get(`${i},${j + 1}`)
      d.x = xs[i]
    }
    const n = node('door:' + loc.id, d.x, d.z)
    link(n, a, Math.hypot(a.x - d.x, a.z - d.z), { door: true })
    if (b !== a) link(n, b, Math.hypot(b.x - d.x, b.z - d.z), { door: true })
    loc.node = n.id
  }

  return {
    seed,
    W,
    H,
    xs,
    zs,
    NX,
    NZ,
    jr,
    iR,
    hE,
    vE,
    avX,
    avZ,
    blocks: all,
    lots,
    locs,
    river,
    rail,
    bridges,
    camp,
    campNode: campNode.id,
    nodes,
    fires,
    downtown: DT,
    highway: { z: HIGHWAY_Z, w: HIGHWAY_W, x0: -900, x1: W + 900, exit: exit.x },
    inRiver,
  }
}

// Lot-local (x across the frontage, z toward the street) to world.
export function lotToWorld(lot, lx, lz) {
  const r = FACE_ROT[lot.face]
  const c = Math.cos(r)
  const s = Math.sin(r)
  return { x: lot.cx + lx * c + lz * s, z: lot.cz - lx * s + lz * c }
}

// Shortest drive from the camp to a location along the streets.
export function route(city, fromId, toId) {
  const dist = new Map([[fromId, 0]])
  const prev = new Map()
  const open = [fromId]
  const done = new Set()
  while (open.length) {
    let bi = 0
    for (let k = 1; k < open.length; k++) if (dist.get(open[k]) < dist.get(open[bi])) bi = k
    const id = open.splice(bi, 1)[0]
    if (done.has(id)) continue
    done.add(id)
    if (id === toId) break
    const n = city.nodes.get(id)
    for (const l of n.links) {
      if (l.road?.door && l.to !== toId && !id.startsWith('door:')) continue
      const nd = dist.get(id) + l.len
      if (nd < (dist.get(l.to) ?? Infinity)) {
        dist.set(l.to, nd)
        prev.set(l.to, id)
        open.push(l.to)
      }
    }
  }
  if (!dist.has(toId)) return null
  const pts = []
  let cur = toId
  while (cur) {
    const n = city.nodes.get(cur)
    pts.unshift([n.x, n.z])
    cur = prev.get(cur)
  }
  return { pts, len: dist.get(toId) }
}
