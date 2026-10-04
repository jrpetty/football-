// First person on a run (see render/firstperson.js): the player walks as one
// of the squad while the others keep their orders. The top-down aids give
// way to the real place: no cutaway, no fog-of-war veil (you see what your
// eyes see), every floor of a tall building in place, ceilings and roofs on
// every building and interior walls that reach them, darker indoors.
import * as THREE from 'three'
import { view } from '../render/view.js'
import { Builder, seeded } from '../models/kit.js'
import { gableRoof } from '../models/parts.js'
import { HOUSE_ROOFS } from '../models/citykit.js'
import { OUTFITS } from '../models/character.js'
import { fpLook } from '../models/viewmodel.js'
import { slideMove, fpShoot, fpMelee, takedownTarget, takedown } from '../world/fpcombat.js'
import { S } from '../game/state.js'
import { ITEMS, RES } from '../game/data.js'
import { FACE_ROT } from '../world/city.js'
import { INDOOR } from '../render/materials.js'
import { GrassField } from '../render/terrain.js'
import { CullView } from '../render/instcull.js'
import { wallFace } from '../world/finishes.js'
import { magOf, reserveOf, loadRounds } from '../world/mag.js'
import { sfx } from '../core/audio.js'
import { clamp } from '../core/util.js'

const SHADOW_ONLY = new THREE.MeshBasicMaterial({ colorWrite: false, depthWrite: false })
const CEIL = 3.0
const WALL_INT = 2.35
const WALL_EXT = 3.1
const T_WALL = 0.22
const EXT_MAT = { siding: 'siding', brick: 'brick', concrete: 'concrete', corrugated: 'corrugated' }
// painted trim: an off-white, as real paint is (pure white glares in the sun)
const TRIM = '#d9d3c6'
const MK = (key) => ({ mat: key })

// The player's own body in first person: still casts its shadow, draws
// nothing (the camera is inside its head), and its weapon goes too. Third
// person draws it as it is.
export function hideBody(ch, on) {
  const m = ch.mesh
  if (!m) return
  if (on) {
    if (!ch._fpMats) ch._fpMats = m.material
    m.material = Array.isArray(ch._fpMats) ? ch._fpMats.map(() => SHADOW_ONLY) : SHADOW_ONLY
  } else if (ch._fpMats) {
    m.material = ch._fpMats
    ch._fpMats = null
  }
  if (ch.weapon) ch.weapon.visible = !on
}

export const MissionFPMixin = {
  fpWhyNot() {
    if (this.remote) return 'On a friend’s run you watch from above: their game runs the street.'
    if (this.over) return 'The run is over.'
    return 'Nobody on the squad can walk right now.'
  },
  fpCan() {
    return !this.remote && !this.over && this.squad.some((a) => !a.npc && !a.downed && !a.dead && this.canOrder(a))
  },
  fpPick() {
    const ok = (a) => a && !a.npc && !a.downed && !a.dead && this.canOrder(a)
    return [...this.selected].find(ok) || this.squad.find(ok) || null
  },
  fpEnter() {
    if (!this.fpCan()) return false
    const a = this.fpPick()
    if (!a) return false
    if (this.throwing) this.cancelThrow()
    this.closeMenu?.()
    this.fpOn = true
    this.fpBodyOn = false
    this.fpTake(a)
    this.fpTorch = false
    if (this.fowU) this.fowU.uOn.value = 0
    for (const u of this.cutUs) u.n.value = 0
    for (let q = 1; q < this.levelRoot.length; q++) this.levelRoot[q].visible = true
    this.fpBuild()
    for (const g of this.fpGroups) g.visible = true
    this.fpSurroundings(true)
    this.fpGrass(true)
    this.fpIndoor(true)
    this.atmo.fp = true
    // a run's street is short: the haze closes in sooner than in the open
    this.atmo.fpFog = [18, 125]
    document.body.classList.add('fprun')
    return true
  },
  fpTake(a) {
    if (this.fpA && this.fpA !== a) this.fpRelease(this.fpA)
    this.fpA = a
    a.fp = true
    a.fpCrouch = !!this.game.fp?.crouch
    a.order = null
    a.path = null
    a.work = null
    a.target = null
    a.pendingThrow = null
    for (const x of [...this.selected]) if (x !== a) x.select(false)
    this.selected = new Set([a])
    a.select(true)
    a.ring.visible = false
    hideBody(a.ch, !this.fpBodyOn)
    if (a.label) a.label.hidden = true
    this.fpWork = null
    this.renderSquad()
  },
  fpRelease(a) {
    a.fp = false
    this.unbrace(a)
    a.fpCrouch = false
    a.fpReload = false
    a.anchor = { x: a.pos.x, z: a.pos.z }
    a.path = null
    a.work = null
    hideBody(a.ch, false)
    if (a.label) a.label.hidden = false
    a.ring.visible = a.selected
    a.showProg(null)
  },
  fpExit() {
    if (this.fpA) this.fpRelease(this.fpA)
    this.fpA = null
    this.fpOn = false
    this.fpWork = null
    if (this.fowU) this.fowU.uOn.value = 1
    for (const g of this.fpGroups || []) g.visible = false
    this.fpSurroundings(false)
    this.fpGrass(false)
    this.fpIndoor(false)
    this.atmo.fp = false
    document.body.classList.remove('fprun')
    // back to one floor at a time, walls cut away
    if (this.F) this.setView(this.view, true)
    else this.cutU.n.value = this.cutN
  },
  // third person: the body is drawn (behind the eyes, only its shadow)
  fpSetBody(on) {
    this.fpBodyOn = on
    if (this.fpA) hideBody(this.fpA.ch, !on)
  },
  fpAlive() {
    const a = this.fpA
    return !!a && !a.downed && !a.dead && this.squad.includes(a) && !this.over
  },
  // the body went down: carry on as someone else still standing
  fpNext() {
    const was = this.fpA
    const b = this.squad.find((x) => x !== was && !x.npc && !x.downed && !x.dead && this.canOrder(x))
    if (!b) return false
    this.fpTake(b)
    this.toast(`${was?.data.first || 'They'} went down. You're ${b.data.first} now.`, 'bad')
    return true
  },
  fpSwitch(dir) {
    const team = this.squad.filter((x) => !x.npc && !x.downed && !x.dead && this.canOrder(x))
    if (team.length < 2) return
    const i = team.indexOf(this.fpA)
    const b = team[(i + dir + team.length) % team.length]
    this.fpTake(b)
    sfx('select')
  },
  fpFeet() {
    return this.fpA.rpos
  },
  fpEye(out) {
    const a = this.fpA
    return out.set(a.rpos.x, a.rpos.y + 1.6 * (a.data.look?.height || 1), a.rpos.z)
  },
  fpSpeed() {
    return this.fpA.speed
  },
  fpMove(dx, dz) {
    const a = this.fpA
    if (this.paused || a.climb || a.snared > 0) return { x: 0, z: 0 }
    // on the way up or down the stairs (or in the lift) the feet are not ours
    if (a.path) {
      if (Math.hypot(dx, dz) > 0.02 && !a.path.some((w) => w.link)) a.path = null
      else return { x: 0, z: 0 }
    }
    const g = this.grid
    const moved = slideMove((i, j) => g.open(i, j), a.pos, dx, dz, 0.2)
    // pushing into a flight of stairs (or the opening at the top) takes them
    const want = Math.hypot(dx, dz)
    if (this.F && want > 1e-4 && Math.hypot(moved.x, moved.z) < want * 0.4) {
      const tx = a.pos.x + (dx / want) * 0.55
      const tz = a.pos.z + (dz / want) * 0.55
      const t = this.stairTarget(tx, tz)
      if (t && a.moveTo(t.x, t.z)) sfx('move', 300)
    }
    return moved
  },
  fpFace(heading, speed, run, aim) {
    const a = this.fpA
    if (!a.climb && !a.path) a.heading = heading
    a.fpSpeed = speed
    a.fpRun = run
    a.fpAim = !!aim
  },
  fpStep(run) {
    const a = this.fpA
    // running feet carry; walking is near silent
    if (run) this.noise(a.pos.x, a.pos.z, 3.2 * a.st.noiseMult)
  },
  fpAccuracy() {
    const st = this.fpA.st
    const night = this.isNight() ? 1 + (1 - this.nightAcc(this.fpA)) : 1
    return ((1 - st.acc) * 0.075 + 0.004) * night
  },
  fpAttack(origin, dir, o) {
    const a = this.fpA
    if (this.paused || a.climb) return null
    // a swing or a shot drops whatever was in hand
    if (this.fpWork) {
      this.fpWork = null
      a.work = null
    }
    if (a.st.gun && a.hasAmmo()) return fpShoot(a, this, origin, dir, o)
    if (a.st.gun && !a.hasAmmo() && !o.bash) {
      // dry: the first pull clicks, then the gun is a club
      this.fpTipT = 3
      this.fpTipText = `Out of ${RES[a.st.ammoType]?.name.toLowerCase() || 'ammo'}: you swing it instead.`
    }
    return fpMelee(a, this, origin, dir, { ...o, delay: clamp(a.st.rate * 0.85, 0.32, 0.9) * 0.42 })
  },
  // what a bullet stops at (drawn space)
  fpSolid(x, y, z) {
    if (y < 0.02) return true
    const lv = this.lv
    let k = 0
    if (this.F && this.inFoot(x, z, 0)) k = clamp(Math.floor((y + 0.3) / lv.FH), 0, lv.levels - 1)
    const ly = y - k * (lv.FH || 0)
    if (k && ly < 0.05) return true
    // the ceilings and roofs
    if (this.fpRoofAt(x, z, y)) return true
    const s = this.toSim(x, z, k)
    const g = lv.grid
    const i = Math.floor(s.x - lv.x0)
    const j = Math.floor(s.z - lv.z0)
    if (!g.inb(i, j)) return false
    const idx = g.i(i, j)
    if (g.cost[idx] !== 255) return false
    const own = g.owner[idx]
    if (own === 'wall') return ly < CEIL
    if (own === 'edge' || own === 'void' || own === 'stairhole' || own === 'fence') return false
    if (own === 'stairs') return ly < 0.5
    if (own === 'shaft') return true
    if (own === 'neighbour' || own === 'truck') return ly < 5
    if (own === 'van') return ly < 1.7
    if (own === 'hesco') return ly < 1.4
    if (own === 'door') return ly < 2.12
    if (own && typeof own === 'object') return own.box ? ly < own.box.max.y - k * (lv.FH || 0) : ly < 1
    return ly < 1
  },
  fpRoofAt(x, z, y) {
    for (const r of this.fpRoofs || []) if (x > r.x0 && x < r.x1 && z > r.z0 && z < r.z1 && y > r.y0 && y < r.y1) return true
    return false
  },
  fpStatus() {
    const a = this.fpA
    const st = a.st
    const it = st.weaponItem
    const name = ITEMS[st.weaponId]?.name || 'Fists'
    // rounds in the gun / what it holds, then the camp's stock to load from
    let ammo = ''
    const m = magOf(a)
    const res = st.ammoType ? Math.floor(this.ammoLeft(st.ammoType)) : null
    if (st.gun) ammo = `<b>${m.n}</b><i>/${m.cap}</i> ${res == null ? '∞ bolts' : `${res} ${RES[st.ammoType]?.name.toLowerCase() || ''}`}`
    if (it && ITEMS[st.weaponId]?.dur) ammo += `<small>${Math.round((it.cond / ITEMS[st.weaponId].dur) * 100)}% condition</small>`
    if (!this.fpLookCache || this.fpLookCache.id !== a.data.id) this.fpLookCache = { id: a.data.id, look: fpLook(a.data, OUTFITS[a.data.occ] || OUTFITS.drifter) }
    const W = this.fpWork
    this.fpTipT = (this.fpTipT || 0) - 0.016
    let tip = this.fpTipT > 0 ? this.fpTipText : ''
    if (!tip && a.climb) tip = a.climb.kind === 'lift' ? 'Riding the lift…' : ''
    if (!tip && st.gun && m.n <= 0) tip = res === 0 ? `Out of ${RES[st.ammoType]?.name.toLowerCase() || 'ammo'}` : 'Empty: R to reload'
    return {
      name: `${a.data.first} · ${Math.ceil(Math.max(0, a.hp))} hp${this.fpFollow ? ' · squad following' : ''}`,
      hp: a.hp,
      maxHp: a.maxHp,
      weapon: name,
      ammo,
      weaponId: st.weaponId,
      mods: it?.mods || [],
      look: this.fpLookCache.look,
      lookKey: a.data.id,
      prog: W ? W.t / W.total : null,
      progLabel: W ? W.label : '',
      tip,
      torch: this.fpTorchOn(),
      mag: st.gun ? m.n : null,
      magCap: m.cap,
    }
  },
  // the gun in hand's magazine, for reloading in first person
  fpMag() {
    const a = this.fpA
    if (!a?.st.gun || !a.st.magCap) return null
    const m = magOf(a)
    return { n: m.n, cap: m.cap, reserve: reserveOf(a, this), perShell: a.st.perShell, reload: a.st.reload, id: a.st.weaponId }
  },
  fpLoad(n) {
    return this.fpA ? loadRounds(this.fpA, this, n) : 0
  },
  fpReloading(on) {
    if (this.fpA) this.fpA.fpReload = on
  },
  fpCrouch(on) {
    if (this.fpA) this.fpA.fpCrouch = on
  },
  // a silent kill from behind (world/fpcombat.js says who can be taken)
  fpTakedown(z) {
    const a = this.fpA
    if (!a || z.dead) return null
    this.fpWork = null
    a.work = null
    takedown(a, this, z, () => {
      S.stats.takedowns = (S.stats.takedowns || 0) + 1
    })
    return { takedown: true }
  },
  fpTorchOn() {
    return this.fpTorch || this.isNight()
  },
  fpShade() {
    const a = this.fpA
    if (!a) return 1
    const p = a.rpos
    for (const r of this.fpIn || []) if (p.x > r[0] && p.x < r[2] && p.z > r[1] && p.z < r[3] && p.y < r[4]) return 0.32
    return 1
  },
  // What is under the crosshair and within reach: a fitting to search,
  // a downed friend, the lift.
  fpLook(eye, dir) {
    const a = this.fpA
    if (!a || a.climb) return null
    if (this.fpWork) return { text: `${this.fpWork.label}… <small>move to stop</small>`, key: '' }
    // a zombie with its back to you, close enough to take quietly
    const tz = takedownTarget(a, this, dir)
    if (tz) return { text: `Silent takedown <small>${tz.def.name.toLowerCase()}</small>`, act: () => this.fpTakedown(tz) }
    const ray = new THREE.Ray(eye, dir)
    const hitP = new THREE.Vector3()
    // a downed friend first
    for (const o of this.squad) {
      if (o === a || !o.downed || o.dead) continue
      if (o.rpos.distanceTo(a.rpos) < 1.8) return { text: `Help ${o.data.first} up`, act: () => this.fpStartWork({ kind: 'revive', a: o, total: 3.5 * a.st.revive, label: `Helping ${o.data.first} up` }) }
    }
    // the lift car
    if (this.F) {
      const g = this.lv.grid
      const [i, j] = this.tile(a.pos.x, a.pos.z)
      if (g.owner[g.i(i, j)] === 'lift') {
        const links = (g.links?.get(g.i(i, j)) || []).filter((L) => L.kind === 'lift')
        if (links.length) {
          if (links.every((L) => L.ref.off)) return { text: 'The lift has no power', key: '' }
          const k = this.levelOf(a.pos.x)
          const up = k < this.lv.levels - 1
          const want = up ? k + 1 : 0
          const L = links.find((x) => !x.ref.off && g.floorOf(x.to % g.w) === want) || links.find((x) => !x.ref.off)
          if (L) {
            const ti = L.to % g.w
            const tj = Math.floor(L.to / g.w)
            const c = this.center(ti, tj)
            return { text: `Take the lift ${up ? 'up' : 'down'} to ${this.floorName(g.floorOf(ti)).toLowerCase()}`, act: () => a.moveTo(c.x, c.z) }
          }
        }
      }
    }
    // a door: shut it, open it, or lean on it while they push
    if (a.bracing) {
      const D = a.bracing
      return { text: `Bracing the door <small>${Math.round((D.hp / D.max) * 100)}% · E or move to let go</small>`, act: () => this.unbrace(a) }
    }
    let door = null
    let dd = 2.2
    for (const D of this.doorList || []) {
      if (D.broken || (this.F && !this.onView(D.x, D.z))) continue
      // standing in the doorway, it's not the door you're looking at
      if (Math.abs(a.pos.x - D.x) < 0.5 && Math.abs(a.pos.z - D.z) < 0.5) continue
      if (!ray.intersectBox(D.box, hitP)) continue
      const d = hitP.distanceTo(eye)
      if (d < dd) {
        dd = d
        door = D
      }
    }
    if (door && this.containers.some((c) => !c.gone && c.box && (!this.F || this.onView(c.x, c.z)) && ray.intersectBox(c.box, hitP) && hitP.distanceTo(eye) < dd)) door = null
    if (door) {
      const D = door
      const tip = (t) => {
        this.fpTipT = 2.5
        this.fpTipText = t
      }
      if (D.shut && this.time - (D.hitT ?? -99) < 4) return { text: `Brace the door <small>${Math.round((D.hp / D.max) * 100)}% · they’re pushing</small>`, act: () => this.braceDoor(D, a) }
      if (D.shut) return { text: 'Open the door', act: () => this.toggleDoor(D, a) || tip('It won’t budge') }
      return { text: 'Shut the door', act: () => this.toggleDoor(D, a) || tip('Something’s in the doorway') }
    }
    let best = null
    let bd = 2.4
    for (const c of this.containers) {
      if (c.gone || !c.box) continue
      if (this.F && !this.onView(c.x, c.z)) continue
      if (!ray.intersectBox(c.box, hitP)) continue
      const d = hitP.distanceTo(eye)
      if (d < bd) {
        bd = d
        best = c
      }
    }
    if (!best) return null
    const c = best
    if (!this.grid.los(a.pos.x, a.pos.z, c.x, c.z) && Math.hypot(a.pos.x - c.x, a.pos.z - c.z) > 1.6) return null
    const nm = c.def?.name || c.kind
    if (c.def?.isTrap) return c.armed ? { text: `Disarm the ${nm.toLowerCase()}`, act: () => this.fpStartWork({ kind: 'search', c, label: 'Disarming' }) } : null
    if (c.def?.power) return this.powered ? { text: `${nm}: running`, key: '' } : { text: `Start the ${nm.toLowerCase()}`, act: () => this.fpStartWork({ kind: 'search', c, label: 'Starting it up' }) }
    if (c.drive) return { text: c.drive.keys ? `Start the ${nm.toLowerCase()} (keys in it)` : `Hotwire the ${nm.toLowerCase()}`, act: () => this.fpStartWork({ kind: 'hotwire', c, label: 'Hotwiring' }) }
    if (c.stash) return { text: 'Take what is left', act: () => this.fpStartWork({ kind: 'search', c, label: 'Taking it' }) }
    if (!c.searched) {
      if (c.needsKey && !this.workTime(a, c, 'search')) return { text: `${nm}: locked, needs a key`, key: '' }
      if (c.locked && !a.st.picklock) return { text: `Smash open the ${nm.toLowerCase()} <small>loud</small>`, act: () => ((c.smash = true), this.fpStartWork({ kind: 'search', c, label: 'Smashing it open' })) }
      return { text: `Search the ${nm.toLowerCase()}${c.locked ? ' <small>pick the lock</small>' : ''}`, act: () => ((c.smash = false), this.fpStartWork({ kind: 'search', c, label: 'Searching' })) }
    }
    if (c.def?.parts === false) return null
    return { text: `Break down the ${nm.toLowerCase()} for parts <small>noisy</small>`, act: () => this.fpStartWork({ kind: 'dismantle', c, label: 'Breaking it down' }) }
  },
  fpStartWork(w) {
    const a = this.fpA
    if (w.kind === 'revive') {
      this.fpWork = { ...w, t: 0, at: { x: a.pos.x, z: a.pos.z } }
      return
    }
    const total = this.workTime(a, w.c, w.kind)
    if (total == null) {
      sfx('error')
      return this.toast(w.c.locked ? 'Locked, and nobody here can pick it.' : 'That can’t be done now.')
    }
    this.fpWork = { ...w, t: 0, total, at: { x: a.pos.x, z: a.pos.z } }
    sfx('click')
  },
  // the work in hand goes on while the body stays put
  fpTickWork(dt) {
    const W = this.fpWork
    const a = this.fpA
    if (!W || !a) return
    if (a.downed || Math.hypot(a.pos.x - W.at.x, a.pos.z - W.at.z) > 0.35) {
      this.fpWork = null
      a.work = null
      return
    }
    if (W.kind === 'revive') {
      if (!W.a.downed || W.a.dead) {
        this.fpWork = null
        return
      }
      W.t += dt
      if (W.t >= W.total) {
        W.a.revive()
        this.fpWork = null
        view.labels.float(this.scene, W.a.chestPos(2), 'Back on their feet', 'good')
        sfx('levelup')
      }
      return
    }
    const c = W.c
    if (c.gone || (W.kind === 'search' && c.searched && !c.stash && !c.def?.isTrap)) {
      this.fpWork = null
      return
    }
    W.t += dt
    // one record for the whole job: it keeps its own noise and sound timers
    if (!W.wk) W.wk = { kind: W.kind, t: 0, total: W.total, c }
    W.wk.t = W.t
    a.work = W.wk
    this.workTick(a, W.wk, dt)
    if (W.t >= W.total) {
      this.finishWork(a, c, W.kind)
      this.fpWork = null
      a.work = null
    }
  },
  fpKey(e, fp) {
    const k = e.key.toLowerCase()
    const n = parseInt(e.key, 10)
    if (n >= 1 && n <= 9) {
      const team = this.squad.filter((x) => !x.npc && this.canOrder(x))
      const b = team[n - 1]
      if (b && !b.downed && b !== this.fpA) {
        this.fpTake(b)
        sfx('select')
      }
      return true
    }
    if (k === 'tab') {
      e.preventDefault?.()
      this.fpSwitch(e.shiftKey ? -1 : 1)
      return true
    }
    if (k === 'f' && !e.ctrlKey) {
      this.fpTorch = !this.fpTorch
      sfx('click')
      return true
    }
    if (k === 'g' && !e.ctrlKey) {
      this.fpFollow = !this.fpFollow
      this.fpFollowT = 0
      this.toast(this.fpFollow ? 'The squad follows you. G again and they hold where they are.' : 'The squad holds here.', this.fpFollow ? 'good' : '')
      sfx('click')
      return true
    }
    const U = { z: 'molotov', x: 'pipebomb', c: 'noisemaker', v: 'medkit' }
    if (U[k]) {
      this.fpUtil(U[k], fp)
      return true
    }
    if (k === 'pageup' || k === 'pagedown' || k === '[' || k === ']') return true
    return false
  },
  // the buttons a touch screen shows for those keys
  fpTouchKeys() {
    const team = this.squad.filter((x) => !x.npc && this.canOrder(x) && !x.downed)
    const out = []
    if (team.length > 1) out.push({ key: 'Tab', icon: 'next', label: 'Next' })
    out.push({ key: 'f', icon: 'sun', label: 'Torch', on: !!this.fpTorch })
    if (team.length > 1) out.push({ key: 'g', icon: 'people', label: 'Follow', on: !!this.fpFollow })
    for (const [key, kind, label] of [['z', 'molotov', 'Molotov'], ['x', 'pipebomb', 'Bomb'], ['c', 'noisemaker', 'Noise'], ['v', 'medkit', 'Medkit']]) {
      const n = this.utils[kind] || 0
      if (n > 0) out.push({ key, icon: kind, label, n })
    }
    return out
  },
  fpUtil(kind, fp) {
    const a = this.fpA
    if (this.utils[kind] <= 0) {
      sfx('error')
      return this.toast(`No ${RES[kind].name.toLowerCase()} left`)
    }
    if (kind === 'medkit') {
      if (a.hp >= a.maxHp) return this.toast('You don’t need patching up')
      this.utils.medkit--
      a.hp = Math.min(a.maxHp, a.hp + a.maxHp * 0.5)
      sfx('levelup')
      fp.throwAnim()
      return this.renderUtils()
    }
    // thrown where the crosshair meets the floor, up to 16 m away
    const cam = view.camera
    const dir = fp.forward(new THREE.Vector3())
    const fy = a.rpos.y + 0.05
    let t = dir.y < -0.02 ? (fy - cam.position.y) / dir.y : 14
    t = clamp(t, 1.5, 16)
    const px = cam.position.x + dir.x * t
    const pz = cam.position.z + dir.z * t
    const k = this.F && this.inFoot(px, pz, 0) ? this.levelOf(a.pos.x) : 0
    const s = this.toSim(px, pz, k)
    this.throwItem(a, kind, s.x, s.z)
    fp.throwAnim()
    sfx('throw')
  },

  // ---------------------------------------------------------------- ceilings
  // Built once, on the first switch to first person: a ceiling and a roof
  // over every storey that has nothing above it, and the interior walls
  // carried up to the ceiling. One group per floor, in that floor's frame.
  fpBuild() {
    if (this.fpGroups) return
    const lv = this.lv
    const L = this.def
    // the facade as the street build has it (same seed, same first draw)
    const r = seeded(lv.loc.lot.seed + 5)
    const ext = EXT_MAT[L.ext] || 'plaster'
    const extColor = L.extColor ? L.extColor[Math.floor(r() * L.extColor.length)] : ext === 'brick' ? '#ffffff' : L.wall
    const house = this.loc.type === 'house' && !this.F
    const rr = seeded(lv.loc.lot.seed + 11)
    this.fpGroups = []
    this.fpRoofs = []
    this.fpIn = []
    const levels = this.F ? lv.levels : 1
    const FH = lv.FH || 3.3
    for (let k = 0; k < levels; k++) {
      const b = new Builder()
      const W = lv.W
      const c0i = k === 0 ? 0 : lv.W0 + (k - 1) * lv.regionW
      const c1i = k === 0 ? lv.W0 : c0i + lv.regionW
      const inLvl = (i) => i >= c0i && i < c1i
      const Bk = k ? lv.levelBlds[k] : null
      const roofLevel = !!Bk?.roof
      if (roofLevel) {
        this.fpGroups.push(this.fpAdd(b, k))
        continue
      }
      const blds = k ? [Bk] : lv.buildings
      const isW = (i, j) => i >= 0 && j >= 0 && i < W && j < lv.H && lv.walls[j * W + i] === 1
      const isExt = (i, j) => blds.some((B) => (i === B.i0 || i === B.i1) && j >= B.j0 && j <= B.j1) || blds.some((B) => (j === B.j0 || j === B.j1) && i >= B.i0 && i <= B.i1)
      const doorAt = new Map(lv.doors.map((d) => [d.i + ',' + d.j, d]))
      const roomCol = (i, j) => {
        const q = i >= 0 && j >= 0 && i < W && j < lv.H ? lv.roomAt[j * W + i] : -1
        return q >= 0 ? lv.rooms[q].def.wall : null
      }
      const roomDef = (i, j) => {
        const q = i >= 0 && j >= 0 && i < W && j < lv.H ? lv.roomAt[j * W + i] : -1
        return q >= 0 ? lv.rooms[q].def : null
      }
      // the main tall building's lower floors have the next slab overhead
      const covered = (B) => this.F && k < levels - 1 && (k > 0 || (B.i0 === lv.bld?.i0 && B.j0 === lv.bld?.j0))
      const top = (B) => (covered(B) ? FH - 0.22 : CEIL)
      for (const B of blds) {
        const p0 = this.center(B.i0, B.j0)
        const p1 = this.center(B.i1, B.j1)
        const dx0 = k ? lv.off[k] : 0
        this.fpIn.push([p0.x - dx0, p0.z, p1.x - dx0, p1.z, k * FH + top(B) + 0.2])
        if (covered(B)) continue
        const cx = (p0.x + p1.x) / 2
        const cz = (p0.z + p1.z) / 2
        // a plaster ceiling inside
        b.box(p1.x - p0.x, 0.04, p1.z - p0.z, { mat: 'plaster', color: '#d8d2c6', x: cx, y: CEIL + 0.02, z: cz, ao: 0 })
        let roofTop = 3.4
        if (house && !k) roofTop = this.fpHouseRoof(b, cx, cz, p1.x - p0.x + T_WALL, p1.z - p0.z + T_WALL, extColor, rr)
        else this.fpFlatRoof(b, p0, p1, ext, extColor)
        this.fpRoofs.push({ x0: p0.x - dx0 - 0.3, z0: p0.z - 0.3, x1: p1.x - dx0 + 0.3, z1: p1.z + 0.3, y0: k * FH + CEIL, y1: k * FH + roofTop })
        // a light fitting in the middle of each room
        for (const room of lv.rooms) {
          if (!inLvl(room.i0) || room.type === 'roof' || room.type === 'stairs') continue
          if (room.i0 < B.i0 || room.i1 > B.i1 || room.j0 < B.j0 || room.j1 > B.j1) continue
          const r0 = this.center(room.i0, room.j0)
          const r1 = this.center(room.i1, room.j1)
          b.cyl(0.13, 0.15, 0.05, { mat: 'plain', color: '#a8a49a', x: (r0.x + r1.x) / 2, y: CEIL - 0.025, z: (r0.z + r1.z) / 2, seg: 16, ao: 0 })
          b.sphere(0.1, { mat: 'glass', color: '#e8e4d8', x: (r0.x + r1.x) / 2, y: CEIL - 0.05, z: (r0.z + r1.z) / 2, ws: 12, hs: 6, tl: Math.PI / 2, rx: Math.PI, ao: 0 })
        }
      }
      // interior walls up to the ceiling (they stop at 2.35 m so the
      // top-down camera can see over them)
      const wallTop = (i, j) => {
        for (const B of blds) if (i >= B.i0 && i <= B.i1 && j >= B.j0 && j <= B.j1) return top(B)
        return CEIL
      }
      for (let j = 0; j < lv.H; j++) {
        for (let i = c0i; i < c1i; i++) {
          if (!isW(i, j) || isExt(i, j)) continue
          const c = this.center(i, j)
          const T = wallTop(i, j)
          const hgt = T - (0.05 + WALL_INT)
          if (hgt <= 0.02) continue
          const y = 0.05 + WALL_INT + hgt / 2
          const core = { mat: 'plaster', color: '#c8c0b0', ao: 0 }
          b.box(T_WALL, hgt, T_WALL, { ...core, x: c.x, y, z: c.z })
          for (const [di, dj] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
            if (!(isW(i + di, j + dj) || doorAt.has(i + di + ',' + (j + dj)))) continue
            const alongX = di !== 0
            const ax = c.x + di * 0.25
            const az = c.z + dj * 0.25
            b.box(alongX ? 0.5 : T_WALL, hgt, alongX ? T_WALL : 0.5, { ...core, x: ax, y, z: az })
            for (const s of [-1, 1]) {
              const col = roomCol(alongX ? i : i + s, alongX ? j + s : j)
              if (!col) continue
              wallFace(b, MK, roomDef(alongX ? i : i + s, alongX ? j + s : j), col, alongX, s, 0.5, alongX ? ax : c.x + s * (T_WALL / 2 + 0.006), alongX ? c.z + s * (T_WALL / 2 + 0.006) : az, y - hgt / 2, y + hgt / 2, 0)
            }
          }
        }
      }
      // over interior doorways too
      for (const d of lv.doors) {
        if (d.ext || d.passage || !inLvl(d.i)) continue
        const c = this.center(d.i, d.j)
        const T = wallTop(d.i, d.j)
        const hgt = T - (0.05 + WALL_INT)
        if (hgt <= 0.02) continue
        b.box(d.horiz ? 1.0 : T_WALL, hgt, d.horiz ? T_WALL : 1.0, { mat: 'plaster', color: '#c8c0b0', x: c.x, y: 0.05 + WALL_INT + hgt / 2, z: c.z, ao: 0 })
      }
      this.fpTrim(b, k, { c0i, c1i, isW, isExt, doorAt, roomCol, roomDef, wallTop, blds, ext, extColor, house: house && !k })
      this.fpGroups.push(this.fpAdd(b, k))
    }
  },
  // A house's pitched roof: shingles over gable ends in the house's own
  // siding, fascia and soffits, gutters with downpipes, a chimney. The ridge
  // runs the long way. Returns the height of the ridge.
  fpHouseRoof(b, cx, cz, w, d, extColor, r) {
    const along = w >= d
    const len = along ? w : d
    const span = along ? d : w
    const rise = Math.min(3.2, span * 0.24)
    const over = 0.45
    const y0 = WALL_EXT + 0.05
    const col = HOUSE_ROOFS[Math.floor(r() * HOUSE_ROOFS.length)]
    const ry = along ? 0 : Math.PI / 2
    gableRoof(b, { w: len, d: span, y: y0, rise, mat: 'shingles', color: col, gableMat: 'siding', gableColor: extColor, fascia: TRIM, over, x: cx, z: cz, ry })
    const run = span / 2 + over
    const eave = y0 + rise - rise * (run / (span / 2))
    b.at({ x: cx, z: cz, ry }, () => {
      for (const s of [-1, 1]) {
        // soffit from the wall out to the fascia
        b.box(len + over * 2, 0.03, over, { mat: 'paint', color: TRIM, y: eave + 0.05, z: s * (span / 2 + over / 2), ao: 0 })
        // the gutter along the eave
        b.box(len + over * 2, 0.11, 0.12, { mat: 'paint', color: '#d8d6ce', y: eave - 0.02, z: s * (run + 0.07) })
        // a downpipe at each end of it
        for (const e of [-1, 1]) {
          const x = e * (len / 2 - 0.12)
          b.cyl(0.035, 0.035, eave - 0.1, { mat: 'paint', color: '#d8d6ce', x, y: (eave - 0.1) / 2 + 0.05, z: s * (span / 2 + 0.09), seg: 8 })
          b.box(0.07, 0.07, over + 0.1, { mat: 'paint', color: '#d8d6ce', x, y: eave - 0.06, z: s * (span / 2 + over / 2 + 0.06) })
          b.box(0.09, 0.06, 0.2, { mat: 'paint', color: '#d8d6ce', x, y: 0.12, z: s * (span / 2 + 0.16), rx: s * 0.4 })
        }
        // corner boards on the walls below
        for (const e of [-1, 1]) b.box(0.13, WALL_EXT, 0.13, { mat: 'paint', color: TRIM, x: e * (len / 2 - 0.02), y: 0.05 + WALL_EXT / 2, z: s * (span / 2 - 0.02) })
      }
      // a brick chimney through the back slope, near one end
      const chx = (r() < 0.5 ? -1 : 1) * len * 0.28
      const chz = -span * 0.18
      const top = y0 + rise + 0.95
      const base = y0 + rise * 0.25
      b.box(0.72, top - base, 0.62, { mat: 'brick', color: '#c8a090', x: chx, y: (top + base) / 2, z: chz })
      b.box(0.84, 0.1, 0.74, { mat: 'concrete', color: '#9a968e', x: chx, y: top + 0.05, z: chz })
      b.cyl(0.09, 0.09, 0.3, { mat: 'rust', color: '#8a6a50', x: chx + 0.12, y: top + 0.25, z: chz, seg: 10 })
    })
    return y0 + rise + 0.4
  },
  // A flat roof behind a parapet in the building's own facade, capped with
  // concrete coping.
  fpFlatRoof(b, p0, p1, ext, extColor) {
    const cx = (p0.x + p1.x) / 2
    const cz = (p0.z + p1.z) / 2
    const w = p1.x - p0.x
    const d = p1.z - p0.z
    const y0 = WALL_EXT + 0.05
    const ph = 0.72
    b.box(w, 0.14, d, { mat: 'roofTar', color: '#6e6a64', x: cx, y: y0 + 0.07, z: cz, ao: 0 })
    for (const s of [-1, 1]) {
      b.box(w + T_WALL, ph, T_WALL, { mat: ext, color: extColor, x: cx, y: y0 + ph / 2, z: cz + (s * d) / 2 })
      b.box(T_WALL, ph, d, { mat: ext, color: extColor, x: cx + (s * w) / 2, y: y0 + ph / 2, z: cz })
      b.box(w + T_WALL + 0.1, 0.08, T_WALL + 0.1, { mat: 'concrete', color: '#b0aca2', x: cx, y: y0 + ph + 0.04, z: cz + (s * d) / 2 })
      b.box(T_WALL + 0.1, 0.08, d + T_WALL + 0.1, { mat: 'concrete', color: '#b0aca2', x: cx + (s * w) / 2, y: y0 + ph + 0.04, z: cz })
    }
  },
  // Trim that the view from above never needed: a picture rail where the
  // interior walls meet their extension (over the dark cap that marks wall
  // tops from above), crown moulding at the ceiling, a foundation band and
  // window casings outside.
  fpTrim(b, k, o) {
    const { c0i, c1i, isW, isExt, doorAt, roomCol, roomDef, wallTop } = o
    const lv = this.lv
    const winAt = new Map(lv.windows.map((w) => [w.i + ',' + w.j, w]))
    const rail = (alongX, x, z, y, h, depth) => b.box(alongX ? 0.5 : depth, h, alongX ? depth : 0.5, { mat: 'paint', color: TRIM, x, y, z, ao: 0 })
    for (let j = 0; j < lv.H; j++) {
      for (let i = c0i; i < c1i; i++) {
        const wall = isW(i, j)
        const door = doorAt.get(i + ',' + j)
        if (!wall && !(door && !door.passage)) continue
        const c = this.center(i, j)
        const ex = isExt(i, j) || !!door?.ext
        const T = ex ? CEIL : wallTop(i, j)
        const winHere = wall && winAt.has(i + ',' + j)
        const arms = door ? (door.horiz ? [[1, 0], [-1, 0]] : [[0, 1], [0, -1]]) : [[1, 0], [-1, 0], [0, 1], [0, -1]].filter(([di, dj]) => isW(i + di, j + dj) || doorAt.has(i + di + ',' + (j + dj)))
        for (const [di, dj] of arms) {
          const alongX = di !== 0
          const ax = c.x + di * 0.25
          const az = c.z + dj * 0.25
          for (const s of [-1, 1]) {
            const si = alongX ? i : i + s
            const sj = alongX ? j + s : j
            let col = roomCol(si, sj)
            if (!col && !door && !winHere && isW(si, sj)) {
              // a corner: the room is round it, diagonally. The street build
              // looked only beside the wall and left this half unpainted
              const di2 = alongX ? i + di : i + s
              const dj2 = alongX ? j + s : j + dj
              col = roomCol(di2, dj2)
              if (col) {
                const po = T_WALL / 2 + 0.006
                wallFace(b, MK, roomDef(di2, dj2), col, alongX, s, 0.5, alongX ? ax : c.x + s * po, alongX ? c.z + s * po : az, 0.05, T, 0)
                const ko = T_WALL / 2 + 0.015
                b.box(alongX ? 0.5 : 0.03, 0.12, alongX ? 0.03 : 0.5, { mat: 'paint', color: '#5a4a3e', x: alongX ? ax : c.x + s * ko, y: 0.12, z: alongX ? c.z + s * ko : az, ao: 0 })
              }
            }
            const off = T_WALL / 2 + 0.016
            const fx = alongX ? ax : c.x + s * off
            const fz = alongX ? c.z + s * off : az
            if (col) {
              // picture rail and crown moulding, room side
              rail(alongX, fx, fz, 0.05 + WALL_INT + 0.025, 0.075, 0.034)
              rail(alongX, fx, fz, T - 0.045, 0.09, 0.05)
            } else if (ex && !k && !door) {
              // outside: a concrete foundation band, proud of the wall
              const bo = T_WALL / 2 + 0.03
              b.box(alongX ? 0.5 : 0.06, 0.36, alongX ? 0.06 : 0.5, { mat: 'concrete', color: '#8e8a82', x: alongX ? ax : c.x + s * bo, y: 0.05 + 0.18, z: alongX ? c.z + s * bo : az, ao: 0 })
              if (winAt.has(i + ',' + j)) {
                // window casing: a head over the glass, the sill is the street build's
                const wo = T_WALL / 2 + 0.025
                b.box(alongX ? 0.5 : 0.05, 0.1, alongX ? 0.05 : 0.5, { mat: 'paint', color: TRIM, x: alongX ? ax : c.x + s * wo, y: 0.05 + 2.2, z: alongX ? c.z + s * wo : az, ao: 0 })
              }
            }
          }
        }
        // the room side of window walls and over outside doors is the
        // facade's material in the street build: paint it like the room
        const win = wall && winAt.has(i + ',' + j)
        if (win || door?.ext) {
          const alongX = door ? door.horiz : isW(i + 1, j) || isW(i - 1, j) || doorAt.has(i + 1 + ',' + j) || doorAt.has(i - 1 + ',' + j)
          for (const s of [-1, 1]) {
            const col = roomCol(alongX ? i : i + s, alongX ? j + s : j)
            if (!col) continue
            const def = roomDef(alongX ? i : i + s, alongX ? j + s : j)
            const off = T_WALL / 2 + 0.007
            const fx = alongX ? c.x : c.x + s * off
            const fz = alongX ? c.z + s * off : c.z
            const face = (y0, y1, w = 1.0) => wallFace(b, MK, def, col, alongX, s, w, fx, fz, y0, y1, 0)
            if (door) face(0.05 + (door.wide >= 3 ? 2.6 : 2.15), T)
            else {
              face(0.05, 0.05 + 0.9)
              face(0.05 + 2.15, T)
              // the post between the panes, and a sill board inside
              face(0.05 + 0.9, 0.05 + 2.15, T_WALL)
              const so = T_WALL / 2 + 0.035
              b.box(alongX ? 1.0 : 0.09, 0.035, alongX ? 0.09 : 1.0, { mat: 'paint', color: TRIM, x: alongX ? c.x : c.x + s * so, y: 0.05 + 0.93, z: alongX ? c.z + s * so : c.z, ao: 0 })
            }
          }
        }
        // window casings' sides, where a run of windows starts and ends
        if (winAt.has(i + ',' + j) && ex && !k) {
          for (const [di, dj] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
            const alongX = isW(i + 1, j) || isW(i - 1, j) || winAt.has(i + 1 + ',' + j) || winAt.has(i - 1 + ',' + j)
            if (alongX !== (di !== 0)) continue
            if (winAt.has(i + di + ',' + (j + dj))) continue
            for (const s of [-1, 1]) {
              if (roomCol(alongX ? i : i + s, alongX ? j + s : j)) continue
              const wo = T_WALL / 2 + 0.025
              const px = alongX ? c.x + di * 0.47 : c.x + s * wo
              const pz = alongX ? c.z + s * wo : c.z + dj * 0.47
              b.box(alongX ? 0.07 : 0.05, 1.32, alongX ? 0.05 : 0.07, { mat: 'paint', color: TRIM, x: px, y: 0.05 + 1.56, z: pz, ao: 0 })
            }
          }
        }
      }
    }
  },
  // From the street you can see past the lot: the ground runs on flat (the
  // top-down view's framing hills go), a second ring of the real
  // neighbourhood stands round it, and the city's skyline (its actual
  // towers and blocks, as silhouettes in the haze) closes the horizon.
  fpSurroundings(on) {
    const T = this.terrain
    const pos = T.mesh.geometry.attributes.position
    if (on) {
      if (!T.fpY) {
        T.fpY = new Float32Array(pos.count)
        for (let i = 0; i < pos.count; i++) T.fpY[i] = pos.getY(i)
      }
      for (let i = 0; i < pos.count; i++) pos.setY(i, Math.min(T.fpY[i], 0))
    } else if (T.fpY) for (let i = 0; i < pos.count; i++) pos.setY(i, T.fpY[i])
    pos.needsUpdate = true
    T.mesh.geometry.computeVertexNormals()
    if (on && !this.fpFar) this.fpBuildFar()
    if (this.fpFar) this.fpFar.visible = on
  },
  // Grass on the lots' lawns in first person (from above, the painted
  // ground reads as grass; up close it wants blades): tufts where the ground
  // is grassy, off the paving, the street, the buildings and anything solid.
  fpGrass(on) {
    if (!on) {
      this.fpGrassF?.setVisible(false)
      return
    }
    if (!this.fpGrassF) {
      const lv = this.lv
      const g = this.grid
      const blds = lv.buildings.map((B) => {
        const a = this.center(B.i0, B.j0)
        const c = this.center(B.i1, B.j1)
        return [a.x - 0.6, a.z - 0.6, c.x + 0.6, c.z + 0.6]
      })
      const avoid = (x, z) => {
        if (z > lv.zFront - 0.2) return true
        if (!g.open(Math.floor(x), Math.floor(z))) return true
        for (const p of lv.pave) if (x > p.x0 - 0.15 && x < p.x1 + 0.15 && z > p.z0 - 0.15 && z < p.z1 + 0.15) return true
        for (const b of blds) if (x > b[0] && x < b[2] && z > b[1] && z < b[3]) return true
        return false
      }
      this.fpGrassF = new GrassField(this.scene, this.splat, { count: 0, seed: 53 + (lv.loc.lot.seed % 997), heightFn: () => 0, avoid, cap: 12000, hScale: 0.58 })
      this.fpGrassF.area = { x0: lv.x0 + 1, z0: lv.z0 + 1, x1: lv.x0 + lv.W0 - 1, z1: lv.zFront }
      this.fpGrassF.rebuild()
      this.fpCull = new CullView()
      this.fpCull.wide = 26
    }
    this.fpCull.cam.set(1e9, 0, 0)
    this.fpGrassF.setVisible(true)
  },
  fpBuildFar() {
    const lv = this.lv
    const g = (this.fpFar = new THREE.Group())
    this.scene.add(g)
    // the ground: pavement out to the haze
    const gb = new Builder()
    gb.box(900, 0.04, 900, { mat: 'asphalt', color: '#77756f', x: lv.x0 + lv.W0 / 2, y: -0.06, z: lv.z0 + lv.H / 2, ao: 0, shadow: false })
    const ground = gb.build()
    g.add(ground)
    // the next ring of lots
    const inner = this.nReach || 60
    this.buildNeighbours(inner, inner + 55, g, true)
    // the skyline
    const city = this.game.city
    const me = lv.loc.lot
    const r0 = FACE_ROT[me.face]
    const c = Math.cos(r0)
    const s = Math.sin(r0)
    const M = (this.fpSkyMat = new THREE.MeshBasicMaterial({ vertexColors: true, fog: false }))
    const b = new Builder()
    let n = 0
    for (const lot of city.lots) {
      if (lot === me) continue
      const dx = lot.cx - me.cx
      const dz = lot.cz - me.cz
      const x = dx * c - dz * s
      const z = dx * s + dz * c
      const d = Math.hypot(x, z)
      if (d < inner + 50 || d > 420) continue
      const kind = lot.loc?.type || lot.bld?.kind || 'house'
      const floors = lot.bld?.floors || (kind === 'tower' ? 18 : kind === 'office' ? 8 : kind === 'apartment' ? 4 : kind === 'warehouse' || kind === 'factory' ? 3 : kind === 'park' || kind === 'parking' || kind === 'yard' || kind === 'empty' || kind === 'plaza' ? 0 : 2)
      if (!floors) continue
      const h = floors * 3.3 + 1
      const w = Math.max(8, (lot.fw || 20) * 0.7)
      const dd = Math.max(8, (lot.fd || 20) * 0.6)
      // nearer blocks a touch darker, the far ones melt into the sky
      const k = 0.62 + Math.min(1, (d - inner) / 360) * 0.3 + ((n * 37) % 7) * 0.012
      b.box(w, h, dd, { material: M, color: new THREE.Color(k, k, k), x: lv.x0 + lv.W0 / 2 + x, y: h / 2 - 0.1, z: lv.z0 + lv.H / 2 + z, ry: FACE_ROT[lot.face] - r0, ao: 0, shadow: false })
      if (floors > 8) b.box(w * 0.4, 4, dd * 0.4, { material: M, color: new THREE.Color(k * 0.98, k * 0.98, k * 0.98), x: lv.x0 + lv.W0 / 2 + x, y: h + 2, z: lv.z0 + lv.H / 2 + z, ry: FACE_ROT[lot.face] - r0, ao: 0, shadow: false })
      n++
    }
    const sky = b.build({ shadow: false, receive: false })
    sky.traverse((o) => o.isMesh && (o.castShadow = false))
    g.add(sky)
  },
  // every frame: the skyline takes the colour of the haze it stands in, and
  // the squad (when told to) keeps up
  fpTick(dt) {
    if (this.fpSkyMat) this.fpSkyMat.color.copy(this.scene.fog.color)
    // the lawns draw only the tufts in view
    const G = this.fpGrassF
    if (G && this.fpA) {
      const cv = this.fpCull
      const f = this.fpA.rpos
      if (cv.moved(view.camera, null, f)) {
        cv.capture(view.camera, null, f)
        G.cull(cv, f)
      }
    }
    if (!this.fpFollow || this.paused) return
    this.fpFollowT = (this.fpFollowT || 0) - dt
    if (this.fpFollowT > 0) return
    this.fpFollowT = 1.2
    const me = this.fpA
    let n = 0
    for (const a of this.squad) {
      if (a === me || a.downed || a.dead || a.npc || !this.canOrder(a)) continue
      // busy searching or fighting at close quarters: let them finish
      if (a.work || (a.target && !a.target.dead && a.dist(a.target) < 3)) continue
      n++
      if (a.climb) continue
      const d = a.dist(me)
      if (d < 2.6) continue
      // fall in behind, a little to either side
      const back = me.heading + Math.PI + (n % 2 ? 0.5 : -0.5) * Math.ceil(n / 2)
      const tx = me.pos.x + Math.sin(back) * 1.6
      const tz = me.pos.z + Math.cos(back) * 1.6
      const spot = this.grid.open(Math.floor(tx), Math.floor(tz)) ? { x: tx, z: tz } : { x: me.pos.x, z: me.pos.z }
      const last = a.order?.type === 'move' ? a.order : null
      if (!last || Math.hypot(last.x - spot.x, last.z - spot.z) > 1.2) a.command({ type: 'move', x: spot.x, z: spot.z })
    }
  },
  fpAdd(b, k) {
    const g = b.build()
    g.visible = false
    g.traverse((o) => {
      if (o.isMesh) {
        o.castShadow = true
        o.receiveShadow = true
      }
    })
    ;(this.levelRoot[k] || this.scene).add(g)
    return g
  },
  // Indoors is darker: the sky lights a room through its windows, not
  // straight down. The weathering shader dims ambient light inside these
  // boxes while first person is on.
  fpIndoor(on) {
    INDOOR.uIndoorK.value = on ? 1 : 0
    const R = INDOOR.uIndoorR.value
    const T = INDOOR.uIndoorTop.value
    const list = on ? this.fpIn || [] : []
    INDOOR.uIndoorN.value = Math.min(R.length, list.length)
    list.slice(0, R.length).forEach((r, q) => {
      R[q].set(r[0], r[1], r[2], r[3])
      T[q] = r[4]
    })
  },
}
