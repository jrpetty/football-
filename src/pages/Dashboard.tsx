import { Link } from 'react-router-dom'
import type { Player } from '../types'
import { useData } from '../store/store'
import {
  teamSeasonStats,
  pointsTimeline,
  recent,
  form,
  leaderboard,
} from '../analytics/selectors'
import {
  fmtDateShort,
  pct,
  num,
  RESULT_COLOR,
  resultOf,
  statusColor,
} from '../utils/format'
import { StatCard } from '../components/ui/StatCard'
import { LineChart } from '../components/charts/LineChart'
import {
  Avatar,
  RatingChip,
  FormPips,
  EmptyState,
} from '../components/ui/Primitives'

const STATUSES: Player['status'][] = ['Available', 'Doubtful', 'Injured', 'Suspended']

export function Dashboard() {
  const data = useData()
  const { team, matches, players } = data
  const stats = teamSeasonStats(matches)
  const timeline = pointsTimeline(matches)
  const recentMatches = recent(matches).slice(0, 5)
  const recentForm = form(matches, 6)
  const lastResult = recentForm.length ? recentForm[recentForm.length - 1] : null

  const labels = timeline.map((p) => p.label)
  const cumulative = timeline.map((p) => p.cumulativePoints)
  const goalsForSeries = timeline.map((p) => p.goalsFor)
  const xgForSeries = timeline.map((p) => Math.round(p.xgFor * 10) / 10)

  const topScorers = leaderboard(data, 'goals', 5)
  const topAssists = leaderboard(data, 'assists', 5)
  const topRated = leaderboard(data, 'avgRating', 5, 3)

  const statusCounts: Record<Player['status'], number> = {
    Available: 0, Doubtful: 0, Injured: 0, Suspended: 0,
  }
  for (const p of players) statusCounts[p.status]++

  const recordSub = `${stats.points} pts in ${stats.played} matches`

  return (
    <div className="content">
      <div className="page-head">
        <h1 className="page-title">Dashboard</h1>
        <p className="page-subtitle">
          {team.name} · {team.season} · {stats.wins}W–{stats.draws}D–{stats.losses}L
          {stats.played > 0 && <> · {stats.points} pts</>}
        </p>
      </div>

      {matches.length === 0 ? (
        <div className="card card-pad">
          <EmptyState
            icon="⚽"
            title="No matches recorded yet"
            hint={<Link className="link" to="/matches">Add your first match</Link>}
          />
        </div>
      ) : (
        <>
          {/* Headline stats */}
          <div className="grid grid-4">
            <StatCard
              label="Points"
              value={stats.points}
              icon="🏆"
              sub={<span className="muted">{num(stats.ppg, 2)} PPG</span>}
            />
            <StatCard
              label="Record"
              value={`${stats.wins}-${stats.draws}-${stats.losses}`}
              icon="📊"
              sub={<span className="muted">{recordSub}</span>}
            />
            <StatCard
              label="Goals"
              value={`${stats.goalsFor}:${stats.goalsAgainst}`}
              icon="🥅"
              accent={stats.goalDiff >= 0 ? '#22c55e' : '#ef4444'}
              sub={
                <span className={stats.goalDiff >= 0 ? 'text-green' : 'text-red'}>
                  {stats.goalDiff >= 0 ? '+' : ''}{stats.goalDiff} GD
                </span>
              }
            />
            <StatCard
              label="Clean sheets"
              value={stats.cleanSheets}
              icon="🛡️"
              accent="#3b82f6"
              sub={<span className="muted">{stats.failedToScore} failed to score</span>}
            />
          </div>

          <div className="grid grid-4" style={{ marginTop: 14 }}>
            <StatCard
              label="Avg possession"
              value={pct(stats.avgPossession, 1)}
              icon="🔄"
              accent="#22c55e"
            />
            <StatCard
              label="xG For"
              value={num(stats.xgFor, 1)}
              icon="📈"
              accent="#84cc16"
              sub={
                <span className="muted">
                  {stats.goalsFor - stats.xgFor >= 0 ? 'outperforming' : 'underperforming'} by{' '}
                  {num(Math.abs(stats.goalsFor - stats.xgFor), 1)}
                </span>
              }
            />
            <StatCard
              label="xG Against"
              value={num(stats.xgAgainst, 1)}
              icon="📉"
              accent="#f97316"
            />
            <StatCard
              label="Win rate"
              value={pct(stats.winRate, 0)}
              icon="✅"
              accent="#eab308"
            />
          </div>

          {/* Form + charts */}
          <div className="grid grid-3" style={{ marginTop: 18 }}>
            <div className="card card-pad">
              <div className="card-head">
                <h3>Form</h3>
                <span className="sub">last {recentForm.length}</span>
              </div>
              <div className="col gap-lg" style={{ marginTop: 12 }}>
                <FormPips form={recentForm} />
                {lastResult && (
                  <div className="row between">
                    <span className="muted tiny">Last result</span>
                    <span
                      className="badge solid"
                      style={{ background: RESULT_COLOR[lastResult] }}
                    >
                      {lastResult === 'W' ? 'Win' : lastResult === 'D' ? 'Draw' : 'Loss'}
                    </span>
                  </div>
                )}
                <div className="divider" />
                <div className="kv">
                  <span className="k">Points per game</span>
                  <span className="v mono">{num(stats.ppg, 2)}</span>
                </div>
                <div className="kv">
                  <span className="k">Win rate</span>
                  <span className="v mono">{pct(stats.winRate, 0)}</span>
                </div>
              </div>
            </div>

            <div className="card card-pad" style={{ gridColumn: 'span 2' }}>
              <div className="card-head">
                <h3>Points progression</h3>
                <span className="sub">cumulative over the season</span>
              </div>
              <LineChart
                labels={labels}
                series={[
                  { name: 'Points', color: '#22c55e', values: cumulative, area: true },
                ]}
                unit=""
                min={0}
              />
            </div>
          </div>

          <div className="grid grid-2" style={{ marginTop: 18 }}>
            <div className="card card-pad">
              <div className="card-head">
                <h3>Goals vs Expected (xG)</h3>
                <span className="sub">per match</span>
              </div>
              <LineChart
                labels={labels}
                series={[
                  { name: 'Goals', color: '#22c55e', values: goalsForSeries },
                  { name: 'xG', color: '#84cc16', values: xgForSeries, dashed: true },
                ]}
                min={0}
              />
              <div className="legend" style={{ marginTop: 8 }}>
                <span className="item">
                  <span className="dot" style={{ background: '#22c55e' }} /> Goals for
                </span>
                <span className="item">
                  <span className="dot" style={{ background: '#84cc16' }} /> Expected (xG)
                </span>
              </div>
            </div>

            <div className="card card-pad">
              <div className="card-head">
                <h3>Recent results</h3>
                <Link className="link sub" to="/matches">All matches →</Link>
              </div>
              <div className="col" style={{ marginTop: 6 }}>
                {recentMatches.map((m) => {
                  const r = resultOf(m.goalsFor, m.goalsAgainst)
                  return (
                    <Link
                      key={m.id}
                      to={`/matches/${m.id}`}
                      className="row between"
                      style={{
                        padding: '10px 6px',
                        borderBottom: '1px solid var(--border, rgba(255,255,255,0.06))',
                        textDecoration: 'none',
                      }}
                    >
                      <div className="row gap-sm center">
                        <span
                          className="form-pip"
                          style={{ background: RESULT_COLOR[r], flex: '0 0 auto' }}
                        >
                          {r}
                        </span>
                        <div className="col">
                          <span className="bold">{m.opponent}</span>
                          <span className="muted tiny">
                            {fmtDateShort(m.date)} · {m.venue === 'Home' ? 'H' : 'A'} · {m.competition}
                          </span>
                        </div>
                      </div>
                      <div className="row gap-sm center">
                        <span className="mono bold">{m.goalsFor}–{m.goalsAgainst}</span>
                        <span className="muted tiny tnum">{m.possession}%</span>
                      </div>
                    </Link>
                  )
                })}
              </div>
            </div>
          </div>

          {/* Top performers + availability */}
          <div className="grid grid-2" style={{ marginTop: 18 }}>
            <div className="card card-pad">
              <div className="card-head">
                <h3>Top performers</h3>
                <Link className="link sub" to="/squad">Squad →</Link>
              </div>
              <div className="grid grid-3" style={{ marginTop: 10 }}>
                <Leaderboard
                  title="Goals"
                  rows={topScorers.map((a) => ({
                    player: a.player,
                    value: <span className="mono bold">{a.goals}</span>,
                  }))}
                />
                <Leaderboard
                  title="Assists"
                  rows={topAssists.map((a) => ({
                    player: a.player,
                    value: <span className="mono bold">{a.assists}</span>,
                  }))}
                />
                <Leaderboard
                  title="Rating"
                  rows={topRated.map((a) => ({
                    player: a.player,
                    value: <RatingChip rating={a.avgRating} small />,
                  }))}
                />
              </div>
            </div>

            <div className="card card-pad">
              <div className="card-head">
                <h3>Squad availability</h3>
                <span className="sub">{players.length} players</span>
              </div>
              <div className="col gap-lg" style={{ marginTop: 12 }}>
                <div className="row wrap gap-sm">
                  {STATUSES.map((s) => (
                    <span
                      key={s}
                      className="badge"
                      style={{
                        borderColor: `${statusColor(s)}55`,
                        color: statusColor(s),
                      }}
                    >
                      <span className="dot" style={{ background: statusColor(s) }} />
                      {s}
                      <span className="bold" style={{ marginLeft: 4 }}>{statusCounts[s]}</span>
                    </span>
                  ))}
                </div>
                <div className="meter" style={{ display: 'flex', height: 14, overflow: 'visible' }}>
                  {STATUSES.map((s) =>
                    statusCounts[s] > 0 ? (
                      <span
                        key={s}
                        style={{
                          width: `${(statusCounts[s] / Math.max(1, players.length)) * 100}%`,
                          background: statusColor(s),
                          display: 'block',
                          height: '100%',
                        }}
                        title={`${s}: ${statusCounts[s]}`}
                      />
                    ) : null,
                  )}
                </div>
                <div className="divider" />
                <div className="row between">
                  <span className="muted tiny">Fit & available</span>
                  <span className="text-green bold">
                    {statusCounts.Available} / {players.length}
                  </span>
                </div>
                <div className="row between">
                  <span className="muted tiny">Unavailable</span>
                  <span className="text-red bold">
                    {statusCounts.Injured + statusCounts.Suspended}
                  </span>
                </div>
              </div>
            </div>
          </div>
        </>
      )}
    </div>
  )
}

function Leaderboard({
  title,
  rows,
}: {
  title: string
  rows: { player: Player; value: React.ReactNode }[]
}) {
  return (
    <div className="col">
      <span className="muted tiny bold" style={{ textTransform: 'uppercase', letterSpacing: 0.5 }}>
        {title}
      </span>
      <div className="col" style={{ marginTop: 8 }}>
        {rows.length === 0 ? (
          <span className="faint tiny">No data</span>
        ) : (
          rows.map((r, i) => (
            <div
              key={r.player.id}
              className="row between center"
              style={{ padding: '5px 0', gap: 8 }}
            >
              <div className="row gap-sm center" style={{ minWidth: 0 }}>
                <span className="muted tiny mono" style={{ width: 14, textAlign: 'right' }}>
                  {i + 1}
                </span>
                <Avatar player={r.player} size="sm" />
                <Link
                  className="link"
                  to={`/squad/${r.player.id}`}
                  style={{
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                    fontSize: 13,
                  }}
                >
                  {r.player.name}
                </Link>
              </div>
              {r.value}
            </div>
          ))
        )}
      </div>
    </div>
  )
}
