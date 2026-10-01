// Supply-run levels. The chosen location is built from its city lot: the
// building at its real footprint, divided into typed rooms (kitchens,
// wards, armories, sales floors with aisles...), with doors, windows,
// furniture and lootable containers against the walls; its yard (lawn and
// shed, car park, forecourt and pumps, loading bay); the street out front
// where the van waits; and the neighbouring lots behind their fences.
// Everything lives on a 1 m tile grid in the lot's own frame: x along the
// frontage, z toward the street.
import { Grid, BLOCK } from '../core/grid.js'
import { LOCATIONS, ROOMS, CONTAINERS } from '../game/data.js'
import { seeded } from '../models/kit.js'
import { SIDEWALK, FACE_ROT, lotToWorld } from './city.js'
import { footOf, DEPTH } from '../models/furniture.js'

// Relative room sizes when a floor plan is divided up.
const ROOM_W = { sales: 7, warehouse: 9, hardware: 6, pharmacy: 3, diner: 3.2, garage: 2.6, ward: 2.2, classroom: 2.2, living: 1.7, lobby: 1.6, barracks: 1.6, kitchen: 1.3, bedroom: 1.15, lab: 1.2, cells: 1.3, armory: 1.2, office: 0.9, storeroom: 1.0, lockers: 0.9, bathroom: 0.55, corridor: 1 }
// Furniture footprints in tiles [along the wall, out from it]; null = no blocking.
export const DECOR_FOOT = { bed: [2, 3], sofa: [2, 1], armchair: [1, 1], rug: null, lamp: [1, 1], table: [2, 2], stove: [1, 1], toilet: [1, 1], tub: [1, 2], sink: [1, 1], chair: [1, 1], plant: [1, 1], boxes: [1, 1], booth: [2, 2], counterDecor: [3, 1], hospitalBed: [1, 2], curtain: null, cellBars: [2, 1], cot: [1, 2], forklift: [2, 3], schoolDesks: [2, 2], labBench: [2, 1], workbenchDecor: [2, 1], bench: [2, 1], coffeetable: [1, 1], nightstand: [1, 1] }
const DECOR_CENTER = new Set(['table', 'rug', 'coffeetable', 'booth', 'schoolDesks', 'forklift'])
const DECOR_DEPTH = { bed: 2.1, sofa: 0.95, armchair: 0.85, lamp: 0.4, stove: 0.64, toilet: 0.68, tub: 0.8, sink: 0.5, chair: 0.5, plant: 0.4, boxes: 0.8, counterDecor: 0.85, hospitalBed: 2.05, cellBars: 0.12, cot: 1.95, labBench: 0.84, workbenchDecor: 0.7, bench: 0.4, nightstand: 0.4 }

export function genLevel(city, loc, opts = {}) {
  const L = LOCATIONS[loc.type]
  const lot = loc.lot
  const site = loc.site
  const R = seeded((lot.seed ^ 0x9e3779b9) >>> 0) // layout: the same every visit
  const ri = (a, b) => Math.floor(a + R() * (b - a + 1))
  const chance = (p) => R() < p
  const pickR = (a) => a[Math.floor(R() * a.length)]
  const LR = opts.rnd || Math.random // loot and zombies: different every visit

  // ---------------------------------------------------------------- frame
  const fw = Math.round(lot.fw)
  const fd = Math.round(lot.fd)
  const roadW = streetWidth(city, lot)
  const NB = 14
  const x0 = Math.floor(-fw / 2 - NB)
  const x1 = Math.ceil(fw / 2 + NB)
  const zFront = fd / 2
  const zRoad0 = zFront + SIDEWALK
  const zRoad1 = zRoad0 + roadW
  const zFar = zRoad1 + SIDEWALK
  const z0 = Math.floor(-fd / 2 - 7)
  const z1 = Math.ceil(zFar + 9)
  const W = x1 - x0
  const H = z1 - z0
  const grid = new Grid(W, H)
  const T = (x, z) => [Math.floor(x - x0), Math.floor(z - z0)] // lot-local -> tile
  const C = (i, j) => ({ x: x0 + i + 0.5, z: z0 + j + 0.5 }) // tile centre -> lot-local
  const lv = {
    loc,
    type: loc.type,
    def: L,
    level: loc.level,
    W,
    H,
    x0,
    z0,
    grid,
    T,
    C,
    fw,
    fd,
    roadW,
    zFront,
    zRoad0,
    zRoad1,
    zFar,
    rooms: [],
    walls: new Uint8Array(W * H),
    wallSide: new Int16Array(W * H).fill(-1),
    roomAt: new Int16Array(W * H).fill(-1),
    doors: [],
    windows: [],
    containers: [],
    decor: [],
    fences: [],
    cars: [],
    trees: [],
    props: [],
    pave: [],
    buildings: [],
    neighbours: [],
    spawns: { inside: [], outside: [] },
  }
  const isWall = (i, j) => i >= 0 && j >= 0 && i < W && j < H && lv.walls[j * W + i] === 1
  const setWall = (i, j, kind = 1) => {
    if (i < 0 || j < 0 || i >= W || j >= H) return
    lv.walls[j * W + i] = kind
    grid.set(i, j, BLOCK, 1, 'wall')
  }
  const openTile = (i, j) => {
    lv.walls[j * W + i] = 0
    grid.set(i, j, 0, 0, null)
  }
  // world edges of the play area
  for (let i = 0; i < W; i++) {
    grid.set(i, 0, BLOCK, 1, 'edge')
    grid.set(i, H - 1, BLOCK, 1, 'edge')
  }
  for (let j = 0; j < H; j++) {
    grid.set(0, j, BLOCK, 1, 'edge')
    grid.set(W - 1, j, BLOCK, 1, 'edge')
  }

  // ---------------------------------------------------------------- the building(s)
  if (L.layout === 'compound') buildCompound()
  else {
    const [bw, bd] = L.size
    const bi0 = Math.round(site.bx - bw / 2 - x0)
    const bj0 = Math.round(site.bz - bd / 2 - z0)
    const B = { i0: bi0, j0: bj0, i1: bi0 + bw - 1, j1: bj0 + bd - 1, main: true }
    lv.bld = B
    lv.buildings.push(B)
    outline(B)
    const rooms = planRooms(B, L.layout, [...L.rooms])
    connectRooms(B, rooms)
  }

  function outline(B) {
    for (let i = B.i0; i <= B.i1; i++) {
      setWall(i, B.j0)
      setWall(i, B.j1)
    }
    for (let j = B.j0; j <= B.j1; j++) {
      setWall(B.i0, j)
      setWall(B.i1, j)
    }
  }
  function addRoom(type, i0, j0, i1, j1, B) {
    const r = { id: lv.rooms.length, type, i0, j0, i1, j1, B, def: ROOMS[type] || ROOMS.storeroom }
    lv.rooms.push(r)
    for (let j = j0; j <= j1; j++) for (let i = i0; i <= i1; i++) lv.roomAt[j * W + i] = r.id
    return r
  }
  function wallLine(axis, at, a0, a1) {
    if (axis === 'x') for (let j = a0; j <= a1; j++) setWall(at, j)
    else for (let i = a0; i <= a1; i++) setWall(i, at)
  }
  // Split a rectangle among rooms in proportion to their weights.
  function treemap(i0, j0, i1, j1, list, B) {
    if (list.length === 1) return [addRoom(list[0], i0, j0, i1, j1, B)]
    const ws = list.map((t) => ROOM_W[t] || 1)
    const total = ws.reduce((a, b) => a + b, 0)
    let acc = 0
    let k = 0
    let best = 1e9
    for (let n = 0; n < list.length - 1; n++) {
      acc += ws[n]
      const d = Math.abs(acc - total / 2)
      if (d < best) {
        best = d
        k = n
      }
    }
    const left = list.slice(0, k + 1)
    const right = list.slice(k + 1)
    const lw = left.reduce((a, t) => a + (ROOM_W[t] || 1), 0) / total
    const w = i1 - i0 + 1
    const d = j1 - j0 + 1
    const alongX = w >= d
    const len = alongX ? w : d
    let cut = Math.round(len * lw)
    cut = Math.max(3, Math.min(len - 4, cut))
    if (len < 7) {
      // too small to split: merge the smallest away
      const keep = list.slice().sort((a, b) => (ROOM_W[b] || 1) - (ROOM_W[a] || 1))[0]
      return [addRoom(keep, i0, j0, i1, j1, B)]
    }
    if (alongX) {
      wallLine('x', i0 + cut, j0, j1)
      return [...treemap(i0, j0, i0 + cut - 1, j1, left, B), ...treemap(i0 + cut + 1, j0, i1, j1, right, B)]
    }
    wallLine('z', j0 + cut, i0, i1)
    return [...treemap(i0, j0, i1, j0 + cut - 1, left, B), ...treemap(i0, j0 + cut + 1, i1, j1, right, B)]
  }
  function planRooms(B, layout, list) {
    const i0 = B.i0 + 1
    const j0 = B.j0 + 1
    const i1 = B.i1 - 1
    const j1 = B.j1 - 1
    const iw = i1 - i0 + 1
    const id = j1 - j0 + 1
    if (layout === 'shop' || layout === 'hall') {
      // the big front room, a strip of small rooms along the back
      const main = list.shift()
      const back = Math.max(4, Math.round(id * (layout === 'hall' ? 0.26 : 0.34)))
      const mainR = addRoom(main, i0, j0 + back + 1, i1, j1, B)
      wallLine('z', j0 + back, i0, i1)
      const rest = list.length ? treemapStrip(i0, j0, i1, j0 + back - 1, list, B) : [addRoom('storeroom', i0, j0, i1, j0 + back - 1, B)]
      B.front = mainR
      return [mainR, ...rest]
    }
    if (layout === 'corridor') {
      // a hallway from the front door to the back, rooms either side
      const cw = iw >= 20 ? 3 : 2
      const ci0 = i0 + Math.floor((iw - cw) / 2)
      const ci1 = ci0 + cw - 1
      wallLine('x', ci0 - 1, j0, j1)
      wallLine('x', ci1 + 1, j0, j1)
      const corr = addRoom('corridor', ci0, j0, ci1, j1, B)
      corr.corridor = true
      const lobby = list[0] === 'lobby' ? list.shift() : null
      const L1 = []
      const R1 = []
      list.forEach((t, k) => (k % 2 ? R1 : L1).push(t))
      const rooms = [corr]
      if (lobby) {
        // the lobby spans the front of the building across the hallway
        const ld = Math.max(4, Math.round(id * 0.24))
        // reopen the hallway walls through the lobby
        for (let j = j1 - ld + 1; j <= j1; j++) {
          openTile(ci0 - 1, j)
          openTile(ci1 + 1, j)
        }
        wallLine('z', j1 - ld, i0, i1)
        for (let i = ci0; i <= ci1; i++) {
          openTile(i, j1 - ld)
          lv.doors.push({ i, j: j1 - ld, horiz: true, passage: true, B })
        }
        const lob = addRoom('lobby', i0, j1 - ld + 1, i1, j1, B)
        for (let j = j1 - ld + 1; j <= j1; j++) for (let i = ci0 - 1; i <= ci1 + 1; i++) lv.roomAt[j * W + i] = lob.id
        corr.j1 = j1 - ld - 1
        for (let j = corr.j1 + 1; j <= j1; j++) for (let i = ci0; i <= ci1; i++) lv.roomAt[j * W + i] = lob.id
        rooms.push(lob)
        B.front = lob
        if (L1.length) rooms.push(...treemapStrip(i0, j0, ci0 - 2, j1 - ld - 1, L1, B, 'z'))
        if (R1.length) rooms.push(...treemapStrip(ci1 + 2, j0, i1, j1 - ld - 1, R1, B, 'z'))
      } else {
        B.front = corr
        if (L1.length) rooms.push(...treemapStrip(i0, j0, ci0 - 2, j1, L1, B, 'z'))
        if (R1.length) rooms.push(...treemapStrip(ci1 + 2, j0, i1, j1, R1, B, 'z'))
      }
      return rooms
    }
    // house and anything else
    const ordered = list.slice()
    // the garage goes on one end, the living room toward the street
    const gi = ordered.indexOf('garage')
    if (gi >= 0) {
      ordered.splice(gi, 1)
      const gw = Math.min(7, Math.max(5, Math.round(iw * 0.32)))
      const left = chance(0.5)
      const ga = left ? addRoom('garage', i0, j0, i0 + gw - 1, j1, B) : addRoom('garage', i1 - gw + 1, j0, i1, j1, B)
      wallLine('x', left ? i0 + gw : i1 - gw, j0, j1)
      ga.garage = true
      const rest = left ? treemap(i0 + gw + 1, j0, i1, j1, ordered, B) : treemap(i0, j0, i1 - gw - 1, j1, ordered, B)
      B.front = rest.find((r) => r.type === 'living') || rest[0]
      return [ga, ...rest]
    }
    const rooms = treemap(i0, j0, i1, j1, ordered, B)
    B.front = rooms.find((r) => r.type === 'living' || r.type === 'lobby') || rooms[0]
    return rooms
  }
  // Divide a strip into rooms one after another along its long axis.
  function treemapStrip(i0, j0, i1, j1, list, B, axisHint) {
    const w = i1 - i0 + 1
    const d = j1 - j0 + 1
    if (w < 3 || d < 3) return []
    const alongX = axisHint ? axisHint === 'x' : w >= d
    const len = alongX ? w : d
    list = list.slice()
    // drop the smallest rooms until everything fits at 3 tiles or more
    while (list.length > 1 && len - (list.length - 1) < list.length * 3) {
      let k = 0
      for (let n = 1; n < list.length; n++) if ((ROOM_W[list[n]] || 1) < (ROOM_W[list[k]] || 1)) k = n
      list.splice(k, 1)
    }
    const ws = list.map((t) => ROOM_W[t] || 1)
    const tot = ws.reduce((a, b) => a + b, 0)
    const usable = len - (list.length - 1)
    const sizes = ws.map((v) => Math.max(3, Math.round((usable * v) / tot)))
    let diff = usable - sizes.reduce((a, b) => a + b, 0)
    for (let guard = 0; diff !== 0 && guard < 200; guard++) {
      const k = guard % sizes.length
      if (diff > 0) {
        sizes[k]++
        diff--
      } else if (sizes[k] > 3) {
        sizes[k]--
        diff++
      }
    }
    const out = []
    let p = alongX ? i0 : j0
    list.forEach((t, k) => {
      const e = p + sizes[k] - 1
      out.push(alongX ? addRoom(t, p, j0, e, j1, B) : addRoom(t, i0, p, i1, e, B))
      if (k < list.length - 1) wallLine(alongX ? 'x' : 'z', e + 1, alongX ? j0 : i0, alongX ? j1 : i1)
      p = e + 2
    })
    return out
  }
  // Doors between rooms (every room reachable), out the front and back.
  function connectRooms(B, rooms) {
    const doorAt = (i, j, ext = false, wide = 1, axis = null) => {
      for (let k = 0; k < wide; k++) {
        const ii = axis === 'x' ? i + k : i
        const jj = axis === 'z' ? j + k : j
        openTile(ii, jj)
        const horiz = isWall(ii - 1, jj) || isWall(ii + 1, jj) || axis === 'x'
        lv.doors.push({ i: ii, j: jj, horiz, ext, wide, part: k, B })
      }
    }
    // candidate tiles: wall tiles with room a on one side and b on the other
    const pairs = new Map()
    for (let j = B.j0 + 1; j < B.j1; j++) {
      for (let i = B.i0 + 1; i < B.i1; i++) {
        if (!isWall(i, j)) continue
        const n = lv.roomAt[(j - 1) * W + i]
        const s = lv.roomAt[(j + 1) * W + i]
        const w = lv.roomAt[j * W + i - 1]
        const e = lv.roomAt[j * W + i + 1]
        let a = -1
        let b2 = -1
        if (n >= 0 && s >= 0 && n !== s && isWall(i - 1, j) && isWall(i + 1, j)) {
          a = n
          b2 = s
        } else if (w >= 0 && e >= 0 && w !== e && isWall(i, j - 1) && isWall(i, j + 1)) {
          a = w
          b2 = e
        } else continue
        const key = Math.min(a, b2) + ',' + Math.max(a, b2)
        if (!pairs.has(key)) pairs.set(key, { a: Math.min(a, b2), b: Math.max(a, b2), tiles: [] })
        pairs.get(key).tiles.push([i, j])
      }
    }
    const linked = new Set([B.front.id])
    const edges = [...pairs.values()]
    // corridor rooms always open onto the corridor
    for (const e of edges) {
      const ra = lv.rooms[e.a]
      const rb = lv.rooms[e.b]
      if (ra.corridor || rb.corridor) {
        const t = e.tiles[Math.floor(e.tiles.length / 2)]
        doorAt(t[0], t[1])
        e.used = true
        linked.add(e.a)
        linked.add(e.b)
      }
    }
    let guard = 0
    while (linked.size < rooms.length && guard++ < 200) {
      const cands = edges.filter((e) => !e.used && linked.has(e.a) !== linked.has(e.b))
      if (!cands.length) break
      const e = pickR(cands)
      const t = e.tiles[Math.floor(e.tiles.length * (0.3 + R() * 0.4))]
      doorAt(t[0], t[1], false, lv.rooms[e.a].type === 'sales' || lv.rooms[e.b].type === 'warehouse' ? 2 : 1, isWall(t[0] - 1, t[1]) ? 'x' : 'z')
      e.used = true
      linked.add(e.a)
      linked.add(e.b)
    }
    for (const e of edges) if (!e.used && e.tiles.length > 3 && chance(0.25)) doorAt(...e.tiles[Math.floor(e.tiles.length / 2)])
    // front door(s)
    const front = B.front
    const fi = Math.max(front.i0 + 1, Math.min(front.i1 - 1, Math.round((front.i0 + front.i1) / 2)))
    const wideFront = ['shop', 'hall', 'corridor'].includes(L.layout) ? 2 : 1
    doorAt(fi - (wideFront > 1 ? 1 : 0), B.j1, true, wideFront, 'x')
    B.door = { i: fi, j: B.j1 }
    // garage bay doors open onto the street
    for (const r of rooms) {
      if (r.type !== 'garage' || r.j1 !== B.j1 - 1) continue
      const n = Math.min(4, r.i1 - r.i0 - 1)
      const gi = r.i0 + Math.floor((r.i1 - r.i0 + 1 - n) / 2)
      doorAt(gi, B.j1, true, n, 'x')
      r.bay = true
    }
    // back and side doors
    const backRooms = rooms.filter((r) => r.j0 === B.j0 + 1 && !r.corridor)
    if (backRooms.length && chance(0.75)) {
      const r = pickR(backRooms)
      doorAt(ri(r.i0 + 1, Math.max(r.i0 + 1, r.i1 - 1)), B.j0, true)
    }
    if (chance(0.35)) {
      const side = chance(0.5)
      const rs = rooms.filter((r) => (side ? r.i0 === B.i0 + 1 : r.i1 === B.i1 - 1) && r.j1 - r.j0 >= 3)
      if (rs.length) {
        const r = pickR(rs)
        doorAt(side ? B.i0 : B.i1, ri(r.j0 + 1, r.j1 - 1), true, 1, 'z')
      }
    }
    // windows on the outside walls
    for (let i = B.i0 + 1; i < B.i1; i++) {
      for (const j of [B.j0, B.j1]) {
        if (!isWall(i, j)) continue
        const inner = lv.roomAt[(j === B.j0 ? j + 1 : j - 1) * W + i]
        const rt = inner >= 0 ? lv.rooms[inner].type : null
        if (rt === 'garage' || rt === 'warehouse' || rt === 'storeroom' || rt === 'cells') continue
        if ((i - B.i0) % 3 === 2 && !isWall(i, j === B.j0 ? j + 1 : j - 1) && !lv.doors.some((d) => Math.abs(d.i - i) <= 1 && d.j === j)) lv.windows.push({ i, j, horiz: true })
      }
    }
    for (let j = B.j0 + 1; j < B.j1; j++) {
      for (const i of [B.i0, B.i1]) {
        if (!isWall(i, j)) continue
        const inner = lv.roomAt[j * W + (i === B.i0 ? i + 1 : i - 1)]
        const rt = inner >= 0 ? lv.rooms[inner].type : null
        if (rt === 'garage' || rt === 'warehouse' || rt === 'storeroom' || rt === 'cells') continue
        if ((j - B.j0) % 3 === 2 && !isWall(i === B.i0 ? i + 1 : i - 1, j) && !lv.doors.some((d) => Math.abs(d.j - j) <= 1 && d.i === i)) lv.windows.push({ i, j, horiz: false })
      }
    }
  }

  // The military compound: prefab buildings inside a blast-wall ring.
  function buildCompound() {
    const lw = fw
    const ld = fd
    const ring = { i0: Math.round(-lw / 2 + 2 - x0), j0: Math.round(-ld / 2 + 2 - z0), i1: Math.round(lw / 2 - 2 - x0), j1: Math.round(ld / 2 - 2 - z0) }
    lv.compound = ring
    for (let i = ring.i0; i <= ring.i1; i++) {
      for (const j of [ring.j0, ring.j1]) {
        if (j === ring.j1 && Math.abs(i - (ring.i0 + ring.i1) / 2) < 3) continue
        grid.set(i, j, BLOCK, 1, 'hesco')
        lv.props.push({ kind: 'hesco', i, j })
      }
    }
    for (let j = ring.j0 + 1; j < ring.j1; j++)
      for (const i of [ring.i0, ring.i1]) {
        grid.set(i, j, BLOCK, 1, 'hesco')
        lv.props.push({ kind: 'hesco', i, j })
      }
    const pre = [
      ['armory', 9, 7],
      ['barracks', 12, 7],
      ['barracks', 12, 7],
      ['storeroom', 9, 7],
      ['office', 8, 6],
      ['ward', 10, 7],
    ]
    const slots = [
      [ring.i0 + 4, ring.j0 + 4],
      [ring.i0 + 18, ring.j0 + 4],
      [ring.i0 + 34, ring.j0 + 4],
      [ring.i0 + 4, ring.j0 + 16],
      [ring.i0 + 18, ring.j0 + 18],
      [ring.i0 + 34, ring.j0 + 16],
    ]
    pre.forEach(([type, bw, bd], k) => {
      const [si, sj] = slots[k]
      if (si + bw >= ring.i1 - 2 || sj + bd >= ring.j1 - 4) return
      const B = { i0: si, j0: sj, i1: si + bw - 1, j1: sj + bd - 1, prefab: true }
      lv.buildings.push(B)
      outline(B)
      const r = addRoom(type, B.i0 + 1, B.j0 + 1, B.i1 - 1, B.j1 - 1, B)
      B.front = r
      connectRooms(B, [r])
      if (!lv.bld) lv.bld = B
    })
  }

  // ---------------------------------------------------------------- furniture and loot
  const nearDoor = (i, j, d = 1) => lv.doors.some((o) => Math.abs(o.i - i) <= d && Math.abs(o.j - j) <= d)
  const free = (i, j) => grid.open(i, j) && !grid.owner[grid.i(i, j)] && !lv.walls[j * W + i]
  // tiles against a wall inside a room, with the direction they face
  function wallSlots(r) {
    const out = []
    for (let j = r.j0; j <= r.j1; j++)
      for (let i = r.i0; i <= r.i1; i++) {
        if (lv.roomAt[j * W + i] !== r.id || nearDoor(i, j)) continue
        if (isWall(i, j - 1)) out.push({ i, j, rot: 0, ax: 'x' })
        if (isWall(i, j + 1)) out.push({ i, j, rot: Math.PI, ax: 'x' })
        if (isWall(i - 1, j)) out.push({ i, j, rot: Math.PI / 2, ax: 'z' })
        if (isWall(i + 1, j)) out.push({ i, j, rot: -Math.PI / 2, ax: 'z' })
      }
    for (let k = out.length - 1; k > 0; k--) {
      const m = Math.floor(R() * (k + 1))
      ;[out[k], out[m]] = [out[m], out[k]]
    }
    return out
  }
  // footprint tiles for something w wide along the wall and d deep, starting at a slot
  function footprint(s, w, d) {
    const tiles = []
    const along = s.ax === 'x' ? [1, 0] : [0, 1]
    const out = s.rot === 0 ? [0, 1] : s.rot === Math.PI ? [0, -1] : s.rot === Math.PI / 2 ? [1, 0] : [-1, 0]
    for (let a = 0; a < w; a++) for (let b = 0; b < d; b++) tiles.push([s.i + along[0] * a + out[0] * b, s.j + along[1] * a + out[1] * b])
    return tiles
  }
  function placeAgainstWall(r, kind, w, d, depth, onPlace) {
    for (const s of wallSlots(r)) {
      const tiles = footprint(s, w, d)
      if (!tiles.every(([i, j]) => free(i, j) && lv.roomAt[j * W + i] === r.id && !nearDoor(i, j))) continue
      // the tile in front must stay walkable
      const out = s.rot === 0 ? [0, 1] : s.rot === Math.PI ? [0, -1] : s.rot === Math.PI / 2 ? [1, 0] : [-1, 0]
      const fronts = footprint(s, w, d + 1).filter((t) => !tiles.some((u) => u[0] === t[0] && u[1] === t[1]))
      if (!fronts.some(([i, j]) => free(i, j))) continue
      const ci = tiles.reduce((a, t) => a + t[0], 0) / tiles.length
      const cj = tiles.reduce((a, t) => a + t[1], 0) / tiles.length
      const c = C(ci, cj)
      const off = -d / 2 - 0.38 + depth / 2
      const x = c.x + out[0] * off
      const z = c.z + out[1] * off
      return onPlace({ tiles, x, z, rot: s.rot, i: ci, j: cj })
    }
    return null
  }
  function addContainer(kind, r, at) {
    const def = CONTAINERS[kind]
    if (!def) return null
    const c = { id: lv.containers.length, kind, def, room: r?.type || null, tiles: at.tiles, x: at.x, z: at.z, rot: at.rot, locked: !!def.locked }
    for (const [i, j] of at.tiles) grid.set(i, j, BLOCK, 0, c)
    lv.containers.push(c)
    return c
  }
  const rooms = lv.rooms.filter((r) => !r.corridor)
  for (const r of rooms) {
    const D = r.def
    const area = (r.i1 - r.i0 + 1) * (r.j1 - r.j0 + 1)
    // aisles of shelving across the middle of big shop floors
    if (D.aisles) {
      const kind = D.aisles
      const [aw] = footOf(kind)
      for (let j = r.j0 + 2; j <= r.j1 - 3; j += 3) {
        for (let i = r.i0 + 2; i + aw - 1 <= r.i1 - 2; i += aw) {
          if ((i - r.i0) % (aw * 3 + 2) >= aw * 3) continue
          const tiles = []
          for (let a = 0; a < aw; a++) tiles.push([i + a, j])
          if (!tiles.every(([a, b]) => free(a, b) && !nearDoor(a, b, 2))) continue
          const c = C(i + (aw - 1) / 2, j)
          addContainer(kind, r, { tiles, x: c.x, z: c.z, rot: ((j - r.j0) / 3) % 2 ? Math.PI : 0 })
        }
      }
    }
    // cars parked in garages
    if (D.car && chance(D.car)) {
      const ci = Math.round((r.i0 + r.i1) / 2) - 1
      const cj = Math.round((r.j0 + r.j1) / 2) - 2
      const tiles = []
      for (let a = 0; a < 2; a++) for (let b = 0; b < 4; b++) tiles.push([ci + a, cj + b])
      if (tiles.every(([a, b]) => free(a, b) && lv.roomAt[b * W + a] === r.id)) {
        const c = C(ci + 0.5, cj + 1.5)
        addContainer('car', r, { tiles, x: c.x, z: c.z, rot: chance(0.5) ? 0 : Math.PI })
      }
    }
    // containers against the walls
    const want = []
    const scale = Math.max(0.7, Math.min(2.2, area / 22))
    for (const [kind, w] of Object.entries(D.containers || {})) {
      const n = Math.floor(w * scale) + (chance((w * scale) % 1) ? 1 : 0)
      for (let k = 0; k < n; k++) want.push(kind)
    }
    for (const kind of want) {
      const [w, d] = footOf(kind)
      placeAgainstWall(r, kind, w, d, DEPTH[kind] ?? 0.6, (at) => addContainer(kind, r, at))
    }
    // furniture
    for (const kind of D.decor || []) {
      if (chance(0.15)) continue
      placeDecor(r, kind)
    }
    if (D.center) placeDecor(r, D.center)
  }
  function placeDecor(r, kind) {
    const f = DECOR_FOOT[kind]
    if (DECOR_CENTER.has(kind) || !f) {
      const cw = f ? f[0] : 3
      const cd = f ? f[1] : 2
      const ci = Math.round((r.i0 + r.i1) / 2 - (cw - 1) / 2)
      const cj = Math.round((r.j0 + r.j1) / 2 - (cd - 1) / 2)
      const tiles = []
      for (let a = 0; a < cw; a++) for (let b = 0; b < cd; b++) tiles.push([ci + a, cj + b])
      if (f && !tiles.every(([a, b]) => free(a, b) && lv.roomAt[b * W + a] === r.id && !nearDoor(a, b))) return
      const c = C(ci + (cw - 1) / 2, cj + (cd - 1) / 2)
      lv.decor.push({ kind, x: c.x, z: c.z, rot: (cw < cd ? Math.PI / 2 : 0) + (chance(0.5) ? Math.PI : 0), block: !!f })
      if (f) for (const [a, b] of tiles) grid.set(a, b, BLOCK, 0, 'decor')
      return
    }
    placeAgainstWall(r, kind, f[0], f[1], DECOR_DEPTH[kind] ?? 0.6, (at) => {
      lv.decor.push({ kind, x: at.x, z: at.z, rot: at.rot, block: true })
      for (const [a, b] of at.tiles) grid.set(a, b, BLOCK, 0, 'decor')
      return true
    })
  }

  // ---------------------------------------------------------------- street, yard, neighbours
  const block = (xa, za, xb, zb, owner = 'solid', opaque = 1) => {
    const [i0, j0] = T(Math.min(xa, xb), Math.min(za, zb))
    const [i1, j1] = T(Math.max(xa, xb) - 0.01, Math.max(za, zb) - 0.01)
    for (let j = Math.max(1, j0); j <= Math.min(H - 2, j1); j++) for (let i = Math.max(1, i0); i <= Math.min(W - 2, i1); i++) if (grid.open(i, j)) grid.set(i, j, BLOCK, opaque, owner)
  }
  // our lot's fence line on the sides and back, with gaps
  const fenceKind = ['house'].includes(L.yard) ? 'wood' : 'chain'
  lv.fences.push({ kind: fenceKind, pts: [[-fw / 2, zFront - 2], [-fw / 2, -fd / 2], [fw / 2, -fd / 2], [fw / 2, zFront - 2]], gaps: [] })
  for (let z = -fd / 2; z < zFront - 2; z++) {
    if (Math.abs(z - (site.bz - site.bd / 2 - 2)) < 1.2) continue
    block(-fw / 2 - 0.5, z, -fw / 2 + 0.5, z + 1, 'fence', 0)
    block(fw / 2 - 0.5, z, fw / 2 + 0.5, z + 1, 'fence', 0)
  }
  for (let x = -fw / 2; x < fw / 2; x++) if (Math.abs(x) > 1.5) block(x, -fd / 2 - 0.5, x + 1, -fd / 2 + 0.5, 'fence', 0)
  // neighbours: their buildings stand behind fences; only their front yards are open
  for (const s of [-1, 1]) {
    const nx0 = s < 0 ? x0 + 1 : fw / 2 + 1
    const nx1 = s < 0 ? -fw / 2 - 1 : x1 - 1
    block(nx0, z0 + 1, nx1, zFront - 7, 'neighbour', 1)
    lv.neighbours.push({ side: s, x0: nx0, x1: nx1 })
  }
  // across the street: their front walls
  block(x0 + 1, zFar + 3, x1 - 1, z1 - 1, 'neighbour', 1)
  // street furniture and parked cars
  const vanX = Math.max(x0 + 8, Math.min(x1 - 12, site.bx + (chance(0.5) ? -1 : 1) * Math.min(6, fw / 4)))
  const vanZ = zRoad0 + 2.6
  lv.van = { x: vanX, z: vanZ, rot: Math.PI / 2 }
  block(vanX - 2.8, vanZ - 1.1, vanX + 2.8, vanZ + 1.1, 'van', 1)
  lv.evac = { x: vanX + 5.6, z: vanZ - 0.6, r: 3.0 }
  for (let k = 0; k < 6; k++) {
    const x = x0 + 6 + LR() * (W - 12)
    const curb = chance(0.7)
    const z = curb ? (chance(0.5) ? zRoad0 + 1.4 : zRoad1 - 1.4) : zRoad0 + roadW / 2 + (LR() - 0.5) * 3
    if (Math.abs(x - vanX) < 7 || Math.hypot(x - lv.evac.x, z - lv.evac.z) < 6) continue
    const [ci, cj] = T(x - 2, z - 1)
    const tiles = []
    for (let a = 0; a < 4; a++) for (let b = 0; b < 2; b++) tiles.push([ci + a, cj + b])
    if (!tiles.every(([a, b]) => free(a, b))) continue
    const c = C(ci + 1.5, cj + 0.5)
    addContainer('car', null, { tiles, x: c.x, z: c.z, rot: Math.PI / 2 + (chance(0.5) ? Math.PI : 0) + (curb ? 0 : (LR() - 0.5) * 0.8) })
  }
  for (let x = x0 + 4; x < x1 - 4; x += 9 + R() * 6) {
    const [i, j] = T(x, zFront + 0.8)
    if (!free(i, j) || nearDoor(i, j, 2)) continue
    if (chance(0.45)) {
      const c = C(i, j)
      addContainer('trash', null, { tiles: [[i, j]], x: c.x, z: c.z, rot: Math.PI })
    } else if (chance(0.3)) {
      lv.props.push({ kind: 'hydrant', x: C(i, j).x, z: C(i, j).z })
      grid.set(i, j, BLOCK, 0, 'prop')
    }
  }
  for (let x = x0 + 6; x < x1 - 4; x += 16)
    for (const z of [zFront + SIDEWALK - 0.5, zFar - SIDEWALK + 0.5]) {
      lv.props.push({ kind: 'lamp', x, z, ry: z < zRoad0 ? 0 : Math.PI })
      const [i, j] = T(x, z)
      if (free(i, j)) grid.set(i, j, BLOCK, 0, 'prop')
    }
  yard()

  function yard() {
    const lw = fw
    const ld = fd
    const by = L.yard
    const front = site.bz + site.bd / 2
    if (by === 'house') {
      const door = C(lv.bld.door.i, lv.bld.door.j)
      lv.pave.push({ mat: 'concrete', x0: door.x - 0.8, z0: front, x1: door.x + 0.8, z1: zFront, c: '#b4b0a6' })
      const dx = door.x > 0 ? -lw / 2 + 3 : lw / 2 - 3
      lv.pave.push({ mat: 'concrete', x0: dx - 1.8, z0: front + 0.3, x1: dx + 1.8, z1: zFront, c: '#aaa69c' })
      if (chance(0.75)) carAt(dx, zFront - 3.2, 0)
      const tree = (x, z, kind) => {
        const [ti, tj] = T(x, z)
        if (free(ti, tj) && lv.roomAt[tj * W + ti] < 0 && Math.abs(x - door.x) > 1.6 && Math.abs(x - dx) > 2.4) {
          lv.trees.push({ x, z, kind })
          grid.set(ti, tj, BLOCK, 0, 'tree')
        }
      }
      for (let k = 0; k < 5; k++) tree((R() - 0.5) * (lw - 4), -ld / 2 + 2 + R() * Math.max(1, site.bz - site.bd / 2 + ld / 2 - 4), pickR(['oak', 'maple', 'pine']))
      for (let k = 0; k < 2; k++) tree((R() < 0.5 ? -1 : 1) * (lw / 2 - 2), front + 2 + R() * Math.max(0.5, zFront - front - 4), 'maple')
      const sx = (R() < 0.5 ? -1 : 1) * (lw / 2 - 3)
      const [si, sj] = T(sx - 1.5, -ld / 2 + 1.2)
      const tiles = []
      for (let a = 0; a < 3; a++) for (let b = 0; b < 3; b++) tiles.push([si + a, sj + b])
      if (tiles.every(([a, b]) => free(a, b))) {
        const c = C(si + 1, sj + 1)
        addContainer('shed', null, { tiles, x: c.x, z: c.z, rot: 0 })
      }
    } else if (by === 'parking' || by === 'lot' || by === 'loading') {
      const pz0 = by === 'parking' ? front + 1 : -ld / 2 + 1
      lv.pave.push({ mat: 'asphalt', x0: -lw / 2 + 0.5, z0: pz0, x1: lw / 2 - 0.5, z1: zFront, c: '#8a8a86', stalls: by !== 'loading' })
      if (by !== 'parking') lv.pave.push({ mat: 'asphalt', x0: -lw / 2 + 0.5, z0: -ld / 2 + 0.5, x1: lw / 2 - 0.5, z1: front, c: '#8a8a86' })
      const n = by === 'parking' ? 6 : 3
      for (let k = 0; k < n * 3 && lv.containers.filter((c) => c.kind === 'car').length < n + 6; k++) {
        const x = -lw / 2 + 4 + LR() * (lw - 8)
        const z = by === 'parking' ? front + 6 + LR() * Math.max(1, zFront - front - 10) : -ld / 2 + 4 + LR() * (ld - 8)
        carAt(x, z, chance(0.5) ? 0 : Math.PI)
      }
      const dx = site.bx + (site.side || 1) * (site.bw / 2 + 2.5)
      const [di, dj] = T(dx - 1, site.bz - site.bd / 2 - 1.6)
      if (free(di, dj) && free(di + 1, dj)) {
        const c = C(di + 0.5, dj)
        addContainer('dumpster', null, { tiles: [[di, dj], [di + 1, dj]], x: c.x, z: c.z, rot: 0 })
      }
      if (by === 'loading') {
        for (let k = 0; k < 6; k++) {
          const x = -lw / 2 + 4 + LR() * (lw - 8)
          const z = front + 3 + LR() * Math.max(1, zFront - front - 6)
          const [ci, cj] = T(x, z)
          if (free(ci, cj)) {
            const c = C(ci, cj)
            addContainer('crate', null, { tiles: [[ci, cj]], x: c.x, z: c.z, rot: LR() * 6 })
          }
        }
        lv.trucks = [{ x: site.bx - site.bw / 4, z: front + 7, rot: Math.PI }]
        block(site.bx - site.bw / 4 - 1.4, front + 1, site.bx - site.bw / 4 + 1.4, front + 13, 'truck', 1)
      }
    } else if (by === 'forecourt') {
      lv.pave.push({ mat: 'concrete', x0: -lw / 2 + 0.5, z0: front, x1: lw / 2 - 0.5, z1: zFront, c: '#b8b4aa' })
      const cz = front + 9
      lv.canopy = { x: 0, z: cz, w: Math.min(lw - 4, 16), d: 9 }
      for (const sx of [-1, 1])
        for (const sz of [-1, 1]) {
          const [pi, pj] = T((sx * lv.canopy.w) / 4, cz + sz * 1.6)
          if (free(pi, pj)) {
            const c = C(pi, pj)
            addContainer('pump', null, { tiles: [[pi, pj]], x: c.x, z: c.z, rot: Math.PI / 2 })
          }
        }
      carAt(lv.canopy.w / 4 + 2.4, cz, 0)
    } else if (by === 'street') {
      lv.pave.push({ mat: 'concrete', x0: -lw / 2 + 0.5, z0: front, x1: lw / 2 - 0.5, z1: zFront, c: '#b0aca4' })
      for (const s of [-1, 1]) {
        const [di, dj] = T(site.bx + s * (site.bw / 2 + 1.5), site.bz - 2)
        if (free(di, dj) && free(di, dj + 1)) {
          const c = C(di, dj + 0.5)
          addContainer('dumpster', null, { tiles: [[di, dj], [di, dj + 1]], x: c.x, z: c.z, rot: s > 0 ? -Math.PI / 2 : Math.PI / 2 })
        }
      }
    } else if (by === 'checkpoint') {
      lv.pave.push({ mat: 'gravel', x0: -lw / 2 + 1, z0: -ld / 2 + 1, x1: lw / 2 - 1, z1: ld / 2 - 1, c: '#a49a84' })
      for (let k = 0; k < 8; k++) {
        const x = -lw / 2 + 8 + LR() * (lw - 16)
        const z = -ld / 2 + 8 + LR() * (ld - 16)
        const [ci, cj] = T(x, z)
        if (free(ci, cj) && free(ci + 1, cj)) {
          const c = C(ci + 0.5, cj)
          addContainer('milcrate', null, { tiles: [[ci, cj]], x: c.x, z: c.z, rot: LR() * 6 })
        }
      }
      for (let k = 0; k < 3; k++) carAt(-lw / 2 + 10 + k * 6, ld / 2 - 8, 0, 'humvee')
    }
  }
  function carAt(x, z, rot, kind = null) {
    const vertical = Math.abs(Math.cos(rot)) > 0.5
    const [ci, cj] = vertical ? T(x - 1, z - 2) : T(x - 2, z - 1)
    const tiles = []
    for (let a = 0; a < (vertical ? 2 : 4); a++) for (let b = 0; b < (vertical ? 4 : 2); b++) tiles.push([ci + a, cj + b])
    if (!tiles.every(([a, b]) => free(a, b))) return null
    const c = vertical ? C(ci + 0.5, cj + 1.5) : C(ci + 1.5, cj + 0.5)
    const car = addContainer('car', null, { tiles, x: c.x, z: c.z, rot })
    if (car && kind) car.model = kind
    return car
  }

  // ---------------------------------------------------------------- reachability
  const [ei, ej] = T(lv.evac.x, lv.evac.z)
  lv.reach = flood(grid, ei, ej)
  // drop containers nobody can get to
  for (const c of [...lv.containers]) {
    const ok = c.tiles.some(([i, j]) =>
      [
        [1, 0],
        [-1, 0],
        [0, 1],
        [0, -1],
      ].some(([di, dj]) => grid.open(i + di, j + dj) && lv.reach[grid.i(i + di, j + dj)]),
    )
    if (!ok) {
      for (const [i, j] of c.tiles) grid.set(i, j, 0, 0, null)
      c.gone = true
    }
  }
  lv.containers = lv.containers.filter((c) => !c.gone)
  lv.containers.forEach((c, k) => (c.id = k))
  lv.reach = flood(grid, ei, ej)
  // spawn spots
  for (let j = 1; j < H - 1; j++)
    for (let i = 1; i < W - 1; i++) {
      if (!free(i, j) || !lv.reach[grid.i(i, j)]) continue
      const c = C(i, j)
      if (Math.hypot(c.x - lv.evac.x, c.z - lv.evac.z) < 12) continue
      if (lv.roomAt[j * W + i] >= 0) lv.spawns.inside.push([i, j])
      else lv.spawns.outside.push([i, j])
    }
  lv.streetEnds = [T(x0 + 2, zRoad0 + roadW / 2), T(x1 - 3, zRoad0 + roadW / 2)]
  return lv
}

function flood(grid, si, sj) {
  const seen = new Uint8Array(grid.w * grid.h)
  if (!grid.open(si, sj)) {
    const n = grid.nearestOpen(si, sj, 6)
    if (!n) return seen
    si = n.x
    sj = n.z
  }
  const q = [[si, sj]]
  seen[grid.i(si, sj)] = 1
  while (q.length) {
    const [i, j] = q.pop()
    for (const [di, dj] of [
      [1, 0],
      [-1, 0],
      [0, 1],
      [0, -1],
    ]) {
      const ni = i + di
      const nj = j + dj
      if (!grid.open(ni, nj) || seen[grid.i(ni, nj)]) continue
      seen[grid.i(ni, nj)] = 1
      q.push([ni, nj])
    }
  }
  return seen
}

// Width of the street the lot faces.
export function streetWidth(city, lot) {
  if (lot.special) return 26
  const blk = lot.block
  const cx = lot.cx
  const cz = lot.cz
  if (lot.face === 'n' || lot.face === 's') {
    const j = lot.face === 'n' ? blk.j0 : blk.j1 + 1
    let i = 0
    while (i < city.NX - 2 && city.xs[i + 1] < cx) i++
    return city.hE[j]?.[i]?.w || 13
  }
  const i = lot.face === 'w' ? blk.i0 : blk.i1 + 1
  let j = 0
  while (j < city.NZ - 2 && city.zs[j + 1] < cz) j++
  return city.vE[i]?.[j]?.w || 13
}
export { lotToWorld, FACE_ROT }
