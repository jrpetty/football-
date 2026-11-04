// The world: terrain, people, tribes, buildings, wildlife, weather, time.
// `tick(minutes)` advances everything deterministically from the world seed;
// the AI layer (src/ai) only ever changes an agent's *plan*, and the
// simulation carries that plan out under the same physical rules as everyone.

import { B, BIOMES, D, DEPOSITS, DEPTH_NAMES, F, FEATURES, TREE_FEATURES, ITEMS, FOOD_ITEMS, invAdd, invCount, invTotal, type Inventory, type Item } from './data.ts'
import { BUILDINGS, type BuildingType } from './buildings.ts'
import { TECH_BY_ID, TECHS, availableTechs, eraOf, ERAS, inspires, geographyAffinity, techFx, RESEARCH_PRIORITY, type Landscape, type TechDef } from './tech.ts'
import { generateTerrain, generateGeology, PRESETS, featureAmount, type Preset } from './worldgen.ts'
import { Rng, hash2, clamp } from './rng.ts'
import { findPath, type PathGrid } from './path.ts'
import { personName, tribeName, TRIBE_COLORS, TRIBE_EMBLEMS } from './names.ts'
import { DAY, HOUR, YEAR, SEASONS, seasonOf, isNight, hourOf, ageYears, dayOf } from './time.ts'
import type {
  Look, Agent, Tribe, Building, Animal, ChronicleEntry, Chapter, WorldEvent, Sample, Traits, SkillName, Memory, PlanStep, Task,
} from './types.ts'
import { SKILL_NAMES } from './types.ts'
import { updateTask, startTask, cancelTask } from './actions.ts'
import { instinctStep } from './instinct.ts'
import { newDiplomacy, diplomacyDaily, diplomacyHourly, type Diplomacy } from './diplomacy.ts'

export interface WorldConfig {
  name: string
  seed: number
  size: number
  preset: string
  tribes: number
  perTribe: number
  popCap: number
  /** replay exactly the same history for this seed (otherwise every run diverges) */
  fixedHistory?: boolean
}

export const DEFAULT_CONFIG: WorldConfig = {
  name: 'Terra Nova', seed: 1337, size: 160, preset: 'continent', tribes: 3, perTribe: 5, popCap: 90,
}

export type Weather = 'clear' | 'cloudy' | 'rain' | 'storm' | 'snow' | 'fog'

export const ADULT_AGE = 14
export const CARRY_CAP = 30

export class World implements PathGrid {
  cfg: WorldConfig
  preset: Preset
  W: number
  H: number
  biome!: Uint8Array
  elev!: Float32Array
  temp!: Float32Array
  moist!: Float32Array
  feature!: Uint8Array
  amount!: Uint16Array
  fertility!: Float32Array
  road!: Uint8Array
  occ!: Int32Array // building id + 1
  /** connected land regions (on foot), for instantly rejecting impossible paths */
  comp!: Int32Array
  /** short-lived "already looked, nothing there" memory, keyed per tribe */
  failCache = new Map<string, number>()
  explored = new Map<number, Uint8Array>()
  regrow: { i: number; t: number; f: number }[] = []
  /** what lies underground: mineral (D), reserves and depth (0 surface … 3 very deep) */
  deposit!: Uint8Array
  depAmt!: Uint16Array
  depDepth!: Uint8Array
  /** which deposits each tribe has found by prospecting */
  known = new Map<number, Uint8Array>()
  /** the same, as a list of tiles (derived) */
  private knownTiles = new Map<number, number[]>()
  private powerCache = new Map<number, { v: number; on: boolean }>()
  /** relations between peoples: contact, pacts, wars, campaigns */
  dip: Diplomacy = newDiplomacy()
  /** fish in each water tile, and what that water can support */
  fish!: Float32Array
  fishCap!: Float32Array

  time = 6 * HOUR // start at dawn, day 0
  rng: Rng
  nextId = 1
  agents: Agent[] = []
  tribes: Tribe[] = []
  buildings: Building[] = []
  animals: Animal[] = []
  chronicle: ChronicleEntry[] = []
  chapters: Chapter[] = []
  events: WorldEvent[] = []
  samples: Sample[] = []
  weather: Weather = 'clear'
  weatherUntil = 0
  milestones: string[] = []

  // derived, not serialised
  agentById = new Map<number, Agent>()
  buildingById = new Map<number, Building>()
  techSets = new Map<number, Set<string>>()
  dirtyChunks = new Set<number>()
  territoryDirty = true
  version = 0
  listeners = new Set<(e: ChronicleEntry) => void>()
  /** Set by the AI layer: is this agent's mind driven by Claude right now? */
  aiActive: (a: Agent) => boolean = () => false

  constructor(cfg: WorldConfig, skipGen = false) {
    this.cfg = { ...cfg }
    this.preset = PRESETS.find((p) => p.id === cfg.preset) ?? PRESETS[0]
    this.W = cfg.size
    this.H = cfg.size
    this.rng = new Rng(cfg.seed ^ 0x5bd1e995)
    if (!skipGen) this.generate()
  }

  // ---------------------------------------------------------------- creation

  private generate() {
    const m = generateTerrain(this.W, this.H, this.cfg.seed, this.preset)
    this.biome = m.biome
    this.elev = m.elev
    this.temp = m.temp
    this.moist = m.moist
    this.feature = m.feature
    this.amount = m.amount
    this.fertility = m.fertility
    this.deposit = m.deposit
    this.depAmt = m.depAmt
    this.depDepth = m.depDepth
    this.initFish()
    this.road = new Uint8Array(this.W * this.H)
    this.occ = new Int32Array(this.W * this.H)
    this.computeComponents()

    // The map is reproducible from its seed; the *history* is not. Mixing in
    // fresh entropy means the same land gives rise to different people,
    // different discoveries and a different story every time.
    if (!this.cfg.fixedHistory) {
      let e = Math.floor(Math.random() * 0xffffffff)
      try { const b = new Uint32Array(1); crypto.getRandomValues(b); e ^= b[0] } catch { /* no crypto */ }
      this.rng.state = (this.rng.state ^ e) >>> 0
    }
    const sites = this.pickStartSites(this.cfg.tribes)
    sites.forEach((s, k) => this.spawnTribe(s.x, s.y, k))
    this.spawnAnimals(Math.round((this.W * this.H) / 600))
    this.log('milestone', `The world of ${this.cfg.name} awakens. ${this.agents.length} people in ${this.tribes.length} bands open their eyes for the first time.`, '🌍')
  }

  private pickStartSites(n: number): { x: number; y: number }[] {
    const cands: { x: number; y: number; s: number }[] = []
    for (let k = 0; k < 4000; k++) {
      const x = this.rng.int(6, this.W - 7)
      const y = this.rng.int(6, this.H - 7)
      const i = this.idx(x, y)
      const b = this.biome[i]
      if (!this.walkableTile(i) || b === B.BEACH || b === B.RIVER || b === B.SNOW || b === B.MOUNTAIN) continue
      const t = this.temp[i]
      let water = 99
      let trees = 0, food = 0, stone = 0
      for (let dy = -9; dy <= 9; dy++) for (let dx = -9; dx <= 9; dx++) {
        const j = this.idx(clamp(x + dx, 0, this.W - 1), clamp(y + dy, 0, this.H - 1))
        if (BIOMES[this.biome[j]].fresh && this.biome[j] !== B.SNOW) water = Math.min(water, Math.abs(dx) + Math.abs(dy))
        const f = this.feature[j]
        if (TREE_FEATURES.has(f)) trees++
        if (f === F.BUSH) food++
        if (f === F.ROCK) stone++
      }
      if (water > 8) continue
      const s = (1 - Math.abs(t - 0.55)) * 3 + Math.min(trees, 30) / 10 + Math.min(food, 10) / 4 + Math.min(stone, 3) + this.fertility[i] * 2 - water * 0.15
      cands.push({ x, y, s })
    }
    cands.sort((a, b) => b.s - a.s)
    const out: { x: number; y: number }[] = []
    const minD = Math.min(this.W, this.H) * (n <= 2 ? 0.35 : n <= 4 ? 0.26 : 0.18)
    for (const c of cands) {
      if (out.every((o) => Math.hypot(o.x - c.x, o.y - c.y) > minD)) out.push(c)
      if (out.length >= n) break
    }
    // fall back to closer sites if the map is small
    for (const c of cands) {
      if (out.length >= n) break
      if (out.every((o) => Math.hypot(o.x - c.x, o.y - c.y) > 10)) out.push(c)
    }
    if (out.length === 0) out.push({ x: this.W >> 1, y: this.H >> 1 })
    return out
  }

  spawnTribe(x: number, y: number, k: number): Tribe {
    const t = this.newTribe(tribeName(this.rng), x, y, -1, ['foraging'])
    t.color = TRIBE_COLORS[k % TRIBE_COLORS.length]
    t.emblem = TRIBE_EMBLEMS[k % TRIBE_EMBLEMS.length]
    const n = this.cfg.perTribe
    const members: Agent[] = []
    for (let j = 0; j < n; j++) {
      const sex: 'female' | 'male' = j % 2 === 0 ? 'female' : 'male'
      const age = this.rng.range(16, 34)
      let px = x, py = y
      for (let tries = 0; tries < 30; tries++) {
        px = x + this.rng.int(-3, 3)
        py = y + this.rng.int(-3, 3)
        if (this.inBounds(px, py) && this.walkableTile(this.idx(px, py))) break
      }
      const a = this.newAgent(sex, age, px + 0.5, py + 0.5, t.id, t.id, [], 0)
      members.push(a)
    }
    t.founderId = members[0].id
    t.leaderId = members[0].id
    // they all know each other a little
    for (const a of members) for (const b of members) if (a !== b) a.relations[b.id] = { affinity: this.rng.int(5, 35), familiarity: 40, lastTalk: 0 }
    // a band is built around families: pair up the first couple or two
    for (let j = 0; j + 1 < members.length && j < 4; j += 2) {
      const f = members[j], m = members[j + 1]
      f.partnerId = m.id
      m.partnerId = f.id
      f.relations[m.id] = { affinity: 70, familiarity: 90, lastTalk: 0 }
      m.relations[f.id] = { affinity: 70, familiarity: 90, lastTalk: 0 }
    }
    return t
  }

  newTribe(name: string, x: number, y: number, founderId: number, techs: string[]): Tribe {
    const id = this.nextId++
    const t: Tribe = {
      id, name, color: TRIBE_COLORS[this.tribes.length % TRIBE_COLORS.length], emblem: TRIBE_EMBLEMS[this.tribes.length % TRIBE_EMBLEMS.length],
      founderId, leaderId: founderId, foundedT: this.time, values: '', beliefs: '', laws: [], techs: [...techs],
      research: null, insight: {}, lost: [], storage: { berries: 10 }, center: { x, y }, relations: {}, model: '', alive: true, history: [], works: [], launched: false,
    }
    this.tribes.push(t)
    this.techSets.set(id, new Set(techs))
    this.explored.set(id, new Uint8Array(this.W * this.H))
    this.revealAround(id, x, y, 14)
    return t
  }

  newAgent(sex: 'female' | 'male', ageYrs: number, x: number, y: number, tribeId: number, culture: number, parents: Agent[] | number[], generation: number): Agent {
    const id = this.nextId++
    const pa = (parents as (Agent | number)[]).map((p) => (typeof p === 'number' ? this.agentById.get(p) : p)).filter(Boolean) as Agent[]
    const trait = (k: keyof Traits) => {
      if (pa.length === 2) return clamp((pa[0].traits[k] + pa[1].traits[k]) / 2 + this.rng.gauss(0, 0.12), 0.02, 0.98) // inheritance + mutation
      return clamp(this.rng.gauss(0.5, 0.2), 0.02, 0.98)
    }
    const traits: Traits = {
      openness: trait('openness'), conscientiousness: trait('conscientiousness'), extraversion: trait('extraversion'),
      agreeableness: trait('agreeableness'), neuroticism: trait('neuroticism'), intelligence: trait('intelligence'), strength: trait('strength'),
    }
    const skills = Object.fromEntries(SKILL_NAMES.map((s) => [s, Math.round(this.rng.range(5, 25) + (pa.length ? 5 : 0))])) as Record<SkillName, number>
    const tribe = this.tribeById(tribeId)
    const a: Agent = {
      id, name: personName(this.rng, culture, sex), sex, birth: this.time - ageYrs * YEAR, x, y, dir: 1, moving: false,
      tribeId, homeId: 0, partnerId: 0, parents: pa.map((p) => p.id), children: [], alive: true, deathT: 0, deathCause: '', generation,
      health: 100, hunger: this.rng.range(10, 35), thirst: this.rng.range(10, 30), energy: this.rng.range(70, 95), warmth: 90, social: 70, mood: 65,
      sick: 0, injured: 0, traits, skills, toolTier: 0, toolWear: 0, clothing: 30, inv: {},
      memories: [], reflections: [], goal: '', selfImage: '', beliefs: '', thought: '', feeling: 'curious', relations: {}, heard: [], journal: [],
      plan: [], task: null, speech: null, emote: null, look: undefined as unknown as Look, aiPending: false, aiLastT: -1e9, aiDecisions: 0, aiErrors: 0, lastReflectT: this.time, urgentThink: true, model: '',
      stats: { gathered: 0, built: 0, research: 0, crafted: 0, talks: 0, kids: 0 },
    }
    if (pa.length) a.name = personName(this.rng, culture + (tribe ? tribe.id : 0), sex)
    a.look = this.makeLook(sex, traits, pa)
    this.agents.push(a)
    this.agentById.set(id, a)
    this.version++
    return a
  }

  private makeLook(sex: 'female' | 'male', t: Traits, parents: Agent[]): Look {
    const r = this.rng
    const inherit = <T,>(f: (l: Look) => T, fresh: () => T): T => (parents.length && r.chance(0.8) ? f(r.pick(parents).look) : fresh())
    const accents = ['#e8c547', '#6fd3c7', '#e57373', '#9575cd', '#81c784', '#ffb74d', '#f06292', '#4fc3f7', '#dce775', '#a1887f']
    return {
      skin: inherit((l) => l.skin, () => r.int(0, 5)),
      hair: inherit((l) => l.hair, () => r.int(0, 6)),
      style: sex === 'female' ? r.pick([1, 1, 2, 3, 5, 0]) : r.pick([0, 0, 4, 5, 1, 2]),
      beard: sex === 'male' && r.chance(0.45),
      height: clamp(r.gauss(1, 0.05) + (sex === 'male' ? 0.03 : -0.02), 0.88, 1.12),
      build: clamp(0.85 + t.strength * 0.35 + r.gauss(0, 0.04), 0.82, 1.22),
      // the bold and the open wear brighter colours
      accent: t.extraversion + t.openness > 1.1 ? r.pick(accents.slice(0, 6)) : r.pick(['#8d6e63', '#78909c', '#a1887f', '#90a4ae', '#bcaaa4']),
      marks: r.chance(0.12) ? 3 : r.chance(0.2) ? 2 : t.extraversion > 0.7 && r.chance(0.4) ? 1 : 0,
    }
  }

  /** Show a little icon over someone's head for a moment. */
  emote(a: Agent, icon: string, ms = 4000) {
    const now = typeof performance !== 'undefined' ? performance.now() : Date.now()
    a.emote = { icon, until: now + ms, start: now }
  }

  spawnAnimals(n: number) {
    for (let k = 0; k < n; k++) {
      for (let tries = 0; tries < 30; tries++) {
        const x = this.rng.int(1, this.W - 2), y = this.rng.int(1, this.H - 2)
        const b = this.biome[this.idx(x, y)]
        if (b === B.FOREST || b === B.GRASSLAND || b === B.SAVANNA || b === B.TAIGA || b === B.RAINFOREST || b === B.TUNDRA) {
          const kind = b === B.SAVANNA || b === B.GRASSLAND ? (this.rng.chance(0.25) ? 'rabbit' : 'deer') : this.rng.chance(0.35) ? 'boar' : 'deer'
          this.animals.push({ id: this.nextId++, kind, x: x + 0.5, y: y + 0.5, tx: x, ty: y, alive: true, dir: 1 })
          break
        }
      }
    }
  }

  computeComponents() {
    const N = this.W * this.H
    const comp = new Int32Array(N).fill(-1)
    const q = new Int32Array(N)
    let label = 0
    for (let s0 = 0; s0 < N; s0++) {
      if (comp[s0] >= 0 || BIOMES[this.biome[s0]].move === Infinity) continue
      let h = 0, t = 0
      q[t++] = s0
      comp[s0] = label
      while (h < t) {
        const i = q[h++]
        const x = i % this.W, y = (i / this.W) | 0
        for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
          const nx = x + dx, ny = y + dy
          if (nx < 0 || ny < 0 || nx >= this.W || ny >= this.H) continue
          const j = ny * this.W + nx
          if (comp[j] >= 0 || BIOMES[this.biome[j]].move === Infinity) continue
          comp[j] = label
          q[t++] = j
        }
      }
      label++
    }
    this.comp = comp
  }

  failed(key: string): boolean {
    const until = this.failCache.get(key)
    return until !== undefined && until > this.time
  }
  markFailed(key: string, hours: number) {
    this.failCache.set(key, this.time + hours * HOUR)
    if (this.failCache.size > 2000) for (const [k, v] of this.failCache) if (v <= this.time) this.failCache.delete(k)
  }

  // ---------------------------------------------------------------- helpers

  idx(x: number, y: number): number {
    return y * this.W + x
  }
  inBounds(x: number, y: number): boolean {
    return x >= 0 && y >= 0 && x < this.W && y < this.H
  }
  tribeById(id: number): Tribe | undefined {
    return this.tribes.find((t) => t.id === id)
  }
  techs(tribeId: number): Set<string> {
    let s = this.techSets.get(tribeId)
    if (!s) {
      const t = this.tribeById(tribeId)
      s = new Set(t ? t.techs : [])
      this.techSets.set(tribeId, s)
    }
    return s
  }
  hasTech(tribeId: number, tech: string | null): boolean {
    return !tech || this.techs(tribeId).has(tech)
  }
  members(tribeId: number): Agent[] {
    return this.agents.filter((a) => a.alive && a.tribeId === tribeId)
  }
  get living(): Agent[] {
    return this.agents.filter((a) => a.alive)
  }
  age(a: Agent): number {
    return ageYears(a.birth, this.time)
  }
  isAdult(a: Agent): boolean {
    return this.age(a) >= ADULT_AGE
  }
  private tbCache = new Map<string, Building[]>()
  private tbVersion = -1
  /** Bumped whenever buildings are added, finished or removed (invalidates caches). */
  buildVersion = 0
  tribeBuildings(tribeId: number, type?: BuildingType, doneOnly = true): Building[] {
    if (this.tbVersion !== this.buildVersion) { this.tbCache.clear(); this.tbVersion = this.buildVersion }
    const key = `${tribeId}|${type ?? ''}|${doneOnly ? 1 : 0}`
    let v = this.tbCache.get(key)
    if (!v) {
      v = this.buildings.filter((b) => b.tribeId === tribeId && (!type || b.type === type) && (!doneOnly || b.done))
      this.tbCache.set(key, v)
    }
    return v
  }
  private warmGrid: Uint8Array | null = null
  private warmVersion = -1
  findAgentByName(name: string, near?: Agent): Agent | undefined {
    const n = name.trim().toLowerCase().replace(/^the\s+/, '')
    if (!n) return undefined
    const alive = this.agents.filter((a) => a.alive && a !== near)
    let best = alive.filter((a) => a.name.toLowerCase() === n)
    if (!best.length) best = alive.filter((a) => n.includes(a.name.toLowerCase()) || a.name.toLowerCase().startsWith(n))
    if (!best.length) return undefined
    if (near) best.sort((a, b) => Math.hypot(a.x - near.x, a.y - near.y) - Math.hypot(b.x - near.x, b.y - near.y))
    return best[0]
  }
  findTribeByName(name: string): Tribe | undefined {
    const n = name.trim().toLowerCase().replace(/^the\s+/, '')
    return this.tribes.find((t) => t.alive && t.name.toLowerCase() === n) ?? this.tribes.find((t) => t.alive && (t.name.toLowerCase().includes(n) || n.includes(t.name.toLowerCase())))
  }

  walkableTile(i: number): boolean {
    return this.cost(i, 0) !== Infinity
  }
  /** PathGrid: cost of entering tile i. */
  cost(i: number, sail: number): number {
    const o = this.occ[i]
    if (o) {
      const b = this.buildingById.get(o - 1)
      if (b && !BUILDINGS[b.type].walkable) return Infinity
    }
    if (this.road[i]) return this.road[i] === 2 ? 0.22 : 0.4
    const bd = BIOMES[this.biome[i]]
    if (bd.move === Infinity) {
      const b = this.biome[i]
      if (sail >= 1 && (b === B.SHALLOWS || b === B.LAKE)) return 1.6
      if (sail >= 2 && b === B.OCEAN) return 1.4
      if (sail >= 3 && b === B.DEEP_OCEAN) return 2
      return Infinity
    }
    return bd.move
  }
  /** 0 on foot · 1 canoes (lakes, shallows) · 2 sail (open sea) · 3 navigation (deep ocean) */
  canSail(tribeId: number): number {
    return this.hasTech(tribeId, 'navigation') ? 3 : this.hasTech(tribeId, 'sailing') ? 2 : this.hasTech(tribeId, 'boats') ? 1 : 0
  }
  path(a: Agent, tx: number, ty: number, adjacent: boolean): Int32Array | null {
    const sx = Math.floor(a.x), sy = Math.floor(a.y)
    const sail = this.canSail(a.tribeId)
    if (sail === 0 && this.comp) {
      const c = this.comp[sy * this.W + sx]
      let ok = c >= 0 && this.comp[ty * this.W + tx] === c
      if (!ok && adjacent && c >= 0) {
        for (let dy = -1; dy <= 1 && !ok; dy++) for (let dx = -1; dx <= 1 && !ok; dx++) {
          const nx = tx + dx, ny = ty + dy
          if (this.inBounds(nx, ny) && this.comp[ny * this.W + nx] === c) ok = true
        }
      }
      if (!ok && c >= 0) return null
    }
    const dist = Math.abs(tx - sx) + Math.abs(ty - sy)
    return findPath(this, sx, sy, tx, ty, adjacent, sail, Math.min(30000, 2500 + dist * dist * 4))
  }

  remember(a: Agent, text: string, imp = 3) {
    // the same thing happening again refreshes the memory rather than repeating it
    for (let k = a.memories.length - 1; k >= Math.max(0, a.memories.length - 10); k--) {
      if (a.memories[k].text === text) {
        const m = a.memories.splice(k, 1)[0]
        m.t = this.time
        m.imp = Math.max(m.imp, imp)
        a.memories.push(m)
        return
      }
    }
    a.memories.push({ t: this.time, text, imp })
    if (a.memories.length > 70) {
      // forget the least important of the older half
      let worst = 0
      for (let k = 1; k < 40; k++) if (a.memories[k].imp < a.memories[worst].imp) worst = k
      a.memories.splice(worst, 1)
    }
  }
  recentMemories(a: Agent, n: number): Memory[] {
    return a.memories.slice(-n)
  }
  relation(a: Agent, b: Agent) {
    let r = a.relations[b.id]
    if (!r) r = a.relations[b.id] = { affinity: 0, familiarity: 0, lastTalk: 0 }
    return r
  }
  bump(a: Agent, b: Agent, aff: number, fam = 2) {
    const r = this.relation(a, b)
    r.affinity = clamp(r.affinity + aff, -100, 100)
    r.familiarity = clamp(r.familiarity + fam, 0, 100)
    r.lastTalk = this.time
  }

  log(kind: ChronicleEntry['kind'], text: string, icon: string, agentId?: number, tribeId?: number) {
    const e: ChronicleEntry = { t: this.time, kind, text, icon, agentId, tribeId }
    this.chronicle.push(e)
    if (this.chronicle.length > 4000) this.chronicle.splice(0, 500)
    if (tribeId) {
      const t = this.tribeById(tribeId)
      if (t && kind !== 'speech') {
        t.history.push({ t: this.time, text })
        if (t.history.length > 120) t.history.shift()
      }
    }
    for (const l of this.listeners) l(e)
  }

  markTile(i: number) {
    const x = i % this.W, y = (i / this.W) | 0
    this.dirtyChunks.add(((y >> 5) << 8) | (x >> 5))
  }

  revealAround(tribeId: number, x: number, y: number, r: number) {
    const ex = this.explored.get(tribeId)
    if (!ex) return
    const cx = Math.floor(x), cy = Math.floor(y)
    for (let dy = -r; dy <= r; dy++) {
      const yy = cy + dy
      if (yy < 0 || yy >= this.H) continue
      const w = Math.floor(Math.sqrt(r * r - dy * dy))
      for (let dx = -w; dx <= w; dx++) {
        const xx = cx + dx
        if (xx >= 0 && xx < this.W) ex[yy * this.W + xx] = 1
      }
    }
  }
  isExplored(tribeId: number, i: number): boolean {
    const ex = this.explored.get(tribeId)
    return ex ? ex[i] === 1 : true
  }

  storageCap(tribeId: number): number {
    let cap = 60
    for (const b of this.tribeBuildings(tribeId)) cap += BUILDINGS[b.type].storage ?? 0
    return cap
  }
  storageUsed(t: Tribe): number {
    return invTotal(t.storage)
  }

  /** Nearest tile satisfying pred, searching rings outward (cheap for small radii). */
  nearestTile(x: number, y: number, r: number, pred: (i: number) => boolean): number {
    const cx = Math.floor(x), cy = Math.floor(y)
    for (let d = 0; d <= r; d++) {
      for (let k = -d; k <= d; k++) {
        const cand = [[cx + k, cy - d], [cx + k, cy + d], [cx - d, cy + k], [cx + d, cy + k]]
        for (const [xx, yy] of cand) {
          if (xx < 0 || yy < 0 || xx >= this.W || yy >= this.H) continue
          const i = yy * this.W + xx
          if (pred(i)) return i
        }
      }
    }
    return -1
  }

  /** The tribe's communal gathering point: town hall › storage › campfire › centre. */
  tribeHub(tribeId: number): { x: number; y: number; b?: Building } {
    const t = this.tribeById(tribeId)
    const pri: BuildingType[] = ['town_hall', 'warehouse', 'granary', 'stockpile', 'campfire']
    for (const type of pri) {
      const bs = this.tribeBuildings(tribeId, type)
      if (bs.length && t) {
        bs.sort((a, b) => Math.hypot(a.x - t.center.x, a.y - t.center.y) - Math.hypot(b.x - t.center.x, b.y - t.center.y))
        return { x: bs[0].x, y: bs[0].y, b: bs[0] }
      }
    }
    return t ? { x: t.center.x, y: t.center.y } : { x: this.W >> 1, y: this.H >> 1 }
  }

  nearestBuilding(a: Agent, types: BuildingType[], tribeOnly = true, maxD = 60): Building | undefined {
    let best: Building | undefined
    let bd = maxD
    for (const b of tribeOnly ? this.tribeBuildings(a.tribeId) : this.buildings) {
      if (!b.done || !types.includes(b.type)) continue
      const d = Math.hypot(b.x + 0.5 - a.x, b.y + 0.5 - a.y)
      if (d < bd) { bd = d; best = b }
    }
    return best
  }

  // ---------------------------------------------------------------- environment

  get season(): number {
    return seasonOf(this.time)
  }
  get era(): number {
    let e = 0
    for (const t of this.tribes) if (t.alive) e = Math.max(e, eraOf(this.techs(t.id)))
    return e
  }
  hasEvent(kind: WorldEvent['kind']): boolean {
    return this.events.some((e) => e.kind === kind && e.start <= this.time && e.end > this.time)
  }

  /** Felt temperature 0 (freezing) … 1 (hot) at a tile now. */
  feltTemp(x: number, y: number): number {
    const i = this.idx(clamp(Math.floor(x), 0, this.W - 1), clamp(Math.floor(y), 0, this.H - 1))
    const s = this.season
    let t = this.temp[i] + [0, 0.14, -0.04, -0.28][s]
    if (isNight(this.time)) t -= 0.1
    if (this.weather === 'rain' || this.weather === 'storm') t -= 0.05
    if (this.weather === 'snow') t -= 0.08
    if (this.hasEvent('harsh_winter') && s === 3) t -= 0.15
    return t
  }

  warmSourceNear(x: number, y: number): boolean {
    if (!this.warmGrid || this.warmVersion !== this.buildVersion) {
      const g = this.warmGrid ?? new Uint8Array(this.W * this.H)
      g.fill(0)
      for (const b of this.buildings) {
        if (!b.done) continue
        const r = BUILDINGS[b.type].warmth
        if (!r) continue
        for (let dy = -r; dy <= r; dy++) for (let dx = -r; dx <= r; dx++) {
          const xx = b.x + dx, yy = b.y + dy
          if (this.inBounds(xx, yy)) g[yy * this.W + xx] = 1
        }
      }
      this.warmGrid = g
      this.warmVersion = this.buildVersion
    }
    const xi = Math.floor(x), yi = Math.floor(y)
    return this.inBounds(xi, yi) && this.warmGrid[yi * this.W + xi] === 1
  }

  // ---------------------------------------------------------------- the sea and the ground

  /** Fish stocks follow the water: rich coastal shallows, upwelling at the shelf edge, thin open ocean. */
  initFish() {
    const N = this.W * this.H
    this.fish = new Float32Array(N)
    this.fishCap = new Float32Array(N)
    for (let i = 0; i < N; i++) {
      const b = this.biome[i]
      let cap = b === B.SHALLOWS ? 90 : b === B.OCEAN ? 70 : b === B.DEEP_OCEAN ? 30 : b === B.LAKE ? 50 : b === B.RIVER ? 24 : b === B.MARSH ? 6 : 0
      if (b === B.OCEAN || b === B.DEEP_OCEAN) {
        // the shelf edge, where cold water wells up, teems with life
        const x = i % this.W, y = (i / this.W) | 0
        let shelf = false
        for (let dy = -3; dy <= 3 && !shelf; dy++) for (let dx = -3; dx <= 3 && !shelf; dx++) if (this.inBounds(x + dx, y + dy) && this.biome[this.idx(x + dx, y + dy)] === B.SHALLOWS) shelf = true
        if (shelf) cap *= 1.6
        cap *= 0.75 + hash2(x >> 3, y >> 3, this.cfg.seed + 5) * 0.6
      }
      this.fishCap[i] = cap
      this.fish[i] = cap
    }
  }
  /** Stocks recover logistically, and drift in from neighbouring water. */
  private growFish() {
    const N = this.W * this.H
    for (let i = 0; i < N; i++) {
      const cap = this.fishCap[i]
      if (!cap) continue
      const f = this.fish[i]
      // fish breed fast at low density: a village can fish a lake for ever, a trawler fleet can strip it
      this.fish[i] = Math.min(cap, f + 0.3 * f * (1 - f / cap) + cap * 0.012)
    }
  }
  /** How many fish swim within r tiles of (x, y). */
  fishNear(x: number, y: number, r: number): number {
    let s = 0
    for (let dy = -r; dy <= r; dy++) for (let dx = -r; dx <= r; dx++) {
      const xx = Math.floor(x) + dx, yy = Math.floor(y) + dy
      if (this.inBounds(xx, yy)) s += this.fish[this.idx(xx, yy)]
    }
    return s
  }
  /** Take n fish from around a spot; returns how many were really there. */
  takeFish(x: number, y: number, n: number, r = 2): number {
    let got = 0
    for (let dy = -r; dy <= r && got < n; dy++) for (let dx = -r; dx <= r && got < n; dx++) {
      const xx = Math.floor(x) + dx, yy = Math.floor(y) + dy
      if (!this.inBounds(xx, yy)) continue
      const i = this.idx(xx, yy)
      const take = Math.min(this.fish[i], n - got)
      this.fish[i] -= take
      got += take
    }
    return got
  }

  /** Old saves predate geology: rebuild it from the seed (without touching the surface). */
  ensureGeology() {
    if (this.deposit) return
    const g = generateGeology(this.W, this.H, this.cfg.seed, this.biome, this.elev, this.temp, this.moist, new Uint8Array(this.feature), new Uint16Array(this.amount))
    this.deposit = g.deposit
    this.depAmt = g.depAmt
    this.depDepth = g.depDepth
  }

  /** Put someone to a task right now (orders from their people, not their own plan). */
  order(a: Agent, step: PlanStep) {
    cancelTask(this, a)
    startTask(this, a, step, false)
  }

  /** Does this people's town have electricity? */
  hasPower(tribeId: number): boolean {
    const c = this.powerCache.get(tribeId)
    if (c && c.v === this.buildVersion) return c.on
    const on = this.buildings.some((b) => b.done && b.tribeId === tribeId && BUILDINGS[b.type].power)
    this.powerCache.set(tribeId, { v: this.buildVersion, on })
    return on
  }
  /** Tiles of every deposit this tribe has found. */
  knownList(tribeId: number): number[] {
    let l = this.knownTiles.get(tribeId)
    if (!l) {
      l = []
      const k = this.knownDeposits(tribeId)
      for (let i = 0; i < k.length; i++) if (k[i]) l.push(i)
      this.knownTiles.set(tribeId, l)
    }
    return l
  }

  knownDeposits(tribeId: number): Uint8Array {
    let k = this.known.get(tribeId)
    if (!k) { k = new Uint8Array(this.W * this.H); this.known.set(tribeId, k) }
    return k
  }
  knowsDeposit(tribeId: number, i: number): boolean {
    return !!this.deposit[i] && this.knownDeposits(tribeId)[i] === 1
  }
  /** How deep a people can dig: pits → shafts → steam-pumped deep mines. */
  maxDepth(tribeId: number): number {
    return this.hasTech(tribeId, 'deep_mining') ? 3 : this.hasTech(tribeId, 'shaft_mining') ? 2 : this.hasTech(tribeId, 'mining') ? 1 : 0
  }
  /** Can this tribe extract the deposit under tile i (and with which installation)? */
  extractable(tribeId: number, i: number): 'mine' | 'oil_well' | 'offshore_rig' | 'evaporation_ponds' | null {
    const d = this.deposit[i]
    if (!d || !this.depAmt[i] || !this.knowsDeposit(tribeId, i)) return null
    const def = DEPOSITS[d]
    const water = BIOMES[this.biome[i]].water
    if (def.via === 'well') return water ? (this.hasTech(tribeId, 'offshore_drilling') ? 'offshore_rig' : null) : this.hasTech(tribeId, 'oil_drilling') ? 'oil_well' : null
    if (def.via === 'brine') return this.hasTech(tribeId, 'chemistry') ? 'evaporation_ponds' : null
    return this.depDepth[i] <= this.maxDepth(tribeId) ? 'mine' : null
  }
  /** Mark a whole ore body (connected tiles of the same mineral) as found. */
  revealBody(tribeId: number, i: number): { tiles: number; reserves: number; depth: number } {
    const k = this.knownDeposits(tribeId)
    const d = this.deposit[i]
    if (!d) return { tiles: 0, reserves: 0, depth: 0 }
    const stack = [i]
    let tiles = 0, reserves = 0, depth = 3
    const list = this.knownList(tribeId)
    k[i] = 1
    list.push(i)
    while (stack.length) {
      const j = stack.pop()!
      tiles++
      reserves += this.depAmt[j]
      depth = Math.min(depth, this.depDepth[j])
      const x = j % this.W, y = (j / this.W) | 0
      for (const [dx, dy] of [[1, 0], [-1, 0], [0, 1], [0, -1]]) {
        if (!this.inBounds(x + dx, y + dy)) continue
        const n = this.idx(x + dx, y + dy)
        if (this.deposit[n] === d && !k[n]) { k[n] = 1; list.push(n); stack.push(n) }
      }
    }
    this.territoryDirty = true
    return { tiles, reserves, depth }
  }
  /**
   * An hour of prospecting around (x, y). Surface showings lead straight to
   * their body; beyond that, how far and how deep a survey sees depends on
   * what the people know — a prospector's eye, a geologist's map, a seismic
   * survey.
   */
  prospect(a: Agent, hours: number): string[] {
    const k = this.hasTech(a.tribeId, 'seismic_survey') ? 3 : this.hasTech(a.tribeId, 'geology') ? 2 : this.hasTech(a.tribeId, 'prospecting') ? 1 : 0
    const R = [3, 4, 7, 11][k], maxD = [1, 1, 2, 3][k]
    const found: string[] = []
    const x0 = Math.floor(a.x), y0 = Math.floor(a.y)
    const kd = this.knownDeposits(a.tribeId)
    const skill = 0.6 + a.skills.gathering / 150 + a.traits.intelligence * 0.3
    for (let dy = -R; dy <= R; dy++) for (let dx = -R; dx <= R; dx++) {
      const x = x0 + dx, y = y0 + dy
      if (!this.inBounds(x, y) || Math.hypot(dx, dy) > R) continue
      const i = this.idx(x, y)
      const d = this.deposit[i]
      if (!d || kd[i]) continue
      const water = BIOMES[this.biome[i]].water
      if (water && k < 3) continue // only seismic surveys see under the sea
      // a visible showing nearby makes the body easy to trace
      const f = this.feature[i]
      const showing = f && DEPOSITS[d].outcrop === f
      const depth = this.depDepth[i]
      if (!showing && depth > maxD) continue
      const p = showing ? 0.7 : (0.05 + 0.05 * k) * (1.4 - depth * 0.3) * skill
      if (this.rng.chance(Math.min(0.95, p * hours))) {
        const r = this.revealBody(a.tribeId, i)
        const def = DEPOSITS[d]
        const size = r.reserves > 3000 ? 'a vast' : r.reserves > 1200 ? 'a rich' : r.reserves > 400 ? 'a good' : 'a small'
        found.push(`${size} ${def.name.toLowerCase()} (${DEPTH_NAMES[Math.min(3, r.depth)]}, ~${Math.round(r.reserves / 10) * 10} ${ITEMS[def.item].name.toLowerCase()})`)
      }
    }
    return found
  }
  /** Draw one unit from the reserves around an extraction site. */
  extractNear(tribeId: number, x: number, y: number, item: Item): boolean {
    let best = -1, bv = 0
    for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) {
      if (!this.inBounds(x + dx, y + dy)) continue
      const i = this.idx(x + dx, y + dy)
      if (!this.deposit[i] || DEPOSITS[this.deposit[i]].item !== item || !this.extractable(tribeId, i)) continue
      if (this.depAmt[i] > bv) { bv = this.depAmt[i]; best = i }
    }
    if (best < 0) return false
    this.depAmt[best]--
    if (!this.depAmt[best]) this.deposit[best] = D.NONE
    return true
  }
  /** Reserves of an item reachable from an extraction site. */
  reservesNear(tribeId: number, x: number, y: number, item: Item): number {
    let n = 0
    for (let dy = -2; dy <= 2; dy++) for (let dx = -2; dx <= 2; dx++) {
      if (!this.inBounds(x + dx, y + dy)) continue
      const i = this.idx(x + dx, y + dy)
      if (this.deposit[i] && DEPOSITS[this.deposit[i]].item === item && this.extractable(tribeId, i)) n += this.depAmt[i]
    }
    return n
  }

  // ---------------------------------------------------------------- main loop

  tick(dtMin: number) {
    let left = dtMin
    while (left > 0) {
      const step = Math.min(left, 4)
      this.step(step)
      left -= step
    }
    this.version++
  }

  private step(dt: number) {
    const prev = this.time
    this.time += dt
    if (Math.floor(prev / HOUR) !== Math.floor(this.time / HOUR)) this.hourly()
    if (Math.floor(prev / DAY) !== Math.floor(this.time / DAY)) this.daily()
    if (seasonOf(prev) !== seasonOf(this.time)) this.newSeason()
    const dtH = dt / HOUR
    for (let k = 0; k < this.agents.length; k++) {
      const a = this.agents[k]
      if (a.alive) this.updateAgent(a, dtH)
    }
    for (const an of this.animals) if (an.alive) this.updateAnimal(an, dtH)
  }

  private updateAgent(a: Agent, dtH: number) {
    const sleeping = a.task?.action === 'sleep' && a.task.phase === 'work'
    const adult = this.isAdult(a)
    const ageY = this.age(a)
    // --- needs
    a.hunger = clamp(a.hunger + dtH * (sleeping ? 2 : 3.1), 0, 100)
    a.thirst = clamp(a.thirst + dtH * (sleeping ? 2.6 : 4.8) * (this.feltTemp(a.x, a.y) > 0.75 ? 1.4 : 1), 0, 100)
    if (sleeping) a.energy = clamp(a.energy + dtH * (a.homeId ? 14 : 9), 0, 100)
    else a.energy = clamp(a.energy - dtH * (a.task && a.task.phase === 'work' ? 4.6 : 3.4), 0, 100)
    a.social = clamp(a.social - dtH * (1 + a.traits.extraversion * 1.6), 0, 100)

    // --- warmth
    const felt = this.feltTemp(a.x, a.y) + (a.clothing > 0 ? 0.22 * Math.min(1, a.clothing / 40) : 0)
    let sheltered = sleeping && a.homeId > 0
    if (!sheltered && a.homeId && (!adult || sleeping)) {
      const h = this.buildingById.get(a.homeId)
      if (h && Math.abs(h.x + 0.5 - a.x) < 2.2 && Math.abs(h.y + 0.5 - a.y) < 2.2) sheltered = true
    }
    const warmSrc = sheltered || this.warmSourceNear(a.x, a.y)
    if (warmSrc || felt > 0.32) a.warmth = clamp(a.warmth + dtH * 18, 0, 100)
    else a.warmth = clamp(a.warmth - dtH * (0.32 - felt) * 70, 0, 100)
    if (a.clothing > 0) a.clothing = Math.max(0, a.clothing - dtH * 0.08)

    // --- health
    let dmg = 0
    if (a.hunger > 92) dmg += 2.2
    if (a.thirst > 92) dmg += 3.5
    if (a.warmth < 12) dmg += 2.5
    if (a.warmth < 40) this.practice(a, 'cold', dtH)
    if (a.sick > 0) this.practice(a, 'sick', dtH * 0.5)
    if (a.energy < 3) dmg += 0.6
    if (a.sick > 0) {
      dmg += a.sick * 0.35 * (this.hasTech(a.tribeId, 'antibiotics') ? 0.3 : 1)
      a.sick = Math.max(0, a.sick - dtH * (0.04 + (this.nearestBuilding(a, ['hospital'], true, 6) ? 0.2 : 0)))
    }
    if (a.injured > 0) {
      dmg += a.injured * 0.15
      a.injured = Math.max(0, a.injured - dtH * 0.08)
    }
    if (dmg > 0) a.health = clamp(a.health - dmg * dtH, 0, 100)
    else a.health = clamp(a.health + dtH * (sleeping ? 2.5 : 0.8), 0, 100)
    if (a.health <= 0) {
      const cause = a.sick > 0.5 ? 'illness' : a.thirst > 92 ? 'thirst' : a.hunger > 92 ? 'starvation' : a.warmth < 12 ? 'the cold' : a.injured > 0 ? 'wounds' : a.sick > 0 ? 'illness' : 'exhaustion'
      this.kill(a, cause)
      return
    }

    // --- mood (drifts toward a target set by needs, home, community, culture)
    let target = 70
    target -= Math.max(0, a.hunger - 50) * 0.5
    target -= Math.max(0, a.thirst - 50) * 0.5
    target -= Math.max(0, 40 - a.energy) * 0.4
    target -= Math.max(0, 50 - a.warmth) * 0.5
    target -= Math.max(0, 40 - a.social) * 0.4
    target -= (100 - a.health) * 0.3
    if (a.homeId) target += 6
    if (a.partnerId) target += 5
    if (this.weather === 'storm') target -= 4
    if (this.hasEvent('golden_age')) target += 8
    target += this.happinessBonus(a)
    target -= a.traits.neuroticism * 10 - 5
    a.mood = clamp(a.mood + (target - a.mood) * Math.min(1, dtH * 0.15), 0, 100)

    // --- children don't work; they stay near family and are fed by the tribe
    if (!adult) {
      this.updateChild(a, dtH, ageY)
      return
    }

    // --- survival interrupts (anyone, AI or not, will drop a task to drink when parched)
    if (!a.task || (a.task.action !== 'drink' && a.task.action !== 'eat' && a.task.action !== 'sleep' && a.task.text !== 'survival')) {
      let urgent: PlanStep | null = null
      if (a.thirst > 78) urgent = { action: 'drink', target: '', amount: 0, text: '' }
      else if (a.hunger > 78) urgent = { action: 'eat', target: '', amount: 0, text: '' }
      else if (a.energy < 8) urgent = { action: 'sleep', target: '', amount: 0, text: '' }
      else if (a.warmth < 30 && !this.warmSourceNear(a.x, a.y) && a.task?.action !== 'go_home' && a.task?.text !== 'warmth') {
        // freezing: light a fire on the spot if possible, otherwise head for shelter
        const t = this.tribeById(a.tribeId)
        const wood = invCount(a.inv, 'wood')
        const home = a.homeId ? this.buildingById.get(a.homeId) : undefined
        const farFromHome = !home || Math.hypot(home.x - a.x, home.y - a.y) > 12
        if (this.hasTech(a.tribeId, 'fire') && wood >= 5 && farFromHome && t) urgent = { action: 'build', target: 'campfire', amount: 0, text: 'warmth' }
        else urgent = { action: 'go_home', target: '', amount: 0, text: 'warmth' }
      }
      if (urgent) {
        if (a.task && a.task.fromPlan) a.plan.unshift(stripTask(a.task))
        a.task = null
        startTask(this, a, urgent, false)
      }
    }

    // --- execute
    if (a.task) {
      updateTask(this, a, dtH)
    } else if (a.plan.length) {
      const step = a.plan.shift()!
      startTask(this, a, step, true)
    } else {
      // No plan: the AI mind (if attached) will be asked; meanwhile, instinct.
      if (this.aiActive(a)) a.urgentThink = true
      if (!a.aiPending || !this.aiActive(a)) {
        const s = instinctStep(this, a)
        startTask(this, a, s, false)
        // a step that fails immediately shouldn't be retried every tick
        if (!a.task) startTask(this, a, { action: 'rest', target: '', amount: 0.5, text: '' }, false)
      } else {
        // waiting for a thought: do something small and useful nearby
        startTask(this, a, { action: 'rest', target: '', amount: 0, text: 'pondering' }, false)
        const tk = a.task as Task | null
        if (tk) tk.need = 0.4
      }
    }

    // speech bubble expiry handled in renderer (real-time)
  }

  agentFoodAccess(a: Agent): number {
    let f = 0
    for (const it of FOOD_ITEMS) f += invCount(a.inv, it)
    const t = this.tribeById(a.tribeId)
    if (t) for (const it of FOOD_ITEMS) f += invCount(t.storage, it)
    return f
  }

  private updateChild(a: Agent, dtH: number, ageY: number) {
    const t = this.tribeById(a.tribeId)
    // fed from tribe stores or parents
    if (a.hunger > 55) {
      const sources: Inventory[] = []
      if (t) sources.push(t.storage)
      for (const pid of a.parents) { const p = this.agentById.get(pid); if (p?.alive) sources.push(p.inv) }
      for (const src of sources) {
        const food = FOOD_ITEMS.find((f) => invCount(src, f) > 0)
        if (food) { invAdd(src, food, -1); a.hunger = Math.max(0, a.hunger - (ITEMS[food].food ?? 10) * 1.5); break }
      }
    }
    if (a.thirst > 60) {
      // carried by parents to water; abstract it
      a.thirst = Math.max(0, a.thirst - 40)
    }
    const night = isNight(this.time)
    if (night) {
      a.energy = clamp(a.energy + dtH * 12, 0, 100)
    }
    // follow a parent / play near home
    if (!a.task) {
      const parent = a.parents.map((p) => this.agentById.get(p)).find((p) => p?.alive)
      let tx: number, ty: number
      const home = a.homeId ? this.buildingById.get(a.homeId) : undefined
      if (night && home) { tx = home.x; ty = home.y }
      else if (parent && ageY > 3 && Math.hypot(parent.x - a.x, parent.y - a.y) > 6) { tx = Math.floor(parent.x); ty = Math.floor(parent.y) }
      else if (home) { tx = home.x + this.rng.int(-3, 3); ty = home.y + this.rng.int(-3, 3) }
      else { const h = this.tribeHub(a.tribeId); tx = h.x + this.rng.int(-3, 3); ty = h.y + this.rng.int(-3, 3) }
      tx = clamp(tx, 0, this.W - 1); ty = clamp(ty, 0, this.H - 1)
      if (!night && this.rng.chance(0.5) && Math.hypot(tx - a.x, ty - a.y) < 8) startTask(this, a, { action: 'rest', target: '', amount: 1, text: 'playing' }, false)
      else startTask(this, a, { action: 'wander', target: `${tx},${ty}`, amount: 0, text: '' }, false)
    } else updateTask(this, a, dtH)
  }

  private hbCache = new WeakMap<Agent, { t: number; v: number }>()
  happinessBonus(a: Agent): number {
    const c = this.hbCache.get(a)
    if (c && this.time - c.t < HOUR) return c.v
    const v = this.happinessBonusUncached(a)
    this.hbCache.set(a, { t: this.time, v })
    return v
  }
  private happinessBonusUncached(a: Agent): number {
    let h = 0
    for (const b of this.tribeBuildings(a.tribeId)) {
      const hp = BUILDINGS[b.type].happiness
      if (hp && Math.abs(b.x - a.x) < 25 && Math.abs(b.y - a.y) < 25) h += hp
    }
    return Math.min(h, 18)
  }

  private updateAnimal(an: Animal, dtH: number) {
    const dx = an.tx + 0.5 - an.x, dy = an.ty + 0.5 - an.y
    const d = Math.hypot(dx, dy)
    if (d < 0.2) {
      if (this.rng.chance(0.02)) {
        for (let k = 0; k < 6; k++) {
          const nx = clamp(Math.floor(an.x) + this.rng.int(-6, 6), 0, this.W - 1)
          const ny = clamp(Math.floor(an.y) + this.rng.int(-6, 6), 0, this.H - 1)
          const i = this.idx(nx, ny)
          if (this.walkableTile(i) && this.biome[i] !== B.RIVER && !this.occ[i]) { an.tx = nx; an.ty = ny; break }
        }
      }
      return
    }
    const sp = (an.kind === 'rabbit' ? 5 : 3.5) * dtH
    const m = Math.min(sp, d)
    an.x += (dx / d) * m
    an.y += (dy / d) * m
    an.dir = dx < 0 ? -1 : 1
  }

  // ---------------------------------------------------------------- periodic

  private hourly() {
    diplomacyHourly(this)
    // weather
    if (this.time >= this.weatherUntil) {
      const s = this.season
      const r = this.rng.next()
      const wet = this.preset.moisture + 0.5
      let w: Weather = 'clear'
      if (s === 3 && this.preset.latTop < 0.5 && r < 0.35) w = 'snow'
      else if (r < 0.18 * wet) w = 'rain'
      else if (r < 0.22 * wet) w = 'storm'
      else if (r < 0.34) w = 'cloudy'
      else if (r < 0.38 && hourOf(this.time) < 9) w = 'fog'
      if (this.hasEvent('drought') && (w === 'rain' || w === 'storm')) w = 'clear'
      this.weather = w
      this.weatherUntil = this.time + this.rng.int(3, 10) * HOUR
    }
    // farms grow in spring/summer (rain helps), pastures breed
    const s = this.season
    for (const b of this.buildings) {
      if (!b.done) continue
      if (b.type === 'farm' && b.planted && b.crop < 1) {
        const i = this.idx(b.x, b.y)
        const rate = s === 0 || s === 1 ? 1 : s === 2 ? 0.5 : 0
        const irrigated = this.hasTech(b.tribeId, 'irrigation')
        const wet = this.weather === 'rain' ? 1.3 : this.hasEvent('drought') ? (irrigated ? 0.85 : 0.4) : 1
        const know = (irrigated ? 1.3 : 1) * (this.hasTech(b.tribeId, 'calendar') ? 1.15 : 1)
        b.crop = Math.min(1, b.crop + (rate * wet * know * (0.4 + this.fertility[i])) / 70)
        if (s === 3 && b.crop < 1 && this.rng.chance(0.01)) { b.planted = false; b.crop = 0 }
      }
      if (b.type === 'pasture' && b.crop < 12 && s !== 3) b.crop = Math.min(12, b.crop + 0.04)
    }
    // regrowth: saplings become trees, bushes refill in the growing seasons
    if (this.regrow.length) {
      const keep: typeof this.regrow = []
      for (const r of this.regrow) {
        if (r.t <= this.time) {
          if ((r.f === F.BUSH || r.f === F.FIBER) && this.feature[r.i] === r.f) {
            this.amount[r.i] = featureAmount(r.f, hash2(r.i, this.time | 0))
            this.markTile(r.i)
          } else if (this.feature[r.i] === F.SAPLING || this.feature[r.i] === F.NONE) {
            if (!this.occ[r.i]) {
              this.feature[r.i] = r.f
              this.amount[r.i] = featureAmount(r.f, hash2(r.i, this.time | 0))
              this.markTile(r.i)
            }
          }
        } else keep.push(r)
      }
      this.regrow = keep
    }
    // animal population recovers slowly
    const target = Math.round((this.W * this.H) / 600)
    this.animals = this.animals.filter((a) => a.alive)
    if (this.animals.length < target && this.rng.chance(0.25)) this.spawnAnimals(1)
  }

  private daily() {
    this.growFish()
    diplomacyDaily(this)
    // fish farms put a steady harvest into the stores
    for (const b of this.buildings) {
      if (!b.done || b.type !== 'fish_farm') continue
      const t = this.tribeById(b.tribeId)
      if (t && this.storageUsed(t) < this.storageCap(t.id)) invAdd(t.storage, 'fish', 10)
    }
    const day = dayOf(this.time)
    this.events = this.events.filter((e) => e.end > this.time)
    for (const t of this.tribes) if (t.alive) this.assignHomes(t)
    this.lifeEvents()
    this.knowledgeDay()
    this.diseaseCheck()
    this.foodSpoilage()
    if (day % 1 === 0) this.sample()
    this.checkMilestones()
    // random world events, at most one major event at a time
    if (this.events.length === 0 && this.rng.chance(0.07)) this.randomEvent()
    // tribes left with no members dissolve
    for (const t of this.tribes) {
      if (t.alive && this.members(t.id).length === 0) {
        t.alive = false
        this.log('tribe', `The ${t.name} are no more. Their last member is gone.`, '🕯️', undefined, t.id)
      }
    }
  }

  private newSeason() {
    const s = SEASONS[this.season]
    if (this.season === 0) this.log('event', `Year ${Math.floor(this.time / YEAR) + 1} begins. Spring returns.`, '🌱')
    else if (this.season === 3) this.log('event', `Winter descends. Fields lie fallow and the nights grow cold.`, '❄️')
    else if (this.season === 2) this.log('event', `${s} arrives — harvest time.`, '🍂')
    // re-render terrain for seasonal colours
    for (let cy = 0; cy < Math.ceil(this.H / 32); cy++) for (let cx = 0; cx < Math.ceil(this.W / 32); cx++) this.dirtyChunks.add((cy << 8) | cx)
  }

  private foodSpoilage() {
    for (const t of this.tribes) {
      if (!t.alive) continue
      const hasGranary = this.tribeBuildings(t.id, 'granary').length > 0 || this.tribeBuildings(t.id, 'warehouse').length > 0
      const rate = hasGranary ? 0.02 : 0.06
      for (const f of ['berries', 'meat', 'fish', 'grain', 'bread'] as Item[]) {
        const n = invCount(t.storage, f)
        const r = f === 'grain' || f === 'bread' ? rate * 0.4 : rate
        if (n > 4) invAdd(t.storage, f, -Math.ceil(n * r))
      }
    }
  }

  assignHomes(t: Tribe) {
    const homes = this.buildings.filter((b) => b.tribeId === t.id && b.done && BUILDINGS[b.type].housing)
    const occupancy = new Map<number, number>()
    const mem = this.members(t.id)
    for (const a of mem) {
      if (a.homeId) {
        const h = this.buildingById.get(a.homeId)
        if (!h || !h.done || h.tribeId !== t.id) a.homeId = 0
        else occupancy.set(h.id, (occupancy.get(h.id) ?? 0) + 1)
      }
    }
    // partners and children live with their family
    for (const a of mem) {
      if (a.homeId) continue
      const fam = [a.partnerId, ...a.parents].map((id) => this.agentById.get(id)).find((p) => p?.alive && p.homeId && p.tribeId === t.id)
      if (fam) {
        const h = this.buildingById.get(fam.homeId)!
        if ((occupancy.get(h.id) ?? 0) < (BUILDINGS[h.type].housing ?? 0) + 1) {
          a.homeId = h.id
          occupancy.set(h.id, (occupancy.get(h.id) ?? 0) + 1)
          continue
        }
      }
      const free = homes.find((h) => (occupancy.get(h.id) ?? 0) < (BUILDINGS[h.type].housing ?? 0))
      if (free) {
        a.homeId = free.id
        occupancy.set(free.id, (occupancy.get(free.id) ?? 0) + 1)
      }
    }
  }

  private lifeEvents() {
    const living = this.living
    const pop = living.length
    for (const a of living) {
      const age = this.age(a)
      // coming of age
      if (age >= ADULT_AGE && age - 1 / 12 < ADULT_AGE) {
        a.urgentThink = true
        this.log('social', `${a.name} has come of age.`, '🎓', a.id, a.tribeId)
        this.remember(a, 'I came of age today. I am an adult now and must find my own path.', 7)
        if (!a.goal) a.goal = 'Find my place in the tribe.'
      }
      // old age
      const medicine = this.hasTech(a.tribeId, 'medicine') ? 8 : 0
      const anti = this.hasTech(a.tribeId, 'antibiotics') ? 8 : 0
      const frail = 52 + medicine + anti
      if (age > frail) {
        const p = Math.pow((age - frail) / 30, 2) / 12
        if (this.rng.chance(p)) { this.kill(a, 'old age'); continue }
      }
    }
    // births: partnered couples with a home and food
    if (pop < this.cfg.popCap) {
      for (const a of living) {
        if (a.sex !== 'female' || !a.partnerId || !a.alive) continue
        const p = this.agentById.get(a.partnerId)
        if (!p || !p.alive) continue
        const age = this.age(a)
        if (age < 16 || age > 44) continue
        const lastChild = a.children.map((c) => this.agentById.get(c)).filter(Boolean).reduce((m, c) => Math.max(m, c!.birth), -1e12)
        if (this.time - lastChild < YEAR * 1.2) continue
        const t = this.tribeById(a.tribeId)
        const fed = a.hunger < 70 && (t ? this.agentFoodAccess(a) > 3 : true)
        const chance = 0.06 * (a.homeId ? 1.4 : 0.6) * (fed ? 1 : 0.3) * (a.mood > 50 ? 1.2 : 0.7) * (1 - pop / this.cfg.popCap * 0.6)
        if (this.rng.chance(chance)) this.birth(a, p)
      }
    }
  }

  birth(mother: Agent, father: Agent) {
    const sex = this.rng.chance(0.5) ? 'female' : 'male'
    const gen = Math.max(mother.generation, father.generation) + 1
    const child = this.newAgent(sex, 0, mother.x, mother.y, mother.tribeId, mother.tribeId, [mother, father], gen)
    child.homeId = mother.homeId
    child.urgentThink = false
    mother.children.push(child.id)
    father.children.push(child.id)
    mother.stats.kids++
    father.stats.kids++
    for (const p of [mother, father]) {
      p.relations[child.id] = { affinity: 80, familiarity: 100, lastTalk: this.time }
      child.relations[p.id] = { affinity: 80, familiarity: 100, lastTalk: this.time }
      this.remember(p, `Our child ${child.name} was born.`, 9)
      this.emote(p, '👶', 7000)
    }
    this.log('birth', `${child.name} was born to ${mother.name} and ${father.name}. (generation ${gen})`, '👶', child.id, mother.tribeId)
  }

  kill(a: Agent, cause: string) {
    if (!a.alive) return
    a.alive = false
    a.deathT = this.time
    a.deathCause = cause
    a.task = null
    a.plan = []
    const t = this.tribeById(a.tribeId)
    // inheritance
    if (t) for (const k in a.inv) invAdd(t.storage, k as Item, a.inv[k as Item] ?? 0)
    a.inv = {}
    const age = Math.floor(this.age(a))
    this.log('death', `${a.name} died of ${cause} at age ${age}.`, '🕯️', a.id, a.tribeId)
    for (const o of this.agents) {
      if (!o.alive) continue
      const r = o.relations[a.id]
      if (r && r.affinity > 30) {
        this.remember(o, `${a.name} died of ${cause}. I grieve for them.`, Math.min(10, 5 + Math.round(r.affinity / 25)))
        this.emote(o, '😢', 8000)
        this.practice(o, 'grief', 2)
        o.mood = Math.max(0, o.mood - r.affinity / 5)
        o.urgentThink = true
      }
      if (o.partnerId === a.id) o.partnerId = 0
    }
    if (t && t.leaderId === a.id) this.chooseLeader(t)
  }

  chooseLeader(t: Tribe) {
    const mem = this.members(t.id).filter((m) => this.isAdult(m))
    if (!mem.length) return
    let best = mem[0], bs = -1e9
    for (const m of mem) {
      let s = 0
      for (const o of mem) if (o !== m) s += o.relations[m.id]?.affinity ?? 0
      s += m.traits.extraversion * 20 + this.age(m) * 0.3
      if (s > bs) { bs = s; best = m }
    }
    t.leaderId = best.id
    this.log('tribe', `${best.name} now leads the ${t.name}.`, '👑', best.id, t.id)
    this.remember(best, `I have become the leader of the ${t.name}.`, 9)
    best.urgentThink = true
  }

  private diseaseCheck() {
    const plague = this.hasEvent('plague')
    for (const a of this.living) {
      if (a.sick > 0) {
        // contagion
        for (const o of this.living) {
          if (o !== a && o.sick === 0 && Math.abs(o.x - a.x) < 3 && Math.abs(o.y - a.y) < 3 && this.rng.chance(this.hasTech(o.tribeId, 'vaccination') ? 0.04 : 0.15)) {
            o.sick = this.rng.range(0.8, 2.5)
            this.remember(o, `I caught an illness from ${a.name}.`, 5)
          }
        }
        continue
      }
      const know = 1 / techFx(this.techs(a.tribeId), 'health')
      const p = (plague ? 0.08 : 0.006) * (this.season === 3 ? 1.6 : 1) * (a.health < 50 ? 2 : 1) * know
      if (this.rng.chance(p)) {
        a.sick = this.rng.range(0.8, plague ? 4 : 2.5)
        this.remember(a, 'I have fallen ill. I feel feverish and weak.', 6)
        a.urgentThink = true
      }
    }
  }

  private randomEvent() {
    const r = this.rng.next()
    const s = this.season
    const span = this.rng.int(2, 5) * DAY
    let e: WorldEvent | null = null
    if (r < 0.25 && s === 1) e = { kind: 'drought', start: this.time, end: this.time + span, label: 'Drought' }
    else if (r < 0.4 && s === 3) e = { kind: 'harsh_winter', start: this.time, end: this.time + span, label: 'Harsh winter' }
    else if (r < 0.52 && this.living.length > 12) e = { kind: 'plague', start: this.time, end: this.time + span, label: 'Plague' }
    else if (r < 0.72 && s !== 3) e = { kind: 'bounty', start: this.time, end: this.time + span, label: 'Season of plenty' }
    else if (r < 0.8 && this.buildings.length > 10) e = { kind: 'earthquake', start: this.time, end: this.time + HOUR, label: 'Earthquake' }
    else if (r < 0.9 && this.living.reduce((m, a) => m + a.mood, 0) / Math.max(1, this.living.length) > 68) e = { kind: 'golden_age', start: this.time, end: this.time + span * 2, label: 'Golden age' }
    if (!e) return
    this.events.push(e)
    const msg: Record<WorldEvent['kind'], [string, string]> = {
      drought: ['A drought grips the land. Rivers run low and berries wither.', '☀️'],
      harsh_winter: ['A brutally harsh winter sets in. Only the well-sheltered will stay warm.', '🥶'],
      plague: ['A plague spreads among the people. The sick must be tended.', '🦠'],
      bounty: ['A season of plenty: bushes heavy with fruit, game everywhere.', '🌾'],
      earthquake: ['The earth shakes violently! Buildings are damaged.', '🌋'],
      migration: ['Great herds migrate through the land.', '🦌'],
      golden_age: ['Contentment spreads — a golden age of culture begins.', '✨'],
    }
    this.log('event', msg[e.kind][0], msg[e.kind][1])
    if (e.kind === 'bounty') {
      for (let k = 0; k < 600; k++) {
        const i = this.rng.int(0, this.W * this.H - 1)
        if (this.feature[i] === F.BUSH) { this.amount[i] = 12; this.markTile(i) }
      }
      this.spawnAnimals(15)
    }
    if (e.kind === 'earthquake') {
      for (const b of this.buildings) {
        if (b.done && this.rng.chance(0.08) && b.type !== 'road') {
          b.hp -= 50
          if (b.hp <= 0) this.destroyBuilding(b, 'collapsed in the earthquake')
        }
      }
    }
    for (const a of this.living) this.remember(a, msg[e.kind][0], 6)
  }

  destroyBuilding(b: Building, why: string) {
    const def = BUILDINGS[b.type]
    for (let dy = 0; dy < def.size; dy++) for (let dx = 0; dx < def.size; dx++) {
      const i = this.idx(b.x + dx, b.y + dy)
      if (this.occ[i] === b.id + 1) this.occ[i] = 0
      this.markTile(i)
    }
    this.buildings = this.buildings.filter((x) => x !== b)
    this.buildingById.delete(b.id)
    this.buildVersion++
    for (const a of this.agents) if (a.homeId === b.id) a.homeId = 0
    const t = this.tribeById(b.tribeId)
    this.log('build', `The ${t?.name ?? ''} ${def.name.toLowerCase()} ${why}.`, '💥', undefined, b.tribeId)
    this.territoryDirty = true
  }

  private sample() {
    const living = this.living
    let food = 0
    for (const t of this.tribes) if (t.alive) for (const f of FOOD_ITEMS) food += invCount(t.storage, f)
    this.samples.push({
      t: this.time,
      pop: living.length,
      tribes: this.tribes.filter((t) => t.alive).length,
      techs: this.tribes.reduce((m, t) => Math.max(m, t.alive ? t.techs.length : 0), 0),
      buildings: this.buildings.filter((b) => b.done && b.type !== 'road').length,
      food,
      avgMood: living.length ? living.reduce((m, a) => m + a.mood, 0) / living.length : 0,
      era: this.era,
    })
    if (this.samples.length > 3000) this.samples.splice(0, 1000)
  }

  private checkMilestones() {
    const era = this.era
    const key = `era${era}`
    if (era > 0 && !this.milestones.includes(key)) {
      this.milestones.push(key)
      this.log('milestone', `The world enters the ${ERAS[era].name}.`, '🏛️')
    }
    const pop = this.living.length
    for (const n of [25, 50, 100, 200]) {
      const k = `pop${n}`
      if (pop >= n && !this.milestones.includes(k)) {
        this.milestones.push(k)
        this.log('milestone', `The world's population reaches ${n}.`, '👥')
      }
    }
  }

  // ---------------------------------------------------------------- knowledge

  learnTech(t: Tribe, techId: string, by?: Agent, how: 'research' | 'diffusion' | 'join' = 'research') {
    const set = this.techs(t.id)
    if (set.has(techId)) return
    set.add(techId)
    t.techs.push(techId)
    delete t.insight[techId]
    t.lost = t.lost.filter((x) => x !== techId)
    this.availCache.delete(t.id)
    const def = TECH_BY_ID[techId]
    if (t.research?.tech === techId) t.research = null
    if (by) this.emote(by, how === 'research' ? '💡' : '🤝', 6000)
    const icon = how === 'research' ? '💡' : '🤝'
    const who = by ? (how === 'research' ? ` thanks to ${by.name}` : ` from ${by.name}`) : ''
    this.log('tech', `The ${t.name} ${how === 'research' ? 'discovered' : 'learned'} ${def?.name ?? techId}${who}.`, icon, by?.id, t.id)
    for (const m of this.members(t.id)) {
      this.remember(m, `Our people now know ${def?.name ?? techId}: ${def?.desc ?? ''}`, by === m ? 8 : 5)
      m.urgentThink = true
    }
    // the world era may have changed
    this.checkMilestones()
  }

  /** Choose what to study: cheap ideas, ones already half-understood, and ones the land suggests. Never the same order twice. */
  pickResearch(t: Tribe): string | null {
    const av = this.available(t)
    if (!av.length) return null
    const l = this.landscape(t)
    const weights = av.map((d) => {
      const progress = this.techProgress(t, d.id) / d.cost
      return (geographyAffinity(d.id, l) * (1 + progress * 3) * (RESEARCH_PRIORITY[d.id] ?? 1)) / Math.sqrt(d.cost) * (0.6 + this.rng.next() * 0.8)
    })
    let best = 0
    for (let k = 1; k < av.length; k++) if (weights[k] > weights[best]) best = k
    return av[best].id
  }

  private availCache = new Map<number, TechDef[]>()
  available(t: Tribe): TechDef[] {
    let v = this.availCache.get(t.id)
    if (!v) { v = availableTechs(this.techs(t.id)); this.availCache.set(t.id, v) }
    return v
  }

  techProgress(t: Tribe, id: string): number {
    return (t.insight[id] ?? 0) + (t.research?.tech === id ? t.research.progress : 0)
  }

  /** Switch the tribe's deliberate research focus without losing what was learnt so far. */
  focusResearch(t: Tribe, id: string) {
    if (t.research?.tech === id) return
    if (t.research) t.insight[t.research.tech] = (t.insight[t.research.tech] ?? 0) + t.research.progress
    const carried = t.insight[id] ?? 0
    delete t.insight[id]
    t.research = { tech: id, progress: carried }
  }

  private landCache = new Map<number, { t: number; l: Landscape }>()
  landscape(t: Tribe): Landscape {
    const c = this.landCache.get(t.id)
    if (c && this.time - c.t < DAY) return c.l
    const ex = this.explored.get(t.id)
    const l: Landscape = { coast: 0, river: 0, forest: 0, fertile: 0, cold: 0, game: 0, copper: 0, tin: 0, iron: 0, coal: 0, gold: 0, oil: 0, sand: 0, clay: 0, bauxite: 0, uranium: 0, lithium: 0, hills: 0 }
    const kd = this.knownDeposits(t.id)
    let n = 0, cold = 0
    for (let i = 0; i < this.W * this.H; i++) {
      if (ex && !ex[i]) continue
      n++
      const b = this.biome[i]
      if (b === B.SHALLOWS) l.coast++
      else if (b === B.RIVER || b === B.LAKE) l.river++
      else if (b === B.FOREST || b === B.RAINFOREST || b === B.TAIGA) l.forest++
      if (this.fertility[i] > 0.5) l.fertile++
      if (b === B.BEACH || b === B.DESERT) l.sand++
      if (b === B.HILLS || b === B.MOUNTAIN) l.hills++
      if (this.temp[i] < 0.35) cold++
      const f = this.feature[i]
      if (f === F.COPPER) l.copper++
      else if (f === F.TIN) l.tin++
      else if (f === F.IRON) l.iron++
      else if (f === F.COAL) l.coal++
      else if (f === F.GOLD) l.gold++
      else if (f === F.OIL) l.oil++
      else if (f === F.CLAY) l.clay++
      if (kd[i]) {
        const d = this.deposit[i]
        if (d === D.COPPER) l.copper++
        else if (d === D.TIN) l.tin++
        else if (d === D.IRON) l.iron++
        else if (d === D.COAL) l.coal++
        else if (d === D.GOLD) l.gold++
        else if (d === D.OIL) l.oil++
        else if (d === D.BAUXITE) l.bauxite++
        else if (d === D.URANIUM) l.uranium++
        else if (d === D.LITHIUM) l.lithium++
      }
    }
    l.cold = n ? cold / n : 0
    l.game = this.animals.some((an) => an.alive && (!ex || ex[this.idx(Math.floor(an.x), Math.floor(an.y))])) ? 1 : 0
    this.landCache.set(t.id, { t: this.time, l })
    return l
  }

  /** Learning by doing: an hour of relevant work nudges a people toward an idea. */
  practice(a: Agent, key: string, hours: number) {
    const t = this.tribeById(a.tribeId)
    if (!t || hours <= 0) return
    const av = this.available(t)
    if (!av.length) return
    const l = this.landscape(t)
    const knack = 0.09 * (0.5 + a.traits.intelligence) * (0.7 + a.traits.openness * 0.6)
    for (const d of av) {
      if (!inspires(d.id, key)) continue
      // practical insight fades for advanced ideas: you can't stumble on electricity
      const tierDamp = d.era === 0 ? 1 : d.era === 1 ? 0.6 : d.era <= 3 ? 0.25 : 0.08
      t.insight[d.id] = (t.insight[d.id] ?? 0) + hours * knack * tierDamp * geographyAffinity(d.id, l)
      if (this.techProgress(t, d.id) >= d.cost) {
        this.learnTech(t, d.id, a, 'research')
        this.remember(a, `While I was at work, it came to me: ${d.name}. ${d.desc}`, 8)
        return
      }
    }
  }

  /** Daily: flashes of insight, and knowledge slipping away from small peoples without writing. */
  private knowledgeDay() {
    for (const t of this.tribes) {
      if (!t.alive) continue
      const mem = this.members(t.id).filter((m) => this.isAdult(m))
      if (!mem.length) continue
      // eureka: a half-understood idea suddenly clicks
      for (const d of this.available(t)) {
        const frac = this.techProgress(t, d.id) / d.cost
        if (frac < 0.35) continue
        const genius = mem.reduce((m, a) => Math.max(m, a.traits.intelligence + a.traits.openness * 0.5), 0)
        if (this.rng.chance(0.025 * frac * genius)) {
          const who = mem.reduce((b, a) => (a.traits.intelligence + this.rng.next() * 0.5 > b.traits.intelligence ? a : b), mem[0])
          t.insight[d.id] = (t.insight[d.id] ?? 0) + d.cost * 0.4
          this.log('tech', `${who.name} had a flash of insight about ${d.name.toLowerCase()}.`, '✨', who.id, t.id)
          this.emote(who, '✨', 6000)
          this.remember(who, `In a flash I understood something new about ${d.name}.`, 7)
          if (this.techProgress(t, d.id) >= d.cost) this.learnTech(t, d.id, who, 'research')
          break
        }
      }
      // oral tradition is fragile: a tiny people can forget (the "Tasmanian effect")
      if (!this.techs(t.id).has('writing') && mem.length <= 3 && this.rng.chance(0.03)) {
        const known = this.techs(t.id)
        const leaves = t.techs.filter((id) => id !== 'foraging' && !TECHS.some((o) => known.has(o.id) && o.req.includes(id)))
        if (leaves.length) {
          const lost = this.rng.pick(leaves)
          known.delete(lost)
          t.techs = t.techs.filter((x) => x !== lost)
          t.lost.push(lost)
          this.availCache.delete(t.id)
          this.log('tech', `With so few left to remember it, the ${t.name} lost the knowledge of ${TECH_BY_ID[lost]?.name.toLowerCase()}.`, '🌫️', undefined, t.id)
        }
      }
    }
  }

  // ---------------------------------------------------------------- placing buildings

  canPlace(type: BuildingType, x: number, y: number, tribeId?: number): boolean {
    const def = BUILDINGS[type]
    if (!this.inBounds(x, y)) return false
    // extraction sites go where the deposit is (and where this people can reach it)
    if (def.placement === 'deposit' || def.placement === 'offshore') {
      const i0 = this.idx(x, y)
      const want = type === 'mine' ? 'mine' : type
      const ok = tribeId !== undefined && this.extractable(tribeId, i0) === want
      // early mines can also be sunk beside surface outcrops in the hills
      const outcrops = type === 'mine' && (this.biome[i0] === B.HILLS || this.biome[i0] === B.MOUNTAIN) && this.nearestTile(x, y, 3, (j) => this.feature[j] >= F.COPPER && this.feature[j] <= F.GOLD) >= 0
      if (!ok && !outcrops) return false
    }
    for (let dy = 0; dy < def.size; dy++) for (let dx = 0; dx < def.size; dx++) {
      const xx = x + dx, yy = y + dy
      if (!this.inBounds(xx, yy)) return false
      const i = this.idx(xx, yy)
      if (this.occ[i]) return false
      const b = this.biome[i]
      const bd = BIOMES[b]
      if (def.placement === 'offshore') {
        if (b !== B.SHALLOWS && b !== B.OCEAN) return false
        continue
      }
      if (bd.water || b === B.PEAK) return false
      if (type === 'road') continue
      if (this.road[i]) return false
      const f = this.feature[i]
      if (f === F.ROCK || f === F.COPPER || f === F.TIN || f === F.IRON || f === F.COAL || f === F.GOLD || f === F.OIL) {
        if (type !== 'mine') return false
      }
      if (def.placement === 'fertile' && (this.fertility[i] < 0.2 || b === B.MOUNTAIN || b === B.DESERT)) return false
      if (def.placement === 'land' && b === B.MOUNTAIN && type !== 'watchtower') return false
    }
    if (def.placement === 'hills') {
      const i = this.idx(x, y)
      const b = this.biome[i]
      if (b !== B.HILLS && b !== B.MOUNTAIN) return false
    }
    if (def.placement === 'coast') {
      let coast = false
      for (let dy = -1; dy <= 1; dy++) for (let dx = -1; dx <= 1; dx++) {
        const xx = x + dx, yy = y + dy
        if (this.inBounds(xx, yy) && BIOMES[this.biome[this.idx(xx, yy)]].water && this.biome[this.idx(xx, yy)] !== B.RIVER) coast = true
      }
      if (!coast) return false
    }
    // keep a walkable gap around non-walkable buildings so towns don't wall themselves in
    if (!def.walkable) {
      for (let dy = -1; dy <= def.size; dy++) for (let dx = -1; dx <= def.size; dx++) {
        if (dx >= 0 && dy >= 0 && dx < def.size && dy < def.size) continue
        const xx = x + dx, yy = y + dy
        if (!this.inBounds(xx, yy)) continue
        const o = this.occ[this.idx(xx, yy)]
        if (o) {
          const ob = this.buildingById.get(o - 1)
          if (ob && !BUILDINGS[ob.type].walkable) return false
        }
      }
    }
    return true
  }

  /** Spiral out from the tribe centre to find a site for a new building. */
  findSite(a: Agent, type: BuildingType): { x: number; y: number } | null {
    const key = `${a.tribeId}|site|${type}`
    if (this.failed(key)) return null
    const r = this.findSiteUncached(a, type)
    if (!r) this.markFailed(key, 12)
    return r
  }

  private findSiteUncached(a: Agent, type: BuildingType): { x: number; y: number } | null {
    const def = BUILDINGS[type]
    const t = this.tribeById(a.tribeId)
    let cx = t ? t.center.x : Math.floor(a.x)
    let cy = t ? t.center.y : Math.floor(a.y)
    const ownB = this.tribeBuildings(a.tribeId, undefined, false)
    if (!ownB.length) { cx = Math.floor(a.x); cy = Math.floor(a.y) }
    let maxR = 26
    let minR = type === 'campfire' && !ownB.length ? 0 : 1
    if (def.placement === 'hills' || def.placement === 'coast') { maxR = 40; minR = 0 }
    if (def.placement === 'deposit' || def.placement === 'offshore') { maxR = 70; minR = 0 }
    if (type === 'farm' || type === 'pasture') minR = 3
    let best: { x: number; y: number } | null = null
    let bestScore = -1e9
    for (let r = minR; r <= maxR; r++) {
      for (let k = 0; k < 8 * Math.max(1, r); k++) {
        const ang = (k / (8 * Math.max(1, r))) * Math.PI * 2
        const x = Math.round(cx + Math.cos(ang) * r)
        const y = Math.round(cy + Math.sin(ang) * r)
        if (!this.canPlace(type, x, y, a.tribeId)) continue
        const i = this.idx(x, y)
        if (!this.isExplored(a.tribeId, i)) continue
        let score = -r
        if (type === 'farm') {
          score += this.fertility[i] * 12
          // farms like to cluster with other farms
          for (const fb of ownB) if (fb.type === 'farm' && Math.abs(fb.x - x) + Math.abs(fb.y - y) <= 1) score += 4
        }
        if (def.placement === 'deposit' || def.placement === 'offshore') {
          // richer, shallower bodies first — and not right next to an existing working
          score += Math.min(40, this.depAmt[i] / 25) - this.depDepth[i] * 2
          if (ownB.some((ob) => ob.type === type && Math.abs(ob.x - x) + Math.abs(ob.y - y) < 5)) score -= 30
        }
        if (type === 'mine') {
          let ore = 0
          for (let dy = -3; dy <= 3; dy++) for (let dx = -3; dx <= 3; dx++) {
            const xx = x + dx, yy = y + dy
            if (!this.inBounds(xx, yy)) continue
            const f = this.feature[this.idx(xx, yy)]
            if (f >= F.ROCK && f <= F.GOLD) ore++
          }
          score += ore * 2
        }
        if (TREE_FEATURES.has(this.feature[i])) score -= 2
        if (score > bestScore) { bestScore = score; best = { x, y } }
      }
      if (best && r > minR + 3 && type !== 'farm' && type !== 'mine' && def.placement !== 'deposit' && def.placement !== 'offshore') break
      if (best && r > minR + 8) break
    }
    return best
  }

  placeBuilding(type: BuildingType, x: number, y: number, tribeId: number, ownerId: number): Building {
    const def = BUILDINGS[type]
    const b: Building = {
      id: this.nextId++, type, x, y, tribeId, ownerId, progress: 0, done: false, hp: 100, crop: 0, planted: false, builtT: 0, name: '',
    }
    this.buildings.push(b)
    this.buildingById.set(b.id, b)
    this.buildVersion++
    for (let dy = 0; dy < def.size; dy++) for (let dx = 0; dx < def.size; dx++) {
      const i = this.idx(x + dx, y + dy)
      this.occ[i] = b.id + 1
      if (type !== 'farm' && type !== 'pasture' && type !== 'road') {
        if (this.feature[i] !== F.NONE && FEATURES[this.feature[i]].item === null) this.feature[i] = F.NONE
        if (TREE_FEATURES.has(this.feature[i]) || this.feature[i] === F.BUSH || this.feature[i] === F.FIBER) this.feature[i] = F.NONE
      } else if (type === 'farm' || type === 'pasture') {
        this.feature[i] = F.NONE
      }
      this.markTile(i)
    }
    this.territoryDirty = true
    return b
  }

  completeBuilding(b: Building, by: Agent) {
    b.done = true
    this.buildVersion++
    b.builtT = this.time
    b.hp = 100
    const def = BUILDINGS[b.type]
    const t = this.tribeById(b.tribeId)
    if (b.type === 'road') {
      const i = this.idx(b.x, b.y)
      this.road[i] = this.hasTech(b.tribeId, 'railways') ? 2 : 1
      this.occ[i] = 0
      this.buildings = this.buildings.filter((x) => x !== b)
      this.buildingById.delete(b.id)
      this.buildVersion++
      this.markTile(i)
      return
    }
    for (let dy = 0; dy < def.size; dy++) for (let dx = 0; dx < def.size; dx++) this.markTile(this.idx(b.x + dx, b.y + dy))
    this.territoryDirty = true
    if (!t) return
    const count = this.tribeBuildings(t.id, b.type).length
    if (count === 1 || b.type === 'monument' || b.type === 'launch_pad' || def.size === 2) {
      this.log('build', `${by.name} completed the ${t.name}'s ${count === 1 ? 'first ' : ''}${def.name.toLowerCase()}.`, '🏗️', by.id, t.id)
    }
    if (b.type === 'town_hall') {
      t.center = { x: b.x, y: b.y }
    }
    if (def.housing) this.assignHomes(t)
    if (b.type === 'campfire' && this.tribeBuildings(t.id).length === 1) {
      t.center = { x: b.x, y: b.y }
      this.log('tribe', `The ${t.name} settle by their first campfire. A home is born.`, '🔥', by.id, t.id)
    }
    if (b.type === 'launch_pad' && !t.launched) {
      t.launched = true
      this.log('milestone', `🚀 The ${t.name} launch a rocket from their pad! Their people have reached the stars.`, '🚀', by.id, t.id)
      for (const a of this.living) this.remember(a, `The ${t.name} launched a rocket into space. The sky is no longer the limit.`, 10)
    }
  }

  /** Pay a cost from an agent's pack first, then the tribe's stores. */
  payCost(a: Agent, cost: Inventory): boolean {
    const t = this.tribeById(a.tribeId)
    for (const k in cost) {
      const need = cost[k as Item] ?? 0
      const have = invCount(a.inv, k as Item) + (t ? invCount(t.storage, k as Item) : 0)
      if (have < need) return false
    }
    for (const k in cost) {
      let need = cost[k as Item] ?? 0
      const fromInv = Math.min(need, invCount(a.inv, k as Item))
      invAdd(a.inv, k as Item, -fromInv)
      need -= fromInv
      if (need > 0 && t) invAdd(t.storage, k as Item, -need)
    }
    return true
  }
  missing(a: Agent, cost: Inventory): string[] {
    const t = this.tribeById(a.tribeId)
    const out: string[] = []
    for (const k in cost) {
      const need = cost[k as Item] ?? 0
      const have = invCount(a.inv, k as Item) + (t ? invCount(t.storage, k as Item) : 0)
      if (have < need) out.push(`${need - Math.floor(have)} ${k}`)
    }
    return out
  }

  // ---------------------------------------------------------------- tribes & society

  foundTribe(a: Agent, name: string): Tribe {
    const old = this.tribeById(a.tribeId)
    const t = this.newTribe(name || tribeName(this.rng), Math.floor(a.x), Math.floor(a.y), a.id, old ? [...old.techs] : ['foraging'])
    t.storage = {}
    // copy exploration knowledge
    if (old) {
      const src = this.explored.get(old.id), dst = this.explored.get(t.id)
      if (src && dst) dst.set(src)
    }
    a.tribeId = t.id
    a.homeId = 0
    t.leaderId = a.id
    t.founderId = a.id
    if (old) {
      old.relations[t.id] = -10
      t.relations[old.id] = -10
      if (old.leaderId === a.id) this.chooseLeader(old)
    }
    this.log('tribe', `${a.name} broke away${old ? ` from the ${old.name}` : ''} and founded the ${t.name}.`, '🚩', a.id, t.id)
    // dependants follow
    const followers = [a.partnerId, ...a.children].map((id) => this.agentById.get(id)).filter((x) => x && x.alive) as Agent[]
    for (const f of followers) {
      if (f.id !== a.partnerId && this.isAdult(f)) continue
      f.tribeId = t.id
      f.homeId = 0
      this.remember(f, `I followed ${a.name} to found the ${t.name}.`, 7)
    }
    return t
  }

  joinTribe(a: Agent, t: Tribe) {
    const old = this.tribeById(a.tribeId)
    // knowledge travels with people
    if (old) for (const tech of old.techs) if (!this.techs(t.id).has(tech)) this.learnTech(t, tech, a, 'join')
    a.tribeId = t.id
    a.homeId = 0
    if (old && old.leaderId === a.id) this.chooseLeader(old)
    this.log('tribe', `${a.name} joined the ${t.name}.`, '🤝', a.id, t.id)
    const kids = a.children.map((id) => this.agentById.get(id)).filter((c) => c?.alive && !this.isAdult(c)) as Agent[]
    for (const c of kids) { c.tribeId = t.id; c.homeId = 0 }
    this.assignHomes(t)
  }

  tribeRelation(a: number, b: number): number {
    return this.tribeById(a)?.relations[b] ?? 0
  }
  shiftTribeRelation(a: number, b: number, d: number) {
    if (a === b) return
    const ta = this.tribeById(a), tb = this.tribeById(b)
    if (ta) ta.relations[b] = clamp((ta.relations[b] ?? 0) + d, -100, 100)
    if (tb) tb.relations[a] = clamp((tb.relations[a] ?? 0) + d, -100, 100)
  }

  say(a: Agent, text: string, to: Agent | null) {
    const clean = text.trim().slice(0, 240)
    if (!clean) return
    a.speech = { text: clean, to: to ? to.id : null, until: (typeof performance !== 'undefined' ? performance.now() : Date.now()) + 6000 + clean.length * 40, t: this.time }
    a.stats.talks++
    a.social = clamp(a.social + 15, 0, 100)
    const listeners = to ? [to] : this.living.filter((o) => o !== a && Math.hypot(o.x - a.x, o.y - a.y) < 6 && this.isAdult(o))
    for (const l of listeners) {
      l.heard.push({ t: this.time, fromId: a.id, from: a.name, text: clean })
      if (l.heard.length > 12) l.heard.shift()
      l.social = clamp(l.social + 10, 0, 100)
      this.bump(l, a, (a.traits.agreeableness - 0.4) * 4 + 1, 3)
      this.bump(a, l, 1, 3)
      if (to) l.urgentThink = true
      if (l.tribeId !== a.tribeId) this.shiftTribeRelation(l.tribeId, a.tribeId, 0.6)
    }
    this.log('speech', `${a.name}${to ? ` → ${to.name}` : ''}: “${clean}”`, '💬', a.id, a.tribeId)
  }

  cancelAll(a: Agent) {
    cancelTask(this, a)
    a.plan = []
  }

  resourceSummary(tribeId: number): Inventory {
    const t = this.tribeById(tribeId)
    return t ? t.storage : {}
  }
}

function stripTask(t: Task): PlanStep {
  return { action: t.action, target: t.target, amount: t.amount, text: t.text }
}
