export interface LineSeries {
  name: string
  color: string
  values: number[]
  /** Render as dashed (e.g. expected vs actual). */
  dashed?: boolean
  /** Fill the area under the line. */
  area?: boolean
}

/**
 * Multi-series line chart with shared x labels. Lightweight, responsive SVG.
 */
export function LineChart({
  series,
  labels,
  height = 220,
  min,
  max,
  yTicks = 4,
  unit = '',
}: {
  series: LineSeries[]
  labels: string[]
  height?: number
  min?: number
  max?: number
  yTicks?: number
  unit?: string
}) {
  const W = 600
  const H = height
  const padL = 34
  const padR = 12
  const padT = 12
  const padB = 26
  const allVals = series.flatMap((s) => s.values)
  const lo = min ?? Math.min(0, ...allVals)
  const hi = max ?? Math.max(1, ...allVals) * 1.1
  const n = labels.length
  const xAt = (i: number) => padL + (n <= 1 ? 0 : (i / (n - 1)) * (W - padL - padR))
  const yAt = (v: number) => padT + (1 - (v - lo) / (hi - lo || 1)) * (H - padT - padB)

  const ticks = Array.from({ length: yTicks + 1 }, (_, i) => lo + ((hi - lo) * i) / yTicks)

  return (
    <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: 'auto', display: 'block' }}>
      {/* Gridlines + y labels */}
      {ticks.map((t, i) => (
        <g key={i}>
          <line x1={padL} y1={yAt(t)} x2={W - padR} y2={yAt(t)} stroke="rgba(255,255,255,0.06)" strokeWidth={1} />
          <text x={padL - 6} y={yAt(t)} fontSize={10} fill="#65779a" textAnchor="end" dominantBaseline="middle">
            {Math.round(t * 10) / 10}{unit}
          </text>
        </g>
      ))}
      {/* x labels (thinned to avoid overlap) */}
      {labels.map((lab, i) => {
        const step = Math.ceil(n / 8)
        if (i % step !== 0 && i !== n - 1) return null
        return (
          <text key={i} x={xAt(i)} y={H - 8} fontSize={10} fill="#65779a" textAnchor="middle">
            {lab.length > 8 ? lab.slice(0, 7) + '…' : lab}
          </text>
        )
      })}
      {/* Series */}
      {series.map((s, si) => {
        const pts = s.values.map((v, i) => [xAt(i), yAt(v)] as const)
        const path = pts.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p[0]} ${p[1]}`).join(' ')
        const areaPath =
          `${path} L ${xAt(s.values.length - 1)} ${yAt(lo)} L ${xAt(0)} ${yAt(lo)} Z`
        return (
          <g key={si}>
            {s.area && <path d={areaPath} fill={s.color} fillOpacity={0.12} />}
            <path d={path} fill="none" stroke={s.color} strokeWidth={2.2} strokeDasharray={s.dashed ? '5 4' : undefined} strokeLinejoin="round" strokeLinecap="round" />
            {pts.map((p, i) => (
              <circle key={i} cx={p[0]} cy={p[1]} r={2.4} fill={s.color} />
            ))}
          </g>
        )
      })}
    </svg>
  )
}
