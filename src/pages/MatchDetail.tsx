import { useMemo } from 'react'
import { Link, useParams } from 'react-router-dom'
import type { MatchEvent, Player } from '../types'
import { useData, useMatch, useActions } from '../store/store'
import { AiPanel } from '../components/ai/AiPanel'
import { matchAnalysis } from '../ai/analyses'
import { TacticalReplay } from '../components/pitch/TacticalReplay'
import {
  fmtDate,
  resultOf,
  RESULT_COLOR,
  POSITION_GROUP_COLOR,
  POSITION_LABEL,
} from '../utils/format'
import { positionGroupOf } from '../data/formations'
import { Pitch, pitchX, pitchY } from '../components/pitch/Pitch'
import { ShotMap } from '../components/pitch/ShotMap'
import type { Shot } from '../components/pitch/ShotMap'
import { EmptyState, RatingChip, SectionTitle } from '../components/ui/Primitives'

const EVENT_ICON: Record<MatchEvent['type'], string> = {
  goal: '⚽',
  assist: '🅰',
  yellow: '🟨',
  red: '🟥',
  'sub-on': '🔺',
  'sub-off': '🔻',
  shot: '•',
  save: '🧤',
}

const EVENT_LABEL: Record<MatchEvent['type'], string> = {
  goal: 'Goal',
  assist: 'Assist',
  yellow: 'Yellow card',
  red: 'Red card',
  'sub-on': 'Substitution on',
  'sub-off': 'Substitution off',
  shot: 'Shot',
  save: 'Save',
}

function surnameOf(name: string): string {
  const parts = name.trim().split(/\s+/)
  return parts.length > 1 ? parts[parts.length - 1] : name
}

/** A labelled dual bar: our value (accent, left) vs their value (red, right). */
function DualBar({
  label,
  ours,
  theirs,
  unit = '',
  digits = 0,
}: {
  label: string
  ours: number
  theirs: number
  unit?: string
  digits?: number
}) {
  const total = ours + theirs
  const ourPct = total > 0 ? (ours / total) * 100 : 50
  const fmt = (v: number) => (digits > 0 ? v.toFixed(digits) : Math.round(v).toString())
  return (
    <div style={{ marginBottom: 14 }}>
      <div className="row between" style={{ marginBottom: 5 }}>
        <span className="bold tnum text-green">{fmt(ours)}{unit}</span>
        <span className="muted tiny">{label}</span>
        <span className="bold tnum text-red">{fmt(theirs)}{unit}</span>
      </div>
      <div
        style={{
          display: 'flex',
          height: 9,
          borderRadius: 6,
          overflow: 'hidden',
          background: 'rgba(255,255,255,0.06)',
        }}
      >
        <div style={{ width: `${ourPct}%`, background: '#22c55e' }} />
        <div style={{ width: `${100 - ourPct}%`, background: '#ef4444' }} />
      </div>
    </div>
  )
}

export function MatchDetail() {
  const { matchId } = useParams()
  const match = useMatch(matchId)
  const data = useData()
  const actions = useActions()

  const playerById = useMemo(() => {
    const map = new Map<string, Player>()
    for (const p of data.players) map.set(p.id, p)
    return map
  }, [data.players])

  if (!match) {
    return (
      <div className="content">
        <div className="page-head">
          <div className="page-title">Match not found</div>
          <div className="page-subtitle">This fixture may have been removed.</div>
        </div>
        <EmptyState
          icon="🔍"
          title="No such match"
          hint="The match you are looking for does not exist."
        />
        <div style={{ marginTop: 16 }}>
          <Link className="btn" to="/matches">← Back to matches</Link>
        </div>
      </div>
    )
  }

  const result = resultOf(match.goalsFor, match.goalsAgainst)
  const resultColor = RESULT_COLOR[result]
  const resultText = result === 'W' ? 'Win' : result === 'D' ? 'Draw' : 'Loss'

  const starters = match.lineup.filter((s) => s.isStarter)

  // Assist lookup: for a goal event, find an assist event at the same minute
  // whose related player matches the scorer (or that references the scorer).
  const assistForGoal = (goal: MatchEvent): Player | undefined => {
    const a = match.events.find(
      (e) =>
        e.type === 'assist' &&
        e.minute === goal.minute &&
        (e.relatedPlayerId === goal.playerId || goal.relatedPlayerId === e.playerId),
    )
    if (a) return playerById.get(a.playerId)
    if (goal.relatedPlayerId) return playerById.get(goal.relatedPlayerId)
    return undefined
  }

  const timeline = [...match.events]
    .filter((e) => e.type !== 'assist')
    .sort((a, b) => a.minute - b.minute)

  const shots: Shot[] = match.events
    .filter(
      (e) =>
        (e.type === 'goal' || e.type === 'shot') &&
        typeof e.x === 'number' &&
        typeof e.y === 'number',
    )
    .map((e) => ({
      x: e.x as number,
      y: e.y as number,
      goal: e.type === 'goal',
      onTarget: e.type === 'goal' ? true : e.onTarget ?? false,
      label: playerById.get(e.playerId)?.name,
    }))

  const ratings = [...match.playerStats].sort((a, b) => b.rating - a.rating)

  return (
    <div className="content">
      <div className="page-head">
        <div className="row between wrap">
          <div>
            <div className="page-title">vs {match.opponent}</div>
            <div className="page-subtitle">
              {fmtDate(match.date)} · {match.competition} · {match.venue}
            </div>
          </div>
          <Link className="btn ghost" to="/matches">← All matches</Link>
        </div>
      </div>

      {/* Header / scoreline ------------------------------------------------ */}
      <div className="card card-pad" style={{ marginBottom: 16 }}>
        <div className="row between wrap gap-lg">
          <div className="row center gap-lg">
            <div style={{ textAlign: 'center' }}>
              <div
                className="tnum bold"
                style={{ fontSize: 52, lineHeight: 1, letterSpacing: -1 }}
              >
                {match.goalsFor}<span className="muted"> – </span>{match.goalsAgainst}
              </div>
              <span
                className="badge solid"
                style={{
                  marginTop: 8,
                  background: `${resultColor}22`,
                  color: resultColor,
                  borderColor: `${resultColor}55`,
                }}
              >
                <span className="dot" style={{ background: resultColor }} />
                {resultText}
              </span>
            </div>
            <div className="divider" style={{ width: 1, height: 56 }} />
            <div className="col gap-sm">
              <div className="row gap-sm wrap">
                <span className="badge">{match.competition}</span>
                <span className="badge">{match.venue}</span>
                <span className="badge">Formation {match.formation}</span>
              </div>
              <div className="muted tiny">{fmtDate(match.date)}</div>
            </div>
          </div>
          <div className="row gap-lg" style={{ textAlign: 'right' }}>
            <div>
              <div className="stat-value tnum">{match.possession}<span className="unit">%</span></div>
              <div className="stat-label">Possession</div>
            </div>
            <div>
              <div className="stat-value tnum">{match.xgFor.toFixed(1)}</div>
              <div className="stat-label">xG for</div>
            </div>
            <div>
              <div className="stat-value tnum">{match.xgAgainst.toFixed(1)}</div>
              <div className="stat-label">xG against</div>
            </div>
          </div>
        </div>
        {match.notes && (
          <>
            <div className="divider" style={{ margin: '14px 0' }} />
            <div className="muted" style={{ fontStyle: 'italic' }}>{match.notes}</div>
          </>
        )}
      </div>

      <div className="grid grid-2">
        {/* Match stats ----------------------------------------------------- */}
        <div className="card card-pad">
          <SectionTitle>Match stats</SectionTitle>
          <div className="row between tiny muted" style={{ margin: '4px 0 12px' }}>
            <span className="text-green bold">Us</span>
            <span className="text-red bold">{match.opponent}</span>
          </div>
          <DualBar label="Possession" ours={match.possession} theirs={100 - match.possession} unit="%" />
          <DualBar label="Shots" ours={match.shotsFor} theirs={match.shotsAgainst} />
          <DualBar label="Expected goals (xG)" ours={match.xgFor} theirs={match.xgAgainst} digits={2} />
          <DualBar label="Corners" ours={match.cornersFor} theirs={match.cornersAgainst} />
        </div>

        {/* Lineup ---------------------------------------------------------- */}
        <div className="card card-pad">
          <SectionTitle action={<span className="muted tiny">{match.formation}</span>}>
            Starting XI
          </SectionTitle>
          {starters.length === 0 ? (
            <EmptyState icon="👥" title="No lineup recorded" />
          ) : (
            <Pitch>
              {starters.map((slot) => {
                const player = playerById.get(slot.playerId)
                const group = positionGroupOf(slot.position)
                const cx = pitchX(slot.x)
                const cy = pitchY(slot.y)
                return (
                  <g key={slot.playerId}>
                    <circle
                      cx={cx}
                      cy={cy}
                      r={3.4}
                      fill={POSITION_GROUP_COLOR[group]}
                      stroke="#0a0f1a"
                      strokeWidth={0.5}
                    />
                    <text
                      x={cx}
                      y={cy + 1.3}
                      textAnchor="middle"
                      fontSize={3.4}
                      fontWeight={800}
                      fill="#0a0f1a"
                    >
                      {player?.number ?? '?'}
                    </text>
                    <text
                      x={cx}
                      y={cy + 7.6}
                      textAnchor="middle"
                      fontSize={3}
                      fontWeight={600}
                      fill="rgba(255,255,255,0.92)"
                    >
                      {player ? surnameOf(player.name) : 'Unknown'}
                    </text>
                  </g>
                )
              })}
            </Pitch>
          )}
        </div>
      </div>

      <div className="grid grid-2" style={{ marginTop: 16 }}>
        {/* Timeline -------------------------------------------------------- */}
        <div className="card card-pad">
          <SectionTitle>Timeline</SectionTitle>
          {timeline.length === 0 ? (
            <EmptyState icon="⏱" title="No events recorded" />
          ) : (
            <div className="col" style={{ gap: 2 }}>
              {timeline.map((e) => {
                const player = playerById.get(e.playerId)
                const assister = e.type === 'goal' ? assistForGoal(e) : undefined
                return (
                  <div
                    key={e.id}
                    className="row gap-sm"
                    style={{ padding: '7px 0', borderBottom: '1px solid rgba(255,255,255,0.05)' }}
                  >
                    <span className="mono tnum muted" style={{ width: 34, textAlign: 'right' }}>
                      {e.minute}&apos;
                    </span>
                    <span style={{ width: 22, textAlign: 'center' }} title={EVENT_LABEL[e.type]}>
                      {EVENT_ICON[e.type]}
                    </span>
                    <div className="flex-1">
                      {player ? (
                        <Link className="link bold" to={`/squad/${player.id}`}>{player.name}</Link>
                      ) : (
                        <span className="muted">Unknown player</span>
                      )}
                      {assister ? (
                        <span className="muted tiny"> · assist{' '}
                          <Link className="link" to={`/squad/${assister.id}`}>{assister.name}</Link>
                        </span>
                      ) : (
                        e.note && <span className="muted tiny"> · {e.note}</span>
                      )}
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </div>

        {/* Shot map -------------------------------------------------------- */}
        <div className="card card-pad">
          <SectionTitle action={<span className="muted tiny">{shots.length} shots plotted</span>}>
            Shot map
          </SectionTitle>
          {shots.length === 0 ? (
            <EmptyState icon="🎯" title="No shot coordinates" hint="This match has no positional shot data." />
          ) : (
            <ShotMap shots={shots} />
          )}
        </div>
      </div>

      {/* Tactical view (2D animated) ------------------------------------- */}
      <div className="card card-pad" style={{ marginTop: 16 }}>
        <SectionTitle action={<span className="muted tiny">▶ press play</span>}>Tactical view</SectionTitle>
        <TacticalReplay match={match} players={data.players} />
      </div>

      {/* Player ratings -------------------------------------------------- */}
      <div className="card card-pad" style={{ marginTop: 16 }}>
        <SectionTitle>Player ratings</SectionTitle>
        {ratings.length === 0 ? (
          <EmptyState icon="📊" title="No player statistics" />
        ) : (
          <div className="scroll-x">
            <table className="table">
              <thead>
                <tr>
                  <th>Player</th>
                  <th className="center-col">Pos</th>
                  <th className="num">Min</th>
                  <th className="num">G</th>
                  <th className="num">A</th>
                  <th className="num">Sh</th>
                  <th className="num">Pass</th>
                  <th className="num">Tkl</th>
                  <th className="num">Rating</th>
                </tr>
              </thead>
              <tbody>
                {ratings.map((s) => {
                  const player = playerById.get(s.playerId)
                  return (
                    <tr key={s.playerId}>
                      <td>
                        <div className="player-cell">
                          {player ? (
                            <Link className="link bold" to={`/squad/${player.id}`}>
                              <span className="pname">{player.number}. {player.name}</span>
                            </Link>
                          ) : (
                            <span className="pname muted">Unknown</span>
                          )}
                        </div>
                      </td>
                      <td className="center-col">
                        <span className="pos-pill">{POSITION_LABEL[s.position]}</span>
                      </td>
                      <td className="num tnum">{s.minutes}</td>
                      <td className="num tnum">{s.goals}</td>
                      <td className="num tnum">{s.assists}</td>
                      <td className="num tnum">{s.shots}</td>
                      <td className="num tnum">{s.passesCompleted}/{s.passes}</td>
                      <td className="num tnum">{s.tackles}</td>
                      <td className="num">
                        <RatingChip rating={s.rating} small />
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      {/* AI analysis ------------------------------------------------------ */}
      <div style={{ marginTop: 16 }}>
        <AiPanel
          title="AI match breakdown"
          build={() => matchAnalysis(data, match.id)}
          onSave={(t) => actions.updateMatch({ ...match, notes: match.notes ? `${match.notes}\n\n${t}` : t })}
          saveLabel="Append to notes"
        />
      </div>
    </div>
  )
}
