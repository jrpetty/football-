// The report card.
//
// This page exists to make the experiment falsifiable. Predictions are
// recorded before kickoff and scored afterwards against fixed metrics; if the
// model is bad, this is where it shows.

import { useSeason } from '../data/store.tsx'
import { Stat } from '../components/primitives.tsx'
import { clubName } from '../config/teams.ts'
import { BACKTEST, RPS_STANDARD_ERROR } from '../config/backtest.ts'

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

/**
 * Where the model sits between doing nothing and a serious bookmaker.
 *
 * A bar chart of three RPS values would be nearly unreadable — they differ in
 * the third decimal — so this places them on a shared axis instead, which is
 * what the reader actually wants to judge: how far along the gap the model
 * has come.
 */
function ScoreScale() {
  const W = 640
  const H = 92
  const pad = 18
  // A little headroom either side of the values being plotted.
  const lo = BACKTEST.bookmakerRps - 0.006
  const hi = BACKTEST.baseline.rps + 0.006
  const x = (v: number): number => pad + ((v - lo) / (hi - lo)) * (W - pad * 2)

  const marks = [
    { v: BACKTEST.baseline.rps, label: 'Fixed baseline', color: 'var(--away)', above: true },
    { v: BACKTEST.model.rps, label: 'This model', color: 'var(--good)', above: false },
    { v: BACKTEST.bookmakerRps, label: 'Strong bookmaker', color: 'var(--text-faint)', above: true },
  ]

  return (
    <svg width="100%" viewBox={`0 0 ${W} ${H}`} role="img"
      aria-label={`Ranked probability score: baseline ${BACKTEST.baseline.rps}, this model ${BACKTEST.model.rps}, bookmaker ${BACKTEST.bookmakerRps}`}>
      {/* The span from "no model at all" to "as good as it gets". */}
      <line x1={x(BACKTEST.bookmakerRps)} y1={H / 2} x2={x(BACKTEST.baseline.rps)} y2={H / 2}
        stroke="var(--border)" strokeWidth={3} strokeLinecap="round" />
      {/* How much of that span the model has closed. */}
      <line x1={x(BACKTEST.model.rps)} y1={H / 2} x2={x(BACKTEST.baseline.rps)} y2={H / 2}
        stroke="var(--good)" strokeWidth={3} strokeLinecap="round" />
      {marks.map((m) => (
        <g key={m.label}>
          <circle cx={x(m.v)} cy={H / 2} r={6} fill={m.color} stroke="var(--panel)" strokeWidth={2.5} />
          <text x={x(m.v)} y={m.above ? H / 2 - 16 : H / 2 + 24} textAnchor="middle"
            fontSize={11.5} fontWeight={700} fill="var(--text-dim)">
            {m.label}
          </text>
          <text x={x(m.v)} y={m.above ? H / 2 - 30 : H / 2 + 38} textAnchor="middle"
            fontSize={12} fontWeight={700} fill={m.color} className="tabular">
            {m.v.toFixed(4)}
          </text>
        </g>
      ))}
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
        <>
          <div className="note" style={{ marginBottom: 18 }}>
            <span className="note-icon">i</span>
            <span>
              No {season.season} match has been played yet, so there is nothing here to score. This
              page fills in from the first results onward. Until then, what follows is the model's
              performance across {BACKTEST.season}, measured walk-forward — refitting as each season
              progressed and never seeing a result before predicting it.
            </span>
          </div>
          <div className="grid-3" style={{ marginBottom: 22 }}>
            <Stat
              label="Ranked probability score"
              value={BACKTEST.model.rps.toFixed(4)}
              sub={`Baseline ${BACKTEST.baseline.rps.toFixed(4)} · a strong bookmaker ≈ ${BACKTEST.bookmakerRps.toFixed(3)}`}
              accent="var(--good)"
            />
            <Stat
              label="Outcomes called"
              value={`${(BACKTEST.model.accuracy * 100).toFixed(1)}%`}
              sub={`Baseline ${(BACKTEST.baseline.accuracy * 100).toFixed(1)}% over ${BACKTEST.matches} matches`}
            />
            <Stat
              label="Calibration error"
              value={BACKTEST.calibrationError.toFixed(4)}
              sub="Of everything called at 30%, close to 30% happened"
            />
          </div>
          <div className="panel panel-pad" style={{ marginBottom: 22 }}>
            <div className="section-title">
              <h3 style={{ fontSize: 16 }}>Measured against the alternatives</h3>
              <span className="faint" style={{ fontSize: 11.5 }}>
                {BACKTEST.season}, {BACKTEST.matches} matches · lower is better
              </span>
            </div>
            <ScoreScale />
            <p className="faint" style={{ fontSize: 12, marginTop: 12, lineHeight: 1.55 }}>
              Reproduce it with <code style={{ fontFamily: 'var(--mono)' }}>{BACKTEST.command}</code>.
              The standard error on this measure at {BACKTEST.matches} matches is about{' '}
              {RPS_STANDARD_ERROR.toFixed(4)}, so the gap over the baseline is real and the gap to a
              bookmaker is the honest amount of room left. Seasons differ: the hardest of the five,
              {' '}{BACKTEST.latest.season}, scored {BACKTEST.latest.model.rps.toFixed(4)} against a
              {' '}{BACKTEST.latest.baseline.rps.toFixed(4)} baseline and called{' '}
              {(BACKTEST.latest.model.accuracy * 100).toFixed(1)}% of outcomes, so treat the pooled
              figure as an average rather than a promise.
            </p>
          </div>
        </>
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

      {/* Before any match is played the calibration panel has nothing to draw,
          and an empty box beside a full one just reads as broken. */}
      <div className={scored > 0 ? 'grid-2' : ''} style={{ marginBottom: 22, alignItems: 'start' }}>
        {scored > 0 && (
          <div className="panel panel-pad">
            <div className="section-title">
              <h3 style={{ fontSize: 16 }}>Calibration</h3>
              <span className="faint" style={{ fontSize: 11.5 }}>of everything called 30%, did 30% happen?</span>
            </div>
            <CalibrationChart bins={accuracy.calibration} />
          </div>
        )}

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
            <strong>The player-availability adjustment does not demonstrably improve forecasts.</strong>{' '}
            Walk-forward testing — inferring absences from who missed a club's previous two matches,
            the only signal the archive supports that was genuinely known before kickoff — found it
            cost 0.0006 RPS at its current weight and up to 0.0031 at higher ones, at every severity
            of absence. Recency-weighted team ratings appear to absorb most of the effect already: a
            side playing without its best players produces worse results, and the rating has read
            that. The weight was cut from 0.7 to 0.3 on this evidence. It is kept, rather than
            removed, because the test's absence signal is far noisier than the live one (the proxy
            flags 7.0 players per team-match; the real injury feed flags 2.9 per club, so most of
            what it catches is rotation) and because at 0.3 the cost is well inside the noise for a
            380-match sample. Availability flags are now recorded weekly so the honest version of
            this test becomes possible once this season has produced a real history.
          </li>
          <li>
            Starting elevens are predicted, never confirmed. For a newly promoted club with no
            top-flight record in the data, the eleven is inferred from squad valuation and is a
            rough guess — those are labelled as such on the match page.
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
