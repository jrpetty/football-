// The map, learned from watching. Each look says where the player is (a
// named area — a callout — and its height); deaths, kills and killcams say
// where fights were lost and won and where the killer shot from; enemy pings
// say where the other team tends to be. From that the coach builds a model of
// each map: areas with time spent, kills and deaths and the height layers
// seen; routes (the moves between areas that were actually made); held angles
// (a killer's spot → where the player died); and, per weapon in hand, how
// fights went at each range.
//
// The pack's map reference (callout names, rough 3×3 positions, links,
// power positions) names and seeds it; what the screen showed fills it in.
// Nothing is guessed: an area is only counted when a look named it, and a
// death is only placed when the player's own view placed them shortly before.

import type { Elevation, GameMap, MapCallout, MapGridCell, PositioningSpec } from '../profile/schema.ts'
import type { GameEvent, VisibleState } from '../types.ts'
import { parseList } from './learning.ts'

export interface AreaStat {
  visits: number
  /** Seconds spent there (between looks, capped). */
  seconds: number
  deaths: number
  kills: number
  /** Height layers the player was seen at there. */
  elevations: Partial<Record<Elevation, number>>
  /** Times enemies were seen there. */
  enemies: number
}

export interface WeaponRangeStat {
  kills: number
  deaths: number
}

/** One match on one map. */
export interface MapMatchRecord {
  readonly map: string
  readonly areas: Readonly<Record<string, AreaStat>>
  /** "From→To": moves between areas. */
  readonly routes: Readonly<Record<string, number>>
  /** "Killer spot→Where you died". */
  readonly sightlines: Readonly<Record<string, number>>
  /** Killers' weapons, as named on the death screen. */
  readonly killerWeapons: Readonly<Record<string, number>>
  /** Weapon in hand → range → kills and deaths. */
  readonly weapons: Readonly<Record<string, Readonly<Record<string, WeaponRangeStat>>>>
  /** What killed the player (weapon class) at each range: range → class → deaths. */
  readonly deathsBy: Readonly<Record<string, Readonly<Record<string, number>>>>
  /** Deaths soon after the previous one (just after respawning), by where they happened ("?" when not placed). */
  readonly spawnDeaths?: Readonly<Record<string, number>>
  /** Seconds between deaths (each life after the first death), at most 60 kept. */
  readonly lives?: readonly number[]
}

/** Where the player is right now. */
export interface MapPosition {
  readonly map: string | null
  readonly area: string | null
  readonly elevation: Elevation | null
  /** Ms since the area was read. */
  readonly ageMs: number
}

const MAX_AREAS = 80
/** A gap longer than this between two looks is not counted as time in an area or a route. */
const MAX_GAP_MS = 15_000
/** A death is placed where the player was if their own view placed them this recently. */
const PLACE_DEATH_MS = 12_000
/** A death this soon after the previous one came right after respawning. */
const SPAWN_DEATH_MS = 15_000
const ELEVATION_SET = new Set<string>(['underground', 'ground', 'upper', 'roof'])

const sep = '→'

/** Matches a callout read from the screen to the map's own name (exact, alias, or the same words). */
export function calloutMatcher(map: GameMap | undefined): (raw: string) => string {
  const index = new Map<string, string>()
  const key = (s: string) => s.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim()
  for (const c of map?.callouts ?? []) for (const n of [c.name, ...(c.aliases ?? [])]) index.set(key(n), c.name)
  return (raw) => {
    const k = key(raw)
    const hit = index.get(k) ?? index.get(k.replace(/^the /, '')) ?? [...index].find(([n]) => n.length > 3 && (k.startsWith(`${n} `) || k.endsWith(` ${n}`)))?.[1]
    if (hit) return hit
    // A name the reference does not have: learned as read, tidied.
    const t = raw.replace(/\s+/g, ' ').trim().slice(0, 40)
    return t ? t[0].toUpperCase() + t.slice(1) : t
  }
}

export function findMap(maps: readonly GameMap[] | undefined, raw: string | null | undefined): GameMap | undefined {
  if (!raw) return undefined
  const k = raw.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim()
  return (maps ?? []).find((m) => [m.name, m.id, ...(m.aliases ?? [])].some((n) => n.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim() === k))
}

/** Follows the player around one match. */
export class MapTracker {
  private readonly spec: PositioningSpec
  private readonly maps: readonly GameMap[]
  private mapName: string | null = null
  private match: (raw: string) => string = calloutMatcher(undefined)
  private areas = new Map<string, AreaStat>()
  private routes = new Map<string, number>()
  private sightlines = new Map<string, number>()
  private killerWeapons = new Map<string, number>()
  private weapons = new Map<string, Map<string, WeaponRangeStat>>()
  private deathsBy = new Map<string, Map<string, number>>()
  private here: { area: string; at: number; elevation: Elevation | null } | null = null
  private lastRead = new Map<string, number>()
  /** Deaths this match from each killer spot to each area (for live tips). */
  private matchSightlines = new Map<string, number>()
  private spawnDeaths = new Map<string, number>()
  private lives: number[] = []
  private lastDeathAt: number | null = null

  constructor(spec: PositioningSpec, maps: readonly GameMap[] | undefined) {
    this.spec = spec
    this.maps = maps ?? []
  }

  get empty(): boolean {
    return this.areas.size === 0 && this.weapons.size === 0
  }

  position(now: number): MapPosition {
    return { map: this.mapName, area: this.here?.area ?? null, elevation: this.here?.elevation ?? null, ageMs: this.here ? now - this.here.at : Infinity }
  }

  /** The reference entry for the current map, if the pack has one. */
  reference(): GameMap | undefined {
    return findMap(this.maps, this.mapName)
  }

  observe(visible: VisibleState): void {
    if (visible.stale) return
    const map = this.fresh(visible, this.spec.mapKey)
    if (map && map !== this.mapName) {
      this.mapName = map
      this.match = calloutMatcher(findMap(this.maps, map))
    }
    const enemies = this.spec.enemyPositionsKey ? this.fresh(visible, this.spec.enemyPositionsKey) : null
    if (enemies) for (const [raw, n] of parseList(enemies)) this.area(this.match(raw)).enemies += n
    const loc = this.fresh(visible, this.spec.locationKey)
    if (!loc) return
    const at = visible.values[this.spec.locationKey]!.observedAt
    const area = this.match(loc)
    if (!area) return
    const elevRaw = this.spec.elevationKey ? visible.values[this.spec.elevationKey] : undefined
    const elevation = elevRaw && typeof elevRaw.value === 'string' && ELEVATION_SET.has(elevRaw.value) && Math.abs(elevRaw.observedAt - at) < 4000 ? (elevRaw.value as Elevation) : null
    const prev = this.here
    const stat = this.area(area)
    if (prev && at - prev.at <= MAX_GAP_MS) {
      this.area(prev.area).seconds += (at - prev.at) / 1000
      if (prev.area !== area) this.routes.set(`${prev.area}${sep}${area}`, (this.routes.get(`${prev.area}${sep}${area}`) ?? 0) + 1)
    }
    if (!prev || prev.area !== area || at - prev.at > MAX_GAP_MS) stat.visits++
    if (elevation) stat.elevations[elevation] = (stat.elevations[elevation] ?? 0) + 1
    this.here = { area, at, elevation }
  }

  /** A death or kill: where it happened, the killer's spot and weapon, the weapon in hand and the range. Returns the death's placement. */
  event(ev: GameEvent, visible: VisibleState): { area: string | null; killerSpot: string | null; repeat: number } | null {
    const death = ev.ruleId === this.spec.deathEvent
    const kill = !!this.spec.killEvent && ev.ruleId === this.spec.killEvent
    if (!death && !kill) return null
    const placed = this.here && ev.at - this.here.at <= PLACE_DEATH_MS && ev.at >= this.here.at - 2000 ? this.here.area : null
    const range = this.spec.rangeAttribute ? ev.attributes?.[this.spec.rangeAttribute] : undefined
    const weapon = this.spec.weaponKey ? this.recent(visible, this.spec.weaponKey, ev.at, 30_000) : null
    if (weapon && typeof range === 'string' && range !== 'unknown') {
      let w = this.weapons.get(weapon)
      if (!w) this.weapons.set(weapon, (w = new Map()))
      const r = w.get(range) ?? { kills: 0, deaths: 0 }
      if (death) r.deaths++
      else r.kills++
      w.set(range, r)
    }
    if (kill) {
      if (placed) this.area(placed).kills++
      return null
    }
    if (placed) this.area(placed).deaths++
    if (this.lastDeathAt !== null) {
      const life = ev.at - this.lastDeathAt
      if (life > 0 && this.lives.length < 60) this.lives.push(Math.round(life / 1000))
      if (life > 0 && life <= SPAWN_DEATH_MS) this.spawnDeaths.set(placed ?? '?', (this.spawnDeaths.get(placed ?? '?') ?? 0) + 1)
    }
    this.lastDeathAt = ev.at
    const by = this.spec.killedByAttribute ? ev.attributes?.[this.spec.killedByAttribute] : undefined
    if (typeof range === 'string' && range !== 'unknown' && typeof by === 'string' && by !== 'unknown') {
      let m = this.deathsBy.get(range)
      if (!m) this.deathsBy.set(range, (m = new Map()))
      m.set(by, (m.get(by) ?? 0) + 1)
    }
    const killerWeapon = this.spec.killerWeaponKey ? this.recent(visible, this.spec.killerWeaponKey, ev.at, 8000) : null
    if (killerWeapon) this.killerWeapons.set(killerWeapon, (this.killerWeapons.get(killerWeapon) ?? 0) + 1)
    const spotRaw = this.spec.killerSpotKey ? this.recent(visible, this.spec.killerSpotKey, ev.at, 8000) : null
    const killerSpot = spotRaw ? this.match(spotRaw) : null
    let repeat = 0
    if (killerSpot && placed) {
      const k = `${killerSpot}${sep}${placed}`
      this.sightlines.set(k, (this.sightlines.get(k) ?? 0) + 1)
      repeat = (this.matchSightlines.get(k) ?? 0) + 1
      this.matchSightlines.set(k, repeat)
    }
    if (killerSpot) this.area(killerSpot).enemies++
    return { area: placed, killerSpot, repeat }
  }

  finish(): MapMatchRecord | null {
    const out =
      this.mapName && !this.empty
        ? {
            map: this.mapName,
            areas: Object.fromEntries([...this.areas].map(([k, v]) => [k, { ...v, seconds: Math.round(v.seconds), elevations: { ...v.elevations } }])),
            routes: Object.fromEntries(this.routes),
            sightlines: Object.fromEntries(this.sightlines),
            killerWeapons: Object.fromEntries(this.killerWeapons),
            weapons: Object.fromEntries([...this.weapons].map(([w, m]) => [w, Object.fromEntries([...m].map(([r, s]) => [r, { ...s }]))])),
            deathsBy: Object.fromEntries([...this.deathsBy].map(([r, m]) => [r, Object.fromEntries(m)])),
            ...(this.spawnDeaths.size ? { spawnDeaths: Object.fromEntries(this.spawnDeaths) } : {}),
            ...(this.lives.length ? { lives: [...this.lives] } : {}),
          }
        : null
    this.newMatch()
    return out
  }

  newMatch(): void {
    this.areas = new Map()
    this.routes = new Map()
    this.sightlines = new Map()
    this.killerWeapons = new Map()
    this.weapons = new Map()
    this.deathsBy = new Map()
    this.matchSightlines = new Map()
    this.spawnDeaths = new Map()
    this.lives = []
    this.lastDeathAt = null
    this.here = null
  }

  /** The current match so far, as a record (for the live view). */
  snapshot(): MapMatchRecord | null {
    if (!this.mapName || this.empty) return null
    return {
      spawnDeaths: Object.fromEntries(this.spawnDeaths),
      lives: [...this.lives],
      map: this.mapName,
      areas: Object.fromEntries([...this.areas].map(([k, v]) => [k, { ...v, elevations: { ...v.elevations } }])),
      routes: Object.fromEntries(this.routes),
      sightlines: Object.fromEntries(this.sightlines),
      killerWeapons: Object.fromEntries(this.killerWeapons),
      weapons: Object.fromEntries([...this.weapons].map(([w, m]) => [w, Object.fromEntries([...m].map(([r, s]) => [r, { ...s }]))])),
      deathsBy: Object.fromEntries([...this.deathsBy].map(([r, m]) => [r, Object.fromEntries(m)])),
    }
  }

  private area(name: string): AreaStat {
    let a = this.areas.get(name)
    if (!a) {
      a = { visits: 0, seconds: 0, deaths: 0, kills: 0, elevations: {}, enemies: 0 }
      if (this.areas.size < MAX_AREAS) this.areas.set(name, a)
    }
    return a
  }

  /** A string state value read since the last time it was used. */
  private fresh(visible: VisibleState, key: string): string | null {
    const v = visible.values[key]
    if (!v || v.conflicted || typeof v.value !== 'string' || !v.value.trim()) return null
    if ((this.lastRead.get(key) ?? -Infinity) >= v.observedAt) return null
    this.lastRead.set(key, v.observedAt)
    return v.value.trim()
  }

  /** A string state value read close to a moment (a killcam's killer spot, the weapon in hand). */
  private recent(visible: VisibleState, key: string, at: number, withinMs: number): string | null {
    const v = visible.values[key]
    if (!v || v.conflicted || typeof v.value !== 'string' || !v.value.trim()) return null
    return Math.abs(at - v.observedAt) <= withinMs ? v.value.trim().slice(0, 40) : null
  }
}

// --- Across matches ----------------------------------------------------------------------------

export interface MapArea {
  readonly name: string
  readonly visits: number
  readonly seconds: number
  readonly deaths: number
  readonly kills: number
  readonly enemies: number
  /** Height layers seen there (by the player) or listed by the reference. */
  readonly elevations: readonly Elevation[]
  /** The reference callout, when the pack names this area. */
  readonly ref: MapCallout | null
}

export interface MapModel {
  readonly map: string
  readonly reference: GameMap | null
  readonly matches: number
  readonly areas: readonly MapArea[]
  readonly routes: readonly { readonly from: string; readonly to: string; readonly n: number }[]
  /** Killer's spot → where the player died, most first. */
  readonly sightlines: readonly { readonly from: string; readonly to: string; readonly n: number }[]
  readonly killerWeapons: readonly { readonly name: string; readonly n: number }[]
  readonly weapons: readonly { readonly name: string; readonly byRange: Readonly<Record<string, WeaponRangeStat>>; readonly kills: number; readonly deaths: number }[]
  readonly deathsBy: Readonly<Record<string, Readonly<Record<string, number>>>>
  /** Deaths just after respawning, by where ("?" when not placed), most first. */
  readonly spawnDeaths: readonly { readonly area: string; readonly n: number }[]
  /** Median seconds between deaths, when there were a few. */
  readonly medianLifeSec: number | null
}

const ELEV_ORDER: readonly Elevation[] = ['underground', 'ground', 'upper', 'roof']

function addInto(target: Map<string, number>, src: Readonly<Record<string, number>>): void {
  for (const [k, v] of Object.entries(src)) target.set(k, (target.get(k) ?? 0) + v)
}

/** Everything learned about one map, from the given matches' records (plus the pack's reference for it). */
export function mapModelFrom(records: readonly MapMatchRecord[], maps: readonly GameMap[] | undefined, mapName: string): MapModel | null {
  const reference = findMap(maps, mapName) ?? null
  const key = (reference?.name ?? mapName).toLowerCase()
  const recs = records.filter((r) => (findMap(maps, r.map)?.name ?? r.map).toLowerCase() === key)
  if (!recs.length && !reference) return null
  const areas = new Map<string, { visits: number; seconds: number; deaths: number; kills: number; enemies: number; elev: Set<Elevation> }>()
  const routes = new Map<string, number>()
  const sightlines = new Map<string, number>()
  const killerWeapons = new Map<string, number>()
  const weapons = new Map<string, Map<string, WeaponRangeStat>>()
  const deathsBy = new Map<string, Map<string, number>>()
  const spawnDeaths = new Map<string, number>()
  const lives: number[] = []
  for (const r of recs) {
    addInto(spawnDeaths, r.spawnDeaths ?? {})
    lives.push(...(r.lives ?? []))
    for (const [name, a] of Object.entries(r.areas)) {
      const cur = areas.get(name) ?? { visits: 0, seconds: 0, deaths: 0, kills: 0, enemies: 0, elev: new Set<Elevation>() }
      cur.visits += a.visits
      cur.seconds += a.seconds
      cur.deaths += a.deaths
      cur.kills += a.kills
      cur.enemies += a.enemies
      for (const e of Object.keys(a.elevations)) cur.elev.add(e as Elevation)
      areas.set(name, cur)
    }
    addInto(routes, r.routes)
    addInto(sightlines, r.sightlines)
    addInto(killerWeapons, r.killerWeapons)
    for (const [w, byRange] of Object.entries(r.weapons)) {
      let m = weapons.get(w)
      if (!m) weapons.set(w, (m = new Map()))
      for (const [range, s] of Object.entries(byRange)) {
        const cur = m.get(range) ?? { kills: 0, deaths: 0 }
        m.set(range, { kills: cur.kills + s.kills, deaths: cur.deaths + s.deaths })
      }
    }
    for (const [range, by] of Object.entries(r.deathsBy)) {
      let m = deathsBy.get(range)
      if (!m) deathsBy.set(range, (m = new Map()))
      addInto(m, by)
    }
  }
  // Every reference callout is part of the map, seen or not.
  for (const c of reference?.callouts ?? []) if (!areas.has(c.name)) areas.set(c.name, { visits: 0, seconds: 0, deaths: 0, kills: 0, enemies: 0, elev: new Set() })
  const refOf = (name: string) => reference?.callouts.find((c) => c.name === name) ?? null
  const pairs = (m: Map<string, number>) =>
    [...m]
      .map(([k, n]) => {
        const [from, to] = k.split(sep)
        return { from, to, n }
      })
      .sort((a, b) => b.n - a.n)
  return {
    map: reference?.name ?? mapName,
    reference,
    matches: recs.length,
    areas: [...areas]
      .map(([name, a]) => {
        const ref = refOf(name)
        const elevations = ELEV_ORDER.filter((e) => a.elev.has(e) || ref?.elevation?.includes(e))
        return { name, visits: a.visits, seconds: Math.round(a.seconds), deaths: a.deaths, kills: a.kills, enemies: a.enemies, elevations, ref }
      })
      .sort((a, b) => b.seconds - a.seconds || b.visits - a.visits || a.name.localeCompare(b.name)),
    routes: pairs(routes),
    sightlines: pairs(sightlines),
    killerWeapons: [...killerWeapons].map(([name, n]) => ({ name, n })).sort((a, b) => b.n - a.n),
    weapons: [...weapons]
      .map(([name, m]) => {
        const byRange = Object.fromEntries(m)
        const all = [...m.values()]
        return { name, byRange, kills: all.reduce((s, x) => s + x.kills, 0), deaths: all.reduce((s, x) => s + x.deaths, 0) }
      })
      .sort((a, b) => b.kills + b.deaths - (a.kills + a.deaths)),
    deathsBy: Object.fromEntries([...deathsBy].map(([r, m]) => [r, Object.fromEntries(m)])),
    spawnDeaths: [...spawnDeaths].map(([area, n]) => ({ area, n })).sort((a, b) => b.n - a.n),
    medianLifeSec: lives.length >= 3 ? [...lives].sort((a, b) => a - b)[lives.length >> 1] : null,
  }
}

/** The maps the records cover, most played first. */
export function mapsPlayed(records: readonly MapMatchRecord[], maps: readonly GameMap[] | undefined): { name: string; matches: number }[] {
  const counts = new Map<string, number>()
  for (const r of records) {
    const name = findMap(maps, r.map)?.name ?? r.map
    counts.set(name, (counts.get(name) ?? 0) + 1)
  }
  return [...counts].map(([name, matches]) => ({ name, matches })).sort((a, b) => b.matches - a.matches)
}

function share(n: number, of: number): string {
  return `${n} of ${of}`
}

/**
 * What the model says, most useful first: where the player dies and from
 * where, held angles, where they do well, where enemies show up, their
 * routes, and the reference's power positions against where they spend time.
 */
export function mapLines(m: MapModel, max = 6): string[] {
  const out: string[] = []
  const deaths = m.areas.reduce((s, a) => s + a.deaths, 0)
  const worst = [...m.areas].filter((a) => a.deaths >= 2).sort((a, b) => b.deaths - a.deaths)[0]
  if (worst) {
    const from = m.sightlines.find((s) => s.to === worst.name)
    out.push(`Most deaths at ${worst.name} (${share(worst.deaths, deaths)})${from && from.n >= 2 ? `, ${from.n} of them shot from ${from.from}` : ''}.`)
  }
  const held = m.sightlines.find((s) => s.n >= 2 && s.to !== worst?.name)
  if (held) out.push(`${held.from} → ${held.to} killed you ${held.n} times: that angle is held. Check it first, smoke it, or cross somewhere else.`)
  const best = [...m.areas].filter((a) => a.kills >= 3 && a.kills > a.deaths).sort((a, b) => b.kills - b.deaths - (a.kills - a.deaths))[0]
  if (best) out.push(`Your best area: ${best.name} (${best.kills} kills, ${best.deaths} deaths).`)
  const hot = [...m.areas].filter((a) => a.enemies >= 3).sort((a, b) => b.enemies - a.enemies)[0]
  if (hot) out.push(`Enemies show up most at ${hot.name} (${hot.enemies} sightings).`)
  const time = m.areas.reduce((s, a) => s + a.seconds, 0)
  for (const pp of (m.reference?.powerPositions ?? []).slice(0, 2)) {
    const a = m.areas.find((x) => x.name === pp.callout)
    if (time > 120 && a) out.push(`Power position ${pp.callout}: ${pp.why}${/[.!?]$/.test(pp.why) ? '' : '.'} You spent ${Math.round((100 * a.seconds) / time)}% of your time there.`)
  }
  const quick = m.spawnDeaths.reduce((s, x) => s + x.n, 0)
  if (quick >= 3) {
    const at = m.spawnDeaths.find((x) => x.area !== '?' && x.n >= 2)
    out.push(
      `${quick} deaths came within 15 s of the one before${at ? `, mostly at ${at.area}` : ''}: they are holding your spawn's exits. Check the exits, wait for a teammate, or take another way out.`,
    )
  }
  const route = m.routes[0]
  if (route && route.n >= 3) out.push(`Your most used route: ${route.from} → ${route.to} (${route.n} times).`)
  if (m.medianLifeSec !== null) out.push(`Median time between deaths: ${m.medianLifeSec} s.`)
  return out.slice(0, max)
}

/** Ranges from closest to longest, as the packs name them. */
const RANGE_ORDER = ['close', 'mid', 'long']
const BAND_RANGES: Readonly<Record<string, readonly string[]>> = {
  close: ['close'],
  'close-mid': ['close', 'mid'],
  mid: ['mid'],
  'mid-long': ['mid', 'long'],
  long: ['long'],
}

/**
 * Does the weapon suit the fights the player takes with it? From kills and
 * deaths with it at each range, against the range it is built for.
 */
export function weaponFitLine(
  weapon: { readonly name: string; readonly byRange: Readonly<Record<string, WeaponRangeStat>> },
  bestRange: string | undefined,
  deathsBy: Readonly<Record<string, Readonly<Record<string, number>>>>,
  className?: (id: string) => string,
): string | null {
  if (!bestRange) return null
  const good = BAND_RANGES[bestRange] ?? []
  const off = RANGE_ORDER.filter((r) => !good.includes(r))
  const lostOff = off.map((r) => ({ r, s: weapon.byRange[r] ?? { kills: 0, deaths: 0 } })).filter((x) => x.s.deaths >= 3 && x.s.deaths > x.s.kills)
  if (!lostOff.length) return null
  const worst = lostOff.sort((a, b) => b.s.deaths - a.s.deaths)[0]
  const by = Object.entries(deathsBy[worst.r] ?? {}).sort((a, b) => b[1] - a[1])[0]
  const byText = by && by[1] >= 2 ? `, mostly to ${className ? className(by[0]) : by[0]}` : ''
  return `${weapon.name} is built for ${bestRange} range, but you lost ${worst.s.deaths} of ${worst.s.deaths + worst.s.kills} ${worst.r}-range fights with it${byText}. Play the lanes that suit it, or carry something for ${worst.r} range.`
}

// --- Layout: the map drawn from what was learned ----------------------------------------------------

const GRID_XY: Readonly<Record<MapGridCell, readonly [number, number]>> = {
  nw: [1 / 6, 1 / 6],
  n: [0.5, 1 / 6],
  ne: [5 / 6, 1 / 6],
  w: [1 / 6, 0.5],
  c: [0.5, 0.5],
  e: [5 / 6, 0.5],
  sw: [1 / 6, 5 / 6],
  s: [0.5, 5 / 6],
  se: [5 / 6, 5 / 6],
}

export interface LaidOutArea {
  readonly name: string
  readonly x: number
  readonly y: number
  /** Height layer index: -1 underground, 0 ground, 1 upper, 2 roof (the highest seen). */
  readonly z: number
}

/**
 * Positions for the areas: seeded from the reference's rough grid (north up),
 * then pulled together along links (the reference's and the routes actually
 * taken) and pushed apart, so the drawing reflects how the map connects.
 * Deterministic. Coordinates in [0, 1].
 */
export function layoutMap(m: MapModel, iterations = 220): LaidOutArea[] {
  const names = m.areas.map((a) => a.name)
  const n = names.length
  if (!n) return []
  const idx = new Map(names.map((x, i) => [x, i]))
  const seed: [number, number][] = []
  const perCell = new Map<string, number>()
  m.areas.forEach((area, i) => {
    const g = area.ref?.grid
    if (g) {
      const k = perCell.get(g) ?? 0
      perCell.set(g, k + 1)
      const [cx, cy] = GRID_XY[g]
      const a = k * 2.399963 // golden angle: spread areas sharing a cell
      const r = k ? 0.05 + 0.025 * Math.sqrt(k) : 0
      seed.push([cx + r * Math.cos(a), cy + r * Math.sin(a)])
    } else {
      const a = i * 2.399963
      const r = 0.12 + 0.3 * Math.sqrt((i + 1) / n)
      seed.push([0.5 + r * Math.cos(a), 0.5 + r * Math.sin(a)])
    }
  })
  const edges: [number, number, number][] = []
  const addEdge = (a: string, b: string, w: number) => {
    const i = idx.get(a)
    const j = idx.get(b)
    if (i !== undefined && j !== undefined && i !== j) edges.push([i, j, w])
  }
  for (const [a, b] of m.reference?.connections ?? []) addEdge(a, b, 1)
  for (const r of m.routes) addEdge(r.from, r.to, Math.min(3, 0.5 + r.n / 3))
  const pos = seed.map(([x, y]) => [x, y])
  const k = 0.75 / Math.sqrt(n)
  for (let it = 0; it < iterations; it++) {
    const t = 0.05 * (1 - it / iterations) + 0.002
    const disp = pos.map(() => [0, 0])
    for (let i = 0; i < n; i++) {
      for (let j = i + 1; j < n; j++) {
        let dx = pos[i][0] - pos[j][0]
        let dy = pos[i][1] - pos[j][1]
        let d = Math.hypot(dx, dy)
        if (d < 1e-4) {
          dx = 0.001 * (i - j)
          dy = 0.001
          d = Math.hypot(dx, dy)
        }
        const f = (k * k) / d
        disp[i][0] += (dx / d) * f
        disp[i][1] += (dy / d) * f
        disp[j][0] -= (dx / d) * f
        disp[j][1] -= (dy / d) * f
      }
    }
    for (const [i, j, w] of edges) {
      const dx = pos[i][0] - pos[j][0]
      const dy = pos[i][1] - pos[j][1]
      const d = Math.max(1e-4, Math.hypot(dx, dy))
      const f = ((d * d) / k) * w
      disp[i][0] -= (dx / d) * f
      disp[i][1] -= (dy / d) * f
      disp[j][0] += (dx / d) * f
      disp[j][1] += (dy / d) * f
    }
    for (let i = 0; i < n; i++) {
      // A pull back towards the reference position keeps north up and the map's shape.
      const anchor = m.areas[i].ref?.grid ? 0.6 : 0.08
      disp[i][0] += (seed[i][0] - pos[i][0]) * anchor
      disp[i][1] += (seed[i][1] - pos[i][1]) * anchor
      const d = Math.max(1e-6, Math.hypot(disp[i][0], disp[i][1]))
      const step = Math.min(d, t)
      pos[i][0] = Math.min(0.97, Math.max(0.03, pos[i][0] + (disp[i][0] / d) * step))
      pos[i][1] = Math.min(0.97, Math.max(0.03, pos[i][1] + (disp[i][1] / d) * step))
    }
  }
  const zOf = (e: readonly Elevation[]) => (e.includes('roof') ? 2 : e.includes('upper') ? 1 : e.includes('ground') || !e.length ? 0 : -1)
  return m.areas.map((a, i) => ({ name: a.name, x: pos[i][0], y: pos[i][1], z: zOf(a.elevations) }))
}
