// Practice plans: the player's two or three most frequent weaknesses, each
// turned into a focus with a target set from their own recent games, a drill,
// and a number of games to play with it. Each game after the plan starts is
// measured for the current focus; when its games are played, the next focus
// starts, and the plan shows before and after.
//
// Measurements come from the coach's own match records (any game it watched)
// and, for Age of Empires IV, from the player's AoE4World history (any public
// game). The same game is counted once.

import type { GameProfile } from '../profile/schema.ts'
import type { MatchRecord } from './matchRecorder.ts'
import { expectedAt, specFor } from './benchmarks.ts'
import type { Aoe4History } from '../aoe4/history.ts'

export const PRACTICE_SCHEMA = 1
/** Games per focus. */
export const GAMES_PER_FOCUS = 3
/** At least this many measured games before a weakness is called one. */
const MIN_GAMES = 3
const RECENT = 10

export interface PracticeResult {
  /** The game: "aoe4world:<id>" or "match:<record id>". */
  readonly key: string
  /** ISO time the game ended (or started, from AoE4World). */
  readonly at: string
  readonly value: number
}

export interface PracticeFocus {
  /** "villagers-10", "feudal", "castle" or "mistake:<id>". */
  readonly id: string
  readonly title: string
  /** Why this was picked, with the numbers. */
  readonly why: string
  readonly drill: string
  readonly unit: 'villagers' | 'time' | 'count'
  readonly better: 'higher' | 'lower'
  readonly baseline: number
  readonly target: number
  readonly games: number
  readonly results: readonly PracticeResult[]
}

export interface PracticePlan {
  readonly schema: typeof PRACTICE_SCHEMA
  readonly profileId: string
  readonly createdAt: string
  readonly foci: readonly PracticeFocus[]
}

export interface Measurement {
  readonly key: string
  readonly at: string
  readonly values: Readonly<Record<string, number>>
}

const median = (xs: readonly number[]): number => {
  const s = [...xs].sort((a, b) => a - b)
  const m = Math.floor(s.length / 2)
  return s.length % 2 ? s[m] : Math.round((s[m - 1] + s[m]) / 2)
}

export const clock = (sec: number) => `${Math.floor(sec / 60)}:${String(Math.round(sec % 60)).padStart(2, '0')}`

/** "34 villagers", "4:35", "2 times". */
export function showValue(unit: PracticeFocus['unit'], v: number): string {
  return unit === 'time' ? clock(v) : unit === 'villagers' ? `${v} villagers` : `${v} ${v === 1 ? 'time' : 'times'}`
}

/** Every measured game, oldest first: the coach's match records and the AoE4World history, each game once. */
export function measurements(records: readonly MatchRecord[], aoe4: Aoe4History | null): Measurement[] {
  const out: Measurement[] = []
  const covered = new Set<number>()
  for (const r of records) {
    const values: Record<string, number> = {}
    const realVill = r.truth?.lines.find((l) => l.what === 'Villagers at 10:00')?.real
    const vill = typeof realVill === 'number' ? realVill : r.benchmarks?.['villager-curve']?.find((p) => p.atSec === 600)?.actual
    if (typeof vill === 'number') values['villagers-10'] = vill
    for (const [age, key] of [
      ['feudal', 'Feudal Age reached'],
      ['castle', 'Castle Age reached'],
    ] as const) {
      const real = r.truth?.lines.find((l) => l.what === key)?.real
      const m = typeof real === 'string' ? /^(\d+):(\d\d)$/.exec(real) : null
      const seen = r.readings?.changes.find(([, k, v]) => k === 'age' && v === age)?.[0]
      const sec = m ? Number(m[1]) * 60 + Number(m[2]) : seen
      if (typeof sec === 'number') values[age] = sec
    }
    for (const [id, m] of Object.entries(r.mistakes)) values[`mistake:${id}`] = m.count
    if (r.truth?.gameId) covered.add(r.truth.gameId)
    out.push({ key: `match:${r.id}`, at: r.endedAt, values })
  }
  for (const g of aoe4?.games ?? []) {
    const f = aoe4?.facts[g.gameId]
    if (!f || covered.has(g.gameId)) continue
    const values: Record<string, number> = {}
    if (typeof f.villagers[600] === 'number') values['villagers-10'] = f.villagers[600]
    if (typeof f.ages.feudal === 'number') values.feudal = f.ages.feudal
    if (typeof f.ages.castle === 'number') values.castle = f.ages.castle
    out.push({ key: `aoe4world:${g.gameId}`, at: new Date(g.startedAt * 1000).toISOString(), values })
  }
  return out.sort((a, b) => a.at.localeCompare(b.at))
}

const recentValues = (ms: readonly Measurement[], id: string) =>
  ms
    .filter((m) => typeof m.values[id] === 'number')
    .slice(-RECENT)
    .map((m) => m.values[id])

/** The weaknesses worth practising, most important first (each with its baseline, target and drill). */
export function suggestFoci(ms: readonly Measurement[], profile: GameProfile, faction: string | null): PracticeFocus[] {
  const out: { score: number; focus: PracticeFocus }[] = []
  const playbook = new Map((profile.knowledge.playbook ?? []).map((p) => [p.id, p]))
  const base = { games: GAMES_PER_FOCUS, results: [] as PracticeResult[] }

  const curve = profile.benchmarks?.find((b) => b.id === 'villager-curve')
  const spec = curve ? specFor(curve, faction) : null
  const vill = recentValues(ms, 'villagers-10')
  if (spec && vill.length >= MIN_GAMES) {
    const b = median(vill)
    const expected = expectedAt(spec, 600)
    if (b <= expected - 3) {
      const target = Math.min(expected, b + 4)
      out.push({
        score: ((expected - b) / expected) * 100,
        focus: {
          ...base,
          id: 'villagers-10',
          title: 'Villagers at 10:00',
          why: `Your usual is ${b} at 10:00 (last ${vill.length} games); ${spec.expectedPhrase} has ${expected}.`,
          drill: `Skirmish against the AI (Hard): keep the Town Center making villagers without a break, one or two queued. Target: ${target} at 10:00.`,
          unit: 'villagers',
          better: 'higher',
          baseline: b,
          target,
        },
      })
    }
  }
  for (const [age, label, slow, step, floor] of [
    ['feudal', 'Feudal Age time', 330, 30, 270],
    ['castle', 'Castle Age time', 900, 60, 720],
  ] as const) {
    const xs = recentValues(ms, age)
    if (xs.length < MIN_GAMES) continue
    const b = median(xs)
    if (b <= slow) continue
    const target = Math.max(floor, b - step)
    out.push({
      score: (b - slow) / (age === 'feudal' ? 6 : 12) + 10,
      focus: {
        ...base,
        id: age,
        title: label,
        why: `Your usual ${age === 'feudal' ? 'Feudal' : 'Castle'} Age is ${clock(b)} (last ${xs.length} games).`,
        drill: `Skirmish against the AI with a build order in the build trainer: start the age-up at the build's time. Target: ${clock(target)}.`,
        unit: 'time',
        better: 'lower',
        baseline: b,
        target,
      },
    })
  }
  // Late age-ups are the Feudal and Castle foci above.
  for (const m of (profile.mistakes ?? []).filter((x) => !/^late-(feudal|castle)$/.test(x.id))) {
    const xs = recentValues(ms, `mistake:${m.id}`)
    // Mistake counts exist only for games the coach watched.
    const watched = ms.filter((x) => x.key.startsWith('match:')).slice(-RECENT)
    if (watched.length < MIN_GAMES) continue
    const counts = watched.map((x) => x.values[`mistake:${m.id}`] ?? 0)
    const avg = counts.reduce((a, b) => a + b, 0) / counts.length
    if (avg < 1 || !xs.length) continue
    const fix = m.playbook ? playbook.get(m.playbook) : undefined
    const target = Math.floor(avg / 2)
    out.push({
      score: avg * (m.severity === 'high' ? 15 : m.severity === 'medium' ? 10 : 5),
      focus: {
        ...base,
        id: `mistake:${m.id}`,
        title: m.label,
        why: `About ${Math.round(avg * 10) / 10} a game over your last ${watched.length} watched games.`,
        drill: `${fix?.drill ?? fix?.fix ?? m.summary.replace(/\{[^}]+\}/g, '').trim()} Target: at most ${target} a game.`,
        unit: 'count',
        better: 'lower',
        baseline: Math.round(avg * 10) / 10,
        target,
      },
    })
  }
  return out.sort((a, b) => b.score - a.score).map((x) => x.focus)
}

export function makePlan(profileId: string, foci: readonly PracticeFocus[], now = new Date()): PracticePlan {
  return { schema: PRACTICE_SCHEMA, profileId, createdAt: now.toISOString(), foci: foci.slice(0, 3).map((f) => ({ ...f, results: [] })) }
}

/** The focus being practised: the first whose games are not all played (null when the plan is done). */
export function currentFocus(plan: PracticePlan): PracticeFocus | null {
  return plan.foci.find((f) => f.results.length < f.games) ?? null
}

/**
 * Adds the games played since the plan started, in order: a game counts for the focus that was current
 * when it was played (after the previous focus's last game), once. Running it again changes nothing.
 */
export function recordGames(plan: PracticePlan, ms: readonly Measurement[]): PracticePlan {
  const used = new Set(plan.foci.flatMap((f) => f.results.map((r) => r.key)))
  const sorted = [...ms].sort((a, b) => a.at.localeCompare(b.at))
  let since = plan.createdAt
  const foci = plan.foci.map((f) => ({ ...f, results: [...f.results] }))
  for (const f of foci) {
    if (f.results.length >= f.games) {
      since = f.results[f.results.length - 1].at
      continue
    }
    const from = f.results.length ? f.results[f.results.length - 1].at : since
    for (const m of sorted) {
      if (f.results.length >= f.games) break
      if (m.at <= from || used.has(m.key)) continue
      const v = m.values[f.id] ?? (f.id.startsWith('mistake:') && m.key.startsWith('match:') ? 0 : undefined)
      if (typeof v !== 'number') continue
      f.results.push({ key: m.key, at: m.at, value: v })
      used.add(m.key)
    }
    if (f.results.length < f.games) break
    since = f.results[f.results.length - 1].at
  }
  return { ...plan, foci }
}

export interface FocusStatus {
  readonly done: boolean
  /** Median of the games played with this focus. */
  readonly after: number | null
  readonly improved: boolean | null
  readonly reachedTarget: boolean | null
  readonly line: string
}

export function focusStatus(f: PracticeFocus): FocusStatus {
  const done = f.results.length >= f.games
  if (!f.results.length) return { done, after: null, improved: null, reachedTarget: null, line: `Not started: ${f.games} games to play. Before: ${showValue(f.unit, f.baseline)}.` }
  const after = median(f.results.map((r) => r.value))
  const improved = f.better === 'higher' ? after > f.baseline : after < f.baseline
  const reached = f.better === 'higher' ? after >= f.target : after <= f.target
  const progress = `${f.results.length} of ${f.games} games`
  return {
    done,
    after,
    improved,
    reachedTarget: reached,
    line: `${progress}: ${showValue(f.unit, after)} against ${showValue(f.unit, f.baseline)} before (target ${showValue(f.unit, f.target)})${reached ? '. Target reached.' : improved ? '. Better, not at the target yet.' : '.'}`,
  }
}

/** What to say at the start of a game: the current focus and its target. */
export function practiceLine(plan: PracticePlan | null): string | null {
  const f = plan ? currentFocus(plan) : null
  if (!f) return null
  return `Practice: ${f.title.toLowerCase()}. Target ${showValue(f.unit, f.target)}. Game ${f.results.length + 1} of ${f.games}.`
}

export function isPracticePlan(v: unknown): v is PracticePlan {
  const o = v as PracticePlan | null
  return (
    !!o &&
    o.schema === PRACTICE_SCHEMA &&
    typeof o.profileId === 'string' &&
    typeof o.createdAt === 'string' &&
    Array.isArray(o.foci) &&
    o.foci.length <= 3 &&
    o.foci.every((f) => f && typeof f.id === 'string' && typeof f.title === 'string' && typeof f.baseline === 'number' && typeof f.target === 'number' && typeof f.games === 'number' && Array.isArray(f.results) && f.results.length <= 20)
  )
}
