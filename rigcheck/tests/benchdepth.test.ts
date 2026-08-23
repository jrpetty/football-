import { describe, expect, it } from 'vitest';
import {
  balanceVerdict, benchDrift, benchSnapshot, cacheReading, coreInference, corroborateCpu,
  percentile, percentilesOf, stabilityVerdict, steadinessVerdict, throttleVerdict, workloadSummary,
  type BenchResult, type CpuProbe, type GpuWorkload, type LatencyPoint, type ScalingPoint,
} from '../src/core/browserbench.ts';

/* --------------------------------------------------------------- helpers -- */

const cpu = (over: Partial<CpuProbe> = {}): CpuProbe => ({
  singleThread: 4.3e8,
  multiThread: 1.2e9,
  reportedThreads: 4,
  workersUsed: 4,
  windows: [],
  ...over,
});

/**
 * A scaling ladder for a part with `cores` cores and `threadsPerCore` per core.
 *
 * **This is a model, not data.** The 0.97 and 0.25 below are how thread sharing
 * is expected to behave, written here from the same reasoning that set the
 * thresholds being tested — so these cases check that the arithmetic finds the
 * knee in a curve of the expected shape. They do not check that real hardware
 * produces that shape. No machine with a known topology has been available to
 * run this against; the one that has is a four-vCPU container whose curve is
 * ambiguous, and the case below named "abstains rather than halving the core
 * count" is built from its real numbers.
 */
function ladderFor(cores: number, threadsPerCore: 1 | 2, perCore = 1e8): ScalingPoint[] {
  const total = cores * threadsPerCore;
  const widths = [...new Set([1, 2, 4, 8, 16, Math.ceil(total / 2), total])]
    .filter((t) => t >= 1 && t <= total)
    .sort((a, b) => a - b);
  return widths.map((threads) => {
    // Up to the core count every worker gets a core, losing a little to shared
    // cache. Past it, each extra worker is a second thread on a busy core and
    // brings only a quarter of one.
    const onCores = Math.min(threads, cores);
    const sharing = Math.max(0, threads - cores);
    return { threads, totalOps: perCore * (onCores * 0.97 + sharing * 0.25) };
  });
}

/* ----------------------------------------------------------- percentiles -- */

describe('percentiles', () => {
  it('takes the nearest rank, so p50 of 1..100 is the 50th value', () => {
    const xs = Array.from({ length: 100 }, (_, i) => i + 1);
    expect(percentile(xs, 0.5)).toBe(50);
    expect(percentile(xs, 0.99)).toBe(99);
    expect(percentile(xs, 1)).toBe(100);
    expect(percentile(xs, 0)).toBe(1);
  });

  it('refuses to compute a 99th percentile from too few samples', () => {
    // With 19 samples the "99th percentile" is just the maximum wearing a hat,
    // and reporting the maximum as a percentile invites reading one unlucky
    // pass as a property of the machine.
    expect(percentilesOf(Array.from({ length: 19 }, () => 10))).toBeNull();
    expect(percentilesOf(Array.from({ length: 20 }, () => 10))).not.toBeNull();
  });

  it('ignores zero and non-finite durations rather than counting them', () => {
    const xs = [...Array.from({ length: 30 }, () => 10), 0, NaN, -1, Infinity];
    expect(percentilesOf(xs)!.samples).toBe(30);
  });
});

describe('steadiness', () => {
  it('calls an even run even', () => {
    const v = steadinessVerdict(Array.from({ length: 200 }, (_, i) => 10 + (i % 5) * 0.1))!;
    expect(v.steady).toBe(true);
    expect(v.pct.worstRatio).toBeLessThan(1.2);
  });

  it('catches a tail that a mean would hide', () => {
    // 190 passes at 10ms and 10 at 400ms average 29.5ms — a threefold rise that
    // reads like severe throttling. The tail is the finding, and only a
    // percentile sees it.
    const passes = [...Array.from({ length: 190 }, () => 10), ...Array.from({ length: 10 }, () => 400)];
    const v = steadinessVerdict(passes)!;
    expect(v.steady).toBe(false);
    expect(v.pct.p50).toBe(10);
    expect(v.pct.worstRatio).toBeGreaterThan(10);
    expect(v.detail).toMatch(/contention/);
  });

  it('sees a tail exactly one percent long, which the 99th percentile cannot', () => {
    // The boundary case, and the reason the maximum is reported at all. Two
    // stalls in two hundred passes ARE the top one percent, so a nearest-rank
    // p99 lands on the value just below them and reads as perfectly even.
    const passes = [...Array.from({ length: 198 }, () => 10), 400, 400];
    const v = steadinessVerdict(passes)!;
    expect(v.pct.p99).toBe(10);
    expect(v.pct.worstRatio).toBe(1);
    expect(v.pct.max).toBe(400);
    expect(v.steady).toBe(false);
    expect(v.detail).toMatch(/40x the typical/);
  });

  it('does not fire on the 2.5x a headless software rasteriser produces at rest', () => {
    // Measured, not imagined: a quick run under SwiftShader in this container
    // with nothing else happening came out at p50 8.3ms, p99 21.1ms, 2.54x, and
    // the threshold was set above it so an idle machine is not told it has a
    // problem. The same run measures 1.4-1.7x since the pass loop stopped
    // yielding through a clamped timer — most of that 2.54 was the browser's
    // four-millisecond timeout floor, not the renderer — so the headroom is now
    // wider than it was designed to be. The old figure is kept as the fixture
    // because it is the harder case.
    const passes = [...Array.from({ length: 590 }, () => 8.3), ...Array.from({ length: 7 }, () => 21.1)];
    const v = steadinessVerdict(passes)!;
    expect(v.pct.worstRatio).toBeCloseTo(2.54, 1);
    expect(v.steady).toBe(true);
  });
});

/* ------------------------------------------------------------- topology -- */

describe('core inference', () => {
  it('reads two threads per core off the knee', () => {
    const v = coreInference(ladderFor(8, 2), 16)!;
    expect(v.shape).toBe('smt');
    expect(v.physicalCores).toBe(8);
    expect(v.detail).toMatch(/8 physical cores/);
  });

  it('handles a thread count that is not a power of two', () => {
    // 6 cores, 12 threads. A powers-of-two ladder samples 4 and 8 and never
    // asks about 6, which is the only width that could show the knee.
    const v = coreInference(ladderFor(6, 2), 12)!;
    expect(v.shape).toBe('smt');
    expect(v.physicalCores).toBe(6);
  });

  it('normalises each step by its width, so the widest step does not win by default', () => {
    // The bug this guards. On a 6-core part the ladder samples 6, 8 and 12: the
    // 8-to-12 step adds four sharing threads to the 6-to-8 step's two, so its
    // raw drop is larger even though the cores ran out at 6. Comparing the
    // steps per doubling puts the knee back where the hardware is.
    const points = ladderFor(6, 2);
    expect(points.map((p) => p.threads)).toContain(8);
    expect(coreInference(points, 12)!.knee).toBe(6);
  });

  it('does not invent thread sharing on a part that has none', () => {
    const v = coreInference(ladderFor(8, 1), 8)!;
    expect(v.shape).toBe('all-cores');
    expect(v.physicalCores).toBe(8);
  });

  it('abstains rather than halving the core count on a merely bandwidth-limited part', () => {
    // The failure this guards. Per-thread throughput here drifts to 73% at full
    // width — below the holding line — but each step keeps 84% of the last,
    // which is far more than a shared core delivers. An earlier version called
    // this "2 cores with two threads each" on a machine with four.
    const drifting: ScalingPoint[] = [
      { threads: 1, totalOps: 431_300_000 },
      { threads: 2, totalOps: 751_797_693 },
      { threads: 4, totalOps: 1_255_223_055 },
    ];
    const v = coreInference(drifting, 4)!;
    expect(v.shape).not.toBe('smt');
    expect(v.physicalCores).not.toBe(2);
  });

  it('needs a one-thread reference and three points before it says anything', () => {
    expect(coreInference([{ threads: 2, totalOps: 1 }, { threads: 4, totalOps: 2 }], 4)).toBeNull();
    expect(coreInference(ladderFor(8, 2).slice(0, 2), 16)).toBeNull();
  });
});

/* --------------------------------------------------------------- caches -- */

/** The sweep this container actually produced, kept as the shape to read. */
const REAL_CURVE: LatencyPoint[] = [
  { bytes: 8192, latencyNs: 2.05 },
  { bytes: 32768, latencyNs: 1.84 },
  { bytes: 131072, latencyNs: 3.58 },
  { bytes: 524288, latencyNs: 5.55 },
  { bytes: 2097152, latencyNs: 15.91 },
  { bytes: 8388608, latencyNs: 112.5 },
  { bytes: 16777216, latencyNs: 146.0 },
];

describe('cache reading', () => {
  it('finds the steps in a real sweep and puts the last level below the last step', () => {
    const c = cacheReading(REAL_CURVE)!;
    expect(c.steps.map((s) => s.bytes)).toEqual([131072, 524288, 2097152, 8388608]);
    // The last working set that still FITTED is the one below the final step,
    // not the one at it.
    expect(c.lastLevelBytes).toBe(4 * 1024 * 1024);
    expect(c.slowestNs).toBe(146);
    expect(c.ratio).toBeGreaterThan(50);
  });

  it('says so when there is no staircase rather than inventing one', () => {
    const flat = REAL_CURVE.map((p, i) => ({ bytes: p.bytes, latencyNs: 10 + i }));
    const c = cacheReading(flat)!;
    expect(c.steps).toHaveLength(0);
    expect(c.lastLevelBytes).toBeNull();
    expect(c.detail).toMatch(/no clear step/);
  });

  it('needs five points before it reads anything', () => {
    expect(cacheReading(REAL_CURVE.slice(0, 4))).toBeNull();
  });

  it('is unmoved by the order the points arrive in', () => {
    const shuffled = [...REAL_CURVE].reverse();
    expect(cacheReading(shuffled)!.lastLevelBytes).toBe(cacheReading(REAL_CURVE)!.lastLevelBytes);
  });
});

/* ------------------------------------------------------------ stability -- */

describe('stability', () => {
  it('passes a quiet machine and fails a busy one', () => {
    expect(stabilityVerdict(0.016)!.reliable).toBe(true);
    expect(stabilityVerdict(0.18)!.reliable).toBe(false);
    expect(stabilityVerdict(undefined)).toBeNull();
  });

  it('widens the throttling bar to twice the measured scatter', () => {
    // A 12% decline is a finding on a machine that repeats to 1%, and noise on
    // one that repeats to 9%. The same windows, two answers, both right.
    const windows = [100, 100, 100, 100, 94, 92, 90, 88].map((t, i) => ({ atMs: i * 1000, throughput: t }));
    expect(throttleVerdict(windows, 0.01)!.declined).toBe(true);
    expect(throttleVerdict(windows, 0.09)!.declined).toBe(false);
  });

  it('never lowers the bar below 8% however quiet the machine is', () => {
    const windows = [100, 100, 100, 100, 96, 96, 95, 95].map((t, i) => ({ atMs: i * 1000, throughput: t }));
    expect(throttleVerdict(windows, 0)!.declined).toBe(false);
  });
});

/* -------------------------------------------------------------- balance -- */

const wl = (alu: number, bandwidth: number): GpuWorkload[] => [
  { kind: 'alu', peak: alu, unit: 'shader operations/s', passMs: [], workPerPass: 1 },
  { kind: 'bandwidth', peak: bandwidth, unit: 'bytes/s', passMs: [], workPerPass: 1 },
];

describe('workload balance', () => {
  it('says nothing at all without a previous run to compare against', () => {
    expect(balanceVerdict(wl(1e11, 3e11), undefined)).toBeNull();
  });

  it('catches memory falling away from arithmetic', () => {
    const v = balanceVerdict(wl(1e11, 1.5e11), { alu: 1e11, bandwidth: 3e11 })!;
    expect(v.shifted).toBe(true);
    expect(v.detail).toMatch(/memory clock/i);
  });

  it('is unmoved when both halves scale together', () => {
    // Both figures down 30% — a machine on battery, say. The BALANCE has not
    // changed, and this check is only about the balance.
    const v = balanceVerdict(wl(0.7e11, 2.1e11), { alu: 1e11, bandwidth: 3e11 })!;
    expect(v.shifted).toBe(false);
    expect(v.change).toBeCloseTo(1, 5);
  });

  it('describes the workloads without scoring them', () => {
    const rows = workloadSummary(wl(2.4e11, 3.1e11));
    expect(rows.map((r) => r.label)).toEqual(['arithmetic', 'memory reads']);
    expect(rows[0].value).toMatch(/^\d/);
    // No verdict words anywhere: there is no reference to judge these against.
    expect(rows.map((r) => r.value).join(' ')).not.toMatch(/good|bad|slow|fast|expected/i);
  });
});

/* -------------------------------------------------------- corroboration -- */

describe('corroborating the selected processor', () => {
  const probe = cpu({ reportedThreads: 16, scaling: ladderFor(8, 2), latencyCurve: REAL_CURVE });

  it('agrees with the part that matches', () => {
    const c = corroborateCpu(probe, { fullName: 'Test 8C16T', cores: 8, threads: 16, l3CacheMB: 4 })!;
    expect(c.conflicts).toBe(false);
    expect(c.checks.every((x) => x.agrees === true)).toBe(true);
  });

  it('flags a part with the wrong thread count', () => {
    const c = corroborateCpu(probe, { fullName: 'Test 6C12T', cores: 6, threads: 12, l3CacheMB: 4 })!;
    expect(c.conflicts).toBe(true);
    expect(c.checks.find((x) => x.label === 'thread count')!.agrees).toBe(false);
  });

  it('abstains on core count for a hybrid part instead of failing it', () => {
    const c = corroborateCpu(probe, { fullName: 'Test hybrid', cores: 12, threads: 16, l3CacheMB: 4, hybrid: true })!;
    expect(c.checks.find((x) => x.label === 'physical cores')!.agrees).toBeNull();
  });

  it('accepts a cache size within the power-of-two sampling, and not beyond it', () => {
    const near = corroborateCpu(probe, { fullName: 'a', cores: 8, threads: 16, l3CacheMB: 8 })!;
    expect(near.checks.find((x) => x.label === 'cache size')!.agrees).toBe(true);
    const far = corroborateCpu(probe, { fullName: 'b', cores: 8, threads: 16, l3CacheMB: 96 })!;
    expect(far.checks.find((x) => x.label === 'cache size')!.agrees).toBe(false);
  });

  it('skips a check it has no data for rather than counting it against the part', () => {
    const bare = cpu({ reportedThreads: 16 });
    const c = corroborateCpu(bare, { fullName: 'a', cores: 8, threads: 16, l3CacheMB: 32 })!;
    expect(c.checks).toHaveLength(1);
    expect(c.conflicts).toBe(false);
  });
});

/* ------------------------------------------------------- run against run -- */

const runResult = (over: Partial<BenchResult> = {}): BenchResult => ({
  startedAt: '2026-08-20T00:00:00.000Z',
  durationS: 70,
  gpu: {
    renderer: 'NVIDIA GeForce RTX 3060',
    vendor: 'NVIDIA',
    api: 'webgl2',
    throughput: 2.4e11,
    windows: [100, 100, 100, 99, 98, 98, 97, 97].map((t, i) => ({ atMs: i * 1000, throughput: t })),
    workloads: [
      { kind: 'alu', peak: 2.4e11, unit: 'shader operations/s', passMs: [], workPerPass: 1 },
      { kind: 'bandwidth', peak: 3.1e11, unit: 'bytes/s', passMs: [], workPerPass: 1 },
      { kind: 'fill', peak: 8.8e9, unit: 'pixels/s', passMs: [], workPerPass: 1 },
    ],
  },
  cpu: cpu({ variation: 0.02, latencyCurve: REAL_CURVE }),
  memory: null,
  notes: [],
  interrupted: false,
  ...over,
});

describe('the session snapshot', () => {
  it('keeps the figures a later run needs and throws away the pass timings', () => {
    const snap = benchSnapshot(runResult());
    expect(snap.alu).toBe(2.4e11);
    expect(snap.bandwidth).toBe(3.1e11);
    expect(snap.fill).toBe(8.8e9);
    expect(snap.dramNs).toBe(146);
    expect(snap.gpuRetained).toBeGreaterThan(0.9);
    // Small enough that a year of monthly checks does not fill localStorage.
    expect(JSON.stringify(snap).length).toBeLessThan(400);
  });

  it('survives a run where nothing could be measured', () => {
    const snap = benchSnapshot(runResult({ gpu: null, cpu: null }));
    expect(snap.alu).toBeUndefined();
    expect(snap.at).toBe('2026-08-20T00:00:00.000Z');
  });
});

describe('drift between two runs', () => {
  const before = benchSnapshot(runResult());

  it('says nothing moved when nothing moved', () => {
    const rows = benchDrift(before, before);
    expect(rows.length).toBeGreaterThan(3);
    expect(rows.some((r) => r.moved)).toBe(false);
  });

  it('flags a fall larger than the noise floor', () => {
    const now = { ...before, alu: before.alu! * 0.7 };
    const row = benchDrift(now, before).find((r) => r.label === 'graphics arithmetic')!;
    expect(row.moved).toBe(true);
    expect(row.change).toBeCloseTo(0.7, 5);
  });

  it('widens the bar to cover whichever run was noisier', () => {
    // A 15% move is real against two clean runs and inside the noise when one
    // of them scattered by 9%. Comparing a careful measurement with a scrappy
    // one can only ever be as precise as the scrappy one was.
    const now = { ...before, alu: before.alu! * 0.85 };
    expect(benchDrift(now, before).find((r) => r.label === 'graphics arithmetic')!.moved).toBe(true);
    const scrappy = { ...before, variation: 0.09 };
    expect(benchDrift(now, scrappy).find((r) => r.label === 'graphics arithmetic')!.moved).toBe(false);
  });

  it('knows latency is the one where lower is better', () => {
    const rows = benchDrift(before, before);
    expect(rows.find((r) => r.label === 'main memory latency')!.higherIsBetter).toBe(false);
    expect(rows.find((r) => r.label === 'graphics arithmetic')!.higherIsBetter).toBe(true);
  });

  it('skips a figure that only one of the two runs has', () => {
    const partial = { ...before, fill: undefined };
    expect(benchDrift(before, partial).map((r) => r.label)).not.toContain('graphics fill rate');
  });
});

describe('the indefinite article', () => {
  it('writes "an AMD" and "an Intel", not "a AMD"', () => {
    const probe = cpu({ reportedThreads: 16, scaling: ladderFor(8, 2) });
    const amd = corroborateCpu(probe, { fullName: 'AMD Ryzen 5 5600X', cores: 6, threads: 12 })!;
    expect(amd.checks[0].detail).toContain('an AMD Ryzen 5 5600X');
    expect(amd.checks[0].detail).not.toContain('a AMD');
    const intel = corroborateCpu(probe, { fullName: 'Intel Core i5-12400F', cores: 6, threads: 12 })!;
    expect(intel.checks[0].detail).toContain('an Intel');
  });
});
