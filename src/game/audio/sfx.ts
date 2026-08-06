// All the game's sound, synthesised at runtime with the Web Audio API.
//
// Nothing is loaded from disk: every noise here is built from oscillators and
// filtered noise, which keeps the whole game a single portable file and lets the
// sounds respond continuously to the simulation — the thump of a strike really
// does get deeper and louder with the power behind it, rather than triggering
// one of a handful of canned samples.
//
// Browsers refuse to start audio without a gesture, so the context stays
// suspended until the first click and everything is a no-op until then.

import { store } from '../core/store'

type Ctx = AudioContext & { _noise?: AudioBuffer }

export class Sfx {
  private ctx: Ctx | null = null
  private master: GainNode | null = null
  private crowdGain: GainNode | null = null
  private noiseBuf: AudioBuffer | null = null
  private started = false

  // Whether you had the sound off is remembered: turning it back on every
  // single time you load the page is exactly the kind of thing that makes
  // people stop turning it on at all.
  muted = store.get('muted')
  volume = 0.75

  // Called on the first user gesture. Safe to call repeatedly.
  unlock() {
    if (this.started) {
      void this.ctx?.resume()
      return
    }
    const AC: typeof AudioContext =
      window.AudioContext ?? (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext
    if (!AC) return
    this.started = true
    const ctx = new AC() as Ctx
    this.ctx = ctx
    const master = ctx.createGain()
    master.gain.value = this.muted ? 0 : this.volume
    master.connect(ctx.destination)
    this.master = master

    // One second of white noise, reused as the source for every percussive and
    // airy sound instead of allocating a buffer per hit.
    const buf = ctx.createBuffer(1, ctx.sampleRate, ctx.sampleRate)
    const d = buf.getChannelData(0)
    for (let i = 0; i < d.length; i++) d[i] = Math.random() * 2 - 1
    this.noiseBuf = buf

    this.startCrowd()
    void ctx.resume()
  }

  setMuted(m: boolean) {
    this.muted = m
    store.set('muted', m)
    if (this.master && this.ctx) {
      this.master.gain.setTargetAtTime(m ? 0 : this.volume, this.ctx.currentTime, 0.05)
    }
  }

  toggleMute(): boolean {
    this.setMuted(!this.muted)
    return this.muted
  }

  private get t(): number {
    return this.ctx ? this.ctx.currentTime : 0
  }

  // ---- building blocks ----

  private noise(dur: number, gain: number, filter: 'low' | 'band' | 'high', freq: number, q = 1): void {
    const ctx = this.ctx
    if (!ctx || !this.noiseBuf || !this.master) return
    const src = ctx.createBufferSource()
    src.buffer = this.noiseBuf
    src.loop = true
    const bq = ctx.createBiquadFilter()
    bq.type = filter === 'low' ? 'lowpass' : filter === 'high' ? 'highpass' : 'bandpass'
    bq.frequency.value = freq
    bq.Q.value = q
    const g = ctx.createGain()
    const t = this.t
    g.gain.setValueAtTime(0, t)
    g.gain.linearRampToValueAtTime(gain, t + 0.004)
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur)
    src.connect(bq).connect(g).connect(this.master)
    src.start(t)
    src.stop(t + dur + 0.05)
  }

  private tone(
    freq: number,
    dur: number,
    gain: number,
    type: OscillatorType = 'sine',
    bendTo?: number,
  ): void {
    const ctx = this.ctx
    if (!ctx || !this.master) return
    const osc = ctx.createOscillator()
    osc.type = type
    const t = this.t
    osc.frequency.setValueAtTime(freq, t)
    if (bendTo !== undefined) osc.frequency.exponentialRampToValueAtTime(Math.max(20, bendTo), t + dur)
    const g = ctx.createGain()
    g.gain.setValueAtTime(0, t)
    g.gain.linearRampToValueAtTime(gain, t + 0.005)
    g.gain.exponentialRampToValueAtTime(0.0001, t + dur)
    osc.connect(g).connect(this.master)
    osc.start(t)
    osc.stop(t + dur + 0.05)
  }

  // ---- the sounds ----

  // Boot off the laces. Power drives both the depth of the thump and how much
  // crack sits on top of it, so a tap and a rocket are audibly different kicks.
  strike(power: number) {
    const p = Math.max(0, Math.min(1, power))
    this.tone(190 - p * 70, 0.13 + p * 0.1, 0.25 + p * 0.35, 'sine', 55 - p * 15)
    this.noise(0.05 + p * 0.05, 0.12 + p * 0.3, 'band', 1400 + p * 1600, 0.8)
  }

  // A close-control touch: much softer, higher, shorter.
  touch(power: number) {
    const p = Math.max(0, Math.min(1, power))
    this.tone(300 - p * 60, 0.06, 0.1 + p * 0.1, 'sine', 120)
    this.noise(0.03, 0.06 + p * 0.08, 'band', 2200, 1.2)
  }

  // Ball meeting turf. Quiet, damp, and scaled by how hard it landed.
  bounce(impact: number) {
    const i = Math.max(0, Math.min(1, impact))
    if (i < 0.05) return
    this.tone(120, 0.07, 0.05 + i * 0.16, 'sine', 60)
    this.noise(0.04, 0.03 + i * 0.1, 'low', 700)
  }

  // The hollow knock of the ball coming back off the boards.
  wall(impact: number) {
    const i = Math.max(0, Math.min(1, impact))
    this.tone(150, 0.1, 0.08 + i * 0.28, 'triangle', 70)
    this.noise(0.06, 0.05 + i * 0.15, 'band', 900, 1.5)
  }

  // Metallic ring off the frame.
  post() {
    this.tone(880, 0.5, 0.3, 'triangle', 520)
    this.tone(1320, 0.35, 0.14, 'sine', 900)
  }

  // Net ripple.
  net() {
    this.noise(0.35, 0.16, 'high', 2600, 0.6)
  }

  goal() {
    this.net()
    this.crowdSurge(1)
    this.tone(523, 0.16, 0.16, 'triangle')
    window.setTimeout(() => this.tone(784, 0.3, 0.16, 'triangle'), 130)
  }

  save() {
    this.noise(0.12, 0.2, 'band', 700, 0.9)
    this.crowdSurge(0.45)
  }

  tackle() {
    this.noise(0.16, 0.24, 'low', 480)
  }

  // A ball off the forehead. Not a boot: bone rather than leather, so it is a
  // duller and shorter knock with none of the crack a strike has, and no low
  // end to speak of because there is no swing behind it.
  header(power: number) {
    const p = Math.max(0, Math.min(1, power))
    this.tone(240 - p * 50, 0.07, 0.14 + p * 0.16, 'sine', 110)
    this.noise(0.045, 0.08 + p * 0.14, 'low', 1100)
  }

  // A ball struck before it lands. Everything a grounded strike has, but the
  // contact is cleaner and shorter — nothing is scuffing along the turf — so
  // it's brighter on top and quicker to decay.
  volley(power: number) {
    const p = Math.max(0, Math.min(1, power))
    this.tone(210 - p * 70, 0.1 + p * 0.07, 0.24 + p * 0.34, 'sine', 60 - p * 12)
    this.noise(0.04 + p * 0.03, 0.16 + p * 0.32, 'band', 2000 + p * 1800, 0.7)
  }

  // Taking the pace off one out of the air. The sound of a cushion is mostly
  // the sound of pace *not* happening: a soft thud whose weight comes from how
  // fast the ball was travelling before you killed it.
  cushion(impact: number) {
    const i = Math.max(0, Math.min(1, impact))
    this.tone(150, 0.09, 0.04 + i * 0.1, 'sine', 70)
    this.noise(0.07, 0.04 + i * 0.09, 'low', 520)
  }

  // Boots leaving the turf: a scuff of grass and a short breath.
  jump() {
    this.noise(0.09, 0.07, 'band', 1700, 0.8)
    this.tone(190, 0.07, 0.03, 'sine', 130)
  }

  // Coming back down, scaled by how far you fell — a hop off the floor barely
  // registers, coming down off a full leap thumps.
  land(impact: number) {
    const i = Math.max(0, Math.min(1, impact))
    if (i < 0.06) return
    this.tone(95, 0.1, 0.05 + i * 0.18, 'sine', 48)
    this.noise(0.08, 0.05 + i * 0.14, 'low', 420)
  }

  whistle() {
    this.tone(2100, 0.28, 0.14, 'sine', 2000)
    this.noise(0.28, 0.05, 'band', 2300, 6)
  }

  // ---- crowd ----

  // A permanent bed of filtered noise standing in for a stadium, with the gain
  // pushed up briefly when something happens worth reacting to.
  private startCrowd() {
    const ctx = this.ctx
    if (!ctx || !this.noiseBuf || !this.master) return
    const src = ctx.createBufferSource()
    src.buffer = this.noiseBuf
    src.loop = true
    const lp = ctx.createBiquadFilter()
    lp.type = 'lowpass'
    lp.frequency.value = 900
    const hp = ctx.createBiquadFilter()
    hp.type = 'highpass'
    hp.frequency.value = 200
    const g = ctx.createGain()
    g.gain.value = 0.022
    src.connect(hp).connect(lp).connect(g).connect(this.master)
    src.start()
    this.crowdGain = g
  }

  crowdSurge(amount: number) {
    const g = this.crowdGain
    const ctx = this.ctx
    if (!g || !ctx) return
    const t = ctx.currentTime
    g.gain.cancelScheduledValues(t)
    g.gain.setValueAtTime(g.gain.value, t)
    g.gain.linearRampToValueAtTime(0.022 + 0.14 * amount, t + 0.12)
    g.gain.exponentialRampToValueAtTime(0.022, t + 1.4 + amount * 1.6)
  }
}

export const sfx = new Sfx()
