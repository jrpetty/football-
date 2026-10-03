// Ground rendering: a splat-mapped terrain shader that blends grass, packed
// dirt, gravel, mud and forest floor with anti-tiling, wind-swept instanced
// grass, and instanced scatter (trees, bushes, rocks) with swaying foliage.
import * as THREE from 'three'
import { CulledSet } from './instcull.js'
import { texSet } from './texgen.js'
import { mat, cloneMat, FOLIAGE_KEYS, WEATHER } from './materials.js'
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
uniform float uSnow;
varying vec3 vTW;
varying vec3 vTN;
vec4 tw4;
float twG;
vec2 trot(vec2 p, float a) { float s = sin(a), c = cos(a); return vec2(c * p.x - s * p.y, s * p.x + c * p.y); }
// Each ground layer is read once, or twice where the anti-tiling blend is
// partway: k of 0 or 1 needs only one of the two reads. The derivatives come
// in from outside the branches (textureGrad), so a layer that is skipped in
// part of the picture cannot upset the mip level of its neighbours.
vec3 antiTile(sampler2D t, vec2 wp, float sc, vec2 dwx, vec2 dwy, float k) {
  vec2 uv = wp / sc;
  vec2 dx = dwx / sc;
  vec2 dy = dwy / sc;
  vec3 c = vec3(0.0);
  if (k < 0.999) c += (1.0 - k) * textureGrad(t, uv, dx, dy).rgb;
  if (k > 0.001) c += k * textureGrad(t, trot(uv, 1.17) * 0.43 + vec2(0.31, 0.77), trot(dx, 1.17) * 0.43, trot(dy, 1.17) * 0.43).rgb;
  return c;
}
vec3 antiTileN(sampler2D t, vec2 wp, float sc, vec2 dwx, vec2 dwy, float k) {
  vec2 uv = wp / sc;
  vec2 dx = dwx / sc;
  vec2 dy = dwy / sc;
  vec3 n = vec3(0.0);
  if (k < 0.999) n += (1.0 - k) * (textureGrad(t, uv, dx, dy).xyz * 2.0 - 1.0);
  if (k > 0.001) {
    vec3 b = textureGrad(t, trot(uv, 1.17) * 0.43 + vec2(0.31, 0.77), trot(dx, 1.17) * 0.43, trot(dy, 1.17) * 0.43).xyz * 2.0 - 1.0;
    b.xy = trot(b.xy, 1.17);
    n += k * b;
  }
  return normalize(n);
}
`
const TERRAIN_MAP = /* glsl */ `
vec2 wp = vTW.xz;
vec2 dwx = dFdx(wp);
vec2 dwy = dFdy(wp);
vec4 sp = texture2D(tSplat, (wp - uSplatRect.xy) * uSplatRect.zw);
vec3 nz = texture2D(tNoise, wp * 0.0085).rgb;
vec3 nz2 = texture2D(tNoise, wp * 0.041 + 0.37).rgb;
float kTile = smoothstep(0.32, 0.68, nz.g);
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
// only the layers that are actually here are read: most of the ground is one
// or two of the five
vec3 tcol = vec3(0.0);
if (twG > 0.004) tcol += twG * antiTile(tGrass, wp, 5.5, dwx, dwy, kTile) * mix(uLush, uDry, smoothstep(0.38, 0.78, nz.r * 0.75 + nz2.b * 0.4));
if (tw4.x + tw4.z > 0.004) {
  vec3 cDirtT = antiTile(tDirt, wp, 6.0, dwx, dwy, kTile);
  tcol += cDirtT * (tw4.x * uDirtTint * (0.9 + nz2.g * 0.2) + tw4.z * uMudTint);
}
if (tw4.y > 0.004) tcol += tw4.y * antiTile(tGravel, wp, 1.3, dwx, dwy, kTile) * vec3(0.64, 0.6, 0.54);
if (tw4.w > 0.004) tcol += tw4.w * antiTile(tForest, wp, 4.0, dwx, dwy, kTile);
if (uSnow > 0.0) {
  // drifts lie thicker on grass and forest floor than on trodden dirt
  float sn = nz.g * 0.5 + nz2.r * 0.35 + (twG + tw4.w) * 0.25 - tw4.z * 0.2;
  float cover = smoothstep(0.56 - uSnow * 0.3, 0.76 - uSnow * 0.25, sn) * min(1.0, uSnow * 1.25);
  vec3 snowC = vec3(0.5, 0.53, 0.58) * (0.88 + nz2.g * 0.16);
  tcol = mix(tcol, snowC, clamp(cover, 0.0, 1.0));
}
diffuseColor.rgb *= tcol;
`
const TERRAIN_ROUGH = /* glsl */ `
float roughnessFactor = roughness * (0.96 * twG + 0.93 * tw4.x + 0.86 * tw4.y + mix(0.75, 0.35, uWet) * tw4.z + 0.95 * tw4.w) * mix(1.0, 0.55, uWet * (1.0 - twG * 0.5));
`
const TERRAIN_NORMAL = /* glsl */ `
{
  vec3 tn = vec3(0.0);
  if (twG > 0.004) tn += twG * antiTileN(tGrassN, wp, 5.5, dwx, dwy, kTile);
  if (tw4.x + tw4.z > 0.004) tn += (tw4.x + tw4.z) * antiTileN(tDirtN, wp, 6.0, dwx, dwy, kTile);
  if (tw4.y > 0.004) tn += tw4.y * antiTileN(tGravelN, wp, 2.6, dwx, dwy, kTile);
  if (tw4.w > 0.004) tn += tw4.w * antiTileN(tForestN, wp, 4.0, dwx, dwy, kTile);
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
    uSnow: WEATHER.uSnow,
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
  m.customProgramCacheKey = () => 'terrain-v2'
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
    sh.uniforms.uSnow = WEATHER.uSnow
    sh.fragmentShader = sh.fragmentShader
      .replace('#include <common>', '#include <common>\nuniform float uSnow;')
      .replace('#include <color_fragment>', '#include <color_fragment>\ndiffuseColor.rgb = mix(diffuseColor.rgb, vec3(0.42, 0.44, 0.41), uSnow * 0.65);')
    sh.vertexShader = sh.vertexShader
      .replace('#include <common>', '#include <common>\n' + WIND_PARS + '\nuniform float uSnow;')
      .replace(
        '#include <begin_vertex>',
        `#include <begin_vertex>
transformed.y *= 1.0 - 0.6 * uSnow;
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
  const broadleaf = key.startsWith('leaf') && key !== 'leafCardAutumn'
  const m = cloneMat(mat(key), (sh) => {
    sh.uniforms.uWindTime = wind.time
    sh.uniforms.uWindStrength = wind.strength
    if (broadleaf) {
      // seasons: in autumn each tree turns its own gold, orange or red; in
      // winter what is left goes brown under the snow
      sh.uniforms.uAutumn = WEATHER.uAutumn
      sh.fragmentShader = sh.fragmentShader
        .replace('#include <common>', '#include <common>\nuniform float uAutumn;')
        .replace(
          '#include <color_fragment>',
          `#include <color_fragment>
  if (uAutumn > 0.0) {
    float tn = fract(sin(dot(floor(vWW.xz * 0.28), vec2(12.9898, 78.233))) * 43758.5453);
    float ln = fract(sin(dot(floor(vWW.xyz * 2.1), vec3(12.9898, 78.233, 37.719))) * 43758.5453);
    vec3 hue = tn < 0.4 ? vec3(0.78, 0.5, 0.1) : tn < 0.75 ? vec3(0.82, 0.32, 0.07) : vec3(0.62, 0.12, 0.06);
    hue = mix(hue, vec3(0.42, 0.3, 0.16), smoothstep(1.0, 1.8, uAutumn));
    float l = dot(diffuseColor.rgb, vec3(0.333));
    float k = clamp(uAutumn, 0.0, 1.0) * (0.55 + 0.45 * tn) * (0.75 + 0.25 * ln);
    diffuseColor.rgb = mix(diffuseColor.rgb, hue * (0.55 + 1.4 * l), k);
  }`,
        )
    }
    foliageWind(sh)
  }, broadleaf ? '-fol-s' : '-fol')
  foliageMats[key] = m
  return m
}
// The canopy's sway, as vertex code: the same for the leaf cards' colour pass
// and their depth pass, so the two agree on where every card is.
function foliageWind(sh) {
  sh.uniforms.uWindTime = wind.time
  sh.uniforms.uWindStrength = wind.strength
  // invariant: both programs must place a card on exactly the same depth
  sh.vertexShader = ('invariant gl_Position;\n' + sh.vertexShader)
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
// Leaf and needle cards overlap a dozen deep in a forest, and each overlapping
// card used to be shaded in full before the card in front of it covered it.
// So they are drawn twice: first only their alpha-cut shape into the depth
// buffer (cheap), then in colour only where they are the nearest thing.
const prepassMats = new Map()
function foliagePrepass(material, windy) {
  let m = prepassMats.get(material.uuid)
  if (m) return m
  m = new THREE.MeshBasicMaterial({ map: material.map, alphaTest: material.alphaTest, side: material.side, colorWrite: false })
  if (windy) m.onBeforeCompile = foliageWind
  m.customProgramCacheKey = () => 'fol-pre' + (windy ? '-w' : '')
  prepassMats.set(material.uuid, m)
  return m
}
const mainMats = new Map()
function foliageMain(material) {
  let m = mainMats.get(material.uuid)
  if (m) return m
  m = material.clone()
  m.onBeforeCompile = material.onBeforeCompile
  m.customProgramCacheKey = material.customProgramCacheKey
  // the depth pass has already written the front cards; the colour pass only
  // has to land on them (a little forward, since the two programs round alike
  // but not always identically)
  m.depthWrite = false
  m.polygonOffset = true
  m.polygonOffsetFactor = -1
  m.polygonOffsetUnits = -2
  mainMats.set(material.uuid, m)
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
    // each shape keeps its tufts in a grid and draws the ones in view (see
    // instcull.js); far cells are thinned, where tufts are a few pixels
    this.sets = this.meshes.map((m) => new CulledSet([m], { colors: true, thin: { near: 55, span: 110, min: 0.35 } }))
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
    const mats = this.meshes.map(() => new Float32Array(per * 16))
    const cols = this.meshes.map(() => new Float32Array(per * 3))
    const rad = this.meshes.map(() => new Float32Array(per))
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
      m.toArray(mats[k], counts[k] * 16)
      const n = Math.sin(x * 0.07) * Math.cos(z * 0.05) * 0.5 + 0.5
      c.copy(lush).lerp(dry, Math.min(1, n * 0.9 + rnd() * 0.25)).multiplyScalar(0.85 + rnd() * 0.3)
      c.toArray(cols[k], counts[k] * 3)
      rad[k][counts[k]] = 1.1 * s0
      counts[k]++
    }
    this.sets.forEach((cs, k) => cs.set(mats[k].subarray(0, counts[k] * 16), cols[k].subarray(0, counts[k] * 3), rad[k].subarray(0, counts[k])))
  }
  // Draw only the tufts in view (camera, shadow box).
  cull(view, focus) {
    for (const cs of this.sets) cs.pack(view, focus)
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
  // lod: a cheaper model of the same thing, drawn past lodDist metres
  constructor(scene, model, max, { wind: windy = false, shadow = true, lod = null, lodDist = 44 } = {}) {
    this.scene = scene
    this.max = max
    // for each card material a depth-only twin shares the same instances
    const build = (mdl) => {
      const parts = []
      const pres = []
      mdl.updateMatrixWorld(true)
      mdl.traverse((o) => {
        if (!o.isMesh) return
        let material = o.material
        if (windy && FOLIAGE_KEYS.has(material.userData?.key)) material = foliageMaterial(material.userData.key)
        const g = o.geometry.clone()
        g.applyMatrix4(o.matrixWorld)
        const cards = material.alphaTest > 0 && !!material.map
        const im = new THREE.InstancedMesh(g, cards ? foliageMain(material) : material, max)
        im.count = 0
        im.castShadow = shadow && o.castShadow
        im.receiveShadow = true
        im.frustumCulled = false
        scene.add(im)
        parts.push(im)
        if (cards) {
          const pre = new THREE.InstancedMesh(g, foliagePrepass(material, windy), max)
          pre.count = 0
          pre.castShadow = false
          pre.receiveShadow = false
          pre.frustumCulled = false
          pre.renderOrder = -1
          scene.add(pre)
          pres.push(pre)
        }
      })
      return { parts, pres }
    }
    const near = build(model)
    const far = lod ? build(lod) : { parts: [], pres: [] }
    this.parts = near.parts
    this.lodParts = far.parts
    this.pres = [...near.pres, ...far.pres]
    // how far from its own origin any part of the model reaches
    this.reach = 0
    for (const im of [...this.parts, ...this.lodParts]) {
      im.geometry.computeBoundingSphere()
      const b = im.geometry.boundingSphere
      this.reach = Math.max(this.reach, b.center.length() + b.radius)
    }
    this.cs = new CulledSet([...this.parts, ...near.pres], { casts: this.parts.some((p) => p.castShadow), max, lod: this.lodParts.length ? { meshes: [...this.lodParts, ...far.pres], dist: lodDist } : null })
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
    const mats = new Float32Array(n * 16)
    const rad = new Float32Array(n)
    for (let i = 0; i < n; i++) {
      const it = list[i]
      e.set(it.rx || 0, it.ry || 0, it.rz || 0)
      q.setFromEuler(e)
      sc.set(it.s ?? 1, (it.s ?? 1) * (it.sy ?? 1), it.s ?? 1)
      m.compose(p.set(it.x, it.y || 0, it.z), q, sc)
      m.toArray(mats, i * 16)
      rad[i] = this.reach * (it.s ?? 1) * Math.max(1, it.sy ?? 1)
    }
    this.cs.set(mats, null, rad)
    this.items = list.slice(0, n)
  }
  // Draw only the instances in view (camera, shadow box).
  cull(view, focus) {
    this.cs.pack(view, focus)
  }
  dispose() {
    for (const im of [...this.parts, ...this.lodParts, ...this.pres]) {
      this.scene.remove(im)
      im.geometry.dispose()
    }
  }
}
