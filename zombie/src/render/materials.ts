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
    m = build(name);
    cache.set(name, m);
  }
  return m;
}

/** Weathered car paint (MeshPhysicalMaterial with a dull clearcoat), cached per colour. */
export function getCarPaint(hex: number): THREE.MeshStandardMaterial {
  const key = `car:${hex}`;
  let m = cache.get(key);
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
