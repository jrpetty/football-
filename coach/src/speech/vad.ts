// Always-listening voice input: find where the player speaks in a stream of
// microphone audio, and decide whether what the local recogniser heard is
// meant for the coach. Pure (no audio APIs), so it can be tested with
// generated sound.
//
// Detection is by loudness against the room's noise (a low percentile of the
// last few seconds): speech starts after a short run of frames well above it
// and ends after a pause. A little audio from before the start is kept so the
// first word is not clipped. Steady game sound through speakers raises the bar
// rather than triggering it; sudden loud game sound can still be taken for
// speech, so a headset works best (the recogniser then usually hears no words).

export const VAD_RATE = 16_000
/** Frame length the detector expects (64 ms at 16 kHz). */
export const VAD_FRAME = 1024
const FRAME_MS = (VAD_FRAME / VAD_RATE) * 1000
/** Frames of level history behind the noise floor (about five seconds). */
const FLOOR_FRAMES = Math.round(5000 / FRAME_MS)
/** The room is listened to for about a second before anything counts as speech. */
const WARMUP_FRAMES = Math.round(1000 / FRAME_MS)

export interface VadOptions {
  /** Speech starts after this long above the threshold. */
  readonly startMs: number
  /** Speech ends after this long below it. */
  readonly endMs: number
  /** Audio kept from before the start. */
  readonly preRollMs: number
  /** Shorter segments (of speech, not counting the pause) are dropped as noise. */
  readonly minSpeechMs: number
  /** Longer speech is cut here and handed on. */
  readonly maxMs: number
  /** The threshold is this many times the room's noise... */
  readonly floorRatio: number
  /** ...but never below this level (RMS, 0..1). */
  readonly minLevel: number
}

export const VAD_DEFAULTS: VadOptions = { startMs: 190, endMs: 800, preRollMs: 320, minSpeechMs: 450, maxMs: 15_000, floorRatio: 3.2, minLevel: 0.012 }

export function rms(frame: Float32Array): number {
  let s = 0
  for (const v of frame) s += v * v
  return Math.sqrt(s / Math.max(1, frame.length))
}

export class VoiceActivity {
  private readonly o: VadOptions
  /** Recent frame levels (about five seconds), for the noise floor. */
  private levels: number[] = []
  private pre: Float32Array[] = []
  private run = 0
  private quiet = 0
  private voiced = 0
  private segment: Float32Array[] | null = null
  /** Last frame's level and threshold, for a meter. */
  level = 0
  threshold = 0

  constructor(options: Partial<VadOptions> = {}) {
    this.o = { ...VAD_DEFAULTS, ...options }
  }

  get speaking(): boolean {
    return this.segment !== null
  }

  /** Forget the speech in progress (the coach is talking, or listening was paused). The room's noise is kept. */
  reset(): void {
    this.pre = []
    this.run = 0
    this.quiet = 0
    this.voiced = 0
    this.segment = null
  }

  /**
   * The room's noise: a low percentile of the last few seconds' levels, so steady sound (a fan,
   * game music) sets it within a second or two while the gaps between words keep it low during speech.
   */
  private floor(): number {
    if (this.levels.length < WARMUP_FRAMES) return Infinity
    const sorted = [...this.levels].sort((a, b) => a - b)
    return Math.max(0.0005, sorted[Math.floor(sorted.length * 0.1)])
  }

  /** Feed one frame; returns a finished utterance (16 kHz mono) when one ends. */
  push(frame: Float32Array): Float32Array | null {
    const level = rms(frame)
    this.levels.push(level)
    if (this.levels.length > FLOOR_FRAMES) this.levels.shift()
    const floor = this.floor()
    this.level = level
    // Until the room has been heard for a moment, nothing counts as speech.
    const threshold = floor === Infinity ? Infinity : Math.max(this.o.minLevel, floor * this.o.floorRatio)
    this.threshold = threshold === Infinity ? 0 : threshold
    const loud = level > threshold
    const frames = (ms: number) => Math.max(1, Math.round(ms / FRAME_MS))

    if (!this.segment) {
      this.pre.push(frame)
      if (this.pre.length > frames(this.o.preRollMs) + frames(this.o.startMs)) this.pre.shift()
      this.run = loud ? this.run + 1 : 0
      if (this.run >= frames(this.o.startMs)) {
        this.segment = [...this.pre]
        this.pre = []
        this.voiced = this.run
        this.quiet = 0
      }
      return null
    }

    this.segment.push(frame)
    // A little below the threshold still counts as speech (hysteresis), so soft word endings are kept.
    if (level > threshold * 0.75) {
      this.voiced++
      this.quiet = 0
    } else this.quiet++
    const long = this.segment.length >= frames(this.o.maxMs)
    if (this.quiet < frames(this.o.endMs) && !long) return null
    const seg = this.segment
    const voiced = this.voiced
    this.reset()
    if (voiced < frames(this.o.minSpeechMs)) return null
    const out = new Float32Array(seg.reduce((n, f) => n + f.length, 0))
    let at = 0
    for (const f of seg) {
      out.set(f, at)
      at += f.length
    }
    return out
  }
}

/**
 * The speech in a recording, with a short lead-in and tail: the local recogniser (Moonshine)
 * returns nothing when a second or more of quiet comes before the words, which is what a press
 * of the talk key usually records. The whole recording when no speech stands out.
 */
export function trimToSpeech(pcm: Float32Array, o: Pick<VadOptions, 'floorRatio' | 'minLevel'> = VAD_DEFAULTS): Float32Array {
  const levels: number[] = []
  for (let i = 0; i + VAD_FRAME <= pcm.length; i += VAD_FRAME) levels.push(rms(pcm.subarray(i, i + VAD_FRAME)))
  if (levels.length < 4) return pcm
  const sorted = [...levels].sort((a, b) => a - b)
  const threshold = Math.max(o.minLevel, sorted[Math.floor(sorted.length * 0.1)] * o.floorRatio)
  // Speech: two frames in a row above the threshold (a click is one).
  const loud = levels.map((l, i) => l > threshold && ((levels[i + 1] ?? 0) > threshold || (levels[i - 1] ?? 0) > threshold))
  const first = loud.indexOf(true)
  const last = loud.lastIndexOf(true)
  if (first < 0) return pcm
  const lead = Math.round(0.45 * VAD_RATE)
  const tail = Math.round(0.5 * VAD_RATE)
  const from = Math.max(0, first * VAD_FRAME - lead)
  const to = Math.min(pcm.length, (last + 1) * VAD_FRAME + tail)
  return pcm.subarray(from, to)
}

// --- Is it for the coach? -------------------------------------------------------------------

/** How the recogniser tends to hear "coach" (Moonshine, a headset, a noisy room). */
const WAKE = /^(?:(?:hey|hi|ok|okay|yo|so)\s+)?(?:coach|couch|coat|coach's|coaches|coaching|kotch|koch|cotch|poach|hoach|hooch|hoat|hoot|koach)\b[\s,.:!?-]*/i
/** What the recogniser makes of a cough, a click or game noise: never a question. */
const NOISE = /^(?:you|thank you|thanks|thanks for watching|bye|uh+|um+|hm+|mm+|ah+|oh+|okay|ok|yeah|yes|no|the|a|i|so|and|huh|wow|nice|what)$/i

/**
 * "Coach" as the recogniser often mishears it ("Ouch,", "Poch,", "Hoech,"): a first word within two
 * letters of "coach", followed by a comma (the pause after a name). Without the comma, only the
 * listed spellings count, so "catch them" or "each of you" are not taken for the coach.
 */
function nearWake(text: string): RegExpExecArray | null {
  const m = /^((?:hey|hi|ok|okay|yo|so)\s+)?([a-z']{3,7}),\s*/i.exec(text)
  return m && editDistance(m[2].toLowerCase(), 'coach') <= 2 ? m : null
}

function editDistance(a: string, b: string): number {
  const d = Array.from({ length: a.length + 1 }, (_, i) => Array.from({ length: b.length + 1 }, (_, j) => (i === 0 ? j : j === 0 ? i : 0)))
  for (let i = 1; i <= a.length; i++) for (let j = 1; j <= b.length; j++) d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1))
  return d[a.length][b.length]
}

function words(text: string): string[] {
  return text
    .toLowerCase()
    .replace(/[^a-z0-9'\s-]/g, ' ')
    .split(/\s+/)
    .filter(Boolean)
}

export type Heard =
  /** A question for the coach: ask it. */
  | { readonly kind: 'question'; readonly text: string }
  /** Just "coach": the next thing said within a few seconds is the question. */
  | { readonly kind: 'wake' }
  /** Not for the coach (talk to friends, noise). */
  | { readonly kind: 'ignore' }

/**
 * What a transcript means. With the wake word on, only speech that starts with "coach" (or
 * "hey coach") is a question; "coach" on its own opens a short window for the question. With it
 * off, anything of two words or more is a question, except the recogniser's usual noise.
 */
export function heardCommand(transcript: string, wakeWord: boolean, awaitingQuestion = false): Heard {
  const text = transcript.trim().replace(/\s+/g, ' ')
  const plain = words(text).join(' ')
  if (!plain || NOISE.test(plain)) return { kind: 'ignore' }
  const m = WAKE.exec(text.replace(/^[^a-z]+/i, '')) ?? nearWake(text.replace(/^[^a-z]+/i, ''))
  if (m) {
    const rest = text.replace(/^[^a-z]+/i, '').slice(m[0].length).trim()
    if (!rest || NOISE.test(words(rest).join(' '))) return { kind: 'wake' }
    return { kind: 'question', text: capitalise(rest) }
  }
  if (wakeWord && !awaitingQuestion) return { kind: 'ignore' }
  if (words(text).length < 2) return { kind: 'ignore' }
  return { kind: 'question', text: capitalise(text) }
}

function capitalise(s: string): string {
  return s ? s[0].toUpperCase() + s.slice(1) : s
}

/** A talk-key question without a leading "Coach," (everything said with the key is for the coach). */
export function withoutWakeWord(transcript: string): string {
  const text = transcript.trim().replace(/^[^a-z]+/i, '')
  const m = WAKE.exec(text) ?? nearWake(text)
  const rest = m ? text.slice(m[0].length).trim() : text
  return rest ? capitalise(rest) : transcript.trim()
}
