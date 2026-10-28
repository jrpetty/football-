// Align the radar (football): one still of the capture with the box where the
// coach reads the radar. Drag the box onto the radar and its corner to fit;
// what the coach reads inside it is drawn on top as it moves (the pitch
// outline, each player and the ball), so a good fit is plain to see. Shown in
// this app's window only; the still is discarded on close.

import { useEffect, useMemo, useRef, useState } from 'react'
import { readRadar, type RadarColour } from '../../core/pitch/radar.ts'
import type { MinimapBox } from '../../core/settings.ts'
import type { CoachSession } from '../session.ts'
import { Modal } from './common.tsx'
import { TEAM } from './PitchView.tsx'

export function RadarAlign(props: {
  session: CoachSession
  initial: MinimapBox
  packDefault: MinimapBox
  colours: { ours: RadarColour | null; theirs: RadarColour | null }
  onSave: (box: MinimapBox | null) => void
  onClose: () => void
}) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const pixels = useRef<ImageData | null>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'unavailable'>('loading')
  const [box, setBox] = useState<MinimapBox>(props.initial)
  const [dims, setDims] = useState({ w: 960, h: 540 })
  const drag = useRef<{ mode: 'move' | 'size'; x: number; y: number; box: MinimapBox } | null>(null)
  // A new still on request: the radar is hidden for replays, celebrations and set pieces.
  const [take, setTake] = useState(0)

  useEffect(() => {
    let cancelled = false
    setState('loading')
    void props.session.still().then((bitmap) => {
      const c = canvas.current
      if (cancelled || !bitmap || !c) {
        bitmap?.close()
        if (!cancelled) setState('unavailable')
        return
      }
      const scale = Math.min(1, 1100 / bitmap.width)
      c.width = Math.round(bitmap.width * scale)
      c.height = Math.round(bitmap.height * scale)
      const ctx = c.getContext('2d', { willReadFrequently: true })!
      ctx.drawImage(bitmap, 0, 0, c.width, c.height)
      bitmap.close()
      pixels.current = ctx.getImageData(0, 0, c.width, c.height)
      setDims({ w: c.width, h: c.height })
      setState('ready')
    })
    return () => {
      cancelled = true
      pixels.current = null
      const c = canvas.current
      if (c) c.width = 0
    }
  }, [props.session, take])

  // What the coach reads in the box right now.
  const reading = useMemo(() => {
    const img = pixels.current
    if (!img || state !== 'ready') return null
    return readRadar({ width: img.width, height: img.height, data: img.data }, box, { ours: props.colours.ours, theirs: props.colours.theirs })
  }, [box, state, props.colours.ours, props.colours.theirs])

  const onDown = (mode: 'move' | 'size') => (e: React.PointerEvent) => {
    e.stopPropagation()
    ;(e.target as Element).setPointerCapture(e.pointerId)
    drag.current = { mode, x: e.clientX, y: e.clientY, box }
  }
  const onMove = (e: React.PointerEvent) => {
    const d = drag.current
    if (!d) return
    const dx = (e.clientX - d.x) / dims.w
    const dy = (e.clientY - d.y) / dims.h
    const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v))
    if (d.mode === 'move') setBox({ ...d.box, x: clamp(d.box.x + dx, 0, 1 - d.box.w), y: clamp(d.box.y + dy, 0, 1 - d.box.h) })
    else setBox({ ...d.box, w: clamp(d.box.w + dx, 0.06, 1 - d.box.x), h: clamp(d.box.h + dy, 0.04, 1 - d.box.y) })
  }
  const onUp = () => {
    drag.current = null
  }

  const px = (bx: number, by: number) => ({ left: (box.x + bx * box.w) * dims.w, top: (box.y + by * box.h) * dims.h })
  const r = reading
  const teams = r ? [r.dots.filter((d) => d.team === 'ours' || d.team === 'A').length, r.dots.filter((d) => d.team === 'theirs' || d.team === 'B').length] : [0, 0]
  return (
    <Modal title="Align the radar" onClose={props.onClose}>
      <div>
        <h1>Align the radar</h1>
        <p className="lede">
          Drag the box onto the radar at the bottom of the screen and its corner so it holds the whole pitch. What the coach reads is drawn on top: the pitch outline and each
          player. In the game, use the 2D radar (Game Settings › Visual › Radar).
        </p>
      </div>
      <div className="align-stage" style={{ width: dims.w, height: dims.h }} onPointerMove={onMove} onPointerUp={onUp}>
        <canvas ref={canvas} />
        {state === 'ready' ? (
          <>
            {r?.pitch.outline ? (
              <div
                className="radar-outline"
                style={{
                  ...px(r.pitch.x0, r.pitch.y0),
                  width: (r.pitch.x1 - r.pitch.x0) * box.w * dims.w,
                  height: (r.pitch.y1 - r.pitch.y0) * box.h * dims.h,
                }}
                aria-hidden="true"
              />
            ) : null}
            {r
              ? r.dots.map((d, i) => {
                  const p = px(r.pitch.x0 + (d.x / 105) * (r.pitch.x1 - r.pitch.x0), r.pitch.y0 + (d.y / 68) * (r.pitch.y1 - r.pitch.y0))
                  const ours = d.team === 'ours' || d.team === 'A'
                  return <span key={i} className={`radar-dot ${ours ? 'ours' : 'theirs'}`} style={{ ...p, background: ours ? TEAM.ours : TEAM.theirs }} aria-hidden="true" />
                })
              : null}
            <div
              className={`align-box${r?.ok ? ' ok' : ''}`}
              style={{ left: box.x * dims.w, top: box.y * dims.h, width: box.w * dims.w, height: box.h * dims.h }}
              onPointerDown={onDown('move')}
              role="slider"
              aria-label="Radar position"
              aria-valuetext={`x ${Math.round(box.x * 100)}%, y ${Math.round(box.y * 100)}%`}
              tabIndex={0}
              onKeyDown={(e) => {
                const step = e.shiftKey ? 0.01 : 0.002
                const k = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] }[e.key]
                if (!k) return
                e.preventDefault()
                setBox((b) => ({ ...b, x: Math.max(0, Math.min(1 - b.w, b.x + k[0])), y: Math.max(0, Math.min(1 - b.h, b.y + k[1])) }))
              }}
            >
              <span className="align-handle" onPointerDown={onDown('size')} aria-hidden="true" />
            </div>
          </>
        ) : null}
      </div>
      {state === 'unavailable' ? <p className="muted small">Start capture (or the demo) first: the box is aligned on a still of what is being captured.</p> : null}
      {r ? (
        <p className={`small ${r.ok ? '' : 'warn-text'}`}>
          {r.ok
            ? `✓ Radar read: the pitch outline, ${teams[0]} and ${teams[1]} players${r.ball ? ' and the ball' : ''}.`
            : !r.pitch.outline
              ? '⚠ No pitch outline in the box. Fit the box round the whole radar (or wait for open play: replays and menus hide it).'
              : `⚠ Outline found, but only ${r.dots.length} players read. Check the radar colours on the Coach screen match the game’s, and that the radar is 2D.`}
        </p>
      ) : null}
      <div className="btn-row" style={{ marginTop: 12 }}>
        <button type="button" className="btn ghost" onClick={() => setTake((n) => n + 1)} disabled={state === 'loading'}>
          New still
        </button>
        <div style={{ flex: 1 }} />
        <button type="button" className="btn ghost" onClick={() => setBox(props.packDefault)}>
          Game default
        </button>
        <button type="button" className="btn ghost" onClick={props.onClose}>
          Cancel
        </button>
        <button
          type="button"
          className="btn primary"
          disabled={state !== 'ready'}
          onClick={() => {
            const same = ['x', 'y', 'w', 'h'].every((k) => Math.abs(box[k as 'x'] - props.packDefault[k as 'x']) < 0.0005)
            props.onSave(same ? null : box)
            props.onClose()
          }}
        >
          Save
        </button>
      </div>
    </Modal>
  )
}
