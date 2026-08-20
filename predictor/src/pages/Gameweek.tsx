// The gameweek board — the front page of the experiment.
//
// Every match in the round, grouped by day, each one clickable through to the
// full reasoning. The summary strip above deliberately leads with how the
// model has actually been doing rather than with its predictions, because a
// forecast without a track record is just an opinion.

import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { useSeason, useGameweek } from '../data/store.tsx'
import { FixtureCard } from '../components/FixtureCard.tsx'
import { Stat, formatDayLabel } from '../components/primitives.tsx'
import { clubName } from '../config/teams.ts'

export default function Gameweek() {
  const { season, accuracy, loading, error } = useSeason()
  const [params, setParams] = useSearchParams()
  const [gw, setGw] = useState<number | null>(null)

  // The URL is the source of truth for which gameweek is shown, so a link to
  // a particular round survives a refresh and can be shared.
  useEffect(() => {
    if (!season) return
    const fromUrl = Number(params.get('gw'))
    setGw(Number.isFinite(fromUrl) && fromUrl > 0 ? fromUrl : season.currentGameweek)
  }, [season, params])

  const { data, loading: gwLoading, error: gwError } = useGameweek(gw)

  const byDay = useMemo(() => {
    if (!data) return []
    const groups = new Map<string, typeof data.fixtures>()
    for (const f of [...data.fixtures].sort((a, b) => a.kickoff - b.kickoff)) {
      const key = new Date(f.kickoff).toISOString().slice(0, 10)
      if (!groups.has(key)) groups.set(key, [])
      groups.get(key)!.push(f)
    }
    return [...groups.entries()]
  }, [data])

  if (loading) return <div className="loading">Loading the season…</div>
  if (error || !season) {
    return (
      <div className="loading">
        Could not load the season data. {error}
      </div>
    )
  }

  const availableWeeks = season.allGameweeks
  const forecastable = new Set([...season.completedGameweeks, season.currentGameweek,
    season.currentGameweek + 1, season.currentGameweek + 2])

  const scored = accuracy?.overall.n ?? 0
  const upsetCount =
    data?.fixtures.filter((f) => f.upset.rating === 'live' || f.upset.rating === 'dangerous').length ?? 0
  const tossUps = data?.fixtures.filter((f) => f.upset.rating === 'toss-up').length ?? 0
  const biggest = data?.fixtures
    .slice()
    .filter((f) => f.upset.rating === 'live' || f.upset.rating === 'dangerous')
    .sort((a, b) => b.upset.upsetProbability - a.upset.upsetProbability)[0]

  return (
    <>
      <div className="section-title" style={{ alignItems: 'flex-end', marginBottom: 18 }}>
        <div>
          <div className="eyebrow">{season.season} Premier League</div>
          <h1 style={{ fontSize: 30, marginTop: 4 }}>Gameweek {gw ?? season.currentGameweek}</h1>
          {byDay.length > 0 && (
            <div className="muted" style={{ fontSize: 13.5, marginTop: 5 }}>
              {formatDayLabel(byDay[0]![1][0]!.kickoff)}
              {byDay.length > 1 && ` — ${formatDayLabel(byDay[byDay.length - 1]![1].slice(-1)[0]!.kickoff)}`}
            </div>
          )}
        </div>
      </div>

      <div className="gw-nav" style={{ marginBottom: 20 }}>
        {availableWeeks.map((w) => (
          <button
            key={w}
            className={`gw-btn${w === gw ? ' active' : ''}`}
            disabled={!forecastable.has(w)}
            title={forecastable.has(w) ? `Gameweek ${w}` : 'Not forecast yet — predictions are generated a few weeks ahead'}
            onClick={() => setParams({ gw: String(w) })}
          >
            {w}
          </button>
        ))}
      </div>

      {season.notes.length > 0 && (
        <div className="note" style={{ marginBottom: 20 }}>
          <span className="note-icon" aria-hidden="true">i</span>
          <span>{season.notes[0]}</span>
        </div>
      )}

      <div className="grid-3" style={{ marginBottom: 22 }}>
        <Stat
          label="Model record"
          value={scored > 0 ? `${((accuracy?.overall.accuracy ?? 0) * 100).toFixed(0)}%` : '—'}
          sub={
            scored > 0
              ? `${scored} matches scored · RPS ${(accuracy?.overall.rps ?? 0).toFixed(3)} vs ${(accuracy?.baseline.rps ?? 0).toFixed(3)} baseline`
              : 'No matches scored yet — the season starts here'
          }
        />
        <Stat
          label="Upsets in play"
          value={upsetCount}
          sub={
            biggest
              ? `${biggest.upset.underdog} at ${clubName(biggest.home) === biggest.upset.underdog ? clubName(biggest.away) : clubName(biggest.home)} is the liveliest, ${(biggest.upset.upsetProbability * 100).toFixed(0)}%`
              : `No clear favourite is in danger${tossUps > 0 ? ` — but ${tossUps} are too close to call` : ''}`
          }
        />
        <Stat
          label="Matches"
          value={data?.fixtures.length ?? '—'}
          sub={`Home advantage worth ${((Math.exp(season.params.homeAdvantage ?? 0) - 1) * 100).toFixed(0)}% this season`}
        />
      </div>

      {gwLoading && <div className="loading">Loading gameweek {gw}…</div>}
      {gwError && (
        <div className="note">
          <span className="note-icon">!</span>
          <span>
            Gameweek {gw} has not been forecast yet. Predictions are generated a few weeks ahead,
            because availability and form are only meaningful close to kickoff.
          </span>
        </div>
      )}

      {byDay.map(([day, fixtures]) => (
        <section key={day} style={{ marginBottom: 26 }}>
          <div className="eyebrow" style={{ marginBottom: 11 }}>{formatDayLabel(fixtures[0]!.kickoff)}</div>
          <div className="fixture-grid">
            {fixtures.map((f) => (
              <FixtureCard key={f.id} fixture={f} />
            ))}
          </div>
        </section>
      ))}
    </>
  )
}
