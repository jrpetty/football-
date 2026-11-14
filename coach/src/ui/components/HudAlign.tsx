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

/** What a box holds, checked on the picture: ok, a short badge, and a line for the player. */
export type BoxProbe = (frame: { width: number; height: number; data: Uint8ClampedArray; timestamp: number }, box: MinimapBox, key: string) => { ok: boolean; badge: string; line: string }

/** The default check: characters found in the box (to check it holds the whole number and nothing else). */
const digitsProbe: BoxProbe = (frame, box) => {
  const n = segment(frame, box)?.length ?? 0
  return { ok: n > 0, badge: n ? `${n} characters` : 'nothing found', line: n ? `${n} characters found in the box. If that is more or fewer than the number shows, redraw it tighter.` : 'no characters found in the box. Draw it around the number.' }
}

export function HudAlign(props: {
  session: CoachSession
  specs: readonly Pick<HudNumberSpec, 'key' | 'label'>[]
  initial: Boxes
  onSave: (boxes: Boxes) => void
  onClose: () => void
  title?: string
  lede?: string
  probe?: BoxProbe
}) {
  const probe = props.probe ?? digitsProbe
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

  /** What the box holds on the picture. */
  const found = (box: MinimapBox | undefined, key: string) => {
    const img = pixels.current
    if (!img || !box || box.w <= 0 || box.h <= 0) return null
    return probe({ width: img.width, height: img.height, data: img.data, timestamp: 0 }, box, key)
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
  const n = found(boxes[selected], selected)
  const title = props.title ?? 'Mark the numbers on your screen'

  return (
    <Modal title={title} onClose={props.onClose}>
      <div>
        <h1>{title}</h1>
        <p className="lede">
          {props.lede ??
            "Pick a number from the list, then drag a box tightly around it on the picture (the whole number, nothing else). The coach then learns your HUD's digits from what the cloud model reads, and reads them on this PC once the two agree."}
        </p>
      </div>
      <div className="hud-align">
        <div className="align-stage" style={{ width: dims.w, height: dims.h }} onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp}>
          <canvas ref={canvas} />
          {state === 'none' ? (
            <div className="empty" style={{ position: 'absolute', inset: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 12, textAlign: 'center', padding: 24 }}>
              <span>No picture yet. Start capture in a match and open this again, or use a screenshot of your game (full size, with the HUD showing).</span>
              <label className="btn" onPointerDown={(e) => e.stopPropagation()}>
                Use a screenshot…
                <input type="file" accept="image/png,image/jpeg,image/webp" hidden onChange={(e) => void pick(e.target.files?.[0])} />
              </label>
            </div>
          ) : null}
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
        <ol className="hud-fields" aria-label="Items to mark">
          {props.specs.map((s) => {
            const res = found(boxes[s.key], s.key)
            return (
              <li key={s.key}>
                <button type="button" className={`hud-field${s.key === selected ? ' selected' : ''}`} aria-pressed={s.key === selected} onClick={() => setSelected(s.key)}>
                  <span>{s.label}</span>
                  <span className={`badge ${boxes[s.key] ? (res?.ok ? 'ok' : 'warn') : 'neutral'}`}>{boxes[s.key] ? (res?.badge ?? 'marked') : 'not marked'}</span>
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
      {sel && boxes[selected] && n ? <p className={`small ${n.ok ? '' : 'warn-text'}`}>{`${sel.label}: ${n.line}`}</p> : null}
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
