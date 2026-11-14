// The DayZ map: Bohemia Interactive's own top-down image of the terrain
// (from its Central Economy tool), with the pack's places and features drawn
// over it. Drag to pan, scroll or +/− to zoom, click a spot to see its grid,
// the nearest town and water, and to mark yourself there.

import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent, type WheelEvent } from 'react'
import { distance, type DayzMap } from '../../core/dayz/places.ts'
import { clampView, focusOn, fullView, gridLines, labelled, pointAt, pointInfo, viewBox, zoomAt, type MapViewState } from '../../core/dayz/mapView.ts'
import chernarusImg from '../assets/dayz/chernarus.jpg'
import livoniaImg from '../assets/dayz/livonia.jpg'
import sakhalImg from '../assets/dayz/sakhal.jpg'

const IMAGES: Readonly<Record<DayzMap['id'], string>> = { chernarus: chernarusImg, livonia: livoniaImg, sakhal: sakhalImg }

export type Pos = { readonly x: number; readonly y: number; readonly label: string }

export type LayerId = 'towns' | 'grid' | 'water' | 'medical' | 'military' | 'police' | 'fuel' | 'heliCrash' | 'contaminated'

export const LAYERS: readonly { id: LayerId; label: string }[] = [
  { id: 'towns', label: 'Towns' },
  { id: 'grid', label: 'Grid' },
  { id: 'water', label: 'Water pumps' },
  { id: 'medical', label: 'Medical' },
  { id: 'military', label: 'Military' },
  { id: 'police', label: 'Police' },
  { id: 'fuel', label: 'Fuel' },
  { id: 'heliCrash', label: 'Heli crash spots' },
  { id: 'contaminated', label: 'Gas zones' },
]

const DEFAULT_LAYERS: readonly LayerId[] = ['towns', 'grid', 'water', 'medical']

interface Props {
  readonly map: DayzMap
  /** Where the player is: set by them, or from the last road sign read (where that is allowed). */
  readonly here: (Pos & { readonly source: 'you' | 'sign' }) | null
  /** Fly here when it changes (a search, "Show on map"). */
  readonly focus: (Pos & { readonly nonce: number }) | null
  /** A straight line between two places. */
  readonly route: readonly [Pos, Pos] | null
  readonly onSetHere: (p: Pos) => void
}

export function DayzMapCanvas({ map, here, focus, route, onSetHere }: Props) {
  const box = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState({ width: 800, height: 600 })
  const [view, setView] = useState<MapViewState>(() => fullView(map))
  const [layers, setLayers] = useState<ReadonlySet<LayerId>>(() => new Set(DEFAULT_LAYERS))
  const [picked, setPicked] = useState<Pos | null>(null)
  const drag = useRef<{ x: number; y: number; view: MapViewState; moved: boolean } | null>(null)

  // A new map: the whole of it.
  useEffect(() => {
    setView(fullView(map))
    setPicked(null)
  }, [map])
  useEffect(() => {
    if (focus) setView(focusOn(map, focus, focus.label.startsWith('grid') ? 800 : 2400))
  }, [focus, map])
  useEffect(() => {
    if (!route) return
    const [a, b] = route
    setView(clampView(map, { cx: (a.x + b.x) / 2, cy: (a.y + b.y) / 2, span: Math.max(1500, distance(a, b) * 1.4) }))
  }, [route, map])
  useEffect(() => {
    const el = box.current
    if (!el) return
    const ro = new ResizeObserver(([e]) => setSize({ width: Math.max(200, e.contentRect.width), height: Math.max(200, e.contentRect.height) }))
    ro.observe(el)
    return () => ro.disconnect()
  }, [])

  const vb = viewBox(map, view, size.width / size.height)
  /** World metres per screen pixel: marks keep their size on screen at every zoom. */
  const px = view.span / size.height
  const sy = (y: number) => map.worldSize - y
  const ws = map.worldSize

  const local = (e: { clientX: number; clientY: number }) => {
    const r = box.current!.getBoundingClientRect()
    return { px: e.clientX - r.left, py: e.clientY - r.top }
  }
  const onWheel = (e: WheelEvent) => {
    const { px: x, py: y } = local(e)
    setView((v) => zoomAt(map, v, Math.pow(1.0015, -e.deltaY), pointAt(map, v, size, x, y)))
  }
  const onPointerDown = (e: PointerEvent) => {
    ;(e.target as Element).setPointerCapture?.(e.pointerId)
    drag.current = { x: e.clientX, y: e.clientY, view, moved: false }
  }
  const onPointerMove = (e: PointerEvent) => {
    const d = drag.current
    if (!d) return
    const dx = e.clientX - d.x
    const dy = e.clientY - d.y
    if (Math.abs(dx) + Math.abs(dy) > 4) d.moved = true
    if (d.moved) setView(clampView(map, { ...d.view, cx: d.view.cx - dx * (d.view.span / size.height), cy: d.view.cy + dy * (d.view.span / size.height) }))
  }
  const onPointerUp = (e: PointerEvent) => {
    const d = drag.current
    drag.current = null
    if (d && !d.moved) {
      const { px: x, py: y } = local(e)
      const at = pointAt(map, view, size, x, y)
      setPicked({ ...at, label: pointInfo(map, at).grid })
    }
  }
  const onKey = (e: KeyboardEvent) => {
    const step = view.span * 0.15
    const moves: Record<string, Partial<MapViewState>> = { ArrowLeft: { cx: view.cx - step }, ArrowRight: { cx: view.cx + step }, ArrowUp: { cy: view.cy + step }, ArrowDown: { cy: view.cy - step } }
    if (moves[e.key]) setView(clampView(map, { ...view, ...moves[e.key] }))
    else if (e.key === '+' || e.key === '=') setView(zoomAt(map, view, 1.5))
    else if (e.key === '-') setView(zoomAt(map, view, 1 / 1.5))
    else return
    e.preventDefault()
  }

  const show = (id: LayerId) => layers.has(id)
  const places = useMemo(() => [...map.places].sort((a, b) => rank(b.kind) - rank(a.kind)), [map])
  const info = picked ? pointInfo(map, picked) : null

  return (
    <div className="dz-map-wrap">
      <div className="dz-toolbar" role="toolbar" aria-label="Map controls">
        <button type="button" className="btn sm" onClick={() => setView(zoomAt(map, view, 1.6))} aria-label="Zoom in">
          +
        </button>
        <button type="button" className="btn sm" onClick={() => setView(zoomAt(map, view, 1 / 1.6))} aria-label="Zoom out">
          −
        </button>
        <button type="button" className="btn sm ghost" onClick={() => setView(fullView(map))}>
          Whole map
        </button>
        {here ? (
          <button type="button" className="btn sm primary" onClick={() => setView(focusOn(map, here, 1600))}>
            Zoom to me
          </button>
        ) : null}
        <span className="dz-scale muted small">{scaleText(view.span)} top to bottom</span>
        <span className="dz-layers">
          {LAYERS.map((l) => (
            <label key={l.id} className={`dz-layer dz-layer-${l.id}`}>
              <input
                type="checkbox"
                checked={layers.has(l.id)}
                onChange={() =>
                  setLayers((s) => {
                    const n = new Set(s)
                    if (n.has(l.id)) n.delete(l.id)
                    else n.add(l.id)
                    return n
                  })
                }
              />
              <span className="dz-swatch" aria-hidden="true" />
              {l.label}
            </label>
          ))}
        </span>
      </div>

      <div
        ref={box}
        className="dz-map"
        tabIndex={0}
        role="application"
        aria-label={`Map of ${map.name}. Drag to move, scroll or plus and minus to zoom, arrow keys to move, click a spot for its grid.`}
        onWheel={onWheel}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={onPointerUp}
        onDoubleClick={(e) => {
          const { px: x, py: y } = local(e)
          setView((v) => zoomAt(map, v, 2, pointAt(map, v, size, x, y)))
        }}
        onKeyDown={onKey}
      >
        <svg viewBox={`${vb.x} ${vb.y} ${vb.w} ${vb.h}`} width={size.width} height={size.height} preserveAspectRatio="xMidYMid meet">
          <rect x={-ws} y={-ws} width={ws * 3} height={ws * 3} className="dz-sea" />
          <image href={IMAGES[map.id]} x={0} y={0} width={ws} height={ws} preserveAspectRatio="none" />

          {show('grid') && (
            <g className="dz-grid" strokeWidth={px}>
              {gridLines(map).map((g) => (
                <g key={g.at}>
                  <line x1={g.at} y1={0} x2={g.at} y2={ws} />
                  <line x1={0} y1={g.at} x2={ws} y2={g.at} />
                  {view.span <= ws * 0.75 ? (
                    <>
                      <text x={g.at + 3 * px} y={vb.y + 12 * px} fontSize={10 * px}>
                        {g.label}
                      </text>
                      <text x={vb.x + 3 * px} y={g.at - 3 * px} fontSize={10 * px}>
                        {g.label}
                      </text>
                    </>
                  ) : null}
                </g>
              ))}
            </g>
          )}

          {show('contaminated') &&
            map.world.contaminated.map((z, i) => <circle key={`cz${i}`} className="dz-gas" cx={z.x} cy={sy(z.y)} r={Math.max(z.r, 4 * px)} strokeWidth={1.5 * px} />)}
          {show('military') && map.world.military.map(([x, y], i) => <rect key={`mi${i}`} className="dz-military" x={x - 3 * px} y={sy(y) - 3 * px} width={6 * px} height={6 * px} strokeWidth={px} />)}
          {show('police') && map.world.police.map(([x, y], i) => <rect key={`po${i}`} className="dz-police" x={x - 3 * px} y={sy(y) - 3 * px} width={6 * px} height={6 * px} transform={`rotate(45 ${x} ${sy(y)})`} strokeWidth={px} />)}
          {show('fuel') && map.world.fuel.map(([x, y], i) => <polygon key={`fu${i}`} className="dz-fuel" points={tri(x, sy(y), 5 * px)} strokeWidth={px} />)}
          {show('heliCrash') && map.world.heliCrash.map(([x, y], i) => <path key={`hc${i}`} className="dz-heli" d={cross(x, sy(y), 5 * px, true)} strokeWidth={2 * px} />)}
          {show('medical') && map.world.medical.map(([x, y], i) => <path key={`me${i}`} className="dz-medical" d={cross(x, sy(y), 5 * px, false)} strokeWidth={2.5 * px} />)}
          {show('water') && map.world.water.map(([x, y], i) => <circle key={`wa${i}`} className="dz-water" cx={x} cy={sy(y)} r={4 * px} strokeWidth={px} />)}

          {route ? (
            <g className="dz-route">
              <line x1={route[0].x} y1={sy(route[0].y)} x2={route[1].x} y2={sy(route[1].y)} strokeWidth={3 * px} strokeDasharray={`${8 * px} ${5 * px}`} />
              {route.map((p, i) => (
                <circle key={i} cx={p.x} cy={sy(p.y)} r={6 * px} strokeWidth={2 * px} />
              ))}
            </g>
          ) : null}

          {show('towns') &&
            places
              .filter((p) => labelled(p, view.span, ws))
              .map((p) => (
                <g key={p.name} className={`dz-place dz-place-${p.kind}`}>
                  <circle cx={p.x} cy={sy(p.y)} r={(p.kind === 'city' ? 4 : 3) * px} strokeWidth={px} />
                  <text x={p.x + 6 * px} y={sy(p.y) + 4 * px} fontSize={(p.kind === 'city' ? 14 : p.kind === 'town' || p.kind === 'military' || p.kind === 'airfield' ? 12 : 11) * px} strokeWidth={3 * px}>
                    {p.name}
                    {p.cyrillic && map.signs === 'cyrillic' && view.span <= 6000 ? ` · ${p.cyrillic}` : ''}
                  </text>
                </g>
              ))}

          {picked ? <path className="dz-picked" d={cross(picked.x, sy(picked.y), 7 * px, true)} strokeWidth={2.5 * px} /> : null}
          {here ? (
            <g className={`dz-here dz-here-${here.source}`}>
              {here.source === 'sign' ? <circle cx={here.x} cy={sy(here.y)} r={300} className="dz-here-area" /> : null}
              <circle cx={here.x} cy={sy(here.y)} r={8 * px} strokeWidth={3 * px} />
              <circle cx={here.x} cy={sy(here.y)} r={2.5 * px} className="dz-here-dot" />
            </g>
          ) : null}
        </svg>
      </div>

      <div className="dz-info small" aria-live="polite">
        {info && picked ? (
          <>
            <span>{info.line}</span>{' '}
            <button type="button" className="btn sm" onClick={() => onSetHere({ x: picked.x, y: picked.y, label: `grid ${info.grid}` })}>
              I’m here
            </button>{' '}
            <button type="button" className="btn sm ghost" onClick={() => setPicked(null)}>
              Clear
            </button>
          </>
        ) : (
          <span className="muted">Click a spot for its grid, the nearest town and water. Drag to move; scroll or + / − to zoom.</span>
        )}
      </div>
    </div>
  )
}

const RANKS: Readonly<Record<string, number>> = { city: 5, airfield: 4, military: 4, town: 3, village: 2 }
const rank = (kind: string) => RANKS[kind] ?? 1

function scaleText(span: number): string {
  return span >= 1000 ? `${(span / 1000).toFixed(span >= 10_000 ? 0 : 1)} km` : `${Math.round(span / 10) * 10} m`
}

function tri(x: number, y: number, r: number): string {
  return `${x},${y - r} ${x + r * 0.9},${y + r * 0.7} ${x - r * 0.9},${y + r * 0.7}`
}

/** A plus (medical) or an x (crash spot, a picked point). */
function cross(x: number, y: number, r: number, diagonal: boolean): string {
  return diagonal ? `M${x - r},${y - r}L${x + r},${y + r}M${x + r},${y - r}L${x - r},${y + r}` : `M${x - r},${y}L${x + r},${y}M${x},${y - r}L${x},${y + r}`
}
