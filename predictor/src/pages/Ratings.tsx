// League table and the ratings underneath it.
//
// The table is the familiar view; the ratings columns are what the model
// actually runs on, shown alongside so the two can be compared.

import { useSeason } from '../data/store.tsx'
import { FormRun, TeamChip } from '../components/primitives.tsx'

export default function Ratings() {
  const { season, loading } = useSeason()
  if (loading) return <div className="loading">Loading…</div>
  if (!season) return <div className="loading">No season data.</div>

  const started = season.teams.some((t) => t.played > 0)
  // Before a ball is kicked the table is 20 zeroes; rank by model strength instead.
  const rows = started
    ? season.teams
    : [...season.teams].sort((a, b) => b.attack + b.defence - (a.attack + a.defence))

  return (
    <>
      <div className="eyebrow">{season.season}</div>
      <h1 style={{ fontSize: 28, marginTop: 4, marginBottom: 6 }}>
        {started ? 'Table and ratings' : 'Model ratings'}
      </h1>
      <p className="muted" style={{ fontSize: 13.5, marginBottom: 18, maxWidth: 720, lineHeight: 1.6 }}>
        {started
          ? 'Attack and defence are log-scale ratings fitted across the last decade, weighted toward recent matches and blended with expected goals.'
          : 'No matches have been played yet, so this is ranked by model strength rather than points. Attack and defence are log-scale ratings: 0 is league average, and a promoted club with no top-flight record carries a wide uncertainty band.'}
      </p>

      <div className="panel" style={{ overflowX: 'auto' }}>
        <table className="data">
          <thead>
            <tr>
              <th style={{ width: 34 }}>#</th>
              <th>Team</th>
              {started && (
                <>
                  <th className="num">P</th>
                  <th className="num">W</th>
                  <th className="num">D</th>
                  <th className="num">L</th>
                  <th className="num">GF</th>
                  <th className="num">GA</th>
                  <th className="num">Pts</th>
                </>
              )}
              <th className="num">Attack</th>
              <th className="num">Defence</th>
              <th className="num">± </th>
              <th>Form</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((t, i) => (
              <tr key={t.code}>
                <td className="faint tabular">{i + 1}</td>
                <td>
                  <span className="row gap-8">
                    <TeamChip code={t.code} />
                    {t.promoted && <span className="badge badge-live" style={{ fontSize: 10 }}>promoted</span>}
                  </span>
                </td>
                {started && (
                  <>
                    <td className="num tabular">{t.played}</td>
                    <td className="num tabular">{t.won}</td>
                    <td className="num tabular">{t.drawn}</td>
                    <td className="num tabular">{t.lost}</td>
                    <td className="num tabular">{t.goalsFor}</td>
                    <td className="num tabular">{t.goalsAgainst}</td>
                    <td className="num tabular" style={{ fontWeight: 700 }}>{t.points}</td>
                  </>
                )}
                <td className="num tabular" style={{ color: t.attack >= 0 ? 'var(--home)' : 'var(--text-dim)' }}>
                  {t.attack >= 0 ? '+' : ''}{t.attack.toFixed(3)}
                </td>
                <td className="num tabular" style={{ color: t.defence >= 0 ? 'var(--good)' : 'var(--text-dim)' }}>
                  {t.defence >= 0 ? '+' : ''}{t.defence.toFixed(3)}
                </td>
                <td className="num tabular faint" title="Rating uncertainty — higher means the model knows less">
                  ±{t.ratingSd.toFixed(2)}
                </td>
                <td><FormRun form={t.form} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="faint" style={{ fontSize: 12, marginTop: 12, lineHeight: 1.55, maxWidth: 720 }}>
        Attack is how much a side scores relative to league average, on a log scale — +0.30 means
        roughly 35% more goals than average. Defence works the same way, with higher meaning fewer
        conceded. The ± column is how confident the model is in that rating.
      </p>
    </>
  )
}
