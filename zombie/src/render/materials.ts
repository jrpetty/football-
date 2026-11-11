/**
 * REKINDLE — cached material library built on the procedural textures in textures.ts.
 * Call (and await) initTextures() before requesting any textured material.
 */
import * as THREE from 'three';
import {
  getTexSet, getExtraTexSet, getPaintedTexSet, getPineNeedleTexture, getLeafTexture,
  METALLIC_TEX, type TexName, type PBRSet,
} from './textures';

export type MaterialName =
  | TexName
  | 'glass' | 'glassDirty' | 'rubber' | 'plasticBlack' | 'plasticWhite' | 'chrome'
  | 'emissiveWarm' | 'emissiveCool' | 'emissiveRed' | 'emissiveGreen'
  | 'factoryOrange' | 'factoryGrey'
  | 'sidingWhite' | 'sidingBlue' | 'sidingGreen' | 'sidingYellow'
  | 'pineNeedles' | 'leaves' | 'skinPale' | 'clothDenim' | 'clothDark';

export const MATERIAL_NAMES: readonly MaterialName[] = [
  'grass', 'dirt', 'rock', 'forestFloor', 'mud', 'sand', 'asphalt', 'sidewalk', 'concrete',
  'brick', 'brickRed', 'siding', 'plaster', 'woodFloor', 'tile', 'roofShingle', 'roofMetal',
  'wood', 'woodDark', 'bark', 'birchBark', 'metal', 'metalRust', 'metalDark', 'steelBrushed',
  'fabric', 'factoryPanel', 'hazard', 'grating', 'belt', 'soil',
  'glass', 'glassDirty', 'rubber', 'plasticBlack', 'plasticWhite', 'chrome',
  'emissiveWarm', 'emissiveCool', 'emissiveRed', 'emissiveGreen',
  'factoryOrange', 'factoryGrey', 'sidingWhite', 'sidingBlue', 'sidingGreen', 'sidingYellow',
  'pineNeedles', 'leaves', 'skinPale', 'clothDenim', 'clothDark',
];

interface TexMatSpec {
  normalScale?: number;
  color?: number;
  roughness?: number; // multiplier on roughnessMap.g
  envMapIntensity?: number;
}

/** Per-texture material tuning. */
const TEX_SPECS: Partial<Record<TexName, TexMatSpec>> = {
  grass: { normalScale: 0.8 },
  forestFloor: { normalScale: 0.9 },
  mud: { normalScale: 0.8 },
  asphalt: { normalScale: 0.9 },
  steelBrushed: { normalScale: 0.5 },
  metal: { normalScale: 0.7 },
  metalDark: { normalScale: 0.7 },
  belt: { normalScale: 1 },
  fabric: { normalScale: 0.8 },
};

const cache = new Map<string, THREE.MeshStandardMaterial>();

/**
 * Low-poly art direction: every surface is a flat, faceted colour (The Long Dark-style), lit by the
 * atmosphere. Textures are only kept where they carry information (hazard stripes, belt motion).
 */
/** art style switch, set once at boot from settings before any material is built */
export let LOWPOLY = false;
export function setLowPoly(v: boolean) { LOWPOLY = v; }

// ---- global shader upgrades (patched once, before any program compiles) ----
// FACET_VARIATION: per-face tonal variation from the world-space flat normal + a per-object seed, giving
// hand-painted low-poly facets without textures. Height mist: low-lying fog that pools in valleys.
(function patchChunks() {
  const C = THREE.ShaderChunk as any;
  if (C.__rekindle) return; C.__rekindle = true;
  C.common = C.common + `
#ifdef FACET_VARIATION
varying vec3 vFacetW; varying vec3 vFacetSeed;
#endif
#ifdef USE_FOG
varying float vFogWY;
#endif
`;
  C.project_vertex = C.project_vertex + `
#ifdef FACET_VARIATION
  #ifdef USE_INSTANCING
    vFacetW = (modelMatrix * instanceMatrix * vec4(transformed, 1.0)).xyz;
    vFacetSeed = (modelMatrix * vec4(instanceMatrix[3].xyz, 1.0)).xyz;
  #else
    vFacetW = (modelMatrix * vec4(transformed, 1.0)).xyz;
    vFacetSeed = modelMatrix[3].xyz;
  #endif
#endif
`;
  C.fog_vertex = C.fog_vertex + `
#ifdef USE_FOG
  vFogWY = (inverse(viewMatrix) * mvPosition).y;
#endif
`;
  C.color_fragment = C.color_fragment + `
#ifdef FACET_VARIATION
  {
    vec3 fN = normalize(cross(dFdx(vFacetW), dFdy(vFacetW)));
    vec3 q = floor(fN * 7.0 + 0.5) + floor(vFacetSeed * 0.37);
    float fh = fract(sin(dot(q, vec3(12.9898, 78.233, 45.164))) * 43758.5453);
    float sh = fract(sin(dot(floor(vFacetSeed * 0.5), vec3(7.13, 157.1, 113.7))) * 24634.63);
    diffuseColor.rgb *= 0.92 + fh * 0.13;
    diffuseColor.rgb *= mix(vec3(0.97, 1.0, 1.02), vec3(1.04, 1.0, 0.95), sh);
    // sky bounce on up-facing facets, darker undersides: reads as baked ambient occlusion
    diffuseColor.rgb *= 0.9 + 0.14 * clamp(fN.y * 0.5 + 0.5, 0.0, 1.0);
  }
#endif
`;
  C.fog_fragment = `
#ifdef USE_FOG
  #ifdef FOG_EXP2
    float fogFactor = 1.0 - exp( - fogDensity * fogDensity * vFogDepth * vFogDepth );
    float mist = (1.0 - exp(-vFogDepth * fogDensity * 2.2)) * exp(-max(vFogWY - 1.0, 0.0) * 0.22);
    fogFactor = clamp(max(fogFactor, mist * 0.55), 0.0, 1.0);
  #else
    float fogFactor = smoothstep( fogNear, fogFar, vFogDepth );
  #endif
  gl_FragColor.rgb = mix( gl_FragColor.rgb, fogColor, fogFactor );
#endif
`;
})();
/** opt a material into faceted tonal variation */
export function facet<T extends THREE.Material>(m: T): T { if (!LOWPOLY) return m; (m as any).defines = { ...((m as any).defines ?? {}), FACET_VARIATION: '' }; return m; }
const PALETTE: Partial<Record<MaterialName, [number, number, number]>> = {
  // [colour, roughness, metalness]
  grass: [0x6f7d45, 0.95, 0], dirt: [0x7a6248, 0.95, 0], rock: [0x85847c, 0.9, 0], forestFloor: [0x5a4a34, 0.95, 0],
  mud: [0x4e3f30, 0.85, 0], sand: [0xb8a57e, 0.95, 0], asphalt: [0x3e3f42, 0.9, 0], sidewalk: [0x9a968c, 0.92, 0],
  concrete: [0xa29f96, 0.92, 0], brick: [0x8c5242, 0.9, 0], brickRed: [0x9a4535, 0.9, 0], siding: [0xc8c2b0, 0.85, 0],
  plaster: [0xd6cdb8, 0.92, 0], woodFloor: [0x8a6440, 0.75, 0], tile: [0xc9c6bc, 0.6, 0], roofShingle: [0x4a4440, 0.92, 0],
  roofMetal: [0x6e706e, 0.6, 0.3], wood: [0x9a7650, 0.85, 0], woodDark: [0x5c4430, 0.85, 0], bark: [0x5a4434, 0.95, 0],
  birchBark: [0xd8d4c8, 0.9, 0], metal: [0x8e9296, 0.55, 0.35], metalRust: [0x8a5432, 0.8, 0.15], metalDark: [0x3e4246, 0.6, 0.3],
  steelBrushed: [0xb0b4b8, 0.4, 0.45], fabric: [0x6a645a, 0.95, 0], factoryPanel: [0xc4c2ba, 0.7, 0.1], grating: [0x6a6e70, 0.6, 0.3],
  soil: [0x4a3626, 0.95, 0], rubber: [0x1e1e1e, 0.92, 0], plasticBlack: [0x1a1a1a, 0.6, 0], plasticWhite: [0xd2d0c8, 0.55, 0],
  chrome: [0xb8bcc0, 0.3, 0.6], factoryOrange: [0xd8641e, 0.7, 0.05], factoryGrey: [0x5a5e60, 0.7, 0.1],
  sidingWhite: [0xddd6c4, 0.85, 0], sidingBlue: [0x7d93a0, 0.85, 0], sidingGreen: [0x87957a, 0.85, 0], sidingYellow: [0xcdb984, 0.85, 0],
  pineNeedles: [0x3c5a3a, 0.9, 0], leaves: [0x6e8a3e, 0.9, 0], skinPale: [0xb8ad9c, 0.85, 0], clothDenim: [0x4e6a8a, 0.95, 0],
  clothDark: [0x3a3834, 0.95, 0],
};
/** A flat faceted material in the low-poly palette. */
export function getFlatMaterial(hex: number, roughness = 0.9, metalness = 0): THREE.MeshStandardMaterial {
  const key = `flat:${hex}:${roughness}:${metalness}`;
  let m = cache.get(key);
  if (!m) { m = facet(new THREE.MeshStandardMaterial({ name: key, color: hex, roughness, metalness, flatShading: true })); cache.set(key, m); }
  return m;
}
function lowpoly(name: MaterialName): THREE.MeshStandardMaterial | null {
  if (!LOWPOLY) return null;
  if (name === 'glassDirty') return new THREE.MeshStandardMaterial({ name, color: 0x9fb4ae, roughness: 0.2, metalness: 0.1, transparent: true, opacity: 0.45, depthWrite: false, side: THREE.DoubleSide });
  if (name === 'pineNeedles' || name === 'leaves') {
    const c = PALETTE[name]!;
    return facet(new THREE.MeshStandardMaterial({ name, color: c[0], roughness: c[1], flatShading: true, side: THREE.DoubleSide }));
  }
  const c = PALETTE[name];
  if (!c) return null;
  return facet(new THREE.MeshStandardMaterial({ name, color: c[0], roughness: c[1], metalness: c[2], flatShading: true }));
}

function applySet(m: THREE.MeshStandardMaterial, set: PBRSet, metallic: boolean, spec: TexMatSpec = {}): void {
  m.map = set.map;
  m.normalMap = set.normalMap;
  const ns = spec.normalScale ?? 1;
  m.normalScale.set(ns, ns);
  m.roughnessMap = set.roughnessMap;
  m.roughness = spec.roughness ?? 1;
  m.aoMap = set.roughnessMap; // R channel = cavity AO
  m.aoMapIntensity = 0.8;
  if (metallic) {
    m.metalnessMap = set.roughnessMap; // B channel = metalness
    m.metalness = 1;
  } else {
    m.metalness = 0;
  }
  if (spec.color !== undefined) m.color.set(spec.color);
  if (spec.envMapIntensity !== undefined) m.envMapIntensity = spec.envMapIntensity;
}

function texMat(name: TexName, label: string): THREE.MeshStandardMaterial {
  const m = new THREE.MeshStandardMaterial({ name: label });
  applySet(m, getTexSet(name), METALLIC_TEX.has(name), TEX_SPECS[name]);
  if (name === 'grating') {
    m.alphaTest = 0.5;
    m.side = THREE.DoubleSide;
  }
  return m;
}

function paintedMat(base: 'siding' | 'factoryPanel', hex: number, label: string): THREE.MeshStandardMaterial {
  const m = new THREE.MeshStandardMaterial({ name: label });
  applySet(m, getPaintedTexSet(base, hex), METALLIC_TEX.has(base), TEX_SPECS[base]);
  return m;
}

function emissive(hex: number, label: string, intensity = 3): THREE.MeshStandardMaterial {
  const c = new THREE.Color(hex);
  return new THREE.MeshStandardMaterial({
    name: label,
    color: c.clone().multiplyScalar(0.25),
    emissive: c,
    emissiveIntensity: intensity,
    roughness: 0.4,
    metalness: 0,
  });
}

function build(name: MaterialName): THREE.MeshStandardMaterial {
  switch (name) {
    case 'glass':
      return new THREE.MeshStandardMaterial({
        name, color: 0xa9c4bc, roughness: 0.04, metalness: 0.1, transparent: true, opacity: 0.25,
        depthWrite: false, side: THREE.DoubleSide, envMapIntensity: 1.5,
      });
    case 'glassDirty': {
      const set = getExtraTexSet('glassGrime');
      return new THREE.MeshStandardMaterial({
        name, color: 0xffffff, map: set.map, normalMap: set.normalMap, roughnessMap: set.roughnessMap,
        roughness: 1, metalness: 0.05, transparent: true, opacity: 1, depthWrite: false, side: THREE.DoubleSide,
      });
    }
    case 'rubber':
      return new THREE.MeshStandardMaterial({ name, color: 0x1a1a1a, roughness: 0.92, metalness: 0 });
    case 'plasticBlack':
      return new THREE.MeshStandardMaterial({ name, color: 0x141414, roughness: 0.5, metalness: 0 });
    case 'plasticWhite':
      return new THREE.MeshStandardMaterial({ name, color: 0xd2d0c8, roughness: 0.45, metalness: 0 });
    case 'chrome':
      return new THREE.MeshStandardMaterial({ name, color: 0xe0e0e0, roughness: 0.12, metalness: 1 });
    case 'emissiveWarm': return emissive(0xff9a40, name, 2.5);
    case 'emissiveCool': return emissive(0x7fc4ff, name, 2.5);
    case 'emissiveRed': return emissive(0xff1808, name, 3);
    case 'emissiveGreen': return emissive(0x30ff50, name, 2.5);
    case 'factoryOrange': return paintedMat('factoryPanel', 0xd8601a, name);
    case 'factoryGrey': return paintedMat('factoryPanel', 0x55595c, name);
    case 'sidingWhite': return paintedMat('siding', 0xddd9cc, name);
    case 'sidingBlue': return paintedMat('siding', 0x7d93a0, name);
    case 'sidingGreen': return paintedMat('siding', 0x87957a, name);
    case 'sidingYellow': return paintedMat('siding', 0xcdb984, name);
    case 'pineNeedles':
      return new THREE.MeshStandardMaterial({
        name, map: getPineNeedleTexture(), alphaTest: 0.5, side: THREE.DoubleSide, roughness: 0.85, metalness: 0,
      });
    case 'leaves':
      return new THREE.MeshStandardMaterial({
        name, map: getLeafTexture(), alphaTest: 0.5, side: THREE.DoubleSide, roughness: 0.75, metalness: 0,
      });
    case 'skinPale': {
      const m = new THREE.MeshStandardMaterial({ name });
      applySet(m, getExtraTexSet('skin'), false, { normalScale: 0.6 });
      return m;
    }
    case 'clothDenim': {
      const m = texMat('fabric', name);
      m.color.set(0x6d86b0);
      return m;
    }
    case 'clothDark': {
      const m = texMat('fabric', name);
      m.color.set(0x5a5753);
      return m;
    }
    default:
      return texMat(name, name);
  }
}

/** Cached singleton per name. Do not mutate the returned instance; use getTintedMaterial or clone(). */
export function getMaterial(name: MaterialName): THREE.MeshStandardMaterial {
  let m = cache.get(name);
  if (!m) {
    m = lowpoly(name) ?? build(name);
    cache.set(name, m);
  }
  return m;
}

/** Weathered car paint (MeshPhysicalMaterial with a dull clearcoat), cached per colour. */
export function getCarPaint(hex: number): THREE.MeshStandardMaterial {
  const key = `car:${hex}`;
  let m = cache.get(key);
  if (!m && LOWPOLY) {
    m = facet(new THREE.MeshPhysicalMaterial({ name: `carPaint_${hex.toString(16)}`, color: new THREE.Color(hex).multiplyScalar(0.85), roughness: 0.5, metalness: 0.15, clearcoat: 0.25, clearcoatRoughness: 0.5, flatShading: true }));
    cache.set(key, m);
  }
  if (!m) {
    const set = getPaintedTexSet('carPaint', hex);
    const p = new THREE.MeshPhysicalMaterial({ name: `carPaint_${hex.toString(16)}` });
    applySet(p, set, true, { normalScale: 0.6 });
    p.clearcoat = 0.35;
    p.clearcoatRoughness = 0.45;
    m = p;
    cache.set(key, m);
  }
  return m;
}

/** Cached clone of a base material with its colour multiplied by `hex`. */
export function getTintedMaterial(base: MaterialName, hex: number): THREE.MeshStandardMaterial {
  const key = `tint:${base}:${hex}`;
  let m = cache.get(key);
  if (!m) {
    m = getMaterial(base).clone();
    m.color.multiply(new THREE.Color(hex));
    m.name = `${base}_tint_${hex.toString(16)}`;
    cache.set(key, m);
  }
  return m;
}
