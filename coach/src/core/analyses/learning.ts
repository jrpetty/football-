// Learning from what the screen showed, across matches. For each side, the
// pack's tracks follow what was seen and when: buildings, units, ages, a
// weapon in hand, how goals were scored. A match's record is filed under what
// the match was (the opponent's civilisation, yours, the map, the mode), and
// later matches of the same kind are summarised: a usual build order (by the
// time each thing was first seen), the usual army mix, and results.
//
// Nothing is guessed. "First seen" is when the player's screen first showed
// it, which can be later than when it was made; what was never on screen is
// simply missing. Records are kept only when the player turned memory on.

import type { GameProfile, LearningTrack, Roster } from '../profile/schema.ts'
import type { GameEvent, RoundResult, VisibleState } from '../types.ts'
import { plural } from './intel.ts'

export interface ScoutItem {
  /** Seconds into the match it was first seen (the game clock when the pack reads one), or null. */
  readonly first: number | null
  /** Most seen at once (list tracks). */
  readonly peak: number
  /** Times seen: readings for list and value tracks, events for event tracks. */
  readonly n: number
  /** Value tracks with a tally: good and bad events while this value was current (e.g. kills and deaths with a weapon). */
  readonly good?: number
  readonly bad?: number
}

export interface MatchScout {
  /** What the match was, e.g. { opponent: 'English', civ: 'French', map: 'Dry Arabia' }. */
  readonly dims: Readonly<Record<string, string>>
  readonly tracks: Readonly<Record<string, Readonly<Record<string, ScoutItem>>>>
}

/** Per track, at most this many names are kept per match. */
const MAX_ITEMS = 40
/** The first buildings seen before this were there from the start. */
const STARTING_SEC = 120

interface Mutable {
  first: number | null
  peak: number
  n: number
  good: number
  bad: number
}

/** "2 Stable, Archery Range x3; 4 Knights" → raw names with counts. */
export function parseList(text: string): Map<string, number> {
  const out = new Map<string, number>()
  for (const part of text.split(/[,;\n]|\band\b/i)) {
    const m = /^\s*(?:(\d+)\s*[x×]?\s+)?(.+?)(?:\s*[x×]\s*(\d+)|\s*\((\d+)\))?\s*$/i.exec(part)
    if (!m || !m[2]) continue
    const name = m[2].replace(/[.:]+$/, '').trim()
    if (!name || name.length > 60) continue
    const n = Number(m[1] ?? m[3] ?? m[4] ?? 1)
    out.set(name, Math.max(out.get(name) ?? 0, Number.isFinite(n) && n > 0 ? Math.min(n, 999) : 1))
  }
  return out
}

/** Singular forms to try for a plural ("Longbowmen" → "Longbowman", "Archeries" → "Archery", "Knights" → "Knight"); the name itself first. */
function singulars(s: string): string[] {
  const out = [s]
  // Names that end in s but are one thing.
  if (/(barracks|ss|us|is|ics|works)$/i.test(s)) return out
  if (/men$/i.test(s)) out.push(s.replace(/men$/i, (m) => (m === 'MEN' ? 'MAN' : 'man')))
  if (/^men-at-arms$/i.test(s)) out.push(s.replace(/^men/i, (m) => (m[0] === 'M' ? 'Man' : 'man')))
  if (/ies$/i.test(s)) out.push(s.replace(/ies$/i, 'y'))
  if (/(ch|sh|x|ss)es$/i.test(s)) out.push(s.replace(/es$/i, ''))
  if (/[a-z]s$/.test(s) && !/ss$/i.test(s)) out.push(s.replace(/s$/, ''))
  return out
}

function capitalise(s: string): string {
  return s ? s[0].toUpperCase() + s.slice(1) : s
}

/**
 * Turns a name read from the screen into the name it is filed under: the
 * roster's when the pack has one; else as read, singular and capitalised
 * ("knights" → "Knight"; "UAV" stays "UAV").
 */
export function nameIndex(roster: Roster | undefined): (raw: string, keepPlural?: boolean) => string {
  const index = new Map<string, string>()
  const add = (key: string, name: string) => {
    const k = key.toLowerCase().replace(/\s+/g, ' ').trim()
    if (k && !index.has(k)) index.set(k, name)
  }
  for (const u of roster?.units ?? []) [u.name, ...(u.aliases ?? [])].forEach((a) => add(a, u.name))
  for (const b of roster?.buildings ?? []) [b.name, ...(b.aliases ?? [])].forEach((a) => add(a, b.name))
  for (const f of roster?.factions ?? []) for (const l of f.landmarks ?? []) add(l.name, l.name)
  return (raw, keepPlural = false) => {
    const tidy = raw.replace(/\s+/g, ' ').trim()
    for (const s of singulars(tidy.toLowerCase())) {
      const hit = index.get(s)
      if (hit) return hit
    }
    const forms = singulars(tidy)
    return capitalise(keepPlural || forms.length < 2 ? tidy : forms[1])
  }
}

/** Follows one match. */
export class ScoutRecorder {
  private readonly tracks: readonly LearningTrack[]
  private readonly clockKey: string | undefined
  private readonly name: (raw: string, keepPlural?: boolean) => string
  private items = new Map<string, Map<string, Mutable>>()
  private current = new Map<string, string>()
  private lastObserved = new Map<string, number>()
  private startedAt: number | null = null

  constructor(profile: GameProfile) {
    this.tracks = profile.learning?.tracks ?? []
    this.clockKey = profile.learning?.clockKey
    this.name = nameIndex(profile.knowledge.roster)
  }

  /** Whether anything was recorded this match. */
  get empty(): boolean {
    return [...this.items.values()].every((m) => m.size === 0)
  }

  observe(visible: VisibleState, now: number): void {
    if (visible.stale || !this.tracks.length) return
    this.startedAt ??= now
    const at = this.clock(visible, now)
    for (const t of this.tracks) {
      if (t.from === 'event') continue
      const v = visible.values[t.key]
      if (!v || v.conflicted || typeof v.value !== 'string' || !v.value.trim()) continue
      // One reading counts once, however long the state keeps it.
      if ((this.lastObserved.get(t.key) ?? -Infinity) >= v.observedAt) continue
      this.lastObserved.set(t.key, v.observedAt)
      if (t.from === 'value') {
        const name = this.tidy(t, v.value)
        this.current.set(t.id, name)
        this.bump(t.id, name, at, 1)
      } else {
        for (const [raw, count] of parseList(v.value)) this.bump(t.id, this.name(raw), at, count)
      }
    }
  }

  event(ev: GameEvent, now: number, visible: VisibleState): void {
    if (!this.tracks.length) return
    this.startedAt ??= now
    const at = this.clock(visible, now)
    for (const t of this.tracks) {
      if (t.from === 'event' && t.key === ev.ruleId) {
        if (t.where && ev.attributes?.[t.where.attribute] !== t.where.value) continue
        const raw = t.attribute ? ev.attributes?.[t.attribute] : ev.ruleId
        if (typeof raw !== 'string' || !raw || raw === 'unknown') continue
        this.bump(t.id, raw, at, 1)
      }
      // Kills and deaths (say) while a weapon was in hand.
      if (t.from === 'value' && t.tally) {
        const cur = this.current.get(t.id)
        const item = cur ? this.items.get(t.id)?.get(cur) : undefined
        if (!item) continue
        if (ev.ruleId === t.tally.good) item.good++
        if (ev.ruleId === t.tally.bad) item.bad++
      }
    }
  }

  /** The match's record, filed under `dims`, or null when nothing was seen. */
  finish(dims: Record<string, string>): MatchScout | null {
    const tracks: Record<string, Record<string, ScoutItem>> = {}
    for (const [id, m] of this.items) {
      if (!m.size) continue
      const t = this.tracks.find((x) => x.id === id)
      tracks[id] = Object.fromEntries(
        [...m].map(([name, x]) => [
          name,
          { first: x.first === null ? null : Math.round(x.first), peak: x.peak, n: x.n, ...(t?.tally ? { good: x.good, bad: x.bad } : {}) },
        ]),
      )
    }
    this.newMatch()
    return Object.keys(tracks).length ? { dims, tracks } : null
  }

  newMatch(): void {
    this.items = new Map()
    this.current = new Map()
    this.startedAt = null
  }

  private tidy(t: LearningTrack, raw: string): string {
    const s = raw.trim().slice(0, 60)
    // A single value (an age, a weapon in hand) is a name as shown, not a count of things.
    return t.kind === 'age' ? s.toLowerCase() : this.name(s, true)
  }

  private clock(visible: VisibleState, now: number): number {
    const v = this.clockKey ? visible.values[this.clockKey] : undefined
    if (v && typeof v.value === 'number' && !v.conflicted) return v.value + Math.max(0, now - v.observedAt) / 1000
    return this.startedAt === null ? 0 : (now - this.startedAt) / 1000
  }

  private bump(trackId: string, name: string, at: number, count: number): void {
    let m = this.items.get(trackId)
    if (!m) this.items.set(trackId, (m = new Map()))
    let item = m.get(name)
    if (!item) {
      if (m.size >= MAX_ITEMS) return
      m.set(name, (item = { first: at, peak: 0, n: 0, good: 0, bad: 0 }))
    }
    item.n++
    item.peak = Math.max(item.peak, count)
    if (item.first === null || at < item.first) item.first = at
  }
}

// --- Across matches --------------------------------------------------------------------------

export interface LearnedItem {
  readonly name: string
  /** Matches (of those with this track) where it was seen. */
  readonly seenIn: number
  readonly share: number
  /** Median first-seen time, seconds. */
  readonly firstSec: number | null
  /** Median of the most seen at once. */
  readonly peak: number | null
  readonly total: number
  readonly good: number
  readonly bad: number
  /** Results of the matches where it was seen. */
  readonly won: number
  readonly lost: number
}

export interface LearnedTrack {
  readonly id: string
  readonly label: string
  readonly side: 'you' | 'them'
  readonly kind: string
  /** Matches with anything on this track. */
  readonly matches: number
  readonly items: readonly LearnedItem[]
  /** The usual order: things seen in at least half the matches, by median first-seen time. */
  readonly order: readonly { readonly name: string; readonly atSec: number }[]
  readonly composition: boolean
  readonly tally: boolean
}

export interface Learned {
  readonly filter: Readonly<Record<string, string>>
  /** "against English on Dry Arabia". */
  readonly filterText: string
  readonly matches: number
  readonly won: number
  readonly lost: number
  readonly tracks: readonly LearnedTrack[]
}

/** Matches of this pack that carry a learning record, optionally only those filed under `filter`. */
function matching(records: readonly LearnSource[], profile: GameProfile, filter: Readonly<Record<string, string>>): LearnSource[] {
  const want = Object.entries(filter).filter(([, v]) => v)
  return records.filter(
    (r) => r.gameProfileId === profile.id && r.scout && want.every(([k, v]) => (r.scout!.dims[k] ?? '').toLowerCase() === v.toLowerCase()),
  )
}

/** What a match record must carry to be learned from. */
export interface LearnSource {
  readonly gameProfileId: string
  readonly result: RoundResult
  readonly scout?: MatchScout
}

/** What the matches filed under `filter` have in common. Null when none match. */
export function learnedFrom(records: readonly LearnSource[], profile: GameProfile, filter: Readonly<Record<string, string>> = {}): Learned | null {
  const spec = profile.learning
  if (!spec) return null
  const recs = matching(records, profile, filter)
  if (!recs.length) return null
  const tracks: LearnedTrack[] = []
  for (const t of spec.tracks) {
    const withTrack = recs.filter((r) => r.scout!.tracks[t.id] && Object.keys(r.scout!.tracks[t.id]).length)
    if (!withTrack.length) continue
    const names = new Set(withTrack.flatMap((r) => Object.keys(r.scout!.tracks[t.id])))
    const items: LearnedItem[] = [...names].map((name) => {
      const seen = withTrack.filter((r) => r.scout!.tracks[t.id][name])
      const xs = seen.map((r) => r.scout!.tracks[t.id][name])
      return {
        name,
        seenIn: seen.length,
        share: seen.length / withTrack.length,
        firstSec: median(xs.map((x) => x.first).filter((x): x is number => x !== null)),
        peak: median(xs.map((x) => x.peak).filter((x) => x > 0)),
        total: xs.reduce((a, x) => a + x.n, 0),
        good: xs.reduce((a, x) => a + (x.good ?? 0), 0),
        bad: xs.reduce((a, x) => a + (x.bad ?? 0), 0),
        won: seen.filter((r) => r.result === 'win').length,
        lost: seen.filter((r) => r.result === 'loss').length,
      }
    })
    items.sort((a, b) => b.seenIn - a.seenIn || b.total - a.total || a.name.localeCompare(b.name))
    // What a side starts with (its first Town Center) is not part of a build order: the buildings seen
    // first, early on, before anything else was.
    const earliest = Math.min(...items.map((i) => i.firstSec ?? Infinity))
    const starting = (i: LearnedItem) => t.kind === 'building' && i.firstSec !== null && i.firstSec < STARTING_SEC && i.firstSec <= earliest
    const order = t.order
      ? items
          .filter((i) => i.share >= 0.5 && i.firstSec !== null && !starting(i))
          .sort((a, b) => a.firstSec! - b.firstSec!)
          .slice(0, 8)
          .map((i) => ({ name: i.name, atSec: i.firstSec! }))
      : []
    tracks.push({ id: t.id, label: t.label, side: t.side, kind: t.kind, matches: withTrack.length, items, order, composition: !!t.composition, tally: !!t.tally })
  }
  return {
    filter,
    filterText: filterText(profile, filter),
    matches: recs.length,
    won: recs.filter((r) => r.result === 'win').length,
    lost: recs.filter((r) => r.result === 'loss').length,
    tracks,
  }
}

/** The values each dimension has taken in the records, most common first (for choosing what to look at). */
export function dimensionValues(records: readonly LearnSource[], profile: GameProfile): { id: string; label: string; values: { value: string; matches: number }[] }[] {
  const recs = matching(records, profile, {})
  return (profile.learning?.dimensions ?? []).map((d) => {
    const counts = new Map<string, number>()
    for (const r of recs) {
      const v = r.scout!.dims[d.id]
      if (v) counts.set(v, (counts.get(v) ?? 0) + 1)
    }
    return { id: d.id, label: d.label, values: [...counts].map(([value, matches]) => ({ value, matches })).sort((a, b) => b.matches - a.matches) }
  })
}

function filterText(profile: GameProfile, filter: Readonly<Record<string, string>>): string {
  const parts: string[] = []
  for (const d of profile.learning?.dimensions ?? []) {
    const v = filter[d.id]
    if (v) parts.push(`${d.phrase ?? d.label} ${v}`)
  }
  return parts.join(', ')
}

function median(xs: number[]): number | null {
  if (!xs.length) return null
  const s = [...xs].sort((a, b) => a - b)
  const m = s.length >> 1
  return s.length % 2 ? s[m] : Math.round((s[m - 1] + s[m]) / 2)
}

export function clock(sec: number): string {
  const s = Math.max(0, Math.round(sec))
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`
}

const SAY: Readonly<Record<string, string>> = { smg: 'SMG', lmg: 'LMG' }

/** Pack values as words: "assault_rifle" → "assault rifle", "smg" → "SMG". */
export function humanName(s: string): string {
  return SAY[s] ?? (s.includes('_') ? s.replace(/_/g, ' ') : s)
}

function ofGames(i: LearnedItem, of: number): string {
  return `${i.seenIn} of ${of}`
}

/**
 * Short sentences for one side ('them' or 'you'), most useful first: the usual
 * order, the usual army or picks, tallies, and what went with wins and losses.
 */
export function learnedLines(l: Learned, side: 'you' | 'them', max = 4): string[] {
  const out: string[] = []
  const who = side === 'them' ? 'Their' : 'Your'
  for (const t of l.tracks.filter((x) => x.side === side)) {
    if (t.kind === 'age') {
      // The starting age is always "seen" at the start; the age-ups are what matter.
      const ups = t.order.filter((o) => o.atSec >= 60)
      if (ups.length) out.push(`${who} usual age-ups: ${ups.map((o) => `${capitalise(o.name)} ${clock(o.atSec)}`).join(', ')}.`)
    } else if (t.order.length >= 2 && !t.composition) out.push(`${t.label} in the usual order (first seen): ${t.order.map((o) => `${o.name} ${clock(o.atSec)}`).join(', ')}.`)
  }
  // An army mix: what is fielded most, and from when (the order, for a mix, is when each first showed up).
  for (const t of l.tracks.filter((x) => x.side === side && x.composition && !x.tally)) {
    const top = t.items.filter((i) => i.share >= 0.34).slice(0, 3)
    const from = (i: LearnedItem) => (t.order.length && i.firstSec !== null ? `, from ${clock(i.firstSec)}` : '')
    if (top.length) out.push(`${who} ${t.label.toLowerCase().replace(/^(their|your) /, '')}: mostly ${top.map((i) => `${t.kind === 'unit' ? plural(i.name) : humanName(i.name)} (${ofGames(i, t.matches)}${from(i)})`).join(', ')}.`)
  }
  for (const t of l.tracks.filter((x) => x.side === side && x.tally)) {
    const top = t.items.filter((i) => i.good + i.bad > 0).slice(0, 3)
    if (top.length) out.push(`${t.label}: ${top.map((i) => `${humanName(i.name)} ${i.good}–${i.bad}`).join(', ')}.`)
  }
  for (const t of l.tracks.filter((x) => x.side === side && x.kind !== 'age' && !x.order.length && !x.composition && !x.tally)) {
    const top = t.items.slice(0, 3)
    if (top.length) out.push(`${t.label}: ${top.map((i) => `${humanName(i.name)} ${i.total}`).join(', ')} (${t.matches} ${t.matches === 1 ? 'game' : 'games'}).`)
  }
  // Results by what was fielded, only when there are a few games to go on.
  if (side === 'you' || side === 'them') {
    const comp = l.tracks.find((x) => x.side === side && x.composition)
    const rated = (comp?.items ?? []).filter((i) => i.won + i.lost >= 2)
    const best = [...rated].sort((a, b) => b.won / (b.won + b.lost) - a.won / (a.won + a.lost))[0]
    const worst = [...rated].sort((a, b) => a.won / (a.won + a.lost) - b.won / (b.won + b.lost))[0]
    const subject = side === 'you' ? 'With your' : 'When they had'
    const say = (i: LearnedItem) => (comp?.kind === 'unit' ? plural(i.name) : humanName(i.name))
    if (best && best.won > best.lost) out.push(`${subject} ${say(best)}: you won ${best.won} of ${best.won + best.lost}.`)
    if (worst && worst !== best && worst.lost > worst.won) out.push(`${subject} ${say(worst)}: you lost ${worst.lost} of ${worst.won + worst.lost}.`)
  }
  return out.slice(0, max)
}

/**
 * A civilisation's reference plans, age by age, with how many of the
 * learned games showed each plan's tell-tale signs (landmarks, unique units,
 * civilisation buildings) on the given side. Links the pack's plans for a
 * civilisation to what was actually seen with or against it.
 */
export function plansSeen(
  profile: GameProfile,
  factionName: string,
  l: Learned,
  side: 'you' | 'them',
): { age: number; id: string; name: string; seen: number; of: number; playAs: readonly string[] }[] {
  const roster = profile.knowledge.roster
  const f = roster?.factions.find((x) => x.name.toLowerCase() === factionName.toLowerCase() || x.id === factionName.toLowerCase())
  if (!roster || !f?.agePlans?.length) return []
  const seen = new Map<string, number>()
  let of = 0
  for (const t of l.tracks.filter((x) => x.side === side)) {
    of = Math.max(of, t.matches)
    for (const i of t.items) seen.set(i.name, Math.max(seen.get(i.name) ?? 0, i.seenIn))
  }
  const unit = new Map(roster.units.map((u) => [u.id, u]))
  const building = new Map(roster.buildings.map((b) => [b.id, b]))
  const out: { age: number; id: string; name: string; seen: number; of: number; playAs: readonly string[] }[] = []
  for (const plan of f.agePlans) {
    for (const m of plan.theirMoves) {
      // A plan's landmark is its surest sign (a staple unique unit shows up in most of a civ's plans); else its unique units and buildings.
      const signs = m.signals.landmarks?.length
        ? m.signals.landmarks
        : [
            ...(m.signals.units ?? []).filter((id) => unit.get(id)?.factions?.length).map((id) => unit.get(id)!.name),
            ...(m.signals.buildings ?? []).filter((id) => building.get(id)?.factions?.length).map((id) => building.get(id)!.name),
          ]
      if (!signs.length) continue
      out.push({ age: plan.age, id: m.id, name: m.name, seen: Math.max(0, ...signs.map((s) => seen.get(s) ?? 0)), of, playAs: plan.playAs })
    }
  }
  return out
}
