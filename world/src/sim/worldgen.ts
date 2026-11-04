// Earth-like terrain generation. Elevation from warped fractal noise and
// ridged mountain ranges; temperature from latitude and altitude (lapse rate);
// moisture from distance to the sea plus noise; rivers traced downhill from
// wet highlands to the sea; biomes classified Whittaker-style from all three.

import { B, F, BIOMES } from './data.ts'
import { Noise2D, Rng, clamp, smoothstep, hash2 } from './rng.ts'

export interface Preset {
  id: string
  name: string
  blurb: string
  sea: number // fraction of tiles below sea level (approx)
  shape: 'continent' | 'islands' | 'valley' | 'highlands' | 'coast'
  latTop: number // temperature at the top edge (0 cold … 1 hot)
  latBottom: number
  moisture: number // bias -0.3..0.3
  mountains: number // 0..1
  rivers: number // count per 100x100 tiles
}

export const PRESETS: Preset[] = [
  { id: 'continent', name: 'Temperate Continent', blurb: 'A broad continent from cold northern taiga to warm southern plains, with mountain ranges and great rivers — like Europe.', sea: 0.38, shape: 'continent', latTop: 0.18, latBottom: 0.72, moisture: 0.05, mountains: 0.6, rivers: 7 },
  { id: 'crescent', name: 'Fertile Crescent', blurb: 'Two rivers carve a green valley through hot dry hills and desert — the cradle of agriculture.', sea: 0.18, shape: 'valley', latTop: 0.62, latBottom: 0.82, moisture: -0.22, mountains: 0.5, rivers: 5 },
  { id: 'archipelago', name: 'Tropical Archipelago', blurb: 'Lush volcanic islands in a warm sea. Fish are plentiful, but reaching new land needs boats.', sea: 0.6, shape: 'islands', latTop: 0.74, latBottom: 0.9, moisture: 0.2, mountains: 0.55, rivers: 4 },
  { id: 'highlands', name: 'Alpine Highlands', blurb: 'Snowy peaks, deep valleys and mountain lakes. Rich in ore, harsh in winter.', sea: 0.12, shape: 'highlands', latTop: 0.1, latBottom: 0.45, moisture: 0.1, mountains: 1, rivers: 8 },
  { id: 'savanna', name: 'Rift Valley', blurb: 'Savanna, lakes and volcanic highlands near the equator — where humanity first walked.', sea: 0.15, shape: 'coast', latTop: 0.7, latBottom: 0.8, moisture: -0.05, mountains: 0.7, rivers: 5 },
]

export interface WorldMaps {
  W: number
  H: number
  biome: Uint8Array
  elev: Float32Array
  temp: Float32Array
  moist: Float32Array
  feature: Uint8Array
  amount: Uint16Array
  fertility: Float32Array
}

export function generateTerrain(W: number, H: number, seed: number, preset: Preset): WorldMaps {
  const N = W * H
  const rng = new Rng(seed)
  const nE = new Noise2D(seed + 11)
  const nW = new Noise2D(seed + 23)
  const nM = new Noise2D(seed + 37)
  const nR = new Noise2D(seed + 41)
  const nT = new Noise2D(seed + 53)
  const nO = new Noise2D(seed + 67)

  const elev = new Float32Array(N)
  const scale = 1 / 64

  // Island centres for archipelago
  const islands: [number, number, number][] = []
  if (preset.shape === 'islands') {
    const count = Math.round((W * H) / 3800) + 3
    for (let i = 0; i < count; i++) islands.push([rng.range(0.12, 0.88) * W, rng.range(0.12, 0.88) * H, rng.range(0.08, 0.2) * Math.min(W, H)])
  }
  // Valley axis for crescent: a gentle curve across the map
  const valleyPhase = rng.range(0, Math.PI * 2)

  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const i = y * W + x
      const nx = x / W - 0.5
      const ny = y / H - 0.5
      // domain warp for organic coastlines
      const wx = x * scale + 1.6 * nW.fbm(x * scale * 0.7, y * scale * 0.7, 3)
      const wy = y * scale + 1.6 * nW.fbm(x * scale * 0.7 + 50, y * scale * 0.7 + 50, 3)
      let e = 0.5 + 0.5 * nE.fbm(wx, wy, 6)
      const ridge = nR.ridged(x * scale * 1.3 + 9, y * scale * 1.3 + 3, 5)
      const mountainMask = smoothstep(0.45, 0.75, 0.5 + 0.5 * nE.fbm(x * scale * 0.45 + 100, y * scale * 0.45, 3))
      e += preset.mountains * 0.45 * ridge * mountainMask

      const d = Math.sqrt(nx * nx + ny * ny) * 2 // 0 centre → ~1.4 corners
      switch (preset.shape) {
        case 'continent':
          e = e * 0.85 + 0.42 - d * 0.62
          break
        case 'islands': {
          let best = 0
          for (const [cx, cy, r] of islands) {
            const dd = Math.hypot(x - cx, y - cy) / r
            best = Math.max(best, 1 - dd)
          }
          e = e * 0.55 + best * 0.75 - 0.08
          break
        }
        case 'valley': {
          const axis = 0.5 + 0.12 * Math.sin(nx * 5 + valleyPhase)
          const dv = Math.abs(y / H - axis)
          e = e * 0.7 + 0.18 + smoothstep(0.02, 0.4, dv) * 0.35 - (x / W < 0.08 ? (0.08 - x / W) * 5 : 0)
          break
        }
        case 'highlands':
          e = e * 0.9 + 0.22 + ridge * 0.25 - Math.max(0, d - 1) * 0.6
          break
        case 'coast': {
          // sea along one side, land rising inland
          e = e * 0.85 + 0.12 + (x / W) * 0.45
          break
        }
      }
      // keep a ring of ocean at the map edges so it never looks cut off
      const edge = Math.min(x, y, W - 1 - x, H - 1 - y) / Math.min(W, H)
      if (preset.shape !== 'valley' && preset.shape !== 'coast') e -= Math.max(0, 0.06 - edge) * 6
      elev[i] = e
    }
  }

  // Normalise so that the requested fraction of tiles lies below sea level.
  const sorted = Float32Array.from(elev).sort()
  const seaLevel = sorted[Math.floor(clamp(preset.sea, 0.02, 0.95) * (N - 1))]
  const maxE = sorted[N - 1]
  for (let i = 0; i < N; i++) {
    const v = elev[i]
    elev[i] = v < seaLevel ? -1 + (v - sorted[0]) / (seaLevel - sorted[0] + 1e-6) : (v - seaLevel) / (maxE - seaLevel + 1e-6)
  }
  // Now: elev < 0 is underwater (−1 deepest), 0..1 land height.

  // Distance to ocean (BFS) for moisture & beaches.
  const distSea = new Int16Array(N).fill(-1)
  const q = new Int32Array(N)
  let qh = 0, qt = 0
  for (let i = 0; i < N; i++) if (elev[i] < 0) { distSea[i] = 0; q[qt++] = i }
  while (qh < qt) {
    const i = q[qh++]
    const x = i % W, y = (i / W) | 0
    const dd = distSea[i] + 1
    if (x > 0 && distSea[i - 1] < 0) { distSea[i - 1] = dd; q[qt++] = i - 1 }
    if (x < W - 1 && distSea[i + 1] < 0) { distSea[i + 1] = dd; q[qt++] = i + 1 }
    if (y > 0 && distSea[i - W] < 0) { distSea[i - W] = dd; q[qt++] = i - W }
    if (y < H - 1 && distSea[i + W] < 0) { distSea[i + W] = dd; q[qt++] = i + W }
  }

  const temp = new Float32Array(N)
  const moist = new Float32Array(N)
  for (let y = 0; y < H; y++) {
    const lat = preset.latTop + (preset.latBottom - preset.latTop) * (y / (H - 1))
    for (let x = 0; x < W; x++) {
      const i = y * W + x
      const h = Math.max(0, elev[i])
      temp[i] = clamp(lat - h * 0.55 + 0.08 * nT.fbm(x / 40, y / 40, 3), 0, 1)
      const ds = distSea[i] < 0 ? 99 : distSea[i]
      const coastal = 1 - smoothstep(0, 40, ds)
      moist[i] = clamp(0.45 + 0.35 * nM.fbm(x / 50, y / 50, 4) + coastal * 0.25 + preset.moisture - Math.max(0, h - 0.6) * 0.3, 0, 1)
    }
  }

  // Biomes (before rivers)
  const biome = new Uint8Array(N)
  for (let i = 0; i < N; i++) biome[i] = classify(elev[i], temp[i], moist[i], distSea[i])

  // Rivers: start in wet highlands, flow downhill to sea or a basin (lake).
  const riverCount = Math.round((preset.rivers * W * H) / 10000)
  const isRiver = new Uint8Array(N)
  let attempts = 0
  let made = 0
  while (made < riverCount && attempts < riverCount * 40) {
    attempts++
    const sx = rng.int(4, W - 5), sy = rng.int(4, H - 5)
    const s = sy * W + sx
    if (elev[s] < 0.45 || elev[s] > 0.92 || moist[s] < 0.35 || isRiver[s]) continue
    const path: number[] = []
    let cur = s
    const seen = new Set<number>()
    let ok = false
    for (let step = 0; step < W * 2; step++) {
      path.push(cur)
      seen.add(cur)
      if (elev[cur] < 0 || (isRiver[cur] && step > 0)) { ok = true; break }
      const cx = cur % W, cy = (cur / W) | 0
      let best = -1, bestE = elev[cur] + 0.002
      for (const [dx, dy] of DIRS4) {
        const nx = cx + dx, ny = cy + dy
        if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue
        const ni = ny * W + nx
        if (seen.has(ni)) continue
        // small noise lets rivers meander across flats
        const ee = elev[ni] + (hash2(nx, ny, seed) - 0.5) * 0.004
        if (ee < bestE) { bestE = ee; best = ni }
      }
      if (best < 0) {
        // basin → small lake
        if (path.length > 8) {
          for (const [dx, dy] of [[0, 0], ...DIRS4, [1, 1], [-1, -1], [1, -1], [-1, 1]]) {
            const nx = cx + dx, ny = cy + dy
            if (nx >= 0 && ny >= 0 && nx < W && ny < H) biome[ny * W + nx] = B.LAKE
          }
          ok = true
        }
        break
      }
      cur = best
    }
    if (ok && path.length > 10) {
      for (const p of path) {
        if (elev[p] >= 0 && biome[p] !== B.LAKE) { isRiver[p] = 1; biome[p] = B.RIVER }
      }
      made++
    }
  }
  // Rivers moisten their banks
  for (let i = 0; i < N; i++) {
    if (!isRiver[i] && biome[i] !== B.LAKE) continue
    const x = i % W, y = (i / W) | 0
    for (let dy = -4; dy <= 4; dy++) for (let dx = -4; dx <= 4; dx++) {
      const nx = x + dx, ny = y + dy
      if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue
      const ni = ny * W + nx
      const fall = 1 - Math.hypot(dx, dy) / 5
      if (fall > 0) moist[ni] = Math.min(1, moist[ni] + fall * 0.08)
    }
  }
  for (let i = 0; i < N; i++) {
    if (biome[i] === B.RIVER || biome[i] === B.LAKE) continue
    biome[i] = classify(elev[i], temp[i], moist[i], distSea[i])
  }

  // Fertility
  const fertility = new Float32Array(N)
  for (let i = 0; i < N; i++) fertility[i] = BIOMES[biome[i]].fertility * (0.6 + 0.4 * moist[i])

  // Features
  const feature = new Uint8Array(N)
  const amount = new Uint16Array(N)
  const ore = new Noise2D(seed + 91)
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const i = y * W + x
      const b = biome[i]
      const r = hash2(x, y, seed + 5)
      const clump = 0.5 + 0.5 * nO.fbm(x / 9, y / 9, 3)
      let f: number = F.NONE
      const nearWater = hasNeighbour(biome, W, H, x, y, (bb) => bb === B.RIVER || bb === B.LAKE, 2)
      switch (b) {
        case B.FOREST:
          if (r < 0.55 * clump + 0.1) f = F.TREE
          else if (r < 0.6 * clump + 0.16) f = F.BUSH
          break
        case B.RAINFOREST:
          if (r < 0.7 * clump + 0.12) f = F.JUNGLE
          else if (r < 0.75 * clump + 0.18) f = F.BUSH
          break
        case B.TAIGA:
          if (r < 0.55 * clump + 0.1) f = F.PINE
          else if (r < 0.58 * clump + 0.12) f = F.BUSH
          break
        case B.GRASSLAND:
          if (r < 0.06 * clump) f = F.TREE
          else if (r < 0.06 * clump + 0.05) f = F.BUSH
          else if (r < 0.06 * clump + 0.09) f = F.FLOWERS
          else if (nearWater && r < 0.2) f = F.FIBER
          break
        case B.SAVANNA:
          if (r < 0.035) f = F.TREE
          else if (r < 0.06) f = F.BUSH
          else if (nearWater && r < 0.14) f = F.FIBER
          break
        case B.DESERT:
          if (r < 0.025) f = F.CACTUS
          else if (r < 0.03 && moist[i] > 0.2) f = F.PALM
          break
        case B.BEACH:
          if (temp[i] > 0.6 && r < 0.1) f = F.PALM
          break
        case B.MARSH:
          if (r < 0.3) f = F.FIBER
          else if (r < 0.36) f = F.TREE
          break
        case B.TUNDRA:
          if (r < 0.04) f = F.BUSH
          else if (r < 0.07) f = F.ROCK
          break
        case B.HILLS:
          if (r < 0.12) f = F.ROCK
          else if (r < 0.2 && temp[i] < 0.4) f = F.PINE
          else if (r < 0.2) f = F.TREE
          break
        case B.MOUNTAIN:
          if (r < 0.22) f = F.ROCK
          break
      }
      // Ore bodies cluster in hills and mountains
      if (b === B.HILLS || b === B.MOUNTAIN) {
        const o = ore.noise(x / 6, y / 6)
        const o2 = ore.noise(x / 7 + 40, y / 7 + 40)
        if (o > 0.62) f = F.IRON
        else if (o < -0.66) f = F.COPPER
        else if (o2 > 0.68) f = F.COAL
        else if (o2 < -0.72) f = F.TIN
        else if (r > 0.992) f = F.GOLD
      }
      if (b === B.DESERT || b === B.MARSH || b === B.TUNDRA) {
        if (ore.noise(x / 8 + 90, y / 8 + 90) > 0.74) f = F.OIL
      }
      // Clay along river banks and lake shores
      if (f === F.NONE && nearWater && b !== B.RIVER && b !== B.LAKE && r > 0.9) f = F.CLAY
      // Occasional surface stones and copper near the start so the stone age is playable
      if (f === F.NONE && (b === B.GRASSLAND || b === B.SAVANNA || b === B.FOREST) && r > 0.985) f = F.ROCK
      feature[i] = f
      if (f !== F.NONE) amount[i] = featureAmount(f, r)
    }
  }

  return { W, H, biome, elev, temp, moist, feature, amount, fertility }
}

export const DIRS4: [number, number][] = [[1, 0], [-1, 0], [0, 1], [0, -1]]
export const DIRS8: [number, number][] = [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [-1, -1], [1, -1], [-1, 1]]

function hasNeighbour(biome: Uint8Array, W: number, H: number, x: number, y: number, pred: (b: number) => boolean, r: number): boolean {
  for (let dy = -r; dy <= r; dy++) for (let dx = -r; dx <= r; dx++) {
    const nx = x + dx, ny = y + dy
    if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue
    if (pred(biome[ny * W + nx])) return true
  }
  return false
}

const AMOUNTS: Record<number, [number, number]> = {
  [F.TREE]: [6, 12], [F.PINE]: [6, 12], [F.PALM]: [4, 8], [F.JUNGLE]: [8, 16], [F.BUSH]: [5, 10],
  [F.ROCK]: [12, 30], [F.CLAY]: [15, 30], [F.COPPER]: [20, 40], [F.TIN]: [15, 30], [F.IRON]: [25, 50],
  [F.COAL]: [25, 50], [F.GOLD]: [6, 14], [F.OIL]: [40, 80], [F.FIBER]: [5, 10],
}
export function featureAmount(f: number, r: number): number {
  const a = AMOUNTS[f]
  if (!a) return 1
  return Math.round(a[0] + (a[1] - a[0]) * r)
}

function classify(e: number, t: number, m: number, distSea: number): number {
  if (e < 0) {
    if (e > -0.18) return B.SHALLOWS
    if (e > -0.55) return B.OCEAN
    return B.DEEP_OCEAN
  }
  if (e > 0.9 || (e > 0.8 && t < 0.25)) return B.PEAK
  if (e > 0.72) return B.MOUNTAIN
  if (distSea >= 0 && distSea <= 1 && e < 0.06 && t > 0.2) return B.BEACH
  if (t < 0.12) return B.SNOW
  if (e > 0.55) return t < 0.25 ? B.TUNDRA : B.HILLS
  if (t < 0.24) return m > 0.5 ? B.TAIGA : B.TUNDRA
  if (m > 0.78 && e < 0.12) return B.MARSH
  if (t < 0.55) {
    if (m < 0.28) return B.GRASSLAND
    return m > 0.52 ? B.FOREST : B.GRASSLAND
  }
  // warm / hot
  if (m < 0.3) return B.DESERT
  if (m < 0.45) return B.SAVANNA
  if (m > 0.66 && t > 0.7) return B.RAINFOREST
  return m > 0.55 ? B.FOREST : B.GRASSLAND
}
