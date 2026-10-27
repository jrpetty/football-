// The worker's logic, kept free of worker globals so it can be tested in Node.
// Frames are queued (bounded, drop-oldest), decoded, analysed, and released.
// When cloud vision is on, the sampler picks occasional frames to encode as a
// downscaled JPEG for the main process; the answers come back here, are
// validated against the pack, and only then reach the engine.

import { CoachEngine, type EngineOutput } from '../core/engine.ts'
import { sampleGrid } from '../core/frameHealth.ts'
import { BoundedFrameQueue } from '../core/queue.ts'
import type { GameProfile } from '../core/profile/schema.ts'
import type { Frame, TimelineEntry } from '../core/types.ts'
import { validateVisionResponse } from '../core/vision/contract.ts'
import { VisionSampler } from '../core/vision/sampler.ts'
import type { FromWorker, ToWorker, VisionConfig, VisionStatus } from './protocol.ts'

/** At most this many frames wait for analysis; older ones are dropped. */
export const QUEUE_CAPACITY = 2
/** Send a full report to the UI at least this often (outputs), even without new events. */
const REPORT_EVERY = 10

type Pending = { kind: 'bitmap'; bitmap: ImageBitmap; timestamp: number } | { kind: 'pixels'; frame: Frame }

export interface HostDeps {
  post(msg: FromWorker, transfer?: Transferable[]): void
  /** Decode a bitmap into RGBA pixels. Must not keep the bitmap. */
  decode(bitmap: ImageBitmap, timestamp: number): Frame
  /** Encode a frame (or the pack's crops) as a downscaled JPEG. */
  encodeJpeg(frame: Frame, profile: GameProfile): Promise<ArrayBuffer>
  /** Schedule the queue to drain (setTimeout in a worker; synchronous in tests). */
  schedule(fn: () => void): void
  now(): number
}

export class PipelineHost {
  private engine: CoachEngine | null = null
  private profile: GameProfile | null = null
  private readonly deps: HostDeps
  private readonly queue: BoundedFrameQueue<Pending>
  private draining = false
  private timings: number[] = []
  private sampler: VisionSampler | null = null
  private vision: VisionConfig = { enabled: false, intervalMs: 3000 }
  private visionLastError: string | null = null
  private visionAccepted = 0
  private visionDropped = 0
  private latencies: number[] = []
  private outputs = 0

  constructor(deps: HostDeps) {
    this.deps = deps
    this.queue = new BoundedFrameQueue<Pending>(QUEUE_CAPACITY, (p) => {
      if (p.kind === 'bitmap') p.bitmap.close()
      // A dropped frame still completes from the renderer's point of view.
      this.deps.post(emptyOutput(true))
    })
  }

  handle(msg: ToWorker): void {
    try {
      this.dispatch(msg)
    } catch (err) {
      this.deps.post({ type: 'error', message: err instanceof Error ? err.message : String(err) })
    }
  }

  private dispatch(msg: ToWorker): void {
    switch (msg.type) {
      case 'start': {
        this.queue.clear()
        this.profile = msg.profile
        this.engine = new CoachEngine(msg.profile, msg.settings, {
          policy: msg.policy,
          history: msg.history,
          matchHistory: msg.matchHistory,
          context: msg.context,
        })
        this.configureVision(msg.vision)
        this.emit({ entries: msg.profile.vision || msg.profile.signals.length ? this.engine.startNotices(msg.now) : [], utterances: [], cancel: [], matches: [] }, true)
        return
      }
      case 'bitmap':
        if (!this.engine) return msg.bitmap.close()
        this.queue.push({ kind: 'bitmap', bitmap: msg.bitmap, timestamp: msg.timestamp })
        return this.scheduleDrain()
      case 'pixels':
        if (!this.engine) return
        this.queue.push({ kind: 'pixels', frame: msg.frame })
        return this.scheduleDrain()
      case 'observations':
        if (this.engine) this.emit(this.engine.ingestObservations(msg.observations, msg.now), true)
        return
      case 'vision-result':
        return this.visionResult(msg)
      case 'vision-config':
        this.configureVision(msg.vision)
        if (this.engine) this.emit({ entries: [], utterances: [], cancel: [], matches: [] }, true)
        return
      case 'settings':
        if (this.engine) this.emit({ entries: this.engine.configure(msg.settings, msg.policy, msg.context, msg.now), utterances: [], cancel: [], matches: [] }, true)
        return
      case 'capture-problem':
        if (this.engine) this.emit({ entries: this.engine.setCaptureProblem(msg.reason, msg.now), utterances: [], cancel: [], matches: [] }, true)
        return
      case 'answer':
        if (this.engine) this.emit(this.engine.answer(msg.question, msg.text, msg.author, msg.now), true)
        return
      case 'boundary':
        if (this.engine) this.emit(this.engine.manualBoundary(msg.kind, msg.result, msg.now), true)
        return
      case 'stop': {
        this.queue.clear()
        if (!this.engine) return
        const engine = this.engine
        this.engine = null
        this.sampler = null
        const out = engine.stop(msg.now)
        this.deps.post({ type: 'output', ...out, status: null, report: engine.report(), frameDone: false })
        this.deps.post({ type: 'stopped', snapshot: engine.snapshot(new Date()), insights: engine.insights(), report: engine.report() })
        return
      }
    }
  }

  private configureVision(cfg: VisionConfig): void {
    this.vision = cfg
    const usable = cfg.enabled && !!this.profile?.vision
    if (!usable) {
      this.sampler?.setPaused(true)
      return
    }
    if (!this.sampler) this.sampler = new VisionSampler({ intervalMs: cfg.intervalMs, minChange: 1.5, maxSkipMs: cfg.intervalMs * 4 })
    this.sampler.setPaused(false)
  }

  private visionResult(msg: Extract<ToWorker, { type: 'vision-result' }>): void {
    if (!this.engine || !this.profile) return
    const ok = msg.error === null
    this.sampler?.done(msg.arrivedAt, ok, msg.retryAfterMs)
    const notices: TimelineEntry[] = []
    if (!ok) {
      if (msg.error !== this.visionLastError) notices.push({ type: 'notice', at: msg.arrivedAt, level: 'warning', text: `Cloud vision: ${msg.error}` })
      this.visionLastError = msg.error
      if (msg.fatal) this.sampler?.setPaused(true)
      this.emit({ entries: notices, utterances: [], cancel: [], matches: [] }, false)
      return
    }
    this.visionLastError = null
    this.latencies = [...this.latencies.slice(-19), msg.arrivedAt - msg.frameAt]
    const { observations, stats } = validateVisionResponse(msg.response, this.profile, msg.frameAt, msg.arrivedAt, `v${msg.frameAt}`)
    this.visionAccepted += stats.accepted
    this.visionDropped += stats.dropped.length
    this.emit(this.engine.ingestObservations(observations, msg.arrivedAt), true)
  }

  private scheduleDrain(): void {
    if (this.draining) return
    this.draining = true
    this.deps.schedule(() => {
      try {
        this.drain()
      } catch (err) {
        this.deps.post({ type: 'error', message: err instanceof Error ? err.message : String(err) })
        // Keep the renderer's flow control moving: the frame that failed is done.
        this.deps.post(emptyOutput(true))
      }
    })
  }

  private drain(): void {
    this.draining = false
    let next: Pending | undefined
    while (this.engine && (next = this.queue.shift())) {
      const started = this.deps.now()
      let frame: Frame
      if (next.kind === 'bitmap') {
        try {
          frame = this.deps.decode(next.bitmap, next.timestamp)
        } finally {
          next.bitmap.close()
        }
      } else frame = next.frame
      const out = this.engine.processFrame(frame)
      this.maybeSample(frame)
      this.timings = [...this.timings.slice(-19), this.deps.now() - started]
      this.emit(out, true, true)
    }
  }

  /** Cloud vision: send this frame if the sampler says so. The pixels are released once encoded. */
  private maybeSample(frame: Frame): void {
    if (!this.sampler || !this.profile?.vision || !this.vision.enabled) return
    const grid = sampleGrid(frame)
    if (!this.sampler.shouldSample(frame.timestamp, grid)) return
    this.sampler.sending(frame.timestamp, grid)
    this.deps
      .encodeJpeg(frame, this.profile)
      .then((jpeg) => this.deps.post({ type: 'vision-frame', frameAt: frame.timestamp, jpeg }, [jpeg]))
      .catch((err: unknown) => {
        this.sampler?.done(frame.timestamp, false)
        this.visionLastError = err instanceof Error ? err.message : String(err)
      })
  }

  private emit(out: EngineOutput, withStatus: boolean, frameDone = false): void {
    const engine = this.engine
    this.outputs++
    const changed = out.entries.some((e) => e.type !== 'notice') || out.matches.length > 0
    this.deps.post({
      type: 'output',
      ...out,
      status: withStatus && engine ? { ...engine.status, queue: this.queue.stats, analysisMs: mean(this.timings), vision: this.visionStatus() } : null,
      report: engine && (changed || this.outputs % REPORT_EVERY === 0) ? engine.report() : null,
      frameDone,
    })
  }

  private visionStatus(): VisionStatus {
    return {
      enabled: this.vision.enabled && !!this.profile?.vision,
      sampler: this.sampler?.stats ?? { sent: 0, skippedUnchanged: 0, errors: 0, inFlight: false, backoffUntil: 0 },
      lastError: this.visionLastError,
      accepted: this.visionAccepted,
      dropped: this.visionDropped,
      latencyMs: mean(this.latencies),
    }
  }
}

function mean(xs: readonly number[]): number {
  return xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : 0
}

function emptyOutput(frameDone: boolean): FromWorker {
  return { type: 'output', entries: [], utterances: [], cancel: [], matches: [], status: null, report: null, frameDone }
}
