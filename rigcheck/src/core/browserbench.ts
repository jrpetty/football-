/**
 * Reading an in-browser benchmark honestly.
 *
 * The PowerShell harness is the accurate path and stays the accurate path.
 * But it asks someone to open a terminal, get an execution policy right, and
 * find a file — and the first thing that happens in practice is the command
 * fails and the check never gets run. So there is now a second path that runs
 * in the page itself, and this module decides what its numbers are allowed to
 * claim.
 *
 * ## What a browser benchmark genuinely cannot do
 *
 * It cannot tell you a game's frame rate. A fragment shader in a sandboxed
 * canvas and a game engine share almost nothing: no draw-call pattern, no
 * geometry, no VRAM pressure, no CPU-side simulation, no driver fast paths.
 * Numbers here are NEVER converted into fps, and nothing in this file returns
 * a Measurement — that type is reserved for real game captures.
 *
 * It also cannot say "your GPU scores 4200 and should score 5000", because no
 * measured browser-benchmark corpus exists for any card in this catalogue.
 * Inventing thresholds would be the same error the whole project exists to
 * avoid.
 *
 * ## What it genuinely can do
 *
 * Four things, none of which need an absolute calibration:
 *
 *  1. **Say which GPU is actually rendering.** The unmasked renderer string is
 *     the hardware the driver handed the browser. On a laptop that is very
 *     often the integrated chip while the discrete card idles — the single
 *     most common invisible fault there is, and a spec sheet cannot see it.
 *  2. **Catch software rendering.** SwiftShader or llvmpipe means no GPU
 *     acceleration at all, which is a certain finding, not an estimate.
 *  3. **Catch thermal decline.** Hold a load for a minute and compare the
 *     first quarter's throughput with the last quarter's. That is the machine
 *     measured against itself, so it needs no reference at all.
 *  4. **Catch a machine not using its cores.** Measured parallel speedup
 *     against the core count the browser reports finds parked cores and power
 *     plans set to throttle, again with no external reference.
 *
 *  5. **Read the machine's own shape.** The cache hierarchy shows up as a
 *     staircase in a pointer-chase sweep, and the core topology shows up as a
 *     knee in the thread-scaling curve. Both are facts about the machine, and
 *     both can be checked against the part somebody selected in the form —
 *     which is how you find out you are reading an estimate for a part you do
 *     not own.
 *
 * ## What was tried and removed
 *
 * There was a sixth use here: comparing the measured GPU-to-CPU ratio against
 * the ratio the catalogue implies. It has been taken out rather than softened.
 * Both measured figures are in units this benchmark invented, so their ratio
 * differs from the catalogue's by an unknown constant — and treating that
 * constant as 1 does not produce a weak finding, it produces one that fires on
 * every machine with a working graphics card. It survived review as "weak
 * evidence, deliberately labelled as such" because it had never been run
 * against hardware that has a GPU.
 *
 * Everything that replaced it compares the machine either with itself over
 * time, where the constant cancels, or with a fact about the part it is
 * supposed to be, where there is no constant.
 */

import type { Finding } from './health.ts';

/** One timed window inside a sustained run, used to see decline over time. */
export interface BenchWindow {
  /** Milliseconds from the start of the sustained phase. */
  atMs: number;
  /** Throughput in this window, in the test's own units. */
  throughput: number;
}

/**
 * One of the three graphics workloads.
 *
 * A single shader measures a single thing. The three here load different parts
 * of the card on purpose — arithmetic, memory, and the raster back end — and
 * the useful reading is the SHAPE across them, because that shape is a
 * property of the card rather than of the units, which are arbitrary.
 */
export interface GpuWorkload {
  /**
   * `alu`       — a serial transcendental loop: shader arithmetic, nothing else.
   * `bandwidth` — scattered reads from a texture far larger than any GPU cache.
   * `fill`      — overlapping blended full-screen passes: raster back end and
   *               framebuffer write bandwidth.
   */
  kind: 'alu' | 'bandwidth' | 'fill';
  /** Peak rate, in this workload's own units. Comparable only within a kind. */
  peak: number;
  /** What `peak` counts, for display. */
  unit: string;
  /** Every timed pass, in order, in milliseconds. Feeds the percentile read. */
  passMs: number[];
  /** Work completed per pass, in `unit`s — constant within a workload. */
  workPerPass: number;
}

/** What `navigator.gpu` said about the adapter, when the browser has it. */
export interface AdapterInfo {
  vendor: string;
  architecture: string;
  device: string;
  description: string;
  /** True when the adapter reports itself as a software fallback. */
  fallback: boolean;
  /** A few limits worth recording — they scale with the class of the part. */
  maxBufferSizeMB?: number;
  maxComputeInvocations?: number;
}

export interface GpuProbe {
  /** Unmasked renderer string, verbatim. The most useful field here. */
  renderer: string;
  vendor: string;
  /** 'webgpu' | 'webgl2' | 'webgl' — what actually ran. */
  api: string;
  /** Shader invocations per second, auto-ranged. Arbitrary units. */
  throughput: number;
  /** Sustained-load windows, earliest first. Empty if the run was skipped. */
  windows: BenchWindow[];
  /** Maximum texture size and similar, for the record. */
  maxTextureSize?: number;
  /** The three workloads, when they ran. `throughput` above is the ALU peak. */
  workloads?: GpuWorkload[];
  /**
   * Every timed pass of the sustained ALU phase, in order.
   *
   * Separate from `windows`, which are already reduced. The raw list is what
   * the percentile read needs: the interesting figure is the worst 1% of
   * passes, and a reduction has by definition thrown those away.
   */
  passMs?: number[];
  /** WebGPU adapter identity, when the browser exposes one. */
  adapter?: AdapterInfo | null;
}

/** Total throughput with a given number of workers running at once. */
export interface ScalingPoint {
  threads: number;
  /** Summed operations per second across all workers at this width. */
  totalOps: number;
}

/** One point on the pointer-chase latency sweep. */
export interface LatencyPoint {
  /** Working-set size in bytes. */
  bytes: number;
  /** Nanoseconds per dependent load at this size. */
  latencyNs: number;
}

export interface CpuProbe {
  /** Operations per second on one worker. Arbitrary units. */
  singleThread: number;
  /** Operations per second across all workers. Same units. */
  multiThread: number;
  /** navigator.hardwareConcurrency — logical processors the browser can see. */
  reportedThreads: number;
  /** Workers actually launched. */
  workersUsed: number;
  windows: BenchWindow[];
  /**
   * Throughput at 1, 2, 4, ... threads.
   *
   * Two points cannot tell you where scaling stopped, only that it did. The
   * curve can: the width at which per-thread throughput starts falling is the
   * point where threads stop landing on cores of their own.
   */
  scaling?: ScalingPoint[];
  /**
   * Latency against working-set size, smallest first.
   *
   * A dependent-load chain through a randomly permuted array cannot be
   * prefetched, so each step costs a full trip to whichever level of cache
   * holds it. Plotted against size, the cache hierarchy is the staircase.
   */
  latencyCurve?: LatencyPoint[];
  /**
   * Coefficient of variation across repeated single-thread measurements.
   *
   * The honest gate on everything else: a machine that measures differently
   * each time it is asked cannot support a finding that depends on a few
   * percent, and this is the number that says so.
   */
  variation?: number;
}

export interface MemoryProbe {
  /** Sequential copy bandwidth in GB/s, as measured through a typed array. */
  copyGBs: number;
  /** Strided read bandwidth in GB/s — much lower, and the interesting one. */
  stridedGBs: number;
}

export interface BenchResult {
  startedAt: string;
  /** Total wall-clock of the whole run, in seconds. */
  durationS: number;
  gpu: GpuProbe | null;
  cpu: CpuProbe | null;
  memory: MemoryProbe | null;
  /** Anything the harness had to skip or could not read. */
  notes: string[];
  /** Whether the page was hidden at any point — invalidates timing. */
  interrupted: boolean;
}

/* ------------------------------------------------------------------ marks -- */

/**
 * Renderer strings that mean "no GPU is involved". These are exact software
 * rasterisers, not a guess from a name pattern — a card whose name merely
 * contains one of these words is not caught, which is the right way round.
 */
const SOFTWARE_RENDERERS = ['swiftshader', 'llvmpipe', 'softpipe', 'microsoft basic render', 'generic renderer'];

/** Integrated-graphics families, for telling a dGPU apart from an iGPU. */
const INTEGRATED_MARKS = [
  'intel(r) hd', 'intel(r) uhd', 'intel hd graphics', 'intel uhd', 'intel(r) iris', 'intel iris',
  'radeon(tm) graphics', 'radeon vega', 'amd radeon(tm) vega', 'gfx90c', 'gfx902', 'raphael',
  'microsoft basic', 'apple m',
];

const DISCRETE_MARKS = ['geforce', 'radeon rx', 'radeon pro', 'quadro', 'arc(tm) a', 'intel(r) arc'];

export function isSoftwareRenderer(renderer: string): boolean {
  const r = renderer.toLowerCase();
  return SOFTWARE_RENDERERS.some((m) => r.includes(m));
}

export function rendererClass(renderer: string): 'discrete' | 'integrated' | 'software' | 'unknown' {
  if (!renderer.trim()) return 'unknown';
  const r = renderer.toLowerCase();
  if (isSoftwareRenderer(r)) return 'software';
  // Discrete is checked FIRST: "Intel(R) Arc(TM) A770" contains "intel(r)" but
  // is a discrete card, and an iGPU pattern would otherwise claim it.
  if (DISCRETE_MARKS.some((m) => r.includes(m))) return 'discrete';
  if (INTEGRATED_MARKS.some((m) => r.includes(m))) return 'integrated';
  return 'unknown';
}

/* ------------------------------------------------------------- throttling -- */

export interface ThrottleVerdict {
  /** Throughput in the last quarter as a fraction of the first quarter. */
  retained: number;
  /** True only when the decline is larger than run-to-run noise. */
  declined: boolean;
  detail: string;
}

/**
 * Compare the start of a sustained run with its end.
 *
 * This is the one measurement here that needs no reference of any kind: the
 * machine is compared with itself, minutes apart, under the same load. A part
 * that cannot hold its clocks shows up as a falling line and nothing else
 * does.
 *
 * The 8% floor is deliberately not tight. Browser timing jitters, other
 * processes take the GPU, and boost algorithms move around by a few percent
 * on their own; calling a 3% dip "thermal throttling" would be inventing a
 * fault. Sustained clocks below boost clocks are also normal and expected —
 * what this looks for is a machine still sliding after it should have settled.
 *
 * `noise` raises that floor when the machine has told us it is noisy. The run
 * measures its own repeatability, and a machine whose repeat measurements
 * scatter by 10% cannot support an 8% finding — the decline has to clear twice
 * the observed scatter before it means anything. A fixed threshold would
 * happily report thermal throttling on a laptop that was merely busy.
 */
export function throttleVerdict(windows: BenchWindow[], noise = 0): ThrottleVerdict | null {
  if (windows.length < 4) return null;
  const q = Math.max(1, Math.floor(windows.length / 4));
  const mean = (xs: number[]) => xs.reduce((a, b) => a + b, 0) / xs.length;
  const first = mean(windows.slice(0, q).map((w) => w.throughput));
  const last = mean(windows.slice(-q).map((w) => w.throughput));
  if (!(first > 0)) return null;
  const retained = last / first;
  const dropPct = (1 - retained) * 100;
  const floor = Math.max(0.08, 2 * Math.max(0, noise));
  if (retained >= 1 - floor) {
    return {
      retained,
      declined: false,
      detail:
        `Throughput held within ${dropPct < 0 ? 'noise' : `${dropPct.toFixed(0)}%`} of its opening rate ` +
        `across ${(windows[windows.length - 1].atMs / 1000).toFixed(0)} seconds of sustained load. ` +
        `Some fall is normal — boost clocks are not meant to be held indefinitely — and this is inside it` +
        (floor > 0.081
          ? `, against a ${(floor * 100).toFixed(0)}% bar widened to cover this machine's own measurement scatter.`
          : `.`),
    };
  }
  return {
    retained,
    declined: true,
    detail:
      `Throughput fell ${dropPct.toFixed(0)}% between the first and last quarter of a ` +
      `${(windows[windows.length - 1].atMs / 1000).toFixed(0)}-second sustained load. A part that cannot ` +
      `hold its clocks under load behaves exactly like this, and the effect on a long gaming session is ` +
      `the same shape: fine for a few minutes, slower afterwards.`,
  };
}

/**
 * Reduce a run to a readable number of points, taking the MEDIAN of each
 * bucket.
 *
 * A sustained run produces hundreds of timed passes and plotting them raw
 * draws a solid block of spikes: the per-pass jitter is far larger than the
 * trend, so the one thing the chart exists to show is the one thing you cannot
 * see. Median rather than mean because the noise is one-sided — a pass
 * occasionally gets descheduled and reads low, and nothing makes a pass read
 * spuriously high — so a mean is dragged down by exactly the samples that say
 * least about the hardware.
 */
export function bucketWindows(windows: BenchWindow[], buckets = 40): BenchWindow[] {
  if (windows.length <= buckets) return windows;
  const size = windows.length / buckets;
  const out: BenchWindow[] = [];
  for (let i = 0; i < buckets; i++) {
    const slice = windows.slice(Math.floor(i * size), Math.max(Math.floor((i + 1) * size), Math.floor(i * size) + 1));
    if (!slice.length) continue;
    const sorted = slice.map((w) => w.throughput).sort((a, b) => a - b);
    const mid = Math.floor(sorted.length / 2);
    out.push({
      atMs: slice[Math.floor(slice.length / 2)].atMs,
      throughput: sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2,
    });
  }
  return out;
}

/* ----------------------------------------------------------- core scaling -- */

export interface ScalingVerdict {
  /** Measured multi-thread speedup over single-thread. */
  speedup: number;
  /** Speedup per available thread. 1.0 would be perfect linear scaling. */
  efficiency: number;
  healthy: boolean;
  detail: string;
}

/**
 * How much faster all the cores are than one of them.
 *
 * Perfect scaling is not the expectation and is not the bar. Hyper-threaded
 * and E-core siblings do not double throughput, shared cache and memory
 * bandwidth cap it further, and a browser's workers carry real overhead. A
 * healthy machine lands somewhere around half of linear; well under a third
 * means threads are not running at full speed, which on a real machine is
 * usually a power plan, parked cores, or an aggressive laptop profile.
 */
export function scalingVerdict(cpu: CpuProbe): ScalingVerdict | null {
  if (!(cpu.singleThread > 0) || !(cpu.multiThread > 0) || cpu.workersUsed < 2) return null;
  const speedup = cpu.multiThread / cpu.singleThread;
  const efficiency = speedup / cpu.workersUsed;
  const healthy = efficiency >= 0.33;
  return {
    speedup,
    efficiency,
    healthy,
    detail: healthy
      ? `${cpu.workersUsed} workers ran ${speedup.toFixed(1)}x one worker — ${(efficiency * 100).toFixed(0)}% ` +
        `of linear, which is the normal range once shared cache, memory bandwidth and thread siblings are ` +
        `accounted for.`
      : `${cpu.workersUsed} workers ran only ${speedup.toFixed(1)}x one worker — ${(efficiency * 100).toFixed(0)}% ` +
        `of linear, below the roughly 33% a healthy machine manages. Cores that are parked, a power plan ` +
        `capping multi-core clocks, or a laptop profile limiting sustained draw all produce this.`,
  };
}


/* ------------------------------------------------------------- steadiness -- */

/** Pass durations reduced to the numbers that describe their tail. */
export interface Percentiles {
  p50: number;
  p95: number;
  p99: number;
  /**
   * The single slowest pass.
   *
   * Reported alongside the percentiles rather than instead of them, because a
   * tail that is exactly one percent long hides underneath a nearest-rank p99:
   * two stalls in two hundred passes ARE the top one percent, and the 99th
   * value is the one just below them. The percentile is the right statistic for
   * how often; the maximum is the only one that says how bad.
   */
  max: number;
  /** How much longer the worst 1% of passes took than the typical one. */
  worstRatio: number;
  samples: number;
}

/** Nearest-rank percentile. `q` is 0..1. */
export function percentile(sorted: number[], q: number): number {
  if (!sorted.length) return 0;
  const i = Math.min(sorted.length - 1, Math.max(0, Math.ceil(q * sorted.length) - 1));
  return sorted[i];
}

export function percentilesOf(values: number[]): Percentiles | null {
  const clean = values.filter((v) => Number.isFinite(v) && v > 0).sort((a, b) => a - b);
  if (clean.length < 20) return null;
  const p50 = percentile(clean, 0.5);
  const p95 = percentile(clean, 0.95);
  const p99 = percentile(clean, 0.99);
  const max = clean[clean.length - 1];
  return { p50, p95, p99, max, worstRatio: p50 > 0 ? p99 / p50 : 0, samples: clean.length };
}

export interface SteadinessVerdict {
  pct: Percentiles;
  steady: boolean;
  detail: string;
}

/**
 * How evenly identical work was delivered.
 *
 * Every pass submits exactly the same shader over exactly the same pixels, so
 * in the absence of interference every pass should take the same time. It never
 * quite does — the compositor takes the GPU, the driver schedules something
 * else — and the size of that unevenness is worth knowing, because it is the
 * same mechanism that produces stutter in a game.
 *
 * The claim is kept narrow on purpose. This measures the delivery of a fixed
 * workload inside a browser tab; it does not measure frame pacing, and it is
 * never described as if it did. A machine that is simply busy with something
 * else will look uneven here and be perfectly smooth once that finishes, which
 * is why the finding it produces says "while this ran" and stays at `minor`.
 */
export function steadinessVerdict(passMs: number[]): SteadinessVerdict | null {
  const pct = percentilesOf(passMs);
  if (!pct) return null;
  // 3x before it counts. A stall of that size once in a hundred passes is
  // beyond what an idle machine produces, and anything tighter reads the
  // browser's own scheduler rather than the hardware — a headless software
  // rasteriser under no load at all measures 2.5x, which is the calibration
  // this threshold is set against.
  // The maximum counts too, at a higher bar. A handful of very large stalls in
  // several hundred passes is under one percent by definition, so the
  // percentile cannot see them however bad they are — and "one pass in three
  // hundred took forty times as long" is worth saying.
  const spike = pct.p50 > 0 ? pct.max / pct.p50 : 0;
  const steady = pct.worstRatio <= 3 && spike <= 12;
  const worst = `${pct.p50.toFixed(1)}ms typical, ${pct.p99.toFixed(1)}ms at the 99th, ${pct.max.toFixed(1)}ms at worst`;
  return {
    pct,
    steady,
    detail: steady
      ? `Across ${pct.samples} identical passes the slowest 1% took ${pct.worstRatio.toFixed(2)}x the typical ` +
        `pass — even delivery, with no sign of anything else fighting for the card.`
      : pct.worstRatio > 3
        ? `Across ${pct.samples} identical passes the slowest 1% took ${pct.worstRatio.toFixed(1)}x the typical ` +
          `pass (${worst}). Identical work taking several times as long at intervals is what contention looks ` +
          `like from inside a browser tab.`
        : `Across ${pct.samples} identical passes, delivery was even apart from occasional stalls of up to ` +
          `${spike.toFixed(0)}x the typical pass (${worst}). Too rare to move the percentiles and too large ` +
          `to be the hardware doing its job.`,
  };
}

/* --------------------------------------------------------- core topology -- */

export interface CoreInference {
  /** The widest run at which per-thread throughput was still holding up. */
  knee: number;
  /** What the browser said the thread count was. */
  reported: number;
  /**
   * `smt`        — the knee sits at half the thread count: two threads per core.
   * `all-cores`  — throughput held all the way out: every thread had a core.
   * `unclear`    — the curve does not have a clean knee to read.
   */
  shape: 'smt' | 'all-cores' | 'unclear';
  /** Physical cores, only when the shape supports naming a number. */
  physicalCores: number | null;
  detail: string;
}

/**
 * Read the thread-scaling curve for the machine's core topology.
 *
 * Two points — one thread and all threads — can tell you that scaling stopped
 * but not where. The curve can, and where it stops is a fact about the machine:
 * per-thread throughput holds while every worker has a core to itself and falls
 * once workers start sharing one. On a part with two threads per core the knee
 * lands at half the reported thread count, and reporting "8 cores, 16 threads"
 * from a measurement rather than from a lookup is exactly the kind of thing
 * this can honestly do.
 *
 * Perfect scaling does not happen even on genuinely separate cores — shared L3,
 * memory bandwidth and the browser's own scheduling all cost something — so the
 * bar for "still holding" is 72%, and a machine has to lose considerably more
 * than that at a single step before this is willing to call it thread sharing.
 *
 * **On the two thresholds below.** They are reasoned from how thread sharing is
 * expected to behave — a second thread on a busy core adding roughly a quarter
 * of a core rather than a whole one — and they have never been checked against a
 * machine whose topology is known. The only real hardware this has run on is a
 * four-vCPU container, whose curve is genuinely ambiguous and which this
 * correctly declines to classify. The tests exercise ladders generated from that
 * same expected behaviour, which makes them a check on the arithmetic and not on
 * the premise.
 *
 * That is why the knee itself and the inference from it are kept apart
 * everywhere they surface. The knee is measured: per-thread throughput held to
 * here and fell after, which is true whatever the cause. "That is eight cores
 * running two threads each" is an inference from the shape, and it is worded as
 * one — and `corroborateCpu` treats a disagreement on core count as the weakest
 * of its three checks for the same reason.
 */

/** Per-thread throughput, relative to one thread, that still counts as holding. */
const HOLD = 0.72;

/**
 * The most a step can retain and still look like threads sharing a core.
 *
 * Two threads on one core typically deliver about 1.25x that core's single
 * thread, so per-thread throughput lands near 62%. Genuinely separate cores
 * hold nearer 90%. 72% sits in the gap with room either side.
 */
const SHARED_STEP = 0.72;
export function coreInference(scaling: ScalingPoint[], reported: number): CoreInference | null {
  const pts = scaling
    .filter((p) => p.threads > 0 && Number.isFinite(p.totalOps) && p.totalOps > 0)
    .sort((a, b) => a.threads - b.threads);
  if (pts.length < 3 || pts[0].threads !== 1) return null;
  const base = pts[0].totalOps;
  const efficiency = (p: ScalingPoint) => p.totalOps / p.threads / base;
  const widest = pts[pts.length - 1].threads;

  // Nothing fell away at all: every worker had a core to itself, all the way
  // out. Checked first because a part with no thread sharing has no knee to
  // find, and looking for one anyway would find noise.
  if (efficiency(pts[pts.length - 1]) >= HOLD) {
    return {
      knee: widest,
      reported,
      shape: 'all-cores',
      physicalCores: widest,
      detail:
        `Per-thread throughput held all the way out to ${widest} workers, so every worker had a core to ` +
        `itself. The ${reported} logical processors the browser reports look like ${widest} real cores ` +
        `rather than threads sharing them.`,
    };
  }

  // Otherwise find the step that cost the most, normalised PER DOUBLING of
  // width. Without that normalisation the widest step usually wins simply for
  // being the widest: on a 6-core part sampled at 6, 8 and 12, the 8-to-12 step
  // adds four sharing threads against the 6-to-8 step's two, and the larger raw
  // drop puts the knee at 8 on a machine whose cores ran out at 6.
  let worst = { at: pts[0].threads, retained: 1 };
  for (let i = 1; i < pts.length; i++) {
    const ratio = efficiency(pts[i]) / efficiency(pts[i - 1]);
    const doublings = Math.log2(pts[i].threads / pts[i - 1].threads);
    if (!(doublings > 0)) continue;
    const perDoubling = Math.pow(ratio, 1 / doublings);
    if (perDoubling < worst.retained) worst = { at: pts[i - 1].threads, retained: perDoubling };
  }

  const knee = worst.at;
  const looksHalf = reported >= 4 && knee >= 2 && Math.abs(knee - reported / 2) <= Math.max(1, reported * 0.12);
  if (worst.retained <= SHARED_STEP && looksHalf) {
    return {
      knee,
      reported,
      shape: 'smt',
      physicalCores: knee,
      detail:
        `Per-thread throughput held out to ${knee} workers and then fell ` +
        `${((1 - worst.retained) * 100).toFixed(0)}% across the next doubling, while the browser reports ` +
        `${reported} logical processors. The measured part is the fall itself. The likeliest reading of it ` +
        `is ${knee} physical cores running two threads each, since a second thread on a busy core adds ` +
        `throughput but not a whole core's worth — though a part that simply runs out of memory bandwidth ` +
        `at this width would look similar.`,
    };
  }

  return {
    knee,
    reported,
    shape: 'unclear',
    physicalCores: null,
    detail:
      `Per-thread throughput started falling at ${knee} workers out of ${reported}, losing ` +
      `${((1 - worst.retained) * 100).toFixed(0)}% per doubling beyond it. That matches neither a plain core ` +
      `count nor two threads per core. A hybrid part with fast and slow cores does this, and so does a ` +
      `machine that was busy with something else while this ran.`,
  };
}

/* --------------------------------------------------------------- caches -- */

export interface CacheReading {
  /** Latency at the smallest working set — the first level of cache. */
  fastestNs: number;
  /** Latency at the largest working set — main memory. */
  slowestNs: number;
  /** How much slower main memory is than the first level. */
  ratio: number;
  /** Working-set sizes at which latency stepped up, with the sizes either side. */
  steps: { bytes: number; fromNs: number; toNs: number }[];
  /**
   * The largest working set that still fitted in cache, in bytes.
   *
   * Sampled at powers of two, so the true size is somewhere between this and
   * twice it. Treated as an order-of-magnitude reading and never as a number.
   */
  lastLevelBytes: number | null;
  detail: string;
}

/**
 * Read the cache hierarchy out of the pointer-chase sweep.
 *
 * A dependent chain of loads through a randomly permuted array cannot be
 * prefetched: each load's address is only known once the previous one has come
 * back, so every access pays the full cost of whichever level of memory holds
 * it. Sweeping the working set from a few kilobytes to tens of megabytes turns
 * the cache hierarchy into a staircase, and the steps are where one level stops
 * fitting.
 *
 * The absolute numbers carry the JavaScript interpreter's own overhead on top
 * of the hardware, so they read high — a level that costs the machine 1ns will
 * measure at two or three. The SHAPE is what this is for, and the shape
 * survives a constant offset.
 */
export function cacheReading(curve: LatencyPoint[]): CacheReading | null {
  const pts = curve
    .filter((p) => p.bytes > 0 && Number.isFinite(p.latencyNs) && p.latencyNs > 0)
    .sort((a, b) => a.bytes - b.bytes);
  if (pts.length < 5) return null;

  const steps: CacheReading['steps'] = [];
  for (let i = 1; i < pts.length; i++) {
    // 1.4x between adjacent samples. Within a level the curve is flat to a few
    // percent; crossing out of one costs at least half again, usually far more.
    if (pts[i].latencyNs / pts[i - 1].latencyNs >= 1.4) {
      steps.push({ bytes: pts[i].bytes, fromNs: pts[i - 1].latencyNs, toNs: pts[i].latencyNs });
    }
  }

  const fastestNs = pts[0].latencyNs;
  const slowestNs = pts[pts.length - 1].latencyNs;
  const last = steps[steps.length - 1];
  // The working set just BELOW the final step is the one that still fitted, so
  // that is the size being reported, not the size at which it stopped fitting.
  const lastLevelBytes = last ? last.bytes / 2 : null;

  const mb = (b: number) => (b >= 1024 * 1024 ? `${(b / 1024 / 1024).toFixed(0)}MB` : `${(b / 1024).toFixed(0)}KB`);

  return {
    fastestNs,
    slowestNs,
    ratio: fastestNs > 0 ? slowestNs / fastestNs : 0,
    steps,
    lastLevelBytes,
    detail: steps.length
      ? `Latency stepped up at ${steps.map((s) => mb(s.bytes)).join(', ')} — ${steps.length} boundaries between ` +
        `levels of cache. Beyond the last one, at about ${mb(lastLevelBytes ?? 0)}, every access goes to main ` +
        `memory at ${slowestNs.toFixed(1)}ns against ${fastestNs.toFixed(1)}ns when it fits in the first level.`
      : `Latency rose smoothly from ${fastestNs.toFixed(1)}ns to ${slowestNs.toFixed(1)}ns with no clear step, ` +
        `so the levels could not be separated. A machine busy with other work blurs the staircase like this.`,
  };
}

/* ------------------------------------------------------ repeatability -- */

export interface Stability {
  /** Coefficient of variation across the repeats, as a fraction. */
  variation: number;
  reliable: boolean;
  detail: string;
}

/**
 * Whether this run can support a finding at all.
 *
 * The single-thread measurement is taken more than once, and the scatter across
 * those repeats is the machine's own answer to "how precisely can you be
 * measured right now". It is the honest gate on everything else here: a machine
 * whose repeats differ by 12% cannot support an 8% conclusion, and reporting
 * one anyway would be reporting the noise.
 *
 * 5% is where a normally quiet machine lands. Above it something else is
 * running, or the platform is moving clocks around underneath the measurement.
 */
export function stabilityVerdict(variation: number | undefined): Stability | null {
  if (variation == null || !Number.isFinite(variation) || variation < 0) return null;
  const reliable = variation <= 0.05;
  return {
    variation,
    reliable,
    detail: reliable
      ? `Repeat measurements of the same workload agreed to within ${(variation * 100).toFixed(1)}%, so small ` +
        `differences below are worth reading.`
      : `Repeat measurements of the same workload disagreed by ${(variation * 100).toFixed(0)}%, which is more ` +
        `than the effects this is looking for. Something else on the machine was taking time while this ran. ` +
        `The thresholds below have been widened to match, and anything close to the line is being withheld ` +
        `rather than reported as a finding.`,
  };
}

/* ------------------------------------------------------ workload balance -- */

export interface BalanceVerdict {
  /** Memory throughput per unit of arithmetic throughput, in this run. */
  ratio: number;
  /** The same figure from an earlier run on this machine. */
  previousRatio: number;
  /** How far the balance has moved since. 1.0 is unchanged. */
  change: number;
  shifted: boolean;
  detail: string;
}

/**
 * Compare the card's memory-to-arithmetic balance with its OWN earlier balance.
 *
 * The obvious thing to do here is compare the measured ratio with the ratio the
 * catalogue implies, since every card has an fp32 figure and a bandwidth figure
 * on record. That comparison does not work, and it is worth being explicit
 * about why, because the same trap has been walked into once already in this
 * file.
 *
 * The two measured numbers are in units this benchmark invented: "shader
 * operations" means one iteration of a loop chosen here, and "bytes" means taps
 * of a texture sized here. Their ratio therefore differs from TFLOPS-over-GB/s
 * by some constant that depends entirely on how those two shaders were written
 * — a constant nobody has measured, because measuring it would need a corpus of
 * runs on known hardware, which does not exist for this catalogue. Comparing
 * the two ratios as though that constant were 1 does not produce a weak
 * finding; it produces a finding that fires on every machine, and it fires
 * hardest on the healthiest ones.
 *
 * What survives is the comparison against the same machine on another day. The
 * constant is the same on both sides and cancels exactly, the units never have
 * to mean anything, and the thing it catches — a memory clock that has stopped
 * boosting — is the thing the catalogue comparison was reaching for anyway.
 * It needs a previous run, so it says nothing on the first one, which is
 * honest: on the first run there is genuinely nothing to say.
 */
export function balanceVerdict(
  workloads: GpuWorkload[] | undefined,
  previous: { alu: number; bandwidth: number } | undefined,
): BalanceVerdict | null {
  if (!workloads || !previous) return null;
  const alu = workloads.find((w) => w.kind === 'alu')?.peak;
  const bw = workloads.find((w) => w.kind === 'bandwidth')?.peak;
  if (!alu || !bw || !(previous.alu > 0) || !(previous.bandwidth > 0)) return null;

  const ratio = bw / alu;
  const previousRatio = previous.bandwidth / previous.alu;
  if (!(previousRatio > 0)) return null;
  const change = ratio / previousRatio;
  // A third either way. Both figures are peaks over hundreds of passes, so
  // run-to-run movement is small; a shift this size is the card behaving
  // differently, not the measurement.
  const shifted = change < 0.75 || change > 1.33;

  return {
    ratio,
    previousRatio,
    change,
    shifted,
    detail: !shifted
      ? `Memory and arithmetic throughput are in the same balance as the last run on this machine, within ` +
        `${Math.abs(1 - change) * 100 < 1 ? 'a percent' : `${(Math.abs(1 - change) * 100).toFixed(0)}%`}.`
      : change < 1
        ? `Memory throughput has fallen ${((1 - change) * 100).toFixed(0)}% relative to arithmetic since the ` +
          `last run on this machine. Both are measured in this benchmark's own units, but the units cancel in ` +
          `the comparison — the card's two halves have moved apart. A memory clock that has stopped boosting ` +
          `does exactly this, and it is invisible to a test that only measures arithmetic.`
        : `Memory throughput has risen ${((change - 1) * 100).toFixed(0)}% relative to arithmetic since the ` +
          `last run on this machine. If nothing was changed deliberately, the earlier run is the suspect one: ` +
          `something was probably competing for the card while it was taken.`,
  };
}

/**
 * The three workload figures, described rather than judged.
 *
 * There is no reference to judge them against and there is not going to be one,
 * so this returns sentences that say what was measured and in what units, and
 * stops there. Reporting a number honestly is worth more than scoring it
 * dishonestly.
 */
export function workloadSummary(workloads: GpuWorkload[] | undefined): { kind: string; label: string; value: string }[] {
  if (!workloads) return [];
  const fmt = (n: number) => {
    if (!(n > 0)) return '—';
    const units = [[1e12, 'T'], [1e9, 'G'], [1e6, 'M'], [1e3, 'k']] as const;
    for (const [scale, suffix] of units) if (n >= scale) return `${(n / scale).toFixed(n / scale >= 100 ? 0 : 1)}${suffix}`;
    return n.toFixed(0);
  };
  const LABELS: Record<GpuWorkload['kind'], string> = {
    alu: 'arithmetic',
    bandwidth: 'memory reads',
    fill: 'pixels drawn',
  };
  return workloads.map((w) => ({
    kind: w.kind,
    label: LABELS[w.kind],
    value: `${fmt(w.peak)} ${w.unit.replace(/^bytes\/s$/, 'B/s').replace(/\/s$/, '/s')}`,
  }));
}

/**
 * "a" or "an", by the first letter.
 *
 * Nearly every part name in this catalogue begins with AMD, Intel or NVIDIA,
 * and all three take "an" — so a hardcoded "a" is wrong more often than it is
 * right. Letter-based rather than pronunciation-based, which is enough here:
 * the initialisms that would break it ("a Unified…") do not appear in part
 * names.
 */
function an(name: string): string {
  return /^[aeiou]/i.test(name.trim()) ? 'an' : 'a';
}

/* ------------------------------------------------------- corroboration -- */

/**
 * Whether the measurements agree with the part that was chosen in the form.
 *
 * This is the quiet, useful half of the whole exercise. Everything else looks
 * for faults; this looks for agreement, and reports where the machine and the
 * specification are telling different stories. Somebody who picked the wrong
 * entry out of four parts called "7700" finds out here rather than reading an
 * estimate for a part they do not own.
 *
 * Each check is independent and each is allowed to abstain. Nothing here fails
 * a part for one disagreement — thread counts are clamped by privacy modes,
 * cache steps are sampled at powers of two — so the verdict is the balance of
 * what could be checked, and a check that could not run says so instead of
 * counting against the part.
 */
export interface Corroboration {
  checks: { label: string; agrees: boolean | null; detail: string }[];
  /** True only when something checkable actually disagreed. */
  conflicts: boolean;
}

export function corroborateCpu(
  cpu: CpuProbe,
  record: { fullName: string; cores: number; threads: number; l3CacheMB?: number | null; hybrid?: boolean } | undefined,
): Corroboration | null {
  if (!record) return null;
  const checks: Corroboration['checks'] = [];

  if (cpu.reportedThreads > 0) {
    const agrees = cpu.reportedThreads === record.threads;
    checks.push({
      label: 'thread count',
      agrees,
      detail: agrees
        ? `The browser sees ${cpu.reportedThreads} logical processors, which is what ${an(record.fullName)} ${record.fullName} has.`
        : `The browser sees ${cpu.reportedThreads} logical processors; ${an(record.fullName)} ${record.fullName} has ${record.threads}. ` +
          `Privacy settings and virtual machines both clamp this figure, so it is evidence rather than proof — ` +
          `but if neither applies, the selected processor is not the one in this machine.`,
    });
  }

  const cores = cpu.scaling ? coreInference(cpu.scaling, cpu.reportedThreads) : null;
  if (cores?.physicalCores) {
    if (record.hybrid) {
      // A part with fast and slow cores bends the curve where the slow ones
      // join, which is not where the core count is. The measurement is real and
      // the comparison is not, so this abstains rather than counting a
      // disagreement it created itself.
      checks.push({
        label: 'physical cores',
        agrees: null,
        detail:
          `Not checked. ${an(record.fullName) === 'an' ? 'An' : 'A'} ${record.fullName} has two kinds of core, and the scaling curve bends where the ` +
          `slower ones join in rather than at the core count — so the knee at ${cores.physicalCores} workers ` +
          `says something real about this machine but nothing about whether the part is right.`,
      });
    } else {
      const agrees = cores.physicalCores === record.cores;
      checks.push({
        label: 'physical cores',
        agrees,
        detail: agrees
          ? `The scaling curve knees at ${cores.physicalCores} workers, matching the ${record.cores} cores ` +
            `${an(record.fullName)} ${record.fullName} has.`
          : `The scaling curve knees at ${cores.physicalCores} workers; ${an(record.fullName)} ${record.fullName} ` +
            `has ${record.cores} cores. The weakest of these checks: the knee is measured, but reading a core ` +
            `count off it assumes the fall is thread sharing rather than memory bandwidth running out, and ` +
            `that assumption has not been checked against a machine whose topology is known.`,
      });
    }
  }

  const cache = cpu.latencyCurve ? cacheReading(cpu.latencyCurve) : null;
  if (cache?.lastLevelBytes && record.l3CacheMB) {
    const measuredMB = cache.lastLevelBytes / 1024 / 1024;
    // Sampled at powers of two, so a factor of two either way is the resolution
    // of the measurement rather than a disagreement.
    const agrees = measuredMB >= record.l3CacheMB / 2.5 && measuredMB <= record.l3CacheMB * 2.5;
    checks.push({
      label: 'cache size',
      agrees,
      detail: agrees
        ? `The last cache step sits near ${measuredMB.toFixed(0)}MB, consistent with the ${record.l3CacheMB}MB ` +
          `last-level cache on ${an(record.fullName)} ${record.fullName}. Sampled at powers of two, so this confirms the order of ` +
          `magnitude and not the number.`
        : `The last cache step sits near ${measuredMB.toFixed(0)}MB; ${an(record.fullName)} ${record.fullName} has ${record.l3CacheMB}MB. ` +
          `That is further apart than the power-of-two sampling can explain.`,
    });
  }

  return { checks, conflicts: checks.some((c) => c.agrees === false) };
}

/* -------------------------------------------------------------- snapshot -- */

/**
 * A run, reduced to what is worth keeping between sessions.
 *
 * The full result carries every pass timing — several hundred numbers per
 * workload, and thousands on a thorough run. Sessions live in `localStorage`,
 * which is a few megabytes for the whole origin, so storing raw runs would fill
 * it and start losing history after a handful of checks.
 *
 * What survives is what a later run can actually compare itself against: the
 * peaks, the two retention figures, the noise floor and the main-memory
 * latency. Everything the comparison needs, at about two hundred bytes.
 */
export interface BenchSnapshot {
  at: string;
  alu?: number;
  bandwidth?: number;
  fill?: number;
  singleThread?: number;
  multiThread?: number;
  /** Fraction of opening throughput retained by the end of the sustained load. */
  gpuRetained?: number;
  cpuRetained?: number;
  variation?: number;
  /** Latency at the largest working set, in nanoseconds. */
  dramNs?: number;
}

export function benchSnapshot(result: BenchResult): BenchSnapshot {
  const peak = (kind: GpuWorkload['kind']) => result.gpu?.workloads?.find((w) => w.kind === kind)?.peak;
  const noise = result.cpu?.variation ?? 0;
  const curve = result.cpu?.latencyCurve;
  return {
    at: result.startedAt,
    alu: peak('alu'),
    bandwidth: peak('bandwidth'),
    fill: peak('fill'),
    singleThread: result.cpu?.singleThread,
    multiThread: result.cpu?.multiThread,
    gpuRetained: result.gpu ? (throttleVerdict(result.gpu.windows, noise)?.retained ?? undefined) : undefined,
    cpuRetained: result.cpu ? (throttleVerdict(result.cpu.windows, noise)?.retained ?? undefined) : undefined,
    variation: result.cpu?.variation,
    dramNs: curve?.length ? curve[curve.length - 1].latencyNs : undefined,
  };
}

/**
 * How this run compares with the last one on the same machine.
 *
 * Every figure here is in the same invented units on both sides, taken by the
 * same code on the same hardware, so the comparison is the one thing in this
 * module that is both quantitative and sound. It is also the only way a browser
 * benchmark can say "this got worse" — which is, in practice, the question
 * somebody has when they come back to run it a second time.
 *
 * The 10% bar is set by the noise floor the runs measured themselves: a machine
 * that repeats to 1-2% can support it comfortably, and one that cannot has
 * already said so through `stabilityVerdict`.
 */
export interface DriftRow {
  label: string;
  now: number;
  before: number;
  change: number;
  /** True when the move is larger than run-to-run noise. */
  moved: boolean;
  /** Whether a rise is good. False for latency, where lower is better. */
  higherIsBetter: boolean;
}

export function benchDrift(now: BenchSnapshot, before: BenchSnapshot): DriftRow[] {
  const FIELDS: [keyof BenchSnapshot, string, boolean][] = [
    ['alu', 'graphics arithmetic', true],
    ['bandwidth', 'graphics memory reads', true],
    ['fill', 'graphics fill rate', true],
    ['singleThread', 'one processor thread', true],
    ['multiThread', 'all processor threads', true],
    ['dramNs', 'main memory latency', false],
  ];
  const out: DriftRow[] = [];
  // The bar widens to cover whichever run was noisier: comparing a clean run
  // against a scrappy one can only be as precise as the scrappy one was.
  const bar = Math.max(0.1, 2 * Math.max(now.variation ?? 0, before.variation ?? 0));
  for (const [key, label, higherIsBetter] of FIELDS) {
    const a = now[key];
    const b = before[key];
    if (typeof a !== 'number' || typeof b !== 'number' || !(a > 0) || !(b > 0)) continue;
    const change = a / b;
    out.push({ label, now: a, before: b, change, moved: Math.abs(1 - change) > bar, higherIsBetter });
  }
  return out;
}

/* --------------------------------------------------------------- findings -- */

export interface BenchContext {
  /** Catalogue name of the GPU the operator says is in the machine. */
  expectedGpuName?: string;
  /** Catalogue id of that GPU. */
  expectedGpuId?: string;
  /**
   * Catalogue id the renderer string itself resolved to.
   *
   * Filled by the caller, which has the detector. When this and `expectedGpuId`
   * disagree, the machine and the form are describing different cards, and that
   * is a certain finding rather than an inference.
   */
  detectedGpuId?: string;
  /** Catalogue name for `detectedGpuId`. */
  detectedGpuName?: string;
  /** The CPU record the operator selected, for the corroboration checks. */
  expectedCpu?: { fullName: string; cores: number; threads: number; l3CacheMB?: number | null; hybrid?: boolean };
  /** The last run on this machine, for the comparisons that need no reference. */
  previous?: BenchSnapshot;
}

/**
 * Turn a run into findings for the health report.
 *
 * Every finding here is either certain (software rendering, the wrong adapter,
 * a card that is not the card in the form) or self-referential (decline over a
 * run, scaling against core count, this machine against itself last week).
 * Nothing is scored against a corpus, because no corpus exists.
 *
 * The whole set is gated on the run's own repeatability. A machine whose repeat
 * measurements scatter by 12% cannot support an 8% conclusion, so the
 * thresholds widen to match and anything that only just cleared the old bar is
 * withheld rather than reported.
 */
export function benchFindings(result: BenchResult, ctx: BenchContext = {}): Finding[] {
  const out: Finding[] = [];
  const { gpu, cpu, memory } = result;
  const noise = cpu?.variation ?? 0;

  if (result.interrupted) {
    out.push({
      id: 'bench-interrupted',
      component: 'Software',
      severity: 'unknown',
      title: 'The tab lost focus during the run, so the timings are not trustworthy',
      evidence:
        'Browsers throttle background tabs deliberately: timers slow down and animation frames stop. ' +
        'Any window measured while this tab was hidden reads low for that reason alone.',
      impact: 'Nothing below can be relied on. Thermal decline in particular will look worse than it is.',
      remedy: 'Run it again and leave this tab in the foreground for the whole run.',
      measured: false,
    });
  }

  /* -- GPU identity: certain, and the most valuable thing here -------------- */

  if (gpu) {
    const cls = rendererClass(gpu.renderer);

    if (cls === 'software') {
      out.push({
        id: 'bench-software-rendering',
        component: 'GPU',
        severity: 'critical',
        title: 'No graphics card is being used at all — this is rendering on the CPU',
        evidence: `The browser reports its renderer as "${gpu.renderer}", which is a software rasteriser.`,
        impact:
          'Every graphics figure in this run measures a CPU emulating a GPU, so it says nothing about the ' +
          'card. If games behave the same way, the card is not being used there either.',
        remedy:
          'Check the graphics driver is installed and the card appears in Device Manager without a warning ' +
          'triangle. In the browser, confirm hardware acceleration is switched on.',
        measured: true,
      });
    }

    if (cls === 'integrated' && ctx.expectedGpuName) {
      const expectedIsDiscrete = rendererClass(ctx.expectedGpuName) === 'discrete';
      if (expectedIsDiscrete) {
        out.push({
          id: 'bench-wrong-adapter',
          component: 'GPU',
          severity: 'critical',
          title: 'The integrated graphics are rendering, not the card you said is in the machine',
          evidence:
            `The renderer is "${gpu.renderer}", which is integrated graphics, but the machine is recorded ` +
            `as having ${an(ctx.expectedGpuName)} ${ctx.expectedGpuName}.`,
          impact:
            'An integrated chip is a small fraction of a discrete card. If games pick the same adapter, ' +
            'this is the entire performance problem and no other tuning will matter next to it.',
          remedy:
            'On a laptop, set the game and the browser to the high-performance GPU in Windows Graphics ' +
            'Settings, and check the display is not wired to the motherboard output instead of the card. ' +
            'On a desktop, move the monitor cable to the card itself.',
          measured: true,
          estimatedGainPct: 1.5,
        });
      }
    }

    /* -- The card in the machine against the card in the form ------------ */

    if (ctx.detectedGpuId && ctx.expectedGpuId && ctx.detectedGpuId !== ctx.expectedGpuId && cls !== 'software') {
      out.push({
        id: 'bench-gpu-mismatch',
        component: 'GPU',
        severity: 'major',
        title: 'The card doing the rendering is not the card selected on the form',
        evidence:
          `The graphics driver reports "${gpu.renderer}", which this catalogue matches to a ` +
          `${ctx.detectedGpuName ?? ctx.detectedGpuId}. The form says ${ctx.expectedGpuName ?? ctx.expectedGpuId}.`,
        impact:
          'Every estimate on the other screens is being produced for a part this machine does not appear to ' +
          'have, so the numbers are answering a question about somebody else\'s computer.',
        remedy:
          'If the machine has two graphics adapters, this may be the wrong one being used rather than the ' +
          'wrong one being recorded — check which is driving the display. Otherwise change the selection to ' +
          'match what the driver reports.',
        measured: true,
      });
    }

    /* -- Decline over the run: needs no reference ------------------------- */

    const thr = throttleVerdict(gpu.windows, noise);
    if (thr?.declined) {
      out.push({
        id: 'bench-gpu-throttle',
        component: 'Cooling',
        severity: thr.retained < 0.8 ? 'major' : 'minor',
        title: `Graphics throughput fell ${((1 - thr.retained) * 100).toFixed(0)}% while under sustained load`,
        evidence: thr.detail,
        impact:
          'The first minutes of a session run faster than the rest. Benchmarks that run for thirty seconds ' +
          'will not show this; a long evening will.',
        remedy:
          'Check the card\'s fans spin up and its intake is not blocked with dust. Case airflow matters as ' +
          'much as the cooler: a card breathing its own exhaust throttles regardless of how good it is.',
        measured: true,
        estimatedGainPct: Math.max(0, 1 - thr.retained),
      });
    }

    /* -- Evenness of delivery: also self-referential ---------------------- */

    const steady = steadinessVerdict(gpu.passMs ?? []);
    if (steady && !steady.steady && cls !== 'software') {
      out.push({
        id: 'bench-gpu-uneven',
        component: 'GPU',
        severity: 'minor',
        title: 'Identical graphics work took wildly different times from one pass to the next',
        evidence: steady.detail,
        impact:
          'Something was competing for the card while this ran. That may have been a browser tab, a game ' +
          'launcher, a streaming overlay or a driver service — and whatever it was, it will compete with a ' +
          'game in the same way. It also means the throughput figures above read lower than the card can do.',
        remedy:
          'Close other tabs and any overlay software, then run this again. If it is still uneven with nothing ' +
          'else open, the graphics driver is the next thing to look at.',
        measured: true,
      });
    }

    if (gpu.adapter?.fallback) {
      out.push({
        id: 'bench-adapter-fallback',
        component: 'GPU',
        severity: 'major',
        title: 'The browser is using a fallback graphics adapter rather than the real one',
        evidence:
          'The adapter reports itself as a fallback, which means the browser could not use the hardware ' +
          'adapter and dropped to a substitute.',
        impact:
          'Graphics figures in this run describe the substitute. Whether games are affected depends on ' +
          'whether they hit the same problem, but a browser refusing the card is worth explaining.',
        remedy:
          'Update the graphics driver, then confirm hardware acceleration is enabled in the browser settings.',
        measured: true,
      });
    }
  } else {
    out.push({
      id: 'bench-no-gpu',
      component: 'GPU',
      severity: 'unknown',
      title: 'The graphics test could not run',
      evidence: result.notes.find((n) => n.toLowerCase().includes('webg')) ?? 'No WebGL context was available.',
      impact: 'Nothing was measured about the card. This is a gap in the check, not a fault in the machine.',
      remedy: 'Check hardware acceleration is enabled in the browser, or use the PowerShell harness instead.',
      measured: false,
    });
  }

  /* -- CPU: scaling and decline, both self-referential --------------------- */

  if (cpu) {
    const sc = scalingVerdict(cpu);
    if (sc && !sc.healthy) {
      out.push({
        id: 'bench-cpu-scaling',
        component: 'CPU',
        severity: 'major',
        title: 'The processor is not using all of its cores at full speed',
        evidence: sc.detail,
        impact:
          'Anything that spreads across cores — simulation-heavy games, compiling, encoding — runs at a ' +
          'fraction of what the part can do. Single-core work is unaffected, which is why this often goes ' +
          'unnoticed.',
        remedy:
          'Set the Windows power plan to Balanced or High Performance rather than Power Saver, and check ' +
          'that core parking is not enabled. On a laptop, check it is plugged in — most cap multi-core ' +
          'clocks hard on battery.',
        measured: true,
        estimatedGainPct: 0.15,
      });
    }

    const thr = throttleVerdict(cpu.windows, noise);
    if (thr?.declined) {
      out.push({
        id: 'bench-cpu-throttle',
        component: 'Cooling',
        severity: thr.retained < 0.8 ? 'major' : 'minor',
        title: `Processor throughput fell ${((1 - thr.retained) * 100).toFixed(0)}% while under sustained load`,
        evidence: thr.detail,
        impact:
          'Sustained work slows down as the machine warms. In games this shows up as frame rate drifting ' +
          'down over a session rather than as a sudden stutter.',
        remedy:
          'Cooler mounting pressure and thermal paste are the usual causes on a machine more than a few ' +
          'years old. Dust in the cooler fins is the other. Both are reversible.',
        measured: true,
        estimatedGainPct: Math.max(0, 1 - thr.retained),
      });
    }
  }

  /* -- This machine against itself, last time ------------------------------ */

  if (gpu && ctx.previous?.alu && ctx.previous.bandwidth) {
    const bal = balanceVerdict(gpu.workloads, { alu: ctx.previous.alu, bandwidth: ctx.previous.bandwidth });
    if (bal?.shifted && bal.change < 1) {
      out.push({
        id: 'bench-balance-shift',
        component: 'GPU',
        severity: 'minor',
        title: 'The card\'s memory throughput has fallen away from its arithmetic since the last check',
        evidence: bal.detail,
        impact:
          'A card whose two halves have moved apart is not the card it was. The commonest cause is a memory ' +
          'clock that is no longer boosting — which costs frames in every game and is invisible to any test ' +
          'that only measures shader arithmetic.',
        remedy:
          'Check whether anything was changed since the last run: a driver update, an overclocking profile ' +
          'that stopped loading, a power setting. If nothing was, run it again with everything else closed ' +
          'before treating it as real.',
        measured: true,
      });
    }
  }

  /* -- Whether the run could support a finding at all ---------------------- */

  if (cpu) {
    const stab = stabilityVerdict(cpu.variation);
    if (stab && !stab.reliable) {
      out.push({
        id: 'bench-unstable',
        component: 'Software',
        severity: 'unknown',
        title: 'The machine measured differently each time it was asked, so small effects are being withheld',
        evidence: stab.detail,
        impact:
          'Anything below that depends on a few percent has been suppressed rather than reported. The ' +
          'certain findings — which adapter is rendering, whether it is software — are unaffected, because ' +
          'those do not depend on timing at all.',
        remedy:
          'Close whatever else is running and take the check again. On a laptop, plug it in first: power ' +
          'management moves clocks around underneath a measurement and produces exactly this.',
        measured: false,
      });
    }

    /* -- The processor in the machine against the one on the form --------- */

    const corr = corroborateCpu(cpu, ctx.expectedCpu);
    if (corr?.conflicts) {
      const bad = corr.checks.filter((c) => c.agrees === false);
      out.push({
        id: 'bench-cpu-mismatch',
        component: 'CPU',
        severity: 'minor',
        title: `What was measured does not match ${an(ctx.expectedCpu?.fullName ?? '')} ${ctx.expectedCpu?.fullName}`,
        evidence: bad.map((c) => c.detail).join(' '),
        impact:
          'Every estimate on the other screens is produced for the processor selected on the form. If that ' +
          'is not the processor in this machine, those numbers are about a different computer.',
        remedy:
          'Check the selection against what the machine actually reports — Task Manager\'s Performance tab ' +
          'names the processor, its cores and its cache. A virtual machine or a privacy setting can also ' +
          'produce this without the selection being wrong.',
        measured: true,
      });
    }
  }

  /* -- Memory: strided-to-sequential ratio --------------------------------- */

  if (memory && memory.copyGBs > 0 && memory.stridedGBs > 0) {
    const ratio = memory.stridedGBs / memory.copyGBs;
    // A strided read defeats the prefetcher and should be far slower than a
    // sequential copy. Everything here is inside one process's heap, so this
    // says more about cache behaviour than about the DIMMs — worth recording,
    // not worth calling a fault. No finding is raised from it.
    void ratio;
  }

  return out;
}

/**
 * The one-line summary shown above the findings.
 *
 * Deliberately leads with what the run could NOT establish, because the honest
 * headline for a browser benchmark is its limits, not its numbers.
 */
export function benchSummary(result: BenchResult, findings: Finding[]): string {
  const real = findings.filter((f) => f.severity !== 'ok' && f.severity !== 'unknown');
  const worst = real.find((f) => f.severity === 'critical') ?? real.find((f) => f.severity === 'major');
  const head = worst
    ? `${worst.title}.`
    : real.length
      ? `Nothing serious: ${real.length} minor point${real.length === 1 ? '' : 's'} below.`
      : 'Nothing looks wrong from what this can see.';
  const ran = [
    result.gpu?.workloads?.length ? `${result.gpu.workloads.length} graphics workloads` : result.gpu && 'graphics',
    result.cpu?.scaling?.length ? `the processor at ${result.cpu.scaling.length} thread counts` : result.cpu && 'processor',
    result.cpu?.latencyCurve?.length && 'the cache hierarchy',
    result.memory && 'memory bandwidth',
  ]
    .filter(Boolean)
    .join(', ');
  return (
    `${head} Ran ${ran || 'nothing'} in ${result.durationS.toFixed(0)} seconds, in this browser. ` +
    `That is enough to identify the adapter actually rendering, catch software rendering, watch for decline ` +
    `under sustained load, and check the machine's own shape against the parts on the form — it is not ` +
    `enough to tell you a frame rate, and nothing here has been converted into one.`
  );
}
