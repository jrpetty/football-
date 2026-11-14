// Where things spawn on Chernarus, from Bohemia's own loot table (built by
// scripts/build-dayz-items.mjs): every item's building types, loot tiers and
// how many the server keeps on the map, and the loot tier of every spot. So
// "where do I find a canteen?" gets the kind of building, the tiers, and the
// nearest settlement with those buildings in the right tier.

import data from '../../profiles/dayz/items.chernarus.json' with { type: 'json' }
import { bearing, distance, DAYZ_MAPS, km, type DayzMap, type DayzPlace } from './places.ts'

export interface LootItem {
  readonly id: string
  readonly name: string
  readonly aka: readonly string[]
  readonly kind: string
  readonly usage: readonly string[]
  readonly tiers: readonly number[]
  readonly nominal: number
  readonly deloot?: boolean
}

export const CHERNARUS_ITEMS: readonly LootItem[] = data.items as LootItem[]
const CHERNARUS: DayzMap = DAYZ_MAPS.find((m) => m.id === 'chernarus')!

let tierGrid: Uint8Array | null = null
function grid(): Uint8Array {
  if (tierGrid) return tierGrid
  const raw = typeof atob === 'function' ? Uint8Array.from(atob(data.tiers.rle), (c) => c.charCodeAt(0)) : new Uint8Array(Buffer.from(data.tiers.rle, 'base64'))
  const g = new Uint8Array(data.tiers.size * data.tiers.size)
  let k = 0
  for (let i = 0; i + 1 < raw.length; i += 2) {
    g.fill(raw[i], k, k + raw[i + 1])
    k += raw[i + 1]
  }
  return (tierGrid = g)
}

/** The loot tier (1 coast … 4 north-west military, 0 none) at a spot on Chernarus. */
export function tierAt(x: number, y: number): number {
  const n = data.tiers.size
  const gx = Math.min(n - 1, Math.max(0, Math.floor((x / data.worldSize) * n)))
  const gy = Math.min(n - 1, Math.max(0, Math.floor((1 - y / data.worldSize) * n)))
  return grid()[gy * n + gx]
}

const USAGE_WORDS: Readonly<Record<string, string>> = {
  Military: 'military buildings',
  Police: 'police stations',
  Medic: 'medical buildings',
  Firefighter: 'fire stations',
  Industrial: 'industrial buildings and sheds',
  Farm: 'farms',
  Town: 'town buildings',
  Village: 'houses',
  Hunting: 'hunting stands and lodges',
  Office: 'offices',
  School: 'schools',
  Prison: 'the prison',
  Coast: 'coastal buildings',
  ContaminatedArea: 'contaminated zones',
  Lunapark: 'the amusement park',
  Historical: 'castles and ruins',
  SeasonalEvent: 'seasonal events',
}
const TIER_WORDS = ['', 'along the coast', 'inland', 'in the north and west', 'in the north-west military areas']

const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9.]+/g, ' ').trim()

/** Calibres as players say them → how the loot table's ammo is named. */
const CALIBRES: readonly (readonly [RegExp, string])[] = [
  [/7\.?62 ?x ?39/, '7.62x39mm rounds'], [/5\.?45/, '5.45x39mm rounds'], [/5\.?56/, '5.56x45mm rounds'], [/7\.?62 ?x ?54|54r/, '7.62x54mmR rounds'],
  [/\.?308/, '.308 WIN rounds'], [/9 ?x ?39/, '9x39mm rounds'], [/9 ?(?:x ?19|mm)/, '9x19mm rounds'], [/(?<![\d.])\.?45\b/, '.45 ACP rounds'], [/(?<![\d.])\.?380/, '.380 ACP rounds'],
  [/\.?357/, '.357 rounds'], [/\.22|\b22 ?lr\b/, '.22 LR rounds'], [/12 ?(?:ga|gauge)|buck ?shot|shotgun shells/, '12ga buckshot'], [/slugs?/, '12ga slugs'],
]

/** The item named in the text (longest name or nickname that appears as words); ammo by its calibre. */
export function findItem(text: string): LootItem | null {
  const lower = text.toLowerCase()
  if (/\b(ammo|rounds|bullets|shells)\b/.test(lower)) {
    const cal = CALIBRES.find(([re]) => re.test(lower))
    // The box: more rounds per find, and the same places as loose rounds.
    if (cal) return CHERNARUS_ITEMS.find((i) => i.name.startsWith(`${cal[1]}, box`)) ?? CHERNARUS_ITEMS.find((i) => i.name === cal[1]) ?? null
  }
  const q = ` ${norm(text)} `
  let best: { item: LootItem; len: number } | null = null
  for (const item of CHERNARUS_ITEMS) {
    for (const n of [item.name, ...item.aka]) {
      const k = norm(n)
      if (k.length < 3 || (best && k.length <= best.len)) continue
      if (q.includes(` ${k} `) || q.includes(` ${k}s `)) best = { item, len: k.length }
    }
  }
  return best?.item ?? null
}

/** Item kinds players ask for by kind: "where do I find a backpack". */
const KINDS: readonly (readonly [RegExp, string])[] = [
  [/\b(backpacks?|bags?)\b/, 'backpack'],
  [/\bhelmets?\b/, 'helmet'],
  [/\b(vests?|plate carriers?|armou?r)\b/, 'vest'],
  [/\b(boots|shoes|footwear)\b/, 'footwear'],
  [/\btents?\b/, 'tent'],
]
export function kindIn(text: string): string | null {
  const q = text.toLowerCase()
  return KINDS.find(([re]) => re.test(q))?.[1] ?? null
}

function where(item: LootItem): string {
  const places = item.usage.map((u) => USAGE_WORDS[u] ?? u.toLowerCase())
  const list = places.length > 1 ? `${places.slice(0, -1).join(', ')} and ${places.at(-1)}` : places[0] ?? 'nowhere listed'
  const tiers = item.tiers.length && item.tiers.length < 4 ? ` ${item.tiers.map((t) => TIER_WORDS[t]).join(' and ')} (loot tier${item.tiers.length > 1 ? `s ${item.tiers[0]}-${item.tiers.at(-1)}` : ` ${item.tiers[0]}`})` : ''
  return `${list}${tiers}`
}

/** The settlements whose buildings can hold the item, in its tiers, nearest first. */
export function placesFor(item: LootItem, from: { x: number; y: number } | null): { place: DayzPlace; buildings: number; tier: number; m: number }[] {
  const near = (CHERNARUS.world as unknown as { near: Record<string, Record<string, number>> }).near
  return CHERNARUS.places
    .filter((p) => ['city', 'town', 'village', 'military', 'airfield', 'local'].includes(p.kind))
    .map((p) => ({ place: p, buildings: item.usage.reduce((s, u) => s + (near[p.name]?.[u] ?? 0), 0), tier: tierAt(p.x, p.y), m: from ? distance(from, p) : 0 }))
    .filter((c) => c.buildings > 0 && (!item.tiers.length || item.tiers.includes(c.tier)))
    .sort((a, b) => (from ? a.m - b.m : b.buildings - a.buildings))
}

/** Where an item spawns, how many there are, and the nearest good place to look. */
export function itemAnswer(item: LootItem, from: { x: number; y: number; label: string } | null): string {
  const parts = [`${item.name}: spawns in ${where(item)}, about ${item.nominal} on Chernarus at a time${item.deloot ? ', and at helicopter crashes and convoys' : ''}.`]
  const spots = placesFor(item, from)
  if (from && spots.length) {
    const [a, b] = [spots[0], spots.find((s) => s.buildings >= Math.max(3, spots[0].buildings * 2) && s !== spots[0])]
    const here = a.m < 400 ? `right here in ${a.place.name}` : `${a.place.name}, ${km(a.m)} ${bearing(from, a.place)}`
    parts.push(`Nearest from ${from.label}: ${here} (${a.buildings} such building${a.buildings === 1 ? '' : 's'}${a.tier ? `, loot tier ${a.tier}` : ''}).`)
    if (b) parts.push(`More chances in ${b.place.name}, ${km(b.m)} ${bearing(from, b.place)} (${b.buildings}).`)
  } else if (spots.length) {
    parts.push(`Most of those buildings: ${spots.slice(0, 3).map((s) => `${s.place.name} (${s.buildings})`).join(', ')}. Tell me where you are for the nearest.`)
  } else if (!item.usage.length) parts.push('It is not given a building type in the loot table (events or crafting).')
  return parts.join(' ')
}

/** The kinds people ask about: the most common few, with where they spawn. */
export function kindAnswer(kind: string, from: { x: number; y: number; label: string } | null): string {
  const list = CHERNARUS_ITEMS.filter((i) => i.kind === kind && i.nominal > 0).sort((a, b) => b.nominal - a.nominal).slice(0, 5)
  if (!list.length) return `No ${kind} in Chernarus's loot table.`
  const first = list[0]
  const nearest = from ? placesFor(first, from)[0] : null
  return `${kind.charAt(0).toUpperCase() + kind.slice(1)}s on Chernarus, most common first: ${list.map((i) => `${i.name} (${where(i)}, about ${i.nominal})`).join('; ')}.${nearest && from ? ` Nearest place for a ${first.name.toLowerCase()} from ${from.label}: ${nearest.place.name}, ${km(nearest.m)} ${bearing(from, nearest.place)}.` : ''}`
}

/** "What spawns in police stations?": the most common items of a building type. */
export function buildingAnswer(usage: string): string {
  const list = CHERNARUS_ITEMS.filter((i) => i.usage.includes(usage) && !/^(Ammo|Mag)/.test(i.id)).sort((a, b) => b.nominal - a.nominal).slice(0, 14)
  return `Most common in ${USAGE_WORDS[usage] ?? usage} on Chernarus: ${list.map((i) => i.name).join(', ')}.`
}

export const USAGES = USAGE_WORDS
