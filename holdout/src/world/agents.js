// Characters that walk the tile grid and fight: survivors and zombies.
// Used by horde attacks on the camp and by supply runs. Visuals come from the
// skinned Character; weapons, armor and packs match what each survivor carries.
import * as THREE from 'three'
import { Character, OUTFITS, zombieOutfit, rngFrom, hashStr } from '../models/character.js'
import { weaponModel, holdStyle } from '../models/weapons.js'
import { ZOMBIES, ITEMS } from '../game/data.js'
import { survivorStats, gainXP, equippedItem, wear, S, researchDone, exposeInfection } from '../game/state.js'
import { INFECTION } from '../game/data.js'
import { sfx } from '../core/audio.js'
import { creditKill, deed, callName } from '../game/deeds.js'
import { clamp, angleLerp, rand, chance, h } from '../core/util.js'
import { view } from '../render/view.js'
import { magOf, setMag, reserveOf, loadRounds, reloadTime } from './mag.js'

const _v = new THREE.Vector3()
const ZVARIANTS = 10
const ZSKIN = ['#8f9a80', '#7d8a74', '#9a9e8a', '#76826e', '#a0a08e', '#8a9488']

// ---------------------------------------------------------------- looks
export function survivorSpec(s, stats = null) {
  const st = stats || survivorStats(s)
  const a = equippedItem(s, 'armor')
  const g = equippedItem(s, 'gear')
  const look = s.look
  return {
    skin: look.skin,
    hair: look.hair,
    face: { beard: look.beard },
    build: look.build,
    height: look.height,
    female: look.female,
    outfit: OUTFITS[s.occ] || OUTFITS.drifter,
    armor: a && a.cond > 0 ? ITEMS[a.id].look : null,
    // what's fitted to the armour and what's worn as gear show too
    armorMods: a && a.cond > 0 ? a.mods || [] : [],
    gear: g ? ITEMS[g.id].look || null : null,
    pack: st.pack,
    seed: look.seed,
    age: s.age,
  }
}
export function survivorLookKey(s) {
  const st = survivorStats(s)
  const a = equippedItem(s, 'armor')
  return [s.occ, a?.id || '', (a?.mods || []).join('+'), ITEMS[equippedItem(s, 'gear')?.id]?.look || '', st.pack || '', s.look.seed, s.age >= 65 ? 'o' : s.age < 16 ? 'c' : ''].join('|')
}
export function makeSurvivorCharacter(s) {
  return new Character(survivorSpec(s))
}
export function armSurvivor(ch, s, show = true) {
  const st = survivorStats(s)
  const it = st.weaponItem
  const id = st.weaponId
  if (id === 'fists' || !show) {
    ch.setWeapon(null)
    return st
  }
  ch.setWeapon(weaponModel(id, it?.mods || []), holdStyle(id, ITEMS[id].kind))
  return st
}

// Little selection ring that sits under a character.
let ringGeo = null
function makeRing(color = '#f0c060') {
  if (!ringGeo) ringGeo = new THREE.RingGeometry(0.42, 0.52, 32)
  const m = new THREE.Mesh(ringGeo, new THREE.MeshBasicMaterial({ color: new THREE.Color(color).multiplyScalar(1.5), transparent: true, opacity: 0.9, depthWrite: false }))
  m.rotation.x = -Math.PI / 2
  m.position.y = 0.04
  m.renderOrder = 2
  return m
}

// path options for people: they ride a running lift
const LIFT = { lift: true }

// ---------------------------------------------------------------- base agent
// Orders that walk to a container and work at it for a while.
const WORK_ORDERS = new Set(['search', 'dismantle', 'hotwire', 'barricade', 'unbarricade'])

export class Agent {
  constructor(world, ch, x, z) {
    this.world = world
    this.ch = ch
    this.root = ch.root
    this.pos = new THREE.Vector3(x, 0, z)
    // where it is drawn: the same as pos, except on a tall building's upper
    // floors (and mid-climb between them)
    this.rpos = new THREE.Vector3(x, 0, z)
    this.climb = null
    this.heading = Math.random() * 6
    this.path = null
    this.pi = 0
    this.speed = 3
    this.curSpeed = 0
    this.cool = rand(0, 0.5)
    this.swing = 0
    this.dead = false
    this.radius = 0.28
    this.root.traverse((o) => {
      if (o.isMesh) {
        o.castShadow = true
        o.receiveShadow = true
      }
    })
    world.scene.add(this.root)
    this.sync()
  }
  moveTo(x, z) {
    // mid-climb, finish the flight first: the new path starts where it ends
    const from = this.climb ? this.climb.to : this.pos
    let p = this.world.grid.path(from.x, from.z, x, z, undefined, this.faction === 'survivor' ? LIFT : null)
    // people go through shut doors, opening them on the way (see step)
    if (!p && this.faction === 'survivor' && this.world.pathThroughDoors) p = this.world.pathThroughDoors(from.x, from.z, x, z, LIFT)
    if (this.climb) {
      // keep the jump in progress at the head of the new path
      this.path = [this.climb.wp, ...(p || [])]
      this.pi = 0
      return true
    }
    this.path = p && p.length ? p : null
    this.pi = 0
    return !!this.path
  }
  stop() {
    if (this.climb) this.path = [this.climb.wp]
    else this.path = null
    this.pi = 0
  }
  // The body stays where it was in the sim until it arrives; the drawing
  // follows the flight (or the lift's car) in between.
  startClimb(wp, speedMult = 1) {
    const W = this.world
    const stairs = wp.link === 'stairs'
    const df = W.levelOf ? Math.abs(W.levelOf(wp.x) - W.levelOf(wp.from.x)) : 1
    const pace = Math.max(0.5, Math.min(1.6, (this.speed * speedMult) / 3))
    this.climb = { t: 0, dur: stairs ? 2.6 / pace : 2.2 + df * 1.1, from: { x: wp.from.x, z: wp.from.z }, to: { x: wp.x, z: wp.z }, wp, kind: wp.link }
    if (!stairs) W.onLift?.(this, wp)
  }
  advanceClimb(dt) {
    const c = this.climb
    c.t += dt
    this.curSpeed = c.kind === 'stairs' ? Math.min(this.speed, 2.2) : 0
    if (c.kind === 'stairs' && this.world.stairHeading) this.heading = angleLerp(this.heading, this.world.stairHeading(c), 1 - Math.exp(-dt * 10))
    if (c.t >= c.dur) this.endClimb()
  }
  endClimb() {
    const c = this.climb
    if (!c) return
    this.climb = null
    this.pos.x = c.to.x
    this.pos.z = c.to.z
    if (this.path && this.path[this.pi] === c.wp) {
      this.pi++
      if (this.pi >= this.path.length) this.path = null
    }
    this.world.onClimbed?.(this, c)
  }
  get moving() {
    return !!this.path
  }
  // Follow the current path. Returns true on the frame it arrives.
  step(dt, speedMult = 1) {
    if (!this.path || this.snared > 0) {
      this.curSpeed = 0
      return false
    }
    const wp = this.path[this.pi]
    if (wp.link) {
      // up or down the stairs, or a ride in the lift: the agent's update
      // carries the climb through from here (see advanceClimb)
      if (!this.climb) this.startClimb(wp, speedMult)
      return false
    }
    // a shut door ahead: people open it, the dead stop and think again
    if (this.world.doorMap?.size) {
      const D = this.world.doorAtPos(wp.x, wp.z)
      // (not one the dead are pounding on from the other side)
      const pounded = this.world.time - (D?.hitT ?? -99) < 4
      if (D?.shut && !(this.faction === 'survivor' && !D.brace && !pounded && this.world.toggleDoor(D, this))) {
        this.path = null
        this.curSpeed = 0
        return false
      }
    }
    const dx = wp.x - this.pos.x
    const dz = wp.z - this.pos.z
    const d = Math.hypot(dx, dz)
    const sp = this.speed * speedMult
    this.curSpeed = sp
    const stepLen = sp * dt
    if (d <= stepLen || d < 0.05) {
      this.pos.x = wp.x
      this.pos.z = wp.z
      this.pi++
      if (this.pi >= this.path.length) {
        this.path = null
        return true
      }
    } else {
      this.pos.x += (dx / d) * stepLen
      this.pos.z += (dz / d) * stepLen
      this.heading = angleLerp(this.heading, Math.atan2(dx, dz), 1 - Math.exp(-dt * 12))
    }
    return false
  }
  face(x, z, dt = 1) {
    this.heading = angleLerp(this.heading, Math.atan2(x - this.pos.x, z - this.pos.z), dt >= 1 ? 1 : 1 - Math.exp(-dt * 14))
  }
  // a sound from this agent: placed in the world where the world knows how
  // (runs and the camp), plain otherwise
  play(id, throttle = 40, opts = {}) {
    const W = this.world
    if (W.sound) W.sound(id, this.pos, { throttle, ...opts })
    else sfx(id, throttle)
  }
  dist(o) {
    if (this.world.fdist) return this.world.fdist(this, o)
    return Math.hypot(o.pos.x - this.pos.x, o.pos.z - this.pos.z)
  }
  sync() {
    const W = this.world
    const r = this.rpos
    if (this.climb && W.mapXZ) {
      // along the flight: from the foot to the top (or the lift's car, up or down)
      const c = this.climb
      const k = Math.min(1, c.t / c.dur)
      const a = W.mapXZ(c.from.x, c.from.z)
      const b = W.mapXZ(c.to.x, c.to.z)
      const e = c.kind === 'stairs' ? k : k * k * (3 - 2 * k)
      r.set(a.x + (b.x - a.x) * e, a.y + (b.y - a.y) * e + (this.pos.y || 0), a.z + (b.z - a.z) * e)
    } else if (W.mapXZ) {
      const a = W.mapXZ(this.pos.x, this.pos.z)
      r.set(a.x, a.y + (this.pos.y || 0), a.z)
    } else r.copy(this.pos)
    this.root.position.copy(r)
    this.root.rotation.y = this.heading
  }
  separate(dt, others) {
    for (const o of others) {
      if (o === this || o.dead || o.downed) continue
      const dx = this.pos.x - o.pos.x
      const dz = this.pos.z - o.pos.z
      const d2 = dx * dx + dz * dz
      const min = this.radius + o.radius
      if (d2 < min * min && d2 > 1e-6) {
        const d = Math.sqrt(d2)
        const push = ((min - d) / d) * 0.5 * Math.min(1, dt * 12)
        const nx = this.pos.x + dx * push
        const nz = this.pos.z + dz * push
        if (this.world.grid.open(Math.floor(nx), Math.floor(nz))) {
          this.pos.x = nx
          this.pos.z = nz
        }
      }
    }
  }
  chestPos(y = 1.3) {
    return new THREE.Vector3(this.rpos.x, this.rpos.y + y, this.rpos.z)
  }
  remove() {
    this.world.scene.remove(this.root)
    this.label?.remove()
    this.ch.dispose()
  }
}

// ---------------------------------------------------------------- survivors
export class SurvivorAgent extends Agent {
  constructor(world, data, x, z) {
    super(world, makeSurvivorCharacter(data), x, z)
    this.data = data
    this.faction = 'survivor'
    this.refreshStats()
    this.hp = Math.min(data.hp, this.maxHp)
    this.anchor = { x, z }
    this.order = null
    this.target = null
    this.downed = false
    this.bleed = 0
    this.hurtT = 0
    this.work = null
    this.selected = false
    this.medkitUsed = false
    this.leash = 3.2
    this.ring = makeRing()
    this.ring.visible = false
    this.root.add(this.ring)
    this.makeLabel()
    this.anim = 'idle'
    this.throwT = 0
  }
  refreshStats() {
    this.st = armSurvivor(this.ch, this.data)
    this.maxHp = this.st.maxHp
    this.speed = this.st.speed
  }
  makeLabel() {
    const bar = h('div.hpbar', h('i'))
    const prog = h('div.prog', h('i'))
    const el = h('div.alabel.surv', h('span.nm', callName(this.data)), bar, prog)
    this.label = view.labels.add(el, () => this.rpos, { offsetY: 2.2, scene: this.world.scene })
    // a survivor's name shows on every floor (dimmed off the floor in view)
    this.label.anyFloor = true
    this.labelBar = bar.firstChild
    this.labelProg = prog
    this.labelEl = el
    el.addEventListener('click', () => this.world.onLabelClick?.(this))
  }
  select(v) {
    this.selected = v
    this.ring.visible = v
    this.labelEl.classList.toggle('sel', v)
  }
  // order: {type:'move', x, z} | {type:'search'|'dismantle'|'hotwire', c} | {type:'attack', z} | {type:'revive', a} | {type:'throw', item, x, z}
  command(order) {
    if (this.downed) return false
    this.order = order
    this.work = null
    this.target = null
    if (order.type === 'move') {
      this.moveTo(order.x, order.z)
      this.anchor = { x: order.x, z: order.z }
    } else if (order.type === 'door') {
      const spot = this.world.doorSpot?.(order.D, this)
      if (!spot) {
        this.order = null
        return false
      }
      order.spot = spot
      this.moveTo(spot.x, spot.z)
    } else if (WORK_ORDERS.has(order.type)) {
      const spot = this.world.accessTile(order.c, this)
      if (!spot) {
        this.order = null
        return false
      }
      order.spot = spot
      this.moveTo(spot.x, spot.z)
      this.anchor = { x: spot.x, z: spot.z }
    } else if (order.type === 'attack') {
      this.target = order.z
    } else if (order.type === 'revive') {
      this.moveTo(order.a.pos.x, order.a.pos.z)
    } else if (order.type === 'throw') {
      order.t = 0
      this.path = null
    }
    return true
  }
  hurt(dmg, from, o = {}) {
    if (this.downed || this.dead) return
    // a gas mask: the gas stings, but it can't get in
    const masked = o.gas && this.st.gasProof
    const real = dmg * (1 - this.st.dr) * (masked ? 0.3 : 1)
    this.hp -= real
    if (!this.npc && !masked && (from?.faction === 'zombie' || o.gas)) {
      if (exposeInfection(this.data, o.gas ? INFECTION.gas : INFECTION.bite, o.gas ? 0 : this.st.dr, o.gas ? null : Math.max(0, this.hp) / this.maxHp)) {
        this.world.toast?.(`${this.data.first} was ${o.gas ? 'poisoned by the gas' : 'bitten'}. Infected!`, 'bad')
        view.labels.float(this.world.scene, this.chestPos(2.1), 'Infected', 'bad')
      }
    }
    this.hurtT = 1.2
    this.ch.flinch()
    this.world.fx.blood(this.chestPos(1.2))
    if (this.st.armorItem && this.st.armorItem.cond > 0 && wear(this.st.armorItem, 1)) {
      this.world.toast?.(`${this.data.first}'s ${ITEMS[this.st.armorItem.id].name} is ruined.`, 'bad')
      this.refreshStats()
    }
    if (chance(0.4)) this.play('hurt', 200)
    const canKit = this.world.mode === 'mission' && !this.medkitUsed && this.world.hasMedkit?.(this)
    if (this.hp <= 0) {
      if (canKit) return this.useMedkit()
      this.goDown()
    } else if (this.hp < this.maxHp * 0.3 && canKit) this.useMedkit()
  }
  useMedkit() {
    this.medkitUsed = true
    this.world.consumeMedkit?.(this)
    this.hp = Math.min(this.maxHp, Math.max(this.hp, 0) + this.maxHp * (researchDone('fieldmed') ? 0.75 : 0.5))
    view.labels.float(this.world.scene, this.chestPos(2), 'First aid kit', 'good')
    sfx('levelup')
  }
  goDown() {
    this.endClimb()
    this.hp = 0
    this.downed = true
    this.bleed = this.world.mode === 'mission' ? 40 : 1e9
    this.order = null
    this.work = null
    this.path = null
    this.select(false)
    this.play('down')
    if (!this.npc) deed(this.data, 'downs')
    this.world.onDowned?.(this)
  }
  revive(frac = 0.35) {
    this.downed = false
    this.hp = Math.max(1, this.maxHp * frac)
    this.bleed = 0
    this.anchor = { x: this.pos.x, z: this.pos.z }
  }
  ammoType() {
    return this.st.gun ? this.st.ammoType : null
  }
  // a gun is worth raising while there is a round in it or more to load
  hasAmmo() {
    if (!this.st.gun) return false
    return magOf(this).n > 0 || reserveOf(this, this.world) > 0
  }
  // Start reloading: false if the magazine is full or there is nothing to load.
  startReload() {
    if (this.reloadT > 0 || !this.st.magCap) return false
    const m = magOf(this)
    const res = reserveOf(this, this.world)
    if (m.n >= m.cap || res <= 0) return false
    this.reloadTac = m.n > 0
    this.reloadT = reloadTime(this.st, m.n, Math.min(m.cap - m.n, res))
    this.cool = Math.max(this.cool, this.reloadT)
    this.play('magOut', 80)
    return true
  }
  tickReload(dt) {
    if (!(this.reloadT > 0)) return
    this.reloadT -= dt
    if (this.reloadT > 0) return
    const m = magOf(this)
    loadRounds(this, this.world, m.cap + (this.reloadTac && !this.st.perShell ? 1 : 0) - m.n)
    this.play(this.st.perShell ? 'rack' : 'magIn', 80)
  }
  findThreat() {
    const W = this.world
    if (this.target && !this.target.dead) return this.target
    this.target = null
    const gun = this.hasAmmo()
    const reach = gun ? this.st.range : Math.max(this.st.range + 0.4, W.mode === 'raid' ? 2.4 : 0)
    let best = null
    let bd = 1e9
    for (const z of W.zombies) {
      if (z.dead) continue
      const d = this.dist(z)
      if (W.canTarget && !W.canTarget(z)) continue
      if (gun) {
        if (d > reach) continue
        if (!W.grid.los(this.pos.x, this.pos.z, z.pos.x, z.pos.z)) continue
      } else {
        const da = Math.hypot(z.pos.x - this.anchor.x, z.pos.z - this.anchor.z)
        if (da > this.leash + 1 && d > reach) continue
        if (d > 6) continue
        if (!W.grid.los(this.pos.x, this.pos.z, z.pos.x, z.pos.z)) continue
      }
      if (d < bd) {
        bd = d
        best = z
      }
    }
    return best
  }
  update(dt) {
    const W = this.world
    this.cool -= dt
    this.hurtT -= dt
    this.swing = Math.max(0, this.swing - dt * 2.2)
    this.tickReload(dt)
    let mode = 'idle'
    if (this.climb) {
      if (this.downed) this.endClimb()
      else {
        this.advanceClimb(dt)
        this.finish(dt, this.climb?.kind === 'lift' ? 'idle' : 'walk')
        return
      }
    }
    if (this.downed) {
      if (W.mode === 'mission' && !W.paused) {
        this.bleed -= dt
        if (this.bleed <= 0) W.onBledOut?.(this)
      }
      this.finish(dt, 'downed')
      return
    }
    if (this.st.aura && W.mode === 'mission') {
      for (const a of W.squad) if (!a.downed && a !== this && a.dist(this) < 4) a.hp = Math.min(a.maxHp, a.hp + this.st.aura * dt)
    }
    const o = this.order
    if (o?.type === 'move') {
      if (this.step(dt)) this.order = null
      mode = this.path ? 'run' : 'idle'
      if (!this.path) this.order = null
      this.finish(dt, mode)
      return
    }
    if (o?.type === 'door') {
      // walk up to a door and shut it, or open it
      const sp = o.spot
      if (this.path) {
        this.step(dt)
        mode = 'run'
      } else if (Math.hypot(sp.x - this.pos.x, sp.z - this.pos.z) > 0.35) {
        this.moveTo(sp.x, sp.z)
        if (!this.path) this.order = null
      } else {
        this.face(o.D.x, o.D.z, 1)
        if (!W.toggleDoor(o.D, this)) view.labels.float(W.scene, this.chestPos(2), o.D.broken ? 'It’s off its hinges' : 'Something’s in the way', 'bad')
        this.order = null
      }
      this.finish(dt, mode)
      return
    }
    if (o?.type === 'throw') {
      this.face(o.x, o.z, dt)
      o.t += dt
      this.throwT = Math.min(1, o.t / 0.7)
      if (o.t >= 0.35 && !o.thrown) {
        o.thrown = true
        W.throwItem?.(this, o.item, o.x, o.z)
      }
      if (o.t >= 0.7) this.order = null
      this.finish(dt, 'throw')
      return
    }
    if (o?.type === 'revive') {
      const a = o.a
      if (!a.downed || a.dead) this.order = null
      else if (this.dist(a) > 1.3) {
        if (!this.path) this.moveTo(a.pos.x, a.pos.z)
        this.step(dt)
        mode = 'run'
      } else {
        this.path = null
        this.face(a.pos.x, a.pos.z, dt)
        o.t = (o.t || 0) + dt
        const need = 3.5 * this.st.revive
        this.showProg(o.t / need)
        mode = 'search'
        if (o.t >= need) {
          a.revive()
          gainXP(this.data, 'medic', 8)
          deed(this.data, 'revives')
          this.order = null
          this.showProg(null)
          view.labels.float(W.scene, a.chestPos(2), 'Back on their feet', 'good')
        }
      }
      this.finish(dt, mode)
      return
    }
    const threat = this.findThreat()
    const busyWork = o && WORK_ORDERS.has(o.type) && this.work
    const underAttack = this.hurtT > 0
    if (threat && (!busyWork || underAttack || this.dist(threat) < 2.2)) {
      mode = this.fight(threat, dt)
      this.finish(dt, mode)
      return
    }
    if (o?.type === 'attack' && (!o.z || o.z.dead)) this.order = null
    if (o && WORK_ORDERS.has(o.type)) {
      const c = o.c
      if (c.gone || (o.type === 'search' && c.searched) || (o.type === 'barricade' && c.barricade) || (o.type === 'unbarricade' && !c.barricade)) {
        this.order = null
        this.work = null
        this.showProg(null)
      } else if (!this.work) {
        if (this.path) {
          this.step(dt)
          mode = 'run'
        } else if (Math.hypot(o.spot.x - this.pos.x, o.spot.z - this.pos.z) > 0.2) {
          this.moveTo(o.spot.x, o.spot.z)
          if (!this.path) this.order = null
        } else {
          const total = W.workTime(this, c, o.type)
          if (total == null) this.order = null
          else this.work = { kind: o.type, t: 0, total, c }
        }
      } else {
        const wk = this.work
        this.face(c.x, c.z, dt)
        wk.t += dt
        this.showProg(wk.t / wk.total)
        mode = wk.kind === 'search' || wk.kind === 'hotwire' ? 'search' : wk.kind === 'barricade' || wk.kind === 'unbarricade' ? 'push' : 'hammer'
        W.workTick?.(this, wk, dt)
        if (wk.t >= wk.total) {
          W.finishWork(this, c, wk.kind)
          this.work = null
          this.order = null
          this.showProg(null)
        }
      }
      this.finish(dt, mode)
      return
    }
    if (this.path) {
      this.step(dt)
      mode = 'walk'
    } else if (Math.hypot(this.anchor.x - this.pos.x, this.anchor.z - this.pos.z) > 0.8 && !this.tower) {
      this.moveTo(this.anchor.x, this.anchor.z)
    } else if (W.mode === 'raid' && this.st.gun) mode = 'aimIdle'
    // a lull: top the magazine up
    if (!this.target && this.st.magCap && !(this.reloadT > 0) && magOf(this).n < magOf(this).cap * 0.5) this.startReload()
    this.finish(dt, mode)
  }
  // First person (render/firstperson.js): the player walks and aims this
  // body, so none of the AI runs; only its own upkeep does: cooldowns,
  // bleeding out, the stairs and the lift (taken on a path), a medic's aura,
  // and the animation the others (and its shadow) show.
  fpUpdate(dt) {
    const W = this.world
    this.cool -= dt
    this.hurtT -= dt
    this.swing = Math.max(0, this.swing - dt * 2.2)
    // the player reloads by hand (R); a swap the AI had started is dropped
    this.reloadT = 0
    if (this.climb) {
      if (this.downed) this.endClimb()
      else {
        this.advanceClimb(dt)
        this.finish(dt, this.climb?.kind === 'lift' ? 'idle' : 'walk')
        return
      }
    }
    if (this.downed) {
      if (W.mode === 'mission' && !W.paused) {
        this.bleed -= dt
        if (this.bleed <= 0) W.onBledOut?.(this)
      }
      this.finish(dt, 'downed')
      return
    }
    if (this.st.aura && W.mode === 'mission') {
      for (const a of W.squad) if (!a.downed && a !== this && a.dist(this) < 4) a.hp = Math.min(a.maxHp, a.hp + this.st.aura * dt)
    }
    if (this.path) {
      this.step(dt)
      this.finish(dt, 'walk')
      return
    }
    this.curSpeed = this.fpSpeed || 0
    this.finish(dt, this.swing > 0 ? 'swing' : this.work ? 'search' : this.curSpeed > 0.3 ? 'walk' : this.st.gun && this.fpAim ? 'aimIdle' : 'idle')
  }
  fight(z, dt) {
    const W = this.world
    const d = this.dist(z)
    const gun = this.hasAmmo()
    const reach = gun ? this.st.range : W.mode === 'raid' && W.fenceBetween?.(this, z) ? 2.4 : this.st.range
    if (d > reach) {
      if (gun) {
        if (this.order?.type === 'attack' && !this.tower) {
          if (!this.path || (this._repath ?? 0) <= 0) {
            this.moveTo(z.pos.x, z.pos.z)
            this._repath = 0.5
          }
          this._repath -= dt
          this.step(dt)
          return 'run'
        }
        return 'aimIdle'
      }
      if (this.tower) return 'idle'
      const da = Math.hypot(z.pos.x - this.anchor.x, z.pos.z - this.anchor.z)
      if (da <= this.leash + 1.5 || this.order?.type === 'attack') {
        if (!this.path || (this._repath ?? 0) <= 0) {
          this.moveTo(z.pos.x, z.pos.z)
          this._repath = 0.4
        }
        this._repath -= dt
        this.step(dt)
        return 'run'
      }
      return 'idle'
    }
    this.path = null
    this.face(z.pos.x, z.pos.z, dt)
    if (this.cool <= 0) {
      if (gun) return this.shoot(z, d)
      return this.melee(z, reach)
    }
    return gun ? 'aim' : this.swing > 0 ? 'swing' : 'idle'
  }
  shoot(z, d) {
    const W = this.world
    const st = this.st
    // the magazine: empty, reload (the shot waits); the last round, reload after
    const m = magOf(this)
    if (m.n <= 0) {
      this.startReload()
      return 'aim'
    }
    setMag(this, m.n - 1)
    this.cool = st.rate
    if (m.n - 1 <= 0) this.reloadNext = true
    const fwd = new THREE.Vector3(Math.sin(this.heading), 0, Math.cos(this.heading))
    const muzzle = this.chestPos(1.38).addScaledVector(fwd, st.weapon.pistol ? 0.55 : 0.85)
    const night = W.isNight?.() ? W.nightAcc?.(this) ?? 0.8 : 1
    const hitP = st.acc * night * (1 - clamp((d / st.range) * 0.25, 0, 0.25)) * (z.def?.crawl ? 0.9 : 1)
    const endP = z.chestPos(z.def?.crawl ? 0.3 : 1.2)
    const hit = chance(hitP)
    if (!hit) endP.add(new THREE.Vector3(rand(-0.9, 0.9), rand(-0.3, 0.4), rand(-0.9, 0.9)))
    W.fx.muzzle(muzzle)
    W.fx.tracer(muzzle, endP, st.weaponId === 'crossbow' ? '#c8b080' : '#ffd890')
    this.ch.fire()
    this.shots = (this.shots || 0) + 1
    const id = st.weaponId
    this.play(id === 'shotgun' ? 'shotgun' : id === 'rifle' ? 'rifle' : id === 'smg' || id === 'ar' ? 'smg' : id === 'crossbow' ? 'crossbow' : 'pistol', 30)
    W.noise?.(this.pos.x, this.pos.z, st.noise * st.noiseMult)
    if (st.weaponItem && wear(st.weaponItem, 1)) this.weaponBroke()
    if (hit) {
      let dmg = st.dmg * rand(0.85, 1.15)
      if (st.weapon.falloff) dmg *= clamp(1.25 - d / st.range, 0.35, 1.1)
      dmg *= W.dmgBonus?.(this) ?? 1
      z.hurt(dmg, this)
    }
    gainXP(this.data, 'ranged', 0.8)
    if (this.reloadNext) {
      this.reloadNext = false
      this.startReload()
    }
    return 'aim'
  }
  melee(z, reach) {
    const W = this.world
    const st = this.st
    const armed = !st.gun
    this.cool = armed ? st.rate : 0.8
    this.swing = 1
    this.play('swing', 60)
    const base = armed ? st.dmg : 7
    const dmg = base * rand(0.85, 1.15) * (W.dmgBonus?.(this) ?? 1)
    const knock = armed && st.knock
    setTimeout(() => {
      if (!z.dead && !this.downed && this.dist(z) <= reach + 0.6) {
        z.hurt(dmg, this, { knock })
        z.play('hit', 40)
        if (armed && st.weaponItem && wear(st.weaponItem, 1)) this.weaponBroke()
      }
    }, 160)
    W.noise?.(this.pos.x, this.pos.z, 2 * st.noiseMult)
    gainXP(this.data, 'melee', 0.8)
    return 'swing'
  }
  weaponBroke() {
    this.world.toast?.(`${this.data.first}'s ${ITEMS[this.st.weaponId].name} broke!`, 'bad')
    this.play('dismantle')
    this.refreshStats()
  }
  showProg(v) {
    if (v == null) {
      this.labelProg.classList.remove('on')
      return
    }
    this.labelProg.classList.add('on')
    this.labelProg.firstChild.style.width = `${clamp(v, 0, 1) * 100}%`
  }
  finish(dt, mode) {
    this.lastMode = mode
    let anim = mode
    // a player aiming over the shoulder keeps the gun up while walking
    const o = { speed: this.curSpeed, aim: !!(this.fp && this.fpAim && this.st.gun), reload: this.reloadT > 0 || !!(this.fp && this.fpReload), crouch: !!(this.fp && this.fpCrouch) }
    if (mode === 'swing') {
      anim = this.st.gun ? 'punch' : this.st.weaponId === 'fists' ? 'punch' : 'swing'
      o.swing = 1 - this.swing
    } else if (mode === 'aimIdle') anim = 'aim'
    else if (mode === 'throw') {
      o.swing = this.throwT
    } else if (mode === 'run' || mode === 'walk') {
      anim = this.curSpeed > 3.1 ? 'run' : 'walk'
    }
    if (!this.path && !this.fp) o.speed = 0
    this.ch.update(dt, anim, o)
    this.labelBar.style.width = `${clamp(this.hp / this.maxHp, 0, 1) * 100}%`
    this.labelBar.parentNode.classList.toggle('low', this.hp < this.maxHp * 0.35)
    this.labelEl.classList.toggle('down', this.downed)
    if (this.downed) this.labelEl.querySelector('.nm').textContent = `${this.data.first} · ${Math.ceil(Math.min(this.bleed, 999))}s`
    else if (this._wasDown !== this.downed) this.labelEl.querySelector('.nm').textContent = callName(this.data)
    this._wasDown = this.downed
    if (this.ring.visible) this.ring.material.opacity = 0.65 + Math.sin(performance.now() / 180) * 0.25
    this.sync()
  }
}

// ---------------------------------------------------------------- zombies
export class ZombieAgent extends Agent {
  constructor(world, type, x, z, level = 1, theme = null) {
    const def = ZOMBIES[type]
    // a handful of looks per type and theme, sharing geometry between zombies
    const vs = Math.floor(Math.random() * ZVARIANTS)
    const seed = (hashStr(type + '|' + (theme || '')) + vs * 7919) % 1000003
    const r = rngFrom(seed)
    const outfit = type === 'armored' ? zombieOutfit('riot', r) : def.stalk || def.scream || def.burst ? zombieOutfit(type, r) : zombieOutfit(theme, r)
    const ch = new Character({
      skin: def.skin || ZSKIN[Math.floor(r() * ZSKIN.length)],
      hair: { style: ['short', 'long', 'bald', 'buzz', 'side', 'curly'][Math.floor(r() * 6)], color: ['#3a3028', '#2a2420', '#5a4a3a', '#6a6a62'][Math.floor(r() * 4)] },
      build: def.build || 1,
      female: r() < 0.4,
      outfit,
      seed,
      zombie: { kind: type },
      cacheKey: `z|${type}|${def.stalk || def.scream || def.burst ? '' : theme || ''}|${vs}`,
    })
    super(world, ch, x, z)
    if (def.scale !== 1) this.root.scale.setScalar(def.scale)
    if (def.crawl) {
      ch.mesh.rotation.x = Math.PI / 2 * 0.95
      ch.mesh.position.y = 0.25
    }
    this.type = type
    this.def = def
    this.faction = 'zombie'
    this.maxHp = def.hp * (1 + 0.18 * (level - 1))
    this.hp = this.maxHp
    this.speed = def.speed * rand(0.9, 1.1)
    this.dmg = def.dmg * (1 + 0.12 * (level - 1))
    this.state = 'idle'
    this.target = null
    this.think = rand(0, 0.3)
    this.repath = 0
    this.groanT = rand(3, 12)
    // each has a voice of its own: big ones low, screamers shrill
    this.voice = (type === 'brute' ? 0.72 : type === 'bloater' ? 0.82 : type === 'screamer' ? 1.3 : type === 'runner' ? 1.08 : 1) * rand(0.88, 1.12)
    this.deadT = 0
    this.radius = 0.3 * def.scale
    this.wanderT = rand(2, 6)
    this.burn = 0
    this.stun = 0
    this.lured = null
    this.screamCool = 0
    this.lunge = 0
    const bar = h('div.hpbar.z', h('i'))
    this.label = view.labels.add(h('div.alabel.zl', bar), () => this.rpos, { offsetY: 1.95 * def.scale, scene: world.scene })
    this.labelBar = bar.firstChild
    this.label.hidden = true
  }
  hurt(dmg, from, o = {}) {
    if (this.dead) return
    const armor = this.def.armor || 0
    let real = dmg * (1 - (from?.st?.gun ? armor : armor * 0.4))
    if (from?.faction === 'survivor' && (this.def.stalk || this.def.scream || this.def.burst) && researchDone('biology')) real *= 1.25
    this.hp -= real
    this.world.fx.blood(this.chestPos(this.def.crawl ? 0.3 : 1.2))
    this.ch.flinch()
    this.label.hidden = false
    // a knock-down blow (a sledge always; a pan's ring now and then)
    if (o.knock && (o.knock >= 1 || Math.random() < o.knock) && !this.def.crawl && this.type !== 'brute') this.stun = 1.3
    if (from && from.faction === 'survivor' && ['idle', 'wander', 'investigate', 'lured'].includes(this.state)) {
      this.state = 'chase'
      this.target = from
    }
    if (this.hp <= 0) this.die(from)
  }
  ignite(sec) {
    this.burn = Math.max(this.burn, sec)
  }
  die(from) {
    this.endClimb()
    this.dead = true
    this.deadT = 0
    this.path = null
    this.label.remove()
    // a takedown leaves nothing to hear
    if (!this.quiet) this.play('zdie', 80, { pitch: this.voice })
    if (this.def.burst) this.world.onBurst?.(this)
    this.world.fx.blood(this.chestPos(0.6), true)
    if (from?.data) {
      from.data.kills++
      gainXP(from.data, from.st.gun ? 'ranged' : 'melee', this.def.xp)
      if (!from.npc) creditKill(from.data, this, from.st.weaponId, this.world.mode !== 'mission')
    }
    this.world.onKill?.(this, from)
  }
  alertTo(x, z) {
    if (this.dead || this.state === 'chase' || this.state === 'fence' || this.state === 'bash') return
    this.state = 'investigate'
    this.moveTo(x, z)
  }
  update(dt) {
    const W = this.world
    if (this.dead) {
      this.deadT += dt
      this.ch.update(dt, 'dead', {})
      if (this.deadT > 4) this.pos.y = -(this.deadT - 4) * 0.35
      this.sync()
      return this.deadT > 7
    }
    if (this.burn > 0) {
      this.burn -= dt
      this.hp -= dt * 14
      if (Math.random() < dt * 12) W.fx.ember(this.chestPos(rand(0.4, 1.6)), 0.5)
      if (Math.random() < dt * 4) W.fx.smoke(this.chestPos(1.6), { size: 0.5, life: 1.2, color: '#3a3430', a: 0.3 })
      if (this.hp <= 0) return this.die(null), false
    }
    this.cool -= dt
    this.swing = Math.max(0, this.swing - dt * 2.5)
    this.think -= dt
    this.groanT -= dt
    this.screamCool -= dt
    this.lunge = Math.max(0, this.lunge - dt)
    if (this.groanT <= 0) {
      // closer to their prey, they are louder and more often
      this.groanT = this.state === 'chase' ? rand(3, 7) : rand(6, 16)
      if (!this.def.stalk) {
        if (W.sound) this.play('groan', 650, { pitch: this.voice })
        else if (W.nearCamera?.(this.pos)) sfx('groan', 900)
      }
    }
    if (this.climb) {
      this.advanceClimb(dt)
      this.ch.update(dt, this.climb ? (this.def.crawl ? 'zcrawl' : 'zwalk') : 'zidle', { speed: this.curSpeed })
      this.sync()
      return false
    }
    if (this.stun > 0) {
      this.stun -= dt
      // held from behind it stays on its feet until it drops
      this.ch.update(dt, this.takenDown ? 'zidle' : 'downed', {})
      this.sync()
      return false
    }
    if (this.think <= 0) {
      this.think = 0.3
      this.perceive()
    }
    let anim = this.def.crawl ? 'zcrawl' : 'zidle'
    let speed = 0
    if (this.state === 'fence' && W.fenceTick) {
      const m = W.fenceTick(this, dt) || 'walk'
      anim = m === 'attack' ? 'zattack' : this.def.crawl ? 'zcrawl' : this.def.speed > 2 ? 'zrun' : 'zwalk'
      speed = m === 'attack' ? 0 : this.speed
    } else if (this.state === 'lured' && this.lured) {
      if (this.lured.until < W.time) {
        this.lured = null
        this.state = 'idle'
      } else {
        if (!this.path && Math.hypot(this.lured.x - this.pos.x, this.lured.z - this.pos.z) > 1.2) this.moveTo(this.lured.x, this.lured.z)
        this.step(dt)
        speed = this.path ? this.speed : 0
        anim = this.def.crawl ? 'zcrawl' : this.path ? 'zwalk' : 'zidle'
      }
    } else if (this.state === 'stalk' && this.target) {
      // creep closer while unseen; freeze when watched; lunge from close in
      const t = this.target
      const d = t.dead || t.downed ? 1e9 : this.dist(t)
      if (d > 28) {
        this.state = 'idle'
        this.target = null
      } else if (d <= 4.6) {
        this.state = 'chase'
        this.lunge = 1.3
        this.play('groan', 300, { pitch: this.voice, loud: 1.2 })
        W.noise?.(this.pos.x, this.pos.z, 4, this)
      } else {
        const watched = W.isWatched?.(this) && d > 6
        if (watched) {
          this.path = null
          this.face(t.pos.x, t.pos.z, dt)
          anim = 'zidle'
        } else {
          this.repath -= dt
          if (this.repath <= 0 || !this.path) {
            this.repath = 0.6
            this.moveTo(t.pos.x, t.pos.z)
          }
          const night = W.isNight?.() ? 1.3 : 1
          this.step(dt, 0.75 * night)
          speed = this.speed * 0.75 * night
          anim = 'zwalk'
        }
      }
    } else if (this.state === 'chase' && this.target) {
      const t = this.target
      if (t.dead || t.downed) {
        this.target = null
        this.state = 'idle'
      } else {
        const d = this.dist(t)
        if (d <= 1.05 + t.radius && Math.abs((t.pos.y || 0) - (this.pos.y || 0)) < 1) {
          this.path = null
          this.face(t.pos.x, t.pos.z, dt)
          if (this.cool <= 0) {
            this.cool = this.def.rate
            this.swing = 1
            const k = this.lunge > 0 ? 1.6 : 1
            this.lunge = 0
            setTimeout(() => {
              if (!this.dead && !t.dead && this.dist(t) < 1.7) t.hurt(this.dmg * k * rand(0.8, 1.2), this)
            }, 280)
          }
          anim = this.def.crawl ? 'zcrawl' : 'zattack'
        } else {
          this.repath -= dt
          if (this.repath <= 0 || !this.path) {
            this.repath = 0.5 + Math.random() * 0.3
            // shut in behind a barricade? then break it down (a path would
            // only lead up to the wall)
            const bar = W.barricadeToward?.(this, t)
            if (bar) {
              this.state = 'bash'
              this.bash = bar
              this.moveTo(bar.outPos.x, bar.outPos.z)
            } else if (!this.moveTo(t.pos.x, t.pos.z)) {
              // a shut door in the way: break it down
              const door = W.doorToward?.(this, t)
              if (door) {
                this.state = 'bash'
                this.bash = door
              } else {
                this.state = 'idle'
                this.target = null
              }
            }
          }
          const boost = this.lunge > 0 ? 1.75 : 1
          this.step(dt, boost)
          speed = this.speed * boost
          anim = this.def.crawl ? 'zcrawl' : this.def.speed * boost > 2 ? 'zrun' : 'zwalk'
        }
      }
    } else if (this.state === 'bash' && this.bash) {
      // at a barricade: hammer at it until it gives, then go for whoever is inside
      const b = this.bash
      if (b.broken || !b.c.barricade) {
        this.bash = null
        this.state = this.target && !this.target.dead ? 'chase' : 'idle'
        this.repath = 0
      } else {
        const d = Math.hypot(b.outPos.x - this.pos.x, b.outPos.z - this.pos.z)
        if (d > 0.75) {
          this.repath -= dt
          if (!this.path && this.repath <= 0) {
            this.repath = 1
            if (!this.moveTo(b.outPos.x, b.outPos.z)) {
              this.state = 'idle'
              this.bash = null
            }
          }
          this.step(dt)
          speed = this.speed
          anim = this.def.crawl ? 'zcrawl' : 'zwalk'
        } else {
          this.path = null
          this.face(b.c.x, b.c.z, dt)
          if (this.cool <= 0) {
            this.cool = this.def.rate
            this.swing = 1
            setTimeout(() => !this.dead && W.bashBarricade?.(b, this), 280)
          }
          anim = this.def.crawl ? 'zcrawl' : 'zattack'
        }
      }
    } else if (this.state === 'trail' && this.trail) {
      // following the squad's prints in the snow back the way they came
      if (!this.path) {
        const next = W.tracks?.trailBack(this.trail)
        if (!next) {
          this.state = 'idle'
          this.trail = null
          this.wanderT = rand(8, 16)
        } else {
          this.trail = next
          this.moveTo(next.x, next.z)
        }
      }
      if (this.path) this.step(dt, 0.7)
      speed = this.speed * 0.7
      anim = this.def.crawl ? 'zcrawl' : 'zwalk'
    } else if (this.state === 'investigate') {
      if (!this.path || this.step(dt)) {
        // drawn to the hammering at a barricade with someone behind it: join in
        const bar = W.barricadeNear?.(this)
        if (bar) {
          this.state = 'bash'
          this.bash = bar
          this.moveTo(bar.outPos.x, bar.outPos.z)
        } else {
          this.state = 'idle'
          this.wanderT = rand(3, 7)
        }
      }
      speed = this.speed
      anim = this.def.crawl ? 'zcrawl' : 'zwalk'
    } else {
      this.wanderT -= dt
      if (this.path) {
        if (this.step(dt, 0.45)) this.path = null
        speed = this.speed * 0.45
        anim = this.def.crawl ? 'zcrawl' : 'zwalk'
      } else if (this.wanderT <= 0) {
        this.wanderT = rand(4, 10)
        const n = W.grid.nearestOpen(this.pos.x + rand(-3, 3), this.pos.z + rand(-3, 3), 3)
        if (n && W.grid.walkLine(this.pos.x, this.pos.z, n.x + 0.5, n.z + 0.5)) this.moveTo(n.x + 0.5, n.z + 0.5)
      }
    }
    this.ch.update(dt, anim, { speed: this.path ? speed : 0, swing: 1 - this.swing })
    this.labelBar.style.width = `${clamp(this.hp / this.maxHp, 0, 1) * 100}%`
    this.label.wrap.style.visibility = this.label.hidden || this.fogHidden ? 'hidden' : ''
    this.sync()
    return false
  }
  perceive() {
    const W = this.world
    if (this.state === 'fence') return
    let sight = this.def.sight * (W.isNight?.() ? 0.75 : 1) * (W.sightMult ?? 1)
    if (this.state === 'chase') sight *= 1.8
    let best = null
    let bd = 1e9
    for (const s of W.squad) {
      if (s.downed || s.dead) continue
      const d = this.dist(s)
      let eff = sight * (1 - (s.st?.stealth || 0))
      // crouched: seen late ahead, hardly at all from behind
      if (s.fpCrouch && this.state !== 'chase') {
        const ahead = d > 0.01 ? ((s.pos.x - this.pos.x) * Math.sin(this.heading) + (s.pos.z - this.pos.z) * Math.cos(this.heading)) / d : 1
        eff *= ahead > 0.2 ? 0.55 : ahead > -0.2 ? 0.3 : 0.11
      }
      if (d > eff) continue
      if (!W.grid.los(this.pos.x, this.pos.z, s.pos.x, s.pos.z)) continue
      if (d < bd) {
        bd = d
        best = s
      }
    }
    if (best) {
      if (this.def.scream && this.screamCool <= 0 && W.mode === 'mission') {
        this.screamCool = 11
        W.onScream?.(this)
      }
      if (this.def.stalk && this.state !== 'chase') {
        this.state = 'stalk'
        this.target = best
        return
      }
      if (this.state !== 'chase') {
        if (W.sound) this.play('groan', 500, { pitch: this.voice, loud: 1.15 })
        else if (W.nearCamera?.(this.pos)) sfx('groan', 500)
        W.noise?.(this.pos.x, this.pos.z, 3, this)
      }
      this.state = 'chase'
      this.target = best
    } else if (this.state === 'stalk') {
      // keeps hunting its last target even out of sight
    } else if (this.state === 'idle' && W.trackNear) {
      const p = W.trackNear(this)
      if (p) {
        this.state = 'trail'
        this.trail = p
        this.path = null
      }
    } else if (this.state === 'chase' && (!this.target || this.target.downed || this.dist(this.target) > sight * 1.5)) {
      this.state = 'idle'
      this.target = null
    }
  }
}
