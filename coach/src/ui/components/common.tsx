import type { ReactNode } from 'react'
import type { EvidenceGrade } from '../../core/types.ts'
import { GRADE_TEXT } from '../format.ts'

export function GradeBadge({ grade }: { grade: EvidenceGrade }) {
  return <span className={`badge ${grade}`}>{GRADE_TEXT[grade]}</span>
}

export function Meter({ value, label, color }: { value: number; label: string; color?: string }) {
  const v = Math.max(0, Math.min(1, value))
  return (
    <div className="meter" role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(v * 100)}>
      <span style={{ width: `${v * 100}%`, background: color }} />
    </div>
  )
}

export function Segmented<T extends string>(props: {
  label: string
  value: T
  options: readonly { value: T; label: string }[]
  onChange: (v: T) => void
}) {
  return (
    <div className="segmented" role="radiogroup" aria-label={props.label}>
      {props.options.map((o) => (
        <button key={o.value} type="button" role="radio" aria-checked={o.value === props.value} onClick={() => props.onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function Switch(props: { checked: boolean; onChange: (v: boolean) => void; label: ReactNode; hint?: ReactNode }) {
  return (
    <label className="switch">
      <input type="checkbox" role="switch" checked={props.checked} onChange={(e) => props.onChange(e.target.checked)} />
      <span>
        <span>{props.label}</span>
        {props.hint ? <span className="muted small" style={{ display: 'block' }}>{props.hint}</span> : null}
      </span>
    </label>
  )
}

export function Notice({ level, children }: { level: 'info' | 'warning' | 'error'; children: ReactNode }) {
  return (
    <div className={`notice ${level}`} role={level === 'info' ? 'status' : 'alert'}>
      <span aria-hidden="true">{level === 'info' ? 'ℹ' : '⚠'}</span>
      <div>{children}</div>
    </div>
  )
}

export function Modal(props: { title: string; onClose: () => void; children: ReactNode }) {
  return (
    <div className="backdrop" onMouseDown={(e) => e.target === e.currentTarget && props.onClose()}>
      <div
        className="modal"
        role="dialog"
        aria-modal="true"
        aria-label={props.title}
        onKeyDown={(e) => e.key === 'Escape' && props.onClose()}
      >
        {props.children}
      </div>
    </div>
  )
}

export function MicIcon() {
  return (
    <svg viewBox="0 0 24 24" width="15" height="15" aria-hidden="true">
      <rect x="9" y="3" width="6" height="11" rx="3" fill="currentColor" />
      <path d="M5 11a7 7 0 0 0 14 0M12 18v3" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  )
}
