// DayZ: the map. Bohemia Interactive's top-down image of Chernarus, Livonia
// or Sakhal with towns, water pumps, medical, military, crash spots and gas
// zones. Find a town or grid, draw the way between two, and see where you
// are: a spot you set yourself (any time), or the last road sign the coach
// read off your screen (live where coaching is allowed; on public servers
// after the life or the session, like "where am I?").

import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { assistantAnswer, assistantRoute, isAssistantQuestion, KIT } from '../../core/dayz/assistant.ts'
import type { ResolvedPolicy } from '../../core/policy/registry.ts'
import type { CoachSettings } from '../../core/settings.ts'
import { DAYZ_MAPS, describePlace, findPlace, placesIn, routeLine, type DayzMapId } from '../../core/dayz/places.ts'
import { mapById, parsePosition, pointInfo } from '../../core/dayz/mapView.ts'
import { mapPreference } from '../../core/dayz/ask.ts'
import { groundTime, TIME_NOTE } from '../../core/dayz/travel.ts'
import { isLootRun, lootRunLine, lootRunPath, parseLootRun, planLootRun } from '../../core/dayz/lootRun.ts'
import { lifeLine } from '../../core/dayz/trail.ts'
import type { Travel } from '../../core/dayz/terrain.ts'
import { useFollowGround } from '../components/dayzGround.ts'
import { DayzMapCanvas, type Pos, type TrailLine } from '../components/dayzMap.tsx'

import type { CoachSession, SessionState } from '../session.ts'

export type MapHere = Pos & { readonly map: DayzMapId }
export type MapFocus = { readonly text: string; readonly nonce: number }

interface Props {
  state: SessionState
  settings: CoachSettings
  policy: ResolvedPolicy
  capturing: boolean
  /** The spot and kit live in the session (shared with Ask and voice). */
  session: CoachSession
  /** A place or route asked for elsewhere ("Show on map" on the Ask screen). */
  focus: MapFocus | null
}

export function DayzMapView({ state, settings, policy, capturing, session, focus }: Props) {
  const here = state.spot
  const onHere = (h: MapHere | null) => session.setSpot(h)
  const context = state.report?.profileId === 'dayz' ? state.report.context : {}
  const chosen = mapPreference({ ...(settings.context.dayz ?? {}), ...context }) ?? 'chernarus'
  const [mapId, setMapId] = useState<DayzMapId>(chosen)
  const map = mapById(DAYZ_MAPS, mapId)
  const [query, setQuery] = useState('')
  const [result, setResult] = useState<{ text: string; focus: (Pos & { nonce: number }) | null; route: readonly Pos[] | null } | null>(null)
  const [hereText, setHereText] = useState('')
  // Routes over the ground: on foot (roads, fields, round water), by car (roads), or straight lines.
  const [travel, setTravel] = useState<Travel | 'straight'>('foot')
  const follow = useFollowGround(map, travel)
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
    // The assistant (Chernarus): what now, take me to, where an item spawns, from your spot and kit.
    if (isAssistantQuestion(t)) {
      if (onMap !== 'chernarus') setMapId('chernarus')
      const spotHere = here?.map === 'chernarus' ? here : null
      const plan = assistantRoute(t, spotHere, new Set(state.kit))
      const text = assistantAnswer(t, { ...(spotHere ? { spotX: spotHere.x, spotY: spotHere.y, spotLabel: spotHere.label } : {}), kit: state.kit.join(',') }) ?? ''
      setResult({ text, focus: null, route: plan?.route.length ? plan.route : null })
      return
    }
    // A loot run: the shortest way through one of each kind of place asked for, drawn through numbered stops.
    if (isLootRun(t)) {
      const here = signAllowed && signPlace && signPlace.map.id === onMap ? signPlace : null
      const req = parseLootRun(t, onMap, here)
      if (!req) {
        setResult({ text: 'Where from? Start with a town: "loot run from Berezino hitting a hospital, police and military".', focus: null, route: null })
        return
      }
      if (req.start.map.id !== onMap) setMapId(req.start.map.id)
      const plan = planLootRun(req)
      setResult({ text: lootRunLine(plan).replace(/ Open it on the Map to see the line\.$/, ''), focus: null, route: lootRunPath(plan) })
      return
    }
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
    // The places the question names: one is shown, two are the way between them; a loot run as asked.
    const named = placesIn(focus.text, mapId)
    const onMap = named[0]?.map.id ?? mapId
    const text = isLootRun(focus.text) || isAssistantQuestion(focus.text) ? focus.text : named.length >= 2 ? `${named[0].place.name} to ${named[1].place.name}` : named[0]?.place.name ?? focus.text
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

  // The trail: finished lives always; the current one live where coaching is allowed, or once play has stopped.
  const all = state.trail.map((life, i) => ({ life, i })).filter(({ life }) => life.map === mapId)
  const hiddenCurrent = all.some(({ life }) => !life.end) && !signAllowed
  const lives = all.filter(({ life }) => life.end || signAllowed)
  const trailLines: TrailLine[] = lives.map(({ life }) => ({
    id: life.id,
    points: life.stops.map((s) => ({ x: s.x, y: s.y, label: s.name })),
    died: life.end?.died ? (life.stops.at(-1) ? { x: life.stops.at(-1)!.x, y: life.stops.at(-1)!.y, label: life.end.near ?? '' } : null) : null,
    current: !life.end,
  }))

  // The route over the ground, in words.
  const groundLine = useMemo(() => {
    if (!follow || !result?.route || result.route.length < 2) return null
    const r = follow(result.route)
    const missing = r.legs.filter((l) => !l).length
    const how = travel === 'car' ? 'By car' : 'On foot'
    return `${how} over the ground: about ${(r.lengthM / 1000).toFixed(1)} km, ${Math.round(r.road * 100)}% on roads, ${groundTime(r, r.travel)}${missing ? `; ${missing === 1 ? 'one leg has' : `${missing} legs have`} no way by ${travel === 'car' ? 'car (water or forest)' : 'land'}, drawn straight` : ''}.`
  }, [follow, result, travel])

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
        <span className="segmented" role="radiogroup" aria-label="Routes">
          {(
            [
              ['foot', 'On foot'],
              ['car', 'By car'],
              ['straight', 'Straight lines'],
            ] as const
          ).map(([id, label]) => (
            <button key={id} type="button" role="radio" aria-checked={travel === id} onClick={() => setTravel(id)}>
              {label}
            </button>
          ))}
        </span>
        {result ? (
          <p className="dz-result small">
            {result.text}
            {groundLine ? ` ${groundLine}` : ''}
          </p>
        ) : null}

        <DayzMapCanvas map={map} here={shown} focus={result?.focus ?? null} route={result?.route ?? null} trail={trailLines} follow={follow} onSetHere={(p) => onHere({ ...p, map: mapId })} />
        <p className="muted small" style={{ margin: 0 }}>
          {travel === 'straight'
            ? 'Routes are straight lines.'
            : `Routes follow the ground: ${travel === 'car' ? 'roads first, open ground if needed, never forest, water or gas zones' : 'roads where they help, across fields freely, through forest when it saves enough, round lakes, the sea and gas zones'}. Read from Bohemia's map picture, so small paths and bridges can be missed${mapId === 'sakhal' ? "; Sakhal's roads barely show in its picture, so there routes mostly keep out of the sea and forest" : ''}.`}{' '}
          {TIME_NOTE}
        </p>
      </section>

      {mapId === 'chernarus' ? (
        <section className="panel" aria-labelledby="dz-asst-h">
          <div className="panel-head">
            <h2 id="dz-asst-h">Survival assistant</h2>
            <span className="muted small">Chernarus · from your spot and your kit</span>
          </div>
          <div className="btn-row">
            <button type="button" className="btn primary" onClick={() => search('what now?', 'chernarus')} disabled={!here || here.map !== 'chernarus'}>
              What now?
            </button>
            {(['water', 'hospital', 'medical', 'police', 'military'] as const).map((k) => (
              <button key={k} type="button" className="btn sm" onClick={() => search(`take me to the nearest ${k}`, 'chernarus')} disabled={!here || here.map !== 'chernarus'}>
                Nearest {k}
              </button>
            ))}
          </div>
          {!here || here.map !== 'chernarus' ? <p className="muted small">Set where you are first (below, or say “Coach, I'm at Turovo”).</p> : null}
          <fieldset className="dz-kit">
            <legend className="small">Your kit (tick what you have, or say “Coach, I found a knife”; it clears when you die)</legend>
            {KIT.map((k) => (
              <label key={k.id} className="small">
                <input type="checkbox" checked={state.kit.includes(k.id)} onChange={(e) => session.setKit(k.id, e.target.checked)} /> {k.label}
              </label>
            ))}
          </fieldset>
          <p className="muted small">
            Ask anything about loot too: “where do I find a canteen”, “where can I get 7.62x39 ammo”, “what spawns in police stations”, “take me to a place with morphine”. From Bohemia's loot table for Chernarus (building types, loot tiers, how many at a time); it says where things can spawn, not what is there now.
          </p>
        </section>
      ) : null}

      <section className="panel" aria-labelledby="dz-trail-h">
        <div className="panel-head">
          <h2 id="dz-trail-h">Where you have been</h2>
          <span className="muted small">From road signs read off your screen</span>
        </div>
        {lives.length ? (
          <ol className="dz-lives small">
            {lives.map(({ life, i }) => (
              <li key={life.id}>{lifeLine(life, i)}</li>
            ))}
          </ol>
        ) : (
          <p className="muted small">No signs read yet this session.</p>
        )}
        {hiddenCurrent ? <p className="muted small">On public servers this life's trail shows after it ends (or when you stop).</p> : null}
        <p className="muted small">
          Each life is a dotted line through the towns whose signs were read, with an ✕ where it ended in death (near the last sign read; DayZ shows no exact position). With screen reading set to look only when you ask, only signs from your questions are read, so the trail is sparse; a look every few seconds fills it in.
        </p>
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
