import { describe, expect, it } from 'vitest';
import {
  benchFindings, benchSummary, bucketWindows, isSoftwareRenderer, rendererClass, scalingVerdict, throttleVerdict,
  type BenchResult, type BenchWindow, type CpuProbe,
} from '../src/core/browserbench.ts';
import { deriveVerdict, type Finding } from '../src/core/health.ts';

const win = (xs: number[]): BenchWindow[] => xs.map((t, i) => ({ atMs: i * 1000, throughput: t }));

const cpu = (over: Partial<CpuProbe> = {}): CpuProbe => ({
  singleThread: 1e6, multiThread: 6e6, reportedThreads: 12, workersUsed: 12, windows: [], ...over,
});

const result = (over: Partial<BenchResult> = {}): BenchResult => ({
  startedAt: '2026-08-20T00:00:00.000Z', durationS: 60,
  gpu: null, cpu: null, memory: null, notes: [], interrupted: false, ...over,
});

describe('renderer classification', () => {
  it('names the software rasterisers', () => {
    expect(isSoftwareRenderer('Google SwiftShader')).toBe(true);
    expect(isSoftwareRenderer('llvmpipe (LLVM 15.0.7, 256 bits)')).toBe(true);
    expect(isSoftwareRenderer('Microsoft Basic Render Driver')).toBe(true);
    expect(isSoftwareRenderer('NVIDIA GeForce RTX 4070')).toBe(false);
  });

  it('separates discrete cards from integrated chips', () => {
    expect(rendererClass('ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Direct3D11)')).toBe('discrete');
    expect(rendererClass('ANGLE (AMD, AMD Radeon RX 6800 XT Direct3D11)')).toBe('discrete');
    expect(rendererClass('ANGLE (Intel, Intel(R) UHD Graphics 770 Direct3D11)')).toBe('integrated');
    expect(rendererClass('AMD Radeon(TM) Graphics')).toBe('integrated');
    expect(rendererClass('Google SwiftShader')).toBe('software');
    expect(rendererClass('')).toBe('unknown');
  });

  it('does not mistake Intel Arc for integrated graphics', () => {
    // The failure this guards: Arc strings contain "Intel(R)", which an
    // iGPU-pattern check claims. A discrete Arc reported as integrated would
    // raise a false "wrong adapter" alarm at CRITICAL on a correct machine.
    expect(rendererClass('ANGLE (Intel, Intel(R) Arc(TM) A770 Graphics Direct3D11)')).toBe('discrete');
  });
});

describe('decline under sustained load', () => {
  it('says nothing without enough windows to see a trend', () => {
    expect(throttleVerdict(win([100, 99, 98]))).toBeNull();
  });

  it('calls a flat run healthy', () => {
    const v = throttleVerdict(win([100, 101, 99, 100, 100, 98, 101, 100]))!;
    expect(v.declined).toBe(false);
    expect(v.retained).toBeGreaterThan(0.95);
  });

  it('does not call ordinary boost settling a fault', () => {
    // Boost clocks are not meant to be held. A few percent off the opening
    // rate is how every healthy part behaves and must not raise a finding.
    const v = throttleVerdict(win([100, 100, 98, 97, 96, 95, 95, 94]))!;
    expect(v.declined).toBe(false);
  });

  it('catches a genuine slide', () => {
    const v = throttleVerdict(win([100, 98, 92, 85, 78, 72, 68, 65]))!;
    expect(v.declined).toBe(true);
    expect(v.retained).toBeLessThan(0.75);
    expect(v.detail).toMatch(/fell \d+%/);
  });
});

describe('core scaling', () => {
  it('needs more than one worker to say anything', () => {
    expect(scalingVerdict(cpu({ workersUsed: 1 }))).toBeNull();
  });

  it('accepts sub-linear scaling as normal', () => {
    // 12 threads at 6x is 50% of linear — normal once SMT siblings and shared
    // memory bandwidth are accounted for. Demanding near-linear here would
    // flag every healthy hyper-threaded machine.
    const v = scalingVerdict(cpu())!;
    expect(v.healthy).toBe(true);
    expect(v.speedup).toBeCloseTo(6, 5);
  });

  it('flags a machine barely using its cores', () => {
    const v = scalingVerdict(cpu({ multiThread: 2.4e6 }))!;
    expect(v.healthy).toBe(false);
    expect(v.detail).toMatch(/power plan|parked/i);
  });
});

describe('findings', () => {
  it('calls software rendering critical', () => {
    const f = benchFindings(result({
      gpu: { renderer: 'Google SwiftShader', vendor: 'Google', api: 'webgl2', throughput: 1e6, windows: [] },
    }));
    const soft = f.find((x) => x.id === 'bench-software-rendering')!;
    expect(soft.severity).toBe('critical');
    expect(soft.measured).toBe(true);
  });

  it('catches a laptop rendering on the iGPU while a discrete card idles', () => {
    const f = benchFindings(
      result({ gpu: { renderer: 'Intel(R) UHD Graphics 770', vendor: 'Intel', api: 'webgl2', throughput: 1e6, windows: [] } }),
      { expectedGpuName: 'NVIDIA GeForce RTX 4070' },
    );
    expect(f.find((x) => x.id === 'bench-wrong-adapter')?.severity).toBe('critical');
  });

  it('does not cry wrong-adapter when the machine really has integrated graphics', () => {
    const f = benchFindings(
      result({ gpu: { renderer: 'AMD Radeon(TM) Graphics', vendor: 'AMD', api: 'webgl2', throughput: 1e6, windows: [] } }),
      { expectedGpuName: 'AMD Radeon 780M' },
    );
    expect(f.find((x) => x.id === 'bench-wrong-adapter')).toBeUndefined();
  });

  it('never emits a frame rate, in any field, from any input', () => {
    // The rule the whole module exists to keep: browser throughput is not fps
    // and must never be presented as it.
    const f = benchFindings(
      result({
        gpu: { renderer: 'NVIDIA GeForce RTX 3060', vendor: 'NVIDIA', api: 'webgl2', throughput: 9e8, windows: win([100, 90, 80, 70, 60, 55, 50, 48]) },
        cpu: cpu({ multiThread: 1.5e6, windows: win([100, 99, 98, 97, 96, 95, 94, 93]) }),
        memory: { copyGBs: 12, stridedGBs: 2 },
      }),
      { expectedGpuName: 'NVIDIA GeForce RTX 3060' },
    );
    const text = f.map((x) => `${x.title} ${x.evidence} ${x.impact} ${x.remedy ?? ''}`).join(' ');
    expect(text).not.toMatch(/\d+\s*fps/i);
    expect(text).not.toMatch(/frames per second/i);
  });

  /**
   * The removed cross-check, pinned so it cannot come back.
   *
   * It compared the measured GPU-to-CPU throughput ratio with the ratio the
   * catalogue implies. Both measured figures are in units this benchmark
   * invented, so the two ratios differ by an unknown constant — and with the
   * constant assumed to be 1, the check fires on every machine that has a
   * working graphics card. A real RTX 3060 measures its shader loop in the
   * hundreds of billions of operations a second against a few hundred million
   * integer operations on one core, so the ratio comes out in the hundreds
   * where the catalogue expects about three.
   */
  it('makes no absolute comparison between browser throughput and the catalogue', () => {
    const f = benchFindings(
      result({
        gpu: { renderer: 'NVIDIA GeForce RTX 3060', vendor: 'NVIDIA', api: 'webgl2', throughput: 2.4e11, windows: [] },
        cpu: cpu({ singleThread: 4.3e8, multiThread: 4.3e8 * 7 }),
      }),
      { expectedGpuName: 'NVIDIA GeForce RTX 3060', expectedGpuId: 'nvidia-geforce-rtx-3060-12gb' },
    );
    expect(f.map((x) => x.id)).not.toContain('bench-ratio-mismatch');
    expect(f.filter((x) => x.severity !== 'ok' && x.severity !== 'unknown')).toHaveLength(0);
  });

  it('reports the card in the machine differing from the card on the form', () => {
    const f = benchFindings(
      result({
        gpu: { renderer: 'NVIDIA GeForce RTX 3060', vendor: 'NVIDIA', api: 'webgl2', throughput: 1e6, windows: [] },
        cpu: cpu(),
      }),
      {
        expectedGpuId: 'nvidia-geforce-rtx-4070',
        expectedGpuName: 'NVIDIA GeForce RTX 4070',
        detectedGpuId: 'nvidia-geforce-rtx-3060-12gb',
        detectedGpuName: 'NVIDIA GeForce RTX 3060 12GB',
      },
    );
    const m = f.find((x) => x.id === 'bench-gpu-mismatch')!;
    expect(m.severity).toBe('major');
    expect(m.evidence).toContain('RTX 3060');
    expect(m.evidence).toContain('RTX 4070');
  });

  it('stays quiet when the detected card matches the selected one', () => {
    const f = benchFindings(
      result({
        gpu: { renderer: 'NVIDIA GeForce RTX 3060', vendor: 'NVIDIA', api: 'webgl2', throughput: 1e6, windows: [] },
        cpu: cpu(),
      }),
      { expectedGpuId: 'nvidia-geforce-rtx-3060-12gb', detectedGpuId: 'nvidia-geforce-rtx-3060-12gb' },
    );
    expect(f.map((x) => x.id)).not.toContain('bench-gpu-mismatch');
  });

  it('invalidates a run measured in a background tab', () => {
    const f = benchFindings(result({ interrupted: true }));
    const i = f.find((x) => x.id === 'bench-interrupted')!;
    expect(i.severity).toBe('unknown');
    expect(i.measured).toBe(false);
  });
});

describe('summary', () => {
  it('states the limits rather than leading with a score', () => {
    const r = result({ gpu: { renderer: 'NVIDIA GeForce RTX 3060', vendor: 'NVIDIA', api: 'webgl2', throughput: 1e8, windows: [] } });
    const s = benchSummary(r, benchFindings(r));
    expect(s).toMatch(/not enough to tell you a frame rate/i);
  });
});

describe('bucketing for the chart', () => {
  it('leaves a short run alone', () => {
    const w = win([1, 2, 3]);
    expect(bucketWindows(w, 40)).toBe(w);
  });

  it('reduces a long run to the requested number of points', () => {
    const w = win(Array.from({ length: 500 }, (_, i) => 100 + (i % 7)));
    expect(bucketWindows(w, 40)).toHaveLength(40);
  });

  it('uses the median so one descheduled pass cannot pull the line down', () => {
    // The real shape of the noise: passes read LOW when the scheduler takes the
    // thread, never spuriously high. A mean would follow those dropouts and
    // draw a decline that did not happen.
    const w = win([100, 100, 100, 2, 100, 100, 100, 100, 100, 3]);
    const [b] = bucketWindows(w, 1);
    expect(b.throughput).toBe(100);
  });

  it('keeps a real decline visible after bucketing', () => {
    const raw = Array.from({ length: 400 }, (_, i) => 100 - i * 0.1);
    const b = bucketWindows(win(raw), 20);
    expect(b[0].throughput).toBeGreaterThan(b[b.length - 1].throughput + 30);
    expect(throttleVerdict(b)!.declined).toBe(true);
  });
});

describe('the verdict when the headline fault has no number on it', () => {
  it('does not attribute the other findings\' percentage to it', () => {
    // The real output this guards against: "No graphics card is being used at
    // all ... Fixing what is listed here is worth roughly 4%". Rendering on the
    // CPU instead of a discrete card is not a 4% problem, and understating a
    // total loss is worse than quoting no figure at all.
    const critical: Finding = {
      id: 'x', component: 'GPU', severity: 'critical', title: 'No graphics card is being used at all',
      evidence: '', impact: '', remedy: null, measured: true,
    };
    const minor: Finding = {
      id: 'y', component: 'Memory', severity: 'minor', title: 'Memory is slow',
      evidence: '', impact: '', remedy: null, measured: false, estimatedGainPct: 0.04,
    };
    const v = deriveVerdict([critical, minor], [], []);
    expect(v).toMatch(/not quantified here/i);
    expect(v).toMatch(/they are not the problem/i);
  });

  it('still quotes the figure when the headline fault does carry one', () => {
    const critical: Finding = {
      id: 'x', component: 'GPU', severity: 'critical', title: 'Wrong adapter',
      evidence: '', impact: '', remedy: null, measured: true, estimatedGainPct: 1.5,
    };
    expect(deriveVerdict([critical], [], [])).toMatch(/worth roughly \d+%/);
  });
});
