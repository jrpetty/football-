// Procedural people and zombies. Each character is ONE skinned mesh built
// from 100+ parts (rigid-skinned to a 20-bone skeleton), so detail costs no
// extra draw calls. Outfits follow the survivor's pre-outbreak job.
import * as THREE from 'three'
import { mergeGeometries, mergeVertices } from 'three/addons/utils/BufferGeometryUtils.js'
import { Builder } from './kit.js'
import { mat } from '../render/materials.js'
import { lerp, clamp } from '../core/util.js'

// ---------------------------------------------------------------- skeleton
// name, parent, offset (bind pose: standing, arms hanging, facing +Z)
const BONES = [
  ['root', -1, [0, 0, 0]],
  ['hips', 0, [0, 0.98, 0]],
  ['spine', 1, [0, 0.1, 0]],
  ['chest', 2, [0, 0.2, 0]],
  ['neck', 3, [0, 0.2, 0]],
  ['head', 4, [0, 0.08, 0]],
  ['shoulderL', 3, [0.07, 0.15, 0]],
  ['upperArmL', 6, [0.12, 0, 0]],
  ['foreArmL', 7, [0, -0.28, 0]],
  ['handL', 8, [0, -0.255, 0]],
  ['shoulderR', 3, [-0.07, 0.15, 0]],
  ['upperArmR', 10, [-0.12, 0, 0]],
  ['foreArmR', 11, [0, -0.28, 0]],
  ['handR', 12, [0, -0.255, 0]],
  ['thighL', 1, [0.095, -0.05, 0]],
  ['shinL', 14, [0, -0.44, 0]],
  ['footL', 15, [0, -0.43, 0]],
  ['thighR', 1, [-0.095, -0.05, 0]],
  ['shinR', 17, [0, -0.44, 0]],
  ['footR', 18, [0, -0.43, 0]],
]
export const BONE = Object.fromEntries(BONES.map((b, i) => [b[0], i]))
const NB = BONES.length

function bindMatrices() {
  const objs = BONES.map(([, , off]) => {
    const o = new THREE.Object3D()
    o.position.set(...off)
    return o
  })
  BONES.forEach(([, p], i) => p >= 0 && objs[p].add(objs[i]))
  objs[0].updateMatrixWorld(true)
  return objs.map((o) => o.matrixWorld.clone())
}
const BIND = bindMatrices()

// Builder that records which bone each part belongs to.
class SkinBuilder extends Builder {
  constructor() {
    super()
    this.boneIdx = 0
  }
  bone(name) {
    this.boneIdx = BONE[name]
    this.stack = [BIND[this.boneIdx].clone()]
    return this
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
  // Merge everything into one SkinnedMesh with one group per material.
  buildSkinned() {
    const perMat = []
    const mats = []
    for (const { material, geos } of this.batches.values()) {
      let m = mergeGeometries(geos, false)
      for (const g of geos) g.dispose()
      if (!m) continue
      // share vertices between faces: cuts the vertex count several times over
      const idx = mergeVertices(m, 1e-4)
      m.dispose()
      m = idx
      perMat.push(m)
      mats.push(material)
    }
    const geometry = mergeGeometries(perMat, true)
    for (const g of perMat) g.dispose()
    geometry.computeBoundingSphere()
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
    this.batches.clear()
    return { mesh, bones }
  }
}
// The skin builder never darkens parts near the ground.
const AO0 = { ao: 0 }

// ---------------------------------------------------------------- palettes
export const SKIN_TONES = ['#f2cfb0', '#e6b694', '#d39d76', '#b47b55', '#8c5a3a', '#663f28', '#4e2f1e']
export const HAIR_COLORS = ['#1b1612', '#2e221a', '#4a3424', '#6b4a2b', '#9b7444', '#c8a26a', '#8a8a86', '#c9c6c0', '#9a3a26']
const ZSKIN = ['#8f9a80', '#7d8a74', '#9a9e8a', '#76826e', '#a0a08e', '#8a9488']
const pickR = (r, a) => a[Math.floor(r() * a.length)]

// ---------------------------------------------------------------- outfits
// Each occupation maps to a base outfit; zombies get civilian or themed ones.
// top: { kind: tee|shirt|polo|jacket|coat|hoodie|tank|scrubs|uniform|coveralls|chef|cardigan|turnout|track|flannel|gown, color, sleeves: short|long|none, open, collar }
// legs: { kind: jeans|cargo|slacks|scrubs|overalls|coveralls|track|shorts|bare, color }
export const OUTFITS = {
  doctor: { top: { kind: 'coat', color: '#eef0ee', inner: '#7aa3c8', sleeves: 'long', collar: true }, legs: { kind: 'slacks', color: '#3a3f48' }, shoes: { kind: 'shoes', color: '#2a2420' }, extras: ['stethoscope', 'badge'] },
  nurse: { top: { kind: 'scrubs', color: '#4f9a9a', sleeves: 'short', vneck: true }, legs: { kind: 'scrubs', color: '#4f9a9a' }, shoes: { kind: 'sneakers', color: '#e8e8e4' }, extras: ['nametag'] },
  paramedic: { top: { kind: 'uniform', color: '#2f4a3a', sleeves: 'long', collar: true }, legs: { kind: 'cargo', color: '#26302a' }, shoes: { kind: 'boots', color: '#1e1c1a' }, extras: ['stripesArms', 'stripesLegs', 'radio'] },
  police: { top: { kind: 'uniform', color: '#2c3a56', sleeves: 'short', collar: true }, legs: { kind: 'slacks', color: '#222a3c' }, shoes: { kind: 'boots', color: '#141414' }, hat: { kind: 'policecap', color: '#1f2a42' }, extras: ['dutybelt', 'badge', 'radio'] },
  soldier: { top: { kind: 'uniform', color: '#5a6040', sleeves: 'long', collar: true, camo: true }, legs: { kind: 'cargo', color: '#565c3e', camo: true }, shoes: { kind: 'boots', color: '#3a3024' }, hat: { kind: 'patrolcap', color: '#5a6040' }, extras: ['belt', 'pouches'] },
  firefighter: { top: { kind: 'turnout', color: '#a8875a', sleeves: 'long' }, legs: { kind: 'cargo', color: '#9a7c52' }, shoes: { kind: 'boots', color: '#181614' }, extras: ['stripesArms', 'stripesLegs', 'stripesChest', 'suspenders'] },
  farmer: { top: { kind: 'flannel', color: '#a83a2c', check: '#4a1a14', sleeves: 'long', rolled: true }, legs: { kind: 'overalls', color: '#3e5a7a' }, shoes: { kind: 'boots', color: '#5a4030' }, hat: { kind: 'straw', color: '#d4b878' } },
  chef: { top: { kind: 'chef', color: '#f0eee8', sleeves: 'long', rolled: true }, legs: { kind: 'slacks', color: '#3a3a3a', check: true }, shoes: { kind: 'shoes', color: '#202020' }, hat: { kind: 'toque', color: '#f4f2ec' }, extras: ['apron', 'neckerchief'] },
  carpenter: { top: { kind: 'flannel', color: '#6a7a4a', check: '#2a3020', sleeves: 'long', rolled: true }, legs: { kind: 'jeans', color: '#3d5270' }, shoes: { kind: 'boots', color: '#7a5a3a' }, extras: ['toolbelt', 'pencil'] },
  mechanic: { top: { kind: 'coveralls', color: '#4a5a6a', sleeves: 'long', rolled: true }, legs: { kind: 'coveralls', color: '#4a5a6a' }, shoes: { kind: 'boots', color: '#2a2420' }, hat: { kind: 'cap', color: '#a83a2c' }, extras: ['rag', 'grease', 'nametag'] },
  electrician: { top: { kind: 'shirt', color: '#4a5a7a', sleeves: 'long', collar: true }, legs: { kind: 'jeans', color: '#30425a' }, shoes: { kind: 'boots', color: '#3a2a20' }, hat: { kind: 'hardhat', color: '#e8e4dc' }, extras: ['hivis', 'toolbelt'] },
  engineer: { top: { kind: 'shirt', color: '#9ab8d8', sleeves: 'long', collar: true, rolled: true }, legs: { kind: 'slacks', color: '#8a7a5a' }, shoes: { kind: 'shoes', color: '#4a3020' }, hat: { kind: 'hardhat', color: '#f0f0ea' }, extras: ['glasses', 'pens'] },
  tailor: { top: { kind: 'shirt', color: '#e8e2d4', sleeves: 'long', collar: true, rolled: true }, legs: { kind: 'slacks', color: '#4a4038' }, shoes: { kind: 'shoes', color: '#2a1e18' }, extras: ['waistcoat', 'tape', 'glasses'] },
  gunsmith: { top: { kind: 'tee', color: '#5a5048', sleeves: 'short' }, legs: { kind: 'cargo', color: '#3a3a30' }, shoes: { kind: 'boots', color: '#3a2a1e' }, extras: ['leatherApron', 'goggles', 'gloves'] },
  hunter: { top: { kind: 'flannel', color: '#5a4a32', check: '#2a2016', sleeves: 'long' }, legs: { kind: 'cargo', color: '#5a5a3a', camo: true }, shoes: { kind: 'boots', color: '#4a3a28' }, hat: { kind: 'cap', color: '#e06a1a' }, extras: ['huntvest'] },
  athlete: { top: { kind: 'track', color: '#2a4a8a', stripe: '#f0f0f0', sleeves: 'long', zip: true }, legs: { kind: 'track', color: '#2a4a8a', stripe: '#f0f0f0' }, shoes: { kind: 'sneakers', color: '#e84a3a' }, extras: ['headband'] },
  student: { top: { kind: 'hoodie', color: '#7a3a5a', sleeves: 'long' }, legs: { kind: 'jeans', color: '#4a6080' }, shoes: { kind: 'sneakers', color: '#f0f0f0' }, extras: ['backpackSmall'] },
  teacher: { top: { kind: 'cardigan', color: '#8a6a4a', inner: '#e8e0d0', sleeves: 'long', open: true }, legs: { kind: 'slacks', color: '#4a4a52' }, shoes: { kind: 'shoes', color: '#3a2a20' }, extras: ['glasses', 'tie'] },
  plumber: { top: { kind: 'tee', color: '#8a8a88', sleeves: 'short' }, legs: { kind: 'overalls', color: '#2a4a8a' }, shoes: { kind: 'boots', color: '#2a2420' }, hat: { kind: 'cap', color: '#2a4a8a' }, extras: ['wrench'] },
  builder: { top: { kind: 'tee', color: '#c8b89a', sleeves: 'short' }, legs: { kind: 'jeans', color: '#3d5270' }, shoes: { kind: 'boots', color: '#8a6a3a' }, hat: { kind: 'hardhat', color: '#f0c020' }, extras: ['hivis', 'toolbelt'] },
  clerk: { top: { kind: 'polo', color: '#2a7a4a', sleeves: 'short', collar: true }, legs: { kind: 'slacks', color: '#a8956a' }, shoes: { kind: 'sneakers', color: '#3a3a3a' }, extras: ['nametag'] },
  excon: { top: { kind: 'tank', color: '#e8e4dc', sleeves: 'none' }, legs: { kind: 'jeans', color: '#2a3448' }, shoes: { kind: 'boots', color: '#1a1a1a' }, extras: ['tattoos', 'bandana'] },
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
// spec: { skin, hair:{style,color}, face:{beard,brows}, build (0.85..1.3), height, female, outfit, armor, gear:{backpack}, zombie:{kind, decay, seed} }
export function buildCharacterMesh(spec) {
  const b = new SkinBuilder()
  const B = spec.build || 1
  const fem = !!spec.female
  const Z = spec.zombie
  const r = rngFrom(spec.seed || 1)
  const skin = spec.skin
  const O = spec.outfit || OUTFITS.drifter
  const top = O.top || { kind: 'tee', color: '#777', sleeves: 'short' }
  const legs = O.legs || { kind: 'jeans', color: '#334' }
  const S = { mat: 'skin', color: skin, ...AO0 }
  const shW = fem ? 0.92 : 1
  const hipW = fem ? 1.08 : 1
  const torn = Z ? (p) => r() < p : () => false

  // ---- head & face
  b.bone('head')
  b.sphere(0.104, { ...S, y: 0.1, sx: 0.92, sy: 1.08, sz: 1.0, ws: 18, hs: 14 })
  b.box(0.13, 0.07, 0.12, { ...S, y: 0.035, z: 0.012, r: 0.03 }) // jaw
  b.box(0.024, 0.042, 0.028, { ...S, x: 0, y: 0.09, z: 0.103, r: 0.009, rx: -0.15 }) // nose
  for (const sx of [1, -1]) {
    b.sphere(0.026, { ...S, x: sx * 0.099, y: 0.098, sx: 0.4, sy: 1, sz: 0.7 }) // ears
    if (Z) {
      b.sphere(0.02, { mat: 'plain', color: '#2a1612', x: sx * 0.036, y: 0.112, z: 0.088, sz: 0.5, ...AO0 })
    } else {
      b.sphere(0.016, { mat: 'gloss', color: '#f4f0ea', x: sx * 0.036, y: 0.112, z: 0.0935, sz: 0.6, ...AO0 })
      b.sphere(0.0088, { mat: 'gloss', color: pickR(r, ['#3a2a1e', '#2a4a6a', '#3a5a3a', '#5a4030', '#202020']), x: sx * 0.036, y: 0.112, z: 0.1015, sz: 0.45, ...AO0 })
    }
    b.box(0.038, 0.008, 0.012, { mat: 'plain', color: spec.hair?.color || '#2a2018', x: sx * 0.037, y: 0.136, z: 0.094, rz: sx * -0.12, ...AO0 })
  }
  // mouth
  if (Z) {
    b.box(0.05, 0.026, 0.012, { mat: 'plain', color: '#2a0e0c', y: 0.05, z: 0.1, ...AO0 })
    b.box(0.044, 0.006, 0.006, { mat: 'plain', color: '#d8d0b0', y: 0.061, z: 0.104, ...AO0 })
  } else b.box(0.042, 0.007, 0.01, { mat: 'plain', color: shade(skin, -0.25), y: 0.052, z: 0.101, ...AO0 })
  // facial hair
  const fh = spec.face?.beard
  const hc = spec.hair?.color || '#2a2018'
  if (fh === 'beard') b.box(0.125, 0.07, 0.07, { mat: 'cloth', color: hc, y: 0.03, z: 0.05, r: 0.03, ...AO0 })
  if (fh === 'goatee') b.box(0.04, 0.045, 0.04, { mat: 'cloth', color: hc, y: 0.025, z: 0.098, r: 0.015, ...AO0 })
  if (fh === 'beard' || fh === 'mustache') b.box(0.06, 0.012, 0.014, { mat: 'cloth', color: hc, y: 0.066, z: 0.104, r: 0.005, ...AO0 })
  hair(b, spec.hair, O.hat, r)
  if (O.hat) hat(b, O.hat)
  // neck
  b.bone('neck')
  b.cyl(0.047, 0.052, 0.14, { ...S, y: 0.03 })

  // ---- torso (skin under clothes)
  const tc = top.color
  const chestMat = top.camo ? 'cloth' : 'cloth'
  b.bone('chest')
  b.box(0.34 * B * shW, 0.27, 0.21 * B, { ...S, y: 0.05, r: 0.08 })
  b.bone('spine')
  b.box(0.29 * B, 0.21, 0.19 * B, { ...S, y: 0.08, r: 0.07 })
  b.bone('hips')
  b.box(0.31 * B * hipW, 0.17, 0.2 * B, { ...S, y: -0.02, r: 0.06 })
  if (fem) {
    b.bone('chest')
    for (const sx of [1, -1]) b.sphere(0.06, { ...S, x: sx * 0.07, y: 0.04, z: 0.1 * B, sz: 0.8 })
  }
  if (Z?.kind === 'brute') {
    b.bone('spine')
    b.sphere(0.2, { ...S, y: 0.06, z: 0.06, sx: 1.05, sy: 0.85, sz: 0.9 })
  }

  // ---- shirt / jacket
  const sleeves = Z && top.sleeves === 'long' && torn(0.4) ? 'short' : top.sleeves
  if (top.kind !== 'bare') {
    const T = top.kind
    const shellC = tc
    const tm = 'cloth'
    b.bone('chest')
    b.box(0.355 * B * shW, 0.29, 0.225 * B, { mat: tm, color: shellC, y: 0.05, r: 0.085, ...AO0 })
    if (fem) for (const sx of [1, -1]) b.sphere(0.066, { mat: tm, color: shellC, x: sx * 0.07, y: 0.04, z: 0.104 * B, sz: 0.8, ...AO0 })
    b.bone('spine')
    const shortTop = Z && torn(0.35)
    if (!shortTop) b.box(0.305 * B, 0.23, 0.205 * B, { mat: tm, color: shellC, y: 0.07, r: 0.075, ...AO0 })
    else {
      b.box(0.305 * B, 0.1, 0.205 * B, { mat: tm, color: shellC, y: 0.12, r: 0.05, ...AO0 })
      // exposed ribs and gore
      for (let i = 0; i < 3; i++) b.box(0.16, 0.012, 0.01, { mat: 'plain', color: '#d8ccb0', y: 0.0 + i * 0.03, z: 0.1 * B, ...AO0 })
      b.box(0.12, 0.08, 0.01, { mat: 'plain', color: '#5a1612', y: 0.0, z: 0.096 * B, ...AO0 })
    }
    if (T === 'coat' || T === 'gown' || T === 'chef' || T === 'turnout') {
      // long hem that hangs over the hips/thighs
      b.bone('hips')
      const len = T === 'coat' ? 0.42 : T === 'gown' ? 0.5 : 0.22
      const cc = T === 'chef' ? shellC : shellC
      b.box(0.33 * B * hipW, len, 0.215 * B, { mat: tm, color: cc, y: -len / 2 + 0.06, r: 0.05, ...AO0 })
      if (top.open) {
        b.box(0.02, len + 0.25, 0.01, { mat: tm, color: shade(cc, -0.3), y: -len / 2 + 0.18, z: 0.11 * B, ...AO0 })
      }
    }
    if (top.open) {
      // open front shows the inner shirt
      b.bone('chest')
      b.box(0.1, 0.27, 0.01, { mat: 'cloth', color: top.inner || '#ddd', y: 0.04, z: 0.112 * B, ...AO0 })
      b.bone('spine')
      b.box(0.1, 0.22, 0.01, { mat: 'cloth', color: top.inner || '#ddd', y: 0.07, z: 0.103 * B, ...AO0 })
    }
    if (T === 'hoodie') {
      b.bone('chest')
      b.box(0.24, 0.1, 0.1, { mat: tm, color: shade(shellC, -0.08), y: 0.2, z: -0.1, r: 0.04, ...AO0 }) // hood
      b.bone('spine')
      b.box(0.18, 0.08, 0.02, { mat: tm, color: shade(shellC, -0.1), y: 0.02, z: 0.104 * B, r: 0.01, ...AO0 }) // pouch
      b.bone('chest')
      for (const sx of [1, -1]) b.cyl(0.004, 0.004, 0.16, { mat: 'plain', color: '#e8e4dc', x: sx * 0.03, y: 0.05, z: 0.118 * B, ...AO0 })
    }
    if (top.collar) {
      b.bone('chest')
      for (const sx of [1, -1]) b.box(0.07, 0.035, 0.07, { mat: tm, color: T === 'coat' && top.inner ? shellC : shade(shellC, 0.05), x: sx * 0.05, y: 0.19, z: 0.05, rz: sx * 0.5, rx: -0.4, r: 0.01, ...AO0 })
      if (T === 'shirt' || T === 'uniform') for (let i = 0; i < 4; i++) b.sphere(0.006, { mat: 'gloss', color: '#e8e0d0', y: 0.15 - i * 0.065, z: 0.115 * B, ...AO0 })
    } else if (top.vneck) {
      b.bone('chest')
      b.box(0.06, 0.07, 0.01, { ...S, y: 0.15, z: 0.112 * B, rz: Math.PI / 4, ...AO0 })
    } else if (T !== 'coat' && T !== 'tank') {
      b.bone('chest')
      b.torus(0.052, 0.012, { mat: tm, color: shade(shellC, -0.06), y: 0.175, rx: Math.PI / 2, ...AO0 })
    }
    if (top.zip) {
      b.bone('chest')
      b.box(0.006, 0.27, 0.006, { mat: 'steel', color: '#c8c8c8', y: 0.05, z: 0.115 * B, ...AO0 })
      b.bone('spine')
      b.box(0.006, 0.22, 0.006, { mat: 'steel', color: '#c8c8c8', y: 0.07, z: 0.105 * B, ...AO0 })
    }
    if (top.check) {
      // flannel check: dark bands across chest and sleeves
      b.bone('chest')
      for (let i = 0; i < 3; i++) b.box(0.358 * B * shW, 0.018, 0.228 * B, { mat: tm, color: top.check, y: -0.04 + i * 0.09, r: 0.006, ...AO0 })
      b.bone('spine')
      for (let i = 0; i < 2; i++) b.box(0.308 * B, 0.018, 0.208 * B, { mat: tm, color: top.check, y: 0.02 + i * 0.09, r: 0.006, ...AO0 })
    }
    if (top.camo) camo(b, ['chest', 'spine'], top.color, r)
    if (T === 'track' && top.stripe) {
      for (const side of ['L', 'R']) {
        b.bone('upperArm' + side)
        b.box(0.012, 0.25, 0.012, { mat: 'cloth', color: top.stripe, x: (side === 'L' ? 1 : -1) * 0.05, y: -0.13, ...AO0 })
        b.bone('foreArm' + side)
        b.box(0.012, 0.22, 0.012, { mat: 'cloth', color: top.stripe, x: (side === 'L' ? 1 : -1) * 0.045, y: -0.12, ...AO0 })
      }
    }
  }

  // ---- arms
  for (const side of ['L', 'R']) {
    const sx = side === 'L' ? 1 : -1
    const armSkin = { ...S }
    b.bone('upperArm' + side)
    b.sphere(0.058 * B, { ...armSkin, y: -0.01, sx: 1, sy: 0.9 })
    b.capsule(0.046 * B, 0.18, { ...armSkin, y: -0.14 })
    b.bone('foreArm' + side)
    b.sphere(0.043 * B, { ...armSkin })
    b.capsule(0.039 * B, 0.16, { ...armSkin, y: -0.125, sx: 1, sz: 0.92 })
    b.bone('hand' + side)
    const gloveC = (O.extras || []).includes('gloves') ? '#3a2e24' : null
    const hm = gloveC ? { mat: 'cloth', color: gloveC, ...AO0 } : armSkin
    b.box(0.03, 0.08, 0.07, { ...hm, y: -0.035, r: 0.014 })
    b.box(0.028, 0.055, 0.066, { ...hm, y: -0.085, z: 0.006, r: 0.012, rx: 0.25 })
    b.capsule(0.012, 0.035, { ...hm, x: -sx * 0.004, y: -0.035, z: 0.04, rx: 0.6 })
    // sleeves
    if (top.kind !== 'bare' && sleeves !== 'none') {
      const sc = top.kind === 'coat' || top.kind === 'jacket' || top.kind === 'cardigan' ? tc : tc
      b.bone('upperArm' + side)
      b.sphere(0.064 * B, { mat: 'cloth', color: sc, y: -0.01, sy: 0.92, ...AO0 })
      if (sleeves === 'short') b.cyl(0.056 * B, 0.054 * B, 0.13, { mat: 'cloth', color: sc, y: -0.075, ...AO0 })
      else {
        b.capsule(0.053 * B, 0.18, { mat: 'cloth', color: sc, y: -0.14, ...AO0 })
        b.bone('foreArm' + side)
        b.sphere(0.05 * B, { mat: 'cloth', color: sc, ...AO0 })
        if (top.rolled) b.cyl(0.05 * B, 0.048 * B, 0.06, { mat: 'cloth', color: shade(sc, -0.05), y: -0.04, ...AO0 })
        else {
          b.capsule(0.046 * B, 0.15, { mat: 'cloth', color: sc, y: -0.11, ...AO0 })
          b.cyl(0.048 * B, 0.048 * B, 0.03, { mat: 'cloth', color: shade(sc, -0.12), y: -0.215, ...AO0 })
        }
      }
      if (top.check && sleeves === 'long') {
        b.bone('upperArm' + side)
        b.cyl(0.055 * B, 0.055 * B, 0.018, { mat: 'cloth', color: top.check, y: -0.1, ...AO0 })
      }
    }
    if ((O.extras || []).includes('tattoos')) {
      b.bone('upperArm' + side)
      b.box(0.03, 0.08, 0.06, { mat: 'plain', color: '#2a3a4a', x: sx * 0.035, y: -0.12, ...AO0 })
      b.bone('foreArm' + side)
      b.box(0.02, 0.07, 0.05, { mat: 'plain', color: '#2a3a4a', x: sx * 0.028, y: -0.1, ...AO0 })
    }
  }

  // ---- legs
  const L = legs.kind
  const lc = legs.color || '#334'
  for (const side of ['L', 'R']) {
    const sx = side === 'L' ? 1 : -1
    const bareLeg = L === 'bare' || L === 'shorts'
    const crawlerCut = Z?.kind === 'crawler'
    b.bone('thigh' + side)
    b.sphere(0.08 * B, { mat: bareLeg ? 'skin' : 'cloth', color: bareLeg ? skin : lc, y: -0.02, ...AO0 })
    b.capsule(0.074 * B, 0.28, { mat: L === 'bare' ? 'skin' : 'cloth', color: L === 'bare' ? skin : lc, y: -0.22, ...AO0 })
    if (crawlerCut) {
      b.box(0.12, 0.05, 0.12, { mat: 'plain', color: '#4a1210', y: -0.42, r: 0.03, ...AO0 })
      continue
    }
    b.bone('shin' + side)
    b.sphere(0.058 * B, { mat: bareLeg ? 'skin' : 'cloth', color: bareLeg ? skin : lc, ...AO0 })
    const shinC = bareLeg ? skin : lc
    b.capsule(0.054 * B, 0.29, { mat: bareLeg ? 'skin' : 'cloth', color: shinC, y: -0.2, ...AO0 })
    b.sphere(0.05 * B, { mat: bareLeg ? 'skin' : 'cloth', color: shinC, y: -0.11, z: -0.02, sz: 0.9, ...AO0 })
    if (L === 'shorts') {
      b.bone('thigh' + side)
      b.cyl(0.082 * B, 0.08 * B, 0.26, { mat: 'cloth', color: lc, y: -0.12, ...AO0 })
    }
    if (legs.camo) camo(b, ['thigh' + side, 'shin' + side], lc, r)
    if (L === 'cargo') {
      b.bone('thigh' + side)
      b.box(0.03, 0.1, 0.09, { mat: 'cloth', color: shade(lc, -0.08), x: sx * 0.07, y: -0.25, r: 0.01, ...AO0 })
    }
    if (L === 'jeans') {
      b.bone('thigh' + side)
      b.box(0.06, 0.006, 0.006, { mat: 'cloth', color: '#c8a050', x: sx * 0.04, y: -0.02, z: 0.07, ...AO0 })
    }
    if (legs.stripe) {
      b.bone('thigh' + side)
      b.box(0.012, 0.32, 0.012, { mat: 'cloth', color: legs.stripe, x: sx * 0.075, y: -0.2, ...AO0 })
      b.bone('shin' + side)
      b.box(0.012, 0.3, 0.012, { mat: 'cloth', color: legs.stripe, x: sx * 0.055, y: -0.2, ...AO0 })
    }
    // footwear
    shoe(b, side, O.shoes || { kind: 'boots', color: '#2a2420' }, L === 'bare')
  }
  // pelvis / waist
  b.bone('hips')
  if (L !== 'bare') {
    b.box(0.325 * B * hipW, 0.18, 0.212 * B, { mat: 'cloth', color: lc, y: -0.02, r: 0.065, ...AO0 })
    if (L === 'overalls' || L === 'coveralls') {
      b.bone('spine')
      b.box(0.31 * B, 0.24, 0.21 * B, { mat: 'cloth', color: lc, y: 0.07, r: 0.07, ...AO0 })
      if (L === 'overalls') {
        b.bone('chest')
        b.box(0.2, 0.14, 0.02, { mat: 'cloth', color: lc, y: 0.0, z: 0.112 * B, r: 0.008, ...AO0 })
        for (const sx of [1, -1]) {
          b.box(0.035, 0.32, 0.012, { mat: 'cloth', color: lc, x: sx * 0.09, y: 0.06, z: 0.115 * B, ...AO0 })
          b.box(0.035, 0.3, 0.012, { mat: 'cloth', color: lc, x: sx * 0.09, y: 0.06, z: -0.115 * B, ...AO0 })
          b.cyl(0.012, 0.012, 0.01, { mat: 'steel', color: '#c8b888', x: sx * 0.09, y: 0.06, z: 0.124 * B, rx: Math.PI / 2, ...AO0 })
        }
        b.bone('spine')
        b.box(0.08, 0.06, 0.012, { mat: 'cloth', color: shade(lc, -0.1), y: 0.16, z: 0.11 * B, ...AO0 })
      }
      if (L === 'coveralls') {
        b.bone('chest')
        b.box(0.358 * B * shW, 0.29, 0.228 * B, { mat: 'cloth', color: lc, y: 0.05, r: 0.085, ...AO0 })
      }
    }
    if (legs.check) {
      b.bone('hips')
      b.box(0.33 * B * hipW, 0.02, 0.216 * B, { mat: 'cloth', color: '#d8d8d0', y: -0.06, r: 0.006, ...AO0 })
    }
  } else {
    b.box(0.315 * B * hipW, 0.14, 0.205 * B, { mat: 'cloth', color: '#d8d4c8', y: -0.03, r: 0.05, ...AO0 })
  }

  // ---- extras
  for (const ex of O.extras || []) extra(b, ex, O, B, r)
  if (spec.armor || O.armor) armor(b, spec.armor || O.armor, B)
  if (spec.pack) backpack(b, spec.pack === 'large', B)

  // ---- zombie gore
  if (Z) {
    const spots = ['chest', 'spine', 'upperArmL', 'upperArmR', 'foreArmL', 'thighL', 'thighR', 'head']
    const n = 3 + Math.floor(r() * 4)
    for (let i = 0; i < n; i++) {
      const bn = pickR(r, spots)
      b.bone(bn)
      const big = bn === 'chest' || bn === 'spine'
      const ox = (r() - 0.5) * (big ? 0.24 : 0.06)
      const oy = big ? (r() - 0.5) * 0.18 : -0.05 - r() * 0.15
      const front = r() < 0.6 ? 1 : -1
      b.sphere(big ? 0.05 : 0.03, { mat: 'gloss', color: pickR(r, ['#4a0e0a', '#5e1410', '#3a0a08']), x: ox, y: oy + (bn === 'head' ? 0.12 : 0), z: front * (big ? 0.105 : 0.045), sz: 0.35, ...AO0 })
    }
    // glowing eyes
    b.bone('head')
    for (const sx of [1, -1]) b.sphere(0.011, { mat: 'glowRed', color: '#ffffff', x: sx * 0.036, y: 0.112, z: 0.094, sz: 0.5, ...AO0 })
  }
  return b.buildSkinned()
}

function hair(b, H, hatSpec, r) {
  if (!H || H.style === 'bald') return
  const c = H.color
  const m = { mat: 'cloth', color: c, ...AO0 }
  b.bone('head')
  const hatOn = !!hatSpec && !['headband', 'bandana'].includes(hatSpec.kind)
  const capTl = hatOn ? 0.62 : 0.56
  if (H.style === 'buzz') {
    b.sphere(0.108, { ...m, y: 0.104, sx: 0.93, sy: 1.07, sz: 1.02, tl: Math.PI * 0.52, ws: 16, hs: 8 })
    return
  }
  // base cap of hair
  b.sphere(0.114, { ...m, y: 0.1, sx: 0.95, sy: 1.08, sz: 1.04, tl: Math.PI * capTl, ws: 18, hs: 10 })
  // back of head
  b.sphere(0.11, { ...m, y: 0.085, z: -0.018, sx: 0.95, sy: 1.0, sz: 1.0, ts: Math.PI * 0.45, tl: Math.PI * 0.3, ps: Math.PI * 0.2, pl: Math.PI * 0.6 })
  if (H.style === 'short' || H.style === 'side') {
    b.box(0.17, 0.035, 0.05, { ...m, y: 0.175, z: 0.07, r: 0.016, rx: -0.3, ry: H.style === 'side' ? 0.25 : 0 })
  }
  if (H.style === 'curly') {
    for (let i = 0; i < 12; i++) {
      const a = (i / 12) * Math.PI * 2
      b.sphere(0.04, { ...m, x: Math.cos(a) * 0.075, y: 0.17 + Math.sin(i) * 0.012, z: Math.sin(a) * 0.075 - 0.01, ws: 8, hs: 6 })
    }
  }
  if (H.style === 'long' || H.style === 'ponytail' || H.style === 'bun') {
    b.box(0.2, 0.06, 0.06, { ...m, y: 0.17, z: 0.07, r: 0.025, rx: -0.4 })
  }
  if (H.style === 'long') {
    b.box(0.2, 0.26, 0.06, { ...m, y: 0.0, z: -0.075, r: 0.03 })
    for (const sx of [1, -1]) b.box(0.035, 0.18, 0.06, { ...m, x: sx * 0.09, y: 0.04, z: -0.01, r: 0.015 })
  }
  if (H.style === 'ponytail') b.tube([[0, 0.13, -0.11], [0, 0.07, -0.15], [0, -0.04, -0.14], [0, -0.12, -0.12]], 0.024, { ...m, seg: 6 })
  if (H.style === 'bun') b.sphere(0.045, { ...m, y: 0.18, z: -0.09 })
  if (H.style === 'mohawk') for (let i = 0; i < 6; i++) b.box(0.025, 0.06, 0.04, { ...m, y: 0.2, z: 0.07 - i * 0.035, rx: -0.2 + i * 0.12 })
}

function hat(b, H) {
  const c = H.color
  b.bone('head')
  const m = { mat: 'cloth', color: c, ...AO0 }
  switch (H.kind) {
    case 'cap':
      b.sphere(0.118, { ...m, y: 0.12, sx: 0.96, sy: 0.9, sz: 1.04, tl: Math.PI * 0.5 })
      b.box(0.15, 0.012, 0.09, { ...m, y: 0.13, z: 0.12, rx: 0.12, r: 0.004 })
      b.sphere(0.008, { ...m, y: 0.226 })
      break
    case 'policecap':
      b.cyl(0.11, 0.1, 0.07, { ...m, y: 0.2 })
      b.cyl(0.12, 0.12, 0.012, { ...m, y: 0.24, sx: 1.05 })
      b.box(0.15, 0.01, 0.08, { mat: 'gloss', color: '#111', y: 0.17, z: 0.12, rx: 0.2 })
      b.box(0.03, 0.03, 0.006, { mat: 'steel', color: '#d8b84a', y: 0.205, z: 0.11 })
      break
    case 'patrolcap':
      b.cyl(0.108, 0.112, 0.08, { ...m, y: 0.2 })
      b.box(0.15, 0.01, 0.07, { ...m, y: 0.165, z: 0.12, rx: 0.12 })
      break
    case 'hardhat':
      b.sphere(0.125, { mat: 'gloss', color: c, y: 0.13, sx: 0.98, sy: 0.85, sz: 1.06, tl: Math.PI * 0.5, ...AO0 })
      b.cyl(0.14, 0.14, 0.012, { mat: 'gloss', color: c, y: 0.135, sz: 1.12, ...AO0 })
      b.box(0.02, 0.03, 0.22, { mat: 'gloss', color: shade(c, -0.08), y: 0.235, ...AO0 })
      break
    case 'helmet':
      b.sphere(0.13, { mat: 'paint', color: c, y: 0.13, sx: 1, sy: 0.88, sz: 1.06, tl: Math.PI * 0.55, ...AO0 })
      b.box(0.04, 0.02, 0.03, { mat: 'plain', color: '#222', y: 0.21, z: 0.11, ...AO0 })
      break
    case 'beanie':
      b.sphere(0.12, { ...m, y: 0.13, sx: 0.96, sy: 1.0, sz: 1.04, tl: Math.PI * 0.52 })
      b.cyl(0.118, 0.118, 0.04, { ...m, color: shade(c, -0.08), y: 0.14 })
      break
    case 'straw':
      b.cyl(0.1, 0.11, 0.09, { mat: 'cloth', color: c, y: 0.21, ...AO0 })
      b.cyl(0.21, 0.22, 0.012, { mat: 'cloth', color: c, y: 0.17, ...AO0 })
      b.cyl(0.111, 0.111, 0.022, { mat: 'cloth', color: '#5a3a24', y: 0.185, ...AO0 })
      break
    case 'toque':
      b.cyl(0.11, 0.1, 0.06, { ...m, y: 0.18 })
      b.sphere(0.12, { ...m, y: 0.27, sy: 0.8 })
      break
    case 'headband':
      b.cyl(0.112, 0.112, 0.03, { ...m, y: 0.15 })
      break
    case 'bandana':
      b.sphere(0.116, { ...m, y: 0.11, tl: Math.PI * 0.42 })
      b.box(0.04, 0.06, 0.02, { ...m, y: 0.1, z: -0.11, rx: 0.3 })
      break
  }
}

function shoe(b, side, sh, bareLeg) {
  const sx = side === 'L' ? 1 : -1
  b.bone('foot' + side)
  if (sh.kind === 'bare') {
    b.box(0.08, 0.05, 0.22, { mat: 'skin', color: '#9a9e8a', y: -0.035, z: 0.05, r: 0.02, ...AO0 })
    return
  }
  const c = sh.color
  if (sh.kind === 'boots') {
    b.cyl(0.062, 0.06, 0.16, { mat: 'cloth', color: c, y: 0.04, ...AO0 })
    b.box(0.1, 0.075, 0.24, { mat: 'cloth', color: c, y: -0.03, z: 0.04, r: 0.03, ...AO0 })
    b.box(0.105, 0.025, 0.25, { mat: 'rubber', color: '#1a1714', y: -0.07, z: 0.04, r: 0.008, ...AO0 })
    b.box(0.07, 0.008, 0.07, { mat: 'plain', color: shade(c, -0.25), y: 0.0, z: 0.09, ...AO0 })
  } else if (sh.kind === 'sneakers') {
    b.box(0.095, 0.065, 0.24, { mat: 'cloth', color: c, y: -0.035, z: 0.045, r: 0.03, ...AO0 })
    b.box(0.1, 0.028, 0.25, { mat: 'rubber', color: '#f0eee8', y: -0.068, z: 0.045, r: 0.01, ...AO0 })
    b.box(0.05, 0.006, 0.08, { mat: 'plain', color: '#f0f0f0', y: 0.0, z: 0.08, ...AO0 })
  } else {
    b.box(0.088, 0.055, 0.23, { mat: 'gloss', color: c, y: -0.04, z: 0.045, r: 0.025, ...AO0 })
    b.box(0.09, 0.016, 0.235, { mat: 'rubber', color: '#141210', y: -0.07, z: 0.045, ...AO0 })
  }
  if (!bareLeg) {
    b.bone('shin' + side)
    b.cyl(0.058, 0.058, 0.03, { mat: 'cloth', color: shade(c, -0.1), y: -0.36, ...AO0 })
  }
}

function camo(b, bones, base, r) {
  const cols = [shade(base, -0.25), shade(base, 0.15), '#3a3424']
  for (const bn of bones) {
    b.bone(bn)
    for (let i = 0; i < 4; i++) {
      const big = bn === 'chest' || bn === 'spine'
      b.sphere(big ? 0.045 : 0.035, { mat: 'cloth', color: pickR(r, cols), x: (r() - 0.5) * (big ? 0.26 : 0.08), y: big ? (r() - 0.3) * 0.2 : -0.06 - r() * 0.22, z: (r() < 0.5 ? 1 : -1) * (big ? 0.1 : 0.06), sz: 0.3, sx: 1.3, ...AO0 })
    }
  }
}

function extra(b, ex, O, B, r) {
  switch (ex) {
    case 'stethoscope':
      b.bone('chest')
      b.tube([[-0.07, 0.19, 0.06], [-0.09, 0.08, 0.12], [-0.04, -0.02, 0.125], [0.0, 0.0, 0.125], [0.05, -0.01, 0.125], [0.09, 0.08, 0.12], [0.07, 0.19, 0.06]], 0.006, { mat: 'rubber', color: '#1a1a1a', ...AO0 })
      b.cyl(0.018, 0.018, 0.01, { mat: 'chrome', color: '#d0d0d0', y: -0.03, z: 0.13, rx: Math.PI / 2, ...AO0 })
      break
    case 'badge':
      b.bone('chest')
      b.box(0.03, 0.035, 0.006, { mat: 'steel', color: '#d8b84a', x: 0.08, y: 0.08, z: 0.118 * B, ...AO0 })
      break
    case 'nametag':
      b.bone('chest')
      b.box(0.06, 0.02, 0.005, { mat: 'plain', color: '#f0f0ea', x: 0.08, y: 0.08, z: 0.118 * B, ...AO0 })
      break
    case 'radio':
      b.bone('chest')
      b.box(0.04, 0.07, 0.025, { mat: 'plain', color: '#1a1a1a', x: -0.11, y: 0.12, z: 0.1, ...AO0 })
      b.cyl(0.004, 0.004, 0.05, { mat: 'plain', color: '#1a1a1a', x: -0.12, y: 0.18, z: 0.1, ...AO0 })
      break
    case 'dutybelt':
    case 'belt':
      b.bone('hips')
      b.box(0.335 * B, 0.045, 0.222 * B, { mat: 'gloss', color: '#141414', y: 0.05, r: 0.02, ...AO0 })
      b.box(0.04, 0.03, 0.01, { mat: 'steel', color: '#b8b8b0', y: 0.05, z: 0.113 * B, ...AO0 })
      if (ex === 'dutybelt') {
        b.box(0.05, 0.11, 0.05, { mat: 'gloss', color: '#141414', x: -0.17, y: -0.01, z: 0.02, ...AO0 })
        b.box(0.04, 0.05, 0.04, { mat: 'gloss', color: '#141414', x: 0.15, y: 0.02, z: 0.07, ...AO0 })
      }
      break
    case 'pouches':
      b.bone('hips')
      for (const x of [-0.12, -0.05, 0.05, 0.12]) b.box(0.05, 0.06, 0.04, { mat: 'cloth', color: '#4a5038', x, y: 0.04, z: 0.11 * B, r: 0.008, ...AO0 })
      break
    case 'stripesArms':
      for (const side of ['L', 'R']) {
        b.bone('foreArm' + side)
        b.cyl(0.05 * B, 0.05 * B, 0.025, { mat: 'glowWhite', color: '#c8c8a0', y: -0.12, ...AO0 })
        b.bone('upperArm' + side)
        b.cyl(0.057 * B, 0.057 * B, 0.025, { mat: 'glowWhite', color: '#c8c8a0', y: -0.16, ...AO0 })
      }
      break
    case 'stripesLegs':
      for (const side of ['L', 'R']) {
        b.bone('shin' + side)
        b.cyl(0.058 * B, 0.058 * B, 0.028, { mat: 'glowWhite', color: '#c8c8a0', y: -0.2, ...AO0 })
      }
      break
    case 'stripesChest':
      b.bone('spine')
      b.box(0.31 * B, 0.028, 0.212 * B, { mat: 'glowWhite', color: '#c8c8a0', y: 0.02, r: 0.01, ...AO0 })
      b.bone('chest')
      b.box(0.36 * B, 0.028, 0.232 * B, { mat: 'glowWhite', color: '#c8c8a0', y: 0.1, r: 0.01, ...AO0 })
      break
    case 'suspenders':
      b.bone('chest')
      for (const sx of [1, -1]) b.box(0.03, 0.3, 0.008, { mat: 'cloth', color: '#a83020', x: sx * 0.08, y: 0.05, z: 0.118 * B, ...AO0 })
      break
    case 'apron':
      b.bone('chest')
      b.box(0.22, 0.2, 0.01, { mat: 'cloth', color: '#f4f2ec', y: 0.02, z: 0.118 * B, ...AO0 })
      b.bone('spine')
      b.box(0.26, 0.22, 0.01, { mat: 'cloth', color: '#f4f2ec', y: 0.07, z: 0.108 * B, ...AO0 })
      b.bone('hips')
      b.box(0.28, 0.32, 0.01, { mat: 'cloth', color: '#f4f2ec', y: -0.12, z: 0.11 * B, ...AO0 })
      break
    case 'leatherApron':
      b.bone('chest')
      b.box(0.24, 0.22, 0.012, { mat: 'cloth', color: '#6a4228', y: 0.0, z: 0.12 * B, ...AO0 })
      b.bone('spine')
      b.box(0.28, 0.22, 0.012, { mat: 'cloth', color: '#6a4228', y: 0.07, z: 0.11 * B, ...AO0 })
      b.bone('hips')
      b.box(0.3, 0.36, 0.012, { mat: 'cloth', color: '#6a4228', y: -0.14, z: 0.112 * B, ...AO0 })
      b.box(0.08, 0.06, 0.012, { mat: 'cloth', color: '#4a2a18', x: 0.06, y: -0.06, z: 0.12 * B, ...AO0 })
      break
    case 'neckerchief':
      b.bone('chest')
      b.torus(0.058, 0.016, { mat: 'cloth', color: '#c83a2a', y: 0.18, rx: Math.PI / 2, ...AO0 })
      break
    case 'toolbelt':
      b.bone('hips')
      b.box(0.34 * B, 0.05, 0.226 * B, { mat: 'cloth', color: '#7a5a38', y: 0.05, r: 0.02, ...AO0 })
      for (const x of [-0.13, 0.13]) {
        b.box(0.07, 0.1, 0.06, { mat: 'cloth', color: '#8a6a44', x, y: -0.01, z: 0.09, r: 0.01, ...AO0 })
        b.box(0.012, 0.09, 0.012, { mat: 'steel', color: '#9a9a9a', x: x + 0.02, y: 0.06, z: 0.1, ...AO0 })
      }
      b.box(0.02, 0.12, 0.02, { mat: 'wood', color: '#a07a50', x: 0.17, y: -0.02, z: 0.04, ...AO0 })
      break
    case 'hivis':
    case 'securityvest':
    case 'huntvest': {
      const c = ex === 'hivis' ? '#d8e830' : ex === 'huntvest' ? '#e8661a' : '#1a1a1a'
      b.bone('chest')
      for (const sx of [1, -1]) b.box(0.13, 0.28, 0.232 * B, { mat: 'cloth', color: c, x: sx * 0.11, y: 0.04, r: 0.03, ...AO0 })
      b.box(0.36 * B, 0.28, 0.02, { mat: 'cloth', color: c, y: 0.04, z: -0.115 * B, r: 0.01, ...AO0 })
      b.bone('spine')
      b.box(0.315 * B, 0.2, 0.215 * B, { mat: 'cloth', color: c, y: 0.08, r: 0.06, ...AO0 })
      if (ex === 'hivis') {
        b.bone('spine')
        b.box(0.318 * B, 0.025, 0.218 * B, { mat: 'glowWhite', color: '#b0b0a0', y: 0.05, r: 0.01, ...AO0 })
        b.bone('chest')
        for (const sx of [1, -1]) b.box(0.025, 0.28, 0.235 * B, { mat: 'glowWhite', color: '#b0b0a0', x: sx * 0.08, y: 0.04, ...AO0 })
      }
      if (ex === 'securityvest') {
        b.bone('chest')
        b.box(0.16, 0.035, 0.005, { mat: 'plain', color: '#e8e8e0', y: 0.08, z: -0.127 * B, ...AO0 })
      }
      break
    }
    case 'waistcoat':
      b.bone('chest')
      for (const sx of [1, -1]) b.box(0.12, 0.26, 0.232 * B, { mat: 'cloth', color: '#4a3a4a', x: sx * 0.11, y: 0.03, r: 0.03, ...AO0 })
      b.bone('spine')
      b.box(0.315 * B, 0.2, 0.215 * B, { mat: 'cloth', color: '#4a3a4a', y: 0.08, r: 0.06, ...AO0 })
      break
    case 'tape':
      b.bone('chest')
      for (const sx of [1, -1]) b.box(0.012, 0.3, 0.005, { mat: 'plain', color: '#e8c830', x: sx * 0.06, y: 0.05, z: 0.125 * B, rz: sx * 0.08, ...AO0 })
      break
    case 'glasses':
      b.bone('head')
      for (const sx of [1, -1]) b.torus(0.019, 0.003, { mat: 'plain', color: '#1a1a1a', x: sx * 0.037, y: 0.112, z: 0.1, ...AO0 })
      b.box(0.02, 0.003, 0.003, { mat: 'plain', color: '#1a1a1a', y: 0.114, z: 0.104, ...AO0 })
      break
    case 'goggles':
      b.bone('head')
      b.cyl(0.115, 0.115, 0.025, { mat: 'cloth', color: '#2a2a2a', y: 0.17, ...AO0 })
      for (const sx of [1, -1]) b.cyl(0.024, 0.024, 0.02, { mat: 'glass', color: '#6a8a9a', x: sx * 0.035, y: 0.17, z: 0.11, rx: Math.PI / 2, ...AO0 })
      break
    case 'gloves':
      break
    case 'tie':
      b.bone('chest')
      b.box(0.035, 0.22, 0.008, { mat: 'cloth', color: '#7a2a2a', y: 0.04, z: 0.116 * B, ...AO0 })
      break
    case 'scarf':
      b.bone('chest')
      b.torus(0.062, 0.025, { mat: 'cloth', color: '#7a3a2a', y: 0.18, rx: Math.PI / 2, ...AO0 })
      b.box(0.05, 0.22, 0.02, { mat: 'cloth', color: '#7a3a2a', x: 0.05, y: 0.06, z: 0.12, ...AO0 })
      break
    case 'headband':
      b.bone('head')
      b.cyl(0.112, 0.112, 0.028, { mat: 'cloth', color: '#e8e8e8', y: 0.155, ...AO0 })
      break
    case 'bandana':
      b.bone('head')
      b.sphere(0.117, { mat: 'cloth', color: '#a82a2a', y: 0.11, tl: Math.PI * 0.42, ...AO0 })
      break
    case 'rag':
      b.bone('hips')
      b.box(0.05, 0.14, 0.012, { mat: 'cloth', color: '#a82a2a', x: -0.15, y: -0.06, z: -0.08, ...AO0 })
      break
    case 'grease':
      b.bone('chest')
      b.sphere(0.03, { mat: 'plain', color: '#2a2a2a', x: -0.05, y: -0.02, z: 0.11 * B, sz: 0.2, ...AO0 })
      break
    case 'pens':
      b.bone('chest')
      for (let i = 0; i < 3; i++) b.cyl(0.004, 0.004, 0.06, { mat: 'plastic', color: ['#1a3a8a', '#1a1a1a', '#a82a2a'][i], x: 0.07 + i * 0.012, y: 0.11, z: 0.12 * B, ...AO0 })
      break
    case 'pencil':
      b.bone('head')
      b.cyl(0.004, 0.004, 0.08, { mat: 'plain', color: '#e8b830', x: 0.1, y: 0.11, z: 0.0, rz: 1.2, ...AO0 })
      break
    case 'wrench':
      b.bone('hips')
      b.box(0.015, 0.2, 0.01, { mat: 'steel', color: '#a8a8a8', x: 0.17, y: -0.06, z: 0.04, ...AO0 })
      break
    case 'backpackSmall':
      backpack(b, false, B)
      break
  }
}

function backpack(b, large, B) {
  b.bone('chest')
  const h = large ? 0.46 : 0.32
  const c = large ? '#4a5038' : '#3a4a6a'
  b.box(0.28, h, 0.14, { mat: 'cloth', color: c, y: 0.03 - (large ? 0.06 : 0), z: -0.17 * B, r: 0.05, ...AO0 })
  b.box(0.22, h * 0.45, 0.05, { mat: 'cloth', color: shade(c, -0.1), y: -0.04 - (large ? 0.08 : 0), z: -0.25 * B, r: 0.02, ...AO0 })
  if (large) {
    b.cyl(0.06, 0.06, 0.3, { mat: 'cloth', color: '#6a5a3a', y: 0.28, z: -0.16, rz: Math.PI / 2, ...AO0 })
  }
  for (const sx of [1, -1]) b.box(0.035, 0.3, 0.02, { mat: 'cloth', color: shade(c, -0.15), x: sx * 0.09, y: 0.05, z: 0.112 * B, ...AO0 })
}

function armor(b, kind, B) {
  if (kind === 'jacket') {
    const c = '#3a2a1e'
    b.bone('chest')
    for (const sx of [1, -1]) b.box(0.15, 0.3, 0.236 * B, { mat: 'gloss', color: c, x: sx * 0.1, y: 0.05, r: 0.05, ...AO0 })
    b.box(0.36 * B, 0.3, 0.03, { mat: 'gloss', color: c, y: 0.05, z: -0.105 * B, r: 0.02, ...AO0 })
    for (const sx of [1, -1]) b.box(0.08, 0.05, 0.08, { mat: 'gloss', color: c, x: sx * 0.07, y: 0.19, z: 0.07, rz: sx * 0.6, rx: -0.5, ...AO0 })
    b.bone('spine')
    b.box(0.31 * B, 0.22, 0.216 * B, { mat: 'gloss', color: c, y: 0.08, r: 0.07, ...AO0 })
    b.box(0.12, 0.22, 0.01, { mat: 'cloth', color: '#5a5a5a', y: 0.08, z: 0.11 * B, ...AO0 })
    for (const side of ['L', 'R']) {
      b.bone('upperArm' + side)
      b.capsule(0.058 * B, 0.18, { mat: 'gloss', color: c, y: -0.14, ...AO0 })
      b.bone('foreArm' + side)
      b.capsule(0.05 * B, 0.15, { mat: 'gloss', color: c, y: -0.11, ...AO0 })
    }
    return
  }
  if (kind === 'vest' || kind === 'military') {
    const c = kind === 'vest' ? '#2a3028' : '#4e5438'
    b.bone('chest')
    b.box(0.34 * B, 0.27, 0.27 * B, { mat: 'cloth', color: c, y: 0.03, r: 0.04, ...AO0 })
    b.bone('spine')
    b.box(0.31 * B, 0.16, 0.25 * B, { mat: 'cloth', color: c, y: 0.12, r: 0.04, ...AO0 })
    for (const x of [-0.1, 0, 0.1]) b.box(0.07, 0.08, 0.04, { mat: 'cloth', color: shade(c, -0.12), x, y: 0.08, z: 0.13 * B, r: 0.01, ...AO0 })
    b.bone('chest')
    for (const sx of [1, -1]) b.box(0.06, 0.04, 0.27 * B, { mat: 'cloth', color: shade(c, -0.1), x: sx * 0.12, y: 0.17, ...AO0 })
    if (kind === 'military') {
      b.box(0.06, 0.08, 0.03, { mat: 'cloth', color: shade(c, -0.15), x: 0.1, y: 0.07, z: 0.14 * B, ...AO0 })
      b.bone('head')
      b.sphere(0.132, { mat: 'paint', color: '#4e5438', y: 0.13, sx: 1, sy: 0.88, sz: 1.06, tl: Math.PI * 0.55, ...AO0 })
      for (const side of ['L', 'R']) {
        b.bone('shin' + side)
        b.box(0.1, 0.09, 0.05, { mat: 'paint', color: '#3a3e2a', y: 0.0, z: 0.05, r: 0.02, ...AO0 })
      }
    } else {
      b.box(0.12, 0.03, 0.005, { mat: 'plain', color: '#e8e8e0', y: 0.08, z: 0.14 * B, ...AO0 })
    }
    return
  }
  if (kind === 'riot') {
    const c = '#1c2026'
    b.bone('chest')
    b.box(0.36 * B, 0.29, 0.27 * B, { mat: 'paint', color: c, y: 0.04, r: 0.05, ...AO0 })
    for (const side of ['L', 'R']) {
      const sx = side === 'L' ? 1 : -1
      b.bone('upperArm' + side)
      b.sphere(0.085, { mat: 'paint', color: c, x: sx * 0.01, y: 0.0, sy: 0.7, tl: Math.PI * 0.6, ...AO0 })
      b.bone('foreArm' + side)
      b.box(0.08, 0.17, 0.08, { mat: 'paint', color: c, y: -0.12, r: 0.03, ...AO0 })
      b.bone('shin' + side)
      b.box(0.1, 0.3, 0.07, { mat: 'paint', color: c, y: -0.17, z: 0.03, r: 0.03, ...AO0 })
      b.bone('thigh' + side)
      b.box(0.12, 0.2, 0.06, { mat: 'paint', color: c, y: -0.2, z: 0.05, r: 0.03, ...AO0 })
    }
    b.bone('spine')
    b.box(0.32 * B, 0.2, 0.25 * B, { mat: 'paint', color: c, y: 0.09, r: 0.05, ...AO0 })
    b.bone('head')
    b.sphere(0.135, { mat: 'paint', color: c, y: 0.12, sy: 0.95, tl: Math.PI * 0.62, ...AO0 })
    b.sphere(0.138, { mat: 'glass', color: '#9ab0c0', y: 0.1, z: 0.01, ts: Math.PI * 0.35, tl: Math.PI * 0.35, ps: Math.PI * 0.15, pl: Math.PI * 0.7, ...AO0 })
    return
  }
  if (kind === 'ghillie') {
    const cols = ['#4a5a30', '#5a5a38', '#3e4a28', '#6a6440']
    for (const bn of ['chest', 'spine', 'head', 'upperArmL', 'upperArmR', 'thighL', 'thighR']) {
      b.bone(bn)
      for (let i = 0; i < 9; i++) {
        const big = bn === 'chest' || bn === 'spine'
        b.box(0.02, 0.12, 0.012, { mat: 'cloth', color: cols[i % 4], x: (Math.random() - 0.5) * (big ? 0.34 : 0.1), y: bn === 'head' ? 0.15 + Math.random() * 0.05 : -Math.random() * (big ? 0.2 : 0.3), z: (Math.random() - 0.5) * (big ? 0.26 : 0.1), rz: (Math.random() - 0.5) * 0.8, rx: (Math.random() - 0.5) * 0.6, ...AO0 })
      }
    }
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
    const { mesh, bones } = buildCharacterMesh(spec)
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
    this.mesh.geometry.dispose()
  }
}
