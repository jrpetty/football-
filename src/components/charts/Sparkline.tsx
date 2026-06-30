/** Tiny inline trend line, e.g. a player's rating over recent matches. */
export function Sparkline({
  values,
  color = '#38bdf8',
  width = 120,
  height = 32,
  fill = true,
}: {
  values: number[]
  color?: string
  width?: number
  height?: number
  fill?: boolean
}) {
  if (values.length === 0) return <svg width={width} height={height} />
  const lo = Math.min(...values)
  const hi = Math.max(...values)
  const pad = 3
  const xAt = (i: number) => (values.length <= 1 ? width / 2 : pad + (i / (values.length - 1)) * (width - pad * 2))
  const yAt = (v: number) => pad + (1 - (v - lo) / (hi - lo || 1)) * (height - pad * 2)
  const pts = values.map((v, i) => [xAt(i), yAt(v)] as const)
  const path = pts.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p[0].toFixed(1)} ${p[1].toFixed(1)}`).join(' ')
  const areaPath = `${path} L ${xAt(values.length - 1)} ${height - pad} L ${xAt(0)} ${height - pad} Z`
  return (
    <svg width={width} height={height} viewBox={`0 0 ${width} ${height}`} style={{ display: 'block' }}>
      {fill && <path d={areaPath} fill={color} fillOpacity={0.14} />}
      <path d={path} fill="none" stroke={color} strokeWidth={1.8} strokeLinejoin="round" strokeLinecap="round" />
      <circle cx={xAt(values.length - 1)} cy={yAt(values[values.length - 1])} r={2.2} fill={color} />
    </svg>
  )
}
