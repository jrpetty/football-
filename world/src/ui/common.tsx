import { useSyncExternalStore, type ReactNode } from 'react'
import { game } from '../game.ts'
import { mind } from '../ai/mind.ts'
import { DAY, HOUR, formatDate } from '../sim/time.ts'
import type { Agent } from '../sim/types.ts'

/** Re-render on the game's throttled heartbeat (~4×/s). */
export function useGameVersion(): number {
  return useSyncExternalStore(game.subscribe, game.getVersion)
}

let mindVersion = 0
const mindSubs = new Set<() => void>()
mind.listeners.add(() => {
  mindVersion++
  for (const s of mindSubs) s()
})
export function useMindVersion(): number {
  return useSyncExternalStore(
    (fn) => {
      mindSubs.add(fn)
      return () => mindSubs.delete(fn)
    },
    () => mindVersion,
  )
}

export function Bar({ value, color, max = 100 }: { value: number; color: string; max?: number }) {
  return (
    <div className="bar">
      <div style={{ width: `${Math.max(0, Math.min(100, (value / max) * 100))}%`, background: color }} />
    </div>
  )
}

export function Need({ label, value, invert = false }: { label: string; value: number; invert?: boolean }) {
  const good = invert ? 100 - value : value
  const color = good > 70 ? 'var(--good)' : good > 40 ? 'var(--warn)' : 'var(--danger)'
  return (
    <div className="need">
      <span>{label}</span>
      <Bar value={good} color={color} />
      <span className="v">{Math.round(good)}</span>
    </div>
  )
}

export function Section({ title, children, right }: { title: string; children: ReactNode; right?: ReactNode }) {
  return (
    <>
      <div className="section-title">
        {title}
        {right}
      </div>
      {children}
    </>
  )
}

export function Avatar({ a, size = 'md' }: { a: Agent; size?: 'md' | 'lg' }) {
  const t = game.world.tribeById(a.tribeId)
  const isMind = mind.isMind(a)
  return (
    <div className={`avatar ${size === 'lg' ? 'lg' : ''}`} style={{ background: t?.color ?? '#999', opacity: a.alive ? 1 : 0.45 }}>
      {a.name.charAt(0)}
      {isMind && <span className={`mind-dot ${a.aiPending ? 'thinking' : ''}`} title={a.aiPending ? 'Thinking…' : 'Claude mind'} />}
    </div>
  )
}

export function ago(t: number): string {
  const d = game.world.time - t
  if (d < HOUR) return 'just now'
  if (d < DAY) return `${Math.floor(d / HOUR)}h ago`
  if (d < DAY * 12) return `${Math.floor(d / DAY)}d ago`
  return formatDate(t)
}

export function shortDate(t: number): string {
  const y = Math.floor(t / (12 * DAY)) + 1
  const s = Math.floor(t / (3 * DAY)) % 4
  return `Y${y} ${['Spr', 'Sum', 'Aut', 'Win'][s]}`
}

/** Tiny multi-series SVG line chart. */
export function LineChart({ series, height = 120 }: { series: { label: string; color: string; values: number[] }[]; height?: number }) {
  const W = 340, H = height
  const n = Math.max(...series.map((s) => s.values.length), 2)
  const max = Math.max(1, ...series.flatMap((s) => s.values))
  return (
    <>
      <svg className="chart" viewBox={`0 0 ${W} ${H}`} preserveAspectRatio="none" style={{ height }}>
        {[0.25, 0.5, 0.75].map((g) => (
          <line key={g} x1={0} x2={W} y1={H * g} y2={H * g} stroke="rgba(255,255,255,0.06)" />
        ))}
        {series.map((s) => {
          const pts = s.values.map((v, i) => `${(i / (n - 1)) * W},${H - 4 - (v / max) * (H - 10)}`).join(' ')
          return <polyline key={s.label} points={pts} fill="none" stroke={s.color} strokeWidth={2} vectorEffect="non-scaling-stroke" strokeLinejoin="round" />
        })}
      </svg>
      <div className="legend">
        {series.map((s) => (
          <span key={s.label}>
            <i style={{ background: s.color }} />
            {s.label} <b style={{ color: 'var(--text)' }}>{Math.round(s.values[s.values.length - 1] ?? 0)}</b>
          </span>
        ))}
      </div>
    </>
  )
}

export function Toggle({ checked, onChange, label }: { checked: boolean; onChange: (v: boolean) => void; label: ReactNode }) {
  return (
    <label className="toggle">
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <span>{label}</span>
    </label>
  )
}
