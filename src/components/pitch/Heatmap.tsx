import { Pitch, pitchX, pitchY, PITCH_W, PITCH_H } from './Pitch'

interface Point { x: number; y: number }

// Heat colour ramp from cool (low) to hot (high).
function heatColor(t: number): string {
  // t in 0..1
  const stops: [number, [number, number, number]][] = [
    [0.0, [14, 165, 233]],   // sky
    [0.4, [34, 197, 94]],    // green
    [0.7, [234, 179, 8]],    // amber
    [1.0, [239, 68, 68]],    // red
  ]
  for (let i = 0; i < stops.length - 1; i++) {
    const [t0, c0] = stops[i]
    const [t1, c1] = stops[i + 1]
    if (t <= t1) {
      const k = (t - t0) / (t1 - t0)
      const r = Math.round(c0[0] + (c1[0] - c0[0]) * k)
      const g = Math.round(c0[1] + (c1[1] - c0[1]) * k)
      const b = Math.round(c0[2] + (c1[2] - c0[2]) * k)
      return `rgb(${r},${g},${b})`
    }
  }
  return 'rgb(239,68,68)'
}

/**
 * Density heatmap of touch/position samples over a vertical pitch.
 * Points are binned into a grid and rendered as blurred cells.
 */
export function Heatmap({
  points,
  cols = 12,
  rows = 18,
}: {
  points: Point[]
  cols?: number
  rows?: number
}) {
  const grid = new Array(cols * rows).fill(0)
  for (const p of points) {
    const cx = Math.min(cols - 1, Math.max(0, Math.floor((p.x / 100) * cols)))
    const cy = Math.min(rows - 1, Math.max(0, Math.floor((p.y / 100) * rows)))
    grid[cy * cols + cx]++
  }
  const max = Math.max(1, ...grid)
  const cellW = PITCH_W / cols
  const cellH = PITCH_H / rows

  return (
    <Pitch stripes={false}>
      <defs>
        <filter id="heatblur" x="-20%" y="-20%" width="140%" height="140%">
          <feGaussianBlur stdDeviation={3.2} />
        </filter>
      </defs>
      <g filter="url(#heatblur)" opacity={0.78}>
        {grid.map((count, i) => {
          if (count === 0) return null
          const col = i % cols
          const rowIdx = Math.floor(i / cols)
          const t = count / max
          const x = pitchX(((col + 0.5) / cols) * 100)
          const y = pitchY(((rowIdx + 0.5) / rows) * 100)
          return (
            <rect
              key={i}
              x={x - cellW * 0.7}
              y={y - cellH * 0.7}
              width={cellW * 1.4}
              height={cellH * 1.4}
              rx={cellW * 0.5}
              fill={heatColor(t)}
              opacity={0.25 + t * 0.6}
            />
          )
        })}
      </g>
    </Pitch>
  )
}
