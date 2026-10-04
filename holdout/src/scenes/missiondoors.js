// Doors on a run. Every single door hangs on a hinge and can be swung shut
// and opened again: shut, it stops feet, eyes and bullets. The dead that
// want through bash at it until it splits; someone leaning on it (bracing)
// makes each blow count for much less, and feels every one. People open a
// shut door on their way through; the dead only ever break them.
import * as THREE from 'three'
import { BLOCK } from '../core/grid.js'
import { Builder } from '../models/kit.js'
import { view } from '../render/view.js'
import { sfx } from '../core/audio.js'
import { rand, clamp } from '../core/util.js'
import { S } from '../game/state.js'

// how long a door holds against the dead, in hit points
export const DOOR_HP = { inside: 220, outside: 360 }
// the share of a bite a door takes, and of that when someone braces it
const BASH_K = 0.22
const BRACE_K = 0.3
const ease = (t) => t * t * (3 - 2 * t)
const wrapA = (a) => Math.atan2(Math.sin(a), Math.cos(a))

export const DoorsMixin = {
  // The leaf of a single door, on a hinge at one jamb (local +x runs from
  // the hinge to the latch). It starts open, against the wall, as the
  // level was drawn before doors could shut.
  makeDoorLeaf(d, k, c, alongX, mat, color) {
    const s = (d.i + d.j) % 2 ? 1 : -1
    const b = new Builder()
    b.box(0.86, 2.05, 0.05, { ...mat, color, x: 0.45, y: 0.05 + 1.03, z: 0 })
    // a panel moulding each side and a handle
    for (const z of [-0.03, 0.03]) {
      b.box(0.62, 0.7, 0.012, { ...mat, color, x: 0.45, y: 1.55, z })
      b.box(0.62, 0.7, 0.012, { ...mat, color, x: 0.45, y: 0.62, z })
      b.box(0.1, 0.03, 0.05, { mat: 'steel', color: '#b8b0a0', x: 0.76, y: 1.0, z: z * 1.8 })
    }
    const pivot = new THREE.Group()
    pivot.add(b.build())
    pivot.position.set(alongX ? c.x - 0.45 : c.x, 0, alongX ? c.z : c.z - 0.45)
    const ang = (dx, dz) => Math.atan2(-dz, dx)
    const sides = d.horiz ? [[d.i, d.j - 1], [d.i, d.j + 1]] : [[d.i - 1, d.j], [d.i + 1, d.j]]
    const D = {
      d,
      k,
      i: d.i,
      j: d.j,
      x: c.x,
      z: c.z,
      ext: !!d.ext,
      sides,
      pivot,
      openRy: alongX ? ang(0, s) : ang(s, 0),
      shutRy: alongX ? ang(1, 0) : ang(0, 1),
      t: 0,
      shut: false,
      broken: false,
      hp: d.ext ? DOOR_HP.outside : DOOR_HP.inside,
      max: d.ext ? DOOR_HP.outside : DOOR_HP.inside,
      brace: null,
      shake: 0,
      fall: 0,
      box: null,
    }
    // what the dead's bash state reads (it treats a shut door as a barricade)
    D.c = {
      x: c.x,
      z: c.z,
      get barricade() {
        return D.shut && !D.broken
      },
    }
    pivot.rotation.y = D.openRy
    this.levelRoot[k].add(pivot)
    D.box = this.pickBox(c.x - 0.5, c.z - 0.5, c.x + 0.5, c.z + 0.5, 2.1)
    this.doorList ||= []
    this.doorMap ||= new Map()
    this.doorList.push(D)
    this.doorMap.set(d.j * this.lv.W + d.i, D)
    return D
  },
  // The door on the tile under a sim position, if any.
  doorAtPos(x, z) {
    if (!this.doorMap?.size) return null
    const [i, j] = this.tile(x, z)
    return this.doorMap.get(j * this.lv.W + i) || null
  },
  doorTile(D, shut) {
    const g = this.lv.grid
    if (shut) g.set(D.i, D.j, BLOCK, 1, 'door')
    else g.set(D.i, D.j, 0, 0, null)
    if (this.vision) this.vision.block[D.j * this.lv.W + D.i] = shut ? 1 : 0
  },
  // Can it be swung now? (a barricade in the way, someone in the doorway)
  doorFree(D) {
    const g = this.lv.grid
    const own = g.owner[g.i(D.i, D.j)]
    if (own && own !== 'door') return false
    if (D.shut) return true
    return ![...this.squad, ...this.zombies].some((a) => !a.dead && Math.hypot(a.pos.x - D.x, a.pos.z - D.z) < 0.5)
  },
  // Shut it or open it. Returns false if it can't be done.
  toggleDoor(D, by = null) {
    if (D.broken || !this.doorFree(D)) return false
    // a friend's run: the leader's street decides (their order goes there)
    if (this.remote) {
      if (by?.command && !by.fp) return by.command({ type: 'door', D }) !== false
      if (by?.fp) this.coopSend?.({ k: 'rc', sid: by.data.id, o: { type: 'door', door: this.doorList.indexOf(D) } })
      return true
    }
    D.shut = !D.shut
    if (!D.shut) {
      if (D.brace) D.brace.bracing = null
      D.brace = null
    }
    this.doorTile(D, D.shut)
    const pos = new THREE.Vector3(D.x, 1, D.z)
    if (this.sound) this.sound(D.shut ? 'doorShut' : 'doorOpen', pos, { throttle: 60 })
    else if (this.nearCamera(pos)) sfx(D.shut ? 'doorShut' : 'doorOpen', 60)
    // a door slammed carries; one eased shut while crouched hardly does
    if (D.shut) this.noise(D.x, D.z, by?.fpCrouch ? 1.2 : 3.5, by)
    // the dead already on their way through stop where they are
    if (D.shut) for (const z of this.zombies) if (!z.dead && z.path) z.repath = 0
    return true
  },
  // Lean on a shut door. Each blow from the other side then counts for less.
  braceDoor(D, a) {
    if (!D.shut || D.broken) return false
    if (a.bracing && a.bracing !== D) a.bracing.brace = null
    D.brace = a
    a.bracing = D
    return true
  },
  unbrace(a) {
    if (a?.bracing) a.bracing.brace = null
    if (a) a.bracing = null
  },
  // A zombie's blow (see the bash state in world/agents.js).
  bashDoor(D, z) {
    if (!D.shut || D.broken) return
    const held = D.brace && !D.brace.dead && !D.brace.downed
    D.hp -= z.dmg * BASH_K * rand(0.8, 1.2) * (held ? BRACE_K : 1)
    if (held && !D.braceCounted && S.stats) {
      D.braceCounted = true
      S.stats.braced = (S.stats.braced || 0) + 1
    }
    D.shake = 1
    D.hitT = this.time
    this.noise(D.x, D.z, 6, z)
    const pos = new THREE.Vector3(D.x, 1.2, D.z)
    if (this.sound) this.sound('doorBash', pos, { throttle: 90 })
    else if (this.nearCamera(pos)) sfx('doorBash', 90)
    if (Math.random() < 0.4) this.fx.burst(pos, '#8a7a60', 2, 1.2, 0.4, 2)
    // the one leaning on it feels it
    if (held && D.brace.fp && this.game.fp?.active) this.game.fp.shake = Math.max(this.game.fp.shake, 0.55)
    if (D.hp <= 0) this.breakDoor(D)
  },
  breakDoor(D) {
    D.broken = true
    D.shut = false
    D.fall = 0.001
    if (D.brace) D.brace.bracing = null
    D.brace = null
    this.doorTile(D, false)
    const pos = new THREE.Vector3(D.x, 1, D.z)
    if (this.sound) this.sound('dismantle', pos, { throttle: 60 })
    else if (this.nearCamera(pos)) sfx('dismantle', 60)
    this.noise(D.x, D.z, 8)
    view.labels.float(this.scene, pos.clone().setY(2.2), 'The door gave way', 'bad')
  },
  // The shut door a zombie must break to reach t: one it can walk up to
  // whose far side is nearer t. (A path to t failed, so something shut is
  // in the way.) Leaves the zombie walking to it.
  doorToward(z, t) {
    if (!this.doorList) return null
    const cands = this.doorList.filter((D) => D.shut && !D.broken && Math.hypot(D.x - t.pos.x, D.z - t.pos.z) < 16)
    cands.sort((p, q) => Math.hypot(p.x - t.pos.x, p.z - t.pos.z) - Math.hypot(q.x - t.pos.x, q.z - t.pos.z))
    for (const D of cands.slice(0, 3)) {
      const [a, b] = D.sides.map(([i, j]) => this.center(i, j))
      const da = Math.hypot(a.x - t.pos.x, a.z - t.pos.z)
      const db = Math.hypot(b.x - t.pos.x, b.z - t.pos.z)
      // stand on the side away from the prey
      const [near, far] = da < db ? [b, a] : [a, b]
      if (!this.lv.grid.open(...D.sides[da < db ? 1 : 0])) continue
      const there = Math.hypot(near.x - z.pos.x, near.z - z.pos.z) < 0.8
      if (there) z.path = null
      if (there || z.moveTo(near.x, near.z)) return { isDoor: true, D, c: D.c, outPos: near, inPos: far, broken: false }
    }
    return null
  },
  // A path for a person that may go through shut doors (they open them
  // on the way: see Agent.step).
  pathThroughDoors(fx, fz, x, z, opts) {
    const shut = (this.doorList || []).filter((D) => D.shut && !D.broken)
    if (!shut.length) return null
    for (const D of shut) this.doorTile(D, false)
    const p = this.grid.path(fx, fz, x, z, undefined, opts)
    for (const D of shut) this.doorTile(D, true)
    return p
  },
  // Every frame: swing towards shut or open, shiver under blows, fall when
  // broken; let go of a door walked away from.
  updateDoors(dt) {
    if (!this.doorList) return
    for (const D of this.doorList) {
      if (D.brace && (D.brace.dead || D.brace.downed || Math.hypot(D.brace.pos.x - D.x, D.brace.pos.z - D.z) > 1.4)) this.unbrace(D.brace)
      const want = D.shut ? 1 : 0
      const moving = D.t !== want || D.shake > 0 || (D.fall > 0 && D.fall < 1)
      if (!moving) continue
      D.t = D.t < want ? Math.min(want, D.t + dt * 4.5) : Math.max(want, D.t - dt * 3.2)
      D.shake = Math.max(0, D.shake - dt * 5)
      const P = D.pivot
      let ry = D.openRy + wrapA(D.shutRy - D.openRy) * ease(D.t)
      if (D.shake > 0) ry += Math.sin(this.elapsed * 70) * 0.035 * D.shake
      P.rotation.y = ry
      if (D.fall > 0) {
        // off its hinges, flat on the floor
        D.fall = Math.min(1, D.fall + dt * 2.4)
        const f = D.fall * D.fall
        P.rotation.x = 0
        P.rotation.z = 0
        P.children[0].rotation.x = -f * (Math.PI / 2 - 0.04)
        P.children[0].position.y = f * 0.03
      }
    }
  },
  // Top down: which door the pointer is over.
  doorAtScreen(ray) {
    if (!this.doorList) return null
    let best = null
    let bd = 1e9
    const hit = new THREE.Vector3()
    for (const D of this.doorList) {
      if (D.broken || !this.onView(D.x, D.z)) continue
      if (ray.intersectBox(D.box, hit)) {
        const d = hit.distanceTo(ray.origin)
        if (d < bd) {
          bd = d
          best = D
        }
      }
    }
    return best ? { D: best, d: bd } : null
  },
  // Where someone stands to work a door: the side nearer them.
  doorSpot(D, a) {
    const g = this.lv.grid
    const opts = D.sides.filter(([i, j]) => g.open(i, j)).map(([i, j]) => this.center(i, j))
    opts.sort((p, q) => Math.hypot(p.x - a.pos.x, p.z - a.pos.z) - Math.hypot(q.x - a.pos.x, q.z - a.pos.z))
    return opts[0] || null
  },
  // A friend's run: the leader's doors, one letter each (o, s, b).
  applyDoorSig(sig) {
    ;(this.doorList || []).forEach((D, n) => {
      const c = sig[n]
      if (c === 'b' && !D.broken) this.breakDoor(D)
      else if (c === 's' && !D.shut && !D.broken) {
        D.shut = true
        this.doorTile(D, true)
      } else if (c === 'o' && D.shut) {
        D.shut = false
        this.doorTile(D, false)
      }
    })
  },
  doorState(D) {
    if (D.broken) return 'broken'
    return D.shut ? `shut · ${Math.round(clamp(D.hp / D.max, 0, 1) * 100)}%` : 'open'
  },
}
