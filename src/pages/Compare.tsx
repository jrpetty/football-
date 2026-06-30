import { useMemo, useState } from 'react'
import { useData } from '../store/store'
import { overallRating, playerAggregate } from '../analytics/selectors'
import type { PlayerAggregate } from '../analytics/selectors'
import { num, pct } from '../utils/format'
import { RadarChart } from '../components/charts/RadarChart'
import { BarChart } from '../components/charts/BarChart'
import { StatCard } from '../components/ui/StatCard'
import {
  Avatar, PositionBadge, RatingChip, EmptyState, ATTR_ORDER, ATTR_LABEL,
} from '../components/ui/Primitives'
import type { Player } from '../types'

// Up to three comparison slots. `''` means an empty slot.
type SlotId = string

// One row of the season-stats comparison table.
interface StatRow {
  label: string
  // Raw numeric value used to find the best performer in the row.
  get: (a: PlayerAggregate) => number
  // Formatted cell text.
  fmt: (a: PlayerAggregate) => string
}

const STAT_ROWS: StatRow[] = [
  { label: 'Appearances', get: (a) => a.appearances, fmt: (a) => num(a.appearances) },
  { label: 'Minutes', get: (a) => a.minutes, fmt: (a) => num(a.minutes) },
  { label: 'Avg rating', get: (a) => a.avgRating, fmt: (a) => a.avgRating.toFixed(2) },
  { label: 'Goals', get: (a) => a.goals, fmt: (a) => num(a.goals) },
  { label: 'Assists', get: (a) => a.assists, fmt: (a) => num(a.assists) },
  { label: 'Goal contributions', get: (a) => a.goalContributions, fmt: (a) => num(a.goalContributions) },
  { label: 'Goals / 90', get: (a) => a.goalsPer90, fmt: (a) => a.goalsPer90.toFixed(2) },
  { label: 'Assists / 90', get: (a) => a.assistsPer90, fmt: (a) => a.assistsPer90.toFixed(2) },
  { label: 'xG', get: (a) => a.xg, fmt: (a) => a.xg.toFixed(1) },
  { label: 'Shots', get: (a) => a.shots, fmt: (a) => num(a.shots) },
  { label: 'Key passes', get: (a) => a.keyPasses, fmt: (a) => num(a.keyPasses) },
  { label: 'Pass accuracy %', get: (a) => a.passAccuracy, fmt: (a) => pct(a.passAccuracy, 1) },
  { label: 'Tackles', get: (a) => a.tackles, fmt: (a) => num(a.tackles) },
  { label: 'Interceptions', get: (a) => a.interceptions, fmt: (a) => num(a.interceptions) },
  { label: 'Duel win %', get: (a) => a.duelWinRate, fmt: (a) => pct(a.duelWinRate, 1) },
  { label: 'Distance km', get: (a) => a.distanceKm, fmt: (a) => a.distanceKm.toFixed(1) },
]

export function Compare() {
  const data = useData()
  const players = data.players

  // Two highest-rated players preselected; third slot empty by default.
  const topTwo = useMemo(() => {
    return [...players]
      .sort((a, b) => overallRating(b) - overallRating(a))
      .slice(0, 2)
      .map((p) => p.id)
  }, [players])

  const [slots, setSlots] = useState<[SlotId, SlotId, SlotId]>(() => [
    topTwo[0] ?? '',
    topTwo[1] ?? '',
    '',
  ])

  const setSlot = (index: number, id: SlotId) => {
    setSlots((prev) => {
      const next = [...prev] as [SlotId, SlotId, SlotId]
      next[index] = id
      return next
    })
  }

  // Selected, de-duplicated players in slot order.
  const selected = useMemo<Player[]>(() => {
    const seen = new Set<string>()
    const out: Player[] = []
    for (const id of slots) {
      if (!id || seen.has(id)) continue
      const p = players.find((pl) => pl.id === id)
      if (p) {
        seen.add(id)
        out.push(p)
      }
    }
    return out
  }, [slots, players])

  const aggregates = useMemo<PlayerAggregate[]>(
    () => selected.map((p) => playerAggregate(data, p.id)),
    [selected, data],
  )

  if (players.length === 0) {
    return (
      <div className="content">
        <div className="page-head">
          <div className="page-title">Player Comparison</div>
          <div className="page-subtitle">Stack players side by side across attributes and season output.</div>
        </div>
        <div className="card card-pad">
          <EmptyState icon="🆚" title="No players to compare" hint="Add players to your squad first." />
        </div>
      </div>
    )
  }

  const radarSeries = selected.map((p) => ({
    name: p.name,
    color: p.color,
    values: ATTR_ORDER.map((k) => p.attributes[k]),
  }))

  // Index of the best (max) value per stat row, for highlighting.
  const bestIndexFor = (row: StatRow): number => {
    if (aggregates.length === 0) return -1
    let best = 0
    for (let i = 1; i < aggregates.length; i++) {
      if (row.get(aggregates[i]) > row.get(aggregates[best])) best = i
    }
    // Avoid highlighting an all-zero row.
    return row.get(aggregates[best]) > 0 ? best : -1
  }

  return (
    <div className="content">
      <div className="page-head">
        <div className="page-title">Player Comparison</div>
        <div className="page-subtitle">
          Pick two or three players to compare attributes, season form and underlying output head to head.
        </div>
      </div>

      {/* ---------------------------------------------------------- Controls */}
      <div className="card card-pad" style={{ marginBottom: 16 }}>
        <div className="grid grid-3">
          {([0, 1, 2] as const).map((i) => {
            const cur = players.find((p) => p.id === slots[i])
            return (
              <div className="field" key={i}>
                <label>{i === 2 ? 'Player C (optional)' : `Player ${String.fromCharCode(65 + i)}`}</label>
                <div className="row gap-sm" style={{ alignItems: 'center' }}>
                  {cur ? (
                    <Avatar player={cur} size="sm" />
                  ) : (
                    <span
                      className="avatar sm"
                      style={{ background: 'var(--bg-2)', color: 'var(--text-faint)' }}
                    >
                      —
                    </span>
                  )}
                  <select
                    className="select flex-1"
                    value={slots[i]}
                    onChange={(e) => setSlot(i, e.target.value)}
                  >
                    {i === 2 && <option value="">— none —</option>}
                    {i !== 2 && <option value="">— select —</option>}
                    {players.map((p) => (
                      <option key={p.id} value={p.id}>
                        {p.number} · {p.name}
                      </option>
                    ))}
                  </select>
                </div>
              </div>
            )
          })}
        </div>
      </div>

      {selected.length === 0 ? (
        <div className="card card-pad">
          <EmptyState icon="🆚" title="Select players to compare" hint="Choose at least one player above." />
        </div>
      ) : (
        <>
          {/* ----------------------------------------------- Headline cards */}
          <div className="grid grid-3" style={{ marginBottom: 16 }}>
            {selected.map((p, i) => {
              const a = aggregates[i]
              return (
                <div className="card card-pad" key={p.id}>
                  <div className="row between" style={{ alignItems: 'flex-start' }}>
                    <div className="row gap-sm" style={{ alignItems: 'center' }}>
                      <Avatar player={p} size="md" />
                      <div>
                        <div className="bold">{p.name}</div>
                        <div className="muted tiny">
                          #{p.number} · {p.nationality} · {p.age}y
                        </div>
                      </div>
                    </div>
                    <PositionBadge group={p.positionGroup} code={p.position} />
                  </div>
                  <div className="row between" style={{ marginTop: 12, alignItems: 'center' }}>
                    <div className="col">
                      <span className="muted tiny">Overall</span>
                      <span className="bold mono" style={{ fontSize: 20, color: p.color }}>
                        {overallRating(p)}
                      </span>
                    </div>
                    <div className="col center">
                      <span className="muted tiny">Avg match</span>
                      <RatingChip rating={a.avgRating} />
                    </div>
                    <div className="col" style={{ textAlign: 'right' }}>
                      <span className="muted tiny">Value</span>
                      <span className="bold mono" style={{ fontSize: 16 }}>€{p.marketValueM}M</span>
                    </div>
                  </div>
                </div>
              )
            })}
          </div>

          <div className="grid grid-2" style={{ marginBottom: 16, alignItems: 'start' }}>
            {/* ------------------------------------------- Attribute radar */}
            <div className="card card-pad">
              <div className="card-head">
                <h3>Attribute radar</h3>
                <span className="sub">Six core attributes (0–99)</span>
              </div>
              <RadarChart
                axes={ATTR_ORDER.map((k) => ATTR_LABEL[k])}
                series={radarSeries}
                max={99}
                size={300}
                showLabels
              />
              <div className="legend" style={{ marginTop: 10 }}>
                {selected.map((p) => (
                  <span className="item" key={p.id}>
                    <span className="dot" style={{ background: p.color }} />
                    {p.name}
                  </span>
                ))}
              </div>
            </div>

            {/* --------------------------------------- Attribute breakdown */}
            <div className="card card-pad">
              <div className="card-head">
                <h3>Attribute breakdown</h3>
                <span className="sub">Per-attribute, colored by player</span>
              </div>
              <div className="col" style={{ gap: 16 }}>
                {ATTR_ORDER.map((k) => (
                  <div key={k}>
                    <div className="muted tiny bold" style={{ marginBottom: 6, textTransform: 'uppercase' }}>
                      {ATTR_LABEL[k]}
                    </div>
                    <BarChart
                      data={selected.map((p) => ({
                        label: p.name,
                        value: p.attributes[k],
                        color: p.color,
                      }))}
                      max={99}
                      height={18}
                      showValue
                    />
                  </div>
                ))}
              </div>
            </div>
          </div>

          {/* ----------------------------------------- Output comparison */}
          <div className="grid grid-3" style={{ marginBottom: 16 }}>
            <StatCard
              label="Goals"
              value={num(Math.max(...aggregates.map((a) => a.goals)))}
              icon="⚽"
              accent="#22c55e"
              sub={
                <span className="tiny muted">
                  {topName(selected, aggregates, (a) => a.goals)}
                </span>
              }
            />
            <StatCard
              label="Assists"
              value={num(Math.max(...aggregates.map((a) => a.assists)))}
              icon="🅰️"
              accent="#38bdf8"
              sub={
                <span className="tiny muted">
                  {topName(selected, aggregates, (a) => a.assists)}
                </span>
              }
            />
            <StatCard
              label="Avg rating"
              value={Math.max(...aggregates.map((a) => a.avgRating)).toFixed(2)}
              icon="⭐"
              accent="#eab308"
              sub={
                <span className="tiny muted">
                  {topName(selected, aggregates, (a) => a.avgRating)}
                </span>
              }
            />
          </div>

          {/* --------------------------------------------- Season stats */}
          <div className="card card-pad">
            <div className="card-head">
              <h3>Season stats</h3>
              <span className="sub">Best in each row highlighted</span>
            </div>
            <div className="scroll-x">
              <table className="table">
                <thead>
                  <tr>
                    <th>Metric</th>
                    {selected.map((p) => (
                      <th key={p.id} className="num">
                        <span className="row gap-sm" style={{ justifyContent: 'flex-end', alignItems: 'center' }}>
                          <span className="dot" style={{ background: p.color }} />
                          {p.name}
                        </span>
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {STAT_ROWS.map((row) => {
                    const best = bestIndexFor(row)
                    return (
                      <tr key={row.label}>
                        <td className="muted">{row.label}</td>
                        {aggregates.map((a, i) => (
                          <td
                            key={selected[i].id}
                            className={`num${i === best ? ' bold text-green' : ''}`}
                          >
                            {row.fmt(a)}
                          </td>
                        ))}
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          </div>
        </>
      )}
    </div>
  )
}

// Name of the player with the highest value for a given accessor.
function topName(
  players: Player[],
  aggregates: PlayerAggregate[],
  get: (a: PlayerAggregate) => number,
): string {
  if (aggregates.length === 0) return '—'
  let best = 0
  for (let i = 1; i < aggregates.length; i++) {
    if (get(aggregates[i]) > get(aggregates[best])) best = i
  }
  return get(aggregates[best]) > 0 ? `Leader: ${players[best].name}` : 'No data yet'
}
