// Static world data: terrain biomes, natural features (resource nodes), items.
// Values are loosely grounded in reality — fresh water is drinkable but sea
// water is not, ores sit in hills and mountains, clay by rivers, sand on
// beaches and deserts, palms in the hot coasts, pines in the cold north.

// Plain objects rather than enums so the code runs under type-stripping (node --test).
export const B = {
  DEEP_OCEAN: 0, OCEAN: 1, SHALLOWS: 2, LAKE: 3, RIVER: 4, BEACH: 5, GRASSLAND: 6, FOREST: 7, RAINFOREST: 8,
  SAVANNA: 9, DESERT: 10, TAIGA: 11, TUNDRA: 12, SNOW: 13, MARSH: 14, HILLS: 15, MOUNTAIN: 16, PEAK: 17,
} as const
export type B = number

export interface BiomeDef {
  id: B
  name: string
  color: [number, number, number]
  move: number // movement cost multiplier; Infinity = impassable on foot
  water: boolean
  fresh: boolean // drinkable fresh water
  fertility: number // 0..1 for farming
}

const INF = Number.POSITIVE_INFINITY

export const BIOMES: BiomeDef[] = [
  { id: B.DEEP_OCEAN, name: 'Deep ocean', color: [18, 52, 96], move: INF, water: true, fresh: false, fertility: 0 },
  { id: B.OCEAN, name: 'Ocean', color: [27, 76, 128], move: INF, water: true, fresh: false, fertility: 0 },
  { id: B.SHALLOWS, name: 'Coastal shallows', color: [44, 118, 160], move: INF, water: true, fresh: false, fertility: 0 },
  { id: B.LAKE, name: 'Lake', color: [44, 112, 160], move: INF, water: true, fresh: true, fertility: 0 },
  { id: B.RIVER, name: 'River', color: [56, 128, 176], move: 3.5, water: true, fresh: true, fertility: 0 },
  { id: B.BEACH, name: 'Beach', color: [221, 205, 152], move: 1.2, water: false, fresh: false, fertility: 0.1 },
  { id: B.GRASSLAND, name: 'Grassland', color: [118, 168, 82], move: 1, water: false, fresh: false, fertility: 0.9 },
  { id: B.FOREST, name: 'Temperate forest', color: [64, 128, 62], move: 1.5, water: false, fresh: false, fertility: 0.7 },
  { id: B.RAINFOREST, name: 'Rainforest', color: [36, 112, 54], move: 2, water: false, fresh: false, fertility: 0.6 },
  { id: B.SAVANNA, name: 'Savanna', color: [176, 168, 86], move: 1, water: false, fresh: false, fertility: 0.55 },
  { id: B.DESERT, name: 'Desert', color: [222, 192, 132], move: 1.4, water: false, fresh: false, fertility: 0.05 },
  { id: B.TAIGA, name: 'Boreal forest', color: [58, 100, 78], move: 1.6, water: false, fresh: false, fertility: 0.35 },
  { id: B.TUNDRA, name: 'Tundra', color: [150, 158, 128], move: 1.3, water: false, fresh: false, fertility: 0.15 },
  { id: B.SNOW, name: 'Snowfield', color: [232, 238, 244], move: 2, water: false, fresh: true, fertility: 0 },
  { id: B.MARSH, name: 'Marshland', color: [86, 124, 92], move: 2.4, water: false, fresh: true, fertility: 0.5 },
  { id: B.HILLS, name: 'Hills', color: [128, 140, 92], move: 2, water: false, fresh: false, fertility: 0.4 },
  { id: B.MOUNTAIN, name: 'Mountains', color: [124, 116, 108], move: 4, water: false, fresh: false, fertility: 0 },
  { id: B.PEAK, name: 'Snow-capped peak', color: [236, 240, 246], move: INF, water: false, fresh: true, fertility: 0 },
]

// --- Natural features ---------------------------------------------------------

export const F = {
  NONE: 0, TREE: 1, PINE: 2, PALM: 3, JUNGLE: 4, BUSH: 5, ROCK: 6, CLAY: 7, COPPER: 8, TIN: 9, IRON: 10, COAL: 11,
  GOLD: 12, OIL: 13, FIBER: 14, CACTUS: 15, SAPLING: 16, FLOWERS: 17,
} as const
export type F = number

export type Item =
  | 'berries' | 'meat' | 'fish' | 'grain' | 'bread'
  | 'wood' | 'stone' | 'fiber' | 'hide' | 'clay' | 'sand' | 'copper' | 'tin' | 'iron_ore' | 'coal' | 'gold' | 'oil'
  | 'planks' | 'bricks' | 'pottery' | 'cloth' | 'clothes' | 'bronze' | 'iron' | 'steel' | 'glass' | 'paper'
  | 'concrete' | 'machinery' | 'electronics' | 'fuel' | 'medicine'
  | 'bauxite' | 'uranium' | 'lithium' | 'aluminium' | 'plastic' | 'batteries'

export interface FeatureDef {
  id: F
  name: string
  item: Item | null
  amount: [number, number]
  hoursPerUnit: number
  tech: string | null // required tech to harvest
  regrow: boolean
}

export const FEATURES: FeatureDef[] = [
  { id: F.NONE, name: '', item: null, amount: [0, 0], hoursPerUnit: 0, tech: null, regrow: false },
  { id: F.TREE, name: 'Oak tree', item: 'wood', amount: [6, 12], hoursPerUnit: 0.35, tech: null, regrow: true },
  { id: F.PINE, name: 'Pine tree', item: 'wood', amount: [6, 12], hoursPerUnit: 0.35, tech: null, regrow: true },
  { id: F.PALM, name: 'Palm tree', item: 'wood', amount: [4, 8], hoursPerUnit: 0.35, tech: null, regrow: true },
  { id: F.JUNGLE, name: 'Jungle tree', item: 'wood', amount: [8, 16], hoursPerUnit: 0.4, tech: null, regrow: true },
  { id: F.BUSH, name: 'Berry bush', item: 'berries', amount: [5, 10], hoursPerUnit: 0.2, tech: null, regrow: true },
  { id: F.ROCK, name: 'Rock outcrop', item: 'stone', amount: [12, 30], hoursPerUnit: 0.45, tech: null, regrow: false },
  { id: F.CLAY, name: 'Clay bank', item: 'clay', amount: [15, 30], hoursPerUnit: 0.3, tech: null, regrow: false },
  { id: F.COPPER, name: 'Copper vein', item: 'copper', amount: [20, 40], hoursPerUnit: 0.6, tech: 'mining', regrow: false },
  { id: F.TIN, name: 'Tin deposit', item: 'tin', amount: [15, 30], hoursPerUnit: 0.6, tech: 'mining', regrow: false },
  { id: F.IRON, name: 'Iron ore', item: 'iron_ore', amount: [25, 50], hoursPerUnit: 0.7, tech: 'mining', regrow: false },
  { id: F.COAL, name: 'Coal seam', item: 'coal', amount: [25, 50], hoursPerUnit: 0.5, tech: 'mining', regrow: false },
  { id: F.GOLD, name: 'Gold deposit', item: 'gold', amount: [6, 14], hoursPerUnit: 0.9, tech: 'mining', regrow: false },
  { id: F.OIL, name: 'Oil seep', item: 'oil', amount: [40, 80], hoursPerUnit: 0.4, tech: 'refining', regrow: false },
  { id: F.FIBER, name: 'Reeds & flax', item: 'fiber', amount: [5, 10], hoursPerUnit: 0.2, tech: null, regrow: true },
  { id: F.CACTUS, name: 'Cactus', item: null, amount: [0, 0], hoursPerUnit: 0, tech: null, regrow: false },
  { id: F.SAPLING, name: 'Sapling', item: null, amount: [0, 0], hoursPerUnit: 0, tech: null, regrow: false },
  { id: F.FLOWERS, name: 'Wildflowers', item: null, amount: [0, 0], hoursPerUnit: 0, tech: null, regrow: false },
]

export const TREE_FEATURES = new Set<F>([F.TREE, F.PINE, F.PALM, F.JUNGLE])

export interface ItemDef {
  name: string
  food?: number // nutrition per unit (hunger points removed)
  icon: string
}

export const ITEMS: Record<Item, ItemDef> = {
  berries: { name: 'Berries', food: 14, icon: '🫐' },
  meat: { name: 'Meat', food: 30, icon: '🍖' },
  fish: { name: 'Fish', food: 24, icon: '🐟' },
  grain: { name: 'Grain', food: 18, icon: '🌾' },
  bread: { name: 'Bread', food: 38, icon: '🍞' },
  wood: { name: 'Wood', icon: '🪵' },
  stone: { name: 'Stone', icon: '🪨' },
  fiber: { name: 'Fibre', icon: '🌿' },
  hide: { name: 'Hide', icon: '🦌' },
  clay: { name: 'Clay', icon: '🟫' },
  sand: { name: 'Sand', icon: '⏳' },
  copper: { name: 'Copper', icon: '🟠' },
  tin: { name: 'Tin', icon: '⚪' },
  iron_ore: { name: 'Iron ore', icon: '⛏️' },
  coal: { name: 'Coal', icon: '⚫' },
  gold: { name: 'Gold', icon: '🪙' },
  oil: { name: 'Oil', icon: '🛢️' },
  planks: { name: 'Planks', icon: '🪚' },
  bricks: { name: 'Bricks', icon: '🧱' },
  pottery: { name: 'Pottery', icon: '🏺' },
  cloth: { name: 'Cloth', icon: '🧵' },
  clothes: { name: 'Clothes', icon: '🧥' },
  bronze: { name: 'Bronze', icon: '🔶' },
  iron: { name: 'Iron', icon: '🔩' },
  steel: { name: 'Steel', icon: '🏗️' },
  glass: { name: 'Glass', icon: '🪟' },
  paper: { name: 'Paper', icon: '📜' },
  concrete: { name: 'Concrete', icon: '🧊' },
  machinery: { name: 'Machinery', icon: '⚙️' },
  electronics: { name: 'Electronics', icon: '💡' },
  fuel: { name: 'Fuel', icon: '⛽' },
  medicine: { name: 'Medicine', icon: '💊' },
  bauxite: { name: 'Bauxite', icon: '🟤' },
  uranium: { name: 'Uranium ore', icon: '☢️' },
  lithium: { name: 'Lithium brine', icon: '🧂' },
  aluminium: { name: 'Aluminium', icon: '🥫' },
  plastic: { name: 'Plastic', icon: '🧴' },
  batteries: { name: 'Batteries', icon: '🔋' },
}

export const ALL_ITEMS = Object.keys(ITEMS) as Item[]
export const FOOD_ITEMS = ALL_ITEMS.filter((i) => ITEMS[i].food) as Item[]

/** Gatherable raw resources an agent can name in a plan. */
export const GATHERABLE: Item[] = ['wood', 'stone', 'berries', 'fiber', 'clay', 'sand', 'copper', 'tin', 'iron_ore', 'coal', 'gold', 'oil', 'bauxite', 'uranium', 'lithium']

// ------------------------------------------------------------------ geology
//
// Minerals lie where the rocks that hold them formed, mostly out of sight.
// Copper, gold, tin and uranium come from the roots of mountain belts; the
// great iron ores from ancient shield rock; coal from buried swamp forests
// and oil from deep sedimentary basins (on land and under the sea); bauxite
// from deeply weathered tropical soils; lithium from desert salt flats.
// Only a few bodies break the surface. The rest must be found by
// prospecting and reached by digging — deeper as technology allows.

export const D = { NONE: 0, COPPER: 1, TIN: 2, IRON: 3, COAL: 4, GOLD: 5, OIL: 6, BAUXITE: 7, URANIUM: 8, LITHIUM: 9 } as const
export type D = number

export interface DepositDef {
  id: D
  name: string
  item: Item
  /** the surface showing that hints at it */
  outcrop: F | null
  color: string
  /** a bright, distinct colour for the geology map */
  map: string
  /** units per tile of the ore body */
  grade: [number, number]
  /** extracted by: 'mine' (shafts and pits), 'well' (drilling), 'brine' (evaporation ponds) */
  via: 'mine' | 'well' | 'brine'
}

export const DEPOSITS: DepositDef[] = [
  { id: D.NONE, name: '', item: 'stone', outcrop: null, map: '#000000', color: '#000', grade: [0, 0], via: 'mine' },
  { id: D.COPPER, name: 'Copper porphyry', item: 'copper', outcrop: F.COPPER, map: '#ff8c42', color: '#d9774a', grade: [120, 320], via: 'mine' },
  { id: D.TIN, name: 'Tin lode', item: 'tin', outcrop: F.TIN, map: '#9ad1ff', color: '#c9d1d9', grade: [60, 160], via: 'mine' },
  { id: D.IRON, name: 'Iron formation', item: 'iron_ore', outcrop: F.IRON, map: '#e63946', color: '#a2462e', grade: [200, 600], via: 'mine' },
  { id: D.COAL, name: 'Coal seam', item: 'coal', outcrop: F.COAL, map: '#a78bfa', color: '#2a2a30', grade: [200, 500], via: 'mine' },
  { id: D.GOLD, name: 'Gold vein', item: 'gold', outcrop: F.GOLD, map: '#ffd60a', color: '#ffd34a', grade: [20, 70], via: 'mine' },
  { id: D.OIL, name: 'Oil reservoir', item: 'oil', outcrop: F.OIL, map: '#ff2fa0', color: '#141018', grade: [300, 900], via: 'well' },
  { id: D.BAUXITE, name: 'Bauxite laterite', item: 'bauxite', outcrop: null, map: '#c2733a', color: '#b5651d', grade: [150, 400], via: 'mine' },
  { id: D.URANIUM, name: 'Uranium deposit', item: 'uranium', outcrop: null, map: '#7cff4f', color: '#9be564', grade: [20, 60], via: 'mine' },
  { id: D.LITHIUM, name: 'Lithium brine', item: 'lithium', outcrop: null, map: '#3ef0e0', color: '#e8f4ff', grade: [80, 200], via: 'brine' },
]

/** Depths: 0 = at the surface, 1 = shallow (pits, adits), 2 = deep (shafts), 3 = very deep (pumped shafts, deep wells). */
export const DEPTH_NAMES = ['at the surface', 'shallow', 'deep', 'very deep']
export const DEPTH_METRES = ['0–5 m', '5–40 m', '40–300 m', '300 m +']

export type Inventory = Partial<Record<Item, number>>

export function invCount(inv: Inventory, item: Item): number {
  return inv[item] ?? 0
}
export function invTotal(inv: Inventory): number {
  let t = 0
  for (const k in inv) t += inv[k as Item] ?? 0
  return t
}
export function invAdd(inv: Inventory, item: Item, n: number): void {
  const v = (inv[item] ?? 0) + n
  if (v <= 0) delete inv[item]
  else inv[item] = v
}
export function invHas(inv: Inventory, cost: Inventory): boolean {
  for (const k in cost) if ((inv[k as Item] ?? 0) < (cost[k as Item] ?? 0)) return false
  return true
}
export function invFoodTotal(inv: Inventory): number {
  let t = 0
  for (const f of FOOD_ITEMS) t += (inv[f] ?? 0) * (ITEMS[f].food ?? 0)
  return t
}
export function invToString(inv: Inventory): string {
  const parts: string[] = []
  for (const k in inv) {
    const n = inv[k as Item] ?? 0
    if (n > 0) parts.push(`${k} ${Math.floor(n)}`)
  }
  return parts.length ? parts.join(', ') : 'nothing'
}
