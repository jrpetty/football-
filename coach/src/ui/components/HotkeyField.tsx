// "Press the key you want": records a global shortcut from a key press.

import { useEffect, useState } from 'react'
import { isBusyKey, isValidHotkey } from '../../core/settings.ts'

const CODE_KEYS: Record<string, string> = {
  Space: 'Space', Tab: 'Tab', Backspace: 'Backspace', Delete: 'Delete', Insert: 'Insert', Home: 'Home', End: 'End',
  PageUp: 'PageUp', PageDown: 'PageDown', Pause: 'Pause', ScrollLock: 'Scrolllock',
  ArrowUp: 'Up', ArrowDown: 'Down', ArrowLeft: 'Left', ArrowRight: 'Right',
  Minus: '-', Equal: '=', BracketLeft: '[', BracketRight: ']', Semicolon: ';', Quote: "'", Comma: ',', Period: '.', Slash: '/', Backquote: '`',
  NumpadAdd: 'numadd', NumpadSubtract: 'numsub', NumpadMultiply: 'nummult', NumpadDivide: 'numdiv', NumpadDecimal: 'numdec',
  Enter: 'Enter', NumpadEnter: 'Enter', CapsLock: 'Capslock', NumLock: 'Numlock', PrintScreen: 'PrintScreen', Backslash: '\\', IntlBackslash: '\\',
  AudioVolumeUp: 'VolumeUp', AudioVolumeDown: 'VolumeDown', AudioVolumeMute: 'VolumeMute',
  MediaPlayPause: 'MediaPlayPause', MediaTrackNext: 'MediaNextTrack', MediaTrackPrevious: 'MediaPreviousTrack', MediaStop: 'MediaStop',
}

/** The accelerator for a key press, or null for a lone modifier or an unsupported key. */
export function acceleratorFor(e: Pick<KeyboardEvent, 'code' | 'ctrlKey' | 'altKey' | 'shiftKey'>): string | null {
  let key: string | undefined
  if (/^Key[A-Z]$/.test(e.code)) key = e.code.slice(3)
  else if (/^Digit[0-9]$/.test(e.code)) key = e.code.slice(5)
  else if (/^F([1-9]|1[0-9]|2[0-4])$/.test(e.code)) key = e.code
  else if (/^Numpad[0-9]$/.test(e.code)) key = `num${e.code.slice(6)}`
  else key = CODE_KEYS[e.code]
  if (!key) return null
  const mods = [e.ctrlKey ? 'Ctrl' : '', e.altKey ? 'Alt' : '', e.shiftKey ? 'Shift' : ''].filter(Boolean)
  return [...mods, key].join('+')
}

export function hotkeyLabel(accel: string): string {
  return accel.replace('CommandOrControl', 'Ctrl').replace(/^num/, 'Num ').replace(/\+num/, '+Num ').replace('Capslock', 'Caps Lock').replace('Numlock', 'Num Lock').replace('Scrolllock', 'Scroll Lock').replace(/Media(\w+)/, 'Media $1').replace(/Volume(\w+)/, 'Volume $1')
}

export function HotkeyField(props: { label: string; hint: string; value: string; taken: readonly string[]; onChange: (accel: string) => void }) {
  const [listening, setListening] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [heads, setHeads] = useState<string | null>(null)

  useEffect(() => {
    if (!listening) return
    const onKey = (e: KeyboardEvent) => {
      e.preventDefault()
      e.stopPropagation()
      if (e.code === 'Escape') {
        setListening(false)
        return
      }
      if (['ControlLeft', 'ControlRight', 'AltLeft', 'AltRight', 'ShiftLeft', 'ShiftRight', 'MetaLeft', 'MetaRight'].includes(e.code)) return
      const accel = acceleratorFor(e)
      if (!accel || !isValidHotkey(accel)) {
        setError(`That key (${e.code || e.key}) cannot be a global key on this system. Try another, or hold Ctrl, Alt or Shift with it.`)
        return
      }
      if (props.taken.includes(accel)) {
        setError(`${hotkeyLabel(accel)} is already used for something else here.`)
        return
      }
      setError(null)
      setHeads(isBusyKey(accel) ? `${hotkeyLabel(accel)} on its own: while Sightline runs the game (and game chat) will not get ${hotkeyLabel(accel)}. Fine if your game does not use it.` : null)
      setListening(false)
      props.onChange(accel)
    }
    // Mouse buttons: a global mouse hook would be needed, which anti-cheat can flag; say how to get one instead.
    const onMouse = (e: MouseEvent) => {
      if (e.button < 3) return
      e.preventDefault()
      setError('Mouse buttons cannot be global keys without a low-level input hook, which anti-cheat (BattlEye in DayZ) can flag. In your mouse software (Logitech G Hub, Razer Synapse…) set the side button to a spare key such as F13, then press that button here.')
    }
    window.addEventListener('keydown', onKey, true)
    window.addEventListener('mousedown', onMouse, true)
    return () => {
      window.removeEventListener('keydown', onKey, true)
      window.removeEventListener('mousedown', onMouse, true)
    }
  }, [listening, props])

  return (
    <div className="hotkey-row">
      <div>
        <div className="hk-label">{props.label}</div>
        <div className="muted small">{props.hint}</div>
        {error ? <div className="small warn-text">{error}</div> : null}
        {!error && heads ? <div className="small muted">{heads}</div> : null}
      </div>
      <button
        type="button"
        className={`keycap${listening ? ' listening' : ''}`}
        onClick={() => {
          setError(null)
          setListening((l) => !l)
        }}
        aria-label={`${props.label}: ${hotkeyLabel(props.value)}. Click, then press a new key.`}
      >
        {listening ? 'Press any key or combo… (Esc to cancel)' : hotkeyLabel(props.value).split('+').map((k, i) => (
          <kbd key={i}>{k}</kbd>
        ))}
      </button>
    </div>
  )
}
