// Scouting intel (strategy games). When the player's screen shows the
// opponent's buildings or army — while scouting, in a fight, on the loading
// screen — the vision model names them (from the pack's roster). This module
// keeps what was seen, works out what each scouted production building can
// make for the opponent's civilisation (unique units replace standard ones;
// some civilisations lack units), and what beats it with the player's own
// civilisation. Only what was on the player's screen is used: nothing in the
// fog of war is guessed, and "likely" means "this building can make it".
//
// Ages matter: a unit is expected only once the opponent can have reached
// its age (Wynguard Footmen need the Imperial Age), and only answers the
// player can build at their own age are suggested. The opponent's age comes
// from the screen when it shows it (an age-up notice, the player list, their
// landmark), else a lower bound from what they have fielded, else the
// player's own age as a working assumption — said as such.

import type { GameProfile, Roster, RosterFaction, RosterUnit } from '../profile/schema.ts'
import type { VisibleState } from '../types.ts'

export interface IntelSeen {
  readonly id: string
  readonly name: string
  /** Most seen at once. */
  readonly count: number
  readonly firstAt: number
}

export interface IntelReport {
  readonly faction: { readonly id: string; readonly name: string; readonly status: RosterFaction['status'] } | null
  readonly buildings: readonly IntelSeen[]
  readonly units: readonly IntelSeen[]
  /** The opponent's age (1-based) and how it is known. */
  readonly opponentAge: { readonly age: number; readonly name: string; readonly basis: AgeBasis } | null
  /** Units the scouted buildings can make for this civilisation, not yet seen; `later` = not before a later age. */
  readonly likely: readonly { readonly id: string; readonly name: string; readonly from: string; readonly age: number | null; readonly later: boolean }[]
  /** For each enemy unit type seen (most first): what the player can build against it now (or from which age). */
  readonly counters: readonly { readonly against: string; readonly build: readonly string[]; readonly why: string | null }[]
}

/** seen: the screen showed their age; fielded: at least the age of what they have; assumed: same as the player's. */
export type AgeBasis = 'seen' | 'fielded' | 'assumed'

export interface IntelNews {
  readonly kind: 'faction' | 'building' | 'unit' | 'age'
  readonly id: string
  readonly text: string
}

/** A unit type seen this many at once is "being made", worth a line. */
const UNIT_NEWS_COUNT = 3

interface Matcher {
  readonly kind: 'unit' | 'building'
  readonly id: string
  readonly re: RegExp
  readonly len: number
}

function escape(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/** "12 Spearmen", "Spearman x12", "Spearmen (12)", "spearmen". */
function matcher(kind: Matcher['kind'], id: string, name: string): Matcher {
  const n = escape(name.toLowerCase()).replace(/[\s-]+/g, '[\\s-]+')
  // Plurals: Spearman → Spearmen, Archer → Archers, Man-at-Arms → Men-at-Arms.
  const plural = n.replace(/man\b/, 'm[ae]n')
  const re = new RegExp(`(?:(\\d+)\\s*(?:x\\s*)?)?\\b(?:${plural}|${n})(?:e?s)?\\b(?:\\s*(?:x|×)\\s*(\\d+)|\\s*\\((\\d+)\\))?`, 'i')
  return { kind, id, re, len: name.length }
}

/** Names found in a free-text list, with counts (1 when no number is given). */
export function parseNames(text: string, matchers: readonly Matcher[]): Map<string, number> {
  const found = new Map<string, number>()
  let rest = ` ${text.toLowerCase()} `
  // Longest names first, so "Horse Archer" is not also read as "Archer".
  for (const m of matchers) {
    let hit: RegExpExecArray | null
    while ((hit = m.re.exec(rest))) {
      const count = Number(hit[1] ?? hit[2] ?? hit[3] ?? 1) || 1
      found.set(m.id, Math.max(found.get(m.id) ?? 0, 0) + count)
      rest = rest.slice(0, hit.index) + ' '.repeat(hit[0].length) + rest.slice(hit.index + hit[0].length)
    }
  }
  return found
}

export class IntelTracker {
  private readonly roster: Roster
  private readonly spec: NonNullable<GameProfile['intel']>
  private readonly units: Map<string, RosterUnit>
  private readonly unitMatchers: Matcher[]
  private readonly buildingMatchers: Matcher[]
  private playerFaction: RosterFaction | null
  private faction: RosterFaction | null = null
  private buildings = new Map<string, { count: number; firstAt: number }>()
  private seenUnits = new Map<string, { count: number; firstAt: number }>()
  private announcedUnits = new Set<string>()
  private previous: IntelReport | null = null
  /** Readings from before the current match started belong to the last one. */
  private since = -Infinity
  /** The opponent's age as last shown on screen (ages only go up). */
  private opponentAgeSeen: number | null = null
  private playerAge: number | null = null

  constructor(roster: Roster, spec: NonNullable<GameProfile['intel']>, playerFaction: string | null) {
    this.roster = roster
    this.spec = spec
    this.units = new Map(roster.units.map((u) => [u.id, u]))
    const byLength = (a: Matcher, b: Matcher) => b.len - a.len
    this.unitMatchers = roster.units.flatMap((u) => [u.name, ...(u.aliases ?? [])].map((n) => matcher('unit', u.id, n))).sort(byLength)
    this.buildingMatchers = roster.buildings.flatMap((b) => [b.name, ...(b.aliases ?? [])].map((n) => matcher('building', b.id, n))).sort(byLength)
    this.playerFaction = this.findFaction(playerFaction)
  }

  setPlayerFaction(name: string | null): void {
    this.playerFaction = this.findFaction(name)
  }

  findFaction(name: string | null | undefined): RosterFaction | null {
    if (!name) return null
    const q = name.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim()
    if (!q) return null
    return (
      this.roster.factions.find((f) => [f.id.replace(/-/g, ' '), f.name, ...(f.aliases ?? [])].some((n) => n.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim() === q)) ??
      this.roster.factions.find((f) => q.length >= 4 && f.name.toLowerCase().includes(q)) ??
      null
    )
  }

  update(visible: VisibleState, now: number): IntelNews[] {
    if (visible.stale) return []
    const news: IntelNews[] = []
    const str = (key: string) => {
      const v = visible.values[key]
      return v && typeof v.value === 'string' && !v.conflicted && v.observedAt >= this.since ? v.value : null
    }
    const ageOf = (key: string | undefined) => {
      const v = key ? visible.values[key] : undefined
      const i = v && typeof v.value === 'string' && !v.conflicted && v.observedAt >= this.since ? (this.spec.ages ?? []).indexOf(v.value) : -1
      return i >= 0 ? i + 1 : null
    }
    this.playerAge = ageOf(this.spec.playerAgeKey) ?? this.playerAge
    const theirs = ageOf(this.spec.opponentAgeKey)
    if (theirs !== null && theirs > (this.opponentAgeSeen ?? 0)) {
      const first = this.opponentAgeSeen === null
      this.opponentAgeSeen = theirs
      if (!first || theirs > 1) news.push({ kind: 'age', id: String(theirs), text: this.ageLine(theirs) })
    }
    const factionText = str(this.spec.factionKey)
    if (!this.faction && factionText) {
      this.faction = this.findFaction(factionText)
      if (this.faction) news.push({ kind: 'faction', id: this.faction.id, text: this.factionLine(this.faction) })
    }
    const b = str(this.spec.buildingsKey)
    if (b) {
      for (const [id, count] of parseNames(b, this.buildingMatchers)) {
        const cur = this.buildings.get(id)
        if (!cur) {
          this.buildings.set(id, { count, firstAt: now })
          const line = this.buildingLine(id)
          if (line) news.push({ kind: 'building', id, text: line })
        } else cur.count = Math.max(cur.count, count)
      }
    }
    const u = str(this.spec.unitsKey)
    if (u) {
      for (const [id, count] of parseNames(u, this.unitMatchers)) {
        const cur = this.seenUnits.get(id)
        if (!cur) this.seenUnits.set(id, { count, firstAt: now })
        else cur.count = Math.max(cur.count, count)
        if (count >= UNIT_NEWS_COUNT && !this.announcedUnits.has(id)) {
          this.announcedUnits.add(id)
          news.push({ kind: 'unit', id, text: this.unitLine(id) })
        }
      }
    }
    return news
  }

  newMatch(at = Infinity): void {
    if (this.faction || this.buildings.size || this.seenUnits.size) this.previous = this.report()
    this.since = at
    this.faction = null
    this.buildings = new Map()
    this.seenUnits = new Map()
    this.announcedUnits = new Set()
    this.opponentAgeSeen = null
  }

  /** The opponent's age as best known: shown on screen, else at least what they fielded, else the player's own (assumed). */
  opponentAge(): { age: number; basis: AgeBasis } | null {
    if (this.opponentAgeSeen !== null) return { age: this.opponentAgeSeen, basis: 'seen' }
    const ages = [...[...this.seenUnits.keys()].map((id) => this.units.get(id)?.age), ...[...this.buildings.keys()].map((id) => this.roster.buildings.find((b) => b.id === id)?.age)].filter(
      (a): a is number => typeof a === 'number',
    )
    const fielded = ages.length ? Math.max(...ages) : null
    if (this.playerAge !== null && (fielded === null || this.playerAge > fielded)) return { age: this.playerAge, basis: 'assumed' }
    return fielded !== null ? { age: fielded, basis: 'fielded' } : null
  }

  ageName(age: number): string {
    const n = this.spec.ages?.[age - 1]
    return n ? `${n[0].toUpperCase()}${n.slice(1)} Age` : `age ${age}`
  }

  /** This match's intel, or the last match's while nothing is known yet. */
  report(): IntelReport {
    if (!this.faction && !this.buildings.size && !this.seenUnits.size && this.previous) return this.previous
    const name = (id: string) => this.units.get(id)?.name ?? this.roster.buildings.find((b) => b.id === id)?.name ?? id
    const seen = (m: Map<string, { count: number; firstAt: number }>) =>
      [...m].map(([id, v]) => ({ id, name: name(id), count: v.count, firstAt: v.firstAt })).sort((a, b) => b.count - a.count || a.firstAt - b.firstAt)
    const units = seen(this.seenUnits)
    const theirAge = this.opponentAge()
    const likely: IntelReport['likely'][number][] = []
    for (const [bid] of this.buildings) {
      for (const uid of this.producible(bid, this.faction)) {
        if (this.seenUnits.has(uid) || likely.some((l) => l.id === uid)) continue
        const age = this.units.get(uid)?.age ?? null
        likely.push({ id: uid, name: name(uid), from: name(bid), age, later: age !== null && theirAge !== null && age > theirAge.age })
      }
    }
    likely.sort((a, b) => Number(a.later) - Number(b.later) || (a.age ?? 0) - (b.age ?? 0))
    return {
      faction: this.faction ? { id: this.faction.id, name: this.faction.name, status: this.faction.status } : null,
      buildings: seen(this.buildings),
      units,
      opponentAge: theirAge ? { ...theirAge, name: this.ageName(theirAge.age) } : null,
      likely,
      counters: units.slice(0, 4).map((x) => ({
        against: x.name,
        build: this.answers(x.id).map((a) => (a.now ? name(a.id) : `${name(a.id)} (from the ${this.ageName(a.age!)})`)),
        why: this.units.get(x.id)?.why ?? null,
      })),
    }
  }

  /**
   * What a building can make for a faction: standard units (replaced or
   * removed as the faction does) plus its unique units; with `maxAge`, only
   * units available by that age.
   */
  producible(buildingId: string, faction: RosterFaction | null, maxAge: number | null = null): string[] {
    const out: string[] = []
    const inAge = (id: string) => maxAge === null || (this.units.get(id)?.age ?? 1) <= maxAge
    for (const u of this.roster.units) {
      if (u.building !== buildingId) continue
      if (u.factions?.length) {
        if (faction && u.factions.includes(faction.id)) out.push(u.id)
        continue
      }
      if (u.id === 'villager') continue
      const replacement = faction?.replaces?.[u.id]
      if (replacement) out.push(replacement)
      else if (!faction?.lacks?.includes(u.id)) out.push(u.id)
    }
    return [...new Set(out)].filter(inAge)
  }

  /** Units the player can make that beat this one, available now (at the player's age) first. */
  answersTo(unitId: string): string[] {
    return this.answers(unitId)
      .filter((a) => a.now)
      .map((a) => a.id)
  }

  /**
   * Answers with their age: those the player can build now, or — when none
   * can be built yet — the earliest ones, marked with their age. Units made
   * only at a landmark the player may not have built are left out.
   */
  answers(unitId: string): { id: string; age: number | null; now: boolean }[] {
    const all = this.candidateAnswers(unitId)
      .filter((id) => !this.isLandmarkUnit(id))
      .map((id) => {
        const age = this.units.get(id)?.age ?? null
        return { id, age, now: this.playerAge === null || age === null || age <= this.playerAge }
      })
    const now = all.filter((a) => a.now)
    const later = all.filter((a) => !a.now).sort((a, b) => (a.age ?? 0) - (b.age ?? 0))
    // What works now first; the best later answer too, marked with its age.
    if (now.length) return [...now.slice(0, 3), ...later.slice(0, 1)]
    return later.slice(0, 2)
  }

  /** Made at a civilisation-specific building (a landmark), not a standard production building. */
  private isLandmarkUnit(id: string): boolean {
    const u = this.units.get(id)
    const b = u ? this.roster.buildings.find((x) => x.id === u.building) : undefined
    return !!b?.factions?.length
  }

  private candidateAnswers(unitId: string): string[] {
    const u = this.units.get(unitId)
    if (!u) return []
    const mine = this.playerFaction
    // A standard unit the player's civilisation replaces becomes its replacement; one it lacks is skipped.
    const mineOf = (x: RosterUnit): string | null => {
      if (x.factions?.length) return mine !== null && x.factions.includes(mine.id) ? x.id : null
      const r = mine?.replaces?.[x.id]
      if (r) return r
      return mine?.lacks?.includes(x.id) ? null : x.id
    }
    const out: string[] = []
    for (const ref of u.counteredBy) {
      const direct = this.units.get(ref)
      const cands = direct ? [direct] : this.roster.units.filter((x) => x.classes.includes(ref) && !x.factions?.length).slice(0, 2)
      for (const c of cands) {
        const id = mineOf(c)
        if (id && !out.includes(id)) out.push(id)
      }
    }
    return out.slice(0, 6)
  }

  private factionLine(f: RosterFaction): string {
    const uniques = f.uniqueUnits
      .map((id) => this.units.get(id))
      .filter((u): u is RosterUnit => !!u)
      .sort((a, b) => (a.age ?? 9) - (b.age ?? 9))
      .slice(0, 3)
      .map((u) => (u.age ? `${u.name} (${this.shortAge(u.age)})` : u.name))
    return `Opponent: ${f.name}.${uniques.length ? ` Watch for ${list(uniques)}.` : ''}`
  }

  /** "Feudal", "Imperial". */
  shortAge(age: number): string {
    const n = this.spec.ages?.[age - 1]
    return n ? `${n[0].toUpperCase()}${n.slice(1)}` : `age ${age}`
  }

  private buildingLine(id: string): string | null {
    const b = this.roster.buildings.find((x) => x.id === id)
    if (!b) return null
    const military = (ids: string[]) => ids.map((u) => this.units.get(u)!).filter((u) => u && !u.classes.includes('economic') && !u.classes.includes('workers'))
    const age = this.opponentAge()
    const can = military(this.producible(id, this.faction, age?.age ?? null))
    const a = /^[aeiou]/i.test(b.name) ? 'an' : 'a'
    if (!can.length) {
      // Nothing yet at their age: say when it starts to matter.
      const later = military(this.producible(id, this.faction)).sort((x, y) => (x.age ?? 0) - (y.age ?? 0))
      if (!later.length || later[0].age == null) return null
      return `They have ${a} ${b.name}: ${list(later.slice(0, 2).map((u) => plural(u.name)), 'or')} from the ${this.ageName(later[0].age)}.`
    }
    const names = can.slice(0, 3).map((u) => plural(u.name))
    const beat = [...new Set(can.slice(0, 2).flatMap((u) => this.answersTo(u.id)))].slice(0, 2).map((x) => plural(this.units.get(x)?.name ?? x))
    return `They have ${a} ${b.name}: expect ${list(names, 'or')}.${beat.length ? ` ${list(beat)} answer them.` : ''}`
  }

  /** "They reached the Castle Age: Knights and Springalds can come now." */
  private ageLine(age: number): string {
    const unlocked: string[] = []
    const fromBuildings = [...this.buildings.keys()].flatMap((b) => this.producible(b, this.faction, age))
    const fromCiv = this.faction?.uniqueUnits ?? []
    for (const id of [...fromBuildings, ...fromCiv]) {
      const u = this.units.get(id)
      if (u && u.age === age && !u.classes.includes('workers') && !unlocked.includes(u.name)) unlocked.push(u.name)
    }
    return `They reached the ${this.ageName(age)}.${unlocked.length ? ` Now possible: ${list(unlocked.slice(0, 3).map(plural))}.` : ''}`
  }

  private unitLine(id: string): string {
    const u = this.units.get(id)!
    const all = this.answers(id)
    const now = all.filter((a) => a.now).slice(0, 2)
    const later = all.find((a) => !a.now)
    const name = (x: string) => plural(this.units.get(x)?.name ?? x)
    if (!all.length) return `They're making ${plural(u.name)}.`
    if (!now.length) return `They're making ${plural(u.name)}. ${list(all.slice(0, 2).map((a) => name(a.id)), 'or')} beat them once you reach the ${this.ageName(all[0].age!)}.`
    const tail = later && now.length < 2 ? ` ${name(later.id)} from the ${this.ageName(later.age!)}.` : ''
    return `They're making ${plural(u.name)}. ${list(now.map((a) => name(a.id)))} beat them.${tail}`
  }
}

export function plural(name: string): string {
  // Japanese and Mongol names (Samurai, Mangudai, Yumi Ashigaru) keep their form.
  if (/[iu]$/i.test(name)) return name
  if (/man$/i.test(name)) return name.replace(/man$/i, 'men')
  if (/-at-arms$/i.test(name)) return name.replace(/^man/i, 'Men')
  if (/(s|x|ch|sh)$/i.test(name)) return `${name}es`
  return `${name}s`
}

function list(items: readonly string[], joiner = 'and'): string {
  if (items.length <= 1) return items[0] ?? ''
  return `${items.slice(0, -1).join(', ')} ${joiner} ${items[items.length - 1]}`
}
