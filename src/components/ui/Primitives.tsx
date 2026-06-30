import type { ReactNode } from 'react'
import type { Player, PositionGroup, Attributes, AttributeKey } from '../../types'
import {
  initials, POSITION_GROUP_COLOR, statusColor, ratingColor, RESULT_COLOR,
} from '../../utils/format'

// --------------------------------------------------------------- Avatar
export function Avatar({
  player,
  size = 'md',
}: {
  player: Pick<Player, 'name' | 'color'>
  size?: 'sm' | 'md' | 'lg'
}) {
  return (
    <div
      className={`avatar${size === 'lg' ? ' lg' : size === 'sm' ? ' sm' : ''}`}
      style={{ background: `linear-gradient(135deg, ${player.color}, ${player.color}aa)` }}
    >
      {initials(player.name)}
    </div>
  )
}

// --------------------------------------------------------------- Position badge
export function PositionBadge({ group, code }: { group: PositionGroup; code?: string }) {
  return (
    <span className="pos-pill" style={{ background: POSITION_GROUP_COLOR[group] }}>
      {code ?? group}
    </span>
  )
}

// --------------------------------------------------------------- Status badge
export function StatusBadge({ status }: { status: Player['status'] }) {
  const c = statusColor(status)
  return (
    <span className="badge" style={{ borderColor: `${c}55`, color: c }}>
      <span className="dot" style={{ background: c }} />
      {status}
    </span>
  )
}

// --------------------------------------------------------------- Rating chip
export function RatingChip({ rating, small }: { rating: number; small?: boolean }) {
  const c = ratingColor(rating)
  return (
    <span
      style={{
        display: 'inline-grid', placeItems: 'center',
        minWidth: small ? 30 : 38, padding: small ? '2px 5px' : '3px 8px',
        borderRadius: 8, fontWeight: 800, fontSize: small ? 12 : 13.5,
        background: `${c}22`, color: c, border: `1px solid ${c}55`,
        fontVariantNumeric: 'tabular-nums',
      }}
    >
      {rating.toFixed(1)}
    </span>
  )
}

// --------------------------------------------------------------- Form pips
export function FormPips({ form }: { form: ('W' | 'D' | 'L')[] }) {
  return (
    <div style={{ display: 'flex', gap: 4 }}>
      {form.map((r, i) => (
        <span key={i} className="form-pip" style={{ background: RESULT_COLOR[r] }}>
          {r}
        </span>
      ))}
    </div>
  )
}

// --------------------------------------------------------------- Attribute bars
const ATTR_ORDER: AttributeKey[] = ['pace', 'shooting', 'passing', 'dribbling', 'defending', 'physical']
const ATTR_LABEL: Record<AttributeKey, string> = {
  pace: 'Pace', shooting: 'Shooting', passing: 'Passing',
  dribbling: 'Dribbling', defending: 'Defending', physical: 'Physical',
}

function attrColor(v: number): string {
  if (v >= 85) return '#22c55e'
  if (v >= 75) return '#84cc16'
  if (v >= 65) return '#eab308'
  if (v >= 50) return '#f97316'
  return '#ef4444'
}

export function AttributeBars({ attributes }: { attributes: Attributes }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 9 }}>
      {ATTR_ORDER.map((k) => (
        <div className="attr-row" key={k}>
          <span className="attr-name">{ATTR_LABEL[k]}</span>
          <span className="meter">
            <span style={{ width: `${attributes[k]}%`, background: attrColor(attributes[k]) }} />
          </span>
          <span className="attr-val" style={{ color: attrColor(attributes[k]) }}>{attributes[k]}</span>
        </div>
      ))}
    </div>
  )
}

// --------------------------------------------------------------- Empty state
export function EmptyState({ icon = '📋', title, hint }: { icon?: string; title: string; hint?: ReactNode }) {
  return (
    <div className="empty">
      <div className="big">{icon}</div>
      <div style={{ fontWeight: 700, color: 'var(--text-dim)', fontSize: 15 }}>{title}</div>
      {hint && <div style={{ marginTop: 6, fontSize: 13 }}>{hint}</div>}
    </div>
  )
}

// --------------------------------------------------------------- Section header
export function SectionTitle({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
      <h2 style={{ fontSize: 18 }}>{children}</h2>
      {action}
    </div>
  )
}

export { ATTR_ORDER, ATTR_LABEL, attrColor }
