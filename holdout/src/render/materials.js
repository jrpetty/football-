// Shared PBR materials. Every material uses vertex colours as a tint, so one
// material (one draw call after merging) covers many differently coloured parts.
// Opaque surfaces also get a world-space weathering layer (see WEATHER_FRAG).
import * as THREE from 'three'
import { texSet } from './texgen.js'

// key: { tex, scale (metres per texture repeat), rough, metal, ... }
export const MAT_DEFS = {
  plain: { rough: 0.82 },
  gloss: { rough: 0.32 },
  carGlass: { rough: 0.16, metal: 0.1 },
  plastic: { rough: 0.5 },
  rubber: { rough: 0.95 },
  skin: { tex: 'skinTex', scale: 0.4, rough: 0.68, clean: true },
  cloth: { tex: 'cloth2', scale: 0.32, rough: 0.92, normalScale: 0.55 },
  clothDS: { tex: 'cloth2', scale: 0.32, rough: 0.92, normalScale: 0.55, side: THREE.DoubleSide },
  denim: { tex: 'denim', scale: 0.26, rough: 0.9, normalScale: 0.6 },
  leather: { tex: 'leather', scale: 0.3, rough: 0.62, normalScale: 0.7 },
  knit: { tex: 'knit', scale: 0.09, rough: 0.96, normalScale: 0.8 },
  hair: { tex: 'hairTex', scale: 0.14, rough: 0.74, normalScale: 0.45, clean: true, side: THREE.DoubleSide },
  canvas: { tex: 'fabric', scale: 1.2, rough: 0.95, side: THREE.DoubleSide },
  wood: { tex: 'wood', scale: 1.4, rough: 0.8, grain: true },
  planks: { tex: 'planks', scale: 2.4, rough: 0.7 },
  metal: { tex: 'metal', scale: 1, rough: 0.5, metal: 0.55 },
  paint: { tex: 'paint', scale: 1.6, rough: 0.55, metal: 0.12 },
  steel: { rough: 0.32, metal: 0.85 },
  chrome: { rough: 0.15, metal: 1, clean: true },
  rust: { tex: 'rust', scale: 1, rough: 0.92, metal: 0.25 },
  corrugated: { tex: 'corrugated', scale: 2, rough: 0.55, metal: 0.45, side: THREE.DoubleSide },
  roofmetal: { tex: 'roofmetal', scale: 3, rough: 0.5, metal: 0.4 },
  concrete: { tex: 'concrete', scale: 3, rough: 0.9 },
  pavers: { tex: 'pavers', scale: 2, rough: 0.88 },
  asphalt: { tex: 'asphalt', scale: 6, rough: 0.85 },
  road: { tex: 'asphaltWorn', scale: 7, rough: 0.9 },
  brick: { tex: 'brick', scale: 2.2, rough: 0.9 },
  siding: { tex: 'siding', scale: 1.6, rough: 0.72 },
  plaster: { tex: 'plaster', scale: 2.5, rough: 0.92 },
  tiles: { tex: 'tiles', scale: 2, rough: 0.4 },
  checker: { tex: 'checker', scale: 2.4, rough: 0.35 },
  carpet: { tex: 'carpet', scale: 1.5, rough: 1 },
  linoleum: { tex: 'linoleum', scale: 2.5, rough: 0.55 },
  shingles: { tex: 'shingles', scale: 2.5, rough: 0.9 },
  bark: { tex: 'bark', scale: 1.4, rough: 0.95, grain: true },
  dirt: { tex: 'dirt', scale: 6, rough: 0.95 },
  grass: { tex: 'grass', scale: 6, rough: 0.96 },
  gravel: { tex: 'gravel', scale: 2.5, rough: 0.9 },
  leaf: { tex: 'foliage', scale: 0.7, rough: 0.8, normalScale: 0.7 },
  // alpha-cut foliage cards (UVs 0..1 per card)
  leafCard: { tex: 'leafcard', scale: 1, rough: 0.75, normalScale: 0.6, alphaTest: 0.45, side: THREE.DoubleSide },
  leafCardAutumn: { tex: 'leafcardAutumn', scale: 1, rough: 0.75, normalScale: 0.6, alphaTest: 0.45, side: THREE.DoubleSide },
  needleCard: { tex: 'needlecard', scale: 1, rough: 0.8, normalScale: 0.6, alphaTest: 0.4, side: THREE.DoubleSide },
  needle: { tex: 'needles', scale: 0.5, rough: 0.82, normalScale: 0.8 },
  mapGlass: { rough: 0.14, metal: 0.55, clean: true },
  curtain: { tex: 'curtain', scale: 6, rough: 1, metal: 0.5, clean: true },
  roofTar: { tex: 'gravel', scale: 3, rough: 0.95 },
  glass: { rough: 0.05, metal: 0.2, transparent: true, opacity: 0.38, clean: true },
  water: { rough: 0.04, metal: 0.3, transparent: true, opacity: 0.75, clean: true },
  // Emissive light sources; bloom picks these up.
  glowWarm: { emissive: '#ffbe6a', ei: 3.2, rough: 0.5, clean: true },
  glowWhite: { emissive: '#fff6e0', ei: 4, rough: 0.5, clean: true },
  glowRed: { emissive: '#ff2a12', ei: 4, rough: 0.5, clean: true },
  glowGreen: { emissive: '#30ff60', ei: 3.5, rough: 0.5, clean: true },
  glowBlue: { emissive: '#4ab0ff', ei: 3, rough: 0.5, clean: true },
  glowAmber: { emissive: '#ffa020', ei: 3.5, rough: 0.5, clean: true },
  glowPurple: { emissive: '#c070ff', ei: 3.2, rough: 0.5, clean: true },
  fire: { emissive: '#ff8a2a', ei: 6, rough: 1, transparent: true, opacity: 0.9, noShadow: true, clean: true },
  // Lit only at night (scenes drive the intensity with setNightGlow).
  nightGlow: { emissive: '#ffb45a', ei: 3.5, rough: 0.5, night: true, clean: true },
  window: { emissive: '#ffa850', ei: 1.6, rough: 0.15, metal: 0.1, night: true, clean: true },
  embers: { emissive: '#ff5a14', ei: 3, rough: 0.9, clean: true },
}

const cache = new Map()
export function mat(key) {
  if (cache.has(key)) return cache.get(key)
  const d = MAT_DEFS[key] || MAT_DEFS.plain
  const p = {
    color: '#ffffff',
    vertexColors: true,
    roughness: d.rough ?? 0.8,
    metalness: d.metal ?? 0,
    flatShading: !!d.flat,
  }
  if (d.tex) {
    const t = texSet(d.tex)
    p.map = t.map
    p.normalMap = t.normalMap
    p.normalScale = new THREE.Vector2(d.normalScale ?? 1, d.normalScale ?? 1)
    if (t.roughnessMap && d.rough != null) p.roughnessMap = t.roughnessMap
  }
  if (d.emissive) {
    p.emissive = new THREE.Color(d.emissive)
    p.emissiveIntensity = d.ei ?? 2
  }
  if (d.transparent) {
    p.transparent = true
    p.opacity = d.opacity ?? 1
    p.depthWrite = false
  }
  if (d.side) p.side = d.side
  if (d.alphaTest) p.alphaTest = d.alphaTest
  const m = new THREE.MeshStandardMaterial(p)
  m.userData.key = key
  m.userData.noShadow = !!d.noShadow
  if (!d.clean) withWeather(m, key)
  if (d.night) {
    m.userData.night = d.ei
    m.emissiveIntensity = 0
  }
  cache.set(key, m)
  return m
}
export const texScale = (key) => MAT_DEFS[key]?.scale ?? 1
export const FOLIAGE_KEYS = new Set(['leaf', 'needle', 'leafCard', 'leafCardAutumn', 'needleCard'])
export const texGrain = (key) => !!MAT_DEFS[key]?.grain
// 0 = day (lamps off, windows dark), 1 = full night.
export function setNightGlow(k) {
  for (const m of cache.values()) if (m.userData.night != null) m.emissiveIntensity = m.userData.night * k
}
export function allMaterials() {
  return [...cache.values()]
}

// ---------------------------------------------------------------- weathering
// Nothing out here is factory clean. Every opaque prop gets, in world space:
// broad tonal and hue drift so flat colours never read as plastic, grime that
// creeps up from the ground on walls and legs, dust settling on upward faces
// and faint rain streaks running down vertical surfaces. Driven by one tiling
// noise texture sampled triplanar, so it costs no UVs and no extra geometry.
export const WEATHER = { tNoise: { value: null }, uWeather: { value: 1 } }
const WEATHER_VERT = /* glsl */ `
{
  vec4 wwp = vec4(transformed, 1.0);
  #ifdef USE_INSTANCING
  wwp = instanceMatrix * wwp;
  #endif
  vWW = (modelMatrix * wwp).xyz;
  vWN = normalize((vec4(transformedNormal, 0.0) * viewMatrix).xyz);
}
`
const WEATHER_FRAG = /* glsl */ `
if (uWeather > 0.0) {
  vec3 bw = pow(abs(vWN), vec3(4.0));
  bw /= (bw.x + bw.y + bw.z + 1e-4);
  vec3 p = vWW * 0.22;
  vec3 nA = texture2D(tWNoise, p.zy).rgb * bw.x + texture2D(tWNoise, p.xz).rgb * bw.y + texture2D(tWNoise, p.xy).rgb * bw.z;
  vec3 q = vWW * 1.3 + 0.31;
  float nB = texture2D(tWNoise, q.zy).g * bw.x + texture2D(tWNoise, q.xz).g * bw.y + texture2D(tWNoise, q.xy).g * bw.z;
  float up = smoothstep(0.55, 0.92, vWN.y);
  // broad tone and a slight warm/cool drift
  float tone = 1.0 + ((nA.r - 0.5) * 0.2 + (nB - 0.5) * 0.06) * uWeather;
  diffuseColor.rgb *= tone * mix(vec3(1.0), vec3(1.05, 1.0, 0.92), clamp((nA.b - 0.5) * 1.8, -1.0, 1.0) * uWeather);
  // grime from the ground up (not on floors and roads themselves)
  float gh = 0.35 + nA.g * 0.55;
  float g = (1.0 - smoothstep(0.0, gh, vWW.y)) * (1.0 - up) * uWeather;
  diffuseColor.rgb = mix(diffuseColor.rgb, diffuseColor.rgb * vec3(0.6, 0.53, 0.45), g * 0.6);
  // dust on top faces
  diffuseColor.rgb = mix(diffuseColor.rgb, vec3(0.44, 0.4, 0.33), up * smoothstep(0.35, 0.8, nA.g) * 0.14 * uWeather);
  // rain streaks down walls
  float side = 1.0 - abs(vWN.y);
  float st = texture2D(tWNoise, vec2((vWW.x + vWW.z) * 1.9, vWW.y * 0.07)).g;
  diffuseColor.rgb *= 1.0 - side * smoothstep(0.56, 0.86, st) * 0.16 * uWeather;
}
`
// Patch a shader object (from onBeforeCompile) with the weathering layer.
export function weatherShader(sh) {
  if (!WEATHER.tNoise.value) WEATHER.tNoise.value = texSet('noise').map
  sh.uniforms.tWNoise = WEATHER.tNoise
  sh.uniforms.uWeather = WEATHER.uWeather
  sh.vertexShader = sh.vertexShader.replace('#include <common>', '#include <common>\nvarying vec3 vWW;\nvarying vec3 vWN;').replace('#include <project_vertex>', WEATHER_VERT + '#include <project_vertex>')
  sh.fragmentShader = sh.fragmentShader
    .replace('#include <common>', '#include <common>\nvarying vec3 vWW;\nvarying vec3 vWN;\nuniform sampler2D tWNoise;\nuniform float uWeather;')
    .replace('#include <color_fragment>', '#include <color_fragment>\n' + WEATHER_FRAG)
}
// Give a material the weathering layer; extra patches run after it.
export function withWeather(m, key, extra = null, cacheKey = '') {
  m.onBeforeCompile = (sh, r) => {
    weatherShader(sh)
    extra?.(sh, r)
  }
  m.customProgramCacheKey = () => 'wx1-' + key + cacheKey
  m.userData.weather = true
  return m
}
// Clone a shared material keeping its weathering (Material.clone drops shader patches).
export function cloneMat(m, extra = null, cacheKey = '') {
  const c = m.clone()
  if (m.userData.weather) withWeather(c, m.userData.key, extra, cacheKey)
  else if (extra) {
    c.onBeforeCompile = extra
    c.customProgramCacheKey = () => 'x-' + m.userData.key + cacheKey
  }
  return c
}
