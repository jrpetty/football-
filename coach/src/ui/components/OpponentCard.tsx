// The opponent card: the civilisation you are up against, on one screen,
// from the pack's researched knowledge. Before the match you pick their
// civilisation (any game mode); during play it opens by itself once the
// loading screen showed it, where live coaching is allowed.

import { useMemo, useState } from 'react'
import type { SightlineBridge } from '../../../shared/bridge.ts'
import { AGE_NAMES, opponentCard } from '../../core/aoe4/opponentCard.ts'
import { opponentProfile, type OpponentProfile } from '../../core/aoe4/opponent.ts'
import { itemName } from '../../core/aoe4/names.ts'
import type { GameProfile } from '../../core/profile/schema.ts'
import { Notice } from './common.tsx'

export function OpponentCardView({ profile, theirCiv, yourCiv, yourAge = 1 }: { profile: GameProfile; theirCiv: string; yourCiv: string | null; yourAge?: number }) {
  const roster = profile.knowledge.roster
  const card = useMemo(() => (roster ? opponentCard(roster, theirCiv, yourCiv, yourAge) : null), [roster, theirCiv, yourCiv, yourAge])
  if (!card) return null
  return (
    <div className="opp-card">
      {card.playstyle ? <p className="small" style={{ margin: 0 }}>{card.playstyle}</p> : null}
      {card.howToPlayAgainst ? (
        <p className="small" style={{ margin: 0 }}>
          <b>Against them:</b> {card.howToPlayAgainst}
        </p>
      ) : null}
      {card.uniques.length ? (
        <div className="small">
          <b>Their unique units</b>
          <ul style={{ margin: '4px 0 0', paddingLeft: 18 }}>
            {card.uniques.map((u) => (
              <li key={u.name}>
                {u.name}
                {u.age ? <span className="muted"> ({AGE_NAMES[u.age - 1]})</span> : null}
                {u.beatenBy.length ? <span>: beaten by {u.beatenBy.join(', ')}</span> : null}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      {card.ages.length ? (
        <div className="opp-ages">
          {card.ages.map((a) => (
            <div key={a.age} className={`opp-age${a.age === yourAge ? ' now' : ''}`}>
              <b className="small">{AGE_NAMES[a.age - 1]}</b>
              {a.plans.length ? (
                <ul className="small">
                  {a.plans.map((p) => (
                    <li key={p.name}>
                      {p.name}
                      {p.timing ? <span className="muted"> · {p.timing}</span> : null}
                      {p.response ? <div className="muted">{p.response}</div> : null}
                    </li>
                  ))}
                </ul>
              ) : null}
              {a.spikes.length ? <span className="small">Strong: {a.spikes.join('; ')}</span> : null}
              {a.weak.length ? <span className="small muted">Weak: {a.weak.join('; ')}</span> : null}
            </div>
          ))}
        </div>
      ) : null}
      {card.yourPlan ? (
        <div className="small">
          <b>
            Your plan as {card.yourPlan.civ} ({AGE_NAMES[card.yourPlan.age - 1]})
          </b>
          <ul style={{ margin: '4px 0 0', paddingLeft: 18 }}>
            {card.yourPlan.lines.map((l) => (
              <li key={l}>{l}</li>
            ))}
          </ul>
        </div>
      ) : null}
      <span className="muted small">From the coach's researched notes on each civilisation (patch {roster?.patch ?? 'unknown'}), not from your opponent's own games.</span>
    </div>
  )
}

/** Before the match: pick the opponent's civilisation (shown on the loading screen) to see their card. */
export function OpponentCardPicker({ profile, yourCiv, bridge }: { profile: GameProfile; yourCiv: string | null; bridge: SightlineBridge }) {
  const factions = (profile.knowledge.roster?.factions ?? []).filter((f) => f.status === 'released')
  const [civ, setCiv] = useState('')
  const [record, setRecord] = useState<{ name: string; p: OpponentProfile } | null>(null)
  return (
    <section className="panel span-2" aria-labelledby="opp-h">
      <div className="panel-head">
        <h2 id="opp-h">Opponent card</h2>
        <select aria-label="Their civilisation" value={civ} onChange={(e) => setCiv(e.target.value)}>
          <option value="">Their civilisation…</option>
          {factions.map((f) => (
            <option key={f.id} value={f.id}>
              {f.name}
            </option>
          ))}
        </select>
      </div>
      <OpponentLookup
        bridge={bridge}
        onFound={(name, p) => {
          setRecord({ name, p })
          // Their most-played civilisation is the first guess; the loading screen says for sure.
          if (!civ && p.civs[0]) setCiv(p.civs[0].civ)
        }}
      />
      {record ? <OpponentRecord profile={profile} name={record.name} p={record.p} /> : null}
      {civ ? (
        <OpponentCardView profile={profile} theirCiv={civ} yourCiv={yourCiv} />
      ) : (
        <p className="muted small" style={{ margin: 0 }}>
          On the loading screen, pick their civilisation: their power spikes and weak windows, the plans they usually go for and when, their unique units with what beats them, and your plan. During play it opens by itself where live coaching is allowed.
        </p>
      )}
    </section>
  )
}

/** Their public AoE4World record, looked up by name before the match (never during one). */
function OpponentLookup({ bridge, onFound }: { bridge: SightlineBridge; onFound: (name: string, p: OpponentProfile) => void }) {
  const [query, setQuery] = useState('')
  const [found, setFound] = useState<{ profileId: number; name: string; country: string | null }[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const search = async () => {
    setBusy(true)
    setError(null)
    const r = await bridge.aoe4worldSearch(query).catch(() => ({ ok: false as const, error: 'Could not reach AoE4World.' }))
    setBusy(false)
    if (!r.ok) setError(r.error)
    else setFound(r.players)
  }
  const pick = async (id: number, name: string) => {
    setBusy(true)
    setError(null)
    const r = await bridge.aoe4worldOpponent(id).catch(() => ({ ok: false as const, error: 'Could not reach AoE4World.' }))
    setBusy(false)
    setFound(null)
    if (!r.ok) setError(r.error)
    else onFound(name, opponentProfile(r.games, r.facts))
  }
  return (
    <div className="opp-lookup">
      <div className="btn-row">
        <input type="text" placeholder="Their name (optional): look up their public games on AoE4World" value={query} onChange={(e) => setQuery(e.target.value)} onKeyDown={(e) => e.key === 'Enter' && query.trim().length >= 3 && void search()} style={{ flex: 1 }} />
        <button type="button" className="btn" disabled={busy || query.trim().length < 3} onClick={() => void search()}>
          {busy ? 'Looking…' : 'Look up'}
        </button>
      </div>
      <span className="muted small">Before the match only, on request: what anyone can see on AoE4World. Nothing is looked up during play.</span>
      {error ? <Notice level="warning">{error}</Notice> : null}
      {found ? (
        found.length ? (
          <ul className="acc-found">
            {found.map((p) => (
              <li key={p.profileId}>
                <button type="button" className="btn sm ghost" onClick={() => void pick(p.profileId, p.name)}>
                  {p.name} {p.country ? <span className="muted">({p.country.toUpperCase()})</span> : null}
                </button>
              </li>
            ))}
          </ul>
        ) : (
          <p className="muted small">No player by that name on AoE4World.</p>
        )
      ) : null}
    </div>
  )
}

const clock = (sec: number) => `${Math.floor(sec / 60)}:${String(Math.round(sec % 60)).padStart(2, '0')}`

function OpponentRecord({ profile, name, p }: { profile: GameProfile; name: string; p: OpponentProfile }) {
  const roster = profile.knowledge.roster
  const civName = (id: string) => roster?.factions.find((f) => f.id === id)?.name ?? id
  if (!p.games) return <p className="muted small">{name}: no public Ranked or Quick Match games on AoE4World.</p>
  return (
    <div className="opp-record small">
      <b>
        {name}
        {p.rating ? ` · rating ${p.rating}` : ''}
      </b>
      <span>
        Last {p.games} games: {p.wins} won. Recent: {p.recent.map((r) => (r === 'win' ? 'W' : r === 'loss' ? 'L' : '–')).join(' ')}
      </span>
      <span>Plays: {p.civs.map((c) => `${civName(c.civ)} ${c.games} (${Math.round((c.wins / c.games) * 100)}% won)`).join(', ')}</span>
      {p.openings.length ? (
        <ul style={{ margin: '2px 0 0', paddingLeft: 18 }}>
          {p.openings.map((o) => (
            <li key={o.gameId}>
              {civName(o.civ)}: {o.landmark ? `${itemName('building', o.landmark, roster).replace(/ \(landmark\)$/, '')}, ` : ''}
              {o.feudal ? `Feudal ${clock(o.feudal)}` : 'no Feudal'}
              {o.firstUnits.length ? `, first ${o.firstUnits.map((u) => itemName('unit', u, roster)).join(' then ')}` : ''}
              {o.result ? ` (${o.result === 'win' ? 'won' : 'lost'})` : ''}
            </li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}
