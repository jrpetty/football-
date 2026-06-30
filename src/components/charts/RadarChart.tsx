export interface RadarSeries {
  name: string
  color: string
  values: number[]
}

/**
 * Multi-series radar/spider chart. All series must share the same axis order.
 */
export function RadarChart({
  axes,
  series,
  max = 100,
  size = 280,
  showLabels = true,
}: {
  axes: string[]
  series: RadarSeries[]
  max?: number
  size?: number
  showLabels?: boolean
}) {
  const cx = size / 2
  const cy = size / 2
  const r = size / 2 - (showLabels ? 42 : 14)
  const n = axes.length
  const angle = (i: number) => (Math.PI * 2 * i) / n - Math.PI / 2

  const point = (i: number, value: number) => {
    const rr = (Math.max(0, Math.min(max, value)) / max) * r
    return [cx + rr * Math.cos(angle(i)), cy + rr * Math.sin(angle(i))]
  }

  const rings = [0.25, 0.5, 0.75, 1]
  // Extra horizontal room so side axis labels aren't clipped at the edge.
  const padX = showLabels ? 40 : 6

  return (
    <svg
      viewBox={`${-padX} 0 ${size + padX * 2} ${size}`}
      style={{ width: '100%', height: 'auto', display: 'block' }}
    >
      {/* Grid rings */}
      {rings.map((ring, ri) => {
        const pts = axes
          .map((_, i) => {
            const x = cx + r * ring * Math.cos(angle(i))
            const y = cy + r * ring * Math.sin(angle(i))
            return `${x},${y}`
          })
          .join(' ')
        return (
          <polygon
            key={ri}
            points={pts}
            fill={ri === rings.length - 1 ? 'rgba(255,255,255,0.02)' : 'none'}
            stroke="rgba(255,255,255,0.1)"
            strokeWidth={1}
          />
        )
      })}
      {/* Spokes */}
      {axes.map((_, i) => {
        const x = cx + r * Math.cos(angle(i))
        const y = cy + r * Math.sin(angle(i))
        return <line key={i} x1={cx} y1={cy} x2={x} y2={y} stroke="rgba(255,255,255,0.08)" strokeWidth={1} />
      })}
      {/* Series */}
      {series.map((s, si) => {
        const pts = s.values.map((v, i) => point(i, v))
        const path = pts.map((p) => p.join(',')).join(' ')
        return (
          <g key={si}>
            <polygon points={path} fill={s.color} fillOpacity={0.18} stroke={s.color} strokeWidth={2} strokeLinejoin="round" />
            {pts.map((p, i) => (
              <circle key={i} cx={p[0]} cy={p[1]} r={2.6} fill={s.color} />
            ))}
          </g>
        )
      })}
      {/* Axis labels */}
      {showLabels &&
        axes.map((label, i) => {
          const lr = r + 18
          const x = cx + lr * Math.cos(angle(i))
          const y = cy + lr * Math.sin(angle(i))
          const anchor = Math.abs(Math.cos(angle(i))) < 0.3 ? 'middle' : x > cx ? 'start' : 'end'
          return (
            <text
              key={i}
              x={x}
              y={y}
              fontSize={11}
              fill="#9fb0c8"
              textAnchor={anchor}
              dominantBaseline="middle"
              style={{ textTransform: 'capitalize' }}
            >
              {label}
            </text>
          )
        })}
    </svg>
  )
}
