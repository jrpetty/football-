// Scoreline heatmap.
//
// Sequential encoding — one hue, light to dark — because the value being shown
// is magnitude (how likely is this exact score), not identity. The most likely
// scoreline is labelled directly rather than left to the reader to hunt for.

import { useMemo, useState } from 'react'
import { scoreMatrix } from '../core/dixonColes.ts'
import { club } from '../config/teams.ts'

const RAMP = ['var(--seq-0)', 'var(--seq-1)', 'var(--seq-2)', 'var(--seq-3)', 'var(--seq-4)', 'var(--seq-5)']

function rampColor(p: number, max: number): string {
  if (max <= 0) return RAMP[0]!
  // Square root keeps the low-probability cells from all collapsing to one step.
  const t = Math.sqrt(p / max)
  const idx = Math.min(RAMP.length - 1, Math.floor(t * RAMP.length))
  return RAMP[idx]!
}

export function ScoreMatrix({
  lambdaHome,
  lambdaAway,
  rho,
  dispersion,
  homeCode,
  awayCode,
  maxGoals = 5,
}: {
  lambdaHome: number
  lambdaAway: number
  rho: number
  dispersion: number
  homeCode: string
  awayCode: string
  maxGoals?: number
}) {
  const [hover, setHover] = useState<{ h: number; a: number; p: number } | null>(null)

  const grid = useMemo(() => {
    const m = scoreMatrix(lambdaHome, lambdaAway, rho, dispersion)
    const out: number[][] = []
    for (let h = 0; h <= maxGoals; h++) {
      const row: number[] = []
      for (let a = 0; a <= maxGoals; a++) row.push(m.grid[h]![a]!)
      out.push(row)
    }
    return out
  }, [lambdaHome, lambdaAway, rho, dispersion, maxGoals])

  const max = Math.max(...grid.flat())
  const cell = 44
  const pad = 30
  const size = pad + (maxGoals + 1) * cell

  return (
    <div>
      <svg width="100%" viewBox={`0 0 ${size + 8} ${size + 8}`} role="img"
        aria-label="Grid of exact scoreline probabilities">
        {/* Axis labels */}
        <text x={pad + ((maxGoals + 1) * cell) / 2} y={12} textAnchor="middle"
          fill="var(--text-faint)" fontSize={11} fontWeight={700} letterSpacing="0.08em">
          {club(awayCode).short} GOALS
        </text>
        <text x={11} y={pad + ((maxGoals + 1) * cell) / 2} textAnchor="middle"
          fill="var(--text-faint)" fontSize={11} fontWeight={700} letterSpacing="0.08em"
          transform={`rotate(-90 11 ${pad + ((maxGoals + 1) * cell) / 2})`}>
          {club(homeCode).short} GOALS
        </text>

        {Array.from({ length: maxGoals + 1 }, (_, a) => (
          <text key={`ca-${a}`} x={pad + a * cell + cell / 2} y={pad - 6} textAnchor="middle"
            fill="var(--text-dim)" fontSize={12} fontWeight={600}>
            {a}
          </text>
        ))}
        {Array.from({ length: maxGoals + 1 }, (_, h) => (
          <text key={`ch-${h}`} x={pad - 8} y={pad + h * cell + cell / 2 + 4} textAnchor="end"
            fill="var(--text-dim)" fontSize={12} fontWeight={600}>
            {h}
          </text>
        ))}

        {grid.map((row, h) =>
          row.map((p, a) => {
            const isMax = p === max
            return (
              <g key={`${h}-${a}`}>
                <rect
                  className="matrix-cell"
                  x={pad + a * cell + 1}
                  y={pad + h * cell + 1}
                  width={cell - 2}
                  height={cell - 2}
                  rx={6}
                  fill={rampColor(p, max)}
                  stroke={isMax ? 'var(--accent)' : 'transparent'}
                  strokeWidth={isMax ? 2 : 0}
                  onMouseEnter={() => setHover({ h, a, p })}
                  onMouseLeave={() => setHover(null)}
                />
                {p >= max * 0.32 && (
                  <text
                    x={pad + a * cell + cell / 2}
                    y={pad + h * cell + cell / 2 + 4}
                    textAnchor="middle"
                    fill="#eaf2ff"
                    fontSize={11}
                    fontWeight={700}
                    pointerEvents="none"
                  >
                    {(p * 100).toFixed(0)}%
                  </text>
                )}
              </g>
            )
          }),
        )}
      </svg>
      <div className="faint" style={{ fontSize: 12, marginTop: 6, minHeight: 18 }}>
        {hover
          ? `${club(homeCode).short} ${hover.h}–${hover.a} ${club(awayCode).short} · ${(hover.p * 100).toFixed(1)}%`
          : 'Hover a cell for its exact probability. The outlined cell is the most likely score.'}
      </div>
    </div>
  )
}
