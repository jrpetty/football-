// Supply runs, played at street level. The chosen location is rebuilt from
// its city lot: rooms with their furniture and lootable fittings, the yard,
// the street where the van waits, the neighbours behind their fences. Walls
// facing the camera are cut away so you can see inside, and the cut follows
// the camera around. Select survivors, right-click to move, search or tear
// fittings apart; carry what you find back to the van; get out before the
// horde arrives.
import * as THREE from 'three'
import { view, screenRay, groundAt } from '../render/view.js'
import { Atmosphere, nightFactor, isNight } from '../render/sky.js'
import { FX, makeFlame, tickFlames, ringTex } from '../render/fx.js'
import { Splat, Terrain, wind } from '../render/terrain.js'
import { mat, setNightGlow, cloneMat } from '../render/materials.js'
import { Builder, seeded } from '../models/kit.js'
import { CONTAINER_MODELS, DECOR_MODELS, addDecor } from '../models/furniture.js'
import { carModel, vanModel, pickupModel } from '../models/vehicles.js'
import { pineModel, broadleafModel } from '../models/nature.js'
import { throwableModel } from '../models/weapons.js'
import { plateMat } from '../models/detail.js'
import { InstSet, PropList, mapTreeModel, mapVehicleModel, mapPropModel } from '../models/citykit.js'
import * as CB from '../models/citybuildings.js'
import { locationModel } from '../models/citylandmarks.js'
import { SurvivorAgent, ZombieAgent } from '../world/agents.js'
import { genLevel } from '../world/levelgen.js'
import { OffsetGrid } from '../core/grid.js'
import { FACE_ROT, SIDEWALK, lotToWorld } from '../world/city.js'
import { LOCATIONS, CONTAINERS, ITEMS, RARITY, RES, QUALITY, zombieMix, LEVEL_COLORS, UTILITIES } from '../game/data.js'
import { S, getS, gain, addItem, gainXP, killSurvivor, hour, day, completeGoal, log, survivorStats, makeSurvivor, addMoraleEvent, researchDone } from '../game/state.js'
import { scheduleRaid } from '../game/economy.js'
import { sfx, setAmbience } from '../core/audio.js'
import { h, rand, rint, pick, chance, weighted, clamp, fmtTime, bus, fmt } from '../core/util.js'
import { icon } from '../ui/icons.js'
import { resIcon } from '../ui/common.js'
import { infectedRange } from '../ui/mappanel.js'
import { VisionMixin } from './missionvision.js'
import { TrapsMixin } from './missiontraps.js'

const TAU = Math.PI * 2
// How much room things take in a pack.
const LOAD = { food: 1, water: 1, wood: 1, scrap: 1, metal: 1.5, cloth: 0.5, parts: 0.5, electronics: 0.5, chemicals: 1, gunpowder: 0.5, fuel: 1, pammo: 0.02, rammo: 0.03, shells: 0.05, meds: 0.3, medkit: 1, molotov: 1, pipebomb: 1, noisemaker: 0.5, module: 2, cash: 0, steel: 1.5, wiring: 0.5, rubber: 0.8, circuits: 0.4, motors: 2, coils: 1, cells: 1, amps: 2, schematic: 0.2, specimen: 0.3, core: 1, antiviral: 0.3 }
const ITEM_LOAD = 4
const CH = { fridge: 1.9, cabinet: 2.1, counter: 1.2, wardrobe: 2.1, dresser: 1.3, desk: 1.2, filing: 1.4, bookshelf: 1.95, trash: 1.0, shelf: 1.8, register: 1.4, toolrack: 1.9, medcab: 1.8, locker: 1.95, gunlocker: 1.8, safe: 0.9, crate: 1.1, pallet: 2.6, milcrate: 1.0, server: 2.0, tv: 1.5, chemshelf: 2.0, toolchest: 1.15, firelocker: 1.95, dumpster: 1.3, pump: 1.9, shed: 2.3, car: 1.5 }
const WALL_EXT = 3.1
const WALL_INT = 2.35
const CUT_Y = 1.05
const T_WALL = 0.22
const EXT_MAT = { siding: 'siding', brick: 'brick', concrete: 'concrete', corrugated: 'corrugated' }

// Roll a container's loot table.
export function rollLoot(def, level, lootMult = 1) {
  let rolls = rint(def.rolls[0], def.rolls[1])
  if (chance(lootMult - 1)) rolls++
  const out = []
  const qMult = (1 + 0.3 * (level - 1)) * lootMult
  for (let i = 0; i < rolls; i++) {
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
function lootQuality(level) {
  const r = Math.random()
  if (r < 0.02 + level * 0.012) return 3
  if (r < 0.12 + level * 0.04) return 2
  if (r < 0.42 - level * 0.04) return 0
  return 1
}

// Exterior walls facing the camera drop to waist height. The test runs per
// vertex: which outside wall of which building it sits on, and whether that
// wall faces the camera.
function cutaway(base, U) {
  return cloneMat(base, (sh) => {
    sh.uniforms.uCutR = U.rects
    sh.uniforms.uCutN = U.n
    sh.uniforms.uCutDir = U.dir
    sh.uniforms.uCutY = U.y
    sh.vertexShader = sh.vertexShader.replace('#include <common>', '#include <common>\nuniform vec4 uCutR[8];\nuniform int uCutN;\nuniform vec2 uCutDir;\nuniform float uCutY;').replace(
      '#include <begin_vertex>',
      `#include <begin_vertex>
{
  vec4 cwp = modelMatrix * vec4(transformed, 1.0);
  float cut = 0.0;
  for (int k = 0; k < 8; k++) {
    if (k >= uCutN) break;
    vec4 R = uCutR[k];
    vec2 d = cwp.xz - R.xy;
    if (abs(d.x) > R.z + 1.3 || abs(d.y) > R.w + 1.3) continue;
    vec2 n = vec2(0.0);
    if (abs(abs(d.x) - R.z) < 1.3) n.x = sign(d.x);
    if (abs(abs(d.y) - R.w) < 1.3) n.y = sign(d.y);
    float f = max(n.x * uCutDir.x, n.y * uCutDir.y);
    cut = max(cut, smoothstep(0.12, 0.35, f));
  }
  if (cut > 0.0 && transformed.y > uCutY) transformed.y = mix(transformed.y, uCutY, cut);
}`,
    )
  }, '-cut')
}

export class Mission {
  constructor(game, loc, ids, loadout = {}) {
    this.game = game
    this.loc = loc
    this.def = LOCATIONS[loc.type]
    this.level = loc.level
    this.loadout = loadout
    this.mode = 'mission'
    this.scene = new THREE.Scene()
    this.atmo = new Atmosphere(this.scene, game.pipe.renderer, { shadowSize: game.pipe.Q.shadow })
    this.fx = new FX(this.scene)
    this.squad = []
    this.zombies = []
    this.selected = new Set()
    this.paused = false
    this.over = false
    this.elapsed = 0
    this.time = 0
    this.van = { res: {}, items: [] }
    this.fires = []
    this.lures = []
    this.throwing = null
    this.lv = genLevel(game.city, loc)
    // agents walk in the lot's frame; the grid answers in its own tiles
    this.grid = new OffsetGrid(this.lv.grid, this.lv.x0, this.lv.z0)
    this.event = (S.events || []).find((e) => e.locId === loc.id && e.expires > S.time) || null
    this.buildWorld()
    // the squad climbs out of the van
    const E = this.lv.evac
    ids.forEach((id, k) => {
      const s = getS(id)
      if (!s) return
      s.status = 'mission'
      const n = this.lv.grid.nearestOpen(E.x - this.lv.x0 + (k % 2) * 1.2 - 0.6, E.z - this.lv.z0 + Math.floor(k / 2) * 1.2 - 0.6, 4)
      const a = new SurvivorAgent(this, s, n.x + this.lv.x0 + 0.5, n.z + this.lv.z0 + 0.5)
      a.heading = Math.PI
      a.pack = { res: {}, items: [], load: 0 }
      this.squad.push(a)
    })
    this.takeUtilities()
    this.spawnInitial()
    this.placeTraps()
    this.setupEvent()
    const walkie = this.squad.some((a) => a.st.walkie)
    this.hordeIn = clamp(175 - this.level * 13, 95, 165) + (walkie ? 45 : 0) + (loadout.van ? 0 : 25)
    this.hordeOn = false
    this.spawnT = 0
    view.rig.minDist = 9
    view.rig.maxDist = 52
    view.rig.pitchCfg = [0.62, 0.92, 34]
    view.rig.setBounds(this.lv.x0 + 4, this.lv.z0 + 4, this.lv.x0 + this.lv.W - 4, this.lv.z0 + this.lv.H - 4)
    view.camera.far = 600
    view.camera.near = 0.5
    view.camera.updateProjectionMatrix()
    view.rig.jump((E.x + this.lv.bld.cx) / 2, E.z - 6, 30)
    view.rig.yaw = view.rig.yawGoal = 0.35
    this.buildHud()
    this.setupVision()
    this.select(this.squad[0])
    sfx('truck')
    log(`The squad reached ${loc.name}.`, 'story')
  }
  get rightClickCommands() {
    return true
  }
  // Ground height under a point: building floors, raised sidewalks.
  floorY(x, z) {
    const lv = this.lv
    const [i, j] = this.tile(x, z)
    for (const B of lv.buildings) if (i >= B.i0 && i <= B.i1 && j >= B.j0 && j <= B.j1) return 0.07
    if ((z >= lv.zFront && z < lv.zRoad0) || (z >= lv.zRoad1 && z < lv.zFar)) return 0.15
    return 0
  }
  // tile <-> world helpers (world = the lot's own frame)
  tile(x, z) {
    return [Math.floor(x - this.lv.x0), Math.floor(z - this.lv.z0)]
  }
  center(i, j) {
    return { x: this.lv.x0 + i + 0.5, z: this.lv.z0 + j + 0.5 }
  }

  // ---------------------------------------------------------------- world
  buildWorld() {
    const lv = this.lv
    const Lx0 = lv.x0
    const Lz0 = lv.z0
    // building bounds for the cutaway
    const rects = []
    for (const B of lv.buildings) {
      const cx = Lx0 + (B.i0 + B.i1 + 1) / 2
      const cz = Lz0 + (B.j0 + B.j1 + 1) / 2
      B.cx = cx
      B.cz = cz
      B.hx = (B.i1 - B.i0) / 2
      B.hz = (B.j1 - B.j0) / 2
      rects.push(new THREE.Vector4(cx, cz, B.hx, B.hz))
    }
    while (rects.length < 8) rects.push(new THREE.Vector4(1e5, 1e5, 0, 0))
    this.cutU = { rects: { value: rects }, n: { value: Math.min(8, lv.buildings.length) }, dir: { value: new THREE.Vector2(0, 1) }, y: { value: CUT_Y } }
    this.cutMats = new Map()
    this.buildGround()
    this.buildStreet()
    this.buildBuilding()
    this.buildYard()
    this.buildNeighbours()
    this.buildContainers()
    this.buildVan()
  }
  cutMat(key) {
    if (!this.cutMats.has(key)) this.cutMats.set(key, cutaway(mat(key), this.cutU))
    return this.cutMats.get(key)
  }
  buildGround() {
    const lv = this.lv
    const size = Math.max(lv.W, lv.H) + 120
    const cx = lv.x0 + lv.W / 2
    const cz = lv.z0 + lv.H / 2
    const sp = new Splat(cx - size / 2, cz - size / 2, size, 0.5)
    this.splat = sp
    // worn yards and dirt by the fences
    for (let k = 0; k < 60; k++) sp.circle(lv.x0 + Math.random() * lv.W, lv.z0 + Math.random() * lv.H, 1 + Math.random() * 3, Math.random() < 0.6 ? 2 : 0, 0.5 + Math.random() * 0.3, 2, 1)
    sp.rect(-lv.fw / 2 - 0.5, -lv.fd / 2 - 0.5, -lv.fw / 2 + 1.2, lv.zFront, 0, 0.6, 1.5, 0.8)
    sp.rect(lv.fw / 2 - 1.2, -lv.fd / 2 - 0.5, lv.fw / 2 + 0.5, lv.zFront, 0, 0.6, 1.5, 0.8)
    for (const p of lv.pave) if (p.mat === 'gravel') sp.rect(p.x0, p.z0, p.x1, p.z1, 1, 1, 1, 0.5)
    sp.upload()
    this.terrain = new Terrain(this.scene, { cx, cz, size, segs: 160, splat: sp, height: (x, z) => {
      const dx = Math.max(lv.x0 - x, 0, x - (lv.x0 + lv.W))
      const dz = Math.max(lv.z0 - z, 0, z - (lv.z0 + lv.H))
      const d = Math.hypot(dx, dz)
      return d > 0 ? Math.min(1, d / 30) * (Math.sin(x * 0.11) * Math.cos(z * 0.09) * 1.2 + 1.4) : 0
    } })
  }
  // The street out front: asphalt, curbs, sidewalks, markings, lamps.
  buildStreet() {
    const lv = this.lv
    const b = new Builder()
    const X0 = lv.x0 - 40
    const X1 = lv.x0 + lv.W + 40
    const len = X1 - X0
    const xm = (X0 + X1) / 2
    b.box(len, 0.1, lv.roadW, { mat: 'road', color: '#8e8c88', x: xm, y: -0.03, z: (lv.zRoad0 + lv.zRoad1) / 2, ao: 0, shadow: false })
    for (const [z0, z1] of [
      [lv.zFront, lv.zRoad0],
      [lv.zRoad1, lv.zFar],
    ]) {
      b.box(len, 0.18, z1 - z0, { mat: 'concrete', color: '#b4b0a8', x: xm, y: 0.06, z: (z0 + z1) / 2, ao: 0, shadow: false })
      for (let x = X0; x < X1; x += 1.6) b.box(0.03, 0.01, z1 - z0 - 0.3, { mat: 'plain', color: '#7a766e', x, y: 0.152, z: (z0 + z1) / 2, ao: 0, shadow: false })
      const curb = z0 === lv.zFront ? z1 : z0
      b.box(len, 0.2, 0.22, { mat: 'concrete', color: '#c8c4bc', x: xm, y: 0.05, z: curb, ao: 0 })
    }
    // markings
    const zc = (lv.zRoad0 + lv.zRoad1) / 2
    for (let x = X0; x < X1; x += 6) b.box(3, 0.01, 0.13, { mat: 'plain', color: '#b0903a', x, y: 0.025, z: zc, shadow: false, ao: 0 })
    for (const s of [-1, 1]) b.box(len, 0.01, 0.1, { mat: 'plain', color: '#9e9a90', x: xm, y: 0.025, z: zc + s * (lv.roadW / 2 - 0.6), shadow: false, ao: 0 })
    // patches, cracks, manholes, drains
    for (let k = 0; k < 40; k++) {
      const x = X0 + Math.random() * len
      const z = lv.zRoad0 + 0.6 + Math.random() * (lv.roadW - 1.2)
      const r = Math.random()
      if (r < 0.45) b.box(0.6 + Math.random() * 2, 0.02, 0.5 + Math.random() * 1.4, { mat: 'road', color: '#6a6864', x, y: 0.024, z, ry: Math.random() * 3, shadow: false, ao: 0 })
      else if (r < 0.55) b.cyl(0.42, 0.42, 0.01, { mat: 'metal', color: '#4a4a48', x, y: 0.026, z, seg: 14, shadow: false })
      else if (r < 0.8) b.box(0.08, 0.02, 2 + Math.random() * 4, { mat: 'plain', color: '#2e2e2c', x, y: 0.0255, z, ry: Math.random() * 3, shadow: false, ao: 0 })
      else b.box(0.3, 0.1, 0.3, { mat: 'concrete', color: '#8a867e', x, y: 0.06, z, rx: Math.random(), ry: Math.random() * 3 })
    }
    for (let x = X0 + 8; x < X1; x += 22) b.box(0.8, 0.04, 0.4, { mat: 'steel', color: '#3a3c3a', x, y: 0.03, z: lv.zRoad0 + 0.3, shadow: false })
    this.addStatic(b)
    // street lamps (dark: the grid is down)
    const pb = new Builder()
    for (const p of lv.props) {
      if (p.kind === 'lamp') {
        pb.at({ x: p.x, z: p.z, ry: p.ry }, () => {
          pb.cyl(0.07, 0.11, 6.4, { mat: 'paint', color: '#4a4e52', y: 3.2, seg: 8 })
          pb.cyl(0.14, 0.16, 0.6, { mat: 'paint', color: '#4a4e52', y: 0.3, seg: 8 })
          pb.beam([0, 6.2, 0], [0, 6.6, 1.5], 0.07, 0.07, { mat: 'paint', color: '#4a4e52' })
          pb.box(0.32, 0.14, 0.62, { mat: 'paint', color: '#4a4e52', y: 6.6, z: 1.65 })
          pb.box(0.26, 0.04, 0.5, { mat: 'glass', color: '#d8d4c8', y: 6.52, z: 1.65 })
        })
      } else if (p.kind === 'hydrant') {
        pb.cyl(0.13, 0.16, 0.6, { mat: 'paint', color: '#b8322a', x: p.x, y: 0.3, z: p.z, seg: 10 })
        pb.sphere(0.14, { mat: 'paint', color: '#b8322a', x: p.x, y: 0.62, z: p.z, ws: 10, hs: 6 })
        for (const s of [-1, 1]) pb.cyl(0.05, 0.05, 0.12, { mat: 'paint', color: '#b8322a', x: p.x + s * 0.16, y: 0.4, z: p.z, rz: Math.PI / 2, seg: 8 })
      } else if (p.kind === 'hesco') {
        const c = this.center(p.i, p.j)
        pb.box(1.0, 1.4, 1.0, { mat: 'canvas', color: '#b0a07a', x: c.x, y: 0.7, z: c.z })
        pb.box(1.02, 0.04, 1.02, { mat: 'steel', color: '#6a6e68', x: c.x, y: 1.4, z: c.z })
      }
    }
    this.addStatic(pb)
  }
  addStatic(b) {
    const g = b.build({ index: false })
    g.traverse((o) => {
      if (o.isMesh) o.matrixAutoUpdate = false
    })
    this.scene.add(g)
    return g
  }

  // ---------------------------------------------------------------- the building
  buildBuilding() {
    const lv = this.lv
    const L = this.def
    const W = lv.W
    const r = seeded(lv.loc.lot.seed + 5)
    const b = new Builder()
    const ext = EXT_MAT[L.ext] || 'plaster'
    const extColor = L.extColor ? L.extColor[Math.floor(r() * L.extColor.length)] : ext === 'brick' ? '#ffffff' : L.wall
    const isW = (i, j) => i >= 0 && j >= 0 && i < W && j < lv.H && lv.walls[j * W + i] === 1
    const isExt = (i, j) => lv.buildings.some((B) => (i === B.i0 || i === B.i1) && j >= B.j0 && j <= B.j1) || lv.buildings.some((B) => (j === B.j0 || j === B.j1) && i >= B.i0 && i <= B.i1)
    const roomCol = (i, j) => {
      const k = i >= 0 && j >= 0 && i < W && j < lv.H ? lv.roomAt[j * W + i] : -1
      return k >= 0 ? lv.rooms[k].def.wall : null
    }
    const winAt = new Map(lv.windows.map((w) => [w.i + ',' + w.j, w]))
    const doorAt = new Map(lv.doors.map((d) => [d.i + ',' + d.j, d]))
    // foundations and floors
    for (const B of lv.buildings) {
      const c0 = this.center(B.i0, B.j0)
      const c1 = this.center(B.i1, B.j1)
      b.box(c1.x - c0.x + 1.3, 0.1, c1.z - c0.z + 1.3, { mat: 'concrete', color: '#8e8a82', x: (c0.x + c1.x) / 2, y: 0, z: (c0.z + c1.z) / 2, ao: 0 })
    }
    for (const room of lv.rooms) {
      const c0 = this.center(room.i0, room.j0)
      const c1 = this.center(room.i1, room.j1)
      const fl = room.corridor ? 'linoleum' : room.def.floor
      const tint = { tiles: '#e8e4dc', planks: '#ffffff', carpet: pick(['#8a6a5a', '#6a7a8a', '#7a8a6a', '#a89070']), checker: '#ffffff', linoleum: '#d8d4c8', concrete: '#b8b4ac' }[fl] || '#ffffff'
      b.box(c1.x - c0.x + 1.05, 0.02, c1.z - c0.z + 1.05, { mat: fl, color: tint, x: (c0.x + c1.x) / 2, y: 0.06, z: (c0.z + c1.z) / 2, ao: 0, shadow: false })
      // dirt, blood and debris on the floor
      for (let k = 0; k < Math.floor(((room.i1 - room.i0 + 1) * (room.j1 - room.j0 + 1)) / 14); k++) {
        const x = c0.x - 0.4 + r() * (c1.x - c0.x + 0.8)
        const z = c0.z - 0.4 + r() * (c1.z - c0.z + 0.8)
        const q = r()
        if (q < 0.35) b.box(0.3 + r() * 0.5, 0.01, 0.2 + r() * 0.4, { mat: 'plain', color: '#f0ece0', x, y: 0.075, z, ry: r() * 3, shadow: false, ao: 0 })
        else if (q < 0.5) b.box(0.6 + r() * 0.8, 0.005, 0.4 + r() * 0.7, { mat: 'plain', color: '#3a1010', x, y: 0.074, z, ry: r() * 3, shadow: false, ao: 0 })
        else if (q < 0.7) b.dodeca(0.06 + r() * 0.08, { mat: 'concrete', color: '#9a948a', x, y: 0.11, z, rx: r() * 3 })
        else if (q < 0.8) b.cyl(0.035, 0.035, 0.11, { mat: 'metal', color: '#c8ccd0', x, y: 0.105, z, rz: Math.PI / 2, ry: r() * 3, seg: 8 })
      }
    }
    // walls
    const h = (i, j) => (isExt(i, j) ? WALL_EXT : WALL_INT)
    for (let j = 0; j < lv.H; j++) {
      for (let i = 0; i < W; i++) {
        if (!isW(i, j)) continue
        const c = this.center(i, j)
        const ex = isExt(i, j)
        const H = h(i, j)
        const core = ex ? { material: this.cutMat(ext), color: extColor } : { mat: 'plaster', color: '#c8c0b0' }
        const win = winAt.get(i + ',' + j)
        // post at the tile centre
        b.box(T_WALL, H, T_WALL, { ...core, x: c.x, y: 0.05 + H / 2, z: c.z })
        const arms = [
          [1, 0, isW(i + 1, j) || doorAt.has(i + 1 + ',' + j)],
          [-1, 0, isW(i - 1, j) || doorAt.has(i - 1 + ',' + j)],
          [0, 1, isW(i, j + 1) || doorAt.has(i + ',' + (j + 1))],
          [0, -1, isW(i, j - 1) || doorAt.has(i + ',' + (j - 1))],
        ]
        for (const [di, dj, on] of arms) {
          if (!on) continue
          const ax = c.x + di * 0.25
          const az = c.z + dj * 0.25
          const alongX = di !== 0
          const w = alongX ? 0.5 : T_WALL
          const d = alongX ? T_WALL : 0.5
          if (win) {
            // window: sill wall, glass, head wall
            b.box(w, 0.9, d, { ...core, x: ax, y: 0.05 + 0.45, z: az })
            b.box(w, H - 2.15, d, { ...core, x: ax, y: 0.05 + 2.15 + (H - 2.15) / 2, z: az })
            const broken = (i * 7 + j * 3) % 5 === 0
            const boarded = (i * 5 + j) % 7 === 0
            if (boarded) b.box(alongX ? 0.52 : 0.06, 0.14, alongX ? 0.06 : 0.52, { material: this.cutMat('planks'), color: '#a8906c', x: ax + (alongX ? 0 : (dj ? 0 : 0)), y: 0.05 + 1.3 + (di + dj) * 0.15, z: az })
            if (!broken) b.box(alongX ? 0.5 : 0.04, 1.25, alongX ? 0.04 : 0.5, { material: this.cutMat('glass'), color: '#b8c8cc', x: ax, y: 0.05 + 1.53, z: az })
            b.box(alongX ? 0.5 : T_WALL + 0.08, 0.06, alongX ? T_WALL + 0.08 : 0.5, { material: this.cutMat('paint'), color: '#e8e4dc', x: ax, y: 0.05 + 0.92, z: az })
            continue
          }
          b.box(w, H, d, { ...core, x: ax, y: 0.05 + H / 2, z: az })
          // painted faces and skirting on the room sides
          for (const s of [-1, 1]) {
            const fi = alongX ? i : i + s
            const fj = alongX ? j + s : j
            const col = roomCol(fi, fj)
            if (!col) continue
            const fx = alongX ? ax : c.x + s * (T_WALL / 2 + 0.006)
            const fz = alongX ? c.z + s * (T_WALL / 2 + 0.006) : az
            const fh = ex ? H : WALL_INT
            const faceMat = ex ? { material: this.cutMat('plaster') } : { mat: 'plaster' }
            b.box(alongX ? 0.5 : 0.012, fh - 0.02, alongX ? 0.012 : 0.5, { ...faceMat, color: col, x: fx, y: 0.05 + fh / 2, z: fz, ao: 0.1 })
            b.box(alongX ? 0.5 : 0.03, 0.12, alongX ? 0.03 : 0.5, { ...(ex ? { material: this.cutMat('paint') } : { mat: 'paint' }), color: '#5a4a3e', x: alongX ? ax : c.x + s * (T_WALL / 2 + 0.015), y: 0.12, z: alongX ? c.z + s * (T_WALL / 2 + 0.015) : az })
          }
        }
        if (!ex) b.box(T_WALL + 0.03, 0.04, T_WALL + 0.03, { mat: 'plain', color: '#3a3632', x: c.x, y: 0.05 + H + 0.02, z: c.z, shadow: false })
        if (!ex)
          for (const [di, dj, on] of arms) if (on) b.box(di ? 0.5 : T_WALL + 0.03, 0.04, di ? T_WALL + 0.03 : 0.5, { mat: 'plain', color: '#3a3632', x: c.x + di * 0.25, y: 0.05 + H + 0.02, z: c.z + dj * 0.25, shadow: false })
      }
    }
    // door frames, open doors, lintels
    for (const d of lv.doors) {
      if (d.passage) continue
      const c = this.center(d.i, d.j)
      const ex = d.ext
      const H = ex ? WALL_EXT : WALL_INT
      const alongX = d.horiz
      const m = ex ? (k) => ({ material: this.cutMat(k) }) : (k) => ({ mat: k })
      const garage = ex && d.wide >= 3
      const top = garage ? 2.6 : 2.15
      // head wall over the opening
      b.box(alongX ? 1.0 : T_WALL, H - top, alongX ? T_WALL : 1.0, { ...(ex ? { material: this.cutMat(EXT_MAT[L.ext] || 'plaster'), color: extColor } : { mat: 'plaster', color: '#c8c0b0' }), x: c.x, y: 0.05 + top + (H - top) / 2, z: c.z })
      if (d.part === 0 || d.wide === 1) {
        const jx = alongX ? c.x - 0.47 : c.x
        const jz = alongX ? c.z : c.z - 0.47
        b.box(alongX ? 0.07 : T_WALL + 0.06, top, alongX ? T_WALL + 0.06 : 0.07, { ...m('wood'), color: '#e8e4dc', x: jx, y: 0.05 + top / 2, z: jz })
      }
      if (d.part === d.wide - 1) {
        const jx = alongX ? c.x + 0.47 : c.x
        const jz = alongX ? c.z : c.z + 0.47
        b.box(alongX ? 0.07 : T_WALL + 0.06, top, alongX ? T_WALL + 0.06 : 0.07, { ...m('wood'), color: '#e8e4dc', x: jx, y: 0.05 + top / 2, z: jz })
      }
      if (garage) {
        // roll-up door, rolled up
        b.box(alongX ? 1.0 : 0.3, 0.4, alongX ? 0.3 : 1.0, { ...m('roofmetal'), color: '#c8c8c0', x: c.x, y: 0.05 + 2.4, z: c.z })
      } else if (d.wide === 1) {
        // the door itself, swung open against the wall
        const s = (d.i + d.j) % 2 ? 1 : -1
        const hx = alongX ? c.x - 0.45 : c.x + s * 0.45
        const hz = alongX ? c.z + s * 0.45 : c.z - 0.45
        b.box(alongX ? 0.05 : 0.86, 2.05, alongX ? 0.86 : 0.05, { ...m(ex ? 'paint' : 'wood'), color: ex ? pick(['#5a3a2a', '#2a3a4a', '#6a2a2a', '#e8e4dc']) : '#c8a880', x: hx, y: 0.05 + 1.03, z: hz })
      } else if (d.part === 0) {
        // shop doors: glass, both pushed open
        const s = alongX ? 1 : -1
        for (const k of [0, 1]) b.box(alongX ? 0.05 : 0.9, 2.05, alongX ? 0.9 : 0.05, { ...m('glass'), color: '#c8d8d8', x: alongX ? c.x - 0.45 + k * (d.wide - 0.1) : c.x + s * 0.45, y: 0.05 + 1.03, z: alongX ? c.z + 0.45 : c.z - 0.45 + k * (d.wide - 0.1) })
      }
      b.box(alongX ? 1.0 : T_WALL + 0.1, 0.02, alongX ? T_WALL + 0.1 : 1.0, { mat: 'wood', color: '#6a5a48', x: c.x, y: 0.072, z: c.z, shadow: false, ao: 0 })
    }
    // a name board over the front door of shops and public buildings, kept
    // within the front wall (homes don't get one)
    const B = lv.bld
    if (B && !B.prefab && this.loc.type !== 'house') {
      const dc = this.center(B.door.i, B.door.j)
      const left = this.center(B.i0, B.j1).x
      const right = this.center(B.i1, B.j1).x
      const w = Math.min(7, (right - left) * 0.5, 2 * Math.min(dc.x - left, right - dc.x) - 0.4)
      if (w > 1.5) {
        const name = (this.loc.name || '').split(' ').slice(-2).join(' ').toUpperCase()
        b.box(w + 0.16, 0.86, 0.16, { material: this.cutMat('paint'), color: '#2a2e2a', x: dc.x, y: WALL_EXT + 0.5, z: dc.z + 0.2 })
        b.plane(w, 0.7, { material: cutaway(plateMat(name, '#e8e0c8', '#2a2420'), this.cutU), x: dc.x, y: WALL_EXT + 0.5, z: dc.z + 0.285, shadow: false })
      }
    }
    this.addStatic(b)
    // furniture
    const fb = new Builder()
    for (const d of lv.decor) addDecor(fb, d.kind, { x: d.x, y: 0.07, z: d.z, ry: d.rot }, Math.floor(d.x * 13 + d.z * 7) & 0xffff)
    this.addStatic(fb)
    // ceiling lights as dark fittings (and a few flickering emergency lights at night)
    this.emergency = []
    for (const room of lv.rooms) {
      if (room.corridor || chance(0.6)) continue
      const c0 = this.center(room.i0, room.j0)
      const c1 = this.center(room.i1, room.j1)
      this.emergency.push({ x: (c0.x + c1.x) / 2, z: (c0.z + c1.z) / 2 })
    }
  }

  // ---------------------------------------------------------------- yard and fences
  buildYard() {
    const lv = this.lv
    const site = lv.loc.site
    const b = new Builder()
    for (const p of lv.pave) {
      b.box(p.x1 - p.x0, 0.06, p.z1 - p.z0, { mat: p.mat === 'gravel' ? 'gravel' : p.mat, color: p.c, x: (p.x0 + p.x1) / 2, y: 0, z: (p.z0 + p.z1) / 2, shadow: false, ao: 0 })
      // worn paint: each stall line is a few broken dashes
      if (p.stalls)
        for (let x = p.x0 + 2; x < p.x1 - 1; x += 2.7)
          for (let z = p.z1 - 6.3; z < p.z1 - 1.7; ) {
            const len = Math.min(p.z1 - 1.7 - z, 0.5 + Math.random() * 1.6)
            b.box(0.11, 0.01, len, { mat: 'concrete', color: '#a6a296', x, y: 0.034, z: z + len / 2, shadow: false, ao: 0 })
            z += len + Math.random() * 0.35
          }
    }
    // fences around our lot and the neighbours'
    const fence = (ax, az, bx, bz, kind) => {
      const len = Math.hypot(bx - ax, bz - az)
      const n = Math.max(1, Math.round(len / 2.4))
      for (let k = 0; k < n; k++) {
        const t0 = k / n
        const t1 = (k + 1) / n
        const pa = [ax + (bx - ax) * t0, az + (bz - az) * t0]
        const pb = [ax + (bx - ax) * t1, az + (bz - az) * t1]
        if (kind === 'wood') {
          b.box(0.1, 1.9, 0.1, { mat: 'wood', color: '#8a7058', x: pa[0], y: 0.95, z: pa[1] })
          b.beam([pa[0], 0.95, pa[1]], [pb[0], 0.95, pb[1]], 0.06, 1.8, { mat: 'planks', color: '#b09878' })
        } else {
          b.cyl(0.035, 0.035, 2.0, { mat: 'steel', color: '#8a8e90', x: pa[0], y: 1.0, z: pa[1], seg: 6 })
          b.beam([pa[0], 1.95, pa[1]], [pb[0], 1.95, pb[1]], 0.04, 0.04, { mat: 'steel', color: '#8a8e90', round: true })
          b.beam([pa[0], 1.0, pa[1]], [pb[0], 1.0, pb[1]], 0.02, 1.9, { mat: 'glass', color: '#a8b0b4' })
        }
      }
    }
    const gapZ = site.bz - site.bd / 2 - 2
    for (const s of [-1, 1]) {
      fence(s * (lv.fw / 2), -lv.fd / 2, s * (lv.fw / 2), gapZ - 1.2, lv.fences[0].kind)
      fence(s * (lv.fw / 2), gapZ + 1.2, s * (lv.fw / 2), lv.zFront - 2, lv.fences[0].kind)
    }
    fence(-lv.fw / 2, -lv.fd / 2, -1.5, -lv.fd / 2, lv.fences[0].kind)
    fence(1.5, -lv.fd / 2, lv.fw / 2, -lv.fd / 2, lv.fences[0].kind)
    // trees in the yard
    for (const t of lv.trees) {
      const g = t.kind === 'pine' ? pineModel(Math.floor(t.x * 31 + t.z * 17), { h: 7 + Math.random() * 3 }) : broadleafModel(Math.floor(t.x * 31 + t.z * 17), { h: 5 + Math.random() * 2.5 })
      mergeGroup(b, g, { x: t.x, z: t.z, ry: Math.random() * TAU })
    }
    // the canopy over the pumps
    if (lv.canopy) {
      const C = lv.canopy
      b.box(C.w, 0.9, C.d, { mat: 'paint', color: '#e8e4dc', x: C.x, y: 5.3, z: C.z })
      b.box(C.w + 0.1, 0.4, C.d + 0.1, { mat: 'paint', color: '#c8302a', x: C.x, y: 5.1, z: C.z })
      for (const s of [-1, 1]) b.box(0.5, 4.9, 0.5, { mat: 'paint', color: '#d8d4cc', x: C.x + (s * C.w) / 4, y: 2.45, z: C.z })
    }
    for (const t of lv.trucks || []) mergeGroup(b, mapVehicleModel('semi'), { x: t.x, z: t.z, ry: t.rot })
    this.addStatic(b)
  }
  // Neighbouring lots from the city, built exactly as they stand on the map.
  buildNeighbours() {
    const lv = this.lv
    const city = this.game.city
    const me = lv.loc.lot
    const r0 = FACE_ROT[me.face]
    const c = Math.cos(r0)
    const s = Math.sin(r0)
    const toLocal = (wx, wz) => {
      const dx = wx - me.cx
      const dz = wz - me.cz
      return { x: dx * c - dz * s, z: dx * s + dz * c }
    }
    const P = new PropList()
    const b = new Builder()
    const reach = Math.max(lv.W, lv.H) * 0.75 + 20
    for (const lot of city.lots) {
      if (lot === me || lot.taken) continue
      const p = toLocal(lot.cx, lot.cz)
      if (Math.abs(p.x) > reach || Math.abs(p.z) > reach) continue
      const r = seeded(lot.seed)
      b.at({ x: p.x, z: p.z, ry: FACE_ROT[lot.face] - r0 }, () => {
        if (lot.loc) locationModel(b, lot.loc, r, P)
        else if (lot.bld) CB.fillerLot(b, lot, r, P)
      })
    }
    this.addStatic(b)
    // their small props, instanced
    this.inst = []
    const make = (key, template, opts) => {
      const list = P.by[key]
      if (!list?.length) return
      const set = new InstSet(this.scene, template, list.length, opts)
      set.set(list)
      this.inst.push(set)
    }
    for (const k of ['oak', 'maple', 'poplar', 'pine', 'spruce', 'dead']) make('tree-' + k, mapTreeModel(k), { tint: ['leaf', 'needle'], wind: true })
    make('bush', mapTreeModel('bush'), { tint: ['leaf'], shadow: false })
    make('hedge', mapTreeModel('hedge'), { tint: ['leaf'], shadow: false })
    for (const k of ['sedan', 'hatch', 'suv', 'pickup', 'van', 'police', 'truck', 'semi', 'bus', 'ambulance', 'firetruck', 'humvee', 'mtruck', 'heli']) make('car-' + k, mapVehicleModel(k), { tint: ['paint'] })
    make('car-burnt', mapVehicleModel('sedan', { burnt: true }), {})
    for (const k of ['semi', 'truck', 'ambulance', 'firetruck', 'humvee', 'mtruck', 'heli']) make(k, mapVehicleModel(k), { tint: ['paint'] })
    make('bus-school', mapVehicleModel('schoolbus'), { tint: ['paint'] })
    for (const k of ['lamp', 'hydrant', 'bench', 'trash', 'mailbox', 'dumpster', 'pallets', 'tires', 'rubble', 'tent', 'tomb', 'cross', 'swing', 'container', 'barrier', 'sandbags', 'hesco', 'policeline', 'car-pile', 'crane', 'busstop', 'pole', 'poleT', 'signal']) make(k, mapPropModel(k), { tint: k === 'container' ? ['corrugated'] : k === 'dumpster' || k === 'mailbox' ? ['paint'] : k === 'tent' ? ['canvas'] : [] })
    // fires in the neighbourhood
    for (const f of P.by.fire || []) this.addFire(f.x, f.y || 3, f.z, 2.5 * (f.s || 1), 1e9)
  }

  // ---------------------------------------------------------------- containers
  buildContainers() {
    const lv = this.lv
    this.containers = lv.containers
    this.buckets = new Map()
    for (const c of this.containers) {
      c.searched = false
      c.gone = false
      c.open = 0
      c.openGoal = 0
      c.seed = (c.id * 7919 + lv.loc.lot.seed) & 0xffff
      const key = c.room != null ? 'r' + (lv.roomAt[c.tiles[0][1] * lv.W + c.tiles[0][0]] ?? 'o') : 'o' + Math.floor(c.x / 10) + ',' + Math.floor(c.z / 10)
      c.bucket = key
      if (!this.buckets.has(key)) this.buckets.set(key, { list: [], group: null })
      this.buckets.get(key).list.push(c)
      // a box for picking
      let x0 = 1e9
      let z0 = 1e9
      let x1 = -1e9
      let z1 = -1e9
      for (const [i, j] of c.tiles) {
        const p = this.center(i, j)
        x0 = Math.min(x0, p.x - 0.5)
        z0 = Math.min(z0, p.z - 0.5)
        x1 = Math.max(x1, p.x + 0.5)
        z1 = Math.max(z1, p.z + 0.5)
      }
      c.box = new THREE.Box3(new THREE.Vector3(x0, 0, z0), new THREE.Vector3(x1, CH[c.kind] || 1.2, z1))
      c.label = view.labels.add(h('div.lootmark' + (c.locked ? '.locked' : ''), { html: c.locked ? icon('lock') : '' }), new THREE.Vector3(c.x, (CH[c.kind] || 1.2) + 0.35, c.z), { scene: this.scene, maxDist: 40 })
    }
    for (const key of this.buckets.keys()) this.buildBucket(key)
  }
  buildBucket(key) {
    const bk = this.buckets.get(key)
    if (bk.group) {
      this.scene.remove(bk.group)
      bk.group.traverse((o) => o.isMesh && o.geometry.dispose())
    }
    const b = new Builder()
    const ranges = []
    for (const c of bk.list) {
      if (c.gone) continue
      const start = b.pivots.length
      const y = this.floorY(c.x, c.z)
      if (c.kind === 'car') {
        const g = c.model === 'humvee' ? mapVehicleModel('humvee') : carModel({ seed: c.seed, wreck: c.seed % 3 === 0 ? 0.5 + (c.seed % 50) / 100 : 0.1 })
        mergeGroup(b, g, { x: c.x, y: 0, z: c.z, ry: c.rot })
      } else {
        const fn = CONTAINER_MODELS[c.kind] || CONTAINER_MODELS.crate
        b.at({ x: c.x, y, z: c.z, ry: c.rot }, () => fn(b, seeded(c.seed)))
      }
      ranges.push([c, start, b.pivots.length])
    }
    const g = b.build({ index: false })
    const list = g.userData.pivotList || []
    for (const [c, s0, s1] of ranges) {
      c.pivots = list.slice(s0, s1)
      for (const p of c.pivots) p.userData.base = { x: p.position.x, y: p.position.y, z: p.position.z, ry: p.rotation.y, rx: p.rotation.x }
      this.applyOpen(c)
    }
    this.scene.add(g)
    bk.group = g
  }
  applyOpen(c) {
    const t = c.open
    for (const o of c.pivots || []) {
      const B = o.userData.base
      const name = o.name
      if (name.startsWith('drawer')) {
        const dir = new THREE.Vector3(Math.sin(c.rot), 0, Math.cos(c.rot))
        o.position.set(B.x + dir.x * t * 0.3, B.y, B.z + dir.z * t * 0.3)
      } else if (name === 'door' && (c.kind === 'trash' || c.kind === 'milcrate' || c.kind === 'dumpster')) o.rotation.x = B.rx - t * 1.5
      else if (name === 'doorR') o.rotation.y = B.ry + t * 1.8
      else o.rotation.y = B.ry - t * 1.8
    }
  }
  removeContainer(c) {
    c.gone = true
    for (const [i, j] of c.tiles) this.lv.grid.set(i, j, 0, 0, null)
    c.label?.remove()
    this.buildBucket(c.bucket)
  }
  buildVan() {
    const lv = this.lv
    if (this.loadout.van === false) {
      this.vanGroup = null
    } else {
      const g = vanModel({})
      g.position.set(lv.van.x, 0, lv.van.z)
      g.rotation.y = lv.van.rot
      g.traverse((o) => {
        if (o.isMesh) {
          o.castShadow = true
          o.receiveShadow = true
        }
      })
      this.scene.add(g)
      this.vanGroup = g
    }
    const E = lv.evac
    const ring = new THREE.Mesh(new THREE.RingGeometry(E.r - 0.14, E.r, 48), new THREE.MeshBasicMaterial({ color: new THREE.Color('#6fd08a').multiplyScalar(1.5), transparent: true, opacity: 0.7, depthWrite: false }))
    ring.rotation.x = -Math.PI / 2
    ring.position.set(E.x, 0.06, E.z)
    ring.renderOrder = 2
    this.scene.add(ring)
    const fill = new THREE.Mesh(new THREE.CircleGeometry(E.r, 48), new THREE.MeshBasicMaterial({ color: '#6fd08a', transparent: true, opacity: 0.1, depthWrite: false }))
    fill.rotation.x = -Math.PI / 2
    fill.position.set(E.x, 0.055, E.z)
    this.scene.add(fill)
    this.evacRing = ring
    view.labels.add(h('div.evac-tag', 'EVAC'), new THREE.Vector3(E.x, 0.3, E.z + E.r + 0.4), { scene: this.scene })
    // headlights at night
    this.vanLights = []
    if (this.vanGroup) {
      for (const s of [-1, 1]) {
        const l = new THREE.SpotLight('#fff0d0', 0, 26, 0.5, 0.6, 1.4)
        l.position.set(lv.van.x + 2.6, 1.0, lv.van.z + s * 0.7)
        l.target.position.set(lv.van.x + 14, 0, lv.van.z + s * 1.5)
        this.scene.add(l, l.target)
        this.vanLights.push(l)
      }
    }
  }

  // ---------------------------------------------------------------- zombies and events
  takeUtilities() {
    this.utils = { medkit: 0, molotov: 0, pipebomb: 0, noisemaker: 0 }
    for (const a of this.squad) {
      const k = a.data.util
      if (!k || !UTILITIES.includes(k)) continue
      const n = Math.min(a.st.utilSlots, Math.floor(S.res[k] || 0))
      if (n <= 0) continue
      S.res[k] -= n
      this.utils[k] += n
    }
  }
  spawnZombie(kind, i, j, theme = this.def.zombieTheme) {
    const c = this.center(i, j)
    const z = new ZombieAgent(this, kind, c.x, c.z, this.level, theme)
    this.zombies.push(z)
    return z
  }
  spawnInitial() {
    const lv = this.lv
    const [lo, hi] = infectedRange(lv.loc)
    const n = rint(lo, hi)
    const mix = zombieMix(this.level, this.def.zombieTheme)
    const used = new Set()
    for (let k = 0; k < n; k++) {
      const pool = Math.random() < 0.66 && lv.spawns.inside.length ? lv.spawns.inside : lv.spawns.outside
      for (let t = 0; t < 30; t++) {
        const [i, j] = pool[Math.floor(Math.random() * pool.length)]
        if (used.has(i + ',' + j)) continue
        used.add(i + ',' + j)
        const z = this.spawnZombie(weighted(mix).t, i, j)
        z.heading = Math.random() * TAU
        break
      }
    }
  }
  spawnHorde() {
    const lv = this.lv
    const mix = zombieMix(Math.min(5, this.level + 1), this.def.zombieTheme)
    const end = chance(0.7) ? pick(lv.streetEnds) : pick(lv.spawns.outside)
    const n = lv.grid.nearestOpen(end[0] + rint(-1, 1), end[1] + rint(-2, 2), 4)
    if (!n) return
    const z = this.spawnZombie(weighted(mix).t, n.x, n.z)
    const alive = this.squad.filter((a) => !a.downed)
    if (alive.length) {
      z.state = 'chase'
      z.target = alive.reduce((a, b) => (z.dist(a) < z.dist(b) ? a : b))
    }
  }
  setupEvent() {
    const ev = this.event
    const lv = this.lv
    if (!ev) return
    if (ev.kind === 'distress' && ev.npc) {
      // the caller hides in a room deep inside
      const spots = lv.spawns.inside.length ? lv.spawns.inside : lv.spawns.outside
      let best = spots[0]
      let bd = -1
      for (let k = 0; k < 40; k++) {
        const s = spots[Math.floor(Math.random() * spots.length)]
        const p = this.center(s[0], s[1])
        const d = Math.hypot(p.x - lv.evac.x, p.z - lv.evac.z)
        if (d > bd) {
          bd = d
          best = s
        }
      }
      const p = this.center(best[0], best[1])
      ev.npc.hp = ev.npc.hp || 60
      const a = new SurvivorAgent(this, ev.npc, p.x, p.z)
      a.npc = true
      a.pack = { res: {}, items: [], load: 0 }
      a.labelEl.classList.add('npc')
      a.labelEl.querySelector('.nm').textContent = 'SOS · ' + ev.npc.first
      this.npc = a
    } else if (ev.kind === 'airdrop') {
      // two military crates where the parachute came down, marked with smoke
      const spots = lv.spawns.outside.filter(([i, j]) => {
        const p = this.center(i, j)
        return p.z < lv.zFront - 2 && Math.abs(p.x) < lv.fw / 2 - 2
      })
      const s = spots.length ? pick(spots) : pick(lv.spawns.outside)
      for (let k = 0; k < 2; k++) {
        const n = lv.grid.nearestOpen(s[0] + k * 2, s[1], 4, (i, j) => !lv.grid.owner[lv.grid.i(i, j)])
        if (!n) continue
        const p = this.center(n.x, n.z)
        const c = { id: this.containers.length, kind: 'milcrate', def: CONTAINERS.milcrate, room: null, tiles: [[n.x, n.z]], x: p.x, z: p.z, rot: Math.random() * TAU, locked: false, searched: false, gone: false, open: 0, openGoal: 0, seed: 99 + k, bucket: 'drop' }
        c.box = new THREE.Box3(new THREE.Vector3(p.x - 0.5, 0, p.z - 0.5), new THREE.Vector3(p.x + 0.5, 1.1, p.z + 0.5))
        c.label = view.labels.add(h('div.lootmark.drop'), new THREE.Vector3(p.x, 1.5, p.z), { scene: this.scene })
        lv.grid.set(n.x, n.z, 255, 0, c)
        this.containers.push(c)
        if (!this.buckets.has('drop')) this.buckets.set('drop', { list: [], group: null })
        this.buckets.get('drop').list.push(c)
        this.dropAt = p
      }
      this.buildBucket('drop')
      // parachute draped over the crates
      if (this.dropAt) {
        const pb = new Builder()
        pb.cloth(4, 3, { mat: 'canvas', color: '#d8d0b8', x: this.dropAt.x + 1.5, y: 0.2, z: this.dropAt.z + 2, corners: [0.1, 0.4, 0.05, 0.2], sag: -0.3, wrinkle: 0.2 })
        this.addStatic(pb)
        this.fx.addEmitter((fx, dt) => {
          if (Math.random() < dt * 9) fx.smoke(new THREE.Vector3(this.dropAt.x, 0.4, this.dropAt.z), { size: 1.4, life: 5, color: '#5ad87a', a: 0.45, vy: 1.6, spread: 0.4 })
        })
      }
    }
  }

  // ---------------------------------------------------------------- world API for agents
  ammoLeft(type) {
    return S.res[type] || 0
  }
  useAmmo(type, n) {
    S.res[type] = Math.max(0, (S.res[type] || 0) - n)
  }
  isNight() {
    return isNight(hour())
  }
  nightAcc(a) {
    return 0.72 + (a.st?.nightSight || 0) * 0.28
  }
  nearCamera(p) {
    return Math.hypot(p.x - view.rig.target.x, p.z - view.rig.target.z) < view.rig.dist * 0.8 + 6
  }
  noise(x, z, r, src = null) {
    for (const zz of this.zombies) {
      if (zz.dead || zz === src) continue
      if (Math.hypot(zz.pos.x - x, zz.pos.z - z) <= r) zz.alertTo(x, z)
    }
  }
  hasMedkit() {
    return this.utils.medkit > 0
  }
  consumeMedkit() {
    this.utils.medkit = Math.max(0, this.utils.medkit - 1)
    this.renderUtils()
  }
  accessTile(c, agent = null) {
    const g = this.lv.grid
    const cand = []
    for (const [ti, tj] of c.tiles) {
      for (const [di, dj] of [
        [0, 1],
        [0, -1],
        [1, 0],
        [-1, 0],
        [1, 1],
        [-1, 1],
        [1, -1],
        [-1, -1],
      ]) {
        const i = ti + di
        const j = tj + dj
        if (!g.open(i, j) || g.owner[g.i(i, j)]) continue
        if (this.lv.reach && !this.lv.reach[g.i(i, j)]) continue
        if (di && dj && !g.open(ti + di, tj) && !g.open(ti, tj + dj)) continue
        // don't search a fitting through a wall
        if (!this.lv.walls[(tj + dj) * this.lv.W + ti] && !this.lv.walls[tj * this.lv.W + ti + di]) cand.push({ ...this.center(i, j), diag: di && dj ? 1 : 0 })
      }
    }
    if (!cand.length) return null
    const ax = agent ? agent.pos.x : this.lv.evac.x
    const az = agent ? agent.pos.z : this.lv.evac.z
    cand.sort((a, b) => a.diag - b.diag || Math.hypot(a.x - ax, a.z - az) - Math.hypot(b.x - ax, b.z - az))
    return cand[0]
  }
  workTime(agent, c, kind) {
    if (c.def?.isTrap) return c.armed ? this.disarmTime(agent, c) : null
    if (kind === 'take') return 0.8
    if (kind === 'search') {
      if (c.stash) return 1
      if (c.locked && !agent.st.picklock && !c.smash) return null
      return (c.def.time * (c.smash ? 2.2 : 1)) / agent.st.search
    }
    return (c.def.time * 1.8 + 2) / agent.st.dismantle
  }
  workTick(agent, wk, dt) {
    if (wk.c.def?.isTrap) return
    wk.noiseT = (wk.noiseT || 0) - dt
    wk.sfxT = (wk.sfxT || 0) - dt
    if (wk.noiseT <= 0) {
      wk.noiseT = 1
      const n = wk.kind === 'dismantle' ? 7 : wk.c.smash ? 12 : 1.6
      this.noise(agent.pos.x, agent.pos.z, n * agent.st.noiseMult)
    }
    if (wk.sfxT <= 0) {
      wk.sfxT = wk.kind === 'dismantle' || wk.c.smash ? 0.45 : 0.9
      if (this.nearCamera(agent.pos)) sfx(wk.kind === 'dismantle' || wk.c.smash ? 'dismantle' : 'search', 100)
      if (wk.kind === 'dismantle' || wk.c.smash) this.fx.burst(new THREE.Vector3(wk.c.x, 0.9, wk.c.z), '#8a7a60', 3, 1.5, 0.5, 2)
    }
    if (wk.kind === 'search' && !wk.c.smash) {
      wk.c.openGoal = Math.max(wk.c.openGoal, Math.min(1, (wk.t / wk.total) * 1.5))
    }
  }
  finishWork(agent, c, kind) {
    if (c.def?.isTrap) return this.finishDisarm(agent, c)
    const pos = new THREE.Vector3(c.x, (CH[c.kind] || 1.2) + 0.4, c.z)
    if (kind === 'search') {
      let found
      if (c.stash) {
        found = c.stash
        c.stash = null
      } else {
        c.searched = true
        c.openGoal = 1
        found = rollLoot(c.def, this.level, agent.st.loot)
        gainXP(agent.data, 'scavenge', 3 + this.level)
        if (c.smash) sfx('dismantle')
        if (c.locked) sfx('unlock')
      }
      if (!found.length) view.labels.float(this.scene, pos, 'Nothing useful', 'dim')
      const left = this.stow(agent, found, pos)
      if (left.length) {
        c.stash = left
        c.label?.el.classList.add('stash')
        this.toast(`${agent.data.first}'s pack is full. Some loot is still in the ${c.def.name.toLowerCase()}.`)
      } else {
        c.label?.el.classList.remove('stash')
        if (c.searched) c.label?.el.classList.add('done')
      }
      sfx(found.some((f) => f.item && RARITY[ITEMS[f.item].rarity].rank >= 1) ? 'rare' : 'loot')
    } else {
      const yieldRes = []
      for (const [k, [a, b]] of Object.entries(c.def.strip)) {
        const n = Math.round(rint(a, b) * (0.8 + agent.st.dismantle * 0.25))
        if (n > 0) yieldRes.push({ r: k, n })
      }
      if (c.kind === 'car' && agent.st.carParts) yieldRes.push({ r: 'parts', n: 2 })
      // whatever was inside spills out too
      if (!c.searched && !c.locked) for (const f of rollLoot(c.def, this.level, agent.st.loot * 0.6)) yieldRes.push(f)
      if (c.stash) yieldRes.push(...c.stash)
      const left = this.stow(agent, yieldRes, pos)
      this.fx.burst(pos.clone().setY(0.7), '#7a6a52', 18, 3, 0.9, 3, 1.4)
      if (c.kind === 'car') this.fx.sparks(pos.clone().setY(0.6), 14)
      gainXP(agent.data, 'build', 3)
      sfx('dismantle')
      if (left.length) {
        // what doesn't fit lands in a pile on the floor
        const p = { id: this.containers.length, kind: 'crate', def: { ...CONTAINERS.crate, name: 'Pile of salvage', strip: {} }, room: c.room, tiles: [c.tiles[0]], x: c.x, z: c.z, rot: 0, searched: true, gone: false, open: 1, openGoal: 1, seed: 7, stash: left, bucket: c.bucket }
        this.removeContainer(c)
        p.box = new THREE.Box3(new THREE.Vector3(c.x - 0.5, 0, c.z - 0.5), new THREE.Vector3(c.x + 0.5, 0.8, c.z + 0.5))
        p.label = view.labels.add(h('div.lootmark.stash'), new THREE.Vector3(c.x, 1.1, c.z), { scene: this.scene, maxDist: 40 })
        this.lv.grid.set(c.tiles[0][0], c.tiles[0][1], 255, 0, p)
        this.containers.push(p)
        this.buckets.get(c.bucket).list.push(p)
        this.buildBucket(c.bucket)
      } else this.removeContainer(c)
    }
    this.updateHaul()
  }
  // Put finds in a survivor's pack; returns what didn't fit.
  stow(agent, found, pos) {
    const left = []
    const cap = agent.st.carry
    let k = 0
    for (const f of found) {
      if (f.item) {
        if (agent.pack.load + ITEM_LOAD > cap) {
          left.push(f)
          continue
        }
        agent.pack.items.push(f.item)
        agent.pack.load += ITEM_LOAD
        const it = ITEMS[f.item]
        setTimeout(() => view.labels.float(this.scene, pos.clone().setY(pos.y + k * 0.35), `<b style="color:${RARITY[it.rarity].color}">${it.name}</b>`, 'item'), k * 160)
      } else {
        const w = LOAD[f.r] ?? 1
        const fit = w > 0 ? Math.min(f.n, Math.floor((cap - agent.pack.load) / w + 1e-6)) : f.n
        if (fit < f.n) left.push({ r: f.r, n: f.n - fit })
        if (fit <= 0) continue
        agent.pack.res[f.r] = (agent.pack.res[f.r] || 0) + fit
        agent.pack.load += fit * w
        const n = fit
        const kk = k
        setTimeout(() => view.labels.float(this.scene, pos.clone().setY(pos.y + kk * 0.35), `+${n} ${RES[f.r].name}`, 'res res-' + f.r), kk * 160)
      }
      k++
    }
    return left
  }
  onKill(z, from) {
    S.stats.kills++
    this.kills = (this.kills || 0) + 1
    // special infected leave tissue worth studying
    if ((z.def.stalk || z.def.scream || z.def.burst) && chance(0.65)) {
      const a = from?.pack && !from.npc ? from : this.squad.filter((x) => !x.npc && !x.downed).sort((p, q) => p.dist(z) - q.dist(z))[0]
      if (a?.pack) {
        a.pack.res.specimen = (a.pack.res.specimen || 0) + 1
        view.labels.float(this.scene, z.chestPos(1.6), '+1 specimen', 'res')
        this.toast(`${a.data.first} took a tissue sample from the ${z.def.name.toLowerCase()}.`, 'good')
      }
    }
  }
  onDowned(a) {
    this.toast(`${a.data.first} is down! Send someone to help them up.`, 'bad')
  }
  onBledOut(a) {
    a.dead = true
    a.remove()
    this.squad = this.squad.filter((x) => x !== a)
    this.selected.delete(a)
    if (a.npc) return
    killSurvivor(a.data, `Killed on a supply run at ${this.loc.name}`)
    this.lost = (this.lost || []).concat(a.data.first)
    this.renderSquad()
    if (!this.squad.filter((x) => !x.npc).length) this.end('wiped')
  }
  onLabelClick(a) {
    if (a.npc) return
    this.select(a, false)
  }
  dmgBonus() {
    return 1
  }
  toastFn(msg, kind) {
    this.toast(msg, kind)
  }

  // ---------------------------------------------------------------- throwables
  throwItem(agent, item, x, z) {
    const from = agent.chestPos(1.5)
    const to = new THREE.Vector3(x, 0.2, z)
    const m = throwableModel(item)
    m.position.copy(from)
    this.scene.add(m)
    const dur = clamp(from.distanceTo(to) / 12, 0.35, 0.9)
    this.flying = (this.flying || []).concat({ m, from, to, t: 0, dur, item, agent })
    sfx('throw')
    this.utils[item] = Math.max(0, this.utils[item] - 1)
    this.renderUtils()
  }
  updateThrown(dt) {
    for (const f of [...(this.flying || [])]) {
      f.t += dt
      const k = Math.min(1, f.t / f.dur)
      f.m.position.lerpVectors(f.from, f.to, k)
      f.m.position.y += Math.sin(k * Math.PI) * (1.5 + f.from.distanceTo(f.to) * 0.18)
      f.m.rotation.x += dt * 9
      f.m.rotation.z += dt * 5
      if (k >= 1) {
        this.scene.remove(f.m)
        this.flying = this.flying.filter((x) => x !== f)
        this.detonate(f.item, f.to.x, f.to.z, f.agent)
      }
    }
  }
  detonate(item, x, z, agent) {
    const p = new THREE.Vector3(x, 0.3, z)
    if (item === 'molotov') {
      sfx('glass')
      sfx('fire')
      this.addFire(x, 0.2, z, 2.6, 9, agent)
      this.noise(x, z, 9)
    } else if (item === 'pipebomb') {
      sfx('boom')
      this.fx.explosion(p, 3.2)
      view.rig.shake = 1
      for (const zz of this.zombies) {
        if (zz.dead) continue
        const d = Math.hypot(zz.pos.x - x, zz.pos.z - z)
        if (d < 4.5) zz.hurt(160 * (1 - d / 5), agent, { knock: true })
      }
      for (const a of this.squad) {
        const d = Math.hypot(a.pos.x - x, a.pos.z - z)
        if (d < 3) a.hurt(40 * (1 - d / 3.2), null)
      }
      this.noise(x, z, 30)
    } else if (item === 'noisemaker') {
      const lure = { x, z, until: this.time + 15, mesh: throwableModel('noisemaker'), beepT: 0 }
      lure.mesh.position.set(x, 0.05, z)
      this.scene.add(lure.mesh)
      this.lures.push(lure)
      for (const zz of this.zombies) {
        if (zz.dead || zz.state === 'chase') continue
        if (Math.hypot(zz.pos.x - x, zz.pos.z - z) < 20) {
          zz.state = 'lured'
          zz.lured = lure
          zz.path = null
        }
      }
    }
  }
  addFire(x, y, z, r, life, agent = null) {
    const grp = new THREE.Group()
    grp.position.set(x, y, z)
    const n = Math.max(3, Math.round(r * 2.4))
    for (let k = 0; k < n; k++) {
      const fl = makeFlame(0.9 + Math.random() * 0.6, 1.4 + Math.random() * 1.2, 2.8)
      const a = Math.random() * TAU
      const rr = Math.sqrt(Math.random()) * r * 0.8
      fl.position.set(Math.cos(a) * rr, 0, Math.sin(a) * rr)
      grp.add(fl)
    }
    this.scene.add(grp)
    const light = new THREE.PointLight('#ff8a3a', 0, r * 6, 1.6)
    light.position.set(x, y + 1.2, z)
    this.scene.add(light)
    this.fires.push({ x, y, z, r, life, t: 0, grp, light, agent })
  }
  updateFires(dt) {
    for (const f of [...this.fires]) {
      f.t += dt
      const fade = f.life > 1e8 ? 1 : clamp((f.life - f.t) / 1.5, 0, 1)
      f.light.intensity = 30 * f.r * fade * (0.8 + Math.sin(this.time * 11 + f.x) * 0.1 + Math.sin(this.time * 7 + f.z) * 0.1)
      f.grp.scale.setScalar(Math.max(0.01, fade))
      if (Math.random() < dt * 6 * f.r) this.fx.smoke(new THREE.Vector3(f.x + (Math.random() - 0.5) * f.r, f.y + 1.5, f.z + (Math.random() - 0.5) * f.r), { size: 1 + f.r * 0.3, life: 3, color: '#2a2624', a: 0.4, vy: 1.6 })
      if (Math.random() < dt * 10) this.fx.ember(new THREE.Vector3(f.x + (Math.random() - 0.5) * f.r, f.y + 0.6, f.z + (Math.random() - 0.5) * f.r), 0.6)
      if (f.life < 1e8) {
        for (const z of this.zombies) if (!z.dead && Math.hypot(z.pos.x - f.x, z.pos.z - f.z) < f.r) z.ignite(5)
        for (const a of this.squad) if (!a.downed && !a.st.fireproof && Math.hypot(a.pos.x - f.x, a.pos.z - f.z) < f.r * 0.8) a.hurt(dt * 18, null)
      }
      if (f.t >= f.life) {
        this.scene.remove(f.grp)
        this.scene.remove(f.light)
        this.fires = this.fires.filter((x) => x !== f)
      }
    }
    for (const L of [...this.lures]) {
      L.beepT -= 0
      if (Math.floor(this.time * 2) !== L.lastBeep) {
        L.lastBeep = Math.floor(this.time * 2)
        if (this.nearCamera(L)) sfx('beep', 300)
        this.fx.ring(new THREE.Vector3(L.x, 0.1, L.z), '#58d0ff', 1.6)
      }
      if (this.time >= L.until) {
        this.scene.remove(L.mesh)
        this.lures = this.lures.filter((x) => x !== L)
      }
    }
  }

  // ---------------------------------------------------------------- input
  select(a, add = false) {
    if (!add) {
      for (const s of this.selected) s.select(false)
      this.selected.clear()
    }
    if (a && !a.downed && !a.npc) {
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
    for (const a of this.squad) if (!a.downed && !a.npc) this.select(a, true)
  }
  agentAt(x, y, list) {
    const ray = screenRay(x, y)
    let best = null
    let bd = 1e9
    const box = new THREE.Box3()
    const hit = new THREE.Vector3()
    for (const a of list) {
      if (a.dead) continue
      const r = a.def?.crawl ? 0.6 : 0.45
      box.min.set(a.pos.x - r, 0, a.pos.z - r)
      box.max.set(a.pos.x + r, a.def?.crawl ? 0.8 : 1.9, a.pos.z + r)
      if (ray.ray.intersectBox(box, hit)) {
        const d = hit.distanceTo(ray.ray.origin)
        if (d < bd) {
          bd = d
          best = a
        }
      }
    }
    return best ? { a: best, d: bd } : null
  }
  containerAt(x, y) {
    const ray = screenRay(x, y)
    let best = null
    let bd = 1e9
    const hit = new THREE.Vector3()
    for (const c of this.containers) {
      if (c.gone) continue
      if (ray.ray.intersectBox(c.box, hit)) {
        const d = hit.distanceTo(ray.ray.origin)
        if (d < bd) {
          bd = d
          best = c
        }
      }
    }
    return best ? { c: best, d: bd } : null
  }
  hitTest(x, y) {
    const s = this.agentAt(x, y, this.squad)
    const z = this.agentAt(x, y, this.zombies.filter((z) => !z.dead))
    const c = this.containerAt(x, y)
    const t = this.trapAt(x, y)
    const opts = [s && { type: 'survivor', a: s.a, d: s.d - 0.6 }, z && !z.a.fogHidden && { type: 'zombie', z: z.a, d: z.d - 0.4 }, c && { type: 'container', c: c.c, d: c.d }, t && { type: 'trap', c: t.t, d: t.d - 0.5 }].filter(Boolean)
    opts.sort((a, b) => a.d - b.d)
    return opts[0] || null
  }
  onPress(x, y, e) {
    // left drag on open ground draws a selection box
    if (e.button !== 0 || this.throwing) return false
    const hit = this.hitTest(x, y)
    if (hit) return false
    this.boxSel = { x0: x, y0: y, x1: x, y1: y }
    return true
  }
  onDragMove(x, y) {
    if (!this.boxSel) return
    this.boxSel.x1 = x
    this.boxSel.y1 = y
    const B = this.boxSel
    this.boxEl.hidden = false
    Object.assign(this.boxEl.style, { left: Math.min(B.x0, B.x1) + 'px', top: Math.min(B.y0, B.y1) + 'px', width: Math.abs(B.x1 - B.x0) + 'px', height: Math.abs(B.y1 - B.y0) + 'px' })
  }
  onRelease(x, y, moved) {
    const B = this.boxSel
    this.boxSel = null
    this.boxEl.hidden = true
    if (!B || !moved) return
    const x0 = Math.min(B.x0, B.x1)
    const x1 = Math.max(B.x0, B.x1)
    const y0 = Math.min(B.y0, B.y1)
    const y1 = Math.max(B.y0, B.y1)
    const add = false
    this.select(null)
    for (const a of this.squad) {
      if (a.downed || a.npc) continue
      const p = new THREE.Vector3(a.pos.x, 1, a.pos.z).project(view.camera)
      const sx = (p.x * 0.5 + 0.5) * window.innerWidth
      const sy = (-p.y * 0.5 + 0.5) * window.innerHeight
      if (sx >= x0 && sx <= x1 && sy >= y0 && sy <= y1) this.select(a, true)
    }
    void add
  }
  onTap(x, y, e) {
    this.closeMenu()
    if (this.over) return
    if (this.throwing) {
      if (e.button === 2) return this.cancelThrow()
      const p = groundAt(x, y)
      if (p) this.doThrow(p.x, p.z)
      return
    }
    const hit = this.hitTest(x, y)
    const command = e.button === 2
    if (hit?.type === 'survivor') {
      const a = hit.a
      if (a.downed) {
        const helper = this.pickHelper(a.pos)
        if (helper) {
          helper.command({ type: 'revive', a })
          sfx('move')
        }
        return
      }
      if (!command) {
        this.select(a, e.shift || e.ctrl)
        sfx('select')
        return
      }
    }
    if (hit?.type === 'zombie' && command) {
      const who = [...this.selected]
      for (const a of who) a.command({ type: 'attack', z: hit.z })
      if (who.length) sfx('move')
      return
    }
    if (hit?.type === 'trap') {
      const helper = this.pickHelper(hit.c)
      if (command && helper) {
        helper.command({ type: 'dismantle', c: hit.c })
        sfx('move')
        return
      }
      return this.openMenu(hit.c, x, y)
    }
    if (hit?.type === 'container') {
      if (command && this.selected.size) return this.defaultAction(hit.c)
      return this.openMenu(hit.c, x, y)
    }
    if (!command) {
      if (!e.shift) this.select(null)
      return
    }
    const p = groundAt(x, y)
    if (!p) return
    const who = [...this.selected].filter((a) => !a.downed)
    if (!who.length) return this.toast('Select a survivor first: click them, their card, or drag a box.')
    this.fx.ring(p)
    sfx('move')
    const used = new Set()
    const g = this.lv.grid
    who.forEach((a, k) => {
      const [ti, tj] = this.tile(p.x, p.z)
      const n = g.nearestOpen(ti + (k ? rint(-1, 1) : 0), tj + (k ? rint(-1, 1) : 0), 5, (i, j) => !used.has(i + ',' + j) && !g.owner[g.i(i, j)])
      if (!n) return
      used.add(n.x + ',' + n.z)
      const c = this.center(n.x, n.z)
      a.command({ type: 'move', x: k ? c.x : p.x, z: k ? c.z : p.z })
    })
  }
  defaultAction(c) {
    const helper = this.pickHelper(c)
    if (!helper) return
    if (c.stash || (!c.searched && (!c.locked || helper.st.picklock))) {
      c.smash = false
      helper.command({ type: 'search', c })
    } else if (!c.searched && c.locked) {
      c.smash = true
      helper.command({ type: 'search', c })
    } else helper.command({ type: 'dismantle', c })
    sfx('move')
  }
  onHover(x, y) {
    if (this.throwing) {
      const p = groundAt(x, y)
      if (p) this.aimRing.position.set(p.x, 0.25, p.z)
      return
    }
    const hit = this.hitTest(x, y)
    view.canvas.style.cursor = hit ? 'pointer' : ''
    const c = hit?.type === 'container' || hit?.type === 'trap' ? hit.c : null
    if (c !== this.hoverC) {
      this.hoverC = c
      this.tip.hidden = !c
      if (c?.def?.isTrap) this.tip.innerHTML = `<b>${c.def.name}</b><span>${c.trap.desc}</span><small>Right-click: disarm for parts</small>`
      else if (c) this.tip.innerHTML = `<b>${c.def.name}</b><span>${c.stash ? 'Loot left inside' : c.searched ? 'Searched · can be broken down' : c.locked ? 'Locked' : 'Not searched'}</span><small>Right-click: ${c.stash || !c.searched ? 'search' : 'break down'}</small>`
    }
    if (c) this.tip.style.transform = `translate(${x + 16}px, ${y + 14}px)`
  }
  onKey(e) {
    if (e._handled || this.over) return
    const k = e.key.toLowerCase()
    if (k === ' ') {
      this.paused = !this.paused
      this.renderPause()
      e.preventDefault()
      return
    }
    if (k === 'escape') {
      if (this.throwing) return this.cancelThrow()
      if (this.menuOpen) return this.closeMenu()
      this.select(null)
      return
    }
    const n = parseInt(e.key, 10)
    const team = this.squad.filter((a) => !a.npc)
    if (n >= 1 && n <= team.length) {
      const a = team[n - 1]
      const now = performance.now()
      if (this.lastKey === n && now - this.lastKeyT < 350) view.rig.focus(a.pos.x, a.pos.z)
      this.lastKey = n
      this.lastKeyT = now
      this.select(a, e.shiftKey)
      return
    }
    if (k === 'a' && (e.ctrlKey || e.metaKey)) {
      e.preventDefault()
      return this.selectAll()
    }
    if (k === 'tab') {
      e.preventDefault()
      return this.selectAll()
    }
    const U = { z: 'molotov', x: 'pipebomb', c: 'noisemaker', v: 'medkit' }
    if (U[k]) return this.useUtil(U[k])
    if (k === 'enter') return this.tryExtract()
    if (k === 'f' && this.selected.size) {
      const a = [...this.selected][0]
      view.rig.focus(a.pos.x, a.pos.z)
    }
  }
  // Survivor to send for a job: a selected one, else the nearest free one.
  pickHelper(target, need = null) {
    const p = target.pos || target
    let pool = [...this.selected].filter((a) => !a.downed && a !== target && (!need || need(a)))
    if (!pool.length) pool = this.squad.filter((a) => !a.downed && !a.npc && a !== target && (!need || need(a)))
    pool.sort((a, b) => Math.hypot(a.pos.x - p.x, a.pos.z - p.z) - Math.hypot(b.pos.x - p.x, b.pos.z - p.z))
    return pool[0] || null
  }
  useUtil(kind) {
    if (this.utils[kind] <= 0) {
      sfx('error')
      return this.toast(`No ${RES[kind].name.toLowerCase()} left`)
    }
    if (kind === 'medkit') {
      const a = [...this.selected].find((x) => x.hp < x.maxHp) || this.squad.filter((x) => !x.downed && !x.npc).sort((p, q) => p.hp / p.maxHp - q.hp / q.maxHp)[0]
      if (!a || a.hp >= a.maxHp) return this.toast('Nobody needs patching up')
      this.utils.medkit--
      a.hp = Math.min(a.maxHp, a.hp + a.maxHp * (researchDone('fieldmed') ? 0.75 : 0.5))
      view.labels.float(this.scene, a.chestPos(2), 'First aid kit', 'good')
      sfx('levelup')
      return this.renderUtils()
    }
    const who = this.pickHelper(view.rig.target)
    if (!who) return
    this.throwing = { kind }
    this.aimRing.visible = true
    this.aimRing.scale.setScalar(kind === 'pipebomb' ? 4.5 : kind === 'molotov' ? 2.6 : 3)
    document.body.classList.add('aiming')
    this.toast(`Click where to throw the ${RES[kind].name.toLowerCase()}. Right-click cancels.`)
  }
  doThrow(x, z) {
    const kind = this.throwing.kind
    const who = this.pickHelper({ x, z })
    this.cancelThrow()
    if (!who) return
    const d = Math.hypot(who.pos.x - x, who.pos.z - z)
    if (d > 16) {
      // walk closer first, then throw
      const dir = Math.atan2(x - who.pos.x, z - who.pos.z)
      who.command({ type: 'move', x: x - Math.sin(dir) * 12, z: z - Math.cos(dir) * 12 })
      who.pendingThrow = { item: kind, x, z }
      return
    }
    who.command({ type: 'throw', item: kind, x, z })
  }
  cancelThrow() {
    this.throwing = null
    this.aimRing.visible = false
    document.body.classList.remove('aiming')
  }
  openMenu(c, x, y) {
    const menu = this.menu
    menu.innerHTML = ''
    const helper = this.pickHelper(c)
    const who = helper ? helper.data.first : 'nobody'
    const btn = (label, sub, fn, disabled = false, cls = '') => h('button.cm-btn' + cls, { disabled, onclick: (e) => (e.stopPropagation(), fn(), this.closeMenu()) }, h('span', label), h('small', sub))
    if (c.def?.isTrap) {
      const t = helper ? this.disarmTime(helper, c) : c.trap.disarm
      const gets = Object.entries(c.trap.yield).map(([k, n]) => `${n} ${RES[k].name.toLowerCase()}`).join(', ')
      menu.append(h('div.cm-title', h('b', c.def.name), h('span', c.trap.desc)))
      menu.append(btn('Disarm', `${who} · ${t.toFixed(1)}s · ${gets}`, () => helper.command({ type: 'dismantle', c }), !helper))
      menu.hidden = false
      this.menuOpen = true
      const r = menu.getBoundingClientRect()
      menu.style.transform = `translate(${Math.max(10, Math.min(x + 10, window.innerWidth - r.width - 10))}px, ${Math.max(10, Math.min(y + 10, window.innerHeight - r.height - 10))}px)`
      sfx('click')
      return
    }
    menu.append(h('div.cm-title', h('b', c.def.name), h('span', c.stash ? 'Loot left inside' : c.searched ? 'Already searched' : c.locked ? 'Locked' : `Level ${this.level} location`)))
    if (c.stash) menu.append(btn('Take the rest', `${who} · ${c.stash.length} lot${c.stash.length === 1 ? '' : 's'}`, () => helper.command({ type: 'search', c }), !helper))
    else if (!c.searched) {
      if (c.locked) {
        const picker = this.pickHelper(c, (a) => a.st.picklock)
        menu.append(btn('Pick the lock', picker ? `${picker.data.first} · quiet` : 'Needs lockpicks or an Ex-Con', () => ((c.smash = false), picker.command({ type: 'search', c })), !picker))
        menu.append(btn('Smash it open', helper ? `${who} · very loud` : '', () => ((c.smash = true), helper.command({ type: 'search', c })), !helper, '.loud'))
      } else {
        const t = helper ? this.workTime(helper, c, 'search') : c.def.time
        menu.append(btn('Search', `${who} · ${t.toFixed(1)}s · quiet`, () => ((c.smash = false), helper.command({ type: 'search', c })), !helper))
      }
    }
    const strip = Object.keys(c.def.strip || {}).map((k) => RES[k].name.toLowerCase()).join(', ')
    if (strip) {
      const dt = helper ? this.workTime(helper, c, 'dismantle') : c.def.time * 2
      menu.append(btn('Break it down', `${who} · ${dt.toFixed(1)}s · loud · ${strip}`, () => helper.command({ type: 'dismantle', c }), !helper, '.loud'))
    }
    menu.hidden = false
    this.menuOpen = true
    const r = menu.getBoundingClientRect()
    menu.style.transform = `translate(${Math.max(10, Math.min(x + 10, window.innerWidth - r.width - 10))}px, ${Math.max(10, Math.min(y + 10, window.innerHeight - r.height - 10))}px)`
    sfx('click')
  }
  closeMenu() {
    if (this.menu) this.menu.hidden = true
    this.menuOpen = false
  }

  // ---------------------------------------------------------------- loop
  update(dt) {
    if (this.over) return
    const hr = hour()
    const night = nightFactor(hr)
    const a = this.atmo.update(hr, view.rig.target, view.rig.dist, dt)
    this.game.pipe.exposure = a.exposure
    this.game.pipe.grade(this.scene, { saturation: a.saturation * 1.05 })
    setNightGlow(night)
    tickFlames(performance.now() / 1000)
    wind.time.value = performance.now() / 1000
    // the cutaway follows the camera around
    const cam = view.camera.position
    const B = this.lv.bld
    if (B) this.cutU.dir.value.set(cam.x - B.cx, cam.z - B.cz).normalize()
    if (!this.paused) {
      this.elapsed += dt
      this.time += dt
      for (const s of this.squad) {
        if (s.pendingThrow && !s.path && s.order?.type !== 'move') {
          const t = s.pendingThrow
          s.pendingThrow = null
          s.command({ type: 'throw', item: t.item, x: t.x, z: t.z })
        }
        if (!s.downed) s.pos.y = this.floorY(s.pos.x, s.pos.z)
        s.update(dt)
      }
      for (const z of this.zombies) {
        if (!z.dead) z.pos.y = this.floorY(z.pos.x, z.pos.z)
        z.update(dt)
      }
      const all = [...this.squad, ...this.zombies.filter((z) => !z.dead)]
      for (const x of all) x.separate(dt, all)
      this.zombies = this.zombies.filter((z) => {
        if (z.dead && z.deadT > 6) {
          z.remove()
          return false
        }
        return true
      })
      this.updateThrown(dt)
      this.updateFires(dt)
      this.updateTraps(dt)
      this.updateClouds(dt)
      this.updateNpc()
      this.unloadAtVan()
      for (const c of this.containers) {
        if (c.open < c.openGoal) {
          c.open = Math.min(c.openGoal, c.open + dt * 2.2)
          this.applyOpen(c)
        }
      }
      // the horde
      if (!this.hordeOn && this.elapsed >= this.hordeIn) {
        this.hordeOn = true
        sfx('alarm')
        this.toast('The horde has found you. Get back to the van!', 'bad')
        setAmbience(0.07)
      }
      if (this.hordeOn) {
        this.spawnT -= dt
        if (this.spawnT <= 0) {
          this.spawnT = Math.max(2.2, 8.5 - this.level) * rand(0.8, 1.2)
          this.spawnHorde()
          if (chance(0.3 + this.level * 0.08)) this.spawnHorde()
        }
      }
      const team = this.squad.filter((x) => !x.npc)
      if (team.length && team.every((x) => x.downed)) this.end('wiped')
    } else for (const s of this.squad) s.sync()
    this.updateLights(night)
    this.updateVision(this.paused ? 0 : dt)
    this.fx.setViewport(window.innerHeight, view.camera.fov)
    this.fx.update(this.paused ? 0 : dt)
    this.evacRing.material.opacity = 0.45 + Math.sin(performance.now() / 330) * 0.25
    this.updateHud(dt)
  }
  // Rescue: reach the caller and they follow you out.
  updateNpc() {
    const n = this.npc
    if (!n || n.joined || n.dead) return
    if (this.squad.some((a) => !a.npc && !a.downed && a.dist(n) < 2.6)) {
      n.joined = true
      n.npc = false
      n.labelEl.classList.remove('npc')
      n.labelEl.querySelector('.nm').textContent = n.data.first
      this.squad.push(n)
      this.toast(`${n.data.first} is with you. Get them to the van.`, 'good')
      sfx('levelup')
      this.renderSquad()
    } else n.finish(0.016, 'idle')
  }
  unloadAtVan() {
    if (this.loadout.van === false) return
    const V = this.lv.van
    for (const a of this.squad) {
      if (a.downed || !a.pack.load) continue
      const E = this.lv.evac
      if (Math.hypot(a.pos.x - V.x, a.pos.z - V.z) > 4.2 && Math.hypot(a.pos.x - E.x, a.pos.z - E.z) > E.r + 0.3) continue
      for (const [k, v] of Object.entries(a.pack.res)) this.van.res[k] = (this.van.res[k] || 0) + v
      this.van.items.push(...a.pack.items)
      a.pack = { res: {}, items: [], load: 0 }
      view.labels.float(this.scene, a.chestPos(2.2), 'Stashed in the van', 'good')
      sfx('loot')
      this.updateHaul()
    }
  }
  updateLights(night) {
    if (!this.torches) {
      this.torches = []
      for (let k = 0; k < 5; k++) {
        const l = new THREE.SpotLight('#fff2dc', 0, 22, 0.55, 0.55, 1.3)
        l.castShadow = false
        this.scene.add(l, l.target)
        this.torches.push(l)
      }
    }
    // flashlights after dark
    this.torches.forEach((l, k) => {
      const a = this.squad[k]
      if (!a || a.downed || night < 0.15) {
        l.intensity = 0
        return
      }
      const fwd = new THREE.Vector3(Math.sin(a.heading), 0, Math.cos(a.heading))
      l.position.set(a.pos.x + fwd.x * 0.3, 1.45, a.pos.z + fwd.z * 0.3)
      l.target.position.set(a.pos.x + fwd.x * 8, 0, a.pos.z + fwd.z * 8)
      l.intensity = 160 * night * (a.st.nightSight ? 1.3 : 1)
    })
    for (const l of this.vanLights) l.intensity = 220 * night
  }

  // ---------------------------------------------------------------- HUD
  buildHud() {
    const L = this.def
    const col = LEVEL_COLORS[this.level - 1]
    this.root = h('div.mhud')
    this.tip = h('div.tip.ctip', { hidden: true })
    this.menu = h('div.cmenu', { hidden: true })
    this.toastEl = h('div.mtoasts')
    this.timerEl = h('div.htimer')
    this.haulEl = h('div.haul')
    this.ammoEl = h('div.mammo')
    this.utilEl = h('div.mutils')
    this.boxEl = h('div.selbox', { hidden: true })
    this.pauseBtn = h('button.btn.ghost.small', { onclick: () => ((this.paused = !this.paused), this.renderPause()), 'data-tip': 'Pause <kbd>Space</kbd>' }, 'Pause')
    this.extractBtn = h('button.btn.go', { onclick: () => this.tryExtract() }, 'Leave')
    const ev = this.event
    this.root.append(
      h(
        'div.mtop',
        h('div.mlocard', h('span.lvlbadge', { style: { '--c': col } }, this.level), h('div', h('b', this.loc.name), h('small', `${L.name}${ev ? (ev.kind === 'distress' ? ' · rescue the survivor inside' : ' · supply drop in the yard') : ''}`))),
        this.timerEl,
        h('div.mright', this.ammoEl, this.pauseBtn),
      ),
      this.haulEl,
      h('div.mbottom', (this.squadEl = h('div.squad')), this.utilEl, h('div.mact', h('button.btn.ghost', { onclick: () => this.selectAll(), 'data-tip': 'Select everyone <kbd>Tab</kbd>' }, 'All'), this.extractBtn)),
      (this.helpEl = h('div.mhelp', h('b', 'Supply run'), ' You only see what your people see: rooms reveal as you walk in, and ripples mark what they hear. Click a survivor (or drag a box), then right-click to move, attack or search. Watch for traps. Bring finds to the van, then everyone into the green circle to leave.')),
      this.toastEl,
      this.menu,
      this.tip,
      this.boxEl,
      (this.pauseEl = h('div.pausebanner', { hidden: true }, 'PAUSED · Space to resume')),
    )
    document.getElementById('hud').appendChild(this.root)
    setTimeout(() => this.helpEl?.remove(), 16000)
    this.aimRing = new THREE.Mesh(new THREE.PlaneGeometry(1, 1), new THREE.MeshBasicMaterial({ map: ringTex(), color: new THREE.Color('#ff9a40').multiplyScalar(1.6), transparent: true, depthWrite: false }))
    this.aimRing.rotation.x = -Math.PI / 2
    this.aimRing.visible = false
    this.aimRing.renderOrder = 3
    this.scene.add(this.aimRing)
    this.renderSquad()
    this.renderUtils()
    this.updateHaul()
  }
  renderPause() {
    this.pauseEl.hidden = !this.paused
    this.pauseBtn.textContent = this.paused ? 'Resume' : 'Pause'
  }
  renderUtils() {
    if (!this.utilEl) return
    this.utilEl.innerHTML = ''
    const keys = { molotov: 'Z', pipebomb: 'X', noisemaker: 'C', medkit: 'V' }
    for (const k of ['molotov', 'pipebomb', 'noisemaker', 'medkit']) {
      const n = this.utils[k]
      this.utilEl.append(h('button.ubtn' + (n ? '' : '.empty'), { onclick: () => this.useUtil(k), 'data-tip': `${RES[k].name} <kbd>${keys[k]}</kbd><br><small>${RES[k].desc}</small>` }, h('i', { html: resIcon(k) }), h('b', n), h('kbd', keys[k])))
    }
  }
  renderSquad() {
    if (!this.squadEl) return
    this.squadEl.innerHTML = ''
    this.cards = new Map()
    this.squad
      .filter((a) => !a.npc)
      .forEach((a, i) => {
        const bar = h('i')
        const load = h('i')
        const status = h('small.st')
        const card = h(
          'button.scard' + (a.selected ? '.sel' : ''),
          {
            onclick: (e) => {
              this.select(a, e.shiftKey)
              sfx('select')
            },
            ondblclick: () => view.rig.focus(a.pos.x, a.pos.z),
          },
          h('span.key', String(i + 1)),
          h('img', { src: this.game.portrait(a.data), alt: '' }),
          h('div.sc-info', h('b', a.data.first), status, h('div.hpbar', bar), h('div.loadbar', { 'data-tip': 'Pack' }, load)),
        )
        this.cards.set(a, { card, bar, status, load })
        this.squadEl.append(card)
      })
  }
  updateHaul() {
    const tot = { res: { ...this.van.res }, items: [...this.van.items] }
    for (const a of this.squad) {
      for (const [k, v] of Object.entries(a.pack.res)) tot.res[k] = (tot.res[k] || 0) + v
      tot.items.push(...a.pack.items)
    }
    const parts = []
    for (const [k, v] of Object.entries(tot.res).sort((a, b) => b[1] - a[1])) if (v > 0) parts.push(h('span.hres', { style: { '--c': RES[k].color }, 'data-tip': RES[k].name }, h('i', { html: resIcon(k) }), fmt(v)))
    for (const id of tot.items) parts.push(h('span.hitem', { style: { '--c': RARITY[ITEMS[id].rarity].color } }, ITEMS[id].name))
    this.haulEl.innerHTML = ''
    const vanTxt = this.loadout.van === false ? 'On foot: only what you carry' : 'Loot is safe once it is in the van'
    this.haulEl.append(h('div.hhead', h('b', 'Haul'), h('small', vanTxt)), h('div.hlist', parts.length ? parts : h('span.dim', 'Nothing yet')))
  }
  updateHud(dt) {
    this.hudT = (this.hudT || 0) - dt
    if (this.hudT > 0) return
    this.hudT = 0.15
    const left = this.hordeIn - this.elapsed
    this.timerEl.className = 'htimer' + (this.hordeOn ? ' on' : left < 30 ? ' warn' : '')
    this.timerEl.innerHTML = this.hordeOn ? '<small>HORDE</small><b>Get out</b>' : `<small>Horde in</small><b>${fmtTime(Math.max(0, left))}</b>`
    const guns = new Set(this.squad.map((a) => a.st.ammoType).filter(Boolean))
    this.ammoEl.innerHTML = [...guns].map((k) => `<span style="--c:${RES[k].color}"><i>${resIcon(k)}</i><b>${Math.floor(S.res[k] || 0)}</b> ${RES[k].short}</span>`).join('') || '<span class="dim">No guns</span>'
    for (const [a, { card, bar, status, load }] of this.cards || []) {
      bar.style.width = `${clamp(a.hp / a.maxHp, 0, 1) * 100}%`
      load.style.width = `${clamp(a.pack.load / a.st.carry, 0, 1) * 100}%`
      card.classList.toggle('sel', a.selected)
      card.classList.toggle('down', a.downed)
      card.classList.toggle('full', a.pack.load >= a.st.carry - 1)
      const m = a.lastMode
      const st = a.downed ? `DOWN · ${Math.ceil(a.bleed)}s` : a.work ? (a.work.kind === 'search' ? 'Searching' : 'Breaking down') : a.order?.type === 'revive' ? 'Reviving' : a.order?.type === 'throw' ? 'Throwing' : m === 'swing' || m === 'aim' ? 'Fighting' : m === 'run' || m === 'walk' ? 'Moving' : a.pack.load >= a.st.carry - 1 ? 'Pack full' : 'Holding'
      if (status.textContent !== st) status.textContent = st
    }
    const standing = this.squad.filter((a) => !a.downed)
    const E = this.lv.evac
    const inZone = standing.filter((a) => Math.hypot(a.pos.x - E.x, a.pos.z - E.z) <= E.r + 0.3)
    const ready = standing.length > 0 && inZone.length === standing.length
    this.extractBtn.disabled = !ready
    this.extractBtn.textContent = ready ? 'Leave' : `Leave · ${inZone.length}/${standing.length} at van`
  }
  toast(msg, kind = '') {
    const t = h('div.mt' + (kind ? '.' + kind : ''), msg)
    this.toastEl.prepend(t)
    while (this.toastEl.children.length > 4) this.toastEl.lastChild.remove()
    setTimeout(() => t.classList.add('out'), 3800)
    setTimeout(() => t.remove(), 4400)
  }
  tryExtract() {
    const standing = this.squad.filter((a) => !a.downed)
    const E = this.lv.evac
    if (standing.some((a) => Math.hypot(a.pos.x - E.x, a.pos.z - E.z) > E.r + 0.3)) return this.toast('Everyone still standing has to be inside the green circle by the van')
    const downed = this.squad.filter((a) => a.downed && !a.npc)
    if (downed.length) {
      this.game.ui.confirm(`Leave ${downed.map((a) => a.data.first).join(' and ')} behind?`, 'They are down and will not survive. Help them up first to bring everyone home.', 'Leave them', () => {
        for (const a of downed) {
          killSurvivor(a.data, `Left behind at ${this.loc.name}`)
          this.lost = (this.lost || []).concat(a.data.first)
        }
        this.squad = this.squad.filter((a) => !a.downed)
        this.end('extracted')
      })
      return
    }
    this.end('extracted')
  }

  // ---------------------------------------------------------------- the end
  end(result) {
    if (this.over) return
    this.over = true
    this.saveVision()
    const report = { result, loc: this.loc, loot: {}, items: [], lost: [...(this.lost || [])], injured: [] }
    const L = this.level
    // unused utility items go back into storage
    for (const [k, v] of Object.entries(this.utils)) S.res[k] = (S.res[k] || 0) + v
    if (this.event) S.events = (S.events || []).filter((e) => e !== this.event)
    if (result === 'extracted') {
      const haul = { ...this.van.res }
      const items = [...this.van.items]
      for (const a of this.squad) {
        for (const [k, v] of Object.entries(a.pack.res)) haul[k] = (haul[k] || 0) + v
        items.push(...a.pack.items)
      }
      const before = { ...S.res }
      gain(haul)
      for (const k of Object.keys(haul)) report.loot[k] = Math.round((S.res[k] || 0) - (before[k] || 0))
      for (const id of items) report.items.push(addItem(id, { q: lootQuality(L), cond: rint(35, 95) }))
      S.looted[this.loc.id] = day() + 3
      S.stats.runs++
      completeGoal('firstRun')
      if (L >= 3) completeGoal('loot3')
      for (const a of this.squad) {
        const d = a.data
        if (a === this.npc || d === this.event?.npc) {
          // the rescued survivor joins the camp
          d.status = 'ok'
          d.hp = Math.max(10, a.hp)
          d.joined = day()
          if (!S.survivors.includes(d)) S.survivors.push(d)
          S.stats.recruited++
          addMoraleEvent(`Rescued ${d.first}`, 6, 1.5)
          log(`${d.name} was rescued from ${this.loc.name} and joined the camp.`, 'good')
          report.rescued = d.first
          continue
        }
        d.hp = Math.max(1, a.hp)
        d.runs = (d.runs || 0) + 1
        d.status = d.hp < a.maxHp * 0.3 ? 'injured' : 'ok'
        if (d.status === 'injured') report.injured.push(d.first)
      }
      const got = Object.values(report.loot).reduce((a, b) => a + b, 0)
      report.title = `Back from ${this.loc.name}`
      report.text = `${got ? `The van came back with ${got} supplies` : 'The van came back nearly empty'}${report.items.length ? ` and ${report.items.length} item${report.items.length === 1 ? '' : 's'}` : ''}.${this.kills ? ` ${this.kills} infected put down.` : ''}${report.rescued ? ` ${report.rescued} came home with you.` : ''}`
      log(`The squad returned from ${this.loc.name}.`, 'good')
    } else {
      for (const a of this.squad) {
        if (a.npc || a === this.npc) continue
        const d = a.data
        if (chance(0.5)) {
          d.hp = 1
          d.status = 'injured'
          report.injured.push(d.first)
        } else {
          killSurvivor(d, `Killed on a supply run at ${this.loc.name}`)
          report.lost.push(d.first)
        }
      }
      report.title = 'The run went wrong'
      report.text = `The squad was overrun at ${this.loc.name}. Whatever they found is gone.`
      log(`The run to ${this.loc.name} went badly wrong.`, 'bad')
    }
    for (const s of S.survivors) if (s.status === 'mission') s.status = 'ok'
    this.game.endMission(report)
  }
  dispose() {
    for (const a of this.squad) a.remove()
    for (const z of this.zombies) z.remove()
    if (this.npc && !this.npc.joined) this.npc.remove()
    for (const c of this.containers) c.label?.remove()
    view.labels.clearScene(this.scene)
    this.root?.remove()
    document.body.classList.remove('aiming')
    view.canvas.style.cursor = ''
    setAmbience(0.035)
    this.game.pipe.forget?.(this.scene)
    this.scene.traverse((o) => {
      if (o.isMesh) o.geometry?.dispose?.()
    })
    for (const m of this.cutMats.values()) m.dispose()
    this.terrain?.mesh.geometry.dispose()
    this.atmo.dispose()
    this.fx.dispose()
    this.disposeVision()
  }
}
Object.assign(Mission.prototype, VisionMixin, TrapsMixin)

// Merge a built model group into a builder, keeping its vertex colours.
function mergeGroup(b, g, o = {}) {
  g.updateMatrixWorld(true)
  g.traverse((m) => {
    if (!m.isMesh) return
    const geo = m.geometry.clone()
    geo.applyMatrix4(m.matrixWorld)
    b.add(geo, { material: m.material, keepColor: true, x: o.x || 0, y: o.y || 0, z: o.z || 0, ry: o.ry || 0, shadow: m.castShadow })
  })
}
