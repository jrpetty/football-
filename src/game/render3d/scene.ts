import * as THREE from 'three'
import { Sky } from 'three/examples/jsm/objects/Sky.js'
import { BALL, FIELD, NET, QUALITY, WALL } from '../config'
import type { Quality } from '../config'
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
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js'
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
  // The back of each net, and how far it is currently pushed out.
  private nets: Record<string, {
    mesh: THREE.Mesh
    rest: Float32Array
    dir: number
    bulge: number
    at: { y: number; z: number }
  }> = {}
  private trailLine: THREE.Line | null = null

  private q: (typeof QUALITY)[Quality]

  constructor(container: HTMLElement, quality: Quality = 'medium') {
    const q = QUALITY[quality]
    this.q = q
    // Antialiasing is fixed at construction — WebGL cannot turn MSAA on and off
    // on a live context — so the setting takes effect on the next match rather
    // than instantly. Everything else here could change live; keeping them
    // together is worth more than the one that can't.
    this.renderer = new THREE.WebGLRenderer({ antialias: q.antialias, powerPreference: 'high-performance' })
    // The single biggest lever there is. A 4K display at devicePixelRatio 2 is
    // four times the pixels of 1, and no amount of geometry work touches that.
    this.renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, q.pixelRatio))
    this.renderer.shadowMap.enabled = q.shadows
    // Soft shadows are several taps per pixel. Worth it when there is fill to
    // spare, and the first thing to go when there isn't.
    this.renderer.shadowMap.type = quality === 'high' ? THREE.PCFSoftShadowMap : THREE.PCFShadowMap
    this.renderer.toneMapping = THREE.ACESFilmicToneMapping
    this.renderer.toneMappingExposure = 0.62
    this.renderer.domElement.style.position = 'fixed'
    this.renderer.domElement.style.inset = '0'
    container.appendChild(this.renderer.domElement)
    this.maxAniso = Math.min(this.renderer.capabilities.getMaxAnisotropy(), q.aniso)

    this.scene.fog = new THREE.Fog('#b7d2e6', 95, 240)

    this.buildSkyAndLights()
    this.buildPitch()
    this.buildStadium()
    this.buildWalls()
    this.buildGoals()
    this.buildBall()
    this.buildBallBlob()
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
    const phi = THREE.MathUtils.degToRad(90 - 32)
    const theta = THREE.MathUtils.degToRad(150)
    const sun = new THREE.Vector3().setFromSphericalCoords(1, phi, theta)

    // The sky is baked once into a cube map and used as the scene background.
    //
    // It used to be a 4000-unit sphere carrying the atmospheric-scattering
    // shader, which meant every visible sky pixel re-solved Rayleigh and Mie
    // scattering sixty times a second for a result that is identical every
    // frame — the sun never moves. Rendering it once into a cube map and
    // throwing the sphere away costs one texture and turns the most expensive
    // fragment shader in the scene into a background blit.
    const sky = new Sky()
    sky.scale.setScalar(4000)
    const u = sky.material.uniforms
    u.turbidity.value = 6
    u.rayleigh.value = 1.4
    u.mieCoefficient.value = 0.006
    u.mieDirectionalG.value = 0.8
    u.sunPosition.value.copy(sun)

    const bakeScene = new THREE.Scene()
    bakeScene.add(sky)
    const cube = new THREE.WebGLCubeRenderTarget(256, { generateMipmaps: true, minFilter: THREE.LinearMipmapLinearFilter })
    const cubeCam = new THREE.CubeCamera(1, 10000, cube)
    cubeCam.update(this.renderer, bakeScene)
    this.scene.background = cube.texture
    sky.geometry.dispose()
    sky.material.dispose()

    this.scene.add(new THREE.HemisphereLight('#dcecff', '#3a5a3f', 0.7))
    this.scene.add(new THREE.AmbientLight('#ffffff', 0.18))

    const dir = new THREE.DirectionalLight('#fff4de', 2.4)
    dir.position.copy(sun.clone().multiplyScalar(80)).add(v3(FIELD.length / 2, FIELD.width / 2, 0))
    dir.target.position.copy(v3(FIELD.length / 2, FIELD.width / 2))
    dir.castShadow = this.q.shadows
    // The shadow frustum only has to cover the things that cast — the players,
    // the ball, the goals and the training apparatus, all of which are on the
    // pitch. It was ±45 m, a 90 m box swallowing the whole stadium, at 2048².
    // Fitting it to the pitch instead means each texel covers a third of the
    // ground it used to, so 1024² is now *sharper* than the old 2048² as well
    // as being a quarter of the fill.
    const pad = 6
    dir.shadow.mapSize.set(this.q.shadowMap || 1024, this.q.shadowMap || 1024)
    const cam = dir.shadow.camera as THREE.OrthographicCamera
    const half = Math.max(FIELD.length, FIELD.width) / 2 + pad
    cam.near = 1
    cam.far = 220
    cam.left = -half
    cam.right = half
    cam.top = half
    cam.bottom = -half
    cam.updateProjectionMatrix()
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

    // The four advertising hoardings.
    //
    // These were a box with a six-material array each — one material per face,
    // which three.js draws as one call per face. Four boards were twenty-four
    // draw calls to put a hundred and ninety triangles on screen, and they
    // never move.
    //
    // So: every board's carcass merges into one geometry with one dark
    // material, and every board's front face merges into another with the ad
    // texture, its repeat baked into the UVs rather than carried on a
    // per-board texture. Twenty-four calls become two.
    const adCanvas = adTexture()
    const bh = 1.15
    const carcasses: THREE.BufferGeometry[] = []
    const faces: THREE.BufferGeometry[] = []
    const mkBoard = (w: number, x: number, z: number, rotY: number) => {
      const box = new THREE.BoxGeometry(w, bh, 0.28)
      box.rotateY(rotY)
      box.translate(x, bh / 2, z)
      carcasses.push(box)

      // The face that carries the advert, a whisker proud of the carcass.
      const face = new THREE.PlaneGeometry(w, bh)
      // One texture pass per ~34 m keeps the sponsor lettering legible. With a
      // shared texture that has to live in the UVs.
      const repeat = Math.max(1, Math.round(w / 34))
      const uv = face.getAttribute('uv') as THREE.BufferAttribute
      for (let i = 0; i < uv.count; i++) uv.setX(i, uv.getX(i) * repeat)
      uv.needsUpdate = true
      face.rotateY(rotY)
      face.translate(x + Math.sin(rotY) * 0.145, bh / 2, z + Math.cos(rotY) * 0.145)
      faces.push(face)
    }
    mkBoard(L + m * 2, cx, -m, Math.PI)
    mkBoard(L + m * 2, cx, W + m, 0)
    mkBoard(W + m * 2, -m, cz, Math.PI / 2)
    mkBoard(W + m * 2, L + m, cz, -Math.PI / 2)

    const carcass = new THREE.Mesh(
      mergeGeometries(carcasses)!,
      new THREE.MeshStandardMaterial({ color: '#0d1117', roughness: 0.8 }),
    )
    // Not a caster. The boards ring the pitch outside the shadow frustum, so
    // every one of them was drawn into the depth map each frame to produce a
    // shadow that lands on nothing anyone can see.
    carcass.receiveShadow = true
    this.scene.add(carcass)

    const adTex = this.tex(adCanvas)
    adTex.wrapS = adTex.wrapT = THREE.RepeatWrapping
    const adBoards = new THREE.Mesh(
      mergeGeometries(faces)!,
      new THREE.MeshStandardMaterial({ map: adTex, roughness: 0.45 }),
    )
    adBoards.receiveShadow = true
    this.scene.add(adBoards)

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
      const pole = new THREE.Mesh(new THREE.CylinderGeometry(0.32, 0.5, 26, 6), poleMat)
      pole.position.set(px, 13, pz)
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
      const bar = new THREE.Mesh(new THREE.CylinderGeometry(0.1, 0.1, b - a, 8), white)
      bar.position.copy(v3(goalX, (a + b) / 2, h))
      bar.rotation.x = Math.PI / 2
      bar.castShadow = true
      this.scene.add(bar)
      const depth = FIELD.goalDepth
      const dir = team === 'home' ? -1 : 1
      // Roof and sides stay a simple shell — nothing hits them hard enough to
      // be worth simulating.
      const shell = new THREE.Mesh(new THREE.BoxGeometry(depth, h, b - a), netMat)
      shell.position.copy(v3(goalX + (dir * depth) / 2, (a + b) / 2, h / 2))
      this.scene.add(shell)

      // The back of the net is the one you actually hit, so it gets enough
      // vertices to take the shape of a shot.
      const back = new THREE.Mesh(
        new THREE.PlaneGeometry(b - a, h, 22, 14),
        new THREE.MeshBasicMaterial({
          color: '#ffffff',
          transparent: true,
          opacity: 0.3,
          side: THREE.DoubleSide,
          depthWrite: false,
          wireframe: true,
        }),
      )
      back.position.copy(v3(goalX + dir * depth, (a + b) / 2, h / 2))
      back.rotation.y = Math.PI / 2
      this.scene.add(back)
      this.nets[team] = {
        mesh: back,
        rest: Float32Array.from(back.geometry.getAttribute('position').array as Float32Array),
        dir,
        bulge: 0,
        at: { y: 0, z: 0 },
      }
    }
  }

  // ---- ball ----

  // A painted circle under the ball, for when there is no shadow map to cast a
  // real one.
  //
  // Turning shadows off is the cheapest way to buy frames, but the ball's
  // shadow is not decoration here — it is the only thing that tells you where a
  // ball in flight is going to land, and this is a game about judging exactly
  // that. So Low keeps the one shadow that is load-bearing and pays a single
  // draw call for it.
  private ballBlob: THREE.Mesh | null = null

  private buildBallBlob() {
    if (this.q.shadows) return
    const geo = new THREE.CircleGeometry(BALL.radius * 1.6, 16)
    geo.rotateX(-Math.PI / 2)
    this.ballBlob = new THREE.Mesh(
      geo,
      new THREE.MeshBasicMaterial({ color: '#000000', transparent: true, opacity: 0.34, depthWrite: false }),
    )
    this.scene.add(this.ballBlob)
  }

  private syncBallBlob(x: number, y: number, z: number) {
    const b = this.ballBlob
    if (!b) return
    // Wider and fainter the higher it is, the way a real one goes.
    const lift = Math.min(1, z / 6)
    b.position.set(x, 0.02, y)
    b.scale.setScalar(1 + lift * 0.8)
    ;(b.material as THREE.MeshBasicMaterial).opacity = 0.34 * (1 - lift * 0.55)
  }

  private buildBall() {
    const map = this.tex(ballTexture())
    this.ball = new THREE.Mesh(
      new THREE.SphereGeometry(BALL.radius, 20, 14),
      new THREE.MeshStandardMaterial({ map, roughness: 0.38, metalness: 0.03 }),
    )
    this.ball.castShadow = true
    this.scene.add(this.ball)
  }

  // Turn the ball at the rate the simulation says it is actually turning.
  //
  // This used to be a tumble faked from the ball's velocity, which meant none of
  // the spin the physics works so hard on was ever on screen: a back-spun chip
  // and a driven ball looked identical in the air. Both spins are carried as the
  // speed of the ball's surface in m/s, so the angular rate is simply that
  // divided by the radius, and the two combine into one axis:
  //
  //   vSpin — about the horizontal axis across the direction of travel, so
  //     topspin rolls the ball forwards over itself and backspin visibly winds
  //     it backwards under itself on the way down.
  //   spin  — about the vertical axis, the rotation that bends the flight.
  //
  // Nothing is scaled for looks. At 30 m/s the ball is turning ~40 times a
  // second and will strobe, exactly as a real one does on camera.
  private ballSpinAxis = new THREE.Vector3()
  private ballSpinQ = new THREE.Quaternion()

  private spinBall(b: World['ball'], dt: number) {
    const hs = Math.hypot(b.vx, b.vy)
    const w = this.ballSpinAxis.set(0, -b.spin / BALL.radius, 0)
    if (hs > 1e-3) {
      // up × d, where d is the horizontal travel direction. Getting this the
      // way round matters and is easy to talk yourself into backwards: a ball
      // rolling forwards has its *top* moving at twice the ball's speed and its
      // contact point standing still, and the opposite axis gives you exactly
      // the opposite — a ball that looks like it's spinning back at you while
      // running away.
      const dx = b.vx / hs
      const dz = b.vy / hs
      const rate = b.vSpin / BALL.radius
      w.x += dz * rate
      w.z += -dx * rate
    }
    const mag = w.length()
    if (mag < 1e-4) return
    w.divideScalar(mag)
    this.ballSpinQ.setFromAxisAngle(w, mag * dt)
    this.ball.quaternion.premultiply(this.ballSpinQ)
  }

  // ---- name tags ----
  //
  // You could not tell who anybody was. Every shirt is a seat somebody may or
  // may not be sitting in, so "who is that" is not a cosmetic question here —
  // it is the difference between a defender you have to beat and a mannequin.
  //
  // Sprites rather than anything parented to the rig: a sprite always faces the
  // camera, and the rig's group is rotated by the player's heading and scaled
  // to a footballer's height, both of which a tag has to ignore.
  private tags = new Map<number, { sprite: THREE.Sprite; text: string; claimed: boolean }>()

  private tagTexture(text: string, claimed: boolean, team: 'home' | 'away'): HTMLCanvasElement {
    const c = document.createElement('canvas')
    c.width = 256
    c.height = 64
    const g = c.getContext('2d')!
    g.font = '600 34px system-ui, sans-serif'
    g.textAlign = 'center'
    g.textBaseline = 'middle'
    // An empty shirt is drawn faintly and in outline: present, but plainly not
    // a person.
    if (claimed) {
      g.fillStyle = 'rgba(8,12,20,0.55)'
      roundRectPath(g, 8, 12, 240, 40, 10)
      g.fill()
      g.fillStyle = team === 'home' ? '#bfe0ff' : '#ffd2d2'
      g.fillText(text, 128, 33)
    } else {
      g.strokeStyle = 'rgba(255,255,255,0.35)'
      g.lineWidth = 2
      g.font = '500 28px system-ui, sans-serif'
      g.fillStyle = 'rgba(255,255,255,0.42)'
      g.fillText(text, 128, 33)
    }
    return c
  }

  private syncTags(world: World, hideId: number, replaying: boolean) {
    for (const p of world.players) {
      const text = world.nameFor(p)
      const claimed = world.isClaimed(p)
      let tag = this.tags.get(p.id)
      if (!tag || tag.text !== text || tag.claimed !== claimed) {
        tag?.sprite.material.map?.dispose()
        tag?.sprite.material.dispose()
        if (!tag) {
          const sprite = new THREE.Sprite()
          sprite.scale.set(2.2, 0.55, 1)
          this.scene.add(sprite)
          tag = { sprite, text, claimed }
          this.tags.set(p.id, tag)
        }
        tag.text = text
        tag.claimed = claimed
        tag.sprite.material = new THREE.SpriteMaterial({
          map: this.tex(this.tagTexture(text, claimed, p.team)),
          transparent: true,
          depthTest: false,
          depthWrite: false,
          // Exempt from the filmic tone mapping the stadium goes through. A
          // label is not lit by the sun and shouldn't be graded like it is —
          // run through ACES it comes out grey and barely readable.
          toneMapped: false,
        })
      }
      const rp = p.renderPos(world.renderAlpha)
      // Above the head, and it rises with a jump because the head does.
      tag.sprite.position.set(rp.x, rp.z + 2.35, rp.y)
      // Your own tag is hidden in first person for the same reason your body
      // is: you are looking out of it.
      tag.sprite.visible = p.id !== hideId && !replaying
    }
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
  // Gates on the floor: a pair of cones you have to drive the ball between. Kept
  // deliberately short, because the drill is about keeping the ball down and a
  // tall marker would let you cheat it by eye.
  private gateCones: THREE.Mesh[] = []
  private syncGates(gates: World['drills'] extends null ? never : { x: number; y: number; width: number; passed: boolean }[]) {
    const need = gates.length * 2
    while (this.gateCones.length < need) {
      const cone = new THREE.Mesh(
        new THREE.ConeGeometry(0.16, 0.44, 8),
        new THREE.MeshStandardMaterial({ color: '#ff9d3d', emissive: '#c25c00', emissiveIntensity: 0.35, roughness: 0.6 }),
      )
      this.scene.add(cone)
      this.gateCones.push(cone)
    }
    for (let i = 0; i < this.gateCones.length; i++) this.gateCones[i].visible = i < need
    gates.forEach((g, i) => {
      for (const side of [0, 1]) {
        const c = this.gateCones[i * 2 + side]
        c.position.set(g.x, 0.22, g.y + (side ? g.width / 2 : -g.width / 2))
        const mat = c.material as THREE.MeshStandardMaterial
        mat.color.set(g.passed ? '#8dffa8' : '#ff9d3d')
        mat.emissive.set(g.passed ? '#42ff77' : '#c25c00')
      }
    })
  }

  // The defensive wall for the curling drill — real bodies, standing where the
  // ball cannot go through them.
  private wallMen: THREE.Mesh[] = []
  private syncWall(wall: { x: number; y: number }[]) {
    while (this.wallMen.length < wall.length) {
      const m = new THREE.Mesh(
        new THREE.CapsuleGeometry(0.32, 1.15, 3, 10),
        new THREE.MeshStandardMaterial({ color: '#2a3340', roughness: 0.85 }),
      )
      m.castShadow = true
      this.scene.add(m)
      this.wallMen.push(m)
    }
    for (let i = 0; i < this.wallMen.length; i++) this.wallMen[i].visible = i < wall.length
    wall.forEach((m, i) => this.wallMen[i].position.set(m.x, 0.9, m.y))
  }

  // Push the mesh out where the ball went in, then let it spring back.
  //
  // The displacement falls off with distance from the impact, so a shot into
  // the top corner bulges the top corner and the rest of the net barely moves —
  // which is what makes it read as the net taking the shape of *that* shot
  // rather than a generic wobble.
  private syncNets(world: World, dt: number) {
    const hit = world.netHit
    for (const team of ['home', 'away'] as const) {
      const n = this.nets[team]
      if (!n) continue
      if (hit && hit.team === team && hit.age < 0.05 && n.bulge < hit.power * NET.give * 26) {
        n.bulge = Math.min(NET.maxGive, hit.power * NET.give * 26)
        n.at = { y: hit.y, z: hit.z }
      }
      if (n.bulge <= 1e-4) continue
      n.bulge = Math.max(0, n.bulge - n.bulge * NET.settle * dt - 0.02 * dt)

      const attr = n.mesh.geometry.getAttribute('position') as THREE.BufferAttribute
      const arr = attr.array as Float32Array
      const [pa, pb] = F.goalPostYs()
      const cy = (pa + pb) / 2
      for (let i = 0; i < arr.length; i += 3) {
        // Plane local x runs along the goal mouth, local y is height.
        const wy = cy + n.rest[i]
        const wz = FIELD.goalHeight / 2 + n.rest[i + 1]
        const d = Math.hypot(wy - n.at.y, wz - n.at.z)
        const fall = Math.exp(-(d * d) / (NET.spread * NET.spread))
        // Local z is the plane's own normal, which after the quarter turn points
        // out through the back of the goal.
        arr[i + 2] = n.rest[i + 2] + n.bulge * fall * n.dir * -1
      }
      attr.needsUpdate = true
    }
  }

  // The permanent training apparatus.
  //
  // Built as instanced meshes rather than a group of meshes per dummy. Eleven
  // dummies made of five parts each was sixty-six draw calls and twenty-three
  // thousand triangles a frame — forty per cent of the entire scene's geometry,
  // spent on training furniture standing still at the side of the pitch, and
  // doubled again by the shadow pass.
  //
  // Every dummy of a kind is identical, so each material becomes one
  // InstancedMesh and the per-dummy transform — position, and the lean when
  // something knocks it — goes in the instance matrix. Sixty-six calls become
  // seven, and the triangle count falls with the segment counts, which were
  // generous for objects nobody gets closer than a couple of metres to.
  private dummyParts: { mesh: THREE.InstancedMesh; leans: boolean }[] = []
  private dummyCount = 0
  // Scratch, so a per-frame matrix rebuild doesn't allocate.
  private mTmp = new THREE.Matrix4()
  private mLean = new THREE.Matrix4()
  private vAxis = new THREE.Vector3()

  private buildDummies(list: { kind: 'slalom' | 'wall' }[]) {
    const slalom: number[] = []
    const wall: number[] = []
    list.forEach((d, i) => (d.kind === 'slalom' ? slalom : wall).push(i))
    this.dummyCount = list.length

    // One instanced mesh per (part, kind). `index` maps instance slot → dummy.
    const add = (
      geo: THREE.BufferGeometry,
      mat: THREE.Material,
      idx: number[],
      leans: boolean,
      shadow: boolean,
    ) => {
      if (!idx.length) return
      const mesh = new THREE.InstancedMesh(geo, mat, idx.length)
      mesh.castShadow = shadow
      mesh.instanceMatrix.setUsage(THREE.DynamicDrawUsage)
      mesh.frustumCulled = false
      mesh.userData.idx = idx
      this.scene.add(mesh)
      this.dummyParts.push({ mesh, leans })
    }

    const baseMat = new THREE.MeshStandardMaterial({ color: '#22262c', roughness: 0.9 })
    const baseGeo = new THREE.CylinderGeometry(0.26, 0.32, 0.07, 10)
    baseGeo.translate(0, 0.035, 0)
    // The base sits flat however hard the body above it is knocked, so it is a
    // separate instanced mesh that never takes the lean.
    add(baseGeo, baseMat, [...slalom, ...wall], false, false)

    // ---- slalom: a banded pole ----
    // The three bands merge into one geometry: they share a material and never
    // move relative to each other, so there is no reason for them to be three
    // draws.
    const pole = new THREE.CylinderGeometry(0.055, 0.065, 1.75, 8)
    pole.translate(0, 0.9, 0)
    add(pole, new THREE.MeshStandardMaterial({ color: '#ff9d3d', roughness: 0.6 }), slalom, true, true)
    const bands: THREE.BufferGeometry[] = []
    for (let i = 0; i < 3; i++) {
      const band = new THREE.CylinderGeometry(0.075, 0.075, 0.16, 8)
      band.translate(0, 0.45 + i * 0.5, 0)
      bands.push(band)
    }
    add(
      mergeGeometries(bands)!,
      new THREE.MeshStandardMaterial({ color: '#f2f5ff', roughness: 0.7 }),
      slalom,
      true,
      false,
    )

    // ---- wall: a bibbed mannequin ----
    const kitMat = new THREE.MeshStandardMaterial({ color: '#5b6779', roughness: 0.85 })
    const torso = new THREE.CapsuleGeometry(0.3, 1.0, 3, 10)
    torso.translate(0, 0.95, 0)
    const guard = new THREE.BoxGeometry(0.52, 0.15, 0.16)
    guard.translate(0, 0.66, 0.25)
    add(mergeGeometries([torso, guard])!, kitMat, wall, true, true)

    const bib = new THREE.CapsuleGeometry(0.315, 0.5, 3, 10)
    bib.translate(0, 1.12, 0)
    // No shadow: it is a skin over the torso, which is already casting one.
    add(bib, new THREE.MeshStandardMaterial({ color: '#e8d43a', roughness: 0.7 }), wall, true, false)

    const head = new THREE.SphereGeometry(0.16, 8, 6)
    head.translate(0, 1.66, 0)
    add(head, new THREE.MeshStandardMaterial({ color: '#c8a284', roughness: 0.8 }), wall, true, false)
  }

  private syncDummies(world: World) {
    const list = world.drills?.dummies
    if (!list || !list.length) return
    if (!this.dummyParts.length || this.dummyCount !== list.length) {
      if (this.dummyParts.length) return // shape changed mid-match; nothing does this
      this.buildDummies(list)
    }
    for (const part of this.dummyParts) {
      const idx = part.mesh.userData.idx as number[]
      for (let slot = 0; slot < idx.length; slot++) {
        const m = list[idx[slot]]
        this.mTmp.makeTranslation(m.x, 0, m.y)
        if (part.leans && m.lean > 1e-4) {
          // Lean away from whatever hit it, about the axis across that direction.
          this.vAxis.set(m.ly, 0, -m.lx).normalize()
          this.mLean.makeRotationAxis(this.vAxis, -m.lean)
          this.mTmp.multiply(this.mLean)
        }
        part.mesh.setMatrixAt(slot, this.mTmp)
      }
      part.mesh.instanceMatrix.needsUpdate = true
    }
  }

  private syncDrills(world: World) {
    const d = world.drills
    if (!d) return

    while (this.targetRings.length < d.targets.length) {
      const ring = new THREE.Mesh(
        new THREE.TorusGeometry(0.55, 0.07, 6, 20),
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
    for (let i = d.targets.length; i < this.targetRings.length; i++) this.targetRings[i].visible = false
    d.targets.forEach((t, i) => {
      this.targetRings[i].visible = true
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

    this.syncGates(d.gates)
    this.syncWall(d.wall)

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

  sync(
    world: World,
    controlledId: number,
    showControlledRing: boolean,
    hideId = -1,
    dt = 0.016,
    // Tags are off during a replay: it is a broadcast cutaway, and the same
    // reason the HUD goes away applies to the labels.
    hideTags = false,
  ) {
    // Draw where the ball *is* between physics steps, not where it was at the
    // last one — this is what removes the stepped, laggy look at low frame rates.
    const a = world.renderAlpha
    const b = world.ball
    const bp = b.renderPos(a)
    this.ball.position.set(bp.x, bp.z + BALL.radius, bp.y)
    this.syncBallBlob(bp.x, bp.y, bp.z)
    this.spinBall(b, dt)

    this.syncNets(world, dt)
    this.syncDrills(world)
    this.syncDummies(world)

    for (const p of world.players) {
      const rig = this.ensurePlayer(p.id, p.team, p.role, p.number)
      const rp = p.renderPos(a)
      rig.pose(p, rp.x, rp.y, dt, rp.z)
      rig.setVisible(p.id !== hideId)
      rig.ring.visible = p.id === controlledId && showControlledRing
    }
    this.syncTags(world, hideId, hideTags)
  }
}

// Local to the tag canvas; the HUD has its own.
function roundRectPath(g: CanvasRenderingContext2D, x: number, y: number, w: number, h: number, r: number) {
  g.beginPath()
  g.moveTo(x + r, y)
  g.arcTo(x + w, y, x + w, y + h, r)
  g.arcTo(x + w, y + h, x, y + h, r)
  g.arcTo(x, y + h, x, y, r)
  g.arcTo(x, y, x + w, y, r)
  g.closePath()
}
