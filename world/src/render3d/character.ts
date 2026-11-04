// 3D people and animals. Each person is a jointed rig — pelvis, spine, neck,
// shoulders, elbows, hips, knees — built from smooth lit primitives, dressed
// for their era, and posed every frame by the same animation library as the
// 2D view (walk cycles, chopping, mining, picking, fishing, reading, talking,
// cheering, kneeling, sleeping). Rotations are about the joint's X axis:
// a positive 2D "forward" angle swings a limb toward the person's front (+Z).

import * as THREE from 'three'
import type { Agent, Animal } from '../sim/types.ts'
import { animFor, pose, hash, clothesFor, shadeHex, SKIN, HAIR, type Anim } from '../render/people.ts'

const geo = {
  torso: new THREE.CapsuleGeometry(0.068, 0.12, 4, 10),
  pelvis: new THREE.CapsuleGeometry(0.06, 0.03, 4, 10),
  limb: new THREE.CapsuleGeometry(0.026, 0.1, 3, 8),
  leg: new THREE.CapsuleGeometry(0.032, 0.11, 3, 8),
  head: new THREE.SphereGeometry(0.07, 18, 14),
  hair: new THREE.SphereGeometry(0.075, 18, 10, 0, Math.PI * 2, 0, Math.PI * 0.55),
  hairLong: new THREE.CapsuleGeometry(0.06, 0.08, 3, 10),
  bun: new THREE.SphereGeometry(0.035, 10, 8),
  curl: new THREE.SphereGeometry(0.032, 8, 6),
  eye: new THREE.SphereGeometry(0.011, 8, 6),
  nose: new THREE.SphereGeometry(0.012, 6, 5),
  hand: new THREE.SphereGeometry(0.026, 10, 8),
  foot: new THREE.BoxGeometry(0.05, 0.03, 0.09),
  beard: new THREE.SphereGeometry(0.06, 12, 8, 0, Math.PI * 2, Math.PI * 0.5, Math.PI * 0.4),
  skirt: new THREE.CylinderGeometry(0.07, 0.11, 0.2, 14, 1, true),
  crown: new THREE.CylinderGeometry(0.07, 0.072, 0.03, 12, 1, true),
  hat: new THREE.CylinderGeometry(0.05, 0.08, 0.06, 12),
  blob: new THREE.CircleGeometry(0.16, 20),
  // tools
  handle: new THREE.CylinderGeometry(0.008, 0.008, 0.3, 6),
  axeHead: new THREE.BoxGeometry(0.012, 0.06, 0.07),
  pickHead: new THREE.BoxGeometry(0.012, 0.02, 0.18),
  hammerHead: new THREE.BoxGeometry(0.04, 0.04, 0.07),
  spearHead: new THREE.ConeGeometry(0.018, 0.07, 6),
  rod: new THREE.CylinderGeometry(0.004, 0.006, 0.6, 5),
  book: new THREE.BoxGeometry(0.1, 0.07, 0.02),
  sack: new THREE.SphereGeometry(0.07, 10, 8),
  log: new THREE.CylinderGeometry(0.018, 0.018, 0.2, 7),
  blanket: new THREE.BoxGeometry(0.2, 0.06, 0.5),
}
Object.assign(geo, {
  ear: new THREE.SphereGeometry(0.016, 8, 6),
  mouth: new THREE.TorusGeometry(0.016, 0.004, 5, 10, Math.PI),
  brow: new THREE.CapsuleGeometry(0.004, 0.022, 2, 5),
  shoulder: new THREE.SphereGeometry(0.034, 12, 9),
  jaw: new THREE.SphereGeometry(0.058, 14, 10),
})
geo.blob.rotateX(-Math.PI / 2)
const torsoCache = new Map<string, THREE.BufferGeometry>()
/** A tapered torso: hips, waist, chest, shoulders (lathe, flattened front to back). */
function torsoGeo(bw: number, woman: boolean): THREE.BufferGeometry {
  const key = `${bw.toFixed(2)}|${woman}`
  let g = torsoCache.get(key)
  if (g) return g
  const sh = (woman ? 0.074 : 0.086) * bw, wa = (woman ? 0.056 : 0.064) * bw, hp = (woman ? 0.07 : 0.066) * bw, h = 0.24
  const pts = [
    new THREE.Vector2(0.001, -0.01), new THREE.Vector2(hp * 0.9, 0), new THREE.Vector2(hp, h * 0.1), new THREE.Vector2(wa, h * 0.36),
    new THREE.Vector2(sh * 0.93, h * 0.7), new THREE.Vector2(sh, h * 0.86), new THREE.Vector2(sh * 0.75, h * 0.97), new THREE.Vector2(0.03, h), new THREE.Vector2(0.001, h + 0.003),
  ]
  g = new THREE.LatheGeometry(pts, 18)
  g.scale(1, 1, 0.68)
  torsoCache.set(key, g)
  return g
}
geo.foot.translate(0, 0, 0.02)
geo.rod.translate(0, -0.3, 0)
geo.handle.translate(0, -0.15, 0)

const mats = new Map<string, THREE.MeshStandardMaterial>()
export function mat(color: string, rough = 0.75, emissive?: string): THREE.MeshStandardMaterial {
  const key = `${color}|${rough}|${emissive ?? ''}`
  let m = mats.get(key)
  if (!m) {
    m = new THREE.MeshStandardMaterial({ color: new THREE.Color(color), roughness: rough, metalness: 0 })
    if (emissive) m.emissive.set(emissive)
    mats.set(key, m)
  }
  return m
}
const blobMat = new THREE.MeshBasicMaterial({ color: 0x000000, transparent: true, opacity: 0.32, depthWrite: false })
const eyeMat = new THREE.MeshBasicMaterial({ color: 0x1a120c })

function mesh(g: THREE.BufferGeometry, m: THREE.Material, x = 0, y = 0, z = 0): THREE.Mesh {
  const o = new THREE.Mesh(g, m)
  o.position.set(x, y, z)
  return o
}

/** A limb hanging from a pivot: rotate the pivot, the limb follows. */
function limb(g: THREE.BufferGeometry, m: THREE.Material, len: number): { pivot: THREE.Group; end: THREE.Group } {
  const pivot = new THREE.Group()
  pivot.add(mesh(g, m, 0, -len / 2, 0))
  const end = new THREE.Group()
  end.position.y = -len
  pivot.add(end)
  return { pivot, end }
}

/** Scale from the rig's model units to world units (tiles). */
export const PERSON_SCALE = 0.5

export class Person3D {
  root = new THREE.Group()
  private body = new THREE.Group()
  private hips = new THREE.Group()
  private spine = new THREE.Group()
  private neck = new THREE.Group()
  private legs: { hip: THREE.Group; knee: THREE.Group }[] = []
  private arms: { sh: THREE.Group; el: THREE.Group; hand: THREE.Group }[] = []
  private toolHolder = new THREE.Group()
  private load = new THREE.Group()
  private sleeper = new THREE.Group()
  private detail: THREE.Object3D[] = []
  private key = ''
  heading = 0
  lastX = NaN
  lastZ = NaN
  impact = false

  id: number
  constructor(id: number) {
    this.id = id
    this.root.add(this.body)
    const blob = new THREE.Mesh(geo.blob, blobMat)
    blob.position.y = 0.01
    blob.renderOrder = 1
    this.root.add(blob)
  }

  /** (Re)build the body when appearance or era changes. */
  private build(a: Agent, era: number, tribeColor: string, leader: boolean, child: boolean, tool: string | null) {
    const key = `${era}|${tribeColor}|${leader}|${child}|${a.clothing > 20}|${tool}|${a.look.style}`
    if (key === this.key) return
    this.key = key
    this.body.clear()
    this.legs = []
    this.arms = []
    this.detail = []
    const L = a.look
    const o = clothesFor(era, a, tribeColor, child)
    const skin = mat(SKIN[L.skin % SKIN.length], 0.65)
    const hairCol = mat(HAIR[L.hair % HAIR.length], 0.85)
    const body = mat(o.body, 0.8)
    const sleeve = mat(o.sleeve, 0.8)
    const legsM = mat(o.legs, 0.85)
    const shoes = mat(o.shoes, 0.6)
    const bw = L.build

    this.hips = new THREE.Group()
    this.hips.position.y = 0.27
    this.body.add(this.hips)
    const pelvis = mesh(geo.pelvis, o.long ? body : legsM)
    pelvis.scale.set(bw, 1, 0.8)
    pelvis.rotation.z = Math.PI / 2
    pelvis.scale.set(1, bw * 1.05, 0.8)
    this.hips.add(pelvis)
    // legs
    for (const side of [-1, 1]) {
      const thigh = limb(geo.leg, legsM, 0.14)
      thigh.pivot.position.set(side * 0.04 * bw, -0.01, 0)
      const shin = limb(geo.leg, legsM, 0.13)
      thigh.end.add(shin.pivot)
      shin.end.add(mesh(geo.foot, shoes, 0, 0.01, 0))
      this.hips.add(thigh.pivot)
      this.legs.push({ hip: thigh.pivot, knee: shin.pivot })
    }
    if (o.long) {
      const sk = mesh(geo.skirt, body, 0, -0.1, 0)
      sk.scale.set(bw, 1, 0.85)
      this.hips.add(sk)
    }
    // spine & torso
    this.spine = new THREE.Group()
    this.spine.position.y = 0.02
    this.hips.add(this.spine)
    const woman = a.sex === 'female'
    const torso = mesh(torsoGeo(bw, woman), body, 0, -0.01, 0)
    this.spine.add(torso)
    if (o.belt) { const belt = mesh(geo.pelvis, mat(o.trim, 0.6), 0, 0.02, 0); belt.rotation.z = Math.PI / 2; belt.scale.set(0.35, bw * 1.12, 0.82); this.spine.add(belt) }
    if (o.cape) { const cape = mesh(geo.skirt, mat(o.trim, 0.85), 0, 0.06, -0.035); cape.scale.set(bw * 0.95, 1.3, 0.5); this.spine.add(cape) }
    // arms
    for (const side of [-1, 1]) {
      const up = limb(geo.limb, sleeve, 0.12)
      up.pivot.position.set(side * 0.09 * bw, 0.19, 0)
      const fo = limb(geo.limb, side > 0 || o.sleeve !== o.body ? sleeve : sleeve, 0.115)
      up.end.add(fo.pivot)
      const hand = new THREE.Group()
      hand.add(mesh(geo.hand, skin))
      fo.end.add(hand)
      this.spine.add(up.pivot)
      const delt = mesh((geo as unknown as { shoulder: THREE.BufferGeometry }).shoulder, sleeve, side * 0.085 * bw, 0.195, 0)
      delt.scale.set(1, 0.85, 0.9)
      this.spine.add(delt)
      this.arms.push({ sh: up.pivot, el: fo.pivot, hand })
    }
    // neck & head
    this.neck = new THREE.Group()
    this.neck.position.y = 0.24
    this.spine.add(this.neck)
    const headR = child ? 1.38 : 1.14
    const head = new THREE.Group()
    head.position.y = 0.07
    head.scale.setScalar(headR)
    this.neck.add(head)
    head.add(mesh(geo.head, skin))
    const nose = mesh(geo.nose, skin, 0, -0.008, 0.068)
    head.add(nose)
    const G = geo as unknown as Record<string, THREE.BufferGeometry>
    const jaw = mesh(G.jaw, skin, 0, -0.025, 0.008)
    jaw.scale.set(woman ? 0.86 : 0.95, 0.78, 0.92)
    head.add(jaw)
    for (const side of [-1, 1]) { const ear = mesh(G.ear, skin, side * 0.064, -0.004, -0.006); ear.scale.set(0.5, 1, 0.8); head.add(ear) }
    const mouth = mesh(G.mouth, mat('#7a3a34', 0.6), 0, -0.035, 0.062)
    mouth.rotation.z = Math.PI
    mouth.scale.y = 0.5
    head.add(mouth)
    this.detail.push(mouth)
    for (const side of [-1, 1]) { const br = mesh(G.brow, hairCol, side * 0.026, 0.03, 0.064); br.rotation.z = Math.PI / 2 + side * 0.12; head.add(br); this.detail.push(br) }
    for (const side of [-1, 1]) { const e = mesh(geo.eye, eyeMat, side * 0.026, 0.012, 0.06); head.add(e); this.detail.push(e) }
    // hair
    if (L.style !== 4) {
      const h = mesh(geo.hair, hairCol, 0, 0.008, -0.004)
      h.rotation.x = -0.25
      head.add(h)
    } else {
      const h = mesh(geo.hair, mat(shadeHex(SKIN[L.skin % SKIN.length], -0.2), 0.9), 0, 0.012, -0.006); h.rotation.x = -0.25; h.scale.setScalar(0.96); head.add(h)
    }
    if (L.style === 1 || L.style === 3) { const lh = mesh(geo.hairLong, hairCol, 0, -0.05, -0.04); lh.scale.set(1.05, L.style === 1 ? 1 : 0.7, 0.55); head.add(lh) }
    if (L.style === 2) head.add(mesh(geo.bun, hairCol, 0, 0.06, -0.05))
    if (L.style === 5) for (let k = 0; k < 6; k++) { const an = (k / 6) * Math.PI * 2; head.add(mesh(geo.curl, hairCol, Math.cos(an) * 0.055, 0.04 + (k % 2) * 0.012, Math.sin(an) * 0.05 - 0.01)) }
    if (L.beard && !child) { const bd = mesh(geo.beard, hairCol, 0, 0.0, 0.012); bd.scale.set(1, 1.1, 1); head.add(bd) }
    if (leader) { const c = mesh(geo.crown, mat('#ffd34a', 0.25, '#4a3a00'), 0, 0.055, 0); c.rotation.x = -0.2; head.add(c) }
    if (o.hat) { const ht = mesh(geo.hat, mat(o.hat, 0.7), 0, 0.075, 0); head.add(ht) }
    // tool in the right hand
    this.toolHolder = new THREE.Group()
    this.arms[1].hand.add(this.toolHolder)
    if (tool) this.toolHolder.add(makeTool(tool, Math.max(a.toolTier, tool === 'sword' ? Math.min(4, era >= 4 ? 4 : era >= 2 ? 3 : 2) : a.toolTier), era, tribeColor))
    // carried load on the back
    this.load = new THREE.Group()
    this.load.position.set(0, 0.12, -0.08)
    this.spine.add(this.load)
    // sleeping bedroll (shown only while asleep)
    this.sleeper = new THREE.Group()
    const blanket = mesh(geo.blanket, mat(o.trim, 0.95), 0, 0.03, 0)
    this.sleeper.add(blanket)
    const sh = mesh(geo.head, skin, 0, 0.05, 0.28)
    sh.scale.setScalar(0.95)
    this.sleeper.add(sh)
    const shh = mesh(geo.hair, hairCol, 0, 0.06, 0.31)
    shh.rotation.x = 1.2
    this.sleeper.add(shh)
    this.sleeper.visible = false
    this.root.add(this.sleeper)
    this.body.traverse((o2) => { if ((o2 as THREE.Mesh).isMesh) (o2 as THREE.Mesh).castShadow = true })
  }

  update(a: Agent, x: number, y: number, z: number, t: number, era: number, tribeColor: string, leader: boolean, age: number, near: boolean) {
    const { anim, tool } = animFor(a)
    const child = age < 14
    this.build(a, era, tribeColor, leader, child, tool)
    const grow = (child ? 0.55 + (age / 14) * 0.38 : 1) * a.look.height
    this.root.position.set(x, y, z)
    // a tile is about six metres: an adult stands ~0.3 tiles tall, a cottage ~0.8
    this.root.scale.setScalar(grow * PERSON_SCALE)
    // face the direction of travel
    if (!Number.isNaN(this.lastX)) {
      const dx = x - this.lastX, dz = z - this.lastZ
      if (dx * dx + dz * dz > 1e-7) this.heading = Math.atan2(dx, dz)
    }
    this.lastX = x
    this.lastZ = z
    let dh = this.heading - this.root.rotation.y
    dh = Math.atan2(Math.sin(dh), Math.cos(dh))
    this.root.rotation.y += dh * 0.18
    for (const d of this.detail) d.visible = near

    const sleeping = anim === 'sleep'
    this.body.visible = !sleeping
    this.sleeper.visible = sleeping
    if (sleeping) {
      this.sleeper.scale.y = 1 + Math.sin(t * 1.6 + this.id) * 0.06
      this.impact = false
      return
    }
    const P = pose(anim as Anim, t, hash(a.id))
    if (age > 60) P.lean += 0.1
    this.impact = P.impact
    this.hips.position.y = 0.27 - P.hipDrop * 0.55 + P.bob * 0.6 + P.hop * 0.6
    this.spine.rotation.x = P.lean
    this.neck.rotation.x = P.head * 0.6
    for (let i = 0; i < 2; i++) {
      const [thigh, knee] = P.legs[i]
      this.legs[i].hip.rotation.x = -thigh
      this.legs[i].knee.rotation.x = knee
      const [up, el] = P.arms[i]
      // arms are relative to the leaning torso
      this.arms[i].sh.rotation.x = -(up - P.lean)
      this.arms[i].sh.rotation.z = (i ? 1 : -1) * 0.08
      this.arms[i].el.rotation.x = -el
    }
    if (tool) {
      // point tools along the forearm, forward
      this.toolHolder.rotation.set(-Math.PI / 2 + 0.3, 0, 0)
    }
    // carried load
    const carrying = anim === 'carry'
    if (carrying && !this.load.children.length) {
      if ((a.inv.wood ?? 0) > 0) for (let k = 0; k < 3; k++) { const lg = mesh(geo.log, mat('#7a5530'), 0, k * 0.03, 0); lg.rotation.z = Math.PI / 2; this.load.add(lg) }
      else this.load.add(mesh(geo.sack, mat('#b08a5a', 0.95)))
    }
    if (!carrying && this.load.children.length) this.load.clear()
  }

  /** World position of the head top (for labels). */
  headTop(out: THREE.Vector3): THREE.Vector3 {
    out.copy(this.root.position)
    out.y += this.root.scale.y * (this.sleeper.visible ? 0.15 : 0.62)
    return out
  }
  /** World position of the tool tip (for impact particles). */
  toolTip(out: THREE.Vector3): THREE.Vector3 {
    this.toolHolder.updateWorldMatrix(true, false)
    return out.set(0, 0.32, 0).applyMatrix4(this.toolHolder.matrixWorld)
  }

  dispose() {
    this.body.clear()
    this.root.clear()
  }
}

const METAL = ['#8d8f93', '#8d8f93', '#c27a3a', '#9aa3ad', '#c9d1d9', '#f2c94c']

let tribeFlag = '#ffffff'
function makeTool(tool: string, tier: number, era: number, color = '#ffffff'): THREE.Group {
  tribeFlag = color
  const g = new THREE.Group()
  const wood = mat('#7a5230', 0.7)
  const metal = mat(METAL[Math.min(5, tier)], 0.35)
  // the holder points +Y along the tool
  switch (tool) {
    case 'axe': case 'hammer': case 'pick': case 'hoe': {
      const h = mesh(geo.handle, wood, 0, 0.15, 0)
      h.rotation.x = Math.PI
      g.add(h)
      const head = mesh(tool === 'axe' ? geo.axeHead : tool === 'pick' ? geo.pickHead : tool === 'hammer' ? geo.hammerHead : geo.axeHead, tool === 'hoe' ? mat('#8d8f93', 0.4) : metal, 0, 0.3, tool === 'axe' ? 0.03 : 0)
      g.add(head)
      break
    }
    case 'spear': {
      const h = mesh(geo.handle, wood, 0, 0.15, 0); h.scale.y = 1.8; h.rotation.x = Math.PI; g.add(h)
      g.add(mesh(geo.spearHead, metal, 0, 0.55, 0))
      break
    }
    case 'staff': { const h = mesh(geo.handle, mat('#6b4a2b'), 0, 0.15, 0); h.scale.y = 1.6; h.rotation.x = Math.PI; g.add(h); break }
    case 'rod': { const r = mesh(geo.rod, mat(era >= 6 ? '#2d2d2d' : '#9a7a4a', 0.5), 0, 0, 0); r.rotation.x = Math.PI; g.add(r); break }
    case 'book': { const b = mesh(geo.book, mat(era >= 2 ? '#7b3f2a' : '#d9c79a', 0.8), 0, 0.03, 0.02); g.add(b); break }
    case 'club': { const h = mesh(geo.handle, wood, 0, 0.15, 0); h.rotation.x = Math.PI; h.scale.set(1.8, 1, 1.8); g.add(h); g.add(mesh(geo.sack, mat('#6b4a2b', 0.9), 0, 0.3, 0)).scale.setScalar(0.4); break }
    case 'sword': {
      const blade = mesh(geo.axeHead, metal, 0, 0.2, 0); blade.scale.set(0.5, 4.2, 0.45); g.add(blade)
      g.add(mesh(geo.hammerHead, mat('#6b4a2b', 0.6), 0, 0.04, 0)).scale.set(1.6, 0.25, 0.4)
      // a round shield on the off arm is implied by the pose; a guard on the hilt
      break
    }
    case 'bow': {
      const arc = new THREE.Mesh(new THREE.TorusGeometry(0.16, 0.008, 4, 14, Math.PI), wood)
      arc.rotation.z = Math.PI / 2
      arc.position.y = 0.05
      g.add(arc)
      break
    }
    case 'rifle': {
      const stock = mesh(geo.hammerHead, mat(era >= 8 ? '#2a2c30' : '#5a3d26', 0.6), 0, 0.05, 0); stock.scale.set(0.5, 1.6, 0.6); g.add(stock)
      const barrel = mesh(geo.handle, mat('#3a3a40', 0.3), 0, 0.12, 0); barrel.rotation.x = Math.PI; barrel.scale.set(0.8, 1.1, 0.8); g.add(barrel)
      break
    }
    case 'flag': {
      const pole = mesh(geo.handle, mat('#d9c9a0', 0.6), 0, 0.15, 0); pole.rotation.x = Math.PI; pole.scale.y = 2.4; g.add(pole)
      const cloth = new THREE.Mesh(new THREE.PlaneGeometry(0.22, 0.14), new THREE.MeshStandardMaterial({ color: tribeFlag, roughness: 0.9, side: THREE.DoubleSide }))
      cloth.position.set(0, 0.62, 0.11)
      cloth.rotation.y = Math.PI / 2
      g.add(cloth)
      break
    }
  }
  g.traverse((o) => { if ((o as THREE.Mesh).isMesh) (o as THREE.Mesh).castShadow = true })
  return g
}

// ------------------------------------------------------------------ animals

const agio = {
  body: new THREE.CapsuleGeometry(0.11, 0.22, 4, 10),
  head: new THREE.SphereGeometry(0.07, 10, 8),
  snout: new THREE.CapsuleGeometry(0.035, 0.05, 3, 6),
  leg: new THREE.CapsuleGeometry(0.018, 0.16, 3, 6),
  neck: new THREE.CapsuleGeometry(0.04, 0.12, 3, 6),
  ear: new THREE.ConeGeometry(0.02, 0.06, 5),
  antler: new THREE.CylinderGeometry(0.006, 0.008, 0.14, 4),
  tail: new THREE.SphereGeometry(0.03, 6, 5),
}
agio.body.rotateX(Math.PI / 2)
agio.snout.rotateX(Math.PI / 2)

export class Animal3D {
  root = new THREE.Group()
  private legs: THREE.Group[] = []
  private head = new THREE.Group()
  private bodyG = new THREE.Group()
  heading = 0
  lastX = NaN
  lastZ = NaN
  an: Animal
  constructor(an: Animal) {
    this.an = an
    const deer = an.kind === 'deer', rabbit = an.kind === 'rabbit'
    const col = deer ? '#a8693a' : rabbit ? '#b9a78f' : '#4c3a2e'
    const m = mat(col, 0.85)
    const s = rabbit ? 0.45 : an.kind === 'boar' ? 0.9 : 1
    this.root.add(this.bodyG)
    this.bodyG.scale.setScalar(s * 0.5)
    const legH = deer ? 0.2 : rabbit ? 0.08 : 0.12
    const body = mesh(agio.body, m, 0, legH + 0.1, 0)
    body.scale.set(deer ? 0.9 : 1.1, deer ? 0.9 : 1.1, rabbit ? 0.6 : 1)
    this.bodyG.add(body)
    for (const [lx, lz] of [[-0.06, 0.13], [0.06, 0.13], [-0.06, -0.13], [0.06, -0.13]]) {
      const pivot = new THREE.Group()
      pivot.position.set(lx, legH + 0.08, lz)
      const leg = mesh(agio.leg, mat(shadeHex(col, -0.2)), 0, -legH / 2 - 0.02, 0)
      leg.scale.y = legH / 0.16 + 0.2
      pivot.add(leg)
      this.bodyG.add(pivot)
      this.legs.push(pivot)
    }
    this.head.position.set(0, legH + (deer ? 0.32 : 0.14), deer ? 0.2 : 0.22)
    this.bodyG.add(this.head)
    if (deer) { const nk = mesh(agio.neck, m, 0, -0.08, -0.04); nk.rotation.x = 0.5; this.head.add(nk) }
    this.head.add(mesh(agio.head, m))
    this.head.add(mesh(agio.snout, mat(an.kind === 'boar' ? '#c9a28a' : shadeHex(col, -0.1)), 0, -0.015, 0.07))
    for (const side of [-1, 1]) {
      const ear = mesh(agio.ear, m, side * 0.04, rabbit ? 0.09 : 0.06, -0.01)
      if (rabbit) ear.scale.set(1, 2.4, 1)
      this.head.add(ear)
      if (deer && hash(an.id) > 0.45) { const ant = mesh(agio.antler, mat('#6b4a2b'), side * 0.04, 0.12, -0.01); ant.rotation.z = side * 0.4; this.head.add(ant) }
    }
    this.bodyG.add(mesh(agio.tail, mat(deer || rabbit ? '#f2efe6' : col), 0, legH + 0.13, -0.24))
    this.root.traverse((o) => { if ((o as THREE.Mesh).isMesh) (o as THREE.Mesh).castShadow = true })
    const blob = new THREE.Mesh(geo.blob, blobMat)
    blob.position.y = 0.01
    blob.scale.setScalar(s * 0.6)
    this.root.add(blob)
  }
  update(x: number, y: number, z: number, t: number) {
    const moving = Math.hypot(this.an.tx + 0.5 - this.an.x, this.an.ty + 0.5 - this.an.y) > 0.25
    this.root.position.set(x, y, z)
    if (!Number.isNaN(this.lastX)) {
      const dx = x - this.lastX, dz = z - this.lastZ
      if (dx * dx + dz * dz > 1e-7) this.heading = Math.atan2(dx, dz)
    }
    this.lastX = x
    this.lastZ = z
    let dh = this.heading - this.root.rotation.y
    dh = Math.atan2(Math.sin(dh), Math.cos(dh))
    this.root.rotation.y += dh * 0.15
    const g = t * 11 + hash(this.an.id) * 10
    const rabbit = this.an.kind === 'rabbit'
    this.legs.forEach((l, k) => { l.rotation.x = moving ? Math.sin(g + (k === 0 || k === 3 ? 0 : Math.PI)) * 0.6 : 0 })
    this.bodyG.position.y = moving ? Math.abs(Math.sin(g)) * (rabbit ? 0.12 : 0.02) : 0
    const graze = !moving && Math.sin(t * 0.5 + hash(this.an.id) * 20) > 0.2
    this.head.rotation.x = graze ? 0.9 : 0
  }
}
