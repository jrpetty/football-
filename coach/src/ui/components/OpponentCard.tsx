// The opponent card: the civilisation you are up against, on one screen,
// from the pack's researched knowledge. Before the match you pick their
// civilisation (any game mode); during play it opens by itself once the
// loading screen showed it, where live coaching is allowed.

import { useMemo, useState } from 'react'
import { AGE_NAMES, opponentCard } from '../../core/aoe4/opponentCard.ts'
import type { GameProfile } from '../../core/profile/schema.ts'

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
export function OpponentCardPicker({ profile, yourCiv }: { profile: GameProfile; yourCiv: string | null }) {
  const factions = (profile.knowledge.roster?.factions ?? []).filter((f) => f.status === 'released')
  const [civ, setCiv] = useState('')
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
