// The renderer's session controller: owns the capture source, the analysis
// worker and the speech controller for one capture session, and exposes a
// single immutable state object for React (useSyncExternalStore).

import type { SightlineBridge } from '../../shared/bridge.ts'
import type { InsightView } from '../core/engine.ts'
import type { PatternSnapshot } from '../core/patterns.ts'
import type { GameProfile } from '../core/profile/schema.ts'
import type { CoachSettings } from '../core/settings.ts'
import type { CandidateMessage, DecisionRecord, RoundResult, TimelineEntry, Utterance } from '../core/types.ts'
import { ScreenCaptureSource, SimulatedSource, type FrameSink, type FrameSource } from '../capture/sources.ts'
import { createBrowserSpeech, type SpeechController } from '../speech/speech.ts'
import type { FromWorker, ToWorker, WorkerStatus } from '../worker/protocol.ts'

/** Frames allowed in flight to the worker before the source skips sampling. */
const MAX_IN_FLIGHT = 2
const TIMELINE_CAP = 1500

export type Phase = 'idle' | 'starting' | 'capturing'

export interface SpokenState {
  readonly message: CandidateMessage
  readonly decision: DecisionRecord
  readonly speech: 'waiting' | 'speaking' | 'finished' | 'interrupted' | 'expired' | 'unavailable'
}

export interface SessionState {
  readonly phase: Phase
  readonly source: { readonly kind: FrameSource['kind']; readonly label: string } | null
  readonly startedAt: number | null
  /** The source clock reading when capture began; timeline times are relative to it. */
  readonly clockOrigin: number
  readonly status: WorkerStatus | null
  readonly timeline: readonly TimelineEntry[]
  readonly insights: readonly InsightView[]
  readonly lastMessage: SpokenState | null
  readonly framesSkipped: number
  readonly error: string | null
  /** Whether the long-term snapshot was saved at the end of the last session. */
  readonly savedTendencies: boolean | null
}

const INITIAL: SessionState = {
  phase: 'idle',
  source: null,
  startedAt: null,
  clockOrigin: 0,
  status: null,
  timeline: [],
  insights: [],
  lastMessage: null,
  framesSkipped: 0,
  error: null,
  savedTendencies: null,
}

export class CoachSession {
  private state: SessionState = INITIAL
  private readonly listeners = new Set<() => void>()
  private readonly bridge: SightlineBridge
  readonly speech: SpeechController
  private worker: Worker | null = null
  private source: FrameSource | null = null
  private inFlight = 0
  private settings: CoachSettings | null = null

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

  private set(patch: Partial<SessionState>): void {
    this.state = { ...this.state, ...patch }
    for (const fn of this.listeners) fn()
  }

  // --- Commands -----------------------------------------------------------------

  /**
   * Start a session. For the screen source, the platform shows its consent
   * flow; a cancelled or denied request leaves the session idle.
   */
  async start(kind: 'screen' | 'simulation', profile: GameProfile, settings: CoachSettings, simSpeed = 1): Promise<void> {
    if (this.state.phase !== 'idle') return
    this.set({ ...INITIAL, phase: 'starting' })
    this.settings = settings
    this.speech.configure(settings.voice)

    let history: PatternSnapshot | null = null
    if (settings.saveTendencies) history = await this.bridge.loadTendencies(profile.id).catch(() => null)

    const worker = new Worker(new URL('../worker/pipeline.worker.ts', import.meta.url), { type: 'module' })
    worker.onmessage = (e: MessageEvent<FromWorker>) => this.onWorker(e.data)
    worker.onerror = (e) => this.set({ error: `Analysis stopped: ${e.message}` })
    this.worker = worker
    this.inFlight = 0
    this.post({ type: 'start', profile, settings, history, now: 0 })

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
    }
    const onEnded = () => void this.stop()

    try {
      this.source =
        kind === 'screen'
          ? await ScreenCaptureSource.start(sink, onEnded)
          : new SimulatedSource(sink, onEnded, { speed: simSpeed })
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
      source: { kind: source.kind, label: source.label },
      startedAt: Date.now(),
      clockOrigin: source.kind === 'simulation' ? 0 : source.now(),
    })
  }

  /** Stop immediately: capture first, then speech, then let the worker finish up. */
  async stop(): Promise<void> {
    const source = this.source
    const worker = this.worker
    if (!source && !worker) return
    this.source = null
    source?.stop()
    this.speech.stopAll()
    this.bridge.setCaptureState(false, null)
    this.set({ phase: 'idle', source: null })
    if (!worker) return

    const stopped = await new Promise<FromWorker | null>((resolve) => {
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
    if (stopped?.type === 'stopped') {
      this.set({ insights: stopped.insights })
      const saved = this.settings?.saveTendencies ? await this.bridge.saveTendencies(stopped.snapshot).catch(() => false) : false
      this.set({ savedTendencies: saved })
    }
  }

  updateSettings(settings: CoachSettings): void {
    const mutedNow = settings.muted && !this.settings?.muted
    this.settings = settings
    this.speech.configure(settings.voice)
    if (mutedNow) this.speech.stopAll()
    if (this.worker) this.post({ type: 'settings', settings, now: this.source?.now() ?? 0 })
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

  // --- Worker output ------------------------------------------------------------

  private post(msg: ToWorker, transfer: Transferable[] = []): void {
    this.worker?.postMessage(msg, transfer)
  }

  private onWorker(msg: FromWorker): void {
    if (msg.type === 'error') {
      this.set({ error: msg.message })
      return
    }
    if (msg.type !== 'output') return
    if (msg.frameDone) this.inFlight = Math.max(0, this.inFlight - 1)

    const patch: { -readonly [K in keyof SessionState]?: SessionState[K] } = {}
    if (msg.status) patch.status = msg.status
    if (msg.insights) patch.insights = msg.insights
    if (msg.entries.length) {
      const timeline = [...this.state.timeline, ...msg.entries]
      patch.timeline = timeline.length > TIMELINE_CAP ? timeline.slice(-TIMELINE_CAP) : timeline
      for (const e of msg.entries) {
        if (e.type !== 'message') continue
        const spoken = msg.utterances.some((u: Utterance) => u.id === e.message.id)
        patch.lastMessage = {
          message: e.message,
          decision: e.decision,
          speech: spoken ? (this.speech.available ? 'waiting' : 'unavailable') : 'finished',
        }
      }
    }
    if (Object.keys(patch).length) this.set(patch)

    if (msg.cancel.length) this.speech.cancel(msg.cancel)
    for (const u of msg.utterances) {
      if (this.settings?.muted) continue
      this.speech.enqueue(u)
    }
  }

  private updateSpeech(id: string, speech: SpokenState['speech']): void {
    const last = this.state.lastMessage
    if (last && last.message.id === id) this.set({ lastMessage: { ...last, speech } })
  }
}
