import { useRef, useState } from 'react'
import { PitchMarkings, pitchX, pitchY, slotStyle, PITCH_W, PITCH_H } from './Pitch'
import type { RoutineToken, RoutineArrow, ArrowKind, TokenKind } from '../../types'

const TOKEN_STYLE: Record<TokenKind, { bg: string; border: string; fg: string }> = {
  player: { bg: 'linear-gradient(135deg,#38bdf8,#0ea5e9)', border: '#bae6fd', fg: '#04121d' },
  opponent: { bg: 'linear-gradient(135deg,#475569,#334155)', border: '#ef4444', fg: '#fff' },
  ball: { bg: '#ffffff', border: '#0a0f1a', fg: '#0a0f1a' },
  cone: { bg: '#f97316', border: '#fdba74', fg: '#fff' },
}

const dashFor = (kind: ArrowKind) => (kind === 'pass' ? '3 2.4' : kind === 'dribble' ? '0.6 2.4' : undefined)

interface DragRef { id: string; moved: boolean }

export function RoutineBoard({
  tokens,
  arrows,
  mode,
  arrowKind,
  arrowColor,
  selectedId,
  onTokensChange,
  onArrowsChange,
  onSelect,
}: {
  tokens: RoutineToken[]
  arrows: RoutineArrow[]
  mode: 'move' | 'draw'
  arrowKind: ArrowKind
  arrowColor: string
  selectedId: string | null
  onTokensChange: (t: RoutineToken[]) => void
  onArrowsChange: (a: RoutineArrow[]) => void
  onSelect: (id: string | null) => void
}) {
  const fieldRef = useRef<HTMLDivElement>(null)
  const drag = useRef<DragRef | null>(null)
  const [wip, setWip] = useState<RoutineArrow | null>(null)
  const drawing = useRef(false)

  function toPitch(clientX: number, clientY: number) {
    const r = fieldRef.current!.getBoundingClientRect()
    return {
      x: Math.min(98, Math.max(2, ((clientX - r.left) / r.width) * 100)),
      y: Math.min(98, Math.max(2, (1 - (clientY - r.top) / r.height) * 100)),
    }
  }

  // ---- token dragging ----------------------------------------------------
  function onTokenDown(e: React.PointerEvent, id: string) {
    if (mode !== 'move') return
    e.preventDefault()
    ;(e.target as HTMLElement).setPointerCapture?.(e.pointerId)
    drag.current = { id, moved: false }
    window.addEventListener('pointermove', onTokenMove)
    window.addEventListener('pointerup', onTokenUp)
  }
  function onTokenMove(e: PointerEvent) {
    const d = drag.current
    if (!d) return
    d.moved = true
    const p = toPitch(e.clientX, e.clientY)
    onTokensChange(tokens.map((t) => (t.id === d.id ? { ...t, x: Math.round(p.x), y: Math.round(p.y) } : t)))
  }
  function onTokenUp() {
    const d = drag.current
    window.removeEventListener('pointermove', onTokenMove)
    window.removeEventListener('pointerup', onTokenUp)
    if (d && !d.moved) onSelect(d.id === selectedId ? null : d.id)
    drag.current = null
  }

  // ---- arrow drawing -----------------------------------------------------
  function onDrawDown(e: React.PointerEvent) {
    if (mode !== 'draw') return
    e.preventDefault()
    ;(e.target as HTMLElement).setPointerCapture?.(e.pointerId)
    drawing.current = true
    const p = toPitch(e.clientX, e.clientY)
    setWip({ id: 'wip', kind: arrowKind, color: arrowColor, points: [p] })
  }
  function onDrawMove(e: React.PointerEvent) {
    if (mode !== 'draw' || !drawing.current) return
    const p = toPitch(e.clientX, e.clientY)
    setWip((prev) => (prev ? { ...prev, points: [...prev.points, p] } : prev))
  }
  function onDrawUp() {
    if (!drawing.current) return
    drawing.current = false
    if (wip && wip.points.length > 2) {
      onArrowsChange([...arrows, { ...wip, id: `ar_${Date.now().toString(36)}_${arrows.length}` }])
    }
    setWip(null)
  }

  const allArrows = wip ? [...arrows, wip] : arrows

  return (
    <div
      ref={fieldRef}
      style={{ position: 'relative', aspectRatio: `${PITCH_W} / ${PITCH_H}`, width: '100%', userSelect: 'none', touchAction: 'none' }}
    >
      {/* Pitch */}
      <svg viewBox={`0 0 ${PITCH_W} ${PITCH_H}`} preserveAspectRatio="none" style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', borderRadius: 12 }}>
        <PitchMarkings />
      </svg>

      {/* Arrow layer */}
      <svg
        viewBox={`0 0 ${PITCH_W} ${PITCH_H}`}
        preserveAspectRatio="none"
        onPointerDown={onDrawDown}
        onPointerMove={onDrawMove}
        onPointerUp={onDrawUp}
        style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', cursor: mode === 'draw' ? 'crosshair' : 'default', pointerEvents: mode === 'draw' ? 'auto' : 'none' }}
      >
        <defs>
          {allArrows.map((a) => (
            <marker key={a.id} id={`rb-arrow-${a.id}`} markerWidth="6" markerHeight="6" refX="4.5" refY="2" orient="auto" markerUnits="strokeWidth">
              <path d="M0,0 L4.5,2 L0,4 Z" fill={a.color} />
            </marker>
          ))}
        </defs>
        {allArrows.map((a) => {
          const d = a.points.map((p, i) => `${i === 0 ? 'M' : 'L'} ${pitchX(p.x).toFixed(1)} ${pitchY(p.y).toFixed(1)}`).join(' ')
          return (
            <path
              key={a.id}
              d={d}
              fill="none"
              stroke={a.color}
              strokeWidth={0.9}
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeDasharray={dashFor(a.kind)}
              markerEnd={`url(#rb-arrow-${a.id})`}
            />
          )
        })}
      </svg>

      {/* Tokens */}
      <div style={{ position: 'absolute', inset: 0, pointerEvents: mode === 'move' ? 'auto' : 'none' }}>
        {tokens.map((t) => {
          const st = TOKEN_STYLE[t.kind]
          const isSel = selectedId === t.id
          const isBall = t.kind === 'ball'
          const isCone = t.kind === 'cone'
          if (isCone) {
            return (
              <div key={t.id} style={slotStyle(t.x, t.y)}>
                <div
                  onPointerDown={(e) => onTokenDown(e, t.id)}
                  title="cone"
                  style={{
                    fontSize: 20, lineHeight: 1, cursor: mode === 'move' ? 'grab' : 'default',
                    filter: isSel ? 'drop-shadow(0 0 3px #fff)' : 'drop-shadow(0 1px 2px rgba(0,0,0,0.6))',
                  }}
                >
                  🔶
                </div>
              </div>
            )
          }
          return (
            <div key={t.id} style={slotStyle(t.x, t.y)}>
              <div
                onPointerDown={(e) => onTokenDown(e, t.id)}
                title={t.kind}
                style={{
                  width: isBall ? 18 : 30,
                  height: isBall ? 18 : 30,
                  borderRadius: '50%',
                  display: 'grid', placeItems: 'center',
                  cursor: mode === 'move' ? 'grab' : 'default',
                  background: st.bg,
                  border: `2px solid ${isSel ? '#fff' : st.border}`,
                  boxShadow: isSel ? '0 0 0 3px rgba(56,189,248,0.5)' : '0 2px 6px rgba(0,0,0,0.45)',
                  color: st.fg, fontWeight: 800, fontSize: 12,
                }}
              >
                {isBall ? '⚽' : t.label}
              </div>
            </div>
          )
        })}
      </div>
    </div>
  )
}
