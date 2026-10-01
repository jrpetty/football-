// Procedural low-poly models built from primitives: people, zombies,
// weapons, lootable containers, decor and every camp workstation.
import * as THREE from 'three'
import { mergeGeometries } from 'three/addons/utils/BufferGeometryUtils.js'
import { M, G, tex } from './gfx.js'
import { pick, rand } from './util.js'

// Add a mesh to a parent. rot = [x,y,z]
export function part(parent, geo, mat, x = 0, y = 0, z = 0, rot = null, scale = null, shadow = true) {
  const m = new THREE.Mesh(geo, mat)
  m.position.set(x, y, z)
  if (rot) m.rotation.set(rot[0] || 0, rot[1] || 0, rot[2] || 0)
  if (scale) Array.isArray(scale) ? m.scale.set(...scale) : m.scale.setScalar(scale)
  m.castShadow = shadow
  m.receiveShadow = true
  parent.add(m)
  return m
}
const box = (p, w, h, d, mat, x, y, z, rot, sh) => part(p, G('box', w, h, d), mat, x, y, z, rot, null, sh)
const cyl = (p, rt, rb, h, seg, mat, x, y, z, rot, sh) => part(p, G('cyl', rt, rb, h, seg), mat, x, y, z, rot, null, sh)

// Merge all static meshes under `group` into one mesh per material.
export function bake(group) {
  group.updateMatrixWorld(true)
  const inv = new THREE.Matrix4().copy(group.matrixWorld).invert()
  const byMat = new Map()
  const toRemove = []
  group.traverse((o) => {
    if (!o.isMesh || o.userData.keep || o.isInstancedMesh) return
    const g = o.geometry.clone()
    g.applyMatrix4(new THREE.Matrix4().multiplyMatrices(inv, o.matrixWorld))
    const key = o.material.uuid + (o.castShadow ? 's' : 'n')
    if (!byMat.has(key)) byMat.set(key, { mat: o.material, geos: [], shadow: o.castShadow })
    byMat.get(key).geos.push(g.index ? g.toNonIndexed() : g)
    toRemove.push(o)
  })
  for (const o of toRemove) o.parent.remove(o)
  for (const { mat, geos, shadow } of byMat.values()) {
    for (const g of geos) {
      for (const k of Object.keys(g.attributes)) if (!['position', 'normal', 'uv'].includes(k)) g.deleteAttribute(k)
      if (!g.attributes.uv) g.setAttribute('uv', new THREE.Float32BufferAttribute(new Float32Array(g.attributes.position.count * 2), 2))
    }
    const merged = mergeGeometries(geos, false)
    if (!merged) continue
    const m = new THREE.Mesh(merged, mat)
    m.castShadow = shadow
    m.receiveShadow = true
    group.add(m)
  }
  return group
}

// ---------------------------------------------------------------- humans
const SKIN = { skin: null }
export function makeHuman(look, opts = {}) {
  const zombie = !!opts.zombie
  const root = new THREE.Group()
  const body = new THREE.Group() // bobs & leans
  root.add(body)
  const skin = M(look.skin, { rough: 0.7 })
  const shirt = M(look.shirt, { rough: 0.9 })
  const pants = M(look.pants, { rough: 0.9 })
  const shoe = M('#262320', { rough: 0.8 })
  const hair = M(look.hair, { rough: 0.95 })
  const bulk = look.bulk || 1

  const hips = new THREE.Group()
  hips.position.y = 0.88
  body.add(hips)
  part(hips, G('box', 0.34 * bulk, 0.16, 0.2), pants, 0, 0, 0)

  const mkLeg = (side) => {
    const g = new THREE.Group()
    g.position.set(0.1 * side * bulk, 0, 0)
    hips.add(g)
    part(g, G('capsule', 0.075 * bulk, 0.62, 3, 8), pants, 0, -0.42, 0)
    part(g, G('box', 0.13, 0.08, 0.24), shoe, 0, -0.84, 0.04)
    return g
  }
  const legL = mkLeg(-1)
  const legR = mkLeg(1)

  const torso = new THREE.Group()
  torso.position.y = 0.95
  body.add(torso)
  part(torso, G('capsule', 0.19, 0.3, 4, 10), shirt, 0, 0.28, 0, null, [bulk * 1.05, 1, 0.68 * bulk])
  if (look.armor) {
    const am = M(look.armor, { rough: 0.7 })
    part(torso, G('box', 0.42, 0.36, 0.3), am, 0, 0.3, 0, null, [bulk, 1, bulk])
  }
  if (look.pack) {
    const pm = M('#4b4a36', { rough: 0.9 })
    part(torso, G('box', 0.3, 0.36, 0.16), pm, 0, 0.32, -0.2)
    part(torso, G('box', 0.26, 0.1, 0.14), pm, 0, 0.1, -0.21)
  }
  const neck = new THREE.Group()
  neck.position.y = 0.62
  torso.add(neck)
  const head = new THREE.Group()
  head.position.y = 0.12
  neck.add(head)
  part(head, G('sphere', 0.14, 12, 10), skin, 0, 0.02, 0, null, [0.95, 1.08, 1])
  // eyes: glowing for zombies
  const eyeMat = zombie ? M('#ff5a2a', { emissive: '#ff3a10', ei: 2.2 }) : M('#1a1614')
  part(head, G('sphere', 0.02, 6, 4), eyeMat, -0.05, 0.04, 0.125, null, null, false)
  part(head, G('sphere', 0.02, 6, 4), eyeMat, 0.05, 0.04, 0.125, null, null, false)
  if (look.hairStyle === 1) part(head, G('sphere', 0.15, 12, 8, 0, Math.PI * 2, 0, Math.PI * 0.5), hair, 0, 0.03, -0.01)
  else if (look.hairStyle === 2) {
    part(head, G('sphere', 0.15, 12, 8, 0, Math.PI * 2, 0, Math.PI * 0.55), hair, 0, 0.03, -0.01)
    part(head, G('box', 0.26, 0.24, 0.1), hair, 0, -0.06, -0.09)
  } else if (look.hairStyle === 3) {
    const cap = M(look.hat || '#5a5f3a', { rough: 0.9 })
    part(head, G('sphere', 0.155, 12, 8, 0, Math.PI * 2, 0, Math.PI * 0.45), cap, 0, 0.04, 0)
    part(head, G('box', 0.2, 0.02, 0.14), cap, 0, 0.07, 0.13)
  } else if (look.hairStyle === 4) {
    part(head, G('sphere', 0.15, 12, 8, 0, Math.PI * 2, 0, Math.PI * 0.4), hair, 0, 0.035, 0)
  }

  const mkArm = (side) => {
    const g = new THREE.Group()
    g.position.set(0.25 * side * bulk, 0.5, 0)
    torso.add(g)
    part(g, G('capsule', 0.058 * bulk, 0.44, 3, 8), shirt, 0, -0.25, 0)
    part(g, G('sphere', 0.06, 8, 6), skin, 0, -0.52, 0)
    const hand = new THREE.Group()
    hand.position.set(0, -0.52, 0)
    g.add(hand)
    return { g, hand }
  }
  const aL = mkArm(-1)
  const aR = mkArm(1)

  const rig = {
    root,
    body,
    hips,
    torso,
    head,
    neck,
    legL,
    legR,
    armL: aL.g,
    armR: aR.g,
    handR: aR.hand,
    handL: aL.hand,
    weapon: null,
    weaponModel: 'none',
    zombie,
    t: Math.random() * 10,
    phase: Math.random() * 6,
  }
  if (zombie) {
    // Rotting details: torn shirt patch and a hunch.
    part(torso, G('box', 0.16, 0.14, 0.02), M('#5a1d17', { rough: 1 }), 0.06, 0.25, 0.14, null, null, false)
    neck.rotation.x = 0.35
    head.rotation.z = (Math.random() - 0.5) * 0.5
  }
  root.userData.rig = rig
  return rig
}

export function setWeapon(rig, model) {
  if (rig.weaponModel === model) return
  if (rig.weapon) rig.handR.remove(rig.weapon)
  rig.weapon = model && model !== 'none' ? makeWeapon(model) : null
  rig.weaponModel = model || 'none'
  if (rig.weapon) rig.handR.add(rig.weapon)
}

export function makeWeapon(model) {
  const g = new THREE.Group()
  const wood = M('#7a5334')
  const steel = M('#9aa2a8', { metal: 0.6, rough: 0.4 })
  const dark = M('#26292c', { metal: 0.4, rough: 0.5 })
  switch (model) {
    case 'bat':
      cyl(g, 0.045, 0.025, 0.8, 8, wood, 0, 0.3, 0.06, [0.25, 0, 0], false)
      break
    case 'nailbat':
      cyl(g, 0.05, 0.025, 0.8, 8, wood, 0, 0.3, 0.06, [0.25, 0, 0], false)
      for (let i = 0; i < 5; i++) box(g, 0.14, 0.01, 0.01, steel, 0, 0.5 + i * 0.05, 0.1 + i * 0.012, [0.25, i, 0], false)
      break
    case 'pipe':
      cyl(g, 0.03, 0.03, 0.75, 8, steel, 0, 0.28, 0.05, [0.25, 0, 0], false)
      break
    case 'machete':
      box(g, 0.04, 0.14, 0.04, M('#2a2420'), 0, 0.02, 0.02, null, false)
      box(g, 0.012, 0.55, 0.07, steel, 0, 0.36, 0.05, [0.2, 0, 0], false)
      break
    case 'katana':
      box(g, 0.04, 0.2, 0.04, M('#1a1a22'), 0, 0.05, 0.02, null, false)
      box(g, 0.01, 0.8, 0.045, M('#d8dde2', { metal: 0.8, rough: 0.2 }), 0, 0.55, 0.07, [0.15, 0, 0], false)
      break
    case 'axe':
      cyl(g, 0.025, 0.025, 0.8, 6, wood, 0, 0.32, 0.05, [0.2, 0, 0], false)
      box(g, 0.03, 0.14, 0.2, M('#b8342a', { rough: 0.5 }), 0, 0.66, 0.2, [0.2, 0, 0], false)
      break
    case 'pistol':
      box(g, 0.05, 0.1, 0.06, dark, 0, -0.02, 0.02, null, false)
      box(g, 0.05, 0.06, 0.22, dark, 0, 0.04, 0.1, null, false)
      break
    case 'smg':
      box(g, 0.06, 0.12, 0.06, dark, 0, -0.02, 0.02, null, false)
      box(g, 0.06, 0.09, 0.38, dark, 0, 0.05, 0.14, null, false)
      box(g, 0.03, 0.16, 0.04, dark, 0, -0.05, 0.14, null, false)
      break
    case 'shotgun':
      box(g, 0.06, 0.08, 0.3, wood, 0, 0.02, -0.12, null, false)
      cyl(g, 0.03, 0.03, 0.7, 8, dark, 0, 0.05, 0.3, [Math.PI / 2, 0, 0], false)
      box(g, 0.06, 0.05, 0.18, wood, 0, 0.0, 0.26, null, false)
      break
    case 'rifle':
      box(g, 0.06, 0.1, 0.34, wood, 0, 0.0, -0.1, null, false)
      box(g, 0.05, 0.07, 0.5, dark, 0, 0.05, 0.3, null, false)
      cyl(g, 0.018, 0.018, 0.4, 6, dark, 0, 0.06, 0.7, [Math.PI / 2, 0, 0], false)
      cyl(g, 0.025, 0.025, 0.18, 8, dark, 0, 0.12, 0.25, [Math.PI / 2, 0, 0], false)
      break
    case 'crossbow':
      box(g, 0.05, 0.07, 0.55, wood, 0, 0.03, 0.15, null, false)
      box(g, 0.6, 0.03, 0.04, dark, 0, 0.05, 0.38, null, false)
      break
  }
  return g
}

// Animation driver shared by survivors and zombies.
// mode: idle | walk | run | attack | shoot | work | search | down | dead | sit | guard
export function animate(rig, dt, mode, speed = 1, extra = 0) {
  rig.t += dt * speed
  const t = rig.t
  const z = rig.zombie
  let legA = 0
  let armL = 0
  let armR = 0
  let armRz = 0
  let armLz = 0
  let bob = 0
  let lean = 0
  let bodyY = 0
  let rootRX = 0
  const gun = rig.weapon && ['pistol', 'smg', 'shotgun', 'rifle', 'crossbow'].includes(rig.weaponModel)
  switch (mode) {
    case 'walk':
    case 'run': {
      const f = mode === 'run' ? 11 : 7.5
      const a = mode === 'run' ? 0.85 : 0.55
      legA = Math.sin(t * f) * a
      armL = -legA * 0.8
      armR = legA * 0.8
      bob = Math.abs(Math.sin(t * f)) * 0.05
      lean = mode === 'run' ? 0.18 : 0.05
      if (z) {
        legA *= 0.6
        armL = -1.35 + Math.sin(t * 3) * 0.12
        armR = -1.25 + Math.sin(t * 3 + 1) * 0.12
        lean = 0.2
        bob = Math.abs(Math.sin(t * f * 0.5)) * 0.05
      } else if (gun) {
        armR = -0.4
      }
      break
    }
    case 'attack': {
      // extra = 0..1 swing progress
      const s = Math.sin(Math.min(1, extra) * Math.PI)
      if (z) {
        armL = -1.5 + s * 0.5
        armR = -1.5 - s * 0.6
        lean = 0.25 + s * 0.15
      } else {
        armR = -2.4 + s * 2.6
        armL = -0.3
        lean = s * 0.2
      }
      break
    }
    case 'shoot':
      armR = -1.5
      armL = -1.35
      armLz = -0.45
      lean = -0.03 - extra * 0.08
      break
    case 'guard':
      armR = gun ? -1.45 : -0.5
      armL = gun ? -1.3 : 0.1
      armLz = gun ? -0.45 : 0
      bob = Math.sin(t * 1.5) * 0.01
      break
    case 'work': {
      const s = Math.sin(t * 7)
      armR = -1.4 + s * 0.8
      armL = -1.0 + Math.sin(t * 7 + 0.5) * 0.3
      lean = 0.25
      bob = Math.abs(s) * 0.02
      break
    }
    case 'farm': {
      const s = Math.sin(t * 4)
      armR = -0.9 + s * 0.5
      armL = -0.9 + s * 0.5
      lean = 0.55
      bodyY = -0.1
      break
    }
    case 'search': {
      armR = -1.2 + Math.sin(t * 9) * 0.35
      armL = -1.1 + Math.sin(t * 9 + 2) * 0.35
      lean = 0.3
      bodyY = -0.05
      break
    }
    case 'sit':
      legA = 0
      bodyY = -0.42
      armL = -0.5
      armR = -0.5
      lean = 0.15
      break
    case 'down':
    case 'dead':
      rootRX = -Math.PI / 2
      bodyY = 0.12
      armL = -2.6
      armR = -2.2
      break
    default: {
      // idle
      bob = Math.sin(t * 1.8 + rig.phase) * 0.012
      armL = Math.sin(t * 1.2 + rig.phase) * 0.05
      armR = -armL
      if (z) {
        armL = -0.9 + Math.sin(t * 1.4) * 0.15
        armR = -0.6 + Math.sin(t * 1.1) * 0.15
        lean = 0.2 + Math.sin(t * 0.7) * 0.05
      } else if (gun) {
        armR = -0.35
      }
    }
  }
  const k = 1 - Math.exp(-dt * 16)
  const L = (o, prop, v) => (o[prop] += (v - o[prop]) * k)
  L(rig.legL.rotation, 'x', legA)
  L(rig.legR.rotation, 'x', -legA)
  if (mode === 'sit') {
    L(rig.legL.rotation, 'x', -1.4)
    L(rig.legR.rotation, 'x', -1.4)
  }
  L(rig.armL.rotation, 'x', armL)
  L(rig.armR.rotation, 'x', armR)
  L(rig.armL.rotation, 'z', -0.08 + armLz)
  L(rig.armR.rotation, 'z', 0.08 + armRz)
  L(rig.torso.rotation, 'x', lean)
  L(rig.body.position, 'y', bob + bodyY)
  L(rig.body.rotation, 'x', rootRX)
}

// ---------------------------------------------------------------- props
const WOOD = () => M('#8a6441', { rough: 0.9 })
const DWOOD = () => M('#5e4330', { rough: 0.9 })
const METAL = () => M('#8b939a', { metal: 0.5, rough: 0.5 })
const DMETAL = () => M('#4a5056', { metal: 0.4, rough: 0.6 })
const RUST = () => M('#8a5536', { metal: 0.2, rough: 0.8 })

// Items on shelves — small coloured boxes and cans.
function stock(g, w, y, d, n, cols) {
  for (let i = 0; i < n; i++) {
    const x = -w / 2 + 0.08 + (i / Math.max(1, n - 1)) * (w - 0.16)
    if (Math.random() < 0.25) continue
    const c = pick(cols)
    if (Math.random() < 0.5) cyl(g, 0.045, 0.045, 0.12, 8, M(c, { rough: 0.5 }), x, y + 0.06, (Math.random() - 0.5) * d * 0.5, null, false)
    else box(g, 0.1, 0.14 + Math.random() * 0.08, 0.12, M(c), x, y + 0.08, (Math.random() - 0.5) * d * 0.4, null, false)
  }
}
const STOCK_COLS = ['#c9442e', '#e0b040', '#3f7fb8', '#e8e0d0', '#5a9a4a', '#d87a2a', '#7a4ab0']

// Lootable container models, facing +z, fitting inside one tile.
export function makeContainer(kind, level = 1) {
  const g = new THREE.Group()
  switch (kind) {
    case 'fridge': {
      const white = M('#dcdad2', { rough: 0.4 })
      box(g, 0.75, 1.75, 0.7, white, 0, 0.875, -0.1)
      box(g, 0.73, 0.02, 0.02, M('#9a9890'), 0, 1.15, 0.26)
      box(g, 0.04, 0.35, 0.04, METAL(), 0.3, 1.4, 0.28)
      box(g, 0.04, 0.3, 0.04, METAL(), 0.3, 0.8, 0.28)
      break
    }
    case 'cabinet': {
      const w = WOOD()
      box(g, 0.9, 0.9, 0.6, w, 0, 0.45, -0.15)
      box(g, 0.94, 0.05, 0.64, M('#c8c0b0', { rough: 0.5 }), 0, 0.92, -0.15)
      box(g, 0.02, 0.8, 0.02, DWOOD(), 0, 0.45, 0.16)
      box(g, 0.1, 0.03, 0.03, METAL(), -0.1, 0.7, 0.17)
      box(g, 0.1, 0.03, 0.03, METAL(), 0.1, 0.7, 0.17)
      break
    }
    case 'wardrobe': {
      const w = M('#6e4c34', { rough: 0.85 })
      box(g, 0.95, 1.9, 0.6, w, 0, 0.95, -0.15)
      box(g, 0.02, 1.8, 0.02, DWOOD(), 0, 0.95, 0.16)
      box(g, 0.03, 0.12, 0.03, METAL(), -0.07, 1.0, 0.17)
      box(g, 0.03, 0.12, 0.03, METAL(), 0.07, 1.0, 0.17)
      break
    }
    case 'desk': {
      const w = M('#7b5a3e')
      box(g, 1.0, 0.06, 0.6, w, 0, 0.75, -0.05)
      box(g, 0.4, 0.7, 0.56, w, 0.28, 0.37, -0.05)
      box(g, 0.05, 0.72, 0.5, w, -0.45, 0.36, -0.05)
      box(g, 0.3, 0.2, 0.25, M('#2a2d30'), -0.15, 0.88, -0.15)
      box(g, 0.2, 0.02, 0.28, M('#e8e4d8'), 0.15, 0.79, 0.0, [0, 0.3, 0])
      break
    }
    case 'bookshelf': {
      const w = M('#6a4a30')
      box(g, 0.95, 1.8, 0.35, w, 0, 0.9, -0.3)
      for (let i = 0; i < 4; i++) {
        const y = 0.2 + i * 0.42
        for (let b = 0; b < 7; b++) {
          if (Math.random() < 0.2) continue
          const h = 0.24 + Math.random() * 0.1
          box(g, 0.1, h, 0.26, M(pick(['#8a2a22', '#2a4a6a', '#3a5a2a', '#8a6a2a', '#5a3a5a', '#c9b88a'])), -0.38 + b * 0.125, y + h / 2, -0.2, null, false)
        }
      }
      break
    }
    case 'trash': {
      const m = M('#5d6a58', { metal: 0.3, rough: 0.6 })
      cyl(g, 0.26, 0.22, 0.75, 12, m, 0, 0.375, 0)
      cyl(g, 0.28, 0.28, 0.06, 12, m, 0, 0.78, 0)
      break
    }
    case 'shelf': {
      const fr = M('#b8b8b0', { metal: 0.5, rough: 0.5 })
      for (const x of [-0.45, 0.45]) box(g, 0.05, 1.6, 0.5, fr, x, 0.8, 0)
      for (let i = 0; i < 4; i++) {
        box(g, 0.95, 0.03, 0.5, fr, 0, 0.1 + i * 0.45, 0)
        stock(g, 0.9, 0.11 + i * 0.45, 0.5, 6, STOCK_COLS)
      }
      box(g, 0.9, 1.5, 0.02, fr, 0, 0.8, 0)
      break
    }
    case 'register': {
      box(g, 1.0, 0.95, 0.6, M('#7a6a58'), 0, 0.475, -0.1)
      box(g, 0.36, 0.18, 0.3, M('#2b2d30'), 0, 1.04, -0.05)
      box(g, 0.3, 0.14, 0.05, M('#3a4a40', { emissive: '#1a3a2a', ei: 0.6 }), 0, 1.2, -0.1, [-0.3, 0, 0])
      break
    }
    case 'toolrack': {
      const w = M('#9a7a52')
      box(g, 1.0, 1.4, 0.06, w, 0, 0.9, -0.4)
      box(g, 1.0, 0.8, 0.5, M('#6a5a48'), 0, 0.4, -0.2)
      for (let i = 0; i < 5; i++) {
        const x = -0.38 + i * 0.19
        box(g, 0.04, 0.3 + Math.random() * 0.2, 0.03, i % 2 ? METAL() : M('#c9442e'), x, 1.25, -0.35, null, false)
      }
      box(g, 0.3, 0.12, 0.2, M('#c9442e', { rough: 0.5 }), 0.2, 0.86, -0.2)
      break
    }
    case 'medcab': {
      const wh = M('#e8e6de', { rough: 0.5 })
      box(g, 0.8, 1.2, 0.35, wh, 0, 0.9, -0.3)
      box(g, 0.8, 0.3, 0.35, wh, 0, 0.15, -0.3)
      box(g, 0.24, 0.07, 0.01, M('#c8323a'), 0, 1.1, -0.12, null, false)
      box(g, 0.07, 0.24, 0.01, M('#c8323a'), 0, 1.1, -0.12, null, false)
      break
    }
    case 'locker': {
      const m = M('#6b7a86', { metal: 0.5, rough: 0.5 })
      box(g, 0.9, 1.85, 0.5, m, 0, 0.925, -0.2)
      for (const x of [-0.3, 0, 0.3]) {
        box(g, 0.01, 1.8, 0.02, M('#3a4550'), x - 0.15, 0.925, 0.06, null, false)
        for (let v = 0; v < 3; v++) box(g, 0.14, 0.015, 0.02, M('#3a4550'), x, 1.6 - v * 0.05, 0.06, null, false)
      }
      break
    }
    case 'gunlocker': {
      const m = M('#3f4a36', { metal: 0.5, rough: 0.5 })
      box(g, 0.9, 1.9, 0.55, m, 0, 0.95, -0.18)
      box(g, 0.02, 1.8, 0.02, M('#222'), 0, 0.95, 0.1, null, false)
      box(g, 0.12, 0.16, 0.05, M('#d8b040', { metal: 0.8, rough: 0.3, emissive: '#6a4a00', ei: 0.4 }), 0.1, 1.0, 0.12, null, false)
      break
    }
    case 'safe': {
      const m = M('#2e3236', { metal: 0.6, rough: 0.4 })
      box(g, 0.75, 0.8, 0.7, m, 0, 0.4, -0.1)
      cyl(g, 0.1, 0.1, 0.05, 16, M('#b0b4b8', { metal: 0.8, rough: 0.3 }), 0.1, 0.45, 0.27, [Math.PI / 2, 0, 0], false)
      box(g, 0.04, 0.2, 0.04, METAL(), -0.2, 0.45, 0.27, null, false)
      break
    }
    case 'crate': {
      const m = M('#a78455', { map: 'crate' })
      box(g, 0.85, 0.75, 0.85, m, 0, 0.375, 0)
      if (Math.random() < 0.6) box(g, 0.6, 0.5, 0.6, m, 0.05, 1.0, 0.02, [0, 0.4, 0])
      break
    }
    case 'milcrate': {
      const m = M('#4e5a3a', { map: 'crate' })
      box(g, 0.95, 0.6, 0.7, m, 0, 0.3, 0)
      box(g, 0.2, 0.08, 0.02, M('#d8d0a0'), 0, 0.42, 0.36, null, false)
      box(g, 0.8, 0.45, 0.6, m, 0, 0.83, 0, [0, 0.1, 0])
      break
    }
    case 'pump': {
      box(g, 0.6, 1.5, 0.45, M('#c8442e', { rough: 0.6 }), 0, 0.75, 0)
      box(g, 0.5, 0.3, 0.02, M('#1a1a1a', { emissive: '#203020', ei: 0.4 }), 0, 1.15, 0.24, null, false)
      box(g, 0.66, 0.1, 0.5, M('#e8e0d0'), 0, 1.55, 0)
      cyl(g, 0.03, 0.03, 0.6, 6, M('#1a1a1a'), 0.33, 0.8, 0.1, [0.3, 0, 0], false)
      break
    }
    case 'dumpster': {
      const m = M('#3f6a4a', { metal: 0.4, rough: 0.6 })
      box(g, 1.7, 1.0, 0.9, m, 0, 0.55, 0)
      box(g, 1.72, 0.06, 0.95, M('#2a2a2a'), 0, 1.07, -0.05, [-0.15, 0, 0])
      for (const x of [-0.7, 0.7]) cyl(g, 0.06, 0.06, 0.08, 8, M('#111'), x, 0.05, 0.35, [0, 0, Math.PI / 2])
      break
    }
    case 'car':
      return makeCar()
  }
  return g
}

export function makeCar(color) {
  const g = new THREE.Group()
  const col = color || pick(['#6a3a32', '#3a4a5a', '#8a8070', '#2a3a2a', '#9a8a5a', '#4a4a50', '#7a6a4a', '#5a2a2a'])
  const body = M(col, { metal: 0.3, rough: 0.55 })
  const glass = M('#2a3238', { metal: 0.2, rough: 0.15 })
  const rust = RUST()
  box(g, 1.7, 0.55, 3.8, body, 0, 0.55, 0)
  box(g, 1.5, 0.5, 1.9, body, 0, 1.07, -0.2)
  box(g, 1.52, 0.38, 1.6, glass, 0, 1.08, -0.2, null, false)
  box(g, 1.72, 0.2, 0.3, rust, 0, 0.4, 1.8, null, false)
  box(g, 0.4, 0.1, 0.6, rust, -0.3, 0.83, 1.0, [0, 0.4, 0], false)
  for (const [x, z] of [[-0.8, 1.2], [0.8, 1.2], [-0.8, -1.25], [0.8, -1.25]]) cyl(g, 0.33, 0.33, 0.24, 12, M('#151515', { rough: 0.9 }), x, 0.33, z, [0, 0, Math.PI / 2])
  box(g, 0.3, 0.12, 0.04, M('#e8e0c0', { emissive: '#443', ei: 0.3 }), -0.55, 0.62, 1.91, null, false)
  box(g, 0.3, 0.12, 0.04, M('#e8e0c0', { emissive: '#443', ei: 0.3 }), 0.55, 0.62, 1.91, null, false)
  return g
}

export function makeDecor(kind) {
  const g = new THREE.Group()
  switch (kind) {
    case 'bed': {
      box(g, 1.0, 0.35, 1.9, DWOOD(), 0, 0.2, 0)
      box(g, 0.95, 0.15, 1.8, M(pick(['#c8c0b0', '#8a9aa8', '#a88a7a'])), 0, 0.45, 0)
      box(g, 0.6, 0.1, 0.3, M('#e8e4dc'), 0, 0.55, -0.7)
      box(g, 1.0, 0.7, 0.08, DWOOD(), 0, 0.5, -0.95)
      break
    }
    case 'sofa': {
      const c = M(pick(['#5a4a3a', '#4a5a6a', '#6a3a3a', '#5a6a4a']))
      box(g, 1.8, 0.4, 0.8, c, 0, 0.3, 0)
      box(g, 1.8, 0.5, 0.2, c, 0, 0.6, -0.35)
      box(g, 0.2, 0.3, 0.8, c, -0.85, 0.6, 0)
      box(g, 0.2, 0.3, 0.8, c, 0.85, 0.6, 0)
      break
    }
    case 'table': {
      const w = M('#7a5a3c')
      box(g, 1.2, 0.06, 0.8, w, 0, 0.75, 0)
      for (const [x, z] of [[-0.5, -0.3], [0.5, -0.3], [-0.5, 0.3], [0.5, 0.3]]) box(g, 0.06, 0.75, 0.06, w, x, 0.375, z)
      for (const [x, z] of [[-0.35, 0.6], [0.35, -0.6]]) {
        box(g, 0.4, 0.05, 0.4, w, x, 0.45, z)
        box(g, 0.4, 0.45, 0.05, w, x, 0.7, z + (z > 0 ? 0.18 : -0.18))
      }
      break
    }
    case 'booth': {
      const r = M('#8a2a2a', { rough: 0.6 })
      box(g, 1.6, 0.45, 0.5, r, 0, 0.225, -0.6)
      box(g, 1.6, 0.6, 0.15, r, 0, 0.7, -0.8)
      box(g, 1.4, 0.06, 0.7, M('#d8d0c0'), 0, 0.75, 0)
      break
    }
  }
  return g
}

export function makeTree(dead = false, scale = 1) {
  const g = new THREE.Group()
  const bark = M('#4a3a2c', { rough: 1 })
  const h = 2.2 + Math.random() * 1.5
  cyl(g, 0.12, 0.2, h, 6, bark, 0, h / 2, 0)
  if (dead) {
    for (let i = 0; i < 4; i++) {
      const b = cyl(g, 0.04, 0.07, 1.2, 5, bark, 0, h * (0.55 + i * 0.1), 0)
      b.rotation.set(0.9, (i / 4) * Math.PI * 2 + Math.random(), 0)
      b.position.x += Math.sin(b.rotation.y) * 0.4
      b.position.z += Math.cos(b.rotation.y) * 0.4
    }
  } else {
    const leaf = M(pick(['#4f5e33', '#5b6538', '#46552f', '#66663a']), { flat: true, rough: 0.9 })
    for (let i = 0; i < 4; i++) {
      part(g, G('ico', 0.8 + Math.random() * 0.4, 0), leaf, (Math.random() - 0.5) * 0.9, h + (Math.random() - 0.3) * 0.8, (Math.random() - 0.5) * 0.9, [Math.random(), Math.random(), 0])
    }
  }
  g.scale.setScalar(scale)
  return g
}

export function makeRock(s = 1) {
  const g = new THREE.Group()
  part(g, G('dodeca', 0.4, 0), M('#7a7870', { flat: true }), 0, 0.15, 0, [Math.random(), Math.random(), Math.random()], [s * (1 + Math.random() * 0.5), s * 0.6, s])
  return g
}

export function makeLamp(on = false) {
  const g = new THREE.Group()
  cyl(g, 0.05, 0.07, 3.2, 6, DMETAL(), 0, 1.6, 0)
  box(g, 0.06, 0.06, 0.8, DMETAL(), 0, 3.15, 0.35)
  box(g, 0.25, 0.1, 0.35, M('#2a2a2a'), 0, 3.08, 0.7)
  const bulb = box(g, 0.2, 0.04, 0.28, M('#ffe8b0', { emissive: '#ffd080', ei: on ? 2 : 0 }), 0, 3.02, 0.7, null, false)
  bulb.userData.keep = true
  g.userData.bulb = bulb
  return g
}

export function makeSandbags(len = 2) {
  const g = new THREE.Group()
  const m = M('#9a8a64', { rough: 1 })
  for (let row = 0; row < 3; row++) {
    for (let i = 0; i < len * 2; i++) {
      part(g, G('capsule', 0.14, 0.28, 2, 6), m, -len / 2 + 0.25 + i * 0.5 + (row % 2) * 0.25 - 0.12, 0.14 + row * 0.24, 0, [0, 0, Math.PI / 2], [1, 1, 0.75])
    }
  }
  return g
}

export function makeBarrel(color = '#4a5a3a') {
  const g = new THREE.Group()
  const m = M(color, { metal: 0.3, rough: 0.6 })
  cyl(g, 0.3, 0.3, 0.9, 12, m, 0, 0.45, 0)
  for (const y of [0.2, 0.7]) cyl(g, 0.31, 0.31, 0.04, 12, DMETAL(), 0, y, 0, null, false)
  return g
}

// ---------------------------------------------------------------- stations
// Built facing +z, centred on the footprint. level changes the look.
export function makeStation(type, level = 1) {
  const g = new THREE.Group()
  const w = WOOD()
  const dw = DWOOD()
  const mt = METAL()
  const dm = DMETAL()
  const canvas = (c) => M(c, { map: 'canvas', rough: 1, side: THREE.DoubleSide })
  const anim = [] // { obj, kind }
  switch (type) {
    case 'campfire': {
      const stone = M('#6e6a62', { flat: true })
      for (let i = 0; i < 10; i++) {
        const a = (i / 10) * Math.PI * 2
        part(g, G('dodeca', 0.16, 0), stone, Math.cos(a) * 0.55, 0.08, Math.sin(a) * 0.55, [i, i * 2, 0])
      }
      for (let i = 0; i < 4; i++) cyl(g, 0.06, 0.06, 0.8, 6, dw, 0, 0.12, 0, [Math.PI / 2 - 0.25, (i * Math.PI) / 2, 0])
      const flame = new THREE.Group()
      flame.position.y = 0.2
      const fm = M('#ff9a30', { emissive: '#ff7a10', ei: 3, transparent: true, opacity: 0.9 })
      const fm2 = M('#ffd060', { emissive: '#ffc040', ei: 3, transparent: true, opacity: 0.9 })
      for (let i = 0; i < 3; i++) {
        const f = part(flame, G('cone', 0.2 - i * 0.04, 0.6 + i * 0.1, 6), i === 1 ? fm2 : fm, (i - 1) * 0.08, 0.3, (i % 2) * 0.06, null, null, false)
        f.userData.keep = true
      }
      g.add(flame)
      anim.push({ obj: flame, kind: 'flame' })
      // log benches
      for (const a of [0.4, 2.2, 4.0]) {
        const b = cyl(g, 0.16, 0.16, 1.2, 8, M('#6a4a32'), Math.cos(a) * 1.55, 0.16, Math.sin(a) * 1.55, [0, -a, Math.PI / 2])
        b.rotation.set(Math.PI / 2, 0, a + Math.PI / 2)
      }
      break
    }
    case 'bunkhouse': {
      if (level === 1) {
        // two canvas tents
        for (const x of [-0.75, 0.75]) {
          const t = new THREE.Group()
          t.position.x = x
          g.add(t)
          const c = canvas(x < 0 ? '#7a7a5a' : '#6a6e56')
          part(t, G('box', 0.05, 1.3, 2.4), c, -0.42, 0.55, 0, [0, 0, 0.62])
          part(t, G('box', 0.05, 1.3, 2.4), c, 0.42, 0.55, 0, [0, 0, -0.62])
          box(t, 0.9, 0.12, 0.05, dw, 0, 0.06, 1.2)
          box(t, 0.1, 0.9, 0.05, M('#3a3020'), 0, 0.45, 1.19, null, false)
        }
      } else {
        const wall = M(level === 2 ? '#7c5c3e' : '#6a6458')
        box(g, 2.8, 1.8, 2.6, wall, 0, 0.9, 0)
        const roof = M(level === 2 ? '#5a4a3a' : '#7a7e84', { metal: level === 3 ? 0.4 : 0, map: level === 3 ? 'corrugated' : null })
        part(g, G('box', 3.1, 0.08, 1.6), roof, 0, 2.15, 0.62, [0.55, 0, 0])
        part(g, G('box', 3.1, 0.08, 1.6), roof, 0, 2.15, -0.62, [-0.55, 0, 0])
        box(g, 2.8, 0.5, 0.05, wall, 0, 2.0, 1.28)
        box(g, 0.6, 1.2, 0.06, dw, 0, 0.6, 1.31)
        box(g, 0.5, 0.4, 0.06, M('#ffd890', { emissive: '#ffb050', ei: 0.4 }), 0.9, 1.2, 1.31, null, false)
        box(g, 0.5, 0.4, 0.06, M('#ffd890', { emissive: '#ffb050', ei: 0.4 }), -0.9, 1.2, 1.31, null, false)
      }
      break
    }
    case 'storage': {
      for (const x of [-1.3, 1.3]) for (const z of [-0.8, 0.8]) cyl(g, 0.05, 0.05, 2, 6, dw, x, 1, z)
      part(g, G('box', 2.9, 0.05, 2.0), canvas(level > 1 ? '#3f5a6a' : '#6a6a50'), 0, 2.0, 0, [0.1, 0, 0])
      for (let i = 0; i < 4 + level * 2; i++) {
        const x = -1.0 + (i % 4) * 0.66
        const z = i < 4 ? -0.45 : 0.4
        const y = i >= 8 ? 0.85 : 0.3
        if (i % 3 === 0) part(g, G('cyl', 0.28, 0.28, 0.8, 10), M(pick(['#4a5a3a', '#6a3a2a', '#3a4a5a'])), x, 0.4, z)
        else part(g, G('box', 0.55, 0.55, 0.55), M('#a07c52', { map: 'crate' }), x, y, z, [0, Math.random() * 0.4, 0])
      }
      break
    }
    case 'kitchen': {
      for (const x of [-1.3, 1.3]) for (const z of [-0.8, 0.8]) cyl(g, 0.05, 0.05, 2.1, 6, dw, x, 1.05, z)
      part(g, G('box', 2.9, 0.05, 2.0), canvas('#6a5a40'), 0, 2.1, 0, [0.08, 0, 0])
      box(g, 1.2, 0.85, 0.6, M('#6a6660', { metal: 0.3 }), -0.6, 0.425, -0.5)
      cyl(g, 0.25, 0.22, 0.4, 12, mt, -0.8, 1.05, -0.5)
      cyl(g, 0.18, 0.18, 0.2, 12, M('#2a2a2a'), -0.3, 0.95, -0.5)
      box(g, 1.6, 0.06, 0.7, w, 0.3, 0.75, 0.45)
      for (const [x, z] of [[-0.4, 0.2], [1.0, 0.2], [-0.4, 0.7], [1.0, 0.7]]) box(g, 0.06, 0.75, 0.06, w, x, 0.375, z)
      const smoke = new THREE.Group()
      smoke.position.set(-0.8, 1.4, -0.5)
      g.add(smoke)
      anim.push({ obj: smoke, kind: 'steam' })
      break
    }
    case 'infirmary': {
      const c = canvas('#d8d4c8')
      part(g, G('box', 2.9, 0.05, 1.3), c, 0, 1.75, 0.5, [0.45, 0, 0])
      part(g, G('box', 2.9, 0.05, 1.3), c, 0, 1.75, -0.5, [-0.45, 0, 0])
      box(g, 0.05, 1.4, 2.0, c, -1.45, 0.9, 0)
      box(g, 0.05, 1.4, 2.0, c, 1.45, 0.9, 0)
      box(g, 0.5, 0.14, 0.02, M('#c8323a'), 0, 1.75, 1.08, [0.45, 0, 0], false)
      box(g, 0.14, 0.5, 0.02, M('#c8323a'), 0, 1.75, 1.08, [0.45, 0, 0], false)
      for (let i = 0; i < Math.min(level + 1, 3); i++) {
        const x = -0.9 + i * 0.9
        box(g, 0.6, 0.06, 1.4, mt, x, 0.45, 0)
        box(g, 0.55, 0.08, 1.35, M('#e8e8e0'), x, 0.52, 0)
        for (const [dx, dz] of [[-0.25, -0.6], [0.25, -0.6], [-0.25, 0.6], [0.25, 0.6]]) box(g, 0.04, 0.45, 0.04, mt, x + dx, 0.22, dz)
      }
      break
    }
    case 'training': {
      for (let i = 0; i < level + 1; i++) {
        const x = -1 + i * (2 / Math.max(1, level))
        const d = new THREE.Group()
        d.position.set(x, 0, -0.6)
        g.add(d)
        cyl(d, 0.05, 0.05, 1.5, 6, dw, 0, 0.75, 0)
        part(d, G('capsule', 0.2, 0.5, 3, 8), M('#b8a070', { map: 'canvas' }), 0, 1.2, 0)
        part(d, G('sphere', 0.14, 8, 6), M('#b8a070', { map: 'canvas' }), 0, 1.75, 0)
        box(d, 0.8, 0.06, 0.06, dw, 0, 1.35, 0)
      }
      // target board
      cyl(g, 0.4, 0.4, 0.06, 16, M('#e8e0d0'), 1.1, 1.0, 0.9, [Math.PI / 2, 0, 0])
      cyl(g, 0.25, 0.25, 0.07, 16, M('#c8323a'), 1.1, 1.0, 0.9, [Math.PI / 2, 0, 0], false)
      box(g, 0.06, 1.0, 0.06, dw, 1.1, 0.5, 0.85)
      g.add(makeSandbags(1)).position.set(-0.8, 0, 1.0)
      break
    }
    case 'radio': {
      const h = 5 + level * 1.5
      const legs = [[-0.6, -0.6], [0.6, -0.6], [-0.6, 0.6], [0.6, 0.6]]
      for (const [x, z] of legs) {
        const l = cyl(g, 0.04, 0.04, h, 4, dm, x * 0.6, h / 2, z * 0.6)
        l.rotation.set(-z * 0.08, 0, x * 0.08)
      }
      for (let y = 0.6; y < h; y += 0.9) {
        const s = 0.72 * (1 - (y / h) * 0.6)
        box(g, s * 2, 0.03, 0.03, dm, 0, y, s)
        box(g, s * 2, 0.03, 0.03, dm, 0, y, -s)
        box(g, 0.03, 0.03, s * 2, dm, s, y, 0)
        box(g, 0.03, 0.03, s * 2, dm, -s, y, 0)
      }
      cyl(g, 0.02, 0.02, 1.5, 4, mt, 0, h + 0.75, 0)
      const blink = part(g, G('sphere', 0.08, 8, 6), M('#ff3020', { emissive: '#ff2010', ei: 3 }), 0, h + 1.5, 0, null, null, false)
      blink.userData.keep = true
      anim.push({ obj: blink, kind: 'blink' })
      box(g, 0.7, 0.9, 0.6, M('#5a6048'), 0.7, 0.45, 0.6)
      part(g, G('cyl', 0.35, 0.35, 0.05, 12), mt, -0.5, h * 0.7, 0.4, [0.8, 0, 0.3])
      break
    }
    case 'farm': {
      const soil = M('#4a3a28', { rough: 1 })
      const leaf = M('#5f8a3a', { flat: true })
      const leaf2 = M('#7a9a40', { flat: true })
      for (let r = 0; r < 3; r++) {
        const z = -1 + r
        box(g, 2.6, 0.25, 0.7, dw, 0, 0.12, z)
        box(g, 2.5, 0.05, 0.62, soil, 0, 0.26, z, null, false)
        for (let i = 0; i < 6; i++) {
          const plant = new THREE.Group()
          plant.position.set(-1.05 + i * 0.42, 0.28, z)
          g.add(plant)
          const tall = r === 1
          part(plant, G('cone', tall ? 0.12 : 0.16, tall ? 0.7 : 0.35, 5), i % 2 ? leaf : leaf2, 0, tall ? 0.35 : 0.17, 0, [0, i, 0])
          if (!tall) part(plant, G('sphere', 0.07, 6, 4), M('#c8452e'), 0.06, 0.18, 0.04, null, null, false)
          anim.push({ obj: plant, kind: 'grow' })
        }
      }
      if (level >= 3) {
        cyl(g, 0.03, 0.03, 2.8, 6, mt, 0, 0.6, 1.4, [0, 0, Math.PI / 2])
        for (const x of [-1.2, 0, 1.2]) cyl(g, 0.02, 0.02, 0.5, 6, mt, x, 0.35, 1.4)
      }
      break
    }
    case 'collector': {
      const n = 1 + level
      for (let i = 0; i < n; i++) {
        const b = makeBarrel(pick(['#3a5a6a', '#4a6a7a', '#2f4f5f']))
        b.position.set(-0.45 + (i % 2) * 0.9, 0, -0.45 + Math.floor(i / 2) * 0.9)
        g.add(b)
      }
      for (const [x, z] of [[-0.9, -0.9], [0.9, -0.9], [-0.9, 0.9], [0.9, 0.9]]) cyl(g, 0.04, 0.04, 1.8, 6, dw, x, 0.9, z)
      part(g, G('cone', 1.3, 0.5, 4, 1, true), canvas('#4a6a7a'), 0, 1.6, 0, [Math.PI, Math.PI / 4, 0])
      break
    }
    case 'filter': {
      cyl(g, 0.45, 0.45, 1.4, 14, M('#8aa0a8', { metal: 0.4, rough: 0.4 }), -0.3, 0.7, -0.2)
      cyl(g, 0.47, 0.47, 0.06, 14, dm, -0.3, 1.4, -0.2)
      cyl(g, 0.3, 0.3, 0.9, 12, M('#5a7a88'), 0.5, 0.45, 0.3)
      cyl(g, 0.05, 0.05, 1.0, 6, mt, 0.1, 1.1, 0.05, [0, 0, 1.2])
      box(g, 0.3, 0.5, 0.3, M('#3a3e42'), 0.55, 0.25, -0.5)
      // hand pump
      const arm = new THREE.Group()
      arm.position.set(0.55, 0.55, -0.5)
      g.add(arm)
      box(arm, 0.05, 0.05, 0.6, dm, 0, 0, 0.2)
      anim.push({ obj: arm, kind: 'pump' })
      if (level >= 3) box(g, 0.4, 0.3, 0.3, M('#c8a030', { metal: 0.3 }), -0.3, 0.15, 0.55)
      break
    }
    case 'lumber': {
      for (let i = 0; i < 5; i++) cyl(g, 0.18, 0.18, 2.4, 8, M('#6a4c30'), -0.3 + (i % 3) * 0.37, 0.18 + Math.floor(i / 3) * 0.32, -0.8, [0, 0, Math.PI / 2])
      box(g, 1.4, 0.8, 0.6, dw, 0.5, 0.4, 0.5)
      for (let i = 0; i < 4 + level; i++) box(g, 1.2, 0.06, 0.18, M('#b08a5a'), -0.8, 0.05 + i * 0.07, 0.6, [0, 0.05 * i, 0])
      const blade = part(g, G('cyl', 0.3, 0.3, 0.02, 16), M('#c0c4c8', { metal: 0.8, rough: 0.3 }), 0.5, 0.85, 0.5, [0, 0, Math.PI / 2], null, false)
      blade.userData.keep = true
      anim.push({ obj: blade, kind: 'saw' })
      part(g, G('cyl', 0.35, 0.4, 0.5, 10), M('#6a4c30'), 1.1, 0.25, -0.2)
      break
    }
    case 'scrapyard': {
      const car = makeCar('#6a5040')
      car.rotation.y = 0.3
      car.scale.setScalar(0.62)
      car.position.set(-0.3, 0, -0.3)
      g.add(car)
      for (let i = 0; i < 9; i++) {
        part(g, G('box', 0.3 + Math.random() * 0.4, 0.2 + Math.random() * 0.3, 0.3 + Math.random() * 0.3), pick([RUST(), dm, mt]), 0.6 + Math.random() * 0.8, 0.15, 0.4 + Math.random() * 0.8, [Math.random(), Math.random(), Math.random()])
      }
      box(g, 0.9, 0.8, 0.5, M('#5a5850', { metal: 0.3 }), 1.0, 0.4, -1.0)
      break
    }
    case 'still': {
      const copper = M('#b8703a', { metal: 0.7, rough: 0.35 })
      cyl(g, 0.45, 0.5, 1.0, 14, copper, -0.3, 0.6, -0.2)
      part(g, G('sphere', 0.45, 14, 8, 0, Math.PI * 2, 0, Math.PI / 2), copper, -0.3, 1.1, -0.2)
      cyl(g, 0.04, 0.04, 1.1, 6, copper, 0.25, 1.35, -0.2, [0, 0, Math.PI / 2 - 0.3])
      cyl(g, 0.25, 0.25, 0.7, 12, dm, 0.6, 0.35, 0.2)
      box(g, 0.9, 0.12, 0.9, M('#3a3a3a'), -0.3, 0.06, -0.2)
      const fire = part(g, G('box', 0.5, 0.06, 0.5), M('#ff7a20', { emissive: '#ff5a10', ei: 2 }), -0.3, 0.12, -0.2, null, null, false)
      fire.userData.keep = true
      anim.push({ obj: fire, kind: 'glow' })
      g.add(makeBarrel('#8a3a2a')).position.set(0.5, 0, -0.6)
      break
    }
    case 'generator': {
      const body = M(level === 1 ? '#c8a030' : level === 2 ? '#3a6a8a' : '#6a7a4a', { metal: 0.4, rough: 0.5 })
      box(g, 1.3, 0.8, 0.8, body, 0, 0.55, 0)
      box(g, 1.4, 0.15, 0.9, dm, 0, 0.1, 0)
      for (let i = 0; i < 5; i++) box(g, 0.02, 0.4, 0.6, dm, -0.4 + i * 0.2, 0.55, 0.41, null, false)
      cyl(g, 0.06, 0.06, 0.8, 8, dm, 0.45, 1.3, -0.2)
      const lamp = part(g, G('sphere', 0.05, 6, 4), M('#40ff60', { emissive: '#20ff40', ei: 2 }), -0.5, 1.0, 0.4, null, null, false)
      lamp.userData.keep = true
      anim.push({ obj: lamp, kind: 'blink' })
      const exhaust = new THREE.Group()
      exhaust.position.set(0.45, 1.75, -0.2)
      g.add(exhaust)
      anim.push({ obj: exhaust, kind: 'smoke' })
      g.add(makeBarrel('#8a3a2a')).position.set(-0.6, 0, -0.65)
      g.add(makeBarrel('#8a3a2a')).position.set(0.65, 0, 0.6)
      break
    }
    case 'workbench': {
      box(g, 2.4, 0.08, 0.9, w, 0, 0.9, 0)
      for (const [x, z] of [[-1.1, -0.35], [1.1, -0.35], [-1.1, 0.35], [1.1, 0.35]]) box(g, 0.08, 0.9, 0.08, dw, x, 0.45, z)
      box(g, 2.2, 0.05, 0.8, dw, 0, 0.3, 0)
      box(g, 2.4, 1.2, 0.05, M('#9a7a52'), 0, 1.5, -0.45)
      for (let i = 0; i < 6; i++) box(g, 0.05, 0.3, 0.03, i % 2 ? mt : M('#c9442e'), -0.9 + i * 0.35, 1.6, -0.42, null, false)
      box(g, 0.25, 0.15, 0.2, dm, 0.8, 1.02, 0.2)
      box(g, 0.5, 0.1, 0.3, M('#a07a50'), -0.3, 0.99, 0.1, [0, 0.3, 0])
      if (level >= 2) box(g, 0.4, 0.3, 0.4, M('#3a6a4a', { metal: 0.3 }), -0.8, 1.09, 0.15)
      break
    }
    case 'weapons': {
      box(g, 2.4, 0.08, 0.9, M('#5a4a3a'), 0, 0.9, 0)
      for (const [x, z] of [[-1.1, -0.35], [1.1, -0.35], [-1.1, 0.35], [1.1, 0.35]]) box(g, 0.08, 0.9, 0.08, dm, x, 0.45, z)
      box(g, 2.4, 1.3, 0.08, M('#3a3a34'), 0, 1.55, -0.45)
      for (let i = 0; i < 3; i++) {
        const gm = makeWeapon(pick(['rifle', 'shotgun', 'smg']))
        gm.rotation.set(0, Math.PI / 2, Math.PI / 2)
        gm.position.set(-0.6 + i * 0.6, 1.8 - (i % 2) * 0.35, -0.38)
        g.add(gm)
      }
      const pg = makeWeapon('pistol')
      pg.rotation.set(Math.PI / 2, 0, 0.4)
      pg.position.set(0.3, 0.96, 0.1)
      g.add(pg)
      box(g, 0.3, 0.2, 0.3, M('#6a6a2a'), -0.7, 1.04, 0.15)
      break
    }
    case 'clothing': {
      box(g, 1.6, 0.06, 0.8, w, -0.5, 0.8, 0)
      for (const [x, z] of [[-1.2, -0.3], [0.2, -0.3], [-1.2, 0.3], [0.2, 0.3]]) box(g, 0.06, 0.8, 0.06, dw, x, 0.4, z)
      box(g, 0.3, 0.25, 0.2, M('#2a2a2a'), -0.6, 0.95, 0)
      for (let i = 0; i < 3; i++) box(g, 0.5, 0.02, 0.4, M(pick(['#6a4a3a', '#3a4a5a', '#5a5a3a', '#8a7a5a'])), -0.1, 0.84 + i * 0.02, 0.05, [0, i * 0.3, 0], false)
      // mannequin with a vest
      const man = new THREE.Group()
      man.position.set(0.95, 0, -0.1)
      g.add(man)
      cyl(man, 0.03, 0.03, 1.0, 6, dm, 0, 0.5, 0)
      part(man, G('capsule', 0.17, 0.3, 3, 8), M('#d8c8a8'), 0, 1.25, 0, null, [1, 1, 0.7])
      box(man, 0.38, 0.36, 0.26, M(level >= 2 ? '#2f3a2e' : '#4a3526'), 0, 1.28, 0)
      for (let i = 0; i < 4; i++) cyl(g, 0.12, 0.12, 0.3, 10, M(pick(['#8a2a2a', '#2a4a6a', '#c8b88a', '#4a6a3a'])), -1.1 + i * 0.3, 0.15, 0.7, [Math.PI / 2, 0, 0])
      break
    }
    case 'ammo': {
      box(g, 1.2, 0.9, 0.9, M('#5a5e62', { metal: 0.5, rough: 0.5 }), -0.2, 0.45, -0.1)
      cyl(g, 0.08, 0.08, 1.2, 8, dm, -0.6, 1.4, -0.1)
      cyl(g, 0.08, 0.08, 1.2, 8, dm, 0.2, 1.4, -0.1)
      box(g, 1.0, 0.15, 0.3, dm, -0.2, 2.0, -0.1)
      const ram = new THREE.Group()
      ram.position.set(-0.2, 1.5, -0.1)
      g.add(ram)
      box(ram, 0.3, 0.5, 0.3, M('#b8a040', { metal: 0.6, rough: 0.3 }), 0, 0, 0)
      anim.push({ obj: ram, kind: 'press' })
      for (let i = 0; i < 3; i++) box(g, 0.35, 0.22, 0.25, M('#4a5a3a'), 0.6, 0.11 + i * 0.23, 0.4)
      break
    }
    case 'watchtower': {
      const h = 2.6 + level * 0.5
      const post = level === 3 ? dm : dw
      for (const [x, z] of [[-0.75, -0.75], [0.75, -0.75], [-0.75, 0.75], [0.75, 0.75]]) cyl(g, 0.08, 0.1, h + 1.1, 6, post, x, (h + 1.1) / 2, z)
      box(g, 1.9, 0.12, 1.9, level === 3 ? mt : w, 0, h, 0)
      for (const [x, z, ry] of [[0, 0.92, 0], [0, -0.92, 0], [0.92, 0, Math.PI / 2], [-0.92, 0, Math.PI / 2]]) box(g, 1.9, 0.6, 0.06, level >= 2 ? M('#7a6a4a') : w, x, h + 0.35, z, [0, ry, 0])
      part(g, G('cone', 1.5, 0.7, 4), level === 3 ? M('#6a6e72', { metal: 0.4 }) : M('#5a4a3a'), 0, h + 1.5, 0, [0, Math.PI / 4, 0])
      for (let y = 0.3; y < h; y += 0.35) box(g, 0.5, 0.04, 0.04, dw, 0, y, 0.95)
      g.userData.platformY = h + 0.06
      break
    }
    case 'turret': {
      box(g, 0.8, 0.5, 0.8, M('#4a5040', { metal: 0.4 }), 0, 0.25, 0)
      const head = new THREE.Group()
      head.position.y = 0.75
      g.add(head)
      box(head, 0.5, 0.35, 0.6, M('#5a6048', { metal: 0.5, rough: 0.4 }), 0, 0, 0)
      for (const x of [-0.1, 0.1]) cyl(head, 0.04, 0.04, 0.7, 8, M('#1a1a1a', { metal: 0.6 }), x, 0.02, 0.55, [Math.PI / 2, 0, 0])
      const eye = part(head, G('sphere', 0.05, 6, 4), M('#ff4020', { emissive: '#ff2010', ei: 2 }), 0, 0.12, 0.3, null, null, false)
      eye.userData.keep = true
      head.traverse((o) => (o.userData.keep = true))
      g.userData.head = head
      g.add(makeSandbags(1)).position.set(0, 0, 0.55)
      break
    }
    case 'floodlight': {
      cyl(g, 0.05, 0.07, 3.4, 6, dm, 0, 1.7, 0)
      box(g, 0.9, 0.06, 0.06, dm, 0, 3.4, 0)
      for (const x of [-0.3, 0.3]) {
        const l = box(g, 0.28, 0.22, 0.2, M('#fff4d0', { emissive: '#fff0c0', ei: 0 }), x, 3.5, 0.1, [0.4, 0, 0], false)
        l.userData.keep = true
        l.userData.bulb = true
      }
      break
    }
  }
  g.userData.anim = anim
  return g
}

// Construction scaffold shown while a station is being built.
export function makeScaffold(w, d) {
  const g = new THREE.Group()
  const pole = M('#a08058')
  const h = 1.8
  for (const x of [-w / 2 + 0.1, w / 2 - 0.1]) for (const z of [-d / 2 + 0.1, d / 2 - 0.1]) cyl(g, 0.04, 0.04, h, 5, pole, x, h / 2, z)
  for (const y of [0.7, 1.6]) {
    box(g, w - 0.2, 0.04, 0.04, pole, 0, y, d / 2 - 0.1)
    box(g, w - 0.2, 0.04, 0.04, pole, 0, y, -d / 2 + 0.1)
    box(g, 0.04, 0.04, d - 0.2, pole, w / 2 - 0.1, y, 0)
    box(g, 0.04, 0.04, d - 0.2, pole, -w / 2 + 0.1, y, 0)
  }
  box(g, w - 0.4, 0.3, 0.4, M('#a78455', { map: 'crate' }), 0, 0.15, 0)
  const hazard = M('#e8a33d')
  box(g, 0.5, 0.5, 0.05, hazard, w / 2 - 0.35, 0.5, d / 2, null, false)
  return g
}

// Per-frame station animation (flames, blinking lights, saws…).
export function animateStation(g, t, active, dt) {
  for (const a of g.userData.anim || []) {
    const o = a.obj
    switch (a.kind) {
      case 'flame':
        o.scale.set(1 + Math.sin(t * 13) * 0.1, 0.85 + Math.sin(t * 9) * 0.2 + Math.sin(t * 23) * 0.08, 1 + Math.cos(t * 11) * 0.1)
        o.rotation.y = t * 1.5
        break
      case 'blink':
        o.visible = active && Math.sin(t * 4) > 0
        break
      case 'saw':
        if (active) o.rotation.x += dt * 20
        break
      case 'pump':
        o.rotation.x = active ? Math.sin(t * 4) * 0.4 : 0
        break
      case 'press':
        o.position.y = active ? 1.4 + Math.abs(Math.sin(t * 3)) * 0.3 : 1.7
        break
      case 'glow':
        o.material.emissiveIntensity = active ? 1.5 + Math.sin(t * 7) * 0.5 : 0
        break
      case 'grow':
        break
      case 'steam':
      case 'smoke':
        break
    }
  }
}

// Make a quick default survivor look.
export function randomLook(rng = Math.random) {
  const p = (a) => a[Math.floor(rng() * a.length)]
  return {
    skin: p(['#f1c9a5', '#e0ac85', '#c68b62', '#a86b45', '#7d4a2e', '#5a3421']),
    hair: p(['#1c1714', '#3b2a1e', '#6b4a2b', '#a57b45', '#d8b67a', '#8c8c8c', '#b3432e']),
    shirt: p(['#5a6b4a', '#6d5a44', '#3f5566', '#7a3b33', '#8a7a5a', '#4a4f57', '#6b6f3a', '#34464a', '#8c5a2e', '#57466b']),
    pants: p(['#2f3640', '#3d3a33', '#4a4234', '#2b3a33', '#51493d', '#1f2a36']),
    hairStyle: Math.floor(rng() * 5),
    hat: p(['#5a5f3a', '#6a3a2a', '#2a3a4a', '#8a7a5a']),
    bulk: 0.95 + rng() * 0.15,
  }
}
export function zombieLook(type) {
  const skin = pick(['#8a9a7a', '#7a8a70', '#9aa088', '#6e7a64', '#a0a08a'])
  return {
    skin,
    hair: pick(['#2a2622', '#3a3028', '#4a4a44', '#5a4a3a']),
    shirt: pick(['#4a4038', '#3a4048', '#5a3a30', '#4a4a3a', '#6a6050', '#3a3a3a']),
    pants: pick(['#2a2a2a', '#3a3428', '#2a3038', '#403a30']),
    hairStyle: pick([0, 1, 4, 4]),
    bulk: type === 'brute' ? 1.35 : type === 'runner' ? 0.85 : 1,
  }
}
