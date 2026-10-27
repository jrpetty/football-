// The worker's logic, kept free of worker globals so it can be tested in Node.
// Frames are queued (bounded, drop-oldest), decoded, analysed, and released;
// the engine never sees an ImageBitmap and nothing here retains pixels.

import { CoachEngine } from '../core/engine.ts'
import { BoundedFrameQueue } from '../core/queue.ts'
import type { Frame, TimelineEntry, Utterance } from '../core/types.ts'
import type { FromWorker, ToWorker } from './protocol.ts'

/** At most this many frames wait for analysis; older ones are dropped. */
export const QUEUE_CAPACITY = 2

type Pending = { kind: 'bitmap'; bitmap: ImageBitmap; timestamp: number } | { kind: 'pixels'; frame: Frame }

export interface HostDeps {
  post(msg: FromWorker): void
  /** Decode a bitmap into RGBA pixels. Must not keep the bitmap. */
  decode(bitmap: ImageBitmap, timestamp: number): Frame
  /** Schedule the queue to drain (setTimeout in a worker; synchronous in tests). */
  schedule(fn: () => void): void
  now(): number
}

export class PipelineHost {
  private engine: CoachEngine | null = null
  private readonly deps: HostDeps
  private readonly queue: BoundedFrameQueue<Pending>
  private draining = false
  private timings: number[] = []

  constructor(deps: HostDeps) {
    this.deps = deps
    this.queue = new BoundedFrameQueue<Pending>(QUEUE_CAPACITY, (p) => {
      if (p.kind === 'bitmap') p.bitmap.close()
      // A dropped frame still completes from the renderer's point of view.
      this.deps.post({ type: 'output', entries: [], utterances: [], cancel: [], status: null, insights: null, frameDone: true })
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
        this.engine = new CoachEngine(msg.profile, msg.settings, msg.history)
        this.emit(this.engine.startNotices(msg.now), [], [], true, false)
        return
      }
      case 'bitmap':
        if (!this.engine) {
          msg.bitmap.close()
          return
        }
        this.queue.push({ kind: 'bitmap', bitmap: msg.bitmap, timestamp: msg.timestamp })
        this.scheduleDrain()
        return
      case 'pixels':
        if (!this.engine) return
        this.queue.push({ kind: 'pixels', frame: msg.frame })
        this.scheduleDrain()
        return
      case 'settings':
        if (this.engine) this.emit(this.engine.configure(msg.settings, msg.now), [], [], false, false)
        return
      case 'boundary': {
        if (!this.engine) return
        const out = this.engine.manualBoundary(msg.kind, msg.result, msg.now)
        this.emit(out.entries, out.utterances, out.cancel, true, false)
        return
      }
      case 'stop': {
        this.queue.clear()
        if (!this.engine) return
        const engine = this.engine
        this.engine = null
        const out = engine.stop(msg.now)
        this.deps.post({ type: 'output', entries: out.entries, utterances: out.utterances, cancel: out.cancel, status: null, insights: null, frameDone: false })
        this.deps.post({ type: 'stopped', snapshot: engine.snapshot(new Date()), insights: engine.insights() })
        return
      }
    }
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
        this.deps.post({ type: 'output', entries: [], utterances: [], cancel: [], status: null, insights: null, frameDone: true })
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
      this.timings = [...this.timings.slice(-19), this.deps.now() - started]
      const learned = out.entries.some((e) => e.type === 'insight' || e.type === 'episode')
      this.emit(out.entries, out.utterances, out.cancel, true, learned, true)
    }
  }

  private emit(
    entries: TimelineEntry[],
    utterances: Utterance[],
    cancel: string[],
    withStatus: boolean,
    withInsights: boolean,
    frameDone = false,
  ): void {
    const engine = this.engine
    this.deps.post({
      type: 'output',
      entries,
      utterances,
      cancel,
      status:
        withStatus && engine
          ? {
              ...engine.status,
              queue: this.queue.stats,
              analysisMs: this.timings.length ? this.timings.reduce((a, b) => a + b, 0) / this.timings.length : 0,
            }
          : null,
      insights: withInsights && engine ? engine.insights() : null,
      frameDone,
    })
  }
}
