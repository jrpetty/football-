// Local text-to-speech. Uses the platform speech synthesiser and *only* voices
// that report themselves as local (`localService`), so no coaching text is
// sent to a cloud voice. Speech is one-at-a-time, interruptible, and an
// utterance that could not start before its expiry is dropped, not delayed.

import type { CoachSettings } from '../core/settings.ts'
import type { Utterance } from '../core/types.ts'

/** The subset of the Web Speech API this module uses (injectable for tests). */
export interface Synth {
  speak(u: SpeechSynthesisUtterance): void
  cancel(): void
  getVoices(): SpeechSynthesisVoice[]
}

export interface SpeechEvents {
  onStart?(id: string): void
  onEnd?(id: string, outcome: 'finished' | 'interrupted' | 'expired' | 'error'): void
}

export interface LocalVoice {
  readonly voiceURI: string
  readonly name: string
  readonly lang: string
  readonly isDefault: boolean
}

const RANK: Record<Utterance['priority'], number> = { low: 0, normal: 1, high: 2 }

/** Waiting utterances beyond this are dropped: a backlog of advice is noise. */
const MAX_WAITING = 2

export class SpeechController {
  private readonly synth: Synth | null
  private readonly makeUtterance: (text: string) => SpeechSynthesisUtterance
  private readonly events: SpeechEvents
  private voice: CoachSettings['voice'] = { voiceURI: null, rate: 1, volume: 0.8 }
  private waiting: Utterance[] = []
  private current: { id: string; u: SpeechSynthesisUtterance; priority: Utterance['priority'] } | null = null
  private clock: () => number = () => performance.now()

  constructor(synth: Synth | null, makeUtterance: (text: string) => SpeechSynthesisUtterance, events: SpeechEvents = {}) {
    this.synth = synth
    this.makeUtterance = makeUtterance
    this.events = events
  }

  get available(): boolean {
    return this.synth !== null && this.localVoices().length > 0
  }

  get speakingId(): string | null {
    return this.current?.id ?? null
  }

  localVoices(): LocalVoice[] {
    if (!this.synth) return []
    return this.synth
      .getVoices()
      .filter((v) => v.localService)
      .map((v) => ({ voiceURI: v.voiceURI, name: v.name, lang: v.lang, isDefault: v.default }))
  }

  configure(voice: CoachSettings['voice']): void {
    this.voice = voice
  }

  /** The clock that utterance expiry times are expressed on. */
  setClock(clock: () => number): void {
    this.clock = clock
  }

  /**
   * Queue an utterance. Higher priority goes first; a high-priority message
   * interrupts lower-priority speech already playing (a fresh live tip beats
   * an old note). When the backlog is full, the lowest-priority, oldest
   * message is dropped.
   */
  enqueue(u: Utterance): void {
    this.waiting.push(u)
    this.waiting.sort((a, b) => RANK[b.priority] - RANK[a.priority])
    while (this.waiting.length > MAX_WAITING) {
      // Drop the oldest of the least important: stale advice is worth least.
      const lowest = Math.min(...this.waiting.map((w) => RANK[w.priority]))
      const i = this.waiting.findIndex((w) => RANK[w.priority] === lowest)
      this.events.onEnd?.(this.waiting.splice(i, 1)[0].id, 'expired')
    }
    if (this.current && RANK[u.priority] > RANK[this.current.priority] && u.priority === 'high') this.interruptCurrent()
    else this.pump()
  }

  /** Drop specific utterances (e.g. reflective speech when a new round starts). */
  cancel(ids: readonly string[]): void {
    const set = new Set(ids)
    this.waiting = this.waiting.filter((u) => {
      if (!set.has(u.id)) return true
      this.events.onEnd?.(u.id, 'interrupted')
      return false
    })
    if (this.current && set.has(this.current.id)) this.interruptCurrent()
  }

  /** Silence everything immediately: mute, Stop Capture, or "quiet" button. */
  stopAll(): void {
    for (const u of this.waiting) this.events.onEnd?.(u.id, 'interrupted')
    this.waiting = []
    if (this.current) this.interruptCurrent()
  }

  speakTest(text: string): void {
    this.enqueue({ id: `test-${Date.now()}`, text, category: 'descriptive', priority: 'normal', expiresAt: null })
  }

  private interruptCurrent(): void {
    const id = this.current!.id
    this.current = null
    this.synth?.cancel()
    this.events.onEnd?.(id, 'interrupted')
    this.pump()
  }

  private pump(): void {
    if (this.current || !this.synth) return
    let next: Utterance | undefined
    while ((next = this.waiting.shift())) {
      if (next.expiresAt !== null && this.clock() > next.expiresAt) {
        this.events.onEnd?.(next.id, 'expired')
        continue
      }
      break
    }
    if (!next) return
    const u = this.makeUtterance(next.text)
    const voices = this.synth.getVoices().filter((v) => v.localService)
    const chosen = voices.find((v) => v.voiceURI === this.voice.voiceURI) ?? voices.find((v) => v.default) ?? voices[0]
    if (!chosen) {
      // No local voice: never fall back to a network voice.
      this.events.onEnd?.(next.id, 'error')
      return
    }
    u.voice = chosen
    u.rate = this.voice.rate
    u.volume = this.voice.volume
    const id = next.id
    const finish = (outcome: 'finished' | 'error') => {
      if (this.current?.id !== id) return
      this.current = null
      this.events.onEnd?.(id, outcome)
      this.pump()
    }
    u.onend = () => finish('finished')
    u.onerror = () => finish('error')
    this.current = { id, u, priority: next.priority }
    this.events.onStart?.(id)
    this.synth.speak(u)
  }
}

export function createBrowserSpeech(events: SpeechEvents): SpeechController {
  const synth = typeof window !== 'undefined' && 'speechSynthesis' in window ? window.speechSynthesis : null
  return new SpeechController(synth, (text) => new SpeechSynthesisUtterance(text), events)
}
