// The renderer's session controller. It owns, for one capture session, the
// frame source, the analysis worker, the speech controller and the cloud-vision
// relay; and, across sessions, the player's opt-in data for the selected pack
// (match history, stored patterns, notes, research) and the "ask the coach"
// conversation. React reads one immutable state object (useSyncExternalStore).

import type { PlayerNotes, SightlineBridge } from '../../shared/bridge.ts'
import type { CloudStatus, ResearchBrief } from '../../shared/cloud.ts'
import type { MatchRecord } from '../core/analyses/matchRecorder.ts'
import { defaultContext, type SessionReport } from '../core/engine.ts'
import type { PatternSnapshot, PatternVerdict } from '../core/patterns.ts'
import type { GameProfile } from '../core/profile/schema.ts'
import { answerLocally, buildDigest, classify } from '../core/qa/ask.ts'
import { statusLabel } from '../core/policy/registry.ts'
import { visionPrefsFor, type CoachSettings } from '../core/settings.ts'
import type { CoachingMessage, DecisionRecord, MessageCategory, RoundResult, TimelineEntry } from '../core/types.ts'
import { ReplaySource, ScreenCaptureSource, SimulatedSource, type FrameSink, type FrameSource, type Surface } from '../capture/sources.ts'
import { aoe2GameReplay, aoe4GameReplay, demoMapFrom, fcMatchReplay, mw4MatchReplay } from '../sim/visionReplay.ts'
import { mw4DemoPicture } from '../sim/mw4Minimap.ts'
import { localReview, reviewSpeech, type MatchReview } from '../core/review.ts'
import { PushToTalkRecorder, micErrorMessage } from '../speech/recorder.ts'
import { tone } from './tones.ts'
import { NeuralPlayer } from '../speech/neural.ts'
import { createBrowserSpeech, type SpeechController } from '../speech/speech.ts'
import type { FromWorker, ToWorker, VisionConfig, WorkerStatus } from '../worker/protocol.ts'
import type { StoredMosaic } from '../core/map/minimap.ts'
import { policyFor } from './policy.ts'

/** Frames allowed in flight to the worker before the source skips sampling. */
const MAX_IN_FLIGHT = 2
const TIMELINE_CAP = 1500
const CAPTIONS_CAP = 40
const CONVERSATION_CAP = 60
/** Marked moments kept per session (each is a ~100 KB screenshot in memory). */
const MOMENTS_CAP = 24
/** A voice question this soon after marking a moment becomes that moment's note. */
const MOMENT_NOTE_WINDOW_MS = 20_000

export type Phase = 'idle' | 'starting' | 'capturing'
export type SourceChoice = 'screen' | 'simulation' | 'replay-fc' | 'replay-aoe' | 'replay-mw4' | 'replay-aoe4'

export type SpeechState = 'waiting' | 'speaking' | 'finished' | 'interrupted' | 'expired' | 'unavailable' | 'shown'

/** Something the coach said or showed. */
export interface Caption {
  readonly id: string
  readonly at: number
  readonly text: string
  readonly category: MessageCategory
  readonly evidence: string
  readonly author: CoachingMessage['author']
  readonly requestedByPlayer: boolean
  readonly decision: DecisionRecord
  readonly speech: SpeechState
}

export interface QaEntry {
  readonly id: string
  readonly question: string
  readonly via: 'text' | 'voice'
  readonly askedAt: string
  readonly status: 'thinking' | 'answered' | 'held' | 'not-delivered' | 'error'
  readonly answer: string | null
  readonly author: CoachingMessage['author'] | null
  /** Why it is held, not delivered, or failed; or where the answer came from. */
  readonly note: string | null
  readonly costUsd: number
  /** The engine topic, to match a held answer when it is released. */
  readonly topic: string | null
}

/** A moment the player marked during play, to ask about after the match. Kept in memory only. */
export interface Moment {
  readonly id: string
  /** Source-clock time. */
  readonly at: number
  readonly markedAt: string
  /** Object URL of the downscaled screenshot (this session only; never written to disk). */
  readonly imageUrl: string | null
  readonly context: { readonly score: { player: number; opponent: number } | null; readonly screen: string | null; readonly phase: string | null }
  /** What the player said about it (a voice question right after marking). */
  readonly note: string | null
  readonly review: { readonly status: 'idle' | 'reviewing' | 'done' | 'error'; readonly text: string | null; readonly costUsd: number }
}

export interface SessionState {
  readonly phase: Phase
  readonly source: { readonly kind: FrameSource['kind']; readonly label: string; readonly surface: Surface } | null
  readonly startedAt: number | null
  /** The source clock reading when capture began; timeline times are relative to it. */
  readonly clockOrigin: number
  readonly status: WorkerStatus | null
  readonly report: SessionReport | null
  readonly timeline: readonly TimelineEntry[]
  readonly captions: readonly Caption[]
  readonly framesSkipped: number
  readonly error: string | null
  readonly visionCostUsd: number
  /** Matches that ended in this session. */
  readonly matches: readonly MatchRecord[]
  /** Whether the long-term data was saved at the end of the last session. */
  readonly saved: { readonly tendencies: boolean; readonly matches: number } | null

  // Player data for the selected pack (loaded only when the player opted in to keeping it).
  readonly profileId: string | null
  readonly history: readonly MatchRecord[]
  readonly snapshot: PatternSnapshot | null
  readonly notes: PlayerNotes | null
  readonly research: ResearchBrief | null
  readonly researching: boolean
  /** Maps stitched from the minimap, by map name (lower case). A demo's are synthetic and never saved. */
  readonly mosaics: Readonly<Record<string, ShownMosaic>>

  readonly conversation: readonly QaEntry[]
  readonly talk: 'idle' | 'recording' | 'transcribing'
  readonly talkError: string | null
  readonly moments: readonly Moment[]
  /** A short confirmation (e.g. "Moment marked"), cleared after a few seconds. */
  readonly toast: string | null
  /** The coach's review of the session (built on request). */
  readonly review: ReviewState
}

export type ShownMosaic = StoredMosaic & { readonly synthetic?: boolean }

/** The stitched map for a map name, if there is one. */
export function mosaicFor(mosaics: Readonly<Record<string, ShownMosaic>>, map: string | null | undefined): ShownMosaic | null {
  return map ? (mosaics[map.toLowerCase()] ?? null) : null
}

export interface ReviewState {
  readonly status: 'idle' | 'working' | 'ready'
  readonly review: MatchReview | null
  /** Why Claude's review is not shown (the local one is). */
  readonly note: string | null
  readonly costUsd: number
}

const NO_REVIEW: ReviewState = { status: 'idle', review: null, note: null, costUsd: 0 }

const INITIAL: SessionState = {
  phase: 'idle',
  source: null,
  startedAt: null,
  clockOrigin: 0,
  status: null,
  report: null,
  timeline: [],
  captions: [],
  framesSkipped: 0,
  error: null,
  visionCostUsd: 0,
  matches: [],
  saved: null,
  profileId: null,
  history: [],
  snapshot: null,
  notes: null,
  research: null,
  researching: false,
  mosaics: {},
  conversation: [],
  talk: 'idle',
  talkError: null,
  moments: [],
  toast: null,
  review: NO_REVIEW,
}

type Mutable<T> = { -readonly [K in keyof T]?: T[K] }

let seq = 0
const uid = (p: string) => `${p}-${Date.now().toString(36)}-${(++seq).toString(36)}`

export class CoachSession {
  private state: SessionState = INITIAL
  private readonly listeners = new Set<() => void>()
  private readonly bridge: SightlineBridge
  readonly speech: SpeechController
  private worker: Worker | null = null
  /** Cloud vision runs for this session (screen capture, cloud on, key set, a pack that reads the screen). */
  private visionEnabled = false
  private source: FrameSource | null = null
  private inFlight = 0
  private settings: CoachSettings | null = null
  private profile: GameProfile | null = null
  private recorder: PushToTalkRecorder | null = null
  private snapshotSeq = 0
  private readonly snapshots = new Map<number, (s: { jpeg: ArrayBuffer; frameAt: number } | null) => void>()
  /** Screenshots of marked moments, by moment id (memory only). */
  private readonly momentImages = new Map<string, ArrayBuffer>()
  private toastTimer: ReturnType<typeof setTimeout> | null = null
  /** What the current (or last) session watched: only real play's maps are saved. */
  private sourceKind: string | null = null
  private readonly mosaicSavedAt = new Map<string, number>()

  constructor(bridge: SightlineBridge) {
    this.bridge = bridge
    this.speech = createBrowserSpeech(
      {
        onStart: (id) => this.updateSpeech(id, 'speaking'),
        onEnd: (id, outcome) => this.updateSpeech(id, outcome === 'error' ? 'unavailable' : outcome),
      },
      new NeuralPlayer((req) => bridge.synthesize(req)),
    )
  }

  // --- React integration --------------------------------------------------------

  subscribe = (fn: () => void): (() => void) => {
    this.listeners.add(fn)
    return () => this.listeners.delete(fn)
  }

  getState = (): SessionState => this.state

  private set(patch: Mutable<SessionState>): void {
    this.state = { ...this.state, ...patch }
    for (const fn of this.listeners) fn()
  }

  /** Session-relative time on the source clock, for the timeline and Q&A. */
  now(): number {
    return this.source?.now() ?? 0
  }

  // --- Selected pack and player data -----------------------------------------------

  /** Called when the selected pack or settings change. Loads opt-in data when the pack changes. */
  async select(profile: GameProfile, settings: CoachSettings): Promise<void> {
    const changedPack = this.profile !== null && this.profile.id !== profile.id
    const changedOptIn = this.settings?.saveTendencies !== settings.saveTendencies
    const first = this.profile === null
    this.profile = profile
    this.settings = settings
    // The chosen voice applies straight away, not only once capture starts.
    this.speech.configure(settings.voice)
    // Questions are about one game; a new game starts a new conversation.
    if (changedPack && this.state.phase === 'idle') this.set({ conversation: [] })
    if (first || changedPack || changedOptIn) await this.reloadPlayerData()
  }

  async reloadPlayerData(): Promise<void> {
    const profile = this.profile
    if (!profile) return
    const id = profile.id
    const [history, snapshot, notes, research, stitched] = await Promise.all([
      this.bridge.loadMatches(id).catch(() => []),
      this.bridge.loadTendencies(id).catch(() => null),
      this.bridge.loadNotes(id).catch(() => null),
      this.bridge.loadResearch(id).catch(() => null),
      profile.positioning?.minimap ? this.bridge.loadMosaics(id).catch(() => []) : Promise.resolve([]),
    ])
    if (this.profile?.id !== id) return
    const mosaics = Object.fromEntries(stitched.map((m) => [m.map.toLowerCase(), m]))
    this.set({ profileId: id, history, snapshot, notes, research, mosaics, ...(this.state.report?.profileId !== id ? { report: null, review: NO_REVIEW } : {}) })
  }

  async saveNotes(notes: PlayerNotes): Promise<boolean> {
    if (!this.profile) return false
    const ok = await this.bridge.saveNotes(this.profile.id, notes).catch(() => false)
    if (ok) this.set({ notes })
    return ok
  }

  async setVerdict(episodeId: string, verdict: PatternVerdict | null): Promise<void> {
    if (!this.profile) return
    const snapshot = await this.bridge.setPatternVerdict(this.profile.id, episodeId, verdict).catch(() => null)
    if (snapshot) this.set({ snapshot })
  }

  async deletePattern(episodeId: string): Promise<void> {
    if (!this.profile) return
    const snapshot = await this.bridge.deletePattern(this.profile.id, episodeId).catch(() => null)
    this.set({ snapshot })
  }

  async deleteMatch(id: string): Promise<void> {
    if (!this.profile) return
    await this.bridge.deleteMatch(this.profile.id, id).catch(() => undefined)
    this.set({ history: this.state.history.filter((m) => m.id !== id) })
  }

  /** Forgets what was learned from this game's saved matches (the rest of each record stays). Returns how many were changed. */
  async forgetLearned(): Promise<number> {
    if (!this.profile) return 0
    let changed = 0
    const history: MatchRecord[] = []
    for (const m of this.state.history) {
      if (!m.scout) {
        history.push(m)
        continue
      }
      const { scout: _forgotten, ...rest } = m
      if (await this.bridge.saveMatch(rest).catch(() => false)) changed++
      history.push(rest)
    }
    this.set({ history })
    return changed
  }

  async runResearch(focus: string): Promise<string | null> {
    const profile = this.profile
    if (!profile || this.state.researching) return null
    this.set({ researching: true })
    try {
      const r = await this.bridge.research({ profileId: profile.id, gameTitle: profile.game.title, focus })
      if (!r.ok) return r.error
      if (this.profile?.id === profile.id) this.set({ research: r.brief })
      this.flash(`Research done: ${r.costUsd < 0.01 ? 'under $0.01' : `$${r.costUsd.toFixed(2)}`} (estimate).`)
      return null
    } catch (err) {
      return String(err)
    } finally {
      this.set({ researching: false })
    }
  }

  /** Forgets a map stitched from the minimap (not during capture: the session still holds it). */
  async forgetMosaic(map: string): Promise<void> {
    if (!this.profile || this.state.phase !== 'idle') return
    await this.bridge.deleteMosaic(this.profile.id, map).catch(() => undefined)
    const next = { ...this.state.mosaics }
    delete next[map.toLowerCase()]
    this.set({ mosaics: next })
  }

  async deleteResearch(): Promise<void> {
    if (!this.profile) return
    await this.bridge.deleteResearch(this.profile.id).catch(() => undefined)
    this.set({ research: null })
  }

  // --- Capture -----------------------------------------------------------------------

  /**
   * Start a session. For the screen source, the platform shows its consent
   * flow; a cancelled or denied request leaves the session idle.
   */
  async start(kind: SourceChoice, profile: GameProfile, settings: CoachSettings, cloud: CloudStatus, opts: { speed?: number } = {}): Promise<void> {
    if (this.state.phase !== 'idle') return
    this.profile = profile
    this.settings = settings
    const samePack = this.state.profileId === profile.id
    this.set({ phase: 'starting', error: null })
    if (!samePack) await this.reloadPlayerData()
    this.set({
      ...INITIAL,
      phase: 'starting',
      profileId: profile.id,
      history: this.state.history,
      snapshot: this.state.snapshot,
      notes: this.state.notes,
      research: this.state.research,
      mosaics: this.state.mosaics,
      conversation: samePack ? this.state.conversation : [],
    })
    this.speech.configure(settings.voice)

    const policy = policyFor(profile, settings)
    const context = this.contextFor(profile, settings)
    this.visionEnabled = kind === 'screen' && cloud.available && cloud.settings.enabled && cloud.key.set && !!profile.vision
    const vision = this.visionConfig(profile, settings)
    if (vision.enabled) await this.bridge.resetCloudSession().catch(() => undefined)

    const screens = vision.enabled ? await this.bridge.loadScreens(profile.id).catch(() => null) : null
    this.clearMoments()

    const worker = new Worker(new URL('../worker/pipeline.worker.ts', import.meta.url), { type: 'module' })
    worker.onmessage = (e: MessageEvent<FromWorker>) => this.onWorker(e.data)
    worker.onerror = (e) => this.set({ error: `Analysis stopped: ${e.message}` })
    this.worker = worker
    this.inFlight = 0
    this.post({
      type: 'start',
      profile,
      settings,
      policy,
      history: settings.saveTendencies ? this.state.snapshot : null,
      matchHistory: settings.saveTendencies ? this.state.history : [],
      context,
      vision,
      screens,
      // A demo stitches its own synthetic map from scratch; real play continues the maps learned before.
      mosaics: kind === 'screen' ? Object.values(this.state.mosaics).filter((m) => !m.synthetic) : [],
      now: 0,
    })

    const sink: FrameSink = {
      ready: () => this.inFlight < MAX_IN_FLIGHT,
      skipped: () => this.set({ framesSkipped: this.state.framesSkipped + 1 }),
      bitmap: (bitmap, timestamp) => {
        this.inFlight++
        this.post({ type: 'bitmap', bitmap, timestamp }, [bitmap])
      },
      pixels: (frame) => {
        this.inFlight++
        this.post({ type: 'pixels', frame }, [frame.data.buffer as ArrayBuffer])
      },
      observations: (observations, now) => this.post({ type: 'observations', observations, now }),
      problem: (reason) => this.post({ type: 'capture-problem', reason, now: this.now() }),
    }
    const onEnded = () => void this.stop()

    try {
      this.source = await this.openSource(kind, sink, onEnded, opts.speed ?? 1)
    } catch (err) {
      worker.terminate()
      this.worker = null
      const denied = err instanceof DOMException && (err.name === 'NotAllowedError' || err.name === 'AbortError')
      this.set({ phase: 'idle', error: denied ? null : `Could not start capture: ${String(err)}` })
      return
    }
    const source = this.source
    this.sourceKind = source.kind
    this.speech.setClock(() => source.now())
    this.bridge.setCaptureState(true, source.label)
    this.set({
      phase: 'capturing',
      source: { kind: source.kind, label: source.label, surface: source.surface },
      startedAt: Date.now(),
      clockOrigin: source.kind === 'screen' ? source.now() : 0,
    })
  }

  private async openSource(kind: SourceChoice, sink: FrameSink, onEnded: () => void, speed: number): Promise<FrameSource> {
    const seed = Date.now() % 100_000
    switch (kind) {
      case 'screen':
        return ScreenCaptureSource.start(sink, onEnded)
      case 'simulation':
        return new SimulatedSource(sink, onEnded, { speed })
      case 'replay-fc':
        return new ReplaySource(sink, onEnded, fcMatchReplay({ seed, realMinutes: 4 }), { speed, label: 'EA SPORTS FC — synthetic replay (not a real match)' })
      case 'replay-aoe':
        return new ReplaySource(sink, onEnded, aoe2GameReplay({ seed, realMinutes: 4 }), { speed, label: 'Age of Empires II — synthetic replay (not a real game)' })
      case 'replay-mw4':
      {
        // A real map from the pack (a core map with links and a power position), walked by a synthetic player.
        const maps = this.profile?.knowledge.maps ?? []
        const map = maps.filter((m) => m.type === 'core').map(demoMapFrom).find((m) => !!m) ?? null
        const replay = mw4MatchReplay({ seed, realMinutes: 4, map })
        // A synthetic minimap in its usual place, so the map is stitched and deaths are pinned as in real play.
        const mm = this.profile?.positioning?.minimap
        const picture = mm
          ? mw4DemoPicture(replay, {
              seed,
              region: this.settings?.minimap[this.profile!.id] ?? mm.region,
              callouts: maps.find((m) => m.name === map?.name)?.callouts,
            })
          : null
        return new ReplaySource(sink, onEnded, replay, {
          speed,
          label: `Modern Warfare 4${map ? ` on ${map.name}` : ''} — synthetic replay (not a real match)`,
          ...(picture ? { frames: (t: number) => picture.frame(t) } : {}),
        })
      }
      case 'replay-aoe4':
        return new ReplaySource(sink, onEnded, aoe4GameReplay({ seed, realMinutes: 4 }), { speed, label: 'Age of Empires IV — synthetic replay (not a real game)' })
    }
  }

  /** Stop immediately: capture first, then speech and the microphone, then let the worker finish up. */
  async stop(): Promise<void> {
    const source = this.source
    const worker = this.worker
    if (!source && !worker) return
    this.source = null
    source?.stop()
    this.speech.stopAll()
    this.cancelTalk()
    this.bridge.setCaptureState(false, null)
    this.set({ phase: 'idle', source: null })
    if (!worker) return

    const stopped = await new Promise<Extract<FromWorker, { type: 'stopped' }> | null>((resolve) => {
      const timeout = setTimeout(() => resolve(null), 2000)
      worker.addEventListener('message', (e: MessageEvent<FromWorker>) => {
        if (e.data.type === 'stopped') {
          clearTimeout(timeout)
          resolve(e.data)
        }
      })
      worker.postMessage({ type: 'stop', now: source?.now() ?? 0 } satisfies ToWorker)
    })
    worker.terminate()
    if (this.worker === worker) this.worker = null
    if (!stopped) return
    this.set({ report: stopped.report })
    let tendencies = false
    if (this.settings?.saveTendencies) {
      tendencies = await this.bridge.saveTendencies(stopped.snapshot).catch(() => false)
      if (tendencies) this.set({ snapshot: stopped.snapshot })
    }
    this.set({ saved: { tendencies, matches: this.state.saved?.matches ?? 0 } })
  }

  updateSettings(settings: CoachSettings): void {
    const mutedNow = settings.muted && !this.settings?.muted
    this.settings = settings
    this.speech.configure(settings.voice)
    if (mutedNow) this.speech.stopAll()
    const profile = this.profile
    if (this.worker && profile) {
      this.post({ type: 'settings', settings, policy: policyFor(profile, settings), context: this.contextFor(profile, settings), now: this.now() })
      // A new refresh time or detail applies from the next frame, without restarting capture.
      this.post({ type: 'vision-config', vision: this.visionConfig(profile, settings), now: this.now() })
    }
  }

  /** Screen reading for this pack: the player's refresh time, looks at once and detail over the pack's defaults. */
  private visionConfig(profile: GameProfile, settings: CoachSettings): VisionConfig {
    const v = visionPrefsFor(profile, settings)
    return { enabled: this.visionEnabled, intervalMs: v.intervalMs, maxInFlight: v.maxInFlight, maxWidth: v.maxWidth }
  }

  markBoundary(kind: 'round-end' | 'match-end', result: RoundResult): void {
    if (!this.worker || !this.source) return
    this.post({ type: 'boundary', kind, result, now: this.source.now() })
  }

  /** Silence current speech without changing settings. */
  quiet(): void {
    this.speech.stopAll()
  }

  /** A still for the alignment check; the caller must close it. */
  still(): Promise<ImageBitmap | null> {
    return this.source?.still() ?? Promise.resolve(null)
  }

  /** The player's context for a screen reading, plus the opponent's faction once it was read (for the matchup's reference). */
  private visionContext(profile: GameProfile, settings: CoachSettings): Record<string, string | number> {
    const report = this.state.report?.profileId === profile.id ? this.state.report : null
    const faction = report?.intel?.faction?.id
    // Shooters: the map once read, so each look carries that map's callouts.
    const mapKey = profile.positioning?.mapKey
    const map = mapKey ? report?.context[mapKey] : undefined
    return { ...this.contextFor(profile, settings), ...(faction ? { opponentFaction: faction } : {}), ...(mapKey && typeof map === 'string' ? { [mapKey]: map } : {}) }
  }

  private contextFor(profile: GameProfile, settings: CoachSettings): Record<string, string | number> {
    return { ...defaultContext(profile), ...(settings.context[profile.id] ?? {}) }
  }

  // --- Ask the coach ------------------------------------------------------------------

  /**
   * Answer a question: locally from the structured data when possible, else by
   * Claude from a compact digest (if cloud features are on). While capturing,
   * the answer goes through the same policy as any message: during restricted
   * play it is held until the match ends.
   */
  async ask(question: string, via: 'text' | 'voice', cloud: CloudStatus): Promise<void> {
    const q = question.trim().slice(0, 500)
    const profile = this.profile
    const settings = this.settings
    if (!q || !profile || !settings) return
    const id = uid('qa')
    this.pushQa({ id, question: q, via, askedAt: new Date().toISOString(), status: 'thinking', answer: null, author: null, note: null, costUsd: 0, topic: null })

    const report = this.state.report?.profileId === profile.id ? this.state.report : emptyReport(profile)
    const history = settings.saveTendencies ? this.state.history : []
    const local = answerLocally(classify(q, profile), profile, report, history)
    let text: string
    let author: CoachingMessage['author'] = 'template'
    let costUsd = 0
    let note: string | null = 'Answered on this computer from what the coach recorded.'
    if (local) text = local
    else if (cloud.available && cloud.settings.enabled && cloud.key.set) {
      const policy = policyFor(profile, settings)
      const mode = profile.modes.find((m) => m.id === settings.profileModeId)
      const digest = buildDigest({
        profile,
        modeLabel: mode?.label ?? null,
        policyLine: `${statusLabel(policy.status)}. ${policy.reasons[0] ?? ''}`,
        report,
        history,
        notes: this.state.notes,
        research: this.state.research,
      })
      // Asking during play: Claude sees the screen too, so "what should I do here?" can be answered.
      const shot = this.state.source?.kind === 'screen' ? await this.snapshot(1280) : null
      const r = await this.bridge
        .ask({ profileId: profile.id, question: q, digest, ...(shot ? { image: shot.jpeg } : {}) })
        .catch((err: unknown) => ({ ok: false as const, error: String(err) }))
      if (!r.ok) {
        this.patchQa(id, { status: 'error', note: r.error })
        return
      }
      text = r.text
      author = 'ai'
      costUsd = r.costUsd
      note = `Answered by ${cloud.settings.answerModel} from ${shot ? 'your screen and ' : ''}this session's recorded data${history.length ? ' and your match history' : ''}.`
    } else {
      text =
        'Without Claude I can answer from what I recorded: where things happen, how your fights go, your mistakes, patterns, statistics and how a match went. ' +
        'For other questions, add your Claude API key in Settings.'
      note = null
    }

    // Not capturing: nobody is mid-match in front of the coach, so answer directly.
    if (!this.worker || !this.source) {
      this.patchQa(id, { status: 'answered', answer: text, author, note, costUsd })
      // Asked out loud: answer out loud.
      if (via === 'voice' && !settings.muted) this.speech.enqueue({ id: `answer-${id}`, text, category: 'post_event', priority: 'normal', expiresAt: null })
      return
    }
    const now = this.source.now()
    this.patchQa(id, { status: 'thinking', answer: text, author, note, costUsd, topic: `answer:${now}` })
    this.post({ type: 'answer', question: q, text, author, now })
  }

  /** Push-to-talk: first call starts recording, second stops and asks. */
  async toggleTalk(cloud: CloudStatus): Promise<void> {
    if (this.state.talk === 'transcribing') return
    if (this.state.talk === 'recording') {
      const rec = this.recorder
      this.recorder = null
      if (!rec) return
      this.set({ talk: 'transcribing' })
      tone('send')
      try {
        const pcm = await rec.stop()
        if (pcm.length < 16_000 * 0.3) return this.talkFailed('That was too short to hear. Press the talk key, ask your question, then press it again.')
        const text = (await this.bridge.transcribe(pcm)).trim()
        if (!text) return this.talkFailed('I did not catch that. Try again a little closer to the microphone.')
        this.set({ talk: 'idle', talkError: null })
        this.noteOnRecentMoment(text)
        await this.ask(text, 'voice', cloud)
      } catch (err) {
        this.talkFailed(talkErrorMessage(err))
      }
      return
    }
    if (!this.settings?.voiceInput) return this.talkFailed('Voice input is off. Turn it on in Settings, Voice input.')
    try {
      // Stop the coach talking first, so the microphone does not hear it.
      this.speech.stopAll()
      tone('listen')
      this.recorder = await PushToTalkRecorder.start(() => void this.toggleTalk(cloud))
      this.set({ talk: 'recording', talkError: null })
    } catch (err) {
      this.talkFailed(micErrorMessage(err))
    }
  }

  /**
   * A talk problem: shown, and said out loud when not mid-match, since the
   * player pressed a key while looking at the game and would otherwise hear
   * nothing. During capture it is a tone and the on-screen message only.
   */
  private talkFailed(message: string): void {
    this.set({ talk: 'idle', talkError: message })
    tone('error')
    if (!this.worker && !this.settings?.muted) this.speech.enqueue({ id: uid('talk-error'), text: message, category: 'descriptive', priority: 'normal', expiresAt: null })
  }

  cancelTalk(): void {
    this.recorder?.cancel()
    this.recorder = null
    if (this.state.talk !== 'idle') this.set({ talk: 'idle' })
  }

  clearConversation(): void {
    this.set({ conversation: [] })
  }

  // --- Marked moments ------------------------------------------------------------------

  /** Bookmark the current screen to ask about after the match (the "mark" key). */
  async markMoment(): Promise<void> {
    if (!this.worker || !this.source) {
      this.flash('Start capture to mark moments.')
      return
    }
    const at = this.source.now()
    const shot = this.state.source?.kind === 'screen' ? await this.snapshot(1024) : null
    const id = uid('moment')
    if (shot) this.momentImages.set(id, shot.jpeg)
    const st = this.state.status
    const moment: Moment = {
      id,
      at,
      markedAt: new Date().toISOString(),
      imageUrl: shot ? URL.createObjectURL(new Blob([shot.jpeg], { type: 'image/jpeg' })) : null,
      context: { score: st?.score ?? null, screen: st?.vision.screen?.name ?? null, phase: st?.visible.phase ?? null },
      note: null,
      review: { status: 'idle', text: null, costUsd: 0 },
    }
    let moments = [...this.state.moments, moment]
    while (moments.length > MOMENTS_CAP) {
      this.dropMomentImage(moments[0])
      moments = moments.slice(1)
    }
    this.set({
      moments,
      timeline: [...this.state.timeline, { type: 'notice', at, level: 'info', text: 'You marked this moment. Review it after the match.' }],
    })
    this.flash(`Moment marked${this.settings?.voiceInput ? ' — press your talk key to add what you were thinking' : ''}.`)
    tone('mark')
  }

  /**
   * Ask Claude to review a marked moment: the screenshot, the player's note and
   * the session data. During restricted play this waits until the match ends.
   */
  async reviewMoment(id: string, cloud: CloudStatus): Promise<void> {
    const profile = this.profile
    const settings = this.settings
    const m = this.state.moments.find((x) => x.id === id)
    if (!profile || !settings || !m || m.review.status === 'reviewing') return
    const blocked = this.reviewBlocked()
    if (blocked) {
      this.flash(blocked)
      return
    }
    if (!(cloud.available && cloud.settings.enabled && cloud.key.set)) {
      this.patchMoment(id, { review: { status: 'error', text: 'Reviewing a moment needs cloud features (Settings).', costUsd: 0 } })
      return
    }
    this.patchMoment(id, { review: { status: 'reviewing', text: null, costUsd: 0 } })
    const report = this.state.report?.profileId === profile.id ? this.state.report : emptyReport(profile)
    const policy = policyFor(profile, settings)
    const digest = buildDigest({
      profile,
      modeLabel: profile.modes.find((x) => x.id === settings.profileModeId)?.label ?? null,
      policyLine: `${statusLabel(policy.status)}. ${policy.reasons[0] ?? ''}`,
      report,
      history: settings.saveTendencies ? this.state.history : [],
      notes: this.state.notes,
      research: this.state.research,
    })
    const ctx = [
      m.context.score ? `Score at the time: ${m.context.score.player}-${m.context.score.opponent}.` : '',
      m.context.screen ? `Screen: ${m.context.screen}.` : '',
      m.note ? `The player said: "${m.note}".` : 'The player did not add a note; work out the decision from the screen.',
    ]
      .filter(Boolean)
      .join(' ')
    const image = this.momentImages.get(id)
    const r = await this.bridge
      .ask({ profileId: profile.id, question: ctx, digest, review: true, ...(image ? { image } : {}) })
      .catch((err: unknown) => ({ ok: false as const, error: String(err) }))
    if (!r.ok) {
      this.patchMoment(id, { review: { status: 'error', text: r.error, costUsd: 0 } })
      return
    }
    this.patchMoment(id, { review: { status: 'done', text: r.text, costUsd: r.costUsd } })
    if (!settings.muted) this.speech.enqueue({ id: `review-${id}`, text: r.text, category: 'post_event', priority: 'normal', expiresAt: null })
  }

  /**
   * The coach's review of this session: built on this computer from the
   * findings and the playbook straight away; when cloud features are on and
   * the player asks for it, Claude writes a deeper one from the same data.
   */
  async reviewSession(cloud: CloudStatus, opts: { deep: boolean }): Promise<void> {
    const profile = this.profile
    const settings = this.settings
    if (!profile || !settings || this.state.review.status === 'working') return
    const blocked = this.reviewBlocked()
    if (blocked) {
      this.flash(blocked)
      return
    }
    const report = this.state.report?.profileId === profile.id ? this.state.report : emptyReport(profile)
    const local = localReview(profile, report)
    const canDeep = cloud.available && cloud.settings.enabled && cloud.key.set
    if (!opts.deep || !canDeep) {
      this.set({ review: { status: 'ready', review: local, note: opts.deep ? 'Claude reviews need cloud features (Settings); this one was built on this computer.' : null, costUsd: 0 } })
      return
    }
    this.set({ review: { status: 'working', review: local, note: null, costUsd: 0 } })
    const policy = policyFor(profile, settings)
    const digest = buildDigest({
      profile,
      modeLabel: profile.modes.find((x) => x.id === settings.profileModeId)?.label ?? null,
      policyLine: `${statusLabel(policy.status)}. ${policy.reasons[0] ?? ''}`,
      report,
      history: settings.saveTendencies ? this.state.history : [],
      notes: this.state.notes,
      research: this.state.research,
    })
    const r = await this.bridge.reviewMatch({ profileId: profile.id, digest }).catch((err: unknown) => ({ ok: false as const, error: String(err) }))
    if (this.profile?.id !== profile.id) return
    this.set({
      review: r.ok
        ? { status: 'ready', review: r.review, note: null, costUsd: r.costUsd }
        : { status: 'ready', review: local, note: `Claude's review failed (${r.error}); this one was built on this computer.`, costUsd: 0 },
    })
  }

  /** Read the review aloud (replacing anything the coach is saying). */
  readReview(): void {
    const r = this.state.review.review
    if (!r || this.settings?.muted) return
    this.speech.stopAll()
    this.speech.enqueue({ id: uid('review'), text: reviewSpeech(r), category: 'post_event', priority: 'normal', expiresAt: null })
  }

  /** Why a moment cannot be reviewed right now (restricted live play), or null. */
  reviewBlocked(): string | null {
    const st = this.state.status
    if (!this.worker || !st) return null
    if (st.window === 'during_play' && !st.policy.scope.includes('live')) return 'Moment reviews open after the match in this mode, so they never coach you mid-game where that is not allowed.'
    if (st.window === 'round_break' && !st.policy.scope.includes('round_break') && !st.policy.scope.includes('live')) return 'Moment reviews open after the match in this mode.'
    return null
  }

  deleteMoment(id: string): void {
    const m = this.state.moments.find((x) => x.id === id)
    if (m) this.dropMomentImage(m)
    this.set({ moments: this.state.moments.filter((x) => x.id !== id) })
  }

  clearMoments(): void {
    for (const m of this.state.moments) this.dropMomentImage(m)
    if (this.state.moments.length) this.set({ moments: [] })
  }

  private dropMomentImage(m: Moment): void {
    if (m.imageUrl) URL.revokeObjectURL(m.imageUrl)
    this.momentImages.delete(m.id)
  }

  private noteOnRecentMoment(text: string): void {
    const last = this.state.moments.at(-1)
    if (!last || last.note || !this.source || this.source.now() - last.at > MOMENT_NOTE_WINDOW_MS) return
    this.patchMoment(last.id, { note: text.slice(0, 300) })
  }

  private patchMoment(id: string, patch: Partial<Moment>): void {
    this.set({ moments: this.state.moments.map((m) => (m.id === id ? { ...m, ...patch } : m)) })
  }

  /** The latest captured frame as a JPEG (whole screen, downscaled), or null (no frames, e.g. a replay). */
  private snapshot(maxWidth: number): Promise<{ jpeg: ArrayBuffer; frameAt: number } | null> {
    if (!this.worker) return Promise.resolve(null)
    const requestId = ++this.snapshotSeq
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.snapshots.delete(requestId)
        resolve(null)
      }, 3000)
      this.snapshots.set(requestId, (s) => {
        clearTimeout(timer)
        resolve(s)
      })
      this.post({ type: 'snapshot', requestId, maxWidth })
    })
  }

  private flash(text: string): void {
    if (this.toastTimer) clearTimeout(this.toastTimer)
    this.set({ toast: text })
    this.toastTimer = setTimeout(() => this.set({ toast: null }), 4000)
  }

  private pushQa(e: QaEntry): void {
    this.set({ conversation: [...this.state.conversation, e].slice(-CONVERSATION_CAP) })
  }

  private patchQa(id: string, patch: Partial<QaEntry>): void {
    this.set({ conversation: this.state.conversation.map((e) => (e.id === id ? { ...e, ...patch } : e)) })
  }

  // --- Worker output ------------------------------------------------------------

  private post(msg: ToWorker, transfer: Transferable[] = []): void {
    this.worker?.postMessage(msg, transfer)
  }

  private onWorker(msg: FromWorker): void {
    switch (msg.type) {
      case 'error':
        this.set({ error: msg.message })
        return
      case 'vision-frame':
        void this.relayVision(msg.frameAt, msg.jpeg)
        return
      case 'screens':
        void this.bridge.saveScreens(msg.catalog).catch(() => false)
        return
      case 'mosaic': {
        // The source is already gone when the last map of a session arrives.
        const real = this.sourceKind === 'screen'
        const shown: ShownMosaic = real ? msg.mosaic : { ...msg.mosaic, synthetic: true }
        const key = msg.mosaic.map.toLowerCase()
        this.set({ mosaics: { ...this.state.mosaics, [key]: shown } })
        // On disk at most every 30 s while playing, and always the last one of a session.
        const now = Date.now()
        if (real && this.profile && (!this.source || now - (this.mosaicSavedAt.get(key) ?? 0) >= 30_000)) {
          this.mosaicSavedAt.set(key, now)
          void this.bridge.saveMosaic(this.profile.id, msg.mosaic).catch(() => false)
        }
        return
      }
      case 'snapshot': {
        const resolve = this.snapshots.get(msg.requestId)
        this.snapshots.delete(msg.requestId)
        resolve?.(msg.jpeg ? { jpeg: msg.jpeg, frameAt: msg.frameAt } : null)
        return
      }
      case 'stopped':
        return
      case 'output':
        break
    }
    if (msg.frameDone) this.inFlight = Math.max(0, this.inFlight - 1)

    const patch: Mutable<SessionState> = {}
    if (msg.status) patch.status = msg.status
    if (msg.report) patch.report = msg.report
    if (msg.entries.length) {
      const timeline = [...this.state.timeline, ...msg.entries]
      patch.timeline = timeline.length > TIMELINE_CAP ? timeline.slice(-TIMELINE_CAP) : timeline
      const captions = [...this.state.captions]
      let conversation = this.state.conversation
      for (const e of msg.entries) {
        if (e.type !== 'message') continue
        const d = e.decision.decision
        const spoken = msg.utterances.some((u) => u.id === e.message.id)
        if (d === 'spoken' || d === 'text-only') {
          captions.push({
            id: e.message.id,
            at: e.at,
            text: e.message.text,
            category: e.message.category,
            evidence: e.message.evidenceSummary,
            author: e.message.author,
            requestedByPlayer: !!e.message.requestedByPlayer,
            decision: e.decision,
            speech: spoken && !this.settings?.muted ? (this.speech.available ? 'waiting' : 'unavailable') : 'shown',
          })
        }
        if (e.message.requestedByPlayer) {
          conversation = settleAnswer(conversation, e.message, e.decision)
          // Held for after the match: a tone, so the player knows it was heard.
          if (e.decision.decision === 'queued') tone('held')
        }
      }
      patch.captions = captions.slice(-CAPTIONS_CAP)
      if (conversation !== this.state.conversation) patch.conversation = conversation
    }
    if (msg.matches.length) {
      patch.matches = [...this.state.matches, ...msg.matches]
      void this.saveMatches(msg.matches)
    }
    if (Object.keys(patch).length) this.set(patch)

    if (msg.cancel.length) this.speech.cancel(msg.cancel)
    for (const u of msg.utterances) {
      if (this.settings?.muted) continue
      this.speech.enqueue(u)
    }
  }

  private async saveMatches(records: readonly MatchRecord[]): Promise<void> {
    if (!this.settings?.saveTendencies) return
    let saved = 0
    for (const r of records) if (await this.bridge.saveMatch(r).catch(() => false)) saved++
    if (!saved) return
    this.set({
      history: [...this.state.history, ...records],
      saved: { tendencies: this.state.saved?.tendencies ?? false, matches: (this.state.saved?.matches ?? 0) + saved },
    })
  }

  /** Cloud vision: one sampled frame → the main process → back to the worker, validated there. */
  private async relayVision(frameAt: number, jpeg: ArrayBuffer): Promise<void> {
    const profile = this.profile
    const settings = this.settings
    const worker = this.worker
    if (!profile || !settings || !worker) return
    const r = await this.bridge
      .analyzeFrame({ profileId: profile.id, jpeg, context: this.visionContext(profile, settings) })
      .catch((err: unknown) => ({ ok: false as const, error: String(err) }))
    // The session may have stopped (or a new one started) while the request was out.
    if (this.worker !== worker || !this.source) return
    const arrivedAt = this.source.now()
    if (r.ok) {
      this.set({ visionCostUsd: this.state.visionCostUsd + r.costUsd })
      this.post({ type: 'vision-result', frameAt, arrivedAt, response: r.response, error: null })
    } else {
      this.post({
        type: 'vision-result',
        frameAt,
        arrivedAt,
        response: null,
        error: r.error,
        ...('retryAfterMs' in r && r.retryAfterMs !== undefined ? { retryAfterMs: r.retryAfterMs } : {}),
        ...('fatal' in r && r.fatal ? { fatal: true } : {}),
      })
    }
  }

  private updateSpeech(id: string, speech: SpeechState): void {
    if (!this.state.captions.some((c) => c.id === id)) return
    this.set({ captions: this.state.captions.map((c) => (c.id === id ? { ...c, speech } : c)) })
  }
}

/** Match a delivered (or held) answer back to its question by the engine topic. */
function settleAnswer(conversation: readonly QaEntry[], message: CoachingMessage, decision: DecisionRecord): readonly QaEntry[] {
  const i = conversation.findIndex((q) => q.topic === message.topic && (q.status === 'thinking' || q.status === 'held'))
  if (i < 0) return conversation
  const q = conversation[i]
  const next: QaEntry =
    decision.decision === 'queued'
      ? { ...q, status: 'held', note: `Held until the match ends: ${decision.reasons[0] ?? 'live coaching is not allowed here.'}` }
      : decision.decision === 'suppressed'
        ? { ...q, status: 'not-delivered', note: decision.reasons[0] ?? 'Not delivered.' }
        : { ...q, status: 'answered' }
  return conversation.map((e, k) => (k === i ? next : e))
}

export function emptyReport(profile: GameProfile): SessionReport {
  return { profileId: profile.id, record: { won: 0, lost: 0, drawn: 0, unread: 0 }, score: null, heatmaps: [], factors: [], benchmarks: [], battles: [], context: {}, intel: null, learned: null, map: null, mistakes: [], insights: [], matches: [] }
}

/** A readable message for a failed transcription (IPC errors carry a long prefix). */
function talkErrorMessage(err: unknown): string {
  const raw = err instanceof Error ? err.message : String(err)
  const msg = raw.replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '')
  if (/not installed/i.test(msg)) return 'Speech recognition is not downloaded yet. Get it in Settings, Voice input.'
  if (/voice input is off/i.test(msg)) return 'Voice input is off. Turn it on in Settings, Voice input.'
  return `I could not understand the recording: ${msg}`
}
