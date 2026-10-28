// Always-listening voice input, for players who chose it in Settings: the
// microphone stays open while it is on, and each stretch of speech (found by
// src/speech/vad.ts) is handed to the local recogniser. Audio is never stored
// or sent anywhere; a stretch is dropped once it has been transcribed. It
// stops listening while the coach itself is talking, so it does not hear its
// own voice through the speakers.

import { VAD_FRAME, VAD_RATE, VoiceActivity } from './vad.ts'

export class AlwaysListener {
  private readonly stream: MediaStream
  private readonly ctx: AudioContext
  private readonly processor: ScriptProcessorNode
  private readonly vad = new VoiceActivity()
  private pausedUntil = 0
  private paused = false
  private stopped = false

  private constructor(stream: MediaStream, onUtterance: (pcm: Float32Array) => void) {
    this.stream = stream
    this.ctx = new AudioContext({ sampleRate: VAD_RATE })
    const source = this.ctx.createMediaStreamSource(stream)
    this.processor = this.ctx.createScriptProcessor(VAD_FRAME, 1, 1)
    this.processor.onaudioprocess = (e) => {
      if (this.stopped) return
      if (this.paused || performance.now() < this.pausedUntil) {
        this.vad.reset()
        this.vad.level = 0
        return
      }
      const pcm = this.vad.push(new Float32Array(e.inputBuffer.getChannelData(0)))
      if (pcm) onUtterance(pcm)
    }
    source.connect(this.processor)
    this.processor.connect(this.ctx.destination)
  }

  static async start(onUtterance: (pcm: Float32Array) => void): Promise<AlwaysListener> {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true },
      video: false,
    })
    const l = new AlwaysListener(stream, onUtterance)
    await l.ctx.resume().catch(() => undefined)
    return l
  }

  /** Input level and the current speech threshold (0..1), for a meter. */
  get level(): { level: number; threshold: number; speaking: boolean } {
    return { level: this.vad.level, threshold: this.vad.threshold, speaking: this.vad.speaking }
  }

  /** Stop hearing (the coach is talking, push-to-talk is recording); resume with a short tail. */
  setPaused(paused: boolean, tailMs = 600): void {
    if (this.paused && !paused) this.pausedUntil = performance.now() + tailMs
    this.paused = paused
  }

  stop(): void {
    if (this.stopped) return
    this.stopped = true
    this.processor.disconnect()
    for (const t of this.stream.getTracks()) t.stop()
    void this.ctx.close()
  }
}
