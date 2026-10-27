// Neural text-to-speech with sherpa-onnx (native addon), in a worker thread of
// the main process so synthesis never blocks the app. Text in, PCM out; the
// renderer plays it. Nothing leaves the computer.

import { createRequire } from 'node:module'
import { availableParallelism } from 'node:os'
import { join } from 'node:path'
import { parentPort, Worker } from 'node:worker_threads'
import { MAX_SYNTH_CHARS, type SynthResult, type TtsModelKey } from '../../shared/voices.ts'
import { modelDir, TTS_MODELS } from '../models.ts'

const require = createRequire(import.meta.url)

/** The slice of the sherpa-onnx-node API used here (the package ships no TypeScript types for it). */
interface SherpaNode {
  OfflineTts: new (config: unknown) => {
    readonly sampleRate: number
    generate(req: { text: string; sid: number; speed: number }): { samples: Float32Array; sampleRate: number }
  }
}

function threads(): number {
  // Leave cores for the game.
  return Math.max(1, Math.min(4, Math.floor(availableParallelism() / 3)))
}

export function createTts(root: string, key: TtsModelKey) {
  const sherpa = require('sherpa-onnx-node') as SherpaNode
  const dir = modelDir(root, TTS_MODELS[key])
  const f = (name: string) => join(dir, name)
  const model =
    key === 'jenny'
      ? { vits: { model: f('en_GB-jenny_dioco-medium.onnx'), tokens: f('tokens.txt'), dataDir: f('espeak-ng-data') } }
      : { kokoro: { model: f('model.int8.onnx'), voices: f('voices.bin'), tokens: f('tokens.txt'), dataDir: f('espeak-ng-data') } }
  const tts = new sherpa.OfflineTts({ model: { ...model, numThreads: threads(), debug: false, provider: 'cpu' }, maxNumSentences: 1 })
  return {
    synthesize(text: string, sid: number, speed: number): SynthResult {
      const out = tts.generate({ text: text.slice(0, MAX_SYNTH_CHARS), sid, speed })
      // Copy out of the addon's external buffer so it can be transferred between threads.
      return { samples: new Float32Array(out.samples), sampleRate: out.sampleRate }
    },
  }
}

// --- Worker-thread wrapper -----------------------------------------------------------------

type Request = { id: number; root: string; model: TtsModelKey; text: string; sid: number; speed: number }
type Reply = { id: number; result?: SynthResult; ms?: number; error?: string }

if (parentPort) {
  const port = parentPort
  const engines = new Map<TtsModelKey, ReturnType<typeof createTts>>()
  port.on('message', (msg: Request) => {
    try {
      let engine = engines.get(msg.model)
      if (!engine) engines.set(msg.model, (engine = createTts(msg.root, msg.model)))
      const t = performance.now()
      const result = engine.synthesize(msg.text, msg.sid, msg.speed)
      port.postMessage({ id: msg.id, result, ms: performance.now() - t } satisfies Reply, [result.samples.buffer as ArrayBuffer])
    } catch (err) {
      port.postMessage({ id: msg.id, error: err instanceof Error ? err.message : String(err) } satisfies Reply)
    }
  })
}

/** A synthesiser living in a worker thread; requests are served in order. */
export class TtsThread {
  private worker: Worker | null = null
  private seq = 0
  private readonly pending = new Map<number, { resolve: (r: { result: SynthResult; ms: number }) => void; reject: (e: Error) => void }>()
  private readonly root: string

  constructor(root: string) {
    this.root = root
  }

  synthesize(model: TtsModelKey, text: string, sid: number, speed: number): Promise<{ result: SynthResult; ms: number }> {
    const w = this.ensure()
    const id = ++this.seq
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      w.postMessage({ id, root: this.root, model, text, sid, speed } satisfies Request)
    })
  }

  close(): void {
    void this.worker?.terminate()
    this.worker = null
    for (const p of this.pending.values()) p.reject(new Error('Voice engine closed.'))
    this.pending.clear()
  }

  private ensure(): Worker {
    if (this.worker) return this.worker
    const w = new Worker(new URL(import.meta.url))
    w.on('message', (r: Reply) => {
      const p = this.pending.get(r.id)
      if (!p) return
      this.pending.delete(r.id)
      if (r.error !== undefined || !r.result) p.reject(new Error(r.error ?? 'No audio'))
      else p.resolve({ result: r.result, ms: r.ms ?? 0 })
    })
    w.on('error', (e) => {
      for (const p of this.pending.values()) p.reject(e)
      this.pending.clear()
      this.worker = null
    })
    this.worker = w
    return w
  }
}
