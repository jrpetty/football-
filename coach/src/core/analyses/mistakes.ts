// Sustained-condition mistakes: rules over visible state ("population at the
// cap for 10 s" = housed). A condition only counts while every value it needs
// is fresh and readable; an unreadable value resets the timer rather than
// being assumed. Each occurrence becomes one event, with its duration tracked.

import type { Condition, MistakeSpec } from '../profile/schema.ts'
import type { FusedValue, GameEvent, VisibleState } from '../types.ts'

export interface MistakeTotals {
  readonly count: number
  readonly totalMs: number
  readonly active: boolean
}

interface Track {
  readonly spec: MistakeSpec
  since: number | null
  firing: boolean
  count: number
  totalMs: number
  matchCount: number
  matchTotalMs: number
  firedThisMatch: boolean
}

export class MistakeDetector {
  private readonly tracks: Track[]
  private context: Readonly<Record<string, string | number>>
  private seq = 0

  constructor(specs: readonly MistakeSpec[], context: Readonly<Record<string, string | number>>) {
    this.tracks = specs.map((spec) => ({
      spec,
      since: null,
      firing: false,
      count: 0,
      totalMs: 0,
      matchCount: 0,
      matchTotalMs: 0,
      firedThisMatch: false,
    }))
    this.context = context
  }

  setContext(context: Readonly<Record<string, string | number>>): void {
    this.context = context
  }

  update(state: VisibleState, now: number): GameEvent[] {
    const out: GameEvent[] = []
    for (const tr of this.tracks) {
      const holds = !state.stale && this.holds(tr.spec, state)
      if (!holds) {
        this.end(tr, now)
        continue
      }
      tr.since ??= holds.at
      if (!tr.firing && now - tr.since >= tr.spec.forMs) {
        if (tr.spec.oncePerMatch && tr.firedThisMatch) continue
        tr.firing = true
        tr.firedThisMatch = true
        tr.count++
        tr.matchCount++
        out.push({
          uid: `${tr.spec.id}#${++this.seq}`,
          ruleId: tr.spec.id,
          label: tr.spec.label,
          category: 'mistake',
          at: now,
          confidence: holds.confidence,
          evidence: `${holds.evidence}${tr.spec.forMs ? ` for ${Math.round((now - tr.since) / 1000)}s` : ''}`,
          source: 'derived',
        })
      }
    }
    return out
  }

  totals(scope: 'session' | 'match' = 'session', now?: number): Record<string, MistakeTotals> {
    return Object.fromEntries(
      this.tracks.map((tr) => {
        const running = tr.firing && tr.since !== null && now !== undefined ? now - tr.since : 0
        const count = scope === 'session' ? tr.count : tr.matchCount
        const totalMs = (scope === 'session' ? tr.totalMs : tr.matchTotalMs) + running
        return [tr.spec.id, { count, totalMs, active: tr.firing }]
      }),
    )
  }

  /** Close running occurrences (end of match or capture). */
  closeAll(now: number): void {
    for (const tr of this.tracks) this.end(tr, now)
  }

  newMatch(): void {
    for (const tr of this.tracks) {
      tr.matchCount = 0
      tr.matchTotalMs = 0
      tr.firedThisMatch = false
    }
  }

  private end(tr: Track, now: number): void {
    if (tr.firing && tr.since !== null) {
      tr.totalMs += now - tr.since
      tr.matchTotalMs += now - tr.since
    }
    tr.firing = false
    tr.since = null
  }

  private holds(spec: MistakeSpec, state: VisibleState): { at: number; confidence: number; evidence: string } | false {
    if (spec.phases && !spec.phases.includes(state.phase)) return false
    const used: FusedValue[] = []
    if (spec.minGameTimeSec !== undefined) {
      const t = state.values.gameTimeSec
      if (!t || typeof t.value !== 'number' || t.value < spec.minGameTimeSec) return false
      used.push(t)
    }
    const parts: string[] = []
    for (const cond of spec.when) {
      const r = this.evaluate(cond, state)
      if (!r) return false
      used.push(...r.used)
      parts.push(r.text)
    }
    return {
      at: Math.max(...used.map((u) => u.observedAt)),
      confidence: Math.min(...used.map((u) => u.confidence)),
      evidence: parts.join(', '),
    }
  }

  private evaluate(c: Condition, state: VisibleState): { used: FusedValue[]; text: string } | null {
    const read = (key: string) => {
      const v = state.values[key]
      return v && !v.conflicted ? v : null
    }
    let left: number | string | boolean
    let leftText: string
    const used: FusedValue[] = []
    if (c.sum) {
      let total = 0
      for (const k of c.sum) {
        const v = read(k)
        if (!v || typeof v.value !== 'number') return null
        used.push(v)
        total += v.value
      }
      left = total
      leftText = `${c.sum.join('+')} = ${total}`
    } else {
      const v = read(c.key!)
      if (!v) return null
      used.push(v)
      left = v.value
      leftText = `${c.key} ${String(v.value)}`
    }
    let right: number | string | boolean
    if (c.ref !== undefined) {
      const v = read(c.ref)
      if (!v) return null
      used.push(v)
      right = v.value
    } else if (c.contextValue !== undefined) {
      const v = this.context[c.contextValue]
      if (v === undefined || v === '') return null
      right = typeof left === 'number' ? Number(v) : v
    } else right = c.value!
    if (!compare(left, c.op, right)) return null
    return { used, text: `${leftText} ${c.op} ${String(right)}` }
  }
}

function compare(a: number | string | boolean, op: Condition['op'], b: number | string | boolean): boolean {
  switch (op) {
    case '==':
      return a === b
    case '!=':
      return a !== b
  }
  if (typeof a !== 'number' || typeof b !== 'number' || Number.isNaN(b)) return false
  switch (op) {
    case '>':
      return a > b
    case '>=':
      return a >= b
    case '<':
      return a < b
    case '<=':
      return a <= b
  }
}
