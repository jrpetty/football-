// First person on a run (see render/firstperson.js): the player walks as one
// of the squad while the others keep their orders. The top-down aids give
// way to the real place: no cutaway, no fog-of-war veil (you see what your
// eyes see), every floor of a tall building in place, ceilings and roofs on
// every building and interior walls that reach them, darker indoors.
import * as THREE from 'three'
import { view } from '../render/view.js'
import { Builder } from '../models/kit.js'
import { OUTFITS } from '../models/character.js'
import { fpLook } from '../models/viewmodel.js'
import { slideMove, fpShoot, fpMelee } from '../world/fpcombat.js'
import { ITEMS, RES } from '../game/data.js'
import { FACE_ROT } from '../world/city.js'
import { INDOOR } from '../render/materials.js'
import { sfx } from '../core/audio.js'
import { clamp } from '../core/util.js'

const SHADOW_ONLY = new THREE.MeshBasicMaterial({ colorWrite: false, depthWrite: false })
const CEIL = 3.0
const WALL_INT = 2.35
const T_WALL = 0.22

// The player's own body: still casts its shadow, draws nothing (the camera
// is inside its head). Its weapon, label and ring go too.
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
    this.fpTake(a)
    this.fpTorch = false
    if (this.fowU) this.fowU.uOn.value = 0
    for (const u of this.cutUs) u.n.value = 0
    for (let q = 1; q < this.levelRoot.length; q++) this.levelRoot[q].visible = true
    this.fpBuild()
    for (const g of this.fpGroups) g.visible = true
    this.fpSurroundings(true)
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
    a.order = null
    a.path = null
    a.work = null
    a.target = null
    a.pendingThrow = null
    for (const x of [...this.selected]) if (x !== a) x.select(false)
    this.selected = new Set([a])
    a.select(true)
    a.ring.visible = false
    hideBody(a.ch, true)
    if (a.label) a.label.hidden = true
    this.fpWork = null
    this.renderSquad()
  },
  fpRelease(a) {
    a.fp = false
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
    this.fpIndoor(false)
    this.atmo.fp = false
    document.body.classList.remove('fprun')
    // back to one floor at a time, walls cut away
    if (this.F) this.setView(this.view, true)
    else this.cutU.n.value = this.cutN
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
  fpFace(heading, speed, run) {
    const a = this.fpA
    if (!a.climb && !a.path) a.heading = heading
    a.fpSpeed = speed
    a.fpRun = run
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
    let ammo = ''
    if (st.gun) ammo = st.ammoType ? `<b>${this.ammoLeft(st.ammoType)}</b> ${RES[st.ammoType]?.name.toLowerCase() || ''}` : '<b>∞</b> bolts'
    if (it && ITEMS[st.weaponId]?.dur) ammo += `<small>${Math.round((it.cond / ITEMS[st.weaponId].dur) * 100)}% condition</small>`
    if (!this.fpLookCache || this.fpLookCache.id !== a.data.id) this.fpLookCache = { id: a.data.id, look: fpLook(a.data, OUTFITS[a.data.occ] || OUTFITS.drifter) }
    const W = this.fpWork
    this.fpTipT = (this.fpTipT || 0) - 0.016
    let tip = this.fpTipT > 0 ? this.fpTipText : ''
    if (!tip && a.climb) tip = a.climb.kind === 'lift' ? 'Riding the lift…' : ''
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
    }
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
      // the main tall building's lower floors have the next slab overhead
      const covered = (B) => this.F && k < levels - 1 && (k > 0 || (B.i0 === lv.bld?.i0 && B.j0 === lv.bld?.j0))
      const top = (B) => (covered(B) ? FH - 0.22 : CEIL)
      for (const B of blds) {
        const p0 = this.center(B.i0, B.j0)
        const p1 = this.center(B.i1, B.j1)
        const dx0 = k ? lv.off[k] : 0
        this.fpIn.push([p0.x - dx0, p0.z, p1.x - dx0, p1.z, k * FH + top(B) + 0.2])
        if (covered(B)) continue
        // a plaster ceiling inside, a flat tarred roof over the walls
        b.box(p1.x - p0.x, 0.04, p1.z - p0.z, { mat: 'plaster', color: '#d8d2c6', x: (p0.x + p1.x) / 2, y: CEIL + 0.02, z: (p0.z + p1.z) / 2, ao: 0 })
        b.box(p1.x - p0.x + 0.5, 0.16, p1.z - p0.z + 0.5, { mat: 'roofTar', color: '#7a7670', x: (p0.x + p1.x) / 2, y: 3.15 + 0.08, z: (p0.z + p1.z) / 2, ao: 0 })
        b.box(p1.x - p0.x + 0.56, 0.1, p1.z - p0.z + 0.56, { mat: 'concrete', color: '#8e8a82', x: (p0.x + p1.x) / 2, y: 3.15 + 0.2, z: (p0.z + p1.z) / 2, ao: 0 })
        this.fpRoofs.push({ x0: p0.x - dx0 - 0.3, z0: p0.z - 0.3, x1: p1.x - dx0 + 0.3, z1: p1.z + 0.3, y0: k * FH + CEIL, y1: k * FH + 3.4 })
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
              b.box(alongX ? 0.5 : 0.012, hgt, alongX ? 0.012 : 0.5, { mat: 'plaster', color: col, x: alongX ? ax : c.x + s * (T_WALL / 2 + 0.006), y, z: alongX ? c.z + s * (T_WALL / 2 + 0.006) : az, ao: 0 })
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
      this.fpGroups.push(this.fpAdd(b, k))
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
