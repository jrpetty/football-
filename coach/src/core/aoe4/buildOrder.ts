// Build orders for Age of Empires IV: made from a real game (one of yours, or a
// strong player's from AoE4World), compared step by step with how a match
// really went, and called out as you play against the AI.
//
// A step is something the game records with its time: an age reached, a
// building completed, a unit or technology finished, or the villager count at a
// time. What villagers were sent to which resource is not in the game's record;
// a build can hold the split the player wants (typed in, or taken from a game
// the coach watched, which read the HUD's numbers beside each resource).

import type { Roster } from '../profile/schema.ts'
import type { BuildEvent } from '../truth/aoe4world.ts'
import { itemKey, itemName, nameKey } from './names.ts'
import { isWorkerSplit, MAX_SPLITS, splitLine, type WorkerSplit, type Workers } from './workers.ts'
import { TRACKED_UPGRADES, trackedUpgrade, upgradeTimes } from './upgrades.ts'

export const BUILD_SCHEMA = 1
export const MAX_STEPS = 32

export interface BuildStep {
  /** Game seconds. */
  readonly atSec: number
  readonly kind: 'age' | 'building' | 'unit' | 'upgrade' | 'villagers'
  /** Matching key: itemKey of the icon name, the age ("feudal"), or "villagers". */
  readonly key: string
  readonly name: string
  /** Villager steps: how many by then. Other steps: which one (1 = the first). */
  readonly count: number
}

export interface BuildOrder {
  readonly schema: typeof BUILD_SCHEMA
  readonly id: string
  readonly name: string
  /** The civilisation, as the pack names it. */
  readonly civ: string
  readonly source: 'mine' | 'game' | 'custom'
  /** Where it came from: an AoE4World game. */
  readonly from?: { readonly gameId: number; readonly player: string; readonly rating: number | null; readonly opponentCiv?: string; readonly result?: string | null }
  readonly createdAt: string
  readonly steps: readonly BuildStep[]
  /** Villagers wanted on each resource by a few times (optional). */
  readonly splits?: readonly WorkerSplit[]
  /** Wheelbarrow, Textiles and Blacksmith upgrade times in the whole game it came from (first 20 minutes). */
  readonly upgrades?: Readonly<Record<string, number>>
}

/** The facts a build is made from, or compared with. */
export interface PlayedBuild {
  readonly events: readonly BuildEvent[]
  /** Villagers at game seconds. */
  readonly villagers: Readonly<Record<string, number>>
  readonly ages: Readonly<Record<string, number>>
}

/** Buildings that are not steps of a build (made all game long). */
const NOT_A_STEP = /^(house|farm|farmhouse|palisade.*|.*wall.*|.*gate.*|outpost|wooden_fortress|cistern|aquaeducts|ovoo|pasture|.*_wall)$/
/** Units that are not steps (workers and support). */
const NOT_A_UNIT_STEP = /^(villager|scout|fishing_boat|trade.*|traders?|monk|imam|prelate|scholar|dervish|shaman)$/
/** Production buildings whose second copy is a step too. */
const SECOND_COPY = /^(barracks|archery_range|stable|town_center|siege_workshop|dock)$/

/** A step's matching key: the icon key, or for Wheelbarrow, Textiles and the Blacksmith the upgrade with its tier. */
const stepKey = (e: BuildEvent) => (e.kind === 'upgrade' ? (trackedUpgrade(e.item) ?? itemKey(e.item)) : itemKey(e.item))

let seq = 0
export const buildId = () => `b-${Date.now().toString(36)}-${(++seq).toString(36)}`

/**
 * A build order from a played game: ages, the first of each building (and the second production
 * building), the first of each military unit and technology, and villager counts, up to `untilSec`
 * (by default a minute after the Castle Age, between 8 and 15 minutes).
 */
export function buildFromGame(played: PlayedBuild, opts: { civ: string; name: string; source: BuildOrder['source']; from?: BuildOrder['from']; roster?: Roster | null; untilSec?: number; now?: Date }): BuildOrder {
  const castle = played.ages.castle
  const until = opts.untilSec ?? Math.min(900, Math.max(480, castle ? castle + 60 : 600))
  const steps: BuildStep[] = []
  const seen = new Map<string, number>()
  const events = [...played.events].sort((a, b) => a.t - b.t)
  // The landmark completed as an age is reached is that age-up: named in the age step, not a step of its own.
  const ageLandmark = (t: number) => events.find((x) => x.kind === 'building' && itemKey(x.item).startsWith('landmark_') && Math.abs(x.t - t) <= 3)
  for (const e of events) {
    if (e.t > until) break
    if (e.kind === 'age') {
      const lm = ageLandmark(e.t)
      const via = lm ? itemName('building', lm.item, opts.roster).replace(/ \(landmark\)$/, '') : null
      steps.push({ atSec: e.t, kind: 'age', key: e.item, name: `${itemName('age', e.item)}${via ? ` (${via})` : ''}`, count: 1 })
      continue
    }
    if (e.kind === 'building' && itemKey(e.item).startsWith('landmark_') && events.some((x) => x.kind === 'age' && Math.abs(x.t - e.t) <= 3)) continue
    const key = stepKey(e)
    const n = (seen.get(`${e.kind}:${key}`) ?? 0) + 1
    seen.set(`${e.kind}:${key}`, n)
    if (e.kind === 'building' && (NOT_A_STEP.test(key) || n > (SECOND_COPY.test(key) ? 2 : 1))) continue
    if (e.kind === 'unit' && (NOT_A_UNIT_STEP.test(key) || n > 1)) continue
    if (e.kind === 'upgrade' && n > 1) continue
    const tracked = e.kind === 'upgrade' ? TRACKED_UPGRADES.find((u) => u.id === key) : undefined
    steps.push({ atSec: e.t, kind: e.kind, key, name: tracked?.name ?? itemName(e.kind, e.item, opts.roster), count: n })
  }
  for (const [sec, v] of Object.entries(played.villagers)) if (Number(sec) <= until) steps.push({ atSec: Number(sec), kind: 'villagers', key: 'villagers', name: 'Villagers', count: v })
  steps.sort((a, b) => a.atSec - b.atSec || (a.kind === 'villagers' ? 1 : 0) - (b.kind === 'villagers' ? 1 : 0))
  // Keep every age and villager step; drop the latest other steps beyond the limit.
  while (steps.length > MAX_STEPS) {
    let i = steps.length - 1
    while (i >= 0 && (steps[i].kind === 'age' || steps[i].kind === 'villagers')) i--
    if (i < 0) break
    steps.splice(i, 1)
  }
  const upgrades = upgradeTimes(played.events)
  return {
    schema: BUILD_SCHEMA,
    id: buildId(),
    name: opts.name.slice(0, 80),
    civ: opts.civ,
    source: opts.source,
    ...(opts.from ? { from: opts.from } : {}),
    createdAt: (opts.now ?? new Date()).toISOString(),
    steps,
    ...(Object.keys(upgrades).length ? { upgrades } : {}),
  }
}

export const clock = (sec: number) => `${sec < 0 ? '-' : ''}${Math.floor(Math.abs(sec) / 60)}:${String(Math.round(Math.abs(sec) % 60)).padStart(2, '0')}`

export interface StepCheck {
  readonly step: BuildStep
  /** When it really happened (game seconds), or null when it did not by the end of the comparison. */
  readonly actualSec: number | null
  /** Villager steps: the real count at that time. */
  readonly actualCount: number | null
  /** Seconds late (negative: early). */
  readonly deltaSec: number | null
  readonly status: 'early' | 'on time' | 'late' | 'missing' | 'not known'
}

/** Within this many seconds of the plan is on time. */
const ON_TIME_SEC = 20

/** How a played game went against a build, step by step. */
export function compareBuild(plan: BuildOrder, played: PlayedBuild, opts: { lastSec?: number } = {}): StepCheck[] {
  const counts = new Map<string, number[]>()
  for (const e of played.events) {
    if (e.kind === 'age') continue
    // Upgrades by their tracked key ("melee-damage-2"), and by the icon key builds made before had.
    for (const k of new Set([`${e.kind}:${stepKey(e)}`, `${e.kind}:${itemKey(e.item)}`])) counts.set(k, [...(counts.get(k) ?? []), e.t].sort((a, b) => a - b))
  }
  const last = opts.lastSec ?? Infinity
  return plan.steps.map((step): StepCheck => {
    if (step.kind === 'villagers') {
      const v = played.villagers[String(step.atSec)]
      if (typeof v !== 'number') return { step, actualSec: null, actualCount: null, deltaSec: null, status: 'not known' }
      const d = v - step.count
      return { step, actualSec: step.atSec, actualCount: v, deltaSec: null, status: d <= -2 ? 'late' : d >= 2 ? 'early' : 'on time' }
    }
    const t = step.kind === 'age' ? (played.ages[step.key] ?? null) : (counts.get(`${step.kind}:${step.key}`)?.[step.count - 1] ?? null)
    if (t === null) return { step, actualSec: null, actualCount: null, deltaSec: null, status: last >= step.atSec + 60 ? 'missing' : 'not known' }
    const d = t - step.atSec
    return { step, actualSec: t, actualCount: null, deltaSec: d, status: d > ON_TIME_SEC ? 'late' : d < -ON_TIME_SEC ? 'early' : 'on time' }
  })
}

const ordinal = (n: number) => (n === 1 ? '' : n === 2 ? 'second ' : n === 3 ? 'third ' : `${n}th `)
/** "Stable", "Barracks (second)", "Feudal Age (Council Hall)". */
export const stepLabel = (s: BuildStep) => (s.kind === 'villagers' ? `${s.count} villagers` : s.count > 1 ? `${s.name} (${ordinal(s.count).trim()})` : s.name)

/** The few steps most worth knowing about: ages first, then the biggest delays, villagers short, and steps missed. */
export function buildFindings(checks: readonly StepCheck[], max = 4): string[] {
  const out: { score: number; text: string }[] = []
  for (const c of checks) {
    const s = c.step
    if (s.kind === 'villagers' && c.actualCount !== null && c.actualCount <= s.count - 2) {
      out.push({ score: 50 + (s.count - c.actualCount) * 10, text: `Villagers at ${clock(s.atSec)}: ${c.actualCount}, the build has ${s.count}.` })
    } else if (c.status === 'late' && c.deltaSec !== null) {
      out.push({ score: (s.kind === 'age' ? 1000 : 0) + c.deltaSec, text: `${stepLabel(s)} ${clock(c.deltaSec)} late (${clock(c.actualSec!)}; the build ${clock(s.atSec)}).` })
    } else if (c.status === 'missing' && s.kind !== 'upgrade') {
      out.push({ score: s.kind === 'age' ? 900 : 120, text: `${stepLabel(s)}: not made (the build has it at ${clock(s.atSec)}).` })
    }
  }
  if (!out.length && checks.some((c) => c.status === 'on time')) return ['The build was followed: every step within 20 seconds, or close to it.']
  // One villager line (the furthest behind) is enough.
  const sorted = out.sort((a, b) => b.score - a.score)
  const firstVill = sorted.findIndex((x) => x.text.startsWith('Villagers at'))
  return sorted.filter((x, i) => !x.text.startsWith('Villagers at') || i === firstVill).slice(0, max).map((x) => x.text)
}

/**
 * What a captured match (no AoE4World summary) shows of the build: buildings and units by when the
 * screen first showed them, ages by when the age was first read, villagers at the checkpoints read.
 * First seen can be later than built, so this is a lower bound on how early things were.
 */
export function playedFromScreen(rec: {
  readonly scout?: { readonly tracks: Readonly<Record<string, Readonly<Record<string, { readonly first: number | null }>>>> }
  readonly benchmarks?: Readonly<Record<string, readonly { readonly atSec: number; readonly actual: number }[]>>
  readonly readings?: { readonly changes: readonly (readonly [number, string, string | number])[] }
}): PlayedBuild {
  const events: BuildEvent[] = []
  for (const [track, kind] of [
    ['your-buildings', 'building'],
    ['your-army', 'unit'],
  ] as const) {
    for (const [name, item] of Object.entries(rec.scout?.tracks[track] ?? {})) if (item.first !== null) events.push({ t: item.first, kind, item: nameKey(name) })
  }
  const ages: Record<string, number> = {}
  for (const [t, key, value] of rec.readings?.changes ?? []) if (key === 'age' && typeof value === 'string' && !(value in ages) && value !== 'dark') ages[value] = t
  for (const [name, item] of Object.entries(rec.scout?.tracks['your-age'] ?? {})) {
    const a = name.toLowerCase().replace(/\s*age$/, '')
    if (item.first !== null && !(a in ages) && a !== 'dark') ages[a] = item.first
  }
  const villagers: Record<string, number> = {}
  for (const p of rec.benchmarks?.['villager-curve'] ?? []) villagers[String(p.atSec)] = p.actual
  return { events, ages, villagers }
}

// --- Calling the build as you play ------------------------------------------------------------

/** A step is called this long before its time. */
const LEAD_SEC = 8
/** Steps this close together are called in one line. */
const GROUP_SEC = 20
/** A step whose time passed this long ago is not called any more. */
const STALE_SEC = 25
/** A split is checked within this long after its time. */
const SPLIT_WINDOW_SEC = 20

export interface BuildCall {
  readonly text: string
  readonly topic: string
}

/** Calls the next steps of a build against the game clock, once each. */
export class BuildCaller {
  readonly build: BuildOrder
  private next = 0
  private ended = false
  private readonly splitsDone = new Set<number>()

  constructor(build: BuildOrder) {
    this.build = build
  }

  /** The calls due at this game time, given the villager count and the villagers on each resource on screen now (null when not read). */
  tick(clockSec: number, villagersNow: number | null, workersNow: Workers | null = null): BuildCall[] {
    const steps = this.build.steps
    const out: BuildCall[] = []
    // Skip steps long past (capture started late, or the clock jumped).
    while (this.next < steps.length && steps[this.next].atSec < clockSec - STALE_SEC) this.next++
    if (this.next < steps.length && clockSec >= steps[this.next].atSec - LEAD_SEC) {
      const first = steps[this.next]
      const group: BuildStep[] = []
      while (this.next < steps.length && steps[this.next].atSec <= first.atSec + GROUP_SEC) group.push(steps[this.next++])
      const parts = group.map((s) => {
        if (s.kind === 'villagers') {
          const now = villagersNow !== null && Math.abs(clockSec - s.atSec) <= LEAD_SEC + 2 ? ` (${villagersNow} now)` : ''
          return `${s.count} villagers by ${clock(s.atSec)}${now}`
        }
        if (s.kind === 'age') return `${s.name} by ${clock(s.atSec)}`
        return `${stepLabel(s)} at ${clock(s.atSec)}`
      })
      out.push({ text: `Build: ${parts.join(', then ')}.`, topic: `build:${this.build.id}:${first.atSec}` })
    }
    // The split the build wants, checked once at its time while the numbers are on screen, in a tick
    // with no step call (calls are spaced a few seconds apart).
    for (const s of this.build.splits ?? []) {
      if (out.length || !workersNow || this.splitsDone.has(s.atSec) || clockSec < s.atSec || clockSec > s.atSec + SPLIT_WINDOW_SEC) continue
      this.splitsDone.add(s.atSec)
      const line = splitLine(s, workersNow)
      if (line) out.push({ text: line, topic: `build:${this.build.id}:split:${s.atSec}` })
    }
    if (!out.length && !this.ended && this.next >= steps.length && steps.length && clockSec >= steps[steps.length - 1].atSec + 30) {
      this.ended = true
      out.push({ text: 'End of the build. Keep the Town Center working.', topic: `build:${this.build.id}:end` })
    }
    return out
  }
}

/** The player's saved build orders for one game, and the one to train with for each civilisation. */
export interface BuildLibrary {
  readonly schema: typeof BUILD_SCHEMA
  readonly builds: readonly BuildOrder[]
  /** civilisation → build id. */
  readonly active: Readonly<Record<string, string>>
}

export const MAX_BUILDS = 60
export const emptyLibrary = (): BuildLibrary => ({ schema: BUILD_SCHEMA, builds: [], active: {} })

export function isBuildLibrary(v: unknown): v is BuildLibrary {
  const o = v as BuildLibrary | null
  return !!o && o.schema === BUILD_SCHEMA && Array.isArray(o.builds) && o.builds.length <= MAX_BUILDS && o.builds.every(isBuildOrder) && typeof o.active === 'object' && o.active !== null && Object.values(o.active).every((x) => typeof x === 'string')
}

export function isBuildOrder(v: unknown): v is BuildOrder {
  const o = v as BuildOrder | null
  return (
    !!o &&
    o.schema === BUILD_SCHEMA &&
    typeof o.id === 'string' &&
    o.id.length <= 40 &&
    typeof o.name === 'string' &&
    o.name.length <= 80 &&
    typeof o.civ === 'string' &&
    (o.source === 'mine' || o.source === 'game' || o.source === 'custom') &&
    Array.isArray(o.steps) &&
    o.steps.length <= MAX_STEPS &&
    o.steps.every(
      (s) =>
        s &&
        typeof s.atSec === 'number' &&
        s.atSec >= 0 &&
        s.atSec <= 3600 &&
        ['age', 'building', 'unit', 'upgrade', 'villagers'].includes(s.kind) &&
        typeof s.key === 'string' &&
        typeof s.name === 'string' &&
        s.name.length <= 80 &&
        typeof s.count === 'number',
    ) &&
    (o.splits === undefined || (Array.isArray(o.splits) && o.splits.length <= MAX_SPLITS && o.splits.every(isWorkerSplit))) &&
    (o.upgrades === undefined ||
      (typeof o.upgrades === 'object' && o.upgrades !== null && Object.keys(o.upgrades).length <= 20 && Object.values(o.upgrades).every((t) => typeof t === 'number' && t >= 0 && t <= 3600)))
  )
}
