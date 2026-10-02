// Horde attacks played out live in the camp, and the stragglers that wander
// up to the wall at night. Mixed into BaseScene.
import * as THREE from 'three'
import { SurvivorAgent, ZombieAgent } from '../world/agents.js'
import { view, pickAt, groundAt } from '../render/view.js'
import { isNight } from '../render/sky.js'
import { STATIONS, zombieMix, ITEMS } from '../game/data.js'
import { NET, S, day, bounds, workersOf, survivorStats, fenceMax, completeGoal, log, killSurvivor, getS, gateTiles, addMoraleEvent } from '../game/state.js'
import { scheduleRaid, power, finishGame } from '../game/economy.js'
import { sfx, setAmbience } from '../core/audio.js'
import { deed } from '../game/deeds.js'
import { rand, pick, chance, weighted, clamp, angleLerp } from '../core/util.js'
import { soundAt } from '../world/sound.js'

const _v = new THREE.Vector3()

export const RaidMixin = {
  // ---------------------------------------------------------------- world interface for agents
  ammoLeft(type) {
    return S.res[type] || 0
  },
  useAmmo(type, n) {
    S.res[type] = Math.max(0, (S.res[type] || 0) - n)
  },
  isNight() {
    return isNight(((S.time % 1440) / 60))
  },
  nightAcc(a) {
    if (a.st?.nightSight >= 1) return 1
    const pinfo = power()
    const lit = S.stations.some((st) => st.type === 'floodlight' && pinfo.powered.has(st.id) && Math.hypot(st.x + 0.5 - a.pos.x, st.z + 0.5 - a.pos.z) < 14)
    return lit ? 1 : 0.72 + (a.st?.nightSight || 0) * 0.28
  },
  dmgBonus(a) {
    if (!a.tower) return 1
    return 1 + STATIONS.watchtower.towerDmg[a.tower.level - 1] + (a.data.occ === 'guard' ? 0.3 : 0)
  },
  // a horde fight is heard from where the camera looks, left or right
  sound(id, pos, opts = {}) {
    soundAt(this, id, pos, opts)
  },
  listener() {
    return view.rig.target
  },
  hearingK() {
    return Math.max(1, (view.rig?.dist || 24) / 24)
  },
  nearCamera(p) {
    return Math.hypot(p.x - view.rig.target.x, p.z - view.rig.target.z) < view.rig.dist * 0.8 + 6
  },
  noise() {},
  toast(msg, kind) {
    this.game.ui?.toast(msg, kind)
  },
  fenceBetween(a, z) {
    return z.state === 'fence' && !z.path
  },
  onKill(z) {
    S.stats.kills++
    if (S.raid) S.raid.killed++
  },
  onDowned(a) {
    this.game.ui?.toast(`${a.data.first} is down!`, 'bad')
  },
  onLabelClick(a) {
    if (S.raid) this.selectDefender(a)
  },

  // ---------------------------------------------------------------- start
  startRaid(R) {
    const sides = ['n', 'e', 'w', 's']
    S.raid = { count: R.count, killed: 0, spawned: 0, t: 0, side: pick(sides), side2: chance(R.size >= 2 || R.blood ? 0.7 : 0.25) ? pick(sides) : null, acc: 0, blood: !!R.blood, lvl: R.lvl, finale: !!R.finale }
    this.mode = 'raid'
    this.cancelPlacing()
    this.cancelLinking()
    this.selectedDef = null
    this.removeWanderers()
    this.squad = []
    this.people.setVisible(false)
    for (const w of this.people.workers) {
      const s = w.s
      if (s.status !== 'ok') continue
      const a = new SurvivorAgent(this, s, w.pos.x, w.pos.z)
      a.leash = 2.4
      a.root.traverse((o) => o.isMesh && (o.userData.pick = { type: 'defender', a }))
      this.squad.push(a)
      const job = S.stations.find((x) => x.id === s.job)
      if (job?.type === 'watchtower' && job.level > 0) {
        const v = this.stationViews.get(job.id)
        const sp = v.spotWorld(workersOf(job).indexOf(s))
        a.tower = job
        a.pos.set(sp.x, sp.y, sp.z)
        a.anchor = { x: sp.x, z: sp.z }
        a.leash = 0
      } else {
        const spot = this.defendSpot(S.raid.side, this.squad.length - 1)
        a.command({ type: 'move', x: spot.x, z: spot.z })
      }
    }
    this.zombies = []
    this.turrets = S.stations.filter((st) => st.type === 'turret' && st.level > 0 && !st.building).map((st) => ({ st, cool: 0, view: this.stationViews.get(st.id) }))
    const b = bounds()
    const f = this.sideCenter(S.raid.side, 4)
    view.rig.follow = null
    view.rig.focus(f.x, f.z, Math.min(view.rig.distGoal, 34))
    sfx('alarm')
    setAmbience(0.06)
    this.game.ui?.raidHud(true)
    log(`A horde of ${R.count} is at the fence!`, 'bad')
  },
  sideCenter(side, inset = 0) {
    const b = bounds()
    const cx = (b.x0 + b.x1 + 1) / 2
    const cz = (b.z0 + b.z1 + 1) / 2
    if (side === 'n') return { x: cx, z: b.z0 + inset }
    if (side === 's') return { x: cx, z: b.z1 + 1 - inset }
    if (side === 'w') return { x: b.x0 + inset, z: cz }
    return { x: b.x1 + 1 - inset, z: cz }
  },
  defendSpot(side, i) {
    const c = this.sideCenter(side, 3)
    const off = ((i % 8) - 3.5) * 2.4
    let p = side === 'n' || side === 's' ? { x: c.x + off, z: c.z } : { x: c.x, z: c.z + off }
    const n = this.grid.nearestOpen(p.x, p.z, 5)
    return n ? { x: n.x + 0.5, z: n.z + 0.5 } : p
  },
  spawnRaidZombie() {
    const R = S.raid
    const side = R.side2 && chance(0.4) ? R.side2 : R.side
    const b = bounds()
    const out = rand(9, 13)
    let x
    let z
    if (side === 'n' || side === 's') {
      x = rand(b.x0 + 2, b.x1 - 2)
      z = side === 'n' ? b.z0 - out : b.z1 + out
    } else {
      z = rand(b.z0 + 2, b.z1 - 2)
      x = side === 'w' ? b.x0 - out : b.x1 + out
    }
    const n = this.grid.nearestOpen(x, z, 5)
    if (n) {
      x = n.x + 0.5
      z = n.z + 0.5
    }
    const lvl = R.lvl || clamp(1 + Math.floor(day() / 4), 1, 5)
    const zz = new ZombieAgent(this, weighted(zombieMix(lvl)).t, x, z, lvl)
    zz.state = 'fence'
    zz.root.traverse((o) => o.isMesh && (o.userData.pick = { type: 'zombie', z: zz }))
    this.zombies.push(zz)
    R.spawned++
  },
  // Outside the wall: walk up to it and batter it down.
  fenceTick(z, dt) {
    const tiles = this.fence.tiles || []
    if (z.fenceIdx == null) {
      let best = -1
      let bd = 1e9
      tiles.forEach(([fx, fz], i) => {
        const d = Math.hypot(fx + 0.5 - z.pos.x, fz + 0.5 - z.pos.z) + rand(0, 3)
        if (d < bd) {
          bd = d
          best = i
        }
      })
      if (best < 0) {
        z.state = 'chase'
        return 'walk'
      }
      z.fenceIdx = best
      const [fx, fz] = tiles[best]
      const b = bounds()
      const ox = fx === b.x0 ? -1 : fx === b.x1 ? 1 : 0
      const oz = fz === b.z0 ? -1 : fz === b.z1 ? 1 : 0
      z.fenceSpot = { x: fx + 0.5 + ox * 0.95, z: fz + 0.5 + oz * 0.95 }
      z.moveTo(z.fenceSpot.x, z.fenceSpot.z)
    }
    const i = z.fenceIdx
    if ((S.fence.hp[i] ?? 0) <= 0) {
      if (z.wanderer) {
        z.fenceIdx = null
        return 'walk'
      }
      // breach: go for the defenders
      z.state = 'chase'
      z.target = null
      z.perceive()
      if (!z.target) {
        const alive = this.squad.filter((a) => !a.downed && !a.tower)
        if (alive.length) z.target = alive.reduce((a, b) => (z.dist(a) < z.dist(b) ? a : b))
      }
      if (!z.target) z.state = 'idle'
      return 'walk'
    }
    if (z.path) {
      z.step(dt)
      return 'walk'
    }
    const [fx, fz] = tiles[i]
    z.face(fx + 0.5, fz + 0.5, dt)
    if (z.cool <= 0) {
      z.cool = z.def.rate
      z.swing = 1
      const mx = fenceMax()
      const floor = z.wanderer ? mx * 0.25 : 0
      S.fence.hp[i] = Math.max(floor, (S.fence.hp[i] ?? mx) - z.dmg)
      if (chance(0.35)) this.fx.burst(new THREE.Vector3(fx + 0.5, 1, fz + 0.5), S.fence.level >= 2 ? '#8a8e92' : '#8a6a48', 3, 1.5, 0.5, 2)
      this.sound('hit', z.pos, { throttle: 120 })
      if (S.fence.hp[i] <= 0) {
        this.fence.refresh()
        sfx('dismantle')
        this.fx.burst(new THREE.Vector3(fx + 0.5, 1, fz + 0.5), '#8a6a48', 22, 3, 1, 3)
        this.fx.dust(new THREE.Vector3(fx + 0.5, 0.3, fz + 0.5), 8)
        view.rig.shake = 0.6
        this.game.ui?.toast('The wall is breached!', 'bad')
      } else if (S.fence.hp[i] < mx * 0.5 && (S.fence.hp[i] + z.dmg) >= mx * 0.5) this.fence.refresh()
    }
    return 'attack'
  },

  // ---------------------------------------------------------------- input during a raid
  selectDefender(a) {
    for (const s of this.squad) s.select(false)
    this.selectedDef = a && !a.downed ? a : null
    if (this.selectedDef) this.selectedDef.select(true)
    this.game.ui?.raidHud(true)
  },
  onRaidTap(x, y, e) {
    const hit = pickAt(x, y, [...this.squad.map((a) => a.root), ...this.zombies.filter((z) => !z.dead).map((z) => z.root)])
    const pk = hit?.pick
    const command = e.button === 2 || !!this.selectedDef
    if (pk?.type === 'defender' && e.button !== 2) {
      const a = pk.a
      if (a.downed && this.selectedDef && this.selectedDef !== a) this.selectedDef.command({ type: 'revive', a })
      else this.selectDefender(a)
      sfx('select')
      return
    }
    if (!this.selectedDef) return
    if (pk?.type === 'zombie' && command) {
      this.selectedDef.command({ type: 'attack', z: pk.z })
      sfx('move')
      return
    }
    const p = groundAt(x, y)
    if (p && !this.selectedDef.tower && (e.button === 2 || e.button === 0)) {
      this.selectedDef.command({ type: 'move', x: p.x, z: p.z })
      this.fx.ring(p)
      sfx('move')
    }
  },
  onRaidHover(x, y) {
    const hit = pickAt(x, y, [...this.squad.map((a) => a.root), ...this.zombies.filter((z) => !z.dead).map((z) => z.root)])
    document.body.style.cursor = hit?.pick?.type === 'zombie' && this.selectedDef ? 'crosshair' : hit ? 'pointer' : ''
  },
  onRaidKey(e) {
    const n = parseInt(e.key, 10)
    if (n >= 1 && n <= this.squad.length) this.selectDefender(this.squad[n - 1])
    if (e.key === 'Escape') this.selectDefender(null)
  },

  // ---------------------------------------------------------------- tick
  updateRaid(dt) {
    const R = S.raid
    R.t += dt
    const rate = R.count / 42
    R.acc += dt * rate
    while (R.acc >= 1 && R.spawned < R.count) {
      R.acc -= 1
      this.spawnRaidZombie()
    }
    for (const a of this.squad) {
      if (a.tower && !a.downed) {
        const v = this.stationViews.get(a.tower.id)
        a.pos.y = v?.info.platformY || 3
      }
      a.update(dt)
    }
    for (const z of this.zombies) z.update(dt)
    const ground = [...this.squad.filter((a) => !a.tower), ...this.zombies.filter((z) => !z.dead)]
    for (const a of ground) a.separate(dt, ground)
    this.updateTurrets(dt, this.zombies)
    this.zombies = this.zombies.filter((z) => {
      if (z.dead && z.deadT > 7) {
        z.remove()
        return false
      }
      return true
    })
    const done = R.spawned >= R.count && this.zombies.every((z) => z.dead)
    const lost = this.squad.length === 0 || this.squad.every((a) => a.downed)
    if (done || (lost && R.spawned >= R.count && R.t > 20) || (lost && R.t > 75)) this.endRaid(!lost)
  },
  updateTurrets(dt, targets) {
    const pinfo = power()
    for (const t of this.turrets || []) {
      const v = t.view
      if (!v?.pivots?.head) continue
      const head = v.pivots.head
      if (!pinfo.powered.has(t.st.id) || S.res.pammo <= 0) {
        head.rotation.y += dt * 0.2
        continue
      }
      t.cool -= dt
      const D = STATIONS.turret
      const range = D.range[t.st.level - 1]
      let best = null
      let bd = range
      for (const z of targets) {
        if (z.dead) continue
        const d = Math.hypot(z.pos.x - v.cx, z.pos.z - v.cz)
        if (d < bd) {
          bd = d
          best = z
        }
      }
      if (!best) {
        head.rotation.y += dt * 0.6
        continue
      }
      const want = Math.atan2(best.pos.x - v.cx, best.pos.z - v.cz) - v.rotY
      head.rotation.y = angleLerp(head.rotation.y, want, 1 - Math.exp(-dt * 10))
      if (t.cool <= 0) {
        t.cool = D.rate[t.st.level - 1]
        S.res.pammo = Math.max(0, S.res.pammo - 1)
        const mz = v.info.muzzle || { z: 0.8, y: 0.12 }
        const from = head.localToWorld(_v.set(0, mz.y, mz.z)).clone()
        this.fx.muzzle(from)
        this.fx.tracer(from, best.chestPos(1.1), '#ffd080')
        if (this.nearCamera(from)) sfx('smg', 70)
        if (chance(0.75)) best.hurt(D.dmg[t.st.level - 1] * rand(0.85, 1.15), null)
      }
    }
  },
  endRaid(won) {
    const R = S.raid
    const report = { count: R.count, killed: R.killed, injured: [], dead: [], lost: {}, won }
    for (const a of this.squad) {
      const s = a.data
      deed(s, 'raids')
      if (a.downed) {
        s.status = 'injured'
        s.hp = Math.max(1, a.maxHp * 0.1)
        report.injured.push(s.first)
      } else {
        s.hp = Math.max(1, a.hp)
        if (a.hp < a.maxHp * 0.3) {
          s.status = 'injured'
          report.injured.push(s.first)
        }
      }
      const w = this.people.list.get(s.id)
      if (w) {
        w.pos.set(a.pos.x, 0, a.pos.z)
        w.y = 0
        w.goal = null
        w.think = 0
      }
      a.remove()
    }
    if (!won) {
      S.lastBreach = S.time
      for (const k of ['food', 'water', 'meds', 'fuel']) {
        const l = Math.floor(S.res[k] * rand(0.15, 0.35))
        S.res[k] -= l
        report.lost[k] = l
      }
      const victims = this.squad.filter((a) => a.downed)
      if (victims.length && victims.length === this.squad.length) {
        // overrun: nobody was left standing, and the horde was in among them
        const dead = victims.filter(() => chance(0.45))
        if (!dead.length) dead.push(pick(victims))
        for (const v of dead) {
          killSurvivor(v.data, 'Killed when the horde overran the camp')
          report.dead.push(v.data.first)
        }
      } else if (victims.length && chance(0.6)) {
        const v = pick(victims)
        killSurvivor(v.data, 'Killed when the horde overran the camp')
        report.dead.push(v.data.first)
      }
      addMoraleEvent('The horde broke through', -18, 2)
      S.stats.raidsLost = (S.stats.raidsLost || 0) + 1
    } else addMoraleEvent('Held the wall', 8, 1)
    for (const z of this.zombies) z.remove()
    this.zombies = []
    this.squad = []
    this.selectedDef = null
    S.raid = null
    this.mode = 'base'
    S.stats.raids++
    completeGoal('surviveHorde')
    if (R.finale && NET.role !== 'client') S.finale = null
    // a guest who fought for the camp reports back; the host plans the next one
    const net = this.game.net
    if (NET.role !== 'client') scheduleRaid()
    setAmbience(0.035)
    this.people.setVisible(true)
    this.people.sync()
    this.fence.refresh()
    this.game.ui?.raidHud(false)
    log(won ? `The horde is dead. ${report.killed} zombies put down.` : 'The horde broke through and ransacked the camp.', won ? 'good' : 'bad')
    if (net?.captain) net.captainDone(report, R.finale)
    if (R.finale && NET.role !== 'client') return finishGame(won)
    // the camp fell with them: the ending has the floor
    if (S.over) return
    this.game.ui?.raidReport(report)
  },

  // ---------------------------------------------------------------- night wanderers
  removeWanderers() {
    for (const z of this.wanderers || []) z.remove()
    this.wanderers = []
  },
  updateWanderers(dt, night) {
    this.wanderers ||= []
    this.squad = []
    this.wanderT = (this.wanderT ?? rand(20, 50)) - dt
    // hens and goats make noise: more of the dead come sniffing round
    const pens = S.stations.filter((st) => STATIONS[st.type].livestock && st.level > 0 && (st.flock ?? 1) > 0)
    if (night > 0.6 && this.wanderT <= 0 && this.wanderers.length < 4 + pens.length) {
      this.wanderT = rand(40, 90) / (1 + 0.5 * pens.length)
      const n = chance(0.3) ? 2 : 1
      for (let i = 0; i < n; i++) this.spawnWanderer(pens.length && chance(0.6) ? pick(pens) : null)
    }
    if (!this.wanderers.length) return
    for (const z of this.wanderers) {
      z.wanderLife = (z.wanderLife ?? 0) + dt
      if (z.wanderLife > 70 && !z.dead && z.state === 'fence') {
        // give up and drift away into the dark
        z.state = 'idle'
        z.fenceIdx = null
        const b = bounds()
        z.moveTo(z.pos.x + (z.pos.x < (b.x0 + b.x1) / 2 ? -12 : 12), z.pos.z + (z.pos.z < (b.z0 + b.z1) / 2 ? -12 : 12))
      }
      z.update(dt)
    }
    this.towerGuards(dt)
    this.updateTurrets(dt, this.wanderers)
    this.wanderers = this.wanderers.filter((z) => {
      if ((z.dead && z.deadT > 7) || (z.wanderLife > 95 && !z.dead)) {
        z.remove()
        return false
      }
      return true
    })
  },
  spawnWanderer(near = null) {
    const b = bounds()
    // drawn by animals: come in on the side nearest them
    let side = pick(['n', 'e', 'w', 's'])
    if (near) {
      const d = { w: near.x - b.x0, e: b.x1 - near.x, n: near.z - b.z0, s: b.z1 - near.z }
      side = Object.keys(d).sort((p, q) => d[p] - d[q])[0]
    }
    const out = rand(10, 16)
    let x = side === 'w' ? b.x0 - out : side === 'e' ? b.x1 + out : rand(b.x0, b.x1)
    let z = side === 'n' ? b.z0 - out : side === 's' ? b.z1 + out : rand(b.z0, b.z1)
    const n = this.grid.nearestOpen(x, z, 6)
    if (!n) return
    const zz = new ZombieAgent(this, chance(0.8) ? 'walker' : 'crawler', n.x + 0.5, n.z + 0.5, clamp(1 + Math.floor(day() / 5), 1, 4))
    zz.state = 'fence'
    zz.wanderer = true
    this.wanderers.push(zz)
  },
  // Watchtower guards pick off wanderers with their own weapons.
  towerGuards(dt) {
    for (const st of S.stations) {
      if (st.type !== 'watchtower' || st.level < 1 || st.building) continue
      const v = this.stationViews.get(st.id)
      if (!v) continue
      for (const s of workersOf(st)) {
        if (s.status !== 'ok') continue
        const w = this.people.list.get(s.id)
        if (!w || w.path) continue
        w.guardCool = (w.guardCool ?? rand(0.5, 1.5)) - dt
        const stt = survivorStats(s)
        const range = stt.gun ? Math.max(14, stt.range * 1.5) : 9
        let best = null
        let bd = range
        for (const z of this.wanderers) {
          if (z.dead) continue
          const d = Math.hypot(z.pos.x - w.pos.x, z.pos.z - w.pos.z)
          if (d < bd) {
            bd = d
            best = z
          }
        }
        if (!best || w.guardCool > 0) continue
        w.guardCool = stt.gun ? Math.max(0.8, stt.rate * 1.6) : 2.2
        w.shootT = 0.7
        w.heading = Math.atan2(best.pos.x - w.pos.x, best.pos.z - w.pos.z)
        const from = new THREE.Vector3(w.pos.x, w.pos.y + 1.4, w.pos.z)
        if (stt.gun && stt.ammoType && S.res[stt.ammoType] <= 0) continue
        if (stt.gun) {
          if (stt.ammoType) S.res[stt.ammoType] -= 1
          this.fx.muzzle(from)
          this.fx.tracer(from, best.chestPos(1.1))
          if (this.nearCamera(from)) sfx(stt.weaponId === 'rifle' ? 'rifle' : 'pistol', 80)
        } else {
          // no gun: throw stones from the tower
          this.fx.tracer(from, best.chestPos(1.1), '#a89a80')
        }
        const dmg = (stt.gun ? stt.dmg : 10) * (1 + STATIONS.watchtower.towerDmg[st.level - 1])
        if (chance(stt.gun ? 0.7 : 0.5)) best.hurt(dmg, null)
      }
    }
  },
}
