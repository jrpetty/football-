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
  mesh.frustumCulled = false
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
// walking gait driven by phase; amp 0..1 (1 = run)
function gait(ph, amp, Z) {
  const s = Math.sin(ph)
  const c = Math.cos(ph)
  const th = lerp(0.48, 0.85, amp)
  const kn = lerp(0.8, 1.35, amp)
  const thL = Z ? th * 0.75 : th
  const thR = Z ? th * 0.45 : th
  set('thighL', -s * thL, 0, 0.01)
  set('thighR', s * thR, 0, -0.01)
  const kL = 0.06 + Math.pow(Math.max(0, c), 1.5) * kn
  const kR = 0.06 + Math.pow(Math.max(0, -c), 1.5) * kn * (Z ? 0.5 : 1)
  set('shinL', kL)
  set('shinR', kR)
  set('footL', -(get('thighL', 0) + kL) * 0.65)
  set('footR', -(get('thighR', 0) + kR) * 0.65)
  return { s, c }
}

export const ANIMS = {
  idle(ch, t) {
    armsDown(0.07 + Math.sin(t * 1.1 + ch.seed) * 0.01)
    legsStraight()
    set('chest', Math.sin(t * 1.7) * 0.02)
    set('spine', 0.02)
    set('head', Math.sin(t * 0.37 + ch.seed) * 0.08, Math.sin(t * 0.23 + ch.seed * 3) * 0.35, 0)
    set('hips', 0, 0, Math.sin(t * 0.5 + ch.seed) * 0.02)
    ch.hipsY = 0
  },
  walk(ch, t, o) {
    const { s, c } = gait(ch.phase, 0, false)
    set('hips', 0, s * 0.07, 0)
    set('spine', 0.05, 0, 0)
    set('chest', 0.02, -s * 0.1, 0)
    set('upperArmL', s * 0.42, 0, 0.08)
    set('upperArmR', -s * 0.42, 0, -0.08)
    set('foreArmL', -0.22 - Math.max(0, -s) * 0.4)
    set('foreArmR', -0.22 - Math.max(0, s) * 0.4)
    set('head', -0.02, s * 0.05, 0)
    ch.hipsY = -0.015 + Math.abs(c) * 0.03
  },
  run(ch, t) {
    const { s, c } = gait(ch.phase, 1, false)
    set('hips', 0, s * 0.12, 0)
    set('spine', 0.22)
    set('chest', 0.08, -s * 0.18, 0)
    set('upperArmL', s * 0.85, 0, 0.12)
    set('upperArmR', -s * 0.85, 0, -0.12)
    set('foreArmL', -1.3)
    set('foreArmR', -1.3)
    set('head', -0.12)
    ch.hipsY = -0.05 + Math.abs(c) * 0.07
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
    set('spine', 0.08, sp * 0.4, 0)
    set('chest', cx, sp * 0.6, 0)
    ch.hipsY = -0.05
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
  downed(ch, t) {
    armsDown(0.35)
    legsStraight()
    set('thighL', -0.2)
    set('shinL', 0.5)
    set('upperArmR', -1.4 + Math.sin(t * 1.5) * 0.25, 0, -0.2)
    set('foreArmR', -0.4)
    set('head', 0.3, Math.sin(t * 0.8) * 0.3, 0)
  },
  dead(ch) {
    armsDown(0.6)
    legsStraight()
    set('thighL', -0.15)
    set('head', 0, 0.6, 0)
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
  zidle(ch, t) {
    legsStraight()
    const sw = Math.sin(t * 0.9 + ch.seed)
    set('hips', 0, 0, sw * 0.05)
    set('spine', 0.25)
    set('chest', 0.18, 0, -sw * 0.06)
    set('neck', 0.35)
    set('head', 0.1 + Math.sin(t * 2.7 + ch.seed) * 0.05, Math.sin(t * 0.6) * 0.3, 0.3 + Math.sin(t * 1.3) * 0.1)
    set('upperArmL', -0.7 + Math.sin(t * 1.2) * 0.1, 0, 0.2)
    set('upperArmR', -0.4 + Math.sin(t * 1.0 + 1) * 0.1, 0, -0.15)
    set('foreArmL', -0.3)
    set('foreArmR', -0.5)
    ch.hipsY = -0.03
  },
  zwalk(ch, t) {
    const { s, c } = gait(ch.phase, 0, true)
    set('hips', 0, s * 0.08, s * 0.06)
    set('spine', 0.3)
    set('chest', 0.18, -s * 0.08, -s * 0.05)
    set('neck', 0.3)
    set('head', 0.1, s * 0.15, 0.35)
    set('upperArmL', -1.25 + s * 0.15, 0, 0.12)
    set('upperArmR', -1.15 - s * 0.15, 0, -0.12)
    set('foreArmL', -0.2)
    set('foreArmR', -0.35)
    ch.hipsY = -0.04 + Math.abs(c) * 0.03
  },
  zrun(ch, t) {
    const { s, c } = gait(ch.phase, 1, false)
    set('hips', 0, s * 0.15, s * 0.05)
    set('spine', 0.45)
    set('chest', 0.25, -s * 0.2, 0)
    set('neck', 0.1)
    set('head', -0.2, 0, 0.2)
    set('upperArmL', -0.9 + s * 0.9, 0, 0.3)
    set('upperArmR', -0.9 - s * 0.9, 0, -0.3)
    set('foreArmL', -0.3)
    set('foreArmR', -0.3)
    ch.hipsY = -0.08 + Math.abs(c) * 0.06
  },
  zattack(ch, t, o) {
    const s = o.swing ?? 0
    const k = Math.sin(Math.min(1, s) * Math.PI)
    legsStraight()
    set('thighL', -0.4)
    set('thighR', 0.3)
    set('spine', 0.3 + k * 0.25)
    set('chest', 0.15 + k * 0.2)
    set('head', 0.1 - k * 0.2, 0, 0.1)
    set('upperArmL', -1.5 - k * 0.6, 0, 0.25 - k * 0.2)
    set('upperArmR', -1.5 - k * 0.6, 0, -0.25 + k * 0.2)
    set('foreArmL', -0.4 + k * 0.3)
    set('foreArmR', -0.4 + k * 0.3)
    ch.hipsY = -0.06
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
    this.root.userData.character = this
    this.zombie = !!spec.zombie
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
  }
  flinch() {
    this.flinchT = 1
  }
  // speed in m/s drives the gait phase so feet don't slide
  update(dt, anim, o = {}) {
    if (!(dt >= 0)) dt = 0
    if (dt > 0.25) dt = 0.25
    this.t += dt
    if (anim) this.anim = anim
    const speed = o.speed ?? 0
    if (speed > 0.05) this.phase += (speed * dt * Math.PI * 2) / (this.anim === 'run' || this.anim === 'zrun' ? 2.4 : this.anim === 'zcrawl' ? 0.9 : 1.45)
    P.set(ZERO)
    this.hipsY = 0
    const fn = ANIMS[this.anim] || ANIMS.idle
    fn(this, this.t, o)
    // weapon-holding overlays on locomotion
    if (!this.zombie && this.weapon?.visible !== false && ['idle', 'walk', 'run'].includes(this.anim)) {
      if (this.hold === 'rifle') ANIMS.rifleReady(this)
      else if (this.hold === 'pistol') ANIMS.pistolReady(this)
      else if (this.hold === 'melee' && this.anim !== 'run') ANIMS.meleeReady(this)
    }
    // recoil and flinch are additive kicks
    if (this.recoil > 0) {
      add('upperArmR', this.recoil * 0.22)
      add('upperArmL', this.recoil * 0.18)
      add('chest', -this.recoil * 0.06)
      this.recoil = Math.max(0, this.recoil - dt * 9)
    }
    if (this.flinchT > 0) {
      add('chest', -this.flinchT * 0.3)
      add('head', -this.flinchT * 0.4)
      this.flinchT = Math.max(0, this.flinchT - dt * 4)
    }
    const k = 1 - Math.exp(-dt * (o.snap ? 40 : 14))
    const cur = this.cur
    for (let i = 0; i < NB; i++) {
      const j = i * 3
      cur[j] += (P[j] - cur[j]) * k
      cur[j + 1] += (P[j + 1] - cur[j + 1]) * k
      cur[j + 2] += (P[j + 2] - cur[j + 2]) * k
      if (i === 0) continue
      this.bones[i].rotation.set(cur[j], cur[j + 1], cur[j + 2])
    }
    this.curHipsY += (this.hipsY - this.curHipsY) * k
    this.bones[BONE.hips].position.y = 0.98 + this.curHipsY
  }
  dispose() {
    if (this.spec.cacheKey) releaseShared(this.spec.cacheKey)
    else this.mesh.geometry.dispose()
  }
}
