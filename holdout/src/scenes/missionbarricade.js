// Barricades on a run. A survivor pushes a wardrobe, a fridge or a shelf
// across a doorway and the room behind it is shut. The dead that want in
// bash at it until it gives (a heavy piece holds for minutes against one or
// two of them); while it holds, a room with no way in is a safe room, and
// anyone resting inside patches themselves up. Taking it down slides it
// back; a broken one topples over and opens the door.
import * as THREE from 'three'
import { BLOCK } from '../core/grid.js'
import { view } from '../render/view.js'
import { sfx } from '../core/audio.js'
import { h, rand } from '../core/util.js'

// How long each piece holds, in hit points against the dead. Only these can
// be pushed: heavy, flat-sided and not bolted down.
export const BARRICADE_HP = {
  fridge: 620, wardrobe: 540, bookshelf: 430, locker: 500, firelocker: 500, gunlocker: 680, filing: 400, dresser: 380, desk: 340,
  cabinet: 320, counter: 360, shelf: 460, crate: 360, milcrate: 380, toolchest: 420, toolrack: 400, server: 520, chemshelf: 400, pallet: 560,
}
// The dead do this share of their bite to wood and steel.
const BASH_K = 0.15
// A safe room heals this fast, up to this share of health.
const HEAL = 1.6
const HEAL_TO = 0.85
const REGION_MAX = 260

export const BarricadeMixin = {
  // The doorway this piece could be pushed across (in its own room, within
  // a few metres, nobody standing in it), or null.
  barricadeDoor(c) {
    if (!BARRICADE_HP[c.kind] || c.gone || c.barricade || c.drive || c.def?.isTrap) return null
    const lv = this.lv
    const W = lv.W
    const g = lv.grid
    const [ti, tj] = c.tiles[0]
    const room = lv.roomAt[tj * W + ti]
    if (room == null || room < 0) return null
    let best = null
    let bd = 1e9
    for (const d of lv.doors) {
      if (d.passage || d.wide !== 1) continue
      if ((this.barricades || []).some((b) => b.door === d)) continue
      const sides = d.horiz ? [[d.i, d.j - 1], [d.i, d.j + 1]] : [[d.i - 1, d.j], [d.i + 1, d.j]]
      const inside = sides.find(([i, j]) => lv.roomAt[j * W + i] === room)
      if (!inside) continue
      const outside = sides.find((s) => s !== inside)
      if (!g.open(d.i, d.j) || g.owner[g.i(d.i, d.j)]) continue
      const p = this.center(d.i, d.j)
      if ([...this.squad, ...this.zombies].some((a) => !a.dead && Math.hypot(a.pos.x - p.x, a.pos.z - p.z) < 0.55)) continue
      const dist = Math.hypot(p.x - c.x, p.z - c.z)
      if (dist < bd && dist < 7.5) {
        bd = dist
        best = { door: d, inside, outside }
      }
    }
    return best
  },
  // ---------------------------------------------------------------- up
  placeBarricade(c, plan, opts = {}) {
    const g = this.lv.grid
    this.barricades ||= []
    const { door, inside, outside } = plan
    const from = { x: c.x, z: c.z, rot: c.rot, tiles: c.tiles }
    for (const [i, j] of c.tiles) if (g.owner[g.i(i, j)] === c) g.set(i, j, 0, 0, null)
    const p = this.center(door.i, door.j)
    const pin = this.center(inside[0], inside[1])
    c.tiles = [[door.i, door.j]]
    c.x = p.x
    c.z = p.z
    // across the opening, its back to the way in
    c.rot = Math.atan2(pin.x - p.x, pin.z - p.z)
    g.set(door.i, door.j, BLOCK, 0, c)
    const hp = opts.hp ?? BARRICADE_HP[c.kind]
    const out = this.center(outside[0], outside[1])
    const b = { c, door, inside, outside, outPos: out, inPos: pin, hp, max: BARRICADE_HP[c.kind], from, slide: opts.instant ? 1 : 0, shake: 0, region: null }
    c.barricade = b
    this.barricades.push(b)
    // its own draw bucket, so it can slide, shake and fall on its own
    const old = c.bucket
    const bk = this.buckets.get(old)
    if (bk) bk.list = bk.list.filter((x) => x !== c)
    c.bucket = 'bar' + c.id
    this.buckets.set(c.bucket, { list: [c], group: null })
    if (bk) this.buildBucket(old)
    this.buildBucket(c.bucket)
    b.group = this.buckets.get(c.bucket).group
    b.dx = from.x - c.x
    b.dz = from.z - c.z
    c.box = this.pickBox(p.x - 0.55, p.z - 0.55, p.x + 0.55, p.z + 0.55, 2)
    c.label?.remove()
    b.bar = h('div.barmark', h('i'))
    c.label = view.labels.add(b.bar, new THREE.Vector3(c.x, 2.3, c.z), { scene: this.scene, maxDist: 40 })
    this.sealCheck()
    if (!opts.instant) {
      this.noise(c.x, c.z, 5)
      if (this.nearCamera(new THREE.Vector3(c.x, 0, c.z))) sfx('dismantle', 80)
    }
    return b
  },
  // ---------------------------------------------------------------- down
  takeDownBarricade(b) {
    const c = b.c
    const g = this.lv.grid
    g.set(b.door.i, b.door.j, 0, 0, null)
    // back where it came from, if that's still clear
    const clear = b.from.tiles.every(([i, j]) => g.open(i, j) && !g.owner[g.i(i, j)])
    this.barricades = this.barricades.filter((x) => x !== b)
    c.barricade = null
    c.label?.remove()
    c.label = null
    if (!clear) {
      c.gone = true
      this.buildBucket(c.bucket)
    } else {
      c.x = b.from.x
      c.z = b.from.z
      c.rot = b.from.rot
      c.tiles = b.from.tiles
      for (const [i, j] of c.tiles) g.set(i, j, BLOCK, 0, c)
      this.buildBucket(c.bucket)
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
      c.box = this.pickBox(x0, z0, x1, z1, 1.6)
    }
    this.sealCheck()
  },
  breakBarricade(b, by = null) {
    if (b.broken) return
    b.broken = true
    const c = b.c
    this.lv.grid.set(b.door.i, b.door.j, 0, 0, null)
    this.barricades = this.barricades.filter((x) => x !== b)
    c.barricade = null
    c.label?.remove()
    c.label = null
    b.fall = 0
    this.falling ||= []
    this.falling.push(b)
    this.noise(c.x, c.z, 12)
    const pos = new THREE.Vector3(c.x, 1, c.z)
    this.fx.burst(pos, '#8a7a60', 14, 3, 0.8, 3)
    if (this.nearCamera(pos)) sfx('dismantle', 60)
    view.labels.float(this.scene, pos.clone().setY(2.2), 'The barricade gave way', 'bad')
    this.toast(`The barricade gave way${by ? '' : ''}. They're coming in.`, 'bad')
    this.sealCheck()
  },
  bashBarricade(b, z) {
    // a shut door stands in for a barricade (scenes/missiondoors.js)
    if (b.isDoor) return this.bashDoor(b.D, z)
    if (b.broken) return
    b.hp -= z.dmg * BASH_K * rand(0.8, 1.2)
    b.shake = 0.3
    this.noise(b.c.x, b.c.z, 6, z)
    const pos = new THREE.Vector3(b.c.x, 1.1, b.c.z)
    if (Math.random() < 0.6) this.fx.burst(pos, '#8a7a60', 3, 1.5, 0.5, 2)
    if (this.nearCamera(pos)) sfx('hit', 120)
    if (b.hp <= 0) this.breakBarricade(b, z)
  },
  // The barricade a zombie should break to reach t, if t is shut in behind one.
  barricadeToward(z, t) {
    if (!this.barricades?.length) return null
    const g = this.lv.grid
    const [ti, tj] = this.tile(t.pos.x, t.pos.z)
    const k = g.i(ti, tj)
    const [zi, zj] = this.tile(z.pos.x, z.pos.z)
    const zk = g.i(zi, zj)
    let best = null
    let bd = 1e9
    for (const b of this.barricades) {
      // only when the prey is shut in and this one is outside
      if (b.broken || !b.region?.has(k) || b.region.has(zk)) continue
      const d = Math.hypot(b.outPos.x - z.pos.x, b.outPos.z - z.pos.z)
      if (d < bd) {
        bd = d
        best = b
      }
    }
    return best
  },
  // ---------------------------------------------------------------- the rooms they shut
  // Which tiles each barricade shuts in: everything reachable from its inside
  // without passing a wall or another barricade, if that's a room's worth.
  // The open floor behind a doorway, or null when it leads out some other
  // way (another door, a hole, the stairs or the lift). door: one more
  // doorway to count as shut (for asking before it is).
  regionBehind(inside, door = null) {
    const g = this.lv.grid
    const shut = door ? g.i(door.i, door.j) : -1
    const seen = new Set([g.i(inside[0], inside[1])])
    const q = [inside]
    while (q.length) {
      const [i, j] = q.pop()
      // stairs and the lift lead out too
      if (g.links?.get(g.i(i, j))?.length) return null
      for (const [di, dj] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
        const ni = i + di
        const nj = j + dj
        if (!g.open(ni, nj)) continue
        const k = g.i(ni, nj)
        if (k === shut || seen.has(k)) continue
        seen.add(k)
        if (seen.size > REGION_MAX) return null
        q.push([ni, nj])
      }
    }
    return seen
  },
  // A held barricade close by with someone shut in behind it: the dead that
  // come to the noise join in.
  barricadeNear(z) {
    const g = this.lv.grid
    for (const b of this.barricades || []) {
      if (b.broken || !b.region) continue
      if (Math.hypot(b.outPos.x - z.pos.x, b.outPos.z - z.pos.z) > 3) continue
      if (b.region.has(g.i(...this.tile(z.pos.x, z.pos.z)))) continue
      if (this.squad.some((a) => !a.dead && b.region.has(g.i(...this.tile(a.pos.x, a.pos.z))))) return b
    }
    return null
  },
  sealCheck() {
    for (const b of this.barricades || []) {
      const was = !!b.region
      b.region = this.regionBehind(b.inside)
      if (b.region && !was && !b.told) {
        b.told = true
        this.toast('The room is shut. Rest inside to patch up while the barricade holds.', 'good')
      }
    }
  },
  inSafeRoom(a) {
    const g = this.lv.grid
    const [i, j] = this.tile(a.pos.x, a.pos.z)
    const k = g.i(i, j)
    return (this.barricades || []).find((b) => b.region?.has(k) && !this.zombies.some((z) => !z.dead && b.region.has(g.i(...this.tile(z.pos.x, z.pos.z))))) || null
  },
  // ---------------------------------------------------------------- each frame
  updateBarricades(dt, visual = false) {
    for (const b of this.barricades || []) {
      const G = b.group
      if (G) {
        if (b.slide < 1) b.slide = Math.min(1, b.slide + dt / 0.8)
        const k = 1 - (1 - b.slide) ** 3
        const sh = b.shake > 0 ? (Math.random() - 0.5) * 0.06 * (b.shake / 0.3) : 0
        G.position.set(b.dx * (1 - k) + sh, 0, b.dz * (1 - k) + sh * 0.6)
        b.shake = Math.max(0, b.shake - dt)
      }
      if (b.bar) b.bar.firstChild.style.width = `${Math.max(0, (b.hp / b.max) * 100)}%`
    }
    // broken ones topple, then go
    for (const b of this.falling || []) {
      b.fall += dt
      const G = b.group
      if (G) {
        const t = Math.min(1, b.fall / 0.6)
        G.position.y = -t * 0.2
        G.rotation.z = t * t * 0.4
        G.position.x += (b.inPos.x - b.c.x) * dt * 0.8
        G.position.z += (b.inPos.z - b.c.z) * dt * 0.8
      }
      if (b.fall > 1.4) {
        b.c.gone = true
        this.buildBucket(b.c.bucket)
        b.done = true
      }
    }
    if (this.falling?.length) this.falling = this.falling.filter((b) => !b.done)
    if (visual) return
    // patching up in a safe room
    this.safeT = (this.safeT || 0) - dt
    if (this.safeT > 0) return
    this.safeT = 0.5
    for (const a of this.squad) {
      if (a.downed || a.dead || a.path || a.work || a.hp >= a.maxHp * HEAL_TO) {
        a.patching = false
        continue
      }
      if (a.findThreat?.() || !this.inSafeRoom(a)) {
        a.patching = false
        continue
      }
      a.hp = Math.min(a.maxHp * HEAL_TO, a.hp + HEAL * 0.5)
      if (!a.patching) view.labels.float(this.scene, a.chestPos(2), 'Patching up', 'good')
      a.patching = true
    }
  },
}
