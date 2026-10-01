// The squad's eyes on a supply run, mixed into Mission. Each tick every
// survivor casts sight from where they stand and the way they face: sight
// range from their job, traits and gear, cut by night (a torch beam helps)
// and fog; a scout's wall sense reaches through one wall. Zombies only show
// while someone can see them. Out of sight they leave a "last seen" mark,
// and anyone within earshot hears them move, which shows as a ripple at
// their rough position. Scout-sensed zombies glow through the wall. A
// minimap shows the explored layout.
import * as THREE from 'three'
import { Vision } from '../world/vision.js'
import { fowUniforms } from '../render/fow.js'
import { nightFactor } from '../render/sky.js'
import { S, hour } from '../game/state.js'
import { view, groundAt } from '../render/view.js'
import { h, rand, clamp, chance } from '../core/util.js'
import { sfx } from '../core/audio.js'

const GHOST_MAT = new THREE.MeshBasicMaterial({ color: new THREE.Color('#6fdcff').multiplyScalar(2.4), transparent: true, opacity: 0.42, depthTest: false, depthWrite: false })
const ROOM_NAMES = {
  kitchen: 'Kitchen', living: 'Living room', bedroom: 'Bedroom', bathroom: 'Bathroom', garage: 'Garage', office: 'Office', storeroom: 'Storeroom', sales: 'Sales floor', hardware: 'Shop floor', pharmacy: 'Pharmacy', diner: 'Dining room',
  ward: 'Ward', corridor: 'Hallway', lobby: 'Lobby', cells: 'Holding cells', armory: 'Armory', lockers: 'Locker room', warehouse: 'Warehouse floor', classroom: 'Classroom', lab: 'Laboratory', barracks: 'Barracks',
}
const MM = 236 // minimap size in CSS px

export const VisionMixin = {
  setupVision() {
    const lv = this.lv
    const V = (this.vision = new Vision(lv))
    const saved = S.explored?.[this.loc.id]
    if (saved) V.load(saved)
    V.revealOutdoors()
    const u = (this.fowU = fowUniforms())
    u.tFow.value = V.tex
    u.uOrigin.value.set(lv.x0, lv.z0)
    u.uSize.value.set(lv.W, lv.H)
    u.uOn.value = 1
    this.scene.userData.fowUniforms = u
    this.visT = 0
    this.lastSeen = []
    this.mmT = 0
    this.knownRooms = new Set()
    V.roomSeen.forEach((v, r) => v && this.knownRooms.add(r))
    V.update(this.observers())
    V.newRoom = null
    V.vis.set(V.target)
    V.sense.set(V.tsense)
    V.changed = true
    V.bake()
    this.applyFog(0)
    this.buildMinimap()
  },
  // What each survivor can see right now.
  observers() {
    const night = nightFactor(hour())
    const w = S.weather?.type
    const wx = w === 'fog' ? 0.6 : w === 'rain' ? 0.9 : 1
    const out = []
    for (const a of this.squad) {
      if (a.npc || a.dead) continue
      const st = a.st
      if (a.downed) {
        out.push({ x: a.pos.x, z: a.pos.z, heading: a.heading, sight: 2.5, back: 1, pierce: 0 })
        continue
      }
      const ns = clamp(st.nightSight || 0, -0.4, 1)
      const nightMul = 1 - night * 0.55 * (1 - ns)
      const sight = Math.max(3, st.sight * nightMul * wx)
      const torch = night > 0.25 ? { r: (st.torch ? 15 : 10) * (w === 'fog' ? 0.7 : 1), half: st.torch ? 0.46 : 0.36 } : null
      out.push({ x: a.pos.x, z: a.pos.z, heading: a.heading, sight, back: 0.62, cone: 1.22, pierce: st.wallSense || 0, torch })
    }
    return out
  },
  updateVisionNow() {
    const V = this.vision
    V.update(this.observers())
    if (V.newRoom) {
      const r = V.newRoom
      V.newRoom = null
      if (!this.knownRooms.has(r.id)) {
        this.knownRooms.add(r.id)
        const c0 = this.center(r.i0, r.j0)
        const c1 = this.center(r.i1, r.j1)
        const lbl = view.labels.add(h('div.roomtag', ROOM_NAMES[r.type] || r.type), new THREE.Vector3((c0.x + c1.x) / 2, 1.2, (c0.z + c1.z) / 2), { life: 2.6, rise: 0.5, scene: this.scene })
        void lbl
        sfx('reveal', 400)
      }
    }
  },
  // Can the squad see this zombie well enough to shoot at it?
  canTarget(z) {
    return !this.vision || this.vision.seesNow(z.pos.x, z.pos.z) > 0.2
  },
  applyFog(dt) {
    const V = this.vision
    const night = nightFactor(hour())
    this.fowU.uNight.value = night
    // memory is a little darker at night
    this.fowU.uMem.value = 0.48 - night * 0.16
    for (const z of this.zombies) {
      const v = V.visibleAt(z.pos.x, z.pos.z)
      const sen = V.sensedAt(z.pos.x, z.pos.z)
      const shown = v > 0.14
      const ghost = !shown && !z.dead && sen > 0.3
      if (shown !== !!z.fogShown) {
        if (!shown && !z.dead && dt > 0) this.markLastSeen(z)
        if (shown && !z.spotted && !z.dead) {
          z.spotted = true
          if (dt > 0) sfx('spot', 300)
        }
        z.fogShown = shown
      }
      z.fogHidden = !shown
      this.setGhost(z, ghost)
      z.root.visible = shown || ghost
      if (!shown && !ghost && !z.dead) this.listen(z, dt)
    }
    // the rescue caller stays hidden until found, but their label shows the way
    if (this.npc && !this.npc.joined && this.npc.root) this.npc.root.visible = V.visibleAt(this.npc.pos.x, this.npc.pos.z) > 0.14
    // loot marks appear once their spot has been explored
    for (const c of this.containers) if (c.label && !c.gone) c.label.hidden = !V.exploredAt(c.x, c.z)
    for (const m of [...this.lastSeen]) {
      m.t -= dt
      if (m.t <= 0 || m.z.dead || !m.z.root || m.z.root.visible) {
        m.label.remove()
        this.lastSeen = this.lastSeen.filter((x) => x !== m)
      } else m.label.el.style.opacity = String(Math.min(1, m.t / 2))
    }
  },
  setGhost(z, on) {
    if (on && !z.ghost && z.ch?.mesh?.isSkinnedMesh) {
      const g = new THREE.SkinnedMesh(z.ch.mesh.geometry, GHOST_MAT)
      g.bind(z.ch.mesh.skeleton, z.ch.mesh.bindMatrix)
      g.frustumCulled = false
      g.renderOrder = 6
      z.ch.mesh.add(g)
      z.ghost = g
    }
    if (!z.ghost) return
    z.ghost.visible = on
    // only the ghost draws while a zombie is just sensed
    hideOwn(z, on)
  },
  markLastSeen(z) {
    if (this.lastSeen.length > 10) {
      const old = this.lastSeen.shift()
      old.label.remove()
    }
    const el = h('div.lastseen' + (z.type === 'brute' ? '.big' : ''), h('i'))
    const label = view.labels.add(el, new THREE.Vector3(z.pos.x, 0.25, z.pos.z), { scene: this.scene })
    this.lastSeen.push({ z, t: 7, label })
  },
  // Out of sight but within earshot: footsteps, groans, banging.
  listen(z, dt) {
    if (dt <= 0) return
    z.pingT = (z.pingT ?? rand(0, 1.5)) - dt
    if (z.pingT > 0) return
    const loud = z.state === 'chase' || z.state === 'fence' || z.swing > 0.2 || z.burn > 0
    const moving = !!z.path && z.curSpeed > 0.2
    if (!loud && !moving) {
      z.pingT = rand(0.8, 1.6)
      return
    }
    const rainK = S.weather?.type === 'rain' ? 0.75 : 1
    let heard = false
    for (const a of this.squad) {
      if (a.npc || a.downed || a.dead) continue
      const r = a.st.hearing * rainK * (loud ? 1.35 : 1) * (z.type === 'brute' ? 1.3 : z.def?.crawl ? 0.6 : 1)
      if (Math.hypot(a.pos.x - z.pos.x, a.pos.z - z.pos.z) <= r) {
        heard = true
        break
      }
    }
    if (!heard) {
      z.pingT = rand(0.5, 1)
      return
    }
    z.pingT = loud ? rand(0.8, 1.3) : rand(1.6, 2.6)
    if (z.def?.stalk && !loud && chance(0.75)) return
    const kind = z.type === 'brute' || z.type === 'bloater' ? '.heavy' : z.type === 'runner' ? '.fast' : ''
    const p = new THREE.Vector3(z.pos.x + rand(-1, 1), 0.2, z.pos.z + rand(-1, 1))
    view.labels.add(h('div.ping' + kind + (loud ? '.loud' : '')), p, { life: 1.6, scene: this.scene })
    this.pings = (this.pings || []).filter((q) => q.t > 0)
    this.pings.push({ x: p.x, z: p.z, t: 1.6, loud })
  },
  updateVision(dt) {
    const V = this.vision
    if (!V) return
    this.visT -= dt
    if (this.visT <= 0) {
      this.visT = 0.06
      this.updateVisionNow()
      if (dt > 0 && !this.remote) this.spotTraps?.(0.06)
    }
    V.ease(dt)
    V.bake()
    this.applyFog(dt)
    for (const p of this.pings || []) p.t -= dt
    this.mmT -= Math.max(dt, 1 / 60)
    if (this.mmT <= 0) {
      this.mmT = 0.12
      this.drawMinimap()
    }
  },

  // ---------------------------------------------------------------- minimap
  buildMinimap() {
    const lv = this.lv
    const dpr = Math.min(2, window.devicePixelRatio || 1)
    const cv = h('canvas.mm-canvas')
    cv.width = MM * dpr
    cv.height = MM * dpr
    this.mmCanvas = cv
    this.mmDpr = dpr
    this.mmImg = document.createElement('canvas')
    this.mmImg.width = lv.W
    this.mmImg.height = lv.H
    this.mmData = this.mmImg.getContext('2d').createImageData(lv.W, lv.H)
    this.mmPct = h('b', '0%')
    this.mmEl = h('div.minimap', h('div.mm-head', h('span', 'Map'), h('small', 'Explored ', this.mmPct)), cv)
    const pick = (e) => {
      const r = cv.getBoundingClientRect()
      const p = this.mmToWorld(e.clientX - r.left, e.clientY - r.top)
      if (p) view.rig.focus(p.x, p.z)
    }
    let drag = false
    cv.addEventListener('pointerdown', (e) => {
      drag = true
      cv.setPointerCapture(e.pointerId)
      pick(e)
    })
    cv.addEventListener('pointermove', (e) => drag && pick(e))
    cv.addEventListener('pointerup', () => (drag = false))
    this.root.append(this.mmEl)
    this.drawMinimap()
  },
  // world -> minimap pixels, oriented like the camera
  mmBasis() {
    const lv = this.lv
    const y = view.rig.yaw
    const fx = -Math.sin(y)
    const fz = -Math.cos(y)
    const rx = Math.cos(y)
    const rz = -Math.sin(y)
    const cx = lv.x0 + lv.W / 2
    const cz = lv.z0 + lv.H / 2
    const s = (MM - 12) / Math.hypot(lv.W, lv.H)
    return { fx, fz, rx, rz, cx, cz, s }
  },
  mmToScreen(x, z, B = this.mmBasis()) {
    const dx = x - B.cx
    const dz = z - B.cz
    return { x: MM / 2 + (dx * B.rx + dz * B.rz) * B.s, y: MM / 2 - (dx * B.fx + dz * B.fz) * B.s }
  },
  mmToWorld(px, py) {
    const B = this.mmBasis()
    const a = (px - MM / 2) / B.s
    const b = -(py - MM / 2) / B.s
    // invert [rx rz; fx fz]
    const det = B.rx * B.fz - B.rz * B.fx
    if (Math.abs(det) < 1e-6) return null
    const dx = (a * B.fz - b * B.rz) / det
    const dz = (b * B.rx - a * B.fx) / det
    return { x: B.cx + dx, z: B.cz + dz }
  },
  drawMinimap() {
    if (!this.mmCanvas) return
    const lv = this.lv
    const V = this.vision
    const D = this.mmData.data
    const W = lv.W
    // the tile layer: unexplored dark, explored muted, visible bright, sensed cyan
    for (let k = 0; k < V.N; k++) {
      const b = k * 4
      const seen = V.seen[k]
      const v = V.vis[k]
      const sen = V.sense[k]
      const wall = lv.walls[k] === 1
      const block = !wall && V.solid[k]
      const room = lv.roomAt[k] >= 0
      let r = 10
      let g = 12
      let bl = 11
      let a = 235
      if (seen) {
        if (wall) {
          r = 150
          g = 156
          bl = 148
        } else if (block) {
          r = 22
          g = 24
          bl = 24
        } else if (room) {
          r = 58
          g = 62
          bl = 56
        } else {
          r = 34
          g = 38
          bl = 36
        }
        if (v > 0 && !block) {
          r += (wall ? 70 : 60) * v
          g += (wall ? 66 : 64) * v
          bl += (wall ? 56 : 44) * v
        }
      }
      if (sen > 0.05) {
        r = r * (1 - sen * 0.6) + 40 * sen
        g = g * (1 - sen * 0.6) + 150 * sen
        bl = bl * (1 - sen * 0.6) + 190 * sen
      }
      D[b] = r
      D[b + 1] = g
      D[b + 2] = bl
      D[b + 3] = a
    }
    const ictx = this.mmImg.getContext('2d')
    ictx.putImageData(this.mmData, 0, 0)
    const ctx = this.mmCanvas.getContext('2d')
    const dpr = this.mmDpr
    const B = this.mmBasis()
    ctx.setTransform(1, 0, 0, 1, 0, 0)
    ctx.clearRect(0, 0, this.mmCanvas.width, this.mmCanvas.height)
    // tile image placed with the camera's rotation
    const o = this.mmToScreen(lv.x0, lv.z0, B)
    ctx.setTransform(B.rx * B.s * dpr, -B.fx * B.s * dpr, B.rz * B.s * dpr, -B.fz * B.s * dpr, o.x * dpr, o.y * dpr)
    ctx.imageSmoothingEnabled = false
    ctx.drawImage(this.mmImg, 0, 0)
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    // the camera's view on the ground
    const corners = [[0, 0], [window.innerWidth, 0], [window.innerWidth, window.innerHeight], [0, window.innerHeight]].map(([x, y]) => groundAt(x, y))
    if (corners.every(Boolean)) {
      ctx.beginPath()
      corners.forEach((p, i) => {
        const q = this.mmToScreen(p.x, p.z, B)
        if (i) ctx.lineTo(q.x, q.y)
        else ctx.moveTo(q.x, q.y)
      })
      ctx.closePath()
      ctx.strokeStyle = 'rgba(240, 232, 210, 0.35)'
      ctx.lineWidth = 1
      ctx.stroke()
    }
    // containers in explored rooms
    for (const c of this.containers) {
      if (c.gone || !V.exploredAt(c.x, c.z)) continue
      const q = this.mmToScreen(c.x, c.z, B)
      ctx.fillStyle = c.stash ? '#7ad07a' : c.searched ? 'rgba(160,150,130,0.45)' : c.locked ? '#d8a04a' : '#e8c070'
      ctx.fillRect(q.x - 1.5, q.y - 1.5, 3, 3)
    }
    // the van and the exit
    const E = lv.evac
    const e = this.mmToScreen(E.x, E.z, B)
    ctx.strokeStyle = '#6fd08a'
    ctx.lineWidth = 1.5
    ctx.beginPath()
    ctx.arc(e.x, e.y, E.r * B.s, 0, Math.PI * 2)
    ctx.stroke()
    // sounds
    for (const p of this.pings || []) {
      if (p.t <= 0) continue
      const q = this.mmToScreen(p.x, p.z, B)
      const k = 1 - p.t / 1.6
      ctx.strokeStyle = `rgba(255, 190, 90, ${(1 - k) * 0.9})`
      ctx.beginPath()
      ctx.arc(q.x, q.y, 2 + k * 7, 0, Math.PI * 2)
      ctx.stroke()
    }
    // last seen
    for (const m of this.lastSeen) {
      const q = this.mmToScreen(m.label.pos.x, m.label.pos.z, B)
      ctx.strokeStyle = `rgba(255, 110, 90, ${Math.min(1, m.t / 2) * 0.8})`
      ctx.strokeRect(q.x - 2.5, q.y - 2.5, 5, 5)
    }
    // zombies you can see (or sense)
    for (const z of this.zombies) {
      if (z.dead) continue
      const shown = !z.fogHidden
      const sensed = z.ghost?.visible
      if (!shown && !sensed) continue
      const q = this.mmToScreen(z.pos.x, z.pos.z, B)
      ctx.fillStyle = sensed && !shown ? '#6fdcff' : z.type === 'brute' ? '#ff5a3a' : '#e8483a'
      ctx.beginPath()
      ctx.arc(q.x, q.y, z.type === 'brute' ? 3.2 : 2.3, 0, Math.PI * 2)
      ctx.fill()
    }
    // the squad, with their facing
    for (const a of this.squad) {
      if (a.dead) continue
      const q = this.mmToScreen(a.pos.x, a.pos.z, B)
      const hx = Math.sin(a.heading)
      const hz = Math.cos(a.heading)
      const t = this.mmToScreen(a.pos.x + hx * 2.2, a.pos.z + hz * 2.2, B)
      ctx.strokeStyle = a.npc ? '#ff9a86' : a.selected ? '#ffd27a' : '#f0e8d0'
      ctx.lineWidth = 1.4
      ctx.beginPath()
      ctx.moveTo(q.x, q.y)
      ctx.lineTo(t.x, t.y)
      ctx.stroke()
      ctx.fillStyle = a.downed ? '#c83a2a' : a.npc ? '#ff9a86' : a.selected ? '#ffc14a' : '#f0e8d0'
      ctx.beginPath()
      ctx.arc(q.x, q.y, 3, 0, Math.PI * 2)
      ctx.fill()
    }
    const pct = Math.round(V.exploredFrac() * 100) + '%'
    if (this.mmPct.textContent !== pct) this.mmPct.textContent = pct
  },
  saveVision() {
    if (!this.vision) return
    S.explored = S.explored || {}
    S.explored[this.loc.id] = this.vision.save()
  },
  disposeVision() {
    this.vision?.dispose()
  },
}

// While a zombie is only sensed, draw just its ghost: hide the real mesh's
// own material without hiding its children.
function hideOwn(z, on) {
  const m = z.ch.mesh
  if (on) {
    if (!m.userData.realMat) m.userData.realMat = m.material
    m.material = INVISIBLE
  } else if (m.userData.realMat) {
    m.material = m.userData.realMat
    m.userData.realMat = null
  }
}
const INVISIBLE = new THREE.MeshBasicMaterial({ visible: false })
