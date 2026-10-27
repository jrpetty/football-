// Game profiles: the maintained, per-game knowledge package. A profile says
// where visible HUD elements are, how to read them, which visible transitions
// count as events, which sequences are worth learning from, and — crucially —
// what each game mode permits in terms of live coaching.
//
// Profiles are data (JSON) and may come from users, so they are validated
// structurally before the engine sees them.

import type { EventCategory, BoundaryKind, RoundResult, HsvRange, NormRect } from '../types.ts'

export const PROFILE_SCHEMA_VERSION = 1

export type LiveCoachingStatus = 'not-reviewed' | 'prohibited' | 'approved'

export interface ProfileMode {
  readonly id: string
  readonly label: string
  readonly multiplayer: boolean
  readonly competitive: boolean
  /**
   * Whether the publisher permits live tactical coaching in this mode.
   * 'approved' requires a cited source; OS capture permission is not publisher permission.
   */
  readonly liveCoaching: {
    readonly status: LiveCoachingStatus
    readonly source?: string
    readonly reviewedAt?: string
    readonly note?: string
  }
}

interface SignalBase {
  readonly id: string
  readonly label: string
  readonly region: NormRect
  /** Readings below this confidence are treated as "could not read". Default 0.5. */
  readonly minConfidence?: number
}

export interface BarSignalSpec extends SignalBase {
  readonly kind: 'bar'
  readonly axis: 'horizontal' | 'vertical'
  /** Which end the bar fills from: 'start' = left/bottom. */
  readonly fillFrom: 'start' | 'end'
  readonly fill: HsvRange
  /**
   * Colour of the bar's empty part. Strongly recommended: without it an empty
   * bar cannot be told apart from a bar that is not on screen.
   */
  readonly track?: HsvRange
  /** Median window, in samples. Default 3. */
  readonly smoothing?: number
}

export interface CoverageSignalSpec extends SignalBase {
  readonly kind: 'coverage'
  readonly color: HsvRange
  /** Fraction of matching pixels at which the signal turns on… */
  readonly on: number
  /** …and the lower fraction at which it turns off again (hysteresis). */
  readonly off: number
}

export interface SignatureSignalSpec extends SignalBase {
  readonly kind: 'signature'
  /** A small grayscale reference (cols × rows, values 0..255) of the HUD element. */
  readonly template: { readonly cols: number; readonly rows: number; readonly values: readonly number[] }
  /** Correlation (−1..1) at which it matches, and the lower value at which it stops matching. */
  readonly on: number
  readonly off: number
}

export type SignalSpec = BarSignalSpec | CoverageSignalSpec | SignatureSignalSpec

interface EventRuleBase {
  readonly id: string
  readonly label: string
  readonly category: EventCategory
  readonly signal: string
  /** Minimum time between two firings of this rule. Default 500ms. */
  readonly cooldownMs?: number
  readonly boundary?: BoundaryKind
  readonly result?: RoundResult
}

export interface ChangeRule extends EventRuleBase {
  readonly type: 'drop' | 'rise'
  readonly delta: number
  readonly withinMs: number
}

export interface CrossRule extends EventRuleBase {
  readonly type: 'below' | 'above'
  readonly value: number
  /** The level the signal must return past before the rule can fire again. */
  readonly rearm: number
}

export interface EdgeRule extends EventRuleBase {
  readonly type: 'becomes-true' | 'becomes-false'
  /** The new state must hold this long before the event fires. Default 0. */
  readonly minHoldMs?: number
}

export type EventRuleSpec = ChangeRule | CrossRule | EdgeRule

export interface EpisodeResponseSpec {
  readonly id: string
  readonly event: string
  /** Short label, e.g. "patched up". */
  readonly label: string
  /** Past-tense clause, e.g. "you patched up first". */
  readonly phrase: string
}

export interface EpisodeSpec {
  readonly id: string
  readonly label: string
  readonly trigger: string
  readonly windowMs: number
  readonly responses: readonly EpisodeResponseSpec[]
  readonly noResponse: { readonly label: string; readonly phrase: string }
  readonly negativeOutcomes: readonly string[]
  readonly positiveOutcomes?: readonly string[]
  /** Signals that must stay readable for the episode to count as evidence. */
  readonly visibility: readonly string[]
  readonly phrases: {
    /** e.g. "your health got low" */
    readonly trigger: string
    /** e.g. "you were eliminated" */
    readonly negative: string
    /** Short past participle, e.g. "eliminated" */
    readonly negativeShort: string
  }
}

export interface LiveCueSpec {
  readonly id: string
  readonly trigger: string
  readonly episode: string
  readonly recommend: string
  /** Imperative, ≤ 6 words, e.g. "patch up first". */
  readonly imperative: string
  /** Do not speak a cue about an event older than this. */
  readonly maxAgeMs: number
}

export interface GameProfile {
  readonly schemaVersion: typeof PROFILE_SCHEMA_VERSION
  readonly id: string
  /** Semver. A major bump means older long-term tendencies are not merged. */
  readonly version: string
  readonly name: string
  readonly game: { readonly title: string; readonly publisher?: string; readonly patch: string }
  readonly validation: {
    readonly status: 'reference' | 'community' | 'verified'
    readonly notes: string
    readonly validatedAt?: string
  }
  readonly capture: { readonly aspectRatio: number; readonly notes: string }
  readonly modes: readonly ProfileMode[]
  readonly signals: readonly SignalSpec[]
  readonly events: readonly EventRuleSpec[]
  readonly episodes: readonly EpisodeSpec[]
  readonly liveCues: readonly LiveCueSpec[]
  readonly limitations: readonly string[]
}

// --- Validation ---------------------------------------------------------------

export type ValidationResult = { ok: true; profile: GameProfile } | { ok: false; errors: string[] }

const ID = /^[a-z0-9][a-z0-9-]{0,63}$/
const SEMVER = /^\d+\.\d+\.\d+$/
const CATEGORIES: readonly EventCategory[] = ['context', 'action', 'outcome', 'boundary']
const BOUNDARIES: readonly BoundaryKind[] = ['round-start', 'round-end', 'match-end']
const RESULTS: readonly RoundResult[] = ['win', 'loss', 'draw', 'unknown']

type Obj = Record<string, unknown>

class Checker {
  readonly errors: string[] = []

  obj(v: unknown, path: string): Obj | null {
    if (typeof v !== 'object' || v === null || Array.isArray(v)) {
      this.errors.push(`${path}: expected an object`)
      return null
    }
    return v as Obj
  }

  arr(v: unknown, path: string): unknown[] {
    if (!Array.isArray(v)) {
      this.errors.push(`${path}: expected an array`)
      return []
    }
    return v
  }

  str(v: unknown, path: string, opts: { pattern?: RegExp; optional?: boolean; max?: number } = {}): string | null {
    if (v === undefined && opts.optional) return null
    if (typeof v !== 'string' || v.length === 0) {
      this.errors.push(`${path}: expected a non-empty string`)
      return null
    }
    if (v.length > (opts.max ?? 500)) this.errors.push(`${path}: too long`)
    if (opts.pattern && !opts.pattern.test(v)) this.errors.push(`${path}: "${v}" has an invalid format`)
    return v
  }

  num(v: unknown, path: string, lo: number, hi: number, optional = false): number | null {
    if (v === undefined && optional) return null
    if (typeof v !== 'number' || !Number.isFinite(v) || v < lo || v > hi) {
      this.errors.push(`${path}: expected a number in [${lo}, ${hi}]`)
      return null
    }
    return v
  }

  bool(v: unknown, path: string): boolean | null {
    if (typeof v !== 'boolean') {
      this.errors.push(`${path}: expected true or false`)
      return null
    }
    return v
  }

  oneOf<T extends string>(v: unknown, path: string, options: readonly T[], optional = false): T | null {
    if (v === undefined && optional) return null
    if (typeof v !== 'string' || !options.includes(v as T)) {
      this.errors.push(`${path}: expected one of ${options.join(', ')}`)
      return null
    }
    return v as T
  }

  rect(v: unknown, path: string): void {
    const o = this.obj(v, path)
    if (!o) return
    const x = this.num(o.x, `${path}.x`, 0, 1)
    const y = this.num(o.y, `${path}.y`, 0, 1)
    const w = this.num(o.w, `${path}.w`, 0.001, 1)
    const h = this.num(o.h, `${path}.h`, 0.001, 1)
    if (x !== null && w !== null && x + w > 1.0001) this.errors.push(`${path}: extends past the right edge`)
    if (y !== null && h !== null && y + h > 1.0001) this.errors.push(`${path}: extends past the bottom edge`)
  }

  hsv(v: unknown, path: string): void {
    const o = this.obj(v, path)
    if (!o) return
    this.pair(o.h, `${path}.h`, 0, 360, true)
    this.pair(o.s, `${path}.s`, 0, 1, false)
    this.pair(o.v, `${path}.v`, 0, 1, false)
  }

  private pair(v: unknown, path: string, lo: number, hi: number, wraps: boolean): void {
    const a = this.arr(v, path)
    if (a.length !== 2) {
      this.errors.push(`${path}: expected [min, max]`)
      return
    }
    const p = this.num(a[0], `${path}[0]`, lo, hi)
    const q = this.num(a[1], `${path}[1]`, lo, hi)
    if (!wraps && p !== null && q !== null && p > q) this.errors.push(`${path}: min is greater than max`)
  }
}

export function validateProfile(input: unknown): ValidationResult {
  const c = new Checker()
  const p = c.obj(input, 'profile')
  if (!p) return { ok: false, errors: c.errors }

  if (p.schemaVersion !== PROFILE_SCHEMA_VERSION) {
    c.errors.push(`schemaVersion: expected ${PROFILE_SCHEMA_VERSION}`)
  }
  c.str(p.id, 'id', { pattern: ID })
  c.str(p.version, 'version', { pattern: SEMVER })
  c.str(p.name, 'name', { max: 80 })

  const game = c.obj(p.game, 'game')
  if (game) {
    c.str(game.title, 'game.title', { max: 120 })
    c.str(game.publisher, 'game.publisher', { optional: true, max: 120 })
    c.str(game.patch, 'game.patch', { max: 60 })
  }
  const validation = c.obj(p.validation, 'validation')
  if (validation) {
    c.oneOf(validation.status, 'validation.status', ['reference', 'community', 'verified'] as const)
    c.str(validation.notes, 'validation.notes', { max: 2000 })
    c.str(validation.validatedAt, 'validation.validatedAt', { optional: true, max: 40 })
  }
  const capture = c.obj(p.capture, 'capture')
  if (capture) {
    c.num(capture.aspectRatio, 'capture.aspectRatio', 0.5, 4)
    c.str(capture.notes, 'capture.notes', { max: 1000 })
  }

  // Modes and the competitive-integrity rule.
  const modes = c.arr(p.modes, 'modes')
  if (modes.length === 0) c.errors.push('modes: at least one mode is required')
  const modeIds = new Set<string>()
  modes.forEach((m, i) => {
    const path = `modes[${i}]`
    const o = c.obj(m, path)
    if (!o) return
    const id = c.str(o.id, `${path}.id`, { pattern: ID })
    if (id) {
      if (modeIds.has(id)) c.errors.push(`${path}.id: duplicate "${id}"`)
      modeIds.add(id)
    }
    c.str(o.label, `${path}.label`, { max: 80 })
    c.bool(o.multiplayer, `${path}.multiplayer`)
    c.bool(o.competitive, `${path}.competitive`)
    const lc = c.obj(o.liveCoaching, `${path}.liveCoaching`)
    if (lc) {
      const status = c.oneOf(lc.status, `${path}.liveCoaching.status`, ['not-reviewed', 'prohibited', 'approved'] as const)
      const source = c.str(lc.source, `${path}.liveCoaching.source`, { optional: true, max: 500 })
      const reviewedAt = c.str(lc.reviewedAt, `${path}.liveCoaching.reviewedAt`, { optional: true, max: 40 })
      c.str(lc.note, `${path}.liveCoaching.note`, { optional: true, max: 1000 })
      if (status === 'approved' && (!source || !reviewedAt)) {
        c.errors.push(`${path}.liveCoaching: "approved" requires a cited source and a reviewedAt date`)
      }
    }
  })

  // Signals.
  const signalIds = new Set<string>()
  c.arr(p.signals, 'signals').forEach((s, i) => {
    const path = `signals[${i}]`
    const o = c.obj(s, path)
    if (!o) return
    const id = c.str(o.id, `${path}.id`, { pattern: ID })
    if (id) {
      if (signalIds.has(id)) c.errors.push(`${path}.id: duplicate "${id}"`)
      signalIds.add(id)
    }
    c.str(o.label, `${path}.label`, { max: 80 })
    c.rect(o.region, `${path}.region`)
    c.num(o.minConfidence, `${path}.minConfidence`, 0, 1, true)
    const kind = c.oneOf(o.kind, `${path}.kind`, ['bar', 'coverage', 'signature'] as const)
    if (kind === 'bar') {
      c.oneOf(o.axis, `${path}.axis`, ['horizontal', 'vertical'] as const)
      c.oneOf(o.fillFrom, `${path}.fillFrom`, ['start', 'end'] as const)
      c.hsv(o.fill, `${path}.fill`)
      if (o.track !== undefined) c.hsv(o.track, `${path}.track`)
      c.num(o.smoothing, `${path}.smoothing`, 1, 15, true)
    } else if (kind === 'coverage' || kind === 'signature') {
      const lo = kind === 'coverage' ? 0 : -1
      const on = c.num(o.on, `${path}.on`, lo, 1)
      const off = c.num(o.off, `${path}.off`, lo, 1)
      if (on !== null && off !== null && off >= on) c.errors.push(`${path}: "off" must be below "on" (hysteresis)`)
      if (kind === 'coverage') c.hsv(o.color, `${path}.color`)
      else {
        const t = c.obj(o.template, `${path}.template`)
        if (t) {
          const cols = c.num(t.cols, `${path}.template.cols`, 2, 64)
          const rows = c.num(t.rows, `${path}.template.rows`, 1, 64)
          const values = c.arr(t.values, `${path}.template.values`)
          if (cols !== null && rows !== null && values.length !== cols * rows) {
            c.errors.push(`${path}.template.values: expected ${cols * rows} values`)
          }
          if (values.some((v) => typeof v !== 'number' || v < 0 || v > 255)) {
            c.errors.push(`${path}.template.values: values must be 0..255`)
          }
        }
      }
    }
  })

  // Events.
  const eventIds = new Set<string>()
  c.arr(p.events, 'events').forEach((e, i) => {
    const path = `events[${i}]`
    const o = c.obj(e, path)
    if (!o) return
    const id = c.str(o.id, `${path}.id`, { pattern: ID })
    if (id) {
      if (eventIds.has(id)) c.errors.push(`${path}.id: duplicate "${id}"`)
      eventIds.add(id)
    }
    c.str(o.label, `${path}.label`, { max: 80 })
    const category = c.oneOf(o.category, `${path}.category`, CATEGORIES)
    const signal = c.str(o.signal, `${path}.signal`)
    if (signal && !signalIds.has(signal)) c.errors.push(`${path}.signal: unknown signal "${signal}"`)
    c.num(o.cooldownMs, `${path}.cooldownMs`, 0, 600_000, true)
    const boundary = c.oneOf(o.boundary, `${path}.boundary`, BOUNDARIES, true)
    c.oneOf(o.result, `${path}.result`, RESULTS, true)
    if ((category === 'boundary') !== (boundary !== null)) {
      c.errors.push(`${path}: boundary events need category "boundary" and a "boundary" kind, and only they may have one`)
    }
    const type = c.oneOf(o.type, `${path}.type`, ['drop', 'rise', 'below', 'above', 'becomes-true', 'becomes-false'] as const)
    if (type === 'drop' || type === 'rise') {
      c.num(o.delta, `${path}.delta`, 0.001, 1)
      c.num(o.withinMs, `${path}.withinMs`, 50, 60_000)
    } else if (type === 'below' || type === 'above') {
      const value = c.num(o.value, `${path}.value`, 0, 1)
      const rearm = c.num(o.rearm, `${path}.rearm`, 0, 1)
      if (value !== null && rearm !== null) {
        if (type === 'below' && rearm <= value) c.errors.push(`${path}.rearm: must be above "value" for a "below" rule`)
        if (type === 'above' && rearm >= value) c.errors.push(`${path}.rearm: must be below "value" for an "above" rule`)
      }
    } else if (type) {
      c.num(o.minHoldMs, `${path}.minHoldMs`, 0, 60_000, true)
    }
  })

  // Episodes.
  const episodeResponses = new Map<string, Set<string>>()
  c.arr(p.episodes, 'episodes').forEach((ep, i) => {
    const path = `episodes[${i}]`
    const o = c.obj(ep, path)
    if (!o) return
    const id = c.str(o.id, `${path}.id`, { pattern: ID })
    c.str(o.label, `${path}.label`, { max: 80 })
    const trigger = c.str(o.trigger, `${path}.trigger`)
    if (trigger && !eventIds.has(trigger)) c.errors.push(`${path}.trigger: unknown event "${trigger}"`)
    c.num(o.windowMs, `${path}.windowMs`, 250, 120_000)
    const responses = new Set<string>()
    c.arr(o.responses, `${path}.responses`).forEach((r, j) => {
      const rp = `${path}.responses[${j}]`
      const ro = c.obj(r, rp)
      if (!ro) return
      const rid = c.str(ro.id, `${rp}.id`, { pattern: ID })
      if (rid === 'none') c.errors.push(`${rp}.id: "none" is reserved for "no visible response"`)
      if (rid) responses.add(rid)
      const ev = c.str(ro.event, `${rp}.event`)
      if (ev && !eventIds.has(ev)) c.errors.push(`${rp}.event: unknown event "${ev}"`)
      c.str(ro.label, `${rp}.label`, { max: 60 })
      c.str(ro.phrase, `${rp}.phrase`, { max: 120 })
    })
    const nr = c.obj(o.noResponse, `${path}.noResponse`)
    if (nr) {
      c.str(nr.label, `${path}.noResponse.label`, { max: 60 })
      c.str(nr.phrase, `${path}.noResponse.phrase`, { max: 120 })
    }
    const neg = c.arr(o.negativeOutcomes, `${path}.negativeOutcomes`)
    if (neg.length === 0) c.errors.push(`${path}.negativeOutcomes: at least one outcome event is required`)
    const pos = o.positiveOutcomes === undefined ? [] : c.arr(o.positiveOutcomes, `${path}.positiveOutcomes`)
    for (const ev of [...neg, ...pos]) {
      if (typeof ev !== 'string' || !eventIds.has(ev)) c.errors.push(`${path}: unknown outcome event "${String(ev)}"`)
    }
    for (const s of c.arr(o.visibility, `${path}.visibility`)) {
      if (typeof s !== 'string' || !signalIds.has(s)) c.errors.push(`${path}.visibility: unknown signal "${String(s)}"`)
    }
    const ph = c.obj(o.phrases, `${path}.phrases`)
    if (ph) {
      c.str(ph.trigger, `${path}.phrases.trigger`, { max: 120 })
      c.str(ph.negative, `${path}.phrases.negative`, { max: 120 })
      c.str(ph.negativeShort, `${path}.phrases.negativeShort`, { max: 60 })
    }
    if (id) episodeResponses.set(id, responses)
  })

  // Live cues.
  c.arr(p.liveCues, 'liveCues').forEach((lc, i) => {
    const path = `liveCues[${i}]`
    const o = c.obj(lc, path)
    if (!o) return
    c.str(o.id, `${path}.id`, { pattern: ID })
    const trigger = c.str(o.trigger, `${path}.trigger`)
    if (trigger && !eventIds.has(trigger)) c.errors.push(`${path}.trigger: unknown event "${trigger}"`)
    const episode = c.str(o.episode, `${path}.episode`)
    const recommend = c.str(o.recommend, `${path}.recommend`)
    if (episode && !episodeResponses.has(episode)) c.errors.push(`${path}.episode: unknown episode "${episode}"`)
    else if (episode && recommend && !episodeResponses.get(episode)!.has(recommend)) {
      c.errors.push(`${path}.recommend: "${recommend}" is not a response of episode "${episode}"`)
    }
    const imperative = c.str(o.imperative, `${path}.imperative`, { max: 60 })
    if (imperative && imperative.split(/\s+/).length > 6) c.errors.push(`${path}.imperative: keep live cues to 6 words or fewer`)
    c.num(o.maxAgeMs, `${path}.maxAgeMs`, 100, 10_000)
  })

  c.arr(p.limitations, 'limitations').forEach((l, i) => c.str(l, `limitations[${i}]`, { max: 500 }))

  return c.errors.length ? { ok: false, errors: c.errors } : { ok: true, profile: input as GameProfile }
}

/** Live tactical coaching is permitted only for non-competitive modes or modes with documented approval. */
export function modeAllowsLiveCoaching(mode: ProfileMode | null): boolean {
  if (!mode) return false
  if (mode.liveCoaching.status === 'prohibited') return false
  if (!mode.competitive && !mode.multiplayer) return true
  return mode.liveCoaching.status === 'approved' && !!mode.liveCoaching.source && !!mode.liveCoaching.reviewedAt
}

export function majorVersion(version: string): number {
  return Number.parseInt(version.split('.')[0] ?? '0', 10)
}
