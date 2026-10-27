// CoachEngine wires the pipeline together for one capture session:
//
//   frame → tracker → event rules → episodes → pattern memory → evidence
//         → planner (here) → governor → utterances + timeline entries
//
// It is synchronous, deterministic and free of DOM/Electron dependencies, so
// the whole pipeline runs identically in a Web Worker, in Node tests, and on
// synthetic frames. It never keeps a reference to a frame after processFrame
// returns.

import { EpisodeEvaluator } from './episodes.ts'
import { evaluateEpisode, gradeAtLeast, GRADE_ORDER } from './evidence.ts'
import { EventDetector } from './events.ts'
import {
  episodeSummary,
  insightLine,
  insightStatement,
  liveCueText,
  matchSummaryText,
  roundReviewText,
  type MatchRecord,
} from './language.ts'
import { PatternMemory, type PatternSnapshot } from './patterns.ts'
import { CoachingGovernor, type EffectiveMode } from './policy.ts'
import type { EpisodeSpec, GameProfile, ProfileMode } from './profile/schema.ts'
import type { CoachSettings } from './settings.ts'
import { VisibleStateTracker } from './tracker.ts'
import type {
  BoundaryKind,
  CandidateMessage,
  Episode,
  Frame,
  GameEvent,
  Insight,
  RoundResult,
  SignalState,
  TimelineEntry,
  Utterance,
} from './types.ts'

/** The core HUD must be unreadable this long before the coach says it cannot see. */
const HUD_LOST_AFTER_MS = 3000

export interface EngineOutput {
  readonly entries: TimelineEntry[]
  readonly utterances: Utterance[]
  /** Utterance ids the speech engine should drop if it has not finished them. */
  readonly cancel: string[]
}

export interface EngineStatus {
  readonly signals: SignalState[]
  readonly hudReadable: boolean
  readonly effectiveMode: EffectiveMode
  readonly framesProcessed: number
  readonly round: { readonly index: number; readonly active: boolean }
  readonly record: MatchRecord
  readonly openEpisodes: number
}

export interface InsightView {
  readonly insight: Insight
  readonly statement: string
  readonly episodeLabel: string
}

export class CoachEngine {
  private readonly profile: GameProfile
  private settings: CoachSettings
  private readonly tracker: VisibleStateTracker
  private readonly detector: EventDetector
  private readonly evaluator: EpisodeEvaluator
  private readonly memory: PatternMemory
  private readonly governor: CoachingGovernor
  private readonly episodeSpecs: ReadonlyMap<string, EpisodeSpec>
  private readonly coreSignals: readonly string[]
  private readonly insightCache = new Map<string, Insight>()

  private signals: ReadonlyMap<string, SignalState> = new Map()
  private frames = 0
  private hudReadable = true
  private hudUnreadableSince: number | null = null
  private round = { index: 0, active: false }
  private record: MatchRecord = { won: 0, lost: 0, drawn: 0, unread: 0 }
  private roundEpisodeIds = new Set<string>()
  private matchHasData = false
  private msgSeq = 0
  private manualSeq = 0
  private aspectChecked = false

  constructor(profile: GameProfile, settings: CoachSettings, history: PatternSnapshot | null = null) {
    this.profile = profile
    this.settings = settings
    this.tracker = new VisibleStateTracker(profile.signals)
    this.detector = new EventDetector(profile.events, profile.signals)
    this.evaluator = new EpisodeEvaluator(profile.episodes)
    this.memory = new PatternMemory(profile, settings.saveTendencies ? history : null)
    this.governor = new CoachingGovernor(settings, this.profileMode(settings))
    this.episodeSpecs = new Map(profile.episodes.map((e) => [e.id, e]))
    this.coreSignals = [...new Set(profile.episodes.flatMap((e) => e.visibility))]
    for (const spec of profile.episodes) this.refreshInsight(spec.id)
  }

  /** Notices to show once when the session starts. */
  startNotices(now: number): TimelineEntry[] {
    const out: TimelineEntry[] = []
    const { downgradeReason } = this.governor.mode
    if (downgradeReason) out.push(notice(now, 'warning', downgradeReason))
    if (this.memory.historyNotice) out.push(notice(now, 'info', this.memory.historyNotice))
    if (this.profile.validation.status === 'reference') {
      out.push(notice(now, 'info', `"${this.profile.name}" is a reference profile: ${this.profile.validation.notes}`))
    }
    return out
  }

  configure(settings: CoachSettings, now: number): TimelineEntry[] {
    const before = this.governor.mode
    this.settings = settings
    this.governor.configure(settings, this.profileMode(settings))
    const after = this.governor.mode
    if (after.downgradeReason && after.downgradeReason !== before.downgradeReason) {
      return [notice(now, 'warning', after.downgradeReason)]
    }
    return []
  }

  processFrame(frame: Frame): EngineOutput {
    const t = frame.timestamp
    const out = emptyOutput()
    this.frames++

    if (!this.aspectChecked) {
      this.aspectChecked = true
      const aspect = frame.width / frame.height
      if (Math.abs(aspect - this.profile.capture.aspectRatio) / this.profile.capture.aspectRatio > 0.03) {
        out.entries.push(
          notice(
            t,
            'warning',
            `The captured image is ${aspect.toFixed(2)}:1 but this profile expects ${this.profile.capture.aspectRatio.toFixed(2)}:1. ` +
              `HUD regions may be misaligned. ${this.profile.capture.notes}`,
          ),
        )
      }
    }

    this.signals = this.tracker.update(frame)
    this.checkHud(t, out)
    this.handleEvents(this.detector.update(this.signals, t), t, out)
    return out
  }

  /** The player marked a round or match end by hand (for HUDs the profile cannot read). */
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
    if (this.matchHasData) this.composeMatchSummary(now, out)
    return out
  }

  get status(): EngineStatus {
    return {
      signals: [...this.signals.values()],
      hudReadable: this.hudReadable,
      effectiveMode: this.governor.mode,
      framesProcessed: this.frames,
      round: { ...this.round },
      record: { ...this.record },
      openEpisodes: this.evaluator.openCount,
    }
  }

  insights(): InsightView[] {
    return [...this.insightCache.values()]
      .sort((a, b) => GRADE_ORDER[b.grade] - GRADE_ORDER[a.grade] || b.probability - a.probability)
      .map((insight) => {
        const spec = this.episodeSpecs.get(insight.episodeId)!
        return { insight, statement: insightStatement(spec, insight), episodeLabel: spec.label }
      })
  }

  snapshot(now: Date): PatternSnapshot {
    return this.memory.snapshot(now)
  }

  // --- Pipeline steps ---------------------------------------------------------

  private checkHud(t: number, out: EngineOutput): void {
    if (this.coreSignals.length === 0) return
    const unreadable = this.coreSignals.every((id) => !this.signals.get(id)?.known)
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
        notice(
          t,
          'warning',
          'Cannot read the HUD (menu, loading screen, spectating, or the wrong window?). Nothing is inferred until it is readable again.',
        ),
      )
    }
  }

  private handleEvents(events: GameEvent[], t: number, out: EngineOutput): void {
    for (const ev of events) out.entries.push({ type: 'event', at: ev.at, event: ev })

    for (const ep of this.evaluator.update(t, events, this.signals)) this.recordEpisode(ep, out)

    for (const ev of events) {
      if (ev.boundary === 'round-start') {
        this.round = { index: this.round.index + 1, active: true }
        this.roundEpisodeIds = new Set()
        out.cancel.push(...this.governor.onRoundStart(t))
      } else if (ev.boundary === 'round-end') {
        this.endRound(ev, t, out)
      } else if (ev.boundary === 'match-end') {
        if (this.round.active) this.endRound({ ...ev, result: 'unknown' }, t, out, false)
        this.composeMatchSummary(t, out)
      } else {
        this.maybeLiveCue(ev, t, out)
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
    out.entries.push({ type: 'episode', at: ep.endedAt, episode: ep, summary: episodeSummary(spec, ep, this.profile) + excluded })
    const changed = this.refreshInsight(ep.episodeId)
    if (changed) out.entries.push({ type: 'insight', at: ep.endedAt, insight: changed, statement: insightStatement(spec, changed) })
  }

  /** Recompute the best insight for an episode type; returns it when its key or grade changed. */
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

  // --- Planning ----------------------------------------------------------------

  private maybeLiveCue(ev: GameEvent, t: number, out: EngineOutput): void {
    for (const cue of this.profile.liveCues) {
      if (cue.trigger !== ev.ruleId) continue
      const insight = this.insightCache.get(cue.episode)
      // Only cue when the player's own visible history supports the advice.
      if (!insight || insight.better.response !== cue.recommend || !gradeAtLeast(insight.grade, 'weak')) continue
      const spec = this.episodeSpecs.get(cue.episode)!
      this.decide(
        {
          id: this.nextId('cue'),
          kind: 'live-cue',
          topic: `cue:${cue.id}`,
          text: liveCueText(this.settings.tone, cue, spec, insight),
          createdAt: t,
          basedOnEventAt: ev.at,
          confidence: ev.confidence,
          grade: insight.grade,
          priority: 2,
        },
        t,
        out,
        cue.maxAgeMs,
      )
    }
  }

  private endRound(ev: GameEvent, t: number, out: EngineOutput, review = true): void {
    const result = ev.result ?? 'unknown'
    if (result === 'win') this.record.won++
    else if (result === 'loss') this.record.lost++
    else if (result === 'draw') this.record.drawn++
    else this.record.unread++
    this.round = { ...this.round, active: false }
    this.matchHasData = true
    if (!review) return

    const rules = this.governor.rules
    const queued = new Set(
      this.governor
        .takeQueuedTopics()
        .map((topic) => this.profile.liveCues.find((c) => `cue:${c.id}` === topic)?.episode)
        .filter((id): id is string => !!id),
    )
    const relevant = [...this.insightCache.values()]
      .filter((i) => this.roundEpisodeIds.has(i.episodeId) || queued.has(i.episodeId))
      .filter((i) => gradeAtLeast(i.grade, rules.minReviewGrade))
      .sort((a, b) => GRADE_ORDER[b.grade] - GRADE_ORDER[a.grade] || b.probability - a.probability)
      .slice(0, rules.reviewLines)
    this.roundEpisodeIds = new Set()
    // A bare "round won" every round is chatter; only speak when there is something to learn.
    if (relevant.length === 0) return

    const lines = relevant.map((i) => insightLine(this.settings.tone, this.episodeSpecs.get(i.episodeId)!, i))
    this.decide(
      {
        id: this.nextId('round'),
        kind: 'round-review',
        topic: relevant.map((i) => i.key).join('+'),
        text: roundReviewText(this.settings.tone, result, lines),
        createdAt: t,
        basedOnEventAt: ev.at,
        confidence: ev.confidence,
        grade: relevant[0].grade,
        priority: 1,
      },
      t,
      out,
    )
  }

  private composeMatchSummary(t: number, out: EngineOutput): void {
    const limit = { minimal: 1, balanced: 2, detailed: 3 }[this.settings.verbosity]
    const top = [...this.insightCache.values()]
      .filter((i) => gradeAtLeast(i.grade, this.governor.rules.minReviewGrade))
      .sort((a, b) => GRADE_ORDER[b.grade] - GRADE_ORDER[a.grade] || b.probability - a.probability)
      .slice(0, limit)
    const lines = top.map((i) => insightLine(this.settings.tone, this.episodeSpecs.get(i.episodeId)!, i))
    this.decide(
      {
        id: this.nextId('summary'),
        kind: 'match-summary',
        topic: 'match-summary',
        text: matchSummaryText(this.settings.tone, this.record, lines),
        createdAt: t,
        basedOnEventAt: null,
        confidence: 1,
        grade: top[0]?.grade ?? 'insufficient',
        priority: 1,
      },
      t,
      out,
    )
    this.record = { won: 0, lost: 0, drawn: 0, unread: 0 }
    this.round = { index: 0, active: false }
    this.matchHasData = false
  }

  private decide(msg: CandidateMessage, t: number, out: EngineOutput, maxAgeMs?: number): void {
    const { record, utterance } = this.governor.decide(msg, t, { maxAgeMs })
    out.entries.push({ type: 'message', at: t, message: msg, decision: record })
    if (utterance) out.utterances.push(utterance)
  }

  private profileMode(settings: CoachSettings): ProfileMode | null {
    return this.profile.modes.find((m) => m.id === settings.profileModeId) ?? null
  }

  private nextId(prefix: string): string {
    return `${prefix}-${++this.msgSeq}`
  }
}

function emptyOutput(): EngineOutput {
  return { entries: [], utterances: [], cancel: [] }
}

function notice(at: number, level: 'info' | 'warning', text: string): TimelineEntry {
  return { type: 'notice', at, level, text }
}
