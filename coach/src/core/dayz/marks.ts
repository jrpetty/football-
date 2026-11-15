// Your own marks on the DayZ map: what you saw and what is yours. "I saw two
// players heading north near Gorka", "heard shots east of Elektro", "sniper on
// the hill north of Cherno", "my base is 500 m north of Gorka", "I buried a
// barrel here", "parked the car at Berezino", "meet at the Green Mountain
// tower". Each becomes a mark where you said (a town, a grid, "here" for your
// spot, or "500 m north of" a town); if there is no place in what you said,
// you click it on the map.
//
// Marks are only what you told the coach: nothing is read off the screen and
// nothing is guessed about other players. Sightings age: shown with how long
// ago, faded after half an hour, dropped after six hours (danger and shots
// after a day). Your base, stashes, car and notes stay until you remove them.
// Routes say when they pass near a recent sighting or danger you marked.

import { bearing, DAYZ_MAPS, distance, km, neighbours, placesIn, type DayzMap, type DayzMapId } from './places.ts'
import { parsePosition } from './mapView.ts'
import { tripBetween } from './heights.ts'

export { EMPTY_MARKS, isMark, isMarkBook, type Mark, type MarkBook, type MarkKind } from './markBook.ts'
import { isMark, type Mark, type MarkKind } from './markBook.ts'

export interface MarkInfo {
  readonly label: string
  /** One or two characters for the map pin. */
  readonly glyph: string
  /** Seen things age out; owned things stay. */
  readonly sighting: boolean
}

export const MARK_INFO: Readonly<Record<MarkKind, MarkInfo>> = {
  player: { label: 'Players seen', glyph: 'P', sighting: true },
  shots: { label: 'Shots heard', glyph: '!', sighting: true },
  danger: { label: 'Danger', glyph: '⚠', sighting: true },
  base: { label: 'Base', glyph: 'B', sighting: false },
  stash: { label: 'Stash', glyph: 'S', sighting: false },
  vehicle: { label: 'Vehicle', glyph: 'V', sighting: false },
  loot: { label: 'Loot', glyph: 'L', sighting: false },
  rally: { label: 'Meeting point', glyph: 'R', sighting: false },
  body: { label: 'Where you died', glyph: '✝', sighting: false },
  note: { label: 'Note', glyph: 'N', sighting: false },
}

export const MARK_KINDS = Object.keys(MARK_INFO) as MarkKind[]

const MIN = 60_000
/** Faded on the map after this. */
export const FADE_MS = 30 * MIN
/** How long a sighting is kept. */
export function keepMs(kind: MarkKind): number {
  if (kind === 'player') return 6 * 60 * MIN
  if (kind === 'shots' || kind === 'danger') return 24 * 60 * MIN
  if (kind === 'body') return 3 * 60 * MIN
  return Number.POSITIVE_INFINITY
}

/** The marks still kept now (old sightings dropped). */
export function liveMarks(marks: readonly Mark[], now: number): Mark[] {
  return marks.filter((m) => now - m.at < keepMs(m.kind))
}

export const isFaded = (m: Mark, now: number) => MARK_INFO[m.kind].sighting && now - m.at > FADE_MS

/** "just now", "12 min ago", "2 h ago", "3 days ago". */
export function ago(at: number, now: number): string {
  const min = Math.round((now - at) / MIN)
  if (min < 1) return 'just now'
  if (min < 60) return `${min} min ago`
  const h = Math.round(min / 60)
  if (h < 48) return `${h} h ago`
  return `${Math.round(h / 24)} days ago`
}

// --- Where: here, a grid, "500 m north of Gorka", a town -----------------------------------------

const DIRS: Readonly<Record<string, readonly [number, number]>> = {
  north: [0, 1],
  south: [0, -1],
  east: [1, 0],
  west: [-1, 0],
  'north-east': [Math.SQRT1_2, Math.SQRT1_2],
  'north-west': [-Math.SQRT1_2, Math.SQRT1_2],
  'south-east': [Math.SQRT1_2, -Math.SQRT1_2],
  'south-west': [-Math.SQRT1_2, -Math.SQRT1_2],
}
const DIR_RE = '(north[- ]?east|north[- ]?west|south[- ]?east|south[- ]?west|north|south|east|west|ne|nw|se|sw|n|s|e|w)'
const SHORT: Readonly<Record<string, string>> = { n: 'north', s: 'south', e: 'east', w: 'west', ne: 'north-east', nw: 'north-west', se: 'south-east', sw: 'south-west' }
const dirName = (d: string) => SHORT[d.toLowerCase()] ?? d.toLowerCase().replace(/\s/, '-').replace(/^(north|south)(east|west)$/, '$1-$2')

export interface Where {
  readonly x: number
  readonly y: number
  /** In words, e.g. "500 m north of Gorka", "grid 135 012", "your spot (Turovo)". */
  readonly where: string
}

/**
 * Where a mark goes, from what was said: "here" (your spot), a grid, "500 m north of Gorka",
 * "north of Gorka" (about 400 m), "near/at/in Gorka". Null when nothing in it is a place.
 */
export function whereIn(text: string, map: DayzMap, spot: { x: number; y: number; label: string } | null): Where | null {
  const t = text.toLowerCase()
  const off = t.match(new RegExp(`\\b(?:(\\d+(?:\\.\\d+)?)\\s*(m|metres?|meters?|km|k|clicks?)\\s+)?(?:to the |just )?${DIR_RE}\\s+of\\s+(.+)$`, 'i'))
  if (off) {
    const rest = off[4]
    const base = /^(here|me|my spot|where i am)\b/.test(rest) && spot && map.id === 'chernarus' ? { x: spot.x, y: spot.y, name: spot.label, me: true } : placeAt(rest, map)
    if (base) {
      const n = off[1] ? Number(off[1]) * (/^(km|k|click)/.test(off[2]!) ? 1000 : 1) : 400
      const metres = Math.min(Math.max(n, 20), 8000)
      const d = dirName(off[3])
      const [dx, dy] = DIRS[d] ?? [0, 0]
      const x = clamp(base.x + dx * metres, map.worldSize)
      const y = clamp(base.y + dy * metres, map.worldSize)
      return { x, y, where: `${off[1] ? km(metres) : 'just'} ${d} of ${'me' in base ? 'your spot' : base.name}` }
    }
  }
  if (/\b(right here|here|where i am|my spot|my position|on me)\b/.test(t) && spot && map.id === 'chernarus') return { x: spot.x, y: spot.y, where: `your spot (${spot.label})` }
  const grid = t.match(/\b(\d{3})\s?(\d{3})\b/)
  if (grid) {
    const p = parsePosition(map, `${grid[1]} ${grid[2]}`)
    if (p) return { x: p.x, y: p.y, where: p.label }
  }
  const p = placeAt(t, map)
  return p ? { x: p.x, y: p.y, where: p.name } : null
}

function placeAt(text: string, map: DayzMap): { x: number; y: number; name: string } | null {
  const m = placesIn(text, map.id).find((c) => c.map.id === map.id)
  return m ? { x: m.place.x, y: m.place.y, name: m.place.name } : null
}

const clamp = (v: number, size: number) => Math.min(size, Math.max(0, v))

// --- What was said ----------------------------------------------------------------------------

const NUMBERS: Readonly<Record<string, number>> = { a: 1, an: 1, one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, couple: 2, few: 3, several: 4 }

export interface MarkDraft {
  readonly kind: MarkKind
  readonly label: string
  readonly count?: number
  readonly heading?: string
  readonly note?: string
}

/** A new mark said ("I saw…", "my base is…", "stash here"), or null. The place is worked out separately. */
export function draftIn(question: string): MarkDraft | null {
  const q = question.toLowerCase().replace(/^coach[,\s]+/, '').trim()
  // Asking about marks is not making one.
  if (/^(where|what|any|show|list|take|get|guide|how|is|are|did|do|does|should|can|could|would|will|clear|remove|delete|forget)\b/.test(q) || /\?\s*$/.test(q)) return null
  const heading = q.match(new RegExp(`\\b(?:heading|going|moving|running|driving|walking|went|ran|headed)\\s+(?:towards?\\s+(?:the\\s+)?)?${DIR_RE}\\b`))
  const dir = heading ? dirName(heading[1]) : undefined
  if (/\b(heard|hear|hearing)\b.*\b(shots?|shooting|gunfire|gun ?shots?|firing|fight)\b|\b(shots?|gunfire|shooting) (fired|going off)\b/.test(q)) return { kind: 'shots', label: 'Shots heard' }
  const people = q.match(/\b(saw|seen|see|spotted|spot|there'?s|there (?:is|are)|got shot at by|shot at by|ran into|bumped into|met)\b(?:\s+(?:a|an|some|one|two|three|four|five|six|seven|eight|\d+|couple of|couple|few|several|group of|squad of|team of|duo|pair of))*\s*(?:fresh(?:ie)?s?\s+|geared\s+|armed\s+)?(players?|guys?|people|persons?|someone|somebody|survivors?|men|man|dudes?|squad|group|team|duo|bandits?|campers?|snipers?|freshies?|fresh spawns?)\b/)
  if (people || /\b(sniper|camper|bandits?|ambush|kos)\b.*\b(at|on|in|near|by|around|north|south|east|west)\b/.test(q)) {
    const danger = /\b(sniper|snipers|camp(?:er|ers|ing)|ambush|kos|shot at|got shot|bandits?)\b/.test(q)
    const n = countIn(q)
    const who = /\bshot at\b/.test(q) ? 'Shot at' : /\bsnipers?\b/.test(q) ? (n > 1 ? `${n} snipers` : 'Sniper') : /\bcamp/.test(q) ? 'Campers' : /\bfresh/.test(q) ? (n > 1 ? `${n} fresh spawns` : 'Fresh spawn') : n > 1 ? `${n} players` : 'A player'
    const label = `${who}${dir ? ` heading ${dir}` : ''}`
    return { kind: danger ? 'danger' : 'player', label, count: n, ...(dir ? { heading: dir } : {}) }
  }
  if (/\b(my|our)\s+(base|camp|home|house)\b/.test(q) || /\b(built|building|set up|made)\b.*\b(base|camp)\b/.test(q) || /^(mark|set|save)\b.*\bbase\b/.test(q)) return { kind: 'base', label: /\bour\b/.test(q) ? 'Our base' : 'Base' }
  if (/\b(stash|buried|bury|hid|hidden|cache|barrel|tent|crate)\b/.test(q) && /\b(my|our|a|the|i|we)\b/.test(q)) {
    const what = q.match(/\b(barrel|tent|crate|stash|cache)\b/)?.[1]
    return { kind: 'stash', label: what && what !== 'stash' && what !== 'cache' ? `Stash (${what})` : 'Stash' }
  }
  const car = q.match(/\b(park(?:ed)?|left|ditched|hid|stashed)\b.*\b(car|truck|van|vehicle|ride|olga|ada|gunter|sarka|humvee|m1025|m3s|boat|heli(?:copter)?)\b|\b(my|our)\s+(car|truck|van|vehicle|ride|olga|ada|gunter|sarka|humvee|m1025|m3s|boat)\b/)
  if (car) {
    const what = (car[2] ?? car[4])!
    return { kind: 'vehicle', label: /^(car|vehicle|ride)$/.test(what) ? 'Car' : what === 'boat' ? 'Boat' : `Car (${what.charAt(0).toUpperCase() + what.slice(1)})` }
  }
  if (/\b(meet|meeting point|rally(?: point)?|regroup)\b/.test(q)) return { kind: 'rally', label: 'Meeting point' }
  if (/\b(good|great|lots of|decent)\s+loot\b|\bloot (?:spot|here)\b|\bfound (?:a |good )?loot\b/.test(q)) return { kind: 'loot', label: 'Good loot' }
  const note = q.match(/^(?:mark|note|remember|save)\s+(?:that\s+|this\s+)?(.+)$/)
  if (note) {
    // The note without where it is (the mark shows that): "the tower at 040 100 has a camper" → "the tower has a camper".
    const text = note[1]
      .replace(/\s*\b(?:at|on|in|near|by|around)\s+(?:grid\s+)?\d{3}\s?\d{3}\b/, '')
      .replace(/\s*\b(?:right )?here\b/, '')
      .replace(/\s+/g, ' ')
      .trim()
    return { kind: 'note', label: text ? text.charAt(0).toUpperCase() + text.slice(1, 80) : 'Note' }
  }
  return null
}

function countIn(q: string): number {
  const m = q.match(/\b(\d+|a|an|one|two|three|four|five|six|seven|eight|couple|few|several)\s+(?:of\s+)?(?:fresh\w*\s+|geared\s+|armed\s+)?(players?|guys?|people|persons?|survivors?|men|dudes?|bandits?|campers?|snipers?|freshies?|fresh spawns?)\b/)
  if (m) {
    const n = /^\d+$/.test(m[1]) ? Number(m[1]) : (NUMBERS[m[1]] ?? 1)
    return Math.max(1, Math.min(n, 50))
  }
  if (/\b(squad|group|team)\b/.test(q)) return 4
  if (/\b(duo|pair)\b/.test(q)) return 2
  return 1
}

// --- Asking about marks ------------------------------------------------------------------------

export type MarkAsk =
  | { readonly type: 'go'; readonly kind: MarkKind }
  | { readonly type: 'where'; readonly kind: MarkKind }
  | { readonly type: 'seen'; readonly near: string | null }
  | { readonly type: 'list' }
  | { readonly type: 'remove'; readonly kind: MarkKind | 'sightings' | 'all'; readonly last: boolean }

const OWN: readonly (readonly [RegExp, MarkKind])[] = [
  [/\b(base|camp|home)\b/, 'base'],
  [/\b(stash|barrel|tent|cache|crate)\b/, 'stash'],
  [/\b(car|truck|van|vehicle|ride|boat)\b/, 'vehicle'],
  [/\b(body|corpse|where i died|my gear|my stuff|my loot back)\b/, 'body'],
  [/\b(meeting point|rally(?: point)?|meet(?:ing)? (?:up )?spot|where we meet)\b/, 'rally'],
  [/\bloot (?:spot|mark)\b/, 'loot'],
]

/** A question or order about marks, or null. */
export function markAsk(question: string): MarkAsk | null {
  const q = question.toLowerCase().replace(/^coach[,\s]+/, '').trim()
  const own = OWN.find(([re]) => re.test(q))?.[1] ?? null
  if (/^(remove|delete|clear|forget|undo|drop|wipe)\b/.test(q)) {
    if (/\b(last|that) (mark|one)\b|^undo\b/.test(q)) return { type: 'remove', kind: 'all', last: true }
    if (/\b(sightings?|players?|shots|danger|contacts?)\b/.test(q)) return { type: 'remove', kind: 'sightings', last: false }
    if (/\b(all|every|everything)\b.*\bmarks?\b/.test(q)) return { type: 'remove', kind: 'all', last: false }
    if (own) return { type: 'remove', kind: own, last: false }
    return null
  }
  if (own && /\b(take me|get me|guide me|lead me|route|way|go|back to|head (?:back )?to|how (?:far|long)|navigate|return)\b/.test(q)) return { type: 'go', kind: own }
  if (own && /\b(where(?:'s| is| was| did)|show|find)\b/.test(q) && /\b(my|our|the)\b/.test(q)) return { type: 'where', kind: own }
  if (/\b(what|who|anyone|anybody|any players?|any contacts?|any sightings?)\b.*\b(seen|saw|spotted|sightings?|heard)\b|\b(recent|latest) (sightings?|contacts?)\b|\bwho(?:'s| is) (?:been )?around\b/.test(q)) {
    const near = q.match(/\b(?:near|around|at|in|by)\s+(.+?)[?.!]*$/)?.[1] ?? null
    return { type: 'seen', near }
  }
  if (/\b(my|show|list|all)\s+(?:my\s+)?marks?\b|\bmarks? (?:list|so far)\b|\bwhat (?:have i|did i) mark/.test(q)) return { type: 'list' }
  return null
}

// --- Answers -----------------------------------------------------------------------------------

/** The one mark of a kind to use: the newest. */
export function newest(marks: readonly Mark[], kind: MarkKind, map: DayzMapId | null = null): Mark | null {
  return marks.filter((m) => m.kind === kind && (!map || m.map === map)).sort((a, b) => b.at - a.at)[0] ?? null
}

/** One line for a mark: "2 players heading north, just north of Gorka, 12 min ago". */
export function markLine(m: Mark, now: number, from: { x: number; y: number } | null = null): string {
  const away = from ? `, ${km(distance(from, m))} ${bearing(from, m)} of you` : ''
  const age = MARK_INFO[m.kind].sighting || m.kind === 'body' ? `, ${ago(m.at, now)}` : ''
  return `${m.label}, ${m.where}${away}${age}${m.note ? ` (${m.note})` : ''}`
}

/** The confirmation for a new mark, with what is close to it. */
export function addedLine(m: Mark, now: number, from: { x: number; y: number; label: string } | null): string {
  const map = DAYZ_MAPS.find((d) => d.id === m.map)!
  const nb = neighbours(map, m, 1)[0]
  const near = nb && !m.where.includes(nb.place.name) ? ` Nearest town: ${nb.place.name}, ${km(nb.m)} ${nb.dir}.` : ''
  const fromYou = from && m.map === 'chernarus' && distance(from, m) > 60 ? ` ${km(distance(from, m))} ${bearing(from, m)} of you.` : ''
  const name = m.kind === 'vehicle' ? 'car' : m.kind === 'rally' ? 'meeting point' : m.kind
  const tail = MARK_INFO[m.kind].sighting ? ' It fades after half an hour; I will mention it if a route passes near.' : m.kind === 'body' ? '' : ` Say "take me to my ${name}" any time.`
  void now
  return `Marked: ${m.label.charAt(0).toLowerCase() + m.label.slice(1)}, ${m.where}.${fromYou}${near}${tail}`
}

/** What you have seen (near a place, or anywhere), newest first. */
export function seenLine(marks: readonly Mark[], now: number, near: { x: number; y: number; name: string } | null, from: { x: number; y: number } | null): string {
  const seen = marks
    .filter((m) => MARK_INFO[m.kind].sighting && (!near || distance(m, near) < 1500))
    .sort((a, b) => b.at - a.at)
  if (!seen.length) return near ? `Nothing marked near ${near.name}. Tell me when you see someone ("I saw a player at …").` : 'No sightings marked. Tell me when you see or hear someone: "I saw two players heading north near Gorka", "heard shots east of Elektro".'
  return `${near ? `Near ${near.name}: ` : ''}${seen.slice(0, 6).map((m) => markLine(m, now, from)).join('; ')}.${seen.length > 6 ? ` And ${seen.length - 6} more on the map.` : ''} Only what you told me; nobody's position now.`
}

/** All marks in short. */
export function listLine(marks: readonly Mark[], now: number, from: { x: number; y: number } | null): string {
  if (!marks.length) return 'No marks yet. Say "my base is …", "I buried a barrel here", "parked the car at …" or "I saw a player at …", or click Mark on the map.'
  const owned = marks.filter((m) => !MARK_INFO[m.kind].sighting)
  const seen = marks.filter((m) => MARK_INFO[m.kind].sighting)
  const parts = [...owned.slice(0, 6).map((m) => markLine(m, now, from))]
  if (seen.length) parts.push(`${seen.length} sighting${seen.length === 1 ? '' : 's'} (newest: ${markLine(seen.sort((a, b) => b.at - a.at)[0], now, from)})`)
  return `Your marks: ${parts.join('; ')}.`
}

/** "Your base: 2.1 km north-east, about 9 min jogging." */
export function goLine(m: Mark, from: { x: number; y: number; label: string }, now: number): string {
  const d = distance(from, m)
  return `${MARK_INFO[m.kind].label === 'Where you died' ? 'Where you died (your last spot before)' : m.label}: ${m.where}, ${km(d)} ${bearing(from, m)} from ${from.label}, ${tripBetween(m.map, from, m)}.${m.kind === 'body' ? ` Marked ${ago(m.at, now)}; bodies do not stay for ever, so go soon and go careful.` : ''}`
}

// --- Warnings on a route ----------------------------------------------------------------------

/** How close to the way a sighting must be to mention, in metres. */
const NEAR_ROUTE = 600
/** Sightings older than this are not mentioned on routes. */
const WARN_MS = 2 * 60 * MIN

function segDistance(p: { x: number; y: number }, a: { x: number; y: number }, b: { x: number; y: number }): number {
  const dx = b.x - a.x
  const dy = b.y - a.y
  const len2 = dx * dx + dy * dy
  const t = len2 ? Math.max(0, Math.min(1, ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2)) : 0
  return Math.hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}

/** Recent sightings and danger you marked close to a route, nearest first. */
export function nearRoute(marks: readonly Mark[], route: readonly { x: number; y: number }[], map: DayzMapId, now: number): { mark: Mark; m: number }[] {
  if (route.length < 2) return []
  return marks
    .filter((m) => m.map === map && MARK_INFO[m.kind].sighting && now - m.at < (m.kind === 'player' ? WARN_MS : 12 * 60 * MIN))
    .map((mark) => ({ mark, m: Math.min(...route.slice(1).map((b, i) => segDistance(mark, route[i], b))) }))
    .filter((x) => x.m < NEAR_ROUTE)
    .sort((a, b) => a.m - b.m)
}

/** "Heads up: you saw 2 players heading north near Gorka 12 min ago, 300 m from this way." */
export function warningLine(marks: readonly Mark[], route: readonly { x: number; y: number }[], map: DayzMapId, now: number): string | null {
  const near = nearRoute(marks, route, map, now)
  if (!near.length) return null
  const parts = near.slice(0, 2).map(({ mark, m }) => `${mark.kind === 'player' ? 'you saw ' : mark.kind === 'shots' ? 'you heard ' : 'you marked '}${mark.label.charAt(0).toLowerCase() + mark.label.slice(1)} ${mark.where.startsWith('your spot') ? 'at your old spot' : `near ${mark.where}`} ${ago(mark.at, now)}, ${m < 100 ? 'on this way' : `${km(m)} from this way`}`)
  return `Heads up: ${parts.join('; ')}.${near.length > 2 ? ` ${near.length - 2} more on the map.` : ''}`
}

/** Sightings close to a spot you just set ("I'm at …"). */
export function aroundLine(marks: readonly Mark[], spot: { x: number; y: number }, map: DayzMapId, now: number): string | null {
  const close = marks
    .filter((m) => m.map === map && MARK_INFO[m.kind].sighting && now - m.at < WARN_MS * 2 && distance(m, spot) < 1000)
    .sort((a, b) => distance(a, spot) - distance(b, spot))
  if (!close.length) return null
  const m = close[0]
  return `Heads up: ${m.label.charAt(0).toLowerCase() + m.label.slice(1)} marked ${km(distance(spot, m))} ${bearing(spot, m)} of here, ${ago(m.at, now)}.`
}

// --- Sharing with your squad --------------------------------------------------------------------

/** A code to paste to a friend: the marks you choose, nothing else. */
export function shareCode(marks: readonly Mark[]): string {
  const slim = marks.map((m) => [m.kind, m.map, Math.round(m.x), Math.round(m.y), m.label, m.where, m.at, m.note ?? ''])
  return `SLMARKS1:${toBase64(JSON.stringify(slim))}`
}

/** Marks from a friend's code (new ids, so they never replace yours), or null when it is not a code. */
export function readShareCode(code: string, now: number): Mark[] | null {
  const m = code.trim().match(/^SLMARKS1:([A-Za-z0-9+/=]+)$/)
  if (!m) return null
  try {
    const rows = JSON.parse(fromBase64(m[1])) as unknown
    if (!Array.isArray(rows) || rows.length > 500) return null
    const out: Mark[] = []
    rows.forEach((r, i) => {
      if (!Array.isArray(r)) return
      const [kind, map, x, y, label, where, at, note] = r as unknown[]
      const mark = { id: `shared-${now}-${i}`, kind, map, x, y, label: String(label ?? '').slice(0, 120), where: String(where ?? '').slice(0, 160), at: Number(at), ...(note ? { note: String(note).slice(0, 300) } : {}) }
      if (isMark(mark)) out.push(mark)
    })
    return out
  } catch {
    return null
  }
}

function toBase64(s: string): string {
  const bytes = new TextEncoder().encode(s)
  let bin = ''
  bytes.forEach((b) => (bin += String.fromCharCode(b)))
  return btoa(bin)
}

function fromBase64(s: string): string {
  const bin = atob(s)
  return new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)))
}

// --- Making and removing marks from what was said -----------------------------------------------

/** A new mark from what was said and where. */
export function makeMark(d: MarkDraft, w: Where, map: DayzMapId, now: number, id = `mark-${now.toString(36)}-${Math.floor(Math.random() * 1e6).toString(36)}`): Mark {
  return { id, kind: d.kind, map, x: Math.round(w.x), y: Math.round(w.y), label: d.label, where: w.where, at: now, ...(d.count ? { count: d.count } : {}), ...(d.heading ? { heading: d.heading } : {}), ...(d.note ? { note: d.note } : {}) }
}

export interface MarkCtx {
  readonly spot: { x: number; y: number; label: string } | null
  readonly map: DayzMapId
  readonly marks: readonly Mark[]
  readonly now: number
}

export type MarkSaid =
  | { readonly type: 'add'; readonly mark: Mark; readonly text: string }
  | { readonly type: 'pick'; readonly draft: MarkDraft; readonly text: string }
  | { readonly type: 'remove'; readonly ids: readonly string[]; readonly text: string }

/** "I saw…", "my base is…", "remove my base", "clear sightings": what to do with the marks, or null. */
export function markSaid(question: string, ctx: MarkCtx): MarkSaid | null {
  const ask = markAsk(question)
  if (ask?.type === 'remove') return removal(ask, ctx)
  const d = draftIn(question)
  if (!d) return null
  const map = DAYZ_MAPS.find((m) => m.id === ctx.map)!
  const w = whereIn(question, map, ctx.spot)
  if (!w) {
    const what = d.label.charAt(0).toLowerCase() + d.label.slice(1)
    return { type: 'pick', draft: d, text: `Where was that? Click the spot on the map to mark ${what}, or say a town, a grid or "500 m north of" a town.` }
  }
  const mark = makeMark(d, w, ctx.map, ctx.now)
  return { type: 'add', mark, text: addedLine(mark, ctx.now, ctx.spot) }
}

function removal(ask: Extract<MarkAsk, { type: 'remove' }>, ctx: MarkCtx): MarkSaid {
  const { marks } = ctx
  if (ask.last) {
    const m = [...marks].sort((a, b) => b.at - a.at)[0]
    return m ? { type: 'remove', ids: [m.id], text: `Removed the last mark: ${m.label.charAt(0).toLowerCase() + m.label.slice(1)}, ${m.where}.` } : { type: 'remove', ids: [], text: 'There are no marks to remove.' }
  }
  const pick = ask.kind === 'all' ? marks : ask.kind === 'sightings' ? marks.filter((m) => MARK_INFO[m.kind].sighting) : marks.filter((m) => m.kind === ask.kind)
  if (!pick.length) return { type: 'remove', ids: [], text: ask.kind === 'all' ? 'There are no marks to remove.' : `No ${ask.kind === 'sightings' ? 'sightings' : MARK_INFO[ask.kind].label.toLowerCase()} marked.` }
  const what = ask.kind === 'all' ? `all ${pick.length} marks` : ask.kind === 'sightings' ? `${pick.length} sighting${pick.length === 1 ? '' : 's'}` : pick.length === 1 ? `your ${MARK_INFO[ask.kind].label.toLowerCase()} mark` : `${pick.length} ${MARK_INFO[ask.kind].label.toLowerCase()} marks`
  return { type: 'remove', ids: pick.map((m) => m.id), text: `Removed ${what}.` }
}

/** A clicked point in words: "in Gorka" or "600 m north-west of Gorka", with its grid. */
export function whereWords(map: DayzMap, at: { x: number; y: number }): string {
  const grid = `grid ${String(Math.floor(at.x / 100)).padStart(3, '0')} ${String(Math.max(0, Math.floor((map.worldSize - at.y) / 100))).padStart(3, '0')}`
  const nb = neighbours(map, at, 1)[0]
  if (!nb) return grid
  return nb.m < 300 ? `in ${nb.place.name} (${grid})` : `${km(nb.m)} ${bearing(nb.place, at)} of ${nb.place.name} (${grid})`
}
