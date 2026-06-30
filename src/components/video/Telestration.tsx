import { useEffect, useRef, useState } from 'react'
import type { DrawShape, DrawTool } from '../../types'

interface Pt { x: number; y: number }

/**
 * Telestration overlay rendered over a video frame. Coordinates are stored
 * normalised (0..1) so a drawing reproduces correctly at any size. When
 * `editable`, pointer input draws with the active tool/colour; otherwise the
 * shapes are shown read-only and pointer events pass through to the video.
 */
export function Telestration({
  shapes,
  editable,
  tool = 'arrow',
  color = '#fbbf24',
  onChange,
}: {
  shapes: DrawShape[]
  editable: boolean
  tool?: DrawTool
  color?: string
  onChange?: (shapes: DrawShape[]) => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState({ w: 16, h: 9 })
  const [wip, setWip] = useState<DrawShape | null>(null)
  const drawing = useRef(false)

  useEffect(() => {
    const el = ref.current
    if (!el) return
    const ro = new ResizeObserver(() => setSize({ w: el.clientWidth || 16, h: el.clientHeight || 9 }))
    ro.observe(el)
    setSize({ w: el.clientWidth || 16, h: el.clientHeight || 9 })
    return () => ro.disconnect()
  }, [])

  function toNorm(e: React.PointerEvent): Pt {
    const rect = ref.current!.getBoundingClientRect()
    return {
      x: Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width)),
      y: Math.min(1, Math.max(0, (e.clientY - rect.top) / rect.height)),
    }
  }

  function onDown(e: React.PointerEvent) {
    if (!editable) return
    e.preventDefault()
    ;(e.target as HTMLElement).setPointerCapture?.(e.pointerId)
    drawing.current = true
    const p = toNorm(e)
    setWip({ tool, color, points: tool === 'pen' ? [p] : [p, p] })
  }
  function onMove(e: React.PointerEvent) {
    if (!editable || !drawing.current || !wip) return
    const p = toNorm(e)
    setWip((prev) => {
      if (!prev) return prev
      if (prev.tool === 'pen') return { ...prev, points: [...prev.points, p] }
      return { ...prev, points: [prev.points[0], p] }
    })
  }
  function onUp() {
    if (!editable || !wip) { drawing.current = false; return }
    drawing.current = false
    const len = wip.points.length
    const valid =
      wip.tool === 'pen' ? len > 2 : Math.hypot(wip.points[1].x - wip.points[0].x, wip.points[1].y - wip.points[0].y) > 0.01
    if (valid) onChange?.([...shapes, wip])
    setWip(null)
  }

  const all = wip ? [...shapes, wip] : shapes
  const { w, h } = size

  return (
    <div
      ref={ref}
      onPointerDown={onDown}
      onPointerMove={onMove}
      onPointerUp={onUp}
      style={{
        position: 'absolute', inset: 0, zIndex: 5,
        cursor: editable ? 'crosshair' : 'default',
        pointerEvents: editable ? 'auto' : 'none',
        touchAction: 'none',
      }}
    >
      <svg viewBox={`0 0 ${w} ${h}`} width="100%" height="100%" style={{ display: 'block' }}>
        <defs>
          {all.map((s, i) => (
            <marker key={i} id={`arrow-${i}`} markerWidth="10" markerHeight="10" refX="7" refY="3" orient="auto" markerUnits="strokeWidth">
              <path d="M0,0 L7,3 L0,6 Z" fill={s.color} />
            </marker>
          ))}
        </defs>
        {all.map((s, i) => renderShape(s, i, w, h))}
      </svg>
    </div>
  )
}

function renderShape(s: DrawShape, i: number, w: number, h: number) {
  const px = (p: Pt) => ({ x: p.x * w, y: p.y * h })
  const sw = Math.max(2, w * 0.004)
  const common = { stroke: s.color, strokeWidth: sw, fill: 'none', strokeLinecap: 'round' as const, strokeLinejoin: 'round' as const }
  if (s.tool === 'pen') {
    const d = s.points.map((p, j) => { const q = px(p); return `${j === 0 ? 'M' : 'L'} ${q.x.toFixed(1)} ${q.y.toFixed(1)}` }).join(' ')
    return <path key={i} d={d} {...common} />
  }
  const a = px(s.points[0])
  const b = px(s.points[1])
  if (s.tool === 'line') return <line key={i} x1={a.x} y1={a.y} x2={b.x} y2={b.y} {...common} />
  if (s.tool === 'arrow') return <line key={i} x1={a.x} y1={a.y} x2={b.x} y2={b.y} {...common} markerEnd={`url(#arrow-${i})`} />
  if (s.tool === 'rect') {
    return <rect key={i} x={Math.min(a.x, b.x)} y={Math.min(a.y, b.y)} width={Math.abs(b.x - a.x)} height={Math.abs(b.y - a.y)} {...common} />
  }
  // ellipse
  return <ellipse key={i} cx={(a.x + b.x) / 2} cy={(a.y + b.y) / 2} rx={Math.abs(b.x - a.x) / 2} ry={Math.abs(b.y - a.y) / 2} {...common} />
}
