export interface BarDatum {
  label: string
  value: number
  color?: string
  /** Optional secondary (background/comparison) value, e.g. xG vs goals. */
  value2?: number
}

/** Horizontal bar chart — good for leaderboards and stat comparisons. */
export function BarChart({
  data,
  max,
  unit = '',
  accent = '#38bdf8',
  height = 26,
  showValue = true,
  digits = 0,
}: {
  data: BarDatum[]
  max?: number
  unit?: string
  accent?: string
  height?: number
  showValue?: boolean
  digits?: number
}) {
  const top = max ?? Math.max(1, ...data.map((d) => Math.max(d.value, d.value2 ?? 0)))
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      {data.map((d, i) => (
        <div key={i} style={{ display: 'grid', gridTemplateColumns: '110px 1fr', gap: 12, alignItems: 'center' }}>
          <div style={{ fontSize: 13, color: 'var(--text-dim)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
            {d.label}
          </div>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <div style={{ flex: 1, position: 'relative', height, background: 'var(--bg-2)', borderRadius: 8, overflow: 'hidden' }}>
              {d.value2 !== undefined && (
                <div
                  style={{
                    position: 'absolute', inset: 0, width: `${(d.value2 / top) * 100}%`,
                    background: 'rgba(255,255,255,0.08)', borderRadius: 8,
                  }}
                />
              )}
              <div
                style={{
                  position: 'absolute', top: 0, bottom: 0, left: 0,
                  width: `${(d.value / top) * 100}%`,
                  background: `linear-gradient(90deg, ${d.color ?? accent}, ${d.color ?? accent}cc)`,
                  borderRadius: 8, transition: 'width 0.4s ease',
                }}
              />
            </div>
            {showValue && (
              <div style={{ width: 52, textAlign: 'right', fontVariantNumeric: 'tabular-nums', fontWeight: 700, fontSize: 13.5 }}>
                {d.value.toFixed(digits)}
                {unit && <span style={{ color: 'var(--text-faint)', fontSize: 11, marginLeft: 2 }}>{unit}</span>}
              </div>
            )}
          </div>
        </div>
      ))}
    </div>
  )
}
