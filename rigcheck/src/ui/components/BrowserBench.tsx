/**
 * The in-browser benchmark, as a screen.
 *
 * Design constraints that are not negotiable here:
 *
 *  - **It must say what it cannot do, before it runs.** A benchmark that
 *    produces a number invites the reader to treat that number as a frame
 *    rate. The limits go above the button, not in a footnote under the result.
 *  - **It must be interruptible.** A minute of full GPU load with no way out
 *    is hostile, especially on a laptop.
 *  - **Progress must be real.** A spinner during a sixty-second load reads as
 *    a hang; the phase and the seconds remaining are shown throughout.
 *  - **Every number must carry its units and its limits.** The three workload
 *    figures are in units this benchmark invented. They are shown, because
 *    they are what was measured, and they are never scored, because there is
 *    nothing to score them against.
 */

import { useEffect, useMemo, useRef, useState } from 'react';
import {
  benchSummary, benchFindings, bucketWindows, cacheReading, coreInference, corroborateCpu,
  rendererClass, scalingVerdict, stabilityVerdict, steadinessVerdict, throttleVerdict, workloadSummary,
  type BenchContext, type BenchResult, type LatencyPoint, type ScalingPoint,
} from '../../core/browserbench.ts';
import { DEFAULT_DEPTH, estimateSeconds, runBenchmark, type Depth, type Progress } from '../bench/run.ts';

const DEPTHS: { id: Depth; label: string; sees: string }[] = [
  { id: 'quick', label: 'quick', sees: 'Which adapter is rendering, software rendering, the cache levels and the core topology.' },
  { id: 'standard', label: 'standard', sees: 'All of the above, plus enough sustained load to see a part that cannot hold its clocks.' },
  { id: 'thorough', label: 'thorough', sees: 'All of the above, held long enough to warm the machine properly — which is when a cooling problem actually appears.' },
];

export function BrowserBenchPanel({
  onResult,
  context,
}: {
  onResult: (r: BenchResult) => void;
  /** What the form says is in the machine, so the run can be checked against it. */
  context?: BenchContext;
}) {
  const [depth, setDepth] = useState<Depth>(DEFAULT_DEPTH);
  const [running, setRunning] = useState(false);
  const [progress, setProgress] = useState<Progress | null>(null);
  const [result, setResult] = useState<BenchResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const abort = useRef<AbortController | null>(null);

  // A run holds the GPU at full load; leaving one going after the panel is
  // gone would keep the fans up with nothing on screen to explain why.
  useEffect(() => () => abort.current?.abort(), []);

  const start = async () => {
    setError(null);
    setResult(null);
    setRunning(true);
    abort.current = new AbortController();
    try {
      const r = await runBenchmark({ depth, signal: abort.current.signal, onProgress: setProgress });
      setResult(r);
      onResult(r);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setRunning(false);
      setProgress(null);
    }
  };

  return (
    <div className="panel sub-panel" style={{ marginTop: 14 }}>
      <div className="panel-head">
        <h3>Or measure it here, with nothing to install</h3>
        <span className="spacer" />
        <span className="tag">no download</span>
      </div>
      <div className="panel-body">
        <p className="mini" style={{ marginTop: 0 }}>
          This runs three graphics workloads, a processor scaling ladder and a memory-latency sweep inside
          this page. It is not as thorough as the script, and it is worth being exact about the difference:
        </p>

        <div className="consent-cols" style={{ marginBottom: 14 }}>
          <div>
            <h4>what it can tell you</h4>
            <ul>
              <li>Which graphics adapter is <em>actually</em> rendering — the commonest invisible fault on a laptop is the integrated chip doing the work while the real card idles</li>
              <li>Whether graphics are being done in software, with no card involved at all</li>
              <li>Whether performance falls away under a sustained load, which is what a cooling problem looks like</li>
              <li>How many cores the machine really has, read off the shape of the thread-scaling curve rather than looked up</li>
              <li>Where its cache levels end, read off a memory-latency sweep — which is a way of checking the processor on the form is the processor in the machine</li>
            </ul>
          </div>
          <div className="no">
            <h4>what it cannot</h4>
            <ul>
              <li>Tell you a frame rate. A shader in a browser tab and a game engine have almost nothing in common, and nothing here is converted into fps</li>
              <li>Score your card against other cards. No measured browser results exist for this catalogue, and inventing thresholds would be the exact error this project refuses to make</li>
              <li>Compare its own numbers with the catalogue's specifications. Those are in different units by an unknown factor — so the comparisons here are against this machine on another day, where the factor cancels</li>
              <li>Read your motherboard, memory timings, driver version or PCIe link — the script reads those, this cannot</li>
            </ul>
          </div>
        </div>

        {!running && (
          <>
            <div className="field" style={{ marginBottom: 10 }}>
              <label>how long to run</label>
              <div className="toggle-row">
                {DEPTHS.map((d) => (
                  <button
                    key={d.id}
                    className={`toggle${depth === d.id ? ' on' : ''}`}
                    onClick={() => setDepth(d.id)}
                  >
                    {d.label} · {formatDuration(estimateSeconds(d.id))}
                  </button>
                ))}
              </div>
              <div className="mini" style={{ marginTop: 5 }}>{DEPTHS.find((d) => d.id === depth)!.sees}</div>
            </div>
            <div className="wizard-nav" style={{ marginTop: 0 }}>
              <button className="btn primary" onClick={start}>
                {result ? 'run it again' : 'run the test'}
              </button>
              <span className="mini">
                Keep this tab in front — browsers slow down background tabs, which would show up as a fault
                that is not there.
              </span>
            </div>
          </>
        )}

        {running && progress && (
          <div>
            <div className="meter" style={{ height: 6, marginBottom: 8 }}>
              <i style={{ width: `${Math.round(progress.fraction * 100)}%` }} />
            </div>
            <div className="wizard-nav" style={{ marginTop: 0 }}>
              <span className="mono" style={{ fontSize: 12 }}>{progress.label}</span>
              <span className="spacer" style={{ flex: 1 }} />
              <button className="btn" onClick={() => abort.current?.abort()}>stop</button>
            </div>
          </div>
        )}

        {error && <div className="note bad" style={{ marginTop: 12 }}>{error}</div>}

        {result && <BenchReadout result={result} context={context} />}
      </div>
    </div>
  );
}

function formatDuration(seconds: number): string {
  if (seconds < 90) return `${seconds}s`;
  return `${Math.round(seconds / 60)} min`;
}

/* ------------------------------------------------------------- readout -- */

function BenchReadout({ result, context }: { result: BenchResult; context?: BenchContext }) {
  const findings = useMemo(() => benchFindings(result, context ?? {}), [result, context]);
  const noise = result.cpu?.variation ?? 0;
  const gpuThr = result.gpu ? throttleVerdict(result.gpu.windows, noise) : null;
  const cpuThr = result.cpu ? throttleVerdict(result.cpu.windows, noise) : null;
  const sc = result.cpu ? scalingVerdict(result.cpu) : null;
  const steady = steadinessVerdict(result.gpu?.passMs ?? []);
  const cores = result.cpu?.scaling ? coreInference(result.cpu.scaling, result.cpu.reportedThreads) : null;
  const cache = result.cpu?.latencyCurve ? cacheReading(result.cpu.latencyCurve) : null;
  const stab = stabilityVerdict(result.cpu?.variation);
  const corr = result.cpu ? corroborateCpu(result.cpu, context?.expectedCpu) : null;
  const workloads = workloadSummary(result.gpu?.workloads);

  return (
    <div style={{ marginTop: 14 }}>
      <div className="verdict-hero" style={{ marginBottom: 14 }}>{benchSummary(result, findings)}</div>

      {/* -- identity ------------------------------------------------------ */}
      {result.gpu && (
        <div className="kv" style={{ marginBottom: 14 }}>
          <dt>rendering on</dt>
          <dd>
            {result.gpu.renderer || 'name withheld by the browser'}
            {result.gpu.renderer && (
              <span
                className={`tag ${rendererClass(result.gpu.renderer) === 'discrete' ? 'good' : rendererClass(result.gpu.renderer) === 'software' ? 'bad' : ''}`}
                style={{ marginLeft: 8 }}
              >
                {rendererClass(result.gpu.renderer)}
              </span>
            )}
          </dd>
          {result.gpu.adapter?.device && (
            <>
              <dt>WebGPU says</dt>
              <dd>
                {[result.gpu.adapter.vendor, result.gpu.adapter.architecture, result.gpu.adapter.device]
                  .filter(Boolean)
                  .join(' · ')}
              </dd>
            </>
          )}
          {context?.expectedGpuName && (
            <>
              <dt>you said</dt>
              <dd>{context.expectedGpuName}</dd>
            </>
          )}
        </div>
      )}

      {/* -- the three graphics workloads ---------------------------------- */}
      {workloads.length > 0 && (
        <div style={{ marginBottom: 14 }}>
          <div className="mini" style={{ marginBottom: 6 }}>
            three graphics workloads, at their peak — units this benchmark invented, comparable only with
            this same machine on another day
          </div>
          <div className="stat-row">
            {workloads.map((w) => (
              <div className="stat" key={w.kind}>
                <span className="v" style={{ fontSize: 18 }}>{w.value}</span>
                <span className="k">{w.label}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* -- self-referential readings ------------------------------------- */}
      <div className="stat-row" style={{ marginBottom: 14 }}>
        {gpuThr && (
          <div className="stat">
            <span className="v" style={{ color: gpuThr.declined ? 'var(--bad)' : 'var(--good)' }}>
              {(gpuThr.retained * 100).toFixed(0)}%
            </span>
            <span className="k">graphics held under load</span>
          </div>
        )}
        {cpuThr && (
          <div className="stat">
            <span className="v" style={{ color: cpuThr.declined ? 'var(--bad)' : 'var(--good)' }}>
              {(cpuThr.retained * 100).toFixed(0)}%
            </span>
            <span className="k">processor held under load</span>
          </div>
        )}
        {steady && (
          <div className="stat">
            <span className="v" style={{ color: steady.steady ? 'var(--good)' : 'var(--bad)' }}>
              {steady.pct.worstRatio.toFixed(1)}x
            </span>
            <span className="k">worst 1% of passes</span>
          </div>
        )}
        {sc && (
          <div className="stat">
            <span className="v" style={{ color: sc.healthy ? 'var(--good)' : 'var(--bad)' }}>
              {sc.speedup.toFixed(1)}x
            </span>
            <span className="k">from {result.cpu!.workersUsed} threads</span>
          </div>
        )}
        {stab && (
          <div className="stat">
            <span className="v" style={{ color: stab.reliable ? 'var(--good)' : 'var(--bad)' }}>
              ±{(stab.variation * 100).toFixed(1)}%
            </span>
            <span className="k">repeatability</span>
          </div>
        )}
        {result.memory && (
          <div className="stat">
            <span className="v">{result.memory.copyGBs.toFixed(1)}</span>
            <span className="k">GB/s sequential</span>
          </div>
        )}
      </div>

      {stab && !stab.reliable && <div className="note warn" style={{ marginBottom: 14 }}>{stab.detail}</div>}

      {/* -- what the shape of the machine says ---------------------------- */}
      {(cores || cache) && (
        <div className="grid two" style={{ marginBottom: 14 }}>
          {result.cpu?.scaling && result.cpu.scaling.length > 2 && (
            <div>
              <ScalingChart points={result.cpu.scaling} knee={cores?.knee} />
              {cores && <div className="mini" style={{ marginTop: 6 }}>{cores.detail}</div>}
            </div>
          )}
          {result.cpu?.latencyCurve && result.cpu.latencyCurve.length > 4 && (
            <div>
              <LatencyChart points={result.cpu.latencyCurve} boundary={cache?.lastLevelBytes ?? undefined} />
              {cache && <div className="mini" style={{ marginTop: 6 }}>{cache.detail}</div>}
            </div>
          )}
        </div>
      )}

      {/* -- does the machine match the form? ------------------------------ */}
      {corr && corr.checks.length > 0 && (
        <div style={{ marginBottom: 14 }}>
          <div className="mini" style={{ marginBottom: 6 }}>
            measured against the {context?.expectedCpu?.fullName} on the form
          </div>
          {corr.checks.map((c) => (
            <div key={c.label} className="check-row">
              <span className={`tag ${c.agrees === true ? 'good' : c.agrees === false ? 'bad' : ''}`}>
                {c.agrees === true ? 'agrees' : c.agrees === false ? 'differs' : 'not checked'}
              </span>
              <span>{c.detail}</span>
            </div>
          ))}
        </div>
      )}

      {result.gpu && result.gpu.windows.length > 3 && (
        <DeclineChart windows={bucketWindows(result.gpu.windows)} label="graphics throughput over the run" />
      )}

      {findings.length > 0 && (
        <div style={{ marginTop: 14 }}>
          {findings.map((f) => (
            <div key={f.id} className={`finding ${f.severity}`}>
              <div className="head">
                <span className="tag">{f.component}</span>
                <b>{f.title}</b>
              </div>
              <div className="row"><span className="k">seen</span>{f.evidence}</div>
              {f.remedy && <div className="row"><span className="k">fix</span>{f.remedy}</div>}
            </div>
          ))}
        </div>
      )}

      {result.notes.map((n) => (
        <div key={n} className="note warn" style={{ marginTop: 8 }}>{n}</div>
      ))}

      <p className="mini" style={{ marginTop: 12 }}>
        These findings are folded into the verdict on the next screens alongside everything read from the
        specification. The throughput figures are in arbitrary units and are only meaningful against this
        same machine on another day — which is exactly what the history screen compares them to.
      </p>
    </div>
  );
}

/* -------------------------------------------------------------- charts -- */

/**
 * Throughput against time.
 *
 * The y-axis deliberately starts at zero. A chart zoomed to the data makes
 * every run look like a cliff, and the question here is "did this fall
 * meaningfully", which a truthful axis answers and a flattering one does not.
 */
function DeclineChart({ windows, label }: { windows: { atMs: number; throughput: number }[]; label: string }) {
  const w = 640;
  const h = 120;
  const pad = { l: 4, r: 4, t: 8, b: 16 };
  const max = Math.max(...windows.map((p) => p.throughput));
  const maxT = windows[windows.length - 1].atMs || 1;
  if (!(max > 0)) return null;

  const x = (t: number) => pad.l + (t / maxT) * (w - pad.l - pad.r);
  const y = (v: number) => pad.t + (1 - v / max) * (h - pad.t - pad.b);
  const line = windows.map((p, i) => `${i ? 'L' : 'M'}${x(p.atMs).toFixed(1)} ${y(p.throughput).toFixed(1)}`).join(' ');
  const area = `${line} L${x(maxT).toFixed(1)} ${(h - pad.b).toFixed(1)} L${x(0).toFixed(1)} ${(h - pad.b).toFixed(1)} Z`;

  return (
    <div>
      <div className="mini" style={{ marginBottom: 4 }}>{label}</div>
      <div className="chart-wrap"><svg viewBox={`0 0 ${w} ${h}`} className="curve" role="img" aria-label={label}>
        <path d={area} className="band" />
        <path d={line} className="series" />
        <line x1={pad.l} y1={h - pad.b} x2={w - pad.r} y2={h - pad.b} className="axis" />
        <text x={pad.l} y={h - 4}>0s</text>
        <text x={w - pad.r} y={h - 4} textAnchor="end">{(maxT / 1000).toFixed(0)}s</text>
      </svg></div>
    </div>
  );
}

/**
 * Latency against working-set size, on a logarithmic x-axis.
 *
 * Logarithmic because the sizes span four orders of magnitude and the steps
 * matter equally at every scale: on a linear axis the first five samples pile
 * up against the left edge and the staircase this exists to show is invisible.
 */
function LatencyChart({ points, boundary }: { points: LatencyPoint[]; boundary?: number }) {
  const w = 320;
  const h = 150;
  const pad = { l: 30, r: 6, t: 8, b: 20 };
  const pts = [...points].sort((a, b) => a.bytes - b.bytes);
  const maxNs = Math.max(...pts.map((p) => p.latencyNs));
  if (!(maxNs > 0) || pts.length < 2) return null;

  const lo = Math.log2(pts[0].bytes);
  const hi = Math.log2(pts[pts.length - 1].bytes);
  const x = (b: number) => pad.l + ((Math.log2(b) - lo) / Math.max(0.001, hi - lo)) * (w - pad.l - pad.r);
  const y = (ns: number) => pad.t + (1 - ns / maxNs) * (h - pad.t - pad.b);
  const line = pts.map((p, i) => `${i ? 'L' : 'M'}${x(p.bytes).toFixed(1)} ${y(p.latencyNs).toFixed(1)}`).join(' ');
  const size = (b: number) => (b >= 1 << 20 ? `${b / (1 << 20)}M` : `${b / 1024}K`);

  return (
    <div>
      <div className="mini" style={{ marginBottom: 4 }}>memory latency by working-set size</div>
      <div className="chart-wrap"><svg viewBox={`0 0 ${w} ${h}`} className="curve" role="img" aria-label="memory latency against working-set size">
        {boundary && (
          <line x1={x(boundary)} y1={pad.t} x2={x(boundary)} y2={h - pad.b} className="crosshair" />
        )}
        <path d={line} className="series" />
        {pts.map((p) => (
          <circle key={p.bytes} cx={x(p.bytes)} cy={y(p.latencyNs)} r={2.5} className="pt" />
        ))}
        <line x1={pad.l} y1={h - pad.b} x2={w - pad.r} y2={h - pad.b} className="axis" />
        <line x1={pad.l} y1={pad.t} x2={pad.l} y2={h - pad.b} className="axis" />
        <text x={pad.l - 4} y={pad.t + 6} textAnchor="end">{maxNs.toFixed(0)}ns</text>
        <text x={pad.l - 4} y={h - pad.b} textAnchor="end">0</text>
        <text x={pad.l} y={h - 6}>{size(pts[0].bytes)}</text>
        <text x={w - pad.r} y={h - 6} textAnchor="end">{size(pts[pts.length - 1].bytes)}</text>
      </svg></div>
    </div>
  );
}

/**
 * Per-thread throughput against how many threads were running.
 *
 * Per-thread rather than total, because total always rises and therefore says
 * nothing. The line falling is the whole point: it falls where workers stop
 * getting a core each.
 */
function ScalingChart({ points, knee }: { points: ScalingPoint[]; knee?: number }) {
  const w = 320;
  const h = 150;
  const pad = { l: 30, r: 6, t: 8, b: 20 };
  const pts = [...points].sort((a, b) => a.threads - b.threads);
  if (pts.length < 2 || !(pts[0].totalOps > 0)) return null;
  const base = pts[0].totalOps;
  const eff = (p: ScalingPoint) => p.totalOps / p.threads / base;

  const lo = 0;
  const hi = Math.log2(pts[pts.length - 1].threads);
  const x = (t: number) => pad.l + ((Math.log2(t) - lo) / Math.max(0.001, hi - lo)) * (w - pad.l - pad.r);
  const y = (e: number) => pad.t + (1 - Math.min(1.05, e) / 1.05) * (h - pad.t - pad.b);
  const line = pts.map((p, i) => `${i ? 'L' : 'M'}${x(p.threads).toFixed(1)} ${y(eff(p)).toFixed(1)}`).join(' ');

  return (
    <div>
      <div className="mini" style={{ marginBottom: 4 }}>throughput per thread, as threads are added</div>
      <div className="chart-wrap"><svg viewBox={`0 0 ${w} ${h}`} className="curve" role="img" aria-label="per-thread throughput against thread count">
        <line x1={pad.l} y1={y(1)} x2={w - pad.r} y2={y(1)} className="grid-line" />
        <path d={line} className="series" />
        {pts.map((p) => (
          <circle
            key={p.threads}
            cx={x(p.threads)}
            cy={y(eff(p))}
            r={2.5}
            className={`pt${knee === p.threads ? ' knee' : ''}`}
          />
        ))}
        <line x1={pad.l} y1={h - pad.b} x2={w - pad.r} y2={h - pad.b} className="axis" />
        <line x1={pad.l} y1={pad.t} x2={pad.l} y2={h - pad.b} className="axis" />
        <text x={pad.l - 4} y={y(1) + 3} textAnchor="end">100%</text>
        <text x={pad.l - 4} y={h - pad.b} textAnchor="end">0</text>
        <text x={pad.l} y={h - 6}>1</text>
        <text x={w - pad.r} y={h - 6} textAnchor="end">{pts[pts.length - 1].threads}</text>
      </svg></div>
    </div>
  );
}
