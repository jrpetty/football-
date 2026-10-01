// Traps and special infected on supply runs, mixed into Mission.
// Traps sit in doorways and hallways, hidden until someone spots them: a
// scout from several metres, anyone else only when right on top of one, and
// not always. An unspotted trap goes off under whoever steps on it; zombies
// set them off too, which a clever squad can use. Spotted traps can be
// disarmed for parts. Screamers call every infected around and draw more in
// from the street; bloaters burst into a burning, infectious cloud.
import * as THREE from 'three'
import { TRAPS, RES, zombieMix } from '../game/data.js'
import { gainXP } from '../game/state.js'
import { Builder } from '../models/kit.js'
import { weaponModel } from '../models/weapons.js'
import { view, screenRay } from '../render/view.js'
import { sfx } from '../core/audio.js'
import { h, rand, rint, pick, chance, weighted, clamp } from '../core/util.js'
import { icon } from '../ui/icons.js'

const TAU = Math.PI * 2
const RING_MAT = new THREE.MeshBasicMaterial({ color: new THREE.Color('#ff5a3a').multiplyScalar(1.6), transparent: true, opacity: 0.6, depthWrite: false })

export const TrapsMixin = {
  placeTraps() {
    this.traps = []
    const lv = this.lv
    const L = this.level
    const n = Math.max(0, Math.round(rand(-0.6, 0.9) + L * 0.85 + (lv.rooms.length > 8 ? 1 : 0)))
    if (!n) return
    const g = lv.grid
    const W = lv.W
    const E = lv.evac
    const kinds = Object.entries(TRAPS).filter(([, d]) => L >= d.minLevel).map(([k, d]) => ({ k, w: d.w }))
    // doorways first: interior doors and the back door, then hallways and rooms
    const cand = []
    for (const d of lv.doors) {
      if (d.passage || d.wide > 2) continue
      cand.push({ i: d.i, j: d.j, w: d.ext ? 2 : 3 })
    }
    for (const r of lv.rooms) {
      for (let k = 0; k < 3; k++) cand.push({ i: rint(r.i0, r.i1), j: rint(r.j0, r.j1), w: r.corridor ? 1.6 : 0.8 })
    }
    const used = new Set()
    for (let t = 0; t < n && cand.length; t++) {
      const c = weighted(cand)
      cand.splice(cand.indexOf(c), 1)
      const key = c.i + ',' + c.j
      if (used.has(key) || !g.open(c.i, c.j) || g.owner[g.i(c.i, c.j)]) continue
      if (lv.reach && !lv.reach[g.i(c.i, c.j)]) continue
      const p = this.center(c.i, c.j)
      if (Math.hypot(p.x - E.x, p.z - E.z) < 10) continue
      used.add(key)
      const kind = weighted(kinds).k
      // doorways get tripwires and shotguns, rooms get jaws and plates
      const door = lv.doors.some((d) => d.i === c.i && d.j === c.j)
      const k2 = door && kind === 'beartrap' && chance(0.5) ? 'tripwire' : kind
      this.addTrap(k2, c.i, c.j, door)
    }
  },
  addTrap(kind, i, j, door) {
    const def = TRAPS[kind]
    const p = this.center(i, j)
    const horiz = this.lv.walls[j * this.lv.W + i - 1] === 1 || this.lv.walls[j * this.lv.W + i + 1] === 1
    const t = { id: this.traps.length, kind, def: { name: def.name, time: def.disarm, strip: def.yield, isTrap: true }, trap: def, i, j, x: p.x, z: p.z, tiles: [[i, j]], armed: true, spotted: false, gone: false, searched: true, door, horiz }
    t.box = new THREE.Box3(new THREE.Vector3(p.x - 0.55, 0, p.z - 0.55), new THREE.Vector3(p.x + 0.55, 0.7, p.z + 0.55))
    const g = trapModel(kind, horiz)
    g.position.set(p.x, this.floorY(p.x, p.z), p.z)
    g.visible = false
    this.scene.add(g)
    t.group = g
    const ring = new THREE.Mesh(new THREE.RingGeometry(0.5, 0.62, 28), RING_MAT)
    ring.rotation.x = -Math.PI / 2
    ring.position.set(p.x, 0.09, p.z)
    ring.visible = false
    ring.renderOrder = 3
    this.scene.add(ring)
    t.ring = ring
    this.traps.push(t)
    return t
  },
  // spotting: called on every vision tick
  spotTraps(dt) {
    if (!this.traps?.length) return
    for (const t of this.traps) {
      if (!t.armed || t.spotted) continue
      const seen = this.vision ? this.vision.seesNow(t.x, t.z) : 1
      if (seen < 0.3) continue
      for (const a of this.squad) {
        if (a.downed || a.npc || a.dead) continue
        const d = Math.hypot(a.pos.x - t.x, a.pos.z - t.z)
        const r = a.st.trapSpot
        if (d > r) continue
        // trained eyes catch it at once; anyone else might, if close
        const sure = r > 4
        if (sure || chance(dt * (1.8 - d / r))) {
          this.revealTrap(t, a)
          break
        }
      }
    }
  },
  revealTrap(t, a) {
    t.spotted = true
    t.group.visible = true
    t.ring.visible = true
    t.label = view.labels.add(h('div.trapmark', { html: icon('alert') || '!' }), new THREE.Vector3(t.x, 0.9, t.z), { scene: this.scene })
    // walk around it from now on
    const g = this.lv.grid
    g.cost[g.i(t.i, t.j)] = 120
    if (a) {
      this.toast(`${a.data.first} spotted a ${t.def.name.toLowerCase()}. Right-click it to disarm.`, 'warn')
      gainXP(a.data, 'scavenge', 2)
    }
    sfx('trapSpot', 200)
  },
  updateTraps(dt) {
    if (!this.traps?.length) return
    for (const t of this.traps) {
      if (t.ring.visible) t.ring.material.opacity = 0.35 + Math.sin(performance.now() / 220) * 0.25
      if (t.label) t.label.hidden = this.vision && !this.vision.exploredAt(t.x, t.z)
      if (!t.armed) continue
      // anyone stepping on it: survivors only if they missed it
      let who = null
      for (const a of this.squad) {
        if (a.downed || a.dead || a.npc) continue
        if (Math.floor(a.pos.x - this.lv.x0) === t.i && Math.floor(a.pos.z - this.lv.z0) === t.j && !t.spotted) who = a
      }
      if (!who)
        for (const z of this.zombies) {
          if (z.dead) continue
          if (Math.floor(z.pos.x - this.lv.x0) === t.i && Math.floor(z.pos.z - this.lv.z0) === t.j) {
            who = z
            break
          }
        }
      if (who) this.springTrap(t, who)
    }
    for (const a of this.squad) if (a.snared > 0) a.snared -= dt
  },
  springTrap(t, who) {
    t.armed = false
    const T = t.trap
    const p = new THREE.Vector3(t.x, 0.4, t.z)
    if (!t.spotted) this.revealTrap(t, null)
    const survivor = who.faction === 'survivor'
    if (t.kind === 'tripwire') {
      sfx('glass')
      sfx('alarm')
      this.noise(t.x, t.z, 28)
      this.fx.ring(p, '#ffb070', 3)
      this.toast(survivor ? `${who.data.first} hit a tripwire. Everything nearby heard that.` : 'Something tripped a wire.', 'bad')
    } else if (t.kind === 'beartrap') {
      sfx('snap')
      who.hurt(T.dmg, null)
      if (survivor) {
        who.snared = T.hold
        who.path = null
        this.toast(`${who.data.first} is caught in a bear trap!`, 'bad')
      } else who.stun = Math.max(who.stun || 0, T.hold)
      this.noise(t.x, t.z, 8)
    } else if (t.kind === 'shotgun') {
      sfx('shotgun')
      this.fx.muzzle(p.clone().setY(0.8))
      for (const x of [...this.squad, ...this.zombies]) {
        if (x.dead || x.downed) continue
        const d = Math.hypot(x.pos.x - t.x, x.pos.z - t.z)
        if (d < T.radius) x.hurt(T.dmg * (1 - d / (T.radius + 0.6)), null)
      }
      this.noise(t.x, t.z, 22)
      if (survivor) this.toast(`A shotgun trap went off on ${who.data.first}!`, 'bad')
    } else if (t.kind === 'mine') {
      sfx('boom')
      this.fx.explosion(p, 3)
      view.rig.shake = 1
      for (const x of [...this.squad, ...this.zombies]) {
        if (x.dead || x.downed) continue
        const d = Math.hypot(x.pos.x - t.x, x.pos.z - t.z)
        if (d < T.radius) x.hurt(T.dmg * (1 - d / (T.radius + 0.5)), null, { knock: true })
      }
      this.noise(t.x, t.z, 34)
      if (survivor) this.toast(`${who.data.first} stepped on a pipe-bomb trap!`, 'bad')
    }
    this.clearTrap(t, t.kind === 'beartrap')
  },
  // a sprung bear trap stays on the floor, shut
  clearTrap(t, keepModel = false) {
    t.armed = false
    t.ring.visible = false
    t.label?.remove()
    t.label = null
    const g = this.lv.grid
    g.cost[g.i(t.i, t.j)] = 0
    if (!keepModel) {
      this.scene.remove(t.group)
      t.gone = true
    } else t.group.traverse((o) => o.name === 'jawL' && (o.rotation.z = 0.0))
  },
  trapAt(x, y) {
    if (!this.traps?.length) return null
    const ray = screenRay(x, y)
    const hit = new THREE.Vector3()
    let best = null
    let bd = 1e9
    for (const t of this.traps) {
      if (!t.armed || !t.spotted) continue
      if (ray.ray.intersectBox(t.box, hit)) {
        const d = hit.distanceTo(ray.ray.origin)
        if (d < bd) {
          bd = d
          best = t
        }
      }
    }
    return best ? { t: best, d: bd } : null
  },
  disarmTime(agent, t) {
    return t.trap.disarm / (1 + 0.08 * (agent.data.skills.build - 1) + (agent.st.dismantle - 1) * 0.5)
  },
  finishDisarm(agent, t) {
    if (!t.armed) return
    const found = Object.entries(t.trap.yield).map(([r, n]) => ({ r, n }))
    const pos = new THREE.Vector3(t.x, 0.9, t.z)
    this.stow(agent, found, pos)
    view.labels.float(this.scene, pos.clone().setY(1.6), 'Disarmed', 'good')
    gainXP(agent.data, 'build', 5)
    sfx('unlock')
    this.clearTrap(t)
    this.updateHaul()
  },

  // ---------------------------------------------------------------- special infected
  // The squad is looking straight at this spot right now.
  isWatched(z) {
    return !!this.vision && this.vision.seesNow(z.pos.x, z.pos.z) > 0.35
  },
  onScream(z) {
    sfx('scream')
    this.fx.ring(new THREE.Vector3(z.pos.x, 0.2, z.pos.z), '#ffffff', 5)
    this.noise(z.pos.x, z.pos.z, 32, z)
    // a ripple everyone can see, through any wall
    view.labels.add(h('div.ping.loud.heavy'), new THREE.Vector3(z.pos.x, 0.3, z.pos.z), { life: 1.8, scene: this.scene })
    this.toast('A screamer has seen you. More are coming.', 'bad')
    const lv = this.lv
    const mix = zombieMix(this.level).filter((m) => m.t === 'walker' || m.t === 'runner')
    const n = rint(2, 2 + Math.ceil(this.level / 2))
    for (let k = 0; k < n; k++) {
      const end = pick(lv.streetEnds)
      const s = lv.grid.nearestOpen(end[0] + rint(-1, 1), end[1] + rint(-2, 2), 4)
      if (!s) continue
      const nz = this.spawnZombie(weighted(mix).t, s.x, s.z)
      nz.alertTo(z.pos.x, z.pos.z)
    }
  },
  onBurst(z) {
    sfx('burst')
    const p = new THREE.Vector3(z.pos.x, 0.6, z.pos.z)
    this.fx.burst(p, '#8a9a4a', 26, 4, 1.2, 3)
    this.clouds = this.clouds || []
    this.clouds.push({ x: z.pos.x, z: z.pos.z, r: 3.3, t: 7.5 })
  },
  updateClouds(dt) {
    if (!this.clouds?.length) return
    for (const c of [...this.clouds]) {
      c.t -= dt
      if (Math.random() < dt * 14) {
        const a = Math.random() * TAU
        const rr = Math.sqrt(Math.random()) * c.r
        this.fx.smoke(new THREE.Vector3(c.x + Math.cos(a) * rr, 0.3 + Math.random() * 0.8, c.z + Math.sin(a) * rr), { size: 1.6, life: 2.4, color: '#7a8a3a', a: 0.32, vy: 0.25, spread: 0.3 })
      }
      c.tick = (c.tick ?? 0) - dt
      if (c.tick <= 0) {
        c.tick = 0.5
        for (const a of this.squad) {
          if (a.downed || a.dead) continue
          if (Math.hypot(a.pos.x - c.x, a.pos.z - c.z) < c.r) {
            a.hurt(4.5, null, { gas: true })
            a.gassed = (a.gassed || 0) + 0.5
          }
        }
      }
      if (c.t <= 0) this.clouds = this.clouds.filter((x) => x !== c)
    }
  },
}

// ---------------------------------------------------------------- models
function trapModel(kind, horiz) {
  const b = new Builder()
  let extra = null
  if (kind === 'beartrap') {
    // base plate, two toothed jaws open flat, a chain to a stake
    b.cyl(0.2, 0.2, 0.025, { mat: 'steel', color: '#5a5a56', y: 0.02, seg: 18 })
    b.box(0.06, 0.03, 0.06, { mat: 'steel', color: '#4a4a46', y: 0.05 })
    for (const s of [-1, 1]) {
      b.at({ x: s * 0.2, y: 0.03, rz: s * 0.12 }, () => {
        b.tube([[0, 0, -0.2], [s * 0.06, 0, -0.1], [s * 0.08, 0, 0], [s * 0.06, 0, 0.1], [0, 0, 0.2]], 0.012, { mat: 'steel', color: '#6a6a64', seg: 6 })
        for (let k = -3; k <= 3; k++) b.cone(0.012, 0.045, { mat: 'steel', color: '#8a8a82', x: s * 0.07, y: 0.012, z: k * 0.05, rz: -s * Math.PI / 2, seg: 5 })
      })
      b.box(0.36, 0.012, 0.03, { mat: 'steel', color: '#5a5a54', x: s * 0.0, y: 0.03, z: s * 0.17 })
    }
    b.cyl(0.05, 0.05, 0.012, { mat: 'rust', color: '#a8906a', y: 0.045, seg: 10 })
    for (let k = 0; k < 6; k++) b.sphere(0.018, { mat: 'steel', color: '#5a5a54', x: 0.24 + k * 0.035, y: 0.015, z: 0.05 + Math.sin(k) * 0.02, ws: 6, hs: 4 })
    b.cyl(0.015, 0.008, 0.18, { mat: 'steel', color: '#4a4a46', x: 0.46, y: 0.06, z: 0.06, rx: 0.3, seg: 6 })
  } else if (kind === 'tripwire') {
    // a wire across the doorway at shin height, cans strung on it
    const ax = horiz ? 'x' : 'z'
    const p0 = ax === 'x' ? [-0.46, 0.14, 0] : [0, 0.14, -0.46]
    const p1 = ax === 'x' ? [0.46, 0.14, 0] : [0, 0.14, 0.46]
    b.beam(p0, p1, 0.006, 0.006, { mat: 'steel', color: '#c8c8c0', round: true })
    for (const p of [p0, p1]) b.cyl(0.012, 0.012, 0.2, { mat: 'steel', color: '#7a7a72', x: p[0], y: 0.1, z: p[2], seg: 6 })
    for (let k = 0; k < 3; k++) {
      const t = 0.3 + k * 0.2
      const x = p0[0] + (p1[0] - p0[0]) * t
      const z = p0[2] + (p1[2] - p0[2]) * t
      b.cyl(0.033, 0.033, 0.11, { mat: 'metal', color: pick(['#c8b080', '#a8b8c0', '#d0c8b8']), x, y: 0.07, z, rx: 0.4, seg: 10 })
    }
  } else if (kind === 'shotgun') {
    // a sawn-off lashed to a crate, aimed at the doorway, string to the trigger
    b.box(0.42, 0.34, 0.36, { mat: 'planks', color: '#9a8060', y: 0.17 })
    extra = weaponModel('shotgun')
    extra.scale.setScalar(0.85)
    extra.position.set(0, 0.42, -0.05)
    extra.rotation.set(0, horiz ? 0 : Math.PI / 2, 0)
    b.beam([0, 0.4, 0], [horiz ? 0.45 : 0, 0.12, horiz ? 0 : 0.45], 0.004, 0.004, { mat: 'cloth', color: '#d8d0b8', round: true })
    b.box(0.44, 0.03, 0.05, { mat: 'cloth', color: '#5a4a3a', y: 0.36, z: 0.0 })
  } else {
    // pressure plate and a taped pipe bomb with wires
    b.box(0.42, 0.025, 0.42, { mat: 'steel', color: '#4a4c48', y: 0.012 })
    b.box(0.36, 0.012, 0.36, { mat: 'rubber', color: '#2a2a28', y: 0.03 })
    b.cyl(0.045, 0.045, 0.3, { mat: 'steel', color: '#6a6c68', x: 0.34, y: 0.05, z: 0.04, rz: Math.PI / 2, seg: 10 })
    b.box(0.2, 0.05, 0.1, { mat: 'cloth', color: '#5a5a5a', x: 0.34, y: 0.05, z: 0.04 })
    b.tube([[0.2, 0.03, 0.1], [0.24, 0.05, 0.2], [0.32, 0.06, 0.1]], 0.006, { mat: 'paint', color: '#c83a2a', seg: 5 })
    b.tube([[0.18, 0.03, -0.1], [0.26, 0.05, -0.18], [0.36, 0.06, 0.0]], 0.006, { mat: 'paint', color: '#2a6ac8', seg: 5 })
  }
  const g = new THREE.Group()
  g.add(b.build())
  if (extra) g.add(extra)
  g.traverse((o) => {
    if (o.isMesh) {
      o.castShadow = true
      o.receiveShadow = true
    }
  })
  return g
}
