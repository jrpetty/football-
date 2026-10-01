// Shared PBR materials. Every material uses vertex colours as a tint, so one
// material (one draw call after merging) covers many differently coloured parts.
import * as THREE from 'three'
import { texSet } from './texgen.js'

// key: { tex, scale (metres per texture repeat), rough, metal, ... }
export const MAT_DEFS = {
  plain: { rough: 0.82 },
  gloss: { rough: 0.32 },
  plastic: { rough: 0.5 },
  rubber: { rough: 0.95 },
  skin: { tex: 'skinTex', scale: 0.4, rough: 0.68 },
  cloth: { tex: 'fabric', scale: 0.5, rough: 0.95, normalScale: 0.6 },
  canvas: { tex: 'fabric', scale: 1.2, rough: 0.95, side: THREE.DoubleSide },
  wood: { tex: 'wood', scale: 1.4, rough: 0.8, grain: true },
  planks: { tex: 'planks', scale: 2.4, rough: 0.7 },
  metal: { tex: 'metal', scale: 1, rough: 0.5, metal: 0.55 },
  paint: { tex: 'metal', scale: 1.5, rough: 0.6, metal: 0.15 },
  steel: { rough: 0.32, metal: 0.85 },
  chrome: { rough: 0.15, metal: 1 },
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
  needle: { tex: 'needles', scale: 0.5, rough: 0.82, normalScale: 0.8 },
  mapGlass: { rough: 0.14, metal: 0.55 },
  curtain: { tex: 'curtain', scale: 6, rough: 1, metal: 0.5 },
  roofTar: { tex: 'gravel', scale: 3, rough: 0.95 },
  glass: { rough: 0.05, metal: 0.2, transparent: true, opacity: 0.38 },
  water: { rough: 0.04, metal: 0.3, transparent: true, opacity: 0.75 },
  // Emissive light sources; bloom picks these up.
  glowWarm: { emissive: '#ffbe6a', ei: 3.2, rough: 0.5 },
  glowWhite: { emissive: '#fff6e0', ei: 4, rough: 0.5 },
  glowRed: { emissive: '#ff2a12', ei: 4, rough: 0.5 },
  glowGreen: { emissive: '#30ff60', ei: 3.5, rough: 0.5 },
  glowBlue: { emissive: '#4ab0ff', ei: 3, rough: 0.5 },
  glowAmber: { emissive: '#ffa020', ei: 3.5, rough: 0.5 },
  fire: { emissive: '#ff8a2a', ei: 6, rough: 1, transparent: true, opacity: 0.9, noShadow: true },
  // Lit only at night (scenes drive the intensity with setNightGlow).
  nightGlow: { emissive: '#ffb45a', ei: 3.5, rough: 0.5, night: true },
  window: { emissive: '#ffa850', ei: 1.6, rough: 0.15, metal: 0.1, night: true },
  embers: { emissive: '#ff5a14', ei: 3, rough: 0.9 },
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
  const m = new THREE.MeshStandardMaterial(p)
  m.userData.key = key
  m.userData.noShadow = !!d.noShadow
  if (d.night) {
    m.userData.night = d.ei
    m.emissiveIntensity = 0
  }
  cache.set(key, m)
  return m
}
export const texScale = (key) => MAT_DEFS[key]?.scale ?? 1
export const texGrain = (key) => !!MAT_DEFS[key]?.grain
// 0 = day (lamps off, windows dark), 1 = full night.
export function setNightGlow(k) {
  for (const m of cache.values()) if (m.userData.night != null) m.emissiveIntensity = m.userData.night * k
}
export function allMaterials() {
  return [...cache.values()]
}
