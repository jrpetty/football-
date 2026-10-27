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
import type { CoachSettings } from '../core/settings.ts'
import type { CoachingMessage, DecisionRecord, MessageCategory, RoundResult, TimelineEntry } from '../core/types.ts'
import { ReplaySource, ScreenCaptureSource, SimulatedSource, type FrameSink, type FrameSource, type Surface } from '../capture/sources.ts'
import { aoe2GameReplay, fcMatchReplay } from '../sim/visionReplay.ts'
import { PushToTalkRecorder } from '../speech/recorder.ts'
import { createBrowserSpeech, type SpeechController } from '../speech/speech.ts'
import type { FromWorker, ToWorker, WorkerStatus } from '../worker/protocol.ts'
import { policyFor } from './policy.ts'

/** Frames allowed in flight to the worker before the source skips sampling. */
const MAX_IN_FLIGHT = 2
const TIMELINE_CAP = 1500
const CAPTIONS_CAP = 40
const CONVERSATION_CAP = 60

export type Phase = 'idle' | 'starting' | 'capturing'
export type SourceChoice = 'screen' | 'simulation' | 'replay-fc' | 'replay-aoe'

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

  readonly conversation: readonly QaEntry[]
  readonly talk: 'idle' | 'recording' | 'transcribing'
  readonly talkError: string | null
}

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
  conversation: [],
  talk: 'idle',
  talkError: null,
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
  private source: FrameSource | null = null
  private inFlight = 0
  private settings: CoachSettings | null = null
  private profile: GameProfile | null = null
  private recorder: PushToTalkRecorder | null = null

  constructor(bridge: SightlineBridge) {
    this.bridge = bridge
    this.speech = createBrowserSpeech({
      onStart: (id) => this.updateSpeech(id, 'speaking'),
      onEnd: (id, outcome) => this.updateSpeech(id, outcome === 'error' ? 'unavailable' : outcome),
    })
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
    const changedPack = this.profile?.id !== profile.id
    const changedOptIn = this.settings?.saveTendencies !== settings.saveTendencies
    this.profile = profile
    this.settings = settings
    if (changedPack || changedOptIn) await this.reloadPlayerData()
  }

  async reloadPlayerData(): Promise<void> {
    const profile = this.profile
    if (!profile) return
    const id = profile.id
    const [history, snapshot, notes, research] = await Promise.all([
      this.bridge.loadMatches(id).catch(() => []),
      this.bridge.loadTendencies(id).catch(() => null),
      this.bridge.loadNotes(id).catch(() => null),
      this.bridge.loadResearch(id).catch(() => null),
    ])
    if (this.profile?.id !== id) return
    this.set({ profileId: id, history, snapshot, notes, research, ...(this.state.report?.profileId !== id ? { report: null } : {}) })
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

  async runResearch(focus: string): Promise<string | null> {
    const profile = this.profile
    if (!profile || this.state.researching) return null
    this.set({ researching: true })
    try {
      const r = await this.bridge.research({ profileId: profile.id, gameTitle: profile.game.title, focus })
      if (!r.ok) return r.error
      if (this.profile?.id === profile.id) this.set({ research: r.brief })
      return null
    } catch (err) {
      return String(err)
    } finally {
      this.set({ researching: false })
    }
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
    this.set({ phase: 'starting', error: null })
    if (this.state.profileId !== profile.id) await this.reloadPlayerData()
    this.set({
      ...INITIAL,
      phase: 'starting',
      profileId: profile.id,
      history: this.state.history,
      snapshot: this.state.snapshot,
      notes: this.state.notes,
      research: this.state.research,
      conversation: this.state.conversation,
    })
    this.speech.configure(settings.voice)

    const policy = policyFor(profile, settings)
    const context = this.contextFor(profile, settings)
    const vision = {
      enabled: kind === 'screen' && cloud.available && cloud.settings.enabled && cloud.key.set && !!profile.vision,
      intervalMs: profile.vision?.sampleIntervalMs ?? 3000,
    }
    if (vision.enabled) await this.bridge.resetCloudSession().catch(() => undefined)

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
    }
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
      const r = await this.bridge.ask({ profileId: profile.id, question: q, digest }).catch((err: unknown) => ({ ok: false as const, error: String(err) }))
      if (!r.ok) {
        this.patchQa(id, { status: 'error', note: r.error })
        return
      }
      text = r.text
      author = 'ai'
      costUsd = r.costUsd
      note = `Phrased by ${cloud.settings.answerModel} from this session's recorded data${history.length ? ' and your match history' : ''}.`
    } else {
      text =
        'I can answer questions about where you concede or lose the ball, your mistakes, patterns, statistics and how a match went from what I recorded. ' +
        'For open questions, add an API key and turn on cloud features in Settings.'
      note = null
    }

    // Not capturing: nobody is mid-match in front of the coach, so answer directly.
    if (!this.worker || !this.source) {
      this.patchQa(id, { status: 'answered', answer: text, author, note, costUsd })
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
      try {
        const pcm = await rec.stop()
        if (pcm.length < 16_000 * 0.3) {
          this.set({ talk: 'idle', talkError: 'That was too short to hear. Hold the talk button while you speak.' })
          return
        }
        const text = (await this.bridge.transcribe(pcm)).trim()
        this.set({ talk: 'idle', talkError: text ? null : 'I did not catch that. Try again a little closer to the microphone.' })
        if (text) await this.ask(text, 'voice', cloud)
      } catch (err) {
        this.set({ talk: 'idle', talkError: err instanceof Error ? err.message : String(err) })
      }
      return
    }
    if (!this.settings?.voiceInput) {
      this.set({ talkError: 'Voice input is off. Turn it on in Settings → Voice input.' })
      return
    }
    try {
      this.speech.stopAll()
      this.recorder = await PushToTalkRecorder.start(() => void this.toggleTalk(cloud))
      this.set({ talk: 'recording', talkError: null })
    } catch (err) {
      this.set({ talk: 'idle', talkError: `Microphone unavailable: ${err instanceof Error ? err.message : String(err)}` })
    }
  }

  cancelTalk(): void {
    this.recorder?.cancel()
    this.recorder = null
    if (this.state.talk !== 'idle') this.set({ talk: 'idle' })
  }

  clearConversation(): void {
    this.set({ conversation: [] })
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
        if (e.message.requestedByPlayer) conversation = settleAnswer(conversation, e.message, e.decision)
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
    if (!profile || !settings || !this.worker) return
    const r = await this.bridge
      .analyzeFrame({ profileId: profile.id, jpeg, context: this.contextFor(profile, settings) })
      .catch((err: unknown) => ({ ok: false as const, error: String(err) }))
    if (!this.worker || !this.source) return
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
  return { profileId: profile.id, record: { won: 0, lost: 0, drawn: 0, unread: 0 }, score: null, heatmaps: [], mistakes: [], insights: [], matches: [] }
}
