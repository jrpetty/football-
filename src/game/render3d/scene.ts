import * as THREE from 'three'
import { BALL, FIELD, KITS, PLAYER } from '../config'
import * as F from '../match/field'
import type { World } from '../match/world'

// Simulation → Three.js coordinate map: the pitch plane (sim x, y) becomes the
// ground plane (X, Z); ball height (sim z) becomes world Y (up).
const v3 = (x: number, y: number, z = 0) => new THREE.Vector3(x, z, y)

interface PlayerNodes {
  group: THREE.Group
  ring: THREE.Mesh
  shadow: THREE.Mesh
}

// Builds and owns the WebGL scene, and syncs every mesh from World each frame.
// It only ever reads the simulation — never writes it.
export class Scene3D {
  readonly scene = new THREE.Scene()
  readonly renderer: THREE.WebGLRenderer
  private ball!: THREE.Mesh
  private ballShadow!: THREE.Mesh
  private players = new Map<number, PlayerNodes>()

  constructor(container: HTMLElement) {
    this.renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' })
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2))
    this.renderer.domElement.style.position = 'fixed'
    this.renderer.domElement.style.inset = '0'
    container.appendChild(this.renderer.domElement)

    this.scene.background = new THREE.Color('#8ab6d8')
    this.scene.fog = new THREE.Fog('#8ab6d8', 70, 155)

    this.scene.add(new THREE.HemisphereLight('#cfe4ff', '#2b5533', 1.0))
    const sun = new THREE.DirectionalLight('#fff6e0', 1.5)
    sun.position.set(FIELD.length * 0.3, 60, FIELD.width * 1.2)
    this.scene.add(sun)
    this.scene.add(new THREE.AmbientLight('#ffffff', 0.25))

    this.buildPitch()
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

  // ---- static geometry ----

  private buildPitch() {
    const L = FIELD.length
    const W = FIELD.width
    const surround = new THREE.Mesh(
      new THREE.PlaneGeometry(L + 80, W + 80),
      new THREE.MeshLambertMaterial({ color: '#123a22' }),
    )
    surround.rotation.x = -Math.PI / 2
    surround.position.set(L / 2, -0.02, W / 2)
    this.scene.add(surround)

    const tex = new THREE.CanvasTexture(this.pitchTexture())
    tex.anisotropy = 4
    const pitch = new THREE.Mesh(
      new THREE.PlaneGeometry(L, W),
      new THREE.MeshLambertMaterial({ map: tex }),
    )
    pitch.rotation.x = -Math.PI / 2
    pitch.position.set(L / 2, 0, W / 2)
    this.scene.add(pitch)
  }

  private pitchTexture(): HTMLCanvasElement {
    const L = FIELD.length
    const W = FIELD.width
    const s = 20
    const c = document.createElement('canvas')
    c.width = L * s
    c.height = W * s
    const ctx = c.getContext('2d')!
    const mx = (x: number) => x * s
    const my = (y: number) => y * s

    const stripes = 12
    for (let i = 0; i < stripes; i++) {
      ctx.fillStyle = i % 2 === 0 ? '#2a8a48' : '#248041'
      ctx.fillRect((i / stripes) * c.width, 0, c.width / stripes + 1, c.height)
    }
    ctx.strokeStyle = 'rgba(255,255,255,0.85)'
    ctx.lineWidth = 0.18 * s
    ctx.strokeRect(mx(0.15), my(0.15), mx(L - 0.3), my(W - 0.3))
    ctx.beginPath()
    ctx.moveTo(mx(L / 2), 0)
    ctx.lineTo(mx(L / 2), c.height)
    ctx.stroke()
    ctx.beginPath()
    ctx.arc(mx(L / 2), my(W / 2), FIELD.centerRadius * s, 0, Math.PI * 2)
    ctx.stroke()
    ctx.fillStyle = 'rgba(255,255,255,0.85)'
    ctx.beginPath()
    ctx.arc(mx(L / 2), my(W / 2), 0.25 * s, 0, Math.PI * 2)
    ctx.fill()
    const yMin = (W - FIELD.boxWidth) / 2
    ctx.strokeRect(mx(0.15), my(yMin), mx(FIELD.boxDepth), FIELD.boxWidth * s)
    ctx.strokeRect(mx(L - FIELD.boxDepth - 0.15), my(yMin), FIELD.boxDepth * s, FIELD.boxWidth * s)
    return c
  }

  private buildGoals() {
    const white = new THREE.MeshStandardMaterial({ color: '#f4f6fa', metalness: 0.1, roughness: 0.6 })
    for (const team of ['home', 'away'] as const) {
      const goalX = F.ownGoalLineX(team)
      const [a, b] = F.goalPostYs()
      const h = FIELD.goalHeight
      const postGeo = new THREE.CylinderGeometry(0.11, 0.11, h, 10)
      for (const py of [a, b]) {
        const post = new THREE.Mesh(postGeo, white)
        post.position.copy(v3(goalX, py, h / 2))
        this.scene.add(post)
      }
      const bar = new THREE.Mesh(new THREE.CylinderGeometry(0.11, 0.11, b - a, 10), white)
      bar.position.copy(v3(goalX, (a + b) / 2, h))
      bar.rotation.x = Math.PI / 2
      this.scene.add(bar)
      const depth = FIELD.goalDepth
      const dir = team === 'home' ? -1 : 1
      const net = new THREE.Mesh(
        new THREE.BoxGeometry(depth, h, b - a),
        new THREE.MeshBasicMaterial({ color: '#ffffff', transparent: true, opacity: 0.12, side: THREE.DoubleSide }),
      )
      net.position.copy(v3(goalX + (dir * depth) / 2, (a + b) / 2, h / 2))
      this.scene.add(net)
    }
  }

  private buildBall() {
    this.ball = new THREE.Mesh(
      new THREE.SphereGeometry(BALL.radius, 20, 16),
      new THREE.MeshStandardMaterial({ color: '#f8fafc', roughness: 0.45, metalness: 0.05 }),
    )
    this.scene.add(this.ball)
    this.ballShadow = this.blobShadow(BALL.radius * 1.1)
    this.scene.add(this.ballShadow)
  }

  private blobShadow(r: number): THREE.Mesh {
    const m = new THREE.Mesh(
      new THREE.CircleGeometry(r, 20),
      new THREE.MeshBasicMaterial({ color: '#000', transparent: true, opacity: 0.28 }),
    )
    m.rotation.x = -Math.PI / 2
    return m
  }

  // ---- per-player meshes ----

  private ensurePlayer(id: number, team: 'home' | 'away', role: string): PlayerNodes {
    const existing = this.players.get(id)
    if (existing) return existing
    const kit = KITS[team]
    const bodyColor = role === 'GK' ? kit.gk : kit.primary
    const group = new THREE.Group()

    const torso = new THREE.Mesh(
      new THREE.CylinderGeometry(PLAYER.radius * 0.62, PLAYER.radius * 0.72, 1.05, 12),
      new THREE.MeshStandardMaterial({ color: bodyColor, roughness: 0.7 }),
    )
    torso.position.y = 1.05
    group.add(torso)

    const legs = new THREE.Mesh(
      new THREE.CylinderGeometry(PLAYER.radius * 0.7, PLAYER.radius * 0.6, 0.55, 12),
      new THREE.MeshStandardMaterial({ color: kit.accent, roughness: 0.8 }),
    )
    legs.position.y = 0.32
    group.add(legs)

    const head = new THREE.Mesh(
      new THREE.SphereGeometry(0.26, 16, 12),
      new THREE.MeshStandardMaterial({ color: '#e8b58a', roughness: 0.8 }),
    )
    head.position.y = 1.82
    group.add(head)

    const nose = new THREE.Mesh(
      new THREE.ConeGeometry(0.14, 0.4, 8),
      new THREE.MeshStandardMaterial({ color: kit.secondary, roughness: 0.6 }),
    )
    nose.rotation.z = -Math.PI / 2
    nose.position.set(PLAYER.radius * 0.7, 1.2, 0)
    group.add(nose)

    const ring = new THREE.Mesh(
      new THREE.TorusGeometry(PLAYER.radius + 0.25, 0.07, 8, 28),
      new THREE.MeshBasicMaterial({ color: '#ffffff' }),
    )
    ring.rotation.x = -Math.PI / 2
    ring.position.y = 0.03
    ring.visible = false
    group.add(ring)

    const shadow = this.blobShadow(PLAYER.radius * 1.2)
    this.scene.add(group)
    this.scene.add(shadow)
    const nodes = { group, ring, shadow }
    this.players.set(id, nodes)
    return nodes
  }

  // ---- per-frame sync ----

  sync(world: World, controlledId: number, showControlledRing: boolean, hideId = -1) {
    const b = world.ball
    this.ball.position.copy(v3(b.x, b.y, b.z + BALL.radius))
    this.ball.rotation.x += b.vx * 0.05
    this.ball.rotation.z -= b.vy * 0.05
    this.ballShadow.position.set(b.x, 0.02, b.y)
    this.ballShadow.scale.setScalar(1 + Math.min(b.z / 6, 1) * 0.6)
    ;(this.ballShadow.material as THREE.MeshBasicMaterial).opacity = 0.28 * (1 - Math.min(b.z / 8, 0.6))

    for (const p of world.players) {
      const n = this.ensurePlayer(p.id, p.team, p.role)
      n.group.position.set(p.x, 0, p.y)
      n.group.rotation.y = -p.heading
      n.group.rotation.z = p.sliding ? 0.9 : 0
      n.shadow.position.set(p.x, 0.02, p.y)
      n.group.visible = p.id !== hideId // hide own body in first-person
      n.ring.visible = p.id === controlledId && showControlledRing
    }
  }
}
