// Player watch — form, availability and discipline.
//
// The suspension tracker is the reason this page exists: accumulating bookings
// is one of the few genuinely forecastable disruptions in football, and it is
// almost never surfaced anywhere a fan can see it.

import { useMemo, useState } from 'react'
import { usePlayers, useSeason, useTransfers } from '../data/store.tsx'
import { Stat, TeamChip } from '../components/primitives.tsx'
import { clubName } from '../config/teams.ts'
import type { PlayerArtifact, TransfersArtifact } from '../core/schema.ts'

type Tab = 'availability' | 'discipline' | 'form' | 'transfers'

/**
 * Share of a club's attacking value, as a small meter.
 *
 * A number alone does not answer "is 9% a lot?". Against a common scale it is
 * obvious at a glance which absences are the ones that matter.
 */
function ImpactMeter({ share }: { share: number }) {
  const W = 54
  const pct = Math.min(1, share / 0.15)
  return (
    <span className="row gap-6" style={{ justifyContent: 'flex-end' }}>
      <span className="tabular" style={{ fontSize: 12, minWidth: 34 }}>
        {share > 0 ? `${(share * 100).toFixed(1)}%` : '—'}
      </span>
      <svg width={W} height={8} role="img" aria-label={`${(share * 100).toFixed(1)}% of the club's attacking value`}>
        <rect width={W} height={8} rx={4} fill="var(--panel-3)" />
        <rect width={Math.max(share > 0 ? 3 : 0, pct * W)} height={8} rx={4} fill="var(--home)" />
      </svg>
    </span>
  )
}

function TransfersView({
  transfers,
  teamFilter,
  players,
}: {
  transfers: TransfersArtifact | null
  teamFilter: string
  players: PlayerArtifact[]
}) {
  if (!transfers) {
    return (
      <div className="note">
        <span className="note-icon">i</span>
        <span>The transfer ledger has not been written yet — it appears once the pipeline has run.</span>
      </div>
    )
  }
  // Real movement first; feed listings are the least interesting rows here.
  const rank = { moved: 0, arrived: 1, left: 2, listed: 3 } as const
  const records = transfers.records
    .filter((r) => !teamFilter || r.from === teamFilter || r.to === teamFilter)
    .slice()
    .sort((a, b) => rank[a.kind] - rank[b.kind] || b.detectedAt - a.detectedAt)
  // Before the pipeline has seen a squad change there is nothing to diff, but
  // the feed states a join date for every player — so recent signings can be
  // listed from day one. It is a different kind of evidence and is labelled as
  // such rather than being passed off as detected movement.
  if (records.length === 0) {
    const cutoff = Date.now() - 120 * 86400000
    const recent = players
      .filter((p) => {
        if (!p.joinedAt) return false
        const t = Date.parse(p.joinedAt)
        return Number.isFinite(t) && t >= cutoff
      })
      .filter((p) => !teamFilter || p.team === teamFilter)
      .sort((a, b) => Date.parse(b.joinedAt!) - Date.parse(a.joinedAt!))

    return (
      <>
        <div className="note" style={{ marginBottom: 16 }}>
          <span className="note-icon">i</span>
          <span>
            This pipeline detects movement by comparing each daily run against the one before, so
            its own ledger fills in from the next change onward. Meanwhile, these are the players
            the feed says joined their club in the last four months.
          </span>
        </div>
        {recent.length === 0 ? (
          <div className="note">
            <span className="note-icon">i</span>
            <span>No recent signings {teamFilter ? `at ${clubName(teamFilter)}` : 'in the league'}.</span>
          </div>
        ) : (
          <div className="panel table-scroll">
            <table className="data">
              <thead>
                <tr>
                  <th>Player</th>
                  <th>Club</th>
                  <th>Pos</th>
                  <th>Joined</th>
                  <th className="num">Value</th>
                  <th className="num">Impact</th>
                </tr>
              </thead>
              <tbody>
                {recent.slice(0, 60).map((p) => (
                  <tr key={p.id}>
                    <td style={{ fontWeight: 600 }}>{p.name}</td>
                    <td><TeamChip code={p.team} /></td>
                    <td className="faint">{p.position}</td>
                    <td className="faint" style={{ fontSize: 12.5 }}>
                      {new Date(p.joinedAt!).toLocaleDateString(undefined, {
                        day: 'numeric', month: 'short', year: 'numeric',
                      })}
                    </td>
                    <td className="num tabular">£{p.cost.toFixed(1)}m</td>
                    <td className="num tabular"><ImpactMeter share={p.impact} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="faint" style={{ fontSize: 12, padding: '12px 14px 4px', lineHeight: 1.55, margin: 0 }}>
              From the feed's own join dates, not detected by this pipeline. "Value" is a Fantasy
              Premier League price, not a fee. Impact is the share of his new club's attacking value
              he already accounts for — near zero for someone who has yet to play there, which is
              exactly the point: a signing's record travels with him but his role does not.
            </p>
          </div>
        )}
      </>
    )
  }
  return (
    <div className="panel table-scroll">
      <table className="data">
        <thead>
          <tr>
            <th>Player</th>
            <th>Pos</th>
            <th>Move</th>
            <th>Announced</th>
            <th className="num">Value</th>
            <th>Detected</th>
          </tr>
        </thead>
        <tbody>
          {records.slice(0, 80).map((r) => (
            <tr key={`${r.playerId}-${r.kind}-${r.to ?? 'out'}`}>
              <td style={{ fontWeight: 600 }}>{r.name}</td>
              <td className="faint">{r.position || '—'}</td>
              <td>
                <span className="row gap-6" style={{ alignItems: 'center' }}>
                  {r.from ? (
                    <TeamChip code={r.from} />
                  ) : (
                    <span className="chip">
                      {r.kind === 'listed' ? 'newly listed' : 'outside the league'}
                    </span>
                  )}
                  <span className="faint">→</span>
                  {r.to ? <TeamChip code={r.to} /> : <span className="chip">left the league</span>}
                </span>
              </td>
              <td className="faint" style={{ fontSize: 12.5 }}>
                {r.joinedAt ? new Date(r.joinedAt).toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' }) : '—'}
              </td>
              <td className="num tabular">{r.cost > 0 ? `£${r.cost.toFixed(1)}m` : '—'}</td>
              <td className="faint" style={{ fontSize: 12.5 }}>
                {new Date(r.detectedAt).toLocaleDateString(undefined, { day: 'numeric', month: 'short' })}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="faint" style={{ fontSize: 12, padding: '12px 14px 4px', lineHeight: 1.55, margin: 0 }}>
        Detected by comparing each daily run against the previous one — the feed reports where a
        player is, never that he moved. "Value" is his Fantasy Premier League price, not a fee.
        A club that has just sold a key player keeps the rating it earned with him until results
        catch up, which is exactly why these are worth seeing. "Newly listed" means a player
        appeared in the feed without having just signed — the source periodically expands its
        roster, and that is not a transfer.
      </p>
    </div>
  )
}

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
  const { data: transfers } = useTransfers()
  const [tab, setTab] = useState<Tab>('availability')
  const [team, setTeam] = useState<string>('')

  const players = useMemo(() => {
    if (!data) return []
    const filtered = team ? data.players.filter((p) => p.team === team) : data.players
    if (tab === 'availability') {
      // By impact, not by scoring rate: a fringe forward out-ranks a
      // first-choice centre-half on xGI/90, which buries the absences that
      // actually move a forecast.
      return filtered.filter((p) => p.status !== 'a').sort((a, b) => b.impact - a.impact)
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
    return filtered.filter((p) => p.minutes >= 450).sort((a, b) => b.impact - a.impact)
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

      {tab === 'availability' && players.length > 0 && (
        <div className="grid-3" style={{ marginBottom: 16 }}>
          <Stat
            label="Unavailable"
            value={players.filter((p) => p.status === 'i' || p.status === 's' || p.status === 'u').length}
            sub={`${players.filter((p) => p.status === 'd').length} more carrying a doubt`}
          />
          <Stat
            label="Biggest absence"
            value={players[0] ? players[0].name : '—'}
            sub={
              players[0]
                ? `${clubName(players[0].team)} — ${(players[0].impact * 100).toFixed(1)}% of their attacking value`
                : undefined
            }
          />
          <Stat
            label="Suspended"
            value={players.filter((p) => p.status === 's').length}
            sub={
              players.filter((p) => p.status === 's').length > 0
                ? players.filter((p) => p.status === 's').map((p) => p.name).slice(0, 3).join(', ')
                : 'Nobody is serving a ban'
            }
          />
        </div>
      )}

      <div className="row gap-8 wrap" style={{ marginBottom: 16 }}>
        {(['availability', 'discipline', 'form', 'transfers'] as Tab[]).map((t) => (
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

      {tab === 'transfers' ? (
        <TransfersView transfers={transfers} teamFilter={team} players={data.players} />
      ) : (
      <div className="panel table-scroll">
        <table className="data">
          <thead>
            <tr>
              <th>Player</th>
              <th>Club</th>
              <th>Pos</th>
              {tab === 'availability' && (
                <>
                  <th>Status</th>
                  <th className="num" title="Share of his club's attacking value">Impact</th>
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
                  <th className="num" title="Share of his club's attacking value">Impact</th>
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
                    <td className="num tabular">
                      <ImpactMeter share={p.impact} />
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
                    <td className="num tabular"><ImpactMeter share={p.impact} /></td>
                  </>
                )}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      )}

      {tab !== 'transfers' && players.length === 0 && (
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
