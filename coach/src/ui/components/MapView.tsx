// The map, as learned from watching. With a fixed minimap: the map stitched
// from the player's own minimap, overhead and in 3D, with every death, kill
// and killer pinned to the spot (see MapOverhead and MapScene3D). By name:
// areas placed from the pack's rough layout and pulled together by the
// routes the player actually took, sized by time spent, coloured by kills
// against deaths, with the angles that killed them drawn as red arrows; "3D"
// lifts each area to its height layer in an isometric view. With the map's
// official overhead map, all of it is drawn on that instead (see OfficialSource).

import { lazy, Suspense, useEffect, useMemo, useRef, useState } from 'react'
import type { OverheadInfo } from '../../../shared/bridge.ts'
import type { MapReport } from '../../core/engine.ts'
import { findMap, layoutMap, mapLines, mapModelFrom, mapsPlayed, type MapMatchRecord, type MapModel } from '../../core/analyses/mapModel.ts'
import type { MatchPins } from '../../core/map/locator.ts'
import { fromBase64, type StoredMosaic } from '../../core/map/minimap.ts'
import type { Alignment } from '../../core/map/official.ts'
import { mapInsights, mapLayers, officialPoint, resolvePoint, storedAlignment, type OfficialBase } from '../../core/map/view.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import { gameClock } from '../format.ts'
import type { OverheadAnalysis } from '../overhead.ts'
import type { ShownMosaic } from '../session.ts'
import { Segmented } from './common.tsx'
import { LAYERS, MapOverhead, stylizedImage, type Layer } from './MapOverhead.tsx'

/** Official overhead maps for the map views (see CoachSession). */
export interface OfficialSource {
  /** Kept images, by map id. */
  readonly kept: Readonly<Record<string, OverheadInfo>>
  readonly load: (mapId: string) => Promise<{ url: string; analysis: OverheadAnalysis } | null>
  /** Places a saved stitched map on the official map after the fact (not while capturing). */
  readonly place?: (mosaic: ShownMosaic, mapId: string) => Promise<number>
  /** Saves a placement adjusted by hand (not while capturing). */
  readonly adjust?: (mosaic: ShownMosaic, piece: number, a: Alignment) => Promise<void>
}

/** A map's official picture, loaded and decoded, with its analysis. */
function useOfficialPicture(official: OfficialSource | undefined, mapId: string | null): { base: OfficialBase; picture: HTMLImageElement } | null {
  const savedAt = mapId ? (official?.kept[mapId]?.savedAt ?? null) : null
  const [pic, setPic] = useState<{ key: string; base: OfficialBase; picture: HTMLImageElement } | null>(null)
  const key = mapId && savedAt ? `${mapId}@${savedAt}` : null
  useEffect(() => {
    if (!key || !official || !mapId) return
    let live = true
    void official.load(mapId).then(async (r) => {
      if (!r || !live) return
      const img = new Image()
      img.src = r.url
      await img.decode().catch(() => undefined)
      if (live && img.naturalWidth) setPic({ key, base: r.analysis, picture: img })
    })
    return () => {
      live = false
    }
  }, [key])
  return pic && pic.key === key ? pic : null
}

const W = 640
const H = 420
const LAYER_H = 38

function project(x: number, y: number, z: number, iso: boolean): [number, number] {
  if (!iso) return [24 + x * (W - 48), 20 + y * (H - 40)]
  // Isometric: the map's plane tilted, each height layer lifted.
  const ix = (x - y) * 0.5 + 0.5
  const iy = (x + y) * 0.32 + 0.12
  return [24 + ix * (W - 48), 40 + iy * (H - 60) - z * LAYER_H]
}

/** The learned map for one map (with a picker when several were played). */
export function MapPanel({
  profile,
  records,
  live,
  mosaics,
  official,
}: {
  profile: GameProfile
  records: readonly MapMatchRecord[]
  live?: MapReport | null
  /** Maps stitched from the minimap, by map name (lower case). */
  mosaics?: Readonly<Record<string, ShownMosaic>>
  /** Official overhead maps the player kept. */
  official?: OfficialSource
}) {
  const played = useMemo(() => mapsPlayed(records, profile.knowledge.maps), [records, profile])
  const names = useMemo(() => {
    const out = played.map((p) => p.name)
    for (const m of Object.values(mosaics ?? {})) {
      const n = findMap(profile.knowledge.maps, m.map)?.name ?? m.map
      if (!out.includes(n)) out.unshift(n)
    }
    for (const m of profile.knowledge.maps ?? []) if (!out.includes(m.name)) out.push(m.name)
    return out
  }, [played, profile, mosaics])
  const [chosen, setChosen] = useState<string | null>(null)
  const name = chosen ?? live?.model?.map ?? live?.minimap?.map ?? played[0]?.name ?? names[0] ?? null
  const model = useMemo(() => (name ? (live?.model?.map === name ? live.model : mapModelFrom(records, profile.knowledge.maps, name)) : null), [name, live, records, profile])
  if (!names.length) return <p className="muted small">No map seen yet. With cloud vision on, the coach reads the map name and where you are on it each look.</p>
  const stitched = name ? stitchedFor(mosaics, profile, name) : null
  return (
    <div className="map-panel">
      <div className="learned-filters">
        <label className="field-inline">
          <span className="label">Map</span>
          <select value={name ?? ''} onChange={(e) => setChosen(e.target.value)}>
            {names.map((n) => {
              const p = played.find((x) => x.name === n)
              const has = !!stitchedFor(mosaics, profile, n)
              return (
                <option key={n} value={n}>
                  {n}
                  {p ? ` (${p.matches} ${p.matches === 1 ? 'game' : 'games'})` : ''}
                  {has ? ' · mapped' : ''}
                </option>
              )
            })}
          </select>
        </label>
      </div>
      {name && stitched ? (
        <StitchedMap
          key={name}
          name={name}
          mosaic={stitched}
          profile={profile}
          records={records}
          live={live?.minimap?.map && sameMap(profile, live.minimap.map, name) ? live : null}
          official={official}
        />
      ) : name && official && officialFor(official, profile, name) ? (
        <OfficialPreview name={name} mapId={officialFor(official, profile, name)!} official={official} />
      ) : name ? (
        <p className="muted small map-howto">
          No overhead map of {name} yet. Play it with your minimap set to <b>fixed</b> (north up) and cloud vision on: the coach stitches the map from your minimap as you move,
          and pins where you die to the spot. Until then, the areas below are a sketch from the pack and your routes.
        </p>
      ) : null}
      {model ? (
        stitched ? (
          <details className="map-areas">
            <summary className="small">Areas and routes, by name</summary>
            <MapModelView model={model} here={live?.model?.map === model.map && live.position.ageMs < 20_000 ? live.position.area : null} fit={live?.model?.map === model.map ? live.fit : []} />
          </details>
        ) : (
          <MapModelView model={model} here={live?.model?.map === model.map && live.position.ageMs < 20_000 ? live.position.area : null} fit={live?.model?.map === model.map ? live.fit : []} />
        )
      ) : null}
    </div>
  )
}

function sameMap(profile: GameProfile, a: string, b: string): boolean {
  const n = (x: string) => (findMap(profile.knowledge.maps, x)?.name ?? x).toLowerCase()
  return n(a) === n(b)
}

function officialFor(official: OfficialSource, profile: GameProfile, name: string): string | null {
  const id = findMap(profile.knowledge.maps, name)?.id
  return id && official.kept[id] ? id : null
}

/** A map's official overhead map before it was played with a fixed minimap. */
function OfficialPreview({ name, mapId, official }: { name: string; mapId: string; official: OfficialSource }) {
  const pic = useOfficialPicture(official, mapId)
  const empty = useMemo<StoredMosaic>(() => ({ version: 1, map: name, updatedAt: '', pieces: [], joined: {}, nextId: 1, callouts: {}, heights: {} }), [name])
  const layers = useMemo(() => (pic ? mapLayers(empty, [], pic.base) : null), [pic, empty])
  if (!pic || !layers) return <p className="muted small">Loading the official map of {name}…</p>
  return (
    <div className="stitched">
      <MapOverhead layers={layers} show={new Set()} picture={pic.picture} label={`${name}, official overhead map`} />
      <p className="muted small">
        {name}’s official overhead map. Play it with your minimap set to <b>fixed</b> (north up): the coach places what your minimap shows on this map by itself, and pins
        where you die, get kills and where killers were on it.
      </p>
    </div>
  )
}

function stitchedFor(mosaics: Readonly<Record<string, ShownMosaic>> | undefined, profile: GameProfile, name: string): ShownMosaic | null {
  if (!mosaics) return null
  return Object.values(mosaics).find((m) => sameMap(profile, m.map, name) && m.pieces.length > 0) ?? null
}

const Scene3D = lazy(() => import('./MapScene3D.tsx'))

const DEFAULT_LAYERS: readonly Layer[] = ['deaths', 'kills', 'killers', 'paths', 'heat', 'callouts']

/** The overhead and 3D maps stitched from the minimap (or the official map they are placed on), with this map's matches on them. */
function StitchedMap({
  name,
  mosaic,
  profile,
  records,
  live,
  official,
}: {
  name: string
  mosaic: ShownMosaic
  profile: GameProfile
  records: readonly MapMatchRecord[]
  live: MapReport | null
  official?: OfficialSource
}) {
  // The matches on this map with pins (oldest first), then the one being played.
  // While playing, the live pins are taken again only when something was added (not on every report).
  const livePins = live?.pins && sameMap(profile, live.pins.map, name) ? live.pins : null
  const liveKey = livePins ? `${livePins.pins.length}:${Math.floor(livePins.trail.length / 20)}:${Object.keys(livePins.enemies).length}` : ''
  const matches = useMemo(() => {
    const out: MatchPins[] = records.filter((r) => r.pins && sameMap(profile, r.map, name)).map((r) => r.pins!)
    if (livePins) out.push(livePins)
    return out
  }, [records, liveKey, profile, name])
  const mapId = findMap(profile.knowledge.maps, name)?.id ?? null
  const pic = useOfficialPicture(official, mapId)
  const [basis, setBasis] = useState<'official' | 'stitched'>('official')
  const onOfficial = basis === 'official' && pic ? pic : null
  const layers = useMemo(() => mapLayers(mosaic, matches, onOfficial?.base ?? null), [mosaic, matches, onOfficial])
  // A map stitched before its official map was kept (or placed on another picture) is placed on it once, here.
  const [placing, setPlacing] = useState(false)
  const tried = useRef('')
  const placedHere = !!pic && mosaic.alignedTo?.id === pic.base.id && Object.keys(mosaic.aligned ?? {}).length > 0
  useEffect(() => {
    if (!pic || !mapId || !official?.place || mosaic.synthetic || live || placedHere) return
    const k = `${mosaic.map}:${pic.base.id}`
    if (tried.current === k) return
    tried.current = k
    setPlacing(true)
    void official.place(mosaic, mapId).finally(() => setPlacing(false))
  }, [pic, mapId, official, mosaic, live, placedHere])
  // Checking the placement: the stitched map laid over the official one, nudged by hand if need be.
  const [check, setCheck] = useState(false)
  const overlay = useMemo(() => {
    if (!check || !onOfficial) return null
    const k = onOfficial.base.k
    return mosaic.pieces.flatMap((p) => {
      const a = storedAlignment(mosaic, p.id, onOfficial.base.id)
      if (!a) return []
      const canvas = stylizedImage({ piece: p.id, x0: p.x0, y0: p.y0, w: p.w, h: p.h, lum: fromBase64(p.mean), weight: fromBase64(p.weight) })
      return [{ canvas, m: [a.s * k, 0, 0, a.s * k, (a.s * p.x0 + a.tx) * k, (a.s * p.y0 + a.ty) * k] as const }]
    })
  }, [check, onOfficial, mosaic])
  const mainPlaced = onOfficial ? [...mosaic.pieces].sort((a, b) => b.w * b.h - a.w * a.h).find((p) => mosaic.aligned?.[String(p.id)]) : undefined
  const nudge = (dx: number, dy: number, ds = 1) => {
    if (!mainPlaced || !onOfficial || !official?.adjust) return
    const a = mosaic.aligned![String(mainPlaced.id)]
    const k = onOfficial.base.k
    // Scaled about the piece's middle; moved by working pixels.
    const cx = mainPlaced.x0 + mainPlaced.w / 2
    const cy = mainPlaced.y0 + mainPlaced.h / 2
    const s = a.s * ds
    void official.adjust(mosaic, mainPlaced.id, { ...a, s, tx: a.tx + (a.s - s) * cx + dx / k, ty: a.ty + (a.s - s) * cy + dy / k, manual: true })
  }
  const [view, setView] = useState<'overhead' | '3d'>('overhead')
  const [scope, setScope] = useState<'last' | 'all'>(live?.pins ? 'last' : 'all')
  const [show, setShow] = useState<ReadonlySet<Layer>>(() => new Set(DEFAULT_LAYERS))
  const [playT, setPlayT] = useState<number | null>(null)
  const [playing, setPlaying] = useState(false)
  const focus = scope === 'last' && matches.length ? matches.length - 1 : null
  const here = useMemo(() => {
    const h = live?.minimap?.here
    if (!h || !layers || (live?.minimap?.ageMs ?? Infinity) > 5000) return null
    if (layers.official) return officialPoint(mosaic, layers.official.base, h.piece, h.x, h.y)
    const r = resolvePoint(mosaic, h.piece, h.x, h.y)
    return r.piece === layers.image.piece ? { x: r.x - layers.image.x0, y: r.y - layers.image.y0 } : null
  }, [live, layers, mosaic])
  // Playback of one match.
  const endT = useMemo(() => {
    if (!layers || focus === null) return 0
    const ts = [...layers.pins.filter((p) => p.match === focus).map((p) => p.t), ...layers.paths.filter((p) => p.match === focus).flatMap((p) => p.points.map((q) => q[2]))]
    return ts.length ? Math.max(...ts) : 0
  }, [layers, focus])
  useEffect(() => {
    if (!playing) return
    let raf = 0
    let last = performance.now()
    const step = (now: number) => {
      const dt = (now - last) / 1000
      last = now
      setPlayT((t) => {
        const next = (t ?? 0) + dt * Math.max(8, endT / 40)
        if (next >= endT) {
          setPlaying(false)
          return endT
        }
        return next
      })
      raf = requestAnimationFrame(step)
    }
    raf = requestAnimationFrame(step)
    return () => cancelAnimationFrame(raf)
  }, [playing, endT])
  useEffect(() => {
    setPlayT(null)
    setPlaying(false)
  }, [scope, view])
  if (!layers) return null
  const inScope = layers.pins.filter((p) => focus === null || p.match === focus)
  const deaths = inScope.filter((p) => p.kind === 'death')
  const kills = inScope.filter((p) => p.kind === 'kill')
  const killcam = deaths.filter((p) => p.killer?.from === 'killcam').length
  const label = layers.official ? `${name}, official overhead map` : `${name}, stitched from your minimap`
  const off = layers.official
  const share = off && off.placed + off.unplaced ? off.placed / (off.placed + off.unplaced) : null
  const toggle = (l: Layer) => {
    const next = new Set(show)
    if (next.has(l)) next.delete(l)
    else next.add(l)
    setShow(next)
  }
  return (
    <div className="stitched">
      <div className="stitched-bar">
        {pic ? (
          <Segmented
            label="Map"
            value={basis}
            onChange={(b) => {
              setBasis(b)
              setCheck(false)
            }}
            options={[
              { value: 'official', label: 'Official' },
              { value: 'stitched', label: 'Stitched' },
            ]}
          />
        ) : null}
        <Segmented
          label="View"
          value={view}
          onChange={setView}
          options={[
            { value: 'overhead', label: 'Overhead' },
            { value: '3d', label: '3D' },
          ]}
        />
        {matches.length ? (
          <Segmented
            label="Matches"
            value={scope}
            onChange={setScope}
            options={[
              { value: 'last', label: live?.pins ? 'This match' : 'Last match' },
              { value: 'all', label: `All ${matches.length} ${matches.length === 1 ? 'match' : 'matches'}` },
            ]}
          />
        ) : null}
        <div className="layer-chips" role="group" aria-label="Show on the map">
          {LAYERS.map((l) => (
            <button key={l.id} type="button" className={`chip ${l.id}`} aria-pressed={show.has(l.id)} onClick={() => toggle(l.id)}>
              <span className="chip-mark" aria-hidden="true" />
              {l.label}
            </button>
          ))}
        </div>
      </div>
      {view === 'overhead' ? (
        <MapOverhead layers={layers} show={show} here={here} focus={focus} playT={playT} label={label} picture={onOfficial?.picture ?? null} overlay={overlay} />
      ) : (
        <Suspense fallback={<p className="muted small">Loading 3D…</p>}>
          <Scene3D layers={layers} show={show} here={here} focus={focus} label={label} picture={onOfficial?.picture ?? null} />
        </Suspense>
      )}
      {off ? (
        <div className="official-status small">
          <span className={`badge ${share !== null && share >= 0.95 ? 'ok' : share ? 'warn' : 'neutral'}`}>
            {placing ? 'Placing…' : share === null ? 'Official map' : `${Math.round(share * 100)}% placed`}
          </span>
          <span>
            {placing
              ? 'Placing your stitched map on the official map (about a second a piece)…'
              : share === null
                ? 'Nothing played on this map yet.'
                : off.unplaced
                  ? `${off.placed} of your points are on the official map; ${off.unplaced} are on parts not placed yet (a look-alike part stays unplaced until you walk somewhere only one part has). The Stitched view shows them all.`
                  : 'Every point is on the official map. While playing, each frame is pinned to it.'}
          </span>
          {view === 'overhead' && mosaic.pieces.length ? (
            <button type="button" className="btn sm ghost" aria-pressed={check} onClick={() => setCheck(!check)}>
              {check ? 'Done checking' : 'Check placement'}
            </button>
          ) : null}
        </div>
      ) : null}
      {check && off ? (
        <div className="official-check small">
          <span>Your stitched map is laid over the official one: its walls should sit on the official walls.</span>
          {mainPlaced && official?.adjust && !live && !mosaic.synthetic ? (
            <span className="btn-row" role="group" aria-label="Nudge the placement">
              <button type="button" className="btn sm ghost" aria-label="Move left" onClick={(e) => nudge(e.shiftKey ? -5 : -1, 0)}>
                ←
              </button>
              <button type="button" className="btn sm ghost" aria-label="Move up" onClick={(e) => nudge(0, e.shiftKey ? -5 : -1)}>
                ↑
              </button>
              <button type="button" className="btn sm ghost" aria-label="Move down" onClick={(e) => nudge(0, e.shiftKey ? 5 : 1)}>
                ↓
              </button>
              <button type="button" className="btn sm ghost" aria-label="Move right" onClick={(e) => nudge(e.shiftKey ? 5 : 1, 0)}>
                →
              </button>
              <button type="button" className="btn sm ghost" aria-label="Smaller" onClick={() => nudge(0, 0, 1 / 1.005)}>
                −
              </button>
              <button type="button" className="btn sm ghost" aria-label="Larger" onClick={() => nudge(0, 0, 1.005)}>
                +
              </button>
              <span className="muted">Shift: 5 px. Kept as your placement.</span>
            </span>
          ) : (
            <span className="muted">{live ? 'Adjusting is possible after the session.' : ''}</span>
          )}
        </div>
      ) : null}
      {view === 'overhead' && focus !== null && endT > 0 ? (
        <div className="playback">
          <button
            type="button"
            className="btn sm"
            onClick={() => {
              if (playing) setPlaying(false)
              else {
                if (playT === null || playT >= endT) setPlayT(0)
                setPlaying(true)
              }
            }}
          >
            {playing ? 'Pause' : playT === null ? 'Play the match' : 'Play'}
          </button>
          <input
            type="range"
            min={0}
            max={Math.ceil(endT)}
            step={1}
            value={Math.round(playT ?? endT)}
            aria-label="Match time"
            onChange={(e) => {
              setPlaying(false)
              setPlayT(Number(e.target.value))
            }}
          />
          <span className="small mono">
            {gameClock(playT ?? endT)} / {gameClock(endT)}
          </span>
          {playT !== null ? (
            <button type="button" className="btn sm ghost" onClick={() => setPlayT(null)}>
              Show all
            </button>
          ) : null}
        </div>
      ) : null}
      {(() => {
        const lines = mapInsights(layers, focus)
        return lines.length ? (
          <ul className="small map-insights">
            {lines.map((l) => (
              <li key={l}>{l}</li>
            ))}
          </ul>
        ) : null
      })()}
      <div className="stitched-legend small">
        <span>
          <i className="lg death" aria-hidden="true" /> death
        </span>
        <span>
          <i className="lg kill" aria-hidden="true" /> kill
        </span>
        <span>
          <i className="lg killer" aria-hidden="true" /> killer and line of fire (dashed: roughly, from the callout)
        </span>
        <span>
          <i className="lg path" aria-hidden="true" /> your path
        </span>
        <span>
          <i className="lg enemy" aria-hidden="true" /> enemy pings
        </span>
        {here ? (
          <span>
            <i className="lg here" aria-hidden="true" /> you, now
          </span>
        ) : null}
      </div>
      <p className="muted small">
        {mosaic.synthetic ? 'A synthetic map from the demo (not saved). ' : ''}
        {off ? 'On the official overhead map, from your minimap' : 'Stitched from your minimap'}
        {layers.matches ? ` over ${layers.matches} ${layers.matches === 1 ? 'match' : 'matches'}` : ''}: {deaths.length} deaths and {kills.length} kills
        placed{deaths.length ? `, ${killcam} killers seen on the killcam’s minimap` : ''}. Where you died is where your own minimap was just before it cut away.
        {!off && mosaic.pieces.length > 1 ? ` ${mosaic.pieces.length - 1} more piece${mosaic.pieces.length > 2 ? 's' : ''} of this map not joined yet (walk between them).` : ''}
      </p>
      {deaths.length ? (
        <details className="map-deaths">
          <summary className="small">Every death on this map ({deaths.length})</summary>
          <table className="table small">
            <thead>
              <tr>
                <th>Time</th>
                <th>Where</th>
                <th>Killer</th>
                <th>Weapon</th>
                <th>Range</th>
              </tr>
            </thead>
            <tbody>
              {deaths.map((d, i) => (
                <tr key={`${d.match}-${d.t}-${i}`}>
                  <td className="mono">
                    {matches.length > 1 && focus === null ? `#${d.match + 1} ` : ''}
                    {gameClock(d.t)}
                  </td>
                  <td>{d.area ?? '—'}</td>
                  <td>{d.killer ? `${d.killer.spot ?? 'seen'}${d.killer.from === 'killcam' ? ' (killcam)' : ' (roughly)'}` : '—'}</td>
                  <td>{d.weapon ?? '—'}</td>
                  <td>{d.range ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </details>
      ) : null}
    </div>
  )
}

function MapModelView({ model, here, fit }: { model: MapModel; here: string | null; fit: readonly string[] }) {
  const [view, setView] = useState<'2d' | '3d'>('2d')
  const iso = view === '3d'
  const laid = useMemo(() => layoutMap(model), [model])
  const at = new Map(laid.map((a) => [a.name, a]))
  const areas = new Map(model.areas.map((a) => [a.name, a]))
  const maxSec = Math.max(1, ...model.areas.map((a) => a.seconds))
  const learned = model.areas.some((a) => a.visits || a.deaths || a.kills)
  const lines = mapLines(model)
  const pt = (n: string) => {
    const a = at.get(n)!
    return project(a.x, a.y, a.z, iso)
  }
  const links = new Set<string>()
  const edges: { a: string; b: string; w: number; ref: boolean }[] = []
  for (const [a, b] of model.reference?.connections ?? []) {
    const k = [a, b].sort().join('|')
    if (!links.has(k) && at.has(a) && at.has(b)) (links.add(k), edges.push({ a, b, w: 1, ref: true }))
  }
  for (const r of model.routes) {
    const k = [r.from, r.to].sort().join('|')
    const e = edges.find((x) => [x.a, x.b].sort().join('|') === k)
    if (e) e.w += r.n
    else if (at.has(r.from) && at.has(r.to)) (links.add(k), edges.push({ a: r.from, b: r.to, w: r.n, ref: false }))
  }
  return (
    <div className="map-model">
      <div className="map-head">
        <p className="small" style={{ margin: 0 }}>
          <b>{model.map}</b>
          {model.matches ? ` · learned from ${model.matches} ${model.matches === 1 ? 'game' : 'games'}` : ' · not played yet'}
          {model.reference ? ` · ${model.reference.callouts.length} callouts from the pack` : ' · areas named from what you saw'}
        </p>
        <Segmented label="View" value={view} onChange={setView} options={[{ value: '2d', label: 'Top' }, { value: '3d', label: '3D (heights)' }]} />
      </div>
      <svg className="map-svg" viewBox={`0 0 ${W} ${H}`} role="img" aria-label={`Learned map of ${model.map}`}>
        <defs>
          <marker id="arrow-red" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
            <path d="M0,0 L10,5 L0,10 z" className="map-arrow" />
          </marker>
        </defs>
        {iso
          ? // The ground plane, so heights read as heights.
            (() => {
              const c = [project(0, 0, 0, true), project(1, 0, 0, true), project(1, 1, 0, true), project(0, 1, 0, true)]
              return <polygon className="map-ground" points={c.map((p) => p.join(',')).join(' ')} />
            })()
          : null}
        {iso
          ? laid
              .filter((a) => a.z !== 0)
              .map((a) => {
                const [x0, y0] = project(a.x, a.y, 0, true)
                const [x1, y1] = project(a.x, a.y, a.z, true)
                return <line key={`stem-${a.name}`} className="map-stem" x1={x0} y1={y0} x2={x1} y2={y1} />
              })
          : null}
        {edges.map((e) => {
          const [x1, y1] = pt(e.a)
          const [x2, y2] = pt(e.b)
          return <line key={`${e.a}|${e.b}`} className={e.ref ? 'map-link ref' : 'map-link'} x1={x1} y1={y1} x2={x2} y2={y2} strokeWidth={Math.min(6, 1 + e.w * 0.6)} />
        })}
        {model.sightlines.slice(0, 8).map((s) => {
          if (!at.has(s.from) || !at.has(s.to)) return null
          const [x1, y1] = pt(s.from)
          const [x2, y2] = pt(s.to)
          return <line key={`sl-${s.from}-${s.to}`} className="map-sightline" x1={x1} y1={y1} x2={x2} y2={y2} strokeWidth={Math.min(5, 1 + s.n)} markerEnd="url(#arrow-red)" />
        })}
        {model.reference?.powerPositions?.map((p) => {
          if (!at.has(p.callout)) return null
          const [x, y] = pt(p.callout)
          return <circle key={`pp-${p.callout}`} className="map-power" cx={x} cy={y} r={16} />
        })}
        {[...laid]
          .sort((a, b) => a.z - b.z || a.y - b.y)
          .map((a) => {
            const s = areas.get(a.name)!
            const [x, y] = pt(a.name)
            const r = s.seconds ? 5 + 11 * Math.sqrt(s.seconds / maxSec) : s.visits || s.deaths || s.kills ? 6 : 4
            const tone = s.deaths + s.kills === 0 ? 'none' : s.kills > s.deaths ? 'good' : s.deaths > s.kills ? 'bad' : 'even'
            return (
              <g key={a.name} className={`map-node ${tone}${a.name === here ? ' here' : ''}${!s.visits && !s.deaths && !s.kills ? ' unseen' : ''}`}>
                <circle cx={x} cy={y} r={r}>
                  <title>
                    {`${a.name}${s.elevations.length ? ` (${s.elevations.join(', ')})` : ''}\n${s.seconds}s · ${s.kills} kills · ${s.deaths} deaths · enemies seen ${s.enemies}${s.ref?.use ? `\n${s.ref.use}` : ''}`}
                  </title>
                </circle>
                <text x={x} y={y - r - 4} textAnchor="middle">
                  {a.name}
                  {a.z > 0 ? ' ▲'.repeat(a.z) : a.z < 0 ? ' ▼' : ''}
                </text>
              </g>
            )
          })}
      </svg>
      <p className="muted small map-legend">
        Size: time spent · green: more kills than deaths · red: more deaths · <span className="legend-red">red arrows</span>: where killers shot from ·
        rings: the pack’s power positions · lines: links and the routes you took{iso ? ' · ▲ upper floor or roof' : ''}. Positions are a sketch from the pack’s rough
        layout and your routes, not a survey.
      </p>
      {!learned ? <p className="muted small">Not learned yet: play this map with cloud vision on.</p> : null}
      {lines.length || fit.length ? (
        <ul className="small">
          {[...lines, ...fit].map((l) => (
            <li key={l}>{l}</li>
          ))}
        </ul>
      ) : null}
      {learned ? (
        <table className="table small map-table">
          <thead>
            <tr>
              <th>Area</th>
              <th>Time</th>
              <th>Kills</th>
              <th>Deaths</th>
              <th>Enemies seen</th>
              <th>Heights</th>
            </tr>
          </thead>
          <tbody>
            {model.areas
              .filter((a) => a.visits || a.deaths || a.kills)
              .slice(0, 12)
              .map((a) => (
                <tr key={a.name} className={a.name === here ? 'here' : undefined}>
                  <td>{a.name}</td>
                  <td>{a.seconds}s</td>
                  <td>{a.kills}</td>
                  <td>{a.deaths}</td>
                  <td>{a.enemies}</td>
                  <td>{a.elevations.join(', ')}</td>
                </tr>
              ))}
          </tbody>
        </table>
      ) : null}
      {model.reference ? <MapReference model={model} /> : null}
    </div>
  )
}

function MapReference({ model }: { model: MapModel }) {
  const r = model.reference!
  const o = r.objectives
  return (
    <details className="map-ref">
      <summary className="small">
        <b>{r.name}</b> reference: layout, power positions, objectives
      </summary>
      {r.layout ? <p className="small">{r.layout}</p> : null}
      {r.powerPositions?.length ? (
        <ul className="small">
          {r.powerPositions.map((p) => (
            <li key={p.callout}>
              <b>{p.callout}</b>: {p.why}
              {p.counter ? <span className="muted"> Counter: {p.counter}</span> : null}
            </li>
          ))}
        </ul>
      ) : null}
      {o?.hardpoint?.length ? (
        <p className="small">
          <span className="label">Hardpoint</span> {o.hardpoint.join(' → ')}
        </p>
      ) : null}
      {o?.domination ? (
        <p className="small">
          <span className="label">Domination</span> {Object.entries(o.domination).map(([k, v]) => `${k}: ${v}`).join(' · ')}
        </p>
      ) : null}
      {o?.searchAndDestroy ? (
        <p className="small">
          <span className="label">S&amp;D</span> {Object.entries(o.searchAndDestroy).map(([k, v]) => `${k}: ${v}`).join(' · ')}
        </p>
      ) : null}
      {r.weaponFit ? (
        <p className="small">
          <span className="label">Weapons</span> {r.weaponFit}
        </p>
      ) : null}
    </details>
  )
}

/** The live card: where the player is now and what is known about it, with the stitched map around them. */
export function WhereYouAre({ report, mosaics, profile, official }: { report: MapReport; mosaics?: Readonly<Record<string, ShownMosaic>>; profile?: GameProfile; official?: OfficialSource }) {
  const p = report.position
  const mm = report.minimap
  const mosaic = mm?.map ? (Object.values(mosaics ?? {}).find((m) => m.map.toLowerCase() === mm.map!.toLowerCase()) ?? null) : null
  const mapId = mm?.map && profile ? (findMap(profile.knowledge.maps, mm.map)?.id ?? null) : null
  const pic = useOfficialPicture(official, mapId)
  const layers = useMemo(() => {
    if (!mosaic) return null
    const pins = report.pins ? [report.pins] : []
    // On the official map once anything of this match is placed on it.
    const onOfficial = pic ? mapLayers(mosaic, pins, pic.base) : null
    return onOfficial?.official?.placed ? onOfficial : mapLayers(mosaic, pins)
  }, [mosaic, report.pins, pic])
  const here = useMemo(() => {
    if (!mm?.here || !layers || !mosaic || mm.ageMs > 5000) return null
    if (layers.official) return officialPoint(mosaic, layers.official.base, mm.here.piece, mm.here.x, mm.here.y)
    const r = resolvePoint(mosaic, mm.here.piece, mm.here.x, mm.here.y)
    return r.piece === layers.image.piece ? { x: r.x - layers.image.x0, y: r.y - layers.image.y0 } : null
  }, [mm, layers, mosaic])
  const show = useMemo(() => new Set<Layer>(['deaths', 'kills', 'killers', 'paths']), [])
  if (!p.map && !p.area && !mm?.map) return <p className="muted small">The map and where you are show here once a look has read them.</p>
  const area = report.model?.areas.find((a) => a.name === p.area)
  const from = report.model?.sightlines.find((s) => s.to === p.area)
  const fresh = p.ageMs < 20_000
  return (
    <div className="where">
      {layers ? <MapOverhead layers={layers} show={show} here={here} focus={0} compact label={`Where you are on ${mosaic!.map}`} picture={layers.official ? (pic?.picture ?? null) : null} /> : null}
      <p className="where-now">
        {p.area && fresh ? (
          <>
            <b>{p.area}</b>
            {p.elevation && p.elevation !== 'ground' ? <span className="badge neutral">{p.elevation}</span> : null}
          </>
        ) : (
          <span className="muted">Position not read in the last 20 s</span>
        )}
        {p.map ? <span className="muted"> · {p.map}</span> : null}
      </p>
      {mm ? <p className="small muted">{minimapLine(mm)}</p> : null}
      {area && fresh && (area.deaths || area.kills) ? (
        <p className="small">
          Here before: {area.kills} kills, {area.deaths} deaths
          {from && from.n >= 2 ? `; ${from.n} of those deaths from ${from.from}` : ''}.
        </p>
      ) : null}
      {area?.ref?.use && fresh ? <p className="small muted">{area.ref.use}</p> : null}
    </div>
  )
}

/** How the minimap is being followed, in a few words. */
function minimapLine(mm: NonNullable<MapReport['minimap']>): string {
  if (!mm.enabled) return mm.reason === 'rotating' ? 'Your minimap rotates: deaths are placed by area. Set it to fixed (north up) for a stitched map and exact spots.' : 'The minimap is not being followed.'
  if (mm.state === 'tracking' || mm.state === 'found' || mm.state === 'new') return `Following your minimap (match ${Math.round(mm.ncc * 100)}%)${mm.map ? '' : ': waiting for the map’s name'}.`
  if (mm.ageMs < 10_000) return 'Minimap out of view for a moment (a killcam, a menu).'
  return 'No minimap found where it should be. If your HUD is moved or scaled, align the minimap in Settings.'
}
