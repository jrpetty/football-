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
 * A fifth, weaker use: the GPU-to-CPU ratio can be compared with the ratio the
 * catalogue expects. That one DOES assume browser throughput scales roughly
 * like real throughput, which is a genuine assumption and is why the band
 * around it is deliberately wide and the finding it produces is never worse
 * than `minor`.
 */

import type { Finding } from './health.ts';

/** One timed window inside a sustained run, used to see decline over time. */
export interface BenchWindow {
  /** Milliseconds from the start of the sustained phase. */
  atMs: number;
  /** Throughput in this window, in the test's own units. */
  throughput: number;
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
 * The 8% threshold is deliberately not tight. Browser timing jitters, other
 * processes take the GPU, and boost algorithms move around by a few percent
 * on their own; calling a 3% dip "thermal throttling" would be inventing a
 * fault. Sustained clocks below boost clocks are also normal and expected —
 * what this looks for is a machine still sliding after it should have settled.
 */
export function throttleVerdict(windows: BenchWindow[]): ThrottleVerdict | null {
  if (windows.length < 4) return null;
  const q = Math.max(1, Math.floor(windows.length / 4));
  const mean = (xs: number[]) => xs.reduce((a, b) => a + b, 0) / xs.length;
  const first = mean(windows.slice(0, q).map((w) => w.throughput));
  const last = mean(windows.slice(-q).map((w) => w.throughput));
  if (!(first > 0)) return null;
  const retained = last / first;
  const dropPct = (1 - retained) * 100;
  if (retained >= 0.92) {
    return {
      retained,
      declined: false,
      detail:
        `Throughput held within ${dropPct < 0 ? 'noise' : `${dropPct.toFixed(0)}%`} of its opening rate ` +
        `across ${(windows[windows.length - 1].atMs / 1000).toFixed(0)} seconds of sustained load. ` +
        `Some fall is normal — boost clocks are not meant to be held indefinitely — and this is inside it.`,
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

/* --------------------------------------------------------------- findings -- */

export interface BenchContext {
  /** Catalogue name of the GPU the operator says is in the machine. */
  expectedGpuName?: string;
  /** Catalogue raster index of that GPU, for the ratio cross-check. */
  expectedGpuIndex?: number;
  /** Catalogue throughput index of the CPU. */
  expectedCpuIndex?: number;
}

/**
 * Turn a run into findings for the health report.
 *
 * Every finding here is either certain (software rendering, wrong adapter) or
 * self-referential (decline over a run, scaling against core count). The one
 * comparison against the catalogue is capped at `minor` and says out loud what
 * it is assuming.
 */
export function benchFindings(result: BenchResult, ctx: BenchContext = {}): Finding[] {
  const out: Finding[] = [];
  const { gpu, cpu, memory } = result;

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
            `as having a ${ctx.expectedGpuName}.`,
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

    /* -- Decline over the run: needs no reference ------------------------- */

    const thr = throttleVerdict(gpu.windows);
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

    const thr = throttleVerdict(cpu.windows);
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

  /* -- Memory: strided-to-sequential ratio --------------------------------- */

  if (memory && memory.copyGBs > 0 && memory.stridedGBs > 0) {
    const ratio = memory.stridedGBs / memory.copyGBs;
    // A strided read defeats the prefetcher and should be far slower than a
    // sequential copy. Everything here is inside one process's heap, so this
    // says more about cache behaviour than about the DIMMs — worth recording,
    // not worth calling a fault. No finding is raised from it.
    void ratio;
  }

  /* -- The one comparison against the catalogue, capped at minor ----------- */

  if (gpu && cpu && ctx.expectedGpuIndex && ctx.expectedCpuIndex && cpu.singleThread > 0) {
    const measuredRatio = gpu.throughput / cpu.singleThread;
    const expectedRatio = ctx.expectedGpuIndex / ctx.expectedCpuIndex;
    if (expectedRatio > 0 && Number.isFinite(measuredRatio)) {
      const off = measuredRatio / expectedRatio;
      // A factor of two either way before it is worth mentioning. The units on
      // both sides are arbitrary and only their ratio carries any meaning, so
      // anything tighter than this would be reading noise.
      if (off < 0.5 || off > 2) {
        out.push({
          id: 'bench-ratio-mismatch',
          component: 'GPU',
          severity: 'minor',
          title:
            off < 0.5
              ? 'The graphics side measured weaker than the parts list suggests it should'
              : 'The processor side measured weaker than the parts list suggests it should',
          evidence:
            `Graphics-to-processor throughput came out ${off.toFixed(1)}x the ratio the catalogue expects ` +
            `for this pairing. Both figures are in arbitrary browser units — only their ratio means anything.`,
          impact:
            'Weak evidence, and deliberately labelled as such. This assumes browser throughput scales like ' +
            'real throughput, which is an assumption and not a measurement. Treat it as a reason to look ' +
            'closer, never as a conclusion.',
          remedy:
            'Run the PowerShell harness and capture a real game, which measures the thing itself instead of ' +
            'a proxy for it.',
          measured: true,
        });
      }
    }
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
  const ran = [result.gpu && 'graphics', result.cpu && 'processor', result.memory && 'memory']
    .filter(Boolean)
    .join(', ');
  return (
    `${head} Ran ${ran || 'nothing'} in ${result.durationS.toFixed(0)} seconds, in this browser. ` +
    `That is enough to identify the adapter actually rendering, catch software rendering, and watch for ` +
    `decline under sustained load — it is not enough to tell you a frame rate, and nothing here has been ` +
    `converted into one.`
  );
}
