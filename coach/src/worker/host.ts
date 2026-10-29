// The worker's logic, kept free of worker globals so it can be tested in Node.
// Frames are queued (bounded, drop-oldest), decoded, analysed, and released.
// When cloud vision is on, the sampler picks occasional frames to encode as a
// downscaled JPEG for the main process; the answers come back here, are
// validated against the pack, and only then reach the engine.

import { CoachEngine, type EngineOutput } from '../core/engine.ts'
import { sampleGrid } from '../core/frameHealth.ts'
import { analyzeOfficial } from '../core/map/official.ts'
import { BoundedFrameQueue } from '../core/queue.ts'
import type { GameProfile } from '../core/profile/schema.ts'
import type { Frame, TimelineEntry } from '../core/types.ts'
import { validateVisionResponse } from '../core/vision/contract.ts'
import { VisionSampler } from '../core/vision/sampler.ts'
import { cleanScreen, dHash, ScreenMemory, type ScreenInfo } from '../core/vision/screens.ts'
import { HudReader } from '../core/hud/reader.ts'
import type { FromWorker, ToWorker, VisionConfig, VisionStatus } from './protocol.ts'

/** At most this many frames wait for analysis; older ones are dropped. */
export const QUEUE_CAPACITY = 2
/** A screen recognised from memory is confirmed by the model again after this long. */
const SCREEN_RECHECK_MS = 60_000
/** For captured frames (several a second), send a full report at least this often, even without new events. Other outputs always carry one. */
const REPORT_EVERY = 10
/** HUD numbers are read on this computer at most this often. */
const HUD_READ_MS = 1000
/** Learned digit shapes are sent for keeping at most this often (and at the end). */
const HUD_BOOK_EVERY_MS = 30_000

type Pending = { kind: 'bitmap'; bitmap: ImageBitmap; timestamp: number } | { kind: 'pixels'; frame: Frame }

export interface HostDeps {
  post(msg: FromWorker, transfer?: Transferable[]): void
  /** Decode a bitmap into RGBA pixels. Must not keep the bitmap. */
  decode(bitmap: ImageBitmap, timestamp: number): Frame
  /** Encode a frame (or the pack's crops) as a downscaled JPEG; `full` ignores the crops. */
  encodeJpeg(frame: Frame, profile: GameProfile, opts?: { readonly maxWidth?: number; readonly full?: boolean }): Promise<ArrayBuffer>
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
  private screens: ScreenMemory | null = null
  /** Fingerprints of frames sent to the model, by capture time, to learn the screen from the answer. */
  private readonly sentHashes = new Map<number, string>()
  /** When each known screen was last confirmed by the model (it is re-checked now and then). */
  private readonly confirmed = new Map<string, number>()
  private screen: VisionStatus['screen'] = null
  private lastFrame: Frame | null = null
  /** HUD numbers read on this computer (when the player marked them). */
  private hud: HudReader | null = null
  private hudSaves = false
  /** Looking at the screen half as often: every marked number is read here. */
  private saving = false
  private lastHudRead = -Infinity
  private lastBookPost = -Infinity

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
        this.screens = new ScreenMemory(msg.profile.id, msg.screens)
        this.screen = null
        this.lastFrame = null
        this.engine = new CoachEngine(msg.profile, msg.settings, {
          policy: msg.policy,
          history: msg.history,
          matchHistory: msg.matchHistory,
          context: msg.context,
          mosaics: msg.mosaics,
          build: msg.training?.build ?? null,
          practice: msg.training?.practice ?? null,
        })
        this.hud = msg.hud?.fields.length ? new HudReader(msg.hud.fields, msg.hud.book) : null
        this.hudSaves = msg.hud?.saves === true
        this.saving = false
        this.lastHudRead = -Infinity
        this.lastBookPost = -Infinity
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
      case 'game-state':
        if (this.engine) this.emit(this.engine.ingestGameState(msg.payload, msg.now), true)
        return
      case 'vision-result':
        return this.visionResult(msg)
      case 'snapshot':
        return this.snapshot(msg.requestId, msg.maxWidth)
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
      case 'official':
        // A few hundred milliseconds for a large image, once per map; frames wait in the queue meanwhile.
        this.engine?.setOfficial(msg.map, msg.image ? analyzeOfficial(msg.image.data, msg.image.width, msg.image.height) : null)
        return
      case 'stop': {
        this.queue.clear()
        if (!this.engine) return
        const engine = this.engine
        this.engine = null
        this.sampler = null
        this.lastFrame = null
        const out = engine.stop(msg.now)
        this.postMosaic(engine, msg.now, true)
        this.postBook(true)
        this.hud = null
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
    // Every marked HUD number read here: the model is still needed for everything else, but less often.
    const intervalMs = this.saving ? cfg.intervalMs * 2 : cfg.intervalMs
    if (!this.sampler) this.sampler = new VisionSampler({ intervalMs, minChange: 1.5, maxSkipMs: intervalMs * 4, maxInFlight: cfg.maxInFlight ?? 1 })
    else this.sampler.reconfigure({ intervalMs, maxInFlight: cfg.maxInFlight ?? 1 })
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
    this.learnScreen(msg.frameAt, msg.arrivedAt, (msg.response as { screen?: unknown } | null)?.screen)
    const { observations, stats } = validateVisionResponse(msg.response, this.profile, msg.frameAt, msg.arrivedAt, `v${msg.frameAt}`)
    if (this.hud) {
      // The same frame read here and by the model: compare, then learn the digits.
      this.hud.check(msg.frameAt, observations)
      const saving = this.hudSaves && this.hud.trustedShare === 1
      if (saving !== this.saving) {
        this.saving = saving
        this.configureVision(this.vision)
      }
      this.postBook(false)
    }
    this.visionAccepted += stats.accepted
    this.visionDropped += stats.dropped.length
    this.emit(this.engine.ingestObservations(observations, msg.arrivedAt), true)
  }

  /** Remember what the model said this frame's screen was (menus etc.; gameplay is never cached). */
  private learnScreen(frameAt: number, arrivedAt: number, raw: unknown): void {
    const info: ScreenInfo | null = cleanScreen(raw)
    const hash = this.sentHashes.get(frameAt)
    this.sentHashes.delete(frameAt)
    if (!info) return
    this.screen = { ...info, from: 'model', at: arrivedAt }
    if (!hash || !this.screens) return
    const learned = this.screens.learn(hash, info, new Date())
    if (learned) {
      this.confirmed.set(learned.entry.h, frameAt)
      this.deps.post({ type: 'screens', catalog: this.screens.catalog })
    }
  }

  private snapshot(requestId: number, maxWidth: number): void {
    const frame = this.lastFrame
    if (!frame || !this.profile) {
      this.deps.post({ type: 'snapshot', requestId, jpeg: null, frameAt: 0, width: 0, height: 0 })
      return
    }
    const width = Math.min(maxWidth, frame.width)
    this.deps
      .encodeJpeg(frame, this.profile, { maxWidth: width, full: true })
      .then((jpeg) => this.deps.post({ type: 'snapshot', requestId, jpeg, frameAt: frame.timestamp, width, height: Math.round((frame.height * width) / frame.width) }, [jpeg]))
      .catch(() => this.deps.post({ type: 'snapshot', requestId, jpeg: null, frameAt: 0, width: 0, height: 0 }))
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
      this.postMosaic(this.engine, frame.timestamp, out.matches.length > 0)
      this.lastFrame = frame
      this.readHud(frame)
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
    // A menu or loading screen seen before is recognised here, for free — re-checked by the model once a minute.
    const hash = dHash(frame)
    const known = this.screens?.match(hash)
    if (known && frame.timestamp - (this.confirmed.get(known.h) ?? -Infinity) < SCREEN_RECHECK_MS) {
      this.sampler.handledLocally(frame.timestamp, grid)
      this.screens!.seen(known, new Date())
      this.screen = { name: known.name, kind: known.kind, summary: known.summary, from: 'memory', at: frame.timestamp }
      const phase = known.kind === 'menu' || known.kind === 'loading' ? 'menu' : null
      if (phase && this.engine && this.profile) {
        this.emit(
          this.engine.ingestObservations(
            [{ id: `screen-${frame.timestamp}`, timestampMs: frame.timestamp, gameProfileId: this.profile.id, sourceRegion: 'full-frame', kind: 'state', eventType: 'phase', value: phase, confidence: 0.75, freshnessMs: 0, evidence: [`known screen: ${known.name}`], source: 'derived' }],
            frame.timestamp,
          ),
          true,
        )
      }
      return
    }
    this.hud?.remember(frame)
    this.sentHashes.set(frame.timestamp, hash)
    if (this.sentHashes.size > 8) this.sentHashes.delete(this.sentHashes.keys().next().value!)
    this.sampler.sending(frame.timestamp, grid)
    this.deps
      .encodeJpeg(frame, this.profile, { maxWidth: this.vision?.maxWidth })
      .then((jpeg) => this.deps.post({ type: 'vision-frame', frameAt: frame.timestamp, jpeg }, [jpeg]))
      .catch((err: unknown) => {
        this.sampler?.done(frame.timestamp, false)
        this.visionLastError = err instanceof Error ? err.message : String(err)
      })
  }

  /** The HUD numbers this computer has learned to read, about once a second, into the engine. */
  private readHud(frame: Frame): void {
    if (!this.hud || !this.engine || !this.profile || frame.timestamp - this.lastHudRead < HUD_READ_MS) return
    this.lastHudRead = frame.timestamp
    const observations = this.hud.read(frame, this.profile.id)
    if (observations.length) this.emit(this.engine.ingestObservations(observations, frame.timestamp), false)
  }

  /** The learned digit shapes, for keeping between sessions (throttled; always at the end). */
  private postBook(force: boolean): void {
    const now = this.deps.now()
    if (!this.hud || (!force && now - this.lastBookPost < HUD_BOOK_EVERY_MS)) return
    const book = this.hud.takeBook()
    if (!book) return
    this.lastBookPost = now
    this.deps.post({ type: 'hud-book', book })
  }

  /** Sends the stitched map when it grew (throttled), or now at a match's end. */
  private postMosaic(engine: CoachEngine, now: number, force: boolean): void {
    const mosaic = engine.takeMosaic(now, force)
    if (mosaic) this.deps.post({ type: 'mosaic', mosaic })
  }

  private emit(out: EngineOutput, withStatus: boolean, frameDone = false): void {
    const engine = this.engine
    if (engine && out.matches.length) this.postMosaic(engine, this.deps.now(), true)
    this.outputs++
    const changed = out.entries.some((e) => e.type !== 'notice') || out.matches.length > 0
    this.deps.post({
      type: 'output',
      ...out,
      status: withStatus && engine ? { ...engine.status, queue: this.queue.stats, analysisMs: mean(this.timings), vision: this.visionStatus() } : null,
      report: engine && (changed || !frameDone || this.outputs % REPORT_EVERY === 0) ? engine.report() : null,
      frameDone,
    })
  }

  private visionStatus(): VisionStatus {
    return {
      enabled: this.vision.enabled && !!this.profile?.vision,
      sampler: this.sampler?.stats ?? { sent: 0, skippedUnchanged: 0, errors: 0, knownScreens: 0, inFlight: false, backoffUntil: 0 },
      lastError: this.visionLastError,
      accepted: this.visionAccepted,
      dropped: this.visionDropped,
      latencyMs: mean(this.latencies),
      screen: this.screen,
      knownScreens: this.screens?.catalog.screens.length ?? 0,
      hud: this.hud ? { ...this.hud.status(), saving: this.saving } : null,
    }
  }
}

function mean(xs: readonly number[]): number {
  return xs.length ? xs.reduce((a, b) => a + b, 0) / xs.length : 0
}

function emptyOutput(frameDone: boolean): FromWorker {
  return { type: 'output', entries: [], utterances: [], cancel: [], matches: [], status: null, report: null, frameDone }
}
