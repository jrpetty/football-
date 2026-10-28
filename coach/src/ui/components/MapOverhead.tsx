// The overhead map: the map stitched from the player's own minimap, drawn as
// a tactical map (lighter shapes are structures, edges glow), with where they
// died, where they got kills, where each killer was and the line of fire,
// their paths life by life, enemy pings and a heat of deaths. Zoom with the
// wheel or the buttons, drag to pan, hover a marker for its details. One
// match can be played back on it. With the map's official overhead map, that
// picture is the base (out of bounds dimmed), and the stitched map can be laid
// over it to check the placement.

import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { KIND_OUT } from '../../core/map/official.ts'
import { deathClusters, type MapImage, type MapLayers, type OfficialBase, type PlacedPin } from '../../core/map/view.ts'
import { gameClock } from '../format.ts'

export type Layer = 'deaths' | 'kills' | 'killers' | 'paths' | 'enemies' | 'heat' | 'callouts'

export const LAYERS: readonly { readonly id: Layer; readonly label: string }[] = [
  { id: 'deaths', label: 'Deaths' },
  { id: 'kills', label: 'Kills' },
  { id: 'killers', label: 'Killers' },
  { id: 'paths', label: 'Paths' },
  { id: 'enemies', label: 'Enemy pings' },
  { id: 'heat', label: 'Death heat' },
  { id: 'callouts', label: 'Callouts' },
]

/** Marker colours (each also has its own shape, so colour is never the only cue). */
export const MARK = {
  death: '#ff5c5c',
  kill: '#5ad1b8',
  killer: '#f2b84b',
  path: '#79a8ff',
  enemy: '#d55181',
} as const

/** The stitched map as a picture: dark ground, lighter structures, edges in the accent colour. */
export function stylizedMap(layers: MapLayers): HTMLCanvasElement {
  return stylizedImage(layers.image)
}

/** The official overhead map as the base: the picture (up to 2048 px across), out of bounds dimmed. */
export function officialPicture(picture: HTMLImageElement | ImageBitmap, base: OfficialBase): HTMLCanvasElement {
  const f = Math.min(1, 2048 / Math.max(picture.width, picture.height))
  const c = document.createElement('canvas')
  c.width = Math.round(picture.width * f)
  c.height = Math.round(picture.height * f)
  const ctx = c.getContext('2d')!
  ctx.drawImage(picture, 0, 0, c.width, c.height)
  const mask = document.createElement('canvas')
  mask.width = base.w
  mask.height = base.h
  const mctx = mask.getContext('2d')!
  const img = mctx.createImageData(base.w, base.h)
  for (let i = 0; i < base.w * base.h; i++) {
    if (base.kind[i] !== KIND_OUT) continue
    img.data[i * 4] = 5
    img.data[i * 4 + 1] = 8
    img.data[i * 4 + 2] = 14
    img.data[i * 4 + 3] = 150
  }
  mctx.putImageData(img, 0, 0)
  ctx.imageSmoothingEnabled = true
  ctx.drawImage(mask, 0, 0, c.width, c.height)
  return c
}

/** A stitched image drawn as a tactical map. */
export function stylizedImage(image: MapImage): HTMLCanvasElement {
  const { w, h, lum, weight } = image
  const c = document.createElement('canvas')
  c.width = w
  c.height = h
  const ctx = c.getContext('2d')!
  const img = ctx.createImageData(w, h)
  const d = img.data
  const at = (x: number, y: number) => (x < 0 || y < 0 || x >= w || y >= h || !weight[y * w + x] ? -1 : lum[y * w + x] / 255)
  // Soft edges: how much of a 7×7 neighbourhood has been seen (a running box sum).
  const seen = new Float32Array(w * h)
  const rowSum = new Float32Array(w * h)
  const R = 3
  for (let y = 0; y < h; y++) {
    let acc = 0
    for (let x = -R; x < w + R; x++) {
      if (x + R < w && x + R >= 0) acc += weight[y * w + x + R] ? 1 : 0
      if (x - R - 1 >= 0 && x - R - 1 < w) acc -= weight[y * w + x - R - 1] ? 1 : 0
      if (x >= 0 && x < w) rowSum[y * w + x] = acc
    }
  }
  for (let x = 0; x < w; x++) {
    let acc = 0
    for (let y = -R; y < h + R; y++) {
      if (y + R < h && y + R >= 0) acc += rowSum[(y + R) * w + x]
      if (y - R - 1 >= 0 && y - R - 1 < h) acc -= rowSum[(y - R - 1) * w + x]
      if (y >= 0 && y < h) seen[y * w + x] = acc / ((2 * R + 1) * (2 * R + 1))
    }
  }
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const i = y * w + x
      if (!weight[i]) continue
      const v = lum[i] / 255
      // Edges: the luminance gradient (seen neighbours only).
      let gx = 0
      let gy = 0
      const c0 = v
      const l = at(x - 1, y)
      const r = at(x + 1, y)
      const u = at(x, y - 1)
      const dn = at(x, y + 1)
      gx = (r < 0 ? c0 : r) - (l < 0 ? c0 : l)
      gy = (dn < 0 ? c0 : dn) - (u < 0 ? c0 : u)
      const e = Math.min(1, Math.hypot(gx, gy) * 3.2)
      // Ground #0f1a2a → structures #7b9cc0.
      const t = Math.max(0, Math.min(1, (v - 0.12) / 0.55))
      let R = 15 + t * 108
      let G = 26 + t * 130
      let B = 42 + t * 150
      R += e * (90 - R * 0.4)
      G += e * (209 - G * 0.25)
      B += e * (184 - B * 0.3)
      const o = i * 4
      d[o] = R
      d[o + 1] = G
      d[o + 2] = B
      // The edges of what has been seen fade out softly.
      d[o + 3] = Math.round(255 * Math.min(1, seen[i] * 1.6))
    }
  }
  ctx.putImageData(img, 0, 0)
  return c
}

function heatCanvas(layers: MapLayers, pins: readonly PlacedPin[]): HTMLCanvasElement {
  const { w, h } = layers.image
  const c = document.createElement('canvas')
  c.width = w
  c.height = h
  const ctx = c.getContext('2d')!
  ctx.globalCompositeOperation = 'lighter'
  for (const p of pins) {
    if (p.kind !== 'death') continue
    const g = ctx.createRadialGradient(p.px, p.py, 0, p.px, p.py, 22)
    g.addColorStop(0, 'rgba(255, 70, 70, 0.42)')
    g.addColorStop(1, 'rgba(255, 70, 70, 0)')
    ctx.fillStyle = g
    ctx.fillRect(p.px - 22, p.py - 22, 44, 44)
  }
  return c
}

interface View {
  s: number
  tx: number
  ty: number
}

export function MapOverhead({
  layers,
  show,
  here,
  focus,
  playT,
  compact = false,
  label,
  picture = null,
  overlay = null,
}: {
  layers: MapLayers
  show: ReadonlySet<Layer>
  /** The official overhead map's picture (with official layers). */
  picture?: HTMLImageElement | ImageBitmap | null
  /** Stitched pieces laid over the official map to check their placement: each with its transform (canvas → layer pixels). */
  overlay?: readonly { readonly canvas: HTMLCanvasElement; readonly m: readonly [number, number, number, number, number, number] }[] | null
  /** The player now (image coordinates), live. */
  here?: { readonly x: number; readonly y: number } | null
  /** Only this match (index into the matches the layers were built from), or all. */
  focus?: number | null
  /** Playback: draw the focused match up to this many seconds. */
  playT?: number | null
  compact?: boolean
  label: string
}) {
  const wrap = useRef<HTMLDivElement>(null)
  const canvas = useRef<HTMLCanvasElement>(null)
  const [size, setSize] = useState({ w: 640, h: 420 })
  const [view, setView] = useState<View | null>(null)
  const [hover, setHover] = useState<{ pin: PlacedPin; x: number; y: number } | null>(null)
  const drag = useRef<{ x: number; y: number; tx: number; ty: number } | null>(null)
  const { w: iw, h: ih } = layers.image

  // The picture depends only on the stitched map, not on the pins (which change every few seconds while playing).
  const image = layers.image
  const officialBase = layers.official?.base ?? null
  const base = useMemo(() => (officialBase && picture ? officialPicture(picture, officialBase) : stylizedMap(layers)), [image, officialBase, picture])
  const pins = useMemo(
    () => layers.pins.filter((p) => (focus === null || focus === undefined || p.match === focus) && (playT === null || playT === undefined || p.t <= playT)),
    [layers, focus, playT],
  )
  const heat = useMemo(() => heatCanvas(layers, pins), [layers, pins])
  const clusters = useMemo(() => deathClusters(pins), [pins])

  // Size to the container; the map keeps its proportions.
  useEffect(() => {
    const el = wrap.current
    if (!el) return
    const ro = new ResizeObserver(([e]) => {
      const w = Math.max(240, Math.floor(e.contentRect.width))
      const h = Math.round(Math.max(compact ? 200 : 300, Math.min(compact ? 260 : 600, (w * ih) / iw)))
      setSize({ w, h })
    })
    ro.observe(el)
    return () => ro.disconnect()
  }, [iw, ih, compact])

  const fit = useCallback((): View => {
    const s = Math.min(size.w / iw, size.h / ih) * 0.94
    return { s, tx: (size.w - iw * s) / 2, ty: (size.h - ih * s) / 2 }
  }, [size, iw, ih])
  // Live: keep the player in view; otherwise fit the map.
  useEffect(() => setView(null), [layers.image.piece, size.w, size.h])
  const v = view ?? (compact && here ? centreOn(here, fit(), size, 2.2) : fit())

  // Draw.
  const [pulse, setPulse] = useState(0)
  useEffect(() => {
    if (!here) return
    let raf = 0
    const tick = () => {
      setPulse((performance.now() / 1000) % 1.6)
      raf = requestAnimationFrame(tick)
    }
    raf = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(raf)
  }, [here])

  useEffect(() => {
    const c = canvas.current
    if (!c) return
    const dpr = Math.min(2, window.devicePixelRatio || 1)
    c.width = size.w * dpr
    c.height = size.h * dpr
    const ctx = c.getContext('2d')!
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    // Background: deep navy with a faint grid.
    const bg = ctx.createLinearGradient(0, 0, 0, size.h)
    bg.addColorStop(0, '#0a111c')
    bg.addColorStop(1, '#070b12')
    ctx.fillStyle = bg
    ctx.fillRect(0, 0, size.w, size.h)
    ctx.fillStyle = 'rgba(122, 168, 255, 0.07)'
    for (let y = 8; y < size.h; y += 16) for (let x = 8; x < size.w; x += 16) ctx.fillRect(x, y, 1, 1)

    ctx.save()
    ctx.translate(v.tx, v.ty)
    ctx.scale(v.s, v.s)
    ctx.imageSmoothingEnabled = !!officialBase || v.s < 2
    ctx.drawImage(base, 0, 0, iw, ih)
    if (overlay?.length) {
      ctx.globalAlpha = 0.6
      ctx.imageSmoothingEnabled = v.s < 2
      for (const o of overlay) {
        ctx.save()
        ctx.transform(...o.m)
        ctx.drawImage(o.canvas, 0, 0)
        ctx.restore()
      }
      ctx.globalAlpha = 1
    }
    const px = 1 / v.s
    if (show.has('heat')) {
      ctx.globalCompositeOperation = 'screen'
      ctx.drawImage(heat, 0, 0)
      ctx.globalCompositeOperation = 'source-over'
    }
    if (show.has('enemies')) {
      const max = Math.max(1, ...layers.enemies.map((e) => e.n))
      for (const e of layers.enemies) {
        ctx.fillStyle = `rgba(213, 81, 129, ${0.25 + 0.6 * (e.n / max)})`
        ctx.beginPath()
        ctx.arc(e.x, e.y, (2.5 + 3 * (e.n / max)) * px * Math.max(1, v.s / 2), 0, Math.PI * 2)
        ctx.fill()
      }
    }
    if (show.has('paths')) {
      ctx.lineJoin = 'round'
      ctx.lineCap = 'round'
      const latest = Math.max(-1, ...layers.paths.map((x) => x.match))
      for (const p of layers.paths) {
        if (focus !== null && focus !== undefined && p.match !== focus) continue
        const pts = playT !== null && playT !== undefined ? p.points.filter((q) => q[2] <= playT) : p.points
        if (pts.length < 2) continue
        const recent = focus !== null && focus !== undefined ? 1 : p.match === latest ? 0.85 : 0.35
        ctx.strokeStyle = `rgba(121, 168, 255, ${recent})`
        ctx.lineWidth = 1.6 * px
        ctx.beginPath()
        ctx.moveTo(pts[0][0], pts[0][1])
        for (const q of pts.slice(1)) ctx.lineTo(q[0], q[1])
        ctx.stroke()
      }
      // Playback: the player now.
      if (playT !== null && playT !== undefined) {
        const cur = layers.paths
          .filter((p) => p.match === focus)
          .flatMap((p) => p.points)
          .filter((q) => q[2] <= playT)
          .at(-1)
        if (cur) drawHere(ctx, cur[0], cur[1], px, 0.4)
      }
    }
    if (show.has('killers')) {
      for (const p of pins) {
        if (p.kind !== 'death' || !p.killerPx) continue
        const k = p.killerPx
        ctx.strokeStyle = 'rgba(242, 184, 75, 0.8)'
        ctx.lineWidth = 1.4 * px
        ctx.setLineDash(p.killer?.from === 'killcam' ? [] : [4 * px, 3 * px])
        ctx.beginPath()
        ctx.moveTo(k.x, k.y)
        ctx.lineTo(p.px, p.py)
        ctx.stroke()
        ctx.setLineDash([])
        // The arrowhead, at the player.
        const a = Math.atan2(p.py - k.y, p.px - k.x)
        const r = 7 * px
        ctx.fillStyle = 'rgba(242, 184, 75, 0.9)'
        ctx.beginPath()
        ctx.moveTo(p.px - Math.cos(a) * 6 * px, p.py - Math.sin(a) * 6 * px)
        ctx.lineTo(p.px - Math.cos(a) * 6 * px - r * Math.cos(a - 0.45), p.py - Math.sin(a) * 6 * px - r * Math.sin(a - 0.45))
        ctx.lineTo(p.px - Math.cos(a) * 6 * px - r * Math.cos(a + 0.45), p.py - Math.sin(a) * 6 * px - r * Math.sin(a + 0.45))
        ctx.closePath()
        ctx.fill()
        triangle(ctx, k.x, k.y, 5 * px, MARK.killer)
      }
    }
    if (show.has('kills')) for (const p of pins) if (p.kind === 'kill') diamond(ctx, p.px, p.py, 5 * px, MARK.kill)
    if (show.has('deaths')) {
      for (const p of pins) if (p.kind === 'death') cross(ctx, p.px, p.py, 6 * px, MARK.death, hover?.pin === p)
      // Several deaths on one spot: a count.
      ctx.font = `700 ${11 * px}px system-ui, sans-serif`
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      for (const c of clusters) {
        if (c.n < 2) continue
        ctx.fillStyle = 'rgba(7, 11, 18, 0.85)'
        ctx.beginPath()
        ctx.arc(c.x + 9 * px, c.y - 9 * px, 7 * px, 0, Math.PI * 2)
        ctx.fill()
        ctx.fillStyle = '#ffd1d1'
        ctx.fillText(String(c.n), c.x + 9 * px, c.y - 8.5 * px)
      }
    }
    if (show.has('callouts')) {
      ctx.font = `600 ${11 * px}px system-ui, sans-serif`
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      ctx.lineJoin = 'round'
      // Most-read callouts first; a label that would overlap one already drawn is left out.
      const placed: { x0: number; y0: number; x1: number; y1: number }[] = []
      for (const c of [...layers.callouts].sort((a, b) => b.n - a.n)) {
        const tw = ctx.measureText(c.name).width / 2 + 3 * px
        const box = { x0: c.x - tw, y0: c.y - 8 * px, x1: c.x + tw, y1: c.y + 8 * px }
        if (placed.some((b) => box.x0 < b.x1 && box.x1 > b.x0 && box.y0 < b.y1 && box.y1 > b.y0)) continue
        placed.push(box)
        ctx.strokeStyle = 'rgba(7, 11, 18, 0.9)'
        ctx.lineWidth = 3 * px
        ctx.strokeText(c.name, c.x, c.y)
        ctx.fillStyle = 'rgba(231, 236, 245, 0.92)'
        ctx.fillText(c.name, c.x, c.y)
      }
    }
    if (here) drawHere(ctx, here.x, here.y, px, pulse / 1.6)
    ctx.restore()
  }, [base, heat, layers, pins, clusters, show, v, size, here, pulse, focus, playT, hover, overlay, officialBase, iw, ih])

  // Pointer: drag to pan, wheel to zoom, hover for details.
  const onWheel = (e: React.WheelEvent) => {
    const rect = canvas.current!.getBoundingClientRect()
    const mx = e.clientX - rect.left
    const my = e.clientY - rect.top
    zoomAt(mx, my, Math.exp(-e.deltaY * 0.0015))
  }
  const zoomAt = (mx: number, my: number, k: number) => {
    const f = fit()
    const cur = v
    const s = Math.max(f.s * 0.8, Math.min(f.s * 10, cur.s * k))
    const q = s / cur.s
    setView({ s, tx: mx - (mx - cur.tx) * q, ty: my - (my - cur.ty) * q })
  }
  // The page should not scroll while zooming the map.
  useEffect(() => {
    const c = canvas.current
    if (!c) return
    const stop = (e: WheelEvent) => e.preventDefault()
    c.addEventListener('wheel', stop, { passive: false })
    return () => c.removeEventListener('wheel', stop)
  }, [])
  const onDown = (e: React.PointerEvent) => {
    ;(e.target as Element).setPointerCapture(e.pointerId)
    drag.current = { x: e.clientX, y: e.clientY, tx: v.tx, ty: v.ty }
  }
  const onMove = (e: React.PointerEvent) => {
    const rect = canvas.current!.getBoundingClientRect()
    if (drag.current) {
      setView({ s: v.s, tx: drag.current.tx + e.clientX - drag.current.x, ty: drag.current.ty + e.clientY - drag.current.y })
      setHover(null)
      return
    }
    const mx = e.clientX - rect.left
    const my = e.clientY - rect.top
    let best: { pin: PlacedPin; d: number } | null = null
    for (const p of pins) {
      if ((p.kind === 'death' && !show.has('deaths')) || (p.kind === 'kill' && !show.has('kills'))) continue
      const d = Math.hypot(v.tx + p.px * v.s - mx, v.ty + p.py * v.s - my)
      if (d < 12 && (!best || d < best.d)) best = { pin: p, d }
    }
    setHover(best ? { pin: best.pin, x: v.tx + best.pin.px * v.s, y: v.ty + best.pin.py * v.s } : null)
  }
  const onUp = () => {
    drag.current = null
  }

  const deaths = layers.pins.filter((p) => p.kind === 'death').length
  const kills = layers.pins.filter((p) => p.kind === 'kill').length
  return (
    <div className={`overhead${compact ? ' compact' : ''}`} ref={wrap}>
      <canvas
        ref={canvas}
        style={{ width: size.w, height: size.h }}
        role="img"
        aria-label={`${label}: ${deaths} deaths and ${kills} kills placed on ${officialBase ? 'the official overhead map' : 'the map stitched from your minimap'}.`}
        onWheel={onWheel}
        onPointerDown={onDown}
        onPointerMove={onMove}
        onPointerUp={onUp}
        onPointerLeave={() => {
          onUp()
          setHover(null)
        }}
        onDoubleClick={() => setView(null)}
      />
      {!compact ? (
        <div className="overhead-zoom">
          <button type="button" className="btn sm ghost" aria-label="Zoom in" onClick={() => zoomAt(size.w / 2, size.h / 2, 1.4)}>
            +
          </button>
          <button type="button" className="btn sm ghost" aria-label="Zoom out" onClick={() => zoomAt(size.w / 2, size.h / 2, 1 / 1.4)}>
            −
          </button>
          <button type="button" className="btn sm ghost" onClick={() => setView(null)}>
            Fit
          </button>
        </div>
      ) : null}
      {hover ? <PinTip pin={hover.pin} x={hover.x} y={hover.y} width={size.w} /> : null}
    </div>
  )
}

function centreOn(p: { x: number; y: number }, f: View, size: { w: number; h: number }, zoom: number): View {
  const s = f.s * zoom
  return { s, tx: size.w / 2 - p.x * s, ty: size.h / 2 - p.y * s }
}

function drawHere(ctx: CanvasRenderingContext2D, x: number, y: number, px: number, phase: number) {
  ctx.strokeStyle = `rgba(255, 255, 255, ${0.7 * (1 - phase)})`
  ctx.lineWidth = 2 * px
  ctx.beginPath()
  ctx.arc(x, y, (6 + 14 * phase) * px, 0, Math.PI * 2)
  ctx.stroke()
  ctx.fillStyle = '#ffffff'
  ctx.strokeStyle = '#070b12'
  ctx.lineWidth = 2 * px
  ctx.beginPath()
  ctx.arc(x, y, 4.5 * px, 0, Math.PI * 2)
  ctx.fill()
  ctx.stroke()
}

function cross(ctx: CanvasRenderingContext2D, x: number, y: number, r: number, color: string, hot: boolean) {
  ctx.fillStyle = color
  ctx.strokeStyle = '#070b12'
  ctx.lineWidth = r * 0.35
  ctx.beginPath()
  ctx.arc(x, y, hot ? r * 1.35 : r, 0, Math.PI * 2)
  ctx.fill()
  ctx.stroke()
  ctx.strokeStyle = '#ffffff'
  ctx.lineWidth = r * 0.32
  const k = r * 0.48
  ctx.beginPath()
  ctx.moveTo(x - k, y - k)
  ctx.lineTo(x + k, y + k)
  ctx.moveTo(x + k, y - k)
  ctx.lineTo(x - k, y + k)
  ctx.stroke()
}

function diamond(ctx: CanvasRenderingContext2D, x: number, y: number, r: number, color: string) {
  ctx.fillStyle = color
  ctx.strokeStyle = '#070b12'
  ctx.lineWidth = r * 0.35
  ctx.beginPath()
  ctx.moveTo(x, y - r)
  ctx.lineTo(x + r, y)
  ctx.lineTo(x, y + r)
  ctx.lineTo(x - r, y)
  ctx.closePath()
  ctx.fill()
  ctx.stroke()
}

function triangle(ctx: CanvasRenderingContext2D, x: number, y: number, r: number, color: string) {
  ctx.fillStyle = color
  ctx.strokeStyle = '#070b12'
  ctx.lineWidth = r * 0.35
  ctx.beginPath()
  ctx.moveTo(x, y - r)
  ctx.lineTo(x + r * 0.9, y + r * 0.7)
  ctx.lineTo(x - r * 0.9, y + r * 0.7)
  ctx.closePath()
  ctx.fill()
  ctx.stroke()
}

/** Details of a death or kill. */
function PinTip({ pin, x, y, width }: { pin: PlacedPin; x: number; y: number; width: number }) {
  const left = x > width - 220
  return (
    <div className={`overhead-tip${left ? ' left' : ''}`} style={{ left: left ? x - 14 : x + 14, top: y - 10 }} role="status">
      <div>
        <b>{pin.kind === 'death' ? 'Died' : 'Kill'}</b> <span className="muted">{gameClock(pin.t)} · life {pin.life + 1}</span>
      </div>
      {pin.area ? <div>at {pin.area}</div> : null}
      {pin.weapon || pin.range ? (
        <div className="muted">
          {[pin.weapon, pin.range ? `${pin.range} range` : null].filter(Boolean).join(' · ')}
        </div>
      ) : null}
      {pin.kind === 'death' && pin.killer ? (
        <div className="muted">
          killer {pin.killer.from === 'killcam' ? 'where the killcam ended' : `at ${pin.killer.spot ?? 'the callout'} (roughly)`}
          {pin.killer.from === 'killcam' && pin.killer.spot ? ` · ${pin.killer.spot}` : ''}
        </div>
      ) : null}
    </div>
  )
}
