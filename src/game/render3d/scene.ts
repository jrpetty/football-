import * as THREE from 'three'
import { Sky } from 'three/examples/jsm/objects/Sky.js'
import { BALL, FIELD, KITS, PLAYER } from '../config'
import * as F from '../match/field'
import type { World } from '../match/world'

// Simulation → Three.js coordinate map: the pitch plane (sim x, y) becomes the
// ground plane (X, Z); ball height (sim z) becomes world Y (up).
const v3 = (x: number, y: number, z = 0) => new THREE.Vector3(x, z, y)

interface PlayerNodes {
  group: THREE.Group
  ring: THREE.Mesh
  legL: THREE.Group
  legR: THREE.Group
  armL: THREE.Group
  armR: THREE.Group
  phase: number
}

interface TeamMats {
  jersey: THREE.Material
  gk: THREE.Material
  shorts: THREE.Material
  socks: THREE.Material
}

// Builds and owns the WebGL scene and syncs every mesh from World each frame.
// Reads the simulation only — never writes it. Aims for a clean "stadium" look:
// filmic tone-mapping, an atmospheric sky, real shadows, humanoid players with a
// procedural run cycle, and surrounding stands / ad-boards / floodlights.
export class Scene3D {
  readonly scene = new THREE.Scene()
  readonly renderer: THREE.WebGLRenderer
  private ball!: THREE.Mesh
  private players = new Map<number, PlayerNodes>()
  private teamMats: Record<'home' | 'away', TeamMats>
  private skinMat = new THREE.MeshStandardMaterial({ color: '#e5b08a', roughness: 0.85 })
  private hairMat = new THREE.MeshStandardMaterial({ color: '#2c211b', roughness: 0.9 })
  private bootMat = new THREE.MeshStandardMaterial({ color: '#14161c', roughness: 0.5 })

  constructor(container: HTMLElement) {
    this.renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' })
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
    this.renderer.shadowMap.enabled = true
    this.renderer.shadowMap.type = THREE.PCFSoftShadowMap
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping
    this.renderer.toneMappingExposure = 0.62
    this.renderer.domElement.style.position = 'fixed'
    this.renderer.domElement.style.inset = '0'
    container.appendChild(this.renderer.domElement)

    this.scene.fog = new THREE.Fog('#b7d2e6', 95, 240)

    this.teamMats = {
      home: this.makeTeamMats('home'),
      away: this.makeTeamMats('away'),
    }

    this.buildSkyAndLights()
    this.buildPitch()
    this.buildStadium()
    this.buildGoals()
    this.buildBall()
  }

  dispose() {
    this.renderer.dispose()
    this.renderer.domElement.remove()
  }

  resize(w: number, h: number) {
    this.renderer.setSize(w, h)
  }

  render(camera: THREE.Camera) {
    this.renderer.render(this.scene, camera)
  }

  // ---- sky, sun, lighting ----

  private buildSkyAndLights() {
    const sky = new Sky()
    sky.scale.setScalar(4000)
    const u = sky.material.uniforms
    u.turbidity.value = 6
    u.rayleigh.value = 1.4
    u.mieCoefficient.value = 0.006
    u.mieDirectionalG.value = 0.8
    const elevation = 32
    const azimuth = 150
    const phi = THREE.MathUtils.degToRad(90 - elevation)
    const theta = THREE.MathUtils.degToRad(azimuth)
    const sun = new THREE.Vector3().setFromSphericalCoords(1, phi, theta)
    u.sunPosition.value.copy(sun)
    this.scene.add(sky)

    this.scene.add(new THREE.HemisphereLight('#dcecff', '#3a5a3f', 0.7))
    this.scene.add(new THREE.AmbientLight('#ffffff', 0.18))

    const dir = new THREE.DirectionalLight('#fff4de', 2.4)
    dir.position.copy(sun.clone().multiplyScalar(80)).add(v3(FIELD.length / 2, FIELD.width / 2, 0))
    dir.target.position.copy(v3(FIELD.length / 2, FIELD.width / 2))
    dir.castShadow = true
    dir.shadow.mapSize.set(2048, 2048)
    const cam = dir.shadow.camera as THREE.OrthographicCamera
    cam.near = 1
    cam.far = 220
    cam.left = -45
    cam.right = 45
    cam.top = 45
    cam.bottom = -45
    dir.shadow.bias = -0.0004
    dir.shadow.normalBias = 0.02
    this.scene.add(dir)
    this.scene.add(dir.target)
  }

  // ---- pitch ----

  private buildPitch() {
    const L = FIELD.length
    const W = FIELD.width
    const under = new THREE.Mesh(
      new THREE.PlaneGeometry(L + 120, W + 120),
      new THREE.MeshStandardMaterial({ color: '#20361f', roughness: 1 }),
    )
    under.rotation.x = -Math.PI / 2
    under.position.set(L / 2, -0.03, W / 2)
    under.receiveShadow = true
    this.scene.add(under)

    const tex = new THREE.CanvasTexture(this.pitchTexture())
    tex.anisotropy = 8
    tex.colorSpace = THREE.SRGBColorSpace
    const pitch = new THREE.Mesh(
      new THREE.PlaneGeometry(L, W),
      new THREE.MeshStandardMaterial({ map: tex, roughness: 0.92 }),
    )
    pitch.rotation.x = -Math.PI / 2
    pitch.position.set(L / 2, 0, W / 2)
    pitch.receiveShadow = true
    this.scene.add(pitch)
  }

  private pitchTexture(): HTMLCanvasElement {
    const L = FIELD.length
    const W = FIELD.width
    const s = 26
    const c = document.createElement('canvas')
    c.width = L * s
    c.height = W * s
    const ctx = c.getContext('2d')!
    const mx = (x: number) => x * s
    const my = (y: number) => y * s

    const stripes = 14
    for (let i = 0; i < stripes; i++) {
      const g = ctx.createLinearGradient(0, 0, 0, c.height)
      const base = i % 2 === 0 ? [46, 138, 72] : [38, 122, 63]
      g.addColorStop(0, `rgb(${base[0] + 8},${base[1] + 10},${base[2] + 6})`)
      g.addColorStop(1, `rgb(${base[0]},${base[1]},${base[2]})`)
      ctx.fillStyle = g
      ctx.fillRect((i / stripes) * c.width, 0, c.width / stripes + 1, c.height)
    }

    ctx.strokeStyle = 'rgba(255,255,255,0.9)'
    ctx.lineWidth = 0.16 * s
    ctx.lineCap = 'round'
    ctx.strokeRect(mx(0.2), my(0.2), mx(L - 0.4), my(W - 0.4))
    ctx.beginPath()
    ctx.moveTo(mx(L / 2), my(0.2))
    ctx.lineTo(mx(L / 2), my(W - 0.2))
    ctx.stroke()
    ctx.beginPath()
    ctx.arc(mx(L / 2), my(W / 2), FIELD.centerRadius * s, 0, Math.PI * 2)
    ctx.stroke()
    ctx.fillStyle = 'rgba(255,255,255,0.9)'
    ctx.beginPath()
    ctx.arc(mx(L / 2), my(W / 2), 0.28 * s, 0, Math.PI * 2)
    ctx.fill()
    const yMin = (W - FIELD.boxWidth) / 2
    ctx.strokeRect(mx(0.2), my(yMin), mx(FIELD.boxDepth), FIELD.boxWidth * s)
    ctx.strokeRect(mx(L - FIELD.boxDepth - 0.2), my(yMin), FIELD.boxDepth * s, FIELD.boxWidth * s)
    // penalty spots
    for (const px of [FIELD.boxDepth * 0.65, L - FIELD.boxDepth * 0.65]) {
      ctx.beginPath()
      ctx.arc(mx(px), my(W / 2), 0.22 * s, 0, Math.PI * 2)
      ctx.fill()
    }
    return c
  }

  // ---- stadium (boards, stands, floodlights) ----

  private buildStadium() {
    const L = FIELD.length
    const W = FIELD.width
    const cx = L / 2
    const cz = W / 2
    const m = FIELD.margin

    // Advertising boards ringing the pitch.
    const adTex = new THREE.CanvasTexture(this.adTexture())
    adTex.wrapS = THREE.RepeatWrapping
    adTex.colorSpace = THREE.SRGBColorSpace
    const boardMat = new THREE.MeshStandardMaterial({ map: adTex, roughness: 0.5 })
    const bh = 1.1
    const long = new THREE.BoxGeometry(L + m * 2, bh, 0.3)
    const short = new THREE.BoxGeometry(0.3, bh, W + m * 2)
    const mkBoard = (geo: THREE.BufferGeometry, x: number, z: number) => {
      const b = new THREE.Mesh(geo, boardMat)
      b.position.set(x, bh / 2, z)
      b.castShadow = true
      b.receiveShadow = true
      this.scene.add(b)
    }
    mkBoard(long, cx, -m)
    mkBoard(long, cx, W + m)
    mkBoard(short, -m, cz)
    mkBoard(short, L + m, cz)

    // Raked crowd stands forming a shallow bowl.
    const crowdTex = new THREE.CanvasTexture(this.crowdTexture())
    crowdTex.wrapS = crowdTex.wrapT = THREE.RepeatWrapping
    crowdTex.colorSpace = THREE.SRGBColorSpace
    const standMat = new THREE.MeshStandardMaterial({ map: crowdTex, roughness: 1 })
    const standDepth = 26
    const standH = 15
    const gap = m + 2
    const addStand = (w: number, cxp: number, czp: number, rotY: number) => {
      crowdTex.repeat.set(w / 6, standDepth / 6)
      const geo = new THREE.PlaneGeometry(w, standDepth)
      const s = new THREE.Mesh(geo, standMat)
      s.position.set(cxp, standH / 2, czp)
      s.rotation.order = 'YXZ'
      s.rotation.y = rotY
      s.rotation.x = -Math.PI / 2 + 0.62 // rake angle
      s.receiveShadow = true
      this.scene.add(s)
    }
    addStand(L + m * 2 + standDepth, cx, -gap - standDepth * 0.32, 0)
    addStand(L + m * 2 + standDepth, cx, W + gap + standDepth * 0.32, Math.PI)
    addStand(W + m * 2 + standDepth, -gap - standDepth * 0.32, cz, Math.PI / 2)
    addStand(W + m * 2 + standDepth, L + gap + standDepth * 0.32, cz, -Math.PI / 2)

    // Floodlight pylons at the four corners.
    const poleMat = new THREE.MeshStandardMaterial({ color: '#7c8794', metalness: 0.6, roughness: 0.5 })
    const lampMat = new THREE.MeshStandardMaterial({ color: '#fdfbe6', emissive: '#fff6c8', emissiveIntensity: 1.4 })
    for (const [sx, sz] of [[-1, -1], [1, -1], [-1, 1], [1, 1]] as const) {
      const px = cx + sx * (L / 2 + m + 5)
      const pz = cz + sz * (W / 2 + m + 5)
      const pole = new THREE.Mesh(new THREE.CylinderGeometry(0.35, 0.5, 26, 8), poleMat)
      pole.position.set(px, 13, pz)
      pole.castShadow = true
      this.scene.add(pole)
      const rig = new THREE.Mesh(new THREE.BoxGeometry(4, 1.6, 0.6), lampMat)
      rig.position.set(px, 25.5, pz)
      rig.lookAt(v3(cx, W / 2, 2))
      this.scene.add(rig)
    }
  }

  private adTexture(): HTMLCanvasElement {
    const c = document.createElement('canvas')
    c.width = 1024
    c.height = 128
    const ctx = c.getContext('2d')!
    const cols = ['#12331f', '#2f7df6', '#0b2d63', '#ec4d4d', '#1f7a3d', '#22303f']
    const panel = 170
    for (let x = 0, i = 0; x < c.width; x += panel, i++) {
      ctx.fillStyle = cols[i % cols.length]
      ctx.fillRect(x, 0, panel - 6, c.height)
      ctx.fillStyle = 'rgba(255,255,255,0.92)'
      ctx.font = '800 46px system-ui, sans-serif'
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      ctx.fillText('OPEN PITCH', x + panel / 2 - 3, c.height / 2)
    }
    return c
  }

  private crowdTexture(): HTMLCanvasElement {
    const c = document.createElement('canvas')
    c.width = 256
    c.height = 256
    const ctx = c.getContext('2d')!
    ctx.fillStyle = '#1a2230'
    ctx.fillRect(0, 0, c.width, c.height)
    const cols = ['#e8e8ee', '#c94f4f', '#4f7fe0', '#e0b84f', '#5fae6a', '#b48ad0', '#e2915a']
    // Deterministic scatter so it doesn't shimmer between builds.
    let seed = 1234567
    const rnd = () => ((seed = (seed * 1103515245 + 12345) & 0x7fffffff) / 0x7fffffff)
    for (let i = 0; i < 2600; i++) {
      ctx.fillStyle = cols[(rnd() * cols.length) | 0]
      ctx.globalAlpha = 0.55 + rnd() * 0.45
      const x = rnd() * c.width
      const y = rnd() * c.height
      ctx.beginPath()
      ctx.arc(x, y, 1.6 + rnd() * 1.4, 0, Math.PI * 2)
      ctx.fill()
    }
    ctx.globalAlpha = 1
    return c
  }

  // ---- goals ----

  private buildGoals() {
    const white = new THREE.MeshStandardMaterial({ color: '#f4f6fa', metalness: 0.2, roughness: 0.5 })
    const netMat = new THREE.MeshBasicMaterial({ color: '#ffffff', transparent: true, opacity: 0.14, side: THREE.DoubleSide, depthWrite: false })
    for (const team of ['home', 'away'] as const) {
      const goalX = F.ownGoalLineX(team)
      const [a, b] = F.goalPostYs()
      const h = FIELD.goalHeight
      const postGeo = new THREE.CylinderGeometry(0.1, 0.1, h, 12)
      for (const py of [a, b]) {
        const post = new THREE.Mesh(postGeo, white)
        post.position.copy(v3(goalX, py, h / 2))
        post.castShadow = true
        this.scene.add(post)
      }
      const bar = new THREE.Mesh(new THREE.CylinderGeometry(0.1, 0.1, b - a, 12), white)
      bar.position.copy(v3(goalX, (a + b) / 2, h))
      bar.rotation.x = Math.PI / 2
      bar.castShadow = true
      this.scene.add(bar)
      const depth = FIELD.goalDepth
      const dir = team === 'home' ? -1 : 1
      const net = new THREE.Mesh(new THREE.BoxGeometry(depth, h, b - a), netMat)
      net.position.copy(v3(goalX + (dir * depth) / 2, (a + b) / 2, h / 2))
      this.scene.add(net)
    }
  }

  // ---- ball ----

  private buildBall() {
    const tex = new THREE.CanvasTexture(this.ballTexture())
    this.ball = new THREE.Mesh(
      new THREE.SphereGeometry(BALL.radius, 24, 18),
      new THREE.MeshStandardMaterial({ map: tex, roughness: 0.4, metalness: 0.02 }),
    )
    this.ball.castShadow = true
    this.scene.add(this.ball)
  }

  private ballTexture(): HTMLCanvasElement {
    const c = document.createElement('canvas')
    c.width = c.height = 128
    const ctx = c.getContext('2d')!
    ctx.fillStyle = '#fbfcfe'
    ctx.fillRect(0, 0, 128, 128)
    ctx.fillStyle = '#20242c'
    for (let i = 0; i < 6; i++) {
      const x = 20 + (i % 3) * 44 + (Math.floor(i / 3) % 2) * 22
      const y = 26 + Math.floor(i / 3) * 60
      ctx.beginPath()
      for (let k = 0; k < 5; k++) {
        const ang = (k / 5) * Math.PI * 2 - Math.PI / 2
        const px = x + Math.cos(ang) * 10
        const py = y + Math.sin(ang) * 10
        k === 0 ? ctx.moveTo(px, py) : ctx.lineTo(px, py)
      }
      ctx.closePath()
      ctx.fill()
    }
    return c
  }

  // ---- players (humanoid + run cycle) ----

  private makeTeamMats(team: 'home' | 'away'): TeamMats {
    const kit = KITS[team]
    return {
      jersey: new THREE.MeshStandardMaterial({ color: kit.primary, roughness: 0.65 }),
      gk: new THREE.MeshStandardMaterial({ color: kit.gk, roughness: 0.6 }),
      shorts: new THREE.MeshStandardMaterial({ color: kit.accent, roughness: 0.7 }),
      socks: new THREE.MeshStandardMaterial({ color: kit.secondary, roughness: 0.8 }),
    }
  }

  private makeLimb(mat: THREE.Material, footMat: THREE.Material | null, w: number, len: number): THREE.Group {
    // Pivot at the top; the limb hangs down −Y so rotation.x swings it.
    const g = new THREE.Group()
    const seg = new THREE.Mesh(new THREE.CapsuleGeometry(w, len - w * 2, 4, 8), mat)
    seg.position.y = -len / 2
    seg.castShadow = true
    g.add(seg)
    if (footMat) {
      const foot = new THREE.Mesh(new THREE.BoxGeometry(w * 2.1, w * 1.3, w * 3.4), footMat)
      foot.position.set(0, -len + w * 0.4, w * 0.9)
      foot.castShadow = true
      g.add(foot)
    }
    return g
  }

  private ensurePlayer(id: number, team: 'home' | 'away', role: string, num: number): PlayerNodes {
    const existing = this.players.get(id)
    if (existing) return existing
    const mats = this.teamMats[team]
    const jersey = role === 'GK' ? mats.gk : mats.jersey
    const group = new THREE.Group()

    const hips = new THREE.Mesh(new THREE.BoxGeometry(0.44, 0.26, 0.28), mats.shorts)
    hips.position.y = 0.9
    hips.castShadow = true
    group.add(hips)

    const torso = new THREE.Mesh(new THREE.CapsuleGeometry(0.24, 0.44, 4, 10), jersey)
    torso.scale.set(1.15, 1, 0.72)
    torso.position.y = 1.28
    torso.castShadow = true
    group.add(torso)

    const neck = new THREE.Mesh(new THREE.CylinderGeometry(0.07, 0.08, 0.12, 8), this.skinMat)
    neck.position.y = 1.6
    group.add(neck)
    const head = new THREE.Mesh(new THREE.SphereGeometry(0.15, 16, 14), this.skinMat)
    head.position.y = 1.75
    head.castShadow = true
    group.add(head)
    const hair = new THREE.Mesh(new THREE.SphereGeometry(0.155, 16, 12, 0, Math.PI * 2, 0, Math.PI * 0.62), this.hairMat)
    hair.position.y = 1.77
    group.add(hair)

    // Number on the back.
    const numTex = new THREE.CanvasTexture(this.numberTexture(num, KITS[team].secondary, role === 'GK' ? KITS[team].gk : KITS[team].primary))
    numTex.colorSpace = THREE.SRGBColorSpace
    const back = new THREE.Mesh(
      new THREE.PlaneGeometry(0.34, 0.34),
      new THREE.MeshStandardMaterial({ map: numTex, transparent: true, roughness: 0.7 }),
    )
    back.position.set(0, 1.32, -0.2)
    back.rotation.y = Math.PI
    group.add(back)

    const armL = this.makeLimb(role === 'GK' ? mats.gk : this.skinMat, null, 0.075, 0.62)
    armL.position.set(0.3, 1.5, 0)
    const armR = this.makeLimb(role === 'GK' ? mats.gk : this.skinMat, null, 0.075, 0.62)
    armR.position.set(-0.3, 1.5, 0)
    // Short-sleeve jersey stub on the shoulder.
    for (const [arm, sx] of [[armL, 0.3], [armR, -0.3]] as const) {
      const sleeve = new THREE.Mesh(new THREE.CylinderGeometry(0.1, 0.09, 0.16, 8), jersey)
      sleeve.position.set(sx, 1.46, 0)
      sleeve.castShadow = true
      group.add(sleeve)
      group.add(arm)
    }

    const legL = this.makeLimb(mats.socks, this.bootMat, 0.09, 0.9)
    legL.position.set(0.13, 0.9, 0)
    const legR = this.makeLimb(mats.socks, this.bootMat, 0.09, 0.9)
    legR.position.set(-0.13, 0.9, 0)
    // Shorts overlay on the thighs.
    for (const [sx] of [[0.13], [-0.13]] as const) {
      const thigh = new THREE.Mesh(new THREE.CylinderGeometry(0.11, 0.1, 0.34, 8), mats.shorts)
      thigh.position.set(sx, 0.72, 0)
      thigh.castShadow = true
      group.add(thigh)
    }
    group.add(legL)
    group.add(legR)

    const ring = new THREE.Mesh(
      new THREE.TorusGeometry(PLAYER.radius + 0.2, 0.06, 8, 32),
      new THREE.MeshStandardMaterial({ color: '#eafff0', emissive: '#7dff9a', emissiveIntensity: 0.6 }),
    )
    ring.rotation.x = -Math.PI / 2
    ring.position.y = 0.05
    ring.visible = false
    group.add(ring)

    this.scene.add(group)
    const nodes: PlayerNodes = { group, ring, legL, legR, armL, armR, phase: Math.random() * 6 }
    this.players.set(id, nodes)
    return nodes
  }

  private numCache = new Map<string, HTMLCanvasElement>()
  private numberTexture(num: number, color: string, bg: string): HTMLCanvasElement {
    const key = `${num}|${color}|${bg}`
    const cached = this.numCache.get(key)
    if (cached) return cached
    const c = document.createElement('canvas')
    c.width = c.height = 128
    const ctx = c.getContext('2d')!
    ctx.clearRect(0, 0, 128, 128)
    ctx.fillStyle = color
    ctx.font = '800 92px system-ui, sans-serif'
    ctx.textAlign = 'center'
    ctx.textBaseline = 'middle'
    ctx.fillText(String(num), 64, 70)
    this.numCache.set(key, c)
    return c
  }

  // ---- per-frame sync ----

  sync(world: World, controlledId: number, showControlledRing: boolean, hideId = -1, dt = 0.016) {
    const b = world.ball
    this.ball.position.copy(v3(b.x, b.y, b.z + BALL.radius))
    this.ball.rotation.x += b.vx * 0.05
    this.ball.rotation.z -= b.vy * 0.05

    for (const p of world.players) {
      const n = this.ensurePlayer(p.id, p.team, p.role, p.number)
      n.group.position.set(p.x, 0, p.y)
      n.group.rotation.y = -p.heading
      n.group.visible = p.id !== hideId
      n.ring.visible = p.id === controlledId && showControlledRing

      // Procedural run cycle: stride frequency & amplitude scale with speed.
      const spd = p.speed
      n.phase += (1.2 + spd * 1.15) * dt
      if (p.sliding) {
        n.group.position.y = -0.25
        n.legL.rotation.x = 1.2
        n.legR.rotation.x = 0.6
        n.armL.rotation.x = -0.7
        n.armR.rotation.x = -0.9
      } else {
        n.group.position.y = 0
        const swing = Math.min(0.95, 0.12 + spd * 0.11)
        const sw = Math.sin(n.phase) * swing
        n.legL.rotation.x = sw
        n.legR.rotation.x = -sw
        n.armL.rotation.x = -sw * 0.75
        n.armR.rotation.x = sw * 0.75
      }
    }
  }
}
