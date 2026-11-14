// The companion's screen: whatever the last answer pulled up, at a glance. A
// map (a route, a place, pins where an item spawns), a place card, a gun card
// with its bullet drop, an item's spawns, a step-by-step plan, advice, the
// kit, a comparison or a list.

import { useState } from 'react'
import type { Card } from '../../core/dayz/companion.ts'
import { HELP_EXAMPLES } from '../../core/dayz/companion.ts'
import { KIT } from '../../core/dayz/assistant.ts'
import { DAYZ_MAPS, type DayzMapId } from '../../core/dayz/places.ts'
import type { Travel } from '../../core/dayz/terrain.ts'
import { DayzMapCanvas, type Pos } from './dayzMap.tsx'
import { useFollowGround } from './dayzGround.ts'
import type { CoachSession, SessionState } from '../session.ts'

interface Props {
  readonly card: Card | null
  readonly state: SessionState
  readonly session: CoachSession
  readonly onAsk: (q: string) => void
  readonly onOpenMap: (q: string) => void
  /** The question the card answered (to open it on the big map). */
  readonly question: string | null
}

export function CompanionScreen({ card, state, session, onAsk, onOpenMap, question }: Props) {
  if (!card) return <Welcome onAsk={onAsk} />
  switch (card.type) {
    case 'map':
      return (
        <Frame title={card.title} kicker="Map" onOpen={question ? () => onOpenMap(question) : undefined}>
          {card.caption ? <p className="small comp-caption">{card.caption}</p> : null}
          <ScreenMap mapId={card.map} state={state} session={session} route={card.route} focus={card.focus} markers={card.markers} />
        </Frame>
      )
    case 'place':
      return (
        <Frame title={card.title} kicker={card.subtitle} onOpen={() => onOpenMap(card.title)}>
          <Facts rows={card.facts} />
          <ScreenMap mapId={card.map} state={state} session={session} focus={card.focus} markers={card.markers} />
          <p className="small muted">{card.text}</p>
        </Frame>
      )
    case 'gun':
      return (
        <Frame title={card.title} kicker={card.subtitle}>
          {card.look ? <p className="small comp-look">{card.look}</p> : null}
          <Facts rows={card.stats} />
          {card.range.length ? (
            <table className="comp-table small">
              <caption>Where to aim</caption>
              <thead>
                <tr>
                  <th>Range</th>
                  <th>Aim</th>
                  <th>Speed</th>
                </tr>
              </thead>
              <tbody>
                {card.range.map((r) => (
                  <tr key={r.m}>
                    <td>{r.m} m</td>
                    <td>{r.hold}</td>
                    <td>{r.speed}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
          <Facts rows={card.where} title="Where it spawns" />
          {card.tips.length ? (
            <ul className="comp-tips small">
              {card.tips.map((t) => (
                <li key={t}>{t}</li>
              ))}
            </ul>
          ) : null}
        </Frame>
      )
    case 'item':
      return (
        <Frame title={card.title} kicker={card.subtitle}>
          <Facts rows={card.facts} />
          {card.nearest.length ? (
            <table className="comp-table small">
              <caption>{card.from ? `Nearest from ${card.from.label}` : 'Most of those buildings'}</caption>
              <thead>
                <tr>
                  <th>Place</th>
                  {card.from ? <th>Way</th> : null}
                  <th>Buildings</th>
                  <th>Tier</th>
                </tr>
              </thead>
              <tbody>
                {card.nearest.map((n) => (
                  <tr key={n.name}>
                    <td>
                      <button type="button" className="link-btn" onClick={() => onAsk(`take me to ${n.name}`)}>
                        {n.name}
                      </button>
                    </td>
                    {card.from ? <td>{n.where}</td> : null}
                    <td>{n.buildings}</td>
                    <td>{n.tier || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
          <ScreenMap mapId={card.map} state={state} session={session} markers={card.markers} />
          <p className="muted small">Where it can spawn, from Bohemia's loot table: not what is there now.</p>
        </Frame>
      )
    case 'plan':
      return (
        <Frame title={card.title} kicker="Your next steps" onOpen={question ? () => onOpenMap(question) : undefined}>
          <ol className="comp-steps">
            {card.steps.map((s) => (
              <li key={s.n}>
                <b>{s.title}</b>
                <span className="small">{s.line}</span>
              </li>
            ))}
          </ol>
          <ScreenMap mapId={card.map} state={state} session={session} route={card.route} />
          {card.missing.length ? <p className="small muted">Still missing: {card.missing.join(', ').toLowerCase()}. Say “I found …” as you go.</p> : null}
        </Frame>
      )
    case 'advice':
      return (
        <Frame title={card.title} kicker="Survival">
          {card.text ? <p className="small">{card.text}</p> : null}
          {card.fix ? (
            <div className="comp-fix">
              <b>Do this</b>
              <p className="small">{card.fix}</p>
            </div>
          ) : null}
          {card.why ? <p className="small muted">{card.why}</p> : null}
          {card.sources?.length ? <p className="muted small">Sources: {card.sources.map((s) => s.title).join('; ')}</p> : null}
        </Frame>
      )
    case 'kit':
      return (
        <Frame title="Your kit" kicker={`${card.have.length} of ${card.have.length + card.missing.length}`}>
          <div className="comp-kit">
            {KIT.map((k) => (
              <label key={k.id} className={`small${state.kit.includes(k.id) ? ' have' : ''}`}>
                <input type="checkbox" checked={state.kit.includes(k.id)} onChange={(e) => session.setKit(k.id, e.target.checked)} /> {k.label}
              </label>
            ))}
          </div>
          <button type="button" className="btn sm primary" onClick={() => onAsk('what now?')}>
            What now?
          </button>
        </Frame>
      )
    case 'list':
      return (
        <Frame title={card.title} kicker="List">
          <table className="comp-table small">
            <tbody>
              {card.rows.map(([a, b]) => (
                <tr key={a}>
                  <td>
                    <b>{a}</b>
                  </td>
                  <td>{b}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Frame>
      )
    case 'compare':
      return (
        <Frame title={card.title} kicker="Side by side">
          <table className="comp-table small">
            <thead>
              <tr>
                <th />
                <th>{card.columns[0]}</th>
                <th>{card.columns[1]}</th>
              </tr>
            </thead>
            <tbody>
              {card.rows.map(([label, a, b]) => (
                <tr key={label}>
                  <td className="muted">{label}</td>
                  <td>{a}</td>
                  <td>{b}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Frame>
      )
    case 'help':
      return <Welcome onAsk={onAsk} />
  }
}

function Frame(props: { title: string; kicker: string; onOpen?: () => void; children: React.ReactNode }) {
  return (
    <div className="comp-frame">
      <div className="comp-head">
        <div>
          <span className="comp-kicker">{props.kicker}</span>
          <h2>{props.title}</h2>
        </div>
        {props.onOpen ? (
          <button type="button" className="btn sm ghost" onClick={props.onOpen}>
            Open on the big map
          </button>
        ) : null}
      </div>
      {props.children}
    </div>
  )
}

function Facts({ rows, title }: { rows: readonly (readonly [string, string])[]; title?: string }) {
  if (!rows.length) return null
  return (
    <dl className="comp-facts small">
      {title ? <dt className="comp-facts-title">{title}</dt> : null}
      {rows.map(([k, v]) => (
        <div key={k}>
          <dt>{k}</dt>
          <dd>{v}</dd>
        </div>
      ))}
    </dl>
  )
}

function ScreenMap(props: { mapId: DayzMapId; state: SessionState; session: CoachSession; route?: readonly Pos[]; focus?: Pos; markers?: readonly Pos[] }) {
  const map = DAYZ_MAPS.find((m) => m.id === props.mapId)!
  const [travel, setTravel] = useState<Travel | 'straight'>('foot')
  const follow = useFollowGround(map, travel)
  const here = props.state.spot?.map === props.mapId ? { ...props.state.spot, source: 'you' as const } : null
  return (
    <div className="comp-map">
      {props.route?.length ? (
        <span className="segmented sm" role="radiogroup" aria-label="Routes">
          {(
            [
              ['foot', 'On foot'],
              ['car', 'By car'],
              ['straight', 'Straight'],
            ] as const
          ).map(([id, label]) => (
            <button key={id} type="button" role="radio" aria-checked={travel === id} onClick={() => setTravel(id)}>
              {label}
            </button>
          ))}
        </span>
      ) : null}
      <DayzMapCanvas
        compact
        map={map}
        here={here}
        focus={props.focus ? { ...props.focus, nonce: props.focus.x + props.focus.y } : null}
        route={props.route ?? null}
        markers={props.markers}
        follow={follow}
        onSetHere={(p) => props.session.setSpot({ ...p, map: props.mapId })}
      />
    </div>
  )
}

function Welcome({ onAsk }: { onAsk: (q: string) => void }) {
  return (
    <div className="comp-frame comp-welcome">
      <span className="comp-kicker">Companion</span>
      <h2>Ask me anything about surviving Chernarus</h2>
      <p className="small muted">
        Talk to me like a friend who knows the map: where you are, where to go, where things spawn, guns, staying alive. Whatever you ask comes up here: maps, routes, cards and plans. Say “Coach,” first when always listening is on.
      </p>
      <div className="suggestions">
        {HELP_EXAMPLES.map((q) => (
          <button key={q} type="button" className="chip" onClick={() => onAsk(q)}>
            {q}
          </button>
        ))}
      </div>
    </div>
  )
}
