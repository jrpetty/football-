// DayZ places: where a town is, how to get there, what is near it. From the
// pack's map data (src/profiles/dayz): named places from the DayZ Wiki, and the
// water pumps, medical buildings, fuel, police, military, crash sites and so on
// from Bohemia Interactive's own server files. Answered on this computer.
//
// Road signs on Chernarus and Sakhal are in Cyrillic, and a player often types
// what a sign looks like in Latin letters: "Typobo" is Турово (Turovo), since
// Т looks like T, у like y, р like p, в like B. Places are found by their
// English name, their Cyrillic name, its usual transliteration, that look-alike
// spelling, or a nickname (Cherno, Elektro, NWAF).

import chernarusPlaces from '../../profiles/dayz/places.chernarus.json' with { type: 'json' }
import livoniaPlaces from '../../profiles/dayz/places.livonia.json' with { type: 'json' }
import sakhalPlaces from '../../profiles/dayz/places.sakhal.json' with { type: 'json' }
import chernarusWorld from '../../profiles/dayz/world.chernarus.json' with { type: 'json' }
import livoniaWorld from '../../profiles/dayz/world.livonia.json' with { type: 'json' }
import sakhalWorld from '../../profiles/dayz/world.sakhal.json' with { type: 'json' }

export type DayzMapId = 'chernarus' | 'livonia' | 'sakhal'

export interface DayzPlace {
  readonly name: string
  readonly cyrillic?: string
  readonly kind: string
  readonly tier?: string
  readonly facilities: readonly string[]
  /** Metres from the south-west corner: x east, y north. */
  readonly x: number
  readonly y: number
}

type Pt = readonly number[]

export interface DayzWorld {
  readonly worldSize: number
  readonly water: readonly Pt[]
  readonly fuel: readonly Pt[]
  readonly medical: readonly (readonly [number, number, string])[]
  readonly police: readonly Pt[]
  readonly fire: readonly Pt[]
  readonly military: readonly Pt[]
  readonly prison: readonly Pt[]
  readonly lighthouses: readonly Pt[]
  readonly heliCrash: readonly Pt[]
  readonly policeCars: readonly Pt[]
  readonly convoys: readonly Pt[]
  readonly contaminated: readonly { readonly name: string; readonly x: number; readonly y: number; readonly r: number }[]
  readonly spawns: readonly Pt[]
  /** Place → lootable buildings within its radius, by loot type. */
  readonly near: Readonly<Record<string, Readonly<Record<string, number>>>>
}

export interface DayzMap {
  readonly id: DayzMapId
  readonly name: string
  readonly worldSize: number
  readonly signs: 'cyrillic' | 'latin'
  readonly places: readonly DayzPlace[]
  readonly world: DayzWorld
}

export const DAYZ_MAPS: readonly DayzMap[] = [
  { id: 'chernarus', name: 'Chernarus', worldSize: chernarusPlaces.worldSize, signs: 'cyrillic', places: chernarusPlaces.places, world: chernarusWorld as unknown as DayzWorld },
  { id: 'livonia', name: 'Livonia', worldSize: livoniaPlaces.worldSize, signs: 'latin', places: livoniaPlaces.places, world: livoniaWorld as unknown as DayzWorld },
  { id: 'sakhal', name: 'Sakhal', worldSize: sakhalPlaces.worldSize, signs: 'cyrillic', places: sakhalPlaces.places, world: sakhalWorld as unknown as DayzWorld },
]

export function dayzMap(name: string | null | undefined): DayzMap | null {
  const n = (name ?? '').toLowerCase()
  if (!n) return null
  return DAYZ_MAPS.find((m) => n.includes(m.id) || (m.id === 'livonia' && n.includes('enoch')) || (m.id === 'chernarus' && n.includes('chernarusplus'))) ?? null
}

// --- Names ---------------------------------------------------------------------------------

/** The usual Latin spelling of each Cyrillic letter. */
const TRANSLIT: Readonly<Record<string, string>> = {
  а: 'a', б: 'b', в: 'v', г: 'g', д: 'd', е: 'e', ё: 'e', ж: 'zh', з: 'z', и: 'i', й: 'y', к: 'k', л: 'l', м: 'm', н: 'n', о: 'o', п: 'p', р: 'r', с: 's', т: 't', у: 'u', ф: 'f',
  х: 'kh', ц: 'ts', ч: 'ch', ш: 'sh', щ: 'shch', ъ: '', ы: 'y', ь: '', э: 'e', ю: 'yu', я: 'ya',
}

/** The Latin letter each Cyrillic letter looks like on a sign (what a player types when they cannot read it). */
const LOOKS_LIKE: Readonly<Record<string, string>> = {
  а: 'a', б: '6', в: 'b', г: 'r', д: 'd', е: 'e', ё: 'e', ж: 'zh', з: '3', и: 'n', й: 'n', к: 'k', л: 'n', м: 'm', н: 'h', о: 'o', п: 'n', р: 'p', с: 'c', т: 't', у: 'y', ф: 'f',
  х: 'x', ц: 'u', ч: '4', ш: 'w', щ: 'w', ъ: 'b', ы: 'bl', ь: 'b', э: 'e', ю: 'io', я: 'r',
}

const fold = (s: string) =>
  s
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/ł/g, 'l')
    .replace(/[^a-z0-9а-яё]+/g, '')

export const transliterate = (cyr: string) => [...cyr.toLowerCase()].map((c) => TRANSLIT[c] ?? c).join('')
export const looksLike = (cyr: string) => [...cyr.toLowerCase()].map((c) => LOOKS_LIKE[c] ?? c).join('')

/** Names players use for places, with the place's name on the map. */
const NICKNAMES: readonly (readonly [string, DayzMapId, string])[] = [
  ['cherno', 'chernarus', 'Chernogorsk'],
  ['elektro', 'chernarus', 'Elektrozavodsk'],
  ['electro', 'chernarus', 'Elektrozavodsk'],
  ['nwaf', 'chernarus', 'Vybor Air Base'],
  ['northwest airfield', 'chernarus', 'Vybor Air Base'],
  ['north west airfield', 'chernarus', 'Vybor Air Base'],
  ['neaf', 'chernarus', 'Krasnostav Airfield'],
  ['zeleno', 'chernarus', 'Zelenogorsk'],
  ['svet', 'chernarus', 'Svetloyarsk'],
  ['novo', 'chernarus', 'Novodmitrovsk'],
  ['stary', 'chernarus', 'Stary Sobor'],
  ['krasno', 'chernarus', 'Krasnostav'],
  ['bere', 'chernarus', 'Berezino'],
  ['tisy', 'chernarus', 'Tisy Military Base'],
  ['saf', 'chernarus', 'Balota Airfield'],
  ['balota airstrip', 'chernarus', 'Balota Airfield'],
]

function editDistance(a: string, b: string): number {
  const d = Array.from({ length: a.length + 1 }, (_, i) => [i, ...new Array<number>(b.length).fill(0)])
  for (let j = 1; j <= b.length; j++) d[0][j] = j
  for (let i = 1; i <= a.length; i++) for (let j = 1; j <= b.length; j++) d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1))
  return d[a.length][b.length]
}

export interface PlaceMatch {
  readonly place: DayzPlace
  readonly map: DayzMap
  /** How the name was recognised. */
  readonly via: 'name' | 'sign' | 'transliteration' | 'sign look-alike' | 'nickname' | 'close spelling'
}

/** Every way a place can be named, folded for comparison. */
function spellings(p: DayzPlace): { s: string; via: PlaceMatch['via'] }[] {
  const out: { s: string; via: PlaceMatch['via'] }[] = [{ s: fold(p.name), via: 'name' }]
  // "Tisy Military Base" is also just "Tisy"; "Vybor Air Base" also "Vybor airbase".
  const short = p.name.replace(/\s+(Military Base|Air Base|Airfield|Village|Town)$/i, '')
  if (short !== p.name) out.push({ s: fold(short), via: 'name' })
  if (p.cyrillic) {
    out.push({ s: fold(p.cyrillic), via: 'sign' })
    out.push({ s: fold(transliterate(p.cyrillic)), via: 'transliteration' })
    out.push({ s: fold(looksLike(p.cyrillic)), via: 'sign look-alike' })
  }
  return out
}

/** A place named in `text` (the closest match), preferring `prefer`'s map; null when nothing is close enough. */
export function findPlace(text: string, prefer: DayzMapId | null = null): PlaceMatch | null {
  const q = fold(text)
  if (q.length < 3) return null
  const order = [...DAYZ_MAPS].sort((a, b) => Number(b.id === prefer) - Number(a.id === prefer))
  for (const [nick, mapId, name] of NICKNAMES) {
    if (q === fold(nick)) {
      const map = DAYZ_MAPS.find((m) => m.id === mapId)!
      const place = map.places.find((p) => p.name === name)
      if (place) return { place, map, via: 'nickname' }
    }
  }
  let best: (PlaceMatch & { cost: number }) | null = null
  for (const map of order) {
    for (const place of map.places) {
      for (const { s, via } of spellings(place)) {
        if (!s) continue
        const cost = s === q ? 0 : editDistance(s, q) / Math.max(s.length, q.length)
        // A preferred map's match wins ties.
        if (!best || cost < best.cost - 1e-9) best = { place, map, via: cost === 0 ? via : via === 'name' ? 'close spelling' : via, cost }
      }
    }
  }
  return best && best.cost <= 0.25 ? { place: best.place, map: best.map, via: best.via } : null
}

/** Places named in a sentence: the words (and pairs and triples of words) that match a place. */
export function placesIn(text: string, prefer: DayzMapId | null = null): PlaceMatch[] {
  const words = text.replace(/[?.!,]/g, ' ').split(/\s+/).filter(Boolean)
  const found: (PlaceMatch & { at: number })[] = []
  const used = new Set<number>()
  for (const n of [3, 2, 1]) {
    for (let i = 0; i + n <= words.length; i++) {
      if ([...Array(n).keys()].some((k) => used.has(i + k))) continue
      const phrase = words.slice(i, i + n).join(' ')
      if (STOP.has(phrase.toLowerCase())) continue
      const m = findPlace(phrase, prefer)
      if (m && !found.some((f) => f.place === m.place)) {
        found.push({ ...m, at: i })
        for (let k = 0; k < n; k++) used.add(i + k)
      }
    }
  }
  return found.sort((a, b) => a.at - b.at).map(({ at: _at, ...m }) => m)
}

const STOP = new Set(['where', 'where is', 'what', 'from', 'to', 'the', 'how', 'get', 'nearest', 'closest', 'near', 'there', 'is', 'in', 'at', 'map', 'town', 'city', 'village', 'water', 'pump', 'medical', 'hospital', 'fuel', 'police', 'military', 'base', 'and', 'far', 'dayz', 'chernarus', 'livonia', 'sakhal', 'sign', 'says'])

// --- Describing -----------------------------------------------------------------------------

const COMPASS = ['north', 'north-east', 'east', 'south-east', 'south', 'south-west', 'west', 'north-west']

/** Compass direction from a to b. */
export function bearing(a: { x: number; y: number }, b: { x: number; y: number }): string {
  const deg = (Math.atan2(b.x - a.x, b.y - a.y) * 180) / Math.PI
  return COMPASS[Math.round(((deg + 360) % 360) / 45) % 8]
}

export const distance = (a: { x: number; y: number }, b: { x: number; y: number }) => Math.hypot(a.x - b.x, a.y - b.y)
export const km = (m: number) => (m < 950 ? `${Math.max(50, Math.round(m / 50) * 50)} m` : `${(m / 1000).toFixed(1)} km`)

/** The part of the map a point is in ("far north-east", "south coast"…). */
export function region(map: DayzMap, x: number, y: number): string {
  const third = map.worldSize / 3
  const ns = y > 2 * third ? 'north' : y < third ? 'south' : ''
  const ew = x > 2 * third ? 'east' : x < third ? 'west' : ''
  const far = (x > map.worldSize * 0.8 || x < map.worldSize * 0.2) && (y > map.worldSize * 0.8 || y < map.worldSize * 0.2) ? 'far ' : ''
  const where = ns && ew ? `${far}${ns}-${ew}` : ns || ew ? `${ns || ew}` : 'centre'
  return where === 'centre' ? 'the centre' : `the ${where}`
}

/** The 100 m grid reference players share: eastings and northings counted from the north-west corner ("135 012"). */
export function gridRef(map: DayzMap, x: number, y: number): string {
  return `${String(Math.floor(x / 100)).padStart(3, '0')} ${String(Math.max(0, Math.floor((map.worldSize - y) / 100))).padStart(3, '0')}`
}

const SETTLEMENT = new Set(['city', 'town', 'village', 'airfield', 'military'])

/** The nearest other named settlements, closest first. */
export function neighbours(map: DayzMap, p: { x: number; y: number; name?: string }, n = 3): { place: DayzPlace; m: number; dir: string }[] {
  return map.places
    .filter((o) => o.name !== p.name && SETTLEMENT.has(o.kind))
    .map((o) => ({ place: o, m: distance(p, o), dir: bearing(p, o) }))
    .sort((a, b) => a.m - b.m)
    .slice(0, n)
}

export type FeatureKind = 'water' | 'medical' | 'fuel' | 'police' | 'fire' | 'military' | 'lighthouse' | 'heliCrash' | 'contaminated'

const FEATURE_NAME: Readonly<Record<FeatureKind, string>> = {
  water: 'water pump',
  medical: 'medical building',
  fuel: 'fuel station',
  police: 'police building',
  fire: 'fire station',
  military: 'military building',
  lighthouse: 'lighthouse',
  heliCrash: 'helicopter crash spot',
  contaminated: 'contaminated zone',
}

function featurePoints(map: DayzMap, kind: FeatureKind): { x: number; y: number; label?: string }[] {
  const w = map.world
  const pts = (list: readonly Pt[]) => list.map((p) => ({ x: p[0], y: p[1] }))
  switch (kind) {
    case 'water':
      return pts(w.water)
    case 'medical':
      return w.medical.map((p) => ({ x: p[0], y: p[1], label: p[2] }))
    case 'fuel':
      return pts(w.fuel)
    case 'police':
      return pts(w.police)
    case 'fire':
      return pts(w.fire)
    case 'military':
      return pts(w.military)
    case 'lighthouse':
      return pts(w.lighthouses)
    case 'heliCrash':
      return pts(w.heliCrash)
    case 'contaminated':
      return w.contaminated.filter((c) => c.name !== 'dynamic').map((c) => ({ x: c.x, y: c.y, label: c.name }))
  }
}

/** The nearest feature of a kind to a point, or null where the map has none. */
export function nearest(map: DayzMap, at: { x: number; y: number }, kind: FeatureKind): { m: number; dir: string; label?: string; nearPlace: string | null } | null {
  let best: { x: number; y: number; label?: string } | null = null
  let bestM = Infinity
  for (const p of featurePoints(map, kind)) {
    const m = distance(at, p)
    if (m < bestM) [best, bestM] = [p, m]
  }
  if (!best) return null
  const by = neighbours(map, best, 1)[0]
  return { m: bestM, dir: bearing(at, best), ...(best.label ? { label: best.label } : {}), nearPlace: by && by.m < 1500 ? by.place.name : null }
}

export function featureLine(map: DayzMap, at: { x: number; y: number }, kind: FeatureKind): string | null {
  const f = nearest(map, at, kind)
  if (!f) return null
  const what = kind === 'medical' && f.label === 'hospital' ? 'hospital' : FEATURE_NAME[kind]
  return `nearest ${what} ${km(f.m)} ${f.dir}${f.nearPlace && f.m > 400 ? ` (by ${f.nearPlace})` : ''}`
}

const KIND_NAME: Readonly<Record<string, string>> = { city: 'city', town: 'town', village: 'village', airfield: 'airfield', military: 'military base', hill: 'hill', island: 'island', water: 'stretch of water', ruin: 'ruin', local: 'place' }

/** What the game's own buildings say is in a place, most notable first. */
function contents(map: DayzMap, p: DayzPlace): string | null {
  const c = map.world.near[p.name]
  if (!c) return null
  const parts: string[] = []
  const add = (k: string, one: string, many: string) => {
    const n = c[k] ?? 0
    if (n) parts.push(`${n} ${n === 1 ? one : many}`)
  }
  add('Military', 'military building', 'military buildings')
  add('Police', 'police building', 'police buildings')
  add('Medic', 'medical building', 'medical buildings')
  add('Firefighter', 'fire station', 'fire station buildings')
  add('Prison', 'prison building', 'prison buildings')
  add('Hunting', 'hunting stand or cabin', 'hunting stands and cabins')
  add('Industrial', 'industrial building', 'industrial buildings')
  add('Town', 'town building', 'town buildings')
  add('Village', 'house', 'houses')
  add('Farm', 'farm building', 'farm buildings')
  return parts.length ? parts.slice(0, 6).join(', ') : null
}

/** Where a place is, what is around it, and what is in it. */
export function describePlace(m: PlaceMatch): string {
  const { place: p, map } = m
  const sign = p.cyrillic && map.signs === 'cyrillic' ? ` (the sign reads ${p.cyrillic})` : ''
  const lines = [`${p.name}${sign} is a ${KIND_NAME[p.kind] ?? 'place'} in ${region(map, p.x, p.y)} of ${map.name}, grid ${gridRef(map, p.x, p.y)} (about ${Math.round(p.x)} / ${Math.round(p.y)}).`]
  const nb = neighbours(map, p, 3)
  if (nb.length) lines.push(`Nearest: ${nb.map((n) => `${n.place.name} ${km(n.m)} ${n.dir}`).join(', ')}.`)
  const inside = contents(map, p)
  if (inside) lines.push(`In it: ${inside}${p.tier ? `; loot tier ${p.tier}` : ''}.`)
  else if (p.tier) lines.push(`Loot tier ${p.tier}.`)
  const near = (['water', 'medical', 'fuel'] as const).map((k) => featureLine(map, p, k)).filter(Boolean)
  if (near.length) lines.push(`${near.join('; ')}.`.replace(/^n/, 'N'))
  const gas = nearest(map, p, 'contaminated')
  if (gas && gas.m < 1500) lines.push(`A permanent contaminated zone is ${km(gas.m)} ${gas.dir}: gas mask and NBC suit.`)
  if (m.via === 'sign look-alike') lines.push(`On the sign it looks like "${looksLike(p.cyrillic!).replace(/^./, (c) => c.toUpperCase())}": Cyrillic р is r, у is u, в is v, н is n, с is s.`)
  return lines.join(' ')
}

/** "From A to B": distance, direction, and the settlements on the way. */
export function routeLine(a: PlaceMatch, b: PlaceMatch): string {
  if (a.map.id !== b.map.id) return `${a.place.name} is on ${a.map.name} and ${b.place.name} on ${b.map.name}.`
  const m = distance(a.place, b.place)
  const map = a.map
  // Settlements close to the straight line between them, in order.
  const dx = b.place.x - a.place.x
  const dy = b.place.y - a.place.y
  const len2 = dx * dx + dy * dy
  const onTheWay = map.places
    .filter((p) => p !== a.place && p !== b.place && (p.kind === 'city' || p.kind === 'town' || p.kind === 'village'))
    .map((p) => {
      const t = ((p.x - a.place.x) * dx + (p.y - a.place.y) * dy) / len2
      const off = Math.abs((p.x - a.place.x) * dy - (p.y - a.place.y) * dx) / Math.sqrt(len2)
      return { p, t, off }
    })
    .filter((x) => x.t > 0.1 && x.t < 0.9 && x.off < Math.max(600, m * 0.08))
    .sort((x, y) => x.t - y.t)
    .slice(0, 3)
  return `${b.place.name} is ${km(m)} ${bearing(a.place, b.place)} of ${a.place.name} in a straight line${onTheWay.length ? `, past ${onTheWay.map((x) => x.p.name).join(', ')}` : ''}.`
}
