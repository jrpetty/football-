// Characters that walk the tile grid and fight: survivors and zombies.
// Used by horde attacks on the camp and by supply runs. Visuals come from the
// skinned Character; weapons, armor and packs match what each survivor carries.
import * as THREE from 'three'
import { Character, OUTFITS, zombieOutfit, rngFrom, hashStr } from '../models/character.js'
import { weaponModel, holdStyle } from '../models/weapons.js'
import { ZOMBIES, ITEMS } from '../game/data.js'
import { survivorStats, gainXP, equippedItem, wear, S } from '../game/state.js'
import { sfx } from '../core/audio.js'
import { clamp, angleLerp, rand, chance, h } from '../core/util.js'
import { view } from '../render/view.js'

const _v = new THREE.Vector3()
const ZVARIANTS = 10
const ZSKIN = ['#8f9a80', '#7d8a74', '#9a9e8a', '#76826e', '#a0a08e', '#8a9488']

// ---------------------------------------------------------------- looks
export function survivorSpec(s, stats = null) {
  const st = stats || survivorStats(s)
  const a = equippedItem(s, 'armor')
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
    pack: st.pack,
    seed: look.seed,
  }
}
export function survivorLookKey(s) {
  const st = survivorStats(s)
  return [s.occ, equippedItem(s, 'armor')?.id || '', st.pack || '', s.look.seed].join('|')
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

// ---------------------------------------------------------------- base agent
export class Agent {
  constructor(world, ch, x, z) {
    this.world = world
    this.ch = ch
    this.root = ch.root
    this.pos = new THREE.Vector3(x, 0, z)
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
    if (!this.path) {
      this.curSpeed = 0
      return false
    }
    const wp = this.path[this.pi]
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
  dist(o) {
    return Math.hypot(o.pos.x - this.pos.x, o.pos.z - this.pos.z)
  }
  sync() {
    this.root.position.set(this.pos.x, this.pos.y, this.pos.z)
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
    return new THREE.Vector3(this.pos.x, this.pos.y + y, this.pos.z)
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
    const el = h('div.alabel.surv', h('span.nm', this.data.first), bar, prog)
    this.label = view.labels.add(el, () => this.pos, { offsetY: 2.2, scene: this.world.scene })
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
  // order: {type:'move', x, z} | {type:'search'|'dismantle', c} | {type:'attack', z} | {type:'revive', a} | {type:'throw', item, x, z}
  command(order) {
    if (this.downed) return false
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
    } else if (order.type === 'throw') {
      order.t = 0
      this.path = null
    }
    return true
  }
  hurt(dmg, from) {
    if (this.downed || this.dead) return
    const real = dmg * (1 - this.st.dr)
    this.hp -= real
    this.hurtT = 1.2
    this.ch.flinch()
    this.world.fx.blood(this.chestPos(1.2))
    if (this.st.armorItem && this.st.armorItem.cond > 0 && wear(this.st.armorItem, 1)) {
      this.world.toast?.(`${this.data.first}'s ${ITEMS[this.st.armorItem.id].name} is ruined.`, 'bad')
      this.refreshStats()
    }
    if (chance(0.4)) sfx('hurt', 200)
    const canKit = this.world.mode === 'mission' && !this.medkitUsed && this.world.hasMedkit?.(this)
    if (this.hp <= 0) {
      if (canKit) return this.useMedkit()
      this.goDown()
    } else if (this.hp < this.maxHp * 0.3 && canKit) this.useMedkit()
  }
  useMedkit() {
    this.medkitUsed = true
    this.world.consumeMedkit?.(this)
    this.hp = Math.min(this.maxHp, Math.max(this.hp, 0) + this.maxHp * 0.5)
    view.labels.float(this.world.scene, this.chestPos(2), 'First aid kit', 'good')
    sfx('levelup')
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
  ammoType() {
    return this.st.gun ? this.st.ammoType : null
  }
  hasAmmo() {
    if (!this.st.gun) return false
    const t = this.st.ammoType
    return !t || this.world.ammoLeft(t) > 0
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
    let mode = 'idle'
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
          this.order = null
          this.showProg(null)
          view.labels.float(W.scene, a.chestPos(2), 'Back on their feet', 'good')
        }
      }
      this.finish(dt, mode)
      return
    }
    const threat = this.findThreat()
    const busyWork = o && (o.type === 'search' || o.type === 'dismantle') && this.work
    const underAttack = this.hurtT > 0
    if (threat && (!busyWork || underAttack || this.dist(threat) < 2.2)) {
      mode = this.fight(threat, dt)
      this.finish(dt, mode)
      return
    }
    if (o?.type === 'attack' && (!o.z || o.z.dead)) this.order = null
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
        mode = wk.kind === 'search' ? 'search' : 'hammer'
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
    this.finish(dt, mode)
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
    this.cool = st.rate
    if (st.ammoType) W.useAmmo(st.ammoType, 1)
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
    const id = st.weaponId
    sfx(id === 'shotgun' ? 'shotgun' : id === 'rifle' ? 'rifle' : id === 'smg' || id === 'ar' ? 'smg' : id === 'crossbow' ? 'crossbow' : 'pistol', 30)
    W.noise?.(this.pos.x, this.pos.z, st.noise * st.noiseMult)
    if (st.weaponItem && wear(st.weaponItem, 1)) this.weaponBroke()
    if (hit) {
      let dmg = st.dmg * rand(0.85, 1.15)
      if (st.weapon.falloff) dmg *= clamp(1.25 - d / st.range, 0.35, 1.1)
      dmg *= W.dmgBonus?.(this) ?? 1
      z.hurt(dmg, this)
    }
    gainXP(this.data, 'ranged', 0.8)
    return 'aim'
  }
  melee(z, reach) {
    const W = this.world
    const st = this.st
    const armed = !st.gun
    this.cool = armed ? st.rate : 0.8
    this.swing = 1
    sfx('swing', 60)
    const base = armed ? st.dmg : 7
    const dmg = base * rand(0.85, 1.15) * (W.dmgBonus?.(this) ?? 1)
    const knock = armed && st.knock
    setTimeout(() => {
      if (!z.dead && !this.downed && this.dist(z) <= reach + 0.6) {
        z.hurt(dmg, this, { knock })
        sfx('hit', 40)
        if (armed && st.weaponItem && wear(st.weaponItem, 1)) this.weaponBroke()
      }
    }, 160)
    W.noise?.(this.pos.x, this.pos.z, 2 * st.noiseMult)
    gainXP(this.data, 'melee', 0.8)
    return 'swing'
  }
  weaponBroke() {
    this.world.toast?.(`${this.data.first}'s ${ITEMS[this.st.weaponId].name} broke!`, 'bad')
    sfx('dismantle')
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
    const o = { speed: this.curSpeed }
    if (mode === 'swing') {
      anim = this.st.gun ? 'punch' : this.st.weaponId === 'fists' ? 'punch' : 'swing'
      o.swing = 1 - this.swing
    } else if (mode === 'aimIdle') anim = 'aim'
    else if (mode === 'throw') {
      o.swing = this.throwT
    } else if (mode === 'run' || mode === 'walk') {
      anim = this.curSpeed > 3.1 ? 'run' : 'walk'
    }
    if (!this.path) o.speed = 0
    this.ch.update(dt, anim, o)
    this.labelBar.style.width = `${clamp(this.hp / this.maxHp, 0, 1) * 100}%`
    this.labelBar.parentNode.classList.toggle('low', this.hp < this.maxHp * 0.35)
    this.labelEl.classList.toggle('down', this.downed)
    if (this.downed) this.labelEl.querySelector('.nm').textContent = `${this.data.first} · ${Math.ceil(Math.min(this.bleed, 999))}s`
    else if (this._wasDown !== this.downed) this.labelEl.querySelector('.nm').textContent = this.data.first
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
    const outfit = type === 'armored' ? zombieOutfit('riot', r) : zombieOutfit(theme, r)
    const ch = new Character({
      skin: ZSKIN[Math.floor(r() * ZSKIN.length)],
      hair: { style: ['short', 'long', 'bald', 'buzz', 'side', 'curly'][Math.floor(r() * 6)], color: ['#3a3028', '#2a2420', '#5a4a3a', '#6a6a62'][Math.floor(r() * 4)] },
      build: def.build || 1,
      female: r() < 0.4,
      outfit,
      seed,
      zombie: { kind: type },
      cacheKey: `z|${type}|${theme || ''}|${vs}`,
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
    this.deadT = 0
    this.radius = 0.3 * def.scale
    this.wanderT = rand(2, 6)
    this.burn = 0
    this.stun = 0
    this.lured = null
    const bar = h('div.hpbar.z', h('i'))
    this.label = view.labels.add(h('div.alabel.zl', bar), () => this.pos, { offsetY: 1.95 * def.scale, scene: world.scene })
    this.labelBar = bar.firstChild
    this.label.hidden = true
  }
  hurt(dmg, from, o = {}) {
    if (this.dead) return
    const armor = this.def.armor || 0
    const real = dmg * (1 - (from?.st?.gun ? armor : armor * 0.4))
    this.hp -= real
    this.world.fx.blood(this.chestPos(this.def.crawl ? 0.3 : 1.2))
    this.ch.flinch()
    this.label.hidden = false
    if (o.knock && !this.def.crawl && this.type !== 'brute') this.stun = 1.3
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
    this.dead = true
    this.deadT = 0
    this.path = null
    this.label.remove()
    sfx('zdie', 80)
    this.world.fx.blood(this.chestPos(0.6), true)
    if (from?.data) {
      from.data.kills++
      gainXP(from.data, from.st.gun ? 'ranged' : 'melee', this.def.xp)
    }
    this.world.onKill?.(this, from)
  }
  alertTo(x, z) {
    if (this.dead || this.state === 'chase' || this.state === 'fence') return
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
    if (this.groanT <= 0) {
      this.groanT = rand(6, 16)
      if (W.nearCamera?.(this.pos)) sfx('groan', 900)
    }
    if (this.stun > 0) {
      this.stun -= dt
      this.ch.update(dt, 'downed', {})
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
            setTimeout(() => {
              if (!this.dead && !t.dead && this.dist(t) < 1.7) t.hurt(this.dmg * rand(0.8, 1.2), this)
            }, 280)
          }
          anim = this.def.crawl ? 'zcrawl' : 'zattack'
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
          speed = this.speed
          anim = this.def.crawl ? 'zcrawl' : this.def.speed > 2 ? 'zrun' : 'zwalk'
        }
      }
    } else if (this.state === 'investigate') {
      if (!this.path || this.step(dt)) {
        this.state = 'idle'
        this.wanderT = rand(3, 7)
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
    this.label.wrap.style.visibility = this.label.hidden ? 'hidden' : ''
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
      const eff = sight * (1 - (s.st?.stealth || 0))
      if (d > eff) continue
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
