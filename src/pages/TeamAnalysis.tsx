import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { Match, MatchResult } from '../types'
import { useData } from '../store/store'
import {
  teamSeasonStats,
  chronological,
  pointsTimeline,
  formationUsage,
  goalsByInterval,
  leaderboard,
} from '../analytics/selectors'
import {
  num,
  pct,
  fmtDateShort,
  RESULT_COLOR,
  resultOf,
} from '../utils/format'
import { StatCard } from '../components/ui/StatCard'
import { Donut } from '../components/charts/Donut'
import { LineChart } from '../components/charts/LineChart'
import { BarChart } from '../components/charts/BarChart'
import { Avatar, RatingChip, EmptyState } from '../components/ui/Primitives'

const ACCENT = '#38bdf8'
const RED = '#ef4444'

interface VenueSplit {
  played: number
  wins: number
  draws: number
  losses: number
  goalsFor: number
  goalsAgainst: number
  points: number
  ppg: number
}

function emptySplit(): VenueSplit {
  return { played: 0, wins: 0, draws: 0, losses: 0, goalsFor: 0, goalsAgainst: 0, points: 0, ppg: 0 }
}

function venueSplit(matches: Match[], venue: 'Home' | 'Away'): VenueSplit {
  const s = emptySplit()
  for (const m of matches) {
    if (m.venue !== venue) continue
    s.played++
    s.goalsFor += m.goalsFor
    s.goalsAgainst += m.goalsAgainst
    const r = resultOf(m.goalsFor, m.goalsAgainst)
    if (r === 'W') s.wins++
    else if (r === 'D') s.draws++
    else s.losses++
  }
  s.points = s.wins * 3 + s.draws
  s.ppg = s.played ? s.points / s.played : 0
  return s
}

export function TeamAnalysis() {
  const data = useData()
  const { team, matches } = data

  if (matches.length === 0) {
    return (
      <div className="content">
        <div className="page-head">
          <h1 className="page-title">Team Analysis</h1>
          <p className="page-subtitle">{team.name} · {team.season}</p>
        </div>
        <div className="card card-pad">
          <EmptyState
            icon="📊"
            title="No matches to analyse yet"
            hint={<Link className="link" to="/matches">Record a match to unlock season analytics</Link>}
          />
        </div>
      </div>
    )
  }

  const stats = teamSeasonStats(matches)
  const chron = chronological(matches)
  const timeline = pointsTimeline(matches)
  const formations = formationUsage(matches)
  const intervals = goalsByInterval(matches)

  const labels = chron.map((m) => m.opponent)
  const r1 = (v: number) => Math.round(v * 10) / 10

  // Series for goals vs xG
  const goalsForSeries = chron.map((m) => m.goalsFor)
  const xgForSeries = chron.map((m) => r1(m.xgFor))
  const goalsAgainstSeries = chron.map((m) => m.goalsAgainst)
  const xgAgainstSeries = chron.map((m) => r1(m.xgAgainst))

  const cumulative = timeline.map((p) => p.cumulativePoints)
  const possessionSeries = chron.map((m) => m.possession)

  const xgDiff = r1(stats.xgFor - stats.xgAgainst)
  const goalsPerGame = stats.played ? stats.goalsFor / stats.played : 0
  const concededPerGame = stats.played ? stats.goalsAgainst / stats.played : 0

  const home = venueSplit(matches, 'Home')
  const away = venueSplit(matches, 'Away')

  const resultSlices = [
    { label: 'Wins', value: stats.wins, color: RESULT_COLOR.W },
    { label: 'Draws', value: stats.draws, color: RESULT_COLOR.D },
    { label: 'Losses', value: stats.losses, color: RESULT_COLOR.L },
  ]

  const intervalBars = intervals.map((b) => ({ label: `${b.bucket}'`, value: b.for }))
  const maxFormationCount = Math.max(1, ...formations.map((f) => f.count))

  const topScorers = leaderboard(data, 'goals', 5)
  const topAssists = leaderboard(data, 'assists', 5)
  const topRated = leaderboard(data, 'avgRating', 5, 3)
  const topTackles = leaderboard(data, 'tackles', 5)

  return (
    <div className="content">
      <div className="page-head">
        <h1 className="page-title">Team Analysis</h1>
        <p className="page-subtitle">
          {team.name} · {team.season} · season deep dive across {stats.played} matches
        </p>
      </div>

      {/* Headline expected-goals stats */}
      <div className="grid grid-4">
        <StatCard
          label="xG For"
          value={num(stats.xgFor, 1)}
          icon="📈"
          accent="#84cc16"
          sub={
            <span className="muted">
              {stats.goalsFor - stats.xgFor >= 0 ? 'over' : 'under'}performing by {num(Math.abs(stats.goalsFor - stats.xgFor), 1)}
            </span>
          }
        />
        <StatCard
          label="xG Against"
          value={num(stats.xgAgainst, 1)}
          icon="📉"
          accent="#f97316"
          sub={
            <span className="muted">
              {stats.goalsAgainst - stats.xgAgainst <= 0 ? 'better' : 'worse'} than expected by {num(Math.abs(stats.goalsAgainst - stats.xgAgainst), 1)}
            </span>
          }
        />
        <StatCard
          label="xG difference"
          value={`${xgDiff >= 0 ? '+' : ''}${num(xgDiff, 1)}`}
          icon="⚖️"
          accent={xgDiff >= 0 ? '#22c55e' : '#ef4444'}
          sub={<span className="muted">expected goal balance</span>}
        />
        <StatCard
          label="Avg possession"
          value={pct(stats.avgPossession, 1)}
          icon="🔄"
          accent="#22c55e"
        />
      </div>

      <div className="grid grid-4" style={{ marginTop: 14 }}>
        <StatCard
          label="Clean sheets"
          value={stats.cleanSheets}
          icon="🛡️"
          accent="#3b82f6"
          sub={<span className="muted">{pct(stats.played ? (stats.cleanSheets / stats.played) * 100 : 0, 0)} of matches</span>}
        />
        <StatCard
          label="Failed to score"
          value={stats.failedToScore}
          icon="🚫"
          accent="#f97316"
          sub={<span className="muted">{pct(stats.played ? (stats.failedToScore / stats.played) * 100 : 0, 0)} of matches</span>}
        />
        <StatCard
          label="Goals / game"
          value={num(goalsPerGame, 2)}
          icon="⚽"
          accent="#22c55e"
          sub={<span className="muted">{stats.goalsFor} scored</span>}
        />
        <StatCard
          label="Conceded / game"
          value={num(concededPerGame, 2)}
          icon="🥅"
          accent="#ef4444"
          sub={<span className="muted">{stats.goalsAgainst} conceded</span>}
        />
      </div>

      {/* Results donut + points progression */}
      <div className="grid grid-3" style={{ marginTop: 18 }}>
        <div className="card card-pad">
          <div className="card-head">
            <h3>Results</h3>
            <span className="sub">{stats.played} played</span>
          </div>
          <div className="col center" style={{ marginTop: 12, gap: 16 }}>
            <Donut
              slices={resultSlices}
              size={170}
              thickness={24}
              centerLabel={String(stats.points)}
              centerSub="PTS"
            />
            <div className="legend wrap" style={{ justifyContent: 'center' }}>
              <span className="item">
                <span className="dot" style={{ background: RESULT_COLOR.W }} /> {stats.wins} W
              </span>
              <span className="item">
                <span className="dot" style={{ background: RESULT_COLOR.D }} /> {stats.draws} D
              </span>
              <span className="item">
                <span className="dot" style={{ background: RESULT_COLOR.L }} /> {stats.losses} L
              </span>
            </div>
            <div className="row between" style={{ width: '100%' }}>
              <span className="muted tiny">Points per game</span>
              <span className="mono bold">{num(stats.ppg, 2)}</span>
            </div>
            <div className="row between" style={{ width: '100%' }}>
              <span className="muted tiny">Win rate</span>
              <span className="mono bold">{pct(stats.winRate, 0)}</span>
            </div>
          </div>
        </div>

        <div className="card card-pad" style={{ gridColumn: 'span 2' }}>
          <div className="card-head">
            <h3>Points progression</h3>
            <span className="sub">cumulative points over the season</span>
          </div>
          <LineChart
            labels={labels}
            series={[
              { name: 'Points', color: '#22c55e', values: cumulative, area: true },
            ]}
            min={0}
          />
        </div>
      </div>

      {/* Goals vs xG + possession */}
      <div className="grid grid-2" style={{ marginTop: 18 }}>
        <div className="card card-pad">
          <div className="card-head">
            <h3>Goals: actual vs xG</h3>
            <span className="sub">per match, chronological</span>
          </div>
          <LineChart
            labels={labels}
            series={[
              { name: 'Goals for', color: ACCENT, values: goalsForSeries, area: true },
              { name: 'xG for', color: ACCENT, values: xgForSeries, dashed: true },
              { name: 'Goals against', color: RED, values: goalsAgainstSeries },
              { name: 'xG against', color: RED, values: xgAgainstSeries, dashed: true },
            ]}
            min={0}
          />
          <div className="legend wrap" style={{ marginTop: 8 }}>
            <span className="item">
              <span className="dot" style={{ background: ACCENT }} /> Goals for
            </span>
            <span className="item">
              <span className="dot" style={{ background: ACCENT, opacity: 0.55 }} /> xG for
            </span>
            <span className="item">
              <span className="dot" style={{ background: RED }} /> Goals against
            </span>
            <span className="item">
              <span className="dot" style={{ background: RED, opacity: 0.55 }} /> xG against
            </span>
          </div>
        </div>

        <div className="card card-pad">
          <div className="card-head">
            <h3>Possession by match</h3>
            <span className="sub">% of ball control</span>
          </div>
          <LineChart
            labels={labels}
            series={[
              { name: 'Possession', color: '#22c55e', values: possessionSeries, area: true },
            ]}
            min={0}
            max={100}
            unit="%"
          />
          <div className="row between" style={{ marginTop: 8 }}>
            <span className="muted tiny">Season average</span>
            <span className="mono bold">{pct(stats.avgPossession, 1)}</span>
          </div>
        </div>
      </div>

      {/* Formation usage + Home vs Away */}
      <div className="grid grid-2" style={{ marginTop: 18 }}>
        <div className="card card-pad">
          <div className="card-head">
            <h3>Formation usage</h3>
            <span className="sub">{formations.length} shape{formations.length === 1 ? '' : 's'} used</span>
          </div>
          <div className="scroll-x" style={{ marginTop: 6 }}>
            <table className="table">
              <thead>
                <tr>
                  <th>Formation</th>
                  <th className="num">Played</th>
                  <th className="center-col">W-D-L</th>
                  <th className="num">PPG</th>
                  <th>Share</th>
                </tr>
              </thead>
              <tbody>
                {formations.map((f) => (
                  <tr key={f.formation}>
                    <td className="bold mono">{f.formation}</td>
                    <td className="num">{f.count}</td>
                    <td className="center-col mono">
                      <span className="text-green">{f.wins}</span>-
                      <span className="muted">{f.draws}</span>-
                      <span className="text-red">{f.losses}</span>
                    </td>
                    <td className="num mono bold">{num(f.ppg, 2)}</td>
                    <td style={{ minWidth: 90 }}>
                      <span className="meter" style={{ display: 'block', height: 8 }}>
                        <span style={{ width: `${(f.count / maxFormationCount) * 100}%`, background: ACCENT }} />
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>

        <div className="card card-pad">
          <div className="card-head">
            <h3>Home vs Away</h3>
            <span className="sub">venue splits</span>
          </div>
          <div className="grid grid-2" style={{ marginTop: 10 }}>
            <VenueBlock title="Home" icon="🏟️" split={home} accent="#22c55e" />
            <VenueBlock title="Away" icon="✈️" split={away} accent="#38bdf8" />
          </div>
        </div>
      </div>

      {/* Goals by interval + Leaderboards */}
      <div className="grid grid-2" style={{ marginTop: 18 }}>
        <div className="card card-pad">
          <div className="card-head">
            <h3>Goals by 15' interval</h3>
            <span className="sub">when we score</span>
          </div>
          <div style={{ marginTop: 10 }}>
            <BarChart data={intervalBars} accent={ACCENT} unit="" digits={0} />
          </div>
        </div>

        <div className="card card-pad">
          <div className="card-head">
            <h3>Leaderboards</h3>
            <Link className="link sub" to="/squad">Squad →</Link>
          </div>
          <div className="grid grid-2" style={{ marginTop: 10 }}>
            <LeaderTable
              title="Top scorers"
              rows={topScorers.map((a) => ({
                id: a.player.id,
                name: a.player.name,
                color: a.player.color,
                value: <span className="mono bold">{a.goals}</span>,
              }))}
            />
            <LeaderTable
              title="Top assists"
              rows={topAssists.map((a) => ({
                id: a.player.id,
                name: a.player.name,
                color: a.player.color,
                value: <span className="mono bold">{a.assists}</span>,
              }))}
            />
            <LeaderTable
              title="Top rated"
              rows={topRated.map((a) => ({
                id: a.player.id,
                name: a.player.name,
                color: a.player.color,
                value: <RatingChip rating={a.avgRating} small />,
              }))}
            />
            <LeaderTable
              title="Most tackles"
              rows={topTackles.map((a) => ({
                id: a.player.id,
                name: a.player.name,
                color: a.player.color,
                value: <span className="mono bold">{a.tackles}</span>,
              }))}
            />
          </div>
        </div>
      </div>

      <div className="card card-pad" style={{ marginTop: 18 }}>
        <div className="card-head">
          <h3>Recent matches</h3>
          <Link className="link sub" to="/matches">All matches →</Link>
        </div>
        <div className="scroll-x" style={{ marginTop: 6 }}>
          <table className="table clickable">
            <thead>
              <tr>
                <th>Date</th>
                <th>Opponent</th>
                <th className="center-col">Venue</th>
                <th className="center-col">Result</th>
                <th className="num">Score</th>
                <th className="num">Poss</th>
                <th className="num">xG</th>
              </tr>
            </thead>
            <tbody>
              {[...chron].reverse().slice(0, 8).map((m) => {
                const r: MatchResult = resultOf(m.goalsFor, m.goalsAgainst)
                return (
                  <tr key={m.id}>
                    <td className="muted tiny">{fmtDateShort(m.date)}</td>
                    <td>
                      <Link className="link bold" to={`/matches/${m.id}`}>{m.opponent}</Link>
                    </td>
                    <td className="center-col muted tiny">{m.venue === 'Home' ? 'H' : 'A'}</td>
                    <td className="center-col">
                      <span className="form-pip" style={{ background: RESULT_COLOR[r] }}>{r}</span>
                    </td>
                    <td className="num mono bold">{m.goalsFor}–{m.goalsAgainst}</td>
                    <td className="num tnum muted">{m.possession}%</td>
                    <td className="num tnum muted">{num(m.xgFor, 1)}–{num(m.xgAgainst, 1)}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

function VenueBlock({
  title,
  icon,
  split,
  accent,
}: {
  title: string
  icon: string
  split: VenueSplit
  accent: string
}) {
  return (
    <div className="panel" style={{ padding: 14, borderRadius: 12 }}>
      <div className="row between center" style={{ marginBottom: 10 }}>
        <span className="bold">{icon} {title}</span>
        <span className="badge" style={{ borderColor: `${accent}55`, color: accent }}>{split.played} pld</span>
      </div>
      <div className="col" style={{ gap: 8 }}>
        <div className="kv">
          <span className="k">W-D-L</span>
          <span className="v mono">
            <span className="text-green">{split.wins}</span>-
            <span className="muted">{split.draws}</span>-
            <span className="text-red">{split.losses}</span>
          </span>
        </div>
        <div className="kv">
          <span className="k">Goals</span>
          <span className="v mono">{split.goalsFor}:{split.goalsAgainst}</span>
        </div>
        <div className="kv">
          <span className="k">PPG</span>
          <span className="v mono bold" style={{ color: accent }}>{num(split.ppg, 2)}</span>
        </div>
      </div>
    </div>
  )
}

interface LeaderRow {
  id: string
  name: string
  color: string
  value: ReactNode
}

function LeaderTable({ title, rows }: { title: string; rows: LeaderRow[] }) {
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
            <div key={r.id} className="row between center" style={{ padding: '5px 0', gap: 8 }}>
              <div className="row gap-sm center" style={{ minWidth: 0 }}>
                <span className="muted tiny mono" style={{ width: 14, textAlign: 'right' }}>{i + 1}</span>
                <Avatar player={{ name: r.name, color: r.color }} size="sm" />
                <Link
                  className="link"
                  to={`/squad/${r.id}`}
                  style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontSize: 13 }}
                >
                  {r.name}
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
