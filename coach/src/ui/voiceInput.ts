// Voice input for the session: the talk key and talk button (push-to-talk),
// always-listening with the "coach" wake word, and the ready check's key and
// microphone tests. The session owns the state React reads; this owns the
// microphone, and asks questions through the session.

import type { SightlineBridge } from '../../shared/bridge.ts'
import type { CloudStatus } from '../../shared/cloud.ts'
import type { CoachSettings } from '../core/settings.ts'
import { PushToTalkRecorder, micErrorMessage } from '../speech/recorder.ts'
import { AlwaysListener } from '../speech/listener.ts'
import { heardCommand, trimToSpeech, withoutWakeWord } from '../speech/vad.ts'
import type { SpeechController } from '../speech/speech.ts'
import { tone } from './tones.ts'

export type TalkState = 'idle' | 'recording' | 'transcribing'

export interface ListenState {
  readonly status: 'off' | 'starting' | 'on' | 'paused' | 'error'
  readonly error: string | null
  /** The last thing the recogniser heard, and what it was taken for. */
  readonly heard: { readonly text: string; readonly kind: 'question' | 'wake' | 'ignore'; readonly at: number } | null
  /** "Coach" was heard on its own: the next thing said is the question. */
  readonly awaiting: boolean
}

export const LISTEN_OFF: ListenState = { status: 'off', error: null, heard: null, awaiting: false }
/** After "coach" on its own, how long the next words count as the question. */
const AWAIT_QUESTION_MS = 8000
const LISTEN_RETRY_MS = 5000

/** What voice input needs from the session. */
export interface VoiceHost {
  readonly bridge: SightlineBridge
  readonly speech: SpeechController
  settings(): CoachSettings | null
  cloud(): CloudStatus | null
  talk(): TalkState
  listen(): ListenState
  set(patch: { talk?: TalkState; talkError?: string | null; listen?: ListenState }): void
  /** Ask the coach a question heard by voice. */
  ask(text: string, cloud: CloudStatus): Promise<void>
  /** A question was heard (it may become the note on a just-marked moment). */
  heard(text: string): void
  /** Capture is running (talk problems are then not said out loud). */
  capturing(): boolean
}

export class VoiceInput {
  private readonly host: VoiceHost
  private recorder: PushToTalkRecorder | null = null
  /** The talk key was let go while the microphone was still opening. */
  private stopWhenOpen = false
  private listener: AlwaysListener | null = null
  private listenOpening = false
  private listenPaused = false
  private listenBusy = false
  /** When always-listening last failed to start, so it is not retried in a loop. */
  private listenFailedAt = -Infinity
  private awaitUntil = 0
  private awaitTimer: ReturnType<typeof setTimeout> | null = null
  /** The ready check is waiting for the talk key: a press only answers it. */
  private keyTest: ((ok: boolean) => void) | null = null
  private seq = 0

  constructor(host: VoiceHost) {
    this.host = host
  }

  // --- Ready check ---------------------------------------------------------------------

  /** Resolves true when the talk key reaches the app within 15 s (nothing is recorded). */
  testTalkKey(): Promise<boolean> {
    this.keyTest?.(false)
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.keyTest = null
        resolve(false)
      }, 15_000)
      this.keyTest = (ok) => {
        clearTimeout(timer)
        this.keyTest = null
        resolve(ok)
      }
    })
  }

  /** Open the microphone for a moment and report the loudest level heard (0..1). */
  async testMic(): Promise<{ ok: true; peak: number } | { ok: false; error: string }> {
    if (!this.host.settings()?.voiceInput) return { ok: false, error: 'Voice input is off.' }
    try {
      let peak = 0
      const rec = await PushToTalkRecorder.start(() => undefined)
      const end = Date.now() + 2500
      while (Date.now() < end) {
        await new Promise((r) => setTimeout(r, 50))
        peak = Math.max(peak, rec.level)
      }
      rec.cancel()
      return { ok: true, peak }
    } catch (err) {
      return { ok: false, error: micErrorMessage(err) }
    }
  }

  // --- Push-to-talk --------------------------------------------------------------------

  /** The talk key: a press starts recording; the next tap, or letting go of a held key, asks. */
  async talkKey(action: 'start' | 'stop', cloud: CloudStatus): Promise<void> {
    if (this.keyTest) {
      if (action === 'start') this.keyTest(true)
      return
    }
    if (action === 'start') {
      if (this.host.talk() === 'idle') await this.startTalk(cloud)
    } else if (this.host.talk() === 'recording') await this.finishTalk(cloud)
  }

  /** The talk button: first click starts recording, the second stops and asks. */
  async toggleTalk(cloud: CloudStatus): Promise<void> {
    if (this.host.talk() === 'transcribing') return
    if (this.host.talk() === 'recording') {
      this.host.bridge.talkEnded()
      return this.finishTalk(cloud)
    }
    return this.startTalk(cloud)
  }

  cancelTalk(): void {
    this.recorder?.cancel()
    this.recorder = null
    if (this.host.talk() !== 'idle') {
      this.host.bridge.talkEnded()
      this.host.set({ talk: 'idle' })
    }
    this.resume()
  }

  private async startTalk(cloud: CloudStatus): Promise<void> {
    if (!this.host.settings()?.voiceInput) return this.talkFailed('Voice input is off. Turn it on in Settings, Voice input.')
    // Recording from now on, even while the microphone opens, so a quick release is not lost.
    this.host.set({ talk: 'recording', talkError: null })
    this.stopWhenOpen = false
    this.listener?.setPaused(true)
    try {
      // Stop the coach talking first, so the microphone does not hear it.
      this.host.speech.stopAll()
      tone('listen')
      this.recorder = await PushToTalkRecorder.start(() => {
        this.host.bridge.talkEnded()
        void this.finishTalk(cloud)
      })
      if (this.stopWhenOpen) await this.finishTalk(cloud)
    } catch (err) {
      this.recorder = null
      this.talkFailed(micErrorMessage(err))
    }
  }

  private async finishTalk(cloud: CloudStatus): Promise<void> {
    const rec = this.recorder
    if (!rec) {
      // Still opening the microphone: stop as soon as it is open.
      if (this.host.talk() === 'recording') this.stopWhenOpen = true
      return
    }
    this.recorder = null
    this.host.set({ talk: 'transcribing' })
    tone('send')
    try {
      const pcm = await rec.stop()
      if (pcm.length < 16_000 * 0.3) return this.talkFailed('That was too short to hear. Hold the talk key while you ask, or tap it, ask, and tap it again.')
      const text = withoutWakeWord(await this.host.bridge.transcribe(trimToSpeech(pcm)))
      if (!text) return this.talkFailed('I did not catch that. Try again a little closer to the microphone.')
      this.host.set({ talk: 'idle', talkError: null })
      this.host.heard(text)
      await this.host.ask(text, cloud)
    } catch (err) {
      this.talkFailed(talkErrorMessage(err))
    } finally {
      this.resume()
    }
  }

  /**
   * A talk problem: shown, and said out loud when not mid-match, since the
   * player pressed a key while looking at the game and would otherwise hear
   * nothing. During capture it is a tone and the on-screen message only.
   */
  private talkFailed(message: string): void {
    this.host.set({ talk: 'idle', talkError: message })
    tone('error')
    this.resume()
    if (!this.host.capturing() && !this.host.settings()?.muted) {
      this.host.speech.enqueue({ id: `talk-error-${++this.seq}`, text: message, category: 'descriptive', priority: 'normal', expiresAt: null })
    }
  }

  // --- Always listening ----------------------------------------------------------------

  /** Pause or resume always-listening from the top bar (not saved: it is on again next time). */
  setPaused(paused: boolean): void {
    this.listenPaused = paused
    this.sync()
  }

  /** Try opening the microphone again after a problem. */
  retry(): void {
    this.listenFailedAt = -Infinity
    this.listener?.stop()
    this.listener = null
    this.setListen({ status: 'off', error: null })
    this.sync()
  }

  /** Input level for the meter in Settings (0..1), or null when not listening. */
  level(): { level: number; threshold: number; speaking: boolean } | null {
    return this.listener?.level ?? null
  }

  /** The coach stopped talking (or push-to-talk ended): hear again after a short tail. */
  resume(): void {
    this.listener?.setPaused(this.host.speech.speakingId !== null || this.host.talk() !== 'idle')
  }

  /** Open or close the always-listening microphone to match the settings and the pause button. */
  sync(): void {
    const settings = this.host.settings()
    const want = !!settings?.voiceInput && settings.listen === 'always'
    const listen = this.host.listen()
    if (!want || this.listenPaused) {
      this.listener?.stop()
      this.listener = null
      const status = want ? 'paused' : 'off'
      if (listen.status !== status) this.host.set({ listen: { ...LISTEN_OFF, status, heard: listen.heard } })
      return
    }
    // After a problem (no speech model yet, microphone blocked), try again on the next change, not in a loop.
    if (this.listener || this.listenOpening || (listen.status === 'error' && Date.now() - this.listenFailedAt < LISTEN_RETRY_MS)) return
    this.listenOpening = true
    this.setListen({ status: 'starting', error: null })
    void (async () => {
      try {
        const voice = await this.host.bridge.voiceStatus()
        if (!voice.installed) {
          this.listenFailedAt = Date.now()
          this.setListen({ status: 'error', error: 'Speech recognition is not downloaded yet. Get it in Settings, Voice input.' })
          return
        }
        const l = await AlwaysListener.start((pcm) => void this.heardSpeech(pcm))
        const now = this.host.settings()
        if (!(now?.voiceInput && now.listen === 'always') || this.listenPaused) {
          l.stop()
          return
        }
        this.listener = l
        l.setPaused(this.host.speech.speakingId !== null || this.host.talk() !== 'idle', 0)
        this.setListen({ status: 'on', error: null })
      } catch (err) {
        this.listenFailedAt = Date.now()
        this.setListen({ status: 'error', error: micErrorMessage(err) })
      } finally {
        this.listenOpening = false
        // The settings may have changed while the microphone was opening.
        if (this.host.listen().status !== 'error') this.sync()
      }
    })()
  }

  private setListen(patch: Partial<ListenState>): void {
    this.host.set({ listen: { ...this.host.listen(), ...patch } })
  }

  /** A short tone without the always-on microphone taking it for speech. */
  private cue(kind: Parameters<typeof tone>[0]): void {
    this.listener?.setPaused(true)
    tone(kind)
    this.listener?.setPaused(this.host.speech.speakingId !== null || this.host.talk() !== 'idle', 700)
  }

  /** A stretch of speech heard while always listening: transcribe it, and ask it if it was for the coach. */
  private async heardSpeech(pcm: Float32Array): Promise<void> {
    if (this.listenBusy || this.host.talk() !== 'idle') return
    this.listenBusy = true
    try {
      // Checked for speech first (game sound is dropped before it is transcribed).
      const text = (await this.host.bridge.transcribe(trimToSpeech(pcm), true)).trim()
      if (!text) return
      const h = heardCommand(text, this.host.settings()?.wakeWord ?? true, Date.now() < this.awaitUntil)
      this.setListen({ error: null, heard: { text, kind: h.kind, at: Date.now() }, awaiting: h.kind === 'wake' })
      if (this.awaitTimer) clearTimeout(this.awaitTimer)
      this.awaitTimer = null
      if (h.kind === 'wake') {
        this.awaitUntil = Date.now() + AWAIT_QUESTION_MS
        this.awaitTimer = setTimeout(() => this.setListen({ awaiting: false }), AWAIT_QUESTION_MS)
        this.cue('listen')
        return
      }
      if (h.kind !== 'question') return
      this.awaitUntil = 0
      this.cue('send')
      this.host.heard(h.text)
      const cloud = this.host.cloud()
      if (cloud) await this.host.ask(h.text, cloud)
    } catch (err) {
      this.setListen({ error: talkErrorMessage(err) })
    } finally {
      this.listenBusy = false
    }
  }
}

/** A readable message for a failed transcription (IPC errors carry a long prefix). */
export function talkErrorMessage(err: unknown): string {
  const raw = err instanceof Error ? err.message : String(err)
  const msg = raw.replace(/^Error invoking remote method '[^']+': (?:Error: )?/, '')
  if (/not installed/i.test(msg)) return 'Speech recognition is not downloaded yet. Get it in Settings, Voice input.'
  if (/voice input is off/i.test(msg)) return 'Voice input is off. Turn it on in Settings, Voice input.'
  return `I could not understand the recording: ${msg}`
}
