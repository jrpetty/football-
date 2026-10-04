// Procedural people and zombies. Each character is ONE skinned mesh: a
// continuous lofted body (see body.js) dressed in lofted clothes, hair and
// rigid kit (see dress.js), so detail costs no extra draw calls. Outfits
// follow the survivor's pre-outbreak job.
import * as THREE from 'three'
import { mergeGeometries, mergeVertices } from 'three/addons/utils/BufferGeometryUtils.js'
import { Builder } from './kit.js'
import { lerp, clamp } from '../core/util.js'
import { BONES, BONE, NB, BIND } from './body.js'
import { dress } from './dress.js'

export { BONE }

// Builder that records which bone each rigid part belongs to.
class SkinBuilder extends Builder {
  constructor() {
    super()
    this.boneIdx = 0
    this.keepIndex = true
  }
  // parts in the bone's local frame
  bone(name) {
    this.boneIdx = BONE[name]
    this.stack = [BIND[this.boneIdx].clone()]
    return this
  }
  // parts placed in model (bind) space, skinned to a bone
  rigid(name) {
    this.boneIdx = BONE[name]
    this.stack = [new THREE.Matrix4()]
    return this
  }
  add(geo, o = {}) {
    return super.add(geo, { ao: 0, ...o })
  }
  afterAdd(g) {
    const n = g.attributes.position.count
    const si = new Uint16Array(n * 4)
    const sw = new Float32Array(n * 4)
    for (let i = 0; i < n; i++) {
      si[i * 4] = this.boneIdx
      sw[i * 4] = 1
    }
    g.setAttribute('skinIndex', new THREE.Uint16BufferAttribute(si, 4))
    g.setAttribute('skinWeight', new THREE.Float32BufferAttribute(sw, 4))
  }
  // Merge everything into one geometry with one group per material.
  buildSkinned() {
    const perMat = []
    const mats = []
    for (const { material, geos } of this.batches.values()) {
      // every part is indexed: lofts arrive that way and rigid parts keep theirs
      const merged = geos.length === 1 ? geos[0] : mergeGeometries(geos, false)
      if (geos.length > 1) for (const g of geos) g.dispose()
      if (!merged) continue
      perMat.push(merged)
      mats.push(material)
    }
    const geometry = mergeGeometries(perMat, true)
    for (const g of perMat) g.dispose()
    geometry.computeBoundingSphere()
    this.batches.clear()
    return { geometry, mats }
  }
}
// A fresh skeleton bound to (possibly shared) character geometry.
function skinnedMesh(geometry, mats) {
  const bones = BONES.map(([name, , off]) => {
    const b = new THREE.Bone()
    b.name = name
    b.position.set(...off)
    return b
  })
  BONES.forEach(([, p], i) => p >= 0 && bones[p].add(bones[i]))
  const mesh = new THREE.SkinnedMesh(geometry, mats)
  mesh.add(bones[0])
  mesh.bind(new THREE.Skeleton(bones))
  mesh.castShadow = true
  mesh.receiveShadow = true
  // the bind-pose bounds don't follow the animation, so cull on a sphere
  // roomy enough for any pose (arms out, lying down, a rifle raised): people
  // out of view (or out of the sun's shadow box) cost nothing
  mesh.boundingSphere = new THREE.Sphere(new THREE.Vector3(0, 0.9, 0), 1.7)
  return { mesh, bones }
}

// ---------------------------------------------------------------- palettes
export const SKIN_TONES = ['#e8c3a2', '#dcae8b', '#d39d76', '#b47b55', '#8c5a3a', '#663f28', '#4e2f1e']
export const HAIR_COLORS = ['#1b1612', '#2e221a', '#4a3424', '#6b4a2b', '#9b7444', '#c8a26a', '#8a8a86', '#c9c6c0', '#9a3a26']
const ZSKIN = ['#8f9a80', '#7d8a74', '#9a9e8a', '#76826e', '#a0a08e', '#8a9488']
const pickR = (r, a) => a[Math.floor(r() * a.length)]

// ---------------------------------------------------------------- outfits
// Each occupation maps to a base outfit; zombies get civilian or themed ones.
// top: { kind: tee|shirt|polo|jacket|coat|hoodie|tank|scrubs|uniform|coveralls|chef|cardigan|turnout|track|flannel|gown, color, sleeves: short|long|none, open, collar }
// legs: { kind: jeans|cargo|slacks|scrubs|overalls|coveralls|track|shorts|bare, color }
export const OUTFITS = {
  doctor: { top: { kind: 'coat', color: '#dcdedb', inner: '#7aa3c8', sleeves: 'long', collar: true }, legs: { kind: 'slacks', color: '#3a3f48' }, shoes: { kind: 'shoes', color: '#2a2420' }, extras: ['stethoscope', 'badge'] },
  nurse: { top: { kind: 'scrubs', color: '#4f9a9a', sleeves: 'short', vneck: true }, legs: { kind: 'scrubs', color: '#4f9a9a' }, shoes: { kind: 'sneakers', color: '#e8e8e4' }, extras: ['nametag'] },
  paramedic: { top: { kind: 'uniform', color: '#2f4a3a', sleeves: 'long', collar: true }, legs: { kind: 'cargo', color: '#26302a' }, shoes: { kind: 'boots', color: '#1e1c1a' }, extras: ['stripesArms', 'stripesLegs', 'radio'] },
  police: { top: { kind: 'uniform', color: '#2c3a56', sleeves: 'short', collar: true }, legs: { kind: 'slacks', color: '#222a3c' }, shoes: { kind: 'boots', color: '#141414' }, hat: { kind: 'policecap', color: '#1f2a42' }, extras: ['dutybelt', 'badge', 'radio'] },
  soldier: { top: { kind: 'uniform', color: '#5a6040', sleeves: 'long', collar: true, camo: true }, legs: { kind: 'cargo', color: '#565c3e', camo: true }, shoes: { kind: 'boots', color: '#3a3024' }, hat: { kind: 'patrolcap', color: '#5a6040' }, extras: ['belt', 'pouches'] },
  firefighter: { top: { kind: 'turnout', color: '#a8875a', sleeves: 'long' }, legs: { kind: 'cargo', color: '#9a7c52' }, shoes: { kind: 'boots', color: '#181614' }, extras: ['stripesArms', 'stripesLegs', 'stripesChest', 'suspenders'] },
  farmer: { top: { kind: 'flannel', color: '#a83a2c', check: '#4a1a14', sleeves: 'long', rolled: true }, legs: { kind: 'overalls', color: '#3e5a7a' }, shoes: { kind: 'boots', color: '#5a4030' }, hat: { kind: 'straw', color: '#d4b878' } },
  chef: { top: { kind: 'chef', color: '#dddad2', sleeves: 'long', rolled: true }, legs: { kind: 'slacks', color: '#3a3a3a', check: true }, shoes: { kind: 'shoes', color: '#202020' }, hat: { kind: 'toque', color: '#e0ddd5' }, extras: ['apron', 'neckerchief'] },
  carpenter: { top: { kind: 'flannel', color: '#6a7a4a', check: '#2a3020', sleeves: 'long', rolled: true }, legs: { kind: 'jeans', color: '#3d5270' }, shoes: { kind: 'boots', color: '#7a5a3a' }, extras: ['toolbelt', 'pencil'] },
  mechanic: { top: { kind: 'coveralls', color: '#4a5a6a', sleeves: 'long', rolled: true }, legs: { kind: 'coveralls', color: '#4a5a6a' }, shoes: { kind: 'boots', color: '#2a2420' }, hat: { kind: 'cap', color: '#a83a2c' }, extras: ['rag', 'grease', 'nametag'] },
  electrician: { top: { kind: 'shirt', color: '#4a5a7a', sleeves: 'long', collar: true }, legs: { kind: 'jeans', color: '#30425a' }, shoes: { kind: 'boots', color: '#3a2a20' }, hat: { kind: 'hardhat', color: '#d6d2ca' }, extras: ['hivis', 'toolbelt'] },
  engineer: { top: { kind: 'shirt', color: '#9ab8d8', sleeves: 'long', collar: true, rolled: true }, legs: { kind: 'slacks', color: '#8a7a5a' }, shoes: { kind: 'shoes', color: '#4a3020' }, hat: { kind: 'hardhat', color: '#dcdcd4' }, extras: ['glasses', 'pens'] },
  tailor: { top: { kind: 'shirt', color: '#e8e2d4', sleeves: 'long', collar: true, rolled: true }, legs: { kind: 'slacks', color: '#4a4038' }, shoes: { kind: 'shoes', color: '#2a1e18' }, extras: ['waistcoat', 'tape', 'glasses'] },
  gunsmith: { top: { kind: 'tee', color: '#5a5048', sleeves: 'short' }, legs: { kind: 'cargo', color: '#3a3a30' }, shoes: { kind: 'boots', color: '#3a2a1e' }, extras: ['leatherApron', 'goggles', 'gloves'] },
  hunter: { top: { kind: 'flannel', color: '#5a4a32', check: '#2a2016', sleeves: 'long' }, legs: { kind: 'cargo', color: '#5a5a3a', camo: true }, shoes: { kind: 'boots', color: '#4a3a28' }, hat: { kind: 'cap', color: '#e06a1a' }, extras: ['huntvest'] },
  athlete: { top: { kind: 'track', color: '#2a4a8a', stripe: '#f0f0f0', sleeves: 'long', zip: true }, legs: { kind: 'track', color: '#2a4a8a', stripe: '#f0f0f0' }, shoes: { kind: 'sneakers', color: '#e84a3a' }, extras: ['headband'] },
  student: { top: { kind: 'hoodie', color: '#7a3a5a', sleeves: 'long' }, legs: { kind: 'jeans', color: '#4a6080' }, shoes: { kind: 'sneakers', color: '#f0f0f0' }, extras: ['backpackSmall'] },
  teacher: { top: { kind: 'cardigan', color: '#8a6a4a', inner: '#e8e0d0', sleeves: 'long', open: true }, legs: { kind: 'slacks', color: '#4a4a52' }, shoes: { kind: 'shoes', color: '#3a2a20' }, extras: ['glasses', 'tie'] },
  plumber: { top: { kind: 'tee', color: '#8a8a88', sleeves: 'short' }, legs: { kind: 'overalls', color: '#2a4a8a' }, shoes: { kind: 'boots', color: '#2a2420' }, hat: { kind: 'cap', color: '#2a4a8a' }, extras: ['wrench'] },
  builder: { top: { kind: 'tee', color: '#c8b89a', sleeves: 'short' }, legs: { kind: 'jeans', color: '#3d5270' }, shoes: { kind: 'boots', color: '#8a6a3a' }, hat: { kind: 'hardhat', color: '#f0c020' }, extras: ['hivis', 'toolbelt'] },
  clerk: { top: { kind: 'polo', color: '#2a7a4a', sleeves: 'short', collar: true }, legs: { kind: 'slacks', color: '#a8956a' }, shoes: { kind: 'sneakers', color: '#3a3a3a' }, extras: ['nametag'] },
  excon: { top: { kind: 'tank', color: '#d6d2ca', sleeves: 'none' }, legs: { kind: 'jeans', color: '#2a3448' }, shoes: { kind: 'boots', color: '#1a1a1a' }, extras: ['tattoos', 'bandana'] },
  drifter: { top: { kind: 'coat', color: '#5a4a3a', inner: '#7a6a5a', sleeves: 'long', open: true, collar: true }, legs: { kind: 'jeans', color: '#3a3a3a' }, shoes: { kind: 'boots', color: '#3a2a1e' }, hat: { kind: 'beanie', color: '#5a3a2a' }, extras: ['scarf'] },
  guard: { top: { kind: 'uniform', color: '#2a2a2e', sleeves: 'long', collar: true }, legs: { kind: 'slacks', color: '#1e1e22' }, shoes: { kind: 'boots', color: '#111' }, hat: { kind: 'cap', color: '#1e1e22' }, extras: ['dutybelt', 'securityvest'] },
  scout: { top: { kind: 'jacket', color: '#4a5236', sleeves: 'long', collar: true, open: true, inner: '#3a3a32' }, legs: { kind: 'cargo', color: '#4a4a36', camo: true }, shoes: { kind: 'boots', color: '#3a2e22' }, hat: { kind: 'beanie', color: '#3a4030' }, extras: ['scarf', 'pouches'] },
}
const CIVILIAN_TOPS = [
  { kind: 'tee', sleeves: 'short' },
  { kind: 'shirt', sleeves: 'long', collar: true },
  { kind: 'hoodie', sleeves: 'long' },
  { kind: 'polo', sleeves: 'short', collar: true },
  { kind: 'flannel', sleeves: 'long', check: '#2a2020' },
  { kind: 'jacket', sleeves: 'long', open: true, collar: true },
  { kind: 'tank', sleeves: 'none' },
]
const CIV_COLORS = ['#7a3b33', '#3f5566', '#5a6b4a', '#8a7a5a', '#4a4f57', '#6b6f3a', '#34464a', '#8c5a2e', '#57466b', '#a89a7a', '#2e3a4a', '#9a4a3a']
const PANTS_COLORS = ['#2f3640', '#3d3a33', '#4a4234', '#2b3a33', '#3d5270', '#1f2a36', '#5a5040', '#2a3448']

export function zombieOutfit(theme, r) {
  if (theme === 'hospital') return r() < 0.5 ? { top: { kind: 'gown', color: '#a8c0c8', sleeves: 'short' }, legs: { kind: 'bare' }, shoes: { kind: 'bare' } } : { ...OUTFITS.nurse, extras: [] }
  if (theme === 'police') return { ...OUTFITS.police, extras: ['dutybelt'] }
  if (theme === 'riot') return { ...OUTFITS.police, hat: null, extras: ['dutybelt'], armor: 'riot' }
  if (theme === 'military') return { ...OUTFITS.soldier, hat: { kind: 'helmet', color: '#4a5038' }, armor: r() < 0.5 ? 'military' : null }
  if (theme === 'worker') return { top: { kind: 'tee', color: pickR(r, CIV_COLORS), sleeves: 'short' }, legs: { kind: 'jeans', color: pickR(r, PANTS_COLORS) }, shoes: { kind: 'boots', color: '#5a4030' }, hat: r() < 0.6 ? { kind: 'hardhat', color: '#f0c020' } : null, extras: ['hivis'] }
  if (theme === 'chef') return OUTFITS.chef
  // special infected: each one reads at a glance
  if (theme === 'screamer') return { top: { kind: 'gown', color: '#d8d4c4', sleeves: 'none' }, legs: { kind: 'bare' }, shoes: { kind: 'bare' }, extras: [] }
  if (theme === 'stalker') return { top: { kind: 'hoodie', color: '#1e2220', sleeves: 'long' }, legs: { kind: 'jeans', color: '#1a1e22' }, shoes: { kind: 'boots', color: '#141414' }, extras: [] }
  if (theme === 'bloater') return { top: { kind: 'tank', color: '#8a8a6a', sleeves: 'none' }, legs: { kind: 'slacks', color: '#4a4436' }, shoes: { kind: 'bare' }, extras: [] }
  const top = { ...pickR(r, CIVILIAN_TOPS), color: pickR(r, CIV_COLORS) }
  if (top.open) top.inner = pickR(r, CIV_COLORS)
  return {
    top,
    legs: { kind: r() < 0.15 ? 'shorts' : r() < 0.6 ? 'jeans' : 'slacks', color: pickR(r, PANTS_COLORS) },
    shoes: { kind: pickR(r, ['sneakers', 'shoes', 'boots']), color: pickR(r, ['#2a2420', '#e8e8e4', '#4a3a2a', '#1a1a1a']) },
    hat: r() < 0.15 ? { kind: pickR(r, ['cap', 'beanie']), color: pickR(r, CIV_COLORS) } : null,
    extras: r() < 0.2 ? ['backpackSmall'] : [],
  }
}

// ---------------------------------------------------------------- body
// spec: { skin, hair:{style,color}, face:{beard}, build (0.85..1.35), height,
// female, outfit, armor, pack, zombie:{kind}, seed, res (mesh detail) }
export function buildCharacterGeometry(spec) {
  const b = new SkinBuilder()
  const r = rngFrom(spec.seed || 1)
  dress(b, { ...spec, outfit: spec.outfit || OUTFITS.drifter }, r)
  return b.buildSkinned()
}
export function buildCharacterMesh(spec) {
  const { geometry, mats } = buildCharacterGeometry(spec)
  return skinnedMesh(geometry, mats)
}
// Characters that share a cacheKey (zombie variants) share one geometry, each
// with its own skeleton. Unused entries are kept for reuse, oldest disposed
// once there are too many.
const SHARED = new Map()
const SHARED_IDLE_MAX = 40
function acquireShared(spec) {
  let e = SHARED.get(spec.cacheKey)
  if (!e) {
    e = { ...buildCharacterGeometry(spec), refs: 0, last: 0 }
    SHARED.set(spec.cacheKey, e)
  }
  e.refs++
  return skinnedMesh(e.geometry, e.mats)
}
function releaseShared(key) {
  const e = SHARED.get(key)
  if (!e) return
  e.refs = Math.max(0, e.refs - 1)
  e.last = performance.now()
  const idle = [...SHARED.entries()].filter(([, v]) => v.refs === 0)
  if (idle.length <= SHARED_IDLE_MAX) return
  idle.sort((a, b) => a[1].last - b[1].last)
  for (const [k, v] of idle.slice(0, idle.length - SHARED_IDLE_MAX)) {
    v.geometry.dispose()
    SHARED.delete(k)
  }
}


// ---------------------------------------------------------------- helpers
function shade(hex, amt) {
  const c = new THREE.Color(hex)
  c.offsetHSL(0, 0, amt)
  return '#' + c.getHexString()
}
function rngFrom(seed) {
  let s = (Math.abs(seed) * 2654435761) >>> 0 || 1
  return () => {
    s = (s + 0x6d2b79f5) | 0
    let t = Math.imul(s ^ (s >>> 15), 1 | s)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}
export function hashStr(s) {
  let h = 2166136261
  for (let i = 0; i < s.length; i++) {
    h ^= s.charCodeAt(i)
    h = Math.imul(h, 16777619)
  }
  return h >>> 0
}
export { shade, rngFrom }

// ---------------------------------------------------------------- animation
// A pose is a set of target rotations per bone plus a hip height offset.
// Animations write targets; the character eases towards them.
const P = new Float32Array(NB * 3)
const ZERO = new Float32Array(NB * 3)
const set = (bone, x = 0, y = 0, z = 0) => {
  const i = BONE[bone] * 3
  P[i] = x
  P[i + 1] = y
  P[i + 2] = z
}
const add = (bone, x = 0, y = 0, z = 0) => {
  const i = BONE[bone] * 3
  P[i] += x
  P[i + 1] += y
  P[i + 2] += z
}
const get = (bone, k) => P[BONE[bone] * 3 + k]

function armsDown(spread = 0.07) {
  set('upperArmL', 0, 0, spread)
  set('upperArmR', 0, 0, -spread)
  set('foreArmL', -0.12, 0, 0)
  set('foreArmR', -0.12, 0, 0)
}
function legsStraight() {
  for (const s of ['L', 'R']) {
    set('thigh' + s)
    set('shin' + s, 0.03)
    set('foot' + s)
  }
}
const smoothstep = (a, b, x) => {
  const t = clamp((x - a) / (b - a), 0, 1)
  return t * t * (3 - 2 * t)
}
// a repeatable random number in 0..1 for slot n of a character
const hash1 = (n, seed) => {
  const x = Math.sin(n * 127.1 + seed * 311.7) * 43758.5453
  return x - Math.floor(x)
}
// Walking gait driven by phase; amp 0..1 (1 = run). The knee gives a
// little as the heel lands, folds to clear the ground in the swing, and the
// foot rolls heel to toe: toes up at the strike, pushing off behind.
// drag: how much one leg (the right) is dragged, for the dead.
function gait(ph, amp, Z, drag = 0) {
  const s = Math.sin(ph)
  const c = Math.cos(ph)
  const th = lerp(0.46, 0.88, amp)
  const kn = lerp(0.85, 1.45, amp)
  const thL = Z ? th * 0.75 : th
  const thR = Z ? th * lerp(0.75, 0.4, drag) : th
  set('thighL', -s * thL - amp * 0.08, 0, 0.012)
  set('thighR', s * thR - amp * 0.08, 0, -0.012)
  // swing-phase fold, and a give on landing (just after the leg is forward)
  const landL = Math.exp(-(((ph % TAU) - 1.75) ** 2) * 3) * 0.18
  const landR = Math.exp(-((((ph + Math.PI) % TAU) - 1.75) ** 2) * 3) * 0.18
  const kL = 0.06 + Math.pow(Math.max(0, c), 1.5) * kn + landL * (1 - amp * 0.5)
  const kR = 0.06 + Math.pow(Math.max(0, -c), 1.5) * kn * (Z ? lerp(1, 0.35, drag) : 1) + landR * (1 - amp * 0.5)
  set('shinL', kL)
  set('shinR', kR)
  // heel to toe: flat under the body, toes up ahead, pointed as it lifts off
  const rollL = -Math.max(0, -get('thighL', 0)) * 0.35 + Math.max(0, s) * Math.max(0, -c) * 0.55
  const rollR = -Math.max(0, -get('thighR', 0)) * 0.35 + Math.max(0, -s) * Math.max(0, c) * 0.55 * (1 - drag * 0.6)
  set('footL', -(get('thighL', 0) + kL) * 0.72 + rollL)
  set('footR', -(get('thighR', 0) + kR) * 0.72 + rollR)
  return { s, c }
}
const TAU = Math.PI * 2
// Locomotion for the living: walk and run are one gait blended by speed, so
// speeding up never pops. ch.gaitAmp eases towards the speed's amplitude.
function locomote(ch, t, o, minAmp) {
  const v = o.speed ?? 0
  const want = Math.max(minAmp, clamp((v - 1.7) / 2.2, 0, 1))
  ch.gaitAmp += (want - ch.gaitAmp) * Math.min(1, (o.dt || 0.016) * 4)
  const a = ch.gaitAmp
  const { s, c } = gait(ch.phase, a, false)
  const lag = Math.sin(ch.phase - 0.55)
  // pelvis: turns with the legs, dips towards the swing leg, bobs twice a stride
  set('hips', 0, s * lerp(0.07, 0.13, a), c * lerp(0.035, 0.05, a))
  set('spine', lerp(0.05, 0.24, a), 0, -c * 0.02)
  set('chest', lerp(0.02, 0.08, a), -s * lerp(0.1, 0.2, a), 0)
  // shoulders roll with the arms
  set('shoulderL', 0, s * 0.06, 0)
  set('shoulderR', 0, s * 0.06, 0)
  // arms swing against the legs, forearms trailing a little behind
  const sw = lerp(0.42, 0.9, a)
  set('upperArmL', s * sw, 0, lerp(0.08, 0.14, a))
  set('upperArmR', -s * sw, 0, -lerp(0.08, 0.14, a))
  set('foreArmL', lerp(-0.22, -1.35, a) - Math.max(0, -lag) * lerp(0.35, 0.25, a))
  set('foreArmR', lerp(-0.22, -1.35, a) - Math.max(0, lag) * lerp(0.35, 0.25, a))
  set('handL', 0.1, 0, 0)
  set('handR', 0.1, 0, 0)
  // the head stays level: it counters the lean and the shoulders' turn
  set('neck', -lerp(0.02, 0.12, a), s * 0.04, 0)
  set('head', -lerp(0.02, 0.1, a) + Math.abs(c) * 0.02, s * 0.05, 0)
  ch.hipsY = -0.015 - a * 0.03 + Math.abs(c) * lerp(0.03, 0.075, a)
}

export const ANIMS = {
  // Standing: breathing, the weight on one leg then the other every few
  // seconds, and the head glancing about in looks that hold, not a sway.
  idle(ch, t) {
    const br = Math.sin(t * 1.55 + ch.seed)
    armsDown(0.075 + br * 0.008)
    legsStraight()
    // weight shift: which leg carries it, changed every 4-8 s
    const slot = Math.floor((t + ch.seed * 3) / (4 + (ch.seed % 1) * 4))
    const side = hash1(slot, ch.seed) < 0.5 ? 1 : -1
    ch.idleW += (side - ch.idleW) * 0.02
    const w = ch.idleW
    set('hips', 0, w * 0.04, w * 0.045)
    set('thighL', -0.02 - Math.max(0, -w) * 0.06, 0, 0.02 + w * 0.02)
    set('thighR', -0.02 - Math.max(0, w) * 0.06, 0, -0.02 + w * 0.02)
    set('shinL', 0.03 + Math.max(0, -w) * 0.16)
    set('shinR', 0.03 + Math.max(0, w) * 0.16)
    set('footL', -Math.max(0, -w) * 0.08)
    set('footR', -Math.max(0, w) * 0.08)
    set('spine', 0.02, 0, -w * 0.03)
    set('chest', br * 0.022, 0, -w * 0.02)
    set('shoulderL', 0, 0, br * 0.012)
    set('shoulderR', 0, 0, -br * 0.012)
    // glances: a new look every 2-5 s, held
    const gs = Math.floor((t + ch.seed * 7) / (2 + hash1(Math.floor((t + ch.seed * 7) / 9), ch.seed) * 3))
    const gy = (hash1(gs, ch.seed + 1) - 0.5) * 1.0
    const gx = (hash1(gs, ch.seed + 2) - 0.5) * 0.22
    set('neck', gx * 0.4, gy * 0.35, 0)
    set('head', gx * 0.6 + br * 0.01, gy * 0.65, w * 0.03)
    ch.hipsY = -Math.abs(w) * 0.012
  },
  walk(ch, t, o) {
    locomote(ch, t, o, 0)
  },
  run(ch, t, o) {
    locomote(ch, t, o, 0.65)
  },
  // holding a long gun in the low ready
  rifleReady(ch) {
    set('upperArmR', -0.45, 0.2, -0.15)
    set('foreArmR', -1.15, 0, 0)
    set('upperArmL', -0.85, -0.3, -0.25)
    set('foreArmL', -1.0, 0, 0)
    set('chest', get('chest', 0), get('chest', 1) * 0.3 + 0.12, 0)
  },
  pistolReady(ch) {
    set('upperArmR', -0.35, 0, -0.1)
    set('foreArmR', -0.75, 0, 0)
  },
  meleeReady(ch) {
    set('upperArmR', -0.35, 0, -0.18)
    set('foreArmR', -1.1, 0, 0)
  },
  aim(ch, t, o) {
    legsStraight()
    set('thighL', -0.12)
    set('thighR', 0.12)
    set('spine', 0.04)
    if (ch.hold === 'rifle') {
      set('chest', 0.0, 0.32, 0)
      set('upperArmR', -1.25, 0.25, -0.25)
      set('foreArmR', -0.95, 0, 0)
      set('upperArmL', -1.45, -0.55, -0.1)
      set('foreArmL', -0.35, 0, 0)
      // the wrist turned so the barrel lies level from the shoulder
      set('handR', 0.6, 0, 0)
      set('head', 0.08, -0.3, 0)
    } else {
      set('chest', 0, 0.08, 0)
      set('upperArmR', -1.52, 0.1, -0.05)
      set('foreArmR', -0.05)
      set('upperArmL', -1.4, -0.45, 0.05)
      set('foreArmL', -0.3)
      set('head', 0.05, -0.08, 0)
    }
    ch.hipsY = -0.02
  },
  swing(ch, t, o) {
    const s = o.swing ?? 0
    legsStraight()
    set('thighL', -0.3)
    set('thighR', 0.25)
    set('shinR', 0.2)
    let ua
    let fa
    let sp
    let cx
    if (s < 0.38) {
      const k = s / 0.38
      ua = lerp(-0.5, -2.7, k)
      fa = lerp(-1.0, -1.25, k)
      sp = lerp(0.1, 0.55, k)
      cx = lerp(0.05, -0.12, k)
    } else if (s < 0.58) {
      const k = (s - 0.38) / 0.2
      ua = lerp(-2.7, -0.35, k)
      fa = lerp(-1.25, -0.15, k)
      sp = lerp(0.55, -0.55, k)
      cx = lerp(-0.12, 0.38, k)
    } else {
      const k = (s - 0.58) / 0.42
      ua = lerp(-0.35, -0.5, k)
      fa = lerp(-0.15, -1.0, k)
      sp = lerp(-0.55, 0.1, k)
      cx = lerp(0.38, 0.05, k)
    }
    set('upperArmR', ua, 0.2, -0.3)
    set('foreArmR', fa)
    set('upperArmL', ua * 0.85, -0.2, 0.2)
    set('foreArmL', fa)
    // the hips lead the blow and the weight goes onto the front foot
    const strike = s < 0.38 ? 0 : s < 0.58 ? (s - 0.38) / 0.2 : 1 - (s - 0.58) / 0.42
    set('hips', 0, sp * 0.35, 0)
    set('thighL', -0.3 - strike * 0.22)
    set('shinL', 0.12 + strike * 0.18)
    set('thighR', 0.25 + strike * 0.12)
    set('spine', 0.08 + strike * 0.12, sp * 0.4, 0)
    set('chest', cx, sp * 0.6, 0)
    set('head', 0.08 + strike * 0.1, -sp * 0.35, 0)
    ch.hipsY = -0.05 - strike * 0.05
  },
  throw(ch, t, o) {
    const s = o.swing ?? 0
    legsStraight()
    set('thighL', -0.35)
    set('thighR', 0.3)
    const k = s < 0.5 ? s / 0.5 : 1 - (s - 0.5) / 0.5
    set('upperArmR', lerp(-0.3, -2.9, k), 0, -0.25)
    set('foreArmR', lerp(-0.3, -1.4, k))
    set('upperArmL', -1.1, 0, 0.2)
    set('chest', lerp(0.2, -0.15, k), lerp(-0.4, 0.5, k), 0)
  },
  search(ch, t) {
    set('thighL', -1.3, 0, 0.12)
    set('thighR', -1.1, 0, -0.12)
    set('shinL', 2.0)
    set('shinR', 1.8)
    set('footL', -0.7)
    set('footR', -0.7)
    set('spine', 0.35)
    set('chest', 0.15)
    const a = Math.sin(t * 7.5)
    set('upperArmL', -1.1 + a * 0.25, 0, 0.1)
    set('upperArmR', -1.0 - a * 0.25, 0, -0.1)
    set('foreArmL', -0.7 - Math.max(0, a) * 0.3)
    set('foreArmR', -0.7 - Math.max(0, -a) * 0.3)
    set('head', 0.35, Math.sin(t * 1.3) * 0.2, 0)
    ch.hipsY = -0.36
  },
  hammer(ch, t) {
    legsStraight()
    const a = Math.pow(Math.max(0, Math.sin(t * 6.5)), 0.6)
    set('spine', 0.18)
    set('chest', 0.1, 0.1, 0)
    set('upperArmR', -0.6 - a * 1.4, 0, -0.2)
    set('foreArmR', -1.3 + a * 0.6)
    set('upperArmL', -0.8, 0.2, 0.1)
    set('foreArmL', -0.9)
    set('head', 0.4)
  },
  // shoulder to a wardrobe: legs braced, arms out, heaving in time
  push(ch, t) {
    const a = Math.sin(t * 3.2)
    set('thighL', -0.55 - a * 0.08)
    set('shinL', 0.45)
    set('thighR', 0.35 + a * 0.08)
    set('shinR', 0.15)
    set('footL', 0.1)
    set('spine', 0.45 + a * 0.05)
    set('chest', 0.12)
    set('upperArmL', -1.35 + a * 0.08, 0, 0.12)
    set('upperArmR', -1.35 + a * 0.08, 0, -0.12)
    set('foreArmL', -0.35)
    set('foreArmR', -0.35)
    set('head', -0.15)
    ch.hipsY = -0.08
  },
  saw(ch, t) {
    legsStraight()
    set('thighL', -0.2)
    const a = Math.sin(t * 5)
    set('spine', 0.3)
    set('chest', 0.12, 0.25, 0)
    set('upperArmR', -0.7 + a * 0.35, 0, -0.1)
    set('foreArmR', -0.9 - a * 0.4)
    set('upperArmL', -0.9, -0.3, 0.2)
    set('foreArmL', -0.6)
    set('head', 0.45)
  },
  hoe(ch, t) {
    legsStraight()
    set('thighL', -0.25)
    set('thighR', 0.15)
    const a = Math.sin(t * 2.8)
    set('spine', 0.45 + a * 0.12)
    set('chest', 0.15)
    set('upperArmR', -1.2 - a * 0.7, 0, -0.1)
    set('upperArmL', -1.0 - a * 0.7, 0, 0.1)
    set('foreArmR', -0.4)
    set('foreArmL', -0.5)
    set('head', 0.4)
    ch.hipsY = -0.05
  },
  pump(ch, t) {
    legsStraight()
    const a = Math.sin(t * 4)
    set('spine', 0.25 + a * 0.08)
    set('upperArmR', -1.0 - a * 0.4, 0, -0.1)
    set('upperArmL', -1.0 - a * 0.4, 0, 0.1)
    set('foreArmR', -0.6)
    set('foreArmL', -0.6)
    set('head', 0.3)
  },
  stir(ch, t) {
    legsStraight()
    const a = t * 4
    set('spine', 0.15)
    set('upperArmR', -0.9 + Math.sin(a) * 0.15, Math.cos(a) * 0.2, -0.15)
    set('foreArmR', -0.9)
    set('upperArmL', -0.5, 0, 0.15)
    set('foreArmL', -1.2)
    set('head', 0.4)
  },
  type(ch, t) {
    legsStraight()
    set('spine', 0.12)
    set('upperArmR', -0.55, 0, -0.08)
    set('upperArmL', -0.55, 0, 0.08)
    set('foreArmR', -1.05 + Math.sin(t * 13) * 0.05)
    set('foreArmL', -1.05 + Math.sin(t * 11 + 1) * 0.05)
    set('head', 0.3 + Math.sin(t * 0.5) * 0.05, Math.sin(t * 0.3) * 0.2, 0)
  },
  shovel(ch, t) {
    legsStraight()
    set('thighL', -0.35)
    set('shinL', 0.3)
    const a = Math.sin(t * 3)
    set('spine', 0.5 + a * 0.25)
    set('chest', 0.1, a * 0.3, 0)
    set('upperArmR', -0.9 - a * 0.5, 0, -0.1)
    set('upperArmL', -1.2 - a * 0.5, 0, 0.2)
    set('foreArmR', -0.5)
    set('foreArmL', -0.4)
    ch.hipsY = -0.06
  },
  lookout(ch, t) {
    legsStraight()
    set('upperArmR', -1.55, 0.6, -0.1)
    set('upperArmL', -1.55, -0.6, 0.1)
    set('foreArmR', -2.0)
    set('foreArmL', -2.0)
    set('head', 0.05, Math.sin(t * 0.25) * 0.7, 0)
    set('chest', 0, Math.sin(t * 0.25) * 0.3, 0)
  },
  carry(ch, t) {
    ANIMS.walk(ch, t)
    set('upperArmR', -0.8, 0, -0.1)
    set('upperArmL', -0.8, 0, 0.1)
    set('foreArmR', -1.1)
    set('foreArmL', -1.1)
  },
  sit(ch, t) {
    set('thighL', -1.5, 0, 0.12)
    set('thighR', -1.5, 0, -0.12)
    set('shinL', 1.55)
    set('shinR', 1.55)
    set('footL', -0.1)
    set('footR', -0.1)
    set('spine', 0.2)
    set('chest', 0.08)
    set('upperArmL', -0.75, 0, 0.12)
    set('upperArmR', -0.75, 0, -0.12)
    set('foreArmL', -0.75 + Math.sin(t * 0.7) * 0.05)
    set('foreArmR', -0.75)
    set('head', 0.15 + Math.sin(t * 0.3 + ch.seed) * 0.1, Math.sin(t * 0.21 + ch.seed) * 0.4, 0)
    ch.hipsY = -0.52
  },
  // on a bike saddle, leaning on the bars, legs going round
  pedal(ch, t) {
    const a = t * 5.2
    set('thighL', -1.05 + Math.sin(a) * 0.32, 0, 0.06)
    set('thighR', -1.05 - Math.sin(a) * 0.32, 0, -0.06)
    set('shinL', 1.15 - Math.cos(a) * 0.38)
    set('shinR', 1.15 + Math.cos(a) * 0.38)
    set('footL', -0.15 + Math.sin(a) * 0.15)
    set('footR', -0.15 - Math.sin(a) * 0.15)
    set('spine', 0.55 + Math.sin(a * 2) * 0.02)
    set('chest', 0.18, Math.sin(a) * 0.06, 0)
    set('upperArmL', -1.15, 0, 0.18)
    set('upperArmR', -1.15, 0, -0.18)
    set('foreArmL', -0.45)
    set('foreArmR', -0.45)
    set('head', -0.2 + Math.sin(a * 2) * 0.03, 0, 0)
    ch.hipsY = -0.3 + Math.abs(Math.sin(a)) * 0.015
  },
  // seated at a bench: sewing, soldering, operating a radio
  sitwork(ch, t) {
    ANIMS.sit(ch, t)
    set('spine', 0.28)
    set('chest', 0.12)
    set('upperArmR', -0.62, 0, -0.08)
    set('upperArmL', -0.62, 0, 0.08)
    set('foreArmR', -1.0 + Math.sin(t * 12) * 0.06)
    set('foreArmL', -1.0 + Math.sin(t * 10 + 1) * 0.06)
    set('head', 0.42 + Math.sin(t * 0.6) * 0.04, Math.sin(t * 0.4) * 0.12, 0)
  },
  chop(ch, t) {
    legsStraight()
    set('thighL', -0.3)
    set('thighR', 0.22)
    const k = (t * 1.1) % 1
    const s = k < 0.55 ? k / 0.55 : 1 - (k - 0.55) / 0.45
    const e = s * s * (3 - 2 * s)
    set('upperArmR', -0.4 - e * 2.3, 0.1, -0.25)
    set('upperArmL', -0.5 - e * 2.1, -0.1, 0.25)
    set('foreArmR', -0.4 - e * 0.6)
    set('foreArmL', -0.4 - e * 0.6)
    set('spine', 0.25 - e * 0.3)
    set('chest', 0.15 - e * 0.25)
    set('head', 0.35)
    ch.hipsY = -0.04
  },
  chat(ch, t) {
    ANIMS.idle(ch, t)
    const g = Math.sin(t * 2.3 + ch.seed)
    set('upperArmR', -0.35 + Math.max(0, g) * 0.5, 0, -0.15)
    set('foreArmR', -0.6 - Math.max(0, g) * 0.6)
    set('head', 0.05, Math.sin(t * 0.7 + ch.seed) * 0.25, Math.sin(t * 1.3) * 0.05)
  },
  lie(ch, t) {
    armsDown(0.2)
    legsStraight()
    set('chest', Math.sin(t * 1.2) * 0.03)
    set('head', -0.1, 0.4, 0)
    ch.hipsY = 0
  },
  // Down and bleeding: sat on the ground propped on one hand, the other
  // reaching out weakly for help, breathing hard, the head heavy.
  downed(ch, t) {
    const br = Math.sin(t * 2.7)
    set('thighL', -1.42, 0, 0.14)
    set('thighR', -1.25, 0, -0.1)
    set('shinL', 0.18)
    set('shinR', 0.75)
    set('footL', -0.1)
    set('footR', -0.25)
    set('spine', -0.32 + br * 0.03)
    set('chest', -0.06 + br * 0.04)
    set('upperArmL', 0.75, 0, 0.32)
    set('foreArmL', -0.12)
    set('handL', -0.5)
    set('upperArmR', -0.3 + Math.sin(t * 1.3 + ch.seed) * 0.08, 0, 0.32)
    set('foreArmR', -1.3 + Math.sin(t * 1.3 + ch.seed) * 0.18)
    set('handR', 0.3)
    set('neck', 0.25)
    set('head', 0.3 + Math.sin(t * 0.6) * 0.08, Math.sin(t * 0.45 + ch.seed) * 0.35, 0.1)
    ch.hipsY = -0.8
  },
  // Dying: the knees go, then the body (see Character.fall) tips over and
  // lands limp, arms thrown out, head rolled to one side.
  dead(ch, t) {
    const k = clamp(ch.animT / 0.35, 0, 1)
    const lay = smoothstep(0.25, 0.9, ch.animT)
    const d = ch.fallDir
    set('thighL', lerp(-0.45, -0.25, lay), 0, 0.1)
    set('thighR', lerp(-0.2, 0.05, lay), 0, -0.12)
    set('shinL', lerp(0.9 * k, 0.45, lay))
    set('shinR', lerp(0.6 * k, 0.1, lay))
    set('spine', lerp(0.3 * k, d > 0 ? 0.08 : -0.1, lay))
    set('chest', lerp(0.15 * k, 0, lay))
    const fling = lerp(0.3, d > 0 ? 1.2 : 0.95, lay)
    set('upperArmL', d > 0 ? -0.6 * lay : 0.2 * lay, 0, fling)
    set('upperArmR', d > 0 ? -0.6 * lay : 0.2 * lay, 0, -fling * 0.8)
    set('foreArmL', -0.35)
    set('foreArmR', -0.6)
    set('neck', 0.2 * k)
    set('head', lerp(0.3 * k, 0.1, lay), lay * (ch.seed % 2 < 1 ? 0.7 : -0.7), 0)
    ch.hipsY = -0.25 * k * (1 - lay)
  },
  wave(ch, t) {
    ANIMS.idle(ch, t)
    set('upperArmR', -2.7, 0, -0.35)
    set('foreArmR', -0.5 + Math.sin(t * 8) * 0.45)
  },
  punch(ch, t) {
    legsStraight()
    set('thighL', -0.3)
    set('thighR', 0.2)
    const a = Math.max(0, Math.sin(t * 7))
    const b2 = Math.max(0, Math.sin(t * 7 + Math.PI))
    set('upperArmR', -1.3 - a * 0.25, 0.3, -0.2)
    set('foreArmR', -1.6 + a * 1.4)
    set('upperArmL', -1.3 - b2 * 0.25, -0.3, 0.2)
    set('foreArmL', -1.6 + b2 * 1.4)
    set('chest', 0.12, (a - b2) * 0.3, 0)
    set('spine', 0.1)
  },
  // ---------------- zombies
  // The dead each move their own way (ch.zt: arm heights, the head's lean,
  // how badly a leg drags), sway on their feet and twitch.
  zidle(ch, t) {
    const Z = ch.zt
    legsStraight()
    const sw = Math.sin(t * 0.9 + ch.seed)
    const tw = Math.pow(Math.max(0, Math.sin(t * 3.1 + ch.seed * 5)), 24)
    set('thighR', 0.05, 0, -0.04)
    set('shinR', 0.12 * Z.drag)
    set('hips', 0, sw * 0.06, sw * 0.06)
    set('spine', 0.25 + Z.hunch * 0.15)
    set('chest', 0.18, sw * 0.05, -sw * 0.06)
    set('shoulderL', 0, 0, Z.droop * 0.1)
    set('neck', 0.35)
    set('head', 0.1 + Math.sin(t * 2.7 + ch.seed) * 0.05 + tw * 0.25, Math.sin(t * 0.6) * 0.3, Z.tilt * 0.4 + Math.sin(t * 1.3) * 0.1)
    set('upperArmL', -0.7 + Z.armL * 0.5 + Math.sin(t * 1.2) * 0.1, 0, 0.2)
    set('upperArmR', -0.4 + Z.armR * 0.5 + Math.sin(t * 1.0 + 1) * 0.1 - tw * 0.3, 0, -0.15)
    set('foreArmL', -0.3)
    set('foreArmR', -0.5 + tw * 0.4)
    set('handL', 0.3, 0, 0)
    set('handR', 0.25, 0, 0)
    ch.hipsY = -0.03
  },
  // The shamble: a dragged leg, the body lurching over the good one, arms
  // reaching and bobbing with each step, the head lolling.
  zwalk(ch, t) {
    const Z = ch.zt
    const { s, c } = gait(ch.phase, 0, true, Z.drag)
    const lurch = Math.sin(ch.phase) * (0.06 + Z.drag * 0.05)
    set('hips', 0, s * 0.09, lurch)
    set('spine', 0.3 + Z.hunch * 0.15, 0, -lurch * 0.8)
    set('chest', 0.18, -s * 0.08, -lurch * 0.6)
    set('shoulderL', 0, 0, Z.droop * 0.1 + Math.abs(c) * 0.04)
    set('shoulderR', 0, 0, -Math.abs(c) * 0.04)
    set('neck', 0.3)
    set('head', 0.1 + Math.abs(s) * 0.06, s * 0.15, Z.tilt * 0.4 + lurch * 1.5)
    const bob = Math.abs(c) * 0.12
    set('upperArmL', -1.25 + Z.armL * 0.45 + s * 0.15 + bob, 0, 0.12)
    set('upperArmR', -1.15 + Z.armR * 0.45 - s * 0.15 + bob, 0, -0.12)
    set('foreArmL', -0.2 - bob * 0.5)
    set('foreArmR', -0.35 - bob * 0.5)
    set('handL', 0.35, 0, 0)
    set('handR', 0.3, 0, 0)
    ch.hipsY = -0.04 + Math.abs(c) * 0.03
  },
  // Running dead: head down, arms thrown about out of time with the legs.
  zrun(ch, t) {
    const Z = ch.zt
    const { s, c } = gait(ch.phase, 1, false)
    const fl = Math.sin(ch.phase * 1.3 + ch.seed)
    set('hips', 0, s * 0.15, s * 0.06)
    set('spine', 0.45 + Z.hunch * 0.1)
    set('chest', 0.25, -s * 0.22, fl * 0.06)
    set('neck', 0.1)
    set('head', -0.2 + Math.abs(c) * 0.08, fl * 0.1, Z.tilt * 0.3)
    set('upperArmL', -0.9 + s * 0.9 + fl * 0.3, 0, 0.3 + Math.max(0, fl) * 0.3)
    set('upperArmR', -0.9 - s * 0.9 - fl * 0.25, 0, -0.3 - Math.max(0, -fl) * 0.3)
    set('foreArmL', -0.3 - Math.max(0, fl) * 0.4)
    set('foreArmR', -0.3 - Math.max(0, -fl) * 0.4)
    ch.hipsY = -0.08 + Math.abs(c) * 0.07
  },
  // The lunge: both arms snatch forward and close, the head darts in to bite.
  zattack(ch, t, o) {
    const s = o.swing ?? 0
    const reach = smoothstep(0, 0.4, s) * (1 - smoothstep(0.75, 1, s))
    const bite = Math.exp(-(((s - 0.55) / 0.12) ** 2))
    legsStraight()
    set('thighL', -0.4 - reach * 0.25)
    set('shinL', 0.15 + reach * 0.2)
    set('thighR', 0.3 + reach * 0.1)
    set('spine', 0.3 + reach * 0.3)
    set('chest', 0.15 + reach * 0.2, Math.sin(s * 6) * 0.08, 0)
    set('neck', 0.1 + bite * 0.35)
    set('head', 0.1 - reach * 0.2 + bite * 0.15, 0, 0.1)
    set('upperArmL', -1.5 - reach * 0.6, 0, 0.35 - reach * 0.35)
    set('upperArmR', -1.5 - reach * 0.6, 0, -0.35 + reach * 0.35)
    set('foreArmL', -0.5 + reach * 0.35 - bite * 0.4)
    set('foreArmR', -0.5 + reach * 0.35 - bite * 0.4)
    set('handL', 0.5 * bite, 0, 0)
    set('handR', 0.5 * bite, 0, 0)
    ch.hipsY = -0.06 - reach * 0.05
  },
  zcrawl(ch, t) {
    const s = Math.sin(ch.phase)
    set('spine', 0.1)
    set('chest', 0.1)
    set('neck', -0.6)
    set('head', -0.5)
    set('upperArmL', -2.6 + s * 0.6, 0, 0.3)
    set('upperArmR', -2.6 - s * 0.6, 0, -0.3)
    set('foreArmL', -0.6 - Math.max(0, s) * 0.8)
    set('foreArmR', -0.6 - Math.max(0, -s) * 0.8)
    set('thighL', 0.1)
    set('thighR', 0.1)
    ch.hipsY = 0
  },
}

// ---------------------------------------------------------------- Character
// Each bone eases to its target as a damped spring: the legs stiff (feet
// must not slide), the trunk firm, arms and head softer, so a turn carries
// through the body and a kick (a shot, a hit) rings out and settles.
const STIFF = new Float32Array(NB)
const DAMP = new Float32Array(NB)
for (const [name, k, z] of [
  ['hips', 260, 1], ['spine', 200, 1], ['chest', 170, 0.9], ['neck', 130, 0.85], ['head', 110, 0.8],
  ['shoulderL', 150, 0.9], ['shoulderR', 150, 0.9], ['upperArmL', 120, 0.78], ['upperArmR', 120, 0.78],
  ['foreArmL', 105, 0.75], ['foreArmR', 105, 0.75], ['handL', 120, 0.8], ['handR', 120, 0.8],
  ['thighL', 420, 1], ['thighR', 420, 1], ['shinL', 420, 1], ['shinR', 420, 1], ['footL', 380, 1], ['footR', 380, 1],
]) {
  STIFF[BONE[name]] = k
  DAMP[BONE[name]] = 2 * z * Math.sqrt(k)
}
const BI = (n) => BONE[n] * 3
const wrapA = (a) => Math.atan2(Math.sin(a), Math.cos(a))

export class Character {
  constructor(spec) {
    this.spec = spec
    const { mesh, bones } = spec.cacheKey ? acquireShared(spec) : buildCharacterMesh(spec)
    this.mesh = mesh
    this.bones = bones
    this.root = new THREE.Group()
    this.root.add(mesh)
    const h = spec.height || 1
    this.mesh.scale.setScalar(h)
    this.anim = spec.zombie ? 'zidle' : 'idle'
    this.t = Math.random() * 100
    this.phase = 0
    this.seed = Math.random() * 10
    this.hipsY = 0
    this.curHipsY = 0
    this.hold = 'none'
    this.weapon = null
    this.recoil = 0
    this.flinchT = 0
    this.cur = new Float32Array(NB * 3)
    this.vel = new Float32Array(NB * 3)
    this.root.userData.character = this
    this.zombie = !!spec.zombie
    this.gaitAmp = 0
    this.idleW = 0
    this.animT = 0
    this.lastYaw = null
    this.yawRate = 0
    this.fall = null
    this.fallDir = 1
    this.primed = false
    // the dead's own way of moving
    const r = rngFrom(Math.floor(this.seed * 1000) + 7)
    this.zt = { armL: r() - 0.5, armR: r() - 0.5, tilt: r() - 0.5, drag: r() < 0.55 ? 0.3 + r() * 0.7 : 0, hunch: r(), droop: r() - 0.3 }
  }
  setWeapon(obj, hold) {
    if (this.weapon) this.bones[BONE.handR].remove(this.weapon)
    this.weapon = obj || null
    this.hold = obj ? hold : 'none'
    if (obj) {
      obj.rotation.set(Math.PI / 2, 0, 0)
      obj.position.set(0, -0.06, 0.012)
      this.bones[BONE.handR].add(obj)
    }
  }
  showWeapon(v) {
    if (this.weapon) this.weapon.visible = v
  }
  fire() {
    this.recoil = 1
    // a kick through the arms and shoulders that the springs ring out
    const v = this.vel
    const big = this.hold === 'rifle' ? 1.3 : 1
    v[BI('upperArmR')] += 5 * big
    v[BI('upperArmL')] += 4 * big
    v[BI('chest')] -= 1.6 * big
    v[BI('head')] -= 1.2 * big
  }
  // hit: thrown back (or to one side) and recovering
  flinch(side = 0) {
    this.flinchT = 1
    const v = this.vel
    v[BI('chest')] -= 7
    v[BI('spine')] -= 4
    v[BI('head')] -= 9
    v[BI('chest') + 2] += side * 6
    v[BI('upperArmL') + 2] += 4
    v[BI('upperArmR') + 2] -= 4
  }
  // speed in m/s drives the gait phase so feet don't slide
  update(dt, anim, o = {}) {
    if (!(dt >= 0)) dt = 0
    if (dt > 0.25) dt = 0.25
    this.t += dt
    if (anim && anim !== this.anim) {
      this.anim = anim
      this.animT = 0
    }
    this.animT += dt
    o.dt = dt
    const speed = o.speed ?? 0
    const L = this.anim === 'walk' || this.anim === 'run' || this.anim === 'carry'
    const stride = L ? lerp(1.45, 2.45, this.gaitAmp) : this.anim === 'run' || this.anim === 'zrun' ? 2.4 : this.anim === 'zcrawl' ? 0.9 : this.anim === 'zwalk' ? 1.25 : 1.45
    if (speed > 0.05) this.phase += (speed * dt * Math.PI * 2) / stride
    // how fast the body is turning (the root's heading, set by the owner)
    const yaw = this.root.rotation.y
    if (this.lastYaw != null && dt > 0) this.yawRate += (wrapA(yaw - this.lastYaw) / dt - this.yawRate) * Math.min(1, dt * 10)
    this.lastYaw = yaw
    P.set(ZERO)
    this.hipsY = 0
    const fn = ANIMS[this.anim] || ANIMS.idle
    fn(this, this.t, o)
    // weapon-holding overlays on locomotion; aiming on the move keeps the
    // upper body on target over walking legs
    if (!this.zombie && this.weapon?.visible !== false && ['idle', 'walk', 'run'].includes(this.anim)) {
      if (o.aim && this.hold !== 'melee' && this.anim !== 'run') this.aimOver()
      else if (this.hold === 'rifle') ANIMS.rifleReady(this)
      else if (this.hold === 'pistol') ANIMS.pistolReady(this)
      else if (this.hold === 'melee' && this.anim !== 'run') ANIMS.meleeReady(this)
    }
    // turning on the spot: the feet step round instead of sliding
    const yr = clamp(this.yawRate, -6, 6)
    if (speed < 0.1 && Math.abs(yr) > 0.5 && (this.anim === 'idle' || this.anim === 'zidle' || this.anim === 'aim')) {
      const k = clamp((Math.abs(yr) - 0.5) / 2, 0, 0.6)
      this.phase += Math.abs(yr) * dt * 2.2
      const legs = ['thighL', 'thighR', 'shinL', 'shinR', 'footL', 'footR']
      const keep = legs.map((b) => [get(b, 0), get(b, 1), get(b, 2)])
      gait(this.phase, 0, this.zombie)
      legs.forEach((b, n) => set(b, lerp(keep[n][0], get(b, 0) * 0.55, k), keep[n][1], keep[n][2]))
    }
    // leaning into a turn, the head leading it
    if (!this.fall && Math.abs(yr) > 0.05) {
      const mv = clamp(speed / 3, 0.25, 1)
      add('spine', 0, 0, -yr * 0.035 * mv)
      add('hips', 0, 0, yr * 0.015 * mv)
      add('head', 0, clamp(yr * 0.09, -0.45, 0.45), 0)
      add('neck', 0, clamp(yr * 0.04, -0.2, 0.2), 0)
    }
    // recoil and flinch: held offsets on top of the springs' kick
    if (this.recoil > 0) {
      add('upperArmR', this.recoil * 0.12)
      add('upperArmL', this.recoil * 0.1)
      this.recoil = Math.max(0, this.recoil - dt * 9)
    }
    if (this.flinchT > 0) {
      add('chest', -this.flinchT * 0.15)
      add('head', -this.flinchT * 0.2)
      this.flinchT = Math.max(0, this.flinchT - dt * 4)
    }
    // the springs (sub-stepped so a long frame stays stable)
    const cur = this.cur
    const vel = this.vel
    if (o.snap || !this.primed) {
      cur.set(P)
      vel.fill(0)
      this.curHipsY = this.hipsY
      this.primed = true
    } else {
      const n = Math.max(1, Math.ceil(dt / 0.012))
      const h = dt / n
      for (let q = 0; q < n; q++) {
        for (let i = 1; i < NB; i++) {
          const K = STIFF[i] || 150
          const D = DAMP[i] || 24
          for (let a = 0; a < 3; a++) {
            const j = i * 3 + a
            vel[j] += (K * (P[j] - cur[j]) - D * vel[j]) * h
            cur[j] += vel[j] * h
          }
        }
      }
      this.curHipsY += (this.hipsY - this.curHipsY) * (1 - Math.exp(-dt * 12))
    }
    for (let i = 1; i < NB; i++) {
      const j = i * 3
      this.bones[i].rotation.set(cur[j], cur[j + 1], cur[j + 2])
    }
    this.bones[BONE.hips].position.y = 0.98 + this.curHipsY
    this.updateFall(dt)
  }
  // The upper body of the aim pose over whatever the legs are doing.
  aimOver() {
    const keep = {}
    for (const b of ['hips', 'thighL', 'thighR', 'shinL', 'shinR', 'footL', 'footR']) keep[b] = [get(b, 0), get(b, 1), get(b, 2)]
    const hy = this.hipsY
    ANIMS.aim(this, this.t, {})
    for (const b in keep) set(b, ...keep[b])
    this.hipsY = hy
  }
  // Death throws the body down: it tips from the feet once the knees have
  // gone, lands with a small bounce and lies; a body that gets up (revived)
  // is raised again. Crawlers are already down.
  updateFall(dt) {
    const down = this.anim === 'dead'
    const m = this.mesh
    if (down && !this.fall) {
      if (Math.abs(m.rotation.x) > 0.3) return
      this.fall = { t: 0, rx: m.rotation.x, py: m.position.y, pz: m.position.z, up: false }
      this.fallDir = hash1(Math.floor(this.seed * 97), 3.3) < 0.5 ? 1 : -1
    }
    const F = this.fall
    if (!F) return
    if (down) F.t += dt
    else {
      F.up = true
      F.t = Math.min(F.t, 1.2) - dt * 1.5
    }
    // after the knees go (0.2 s): an accelerating topple, landing at 0.75 s,
    // a rebound that dies away
    const u = clamp((F.t - 0.2) / 0.55, 0, 1)
    let f = u * u
    if (F.t > 0.75 && !F.up) f = 1 - Math.abs(Math.sin((F.t - 0.75) * 9)) * Math.exp(-(F.t - 0.75) * 7) * 0.06
    const d = this.fallDir
    m.rotation.x = F.rx + d * f * Math.PI * 0.5
    m.position.y = F.py + f * 0.11
    m.position.z = F.pz - d * f * 0.55
    if (F.up && F.t <= 0) {
      m.rotation.x = F.rx
      m.position.y = F.py
      m.position.z = F.pz
      this.fall = null
    }
  }
  dispose() {
    if (this.spec.cacheKey) releaseShared(this.spec.cacheKey)
    else this.mesh.geometry.dispose()
  }
}
