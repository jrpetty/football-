// Scouting intel (strategy games). When the player's screen shows the
// opponent's buildings or army — while scouting, in a fight, on the loading
// screen — the vision model names them (from the pack's roster). This module
// keeps what was seen, works out what each scouted production building can
// make for the opponent's civilisation (unique units replace standard ones;
// some civilisations lack units), and what beats it with the player's own
// civilisation. Only what was on the player's screen is used: nothing in the
// fog of war is guessed, and "likely" means "this building can make it".

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
  /** Units the scouted buildings can make for this civilisation, not yet seen. */
  readonly likely: readonly { readonly id: string; readonly name: string; readonly from: string }[]
  /** For each enemy unit type seen (most first): what the player can build against it. */
  readonly counters: readonly { readonly against: string; readonly build: readonly string[]; readonly why: string | null }[]
}

export interface IntelNews {
  readonly kind: 'faction' | 'building' | 'unit'
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
      return v && typeof v.value === 'string' && !v.conflicted ? v.value : null
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

  newMatch(): void {
    if (this.faction || this.buildings.size || this.seenUnits.size) this.previous = this.report()
    this.faction = null
    this.buildings = new Map()
    this.seenUnits = new Map()
    this.announcedUnits = new Set()
  }

  /** This match's intel, or the last match's while nothing is known yet. */
  report(): IntelReport {
    if (!this.faction && !this.buildings.size && !this.seenUnits.size && this.previous) return this.previous
    const name = (id: string) => this.units.get(id)?.name ?? this.roster.buildings.find((b) => b.id === id)?.name ?? id
    const seen = (m: Map<string, { count: number; firstAt: number }>) =>
      [...m].map(([id, v]) => ({ id, name: name(id), count: v.count, firstAt: v.firstAt })).sort((a, b) => b.count - a.count || a.firstAt - b.firstAt)
    const units = seen(this.seenUnits)
    const likely: { id: string; name: string; from: string }[] = []
    for (const [bid] of this.buildings) {
      for (const uid of this.producible(bid, this.faction)) {
        if (!this.seenUnits.has(uid) && !likely.some((l) => l.id === uid)) likely.push({ id: uid, name: name(uid), from: name(bid) })
      }
    }
    return {
      faction: this.faction ? { id: this.faction.id, name: this.faction.name, status: this.faction.status } : null,
      buildings: seen(this.buildings),
      units,
      likely,
      counters: units.slice(0, 4).map((x) => ({ against: x.name, build: this.answersTo(x.id).map((id) => name(id)), why: this.units.get(x.id)?.why ?? null })),
    }
  }

  /** What a building can make for a faction: standard units (replaced or removed as the faction does) plus its unique units. */
  producible(buildingId: string, faction: RosterFaction | null): string[] {
    const out: string[] = []
    for (const u of this.roster.units) {
      if (u.building !== buildingId) continue
      if (u.factions?.length) {
        if (faction && u.factions.includes(faction.id)) out.push(u.id)
        continue
      }
      if (u.id === 'villager') continue
      if (faction?.lacks?.includes(u.id)) continue
      out.push(faction?.replaces?.[u.id] ?? u.id)
    }
    return [...new Set(out)]
  }

  /** Units the player can make that beat this one (standard units, adjusted for the player's civilisation). */
  answersTo(unitId: string): string[] {
    const u = this.units.get(unitId)
    if (!u) return []
    const mine = this.playerFaction
    const ok = (x: RosterUnit) => (!x.factions?.length || (mine !== null && x.factions.includes(mine.id))) && !mine?.lacks?.includes(x.id)
    const out: string[] = []
    for (const ref of u.counteredBy) {
      const direct = this.units.get(ref)
      const cands = direct ? [direct] : this.roster.units.filter((x) => x.classes.includes(ref) && !x.factions?.length).slice(0, 2)
      for (const c of cands) {
        if (!ok(c)) continue
        const id = mine?.replaces?.[c.id] ?? c.id
        if (!out.includes(id)) out.push(id)
      }
    }
    return out.slice(0, 3)
  }

  private factionLine(f: RosterFaction): string {
    const uniques = f.uniqueUnits.map((id) => this.units.get(id)?.name).filter(Boolean).slice(0, 3)
    return `Opponent: ${f.name}.${uniques.length ? ` Watch for ${list(uniques as string[])}.` : ''}`
  }

  private buildingLine(id: string): string | null {
    const b = this.roster.buildings.find((x) => x.id === id)
    if (!b) return null
    const can = this.producible(id, this.faction).map((u) => this.units.get(u)!).filter((u) => u && !u.classes.includes('economic'))
    if (!can.length) return null
    const names = can.slice(0, 3).map((u) => plural(u.name))
    const beat = [...new Set(can.slice(0, 2).flatMap((u) => this.answersTo(u.id)))].slice(0, 2).map((x) => plural(this.units.get(x)?.name ?? x))
    return `They have a ${b.name}: expect ${list(names, 'or')}.${beat.length ? ` ${list(beat)} answer them.` : ''}`
  }

  private unitLine(id: string): string {
    const u = this.units.get(id)!
    const beat = this.answersTo(id).map((x) => plural(this.units.get(x)?.name ?? x))
    return `They're making ${plural(u.name)}.${beat.length ? ` ${list(beat.slice(0, 2))} beat them.` : ''}`
  }
}

export function plural(name: string): string {
  if (/man$/i.test(name)) return name.replace(/man$/i, 'men')
  if (/-at-arms$/i.test(name)) return name.replace(/^man/i, 'Men')
  if (/(s|x|ch|sh)$/i.test(name)) return `${name}es`
  return `${name}s`
}

function list(items: readonly string[], joiner = 'and'): string {
  if (items.length <= 1) return items[0] ?? ''
  return `${items.slice(0, -1).join(', ')} ${joiner} ${items[items.length - 1]}`
}
