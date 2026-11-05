// Trees, bushes, rocks, ores and grass as instanced low-poly models. Every
// natural feature on the map gets a model with its own random size, lean and
// turn; canopies take seasonal colours (spring green, high summer, autumn fire,
// bare winter) and everything leafy sways in a shared wind.

import * as THREE from 'three'
import { mergeGeometries } from 'three/examples/jsm/utils/BufferGeometryUtils.js'
import { B, F } from '../sim/data.ts'
import { BUILDINGS } from '../sim/buildings.ts'
import { hash2 } from '../sim/rng.ts'
import type { World } from '../sim/world.ts'
import type { Heights } from './terrain.ts'

export const CH = 64 // tiles per flora chunk

/** Is there a building here that a tree's crown (or a person) must keep out of? */
export function solidAt(w: World, tx: number, ty: number): boolean {
  if (tx < 0 || ty < 0 || tx >= w.W || ty >= w.H) return false
  const o = w.occ[ty * w.W + tx]
  if (!o) return false
  const b = w.buildingById.get(o - 1)
  return !!b && !BUILDINGS[b.type].walkable && b.type !== 'mine'
}

/**
 * Where a tree stands in its tile, and how big it is (k = 0 the main tree, 1 a
 * smaller companion). Trees beside a building lean their spot away from it and
 * grow a little smaller, so no crown pokes through a wall or roof. The renderer
 * uses the same spots to keep people from walking through trunks.
 */
export function treeSpot(w: World, tx: number, ty: number, k: number): { x: number; z: number; s: number } {
  let ox = (hash2(tx, ty * 7 + k, 5) - 0.5) * 0.45, oz = (hash2(tx * 5 + k, ty, 6) - 0.5) * 0.45
  let s = 0.72 + hash2(tx * 3 + k, ty, 3) * 0.42 - (k === 1 ? 0.15 : 0)
  let crowded = false
  if (solidAt(w, tx + 1, ty)) { ox = Math.min(ox, 0) - 0.12; crowded = true }
  if (solidAt(w, tx - 1, ty)) { ox = Math.max(ox, 0) + 0.12; crowded = true }
  if (solidAt(w, tx, ty + 1)) { oz = Math.min(oz, 0) - 0.12; crowded = true }
  if (solidAt(w, tx, ty - 1)) { oz = Math.max(oz, 0) + 0.12; crowded = true }
  if (!crowded) for (const [dx, dy] of [[1, 1], [1, -1], [-1, 1], [-1, -1]]) if (solidAt(w, tx + dx, ty + dy)) { ox -= dx * 0.08; oz -= dy * 0.08; crowded = true }
  // right beside a wall only a sapling fits; on a corner, a smaller tree
  if (crowded) s *= solidAt(w, tx + 1, ty) || solidAt(w, tx - 1, ty) || solidAt(w, tx, ty + 1) || solidAt(w, tx, ty - 1) ? 0.5 : 0.75
  return { x: tx + 0.5 + Math.max(-0.3, Math.min(0.3, ox)), z: ty + 0.5 + Math.max(-0.3, Math.min(0.3, oz)), s }
}

type Tint = 'fixed' | 'canopy'
interface PartDef { geo: THREE.BufferGeometry; tint: Tint; sway: number; shadow: boolean }

// ---------------------------------------------------------------- geometry kit

function colored(g: THREE.BufferGeometry, color: string, jitter = 0.08, seed = 1): THREE.BufferGeometry {
  const ng = g.index ? g.toNonIndexed() : g
  ng.deleteAttribute('uv')
  const n = ng.getAttribute('position').count
  const c = new THREE.Color(color)
  const arr = new Float32Array(n * 3)
  for (let k = 0; k < n; k += 3) {
    const v = 1 + (hash2(k, seed, 9) - 0.5) * jitter * 2
    for (let q = 0; q < 3 && k + q < n; q++) { arr[(k + q) * 3] = c.r * v; arr[(k + q) * 3 + 1] = c.g * v; arr[(k + q) * 3 + 2] = c.b * v }
  }
  ng.setAttribute('color', new THREE.BufferAttribute(arr, 3))
  return ng
}
function at(g: THREE.BufferGeometry, x: number, y: number, z: number, sx = 1, sy = 1, sz = 1, rx = 0, ry = 0, rz = 0): THREE.BufferGeometry {
  g.scale(sx, sy, sz)
  if (rx) g.rotateX(rx)
  if (ry) g.rotateY(ry)
  if (rz) g.rotateZ(rz)
  g.translate(x, y, z)
  return g
}
/** Squash and wobble the vertices of a blob so it reads as foliage, not a ball. */
function lumpy(g: THREE.BufferGeometry, amt: number, seed: number): THREE.BufferGeometry {
  const p = g.getAttribute('position') as THREE.BufferAttribute
  for (let k = 0; k < p.count; k++) {
    const x = p.getX(k), y = p.getY(k), z = p.getZ(k)
    const n = 1 + (hash2(Math.round(x * 50) + 99, Math.round(y * 50) * 7 + Math.round(z * 50) * 13, seed) - 0.5) * amt
    p.setXYZ(k, x * n, y * n, z * n)
  }
  return g
}
const merge = (gs: THREE.BufferGeometry[]) => {
  const m = mergeGeometries(gs, false)!
  m.computeVertexNormals()
  return m
}
const flat = (gs: THREE.BufferGeometry[]) => {
  // faceted look: recompute per-face normals on a non-indexed mesh
  const m = mergeGeometries(gs, false)!
  m.computeVertexNormals()
  return m
}

const LEAF = '#ffffff' // canopies are white; the instance colour paints them

function oakParts(): PartDef[] {
  const trunk = merge([
    colored(at(new THREE.CylinderGeometry(0.05, 0.09, 0.8, 7), 0, 0.4, 0), '#6b4a2e'),
    colored(at(new THREE.CylinderGeometry(0.025, 0.045, 0.4, 5), 0.1, 0.75, 0, 1, 1, 1, 0, 0, -0.7), '#6b4a2e'),
    colored(at(new THREE.CylinderGeometry(0.02, 0.04, 0.35, 5), -0.08, 0.8, 0.05, 1, 1, 1, 0.3, 0, 0.8), '#6b4a2e'),
  ])
  const canopy = flat([
    colored(lumpy(at(new THREE.IcosahedronGeometry(0.38, 0), 0, 1.1, 0, 1, 0.85, 1), 0.25, 1), LEAF, 0.12, 1),
    colored(lumpy(at(new THREE.IcosahedronGeometry(0.28, 0), 0.22, 0.95, 0.1, 1, 0.85, 1), 0.25, 2), LEAF, 0.12, 2),
    colored(lumpy(at(new THREE.IcosahedronGeometry(0.27, 0), -0.2, 0.98, -0.08, 1, 0.85, 1), 0.25, 3), LEAF, 0.12, 3),
    colored(lumpy(at(new THREE.IcosahedronGeometry(0.24, 0), 0.02, 1.38, 0.05, 1, 0.8, 1), 0.25, 4), LEAF, 0.12, 4),
  ])
  return [{ geo: trunk, tint: 'fixed', sway: 0.3, shadow: true }, { geo: canopy, tint: 'canopy', sway: 1, shadow: true }]
}
function pineParts(): PartDef[] {
  const trunk = merge([colored(at(new THREE.CylinderGeometry(0.04, 0.07, 0.5, 6), 0, 0.25, 0), '#5a3d26')])
  const tiers: THREE.BufferGeometry[] = []
  for (let k = 0; k < 4; k++) {
    const r = 0.42 - k * 0.09, h = 0.55 - k * 0.06
    tiers.push(colored(lumpy(at(new THREE.ConeGeometry(r, h, 8, 1), 0, 0.5 + k * 0.33, 0), 0.12, k + 10), LEAF, 0.1, k + 10))
  }
  return [{ geo: trunk, tint: 'fixed', sway: 0.2, shadow: true }, { geo: flat(tiers), tint: 'canopy', sway: 0.7, shadow: true }]
}
function palmParts(): PartDef[] {
  const segs: THREE.BufferGeometry[] = []
  for (let k = 0; k < 6; k++) segs.push(colored(at(new THREE.CylinderGeometry(0.045, 0.055, 0.24, 6), k * k * 0.006, 0.12 + k * 0.22, 0, 1, 1, 1, 0, 0, -k * 0.04), k % 2 ? '#8a6a44' : '#9b7a50'))
  const fronds: THREE.BufferGeometry[] = []
  for (let k = 0; k < 7; k++) {
    const a = (k / 7) * Math.PI * 2
    const f = new THREE.ConeGeometry(0.09, 0.75, 3, 1)
    f.scale(1, 1, 0.25)
    f.translate(0, 0.37, 0)
    f.rotateZ(-1.15)
    f.rotateY(a)
    f.translate(0.2, 1.38, 0)
    fronds.push(colored(f, LEAF, 0.1, k + 20))
  }
  fronds.push(colored(at(new THREE.SphereGeometry(0.06, 6, 5), 0.2, 1.33, 0.05), '#6b4a2b'))
  return [{ geo: merge(segs), tint: 'fixed', sway: 0.4, shadow: true }, { geo: merge(fronds), tint: 'canopy', sway: 1.1, shadow: true }]
}
function jungleParts(): PartDef[] {
  const trunk = merge([
    colored(at(new THREE.CylinderGeometry(0.06, 0.11, 1.4, 7), 0, 0.7, 0), '#5c4632'),
    colored(at(new THREE.ConeGeometry(0.2, 0.25, 6), 0, 0.1, 0), '#5c4632'),
  ])
  const canopy = flat([
    colored(lumpy(at(new THREE.IcosahedronGeometry(0.5, 0), 0, 1.55, 0, 1.2, 0.5, 1.2), 0.3, 31), LEAF, 0.12, 31),
    colored(lumpy(at(new THREE.IcosahedronGeometry(0.32, 0), 0.3, 1.4, 0.2, 1, 0.6, 1), 0.3, 32), LEAF, 0.12, 32),
    colored(lumpy(at(new THREE.IcosahedronGeometry(0.3, 0), -0.3, 1.45, -0.15, 1, 0.6, 1), 0.3, 33), LEAF, 0.12, 33),
  ])
  return [{ geo: trunk, tint: 'fixed', sway: 0.2, shadow: true }, { geo: canopy, tint: 'canopy', sway: 0.8, shadow: true }]
}
function bushParts(): PartDef[] {
  const body = flat([
    colored(lumpy(at(new THREE.IcosahedronGeometry(0.22, 1), 0, 0.17, 0, 1.2, 0.8, 1), 0.3, 41), LEAF, 0.14, 41),
    colored(lumpy(at(new THREE.IcosahedronGeometry(0.16, 1), 0.17, 0.12, 0.06, 1, 0.8, 1), 0.3, 42), LEAF, 0.14, 42),
    colored(lumpy(at(new THREE.IcosahedronGeometry(0.15, 1), -0.16, 0.12, -0.05, 1, 0.8, 1), 0.3, 43), LEAF, 0.14, 43),
  ])
  const berries: THREE.BufferGeometry[] = []
  for (let k = 0; k < 9; k++) {
    const a = k * 2.4, r = 0.18 + (k % 3) * 0.04
    berries.push(colored(at(new THREE.SphereGeometry(0.03, 5, 4), Math.cos(a) * r, 0.12 + (k % 4) * 0.05, Math.sin(a) * r * 0.8), k % 2 ? '#c2185b' : '#8e24aa'))
  }
  return [{ geo: body, tint: 'canopy', sway: 0.5, shadow: true }, { geo: merge(berries), tint: 'fixed', sway: 0.5, shadow: false }]
}
function rockParts(color = '#8d8a84', ore?: string): PartDef[] {
  const gs = [
    colored(lumpy(at(new THREE.DodecahedronGeometry(0.26, 0), 0, 0.12, 0, 1.2, 0.75, 1), 0.3, 51), color, 0.1, 51),
    colored(lumpy(at(new THREE.DodecahedronGeometry(0.16, 0), 0.24, 0.07, 0.12, 1, 0.8, 1), 0.3, 52), color, 0.1, 52),
    colored(lumpy(at(new THREE.DodecahedronGeometry(0.12, 0), -0.22, 0.05, -0.1, 1, 0.7, 1), 0.3, 53), color, 0.1, 53),
  ]
  if (ore) for (let k = 0; k < 6; k++) {
    const a = k * 1.9
    gs.push(colored(at(new THREE.OctahedronGeometry(0.055, 0), Math.cos(a) * 0.18, 0.15 + (k % 3) * 0.05, Math.sin(a) * 0.15, 1, 1.4, 1, 0.3 * k, k, 0), ore, 0.05, 60 + k))
  }
  return [{ geo: flat(gs), tint: 'fixed', sway: 0, shadow: true }]
}
function clayParts(): PartDef[] {
  return [{ geo: flat([
    colored(lumpy(at(new THREE.SphereGeometry(0.22, 8, 5), 0, 0.02, 0, 1.3, 0.35, 1), 0.3, 71), '#a65f3f', 0.1, 71),
    colored(lumpy(at(new THREE.SphereGeometry(0.15, 7, 5), 0.22, 0.02, 0.1, 1, 0.4, 1), 0.3, 72), '#b86f4a', 0.1, 72),
  ]), tint: 'fixed', sway: 0, shadow: false }]
}
function oilParts(): PartDef[] {
  return [{ geo: merge([
    colored(at(new THREE.CylinderGeometry(0.3, 0.32, 0.02, 14), 0, 0.01, 0, 1.2, 1, 0.9), '#141018'),
    colored(at(new THREE.CylinderGeometry(0.1, 0.1, 0.03, 10), 0.12, 0.02, 0.05), '#2a2230'),
  ]), tint: 'fixed', sway: 0, shadow: false }]
}
function fiberParts(): PartDef[] {
  const gs: THREE.BufferGeometry[] = []
  for (let k = 0; k < 11; k++) {
    const a = k * 2.3, r = (k % 4) * 0.06
    gs.push(colored(at(new THREE.ConeGeometry(0.025, 0.5 + (k % 3) * 0.12, 3), Math.cos(a) * r, 0.25, Math.sin(a) * r, 1, 1, 1, Math.cos(a) * 0.25, 0, Math.sin(a) * 0.25), LEAF, 0.15, 80 + k))
  }
  return [{ geo: merge(gs), tint: 'canopy', sway: 1.4, shadow: false }]
}
function cactusParts(): PartDef[] {
  return [{ geo: merge([
    colored(at(new THREE.CapsuleGeometry(0.08, 0.55, 3, 8), 0, 0.36, 0), '#4f8a3c'),
    colored(at(new THREE.CapsuleGeometry(0.05, 0.18, 3, 6), 0.14, 0.42, 0), '#4f8a3c'),
    colored(at(new THREE.CapsuleGeometry(0.045, 0.14, 3, 6), -0.13, 0.5, 0), '#4f8a3c'),
    colored(at(new THREE.CapsuleGeometry(0.045, 0.1, 3, 6), 0.1, 0.32, 0, 1, 1, 1, 0, 0, 1.4), '#4f8a3c'),
    colored(at(new THREE.CapsuleGeometry(0.04, 0.09, 3, 6), -0.09, 0.4, 0, 1, 1, 1, 0, 0, -1.4), '#4f8a3c'),
  ]), tint: 'fixed', sway: 0, shadow: true }]
}
function saplingParts(): PartDef[] {
  return [
    { geo: merge([colored(at(new THREE.CylinderGeometry(0.015, 0.025, 0.35, 5), 0, 0.17, 0), '#6b4a2e')]), tint: 'fixed', sway: 0.6, shadow: false },
    { geo: flat([colored(lumpy(at(new THREE.IcosahedronGeometry(0.13, 1), 0, 0.4, 0), 0.3, 91), LEAF, 0.1, 91)]), tint: 'canopy', sway: 1.2, shadow: true },
  ]
}
function flowerParts(): PartDef[] {
  const gs: THREE.BufferGeometry[] = []
  const cols = ['#ffd54f', '#f06292', '#ffffff', '#ba68c8', '#ff8a65', '#4fc3f7']
  for (let k = 0; k < 9; k++) {
    const a = k * 2.1, r = 0.08 + (k % 3) * 0.1
    const x = Math.cos(a) * r, z = Math.sin(a) * r
    gs.push(colored(at(new THREE.CylinderGeometry(0.006, 0.006, 0.16, 3), x, 0.08, z), '#4c7a2c'))
    gs.push(colored(at(new THREE.IcosahedronGeometry(0.035, 0), x, 0.17, z, 1, 0.6, 1), cols[k % cols.length], 0.05))
  }
  return [{ geo: merge(gs), tint: 'fixed', sway: 1, shadow: false }]
}
function grassParts(): PartDef[] {
  const gs: THREE.BufferGeometry[] = []
  for (let k = 0; k < 7; k++) {
    const a = k * 2.6, r = (k % 3) * 0.05
    gs.push(colored(at(new THREE.ConeGeometry(0.018, 0.22 + (k % 3) * 0.06, 3), Math.cos(a) * r, 0.11, Math.sin(a) * r, 1, 1, 0.5, Math.cos(a) * 0.35, a, Math.sin(a) * 0.35), LEAF, 0.15, 100 + k))
  }
  return [{ geo: merge(gs), tint: 'canopy', sway: 1.6, shadow: false }]
}

const ORE: Record<number, [string, string]> = {
  [F.COPPER]: ['#8a7f75', '#d9774a'],
  [F.TIN]: ['#8f8f93', '#d8dde3'],
  [F.IRON]: ['#7d7671', '#a2462e'],
  [F.COAL]: ['#6f6c6a', '#1c1b1f'],
  [F.GOLD]: ['#8f877a', '#ffd34a'],
}

const GRASS = 100 // pseudo-feature id for decorative grass

let KINDS: Map<number, PartDef[]> | null = null
function kinds(): Map<number, PartDef[]> {
  if (KINDS) return KINDS
  KINDS = new Map<number, PartDef[]>([
    [F.TREE, oakParts()], [F.PINE, pineParts()], [F.PALM, palmParts()], [F.JUNGLE, jungleParts()],
    [F.BUSH, bushParts()], [F.ROCK, rockParts()], [F.CLAY, clayParts()], [F.OIL, oilParts()],
    [F.FIBER, fiberParts()], [F.CACTUS, cactusParts()], [F.SAPLING, saplingParts()], [F.FLOWERS, flowerParts()],
    [GRASS, grassParts()],
  ])
  for (const [id, [rock, ore]] of Object.entries(ORE)) KINDS.set(+id, rockParts(rock, ore))
  return KINDS
}

// ---------------------------------------------------------------- materials

export const wind = {
  uTime: { value: 0 }, uWind: { value: 1 },
  // the see-through window: leaves between the eye and what it looks at dissolve
  uCam: { value: new THREE.Vector3() }, uLook: { value: new THREE.Vector3() }, uCut: { value: 0 },
}

function swayMaterial(sway: number, instTint: boolean): THREE.MeshStandardMaterial {
  const m = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.88, metalness: 0 })
  if (!instTint) m.color.set('#ffffff')
  if (sway > 0) {
    m.onBeforeCompile = (sh) => {
      sh.uniforms.uTime = wind.uTime
      sh.uniforms.uWind = wind.uWind
      sh.uniforms.uCam = wind.uCam
      sh.uniforms.uLook = wind.uLook
      sh.uniforms.uCut = wind.uCut
      sh.fragmentShader = 'uniform vec3 uCam;\nuniform vec3 uLook;\nuniform float uCut;\nvarying vec3 vXr;\n' + sh.fragmentShader.replace('#include <clipping_planes_fragment>', `#include <clipping_planes_fragment>
        if (uCut > 0.0) {
          vec3 seg = uLook - uCam;
          float L = length(seg);
          vec3 dir = seg / L;
          float t = dot(vXr - uCam, dir);
          if (t > 0.0 && t < L - 0.25) {
            float d = length(vXr - (uCam + dir * t));
            float r = uCut * mix(0.45, 1.0, t / L);
            // a clean round window with a fine ordered-dither rim, not a speckle
            float k = 1.0 - smoothstep(r * 0.8, r, d);
            vec2 q = mod(floor(gl_FragCoord.xy), 4.0);
            float bayer = (mod(q.x + q.y * 2.0, 4.0) * 4.0 + mod(q.x * 2.0 + q.y, 4.0)) / 16.0;
            if (k > 0.97 || bayer < k) discard;
          }
        }`)
      sh.vertexShader = 'uniform float uTime;\nuniform float uWind;\nvarying vec3 vXr;\n' + sh.vertexShader.replace('#include <begin_vertex>', `#include <begin_vertex>
        #ifdef USE_INSTANCING
          vec3 ip = vec3(instanceMatrix[3][0], instanceMatrix[3][1], instanceMatrix[3][2]);
        #else
          vec3 ip = vec3(0.0);
        #endif
        float sw = ${sway.toFixed(2)} * uWind * max(0.0, transformed.y) * max(0.0, transformed.y);
        float ph = uTime * (1.3 + fract(ip.x * 0.37) * 0.6) + ip.x * 0.31 + ip.z * 0.23;
        transformed.x += (sin(ph) * 0.05 + sin(ph * 2.7) * 0.015) * sw;
        transformed.z += (cos(ph * 0.8) * 0.035) * sw;`).replace('#include <project_vertex>', `#include <project_vertex>
        #ifdef USE_INSTANCING
          vXr = (modelMatrix * instanceMatrix * vec4(transformed, 1.0)).xyz;
        #else
          vXr = (modelMatrix * vec4(transformed, 1.0)).xyz;
        #endif`)
    }
    m.customProgramCacheKey = () => 'sway' + sway.toFixed(2)
  }
  return m
}
const matCache = new Map<string, THREE.MeshStandardMaterial>()
function partMat(p: PartDef): THREE.MeshStandardMaterial {
  const key = `${p.sway}|${p.tint}`
  let m = matCache.get(key)
  if (!m) { m = swayMaterial(p.sway, p.tint === 'canopy'); matCache.set(key, m) }
  return m
}

// ---------------------------------------------------------------- seasonal colour

function canopyColor(f: number, season: number, h: number, biome: number, out: THREE.Color): THREE.Color {
  const pick = (cs: string[]) => out.set(cs[Math.floor(h * cs.length) % cs.length])
  if (f === F.PINE) {
    pick(['#1f4a2a', '#1b4226', '#255232'])
    if (season === 3) out.lerp(new THREE.Color('#e8eef2'), 0.35)
    return out
  }
  if (f === F.PALM || f === F.JUNGLE) return pick(['#2f7a28', '#3a862e', '#286e24'])
  if (f === F.FIBER) return pick(season === 3 ? ['#a89a70', '#9a8c64'] : season === 2 ? ['#c9b26a', '#b9a35e'] : ['#a8b85a', '#b7c464', '#9cae52'])
  if (f === GRASS) {
    if (biome === B.SAVANNA || biome === B.DESERT) return pick(['#b9ae62', '#c4b56c', '#a99f58'])
    return pick(season === 3 ? ['#6e7050', '#646a4a'] : season === 2 ? ['#948c3a', '#868436'] : season === 0 ? ['#559a30', '#62a638', '#4c8f2c'] : ['#4a8a2e', '#539434', '#5e9636'])
  }
  // broadleaf trees, bushes and saplings
  if (season === 2) return pick(['#c4561c', '#d48a1e', '#a2311c', '#b8701c', '#7a8222'])
  if (season === 3) return pick(['#5e4e3e', '#544838', '#6a5a46'])
  if (season === 0) return pick(['#4f9a2e', '#5aa634', '#428a28', '#66ad3a'])
  return pick(['#357a26', '#2c6c22', '#3f852c', '#336f26'])
}

// ---------------------------------------------------------------- the forest

interface Chunk { meshes: THREE.InstancedMesh[]; season: number }

export class Flora {
  group = new THREE.Group()
  private w: World
  private hs: Heights
  private chunks = new Map<number, Chunk>()
  private dirty = new Set<number>()
  private nx: number
  private ny: number
  constructor(w: World, hs: Heights) {
    this.w = w
    this.hs = hs
    this.nx = Math.ceil(w.W / CH)
    this.ny = Math.ceil(w.H / CH)
    for (let cy = 0; cy < this.ny; cy++) for (let cx = 0; cx < this.nx; cx++) this.dirty.add(cy * 256 + cx)
  }

  /** The world marks 32-tile chunks dirty; translate to our 64-tile chunks. */
  markDirty32(key32: number) {
    const cx = (key32 & 255) >> 1, cy = (key32 >> 8) >> 1
    this.dirty.add(cy * 256 + cx)
  }
  markAll() {
    for (let cy = 0; cy < this.ny; cy++) for (let cx = 0; cx < this.nx; cx++) this.dirty.add(cy * 256 + cx)
  }

  /** Rebuild at most `budget` dirty chunks, nearest to the camera first. */
  update(budget: number, camX: number, camZ: number) {
    if (!this.dirty.size) return
    const list = [...this.dirty].sort((a, b) => {
      const da = Math.hypot(((a & 255) + 0.5) * CH - camX, ((a >> 8) + 0.5) * CH - camZ)
      const db = Math.hypot(((b & 255) + 0.5) * CH - camX, ((b >> 8) + 0.5) * CH - camZ)
      return da - db
    })
    for (const key of list.slice(0, budget)) {
      this.dirty.delete(key)
      this.build(key & 255, key >> 8)
    }
  }

  private build(cx: number, cy: number) {
    const key = cy * 256 + cx
    const old = this.chunks.get(key)
    if (old) for (const m of old.meshes) { this.group.remove(m); m.dispose() }
    const w = this.w, hs = this.hs
    const season = w.season
    const K = kinds()
    const items = new Map<number, { x: number; z: number; y: number; s: number; r: number; h: number; lean: number; tx: number; ty: number; empty: boolean; biome: number }[]>()
    const x0 = cx * CH, y0 = cy * CH
    const x1 = Math.min(w.W, x0 + CH), y1 = Math.min(w.H, y0 + CH)
    const push = (f: number, tx: number, ty: number, k: number) => {
      const i = ty * w.W + tx
      const h = hash2(tx * 3 + k, ty, 3)
      const big = f >= F.TREE && f <= F.JUNGLE
      let x: number, z: number, sz: number
      if (big) { const sp = treeSpot(w, tx, ty, k); x = sp.x; z = sp.z; sz = sp.s }
      else {
        x = tx + 0.5 + (hash2(tx, ty * 7 + k, 5) - 0.5) * 0.45; z = ty + 0.5 + (hash2(tx * 5 + k, ty, 6) - 0.5) * 0.45
        sz = 0.8 + h * 0.5
      }
      let arr = items.get(f)
      if (!arr) { arr = []; items.set(f, arr) }
      arr.push({ x, z, y: hs.at(x, z), s: sz, r: hash2(tx, ty, 8 + k) * Math.PI * 2, h, lean: (hash2(tx, ty, 12) - 0.5) * 0.12, tx, ty, empty: (f === F.BUSH || f === F.FIBER) && w.amount[i] === 0, biome: w.biome[i] })
    }
    for (let ty = y0; ty < y1; ty++) for (let tx = x0; tx < x1; tx++) {
      const i = ty * w.W + tx
      const f = w.feature[i]
      const occ = w.occ[i]
      if (occ) { const b = w.buildingById.get(occ - 1); if (b && b.type !== 'mine') continue }
      if (w.road[i]) continue
      if (f) {
        push(f, tx, ty, 0)
        // forests are dense: a second, smaller tree shares many tiles
        if (f >= F.TREE && f <= F.JUNGLE && hash2(tx, ty, 41) > 0.72) push(f, tx, ty, 1)
        if ((f === F.TREE || f === F.PINE) && hash2(tx, ty, 44) > 0.6) push(GRASS, tx, ty, 2)
      } else {
        const b = w.biome[i]
        if (b === B.GRASSLAND || b === B.SAVANNA || b === B.FOREST || b === B.MARSH || b === B.HILLS || b === B.TAIGA || b === B.RAINFOREST) {
          if (season === 3 && w.temp[i] < 0.35) continue
          const dens = b === B.SAVANNA ? 0.55 : b === B.HILLS ? 0.35 : 0.3 + w.moist[i] * 0.45
          if (hash2(tx, ty, 47) < dens) push(GRASS, tx, ty, 3)
          if (hash2(tx, ty, 48) < dens * 0.5) push(GRASS, tx, ty, 4)
          if ((b === B.GRASSLAND || b === B.FOREST) && season <= 1 && hash2(tx, ty, 49) < 0.03) push(F.FLOWERS, tx, ty, 5)
        }
        if ((b === B.DESERT || b === B.BEACH || b === B.TUNDRA || b === B.MOUNTAIN) && hash2(tx, ty, 50) < 0.04) push(F.ROCK, tx, ty, 6)
      }
    }
    const meshes: THREE.InstancedMesh[] = []
    const m4 = new THREE.Matrix4(), q = new THREE.Quaternion(), e = new THREE.Euler(), v = new THREE.Vector3(), sc = new THREE.Vector3()
    const col = new THREE.Color()
    for (const [f, arr] of items) {
      const parts = K.get(f)
      if (!parts) continue
      for (const p of parts) {
        // berries vanish when picked; bare winter oaks keep their bones
        const list = p.tint === 'fixed' && f === F.BUSH ? arr.filter((a) => !a.empty && season !== 3) : arr
        if (!list.length) continue
        const im = new THREE.InstancedMesh(p.geo, partMat(p), list.length)
        list.forEach((a, k) => {
          let s = a.s
          if (f === GRASS || f === F.ROCK) s *= 0.8 + a.h * 0.5
          // grass knee-high, bushes chest-high, reeds just over head height
          if (f === GRASS) s *= 0.45
          else if (f === F.FLOWERS) s *= 0.55
          else if (f === F.BUSH) s *= 0.7
          else if (f === F.FIBER) s *= 0.6
          // decorative rocks scattered on bare ground are small
          if (f === F.ROCK && a.biome !== undefined && w.feature[a.ty * w.W + a.tx] !== F.ROCK) s *= 0.5
          let sx = s, sy = s, sz = s
          if (p.tint === 'canopy' && f === F.TREE && season === 3) { sx *= 0.55; sy *= 0.5; sz *= 0.55 }
          if (f === F.FIBER && a.empty) sy *= 0.45
          e.set(a.lean, a.r, a.lean * 0.7)
          q.setFromEuler(e)
          v.set(a.x, a.y - 0.02, a.z)
          sc.set(sx, sy, sz)
          m4.compose(v, q, sc)
          im.setMatrixAt(k, m4)
          if (p.tint === 'canopy') canopyColor(f, season, a.h, a.biome, col)
          else col.set('#ffffff')
          im.setColorAt(k, col)
        })
        im.instanceMatrix.needsUpdate = true
        if (im.instanceColor) im.instanceColor.needsUpdate = true
        im.castShadow = p.shadow
        im.receiveShadow = f !== GRASS
        im.computeBoundingSphere()
        im.userData.small = f === GRASS || f === F.FLOWERS
        meshes.push(im)
        this.group.add(im)
      }
    }
    this.chunks.set(key, { meshes, season })
  }

  /** Grass and flowers only matter up close. */
  setDetail(near: boolean) {
    for (const c of this.chunks.values()) for (const m of c.meshes) if (m.userData.small) m.visible = near
  }

  dispose() {
    for (const c of this.chunks.values()) for (const m of c.meshes) m.dispose()
    this.chunks.clear()
    this.group.clear()
  }
}
