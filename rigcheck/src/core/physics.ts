/**
 * Power, thermals, acoustics and latency.
 *
 * Kept separate from engine.ts because these are properties of the MACHINE
 * rather than of a frame rate: a build draws the same watts and makes the same
 * noise whichever game you point it at, and latency depends on the display and
 * sync mode as much as on the renderer. The engine calls in here for the pieces
 * that feed back into FPS (thermal throttling), and the UI calls the rest
 * directly.
 */
import {
  LATENCY_MODEL,
  NOISE_MODEL,
  POWER_MODEL,
  THERMAL_MODEL,
} from './constants.ts';
import type { Build, CpuRecord, GpuRecord, ModelTerm } from './types.ts';

// ---------------------------------------------------------------------------
// Power
// ---------------------------------------------------------------------------

export interface PowerEstimate {
  cpuW: number;
  gpuW: number;
  systemW: number;
  /** Sustained total under a gaming load. */
  totalW: number;
  /** Worst-case microsecond spike a PSU's protection circuit actually sees. */
  transientPeakW: number;
  /** Recommended PSU wattage with headroom for efficiency and ageing. */
  recommendedPsuW: number;
  /** Running cost at the configured tariff. */
  costPerHourPence: number;
  costPerYearGBP: (hoursPerWeek: number) => number;
  terms: ModelTerm[];
}

const term = (label: string, value: number | string, explain: string): ModelTerm => ({
  label,
  value,
  confidence: 'spec-derived',
  sources: [],
  explain,
});

export function estimatePower(gpu: GpuRecord, cpu: CpuRecord, build: Build): PowerEstimate {
  const terms: ModelTerm[] = [];

  // Games never saturate every core, so a CPU's rated TDP badly overstates its
  // draw while it is feeding a GPU.
  const cpuTdp = cpu.tdpW ?? 65;
  const cpuW = cpuTdp * POWER_MODEL.cpuGamingLoadFactor;
  terms.push(
    term(
      'CPU draw',
      Math.round(cpuW),
      `${cpuTdp}W rated x ${POWER_MODEL.cpuGamingLoadFactor} gaming-load factor. A CPU feeding a GPU never reaches its all-core rating.`,
    ),
  );

  const gpuTdp = gpu.tdpW ?? 150;
  const gpuFactor = POWER_MODEL.gpuLoadFactor[gpu.vendor] ?? 0.95;
  const gpuW = gpuTdp * gpuFactor;
  terms.push(
    term(
      'GPU draw',
      Math.round(gpuW),
      `${gpuTdp}W rated x ${gpuFactor} (${gpu.vendor} board-power conventions differ; AMD's TBP excludes some board components).`,
    ),
  );

  const sticks = build.ram.channels * 2;
  const systemW =
    POWER_MODEL.systemBaseW + sticks * POWER_MODEL.perRamStickW + POWER_MODEL.perDriveW;
  terms.push(
    term('rest of system', Math.round(systemW), `${POWER_MODEL.systemBaseW}W board/fans/USB + ${sticks} RAM sticks + storage.`),
  );

  const totalW = cpuW + gpuW + systemW;

  // The spike, not the average, is what trips a PSU's over-current protection.
  const archSpike = POWER_MODEL.transientByArchitecture[gpu.architecture];
  const spikeMult = archSpike ?? POWER_MODEL.transientMultiplier[gpu.vendor] ?? 1.5;
  const transientPeakW = cpuW + gpuW * spikeMult + systemW;
  terms.push(
    term(
      'transient peak',
      Math.round(transientPeakW),
      `GPU spikes to ${spikeMult}x rated draw for microseconds${archSpike ? ` (${gpu.architecture} is specifically known for this)` : ''}. PSU protection trips on this figure, not the sustained one — the usual cause of a "big enough" PSU shutting down mid-game.`,
    ),
  );

  const recommendedPsuW = Math.ceil((Math.max(totalW * POWER_MODEL.psuHeadroom, transientPeakW * 1.05)) / 50) * 50;
  terms.push(
    term(
      'recommended PSU',
      recommendedPsuW,
      `Greater of sustained x${POWER_MODEL.psuHeadroom} and transient peak x1.05, rounded up to the next 50W.`,
    ),
  );

  const costPerHourPence = (totalW / 1000) * POWER_MODEL.pencePerKwh;

  return {
    cpuW,
    gpuW,
    systemW,
    totalW,
    transientPeakW,
    recommendedPsuW,
    costPerHourPence,
    costPerYearGBP: (hoursPerWeek: number) => (costPerHourPence * hoursPerWeek * 52) / 100,
    terms,
  };
}

// ---------------------------------------------------------------------------
// Thermals
// ---------------------------------------------------------------------------

export interface ThermalEstimate {
  /** Total system heat, in watts. */
  heatLoadW: number;
  /** Heat the CPU COOLER specifically has to move — the figure the throttle
   *  calculation actually uses. Exposed so callers cannot re-derive it wrongly. */
  cpuHeatW: number;
  /** Effective cooler capacity after case airflow. */
  capacityW: number;
  loadRatio: number;
  /** Sustained clock as a fraction of boost. 1.0 = no throttling. */
  cpuClockFactor: number;
  gpuClockFactor: number;
  throttling: boolean;
  terms: ModelTerm[];
}

function interpolateThrottle(loadRatio: number): number {
  const curve = THERMAL_MODEL.throttleCurve;
  if (loadRatio <= curve[0].loadRatio) return 1.0;
  for (let i = 1; i < curve.length; i++) {
    if (loadRatio <= curve[i].loadRatio) {
      const a = curve[i - 1];
      const b = curve[i];
      const f = (loadRatio - a.loadRatio) / (b.loadRatio - a.loadRatio);
      return a.clockFactor + f * (b.clockFactor - a.clockFactor);
    }
  }
  return curve[curve.length - 1].clockFactor;
}

export function estimateThermals(
  gpu: GpuRecord,
  cpu: CpuRecord,
  build: Build,
  airflowTier: 'restricted' | 'moderate' | 'good' | 'excellent' = 'good',
): ThermalEstimate {
  const terms: ModelTerm[] = [];
  const cooling = build.cooling ?? 'budget-tower';

  // The CPU cooler only has to handle the CPU; the GPU carries its own.
  const cpuHeatW = (cpu.tdpW ?? 65) * POWER_MODEL.cpuGamingLoadFactor;
  const rawCapacity = THERMAL_MODEL.coolerCapacityW[cooling] ?? 130;
  const airflow = THERMAL_MODEL.airflowMultiplier[airflowTier] ?? 1.0;
  const capacityW = rawCapacity * airflow;
  const loadRatio = cpuHeatW / capacityW;
  const cpuClockFactor = interpolateThrottle(loadRatio);

  terms.push(
    term(
      'CPU cooling',
      `${cooling} in ${airflowTier} airflow`,
      `${rawCapacity}W cooler x ${airflow} case airflow = ${capacityW.toFixed(0)}W capacity against ${cpuHeatW.toFixed(0)}W of gaming heat.`,
    ),
  );
  if (cpuClockFactor < 1) {
    terms.push(
      term(
        'CPU throttling',
        `${(cpuClockFactor * 100).toFixed(0)}% of boost`,
        'Sustained clocks fall below boost because the cooler cannot shed the heat. The catalogue clock assumes it can — this is the difference between a benchmark run and twenty minutes in.',
      ),
    );
  }

  // A GPU's own cooler is sized for its board power; the case decides whether
  // it can breathe.
  const gpuClockFactor =
    airflowTier === 'restricted' ? THERMAL_MODEL.gpuRestrictedCasePenalty : 1.0;
  if (gpuClockFactor < 1) {
    terms.push(
      term(
        'GPU airflow',
        `${(gpuClockFactor * 100).toFixed(0)}% of boost`,
        'Restricted case airflow means the card recirculates its own exhaust and steps down a bin or two.',
      ),
    );
  }

  return {
    heatLoadW: cpuHeatW + (gpu.tdpW ?? 150),
    cpuHeatW,
    capacityW,
    loadRatio,
    cpuClockFactor,
    gpuClockFactor,
    throttling: cpuClockFactor < 1 || gpuClockFactor < 1,
    terms,
  };
}

// ---------------------------------------------------------------------------
// Noise
// ---------------------------------------------------------------------------

export interface NoiseEstimate {
  dba: number;
  descriptor: string;
  terms: ModelTerm[];
}

export function estimateNoise(
  thermal: ThermalEstimate,
  power: PowerEstimate,
  build: Build,
  noiseTier: 'loud' | 'moderate' | 'quiet' | 'silent' = 'moderate',
): NoiseEstimate {
  const cooling = build.cooling ?? 'budget-tower';
  const base = NOISE_MODEL.baseDba[noiseTier] ?? 30;
  // Total heat relative to what the system can move sets how hard fans work.
  const loadRatio = Math.min(2.5, power.totalW / Math.max(thermal.capacityW * 2.2, 1));
  const rise = Math.max(0, loadRatio - 0.5) * NOISE_MODEL.dbaPerLoadRatio;
  const coolerBonus = NOISE_MODEL.coolerBonusDba[cooling] ?? 0;
  const dba = Math.min(NOISE_MODEL.maxDba, base + rise + coolerBonus);

  const descriptor =
    dba < 27 ? 'barely audible' : dba < 33 ? 'quiet' : dba < 40 ? 'audible' : dba < 46 ? 'noticeable' : 'loud';

  return {
    dba,
    descriptor,
    terms: [
      term(
        'noise estimate',
        `${dba.toFixed(0)} dBA — ${descriptor}`,
        `${base} dBA ${noiseTier} case floor + ${rise.toFixed(1)} from heat load${coolerBonus ? ` ${coolerBonus > 0 ? '+' : ''}${coolerBonus} cooler` : ''}. Rough by nature: fan curves and panel material matter more than wattage, so treat this as a trend, not a spec.`,
      ),
    ],
  };
}

// ---------------------------------------------------------------------------
// Latency
// ---------------------------------------------------------------------------

export type SyncMode = 'off' | 'vsync' | 'adaptive';
export type LatencyMode = 'default' | 'lowLatency' | 'ultraLowLatency';

export interface LatencyEstimate {
  /** Click-to-photon, milliseconds. */
  totalMs: number;
  frameTimeMs: number;
  queueMs: number;
  inputMs: number;
  displayMs: number;
  penaltyMs: number;
  terms: ModelTerm[];
}

export function estimateLatency(
  fps: number,
  opts: {
    panelType?: string;
    refreshHz?: number;
    sync?: SyncMode;
    latencyMode?: LatencyMode;
    frameGen?: boolean;
    cpuBound?: boolean;
  } = {},
): LatencyEstimate {
  const terms: ModelTerm[] = [];
  const frameTimeMs = 1000 / Math.max(fps, 1);
  const mode = opts.latencyMode ?? 'default';
  const queueFrames = LATENCY_MODEL.queueFrames[mode] ?? 2;
  const queueMs = queueFrames * frameTimeMs;

  const inputMs = LATENCY_MODEL.inputStackMs;
  const panel = opts.panelType ?? 'IPS';
  const displayMs = LATENCY_MODEL.displayMs[panel] ?? 6;

  let penaltyMs = 0;
  if (opts.sync === 'vsync' && opts.refreshHz) {
    const p = LATENCY_MODEL.vsyncPenaltyFrames * (1000 / opts.refreshHz);
    penaltyMs += p;
    terms.push(term('V-Sync wait', `${p.toFixed(1)} ms`, 'V-Sync holds a finished frame until the next refresh interval.'));
  }
  if (opts.frameGen) {
    penaltyMs += LATENCY_MODEL.frameGenLatencyPenaltyMs;
    terms.push(
      term(
        'frame generation',
        `+${LATENCY_MODEL.frameGenLatencyPenaltyMs} ms`,
        'Generation must hold the real frame back one interval to interpolate against it. Latency RISES while the frame counter doubles — which is why a frame-gen number must never be set beside a native one as if they were the same measurement.',
      ),
    );
  }
  if (opts.cpuBound) {
    penaltyMs += LATENCY_MODEL.cpuBoundPenaltyMs;
    terms.push(term('CPU-bound', `+${LATENCY_MODEL.cpuBoundPenaltyMs} ms`, 'Simulation steps queue behind each other when the CPU is the limiter.'));
  }

  const totalMs = inputMs + queueMs + frameTimeMs + displayMs + penaltyMs;

  terms.unshift(
    term(
      'click-to-photon',
      `${totalMs.toFixed(1)} ms`,
      `${inputMs} ms input stack + ${queueMs.toFixed(1)} ms render queue (${queueFrames} frames) + ${frameTimeMs.toFixed(1)} ms frame time + ${displayMs} ms ${panel} display${penaltyMs ? ` + ${penaltyMs.toFixed(1)} ms penalties` : ''}. This is what a player feels; frame rate alone does not capture it.`,
    ),
  );

  return { totalMs, frameTimeMs, queueMs, inputMs, displayMs, penaltyMs, terms };
}

/** Perceptual banding, so the number means something to a non-specialist. */
export function latencyVerdict(ms: number): { label: string; severity: 'ok' | 'watch' | 'risk' | 'critical' } {
  if (ms <= 25) return { label: 'excellent — competitive', severity: 'ok' };
  if (ms <= 40) return { label: 'good', severity: 'ok' };
  if (ms <= 60) return { label: 'acceptable', severity: 'watch' };
  if (ms <= 90) return { label: 'sluggish', severity: 'risk' };
  return { label: 'poor — noticeably delayed', severity: 'critical' };
}
