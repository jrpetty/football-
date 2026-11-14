// Loot runs: "plan a route from Berezino hitting a hospital, a police station
// and military" — the shortest way from the start through one of each kind of
// place asked for (any order), optionally back to the start or on to another
// town, from the pack's map data (Bohemia's own building list). Drawn on the
// map as a line through numbered stops.

import { tripTime } from './travel.ts'
import { bearing, distance, findPlace, km, neighbours, placesIn, type DayzMap, type DayzMapId, type PlaceMatch } from './places.ts'

export type StopKind = 'hospital' | 'medical' | 'police' | 'military' | 'fire' | 'fuel' | 'water' | 'heliCrash' | 'lighthouse' | 'prison'

const STOP_WORDS: readonly (readonly [RegExp, StopKind])[] = [
  [/\bhospitals?\b/, 'hospital'],
  [/\b(medical|medic|clinic|meds|medicine|med tent)\b/, 'medical'],
  [/\b(police|cops?|police station)\b/, 'police'],
  [/\b(military|army|barracks|mil base|military base|tents?)\b/, 'military'],
  [/\bfire ?stations?\b|\bfirefighters?\b/, 'fire'],
  [/\b(fuel|gas station|petrol)\b/, 'fuel'],
  [/\b(water|pump|well)\b/, 'water'],
  [/\b(heli|helicopter|crash ?sites?|heli ?crash)\b/, 'heliCrash'],
  [/\blighthouses?\b/, 'lighthouse'],
  [/\bprison\b/, 'prison'],
]

export const STOP_LABELS: Readonly<Record<StopKind, string>> = {
  hospital: 'Hospital',
  medical: 'Medical',
  police: 'Police station',
  military: 'Military',
  fire: 'Fire station',
  fuel: 'Fuel station',
  water: 'Water pump',
  heliCrash: 'Heli crash spot',
  lighthouse: 'Lighthouse',
  prison: 'Prison',
}

/** A loot run question: "plan a route / loot run from X hitting / via / through …". */
export function isLootRun(question: string): boolean {
  const q = question.toLowerCase()
  return /\b(loot ?run|loot ?route|plan (?:a |me a |my )?(?:route|run|path)|route (?:from|starting)|hit(?:ting)?|via|through|visit(?:ing)?|stopping at|on the way)\b/.test(q) && stopsIn(q).length > 0
}

/** The kinds of place asked for, in the order named, each once. */
export function stopsIn(q: string): StopKind[] {
  const found: { kind: StopKind; at: number }[] = []
  for (const [re, kind] of STOP_WORDS) {
    const m = q.toLowerCase().match(re)
    if (m && m.index !== undefined) found.push({ kind, at: m.index })
  }
  // "hospital" already covers "medical" when both match the same words.
  const kinds = found.sort((a, b) => a.at - b.at).map((f) => f.kind)
  const out = [...new Set(kinds)]
  return out.includes('hospital') ? out.filter((k) => k !== 'medical' || /\b(medical|clinic|meds|medicine|med tent)\b/.test(q.toLowerCase())) : out
}

export interface LootRequest {
  readonly start: PlaceMatch
  readonly stops: readonly StopKind[]
  /** Back to the start, or on to another town at the end. */
  readonly back: boolean
  readonly end: PlaceMatch | null
}

/** Read the request: the start ("from X", or the first town named, or the place the last sign read), the stops, and where it ends. */
export function parseLootRun(question: string, prefer: DayzMapId | null, here: PlaceMatch | null): LootRequest | null {
  if (!isLootRun(question)) return null
  const q = question.toLowerCase()
  const named = placesIn(question, prefer)
  const fromHere = /\bfrom (?:here|where i am|my (?:spot|position))\b/.test(q)
  const startName = q.match(/\b(?:from|starting (?:at|in|from)|start (?:at|in))\s+([a-zЀ-ӿ' -]+?)(?=\s+(?:hitting|via|through|to|and|with|then|visiting|stopping|past|,)|[,.?!]|$)/)
  const start = fromHere ? here : (startName ? findPlace(startName[1], prefer) : null) ?? named[0] ?? here
  if (!start) return null
  const endName = q.match(/\b(?:end(?:ing)? (?:at|in|up in)|finish(?:ing)? (?:at|in)|then (?:on )?to)\s+([a-zЀ-ӿ' -]+?)(?=[,.?!]|$)/)
  const end = endName ? findPlace(endName[1], start.map.id) : null
  return { start, stops: stopsIn(q), back: /\b(and back|back to|return|round trip|loop)\b/.test(q), end: end && end.map.id === start.map.id && end.place.name !== start.place.name ? end : null }
}

export interface LootStop {
  readonly kind: StopKind
  readonly label: string
  readonly x: number
  readonly y: number
  /** The nearest named settlement, and how far it is. */
  readonly near: string | null
  readonly nearM: number
  /** From the previous point. */
  readonly legM: number
  readonly dir: string
}

export interface LootPlan {
  readonly map: DayzMap
  readonly start: { readonly name: string; readonly x: number; readonly y: number }
  readonly stops: readonly LootStop[]
  readonly finish: { readonly name: string; readonly x: number; readonly y: number; readonly legM: number; readonly dir: string } | null
  readonly totalM: number
  /** Kinds asked for that this map does not have. */
  readonly missing: readonly StopKind[]
}

type P = { x: number; y: number }

/** The map's points of a kind. */
export function pointsOf(map: DayzMap, kind: StopKind): P[] {
  const w = map.world
  const pts = (a: readonly (readonly number[])[]) => a.map(([x, y]) => ({ x, y }))
  switch (kind) {
    case 'hospital':
      return w.medical.filter((m) => m[2] === 'hospital').map(([x, y]) => ({ x, y }))
    case 'medical':
      return w.medical.map(([x, y]) => ({ x, y }))
    case 'police':
      return pts(w.police)
    case 'military':
      return pts(w.military)
    case 'fire':
      return pts(w.fire)
    case 'fuel':
      return pts(w.fuel)
    case 'water':
      return pts(w.water)
    case 'heliCrash':
      return pts(w.heliCrash)
    case 'lighthouse':
      return pts(w.lighthouses)
    case 'prison':
      return pts(w.prison)
  }
}

/** Candidates per kind: the closest few to the start and to the end, so the search stays small and exact. */
const CANDIDATES = 6

/**
 * The shortest run: from the start through one place of each kind, in the best order, then back or on to
 * the end. Exact over the candidates (dynamic programming over the kinds visited).
 */
export function planLootRun(req: LootRequest): LootPlan {
  const map = req.start.map
  const start: P = { x: req.start.place.x, y: req.start.place.y }
  const finishAt: P | null = req.end ? { x: req.end.place.x, y: req.end.place.y } : req.back ? start : null
  const kinds = req.stops.filter((k) => pointsOf(map, k).length > 0).slice(0, 7)
  const missing = req.stops.filter((k) => !kinds.includes(k))
  const cands = kinds.map((k) => {
    const all = pointsOf(map, k)
    const byStart = [...all].sort((a, b) => distance(a, start) - distance(b, start)).slice(0, CANDIDATES)
    const byEnd = finishAt ? [...all].sort((a, b) => distance(a, finishAt) - distance(b, finishAt)).slice(0, 2) : []
    return dedupe([...byStart, ...byEnd])
  })
  const n = kinds.length
  // best[mask][i][c]: shortest way from the start visiting the kinds in mask, ending at kind i's candidate c.
  const best = new Map<string, { cost: number; prev: string | null }>()
  const key = (mask: number, i: number, c: number) => `${mask}:${i}:${c}`
  for (let i = 0; i < n; i++) cands[i].forEach((p, c) => best.set(key(1 << i, i, c), { cost: distance(start, p), prev: null }))
  for (let mask = 1; mask < 1 << n; mask++) {
    for (let i = 0; i < n; i++) {
      if (!(mask & (1 << i))) continue
      cands[i].forEach((p, c) => {
        const here = best.get(key(mask, i, c))
        if (!here) return
        for (let j = 0; j < n; j++) {
          if (mask & (1 << j)) continue
          cands[j].forEach((q, d) => {
            const k2 = key(mask | (1 << j), j, d)
            const cost = here.cost + distance(p, q)
            const cur = best.get(k2)
            if (!cur || cost < cur.cost) best.set(k2, { cost, prev: key(mask, i, c) })
          })
        }
      })
    }
  }
  const full = (1 << n) - 1
  let end: { k: string; cost: number } | null = null
  for (let i = 0; i < n; i++)
    cands[i].forEach((p, c) => {
      const b = best.get(key(full, i, c))
      if (!b) return
      const cost = b.cost + (finishAt ? distance(p, finishAt) : 0)
      if (!end || cost < end.cost) end = { k: key(full, i, c), cost }
    })
  // Walk back to the order.
  const order: { kind: StopKind; p: P }[] = []
  let k: string | null = n ? (end as { k: string; cost: number } | null)?.k ?? null : null
  while (k) {
    const [, i, c] = k.split(':').map(Number)
    order.unshift({ kind: kinds[i], p: cands[i][c] })
    k = best.get(k)!.prev
  }
  let prev = start
  let total = 0
  const stops: LootStop[] = order.map(({ kind, p }) => {
    const legM = distance(prev, p)
    const dir = bearing(prev, p)
    total += legM
    prev = p
    const nb = neighbours(map, p, 1)[0]
    return { kind, label: STOP_LABELS[kind], x: Math.round(p.x), y: Math.round(p.y), near: nb?.place.name ?? null, nearM: nb?.m ?? 0, legM, dir }
  })
  let finish: LootPlan['finish'] = null
  if (finishAt) {
    const legM = distance(prev, finishAt)
    total += legM
    finish = { name: req.end?.place.name ?? req.start.place.name, x: finishAt.x, y: finishAt.y, legM, dir: bearing(prev, finishAt) }
  }
  return { map, start: { name: req.start.place.name, ...start }, stops, finish, totalM: total, missing }
}

function dedupe(ps: P[]): P[] {
  const out: P[] = []
  for (const p of ps) if (!out.some((q) => distance(p, q) < 30)) out.push(p)
  return out
}

/** The run in words: each stop, where it is, how far from the last; the total. */
export function lootRunLine(plan: LootPlan): string {
  if (!plan.stops.length) return `${plan.map.name} has none of those in the game's files.`
  const parts = plan.stops.map((s, i) => `${i + 1}. ${s.label} ${s.near ? (s.nearM < 400 ? `in ${s.near}` : `by ${s.near}`) : ''}, ${km(s.legM)} ${s.dir}`.replace(/ ,/, ','))
  const tail = plan.finish ? ` Then ${plan.finish.name === plan.start.name ? 'back to' : 'on to'} ${plan.finish.name}, ${km(plan.finish.legM)} ${plan.finish.dir}.` : ''
  const missing = plan.missing.length ? ` ${plan.map.name} has no ${plan.missing.map((k) => STOP_LABELS[k].toLowerCase()).join(' or ')} in the game's files.` : ''
  return `Loot run from ${plan.start.name}, ${km(plan.totalM)} in all (straight lines; ${tripTime(plan.totalM)}, not counting looting): ${parts.join('; ')}.${tail}${missing} Open it on the Map to see the line.`
}

/** The run as points for the map: start, stops, finish. */
export function lootRunPath(plan: LootPlan): { x: number; y: number; label: string }[] {
  return [
    { x: plan.start.x, y: plan.start.y, label: plan.start.name },
    ...plan.stops.map((s, i) => ({ x: s.x, y: s.y, label: `${i + 1} ${s.label}` })),
    ...(plan.finish && plan.finish.name !== plan.start.name ? [{ x: plan.finish.x, y: plan.finish.y, label: plan.finish.name }] : plan.finish ? [{ x: plan.finish.x, y: plan.finish.y, label: '' }] : []),
  ]
}

