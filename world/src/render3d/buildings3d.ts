// Building models. Each type is assembled from lit primitives — walls, gabled
// and thatched roofs, columns, chimneys, domes, glass towers — in materials
// that follow the tribe's era (timber and thatch, then stone and terracotta,
// then brick, then concrete and glass) with roofs and banners in the tribe's
// colour. Static parts are merged into one mesh per model and shared; windows
// are a second, emissive mesh that glows at night. Fires flicker, mill sails
// turn, chimneys smoke, herds wander their pastures.

import * as THREE from 'three'
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js'
import type { BuildingType } from '../sim/buildings.ts'
import { BUILDINGS } from '../sim/buildings.ts'
import type { Building } from '../sim/types.ts'
import { hash2 } from '../sim/rng.ts'

// ---------------------------------------------------------------- kit

class Kit {
  parts: THREE.BufferGeometry[] = []
  glow: THREE.BufferGeometry[] = []
  private seed = 1
  private add(g: THREE.BufferGeometry, color: string, list = this.parts, jitter = 0.05) {
    const ng = g.index ? g.toNonIndexed() : g
    ng.deleteAttribute('uv')
    const n = ng.getAttribute('position').count
    const c = new THREE.Color(color)
    const arr = new Float32Array(n * 3)
    this.seed++
    for (let k = 0; k < n; k += 3) {
      const v = 1 + (hash2(k, this.seed, 4) - 0.5) * jitter * 2
      for (let q = 0; q < 3 && k + q < n; q++) { arr[(k + q) * 3] = c.r * v; arr[(k + q) * 3 + 1] = c.g * v; arr[(k + q) * 3 + 2] = c.b * v }
    }
    ng.setAttribute('color', new THREE.BufferAttribute(arr, 3))
    list.push(ng)
    return ng
  }
  box(w: number, h: number, d: number, x: number, y: number, z: number, c: string, ry = 0, rx = 0, rz = 0) {
    const g = new THREE.BoxGeometry(w, h, d)
    if (rx) g.rotateX(rx)
    if (rz) g.rotateZ(rz)
    if (ry) g.rotateY(ry)
    g.translate(x, y + h / 2, z)
    return this.add(g, c)
  }
  cyl(rt: number, rb: number, h: number, x: number, y: number, z: number, c: string, seg = 10, open = false) {
    const g = new THREE.CylinderGeometry(rt, rb, h, seg, 1, open)
    g.translate(x, y + h / 2, z)
    return this.add(g, c)
  }
  cone(r: number, h: number, x: number, y: number, z: number, c: string, seg = 10, ry = 0) {
    const g = new THREE.ConeGeometry(r, h, seg)
    if (ry) g.rotateY(ry)
    g.translate(x, y + h / 2, z)
    return this.add(g, c)
  }
  sph(r: number, x: number, y: number, z: number, c: string, sy = 1, half = false, seg = 12) {
    const g = half ? new THREE.SphereGeometry(r, seg, Math.ceil(seg / 2), 0, Math.PI * 2, 0, Math.PI / 2) : new THREE.SphereGeometry(r, seg, Math.ceil(seg * 0.7))
    g.scale(1, sy, 1)
    g.translate(x, y, z)
    return this.add(g, c)
  }
  /** A gabled roof: ridge along X, eaves overhanging the walls. */
  gable(w: number, d: number, h: number, x: number, y: number, z: number, c: string, ry = 0) {
    const s = new THREE.Shape([new THREE.Vector2(-d / 2, 0), new THREE.Vector2(d / 2, 0), new THREE.Vector2(0, h)])
    const g = new THREE.ExtrudeGeometry(s, { depth: w, bevelEnabled: false })
    g.translate(0, 0, -w / 2)
    g.rotateY(Math.PI / 2)
    if (ry) g.rotateY(ry)
    g.translate(x, y, z)
    return this.add(g, c, this.parts, 0.03)
  }
  /** A four-sided pyramid / hipped roof. */
  hip(w: number, d: number, h: number, x: number, y: number, z: number, c: string) {
    const g = new THREE.ConeGeometry(Math.SQRT1_2, 1, 4)
    g.rotateY(Math.PI / 4)
    g.scale(w, h, d)
    g.translate(x, y + h / 2, z)
    return this.add(g, c, this.parts, 0.03)
  }
  /** A lit window pane (glows at night). Face: 0=+z (front), 1=+x, 2=-z, 3=-x. */
  win(w: number, h: number, x: number, y: number, z: number, face = 0) {
    const g = new THREE.PlaneGeometry(w, h)
    g.rotateY([0, Math.PI / 2, Math.PI, -Math.PI / 2][face])
    const off = 0.006
    const [ox, oz] = [[0, off], [off, 0], [0, -off], [-off, 0]][face]
    g.translate(x + ox, y + h / 2, z + oz)
    return this.add(g, '#ffffff', this.glow, 0)
  }
  /** A ring of windows around a box's four faces. */
  winRow(w: number, d: number, cx: number, cz: number, y: number, n: number, ww = 0.08, wh = 0.1, faces = [0, 1, 2, 3]) {
    for (const f of faces) {
      const len = f % 2 ? d : w
      for (let k = 0; k < n; k++) {
        const t = (k + 0.5) / n - 0.5
        if (f === 0) this.win(ww, wh, cx + t * len, y, cz + d / 2, 0)
        if (f === 2) this.win(ww, wh, cx - t * len, y, cz - d / 2, 2)
        if (f === 1) this.win(ww, wh, cx + w / 2, y, cz - t * len, 1)
        if (f === 3) this.win(ww, wh, cx - w / 2, y, cz + t * len, 3)
      }
    }
  }
  door(x: number, y: number, z: number, c = '#3a2a1c', w = 0.12, h = 0.2) {
    // a doorway a person can walk through (~2.1 m)
    this.box(Math.max(w * 1.2, 0.14), Math.max(h * 1.45, 0.34), 0.02, x, y, z, c)
  }
}

// ---------------------------------------------------------------- palettes

interface Pal { wall: string; wall2: string; roof: string; trim: string; wood: string; stone: string; dark: string }
function palette(era: number, tribe: string): Pal {
  const t = new THREE.Color(tribe)
  const roofTint = (base: string, k: number) => '#' + new THREE.Color(base).lerp(t, k).getHexString()
  if (era <= 1) return { wall: '#8b6a45', wall2: '#a07c52', roof: roofTint('#c9a55a', 0.15), trim: tribe, wood: '#6b4a2e', stone: '#8d8a84', dark: '#3a2a1c' }
  if (era <= 3) return { wall: '#d8c7a4', wall2: '#c9b48a', roof: roofTint('#b4573a', 0.35), trim: tribe, wood: '#6b4a2e', stone: '#b9b2a2', dark: '#3a2a1c' }
  if (era <= 5) return { wall: '#e6dcc6', wall2: '#a8562f', roof: roofTint('#5a4a48', 0.45), trim: tribe, wood: '#5a3d26', stone: '#a8a197', dark: '#2a201a' }
  if (era <= 7) return { wall: '#a8563a', wall2: '#c9c2b6', roof: roofTint('#454a52', 0.35), trim: tribe, wood: '#5a3d26', stone: '#9a968f', dark: '#26262a' }
  return { wall: '#d9dde2', wall2: '#8fa4b8', roof: roofTint('#3a4048', 0.3), trim: tribe, wood: '#5a3d26', stone: '#b4b8bd', dark: '#202428' }
}

// ---------------------------------------------------------------- models

export interface ModelInfo {
  body: THREE.BufferGeometry
  glow: THREE.BufferGeometry | null
  /** chimney / fire smoke sources in model space */
  smoke: [number, number, number, number][] // x, y, z, kind (0 smoke, 1 sparks, 2 steam)
  /** a light source in model space: x, y, z, radius, warmth */
  light: [number, number, number, number, number] | null
  anim: 'fire' | 'mill' | 'herd' | 'crops' | 'rocket' | 'beacon' | 'pump' | 'wind' | 'wheel' | 'flare' | null
  /** where a moving part sits (pumpjack pivot, rotor hub, winding wheel) */
  pivot?: [number, number, number]
  height: number
}

function build(type: BuildingType, era: number, tribe: string, v: number): ModelInfo {
  const k = new Kit()
  const P = palette(era, tribe)
  const s = BUILDINGS[type].size
  const c = s / 2
  const smoke: ModelInfo['smoke'] = []
  let light: ModelInfo['light'] = null
  let anim: ModelInfo['anim'] = null
  let height = 1
  let pivot: [number, number, number] | undefined
  // a plinth that buries the base into sloping ground
  const plinth = (w = s * 0.92, col = era <= 1 ? '#6e5a40' : P.stone) => k.box(w, 0.5, w, c, -0.46, c, col)

  switch (type) {
    case 'campfire': {
      for (let i = 0; i < 9; i++) { const a = (i / 9) * Math.PI * 2; k.sph(0.07, c + Math.cos(a) * 0.2, 0.03, c + Math.sin(a) * 0.2, '#7d7a74', 0.7, false, 6) }
      k.box(0.36, 0.05, 0.06, c, 0.02, c, '#5a3d26', 0.6)
      k.box(0.36, 0.05, 0.06, c, 0.04, c, '#4d3420', -0.6)
      k.box(0.3, 0.05, 0.06, c, 0.06, c, '#5a3d26', 1.6)
      // seats around the fire
      k.box(0.35, 0.08, 0.1, c, 0, c + 0.42, '#6b4a2e', 0.2)
      k.box(0.35, 0.08, 0.1, c - 0.4, 0, c - 0.1, '#6b4a2e', 1.3)
      smoke.push([c, 0.35, c, 1])
      light = [c, 0.3, c, 6, 1]
      anim = 'fire'
      height = 0.4
      break
    }
    case 'lean_to': {
      k.box(0.05, 0.42, 0.05, c - 0.3, 0, c + 0.15, P.wood)
      k.box(0.05, 0.42, 0.05, c + 0.3, 0, c + 0.15, P.wood)
      k.box(0.7, 0.04, 0.05, c, 0.4, c + 0.15, P.wood)
      for (let i = 0; i < 7; i++) k.box(0.04, 0.03, 0.62, c - 0.3 + i * 0.1, 0.18, c - 0.08, i % 2 ? '#7a5a36' : '#86663f', 0, -0.62)
      k.box(0.72, 0.03, 0.6, c, 0.22, c - 0.08, '#8f9a4a', 0, -0.62)
      k.box(0.4, 0.03, 0.25, c, 0, c - 0.05, '#a08a5a')
      height = 0.5
      break
    }
    case 'hut': {
      plinth(0.8, '#6e5a40')
      if (era <= 1) {
        k.cyl(0.34, 0.36, 0.46, c, 0, c, P.wall, 12)
        k.cone(0.5, 0.48, c, 0.44, c, P.roof, 12)
        k.cone(0.1, 0.12, c, 0.86, c, '#a8874a', 8)
        k.door(c, 0, c + 0.345, '#2a1d12', 0.14, 0.24)
        k.win(0.07, 0.07, c + 0.2, 0.22, c + 0.27, 0)
        smoke.push([c + 0.05, 0.9, c, 0])
      } else {
        k.box(0.62, 0.42, 0.55, c, 0, c, P.wall)
        k.gable(0.74, 0.7, 0.36, c, 0.42, c, P.roof)
        k.door(c - 0.1, 0, c + 0.276)
        k.win(0.09, 0.09, c + 0.15, 0.2, c + 0.276, 0)
        k.win(0.09, 0.09, c + 0.311, 0.2, c, 1)
        k.box(0.08, 0.3, 0.08, c + 0.2, 0.5, c - 0.12, P.stone)
        smoke.push([c + 0.2, 0.85, c - 0.12, 0])
      }
      light = [c, 0.3, c + 0.4, 2.2, 0.6]
      height = 0.9
      break
    }
    case 'house': {
      plinth(0.86)
      const tall = era >= 4
      const h = tall ? 0.62 : 0.48
      k.box(0.7, h, 0.6, c, 0, c, P.wall)
      if (era >= 4) { k.box(0.72, 0.04, 0.62, c, 0, c, P.stone); for (const dx of [-0.35, 0.35]) for (const dz of [-0.3, 0.3]) k.box(0.05, h, 0.05, c + dx, 0, c + dz, P.wood) }
      k.gable(0.82, 0.76, 0.38, c, h, c, P.roof, v % 2 ? Math.PI / 2 : 0)
      k.box(0.1, 0.35, 0.1, c + 0.22, h + 0.08, c - 0.15, era >= 6 ? '#8a4a3a' : P.stone)
      k.door(c, 0, c + 0.301, P.dark, 0.13, 0.24)
      k.winRow(0.7, 0.6, c, c, 0.15, 2, 0.09, 0.11, [0, 1, 2, 3])
      if (tall) k.winRow(0.7, 0.6, c, c, 0.4, 2, 0.08, 0.1, [0, 2])
      // a little garden
      k.box(0.18, 0.06, 0.08, c - 0.24, 0, c + 0.38, '#5a8a3c')
      smoke.push([c + 0.22, h + 0.48, c - 0.15, 0])
      light = [c, 0.3, c + 0.45, 2.4, 0.6]
      height = h + 0.4
      break
    }
    case 'apartment': {
      plinth(1.9)
      const floors = 5
      k.box(1.6, floors * 0.32, 1.3, c, 0, c, era >= 8 ? '#cfd3d8' : P.wall)
      for (let f = 0; f < floors; f++) k.winRow(1.6, 1.3, c, c, 0.1 + f * 0.32, 6, 0.12, 0.15)
      for (let f = 1; f < floors; f++) k.box(1.64, 0.03, 1.34, c, f * 0.32, c, P.stone)
      k.box(1.66, 0.06, 1.36, c, floors * 0.32, c, P.roof)
      k.box(0.3, 0.2, 0.3, c + 0.4, floors * 0.32 + 0.06, c - 0.3, '#8a8f96')
      k.door(c, 0, c + 0.651, '#2a3038', 0.25, 0.26)
      light = [c, 0.3, c + 1, 3.5, 0.3]
      height = floors * 0.32 + 0.3
      break
    }
    case 'skyscraper': {
      plinth(1.95, '#9aa0a8')
      const h = 4.2 + (v % 3) * 0.6
      k.box(1.3, 0.3, 1.3, c, 0, c, '#c4c8ce')
      const glass = v % 2 ? '#3d6f99' : '#4a7fa8'
      k.box(1.1, h, 1.1, c, 0.3, c, glass)
      k.box(0.8, 0.6, 0.8, c, h + 0.3, c, glass)
      k.cyl(0.02, 0.03, 0.8, c, h + 0.9, c, '#c9d1d9', 5)
      for (let f = 0; f < h / 0.25; f++) {
        k.box(1.12, 0.025, 1.12, c, 0.3 + f * 0.25, c, '#9fb2c4')
        k.winRow(1.1, 1.1, c, c, 0.36 + f * 0.25, 5, 0.17, 0.13)
      }
      anim = 'beacon'
      light = [c, 0.3, c + 1, 4, 0.2]
      height = h + 1.7
      break
    }
    case 'stockpile': {
      k.box(0.8, 0.03, 0.8, c, 0, c, '#8a7454')
      for (let i = 0; i < 4; i++) k.cyl(0.05, 0.05, 0.5, c - 0.2, 0.06 + i * 0.09, c - 0.2 + (i % 2) * 0.01, i % 2 ? '#7a5530' : '#8a6038', 7).rotateX(Math.PI / 2)
      // crates and sacks
      k.box(0.22, 0.2, 0.22, c + 0.2, 0, c + 0.15, '#a8804e')
      k.box(0.18, 0.16, 0.18, c + 0.18, 0.2, c + 0.15, '#9a7446', 0.4)
      k.sph(0.11, c - 0.15, 0.1, c + 0.22, '#c8ad7a', 0.8)
      k.sph(0.09, c - 0.0, 0.08, c + 0.28, '#bfa070', 0.8)
      k.box(0.2, 0.18, 0.2, c + 0.25, 0, c - 0.2, '#8d8a84')
      height = 0.45
      break
    }
    case 'granary': {
      plinth(0.8)
      k.cyl(0.3, 0.32, 0.55, c, 0, c, era <= 3 ? '#cdb489' : P.wall, 14)
      k.sph(0.32, c, 0.55, c, P.roof, 0.8, true, 14)
      k.cone(0.06, 0.1, c, 0.8, c, P.trim, 6)
      k.door(c, 0, c + 0.31, P.dark, 0.12, 0.22)
      k.box(0.25, 0.04, 0.25, c, 0.25, c + 0.3, P.wood)
      height = 0.9
      break
    }
    case 'warehouse': {
      plinth(1.9)
      k.box(1.7, 0.75, 1.3, c, 0, c, P.wall)
      const g = new THREE.CylinderGeometry(0.65, 0.65, 1.74, 16, 1, false, -Math.PI / 2, Math.PI)
      g.rotateZ(Math.PI / 2)
      g.scale(1, 0.45, 1)
      g.translate(c, 0.75, c)
      k['add'](g, P.roof)
      k.box(0.5, 0.45, 0.02, c - 0.4, 0, c + 0.651, '#5a5f66')
      k.box(0.5, 0.45, 0.02, c + 0.35, 0, c + 0.651, '#5a5f66')
      k.winRow(1.7, 1.3, c, c, 0.55, 5, 0.12, 0.08, [1, 2, 3])
      height = 1.1
      break
    }
    case 'farm': {
      k.box(0.96, 0.08, 0.96, c, -0.06, c, '#6e5236')
      for (let r = 0; r < 5; r++) k.box(0.9, 0.05, 0.06, c, 0, c - 0.4 + r * 0.2, '#5a4128')
      anim = 'crops'
      height = 0.3
      break
    }
    case 'pasture': {
      k.box(0.98, 0.04, 0.98, c, -0.03, c, '#6f9c4a')
      for (let i = 0; i <= 4; i++) {
        const t = -0.46 + i * 0.23
        for (const [x, z] of [[t, -0.46], [t, 0.46], [-0.46, t], [0.46, t]]) k.box(0.035, 0.2, 0.035, c + x, 0, c + z, P.wood)
      }
      for (const y of [0.08, 0.16]) {
        k.box(0.94, 0.025, 0.02, c, y, c - 0.46, '#8a6038'); k.box(0.94, 0.025, 0.02, c, y, c + 0.46, '#8a6038')
        k.box(0.02, 0.025, 0.94, c - 0.46, y, c, '#8a6038'); k.box(0.02, 0.025, 0.94, c + 0.46, y, c, '#8a6038')
      }
      k.box(0.14, 0.05, 0.08, c + 0.3, 0, c - 0.3, '#7a6a4a')
      anim = 'herd'
      height = 0.3
      break
    }
    case 'well': {
      plinth(0.5, '#7d7a74')
      k.cyl(0.18, 0.2, 0.2, c, 0, c, P.stone, 12)
      k.cyl(0.14, 0.14, 0.02, c, 0.19, c, '#1f4a66', 12)
      k.box(0.04, 0.42, 0.04, c - 0.17, 0, c, P.wood)
      k.box(0.04, 0.42, 0.04, c + 0.17, 0, c, P.wood)
      k.gable(0.42, 0.3, 0.14, c, 0.42, c, P.roof)
      k.cyl(0.02, 0.02, 0.36, c, 0.33, c, P.wood, 6).rotateZ(Math.PI / 2)
      k.cyl(0.04, 0.035, 0.07, c, 0.22, c, '#7a5530', 7)
      height = 0.6
      break
    }
    case 'workshop': {
      plinth(0.86)
      k.box(0.72, 0.42, 0.55, c, 0, c - 0.05, P.wall)
      k.gable(0.82, 0.7, 0.3, c, 0.42, c - 0.05, P.roof)
      // open-front awning and workbench
      k.box(0.7, 0.03, 0.26, c, 0.4, c + 0.33, P.wood, 0, 0.25)
      k.box(0.04, 0.38, 0.04, c - 0.32, 0, c + 0.42, P.wood)
      k.box(0.04, 0.38, 0.04, c + 0.32, 0, c + 0.42, P.wood)
      k.box(0.4, 0.04, 0.15, c - 0.05, 0.16, c + 0.3, '#8a6038')
      k.box(0.04, 0.16, 0.04, c - 0.22, 0, c + 0.3, '#6b4a2e'); k.box(0.04, 0.16, 0.04, c + 0.12, 0, c + 0.3, '#6b4a2e')
      k.cyl(0.05, 0.05, 0.25, c + 0.25, 0, c + 0.3, '#8a6038', 7).rotateZ(Math.PI / 2)
      k.win(0.1, 0.1, c + 0.2, 0.2, c + 0.226, 0)
      k.win(0.1, 0.1, c - 0.2, 0.2, c + 0.226, 0)
      height = 0.8
      break
    }
    case 'kiln': {
      plinth(0.7, '#7d6a54')
      k.sph(0.32, c, 0.02, c, '#b9714a', 1.1, true, 12)
      k.cyl(0.08, 0.1, 0.35, c + 0.12, 0.25, c - 0.1, '#9a5a3a', 8)
      k.box(0.16, 0.14, 0.04, c, 0, c + 0.3, '#ff9a3a')
      k.win(0.14, 0.12, c, 0.01, c + 0.325, 0)
      smoke.push([c + 0.12, 0.62, c - 0.1, 0])
      light = [c, 0.2, c + 0.35, 2.5, 1]
      height = 0.65
      break
    }
    case 'smithy': {
      plinth(0.86)
      k.box(0.72, 0.45, 0.6, c, 0, c, era >= 6 ? P.wall : P.stone)
      k.gable(0.82, 0.74, 0.28, c, 0.45, c, P.roof)
      k.box(0.16, 0.6, 0.16, c - 0.24, 0.3, c - 0.14, '#6e6a64')
      k.box(0.14, 0.08, 0.08, c + 0.18, 0, c + 0.42, '#3a3a3e')
      k.box(0.08, 0.1, 0.06, c + 0.18, 0.08, c + 0.42, '#2a2a2e')
      k.box(0.24, 0.18, 0.02, c - 0.1, 0, c + 0.301, '#ff7a1a')
      k.win(0.22, 0.16, c - 0.1, 0.01, c + 0.305, 0)
      smoke.push([c - 0.24, 0.95, c - 0.14, 1], [c - 0.24, 0.95, c - 0.14, 0])
      light = [c, 0.25, c + 0.45, 2.6, 1]
      height = 0.9
      break
    }
    case 'mine': {
      if (era <= 3) {
        // a pit and a timbered adit into the hillside
        k.box(0.9, 0.25, 0.7, c, -0.05, c - 0.1, '#7d7468')
        k.sph(0.45, c, 0.1, c - 0.15, '#7d7468', 0.7, true, 10)
        k.box(0.3, 0.3, 0.06, c, 0, c + 0.24, '#141210')
        k.box(0.05, 0.36, 0.05, c - 0.18, 0, c + 0.27, P.wood)
        k.box(0.05, 0.36, 0.05, c + 0.18, 0, c + 0.27, P.wood)
        k.box(0.44, 0.06, 0.06, c, 0.34, c + 0.27, P.wood)
        k.box(0.06, 0.01, 0.4, c - 0.05, 0, c + 0.45, '#5a5048'); k.box(0.06, 0.01, 0.4, c + 0.05, 0, c + 0.45, '#5a5048')
        k.box(0.16, 0.1, 0.14, c, 0.03, c + 0.48, '#4a4440')
        k.sph(0.06, c, 0.13, c + 0.48, '#8a7a6a', 0.6)
        height = 0.6
      } else {
        // a shaft with a headframe and winding wheel; a spoil heap beside it
        const steel = era >= 6
        const fr = steel ? '#5a6068' : P.wood
        k.box(0.36, 0.04, 0.36, c, 0, c, '#3a3530')
        k.box(0.2, 0.02, 0.2, c, 0.04, c, '#0c0a08')
        for (const [x, z] of [[-0.15, -0.15], [0.15, -0.15], [-0.15, 0.15], [0.15, 0.15]]) k.box(0.035, 0.95, 0.035, c + x * 0.6, 0, c + z, fr, 0, 0, x * 0.25)
        k.box(0.3, 0.04, 0.3, c, 0.92, c, fr)
        k.box(0.04, 0.9, 0.04, c, 0, c - 0.32, fr, 0, -0.35)
        k.box(0.36, 0.34, 0.3, c - 0.05, 0, c - 0.6 + 0.6, steel ? '#8a4a3a' : P.wall, 0, 0, 0)
        k.cone(0.32, 0.36, c + 0.3, 0, c - 0.3, '#5e5850', 9)
        if (steel) { k.cyl(0.04, 0.05, 0.8, c - 0.32, 0.34, c + 0.1, '#8a4a3a', 8); smoke.push([c - 0.32, 1.16, c + 0.1, 0]) }
        k.box(0.06, 0.01, 0.4, c - 0.05, 0, c + 0.42, '#5a5048'); k.box(0.06, 0.01, 0.4, c + 0.05, 0, c + 0.42, '#5a5048')
        k.box(0.16, 0.1, 0.14, c, 0.03, c + 0.46, '#4a4440')
        anim = 'wheel'
        pivot = [c, 1.02, c]
        height = 1.2
      }
      light = [c, 0.25, c + 0.35, 1.8, 1]
      break
    }
    case 'oil_well': {
      k.box(0.6, 0.04, 0.4, c, 0, c, '#6a6560')
      k.box(0.12, 0.16, 0.18, c - 0.2, 0.04, c, '#3a3a40')
      // the samson post that carries the walking beam
      k.box(0.04, 0.42, 0.04, c - 0.02, 0.04, c - 0.06, '#c0392b', 0, 0.2)
      k.box(0.04, 0.42, 0.04, c - 0.02, 0.04, c + 0.06, '#c0392b', 0, -0.2)
      k.cyl(0.02, 0.02, 0.1, c + 0.24, 0.04, c, '#4a4a50', 6)
      k.box(0.14, 0.1, 0.14, c + 0.24, 0.0, c, '#2a2a2e')
      anim = 'pump'
      pivot = [c - 0.02, 0.46, c]
      height = 0.7
      break
    }
    case 'offshore_rig': {
      for (const [x, z] of [[-0.6, -0.6], [0.6, -0.6], [-0.6, 0.6], [0.6, 0.6]]) k.cyl(0.07, 0.09, 2.2, c + x, -2, c + z, '#d0a030', 8)
      k.box(1.7, 0.12, 1.7, c, 0.2, c, '#6a6e74')
      k.box(1.5, 0.3, 0.6, c - 0.05, 0.32, c - 0.45, '#e8e8e4')
      k.winRow(1.5, 0.6, c - 0.05, c - 0.45, 0.42, 6, 0.1, 0.07, [0, 2])
      // the derrick
      for (const [x, z] of [[-0.14, -0.14], [0.14, -0.14], [-0.14, 0.14], [0.14, 0.14]]) k.box(0.03, 1.1, 0.03, c + 0.35 + x * 0.6, 0.32, c + 0.3 + z * 0.6, '#c0392b', 0, z * -0.12, x * 0.12)
      k.box(0.12, 0.04, 0.12, c + 0.35, 1.4, c + 0.3, '#c0392b')
      // helideck and flare boom
      k.cyl(0.32, 0.32, 0.04, c - 0.5, 0.62, c + 0.5, '#2e7d32', 16)
      k.box(0.7, 0.03, 0.03, c + 0.9, 0.6, c - 0.4, '#8a8f96', 0, 0, -0.5)
      smoke.push([c + 1.2, 0.85, c - 0.4, 1])
      anim = 'flare'
      pivot = [c + 1.2, 0.8, c - 0.4]
      light = [c, 0.6, c, 4, 0.5]
      height = 1.6
      break
    }
    case 'evaporation_ponds': {
      const cols = ['#9fe0e8', '#bfeaf0', '#e8f6f8', '#f6fbfc']
      for (let i = 0; i < 4; i++) {
        k.box(0.86, 0.03, 0.86, c + (i % 2 ? 0.45 : -0.45), 0, c + (i < 2 ? -0.45 : 0.45), '#d8d0c0')
        k.box(0.76, 0.035, 0.76, c + (i % 2 ? 0.45 : -0.45), 0.005, c + (i < 2 ? -0.45 : 0.45), cols[i])
      }
      k.box(0.2, 0.16, 0.14, c, 0, c, '#9aa3ad')
      height = 0.2
      break
    }
    case 'refinery': {
      plinth(1.9, '#8a8680')
      for (const [x, z, h] of [[-0.55, -0.4, 1.3], [-0.25, -0.5, 1.6], [0.1, -0.45, 1.1]]) { k.cyl(0.09, 0.11, h, c + x, 0, c + z, '#b8bcc2', 10); for (let y = 0.3; y < h; y += 0.35) k.cyl(0.13, 0.13, 0.03, c + x, y, c + z, '#8a8f96', 10) }
      for (const [x, z] of [[0.45, 0.35], [0.45, -0.25], [-0.4, 0.45]]) { k.cyl(0.26, 0.26, 0.3, c + x, 0, c + z, '#e8e6e0', 16); k.sph(0.26, c + x, 0.3, c + z, '#d8d6d0', 0.2, true, 16) }
      k.box(1.2, 0.04, 0.04, c - 0.1, 0.5, c, '#7a7f86'); k.box(0.04, 0.04, 1.0, c + 0.2, 0.4, c, '#7a7f86')
      k.cyl(0.03, 0.04, 1.8, c + 0.75, 0, c - 0.7, '#8a8f96', 6)
      smoke.push([c + 0.75, 1.9, c - 0.7, 1], [c - 0.25, 1.65, c - 0.5, 2])
      anim = 'flare'
      pivot = [c + 0.75, 1.82, c - 0.7]
      light = [c, 0.6, c + 0.9, 4, 0.7]
      height = 2
      break
    }
    case 'fish_farm': {
      k.box(0.12, 0.04, 1.6, c, 0.06, c + 0.6, P.wood)
      for (const [x, z] of [[-0.28, 1.05], [0.28, 1.35], [-0.28, 1.6]]) {
        const ring = new THREE.TorusGeometry(0.22, 0.03, 6, 20)
        ring.rotateX(Math.PI / 2)
        ring.translate(c + x, 0.04, c + z)
        k['add'](ring, '#e8e8e4')
        k.cyl(0.21, 0.21, 0.01, c + x, 0.02, c + z, '#2a5a6a', 18)
      }
      k.box(0.3, 0.2, 0.24, c, 0, c - 0.25, '#4a7aa8')
      height = 0.3
      break
    }
    case 'wind_farm': {
      for (const [x, z] of [[0.45, 0.45], [1.5, 0.6], [0.9, 1.5]]) {
        k.cyl(0.035, 0.06, 2.1, x, 0, z, '#f4f4f2', 10)
        k.box(0.12, 0.1, 0.22, x, 2.06, z - 0.02, '#f4f4f2')
      }
      anim = 'wind'
      pivot = [0.45, 2.11, 0.45 + 0.1]
      height = 2.6
      break
    }
    case 'solar_farm': {
      for (let r = 0; r < 5; r++) for (let q = 0; q < 2; q++) {
        const z = 0.25 + r * 0.36, x = 0.5 + q * 0.95
        k.box(0.04, 0.12, 0.04, x - 0.3, 0, z, '#9aa0a8'); k.box(0.04, 0.12, 0.04, x + 0.3, 0, z, '#9aa0a8')
        k.box(0.82, 0.02, 0.26, x, 0.12, z, '#1d3a6a', 0, -0.45)
      }
      height = 0.3
      break
    }
    case 'nuclear_plant': {
      plinth(1.95, '#a8a49c')
      for (const x of [0.45, 1.4]) {
        const pts: THREE.Vector2[] = []
        for (let i = 0; i <= 12; i++) { const t = i / 12; pts.push(new THREE.Vector2(0.4 - Math.sin(t * Math.PI) * 0.13 - t * 0.04, t * 1.6)) }
        const lathe = new THREE.LatheGeometry(pts, 20)
        lathe.translate(x, 0, 0.5)
        k['add'](lathe, '#d8d4cc')
        smoke.push([x, 1.62, 0.5, 2])
      }
      k.cyl(0.32, 0.32, 0.5, 0.6, 0, 1.45, '#e8e6e0', 20)
      k.sph(0.32, 0.6, 0.5, 1.45, '#d8d6d0', 1, true, 20)
      k.box(0.7, 0.45, 0.5, 1.35, 0, 1.45, '#9aa3ad')
      k.winRow(0.7, 0.5, 1.35, 1.45, 0.2, 4, 0.1, 0.08, [0, 1])
      light = [c, 0.4, c + 0.9, 4, 0.2]
      height = 1.7
      break
    }
    case 'airport': {
      k.box(1.95, 0.02, 0.42, c, 0, c - 0.55, '#3a3a3e')
      for (let i = 0; i < 8; i++) k.box(0.12, 0.022, 0.03, 0.15 + i * 0.24, 0, c - 0.55, '#f4f4f2')
      k.box(1.2, 0.3, 0.45, c - 0.3, 0, c + 0.5, '#d8dde2')
      k.winRow(1.2, 0.45, c - 0.3, c + 0.5, 0.08, 8, 0.11, 0.14, [0, 2])
      k.cyl(0.05, 0.06, 0.7, c + 0.7, 0, c + 0.5, '#e8e8e4', 8)
      k.cyl(0.13, 0.1, 0.14, c + 0.7, 0.7, c + 0.5, '#5f8fb3', 10)
      // a parked plane
      k.cyl(0.05, 0.05, 0.6, c + 0.2, 0.08, c - 0.1, '#f4f4f2', 10).rotateZ(Math.PI / 2)
      k.box(0.12, 0.012, 0.6, c + 0.2, 0.08, c - 0.1, '#f4f4f2')
      k.box(0.04, 0.12, 0.12, c + 0.48, 0.1, c - 0.1, '#c0392b')
      anim = 'beacon'
      light = [c, 0.4, c, 4, 0.3]
      height = 0.9
      break
    }
    case 'radio_tower': {
      for (const [x, z] of [[-0.08, -0.08], [0.08, -0.08], [-0.08, 0.08], [0.08, 0.08]]) k.box(0.02, 1.9, 0.02, c + x * 0.6, 0, c + z * 0.6, '#c0392b', 0, z * -0.03, x * 0.03)
      for (let y = 0.2; y < 1.9; y += 0.25) k.box(0.13, 0.02, 0.13, c, y, c, y % 0.5 < 0.25 ? '#f4f4f2' : '#c0392b')
      k.box(0.2, 0.14, 0.16, c + 0.25, 0, c + 0.2, '#9aa3ad')
      anim = 'beacon'
      height = 2
      break
    }
    case 'harbour': {
      k.box(1.95, 0.14, 0.8, c, 0, c - 0.5, '#9a968f')
      for (let i = 0; i < 3; i++) k.box(0.55, 0.06, 0.9, 0.3 + i * 0.7, 0.08, c + 0.45, '#8a8680')
      // a crane
      k.box(0.06, 1.1, 0.06, c - 0.5, 0.14, c - 0.3, '#e0a030'); k.box(0.9, 0.05, 0.05, c - 0.2, 1.2, c - 0.3, '#e0a030', 0, 0, -0.1)
      // warehouses
      k.box(0.6, 0.35, 0.4, c + 0.5, 0.14, c - 0.6, '#8a4a3a'); k.gable(0.62, 0.42, 0.12, c + 0.5, 0.49, c - 0.6, P.roof)
      // a moored trawler
      const hull = new THREE.SphereGeometry(0.3, 12, 6, 0, Math.PI * 2, Math.PI / 2, Math.PI / 2)
      hull.scale(0.6, 0.55, 1.8)
      hull.translate(c + 0.1, 0.12, c + 1.25)
      k['add'](hull, '#2e5a8a')
      k.box(0.24, 0.2, 0.3, c + 0.1, 0.14, c + 1.1, '#f4f4f2')
      k.cyl(0.015, 0.015, 0.5, c + 0.1, 0.14, c + 1.4, '#3a3a3e', 5)
      light = [c, 0.5, c + 0.6, 4, 0.5]
      height = 1.3
      break
    }
    case 'library': {
      plinth(0.92)
      k.box(0.86, 0.06, 0.7, c, 0, c, P.stone)
      k.box(0.74, 0.5, 0.55, c, 0.06, c - 0.03, P.wall)
      for (let i = 0; i < 4; i++) k.cyl(0.03, 0.035, 0.5, c - 0.3 + i * 0.2, 0.06, c + 0.3, '#efe9dc', 8)
      k.box(0.82, 0.06, 0.66, c, 0.56, c, P.stone)
      k.gable(0.82, 0.68, 0.22, c, 0.62, c, P.roof)
      k.door(c, 0.06, c + 0.25, P.dark, 0.14, 0.26)
      k.winRow(0.74, 0.55, c, c - 0.03, 0.22, 3, 0.08, 0.16, [1, 2, 3])
      light = [c, 0.3, c + 0.5, 2.4, 0.5]
      height = 0.9
      break
    }
    case 'temple': {
      plinth(0.96)
      if (era <= 2) {
        // a stepped ziggurat
        k.box(0.9, 0.18, 0.9, c, 0, c, '#c9b48a')
        k.box(0.68, 0.18, 0.68, c, 0.18, c, '#d2bf96')
        k.box(0.46, 0.18, 0.46, c, 0.36, c, '#dccaa2')
        k.box(0.26, 0.2, 0.26, c, 0.54, c, P.trim)
        k.box(0.12, 0.54, 0.3, c, 0, c + 0.4, '#bfa880', 0, 0.6)
        smoke.push([c, 0.8, c, 0])
        height = 0.9
      } else {
        k.box(0.92, 0.1, 0.92, c, 0, c, '#e8e1d2')
        k.box(0.82, 0.06, 0.82, c, 0.1, c, '#efe9dc')
        for (let i = 0; i < 5; i++) for (const z of [-0.36, 0.36]) k.cyl(0.035, 0.04, 0.55, c - 0.36 + i * 0.18, 0.16, c + z, '#f4efe4', 10)
        for (let i = 1; i < 4; i++) for (const x of [-0.36, 0.36]) k.cyl(0.035, 0.04, 0.55, c + x, 0.16, c - 0.36 + i * 0.18, '#f4efe4', 10)
        k.box(0.5, 0.45, 0.5, c, 0.16, c, '#d8cfbe')
        k.box(0.86, 0.07, 0.86, c, 0.71, c, '#efe9dc')
        k.gable(0.88, 0.88, 0.22, c, 0.78, c, P.roof)
        k.win(0.12, 0.2, c, 0.16, c + 0.25, 0)
        height = 1.05
      }
      light = [c, 0.4, c + 0.5, 3, 0.8]
      break
    }
    case 'market': {
      k.box(0.96, 0.03, 0.96, c, -0.01, c, era >= 3 ? '#b9b2a2' : '#9a8a64')
      const cols = [P.trim, '#e8d8a8', '#c0504d', '#4f81bd']
      const spots: [number, number][] = [[-0.25, -0.22], [0.25, -0.22], [-0.25, 0.25], [0.25, 0.25]]
      spots.forEach(([x, z], i) => {
        k.box(0.3, 0.15, 0.18, c + x, 0, c + z, '#8a6038')
        for (const [px, pz] of [[-0.13, -0.08], [0.13, -0.08], [-0.13, 0.08], [0.13, 0.08]]) k.box(0.02, 0.42, 0.02, c + x + px, 0, c + z + pz, P.wood)
        k.gable(0.34, 0.24, 0.1, c + x, 0.42, c + z, cols[i % cols.length])
        k.sph(0.04, c + x - 0.06, 0.18, c + z, ['#e53935', '#fb8c00', '#7cb342', '#fdd835'][i], 0.8, false, 6)
        k.sph(0.04, c + x + 0.06, 0.18, c + z, ['#fdd835', '#7cb342', '#e53935', '#8e24aa'][i], 0.8, false, 6)
      })
      light = [c, 0.3, c, 2.4, 0.7]
      height = 0.5
      break
    }
    case 'town_hall': {
      plinth(1.95)
      k.box(1.8, 0.08, 1.6, c, 0, c, P.stone)
      k.box(1.5, 0.7, 1.1, c, 0.08, c - 0.1, P.wall)
      k.gable(1.6, 1.24, 0.42, c, 0.78, c - 0.1, P.roof)
      // central tower and banner
      k.box(0.42, 1.1, 0.42, c, 0.08, c + 0.3, P.wall2 === '#c9c2b6' ? P.wall2 : P.stone)
      k.hip(0.5, 0.5, 0.4, c, 1.18, c + 0.3, P.roof)
      k.cyl(0.012, 0.012, 0.5, c, 1.55, c + 0.3, '#d9d9d9', 5)
      k.box(0.24, 0.14, 0.01, c + 0.12, 1.88, c + 0.3, P.trim)
      k.cyl(0.11, 0.11, 0.02, c, 0.85, c + 0.515, '#f4efe4', 14).rotateX(Math.PI / 2)
      for (let i = 0; i < 6; i++) k.cyl(0.035, 0.04, 0.55, c - 0.62 + i * 0.25, 0.08, c + 0.5, '#efe9dc', 8)
      k.box(1.5, 0.05, 0.2, c, 0.63, c + 0.48, P.stone)
      k.door(c, 0.08, c + 0.511, P.dark, 0.2, 0.32)
      k.winRow(1.5, 1.1, c, c - 0.1, 0.3, 5, 0.1, 0.16, [1, 2, 3])
      k.winRow(1.5, 1.1, c, c - 0.1, 0.3, 5, 0.1, 0.16, [0])
      light = [c, 0.4, c + 0.8, 4, 0.6]
      height = 2
      break
    }
    case 'watchtower': {
      plinth(0.6)
      k.cyl(0.22, 0.27, 1.3, c, 0, c, P.stone, 10)
      k.cyl(0.3, 0.26, 0.12, c, 1.3, c, P.stone, 10)
      for (let i = 0; i < 8; i++) { const a = (i / 8) * Math.PI * 2; k.box(0.08, 0.1, 0.08, c + Math.cos(a) * 0.26, 1.42, c + Math.sin(a) * 0.26, P.stone, -a) }
      k.cone(0.3, 0.32, c, 1.52, c, P.roof, 10)
      k.box(0.1, 0.18, 0.02, c, 0, c + 0.27, P.dark)
      k.win(0.06, 0.12, c, 0.8, c + 0.235, 0)
      k.win(0.06, 0.12, c + 0.235, 0.6, c, 1)
      smoke.push([c, 1.45, c, 1])
      light = [c, 1.4, c, 4, 1]
      anim = 'fire'
      height = 1.9
      break
    }
    case 'dock': {
      for (const [x, z] of [[-0.35, -0.2], [0.35, -0.2], [-0.35, 0.45], [0.35, 0.45], [-0.35, 0.95], [0.35, 0.95]]) k.cyl(0.04, 0.04, 0.7, c + x, -0.5, c + z, '#5a3d26', 6)
      for (let i = 0; i < 9; i++) k.box(0.8, 0.04, 0.13, c, 0.12, c - 0.3 + i * 0.15, i % 2 ? '#8a6038' : '#7a5530')
      k.box(0.2, 0.15, 0.2, c - 0.2, 0.16, c - 0.15, '#a8804e')
      // a moored boat
      const hull = new THREE.SphereGeometry(0.3, 12, 6, 0, Math.PI * 2, Math.PI / 2, Math.PI / 2)
      hull.scale(0.55, 0.45, 1.3)
      hull.translate(c + 0.65, 0.12, c + 0.6)
      k['add'](hull, '#6b4a2b')
      k.cyl(0.015, 0.015, 0.7, c + 0.65, 0.1, c + 0.6, '#5a3d26', 5)
      k.box(0.01, 0.4, 0.3, c + 0.66, 0.3, c + 0.6, '#efe7d6')
      light = [c, 0.4, c + 0.8, 2.6, 0.6]
      height = 0.7
      break
    }
    case 'mill': {
      plinth(0.7)
      k.cyl(0.24, 0.32, 0.95, c, 0, c, era >= 4 ? '#e6dcc6' : P.stone, 12)
      k.cone(0.3, 0.32, c, 0.95, c, P.roof, 12)
      k.door(c, 0, c + 0.3, P.dark, 0.13, 0.22)
      k.win(0.07, 0.1, c, 0.55, c + 0.27, 0)
      anim = 'mill'
      height = 1.6
      break
    }
    case 'university': {
      plinth(1.95)
      k.box(1.8, 0.1, 1.5, c, 0, c, P.stone)
      k.box(1.7, 0.75, 0.6, c, 0.1, c - 0.4, P.wall)
      k.box(0.55, 0.75, 1.3, c - 0.58, 0.1, c + 0.05, P.wall)
      k.box(0.55, 0.75, 1.3, c + 0.58, 0.1, c + 0.05, P.wall)
      k.gable(1.76, 0.66, 0.25, c, 0.85, c - 0.4, P.roof)
      k.gable(1.36, 0.6, 0.25, c - 0.58, 0.85, c + 0.05, P.roof, Math.PI / 2)
      k.gable(1.36, 0.6, 0.25, c + 0.58, 0.85, c + 0.05, P.roof, Math.PI / 2)
      k.cyl(0.26, 0.28, 0.35, c, 0.85, c - 0.4, '#efe9dc', 14)
      k.sph(0.27, c, 1.2, c - 0.4, era >= 6 ? '#6f8f9a' : '#7a9a8a', 1, true, 16)
      k.cone(0.03, 0.15, c, 1.46, c - 0.4, '#e2c35b', 6)
      for (let i = 0; i < 5; i++) k.cyl(0.03, 0.035, 0.7, c - 0.4 + i * 0.2, 0.1, c - 0.08, '#efe9dc', 8)
      k.winRow(1.7, 0.6, c, c - 0.4, 0.3, 7, 0.08, 0.16, [0, 2])
      k.winRow(0.55, 1.3, c - 0.58, c + 0.05, 0.3, 5, 0.08, 0.16, [1, 3])
      k.winRow(0.55, 1.3, c + 0.58, c + 0.05, 0.3, 5, 0.08, 0.16, [1, 3])
      // a quad with a tree
      k.box(0.55, 0.02, 0.6, c, 0.1, c + 0.35, '#6f9c4a')
      k.cyl(0.03, 0.04, 0.25, c, 0.12, c + 0.35, '#6b4a2e', 6)
      k.sph(0.16, c, 0.45, c + 0.35, '#4a8f3a', 0.9, false, 8)
      light = [c, 0.4, c + 0.9, 4, 0.5]
      height = 1.5
      break
    }
    case 'hospital': {
      plinth(0.94)
      k.box(0.86, 0.75, 0.7, c, 0, c, '#f2f2f0')
      k.box(0.88, 0.05, 0.72, c, 0.75, c, '#c9ced4')
      k.box(0.24, 0.07, 0.02, c, 0.5, c + 0.352, '#e53935')
      k.box(0.07, 0.24, 0.02, c, 0.42, c + 0.352, '#e53935')
      k.winRow(0.86, 0.7, c, c, 0.15, 4, 0.1, 0.1, [1, 2, 3])
      k.winRow(0.86, 0.7, c, c, 0.55, 4, 0.1, 0.1, [1, 2, 3])
      k.box(0.3, 0.24, 0.02, c, 0, c + 0.351, '#5a8fb8')
      light = [c, 0.4, c + 0.5, 3, 0.3]
      height = 0.9
      break
    }
    case 'factory': {
      plinth(1.95)
      k.box(1.7, 0.6, 1.2, c - 0.05, 0, c + 0.1, P.wall)
      for (let i = 0; i < 4; i++) {
        // saw-tooth roof
        const sx = c - 0.7 + i * 0.42
        const g = new THREE.BufferGeometry()
        const z0 = c - 0.5, z1 = c + 0.7, y0 = 0.6, x0 = sx - 0.21, x1 = sx + 0.21
        const p = [x0, y0, z0, x1, y0, z0, x0, y0 + 0.28, z0, x0, y0, z1, x1, y0, z1, x0, y0 + 0.28, z1]
        g.setAttribute('position', new THREE.Float32BufferAttribute([
          ...p.slice(0, 9), ...p.slice(9, 18).slice(0, 9),
          x0, y0 + 0.28, z0, x1, y0, z0, x1, y0, z1, x0, y0 + 0.28, z0, x1, y0, z1, x0, y0 + 0.28, z1,
          x0, y0, z0, x0, y0 + 0.28, z0, x0, y0 + 0.28, z1, x0, y0, z0, x0, y0 + 0.28, z1, x0, y0, z1,
        ], 3))
        g.computeVertexNormals()
        k['add'](g, P.roof)
        k.win(1.15, 0.2, sx - 0.2, 0.62, c + 0.1, 3)
      }
      for (const [x, z] of [[0.65, -0.6], [0.35, -0.6]]) k.cyl(0.08, 0.11, 1.5, c + x, 0, c + z, '#8a4a3a', 10)
      k.box(0.5, 0.35, 0.02, c - 0.3, 0, c + 0.701, '#4a4f56')
      k.winRow(1.7, 1.2, c - 0.05, c + 0.1, 0.32, 6, 0.12, 0.14, [0, 2])
      smoke.push([c + 0.65, 1.55, c - 0.6, 0], [c + 0.35, 1.55, c - 0.6, 0])
      light = [c, 0.4, c + 0.9, 3.5, 0.6]
      height = 1.6
      break
    }
    case 'power_plant': {
      plinth(1.95, '#9a968f')
      // a hyperboloid cooling tower
      const pts: THREE.Vector2[] = []
      for (let i = 0; i <= 10; i++) { const t = i / 10; pts.push(new THREE.Vector2(0.42 - Math.sin(t * Math.PI) * 0.12 - t * 0.06, t * 1.4)) }
      const lathe = new THREE.LatheGeometry(pts, 18)
      lathe.translate(c - 0.4, 0, c - 0.35)
      k['add'](lathe, '#c9c5bd')
      k.box(0.8, 0.55, 0.7, c + 0.45, 0, c + 0.35, '#8f949a')
      k.cyl(0.06, 0.07, 1.1, c + 0.7, 0.55, c + 0.1, '#b4b0a8', 8)
      k.box(0.3, 0.2, 0.3, c - 0.35, 0, c + 0.6, '#d9a54a')
      k.winRow(0.8, 0.7, c + 0.45, c + 0.35, 0.3, 4, 0.1, 0.1, [0, 1])
      smoke.push([c - 0.4, 1.45, c - 0.35, 2], [c + 0.7, 1.7, c + 0.1, 0])
      light = [c, 0.4, c + 0.9, 4, 0.2]
      height = 1.7
      break
    }
    case 'lab': {
      plinth(0.94, '#b4b8bd')
      k.box(0.84, 0.55, 0.66, c, 0, c, '#eef1f4')
      k.box(0.86, 0.04, 0.68, c, 0.55, c, '#9aa3ad')
      k.box(0.8, 0.3, 0.02, c, 0.15, c + 0.331, '#5f8fb3')
      k.win(0.78, 0.28, c, 0.16, c + 0.333, 0)
      k.sph(0.16, c + 0.2, 0.59, c - 0.1, '#d9dde2', 1, true, 12)
      k.cyl(0.01, 0.01, 0.3, c - 0.25, 0.59, c - 0.15, '#c9d1d9', 4)
      k.winRow(0.84, 0.66, c, c, 0.3, 3, 0.12, 0.1, [1, 3])
      light = [c, 0.4, c + 0.5, 3, 0.2]
      height = 0.8
      break
    }
    case 'data_center': {
      plinth(1.95, '#9aa0a8')
      k.box(1.7, 0.6, 1.3, c, 0, c, '#2c3138')
      k.box(1.72, 0.04, 1.32, c, 0.6, c, '#4a5058')
      for (let i = 0; i < 6; i++) k.box(0.18, 0.12, 0.18, c - 0.6 + i * 0.24, 0.64, c - 0.3, '#8a9098')
      for (let r = 0; r < 3; r++) k.winRow(1.7, 1.3, c, c, 0.1 + r * 0.16, 10, 0.06, 0.03)
      light = [c, 0.4, c + 0.9, 3, 0.2]
      anim = 'beacon'
      height = 0.8
      break
    }
    case 'monument': {
      k.box(0.8, 0.12, 0.8, c, -0.02, c, '#d8cfbe')
      k.box(0.6, 0.1, 0.6, c, 0.1, c, '#e8e1d2')
      if (v % 2 === 0) {
        // an obelisk
        const g = new THREE.CylinderGeometry(0.07, 0.12, 1.5, 4)
        g.rotateY(Math.PI / 4)
        g.translate(c, 0.95, c)
        k['add'](g, '#e8dfc8')
        k.cone(0.09, 0.16, c, 1.7, c, '#ffd34a', 4, Math.PI / 4)
        height = 1.9
      } else {
        // a statue on a plinth
        k.box(0.3, 0.45, 0.3, c, 0.2, c, '#d8cfbe')
        k.cyl(0.06, 0.08, 0.35, c, 0.65, c, '#c9a95a', 8)
        k.sph(0.06, c, 1.07, c, '#c9a95a', 1, false, 10)
        k.box(0.04, 0.3, 0.04, c + 0.09, 0.85, c, '#c9a95a', 0, 0, -0.5)
        k.cone(0.03, 0.08, c + 0.17, 1.12, c, '#ffd34a', 6)
        height = 1.3
      }
      light = [c, 0.6, c, 2.5, 0.8]
      break
    }
    case 'launch_pad': {
      k.box(1.9, 0.12, 1.9, c, -0.05, c, '#a8a8a4')
      k.cyl(0.5, 0.55, 0.06, c, 0.07, c, '#6a6a68', 18)
      // gantry
      k.box(0.18, 2.6, 0.18, c + 0.5, 0.07, c, '#c0504d')
      for (let i = 0; i < 8; i++) k.box(0.32, 0.025, 0.03, c + 0.34, 0.25 + i * 0.3, c, '#a8403d')
      k.box(0.3, 0.04, 0.05, c + 0.3, 1.9, c, '#a8403d')
      anim = 'rocket'
      light = [c, 0.4, c, 5, 0.3]
      height = 3
      break
    }
    case 'walls': {
      // a ring of defences around the 2×2 plot, with a gate on the front
      const ring = (h: number, col: string, thick: number) => {
        k.box(2 - thick, h, thick, c, 0, thick / 2, col)
        k.box(thick, h, 2, thick / 2, 0, c, col)
        k.box(thick, h, 2, 2 - thick / 2, 0, c, col)
        k.box(0.7, h, thick, 0.35, 0, 2 - thick / 2, col)
        k.box(0.7, h, thick, 1.65, 0, 2 - thick / 2, col)
      }
      if (era <= 1) {
        // a palisade of sharpened stakes
        for (let i = 0; i <= 16; i++) {
          const t = i / 16 * 2
          for (const [x, z] of [[t, 0.05], [0.05, t], [1.95, t]]) k.cone(0.045, 0.62, x, 0, z, i % 2 ? '#7a5530' : '#8a6038', 5)
          if (t < 0.7 || t > 1.3) k.cone(0.045, 0.62, t, 0, 1.95, i % 2 ? '#7a5530' : '#8a6038', 5)
        }
        k.box(0.06, 0.7, 0.06, 0.7, 0, 1.95, P.wood); k.box(0.06, 0.7, 0.06, 1.3, 0, 1.95, P.wood); k.box(0.7, 0.06, 0.06, c, 0.66, 1.95, P.wood)
        height = 0.7
      } else if (era <= 5) {
        ring(0.55, '#a8a197', 0.12)
        for (let i = 0; i < 12; i++) { const t = 0.08 + i * 0.16; k.box(0.07, 0.08, 0.14, t, 0.55, 0.06, '#9a948a'); k.box(0.14, 0.08, 0.07, 0.06, 0.55, t, '#9a948a'); k.box(0.14, 0.08, 0.07, 1.94, 0.55, t, '#9a948a') }
        for (const [x, z] of [[0.12, 0.12], [1.88, 0.12], [0.12, 1.88], [1.88, 1.88]]) { k.cyl(0.16, 0.18, 0.85, x, 0, z, '#b4ad9f', 10); k.cone(0.2, 0.3, x, 0.85, z, P.roof, 10) }
        // the gatehouse
        k.box(0.12, 0.8, 0.2, 0.68, 0, 1.92, '#9a948a'); k.box(0.12, 0.8, 0.2, 1.32, 0, 1.92, '#9a948a'); k.box(0.76, 0.2, 0.2, c, 0.6, 1.92, '#9a948a')
        k.box(0.5, 0.5, 0.03, c, 0, 1.94, '#5a3d26')
        k.box(0.01, 0.2, 0.14, 0.68, 0.85, 1.92, P.trim)
        height = 1.15
      } else {
        ring(0.4, '#9a9a94', 0.16)
        for (let i = 0; i < 8; i++) k.sph(0.07, 0.2 + i * 0.22, 0.42, 0.08, '#b8a77a', 0.6, false, 6)
        for (const [x, z] of [[0.15, 0.15], [1.85, 0.15]]) { k.box(0.3, 0.45, 0.3, x, 0, z, '#8a8a84'); k.win(0.18, 0.04, x, 0.32, z + 0.151, 0) }
        k.box(0.04, 0.6, 0.04, 0.68, 0, 1.92, '#6a6e74'); k.box(0.04, 0.6, 0.04, 1.32, 0, 1.92, '#6a6e74'); k.box(0.64, 0.04, 0.04, c, 0.3, 1.92, '#c0392b')
        height = 0.6
      }
      break
    }
    case 'barracks': {
      plinth(0.92)
      k.box(0.8, era >= 6 ? 0.5 : 0.42, 0.5, c, 0, c - 0.1, era >= 6 ? '#7a7f6a' : era <= 3 ? P.wall : P.stone)
      k.gable(0.9, 0.62, 0.22, c, era >= 6 ? 0.5 : 0.42, c - 0.1, era >= 6 ? '#5a604a' : P.roof)
      k.door(c, 0, c + 0.151, P.dark, 0.16, 0.24)
      k.winRow(0.8, 0.5, c, c - 0.1, 0.18, 3, 0.08, 0.08, [1, 2, 3])
      // a practice yard: a post with a straw target, and a weapon rack
      k.box(0.03, 0.28, 0.03, c - 0.32, 0, c + 0.36, P.wood); k.sph(0.06, c - 0.32, 0.26, c + 0.36, '#d9c27a', 1.2)
      k.box(0.24, 0.03, 0.04, c + 0.25, 0.22, c + 0.36, P.wood)
      for (let i = 0; i < 4; i++) k.box(0.012, 0.3, 0.012, c + 0.16 + i * 0.06, 0, c + 0.36, era >= 6 ? '#3a3a40' : '#8a8f96')
      k.cyl(0.008, 0.008, 0.55, c + 0.36, 0, c - 0.32, '#d9d9d9', 5)
      k.box(0.16, 0.1, 0.01, c + 0.44, 0.44, c - 0.32, P.trim)
      height = 0.75
      break
    }
    case 'road': default: {
      k.box(0.6, 0.02, 0.6, c, 0, c, '#8b6f4e')
      height = 0.1
    }
  }
  const body = mergeGeometries(k.parts, false)!
  body.computeVertexNormals()
  const glow = k.glow.length ? mergeGeometries(k.glow, false)! : null
  return { body, glow, smoke, light, anim, height, pivot }
}

const modelCache = new Map<string, ModelInfo>()
export function modelFor(type: BuildingType, era: number, tribe: string, variant: number): ModelInfo {
  const eb = era <= 1 ? 0 : era <= 3 ? 2 : era <= 5 ? 4 : era <= 7 ? 6 : 8
  const vv = type === 'monument' || type === 'skyscraper' || type === 'house' ? variant % 4 : 0
  const key = `${type}|${eb}|${tribe}|${vv}`
  let m = modelCache.get(key)
  if (!m) { m = build(type, eb, tribe, vv); modelCache.set(key, m) }
  return m
}

// ---------------------------------------------------------------- shared materials

export const bodyMat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.82, metalness: 0.02 })
export const glowMat = new THREE.MeshStandardMaterial({ color: '#1f2a36', emissive: new THREE.Color('#ffc46a'), emissiveIntensity: 0, roughness: 0.3, metalness: 0.2 })
export const flameMat = new THREE.MeshBasicMaterial({ color: new THREE.Color(4, 1.6, 0.35), transparent: true, opacity: 0.92, depthWrite: false })
export const flameCore = new THREE.MeshBasicMaterial({ color: new THREE.Color(6, 4.4, 1.6), transparent: true, opacity: 0.95, depthWrite: false })
export const scaffoldMat = new THREE.MeshStandardMaterial({ color: '#c9a36a', roughness: 0.8 })
const beaconMat = new THREE.MeshBasicMaterial({ color: new THREE.Color(5, 0.4, 0.3) })
const flameGeo = new THREE.ConeGeometry(0.1, 0.36, 7)
flameGeo.translate(0, 0.18, 0)
const sheepGeo = new THREE.SphereGeometry(0.07, 8, 6)
sheepGeo.scale(1, 0.8, 1.4)
const sheepHead = new THREE.SphereGeometry(0.035, 6, 5)
const cropGeo = (() => {
  const gs: THREE.BufferGeometry[] = []
  for (let r = 0; r < 5; r++) for (let k = 0; k < 7; k++) {
    const g = new THREE.ConeGeometry(0.035, 0.2, 4)
    g.translate(-0.39 + k * 0.13 + (r % 2) * 0.05, 0.1, -0.4 + r * 0.2)
    gs.push(g)
  }
  const m = mergeGeometries(gs, false)!
  m.computeVertexNormals()
  return m
})()
const cropMats = new Map<number, THREE.MeshStandardMaterial>()
function cropMat(g: number): THREE.MeshStandardMaterial {
  const q = Math.round(g * 8)
  let m = cropMats.get(q)
  if (!m) {
    const t = q / 8
    const c = t >= 1 ? new THREE.Color('#e2c44a') : new THREE.Color().setRGB((90 + t * 110) / 255, (150 + t * 50) / 255, (60 - t * 20) / 255, THREE.SRGBColorSpace)
    m = new THREE.MeshStandardMaterial({ color: c, roughness: 0.85 })
    cropMats.set(q, m)
  }
  return m
}
const rocketGeo = (() => {
  const gs = [new THREE.CylinderGeometry(0.12, 0.12, 1.6, 14), new THREE.ConeGeometry(0.12, 0.35, 14), new THREE.CylinderGeometry(0.16, 0.13, 0.3, 14)]
  gs[0].translate(0, 1.1, 0)
  gs[1].translate(0, 2.07, 0)
  gs[2].translate(0, 0.25, 0)
  const m = mergeGeometries(gs, false)!
  m.computeVertexNormals()
  return m
})()
const rocketMat = new THREE.MeshStandardMaterial({ color: '#f4f4f2', roughness: 0.4, metalness: 0.2 })

/** A live building in the scene. */
export class Building3D {
  root = new THREE.Group()
  private body: THREE.Mesh | null = null
  private glow: THREE.Mesh | null = null
  private extras = new THREE.Group()
  private scaffold: THREE.Group | null = null
  private key = ''
  info: ModelInfo | null = null
  private flames: THREE.Mesh[] = []
  private sails: THREE.Group | null = null
  private sheep: THREE.Group[] = []
  private crops: THREE.Mesh | null = null
  private beacon: THREE.Mesh | null = null
  rocket: THREE.Mesh | null = null
  private mover: THREE.Group | null = null
  b: Building
  constructor(b: Building) {
    this.b = b
    this.root.add(this.extras)
  }

  sync(era: number, tribe: string, ground: number, dockDir: number) {
    const b = this.b
    const def = BUILDINGS[b.type]
    const key = `${b.type}|${era}|${tribe}|${b.done}`
    this.root.position.set(b.x, ground, b.y)
    if (key !== this.key) {
      this.key = key
      this.info = modelFor(b.type, era, tribe, b.id)
      if (this.body) this.root.remove(this.body)
      if (this.glow) this.root.remove(this.glow)
      this.extras.clear()
      this.flames = []
      this.sails = null
      this.sheep = []
      this.crops = null
      this.beacon = null
      this.rocket = null
      this.mover = null
      this.body = new THREE.Mesh(this.info.body, bodyMat)
      this.body.castShadow = true
      this.body.receiveShadow = true
      this.root.add(this.body)
      if (this.info.glow) { this.glow = new THREE.Mesh(this.info.glow, glowMat); this.root.add(this.glow) } else this.glow = null
      const s = def.size, c = s / 2
      if (b.done) {
        if (this.info.anim === 'fire') {
          const y = b.type === 'watchtower' ? 1.42 : 0.05
          for (const [sx, sc] of [[0, 1], [0.05, 0.6], [-0.05, 0.7]] as const) {
            const f = new THREE.Mesh(flameGeo, sc === 1 ? flameMat : flameCore)
            f.position.set(c + sx, y, c + (sx ? 0.02 : 0))
            f.scale.setScalar(sc * (b.type === 'watchtower' ? 0.7 : 1))
            this.extras.add(f)
            this.flames.push(f)
          }
        }
        if (this.info.anim === 'mill') {
          this.sails = new THREE.Group()
          this.sails.position.set(c, 0.85, c + 0.33)
          const arm = new THREE.BoxGeometry(0.04, 0.62, 0.015)
          arm.translate(0, 0.33, 0)
          const sail = new THREE.BoxGeometry(0.13, 0.42, 0.01)
          sail.translate(0.075, 0.4, 0)
          for (let q = 0; q < 4; q++) {
            const g = new THREE.Group()
            g.rotation.z = (q * Math.PI) / 2
            g.add(new THREE.Mesh(arm, mat('#6b4a2b')))
            g.add(new THREE.Mesh(sail, mat('#efe7d6')))
            this.sails.add(g)
          }
          this.sails.traverse((o) => { if ((o as THREE.Mesh).isMesh) o.castShadow = true })
          this.extras.add(this.sails)
        }
        if (this.info.anim === 'herd') {
          for (let q = 0; q < 6; q++) {
            const g = new THREE.Group()
            const white = q % 3 !== 0
            const bm = new THREE.Mesh(sheepGeo, mat(white ? '#f2efe6' : '#5a4030'))
            bm.position.y = 0.09
            const hm = new THREE.Mesh(sheepHead, mat(white ? '#2a2420' : '#3a2a20'))
            hm.position.set(0, 0.11, 0.1)
            g.add(bm, hm)
            g.traverse((o) => { if ((o as THREE.Mesh).isMesh) o.castShadow = true })
            this.extras.add(g)
            this.sheep.push(g)
          }
        }
        if (this.info.anim === 'crops') {
          this.crops = new THREE.Mesh(cropGeo, cropMat(0))
          this.crops.position.set(c, 0, c)
          this.crops.castShadow = true
          this.extras.add(this.crops)
        }
        if (this.info.anim === 'beacon') {
          this.beacon = new THREE.Mesh(new THREE.SphereGeometry(0.04, 6, 5), beaconMat)
          this.beacon.position.set(c, this.info.height - 0.02, c)
          this.extras.add(this.beacon)
        }
        if (this.info.pivot && (this.info.anim === 'pump' || this.info.anim === 'wind' || this.info.anim === 'wheel' || this.info.anim === 'flare')) {
          const g = new THREE.Group()
          g.position.set(...this.info.pivot)
          if (this.info.anim === 'pump') {
            const beam = new THREE.Mesh(new THREE.BoxGeometry(0.62, 0.05, 0.06), mat('#2a2a30'))
            beam.position.x = 0.04
            const head = new THREE.Mesh(new THREE.CylinderGeometry(0.1, 0.1, 0.07, 12, 1, false, -Math.PI / 2, Math.PI), mat('#2a2a30'))
            head.rotation.x = Math.PI / 2
            head.position.set(0.34, -0.02, 0)
            const counter = new THREE.Mesh(new THREE.BoxGeometry(0.1, 0.12, 0.1), mat('#c0392b'))
            counter.position.set(-0.24, -0.08, 0)
            g.add(beam, head, counter)
          } else if (this.info.anim === 'wind') {
            // one rotor per turbine, all turning together
            for (const [x, z] of [[0.45, 0.45], [1.5, 0.6], [0.9, 1.5]]) {
              const hub = new THREE.Group()
              hub.position.set(x - 0.45, 0, z - 0.45 + 0.02)
              for (let q = 0; q < 3; q++) {
                const blade = new THREE.Mesh(new THREE.BoxGeometry(0.05, 0.62, 0.012), mat('#ffffff'))
                blade.position.y = 0.31
                const arm = new THREE.Group()
                arm.rotation.z = (q * Math.PI * 2) / 3
                arm.add(blade)
                hub.add(arm)
              }
              g.add(hub)
            }
          } else if (this.info.anim === 'wheel') {
            const wheel = new THREE.Mesh(new THREE.TorusGeometry(0.13, 0.015, 6, 18), mat(this.key.includes('|6|') || this.key.includes('|8|') ? '#5a6068' : '#6b4a2b'))
            wheel.rotation.y = Math.PI / 2
            g.add(wheel)
          } else {
            for (const sc of [1, 0.6]) {
              const f = new THREE.Mesh(flameGeo, sc === 1 ? flameMat : flameCore)
              f.scale.setScalar(sc * 0.8)
              g.add(f)
              this.flames.push(f)
            }
          }
          g.traverse((o) => { if ((o as THREE.Mesh).isMesh && this.info?.anim !== 'flare') o.castShadow = true })
          this.extras.add(g)
          this.mover = g
        }
        if (this.info.anim === 'rocket') {
          this.rocket = new THREE.Mesh(rocketGeo, rocketMat)
          this.rocket.position.set(c, 0.1, c)
          this.rocket.castShadow = true
          this.extras.add(this.rocket)
        }
      } else {
        this.scaffold = this.scaffold ?? makeScaffold(s, Math.max(0.4, this.info.height))
        this.extras.add(this.scaffold)
      }
      // docks face the water
      this.root.rotation.y = 0
      if (b.type === 'dock' || b.type === 'fish_farm' || b.type === 'harbour') {
        this.root.rotation.y = dockDir
      } else if (s === 1 && b.type !== 'farm' && b.type !== 'pasture' && b.type !== 'mill' && b.type !== 'mine' && b.type !== 'oil_well') {
        this.root.rotation.y = [0, Math.PI / 2, -Math.PI / 2, 0][Math.floor(hash2(b.x, b.y, 17) * 4)]
      }
      // rotate about the footprint centre
      if (this.root.rotation.y) {
        const c = s / 2
        const r = this.root.rotation.y
        this.root.position.x += c - (Math.cos(r) * c + Math.sin(r) * c)
        this.root.position.z += c - (-Math.sin(r) * c + Math.cos(r) * c)
      }
    } else if (this.root.rotation.y) {
      const c = def.size / 2, r = this.root.rotation.y
      this.root.position.x += c - (Math.cos(r) * c + Math.sin(r) * c)
      this.root.position.z += c - (-Math.sin(r) * c + Math.cos(r) * c)
    }
    if (!b.done && this.body) {
      const p = Math.max(0, Math.min(1, b.progress / def.hours))
      this.body.scale.y = 0.08 + p * 0.92
      if (this.glow) this.glow.visible = false
    }
  }

  update(t: number, night: number, crop: number) {
    const b = this.b
    if (this.glow) this.glow.visible = b.done
    for (let i = 0; i < this.flames.length; i++) {
      const f = this.flames[i]
      const k = 0.8 + Math.sin(t * 13 + i * 2 + b.id) * 0.12 + Math.sin(t * 7.3 + i) * 0.1
      const base = i === 0 ? 1 : 0.65
      f.scale.set(base * (0.9 + Math.sin(t * 9 + i) * 0.1), base * k * 1.1, base * (0.9 + Math.cos(t * 8 + i) * 0.1))
      f.rotation.y = t * (i ? -2 : 1.5)
    }
    if (this.sails) this.sails.rotation.z = -t * 1.3
    if (this.mover && this.info) {
      if (this.info.anim === 'pump') this.mover.rotation.z = Math.sin(t * 1.6 + b.id) * 0.32
      else if (this.info.anim === 'wind') for (const hub of this.mover.children) hub.rotation.z = -t * 2.2 - b.id
      else if (this.info.anim === 'wheel') for (const c of this.mover.children) c.rotation.x = t * 2.4
    }
    if (this.sheep.length) {
      const n = Math.min(6, Math.ceil(b.crop / 2))
      this.sheep.forEach((g, q) => {
        g.visible = q < n
        if (!g.visible) return
        const ph = t * 0.12 + q * 1.7 + b.id
        const x = 0.5 + Math.sin(ph) * 0.28 + Math.sin(ph * 0.37 + q) * 0.08
        const z = 0.5 + Math.cos(ph * 0.8 + q) * 0.28
        const nx = 0.5 + Math.sin(ph + 0.05) * 0.28, nz = 0.5 + Math.cos((ph + 0.05) * 0.8 + q) * 0.28
        g.position.set(x, 0, z)
        g.rotation.y = Math.atan2(nx - x, nz - z)
        g.children[1].position.y = Math.sin(t * 0.7 + q) > 0.3 ? 0.06 : 0.11
      })
    }
    if (this.crops) {
      this.crops.visible = b.planted
      this.crops.scale.y = 0.15 + crop * 0.95
      this.crops.material = cropMat(crop)
    }
    if (this.beacon) this.beacon.visible = night > 0.2 && Math.sin(t * 3 + b.id) > 0
  }

  dispose() {
    this.root.clear()
  }
}

function makeScaffold(s: number, h: number): THREE.Group {
  const g = new THREE.Group()
  const pole = new THREE.BoxGeometry(0.03, h, 0.03)
  pole.translate(0, h / 2, 0)
  const bar = new THREE.BoxGeometry(1, 0.025, 0.025)
  const m = scaffoldMat
  const e = 0.06, w = s - e * 2
  for (const [x, z] of [[e, e], [s - e, e], [e, s - e], [s - e, s - e]]) { const p = new THREE.Mesh(pole, m); p.position.set(x, 0, z); g.add(p) }
  for (let y = 0.2; y < h; y += 0.3) {
    for (const z of [e, s - e]) { const b = new THREE.Mesh(bar, m); b.scale.x = w; b.position.set(s / 2, y, z); g.add(b) }
    for (const x of [e, s - e]) { const b = new THREE.Mesh(bar, m); b.scale.x = w; b.rotation.y = Math.PI / 2; b.position.set(x, y, s / 2); g.add(b) }
  }
  g.traverse((o) => { if ((o as THREE.Mesh).isMesh) o.castShadow = true })
  return g
}

const smallMats = new Map<string, THREE.MeshStandardMaterial>()
function mat(c: string): THREE.MeshStandardMaterial {
  let m = smallMats.get(c)
  if (!m) { m = new THREE.MeshStandardMaterial({ color: c, roughness: 0.8 }); smallMats.set(c, m) }
  return m
}
