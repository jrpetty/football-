/**
 * Index derivation: hardware records -> comparable performance indices.
 *
 * Both indices are anchored at 100 on the reference parts. The anchor fixes an
 * arbitrary global scale only; it is not a modelling choice.
 */
import {
  IGPU_MODEL,
  CPU_IPC,
  CPU_MODEL,
  DEFAULT_CPU_IPC,
  DEFAULT_GPU_ARCH_EFFICIENCY,
  GPU_ARCH_EFFICIENCY,
  GPU_MODEL,
  GPU_RT_EFFICIENCY,
} from './constants.ts';
import type { CpuIndexVector, CpuRecord, GpuIndex, GpuRecord, RamConfig } from './types.ts';

export interface Derivation {
  /** Fields the record was missing, each of which widens the uncertainty band. */
  missingFields: string[];
  /** True when the architecture had no fitted efficiency constant. */
  unknownArchitecture: boolean;
  /** Human-readable working, shown in the "how was this calculated" panel. */
  steps: { label: string; value: number | string; explain: string }[];
}

// ---------------------------------------------------------------------------
// GPU
// ---------------------------------------------------------------------------

/** Nominal FP32 throughput in TFLOPS from shader count and clock. */
export function nominalTflops(gpu: GpuRecord): number | null {
  if (gpu.fp32TFLOPS != null) return gpu.fp32TFLOPS;
  if (gpu.shaders != null && gpu.boostClockMHz != null) {
    return (gpu.shaders * gpu.boostClockMHz * 2) / 1e6;
  }
  return null;
}

/**
 * Effective memory bandwidth including on-die cache multipliers.
 *
 * Integrated graphics have no dedicated memory: they draw from system RAM, so
 * their bandwidth is a property of the BUILD, not the part. This is why a
 * single-channel configuration costs an iGPU far more than it costs a discrete
 * card — it halves the GPU's bandwidth outright, not just the CPU's.
 */
export function effectiveBandwidth(gpu: GpuRecord, systemRam?: RamConfig): number | null {
  if (gpu.formFactor === 'igpu') {
    if (!systemRam) return null;
    // GB/s = channels x MT/s x 8 bytes per transfer / 1000.
    //
    // Deliberately NO on-die cache multiplier here: an RDNA 3 iGPU shares an
    // architecture name with cards that carry Infinity Cache but has nothing
    // comparable of its own. Applying the discrete multiplier over-predicted
    // integrated parts by roughly 2x in validation.
    //
    // The bandwidthShare derate is contention: the CPU consumes a share of the
    // same memory controller exactly when frame rates — and therefore draw-call
    // traffic — are highest. Without it, iGPU esports fixtures overpredicted
    // by 1.5-2x while iGPU AAA fixtures were close.
    return ((systemRam.channels * systemRam.speedMTs * 8) / 1000) * IGPU_MODEL.bandwidthShare;
  }
  if (gpu.memBandwidthGBs == null) return null;
  const mult = GPU_MODEL.cacheBandwidthMultiplier[gpu.architecture] ?? 1.0;
  return gpu.memBandwidthGBs * mult;
}

export interface GpuIndexResult extends Derivation {
  index: GpuIndex;
}

/**
 * Raster index = 100 * ((effCompute/ref)^w * (effBw/ref)^(1-w))^sublinear
 *
 * The architecture efficiency term is what stops nominal FLOPs lying: Ampere
 * and RDNA 3 double-count FP32 lanes games cannot saturate.
 */
export function deriveGpuIndex(gpu: GpuRecord, reference: GpuRecord, systemRam?: RamConfig): GpuIndexResult {
  const missingFields: string[] = [];
  const steps: Derivation['steps'] = [];

  const archEff = GPU_ARCH_EFFICIENCY[gpu.architecture];
  const unknownArchitecture = archEff === undefined;
  const eff = archEff ?? DEFAULT_GPU_ARCH_EFFICIENCY;

  const tflops = nominalTflops(gpu);
  const refTflops = nominalTflops(reference);
  if (tflops == null) missingFields.push('fp32TFLOPS/shaders/boostClockMHz');

  const bw = effectiveBandwidth(gpu, systemRam);
  const refBw = effectiveBandwidth(reference);
  if (bw == null) missingFields.push(gpu.formFactor === 'igpu' ? 'system RAM configuration' : 'memBandwidthGBs');

  const refEff = GPU_ARCH_EFFICIENCY[reference.architecture] ?? DEFAULT_GPU_ARCH_EFFICIENCY;

  if (tflops == null || refTflops == null) {
    return {
      index: { raster: 0, rt: 0 },
      missingFields,
      unknownArchitecture,
      steps: [{ label: 'insufficient data', value: 0, explain: 'No compute figure available; cannot derive an index.' }],
    };
  }

  // Package-TDP sharing: an iGPU cannot hold its boost clock under combined
  // CPU+GPU load, so its usable compute is derated below nameplate.
  const sustain = gpu.formFactor === 'igpu' ? IGPU_MODEL.sustainedClockFactor : 1;
  const effCompute = tflops * eff * sustain;
  const refEffCompute = refTflops * refEff;
  steps.push({
    label: 'nominal FP32',
    value: Number(tflops.toFixed(2)),
    explain: `${gpu.shaders ?? '?'} shaders x ${gpu.boostClockMHz ?? '?'} MHz x 2 / 1e6`,
  });
  if (sustain !== 1) {
    steps.push({
      label: 'sustained clock derate',
      value: sustain,
      explain: 'Integrated graphics share the package power budget with the CPU; boost clocks do not sustain under combined load.',
    });
  }
  steps.push({
    label: 'architecture efficiency',
    value: eff,
    explain: `Gaming throughput per nominal TFLOP for ${gpu.architecture}, relative to Ampere = 1.00.${
      unknownArchitecture ? ' No fitted constant for this architecture; defaulted.' : ''
    }`,
  });

  const computeRatio = effCompute / refEffCompute;
  // If bandwidth is unknown, fall back to compute alone rather than inventing a
  // figure. The missing field already widens the band.
  const bwRatio = bw != null && refBw != null ? bw / refBw : computeRatio;
  if (bw == null) {
    steps.push({
      label: 'bandwidth',
      value: 'unavailable',
      explain: 'Memory bandwidth unknown; composite falls back to the compute ratio and confidence is reduced.',
    });
  } else if (gpu.formFactor === 'igpu' && systemRam) {
    steps.push({
      label: 'effective bandwidth (shared)',
      value: Number(bw.toFixed(1)),
      explain: `Integrated graphics draw from system RAM: ${systemRam.channels} channel(s) x ${systemRam.speedMTs} MT/s x 8B / 1000 = ${bw.toFixed(1)} GB/s. A single-channel configuration halves this and is the dominant variable for iGPU performance.`,
    });
  } else {
    steps.push({
      label: 'effective bandwidth',
      value: Number(bw.toFixed(0)),
      explain: `${gpu.memBandwidthGBs} GB/s x ${
        GPU_MODEL.cacheBandwidthMultiplier[gpu.architecture] ?? 1
      } on-die cache multiplier = GB/s effective`,
    });
  }

  const w = GPU_MODEL.computeWeight;
  const composite = Math.pow(computeRatio, w) * Math.pow(bwRatio, 1 - w);
  const raster = 100 * Math.pow(composite, GPU_MODEL.sublinearExponent);

  steps.push({
    label: 'composite vs reference',
    value: Number(composite.toFixed(3)),
    explain: `computeRatio^${w} x bandwidthRatio^${(1 - w).toFixed(2)}`,
  });
  steps.push({
    label: 'raster index',
    value: Number(raster.toFixed(1)),
    explain: `composite^${GPU_MODEL.sublinearExponent} x 100. Sublinear because doubling GPU resources yields ~1.8x, not 2x.`,
  });

  const rtEff = GPU_RT_EFFICIENCY[gpu.architecture];
  const refRtEff = GPU_RT_EFFICIENCY[reference.architecture] ?? 1.0;
  const rt = gpu.caps.rayTracing && rtEff != null
    ? 100 * Math.pow((tflops * rtEff) / (refTflops * refRtEff), GPU_MODEL.sublinearExponent)
    : 0;

  return { index: { raster, rt }, missingFields, unknownArchitecture, steps };
}

// ---------------------------------------------------------------------------
// CPU
// ---------------------------------------------------------------------------

/** Effective DRAM latency in nanoseconds, including the uncore floor. */
export function effectiveLatencyNs(cpu: CpuRecord, ram: RamConfig): number {
  const uncore = CPU_MODEL.uncoreLatencyNs[cpu.architecture] ?? CPU_MODEL.defaultUncoreLatencyNs;
  // CAS latency in ns = CL * 2000 / MT/s. Without timings, assume a typical
  // bin for the memory generation rather than inventing a specific kit.
  const cl = ram.timings?.cl ?? defaultCl(ram.speedMTs);
  const dramNs = (cl * 2000) / ram.speedMTs;
  return uncore + dramNs;
}

function defaultCl(speedMTs: number): number {
  if (speedMTs >= 7200) return 36;
  if (speedMTs >= 6000) return 30;
  if (speedMTs >= 5200) return 40;
  if (speedMTs >= 3600) return 18;
  if (speedMTs >= 3200) return 16;
  if (speedMTs >= 2666) return 16;
  if (speedMTs >= 2133) return 15;
  return 11;
}

export interface CpuIndexResult extends Derivation {
  index: CpuIndexVector;
}

/**
 * L3 cache visible to a single game thread, per core.
 *
 * Package-total L3 divided by total cores is WRONG for multi-CCD AMD parts, and
 * wrong in the direction that matters most. A 7950X3D has 128MB across two
 * chiplets, but the stack sits on one CCD only: 96MB on the cache CCD plus a
 * plain 32MB on the other. A game thread parked on the cache CCD sees 96MB
 * shared by 8 cores — identical to a 7800X3D. Dividing 128 by 16 would rank the
 * 7950X3D as LESS cache-endowed than the 7800X3D, which inverts the answer in
 * exactly the cache-sensitive titles the vector model exists to get right.
 */
export function gameVisibleL3PerCore(cpu: CpuRecord): { perCore: number | null; explain: string } {
  if (cpu.l3CacheMB == null) {
    return { perCore: null, explain: 'L3 unknown; defaulted to the reference endowment.' };
  }

  // Intel desktop parts are monolithic: the whole L3 is shared by all cores.
  if (cpu.vendor === 'intel') {
    const perCore = cpu.l3CacheMB / cpu.cores;
    return {
      perCore,
      explain: `${cpu.l3CacheMB}MB shared L3 / ${cpu.cores} cores = ${perCore.toFixed(1)}MB per core`,
    };
  }

  // AMD chiplet parts: L3 is per-CCD and not shared across chiplets.
  const ccdCount = cpu.cores > 8 ? 2 : 1;
  if (cpu.vcache) {
    // The stacked CCD carries 96MB across up to 8 cores on every shipping X3D
    // part; the scheduler parks game threads there.
    const coresOnCacheCcd = Math.min(cpu.cores, 8);
    const perCore = 96 / coresOnCacheCcd;
    return {
      perCore,
      explain:
        ccdCount > 1
          ? `3D V-Cache part: 96MB on the stacked CCD / ${coresOnCacheCcd} cores on that CCD = ${perCore.toFixed(1)}MB per core. The ${cpu.l3CacheMB}MB package total is never visible to one thread, so it is not used here.`
          : `3D V-Cache part: 96MB / ${coresOnCacheCcd} cores = ${perCore.toFixed(1)}MB per core`,
    };
  }
  const perCcd = cpu.l3CacheMB / ccdCount;
  const coresPerCcd = cpu.cores / ccdCount;
  const perCore = perCcd / coresPerCcd;
  return {
    perCore,
    explain:
      ccdCount > 1
        ? `${cpu.l3CacheMB}MB across ${ccdCount} CCDs = ${perCcd}MB per CCD / ${coresPerCcd} cores = ${perCore.toFixed(1)}MB per core (L3 is not shared across chiplets)`
        : `${cpu.l3CacheMB}MB L3 / ${cpu.cores} cores = ${perCore.toFixed(1)}MB per core`,
  };
}

export function deriveCpuIndex(cpu: CpuRecord, ram: RamConfig, reference: CpuRecord, referenceRam: RamConfig): CpuIndexResult {
  const missingFields: string[] = [];
  const steps: Derivation['steps'] = [];

  const ipc = CPU_IPC[cpu.architecture];
  const unknownArchitecture = ipc === undefined;
  const ipcVal = ipc ?? DEFAULT_CPU_IPC;

  const clock = cpu.boostClockMHz ?? cpu.baseClockMHz;
  if (cpu.boostClockMHz == null) missingFields.push('boostClockMHz');
  if (clock == null) {
    return {
      index: { throughput: 0, cacheEndowment: 1, latencyScore: 1, threadCapacity: 1 },
      missingFields: [...missingFields, 'baseClockMHz'],
      unknownArchitecture,
      steps: [{ label: 'insufficient data', value: 0, explain: 'No clock figure available.' }],
    };
  }

  const threadTerm = Math.pow(Math.min(cpu.threads, CPU_MODEL.threadCeiling), CPU_MODEL.threadExponent);
  // Bulldozer-family module pairs share a front end and FPU, so marketed core
  // count overstates game throughput badly.
  const fpuPenalty = /Bulldozer|Piledriver|Excavator/.test(cpu.architecture) ? CPU_MODEL.sharedFpuPenalty : 1;

  const raw = ipcVal * (clock / 1000) * threadTerm * fpuPenalty;

  const refIpc = CPU_IPC[reference.architecture] ?? DEFAULT_CPU_IPC;
  const refClock = reference.boostClockMHz ?? reference.baseClockMHz ?? 4200;
  const refThreadTerm = Math.pow(Math.min(reference.threads, CPU_MODEL.threadCeiling), CPU_MODEL.threadExponent);
  const refRaw = refIpc * (refClock / 1000) * refThreadTerm;

  const throughput = (raw / refRaw) * 100;

  steps.push({ label: 'IPC', value: ipcVal, explain: `${cpu.architecture} per-clock game throughput, Zen 2 = 1.00.` });
  steps.push({ label: 'boost clock', value: clock, explain: 'MHz' });
  steps.push({
    label: 'thread term',
    value: Number(threadTerm.toFixed(3)),
    explain: `min(${cpu.threads}, ${CPU_MODEL.threadCeiling})^${CPU_MODEL.threadExponent} — games scale strongly sublinearly with threads.`,
  });
  if (fpuPenalty !== 1) {
    steps.push({
      label: 'shared-FPU penalty',
      value: fpuPenalty,
      explain: 'Bulldozer-family modules share a front end and FPU; marketed core count overstates game throughput.',
    });
  }
  steps.push({ label: 'throughput index', value: Number(throughput.toFixed(1)), explain: 'vs reference = 100' });

  const l3 = cpu.l3CacheMB;
  if (l3 == null) missingFields.push('l3CacheMB');
  const cache = gameVisibleL3PerCore(cpu);
  const l3PerCore = cache.perCore ?? CPU_MODEL.refL3PerCore;
  // Square-root compression: cache benefit saturates, so a raw per-core ratio
  // over-rewards X3D parts and over-punishes small-cache parts at both tails.
  const cacheEndowment = Math.sqrt(l3PerCore / CPU_MODEL.refL3PerCore);
  steps.push({
    label: 'cache endowment',
    value: Number(cacheEndowment.toFixed(2)),
    explain: `${cache.explain} vs reference ${CPU_MODEL.refL3PerCore}MB per core, square-root compressed because cache benefit saturates.`,
  });

  const latNs = effectiveLatencyNs(cpu, ram);
  const refLatNs = effectiveLatencyNs(reference, referenceRam);
  const latencyScore = refLatNs / latNs; // lower ns is better, so invert
  steps.push({
    label: 'effective memory latency',
    value: Number(latNs.toFixed(1)),
    explain: `${CPU_MODEL.uncoreLatencyNs[cpu.architecture] ?? CPU_MODEL.defaultUncoreLatencyNs}ns uncore + DRAM CAS at ${ram.speedMTs} MT/s. Reference ${refLatNs.toFixed(1)}ns.`,
  });

  const threadCapacity = threadTerm / refThreadTerm;

  return {
    index: { throughput, cacheEndowment, latencyScore, threadCapacity },
    missingFields,
    unknownArchitecture,
    steps,
  };
}

/**
 * Collapse the CPU vector to a single scalar for one workload archetype.
 *
 * Deliberately never exposed as a global "CPU ranking": the ratio between two
 * CPUs changes sign by archetype, so a global ranking would be wrong at exactly
 * the tails the product exists to answer.
 */
export function applyCpuWeights(v: CpuIndexVector, w: { throughput: number; cacheEndowment: number; latencyScore: number; threadCapacity: number }): number {
  return (
    Math.pow(v.throughput / 100, w.throughput) *
    Math.pow(Math.max(v.cacheEndowment, 0.05), w.cacheEndowment) *
    Math.pow(Math.max(v.latencyScore, 0.05), w.latencyScore) *
    Math.pow(Math.max(v.threadCapacity, 0.05), w.threadCapacity) *
    100
  );
}
