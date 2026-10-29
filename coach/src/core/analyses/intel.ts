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

import type { CivAgePlan, CivPlanMove, GameProfile, Roster, RosterFaction, RosterUnit } from '../profile/schema.ts'
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
  /** Their landmarks seen on screen. */
  readonly landmarks: readonly string[]
  /** Their likely plans, best supported first, from what the screen showed (never from what it did not). */
  readonly reads: readonly IntelRead[]
  /** Their civilisation's usual plans at their age, when nothing points to one yet. */
  readonly usual: readonly { readonly name: string; readonly signals: string; readonly past: PastSeen | null }[]
  /** How to play the player's own civilisation in their current age. */
  readonly yourPlan: { readonly age: number; readonly ageName: string; readonly playAs: readonly string[]; readonly powerSpikes: readonly string[]; readonly weakWindows: readonly string[] } | null
  /** Their power spikes and weak windows at their age. */
  readonly theirWindows: { readonly powerSpikes: readonly string[]; readonly weakWindows: readonly string[] } | null
}

/** In the player's past games against this faction: in how many its tell-tale signs were seen. */
export interface PastSeen {
  readonly seen: number
  readonly of: number
}

export interface IntelRead {
  readonly id: string
  readonly name: string
  readonly age: number
  /** How often the player met this plan before (from memory), when there is history against this faction. */
  readonly past: PastSeen | null
  /** Matching evidence seen, e.g. "Council Hall", "2 Barracks", "Longbowman". */
  readonly evidence: readonly string[]
  readonly score: number
  readonly timing: string | null
  readonly threat: string
  readonly response: readonly string[]
  readonly punish: string | null
}

/**
 * A plan is called a read once the screen shows this much support — a landmark (3), a unique unit, cue or
 * second Town Center (2), a common unit or building (1 each) — including at least one of the distinctive ones.
 */
export const READ_MIN_SCORE = 2

/** seen: the screen showed their age; fielded: at least the age of what they have; assumed: same as the player's. */
export type AgeBasis = 'seen' | 'fielded' | 'assumed'

export interface IntelNews {
  readonly kind: 'faction' | 'building' | 'unit' | 'age' | 'read' | 'plan' | 'history'
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

/** A civilisation by its id, name or alias ("Order of the Dragon", "order-of-the-dragon", "OotD"). */
export function findFaction(roster: Roster, name: string | null | undefined): RosterFaction | null {
  if (!name) return null
  const q = name.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim()
  if (!q) return null
  return (
    roster.factions.find((f) => [f.id.replace(/-/g, ' '), f.name, ...(f.aliases ?? [])].some((n) => n.toLowerCase().replace(/[^a-z0-9]+/g, ' ').trim() === q)) ??
    roster.factions.find((f) => q.length >= 4 && f.name.toLowerCase().includes(q)) ??
    null
  )
}

export class IntelTracker {
  private readonly roster: Roster
  private readonly spec: NonNullable<GameProfile['intel']>
  private readonly units: Map<string, RosterUnit>
  private readonly unitMatchers: Matcher[]
  private readonly buildingMatchers: Matcher[]
  /** Landmark names of every civilisation (the opponent's are those that count). */
  private readonly landmarkMatchers: Matcher[]
  private seenLandmarks = new Map<string, number>()
  /** Other enemy things seen (free text from the recogniser), for plan cues. */
  private seenCues: string[] = []
  private announcedReads = new Set<string>()
  private announcedPlanAge: number | null = null
  private history: { readonly matches: number; readonly seen: ReadonlyMap<string, number> } | null = null
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
    const landmarkNames = [...new Set(roster.factions.flatMap((f) => (f.landmarks ?? []).map((l) => l.name)))]
    this.landmarkMatchers = landmarkNames.map((n) => matcher('building', `landmark:${n}`, n)).sort(byLength)
    this.playerFaction = this.findFaction(playerFaction)
  }

  /**
   * What the player's past games against this faction showed on the enemy's
   * side: matches, and in how many each name (building, unit, landmark) was
   * seen. Links the reference plans to what this player actually meets.
   */
  setHistory(h: { readonly matches: number; readonly seen: ReadonlyMap<string, number> } | null): void {
    this.history = h
  }

  /** In how many past games a plan's tell-tale signs (landmarks, unique units, civ buildings) were seen; null without history or signs. */
  private pastSeen(m: CivPlanMove): PastSeen | null {
    const h = this.history
    if (!h?.matches) return null
    // A plan's landmark is its surest sign (a staple unique unit shows up in most of a civ's plans); else its unique units and buildings.
    const names = m.signals.landmarks?.length
      ? m.signals.landmarks
      : [
          ...(m.signals.units ?? []).filter((id) => this.units.get(id)?.factions?.length).map((id) => this.units.get(id)!.name),
          ...(m.signals.buildings ?? []).map((id) => this.roster.buildings.find((b) => b.id === id)).filter((b) => b?.factions?.length).map((b) => b!.name),
        ]
    if (!names.length) return null
    return { seen: Math.max(0, ...names.map((n) => h.seen.get(n) ?? 0)), of: h.matches }
  }

  /** The opponent's faction, once recognised. */
  get opponentFaction(): RosterFaction | null {
    return this.faction
  }

  setPlayerFaction(name: string | null): void {
    this.playerFaction = this.findFaction(name)
  }

  findFaction(name: string | null | undefined): RosterFaction | null {
    return findFaction(this.roster, name)
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
    const cue = this.spec.cuesKey ? str(this.spec.cuesKey) : null
    if (cue && !this.seenCues.includes(cue.toLowerCase())) this.seenCues = [...this.seenCues, cue.toLowerCase()].slice(-40)
    let b = str(this.spec.buildingsKey)
    if (b) {
      // Landmarks first (their names can contain building words, e.g. "Abbey of Kings").
      for (const [id] of parseNames(b, this.landmarkMatchers)) {
        const name = id.slice('landmark:'.length)
        if (!this.seenLandmarks.has(name)) this.seenLandmarks.set(name, now)
        b = b.replace(new RegExp(escape(name), 'ig'), ' ')
      }
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
    news.push(...this.readNews())
    return news
  }

  /** New reads (once each) and the player's plan when their age changes. */
  private readNews(): IntelNews[] {
    const out: IntelNews[] = []
    // Only a clear leader is said: when two plans fit what was seen equally, wait for the screen to tell them apart.
    const [best, next] = this.reads()
    if (best && best.score >= READ_MIN_SCORE && (!next || best.score > next.score) && !this.announcedReads.has(best.id)) {
      this.announcedReads.add(best.id)
      // Sentences in order of worth: commentary keeps whole sentences up to its word limit.
      const timing = best.timing ? ` Timing: ${best.timing}.` : ''
      out.push({ kind: 'read', id: best.id, text: sayable(`Read: ${best.name}. ${best.response[0] ?? best.threat}. Seen: ${best.evidence.join(', ')}.${timing}`) })
    }
    const mine = this.playerAge !== null ? this.agePlan(this.playerFaction, this.playerAge) : null
    if (mine && this.announcedPlanAge !== this.playerAge && mine.playAs.length) {
      this.announcedPlanAge = this.playerAge
      out.push({ kind: 'plan', id: `${this.playerFaction!.id}:${mine.age}`, text: sayable(`${this.ageName(mine.age)} as ${this.playerFaction!.name}: ${mine.playAs[0]}`) })
    }
    return out
  }

  /** The age a faction gets a unit: its own early (or late) version when it has one, else the unit's age. */
  ageOf(unitId: string, faction: RosterFaction | null): number | null {
    const u = this.units.get(unitId)
    if (!u) return null
    return (faction && u.ageFor?.[faction.id]) ?? u.age ?? null
  }

  private agePlan(faction: RosterFaction | null, age: number): CivAgePlan | null {
    return faction?.agePlans?.find((p) => p.age === age) ?? null
  }

  /**
   * The opponent's plans at their age (and the age before), scored by what
   * the screen showed: their landmarks (3 each), units (2 each if not every
   * civilisation has them, else 1), buildings
   * (1 each, more when they have several) and cues the recogniser noted (a
   * second Town Center, towers at gold; 2 each, matched on key words).
   */
  reads(): IntelRead[] {
    const f = this.faction
    if (!f?.agePlans?.length) return []
    const age = this.opponentAge()?.age ?? null
    const plans = f.agePlans.filter((p) => age === null || p.age === age || p.age === age - 1 || (p.age === age + 1 && this.landmarkOfAge(f, age + 1)))
    const out: IntelRead[] = []
    for (const plan of plans) {
      for (const m of plan.theirMoves) {
        const evidence: string[] = []
        let score = 0
        // Something only this plan (or civ) shows: without one, generic sightings never add up to a read.
        let distinct = false
        for (const l of m.signals.landmarks ?? []) if (this.seenLandmarks.has(l)) ((score += 3), (distinct = true), evidence.push(l))
        for (const id of m.signals.units ?? []) {
          const u = this.units.get(id)
          // Villagers and scouts are in every game.
          if (!u || !this.seenUnits.has(id) || id === 'scout' || u.classes.includes('economic') || u.classes.includes('workers')) continue
          // A unit only some civilisations have says more than one every civilisation has.
          const unique = !!u.factions?.length
          score += unique ? 2 : 1
          distinct ||= unique
          evidence.push(u.name)
        }
        for (const c of m.signals.cues ?? []) if (this.cueSeen(c)) ((score += 2), (distinct = true), evidence.push(c.replace(/\s*\(unverified\)/, '')))
        const wanted = new Map<string, number>()
        for (const b of m.signals.buildings ?? []) wanted.set(b, (wanted.get(b) ?? 0) + 1)
        for (const [b, n] of wanted) {
          const seen = this.buildings.get(b)?.count ?? 0
          const building = this.roster.buildings.find((x) => x.id === b)
          // Everyone starts with a Town Center: only a second one is news.
          const extraTc = b === 'town-center'
          if (!seen || (extraTc && seen < 2)) continue
          score += extraTc ? 2 : Math.min(seen, n)
          distinct ||= extraTc || !!building?.factions?.length
          evidence.push(seen > 1 ? `${seen} ${building?.name ?? b}` : (building?.name ?? b))
        }
        if (!distinct) score = Math.min(score, READ_MIN_SCORE - 1)
        if (score > 0) out.push({ id: m.id, name: m.name, age: plan.age, past: this.pastSeen(m), evidence, score, timing: m.timing ?? null, threat: m.threat, response: m.response, punish: m.punish ?? null })
      }
    }
    // Their current age first among equals: that is the plan in motion.
    return out.sort((a, b) => b.score - a.score || (age !== null ? Math.abs(a.age - age) - Math.abs(b.age - age) : 0))
  }

  /** A plan cue ("towers at your gold") counts as seen when the recogniser's notes share its key words. */
  private cueSeen(cue: string): boolean {
    const words = keyWords(cue)
    if (!words.length) return false
    const need = words.length === 1 ? 1 : 2
    return this.seenCues.some((seen) => words.filter((w) => seen.includes(w)).length >= need)
  }

  private landmarkOfAge(f: RosterFaction, age: number): boolean {
    return (f.landmarks ?? []).some((l) => l.age === age && this.seenLandmarks.has(l.name))
  }

  private usual(): { name: string; signals: string; past: PastSeen | null }[] {
    const age = this.opponentAge()?.age
    const plan = age ? this.agePlan(this.faction, age) : null
    // With history against this faction, what this player actually meets comes first.
    const share = (p: PastSeen | null) => (p ? p.seen / p.of : -1)
    return (plan?.theirMoves ?? [])
      .map((m) => ({ m, past: this.pastSeen(m) }))
      .sort((a, b) => share(b.past) - share(a.past))
      .slice(0, 3)
      .map(({ m, past }) => ({
        name: m.name,
        past,
        signals: [...(m.signals.landmarks ?? []), ...(m.signals.buildings ?? []).map((b) => this.roster.buildings.find((x) => x.id === b)?.name ?? b), ...(m.signals.units ?? []).map((u) => this.units.get(u)?.name ?? u), ...(m.signals.cues ?? [])]
          .slice(0, 4)
          .join(', '),
      }))
  }

  newMatch(at = Infinity): void {
    if (this.faction || this.buildings.size || this.seenUnits.size) this.previous = this.report()
    this.since = at
    this.faction = null
    this.buildings = new Map()
    this.seenUnits = new Map()
    this.announcedUnits = new Set()
    this.opponentAgeSeen = null
    this.seenLandmarks = new Map()
    this.seenCues = []
    this.announcedReads = new Set()
    this.announcedPlanAge = null
  }

  /** The opponent's age as best known: shown on screen, else at least what they fielded, else the player's own (assumed). */
  opponentAge(): { age: number; basis: AgeBasis } | null {
    if (this.opponentAgeSeen !== null) return { age: this.opponentAgeSeen, basis: 'seen' }
    const ages = [...[...this.seenUnits.keys()].map((id) => this.ageOf(id, this.faction)), ...[...this.buildings.keys()].map((id) => this.roster.buildings.find((b) => b.id === id)?.age)].filter(
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
    if (!this.faction && !this.buildings.size && !this.seenUnits.size && !this.seenLandmarks.size && this.previous) return this.previous
    const name = (id: string) => this.units.get(id)?.name ?? this.roster.buildings.find((b) => b.id === id)?.name ?? id
    const seen = (m: Map<string, { count: number; firstAt: number }>) =>
      [...m].map(([id, v]) => ({ id, name: name(id), count: v.count, firstAt: v.firstAt })).sort((a, b) => b.count - a.count || a.firstAt - b.firstAt)
    const units = seen(this.seenUnits)
    const theirAge = this.opponentAge()
    const likely: IntelReport['likely'][number][] = []
    for (const [bid] of this.buildings) {
      for (const uid of this.producible(bid, this.faction)) {
        if (this.seenUnits.has(uid) || likely.some((l) => l.id === uid)) continue
        const age = this.ageOf(uid, this.faction)
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
      landmarks: [...this.seenLandmarks.keys()],
      reads: this.reads().filter((r) => r.score >= READ_MIN_SCORE),
      usual: this.usual(),
      yourPlan: (() => {
        const p = this.playerAge !== null ? this.agePlan(this.playerFaction, this.playerAge) : null
        return p ? { age: p.age, ageName: this.ageName(p.age), playAs: p.playAs, powerSpikes: p.powerSpikes ?? [], weakWindows: p.weakWindows ?? [] } : null
      })(),
      theirWindows: (() => {
        const a = theirAge ? this.agePlan(this.faction, theirAge.age) : null
        return a ? { powerSpikes: a.powerSpikes ?? [], weakWindows: a.weakWindows ?? [] } : null
      })(),
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
    const inAge = (id: string) => maxAge === null || (this.ageOf(id, faction) ?? 1) <= maxAge
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
        const age = this.ageOf(id, this.playerFaction)
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
      .map((u) => ({ name: u.name, age: this.ageOf(u.id, f) }))
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
      const at = (u: RosterUnit) => this.ageOf(u.id, this.faction) ?? 0
      const later = military(this.producible(id, this.faction)).sort((x, y) => at(x) - at(y))
      if (!later.length || !at(later[0])) return null
      return `They have ${a} ${b.name}: ${list(later.slice(0, 2).map((u) => plural(u.name)), 'or')} from the ${this.ageName(at(later[0]))}.`
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
      if (u && this.ageOf(id, this.faction) === age && !u.classes.includes('workers') && !unlocked.includes(u.name)) unlocked.push(u.name)
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

const STOP = new Set(['their', 'your', 'with', 'from', 'into', 'near', 'early', 'very', 'than', 'that', 'this', 'they', 'unverified', 'more', 'some', 'many', 'before', 'after', 'about', 'around'])

/** Significant words of a cue (4+ letters, singular-ish), for loose matching against what the recogniser noted. */
function keyWords(text: string): string[] {
  return [...new Set(text.toLowerCase().replace(/\(unverified\)/g, '').split(/[^a-z0-9]+/).filter((w) => w.length >= 4 && !STOP.has(w)).map((w) => w.replace(/(ies|es|s)$/, (m) => (m === 'ies' ? 'y' : ''))))]
}

/** Reference text made fit to speak: no "(unverified)" tags or shorthand symbols, a capital and a full stop. */
export function sayable(text: string): string {
  const t = text
    .replace(/\s*\(unverified\)/gi, '')
    // Community shorthand, said in full.
    .replace(/\b(?:vill?s|vill)\b/gi, 'villagers')
    .replace(/\bTCs\b/g, 'Town Centers')
    .replace(/\bTC\b/g, 'Town Center')
    .replace(/\bMAA\b/g, 'Men-at-Arms')
    .replace(/\bvs\.?(?=\s)/gi, 'against')
    .replace(/~\s*/g, 'about ')
    .replace(/(\d)\s*[-–]\s*(\d)/g, '$1 to $2')
    .replace(/\(\s*\+\s*(?=\d)/g, '(plus ')
    .replace(/\s*\+\s*(?=\d)/g, ' plus ')
    .replace(/\s*\+\s*/g, ' and ')
    .replace(/\s*\/\s*/g, ' or ')
    .replace(/\.(\s*\.)+/g, '.')
    .replace(/\s+/g, ' ')
    .replace(/(^|[.!?]\s+)([a-z])/g, (_, a: string, b: string) => a + b.toUpperCase())
    .trim()
  return /[.!?]$/.test(t) ? t : `${t}.`
}

export function plural(name: string): string {
  // "Man-at-Arms (from the Castle Age)": the name is plural, the note is kept as it is.
  const note = /^(.*?\S)(\s*\([^)]*\))$/.exec(name)
  if (note) return plural(note[1]) + note[2]
  // Japanese and Mongol names (Samurai, Mangudai, Yumi Ashigaru) keep their form.
  if (/[iu]$/i.test(name) || /(cavalry|infantry)$/i.test(name)) return name
  if (/man$/i.test(name)) return name.replace(/man$/i, 'men')
  if (/-at-arms$/i.test(name)) return name.replace(/^man/i, 'Men')
  if (/(s|x|ch|sh)$/i.test(name)) return `${name}es`
  return `${name}s`
}

function list(items: readonly string[], joiner = 'and'): string {
  if (items.length <= 1) return items[0] ?? ''
  return `${items.slice(0, -1).join(', ')} ${joiner} ${items[items.length - 1]}`
}
