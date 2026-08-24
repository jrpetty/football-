// ---------------------------------------------------------------------------
// A box for a number of pounds.
//
// It holds text rather than a number, deliberately. Halfway through typing
// "42.", "42." is not a valid amount, and a field that fights the person using
// it — snapping, reformatting, refusing the keystroke — is worse than one that
// waits until they have finished.
// ---------------------------------------------------------------------------

import { parsePence } from '../core/money.ts'

interface Props {
  id: string
  label: string
  value: string
  onChange: (text: string) => void
  placeholder?: string
  autoFocus?: boolean
}

export function MoneyInput({ id, label, value, onChange, placeholder, autoFocus }: Props) {
  const invalid = value.trim() !== '' && parsePence(value) === null

  return (
    <div className={`money-field${invalid ? ' invalid' : ''}`}>
      <span className="sign" aria-hidden="true">£</span>
      <input
        id={id}
        aria-label={label}
        // A numeric keypad with a decimal point, rather than type="number":
        // number fields silently discard what they cannot parse, swallow the
        // comma she may well type, and can be nudged by a stray scroll.
        inputMode="decimal"
        autoComplete="off"
        enterKeyHint="done"
        placeholder={placeholder ?? '0.00'}
        value={value}
        aria-invalid={invalid || undefined}
        autoFocus={autoFocus}
        onChange={(e) => onChange(e.target.value)}
        onFocus={(e) => e.target.select()}
      />
    </div>
  )
}
