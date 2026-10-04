// First person in the camp (see render/firstperson.js): walk the camp as one
// of its people. Look at a station and press E to run it, climb a
// watchtower to see over the wall, open the gate, talk to the others. When
// a horde comes you fight it yourself: the same survivor, in the raid's
// squad, with the gun or blade they carry. Their camp job waits while you
// have them.
import * as THREE from 'three'
import { view } from '../render/view.js'
import { OUTFITS } from '../models/character.js'
import { fpLook } from '../models/viewmodel.js'
import { slideMove, fpShoot, fpMelee } from '../world/fpcombat.js'
import { hideBody } from './missionfp.js'
import { S, NET, survivorStats, bounds, gateTiles, getS, stationSize } from '../game/state.js'
import { ITEMS, RES, STATIONS } from '../game/data.js'
import { sfx } from '../core/audio.js'
import { clamp } from '../core/util.js'

const FENCE_H = [1.9, 2.5, 2.6, 2.6]
const _ray = new THREE.Raycaster()

export const BaseFPMixin = {
  fpWhyNot() {
    if (S.over) return 'The camp has fallen.'
    if (NET.role === 'client' && S.raid) return 'Your friend’s horde is being fought on their screen: watch from above.'
    return 'Nobody in camp is fit to walk about right now.'
  },
  fpCan() {
    if (S.over || this.game.titleEl) return false
    if (NET.role === 'client' && S.raid && this.mode !== 'raid') return false
    return !!this.fpPickId()
  },
  fpPickId() {
    const ok = (s) => s && (s.status === 'ok' || s.status === 'injured') && this.people.list.has(s.id)
    const raidOk = (s) => !S.raid || this.squad.some((a) => a.data.id === s.id && !a.downed)
    const pref = [this.selectedPerson && getS(this.selectedPerson), getS(this.fpSid)]
    for (const s of pref) if (ok(s) && raidOk(s)) return s.id
    for (const s of S.survivors) if (ok(s) && s.status === 'ok' && raidOk(s)) return s.id
    return null
  },
  fpEnter() {
    const id = this.fpPickId()
    if (!id) return false
    if (this.placing) this.cancelPlacing()
    if (this.linking) this.cancelLinking?.()
    if (this.beltTool) this.cancelBeltTool?.()
    this.multi?.clear?.()
    this.hovered = null
    this.game.ui?.hoverTip?.(null)
    this.fpOn = true
    this.fpSid = id
    this.fpTower = null
    this.fpStub = null
    this.atmo.fp = true
    this.atmo.fpFog = [45, 205]
    this.world.cullView.wide = 26
    const w = this.people.list.get(id)
    if (w) {
      w.leaveBed?.()
      w.y = 0
      w.pos.y = 0
      // stepping out of a station's footprint
      if (!this.grid.open(Math.floor(w.pos.x), Math.floor(w.pos.z))) {
        const n = this.grid.nearestOpen(w.pos.x, w.pos.z, 4)
        if (n) w.pos.set(n.x + 0.5, 0, n.z + 0.5)
      }
    }
    this.fpPrep()
    return true
  },
  // every frame: the dense grass follows the feet
  fpTick() {
    // placing a building is done from above
    if (this.placing || this.linking || this.beltTool) {
      this.game.toggleFirstPerson(false)
      return
    }
    const p = this.fpPos()
    if (p) this.world.setNearGrass(true, p.x, p.z)
  },
  fpExit() {
    this.world.setNearGrass(false)
    this.world.cullView.wide = 0
    this.world.culled = false
    this.fpRestore()
    this.fpOn = false
    this.fpTower = null
    this.atmo.fp = false
    this.fpBodyRef = null
  },
  // the body in play: in a raid the survivor's fighting agent, otherwise
  // their camp worker (looked up fresh: a worker is rebuilt when its looks
  // change)
  fpBody() {
    const id = this.fpSid
    if (S.raid && this.mode === 'raid') {
      const a = this.squad.find((x) => x.data.id === id)
      if (a) return { a, s: a.data }
    }
    const w = this.people.list.get(id)
    return w ? { w, s: w.s } : null
  },
  // mark whoever is the body now (and let go of whoever was)
  fpPrep() {
    const B = this.fpBody()
    const obj = B?.a || B?.w || null
    if (obj === this.fpBodyRef) return B
    this.fpRestore()
    this.fpBodyRef = obj
    if (!obj) return B
    if (B.a) {
      B.a.fp = true
      B.a.order = null
      B.a.path = null
      B.a.target = null
      if (B.a.label) B.a.label.hidden = true
      B.a.ring.visible = false
      this.fpTower = B.a.tower ? { st: B.a.tower, x: B.a.pos.x, z: B.a.pos.z } : this.fpTower
    } else {
      B.w.manual = true
      B.w.path = null
      B.w.goal = null
      this.people.release?.(B.s.id)
      if (B.w.label) B.w.label.hidden = true
      if (B.w.ring) B.w.ring.visible = false
      if (B.w.crate) B.w.crate.visible = false
      B.w.setTool?.(null)
      // the worker carries what the survivor fights with
      const st = survivorStats(B.s)
      if (st.weaponId !== 'fists') B.w.setTool?.(st.gun ? 'gun' : 'melee')
    }
    hideBody(obj.ch, true)
    return B
  },
  fpRestore() {
    const o = this.fpBodyRef
    if (!o) return
    hideBody(o.ch, false)
    if (o.label) o.label.hidden = false
    if ('manual' in o || o.s) {
      o.manual = false
      o.goal = null
      o.think = 0
      o.path = null
      if (o.pos) o.pos.y = 0
      o.y = 0
    }
    if (o.faction === 'survivor' && o.fp) {
      o.fp = false
      o.anchor = { x: o.pos.x, z: o.pos.z }
      o.ring.visible = !!o.selected
    }
    this.fpBodyRef = null
  },
  fpAlive() {
    const B = this.fpPrep()
    if (!B) return false
    if (B.a) return !B.a.downed && !B.a.dead
    return B.s.status === 'ok' || B.s.status === 'injured'
  },
  fpNext() {
    const B = this.fpBody()
    const was = B?.s?.first
    this.fpTower = null
    const id = this.fpPickId()
    if (!id || id === this.fpSid) return false
    this.fpSid = id
    this.fpPrep()
    this.game.ui?.toast(`${was || 'They'} went down. You're ${getS(id)?.first} now.`, 'bad')
    return true
  },
  fpPos() {
    const B = this.fpBody()
    return B.a ? B.a.pos : B.w.pos
  },
  fpFeet() {
    return this.fpPos()
  },
  fpEye(out) {
    const B = this.fpBody()
    const p = B.a ? B.a.rpos : B.w.pos
    const y = B.a?.tower ? B.a.pos.y : this.fpTower ? this.fpTowerY() : p.y
    return out.set(p.x, y + 1.6 * (B.s.look?.height || 1), p.z)
  },
  fpTowerY() {
    const v = this.stationViews.get(this.fpTower.st.id)
    return v?.info.platformY || 3
  },
  fpSpeed() {
    const B = this.fpBody()
    return B.a ? B.a.speed : survivorStats(B.s).speed || 3.2
  },
  fpMove(dx, dz) {
    const B = this.fpBody()
    const p = B.a ? B.a.pos : B.w.pos
    // on a watchtower's platform: a step or two either way
    if (this.fpTower) {
      const T = this.fpTower
      p.x = clamp(p.x + dx, T.x - 0.7, T.x + 0.7)
      p.z = clamp(p.z + dz, T.z - 0.7, T.z + 0.7)
      p.y = this.fpTowerY()
      return { x: dx, z: dz }
    }
    if (B.a?.snared > 0) return { x: 0, z: 0 }
    return slideMove((i, j) => this.grid.open(i, j), p, dx, dz, 0.22)
  },
  fpFace(heading, speed, run) {
    const B = this.fpBody()
    if (B.a) {
      B.a.heading = heading
      B.a.fpSpeed = speed
      B.a.fpRun = run
      return
    }
    const w = B.w
    w.heading = heading
    const anim = speed > 3.1 ? 'run' : speed > 0.3 ? 'walk' : 'idle'
    w.ch.update(1 / 60, anim, { speed })
    w.root.position.set(w.pos.x, this.fpTower ? this.fpTowerY() : 0, w.pos.z)
    w.root.rotation.y = heading
  },
  fpStep() {},
  fpAccuracy() {
    const B = this.fpBody()
    const st = B.a ? B.a.st : survivorStats(B.s)
    return (1 - st.acc) * 0.075 + 0.004
  },
  // the one who shoots: the raid agent, or a stand-in for a camp worker
  fpShooter() {
    const B = this.fpBody()
    if (B.a) return B.a
    const s = B.s
    let st = this.fpStub
    if (!st || st.data !== s) {
      const base = this
      st = this.fpStub = {
        faction: 'survivor',
        data: s,
        radius: 0.28,
        cool: 0,
        swing: 0,
        downed: false,
        dead: false,
        get pos() {
          return base.people.list.get(s.id)?.pos || new THREE.Vector3()
        },
        get rpos() {
          return this.pos
        },
        play: (id, t) => sfx(id, t),
        weaponBroke() {
          base.game.ui?.toast(`${s.first}'s ${ITEMS[this.st.weaponId]?.name || 'weapon'} broke!`, 'bad')
          this.st = survivorStats(s)
        },
        // a straggler outside the wall got close
        hurt(dmg) {
          s.hp = Math.max(1, (s.hp ?? 100) - dmg * (1 - (this.st.dr || 0)))
          base.fx.blood(this.pos.clone().setY(1.2))
          if (s.hp < 25 && s.status === 'ok') {
            s.status = 'injured'
            base.game.ui?.toast(`${s.first} is badly hurt.`, 'bad')
          }
        },
        get hp() {
          return s.hp ?? 100
        },
      }
    }
    st.st = st.stT > performance.now() ? st.st : survivorStats(s)
    st.stT = performance.now() + 1000
    return st
  },
  fpWorld() {
    if (this._fpW) return this._fpW
    const base = this
    this._fpW = {
      get zombies() {
        return [...(base.zombies || []), ...(base.wanderers || [])]
      },
      get fx() {
        return base.fx
      },
      fpSolid: (x, y, z) => base.fpSolid(x, y, z),
      ammoLeft: (t) => Math.floor(S.res[t] || 0),
      useAmmo: (t, n) => (S.res[t] = Math.max(0, (S.res[t] || 0) - n)),
      noise: () => {},
      dmgBonus: (a) => (a.tower ? base.dmgBonus?.(a) ?? 1 : base.fpTower ? 1.25 : 1),
    }
    return this._fpW
  },
  fpAttack(origin, dir, o) {
    if (NET.role === 'client' && S.raid && this.mode !== 'raid') return null
    const a = this.fpShooter()
    const W = this.fpWorld()
    const st = a.st
    if (st.gun && (!st.ammoType || W.ammoLeft(st.ammoType) > 0)) return fpShoot(a, W, origin, dir, o)
    if (st.gun) {
      this.fpTipT = performance.now() + 2500
      this.fpTipText = `Out of ${RES[st.ammoType]?.name.toLowerCase() || 'ammo'}: you swing it instead.`
    }
    return fpMelee(a, W, origin, dir, { ...o, delay: clamp(st.rate * 0.85, 0.32, 0.9) * 0.42 })
  },
  // what a bullet stops at: the wall (all of it is solid: shoot from a
  // tower, a breach or the gate), stations, trees and ruins outside
  fpSolid(x, y, z) {
    if (y < 0.02) return true
    const g = this.grid
    const i = Math.floor(x)
    const j = Math.floor(z)
    if (!g.inb(i, j)) return false
    const idx = g.i(i, j)
    const own = g.owner[idx]
    if (own === 'fence' || own === 'gate') {
      if (g.cost[idx] !== 255) return false
      return y < FENCE_H[S.fence?.level ?? 0] ?? 2
    }
    if (g.cost[idx] !== 255) return false
    if (own === 'world') return y < 6
    if (own === 'decor') return y < 1.6
    if (own && typeof own === 'object' && own.type) return y < (STATIONS[own.type]?.fpH || 2.4) && !(this.fpTower && own === this.fpTower.st)
    return y < 1.2
  },
  fpStatus() {
    const B = this.fpBody()
    const s = B.s
    const st = B.a ? B.a.st : this.fpShooter().st
    const it = st.weaponItem
    let ammo = ''
    if (st.gun) ammo = st.ammoType ? `<b>${Math.floor(S.res[st.ammoType] || 0)}</b> ${RES[st.ammoType]?.name.toLowerCase() || ''}` : '<b>∞</b> bolts'
    if (it && ITEMS[st.weaponId]?.dur) ammo += `<small>${Math.round((it.cond / ITEMS[st.weaponId].dur) * 100)}% condition</small>`
    if (!this.fpLookCache || this.fpLookCache.id !== s.id) this.fpLookCache = { id: s.id, look: fpLook(s, OUTFITS[s.occ] || OUTFITS.drifter) }
    const hp = B.a ? B.a.hp : s.hp ?? 100
    const maxHp = B.a ? B.a.maxHp : st.maxHp || 100
    return {
      name: `${s.first} · ${Math.ceil(Math.max(0, hp))} hp`,
      hp,
      maxHp,
      weapon: ITEMS[st.weaponId]?.name || 'Fists',
      ammo,
      weaponId: st.weaponId,
      mods: it?.mods || [],
      look: this.fpLookCache.look,
      lookKey: s.id,
      prog: null,
      tip: (this.fpTipT || 0) > performance.now() ? this.fpTipText : this.fpTower ? 'On the watchtower: E to climb down' : '',
      torch: false,
    }
  },
  fpShade() {
    return 1
  },
  // what is under the crosshair: a station to run, a tower to climb, the
  // gate, someone to talk to
  fpLook(eye, dir) {
    if (this.fpTower) {
      const T = this.fpTower
      return { text: `Climb down from the ${STATIONS[T.st.type]?.name.toLowerCase() || 'tower'}`, act: () => this.fpClimbDown() }
    }
    // the stranger at the gate (they talk through it)
    if (this.visitor?.ch) {
      const v = this.visitor.ch.root.position
      const p = this.fpPos()
      const dx = v.x - p.x
      const dz = v.z - p.z
      const d = Math.hypot(dx, dz)
      if (d < 4.6 && (dx * dir.x + dz * dir.z) / (d * (Math.hypot(dir.x, dir.z) || 1)) > 0.7) return { text: 'Talk to the stranger at the gate', act: () => this.fpOpen(() => this.game.ui?.showRecruit()) }
    }
    _ray.set(eye, dir)
    _ray.far = 5
    const objs = []
    for (const v of this.stationViews.values()) objs.push(v.group)
    const B = this.fpBody()
    for (const w of this.people.workers) if (w !== B.w && w.root.visible) objs.push(w.root)
    const hits = _ray.intersectObjects(objs, true)
    for (const h of hits) {
      let o = h.object
      while (o && !o.userData.pick) o = o.parent
      const pk = o?.userData.pick
      if (!pk) continue
      if (pk.type === 'person') return { text: `Talk to ${pk.s.first}`, act: () => this.fpOpen(() => this.game.ui?.openSurvivor?.(pk.s.id)) }
      if (pk.type !== 'station') continue
      const st = pk.st
      const name = STATIONS[st.type]?.name || st.type
      if (st.type === 'watchtower' && st.level > 0 && !st.building) return { text: `Climb the ${name.toLowerCase()} <small>Shift+E to open it</small>`, act: () => (view.input.keys.has('shift') ? this.fpOpen(() => this.fpStation(st)) : this.fpClimb(st)) }
      return { text: `${st.building ? 'Check on' : 'Use'} the ${name.toLowerCase()}`, act: () => this.fpOpen(() => this.fpStation(st)) }
    }
    // nothing under the crosshair: a station close by and roughly ahead
    // (low ones, a bench or a pen, sit under the line of sight)
    {
      const p = this.fpPos()
      const fl = Math.hypot(dir.x, dir.z) || 1
      let best = null
      let bs = Infinity
      for (const st of S.stations) {
        const v = this.stationViews.get(st.id)
        if (!v) continue
        const c = this.fpStationCentre(st)
        const dx = c.x - p.x
        const dz = c.z - p.z
        const d = Math.hypot(dx, dz)
        if (d > c.r + 2.2) continue
        const cos = (dx * dir.x + dz * dir.z) / (d * fl || 1)
        if (cos < 0.8 && d > c.r + 0.3) continue
        const score = d * (2 - cos)
        if (score < bs) {
          bs = score
          best = st
        }
      }
      if (best) {
        const name = STATIONS[best.type]?.name || best.type
        if (best.type === 'watchtower' && best.level > 0 && !best.building) return { text: `Climb the ${name.toLowerCase()} <small>Shift+E to open it</small>`, act: () => (view.input.keys.has('shift') ? this.fpOpen(() => this.fpStation(best)) : this.fpClimb(best)) }
        return { text: `${best.building ? 'Check on' : 'Use'} the ${name.toLowerCase()}`, act: () => this.fpOpen(() => this.fpStation(best)) }
      }
    }
    // the gate, from close by
    const b = bounds()
    const gt = gateTiles(b)
    const p = this.fpPos()
    const gx = gt[1] + 0.5
    const gz = b.z1 + 0.5
    if (Math.hypot(p.x - gx, p.z - gz) < 3.2) {
      const open = this.fence.gateOpen > 0.5
      return { text: open ? 'The gate is open' : 'Open the gate', key: open ? '' : 'E', act: open ? null : () => (this.fence.openGate(10), sfx('click')) }
    }
    return null
  },
  // a station's footprint centre and half-size
  fpStationCentre(st) {
    const [w, d] = stationSize(st)
    return { x: st.x + w / 2, z: st.z + d / 2, r: Math.max(w, d) / 2 }
  },
  fpStation(st) {
    this.multi?.clear?.()
    this.select(st.id)
    this.game.ui?.openStation(st.id)
    sfx('click')
  },
  // panels need the mouse: let it go (click the view to come back)
  fpOpen(fn) {
    this.game.fp.unlock()
    fn()
  },
  fpClimb(st) {
    const v = this.stationViews.get(st.id)
    if (!v) return
    const sp = v.spotWorld(0)
    const B = this.fpBody()
    const p = B.a ? B.a.pos : B.w.pos
    this.fpTower = { st, x: sp.x, z: sp.z, from: { x: p.x, z: p.z } }
    p.x = sp.x
    p.z = sp.z
    if (B.a) {
      B.a.tower = st
      B.a.pos.y = this.fpTowerY()
    }
    sfx('build')
  },
  fpClimbDown() {
    const T = this.fpTower
    const B = this.fpBody()
    const p = B.a ? B.a.pos : B.w.pos
    const n = this.grid.nearestOpen(T.from?.x ?? T.x, T.from?.z ?? T.z + 2, 4)
    if (n) p.set(n.x + 0.5, 0, n.z + 0.5)
    p.y = 0
    if (B.a) B.a.tower = null
    this.fpTower = null
    sfx('build')
  },
  fpKey() {
    return false
  },
}
