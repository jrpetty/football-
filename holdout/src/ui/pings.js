// Pings: a player marks a spot (Q, or Alt+click) and everyone in the same
// place sees a pulse in that player's colour with their name: in camp, the
// whole camp; on a co-op run, the friends on that run.
import * as THREE from 'three'
import { view } from '../render/view.js'
import { h } from '../core/util.js'

const LIFE = 6
const ringGeo = new THREE.RingGeometry(0.78, 1, 48).rotateX(-Math.PI / 2)
const dotGeo = new THREE.CircleGeometry(0.32, 24).rotateX(-Math.PI / 2)
const beamGeo = new THREE.CylinderGeometry(0.05, 0.05, 1, 8, 1, true).translate(0, 0.5, 0)

export class Pings {
  constructor() {
    this.list = []
  }
  add(scene, x, y, z, color, name) {
    const g = new THREE.Group()
    g.position.set(x, y + 0.07, z)
    const mat = (o) => new THREE.MeshBasicMaterial({ color, transparent: true, opacity: o, depthWrite: false, side: THREE.DoubleSide })
    const rings = [0, 1].map(() => {
      const m = new THREE.Mesh(ringGeo, mat(0.9))
      m.renderOrder = 4
      g.add(m)
      return m
    })
    const dot = new THREE.Mesh(dotGeo, mat(0.85))
    dot.renderOrder = 4
    const beam = new THREE.Mesh(beamGeo, mat(0.5))
    beam.scale.y = 7
    g.add(dot, beam)
    scene.add(g)
    const label = view.labels.add(h('div.pingmark', { style: { '--c': color } }, name), new THREE.Vector3(x, y + 2.4, z), { scene })
    // a second ping from the same player replaces the first
    for (const p of this.list) if (p.name === name && p.scene === scene) p.t = Math.max(p.t, LIFE - 0.3)
    this.list.push({ g, rings, dot, beam, label, scene, name, t: 0 })
  }
  update(dt, scene) {
    for (const p of this.list) {
      p.t += dt
      const k = p.t / LIFE
      p.rings.forEach((r, i) => {
        const c = ((p.t * 0.9 + i * 0.5) % 1)
        r.scale.setScalar(0.4 + c * 2.4)
        r.material.opacity = (1 - c) * 0.9 * (1 - k)
      })
      p.dot.material.opacity = 0.85 * (1 - k * k)
      p.beam.material.opacity = 0.5 * (1 - k)
      p.label.el.style.opacity = String(Math.min(1, (LIFE - p.t) * 1.5))
    }
    const keep = []
    for (const p of this.list) {
      if (p.t < LIFE && p.scene === scene) keep.push(p)
      else {
        p.scene.remove(p.g)
        p.g.traverse((o) => o.material?.dispose())
        p.label.remove()
      }
    }
    this.list = keep
  }
}
