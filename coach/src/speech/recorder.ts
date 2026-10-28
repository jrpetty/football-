// Push-to-talk recording for "ask the coach". The microphone is opened only
// between two presses of the talk key (or clicks of the talk button), captured as 16 kHz
// mono PCM, and handed to the local recogniser. Nothing is stored or sent
// anywhere else; the stream is closed as soon as recording stops.

export const MAX_RECORD_SECONDS = 15

export class PushToTalkRecorder {
  private readonly stream: MediaStream
  private readonly ctx: AudioContext
  private readonly processor: ScriptProcessorNode
  private readonly chunks: Float32Array[] = []
  private readonly timer: ReturnType<typeof setTimeout>
  private stopped = false
  /** Recent input level 0..1, for a meter. */
  level = 0

  private constructor(stream: MediaStream, onLimit: () => void) {
    this.stream = stream
    // Chromium resamples the microphone to the context rate.
    this.ctx = new AudioContext({ sampleRate: 16_000 })
    const source = this.ctx.createMediaStreamSource(stream)
    // ScriptProcessorNode is deprecated but universally available; an
    // AudioWorklet would need a separate module file for little gain here.
    this.processor = this.ctx.createScriptProcessor(4096, 1, 1)
    this.processor.onaudioprocess = (e) => {
      const data = new Float32Array(e.inputBuffer.getChannelData(0))
      this.chunks.push(data)
      let peak = 0
      for (const v of data) peak = Math.max(peak, Math.abs(v))
      this.level = this.level * 0.6 + peak * 0.4
    }
    source.connect(this.processor)
    this.processor.connect(this.ctx.destination)
    this.timer = setTimeout(onLimit, MAX_RECORD_SECONDS * 1000)
  }

  static async start(onLimit: () => void): Promise<PushToTalkRecorder> {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true },
      video: false,
    })
    const rec = new PushToTalkRecorder(stream, onLimit)
    // Started from a global key, not a click: make sure audio is flowing.
    await rec.ctx.resume().catch(() => undefined)
    return rec
  }

  /** Stop and return the recording as 16 kHz mono samples. */
  async stop(): Promise<Float32Array> {
    this.close()
    const total = this.chunks.reduce((n, c) => n + c.length, 0)
    const out = new Float32Array(total)
    let offset = 0
    for (const c of this.chunks) {
      out.set(c, offset)
      offset += c.length
    }
    return out
  }

  cancel(): void {
    this.close()
    this.chunks.length = 0
  }

  private close(): void {
    if (this.stopped) return
    this.stopped = true
    clearTimeout(this.timer)
    this.processor.disconnect()
    for (const t of this.stream.getTracks()) t.stop()
    void this.ctx.close()
  }
}

/** What to tell the player when the microphone cannot be opened. */
export function micErrorMessage(err: unknown): string {
  const name = err instanceof DOMException ? err.name : ''
  if (name === 'NotAllowedError' || name === 'SecurityError')
    return 'The microphone is blocked. On Windows: Settings, Privacy and security, Microphone, and allow desktop apps to use it.'
  if (name === 'NotFoundError' || name === 'OverconstrainedError') return 'No microphone found. Plug one in or pick a default input device in your sound settings.'
  if (name === 'NotReadableError') return 'The microphone is in use by another program or could not be started.'
  return `Microphone unavailable: ${err instanceof Error ? err.message : String(err)}`
}
