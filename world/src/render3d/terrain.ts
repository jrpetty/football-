// The land itself in 3D: a heightfield sculpted from the world's elevation
// (two vertices per tile, smooth hill shading, a fine grain texture), seasonal
// vertex colours and snow lines, rivers and lakes that sit at their own level
// and an ocean that runs to the horizon with animated ripples, roads draped
// over the ground, and a shader overlay for borders and data maps.

import * as THREE from 'three'
import { B, BIOMES } from '../sim/data.ts'
import { BUILDINGS } from '../sim/buildings.ts'
import { hash2, clamp, smoothstep } from '../sim/rng.ts'
import type { World } from '../sim/world.ts'

export const RES = 2 // terrain vertices per tile edge

export function landH(e: number): number {
  return 0.12 + Math.pow(Math.max(0, e), 1.45) * 7.5
}

const isSea = (b: number) => b === B.DEEP_OCEAN || b === B.OCEAN || b === B.SHALLOWS

/** Height data shared by everything that stands on the ground. */
export class Heights {
  W: number
  H: number
  ground: Float32Array // per tile, at the tile centre
  level: Float32Array // water surface per tile (NaN for dry land)
  constructor(w: World) {
    this.W = w.W
    this.H = w.H
    this.ground = new Float32Array(w.W * w.H)
    this.level = new Float32Array(w.W * w.H).fill(NaN)
    for (let i = 0; i < w.W * w.H; i++) {
      const b = w.biome[i], e = w.elev[i]
      if (isSea(b)) {
        this.level[i] = 0
        this.ground[i] = -0.22 - clamp(-e, 0, 1) * 2.6
      } else if (b === B.LAKE || b === B.RIVER) {
        const lv = Math.max(0.03, landH(e) - 0.07)
        this.level[i] = lv
        this.ground[i] = lv - (b === B.LAKE ? 0.55 : 0.32)
      } else {
        this.ground[i] = landH(e)
      }
    }
    // let water find its level: a lake is flat, a river never climbs above its banks
    for (let pass = 0; pass < 2; pass++) {
      for (let y = 0; y < w.H; y++) for (let x = 0; x < w.W; x++) {
        const i = y * w.W + x
        if (w.biome[i] !== B.LAKE) continue
        let lo = this.level[i]
        for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
          const xx = x + dx, yy = y + dy
          if (xx < 0 || yy < 0 || xx >= w.W || yy >= w.H) continue
          const j = yy * w.W + xx
          if (w.biome[j] === B.LAKE) lo = Math.min(lo, this.level[j])
        }
        this.ground[i] += lo - this.level[i]
        this.level[i] = lo
      }
    }
  }
  private tileGround(x: number, y: number): number {
    const tx = clamp(Math.floor(x), 0, this.W - 1), ty = clamp(Math.floor(y), 0, this.H - 1)
    return this.ground[ty * this.W + tx]
  }
  /** Bilinear ground height at world (x, z) — the same surface the mesh draws. */
  at(x: number, z: number): number {
    const fx = x - 0.5, fz = z - 0.5
    const x0 = Math.floor(fx), z0 = Math.floor(fz)
    const tx = fx - x0, tz = fz - z0
    const a = this.tileGround(x0, z0), b = this.tileGround(x0 + 1, z0)
    const c = this.tileGround(x0, z0 + 1), d = this.tileGround(x0 + 1, z0 + 1)
    return (a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + d * tx) * tz
  }
  /** Ground or water surface, whichever is higher. */
  surface(x: number, z: number): number {
    const g = this.at(x, z)
    const tx = clamp(Math.floor(x), 0, this.W - 1), tz = clamp(Math.floor(z), 0, this.H - 1)
    const lv = this.level[tz * this.W + tx]
    return Number.isNaN(lv) ? g : Math.max(g, lv)
  }
}

/** Seasonal ground colour of a tile, 0..255 per channel, plus snow cover. */
export function tileColor(w: World, i: number, season: number): [number, number, number, number] {
  const b = w.biome[i]
  const bd = BIOMES[b]
  let [r, g, bl] = bd.color
  const v = (hash2(i % w.W, (i / w.W) | 0, 7) - 0.5) * 12
  r += v; g += v; bl += v
  let snow = 0
  if (!bd.water) {
    const m = w.moist[i]
    if (b === B.GRASSLAND || b === B.SAVANNA) { g += (m - 0.5) * 26; r -= (m - 0.5) * 14 }
    if (b === B.GRASSLAND || b === B.FOREST || b === B.RAINFOREST) { r = r * 0.92 + 8; g = g * 0.86 + 6; bl = bl * 0.9 + 6 }
    if (b === B.FOREST || b === B.RAINFOREST || b === B.TAIGA) { r *= 0.86; g *= 0.92; bl *= 0.86 }
    if (season === 0 && (b === B.GRASSLAND || b === B.FOREST)) { g += 12; r -= 6 }
    if (season === 1 && (b === B.GRASSLAND || b === B.SAVANNA)) { r += 12; g += 4; bl -= 8 }
    if (season === 2) {
      if (b === B.FOREST) { r = r * 0.5 + 170 * 0.5; g = g * 0.5 + 110 * 0.5; bl = bl * 0.6 + 40 * 0.4 }
      else if (b === B.GRASSLAND) { r += 28; g += 2; bl -= 12 }
    }
    if (b === B.HILLS) { r = r * 0.9 + 10; g *= 0.94 }
    const wt = w.temp[i] - (season === 3 ? 0.3 : season === 2 ? 0.08 : season === 0 ? 0.02 : -0.1)
    snow = clamp(((season === 3 ? 0.27 : 0.16) - wt) / 0.12, 0, 1)
    if (b === B.SNOW || b === B.PEAK) snow = 1
    if (b === B.MOUNTAIN) snow = Math.max(snow, smoothstep(0.78, 0.88, w.elev[i]))
    if (b === B.DESERT || b === B.BEACH) snow *= 0.4
  } else {
    // the bed beneath the water: sand near shore, dark silt in the deep
    const depth = clamp(-w.elev[i], 0, 1)
    if (b === B.RIVER || b === B.LAKE) { r = 120; g = 110; bl = 80 }
    else { r = 196 - depth * 150; g = 182 - depth * 130; bl = 140 - depth * 80 }
  }
  return [r, g, bl, snow]
}

function srgbToLinear(c: number): number {
  c /= 255
  return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4)
}

/** A soft tileable grain texture (multiplied over the vertex colours). */
function grainTexture(): THREE.Texture {
  const N = 256
  const c = document.createElement('canvas')
  c.width = c.height = N
  const ctx = c.getContext('2d')!
  const img = ctx.createImageData(N, N)
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    // a few octaves of tileable value noise
    let v = 0, amp = 0.5, tot = 0
    for (let o = 0; o < 4; o++) {
      const f = 8 << o
      const gx = (x / N) * f, gy = (y / N) * f
      const x0 = Math.floor(gx), y0 = Math.floor(gy)
      const tx = gx - x0, ty = gy - y0
      const h = (a: number, b: number) => hash2(((a % f) + f) % f, ((b % f) + f) % f, 31 + o)
      const sx = tx * tx * (3 - 2 * tx), sy = ty * ty * (3 - 2 * ty)
      const n = (h(x0, y0) * (1 - sx) + h(x0 + 1, y0) * sx) * (1 - sy) + (h(x0, y0 + 1) * (1 - sx) + h(x0 + 1, y0 + 1) * sx) * sy
      v += n * amp
      tot += amp
      amp *= 0.55
    }
    v /= tot
    const speck = hash2(x, y, 77) > 0.93 ? -18 : 0
    const k = clamp(226 + (v - 0.5) * 80 + speck * 0.7, 0, 255)
    const o = (y * N + x) * 4
    img.data[o] = k; img.data[o + 1] = k; img.data[o + 2] = k; img.data[o + 3] = 255
  }
  ctx.putImageData(img, 0, 0)
  const t = new THREE.CanvasTexture(c)
  t.wrapS = t.wrapT = THREE.RepeatWrapping
  t.colorSpace = THREE.SRGBColorSpace
  t.anisotropy = 8
  return t
}

/** A tileable ripple normal map built from layered sine waves. */
function rippleNormals(): THREE.Texture {
  const N = 256
  const c = document.createElement('canvas')
  c.width = c.height = N
  const ctx = c.getContext('2d')!
  const img = ctx.createImageData(N, N)
  const waves: [number, number, number, number][] = []
  for (let k = 0; k < 9; k++) {
    const kx = Math.round((hash2(k, 1, 3) - 0.5) * 12), ky = Math.round((hash2(k, 2, 3) - 0.5) * 12) || 1
    waves.push([kx, ky, 0.6 / (1 + k * 0.35), hash2(k, 3, 3) * 6.28])
  }
  for (let y = 0; y < N; y++) for (let x = 0; x < N; x++) {
    let dx = 0, dy = 0
    for (const [kx, ky, a, ph] of waves) {
      const p = ((kx * x + ky * y) / N) * Math.PI * 2 + ph
      const d = Math.cos(p) * a
      dx += d * kx * 0.08
      dy += d * ky * 0.08
    }
    const n = new THREE.Vector3(-dx, -dy, 1).normalize()
    const o = (y * N + x) * 4
    img.data[o] = (n.x * 0.5 + 0.5) * 255
    img.data[o + 1] = (n.y * 0.5 + 0.5) * 255
    img.data[o + 2] = (n.z * 0.5 + 0.5) * 255
    img.data[o + 3] = 255
  }
  ctx.putImageData(img, 0, 0)
  const t = new THREE.CanvasTexture(c)
  t.wrapS = t.wrapT = THREE.RepeatWrapping
  return t
}

export interface OverlayUniforms {
  uOver: { value: THREE.Texture | null }
  uMode: { value: number }
  uSize: { value: THREE.Vector2 }
  uStrength: { value: number }
  uTime: { value: number }
}

export class Terrain {
  group = new THREE.Group()
  heights: Heights
  private w: World
  private geom: THREE.BufferGeometry
  private mesh: THREE.Mesh
  private water: THREE.Mesh
  private waterGeom: THREE.BufferGeometry
  private outer: THREE.Mesh
  private roads: THREE.Mesh | null = null
  private roadMat = new THREE.MeshStandardMaterial({ vertexColors: true, roughness: 0.92, polygonOffset: true, polygonOffsetFactor: -2, polygonOffsetUnits: -2 })
  waterMat: THREE.MeshStandardMaterial
  private ripple: THREE.Texture
  private ripple2: THREE.Texture
  uniforms: OverlayUniforms
  private season = -1
  private overTex: THREE.DataTexture

  constructor(w: World) {
    this.w = w
    this.heights = new Heights(w)
    this.uniforms = {
      uOver: { value: null },
      uMode: { value: 0 },
      uSize: { value: new THREE.Vector2(w.W, w.H) },
      uStrength: { value: 1 },
      uTime: { value: 0 },
    }
    this.overTex = new THREE.DataTexture(new Uint8Array(w.W * w.H * 4), w.W, w.H, THREE.RGBAFormat)
    this.overTex.magFilter = THREE.NearestFilter
    this.overTex.minFilter = THREE.NearestFilter
    this.uniforms.uOver.value = this.overTex

    // ---- ground
    const GW = w.W * RES + 1, GH = w.H * RES + 1
    const pos = new Float32Array(GW * GH * 3)
    const uv = new Float32Array(GW * GH * 2)
    const col = new Float32Array(GW * GH * 3)
    const hs = this.heights
    for (let j = 0; j < GH; j++) for (let i = 0; i < GW; i++) {
      const x = i / RES, z = j / RES
      const k = j * GW + i
      const g = hs.at(x, z)
      // a touch of roughness on land so slopes catch the light unevenly
      const rough = g > 0.2 ? (hash2(i, j, 13) - 0.5) * 0.06 * Math.min(1, g) : 0
      pos[k * 3] = x
      pos[k * 3 + 1] = g + rough
      pos[k * 3 + 2] = z
      uv[k * 2] = x * 0.35
      uv[k * 2 + 1] = z * 0.35
    }
    const idx = new Uint32Array((GW - 1) * (GH - 1) * 6)
    let n = 0
    for (let j = 0; j < GH - 1; j++) for (let i = 0; i < GW - 1; i++) {
      const a = j * GW + i, b = a + 1, c = a + GW, d = c + 1
      // alternate the diagonal so the mesh has no visible grain
      if ((i + j) % 2) { idx[n++] = a; idx[n++] = c; idx[n++] = b; idx[n++] = b; idx[n++] = c; idx[n++] = d }
      else { idx[n++] = a; idx[n++] = c; idx[n++] = d; idx[n++] = a; idx[n++] = d; idx[n++] = b }
    }
    this.geom = new THREE.BufferGeometry()
    this.geom.setAttribute('position', new THREE.BufferAttribute(pos, 3))
    this.geom.setAttribute('uv', new THREE.BufferAttribute(uv, 2))
    this.geom.setAttribute('color', new THREE.BufferAttribute(col, 3))
    this.geom.setIndex(new THREE.BufferAttribute(idx, 1))
    this.geom.computeVertexNormals()
    const grain = grainTexture()
    const groundMat = new THREE.MeshStandardMaterial({ vertexColors: true, map: grain, roughness: 0.94, metalness: 0 })
    const U = this.uniforms
    groundMat.onBeforeCompile = (sh) => {
      Object.assign(sh.uniforms, U)
      sh.vertexShader = 'varying vec2 vTerrXZ;\n' + sh.vertexShader.replace('#include <begin_vertex>', '#include <begin_vertex>\nvTerrXZ = position.xz;')
      sh.fragmentShader = 'varying vec2 vTerrXZ;\nuniform sampler2D uOver;\nuniform float uMode;\nuniform vec2 uSize;\nuniform float uStrength;\nuniform float uTime;\n' + sh.fragmentShader.replace('#include <color_fragment>', `#include <color_fragment>
      if (uMode > 0.5) {
        vec2 tuv = vTerrXZ / uSize;
        vec4 o = texture2D(uOver, tuv);
        if (uMode < 1.5) {
          // territory: a faint wash and a crisp glowing border on the inside edge
          float d = 0.22;
          vec4 a1 = texture2D(uOver, (vTerrXZ + vec2(d, 0.0)) / uSize);
          vec4 a2 = texture2D(uOver, (vTerrXZ - vec2(d, 0.0)) / uSize);
          vec4 a3 = texture2D(uOver, (vTerrXZ + vec2(0.0, d)) / uSize);
          vec4 a4 = texture2D(uOver, (vTerrXZ - vec2(0.0, d)) / uSize);
          float diff = step(0.01, distance(o, a1) + distance(o, a2) + distance(o, a3) + distance(o, a4));
          float border = diff * o.a;
          diffuseColor.rgb = mix(diffuseColor.rgb, o.rgb, o.a * 0.16 * uStrength);
          diffuseColor.rgb = mix(diffuseColor.rgb, o.rgb * 1.35 + 0.08, border * 0.85 * uStrength);
        } else {
          diffuseColor.rgb = mix(diffuseColor.rgb, o.rgb, o.a);
        }
      }`)
    }
    this.mesh = new THREE.Mesh(this.geom, groundMat)
    this.mesh.receiveShadow = true
    this.group.add(this.mesh)

    // ---- water
    this.ripple = rippleNormals()
    this.ripple.repeat.set(w.W / 6, w.H / 6)
    this.ripple2 = this.ripple.clone()
    this.ripple2.repeat.set(w.W / 11, w.H / 11)
    this.waterMat = new THREE.MeshStandardMaterial({
      vertexColors: true, transparent: true, roughness: 0.08, metalness: 0.1,
      normalMap: this.ripple, normalScale: new THREE.Vector2(0.35, 0.35), depthWrite: false,
    })
    const ripple2 = this.ripple2
    this.waterMat.onBeforeCompile = (sh) => {
      sh.uniforms.uRipple2 = { value: ripple2 }
      sh.uniforms.uTime = U.uTime
      sh.vertexShader = sh.vertexShader.replace('#include <begin_vertex>', '#include <begin_vertex>\n')
      sh.fragmentShader = 'uniform sampler2D uRipple2;\nuniform float uTime;\n' + sh.fragmentShader.replace('#include <normal_fragment_maps>', `
        vec3 mapN = texture2D( normalMap, vNormalMapUv ).xyz * 2.0 - 1.0;
        vec3 mapN2 = texture2D( uRipple2, vNormalMapUv * 0.55 + vec2(-uTime * 0.011, uTime * 0.007) ).xyz * 2.0 - 1.0;
        mapN = normalize(vec3(mapN.xy + mapN2.xy, mapN.z * mapN2.z));
        mapN.xy *= normalScale;
        normal = normalize( tbn * mapN );`)
    }
    this.waterGeom = new THREE.BufferGeometry()
    this.water = new THREE.Mesh(this.waterGeom, this.waterMat)
    this.water.renderOrder = 2
    this.water.receiveShadow = true
    this.group.add(this.water)
    this.buildWater()

    // the open sea beyond the edges of the map
    const outerMat = new THREE.MeshStandardMaterial({ color: new THREE.Color('#0d3a63'), roughness: 0.1, metalness: 0.1, normalMap: this.ripple, normalScale: new THREE.Vector2(0.3, 0.3) })
    const R = 4000
    const shape = new THREE.Shape([new THREE.Vector2(-R, -R), new THREE.Vector2(R, -R), new THREE.Vector2(R, R), new THREE.Vector2(-R, R)])
    const hole = new THREE.Path([new THREE.Vector2(0.01, 0.01), new THREE.Vector2(0.01, w.H - 0.01), new THREE.Vector2(w.W - 0.01, w.H - 0.01), new THREE.Vector2(w.W - 0.01, 0.01)])
    shape.holes.push(hole)
    const og = new THREE.ShapeGeometry(shape)
    og.rotateX(Math.PI / 2)
    const ouv = og.getAttribute('position')
    const uvs = new Float32Array(ouv.count * 2)
    for (let k = 0; k < ouv.count; k++) { uvs[k * 2] = ouv.getX(k) / 6; uvs[k * 2 + 1] = ouv.getZ(k) / 6 }
    og.setAttribute('uv', new THREE.BufferAttribute(uvs, 2))
    this.outer = new THREE.Mesh(og, outerMat)
    this.outer.position.y = -0.005
    this.outer.receiveShadow = true
    this.group.add(this.outer)
    // a skirt so the land never ends in a paper-thin edge
    this.recolor(w.season)
    this.buildRoads()
  }

  private buildWater() {
    const w = this.w, hs = this.heights
    const W = w.W, H = w.H
    // a water quad for every tile that is water or touches it; corners take the level of nearby water
    const want = new Uint8Array(W * H)
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      const i = y * W + x
      if (Number.isNaN(hs.level[i])) continue
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
        const xx = x + dx, yy = y + dy
        if (xx >= 0 && yy >= 0 && xx < W && yy < H) want[yy * W + xx] = 1
      }
    }
    const cornerLevel = (cx: number, cy: number): number => {
      let s = 0, n = 0
      for (let dy = -1; dy <= 0; dy++) for (let dx = -1; dx <= 0; dx++) {
        const x = cx + dx, y = cy + dy
        if (x < 0 || y < 0 || x >= W || y >= H) continue
        const lv = hs.level[y * W + x]
        if (!Number.isNaN(lv)) { s += lv; n++ }
      }
      if (n) return s / n
      // no water at this corner: borrow from the nearest water within two tiles
      let best = NaN, bd = 9
      for (let dy = -2; dy <= 1; dy++) for (let dx = -2; dx <= 1; dx++) {
        const x = cx + dx, y = cy + dy
        if (x < 0 || y < 0 || x >= W || y >= H) continue
        const lv = hs.level[y * W + x]
        const d = Math.abs(dx + 0.5) + Math.abs(dy + 0.5)
        if (!Number.isNaN(lv) && d < bd) { bd = d; best = lv }
      }
      return Number.isNaN(best) ? 0 : best
    }
    const vmap = new Int32Array((W + 1) * (H + 1)).fill(-1)
    const pos: number[] = [], uv: number[] = [], idx: number[] = []
    const vert = (cx: number, cy: number) => {
      const k = cy * (W + 1) + cx
      if (vmap[k] >= 0) return vmap[k]
      vmap[k] = pos.length / 3
      pos.push(cx, cornerLevel(cx, cy), cy)
      uv.push(cx / W, cy / H)
      return vmap[k]
    }
    for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
      if (!want[y * W + x]) continue
      const a = vert(x, y), b = vert(x + 1, y), c = vert(x, y + 1), d = vert(x + 1, y + 1)
      idx.push(a, c, b, b, c, d)
    }
    const g = this.waterGeom
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3))
    g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2))
    g.setAttribute('normal', new THREE.Float32BufferAttribute(new Float32Array(pos.length).map((_, k) => (k % 3 === 1 ? 1 : 0)), 3))
    g.setAttribute('color', new THREE.Float32BufferAttribute(new Float32Array((pos.length / 3) * 4), 4))
    g.setIndex(idx)
    // normal-map uvs should tile across the world
    this.ripple.repeat.set(W / 5, H / 5)
  }

  private recolorWater(season: number) {
    const w = this.w, hs = this.heights
    const g = this.waterGeom
    const p = g.getAttribute('position') as THREE.BufferAttribute
    const c = g.getAttribute('color') as THREE.BufferAttribute
    const deep = new THREE.Color('#0b3358'), mid = new THREE.Color('#1a6a8c'), shallow = new THREE.Color('#3fb3b8'), river = new THREE.Color('#3a8aa8'), ice = new THREE.Color('#d6e8f2')
    const tmp = new THREE.Color()
    for (let k = 0; k < p.count; k++) {
      const x = p.getX(k), z = p.getZ(k), lv = p.getY(k)
      const depth = lv - hs.at(x, z)
      const tx = clamp(Math.round(x - 0.5), 0, w.W - 1), tz = clamp(Math.round(z - 0.5), 0, w.H - 1)
      const ti = tz * w.W + tx
      const fresh = lv > 0.02
      const frozen = season === 3 && fresh && w.temp[ti] < 0.32
      if (frozen) { tmp.copy(ice); c.setXYZW(k, tmp.r, tmp.g, tmp.b, 0.96); continue }
      if (fresh) tmp.copy(river).lerp(shallow, clamp(0.3 - depth, 0, 0.3))
      else if (depth < 0.6) tmp.copy(shallow).lerp(mid, depth / 0.6)
      else tmp.copy(mid).lerp(deep, clamp((depth - 0.6) / 1.6, 0, 1))
      // foam where the water is barely deep
      const foam = clamp(1 - depth / 0.035, 0, 1) * 0.3
      tmp.lerp(new THREE.Color('#e8f4f4'), foam)
      const alpha = clamp(0.72 + depth * 0.6, 0.72, 0.97) * (depth < 0 ? 0 : 1)
      c.setXYZW(k, tmp.r, tmp.g, tmp.b, alpha)
    }
    c.needsUpdate = true
  }

  /** Repaint every ground colour (on a new season or a new world). */
  recolor(season: number) {
    this.season = season
    const w = this.w
    const GW = w.W * RES + 1, GH = w.H * RES + 1
    const col = this.geom.getAttribute('color') as THREE.BufferAttribute
    const tc = new Float32Array(w.W * w.H * 3)
    for (let i = 0; i < w.W * w.H; i++) {
      const [r, g, b, s] = tileColor(w, i, season)
      tc[i * 3] = srgbToLinear(clamp(r + (236 - r) * s, 0, 255))
      tc[i * 3 + 1] = srgbToLinear(clamp(g + (242 - g) * s, 0, 255))
      tc[i * 3 + 2] = srgbToLinear(clamp(b + (250 - b) * s, 0, 255))
    }
    const at = (x: number, y: number, ch: number) => tc[(clamp(y, 0, w.H - 1) * w.W + clamp(x, 0, w.W - 1)) * 3 + ch]
    const pos = this.geom.getAttribute('position') as THREE.BufferAttribute
    for (let j = 0; j < GH; j++) for (let i = 0; i < GW; i++) {
      const fx = i / RES - 0.5, fz = j / RES - 0.5
      const x0 = Math.floor(fx), z0 = Math.floor(fz)
      const tx = fx - x0, tz = fz - z0
      const k = j * GW + i
      // steep slopes show bare rock and earth
      const nrm = this.geom.getAttribute('normal') as THREE.BufferAttribute
      const steep = clamp((0.86 - nrm.getY(k)) * 4, 0, 0.75)
      const y = pos.getY(k)
      const jit = 1 + (hash2(i, j, 21) - 0.5) * 0.1
      for (let ch = 0; ch < 3; ch++) {
        let v = (at(x0, z0, ch) * (1 - tx) + at(x0 + 1, z0, ch) * tx) * (1 - tz) + (at(x0, z0 + 1, ch) * (1 - tx) + at(x0 + 1, z0 + 1, ch) * tx) * tz
        const rock = [0.19, 0.16, 0.13][ch]
        v = v * (1 - steep) + rock * steep
        // wet sand / mud right at the waterline
        if (y > -0.05 && y < 0.16) v *= 0.82 + (y + 0.05) * 0.85
        col.setComponent(k, ch, v * jit)
      }
    }
    col.needsUpdate = true
    this.recolorWater(season)
  }

  /** Roads as gently raised strips that follow the ground. */
  buildRoads(era = 0) {
    const w = this.w, hs = this.heights
    if (this.roads) { this.group.remove(this.roads); this.roads.geometry.dispose(); this.roads = null }
    const pos: number[] = [], col: number[] = [], idx: number[] = []
    const base = new THREE.Color(era >= 6 ? '#4a4a4f' : era >= 2 ? '#9a8e7a' : '#8b6f4e')
    const edge = new THREE.Color(era >= 6 ? '#3a3a3e' : era >= 2 ? '#7d7262' : '#6f5639')
    const quad = (x0: number, z0: number, x1: number, z1: number, c: THREE.Color) => {
      // subdivide so the strip follows the terrain
      const nx = Math.max(1, Math.ceil((x1 - x0) * 2)), nz = Math.max(1, Math.ceil((z1 - z0) * 2))
      const start = pos.length / 3
      for (let j = 0; j <= nz; j++) for (let i = 0; i <= nx; i++) {
        const x = x0 + ((x1 - x0) * i) / nx, z = z0 + ((z1 - z0) * j) / nz
        pos.push(x, hs.at(x, z) + 0.035, z)
        const v = 0.92 + hash2(Math.round(x * 4), Math.round(z * 4), 5) * 0.16
        col.push(c.r * v, c.g * v, c.b * v)
      }
      for (let j = 0; j < nz; j++) for (let i = 0; i < nx; i++) {
        const a = start + j * (nx + 1) + i, b = a + 1, cc = a + nx + 1, d = cc + 1
        idx.push(a, cc, b, b, cc, d)
      }
    }
    const R = (x: number, y: number) => x >= 0 && y >= 0 && x < w.W && y < w.H && w.road[y * w.W + x] > 0
    const hw = 0.3
    for (let y = 0; y < w.H; y++) for (let x = 0; x < w.W; x++) {
      if (!R(x, y)) continue
      const cx = x + 0.5, cz = y + 0.5
      quad(cx - hw - 0.04, cz - hw - 0.04, cx + hw + 0.04, cz + hw + 0.04, edge)
      quad(cx - hw, cz - hw, cx + hw, cz + hw, base)
      if (R(x + 1, y)) quad(cx + hw, cz - hw, cx + 1 - hw, cz + hw, base)
      if (R(x, y + 1)) quad(cx - hw, cz + hw, cx + hw, cz + 1 - hw, base)
    }
    if (!pos.length) return
    const g = new THREE.BufferGeometry()
    g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3))
    g.setAttribute('color', new THREE.Float32BufferAttribute(col, 3))
    g.setIndex(idx)
    g.computeVertexNormals()
    this.roads = new THREE.Mesh(g, this.roadMat)
    this.roads.receiveShadow = true
    this.group.add(this.roads)
  }

  /** Territory borders or a data layer, sampled by the ground shader. */
  setOverlay(kind: 'none' | 'territory' | 'fertility' | 'temperature' | 'explored', selTribe: number) {
    const w = this.w
    const d = this.overTex.image.data as Uint8Array
    d.fill(0)
    if (kind === 'none') { this.uniforms.uMode.value = 0; return }
    if (kind === 'territory') {
      const own = new Int32Array(w.W * w.H).fill(-1)
      const dist = new Float32Array(w.W * w.H).fill(1e9)
      for (const b of w.buildings) {
        if (!b.done || b.type === 'road') continue
        const rad = b.type === 'town_hall' ? 11 : BUILDINGS[b.type].size === 2 ? 7 : b.type === 'farm' || b.type === 'pasture' ? 3 : 5
        for (let dy = -rad; dy <= rad; dy++) for (let dx = -rad; dx <= rad; dx++) {
          const x = b.x + dx, y = b.y + dy
          if (!w.inBounds(x, y)) continue
          const dd = Math.hypot(dx, dy)
          if (dd > rad) continue
          const i = w.idx(x, y)
          if (BIOMES[w.biome[i]].water && w.biome[i] !== B.RIVER) continue
          if (dd < dist[i]) { dist[i] = dd; own[i] = b.tribeId }
        }
      }
      const colors = new Map<number, THREE.Color>()
      for (const t of w.tribes) colors.set(t.id, new THREE.Color(t.color))
      for (let i = 0; i < w.W * w.H; i++) {
        if (own[i] < 0) continue
        const c = colors.get(own[i])
        if (!c) continue
        // encode the tribe id subtly in the colour so neighbouring tribes always differ
        d[i * 4] = Math.round(c.r * 255); d[i * 4 + 1] = Math.round(c.g * 255); d[i * 4 + 2] = Math.round(c.b * 255); d[i * 4 + 3] = 255
      }
      this.overTex.magFilter = THREE.NearestFilter
      this.uniforms.uMode.value = 1
    } else {
      const ex = selTribe ? w.explored.get(selTribe) : undefined
      for (let i = 0; i < w.W * w.H; i++) {
        let r = 0, g = 0, b = 0, a = 0
        if (kind === 'fertility') {
          const f = w.fertility[i]
          if (!BIOMES[w.biome[i]].water) { r = 255 * (1 - f); g = 220 * f + 30; b = 40; a = 120 }
        } else if (kind === 'temperature') {
          const t = w.temp[i] + [0, 0.14, -0.04, -0.28][w.season]
          r = clamp(t * 2, 0, 1) * 255; b = clamp(1 - t * 1.6, 0, 1) * 255; g = 60; a = 130
        } else if (kind === 'explored' && ex) {
          if (!ex[i]) { r = 8; g = 10; b = 20; a = 215 }
        }
        d[i * 4] = r; d[i * 4 + 1] = g; d[i * 4 + 2] = b; d[i * 4 + 3] = a
      }
      this.overTex.magFilter = kind === 'explored' ? THREE.NearestFilter : THREE.LinearFilter
      this.uniforms.uMode.value = 2
    }
    this.overTex.needsUpdate = true
  }

  update(t: number, season: number) {
    this.uniforms.uTime.value = t
    this.ripple.offset.set(t * 0.012, t * 0.008)
    if (season !== this.season) this.recolor(season)
  }

  dispose() {
    this.geom.dispose()
    this.waterGeom.dispose()
    this.roads?.geometry.dispose()
  }
}
