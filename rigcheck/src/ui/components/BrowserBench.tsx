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
 */

import { useEffect, useRef, useState } from 'react';
import {
  benchSummary, benchFindings, bucketWindows, rendererClass, scalingVerdict, throttleVerdict,
  type BenchResult,
} from '../../core/browserbench.ts';
import { DEFAULT_BENCH, runBenchmark, type Progress } from '../bench/run.ts';

export function BrowserBenchPanel({
  onResult,
  expectedGpuName,
}: {
  onResult: (r: BenchResult) => void;
  expectedGpuName?: string;
}) {
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
      const r = await runBenchmark({
        ...DEFAULT_BENCH,
        signal: abort.current.signal,
        onProgress: setProgress,
      });
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
          This runs a graphics load, a processor load and a memory test inside this page for about a
          minute. It is not as thorough as the script, and it is worth being exact about the difference:
        </p>

        <div className="consent-cols" style={{ marginBottom: 14 }}>
          <div>
            <h4>what it can tell you</h4>
            <ul>
              <li>Which graphics adapter is <em>actually</em> rendering — the commonest invisible fault on a laptop is the integrated chip doing the work while the real card idles</li>
              <li>Whether graphics are being done in software, with no card involved at all</li>
              <li>Whether performance falls away under a sustained load, which is what a cooling problem looks like</li>
              <li>Whether the processor is using all its cores at full speed</li>
            </ul>
          </div>
          <div className="no">
            <h4>what it cannot</h4>
            <ul>
              <li>Tell you a frame rate. A shader in a browser tab and a game engine have almost nothing in common, and nothing here is converted into fps</li>
              <li>Score your card against other cards. No measured browser results exist for this catalogue, and inventing thresholds would be the exact error this project refuses to make</li>
              <li>Read your motherboard, memory timings, driver version or PCIe link — the script reads those, this cannot</li>
            </ul>
          </div>
        </div>

        {!running && (
          <div className="wizard-nav" style={{ marginTop: 0 }}>
            <button className="btn primary" onClick={start}>
              {result ? 'run it again' : 'run the test'}
            </button>
            <span className="mini">
              About {DEFAULT_BENCH.gpuSustainedS + DEFAULT_BENCH.cpuSustainedS + 10}s. Keep this tab in
              front — browsers slow down background tabs, which would show up as a fault that is not there.
            </span>
          </div>
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

        {result && <BenchReadout result={result} expectedGpuName={expectedGpuName} />}
      </div>
    </div>
  );
}

function BenchReadout({ result, expectedGpuName }: { result: BenchResult; expectedGpuName?: string }) {
  const findings = benchFindings(result, { expectedGpuName });
  const gpuThr = result.gpu ? throttleVerdict(result.gpu.windows) : null;
  const cpuThr = result.cpu ? throttleVerdict(result.cpu.windows) : null;
  const sc = result.cpu ? scalingVerdict(result.cpu) : null;

  return (
    <div style={{ marginTop: 14 }}>
      <div className="verdict-hero" style={{ marginBottom: 14 }}>{benchSummary(result, findings)}</div>

      {result.gpu && (
        <div className="kv" style={{ marginBottom: 14 }}>
          <dt>rendering on</dt>
          <dd>
            {result.gpu.renderer || 'name withheld by the browser'}
            {result.gpu.renderer && (
              <span className={`tag ${rendererClass(result.gpu.renderer) === 'discrete' ? 'good' : rendererClass(result.gpu.renderer) === 'software' ? 'bad' : ''}`} style={{ marginLeft: 8 }}>
                {rendererClass(result.gpu.renderer)}
              </span>
            )}
          </dd>
          {expectedGpuName && <><dt>you said</dt><dd>{expectedGpuName}</dd></>}
        </div>
      )}

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
        {sc && (
          <div className="stat">
            <span className="v" style={{ color: sc.healthy ? 'var(--good)' : 'var(--bad)' }}>
              {sc.speedup.toFixed(1)}x
            </span>
            <span className="k">from {result.cpu!.workersUsed} threads</span>
          </div>
        )}
        {result.memory && (
          <div className="stat">
            <span className="v">{result.memory.copyGBs.toFixed(1)}</span>
            <span className="k">GB/s sequential</span>
          </div>
        )}
      </div>

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
        specification. The numbers above are in arbitrary units and are only meaningful against this same
        machine on another day — which is exactly what the history screen compares them to.
      </p>
    </div>
  );
}

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
      <svg viewBox={`0 0 ${w} ${h}`} className="curve" role="img" aria-label={label}>
        <path d={area} className="band" />
        <path d={line} className="series" />
        <line x1={pad.l} y1={h - pad.b} x2={w - pad.r} y2={h - pad.b} className="axis" />
        <text x={pad.l} y={h - 4}>0s</text>
        <text x={w - pad.r} y={h - 4} textAnchor="end">{(maxT / 1000).toFixed(0)}s</text>
      </svg>
    </div>
  );
}
