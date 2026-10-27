// Frame sources feeding the analysis worker.
//
// ScreenCaptureSource uses the platform's display-capture API
// (getDisplayMedia). Under Electron the main process only grants a request the
// player just authorised in the picker; in a browser the browser's own picker
// asks. Frames are sampled at a low rate, downscaled, and handed to the worker
// as transferable ImageBitmaps — they are never drawn into the page, stored,
// or sent anywhere else.
//
// SimulatedSource renders the Synthetic Arena HUD for development and demos.

import { scriptMatch, renderHud, stateAt, type PlayerTendencies } from '../sim/syntheticArena.ts'
import type { Frame } from '../core/types.ts'

export interface FrameSink {
  /** Whether the worker can take another frame (flow control). */
  ready(): boolean
  bitmap(bitmap: ImageBitmap, timestamp: number): void
  pixels(frame: Frame): void
  /** Counts a frame skipped at the source because analysis was busy. */
  skipped(): void
}

export interface FrameSource {
  readonly kind: 'screen' | 'simulation'
  readonly label: string
  /** The clock frame timestamps are on (used to expire stale speech). */
  now(): number
  /** A one-off still for the in-app alignment check. Not retained by the source. */
  still(): Promise<ImageBitmap | null>
  stop(): void
}

export interface CaptureOptions {
  fps: number
  /** Analysis width; height follows the aspect ratio. */
  analysisWidth: number
}

export const DEFAULT_CAPTURE: CaptureOptions = { fps: 5, analysisWidth: 960 }

export class ScreenCaptureSource implements FrameSource {
  readonly kind = 'screen'
  readonly label: string
  private readonly stream: MediaStream
  private readonly video: HTMLVideoElement
  private timer: ReturnType<typeof setInterval> | null = null
  private stopped = false

  private constructor(stream: MediaStream, video: HTMLVideoElement) {
    this.stream = stream
    this.video = video
    this.label = stream.getVideoTracks()[0]?.label || 'Shared screen'
  }

  /** Ask the platform for a capture stream. Rejects if the player cancels or permission is denied. */
  static async start(sink: FrameSink, onEnded: () => void, opts: CaptureOptions = DEFAULT_CAPTURE): Promise<ScreenCaptureSource> {
    const stream = await navigator.mediaDevices.getDisplayMedia({
      video: { frameRate: { ideal: opts.fps, max: Math.max(opts.fps, 10) } },
      audio: false,
    })
    const video = document.createElement('video')
    video.muted = true
    video.playsInline = true
    video.srcObject = stream
    await video.play()
    const source = new ScreenCaptureSource(stream, video)
    // The OS or browser can end the share (e.g. the window closed, or "Stop sharing").
    for (const track of stream.getTracks()) track.addEventListener('ended', () => { source.stop(); onEnded() })
    source.timer = setInterval(() => void source.sample(sink, opts), 1000 / opts.fps)
    return source
  }

  now(): number {
    return performance.now()
  }

  async still(): Promise<ImageBitmap | null> {
    if (this.stopped || this.video.videoWidth === 0) return null
    return createImageBitmap(this.video)
  }

  stop(): void {
    if (this.stopped) return
    this.stopped = true
    if (this.timer) clearInterval(this.timer)
    for (const track of this.stream.getTracks()) track.stop()
    this.video.srcObject = null
  }

  private async sample(sink: FrameSink, opts: CaptureOptions): Promise<void> {
    const { videoWidth: w, videoHeight: h } = this.video
    if (this.stopped || w === 0 || h === 0) return
    if (!sink.ready()) {
      sink.skipped()
      return
    }
    const width = Math.min(opts.analysisWidth, w)
    const height = Math.round((h * width) / w)
    const timestamp = performance.now()
    const bitmap = await createImageBitmap(this.video, { resizeWidth: width, resizeHeight: height, resizeQuality: 'low' })
    if (this.stopped) bitmap.close()
    else sink.bitmap(bitmap, timestamp)
  }
}

export class SimulatedSource implements FrameSource {
  readonly kind = 'simulation'
  readonly label = 'Synthetic Arena (simulation, not a real game)'
  private readonly timer: ReturnType<typeof setInterval>
  private readonly startedAt = performance.now()
  private readonly speed: number
  private readonly keyframes: ReturnType<typeof scriptMatch>['keyframes']
  private readonly endsAt: number
  private ended = false

  constructor(sink: FrameSink, onEnded: () => void, opts: { fps?: number; speed?: number; rounds?: number; seed?: number; tendencies?: PlayerTendencies } = {}) {
    this.speed = opts.speed ?? 1
    const script = scriptMatch({ rounds: opts.rounds ?? 10, seed: opts.seed ?? Date.now() % 100_000, tendencies: opts.tendencies })
    this.keyframes = script.keyframes
    this.endsAt = script.endsAt
    const fps = opts.fps ?? 5
    this.timer = setInterval(() => {
      const t = this.now()
      if (t > this.endsAt) {
        this.stop()
        onEnded()
        return
      }
      if (!sink.ready()) {
        sink.skipped()
        return
      }
      sink.pixels(renderHud(stateAt(this.keyframes, t), 640, 360, t))
    }, 1000 / fps / this.speed)
  }

  now(): number {
    return (performance.now() - this.startedAt) * this.speed
  }

  async still(): Promise<ImageBitmap | null> {
    const f = renderHud(stateAt(this.keyframes, this.now()), 640, 360, this.now())
    return createImageBitmap(new ImageData(new Uint8ClampedArray(f.data), f.width, f.height))
  }

  stop(): void {
    if (this.ended) return
    this.ended = true
    clearInterval(this.timer)
  }
}
