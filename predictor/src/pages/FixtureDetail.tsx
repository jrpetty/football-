// One match, in full.
//
// The order is deliberate: what the model thinks, then why, then what could go
// wrong with it. Reasoning sits above player detail because the reasoning is
// the point of the exercise.

import { Link, useParams } from 'react-router-dom'
import { useGameweek, useSeason } from '../data/store.tsx'
import { ScoreMatrix } from '../components/ScoreMatrix.tsx'
import { NarrativePanel } from '../components/NarrativePanel.tsx'
import { LineupPitch, LineupCaveat } from '../components/LineupPitch.tsx'
import { ProbBar, Stat, TeamChip, formatKickoff, headlineScore } from '../components/primitives.tsx'
import { clubColor, clubName, club } from '../config/teams.ts'
import type { Evidence } from '../core/evidence.ts'
import type { FixtureArtifact } from '../core/schema.ts'

function railColor(favours: Evidence['favours'], home: string, away: string): string {
  if (favours === 'home') return clubColor(home)
  if (favours === 'away') return clubColor(away)
  return 'var(--draw)'
}

function EvidenceList({ fixture }: { fixture: FixtureArtifact }) {
  if (fixture.evidence.length === 0) {
    return <p className="muted" style={{ fontSize: 13 }}>No notable factors beyond the base ratings.</p>
  }
  return (
    <div>
      {fixture.evidence.map((e) => (
        <div key={e.key} className="evidence-item">
          <div className="evidence-rail" style={{ background: railColor(e.favours, fixture.home, fixture.away) }} />
          <div>
            <div className="evidence-head">
              <span className="evidence-title">{e.headline}</span>
              <span className="chip" style={{ flex: 'none' }}>
                {e.favours === 'home'
                  ? club(fixture.home).short
                  : e.favours === 'away'
                    ? club(fixture.away).short
                    : 'Both'}
                {e.weight >= 0.01 ? ` · ${e.weight.toFixed(2)} goals` : ''}
              </span>
            </div>
            <div className="evidence-detail">{e.detail}</div>
          </div>
        </div>
      ))}
    </div>
  )
}

function UpsetPanel({ fixture }: { fixture: FixtureArtifact }) {
  const u = fixture.upset
  return (
    <div className="panel panel-pad">
      <div className="section-title">
        <h3 style={{ fontSize: 16 }}>Could there be an upset?</h3>
        <span
          className={
            u.rating === 'dangerous' ? 'badge badge-danger' : u.rating === 'live' ? 'badge badge-live' : 'badge badge-neutral'
          }
        >
          {u.rating === 'dangerous'
            ? 'Dangerous'
            : u.rating === 'live'
              ? 'Live'
              : u.rating === 'toss-up'
                ? 'Too close to call'
                : 'Unlikely'}
        </span>
      </div>

      <p style={{ fontSize: 13.5, lineHeight: 1.6, color: 'var(--text-dim)', marginBottom: 14 }}>{u.summary}</p>

      <div className="row gap-12 wrap" style={{ marginBottom: 14 }}>
        <div className="stat" style={{ flex: 1, minWidth: 130 }}>
          <div className="stat-label">{u.underdog} win</div>
          <div className="stat-value" style={{ fontSize: 21 }}>{(u.upsetProbability * 100).toFixed(0)}%</div>
        </div>
        <div className="stat" style={{ flex: 1, minWidth: 130 }}>
          <div className="stat-label">Avoid defeat</div>
          <div className="stat-value" style={{ fontSize: 21 }}>{(u.notLoseProbability * 100).toFixed(0)}%</div>
        </div>
        <div className="stat" style={{ flex: 1, minWidth: 130 }}>
          <div className="stat-label">Most likely upset</div>
          <div className="stat-value mono" style={{ fontSize: 21 }}>
            {u.upsetScoreline.home}–{u.upsetScoreline.away}
          </div>
        </div>
      </div>

      {u.mechanisms.length > 0 && (
        <>
          <div className="eyebrow" style={{ marginBottom: 9 }}>How it would happen</div>
          <div className="stack gap-8">
            {u.mechanisms.map((m) => (
              <div key={m.key} style={{ display: 'grid', gridTemplateColumns: '3px 1fr', gap: 11 }}>
                <div style={{ background: m.strength > 0 ? 'var(--warn)' : 'var(--border)', borderRadius: 2 }} />
                <div>
                  <div style={{ fontWeight: 650, fontSize: 13.5, marginBottom: 2 }}>{m.label}</div>
                  <div style={{ fontSize: 12.5, lineHeight: 1.5, color: 'var(--text-dim)' }}>{m.detail}</div>
                </div>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  )
}

function PlayerTable({ players, title }: { players: FixtureArtifact['homePlayers']; title: string }) {
  const shown = players.filter((p) => p.expectedMinutes >= 20).slice(0, 8)
  if (shown.length === 0) return null
  return (
    <div>
      <div className="eyebrow" style={{ marginBottom: 8 }}>{title}</div>
      <table className="data">
        <thead>
          <tr>
            <th>Player</th>
            <th className="num">Goal</th>
            <th className="num">Assist</th>
            <th className="num">Card</th>
          </tr>
        </thead>
        <tbody>
          {shown.map((p) => (
            <tr key={p.id}>
              <td>
                <span style={{ fontWeight: 600 }}>{p.name}</span>
                <span className="faint" style={{ fontSize: 11.5, marginLeft: 6 }}>{p.position}</span>
                {p.doubtful && (
                  <span className="badge badge-live" style={{ marginLeft: 6, fontSize: 10 }} title={p.news}>
                    doubt
                  </span>
                )}
              </td>
              <td className="num tabular">{(p.goal * 100).toFixed(0)}%</td>
              <td className="num tabular">{(p.assist * 100).toFixed(0)}%</td>
              <td className="num tabular">{(p.yellow * 100).toFixed(0)}%</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export default function FixtureDetail() {
  const { gw, id } = useParams()
  const { season } = useSeason()
  const { data, loading } = useGameweek(gw ? Number(gw) : null)

  if (loading) return <div className="loading">Loading match…</div>
  const fixture = data?.fixtures.find((f) => f.id === Number(id))
  if (!fixture) return <div className="loading">Match not found.</div>

  const f = fixture
  const played = f.finished && f.homeGoals !== null && f.awayGoals !== null
  const score = headlineScore(f)

  const missing = [
    ...f.homeMissing.map((m) => ({ ...m, team: f.home })),
    ...f.awayMissing.map((m) => ({ ...m, team: f.away })),
  ]

  return (
    <>
      <Link to={`/?gw=${f.gameweek}`} className="back-link">← Gameweek {f.gameweek}</Link>

      <div className="panel panel-pad" style={{ marginBottom: 18 }}>
        <div className="row" style={{ justifyContent: 'space-between', marginBottom: 16, flexWrap: 'wrap', gap: 10 }}>
          <span className="eyebrow">{formatKickoff(f.kickoff)}</span>
          <span className="row gap-6">
            {played && <span className="badge badge-neutral">Full time</span>}
            <span className="chip">Confidence {(f.confidence * 100).toFixed(0)}%</span>
          </span>
        </div>

        <div
          style={{
            display: 'grid',
            gridTemplateColumns: '1fr auto 1fr',
            alignItems: 'center',
            gap: 16,
            marginBottom: 20,
          }}
        >
          <div style={{ fontSize: 20, fontWeight: 700 }}>
            <TeamChip code={f.home} />
          </div>
          <div
            className="mono"
            style={{
              fontSize: 34,
              fontWeight: 750,
              letterSpacing: '-0.03em',
              padding: '4px 16px',
              background: 'var(--panel-2)',
              borderRadius: 12,
              border: '1px solid var(--border-soft)',
            }}
          >
            {played ? `${f.homeGoals}–${f.awayGoals}` : `${score.home}–${score.away}`}
          </div>
          <div style={{ fontSize: 20, fontWeight: 700, textAlign: 'right' }}>
            <TeamChip code={f.away} reverse />
          </div>
        </div>

        <ProbBar probs={f.probs} homeCode={f.home} awayCode={f.away} height={36} />

        {played && f.result && (
          <div className="note" style={{ marginTop: 16 }}>
            <span className="note-icon">{f.result.correctOutcome ? '✓' : '×'}</span>
            <span>
              The model {f.result.correctOutcome ? 'called this correctly' : 'got this wrong'}
              {f.result.correctScore ? ', including the exact scoreline' : ''}. RPS {f.result.rps.toFixed(3)} —
              lower is better, and a coin-flip forecast scores about 0.33.
            </span>
          </div>
        )}
      </div>

      <div className="grid-3" style={{ marginBottom: 18 }}>
        <Stat label="Expected goals" value={`${f.expectedGoalsHome.toFixed(2)} – ${f.expectedGoalsAway.toFixed(2)}`}
          sub="The model's scoring rates for each side" />
        <Stat label="Both teams score" value={`${(f.btts * 100).toFixed(0)}%`}
          sub={`Over 2.5 goals ${(f.over25 * 100).toFixed(0)}%`} />
        <Stat label="Clean sheets"
          value={`${(f.cleanSheetHome * 100).toFixed(0)}% / ${(f.cleanSheetAway * 100).toFixed(0)}%`}
          sub={`${club(f.home).short} keeps one / ${club(f.away).short} keeps one`} />
      </div>

      <div className="grid-2" style={{ marginBottom: 18, alignItems: 'start' }}>
        <div className="panel panel-pad">
          <div className="section-title">
            <h3 style={{ fontSize: 16 }}>Why the model thinks this</h3>
            <span className="faint" style={{ fontSize: 11.5 }}>ranked by impact</span>
          </div>
          <EvidenceList fixture={f} />
        </div>

        <div className="stack gap-16">
          <div className="panel panel-pad">
            <div className="section-title">
              <h3 style={{ fontSize: 16 }}>Every scoreline</h3>
            </div>
            <ScoreMatrix
              lambdaHome={f.lambdaHome}
              lambdaAway={f.lambdaAway}
              rho={f.rho}
              dispersion={f.dispersion}
              homeCode={f.home}
              awayCode={f.away}
            />
          </div>
          <UpsetPanel fixture={f} />
        </div>
      </div>

      <div className="panel panel-pad" style={{ marginBottom: 18 }}>
        <div className="section-title">
          <h3 style={{ fontSize: 16 }}>Predicted line-ups</h3>
          <span className="faint" style={{ fontSize: 11.5 }}>
            {Math.round(((f.homeLineup.confidence + f.awayLineup.confidence) / 2) * 100)}% confidence
          </span>
        </div>
        <LineupPitch
          homeCode={f.home}
          awayCode={f.away}
          homeLineup={f.homeLineup}
          awayLineup={f.awayLineup}
        />
        <LineupCaveat home={f.homeLineup} away={f.awayLineup} />
      </div>

      {missing.length > 0 && (
        <div className="panel panel-pad" style={{ marginBottom: 18 }}>
          <div className="section-title">
            <h3 style={{ fontSize: 16 }}>Who is missing</h3>
            <span className="faint" style={{ fontSize: 11.5 }}>share of their side's attacking value</span>
          </div>
          <table className="data">
            <thead>
              <tr>
                <th>Player</th>
                <th>Club</th>
                <th>Pos</th>
                <th>Reason</th>
                <th className="num">Attack share</th>
                <th>Detail</th>
              </tr>
            </thead>
            <tbody>
              {missing.map((m) => (
                <tr key={`${m.team}-${m.id}`}>
                  <td style={{ fontWeight: 600 }}>{m.name}</td>
                  <td>{club(m.team).short}</td>
                  <td className="faint">{m.position}</td>
                  <td>
                    <span className={m.reason === 'doubtful' ? 'badge badge-live' : 'badge badge-danger'}>
                      {m.reason}
                    </span>
                  </td>
                  <td className="num tabular">{(m.attackShare * 100).toFixed(1)}%</td>
                  <td className="muted" style={{ fontSize: 12.5, maxWidth: 300 }}>{m.news || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="panel panel-pad" style={{ marginBottom: 18 }}>
        <div className="section-title">
          <h3 style={{ fontSize: 16 }}>Players to watch</h3>
          <span className="faint" style={{ fontSize: 11.5 }}>
            scaled so each squad's chances add up to the team's predicted goals
          </span>
        </div>
        <div className="grid-2">
          <PlayerTable players={f.homePlayers} title={clubName(f.home)} />
          <PlayerTable players={f.awayPlayers} title={clubName(f.away)} />
        </div>
      </div>

      {f.cardWatch.length > 0 && (
        <div className="panel panel-pad" style={{ marginBottom: 18 }}>
          <div className="section-title">
            <h3 style={{ fontSize: 16 }}>Card watch</h3>
            <span className="faint" style={{ fontSize: 11.5 }}>one booking from a ban</span>
          </div>
          <table className="data">
            <thead>
              <tr>
                <th>Player</th>
                <th>Team</th>
                <th className="num">Yellows</th>
                <th className="num">To ban</th>
                <th className="num">Booking risk</th>
              </tr>
            </thead>
            <tbody>
              {f.cardWatch.map((c) => (
                <tr key={c.playerId}>
                  <td style={{ fontWeight: 600 }}>{c.name}</td>
                  <td>{club(c.team).short}</td>
                  <td className="num tabular">{c.yellows}</td>
                  <td className="num tabular">{c.yellowsToBan}</td>
                  <td className="num tabular">{(c.bookingProbability * 100).toFixed(0)}%</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div style={{ marginBottom: 18 }}>
        <NarrativePanel fixture={f} />
      </div>

      <details className="panel panel-pad">
        <summary style={{ cursor: 'pointer', fontWeight: 650, fontSize: 14 }}>
          Model internals — the arithmetic behind this prediction
        </summary>
        <p className="muted" style={{ fontSize: 12.5, lineHeight: 1.55, margin: '12px 0 14px' }}>
          Each factor is an additive term on log scoring rate. Summing the columns and taking the
          exponential gives the two expected-goal figures above; every explanation on this page is
          derived from these numbers rather than written alongside them.
        </p>
        <table className="data">
          <thead>
            <tr>
              <th>Factor</th>
              <th className="num">{club(f.home).short}</th>
              <th className="num">{club(f.away).short}</th>
            </tr>
          </thead>
          <tbody>
            {f.terms.map((t) => (
              <tr key={t.key}>
                <td>
                  {t.label}
                  {t.detail && <div className="faint" style={{ fontSize: 11.5, marginTop: 2 }}>{t.detail}</div>}
                </td>
                <td className="num tabular">{t.home >= 0 ? '+' : ''}{t.home.toFixed(3)}</td>
                <td className="num tabular">{t.away >= 0 ? '+' : ''}{t.away.toFixed(3)}</td>
              </tr>
            ))}
            <tr>
              <td style={{ fontWeight: 700 }}>Expected goals</td>
              <td className="num tabular" style={{ fontWeight: 700 }}>{f.lambdaHome.toFixed(2)}</td>
              <td className="num tabular" style={{ fontWeight: 700 }}>{f.lambdaAway.toFixed(2)}</td>
            </tr>
          </tbody>
        </table>
        {season && (
          <p className="faint" style={{ fontSize: 11.5, marginTop: 12, lineHeight: 1.5 }}>
            Scoreline spread uses a negative binomial with dispersion {f.dispersion.toFixed(1)} and a
            Dixon-Coles low-score correction of {f.rho.toFixed(3)}. Lower dispersion means fatter tails
            and more upset probability, which is how rating uncertainty reaches the forecast.
          </p>
        )}
      </details>
    </>
  )
}
