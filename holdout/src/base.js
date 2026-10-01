// The camp: a fenced compound seen at 45°. Survivors walk to their jobs,
// stations animate, construction happens, and hordes hit the fence.
import * as THREE from 'three'
import { Grid, BLOCK } from './grid.js'
import { gfx, Atmosphere, FX, M, G, tex, pickAt, groundAt, basicMat, isNight, nightFactor } from './gfx.js'
import { makeStation, makeScaffold, animateStation, makeHuman, setWeapon, animate, makeTree, makeRock, makeCar, makeLamp, bake, part, makeSandbags, makeBarrel } from './models.js'
import { SurvivorAgent, ZombieAgent, lookFor } from './agents.js'
import { STATIONS, FENCE, zombieMix, ZOMBIES, RES } from './data.js'
import {
  S, BASE, FENCE_TILES, isGate, hour, day, stationSize, workersOf, survivorStats, power, powerInfo, fenceMax, gainXP,
  completeGoal, log, scheduleRaid, killSurvivor, getS,
} from './state.js'
import { sfx, setAmbience } from './audio.js'
import { bus, h, rand, rint, pick, chance, weighted, clamp, angleLerp } from './util.js'

const tmp = new THREE.Vector3()

export class Base {
  constructor(game) {
    this.game = game
    this.scene = new THREE.Scene()
    this.atmo = new Atmosphere(this.scene)
    this.fx = new FX(this.scene)
    this.labels = gfx.labels
    this.grid = new Grid(BASE.W, BASE.H)
    this.stationViews = new Map() // station id -> { group, scaffold, label }
    this.people = new Map() // survivor id -> Walker
    this.squad = [] // defenders during a horde (SurvivorAgents)
    this.zombies = []
    this.placing = null
    this.selectedStation = null
    this.t = 0
    this.buildTerrain()
    this.buildFence()
    this.syncStations()
    this.syncPeople()
    this.cursor = this.makeCursor()
    this.offs = []
    const on = (ev, fn) => this.offs.push(bus.on(ev, fn))
    on('stations', () => this.syncStations())
    on('built', (st) => {
      this.syncStations()
      const v = this.stationViews.get(st.id)
      if (v && this.active) {
        this.fx.burst(v.group.position.clone().setY(1), '#c8b88a', 20, 3, 1, 3)
        sfx('complete')
      }
    })
    on('fence', () => this.refreshFence())
    on('change', () => this.syncPeople())
    on('recruitJoined', () => this.syncPeople())
    on('produced', (st, out) => this.showProduce(st, out))
  }
  dispose() {
    for (const off of this.offs) off()
    this.labels.clearScene(this.scene)
  }
  get active() {
    return this.game.scene === this
  }
  get mode() {
    return S.raid ? 'raid' : 'base'
  }
  enter() {
    gfx.rig.setBounds(2, 2, BASE.W - 2, BASE.H - 2)
    gfx.rig.minDist = 9
    gfx.rig.maxDist = 62
    if (!this.entered) gfx.rig.jump(24, 26, 34)
    this.entered = true
    this.syncStations()
    this.syncPeople()
    this.refreshFence()
  }

  // ---------------------------------------------------------------- world
  buildTerrain() {
    const scene = this.scene
    const statics = new THREE.Group()
    const outer = new THREE.Mesh(new THREE.PlaneGeometry(260, 260), new THREE.MeshStandardMaterial({ map: this.repeatTex('grass', 260 / 9), roughness: 1 }))
    outer.rotation.x = -Math.PI / 2
    outer.position.set(24, -0.01, 24)
    outer.receiveShadow = true
    scene.add(outer)
    const { F0, F1 } = BASE
    const inner = new THREE.Mesh(new THREE.PlaneGeometry(F1 - F0 + 1, F1 - F0 + 1), new THREE.MeshStandardMaterial({ map: this.repeatTex('dirt', (F1 - F0) / 6), roughness: 1 }))
    inner.rotation.x = -Math.PI / 2
    inner.position.set((F0 + F1 + 1) / 2, 0.005, (F0 + F1 + 1) / 2)
    inner.receiveShadow = true
    scene.add(inner)
    // road leading out of the gate
    const road = new THREE.Mesh(new THREE.PlaneGeometry(4, 60), new THREE.MeshStandardMaterial({ map: this.repeatTex('asphalt', 1, 60 / 4), roughness: 0.95 }))
    road.rotation.x = -Math.PI / 2
    road.position.set(24.5, 0.012, F1 + 31)
    road.receiveShadow = true
    scene.add(road)
    const cross = new THREE.Mesh(new THREE.PlaneGeometry(200, 4), new THREE.MeshStandardMaterial({ map: this.repeatTex('asphalt', 50, 1), roughness: 0.95 }))
    cross.rotation.x = -Math.PI / 2
    cross.position.set(24, 0.011, F1 + 14)
    cross.receiveShadow = true
    scene.add(cross)
    for (let x = -60; x < 110; x += 3) part(statics, G('box', 1.4, 0.01, 0.12), M('#d8b840'), x, 0.02, F1 + 14, null, null, false)
    // trees, rocks, wrecks outside the wall
    const rngPlace = (n, fn) => {
      for (let i = 0; i < n; i++) {
        let x, z
        do {
          x = rand(-30, 78)
          z = rand(-30, 78)
        } while ((x > F0 - 3 && x < F1 + 4 && z > F0 - 3 && z < F1 + 4) || (Math.abs(x - 24.5) < 4 && z > F1) || Math.abs(z - (F1 + 14)) < 3)
        fn(x, z)
      }
    }
    rngPlace(70, (x, z) => {
      const t = makeTree(chance(0.35), rand(0.8, 1.35))
      t.position.set(x, 0, z)
      t.rotation.y = rand(0, 6)
      statics.add(t)
    })
    rngPlace(30, (x, z) => {
      const r = makeRock(rand(0.6, 1.4))
      r.position.set(x, 0, z)
      statics.add(r)
    })
    for (const [x, z, r] of [[14, F1 + 13, 0.2], [34, F1 + 15, 1.7], [5, F1 + 6, 0.9], [44, 12, 0.3], [2, 20, 1.2]]) {
      const c = makeCar()
      c.position.set(x, 0, z)
      c.rotation.y = r
      c.rotation.z = rand(-0.05, 0.05)
      statics.add(c)
    }
    // ruined houses in the distance
    for (let i = 0; i < 12; i++) {
      const a = (i / 12) * Math.PI * 2 + rand(-0.2, 0.2)
      const d = rand(44, 60)
      const w = rand(6, 11)
      const hh = rand(3, 8)
      const g = new THREE.Group()
      part(g, G('box', w, hh, rand(6, 9)), M(pick(['#6e675e', '#5e5a55', '#77705f', '#6a5e50'])), 0, hh / 2, 0)
      part(g, G('box', w + 0.4, 0.3, 7), M('#4a4540'), 0, hh + 0.1, 0, [0.1, 0, 0.05])
      g.position.set(24 + Math.cos(a) * d, 0, 24 + Math.sin(a) * d)
      g.rotation.y = -a
      statics.add(g)
    }
    // lumber-yard leftovers inside the camp edge
    const sb = makeSandbags(2)
    sb.position.set(F0 + 2.5, 0, F1 - 1.2)
    statics.add(sb)
    for (const [x, z] of [[F1 - 2, F0 + 2], [F1 - 1.5, F0 + 3], [F0 + 1.6, F0 + 1.6]]) statics.add(makeBarrel(pick(['#4a5a3a', '#6a3a2a']))).position.set(x, 0, z)
    for (let i = 0; i < 5; i++) part(statics, G('cyl', 0.2, 0.2, 3, 8), M('#6a4c30'), F1 - 2.5, 0.2 + (i > 2 ? 0.35 : 0), F1 - 3 + (i % 3) * 0.42, [Math.PI / 2, 0, 0])
    bake(statics)
    scene.add(statics)
    for (const [x, z] of [[F0 + 1, F0 + 1], [F1 - 1, F1 - 1]]) this.grid.fillRect(x - 1, z - 1, 2, 2, BLOCK)
    this.grid.fillRect(F0 + 1, F1 - 2, 3, 1, BLOCK)
    this.grid.fillRect(F1 - 3, F1 - 4, 2, 3, BLOCK)
    // Grass and clutter in the band just inside the fence, where nothing can be built.
    const tuft = M('#6b7442', { flat: true })
    const tuft2 = M('#7d7c4a', { flat: true })
    for (let i = 0; i < 160; i++) {
      const side = i % 4
      const along = rand(F0 + 0.8, F1 + 0.2)
      const inset = rand(1.0, 1.9)
      const x = side === 0 ? along : side === 1 ? F1 + 1 - inset : side === 2 ? along : F0 + inset
      const z = side === 0 ? F0 + inset : side === 1 ? along : side === 2 ? F1 + 1 - inset : along
      if (side === 2 && Math.abs(x - 24.5) < 3) continue
      const c = new THREE.Group()
      for (let k = 0; k < 3; k++) part(c, G('cone', 0.06, rand(0.25, 0.45), 4), k % 2 ? tuft : tuft2, rand(-0.12, 0.12), 0.15, rand(-0.12, 0.12), [rand(-0.3, 0.3), 0, rand(-0.3, 0.3)], null, false)
      c.position.set(x, 0, z)
      statics.add(c)
    }
    for (const [x, z, r] of [[F0 + 1.3, 20, 0.2], [F1 - 0.4, 15, 1.1], [30, F0 + 1.2, 0.5], [17, F1 - 0.3, 2.1]]) {
      part(statics, G('cyl', 0.32, 0.38, 0.4, 9), M('#6a4c30'), x, 0.2, z)
      part(statics, G('cyl', 0.3, 0.3, 0.02, 9), M('#c8a878'), x, 0.41, z, null, null, false)
      part(statics, G('box', 0.55, 0.55, 0.55), M('#a78455', { map: 'crate' }), x + 0.9, 0.275, z + 0.3, [0, r, 0])
    }
    // worn path from the gate to the fire
    const pathTex = tex('strip').clone()
    pathTex.needsUpdate = true
    pathTex.repeat.set(1, (F1 - 25) / 3)
    const path = new THREE.Mesh(new THREE.PlaneGeometry(2.6, F1 - 25), new THREE.MeshStandardMaterial({ color: '#5e503a', map: pathTex, roughness: 1, transparent: true, opacity: 0.7, depthWrite: false }))
    path.rotation.x = -Math.PI / 2
    path.position.set(24.5, 0.009, (F1 + 25) / 2)
    path.receiveShadow = true
    scene.add(path)
    this.lampLight = new THREE.PointLight('#ff9a40', 0, 16, 1.6)
    scene.add(this.lampLight)
  }
  repeatTex(name, rx, ry = rx) {
    const t = tex(name).clone()
    t.needsUpdate = true
    t.repeat.set(rx, ry)
    return t
  }

  buildFence() {
    // Instanced parts per fence level so broken tiles can vanish individually.
    this.fenceGroup = new THREE.Group()
    this.scene.add(this.fenceGroup)
    for (const [x, z] of FENCE_TILES) this.grid.set(x, z, BLOCK, 0, 'fence')
    this.refreshFence()
  }
  refreshFence() {
    const lv = S.fence.level
    if (this.fenceLevelBuilt !== lv) {
      this.fenceLevelBuilt = lv
      this.fenceGroup.clear()
      this.fenceParts = []
      const n = FENCE_TILES.length
      const mk = (geo, mat, shadow = true) => {
        const im = new THREE.InstancedMesh(geo, mat, n)
        im.castShadow = shadow
        im.receiveShadow = true
        this.fenceGroup.add(im)
        this.fenceParts.push(im)
        return im
      }
      const m = new THREE.Matrix4()
      const q = new THREE.Quaternion()
      const sc = new THREE.Vector3(1, 1, 1)
      const p = new THREE.Vector3()
      this.fenceInst = []
      const parts = []
      if (lv === 0) {
        parts.push({ im: mk(G('box', 0.12, 1.5, 0.12), M('#5e4330')), y: 0.75, off: -0.45, along: true })
        parts.push({ im: mk(G('box', 1.0, 0.22, 0.05), M('#8a6a48')), y: 0.4, jitter: true })
        parts.push({ im: mk(G('box', 1.0, 0.22, 0.05), M('#7a5c40')), y: 0.75, jitter: true })
        parts.push({ im: mk(G('box', 1.0, 0.22, 0.05), M('#94704c')), y: 1.1, jitter: true })
      } else if (lv === 1) {
        for (let i = 0; i < 3; i++) parts.push({ im: mk(G('cyl', 0.16, 0.17, 2.1, 7), M(pick(['#6a4c30', '#5e4330', '#735236']))), y: 1.05, lat: -0.33 + i * 0.33 })
        for (let i = 0; i < 3; i++) parts.push({ im: mk(G('cone', 0.16, 0.35, 7), M('#5e4330')), y: 2.27, lat: -0.33 + i * 0.33 })
        parts.push({ im: mk(G('box', 1.0, 0.12, 0.1), M('#4a3525')), y: 1.4, back: 0.18 })
      } else if (lv === 2) {
        const mat = M('#8a8e90', { map: 'corrugated', metal: 0.5, rough: 0.5 })
        parts.push({ im: mk(G('box', 1.02, 2.2, 0.06), mat), y: 1.1 })
        parts.push({ im: mk(G('box', 0.12, 2.4, 0.12), M('#4a4e52', { metal: 0.4 })), y: 1.2, off: -0.5, along: true })
        parts.push({ im: mk(G('box', 1.0, 0.08, 0.14), M('#6a4a38', { metal: 0.3 })), y: 2.2 })
      } else {
        parts.push({ im: mk(G('box', 1.02, 1.6, 0.5), M('#9a968c', { map: 'concrete' })), y: 0.8 })
        parts.push({ im: mk(G('box', 1.02, 0.7, 0.3), M('#8a867c', { map: 'concrete' })), y: 1.95 })
        parts.push({ im: mk(G('torus', 0.2, 0.02, 4, 10), M('#6a6e72', { metal: 0.6 }), false), y: 2.5, rotX: true })
      }
      this.fenceDef = parts
      FENCE_TILES.forEach(([x, z], i) => {
        const horiz = z === BASE.F0 || z === BASE.F1
        for (const pr of parts) {
          const ry = horiz ? 0 : Math.PI / 2
          q.setFromEuler(new THREE.Euler(pr.rotX ? 0 : 0, ry + (pr.rotX ? Math.PI / 2 : 0), pr.jitter ? rand(-0.06, 0.06) : 0))
          let px = x + 0.5
          let pz = z + 0.5
          const lat = pr.lat || 0
          const off = pr.along ? pr.off || 0 : 0
          if (horiz) px += lat + off
          else pz += lat + off
          if (pr.back) {
            if (horiz) pz += z === BASE.F0 ? pr.back : -pr.back
            else px += x === BASE.F0 ? pr.back : -pr.back
          }
          p.set(px, pr.y, pz)
          m.compose(p, q, sc)
          pr.im.setMatrixAt(i, m)
        }
      })
      // gate: two posts and a sign
      if (this.gateGroup) this.scene.remove(this.gateGroup)
      this.gateGroup = new THREE.Group()
      const gp = M('#3e3a36')
      const gh = [1.9, 2.6, 2.8, 2.8][lv]
      part(this.gateGroup, G('box', 0.3, gh + 0.9, 0.3), gp, BASE.GATE[0] - 0.1, (gh + 0.9) / 2, BASE.F1 + 0.5)
      part(this.gateGroup, G('box', 0.3, gh + 0.9, 0.3), gp, BASE.GATE[2] + 1.1, (gh + 0.9) / 2, BASE.F1 + 0.5)
      part(this.gateGroup, G('box', 3.6, 0.5, 0.12), M('#6a5038'), 24.5, gh + 0.7, BASE.F1 + 0.62)
      this.scene.add(this.gateGroup)
      this.fenceMatrices = parts.map((pr) => {
        const arr = []
        for (let i = 0; i < FENCE_TILES.length; i++) {
          const mm = new THREE.Matrix4()
          pr.im.getMatrixAt(i, mm)
          arr.push(mm)
        }
        return arr
      })
    }
    // hide broken tiles, show gate as the same fence but with a darker tint
    const zero = new THREE.Matrix4().makeScale(0, 0, 0)
    FENCE_TILES.forEach(([x, z], i) => {
      const broken = S.fence.hp[i] <= 0
      this.fenceDef.forEach((pr, j) => pr.im.setMatrixAt(i, broken ? zero : this.fenceMatrices[j][i]))
      this.grid.set(x, z, broken ? 0 : BLOCK, 0, broken ? null : 'fence')
    })
    for (const pr of this.fenceDef) pr.im.instanceMatrix.needsUpdate = true
  }

  // ---------------------------------------------------------------- stations
  syncStations() {
    const seen = new Set()
    // clear station tiles and rebuild occupancy
    for (let x = BASE.F0 + 1; x < BASE.F1; x++) for (let z = BASE.F0 + 1; z < BASE.F1; z++) if (typeof this.grid.owner[this.grid.i(x, z)] === 'object' && this.grid.owner[this.grid.i(x, z)]?.type) this.grid.set(x, z, 0, 0, null)
    for (const st of S.stations) {
      seen.add(st.id)
      let v = this.stationViews.get(st.id)
      const lookKey = `${st.level}|${!!st.building}|${st.rot}`
      if (!v || v.key !== lookKey) {
        if (v) this.scene.remove(v.group)
        v = this.makeStationView(st, v)
        v.key = lookKey
        this.stationViews.set(st.id, v)
      }
      const [w, d] = stationSize(st)
      this.grid.fillRect(st.x, st.z, w, d, BLOCK, 0, st)
    }
    for (const [id, v] of this.stationViews) {
      if (!seen.has(id)) {
        this.scene.remove(v.group)
        v.label?.remove()
        this.stationViews.delete(id)
      }
    }
  }
  makeStationView(st, old) {
    const [w, d] = stationSize(st)
    const group = new THREE.Group()
    group.position.set(st.x + w / 2, 0, st.z + d / 2)
    let model = null
    if (st.level >= 1) {
      model = makeStation(st.type, st.level)
      // Merge static parts into a few meshes; animated parts stay separate.
      for (const a of model.userData.anim || []) a.obj.traverse((o) => (o.userData.keep = true))
      bake(model)
      model.rotation.y = st.rot ? Math.PI / 2 : 0
      group.add(model)
    }
    let scaffold = null
    if (st.building) {
      scaffold = makeScaffold(w, d)
      group.add(scaffold)
      if (model) model.scale.setScalar(0.96)
    }
    // footprint pad
    const pad = new THREE.Mesh(G('plane', 1, 1), M('#5a5040', { rough: 1 }))
    pad.scale.set(w - 0.1, d - 0.1, 1)
    pad.rotation.x = -Math.PI / 2
    pad.position.y = 0.008
    pad.receiveShadow = true
    group.add(pad)
    group.traverse((o) => {
      if (o.isMesh) o.userData.pick = { type: 'station', st }
    })
    this.scene.add(group)
    let label = old?.label
    if (!label) {
      const el = h('div.slabel', h('span.sl-name'), h('div.sl-bar', h('i')), h('span.sl-warn'))
      label = this.labels.add(el, new THREE.Vector3(group.position.x, 0, group.position.z), { offsetY: 2.8, scene: this.scene })
    }
    label.pos = new THREE.Vector3(group.position.x, 0, group.position.z)
    label.offsetY = st.type === 'radio' ? 4 : st.type === 'watchtower' ? 4.6 : st.type === 'campfire' ? 1.6 : 2.6
    return { st, group, model, scaffold, label, key: '' }
  }
  showProduce(st, out) {
    if (!this.active) return
    const v = this.stationViews.get(st.id)
    if (!v) return
    const [k, n] = Object.entries(out)[0]
    this.labels.float(this.scene, v.group.position.clone().setY(2.2), `+${n} ${RES[k].name}`, 'res res-' + k)
  }

  // ---------------------------------------------------------------- people
  // Walkers: lightweight survivors who go about their jobs in camp.
  syncPeople() {
    const want = new Set(S.survivors.filter((s) => s.status === 'ok' || s.status === 'injured').map((s) => s.id))
    for (const [id, p] of this.people) {
      if (!want.has(id)) {
        this.scene.remove(p.rig.root)
        p.label.remove()
        this.people.delete(id)
      }
    }
    for (const s of S.survivors) {
      if (!want.has(s.id)) continue
      let p = this.people.get(s.id)
      const lookKey = JSON.stringify(lookFor(s))
      if (!p || p.lookKey !== lookKey) {
        const fresh = !this.peopleSeeded
        const pos = p ? p.pos.clone() : fresh ? new THREE.Vector3(24 + rand(-4, 4), 0, 26 + rand(-2, 3)) : this.gatePos().add(new THREE.Vector3(rand(-1, 1), 0, -1.5))
        if (p) {
          this.scene.remove(p.rig.root)
          p.label.remove()
        }
        const rig = makeHuman(lookFor(s))
        const el = h('div.alabel.camp', h('span.nm', s.first), h('span.job'))
        p = {
          s,
          rig,
          lookKey,
          pos,
          heading: rand(0, 6),
          path: null,
          pi: 0,
          goal: null,
          task: 'idle',
          wait: rand(0, 2),
          label: this.labels.add(el, pos, { offsetY: 2.1, scene: this.scene }),
        }
        p.label.el.addEventListener('click', () => this.game.ui.openSurvivor(s.id))
        rig.root.userData.pick = { type: 'person', s }
        this.scene.add(rig.root)
        this.people.set(s.id, p)
      }
      const st = survivorStats(s)
      setWeapon(p.rig, S.raid ? st.weapon.model : 'none')
    }
    this.peopleSeeded = true
  }
  gatePos() {
    return new THREE.Vector3(24.5, 0, BASE.F1 - 1)
  }
  // Where a survivor stands to work at a station.
  workSpot(st, idx) {
    const [w, d] = stationSize(st)
    const cx = st.x + w / 2
    const cz = st.z + d / 2
    if (st.type === 'watchtower') {
      const v = this.stationViews.get(st.id)
      return { x: cx + (idx ? 0.4 : -0.3), z: cz, y: v?.model?.userData.platformY || 3, face: Math.PI / 4, tower: true }
    }
    if (st.type === 'infirmary' && idx >= 100) {
      // patient beds
      const b = idx - 100
      return { x: cx - 0.9 + b * 0.9, z: cz, y: 0.55, lie: true }
    }
    // front side (+z) spots, spread along the width
    const n = Math.max(1, STATIONS[st.type].workers[Math.max(0, st.level - 1)] || 1)
    const along = n > 1 ? -w / 2 + 0.5 + (idx * (w - 1)) / Math.max(1, n - 1) : 0
    const r = st.rot
    let x = cx + (r ? 0 : along)
    let z = cz + (r ? along : 0)
    if (r) x += w / 2 + 0.45
    else z += d / 2 + 0.45
    if (st.type === 'farm' || st.type === 'training') {
      x = cx + (idx - 1) * 0.9
      z = cz + (idx % 2 ? 0.1 : -0.5)
      return { x, z, face: rand(0, 6), inside: true }
    }
    return { x, z, face: r ? -Math.PI / 2 : Math.PI }
  }
  personGoal(p) {
    const s = p.s
    if (S.raid) return null
    if (s.status === 'injured') {
      const inf = S.stations.find((st) => st.type === 'infirmary' && st.patients?.includes(s.id))
      if (inf) {
        const i = inf.patients.indexOf(s.id)
        const spot = this.workSpot(inf, 100 + i)
        return { ...spot, task: 'patient' }
      }
      const bunk = S.stations.find((st) => st.type === 'bunkhouse' && st.level > 0)
      if (bunk) {
        const [w, d] = stationSize(bunk)
        return { x: bunk.x + w / 2 + rand(-0.8, 0.8), z: bunk.z + d + 0.5, task: 'rest' }
      }
      return { x: 24 + rand(-2, 2), z: 25 + rand(-1, 1), task: 'rest' }
    }
    if (s.job) {
      const st = S.stations.find((x) => x.id === s.job)
      if (st && st.level > 0) {
        const idx = workersOf(st).indexOf(s)
        const spot = this.workSpot(st, idx)
        return { ...spot, task: st.type === 'farm' ? 'farm' : st.type === 'watchtower' ? 'guard' : st.type === 'training' ? 'train' : 'work', st }
      }
    }
    // Idle hands help on construction sites.
    const site = S.stations.find((st) => st.building)
    if (site && !isNight(hour())) {
      const [w, d] = stationSize(site)
      const a = rand(0, Math.PI * 2)
      return { x: site.x + w / 2 + Math.cos(a) * (w / 2 + 0.6), z: site.z + d / 2 + Math.sin(a) * (d / 2 + 0.6), task: 'build', face: null, center: { x: site.x + w / 2, z: site.z + d / 2 } }
    }
    // Hang around the campfire.
    const fire = S.stations.find((st) => st.type === 'campfire')
    const fx = fire ? fire.x + 1 : 24
    const fz = fire ? fire.z + 1 : 24
    const a = rand(0, Math.PI * 2)
    const r = isNight(hour()) ? rand(1.6, 2.0) : rand(2, 4.5)
    return { x: fx + Math.cos(a) * r, z: fz + Math.sin(a) * r, task: isNight(hour()) && chance(0.7) ? 'sit' : 'idle', center: { x: fx, z: fz } }
  }
  updatePeople(dt) {
    const g = this.grid
    for (const p of this.people.values()) {
      const s = p.s
      if (S.raid && p.agent) continue
      p.wait -= dt
      let mode = 'idle'
      const goalStale = !p.goal || (p.goal.st && s.job !== p.goal.st.id) || (!p.goal.st && s.job) || (p.goal.task === 'patient') !== (s.status === 'injured' && !!S.stations.find((st) => st.patients?.includes(s.id)))
      if (goalStale || (p.wait <= 0 && !p.path && ['idle', 'sit', 'build', 'rest'].includes(p.task))) {
        p.goal = this.personGoal(p)
        p.task = 'walk'
        p.wait = rand(6, 14)
        if (p.goal) {
          if (p.goal.y) {
            // climbing a tower / lying in bed: walk to the base first
            p.path = g.path(p.pos.x, p.pos.z, p.goal.x, p.goal.z + (p.goal.tower ? 1.4 : 1.2))
          } else p.path = g.path(p.pos.x, p.pos.z, p.goal.x, p.goal.z)
          p.pi = 0
          if (p.path && p.path.length && !p.goal.inside) {
            const last = p.path[p.path.length - 1]
            last.x = p.goal.x
            last.z = p.goal.z
          }
        }
      }
      if (p.path) {
        const wp = p.path[p.pi]
        const dx = wp.x - p.pos.x
        const dz = wp.z - p.pos.z
        const d = Math.hypot(dx, dz)
        const sp = (s.status === 'injured' ? 1.2 : 2.2) * dt
        if (d <= sp) {
          p.pos.x = wp.x
          p.pos.z = wp.z
          p.pi++
          if (p.pi >= p.path.length) p.path = null
        } else {
          p.pos.x += (dx / d) * sp
          p.pos.z += (dz / d) * sp
          p.heading = angleLerp(p.heading, Math.atan2(dx, dz), 1 - Math.exp(-dt * 10))
        }
        mode = 'walk'
        p.pos.y = 0
      } else if (p.goal) {
        const G0 = p.goal
        p.task = G0.task
        if (G0.y) {
          p.pos.set(G0.x, G0.y, G0.z)
        }
        const face = G0.center ? Math.atan2(G0.center.x - p.pos.x, G0.center.z - p.pos.z) : G0.face
        if (face != null) p.heading = angleLerp(p.heading, face, 1 - Math.exp(-dt * 6))
        mode = { work: 'work', farm: 'farm', guard: 'guard', train: 'attack', build: 'work', sit: 'sit', patient: 'down', rest: 'sit', idle: 'idle' }[G0.task] || 'idle'
        if (G0.task === 'train') p.swingT = ((p.swingT || 0) + dt) % 1.1
        if (G0.task === 'work' && G0.st && !G0.st.active) mode = 'idle'
      }
      animate(p.rig, dt, mode, 1, mode === 'attack' ? (p.swingT || 0) / 1.1 : 0)
      if (mode === 'down') {
        p.rig.body.rotation.x = -Math.PI / 2
        p.rig.body.position.y = 0.1
      }
      p.rig.root.position.copy(p.pos)
      p.rig.root.rotation.y = p.heading
      const jobEl = p.label.el.querySelector('.job')
      const jt = s.status === 'injured' ? 'Injured' : p.goal?.st ? STATIONS[p.goal.st.type].name : p.task === 'build' ? 'Building' : ''
      if (jobEl.textContent !== jt) jobEl.textContent = jt
      p.label.el.classList.toggle('hurt', s.status === 'injured')
    }
  }

  // ---------------------------------------------------------------- placement
  makeCursor() {
    const g = new THREE.Group()
    const m = new THREE.Mesh(G('plane', 1, 1), basicMat('#7cc36a', { opacity: 0.35 }).clone())
    m.rotation.x = -Math.PI / 2
    m.position.y = 0.05
    g.add(m)
    g.userData.plane = m
    g.visible = false
    this.scene.add(g)
    return g
  }
  startPlacing(type) {
    this.cancelPlacing()
    const def = STATIONS[type]
    const ghost = makeStation(type, 1)
    ghost.traverse((o) => {
      if (o.isMesh) {
        o.material = o.material.clone()
        o.material.transparent = true
        o.material.opacity = 0.55
        o.castShadow = false
      }
    })
    this.scene.add(ghost)
    this.placing = { type, rot: 0, ghost, x: 24, z: 24, ok: false }
    this.cursor.visible = true
    this.updatePlacing(window.innerWidth / 2, window.innerHeight / 2)
    this.game.ui.showPlaceBar(def.name)
  }
  cancelPlacing() {
    if (!this.placing) return
    this.scene.remove(this.placing.ghost)
    this.placing = null
    this.cursor.visible = false
    this.game.ui.hidePlaceBar()
  }
  rotatePlacing() {
    if (!this.placing) return
    this.placing.rot = this.placing.rot ? 0 : 1
    this.placing.ghost.rotation.y = this.placing.rot ? Math.PI / 2 : 0
    this.updatePlacingAt(this.placing.x, this.placing.z)
  }
  updatePlacing(sx, sy) {
    const p = groundAt(sx, sy)
    if (!p) return
    this.updatePlacingAt(p.x, p.z)
  }
  updatePlacingAt(px, pz) {
    const P = this.placing
    const [w0, d0] = STATIONS[P.type].size
    const [w, d] = P.rot ? [d0, w0] : [w0, d0]
    const x = Math.round(px - w / 2)
    const z = Math.round(pz - d / 2)
    P.x = px
    P.z = pz
    P.tx = x
    P.tz = z
    P.ok = this.canPlace(x, z, w, d)
    P.ghost.position.set(x + w / 2, 0, z + d / 2)
    this.cursor.position.set(x + w / 2, 0, z + d / 2)
    this.cursor.userData.plane.scale.set(w, d, 1)
    this.cursor.userData.plane.material.color.set(P.ok ? '#7cc36a' : '#d8384a')
    this.game.ui.placeOk(P.ok)
  }
  canPlace(x, z, w, d) {
    const { F0, F1 } = BASE
    if (x < F0 + 2 || z < F0 + 2 || x + w > F1 - 1 || z + d > F1 - 1) return false
    // keep a walking lane from the gate
    if (x < 26.5 && x + w > 22.5 && z + d > F1 - 4) return false
    for (let i = x - 1; i <= x + w; i++) {
      for (let j = z - 1; j <= z + d; j++) {
        const inner = i >= x && i < x + w && j >= z && j < z + d
        const own = this.grid.owner[this.grid.i(i, j)]
        if (inner && !this.grid.open(i, j)) return false
        if (!inner && own && typeof own === 'object' && own.type) return false // 1-tile gap between stations
      }
    }
    return true
  }
  confirmPlacing() {
    const P = this.placing
    if (!P || !P.ok) {
      sfx('error')
      return false
    }
    const ok = this.game.placeStation(P.type, P.tx, P.tz, P.rot)
    if (ok) this.cancelPlacing()
    return ok
  }

  // ---------------------------------------------------------------- input
  onPress() {
    return false
  }
  onTap(x, y, e) {
    if (this.placing) {
      this.updatePlacing(x, y)
      if (e.touch && !this.placing.touchedOnce) {
        // first tap on touch just positions the ghost
        this.placing.touchedOnce = true
        return
      }
      this.confirmPlacing()
      return
    }
    if (S.raid) {
      this.onRaidTap(x, y, e)
      return
    }
    const hit = pickAt(x, y, [...[...this.stationViews.values()].map((v) => v.group), ...[...this.people.values()].map((p) => p.rig.root), ...(this.visitor ? [this.visitor.root] : [])])
    if (hit?.pick.type === 'visitor') {
      this.game.ui.showRecruit()
      sfx('click')
    } else if (hit?.pick.type === 'station') {
      this.game.ui.openStation(hit.pick.st.id)
      sfx('click')
    } else if (hit?.pick.type === 'person') {
      this.game.ui.openSurvivor(hit.pick.s.id)
      sfx('click')
    } else {
      const p = groundAt(x, y)
      const fenceHit = p && FENCE_TILES.some(([fx, fz]) => Math.abs(fx + 0.5 - p.x) < 0.8 && Math.abs(fz + 0.5 - p.z) < 0.8)
      if (fenceHit) this.game.ui.openFence()
      else this.game.ui.closePanel()
    }
  }
  onHover(x, y) {
    if (this.placing) return this.updatePlacing(x, y)
    const now = performance.now()
    if (now - (this.hoverT || 0) < 70) return
    this.hoverT = now
    const hit = pickAt(x, y, [...this.stationViews.values()].map((v) => v.group).concat(this.visitor ? [this.visitor.root] : []))
    const st = hit?.pick.type === 'station' ? hit.pick.st : null
    document.body.style.cursor = hit ? 'pointer' : ''
    this.game.ui.hoverTip(st ? `<b>${STATIONS[st.type].name}${st.level ? ` · L${st.level}` : ''}</b><span>${st.building ? 'Under construction' : st.stalled || (workersOf(st).length ? workersOf(st).map((s) => s.first).join(', ') : STATIONS[st.type].workers[Math.max(0, st.level - 1)] ? 'No one assigned' : 'Tap for details')}</span>` : hit?.pick.type === 'visitor' ? '<b>Someone at the gate</b><span>Tap to talk</span>' : null, x, y)
  }
  // A stranger waiting outside the gate while a recruit is pending.
  updateVisitor(dt) {
    const p = S.recruit.pending
    if (p && !S.raid) {
      if (!this.visitor || this.visitor.id !== p.s.id) {
        this.removeVisitor()
        const rig = makeHuman(p.s.look)
        rig.root.position.set(24.5 + rand(-0.6, 0.6), 0, BASE.F1 + 2.6)
        rig.root.rotation.y = Math.PI
        rig.root.userData.pick = { type: 'visitor' }
        this.scene.add(rig.root)
        const el = h('div.alabel.visitor', { onclick: () => this.game.ui.showRecruit() }, h('span.nm', 'At the gate'))
        this.visitor = { id: p.s.id, rig, root: rig.root, label: this.labels.add(el, rig.root.position, { offsetY: 2.1, scene: this.scene }) }
      }
      animate(this.visitor.rig, dt, 'idle')
      this.visitor.rig.armR.rotation.x = -2.6 + Math.sin(this.t * 6) * 0.3 // waving
    } else this.removeVisitor()
  }
  removeVisitor() {
    if (!this.visitor) return
    this.scene.remove(this.visitor.root)
    this.visitor.label.remove()
    this.visitor = null
  }
  onKey(e) {
    const k = e.key.toLowerCase()
    if (this.placing) {
      if (k === 'r') this.rotatePlacing()
      if (k === 'escape') this.cancelPlacing()
      if (k === 'enter') this.confirmPlacing()
    } else if (k === 'escape') this.game.ui.closePanel()
    if (S.raid) {
      const n = parseInt(e.key, 10)
      if (n >= 1 && n <= this.squad.length) this.selectDefender(this.squad[n - 1])
    }
  }

  // ---------------------------------------------------------------- hordes
  // A horde attack played out live: zombies come at the fence, defenders shoot.
  startRaid(R) {
    S.raid = { count: R.count, killed: 0, spawned: 0, t: 0, side: pick(['n', 'e', 'w', 's']), side2: chance(R.size >= 2 ? 0.7 : 0.25) ? pick(['n', 'e', 'w', 's']) : null, report: { injured: [], dead: [] } }
    this.cancelPlacing()
    this.selectedDef = null
    // Everyone healthy grabs a weapon and takes position.
    this.squad = []
    for (const p of this.people.values()) {
      const s = p.s
      if (s.status !== 'ok') continue
      const a = new SurvivorAgent(this, s, p.pos.x, p.pos.z)
      a.leash = 2.2
      this.squad.push(a)
      p.agent = a
      p.rig.root.visible = false
      p.label.hidden = true
      const job = S.stations.find((x) => x.id === s.job)
      if (job?.type === 'watchtower') {
        a.tower = job
        const spot = this.workSpot(job, workersOf(job).indexOf(s))
        a.pos.set(spot.x, spot.y, spot.z)
        a.anchor = { x: spot.x, z: spot.z }
        a.leash = 0
      } else {
        const spot = this.defendSpot(S.raid.side, this.squad.length)
        a.command({ type: 'move', x: spot.x, z: spot.z })
      }
    }
    this.zombies = []
    this.turrets = S.stations.filter((st) => st.type === 'turret' && st.level > 0 && !st.building).map((st) => ({ st, cool: 0, view: this.stationViews.get(st.id) }))
    const { F0, F1 } = BASE
    const side = S.raid.side
    const focus = { n: [24, F0 + 5], s: [24, F1 - 5], w: [F0 + 5, 24], e: [F1 - 5, 24] }[side]
    gfx.rig.focus(focus[0], focus[1])
    gfx.rig.distGoal = Math.min(gfx.rig.distGoal, 30)
    sfx('alarm')
    setAmbience(0.06)
    this.game.ui.raidHud(true)
    log(`A horde of ${R.count} is at the fence!`, 'bad')
  }
  defendSpot(side, i) {
    const { F0, F1 } = BASE
    const off = ((i % 6) - 2.5) * 2.2
    if (side === 'n') return { x: 24 + off, z: F0 + 2.5 }
    if (side === 's') return { x: 24 + off + (Math.abs(off) < 2 ? 4 : 0), z: F1 - 2.5 }
    if (side === 'w') return { x: F0 + 2.5, z: 24 + off }
    return { x: F1 - 2.5, z: 24 + off }
  }
  spawnRaidZombie() {
    const R = S.raid
    const side = R.side2 && chance(0.4) ? R.side2 : R.side
    const { F0, F1 } = BASE
    const along = rand(F0 + 3, F1 - 3)
    const out = rand(7, 11)
    let x, z
    if (side === 'n') [x, z] = [along, F0 - out]
    else if (side === 's') [x, z] = [along, F1 + out]
    else if (side === 'w') [x, z] = [F0 - out, along]
    else [x, z] = [F1 + out, along]
    const lvl = clamp(1 + Math.floor(day() / 4), 1, 5)
    const zz = new ZombieAgent(this, weighted(zombieMix(lvl)).t, x, z, lvl)
    zz.state = 'fence'
    zz.label.hidden = true
    this.zombies.push(zz)
    R.spawned++
  }
  // Zombie behaviour while outside the fence: walk to it, then batter it.
  fenceTick(z, dt) {
    const g = this.grid
    if (!z.fenceIdx && z.fenceIdx !== 0) {
      // nearest standing fence tile
      let best = -1
      let bd = 1e9
      FENCE_TILES.forEach(([fx, fz], i) => {
        const d = Math.hypot(fx + 0.5 - z.pos.x, fz + 0.5 - z.pos.z) + rand(0, 3)
        if (d < bd) {
          bd = d
          best = i
        }
      })
      z.fenceIdx = best
      const [fx, fz] = FENCE_TILES[best]
      const ox = fx === BASE.F0 ? -1 : fx === BASE.F1 ? 1 : 0
      const oz = fz === BASE.F0 ? -1 : fz === BASE.F1 ? 1 : 0
      z.fenceSpot = { x: fx + 0.5 + ox * 0.9, z: fz + 0.5 + oz * 0.9 }
      z.moveTo(z.fenceSpot.x, z.fenceSpot.z)
    }
    const i = z.fenceIdx
    if (S.fence.hp[i] <= 0) {
      // breach: go for survivors
      z.state = 'chase'
      z.target = null
      z.perceive()
      if (!z.target) {
        const alive = this.squad.filter((a) => !a.downed)
        if (alive.length) z.target = alive.reduce((a, b) => (z.dist(a) < z.dist(b) ? a : b))
      }
      if (!z.target) z.state = 'idle'
      return 'walk'
    }
    if (z.path) {
      z.step(dt)
      return z.def.speed > 2 ? 'run' : 'walk'
    }
    const [fx, fz] = FENCE_TILES[i]
    z.face(fx + 0.5, fz + 0.5, dt)
    if (z.cool <= 0) {
      z.cool = z.def.rate
      z.swing = 1
      S.fence.hp[i] = Math.max(0, S.fence.hp[i] - z.dmg)
      if (chance(0.3)) this.fx.burst(new THREE.Vector3(fx + 0.5, 1, fz + 0.5), '#8a6a48', 3, 1.5, 0.5, 2)
      if (S.fence.hp[i] <= 0) {
        this.refreshFence()
        sfx('dismantle')
        this.fx.burst(new THREE.Vector3(fx + 0.5, 1, fz + 0.5), '#8a6a48', 20, 3, 1, 3)
        this.game.ui.toast('The fence is breached!', 'bad')
      }
    }
    return 'attack'
  }
  fenceBetween(a, z) {
    return z.state === 'fence' && !z.path
  }
  noise() {}
  ammoLeft() {
    return S.res.ammo
  }
  useAmmo(n) {
    S.res.ammo = Math.max(0, S.res.ammo - n)
  }
  isNight() {
    return isNight(hour())
  }
  nightAcc(a) {
    const lights = S.stations.filter((st) => st.type === 'floodlight' && powerInfo().powered.has(st.id))
    return lights.some((st) => Math.hypot(st.x + 0.5 - a.pos.x, st.z + 0.5 - a.pos.z) < 12) ? 1 : 0.75
  }
  dmgBonus(a) {
    if (!a.tower) return 1
    const def = STATIONS.watchtower
    return 1 + def.towerDmg[a.tower.level - 1] + (a.data.occ === 'guard' ? 0.3 : 0)
  }
  nearCamera(p) {
    return Math.hypot(p.x - gfx.rig.target.x, p.z - gfx.rig.target.z) < 22
  }
  onKill() {
    S.stats.kills++
    if (S.raid) S.raid.killed++
  }
  onDowned(a) {
    this.game.ui.toast(`${a.data.first} is down!`, 'bad')
  }
  selectDefender(a) {
    for (const s of this.squad) s.select(false)
    this.selectedDef = a && !a.downed ? a : null
    if (this.selectedDef) this.selectedDef.select(true)
    this.game.ui.raidHud(true)
  }
  onRaidTap(x, y, e) {
    for (const a of this.squad) if (!a.root.userData.pick) a.root.userData.pick = { type: 'survivor', a }
    for (const z of this.zombies) if (!z.root.userData.pick) z.root.userData.pick = { type: 'zombie', z }
    const hit = pickAt(x, y, [...this.squad.map((a) => a.root), ...this.zombies.filter((z) => !z.dead).map((z) => z.root)])
    if (hit?.pick.type === 'survivor') {
      const a = hit.pick.a
      if (a.downed && this.selectedDef) this.selectedDef.command({ type: 'revive', a })
      else this.selectDefender(a)
      sfx('select')
      return
    }
    if (hit?.pick.type === 'zombie' && this.selectedDef) {
      this.selectedDef.command({ type: 'attack', z: hit.pick.z })
      sfx('move')
      return
    }
    const p = groundAt(x, y)
    if (p && this.selectedDef && !this.selectedDef.tower) {
      this.selectedDef.command({ type: 'move', x: p.x, z: p.z })
      this.fx.ring(p)
      sfx('move')
    }
  }
  updateRaid(dt) {
    const R = S.raid
    R.t += dt
    // stream zombies in over ~40 s
    const rate = R.count / 40
    R.acc = (R.acc || 0) + dt * rate
    while (R.acc >= 1 && R.spawned < R.count) {
      R.acc -= 1
      this.spawnRaidZombie()
    }
    for (const a of this.squad) {
      if (a.tower && !a.downed) {
        const spot = this.workSpot(a.tower, 0)
        a.pos.y = spot.y
      }
      a.update(dt)
    }
    for (const z of this.zombies) z.update(dt)
    const all = [...this.squad.filter((a) => !a.tower), ...this.zombies.filter((z) => !z.dead)]
    for (const a of all) a.separate(dt, all)
    // turrets
    const pinfo = powerInfo()
    for (const t of this.turrets) {
      if (!pinfo.powered.has(t.st.id) || S.res.ammo <= 0) continue
      t.cool -= dt
      const def = STATIONS.turret
      const range = def.range[t.st.level - 1]
      const p = t.view.group.position
      let best = null
      let bd = range
      for (const z of this.zombies) {
        if (z.dead) continue
        const d = Math.hypot(z.pos.x - p.x, z.pos.z - p.z)
        if (d < bd) {
          bd = d
          best = z
        }
      }
      const head = t.view.model?.userData.head
      if (best && head) {
        const want = Math.atan2(best.pos.x - p.x, best.pos.z - p.z) - (t.st.rot ? Math.PI / 2 : 0)
        head.rotation.y = angleLerp(head.rotation.y, want, 1 - Math.exp(-dt * 10))
        if (t.cool <= 0) {
          t.cool = def.rate[t.st.level - 1]
          S.res.ammo = Math.max(0, S.res.ammo - 1)
          const from = head.getWorldPosition(tmp).clone().setY(1)
          this.fx.muzzle(from)
          this.fx.tracer(from, best.pos.clone().setY(1.1), '#ffd080')
          if (this.nearCamera(p)) sfx('smg', 60)
          if (chance(0.75)) best.hurt(def.dmg[t.st.level - 1] * rand(0.85, 1.15), null)
        }
      }
    }
    this.zombies = this.zombies.filter((z) => {
      if (z.dead && z.deadT > 5) {
        z.remove()
        return false
      }
      return true
    })
    const done = R.spawned >= R.count && this.zombies.every((z) => z.dead)
    const lost = this.squad.length === 0 || this.squad.every((a) => a.downed)
    if (done || (lost && R.spawned >= R.count && R.t > 20) || (lost && R.t > 70)) this.endRaid(!lost)
  }
  endRaid(won) {
    const R = S.raid
    const report = { count: R.count, killed: R.killed, injured: [], dead: [], lost: {}, won }
    for (const a of this.squad) {
      const s = a.data
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
      const p = this.people.get(s.id)
      if (p) {
        p.agent = null
        p.pos.set(a.pos.x, 0, a.pos.z)
        p.rig.root.visible = true
        p.label.hidden = false
        p.goal = null
      }
      a.remove()
    }
    if (!won) {
      for (const k of ['food', 'water', 'meds', 'fuel']) {
        const l = Math.floor(S.res[k] * rand(0.25, 0.5))
        S.res[k] -= l
        report.lost[k] = l
      }
      const victims = this.squad.filter((a) => a.downed)
      if (victims.length && chance(0.6)) {
        const v = pick(victims)
        killSurvivor(v.data, 'Killed when the horde overran the camp')
        report.dead.push(v.data.first)
      }
    }
    for (const z of this.zombies) z.remove()
    this.zombies = []
    this.squad = []
    S.raid = null
    S.stats.raids++
    completeGoal('surviveHorde')
    scheduleRaid()
    setAmbience(0.035)
    this.syncPeople()
    this.game.ui.raidHud(false)
    log(won ? `The horde is dead. ${report.killed} zombies put down.` : 'The horde broke through and ransacked the camp.', won ? 'good' : 'bad')
    this.game.ui.raidReport(report)
  }

  // ---------------------------------------------------------------- loop
  update(dt, simDt) {
    this.t += dt
    const hr = hour()
    const nf = nightFactor(hr)
    this.atmo.set(hr, gfx.rig.target, gfx.rig.dist)
    // campfire glow
    const fire = S.stations.find((s) => s.type === 'campfire')
    if (fire) {
      this.lampLight.position.set(fire.x + 1, 1.2, fire.z + 1)
      this.lampLight.intensity = (6 + nf * 26) * (0.85 + Math.sin(this.t * 13) * 0.08 + Math.sin(this.t * 7.3) * 0.07)
    }
    const pinfo = power()
    for (const v of this.stationViews.values()) {
      const st = v.st
      if (v.model) animateStation(v.model, this.t, st.active || st.type === 'campfire' || (st.type === 'generator' && pinfo.supply > 0) || (st.type === 'radio' && st.level > 0), dt)
      if (st.type === 'floodlight' && v.model) {
        const on = pinfo.powered.has(st.id) && nf > 0.3
        v.model.traverse((o) => {
          if (o.userData.bulb) o.material.emissiveIntensity = on ? 3 : 0
        })
      }
      // labels: name on construction, warnings when stalled
      const L = v.label
      const nameEl = L.el.querySelector('.sl-name')
      const barEl = L.el.querySelector('.sl-bar')
      const warnEl = L.el.querySelector('.sl-warn')
      let name = ''
      let bar = null
      let warn = ''
      if (st.building) {
        name = `${STATIONS[st.type].name}${st.building.to > 1 ? ` → L${st.building.to}` : ''}`
        bar = 1 - st.building.left / st.building.total
      } else if (STATIONS[st.type].queue && st.queue.length) {
        bar = 1 - st.queue[0].left / (st.queue[0].total || 60)
        name = 'Crafting'
      }
      if (st.stalled && !st.building && st.stalled !== 'No workers') warn = st.stalled
      if (this.selectedStation === st.id) name = name || `${STATIONS[st.type].name} · L${st.level}`
      nameEl.textContent = name
      barEl.style.display = bar == null ? 'none' : ''
      if (bar != null) barEl.firstChild.style.width = `${clamp(bar, 0, 1) * 100}%`
      warnEl.textContent = warn
      L.el.classList.toggle('empty', !name && !warn && bar == null)
      if (st.building && Math.random() < dt * 1.5 && this.nearCamera(v.group.position)) {
        sfx('build', 250)
        this.fx.burst(v.group.position.clone().setY(1.2), '#c8b88a', 2, 1.2, 0.5, 1.5)
      }
    }
    if (S.raid) this.updateRaid(simDt)
    this.updatePeople(simDt)
    this.updateVisitor(dt)
    this.fx.update(dt)
  }
}
