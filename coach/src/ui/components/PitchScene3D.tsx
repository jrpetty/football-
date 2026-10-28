// The pitch in 3D: a floodlit stadium with a mown pitch and its markings,
// goals with nets, stands full of supporters, and the two teams standing
// where the radar put them (the player's team as rounded figures, the
// opponent's as square columns, so colour is never the only cue) and the
// ball. The positions come from the parent (an average shape, or a goal
// played back), so the scene is built once and only the figures move. Drag
// to orbit, wheel to zoom; it turns slowly until touched. Loaded on demand.

import { useEffect, useRef, useState } from 'react'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { EffectComposer } from 'three/examples/jsm/postprocessing/EffectComposer.js'
import { OutputPass } from 'three/examples/jsm/postprocessing/OutputPass.js'
import { RenderPass } from 'three/examples/jsm/postprocessing/RenderPass.js'
import { UnrealBloomPass } from 'three/examples/jsm/postprocessing/UnrealBloomPass.js'
import { CSS2DObject, CSS2DRenderer } from 'three/examples/jsm/renderers/CSS2DRenderer.js'
import { PITCH_LENGTH as L, PITCH_WIDTH as WD } from '../../core/pitch/radar.ts'
import { mulberry32 } from '../../sim/syntheticArena.ts'
import { TEAM } from './PitchView.tsx'

type P = readonly [number, number]

const PX = 12 // texture pixels per metre
const RUN_OFF = 8 // grass beyond the lines (metres)

/** Pitch metres to scene coordinates (centre spot at the origin, x along the length, z across). */
const at = (p: P) => [p[0] - L / 2, p[1] - WD / 2] as const

function pitchTexture(): HTMLCanvasElement {
  const w = Math.round((L + 2 * RUN_OFF) * PX)
  const h = Math.round((WD + 2 * RUN_OFF) * PX)
  const c = document.createElement('canvas')
  c.width = w
  c.height = h
  const g = c.getContext('2d')!
  g.fillStyle = '#1d5a2c'
  g.fillRect(0, 0, w, h)
  // Mowing stripes, 5.25 m wide, with a little noise.
  const stripes = 20
  for (let i = 0; i < stripes; i++) {
    g.fillStyle = i % 2 ? '#22663a' : '#1b5530'
    g.fillRect((RUN_OFF + (i * L) / stripes) * PX, RUN_OFF * PX, (L / stripes) * PX + 1, WD * PX)
  }
  const rnd = mulberry32(7)
  for (let i = 0; i < 9000; i++) {
    g.fillStyle = `rgba(${rnd() < 0.5 ? '0,0,0' : '255,255,255'},${0.02 + rnd() * 0.03})`
    g.fillRect(rnd() * w, rnd() * h, 2, 2)
  }
  const X = (x: number) => (RUN_OFF + x) * PX
  const Y = (y: number) => (RUN_OFF + y) * PX
  g.strokeStyle = 'rgba(245,248,245,0.92)'
  g.fillStyle = 'rgba(245,248,245,0.92)'
  g.lineWidth = 0.12 * PX
  g.strokeRect(X(0), Y(0), L * PX, WD * PX)
  g.beginPath()
  g.moveTo(X(L / 2), Y(0))
  g.lineTo(X(L / 2), Y(WD))
  g.stroke()
  g.beginPath()
  g.arc(X(L / 2), Y(WD / 2), 9.15 * PX, 0, Math.PI * 2)
  g.stroke()
  const spot = (x: number, y: number) => {
    g.beginPath()
    g.arc(X(x), Y(y), 0.25 * PX, 0, Math.PI * 2)
    g.fill()
  }
  spot(L / 2, WD / 2)
  for (const side of [0, 1]) {
    const x = (v: number) => (side ? L - v : v)
    const box = (depth: number, width: number) => {
      g.beginPath()
      g.moveTo(X(x(0)), Y(WD / 2 - width / 2))
      g.lineTo(X(x(depth)), Y(WD / 2 - width / 2))
      g.lineTo(X(x(depth)), Y(WD / 2 + width / 2))
      g.lineTo(X(x(0)), Y(WD / 2 + width / 2))
      g.stroke()
    }
    box(16.5, 40.32)
    box(5.5, 18.32)
    spot(x(11), WD / 2)
    const a = Math.acos(5.5 / 9.15)
    g.beginPath()
    if (side) g.arc(X(x(11)), Y(WD / 2), 9.15 * PX, Math.PI - a, Math.PI + a)
    else g.arc(X(x(11)), Y(WD / 2), 9.15 * PX, -a, a)
    g.stroke()
    // Corner arcs.
    for (const y of [0, WD]) {
      g.beginPath()
      g.arc(X(x(0)), Y(y), 1 * PX, 0, Math.PI * 2)
      g.stroke()
    }
  }
  return c
}

/** Supporters: rows of seats with people in many colours, a block of each team's colour at its end. */
function crowdTexture(seed: number, home: string, away: string): HTMLCanvasElement {
  const c = document.createElement('canvas')
  c.width = 1024
  c.height = 128
  const g = c.getContext('2d')!
  g.fillStyle = '#10141c'
  g.fillRect(0, 0, c.width, c.height)
  const rnd = mulberry32(seed)
  const palette = ['#cfd6e3', '#8a93a6', '#2b3445', '#e7ecf5', '#4b5568', '#a9b4c7']
  for (let y = 4; y < c.height; y += 8)
    for (let x = 2; x < c.width; x += 6) {
      const u = x / c.width
      const team = u < 0.22 ? home : u > 0.78 ? away : null
      g.fillStyle = team && rnd() < 0.7 ? team : palette[Math.floor(rnd() * palette.length)]
      if (rnd() < 0.86) g.fillRect(x + rnd() * 2, y + rnd() * 2, 3, 4)
    }
  return c
}

export default function PitchScene3D({ ours, theirs, ball, label }: { ours: readonly P[]; theirs: readonly P[]; ball: P | null; label: string }) {
  const wrap = useRef<HTMLDivElement>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const [spin, setSpin] = useState(true)
  const api = useRef<{ place: (ours: readonly P[], theirs: readonly P[], ball: P | null) => void; reset: () => void; setSpin: (on: boolean) => void } | null>(null)

  useEffect(() => {
    const el = wrap.current
    if (!el) return
    let renderer: THREE.WebGLRenderer
    try {
      renderer = new THREE.WebGLRenderer({ antialias: true, powerPreference: 'high-performance' })
    } catch (err) {
      setFailed(err instanceof Error ? err.message : String(err))
      return
    }
    const width = () => Math.max(240, el.clientWidth)
    const height = () => Math.round(Math.max(320, Math.min(600, width() * 0.58)))
    renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1))
    renderer.setSize(width(), height())
    renderer.shadowMap.enabled = true
    renderer.shadowMap.type = THREE.PCFShadowMap
    renderer.toneMapping = THREE.ACESFilmicToneMapping
    renderer.toneMappingExposure = 1.0
    renderer.domElement.setAttribute('role', 'img')
    renderer.domElement.setAttribute('aria-label', label)
    el.appendChild(renderer.domElement)
    const labels = new CSS2DRenderer()
    labels.setSize(width(), height())
    labels.domElement.className = 'scene-labels'
    el.appendChild(labels.domElement)

    const scene = new THREE.Scene()
    scene.background = new THREE.Color('#05080e')
    scene.fog = new THREE.Fog('#05080e', 160, 320)
    const camera = new THREE.PerspectiveCamera(40, width() / height(), 0.5, 900)
    const home = new THREE.Vector3(0, 62, 92)
    camera.position.copy(home)

    // The pitch.
    const tex = new THREE.CanvasTexture(pitchTexture())
    tex.colorSpace = THREE.SRGBColorSpace
    tex.anisotropy = Math.min(8, renderer.capabilities.getMaxAnisotropy())
    const turf = new THREE.Mesh(new THREE.PlaneGeometry(L + 2 * RUN_OFF, WD + 2 * RUN_OFF), new THREE.MeshStandardMaterial({ map: tex, roughness: 0.92, metalness: 0 }))
    turf.rotation.x = -Math.PI / 2
    turf.receiveShadow = true
    scene.add(turf)
    // The concourse round the bowl.
    const apron = new THREE.Mesh(new THREE.PlaneGeometry(420, 340), new THREE.MeshStandardMaterial({ color: '#0b0f16', roughness: 1 }))
    apron.rotation.x = -Math.PI / 2
    apron.position.y = -0.05
    scene.add(apron)

    // Goals: white posts and crossbar, a net behind.
    const postMat = new THREE.MeshStandardMaterial({ color: '#f4f6fa', roughness: 0.35, emissive: '#30343a' })
    const netMat = new THREE.MeshBasicMaterial({ color: '#dfe6ee', wireframe: true, transparent: true, opacity: 0.18 })
    for (const side of [-1, 1]) {
      const gx = (side * L) / 2
      const goal = new THREE.Group()
      for (const z of [-3.66, 3.66]) {
        const post = new THREE.Mesh(new THREE.CylinderGeometry(0.06, 0.06, 2.44, 12), postMat)
        post.position.set(gx, 1.22, z)
        post.castShadow = true
        goal.add(post)
      }
      const bar = new THREE.Mesh(new THREE.CylinderGeometry(0.06, 0.06, 7.32, 12), postMat)
      bar.rotation.x = Math.PI / 2
      bar.position.set(gx, 2.44, 0)
      bar.castShadow = true
      goal.add(bar)
      const net = new THREE.Mesh(new THREE.BoxGeometry(2, 2.44, 7.32, 3, 4, 10), netMat)
      net.position.set(gx + side * 1, 1.22, 0)
      goal.add(net)
      scene.add(goal)
    }

    // Stands on all four sides, three tiers each, full of supporters.
    const standMat = new THREE.MeshStandardMaterial({ color: '#1a2130', roughness: 0.9 })
    const roofMat = new THREE.MeshStandardMaterial({ color: '#0d1119', roughness: 0.6, metalness: 0.4 })
    const boardMat = new THREE.MeshStandardMaterial({ color: '#0a2a24', emissive: '#5ad1b8', emissiveIntensity: 0.55 })
    const stands: { len: number; pos: [number, number]; rot: number }[] = [
      { len: L + 24, pos: [0, -(WD / 2 + RUN_OFF + 2)], rot: 0 },
      { len: L + 24, pos: [0, WD / 2 + RUN_OFF + 2], rot: Math.PI },
      { len: WD + 18, pos: [-(L / 2 + RUN_OFF + 2), 0], rot: Math.PI / 2 },
      { len: WD + 18, pos: [L / 2 + RUN_OFF + 2, 0], rot: -Math.PI / 2 },
    ]
    stands.forEach((s, i) => {
      const g = new THREE.Group()
      const crowd = new THREE.CanvasTexture(crowdTexture(31 + i, TEAM.ours, TEAM.theirs))
      crowd.colorSpace = THREE.SRGBColorSpace
      crowd.wrapS = THREE.RepeatWrapping
      crowd.repeat.set(Math.max(1, s.len / 40), 1)
      const seats = new THREE.MeshStandardMaterial({ map: crowd, roughness: 0.95 })
      // The bowl: one raked bank of seats from the advertising boards up to the back wall.
      const deep = 21
      const rise = 13
      const bank = new THREE.Mesh(new THREE.PlaneGeometry(s.len, Math.hypot(deep, rise)), seats)
      bank.rotation.x = -(Math.PI / 2 - Math.atan2(rise, deep))
      bank.position.set(0, 1.2 + rise / 2, -1 - deep / 2)
      g.add(bank)
      const back = new THREE.Mesh(new THREE.BoxGeometry(s.len, rise + 3, 1), standMat)
      back.position.set(0, (rise + 3) / 2, -2 - deep)
      g.add(back)
      // LED boards round the pitch.
      const boards = new THREE.Mesh(new THREE.BoxGeometry(s.len - 6, 0.9, 0.25), boardMat)
      boards.position.set(0, 0.45, 0.6)
      g.add(boards)
      const roof = new THREE.Mesh(new THREE.BoxGeometry(s.len + 4, 0.6, 26), roofMat)
      roof.position.set(0, 17.5, -13)
      g.add(roof)
      g.position.set(s.pos[0], 0, s.pos[1])
      g.rotation.y = s.rot
      scene.add(g)
    })

    // Floodlights: four towers with glowing panels, and the light they give.
    scene.add(new THREE.HemisphereLight('#bcd4ff', '#0b1a10', 0.55))
    const sun = new THREE.DirectionalLight('#fff6e6', 1.6)
    sun.position.set(-40, 90, 50)
    sun.castShadow = true
    sun.shadow.mapSize.set(2048, 2048)
    Object.assign(sun.shadow.camera, { left: -70, right: 70, top: 50, bottom: -50, near: 10, far: 220 })
    scene.add(sun)
    const panelMat = new THREE.MeshStandardMaterial({ color: '#ffffff', emissive: '#fff4d8', emissiveIntensity: 2.4 })
    const mastMat = new THREE.MeshStandardMaterial({ color: '#2a3140', metalness: 0.6, roughness: 0.4 })
    for (const sx of [-1, 1])
      for (const sz of [-1, 1]) {
        const x = sx * (L / 2 + RUN_OFF + 14)
        const z = sz * (WD / 2 + RUN_OFF + 14)
        const mast = new THREE.Mesh(new THREE.CylinderGeometry(0.5, 0.8, 34, 8), mastMat)
        mast.position.set(x, 17, z)
        scene.add(mast)
        const panel = new THREE.Mesh(new THREE.BoxGeometry(7, 4, 0.6), panelMat)
        panel.position.set(x, 35, z)
        panel.lookAt(0, 0, 0)
        scene.add(panel)
        const spot = new THREE.SpotLight('#fff3dc', 900, 260, Math.PI / 6, 0.6, 1.6)
        spot.position.set(x, 35, z)
        spot.target.position.set(0, 0, 0)
        scene.add(spot, spot.target)
      }

    // The figures: eleven a side (pooled), drawn a little larger than life so they read from the stands, and the ball.
    const FIG = 1.6
    const oursGeo = new THREE.CapsuleGeometry(0.45, 1.0, 6, 14)
    const theirsGeo = new THREE.CylinderGeometry(0.62, 0.62, 1.9, 4)
    const oursMat = new THREE.MeshStandardMaterial({ color: TEAM.ours, roughness: 0.45, emissive: TEAM.ours, emissiveIntensity: 0.25 })
    const theirsMat = new THREE.MeshStandardMaterial({ color: TEAM.theirs, roughness: 0.45, emissive: TEAM.theirs, emissiveIntensity: 0.25 })
    const ringGeo = new THREE.RingGeometry(0.7, 0.95, 24)
    const pool = (geo: THREE.BufferGeometry, mat: THREE.Material, colour: string) =>
      Array.from({ length: 14 }, () => {
        const g = new THREE.Group()
        const body = new THREE.Mesh(geo, mat)
        body.position.y = 0.95
        body.castShadow = true
        if (geo === theirsGeo) body.rotation.y = Math.PI / 4
        const ring = new THREE.Mesh(ringGeo, new THREE.MeshBasicMaterial({ color: colour, transparent: true, opacity: 0.55, side: THREE.DoubleSide }))
        ring.rotation.x = -Math.PI / 2
        ring.position.y = 0.03
        g.add(body, ring)
        g.scale.setScalar(FIG)
        g.visible = false
        scene.add(g)
        return g
      })
    const oursPool = pool(oursGeo, oursMat, TEAM.ours)
    const theirsPool = pool(theirsGeo, theirsMat, TEAM.theirs)
    const ballMesh = new THREE.Mesh(new THREE.SphereGeometry(0.34 * 1.8, 20, 14), new THREE.MeshStandardMaterial({ color: '#ffffff', emissive: '#ffffff', emissiveIntensity: 0.9 }))
    ballMesh.castShadow = true
    ballMesh.visible = false
    scene.add(ballMesh)

    // Which way each team plays.
    const tag = (text: string, x: number, cls: string) => {
      const d = document.createElement('div')
      d.className = `label3d ${cls}`
      d.textContent = text
      const o = new CSS2DObject(d)
      o.position.set(x, 3.2, -WD / 2 - 3)
      scene.add(o)
    }
    tag('Your goal', -L / 2, 'muted')
    tag('You attack →', L / 2 - 14, 'good')

    const composer = new EffectComposer(renderer)
    composer.addPass(new RenderPass(scene, camera))
    const bloom = new UnrealBloomPass(new THREE.Vector2(width(), height()), 0.55, 0.6, 0.82)
    composer.addPass(bloom)
    composer.addPass(new OutputPass())

    const controls = new OrbitControls(camera, renderer.domElement)
    controls.enableDamping = true
    controls.maxPolarAngle = Math.PI / 2.08
    controls.minDistance = 18
    controls.maxDistance = 220
    controls.target.set(0, 0, 0)
    let spinning = true
    controls.addEventListener('start', () => {
      spinning = false
      setSpin(false)
    })

    api.current = {
      place(a, b, ballAt) {
        const put = (list: THREE.Group[], pts: readonly P[]) =>
          list.forEach((g, i) => {
            const p = pts[i]
            g.visible = !!p
            if (p) {
              const [x, z] = at(p)
              g.position.set(x, 0, z)
            }
          })
        put(oursPool, a)
        put(theirsPool, b)
        ballMesh.visible = !!ballAt
        if (ballAt) {
          const [x, z] = at(ballAt)
          ballMesh.position.set(x, 0.34 * 1.8, z)
        }
      },
      reset() {
        camera.position.copy(home)
        controls.target.set(0, 0, 0)
      },
      setSpin(on) {
        spinning = on
      },
    }
    api.current.place(ours, theirs, ball)

    let raf = 0
    let alive = true
    const tick = () => {
      if (!alive) return
      if (spinning) {
        const r = Math.hypot(camera.position.x, camera.position.z)
        const a = Math.atan2(camera.position.z, camera.position.x) + 0.0012
        camera.position.x = r * Math.cos(a)
        camera.position.z = r * Math.sin(a)
      }
      controls.update()
      composer.render()
      labels.render(scene, camera)
      raf = requestAnimationFrame(tick)
    }
    tick()
    const onResize = () => {
      renderer.setSize(width(), height())
      labels.setSize(width(), height())
      composer.setSize(width(), height())
      camera.aspect = width() / height()
      camera.updateProjectionMatrix()
    }
    const ro = new ResizeObserver(onResize)
    ro.observe(el)
    return () => {
      alive = false
      cancelAnimationFrame(raf)
      ro.disconnect()
      controls.dispose()
      composer.dispose()
      scene.traverse((o) => {
        const m = o as THREE.Mesh
        m.geometry?.dispose?.()
        const mat = m.material as THREE.Material | THREE.Material[] | undefined
        for (const x of Array.isArray(mat) ? mat : mat ? [mat] : []) {
          ;(x as THREE.MeshStandardMaterial).map?.dispose()
          x.dispose()
        }
      })
      renderer.dispose()
      el.replaceChildren()
      api.current = null
    }
  }, [])

  useEffect(() => {
    api.current?.place(ours, theirs, ball)
  }, [ours, theirs, ball])

  if (failed) return <div className="empty small">3D is not available here ({failed}). The overhead views above show the same.</div>
  return (
    <div className="scene3d">
      <div ref={wrap} className="scene3d-canvas" />
      <div className="btn-row small" style={{ marginTop: 6 }}>
        <button type="button" className="btn sm ghost" onClick={() => api.current?.reset()}>
          Reset view
        </button>
        <button
          type="button"
          className="btn sm ghost"
          onClick={() => {
            api.current?.setSpin(!spin)
            setSpin(!spin)
          }}
        >
          {spin ? 'Stop turning' : 'Turn slowly'}
        </button>
        <span className="muted">Drag to orbit, wheel to zoom, right-drag to pan.</span>
      </div>
    </div>
  )
}
