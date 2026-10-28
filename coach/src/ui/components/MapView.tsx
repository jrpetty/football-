// The map, as learned from watching: areas placed from the pack's rough
// layout and pulled together by the routes the player actually took, sized by
// time spent, coloured by kills against deaths, with the angles that killed
// them drawn as red arrows. "3D" lifts each area to its height layer
// (underground, ground, upper floor, roof) in an isometric view.

import { useMemo, useState } from 'react'
import type { MapReport } from '../../core/engine.ts'
import { layoutMap, mapLines, mapModelFrom, mapsPlayed, type MapMatchRecord, type MapModel } from '../../core/analyses/mapModel.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import { Segmented } from './common.tsx'

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
export function MapPanel({ profile, records, live }: { profile: GameProfile; records: readonly MapMatchRecord[]; live?: MapReport | null }) {
  const played = useMemo(() => mapsPlayed(records, profile.knowledge.maps), [records, profile])
  const names = useMemo(() => {
    const out = played.map((p) => p.name)
    for (const m of profile.knowledge.maps ?? []) if (!out.includes(m.name)) out.push(m.name)
    return out
  }, [played, profile])
  const [chosen, setChosen] = useState<string | null>(null)
  const name = chosen ?? live?.model?.map ?? played[0]?.name ?? names[0] ?? null
  const model = useMemo(() => (name ? (live?.model?.map === name ? live.model : mapModelFrom(records, profile.knowledge.maps, name)) : null), [name, live, records, profile])
  if (!names.length) return <p className="muted small">No map seen yet. With cloud vision on, the coach reads the map name and where you are on it each look.</p>
  return (
    <div className="map-panel">
      <div className="learned-filters">
        <label className="field-inline">
          <span className="label">Map</span>
          <select value={name ?? ''} onChange={(e) => setChosen(e.target.value)}>
            {names.map((n) => {
              const p = played.find((x) => x.name === n)
              return (
                <option key={n} value={n}>
                  {n}
                  {p ? ` (${p.matches} ${p.matches === 1 ? 'game' : 'games'})` : ''}
                </option>
              )
            })}
          </select>
        </label>
      </div>
      {model ? <MapModelView model={model} here={live?.model?.map === model.map && live.position.ageMs < 20_000 ? live.position.area : null} fit={live?.model?.map === model.map ? live.fit : []} /> : null}
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

/** The live card: where the player is now and what is known about it. */
export function WhereYouAre({ report }: { report: MapReport }) {
  const p = report.position
  if (!p.map && !p.area) return <p className="muted small">The map and where you are show here once a look has read them.</p>
  const area = report.model?.areas.find((a) => a.name === p.area)
  const from = report.model?.sightlines.find((s) => s.to === p.area)
  const fresh = p.ageMs < 20_000
  return (
    <div className="where">
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
