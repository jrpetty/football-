// The report card.
//
// This page exists to make the experiment falsifiable. Predictions are
// recorded before kickoff and scored afterwards against fixed metrics; if the
// model is bad, this is where it shows.

import { useSeason } from '../data/store.tsx'
import { Stat } from '../components/primitives.tsx'
import { clubName } from '../config/teams.ts'

function CalibrationChart({ bins }: { bins: { predicted: number; observed: number; count: number }[] }) {
  const w = 380
  const h = 300
  const pad = 42
  const usable = bins.filter((b) => b.count >= 5)
  const x = (p: number): number => pad + p * (w - pad - 12)
  const y = (p: number): number => h - pad - p * (h - pad - 12)

  return (
    <svg width="100%" viewBox={`0 0 ${w} ${h}`} role="img" aria-label="Calibration curve">
      {/* Perfect-calibration diagonal */}
      <line x1={x(0)} y1={y(0)} x2={x(1)} y2={y(1)} stroke="var(--border)" strokeWidth={1.5} />
      {[0, 0.25, 0.5, 0.75, 1].map((t) => (
        <g key={t}>
          <line x1={x(t)} y1={y(0)} x2={x(t)} y2={y(1)} stroke="var(--border-soft)" strokeWidth={1} />
          <line x1={x(0)} y1={y(t)} x2={x(1)} y2={y(t)} stroke="var(--border-soft)" strokeWidth={1} />
          <text x={x(t)} y={h - pad + 16} textAnchor="middle" fill="var(--text-faint)" fontSize={10}>
            {(t * 100).toFixed(0)}%
          </text>
          <text x={pad - 8} y={y(t) + 3} textAnchor="end" fill="var(--text-faint)" fontSize={10}>
            {(t * 100).toFixed(0)}%
          </text>
        </g>
      ))}
      {usable.length > 1 && (
        <polyline
          points={usable.map((b) => `${x(b.predicted)},${y(b.observed)}`).join(' ')}
          fill="none"
          stroke="var(--accent)"
          strokeWidth={2}
        />
      )}
      {usable.map((b, i) => (
        <circle key={i} cx={x(b.predicted)} cy={y(b.observed)} r={5}
          fill="var(--accent)" stroke="var(--panel)" strokeWidth={2}>
          <title>{`Forecast ${(b.predicted * 100).toFixed(0)}%, happened ${(b.observed * 100).toFixed(0)}% (n=${b.count})`}</title>
        </circle>
      ))}
      <text x={w / 2} y={h - 6} textAnchor="middle" fill="var(--text-faint)" fontSize={11} fontWeight={600}>
        Forecast probability
      </text>
      <text x={12} y={h / 2} textAnchor="middle" fill="var(--text-faint)" fontSize={11} fontWeight={600}
        transform={`rotate(-90 12 ${h / 2})`}>
        Actually happened
      </text>
    </svg>
  )
}

export default function ModelReport() {
  const { season, accuracy, loading } = useSeason()
  if (loading) return <div className="loading">Loading…</div>
  if (!season || !accuracy) return <div className="loading">No data.</div>

  const scored = accuracy.overall.n
  const beatsBaseline = accuracy.overall.rps < accuracy.baseline.rps

  return (
    <>
      <div className="eyebrow">Honesty check</div>
      <h1 style={{ fontSize: 28, marginTop: 4, marginBottom: 6 }}>Report card</h1>
      <p className="muted" style={{ fontSize: 13.5, marginBottom: 20, maxWidth: 760, lineHeight: 1.6 }}>
        Every forecast is written down before kickoff and scored afterwards. Nothing here is
        recalculated with hindsight — the numbers below are what the model actually said at the time.
      </p>

      {scored === 0 ? (
        <div className="note" style={{ marginBottom: 22 }}>
          <span className="note-icon">i</span>
          <span>
            No matches have been scored yet — the {season.season} season starts with this gameweek.
            This page fills in from the first results onward. For reference, a walk-forward backtest
            over the 2025/26 season scored <strong>RPS 0.2095</strong> against a
            baseline of <strong>0.2274</strong>, calling 48.2% of results correctly versus 42.6%.
          </span>
        </div>
      ) : (
        <div className="grid-3" style={{ marginBottom: 22 }}>
          <Stat
            label="Ranked probability score"
            value={accuracy.overall.rps.toFixed(4)}
            sub={`Baseline ${accuracy.baseline.rps.toFixed(4)} — ${beatsBaseline ? 'model ahead' : 'model behind'}`}
            accent={beatsBaseline ? 'var(--good)' : 'var(--away)'}
          />
          <Stat label="Outcomes called" value={`${(accuracy.overall.accuracy * 100).toFixed(1)}%`}
            sub={`${scored} matches · baseline ${(accuracy.baseline.accuracy * 100).toFixed(1)}%`} />
          <Stat label="Calibration error" value={accuracy.expectedCalibrationError.toFixed(4)}
            sub="Mean gap between forecast and reality — lower is better" />
        </div>
      )}

      <div className="grid-2" style={{ marginBottom: 22, alignItems: 'start' }}>
        <div className="panel panel-pad">
          <div className="section-title">
            <h3 style={{ fontSize: 16 }}>Calibration</h3>
            <span className="faint" style={{ fontSize: 11.5 }}>of everything called 30%, did 30% happen?</span>
          </div>
          {scored > 0 ? (
            <CalibrationChart bins={accuracy.calibration} />
          ) : (
            <p className="muted" style={{ fontSize: 13 }}>Fills in once matches have been played.</p>
          )}
        </div>

        <div className="panel panel-pad">
          <div className="section-title">
            <h3 style={{ fontSize: 16 }}>How it works</h3>
          </div>
          <table className="data">
            <tbody>
              <tr><td>Time decay</td><td className="num mono">{season.params.xi}</td></tr>
              <tr><td>Expected-goals blend</td><td className="num mono">{season.params.xgBlend}</td></tr>
              <tr><td>Home advantage</td><td className="num mono">{season.params.homeAdvantage?.toFixed(3)}</td></tr>
              <tr><td>Low-score correction (rho)</td><td className="num mono">{season.params.rho?.toFixed(4)}</td></tr>
              <tr><td>Availability damping</td><td className="num mono">{season.params.availabilityStrength}</td></tr>
              <tr>
                <td>Promoted-side prior</td>
                <td className="num mono">
                  {season.params.promotedPriorAttack?.toFixed(3)} / {season.params.promotedPriorDefence?.toFixed(3)}
                </td>
              </tr>
              <tr><td>Promoted sample size</td><td className="num mono">{season.params.promotedSampleSize} seasons</td></tr>
            </tbody>
          </table>
          <p className="faint" style={{ fontSize: 12, marginTop: 12, lineHeight: 1.55 }}>
            A sweep across time-decay and expected-goals blend settings moved the score by under
            0.0005 RPS — well inside the noise for a 380-match sample — so these are left at sensible
            defaults rather than tuned to a number that would not generalise.
          </p>
        </div>
      </div>

      {scored > 0 && (
        <div className="grid-2">
          <div className="panel panel-pad">
            <div className="section-title"><h3 style={{ fontSize: 16 }}>Best calls</h3></div>
            <table className="data">
              <tbody>
                {accuracy.bestCalls.map((c) => (
                  <tr key={c.fixtureId}>
                    <td>{clubName(c.home)} v {clubName(c.away)}</td>
                    <td className="num tabular">{c.actual?.homeGoals}–{c.actual?.awayGoals}</td>
                    <td className="num tabular faint">{c.actual?.rps.toFixed(3)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="panel panel-pad">
            <div className="section-title"><h3 style={{ fontSize: 16 }}>Worst calls</h3></div>
            <table className="data">
              <tbody>
                {accuracy.worstCalls.map((c) => (
                  <tr key={c.fixtureId}>
                    <td>{clubName(c.home)} v {clubName(c.away)}</td>
                    <td className="num tabular">{c.actual?.homeGoals}–{c.actual?.awayGoals}</td>
                    <td className="num tabular faint">{c.actual?.rps.toFixed(3)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      <div className="panel panel-pad" style={{ marginTop: 22 }}>
        <div className="section-title"><h3 style={{ fontSize: 16 }}>Known limitations</h3></div>
        <ul className="muted" style={{ fontSize: 13, lineHeight: 1.7, paddingLeft: 20, margin: 0 }}>
          {season.notes.map((n, i) => <li key={i}>{n}</li>)}
          <li>
            The player-availability adjustment is measured from historical squad data, not validated
            by walk-forward backtest — doing that honestly would need archived injury reports, which
            this data source does not carry.
          </li>
          <li>
            Football is high variance. A good model calls about half of matches correctly; bookmakers
            with far more data reach RPS ~0.19-0.20. Being wrong often is expected, not a bug.
          </li>
        </ul>
      </div>
    </>
  )
}
