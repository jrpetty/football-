// Plays neural-voice speech: the text is split into sentences, each is
// synthesised on this computer (main process) and played through Web Audio.
// The next sentence is synthesised while the current one plays, so speech
// starts after the first sentence is ready rather than the whole message.

import { MAX_SYNTH_CHARS, type SynthResult } from '../../shared/voices.ts'

export type Synthesize = (req: { voice: string; text: string; speed: number }) => Promise<SynthResult>

/** The Web Audio surface used (injectable for tests). */
export interface AudioOut {
  play(samples: Float32Array, sampleRate: number, volume: number, ended: () => void): { stop(): void }
}

export function webAudioOut(): AudioOut {
  let ctx: AudioContext | null = null
  return {
    play(samples, sampleRate, volume, ended) {
      ctx ??= new AudioContext()
      if (ctx.state === 'suspended') void ctx.resume()
      const buffer = ctx.createBuffer(1, samples.length, sampleRate)
      buffer.copyToChannel(new Float32Array(samples), 0)
      const src = ctx.createBufferSource()
      src.buffer = buffer
      const gain = ctx.createGain()
      gain.gain.value = volume
      src.connect(gain).connect(ctx.destination)
      let done = false
      src.onended = () => {
        if (!done) {
          done = true
          ended()
        }
      }
      src.start()
      return {
        stop() {
          done = true
          try {
            src.stop()
          } catch {
            // already stopped
          }
        },
      }
    },
  }
}

/** Split into speakable chunks: sentences, and long sentences at commas. */
export function sentences(text: string): string[] {
  const out: string[] = []
  for (const s of text.replace(/\s+/g, ' ').trim().split(/(?<=[.!?…])\s+/)) {
    if (!s) continue
    if (s.length <= MAX_SYNTH_CHARS) {
      out.push(s)
      continue
    }
    let rest = s
    while (rest.length > MAX_SYNTH_CHARS) {
      const cut = Math.max(rest.lastIndexOf(', ', MAX_SYNTH_CHARS), rest.lastIndexOf(' ', MAX_SYNTH_CHARS))
      const at = cut > 40 ? cut + 1 : MAX_SYNTH_CHARS
      out.push(rest.slice(0, at).trim())
      rest = rest.slice(at)
    }
    if (rest.trim()) out.push(rest.trim())
  }
  return out
}

export class NeuralPlayer {
  private readonly synthesize: Synthesize
  private readonly out: AudioOut
  private token = 0
  private playing: { stop(): void } | null = null

  constructor(synthesize: Synthesize, out: AudioOut = webAudioOut()) {
    this.synthesize = synthesize
    this.out = out
  }

  /** Speak; `done` is called exactly once unless cancelled. */
  speak(text: string, voice: string, rate: number, volume: number, done: (outcome: 'finished' | 'error') => void): void {
    const token = ++this.token
    const parts = sentences(text)
    const synth = (i: number) => (i < parts.length ? this.synthesize({ voice, text: parts[i], speed: rate }) : null)
    const run = async () => {
      let next = synth(0)
      for (let i = 0; next; i++) {
        const audio = await next
        if (token !== this.token) return
        next = synth(i + 1) // prepare the next sentence while this one plays
        await new Promise<void>((resolve) => {
          this.playing = this.out.play(audio.samples, audio.sampleRate, volume, resolve)
        })
        this.playing = null
        if (token !== this.token) return
      }
      done('finished')
    }
    run().catch(() => {
      if (token === this.token) done('error')
    })
  }

  cancel(): void {
    this.token++
    this.playing?.stop()
    this.playing = null
  }
}
