// The DayZ map: Bohemia Interactive's own top-down image of the terrain
// (from its Central Economy tool), with the pack's places and features drawn
// over it. Drag to pan, scroll or +/− to zoom, click a spot to see its grid,
// the nearest town and water, and to mark yourself there.

import { useEffect, useMemo, useRef, useState, type KeyboardEvent, type PointerEvent, type WheelEvent } from 'react'
import { distance, type DayzMap } from '../../core/dayz/places.ts'
import type { Travel } from '../../core/dayz/terrain.ts'
import { groundTime, tripTime } from '../../core/dayz/travel.ts'
import { isFaded, MARK_INFO, MARK_KINDS, markLine, whereWords, type Mark, type MarkKind } from '../../core/dayz/marks.ts'
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
  /** A route to draw: two places, or a loot run's start, numbered stops and finish. */
  readonly route: readonly Pos[] | null
  /** Where the player has been: towns from road signs read, life by life, and where each life ended. */
  readonly trail?: readonly TrailLine[]
  readonly onSetHere: (p: Pos) => void
  /** Plot routes over the ground (roads, fields, round water) instead of straight lines; null: straight. */
  readonly follow?: FollowGround | null
  /** Pins to show (places where an item spawns, results), fitted into view. */
  readonly markers?: readonly Pos[]
  /** A smaller map with fewer controls (the companion's screen). */
  readonly compact?: boolean
  /** The player's own marks (sightings, base, stash, car…) on this map. */
  readonly marks?: readonly Mark[]
  /** Placing a mark: the next click puts it there (label says what). */
  readonly placing?: string | null
  readonly onPlace?: (p: Pos) => void
  /** Mark a picked spot as one of the kinds. */
  readonly onMarkAt?: (p: Pos, kind: MarkKind) => void
}

/** A route over the ground, leg by leg (null where there is no way), its length and share on roads. */
export type FollowGround = (points: readonly { x: number; y: number }[]) => { legs: (readonly { x: number; y: number }[] | null)[]; lengthM: number; road: number; travel: Travel }

/** One life's trail on the map. */
export interface TrailLine {
  readonly id: string
  readonly points: readonly Pos[]
  /** Where the life ended (the last place read), when it ended in death. */
  readonly died: Pos | null
  /** The current life (drawn brighter). */
  readonly current: boolean
}

export function DayzMapCanvas({ map, here, focus, route, trail, onSetHere, follow, markers, compact, marks, placing, onPlace, onMarkAt }: Props) {
  const now = Date.now()
  const box = useRef<HTMLDivElement>(null)
  const [size, setSize] = useState({ width: 800, height: 600 })
  const [view, setView] = useState<MapViewState>(() => fullView(map))
  const [layers, setLayers] = useState<ReadonlySet<LayerId>>(() => new Set(DEFAULT_LAYERS))
  const [picked, setPicked] = useState<Pos | null>(null)
  const drag = useRef<{ x: number; y: number; view: MapViewState; moved: boolean } | null>(null)
  // Drawing your own route: each click adds a point.
  const [drawing, setDrawing] = useState(false)
  const [drawn, setDrawn] = useState<readonly Pos[]>([])

  // A new map: the whole of it.
  useEffect(() => {
    setView(fullView(map))
    setPicked(null)
    setDrawn([])
  }, [map])
  useEffect(() => {
    if (focus) setView(focusOn(map, focus, focus.label.startsWith('grid') ? 800 : 2400))
  }, [focus, map])
  useEffect(() => {
    if (!route?.length) return
    // Fit the whole route.
    const xs = route.map((p) => p.x)
    const ys = route.map((p) => p.y)
    const spanX = Math.max(...xs) - Math.min(...xs)
    const spanY = Math.max(...ys) - Math.min(...ys)
    setView(clampView(map, { cx: (Math.max(...xs) + Math.min(...xs)) / 2, cy: (Math.max(...ys) + Math.min(...ys)) / 2, span: Math.max(1200, spanY * 1.3, (spanX * 1.3 * size.height) / size.width) }))
    // Bring the map into view, so the drawn route is seen.
    box.current?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' })
  }, [route, map])
  // Pins: all of them in view (with the player's spot when there is one).
  useEffect(() => {
    if (!markers?.length) return
    const pts = [...markers, ...(here ? [here] : [])]
    const xs = pts.map((p) => p.x)
    const ys = pts.map((p) => p.y)
    const spanX = Math.max(...xs) - Math.min(...xs)
    const spanY = Math.max(...ys) - Math.min(...ys)
    setView(clampView(map, { cx: (Math.max(...xs) + Math.min(...xs)) / 2, cy: (Math.max(...ys) + Math.min(...ys)) / 2, span: Math.max(1500, spanY * 1.35, (spanX * 1.35 * size.height) / size.width) }))
  }, [markers, map])
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
      if (placing && onPlace) onPlace({ ...at, label: whereWords(map, at) })
      else if (drawing) setDrawn((ds) => [...ds, { ...at, label: pointInfo(map, at).grid }])
      else setPicked({ ...at, label: pointInfo(map, at).grid })
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
  // Routes over the ground (when on): each leg's path, else the straight line.
  const routeLegs = useMemo(() => (route && route.length > 1 && follow ? follow(route) : null), [route, follow])
  const drawnLegs = useMemo(() => (drawn.length > 1 && follow ? follow(drawn) : null), [drawn, follow])
  const legLine = (legs: ReturnType<FollowGround> | null, pts: readonly Pos[], i: number) => legs?.legs[i] ?? [pts[i], pts[i + 1]]
  const legLength = (legs: ReturnType<FollowGround> | null, pts: readonly Pos[], i: number) => {
    const l = legs?.legs[i]
    return l ? l.slice(1).reduce((s, p, k) => s + distance(l[k], p), 0) : distance(pts[i], pts[i + 1])
  }
  const places = useMemo(() => [...map.places].sort((a, b) => rank(b.kind) - rank(a.kind)), [map])
  const info = picked ? pointInfo(map, picked) : null

  return (
    <div className={`dz-map-wrap${compact ? ' compact' : ''}`}>
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
        <button type="button" className={`btn sm${drawing ? ' primary' : ''}`} aria-pressed={drawing} onClick={() => setDrawing((d) => !d)}>
          {drawing ? 'Drawing: click to add points' : 'Draw a route'}
        </button>
        {drawn.length ? (
          <>
            <button type="button" className="btn sm ghost" onClick={() => setDrawn((ds) => ds.slice(0, -1))}>
              Undo
            </button>
            <button type="button" className="btn sm ghost" onClick={() => setDrawn([])}>
              Clear drawing
            </button>
          </>
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

          {trail?.map((t) => (
            <g key={t.id} className={`dz-trail${t.current ? ' current' : ''}`}>
              {t.points.length > 1 ? <polyline points={t.points.map((p) => `${p.x},${sy(p.y)}`).join(' ')} strokeWidth={2.5 * px} strokeDasharray={`${3 * px} ${4 * px}`} /> : null}
              {t.points.map((p, i) => (
                <circle key={i} cx={p.x} cy={sy(p.y)} r={3.5 * px} strokeWidth={px} />
              ))}
              {t.died ? (
                <g className="dz-died">
                  <path d={cross(t.died.x, sy(t.died.y), 7 * px, true)} strokeWidth={3 * px} />
                  <text x={t.died.x + 9 * px} y={sy(t.died.y) - 6 * px} fontSize={11 * px} strokeWidth={3 * px}>
                    died near {t.died.label}
                  </text>
                </g>
              ) : null}
            </g>
          ))}

          {route?.length ? (
            <g className="dz-route">
              {route.slice(0, -1).map((_, i) => {
                const line = legLine(routeLegs, route, i)
                return <polyline key={`l${i}`} className={routeLegs?.legs[i] ? 'ground' : ''} points={line.map((p) => `${p.x},${sy(p.y)}`).join(' ')} strokeWidth={3 * px} strokeDasharray={routeLegs?.legs[i] ? undefined : `${8 * px} ${5 * px}`} />
              })}
              {route.slice(0, -1).map((_, i) => {
                // The arrow sits halfway along the leg's own line.
                const line = legLine(routeLegs, route, i)
                const k = Math.max(0, Math.floor((line.length - 1) / 2))
                const p = line[k]
                const q = line[Math.min(line.length - 1, k + 1)]
                const angle = (Math.atan2(sy(q.y) - sy(p.y), q.x - p.x) * 180) / Math.PI
                return <path key={`a${i}`} className="dz-arrow" d={`M${-6 * px},${-5 * px}L${4 * px},0L${-6 * px},${5 * px}`} transform={`translate(${(p.x + q.x) / 2} ${(sy(p.y) + sy(q.y)) / 2}) rotate(${angle})`} strokeWidth={2.5 * px} />
              })}
              {route.map((p, i) => (
                <g key={i} className="dz-stop">
                  <circle cx={p.x} cy={sy(p.y)} r={(i === 0 ? 7 : 9) * px} strokeWidth={2 * px} />
                  {i > 0 && /^\d/.test(p.label) ? (
                    <text x={p.x} y={sy(p.y) + 3.5 * px} fontSize={10 * px} textAnchor="middle" className="dz-stop-n">
                      {p.label.split(' ')[0]}
                    </text>
                  ) : null}
                  {p.label && !(show('towns') && map.places.some((pl) => pl.name === p.label)) ? (
                    <text x={p.x + 11 * px} y={sy(p.y) - 8 * px} fontSize={12 * px} strokeWidth={3 * px} className="dz-stop-label">
                      {p.label.replace(/^\d+ /, '')}
                    </text>
                  ) : null}
                </g>
              ))}
            </g>
          ) : null}

          {drawn.length ? (
            <g className="dz-drawn">
              {drawn.slice(1).map((_, i) => (
                <polyline key={`dl${i}`} points={legLine(drawnLegs, drawn, i).map((p) => `${p.x},${sy(p.y)}`).join(' ')} strokeWidth={3 * px} />
              ))}
              {drawn.slice(1).map((_, i) => {
                const line = legLine(drawnLegs, drawn, i)
                const mid = line[Math.floor(line.length / 2)]
                return (
                  <text key={`d${i}`} x={mid.x + 6 * px} y={sy(mid.y) - 6 * px} fontSize={11 * px} strokeWidth={3 * px}>
                    {scaleText(legLength(drawnLegs, drawn, i))}
                  </text>
                )
              })}
              {drawn.map((p, i) => (
                <circle key={i} cx={p.x} cy={sy(p.y)} r={5 * px} strokeWidth={2 * px} />
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

          {markers?.map((m, i) => (
            <g key={`pin${i}`} className="dz-pin">
              <path d={`M${m.x},${sy(m.y)} l${-5 * px},${-12 * px} a${5.5 * px},${5.5 * px} 0 1 1 ${10 * px},0 z`} strokeWidth={1.5 * px} />
              <text x={m.x + 8 * px} y={sy(m.y) - 10 * px} fontSize={11 * px} strokeWidth={3 * px}>
                {m.label}
              </text>
            </g>
          ))}

          {marks
            ?.filter((m) => m.map === map.id)
            .map((m) => (
              <g key={m.id} className={`dz-mark dz-mark-${m.kind}${isFaded(m, now) ? ' faded' : ''}`} data-kind={m.kind}>
                <title>{markLine(m, now)}</title>
                <circle cx={m.x} cy={sy(m.y)} r={8 * px} strokeWidth={2 * px} />
                <text x={m.x} y={sy(m.y) + 3.8 * px} fontSize={10 * px} textAnchor="middle" className="dz-mark-glyph">
                  {MARK_INFO[m.kind].glyph}
                </text>
                {view.span <= (compact ? 9000 : 7000) && !route?.some((p) => Math.abs(p.x - m.x) < 30 && Math.abs(p.y - m.y) < 30) ? (
                  <text x={m.x + 11 * px} y={sy(m.y) + 4 * px} fontSize={11 * px} strokeWidth={3 * px} className="dz-mark-label">
                    {m.label}
                  </text>
                ) : null}
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
        {placing ? (
          <span className="dz-placing">
            <b>Click the map to mark: {placing}.</b> Drag to move; scroll to zoom.
          </span>
        ) : drawn.length ? (
          <span>
            Your route: {drawn.length} point{drawn.length === 1 ? '' : 's'}, {scaleText(drawn.slice(1).reduce((s, _, i) => s + legLength(drawnLegs, drawn, i), 0))} in all
            {drawnLegs ? ` over the ground (${Math.round(drawnLegs.road * 100)}% on roads, ${groundTime(drawnLegs, drawnLegs.travel)})` : drawn.length > 1 ? `, ${tripTime(drawn.slice(1).reduce((s, _, i) => s + legLength(null, drawn, i), 0))}` : ''}
            {drawn.length > 1 ? `, last leg ${scaleText(legLength(drawnLegs, drawn, drawn.length - 2))}` : ''}. {drawing ? 'Click to add more; drag to move the map.' : ''}
          </span>
        ) : info && picked ? (
          <>
            <span>{info.line}</span>{' '}
            <button type="button" className="btn sm" onClick={() => onSetHere({ x: picked.x, y: picked.y, label: `grid ${info.grid}` })}>
              I’m here
            </button>{' '}
            {onMarkAt ? (
              <select
                className="sm"
                aria-label="Mark this spot"
                value=""
                onChange={(e) => {
                  if (e.target.value) onMarkAt({ x: picked.x, y: picked.y, label: whereWords(map, picked) }, e.target.value as MarkKind)
                  setPicked(null)
                }}
              >
                <option value="">Mark as…</option>
                {MARK_KINDS.filter((k) => k !== 'body').map((k) => (
                  <option key={k} value={k}>
                    {MARK_INFO[k].label}
                  </option>
                ))}
              </select>
            ) : null}{' '}
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
