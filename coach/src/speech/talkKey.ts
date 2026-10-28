// The talk key as the player uses it: tap to start and tap again to stop, or
// hold while speaking and let go. A global shortcut only reports key presses,
// never releases, and on Windows and Linux a held key is reported again at the
// keyboard's repeat rate (about 30 a second, after a first delay of up to a
// second). Each report used to flip recording on and off, so holding the key
// never recorded anything. Here the repeats are read as "still held" and their
// stopping as the release. Pure, so it can be tested with a fake clock.

export type TalkKeyAction = 'start' | 'stop'

/** The longest wait for the first auto-repeat (Windows' slowest repeat delay is 1 s). */
export const FIRST_REPEAT_MS = 1300
/** Gap between repeats that means the key was let go (the slowest repeat rate is about 400 ms). */
export const RELEASE_GAP_MS = 450

type State =
  | { readonly kind: 'idle' }
  /** Just pressed: recording; is it a tap or the start of a hold? */
  | { readonly kind: 'pressed'; readonly pressAt: number; readonly lastAt: number }
  /** Held down (repeats arriving): stop when they stop. */
  | { readonly kind: 'held'; readonly lastAt: number }
  /** Tapped: recording until the next press. */
  | { readonly kind: 'latched' }
  /** Stopped by a press: swallow that press's repeats until the key is let go (the first repeat can take a second). */
  | { readonly kind: 'draining'; readonly lastAt: number; readonly repeating: boolean }

export class TalkKeyGesture {
  private state: State = { kind: 'idle' }

  /** A report of the key (a press or an auto-repeat). */
  press(now: number): TalkKeyAction | null {
    const s = this.state
    switch (s.kind) {
      case 'idle':
        this.state = { kind: 'pressed', pressAt: now, lastAt: now }
        return 'start'
      case 'pressed':
        this.state = now - s.lastAt <= FIRST_REPEAT_MS ? { kind: 'held', lastAt: now } : { kind: 'draining', lastAt: now, repeating: false }
        return this.state.kind === 'draining' ? 'stop' : null
      case 'held':
        this.state = { kind: 'held', lastAt: now }
        return null
      case 'latched':
        this.state = { kind: 'draining', lastAt: now, repeating: false }
        return 'stop'
      case 'draining':
        this.state = { kind: 'draining', lastAt: now, repeating: true }
        return null
    }
  }

  /** Called often while the key may be down: the release of a hold, or the end of a tap's repeats. */
  tick(now: number): TalkKeyAction | null {
    const s = this.state
    if (s.kind === 'pressed' && now - s.pressAt > FIRST_REPEAT_MS) this.state = { kind: 'latched' }
    else if (s.kind === 'held' && now - s.lastAt > RELEASE_GAP_MS) {
      this.state = { kind: 'idle' }
      return 'stop'
    } else if (s.kind === 'draining' && now - s.lastAt > (s.repeating ? RELEASE_GAP_MS : FIRST_REPEAT_MS)) this.state = { kind: 'idle' }
    return null
  }

  /** Whether tick() still has anything to watch for. */
  get watching(): boolean {
    return this.state.kind === 'pressed' || this.state.kind === 'held' || this.state.kind === 'draining'
  }

  /** Recording ended some other way (the time limit, the on-screen button): the next press starts again. */
  reset(): void {
    const s = this.state
    // A key still held keeps repeating: swallow those repeats first.
    this.state =
      s.kind === 'held' || s.kind === 'pressed' ? { kind: 'draining', lastAt: s.lastAt, repeating: s.kind === 'held' } : s.kind === 'draining' ? s : { kind: 'idle' }
  }
}

/**
 * The other global keys (stop, mark a moment): one action per press, whatever the repeat rate.
 * A report closer than this to the last one is a repeat of the same press.
 */
export class SinglePress {
  private lastAt = -Infinity
  private readonly gapMs: number

  constructor(gapMs = FIRST_REPEAT_MS) {
    this.gapMs = gapMs
  }

  press(now: number): boolean {
    const fresh = now - this.lastAt > this.gapMs
    this.lastAt = now
    return fresh
  }
}
