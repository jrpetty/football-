/**
 * Survival Island — world generator and deterministic game engine.
 *
 * The engine is a pure state machine: `stepIsland(world, state, command)`
 * applies one action and advances time by one phase (plus the night after
 * the Evening turn). Every random outcome during play is drawn from a
 * stream forked by a label that names *when* it happens (e.g. the fish
 * catch on day 4 afternoon), so the same action at the same moment has the
 * same result for every model.
 */
import type { IslandEventTag, IslandSimFrame, IslandSimWorld, ReplayFrame, Rng, XY } from '../../core/types.ts';
import { clamp, truncate } from './agentic-common.ts';

export type Terrain = 'sea' | 'beach' | 'palm' | 'grass' | 'bush' | 'forest' | 'rocks' | 'summit' | 'spring' | 'cave';
export type Weather = 'clear' | 'cloudy' | 'hot' | 'rain' | 'storm';
export type Dir = 'N' | 'S' | 'E' | 'W';

export const PHASES = ['Morning', 'Afternoon', 'Evening'] as const;

export interface Pos {
  x: number;
  y: number;
}

export interface IslandTile {
  terrain: Terrain;
  stock: number;
  max: number;
  /** Days per regrown unit; 0 = never regrows. */
  regrowDays: number;
  /** Berry colour (bush tiles only). */
  berry?: string;
}

export interface IslandConfig {
  maxDays: number;
  size: number;
  inventoryCap: number;
  /** Range [min, max] for the ship's first passing day. */
  shipFirstDay: [number, number];
  /** Range [min, max] for the days between passings. */
  shipEveryDays: [number, number];
  /** Passes with a blazing signal needed; the last one brings the rescue boat. */
  signalsNeeded: number;
  /** A pass only counts if a campfire burned somewhere on the island the night before. */
  requireNightFire: boolean;
  stormChance: number;
  rainChance: number;
  /** Range [min, max] of the base night cold. */
  nightCold: [number, number];
  /** Water restored by DRINK at the spring (100 = drink your fill). */
  drinkAmount: number;
  berryBushes: number;
  poisonBushes: number;
  poisonDamage: number;
}

/** The standard island (Survival Island 1.1.0). */
export const ISLAND_DEFAULTS: IslandConfig = {
  maxDays: 12,
  size: 12,
  inventoryCap: 12,
  shipFirstDay: [4, 5],
  shipEveryDays: [3, 4],
  signalsNeeded: 1,
  requireNightFire: true,
  stormChance: 0.1,
  rainChance: 0.18,
  nightCold: [8, 18],
  drinkAmount: 100,
  berryBushes: 4,
  poisonBushes: 2,
  poisonDamage: 20,
};

export interface IslandWorld {
  size: number;
  maxDays: number;
  inventoryCap: number;
  /** Initial tiles, [y][x]. */
  tiles: IslandTile[][];
  start: Pos;
  summit: Pos;
  spring: Pos;
  cave: Pos;
  bottle: Pos;
  /** Weather per day, index 1..maxDays (index 0 unused). */
  weather: Weather[];
  /** Base night cold per day (warmth lost in the open). */
  nightCold: number[];
  shipFirst: number;
  shipPeriod: number;
  /** Days on which the ship actually passes (stormy days are skipped). */
  shipDays: number[];
  signalsNeeded: number;
  requireNightFire: boolean;
  drinkAmount: number;
  poisonDamage: number;
  safeBerry: string;
  poisonBerry: string;
  /** Root stream for in-play rolls (forked per labelled event). */
  rolls: Rng;
}

export interface IslandState {
  day: number;
  /** 0 = Morning, 1 = Afternoon, 2 = Evening. */
  phase: number;
  pos: Pos;
  health: number;
  food: number;
  water: number;
  energy: number;
  warmth: number;
  inv: Record<string, number>;
  spear: boolean;
  /** "x,y" → nights of fuel left. */
  fires: Record<string, number>;
  shelters: string[];
  signal: 'none' | 'built' | 'lit';
  /** Quarter-day index when the signal was lit (Morning=0 … Night=3). */
  signalLitAt: number;
  tiles: IslandTile[][];
  explored: boolean[][];
  bottleFound: boolean;
  /** Days the ship passed (always noticed: seen or heard). */
  shipsSeen: number[];
  /** Days the crew counted your signal. */
  sightings: number[];
  /** Nights (by day number) during which a campfire burned somewhere on the island. */
  fireNights: number[];
  poisonEaten: number;
  alive: boolean;
  rescued: boolean;
  rescueDay: number | null;
  over: boolean;
  cause: string | null;
  nightsSurvived: number;
  turn: number;
  invalid: number;
  failed: number;
  milestones: { spear: number | null; campfire: number | null; shelter: number | null; signalPile: number | null; signalLit: number | null };
  lastHurt: { cause: string; amount: number } | null;
}

export type IslandCommand =
  | { kind: 'move'; dir: Dir; steps: number }
  | { kind: 'gather' }
  | { kind: 'drink' }
  | { kind: 'eat'; item: string }
  | { kind: 'craft' }
  | { kind: 'build'; what: 'fire' | 'shelter' | 'signal' }
  | { kind: 'light' }
  | { kind: 'fish' }
  | { kind: 'cook' }
  | { kind: 'rest' };

export interface IslandStep {
  /** Day and phase index the turn was played in. */
  day: number;
  phase: number;
  label: string;
  action: string;
  outcome: string;
  /** Extra things that happened after the action (ship, bottle, night, death…). */
  events: string[];
  valid: boolean;
  tone: 'good' | 'bad' | 'neutral';
  /** Night report when this turn ended the day. */
  night: string | null;
  /** Stats just before the night resolved (only when `night` is set). */
  preNight: { health: number; food: number; water: number; energy: number; warmth: number } | null;
}

// ─────────────────────────────────────────────────────────────────────────────
// Static tables
// ─────────────────────────────────────────────────────────────────────────────

const TERRAIN_CHAR: Record<Terrain, string> = {
  sea: '~',
  beach: '.',
  palm: 'P',
  grass: ',',
  bush: '*',
  forest: 'T',
  rocks: '^',
  summit: 'A',
  spring: 'S',
  cave: 'C',
};

const TERRAIN_NAME: Record<Terrain, string> = {
  sea: 'sea',
  beach: 'beach',
  palm: 'palm grove',
  grass: 'grassland',
  bush: 'berry bush',
  forest: 'forest',
  rocks: 'rocks',
  summit: 'summit',
  spring: 'spring',
  cave: 'cave',
};

const BERRY_COLOURS = ['red', 'blue', 'purple', 'yellow', 'white', 'black'] as const;

const DIRS: Record<Dir, Pos> = { N: { x: 0, y: -1 }, S: { x: 0, y: 1 }, E: { x: 1, y: 0 }, W: { x: -1, y: 0 } };
const DIR_WORD: Record<Dir, string> = { N: 'north', S: 'south', E: 'east', W: 'west' };

/** Longest walk (tiles in a straight line) a single MOVE can make. */
export const MAX_STEPS = 3;

const COST = { move: 4, stormMove: 7, gather: 6, drink: 1, eat: 1, craft: 5, build: 10, light: 2, fish: 8, cook: 2 };

const RECIPES = {
  spear: { wood: 1, stone: 1, fibre: 1 },
  fire: { wood: 3, stone: 1 },
  shelter: { wood: 4, fibre: 3 },
  signal: { wood: 5, fibre: 2 },
} as const;

const WEATHER_TEXT: Record<Weather, string> = {
  clear: 'clear — sunny and mild',
  cloudy: 'cloudy — grey and cool',
  hot: 'hot — scorching sun, you dehydrate faster',
  rain: 'rain — cold and wet',
  storm: 'storm — lashing wind and rain; dangerous in the open',
};

const COLS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ';

export const ISLAND_LEGEND: Record<string, { label: string; color?: string; emoji?: string }> = {
  '~': { label: 'Sea', color: '#1D4ED8', emoji: '🌊' },
  '.': { label: 'Beach', color: '#FCD34D', emoji: '🏖️' },
  P: { label: 'Palm (coconuts)', color: '#65A30D', emoji: '🌴' },
  ',': { label: 'Grass (fibre)', color: '#86EFAC', emoji: '🌾' },
  '*': { label: 'Berry bush', color: '#A855F7', emoji: '🫐' },
  T: { label: 'Forest (wood)', color: '#166534', emoji: '🌲' },
  '^': { label: 'Rocks (stone)', color: '#9CA3AF', emoji: '🪨' },
  A: { label: 'Summit', color: '#57534E', emoji: '⛰️' },
  S: { label: 'Spring', color: '#38BDF8', emoji: '💧' },
  C: { label: 'Cave', color: '#374151', emoji: '🕳️' },
  F: { label: 'Campfire', color: '#F97316', emoji: '🔥' },
  H: { label: 'Shelter', color: '#A16207', emoji: '🛖' },
  '#': { label: 'Signal pile', color: '#78350F', emoji: '🪵' },
  '!': { label: 'Signal fire (lit)', color: '#DC2626', emoji: '🔥' },
  m: { label: 'Message in a bottle', color: '#0EA5E9', emoji: '🍾' },
  '@': { label: 'Castaway', color: '#EF4444', emoji: '🧍' },
};

// ─────────────────────────────────────────────────────────────────────────────
// Generation
// ─────────────────────────────────────────────────────────────────────────────

const key = (p: Pos): string => `${p.x},${p.y}`;
const same = (a: Pos, b: Pos): boolean => a.x === b.x && a.y === b.y;
export const coordName = (p: Pos): string => `${COLS[p.x]}${p.y + 1}`;

function valueNoise(rng: Rng, size: number, cells: number): number[][] {
  const pts = Array.from({ length: cells + 1 }, () => Array.from({ length: cells + 1 }, () => rng.next()));
  const smooth = (t: number): number => t * t * (3 - 2 * t);
  const out: number[][] = [];
  for (let y = 0; y < size; y++) {
    const row: number[] = [];
    for (let x = 0; x < size; x++) {
      const gx = (x / (size - 1)) * cells;
      const gy = (y / (size - 1)) * cells;
      const x0 = Math.min(cells - 1, Math.floor(gx));
      const y0 = Math.min(cells - 1, Math.floor(gy));
      const tx = smooth(gx - x0);
      const ty = smooth(gy - y0);
      const a = pts[y0]![x0]! * (1 - tx) + pts[y0]![x0 + 1]! * tx;
      const b = pts[y0 + 1]![x0]! * (1 - tx) + pts[y0 + 1]![x0 + 1]! * tx;
      row.push(a * (1 - ty) + b * ty);
    }
    out.push(row);
  }
  return out;
}

/** Shortest walking distances (4-neighbour, land only) from `from`. -1 = unreachable. */
export function pathDistances(tiles: IslandTile[][], from: Pos): number[][] {
  const n = tiles.length;
  const dist = Array.from({ length: n }, () => Array.from({ length: n }, () => -1));
  dist[from.y]![from.x] = 0;
  const queue: Pos[] = [from];
  for (let i = 0; i < queue.length; i++) {
    const p = queue[i]!;
    for (const d of Object.values(DIRS)) {
      const q = { x: p.x + d.x, y: p.y + d.y };
      if (q.x < 0 || q.y < 0 || q.x >= n || q.y >= n) continue;
      if (tiles[q.y]![q.x]!.terrain === 'sea' || dist[q.y]![q.x]! >= 0) continue;
      dist[q.y]![q.x] = dist[p.y]![p.x]! + 1;
      queue.push(q);
    }
  }
  return dist;
}

function makeTile(terrain: Terrain): IslandTile {
  switch (terrain) {
    case 'beach':
      return { terrain, stock: 2, max: 2, regrowDays: 0 }; // driftwood
    case 'palm':
      return { terrain, stock: 3, max: 3, regrowDays: 2 }; // coconuts
    case 'grass':
      return { terrain, stock: 4, max: 4, regrowDays: 1 }; // fibre
    case 'bush':
      return { terrain, stock: 5, max: 5, regrowDays: 1 }; // berries
    case 'forest':
      return { terrain, stock: 4, max: 4, regrowDays: 1 }; // wood
    case 'rocks':
    case 'summit':
      return { terrain, stock: 4, max: 4, regrowDays: 0 }; // stone
    default:
      return { terrain, stock: 0, max: 0, regrowDays: 0 };
  }
}

function spreadPick(rng: Rng, candidates: Pos[], count: number, minGap: number): Pos[] {
  const out: Pos[] = [];
  for (const p of rng.shuffle(candidates)) {
    if (out.length >= count) break;
    if (out.every((q) => Math.max(Math.abs(p.x - q.x), Math.abs(p.y - q.y)) >= minGap)) out.push(p);
  }
  return out;
}

interface MapLayout {
  tiles: IslandTile[][];
  start: Pos;
  summit: Pos;
  spring: Pos;
  cave: Pos;
  bottle: Pos;
  safeBerry: string;
  poisonBerry: string;
}

function tryLayout(rng: Rng, n: number, bushCount: number, poisonCount: number): MapLayout | null {
  const c = (n - 1) / 2;
  const noise = valueNoise(rng, n, 3);
  const noise2 = valueNoise(rng, n, 3);
  const elev: number[][] = [];
  for (let y = 0; y < n; y++) {
    const row: number[] = [];
    for (let x = 0; x < n; x++) {
      const d = Math.hypot((x - c) / (n / 2), (y - c) / (n / 2));
      row.push(1 - 1.1 * d + (noise[y]![x]! - 0.5) * 0.7);
    }
    elev.push(row);
  }
  const isLand = (x: number, y: number): boolean => x > 0 && y > 0 && x < n - 1 && y < n - 1 && elev[y]![x]! > 0.22;

  // Keep the largest connected landmass.
  const comp = Array.from({ length: n }, () => Array.from({ length: n }, () => -1));
  const sizes: number[] = [];
  for (let y = 0; y < n; y++)
    for (let x = 0; x < n; x++) {
      if (!isLand(x, y) || comp[y]![x]! >= 0) continue;
      const id = sizes.length;
      let count = 0;
      const stack: Pos[] = [{ x, y }];
      comp[y]![x] = id;
      while (stack.length) {
        const p = stack.pop()!;
        count++;
        for (const d of Object.values(DIRS)) {
          const qx = p.x + d.x;
          const qy = p.y + d.y;
          if (isLand(qx, qy) && comp[qy]![qx]! < 0) {
            comp[qy]![qx] = id;
            stack.push({ x: qx, y: qy });
          }
        }
      }
      sizes.push(count);
    }
  if (sizes.length === 0) return null;
  const main = sizes.indexOf(Math.max(...sizes));
  const land = (x: number, y: number): boolean => x >= 0 && y >= 0 && x < n && y < n && comp[y]![x] === main;
  const landCount = sizes[main]!;
  if (landCount < Math.round(n * n * 0.33) || landCount > Math.round(n * n * 0.56)) return null;

  const terrain: Terrain[][] = Array.from({ length: n }, () => Array.from({ length: n }, () => 'sea' as Terrain));
  const interior: Pos[] = [];
  const beach: Pos[] = [];
  for (let y = 0; y < n; y++)
    for (let x = 0; x < n; x++) {
      if (!land(x, y)) continue;
      const coastal = Object.values(DIRS).some((d) => !land(x + d.x, y + d.y));
      if (coastal) {
        terrain[y]![x] = 'beach';
        beach.push({ x, y });
      } else interior.push({ x, y });
    }
  if (interior.length < 22) return null;

  const byElev = interior.slice().sort((a, b) => elev[b.y]![b.x]! - elev[a.y]![a.x]!);
  const summit = byElev[0]!;
  terrain[summit.y]![summit.x] = 'summit';
  const rockCount = Math.max(3, Math.round(interior.length * 0.14));
  const rocks = byElev.slice(1, 1 + rockCount);
  for (const p of rocks) terrain[p.y]![p.x] = 'rocks';
  const rest = byElev.slice(1 + rockCount).sort((a, b) => noise2[b.y]![b.x]! - noise2[a.y]![a.x]!);
  const forestCount = Math.round(rest.length * 0.45);
  rest.forEach((p, i) => (terrain[p.y]![p.x] = i < forestCount ? 'forest' : 'grass'));
  if (forestCount < 5 || rest.length - forestCount < 6) return null;

  const tiles = terrain.map((row) => row.map((t) => makeTile(t)));
  const fromSummit = pathDistances(tiles, summit);
  const startCands = beach.filter((p) => fromSummit[p.y]![p.x]! >= 5);
  if (startCands.length === 0) return null;
  const start = rng.pick(startCands);
  const fromStart = pathDistances(tiles, start);

  const springCands = rest.filter((p) => {
    const d = fromStart[p.y]![p.x]!;
    return d >= 3 && d <= 5;
  });
  if (springCands.length === 0) return null;
  const spring = rng.pick(springCands);
  tiles[spring.y]![spring.x] = makeTile('spring');

  const caveCands = rocks.filter((p) => fromStart[p.y]![p.x]! >= 4);
  const cave = rng.pick(caveCands.length ? caveCands : rocks);
  tiles[cave.y]![cave.x] = makeTile('cave');

  const palms = spreadPick(
    rng,
    beach.filter((p) => !same(p, start)),
    4,
    3,
  );
  if (palms.length < 3) return null;
  for (const p of palms) tiles[p.y]![p.x] = makeTile('palm');

  const grass = rest.filter((p) => tiles[p.y]![p.x]!.terrain === 'grass');
  const bushes = spreadPick(rng, grass, bushCount, 2);
  if (bushes.length < bushCount) return null;
  const colours = rng.shuffle(BERRY_COLOURS);
  const safeBerry = colours[0]!;
  const poisonBerry = colours[1]!;
  const fromSpring = pathDistances(tiles, spring);
  const nearSpring = bushes.slice().sort((a, b) => fromSpring[a.y]![a.x]! - fromSpring[b.y]![b.x]!);
  const safeFirst = rng.pick(nearSpring.slice(0, 2));
  const others = rng.shuffle(bushes.filter((p) => !same(p, safeFirst)));
  const safeSet = [safeFirst, ...others.slice(0, Math.max(0, bushCount - poisonCount - 1))];
  for (const p of bushes) {
    tiles[p.y]![p.x] = { ...makeTile('bush'), berry: safeSet.some((q) => same(q, p)) ? safeBerry : poisonBerry };
  }

  const bottleCands = beach.filter((p) => {
    const d = fromStart[p.y]![p.x]!;
    return tiles[p.y]![p.x]!.terrain === 'beach' && !same(p, start) && d >= 3 && d <= 9;
  });
  if (bottleCands.length === 0) return null;
  const bottle = rng.pick(bottleCands);

  return { tiles, start, summit, spring, cave, bottle, safeBerry, poisonBerry };
}

export function generateIsland(root: Rng, cfg: IslandConfig): IslandWorld {
  let layout: MapLayout | null = null;
  const bushes = Math.max(2, Math.min(6, cfg.berryBushes));
  const poison = Math.max(1, Math.min(bushes - 1, cfg.poisonBushes));
  for (let attempt = 0; attempt < 500 && !layout; attempt++) layout = tryLayout(root.fork(`island:map:${attempt}`), cfg.size, bushes, poison);
  if (!layout) throw new Error('Survival Island: could not generate an island for this seed');

  const wr = root.fork('island:weather');
  const weather: Weather[] = ['clear'];
  const nightCold: number[] = [0];
  for (let d = 1; d <= cfg.maxDays + 1; d++) {
    let w: Weather;
    if (d <= 2) w = wr.chance(0.6) ? 'clear' : 'cloudy';
    else {
      // Fixed draw order: the same seed gives the same weather under any config thresholds.
      const r = wr.next();
      const storm = cfg.stormChance;
      const rain = storm + cfg.rainChance;
      const fair = 1 - rain;
      w = r < storm ? 'storm' : r < rain ? 'rain' : r < rain + fair * 0.14 ? 'hot' : r < rain + fair * 0.55 ? 'cloudy' : 'clear';
      if (w === 'storm' && weather[d - 1] === 'storm') w = 'rain';
    }
    weather.push(w);
    nightCold.push(wr.int(cfg.nightCold[0], cfg.nightCold[1]));
  }
  const shipFirst = wr.int(cfg.shipFirstDay[0], cfg.shipFirstDay[1]);
  const shipPeriod = wr.int(cfg.shipEveryDays[0], cfg.shipEveryDays[1]);
  const candidates: number[] = [];
  for (let d = shipFirst; d <= cfg.maxDays; d += shipPeriod) candidates.push(d);
  // Enough passes must actually happen for a rescue to be possible: calm the
  // weather on the first `signalsNeeded` passing days; later ones skip storms.
  for (const d of candidates.slice(0, Math.max(1, cfg.signalsNeeded))) if (weather[d] === 'storm') weather[d] = 'cloudy';
  const shipDays = candidates.filter((d) => weather[d] !== 'storm');

  return {
    size: cfg.size,
    maxDays: cfg.maxDays,
    inventoryCap: cfg.inventoryCap,
    tiles: layout.tiles,
    start: layout.start,
    summit: layout.summit,
    spring: layout.spring,
    cave: layout.cave,
    bottle: layout.bottle,
    weather,
    nightCold,
    shipFirst,
    shipPeriod,
    shipDays,
    signalsNeeded: Math.max(1, cfg.signalsNeeded),
    requireNightFire: cfg.requireNightFire,
    drinkAmount: cfg.drinkAmount,
    poisonDamage: cfg.poisonDamage,
    safeBerry: layout.safeBerry,
    poisonBerry: layout.poisonBerry,
    rolls: root.fork('island:rolls'),
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// State helpers
// ─────────────────────────────────────────────────────────────────────────────

export function newIslandState(world: IslandWorld): IslandState {
  const n = world.size;
  const s: IslandState = {
    day: 1,
    phase: 0,
    pos: { ...world.start },
    health: 100,
    food: 60,
    water: 60,
    energy: 80,
    warmth: 70,
    inv: {},
    spear: false,
    fires: {},
    shelters: [],
    signal: 'none',
    signalLitAt: -10,
    tiles: world.tiles.map((row) => row.map((t) => ({ ...t }))),
    explored: Array.from({ length: n }, () => Array.from({ length: n }, () => false)),
    bottleFound: false,
    shipsSeen: [],
    sightings: [],
    fireNights: [],
    poisonEaten: 0,
    alive: true,
    rescued: false,
    rescueDay: null,
    over: false,
    cause: null,
    nightsSurvived: 0,
    turn: 0,
    invalid: 0,
    failed: 0,
    milestones: { spear: null, campfire: null, shelter: null, signalPile: null, signalLit: null },
    lastHurt: null,
  };
  reveal(world, s);
  return s;
}

function tileAt(s: IslandState, p: Pos): IslandTile {
  return s.tiles[p.y]![p.x]!;
}

function inBounds(world: IslandWorld, p: Pos): boolean {
  return p.x >= 0 && p.y >= 0 && p.x < world.size && p.y < world.size;
}

function reveal(world: IslandWorld, s: IslandState): void {
  const here = tileAt(s, s.pos).terrain;
  const r = here === 'summit' ? world.size : here === 'rocks' || here === 'cave' ? 3 : 2;
  for (let y = s.pos.y - r; y <= s.pos.y + r; y++)
    for (let x = s.pos.x - r; x <= s.pos.x + r; x++) if (inBounds(world, { x, y })) s.explored[y]![x] = true;
}

export function invCount(s: IslandState): number {
  return Object.values(s.inv).reduce((a, b) => a + b, 0);
}

function add(s: IslandState, item: string, n: number): void {
  s.inv[item] = (s.inv[item] ?? 0) + n;
}

function take(s: IslandState, item: string, n: number): void {
  const left = (s.inv[item] ?? 0) - n;
  if (left <= 0) delete s.inv[item];
  else s.inv[item] = left;
}

function hasAll(s: IslandState, recipe: Readonly<Record<string, number>>): boolean {
  return Object.entries(recipe).every(([k, v]) => (s.inv[k] ?? 0) >= v);
}

function isSheltered(s: IslandState): boolean {
  return tileAt(s, s.pos).terrain === 'cave' || s.shelters.includes(key(s.pos));
}

function fireHere(s: IslandState): boolean {
  return (s.fires[key(s.pos)] ?? 0) > 0;
}

function isCoast(world: IslandWorld, s: IslandState, p: Pos = s.pos): boolean {
  return Object.values(DIRS).some((d) => {
    const q = { x: p.x + d.x, y: p.y + d.y };
    return !inBounds(world, q) || tileAt(s, q).terrain === 'sea';
  });
}

function roll(world: IslandWorld, label: string): number {
  return world.rolls.fork(label).next();
}

export function itemLabel(item: string): string {
  if (item.endsWith('berry')) return `${item.slice(0, -5)} berries`;
  return { wood: 'wood', stone: 'stone', fibre: 'fibre', coconut: 'coconut', rawfish: 'raw fish', cookedfish: 'cooked fish' }[item] ?? item;
}

function itemCommand(item: string): string {
  return itemLabel(item).toUpperCase();
}

function isFood(item: string): boolean {
  return item === 'coconut' || item === 'rawfish' || item === 'cookedfish' || item.endsWith('berry');
}

function quarterIndex(day: number, quarter: number): number {
  return (day - 1) * 4 + quarter;
}

/** Apply damage; the blow that takes health from above 0 to 0 or below names the cause of death. */
function hurt(s: IslandState, amount: number, cause: string): void {
  if (amount <= 0) return;
  const wasAlive = s.health > 0;
  s.health -= amount;
  if (wasAlive && s.health <= 0) s.lastHurt = { cause, amount: Infinity };
  else if (!s.lastHurt || amount > s.lastHurt.amount) s.lastHurt = { cause, amount };
}

function clampStats(s: IslandState): void {
  s.health = clamp(Math.round(s.health), -100, 100);
  s.food = clamp(Math.round(s.food), 0, 100);
  s.water = clamp(Math.round(s.water), 0, 100);
  s.energy = clamp(Math.round(s.energy), 0, 100);
  s.warmth = clamp(Math.round(s.warmth), 0, 100);
}

// ─────────────────────────────────────────────────────────────────────────────
// Commands
// ─────────────────────────────────────────────────────────────────────────────

const DIR_ALIASES: Record<string, Dir> = { N: 'N', NORTH: 'N', UP: 'N', S: 'S', SOUTH: 'S', DOWN: 'S', E: 'E', EAST: 'E', RIGHT: 'E', W: 'W', WEST: 'W', LEFT: 'W' };

/** Parse a command string (already uppercased). Returns null when unrecognised. */
export function parseIslandCommand(raw: string): IslandCommand | null {
  const s = raw.toUpperCase().replace(/[^A-Z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim();
  const [verb = '', ...restWords] = s.split(' ');
  const rest = restWords.join(' ');
  switch (verb) {
    case 'MOVE':
    case 'GO':
    case 'WALK': {
      const m = rest.match(/^([A-Z]+)(?: ?([1-9]))?(?: (?:TILES?|STEPS?|SQUARES?))?$/);
      const dir = m ? DIR_ALIASES[m[1]!] : undefined;
      if (!m || !dir) return null;
      const steps = m[2] ? Number(m[2]) : 1;
      return steps >= 1 && steps <= MAX_STEPS ? { kind: 'move', dir, steps } : null;
    }
    case 'N':
    case 'S':
    case 'E':
    case 'W':
    case 'NORTH':
    case 'SOUTH':
    case 'EAST':
    case 'WEST':
      return rest === '' ? { kind: 'move', dir: DIR_ALIASES[verb]!, steps: 1 } : null;
    case 'GATHER':
    case 'COLLECT':
    case 'FORAGE':
      return { kind: 'gather' };
    case 'DRINK':
      return { kind: 'drink' };
    case 'EAT': {
      const item = normaliseItem(rest);
      return item ? { kind: 'eat', item } : null;
    }
    case 'CRAFT':
    case 'BUILD':
    case 'MAKE': {
      const what = rest.replace(/^A /, '');
      if (what === 'SPEAR') return { kind: 'craft' };
      if (what === 'FIRE' || what === 'CAMPFIRE' || what === 'CAMP FIRE') return { kind: 'build', what: 'fire' };
      if (what === 'SHELTER' || what === 'HUT') return { kind: 'build', what: 'shelter' };
      if (what === 'SIGNAL' || what === 'SIGNAL FIRE' || what === 'SIGNAL PILE') return { kind: 'build', what: 'signal' };
      return null;
    }
    case 'LIGHT':
    case 'IGNITE':
      return rest === '' || /^(THE )?SIGNAL( FIRE| PILE)?$/.test(rest) ? { kind: 'light' } : null;
    case 'FISH':
      return rest === '' ? { kind: 'fish' } : null;
    case 'COOK':
      return rest === '' || /^(THE )?(RAW )?FISH$/.test(rest) ? { kind: 'cook' } : null;
    case 'REST':
    case 'SLEEP':
      return rest === '' ? { kind: 'rest' } : null;
    default:
      return null;
  }
}

function normaliseItem(text: string): string | null {
  let t = text.toLowerCase().replace(/[^a-z]/g, '');
  if (!t) return null;
  t = t.replace(/berries$/, 'berry').replace(/coconuts$/, 'coconut').replace(/fishes$/, 'fish');
  if (t === 'fish' || t === 'rawfish') return 'rawfish';
  if (t === 'cookedfish' || t === 'grilledfish' || t === 'roastfish') return 'cookedfish';
  if (t === 'coconut') return 'coconut';
  if (t === 'berry') return 'berry'; // resolved against the inventory later
  if ((BERRY_COLOURS as readonly string[]).some((c) => t === `${c}berry`)) return t;
  return null;
}

/** Every command that would currently do something, as exact command strings. */
export function availableActions(world: IslandWorld, s: IslandState): string[] {
  const out: string[] = [];
  const here = tileAt(s, s.pos);
  const weather = world.weather[s.day]!;
  const moveCost = weather === 'storm' ? COST.stormMove : COST.move;
  for (const d of ['N', 'S', 'E', 'W'] as Dir[]) {
    for (let k = 1; k <= MAX_STEPS; k++) {
      const q = { x: s.pos.x + DIRS[d].x * k, y: s.pos.y + DIRS[d].y * k };
      if (!inBounds(world, q) || tileAt(s, q).terrain === 'sea' || s.energy < moveCost * k) break;
      out.push(k === 1 ? `MOVE ${d}` : `MOVE ${d} ${k}`);
    }
  }
  if (here.stock > 0 && invCount(s) < world.inventoryCap && s.energy >= COST.gather) out.push('GATHER');
  if (here.terrain === 'spring' || weather === 'rain' || weather === 'storm') out.push('DRINK');
  for (const item of Object.keys(s.inv).sort()) if (isFood(item)) out.push(`EAT ${itemCommand(item)}`);
  if (!s.spear && hasAll(s, RECIPES.spear) && s.energy >= COST.craft) out.push('CRAFT SPEAR');
  if (s.energy >= COST.build) {
    if (hasAll(s, RECIPES.fire) && here.terrain !== 'spring') out.push('BUILD FIRE');
    if (hasAll(s, RECIPES.shelter) && canShelterHere(s)) out.push('BUILD SHELTER');
    if (here.terrain === 'summit' && s.signal === 'none' && hasAll(s, RECIPES.signal)) out.push('BUILD SIGNAL');
  }
  if (here.terrain === 'summit' && s.signal === 'built' && (s.inv.fibre ?? 0) >= 1 && weather !== 'storm' && s.energy >= COST.light)
    out.push('LIGHT SIGNAL');
  if (s.spear && isCoast(world, s) && weather !== 'storm' && invCount(s) < world.inventoryCap && s.energy >= COST.fish) out.push('FISH');
  if (fireHere(s) && (s.inv.rawfish ?? 0) > 0) out.push('COOK FISH');
  out.push('REST');
  return out;
}

function canShelterHere(s: IslandState): boolean {
  const t = tileAt(s, s.pos).terrain;
  return t !== 'spring' && t !== 'cave' && t !== 'summit' && !s.shelters.includes(key(s.pos));
}

interface ActionResult {
  ok: boolean;
  text: string;
  tone?: 'good' | 'bad';
}

function applyAction(world: IslandWorld, s: IslandState, cmd: IslandCommand): ActionResult {
  const here = tileAt(s, s.pos);
  const weather = world.weather[s.day]!;
  const tired = (cost: number): ActionResult | null =>
    s.energy < cost ? { ok: false, text: `You are too exhausted to do that (needs ${cost} energy) — REST first.` } : null;
  const full = (): boolean => invCount(s) >= world.inventoryCap;

  switch (cmd.kind) {
    case 'move': {
      const cost = weather === 'storm' ? COST.stormMove : COST.move;
      let walked = 0;
      let stop = '';
      let found = false;
      for (let i = 0; i < cmd.steps; i++) {
        const q = { x: s.pos.x + DIRS[cmd.dir].x, y: s.pos.y + DIRS[cmd.dir].y };
        if (!inBounds(world, q) || tileAt(s, q).terrain === 'sea') {
          stop = ` The sea blocks the way further ${DIR_WORD[cmd.dir]}.`;
          break;
        }
        if (s.energy < cost) {
          stop = ' You are too exhausted to walk further.';
          break;
        }
        s.energy -= cost;
        s.pos = q;
        walked++;
        reveal(world, s);
        if (!s.bottleFound && same(q, world.bottle)) {
          s.bottleFound = true;
          found = true;
          if (i < cmd.steps - 1) stop = ' You stop to read it.';
          break;
        }
      }
      if (walked === 0) return { ok: false, text: stop.trim() || `You cannot walk ${DIR_WORD[cmd.dir]}.` };
      const dest = tileAt(s, s.pos);
      let text = `You walk ${walked} tile${walked === 1 ? '' : 's'} ${DIR_WORD[cmd.dir]} to ${coordName(s.pos)} (${TERRAIN_NAME[dest.terrain]}).${stop}`;
      if (dest.terrain === 'summit') text += ' From the summit you can see the whole island.';
      if (found) {
        text += ` You find a message in a bottle! ${bottleMessage(world)}`;
        return { ok: true, text, tone: 'good' };
      }
      return { ok: true, text };
    }
    case 'gather': {
      if (here.stock <= 0) {
        const what =
          here.terrain === 'spring' || here.terrain === 'cave' ? 'Nothing here can be gathered.' : 'This spot is picked clean for now.';
        return { ok: false, text: what };
      }
      if (full()) return { ok: false, text: `Your inventory is full (${world.inventoryCap} items).` };
      const t = tired(COST.gather);
      if (t) return t;
      s.energy -= COST.gather;
      const room = world.inventoryCap - invCount(s);
      const yieldBy: Partial<Record<Terrain, [string, number]>> = {
        beach: ['wood', 1],
        palm: ['coconut', 2],
        grass: ['fibre', 2],
        bush: [`${here.berry}berry`, 3],
        forest: ['wood', 2],
        rocks: ['stone', 2],
        summit: ['stone', 2],
      };
      const [item, amount] = yieldBy[here.terrain]!;
      const got = Math.min(amount, here.stock, room);
      here.stock -= got;
      add(s, item, got);
      let text = `You gather ${got} ${itemLabel(item)}${here.terrain === 'beach' ? ' (driftwood)' : ''}.`;
      if (here.terrain === 'bush') {
        text +=
          here.berry === world.poisonBerry
            ? ` The ${here.berry} berries give off a sharp, bitter smell.`
            : ` The ${here.berry} berries smell sweet.`;
      }
      if ((here.terrain === 'rocks' || here.terrain === 'summit') && roll(world, `slip:${s.day}:${s.phase}`) < 0.08) {
        hurt(s, 10, 'a fall on the rocks');
        text += ' You slip on the loose rocks and gash your leg (−10 health).';
        return { ok: true, text, tone: 'bad' };
      }
      if (here.stock === 0) text += ' Nothing is left here for now.';
      return { ok: true, text };
    }
    case 'drink': {
      if (here.terrain === 'spring') {
        s.energy -= Math.min(s.energy, COST.drink);
        const before = s.water;
        s.water = Math.min(100, s.water + world.drinkAmount);
        const how = world.drinkAmount >= 100 ? 'your fill from the cold spring' : 'from the spring — it only trickles';
        return { ok: true, text: `You drink ${how} (water ${before} → ${s.water}).`, tone: 'good' };
      }
      if (weather === 'rain' || weather === 'storm') {
        s.energy -= Math.min(s.energy, COST.drink);
        const before = s.water;
        s.water = Math.min(100, s.water + 20);
        return { ok: true, text: `You catch rainwater in your hands (water ${before} → ${s.water}).` };
      }
      return { ok: false, text: 'There is no fresh water here — seawater would only make you sicker. Find the spring.' };
    }
    case 'eat': {
      let item = cmd.item;
      if (item === 'berry') {
        const held = Object.keys(s.inv).filter((i) => i.endsWith('berry'));
        if (held.length !== 1) return { ok: false, text: held.length ? 'Which berries? Name the colour (e.g. EAT RED BERRIES).' : 'You have no berries.' };
        item = held[0]!;
      }
      if (!(s.inv[item] ?? 0)) return { ok: false, text: `You have no ${itemLabel(item)}.` };
      s.energy -= Math.min(s.energy, COST.eat);
      if (item.endsWith('berry') && item === `${world.poisonBerry}berry`) {
        take(s, item, 1);
        s.poisonEaten++;
        s.water = Math.max(0, s.water - 15);
        s.energy = Math.max(0, s.energy - 10);
        hurt(s, world.poisonDamage, `poisonous ${world.poisonBerry} berries`);
        return {
          ok: true,
          text: `Bitter! Minutes later you are violently sick — the ${world.poisonBerry} berries are poisonous (−${world.poisonDamage} health, −15 water, −10 energy).`,
          tone: 'bad',
        };
      }
      const gain: Record<string, { food: number; water?: number; health?: number }> = {
        coconut: { food: 10, water: 12 },
        rawfish: { food: 20 },
        cookedfish: { food: 35, health: 3 },
      };
      const g = gain[item] ?? { food: 12 };
      let eaten = 0;
      let sick = false;
      while ((s.inv[item] ?? 0) > 0) {
        const wantsFood = s.food + g.food / 2 <= 100;
        const wantsWater = g.water !== undefined && s.water + g.water / 2 <= 100;
        if (eaten > 0 && !wantsFood && !wantsWater) break;
        take(s, item, 1);
        eaten++;
        s.food = Math.min(100, s.food + g.food);
        if (g.water) s.water = Math.min(100, s.water + g.water);
        if (g.health) s.health = Math.min(100, s.health + g.health);
        if (item === 'rawfish' && !sick && roll(world, `rawfish:${s.day}:${s.phase}:${eaten}`) < 0.3) sick = true;
      }
      let text = `You eat ${eaten} ${itemLabel(item)} (food ${s.food}${g.water ? `, water ${s.water}` : ''}).`;
      if (sick) {
        hurt(s, 8, 'food poisoning from raw fish');
        text += ' The raw fish upsets your stomach (−8 health).';
        return { ok: true, text, tone: 'bad' };
      }
      return { ok: true, text, tone: 'good' };
    }
    case 'craft': {
      if (s.spear) return { ok: false, text: 'You already have a spear.' };
      if (!hasAll(s, RECIPES.spear)) return { ok: false, text: 'A spear needs 1 wood, 1 stone and 1 fibre.' };
      const t = tired(COST.craft);
      if (t) return t;
      s.energy -= COST.craft;
      for (const [k, v] of Object.entries(RECIPES.spear)) take(s, k, v);
      s.spear = true;
      s.milestones.spear ??= s.turn;
      return { ok: true, text: 'You lash a sharp stone to a straight stick: you now have a fishing spear.', tone: 'good' };
    }
    case 'build': {
      const recipe = RECIPES[cmd.what];
      const need = Object.entries(recipe)
        .map(([k, v]) => `${v} ${k}`)
        .join(' + ');
      if (cmd.what === 'fire' && here.terrain === 'spring') return { ok: false, text: 'You cannot build a fire in the spring.' };
      if (cmd.what === 'shelter' && !canShelterHere(s))
        return { ok: false, text: here.terrain === 'cave' ? 'The cave is already a shelter.' : 'You cannot build a shelter on this tile.' };
      if (cmd.what === 'signal') {
        if (here.terrain !== 'summit') return { ok: false, text: 'A signal pile only makes sense on the summit, the highest point of the island.' };
        if (s.signal !== 'none') return { ok: false, text: 'The signal pile is already built.' };
      }
      if (!hasAll(s, recipe)) return { ok: false, text: `Not enough materials: that needs ${need}.` };
      const t = tired(COST.build);
      if (t) return t;
      s.energy -= COST.build;
      for (const [k, v] of Object.entries(recipe)) take(s, k, v);
      if (cmd.what === 'fire') {
        const refuel = key(s.pos) in s.fires;
        s.fires[key(s.pos)] = 3;
        s.milestones.campfire ??= s.turn;
        return { ok: true, text: refuel ? 'You rebuild the campfire: fuel for 3 nights.' : 'You build a campfire here: it will burn for 3 nights.', tone: 'good' };
      }
      if (cmd.what === 'shelter') {
        s.shelters.push(key(s.pos));
        s.milestones.shelter ??= s.turn;
        return { ok: true, text: `You build a sturdy lean-to shelter at ${coordName(s.pos)}.`, tone: 'good' };
      }
      s.signal = 'built';
      s.milestones.signalPile ??= s.turn;
      return { ok: true, text: 'You stack a huge signal pile on the summit. It needs to be lit (LIGHT SIGNAL) to be seen.', tone: 'good' };
    }
    case 'light': {
      if (here.terrain !== 'summit') return { ok: false, text: 'The signal pile is on the summit — you must be there to light it.' };
      if (s.signal === 'none') return { ok: false, text: 'There is no signal pile to light. BUILD SIGNAL first.' };
      if (s.signal === 'lit') return { ok: false, text: 'The signal fire is already blazing.' };
      if (weather === 'storm') return { ok: false, text: 'The storm makes it impossible to light anything.' };
      if ((s.inv.fibre ?? 0) < 1) return { ok: false, text: 'You need 1 fibre as tinder to light the signal.' };
      const t = tired(COST.light);
      if (t) return t;
      s.energy -= COST.light;
      take(s, 'fibre', 1);
      s.signal = 'lit';
      s.signalLitAt = quarterIndex(s.day, s.phase);
      s.milestones.signalLit ??= s.turn;
      return { ok: true, text: 'The signal fire roars to life — a column of smoke rises high above the island. It will burn this turn and the next.', tone: 'good' };
    }
    case 'fish': {
      if (!s.spear) return { ok: false, text: 'You need a spear to fish (CRAFT SPEAR).' };
      if (!isCoast(world, s)) return { ok: false, text: 'You must stand at the water’s edge (a beach or palm tile) to fish.' };
      if (weather === 'storm') return { ok: false, text: 'The waves are far too violent to fish.' };
      if (full()) return { ok: false, text: `Your inventory is full (${world.inventoryCap} items).` };
      const t = tired(COST.fish);
      if (t) return t;
      s.energy -= COST.fish;
      const r = roll(world, `fish:${s.day}:${s.phase}`);
      const caught = Math.min(r < 0.25 ? 0 : r < 0.7 ? 1 : 2, world.inventoryCap - invCount(s));
      if (caught === 0) return { ok: true, text: 'You wade in with your spear but the fish are too quick. Nothing caught.' };
      add(s, 'rawfish', caught);
      return { ok: true, text: `You spear ${caught} fish!`, tone: 'good' };
    }
    case 'cook': {
      if (!fireHere(s)) return { ok: false, text: 'You need a burning campfire on this tile to cook.' };
      const raw = s.inv.rawfish ?? 0;
      if (!raw) return { ok: false, text: 'You have no raw fish to cook.' };
      s.energy -= Math.min(s.energy, COST.cook);
      take(s, 'rawfish', raw);
      add(s, 'cookedfish', raw);
      return { ok: true, text: `You grill ${raw} fish over the campfire.`, tone: 'good' };
    }
    case 'rest': {
      const gain = isSheltered(s) ? 40 : 30;
      s.energy = Math.min(100, s.energy + gain);
      let text = `You rest${isSheltered(s) ? ' in the shade of your shelter' : ''} (energy ${s.energy}).`;
      if (s.food >= 30 && s.water >= 30) {
        s.health = Math.min(100, s.health + 4);
        text = text.replace(').', `, health ${s.health}).`);
      }
      return { ok: true, text };
    }
  }
}

/** The logbook page in the bottle: the ship's full schedule and what the crew looks for. */
export function bottleMessage(world: IslandWorld): string {
  const proof = world.requireNightFire ? ' and a campfire burning somewhere on the island the night before' : '';
  const times = world.signalsNeeded === 1 ? 'we will send a boat' : `once we have seen it on ${world.signalsNeeded} passes, we will send a boat`;
  return (
    `It is a page torn from the logbook of the supply ship Albatross: "Kestrel Isle. We pass on day ${world.shipFirst} of the month and then every ${world.shipPeriod} days, around midday — ` +
    `except in a storm, when we stay in port. Should anyone ever be stranded there: if we see a signal fire blazing on the peak${proof}, ${times}."`
  );
}

function describeCommand(cmd: IslandCommand): string {
  switch (cmd.kind) {
    case 'move':
      return cmd.steps > 1 ? `MOVE ${cmd.dir} ${cmd.steps}` : `MOVE ${cmd.dir}`;
    case 'eat':
      return `EAT ${itemCommand(cmd.item)}`;
    case 'craft':
      return 'CRAFT SPEAR';
    case 'build':
      return `BUILD ${cmd.what.toUpperCase()}`;
    case 'light':
      return 'LIGHT SIGNAL';
    case 'cook':
      return 'COOK FISH';
    default:
      return cmd.kind.toUpperCase();
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// Time
// ─────────────────────────────────────────────────────────────────────────────

function checkDeath(s: IslandState): boolean {
  clampStats(s);
  if (s.health <= 0 && s.alive) {
    s.alive = false;
    s.over = true;
    s.health = 0;
    s.cause = s.lastHurt?.cause ?? 'exhaustion';
    return true;
  }
  return false;
}

function daytimeTick(world: IslandWorld, s: IslandState): void {
  const w = world.weather[s.day]!;
  s.food -= 2;
  s.water -= w === 'hot' ? 7 : 4;
  const sheltered = isSheltered(s);
  const table: Record<Weather, number> = sheltered
    ? { clear: 6, hot: 8, cloudy: 2, rain: 0, storm: -2 }
    : { clear: 8, hot: 10, cloudy: 3, rain: -4, storm: -8 };
  s.warmth += table[w] + (fireHere(s) ? 8 : 0);
  clampStats(s);
  if (s.water === 0) hurt(s, 10, 'dehydration');
  if (s.food === 0) hurt(s, 5, 'starvation');
  if (s.warmth === 0) hurt(s, 5, 'hypothermia');
  if (s.energy === 0) hurt(s, 4, 'exhaustion');
}

function nightTick(world: IslandWorld, s: IslandState, events: string[]): string {
  const w = world.weather[s.day]!;
  s.lastHurt = null;
  const sheltered = isSheltered(s);
  const here = key(s.pos);
  s.food -= 5;
  s.water -= 5;
  const exposure = world.nightCold[s.day]! + (w === 'rain' ? 5 : w === 'storm' ? 12 : 0);
  let dWarm = -exposure;
  if (sheltered) dWarm += 12 + (w === 'storm' ? 12 : w === 'rain' ? 5 : 0);
  let fireNote = '';
  if ((s.fires[here] ?? 0) > 0) {
    if (w === 'storm' && !sheltered) {
      s.fires[here] = 0;
      fireNote = ' The storm drowns your campfire.';
    } else {
      dWarm += w === 'rain' && !sheltered ? 10 : 20;
      fireNote = ' Your campfire keeps you warm.';
    }
  }
  // Other campfires: a storm drowns any fire without a shelter over it.
  for (const k of Object.keys(s.fires)) {
    const [fx, fy] = k.split(',').map(Number);
    const covered = s.shelters.includes(k) || s.tiles[fy!]![fx!]!.terrain === 'cave';
    if (k !== here && s.fires[k]! > 0 && w === 'storm' && !covered) s.fires[k] = 0;
  }
  if (Object.values(s.fires).some((f) => f > 0)) s.fireNights.push(s.day);
  s.warmth += dWarm;
  s.energy += (sheltered ? 35 : w === 'storm' ? 10 : 22) + (fireNote.includes('warm') ? 5 : 0);
  clampStats(s);
  const hurts: string[] = [];
  const before = s.health;
  if (s.water === 0) {
    hurt(s, 15, 'dehydration');
    hurts.push('parched');
  }
  if (s.food === 0) {
    hurt(s, 8, 'starvation');
    hurts.push('starving');
  }
  if (s.warmth === 0) {
    hurt(s, 12, 'hypothermia');
    hurts.push('freezing');
  }
  if (w === 'storm' && !sheltered) {
    hurt(s, 10, 'exposure to the storm');
    hurts.push('battered by the storm in the open');
  }
  if (s.health > 0 && s.food >= 35 && s.water >= 35 && s.warmth >= 35) s.health = Math.min(100, s.health + 8 + (sheltered ? 4 : 0));
  // Fuel burns, plants regrow.
  for (const k of Object.keys(s.fires)) if (s.fires[k]! > 0) s.fires[k] = s.fires[k]! - 1;
  for (const row of s.tiles)
    for (const t of row) if (t.regrowDays > 0 && t.stock < t.max && s.day % t.regrowDays === 0) t.stock++;
  clampStats(s);
  const where = sheltered ? (tileAt(s, s.pos).terrain === 'cave' ? 'in the cave' : 'in your shelter') : 'in the open';
  const weatherNight = w === 'storm' ? 'A storm rages all night.' : w === 'rain' ? 'Cold rain falls all night.' : exposure >= 15 ? 'A bitterly cold night.' : 'A cool night.';
  const delta = s.health - before;
  const report = `Night ${s.day}: ${weatherNight} You sleep ${where}.${fireNote}${hurts.length ? ` You are ${hurts.join(', ')}.` : ''} Health ${delta >= 0 ? '+' : ''}${delta} → ${s.health}, warmth ${s.warmth}, energy ${s.energy}.`;
  events.push(report);
  return report;
}

/**
 * Apply one turn. `command` is the model's raw command (null when missing);
 * `failure` marks a void turn (refusal / empty reply).
 */
export function stepIsland(world: IslandWorld, s: IslandState, command: string | null, failure: string | null = null): IslandStep {
  const label = `Day ${s.day} · ${PHASES[s.phase]}`;
  const events: string[] = [];
  s.lastHurt = null;
  let outcome: string;
  let action = command ?? '(none)';
  let valid = true;
  let tone: 'good' | 'bad' | 'neutral' = 'neutral';

  if (failure) {
    s.invalid++;
    valid = false;
    action = '(no action)';
    outcome = `${failure} The turn is lost.`;
    tone = 'bad';
  } else if (!command) {
    s.invalid++;
    valid = false;
    outcome = 'No ACTION line found in your reply — the turn is lost.';
    tone = 'bad';
  } else {
    const cmd = parseIslandCommand(command);
    if (!cmd) {
      s.invalid++;
      valid = false;
      outcome = `Unrecognised action "${truncate(command, 60)}" — the turn is lost. Use one of the listed commands.`;
      tone = 'bad';
    } else {
      action = describeCommand(cmd);
      const r = applyAction(world, s, cmd);
      outcome = r.text;
      if (!r.ok) {
        s.failed++;
        tone = 'bad';
      } else tone = r.tone ?? 'neutral';
    }
  }
  s.turn++;

  // Harm from the action itself (poison, a fall) can kill before time passes.
  let night: string | null = null;
  let preNight: IslandStep['preNight'] = null;
  const day = s.day;
  const phase = s.phase;
  if (!checkDeath(s)) {
    daytimeTick(world, s);
    const q = quarterIndex(s.day, s.phase);
    if (s.phase === 1 && world.shipDays.includes(s.day)) {
      s.shipsSeen.push(s.day);
      const t = tileAt(s, s.pos).terrain;
      const see = t === 'beach' || t === 'palm' || t === 'rocks' || t === 'summit' || t === 'cave';
      const pass = `At midday the supply ship Albatross sails past the island${see ? ' — you can see her out at sea' : ' — you hear her horn from the sea'}.`;
      const blazing = s.signal === 'lit' && q <= s.signalLitAt + 1;
      if (!blazing) {
        events.push(`${pass} No signal fire is burning on the summit, so she sails on.`);
      } else if (world.requireNightFire && !s.fireNights.includes(s.day - 1)) {
        events.push(`${pass} The crew sees your signal, but no campfire burned on the island last night — the captain is not convinced anyone lives here, and she sails on.`);
      } else {
        s.sightings.push(s.day);
        if (s.sightings.length >= world.signalsNeeded) {
          s.rescued = true;
          s.rescueDay = s.day;
          s.over = true;
          events.push(`${pass} The crew spots your signal fire again and lowers a boat — you are RESCUED!`);
          tone = 'good';
        } else {
          const left = world.signalsNeeded - s.sightings.length;
          events.push(
            `${pass} Her horn sounds three times: the crew has seen your signal! She cannot stop today; they will send a boat once they see your signal ${left === 1 ? 'again on a later pass' : `on ${left} more passes`}.`,
          );
          tone = 'good';
        }
      }
    }
    if (!s.over && !checkDeath(s) && s.phase === 2) {
      preNight = { health: s.health, food: s.food, water: s.water, energy: s.energy, warmth: s.warmth };
      night = nightTick(world, s, events);
      if (!checkDeath(s)) {
        s.nightsSurvived = s.day;
        if (s.day >= world.maxDays) {
          s.over = true;
          events.push(`You have survived all ${world.maxDays} days!`);
        }
      }
    }
    const endQ = s.phase === 2 ? quarterIndex(s.day, 3) : q;
    if (s.signal === 'lit' && !s.rescued && endQ >= s.signalLitAt + 1) {
      s.signal = 'none';
      events.push('The signal fire has burned down to ashes.');
    }
  }
  if (!s.alive) {
    events.push(`You died of ${s.cause}.`);
    tone = 'bad';
  }
  if (!s.over) {
    if (s.phase === 2) {
      s.phase = 0;
      s.day++;
    } else s.phase++;
  }
  return { day, phase, label, action, outcome, events, valid, tone, night, preNight };
}

/** Nights an idle castaway (who never acts) survives on this island. */
export function idleNights(world: IslandWorld): number {
  const s = newIslandState(world);
  while (!s.over) stepIsland(world, s, null, 'idle');
  return s.nightsSurvived;
}

// ─────────────────────────────────────────────────────────────────────────────
// Rendering
// ─────────────────────────────────────────────────────────────────────────────

function offset(from: Pos, to: Pos): string {
  const dx = to.x - from.x;
  const dy = to.y - from.y;
  if (dx === 0 && dy === 0) return 'here';
  const parts: string[] = [];
  if (dx) parts.push(`${Math.abs(dx)}${dx > 0 ? 'E' : 'W'}`);
  if (dy) parts.push(`${Math.abs(dy)}${dy > 0 ? 'S' : 'N'}`);
  return parts.join(' ');
}

function overlayChar(world: IslandWorld, s: IslandState, p: Pos, truth: boolean): string {
  const k = key(p);
  if (same(p, s.pos)) return '@';
  if (same(p, world.summit) && s.signal !== 'none') return s.signal === 'lit' ? '!' : '#';
  if (s.shelters.includes(k)) return 'H';
  if ((s.fires[k] ?? 0) > 0) return 'F';
  if (truth && !s.bottleFound && same(p, world.bottle)) return 'm';
  return TERRAIN_CHAR[s.tiles[p.y]![p.x]!.terrain];
}

/** Full-truth map for replays. */
export function islandGridRows(world: IslandWorld, s: IslandState): string[] {
  const rows: string[] = [];
  for (let y = 0; y < world.size; y++) {
    let r = '';
    for (let x = 0; x < world.size; x++) r += overlayChar(world, s, { x, y }, true);
    rows.push(r);
  }
  return rows;
}

export function islandLegend(rows: string[]): Record<string, { label: string; color?: string; emoji?: string }> {
  const used = new Set(rows.join(''));
  return Object.fromEntries(Object.entries(ISLAND_LEGEND).filter(([ch]) => used.has(ch)));
}

function knownPlaces(world: IslandWorld, s: IslandState): string {
  const parts: string[] = [];
  const known = (p: Pos): boolean => s.explored[p.y]![p.x]!;
  const at = (label: string, p: Pos): string => `${label} ${coordName(p)} (${offset(s.pos, p)})`;
  parts.push(known(world.spring) ? at('spring', world.spring) : 'spring: not found yet');
  parts.push(known(world.summit) ? at('summit', world.summit) : 'summit: not found yet');
  parts.push(known(world.cave) ? at('cave', world.cave) : 'cave: not found yet');
  const nearest = (pred: (t: IslandTile) => boolean): Pos | null => {
    let best: Pos | null = null;
    let bestD = Infinity;
    for (let y = 0; y < world.size; y++)
      for (let x = 0; x < world.size; x++) {
        if (!s.explored[y]![x]! || !pred(s.tiles[y]![x]!)) continue;
        const d = Math.abs(x - s.pos.x) + Math.abs(y - s.pos.y);
        if (d < bestD) {
          bestD = d;
          best = { x, y };
        }
      }
    return best;
  };
  const near: Array<[string, (t: IslandTile) => boolean]> = [
    ['forest', (t) => t.terrain === 'forest'],
    ['rocks', (t) => t.terrain === 'rocks'],
    ['grass', (t) => t.terrain === 'grass'],
    ['palm', (t) => t.terrain === 'palm'],
  ];
  for (const colour of BERRY_COLOURS) near.push([`${colour}-berry bush`, (t) => t.terrain === 'bush' && t.berry === colour]);
  for (const [label, pred] of near) {
    const p = nearest(pred);
    if (p) parts.push(`nearest ${at(label, p)}`);
  }
  if (!s.bottleFound && known(world.bottle)) parts.push(at('something glinting on the sand at', world.bottle));
  return parts.join(' · ');
}

function inventoryText(world: IslandWorld, s: IslandState): string {
  const items = Object.entries(s.inv)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([k, v]) => `${v} ${itemLabel(k)}`);
  return `Inventory ${invCount(s)}/${world.inventoryCap}: ${items.length ? items.join(', ') : 'empty'} · Tools: ${s.spear ? 'spear' : 'none'}`;
}

function campText(world: IslandWorld, s: IslandState): string {
  const fires = Object.entries(s.fires)
    .filter(([, f]) => f > 0)
    .map(([k, f]) => {
      const [x, y] = k.split(',').map(Number);
      return `campfire at ${coordName({ x: x!, y: y! })} (${f} night${f === 1 ? '' : 's'} of fuel)`;
    });
  const shelters = s.shelters.map((k) => {
    const [x, y] = k.split(',').map(Number);
    return `shelter at ${coordName({ x: x!, y: y! })}`;
  });
  const signal = s.signal === 'none' ? 'signal pile: none' : s.signal === 'built' ? 'signal pile: built on the summit (unlit)' : 'signal fire: BLAZING on the summit';
  const seen = `signal sightings by the ship: ${s.sightings.length} of ${world.signalsNeeded} needed`;
  const parts = [...fires, ...shelters, signal, seen];
  if (world.requireNightFire && s.day > 1) parts.push(`last night ${s.fireNights.includes(s.day - 1) ? 'a campfire burned on the island' : 'no campfire burned on the island'}`);
  return parts.join(' · ');
}

function tileStatus(s: IslandState): string {
  const t = tileAt(s, s.pos);
  const res: Partial<Record<Terrain, string>> = {
    beach: `${t.stock} driftwood left`,
    palm: `${t.stock} coconuts left`,
    grass: `${t.stock} fibre left`,
    bush: `${t.stock} ${t.berry} berries left`,
    forest: `${t.stock} wood left`,
    rocks: `${t.stock} stone left`,
    summit: `${t.stock} stone left`,
    spring: 'fresh water',
    cave: 'natural shelter',
  };
  return `${TERRAIN_NAME[t.terrain]} (${res[t.terrain] ?? ''})`;
}

function surroundings(world: IslandWorld, s: IslandState): string {
  return (['N', 'E', 'S', 'W'] as Dir[])
    .map((d) => {
      const q = { x: s.pos.x + DIRS[d].x, y: s.pos.y + DIRS[d].y };
      return `${d} ${inBounds(world, q) ? TERRAIN_NAME[tileAt(s, q).terrain] : 'sea'}`;
    })
    .join(', ');
}

/** The per-turn observation (identical bytes for identical seed + history). */
export function islandObservation(world: IslandWorld, s: IslandState, noteLine: string, recent: readonly string[]): string {
  const weather = world.weather[s.day]!;
  const tomorrow = s.day < world.maxDays ? world.weather[s.day + 1]! : null;
  const lines: string[] = [];
  lines.push(
    `SURVIVAL ISLAND — Day ${s.day} of ${world.maxDays} · ${PHASES[s.phase]} (turn ${s.phase + 1} of 3 today${s.phase === 2 ? '; night falls after this turn' : ''})`,
  );
  lines.push(`Weather today: ${WEATHER_TEXT[weather]}.${tomorrow ? ` Sky signs for tomorrow: ${tomorrow}.` : ''}`);
  lines.push(`You: health ${s.health} · food ${s.food} · water ${s.water} · energy ${s.energy} · warmth ${s.warmth}`);
  lines.push(`You are at ${coordName(s.pos)} on ${tileStatus(s)}${isSheltered(s) ? ', sheltered' : ''}. Around you: ${surroundings(world, s)}.`);
  lines.push(inventoryText(world, s));
  lines.push(`Camp: ${campText(world, s)}`);
  lines.push(`Known places: ${knownPlaces(world, s)}`);
  lines.push(`Map (rows 1-${world.size} north→south, columns A-${COLS[world.size - 1]} west→east; @ = you, ? = unexplored):`);
  lines.push(`    ${COLS.slice(0, world.size)}`);
  for (let y = 0; y < world.size; y++) {
    let r = '';
    for (let x = 0; x < world.size; x++) r += s.explored[y]![x]! ? overlayChar(world, s, { x, y }, false) : '?';
    lines.push(`${String(y + 1).padStart(3)} ${r}`);
  }
  lines.push('Recent events:');
  if (recent.length === 0) lines.push('- You wash up on the beach, soaked, with nothing but the clothes on your back.');
  for (const e of recent) lines.push(`- ${e}`);
  lines.push(noteLine);
  lines.push(`Available actions: ${availableActions(world, s).map((c) => `\`${c}\``).join(', ')}`);
  lines.push('Reply with brief reasoning if you like, then end with one line: ACTION: <command> (optionally a NOTE: <memo> line just before it).');
  return lines.join('\n');
}

export function islandFrame(
  world: IslandWorld,
  s: IslandState,
  step: number,
  frame: Omit<ReplayFrame, 'step' | 'stats' | 'grid'>,
  statsOverride: IslandStep['preNight'] = null,
): ReplayFrame {
  const rows = islandGridRows(world, s);
  const inv = Object.entries(s.inv)
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([k, v]) => `${v} ${itemLabel(k)}`)
    .join(', ');
  const v = statsOverride ?? s;
  return {
    step,
    ...frame,
    stats: {
      health: Math.max(0, v.health),
      food: v.food,
      water: v.water,
      energy: v.energy,
      warmth: v.warmth,
      day: s.day,
      inventory: inv || 'empty',
    },
    grid: { rows, legend: islandLegend(rows) },
  };
}

// ─────────────────────────────────────────────────────────────────────────────
// Visual replay data (ReplayData.sim / ReplayFrame.sim) — never shown to the model
// ─────────────────────────────────────────────────────────────────────────────

const xy = (p: Pos): XY => [p.x, p.y];

/** The static island for the illustrated replay. */
export function islandSimWorld(world: IslandWorld): IslandSimWorld {
  const bushes: IslandSimWorld['bushes'] = [];
  for (let y = 0; y < world.size; y++)
    for (let x = 0; x < world.size; x++) {
      const t = world.tiles[y]![x]!;
      if (t.terrain === 'bush' && t.berry) bushes.push({ at: [x, y], colour: t.berry, poison: t.berry === world.poisonBerry });
    }
  return {
    kind: 'island',
    size: world.size,
    maxDays: world.maxDays,
    terrain: world.tiles.map((row) => row.map((t) => TERRAIN_CHAR[t.terrain]).join('')),
    start: xy(world.start),
    summit: xy(world.summit),
    spring: xy(world.spring),
    cave: xy(world.cave),
    bottle: xy(world.bottle),
    weather: world.weather.slice(0, world.maxDays + 1),
    bushes,
    shipDays: world.shipDays.slice(),
    signalsNeeded: world.signalsNeeded,
    requireNightFire: world.requireNightFire,
    inventoryCap: world.inventoryCap,
  };
}

/** What the replay compares before and after a turn to tag its events. */
export interface IslandSnap {
  springKnown: boolean;
  poison: number;
  milestones: IslandState['milestones'];
  ships: number;
  sightings: number;
  bottle: boolean;
  alive: boolean;
  health: number;
  fish: number;
  signal: IslandState['signal'];
}

export function islandSnap(world: IslandWorld, s: IslandState): IslandSnap {
  return {
    springKnown: s.explored[world.spring.y]![world.spring.x]!,
    poison: s.poisonEaten,
    milestones: { ...s.milestones },
    ships: s.shipsSeen.length,
    sightings: s.sightings.length,
    bottle: s.bottleFound,
    alive: s.alive,
    health: s.health,
    fish: s.inv.rawfish ?? 0,
    signal: s.signal,
  };
}

/** Event tags for a played turn: `day` for the action frame, `night` for the night frame that follows it (if any). */
export function islandEvents(world: IslandWorld, before: IslandSnap, s: IslandState, step: IslandStep): { day: IslandEventTag[]; night: IslandEventTag[] } {
  const day: IslandEventTag[] = [];
  const night: IslandEventTag[] = [];
  const cmd = step.valid ? parseIslandCommand(step.action) : null;
  const ok = step.valid && step.tone !== 'bad';
  if (!step.valid) day.push('invalid');
  if (!before.springKnown && s.explored[world.spring.y]![world.spring.x]!) day.push('found-water');
  if (cmd?.kind === 'drink' && ok) day.push('drink');
  if (s.poisonEaten > before.poison) day.push('poison');
  else if (cmd?.kind === 'eat' && ok) day.push('eat');
  if (cmd?.kind === 'fish' && (s.inv.rawfish ?? 0) > before.fish) day.push('fish');
  const m = s.milestones;
  const b = before.milestones;
  if (m.spear !== null && b.spear === null) day.push('spear');
  if (m.campfire !== null && b.campfire === null) day.push('campfire');
  if (m.shelter !== null && b.shelter === null) day.push('shelter');
  if (m.signalPile !== null && b.signalPile === null) day.push('signal-built');
  if (cmd?.kind === 'light' && before.signal === 'built' && s.signal !== 'built') day.push('signal-lit');
  if (s.bottleFound && !before.bottle) day.push('bottle');
  if (s.shipsSeen.length > before.ships) day.push('ship-pass');
  if (s.rescued) day.push('rescued');
  else if (s.sightings.length > before.sightings) day.push('ship-ack');
  const nightDeath = !s.alive && before.alive && step.night !== null;
  if (!s.alive && before.alive && !nightDeath) day.push('died');
  if (nightDeath) night.push('died');
  const mid = step.preNight?.health ?? s.health;
  if (before.health - mid >= 10 && !day.includes('poison') && !day.includes('died')) day.push('hurt');
  if (step.night && mid - s.health >= 10 && !nightDeath) night.push('hurt');
  if (s.over && s.alive && !s.rescued) (step.night ? night : day).push('survived');
  return { day, night };
}

/** The per-step island view (position, fog of war, camp, inventory, events). */
export function islandSimFrame(s: IslandState, day: number, phase: number, events: IslandEventTag[]): IslandSimFrame {
  return {
    kind: 'island',
    day,
    phase,
    pos: xy(s.pos),
    explored: s.explored.map((row) => row.map((v) => (v ? '1' : '0')).join('')),
    inventory: { ...s.inv },
    spear: s.spear,
    fires: Object.entries(s.fires)
      .filter(([, f]) => f > 0)
      .map(([k, f]): [number, number, number] => {
        const [x, y] = k.split(',').map(Number);
        return [x!, y!, f];
      }),
    shelters: s.shelters.map((k): XY => {
      const [x, y] = k.split(',').map(Number);
      return [x!, y!];
    }),
    signal: s.signal,
    bottleFound: s.bottleFound,
    sightings: s.sightings.length,
    alive: s.alive,
    rescued: s.rescued,
    cause: s.alive ? null : s.cause,
    events,
  };
}
