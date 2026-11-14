// DayZ: the map. Bohemia Interactive's top-down image of Chernarus, Livonia
// or Sakhal with towns, water pumps, medical, military, crash spots and gas
// zones. Find a town or grid, draw the way between two, and see where you
// are: a spot you set yourself (any time), or the last road sign the coach
// read off your screen (live where coaching is allowed; on public servers
// after the life or the session, like "where am I?").

import { useEffect, useMemo, useState, type FormEvent } from 'react'
import type { ResolvedPolicy } from '../../core/policy/registry.ts'
import type { CoachSettings } from '../../core/settings.ts'
import { DAYZ_MAPS, describePlace, findPlace, placesIn, routeLine, type DayzMapId } from '../../core/dayz/places.ts'
import { mapById, parsePosition, pointInfo } from '../../core/dayz/mapView.ts'
import { mapPreference } from '../../core/dayz/ask.ts'
import { DayzMapCanvas, type Pos } from '../components/dayzMap.tsx'
import type { SessionState } from '../session.ts'

export type MapHere = Pos & { readonly map: DayzMapId }
export type MapFocus = { readonly text: string; readonly nonce: number }

interface Props {
  state: SessionState
  settings: CoachSettings
  policy: ResolvedPolicy
  capturing: boolean
  /** The spot the player set (kept while the app is open). */
  here: MapHere | null
  onHere: (h: MapHere | null) => void
  /** A place or route asked for elsewhere ("Show on map" on the Ask screen). */
  focus: MapFocus | null
}

export function DayzMapView({ state, settings, policy, capturing, here, onHere, focus }: Props) {
  const context = state.report?.profileId === 'dayz' ? state.report.context : {}
  const chosen = mapPreference({ ...(settings.context.dayz ?? {}), ...context }) ?? 'chernarus'
  const [mapId, setMapId] = useState<DayzMapId>(chosen)
  const map = mapById(DAYZ_MAPS, mapId)
  const [query, setQuery] = useState('')
  const [result, setResult] = useState<{ text: string; focus: (Pos & { nonce: number }) | null; route: readonly [Pos, Pos] | null } | null>(null)
  const [hereText, setHereText] = useState('')
  const [hereError, setHereError] = useState<string | null>(null)

  // The last road sign read: live where coaching is allowed, otherwise once play has stopped.
  const sign = typeof context.roadSign === 'string' ? context.roadSign : null
  const signAllowed = policy.scope.has('live') || !capturing
  const signPlace = sign ? findPlace(sign, mapId) : null
  const fromSign = signPlace && signPlace.map.id === mapId ? { x: signPlace.place.x, y: signPlace.place.y, label: `${signPlace.place.name} (last sign read)`, source: 'sign' as const } : null
  const mine = here && here.map === mapId ? { ...here, source: 'you' as const } : null
  const shown = mine ?? (signAllowed ? fromSign : null)

  const search = (text: string, onMap: DayzMapId = mapId) => {
    const t = text.trim()
    if (!t) return
    const map = mapById(DAYZ_MAPS, onMap)
    const nonce = Date.now()
    // "Cherno to NWAF": the way between two.
    const two = placesIn(t, onMap).filter((p) => p.map.id === onMap)
    if (two.length >= 2 && /\b(to|from|->|and)\b|→/.test(t.toLowerCase())) {
      const [a, b] = two
      setResult({ text: routeLine(a, b), focus: null, route: [pos(a.place), pos(b.place)] })
      return
    }
    const p = parsePosition(map, t)
    if (!p) {
      const elsewhere = findPlace(t)
      setResult({ text: elsewhere ? `${elsewhere.place.name} is on ${elsewhere.map.name}. Pick that map above.` : `No place or grid "${t}" on ${map.name}. Try a town (any spelling, e.g. Typobo), or a grid like 135 012.`, focus: null, route: null })
      return
    }
    const place = findPlace(t, onMap)
    const text_ = place && place.map.id === onMap && p.label === place.place.name ? describePlace(place) : pointInfo(map, p).line
    setResult({ text: text_, focus: { ...p, nonce }, route: null })
  }

  // "Show on map" from the Ask screen: the map the place is on, then the place (or the way between two).
  useEffect(() => {
    if (!focus) return
    // The places the question names: one is shown, two are the way between them.
    const named = placesIn(focus.text, mapId)
    const onMap = named[0]?.map.id ?? mapId
    const text = named.length >= 2 ? `${named[0].place.name} to ${named[1].place.name}` : named[0]?.place.name ?? focus.text
    setMapId(onMap)
    setQuery(text)
    search(text, onMap)
    // Only when a new request arrives.
  }, [focus?.nonce])

  const setHere = (e: FormEvent) => {
    e.preventDefault()
    const p = parsePosition(map, hereText)
    if (!p) {
      setHereError(`Not a town or grid on ${map.name}. Try "Turovo" or "135 012".`)
      return
    }
    setHereError(null)
    onHere({ ...p, map: mapId })
    setHereText('')
  }

  const hereLine = useMemo(() => (shown ? pointInfo(map, shown).line : null), [shown, map])

  return (
    <div className="page">
      <div>
        <h1>Map</h1>
        <p className="lede">
          {map.name} from Bohemia Interactive’s own top-down map, with the towns, water pumps, medical, military, crash spots and gas zones the
          coach knows. Find a town (any spelling, e.g. Typobo) or a grid, draw the way between two, and mark where you are.
        </p>
      </div>

      <section className="panel dz-panel" aria-labelledby="dz-map-h">
        <div className="panel-head">
          <h2 id="dz-map-h">{map.name}</h2>
          <span className="segmented" role="radiogroup" aria-label="Map">
            {DAYZ_MAPS.map((m) => (
              <button
                key={m.id}
                type="button"
                role="radio"
                aria-checked={m.id === mapId}
                onClick={() => {
                  setMapId(m.id)
                  setResult(null)
                }}
              >
                {m.name}
              </button>
            ))}
          </span>
        </div>

        <form
          className="dz-search"
          onSubmit={(e) => {
            e.preventDefault()
            search(query)
          }}
        >
          <input aria-label="Find a town, grid or route" placeholder="Find a town or grid, or a route: Cherno to NWAF" value={query} onChange={(e) => setQuery(e.target.value)} />
          <button type="submit" className="btn primary" disabled={!query.trim()}>
            Find
          </button>
        </form>
        {result ? <p className="dz-result small">{result.text}</p> : null}

        <DayzMapCanvas map={map} here={shown} focus={result?.focus ?? null} route={result?.route ?? null} onSetHere={(p) => onHere({ ...p, map: mapId })} />
      </section>

      <section className="panel" aria-labelledby="dz-here-h">
        <div className="panel-head">
          <h2 id="dz-here-h">Where you are</h2>
          {mine ? (
            <button type="button" className="btn sm ghost" onClick={() => onHere(null)}>
              Forget my spot
            </button>
          ) : null}
        </div>
        {shown && hereLine ? (
          <p className="small">
            <strong>{shown.source === 'you' ? 'Your spot' : `From the road sign ${sign}`}:</strong> {hereLine}
            {shown.source === 'sign' ? ' Signs stand at the edge of a town, so this is the town, not your exact spot.' : ''}
          </p>
        ) : (
          <p className="muted small">Not set yet.</p>
        )}
        <form className="dz-search" onSubmit={setHere}>
          <input aria-label="Set where you are" placeholder="Set where you are: a town or a grid (135 012)" value={hereText} onChange={(e) => setHereText(e.target.value)} />
          <button type="submit" className="btn" disabled={!hereText.trim()}>
            Set
          </button>
        </form>
        {hereError ? <p className="small warn-text">{hereError}</p> : null}
        <p className="muted small">
          {policy.scope.has('live')
            ? 'Offline the coach also places you at the last road sign it reads, as you play.'
            : sign && capturing
              ? 'On public servers the spot from road signs read off your screen shows after the life or the session; a spot you set yourself shows any time.'
              : 'A spot you set yourself shows any time; the spot from road signs read off your screen shows live offline, and after the life or the session on public servers.'}{' '}
          DayZ has no position on its own map, so the coach knows where you are from a sign, or from you.
        </p>
      </section>

      <p className="muted small">
        Map image: Bohemia Interactive, DayZ Central Economy tool (ADPL-SA, non-commercial). Places: DayZ Wiki (CC BY-NC-SA). © Bohemia Interactive a.s.
        DAYZ® is a registered trademark of Bohemia Interactive a.s.
      </p>
    </div>
  )
}

const pos = (p: { x: number; y: number; name: string }): Pos => ({ x: p.x, y: p.y, label: p.name })
