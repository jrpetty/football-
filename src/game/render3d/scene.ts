import * as THREE from 'three'
import { Sky } from 'three/examples/jsm/objects/Sky.js'
import { BALL, FIELD, WALL } from '../config'
import * as F from '../match/field'
import type { World } from '../match/world'
import {
  adTexture,
  ballTexture,
  crowdTexture,
  grassTexture,
  normalFromCanvas,
  pitchOverlayTexture,
} from './textures'
import { PlayerRig, makeKitMaterials } from './rig'

// Simulation → Three.js coordinate map: the pitch plane (sim x, y) becomes the
// ground plane (X, Z); ball height (sim z) becomes world Y (up).
const v3 = (x: number, y: number, z = 0) => new THREE.Vector3(x, z, y)

// Builds and owns the WebGL scene and syncs every mesh from World each frame.
// Reads the simulation only — never writes it. All surfaces are textured from
// procedurally generated canvases (see ./textures) so the bundle stays
// self-contained while still looking like a real stadium.
export class Scene3D {
  readonly scene = new THREE.Scene()
  readonly renderer: THREE.WebGLRenderer
  private ball!: THREE.Mesh
  private players = new Map<number, PlayerRig>()
  private kitMats = new Map<string, ReturnType<typeof makeKitMaterials>>()
  private maxAniso = 4
  private targetRings: THREE.Mesh[] = []
  private trailLine: THREE.Line | null = null

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
    this.maxAniso = this.renderer.capabilities.getMaxAnisotropy()

    this.scene.fog = new THREE.Fog('#b7d2e6', 95, 240)

    this.buildSkyAndLights()
    this.buildPitch()
    this.buildStadium()
    this.buildWalls()
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

  // Wrap a generated canvas as a colour texture.
  private tex(c: HTMLCanvasElement, repeatX = 1, repeatY = 1): THREE.CanvasTexture {
    const t = new THREE.CanvasTexture(c)
    t.colorSpace = THREE.SRGBColorSpace
    t.anisotropy = this.maxAniso
    if (repeatX !== 1 || repeatY !== 1) {
      t.wrapS = t.wrapT = THREE.RepeatWrapping
      t.repeat.set(repeatX, repeatY)
    }
    return t
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
    const phi = THREE.MathUtils.degToRad(90 - 32)
    const theta = THREE.MathUtils.degToRad(150)
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

  // ---- pitch: tiled turf + a crisp markings decal on top ----

  private buildPitch() {
    const L = FIELD.length
    const W = FIELD.width
    const grass = grassTexture()
    const grassNormal = normalFromCanvas(grass)

    const mkNormal = (rx: number, ry: number) => {
      const n = new THREE.CanvasTexture(grassNormal)
      n.wrapS = n.wrapT = THREE.RepeatWrapping
      n.repeat.set(rx, ry)
      n.anisotropy = this.maxAniso
      return n
    }

    // Surrounding run-off, duller and darker than the playing surface.
    const outR = 26
    const under = new THREE.Mesh(
      new THREE.PlaneGeometry(L + 130, W + 130),
      new THREE.MeshStandardMaterial({
        map: this.tex(grass, outR, outR),
        normalMap: mkNormal(outR, outR),
        normalScale: new THREE.Vector2(0.5, 0.5),
        color: '#7d8f7a',
        roughness: 1,
      }),
    )
    under.rotation.x = -Math.PI / 2
    under.position.set(L / 2, -0.03, W / 2)
    under.receiveShadow = true
    this.scene.add(under)

    const rx = L / 3.6
    const ry = W / 3.6
    const pitch = new THREE.Mesh(
      new THREE.PlaneGeometry(L, W),
      new THREE.MeshStandardMaterial({
        map: this.tex(grass, rx, ry),
        normalMap: mkNormal(rx, ry),
        normalScale: new THREE.Vector2(0.85, 0.85),
        roughness: 0.95,
      }),
    )
    pitch.rotation.x = -Math.PI / 2
    pitch.position.set(L / 2, 0, W / 2)
    pitch.receiveShadow = true
    this.scene.add(pitch)

    // Markings + mowing stripes as a transparent decal, so the lines stay sharp
    // regardless of how densely the turf beneath is tiled.
    const overlay = new THREE.Mesh(
      new THREE.PlaneGeometry(L, W),
      new THREE.MeshStandardMaterial({
        map: this.tex(pitchOverlayTexture()),
        transparent: true,
        depthWrite: false,
        roughness: 0.9,
        polygonOffset: true,
        polygonOffsetFactor: -2,
      }),
    )
    overlay.rotation.x = -Math.PI / 2
    overlay.position.set(L / 2, 0.012, W / 2)
    overlay.receiveShadow = true
    this.scene.add(overlay)
  }

  // ---- stadium (boards, stands, floodlights) ----

  private buildStadium() {
    const L = FIELD.length
    const W = FIELD.width
    const cx = L / 2
    const cz = W / 2
    const m = FIELD.margin

    const adCanvas = adTexture()
    const bh = 1.15
    const mkBoard = (w: number, x: number, z: number, rotY: number) => {
      // One texture pass per ~34m keeps the sponsor lettering legible.
      const t = this.tex(adCanvas, Math.max(1, Math.round(w / 34)), 1)
      const b = new THREE.Mesh(
        new THREE.BoxGeometry(w, bh, 0.28),
        [
          new THREE.MeshStandardMaterial({ color: '#0d1117', roughness: 0.8 }),
          new THREE.MeshStandardMaterial({ color: '#0d1117', roughness: 0.8 }),
          new THREE.MeshStandardMaterial({ color: '#20262f', roughness: 0.8 }),
          new THREE.MeshStandardMaterial({ color: '#0d1117', roughness: 0.8 }),
          new THREE.MeshStandardMaterial({ map: t, roughness: 0.45 }),
          new THREE.MeshStandardMaterial({ color: '#0d1117', roughness: 0.8 }),
        ],
      )
      b.position.set(x, bh / 2, z)
      b.rotation.y = rotY
      b.castShadow = true
      b.receiveShadow = true
      this.scene.add(b)
    }
    mkBoard(L + m * 2, cx, -m, Math.PI)
    mkBoard(L + m * 2, cx, W + m, 0)
    mkBoard(W + m * 2, -m, cz, Math.PI / 2)
    mkBoard(W + m * 2, L + m, cz, -Math.PI / 2)

    // Raked crowd stands forming a shallow bowl.
    const crowdCanvas = crowdTexture()
    const standDepth = 28
    const standH = 16
    const gap = m + 2.5
    const addStand = (w: number, x: number, z: number, rotY: number) => {
      const t = this.tex(crowdCanvas, Math.max(1, Math.round(w / 14)), 1)
      const s = new THREE.Mesh(
        new THREE.PlaneGeometry(w, standDepth),
        // The stands face away from the sun, so a little self-illumination keeps
        // the crowd readable instead of a black wall.
        new THREE.MeshStandardMaterial({
          map: t,
          emissiveMap: t,
          emissive: '#ffffff',
          emissiveIntensity: 0.75,
          roughness: 1,
          side: THREE.DoubleSide,
        }),
      )
      s.position.set(x, standH / 2, z)
      s.rotation.order = 'YXZ'
      s.rotation.y = rotY
      s.rotation.x = -Math.PI / 2 + 0.62
      s.receiveShadow = true
      this.scene.add(s)
    }
    addStand(L + m * 2 + standDepth, cx, -gap - standDepth * 0.32, 0)
    addStand(L + m * 2 + standDepth, cx, W + gap + standDepth * 0.32, Math.PI)
    addStand(W + m * 2 + standDepth, -gap - standDepth * 0.32, cz, Math.PI / 2)
    addStand(W + m * 2 + standDepth, L + gap + standDepth * 0.32, cz, -Math.PI / 2)

    // Floodlight pylons at the four corners.
    const poleMat = new THREE.MeshStandardMaterial({ color: '#7c8794', metalness: 0.6, roughness: 0.45 })
    const lampMat = new THREE.MeshStandardMaterial({
      color: '#fdfbe6',
      emissive: '#fff6c8',
      emissiveIntensity: 1.5,
      roughness: 0.3,
    })
    for (const [sx, sz] of [[-1, -1], [1, -1], [-1, 1], [1, 1]] as const) {
      const px = cx + sx * (L / 2 + m + 5)
      const pz = cz + sz * (W / 2 + m + 5)
      const pole = new THREE.Mesh(new THREE.CylinderGeometry(0.32, 0.5, 26, 10), poleMat)
      pole.position.set(px, 13, pz)
      pole.castShadow = true
      this.scene.add(pole)
      const rig = new THREE.Mesh(new THREE.BoxGeometry(4.4, 1.7, 0.5), lampMat)
      rig.position.set(px, 25.6, pz)
      rig.lookAt(v3(cx, W / 2, 2))
      this.scene.add(rig)
    }
  }

  // ---- the enclosing barrier ----

  // A glass cage around the pitch. The ball rebounds off it rather than going
  // out for a throw-in, so the boundary needs to be visible without hiding play:
  // a faint pane that fades out with height, plus a bright rail along the top.
  private buildWalls() {
    const L = FIELD.length
    const W = FIELD.width
    const h = WALL.height
    const [postA, postB] = F.goalPostYs()

    const paneMat = new THREE.MeshPhysicalMaterial({
      color: '#cfe8ff',
      transparent: true,
      opacity: 0.1,
      roughness: 0.06,
      metalness: 0,
      transmission: 0.6,
      side: THREE.DoubleSide,
      depthWrite: false,
    })
    const railMat = new THREE.MeshStandardMaterial({
      color: '#dbe7f5',
      emissive: '#7fb2ff',
      emissiveIntensity: 0.35,
      roughness: 0.35,
      metalness: 0.4,
    })

    const pane = (w: number, cx: number, cz: number, rotY: number) => {
      const m = new THREE.Mesh(new THREE.PlaneGeometry(w, h), paneMat)
      m.position.set(cx, h / 2, cz)
      m.rotation.y = rotY
      this.scene.add(m)
      const rail = new THREE.Mesh(new THREE.BoxGeometry(w, 0.12, 0.12), railMat)
      rail.position.set(cx, h, cz)
      rail.rotation.y = rotY
      this.scene.add(rail)
    }

    // Touchlines run the full length.
    pane(L, L / 2, 0, 0)
    pane(L, L / 2, W, 0)
    // Goal lines are walled either side of the mouth, and above the crossbar.
    for (const gx of [0, L]) {
      pane(postA, gx, postA / 2, Math.PI / 2)
      pane(W - postB, gx, (postB + W) / 2, Math.PI / 2)
      const over = new THREE.Mesh(
        new THREE.PlaneGeometry(postB - postA, h - FIELD.goalHeight),
        paneMat,
      )
      over.position.set(gx, FIELD.goalHeight + (h - FIELD.goalHeight) / 2, (postA + postB) / 2)
      over.rotation.y = Math.PI / 2
      this.scene.add(over)
    }
  }

  // ---- goals ----

  private buildGoals() {
    const white = new THREE.MeshStandardMaterial({ color: '#f4f6fa', metalness: 0.25, roughness: 0.4 })
    const netMat = new THREE.MeshBasicMaterial({
      color: '#ffffff',
      transparent: true,
      opacity: 0.15,
      side: THREE.DoubleSide,
      depthWrite: false,
    })
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
    const map = this.tex(ballTexture())
    this.ball = new THREE.Mesh(
      new THREE.SphereGeometry(BALL.radius, 32, 24),
      new THREE.MeshStandardMaterial({ map, roughness: 0.38, metalness: 0.03 }),
    )
    this.ball.castShadow = true
    this.scene.add(this.ball)
  }

  // ---- players ----

  private ensurePlayer(id: number, team: 'home' | 'away', role: string, num: number): PlayerRig {
    const existing = this.players.get(id)
    if (existing) return existing
    const key = `${team}:${role === 'GK' ? 'GK' : 'OUT'}`
    let mats = this.kitMats.get(key)
    if (!mats) {
      mats = makeKitMaterials(team, role)
      this.kitMats.set(key, mats)
    }
    const rig = new PlayerRig(mats, team, role, num)
    this.scene.add(rig.group)
    this.players.set(id, rig)
    return rig
  }

  // ---- training apparatus ----

  // Hoops hung in the goal to aim at, and a line tracing the flight of the last
  // strike. Seeing the shape of the ball you just hit is what makes the flick
  // learnable — otherwise you're guessing at what a given wrist movement did.
  private syncDrills(world: World) {
    const d = world.drills
    if (!d) return

    while (this.targetRings.length < d.targets.length) {
      const ring = new THREE.Mesh(
        new THREE.TorusGeometry(0.55, 0.07, 10, 28),
        new THREE.MeshStandardMaterial({
          color: '#ffd85e',
          emissive: '#ffb020',
          emissiveIntensity: 0.7,
          roughness: 0.4,
        }),
      )
      this.scene.add(ring)
      this.targetRings.push(ring)
    }
    d.targets.forEach((t, i) => {
      const ring = this.targetRings[i]
      ring.position.set(t.x, t.z, t.y)
      ring.rotation.y = Math.PI / 2
      const mat = ring.material as THREE.MeshStandardMaterial
      if (t.hit) {
        const k = 1 - t.hitAt / 1.2
        mat.color.set('#8dffa8')
        mat.emissive.set('#42ff77')
        mat.emissiveIntensity = 0.5 + k * 2.5
        ring.scale.setScalar(1 + (1 - k) * 0.9)
      } else {
        mat.color.set('#ffd85e')
        mat.emissive.set('#ffb020')
        mat.emissiveIntensity = 0.7
        ring.scale.setScalar(1)
      }
    })

    // The flight path of the last strike, fading out over a few seconds.
    if (!this.trailLine) {
      const geo = new THREE.BufferGeometry()
      geo.setAttribute('position', new THREE.BufferAttribute(new Float32Array(400 * 3), 3))
      this.trailLine = new THREE.Line(
        geo,
        new THREE.LineBasicMaterial({ color: '#7fe7ff', transparent: true, opacity: 0.85 }),
      )
      this.trailLine.frustumCulled = false
      this.scene.add(this.trailLine)
    }
    const line = this.trailLine
    const pos = line.geometry.getAttribute('position') as THREE.BufferAttribute
    const n = Math.min(d.trail.length, 400)
    for (let i = 0; i < n; i++) {
      const p = d.trail[i]
      pos.setXYZ(i, p.x, p.z + BALL.radius, p.y)
    }
    pos.needsUpdate = true
    line.geometry.setDrawRange(0, n)
    line.visible = n > 1
    ;(line.material as THREE.LineBasicMaterial).opacity = 0.85 * Math.max(0, 1 - d.trailAge / 6)
  }

  // ---- per-frame sync ----

  sync(world: World, controlledId: number, showControlledRing: boolean, hideId = -1, dt = 0.016) {
    // Draw where the ball *is* between physics steps, not where it was at the
    // last one — this is what removes the stepped, laggy look at low frame rates.
    const a = world.renderAlpha
    const b = world.ball
    const bp = b.renderPos(a)
    this.ball.position.copy(v3(bp.x, bp.y, bp.z + BALL.radius))
    this.ball.rotation.x += b.vx * 0.05
    this.ball.rotation.z -= b.vy * 0.05

    this.syncDrills(world)

    for (const p of world.players) {
      const rig = this.ensurePlayer(p.id, p.team, p.role, p.number)
      const rp = p.renderPos(a)
      rig.pose(p, rp.x, rp.y, dt)
      rig.setVisible(p.id !== hideId)
      rig.ring.visible = p.id === controlledId && showControlledRing
    }
  }
}
