/**
 * Model Health — what the estimates are actually standing on.
 *
 * Every other screen answers "how fast will this build be". This one answers
 * "and why should you believe it", which for a tool built on a recalled seed
 * catalogue is the more important question. Three things are shown, in order of
 * how much they should change someone's mind:
 *
 *  1. The evidence ladder: how much of the gate's weight is a measurement and
 *     how much is a recollection, and exactly how many harness runs it would
 *     take to flip that.
 *  2. Drift: whether accuracy is moving between runs, with the gate thresholds
 *     drawn on so a passing run and a barely-passing run look different.
 *  3. Coverage: where the fixture set is thin, so an operator with one
 *     afternoon knows which measurements to take first.
 *
 * The numbers come from the same module the gate uses (src/core/evidence.ts),
 * so this screen cannot quote a different weighting from the thing it reports on.
 */
import { useMemo, useState } from 'react';
import historyJson from '../../../data/validation/history.json';
import lastRunJson from '../../../data/validation/last-run.json';
import fixturesJson from '../../../data/fixtures/fixtures.json';
import {
  GATES, STRICT_GATE_ARMS_AT, accuracyClaim, evidenceLadder, thinnestCells, type Fixture,
} from '../../core/evidence.ts';
import { RESOLUTIONS } from '../../core/types.ts';
import { useApp } from '../store.ts';
import { exportCsv, exportJson } from '../export.ts';
import { fmt } from '../components/Parts.tsx';

interface HistoryPoint {
  at: string;
  fixtures: number;
  measuredShare: number;
  medianAPE: number;
  p90APE: number;
  meanAPE: number;
  spearman: number;
  signAccuracy: number;
  withinBand: number;
  pass: boolean;
}

interface RunMetrics {
  n: number;
  medianAPE: number;
  meanAPE: number;
  p90APE: number;
  medianAPEUnweighted: number;
  p90APEUnweighted: number;
  spearman: number;
  signAccuracy: number;
  signPairs: number;
  signAbstained: number;
  blocked: number;
  withinBand: number;
}

const history = historyJson as HistoryPoint[];
const lastRun = lastRunJson as { generatedAt: string; crossValidation: RunMetrics; inSample: RunMetrics; failures: string[]; measuredShare: number };
const fixtures = (fixturesJson as { records: Fixture[] }).records;
/**
 * Titles left without fixtures on purpose. Without this the coverage panel
 * would report them beside the genuine gaps, which would be misleading in the
 * direction that matters least — implying work is outstanding when the decision
 * was that a frame-rate fixture is meaningless for a turn-based or
 * server-bound title.
 */
const excluded = (fixturesJson as { deliberatelyUncovered?: { why: string; games: Record<string, string> } })
  .deliberatelyUncovered;

const pct = (x: number, dp = 1) => `${(x * 100).toFixed(dp)}%`;

/**
 * A metric worth tracking over time, with the direction that counts as better
 * and the threshold it is gated against. Encoding "lower is better" per metric
 * rather than assuming it is what lets error and correlation share one chart.
 */
interface Series {
  key: keyof HistoryPoint;
  label: string;
  lowerIsBetter: boolean;
  gate?: number;
  gateLabel?: string;
  format: (x: number) => string;
}

const SERIES: Series[] = [
  { key: 'medianAPE', label: 'median disagreement', lowerIsBetter: true, gate: GATES.medianAPE, gateLabel: 'gate 15%', format: (x) => pct(x) },
  { key: 'p90APE', label: 'p90 disagreement (tail)', lowerIsBetter: true, gate: GATES.p90APEAdvisory, gateLabel: 'advisory 45%', format: (x) => pct(x) },
  { key: 'meanAPE', label: 'mean disagreement', lowerIsBetter: true, format: (x) => pct(x) },
  { key: 'spearman', label: 'rank correlation', lowerIsBetter: false, gate: GATES.spearman, gateLabel: 'gate 0.90', format: (x) => x.toFixed(3) },
  { key: 'withinBand', label: 'inside stated band', lowerIsBetter: false, format: (x) => pct(x) },
];

/**
 * A small line chart. Written by hand rather than pulled in as a dependency:
 * five points and a threshold line do not justify a charting library, and an
 * inline SVG keeps the published page self-contained.
 */
function DriftChart({ series, points }: { series: Series; points: HistoryPoint[] }) {
  const W = 320;
  const H = 92;
  const PAD = { l: 44, r: 8, t: 10, b: 18 };
  const vals = points.map((p) => Number(p[series.key]));
  const withGate = series.gate != null ? [...vals, series.gate] : vals;
  const lo = Math.min(...withGate);
  const hi = Math.max(...withGate);
  // A flat series would collapse to a zero-height band and look like an error.
  const span = hi - lo < 1e-9 ? Math.max(Math.abs(hi) * 0.2, 0.01) : (hi - lo) * 1.15;
  const mid = (hi + lo) / 2;
  const yLo = mid - span / 2;
  const x = (i: number) => PAD.l + (points.length < 2 ? (W - PAD.l - PAD.r) / 2 : (i / (points.length - 1)) * (W - PAD.l - PAD.r));
  const y = (v: number) => PAD.t + (1 - (v - yLo) / span) * (H - PAD.t - PAD.b);

  const first = vals[0];
  const last = vals[vals.length - 1];
  const delta = last - first;
  const improved = series.lowerIsBetter ? delta < 0 : delta > 0;
  const moved = Math.abs(delta) > 1e-6;
  const passing = series.gate == null || (series.lowerIsBetter ? last <= series.gate : last >= series.gate);

  return (
    <div className="panel" style={{ padding: 0 }}>
      <div className="panel-body" style={{ paddingBottom: 4 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
          <span className="mini">{series.label}</span>
          <span className="spacer" />
          <span className="mono" style={{ fontSize: 15, color: passing ? 'var(--ink)' : 'var(--bad)' }}>{series.format(last)}</span>
        </div>
        <div className="mini" style={{ color: moved ? (improved ? 'var(--good)' : 'var(--bad)') : 'var(--faint)' }}>
          {moved ? `${improved ? 'better' : 'worse'} by ${series.format(Math.abs(delta))} across ${points.length} runs` : 'unchanged across every recorded run'}
        </div>
      </div>
      <svg className="spark" viewBox={`0 0 ${W} ${H}`} width="100%" style={{ display: 'block' }} role="img" aria-label={`${series.label} over ${points.length} validation runs`}>
        {series.gate != null && (
          <>
            <line x1={PAD.l} x2={W - PAD.r} y1={y(series.gate)} y2={y(series.gate)} stroke="var(--bad)" strokeDasharray="3 3" strokeWidth="1" opacity="0.7" />
            <text x={W - PAD.r} y={y(series.gate) - 3} textAnchor="end" fill="var(--bad)" opacity="0.85">{series.gateLabel}</text>
          </>
        )}
        <text x={PAD.l - 5} y={y(hi) + 3} textAnchor="end" fill="var(--faint)">{series.format(hi)}</text>
        <text x={PAD.l - 5} y={y(lo) + 3} textAnchor="end" fill="var(--faint)">{series.format(lo)}</text>
        <polyline
          points={vals.map((v, i) => `${x(i)},${y(v)}`).join(' ')}
          fill="none"
          stroke="var(--chart-series)"
          strokeWidth="1.6"
          strokeLinejoin="round"
        />
        {vals.map((v, i) => (
          <circle key={i} cx={x(i)} cy={y(v)} r={i === vals.length - 1 ? 3 : 2} fill={points[i].pass ? 'var(--chart-series)' : 'var(--bad)'}>
            <title>{`${points[i].at.slice(0, 16).replace('T', ' ')} — ${series.format(v)} (${points[i].fixtures} fixtures, ${points[i].pass ? 'pass' : 'FAIL'})`}</title>
          </circle>
        ))}
      </svg>
    </div>
  );
}

type CoverBy = 'stratum' | 'game' | 'resolution' | 'gpu' | 'cpu';

const keyOf = (by: CoverBy): ((f: Fixture) => string) =>
  by === 'stratum' ? (f) => f.stratum ?? 'unclassified'
  : by === 'game' ? (f) => f.gameId
  : by === 'resolution' ? (f) => f.resolution
  : by === 'gpu' ? (f) => f.gpuId
  : (f) => f.cpuId;

export function ModelHealth() {
  const { data } = useApp();
  const [coverBy, setCoverBy] = useState<CoverBy>('stratum');

  const ladder = useMemo(() => evidenceLadder(fixtures), []);
  // Derived from the measured share rather than written down, so it weakens on
  // its own as real captures arrive instead of becoming another stale claim.
  const claim = useMemo(() => accuracyClaim(ladder.measuredShare), [ladder.measuredShare]);
  const cv = lastRun.crossValidation;
  const inSample = lastRun.inSample;
  // The overfit tripwire: an in-sample fit far better than the out-of-fold one
  // means capacity was spent memorising fixtures rather than modelling anything.
  const gap = cv.medianAPE - inSample.medianAPE;
  const overfitting = gap > GATES.maxTrainHoldoutGap;

  const coverage = useMemo(() => {
    const by = keyOf(coverBy);
    // Where the product offers a fixed set of choices, pass that set in so a
    // choice with NO fixtures shows as a zero row rather than being absent.
    // An output the tool offers but has never validated is the single most
    // important thing this table can say, and hiding it would be the exact
    // failure the screen exists to prevent. Not done for parts: 231 GPUs sit
    // at zero, and a list of them is the note below, not a table.
    const universe =
      coverBy === 'resolution' ? (RESOLUTIONS as readonly string[])
      : coverBy === 'game' ? [...data.games.keys()]
      : undefined;
    return { rows: thinnestCells(fixtures, by, 14, universe), universe };
  }, [coverBy, data]);

  const cells = coverage.rows;
  // Counted over the WHOLE universe, not the truncated table. With 29 of 50
  // games unanchored, taking the count from the visible 14 rows would report
  // less than half the real gap while looking authoritative.
  const zeroCells = useMemo(() => {
    if (!coverage.universe) return [];
    const deliberate = new Set(Object.keys(excluded?.games ?? {}));
    return thinnestCells(fixtures, keyOf(coverBy), coverage.universe.length, coverage.universe)
      .filter((c) => c.rows === 0)
      // A deliberate exclusion is not an outstanding gap, and counting it as
      // one would overstate the work left while understating nothing.
      .filter((c) => !(coverBy === 'game' && deliberate.has(c.key)));
  }, [coverBy, coverage.universe]);

  // Catalogue parts with no fixture at all are a different kind of gap from a
  // thin one: nothing anchors them, so their estimates rest entirely on the
  // index model extrapolating from parts that were measured.
  const unanchored = useMemo(() => {
    const gpusSeen = new Set(fixtures.map((f) => f.gpuId));
    const cpusSeen = new Set(fixtures.map((f) => f.cpuId));
    return {
      gpus: data.gpus.size - gpusSeen.size,
      cpus: data.cpus.size - cpusSeen.size,
      gpuTotal: data.gpus.size,
      cpuTotal: data.cpus.size,
      gpusSeen: gpusSeen.size,
      cpusSeen: cpusSeen.size,
    };
  }, [data]);

  return (
    <>
      <div className="page-head">
        <h1>Model Health</h1>
        <p>
          What the estimates rest on, how the error figure is moving, and where the evidence is
          thinnest. Every figure here comes from the same module the validation gate uses, so this
          screen cannot quote a weighting the gate disagrees with.
        </p>
      </div>

      {/* Above everything, including the ladder. The error figure below is the
          most trustworthy-looking number in the tool and currently the one to
          trust least, and a reader who takes only one thing from this screen
          should take this. */}
      {claim.kind !== 'accuracy' && (
        <div className={`note ${claim.kind === 'self-consistency' ? 'bad' : 'warn'}`} style={{ marginBottom: 14 }}>
          <b>{claim.headline}</b> {claim.detail}
        </div>
      )}

      {/* --- Evidence ladder --------------------------------------------- */}
      <div className="panel">
        <div className="panel-head">
          <h2>Evidence ladder</h2>
          <span className="spacer" />
          <span className={`tag ${ladder.strictGateArmed ? 'good' : 'bad'}`}>
            {pct(ladder.measuredShare, 0)} measured
          </span>
        </div>
        <div className="panel-body">
          {!ladder.strictGateArmed && (
            <div className="note bad" style={{ marginBottom: 12 }}>
              <b>The strict tail gate is not armed.</b> Measurements carry {pct(ladder.measuredShare, 0)} of the
              evidence weight; the strict {pct(GATES.p90APE, 0)} p90 threshold arms at {pct(STRICT_GATE_ARMS_AT, 0)}.
              Until then the gate runs advisory at {pct(GATES.p90APEAdvisory, 0)}, because a tail computed over
              recollections would only reward fitting the recollections.{' '}
              {ladder.harnessRowsToArmStrictGate > 0 && (
                <>
                  <b>{ladder.harnessRowsToArmStrictGate} harness rows</b> would arm it — roughly{' '}
                  {Math.ceil(ladder.harnessRowsToArmStrictGate / 12)} full runs of the 12-game core suite.
                </>
              )}
            </div>
          )}
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr>
                  <th>tier</th><th className="n">per row</th><th className="n">rows</th>
                  <th className="n">weight</th><th style={{ width: '34%' }}>share of evidence</th><th>what it is</th>
                </tr>
              </thead>
              <tbody>
                {ladder.rungs.map((r) => (
                  <tr key={r.tier}>
                    <td className="mono">
                      {r.tier}
                      {r.measured && <span className="tag good" style={{ marginLeft: 6 }}>measured</span>}
                    </td>
                    <td className="n sub">{r.perRowWeight.toFixed(2)}</td>
                    <td className="n">{r.rows}</td>
                    <td className="n sub">{fmt(r.weight, 1)}</td>
                    <td>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                        <div style={{ flex: 1, height: 7, background: 'var(--surface-2)', borderRadius: 3, overflow: 'hidden' }}>
                          <div style={{ width: `${r.weightShare * 100}%`, height: '100%', background: r.measured ? 'var(--good)' : 'var(--chart-series)' }} />
                        </div>
                        <span className="mini mono" style={{ width: 42, textAlign: 'right' }}>{pct(r.weightShare, 0)}</span>
                      </div>
                    </td>
                    <td className="sub" style={{ fontSize: 12 }}>{r.label}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="mini" style={{ marginTop: 8 }}>
            Recalled rows are additionally scaled by stated recall confidence (high 1.0, medium 0.6,
            low 0.3); measurements are not, because a measurement is a measurement regardless of how
            well anyone remembers taking it. {ladder.totalRows} rows carry {fmt(ladder.totalWeight, 1)} total weight.
          </p>
        </div>
      </div>

      {/* --- Drift -------------------------------------------------------- */}
      <div className="panel">
        <div className="panel-head">
          <h2>{claim.kind === 'accuracy' ? 'Accuracy drift' : 'Error drift'}</h2>
          <span className="spacer" />
          <span className="mini">{history.length} recorded run{history.length === 1 ? '' : 's'}</span>
          <button
            className="btn"
            style={{ marginLeft: 8 }}
            onClick={() => exportCsv('rigcheck-validation-history.csv', history as unknown as Record<string, unknown>[])}
          >
            CSV
          </button>
        </div>
        <div className="panel-body">
          {history.length < 2 ? (
            <div className="empty">
              Only {history.length} validation run recorded. Drift needs at least two — run{' '}
              <span className="mono">npm run validate</span> again after a model change.
            </div>
          ) : (
            <div className="grid three" style={{ gap: 10 }}>
              {SERIES.map((s) => (
                <DriftChart key={s.key as string} series={s} points={history} />
              ))}
            </div>
          )}
          <p className="mini" style={{ marginTop: 10 }}>
            Every point is one grouped 5-fold cross-validation over the whole fixture set, with the
            per-game reference refitted inside each fold. Points are coloured by whether that run
            passed. A run whose fixture count changed is not comparable with the one before it, so
            the count is on every tooltip. What moves here is {claim.term} — a falling line means the
            estimator agrees more closely with the fixture set, which is only worth as much as the
            fixture set is.
          </p>
        </div>
      </div>

      {/* --- Current run -------------------------------------------------- */}
      <div className="panel">
        <div className="panel-head">
          <h2>Latest run</h2>
          <span className={`tag ${claim.kind === 'accuracy' ? 'good' : 'bad'}`}>{claim.term}</span>
          <span className="spacer" />
          <span className="mini mono">{lastRun.generatedAt.slice(0, 16).replace('T', ' ')}</span>
          <button
            className="btn"
            style={{ marginLeft: 8 }}
            onClick={() => exportJson('rigcheck-validation-run.json', lastRun)}
          >
            JSON
          </button>
        </div>
        <div className="panel-body">
          {overfitting && (
            <div className="note bad" style={{ marginBottom: 12 }}>
              <b>Overfitting tripwire.</b> Out-of-fold median error exceeds in-sample by{' '}
              {pct(gap)}, past the {pct(GATES.maxTrainHoldoutGap)} limit. That is capacity spent
              memorising fixtures rather than modelling anything.
            </div>
          )}
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr><th>metric</th><th className="n">out-of-fold</th><th className="n">in-sample</th><th className="n">gap</th><th>gate</th></tr>
              </thead>
              <tbody>
                {([
                  [
                    // Named for what it measures, not for what it would be if the
                    // fixtures were measurements. On a fully recalled set this
                    // reads "median disagreement with the recalled set", which is
                    // exactly what a 12% figure there means.
                    claim.kind === 'accuracy' ? 'median error' : 'median disagreement with the recalled set',
                    cv.medianAPE, inSample.medianAPE, `≤ ${pct(GATES.medianAPE, 0)}`, cv.medianAPE <= GATES.medianAPE, true,
                  ],
                  [claim.kind === 'accuracy' ? 'p90 error' : 'p90 disagreement (tail)', cv.p90APE, inSample.p90APE, `≤ ${pct(ladder.strictGateArmed ? GATES.p90APE : GATES.p90APEAdvisory, 0)}${ladder.strictGateArmed ? '' : ' (advisory)'}`, cv.p90APE <= (ladder.strictGateArmed ? GATES.p90APE : GATES.p90APEAdvisory), true],
                  [claim.kind === 'accuracy' ? 'mean error' : 'mean disagreement', cv.meanAPE, inSample.meanAPE, '—', true, true],
                  ['rank correlation', cv.spearman, inSample.spearman, `≥ ${GATES.spearman.toFixed(2)}`, cv.spearman >= GATES.spearman, false],
                  ['inside stated band', cv.withinBand, inSample.withinBand, '—', true, false],
                ] as [string, number, number, string, boolean, boolean][]).map(([label, out, ins, gate, ok, isPct]) => (
                  <tr key={label}>
                    <td className="sub">{label}</td>
                    <td className="n mono" style={{ color: ok ? 'var(--ink)' : 'var(--bad)' }}>{isPct ? pct(out) : out.toFixed(3)}</td>
                    <td className="n mono sub">{isPct ? pct(ins) : ins.toFixed(3)}</td>
                    <td className="n sub">{isPct ? pct(Math.abs(out - ins)) : Math.abs(out - ins).toFixed(3)}</td>
                    <td className="sub" style={{ fontSize: 12 }}>{gate}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="mini" style={{ marginTop: 8 }}>
            {cv.n} fixtures scored, {cv.blocked} short-circuited by a hard capability gate.
            Sign accuracy: {pct(cv.signAccuracy, 0)} over {cv.signPairs} matched pairs, with{' '}
            {cv.signAbstained} abstentions — pairs where the predicted delta sat inside the combined
            uncertainty, so the model declined to call a direction rather than guessing one.
            {lastRun.failures.length > 0 && <> Failures: {lastRun.failures.join('; ')}.</>}
          </p>
        </div>
      </div>

      {/* --- Coverage ----------------------------------------------------- */}
      <div className="panel">
        <div className="panel-head">
          <h2>Thinnest coverage</h2>
          <span className="spacer" />
          <div className="toggle-row">
            {(['stratum', 'game', 'resolution', 'gpu', 'cpu'] as CoverBy[]).map((k) => (
              <button key={k} className={`toggle${coverBy === k ? ' on' : ''}`} onClick={() => setCoverBy(k)}>{k}</button>
            ))}
          </div>
        </div>
        <div className="panel-body">
          <div className="table-wrap">
            <table className="data">
              <thead>
                <tr><th>{coverBy}</th><th className="n">fixtures</th><th className="n">evidence weight</th></tr>
              </thead>
              <tbody>
                {cells.map((c) => (
                  <tr key={c.key} style={c.rows === 0 ? { background: 'color-mix(in srgb, var(--bad) 9%, transparent)' } : undefined}>
                    <td className="mono" style={{ fontSize: 12 }}>
                      {c.key}
                      {c.rows === 0 && <span className="tag bad" style={{ marginLeft: 6 }}>no evidence</span>}
                    </td>
                    <td className="n" style={c.rows === 0 ? { color: 'var(--bad)' } : undefined}>{c.rows}</td>
                    <td className="n sub">{fmt(c.weight, 2)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {coverBy === 'game' && excluded && (
            <div className="note warn" style={{ marginTop: 12 }}>
              <b>{Object.keys(excluded.games).length} of the uncovered games are excluded on purpose.</b>{' '}
              {excluded.why}
              <ul style={{ margin: '8px 0 0', paddingLeft: 18 }}>
                {Object.entries(excluded.games).map(([id, reason]) => (
                  <li key={id} className="mini" style={{ marginBottom: 2 }}>
                    <span className="mono">{id}</span> — {reason}
                  </li>
                ))}
              </ul>
            </div>
          )}
          {zeroCells.length > 0 && (
            <div className="note bad" style={{ marginTop: 12 }}>
              <b>
                {zeroCells.length} of {coverage.universe?.length} {coverBy}s the tool offers{' '}
                {zeroCells.length === 1 ? 'has' : 'have'} no fixture at all
              </b>
              : <span className="mono" style={{ fontSize: 12 }}>{zeroCells.slice(0, 8).map((c) => c.key).join(', ')}</span>
              {zeroCells.length > 8 && <> and {zeroCells.length - 8} more</>}. Estimates on{' '}
              {zeroCells.length === 1 ? 'it' : 'them'} are pure extrapolation — nothing in the fixture
              set anchors them, and no validation figure on this page covers them. Treat those numbers
              as the weakest the tool produces.
            </div>
          )}
          <div className="note" style={{ marginTop: 12 }}>
            <b>{unanchored.gpus} of {unanchored.gpuTotal} GPUs and {unanchored.cpus} of {unanchored.cpuTotal} CPUs
            appear in no fixture at all.</b> Their estimates rest entirely on the index model
            extrapolating from the {unanchored.gpusSeen} GPUs and {unanchored.cpusSeen} CPUs that do
            appear. That is the intended design — a per-SKU fixture for 707 parts is not achievable —
            but it means an unusual part is further from evidence than a popular one.
          </div>
          <p className="mini" style={{ marginTop: 8 }}>
            This ranks by how little evidence sits behind each cell. It is a coverage measure, not an
            information-gain calculation: it says where the data is thin, not which single
            measurement would move the model most. Those usually coincide, but not always.
          </p>
        </div>
      </div>
    </>
  );
}
