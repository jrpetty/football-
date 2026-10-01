// Supply runs: a procedurally generated location with rooms, lootable
// containers and zombies. Click a survivor, then click where to go.
import * as THREE from 'three'
import { Grid, BLOCK } from './grid.js'
import { gfx, Atmosphere, FX, M, G, tex, pickAt, groundAt, basicMat, isNight } from './gfx.js'
import { makeContainer, makeCar, makeDecor, makeTree, makeLamp, makeRock, makeBarrel, bake, part } from './models.js'
import { SurvivorAgent, ZombieAgent } from './agents.js'
import { LOCATIONS, CONTAINERS, ITEMS, RARITY, RES, zombieMix } from './data.js'
import { S, getS, gain, addItem, gainXP, killSurvivor, hour, day, completeGoal, log, survivorStats } from './state.js'
import { sfx } from './audio.js'
import { bus, h, rand, rint, pick, chance, weighted, shuffle, clamp, fmtTime } from './util.js'

export class Mission {
  constructor(game, loc, squadIds) {
    this.game = game
    this.loc = loc
    this.def = LOCATIONS[loc.type]
    this.level = loc.level
    this.mode = 'mission'
    this.scene = new THREE.Scene()
    this.atmo = new Atmosphere(this.scene)
    this.fx = new FX(this.scene)
    this.labels = gfx.labels
    this.squad = []
    this.zombies = []
    this.containers = []
    this.pickables = []
    this.haul = { res: {}, items: [] }
    this.paused = false
    this.elapsed = 0
    this.over = false
    this.selected = new Set()
    this.lamps = []
    this.build()
    const walkie = squadIds.some((id) => survivorStats(getS(id)).walkie)
    this.hordeIn = 130 + (walkie ? 45 : 0) - this.level * 8
    this.spawnT = 0
    this.hordeOn = false
    // Squad climbs out of the van.
    squadIds.forEach((id, i) => {
      const s = getS(id)
      s.status = 'mission'
      const p = this.grid.nearestOpen(this.evac.x + (i % 2) - 0.5, this.evac.z + Math.floor(i / 2) - 0.5, 4)
      const a = new SurvivorAgent(this, s, p.x + 0.5, p.z + 0.5)
      a.heading = Math.PI
      this.squad.push(a)
    })
    this.spawnZombies()
    this.select(this.squad[0])
    gfx.rig.setBounds(1, 1, this.grid.w - 1, this.grid.h - 1)
    const portrait = window.innerWidth < window.innerHeight
    gfx.rig.jump(portrait ? this.evac.x + 3 : (this.evac.x + this.W / 2) / 2, this.evac.z - (portrait ? 3 : 5), 27)
    gfx.rig.yaw = gfx.rig.yawGoal = Math.PI / 4
    this.buildHud()
    sfx('truck')
  }

  // ---------------------------------------------------------------- level gen
  build() {
    const D = this.def
    const [bw, bd] = D.size
    const W = bw + 18
    const H = bd + 17
    this.W = W
    this.H = H
    const grid = (this.grid = new Grid(W, H))
    const bx0 = Math.floor((W - bw) / 2)
    const bz0 = 3
    const bx1 = bx0 + bw - 1
    const bz1 = bz0 + bd - 1
    this.bld = { bx0, bz0, bx1, bz1 }
    const roadZ0 = H - 6
    this.roadZ0 = roadZ0
    // border
    for (let x = 0; x < W; x++) {
      grid.set(x, 0, BLOCK, 1)
      grid.set(x, H - 1, BLOCK, 1)
    }
    for (let z = 0; z < H; z++) {
      grid.set(0, z, BLOCK, 1)
      grid.set(W - 1, z, BLOCK, 1)
    }
    // walls: 1 = wall tile
    const wall = new Uint8Array(W * H)
    const setWall = (x, z) => {
      wall[z * W + x] = 1
      grid.set(x, z, BLOCK, 1)
    }
    for (let x = bx0; x <= bx1; x++) {
      setWall(x, bz0)
      setWall(x, bz1)
    }
    for (let z = bz0; z <= bz1; z++) {
      setWall(bx0, z)
      setWall(bx1, z)
    }
    const doors = []
    const openDoor = (x, z) => {
      wall[z * W + x] = 0
      grid.set(x, z, 0, 0)
      doors.push([x, z])
    }
    const wide = ['garage', 'warehouse', 'military'].includes(this.loc.type)
    const dmid = bx0 + Math.floor(bw / 2) - 1
    for (let i = 0; i < (wide ? 4 : 2); i++) openDoor(dmid - (wide ? 1 : 0) + i, bz1)
    if (chance(0.7)) openDoor(bx1, bz0 + rint(2, bd - 3))
    if (chance(0.5)) openDoor(bx0, bz0 + rint(2, bd - 3))
    if (bw > 14 && chance(0.6)) openDoor(bx0 + rint(2, bw - 3), bz0)

    // BSP rooms
    const rooms = []
    const aisles = !!D.aisles
    const split = (x0, z0, x1, z1, depth) => {
      const w = x1 - x0 + 1
      const d = z1 - z0 + 1
      const canX = w >= 8
      const canZ = d >= 7
      const want = depth < (aisles ? 1 : 4) && (canX || canZ) && (depth < 2 || chance(0.7))
      if (!want) {
        rooms.push({ x0, z0, x1, z1 })
        return
      }
      let vertical = canX && (!canZ || w > d * 1.1 || (w >= d * 0.9 && chance(0.5)))
      if (aisles) vertical = false
      if (vertical) {
        const sx = rint(x0 + 3, x1 - 3)
        for (let z = z0; z <= z1; z++) setWall(sx, z)
        const dz = rint(z0 + 1, z1 - 1)
        openDoor(sx, dz)
        if (d > 7 && chance(0.4)) openDoor(sx, dz === z0 + 1 ? z1 - 1 : z0 + 1)
        split(x0, z0, sx - 1, z1, depth + 1)
        split(sx + 1, z0, x1, z1, depth + 1)
      } else {
        // aisle stores: a back stock room along the rear wall
        const sz = aisles ? z0 + 3 : rint(z0 + 3, z1 - 3)
        for (let x = x0; x <= x1; x++) setWall(x, sz)
        const dx = rint(x0 + 1, x1 - 1)
        openDoor(dx, sz)
        if (w > 9 && chance(0.5)) openDoor(dx > x0 + w / 2 ? x0 + 1 : x1 - 1, sz)
        split(x0, z0, x1, sz - 1, depth + 1)
        split(x0, sz + 1, x1, z1, depth + 1)
      }
    }
    split(bx0 + 1, bz0 + 1, bx1 - 1, bz1 - 1, 0)
    this.rooms = rooms
    this.wall = wall
    this.doors = doors
    const nearDoor = (x, z) => doors.some(([dx, dz]) => Math.abs(dx - x) <= 1 && Math.abs(dz - z) <= 1)

    // ---- containers
    const want = []
    for (const [kind, w] of Object.entries(D.containers)) {
      const n = Math.floor(w) + (chance(w % 1) ? 1 : 0)
      for (let i = 0; i < n; i++) want.push(kind)
    }
    shuffle(want)
    const slots = []
    for (const r of rooms) {
      for (let x = r.x0; x <= r.x1; x++) {
        for (let z = r.z0; z <= r.z1; z++) {
          if (nearDoor(x, z)) continue
          let rot = null
          if (wall[(z - 1) * W + x]) rot = 0
          else if (wall[(z + 1) * W + x]) rot = Math.PI
          else if (wall[z * W + x - 1]) rot = Math.PI / 2
          else if (wall[z * W + x + 1]) rot = -Math.PI / 2
          if (rot !== null) slots.push({ x, z, rot, room: r })
        }
      }
    }
    shuffle(slots)
    // Aisle shelving in the main hall.
    if (aisles) {
      const hall = rooms.reduce((a, b) => ((a.x1 - a.x0) * (a.z1 - a.z0) > (b.x1 - b.x0) * (b.z1 - b.z0) ? a : b))
      const shelvesWanted = want.filter((k) => k === 'shelf').length
      let placed = 0
      for (let z = hall.z0 + 2; z <= hall.z1 - 3 && placed < shelvesWanted; z += 3) {
        for (let x = hall.x0 + 2; x <= hall.x1 - 2 && placed < shelvesWanted; x++) {
          if (nearDoor(x, z) || nearDoor(x, z + 1)) continue
          this.addContainer('shelf', x, z, 0)
          placed++
        }
      }
      for (let i = 0; i < placed; i++) want.splice(want.indexOf('shelf'), 1)
    }
    const insideCars = D.inside?.car || 0
    for (let i = 0; i < insideCars; i++) {
      const big = rooms.reduce((a, b) => ((a.x1 - a.x0) * (a.z1 - a.z0) > (b.x1 - b.x0) * (b.z1 - b.z0) ? a : b))
      const cx = rint(big.x0 + 2, Math.max(big.x0 + 2, big.x1 - 4))
      const cz = rint(big.z0 + 2, Math.max(big.z0 + 2, big.z1 - 3))
      if (grid.rectOpen(cx, cz, 4, 2) && !nearDoor(cx, cz)) this.addCar(cx, cz, true)
    }
    for (const kind of want) {
      const i = slots.findIndex((s) => grid.open(s.x, s.z) && !grid.owner[grid.i(s.x, s.z)])
      if (i < 0) break
      const s = slots.splice(i, 1)[0]
      this.addContainer(kind, s.x, s.z, s.rot)
    }
    // decor
    const decor = D.decor || []
    for (const kind of decor) {
      if (chance(0.25)) continue
      const i = slots.findIndex((s) => grid.open(s.x, s.z) && !grid.owner[grid.i(s.x, s.z)])
      if (i < 0) break
      const s = slots.splice(i, 1)[0]
      const g = makeDecor(kind)
      g.position.set(s.x + 0.5, 0, s.z + 0.5)
      g.rotation.y = s.rot
      g.scale.setScalar(kind === 'table' ? 0.8 : 0.55)
      this.decorGroup = this.decorGroup || new THREE.Group()
      this.decorGroup.add(g)
      grid.set(s.x, s.z, BLOCK, 0, 'decor')
    }

    // ---- outside
    const yardZ0 = bz1 + 1
    const yardZ1 = roadZ0 - 2
    // van + evac
    this.van = new THREE.Group()
    const vanBody = makeCar('#e8e2d0')
    vanBody.scale.set(1.1, 1.25, 1.2)
    this.van.add(vanBody)
    this.van.rotation.y = Math.PI / 2
    this.van.position.set(4.3, 0, roadZ0 + 2)
    grid.fillRect(2, roadZ0 + 1, 5, 2, BLOCK, 0, 'van')
    this.evac = { x: 9, z: roadZ0 + 2, r: 2.6 }
    const outside = { ...(D.outside || {}) }
    outside.car = (outside.car || 0) + rint(1, 2)
    outside.dumpster = 1
    outside.trash = rint(1, 2)
    // pumps in a row
    for (let i = 0; i < (outside.pump || 0); i++) {
      const px = bx0 + 2 + i * 3
      const pz = yardZ0 + 2
      if (grid.open(px, pz)) this.addContainer('pump', px, pz, Math.PI)
    }
    for (let i = 0; i < outside.car; i++) {
      for (let t = 0; t < 20; t++) {
        const onRoad = chance(0.5)
        const cx = rint(2, W - 7)
        const cz = onRoad ? roadZ0 + rint(0, 2) : rint(yardZ0 + 1, Math.max(yardZ0 + 1, yardZ1 - 1))
        if (Math.hypot(cx + 2 - this.evac.x, cz + 1 - this.evac.z) < 5) continue
        if (grid.rectOpen(cx - 1, cz - 1, 6, 4) && !nearDoor(cx, cz - 1) && !this.frontBlocked(cx, cz, 4, 2)) {
          this.addCar(cx, cz)
          break
        }
      }
    }
    for (let i = 0; i < outside.dumpster; i++) {
      const x = chance(0.5) ? bx0 - 3 : bx1 + 2
      const z = rint(bz0 + 2, bz1 - 1)
      if (grid.rectOpen(x, z, 2, 1)) this.addContainer('dumpster', x, z, Math.PI / 2 * (x < bx0 ? 1 : -1), 2)
    }
    for (let i = 0; i < outside.trash; i++) {
      const x = rint(2, W - 3)
      const z = yardZ1
      if (grid.open(x, z) && !grid.owner[grid.i(x, z)]) this.addContainer('trash', x, z, Math.PI)
    }
    this.yard = { yardZ0, yardZ1 }

    // ---- ensure everything is reachable from the evac point
    const reach = this.flood(this.evac.x, this.evac.z)
    for (const c of [...this.containers]) {
      if (!this.accessTile(c)) this.removeContainer(c)
    }
    this.reach = reach

    this.render3d()
  }
  frontBlocked(x, z, w, d) {
    // keep a lane in front of the building doors clear
    const { bz1 } = this.bld
    return this.doors.some(([dx, dz]) => dz === bz1 && dx >= x - 1 && dx <= x + w && z <= bz1 + 3)
  }
  flood(sx, sz) {
    const g = this.grid
    const seen = new Uint8Array(g.w * g.h)
    const q = [[Math.floor(sx), Math.floor(sz)]]
    seen[g.i(q[0][0], q[0][1])] = 1
    while (q.length) {
      const [x, z] = q.pop()
      for (const [dx, dz] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
        const nx = x + dx
        const nz = z + dz
        if (!g.open(nx, nz) || seen[g.i(nx, nz)]) continue
        seen[g.i(nx, nz)] = 1
        q.push([nx, nz])
      }
    }
    return seen
  }
  addContainer(kind, x, z, rot, len = 1) {
    const def = CONTAINERS[kind]
    const g = makeContainer(kind, this.level)
    const tiles = []
    for (let i = 0; i < len; i++) {
      const tx = Math.abs(Math.sin(rot)) > 0.5 ? x : x + i
      const tz = Math.abs(Math.sin(rot)) > 0.5 ? z + i : z
      tiles.push([tx, tz])
    }
    const cx = tiles.reduce((a, t) => a + t[0], 0) / len + 0.5
    const cz = tiles.reduce((a, t) => a + t[1], 0) / len + 0.5
    g.position.set(cx, 0, cz)
    g.rotation.y = rot
    const c = { id: this.containers.length, kind, def, tiles, x: cx, z: cz, group: g, searched: false, gone: false, locked: !!def.locked }
    g.traverse((o) => {
      if (o.isMesh) o.userData.pick = { type: 'container', c }
    })
    for (const [tx, tz] of tiles) this.grid.set(tx, tz, BLOCK, 0, c)
    this.containers.push(c)
    return c
  }
  addCar(x, z, inside = false) {
    const def = CONTAINERS.car
    const g = makeCar()
    g.rotation.y = Math.PI / 2 + (chance(0.5) ? Math.PI : 0) + rand(-0.08, 0.08)
    const tiles = []
    for (let i = 0; i < 4; i++) for (let j = 0; j < 2; j++) tiles.push([x + i, z + j])
    const c = { id: this.containers.length, kind: 'car', def, tiles, x: x + 2, z: z + 1, group: g, searched: false, gone: false, locked: false }
    g.position.set(x + 2, 0, z + 1)
    g.traverse((o) => {
      if (o.isMesh) o.userData.pick = { type: 'container', c }
    })
    for (const [tx, tz] of tiles) this.grid.set(tx, tz, BLOCK, 0, c)
    this.containers.push(c)
    return c
  }
  removeContainer(c) {
    c.gone = true
    for (const [tx, tz] of c.tiles) this.grid.set(tx, tz, 0, 0, null)
    c.group.parent?.remove(c.group)
    c.marker?.remove()
  }
  // Open tile next to a container that can be reached, closest to `agent`.
  accessTile(c, agent = null) {
    const g = this.grid
    const cand = []
    for (const [tx, tz] of c.tiles) {
      for (const [dx, dz] of [[0, 1], [0, -1], [1, 0], [-1, 0], [1, 1], [-1, 1], [1, -1], [-1, -1]]) {
        const x = tx + dx
        const z = tz + dz
        if (!g.open(x, z) || g.owner[g.i(x, z)]) continue
        if (this.reach && !this.reach[g.i(x, z)]) continue
        if (dx && dz && (!g.open(tx + dx, tz) || !g.open(tx, tz + dz))) {
          // diagonal only if not squeezing through walls
          if (!g.open(x, tz) && !g.open(tx, z)) continue
        }
        cand.push({ x: x + 0.5, z: z + 0.5, diag: dx && dz ? 1 : 0 })
      }
    }
    if (!cand.length) return null
    const ax = agent ? agent.pos.x : this.evac.x
    const az = agent ? agent.pos.z : this.evac.z
    cand.sort((a, b) => a.diag - b.diag || Math.hypot(a.x - ax, a.z - az) - Math.hypot(b.x - ax, b.z - az))
    return cand[0]
  }

  render3d() {
    const { W, H, grid, wall, scene } = this
    const D = this.def
    const statics = new THREE.Group()
    // ground
    const groundTex = tex(this.level >= 4 ? 'dirt' : 'grass').clone()
    groundTex.needsUpdate = true
    groundTex.repeat.set(W / 8, H / 8)
    const ground = new THREE.Mesh(new THREE.PlaneGeometry(W + 60, H + 60), new THREE.MeshStandardMaterial({ map: groundTex, roughness: 1 }))
    groundTex.repeat.set((W + 60) / 8, (H + 60) / 8)
    ground.rotation.x = -Math.PI / 2
    ground.position.set(W / 2, 0, H / 2)
    ground.receiveShadow = true
    scene.add(ground)
    // road + sidewalk
    const road = new THREE.Mesh(new THREE.PlaneGeometry(W + 60, 4), M('#ffffff', { map: 'asphalt' }))
    road.material = road.material.clone()
    road.material.map = tex('asphalt').clone()
    road.material.map.needsUpdate = true
    road.material.map.repeat.set((W + 60) / 6, 4 / 6)
    road.rotation.x = -Math.PI / 2
    road.position.set(W / 2, 0.01, this.roadZ0 + 2)
    road.receiveShadow = true
    scene.add(road)
    for (let x = -28; x < W + 28; x += 3) part(statics, G('box', 1.4, 0.01, 0.12), M('#d8b840', { rough: 0.6 }), x, 0.02, this.roadZ0 + 2, null, null, false)
    part(statics, G('box', W + 60, 0.1, 1), M('#8e8a82', { map: 'concrete' }), W / 2, 0.05, this.roadZ0 - 0.5, null, null, false)
    // yard paving for commercial lots
    if (!['house', 'apartment'].includes(this.loc.type)) {
      const lot = new THREE.Mesh(new THREE.PlaneGeometry(W - 2, this.roadZ0 - 1 - this.bld.bz1), M('#8e9092', { map: 'asphalt', rough: 0.95 }))
      lot.rotation.x = -Math.PI / 2
      lot.position.set(W / 2, 0.012, (this.bld.bz1 + 1 + this.roadZ0 - 1) / 2)
      lot.receiveShadow = true
      scene.add(lot)
    }
    // building floor
    const { bx0, bz0, bx1, bz1 } = this.bld
    const ft = tex(D.floor).clone()
    ft.needsUpdate = true
    ft.repeat.set((bx1 - bx0 + 1) / 2, (bz1 - bz0 + 1) / 2)
    const floor = new THREE.Mesh(new THREE.PlaneGeometry(bx1 - bx0 + 1, bz1 - bz0 + 1), new THREE.MeshStandardMaterial({ map: ft, roughness: 0.8 }))
    floor.rotation.x = -Math.PI / 2
    floor.position.set((bx0 + bx1 + 1) / 2, 0.02, (bz0 + bz1 + 1) / 2)
    floor.receiveShadow = true
    scene.add(floor)
    // walls: thin segments between neighbouring wall tiles
    const wallMat = M(D.wall, { rough: 0.9 })
    const capMat = M('#3a3632', { rough: 0.9 })
    const baseMat = M('#4a4540', { rough: 0.9 })
    const WH = 1.45
    const T = 0.24
    const isW = (x, z) => x >= 0 && z >= 0 && x < W && z < H && wall[z * W + x]
    for (let z = 0; z < H; z++) {
      for (let x = 0; x < W; x++) {
        if (!wall[z * W + x]) continue
        const cx = x + 0.5
        const cz = z + 0.5
        part(statics, G('box', T, WH, T), wallMat, cx, WH / 2, cz)
        part(statics, G('box', T + 0.04, 0.05, T + 0.04), capMat, cx, WH + 0.025, cz, null, null, false)
        if (isW(x + 1, z)) {
          part(statics, G('box', 1, WH, T), wallMat, cx + 0.5, WH / 2, cz)
          part(statics, G('box', 1, 0.05, T + 0.04), capMat, cx + 0.5, WH + 0.025, cz, null, null, false)
          part(statics, G('box', 1, 0.14, T + 0.03), baseMat, cx + 0.5, 0.07, cz, null, null, false)
        }
        if (isW(x, z + 1)) {
          part(statics, G('box', T, WH, 1), wallMat, cx, WH / 2, cz + 0.5)
          part(statics, G('box', T + 0.04, 0.05, 1), capMat, cx, WH + 0.025, cz + 0.5, null, null, false)
          part(statics, G('box', T + 0.03, 0.14, 1), baseMat, cx, 0.07, cz + 0.5, null, null, false)
        }
      }
    }
    // door frames
    for (const [x, z] of this.doors) {
      const horiz = isW(x - 1, z) || isW(x + 1, z) || this.doors.some(([a, b]) => b === z && Math.abs(a - x) === 1)
      part(statics, G('box', horiz ? 1 : 0.3, 0.02, horiz ? 0.3 : 1), M('#6a5a48'), x + 0.5, 0.03, z + 0.5, null, null, false)
    }
    // boundary: chain-link fence and neighbouring ruins
    const fenceMat = M('#b8bcc0', { map: 'chain', alphaTest: 0.5, metal: 0.4, rough: 0.5 })
    fenceMat.map.repeat.set(5, 8)
    const post = M('#6a6e72', { metal: 0.4 })
    for (let x = 0; x < W; x++) {
      if (this.roadZ0 <= 0) continue
      part(statics, G('box', 1, 1.6, 0.02), fenceMat, x + 0.5, 0.8, 0.5, null, null, false)
      part(statics, G('cyl', 0.03, 0.03, 1.7, 5), post, x, 0.85, 0.5)
    }
    for (let z = 0; z < this.roadZ0; z++) {
      for (const x of [0.5, W - 0.5]) {
        part(statics, G('box', 0.02, 1.6, 1), fenceMat, x, 0.8, z + 0.5, null, null, false)
        part(statics, G('cyl', 0.03, 0.03, 1.7, 5), post, x, 0.85, z)
      }
    }
    // silhouettes of other buildings beyond the fence
    for (let i = 0; i < 7; i++) {
      const w = rand(5, 10)
      const hgt = rand(4, 11)
      const x = rand(-6, W + 6)
      part(statics, G('box', w, hgt, rand(5, 8)), M(pick(['#6e675e', '#5e5a55', '#77705f', '#5a5048'])), x, hgt / 2, rand(-9, -5))
    }
    for (const side of [-1, 1]) {
      for (let i = 0; i < 3; i++) {
        const hgt = rand(4, 9)
        part(statics, G('box', rand(5, 7), hgt, rand(5, 8)), M(pick(['#6e675e', '#5e5a55', '#77705f'])), side < 0 ? rand(-9, -5) : W + rand(5, 9), hgt / 2, rand(2, this.roadZ0 - 3))
      }
    }
    // trees and debris in open ground
    for (let i = 0; i < 14; i++) {
      const x = rand(-12, W + 12)
      const z = rand(-12, H + 10)
      const inside = x > 0 && x < W && z > 0 && z < H
      if (inside) continue
      const t = makeTree(chance(0.4), rand(0.8, 1.2))
      t.position.set(x, 0, z)
      statics.add(t)
    }
    for (let i = 0; i < 8; i++) {
      const x = rint(2, W - 3)
      const z = rint(this.bld.bz1 + 2, this.roadZ0 - 2)
      if (!grid.open(x, z) || grid.owner[grid.i(x, z)]) continue
      const r = makeRock(0.5)
      r.position.set(x + rand(0.2, 0.8), 0, z + rand(0.2, 0.8))
      statics.add(r)
    }
    // street lamps
    for (let x = 6; x < W - 3; x += 12) {
      const l = makeLamp(isNight(hour()))
      l.position.set(x, 0, this.roadZ0 - 0.3)
      l.rotation.y = 0
      scene.add(l)
      this.lamps.push(l)
    }
    if (isNight(hour())) {
      for (const l of this.lamps.slice(0, 2)) {
        const pl = new THREE.PointLight('#ffcf8a', 14, 12, 1.8)
        pl.position.set(l.position.x, 2.9, l.position.z + 0.7)
        scene.add(pl)
      }
    }
    // evac marker
    const evacRing = new THREE.Mesh(new THREE.RingGeometry(this.evac.r - 0.15, this.evac.r, 48), basicMat('#6fd08a', { opacity: 0.7 }))
    evacRing.rotation.x = -Math.PI / 2
    evacRing.position.set(this.evac.x, 0.04, this.evac.z)
    scene.add(evacRing)
    const evacFill = new THREE.Mesh(new THREE.CircleGeometry(this.evac.r, 48), basicMat('#6fd08a', { opacity: 0.12 }))
    evacFill.rotation.x = -Math.PI / 2
    evacFill.position.set(this.evac.x, 0.035, this.evac.z)
    scene.add(evacFill)
    this.evacRing = evacRing
    const evLabel = h('div.evac-tag', 'EVAC')
    this.labels.add(evLabel, new THREE.Vector3(this.evac.x, 0.2, this.evac.z + this.evac.r + 0.3), { scene: this.scene })
    scene.add(this.van)

    if (this.decorGroup) statics.add(this.decorGroup)
    bake(statics)
    scene.add(statics)
    for (const c of this.containers) {
      scene.add(c.group)
      this.pickables.push(c.group)
      const mk = h('div.loot-mark', c.locked ? '🔒' : '')
      if (c.locked) mk.classList.add('locked')
      c.marker = this.labels.add(mk, new THREE.Vector3(c.x, c.kind === 'car' ? 1.9 : 2.1, c.z), { scene: this.scene })
    }
  }

  spawnZombies() {
    const n = 3 + this.level * 3 + rint(0, 2)
    const mix = zombieMix(this.level)
    const { bx0, bz0, bx1, bz1 } = this.bld
    for (let i = 0; i < n; i++) {
      const inside = chance(0.72)
      for (let t = 0; t < 40; t++) {
        const x = inside ? rint(bx0 + 1, bx1 - 1) : rint(2, this.W - 3)
        const z = inside ? rint(bz0 + 1, bz1 - 1) : rint(bz1 + 1, this.roadZ0 + 2)
        if (!this.grid.open(x, z) || this.grid.owner[this.grid.i(x, z)]) continue
        if (Math.hypot(x - this.evac.x, z - this.evac.z) < 10) continue
        const z0 = new ZombieAgent(this, weighted(mix).t, x + 0.5, z + 0.5, this.level)
        this.zombies.push(z0)
        break
      }
    }
  }
  spawnHordeZombie() {
    const mix = zombieMix(this.level + 1)
    const side = chance(0.5)
    const x = side ? 1.5 : this.W - 1.5
    const z = this.roadZ0 + rint(0, 3) + 0.5
    const zz = new ZombieAgent(this, weighted(mix).t, x, z, this.level)
    const alive = this.squad.filter((a) => !a.downed)
    if (alive.length) {
      zz.state = 'chase'
      zz.target = alive.reduce((a, b) => (zz.dist(a) < zz.dist(b) ? a : b))
    }
    this.zombies.push(zz)
  }

  // ---------------------------------------------------------------- world API used by agents
  noise(x, z, r, src = null) {
    for (const zz of this.zombies) {
      if (zz.dead || zz === src) continue
      if (Math.hypot(zz.pos.x - x, zz.pos.z - z) <= r) zz.alertTo(x, z)
    }
  }
  ammoLeft() {
    return S.res.ammo
  }
  useAmmo(n) {
    S.res.ammo = Math.max(0, S.res.ammo - n)
  }
  isNight() {
    return isNight(hour())
  }
  nearCamera(p) {
    return Math.hypot(p.x - gfx.rig.target.x, p.z - gfx.rig.target.z) < 16
  }
  workTime(agent, c, kind) {
    if (kind === 'search') {
      if (c.locked && !agent.st.picklock && !c.smash) return null
      return (c.def.time * (c.smash ? 2.2 : 1)) / agent.st.search
    }
    return (c.def.time * 1.8 + 2) / agent.st.dismantle
  }
  workTick(agent, wk, dt) {
    wk.noiseT = (wk.noiseT || 0) - dt
    wk.sfxT = (wk.sfxT || 0) - dt
    if (wk.noiseT <= 0) {
      wk.noiseT = 1
      const n = wk.kind === 'dismantle' ? 6 : wk.c.smash ? 12 : 1.8
      this.noise(agent.pos.x, agent.pos.z, n * agent.st.noiseMult)
    }
    if (wk.sfxT <= 0) {
      wk.sfxT = wk.kind === 'dismantle' || wk.c.smash ? 0.45 : 0.9
      if (this.nearCamera(agent.pos)) sfx(wk.kind === 'dismantle' || wk.c.smash ? 'dismantle' : 'search', 100)
      if (wk.kind === 'dismantle') this.fx.burst(new THREE.Vector3(wk.c.x, 0.8, wk.c.z), '#8a7a60', 3, 1.5, 0.5, 2)
    }
  }
  finishWork(agent, c, kind) {
    const pos = new THREE.Vector3(c.x, 1.8, c.z)
    if (kind === 'search') {
      c.searched = true
      c.marker?.remove()
      const found = rollLoot(c, this.level, agent.st.loot)
      if (!found.length) this.labels.float(this.scene, pos, 'Nothing useful', 'dim')
      let rare = false
      found.forEach((f, i) => {
        setTimeout(() => {
          if (f.item) {
            this.haul.items.push(f.item)
            const it = ITEMS[f.item]
            if (RARITY[it.rarity].rank >= 1) rare = true
            this.labels.float(this.scene, pos.clone().setY(1.8 + i * 0.35), `<b style="color:${RARITY[it.rarity].color}">${it.name}</b>`, 'item')
          } else {
            this.haul.res[f.r] = (this.haul.res[f.r] || 0) + f.n
            this.labels.float(this.scene, pos.clone().setY(1.8 + i * 0.35), `+${f.n} ${RES[f.r].name}`, 'res res-' + f.r)
          }
          this.updateHaul()
        }, i * 180)
      })
      sfx(found.some((f) => f.item && RARITY[ITEMS[f.item].rarity].rank >= 1) ? 'rare' : 'loot')
      gainXP(agent.data, 'scavenge', 3 + this.level)
      // dim the container so it reads as searched
      c.group.traverse((o) => {
        if (o.isMesh) {
          o.material = o.material.clone()
          o.material.color.multiplyScalar(0.55)
        }
      })
    } else {
      const yieldRes = {}
      for (const [k, [a, b]] of Object.entries(c.def.strip)) {
        const n = Math.round(rint(a, b) * (0.8 + agent.st.dismantle * 0.25))
        if (n > 0) yieldRes[k] = n
      }
      if (c.kind === 'car' && agent.st.carParts) yieldRes.parts = (yieldRes.parts || 0) + 2
      let i = 0
      for (const [k, n] of Object.entries(yieldRes)) {
        this.haul.res[k] = (this.haul.res[k] || 0) + n
        this.labels.float(this.scene, pos.clone().setY(1.6 + i++ * 0.35), `+${n} ${RES[k].name}`, 'res res-' + k)
      }
      // unsearched containers still spill their loot when torn apart
      if (!c.searched && !c.locked) {
        for (const f of rollLoot(c, this.level, agent.st.loot * 0.6)) {
          if (f.item) this.haul.items.push(f.item)
          else this.haul.res[f.r] = (this.haul.res[f.r] || 0) + f.n
        }
      }
      this.fx.burst(pos.clone().setY(0.6), '#7a6a52', 18, 3, 0.9, 3, 1.4)
      this.removeContainer(c)
      sfx('dismantle')
      gainXP(agent.data, 'build', 3)
      this.updateHaul()
    }
  }
  onKill() {
    S.stats.kills++
  }
  onDowned(a) {
    log(`${a.data.first} is down! Revive them within 40 seconds.`, 'bad')
    this.toast(`${a.data.first} is down — send someone to revive them`, 'bad')
  }
  onBledOut(a) {
    a.dead = true
    a.remove()
    this.squad = this.squad.filter((x) => x !== a)
    this.selected.delete(a)
    killSurvivor(a.data, `Killed on a supply run at ${this.loc.name}`)
    this.renderSquad()
    if (!this.squad.length) this.end('wiped')
    else if (this.squad.every((x) => x.downed)) this.end('wiped')
  }

  // ---------------------------------------------------------------- input
  select(a, add = false) {
    if (!add) {
      for (const s of this.selected) s.select(false)
      this.selected.clear()
    }
    if (a && !a.downed) {
      if (add && this.selected.has(a)) {
        a.select(false)
        this.selected.delete(a)
      } else {
        a.select(true)
        this.selected.add(a)
      }
    }
    this.renderSquad()
  }
  selectAll() {
    this.select(null)
    for (const a of this.squad) if (!a.downed) this.select(a, true)
  }
  pickables3d() {
    return [...this.squad.map((a) => a.root), ...this.zombies.filter((z) => !z.dead).map((z) => z.root), ...this.containers.filter((c) => !c.gone).map((c) => c.group)]
  }
  hitTest(x, y) {
    // Tag squad and zombie meshes lazily.
    for (const a of this.squad) if (!a.root.userData.pick) a.root.userData.pick = { type: 'survivor', a }
    for (const z of this.zombies) if (!z.root.userData.pick) z.root.userData.pick = { type: 'zombie', z }
    return pickAt(x, y, this.pickables3d())
  }
  onTap(x, y, e) {
    this.closeMenu()
    if (this.over) return
    const hit = this.hitTest(x, y)
    const command = e.button === 2
    if (hit?.pick.type === 'survivor') {
      const a = hit.pick.a
      if (a.downed) {
        const helper = this.pickHelper(a)
        if (helper) {
          helper.command({ type: 'revive', a })
          sfx('move')
        }
        return
      }
      if (!command) {
        this.select(a, e.shift)
        sfx('select')
        return
      }
    }
    if (hit?.pick.type === 'zombie') {
      const z = hit.pick.z
      const who = this.selected.size ? [...this.selected] : []
      for (const a of who) a.command({ type: 'attack', z })
      if (who.length) sfx('move')
      return
    }
    if (hit?.pick.type === 'container') {
      this.openMenu(hit.pick.c, x, y)
      return
    }
    const p = groundAt(x, y)
    if (!p) return
    const who = [...this.selected].filter((a) => !a.downed)
    if (!who.length) {
      this.toast('Select a survivor first (tap them, or their card below)')
      return
    }
    this.fx.ring(p)
    sfx('move')
    // spread a group around the target
    const used = new Set()
    who.forEach((a, i) => {
      const n = this.grid.nearestOpen(p.x + (i ? rand(-1, 1) : 0), p.z + (i ? rand(-1, 1) : 0), 5, (tx, tz) => !used.has(tx + ',' + tz) && !this.grid.owner[this.grid.i(tx, tz)])
      if (!n) return
      used.add(n.x + ',' + n.z)
      a.command({ type: 'move', x: i ? n.x + 0.5 : p.x, z: i ? n.z + 0.5 : p.z })
    })
  }
  onHover(x, y) {
    const hit = this.hitTest(x, y)
    const c = hit?.pick.type === 'container' ? hit.pick.c : null
    document.body.style.cursor = hit ? 'pointer' : ''
    if (c !== this.hoverC) {
      this.hoverC = c
      this.tip.hidden = !c
      if (c) this.tip.innerHTML = `<b>${c.def.name}</b><span>${c.searched ? 'Searched' : c.locked ? 'Locked' : 'Not searched'}</span>`
    }
    if (c) this.tip.style.transform = `translate(${x + 14}px, ${y + 12}px)`
  }
  onKey(e) {
    if (e.key === ' ') {
      this.paused = !this.paused
      this.renderPause()
      e.preventDefault()
    }
    const n = parseInt(e.key, 10)
    if (n >= 1 && n <= this.squad.length) this.select(this.squad[n - 1], e.shiftKey)
    if (e.key.toLowerCase() === 'r' && !e.metaKey) this.selectAll()
    if (e.key === 'Escape') this.closeMenu()
  }
  // Survivor to send for a job: a selected one, else the nearest free one.
  pickHelper(target, need = null) {
    let pool = [...this.selected].filter((a) => !a.downed && a !== target)
    if (need) pool = pool.filter(need)
    if (!pool.length) {
      pool = this.squad.filter((a) => !a.downed && a !== target && (!need || need(a)))
      pool.sort((a, b) => Math.hypot(a.pos.x - target.x, a.pos.z - target.z) - Math.hypot(b.pos.x - target.x, b.pos.z - target.z))
      pool = pool.slice(0, 1)
      if (target.pos) pool.sort((a, b) => a.dist(target) - b.dist(target))
    }
    return pool[0] || null
  }
  openMenu(c, x, y) {
    const menu = this.menu
    menu.innerHTML = ''
    const tgt = { x: c.x, z: c.z }
    const helper = this.pickHelper(tgt)
    const title = h('div.cm-title', h('b', c.def.name), h('span', c.searched ? 'Already searched' : c.locked ? 'Locked' : `Level ${this.level} loot`))
    menu.append(title)
    const who = helper ? helper.data.first : 'nobody'
    const btn = (label, sub, fn, disabled = false) =>
      h('button.cm-btn', { disabled, onclick: (e) => (e.stopPropagation(), fn(), this.closeMenu()) }, h('span', label), h('small', sub))
    if (!c.searched) {
      if (c.locked) {
        const picker = this.pickHelper(tgt, (a) => a.st.picklock)
        menu.append(
          btn('Pick the lock', picker ? `${picker.data.first} · quiet` : 'Needs lockpicks or an Ex-Con', () => {
            c.smash = false
            picker.command({ type: 'search', c })
            sfx('move')
          }, !picker),
        )
        menu.append(
          btn('Smash it open', helper ? `${who} · very loud` : '', () => {
            c.smash = true
            helper.command({ type: 'search', c })
            sfx('move')
          }, !helper),
        )
      } else {
        const t = helper ? this.workTime(helper, c, 'search') : c.def.time
        menu.append(btn('Search', `${who} · ${t.toFixed(1)}s · quiet`, () => (helper.command({ type: 'search', c }), sfx('move')), !helper))
      }
    }
    const strip = Object.keys(c.def.strip).map((k) => RES[k].name.toLowerCase()).join(', ')
    const dt = helper ? this.workTime(helper, c, 'dismantle') : c.def.time * 2
    menu.append(btn('Break it apart', `${who} · ${dt.toFixed(1)}s · loud · ${strip}`, () => (helper.command({ type: 'dismantle', c }), sfx('move')), !helper))
    menu.hidden = false
    const r = menu.getBoundingClientRect()
    const mx = Math.min(x + 10, window.innerWidth - r.width - 10)
    const my = Math.min(y + 10, window.innerHeight - r.height - 10)
    menu.style.transform = `translate(${Math.max(10, mx)}px, ${Math.max(10, my)}px)`
    sfx('click')
  }
  closeMenu() {
    if (this.menu) this.menu.hidden = true
  }

  // ---------------------------------------------------------------- loop
  update(dt) {
    if (this.over) return
    if (!this.paused) {
      this.elapsed += dt
      for (const a of this.squad) a.update(dt)
      for (const z of this.zombies) z.update(dt)
      const all = [...this.squad, ...this.zombies.filter((z) => !z.dead)]
      for (const a of all) a.separate(dt, all)
      this.zombies = this.zombies.filter((z) => {
        if (z.dead && z.deadT > 5) {
          z.remove()
          return false
        }
        return true
      })
      // horde
      if (!this.hordeOn && this.elapsed >= this.hordeIn) {
        this.hordeOn = true
        sfx('alarm')
        this.toast('The horde has found you. Get back to the van!', 'bad')
      }
      if (this.hordeOn) {
        this.spawnT -= dt
        if (this.spawnT <= 0) {
          this.spawnT = Math.max(2.5, 8.5 - this.level) * rand(0.8, 1.2)
          this.spawnHordeZombie()
          if (chance(0.3 + this.level * 0.08)) this.spawnHordeZombie()
        }
      }
      if (this.squad.length && this.squad.every((a) => a.downed)) this.end('wiped')
    } else {
      for (const a of this.squad) a.sync()
    }
    this.fx.update(this.paused ? 0 : dt)
    this.atmo.set(hour(), gfx.rig.target, gfx.rig.dist)
    const t = performance.now() / 1000
    this.evacRing.material.opacity = 0.45 + Math.sin(t * 3) * 0.25
    this.updateHud()
  }

  // ---------------------------------------------------------------- HUD
  buildHud() {
    const root = (this.hud = h('div.mhud'))
    this.tip = h('div.tip', { hidden: true })
    this.menu = h('div.cmenu', { hidden: true })
    this.toastEl = h('div.mtoast')
    this.timerEl = h('div.htimer')
    this.haulEl = h('div.haul')
    this.ammoEl = h('span.ammo')
    this.pauseBtn = h('button.btn.ghost', { onclick: () => ((this.paused = !this.paused), this.renderPause()) }, 'Pause')
    this.extractBtn = h('button.btn.go', { onclick: () => this.tryExtract() }, 'Extract')
    const lvlDots = h('span.lvl', `L${this.level}`)
    lvlDots.style.setProperty('--c', ['#7cc36a', '#c8c64a', '#e8a33d', '#e36b3a', '#d8384a'][this.level - 1])
    root.append(
      h('div.mtop', h('div.mloc', lvlDots, h('div', h('b', this.loc.name), h('small', this.def.name))), this.timerEl, h('div.mright', this.ammoEl, this.pauseBtn)),
      this.haulEl,
      h('div.mbottom', (this.squadEl = h('div.squad')), h('div.mactions', h('button.btn.ghost', { onclick: () => this.selectAll() }, 'Select all'), this.extractBtn)),
      (this.helpEl = h('div.mhelp', 'Tap a survivor, then tap the ground to move or a container to loot. They hold their ground and fight whatever comes close.')),
      this.toastEl,
      this.menu,
      this.tip,
      (this.pauseEl = h('div.pausebanner', { hidden: true }, 'PAUSED · Space to resume')),
    )
    document.getElementById('hud').appendChild(root)
    setTimeout(() => this.helpEl?.remove(), 30000)
    this.renderSquad()
    this.updateHaul()
  }
  renderPause() {
    this.pauseEl.hidden = !this.paused
    this.pauseBtn.textContent = this.paused ? 'Resume' : 'Pause'
  }
  renderSquad() {
    if (!this.squadEl) return
    this.squadEl.innerHTML = ''
    this.cards = new Map()
    this.squad.forEach((a, i) => {
      const bar = h('i')
      const img = h('img', { src: this.game.portrait(a.data), alt: '' })
      const status = h('small.st')
      const card = h(
        'button.scard' + (a.selected ? '.sel' : ''),
        {
          onclick: (e) => {
            this.select(a, e.shiftKey)
            sfx('select')
          },
          ondblclick: () => gfx.rig.focus(a.pos.x, a.pos.z),
        },
        h('span.key', String(i + 1)),
        img,
        h('div.sc-info', h('b', a.data.first), status, h('div.hpbar', bar)),
      )
      this.cards.set(a, { card, bar, status })
      this.squadEl.append(card)
    })
  }
  updateHaul() {
    const parts = []
    for (const [k, v] of Object.entries(this.haul.res)) if (v > 0) parts.push(h('span.hres', { style: { '--c': RES[k].color } }, `${v} ${RES[k].name}`))
    for (const id of this.haul.items) parts.push(h('span.hitem', { style: { '--c': RARITY[ITEMS[id].rarity].color } }, ITEMS[id].name))
    this.haulEl.innerHTML = ''
    this.haulEl.append(h('b', 'Haul'), ...(parts.length ? parts : [h('span.dim', 'Nothing yet')]))
  }
  updateHud() {
    const left = this.hordeIn - this.elapsed
    this.timerEl.className = 'htimer' + (this.hordeOn ? ' on' : left < 30 ? ' warn' : '')
    this.timerEl.innerHTML = this.hordeOn ? '<b>HORDE INCOMING</b><small>Get to the van</small>' : `<small>Horde arrives in</small><b>${fmtTime(left)}</b>`
    this.ammoEl.textContent = `Ammo ${Math.floor(S.res.ammo)}`
    for (const [a, { card, bar, status }] of this.cards || []) {
      bar.style.width = `${clamp(a.hp / a.maxHp, 0, 1) * 100}%`
      card.classList.toggle('sel', a.selected)
      card.classList.toggle('down', a.downed)
      const m = a.lastMode
      const st = a.downed
        ? `DOWN · ${Math.ceil(a.bleed)}s`
        : a.work
          ? a.work.kind === 'search' ? 'Searching' : 'Dismantling'
          : a.order?.type === 'revive' ? 'Reviving'
          : m === 'attack' || m === 'shoot' ? 'Fighting'
          : m === 'run' || m === 'walk' ? (a.order?.type === 'search' || a.order?.type === 'dismantle' ? 'Heading to loot' : 'Moving')
          : 'Holding'
      if (status.textContent !== st) status.textContent = st
    }
    const standing = this.squad.filter((a) => !a.downed)
    const inZone = standing.filter((a) => Math.hypot(a.pos.x - this.evac.x, a.pos.z - this.evac.z) <= this.evac.r + 0.2)
    const ready = standing.length > 0 && inZone.length === standing.length
    this.extractBtn.disabled = !ready
    this.extractBtn.textContent = ready ? 'Extract' : `Extract (${inZone.length}/${standing.length} at van)`
  }
  toast(msg, kind = '') {
    const t = h('div.mt', msg)
    if (kind) t.classList.add(kind)
    this.toastEl.prepend(t)
    setTimeout(() => t.classList.add('out'), 3800)
    setTimeout(() => t.remove(), 4400)
  }
  tryExtract() {
    const downed = this.squad.filter((a) => a.downed)
    if (downed.length) {
      this.game.ui.confirm(
        `Leave ${downed.map((a) => a.data.first).join(' and ')} behind?`,
        `${downed.length > 1 ? 'They are' : 'They are'} down and will not survive. Revive them first to bring everyone home.`,
        'Leave them',
        () => {
          for (const a of downed) killSurvivor(a.data, `Left behind at ${this.loc.name}`)
          this.squad = this.squad.filter((a) => !a.downed)
          this.end('extracted')
        },
      )
      return
    }
    this.end('extracted')
  }

  end(result) {
    if (this.over) return
    this.over = true
    const report = { result, loc: this.loc, haul: this.haul, lost: {}, survivors: [], dead: [], injured: [] }
    const L = this.level
    if (result === 'extracted') {
      report.lost = gain(this.haul.res)
      for (const id of this.haul.items) addItem(id)
      S.looted[this.loc.id] = day() + 3
      S.stats.runs++
      completeGoal('firstRun')
      if (L >= 3) completeGoal('loot3')
      if (L >= 5) completeGoal('loot5')
      for (const a of this.squad) {
        const d = a.data
        d.hp = Math.max(1, a.hp)
        d.runs++
        d.status = d.hp < a.maxHp * 0.3 ? 'injured' : 'ok'
        if (d.status === 'injured') report.injured.push(d.first)
        report.survivors.push(d.first)
      }
      log(`The squad returned from ${this.loc.name}.`, 'good')
    } else {
      // Squad wiped: some crawl home, some don't.
      for (const a of this.squad) {
        const d = a.data
        if (chance(0.5)) {
          d.hp = 1
          d.status = 'injured'
          report.injured.push(d.first)
        } else {
          killSurvivor(d, `Killed on a supply run at ${this.loc.name}`)
          report.dead.push(d.first)
        }
      }
      log(`The run to ${this.loc.name} went badly wrong.`, 'bad')
    }
    report.dead.push(...S.stats.memorial.filter((m) => m.cause.includes(this.loc.name) && !report.dead.includes(m.name.split(' ')[0])).map((m) => m.name.split(' ')[0]))
    this.game.endMission(report)
  }

  dispose() {
    for (const a of this.squad) a.remove()
    for (const z of this.zombies) z.remove()
    for (const c of this.containers) c.marker?.remove()
    this.labels.clearScene(this.scene)
    this.hud?.remove()
    document.body.style.cursor = ''
    this.scene.traverse((o) => {
      if (o.isMesh && o.geometry && !o.geometry.parameters) o.geometry.dispose()
    })
  }
}

// Roll a container's loot table.
export function rollLoot(c, level, lootMult = 1) {
  const def = c.def
  let rolls = rint(def.rolls[0], def.rolls[1])
  if (chance(lootMult - 1)) rolls++
  const out = []
  const qMult = (1 + 0.3 * (level - 1)) * lootMult
  for (let i = 0; i < rolls; i++) {
    // Better items show up more often in dangerous places.
    const pool = def.pool.map((e) => (e.i ? { ...e, w: e.w * (1 + (level - 1) * 0.35 * (RARITY[ITEMS[e.i].rarity].rank + 1) * 0.5) } : e))
    const e = weighted(pool)
    if (e.i) out.push({ item: e.i })
    else {
      const n = Math.max(1, Math.round(rint(e.n[0], e.n[1]) * qMult))
      const same = out.find((o) => o.r === e.r)
      if (same) same.n += n
      else out.push({ r: e.r, n })
    }
  }
  return out
}
