// Shows one still of the capture with the profile's HUD regions outlined, so
// the player can confirm the profile lines up with their screen. Drawn in this
// app's window only (never over the game); the still is discarded on close.

import { useEffect, useRef, useState } from 'react'
import type { GameProfile } from '../../core/profile/schema.ts'
import type { CoachSession } from '../session.ts'
import { Modal } from './common.tsx'

export function AlignmentCheck(props: { session: CoachSession; profile: GameProfile; onClose: () => void }) {
  const canvas = useRef<HTMLCanvasElement>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'unavailable'>('loading')

  useEffect(() => {
    let cancelled = false
    void props.session.still().then((bitmap) => {
      const c = canvas.current
      if (cancelled || !bitmap || !c) {
        bitmap?.close()
        if (!cancelled) setState('unavailable')
        return
      }
      const scale = Math.min(1, 1280 / bitmap.width)
      c.width = Math.round(bitmap.width * scale)
      c.height = Math.round(bitmap.height * scale)
      const ctx = c.getContext('2d')!
      ctx.drawImage(bitmap, 0, 0, c.width, c.height)
      bitmap.close()
      ctx.lineWidth = 2
      ctx.font = '600 12px system-ui, sans-serif'
      for (const s of props.profile.signals) {
        const x = s.region.x * c.width
        const y = s.region.y * c.height
        ctx.strokeStyle = '#5ad1b8'
        ctx.strokeRect(x, y, s.region.w * c.width, s.region.h * c.height)
        const w = ctx.measureText(s.label).width + 8
        ctx.fillStyle = 'rgba(6, 16, 22, 0.85)'
        ctx.fillRect(x, Math.max(0, y - 17), w, 16)
        ctx.fillStyle = '#5ad1b8'
        ctx.fillText(s.label, x + 4, Math.max(12, y - 5))
      }
      setState('ready')
    })
    return () => {
      cancelled = true
      // Discard the still: clear the pixels as well as unmounting.
      const c = canvas.current
      if (c) c.width = 0
    }
  }, [props.session, props.profile])

  return (
    <Modal title="Check HUD alignment" onClose={props.onClose}>
      <div>
        <h1>Check HUD alignment</h1>
        <p className="lede">
          Each box should sit exactly on the HUD element it names. If the boxes are shifted, capture the game's client area
          (borderless or fullscreen) rather than a window with a title bar. {props.profile.capture.notes}
        </p>
      </div>
      <canvas ref={canvas} className="align-canvas" aria-label="Captured still with HUD regions outlined" />
      {state === 'unavailable' ? <p className="muted">No frame is available yet.</p> : null}
      <p className="muted small" style={{ margin: 0 }}>
        This still is held in memory only while this panel is open.
      </p>
      <div className="btn-row" style={{ justifyContent: 'flex-end' }}>
        <button type="button" className="btn primary" onClick={props.onClose}>
          Done
        </button>
      </div>
    </Modal>
  )
}
