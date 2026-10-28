// Align the minimap: one still of the capture with the box where the coach
// reads the minimap. Drag the box onto the minimap, drag its corner to fit,
// and check that the player's arrow is found at its centre. Shown in this
// app's window only; the still is discarded on close.

import { useEffect, useRef, useState } from 'react'
import { readWindow, MIN_MARKER } from '../../core/map/minimap.ts'
import type { MinimapBox } from '../../core/settings.ts'
import type { CoachSession } from '../session.ts'
import { Modal, Switch } from './common.tsx'

export function MinimapAlign(props: { session: CoachSession; initial: MinimapBox; packDefault: MinimapBox; onSave: (box: MinimapBox | null) => void; onClose: () => void }) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const pixels = useRef<ImageData | null>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'unavailable'>('loading')
  const [box, setBox] = useState<MinimapBox>(props.initial)
  const [dims, setDims] = useState({ w: 960, h: 540 })
  const drag = useRef<{ mode: 'move' | 'size'; x: number; y: number; box: MinimapBox } | null>(null)

  useEffect(() => {
    let cancelled = false
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
  }, [props.session])

  // Does the box hold a minimap with the arrow at its centre?
  const check = (() => {
    const img = pixels.current
    if (!img) return null
    const w = readWindow({ width: img.width, height: img.height, data: img.data, timestamp: 0 }, box)
    return { arrow: w.marker >= MIN_MARKER, contrast: w.contrast }
  })()

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
    else {
      // Keep it square on screen.
      const side = clamp(d.box.w + dx, 0.04, 1 - d.box.x)
      const h = (side * dims.w) / dims.h
      setBox({ ...d.box, w: side, h: clamp(h, 0.04, 1 - d.box.y) })
    }
  }
  const onUp = () => {
    drag.current = null
  }

  return (
    <Modal title="Align the minimap" onClose={props.onClose}>
      <div>
        <h1>Align the minimap</h1>
        <p className="lede">
          Drag the box onto your minimap and its corner to fit it, so your arrow is in the middle. Needed only if your HUD is moved or scaled (HUD bounds, minimap size).
        </p>
      </div>
      <div className="align-stage" style={{ width: dims.w, height: dims.h }} onPointerMove={onMove} onPointerUp={onUp}>
        <canvas ref={canvas} />
        {state === 'ready' ? (
          <div
            className={`align-box${box.round ? ' round' : ''}${check?.arrow ? ' ok' : ''}`}
            style={{ left: box.x * dims.w, top: box.y * dims.h, width: box.w * dims.w, height: box.h * dims.h }}
            onPointerDown={onDown('move')}
            role="slider"
            aria-label="Minimap position"
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
            <span className="align-centre" aria-hidden="true" />
            <span className="align-handle" onPointerDown={onDown('size')} aria-hidden="true" />
          </div>
        ) : null}
      </div>
      {state === 'unavailable' ? <p className="muted small">Start capture (or a demo) first: the box is aligned on a still of what is being captured.</p> : null}
      {check ? (
        <p className={`small ${check.arrow ? '' : 'warn-text'}`}>
          {check.arrow ? '✓ Your arrow is at the centre: the minimap can be followed.' : '⚠ No arrow found at the centre of the box. Move it so the arrow is in the middle (or wait until you are in a match).'}
        </p>
      ) : null}
      <div className="btn-row" style={{ marginTop: 12 }}>
        <Switch checked={!!box.round} onChange={(round) => setBox({ ...box, round })} label="Round minimap" />
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
            const same = ['x', 'y', 'w', 'h'].every((k) => Math.abs(box[k as 'x'] - props.packDefault[k as 'x']) < 0.0005) && !!box.round === !!props.packDefault.round
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
