// Local speech recognition with sherpa-onnx (WebAssembly) and the Moonshine
// model. Runs in a worker thread of the main process so decoding never blocks
// the app. Input is 16 kHz mono PCM from the renderer's push-to-talk
// recording; output is text. Nothing leaves the computer.

import { createRequire } from 'node:module'
import { join } from 'node:path'
import { parentPort, Worker } from 'node:worker_threads'
import { VOICE_MODEL } from './model.ts'

const require = createRequire(import.meta.url)

/** The slice of the sherpa-onnx API used here (the package has no type declarations). */
interface SherpaOnnx {
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

// --- Worker-thread wrapper -----------------------------------------------------------------

type Request = { id: number; samples: Float32Array }
type Reply = { id: number; text?: string; error?: string }

if (parentPort) {
  const port = parentPort
  let recognizer: ReturnType<typeof createRecognizer> | null = null
  port.on('message', (msg: Request & { dir?: string }) => {
    try {
      if (msg.dir) {
        recognizer ??= createRecognizer(msg.dir)
        return
      }
      if (!recognizer) throw new Error('Speech model not loaded')
      const text = recognizer.transcribe(msg.samples)
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

  constructor(modelRoot: string) {
    this.dir = join(modelRoot, 'models', VOICE_MODEL.id)
  }

  transcribe(samples: Float32Array): Promise<string> {
    if (samples.length > SAMPLE_RATE * MAX_SECONDS) samples = samples.subarray(0, SAMPLE_RATE * MAX_SECONDS)
    const w = this.ensure()
    const id = ++this.seq
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      w.postMessage({ id, samples })
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
    w.postMessage({ id: 0, dir: this.dir, samples: new Float32Array() })
    this.worker = w
    return w
  }
}
