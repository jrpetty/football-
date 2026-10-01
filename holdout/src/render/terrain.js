// Ground rendering: a splat-mapped terrain shader that blends grass, packed
// dirt, gravel, mud and forest floor with anti-tiling, wind-swept instanced
// grass, and instanced scatter (trees, bushes, rocks) with swaying foliage.
import * as THREE from 'three'
import { texSet } from './texgen.js'
import { mat } from './materials.js'
import { grassTuftGeometry } from '../models/nature.js'

export const wind = { time: { value: 0 }, strength: { value: 1 } }

// ---------------------------------------------------------------- splat map
// Channels: 0 dirt, 1 gravel, 2 mud/worn earth, 3 forest floor. Grass = 1 − sum.
export class Splat {
  constructor(x0, z0, size, res = 0.5) {
    this.x0 = x0
    this.z0 = z0
    this.size = size
    this.res = res
    this.n = Math.round(size / res)
    this.data = new Float32Array(this.n * this.n * 5)
    this.clear()
    this.bytes = new Uint8Array(this.n * this.n * 4)
    this.tex = new THREE.DataTexture(this.bytes, this.n, this.n, THREE.RGBAFormat)
    this.tex.magFilter = THREE.LinearFilter
    this.tex.minFilter = THREE.LinearFilter
    this.tex.wrapS = this.tex.wrapT = THREE.ClampToEdgeWrapping
    this.tex.needsUpdate = true
    this.seed = 1
  }
  clear() {
    const d = this.data
    for (let i = 0; i < d.length; i += 5) {
      d[i] = d[i + 1] = d[i + 2] = d[i + 3] = 0
      d[i + 4] = 1
    }
  }
  // Blend texel i toward "channel ch at weight v" with alpha a, keeping the
  // other layers in proportion so the weights always sum to one.
  // ch = -1 paints grass back.
  apply(i, ch, v, a) {
    const d = this.data
    const j = i * 5
    const c = ch < 0 ? 4 : ch
    const cur = d[j + c]
    const rest = 1 - cur
    for (let k = 0; k < 5; k++) {
      const target = k === c ? v : rest > 1e-5 ? (d[j + k] * (1 - v)) / rest : k === 4 ? 1 - v : 0
      d[j + k] += (target - d[j + k]) * a
    }
  }
  noiseAt(x, z) {
    // cheap smooth noise for ragged edges
    return Math.sin(x * 1.31 + Math.sin(z * 0.73) * 2) * 0.5 + Math.sin(z * 1.17 + Math.sin(x * 0.61) * 2) * 0.5
  }
  // Signed-distance painters. feather in metres, jitter adds ragged edges.
  paint(x0, z0, x1, z1, distFn, ch, v, feather = 1, jitter = 0.6) {
    const n = this.n
    const r = this.res
    const i0 = Math.max(0, Math.floor((x0 - feather - jitter - this.x0) / r))
    const i1 = Math.min(n - 1, Math.ceil((x1 + feather + jitter - this.x0) / r))
    const j0 = Math.max(0, Math.floor((z0 - feather - jitter - this.z0) / r))
    const j1 = Math.min(n - 1, Math.ceil((z1 + feather + jitter - this.z0) / r))
    for (let j = j0; j <= j1; j++) {
      const z = this.z0 + (j + 0.5) * r
      for (let i = i0; i <= i1; i++) {
        const x = this.x0 + (i + 0.5) * r
        const dd = distFn(x, z) + this.noiseAt(x, z) * jitter
        if (dd >= feather) continue
        const a = dd <= 0 ? 1 : 1 - dd / feather
        this.apply(j * n + i, ch, v, a * a * (3 - 2 * a))
      }
    }
  }
  rect(x0, z0, x1, z1, ch, v = 1, feather = 1, jitter = 0.6) {
    this.paint(x0, z0, x1, z1, (x, z) => Math.max(x0 - x, x - x1, z0 - z, z - z1), ch, v, feather, jitter)
  }
  circle(cx, cz, rad, ch, v = 1, feather = 1, jitter = 0.4) {
    this.paint(cx - rad, cz - rad, cx + rad, cz + rad, (x, z) => Math.hypot(x - cx, z - cz) - rad, ch, v, feather, jitter)
  }
  line(pts, width, ch, v = 1, feather = 1, jitter = 0.4) {
    for (let k = 0; k < pts.length - 1; k++) {
      const [ax, az] = pts[k]
      const [bx, bz] = pts[k + 1]
      const dx = bx - ax
      const dz = bz - az
      const L2 = dx * dx + dz * dz || 1
      this.paint(Math.min(ax, bx) - width, Math.min(az, bz) - width, Math.max(ax, bx) + width, Math.max(az, bz) + width, (x, z) => {
        const t = Math.max(0, Math.min(1, ((x - ax) * dx + (z - az) * dz) / L2))
        return Math.hypot(x - (ax + dx * t), z - (az + dz * t)) - width / 2
      }, ch, v, feather, jitter)
    }
  }
  // Weight of grass at a world point (bilinear-ish via nearest texel).
  grassAt(x, z) {
    const i = Math.floor((x - this.x0) / this.res)
    const j = Math.floor((z - this.z0) / this.res)
    if (i < 0 || j < 0 || i >= this.n || j >= this.n) return 1
    return this.data[(j * this.n + i) * 5 + 4]
  }
  channelAt(x, z, ch) {
    const i = Math.floor((x - this.x0) / this.res)
    const j = Math.floor((z - this.z0) / this.res)
    if (i < 0 || j < 0 || i >= this.n || j >= this.n) return 0
    return this.data[(j * this.n + i) * 5 + ch]
  }
  upload() {
    const d = this.data
    const b = this.bytes
    const n = this.n * this.n
    for (let i = 0; i < n; i++) for (let k = 0; k < 4; k++) b[i * 4 + k] = Math.max(0, Math.min(255, Math.round(d[i * 5 + k] * 255)))
    this.tex.needsUpdate = true
  }
}

// ---------------------------------------------------------------- terrain material
const TERRAIN_FRAG_PARS = /* glsl */ `
uniform sampler2D tSplat;
uniform vec4 uSplatRect;
uniform sampler2D tGrass;
uniform sampler2D tGrassN;
uniform sampler2D tDirt;
uniform sampler2D tDirtN;
uniform sampler2D tGravel;
uniform sampler2D tGravelN;
uniform sampler2D tForest;
uniform sampler2D tForestN;
uniform sampler2D tNoise;
uniform vec3 uLush;
uniform vec3 uDry;
uniform vec3 uDirtTint;
uniform vec3 uMudTint;
uniform float uWet;
varying vec3 vTW;
varying vec3 vTN;
vec4 tw4;
float twG;
vec2 trot(vec2 p, float a) { float s = sin(a), c = cos(a); return vec2(c * p.x - s * p.y, s * p.x + c * p.y); }
vec3 antiTile(sampler2D t, vec2 uv, float k) {
  vec3 a = texture2D(t, uv).rgb;
  vec3 b = texture2D(t, trot(uv, 1.17) * 0.43 + vec2(0.31, 0.77)).rgb;
  return mix(a, b, k);
}
vec3 antiTileN(sampler2D t, vec2 uv, float k) {
  vec3 a = texture2D(t, uv).xyz * 2.0 - 1.0;
  vec3 b = texture2D(t, trot(uv, 1.17) * 0.43 + vec2(0.31, 0.77)).xyz * 2.0 - 1.0;
  b.xy = trot(b.xy, 1.17);
  return normalize(mix(a, b, k));
}
`
const TERRAIN_MAP = /* glsl */ `
vec2 wp = vTW.xz;
vec4 sp = texture2D(tSplat, (wp - uSplatRect.xy) * uSplatRect.zw);
vec3 nz = texture2D(tNoise, wp * 0.0085).rgb;
vec3 nz2 = texture2D(tNoise, wp * 0.041 + 0.37).rgb;
float kTile = smoothstep(0.32, 0.68, nz.g);
vec3 cGrass = antiTile(tGrass, wp / 5.5, kTile) * mix(uLush, uDry, smoothstep(0.38, 0.78, nz.r * 0.75 + nz2.b * 0.4));
vec3 cDirtT = antiTile(tDirt, wp / 6.0, kTile);
vec3 cDirt = cDirtT * uDirtTint * (0.9 + nz2.g * 0.2);
vec3 cMud = cDirtT * uMudTint;
vec3 cGravel = antiTile(tGravel, wp / 1.3, kTile) * vec3(0.7, 0.68, 0.64);
vec3 cForest = antiTile(tForest, wp / 4.0, kTile);
tw4 = sp;
twG = max(0.0, 1.0 - dot(sp, vec4(1.0)));
// ragged, noise-driven transitions
float pn = (nz2.r - 0.5) * 1.3;
tw4 = clamp(tw4 + pn * tw4 * (1.0 - tw4) * 3.0, 0.0, 1.0);
twG = clamp(twG - pn * twG * (1.0 - twG) * 3.0, 0.0, 1.0);
tw4 = pow(tw4, vec4(1.5));
twG = pow(twG, 1.5);
float tsum = dot(tw4, vec4(1.0)) + twG + 1e-4;
tw4 /= tsum;
twG /= tsum;
vec3 tcol = cGrass * twG + cDirt * tw4.x + cGravel * tw4.y + cMud * tw4.z + cForest * tw4.w;
diffuseColor.rgb *= tcol;
`
const TERRAIN_ROUGH = /* glsl */ `
float roughnessFactor = roughness * (0.96 * twG + 0.93 * tw4.x + 0.86 * tw4.y + mix(0.75, 0.35, uWet) * tw4.z + 0.95 * tw4.w) * mix(1.0, 0.55, uWet * (1.0 - twG * 0.5));
`
const TERRAIN_NORMAL = /* glsl */ `
{
  vec3 nG = antiTileN(tGrassN, wp / 5.5, kTile);
  vec3 nD = antiTileN(tDirtN, wp / 6.0, kTile);
  vec3 nV = antiTileN(tGravelN, wp / 2.6, kTile);
  vec3 nF = antiTileN(tForestN, wp / 4.0, kTile);
  vec3 tn = nG * twG + nD * (tw4.x + tw4.z) + nV * tw4.y + nF * tw4.w;
  tn.xy *= 0.9;
  tn = normalize(tn + vec3(0.0, 0.0, 0.001));
  vec3 N = normalize(vTN);
  vec3 T = normalize(vec3(1.0, 0.0, 0.0) - N * N.x);
  vec3 B = normalize(cross(T, N));
  vec3 wn = normalize(T * tn.x - B * tn.y + N * tn.z);
  normal = normalize((viewMatrix * vec4(wn, 0.0)).xyz);
}
`
export function terrainMaterial(splat) {
  const m = new THREE.MeshStandardMaterial({ color: '#ffffff', roughness: 1, metalness: 0 })
  const T = (n) => texSet(n)
  const u = {
    tSplat: { value: splat.tex },
    uSplatRect: { value: new THREE.Vector4(splat.x0, splat.z0, 1 / splat.size, 1 / splat.size) },
    tGrass: { value: T('grass').map },
    tGrassN: { value: T('grass').normalMap },
    tDirt: { value: T('dirt').map },
    tDirtN: { value: T('dirt').normalMap },
    tGravel: { value: T('gravel').map },
    tGravelN: { value: T('gravel').normalMap },
    tForest: { value: T('forest').map },
    tForestN: { value: T('forest').normalMap },
    tNoise: { value: T('noise').map },
    uLush: { value: new THREE.Color('#e6f0c8') },
    uDry: { value: new THREE.Color('#f4e0a8') },
    uDirtTint: { value: new THREE.Color('#ffffff') },
    uMudTint: { value: new THREE.Color('#9a8a7a') },
    uWet: { value: 0 },
  }
  m.userData.uniforms = u
  m.onBeforeCompile = (sh) => {
    Object.assign(sh.uniforms, u)
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\nvarying vec3 vTW;\nvarying vec3 vTN;')
      .replace('#include <begin_vertex>', '#include <begin_vertex>\nvTW = (modelMatrix * vec4(transformed, 1.0)).xyz;\nvTN = normalize(mat3(modelMatrix) * objectNormal);')
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>', '#include <common>\n' + TERRAIN_FRAG_PARS)
      .replace('#include <map_fragment>', TERRAIN_MAP)
      .replace('#include <roughnessmap_fragment>', TERRAIN_ROUGH)
      .replace('#include <normal_fragment_maps>', TERRAIN_NORMAL)
  }
  m.customProgramCacheKey = () => 'terrain-v1'
  return m
}

// ---------------------------------------------------------------- terrain mesh
export class Terrain {
  constructor(scene, { cx = 56, cz = 56, size = 420, segs = 210, flat = null, splat, height = null }) {
    this.scene = scene
    this.splat = splat
    if (height) this.heightAt = height
    this.flat = flat || { x0: 6, z0: 6, x1: 106, z1: 106 }
    const g = new THREE.PlaneGeometry(size, size, segs, segs)
    g.rotateX(-Math.PI / 2)
    const p = g.attributes.position
    for (let i = 0; i < p.count; i++) {
      const x = p.getX(i) + cx
      const z = p.getZ(i) + cz
      p.setY(i, this.heightAt(x, z))
    }
    g.computeVertexNormals()
    g.translate(cx, 0, cz)
    this.material = terrainMaterial(splat)
    this.mesh = new THREE.Mesh(g, this.material)
    this.mesh.receiveShadow = true
    this.mesh.userData.ground = true
    scene.add(this.mesh)
  }
  // Flat where the camp can grow, rolling hills further out.
  heightAt(x, z) {
    const f = this.flat
    const dx = Math.max(f.x0 - x, 0, x - f.x1)
    const dz = Math.max(f.z0 - z, 0, z - f.z1)
    const d = Math.hypot(dx, dz)
    if (d <= 0) return 0
    const k = Math.min(1, d / 45)
    const ramp = k * k * (3 - 2 * k)
    const n = Math.sin(x * 0.045) * Math.cos(z * 0.038) * 0.6 + Math.sin(x * 0.11 + z * 0.07) * 0.3 + Math.sin(z * 0.13 - x * 0.05) * 0.25
    return ramp * (4 + n * 5) + Math.max(0, d - 60) * 0.12
  }
}

// ---------------------------------------------------------------- wind shader bits
const WIND_PARS = /* glsl */ `
uniform float uWindTime;
uniform float uWindStrength;
`
// Sway grass by blade height (colour channel carries base→tip).
function grassMaterial() {
  const m = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.92, side: THREE.DoubleSide })
  m.onBeforeCompile = (sh) => {
    sh.uniforms.uWindTime = wind.time
    sh.uniforms.uWindStrength = wind.strength
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\n' + WIND_PARS)
      .replace(
        '#include <begin_vertex>',
        `#include <begin_vertex>
#ifdef USE_INSTANCING
  vec3 ip = instanceMatrix[3].xyz;
  float hk = transformed.y * transformed.y * 3.0;
  float ph = uWindTime * 1.6 + ip.x * 0.35 + ip.z * 0.27;
  float gust = 0.6 + 0.4 * sin(uWindTime * 0.37 + ip.x * 0.05);
  transformed.x += (sin(ph) * 0.07 + sin(ph * 2.3) * 0.025) * hk * uWindStrength * gust;
  transformed.z += cos(ph * 0.8) * 0.045 * hk * uWindStrength * gust;
#endif`,
      )
  }
  m.customProgramCacheKey = () => 'grass-v1'
  return m
}
// Foliage sway for instanced trees (canopy moves more higher up).
const foliageMats = {}
export function foliageMaterial(key = 'leaf') {
  if (foliageMats[key]) return foliageMats[key]
  const base = mat(key)
  const m = base.clone()
  m.onBeforeCompile = (sh) => {
    sh.uniforms.uWindTime = wind.time
    sh.uniforms.uWindStrength = wind.strength
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\n' + WIND_PARS)
      .replace(
        '#include <begin_vertex>',
        `#include <begin_vertex>
#ifdef USE_INSTANCING
  vec3 ip = instanceMatrix[3].xyz;
  float hk = max(0.0, transformed.y - 1.0) * 0.012;
  float ph = uWindTime * 0.9 + ip.x * 0.11 + ip.z * 0.09;
  transformed.x += sin(ph) * hk * transformed.y * uWindStrength;
  transformed.z += cos(ph * 0.7) * hk * 0.6 * transformed.y * uWindStrength;
#endif`,
      )
  }
  m.customProgramCacheKey = () => 'foliage-v1-' + key
  foliageMats[key] = m
  return m
}

// ---------------------------------------------------------------- grass field
export class GrassField {
  constructor(scene, splat, { count = 40000, area, seed = 7, heightFn = null, avoid = null }) {
    this.scene = scene
    this.splat = splat
    this.max = count
    this.area = area
    this.seed = seed
    this.heightFn = heightFn
    this.avoid = avoid
    const geos = [grassTuftGeometry(11, { blades: 9, h: 0.5 }), grassTuftGeometry(23, { blades: 12, h: 0.38 }), grassTuftGeometry(37, { blades: 7, h: 0.65 })]
    this.material = grassMaterial()
    this.meshes = geos.map((g) => {
      const im = new THREE.InstancedMesh(g, this.material, Math.ceil(count / geos.length))
      im.count = 0
      im.castShadow = false
      im.receiveShadow = true
      im.frustumCulled = false
      scene.add(im)
      return im
    })
    if (count > 0) this.rebuild()
  }
  rebuild() {
    let s = this.seed
    const rnd = () => {
      s = (s * 16807) % 2147483647
      return s / 2147483647
    }
    const A = this.area
    const m = new THREE.Matrix4()
    const q = new THREE.Quaternion()
    const sc = new THREE.Vector3()
    const p = new THREE.Vector3()
    const up = new THREE.Vector3(0, 1, 0)
    const c = new THREE.Color()
    const lush = new THREE.Color('#5d7a34')
    const dry = new THREE.Color('#9a9450')
    const per = this.meshes[0].instanceMatrix.count
    const counts = this.meshes.map(() => 0)
    const tries = this.max * 3
    for (let t = 0; t < tries; t++) {
      const x = A.x0 + rnd() * (A.x1 - A.x0)
      const z = A.z0 + rnd() * (A.z1 - A.z0)
      const w = this.splat.grassAt(x, z)
      if (w < 0.15 || rnd() > w * w) continue
      if (this.avoid && this.avoid(x, z)) continue
      const k = Math.floor(rnd() * this.meshes.length)
      if (counts[k] >= per) continue
      const y = this.heightFn ? this.heightFn(x, z) : 0
      q.setFromAxisAngle(up, rnd() * Math.PI * 2)
      const s0 = 0.7 + rnd() * 0.7
      sc.set(s0, s0 * (0.75 + w * 0.45), s0)
      m.compose(p.set(x, y, z), q, sc)
      this.meshes[k].setMatrixAt(counts[k], m)
      const n = Math.sin(x * 0.07) * Math.cos(z * 0.05) * 0.5 + 0.5
      c.copy(lush).lerp(dry, Math.min(1, n * 0.9 + rnd() * 0.25)).multiplyScalar(0.85 + rnd() * 0.3)
      this.meshes[k].setColorAt(counts[k], c)
      counts[k]++
    }
    this.meshes.forEach((im, k) => {
      im.count = counts[k]
      im.instanceMatrix.needsUpdate = true
      if (im.instanceColor) im.instanceColor.needsUpdate = true
    })
  }
  setVisible(v) {
    for (const m of this.meshes) m.visible = v
  }
  dispose() {
    for (const m of this.meshes) {
      this.scene.remove(m)
      m.geometry.dispose()
    }
  }
}

// ---------------------------------------------------------------- scatter
// Instanced copies of a kit model (one InstancedMesh per material batch).
export class Scatter {
  constructor(scene, model, max, { wind: windy = false, shadow = true } = {}) {
    this.scene = scene
    this.max = max
    this.parts = []
    model.updateMatrixWorld(true)
    model.traverse((o) => {
      if (!o.isMesh) return
      let material = o.material
      if (windy && (material.userData?.key === 'leaf' || material.userData?.key === 'needle')) material = foliageMaterial(material.userData.key)
      const g = o.geometry.clone()
      g.applyMatrix4(o.matrixWorld)
      const im = new THREE.InstancedMesh(g, material, max)
      im.count = 0
      im.castShadow = shadow && o.castShadow
      im.receiveShadow = true
      im.frustumCulled = false
      scene.add(im)
      this.parts.push(im)
    })
    this.items = []
  }
  set(list) {
    // list: [{ x, y, z, ry, s, sy }]
    const m = new THREE.Matrix4()
    const q = new THREE.Quaternion()
    const sc = new THREE.Vector3()
    const p = new THREE.Vector3()
    const e = new THREE.Euler()
    const n = Math.min(this.max, list.length)
    for (let i = 0; i < n; i++) {
      const it = list[i]
      e.set(it.rx || 0, it.ry || 0, it.rz || 0)
      q.setFromEuler(e)
      sc.set(it.s ?? 1, (it.s ?? 1) * (it.sy ?? 1), it.s ?? 1)
      m.compose(p.set(it.x, it.y || 0, it.z), q, sc)
      for (const im of this.parts) im.setMatrixAt(i, m)
    }
    for (const im of this.parts) {
      im.count = n
      im.instanceMatrix.needsUpdate = true
      im.computeBoundingSphere?.()
    }
    this.items = list.slice(0, n)
  }
  dispose() {
    for (const im of this.parts) {
      this.scene.remove(im)
      im.geometry.dispose()
    }
  }
}
