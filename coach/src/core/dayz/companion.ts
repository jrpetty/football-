// The DayZ companion: a chat with its own screen. Every answer can put a card
// on the screen (a map with a route or place, a place, a gun, an item's
// spawns, a plan, advice, the kit, a comparison or a list), so the player sees
// what they asked for at a glance. Questions the coach knows are answered on
// this computer at once; anything else goes to Claude, which can call the same
// tools (places, routes, items, guns, plans, the player's spot and kit) and so
// answers from the same data and puts cards on the same screen.
//
// Also: a short memory of what was just talked about, so "how far is it?",
// "take me there" and "show it on the map" follow on.

import type { ArsenalItem, GameProfile } from '../profile/schema.ts'
import { answerMechanics, answerPlaceQuestion, mechanicsFor } from './ask.ts'
import { assistantRoute, KIT, kitLine, mapOf, takeMeTo, whatNow, type Spot } from './assistant.ts'
import { bestLine, compareLine, findGun, guns, gunsIn } from './guns.ts'
import { findItem, itemAnswer, buildingAnswer, itemsOn, kindAnswer, kindIn, placesFor, USAGES, type LootItem } from './items.ts'
import { isLootRun, lootRunLine, lootRunPath, parseLootRun, planLootRun } from './lootRun.ts'
import { bearing, DAYZ_MAPS, describePlace, distance, featureLine, findPlace, gridRef, km, nearest, neighbours, placesIn, region, type DayzMapId, type PlaceMatch } from './places.ts'
import { pointInfo } from './mapView.ts'
import { tripBetween, tripShortBetween } from './heights.ts'
import { goLine, listLine, liveMarks, makeMark, MARK_INFO, MARK_KINDS, markAsk, markLine, markSaid, newest, seenLine, warningLine, whereIn, type Mark, type MarkKind } from './marks.ts'

// --- Cards ----------------------------------------------------------------------------------

export interface CardPos {
  readonly x: number
  readonly y: number
  readonly label: string
}

export type Card =
  | { readonly type: 'map'; readonly map: DayzMapId; readonly title: string; readonly caption?: string; readonly route?: readonly CardPos[]; readonly focus?: CardPos; readonly markers?: readonly CardPos[] }
  | { readonly type: 'place'; readonly map: DayzMapId; readonly title: string; readonly subtitle: string; readonly facts: readonly (readonly [string, string])[]; readonly text: string; readonly focus: CardPos; readonly markers: readonly CardPos[] }
  | { readonly type: 'gun'; readonly title: string; readonly subtitle: string; readonly look: string; readonly stats: readonly (readonly [string, string])[]; readonly range: readonly { readonly m: number; readonly hold: string; readonly speed: string }[]; readonly where: readonly (readonly [string, string])[]; readonly tips: readonly string[] }
  | { readonly type: 'item'; readonly title: string; readonly subtitle: string; readonly facts: readonly (readonly [string, string])[]; readonly nearest: readonly { readonly name: string; readonly where: string; readonly buildings: number; readonly tier: number }[]; readonly map: DayzMapId; readonly markers: readonly CardPos[]; readonly from: CardPos | null }
  | { readonly type: 'plan'; readonly map: DayzMapId; readonly title: string; readonly steps: readonly { readonly n: number; readonly title: string; readonly line: string }[]; readonly route: readonly CardPos[]; readonly missing: readonly string[] }
  | { readonly type: 'advice'; readonly title: string; readonly text: string; readonly fix?: string; readonly why?: string; readonly sources?: readonly { readonly title: string; readonly url: string }[] }
  | { readonly type: 'kit'; readonly have: readonly string[]; readonly missing: readonly string[] }
  | { readonly type: 'list'; readonly title: string; readonly rows: readonly (readonly [string, string])[] }
  | { readonly type: 'compare'; readonly title: string; readonly columns: readonly [string, string]; readonly rows: readonly (readonly [string, string, string])[] }
  | { readonly type: 'help'; readonly examples: readonly string[] }
  /** Click the map to place a mark the player told about without saying where. */
  | { readonly type: 'pick'; readonly map: DayzMapId; readonly title: string; readonly kind: MarkKind; readonly label: string }

/** What the player has: their spot (on the map they are on), kit, and that map. */
export interface CompanionContext {
  readonly spot: Spot | null
  readonly kit: readonly string[]
  readonly map: DayzMapId
  /** Their own marks (what they saw, their base, stash, car…), and the time now. */
  readonly marks?: readonly Mark[]
  readonly now?: number
}

/** The last thing talked about, for follow-ups ("take me there", "show it on the map"). */
export type Topic =
  | { readonly kind: 'place'; readonly name: string; readonly map: DayzMapId; readonly x: number; readonly y: number }
  | { readonly kind: 'item'; readonly id: string }
  | { readonly kind: 'gun'; readonly id: string }
  | null

export interface Reply {
  readonly text: string
  readonly card: Card | null
  readonly topic: Topic
}

const pos = (p: { x: number; y: number }, label: string): CardPos => ({ x: Math.round(p.x), y: Math.round(p.y), label })

export function placeCard(m: PlaceMatch): Card {
  const p = m.place
  const facts: [string, string][] = [
    ['Grid', gridRef(m.map, p.x, p.y)],
    ['Region', region(m.map, p.x, p.y).replace(/^the /, '')],
  ]
  if (p.cyrillic && m.map.signs === 'cyrillic') facts.push(['Sign', p.cyrillic])
  if (p.tier) facts.push(['Loot tier', p.tier])
  for (const k of ['water', 'medical', 'fuel', 'police', 'military'] as const) {
    const f = nearest(m.map, p, k)
    if (f) facts.push([k === 'medical' && f.label === 'hospital' ? 'Hospital' : k.charAt(0).toUpperCase() + k.slice(1), `${km(f.m)} ${f.dir}`])
  }
  const markers = neighbours(m.map, p, 4).map((n) => pos(n.place, n.place.name))
  return { type: 'place', map: m.map.id, title: p.name, subtitle: `${p.kind} · ${m.map.name}`, facts, text: describePlace(m), focus: pos(p, p.name), markers }
}

export function gunCardFor(i: ArsenalItem): Card {
  const s = i.stats ?? {}
  const stat = (k: string) => (s[k] === undefined ? null : String(s[k]))
  const stats: [string, string][] = (
    [
      ['Calibre', stat('calibre')],
      ['Magazines', stat('magazines') ?? (stat('internalMagazine') ? `holds ${stat('internalMagazine')}` : null)],
      ['Fire modes', stat('fireModes')],
      ['Rate of fire', stat('rpm') ? `${stat('rpm')} rpm` : null],
      ['Recoil', stat('recoil')],
      ['Noise', stat('noise')],
      ['Damage', stat('damage') ? `${stat('damage')}${s.calibre === '12ga' ? ' a slug' : ''}` : null],
      ['Muzzle speed', stat('muzzleSpeed') ? `${stat('muzzleSpeed')} m/s` : null],
      ['Weight', stat('weightKg') ? `${stat('weightKg')} kg` : null],
      ['Sights', stat('sights')],
    ] as [string, string | null][]
  ).filter((r): r is [string, string] => r[1] !== null)
  const zero = typeof s.zeroForHold === 'number' ? s.zeroForHold : null
  const range = zero
    ? [100, 200, 300, 400, 600]
        .filter((m) => typeof s[`hold${m}`] === 'number' && (s[`hold${m}`] as number) < 40)
        .map((m) => ({ m, hold: m <= zero ? 'on the crosshair' : `${Math.round((s[`hold${m}`] as number) * 100)} cm high`, speed: typeof s[`speed${m}`] === 'number' ? `${s[`speed${m}`]} m/s` : '' }))
    : []
  return {
    type: 'gun',
    title: i.name,
    subtitle: `${(i.class ?? 'gun').replace(/_/g, ' ')} · best at ${i.bestRange} range${zero ? ` · zeroed at ${zero} m` : ''}`,
    look: i.look ?? '',
    stats,
    range,
    where: [
      ['Chernarus', String(s.whereChernarus ?? '—')],
      ['Livonia', String(s.whereLivonia ?? '—')],
      ['Sakhal', String(s.whereSakhal ?? '—')],
    ],
    tips: [...(i.strengths ?? []).map((x) => `+ ${x}`), ...(i.weaknesses ?? []).map((x) => `− ${x}`), ...(i.tips ?? [])].slice(0, 6),
  }
}

export function itemCardFor(item: LootItem, spot: Spot | null, map: DayzMapId = spot?.map ?? 'chernarus'): Card {
  const spots = placesFor(item, spot, map).slice(0, 6)
  const mapName = DAYZ_MAPS.find((m) => m.id === map)!.name
  const usages = item.usage.map((u) => USAGES[u] ?? u).join(', ') || 'no building type'
  return {
    type: 'item',
    title: item.name,
    subtitle: `${item.kind} · about ${item.nominal} on ${mapName} at a time`,
    facts: [
      ['Spawns in', usages],
      ['Loot tiers', item.tiers.length ? item.tiers.join(', ') : 'any'],
      ...(item.deloot ? [['Also', 'helicopter crashes and convoys'] as [string, string]] : []),
    ],
    nearest: spots.map((s) => ({ name: s.place.name, where: spot ? `${km(s.m)} ${bearing(spot, s.place)} · ${tripShortBetween(map, spot, s.place)}` : '', buildings: s.buildings, tier: s.tier })),
    map,
    markers: spots.map((s) => pos(s.place, `${s.place.name} (${s.buildings})`)),
    from: spot ? pos(spot, spot.label) : null,
  }
}

function planCard(spot: Spot, have: Set<string>): Card {
  const plan = whatNow(spot, have)
  return {
    type: 'plan',
    map: mapOf(spot).id,
    title: `From ${spot.label}`,
    steps: plan.steps.map((s, i) => ({ n: i + 1, title: s.title, line: s.line })),
    route: plan.route.map((r) => pos(r, r.label)),
    missing: KIT.filter((k) => !have.has(k.id)).map((k) => k.label),
  }
}

function adviceCard(question: string, profile: GameProfile): Card | null {
  const m = mechanicsFor(question, profile as never)
  if (!m || (!m.item && !m.entry)) return null
  const entry = m.entry as { title?: string; fix: string; why?: string; sources?: { title: string; url: string }[] } | null
  return {
    type: 'advice',
    title: entry?.title ?? m.item?.name ?? 'Survival',
    text: m.item?.detail ?? '',
    ...(entry ? { fix: entry.fix } : {}),
    ...(entry?.why ? { why: entry.why } : {}),
    ...(entry?.sources?.length ? { sources: entry.sources.slice(0, 3).map((s) => ({ title: s.title, url: s.url })) } : {}),
  }
}

function compareCard(a: ArsenalItem, b: ArsenalItem): Card {
  const row = (label: string, key: string, unit = '') => [label, a.stats?.[key] !== undefined ? `${a.stats[key]}${unit}` : '—', b.stats?.[key] !== undefined ? `${b.stats[key]}${unit}` : '—'] as const
  return {
    type: 'compare',
    title: `${a.name} vs ${b.name}`,
    columns: [a.name, b.name],
    rows: [row('Calibre', 'calibre'), row('Damage', 'damage'), row('Rate of fire', 'rpm', ' rpm'), row('Recoil', 'recoil'), row('Drop at 300 m', 'hold300', ' m'), row('Muzzle speed', 'muzzleSpeed', ' m/s'), row('On Chernarus', 'whereChernarus')],
  }
}

export const HELP_EXAMPLES = [
  "I'm at Turovo",
  'What now?',
  'Take me to the nearest hospital',
  'Where do I find a canteen?',
  'Where is Typobo?',
  'Cherno to NWAF',
  'Mosin range',
  'Mosin vs SVD',
  'Loot run from Berezino hitting police and military',
  'I saw two players heading north near Gorka',
  'My base is 500 m north of Gorka',
  'Take me to my base',
  'How do I treat cholera?',
  'What do I have?',
  'Show me the map',
]

// --- Local replies ----------------------------------------------------------------------------

const FOLLOW = /\b(it|there|that place|that town|that one|this one|them)\b/

/** Follow-ups about the last topic: "take me there", "how far is it", "show it on the map", "where does it spawn". */
function followUp(q: string, topic: Topic, ctx: CompanionContext, profile: GameProfile): Reply | null {
  if (!topic || !FOLLOW.test(q)) return null
  // "How long does it take to get to Berezino": a new place named, so not about the last topic.
  if (placesIn(q, ctx.map).some((m) => topic.kind !== 'place' || m.place.name !== topic.name)) return null
  if (topic.kind === 'place') {
    const p = { x: topic.x, y: topic.y }
    if (/\b(show|map|where is it|on the map)\b/.test(q) && !/\b(take|route|how far|get)\b/.test(q)) {
      return { text: `${topic.name} on the map.`, card: { type: 'map', map: topic.map, title: topic.name, focus: pos(p, topic.name) }, topic }
    }
    if (/\b(take me|get me|route|how far|how long|how do i get|way|go there|distance)\b/.test(q)) {
      if (!ctx.spot || topic.map !== ctx.map) return { text: `Where are you? Say "I'm at" a town or grid, and I will show the way to ${topic.name}.`, card: null, topic }
      const m = distance(ctx.spot, p)
      return { text: `${topic.name}: ${km(m)} ${bearing(ctx.spot, p)} from ${ctx.spot.label}, ${tripBetween(ctx.map, ctx.spot, p)}.`, card: { type: 'map', map: ctx.map, title: `To ${topic.name}`, route: [pos(ctx.spot, ctx.spot.label), pos(p, topic.name)] }, topic }
    }
    if (/\b(what'?s|what is) (near|around)\b/.test(q)) {
      const m = findPlace(topic.name)
      if (m) return { text: describePlace(m), card: placeCard(m), topic }
    }
  }
  if (topic.kind === 'item') {
    const item = itemsOn(ctx.map).find((i) => i.id === topic.id)
    if (item && /\b(where|spawn|find|nearest|show|map|take me)\b/.test(q)) return { text: itemAnswer(item, ctx.spot, ctx.map), card: itemCardFor(item, ctx.spot, ctx.map), topic }
  }
  if (topic.kind === 'gun') {
    const g = guns(profile).find((x) => x.id === topic.id)
    if (g) return { text: `${g.name}: ${g.summary}`, card: gunCardFor(g), topic }
  }
  return null
}

/**
 * The companion's own reply, when it knows the answer: the text, a card for the screen, and the topic.
 * Null when the question needs Claude (or is not a DayZ question it can answer).
 */
export function localReply(question: string, ctx: CompanionContext, profile: GameProfile, topic: Topic, answered: string | null): Reply | null {
  const r = localReplyInner(question, ctx, profile, topic, answered)
  return r ? withWarnings(r, ctx) : null
}

/** A route answer with a heads-up when it passes near a recent sighting or danger the player marked. */
function withWarnings<T extends { text: string; card: Card | null }>(r: T, ctx: CompanionContext): T {
  const card = r.card
  if (!card || (card.type !== 'map' && card.type !== 'plan') || !card.route || card.route.length < 2 || !ctx.marks?.length) return r
  const warn = warningLine(liveMarks(ctx.marks, ctx.now ?? Date.now()), card.route, card.map, ctx.now ?? Date.now())
  return warn ? { ...r, text: `${r.text} ${warn}` } : r
}

/** Questions about the player's marks: take me to my base, where is my car, what have I seen near Gorka, my marks. */
function marksReply(question: string, ctx: CompanionContext, topic: Topic): Reply | null {
  const ask = markAsk(question)
  if (!ask || ask.type === 'remove') return null
  const now = ctx.now ?? Date.now()
  const marks = liveMarks(ctx.marks ?? [], now)
  const spot = ctx.spot
  const here = spot
  if (ask.type === 'go' || ask.type === 'where') {
    const m = newest(marks, ask.kind, ctx.map) ?? newest(marks, ask.kind)
    const name = MARK_INFO[ask.kind].label.toLowerCase()
    if (!m) {
      const text = ask.kind === 'body' ? 'I have nothing for where you died: I mark it from your spot when a life ends, so set your spot as you go ("I\'m at …").' : `No ${name} marked yet. Say "my ${ask.kind === 'vehicle' ? 'car is at' : ask.kind === 'rally' ? 'meeting point is' : `${ask.kind} is`} …" (a town, a grid, "here" or "500 m north of" a town), or click Mark on the map.`
      return { text, card: { type: 'pick', map: ctx.map, title: `Mark your ${name}`, kind: ask.kind, label: MARK_INFO[ask.kind].label }, topic }
    }
    const t: Topic = { kind: 'place', name: m.label, map: m.map, x: m.x, y: m.y }
    if (ask.type === 'go' && here && m.map === ctx.map) {
      return { text: goLine(m, here, now), card: { type: 'map', map: m.map, title: `To ${m.label.charAt(0).toLowerCase() + m.label.slice(1)}`, route: [pos(here, here.label), pos(m, m.label)] }, topic: t }
    }
    return { text: `${markLine(m, now, here)}.${ask.type === 'go' && !here ? ' Say where you are ("I\'m at …") for the way there and how long it takes.' : ''}`, card: { type: 'map', map: m.map, title: m.label, focus: pos(m, m.label) }, topic: t }
  }
  if (ask.type === 'seen') {
    const near = ask.near ? findPlace(ask.near, ctx.map) : null
    const at = near ? { x: near.place.x, y: near.place.y, name: near.place.name } : null
    return { text: seenLine(marks.filter((m) => m.map === ctx.map), now, at, here), card: { type: 'map', map: ctx.map, title: at ? `Seen near ${at.name}` : 'What you have seen', ...(at ? { focus: pos(at, at.name) } : here ? { focus: pos(here, here.label) } : {}) }, topic }
  }
  return { text: listLine(marks.filter((m) => m.map === ctx.map), now, here), card: { type: 'map', map: ctx.map, title: 'Your marks' }, topic }
}

function localReplyInner(question: string, ctx: CompanionContext, profile: GameProfile, topic: Topic, answered: string | null): Reply | null {
  const q = question.toLowerCase().trim()
  const mine = marksReply(question, ctx, topic)
  if (mine) return mine
  const follow = followUp(q, topic, ctx, profile)
  if (follow) return follow
  if (/^(help|what can you do|what do you know|commands)\b/.test(q)) {
    return { text: 'Ask me about places, routes, loot, guns and staying alive on Chernarus, Livonia and Sakhal. Tell me where you are and what you have, and I can plan your next steps and show them on the map.', card: { type: 'help', examples: HELP_EXAMPLES }, topic }
  }
  if (/\b(what do i have|my kit|what am i missing|what do i need)\b/.test(q)) {
    const have = new Set(ctx.kit)
    return { text: kitLine(have), card: { type: 'kit', have: KIT.filter((k) => have.has(k.id)).map((k) => k.label), missing: KIT.filter((k) => !have.has(k.id)).map((k) => k.label) }, topic }
  }
  if (/\b(show|open|pull up)\b.*\bmap\b/.test(q) && !placesIn(q, ctx.map).length) {
    const map = DAYZ_MAPS.find((m) => q.includes(m.id)) ?? DAYZ_MAPS.find((m) => m.id === ctx.map)!
    return { text: `${map.name}.`, card: { type: 'map', map: map.id, title: map.name, ...(ctx.spot && map.id === ctx.map ? { focus: pos(ctx.spot, ctx.spot.label) } : {}) }, topic }
  }
  if (/\bwhere am i\b/.test(q) && ctx.spot) {
    return { text: `You set your spot at ${ctx.spot.label}. ${pointInfo(mapOf({ map: ctx.map }), ctx.spot).line}`, card: { type: 'map', map: ctx.map, title: 'You', focus: pos(ctx.spot, ctx.spot.label) }, topic }
  }
  if (!answered) return null
  // A local answer exists: give it its card.
  return { text: answered, ...cardFor(question, ctx, profile, topic) }
}

/** The card (and the new topic) for a question the coach answered on this computer. */
export function cardFor(question: string, ctx: CompanionContext, profile: GameProfile, topic: Topic): { card: Card | null; topic: Topic } {
  const q = question.toLowerCase()
  const spot = ctx.spot
  // The assistant: plans, take me to, item spawns.
  const route = assistantRoute(question, spot, new Set(ctx.kit))
  if (/\b(what now|what next|what should i do|next step|plan my|just spawned|fresh spawn)\b/.test(q) && spot) return { card: planCard(spot, new Set(ctx.kit)), topic }
  const item = findItem(q, ctx.map)
  if (item && /\b(find|get|spawn|loot|looking for|need|place with|where)\b/.test(q)) return { card: itemCardFor(item, spot, ctx.map), topic: { kind: 'item', id: item.id } }
  const kind = kindIn(q)
  if (kind && /\b(find|get|where)\b/.test(q)) {
    const list = itemsOn(ctx.map).filter((i) => i.kind === kind).sort((a, b) => b.nominal - a.nominal).slice(0, 8)
    return { card: { type: 'list', title: `${kind.charAt(0).toUpperCase() + kind.slice(1)}s on ${mapOf({ map: ctx.map }).name}`, rows: list.map((i) => [i.name, `about ${i.nominal} · ${i.usage.map((u) => USAGES[u] ?? u).join(', ')}`]) }, topic }
  }
  if (/\bwhat spawns|what can i find|what loot\b/.test(q)) {
    const b = Object.keys(USAGES).find((u) => q.includes((USAGES[u] ?? '').split(' ')[0].toLowerCase()))
    if (b) {
      const list = itemsOn(ctx.map).filter((i) => i.usage.includes(b) && !/^(Ammo|Mag)/.test(i.id)).sort((a, c) => c.nominal - a.nominal).slice(0, 12)
      return { card: { type: 'list', title: `In ${USAGES[b]}`, rows: list.map((i) => [i.name, `about ${i.nominal}`]) }, topic }
    }
  }
  if (route && route.route.length) {
    const target = route.route.at(-1)!
    const on = mapOf(spot).id
    return { card: { type: 'map', map: on, title: target.label, route: route.route.map((r) => pos(r, r.label)) }, topic: { kind: 'place', name: target.label, map: on, x: target.x, y: target.y } }
  }
  // Guns.
  const named = gunsIn(q, profile)
  if (named.length >= 2 && /\b(vs|versus|or|compare|better|than)\b/.test(q)) return { card: compareCard(named[0], named[1]), topic: { kind: 'gun', id: named[1].id } }
  if (named[0]) return { card: gunCardFor(named[0]), topic: { kind: 'gun', id: named[0].id } }
  if (/\b(best|strongest)\b/.test(q) && /\b(gun|rifle|sniper|pistol|smg|shotgun)s?\b/.test(q)) {
    const line = bestLine(q, profile, ctx.map)
    if (line) return { card: { type: 'list', title: 'Best of a kind', rows: line.split(/; |: /).slice(1).map((r) => [r.replace(/ \(.*$/, ''), r.match(/\((.*)\)/)?.[1] ?? '']) }, topic }
  }
  // Places: a loot run, a route, a place.
  if (isLootRun(question)) {
    const req = parseLootRun(question, ctx.map, spot ? { place: { name: spot.label, kind: 'local', facilities: [], x: spot.x, y: spot.y }, map: mapOf(spot), via: 'name' } : null)
    if (req) {
      const plan = planLootRun(req)
      return { card: { type: 'map', map: req.start.map.id, title: `Loot run from ${plan.start.name}`, caption: lootRunLine(plan).replace(/ Open it on the Map to see the line\.$/, ''), route: lootRunPath(plan).map((p) => pos(p, p.label)) }, topic }
    }
  }
  const places = placesIn(question, ctx.map)
  if (places.length >= 2 && /\b(to|from|between|route|way|far)\b/.test(q)) {
    const [a, b] = places
    return { card: { type: 'map', map: a.map.id, title: `${a.place.name} → ${b.place.name}`, route: [pos(a.place, a.place.name), pos(b.place, b.place.name)] }, topic: { kind: 'place', name: b.place.name, map: b.map.id, x: b.place.x, y: b.place.y } }
  }
  if (places[0]) return { card: placeCard(places[0]), topic: { kind: 'place', name: places[0].place.name, map: places[0].map.id, x: places[0].place.x, y: places[0].place.y } }
  // Survival.
  const advice = adviceCard(question, profile)
  if (advice) return { card: advice, topic }
  return { card: null, topic }
}

// --- Tools for Claude ---------------------------------------------------------------------------
// The same answers as the local ones, callable by Claude in an open conversation. Each returns text
// for Claude and a card for the screen. Their definitions are sent from the main process (fixed there).

export interface ToolDef {
  readonly name: string
  readonly description: string
  readonly input_schema: { readonly type: 'object'; readonly properties: Record<string, unknown>; readonly required?: readonly string[] }
}

export const COMPANION_TOOLS: readonly ToolDef[] = [
  { name: 'player_state', description: "The player's spot (set by them), their kit checklist and the map they play. Call this before planning from where they are.", input_schema: { type: 'object', properties: {} } },
  { name: 'set_spot', description: 'Set where the player says they are on the map they play: a town (any spelling) or a 100 m grid like "135 012".', input_schema: { type: 'object', properties: { where: { type: 'string' } }, required: ['where'] } },
  { name: 'update_kit', description: 'Tick or untick kit items the player says they have found or lost.', input_schema: { type: 'object', properties: { have: { type: 'array', items: { type: 'string', enum: KIT.map((k) => k.id) } }, lost: { type: 'array', items: { type: 'string', enum: KIT.map((k) => k.id) } } } } },
  { name: 'find_place', description: 'Where a town, village, base or landmark is (any spelling, Cyrillic or the way a sign looks), what is in it and near it. Shows it on the screen.', input_schema: { type: 'object', properties: { name: { type: 'string' } }, required: ['name'] } },
  { name: 'route', description: 'The way between two places, or from the player\'s spot to a place ("from" omitted). Shows the route on the map, following roads and fields.', input_schema: { type: 'object', properties: { from: { type: 'string' }, to: { type: 'string' } }, required: ['to'] } },
  { name: 'nearest', description: "The nearest of a kind of place from the player's spot or a named place, shown on the map.", input_schema: { type: 'object', properties: { kind: { type: 'string', enum: ['water', 'hospital', 'medical', 'police', 'military', 'fire', 'fuel', 'heliCrash'] }, from: { type: 'string' } }, required: ['kind'] } },
  { name: 'item_spawns', description: "Where an item spawns on the map being played (building types, loot tiers, how many at a time) and the nearest places with those buildings. Items include tools, food, clothes, bags, medical, ammo (by calibre), magazines and attachments. Shows it on the screen.", input_schema: { type: 'object', properties: { item: { type: 'string' } }, required: ['item'] } },
  { name: 'what_spawns_in', description: 'The most common items in a building type.', input_schema: { type: 'object', properties: { building: { type: 'string', enum: ['Military', 'Police', 'Medic', 'Firefighter', 'Industrial', 'Farm', 'Town', 'Village', 'Hunting', 'Office', 'School'] } }, required: ['building'] } },
  { name: 'gun_info', description: 'A gun: what it looks like, calibre, magazines, fire modes, recoil, range and bullet drop, where it spawns. Shows its card.', input_schema: { type: 'object', properties: { gun: { type: 'string' } }, required: ['gun'] } },
  { name: 'compare_guns', description: 'Two guns side by side.', input_schema: { type: 'object', properties: { a: { type: 'string' }, b: { type: 'string' } }, required: ['a', 'b'] } },
  { name: 'best_guns', description: 'The hardest-hitting guns of a kind that spawn, and the easiest of those to find.', input_schema: { type: 'object', properties: { kind: { type: 'string', enum: ['sniper', 'pistol', 'smg', 'shotgun', 'assault rifle', 'quiet', 'close range', 'any'] } }, required: ['kind'] } },
  { name: 'survival_advice', description: 'How the game works on a survival topic (diseases, bleeding, fractures, blood types, gas zones, temperature, stamina, food and water, jamming, zeroing), with the fix.', input_schema: { type: 'object', properties: { topic: { type: 'string' } }, required: ['topic'] } },
  { name: 'plan_next_steps', description: "The player's next steps from their spot for what their kit is missing (water, houses, medical, police, military), drawn as a route.", input_schema: { type: 'object', properties: {} } },
  { name: 'loot_run', description: 'The shortest run from a town through one of each kind of place (hospital, medical, police, military, fire station, fuel, water, heli crash), optionally back.', input_schema: { type: 'object', properties: { request: { type: 'string', description: 'In words, e.g. "from Berezino hitting a hospital, police and military and back".' } }, required: ['request'] } },
  { name: 'add_mark', description: "Put a mark on the player's map for something they tell you: players they saw (kind player; count and heading if said), shots heard, danger (a sniper, campers), their base, a stash, their vehicle, good loot, a meeting point, or a note. Only what the player says; never invent sightings.", input_schema: { type: 'object', properties: { kind: { type: 'string', enum: [...MARK_KINDS.filter((k) => k !== 'body')] }, where: { type: 'string', description: 'A town, a grid ("135 012"), "here" (their spot), or "500 m north of Gorka".' }, label: { type: 'string', description: 'Short, e.g. "2 players heading north", "Our base".' }, count: { type: 'integer' }, heading: { type: 'string' }, note: { type: 'string' } }, required: ['kind', 'where'] } },
  { name: 'remove_marks', description: 'Remove marks the player asks to remove.', input_schema: { type: 'object', properties: { what: { type: 'string', enum: ['last', 'sightings', 'all', ...MARK_KINDS] } }, required: ['what'] } },
  { name: 'list_marks', description: "The player's marks (base, stash, vehicle, meeting point, notes) and recent sightings with how long ago, shown on the map.", input_schema: { type: 'object', properties: {} } },
  { name: 'show_map', description: 'Put the map on the screen, optionally centred on a place.', input_schema: { type: 'object', properties: { map: { type: 'string', enum: ['chernarus', 'livonia', 'sakhal'] }, place: { type: 'string' } } } },
]

export interface ToolEffects {
  readonly setSpot: (s: Spot) => void
  readonly setKit: (have: readonly string[], lost: readonly string[]) => void
  /** Add a mark, or remove marks by id (the player's own map marks). */
  readonly addMark?: (m: Mark) => void
  readonly removeMarks?: (ids: readonly string[]) => void
}

/** Run one tool: the text for Claude, a card for the screen, and the topic it leaves. */
export function runTool(name: string, input: Record<string, unknown>, ctx: CompanionContext, profile: GameProfile, effects: ToolEffects): { text: string; card: Card | null; topic?: Topic; ctx?: CompanionContext } {
  return withWarnings(runToolInner(name, input, ctx, profile, effects), ctx)
}

function runToolInner(name: string, input: Record<string, unknown>, ctx: CompanionContext, profile: GameProfile, effects: ToolEffects): { text: string; card: Card | null; topic?: Topic; ctx?: CompanionContext } {
  const str = (k: string) => (typeof input[k] === 'string' ? (input[k] as string).slice(0, 200) : '')
  const spot = ctx.spot
  switch (name) {
    case 'player_state': {
      const now = ctx.now ?? Date.now()
      const marks = liveMarks(ctx.marks ?? [], now).map((m) => markLine(m, now, spot))
      return { text: JSON.stringify({ spot: spot ? { label: spot.label, grid: gridRef(mapOf({ map: ctx.map }), spot.x, spot.y), area: pointInfo(mapOf({ map: ctx.map }), spot).line } : null, kit: { have: ctx.kit, missing: KIT.filter((k) => !ctx.kit.includes(k.id)).map((k) => k.id) }, map: ctx.map, marks }), card: null }
    }
    case 'add_mark': {
      const kind = MARK_KINDS.includes(str('kind') as MarkKind) ? (str('kind') as MarkKind) : 'note'
      const map = DAYZ_MAPS.find((m) => m.id === ctx.map)!
      const w = whereIn(str('where'), map, spot)
      const label = str('label') || MARK_INFO[kind].label
      if (!w) return { text: `No place "${str('where')}" found: ask the player to click it on the map (the screen shows the map to click).`, card: { type: 'pick', map: ctx.map, title: `Mark: ${label}`, kind, label } }
      const count = typeof input.count === 'number' && Number.isInteger(input.count) ? Math.max(1, Math.min(50, input.count)) : undefined
      const mark = makeMark({ kind, label: label.slice(0, 80), ...(count ? { count } : {}), ...(str('heading') ? { heading: str('heading') } : {}), ...(str('note') ? { note: str('note') } : {}) }, w, ctx.map, ctx.now ?? Date.now())
      effects.addMark?.(mark)
      return { text: `Marked: ${markLine(mark, mark.at, spot)}.`, card: { type: 'map', map: ctx.map, title: mark.label, focus: pos(mark, mark.label) }, ctx: { ...ctx, marks: [...(ctx.marks ?? []), mark] } }
    }
    case 'remove_marks': {
      const said = markSaid(str('what') === 'last' ? 'undo last mark' : str('what') === 'sightings' ? 'clear sightings' : str('what') === 'all' ? 'remove all marks' : `remove my ${str('what')}`, { spot, map: ctx.map, marks: ctx.marks ?? [], now: ctx.now ?? Date.now() })
      if (!said || said.type !== 'remove') return { text: 'Nothing removed.', card: null }
      effects.removeMarks?.(said.ids)
      return { text: said.text, card: null, ctx: { ...ctx, marks: (ctx.marks ?? []).filter((m) => !said.ids.includes(m.id)) } }
    }
    case 'list_marks': {
      const now = ctx.now ?? Date.now()
      const marks = liveMarks(ctx.marks ?? [], now).filter((m) => m.map === ctx.map)
      return { text: listLine(marks, now, spot), card: { type: 'map', map: ctx.map, title: 'Your marks' } }
    }
    case 'set_spot': {
      const r = localSpot(str('where'), ctx.map)
      if (!r) return { text: `No town or grid "${str('where')}" on ${mapOf({ map: ctx.map }).name}.`, card: null }
      effects.setSpot(r)
      return { text: `Spot set: ${r.label}. ${pointInfo(mapOf(r), r).line}`, card: { type: 'map', map: mapOf(r).id, title: 'You', focus: pos(r, r.label) }, ctx: { ...ctx, spot: r } }
    }
    case 'update_kit': {
      const have = Array.isArray(input.have) ? (input.have as unknown[]).filter((x): x is string => typeof x === 'string') : []
      const lost = Array.isArray(input.lost) ? (input.lost as unknown[]).filter((x): x is string => typeof x === 'string') : []
      effects.setKit(have, lost)
      const kit = new Set(ctx.kit)
      have.forEach((k) => kit.add(k))
      lost.forEach((k) => kit.delete(k))
      return { text: kitLine(kit), card: { type: 'kit', have: KIT.filter((k) => kit.has(k.id)).map((k) => k.label), missing: KIT.filter((k) => !kit.has(k.id)).map((k) => k.label) }, ctx: { ...ctx, kit: [...kit] } }
    }
    case 'find_place': {
      const m = findPlace(str('name'), ctx.map)
      return m ? { text: describePlace(m), card: placeCard(m), topic: { kind: 'place', name: m.place.name, map: m.map.id, x: m.place.x, y: m.place.y } } : { text: `No place called "${str('name')}".`, card: null }
    }
    case 'route': {
      const to = findPlace(str('to'), ctx.map)
      if (!to) return { text: `No place called "${str('to')}".`, card: null }
      const fromM = str('from') ? findPlace(str('from'), to.map.id) : null
      const from = fromM ? pos(fromM.place, fromM.place.name) : spot && to.map.id === ctx.map ? pos(spot, spot.label) : null
      if (!from) return { text: 'No start: name one, or ask the player where they are.', card: null }
      const m = distance(from, to.place)
      return { text: `${to.place.name} is ${km(m)} ${bearing(from, to.place)} of ${from.label} in a straight line, ${tripBetween(to.map.id, from, to.place)}; the map shows the way over the ground and its time.`, card: { type: 'map', map: to.map.id, title: `${from.label} → ${to.place.name}`, route: [from, pos(to.place, to.place.name)] }, topic: { kind: 'place', name: to.place.name, map: to.map.id, x: to.place.x, y: to.place.y } }
    }
    case 'nearest': {
      const kind = str('kind')
      const fromM = str('from') ? findPlace(str('from'), ctx.map) : null
      const from = fromM ? { x: fromM.place.x, y: fromM.place.y, label: fromM.place.name, map: fromM.map.id } : spot
      if (!from) return { text: 'No start: name a place, or ask the player where they are.', card: null }
      const plan = takeMeTo(`take me to the nearest ${kind}`, from)
      if (!plan?.route.length) return { text: `No ${kind} found.`, card: null }
      const t = plan.route.at(-1)!
      const on = mapOf(from).id
      return { text: plan.text, card: { type: 'map', map: on, title: t.label, route: plan.route.map((r) => pos(r, r.label)) }, topic: { kind: 'place', name: t.label, map: on, x: t.x, y: t.y } }
    }
    case 'item_spawns': {
      const item = findItem(str('item'), ctx.map) ?? findItem(`where do I find ${str('item')}`, ctx.map)
      if (!item) {
        const kind = kindIn(str('item'))
        return kind ? { text: kindAnswer(kind, spot, ctx.map), card: null } : { text: `"${str('item')}" is not in ${mapOf({ map: ctx.map }).name}'s loot table.`, card: null }
      }
      return { text: itemAnswer(item, spot, ctx.map), card: itemCardFor(item, spot, ctx.map), topic: { kind: 'item', id: item.id } }
    }
    case 'what_spawns_in':
      return { text: buildingAnswer(str('building'), ctx.map), card: null }
    case 'gun_info': {
      const g = findGun(str('gun'), profile)
      return g ? { text: `${g.name}: ${g.summary} Look: ${g.look ?? ''}`, card: gunCardFor(g), topic: { kind: 'gun', id: g.id } } : { text: `No gun called "${str('gun')}".`, card: null }
    }
    case 'compare_guns': {
      const a = findGun(str('a'), profile)
      const b = findGun(str('b'), profile)
      return a && b ? { text: compareLine(a, b, ctx.map), card: compareCard(a, b) } : { text: 'One of those guns is not known.', card: null }
    }
    case 'best_guns': {
      const k = str('kind')
      return { text: bestLine(`best ${k === 'any' ? 'gun' : k}`, profile, ctx.map) ?? 'None found.', card: null }
    }
    case 'survival_advice': {
      const t = str('topic')
      const text = answerMechanics(t, profile as never) ?? answerMechanics(`how do i deal with ${t}`, profile as never)
      return { text: text ?? `Nothing in the pack's reference about "${t}".`, card: adviceCard(t, profile) }
    }
    case 'plan_next_steps':
      if (!spot) return { text: 'The player has not said where they are: ask them.', card: null }
      return { text: whatNow(spot, new Set(ctx.kit)).text, card: planCard(spot, new Set(ctx.kit)) }
    case 'loot_run': {
      const req = parseLootRun(`plan a loot run ${str('request')}`, ctx.map, spot ? { place: { name: spot.label, kind: 'local', facilities: [], x: spot.x, y: spot.y }, map: mapOf(spot), via: 'name' } : null)
      if (!req) return { text: 'Name a town to start from and the kinds of place.', card: null }
      const plan = planLootRun(req)
      return { text: lootRunLine(plan), card: { type: 'map', map: req.start.map.id, title: `Loot run from ${plan.start.name}`, route: lootRunPath(plan).map((p) => pos(p, p.label)) } }
    }
    case 'show_map': {
      const map = (['chernarus', 'livonia', 'sakhal'] as const).find((m) => m === str('map')) ?? ctx.map
      const place = str('place') ? findPlace(str('place'), map) : null
      return { text: place ? `Showing ${place.place.name}.` : `Showing ${map}.`, card: { type: 'map', map: place?.map.id ?? map, title: place?.place.name ?? map, ...(place ? { focus: pos(place.place, place.place.name) } : {}) } }
    }
    default:
      return { text: `Unknown tool ${name}.`, card: null }
  }
}

function localSpot(where: string, mapId: DayzMapId): Spot | null {
  const map = mapOf({ map: mapId })
  const g = where.match(/(\d{3})\s*(\d{3})/)
  if (g) {
    const x = +g[1] * 100 + 50
    const y = map.worldSize - (+g[2] * 100 + 50)
    return { x, y, label: `grid ${gridRef(map, x, y)}`, map: mapId }
  }
  const m = findPlace(where, mapId)
  return m ? { x: m.place.x, y: m.place.y, label: m.place.name, map: m.map.id } : null
}

/** The companion's instructions to Claude (the main process adds the pack's knowledge). */
export const COMPANION_SYSTEM = [
  'You are the DayZ companion in the Sightline app: a friendly, sharp survival partner the player talks to while they play on Chernarus, Livonia or Sakhal. You chat naturally and briefly (usually 1-4 sentences, at most about 120 words; your words may be read aloud, so no markdown headings or tables).',
  'You have tools that read the app\'s built-in data: places and roads (from the game files and the DayZ Wiki), Bohemia\'s loot tables for each map, the gun data, the survival reference, the player\'s spot and kit (which they told you). Use them for any fact about places, distances, loot, guns or mechanics instead of guessing, and quote their numbers. Tools also put a card or a map on the app\'s screen beside the chat, so call the one that shows what the player asked for (for "show me", always).',
  'If you need where the player is and do not know, ask them (they can say "I\'m at" a town or grid) or call player_state. When they say where they are or what they found, call set_spot or update_kit. When the player tells you they saw or heard someone, or where their base, stash or car is, call add_mark; their marks come back with player_state and list_marks (sightings say how long ago, and are only what they saw then, never where anyone is now). Whenever the player asks where to go or how to get somewhere, say how far it is and roughly how long it takes (the route, nearest, plan and loot-run tools give rough times jogging and by car; call them rough).',
  'Rules: never claim to know where other players, loot or vehicles are right now; loot data says where things can spawn, not what is there. Do not give exploits, glitches, duping or anything against the game\'s rules. Do not say "because", "always", "definitely", "certainly" or "guarantee". Use British spelling. If the data cannot answer, say so and suggest what the player could check.',
].join('\n\n')

/** A short summary of the topic for the next turn's context. */
export function topicLine(t: Topic): string | null {
  if (!t) return null
  if (t.kind === 'place') return `Last place talked about: ${t.name} (${t.map}).`
  if (t.kind === 'item') return `Last item talked about: ${itemsOn('chernarus').find((i) => i.id === t.id)?.name ?? t.id}.`
  return `Last gun talked about: ${t.id}.`
}

export { answerPlaceQuestion, featureLine }

// --- The conversation loop ------------------------------------------------------------------------

export type ChatBlock =
  | { readonly type: 'text'; readonly text: string }
  | { readonly type: 'tool_use'; readonly id: string; readonly name: string; readonly input: Record<string, unknown> }
  | { readonly type: 'tool_result'; readonly tool_use_id: string; readonly content: string }
export interface ChatMessage {
  readonly role: 'user' | 'assistant'
  readonly content: string | readonly ChatBlock[]
}
export type ChatCall = (messages: readonly ChatMessage[]) => Promise<{ ok: true; content: readonly ChatBlock[]; stop: string | null; costUsd: number } | { ok: false; error: string }>

/**
 * One open question: Claude answers, or asks for tools; the tools run here on the app's data (their cards go
 * to the screen at once), and their results go back, until Claude answers. At most `rounds` calls.
 */
export async function companionTurn(opts: {
  readonly question: string
  readonly history: readonly { readonly question: string; readonly answer: string }[]
  readonly ctx: CompanionContext
  readonly topic: Topic
  readonly profile: GameProfile
  readonly effects: ToolEffects
  readonly chat: ChatCall
  readonly onCard: (card: Card) => void
  readonly rounds?: number
}): Promise<{ ok: true; text: string; costUsd: number; tools: string[]; topic: Topic } | { ok: false; error: string }> {
  let ctx = opts.ctx
  let topic = opts.topic
  const messages: ChatMessage[] = opts.history.slice(-6).flatMap((e) => [
    { role: 'user' as const, content: e.question.slice(0, 1000) },
    { role: 'assistant' as const, content: e.answer.slice(0, 2000) },
  ])
  const facts = [topicLine(topic), ctx.spot ? `The player said they are at ${ctx.spot.label}.` : 'The player has not said where they are.', `Map: ${ctx.map}.`].filter(Boolean).join(' ')
  messages.push({ role: 'user', content: `${opts.question.slice(0, 1000)}\n\n(App: ${facts})` })
  let costUsd = 0
  const tools: string[] = []
  for (let round = 0; round < (opts.rounds ?? 6); round++) {
    const r = await opts.chat(messages)
    if (!r.ok) return r
    costUsd += r.costUsd
    messages.push({ role: 'assistant', content: r.content })
    const calls = r.content.filter((b): b is Extract<ChatBlock, { type: 'tool_use' }> => b.type === 'tool_use')
    if (!calls.length || r.stop !== 'tool_use') {
      const text = r.content.flatMap((b) => (b.type === 'text' ? [b.text] : [])).join(' ').replace(/\*\*|__|^#+\s*/gm, '').replace(/\s+/g, ' ').trim()
      return text ? { ok: true, text, costUsd, tools, topic } : { ok: false, error: 'Empty answer.' }
    }
    const results: ChatBlock[] = calls.map((c) => {
      tools.push(c.name)
      const out = runTool(c.name, c.input, ctx, opts.profile, opts.effects)
      if (out.card) opts.onCard(out.card)
      if (out.topic) topic = out.topic
      if (out.ctx) ctx = out.ctx
      return { type: 'tool_result', tool_use_id: c.id, content: out.text.slice(0, 7000) }
    })
    messages.push({ role: 'user', content: results })
  }
  return { ok: false, error: 'The conversation took too many steps.' }
}
