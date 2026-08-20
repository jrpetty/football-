// Player watch — form, availability and discipline.
//
// The suspension tracker is the reason this page exists: accumulating bookings
// is one of the few genuinely forecastable disruptions in football, and it is
// almost never surfaced anywhere a fan can see it.

import { useMemo, useState } from 'react'
import { usePlayers, useSeason } from '../data/store.tsx'
import { TeamChip } from '../components/primitives.tsx'
import { clubName } from '../config/teams.ts'

type Tab = 'availability' | 'discipline' | 'form'

const STATUS_LABEL: Record<string, string> = {
  i: 'Injured',
  s: 'Suspended',
  d: 'Doubtful',
  u: 'Unavailable',
  n: 'Unavailable',
}

export default function PlayerWatch() {
  const { data, loading } = usePlayers()
  const { season } = useSeason()
  const [tab, setTab] = useState<Tab>('availability')
  const [team, setTeam] = useState<string>('')

  const players = useMemo(() => {
    if (!data) return []
    const filtered = team ? data.players.filter((p) => p.team === team) : data.players
    if (tab === 'availability') {
      return filtered
        .filter((p) => p.status !== 'a')
        .sort((a, b) => b.xgi90 - a.xgi90)
    }
    if (tab === 'discipline') {
      const thisSeason = filtered.filter((p) => p.yellowCards > 0 || p.redCards > 0)
      // Before any bookings exist, showing an empty table is less useful than
      // showing who was booked most last season — clearly labelled as such.
      const source = thisSeason.length > 0 ? thisSeason : filtered.filter((p) => p.priorYellowCards > 0)
      return source.sort((a, b) =>
        thisSeason.length > 0
          ? b.yellowCards - a.yellowCards || b.yellow90 - a.yellow90
          : b.priorYellowCards - a.priorYellowCards,
      )
    }
    return filtered.filter((p) => p.minutes >= 450).sort((a, b) => b.xgi90 - a.xgi90)
  }, [data, tab, team])

  // True when no bookings have happened yet, so the table is showing last
  // season's discipline as a stand-in.
  const disciplineIsPrior =
    tab === 'discipline' && players.length > 0 && players.every((p) => p.yellowCards === 0)

  if (loading) return <div className="loading">Loading players…</div>
  if (!data) return <div className="loading">No player data.</div>

  const teams = season?.teams ?? []

  return (
    <>
      <div className="eyebrow">{data.season}</div>
      <h1 style={{ fontSize: 28, marginTop: 4, marginBottom: 6 }}>Player watch</h1>
      <p className="muted" style={{ fontSize: 13.5, marginBottom: 18, maxWidth: 760, lineHeight: 1.6 }}>
        Availability, discipline and output. Rates carry over from last season until enough of this
        one has been played — which is also how the model treats a summer signing at his new club.
      </p>

      <div className="row gap-8 wrap" style={{ marginBottom: 16 }}>
        {(['availability', 'discipline', 'form'] as Tab[]).map((t) => (
          <button
            key={t}
            className={`gw-btn${tab === t ? ' active' : ''}`}
            style={{ minWidth: 0, padding: '0 14px', textTransform: 'capitalize' }}
            onClick={() => setTab(t)}
          >
            {t}
          </button>
        ))}
        <select
          value={team}
          onChange={(e) => setTeam(e.target.value)}
          className="gw-btn"
          style={{ minWidth: 150, padding: '0 10px' }}
        >
          <option value="">All clubs</option>
          {teams.map((t) => (
            <option key={t.code} value={t.code}>{t.name}</option>
          ))}
        </select>
      </div>

      <div className="panel" style={{ overflowX: 'auto' }}>
        <table className="data">
          <thead>
            <tr>
              <th>Player</th>
              <th>Club</th>
              <th>Pos</th>
              {tab === 'availability' && (
                <>
                  <th>Status</th>
                  <th className="num">Chance</th>
                  <th>Detail</th>
                </>
              )}
              {tab === 'discipline' && (
                <>
                  <th className="num">{disciplineIsPrior ? 'Yellows (last season)' : 'Yellows'}</th>
                  <th className="num">Reds</th>
                  <th className="num">Per 90</th>
                  <th className="num">To ban</th>
                </>
              )}
              {tab === 'form' && (
                <>
                  <th className="num">Mins</th>
                  <th className="num">Goals</th>
                  <th className="num">Assists</th>
                  <th className="num">xGI/90</th>
                </>
              )}
            </tr>
          </thead>
          <tbody>
            {players.slice(0, 60).map((p) => (
              <tr key={p.id}>
                <td style={{ fontWeight: 600 }}>
                  {p.name}
                  {p.onBrink && (
                    <span className="badge badge-danger" style={{ marginLeft: 7, fontSize: 10 }}>
                      one from a ban
                    </span>
                  )}
                </td>
                <td><TeamChip code={p.team} /></td>
                <td className="faint">{p.position}</td>
                {tab === 'availability' && (
                  <>
                    <td>
                      <span className={p.status === 'd' ? 'badge badge-live' : 'badge badge-danger'}>
                        {STATUS_LABEL[p.status] ?? p.status}
                      </span>
                    </td>
                    <td className="num tabular">{p.chanceNextRound === null ? '—' : `${p.chanceNextRound}%`}</td>
                    <td className="muted" style={{ fontSize: 12.5, maxWidth: 320 }}>{p.news || '—'}</td>
                  </>
                )}
                {tab === 'discipline' && (
                  <>
                    <td className="num tabular">{disciplineIsPrior ? p.priorYellowCards : p.yellowCards}</td>
                    <td className="num tabular">{disciplineIsPrior ? p.priorRedCards : p.redCards}</td>
                    <td className="num tabular">{p.yellow90.toFixed(2)}</td>
                    <td className="num tabular">{p.yellowsToBan ?? '—'}</td>
                  </>
                )}
                {tab === 'form' && (
                  <>
                    <td className="num tabular">{p.minutes}</td>
                    <td className="num tabular">{p.goals}</td>
                    <td className="num tabular">{p.assists}</td>
                    <td className="num tabular">{p.xgi90.toFixed(2)}</td>
                  </>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {players.length === 0 && (
        <div className="note" style={{ marginTop: 16 }}>
          <span className="note-icon">i</span>
          <span>
            {tab === 'discipline'
              ? 'No bookings recorded yet this season — this fills in as matches are played.'
              : `Nothing to show for ${team ? clubName(team) : 'any club'} here.`}
          </span>
        </div>
      )}

      {tab === 'discipline' && (
        <p className="faint" style={{ fontSize: 12, marginTop: 12, lineHeight: 1.55, maxWidth: 760 }}>
          {disciplineIsPrior
            ? 'No bookings yet this season — the counts above are last season\u2019s, for context. Suspension counters reset every season, so every player currently starts from zero. '
            : ''}
          English football bans a player for one match at five bookings (before their club's 19th
          league game), two at ten (before the 32nd) and three at fifteen. The count does not reset
          after a ban is served. Red-card bans are shown as the one-match minimum, because the data
          feed records that a red was shown but not the offence.
        </p>
      )}
    </>
  )
}
