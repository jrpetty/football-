// First-person arms and weapons: the survivor's own forearms (their skin,
// their sleeves) holding the weapon they carry, built in view space (the
// camera at the origin looking down -z). The weapon is the same detailed
// model the characters hold; the hands are modelled closed round its grip,
// the support hand on the fore-end, pump or second grip. Everything sits in
// one group, `rig`, which the first-person controller sways, bobs, kicks and
// swings; its materials are clean copies, since the world's weathering is
// laid in world space and would crawl over a gun that moves with the camera.
import * as THREE from 'three'
import { Builder } from './kit.js'
import { weaponModel } from './weapons.js'

const V = (x, y, z) => new THREE.Vector3(x, y, z)

// How each weapon sits. pos/rot place the weapon model in view space (guns
// point along their own +z, so ry = PI turns them to face -z). grip: where
// the main hand closes (weapon space) and the handle's axis; support: the
// other hand. sight: the height of the sights over the barrel line, for
// aiming down them. muzzle: the barrel's end (weapon space).
const GUN = (o) => ({ kind: 'gun', rot: [0.02, Math.PI, 0], ...o })
export const POSES = {
  pistol: GUN({ pos: [0.15, -0.135, -0.56], rot: [0.03, Math.PI - 0.1, -0.07], grip: { p: [0, -0.046, -0.02], axis: [0, 1, 0.12], r: 0.017 }, support: { p: [0.004, -0.06, -0.016], axis: [0, 1, 0.12], r: 0.03, cup: true }, sight: 0.061, muzzle: [0, 0.034, 0.16], ads: [0, -0.061, -0.3] }),
  revolver: GUN({ pos: [0.15, -0.137, -0.58], rot: [0.03, Math.PI - 0.1, -0.07], grip: { p: [0, -0.05, -0.024], axis: [0, 1, 0.3], r: 0.017 }, support: { p: [0.004, -0.064, -0.02], axis: [0, 1, 0.3], r: 0.03, cup: true }, sight: 0.072, muzzle: [0, 0.044, 0.23], ads: [0, -0.072, -0.32] }),
  smg: GUN({ pos: [0.095, -0.1, -0.34], grip: { p: [-0.0, -0.045, -0.006], axis: [0, 1, 0.22], r: 0.017 }, support: { p: [0, -0.07, 0.118], axis: [0, 1, -0.1], r: 0.016, under: true }, sight: 0.084, muzzle: [0, 0.04, 0.33], ads: [0, -0.084, -0.2] }),
  ar: GUN({ pos: [0.088, -0.105, -0.3], grip: { p: [0, -0.06, -0.018], axis: [0, 1, 0.3], r: 0.017 }, support: { p: [0, 0.022, 0.33], axis: [0, 0, 1], r: 0.027, fore: true }, sight: 0.083, muzzle: [0, 0.022, 0.61], ads: [0, -0.083, -0.15] }),
  shotgun: GUN({ pos: [0.09, -0.11, -0.28], grip: { p: [0, -0.03, -0.04], axis: [0, 1, 0.55], r: 0.02 }, support: { p: [0, 0.016, 0.34], axis: [0, 0, 1], r: 0.027, fore: true, pump: true }, sight: 0.072, muzzle: [0, 0.046, 0.71], ads: [0, -0.07, -0.16] }),
  rifle: GUN({ pos: [0.1, -0.13, -0.3], grip: { p: [0, -0.036, -0.04], axis: [0, 1, 0.55], r: 0.019 }, support: { p: [0, -0.002, 0.28], axis: [0, 0, 1], r: 0.024, fore: true }, sight: 0.105, muzzle: [0, 0.042, 0.68], ads: [0, -0.105, -0.12], scope: true }),
  crossbow: GUN({ pos: [0.09, -0.105, -0.3], grip: { p: [0, -0.04, -0.035], axis: [0, 1, 0.45], r: 0.019 }, support: { p: [0, -0.0, 0.24], axis: [0, 0, 1], r: 0.022, fore: true }, sight: 0.05, muzzle: [0, 0.03, 0.56], ads: [0, -0.05, -0.16] }),
}
// Melee weapons extend along +y from the hand. Two-handed ones carry the
// support hand lower on the handle.
const MELEE = (o) => ({ kind: 'melee', pos: [0.24, -0.19, -0.48], rot: [-0.38, 0.25, 0.28], grip: { p: [0, 0.02, 0], axis: [0, 1, 0], r: 0.018 }, ...o })
Object.assign(POSES, {
  bat: MELEE({ support: { p: [0, -0.07, 0], axis: [0, 1, 0], r: 0.017 } }),
  nailbat: MELEE({ support: { p: [0, -0.07, 0], axis: [0, 1, 0], r: 0.017 } }),
  pipe: MELEE({ support: { p: [0, -0.06, 0], axis: [0, 1, 0], r: 0.018 } }),
  crowbar: MELEE({ pos: [0.24, -0.17, -0.46], rot: [-0.75, 0.25, 0.28], grip: { p: [0, -0.03, 0], axis: [0, 1, 0], r: 0.013 } }),
  machete: MELEE({ rot: [-0.7, 0.35, 0.28], grip: { p: [0, 0.0, 0], axis: [0, 1, 0], r: 0.014 } }),
  // the handle rises right of the crosshair, the head in view near the top
  axe: MELEE({ pos: [0.22, -0.24, -0.46], rot: [-0.36, 1.2, -0.64], support: { p: [0, -0.06, 0], axis: [0, 1, 0], r: 0.016 } }),
  sledge: MELEE({ pos: [0.22, -0.27, -0.46], rot: [-0.21, 1.4, -0.7], grip: { p: [0, 0.06, 0], axis: [0, 1, 0], r: 0.019 }, support: { p: [0, -0.04, 0], axis: [0, 1, 0], r: 0.018 } }),
  katana: MELEE({ rot: [-0.66, 0.3, 0.3], grip: { p: [0, 0.07, 0], axis: [0, 1, 0], r: 0.015 }, support: { p: [0, -0.04, 0], axis: [0, 1, 0], r: 0.015 } }),
  spear: MELEE({ pos: [0.16, -0.24, -0.3], rot: [-1.38, 0.06, 0.05], grip: { p: [0, 0.3, 0], axis: [0, 1, 0], r: 0.016 }, support: { p: [0, 0.62, 0], axis: [0, 1, 0], r: 0.016, under: true } }),
  fists: { kind: 'fists', pos: [0, 0, 0], rot: [0, 0, 0] },
})
export const poseOf = (id) => POSES[id] || POSES.fists

// What a survivor's arms look like: skin, the outfit's sleeves, and work
// gloves (everyone out here wears them; the colour is theirs).
const GLOVES = ['#3a2e24', '#2a2826', '#5a4630', '#6a5638', '#33352e', '#4a3226', '#7a6a50']
export function fpLook(s, outfit) {
  const L = s.look || {}
  return { skin: L.skin || '#d39d76', top: outfit?.top || {}, gloves: GLOVES[(L.seed || 1) % GLOVES.length], female: !!L.female }
}

// ---------------------------------------------------------------- materials
// Clean copies of the shared materials: same textures and finish, no
// world-space weathering.
const cleanCache = new Map()
function cleanMat(m) {
  let c = cleanCache.get(m)
  if (!c) {
    c = m.clone()
    c.userData = { key: m.userData.key, vm: true }
    if (m.userData.key === 'skin') c.color.setScalar(0.86)
    cleanCache.set(m, c)
  }
  return c
}
function cleanUp(group) {
  group.traverse((o) => {
    if (!o.isMesh) return
    o.material = Array.isArray(o.material) ? o.material.map(cleanMat) : cleanMat(o.material)
    o.castShadow = false
    o.receiveShadow = false
    o.frustumCulled = false
  })
  return group
}

// ---------------------------------------------------------------- hands
// A frame in rig space: origin at the grip, y up the handle, z from the
// wrist towards the handle, x = y cross z.
function frame(origin, axis, toward) {
  const y = axis.clone().normalize()
  const z = toward.clone().addScaledVector(y, -toward.dot(y)).normalize()
  const x = new THREE.Vector3().crossVectors(y, z).normalize()
  return new THREE.Matrix4().makeBasis(x, y, z).setPosition(origin)
}
const pushM = (b, m) => b.stack.push(b.top.clone().multiply(m))

// A hand closed round a handle of radius r running along local y, the wrist
// towards -z. side +1 is a right hand (its back faces -x), -1 a left one.
// Fingers are tubes laid round the handle, a knuckle bump at each joint;
// trigger: the index finger lies along the frame to the trigger instead.
function hand(b, side, r, look, o = {}) {
  const S = look.skin
  const skin = { mat: 'skin', color: S, ao: 0 }
  const glove = look.gloves ? { mat: 'plain', color: look.gloves, ao: 0 } : skin
  const fw = look.female ? 0.0074 : 0.0083
  const R = r + fw
  const sx = (x) => x * side
  // the back of the hand: a rounded block from the knuckles to the wrist
  const kx = -R * 0.92
  b.box(0.026, 0.084, 0.088, { ...glove, x: sx(kx - 0.004), y: -0.004, z: -0.048, ry: sx(0.18), r: 0.012 })
  // the heel of the palm against the handle's far side
  b.box(0.022, 0.07, 0.05, { ...glove, x: sx(R * 0.25), y: -0.012, z: -R - 0.016, ry: sx(-0.3), r: 0.01 })
  // four fingers, index on top
  const ys = [0.027, 0.006, -0.015, -0.034]
  const lens = [1, 1.06, 0.98, 0.8]
  for (let i = 0; i < 4; i++) {
    if (o.trigger && i === 0) continue
    const y = ys[i]
    const sweep = 3.6 * lens[i]
    const at = (t) => {
      const a = Math.PI + 0.12 - t * sweep
      const rr = R * (1 - t * 0.1)
      return V(sx(Math.cos(a) * rr), y - t * 0.004, Math.sin(a) * rr)
    }
    // three joints, each a little slimmer: knuckle, middle, tip
    const fi = i === 3 ? 0.88 : 1
    const J = [at(0), at(0.36), at(0.66), at(1)]
    capSeg(b, J[0], J[1], fw * fi, glove)
    capSeg(b, J[1], J[2], fw * 0.93 * fi, glove)
    capSeg(b, J[2], J[3], fw * 0.85 * fi, glove)
    // the knuckle ridge on the back of the hand
    b.sphere(fw * 1.18 * fi, { ...glove, x: J[0].x, y: J[0].y, z: J[0].z, ws: 10, hs: 8 })
  }
  if (o.trigger) {
    // index finger along the frame, crooked onto the trigger
    const t = o.trigger
    const P = [V(sx(kx), 0.03, -0.006), V(sx(-R * 0.75), 0.036, 0.022), V(sx(-r - 0.006), t[1] + 0.006, t[2] - 0.01), V(sx(-0.002), t[1], t[2] + 0.002)]
    capSeg(b, P[0], P[1], fw, glove)
    capSeg(b, P[1], P[2], fw * 0.93, glove)
    capSeg(b, P[2], P[3], fw * 0.85, glove)
    b.sphere(fw * 1.18, { ...glove, x: P[0].x, y: P[0].y, z: P[0].z, ws: 10, hs: 8 })
  }
  // thumb: from the base of the palm round the near side, over the index
  const th = o.thumbFwd
    ? [[sx(-0.004), 0.004, -R - 0.03], [sx(R * 0.7), 0.022, -R * 0.6], [sx(R * 1.0), 0.03, 0.01], [sx(R * 0.95), 0.032, 0.04]]
    : [[sx(-0.006), 0.0, -R - 0.032], [sx(R * 0.8), 0.022, -R * 0.7], [sx(R * 1.05), 0.034, R * 0.2], [sx(R * 0.55), 0.04, R * 0.95]]
  const TH = th.map((p) => V(...p))
  // the fleshy base, then two joints
  capSeg(b, TH[0], TH[1], fw * 1.45, glove)
  capSeg(b, TH[1], TH[2], fw * 1.18, glove)
  capSeg(b, TH[2], TH[3], fw * 1.06, glove)
  // where the forearm starts (hand space)
  return V(sx(-R * 0.35), -0.006, -0.1)
}

// One rounded joint of a finger between two points.
function capSeg(b, a, c, r, o) {
  const d = c.clone().sub(a)
  const len = d.length()
  if (len < 1e-4) return
  const q = new THREE.Quaternion().setFromUnitVectors(V(0, 1, 0), d.normalize())
  const m = new THREE.Matrix4().compose(a.clone().add(c).multiplyScalar(0.5), q, V(1, 1, 1))
  pushM(b, m)
  b.capsule(r, len, { ...o, cs: 4, seg: 10 })
  b.pop()
}

// A tapered limb between two points (rig space), radius r0 at a, r1 at b.
function limbSeg(b, a, c, r0, r1, o) {
  const d = c.clone().sub(a)
  const len = d.length()
  const q = new THREE.Quaternion().setFromUnitVectors(V(0, 1, 0), d.clone().normalize())
  const m = new THREE.Matrix4().compose(a.clone().add(c).multiplyScalar(0.5), q, V(1, 1, 1))
  pushM(b, m)
  b.cyl(r1, r0, len, { ...o, seg: o.seg ?? 14 })
  b.pop()
}

// The forearm from the wrist to past the elbow (off screen), dressed: a bare
// forearm for short sleeves or none, rolled sleeves above the wrist, or a
// full sleeve with a cuff.
function forearm(b, wrist, elbow, look) {
  const d = elbow.clone().sub(wrist)
  const at = (t) => wrist.clone().addScaledVector(d, t)
  const end = at(1.6)
  const S = { mat: 'skin', color: look.skin, ao: 0 }
  const top = look.top || {}
  const cloth = { mat: top.camo ? 'denim' : top.kind === 'track' ? 'plastic' : 'cloth', color: top.color || '#5a4a3a', ao: 0 }
  const fat = look.female ? 0.9 : 1
  const sleeve = top.sleeves === 'long' ? (top.rolled ? 0.55 : 0.12) : 2
  // the bare arm up to where the sleeve starts
  limbSeg(b, at(-0.02), at(Math.min(sleeve + 0.05, 1.6)), 0.027 * fat, 0.041 * fat + (sleeve > 1 ? 0.004 : 0), S)
  if (look.gloves) limbSeg(b, at(-0.03), at(0.12), 0.031 * fat, 0.033 * fat, { mat: 'plain', color: look.gloves, ao: 0 })
  if (sleeve > 1) return
  const sr = (t) => (0.03 + t * 0.016) * fat
  const s0 = at(sleeve)
  limbSeg(b, s0, end, sr(sleeve) + 0.008, 0.066 * fat, cloth)
  // the cuff, or the roll of a rolled sleeve
  const q = new THREE.Quaternion().setFromUnitVectors(V(0, 0, 1), d.clone().normalize())
  const e = new THREE.Euler().setFromQuaternion(q)
  if (top.rolled) b.torus(sr(sleeve) + 0.008, 0.011, { ...cloth, x: s0.x, y: s0.y, z: s0.z, rx: e.x, ry: e.y, rz: e.z, rs: 8, ts2: 18 })
  else b.torus(sr(sleeve) + 0.006, 0.006, { ...cloth, color: shade(cloth.color, -0.06), x: s0.x, y: s0.y, z: s0.z, rx: e.x, ry: e.y, rz: e.z, rs: 6, ts2: 18 })
  if (top.stripes) {
    const s1 = at(0.62)
    b.torus(sr(0.62) + 0.009, 0.006, { mat: 'glowWhite', color: '#c8c8b0', x: s1.x, y: s1.y, z: s1.z, rx: e.x, ry: e.y, rz: e.z, rs: 4, ts2: 18 })
  }
}
function shade(hex, amt) {
  const c = new THREE.Color(hex)
  c.offsetHSL(0, 0, amt)
  return '#' + c.getHexString()
}

// The view model for a survivor's look and weapon. look: { skin, top
// (the outfit's top: color, sleeves, rolled, camo), gloves, female }.
// Returns { rig, weapon, pose, muzzle (rig space), sightLine }.
export function viewModel(id, mods, look) {
  const pose = poseOf(id)
  const rig = new THREE.Group()
  rig.name = 'viewmodel'
  const b = new Builder()
  const W = new THREE.Matrix4().compose(V(...pose.pos), new THREE.Quaternion().setFromEuler(new THREE.Euler(...pose.rot, 'YXZ')), V(1, 1, 1))
  const toRig = (p) => V(...p).applyMatrix4(W)
  const dirRig = (a) => V(...a).transformDirection(W)
  let weapon = null
  let muzzle = null
  if (pose.kind === 'fists') {
    // a raised guard, both fists
    for (const side of [1, -1]) {
      const g = V(side * 0.15, -0.115, -0.38)
      const F = frame(g, V(-side * 0.35, 1, 0.1), V(side * 0.2, 0.3, -1))
      pushM(b, F)
      const wl = hand(b, side, 0.011, look, { thumbFwd: true })
      b.pop()
      const wrist = wl.applyMatrix4(F)
      forearm(b, wrist, V(side * 0.3, -0.46, -0.2), look)
    }
  } else {
    weapon = cleanUp(weaponModel(id, mods || []))
    W.decompose(weapon.position, weapon.quaternion, weapon.scale)
    rig.add(weapon)
    // main hand: on the grip, the arm from behind and to the right
    const G = pose.grip
    const gp = toRig(G.p)
    const gax = dirRig(G.axis)
    const melee = pose.kind === 'melee'
    const towardMain = melee ? V(-0.35, 0.15, -1) : dirRig([0, 0, 1])
    const F = frame(gp, gax, towardMain)
    pushM(b, F)
    const trig = pose.kind === 'gun' && id !== 'crossbow' ? toTrigger(id) : null
    const wl = hand(b, 1, G.r, look, { trigger: trig })
    b.pop()
    const wristR = wl.applyMatrix4(F)
    const cup = !!pose.support?.cup
    const elbowR = melee ? V(0.36, -0.48, -0.16) : cup ? V(0.2, -0.42, -0.22) : V(0.27, -0.38, -0.02)
    forearm(b, wristR, elbowR, look)
    // support hand
    const Sp = pose.support
    if (Sp) {
      const sp = toRig(Sp.p)
      const sax = dirRig(Sp.axis)
      let toward
      if (Sp.fore) toward = V(0.55, 0.75, 0.12)
      else if (Sp.cup) toward = V(0.5, 0.15, -1)
      else if (Sp.under) toward = V(0.4, 0.8, 0.1)
      else toward = V(0.45, 0.1, -1)
      const H = frame(sp, Sp.fore ? sax : sax, toward)
      pushM(b, H)
      const wl2 = hand(b, -1, Sp.r, look, { thumbFwd: !!Sp.fore })
      b.pop()
      const wristL = wl2.applyMatrix4(H)
      const elbowL = Sp.fore ? V(-0.15, -0.48, -0.1) : melee ? V(0.1, -0.52, -0.16) : V(-0.08, -0.44, -0.24)
      forearm(b, wristL, elbowL, look)
    }
    // where the spent case leaves the gun: the port over the grip, right side
    if (pose.kind === 'gun') pose.eject = toRig([-0.02, pose.sight * 0.62, pose.support?.cup ? 0.03 : 0.08])
    if (pose.muzzle) {
      let mz = pose.muzzle[2]
      if (mods?.includes('suppressor')) mz += 0.2
      muzzle = toRig([pose.muzzle[0], pose.muzzle[1], mz])
    }
  }
  const arms = cleanUp(b.build({ shadow: false, receive: false }))
  arms.name = 'arms'
  rig.add(arms)
  return { rig, weapon, pose, muzzle, weaponId: id }
}
// Where the trigger is, in the main hand's frame. The grip frame's origin is
// the grip point, y up the grip, z forward; the trigger sits forward of the
// grip and a little above it.
function toTrigger(id) {
  const t = { pistol: [0, 0.034, 0.042], revolver: [0, 0.036, 0.045], smg: [0, 0.03, 0.03], ar: [0, 0.036, 0.04], shotgun: [0, 0.028, 0.058], rifle: [0, 0.03, 0.05] }[id]
  return t || [0, 0.032, 0.045]
}
