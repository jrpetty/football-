// The Chernarus survival assistant: where you are (a spot you set: "I'm at
// Turovo", "I'm at 135 012"), your kit ("I found a knife"), what to do next
// ("Coach, what now?": water, then houses, medical, then a weapon, as one
// route from your spot), "take me to the nearest hospital", and where any item
// spawns. Everything from the built-in data and what you told it; nothing read
// off the screen, nothing guessed about other players.

import { bearing, DAYZ_MAPS, distance, km, neighbours, placesIn, type DayzMap, type DayzPlace, type PlaceMatch } from './places.ts'
import { parsePosition } from './mapView.ts'
import { pointsOf, type StopKind, STOP_LABELS } from './lootRun.ts'
import { footTime, straightSeconds } from './travel.ts'
import { lineHills, tripBetween, tripShortBetween } from './heights.ts'
import { findItem, itemAnswer, kindAnswer, kindIn, placesFor, buildingAnswer, type LootItem } from './items.ts'

export const CHERNARUS: DayzMap = DAYZ_MAPS.find((m) => m.id === 'chernarus')!

export interface Spot {
  readonly x: number
  readonly y: number
  readonly label: string
}

/** A spot as a place, for the planners that start from a place. */
export function spotPlace(s: Spot): PlaceMatch {
  const place: DayzPlace = { name: s.label, kind: 'local', facilities: [], x: s.x, y: s.y }
  return { place, map: CHERNARUS, via: 'name' }
}

/** "I'm at Turovo", "I am near 135 012", "set my spot to Berezino": the spot, or null. */
export function parseSetSpot(question: string): Spot | null {
  const q = question.trim().replace(/^coach[,\s]+/i, '')
  const m = q.match(/^(?:i'?m|i am|im)\s+(?:at|in|near|by|around)\s+(?:grid\s+)?(.+?)[.!?]*$/i) ?? q.match(/^(?:set|mark|put|change)\s+(?:my\s+)?(?:spot|position|location)\s+(?:to|at|as|in)?\s*(?:grid\s+)?(.+?)[.!?]*$/i)
  if (!m) return null
  const p = parsePosition(CHERNARUS, m[1])
  return p ? { x: p.x, y: p.y, label: p.label } : null
}

// --- The kit -------------------------------------------------------------------------------

export interface KitItem {
  readonly id: string
  readonly label: string
  readonly words: RegExp
  /** Where it is found, for the plan. */
  readonly from: 'houses' | 'medical' | 'police' | 'military'
}

export const KIT: readonly KitItem[] = [
  { id: 'water', label: 'Water bottle or canteen', words: /\b(canteen|water ?bottles?|bottles?)\b/, from: 'houses' },
  { id: 'knife', label: 'Knife', words: /\b(knife|knives|cleaver|machete|sickle)\b/, from: 'houses' },
  { id: 'fire', label: 'Matches (to start fires)', words: /\b(match(?:es|box)?|lighter|fire starter|hand drill)\b/, from: 'houses' },
  { id: 'clothes', label: 'Warm, dry clothes', words: /\b(jacket|hoodie|coat|clothes|clothing|pants|trousers|jeans|sweater|raincoat)\b/, from: 'houses' },
  { id: 'backpack', label: 'A backpack', words: /\b(backpack|bag|taloon|mountain bag|alice|tortilla|courier bag)\b/, from: 'houses' },
  { id: 'food', label: 'Food and a can opener', words: /\b(food|can opener|beans|rice|tuna|sardines|peaches|spaghetti|cans?|cereal)\b/, from: 'houses' },
  { id: 'map', label: 'Map or compass', words: /\b(map|compass)\b/, from: 'houses' },
  { id: 'bandages', label: 'Bandages or rags', words: /\b(bandages?|rags?)\b/, from: 'medical' },
  { id: 'medicine', label: 'Disinfectant and tetracycline', words: /\b(disinfectant|alcohol|tincture|tetracycline|antibiotics|vitamins|charcoal)\b/, from: 'medical' },
  { id: 'gun', label: 'A gun', words: /\b(gun|rifle|pistol|shotgun|mosin|sks|ka-?m|akm|ak|m4|blaze|bk-?\d+|cr-?\d+|ij-?70|deagle|revolver|repeater|sporter|winchester|tundra|vss|svd|lar|fx-45|mlock|glock)\b/, from: 'police' },
  { id: 'ammo', label: 'Ammo for it', words: /\b(ammo|rounds|bullets|magazine|mag|shells)\b/, from: 'military' },
]

/** "I found a knife and some rags", "I lost my backpack": what to tick and untick. */
export function parseKitUpdate(question: string): { have: string[]; lost: string[] } | null {
  const q = question.toLowerCase()
  const gained = /\b(i (?:found|got|have|picked up|grabbed|looted|made|crafted)|found|got|picked up|looted)\b/.test(q)
  const lost = /\b(lost|dropped|no longer have|used up|ran out of|don'?t have|broke)\b/.test(q)
  if (!gained && !lost) return null
  const ids = KIT.filter((k) => k.words.test(q)).map((k) => k.id)
  if (!ids.length) return null
  return lost ? { have: [], lost: ids } : { have: ids, lost: [] }
}

export const kitFrom = (s: string | number | undefined): Set<string> => new Set(String(s ?? '').split(',').filter((x) => KIT.some((k) => k.id === x)))
export const kitTo = (have: Set<string>): string => KIT.filter((k) => have.has(k.id)).map((k) => k.id).join(',')

export function kitLine(have: Set<string>): string {
  const missing = KIT.filter((k) => !have.has(k.id))
  return missing.length ? `Still missing: ${missing.map((k) => k.label.toLowerCase()).join(', ')}.` : 'You have the essentials.'
}

// --- What now: the next steps, as one route --------------------------------------------------

export interface Step {
  readonly kind: StopKind | 'houses'
  readonly x: number
  readonly y: number
  readonly title: string
  readonly line: string
}

export interface Plan {
  readonly text: string
  readonly route: readonly Spot[]
  readonly steps: readonly Step[]
}

/** Settlements with houses to search: towns and villages, nearest first. */
function houses(from: { x: number; y: number }): { place: DayzPlace; n: number; m: number }[] {
  const near = (CHERNARUS.world as unknown as { near: Record<string, Record<string, number>> }).near
  return CHERNARUS.places
    .filter((p) => p.kind === 'town' || p.kind === 'village' || p.kind === 'city')
    .map((p) => ({ place: p, n: (near[p.name]?.Village ?? 0) + (near[p.name]?.Town ?? 0), m: distance(from, p) }))
    .filter((h) => h.n >= 8)
    .sort((a, b) => a.m - b.m)
}

function nearestOf(kind: StopKind, from: { x: number; y: number }): { x: number; y: number; m: number } | null {
  let best: { x: number; y: number; m: number } | null = null
  for (const p of pointsOf(CHERNARUS, kind)) {
    const m = distance(from, p)
    if (!best || m < best.m) best = { ...p, m }
  }
  return best
}

const by = (p: { x: number; y: number }) => {
  const nb = neighbours(CHERNARUS, p, 1)[0]
  return nb ? (nb.m < 400 ? `in ${nb.place.name}` : `by ${nb.place.name}`) : ''
}

/**
 * The next steps from your spot for what you are missing, in the order that keeps you alive: water,
 * then the houses of a town (tools, clothes, food, a bag), medical, then a weapon (police first, military
 * for better). Each step is the nearest of its kind to the one before.
 */
export function whatNow(spot: Spot, have: Set<string>, opts: { thirsty?: boolean } = {}): Plan {
  const missing = KIT.filter((k) => !have.has(k.id))
  const steps: Step[] = []
  let at: { x: number; y: number } = spot
  let jog = 0
  let sprint = 0
  const add = (kind: Step['kind'], p: { x: number; y: number }, title: string, line: string) => {
    steps.push({ kind, x: Math.round(p.x), y: Math.round(p.y), title, line: `${line}, ${km(distance(at, p))} ${bearing(at, p)} (${tripShortBetween(CHERNARUS.id, at, p)})` })
    const h = lineHills(CHERNARUS.id, at, p)
    jog += straightSeconds(distance(at, p), 'foot', false, h)
    sprint += straightSeconds(distance(at, p), 'foot', true, h)
    at = p
  }
  if (!have.has('water') || opts.thirsty) {
    const w = nearestOf('water', at)
    if (w) add('water', w, 'Water pump', `Drink at the water pump ${by(w)}`.trim())
  }
  const fromHouses = missing.filter((k) => k.from === 'houses')
  if (fromHouses.length) {
    const h = houses(at)[0]
    if (h) add('houses', h.place, h.place.name, `Search the houses of ${h.place.name} for ${fromHouses.map((k) => k.label.toLowerCase()).slice(0, 4).join(', ')}`)
  }
  if (missing.some((k) => k.from === 'medical')) {
    const md = nearestOf('medical', at)
    if (md) add('medical', md, 'Medical', `Medical building ${by(md)} for bandages, disinfectant and tetracycline`.replace(/ {2}/g, ' '))
  }
  if (!have.has('gun')) {
    const po = nearestOf('police', at)
    if (po) add('police', po, 'Police station', `Police station ${by(po)} for a first gun (pistols, shotguns)`.replace(/ {2}/g, ' '))
  }
  if (!have.has('gun') || !have.has('ammo')) {
    const mil = nearestOf('military', at)
    if (mil && distance(spot, mil) < 6000) add('military', mil, 'Military', `Military ${by(mil)} for rifles and ammo: more players go there, so go ready`.replace(/ {2}/g, ' '))
  }
  if (!steps.length) {
    return { text: `From ${spot.label}: you have the essentials. Keep water and food up, and tell me what you want next ("where do I find a gas mask", "take me to the nearest military").`, route: [], steps }
  }
  const text = `From ${spot.label}: ${steps.map((s, i) => `${i + 1}. ${s.line}`).join('; ')}. ${steps.length > 1 ? `All of it: ${footTime(jog, sprint)}, not counting looting. ` : ''}${kitLine(have)} Tell me what you find ("Coach, I found a knife").`
  return { text, route: [spot, ...steps.map((s) => ({ x: s.x, y: s.y, label: `${steps.indexOf(s) + 1} ${s.title}` }))], steps }
}

// --- Take me to ------------------------------------------------------------------------------

const TARGETS: readonly (readonly [RegExp, StopKind])[] = [
  [/\bhospital\b/, 'hospital'],
  [/\b(medical|clinic|medic|meds)\b/, 'medical'],
  [/\b(water|pump|drink)\b/, 'water'],
  [/\b(police|cops)\b/, 'police'],
  [/\b(military|army|barracks|base)\b/, 'military'],
  [/\bfire ?station\b/, 'fire'],
  [/\b(fuel|gas station|petrol)\b/, 'fuel'],
  [/\b(heli|crash)\b/, 'heliCrash'],
]

export function isTakeMe(q: string): boolean {
  return /\b(how long (?:does it take |would it take |will it take |is it )?to (?:get|walk|run|jog|drive|go) to|how far (?:is it )?to|take me to|get me to|guide me to|lead me to|route (?:me )?to|navigate to|show me the way to|way to the nearest|go to the nearest|nearest place (?:with|for|to find))\b/i.test(q)
}

/** "Take me to the nearest hospital / to Berezino / to a place with morphine": from your spot, one leg. */
export function takeMeTo(question: string, spot: Spot): Plan | null {
  const q = question.toLowerCase()
  if (!isTakeMe(q)) return null
  const item = /\b(with|for|to find|where i can find|that has)\b/.test(q) ? findItem(q) : null
  if (item) return toItem(item, spot)
  const kind = TARGETS.find(([re]) => re.test(q))?.[1]
  if (kind) {
    const p = nearestOf(kind, spot)
    if (!p) return null
    const title = STOP_LABELS[kind]
    const line = `${title} ${by(p)}, ${km(p.m)} ${bearing(spot, p)} from ${spot.label}`.replace(/ {2}/g, ' ')
    return { text: `${line}: ${tripBetween(CHERNARUS.id, spot, p)}.`, route: [spot, { x: p.x, y: p.y, label: title }], steps: [{ kind, x: p.x, y: p.y, title, line }] }
  }
  const place = placesIn(question, 'chernarus').find((m) => m.map.id === 'chernarus')
  if (place) {
    const p = place.place
    return { text: `${p.name}: ${km(distance(spot, p))} ${bearing(spot, p)} from ${spot.label}, ${tripBetween(CHERNARUS.id, spot, p)}.`, route: [spot, { x: p.x, y: p.y, label: p.name }], steps: [] }
  }
  return null
}

function toItem(item: LootItem, spot: Spot): Plan | null {
  const best = placesFor(item, spot)[0]
  if (!best) return { text: itemAnswer(item, spot), route: [], steps: [] }
  return { text: `${itemAnswer(item, spot)}${best.m >= 400 ? ` Getting to ${best.place.name}: ${tripBetween(CHERNARUS.id, spot, best.place)}.` : ''}`, route: [spot, { x: best.place.x, y: best.place.y, label: best.place.name }], steps: [] }
}

// --- Questions -----------------------------------------------------------------------------

export function isWhatNow(q: string): boolean {
  return /\b(what now|what next|what should i do|what do i do|what'?s next|next step|plan my (?:spawn|life|run)|fresh spawn|just spawned|i(?:'?ve| have) just spawned|where should i go)\b/i.test(q)
}

/** "Where do I find / can I get / does X spawn": an item named, a kind (backpacks), or a building type. */
export function isItemQuestion(q: string): boolean {
  const s = q.toLowerCase()
  // "Do I need a gas mask …" asks how the game works, not where to find one.
  if (/\b(?:do|should|would) i need\b/.test(s)) return false
  if (!/\b(where (?:do|can|could|would|should) i (?:find|get|loot|look for)|where (?:does|do|is|are) .+ spawn|where to find|find (?:a|an|some|me)|looking for|i need (?:a|an|some)|spawns? (?:in|at)|what spawns|what can i find|what loot)\b/.test(s)) return false
  return !!findItem(s) || !!kindIn(s) || !!buildingIn(s)
}

function buildingIn(q: string): string | null {
  if (!/\b(what spawns|what can i find|what loot|what'?s in)\b/.test(q)) return null
  if (/\bpolice\b/.test(q)) return 'Police'
  if (/\b(medical|hospital|clinic)\b/.test(q)) return 'Medic'
  if (/\b(military|army|barracks)\b/.test(q)) return 'Military'
  if (/\bfire ?station\b/.test(q)) return 'Firefighter'
  if (/\b(hunting|deer stand|lodge)\b/.test(q)) return 'Hunting'
  if (/\b(industrial|factory|shed)\b/.test(q)) return 'Industrial'
  if (/\bfarms?\b/.test(q)) return 'Farm'
  if (/\b(houses?|village)\b/.test(q)) return 'Village'
  return null
}

export function itemQuestionAnswer(question: string, spot: Spot | null): string | null {
  const q = question.toLowerCase()
  const b = buildingIn(q)
  if (b) return buildingAnswer(b)
  const item = findItem(q)
  if (item) return itemAnswer(item, spot)
  const kind = kindIn(q)
  return kind ? kindAnswer(kind, spot) : null
}

/** The spot from the answer context (set by the player), if any. */
export function spotFrom(context: Readonly<Record<string, string | number>>): Spot | null {
  const x = Number(context.spotX)
  const y = Number(context.spotY)
  return Number.isFinite(x) && Number.isFinite(y) && context.spotX !== undefined ? { x, y, label: String(context.spotLabel ?? 'your spot') } : null
}

/** The assistant's answer to a question, or null when it is not one of its questions. */
export function assistantAnswer(question: string, context: Readonly<Record<string, string | number>>): string | null {
  const spot = spotFrom(context)
  const have = kitFrom(context.kit)
  if (isWhatNow(question)) return spot ? whatNow(spot, have).text : `Tell me where you are first: "Coach, I'm at Turovo" or "I'm at 135 012". ${kitLine(have)}`
  if (isTakeMe(question)) {
    if (!spot) return 'Where are you? Say "Coach, I\'m at" and a town or grid first, then ask again.'
    return takeMeTo(question, spot)?.text ?? null
  }
  if (isItemQuestion(question)) return itemQuestionAnswer(question, spot)
  return null
}

export function isAssistantQuestion(question: string): boolean {
  return isWhatNow(question) || isTakeMe(question) || isItemQuestion(question)
}

/** For the map: the route the assistant would draw for this question, from your spot. */
export function assistantRoute(question: string, spot: Spot | null, have: Set<string>): Plan | null {
  if (!spot) return null
  if (isWhatNow(question)) return whatNow(spot, have)
  if (isTakeMe(question)) return takeMeTo(question, spot)
  if (isItemQuestion(question)) {
    const item = findItem(question)
    return item ? toItem(item, spot) : null
  }
  return null
}
