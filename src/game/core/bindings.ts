import { store } from './store'

// What the keyboard means, by name.
//
// Every key was hardcoded at the site that read it — `input.isDown('KeyQ')`
// buried in the controller, `justPressed('KeyN')` in two different game loops.
// That is fine for three keys and untenable at eleven: there was no list of
// what was bound, no way to change any of it, and Q for shield in particular is
// a choice nobody would guess.
//
// So the readers ask for an action and this decides which physical key that
// is. Codes rather than characters, so the defaults stay where your fingers
// are on AZERTY and QWERTZ.

export type Action =
  | 'up' | 'down' | 'left' | 'right'
  | 'sprint' | 'walk'
  | 'jump' | 'shield' | 'slide'
  | 'pause' | 'view' | 'mute' | 'replay'
  | 'spawnBall' | 'nextDrill'

export interface ActionInfo {
  action: Action
  label: string
  // Grouped for the settings screen so movement doesn't sit next to 'mute'.
  group: 'Moving' | 'On the ball' | 'Match'
  // A second key that also fires this action, and which you can't rebind: the
  // arrow keys for movement, the right-hand modifiers. Being able to bind
  // 'sprint' to a key of your choosing shouldn't take away Shift.
  also?: string[]
}

export const ACTIONS: ActionInfo[] = [
  { action: 'up', label: 'Forward', group: 'Moving', also: ['ArrowUp'] },
  { action: 'down', label: 'Back', group: 'Moving', also: ['ArrowDown'] },
  { action: 'left', label: 'Left', group: 'Moving', also: ['ArrowLeft'] },
  { action: 'right', label: 'Right', group: 'Moving', also: ['ArrowRight'] },
  { action: 'sprint', label: 'Sprint', group: 'Moving', also: ['ShiftRight'] },
  { action: 'walk', label: 'Walk (precise)', group: 'Moving', also: ['AltRight'] },
  { action: 'jump', label: 'Jump', group: 'On the ball' },
  { action: 'shield', label: 'Shield — hold', group: 'On the ball' },
  { action: 'slide', label: 'Slide tackle', group: 'On the ball' },
  { action: 'replay', label: 'Replay', group: 'Match' },
  { action: 'view', label: 'Change view', group: 'Match' },
  { action: 'pause', label: 'Pause', group: 'Match' },
  { action: 'mute', label: 'Mute', group: 'Match' },
  { action: 'spawnBall', label: 'Spawn ball (training)', group: 'Match' },
  { action: 'nextDrill', label: 'Next drill (training)', group: 'Match' },
]

export const DEFAULT_BINDS: Record<Action, string> = {
  up: 'KeyW',
  down: 'KeyS',
  left: 'KeyA',
  right: 'KeyD',
  sprint: 'ShiftLeft',
  walk: 'AltLeft',
  jump: 'Space',
  shield: 'KeyQ',
  slide: 'KeyC',
  pause: 'KeyP',
  view: 'KeyV',
  mute: 'KeyM',
  replay: 'KeyR',
  spawnBall: 'KeyB',
  nextDrill: 'KeyN',
}

// Escape always pauses and always backs out of a replay. It is the one key a
// browser game must not let you bind away from, because it is the only way out
// of pointer lock — bind it to 'shield' and you would have no way to reach the
// menu to unbind it.
export const RESERVED = new Set(['Escape'])

class Bindings {
  private map: Record<Action, string> = { ...DEFAULT_BINDS }

  constructor() {
    this.load()
  }

  private load() {
    const saved = store.bindings()
    const next = { ...DEFAULT_BINDS }
    for (const { action } of ACTIONS) {
      const k = saved[action]
      if (typeof k === 'string' && k && !RESERVED.has(k)) next[action] = k
    }
    this.map = next
  }

  get(a: Action): string {
    return this.map[a]
  }

  // Every key that fires this action, including the fixed alternates.
  keys(a: Action): string[] {
    const info = ACTIONS.find((i) => i.action === a)
    return info?.also ? [this.map[a], ...info.also] : [this.map[a]]
  }

  // Which action, if any, a key currently means. Used to tell you what you are
  // about to overwrite.
  actionFor(code: string): Action | null {
    for (const { action } of ACTIONS) if (this.map[action] === code) return action
    return null
  }

  // Binding a key that is already in use clears it from the other action rather
  // than silently giving one key two jobs.
  bind(a: Action, code: string): { ok: boolean; why?: string; displaced?: Action } {
    if (RESERVED.has(code)) return { ok: false, why: `${label(code)} is reserved` }
    const clash = this.actionFor(code)
    if (clash === a) return { ok: true }
    if (clash) this.map[clash] = ''
    this.map[a] = code
    this.save()
    return { ok: true, displaced: clash ?? undefined }
  }

  reset() {
    this.map = { ...DEFAULT_BINDS }
    this.save()
  }

  // Anything bound is a key the page must not scroll or search on.
  gameKeys(): Set<string> {
    const s = new Set<string>()
    for (const { action, also } of ACTIONS) {
      if (this.map[action]) s.add(this.map[action])
      for (const k of also ?? []) s.add(k)
    }
    return s
  }

  private save() {
    const out: Record<string, string> = {}
    for (const { action } of ACTIONS) out[action] = this.map[action]
    store.setBindings(out)
  }
}

export const binds = new Bindings()

// How a KeyboardEvent.code should read on a settings screen. The codes are
// deliberately physical, which makes them unreadable: 'BracketLeft' is a key
// nobody calls that.
export function label(code: string): string {
  if (!code) return '—'
  const named: Record<string, string> = {
    Space: 'Space',
    ShiftLeft: 'L Shift',
    ShiftRight: 'R Shift',
    ControlLeft: 'L Ctrl',
    ControlRight: 'R Ctrl',
    AltLeft: 'L Alt',
    AltRight: 'R Alt',
    Escape: 'Esc',
    Tab: 'Tab',
    Enter: 'Enter',
    Backspace: 'Backspace',
    CapsLock: 'Caps',
    ArrowUp: '↑',
    ArrowDown: '↓',
    ArrowLeft: '←',
    ArrowRight: '→',
    Backquote: '`',
    Minus: '−',
    Equal: '=',
    BracketLeft: '[',
    BracketRight: ']',
    Backslash: '\\',
    Semicolon: ';',
    Quote: "'",
    Comma: ',',
    Period: '.',
    Slash: '/',
  }
  if (named[code]) return named[code]
  if (code.startsWith('Key')) return code.slice(3)
  if (code.startsWith('Digit')) return code.slice(5)
  if (code.startsWith('Numpad')) return `Num ${code.slice(6)}`
  return code
}
