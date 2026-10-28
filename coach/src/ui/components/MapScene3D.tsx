// The map in 3D, built from what the minimap showed: the stitched map is
// the ground, its lighter shapes (structures) stand up, and places where the
// player was seen on an upper floor or a roof are raised. On it: a red beam
// where each death was, the killer's line of fire as an amber arc with a
// tracer, kills, the paths life by life, enemy pings and callout labels.
// Drag to orbit, wheel to zoom, right-drag to pan; it turns slowly until
// touched. Loaded on demand (three.js is large).

import { useEffect, useRef, useState } from 'react'
import * as THREE from 'three'
import { OrbitControls } from 'three/examples/jsm/controls/OrbitControls.js'
import { EffectComposer } from 'three/examples/jsm/postprocessing/EffectComposer.js'
import { OutputPass } from 'three/examples/jsm/postprocessing/OutputPass.js'
import { RenderPass } from 'three/examples/jsm/postprocessing/RenderPass.js'
import { UnrealBloomPass } from 'three/examples/jsm/postprocessing/UnrealBloomPass.js'
import { CSS2DObject, CSS2DRenderer } from 'three/examples/jsm/renderers/CSS2DRenderer.js'
import { heightField, type MapLayers, type PlacedPin } from '../../core/map/view.ts'
import { gameClock } from '../format.ts'
import { MARK, stylizedMap, type Layer } from './MapOverhead.tsx'

/** Height of a structure, in map pixels. */
const BUILD = 11

export default function MapScene3D({
  layers,
  show,
  here,
  focus,
  label,
}: {
  layers: MapLayers
  show: ReadonlySet<Layer>
  here?: { readonly x: number; readonly y: number } | null
  focus?: number | null
  label: string
}) {
  const wrap = useRef<HTMLDivElement>(null)
  const [failed, setFailed] = useState<string | null>(null)
  const [spin, setSpin] = useState(true)
  const [hover, setHover] = useState<{ pin: PlacedPin; x: number; y: number } | null>(null)
  const api = useRef<{ reset: () => void; setSpin: (on: boolean) => void } | null>(null)

  useEffect(() => {
    const el = wrap.current
    if (!el) return
    let renderer: THREE.WebGLRenderer
    try {
      renderer = new THREE.WebGLRenderer({ antialias: true, alpha: false, powerPreference: 'high-performance' })
    } catch (err) {
      setFailed(err instanceof Error ? err.message : String(err))
      return
    }
    const { w, h } = layers.image
    const size = Math.max(w, h)
    const width = () => Math.max(240, el.clientWidth)
    const height = () => Math.round(Math.max(320, Math.min(620, width() * 0.62)))
    renderer.setPixelRatio(Math.min(2, window.devicePixelRatio || 1))
    renderer.setSize(width(), height())
    renderer.shadowMap.enabled = true
    renderer.shadowMap.type = THREE.PCFShadowMap
    renderer.toneMapping = THREE.ACESFilmicToneMapping
    renderer.toneMappingExposure = 1.05
    renderer.domElement.setAttribute('role', 'img')
    renderer.domElement.setAttribute('aria-label', label)
    el.appendChild(renderer.domElement)
    const labels = new CSS2DRenderer()
    labels.setSize(width(), height())
    labels.domElement.className = 'scene-labels'
    el.appendChild(labels.domElement)

    const scene = new THREE.Scene()
    scene.background = new THREE.Color('#070b12')
    scene.fog = new THREE.FogExp2('#070b12', 0.9 / (size * 2.2))
    const camera = new THREE.PerspectiveCamera(42, width() / height(), 1, size * 8)
    const home = new THREE.Vector3(size * 0.12, size * 0.85, size * 1.02)
    camera.position.copy(home)

    // Ground: the stitched map as a height field, the picture (with death heat) on top.
    const field = heightField(layers)
    const pic = stylizedMap(layers)
    const tex = document.createElement('canvas')
    tex.width = w
    tex.height = h
    const tctx = tex.getContext('2d')!
    tctx.drawImage(pic, 0, 0)
    if (show.has('heat')) {
      tctx.globalCompositeOperation = 'screen'
      for (const p of layers.pins) {
        if (p.kind !== 'death' || (focus !== null && focus !== undefined && p.match !== focus)) continue
        const g = tctx.createRadialGradient(p.px, p.py, 0, p.px, p.py, 20)
        g.addColorStop(0, 'rgba(255, 70, 70, 0.55)')
        g.addColorStop(1, 'rgba(255, 70, 70, 0)')
        tctx.fillStyle = g
        tctx.fillRect(p.px - 20, p.py - 20, 40, 40)
      }
    }
    const texture = new THREE.CanvasTexture(tex)
    texture.colorSpace = THREE.SRGBColorSpace
    texture.anisotropy = 4
    const alpha = document.createElement('canvas')
    alpha.width = w
    alpha.height = h
    const actx = alpha.getContext('2d')!
    const aimg = actx.createImageData(w, h)
    for (let i = 0; i < w * h; i++) {
      const v = layers.image.weight[i] ? 255 : 0
      aimg.data[i * 4] = aimg.data[i * 4 + 1] = aimg.data[i * 4 + 2] = v
      aimg.data[i * 4 + 3] = 255
    }
    actx.putImageData(aimg, 0, 0)
    const alphaTex = new THREE.CanvasTexture(alpha)

    const segX = Math.min(320, Math.max(16, Math.round(w / 1.5)))
    const segZ = Math.min(320, Math.max(16, Math.round(h / 1.5)))
    const geo = new THREE.PlaneGeometry(w, h, segX, segZ)
    geo.rotateX(-Math.PI / 2)
    const pos = geo.attributes.position as THREE.BufferAttribute
    const sample = (px: number, py: number) => {
      const x = Math.max(0, Math.min(w - 1, Math.round(px)))
      const y = Math.max(0, Math.min(h - 1, Math.round(py)))
      return field[y * w + x]
    }
    const heightAt = (px: number, py: number) => {
      const v = sample(px, py)
      if (v < 0) return -3
      // Crisp walls: the structure value pushed towards 0 or 1, then lifted.
      const s = Math.max(0, Math.min(1, (Math.min(v, 1) - 0.42) / 0.16))
      const step = s * s * (3 - 2 * s)
      return step * BUILD + Math.max(0, v - 1) * BUILD * 1.6 + Math.min(0, v) * BUILD
    }
    for (let i = 0; i < pos.count; i++) {
      const px = pos.getX(i) + w / 2
      const py = pos.getZ(i) + h / 2
      pos.setY(i, heightAt(px, py))
    }
    geo.computeVertexNormals()
    const ground = new THREE.Mesh(
      geo,
      new THREE.MeshStandardMaterial({
        map: texture,
        emissive: new THREE.Color('#ffffff'),
        emissiveMap: texture,
        emissiveIntensity: 0.42,
        roughness: 0.72,
        metalness: 0.18,
        alphaMap: alphaTex,
        transparent: true,
        alphaTest: 0.5,
      }),
    )
    ground.castShadow = true
    ground.receiveShadow = true
    scene.add(ground)
    // A slab under the map and a glowing outline, so it reads as a model on a table.
    const slab = new THREE.Mesh(new THREE.BoxGeometry(w + 16, 6, h + 16), new THREE.MeshStandardMaterial({ color: '#0c1422', roughness: 0.9, metalness: 0.1 }))
    slab.position.y = -6
    slab.receiveShadow = true
    scene.add(slab)
    const outline = new THREE.LineSegments(new THREE.EdgesGeometry(new THREE.BoxGeometry(w + 16, 6, h + 16)), new THREE.LineBasicMaterial({ color: '#5ad1b8', transparent: true, opacity: 0.55 }))
    outline.position.y = -6
    scene.add(outline)
    const grid = new THREE.GridHelper(size * 4, 40, new THREE.Color('#1b2a42'), new THREE.Color('#111b2c'))
    grid.position.y = -9.2
    scene.add(grid)

    scene.add(new THREE.HemisphereLight('#a8cfff', '#0a0f18', 0.75))
    scene.add(new THREE.AmbientLight('#ffffff', 0.18))
    const sun = new THREE.DirectionalLight('#ffffff', 1.35)
    sun.position.set(-size * 0.6, size * 1.1, size * 0.45)
    sun.castShadow = true
    sun.shadow.mapSize.set(2048, 2048)
    const sc = sun.shadow.camera as THREE.OrthographicCamera
    sc.left = -size * 0.7
    sc.right = size * 0.7
    sc.top = size * 0.7
    sc.bottom = -size * 0.7
    sc.far = size * 4
    sun.shadow.bias = -0.0006
    scene.add(sun)
    const rim = new THREE.DirectionalLight('#5ad1b8', 0.35)
    rim.position.set(size * 0.7, size * 0.3, -size * 0.8)
    scene.add(rim)

    // Markers.
    const toWorld = (px: number, py: number, lift = 0) => new THREE.Vector3(px - w / 2, heightAt(px, py) + lift, py - h / 2)
    const marks = new THREE.Group()
    scene.add(marks)
    const pickable: THREE.Object3D[] = []
    const tracers: { mesh: THREE.Mesh; curve: THREE.QuadraticBezierCurve3; offset: number }[] = []
    const shown = layers.pins.filter((p) => focus === null || focus === undefined || p.match === focus)
    const glow = (color: string, intensity = 2.2) => new THREE.MeshStandardMaterial({ color, emissive: new THREE.Color(color), emissiveIntensity: intensity, roughness: 0.4, metalness: 0.1 })
    const beamMat = new THREE.MeshBasicMaterial({ color: MARK.death, transparent: true, opacity: 0.32, blending: THREE.AdditiveBlending, depthWrite: false })
    const deathMat = glow(MARK.death)
    const killMat = glow(MARK.kill, 1.8)
    const killerMat = glow(MARK.killer, 1.8)
    const arcMat = new THREE.MeshBasicMaterial({ color: MARK.killer, transparent: true, opacity: 0.95, blending: THREE.AdditiveBlending, depthWrite: false })
    const tracerMat = new THREE.MeshBasicMaterial({ color: '#fff2c6' })
    // Markers keep their size relative to the map.
    const u = Math.max(1, size / 320)
    const sphere = new THREE.SphereGeometry(2.8 * u, 20, 14)
    const beamH = 60 * u
    const beam = new THREE.CylinderGeometry(0.9 * u, 0.9 * u, beamH, 10, 1, true)
    const kill = new THREE.OctahedronGeometry(2.8 * u)
    const cone = new THREE.ConeGeometry(2.6 * u, 6.5 * u, 14)
    const tracer = new THREE.SphereGeometry(1.3 * u, 10, 8)
    for (const p of shown) {
      if (p.kind === 'death' && show.has('deaths')) {
        const at = toWorld(p.px, p.py, 3)
        const m = new THREE.Mesh(sphere, deathMat)
        m.position.copy(at)
        m.castShadow = true
        m.userData.pin = p
        marks.add(m)
        pickable.push(m)
        const b = new THREE.Mesh(beam, beamMat)
        b.position.set(at.x, at.y + beamH / 2, at.z)
        marks.add(b)
      }
      if (p.kind === 'kill' && show.has('kills')) {
        const m = new THREE.Mesh(kill, killMat)
        m.position.copy(toWorld(p.px, p.py, 3.5))
        m.castShadow = true
        m.userData.pin = p
        marks.add(m)
        pickable.push(m)
      }
      if (p.kind === 'death' && p.killerPx && show.has('killers')) {
        const from = toWorld(p.killerPx.x, p.killerPx.y, 5)
        const to = toWorld(p.px, p.py, 3)
        const c = new THREE.Mesh(cone, killerMat)
        c.position.copy(from)
        c.rotation.x = Math.PI
        marks.add(c)
        const mid = from.clone().lerp(to, 0.5)
        mid.y += Math.max(8, from.distanceTo(to) * 0.28)
        const curve = new THREE.QuadraticBezierCurve3(from, mid, to)
        marks.add(new THREE.Mesh(new THREE.TubeGeometry(curve, 40, (p.killer?.from === 'killcam' ? 0.8 : 0.5) * u, 6, false), arcMat))
        const t = new THREE.Mesh(tracer, tracerMat)
        marks.add(t)
        tracers.push({ mesh: t, curve, offset: Math.random() })
      }
    }
    if (show.has('paths')) {
      const latest = Math.max(-1, ...layers.paths.map((p) => p.match))
      for (const p of layers.paths) {
        if (focus !== null && focus !== undefined && p.match !== focus) continue
        const pts = p.points.filter((_, i) => i % 2 === 0 || i === p.points.length - 1).map((q) => toWorld(q[0], q[1], 1.4))
        if (pts.length < 2) continue
        const curve = new THREE.CatmullRomCurve3(pts, false, 'centripetal')
        const recent = focus !== null && focus !== undefined ? 0.9 : p.match === latest ? 0.85 : 0.3
        marks.add(
          new THREE.Mesh(
            new THREE.TubeGeometry(curve, Math.min(600, pts.length * 3), 0.6 * u, 5, false),
            new THREE.MeshBasicMaterial({ color: MARK.path, transparent: true, opacity: recent, blending: THREE.AdditiveBlending, depthWrite: false }),
          ),
        )
      }
    }
    if (show.has('enemies') && layers.enemies.length) {
      const max = Math.max(1, ...layers.enemies.map((e) => e.n))
      const g = new THREE.BufferGeometry().setFromPoints(layers.enemies.map((e) => toWorld(e.x, e.y, 2)))
      const colors = new Float32Array(layers.enemies.length * 3)
      const c = new THREE.Color(MARK.enemy)
      layers.enemies.forEach((e, i) => {
        const k = 0.35 + 0.65 * (e.n / max)
        colors.set([c.r * k, c.g * k, c.b * k], i * 3)
      })
      g.setAttribute('color', new THREE.BufferAttribute(colors, 3))
      marks.add(new THREE.Points(g, new THREE.PointsMaterial({ size: 4.5, vertexColors: true, transparent: true, opacity: 0.95, blending: THREE.AdditiveBlending, depthWrite: false })))
    }
    if (show.has('callouts')) {
      for (const c of layers.callouts) {
        const div = document.createElement('div')
        div.className = 'label3d'
        div.textContent = c.name
        const o = new CSS2DObject(div)
        o.position.copy(toWorld(c.x, c.y, 9))
        marks.add(o)
      }
    }
    let ring: THREE.Mesh | null = null
    if (here) {
      ring = new THREE.Mesh(new THREE.RingGeometry(3, 4, 40), new THREE.MeshBasicMaterial({ color: '#ffffff', transparent: true, side: THREE.DoubleSide, depthWrite: false }))
      ring.rotation.x = -Math.PI / 2
      ring.position.copy(toWorld(here.x, here.y, 1.6))
      marks.add(ring)
      const dot = new THREE.Mesh(new THREE.SphereGeometry(2.2, 16, 12), glow('#ffffff', 1.6))
      dot.position.copy(toWorld(here.x, here.y, 2.6))
      marks.add(dot)
    }

    const controls = new OrbitControls(camera, renderer.domElement)
    controls.enableDamping = true
    controls.dampingFactor = 0.08
    controls.maxPolarAngle = Math.PI * 0.46
    controls.minDistance = size * 0.15
    controls.maxDistance = size * 2.6
    controls.autoRotate = true
    controls.autoRotateSpeed = 0.55
    controls.target.set(0, 0, 0)
    controls.addEventListener('start', () => {
      controls.autoRotate = false
      setSpin(false)
    })

    const composer = new EffectComposer(renderer)
    composer.addPass(new RenderPass(scene, camera))
    const bloom = new UnrealBloomPass(new THREE.Vector2(width(), height()), 0.85, 0.55, 0.62)
    composer.addPass(bloom)
    composer.addPass(new OutputPass())

    const ray = new THREE.Raycaster()
    const pointer = new THREE.Vector2()
    const onMove = (e: PointerEvent) => {
      const r = renderer.domElement.getBoundingClientRect()
      pointer.set(((e.clientX - r.left) / r.width) * 2 - 1, -((e.clientY - r.top) / r.height) * 2 + 1)
      ray.setFromCamera(pointer, camera)
      const hit = ray.intersectObjects(pickable, false)[0]
      setHover(hit ? { pin: hit.object.userData.pin as PlacedPin, x: e.clientX - r.left, y: e.clientY - r.top } : null)
    }
    renderer.domElement.addEventListener('pointermove', onMove)
    renderer.domElement.addEventListener('pointerleave', () => setHover(null))

    const ro = new ResizeObserver(() => {
      const W = width()
      const H = height()
      renderer.setSize(W, H)
      labels.setSize(W, H)
      composer.setSize(W, H)
      bloom.setSize(W, H)
      camera.aspect = W / H
      camera.updateProjectionMatrix()
    })
    ro.observe(el)

    let raf = 0
    const started = performance.now()
    const loop = () => {
      raf = requestAnimationFrame(loop)
      if (document.hidden) return
      const t = (performance.now() - started) / 1000
      controls.update()
      for (const tr of tracers) tr.mesh.position.copy(tr.curve.getPoint((t * 0.45 + tr.offset) % 1))
      if (ring) {
        const k = 1 + ((t * 0.8) % 1) * 2.4
        ring.scale.set(k, k, k)
        ;(ring.material as THREE.MeshBasicMaterial).opacity = 1 - ((t * 0.8) % 1)
      }
      beamMat.opacity = 0.26 + 0.08 * Math.sin(t * 2.4)
      composer.render()
      labels.render(scene, camera)
    }
    loop()

    api.current = {
      reset: () => {
        camera.position.copy(home)
        controls.target.set(0, 0, 0)
      },
      setSpin: (on) => {
        controls.autoRotate = on
      },
    }

    return () => {
      cancelAnimationFrame(raf)
      ro.disconnect()
      renderer.domElement.removeEventListener('pointermove', onMove)
      controls.dispose()
      scene.traverse((o) => {
        const m = o as THREE.Mesh
        m.geometry?.dispose()
        const mat = m.material as THREE.Material | THREE.Material[] | undefined
        if (Array.isArray(mat)) mat.forEach((x) => x.dispose())
        else mat?.dispose()
      })
      texture.dispose()
      alphaTex.dispose()
      composer.dispose()
      renderer.dispose()
      el.replaceChildren()
      api.current = null
    }
  }, [layers, show, here?.x, here?.y, focus, label])

  if (failed) return <p className="muted small">3D needs WebGL, which is not available here ({failed}). The overhead map shows the same.</p>
  return (
    <div className="scene3d">
      <div className="scene3d-canvas" ref={wrap} />
      <div className="overhead-zoom">
        <button
          type="button"
          className="btn sm ghost"
          aria-pressed={spin}
          onClick={() => {
            api.current?.setSpin(!spin)
            setSpin(!spin)
          }}
        >
          {spin ? 'Stop turning' : 'Turn'}
        </button>
        <button type="button" className="btn sm ghost" onClick={() => api.current?.reset()}>
          Reset view
        </button>
      </div>
      {hover ? (
        <div className="overhead-tip" style={{ left: hover.x + 14, top: hover.y - 10 }} role="status">
          <b>{hover.pin.kind === 'death' ? 'Died' : 'Kill'}</b> <span className="muted">{gameClock(hover.pin.t)}</span>
          {hover.pin.area ? <div>at {hover.pin.area}</div> : null}
          {hover.pin.weapon ? <div className="muted">{hover.pin.weapon}</div> : null}
        </div>
      ) : null}
    </div>
  )
}
