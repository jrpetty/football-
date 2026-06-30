import { Link, useParams } from 'react-router-dom'
import { useData, usePlayer, useActions } from '../store/store'
import {
  playerAggregate,
  playerMatchLog,
  playerRatingTrend,
  playerTouches,
  playerPercentiles,
  playerVideoMoments,
  overallRating,
} from '../analytics/selectors'
import {
  fmtDateShort,
  num,
  pct,
  POSITION_GROUP_LABEL,
  POSITION_LABEL,
  RESULT_COLOR,
  resultOf,
  ratingColor,
} from '../utils/format'
import { fmtClock } from '../utils/video'
import { tagMeta } from '../data/tags'
import { StatCard } from '../components/ui/StatCard'
import { RadarChart } from '../components/charts/RadarChart'
import { LineChart } from '../components/charts/LineChart'
import { BarChart } from '../components/charts/BarChart'
import { Heatmap } from '../components/pitch/Heatmap'
import {
  Avatar,
  PositionBadge,
  StatusBadge,
  RatingChip,
  AttributeBars,
  EmptyState,
} from '../components/ui/Primitives'

const RADAR_AXES = ['Pace', 'Shooting', 'Passing', 'Dribbling', 'Defending', 'Physical']

function pctColor(p: number): string {
  if (p >= 80) return '#22c55e'
  if (p >= 60) return '#84cc16'
  if (p >= 40) return '#eab308'
  if (p >= 20) return '#f97316'
  return '#ef4444'
}

export function PlayerProfile() {
  const { playerId } = useParams()
  const data = useData()
  const player = usePlayer(playerId)
  const actions = useActions()

  if (!player) {
    return (
      <div className="content">
        <div className="page-head">
          <h1 className="page-title">Player not found</h1>
          <p className="page-subtitle">This player is no longer in the squad.</p>
        </div>
        <div className="card card-pad">
          <EmptyState
            icon="🔍"
            title="No player matches that id"
            hint={<Link className="link" to="/squad">← Back to squad</Link>}
          />
        </div>
      </div>
    )
  }

  const id = player.id
  const agg = playerAggregate(data, id)
  const touches = playerTouches(data, id)
  const trend = playerRatingTrend(data, id)
  const log = playerMatchLog(data, id)
  const ovr = overallRating(player)
  const percentiles = playerPercentiles(data, id)
  const moments = playerVideoMoments(data, id)

  const a = player.attributes
  const radarValues = [a.pace, a.shooting, a.passing, a.dribbling, a.defending, a.physical]

  const ratingValues = trend.map((t) => Math.round(t.rating * 10) / 10)
  const ratingLabels = trend.map((t) => t.label)

  // newest first for the match log table
  const logRows = [...log].reverse()

  // Strengths / focus areas derived from peer percentiles.
  const strengths = percentiles.rows.filter((r) => r.percentile >= 70).sort((x, y) => y.percentile - x.percentile).slice(0, 4)
  const focus = percentiles.rows.filter((r) => r.percentile <= 35).sort((x, y) => x.percentile - y.percentile).slice(0, 4)

  // Goal contribution per match (chronological) for the timeline chart.
  const contribData = log.map(({ match, stat }) => ({
    label: `${match.venue === 'Home' ? 'v' : '@'}${match.opponent.slice(0, 3)}`,
    value: stat.goals + stat.assists,
    color: player.color,
  }))

  return (
    <div className="content">
      <div className="page-head">
        <div className="row between center wrap gap-sm">
          <div>
            <h1 className="page-title">{player.name}</h1>
            <p className="page-subtitle">
              {POSITION_LABEL[player.position]} · #{player.number} · {player.nationality}
            </p>
          </div>
          <div className="row gap-sm center">
            <Link className="btn ghost sm" to="/squad">← Squad</Link>
            <Link className="btn ghost sm" to="/compare">Compare</Link>
          </div>
        </div>
      </div>

      {/* Header / identity card */}
      <div className="card card-pad">
        <div className="row between wrap gap-lg">
          <div className="row gap-lg center" style={{ minWidth: 0 }}>
            <div style={{ position: 'relative', flex: '0 0 auto' }}>
              <Avatar player={player} size="lg" />
              <span
                className="mono bold"
                style={{
                  position: 'absolute',
                  bottom: -6,
                  right: -6,
                  background: 'var(--bg, #0e141b)',
                  border: `2px solid ${player.color}`,
                  borderRadius: 10,
                  padding: '1px 7px',
                  fontSize: 13,
                }}
              >
                {player.number}
              </span>
            </div>
            <div className="col gap-sm" style={{ minWidth: 0 }}>
              <div className="row gap-sm center wrap">
                <PositionBadge group={player.positionGroup} code={player.position} />
                <span className="bold" style={{ fontSize: 15 }}>{POSITION_LABEL[player.position]}</span>
                <StatusBadge status={player.status} />
              </div>
              {player.altPositions.length > 0 && (
                <div className="row gap-sm center wrap">
                  <span className="muted tiny">Also:</span>
                  {player.altPositions.map((p) => (
                    <span key={p} className="badge">{POSITION_LABEL[p]}</span>
                  ))}
                </div>
              )}
            </div>
          </div>

          <div
            className="row wrap"
            style={{ gap: 18, alignItems: 'flex-start' }}
          >
            <IdentityFact label="Age" value={`${player.age}`} />
            <IdentityFact label="Foot" value={player.foot} />
            <IdentityFact label="Height" value={`${player.heightCm} cm`} />
            <IdentityFact label="Weight" value={`${player.weightKg} kg`} />
            <IdentityFact label="Joined" value={`${player.joinedYear}`} />
            <IdentityFact label="Value" value={`€${num(player.marketValueM, 1)}M`} accent="#22c55e" />
            <IdentityFact label="Overall" value={`${ovr}`} accent={player.color} />
          </div>
        </div>
      </div>

      {/* Two-column body */}
      <div className="grid grid-3" style={{ marginTop: 18, alignItems: 'start' }}>
        {/* LEFT column */}
        <div className="col gap-lg" style={{ gridColumn: 'span 1' }}>
          <div className="card card-pad">
            <div className="card-head">
              <h3>Attributes</h3>
              <span className="sub">six-axis profile</span>
            </div>
            <div className="row between center" style={{ marginTop: 4, marginBottom: 6 }}>
              <span className="muted tiny">Overall rating</span>
              <span
                className="bold tnum"
                style={{ fontSize: 30, lineHeight: 1, color: player.color }}
              >
                {ovr}
              </span>
            </div>
            <RadarChart
              axes={RADAR_AXES}
              series={[{ name: player.name, color: player.color, values: radarValues }]}
              max={99}
              size={280}
              showLabels
            />
            <div className="divider" style={{ margin: '12px 0' }} />
            <AttributeBars attributes={player.attributes} />
          </div>

          <div className="card card-pad">
            <div className="card-head">
              <h3>Average positioning</h3>
              <span className="sub">{touches.length} touches</span>
            </div>
            {touches.length === 0 ? (
              <p className="muted tiny" style={{ marginTop: 8 }}>
                No touch data recorded for this player yet.
              </p>
            ) : (
              <>
                <div style={{ marginTop: 8 }}>
                  <Heatmap points={touches} />
                </div>
                <p className="muted tiny" style={{ marginTop: 8 }}>
                  Touch density across all appearances — attacking direction is up the pitch.
                </p>
              </>
            )}
          </div>
        </div>

        {/* RIGHT column */}
        <div className="col gap-lg" style={{ gridColumn: 'span 2' }}>
          <div className="card card-pad">
            <div className="card-head">
              <h3>Season aggregate</h3>
              <span className="sub">{agg.appearances} apps · {agg.minutes} mins</span>
            </div>

            {agg.appearances === 0 ? (
              <EmptyState
                icon="📊"
                title="No match data yet"
                hint="Stats appear once this player features in a recorded match."
              />
            ) : (
              <>
                <div className="grid grid-3" style={{ marginTop: 8 }}>
                  <StatCard
                    label="Appearances"
                    value={agg.appearances}
                    icon="👕"
                    sub={<span className="muted">{agg.starts} starts</span>}
                  />
                  <StatCard
                    label="Minutes"
                    value={num(agg.minutes)}
                    icon="⏱️"
                    accent="#3b82f6"
                  />
                  <StatCard
                    label="Avg rating"
                    value={num(agg.avgRating, 1)}
                    icon="⭐"
                    accent={ratingColor(agg.avgRating)}
                  />
                  <StatCard
                    label="Goals"
                    value={agg.goals}
                    icon="⚽"
                    accent="#ef4444"
                    sub={<span className="muted">{num(agg.goalsPer90, 2)}/90</span>}
                  />
                  <StatCard
                    label="Assists"
                    value={agg.assists}
                    icon="🅰️"
                    accent="#84cc16"
                    sub={<span className="muted">{num(agg.assistsPer90, 2)}/90</span>}
                  />
                  <StatCard
                    label="Goal contributions"
                    value={agg.goalContributions}
                    icon="✨"
                    accent="#eab308"
                    sub={<span className="muted">{num(agg.contributionsPer90, 2)}/90</span>}
                  />
                  <StatCard
                    label="Expected goals"
                    value={num(agg.xg, 1)}
                    unit="xG"
                    icon="📈"
                    accent="#a855f7"
                  />
                  <StatCard
                    label="Pass accuracy"
                    value={pct(agg.passAccuracy, 1)}
                    icon="🎯"
                    accent="#22c55e"
                    sub={<span className="muted">{num(agg.passesCompleted)}/{num(agg.passes)}</span>}
                  />
                  <StatCard
                    label="Duel win rate"
                    value={pct(agg.duelWinRate, 1)}
                    icon="💪"
                    accent="#f97316"
                    sub={<span className="muted">{agg.duelsWon}/{agg.duelsTotal}</span>}
                  />
                  <StatCard
                    label="Tackles"
                    value={agg.tackles}
                    icon="🛡️"
                    accent="#3b82f6"
                  />
                  <StatCard
                    label="Interceptions"
                    value={agg.interceptions}
                    icon="🔒"
                    accent="#3b82f6"
                  />
                  <StatCard
                    label="Distance"
                    value={num(agg.distanceKm, 1)}
                    unit="km"
                    icon="🏃"
                    accent="#14b8a6"
                  />
                </div>

                <div className="divider" style={{ margin: '14px 0 10px' }} />
                <div className="row wrap" style={{ gap: 20 }}>
                  <div className="kv">
                    <span className="k">Goals / 90</span>
                    <span className="v mono">{num(agg.goalsPer90, 2)}</span>
                  </div>
                  <div className="kv">
                    <span className="k">Assists / 90</span>
                    <span className="v mono">{num(agg.assistsPer90, 2)}</span>
                  </div>
                  <div className="kv">
                    <span className="k">Contributions / 90</span>
                    <span className="v mono">{num(agg.contributionsPer90, 2)}</span>
                  </div>
                  <div className="kv">
                    <span className="k">Key passes</span>
                    <span className="v mono">{agg.keyPasses}</span>
                  </div>
                  <div className="kv">
                    <span className="k">Shots (on target)</span>
                    <span className="v mono">{agg.shots} ({agg.shotsOnTarget})</span>
                  </div>
                </div>
              </>
            )}
          </div>

          {/* Percentile ranking vs position peers */}
          <div className="card card-pad">
            <div className="card-head">
              <h3>Percentile rank</h3>
              <span className="sub">vs {POSITION_GROUP_LABEL[percentiles.group]}s · {percentiles.peers} players</span>
            </div>
            {agg.appearances === 0 || percentiles.peers < 2 ? (
              <p className="muted tiny" style={{ marginTop: 8 }}>Not enough peer data to rank this player yet.</p>
            ) : (
              <>
                {(strengths.length > 0 || focus.length > 0) && (
                  <div className="row wrap gap-lg" style={{ marginTop: 4, marginBottom: 14 }}>
                    {strengths.length > 0 && (
                      <div className="col gap-sm" style={{ minWidth: 200, flex: 1 }}>
                        <span className="muted tiny bold" style={{ textTransform: 'uppercase', letterSpacing: 0.5 }}>💪 Strengths</span>
                        <div className="tag-list">
                          {strengths.map((s) => (
                            <span key={s.key} className="badge" style={{ borderColor: 'rgba(34,197,94,0.4)', color: '#22c55e' }}>{s.label} · {s.percentile}%</span>
                          ))}
                        </div>
                      </div>
                    )}
                    {focus.length > 0 && (
                      <div className="col gap-sm" style={{ minWidth: 200, flex: 1 }}>
                        <span className="muted tiny bold" style={{ textTransform: 'uppercase', letterSpacing: 0.5 }}>🎯 Focus areas</span>
                        <div className="tag-list">
                          {focus.map((s) => (
                            <span key={s.key} className="badge" style={{ borderColor: 'rgba(239,68,68,0.4)', color: '#f97316' }}>{s.label} · {s.percentile}%</span>
                          ))}
                        </div>
                      </div>
                    )}
                  </div>
                )}
                <div className="col" style={{ gap: 9 }}>
                  {percentiles.rows.map((r) => (
                    <div key={r.key} style={{ display: 'grid', gridTemplateColumns: '132px 1fr 96px', alignItems: 'center', gap: 10 }}>
                      <span className="tiny muted">{r.label}</span>
                      <span className="meter"><span style={{ width: `${r.percentile}%`, background: pctColor(r.percentile) }} /></span>
                      <span className="tiny tnum" style={{ textAlign: 'right' }}>
                        <span className="bold" style={{ color: pctColor(r.percentile) }}>{r.percentile}</span>
                        <span className="faint"> · {r.display}</span>
                      </span>
                    </div>
                  ))}
                </div>
                <p className="faint tiny" style={{ marginTop: 10 }}>
                  Percentile vs squad peers in the same position — higher means the player ranks above more team-mates per 90.
                </p>
              </>
            )}
          </div>

          {/* Goal contributions per match */}
          {contribData.length > 0 && (
            <div className="card card-pad">
              <div className="card-head">
                <h3>Goal contributions</h3>
                <span className="sub">goals + assists by match</span>
              </div>
              <div style={{ marginTop: 10 }}>
                <BarChart data={contribData} digits={0} accent={player.color} />
              </div>
            </div>
          )}

          {/* Player film — tagged video moments */}
          <div className="card card-pad">
            <div className="card-head">
              <h3>Film</h3>
              <span className="sub">{moments.length} tagged moments</span>
            </div>
            {moments.length === 0 ? (
              <p className="muted tiny" style={{ marginTop: 8 }}>
                No video moments tagged to this player yet. Tag them on the{' '}
                <Link className="link" to="/video">Video Analysis</Link> screen and link the player.
              </p>
            ) : (
              <div className="col" style={{ gap: 8, marginTop: 6 }}>
                {moments.map(({ video, tag }) => {
                  const meta = tagMeta(tag.category)
                  return (
                    <Link
                      key={tag.id}
                      to={`/video/${video.id}?t=${Math.floor(tag.time)}`}
                      className="panel"
                      style={{ padding: 10, borderLeft: `3px solid ${meta.color}`, display: 'block' }}
                    >
                      <div className="row between center" style={{ gap: 8 }}>
                        <span className="badge mono" style={{ borderColor: meta.color, color: meta.color }}>▶ {fmtClock(tag.time)}</span>
                        <span className="tiny" style={{ color: meta.color, fontWeight: 700 }}>{meta.icon} {tag.category}</span>
                        <span style={{ flex: 1 }} />
                        {tag.drawing && tag.drawing.length > 0 && <span title="Has telestration" className="tiny">✏️</span>}
                        <span className="faint tiny">{video.title}</span>
                      </div>
                      <div className="bold" style={{ marginTop: 5 }}>{tag.title || '(untitled)'}</div>
                      {tag.note && <div className="tiny muted" style={{ marginTop: 2 }}>{tag.note}</div>}
                    </Link>
                  )
                })}
              </div>
            )}
          </div>

          <div className="card card-pad">
            <div className="card-head">
              <h3>Rating trend</h3>
              <span className="sub">match by match</span>
            </div>
            {ratingValues.length < 2 ? (
              <p className="muted tiny" style={{ marginTop: 8 }}>
                Not enough appearances to plot a trend yet
                {ratingValues.length === 1 ? ` (latest: ${num(ratingValues[0], 1)}).` : '.'}
              </p>
            ) : (
              <LineChart
                labels={ratingLabels}
                series={[
                  { name: 'Rating', color: player.color, values: ratingValues, area: true },
                ]}
                min={0}
                max={10}
                height={200}
              />
            )}
          </div>

          <div className="card card-pad">
            <div className="card-head">
              <h3>Match log</h3>
              <span className="sub">{logRows.length} appearances · newest first</span>
            </div>
            {logRows.length === 0 ? (
              <EmptyState icon="📋" title="No appearances recorded" />
            ) : (
              <div className="scroll-x" style={{ marginTop: 6 }}>
                <table className="table">
                  <thead>
                    <tr>
                      <th>Date</th>
                      <th>Opponent</th>
                      <th className="center-col">Res</th>
                      <th>Pos</th>
                      <th className="num">Min</th>
                      <th className="num">G</th>
                      <th className="num">A</th>
                      <th className="num">Sh</th>
                      <th className="num">Pass</th>
                      <th className="num">Rating</th>
                    </tr>
                  </thead>
                  <tbody>
                    {logRows.map(({ match, stat }) => {
                      const r = resultOf(match.goalsFor, match.goalsAgainst)
                      return (
                        <tr key={match.id}>
                          <td className="mono tiny">{fmtDateShort(match.date)}</td>
                          <td>
                            <Link className="link" to={`/matches/${match.id}`}>
                              {match.venue === 'Home' ? 'vs' : '@'} {match.opponent}
                            </Link>
                          </td>
                          <td className="center-col">
                            <span
                              className="form-pip"
                              style={{ background: RESULT_COLOR[r] }}
                              title={`${match.goalsFor}–${match.goalsAgainst}`}
                            >
                              {r}
                            </span>
                          </td>
                          <td>
                            <span className="pos-pill" style={{ background: 'var(--panel, #1a2230)' }}>
                              {stat.position}
                            </span>
                          </td>
                          <td className="num tnum">{stat.minutes}</td>
                          <td className="num tnum">{stat.goals || ''}</td>
                          <td className="num tnum">{stat.assists || ''}</td>
                          <td className="num tnum">{stat.shots || ''}</td>
                          <td className="num tnum">{stat.passesCompleted}/{stat.passes}</td>
                          <td className="num">
                            <RatingChip rating={stat.rating} small />
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </div>

          <div className="card card-pad">
            <div className="card-head">
              <h3>Coach notes</h3>
              <span className="sub">scouting & development</span>
            </div>
            <div className="field" style={{ marginTop: 8 }}>
              <textarea
                className="textarea"
                rows={4}
                value={player.notes}
                placeholder="Strengths, areas to develop, role in the side…"
                onChange={(e) => actions.updatePlayer({ ...player, notes: e.target.value })}
              />
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}

function IdentityFact({
  label,
  value,
  accent,
}: {
  label: string
  value: string
  accent?: string
}) {
  return (
    <div className="col" style={{ gap: 2 }}>
      <span className="muted tiny" style={{ textTransform: 'uppercase', letterSpacing: 0.5 }}>
        {label}
      </span>
      <span className="bold tnum" style={{ fontSize: 16, color: accent }}>
        {value}
      </span>
    </div>
  )
}
