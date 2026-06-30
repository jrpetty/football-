import { Pitch, pitchX, pitchY } from './Pitch'

export interface Shot {
  x: number
  y: number
  goal: boolean
  onTarget: boolean
  label?: string
}

/**
 * Shot map over the attacking half of a vertical pitch.
 * Goals = filled accent dots, on-target = outlined, off-target = faint cross.
 */
export function ShotMap({ shots }: { shots: Shot[] }) {
  return (
    <Pitch stripes>
      {shots.map((s, i) => {
        const cx = pitchX(s.x)
        const cy = pitchY(s.y)
        if (s.goal) {
          return (
            <g key={i}>
              <circle cx={cx} cy={cy} r={2.6} fill="#22c55e" stroke="#0a0f1a" strokeWidth={0.4} />
              <circle cx={cx} cy={cy} r={4.2} fill="none" stroke="#22c55e" strokeWidth={0.5} opacity={0.6} />
            </g>
          )
        }
        if (s.onTarget) {
          return <circle key={i} cx={cx} cy={cy} r={2.1} fill="rgba(56,189,248,0.25)" stroke="#38bdf8" strokeWidth={0.6} />
        }
        return (
          <g key={i} stroke="rgba(239,68,68,0.7)" strokeWidth={0.7}>
            <line x1={cx - 1.6} y1={cy - 1.6} x2={cx + 1.6} y2={cy + 1.6} />
            <line x1={cx - 1.6} y1={cy + 1.6} x2={cx + 1.6} y2={cy - 1.6} />
          </g>
        )
      })}
    </Pitch>
  )
}
