// Survivors living in the camp: walking to their stations and working with
// the right tool, helping on building sites and expansions, chatting, carrying
// supplies, sitting by the fire, sleeping in the bunks, lying in the infirmary.
import * as THREE from 'three'
import { makeSurvivorCharacter, survivorLookKey } from '../world/agents.js'
import { toolModel, weaponModel, holdStyle } from '../models/weapons.js'
import { S, hour, stationSize, workersOf, survivorStats, gateTiles, bounds } from '../game/state.js'
import { STATIONS, OCCUPATIONS, ITEMS, EXPANSIONS, RES } from '../game/data.js'
import { isDepot, isNode, feeds, belted } from '../game/belts.js'
import { plannedRecipe } from '../game/rates.js'
const _wood = new THREE.Color('#b08a5a')
import { view } from '../render/view.js'
import { h, rand, chance, angleLerp, clamp, pick } from '../core/util.js'
import { callName } from '../game/deeds.js'

const TOOL_FOR = { hammer: 'hammer', saw: 'saw', hoe: 'hoe', chop: 'axe', shovel: 'shovel', stir: 'ladle', pump: null, type: null, search: null, lookout: null, carry: null, swing: 'melee', punch: null, aim: 'gun' }
let crateGeo = null

class Worker {
  constructor(people, s) {
    this.people = people
    this.base = people.base
    this.s = s
    this.lookKey = survivorLookKey(s)
    this.ch = makeSurvivorCharacter(s)
    this.root = this.ch.root
    this.root.traverse((o) => {
      if (o.isMesh) {
        o.castShadow = true
        o.receiveShadow = true
        o.userData.pick = { type: 'person', s }
      }
    })
    this.root.userData.pick = { type: 'person', s }
    this.pos = new THREE.Vector3()
    this.heading = rand(0, Math.PI * 2)
    this.path = null
    this.pi = 0
    this.goal = null
    this.think = 0
    this.anim = 'idle'
    this.tool = null
    this.y = 0
    this.lying = false
    // a crate carried around camp
    if (!crateGeo) crateGeo = new THREE.BoxGeometry(0.42, 0.32, 0.36)
    this.crate = new THREE.Mesh(crateGeo, new THREE.MeshStandardMaterial({ color: '#b08a5a', roughness: 0.85 }))
    this.crate.position.set(0, 1.05, 0.34)
    this.crate.castShadow = true
    this.crate.visible = false
    this.root.add(this.crate)
    const el = h('div.alabel.camp', h('span.nm', callName(s)), h('span.job'))
    this.label = view.labels.add(el, () => this.labelPos(), { offsetY: 0, scene: this.base.scene, maxDist: 75 })
    this.jobEl = el.querySelector('.job')
    this.nmEl = el.querySelector('.nm')
    el.addEventListener('click', (e) => {
      e.stopPropagation()
      this.base.game.ui?.openSurvivor(s.id)
    })
    this.ring = new THREE.Mesh(new THREE.RingGeometry(0.4, 0.5, 28), new THREE.MeshBasicMaterial({ color: new THREE.Color('#f0c060').multiplyScalar(1.4), transparent: true, opacity: 0.85, depthWrite: false }))
    this.ring.rotation.x = -Math.PI / 2
    this.ring.position.y = 0.04
    this.ring.visible = false
    this.root.add(this.ring)
    this.base.scene.add(this.root)
  }
  labelPos() {
    return { x: this.pos.x, y: this.pos.y + (this.lying ? 0.9 : this.anim === 'sit' || this.anim === 'sitwork' ? 1.75 : this.anim === 'pedal' ? 1.95 : 2.15), z: this.pos.z }
  }
  setTool(kind) {
    if (this.toolKind === kind) return
    this.toolKind = kind
    if (!kind) return this.ch.setWeapon(null)
    if (kind === 'gun' || kind === 'melee') {
      const st = survivorStats(this.s)
      const id = st.weaponId
      if (id === 'fists' || (kind === 'gun' && !st.gun) || (kind === 'melee' && st.gun)) return this.ch.setWeapon(null)
      return this.ch.setWeapon(weaponModel(id, st.weaponItem?.mods || []), holdStyle(id, ITEMS[id].kind))
    }
    this.ch.setWeapon(toolModel(kind), 'melee')
  }
  go(goal) {
    this.goal = goal
    this.leaveBed()
    const g = this.base.grid
    let p = g.path(this.pos.x, this.pos.z, goal.ax ?? goal.x, goal.az ?? goal.z)
    if (p) {
      p = p.slice()
      if (goal.inside || goal.ax != null) p.push({ x: goal.x, z: goal.z })
    } else p = [{ x: goal.x, z: goal.z }]
    this.path = p
    this.pi = 0
    this.arrived = false
  }
  leaveBed() {
    if (this.lying) {
      this.lying = false
      this.ch.mesh.rotation.set(0, 0, 0)
      this.ch.mesh.position.set(0, 0, 0)
    }
    if (this.y) this.y = 0
  }
  update(dt) {
    const s = this.s
    if (!this.path) this.think -= dt
    let anim = 'idle'
    let speed = 0
    if (this.path) {
      const wp = this.path[this.pi]
      const dx = wp.x - this.pos.x
      const dz = wp.z - this.pos.z
      const d = Math.hypot(dx, dz)
      speed = s.status === 'injured' ? 1.05 : this.goal?.hurry ? 3.2 : 1.9
      const step = speed * dt
      if (d <= step) {
        this.pos.x = wp.x
        this.pos.z = wp.z
        this.pi++
        if (this.pi >= this.path.length) {
          this.path = null
          this.arrived = true
          this.arrive()
        }
      } else {
        this.pos.x += (dx / d) * step
        this.pos.z += (dz / d) * step
        this.heading = angleLerp(this.heading, Math.atan2(dx, dz), 1 - Math.exp(-dt * 9))
      }
      anim = this.goal?.carry ? 'carry' : speed > 3 ? 'run' : 'walk'
      if (s.status === 'injured') anim = 'walk'
    } else if (this.goal) {
      const G = this.goal
      if (G.face != null) this.heading = angleLerp(this.heading, G.face, 1 - Math.exp(-dt * 5))
      else if (G.lookAt) this.heading = angleLerp(this.heading, Math.atan2(G.lookAt.x - this.pos.x, G.lookAt.z - this.pos.z), 1 - Math.exp(-dt * 5))
      anim = G.anim || 'idle'
      if (G.st && G.kind === 'work' && !G.st.active) anim = G.sit ? 'sit' : G.anim === 'lookout' ? 'lookout' : 'idle'
      if (G.sit && anim !== 'sit') anim = G.anim === 'pedal' ? 'pedal' : 'sitwork'
    }
    if (this.shootT > 0) {
      this.shootT -= dt
      anim = 'aim'
    }
    // the tower climb: rise to the platform once at the ladder
    const targetY = !this.path && this.goal?.y ? this.goal.y : 0
    this.y += (targetY - this.y) * Math.min(1, dt * (targetY > this.y ? 1.4 : 6))
    if (Math.abs(targetY - this.y) < 0.01) this.y = targetY
    this.pos.y = this.y
    this.anim = anim
    const toolAnim = this.path ? null : anim
    const tk = toolAnim ? TOOL_FOR[toolAnim] ?? null : null
    this.setTool(this.shootT > 0 ? 'gun' : this.goal?.tool ?? tk)
    this.crate.visible = anim === 'carry'
    if (this.crate.visible && this.goal?.res !== this.crateRes) {
      this.crateRes = this.goal?.res
      this.crate.material.color.set(this.crateRes ? RES[this.crateRes].color : '#b08a5a').lerp(_wood, 0.35)
    }
    this.ch.update(dt, anim === 'run' || anim === 'walk' || anim === 'carry' ? anim : anim, { speed: this.path ? speed : 0 })
    if (this.lying) this.ch.update(0, 'lie', {})
    this.root.position.set(this.pos.x, this.pos.y, this.pos.z)
    this.root.rotation.y = this.heading
    // label text
    const jt = s.status === 'injured' ? 'Injured' : this.goal?.label || ''
    if (this.jobEl.textContent !== jt) this.jobEl.textContent = jt
    this.label.el.classList.toggle('hurt', s.status === 'injured')
    this.ring.visible = this.base.selectedPerson === s.id
    if (this.ring.visible) this.ring.material.opacity = 0.6 + Math.sin(performance.now() / 180) * 0.25
  }
  arrive() {
    const G = this.goal
    if (!G) return
    if (G.lie) {
      this.lying = true
      this.ch.mesh.rotation.set(-Math.PI / 2, 0, 0)
      this.ch.mesh.position.set(0, 0.12, 0.88)
      this.y = G.y || 0
      this.heading = G.face ?? this.heading
      this.ch.update(0.5, 'lie', { snap: true })
    }
  }
  dispose() {
    this.base.scene.remove(this.root)
    this.label.remove()
    this.ch.dispose()
  }
}

export class CampPeople {
  constructor(base) {
    this.base = base
    this.list = new Map()
    this.claims = new Map() // spot key -> survivor id (seats, beds, chat points)
    this.seeded = false
  }
  get workers() {
    return [...this.list.values()]
  }
  sync() {
    const want = new Set(S.survivors.filter((s) => s.status === 'ok' || s.status === 'injured').map((s) => s.id))
    for (const [id, w] of this.list) {
      if (!want.has(id) || w.s !== S.survivors.find((x) => x.id === id)) {
        w.dispose()
        this.list.delete(id)
      }
    }
    for (const s of S.survivors) {
      if (!want.has(s.id)) continue
      let w = this.list.get(s.id)
      // a new name shows over their head
      if (w?.nmEl && w.nmEl.textContent !== callName(s)) w.nmEl.textContent = callName(s)
      if (w && w.lookKey !== survivorLookKey(s)) {
        const old = w
        w = new Worker(this, s)
        w.pos.copy(old.pos)
        w.heading = old.heading
        old.dispose()
        this.list.set(s.id, w)
        w.think = 0
        continue
      }
      if (!w) {
        w = new Worker(this, s)
        const fire = S.stations.find((x) => x.type === 'campfire')
        if (!this.seeded && fire) w.pos.set(fire.x + 2 + rand(-3, 3), 0, fire.z + 2 + rand(2, 4))
        else {
          const g = gateTiles()
          w.pos.set(g[1] + 0.5 + rand(-1, 1), 0, bounds().z1 + 2.5)
        }
        this.list.set(s.id, w)
      }
      w.think = Math.min(w.think, 0.2)
    }
    this.seeded = true
  }
  setVisible(v) {
    for (const w of this.list.values()) {
      w.root.visible = v
      w.label.hidden = !v
    }
  }
  claim(key, id) {
    const c = this.claims.get(key)
    if (c && c !== id && this.list.has(c)) return false
    this.claims.set(key, id)
    return true
  }
  release(id) {
    for (const [k, v] of this.claims) if (v === id) this.claims.delete(k)
  }
  // Decide what a survivor should be doing right now.
  goalFor(w) {
    const s = w.s
    const B = this.base
    const hr = hour()
    const night = hr >= 21.5 || hr < 5.5
    const evening = hr >= 19 && hr < 21.5
    this.release(s.id)
    if (s.status === 'injured') {
      const inf = S.stations.find((st) => st.type === 'infirmary' && st.patients?.includes(s.id) && B.stationViews.get(st.id))
      if (inf) {
        const v = B.stationViews.get(inf.id)
        const bed = v.bedWorld(inf.patients.indexOf(s.id))
        if (bed) return { ...bed, lie: true, injured: true, label: 'In the infirmary', inside: true }
      }
      const bed = this.freeBed(s.id)
      if (bed) return { ...bed, lie: true, injured: true, label: 'Resting', inside: true }
      return { ...(this.fireSeat(s.id, 'Resting') || this.wanderNearFire('Resting')), injured: true, until: 30 }
    }
    if (s.job) {
      const st = S.stations.find((x) => x.id === s.job)
      const v = st && B.stationViews.get(st.id)
      if (st && st.level > 0 && v) {
        const idx = workersOf(st).indexOf(s)
        const sp = v.spotWorld(idx)
        if (sp) {
          const name = STATIONS[st.type].name
          return { ...sp, kind: 'work', st, inside: true, label: name, tool: sp.tool === 'axe' ? 'axe' : undefined }
        }
      }
    }
    // free hands: building sites, the wall, expansions
    const site = !night && this.buildSite(s)
    if (site) return site
    if (night) {
      const bed = this.freeBed(s.id)
      if (bed) return { ...bed, lie: true, sleep: true, label: 'Asleep', inside: true }
    }
    if (evening || night) return this.fireSeat(s.id, evening ? '' : 'Keeping watch') || this.wanderNearFire('')
    // daytime idling: chat, carry supplies, sit, wander
    const r = Math.random()
    if (r < 0.25) {
      const c = this.carryJob(s)
      if (c) return c
    }
    if (r < 0.5) {
      const seat = this.anySeat(s.id)
      if (seat) return seat
    }
    if (r < 0.75) return this.chatSpot(w)
    return this.wanderNearFire('')
  }
  freeBed(id) {
    let i = 0
    for (const st of S.stations) {
      if (st.type !== 'bunkhouse' || st.level < 1 || st.building) continue
      const v = this.base.stationViews.get(st.id)
      if (!v) continue
      const beds = v.info.beds || []
      for (let k = 0; k < beds.length; k++) {
        const key = `bed:${st.id}:${k}`
        if (this.claim(key, id)) return v.bedWorld(k)
      }
      i++
    }
    return null
  }
  fireSeat(id, label) {
    const fire = S.stations.find((st) => st.type === 'campfire')
    const v = fire && this.base.stationViews.get(fire.id)
    if (!v) return null
    const seats = v.info.seats || []
    const order = seats.map((_, i) => i).sort(() => Math.random() - 0.5)
    for (const k of order) if (this.claim(`seat:${fire.id}:${k}`, id)) return { ...v.seatWorld(k), anim: 'sit', label, inside: true }
    return null
  }
  anySeat(id) {
    const views = [...this.base.stationViews.values()].filter((v) => v.info.seats?.length && v.st.level > 0 && !v.st.building)
    if (!views.length) return null
    const v = pick(views)
    const k = Math.floor(Math.random() * v.info.seats.length)
    if (!this.claim(`seat:${v.st.id}:${k}`, id)) return null
    return { ...v.seatWorld(k), anim: 'sit', label: '', inside: true, until: rand(25, 50) }
  }
  wanderNearFire(label) {
    const fire = S.stations.find((st) => st.type === 'campfire')
    const fx = fire ? fire.x + 2 : 56
    const fz = fire ? fire.z + 2 : 56
    const g = this.base.grid
    for (let t = 0; t < 8; t++) {
      const a = rand(0, Math.PI * 2)
      const r = rand(3, 9)
      const n = g.nearestOpen(fx + Math.cos(a) * r, fz + Math.sin(a) * r, 2)
      if (n) return { x: n.x + 0.5, z: n.z + 0.5, anim: 'idle', lookAt: { x: fx, z: fz }, label, until: rand(8, 18) }
    }
    return { x: fx + 3, z: fz + 3, anim: 'idle', label, until: 10 }
  }
  chatSpot(w) {
    // meet another idle survivor and talk
    const others = this.workers.filter((o) => o !== w && !o.s.job && o.s.status === 'ok' && o.goal?.kind === 'chat-wait')
    if (others.length) {
      const o = pick(others)
      const a = rand(0, Math.PI * 2)
      const p = { x: o.pos.x + Math.cos(a) * 1.1, z: o.pos.z + Math.sin(a) * 1.1 }
      o.goal = { ...o.goal, anim: 'chat', lookAt: p, kind: 'chat', until: 14 }
      o.think = 14
      return { x: p.x, z: p.z, anim: 'chat', lookAt: { x: o.pos.x, z: o.pos.z }, kind: 'chat', label: '', until: 14 }
    }
    const g = this.wanderNearFire('')
    return { ...g, kind: 'chat-wait', until: rand(10, 16) }
  }
  // Something that really goes by hand: an input a station draws from
  // storage with no belt bringing it, or what it makes with no belt taking
  // it away. The crate is the colour of the goods.
  carryJob(s) {
    const stor = S.stations.filter((st) => isDepot(st) && st.level > 0)
    if (!stor.length) return null
    const jobs = []
    for (const st of S.stations) {
      if (st.level < 1 || st.building || isDepot(st) || isNode(st)) continue
      const R = plannedRecipe(st)
      if (!R) continue
      for (const k of Object.keys(R.in || {})) if (R.in[k] > 0 && !feeds(st, k)) jobs.push({ st, k, dir: 'in' })
      for (const k of Object.keys(R.out || {})) if (!belted(st, k)) jobs.push({ st, k, dir: 'out' })
    }
    if (!jobs.length) return null
    const j = pick(jobs)
    const near = stor.sort((p, q) => Math.hypot(p.x - j.st.x, p.z - j.st.z) - Math.hypot(q.x - j.st.x, q.z - j.st.z))[0]
    const vs = this.base.stationViews.get(near.id)
    const vt = this.base.stationViews.get(j.st.id)
    if (!vs || !vt) return null
    const [pa, pb] = j.dir === 'in' ? [vs.frontWorld(), vt.frontWorld()] : [vt.frontWorld(), vs.frontWorld()]
    const what = RES[j.k].name.toLowerCase()
    const label = j.dir === 'in' ? `Carrying ${what} to the ${STATIONS[j.st.type].name}` : `Carrying ${what} to storage`
    return { x: pa.x, z: pa.z, anim: 'search', label, kind: 'carry1', res: j.k, next: { x: pb.x, z: pb.z, anim: 'idle', carry: true, res: j.k, label, until: 3 }, until: 2.5 }
  }
  buildSite(s) {
    const B = this.base
    const sites = []
    for (const st of S.stations) if (st.building) sites.push({ st })
    if (S.expanding) sites.push({ exp: S.expanding.id })
    if (S.fence.building) sites.push({ fence: true })
    if (!sites.length) return null
    const site = sites[Math.abs(hashId(s.id)) % sites.length]
    if (site.st) {
      const st = site.st
      const [w, d] = stationSize(st)
      const a = rand(0, Math.PI * 2)
      const cx = st.x + w / 2
      const cz = st.z + d / 2
      const R = Math.max(w, d) / 2 + 0.7
      const x = cx + Math.cos(a) * R
      const z = cz + Math.sin(a) * R
      return { x, z, anim: pick(['hammer', 'hammer', 'saw', 'carry']) === 'carry' ? 'hammer' : pick(['hammer', 'saw']), lookAt: { x: cx, z: cz }, label: `Building ${STATIONS[st.type].name}`, until: rand(10, 20) }
    }
    if (site.exp) {
      const r = B.world.expansionLand(site.exp)
      const X = EXPANSIONS.find((e) => e.id === site.exp)
      const x = rand(r.x0 + 1, r.x1 - 1)
      const z = rand(r.z0 + 1, r.z1 - 1)
      const anim = X.terrain === 'woods' || X.terrain === 'orchard' ? 'chop' : X.terrain === 'meadow' ? 'shovel' : 'hammer'
      return { x, z, ax: x, az: z, anim, tool: anim === 'chop' ? 'axe' : anim === 'shovel' ? 'shovel' : 'hammer', lookAt: { x: x + rand(-1, 1), z: z + rand(-1, 1) }, label: `Clearing ${X.name}`, until: rand(12, 22), inside: true }
    }
    // the wall: hammer along the perimeter
    const b = bounds()
    const side = Math.floor(Math.random() * 4)
    const t = Math.random()
    const x = side === 0 || side === 2 ? b.x0 + 2 + t * (b.x1 - b.x0 - 4) : side === 1 ? b.x1 - 1.2 : b.x0 + 1.8
    const z = side === 1 || side === 3 ? b.z0 + 2 + t * (b.z1 - b.z0 - 4) : side === 0 ? b.z0 + 1.8 : b.z1 - 1.2
    const lx = side === 1 ? b.x1 + 1 : side === 3 ? b.x0 : x
    const lz = side === 0 ? b.z0 : side === 2 ? b.z1 + 1 : z
    return { x, z, anim: 'hammer', lookAt: { x: lx, z: lz }, label: 'Raising the wall', until: rand(12, 20) }
  }
  update(dt, simDt) {
    const hr = hour()
    const nightNow = hr >= 21.5 || hr < 5.5
    for (const w of this.list.values()) {
      const G = w.goal
      const s = w.s
      let need = !G || w.think <= 0
      if (G) {
        if (G.st && (s.job !== G.st.id || G.st.level < 1 || !this.base.stationViews.has(G.st.id))) need = true
        if (!G.st && s.job && !G.injured) need = true
        if (!!G.injured !== (s.status === 'injured')) need = true
        if (G.sleep && !nightNow) need = true
      }
      if (need) {
        if (G?.next && w.arrived && w.think <= 0) {
          w.go(G.next)
          w.think = G.next.until ?? 4
        } else {
          const ng = this.goalFor(w)
          const same = G && Math.abs((G.x ?? 0) - ng.x) < 0.05 && Math.abs((G.z ?? 0) - ng.z) < 0.05 && G.st === ng.st && !!G.lie === !!ng.lie
          if (!same) w.go(ng)
          else w.goal = { ...ng }
          w.think = ng.until ?? 5
        }
      }
      w.update(simDt)
    }
  }
}
function hashId(id) {
  let h = 0
  for (let i = 0; i < id.length; i++) h = (h * 31 + id.charCodeAt(i)) | 0
  return h
}
