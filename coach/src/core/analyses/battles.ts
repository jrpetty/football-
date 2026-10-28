// Battles (strategy games): a fight is a run of frames where the vision model
// sees the player's army fighting, each tagged with the circumstances (where,
// matchup, formation, siege…) and how it looks (winning, losing, even). The
// fight ends after a gap with no fight frames. Its outcome combines the last
// visible trend with the change in the player's army size (population minus
// villagers): neither alone is trusted. Each settled fight becomes a "won" or
// "lost" event carrying the most common reading of each circumstance, so the
// fight factors can compare fights won and lost. Unclear fights are logged,
// never forced into a result.

import type { BattleSpec } from '../profile/schema.ts'
import type { GameEvent, Observation, VisibleState } from '../types.ts'

/** Losing this share of the army counts as a lost fight unless the last read said winning. */
export const LOST_DROP = 0.4
/** A won fight must not cost more than this share of the army. */
export const WON_MAX_DROP = 0.5
/** Below this share of the starting army, a lost fight was fought to the end, not pulled back from. */
export const WIPED_SHARE = 0.25
/** How far back the army size before a fight is read. */
const BEFORE_MS = 20_000

export interface BattleRecord {
  readonly startAt: number
  readonly endAt: number
  /** Game clock at the start, if readable. */
  readonly gameSec: number | null
  readonly frames: number
  readonly outcome: 'won' | 'lost' | 'unclear'
  readonly armyBefore: number | null
  readonly armyLow: number | null
  readonly lastTrend: string | null
  readonly attributes: Readonly<Record<string, string>>
}

interface Open {
  startAt: number
  lastAt: number
  gameSec: number | null
  frames: number
  armyBefore: number | null
  armyLow: number | null
  trends: string[]
  readings: Map<string, string[]>
}

export class BattleTracker {
  private readonly spec: BattleSpec | null
  private readonly timeKey: string | null
  private open: Open | null = null
  private army: { at: number; v: number }[] = []
  private log: BattleRecord[] = []
  private seq = 0

  constructor(spec: BattleSpec | null, timeKey: string | null) {
    this.spec = spec
    this.timeKey = timeKey
  }

  /**
   * Feed one batch: every fight observation (before any event de-duplication)
   * and the fused state. Returns the won/lost events of fights that ended.
   */
  update(observations: readonly Observation[], visible: VisibleState, now: number): GameEvent[] {
    const spec = this.spec
    if (!spec) return []
    const out: GameEvent[] = []
    // A fight with no frames for the gap is over: close it before this reading counts against it.
    if (this.open && now - this.open.lastAt > spec.gapMs && !observations.some((o) => o.kind === 'event' && o.eventType === spec.event && o.timestampMs - this.open!.lastAt <= spec.gapMs)) {
      out.push(...this.close())
    }
    const army = this.armySize(visible)
    if (army !== null) {
      this.army.push({ at: now, v: army })
      this.army = this.army.filter((a) => a.at >= now - BEFORE_MS * 3)
      if (this.open) this.open.armyLow = this.open.armyLow === null ? army : Math.min(this.open.armyLow, army)
    }
    for (const obs of observations) {
      if (obs.kind !== 'event' || obs.eventType !== spec.event) continue
      const at = obs.timestampMs
      if (this.open && at - this.open.lastAt > spec.gapMs) out.push(...this.close())
      const o = (this.open ??= this.start(at, visible))
      o.lastAt = Math.max(o.lastAt, at)
      o.frames++
      for (const [k, v] of Object.entries(obs.attributes ?? {})) {
        if (typeof v !== 'string' || v === 'unknown') continue
        if (k === spec.trendAttribute) o.trends.push(v)
        else o.readings.set(k, [...(o.readings.get(k) ?? []), v])
      }
    }
    // A fight also ends when play stops (menu, round or match end) or after the gap.
    const playing = visible.phase === 'round' || visible.phase === 'combat' || visible.phase === 'unknown'
    if (this.open && (!playing || now - this.open.lastAt > spec.gapMs)) out.push(...this.close())
    return out
  }

  /** Close any open fight (match end, stop). */
  flush(): GameEvent[] {
    return this.open ? this.close() : []
  }

  newMatch(): void {
    this.open = null
    this.army = []
  }

  battles(): readonly BattleRecord[] {
    return this.log
  }

  private start(at: number, visible: VisibleState): Open {
    const before = this.army.filter((a) => a.at >= at - BEFORE_MS && a.at <= at).map((a) => a.v)
    const t = this.timeKey ? visible.values[this.timeKey] : undefined
    return {
      startAt: at,
      lastAt: at,
      gameSec: t && typeof t.value === 'number' ? t.value : null,
      frames: 0,
      armyBefore: before.length ? Math.max(...before) : null,
      armyLow: null,
      trends: [],
      readings: new Map(),
    }
  }

  private armySize(visible: VisibleState): number | null {
    const spec = this.spec!
    if (visible.stale) return null
    const read = (key: string) => {
      const v = visible.values[key]
      return v && typeof v.value === 'number' && !v.conflicted ? v.value : null
    }
    const total = read(spec.army.total)
    const minus = spec.army.minus ? read(spec.army.minus) : 0
    return total === null || minus === null ? null : Math.max(0, total - minus)
  }

  private close(): GameEvent[] {
    const o = this.open!
    this.open = null
    const spec = this.spec!
    const attributes: Record<string, string> = {}
    for (const [k, vs] of o.readings) attributes[k] = mode(vs)
    const last = o.trends.at(-1) ?? null
    const low = o.armyLow === null || o.armyBefore === null ? null : Math.min(o.armyLow, o.armyBefore)
    const drop = o.armyBefore && low !== null ? (o.armyBefore - low) / o.armyBefore : null
    let outcome: BattleRecord['outcome'] = 'unclear'
    if (o.frames >= spec.minFrames) {
      if (last === 'losing' || (drop !== null && drop >= LOST_DROP && last !== 'winning')) outcome = 'lost'
      else if (last === 'winning' && (drop === null || drop < WON_MAX_DROP)) outcome = 'won'
    }
    if (outcome === 'lost' && o.armyBefore && low !== null) attributes.ending = low / o.armyBefore < WIPED_SHARE ? 'fought_to_the_end' : 'pulled_back'
    const record: BattleRecord = { startAt: o.startAt, endAt: o.lastAt, gameSec: o.gameSec, frames: o.frames, outcome, armyBefore: o.armyBefore, armyLow: low, lastTrend: last, attributes }
    this.log.push(record)
    if (outcome === 'unclear') return []
    const army = o.armyBefore !== null && low !== null ? `army ${o.armyBefore} → ${low}` : 'army size not readable'
    return [
      {
        uid: `battle#${++this.seq}`,
        ruleId: outcome === 'won' ? spec.wonEvent : spec.lostEvent,
        label: outcome === 'won' ? 'Fight won' : 'Fight lost',
        category: 'outcome',
        at: o.lastAt,
        confidence: 0.7,
        evidence: `fight on screen in ${o.frames} frames · ${army} · last read: ${last ?? 'unclear'}`,
        attributes,
      },
    ]
  }
}

function mode(values: readonly string[]): string {
  const counts = new Map<string, number>()
  for (const v of values) counts.set(v, (counts.get(v) ?? 0) + 1)
  // Ties go to the later reading: the fight as it ended.
  let best = values[values.length - 1]
  for (const [v, n] of counts) if (n > (counts.get(best) ?? 0)) best = v
  return best
}
