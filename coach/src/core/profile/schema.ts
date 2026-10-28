// Game packs (profiles): the maintained, per-game knowledge package. A pack
// says what is visible and how to read it:
// - pixel regions (signals/events), for HUDs with stable colours;
// - a vision schema, for the optional cloud vision recogniser;
// - zones, heatmaps, mistake rules, score rules and match stats, for analysis;
// - modes (policy lives in the registry, keyed by game and mode), coaching
//   knowledge, and known limitations.
//
// Packs are data (JSON) and may come from users, so they are validated
// structurally before the engine sees them. Pack text that the coach can speak
// is checked for causal or absolute claims.

import { KB_LIMITS, KNOWLEDGE_SECTIONS, type KnowledgeSection } from '../knowledge/base.ts'
import type { BoundaryKind, EventCategory, GamePhase, HsvRange, NormRect, RoundResult } from '../types.ts'

export const PROFILE_SCHEMA_VERSION = 2

export interface ProfileMode {
  readonly id: string
  readonly label: string
  readonly multiplayer: boolean
  readonly competitive: boolean
}

// --- Pixel recognition (v1) -------------------------------------------------------------

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

// --- Episodes and live cues -----------------------------------------------------------

export interface EpisodeResponseSpec {
  readonly id: string
  /** The response is a visible event after the trigger… */
  readonly event?: string
  /** …or a property of the trigger itself (its zone), for "where did it start" comparisons. */
  readonly triggerZones?: readonly string[]
  /** Short label, e.g. "patched up". */
  readonly label: string
  /** Past-tense clause, e.g. "you patched up first". */
  readonly phrase: string
}

export interface EpisodeSpec {
  readonly id: string
  readonly label: string
  readonly trigger: string
  /** Only triggers with this attribute value open an episode (e.g. team = "player"). */
  readonly triggerWhere?: { readonly attribute: string; readonly equals: string | number | boolean }
  readonly windowMs: number
  readonly responses: readonly EpisodeResponseSpec[]
  readonly noResponse: { readonly label: string; readonly phrase: string }
  readonly negativeOutcomes: readonly string[]
  readonly positiveOutcomes?: readonly string[]
  /** State keys (pixel signals or vision state) that must stay readable for the episode to count. */
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
  /** Imperative, 6 words or fewer, e.g. "patch up first". */
  readonly imperative: string
  /** Do not speak a cue about a moment older than this. */
  readonly maxAgeMs: number
}

// --- Vision recognition (v2) -------------------------------------------------------------

export type VisionValueType = 'number' | 'string' | 'boolean' | 'enum'

export interface VisionStateSpec {
  readonly key: string
  readonly label: string
  readonly type: VisionValueType
  readonly options?: readonly string[]
  readonly min?: number
  readonly max?: number
  /** Strings: longest value kept (default 80, at most 300). */
  readonly maxLength?: number
  /** How long a reading stays valid. */
  readonly ttlMs: number
  /** Instruction to the recogniser: where it is on screen and how to read it. */
  readonly description: string
}

export interface VisionEventSpec {
  readonly type: string
  readonly label: string
  readonly category: EventCategory
  readonly description: string
  /** Zone set the recogniser should place this event in, if any. */
  readonly zoneSet?: string
  readonly attributes?: readonly { readonly key: string; readonly options: readonly string[]; readonly description: string }[]
  /** Repeated sightings within this window are the same event (banners persist)… */
  readonly dedupeMs: number
  /** …unless these attributes differ (e.g. a goal by the other team is a different goal). */
  readonly dedupeBy?: readonly string[]
}

export interface VisionSpec {
  /** What the recogniser should pay attention to, in one paragraph. */
  readonly purpose: string
  readonly sampleIntervalMs: number
  /** Longest acceptable recognition latency; older observations are not used for live coaching. */
  readonly maxLatencyMs: number
  readonly maxWidth: number
  /** When present, only these regions are sent (less data, more privacy). */
  readonly crops?: readonly { readonly id: string; readonly label: string; readonly region: NormRect }[]
  readonly state: readonly VisionStateSpec[]
  readonly events: readonly VisionEventSpec[]
}

// --- Analyses (v2) ----------------------------------------------------------------------

export interface ZoneSet {
  readonly id: string
  readonly label: string
  readonly rows: number
  readonly cols: number
  /** Row-major; rows × cols cells. Labels read naturally after "from", e.g. "your box, left side". */
  readonly cells: readonly { readonly id: string; readonly label: string }[]
  /** How the recogniser should map the screen to these cells. */
  readonly description: string
}

export interface HeatmapSpec {
  readonly id: string
  readonly label: string
  readonly eventType: string
  readonly zoneSet: string
  /** No spatial claim below this many located events. */
  readonly minEvents: number
  /** Plural noun phrase, e.g. "goals you conceded". */
  readonly phrase: string
  /** True when the events come from sampled frames and some are missed. */
  readonly sampled: boolean
  readonly breakdown?: {
    readonly attribute: string
    readonly label: string
    /** Playbook entry per breakdown value, so the most common one comes with its fix. */
    readonly playbook?: Readonly<Record<string, string>>
  }
  readonly tags: readonly string[]
}

export type ConditionOp = '>' | '>=' | '<' | '<=' | '==' | '!='

export interface Condition {
  /** State key, or omit and use `sum`. */
  readonly key?: string
  readonly sum?: readonly string[]
  readonly op: ConditionOp
  readonly value?: number | string | boolean
  /** Compare with another state key instead of a constant. */
  readonly ref?: string
  /** Compare with a player-context value instead (e.g. a target time). */
  readonly contextValue?: string
}

export interface MistakeSpec {
  readonly id: string
  readonly label: string
  /** All conditions must hold (on fresh, visible state) for `forMs` before it counts. */
  readonly when: readonly Condition[]
  readonly forMs: number
  readonly minGameTimeSec?: number
  readonly phases?: readonly GamePhase[]
  /** Spoken during play where policy allows, ≤ 8 words. */
  readonly liveTip?: string
  /** Post-match line; placeholders {count} and {total}. */
  readonly summary: string
  readonly severity: 'low' | 'medium' | 'high'
  readonly tags: readonly string[]
  /** Fire at most once per match (e.g. a late age-up). */
  readonly oncePerMatch?: boolean
  /** The playbook entry with the principle and the fix. */
  readonly playbook?: string
}

export interface ScoreRule {
  readonly homeKey: string
  readonly awayKey: string
  /** Player-context id holding "home" or "away". */
  readonly sideContext: string
  readonly forEvent: string
  readonly againstEvent: string
  /** Vision event that, if seen near a score change, adds its zone and attributes. */
  readonly confirmEvent?: string
  readonly confirmWindowMs?: number
}

export interface MatchStatSpec {
  readonly keys: readonly {
    readonly key: string
    readonly label: string
    readonly unit?: string
    readonly better: 'higher' | 'lower' | 'neutral'
    /** A line on the wrong side of which the stat is worth a remark even without history (e.g. pass accuracy under 75%). */
    readonly warn?: { readonly at: number; readonly note: string; readonly playbook?: string }
  }[]
}

/**
 * A factor: a visible circumstance of a fight, read from the killcam or the
 * screen and attached to death (loss) and kill (win) events as an attribute
 * (range, cover, movement…). The analysis compares fights won and lost by
 * factor, and counts how often an avoidable circumstance came before a death.
 */
export interface FactorValueSpec {
  readonly value: string
  readonly label: string
  /** Completes "{k} of your {n} deaths came …" or "You lost {k} of {n} fights …", e.g. "while you were in the open". */
  readonly phrase: string
  /** A circumstance the player can change (in the open, reloading, alone…): worth flagging on its own. */
  readonly avoidable?: boolean
  /** The playbook entry with the principle and the fix. */
  readonly playbook?: string
  /** Spoken during play where policy allows, once the evidence is moderate. ≤ 8 words. */
  readonly liveTip?: string
}

export interface FactorSpec {
  readonly id: string
  readonly label: string
  /** The event attribute holding the value. Either this or `afterEvent`. */
  readonly attribute?: string
  /**
   * Instead of an attribute, timing: value "yes" when this event (e.g. the
   * player's own kill) came within `withinMs` before the loss, else "no".
   * Timing is read from event times, so it does not depend on the killcam.
   */
  readonly afterEvent?: { readonly event: string; readonly withinMs: number }
  /** Event that is a lost fight (e.g. "death"), or a score rule's event whose confirm event carries the attribute. */
  readonly lossEvent: string
  /** Plural noun for loss events in share statements, e.g. "goals conceded" (default "deaths"). */
  readonly lossNoun?: string
  /** Event that is a won fight (e.g. "kill"), when the same attribute is read on it too. */
  readonly winEvent?: string
  /** No claim below this many tagged events. */
  readonly minEvents: number
  readonly values: readonly FactorValueSpec[]
}

/**
 * A benchmark: a visible number compared, at checkpoints of the game clock,
 * with a simple expected curve (e.g. villagers from one Town Center that
 * never stops: start + perMinute × minutes, up to a cap).
 */
export interface BenchmarkSpec {
  readonly id: string
  readonly label: string
  /** State key holding the number (e.g. "villagers"). */
  readonly key: string
  /** State key holding the game clock in seconds. */
  readonly timeKey: string
  readonly expected: { readonly start: number; readonly perMinute: number; readonly cap?: number }
  readonly checkpointsSec: readonly number[]
  /** Flag a checkpoint when the reading is below expected by more than this fraction. */
  readonly tolerance: number
  /** Plural noun for the number, e.g. "villagers". */
  readonly noun: string
  /** What the expected curve represents, e.g. "a Town Center that never stops". */
  readonly expectedPhrase: string
  /** What one unit of shortfall means in time, e.g. "of idle Town Center time" (uses perMinute). */
  readonly gapPhrase?: string
  /** What strong players reach, for context (sourced in the playbook entry). */
  readonly reference?: readonly { readonly atSec: number; readonly value: number; readonly label: string }[]
  readonly playbook?: string
}

/**
 * Battles (strategy games): runs of frames where the player's army is seen
 * fighting, settled into won or lost fights (see src/core/analyses/battles.ts).
 * The won/lost events carry the fight event's attributes (except the trend)
 * plus "ending" on lost fights, so factors can compare them.
 */
export interface BattleSpec {
  /** Vision event reported on frames where the player's army is fighting. */
  readonly event: string
  /** Its attribute with the visible trend: winning, losing or even. */
  readonly trendAttribute: string
  /** Army size = total − minus (state keys), e.g. population − villagers. */
  readonly army: { readonly total: string; readonly minus?: string }
  /** The fight ends after this long without a fight frame. */
  readonly gapMs: number
  /** Fewer frames than this: a skirmish, logged as unclear. */
  readonly minFrames: number
  readonly wonEvent: string
  readonly lostEvent: string
}

/**
 * A strategy game's roster: factions (civilisations), units, the buildings
 * that make them, and counters. Used to recognise what is on screen, to work
 * out what a scouted building can produce for the opponent's faction, and to
 * say what beats it. Reference knowledge, dated, never an observation.
 */
export interface RosterUnit {
  readonly id: string
  readonly name: string
  readonly aliases?: readonly string[]
  readonly classes: readonly string[]
  /** Building id that produces it. */
  readonly building: string
  readonly age?: number
  readonly cost?: string
  readonly counters: readonly string[]
  readonly counteredBy: readonly string[]
  readonly why?: string
  /** How to tell it apart on screen. */
  readonly look?: string
  /** Only these factions have it (unique units). */
  readonly factions?: readonly string[]
}

export interface RosterBuilding {
  readonly id: string
  readonly name: string
  readonly aliases?: readonly string[]
  readonly produces: readonly string[]
  /** What seeing it tells you about the opponent's plan. */
  readonly signals?: string
  readonly look?: string
  readonly factions?: readonly string[]
}

export interface RosterFaction {
  readonly id: string
  readonly name: string
  readonly aliases?: readonly string[]
  readonly status: 'released' | 'upcoming'
  readonly dlc?: string
  readonly release?: string | null
  readonly variantOf?: string | null
  readonly bonuses: readonly string[]
  readonly uniqueUnits: readonly string[]
  /** Standard unit id → the unique unit that takes its place. */
  readonly replaces?: Readonly<Record<string, string>>
  /** Standard units (ids) or features the faction does not have. */
  readonly lacks?: readonly string[]
  readonly landmarks?: readonly { readonly age: number; readonly name: string; readonly effect: string }[]
  readonly playstyle?: string
  readonly commonStrategies?: readonly string[]
  readonly howToPlayAgainst?: string
  readonly sources?: readonly { readonly url: string; readonly title: string; readonly kind: string }[]
}

export interface Roster {
  readonly checkedAt: string
  readonly patch: string
  readonly factions: readonly RosterFaction[]
  readonly units: readonly RosterUnit[]
  readonly buildings: readonly RosterBuilding[]
  readonly counterRules?: readonly { readonly if: string; readonly build: string; readonly why?: string; readonly avoid?: string }[]
}

/** A coaching principle as good players and coaches teach it, with the fix and its sources. */
export interface PlaybookEntry {
  readonly id: string
  readonly title: string
  readonly principle: string
  /** The reasoning behind it: mechanics and numbers (with the patch they come from). */
  readonly why?: string
  readonly good: readonly string[]
  readonly bad: readonly string[]
  /** How it shows on screen (what the coach can see). */
  readonly signals: readonly string[]
  /** One plain, imperative sentence. */
  readonly fix: string
  readonly drill?: string
  readonly sources: readonly { readonly url: string; readonly title: string; readonly kind: 'pro' | 'coach' | 'guide' | 'forum' | 'official' }[]
  readonly tags: readonly string[]
}

export interface PlayerContextSpec {
  readonly id: string
  readonly label: string
  readonly kind: 'choice' | 'number' | 'text'
  readonly options?: readonly string[]
  readonly default: string | number
  readonly help: string
}

export interface CoachingConcept {
  readonly id: string
  readonly title: string
  readonly summary: string
  readonly good: readonly string[]
  readonly bad: readonly string[]
  readonly tags: readonly string[]
}

export interface GameProfile {
  readonly schemaVersion: typeof PROFILE_SCHEMA_VERSION
  readonly id: string
  /** Semver. A major bump means older long-term tendencies are not merged. */
  readonly version: string
  readonly name: string
  readonly depth: 'pack' | 'generic'
  readonly game: {
    readonly title: string
    readonly publisher?: string
    /** The patch / release the pack was checked against, or "any" for generic. */
    readonly patch: string
    readonly languages: readonly string[]
  }
  readonly validation: {
    readonly status: 'reference' | 'community' | 'verified' | 'unverified'
    readonly notes: string
    readonly validatedAt?: string
  }
  readonly health: 'healthy' | 'degraded' | 'experimental'
  readonly capture: {
    /** Expected aspect ratio, or null when any is fine. */
    readonly aspectRatio: number | null
    readonly notes: string
    /** Regular expressions matched against the OS window title, to suggest this pack. */
    readonly windowTitleHints?: readonly string[]
  }
  readonly modes: readonly ProfileMode[]
  readonly playerContext?: readonly PlayerContextSpec[]
  readonly signals: readonly SignalSpec[]
  readonly events: readonly EventRuleSpec[]
  readonly episodes: readonly EpisodeSpec[]
  readonly liveCues: readonly LiveCueSpec[]
  readonly vision?: VisionSpec
  readonly zones?: readonly ZoneSet[]
  readonly heatmaps?: readonly HeatmapSpec[]
  readonly mistakes?: readonly MistakeSpec[]
  readonly scoreRules?: readonly ScoreRule[]
  readonly matchStats?: MatchStatSpec
  readonly factors?: readonly FactorSpec[]
  readonly benchmarks?: readonly BenchmarkSpec[]
  readonly battles?: BattleSpec
  /** Live commentary: event type → the line's opening words (e.g. death → "Died"). */
  readonly commentary?: Readonly<Record<string, string>>
  /** State keys that describe the game rather than the play (map, opponent's civilisation…), kept for the review and answers. */
  readonly contextKeys?: readonly string[]
  /**
   * Scouting intel (needs knowledge.roster): state keys holding the
   * opponent's faction, and the enemy buildings and units visible on the
   * player's screen (names from the roster, with counts).
   */
  readonly intel?: { readonly factionKey: string; readonly buildingsKey: string; readonly unitsKey: string; readonly playerFactionContext?: string }
  /** Live tips about vision-derived moments older than this are not spoken. */
  readonly liveFreshnessMs?: number
  readonly knowledge: {
    readonly concepts: readonly CoachingConcept[]
    /** Advice categories the coach must never give for this game. */
    readonly prohibitedAdvice: readonly string[]
    readonly terminology: Readonly<Record<string, string>>
    /** How good players and coaches judge play, with fixes and sources. */
    readonly playbook?: readonly PlaybookEntry[]
    /** Factions, units, buildings and counters (strategy games). */
    readonly roster?: Roster
    /** Curated reference knowledge (modes, maps, screens, HUD, roster…), dated; see src/core/knowledge/base.ts. */
    readonly reference?: readonly KnowledgeSection[]
    /** When the reference was last checked against the game. */
    readonly referenceCheckedAt?: string
    /** The game version the reference describes. */
    readonly referenceVersion?: string
    readonly referenceSources?: readonly { readonly url: string; readonly title: string }[]
  }
  readonly limitations: readonly string[]
  /** Conditions under which the pack stops inferring (shown to the player). */
  readonly failClosed: readonly string[]
}

// --- Validation ------------------------------------------------------------------------------

export type ValidationResult = { ok: true; profile: GameProfile } | { ok: false; errors: string[] }

const ID = /^[a-z0-9][a-z0-9_-]{0,63}$/
const SEMVER = /^\d+\.\d+\.\d+$/
const CATEGORIES: readonly EventCategory[] = ['context', 'action', 'outcome', 'boundary', 'mistake']
const BOUNDARIES: readonly BoundaryKind[] = ['round-start', 'round-end', 'match-end']
const RESULTS: readonly RoundResult[] = ['win', 'loss', 'draw', 'unknown']
const PHASES: readonly GamePhase[] = ['menu', 'round', 'combat', 'round_end', 'match_end', 'unknown']
const OPS: readonly ConditionOp[] = ['>', '>=', '<', '<=', '==', '!=']
/** Words the coach must never say: they claim causation or certainty. */
export const BANNED_CLAIMS = /\b(caused|causes|causing|because|definitely|always|never fails|guarantee[sd]?|proves?|certainly)\b/i
/** State keys every vision response may report, whatever the pack. */
export const STANDARD_STATE_KEYS = ['phase', 'visibleOutcome', 'visibleHealth', 'visibleObjective'] as const

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

  arr(v: unknown, path: string, optional = false): unknown[] {
    if (v === undefined && optional) return []
    if (!Array.isArray(v)) {
      this.errors.push(`${path}: expected an array`)
      return []
    }
    return v
  }

  str(v: unknown, path: string, opts: { pattern?: RegExp; optional?: boolean; max?: number; spoken?: boolean } = {}): string | null {
    if (v === undefined && opts.optional) return null
    if (typeof v !== 'string' || v.length === 0) {
      this.errors.push(`${path}: expected a non-empty string`)
      return null
    }
    if (v.length > (opts.max ?? 500)) this.errors.push(`${path}: too long`)
    if (opts.pattern && !opts.pattern.test(v)) this.errors.push(`${path}: "${v}" has an invalid format`)
    if (opts.spoken && BANNED_CLAIMS.test(v)) this.errors.push(`${path}: spoken text must not make causal or absolute claims ("${v}")`)
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

  ids(list: unknown[], path: string, field = 'id'): Set<string> {
    const out = new Set<string>()
    list.forEach((item, i) => {
      const id = (item as Obj | null)?.[field]
      if (typeof id === 'string') {
        if (out.has(id)) this.errors.push(`${path}[${i}].${field}: duplicate "${id}"`)
        out.add(id)
      }
    })
    return out
  }
}

export function validateProfile(input: unknown): ValidationResult {
  const c = new Checker()
  const p = c.obj(input, 'profile')
  if (!p) return { ok: false, errors: c.errors }

  if (p.schemaVersion !== PROFILE_SCHEMA_VERSION) c.errors.push(`schemaVersion: expected ${PROFILE_SCHEMA_VERSION}`)
  c.str(p.id, 'id', { pattern: ID })
  c.str(p.version, 'version', { pattern: SEMVER })
  c.str(p.name, 'name', { max: 80 })
  c.oneOf(p.depth, 'depth', ['pack', 'generic'] as const)
  c.oneOf(p.health, 'health', ['healthy', 'degraded', 'experimental'] as const)

  const game = c.obj(p.game, 'game')
  if (game) {
    c.str(game.title, 'game.title', { max: 120 })
    c.str(game.publisher, 'game.publisher', { optional: true, max: 120 })
    c.str(game.patch, 'game.patch', { max: 60 })
    if (c.arr(game.languages, 'game.languages').length === 0) c.errors.push('game.languages: at least one language')
  }
  const validation = c.obj(p.validation, 'validation')
  if (validation) {
    c.oneOf(validation.status, 'validation.status', ['reference', 'community', 'verified', 'unverified'] as const)
    c.str(validation.notes, 'validation.notes', { max: 2000 })
    c.str(validation.validatedAt, 'validation.validatedAt', { optional: true, max: 40 })
  }
  const capture = c.obj(p.capture, 'capture')
  if (capture) {
    if (capture.aspectRatio !== null) c.num(capture.aspectRatio, 'capture.aspectRatio', 0.5, 4)
    c.str(capture.notes, 'capture.notes', { max: 1000 })
    c.arr(capture.windowTitleHints, 'capture.windowTitleHints', true).forEach((h, i) => {
      const s = c.str(h, `capture.windowTitleHints[${i}]`, { max: 200 })
      if (s) {
        try {
          new RegExp(s, 'i')
        } catch {
          c.errors.push(`capture.windowTitleHints[${i}]: not a valid regular expression`)
        }
      }
    })
  }

  const modes = c.arr(p.modes, 'modes')
  if (modes.length === 0) c.errors.push('modes: at least one mode is required')
  c.ids(modes, 'modes')
  modes.forEach((m, i) => {
    const o = c.obj(m, `modes[${i}]`)
    if (!o) return
    c.str(o.id, `modes[${i}].id`, { pattern: ID })
    c.str(o.label, `modes[${i}].label`, { max: 80 })
    c.bool(o.multiplayer, `modes[${i}].multiplayer`)
    c.bool(o.competitive, `modes[${i}].competitive`)
  })

  const contextIds = new Set<string>()
  c.arr(p.playerContext, 'playerContext', true).forEach((x, i) => {
    const path = `playerContext[${i}]`
    const o = c.obj(x, path)
    if (!o) return
    const id = c.str(o.id, `${path}.id`, { pattern: /^[a-zA-Z][a-zA-Z0-9]{0,40}$/ })
    if (id) contextIds.add(id)
    c.str(o.label, `${path}.label`, { max: 80 })
    const kind = c.oneOf(o.kind, `${path}.kind`, ['choice', 'number', 'text'] as const)
    if (kind === 'choice' && c.arr(o.options, `${path}.options`).length < 2) c.errors.push(`${path}.options: at least two`)
    if (typeof o.default !== 'string' && typeof o.default !== 'number') c.errors.push(`${path}.default: required`)
    c.str(o.help, `${path}.help`, { max: 300 })
  })

  // Pixel signals.
  const signals = c.arr(p.signals, 'signals')
  const signalIds = c.ids(signals, 'signals')
  signals.forEach((s, i) => {
    const path = `signals[${i}]`
    const o = c.obj(s, path)
    if (!o) return
    c.str(o.id, `${path}.id`, { pattern: ID })
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

  // Pixel event rules.
  const events = c.arr(p.events, 'events')
  const eventIds = c.ids(events, 'events')
  events.forEach((e, i) => {
    const path = `events[${i}]`
    const o = c.obj(e, path)
    if (!o) return
    c.str(o.id, `${path}.id`, { pattern: ID })
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

  // Zones (needed before vision events and heatmaps).
  const zoneSets = new Map<string, Set<string>>()
  c.arr(p.zones, 'zones', true).forEach((z, i) => {
    const path = `zones[${i}]`
    const o = c.obj(z, path)
    if (!o) return
    const id = c.str(o.id, `${path}.id`, { pattern: ID })
    c.str(o.label, `${path}.label`, { max: 80 })
    c.str(o.description, `${path}.description`, { max: 1500 })
    const rows = c.num(o.rows, `${path}.rows`, 1, 12)
    const cols = c.num(o.cols, `${path}.cols`, 1, 12)
    const cells = c.arr(o.cells, `${path}.cells`)
    if (rows !== null && cols !== null && cells.length !== rows * cols) c.errors.push(`${path}.cells: expected ${rows * cols} cells`)
    const cellIds = c.ids(cells, `${path}.cells`)
    cells.forEach((cell, j) => {
      const co = c.obj(cell, `${path}.cells[${j}]`)
      if (!co) return
      c.str(co.id, `${path}.cells[${j}].id`, { pattern: ID })
      c.str(co.label, `${path}.cells[${j}].label`, { max: 80, spoken: true })
    })
    if (id) zoneSets.set(id, cellIds)
  })

  // Vision schema.
  const visionStateKeys = new Set<string>(STANDARD_STATE_KEYS)
  const visionEvents = new Map<string, { attributes: Set<string>; zoneSet: string | null }>()
  if (p.vision !== undefined) {
    const v = c.obj(p.vision, 'vision')
    if (v) {
      c.str(v.purpose, 'vision.purpose', { max: 2000 })
      c.num(v.sampleIntervalMs, 'vision.sampleIntervalMs', 1000, 60_000)
      c.num(v.maxLatencyMs, 'vision.maxLatencyMs', 1000, 60_000)
      c.num(v.maxWidth, 'vision.maxWidth', 320, 1920)
      c.arr(v.crops, 'vision.crops', true).forEach((cr, i) => {
        const o = c.obj(cr, `vision.crops[${i}]`)
        if (!o) return
        c.str(o.id, `vision.crops[${i}].id`, { pattern: ID })
        c.str(o.label, `vision.crops[${i}].label`, { max: 80 })
        c.rect(o.region, `vision.crops[${i}].region`)
      })
      const state = c.arr(v.state, 'vision.state')
      c.ids(state, 'vision.state', 'key')
      state.forEach((s, i) => {
        const path = `vision.state[${i}]`
        const o = c.obj(s, path)
        if (!o) return
        const key = c.str(o.key, `${path}.key`, { pattern: /^[a-zA-Z][a-zA-Z0-9]{0,40}$/ })
        if (key && (STANDARD_STATE_KEYS as readonly string[]).includes(key)) c.errors.push(`${path}.key: "${key}" is a standard key`)
        if (key) visionStateKeys.add(key)
        c.str(o.label, `${path}.label`, { max: 80 })
        const type = c.oneOf(o.type, `${path}.type`, ['number', 'string', 'boolean', 'enum'] as const)
        if (type === 'enum' && c.arr(o.options, `${path}.options`).length < 2) c.errors.push(`${path}.options: an enum needs options`)
        c.num(o.min, `${path}.min`, -1e9, 1e9, true)
        c.num(o.max, `${path}.max`, -1e9, 1e9, true)
        c.num(o.ttlMs, `${path}.ttlMs`, 500, 600_000)
        c.num(o.maxLength, `${path}.maxLength`, 10, 300, true)
        c.str(o.description, `${path}.description`, { max: 600 })
      })
      const vEvents = c.arr(v.events, 'vision.events')
      c.ids(vEvents, 'vision.events', 'type')
      vEvents.forEach((e, i) => {
        const path = `vision.events[${i}]`
        const o = c.obj(e, path)
        if (!o) return
        const type = c.str(o.type, `${path}.type`, { pattern: ID })
        c.str(o.label, `${path}.label`, { max: 80 })
        c.oneOf(o.category, `${path}.category`, CATEGORIES)
        c.str(o.description, `${path}.description`, { max: 600 })
        c.num(o.dedupeMs, `${path}.dedupeMs`, 0, 600_000)
        const zs = c.str(o.zoneSet, `${path}.zoneSet`, { optional: true })
        if (zs && !zoneSets.has(zs)) c.errors.push(`${path}.zoneSet: unknown zone set "${zs}"`)
        const attrs = new Set<string>()
        c.arr(o.attributes, `${path}.attributes`, true).forEach((a, j) => {
          const ao = c.obj(a, `${path}.attributes[${j}]`)
          if (!ao) return
          const k = c.str(ao.key, `${path}.attributes[${j}].key`, { pattern: /^[a-zA-Z][a-zA-Z0-9]{0,40}$/ })
          if (k) attrs.add(k)
          if (c.arr(ao.options, `${path}.attributes[${j}].options`).length < 2) c.errors.push(`${path}.attributes[${j}].options: at least two`)
          c.str(ao.description, `${path}.attributes[${j}].description`, { max: 300 })
        })
        for (const [j, k] of c.arr(o.dedupeBy, `${path}.dedupeBy`, true).entries()) {
          if (typeof k !== 'string' || !attrs.has(k)) c.errors.push(`${path}.dedupeBy[${j}]: not an attribute of this event`)
        }
        if (type) visionEvents.set(type, { attributes: attrs, zoneSet: zs })
      })
    }
  }

  /** Links to playbook entries, checked once the playbook is read. */
  const playbookRefs: [string, unknown][] = []

  // Score rules may create events too (with the attributes of their confirm event).
  const derivedEvents = new Set<string>()
  const derivedAttributes = new Map<string, Set<string>>()
  c.arr(p.scoreRules, 'scoreRules', true).forEach((r, i) => {
    const path = `scoreRules[${i}]`
    const o = c.obj(r, path)
    if (!o) return
    for (const k of ['homeKey', 'awayKey'] as const) {
      const key = c.str(o[k], `${path}.${k}`)
      if (key && !visionStateKeys.has(key)) c.errors.push(`${path}.${k}: unknown state key "${key}"`)
    }
    const side = c.str(o.sideContext, `${path}.sideContext`)
    if (side && !contextIds.has(side)) c.errors.push(`${path}.sideContext: unknown player context "${side}"`)
    const confirm = c.str(o.confirmEvent, `${path}.confirmEvent`, { optional: true })
    if (confirm && !visionEvents.has(confirm)) c.errors.push(`${path}.confirmEvent: unknown vision event "${confirm}"`)
    for (const k of ['forEvent', 'againstEvent'] as const) {
      const id = c.str(o[k], `${path}.${k}`, { pattern: ID })
      if (id) {
        derivedEvents.add(id)
        derivedAttributes.set(id, visionEvents.get(confirm ?? '')?.attributes ?? new Set())
      }
    }
    c.num(o.confirmWindowMs, `${path}.confirmWindowMs`, 1000, 120_000, true)
  })

  if (p.battles !== undefined) {
    const b = c.obj(p.battles, 'battles')
    if (b) {
      const ev = c.str(b.event, 'battles.event')
      const attrs = ev ? visionEvents.get(ev)?.attributes : undefined
      if (ev && !attrs) c.errors.push(`battles.event: unknown vision event "${ev}"`)
      const trend = c.str(b.trendAttribute, 'battles.trendAttribute')
      if (trend && attrs && !attrs.has(trend)) c.errors.push(`battles.trendAttribute: event "${ev}" has no attribute "${trend}"`)
      const army = c.obj(b.army, 'battles.army')
      for (const k of ['total', 'minus'] as const) {
        if (!army || (k === 'minus' && army.minus === undefined)) continue
        const key = c.str(army[k], `battles.army.${k}`)
        if (key && !visionStateKeys.has(key)) c.errors.push(`battles.army.${k}: unknown state key "${key}"`)
      }
      c.num(b.gapMs, 'battles.gapMs', 2000, 120_000)
      c.num(b.minFrames, 'battles.minFrames', 1, 20)
      const carried = new Set([...(attrs ?? [])].filter((a) => a !== trend))
      for (const k of ['wonEvent', 'lostEvent'] as const) {
        const id = c.str(b[k], `battles.${k}`, { pattern: ID })
        if (!id) continue
        derivedEvents.add(id)
        derivedAttributes.set(id, k === 'lostEvent' ? new Set([...carried, 'ending']) : carried)
      }
    }
  }
  if (p.commentary !== undefined) {
    const com = c.obj(p.commentary, 'commentary')
    const known = new Set([...visionEvents.keys(), ...derivedEvents, ...((Array.isArray(p.events) ? p.events : []) as Obj[]).map((e) => e?.id)])
    for (const [k, v] of Object.entries(com ?? {})) {
      if (!known.has(k)) c.errors.push(`commentary.${k}: unknown event`)
      c.str(v, `commentary.${k}`, { max: 60, spoken: true })
    }
  }
  c.arr(p.contextKeys, 'contextKeys', true).forEach((k, i) => {
    if (typeof k !== 'string' || !visionStateKeys.has(k)) c.errors.push(`contextKeys[${i}]: unknown state key "${String(k)}"`)
  })

  // Mistake rules create events (category "mistake").
  c.arr(p.mistakes, 'mistakes', true).forEach((m, i) => {
    const path = `mistakes[${i}]`
    const o = c.obj(m, path)
    if (!o) return
    const id = c.str(o.id, `${path}.id`, { pattern: ID })
    if (id) derivedEvents.add(id)
    c.str(o.label, `${path}.label`, { max: 80 })
    c.num(o.forMs, `${path}.forMs`, 0, 3_600_000)
    c.num(o.minGameTimeSec, `${path}.minGameTimeSec`, 0, 36_000, true)
    c.arr(o.phases, `${path}.phases`, true).forEach((ph, j) => c.oneOf(ph, `${path}.phases[${j}]`, PHASES))
    const tip = c.str(o.liveTip, `${path}.liveTip`, { optional: true, max: 80, spoken: true })
    if (tip && tip.split(/\s+/).length > 8) c.errors.push(`${path}.liveTip: keep live tips to 8 words or fewer`)
    const summary = c.str(o.summary, `${path}.summary`, { max: 200, spoken: true })
    if (summary && /\{(?!count\}|total\})[^}]*\}/.test(summary)) c.errors.push(`${path}.summary: only {count} and {total} placeholders`)
    c.oneOf(o.severity, `${path}.severity`, ['low', 'medium', 'high'] as const)
    c.arr(o.tags, `${path}.tags`)
    const when = c.arr(o.when, `${path}.when`)
    if (when.length === 0) c.errors.push(`${path}.when: at least one condition`)
    when.forEach((w, j) => {
      const wp = `${path}.when[${j}]`
      const wo = c.obj(w, wp)
      if (!wo) return
      c.oneOf(wo.op, `${wp}.op`, OPS)
      const keys = [wo.key, wo.ref, ...(Array.isArray(wo.sum) ? wo.sum : [])].filter((k) => k !== undefined)
      if (wo.key === undefined && wo.sum === undefined) c.errors.push(`${wp}: needs "key" or "sum"`)
      for (const k of keys) if (typeof k !== 'string' || !visionStateKeys.has(k)) c.errors.push(`${wp}: unknown state key "${String(k)}"`)
      const comparands = [wo.value !== undefined, wo.ref !== undefined, wo.contextValue !== undefined].filter(Boolean).length
      if (comparands !== 1) c.errors.push(`${wp}: exactly one of value, ref, contextValue`)
      if (typeof wo.contextValue === 'string' && !contextIds.has(wo.contextValue)) c.errors.push(`${wp}.contextValue: unknown player context`)
    })
  })

  const allEvents = new Set([...eventIds, ...visionEvents.keys(), ...derivedEvents])

  c.arr(p.heatmaps, 'heatmaps', true).forEach((h, i) => {
    const path = `heatmaps[${i}]`
    const o = c.obj(h, path)
    if (!o) return
    c.str(o.id, `${path}.id`, { pattern: ID })
    c.str(o.label, `${path}.label`, { max: 80 })
    const ev = c.str(o.eventType, `${path}.eventType`)
    if (ev && !allEvents.has(ev)) c.errors.push(`${path}.eventType: unknown event "${ev}"`)
    const zs = c.str(o.zoneSet, `${path}.zoneSet`)
    if (zs && !zoneSets.has(zs)) c.errors.push(`${path}.zoneSet: unknown zone set "${zs}"`)
    c.num(o.minEvents, `${path}.minEvents`, 2, 1000)
    c.str(o.phrase, `${path}.phrase`, { max: 80, spoken: true })
    c.bool(o.sampled, `${path}.sampled`)
    c.arr(o.tags, `${path}.tags`)
    if (o.breakdown !== undefined) {
      const b = c.obj(o.breakdown, `${path}.breakdown`)
      if (b) {
        c.str(b.attribute, `${path}.breakdown.attribute`)
        c.str(b.label, `${path}.breakdown.label`, { max: 80 })
        const links = b.playbook === undefined ? null : c.obj(b.playbook, `${path}.breakdown.playbook`)
        for (const [value, id] of Object.entries(links ?? {})) playbookRefs.push([`${path}.breakdown.playbook.${value}`, id])
      }
    }
  })

  if (p.matchStats !== undefined) {
    const ms = c.obj(p.matchStats, 'matchStats')
    if (ms) {
      c.arr(ms.keys, 'matchStats.keys').forEach((k, i) => {
        const o = c.obj(k, `matchStats.keys[${i}]`)
        if (!o) return
        const key = c.str(o.key, `matchStats.keys[${i}].key`)
        if (key && !visionStateKeys.has(key)) c.errors.push(`matchStats.keys[${i}].key: unknown state key "${key}"`)
        c.str(o.label, `matchStats.keys[${i}].label`, { max: 80 })
        c.oneOf(o.better, `matchStats.keys[${i}].better`, ['higher', 'lower', 'neutral'] as const)
        const w = o.warn === undefined ? null : c.obj(o.warn, `matchStats.keys[${i}].warn`)
        if (w) {
          if (o.better === 'neutral') c.errors.push(`matchStats.keys[${i}].warn: needs better "higher" or "lower"`)
          c.num(w.at, `matchStats.keys[${i}].warn.at`, -1e6, 1e6)
          c.str(w.note, `matchStats.keys[${i}].warn.note`, { max: 120, spoken: true })
          if (w.playbook !== undefined) playbookRefs.push([`matchStats.keys[${i}].warn.playbook`, w.playbook])
        }
      })
    }
  }
  c.num(p.liveFreshnessMs, 'liveFreshnessMs', 500, 60_000, true)

  // Roster (strategy games): every reference must resolve.
  const rosterObj = (p.knowledge && typeof p.knowledge === 'object' ? (p.knowledge as Obj).roster : undefined) as Obj | undefined
  if (rosterObj !== undefined) {
    const r = c.obj(rosterObj, 'knowledge.roster')
    if (r) {
      c.str(r.checkedAt, 'knowledge.roster.checkedAt', { pattern: /^\d{4}-\d{2}-\d{2}$/ })
      c.str(r.patch, 'knowledge.roster.patch', { max: 160 })
      const unitIds = new Set<string>()
      const classes = new Set<string>()
      const buildingIds = new Set<string>()
      const factionIds = new Set<string>()
      const units = c.arr(r.units, 'knowledge.roster.units')
      const buildings = c.arr(r.buildings, 'knowledge.roster.buildings')
      const factions = c.arr(r.factions, 'knowledge.roster.factions')
      for (const [list, ids, name] of [[units, unitIds, 'units'], [buildings, buildingIds, 'buildings'], [factions, factionIds, 'factions']] as const) {
        list.forEach((x, i) => {
          const id = c.str((x as Obj)?.id, `knowledge.roster.${name}[${i}].id`, { pattern: ID })
          if (id && ids.has(id)) c.errors.push(`knowledge.roster.${name}[${i}].id: duplicate "${id}"`)
          if (id) ids.add(id)
          c.str((x as Obj)?.name, `knowledge.roster.${name}[${i}].name`, { max: 80 })
        })
      }
      units.forEach((u) => (Array.isArray((u as Obj)?.classes) ? ((u as Obj).classes as unknown[]) : []).forEach((k) => typeof k === 'string' && classes.add(k)))
      const ref = (v: unknown, path: string, kinds: 'unit' | 'unit-or-class' | 'building' | 'faction') => {
        const ok =
          typeof v === 'string' &&
          (kinds === 'building' ? buildingIds.has(v) : kinds === 'faction' ? factionIds.has(v) : unitIds.has(v) || (kinds === 'unit-or-class' && classes.has(v)))
        if (!ok) c.errors.push(`${path}: unknown ${kinds.replace(/-/g, ' ')} "${String(v)}"`)
      }
      units.forEach((x, i) => {
        const u = c.obj(x, `knowledge.roster.units[${i}]`)
        if (!u) return
        const path = `knowledge.roster.units[${i}]`
        ref(u.building, `${path}.building`, 'building')
        c.arr(u.classes, `${path}.classes`)
        for (const k of ['counters', 'counteredBy'] as const) c.arr(u[k], `${path}.${k}`).forEach((v, j) => ref(v, `${path}.${k}[${j}]`, 'unit-or-class'))
        for (const k of ['why', 'look', 'cost'] as const) c.str(u[k], `${path}.${k}`, { optional: true, max: 400 })
        c.arr(u.factions, `${path}.factions`, true).forEach((v, j) => ref(v, `${path}.factions[${j}]`, 'faction'))
      })
      buildings.forEach((x, i) => {
        const b = c.obj(x, `knowledge.roster.buildings[${i}]`)
        if (!b) return
        c.arr(b.produces, `knowledge.roster.buildings[${i}].produces`).forEach((v, j) => ref(v, `knowledge.roster.buildings[${i}].produces[${j}]`, 'unit'))
        for (const k of ['signals', 'look'] as const) c.str(b[k], `knowledge.roster.buildings[${i}].${k}`, { optional: true, max: 400 })
      })
      factions.forEach((x, i) => {
        const f = c.obj(x, `knowledge.roster.factions[${i}]`)
        if (!f) return
        const path = `knowledge.roster.factions[${i}]`
        c.oneOf(f.status, `${path}.status`, ['released', 'upcoming'] as const)
        c.arr(f.uniqueUnits, `${path}.uniqueUnits`).forEach((v, j) => ref(v, `${path}.uniqueUnits[${j}]`, 'unit'))
        const rep = f.replaces === undefined ? null : c.obj(f.replaces, `${path}.replaces`)
        for (const [k, v] of Object.entries(rep ?? {})) {
          ref(k, `${path}.replaces key`, 'unit')
          ref(v, `${path}.replaces.${k}`, 'unit')
        }
        if (f.variantOf !== undefined && f.variantOf !== null) ref(f.variantOf, `${path}.variantOf`, 'faction')
        for (const k of ['playstyle', 'howToPlayAgainst'] as const) c.str(f[k], `${path}.${k}`, { optional: true, max: 600 })
      })
    }
  }
  if (p.intel !== undefined) {
    const it = c.obj(p.intel, 'intel')
    if (it) {
      if (!rosterObj) c.errors.push('intel: needs knowledge.roster')
      for (const k of ['factionKey', 'buildingsKey', 'unitsKey'] as const) {
        const key = c.str(it[k], `intel.${k}`)
        if (key && !visionStateKeys.has(key)) c.errors.push(`intel.${k}: unknown state key "${key}"`)
      }
      const pc = c.str(it.playerFactionContext, 'intel.playerFactionContext', { optional: true })
      if (pc && !contextIds.has(pc)) c.errors.push(`intel.playerFactionContext: unknown player context "${pc}"`)
    }
  }

  // Playbook (validated first: factors and benchmarks refer to it).
  const playbookIds = new Set<string>()
  const knowledgeObj = (p.knowledge && typeof p.knowledge === 'object' ? p.knowledge : {}) as Obj
  if (knowledgeObj.playbook !== undefined) {
    c.arr(knowledgeObj.playbook, 'knowledge.playbook').forEach((e, i) => {
      const path = `knowledge.playbook[${i}]`
      const o = c.obj(e, path)
      if (!o) return
      const id = c.str(o.id, `${path}.id`, { pattern: ID })
      if (id) {
        if (playbookIds.has(id)) c.errors.push(`${path}.id: duplicate "${id}"`)
        playbookIds.add(id)
      }
      c.str(o.title, `${path}.title`, { max: 100 })
      c.str(o.principle, `${path}.principle`, { max: 600, spoken: true })
      c.str(o.why, `${path}.why`, { optional: true, max: 900 })
      c.str(o.fix, `${path}.fix`, { max: 240, spoken: true })
      c.str(o.drill, `${path}.drill`, { optional: true, max: 400 })
      for (const f of ['good', 'bad', 'signals', 'tags'] as const) c.arr(o[f], `${path}.${f}`).forEach((x, j) => c.str(x, `${path}.${f}[${j}]`, { max: 300, spoken: f !== 'tags' }))
      c.arr(o.sources, `${path}.sources`).forEach((s, j) => {
        const so = c.obj(s, `${path}.sources[${j}]`)
        if (!so) return
        c.str(so.url, `${path}.sources[${j}].url`, { pattern: /^https:\/\/\S+$/, max: 500 })
        c.str(so.title, `${path}.sources[${j}].title`, { max: 200 })
        c.oneOf(so.kind, `${path}.sources[${j}].kind`, ['pro', 'coach', 'guide', 'forum', 'official'] as const)
      })
    })
  }

  for (const [path, id] of playbookRefs) {
    if (typeof id !== 'string' || !playbookIds.has(id)) c.errors.push(`${path}: unknown playbook entry "${String(id)}"`)
  }
  if (Array.isArray(p.mistakes)) {
    p.mistakes.forEach((m, i) => {
      const pb = m && typeof m === 'object' ? (m as Obj).playbook : undefined
      if (pb === undefined) return
      const id = c.str(pb, `mistakes[${i}].playbook`)
      if (id && !playbookIds.has(id)) c.errors.push(`mistakes[${i}].playbook: unknown playbook entry "${id}"`)
    })
  }

  // Factors: attributes of fights, compared between losses and wins.
  c.arr(p.factors, 'factors', true).forEach((f, i) => {
    const path = `factors[${i}]`
    const o = c.obj(f, path)
    if (!o) return
    c.str(o.id, `${path}.id`, { pattern: ID })
    c.str(o.label, `${path}.label`, { max: 80 })
    const after = o.afterEvent === undefined ? null : c.obj(o.afterEvent, `${path}.afterEvent`)
    const attribute = after ? null : c.str(o.attribute, `${path}.attribute`)
    if (after) {
      if (o.attribute !== undefined) c.errors.push(`${path}: give either attribute or afterEvent, not both`)
      const ae = c.str(after.event, `${path}.afterEvent.event`)
      if (ae && !visionEvents.has(ae)) c.errors.push(`${path}.afterEvent.event: unknown vision event "${ae}"`)
      c.num(after.withinMs, `${path}.afterEvent.withinMs`, 500, 120_000)
    }
    c.num(o.minEvents, `${path}.minEvents`, 2, 100)
    c.str(o.lossNoun, `${path}.lossNoun`, { optional: true, max: 40 })
    for (const k of ['lossEvent', 'winEvent'] as const) {
      if (k === 'winEvent' && o.winEvent === undefined) continue
      const ev = c.str(o[k], `${path}.${k}`)
      const attrs = ev ? (visionEvents.get(ev)?.attributes ?? derivedAttributes.get(ev)) : undefined
      if (ev && !attrs) c.errors.push(`${path}.${k}: unknown vision or score event "${ev}"`)
      else if (attrs && attribute && !attrs.has(attribute)) c.errors.push(`${path}.${k}: event "${ev}" has no attribute "${attribute}"`)
    }
    c.arr(o.values, `${path}.values`).forEach((v, j) => {
      const vp = `${path}.values[${j}]`
      const vo = c.obj(v, vp)
      if (!vo) return
      const value = c.str(vo.value, `${vp}.value`)
      if (after && value && value !== 'yes' && value !== 'no') c.errors.push(`${vp}.value: an afterEvent factor has the values "yes" and "no"`)
      c.str(vo.label, `${vp}.label`, { max: 80 })
      c.str(vo.phrase, `${vp}.phrase`, { max: 120, spoken: true })
      if (vo.avoidable !== undefined) c.bool(vo.avoidable, `${vp}.avoidable`)
      const pb = c.str(vo.playbook, `${vp}.playbook`, { optional: true })
      if (pb && !playbookIds.has(pb)) c.errors.push(`${vp}.playbook: unknown playbook entry "${pb}"`)
      const tip = c.str(vo.liveTip, `${vp}.liveTip`, { optional: true, max: 80, spoken: true })
      if (tip && tip.split(/\s+/).length > 8) c.errors.push(`${vp}.liveTip: keep live tips to 8 words or fewer`)
    })
  })

  // Benchmarks: a visible number against an expected curve.
  c.arr(p.benchmarks, 'benchmarks', true).forEach((b, i) => {
    const path = `benchmarks[${i}]`
    const o = c.obj(b, path)
    if (!o) return
    c.str(o.id, `${path}.id`, { pattern: ID })
    c.str(o.label, `${path}.label`, { max: 80 })
    for (const k of ['key', 'timeKey'] as const) {
      const key = c.str(o[k], `${path}.${k}`)
      if (key && !visionStateKeys.has(key)) c.errors.push(`${path}.${k}: unknown state key "${key}"`)
    }
    const e = c.obj(o.expected, `${path}.expected`)
    if (e) {
      c.num(e.start, `${path}.expected.start`, 0, 1e6)
      c.num(e.perMinute, `${path}.expected.perMinute`, 0, 1e6)
      c.num(e.cap, `${path}.expected.cap`, 0, 1e6, true)
    }
    c.arr(o.checkpointsSec, `${path}.checkpointsSec`).forEach((x, j) => c.num(x, `${path}.checkpointsSec[${j}]`, 1, 36_000))
    c.num(o.tolerance, `${path}.tolerance`, 0, 0.9)
    c.str(o.noun, `${path}.noun`, { max: 40 })
    c.str(o.expectedPhrase, `${path}.expectedPhrase`, { max: 120, spoken: true })
    c.str(o.gapPhrase, `${path}.gapPhrase`, { optional: true, max: 80, spoken: true })
    c.arr(o.reference, `${path}.reference`, true).forEach((r, j) => {
      const ro = c.obj(r, `${path}.reference[${j}]`)
      if (!ro) return
      c.num(ro.atSec, `${path}.reference[${j}].atSec`, 1, 36_000)
      c.num(ro.value, `${path}.reference[${j}].value`, 0, 1e6)
      c.str(ro.label, `${path}.reference[${j}].label`, { max: 80 })
    })
    const pb = c.str(o.playbook, `${path}.playbook`, { optional: true })
    if (pb && !playbookIds.has(pb)) c.errors.push(`${path}.playbook: unknown playbook entry "${pb}"`)
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
    if (trigger && !allEvents.has(trigger)) c.errors.push(`${path}.trigger: unknown event "${trigger}"`)
    c.num(o.windowMs, `${path}.windowMs`, 250, 120_000)
    const responses = new Set<string>()
    c.arr(o.responses, `${path}.responses`).forEach((r, j) => {
      const rp = `${path}.responses[${j}]`
      const ro = c.obj(r, rp)
      if (!ro) return
      const rid = c.str(ro.id, `${rp}.id`, { pattern: ID })
      if (rid === 'none') c.errors.push(`${rp}.id: "none" is reserved for "no visible response"`)
      if (rid) responses.add(rid)
      if ((ro.event === undefined) === (ro.triggerZones === undefined)) c.errors.push(`${rp}: exactly one of "event" or "triggerZones"`)
      if (ro.event !== undefined) {
        const ev = c.str(ro.event, `${rp}.event`)
        if (ev && !allEvents.has(ev)) c.errors.push(`${rp}.event: unknown event "${ev}"`)
      } else c.arr(ro.triggerZones, `${rp}.triggerZones`)
      c.str(ro.label, `${rp}.label`, { max: 60, spoken: true })
      c.str(ro.phrase, `${rp}.phrase`, { max: 120, spoken: true })
    })
    const nr = c.obj(o.noResponse, `${path}.noResponse`)
    if (nr) {
      c.str(nr.label, `${path}.noResponse.label`, { max: 60, spoken: true })
      c.str(nr.phrase, `${path}.noResponse.phrase`, { max: 120, spoken: true })
    }
    const neg = c.arr(o.negativeOutcomes, `${path}.negativeOutcomes`)
    if (neg.length === 0) c.errors.push(`${path}.negativeOutcomes: at least one outcome event is required`)
    const pos = c.arr(o.positiveOutcomes, `${path}.positiveOutcomes`, true)
    for (const ev of [...neg, ...pos]) {
      if (typeof ev !== 'string' || !allEvents.has(ev)) c.errors.push(`${path}: unknown outcome event "${String(ev)}"`)
    }
    for (const s of c.arr(o.visibility, `${path}.visibility`)) {
      if (typeof s !== 'string' || (!signalIds.has(s) && !visionStateKeys.has(s))) c.errors.push(`${path}.visibility: unknown signal or state "${String(s)}"`)
    }
    const ph = c.obj(o.phrases, `${path}.phrases`)
    if (ph) {
      c.str(ph.trigger, `${path}.phrases.trigger`, { max: 120, spoken: true })
      c.str(ph.negative, `${path}.phrases.negative`, { max: 120, spoken: true })
      c.str(ph.negativeShort, `${path}.phrases.negativeShort`, { max: 60, spoken: true })
    }
    if (id) episodeResponses.set(id, responses)
  })

  c.arr(p.liveCues, 'liveCues').forEach((lc, i) => {
    const path = `liveCues[${i}]`
    const o = c.obj(lc, path)
    if (!o) return
    c.str(o.id, `${path}.id`, { pattern: ID })
    const trigger = c.str(o.trigger, `${path}.trigger`)
    if (trigger && !allEvents.has(trigger)) c.errors.push(`${path}.trigger: unknown event "${trigger}"`)
    const episode = c.str(o.episode, `${path}.episode`)
    const recommend = c.str(o.recommend, `${path}.recommend`)
    if (episode && !episodeResponses.has(episode)) c.errors.push(`${path}.episode: unknown episode "${episode}"`)
    else if (episode && recommend && !episodeResponses.get(episode)!.has(recommend)) {
      c.errors.push(`${path}.recommend: "${recommend}" is not a response of episode "${episode}"`)
    }
    const imperative = c.str(o.imperative, `${path}.imperative`, { max: 60, spoken: true })
    if (imperative && imperative.split(/\s+/).length > 6) c.errors.push(`${path}.imperative: keep live cues to 6 words or fewer`)
    c.num(o.maxAgeMs, `${path}.maxAgeMs`, 100, 30_000)
  })

  const knowledge = c.obj(p.knowledge, 'knowledge')
  if (knowledge) {
    c.arr(knowledge.concepts, 'knowledge.concepts').forEach((k, i) => {
      const path = `knowledge.concepts[${i}]`
      const o = c.obj(k, path)
      if (!o) return
      c.str(o.id, `${path}.id`, { pattern: ID })
      c.str(o.title, `${path}.title`, { max: 100 })
      c.str(o.summary, `${path}.summary`, { max: 600, spoken: true })
      for (const f of ['good', 'bad', 'tags'] as const) c.arr(o[f], `${path}.${f}`).forEach((x, j) => c.str(x, `${path}.${f}[${j}]`, { max: 300, spoken: true }))
    })
    c.arr(knowledge.prohibitedAdvice, 'knowledge.prohibitedAdvice').forEach((x, i) => c.str(x, `knowledge.prohibitedAdvice[${i}]`, { max: 300 }))
    c.obj(knowledge.terminology, 'knowledge.terminology')
    if (knowledge.reference !== undefined) {
      c.arr(knowledge.reference, 'knowledge.reference').forEach((sec, i) => {
        const path = `knowledge.reference[${i}]`
        const o = c.obj(sec, path)
        if (!o) return
        if (!KNOWLEDGE_SECTIONS.some((k) => k.id === o.id)) c.errors.push(`${path}.id: one of ${KNOWLEDGE_SECTIONS.map((k) => k.id).join(', ')}`)
        c.arr(o.items, `${path}.items`).forEach((it, j) => {
          const io = c.obj(it, `${path}.items[${j}]`)
          if (!io) return
          c.str(io.name, `${path}.items[${j}].name`, { max: KB_LIMITS.name })
          c.str(io.detail, `${path}.items[${j}].detail`, { max: KB_LIMITS.detail })
        })
      })
      c.str(knowledge.referenceCheckedAt, 'knowledge.referenceCheckedAt', { optional: true, max: 40 })
      c.str(knowledge.referenceVersion, 'knowledge.referenceVersion', { optional: true, max: 160 })
      c.arr(knowledge.referenceSources, 'knowledge.referenceSources', true).forEach((x, i) => {
        const so = c.obj(x, `knowledge.referenceSources[${i}]`)
        if (!so) return
        c.str(so.url, `knowledge.referenceSources[${i}].url`, { pattern: /^https:\/\/\S+$/, max: 500 })
        c.str(so.title, `knowledge.referenceSources[${i}].title`, { max: 200 })
      })
    }
  }

  c.arr(p.limitations, 'limitations').forEach((l, i) => c.str(l, `limitations[${i}]`, { max: 500 }))
  c.arr(p.failClosed, 'failClosed').forEach((l, i) => c.str(l, `failClosed[${i}]`, { max: 300 }))

  return c.errors.length ? { ok: false, errors: c.errors } : { ok: true, profile: input as GameProfile }
}

export function majorVersion(version: string): number {
  return Number.parseInt(version.split('.')[0] ?? '0', 10)
}

/** Suggest a pack from the OS window title. A suggestion only: the player confirms. */
export function suggestProfile(profiles: readonly GameProfile[], windowTitle: string): GameProfile | null {
  for (const p of profiles) {
    for (const hint of p.capture.windowTitleHints ?? []) {
      if (new RegExp(hint, 'i').test(windowTitle)) return p
    }
  }
  return null
}

/** Every event type a pack can produce, with its label and category. */
export function eventCatalog(p: GameProfile): Map<string, { label: string; category: EventCategory }> {
  const out = new Map<string, { label: string; category: EventCategory }>()
  for (const e of p.events) out.set(e.id, { label: e.label, category: e.category })
  for (const e of p.vision?.events ?? []) out.set(e.type, { label: e.label, category: e.category })
  for (const r of p.scoreRules ?? []) {
    out.set(r.forEvent, { label: 'Goal scored', category: 'outcome' })
    out.set(r.againstEvent, { label: 'Goal conceded', category: 'outcome' })
  }
  for (const m of p.mistakes ?? []) out.set(m.id, { label: m.label, category: 'mistake' })
  return out
}
