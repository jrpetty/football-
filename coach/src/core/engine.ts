// CoachEngine wires the pipeline together for one capture session:
//
//   pixel frames ─┐                         ┌─ episodes → pattern memory → insights
//                 ├─ Observations → fused   ├─ heatmaps (zones)
//   vision obs ───┘   VisibleState ─────────┼─ mistakes (sustained conditions)
//                                           ├─ scoreboard goals, phase boundaries
//                                           └─ match records
//        → planner → governor (policy × mode × window) → utterances + timeline
//
// Synchronous, deterministic, and free of DOM/Electron dependencies: it runs
// the same in the Web Worker, in Node tests and on synthetic input. It never
// keeps a frame after processFrame returns.

import { FactorAnalyzer, type FactorResult } from './analyses/factors.ts'
import { BenchmarkTracker, type BenchmarkResult } from './analyses/benchmarks.ts'
import { BattleTracker, type BattleRecord } from './analyses/battles.ts'
import { ReadingLogger } from './truth/readingLog.ts'
import { SourceAgreement, type AgreementRow } from './truth/agreement.ts'
import { usualAt, withUsual } from './analyses/baseline.ts'
import { checkpointLine, eventLine, fightStartLine, fillReadings, fitCommentary, statusLine, type ReadingLookup } from './commentary.ts'
import { IntelTracker, type IntelNews, type IntelReport } from './analyses/intel.ts'
import { learnedFrom, learnedLines, ScoutRecorder, type Learned } from './analyses/learning.ts'
import { mapLines, mapModelFrom, MapTracker, weaponFitLine, type MapModel, type MapPosition } from './analyses/mapModel.ts'
import { MinimapLocator, type LocatorStatus, type MatchPins } from './map/locator.ts'
import type { StoredMosaic } from './map/minimap.ts'
import type { OfficialMap } from './map/official.ts'
import { PitchTracker, type PitchLive, type PitchMatchRecord } from './pitch/tracker.ts'
import { pitchInsights, type PitchInsight } from './pitch/insights.ts'
import { RADAR_COLOURS, radarColourHint, type RadarColour } from './pitch/radar.ts'
import { Cs2Gsi, type Cs2Rounds, type GsiStatus } from './gsi/cs2.ts'
import { roundInsights, type RoundInsight } from './analyses/rounds.ts'
import { PhaseBoundaryDetector } from './analyses/boundaries.ts'
import { HeatmapAnalyzer, type HeatmapResult } from './analyses/heatmap.ts'
import { MatchRecorder, type MatchRecord } from './analyses/matchRecorder.ts'
import { MistakeDetector, type MistakeTotals } from './analyses/mistakes.ts'
import { ScoreTracker } from './analyses/score.ts'
import { EpisodeEvaluator } from './episodes.ts'
import { evaluateEpisode, gradeAtLeast, GRADE_ORDER } from './evidence.ts'
import { EventDetector } from './events.ts'
import { FrameHealthMonitor } from './frameHealth.ts'
import { countWords, episodeSummary, insightStatement, WORD_LIMITS, type RoundRecord } from './language.ts'
import { PatternMemory, type PatternSnapshot, type PatternVerdict } from './patterns.ts'
import { liveCueMessage, matchSummaryMessage, mistakeTipMessage, roundReviewMessage } from './planner.ts'
import { CoachingGovernor, type EffectiveMode } from './policy/governor.ts'
import type { PolicyScope, ResolvedPolicy } from './policy/registry.ts'
import { eventCatalog, type EpisodeSpec, type GameProfile, type MistakeSpec, type VisionEventSpec } from './profile/schema.ts'
import { learningPrefsFor, type CoachSettings } from './settings.ts'
import { STALE_AFTER_MS, VisibleStateTracker } from './tracker.ts'
import type {
  BoundaryKind,
  CoachingMessage,
  DeliveryWindow,
  Episode,
  Frame,
  GameEvent,
  Insight,
  Observation,
  PolicyStatus,
  RoundResult,
  SignalState,
  TimelineEntry,
  Utterance,
  VisibleState,
} from './types.ts'
import { VisibleStateFuser } from './visibleState.ts'

/** The core pixel HUD must be unreadable this long before the coach says it cannot see. */
const HUD_LOST_AFTER_MS = 3000
/** Banner and replay sightings this close to a score change belong to that goal. */
const SAME_GOAL_MS = 10_000
/** How long scouting news may wait for a gap in the commentary before it is dropped as old. */
const INTEL_WAIT_MS = 45_000

export type PlayerContext = Readonly<Record<string, string | number>>

export interface EngineOptions {
  readonly policy: ResolvedPolicy
  readonly history?: PatternSnapshot | null
  readonly context?: PlayerContext
  /** Recent matches of this pack (opt-in history), for comparisons in summaries. */
  readonly matchHistory?: readonly MatchRecord[]
  /** Maps stitched from the minimap in earlier sessions (packs with a minimap). */
  readonly mosaics?: readonly StoredMosaic[]
  readonly clock?: () => Date
}

export interface EngineOutput {
  readonly entries: TimelineEntry[]
  readonly utterances: Utterance[]
  /** Utterance ids the speech engine should drop if it has not finished them. */
  readonly cancel: string[]
  /** Matches that ended in this step. */
  readonly matches: MatchRecord[]
}

export interface EngineStatus {
  readonly visible: VisibleState
  readonly signals: SignalState[]
  readonly hudReadable: boolean
  readonly effectiveMode: EffectiveMode
  readonly policy: {
    readonly status: PolicyStatus
    readonly scope: PolicyScope[]
    readonly reasons: readonly string[]
    readonly entryId: string | null
    readonly internal: boolean
  }
  readonly window: DeliveryWindow
  readonly framesProcessed: number
  readonly round: { readonly index: number; readonly active: boolean }
  readonly record: RoundRecord
  readonly score: { readonly player: number; readonly opponent: number } | null
  readonly openEpisodes: number
}

export interface InsightView {
  readonly insight: Insight
  readonly statement: string
  readonly episodeLabel: string
  /** The player's verdict from long-term memory; such patterns are never used for coaching. */
  readonly verdict: PatternVerdict | null
}

export interface SessionReport {
  readonly profileId: string
  readonly record: RoundRecord
  readonly score: { readonly player: number; readonly opponent: number } | null
  readonly heatmaps: readonly HeatmapResult[]
  readonly factors: readonly FactorResult[]
  readonly benchmarks: readonly BenchmarkResult[]
  /** Fights followed this session (strategy packs), oldest first. */
  readonly battles: readonly BattleRecord[]
  /** What the game is (map, opponent's civilisation…), as last read. */
  readonly context: Readonly<Record<string, string | number>>
  /** Scouting intel (strategy packs with a roster), or null. */
  readonly intel: IntelReport | null
  /** What past matches like this one had in common (packs that learn), for what is known of this match so far. */
  readonly learned: Learned | null
  /** Shooters: where the player is, and the map as learned from this and past matches. */
  readonly map: MapReport | null
  /** Football: the match on the radar (now, so far), and what it says. */
  readonly pitch?: PitchReport | null
  readonly rounds?: RoundsReport | null
  readonly mistakes: readonly { readonly spec: MistakeSpec; readonly totals: MistakeTotals }[]
  readonly insights: readonly InsightView[]
  readonly matches: readonly MatchRecord[]
  /** Screen readings checked against a direct source of the same number (game data, this PC's HUD reading). */
  readonly checks?: readonly AgreementRow[]
}

/** Football on the radar: everyone now, this match so far, and lines from it. */
export interface PitchReport {
  readonly live: PitchLive | null
  readonly match: PitchMatchRecord | null
  readonly insights: readonly PitchInsight[]
  /** What to change in the game's radar settings so it reads well (null: nothing). */
  readonly hint: string | null
}

/** Counter-Strike rounds from the game's own data: the feed now, this match's rounds, and lines from them and past matches. */
export interface RoundsReport {
  readonly status: GsiStatus
  readonly match: Cs2Rounds | null
  readonly insights: readonly RoundInsight[]
}

/** The current map: where the player is, what was learned about it, and what that says. */
export interface MapReport {
  readonly position: MapPosition
  readonly model: MapModel | null
  readonly lines: readonly string[]
  /** Whether the weapon in hand suits the fights taken with it. */
  readonly fit: readonly string[]
  /** The minimap: whether it is being followed, and where the player is on the stitched map. */
  readonly minimap: LocatorStatus | null
  /** This match on the stitched map: deaths, kills, killers, the path, enemy pings. */
  readonly pins: MatchPins | null
}

export class CoachEngine {
  private readonly profile: GameProfile
  private settings: CoachSettings
  private policy: ResolvedPolicy
  private context: PlayerContext
  private readonly clock: () => Date
  private readonly matchHistory: MatchRecord[]

  private readonly tracker: VisibleStateTracker
  private readonly detector: EventDetector
  private readonly health = new FrameHealthMonitor()
  private readonly fuser: VisibleStateFuser
  private readonly evaluator: EpisodeEvaluator
  private readonly memory: PatternMemory
  private readonly governor: CoachingGovernor
  private readonly heatmaps: HeatmapAnalyzer
  private readonly mistakes: MistakeDetector
  private readonly scores: ScoreTracker[]
  private readonly phases: PhaseBoundaryDetector
  private readonly recorder: MatchRecorder
  /** What each side is seen building and fielding this match (packs that learn across matches). */
  private readonly scout: ScoutRecorder | null
  /** Where the player goes, fights and dies on the map (shooters). */
  private readonly mapTracker: MapTracker | null
  /** The player on the map stitched from their minimap, to the pixel (fixed minimaps). */
  private readonly locator: MinimapLocator | null
  private readonly pitch: PitchTracker | null
  private readonly gsi: Cs2Gsi | null
  /** The last event recorded per de-duplication key, so a later sighting of the same event can add its details. */
  private readonly lastEventUid = new Map<string, string>()
  private pitchCache: { at: number; report: PitchReport } | null = null
  /** Dimensions already recalled this match ("your last games against English…"). */
  private recalled = new Set<string>()
  /** The opponent's faction the intel's history was last linked for. */
  private historyFor: string | null = null
  /** When the current match began (readings before it belong to the last match). */
  private matchSince = -Infinity
  private readonly factors: FactorAnalyzer
  private readonly benchmarks: BenchmarkTracker
  private readonly battles: BattleTracker
  private readonly intel: IntelTracker | null
  private intelQueue: { news: IntelNews; at: number }[] = []
  /** When a commentary line was last spoken (for the status line after a quiet spell). */
  private lastCommentAt: number | null = null
  /** Watched readings: the value last confirmed, and a new value waiting for a second reading. */
  private watched = new Map<string, { value: string | null; pending: string | null; pendingAt: number }>()
  /** Changes waiting for a gap in the commentary (said once; dropped when old). */
  private changeQueue: { line: string; topic: string; at: number; confidence: number; evidence: string }[] = []
  /** What was read, every 20 s of game time, for checking against the real game afterwards. */
  private readingLog: ReadingLogger | null = null
  private readonly agreement = new SourceAgreement()
  /** Index into the battle log where the current match starts. */
  private matchBattlesFrom = 0
  private gameContext: Record<string, string | number> = {}
  /** Factor tips already given this match (once each). */
  private factorTipsGiven = new Set<string>()

  private readonly episodeSpecs: ReadonlyMap<string, EpisodeSpec>
  private readonly mistakeSpecs: ReadonlyMap<string, MistakeSpec>
  private readonly visionEvents: ReadonlyMap<string, VisionEventSpec>
  private readonly catalog: ReturnType<typeof eventCatalog>
  private readonly corePixelSignals: readonly string[]
  private readonly insightCache = new Map<string, Insight>()
  private readonly lastVisionEventAt = new Map<string, number>()

  private signals: ReadonlyMap<string, SignalState> = new Map()
  private visible: VisibleState
  private frames = 0
  private hudReadable = true
  private hudUnreadableSince: number | null = null
  private round = { index: 0, active: false }
  private record: RoundRecord = { won: 0, lost: 0, drawn: 0, unread: 0 }
  private lastBoundary: BoundaryKind | null = null
  private roundEpisodeIds = new Set<string>()
  private matchHasData = false
  private readonly sessionMatches: MatchRecord[] = []
  private readonly pendingAnswers: CoachingMessage[] = []
  private msgSeq = 0
  private eventSeq = 0
  private manualSeq = 0
  private aspectChecked = false
  private lastT = 0

  constructor(profile: GameProfile, settings: CoachSettings, opts: EngineOptions) {
    this.profile = profile
    this.settings = settings
    this.policy = opts.policy
    this.context = opts.context ?? defaultContext(profile)
    this.clock = opts.clock ?? (() => new Date())
    this.matchHistory = [...(opts.matchHistory ?? [])]

    this.tracker = new VisibleStateTracker(profile.signals)
    this.detector = new EventDetector(profile.events, profile.signals)
    const ttl = new Map<string, number>(profile.vision?.state.map((s) => [s.key, s.ttlMs]) ?? [])
    const standardTtl = (profile.vision?.sampleIntervalMs ?? 3000) * 3
    const pixelIds = new Set(profile.signals.map((s) => s.id))
    this.fuser = new VisibleStateFuser((key) => ttl.get(key) ?? (pixelIds.has(key) ? STALE_AFTER_MS * 2 : standardTtl))
    this.visible = this.fuser.snapshot(0)
    this.evaluator = new EpisodeEvaluator(profile.episodes)
    this.memory = new PatternMemory(profile, settings.saveTendencies ? (opts.history ?? null) : null, settings.profileModeId, this.clock())
    this.governor = new CoachingGovernor(settings, this.policy)
    this.heatmaps = new HeatmapAnalyzer(profile.heatmaps ?? [], profile.zones ?? [])
    this.mistakes = new MistakeDetector(profile.mistakes ?? [], this.context)
    this.scores = (profile.scoreRules ?? []).map((r) => new ScoreTracker(r, sideOf(this.context[r.sideContext])))
    this.phases = new PhaseBoundaryDetector()
    this.recorder = new MatchRecorder(profile.matchStats)
    this.readingLog = readingLoggerFor(profile)
    this.scout = profile.learning ? new ScoutRecorder(profile) : null
    this.mapTracker = profile.positioning ? new MapTracker(profile.positioning, profile.knowledge.maps) : null
    const mm = profile.positioning?.minimap
    this.locator = mm ? new MinimapLocator(mm, { stored: opts.mosaics, region: settings.minimap[profile.id] ?? null, fixed: this.minimapFixed() }) : null
    this.pitch = profile.pitch ? new PitchTracker(settings.minimap[profile.id] ?? profile.pitch.radar, this.radarColours()) : null
    this.gsi = profile.gameState?.kind === 'cs2-gsi' ? new Cs2Gsi({ profileId: profile.id, maps: profile.knowledge.maps, arsenal: profile.knowledge.arsenal }) : null
    this.factors = new FactorAnalyzer(profile.factors ?? [])
    this.benchmarks = new BenchmarkTracker(profile.benchmarks ?? [], this.playerFaction())
    const clockKey = profile.benchmarks?.[0]?.timeKey ?? (profile.vision?.state.some((s) => s.key === 'gameTimeSec') ? 'gameTimeSec' : null)
    this.battles = new BattleTracker(profile.battles ?? null, clockKey)
    const roster = profile.knowledge.roster
    this.intel = profile.intel && roster ? new IntelTracker(roster, profile.intel, this.playerFaction()) : null

    this.episodeSpecs = new Map(profile.episodes.map((e) => [e.id, e]))
    this.mistakeSpecs = new Map((profile.mistakes ?? []).map((m) => [m.id, m]))
    this.visionEvents = new Map((profile.vision?.events ?? []).map((e) => [e.type, e]))
    this.catalog = eventCatalog(profile)
    this.corePixelSignals = [...new Set(profile.episodes.flatMap((e) => e.visibility))].filter((k) => pixelIds.has(k))
    for (const spec of profile.episodes) this.refreshInsight(spec.id)
  }

  /** Notices to show once when the session starts. */
  startNotices(now: number): TimelineEntry[] {
    const out: TimelineEntry[] = [notice(now, this.policy.scope.has('live') ? 'info' : 'warning', `Policy: ${this.policy.reasons[0]}`)]
    const { downgradeReason } = this.governor.mode
    if (downgradeReason) out.push(notice(now, 'warning', downgradeReason))
    if (this.memory.historyNotice) out.push(notice(now, 'info', this.memory.historyNotice))
    const commentary = this.commentaryNotice()
    if (commentary) out.push(commentary(now))
    const faction = this.playerFaction()
    for (const b of this.profile.benchmarks ?? []) {
      const skip = faction ? b.byFaction?.[faction]?.skip : undefined
      if (skip) out.push(notice(now, 'info', `${b.label}: not compared. ${skip}`))
    }
    if (this.profile.validation.status !== 'verified') {
      out.push(notice(now, 'info', `"${this.profile.name}" is ${this.profile.validation.status}: ${this.profile.validation.notes}`))
    }
    return out
  }

  configure(settings: CoachSettings, policy: ResolvedPolicy, context: PlayerContext | null, now: number): TimelineEntry[] {
    const before = this.governor.mode.downgradeReason
    const commentaryBefore = this.commentaryNotice() !== null
    this.settings = settings
    this.policy = policy
    if (context) {
      this.context = context
      this.mistakes.setContext(context)
      this.intel?.setPlayerFaction(this.playerFaction())
      this.benchmarks.setFaction(this.playerFaction())
      ;(this.profile.scoreRules ?? []).forEach((r, i) => this.scores[i].setSide(sideOf(context[r.sideContext])))
    }
    this.governor.configure(settings, policy)
    this.locator?.configure(settings.minimap[this.profile.id] ?? null, this.minimapFixed())
    if (this.profile.pitch) this.pitch?.configure(settings.minimap[this.profile.id] ?? this.profile.pitch.radar, this.radarColours())
    const after = this.governor.mode.downgradeReason
    const out = after && after !== before ? [notice(now, 'warning', after)] : []
    const commentary = this.commentaryNotice()
    if (commentary && !commentaryBefore) out.push(commentary(now))
    return out
  }

  /** Whether the player's minimap is fixed (north up), as they said; fixed unless they said rotating. */
  private minimapFixed(): boolean {
    const k = this.profile.positioning?.minimap?.fixedContextKey
    return !k || this.context[k] !== 'rotating'
  }

  /** The team colours the player set for the radar (null: the game's default colours). */
  /** Radar colour settings that make the radar hard or impossible to read. */
  private radarHint(): string | null {
    const spec = this.profile.pitch
    const v = (k: string | undefined) => (k ? String(this.context[k] ?? 'default') : 'default')
    return radarColourHint(v(spec?.oursColourKey), v(spec?.theirsColourKey))
  }

  private radarColours(): { ours: RadarColour | null; theirs: RadarColour | null } {
    const spec = this.profile.pitch
    const pick = (k: string | undefined) => {
      const v = k ? this.context[k] : undefined
      return typeof v === 'string' && v in RADAR_COLOURS ? (v as RadarColour) : null
    }
    return { ours: pick(spec?.oursColourKey), theirs: pick(spec?.theirsColourKey) }
  }

  /** Was a menu, loading or results screen showing a moment ago (no minimap to follow)? */
  private offPlay(t: number): boolean {
    const p = this.visible.values.phase
    return !!p && !p.conflicted && (p.value === 'menu' || p.value === 'match_end') && t - p.observedAt < 8000
  }

  private playerFaction(): string | null {
    const key = this.profile.intel?.playerFactionContext
    const v = key ? this.context[key] : undefined
    return typeof v === 'string' && v ? v : null
  }

  /** Commentary is on but cannot speak here: say so once, instead of going quiet. */
  private commentaryNotice(): ((now: number) => TimelineEntry) | null {
    if (!this.settings.commentary) return null
    if (!this.policy.scope.has('live'))
      return (now) => notice(now, 'warning', `Live commentary is on, but it cannot speak during play here: ${this.policy.reasons[0]}. You get the review after the match.`)
    if (this.settings.mode === 'silent') return (now) => notice(now, 'warning', 'Live commentary is on, but Silent tracker mode never speaks.')
    if (this.settings.muted) return (now) => notice(now, 'warning', 'Live commentary is on, but the coach is muted.')
    if (!this.profile.commentary && !this.profile.benchmarks?.length) return (now) => notice(now, 'info', 'This pack has little to comment on live; tips and answers still work.')
    return (now) => notice(now, 'info', 'Live commentary is on: short spoken lines about what the coach sees.')
  }

  /** Capture paused, target changed, or resumed: the picture cannot be trusted while set. */
  setCaptureProblem(reason: string | null, now: number): TimelineEntry[] {
    this.fuser.setStale('capture', reason)
    if (reason) this.tracker.reset()
    return reason ? [notice(now, 'warning', reason)] : []
  }

  // --- Inputs --------------------------------------------------------------------------

  processFrame(frame: Frame): EngineOutput {
    const t = frame.timestamp
    const out = emptyOutput()
    this.frames++
    this.lastT = t

    const health = this.health.check(frame)
    const before = this.fuser.snapshot(t).staleReasons
    this.fuser.setStale('frames', health.problem)
    if (health.problem && !before.includes(health.problem)) {
      out.entries.push(notice(t, 'warning', `${health.problem} Nothing is inferred until it recovers.`))
    }
    if (health.sizeChanged) {
      this.tracker.reset()
      this.aspectChecked = false
      out.entries.push(notice(t, 'info', `The captured window changed size to ${frame.width}×${frame.height}.`))
    }
    this.checkAspect(frame, out)
    if (this.locator && !health.problem) this.locator.frame(frame, this.offPlay(t))
    if (this.pitch && !health.problem) this.pitch.frame(frame, this.offPlay(t))

    if (this.profile.signals.length) {
      this.signals = this.tracker.update(frame)
      this.fuser.ingest(pixelObservations(this.signals, t, this.profile.id))
      this.checkHud(t, out)
    }
    const pixelEvents = this.profile.events.length ? this.detector.update(this.signals, t) : []
    this.step(t, pixelEvents, out)
    return out
  }

  /** Observations from the vision recogniser (already schema-validated), arriving at `now`. */
  ingestObservations(observations: readonly Observation[], now: number): EngineOutput {
    const out = emptyOutput()
    this.lastT = Math.max(this.lastT, now)
    this.fuser.ingest(observations)
    for (const o of observations) this.agreement.record(o)
    // Fights are followed frame by frame, before banners are de-duplicated.
    const events: GameEvent[] = this.battles.update(observations, this.fuser.snapshot(now), now)
    for (const obs of observations) {
      if (obs.kind !== 'event') continue
      const spec = this.visionEvents.get(obs.eventType)
      if (!spec) continue
      const key = [obs.eventType, ...(spec.dedupeBy ?? []).map((a) => String(obs.attributes?.[a]))].join('|')
      const last = this.lastVisionEventAt.get(key)
      // The game's own data is exact: every event it reports counts. A screen sighting of an event
      // already recorded (the same banner, or a death the game reported first) adds what it saw.
      if (obs.source !== 'game-api' && last !== undefined && obs.timestampMs - last < spec.dedupeMs) {
        const uid = this.lastEventUid.get(key)
        if (uid && (obs.attributes || obs.zone)) {
          this.heatmaps.amend(uid, obs.zone, obs.attributes ?? {})
          this.factors.amend(uid, obs.attributes)
        }
        continue
      }
      this.lastVisionEventAt.set(key, obs.timestampMs)
      const uid = `${obs.eventType}#v${++this.eventSeq}`
      this.lastEventUid.set(key, uid)
      events.push({
        uid,
        ruleId: obs.eventType,
        label: spec.label,
        category: spec.category,
        at: obs.timestampMs,
        confidence: obs.confidence,
        evidence: obs.evidence.join('; ') || 'seen by the vision recogniser',
        ...(obs.zone ? { zone: obs.zone } : {}),
        ...(obs.attributes ? { attributes: obs.attributes } : {}),
        source: obs.source,
      })
    }
    this.step(now, events, out)
    return out
  }

  /** One update from the game's own data feed (Counter-Strike 2 Game State Integration), arriving at `now`. */
  ingestGameState(payload: unknown, now: number): EngineOutput {
    if (!this.gsi) return emptyOutput()
    const obs = this.gsi.payload(payload, now)
    if (obs.some((o) => o.kind === 'event')) this.matchHasData = true
    return this.ingestObservations(obs, now)
  }

  /** The player marked a round or match end by hand (for HUDs the pack cannot read). */
  manualBoundary(kind: Extract<BoundaryKind, 'round-end' | 'match-end'>, result: RoundResult, now: number): EngineOutput {
    const out = emptyOutput()
    const ev: GameEvent = {
      uid: `manual-${kind}#${++this.manualSeq}`,
      ruleId: `manual-${kind}`,
      label: kind === 'round-end' ? 'Round ended (marked by you)' : 'Match ended (marked by you)',
      category: 'boundary',
      at: now,
      confidence: 1,
      evidence: 'marked in the app, not read from the screen',
      boundary: kind,
      result,
      manual: true,
    }
    this.handleEvents([ev], now, out)
    return out
  }

  /** Capture stopped: close open episodes and, if the match had content, summarise it. */
  stop(now: number): EngineOutput {
    const out = emptyOutput()
    for (const ep of this.evaluator.closeAll(now, 'session-ended')) this.recordEpisode(ep, out)
    const fights = this.battles.flush()
    if (fights.length) this.handleEvents(fights, now, out)
    if (this.matchHasData) this.endMatch({ result: 'unknown', at: now, manual: true }, now, out, 'idle')
    this.releaseAnswers(now, out, 'idle')
    return out
  }

  /**
   * Deliver an answer to the player's question. It goes through the governor
   * like any message: during restricted play it is held and delivered when
   * the match ends.
   */
  answer(question: string, text: string, author: CoachingMessage['author'], now: number): EngineOutput {
    const out = emptyOutput()
    const msg: Omit<CoachingMessage, 'id'> = {
      category: 'descriptive',
      text,
      confidence: 1,
      grade: 'moderate',
      evidenceSummary: `You asked: "${question.slice(0, 200)}"${author === 'ai' ? ' (answer phrased by the language model from this session\'s data)' : ''}`,
      topic: `answer:${now}`,
      priority: 'normal',
      createdAt: now,
      basedOnObservationAt: null,
      expiresAtMs: now + 60 * 60_000,
      requestedByPlayer: true,
      author,
    }
    const id = this.decide(msg, now, out)
    const last = out.entries.at(-1)
    if (last?.type === 'message' && last.decision.decision === 'queued') this.pendingAnswers.push({ ...msg, id })
    return out
  }

  // --- Queries --------------------------------------------------------------------------

  get status(): EngineStatus {
    const p = this.policy
    return {
      visible: this.visible,
      signals: [...this.signals.values()],
      hudReadable: this.hudReadable && !this.visible.stale,
      effectiveMode: this.governor.mode,
      policy: { status: p.status, scope: [...p.scope], reasons: p.reasons, entryId: p.entry?.id ?? null, internal: p.entry?.basis === 'internal' },
      window: this.window(),
      framesProcessed: this.frames,
      round: { ...this.round },
      record: { ...this.record },
      score: this.scores[0]?.score ?? null,
      openEpisodes: this.evaluator.openCount,
    }
  }

  insights(): InsightView[] {
    return [...this.insightCache.values()]
      .sort((a, b) => GRADE_ORDER[b.grade] - GRADE_ORDER[a.grade] || b.probability - a.probability)
      .map((insight) => {
        const spec = this.episodeSpecs.get(insight.episodeId)!
        return { insight, statement: insightStatement(spec, insight), episodeLabel: spec.label, verdict: this.memory.verdict(insight.episodeId) }
      })
  }

  report(): SessionReport {
    const totals = this.mistakes.totals('session', this.lastT)
    return {
      profileId: this.profile.id,
      record: { ...this.record },
      score: this.scores[0]?.score ?? null,
      heatmaps: this.heatmaps.results('session'),
      factors: this.factors.results('session'),
      benchmarks: this.benchmarksWithUsual(),
      battles: this.battles.battles(),
      // Between matches, the last match's (for the review and answers) until the next one is read.
      context: Object.keys(this.gameContext).length ? { ...this.gameContext } : { ...(this.sessionMatches.at(-1)?.context ?? {}) },
      intel: this.intel?.report() ?? null,
      learned: this.profile.learning && this.learning().enabled ? learnedFrom(this.matchHistory, this.profile, this.reportFilter()) : null,
      map: this.mapReport(),
      pitch: this.pitchReport(),
      rounds: this.roundsReport(),
      mistakes: (this.profile.mistakes ?? []).map((spec) => ({ spec, totals: totals[spec.id] })),
      insights: this.insights(),
      matches: [...this.sessionMatches],
      checks: this.agreement.results(),
    }
  }

  snapshot(now: Date): PatternSnapshot {
    return this.memory.snapshot(now)
  }

  /** Where a message produced now would land. */
  window(): DeliveryWindow {
    if (this.lastBoundary === 'match-end') return 'post_match'
    if (this.lastBoundary === 'round-end') return 'round_break'
    return 'during_play'
  }

  // --- Pipeline -------------------------------------------------------------------------

  private step(now: number, events: GameEvent[], out: EngineOutput): void {
    this.visible = this.fuser.snapshot(now)
    const derived: GameEvent[] = []
    if (this.profile.vision) derived.push(...this.phases.update(this.visible))
    for (const [i, tracker] of this.scores.entries()) {
      const u = tracker.update(this.visible, now)
      derived.push(...u.events)
      // Goals on the radar: their last seconds, and (colours not set) which team is the player's.
      const rule = this.profile.scoreRules?.[i]
      for (const e of u.events) if (rule && (e.ruleId === rule.forEvent || e.ruleId === rule.againstEvent)) this.pitch?.goal(e.at, e.ruleId === rule.forEvent ? 'for' : 'against')
      // A confirmed score change is a new goal: sightings from well before it belonged to an
      // earlier goal, so they must not swallow this goal's banner or replay as a repeat.
      const confirm = this.profile.scoreRules?.[i].confirmEvent
      if (confirm && u.events.length) this.forgetSightings(confirm, Math.min(...u.events.map((e) => e.at)) - SAME_GOAL_MS)
      for (const n of u.notices) out.entries.push(notice(now, 'info', n))
      for (const a of u.amendments) {
        this.heatmaps.amend(a.uid, a.zone, a.attributes)
        this.factors.amend(a.uid, a.attributes)
      }
    }
    derived.push(...this.mistakes.update(this.visible, now))
    this.recorder.observe(this.visible, now)
    this.readingLog?.observe(this.visible, now)
    if (this.learning().enabled) this.scout?.observe(this.visible, now)
    this.mapTracker?.observe(this.visible)
    if (this.locator && this.profile.positioning) this.locator.observe(this.visible, this.profile.positioning)
    this.benchmarks.observe(this.visible)
    for (const k of this.profile.contextKeys ?? []) {
      const v = this.visible.values[k]
      // Only this match's readings: the last match's map or opponent can linger in the state for minutes.
      if (v && !v.conflicted && v.observedAt >= this.matchSince && (typeof v.value === 'string' || typeof v.value === 'number')) this.gameContext[k] = v.value
    }

    // Vision sightings of goals enrich (or wait for) scoreboard goals.
    for (const ev of events) {
      for (const [i, rule] of (this.profile.scoreRules ?? []).entries()) {
        if (rule.confirmEvent !== ev.ruleId) continue
        for (const a of this.scores[i].sighting(ev)) {
          this.heatmaps.amend(a.uid, a.zone, a.attributes)
          this.factors.amend(a.uid, a.attributes)
          out.entries.push(notice(ev.at, 'info', `Goal details added from a replay: ${a.zone ? this.zoneLabel(a.zone) : 'location unclear'}.`))
        }
      }
    }
    this.handleEvents([...events, ...derived].sort((a, b) => a.at - b.at), now, out)
    this.commentOnState(now, out)
  }

  private forgetSightings(eventType: string, before: number): void {
    for (const [key, at] of this.lastVisionEventAt) if (key.split('|')[0] === eventType && at < before) this.lastVisionEventAt.delete(key)
  }

  private handleEvents(events: GameEvent[], now: number, out: EngineOutput): void {
    // Deaths placed on the map this tick, for the commentary line.
    const placedDeaths = new Map<string, { area: string | null; killerSpot: string | null }>()
    for (const ev of events) {
      out.entries.push({ type: 'event', at: ev.at, event: ev })
      if (this.learning().enabled) this.scout?.event(ev, now, this.visible)
      const placed = this.mapTracker?.event(ev, this.visible) ?? null
      if (placed) placedDeaths.set(ev.uid, placed)
      this.pinEvent(ev)
      if (placed && placed.repeat >= 2 && placed.killerSpot && placed.area) this.heldAngleTip(placed.killerSpot, placed.area, placed.repeat, now, out)
      this.heatmaps.record(ev)
      this.factors.record(ev)
      if (ev.category !== 'boundary') this.matchHasData = true
    }
    if (events.some((ev) => (this.profile.factors ?? []).some((f) => f.lossEvent === ev.ruleId))) this.maybeFactorTip(now, out)
    if (this.settings.commentary) {
      for (const ev of events) {
        // In training mode a mistake already gets its tip; the commentary would say it twice.
        if (ev.category === 'mistake' && this.settings.mode === 'training') continue
        // Where it happened on the map, and where the killer shot from, when the screen showed it
        // (then the named area replaces the rough minimap zone).
        const p = placedDeaths.get(ev.uid)
        const line = eventLine(this.profile, p?.area ? { ...ev, zone: undefined } : ev, this.reading)
        const from = p?.killerSpot ? (p.killerSpot === p.area ? ', shot from inside it' : `, shot from ${p.killerSpot}`) : ''
        const where = p?.area ? ` At ${p.area}${from}.` : ''
        if (line) this.comment(`${line}${where}`, `event:${ev.ruleId}:${ev.uid}`, ev.at, ev.confidence, ev.evidence, now, out)
      }
    }

    const visible = (key: string) => this.signals.get(key)?.known === true || this.fuser.get(key, now) !== null
    for (const ep of this.evaluator.update(now, events, visible)) this.recordEpisode(ep, out)

    for (const ev of events) {
      if (ev.boundary === 'round-start') {
        this.round = { index: this.round.index + 1, active: true }
        this.roundEpisodeIds = new Set()
        this.lastBoundary = null
        out.cancel.push(...this.governor.onRoundStart(now))
      } else if (ev.boundary === 'round-end') {
        this.endRound(ev, now, out)
      } else if (ev.boundary === 'match-end') {
        if (this.round.active) this.countRound(ev.result ?? 'unknown')
        this.endMatch({ result: ev.result ?? 'unknown', at: ev.at, manual: ev.manual === true }, now, out, 'post_match')
      } else if (ev.category === 'mistake') {
        const spec = this.mistakeSpecs.get(ev.ruleId)
        const msg = spec ? mistakeTipMessage(spec, ev, this.profile.liveFreshnessMs ?? 4000, now) : null
        if (msg) this.decide(msg, now, out)
      } else {
        this.maybeLiveCue(ev, now, out)
      }
    }
  }

  private recordEpisode(ep: Episode, out: EngineOutput): void {
    const spec = this.episodeSpecs.get(ep.episodeId)!
    this.memory.record(ep)
    if (!ep.excluded) {
      this.roundEpisodeIds.add(ep.episodeId)
      this.matchHasData = true
    }
    const excluded = ep.excluded ? ` (not counted: ${ep.excluded.replaceAll('-', ' ')})` : ''
    const label = this.catalog.get(spec.trigger)?.label ?? spec.trigger
    out.entries.push({ type: 'episode', at: ep.endedAt, episode: ep, summary: episodeSummary(spec, ep, label) + excluded })
    const changed = this.refreshInsight(ep.episodeId)
    if (changed) out.entries.push({ type: 'insight', at: ep.endedAt, insight: changed, statement: insightStatement(spec, changed) })
  }

  private refreshInsight(episodeId: string): Insight | null {
    const spec = this.episodeSpecs.get(episodeId)!
    const { counts, usesHistory } = this.memory.counts(episodeId)
    const next = evaluateEpisode(spec, counts, usesHistory)
    const prev = this.insightCache.get(episodeId)
    if (!next) {
      this.insightCache.delete(episodeId)
      return null
    }
    this.insightCache.set(episodeId, next)
    const changed = !prev || prev.key !== next.key || prev.grade !== next.grade
    return changed && next.grade !== 'insufficient' ? next : null
  }

  // --- Planning ---------------------------------------------------------------------------

  private maybeLiveCue(ev: GameEvent, now: number, out: EngineOutput): void {
    if (this.visible.stale) return
    for (const cue of this.profile.liveCues) {
      if (cue.trigger !== ev.ruleId) continue
      const insight = this.insightCache.get(cue.episode)
      if (this.memory.verdict(cue.episode) !== null) continue // the player dismissed or disputed this pattern
      // Only cue when the player's own visible history supports the advice.
      if (!insight || insight.better.response !== cue.recommend || !gradeAtLeast(insight.grade, 'weak')) continue
      this.decide(liveCueMessage(this.settings.tone, cue, this.episodeSpecs.get(cue.episode)!, insight, ev, now), now, out)
    }
  }

  private countRound(result: RoundResult): void {
    if (result === 'win') this.record.won++
    else if (result === 'loss') this.record.lost++
    else if (result === 'draw') this.record.drawn++
    else this.record.unread++
    this.round = { ...this.round, active: false }
  }

  private endRound(ev: GameEvent, now: number, out: EngineOutput): void {
    this.countRound(ev.result ?? 'unknown')
    this.lastBoundary = 'round-end'
    this.matchHasData = true
    const rules = this.governor.rules
    const msg = roundReviewMessage(
      {
        ...this.reviewInputs('match', rules.reviewLines, rules.minReviewGrade),
        result: ev.result ?? 'unknown',
        roundEpisodeIds: this.roundEpisodeIds,
        at: ev.at,
        confidence: ev.confidence,
      },
      now,
    )
    this.roundEpisodeIds = new Set()
    if (msg) this.decide(msg, now, out, 'round_break')
  }

  private endMatch(end: { result: RoundResult; at: number; manual: boolean }, now: number, out: EngineOutput, window: DeliveryWindow): void {
    this.mistakes.closeAll(now)
    this.lastBoundary = 'match-end'
    const score = this.scores[0]?.score ?? null
    const result: RoundResult =
      score && !end.manual ? (score.player > score.opponent ? 'win' : score.player < score.opponent ? 'loss' : 'draw') : end.result
    const limit = { minimal: 1, balanced: 2, detailed: 3 }[this.settings.verbosity]
    const inputs = this.reviewInputs('match', limit, this.governor.rules.minReviewGrade)
    const record = this.recorder.finish({
      now,
      endedAt: this.clock(),
      gameProfileId: this.profile.id,
      profileVersion: this.profile.version,
      modeId: this.settings.profileModeId,
      result,
      score,
      rounds: { ...this.record },
      mistakes: this.mistakes.totals('match', now),
      heatmaps: this.heatmaps.results('match'),
      factors: this.factors.matchCounts(),
      benchmarks: this.benchmarks.results(),
      battles: battleCounts(this.battles.battles().slice(this.matchBattlesFrom)),
      context: { ...this.gameContext, ...(this.playerFaction() ? { playerFaction: this.playerFaction()! } : {}) },
      scout: this.learning().enabled ? (this.scout?.finish(this.learningDims()) ?? null) : (this.scout?.newMatch(), null),
      mapModel: this.finishMap(),
      pitch: this.pitch?.finish() ?? null,
      roundLog: this.gsi?.finish() ?? null,
      readings: this.readingLog?.take() ?? null,
      endedManually: end.manual,
    })
    this.pitchCache = null
    if (this.matchHasData || record.scout || record.mapModel || record.pitch || record.roundLog) {
      const msg = matchSummaryMessage(
        {
          ...inputs,
          rounds: this.record,
          score,
          stats: record.stats,
          history: this.matchHistory.filter((m) => m.gameProfileId === this.profile.id),
          limit,
        },
        now,
      )
      this.decide(msg, now, out, window)
      this.releaseAnswers(now, out, window)
      out.matches.push(record)
      this.sessionMatches.push(record)
      this.matchHistory.push(record)
    }
    this.record = { won: 0, lost: 0, drawn: 0, unread: 0 }
    this.round = { index: 0, active: false }
    this.matchHasData = false
    this.heatmaps.newMatch()
    this.factors.newMatch()
    this.benchmarks.newMatch()
    this.battles.newMatch()
    this.intel?.newMatch(this.lastT)
    this.intelQueue = []
    this.watched = new Map()
    this.changeQueue = []
    this.recalled = new Set()
    this.historyFor = null
    this.matchSince = this.lastT
    this.matchBattlesFrom = this.battles.battles().length
    this.gameContext = {}
    this.factorTipsGiven = new Set()
    this.mistakes.newMatch()
    for (const s of this.scores) s.newMatch()
  }

  /**
   * A short live tip once an avoidable pattern in this match is moderately
   * clear (e.g. dying in the open). Training mode only, where policy allows
   * live tips; elsewhere the governor holds it for the review.
   */
  private maybeFactorTip(now: number, out: EngineOutput): void {
    for (const res of this.factors.results('match')) {
      for (const f of res.findings) {
        if (f.kind !== 'weakness' || !f.liveTip || f.grade === 'weak') continue
        const topic = `factor:${f.factorId}:${f.value}`
        if (this.factorTipsGiven.has(topic)) continue
        this.factorTipsGiven.add(topic)
        this.decide(
          {
            category: 'training',
            text: f.liveTip,
            confidence: 0.8,
            grade: f.grade,
            evidenceSummary: f.statement,
            topic,
            priority: 'normal',
            createdAt: now,
            basedOnObservationAt: now,
            expiresAtMs: now + 20_000,
            author: 'template',
          },
          now,
          out,
        )
        return
      }
    }
  }

  /** Commentary that does not come from a single event: a fight starting, a benchmark checkpoint. */
  private commentOnState(now: number, out: EngineOutput): void {
    const news = this.intel?.update(this.visible, now) ?? []
    this.linkHistory()
    // Past matches like this one: on the timeline and spoken with the commentary, only where live coaching
    // is allowed; elsewhere they wait for Insights after the match, like the rest of the scouting.
    const recalled = this.policy.scope.has('live') ? this.recall(now) : []
    for (const r of recalled) out.entries.push(notice(now, 'info', r.news.text))
    const start = this.battles.takeStart()
    const fresh = this.benchmarks.takeFresh()
    if (!this.settings.commentary) return
    if (start) this.comment(fightStartLine(this.profile, start), `fight-start:${now}`, now, 0.8, 'first frame of a fight', now, out)
    for (const f of fresh) {
      const usual = usualAt(this.matchHistory, f.spec.id, f.atSec, this.playerFaction())
      this.comment(checkpointLine(f.spec, f.atSec, f.actual, f.expected, usual?.value ?? null), `checkpoint:${f.spec.id}:${f.atSec}`, now, 0.85, `${f.spec.label} at ${f.atSec}s`, now, out)
    }
    // Scouting news is said once, so it waits its turn rather than being lost to the pacing; reads go first.
    this.intelQueue.push(...news.map((n) => ({ news: n, at: now })), ...recalled)
    this.intelQueue = this.intelQueue.filter((q) => now - q.at < INTEL_WAIT_MS).sort((a, b) => Number(b.news.kind === 'read') - Number(a.news.kind === 'read'))
    const next = this.intelQueue[0]
    if (next && this.comment(next.news.text, `intel:${next.news.kind}:${next.news.id}`, next.at, 0.8, `seen on screen: ${next.news.id}`, now, out)) this.intelQueue.shift()
    this.commentOnChanges(now, out)
    this.commentStatus(now, out)
  }

  /** A fresh, agreed reading as commentary may say it, or null. */
  private readonly reading: ReadingLookup = (key) => {
    const v = this.visible.values[key]
    return v && !v.conflicted ? v.value : null
  }

  /** A watched reading (the player's age) that changed and was read the same way twice: said once. */
  private commentOnChanges(now: number, out: EngineOutput): void {
    for (const spec of this.profile.commentaryChanges ?? []) {
      const v = this.visible.values[spec.key]
      if (!v || v.conflicted || (typeof v.value !== 'string' && typeof v.value !== 'number')) continue
      const value = String(v.value)
      const w = this.watched.get(spec.key) ?? { value: null, pending: null, pendingAt: -Infinity }
      if (value === w.value) {
        this.watched.set(spec.key, { ...w, pending: null })
        continue
      }
      // A new value counts once it is seen in two different readings.
      if (w.pending !== value) {
        this.watched.set(spec.key, { ...w, pending: value, pendingAt: v.observedAt })
        continue
      }
      if (v.observedAt <= w.pendingAt) continue
      this.watched.set(spec.key, { value, pending: null, pendingAt: -Infinity })
      // The first value of a match is where the player already is, not news.
      if (w.value === null) continue
      const line = fillReadings(spec.text, this.reading)
      if (line) this.changeQueue.push({ line, topic: `change:${spec.key}:${value}`, at: now, confidence: v.confidence, evidence: `${spec.key} read as ${value} twice` })
    }
    // Said once, so it waits its turn rather than being lost to the pacing.
    this.changeQueue = this.changeQueue.filter((c) => now - c.at < INTEL_WAIT_MS)
    const next = this.changeQueue[0]
    if (next && this.comment(next.line, next.topic, next.at, next.confidence, next.evidence, now, out)) this.changeQueue.shift()
  }

  /** After a quiet spell, a short line of what is on screen now, so the player knows the coach is still watching. */
  private commentStatus(now: number, out: EngineOutput): void {
    const spec = this.profile.commentaryStatus
    if (!spec || this.window() !== 'during_play') return
    this.lastCommentAt ??= now
    if (now - this.lastCommentAt < spec.everySec * 1000) return
    const line = statusLine(spec.parts, spec.minParts, this.reading)
    if (!line) return
    this.comment(line, `status:${now}`, now, 0.8, 'readings on screen now', now, out)
  }

  /**
   * What this match is, as far as known: each learning dimension's value from
   * the screen (map, mode), the player's settings (their civilisation) or the
   * recognised opponent's faction.
   */
  private learningDims(): Record<string, string> {
    const out: Record<string, string> = {}
    for (const d of this.profile.learning?.dimensions ?? []) {
      const raw =
        d.from === 'faction' ? this.intel?.opponentFaction?.name : d.from === 'context' ? this.context[d.key] : (this.gameContext[d.key] ?? this.thisMatch(d.key))
      const v = typeof raw === 'number' ? String(raw) : typeof raw === 'string' ? raw.trim().slice(0, 80) : ''
      if (v) out[d.id] = v
    }
    return out
  }

  /** Once the opponent's faction is known, what the player's past games against it showed, for the reference plans. */
  private linkHistory(): void {
    const faction = this.learning().enabled ? (this.intel?.opponentFaction?.name ?? null) : null
    if (faction === this.historyFor) return
    this.historyFor = faction
    const dim = this.profile.learning?.dimensions.find((d) => d.from === 'faction')
    const l = faction && dim ? learnedFrom(this.matchHistory, this.profile, { [dim.id]: faction }) : null
    const seen = new Map<string, number>()
    for (const t of l?.tracks.filter((x) => x.side === 'them') ?? []) for (const i of t.items) seen.set(i.name, Math.max(seen.get(i.name) ?? 0, i.seenIn))
    this.intel?.setHistory(l ? { matches: l.matches, seen } : null)
  }

  /** What the report's learning is about: this match's live dimensions, or between matches the last match's. */
  private reportFilter(): Record<string, string> {
    const now = this.liveFilter()
    if (Object.keys(now).length) return now
    const last = this.sessionMatches.at(-1)?.scout?.dims ?? {}
    return Object.fromEntries((this.profile.learning?.dimensions ?? []).filter((d) => d.live && last[d.id]).map((d) => [d.id, last[d.id]]))
  }

  /** A death or kill, for the stitched map: when it was read, where (by name), the killer's spot and weapons. */
  private pinEvent(ev: GameEvent): void {
    const ps = this.profile.positioning
    if (!this.locator || !ps) return
    const kind = ev.ruleId === ps.deathEvent ? 'death' : ps.killEvent && ev.ruleId === ps.killEvent ? 'kill' : null
    if (!kind) return
    const str = (k: string | undefined, within: number) => {
      const v = k ? this.visible.values[k] : undefined
      return v && !v.conflicted && typeof v.value === 'string' && v.value.trim() && Math.abs(v.observedAt - ev.at) <= within ? v.value.trim().slice(0, 40) : null
    }
    const range = ps.rangeAttribute ? ev.attributes?.[ps.rangeAttribute] : undefined
    this.locator.event({
      kind,
      t: ev.at,
      area: str(ps.locationKey, 6000),
      killerSpot: kind === 'death' ? str(ps.killerSpotKey, 8000) : null,
      weapon: kind === 'death' ? str(ps.killerWeaponKey, 8000) : str(ps.weaponKey, 30_000),
      range: typeof range === 'string' && range !== 'unknown' ? range : null,
    })
  }

  /** The match's map record, with its pins on the stitched map. */
  private finishMap(): MatchRecord['mapModel'] | null {
    const record = this.mapTracker?.finish() ?? null
    const pins = this.locator?.finish() ?? null
    return record ? { ...record, ...(pins && (pins.pins.length || pins.trail.length) ? { pins } : {}) } : null
  }

  /** Counter-Strike rounds from the game's data: the feed now, this match, and lines from it and past matches. */
  private roundsReport(): RoundsReport | null {
    if (!this.gsi) return null
    const match = this.gsi.snapshot()
    const past = this.matchHistory.flatMap((m) => (m.roundLog ? [m.roundLog] : []))
    return { status: this.gsi.status(this.lastT), match, insights: roundInsights([...past, ...(match ? [match] : [])]) }
  }

  /** Football on the radar: now, this match so far (at most every 2 s), and lines from it and past matches. */
  private pitchReport(): PitchReport | null {
    if (!this.pitch) return null
    const t = this.lastT
    if (this.pitchCache && t - this.pitchCache.at < 2000) return { ...this.pitchCache.report, live: this.pitch.status(t) }
    const match = this.pitch.snapshot()
    const past = this.matchHistory.flatMap((m) => (m.pitch ? [m.pitch] : []))
    const all = [...past, ...(match ? [match] : [])]
    const report: PitchReport = { live: this.pitch.status(t), match, insights: pitchInsights(all, null, 8, this.profile.knowledge.formations), hint: this.radarHint() }
    this.pitchCache = { at: t, report }
    return report
  }

  /** The official overhead map of a map (null: none). */
  setOfficial(map: string, off: OfficialMap | null): void {
    this.locator?.setOfficial(map, off)
  }

  takeMosaic(now: number, force = false): StoredMosaic | null {
    return this.locator?.takeSave(now, force) ?? null
  }

  /** Where the player is, and the current (or last) map as learned from past matches and this one. */
  private mapReport(): MapReport | null {
    if (!this.mapTracker) return null
    const position = this.mapTracker.position(this.lastT)
    const current = this.mapTracker.snapshot()
    const name = position.map ?? this.sessionMatches.at(-1)?.mapModel?.map ?? null
    const minimap = this.locator?.status(this.lastT) ?? null
    const pins = this.locator?.snapshot() ?? null
    if (!name) return { position, model: null, lines: [], fit: [], minimap, pins }
    const records = [...this.matchHistory.flatMap((m) => (m.mapModel ? [m.mapModel] : [])), ...(current ? [current] : [])]
    const model = mapModelFrom(records, this.profile.knowledge.maps, name)
    const arsenal = this.profile.knowledge.arsenal ?? []
    const byName = (n: string) => arsenal.find((a) => [a.name, ...(a.aliases ?? [])].some((x) => x.toLowerCase() === n.toLowerCase()))
    const fit = (model?.weapons ?? [])
      .slice(0, 3)
      .map((w) => weaponFitLine(w, byName(w.name)?.bestRange, model!.deathsBy, (c) => c.replace(/_/g, ' ')))
      .filter((x): x is string => !!x)
    return { position, model, lines: model ? mapLines(model) : [], fit, minimap, pins }
  }

  /** The second death this match from the same spot to the same area: that angle is held. Training tip where live tips are allowed. */
  private heldAngleTip(from: string, to: string, n: number, now: number, out: EngineOutput): void {
    const topic = `held-angle:${from}:${to}`
    if (this.factorTipsGiven.has(topic)) return
    const before = out.utterances.length
    this.decide(
      {
        category: 'training',
        // Live tips are short: the longest version that fits.
        text:
          [`${n} deaths at ${to} from ${from}: held angle. Clear it or cross elsewhere.`, `Held angle: ${from} onto ${to}. Cross elsewhere.`, `Held angle from ${from}.`].find(
            (t) => countWords(t) <= WORD_LIMITS.training,
          ) ?? `Held angle from ${from}.`,
        confidence: 0.75,
        grade: 'moderate',
        evidenceSummary: `${n} deaths this match at ${to}, killcam showing the killer at ${from}`,
        topic,
        priority: 'normal',
        createdAt: now,
        basedOnObservationAt: now,
        expiresAtMs: now + 20_000,
        author: 'template',
      },
      now,
      out,
    )
    // Said once; if the pacing held it back, the next death from there tries again.
    if (out.utterances.length > before) this.factorTipsGiven.add(topic)
  }

  /** The player's learning choices for this game. */
  private learning(): { enabled: boolean; recall: boolean; minGames: number } {
    return learningPrefsFor(this.profile.id, this.settings)
  }

  /** A state value read during this match, as text. */
  private thisMatch(key: string): string | undefined {
    const v = this.visible.values[key]
    return v && !v.conflicted && v.observedAt >= this.matchSince ? stringValue(v.value) : undefined
  }

  /** The live dimensions known so far (e.g. the opponent's civilisation once read). */
  private liveFilter(): Record<string, string> {
    const dims = this.learningDims()
    return Object.fromEntries((this.profile.learning?.dimensions ?? []).filter((d) => d.live && dims[d.id]).map((d) => [d.id, dims[d.id]]))
  }

  /** Once a live dimension is known, what past matches filed under it had in common, said once per match. */
  private recall(now: number): { news: IntelNews; at: number }[] {
    const out: { news: IntelNews; at: number }[] = []
    const prefs = this.learning()
    if (!prefs.enabled || !prefs.recall) return out
    for (const [id, value] of Object.entries(this.liveFilter())) {
      if (this.recalled.has(id)) continue
      this.recalled.add(id)
      const l = learnedFrom(this.matchHistory, this.profile, { [id]: value })
      if (!l || l.matches < prefs.minGames) continue
      const lines = [...learnedLines(l, 'them', 2), ...learnedLines(l, 'you', 3).slice(-1)]
      if (!lines.length) continue
      const games = l.matches === 1 ? 'game' : `${l.matches} games`
      out.push({ news: { kind: 'history', id: `${id}:${value}`, text: `Your last ${games} ${l.filterText}: ${lines.join(' ')}` }, at: now })
    }
    return out
  }

  /** Says a commentary line if the governor allows it now; true when it was spoken. */
  private comment(text: string, topic: string, at: number, confidence: number, evidence: string, now: number, out: EngineOutput): boolean {
    const before = out.utterances.length
    this.decide(
      {
        category: 'commentary',
        text: fitCommentary(text),
        confidence,
        grade: 'moderate',
        evidenceSummary: evidence,
        topic: `commentary:${topic}`,
        priority: 'low',
        createdAt: now,
        basedOnObservationAt: at,
        expiresAtMs: Math.max(at, now) + 10_000,
        author: 'template',
      },
      now,
      out,
    )
    const spoken = out.utterances.length > before
    if (spoken) this.lastCommentAt = now
    return spoken
  }

  /** This match's checkpoints, each with what the player usually reaches there. */
  private benchmarksWithUsual() {
    return withUsual(this.benchmarks.results(), this.matchHistory, this.playerFaction())
  }

  /** Insights the coach may use: the player has not dismissed or disputed them. */
  private usableInsights(): Insight[] {
    return [...this.insightCache.values()].filter((i) => this.memory.verdict(i.episodeId) === null)
  }

  private reviewInputs(scope: 'session' | 'match', reviewLines: number, minGrade: Insight['grade']) {
    return {
      tone: this.settings.tone,
      profile: this.profile,
      insights: this.usableInsights(),
      heatmaps: this.heatmaps.results(scope),
      factors: this.factors.results(scope),
      benchmarks: scope === 'match' ? this.benchmarksWithUsual() : [],
      battles: this.battles.battles().slice(scope === 'match' ? this.matchBattlesFrom : 0),
      intel: this.intel?.report() ?? null,
      mistakes: this.mistakes.totals(scope, this.lastT),
      queuedTopics: this.governor.takeQueuedTopics(),
      reviewLines,
      minGrade,
    }
  }

  private decide(msg: Omit<CoachingMessage, 'id'> & { id?: string }, now: number, out: EngineOutput, window: DeliveryWindow = this.window()): string {
    const full: CoachingMessage = { ...msg, id: msg.id ?? `${msg.category}-${++this.msgSeq}` }
    const { record, utterance } = this.governor.decide(full, now, window)
    out.entries.push({ type: 'message', at: now, message: full, decision: record })
    if (utterance) out.utterances.push(utterance)
    return full.id
  }

  /** Answers held during restricted play, delivered once the match is over. */
  private releaseAnswers(now: number, out: EngineOutput, window: DeliveryWindow): void {
    const held = this.pendingAnswers.splice(0)
    for (const a of held) this.decide({ ...a, text: `Earlier you asked: ${a.text}`.slice(0, 900) }, now, out, window)
  }

  private checkAspect(frame: Frame, out: EngineOutput): void {
    if (this.aspectChecked) return
    this.aspectChecked = true
    const expected = this.profile.capture.aspectRatio
    if (expected === null) return
    const aspect = frame.width / frame.height
    if (Math.abs(aspect - expected) / expected > 0.03) {
      out.entries.push(
        notice(
          frame.timestamp,
          'warning',
          `The captured image is ${aspect.toFixed(2)}:1 but this pack expects ${expected.toFixed(2)}:1. HUD regions may be misaligned. ${this.profile.capture.notes}`,
        ),
      )
    }
  }

  private checkHud(t: number, out: EngineOutput): void {
    if (this.corePixelSignals.length === 0) return
    const unreadable = this.corePixelSignals.every((id) => !this.signals.get(id)?.known)
    if (!unreadable) {
      this.hudUnreadableSince = null
      if (!this.hudReadable) {
        this.hudReadable = true
        out.entries.push(notice(t, 'info', 'HUD readable again. Observing.'))
      }
      return
    }
    this.hudUnreadableSince ??= t
    if (this.hudReadable && t - this.hudUnreadableSince >= HUD_LOST_AFTER_MS) {
      this.hudReadable = false
      out.entries.push(
        notice(t, 'warning', 'Cannot read the HUD (menu, loading screen, spectating, or the wrong window?). Nothing is inferred until it is readable again.'),
      )
    }
  }

  private zoneLabel(cell: string): string {
    for (const z of this.profile.zones ?? []) {
      const c = z.cells.find((x) => x.id === cell)
      if (c) return c.label
    }
    return cell
  }
}

export function defaultContext(profile: GameProfile): PlayerContext {
  return Object.fromEntries((profile.playerContext ?? []).map((c) => [c.id, c.default]))
}

function sideOf(v: string | number | undefined): 'home' | 'away' {
  return v === 'away' ? 'away' : 'home'
}

/** Pixel signal readings as state observations, so one fused state describes the screen. */
function pixelObservations(signals: ReadonlyMap<string, SignalState>, t: number, profileId: string): Observation[] {
  const out: Observation[] = []
  for (const s of signals.values()) {
    if (!s.known || s.observedAt !== t) continue
    out.push({
      id: `px-${s.id}-${t}`,
      timestampMs: t,
      gameProfileId: profileId,
      sourceRegion: s.id,
      kind: 'state',
      eventType: s.id,
      value: s.value,
      confidence: s.confidence,
      freshnessMs: 0,
      evidence: ['pixel detector'],
      source: 'pixel',
    })
  }
  return out
}

function emptyOutput(): EngineOutput {
  return { entries: [], utterances: [], cancel: [], matches: [] }
}

function notice(at: number, level: 'info' | 'warning', text: string): TimelineEntry {
  return { type: 'notice', at, level, text }
}

function battleCounts(list: readonly BattleRecord[]): { won: number; lost: number; unclear: number } {
  return { won: list.filter((b) => b.outcome === 'won').length, lost: list.filter((b) => b.outcome === 'lost').length, unclear: list.filter((b) => b.outcome === 'unclear').length }
}

function stringValue(v: unknown): string | undefined {
  return typeof v === 'string' ? v : typeof v === 'number' ? String(v) : undefined
}

/** A reading log for packs with a game clock (strategy games): the HUD numbers, the benchmark readings and the watched changes. */
function readingLoggerFor(profile: GameProfile): ReadingLogger | null {
  const keys = new Set((profile.vision?.state ?? []).map((s) => s.key))
  const timeKey = profile.benchmarks?.[0]?.timeKey ?? (keys.has('gameTimeSec') ? 'gameTimeSec' : null)
  if (!timeKey || !keys.has(timeKey)) return null
  const logged = new Set<string>()
  for (const h of profile.hudNumbers ?? []) if (h.kind !== 'clock') [h.key, h.key2].forEach((k) => k && logged.add(k))
  for (const b of profile.benchmarks ?? []) logged.add(b.key)
  return new ReadingLogger([...logged].filter((k) => keys.has(k)), timeKey, (profile.commentaryChanges ?? []).map((c) => c.key))
}
