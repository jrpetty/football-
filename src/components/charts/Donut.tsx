export interface DonutSlice {
  label: string
  value: number
  color: string
}

/** Donut / ring chart with an optional centre caption. */
export function Donut({
  slices,
  size = 160,
  thickness = 22,
  centerLabel,
  centerSub,
}: {
  slices: DonutSlice[]
  size?: number
  thickness?: number
  centerLabel?: string
  centerSub?: string
}) {
  const total = slices.reduce((s, x) => s + x.value, 0) || 1
  const r = (size - thickness) / 2
  const cx = size / 2
  const cy = size / 2
  const circ = 2 * Math.PI * r
  let offset = 0

  return (
    <div style={{ display: 'inline-flex', flexDirection: 'column', alignItems: 'center', gap: 12 }}>
      <svg viewBox={`0 0 ${size} ${size}`} style={{ width: size, height: size }}>
        <circle cx={cx} cy={cy} r={r} fill="none" stroke="var(--bg-2)" strokeWidth={thickness} />
        {slices.map((s, i) => {
          const frac = s.value / total
          const dash = frac * circ
          const el = (
            <circle
              key={i}
              cx={cx}
              cy={cy}
              r={r}
              fill="none"
              stroke={s.color}
              strokeWidth={thickness}
              strokeDasharray={`${dash} ${circ - dash}`}
              strokeDashoffset={-offset}
              transform={`rotate(-90 ${cx} ${cy})`}
              strokeLinecap="butt"
            />
          )
          offset += dash
          return el
        })}
        {centerLabel && (
          <text x={cx} y={cy - 2} textAnchor="middle" dominantBaseline="middle" fontSize={22} fontWeight={800} fill="#e8eef7">
            {centerLabel}
          </text>
        )}
        {centerSub && (
          <text x={cx} y={cy + 16} textAnchor="middle" fontSize={10} fill="#65779a">
            {centerSub}
          </text>
        )}
      </svg>
    </div>
  )
}
