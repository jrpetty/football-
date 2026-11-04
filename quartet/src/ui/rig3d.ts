// Quartet's people in 3D. Each is a jointed rig (pelvis, spine, neck, head,
// shoulders, elbows, hips, knees) built from smooth lit shapes and dressed
// for their job: suits with shirt and tie, scrubs, lab coats, chef's whites,
// hoodies, parkas with fur trim, dresses and gowns. Faces have eyes that blink
// and follow the conversation, brows and a mouth that show mood and move
// with speech. Poses are procedural: idle breathing and weight shifts,
// walking, talking with the hands, working, hand-on-chin thinking, slumped
// exhaustion, crossed-arm anger, a scared hunch, a happy bounce.

import * as THREE from 'three'
import type { Person } from '../engine/types.ts'
import { outfitFor, moodOf, mix, type Mood } from './Figure.tsx'

const SKIN = ['#f3d0b0', '#e3b48a', '#c99363', '#a06d45', '#7c5133', '#5b3b25']
const HAIR = ['#2b1d14', '#4a2f1d', '#7a4a24', '#b07a3a', '#e0c07a', '#141414', '#9a3b26']

export type State = 'walking' | 'talking' | 'working' | 'pondering' | 'tired' | Mood | 'gone'

const mats = new Map<string, THREE.MeshStandardMaterial>()
function mat(color: string, rough = 0.8, extra?: Partial<THREE.MeshStandardMaterialParameters>): THREE.MeshStandardMaterial {
  const key = color + rough + (extra ? JSON.stringify(extra) : '')
  let m = mats.get(key)
  if (!m) { m = new THREE.MeshStandardMaterial({ color: new THREE.Color(color), roughness: rough, metalness: 0, ...extra }); mats.set(key, m) }
  return m
}
function mesh(g: THREE.BufferGeometry, m: THREE.Material, x = 0, y = 0, z = 0): THREE.Mesh {
  const o = new THREE.Mesh(g, m)
  o.position.set(x, y, z)
  return o
}
function limb(r: number, len: number, m: THREE.Material): { pivot: THREE.Group; end: THREE.Group; mesh: THREE.Mesh } {
  const pivot = new THREE.Group()
  const g = new THREE.CapsuleGeometry(r, len, 6, 16)
  const me = mesh(g, m, 0, -len / 2, 0)
  pivot.add(me)
  const end = new THREE.Group()
  end.position.y = -len
  pivot.add(end)
  return { pivot, end, mesh: me }
}

/** A torso that tapers from chest to waist (lathe profile, flattened front to back). */
function torsoGeo(shoulder: number, waist: number, hip: number, h: number, depth: number): THREE.BufferGeometry {
  const pts = [
    new THREE.Vector2(0.001, -0.02),
    new THREE.Vector2(hip * 0.9, 0),
    new THREE.Vector2(hip, h * 0.08),
    new THREE.Vector2(waist, h * 0.32),
    new THREE.Vector2(shoulder * 0.92, h * 0.68),
    new THREE.Vector2(shoulder, h * 0.84),
    new THREE.Vector2(shoulder * 0.8, h * 0.95),
    new THREE.Vector2(0.06, h),
    new THREE.Vector2(0.001, h + 0.005),
  ]
  const g = new THREE.LatheGeometry(pts, 28)
  g.scale(1, 1, depth)
  return g
}

export class Rig {
  root = new THREE.Group()
  private body = new THREE.Group()
  private hips = new THREE.Group()
  private spine = new THREE.Group()
  private chest = new THREE.Group()
  private neck = new THREE.Group()
  private head = new THREE.Group()
  private legs: { hip: THREE.Group; knee: THREE.Group }[] = []
  private arms: { sh: THREE.Group; el: THREE.Group }[] = []
  private eyes: THREE.Group[] = []
  private pupils: THREE.Mesh[] = []
  private brows: THREE.Mesh[] = []
  private mouthArc = new THREE.Group()
  private mouthOpen: THREE.Mesh | null = null
  private blush: THREE.Mesh[] = []
  private ring: THREE.Mesh
  private blob: THREE.Mesh
  private key = ''
  private seed: number
  yaw = 0
  private yawNow = 0
  private lie = 0
  height = 1.78

  constructor(id: number) {
    this.seed = (id * 0.6180339) % 1
    this.root.add(this.body)
    this.blob = new THREE.Mesh(new THREE.CircleGeometry(0.3, 24).rotateX(-Math.PI / 2), new THREE.MeshBasicMaterial({ color: '#000', transparent: true, opacity: 0.28, depthWrite: false }))
    this.blob.position.y = 0.005
    this.root.add(this.blob)
    this.ring = new THREE.Mesh(new THREE.RingGeometry(0.32, 0.38, 40).rotateX(-Math.PI / 2), new THREE.MeshBasicMaterial({ color: '#fff', transparent: true, opacity: 0.95, depthWrite: false }))
    this.ring.position.y = 0.01
    this.ring.visible = false
    this.root.add(this.ring)
  }

  private build(p: Person, scenario: string) {
    const o = outfitFor(p, scenario)
    const key = `${scenario}|${p.role.title}|${p.look.top}|${p.age}`
    if (key === this.key) return
    this.key = key
    this.body.clear()
    this.legs = []
    this.arms = []
    this.eyes = []
    this.pupils = []
    this.brows = []
    this.blush = []
    const L = p.look
    const woman = p.gender === 'woman'
    const bw = 0.9 + L.build * 0.14
    const skinC = SKIN[L.skin % 6]
    const skin = mat(skinC, 0.55)
    const hairC = p.age > 62 ? '#d8d8d8' : p.age > 52 ? mix(HAIR[L.hair % 7], '#c8c8c8', 0.45) : HAIR[L.hair % 7]
    const hair = mat(hairC, 0.6)
    const top = mat(o.top, o.kind === 'parka' ? 0.6 : 0.85)
    const bottom = mat(o.bottom, 0.85)
    const shoes = mat(o.shoes, 0.35)
    const accent = mat(o.accent, 0.8)
    const long = o.kind === 'dress' || o.kind === 'gown' || o.kind === 'labcoat'
    const shortSleeve = o.kind === 'scrubs' || o.kind === 'casual'
    const puffy = o.kind === 'parka' ? 1.16 : 1

    // ---- hips and legs
    this.hips = new THREE.Group()
    this.hips.position.y = 0.92
    this.body.add(this.hips)
    const pelvis = mesh(new THREE.SphereGeometry(0.15, 20, 14), o.kind === 'gown' || o.kind === 'dress' ? top : bottom, 0, -0.02, 0)
    pelvis.scale.set(bw * (woman ? 1.08 : 1), 0.75, 0.72)
    this.hips.add(pelvis)
    for (const sd of [-1, 1]) {
      const thigh = limb(0.078 * bw, 0.34, bottom)
      thigh.pivot.position.set(sd * 0.085 * bw, -0.05, 0)
      const shin = limb(0.062 * bw, 0.36, bottom)
      thigh.end.add(shin.pivot)
      const foot = new THREE.Mesh(new THREE.CapsuleGeometry(0.045, 0.12, 4, 10), shoes)
      foot.rotation.x = Math.PI / 2
      foot.scale.set(1, 1, 0.8)
      foot.position.set(0, -0.03, 0.05)
      shin.end.add(foot)
      this.hips.add(thigh.pivot)
      this.legs.push({ hip: thigh.pivot, knee: shin.pivot })
    }
    if (long) {
      const len = o.kind === 'gown' ? 0.9 : o.kind === 'labcoat' ? 0.6 : 0.52
      const flare = o.kind === 'gown' ? 0.42 : o.kind === 'labcoat' ? 0.22 : 0.27
      const sk = mesh(new THREE.CylinderGeometry(0.15 * bw, flare * bw, len, 28, 1, o.kind === 'labcoat'), top, 0, -len / 2 + 0.02, 0)
      sk.scale.z = 0.8
      if (o.kind === 'labcoat') (sk.material as THREE.Material).side = THREE.DoubleSide
      this.hips.add(sk)
    }

    // ---- spine, torso
    this.spine = new THREE.Group()
    this.spine.position.y = 0.02
    this.hips.add(this.spine)
    this.chest = new THREE.Group()
    this.spine.add(this.chest)
    const sh = (woman ? 0.165 : 0.19) * bw
    const torso = mesh(torsoGeo(sh * puffy, (woman ? 0.12 : 0.14) * bw * puffy, 0.15 * bw * puffy, 0.52, 0.66 * puffy), top)
    this.chest.add(torso)
    // garment details on the chest
    const front = 0.66 * 0.155 * bw * puffy
    switch (o.kind) {
      case 'suit': {
        const shirt = mesh(new THREE.ConeGeometry(0.06, 0.2, 3), mat('#f4f4f4', 0.7), 0, 0.4, front + 0.004)
        shirt.rotation.z = Math.PI
        shirt.scale.z = 0.2
        this.chest.add(shirt)
        if (o.tie) { const tie = mesh(new THREE.BoxGeometry(0.035, 0.22, 0.01), mat(o.tie, 0.5), 0, 0.33, front + 0.012); this.chest.add(tie) }
        for (const y of [0.18, 0.26]) this.chest.add(mesh(new THREE.SphereGeometry(0.009, 8, 6), mat('#1a1a1a', 0.3), 0.025, y, front + 0.008))
        break
      }
      case 'labcoat': {
        this.chest.add(mesh(new THREE.BoxGeometry(0.05, 0.36, 0.01), accent, 0, 0.3, front + 0.004))
        this.chest.add(mesh(new THREE.BoxGeometry(0.06, 0.05, 0.01), mat('#dfe6ee', 0.6), 0.09, 0.36, front - 0.002))
        break
      }
      case 'chef': {
        for (const x of [-0.04, 0.04]) for (const y of [0.16, 0.25, 0.34]) this.chest.add(mesh(new THREE.SphereGeometry(0.01, 8, 6), mat('#2a2a30', 0.4), x, y, front + 0.006))
        break
      }
      case 'scrubs': {
        const v = mesh(new THREE.ConeGeometry(0.05, 0.1, 3), skin, 0, 0.47, front - 0.01)
        v.rotation.z = Math.PI
        v.scale.z = 0.2
        this.chest.add(v)
        this.chest.add(mesh(new THREE.BoxGeometry(0.07, 0.07, 0.01), mat(mix(o.top, '#000000', 0.15), 0.8), -0.08, 0.32, front))
        break
      }
      case 'hoodie': {
        const hood = mesh(new THREE.TorusGeometry(0.1, 0.045, 10, 20), top, 0, 0.5, -0.05)
        hood.rotation.x = Math.PI / 2.4
        this.chest.add(hood)
        this.chest.add(mesh(new THREE.BoxGeometry(0.18, 0.08, 0.02), mat(mix(o.top, '#000000', 0.12), 0.85), 0, 0.1, front - 0.004))
        for (const x of [-0.03, 0.03]) this.chest.add(mesh(new THREE.CylinderGeometry(0.004, 0.004, 0.12, 5), accent, x, 0.38, front + 0.006))
        break
      }
      case 'parka': {
        const fur = mesh(new THREE.TorusGeometry(0.1, 0.045, 10, 22), mat(o.accent, 1), 0, 0.5, 0)
        fur.rotation.x = Math.PI / 2
        this.chest.add(fur)
        this.chest.add(mesh(new THREE.BoxGeometry(0.012, 0.42, 0.01), mat(mix(o.top, '#000000', 0.3), 0.5), 0, 0.26, front + 0.004))
        for (const y of [0.12, 0.24, 0.36]) { const band = mesh(new THREE.TorusGeometry(sh * puffy * 0.95, 0.008, 6, 28), mat(mix(o.top, '#000000', 0.18), 0.7), 0, y, 0); band.rotation.x = Math.PI / 2; band.scale.y = 0.66; this.chest.add(band) }
        break
      }
      case 'uniform': {
        this.chest.add(mesh(new THREE.BoxGeometry(0.035, 0.045, 0.01), mat(o.accent, 0.3, { metalness: 0.7 }), -0.08, 0.36, front + 0.004))
        for (const y of [0.12, 0.22, 0.32]) this.chest.add(mesh(new THREE.SphereGeometry(0.009, 8, 6), mat(o.accent, 0.3, { metalness: 0.7 }), 0, y, front + 0.006))
        break
      }
      case 'overalls': {
        this.chest.add(mesh(new THREE.BoxGeometry(0.2, 0.18, 0.01), mat(mix(o.top, '#000000', 0.1), 0.85), 0, 0.15, front + 0.002))
        for (const x of [-0.07, 0.07]) this.chest.add(mesh(new THREE.BoxGeometry(0.03, 0.26, 0.012), mat(mix(o.top, '#000000', 0.1), 0.85), x, 0.36, front - 0.002))
        break
      }
      case 'apron': {
        this.chest.add(mesh(new THREE.BoxGeometry(0.22, 0.4, 0.01), accent, 0, 0.12, front + 0.006))
        break
      }
      case 'gown': case 'dress': {
        const neck = mesh(new THREE.TorusGeometry(0.075, 0.01, 6, 20), accent, 0, 0.5, 0)
        neck.rotation.x = Math.PI / 2
        this.chest.add(neck)
        break
      }
      default: {
        const neck = mesh(new THREE.TorusGeometry(0.07, 0.012, 6, 20), accent, 0, 0.51, 0)
        neck.rotation.x = Math.PI / 2
        this.chest.add(neck)
      }
    }

    // ---- arms
    for (const sd of [-1, 1]) {
      const up = limb(0.056 * bw * (puffy > 1 ? 1.25 : 1), 0.22, shortSleeve ? skin : top)
      up.pivot.position.set(sd * (sh * puffy + 0.025), 0.45, 0)
      if (shortSleeve) {
        const sleeve = mesh(new THREE.CylinderGeometry(0.062 * bw, 0.06 * bw, 0.13, 16), top, 0, -0.04, 0)
        up.pivot.add(sleeve)
      }
      const fo = limb(0.048 * bw * (puffy > 1 ? 1.2 : 1), 0.21, shortSleeve ? skin : top)
      up.end.add(fo.pivot)
      if (!shortSleeve && (o.kind === 'suit' || o.kind === 'labcoat' || o.kind === 'chef')) fo.end.add(mesh(new THREE.CylinderGeometry(0.046, 0.046, 0.03, 12), o.kind === 'suit' ? mat('#f4f4f4', 0.7) : top, 0, 0.03, 0))
      const hand = mesh(new THREE.SphereGeometry(0.045, 14, 10), skin, 0, -0.04, 0)
      hand.scale.set(0.8, 1.15, 0.6)
      fo.end.add(hand)
      const thumb = mesh(new THREE.CapsuleGeometry(0.013, 0.03, 3, 6), skin, -sd * 0.03, -0.025, 0.02)
      thumb.rotation.z = sd * 0.6
      fo.end.add(thumb)
      this.chest.add(up.pivot)
      // a rounded shoulder so the arm grows out of the torso
      const delt = mesh(new THREE.SphereGeometry(0.066 * bw * (puffy > 1 ? 1.2 : 1), 16, 12), top, sd * (sh * puffy + 0.012), 0.44, 0)
      delt.scale.set(1, 0.9, 0.95)
      this.chest.add(delt)
      this.arms.push({ sh: up.pivot, el: fo.pivot })
    }

    // ---- neck and head
    this.neck = new THREE.Group()
    this.neck.position.y = 0.5
    this.chest.add(this.neck)
    this.neck.add(mesh(new THREE.CylinderGeometry(0.045, 0.05, 0.1, 14), skin, 0, 0.04, 0))
    this.head = new THREE.Group()
    this.head.position.y = 0.18
    this.head.scale.setScalar(1.22)
    this.neck.add(this.head)
    const hr = 0.112
    const skull = mesh(new THREE.SphereGeometry(hr, 32, 24), skin)
    skull.scale.set(0.9, 1.04, 0.98)
    this.head.add(skull)
    const jaw = mesh(new THREE.SphereGeometry(hr * 0.8, 24, 16), skin, 0, -0.045, 0.015)
    jaw.scale.set(woman ? 0.86 : 0.95, 0.75, 0.9)
    this.head.add(jaw)
    for (const sd of [-1, 1]) {
      const ear = mesh(new THREE.SphereGeometry(0.025, 12, 8), skin, sd * hr * 0.9, -0.005, -0.01)
      ear.scale.set(0.5, 1, 0.8)
      this.head.add(ear)
      // eyes: white, iris, pupil, lid line
      const eye = new THREE.Group()
      eye.position.set(sd * 0.038, 0.012, hr * 0.86)
      const white = mesh(new THREE.SphereGeometry(0.017, 16, 12), mat('#fbfbf8', 0.3))
      white.scale.set(1.15, 1, 0.6)
      eye.add(white)
      const iris = mesh(new THREE.SphereGeometry(0.0105, 14, 10), mat(['#4a3020', '#2f5a7a', '#3a6a3a', '#5a4a30'][L.hair % 4], 0.25), 0, 0, 0.008)
      iris.scale.z = 0.5
      eye.add(iris)
      const pupil = mesh(new THREE.SphereGeometry(0.0055, 10, 8), mat('#0a0a0a', 0.1), 0, 0, 0.0125)
      pupil.scale.z = 0.4
      eye.add(pupil)
      this.pupils.push(iris, pupil)
      this.head.add(eye)
      this.eyes.push(eye)
      const brow = mesh(new THREE.CapsuleGeometry(0.006, 0.03, 3, 6), hair, sd * 0.04, 0.045, hr * 0.92)
      brow.rotation.z = Math.PI / 2
      this.head.add(brow)
      this.brows.push(brow)
      const bl = mesh(new THREE.SphereGeometry(0.02, 10, 8), mat('#ff8a80', 0.9, { transparent: true, opacity: 0.35 }), sd * 0.058, -0.03, hr * 0.8)
      bl.scale.z = 0.3
      this.head.add(bl)
      this.blush.push(bl)
    }
    const nose = mesh(new THREE.SphereGeometry(0.017, 12, 10), mat(mix(skinC, '#000000', 0.06), 0.55), 0, -0.012, hr * 0.98)
    nose.scale.set(0.85, 1.1, 1.1)
    this.head.add(nose)
    // mouth: a curved line for mood, an opening for speech
    this.mouthArc = new THREE.Group()
    this.mouthArc.position.set(0, -0.052, hr * 0.9)
    const arc = mesh(new THREE.TorusGeometry(0.022, 0.0045, 6, 16, Math.PI), mat('#7a3a34', 0.6))
    this.mouthArc.add(arc)
    this.head.add(this.mouthArc)
    this.mouthOpen = mesh(new THREE.SphereGeometry(0.02, 14, 10), mat('#5a1f1c', 0.7), 0, -0.055, hr * 0.88)
    this.mouthOpen.scale.set(1.1, 0.5, 0.4)
    this.head.add(this.mouthOpen)
    if (L.glasses) {
      const fr = mat('#26262e', 0.3)
      for (const sd of [-1, 1]) { const g = mesh(new THREE.TorusGeometry(0.024, 0.0035, 6, 20), fr, sd * 0.04, 0.012, hr * 0.98); this.head.add(g) }
      this.head.add(mesh(new THREE.BoxGeometry(0.03, 0.004, 0.004), fr, 0, 0.015, hr * 1.0))
    }
    // hair
    if (L.style !== 4) {
      const cap = mesh(new THREE.SphereGeometry(hr * 1.07, 32, 16, 0, Math.PI * 2, 0, Math.PI * 0.5), hair, 0, 0.004, -0.008)
      cap.rotation.x = -0.32
      cap.scale.set(0.95, 1.08, 1.03)
      this.head.add(cap)
      const back = mesh(new THREE.SphereGeometry(hr * 1.04, 24, 16, 0, Math.PI * 2, Math.PI * 0.4, Math.PI * 0.32), hair, 0, 0.005, -0.012)
      back.rotation.x = -0.9
      back.scale.set(0.95, 1, 1)
      this.head.add(back)
      // fringe
      const fringe = mesh(new THREE.CapsuleGeometry(0.02, 0.1, 4, 10), hair, 0.01, 0.07, hr * 0.78)
      fringe.rotation.z = Math.PI / 2 + 0.25
      fringe.scale.set(1, 1, 0.6)
      this.head.add(fringe)
    }
    if (L.style === 1) { const lh = mesh(new THREE.CapsuleGeometry(0.08, 0.2, 6, 14), hair, 0, -0.1, -0.06); lh.scale.set(1.2, 1, 0.55); this.head.add(lh) }
    if (L.style === 3) { const lh = mesh(new THREE.CapsuleGeometry(0.075, 0.06, 6, 14), hair, 0, -0.05, -0.05); lh.scale.set(1.3, 1, 0.65); this.head.add(lh) }
    if (L.style === 2) this.head.add(mesh(new THREE.SphereGeometry(0.045, 16, 12), hair, 0, 0.1, -0.08))
    if (L.style === 5) for (let k = 0; k < 9; k++) { const a = (k / 9) * Math.PI * 2; this.head.add(mesh(new THREE.SphereGeometry(0.035, 12, 10), hair, Math.cos(a) * 0.085, 0.06 + (k % 2) * 0.02, Math.sin(a) * 0.075 - 0.015)) }
    if (L.beard && !woman) { const bd = mesh(new THREE.SphereGeometry(hr * 0.86, 24, 14, 0, Math.PI * 2, Math.PI * 0.5, Math.PI * 0.42), hair, 0, -0.025, 0.018); bd.scale.set(0.98, 1.05, 1.02); this.head.add(bd) }
    // hats
    if (o.hat === 'chef') {
      const band = mesh(new THREE.CylinderGeometry(0.1, 0.1, 0.06, 24), mat('#ffffff', 0.9), 0, 0.09, -0.005)
      this.head.add(band)
      const puff = mesh(new THREE.SphereGeometry(0.11, 20, 14), mat('#ffffff', 0.95), 0, 0.17, -0.005)
      puff.scale.set(1.05, 0.9, 1.05)
      this.head.add(puff)
    } else if (o.hat === 'beanie') {
      const bn = mesh(new THREE.SphereGeometry(hr * 1.12, 24, 12, 0, Math.PI * 2, 0, Math.PI * 0.5), mat(o.accent === '#fafafa' ? '#c62828' : o.accent, 0.95), 0, 0.02, -0.005)
      bn.rotation.x = -0.15
      this.head.add(bn)
      const rim = mesh(new THREE.TorusGeometry(hr * 1.05, 0.018, 8, 26), mat(mix(o.accent === '#fafafa' ? '#c62828' : o.accent, '#000000', 0.15), 0.95), 0, 0.03, -0.005)
      rim.rotation.x = Math.PI / 2 - 0.15
      this.head.add(rim)
      this.head.add(mesh(new THREE.SphereGeometry(0.03, 10, 8), mat('#f4f4f4', 1), 0, 0.14, -0.02))
    } else if (o.hat === 'cap') {
      const cp = mesh(new THREE.SphereGeometry(hr * 1.1, 24, 12, 0, Math.PI * 2, 0, Math.PI * 0.45), mat(o.top === '#f1f1ec' ? '#1c2a44' : o.top, 0.7), 0, 0.03, -0.005)
      this.head.add(cp)
      const brim = mesh(new THREE.CylinderGeometry(0.08, 0.08, 0.01, 20, 1, false, -Math.PI / 2, Math.PI), mat('#111318', 0.4), 0, 0.055, 0.07)
      this.head.add(brim)
      this.head.add(mesh(new THREE.BoxGeometry(0.03, 0.02, 0.01), mat(o.accent, 0.3, { metalness: 0.7 }), 0, 0.1, hr * 0.98))
    } else if (o.hat === 'veil') {
      const veil = mesh(new THREE.ConeGeometry(0.2, 0.7, 24, 1, true), mat('#ffffff', 0.9, { transparent: true, opacity: 0.4, side: THREE.DoubleSide }), 0, -0.2, -0.1)
      this.head.add(veil)
      this.head.add(mesh(new THREE.TorusGeometry(0.09, 0.012, 8, 20), mat('#f3c6d3', 0.5), 0, 0.09, -0.01))
    }
    // age and frame
    const scale = (p.age < 16 ? 0.82 : 1) * (0.96 + (L.build - 0.5) * 0.04) * (woman ? 0.96 : 1)
    this.body.scale.setScalar(scale)
    this.height = 1.78 * scale
    this.body.traverse((m) => { if ((m as THREE.Mesh).isMesh) { (m as THREE.Mesh).castShadow = true } })
  }

  /** Pose the person for this frame. */
  update(p: Person, scenario: string, state: State, t: number, dt: number, selected: string | null, talking: boolean) {
    this.build(p, scenario)
    const s = this.seed * 10
    const mood = moodOf(p)
    const br = Math.sin(t * 1.9 + s)
    // defaults: relaxed idle
    let lean = 0.02, bob = 0, sway = Math.sin(t * 0.45 + s) * 0.018, roll = Math.sin(t * 0.45 + s) * 0.02
    let hx = Math.sin(t * 0.37 + s) * 0.05, hy = Math.sin(t * 0.29 + s * 2) * 0.22, hz = 0
    const legs: [number, number][] = [[0, 0.02], [0, 0.02]]
    const arms: [number, number, number][] = [[0.05 + br * 0.02, 0.12, 0.18], [0.05 - br * 0.02, 0.12, 0.18]]
    switch (state) {
      case 'walking': {
        const ph = t * 7.8 + s
        const sw = Math.sin(ph)
        legs[0] = [sw * 0.5, Math.max(0, -Math.cos(ph)) * 0.75 + 0.08]
        legs[1] = [-sw * 0.5, Math.max(0, Math.cos(ph)) * 0.75 + 0.08]
        arms[0] = [-sw * 0.45, 0.08, 0.35 + Math.max(0, -sw) * 0.3]
        arms[1] = [sw * 0.45, 0.08, 0.35 + Math.max(0, sw) * 0.3]
        bob = Math.abs(Math.cos(ph)) * 0.03
        lean = 0.07
        hy = 0
        sway = 0
        roll = sw * 0.03
        break
      }
      case 'talking': {
        const g1 = Math.sin(t * 2.3 + s), g2 = Math.sin(t * 1.7 + s * 3)
        arms[1] = [0.55 + g1 * 0.32, 0.22 + g2 * 0.12, 1.15 + Math.sin(t * 3.2 + s) * 0.45]
        arms[0] = [0.3 + g2 * 0.25, 0.18, 0.75 + Math.sin(t * 2.6 + 1) * 0.35]
        hx = Math.sin(t * 4.2) * 0.07
        lean = 0.04 + Math.sin(t * 1.1) * 0.03
        break
      }
      case 'working': {
        arms[0] = [0.95, -0.12, 1.25 + Math.sin(t * 11 + s) * 0.12]
        arms[1] = [0.95, -0.12, 1.25 + Math.sin(t * 11 + s + 1.7) * 0.12]
        lean = 0.16
        hx = 0.35
        hy = Math.sin(t * 0.6) * 0.15
        break
      }
      case 'pondering': {
        arms[1] = [1.15, -0.32, 2.35]
        arms[0] = [0.6, -0.55, 1.55]
        hz = 0.14
        hx = -0.08 + Math.sin(t * 0.8) * 0.05
        hy = 0.25 + Math.sin(t * 0.4) * 0.15
        break
      }
      case 'tired': case 'sad': {
        lean = 0.26
        hx = 0.42
        hy *= 0.3
        arms[0] = [0.12, 0.04, 0.15]
        arms[1] = [0.12, 0.04, 0.15]
        legs[0] = [0.05, 0.12]
        legs[1] = [0.05, 0.12]
        bob = -0.02 + br * 0.005
        break
      }
      case 'happy': {
        bob = Math.max(0, Math.sin(t * 3.4 + s)) * 0.035
        arms[0] = [0.2 + Math.sin(t * 3.4) * 0.15, 0.3, 0.55]
        arms[1] = [0.2 - Math.sin(t * 3.4) * 0.15, 0.3, 0.55]
        hz = Math.sin(t * 1.7 + s) * 0.12
        lean = -0.03
        break
      }
      case 'angry': {
        arms[0] = [0.78, -0.72, 1.95]
        arms[1] = [0.78, -0.72, 1.95]
        if (Math.sin(t * 0.7 + s) > 0.82) arms[1] = [1.4, 0.1, 1.5 + Math.sin(t * 16) * 0.2]
        lean = -0.05
        hx = 0.08
        hy *= 0.5
        break
      }
      case 'scared': {
        lean = 0.14
        arms[0] = [0.95, -0.45, 2.2]
        arms[1] = [0.95, -0.45, 2.2]
        hy = Math.sin(t * 2.2 + s) * 0.45
        sway = Math.sin(t * 31) * 0.004
        legs[0] = [0.08, 0.15]
        legs[1] = [0.08, 0.15]
        bob = -0.02
        break
      }
      case 'gone': {
        break
      }
    }
    // feelings colour the idle states
    if (state === 'calm' && mood === 'calm' && p.stress > 60) hx += 0.05
    // look at whoever is being addressed
    this.yawNow += (this.yaw - this.yawNow) * Math.min(1, dt * 5)
    const lie = state === 'gone' ? 1 : 0
    this.lie += (lie - this.lie) * Math.min(1, dt * 2)
    this.root.rotation.y = this.yawNow * (1 - this.lie)
    this.body.rotation.x = -this.lie * Math.PI / 2
    this.body.position.y = this.lie * 0.12
    this.body.position.z = -this.lie * 0.0
    this.hips.position.y = 0.92 + bob - (legs[0][1] > 0.1 && state !== 'walking' ? 0.02 : 0)
    this.hips.position.x = sway
    this.hips.rotation.z = roll
    this.spine.rotation.x = lean
    this.chest.scale.set(1 + br * 0.008, 1 + br * 0.01, 1 + br * 0.012)
    this.neck.rotation.set(hx * 0.4, hy * 0.4, hz * 0.5)
    this.head.rotation.set(hx * 0.6, hy * 0.6, hz * 0.5)
    for (let i = 0; i < 2; i++) {
      this.legs[i].hip.rotation.x = -legs[i][0]
      this.legs[i].knee.rotation.x = legs[i][1]
      const sd = i ? 1 : -1
      const [fw, out, el] = arms[i]
      this.arms[i].sh.rotation.set(-(fw - lean * 0.5), 0, sd * out)
      this.arms[i].el.rotation.set(-el, 0, 0)
    }
    // face: blink, brows, mouth
    const blink = ((t + this.seed * 4) % 4.3) > 4.18 || p.energy < 15 ? 0.12 : p.energy < 30 ? 0.6 : 1
    for (const e of this.eyes) e.scale.y = blink
    const gazeX = Math.sin(t * 0.5 + s) * 0.003
    for (const pp of this.pupils) pp.position.x = gazeX
    const browTilt = mood === 'angry' ? -0.35 : mood === 'sad' || mood === 'scared' ? 0.3 : 0
    const browUp = mood === 'scared' ? 0.008 : mood === 'happy' ? 0.004 : mood === 'angry' ? -0.004 : 0
    this.brows.forEach((b, k) => { b.rotation.z = Math.PI / 2 + (k ? -1 : 1) * browTilt; b.position.y = 0.045 + browUp })
    const smile = mood === 'happy' ? 1 : mood === 'sad' ? -0.8 : mood === 'angry' ? -0.6 : mood === 'scared' ? -0.4 : (p.mood - 50) / 80
    if (this.mouthOpen) {
      this.mouthOpen.visible = talking || state === 'scared'
      if (talking) this.mouthOpen.scale.y = 0.25 + Math.abs(Math.sin(t * 13 + s)) * 0.55 + Math.abs(Math.sin(t * 7.1)) * 0.2
      else this.mouthOpen.scale.y = 0.35
    }
    this.mouthArc.visible = !talking
    this.mouthArc.rotation.z = smile >= 0 ? Math.PI : 0
    this.mouthArc.scale.set(1, Math.max(0.15, Math.abs(smile)), 1)
    this.mouthArc.position.y = -0.052 + (smile >= 0 ? 0.006 : -0.012) * Math.abs(smile)
    for (const b of this.blush) b.visible = mood === 'happy' || p.traits.extraversion > 0.75
    this.ring.visible = !!selected
    if (selected) {
      ;(this.ring.material as THREE.MeshBasicMaterial).color.set(selected)
      this.ring.scale.setScalar(1 + Math.sin(t * 4) * 0.05)
    }
    this.blob.scale.set(1 + this.lie * 1.4, 1, 1 + this.lie * 0.3)
  }
}
