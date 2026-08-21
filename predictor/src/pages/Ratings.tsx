// League table and the ratings underneath it.
//
// The table is the familiar view; the ratings columns are what the model
// actually runs on, shown alongside so the two can be compared.

import { useState } from 'react'
import { useSeason } from '../data/store.tsx'
import { FormRun, TeamChip } from '../components/primitives.tsx'
import type { TeamSummary, VenueRecord } from '../core/schema.ts'

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

const ppg = (r: VenueRecord) => (r.played > 0 ? (r.won * 3 + r.drawn) / r.played : 0)

/**
 * Below this a venue record is not worth reading, and the table says so rather
 * than printing "0.00 from one match" beside a club with nineteen.
 */
const MIN_VENUE_MATCHES = 4

/**
 * Points per game at each venue, drawn as one split bar.
 *
 * Home and away share a scale so the two halves are directly comparable, which
 * is the entire point — a club whose bar is lopsided is telling you something
 * its league position does not.
 */
function VenueSplit({ home, away }: { home: VenueRecord; away: VenueRecord }) {
  if (home.played < MIN_VENUE_MATCHES && away.played < MIN_VENUE_MATCHES) return null
  const W = 154
  const H = 20
  const mid = W / 2
  const arm = mid - 3
  // Three points per game is the ceiling, so the two arms are the same scale
  // and a bar reaching the end means every match won.
  const scale = arm / 3
  const h = Math.max(ppg(home) * scale, 1)
  const a = Math.max(ppg(away) * scale, 1)
  const y = H / 2 - 5
  return (
    <svg viewBox={`0 0 ${W} ${H}`} width={W} height={H} className="venue-bar-svg" role="img"
      aria-label={`${ppg(home).toFixed(2)} points per game at home, ${ppg(away).toFixed(2)} away`}>
      {/* Ticks at one and two points per game, so the bars have a scale to sit against. */}
      {[1, 2].map((t) => (
        <g key={t}>
          <line x1={mid - t * scale} y1={y - 2} x2={mid - t * scale} y2={y + 12}
            stroke="var(--border)" strokeWidth={1} />
          <line x1={mid + t * scale} y1={y - 2} x2={mid + t * scale} y2={y + 12}
            stroke="var(--border)" strokeWidth={1} />
        </g>
      ))}
      <rect x={mid - h} y={y} width={h} height={10} rx={2} fill="var(--home)" />
      <rect x={mid} y={y} width={a} height={10} rx={2} fill="var(--away)" />
      <line x1={mid} y1={0} x2={mid} y2={H} stroke="var(--text-faint)" strokeWidth={1} />
    </svg>
  )
}

/** One venue's record as the familiar W-D-L string plus goals. */
function RecordCells({ r }: { r: VenueRecord }) {
  if (r.played < MIN_VENUE_MATCHES) {
    return (
      <>
        <td className="num tabular faint hide-narrow">{r.played}</td>
        <td className="num faint hide-narrow" colSpan={2} style={{ fontSize: 11.5 }}>
          {r.played === 0 ? 'no top-flight record' : 'too few to read'}
        </td>
        <td className="num faint" style={{ fontSize: 11.5 }}>—</td>
      </>
    )
  }
  return (
    <>
      <td className="num tabular faint hide-narrow">{r.played}</td>
      <td className="num tabular hide-narrow">{r.won}–{r.drawn}–{r.lost}</td>
      <td className="num tabular faint hide-narrow">{r.goalsFor}:{r.goalsAgainst}</td>
      <td className="num tabular" style={{ fontWeight: 650 }}>{ppg(r).toFixed(2)}</td>
    </>
  )
}

/**
 * The fitted venue effect, as a percentage rather than a log deviation.
 *
 * This is the number the forecast uses, and it is not the same thing as the
 * record beside it: the record includes who a club happened to host, while
 * this has that divided out. A club can have a fine home record and no venue
 * effect at all, if the fixtures were kind.
 */
function VenueEdge({ team }: { team: TeamSummary }) {
  const total = team.venue.homeAttackDev + team.venue.homeDefenceDev
  if (Math.abs(total) < 0.005) return <span className="faint">league average</span>
  const pct = (Math.exp(total) - 1) * 100
  return (
    <span style={{ color: total > 0 ? 'var(--good)' : 'var(--bad)', fontWeight: 650 }}>
      {pct > 0 ? '+' : ''}{pct.toFixed(1)}%
    </span>
  )
}

export default function Ratings() {
  const [view, setView] = useState<'ratings' | 'venue'>('ratings')
  const { season, loading } = useSeason()
  if (loading) return <div className="loading">Loading…</div>
  if (!season) return <div className="loading">No season data.</div>

  const started = season.teams.some((t) => t.played > 0)
  const maxAbs = Math.max(...season.teams.map((t) => Math.abs(t.attack + t.defence)), 0.1)
  // Before a ball is kicked the table is 20 zeroes; rank by model strength instead.
  const rows = started
    ? season.teams
    : [...season.teams].sort((a, b) => b.attack + b.defence - (a.attack + a.defence))
  // The venue table is ranked by home points per game — the thing it is about —
  // with the fitted edge breaking ties before a season has produced any.
  const venueRows = [...season.teams].sort(
    (a, b) =>
      ppg(b.venue.home) - ppg(a.venue.home) ||
      b.venue.homeAttackDev + b.venue.homeDefenceDev - (a.venue.homeAttackDev + a.venue.homeDefenceDev),
  )
  const trailingCount = season.teams.filter((t) => t.venue.home.trailing || t.venue.away.trailing).length
  const anyTrailing = trailingCount > 0
  // Marking every row is not a signal. When the whole table is on a trailing
  // window — which is the case until a season is six matches old — the note
  // underneath says so once instead.
  const markTrailing = trailingCount > 0 && trailingCount < season.teams.length
  const leagueHome = season.params['homeAdvantage'] ?? 0

  return (
    <>
      <div className="eyebrow">{season.season}</div>
      <h1 style={{ fontSize: 28, marginTop: 4, marginBottom: 6 }}>
        {view === 'venue' ? 'Home and away' : started ? 'Table and ratings' : 'Model ratings'}
      </h1>
      <p className="muted" style={{ fontSize: 13.5, marginBottom: 16, maxWidth: 720, lineHeight: 1.6 }}>
        {view === 'venue'
          ? `Every club's record split by venue, and the venue effect the model fits from it. ` +
            `The league-wide home advantage is worth ${((Math.exp(leagueHome) - 1) * 100).toFixed(0)}% ` +
            `on a home side's scoring rate; the edge column is how far each ground departs from that, ` +
            `after dividing out who they happened to host.`
          : started
            ? 'Attack and defence are log-scale ratings fitted across the last decade, weighted toward recent matches and blended with expected goals.'
            : 'No matches have been played yet, so this is ranked by model strength rather than points. Attack and defence are log-scale ratings: 0 is league average, and a promoted club with no top-flight record carries a wide uncertainty band.'}
      </p>

      <div className="row gap-8 wrap" style={{ marginBottom: 16 }}>
        {([['ratings', started ? 'Table and ratings' : 'Model ratings'], ['venue', 'Home and away']] as const).map(
          ([key, label]) => (
            <button
              key={key}
              className={`gw-btn${view === key ? ' active' : ''}`}
              style={{ minWidth: 0, padding: '0 14px' }}
              onClick={() => setView(key)}
            >
              {label}
            </button>
          ),
        )}
      </div>

      {view === 'ratings' && (
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
      )}

      {view === 'venue' && (
        <div className="panel table-scroll">
          <table className="data venue-table">
            <thead>
              <tr>
                <th style={{ width: 34 }} className="hide-narrow">#</th>
                <th>Team</th>
                <th className="num hide-narrow" title="Home matches counted">P</th>
                <th className="num hide-narrow">Home W–D–L</th>
                <th className="num hide-narrow" title="Goals for : against at home">Goals</th>
                <th className="num" title="Points per game at home">Home PPG</th>
                <th className="num hide-narrow" title="Away matches counted">P</th>
                <th className="num hide-narrow">Away W–D–L</th>
                <th className="num hide-narrow" title="Goals for : against away">Goals</th>
                <th className="num" title="Points per game away">Away PPG</th>
                <th style={{ width: 160 }} className="venue-bar-col"
                  title="Points per game: home to the left, away to the right">
                  Home / away
                </th>
                <th className="num" title="Fitted venue effect on scoring rate, over and above the league-wide home advantage">
                  Edge
                </th>
              </tr>
            </thead>
            <tbody>
              {venueRows.map((t, i) => (
                <tr key={t.code}>
                  <td className="faint tabular hide-narrow">{i + 1}</td>
                  <td>
                    <span className="row gap-8">
                      <TeamChip code={t.code} />
                      {markTrailing && (t.venue.home.trailing || t.venue.away.trailing) && (
                        <span className="faint" style={{ fontSize: 10 }} title="Too few matches this season; showing a trailing window">
                          trailing
                        </span>
                      )}
                    </span>
                  </td>
                  <RecordCells r={t.venue.home} />
                  <RecordCells r={t.venue.away} />
                  <td className="venue-bar-col"><VenueSplit home={t.venue.home} away={t.venue.away} /></td>
                  <td className="num tabular"><VenueEdge team={t} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {view === 'venue' && (
        <p className="faint" style={{ fontSize: 12, marginTop: 12, lineHeight: 1.55, maxWidth: 760 }}>
          The record and the edge are different claims and can disagree. The record is what happened;
          the edge is what is left once the model accounts for how strong the visitors were, so a club
          that hosted the bottom six in its first eight home games can top the left-hand table and
          still carry no edge at all. The edge is the part that reaches the forecast, and it is
          deliberately shrunk toward the league-wide home advantage — a club plays only nineteen home
          matches a season, and an unshrunk venue split is mostly noise. It is also fitted over the
          model's full weighted history rather than the window shown here, which is why a club can
          carry an edge beside a record too thin to print.
          {anyTrailing && (
            <>
              {' '}
              {markTrailing
                ? `Rows marked trailing have fewer than six matches at that venue this season, so they show the club's last nineteen there instead, within the past fifteen months.`
                : `No club has six matches at either venue this season yet, so every row shows its last nineteen there instead, within the past fifteen months. A promoted club with no top-flight record in that window correctly shows none.`}
            </>
          )}
        </p>
      )}

      {view === 'ratings' && (
      <p className="faint" style={{ fontSize: 12, marginTop: 12, lineHeight: 1.55, maxWidth: 720 }}>
        Attack is how much a side scores relative to league average, on a log scale — +0.30 means
        roughly 35% more goals than average. Defence works the same way, with higher meaning fewer
        conceded. Overall is the sum of the two, and the order of this table. The bar shows it
        either side of league average, with the paler band being the model's uncertainty — wide for
        a promoted club it has never rated. Form covers the last fifteen months only, so a club
        returning from a long absence correctly shows none rather than results from years ago.
      </p>
      )}
    </>
  )
}
