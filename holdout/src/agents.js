// Characters that walk the grid and fight: survivors and zombies.
// Used by supply runs (mission.js) and horde attacks on the camp (base.js).
import * as THREE from 'three'
import { makeHuman, setWeapon, animate, zombieLook } from './models.js'
import { blobShadow, M } from './gfx.js'
import { ZOMBIES, ITEMS } from './data.js'
import { survivorStats, gainXP, equipped } from './state.js'
import { sfx } from './audio.js'
import { clamp, angleLerp, dist, rand, chance, h } from './util.js'

const tmpV = new THREE.Vector3()

export class Agent {
  constructor(world, rig, x, z) {
    this.world = world
    this.rig = rig
    this.root = rig.root
    this.pos = new THREE.Vector3(x, 0, z)
    this.heading = Math.random() * 6
    this.path = null
    this.pi = 0
    this.speed = 3
    this.mode = 'idle'
    this.cool = rand(0, 0.5)
    this.swing = 0
    this.dead = false
    this.radius = 0.28
    this.shadow = blobShadow(0.42)
    this.root.add(this.shadow)
    world.scene.add(this.root)
    this.sync()
  }
  moveTo(x, z) {
    const p = this.world.grid.path(this.pos.x, this.pos.z, x, z)
    this.path = p && p.length ? p : null
    this.pi = 0
    return !!this.path
  }
  stop() {
    this.path = null
  }
  get moving() {
    return !!this.path
  }
  // Follow the current path. Returns true on the frame it arrives.
  step(dt, speedMult = 1) {
    if (!this.path) return false
    const wp = this.path[this.pi]
    const dx = wp.x - this.pos.x
    const dz = wp.z - this.pos.z
    const d = Math.hypot(dx, dz)
    const sp = this.speed * speedMult * dt
    if (d <= sp || d < 0.05) {
      this.pos.x = wp.x
      this.pos.z = wp.z
      this.pi++
      if (this.pi >= this.path.length) {
        this.path = null
        return true
      }
    } else {
      this.pos.x += (dx / d) * sp
      this.pos.z += (dz / d) * sp
      this.heading = angleLerp(this.heading, Math.atan2(dx, dz), 1 - Math.exp(-dt * 12))
    }
    return false
  }
  face(x, z, dt = 1) {
    this.heading = angleLerp(this.heading, Math.atan2(x - this.pos.x, z - this.pos.z), dt >= 1 ? 1 : 1 - Math.exp(-dt * 14))
  }
  dist(o) {
    return Math.hypot(o.pos.x - this.pos.x, o.pos.z - this.pos.z)
  }
  sync() {
    this.root.position.copy(this.pos)
    this.root.rotation.y = this.heading
  }
  // Keep agents from stacking on top of each other.
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
  remove() {
    this.world.scene.remove(this.root)
    this.label?.remove()
  }
}

// ---------------------------------------------------------------- survivors
export class SurvivorAgent extends Agent {
  constructor(world, data, x, z) {
    const rig = makeHuman(lookFor(data))
    super(world, rig, x, z)
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
    this.work = null // { kind, t, total, container }
    this.reviveBy = null
    this.selected = false
    this.medkitUsed = false
    this.leash = 3.2
    this.ring = new THREE.Mesh(new THREE.RingGeometry(0.42, 0.52, 28), new THREE.MeshBasicMaterial({ color: '#f0c060', transparent: true, opacity: 0.9, depthWrite: false }))
    this.ring.rotation.x = -Math.PI / 2
    this.ring.position.y = 0.03
    this.ring.visible = false
    this.root.add(this.ring)
    this.makeLabel()
  }
  refreshStats() {
    this.st = survivorStats(this.data)
    this.maxHp = this.st.maxHp
    this.speed = this.st.speed
    setWeapon(this.rig, this.st.weapon.model)
  }
  makeLabel() {
    const bar = h('div.hpbar', h('i'))
    const prog = h('div.prog', h('i'))
    const el = h('div.alabel.surv', h('span.nm', this.data.first), bar, prog)
    this.label = this.world.labels.add(el, () => this.pos, { offsetY: 2.15, scene: this.world.scene })
    this.labelBar = bar.firstChild
    this.labelProg = prog
    this.labelEl = el
  }
  select(v) {
    this.selected = v
    this.ring.visible = v
    this.labelEl.classList.toggle('sel', v)
  }
  command(order) {
    // order: {type:'move', x, z} | {type:'search'|'dismantle', c} | {type:'attack', z} | {type:'revive', a}
    if (this.downed) return
    this.order = order
    this.work = null
    this.target = null
    if (order.type === 'move') {
      this.moveTo(order.x, order.z)
      this.anchor = { x: order.x, z: order.z }
    } else if (order.type === 'search' || order.type === 'dismantle') {
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
    }
    return true
  }
  hurt(dmg, from) {
    if (this.downed || this.dead) return
    const real = dmg * (1 - this.st.dr)
    this.hp -= real
    this.hurtT = 1.2
    this.world.fx.blood(this.pos)
    if (chance(0.4)) sfx('hurt', 200)
    if (this.hp <= 0) {
      if (this.st.medkit && !this.medkitUsed && this.world.mode === 'mission') {
        this.medkitUsed = true
        this.hp = this.maxHp * 0.5
        this.world.labels.float(this.world.scene, this.pos.clone().setY(2), 'First aid kit', 'good')
        return
      }
      this.goDown()
    } else if (this.hp < this.maxHp * 0.3 && this.st.medkit && !this.medkitUsed && this.world.mode === 'mission') {
      this.medkitUsed = true
      this.hp = Math.min(this.maxHp, this.hp + this.maxHp * 0.5)
      this.world.labels.float(this.world.scene, this.pos.clone().setY(2), 'First aid kit', 'good')
    }
  }
  goDown() {
    this.hp = 0
    this.downed = true
    this.bleed = this.world.mode === 'mission' ? 40 : 1e9
    this.order = null
    this.work = null
    this.path = null
    this.select(false)
    sfx('down')
    this.world.onDowned?.(this)
  }
  revive(frac = 0.35) {
    this.downed = false
    this.hp = Math.max(1, this.maxHp * frac)
    this.bleed = 0
    this.anchor = { x: this.pos.x, z: this.pos.z }
  }
  // Pick the zombie to fight: explicit target, otherwise the nearest threat.
  findThreat() {
    const W = this.world
    if (this.target && !this.target.dead) return this.target
    this.target = null
    const gun = this.st.gun && W.ammoLeft() > 0
    const reach = gun ? this.st.range : Math.max(this.st.range + 0.4, W.mode === 'raid' ? 2.4 : 0)
    let best = null
    let bd = 1e9
    for (const z of W.zombies) {
      if (z.dead) continue
      const d = this.dist(z)
      if (gun) {
        if (d > reach) continue
        if (!W.grid.los(this.pos.x, this.pos.z, z.pos.x, z.pos.z)) continue
      } else {
        // melee: defend the anchor area only
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
    this.swing = Math.max(0, this.swing - dt * 3)
    let mode = 'idle'
    if (this.downed) {
      if (W.mode === 'mission' && !W.paused) {
        this.bleed -= dt
        if (this.bleed <= 0) W.onBledOut?.(this)
      }
      mode = 'down'
      this.finish(dt, mode)
      return
    }
    // Nurse / medic aura heals nearby squadmates.
    if (this.st.aura && W.mode === 'mission') {
      for (const a of W.squad) if (!a.downed && a.dist(this) < 4) a.hp = Math.min(a.maxHp, a.hp + this.st.aura * dt)
    }

    const o = this.order
    const threat = this.findThreat()
    // Walking orders are obeyed even with zombies around.
    if (o?.type === 'move') {
      if (this.step(dt)) this.order = null
      mode = this.path ? 'run' : 'idle'
      if (!this.path) this.order = null
      this.finish(dt, mode)
      return
    }
    if (o?.type === 'revive') {
      const a = o.a
      if (!a.downed || a.dead) {
        this.order = null
      } else if (this.dist(a) > 1.3) {
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
          this.order = null
          this.showProg(null)
          W.labels.float(W.scene, a.pos.clone().setY(2), 'Back on their feet', 'good')
        }
      }
      this.finish(dt, mode)
      return
    }
    // Fight back when threatened (unless mid-walk).
    const busyWork = o && (o.type === 'search' || o.type === 'dismantle') && this.work
    const underAttack = this.hurtT > 0
    if (threat && (!busyWork || underAttack || this.dist(threat) < 2.2)) {
      mode = this.fight(threat, dt)
      this.finish(dt, mode)
      return
    }
    if (o?.type === 'attack') {
      if (!o.z || o.z.dead) this.order = null
    }
    if (o && (o.type === 'search' || o.type === 'dismantle')) {
      const c = o.c
      if (c.gone || (o.type === 'search' && c.searched)) {
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
        mode = wk.kind === 'search' ? 'search' : 'work'
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
    // Drift back to the spot they were told to hold.
    if (this.path) {
      this.step(dt)
      mode = 'walk'
    } else if (Math.hypot(this.anchor.x - this.pos.x, this.anchor.z - this.pos.z) > 0.8) {
      this.moveTo(this.anchor.x, this.anchor.z)
    } else if (W.mode === 'raid') mode = 'guard'
    this.finish(dt, mode)
  }
  fight(z, dt) {
    const W = this.world
    const d = this.dist(z)
    const gun = this.st.gun && W.ammoLeft() > 0
    const reach = gun ? this.st.range : W.mode === 'raid' && W.fenceBetween?.(this, z) ? 2.4 : this.st.range
    if (d > reach) {
      if (gun) {
        // Step toward the target only if it's the explicit order.
        if (this.order?.type === 'attack') {
          if (!this.path || this._repath <= 0) {
            this.moveTo(z.pos.x, z.pos.z)
            this._repath = 0.5
          }
          this._repath -= dt
          this.step(dt)
          return 'run'
        }
        return 'guard'
      }
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
      return 'guard'
    }
    this.path = null
    this.face(z.pos.x, z.pos.z, dt)
    if (this.cool <= 0) {
      const wpn = gun ? this.st.weapon : this.st.gun ? { dmg: 7, rate: 0.8, kind: 'melee', model: 'none' } : this.st.weapon
      const dmgMult = gun || !this.st.gun ? 1 : 0
      this.cool = gun ? this.st.rate : this.st.gun ? 0.8 : this.st.rate
      if (gun) {
        W.useAmmo(1)
        const muzzle = this.rig.handR.getWorldPosition(tmpV).clone()
        muzzle.y += 0.1
        const night = W.isNight?.() ? W.nightAcc?.(this) ?? 0.8 : 1
        let hitP = this.st.acc * night * (1 - clamp((d / this.st.range) * 0.25, 0, 0.25))
        const hit = chance(hitP)
        const endP = z.pos.clone().setY(1.2)
        if (!hit) endP.add(new THREE.Vector3(rand(-0.8, 0.8), rand(-0.3, 0.3), rand(-0.8, 0.8)))
        W.fx.muzzle(muzzle)
        W.fx.tracer(muzzle, endP)
        sfx(this.st.weapon.model === 'shotgun' ? 'shotgun' : this.st.weapon.model === 'rifle' ? 'rifle' : this.st.weapon.model === 'smg' ? 'smg' : this.st.weapon.model === 'crossbow' ? 'crossbow' : 'pistol', 30)
        W.noise(this.pos.x, this.pos.z, this.st.noise * this.st.noiseMult)
        if (hit) {
          let dmg = this.st.dmg * rand(0.85, 1.15)
          if (this.st.weapon.falloff) dmg *= clamp(1.25 - d / this.st.range, 0.35, 1.1)
          dmg *= W.dmgBonus?.(this) ?? 1
          z.hurt(dmg, this)
        }
        this.swing = 1
        gainXP(this.data, 'ranged', 0.8)
        return 'shoot'
      }
      this.swing = 1
      sfx('swing', 60)
      const dmg = (this.st.gun ? 7 : this.st.dmg) * rand(0.85, 1.15) * (dmgMult || 1) * (W.dmgBonus?.(this) ?? 1)
      setTimeout(() => {
        if (!z.dead && this.dist(z) <= reach + 0.6 && !this.downed) {
          z.hurt(dmg, this)
          sfx('hit', 40)
        }
      }, 140)
      W.noise(this.pos.x, this.pos.z, 2 * this.st.noiseMult)
      gainXP(this.data, 'melee', 0.8)
      return 'attack'
    }
    return gun ? 'shoot' : this.swing > 0 ? 'attack' : 'guard'
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
    const anim = mode === 'attack' ? 'attack' : mode === 'shoot' ? 'shoot' : mode
    animate(this.rig, dt, anim, 1, 1 - this.swing)
    this.labelBar.style.width = `${clamp(this.hp / this.maxHp, 0, 1) * 100}%`
    this.labelBar.parentNode.classList.toggle('low', this.hp < this.maxHp * 0.35)
    this.labelEl.classList.toggle('down', this.downed)
    if (this.downed) this.labelEl.querySelector('.nm').textContent = `${this.data.first} · ${Math.ceil(this.bleed)}s`
    else if (this._wasDown !== this.downed) this.labelEl.querySelector('.nm').textContent = this.data.first
    this._wasDown = this.downed
    this.ring.material.opacity = 0.65 + Math.sin(performance.now() / 180) * 0.25
    this.sync()
  }
}

export function lookFor(data) {
  const look = { ...data.look }
  const a = equipped(data, 'armor')
  if (a) look.armor = ITEMS[a].color
  if (equipped(data, 'gear') === 'backpack') look.pack = true
  return look
}

// ---------------------------------------------------------------- zombies
export class ZombieAgent extends Agent {
  constructor(world, type, x, z, level = 1) {
    const def = ZOMBIES[type]
    const rig = makeHuman(zombieLook(type), { zombie: true })
    rig.root.scale.setScalar(def.scale)
    super(world, rig, x, z)
    this.type = type
    this.def = def
    this.faction = 'zombie'
    this.maxHp = def.hp * (1 + 0.18 * (level - 1))
    this.hp = this.maxHp
    this.speed = def.speed * rand(0.9, 1.1)
    this.dmg = def.dmg * (1 + 0.12 * (level - 1))
    this.state = 'idle'
    this.target = null
    this.alert = null
    this.think = rand(0, 0.3)
    this.repath = 0
    this.groanT = rand(3, 12)
    this.deadT = 0
    this.radius = 0.3 * def.scale
    this.wanderT = rand(2, 6)
    const bar = h('div.hpbar.z', h('i'))
    this.label = world.labels.add(h('div.alabel.zl', bar), () => this.pos, { offsetY: 1.9 * def.scale, scene: world.scene })
    this.labelBar = bar.firstChild
    this.label.hidden = true
  }
  hurt(dmg, from) {
    if (this.dead) return
    this.hp -= dmg
    this.world.fx.blood(this.pos)
    this.label.hidden = false
    if (from && (this.state === 'idle' || this.state === 'wander' || this.state === 'investigate')) {
      this.state = 'chase'
      this.target = from
    }
    if (this.hp <= 0) this.die(from)
  }
  die(from) {
    this.dead = true
    this.deadT = 0
    this.path = null
    this.label.remove()
    sfx('zdie', 80)
    this.world.fx.blood(this.pos, true)
    if (from?.data) {
      from.data.kills++
      gainXP(from.data, from.st.gun ? 'ranged' : 'melee', this.def.xp)
    }
    this.world.onKill?.(this, from)
  }
  alertTo(x, z) {
    if (this.dead || this.state === 'chase' || this.state === 'fence') return
    this.state = 'investigate'
    this.alert = { x, z }
    this.moveTo(x, z)
  }
  update(dt) {
    const W = this.world
    if (this.dead) {
      this.deadT += dt
      animate(this.rig, dt, 'dead')
      if (this.deadT > 2.5) this.pos.y = -(this.deadT - 2.5) * 0.4
      this.sync()
      return this.deadT > 5
    }
    this.cool -= dt
    this.swing = Math.max(0, this.swing - dt * 2.5)
    this.think -= dt
    this.groanT -= dt
    if (this.groanT <= 0) {
      this.groanT = rand(6, 16)
      if (W.nearCamera?.(this.pos)) sfx('groan', 900)
    }
    if (this.think <= 0) {
      this.think = 0.3
      this.perceive()
    }
    let mode = 'idle'
    let sp = 1
    if (this.state === 'fence' && W.fenceTick) {
      mode = W.fenceTick(this, dt) || 'walk'
    } else if (this.state === 'chase' && this.target) {
      const t = this.target
      if (t.dead || t.downed) {
        this.target = null
        this.state = 'idle'
      } else {
        const d = this.dist(t)
        if (d <= 1.05 + t.radius) {
          this.path = null
          this.face(t.pos.x, t.pos.z, dt)
          if (this.cool <= 0) {
            this.cool = this.def.rate
            this.swing = 1
            setTimeout(() => {
              if (!this.dead && !t.dead && this.dist(t) < 1.6) t.hurt(this.dmg * rand(0.8, 1.2), this)
            }, 250)
          }
          mode = 'attack'
        } else {
          this.repath -= dt
          if (this.repath <= 0 || !this.path) {
            this.repath = 0.5 + Math.random() * 0.3
            if (!this.moveTo(t.pos.x, t.pos.z)) {
              this.state = 'idle'
              this.target = null
            }
          }
          this.step(dt)
          mode = this.def.speed > 2 ? 'run' : 'walk'
        }
      }
    } else if (this.state === 'investigate') {
      if (!this.path || this.step(dt)) {
        this.state = 'idle'
        this.wanderT = rand(3, 7)
      }
      mode = 'walk'
    } else {
      // idle shuffle
      this.wanderT -= dt
      if (this.path) {
        if (this.step(dt, 0.45)) this.path = null
        mode = 'walk'
        sp = 0.45
      } else if (this.wanderT <= 0) {
        this.wanderT = rand(4, 10)
        const n = W.grid.nearestOpen(this.pos.x + rand(-3, 3), this.pos.z + rand(-3, 3), 3)
        if (n && W.grid.walkLine(this.pos.x, this.pos.z, n.x + 0.5, n.z + 0.5)) this.moveTo(n.x + 0.5, n.z + 0.5)
      }
    }
    animate(this.rig, dt, mode === 'attack' ? 'attack' : mode, sp, 1 - this.swing)
    this.labelBar.style.width = `${clamp(this.hp / this.maxHp, 0, 1) * 100}%`
    this.label.el.style.display = this.label.hidden ? 'none' : ''
    this.sync()
    return false
  }
  perceive() {
    const W = this.world
    if (this.state === 'fence') return
    let sight = this.def.sight * (W.isNight?.() ? 0.75 : 1)
    if (this.state === 'chase') sight *= 1.8
    let best = null
    let bd = 1e9
    for (const s of W.squad) {
      if (s.downed || s.dead) continue
      const d = this.dist(s)
      if (d > sight) continue
      if (!W.grid.los(this.pos.x, this.pos.z, s.pos.x, s.pos.z)) continue
      if (d < bd) {
        bd = d
        best = s
      }
    }
    if (best) {
      if (this.state !== 'chase') {
        if (W.nearCamera?.(this.pos)) sfx('groan', 500)
        W.noise?.(this.pos.x, this.pos.z, 3, this)
      }
      this.state = 'chase'
      this.target = best
    } else if (this.state === 'chase' && (!this.target || this.target.downed || this.dist(this.target) > sight * 1.5)) {
      this.state = 'idle'
      this.target = null
    }
  }
}
