// Mark the HUD's numbers: on a still of the capture (or a screenshot the
// player picks), drag a box around each number the coach can read on this
// computer (food, population, the clock…). Each box is checked as it is drawn:
// how many characters are found in it. Shown in this window only; the picture
// is discarded on close and never saved.

import { useEffect, useRef, useState } from 'react'
import type { HudNumberSpec } from '../../core/profile/schema.ts'
import type { MinimapBox } from '../../core/settings.ts'
import { segment } from '../../core/hud/glyphs.ts'
import type { CoachSession } from '../session.ts'
import { Modal } from './common.tsx'

type Boxes = Readonly<Record<string, MinimapBox>>

export function HudAlign(props: { session: CoachSession; specs: readonly HudNumberSpec[]; initial: Boxes; onSave: (boxes: Boxes) => void; onClose: () => void }) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const pixels = useRef<ImageData | null>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'none'>('loading')
  const [dims, setDims] = useState({ w: 960, h: 540 })
  const [boxes, setBoxes] = useState<Boxes>(props.initial)
  const [selected, setSelected] = useState(props.specs.find((s) => !props.initial[s.key])?.key ?? props.specs[0]?.key ?? '')
  const drag = useRef<{ x: number; y: number } | null>(null)

  const show = (bitmap: ImageBitmap | null) => {
    const c = canvas.current
    if (!bitmap || !c) {
      bitmap?.close()
      setState((s) => (s === 'ready' ? s : 'none'))
      return
    }
    // Numbers are small: keep the picture as sharp as the window allows.
    const scale = Math.min(1, 1100 / bitmap.width)
    c.width = Math.round(bitmap.width * scale)
    c.height = Math.round(bitmap.height * scale)
    const ctx = c.getContext('2d', { willReadFrequently: true })!
    ctx.drawImage(bitmap, 0, 0, c.width, c.height)
    bitmap.close()
    pixels.current = ctx.getImageData(0, 0, c.width, c.height)
    setDims({ w: c.width, h: c.height })
    setState('ready')
  }

  useEffect(() => {
    let cancelled = false
    void props.session.still().then((b) => (cancelled ? b?.close() : show(b)))
    return () => {
      cancelled = true
      pixels.current = null
      const c = canvas.current
      if (c) c.width = 0
    }
  }, [props.session])

  const pick = async (file: File | undefined) => {
    if (!file) return
    try {
      show(await createImageBitmap(file))
    } catch {
      setState('none')
    }
  }

  /** Characters found in a box (to check it holds the whole number and nothing else). */
  const found = (box: MinimapBox | undefined): number | null => {
    const img = pixels.current
    if (!img || !box) return null
    return segment({ width: img.width, height: img.height, data: img.data, timestamp: 0 }, box)?.length ?? 0
  }

  const at = (e: React.PointerEvent) => {
    const r = (e.currentTarget as HTMLElement).getBoundingClientRect()
    return { x: Math.max(0, Math.min(1, (e.clientX - r.left) / r.width)), y: Math.max(0, Math.min(1, (e.clientY - r.top) / r.height)) }
  }
  const onDown = (e: React.PointerEvent) => {
    if (state !== 'ready' || !selected) return
    ;(e.currentTarget as Element).setPointerCapture(e.pointerId)
    drag.current = at(e)
    setBoxes((b) => ({ ...b, [selected]: { ...drag.current!, w: 0, h: 0 } }))
  }
  const onMove = (e: React.PointerEvent) => {
    const d = drag.current
    if (!d) return
    const p = at(e)
    setBoxes((b) => ({ ...b, [selected]: { x: Math.min(d.x, p.x), y: Math.min(d.y, p.y), w: Math.abs(p.x - d.x), h: Math.abs(p.y - d.y) } }))
  }
  const onUp = () => {
    if (!drag.current) return
    drag.current = null
    const b = boxes[selected]
    // A click without a drag is not a box.
    if (b && (b.w < 0.004 || b.h < 0.004)) {
      const next = { ...boxes }
      delete next[selected]
      setBoxes(next)
      return
    }
    // On to the next number not yet marked.
    const next = props.specs.find((s) => s.key !== selected && !boxes[s.key])
    if (next) setSelected(next.key)
  }

  const sel = props.specs.find((s) => s.key === selected)
  const n = found(boxes[selected])

  return (
    <Modal title="Mark the numbers on your screen" onClose={props.onClose}>
      <div>
        <h1>Mark the numbers on your screen</h1>
        <p className="lede">
          Pick a number on the right, then drag a box tightly around it on the picture (the whole number, nothing else). The coach then learns your HUD's digits from what the cloud model reads, and reads them on this PC once the two agree.
        </p>
      </div>
      <div className="hud-align">
        <div className="align-stage" style={{ width: dims.w, height: dims.h }} onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp}>
          <canvas ref={canvas} />
          {state === 'ready'
            ? props.specs.map((s) => {
                const b = boxes[s.key]
                if (!b) return null
                return (
                  <div key={s.key} className={`hud-box${s.key === selected ? ' selected' : ''}`} style={{ left: b.x * dims.w, top: b.y * dims.h, width: b.w * dims.w, height: b.h * dims.h }}>
                    <span>{s.label.replace(/ \(.*\)$/, '')}</span>
                  </div>
                )
              })
            : null}
        </div>
        <ol className="hud-fields" aria-label="Numbers to mark">
          {props.specs.map((s) => {
            const count = found(boxes[s.key])
            return (
              <li key={s.key}>
                <button type="button" className={`hud-field${s.key === selected ? ' selected' : ''}`} aria-pressed={s.key === selected} onClick={() => setSelected(s.key)}>
                  <span>{s.label}</span>
                  <span className={`badge ${boxes[s.key] ? (count ? 'ok' : 'warn') : 'neutral'}`}>{boxes[s.key] ? (count ? `${count} characters` : 'nothing found') : 'not marked'}</span>
                </button>
                {boxes[s.key] ? (
                  <button
                    type="button"
                    className="btn sm ghost"
                    onClick={() => {
                      const next = { ...boxes }
                      delete next[s.key]
                      setBoxes(next)
                    }}
                  >
                    Clear
                  </button>
                ) : null}
              </li>
            )
          })}
        </ol>
      </div>
      {state === 'none' ? <p className="muted small">No picture yet: start capture (in a match) or pick a screenshot of your game below.</p> : null}
      {sel && boxes[selected] ? (
        <p className={`small ${n ? '' : 'warn-text'}`}>
          {n ? `${sel.label}: ${n} characters found in the box. If that is more or fewer than the number shows, redraw it tighter.` : `${sel.label}: no characters found in the box. Draw it around the number.`}
        </p>
      ) : null}
      <div className="btn-row" style={{ marginTop: 12 }}>
        <label className="btn ghost">
          Use a screenshot…
          <input type="file" accept="image/png,image/jpeg,image/webp" hidden onChange={(e) => void pick(e.target.files?.[0])} />
        </label>
        <div style={{ flex: 1 }} />
        <button type="button" className="btn ghost" onClick={props.onClose}>
          Cancel
        </button>
        <button
          type="button"
          className="btn primary"
          onClick={() => {
            props.onSave(boxes)
            props.onClose()
          }}
        >
          Save
        </button>
      </div>
    </Modal>
  )
}
