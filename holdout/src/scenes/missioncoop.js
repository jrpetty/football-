// Co-op supply runs, mixed into Mission. The player who planned the run
// leads it: their browser runs the mission for real. Friends who joined
// build the same street from the same seed and play it through a stream:
// every survivor and zombie, every container opened or broken, traps,
// shots, blood, fire and finds. Each player gives orders only to their own
// survivors; a friend's orders travel to the leader, whose game carries
// them out. Sight is shared: everyone sees what any survivor sees.
import * as THREE from 'three'
import { SurvivorAgent, ZombieAgent } from '../world/agents.js'
import { view } from '../render/view.js'
import { throwableModel } from '../models/weapons.js'
import { NET, playerOf } from '../game/state.js'
import { ZOMBIES, CONTAINERS } from '../game/data.js'
import { sfx, sfxTap, setAmbience } from '../core/audio.js'
import { angleLerp, clamp, h } from '../core/util.js'

const FRAME = 0.12
const SLOW = 1
// effects worth showing everyone (ambient smoke and embers each side makes itself)
const FWD_FX = ['blood', 'burst', 'tracer', 'muzzle', 'flash', 'sparks', 'explosion']
const FWD_SFX = new Set(['alarm', 'loot', 'rare', 'dismantle', 'unlock', 'truck', 'scream', 'glass', 'fire', 'boom', 'snap', 'burst', 'levelup', 'trapSpot', 'zdie', 'throw', 'shotgun'])
const r2 = (v) => Math.round(v * 100) / 100
const r1 = (v) => Math.round(v * 10) / 10
const vec = (v) => (v && typeof v.x === 'number' && typeof v.z === 'number' ? { v: [r2(v.x), r2(v.y || 0), r2(v.z)] } : v)
const unvec = (v) => (v && v.v ? new THREE.Vector3(v.v[0], v.v[1], v.v[2]) : v)

// What a survivor is doing, for the squad cards.
export function agentStatus(a) {
  const m = a.lastMode
  return a.downed ? `DOWN · ${Math.ceil(a.bleed)}s` : a.work ? (a.work.kind === 'search' ? 'Searching' : a.work.kind === 'hotwire' ? 'Hotwiring' : 'Breaking down') : a.order?.type === 'revive' ? 'Reviving' : a.order?.type === 'throw' ? 'Throwing' : m === 'swing' || m === 'aim' ? 'Fighting' : m === 'run' || m === 'walk' ? 'Moving' : a.pack && a.pack.load >= a.st.carry - 1 ? 'Pack full' : 'Holding'
}

export const CoopMixin = {
  // ---------------------------------------------------------------- shared
  coopInit() {
    const C = this.coop
    this.roster = C.run.roster
    this.ownerOfAgent = new Map()
    for (const [pid, ids] of Object.entries(this.roster)) for (const id of ids) this.ownerOfAgent.set(id, pid)
    this.znid = 0
    for (const z of this.zombies) z.nid ??= ++this.znid
    if (C.role === 'leader') this.coopLeaderInit()
    else this.coopJoinerInit()
  },
  // Whose orders a survivor takes on this run.
  leaderOf(a) {
    return this.ownerOfAgent?.get(a.data?.id) || this.coop?.run.leader
  },
  canOrder(a) {
    if (!this.coop) return true
    if (a.npc) return false
    const p = this.leaderOf(a)
    // a friend who dropped out leaves their survivors to the run's leader
    return p === NET.pid || (this.coop.role === 'leader' && !!this.dropped?.has(p))
  },
  coopPeers() {
    return Object.keys(this.roster).filter((p) => p !== NET.pid)
  },

  // ---------------------------------------------------------------- leader
  coopLeaderInit() {
    this.coopEv = []
    this.coopT = 0
    this.coopSlowT = 0
    this.sentC = new Map()
    this.sentT = new Map()
    this.knownC = this.containers.length
    // capture what friends should see happen
    const push = (e) => this.coopEv.length < 400 && this.coopEv.push(e)
    for (const name of FWD_FX) {
      const orig = this.fx[name].bind(this.fx)
      this.fx[name] = (...args) => {
        orig(...args)
        push(['fx', name, ...args.map(vec)])
      }
    }
    const L = view.labels
    const float = L.float
    const self = this
    this.restoreFloat = () => (L.float = float)
    L.float = function (scene, pos, html, cls) {
      if (scene === self.scene) push(['float', vec(pos), html, cls])
      return float.call(this, scene, pos, html, cls)
    }
    const toast = this.toast.bind(this)
    this.toast = (msg, kind = '') => {
      toast(msg, kind)
      if (!this.quiet) push(['toast', msg, kind])
    }
    sfxTap.fn = (name) => FWD_SFX.has(name) && push(['sfx', name])
    const throwItem = this.throwItem.bind(this)
    this.throwItem = (agent, item, x, z) => {
      throwItem(agent, item, x, z)
      const f = this.flying[this.flying.length - 1]
      if (f) push(['throw', item, vec(f.from), [r2(x), r2(z)], f.dur])
    }
    const addFire = this.addFire.bind(this)
    this.addFire = (x, y, z, r, life, agent = null) => {
      addFire(x, y, z, r, life, agent)
      push(['fire', r2(x), r2(y), r2(z), r, Math.min(life, 1e6)])
    }
    // the joiners need the parts of the street that came from dice
    this.coopSend({ k: 'rs', setup: this.coopSetup() })
  },
  coopSetup() {
    const flags = []
    for (const c of this.containers) {
      if (c.storyItem || c.needsKey || c.drive || c.carKeys || c.locked) flags.push([c.id, c.storyItem || 0, c.needsKey || 0, c.locked ? 1 : 0, c.drive ? 1 : 0, c.carKeys ? c.carKeys.id : -1, c.def?.name || ''])
    }
    const extra = this.containers.slice(this.lv.containers.length).map((c) => this.contDef(c))
    const npc = this.npc ? { data: JSON.parse(JSON.stringify(this.npc.data)), x: r2(this.npc.pos.x), z: r2(this.npc.pos.z) } : null
    return {
      flags,
      extra,
      traps: (this.traps || []).map((t) => [t.id, t.kind, t.i, t.j, t.door ? 1 : 0]),
      npc,
      utils: this.utils,
      hordeIn: this.hordeIn,
      vehicle: this.loadout.vehicle,
      stashCap: this.stashCap === Infinity ? -1 : this.stashCap,
      dropAt: this.dropAt ? [this.dropAt.x, this.dropAt.z] : null,
    }
  },
  contDef(c) {
    return { id: c.id, kind: c.kind, name: c.def?.name, room: c.room, tiles: c.tiles, x: c.x, z: c.z, rot: c.rot || 0, seed: c.seed, bucket: c.bucket, searched: !!c.searched, open: c.open || 0, stash: c.stash ? c.stash.length : 0 }
  },
  contSig(c) {
    return `${c.searched ? 1 : 0}${c.gone ? 1 : 0}${c.stash ? c.stash.length : 0}|${c.openGoal >= 1 ? 1 : c.openGoal > 0 ? 2 : 0}${c.drive?.started ? 1 : 0}${c.drive?.keys ? 1 : 0}${c.storyFound ? 1 : 0}${c.locked ? 1 : 0}|${c.label?.el.className || ''}`
  },
  coopSend(d) {
    const net = this.game.net
    if (!net) return
    net.relay(this.coopPeers(), { ...d, run: this.coop.run.id })
  },
  coopLeaderTick(dt) {
    this.coopT -= dt
    this.coopSlowT -= dt
    if (this.coopT > 0) return
    // the claude.ai room carries fewer frames a second; a line that is
    // backing up skips a frame rather than falling behind
    const line = this.game.net?.c
    this.coopT = line?.kind === 'room' ? 0.25 : FRAME
    if (line?.backlog > 3) return
    const f = { k: 'rf', t: r1(this.elapsed), ho: this.hordeOn ? 1 : 0 }
    f.a = this.squad.map((a) => {
      return [a.data.id, r2(a.pos.x), r2(a.pos.z), r2(a.heading), a.lastMode || 'idle', r1(a.hp), a.downed ? 1 : 0, Math.ceil(a.bleed || 0), a.shots || 0, a.progV == null ? -1 : r2(a.progV), r2(a.pos.y || 0), r1(a.pack?.load || 0), agentStatus(a), a.npc ? 1 : 0]
    })
    f.z = this.zombies.map((z) => {
      z.nid ??= ++this.znid
      const moving = !!z.path && !z.stun
      return [z.nid, z.type, r2(z.pos.x), r2(z.pos.z), r2(z.heading), r2(Math.max(0, z.hp / z.maxHp)), z.dead ? 1 : 0, moving ? 1 : 0, z.swing > 0.05 || (z.state === 'fence' && !moving) ? 1 : 0, z.stun > 0 ? 1 : 0, z.burn > 0 ? 1 : 0, r2(z.pos.y || 0)]
    })
    // containers and traps that changed
    const cs = []
    for (const c of this.containers) {
      const sig = this.contSig(c)
      if (this.sentC.get(c.id) === sig) continue
      this.sentC.set(c.id, sig)
      cs.push(c.id >= this.knownC ? { ...this.contDef(c), sig } : [c.id, sig])
    }
    if (cs.length) f.c = cs
    const ts = []
    for (const t of this.traps || []) {
      const sig = `${t.armed ? 1 : 0}${t.spotted ? 1 : 0}${t.gone ? 1 : 0}`
      if (this.sentT.get(t.id) === sig) continue
      this.sentT.set(t.id, sig)
      ts.push([t.id, t.armed ? 1 : 0, t.spotted ? 1 : 0, t.gone ? 1 : 0])
    }
    if (ts.length) f.tr = ts
    if (this.coopEv.length) {
      f.ev = this.coopEv
      this.coopEv = []
    }
    if (this.coopSlowT <= 0) {
      this.coopSlowT = SLOW
      this.checkDropped()
      // now and then everything again, in case a frame was dropped on the way
      if ((this.coopFull = (this.coopFull || 0) + 1) % 5 === 0) {
        this.sentC.clear()
        this.sentT.clear()
      }
      f.van = this.van.res
      f.vi = this.van.items
      f.u = this.utils
      f.packs = Object.fromEntries(this.squad.map((a) => [a.data.id, a.pack ? { res: a.pack.res, items: a.pack.items } : null]))
    }
    this.coopSend(f)
  },
  // Friends who lost their connection hand their survivors to the leader.
  checkDropped() {
    const on = this.game.net?.online
    if (!on) return
    this.dropped ??= new Set()
    for (const p of this.coopPeers()) {
      if (on.has(p) || this.dropped.has(p)) continue
      this.dropped.add(p)
      const names = this.squad.filter((a) => this.leaderOf(a) === p).map((a) => a.data.first)
      if (names.length) this.toast(`${playerOf(p)?.name || 'A friend'} dropped out. You lead ${names.join(' and ')} for the rest of the run.`, 'bad')
      this.renderSquad()
    }
  },
  // A friend's order for one of their survivors.
  coopOrder(pid, d) {
    const a = this.squad.find((x) => x.data.id === d.sid)
    if (!a || a.downed || this.leaderOf(a) !== pid) return
    const o = d.o || {}
    const cont = o.c == null ? null : o.trap ? (this.traps || []).find((t) => t.id === o.c) : this.containers.find((c) => c.id === o.c)
    if (cont && o.smash != null) cont.smash = !!o.smash
    this.quiet = true
    try {
      if (o.type === 'move') a.command({ type: 'move', x: +o.x, z: +o.z })
      else if (o.type === 'attack') {
        const z = this.zombies.find((x) => x.nid === o.z && !x.dead)
        if (z) a.command({ type: 'attack', z })
      } else if (o.type === 'revive') {
        const b = this.squad.find((x) => x.data.id === o.a)
        if (b?.downed) a.command({ type: 'revive', a: b })
      } else if ((o.type === 'search' || o.type === 'dismantle' || o.type === 'hotwire') && cont) a.command({ type: o.type, c: cont })
      else if (o.type === 'throw') this.throwFor(a, o.item, +o.x, +o.z)
      else if (o.type === 'medkit') this.medkitFor(a)
    } finally {
      this.quiet = false
    }
  },
  throwFor(who, kind, x, z) {
    if (!this.utils[kind]) return
    const d = Math.hypot(who.pos.x - x, who.pos.z - z)
    if (d > 16) {
      const dir = Math.atan2(x - who.pos.x, z - who.pos.z)
      who.command({ type: 'move', x: x - Math.sin(dir) * 12, z: z - Math.cos(dir) * 12 })
      who.pendingThrow = { item: kind, x, z }
      return
    }
    who.command({ type: 'throw', item: kind, x, z })
  },
  medkitFor(a) {
    if (!this.utils.medkit || a.hp >= a.maxHp) return
    this.utils.medkit--
    a.hp = Math.min(a.maxHp, a.hp + a.maxHp * 0.5)
    view.labels.float(this.scene, a.chestPos(2), 'First aid kit', 'good')
    this.renderUtils()
  },
  coopEnd(result, report) {
    if (this.coop?.role !== 'leader') return
    // what happened to everyone's survivors reaches the host first
    this.game.net?.flush?.()
    const last = { k: 'runEnd', result, report: { result, title: report.title, text: report.text, loot: report.loot, items: (report.items || []).map((it) => ({ uid: it.uid, id: it.id, q: it.q, cond: it.cond, mods: it.mods || [] })), injured: report.injured, lost: report.lost, rescued: report.rescued, vehicle: report.vehicle } }
    this.coopSend(last)
    this.game.coop?.ended(this.coop.run.id, { ...last, run: this.coop.run.id })
  },

  // ---------------------------------------------------------------- joiner
  coopJoinerInit() {
    this.frame = null
    this.lastFrameAt = performance.now()
    this.zmap = new Map()
    // orders for my survivors go to the leader; the rest just stand there
    for (const a of this.squad) this.bindRemote(a)
    this.toast(`${playerOf(this.coop.run.leader)?.name || 'The leader'} leads this run. You give orders to your own survivors.`, 'good')
  },
  bindRemote(a) {
    a.command = (o) => {
      if (!this.canOrder(a)) return false
      const out = { type: o.type }
      if (o.x != null) ((out.x = r2(o.x)), (out.z = r2(o.z)))
      if (o.c) {
        out.c = o.c.id
        if (o.c.def?.isTrap) out.trap = 1
        if (o.c.smash != null) out.smash = o.c.smash ? 1 : 0
      }
      if (o.z?.nid != null) out.z = o.z.nid
      if (o.a?.data) out.a = o.a.data.id
      if (o.item) ((out.item = o.item), (out.x = r2(o.x)), (out.z = r2(o.z)))
      this.coopSend({ k: 'rc', sid: a.data.id, o: out })
      return true
    }
  },
  // the dice-rolled parts of the street, as the leader rolled them
  coopApplySetup(s) {
    if (this.setupDone) return
    this.setupDone = true
    for (const [id, story, key, locked, drive, keysFor, name] of s.flags || []) {
      const c = this.containers.find((x) => x.id === id)
      if (!c) continue
      if (story) ((c.storyItem = story), c.label?.el.classList.add('story'))
      if (key) c.needsKey = key
      c.locked = !!locked
      if (drive) ((c.drive = { keys: false, started: false }), c.label?.el.classList.add('drivable'))
      if (name) c.def = { ...c.def, name }
      void keysFor
    }
    for (const d of s.extra || []) this.addRemoteContainer(d)
    this.traps = []
    for (const [, kind, i, j, door] of s.traps || []) this.addTrap(kind, i, j, !!door)
    if (s.npc) {
      const a = new SurvivorAgent(this, s.npc.data, s.npc.x, s.npc.z)
      a.npc = true
      a.pack = { res: {}, items: [], load: 0 }
      a.labelEl.classList.add('npc')
      a.labelEl.querySelector('.nm').textContent = 'SOS · ' + s.npc.data.first
      this.npc = a
      this.npcData = s.npc.data
    }
    this.utils = { ...this.utils, ...(s.utils || {}) }
    this.hordeIn = s.hordeIn
    this.renderUtils()
    this.renderSquad()
  },
  addRemoteContainer(d) {
    if (this.containers.find((x) => x.id === d.id)) return
    const def = { ...(CONTAINERS[d.kind] || CONTAINERS.crate), name: d.name || CONTAINERS[d.kind]?.name }
    const c = { id: d.id, kind: d.kind, def, room: d.room, tiles: d.tiles, x: d.x, z: d.z, rot: d.rot, searched: d.searched, gone: false, open: d.open, openGoal: d.open, seed: d.seed, bucket: d.bucket || 'drop', stash: d.stash ? new Array(d.stash) : null }
    const top = d.kind === 'milcrate' ? 1.1 : 0.8
    c.box = new THREE.Box3(new THREE.Vector3(d.x - 0.5, 0, d.z - 0.5), new THREE.Vector3(d.x + 0.5, top, d.z + 0.5))
    c.label = view.labels.add(h('div.lootmark' + (d.kind === 'milcrate' ? '.drop' : '.stash')), new THREE.Vector3(d.x, top + 0.4, d.z), { scene: this.scene, maxDist: 40 })
    for (const [i, j] of d.tiles) this.lv.grid.set(i, j, 255, 0, c)
    this.containers.push(c)
    if (!this.buckets.has(c.bucket)) this.buckets.set(c.bucket, { list: [], group: null })
    this.buckets.get(c.bucket).list.push(c)
    this.buildBucket(c.bucket)
  },
  coopFrame(f) {
    this.frame = f
    this.lastFrameAt = performance.now()
    if (f.t != null) this.elapsed = f.t
    if (f.ho && !this.hordeOn) setAmbience(0.07)
    this.hordeOn = !!f.ho
    // survivors
    const seen = new Set()
    for (const [sid, x, z, hd, mode, hp, down, bleed, shots, prog, y, load, status, npc] of f.a || []) {
      seen.add(sid)
      let a = this.squad.find((s) => s.data.id === sid)
      if (!a && this.npc?.data.id === sid && !npc) {
        // the rescued survivor joined the squad
        a = this.npc
        a.npc = false
        a.labelEl.classList.remove('npc')
        a.labelEl.querySelector('.nm').textContent = a.data.first
        this.squad.push(a)
        this.renderSquad()
      }
      if (!a) {
        if (npc && this.npc) a = this.npc
        else continue
      }
      a.tgt = { x, z, hd, mode, y }
      a.hp = hp
      a.downed = !!down
      a.bleed = bleed
      a.remoteStatus = status
      if (a.pack) a.pack.load = load
      if (shots > (a.shots || 0)) {
        const first = a.shots == null
        a.shots = shots
        if (!first) this.remoteShot(a)
      }
      if (prog >= 0) a.showProg(prog)
      else a.showProg(null)
    }
    for (const a of [...this.squad]) {
      if (seen.has(a.data.id) || a.npc) continue
      // gone: bled out, turned or left behind
      a.dead = true
      a.remove()
      this.squad = this.squad.filter((x) => x !== a)
      this.selected.delete(a)
      this.renderSquad()
    }
    // zombies
    const zs = new Set()
    for (const [nid, type, x, z, hd, hp, dead, mv, atk, stun, burn, y] of f.z || []) {
      zs.add(nid)
      let p = this.zmap.get(nid)
      if (!p) {
        if (dead || !ZOMBIES[type]) continue
        p = new ZombieAgent(this, type, x, z, this.level, this.def.zombieTheme)
        p.nid = nid
        p.heading = hd
        this.zmap.set(nid, p)
        this.zombies.push(p)
      }
      p.tgt = { x, z, hd, hp, mv, atk, stun, burn, y }
      if (dead && !p.dead) {
        p.dead = true
        p.deadT = 0
        p.label.remove()
      }
    }
    for (const [nid, p] of this.zmap) {
      if (zs.has(nid)) continue
      p.remove()
      this.zmap.delete(nid)
      this.zombies = this.zombies.filter((x) => x !== p)
    }
    // containers
    for (const e of f.c || []) {
      if (!Array.isArray(e)) this.addRemoteContainer(e)
      const [id, sig] = Array.isArray(e) ? e : [e.id, e.sig]
      const c = this.containers.find((x) => x.id === id)
      if (c) this.applyContSig(c, sig)
    }
    for (const [id, armed, spotted, gone] of f.tr || []) {
      const t = (this.traps || []).find((x) => x.id === id)
      if (!t) continue
      if (spotted && !t.spotted) this.revealTrap(t, null)
      if (!armed && t.armed) this.clearTrap(t, t.kind === 'beartrap' && !gone)
    }
    for (const e of f.ev || []) this.remoteEvent(e)
    if (f.van) {
      this.van.res = f.van
      this.van.items = f.vi || []
      for (const [sid, p] of Object.entries(f.packs || {})) {
        const a = this.squad.find((x) => x.data.id === sid)
        if (a && p) a.pack = { ...a.pack, res: p.res, items: p.items }
      }
      this.utils = { ...this.utils, ...(f.u || {}) }
      this.renderUtils()
      this.updateHaul()
    }
  },
  applyContSig(c, sig) {
    const [a, b, cls] = sig.split('|')
    const searched = a[0] === '1'
    const gone = a[1] === '1'
    const stash = +a.slice(2)
    c.searched = searched
    c.stash = stash ? new Array(stash) : null
    c.openGoal = b[0] === '1' ? 1 : b[0] === '2' ? Math.max(c.openGoal, 0.5) : c.openGoal
    if (c.drive) {
      c.drive.started = b[1] === '1'
      c.drive.keys = b[2] === '1'
    }
    c.storyFound = b[3] === '1'
    c.locked = b[4] === '1'
    if (c.label && cls) c.label.el.className = cls
    if (gone && !c.gone) this.removeContainer(c)
  },
  remoteShot(a) {
    a.ch.fire()
    if (this.nearCamera(a.pos)) sfx(a.st.weaponId === 'shotgun' ? 'shotgun' : a.st.weaponId === 'rifle' ? 'rifle' : a.st.weaponId === 'smg' || a.st.weaponId === 'ar' ? 'smg' : 'pistol', 40)
  },
  remoteEvent(e) {
    const [kind, ...r] = e
    if (kind === 'fx') {
      const [name, ...args] = r
      if (FWD_FX.includes(name)) this.fx[name](...args.map(unvec))
    } else if (kind === 'float') view.labels.float(this.scene, unvec(r[0]), r[1], r[2])
    else if (kind === 'toast') this.toast(r[0], r[1])
    else if (kind === 'sfx') sfx(r[0], 60)
    else if (kind === 'throw') {
      const [item, from, to, dur] = r
      const m = throwableModel(item)
      const f = unvec(from)
      m.position.copy(f)
      this.scene.add(m)
      this.flying = (this.flying || []).concat({ m, from: f, to: new THREE.Vector3(to[0], 0.2, to[1]), t: 0, dur, item, remote: true })
    } else if (kind === 'fire') {
      const [x, y, z, rr, life] = r
      this.addFire(x, y, z, rr, life)
    }
  },
  // the street as the leader last described it, smoothed between frames
  coopJoinerTick(dt) {
    const k = 1 - Math.exp(-dt * 10)
    for (const a of this.squad.concat(this.npc && this.npc.npc ? [this.npc] : [])) {
      const t = a.tgt
      if (!t) {
        a.finish(dt, 'idle')
        continue
      }
      const dx = t.x - a.pos.x
      const dz = t.z - a.pos.z
      a.curSpeed = Math.min(6, Math.hypot(dx, dz) * 5)
      a.pos.x += dx * k
      a.pos.z += dz * k
      a.pos.y = this.floorY(a.pos.x, a.pos.z)
      a.heading = angleLerp(a.heading, t.hd, k)
      if (a.downed) {
        a.ch.update(dt, 'downed', {})
        a.labelEl.classList.add('down')
        a.labelEl.querySelector('.nm').textContent = `${a.data.first} · ${a.bleed}s`
        a.labelBar.style.width = `${clamp(a.hp / a.maxHp, 0, 1) * 100}%`
        a.sync()
        continue
      }
      a.path = a.curSpeed > 0.4 ? true : null
      a.swing = t.mode === 'swing' ? (a.swing + dt * 2.2) % 1 : 0
      a.finish(dt, t.mode)
      a.path = null
    }
    for (const z of this.zombies) {
      const t = z.tgt
      if (!t) continue
      if (z.dead) {
        z.deadT += dt
        z.ch.update(dt, 'dead', {})
        if (z.deadT > 4) z.pos.y = -(z.deadT - 4) * 0.35
        z.sync()
        continue
      }
      z.pos.x += (t.x - z.pos.x) * k
      z.pos.z += (t.z - z.pos.z) * k
      z.pos.y = this.floorY(z.pos.x, z.pos.z)
      z.heading = angleLerp(z.heading, t.hd, k)
      z.path = t.mv ? true : null
      const d = z.def
      const anim = t.stun ? 'downed' : t.atk ? (d.crawl ? 'zcrawl' : 'zattack') : t.mv ? (d.crawl ? 'zcrawl' : d.speed > 2 ? 'zrun' : 'zwalk') : d.crawl ? 'zcrawl' : 'zidle'
      z.swing = t.atk ? (z.swing + dt * 1.6) % 1 : 0
      z.ch.update(dt, anim, { speed: t.mv ? z.speed : 0, swing: z.swing })
      if (t.burn && Math.random() < dt * 12) this.fx.ember(z.chestPos(0.4 + Math.random() * 1.2), 0.5)
      z.label.hidden = t.hp >= 0.999 || z.fogHidden
      z.labelBar.style.width = `${clamp(t.hp, 0, 1) * 100}%`
      z.sync()
    }
    for (const c of this.containers) {
      if (c.open < c.openGoal) {
        c.open = Math.min(c.openGoal, c.open + dt * 2.2)
        this.applyOpen(c)
      }
    }
    this.updateThrown(dt)
    this.updateFires(dt)
    for (const t of this.traps || []) {
      if (t.ring.visible) t.ring.material.opacity = 0.35 + Math.sin(performance.now() / 220) * 0.25
      if (t.label) t.label.hidden = this.vision && !this.vision.exploredAt(t.x, t.z)
    }
    this.time += dt
    // the leader went quiet: wait while the camp still sees them, give up
    // soon once it does not
    const quiet = (performance.now() - this.lastFrameAt) / 1000
    const leader = this.coop.run.leader
    const gone = this.game.net?.online && !this.game.net.online.has(leader)
    if (this.pauseEl) {
      this.pauseEl.hidden = quiet < 3 || this.over
      if (!this.pauseEl.hidden) this.pauseEl.textContent = `Waiting for ${playerOf(leader)?.name || 'the run leader'}…`
    }
    // still there but quiet: the run may be over and the word lost on the way
    if (!this.over && !gone && quiet > 5) this.game.coop?.askRun(this.coop.run.id, leader)
    if (!this.over && ((gone && quiet > 6) || quiet > 90)) this.game.coop?.leaderLost(this.coop.run.id)
  },
}

// stop listening in on sounds when the run is over
export const stopTap = () => (sfxTap.fn = null)
