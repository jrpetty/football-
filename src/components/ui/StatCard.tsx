import type { ReactNode } from 'react'

export function StatCard({
  label,
  value,
  unit,
  sub,
  icon,
  accent,
  trend,
}: {
  label: string
  value: ReactNode
  unit?: string
  sub?: ReactNode
  icon?: ReactNode
  accent?: string
  trend?: { value: number; good?: 'up' | 'down' }
}) {
  let trendEl: ReactNode = null
  if (trend) {
    const up = trend.value >= 0
    const isGood = trend.good === 'down' ? !up : up
    trendEl = (
      <span style={{ color: isGood ? 'var(--green)' : 'var(--red)', fontSize: 12, fontWeight: 700 }}>
        {up ? '▲' : '▼'} {Math.abs(trend.value)}
      </span>
    )
  }
  return (
    <div className="stat">
      <div className="stat-label">
        {icon && <span style={{ color: accent ?? 'var(--accent)' }}>{icon}</span>}
        {label}
      </div>
      <div className="stat-value" style={accent ? { color: accent } : undefined}>
        {value}
        {unit && <span className="unit">{unit}</span>}
      </div>
      {(sub || trendEl) && (
        <div className="stat-foot" style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
          {trendEl}
          {sub}
        </div>
      )}
    </div>
  )
}
