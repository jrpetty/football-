// "Press the key you want": records a global shortcut from a key press.

import { useEffect, useState } from 'react'
import { isValidHotkey } from '../../core/settings.ts'

const CODE_KEYS: Record<string, string> = {
  Space: 'Space', Tab: 'Tab', Backspace: 'Backspace', Delete: 'Delete', Insert: 'Insert', Home: 'Home', End: 'End',
  PageUp: 'PageUp', PageDown: 'PageDown', Pause: 'Pause', ScrollLock: 'Scrolllock',
  ArrowUp: 'Up', ArrowDown: 'Down', ArrowLeft: 'Left', ArrowRight: 'Right',
  Minus: '-', Equal: '=', BracketLeft: '[', BracketRight: ']', Semicolon: ';', Quote: "'", Comma: ',', Period: '.', Slash: '/', Backquote: '`',
  NumpadAdd: 'numadd', NumpadSubtract: 'numsub', NumpadMultiply: 'nummult', NumpadDivide: 'numdiv', NumpadDecimal: 'numdec',
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
  return accel.replace('CommandOrControl', 'Ctrl').replace(/^num/, 'Num ').replace(/\+num/, '+Num ')
}

export function HotkeyField(props: { label: string; hint: string; value: string; taken: readonly string[]; onChange: (accel: string) => void }) {
  const [listening, setListening] = useState(false)
  const [error, setError] = useState<string | null>(null)

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
        setError('Use an F-key on its own, or hold Ctrl, Alt or Shift with another key (a bare letter would trigger while you type in chat).')
        return
      }
      if (props.taken.includes(accel)) {
        setError(`${hotkeyLabel(accel)} is already used for something else here.`)
        return
      }
      setError(null)
      setListening(false)
      props.onChange(accel)
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [listening, props])

  return (
    <div className="hotkey-row">
      <div>
        <div className="hk-label">{props.label}</div>
        <div className="muted small">{props.hint}</div>
        {error ? <div className="small warn-text">{error}</div> : null}
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
        {listening ? 'Press a key… (Esc to cancel)' : hotkeyLabel(props.value).split('+').map((k, i) => (
          <kbd key={i}>{k}</kbd>
        ))}
      </button>
    </div>
  )
}
