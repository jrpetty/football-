// Short audio cues, so a player looking at the game (not this window) knows
// the coach heard the talk key: listening, sent, held for later, or a problem.
// Tones, never speech, so they say nothing about the game.

export type Tone = 'mark' | 'listen' | 'send' | 'held' | 'error'

const NOTES: Record<Tone, readonly number[]> = {
  mark: [660, 880],
  listen: [523, 784],
  send: [784, 523],
  held: [440, 440],
  error: [330, 247],
}

let ctx: AudioContext | null = null

export function tone(kind: Tone): void {
  try {
    ctx ??= new AudioContext()
    // A context created without a click (global key) can start suspended.
    void ctx.resume()
    const t = ctx.currentTime + 0.02
    for (const [i, f] of NOTES[kind].entries()) {
      const o = ctx.createOscillator()
      const g = ctx.createGain()
      const at = t + i * 0.1
      o.frequency.value = f
      g.gain.setValueAtTime(0.0001, at)
      g.gain.exponentialRampToValueAtTime(0.12, at + 0.02)
      g.gain.exponentialRampToValueAtTime(0.0001, at + 0.16)
      o.connect(g).connect(ctx.destination)
      o.start(at)
      o.stop(at + 0.18)
    }
  } catch {
    // No audio device: the on-screen state is enough.
  }
}
