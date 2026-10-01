// Raids with friends. The host fights the horde in its camp as usual and
// streams where every zombie and defender is a few times a second; guests
// watch the same fight with puppets that glide between those frames, and
// can order the defenders they lead (the host carries the orders out).
import * as THREE from 'three'
import { SurvivorAgent, ZombieAgent } from '../world/agents.js'
import { pickAt, groundAt } from '../render/view.js'
import { S, NET, getS, canControl } from '../game/state.js'
import { ZOMBIES } from '../game/data.js'
import { sfx } from '../core/audio.js'
import { angleLerp, clamp } from '../core/util.js'

const FRAME_EVERY = 0.2
const r1 = (v) => Math.round(v * 10) / 10
const r2 = (v) => Math.round(v * 100) / 100
let zid = 0

export const RaidNetMixin = {
  // ---------------------------------------------------------------- host
  raidStream(dt) {
    const net = this.game.net
    if (!net || !S.raid || (!net.captain && !net.peers.size)) return
    this.rzT = (this.rzT ?? 0) - dt
    if (this.rzT > 0 || net.c.backlog > 3) return
    this.rzT = FRAME_EVERY
    const z = (this.zombies || []).map((a) => {
      a.nid ??= ++zid
      const moving = !!a.path && a.curSpeed > 0.05 && !a.stun
      return [a.nid, a.type, r1(a.pos.x), r1(a.pos.z), r2(a.heading), r2(Math.max(0, a.hp / a.maxHp)), a.dead ? 1 : 0, moving ? 1 : 0, a.swing > 0.05 || (a.state === 'fence' && !moving) ? 1 : 0]
    })
    const s = (this.squad || []).map((a) => [a.data.id, r1(a.pos.x), r1(a.pos.z), r2(a.heading), a.lastMode || 'idle', r2(Math.max(0, a.hp / a.maxHp)), a.downed ? 1 : 0, a.shots || 0, a.tower ? 1 : 0, r1(a.pos.y || 0)])
    // a captain sends through the host, which passes it on to everyone
    if (net.captain) net.c.send({ t: 'rz', z, s, k: S.raid.killed }, net.hostPeer)
    else net.c.send({ t: 'rz', z, s, k: S.raid.killed })
  },
  // A guest's order for a defender they lead.
  raidCommand(pid, m) {
    if (!S.raid) return
    const a = (this.squad || []).find((x) => x.data.id === m.sid)
    const owner = S.mp?.owner?.[m.sid]
    if (!a || a.downed || (owner && owner !== pid)) return
    if (m.zid != null) {
      const z = (this.zombies || []).find((x) => x.nid === m.zid && !x.dead)
      if (z) a.command({ type: 'attack', z })
    } else if (m.rev) {
      const b = this.squad.find((x) => x.data.id === m.rev)
      if (b?.downed) a.command({ type: 'revive', a: b })
    } else if (!a.tower && Number.isFinite(m.x) && Number.isFinite(m.z)) a.command({ type: 'move', x: m.x, z: m.z })
  },

  // ---------------------------------------------------------------- guest
  // Called when a frame arrives.
  raidMirror(m) {
    if (NET.role !== 'client') return
    this.rz = m
    this.rzAt = performance.now()
    if (!this.mirror) this.startMirror()
    const M = this.mirror
    const seen = new Set()
    for (const [id, type, x, z, hd, hp, dead, mv, atk] of m.z || []) {
      seen.add(id)
      let p = M.z.get(id)
      if (!p) {
        if (dead || !ZOMBIES[type]) continue
        p = new ZombieAgent(this, type, x, z, S.raid?.lvl || 1)
        p.heading = hd
        const zz = p
        p.root.traverse((o) => o.isMesh && (o.userData.pick = { type: 'zombie', z: zz }))
        M.z.set(id, p)
      }
      p.tgt = { x, z, hd, hp, mv, atk }
      if (dead && !p.dead) {
        p.dead = true
        p.deadT = 0
        p.label.remove()
        this.fx.blood(p.chestPos(0.6), true)
        sfx('zdie', 80)
      }
    }
    for (const [id, p] of M.z) if (!seen.has(id)) (p.remove(), M.z.delete(id))
    const want = new Set()
    for (const [sid, x, z, hd, mode, hp, down, shots, tower, y] of m.s || []) {
      want.add(sid)
      let p = M.s.get(sid)
      if (!p) {
        const data = getS(sid)
        if (!data) continue
        p = new SurvivorAgent(this, data, x, z)
        p.pos.y = y
        p.shots = shots
        p.root.traverse((o) => o.isMesh && (o.userData.pick = { type: 'defender', a: p }))
        M.s.set(sid, p)
      }
      if (shots > p.shots) {
        p.shots = shots
        const fwd = new THREE.Vector3(Math.sin(p.heading), 0, Math.cos(p.heading))
        const from = p.chestPos(1.38).addScaledVector(fwd, 0.8)
        this.fx.muzzle(from)
        this.fx.tracer(from, from.clone().addScaledVector(fwd, 9))
        p.ch.fire()
        if (this.nearCamera(from)) sfx(p.st.weaponId === 'shotgun' ? 'shotgun' : p.st.weaponId === 'rifle' ? 'rifle' : 'pistol', 40)
      }
      p.tgt = { x, z, hd, mode, y }
      p.hp = hp * p.maxHp
      p.downed = !!down
      p.tower = tower ? {} : null
    }
    for (const [sid, p] of M.s) if (!want.has(sid)) (p.remove(), M.s.delete(sid))
    const list = [...M.s.values()]
    if (list.length !== this.squad.length || list.some((a, i) => this.squad[i] !== a)) {
      this.squad = list
      this.game.ui?.updateRaidBar(true)
    }
  },
  startMirror() {
    this.mirror = { z: new Map(), s: new Map() }
    this.squad = []
    this.selectedDef = null
    this.people.setVisible(false)
    this.game.ui?.raidHud(true)
    sfx('alarm')
  },
  stopMirror() {
    const M = this.mirror
    if (!M) return
    for (const p of M.z.values()) p.remove()
    for (const p of M.s.values()) p.remove()
    this.mirror = null
    this.squad = []
    this.selectedDef = null
    this.people.setVisible(true)
    this.people.sync()
    this.game.ui?.raidHud(false)
  },
  // The raid as the host last described it, smoothed.
  updateMirror(dt) {
    if (S.raid && !this.mirror) this.startMirror()
    if (!S.raid) return this.stopMirror()
    const M = this.mirror
    const k = 1 - Math.exp(-dt * 9)
    for (const p of M.z.values()) {
      const t = p.tgt
      if (!t) continue
      if (p.dead) {
        p.deadT += dt
        p.ch.update(dt, 'dead', {})
        if (p.deadT > 4) p.pos.y = -(p.deadT - 4) * 0.35
        p.sync()
        continue
      }
      p.pos.x += (t.x - p.pos.x) * k
      p.pos.z += (t.z - p.pos.z) * k
      p.heading = angleLerp(p.heading, t.hd, k)
      const d = p.def
      const anim = t.atk ? (d.crawl ? 'zcrawl' : 'zattack') : t.mv ? (d.crawl ? 'zcrawl' : d.speed > 2 ? 'zrun' : 'zwalk') : d.crawl ? 'zcrawl' : 'zidle'
      p.swing = t.atk ? (p.swing + dt * 1.6) % 1 : 0
      p.ch.update(dt, anim, { speed: t.mv ? p.speed : 0, swing: p.swing })
      p.label.hidden = t.hp >= 0.999
      p.labelBar.style.width = `${clamp(t.hp, 0, 1) * 100}%`
      p.sync()
    }
    for (const p of M.s.values()) {
      const t = p.tgt
      if (!t) continue
      const dx = t.x - p.pos.x
      const dz = t.z - p.pos.z
      p.curSpeed = Math.min(6, Math.hypot(dx, dz) * 4)
      p.pos.x += dx * k
      p.pos.z += dz * k
      p.pos.y += ((t.y || 0) - p.pos.y) * k
      p.heading = angleLerp(p.heading, t.hd, k)
      p.path = p.curSpeed > 0.4 ? true : null
      if (p.downed) {
        p.ch.update(dt, 'downed', {})
        p.labelEl.classList.add('down')
        p.labelBar.style.width = `${clamp(p.hp / p.maxHp, 0, 1) * 100}%`
        p.sync()
        continue
      }
      p.labelEl.classList.remove('down')
      p.swing = t.mode === 'swing' ? (p.swing + dt * 2.2) % 1 : 0
      p.finish(dt, t.mode)
      p.path = null
    }
  },
  mirrorTap(x, y, e) {
    const M = this.mirror
    if (!M) return
    const zs = [...M.z.values()].filter((z) => !z.dead)
    const hit = pickAt(x, y, [...this.squad.map((a) => a.root), ...zs.map((z) => z.root)])
    const pk = hit?.pick
    if (pk?.type === 'defender' && e.button !== 2) {
      const a = pk.a
      if (a.downed && this.selectedDef && this.selectedDef !== a) return this.game.net.c.send({ t: 'rcmd', sid: this.selectedDef.data.id, rev: a.data.id }, this.game.net.hostPeer)
      if (!canControl(a.data)) return this.game.ui?.toast(`${a.data.first} takes orders from ${S.mp.players[S.mp.owner[a.data.id]]?.name || 'someone else'}.`)
      this.selectDefender(a)
      sfx('select')
      return
    }
    const sel = this.selectedDef
    if (!sel) return
    const net = this.game.net
    if (pk?.type === 'zombie') {
      const id = [...M.z.entries()].find(([, z]) => z === pk.z)?.[0]
      if (id != null) net.c.send({ t: 'rcmd', sid: sel.data.id, zid: id }, net.hostPeer)
      sfx('move')
      return
    }
    const p = groundAt(x, y)
    if (p && !sel.tower) {
      net.c.send({ t: 'rcmd', sid: sel.data.id, x: r1(p.x), z: r1(p.z) }, net.hostPeer)
      this.fx.ring(p)
      sfx('move')
    }
  },
}
