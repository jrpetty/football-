/**
 * System health: is this machine performing as well as its parts say it should?
 *
 * The question people actually have about a PC they already own is not "how
 * fast is a 4070" — it is "my 4070 feels slow, is something wrong?" Almost
 * always something IS wrong, and almost always it is one of a small number of
 * things that are invisible from a frame-rate number: memory running at JEDEC
 * defaults because XMP was never enabled, a single stick where there should be
 * two, a GPU sitting in a chipset x4 slot, a cooler that cannot hold the CPU's
 * sustained draw, a power supply whose protection trips on transients.
 *
 * Two distinct kinds of finding come out of this, and conflating them would be
 * a mistake:
 *
 *   CONFIGURATION findings come from the specification alone. They are certain
 *   — single-channel memory IS costing about a third of the CPU-bound frame
 *   rate, whether or not anyone has benchmarked anything.
 *
 *   MEASURED findings compare a real captured frame rate against what the model
 *   expects. These are only as good as the model, which for absolute frame
 *   rates is a recalled seed — so a measured finding is stated as a comparison
 *   with an explicit uncertainty band, and a result inside that band is
 *   reported as "as expected", never as "confirmed healthy".
 */
import { RAM_MODEL, PCIE_MODEL } from './constants.ts';
import { estimate, type EngineData } from './engine.ts';
import { estimatePower, estimateThermals } from './physics.ts';
import type { Build, CpuRecord, GpuRecord, Resolution } from './types.ts';

export type Severity = 'critical' | 'major' | 'minor' | 'ok' | 'unknown';

export interface Finding {
  id: string;
  /** Which part of the machine this is about. Drives grouping in the UI. */
  component: 'CPU' | 'GPU' | 'Memory' | 'Storage' | 'Cooling' | 'Power' | 'Platform' | 'Software' | 'Display';
  severity: Severity;
  /** One line, written to be read on its own. */
  title: string;
  /** What was observed. Facts only. */
  evidence: string;
  /** What it costs, quantified wherever the model can quantify it. */
  impact: string;
  /** What to do about it, concretely. Null when there is nothing to do. */
  remedy: string | null;
  /**
   * True when this comes from a real measurement rather than from the
   * specification. Measured findings inherit the model's uncertainty.
   */
  measured: boolean;
  /** Estimated frame-rate recovery if fixed, as a fraction. 0.1 = 10% faster. */
  estimatedGainPct?: number;
}

export interface DetectedSystem {
  build: Build;
  /** Free text from the detector, used for driver and OS checks. */
  driverVersion?: string;
  driverDate?: string;
  osBuild?: string;
  motherboard?: { manufacturer?: string; product?: string; chipset?: string; biosDate?: string };
  memory?: {
    modulesPopulated?: number;
    slotsTotal?: number;
    ratedSpeedMTs?: number;
    configuredSpeedMTs?: number;
  };
  /** PCIe link the GPU is actually negotiated at, if the detector could read it. */
  pcieLink?: { gen?: number; width?: number };
  cooling?: { cooler?: string; airflow?: 'restricted' | 'moderate' | 'good' | 'excellent' };
  psuWatts?: number;
  uptimeDays?: number;
}

export interface Measurement {
  gameId: string;
  resolution: Resolution;
  preset?: string;
  avgFps: number;
  low1PctFps?: number;
  upscaling?: Build extends never ? never : { tech: string; quality: string; frameGen: boolean };
  rtTier?: 'on' | 'off';
  note?: string;
}

export interface HealthReport {
  findings: Finding[];
  /** Findings that are genuinely fine, kept so the report is not all bad news. */
  healthy: Finding[];
  /** Overall, in one sentence. */
  verdict: string;
  /** Sum of the estimated recoverable performance, compounded. */
  recoverablePct: number;
  /** Per-measurement comparison against the model. */
  comparisons: MeasurementComparison[];
  /** Everything the report could NOT check, said out loud. */
  notChecked: string[];
}

export interface MeasurementComparison {
  measurement: Measurement;
  expectedFps: number | null;
  /** 1-sigma band the model puts around its own expectation. */
  bandLow: number | null;
  bandHigh: number | null;
  ratio: number | null;
  verdict: 'above expectation' | 'as expected' | 'below expectation' | 'far below expectation' | 'no estimate';
  detail: string;
}

const pct = (x: number) => `${(x * 100).toFixed(0)}%`;

/**
 * Configuration checks. These need no benchmark and are certain in a way that
 * measured findings are not.
 */
function configurationFindings(sys: DetectedSystem, gpu: GpuRecord, cpu: CpuRecord): Finding[] {
  const out: Finding[] = [];
  const ram = sys.build.ram;

  // --- Memory channels: the single biggest silent loss in a prebuilt --------
  const chanMult = RAM_MODEL.channelMultiplier[ram.channels] ?? 1;
  if (ram.channels === 1) {
    out.push({
      id: 'mem-single-channel',
      component: 'Memory',
      severity: 'critical',
      title: 'Memory is running in single channel',
      evidence: `One populated channel${sys.memory?.modulesPopulated ? ` (${sys.memory.modulesPopulated} module${sys.memory.modulesPopulated === 1 ? '' : 's'} of ${sys.memory.slotsTotal ?? '?'} slots)` : ''}.`,
      impact: `Costs about ${pct(1 - chanMult)} of the CPU-bound frame rate. This is the most common single fault in a machine that "feels slow for what it is", and it is invisible on a spec sheet — the capacity is right, the speed is right, and half the bandwidth is missing.`,
      remedy:
        'Add a second matched stick, in the correct slot pair for the board (usually slots 2 and 4, counting from the CPU — the manual is definitive). Two 8GB sticks beat one 16GB stick decisively.',
      measured: false,
      estimatedGainPct: 1 / chanMult - 1,
    });
  } else if (ram.channels >= 2) {
    out.push({
      id: 'mem-channels-ok',
      component: 'Memory',
      severity: 'ok',
      title: `Memory is running in ${ram.channels === 2 ? 'dual-' : `${ram.channels}-`}channel`,
      evidence: `${ram.channels} channels populated.`,
      impact: 'Full memory bandwidth is available to the CPU.',
      remedy: null,
      measured: false,
    });
  }

  // --- XMP/EXPO: rated speed vs the speed actually applied -----------------
  const rated = sys.memory?.ratedSpeedMTs;
  const running = sys.memory?.configuredSpeedMTs ?? ram.speedMTs;
  if (rated && running && rated > running * 1.08) {
    const gain = Math.min(0.12, (rated / running - 1) * 0.35);
    out.push({
      id: 'mem-xmp-off',
      component: 'Memory',
      severity: 'major',
      title: 'Memory is running below the speed it is rated for',
      evidence: `Modules are rated ${rated} MT/s; the system is running them at ${running} MT/s.`,
      impact: `The kit is running at JEDEC defaults, which is what happens when the XMP or EXPO profile was never enabled. Worth roughly ${pct(gain)} in CPU-bound titles — most of a tier of CPU, for free.`,
      remedy:
        'Enable XMP (Intel) or EXPO (AMD) in the BIOS. It is one setting, usually on the first page. Run a memory test afterwards — an unstable profile is worse than a slow one.',
      measured: false,
      estimatedGainPct: gain,
    });
  } else if (rated && running) {
    out.push({
      id: 'mem-xmp-ok',
      component: 'Memory',
      severity: 'ok',
      title: 'Memory is running at its rated speed',
      evidence: `${running} MT/s, matching the modules' ${rated} MT/s rating.`,
      impact: 'The XMP/EXPO profile is applied.',
      remedy: null,
      measured: false,
    });
  }

  // --- Capacity ------------------------------------------------------------
  const capacityRule = RAM_MODEL.capacityPenalty.find((r) => ram.totalGB < r.belowGB);
  if (capacityRule) {
    out.push({
      id: 'mem-capacity',
      component: 'Memory',
      severity: ram.totalGB < 8 ? 'critical' : ram.totalGB < 12 ? 'major' : 'minor',
      title: `${ram.totalGB}GB of system memory is below what modern titles want`,
      evidence: `${ram.totalGB}GB installed.`,
      impact: `Costs roughly ${pct(1 - capacityRule.multiplier)} through paging and streaming stalls, and shows up as stutter rather than as a lower average — which is why it is often blamed on the GPU.`,
      remedy: '16GB is the practical floor and 32GB is the comfortable one. Match the existing kit or replace it outright; mixing kits usually drops both to the slower profile.',
      measured: false,
      estimatedGainPct: 1 / capacityRule.multiplier - 1,
    });
  }

  // --- PCIe link -----------------------------------------------------------
  if (sys.pcieLink?.width != null && sys.pcieLink.width < 16) {
    const gen = sys.pcieLink.gen ?? gpu.pcieGen ?? 4;
    const mult = PCIE_MODEL.bandwidth[`${gen}-${sys.pcieLink.width}`] ?? 0.97;
    out.push({
      id: 'pcie-width',
      component: 'Platform',
      severity: sys.pcieLink.width <= 4 ? 'major' : 'minor',
      title: `Graphics card is running at x${sys.pcieLink.width}, not x16`,
      evidence: `Negotiated link: PCIe gen ${gen} x${sys.pcieLink.width}.`,
      impact: `Costs roughly ${pct(Math.max(0, 1 - mult))}, and considerably more in titles that stream heavily or exceed VRAM, where the link is doing real work every frame.`,
      remedy:
        'Check the card is in the top slot — the one wired directly to the CPU. Slots below it are usually chipset-connected and narrower. An M.2 drive in the wrong socket can also steal lanes from the top slot on some boards; the manual has the lane-sharing table.',
      measured: false,
      estimatedGainPct: 1 / mult - 1,
    });
  }

  // --- Storage -------------------------------------------------------------
  if (sys.build.storage === 'hdd') {
    out.push({
      id: 'storage-hdd',
      component: 'Storage',
      severity: 'major',
      title: 'Games are installed on a mechanical hard drive',
      evidence: 'The games drive is a spinning disk.',
      impact:
        'Modern open-world titles stream assets continuously. On a hard drive that shows up as traversal stutter and pop-in rather than as a lower average frame rate, which is why it rarely gets blamed correctly.',
      remedy: 'Move the games to an SSD. Even a cheap SATA one removes almost all of it; NVMe adds little beyond that for gaming.',
      measured: false,
      estimatedGainPct: 0.04,
    });
  }

  // --- Cooling -------------------------------------------------------------
  const thermal = estimateThermals(gpu, cpu, sys.build, sys.cooling?.airflow ?? 'good');
  if (thermal.throttling) {
    const loss = 1 - Math.min(thermal.cpuClockFactor, thermal.gpuClockFactor);
    // Name the side that is actually throttling. Saying "cooling cannot hold
    // this hardware" while reporting the CPU at 100% reads as a contradiction
    // and sends someone to replace the wrong cooler.
    const cpuHit = thermal.cpuClockFactor < 0.995;
    const gpuHit = thermal.gpuClockFactor < 0.995;
    const side = cpuHit && gpuHit ? 'CPU and GPU' : cpuHit ? 'CPU' : 'GPU';
    out.push({
      id: 'thermal-throttle',
      component: 'Cooling',
      severity: loss > 0.1 ? 'major' : 'minor',
      title: `Heat is costing ${side} clock speed`,
      evidence: `${thermal.cpuHeatW.toFixed(0)}W of CPU heat against an effective cooler capacity of ${thermal.capacityW.toFixed(0)}W after case airflow (load ratio ${thermal.loadRatio.toFixed(2)}). Sustained clocks: CPU ${pct(thermal.cpuClockFactor)} of boost, GPU ${pct(thermal.gpuClockFactor)}.`,
      impact: `${gpuHit && !cpuHit ? 'The CPU cooler is coping; the case is not. Recirculated hot air is what the graphics card is breathing, and it drops its own clocks in response.' : 'Sustained clocks fall below boost under load.'} A throttling machine benchmarks fine for thirty seconds and is slow for the rest of the session, which is exactly the pattern people describe as "it gets worse the longer I play".`,
      remedy: cpuHit
        ? 'In order of effect per pound: clean the dust filters and heatsink fins, re-seat the cooler with fresh paste, add or reverse case fans so the front intakes and the rear exhausts, then replace the cooler. A better case is usually cheaper than a better CPU.'
        : 'This one is the case, not the cooler. Add or reverse fans so the front intakes and the rear and top exhaust, clear the dust filters, and give the card room to breathe. Replacing the CPU cooler will not help — it is already coping.',
      measured: false,
      estimatedGainPct: 1 / Math.min(thermal.cpuClockFactor, thermal.gpuClockFactor) - 1,
    });
  } else {
    out.push({
      id: 'thermal-ok',
      component: 'Cooling',
      severity: 'ok',
      title: 'Cooling holds this hardware at full clocks',
      evidence: `${thermal.cpuHeatW.toFixed(0)}W CPU heat against ${thermal.capacityW.toFixed(0)}W of effective capacity.`,
      impact: 'No sustained-clock loss from heat.',
      remedy: null,
      measured: false,
    });
  }

  // --- Power supply --------------------------------------------------------
  const power = estimatePower(gpu, cpu, sys.build);
  if (sys.psuWatts != null) {
    if (sys.psuWatts < power.transientPeakW) {
      out.push({
        id: 'psu-transient',
        component: 'Power',
        severity: sys.psuWatts < power.totalW ? 'critical' : 'major',
        title: 'Power supply is smaller than this hardware\'s transient peak',
        evidence: `${sys.psuWatts}W supply against a sustained draw of ${power.totalW.toFixed(0)}W and microsecond transient peaks near ${power.transientPeakW.toFixed(0)}W.`,
        impact:
          'Averages are not what trips a power supply — spikes are. A supply sized to the average shuts the machine off under load and looks exactly like a faulty graphics card, which is how people end up replacing the wrong part.',
        remedy: `A ${power.recommendedPsuW}W unit from a reputable OEM, ATX 3.x if the card uses a 12V-2x6 connector. This is not a place to save 20.`,
        measured: false,
      });
    } else {
      out.push({
        id: 'psu-ok',
        component: 'Power',
        severity: 'ok',
        title: 'Power supply has headroom for the transient peak',
        evidence: `${sys.psuWatts}W against a ${power.transientPeakW.toFixed(0)}W peak.`,
        impact: 'No shutdown risk from transient spikes.',
        remedy: null,
        measured: false,
      });
    }
  }

  // --- Driver age ----------------------------------------------------------
  if (sys.driverDate) {
    const days = (Date.now() - new Date(sys.driverDate).getTime()) / 86400000;
    if (Number.isFinite(days) && days > 270) {
      out.push({
        id: 'driver-age',
        component: 'Software',
        severity: days > 540 ? 'major' : 'minor',
        title: `Graphics driver is ${Math.round(days / 30)} months old`,
        evidence: `Driver dated ${sys.driverDate}${sys.driverVersion ? ` (${sys.driverVersion})` : ''}.`,
        impact:
          'Driver age costs nothing in most titles and a great deal in a few — a launch-window title on a driver that predates it can run at half speed or crash outright. It is the cheapest thing on this list to rule out.',
        remedy: 'Update from the vendor directly rather than through Windows Update, which lags by months.',
        measured: false,
      });
    }
  }

  // --- Uptime --------------------------------------------------------------
  if (sys.uptimeDays != null && sys.uptimeDays > 7) {
    out.push({
      id: 'uptime',
      component: 'Software',
      severity: 'minor',
      title: `The machine has been up for ${sys.uptimeDays.toFixed(0)} days`,
      evidence: `Uptime ${sys.uptimeDays.toFixed(0)} days.`,
      impact:
        'Long uptimes accumulate memory pressure, background services and driver state. It is not a hardware fault and it is a real cause of a machine feeling slower than it benchmarks.',
      remedy: 'Reboot before benchmarking, and before concluding anything from the numbers below.',
      measured: false,
    });
  }

  // --- Pairing balance -----------------------------------------------------
  return out;
}

/**
 * Compare a real measurement against what the model expects from the same
 * hardware, and be careful about what that comparison can and cannot say.
 */
export function compareMeasurement(
  m: Measurement,
  sys: DetectedSystem,
  data: EngineData,
): MeasurementComparison {
  const est = estimate(sys.build, m.gameId, m.resolution, data, {
    preset: m.preset,
    upscaling: m.upscaling as never,
    rtTier: m.rtTier ?? 'off',
    airflowTier: sys.cooling?.airflow,
  });
  if (est.status !== 'ok' || est.avgFps == null) {
    return {
      measurement: m,
      expectedFps: null,
      bandLow: null,
      bandHigh: null,
      ratio: null,
      verdict: 'no estimate',
      detail:
        est.status === 'WILL_NOT_RUN'
          ? `The model says this title should not run at all on this hardware — yet a measurement exists. That is worth investigating: either the gate is wrong or the detected specification is.`
          : `No estimate is available for this hardware, so there is nothing to compare against.`,
    };
  }

  const band = est.band ?? { low: est.avgFps * 0.8, high: est.avgFps * 1.2 };
  const ratio = m.avgFps / est.avgFps;
  let verdict: MeasurementComparison['verdict'];
  let detail: string;

  if (m.avgFps >= band.high) {
    verdict = 'above expectation';
    detail = `${m.avgFps.toFixed(0)}fps measured against an expected ${est.avgFps.toFixed(0)} (band ${band.low.toFixed(0)}-${band.high.toFixed(0)}). Your machine is faster than the model predicts. Before celebrating: check the capture was the same preset and resolution, and that no upscaling or frame generation was on that the model was not told about.`;
  } else if (m.avgFps >= band.low) {
    verdict = 'as expected';
    detail = `${m.avgFps.toFixed(0)}fps measured against an expected ${est.avgFps.toFixed(0)} (band ${band.low.toFixed(0)}-${band.high.toFixed(0)}). This is where the model says it should be. That is not proof the machine is healthy — it means nothing detectable is wrong at this level of precision.`;
  } else if (ratio > 0.75) {
    verdict = 'below expectation';
    detail = `${m.avgFps.toFixed(0)}fps measured against an expected ${est.avgFps.toFixed(0)} — ${pct(1 - ratio)} short, outside the model's band. Small but real. Check the configuration findings above first; they explain most gaps this size.`;
  } else {
    verdict = 'far below expectation';
    detail = `${m.avgFps.toFixed(0)}fps measured against an expected ${est.avgFps.toFixed(0)} — ${pct(1 - ratio)} short. A gap this large is rarely subtle: background load, a power plan capping clocks, a display running at the wrong refresh, the game rendering on the wrong adapter, or a setting the capture did not record.`;
  }

  return { measurement: m, expectedFps: est.avgFps, bandLow: band.low, bandHigh: band.high, ratio, verdict, detail };
}

/**
 * Findings that only a measurement can produce.
 *
 * The 1% low relative to the average is the interesting one: a normal ratio is
 * around 0.7-0.8, and a much lower one means the average is fine while the
 * experience is not — which is exactly the machine people describe as "good
 * numbers, feels bad".
 */
function measuredFindings(comparisons: MeasurementComparison[], nameOf: (id: string) => string): Finding[] {
  const out: Finding[] = [];
  const usable = comparisons.filter((c) => c.ratio != null);
  if (!usable.length) return out;

  const short = usable.filter((c) => c.verdict === 'below expectation' || c.verdict === 'far below expectation');

  // A single short title is a different fault from a machine that is short
  // everywhere, and needs saying either way. Without this, one measurement
  // 40% below expectation produced NO finding at all — the aggregate rule
  // needs two — and the verdict then read "the measurements land where the
  // model expects", which was flatly untrue.
  for (const c of short) {
    out.push({
      id: `measured-short-${c.measurement.gameId}`,
      component: 'GPU',
      severity: c.verdict === 'far below expectation' ? 'major' : 'minor',
      title: `${nameOf(c.measurement.gameId)} is ${pct(1 - c.ratio!)} slower than this hardware should be`,
      evidence: `${c.measurement.avgFps.toFixed(0)}fps measured at ${c.measurement.preset ?? 'the reference preset'}, ${c.measurement.resolution}, against an expected ${c.expectedFps!.toFixed(0)} (band ${c.bandLow!.toFixed(0)}-${c.bandHigh!.toFixed(0)}).`,
      impact:
        usable.length === 1
          ? 'One measurement cannot separate a problem with this title from a problem with the machine. A second capture in a different game is the cheapest way to tell them apart — if both are short, it is the machine.'
          : 'The other measurements are closer to expectation, which points at this title rather than at the machine.',
      remedy:
        'First check the capture matched what was entered here: same preset, same resolution, no upscaling or frame generation the model was not told about. Then work down the configuration findings. If they are all clear, capture again after a reboot with nothing else running.',
      measured: true,
    });
  }

  if (short.length >= Math.max(2, usable.length * 0.6)) {
    const worst = short.reduce((a, b) => (a.ratio! < b.ratio! ? a : b));
    out.push({
      id: 'measured-broad-shortfall',
      component: 'Platform',
      severity: 'major',
      title: 'The machine is slower than its parts across most of what was measured',
      evidence: `${short.length} of ${usable.length} measurements came in below the model's band, worst being ${nameOf(worst.measurement.gameId)} at ${pct(1 - worst.ratio!)} short.`,
      impact:
        'A shortfall in one title is usually that title. A shortfall in most of them is the machine: a system-wide cause such as a power plan, memory configuration, thermal limit or background load.',
      remedy:
        'Work down the configuration findings first — they are certain, and they are the usual cause. If they are all clear, capture again after a reboot with nothing else running.',
      measured: true,
    });
  }

  for (const c of usable) {
    const m = c.measurement;
    if (m.low1PctFps == null || m.low1PctFps <= 0) continue;
    const ratio = m.low1PctFps / m.avgFps;
    if (ratio < 0.55) {
      out.push({
        id: `stutter-${m.gameId}`,
        component: 'Storage',
        severity: ratio < 0.4 ? 'major' : 'minor',
        title: `${nameOf(m.gameId)}: the average is fine but the frame pacing is not`,
        evidence: `1% low is ${m.low1PctFps.toFixed(0)}fps against a ${m.avgFps.toFixed(0)}fps average — a ratio of ${ratio.toFixed(2)}, where 0.7 to 0.8 is normal.`,
        impact:
          'This is the machine that "gets good numbers but feels bad". The average is carried by smooth stretches while the stalls people actually notice sit in the tail.',
        remedy:
          'The usual causes, in order: VRAM exceeded (drop textures one step and re-measure), asset streaming from a slow drive, shader compilation on first run, or a background process waking periodically. Re-run the same capture twice — if the second is much better, it was shader compilation and there is nothing wrong.',
        measured: true,
      });
    }
  }

  return out;
}

const SEVERITY_ORDER: Record<Severity, number> = { critical: 0, major: 1, minor: 2, unknown: 3, ok: 4 };

/**
 * The whole report.
 *
 * `notChecked` is not padding. A health report that lists six findings looks
 * complete, and a reader will assume everything else was verified — so the
 * things this cannot see have to be stated as plainly as the things it can.
 */
export function diagnose(
  sys: DetectedSystem,
  measurements: Measurement[],
  data: EngineData,
): HealthReport {
  const gpu = data.gpus.get(sys.build.gpuId);
  const cpu = data.cpus.get(sys.build.cpuId);
  if (!gpu || !cpu) {
    return {
      findings: [],
      healthy: [],
      verdict: 'The detected CPU or GPU is not in the catalogue, so nothing can be checked against it.',
      recoverablePct: 0,
      comparisons: [],
      notChecked: ['Everything — the hardware could not be identified.'],
    };
  }

  const all = configurationFindings(sys, gpu, cpu);
  const comparisons = measurements.map((m) => compareMeasurement(m, sys, data));
  all.push(...measuredFindings(comparisons, (id) => data.games.get(id)?.name ?? id));

  const healthy = all.filter((f) => f.severity === 'ok');
  const findings = all
    .filter((f) => f.severity !== 'ok')
    .sort((a, b) => SEVERITY_ORDER[a.severity] - SEVERITY_ORDER[b.severity] || (b.estimatedGainPct ?? 0) - (a.estimatedGainPct ?? 0));

  // Compounded, not summed: fixing two things that each cost 10% does not
  // return 20%, and claiming it would overstate the prize.
  //
  // Note what this figure is NOT. The model's expected frame rate already
  // includes every configuration penalty below — single-channel memory, the
  // narrow PCIe link, the mechanical drive — so this is the gain over what the
  // machine does TODAY, not a gap against the expectation. The two numbers must
  // never be added together, which is why they are reported separately and the
  // comparison text never mentions this one.
  const recoverablePct =
    findings.reduce((acc, f) => acc * (1 + (f.estimatedGainPct ?? 0)), 1) - 1;

  const verdict = deriveVerdict(findings, comparisons, measurements);

  const notChecked = [
    'Per-core clock behaviour and whether Windows is on a balanced power plan capping boost. The detector cannot read this reliably.',
    'Whether the game is rendering on the intended adapter. On a laptop or a machine with an active iGPU this is a common and invisible cause of a large shortfall.',
    'Physical state: dust in the heatsink, a cooler that is not seated flat, fans that have failed. The thermal finding above models what SHOULD happen, not what is happening.',
    'Storage health and remaining free space. A drive above 90% full slows down, and SSD wear is not visible here.',
    'Anything on the network side, which dominates the experience in multiplayer titles regardless of hardware.',
  ];
  if (!measurements.length) {
    notChecked.unshift('Actual performance. Without at least one benchmark capture this report is a configuration review, not a health check — it can only say what SHOULD be wrong, never what IS.');
  }
  if (sys.pcieLink?.width == null) {
    notChecked.push('The PCIe link the graphics card negotiated. If the detector could not read it, a card sitting in a chipset x4 slot would not show up here.');
  }
  if (sys.memory?.ratedSpeedMTs == null) {
    notChecked.push('Whether the memory kit is running its XMP/EXPO profile. Without the modules\' rated speed there is nothing to compare the running speed against.');
  }

  return { findings, healthy, verdict, recoverablePct, comparisons, notChecked };
}

/**
 * The one-sentence verdict, derived from the findings rather than stored.
 *
 * Extracted so a caller that ADDS findings — the in-browser benchmark merges
 * its own into the list — recomputes the headline instead of leaving the
 * original in place. Leaving it produced "Broadly healthy, with 1 minor thing
 * worth tidying" as the sentence directly above a critical finding saying no
 * graphics card was being used at all.
 */
export function deriveVerdict(
  findings: Finding[],
  comparisons: MeasurementComparison[],
  measurements: Measurement[],
): string {
  const recoverablePct = findings.reduce((acc, f) => acc * (1 + (f.estimatedGainPct ?? 0)), 1) - 1;
  const worst = findings[0]?.severity;
  const anyShort = comparisons.some((c) => c.verdict === 'below expectation' || c.verdict === 'far below expectation');
  return (
    !findings.length
      ? measurements.length
        ? anyShort
          ? 'The configuration is clean, but the machine is not hitting what the model expects. Nothing in the specification explains it, which points at something this report cannot see — the list at the bottom is where to look next.'
          : 'Nothing detectable is wrong with this machine, and the measurements land where the model expects. That is the best this report can say — it is not the same as proof that everything is optimal.'
        : 'Nothing is wrong in the configuration. No measurements were supplied, so nothing has been verified against the hardware actually running.'
      // Only quote a percentage when something was actually quantified. A
      // measured shortfall has no modelled gain attached, and "worth roughly
      // 0%" next to a list of real problems reads as though they do not matter.
      : recoverablePct < 0.005
        ? worst === 'critical' || worst === 'major'
          ? `This machine is not performing as it should: ${findings[0].title.toLowerCase()}. What is costing the performance is not something the specification explains, so the findings below are where to start and the list at the bottom is what this could not see.`
          : `Broadly healthy, with ${findings.length} minor thing${findings.length === 1 ? '' : 's'} worth tidying.`
        : worst === 'critical'
          // When the headline fault carries no quantified gain, the recoverable
          // figure covers only the OTHER findings, and attaching it to the
          // sentence that names the headline reads as though it does. "No
          // graphics card is being used at all ... worth roughly 4%" was the
          // real output, and understating a total loss as 4% is worse than
          // quoting nothing.
          ? findings[0].estimatedGainPct == null
            ? `Something is materially wrong: ${findings[0].title.toLowerCase()}. That one is not quantified here because the loss is too large and too situation-specific to put a number on — the smaller findings below add up to about ${pct(recoverablePct)} between them, and they are not the problem.`
            : `Something is materially wrong: ${findings[0].title.toLowerCase()}. Fixing what is listed here is worth roughly ${pct(recoverablePct)} — more than any upgrade you could buy for the price.`
          : worst === 'major'
            ? `This machine is leaving performance on the table. The findings below are worth roughly ${pct(recoverablePct)} together, and none of them cost anything like a new part.`
            : `Broadly healthy, with ${findings.length} minor thing${findings.length === 1 ? '' : 's'} worth tidying — together worth about ${pct(recoverablePct)}.`
  );
}
