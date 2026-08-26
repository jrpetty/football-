// ---------------------------------------------------------------------------
// The photograph, full screen.
//
// The whole point of keeping the roll photographs is being able to read them
// weeks later, and a thumbnail cannot be read. This is the reading view: one
// photograph at a time, pinched and dragged the way every phone does it, with
// the next part of the roll a swipe away.
//
// The gesture arithmetic is the standard one — keep the point under the
// fingers fixed while the scale changes — done with pointer events so a mouse,
// a trackpad and two thumbs all land in the same code path.
// ---------------------------------------------------------------------------

import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { IconChevronRight } from './icons.tsx'

export interface LightboxPhoto {
  url: string
  label: string
}

interface Props {
  photos: readonly LightboxPhoto[]
  initial: number
  onClose: () => void
}

const MIN_SCALE = 1
const MAX_SCALE = 5
/** Where a double tap lands, between the two ends. */
const DOUBLE_TAP_SCALE = 2.5
/** Fingers travelling less than this is a tap, not a drag. */
const TAP_SLOP = 8
/** A sideways drag past this, at rest, turns the page. */
const SWIPE_DISTANCE = 60

interface Transform {
  scale: number
  tx: number
  ty: number
}

const AT_REST: Transform = { scale: 1, tx: 0, ty: 0 }

interface Gesture {
  /** Both start values, so every move is computed fresh from the beginning. */
  start: Transform
  startMid: { x: number; y: number }
  startDist: number | null
  moved: boolean
  onImage: boolean
}

function clampTransform(next: Transform, stage: { width: number; height: number }): Transform {
  const scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, next.scale))
  // The image sits inside the stage, so how far it can usefully travel grows
  // with the zoom. A little slack keeps the edge reachable without letting the
  // photograph be flung off screen and "lost".
  const boundX = (stage.width * (scale - 1)) / 2 + 40
  const boundY = (stage.height * (scale - 1)) / 2 + 40
  return {
    scale,
    tx: Math.min(boundX, Math.max(-boundX, next.tx)),
    ty: Math.min(boundY, Math.max(-boundY, next.ty)),
  }
}

export function Lightbox({ photos, initial, onClose }: Props) {
  const [index, setIndex] = useState(() => Math.min(Math.max(initial, 0), photos.length - 1))
  const [t, setT] = useState<Transform>(AT_REST)
  const [dragging, setDragging] = useState(false)

  const stageRef = useRef<HTMLDivElement>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  const pointers = useRef(new Map<number, { x: number; y: number }>())
  const gesture = useRef<Gesture | null>(null)
  const lastTap = useRef<{ at: number; x: number; y: number } | null>(null)
  // The state, readable inside native listeners without re-binding them.
  const tRef = useRef(t)
  tRef.current = t

  const count = photos.length
  const photo = photos[index] ?? photos[0]

  function go(next: number) {
    const clamped = Math.min(count - 1, Math.max(0, next))
    setIndex(clamped)
    setT(AT_REST)
  }

  // Escape closes, arrows page — and the page behind must not scroll while a
  // photograph is up, or closing lands somewhere else on the night.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
      else if (e.key === 'ArrowRight') go(index + 1)
      else if (e.key === 'ArrowLeft') go(index - 1)
    }
    window.addEventListener('keydown', onKey)
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      window.removeEventListener('keydown', onKey)
      document.body.style.overflow = previousOverflow
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [index, count])

  // Focus lands on the close button and goes back where it came from after.
  useEffect(() => {
    const before = document.activeElement
    closeRef.current?.focus()
    return () => {
      if (before instanceof HTMLElement) before.focus()
    }
  }, [])

  // Ctrl+wheel is what a trackpad pinch arrives as; a plain wheel zooms too,
  // since there is nothing else for it to do here. Native listener because
  // React's onWheel is passive and preventDefault would be ignored.
  useEffect(() => {
    const stage = stageRef.current
    if (!stage) return
    const onWheel = (e: WheelEvent) => {
      e.preventDefault()
      const rect = stage.getBoundingClientRect()
      const p = { x: e.clientX - rect.left - rect.width / 2, y: e.clientY - rect.top - rect.height / 2 }
      const current = tRef.current
      const factor = Math.exp(-e.deltaY * 0.002)
      const scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, current.scale * factor))
      // Keep the point under the cursor fixed while the scale moves.
      const q = { x: (p.x - current.tx) / current.scale, y: (p.y - current.ty) / current.scale }
      setT(clampTransform({ scale, tx: p.x - scale * q.x, ty: p.y - scale * q.y }, rect))
    }
    stage.addEventListener('wheel', onWheel, { passive: false })
    return () => stage.removeEventListener('wheel', onWheel)
  }, [])

  function mid(): { x: number; y: number } {
    const all = [...pointers.current.values()]
    const rect = stageRef.current?.getBoundingClientRect()
    const cx = rect ? rect.left + rect.width / 2 : 0
    const cy = rect ? rect.top + rect.height / 2 : 0
    const x = all.reduce((a, p) => a + p.x, 0) / all.length - cx
    const y = all.reduce((a, p) => a + p.y, 0) / all.length - cy
    return { x, y }
  }

  function dist(): number | null {
    const all = [...pointers.current.values()]
    if (all.length < 2) return null
    const [a, b] = all as [{ x: number; y: number }, { x: number; y: number }]
    return Math.hypot(a.x - b.x, a.y - b.y)
  }

  function onPointerDown(e: React.PointerEvent) {
    stageRef.current?.setPointerCapture(e.pointerId)
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
    gesture.current = {
      start: tRef.current,
      startMid: mid(),
      startDist: dist(),
      moved: gesture.current?.moved ?? false,
      onImage: e.target instanceof HTMLImageElement,
    }
    setDragging(true)
  }

  function onPointerMove(e: React.PointerEvent) {
    if (!pointers.current.has(e.pointerId) || !gesture.current) return
    pointers.current.set(e.pointerId, { x: e.clientX, y: e.clientY })
    const g = gesture.current
    const m = mid()
    if (Math.hypot(m.x - g.startMid.x, m.y - g.startMid.y) > TAP_SLOP) g.moved = true

    const rect = stageRef.current?.getBoundingClientRect()
    if (!rect) return

    const d = dist()
    if (d !== null && g.startDist !== null && g.startDist > 0) {
      // Two fingers: scale by how far they have spread, anchored where the
      // pinch began, carried along with the fingers.
      const scale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, g.start.scale * (d / g.startDist)))
      const q = { x: (g.startMid.x - g.start.tx) / g.start.scale, y: (g.startMid.y - g.start.ty) / g.start.scale }
      setT(clampTransform({ scale, tx: m.x - scale * q.x, ty: m.y - scale * q.y }, rect))
    } else if (g.start.scale > 1) {
      // One finger, zoomed in: a straightforward pan.
      setT(
        clampTransform(
          { scale: g.start.scale, tx: g.start.tx + (m.x - g.startMid.x), ty: g.start.ty + (m.y - g.startMid.y) },
          rect,
        ),
      )
    } else if (count > 1) {
      // One finger at rest: the drag previews the page turn.
      setT({ scale: 1, tx: m.x - g.startMid.x, ty: 0 })
    }
  }

  function onPointerUp(e: React.PointerEvent) {
    if (!pointers.current.has(e.pointerId)) return
    pointers.current.delete(e.pointerId)
    const g = gesture.current
    if (!g) return

    if (pointers.current.size > 0) {
      // One finger of a pinch lifted: restart the gesture from here so the
      // remaining finger pans smoothly instead of jumping.
      gesture.current = { start: tRef.current, startMid: mid(), startDist: dist(), moved: g.moved, onImage: g.onImage }
      return
    }

    setDragging(false)
    gesture.current = null

    if (!g.moved) {
      // A clean tap. Two of them close together zoom; one on the backdrop closes.
      const now = Date.now()
      const tap = { at: now, x: e.clientX, y: e.clientY }
      const last = lastTap.current
      lastTap.current = tap
      if (last && now - last.at < 320 && Math.hypot(tap.x - last.x, tap.y - last.y) < 32) {
        lastTap.current = null
        const rect = stageRef.current?.getBoundingClientRect()
        if (!rect) return
        const current = tRef.current
        if (current.scale > 1.01) {
          setT(AT_REST)
        } else {
          const p = { x: e.clientX - rect.left - rect.width / 2, y: e.clientY - rect.top - rect.height / 2 }
          setT(clampTransform({ scale: DOUBLE_TAP_SCALE, tx: p.x * (1 - DOUBLE_TAP_SCALE), ty: p.y * (1 - DOUBLE_TAP_SCALE) }, rect))
        }
      } else if (!g.onImage && tRef.current.scale <= 1.01) {
        onClose()
      }
      return
    }

    // A sideways drag at rest turns the page — or snaps back if it was short.
    const current = tRef.current
    if (g.start.scale <= 1.01 && current.scale <= 1.01) {
      if (current.tx <= -SWIPE_DISTANCE && index < count - 1) go(index + 1)
      else if (current.tx >= SWIPE_DISTANCE && index > 0) go(index - 1)
      else setT(AT_REST)
    }
  }

  function onPointerCancel(e: React.PointerEvent) {
    pointers.current.delete(e.pointerId)
    if (pointers.current.size === 0) {
      gesture.current = null
      setDragging(false)
      if (tRef.current.scale <= 1.01) setT(AT_REST)
    }
  }

  if (!photo) return null

  // Portalled to the body: the screens animate in with a transform, and a
  // transformed ancestor quietly becomes what `position: fixed` measures
  // against — the overlay would open pinned inside the page, under the header.
  return createPortal(
    <div
      className="lightbox"
      role="dialog"
      aria-modal="true"
      aria-label={count > 1 ? `${photo.label} — photograph ${index + 1} of ${count}` : photo.label}
      data-testid="lightbox"
    >
      <div className="lb-bar">
        <span className="lb-caption">{photo.label}</span>
        {count > 1 && <span className="lb-count">{index + 1} / {count}</span>}
        <button ref={closeRef} type="button" className="lb-close" onClick={onClose} aria-label="Close the photograph">
          ×
        </button>
      </div>

      <div
        ref={stageRef}
        className={`lb-stage${dragging ? ' dragging' : ''}${t.scale > 1 ? ' zoomed' : ''}`}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onPointerCancel={onPointerCancel}
      >
        <img
          src={photo.url}
          alt={photo.label}
          draggable={false}
          style={{ transform: `translate(${t.tx}px, ${t.ty}px) scale(${t.scale})` }}
        />
      </div>

      {count > 1 && (
        <>
          <button
            type="button"
            className="lb-nav lb-prev"
            onClick={() => go(index - 1)}
            disabled={index === 0}
            aria-label="The photograph before"
          >
            <span style={{ transform: 'rotate(180deg)', display: 'inline-flex' }} aria-hidden="true">
              <IconChevronRight size={22} />
            </span>
          </button>
          <button
            type="button"
            className="lb-nav lb-next"
            onClick={() => go(index + 1)}
            disabled={index === count - 1}
            aria-label="The photograph after"
          >
            <IconChevronRight size={22} />
          </button>
        </>
      )}

      <p className="lb-hint" aria-hidden="true">
        Pinch or double-tap to zoom{count > 1 ? ' · swipe for the rest' : ''}
      </p>
    </div>,
    document.body,
  )
}
