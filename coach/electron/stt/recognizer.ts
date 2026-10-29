// Local speech recognition with sherpa-onnx (WebAssembly) and the Moonshine
// model. Runs in a worker thread of the main process so decoding never blocks
// the app. Input is 16 kHz mono PCM from the renderer's push-to-talk
// recording; output is text. Nothing leaves the computer.

import { createRequire } from 'node:module'
import { join } from 'node:path'
import { parentPort, Worker } from 'node:worker_threads'
import { ASR_MODELS, vadPath, type AsrModelKey } from '../models.ts'

const require = createRequire(import.meta.url)

/** The slice of the sherpa-onnx API used here (the package has no type declarations). */
interface SherpaOnnx {
  createVad(config: unknown): {
    acceptWaveform(samples: Float32Array): void
    isEmpty(): boolean
    front(): { start: number; samples: Float32Array }
    pop(): void
    flush(): void
    reset(): void
  }
  createOfflineRecognizer(config: unknown): {
    createStream(): { acceptWaveform(sampleRate: number, samples: Float32Array): void; free(): void }
    decode(stream: unknown): void
    getResult(stream: unknown): { text: string }
    free(): void
  }
}

export const SAMPLE_RATE = 16_000
/** Longest utterance accepted (seconds). */
export const MAX_SECONDS = 20

export function createRecognizer(dir: string) {
  const sherpa = require('sherpa-onnx') as SherpaOnnx
  const f = (name: string) => join(dir, name)
  const rec = sherpa.createOfflineRecognizer({
    featConfig: { sampleRate: SAMPLE_RATE, featureDim: 80 },
    modelConfig: {
      moonshine: {
        preprocessor: f('preprocess.onnx'),
        encoder: f('encode.int8.onnx'),
        uncachedDecoder: f('uncached_decode.int8.onnx'),
        cachedDecoder: f('cached_decode.int8.onnx'),
      },
      tokens: f('tokens.txt'),
      numThreads: 1,
      provider: 'cpu',
      debug: 0,
    },
  })
  return {
    transcribe(samples: Float32Array): string {
      const stream = rec.createStream()
      try {
        stream.acceptWaveform(SAMPLE_RATE, samples)
        rec.decode(stream)
        return rec.getResult(stream).text.trim()
      } finally {
        stream.free()
      }
    },
    free: () => rec.free(),
  }
}

/** Speech found by the Silero voice detector: [start, end) sample ranges. */
export function createSpeechFinder(modelPath: string) {
  const sherpa = require('sherpa-onnx') as SherpaOnnx
  const vad = sherpa.createVad({
    sileroVad: { model: modelPath, threshold: 0.5, minSilenceDuration: 0.3, minSpeechDuration: 0.2, maxSpeechDuration: 20, windowSize: 512 },
    sampleRate: SAMPLE_RATE,
    numThreads: 1,
    provider: 'cpu',
    debug: 0,
    bufferSizeInSeconds: 30,
  })
  return (samples: Float32Array): [number, number][] => {
    vad.reset()
    const out: [number, number][] = []
    const take = () => {
      while (!vad.isEmpty()) {
        const seg = vad.front()
        out.push([seg.start, seg.start + seg.samples.length])
        vad.pop()
      }
    }
    for (let i = 0; i + 512 <= samples.length; i += 512) {
      vad.acceptWaveform(samples.subarray(i, i + 512))
      take()
    }
    vad.flush()
    take()
    return out
  }
}

/** Less speech than this in an always-listening stretch: not speech (game sound, a cough). */
export const MIN_SPEECH_SEC = 0.3

/**
 * The part of a recording with speech in it, as the voice detector hears it (a short lead-in and
 * tail kept), or null when there is too little speech to be a question.
 */
export function speechPart(samples: Float32Array, segments: readonly [number, number][]): Float32Array | null {
  const total = segments.reduce((n, [a, b]) => n + (b - a), 0)
  if (total < MIN_SPEECH_SEC * SAMPLE_RATE) return null
  const from = Math.max(0, segments[0][0] - Math.round(0.35 * SAMPLE_RATE))
  const to = Math.min(samples.length, segments[segments.length - 1][1] + Math.round(0.4 * SAMPLE_RATE))
  return samples.subarray(from, to)
}

// --- Worker-thread wrapper -----------------------------------------------------------------

type Request = { id: number; samples: Float32Array; verify?: boolean }
type Reply = { id: number; text?: string; error?: string }

if (parentPort) {
  const port = parentPort
  let recognizer: ReturnType<typeof createRecognizer> | null = null
  let findSpeech: ReturnType<typeof createSpeechFinder> | null = null
  port.on('message', (msg: Request & { dir?: string; vad?: string | null }) => {
    try {
      if (msg.dir) {
        recognizer ??= createRecognizer(msg.dir)
        if (msg.vad) {
          try {
            findSpeech ??= createSpeechFinder(msg.vad)
          } catch {
            findSpeech = null
          }
        }
        return
      }
      if (!recognizer) throw new Error('Speech model not loaded')
      let samples = msg.samples
      // With the voice detector: only the speech is transcribed, and an always-listening stretch
      // with no speech in it (game sound) is not transcribed at all.
      if (findSpeech && samples.length > SAMPLE_RATE * 0.3) {
        const part = speechPart(samples, findSpeech(samples))
        if (!part) {
          if (msg.verify) return port.postMessage({ id: msg.id, text: '' } satisfies Reply)
        } else samples = part
      }
      const text = recognizer.transcribe(samples)
      port.postMessage({ id: msg.id, text } satisfies Reply)
    } catch (err) {
      port.postMessage({ id: msg.id, error: err instanceof Error ? err.message : String(err) } satisfies Reply)
    }
  })
}

/** A recogniser living in a worker thread. */
export class RecognizerThread {
  private worker: Worker | null = null
  private seq = 0
  private readonly pending = new Map<number, { resolve: (t: string) => void; reject: (e: Error) => void }>()
  private readonly dir: string
  private readonly vad: string | null

  constructor(modelRoot: string, model: AsrModelKey = 'tiny', withVad = false) {
    this.dir = join(modelRoot, 'models', ASR_MODELS[model].id)
    this.vad = withVad ? vadPath(modelRoot) : null
  }

  /** `verify`: an always-listening stretch, dropped (empty text) when the voice detector hears no speech in it. */
  transcribe(samples: Float32Array, verify = false): Promise<string> {
    if (samples.length > SAMPLE_RATE * MAX_SECONDS) samples = samples.subarray(0, SAMPLE_RATE * MAX_SECONDS)
    const w = this.ensure()
    const id = ++this.seq
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      w.postMessage({ id, samples, verify })
    })
  }

  close(): void {
    void this.worker?.terminate()
    this.worker = null
  }

  private ensure(): Worker {
    if (this.worker) return this.worker
    const w = new Worker(new URL(import.meta.url))
    w.on('message', (r: Reply) => {
      const p = this.pending.get(r.id)
      if (!p) return
      this.pending.delete(r.id)
      if (r.error !== undefined) p.reject(new Error(r.error))
      else p.resolve(r.text ?? '')
    })
    w.on('error', (e) => {
      for (const p of this.pending.values()) p.reject(e)
      this.pending.clear()
      this.worker = null
    })
    w.postMessage({ id: 0, dir: this.dir, vad: this.vad, samples: new Float32Array() })
    this.worker = w
    return w
  }
}
