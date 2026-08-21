// League table and the ratings underneath it.
//
// The table is the familiar view; the ratings columns are what the model
// actually runs on, shown alongside so the two can be compared.

import { useSeason } from '../data/store.tsx'
import { FormRun, TeamChip } from '../components/primitives.tsx'

/**
 * Overall strength as a bar either side of league average.
 *
 * Diverging rather than sequential because zero is a real midpoint here — it
 * is the league mean, not an absence of strength — and a side below it is
 * qualitatively different from one above. The paler cap is the uncertainty on
 * the rating, so a promoted club visibly reads as "somewhere in this range"
 * rather than as a precise number.
 */
function StrengthBar({ value, max, uncertainty }: { value: number; max: number; uncertainty: number }) {
  const W = 120
  const H = 16
  const mid = W / 2
  const scale = max > 0 ? (W / 2 - 6) / max : 0
  const len = Math.abs(value) * scale
  const band = uncertainty * scale
  const positive = value >= 0
  const x = positive ? mid : mid - len
  return (
    <svg width={W} height={H} role="img" aria-label={`Overall rating ${value.toFixed(3)}`}>
      {/* Uncertainty band, drawn behind the value. */}
      <rect
        x={Math.max(2, mid + (positive ? len : -len) - band)}
        y={H / 2 - 6}
        width={Math.min(band * 2, W - 4)}
        height={12}
        rx={3}
        fill={positive ? 'rgba(57,135,229,0.18)' : 'rgba(230,103,103,0.18)'}
      />
      <rect x={x} y={H / 2 - 4} width={Math.max(1.5, len)} height={8} rx={2}
        fill={positive ? 'var(--home)' : 'var(--away)'} />
      <line x1={mid} y1={1} x2={mid} y2={H - 1} stroke="var(--border)" strokeWidth={1} />
    </svg>
  )
}

export default function Ratings() {
  const { season, loading } = useSeason()
  if (loading) return <div className="loading">Loading…</div>
  if (!season) return <div className="loading">No season data.</div>

  const started = season.teams.some((t) => t.played > 0)
  const maxAbs = Math.max(...season.teams.map((t) => Math.abs(t.attack + t.defence)), 0.1)
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

      <div className="panel table-scroll">
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
              <th className="num">Overall</th>
              <th style={{ width: 130 }}>Strength</th>
              <th className="num" title="Rating uncertainty — higher means the model knows less">±</th>
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
                <td className="num tabular" style={{ color: t.attack >= 0 ? 'var(--text)' : 'var(--text-dim)' }}>
                  {t.attack >= 0 ? '+' : ''}{t.attack.toFixed(3)}
                </td>
                <td className="num tabular" style={{ color: t.defence >= 0 ? 'var(--text)' : 'var(--text-dim)' }}>
                  {t.defence >= 0 ? '+' : ''}{t.defence.toFixed(3)}
                </td>
                <td className="num tabular" style={{ fontWeight: 700 }}>
                  {t.attack + t.defence >= 0 ? '+' : ''}{(t.attack + t.defence).toFixed(3)}
                </td>
                <td>
                  <StrengthBar value={t.attack + t.defence} max={maxAbs} uncertainty={t.ratingSd} />
                </td>
                <td className="num tabular faint">±{t.ratingSd.toFixed(2)}</td>
                <td><FormRun form={t.form} /></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="faint" style={{ fontSize: 12, marginTop: 12, lineHeight: 1.55, maxWidth: 720 }}>
        Attack is how much a side scores relative to league average, on a log scale — +0.30 means
        roughly 35% more goals than average. Defence works the same way, with higher meaning fewer
        conceded. Overall is the sum of the two, and the order of this table. The bar shows it
        either side of league average, with the paler band being the model's uncertainty — wide for
        a promoted club it has never rated. Form covers the last fifteen months only, so a club
        returning from a long absence correctly shows none rather than results from years ago.
      </p>
    </>
  )
}
