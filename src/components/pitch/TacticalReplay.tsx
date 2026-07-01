import { useEffect, useMemo, useRef, useState } from 'react'
import type { Match, Player } from '../../types'
import { Pitch, pitchX, pitchY } from './Pitch'
import { positionGroupOf } from '../../data/formations'
import { POSITION_GROUP_COLOR, clamp } from '../../utils/format'
import { fmtClock } from '../../utils/video'

// ---------------------------------------------------------------------------
// A 2D bird's-eye "tactical view" of a match, in the spirit of broadcast
// tactical overlays. Player positions are reconstructed from the data we have —
// kickoff formation shape, each player's average position, and goal locations —
// and interpolated across the 90 minutes with a little life. It is an
// approximation (there is no per-second tracking feed), but it lets you watch
// team shape, space and individual movement, and scrub to key moments.
// ---------------------------------------------------------------------------

interface Node {
  playerId: string
  number: number
  surname: string
  group: 'GK' | 'DEF' | 'MID' | 'FWD'
  kx: number; ky: number     // kickoff position
  ax: number; ay: number     // average position
  phase: number
}
interface GoalMark { minute: number; playerId: string; x: number; y: number }

const lerp = (a: number, b: number, t: number) => a + (b - a) * t

function convexHull(pts: { x: number; y: number }[]): { x: number; y: number }[] {
  if (pts.length < 3) return pts
  const p = [...pts].sort((a, b) => a.x - b.x || a.y - b.y)
  const cross = (o: typeof p[0], a: typeof p[0], b: typeof p[0]) =>
    (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
  const lower: typeof p = []
  for (const q of p) {
    while (lower.length >= 2 && cross(lower[lower.length - 2], lower[lower.length - 1], q) <= 0) lower.pop()
    lower.push(q)
  }
  const upper: typeof p = []
  for (let i = p.length - 1; i >= 0; i--) {
    const q = p[i]
    while (upper.length >= 2 && cross(upper[upper.length - 2], upper[upper.length - 1], q) <= 0) upper.pop()
    upper.push(q)
  }
  lower.pop(); upper.pop()
  return lower.concat(upper)
}

export function TacticalReplay({ match, players }: { match: Match; players: Player[] }) {
  const byId = useMemo(() => new Map(players.map((p) => [p.id, p])), [players])

  const nodes = useMemo<Node[]>(() => {
    return match.lineup.map((slot, i) => {
      const p = byId.get(slot.playerId)
      const stat = match.playerStats.find((s) => s.playerId === slot.playerId)
      return {
        playerId: slot.playerId,
        number: p?.number ?? 0,
        surname: (p?.name ?? '').split(' ').slice(-1)[0] || '?',
        group: positionGroupOf(slot.position),
        kx: slot.x, ky: slot.y,
        ax: stat?.avgX ?? slot.x, ay: stat?.avgY ?? slot.y,
        phase: i * 0.7,
      }
    })
  }, [match, byId])

  const goals = useMemo<GoalMark[]>(
    () => match.events.filter((e) => e.type === 'goal' && e.x != null && e.y != null)
      .map((e) => ({ minute: e.minute, playerId: e.playerId, x: e.x!, y: e.y! })),
    [match.events],
  )
  const markers = useMemo(
    () => [...match.events].filter((e) => e.type === 'goal' || e.type === 'red' || e.type === 'yellow')
      .sort((a, b) => a.minute - b.minute),
    [match.events],
  )

  const [minute, setMinute] = useState(0)
  const [playing, setPlaying] = useState(false)
  const [speed, setSpeed] = useState(1)
  const [selected, setSelected] = useState<string | null>(null)
  const [showShape, setShowShape] = useState(true)
  const [showTrails, setShowTrails] = useState(true)
  const raf = useRef<number | null>(null)
  const last = useRef<number>(0)

  // Playback loop (advance ~90' over ~30s at 1×).
  useEffect(() => {
    if (!playing) return
    let mounted = true
    const step = (ts: number) => {
      if (!mounted) return
      if (!last.current) last.current = ts
      const dt = (ts - last.current) / 1000
      last.current = ts
      setMinute((m) => {
        const next = m + dt * 3 * speed
        if (next >= 90) { setPlaying(false); return 90 }
        return next
      })
      raf.current = requestAnimationFrame(step)
    }
    raf.current = requestAnimationFrame(step)
    return () => { mounted = false; last.current = 0; if (raf.current) cancelAnimationFrame(raf.current) }
  }, [playing, speed])

  function posAt(n: Node, t: number): { x: number; y: number } {
    const settle = clamp(t / 12, 0, 1)
    let x = lerp(n.kx, n.ax, settle)
    let y = lerp(n.ky, n.ay, settle)
    // gentle idle motion so shape breathes
    x += Math.sin(t * 0.45 + n.phase) * 2.1
    y += Math.cos(t * 0.38 + n.phase * 1.3) * 2.1
    // pull the scorer to the goal spot around the goal minute
    for (const g of goals) {
      if (g.playerId === n.playerId) {
        const w = Math.max(0, 1 - Math.abs(t - g.minute) / 2.5)
        if (w > 0) { x = lerp(x, g.x, w); y = lerp(y, g.y, w) }
      }
    }
    return { x: clamp(x, 3, 97), y: clamp(y, 3, 97) }
  }

  const current = nodes.map((n) => ({ n, ...posAt(n, minute) }))
  const outfield = current.filter((c) => c.n.group !== 'GK')

  // Team shape metrics.
  const xs = outfield.map((c) => c.x)
  const ys = outfield.map((c) => c.y)
  const minX = Math.min(...xs), maxX = Math.max(...xs)
  const minY = Math.min(...ys), maxY = Math.max(...ys)
  const widthM = Math.round((maxX - minX) * 0.68)
  const depthM = Math.round((maxY - minY) * 1.05)
  const defYs = outfield.filter((c) => c.n.group === 'DEF').map((c) => c.y)
  const lineM = defYs.length ? Math.round((defYs.reduce((a, b) => a + b, 0) / defYs.length) * 1.05) : 0
  const hull = showShape ? convexHull(outfield.map((c) => ({ x: c.x, y: c.y }))) : []
  const centroid = { x: xs.reduce((a, b) => a + b, 0) / xs.length, y: ys.reduce((a, b) => a + b, 0) / ys.length }

  // Trail for the followed player.
  const trail = selected && showTrails
    ? Array.from({ length: 16 }, (_, i) => minute - (15 - i) * 0.8)
        .filter((t) => t >= 0)
        .map((t) => { const n = nodes.find((x) => x.playerId === selected)!; return posAt(n, t) })
    : []

  if (match.lineup.length === 0) {
    return <p className="muted tiny">Tactical view needs a lineup and player position data for this match.</p>
  }

  return (
    <div>
      <div style={{ maxWidth: 480, margin: '0 auto' }}>
        <Pitch>
          {/* team block */}
          {hull.length >= 3 && (
            <polygon
              points={hull.map((p) => `${pitchX(p.x)},${pitchY(p.y)}`).join(' ')}
              fill="rgba(56,189,248,0.10)" stroke="rgba(56,189,248,0.5)" strokeWidth={0.5} strokeLinejoin="round"
            />
          )}
          {showShape && (
            <>
              <line x1={2} y1={pitchY(lineM / 1.05)} x2={98} y2={pitchY(lineM / 1.05)} stroke="rgba(255,255,255,0.35)" strokeWidth={0.4} strokeDasharray="2 2" />
              <circle cx={pitchX(centroid.x)} cy={pitchY(centroid.y)} r={1.6} fill="none" stroke="#fff" strokeWidth={0.5} />
            </>
          )}
          {/* trail */}
          {trail.length >= 2 && (
            <polyline points={trail.map((p) => `${pitchX(p.x)},${pitchY(p.y)}`).join(' ')} fill="none" stroke="#fbbf24" strokeWidth={0.8} strokeLinecap="round" strokeLinejoin="round" opacity={0.85} />
          )}
          {/* players */}
          {current.map(({ n, x, y }) => {
            const isSel = selected === n.playerId
            const color = POSITION_GROUP_COLOR[n.group]
            return (
              <g key={n.playerId} style={{ cursor: 'pointer' }} onClick={() => setSelected(isSel ? null : n.playerId)}>
                <circle cx={pitchX(x)} cy={pitchY(y)} r={isSel ? 3.4 : 2.9} fill={color} stroke={isSel ? '#fff' : '#0a0f1a'} strokeWidth={isSel ? 0.8 : 0.4} />
                <text x={pitchX(x)} y={pitchY(y)} fontSize={2.6} fontWeight={800} fill="#07121f" textAnchor="middle" dominantBaseline="central">{n.number}</text>
                {isSel && <text x={pitchX(x)} y={pitchY(y) - 4.2} fontSize={2.8} fontWeight={700} fill="#fff" textAnchor="middle" style={{ paintOrder: 'stroke' }} stroke="#0a0f1a" strokeWidth={0.6}>{n.surname}</text>}
              </g>
            )
          })}
        </Pitch>
      </div>

      {/* timeline */}
      <div style={{ position: 'relative', height: 14, background: 'var(--bg-2)', borderRadius: 999, margin: '14px 0 6px', cursor: 'pointer' }}
        onPointerDown={(e) => {
          const r = (e.currentTarget as HTMLElement).getBoundingClientRect()
          setMinute(clamp(((e.clientX - r.left) / r.width) * 90, 0, 90))
        }}>
        <div style={{ position: 'absolute', inset: 0, width: `${(minute / 90) * 100}%`, background: 'linear-gradient(90deg,var(--accent-strong),var(--accent))', borderRadius: 999 }} />
        {markers.map((m, i) => (
          <div key={i} title={`${m.minute}' ${m.type}`}
            onPointerDown={(e) => { e.stopPropagation(); setMinute(m.minute) }}
            style={{ position: 'absolute', top: -3, left: `${(m.minute / 90) * 100}%`, marginLeft: -2, width: 4, height: 20, borderRadius: 2, background: m.type === 'goal' ? '#22c55e' : m.type === 'red' ? '#ef4444' : '#eab308', boxShadow: '0 0 0 1px rgba(0,0,0,0.4)' }} />
        ))}
      </div>

      <div className="row between center wrap" style={{ gap: 10 }}>
        <div className="row gap-sm center">
          <button className="btn primary icon" style={{ width: 42 }} onClick={() => { if (minute >= 90) setMinute(0); setPlaying((p) => !p) }}>{playing ? '⏸' : '▶'}</button>
          <button className="btn icon" onClick={() => { setPlaying(false); setMinute(0) }} title="Restart">⏮</button>
          <span className="mono tiny muted" style={{ minWidth: 42 }}>{fmtClock(minute * 60)}</span>
          <select className="select" style={{ width: 74 }} value={speed} onChange={(e) => setSpeed(parseFloat(e.target.value))}>
            {[0.5, 1, 2, 4].map((s) => <option key={s} value={s}>{s}×</option>)}
          </select>
        </div>
        <div className="row gap-sm center">
          <span className={`chip tiny${showShape ? ' active' : ''}`} style={{ cursor: 'pointer' }} onClick={() => setShowShape((s) => !s)}>Shape</span>
          <span className={`chip tiny${showTrails ? ' active' : ''}`} style={{ cursor: 'pointer' }} onClick={() => setShowTrails((s) => !s)}>Trails</span>
          {selected && <span className="chip tiny" style={{ cursor: 'pointer' }} onClick={() => setSelected(null)}>✕ Following {nodes.find((n) => n.playerId === selected)?.surname}</span>}
        </div>
      </div>

      <div className="row gap-sm wrap" style={{ marginTop: 12 }}>
        <span className="badge tiny">Width {widthM}m</span>
        <span className="badge tiny">Depth {depthM}m</span>
        <span className="badge tiny">Def line {lineM}m</span>
        <span className="badge tiny">Block {Math.round((maxX - minX) * (maxY - minY) / 100 * 0.71)}·10²m²</span>
      </div>
      <p className="faint tiny" style={{ marginTop: 10 }}>
        2D tactical view — positions are reconstructed from recorded shape (kickoff + average positions) and goal locations; tap a player to follow their movement. Connect a tracking feed for exact per-second movement.
      </p>
    </div>
  )
}
