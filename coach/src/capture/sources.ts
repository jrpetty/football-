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
// ReplaySource plays a synthetic vision replay (an FC match or an AoE II game):
// the observations a cloud recogniser would have produced, with no frames at all.

import { scriptMatch, renderHud, stateAt, type PlayerTendencies } from '../sim/syntheticArena.ts'
import type { VisionReplay } from '../sim/visionReplay.ts'
import type { Frame, Observation } from '../core/types.ts'

export interface FrameSink {
  /** Whether the worker can take another frame (flow control). */
  ready(): boolean
  bitmap(bitmap: ImageBitmap, timestamp: number): void
  pixels(frame: Frame): void
  /** Counts a frame skipped at the source because analysis was busy. */
  skipped(): void
  /** Observations from a synthetic vision replay. */
  observations(obs: Observation[], now: number): void
  /** The capture cannot currently be trusted (or recovered, with null). */
  problem(reason: string | null): void
}

/** What the player shared, as the platform reports it. */
export type Surface = 'window' | 'monitor' | 'browser' | 'unknown'

export interface FrameSource {
  readonly kind: 'screen' | 'simulation' | 'replay'
  readonly label: string
  readonly surface: Surface
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
  readonly surface: Surface
  private readonly stream: MediaStream
  private readonly video: HTMLVideoElement
  private timer: ReturnType<typeof setInterval> | null = null
  private stopped = false

  private constructor(stream: MediaStream, video: HTMLVideoElement) {
    this.stream = stream
    this.video = video
    const track = stream.getVideoTracks()[0]
    this.label = track?.label || 'Shared screen'
    const surface = (track?.getSettings() as { displaySurface?: string } | undefined)?.displaySurface
    this.surface = surface === 'window' || surface === 'monitor' || surface === 'browser' ? surface : 'unknown'
  }

  /** Ask the platform for a capture stream. Rejects if the player cancels or permission is denied. */
  static async start(sink: FrameSink, onEnded: () => void, opts: CaptureOptions = DEFAULT_CAPTURE): Promise<ScreenCaptureSource> {
    // Hint the picker towards a window: a whole display also shows notifications and other apps.
    const request = {
      video: { frameRate: { ideal: opts.fps, max: Math.max(opts.fps, 10) }, displaySurface: 'window' },
      audio: false,
      selfBrowserSurface: 'exclude',
      surfaceSwitching: 'exclude',
    }
    const stream = await navigator.mediaDevices.getDisplayMedia(request as DisplayMediaStreamOptions)
    const video = document.createElement('video')
    video.muted = true
    video.playsInline = true
    video.srcObject = stream
    await video.play()
    const source = new ScreenCaptureSource(stream, video)
    // The OS or browser can end the share (e.g. the window closed, or "Stop sharing").
    for (const track of stream.getTracks()) {
      track.addEventListener('ended', () => {
        source.stop()
        onEnded()
      })
      // Muted: the OS stopped delivering frames (window minimised, hidden or protected).
      track.addEventListener('mute', () => sink.problem('The shared window stopped sending pictures (minimised, hidden, or protected by the game).'))
      track.addEventListener('unmute', () => sink.problem(null))
    }
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
  readonly surface = 'unknown'
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

export class ReplaySource implements FrameSource {
  readonly kind = 'replay'
  readonly label: string
  readonly surface = 'unknown'
  private readonly timer: ReturnType<typeof setInterval>
  private readonly startedAt = performance.now()
  private readonly speed: number
  private next = 0
  private ended = false

  private readonly frames: ((t: number) => Frame) | null
  private lastFrameAt = -Infinity

  /** `frames`: a picture to go with the replay (the MW4 demo's minimap), sent about five times a second of replay time. */
  constructor(sink: FrameSink, onEnded: () => void, replay: VisionReplay, opts: { speed?: number; label: string; frames?: (t: number) => Frame }) {
    this.speed = opts.speed ?? 1
    this.label = opts.label
    this.frames = opts.frames ?? null
    const batches = [...replay.batches].sort((a, b) => a.arrivesAt - b.arrivesAt)
    this.timer = setInterval(() => {
      const t = this.now()
      const due: Observation[] = []
      while (this.next < batches.length && batches[this.next].arrivesAt <= t) due.push(...batches[this.next++].observations)
      if (due.length) sink.observations(due, t)
      if (this.frames && t - this.lastFrameAt >= 200) {
        if (sink.ready()) {
          this.lastFrameAt = t
          sink.pixels(this.frames(t))
        } else sink.skipped()
      }
      if (this.next >= batches.length && t > replay.endsAt + 2000) {
        this.stop()
        onEnded()
      }
    }, 100)
  }

  now(): number {
    return (performance.now() - this.startedAt) * this.speed
  }

  async still(): Promise<ImageBitmap | null> {
    if (!this.frames) return null
    const f = this.frames(this.now())
    return createImageBitmap(new ImageData(new Uint8ClampedArray(f.data), f.width, f.height))
  }

  stop(): void {
    if (this.ended) return
    this.ended = true
    clearInterval(this.timer)
  }
}
