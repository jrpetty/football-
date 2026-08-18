/**
 * The estimation engine.
 *
 * Structure:
 *   1. Hard gates run first and short-circuit. WILL_NOT_RUN is not a low number.
 *   2. CPU-bound and GPU-bound FPS are estimated independently.
 *   3. They are combined with a power-mean soft minimum, which models the loss
 *      when both components are near-equal and contending. Math.min() misses it.
 *   4. VRAM cliff, PCIe and storage are applied afterwards as they are
 *      discontinuous or asymmetric between average and 1% low.
 *   5. Every step emits a ModelTerm so the UI can show the full working.
 */
import { COMBINE, LOW1PCT_RATIO, PCIE_MODEL, RAM_MODEL, RT_MODEL, RT_WEIGHT, STORAGE_MODEL, UNCERTAINTY, VRAM_MODEL, CPU_WEIGHTS } from './constants.ts';
import { runGates } from './gates.ts';
import { applyCpuWeights, deriveCpuIndex, deriveGpuIndex } from './indices.ts';
import type {
  Build,
  Confidence,
  CpuRecord,
  FpsEstimate,
  GameRecord,
  GpuRecord,
  ModelTerm,
  RamConfig,
  Resolution,
  UpscalingSetting,
} from './types.ts';
import { CONFIDENCE_RANK, NATIVE, RESOLUTION_PIXELS, UPSCALE_RENDER_SCALE } from './types.ts';

/**
 * Per-game reference performance: the FPS the anchor build achieves when the
 * other side is unconstrained. These are the fitted per-game constants and the
 * single largest source of model error, so they are the calibrator's main
 * target.
 */
export interface GameReference {
  gameId: string;
  /** GPU-bound FPS on the anchor GPU with an unconstrained CPU, per resolution. */
  gpuBound: Partial<Record<Resolution, number>>;
  /** CPU-bound FPS on the anchor CPU with an unconstrained GPU. */
  cpuBound: number;
  /** How steeply this title scales with GPU index. Most sit near 1.0. */
  gpuScalingExponent: number;
  /**
   * Which ray-tracing setting the reference figures represent. Seeded references
   * for aaa-rt titles were captured with RT enabled; everything else is raster.
   * Declaring it lets the engine convert instead of silently comparing an RT-on
   * baseline against a raster query.
   */
  referenceRtTier?: 'on' | 'off';
  confidence: Confidence;
}

export interface EngineData {
  gpus: Map<string, GpuRecord>;
  cpus: Map<string, CpuRecord>;
  games: Map<string, GameRecord>;
  references: Map<string, GameReference>;
  anchorGpu: GpuRecord;
  anchorCpu: CpuRecord;
  anchorRam: RamConfig;
}

export interface EstimateOptions {
  upscaling?: UpscalingSetting;
  /** Ray tracing setting for THIS query. Defaults to off — raster comparison. */
  rtTier?: 'on' | 'off';
}

function weakest(a: Confidence, b: Confidence): Confidence {
  return CONFIDENCE_RANK[a] <= CONFIDENCE_RANK[b] ? a : b;
}

/** Interpolate the VRAM cliff table on deficit ratio. */
function vramCliffMultipliers(deficitRatio: number): { avg: number; low: number } {
  const t = VRAM_MODEL.cliff;
  if (deficitRatio <= 0) return { avg: 1, low: 1 };
  for (let i = 1; i < t.length; i++) {
    if (deficitRatio <= t[i].deficitRatio) {
      const a = t[i - 1];
      const b = t[i];
      const f = (deficitRatio - a.deficitRatio) / (b.deficitRatio - a.deficitRatio);
      return {
        avg: a.avgMultiplier + f * (b.avgMultiplier - a.avgMultiplier),
        low: a.lowMultiplier + f * (b.lowMultiplier - a.lowMultiplier),
      };
    }
  }
  const last = t[t.length - 1];
  return { avg: last.avgMultiplier, low: last.lowMultiplier };
}

/**
 * Power-mean soft minimum.
 *
 * fps = (cpu^-p + gpu^-p)^(-1/p)
 *
 * At equal inputs this returns 2^(-1/p) of either. p is fitted per resolution;
 * see COMBINE for why the spec's stated p=4 and its stated ~10% loss disagree.
 */
export function softMin(cpuFps: number, gpuFps: number, p: number): number {
  if (cpuFps <= 0 || gpuFps <= 0) return 0;
  return Math.pow(Math.pow(cpuFps, -p) + Math.pow(gpuFps, -p), -1 / p);
}

export function estimate(
  build: Build,
  gameId: string,
  resolution: Resolution,
  data: EngineData,
  opts: EstimateOptions = {},
): FpsEstimate {
  const terms: ModelTerm[] = [];
  const gpu = data.gpus.get(build.gpuId);
  const cpu = data.cpus.get(build.cpuId);
  const game = data.games.get(gameId);

  if (!gpu || !cpu || !game) {
    return {
      status: 'WILL_NOT_RUN',
      gateFailures: [],
      confidence: 'gate-blocked',
      terms: [
        {
          label: 'missing record',
          value: 'n/a',
          confidence: 'gate-blocked',
          sources: [],
          explain: `Unknown ${!gpu ? 'GPU' : !cpu ? 'CPU' : 'game'} id in build.`,
        },
      ],
    };
  }

  const upscaling = opts.upscaling ?? build.target.upscaling ?? NATIVE;

  // --- 1. Hard gates ------------------------------------------------------
  const gateFailures = runGates({ gpu, cpu, game, ram: build.ram, resolution });
  if (gateFailures.length > 0) {
    return {
      status: 'WILL_NOT_RUN',
      gateFailures,
      confidence: 'gate-blocked',
      terms: gateFailures.map((f) => ({
        label: f.code,
        value: f.actual,
        confidence: 'gate-blocked' as Confidence,
        sources: [],
        explain: f.detail,
      })),
    };
  }

  const ref = data.references.get(gameId);
  if (!ref) {
    return {
      status: 'WILL_NOT_RUN',
      gateFailures: [],
      confidence: 'gate-blocked',
      terms: [
        {
          label: 'no reference data',
          value: 'n/a',
          confidence: 'gate-blocked',
          sources: [],
          explain: `No calibrated reference performance for ${game.name}. Cannot estimate rather than guess.`,
        },
      ],
    };
  }

  let confidence: Confidence = ref.confidence;
  let extraUncertainty = 0;

  // --- 2. GPU-bound estimate ---------------------------------------------
  // System RAM is passed because integrated graphics draw bandwidth from it.
  const gpuIdx = deriveGpuIndex(gpu, data.anchorGpu, build.ram);
  const cpuIdx = deriveCpuIndex(cpu, build.ram, data.anchorCpu, data.anchorRam);

  for (const s of gpuIdx.steps) {
    terms.push({ label: `gpu: ${s.label}`, value: s.value, confidence: 'spec-derived', sources: gpu._prov?.[''] ?? [], explain: s.explain });
  }
  extraUncertainty += gpuIdx.missingFields.length * UNCERTAINTY.perMissingField;
  extraUncertainty += cpuIdx.missingFields.length * UNCERTAINTY.perMissingField;
  if (gpuIdx.unknownArchitecture || cpuIdx.unknownArchitecture) extraUncertainty += UNCERTAINTY.unknownArchitecture;
  if (gpuIdx.missingFields.length || cpuIdx.missingFields.length) confidence = weakest(confidence, 'spec-derived');

  // Reference FPS at this resolution. If the reference lacks this resolution,
  // scale from 1080p by pixel count rather than dropping the estimate.
  let refGpuFps = ref.gpuBound[resolution];
  if (refGpuFps == null) {
    const base = ref.gpuBound['1080p'];
    if (base == null) {
      return {
        status: 'WILL_NOT_RUN',
        gateFailures: [],
        confidence: 'gate-blocked',
        terms: [{ label: 'no reference', value: 'n/a', confidence: 'gate-blocked', sources: [], explain: 'Reference has no 1080p baseline to scale from.' }],
      };
    }
    const pixelRatio = RESOLUTION_PIXELS[resolution] / RESOLUTION_PIXELS['1080p'];
    // GPU load scales slightly sublinearly with pixels (fixed per-frame costs).
    refGpuFps = base / Math.pow(pixelRatio, 0.88);
    confidence = weakest(confidence, 'interpolated');
    terms.push({
      label: 'resolution interpolation',
      value: Number(refGpuFps.toFixed(1)),
      confidence: 'interpolated',
      sources: [],
      explain: `No measured reference at ${resolution}; scaled from 1080p by pixel ratio ${pixelRatio.toFixed(2)}^0.88.`,
    });
  }

  // Upscaling reduces render resolution, which moves the GPU-bound side only.
  const renderScale = UPSCALE_RENDER_SCALE[upscaling.quality];
  let upscaleGain = 1;
  if (upscaling.tech !== 'none' && renderScale < 1) {
    // Pixel count scales with the square of the linear scale factor.
    upscaleGain = Math.pow(1 / (renderScale * renderScale), 0.85);
    terms.push({
      label: 'upscaling',
      value: `${upscaling.tech.toUpperCase()} ${upscaling.quality}`,
      confidence: 'spec-derived',
      sources: [],
      explain: `Render scale ${renderScale.toFixed(3)} linear; GPU-bound FPS x${upscaleGain.toFixed(2)}. CPU-bound side is unaffected, which is why upscaling cannot lift a CPU-limited build.`,
    });
  }

  /**
   * Effective GPU index blends raster and RT throughput by how much of the
   * title's work runs through the RT path. RT and raster rank differently by
   * vendor, so using raster alone would overrate RDNA 2/3 in ray-traced titles.
   *
   * A part with no RT hardware in an RT-OPTIONAL title falls back to pure raster:
   * the game runs with the effects off. RT-REQUIRED titles never reach here —
   * the gate blocks them.
   */
  const wantRt = (opts.rtTier ?? 'off') === 'on' && gpu.caps.rayTracing;
  const refIsRtOn =
    ref.referenceRtTier === 'on' ||
    (ref.referenceRtTier == null && RT_MODEL.referenceRtOn.includes(game.archetype));

  // Convert the reference between RT-on and RT-off baselines. Skipping this step
  // under-predicted every ray-traced title by 55-80% against the fixture set.
  if (refIsRtOn && !wantRt) {
    refGpuFps = refGpuFps / RT_MODEL.onVsOff;
    terms.push({
      label: 'RT baseline conversion',
      value: Number(refGpuFps.toFixed(1)),
      confidence: 'interpolated',
      sources: [],
      explain: `The reference for ${game.name} was captured with ray tracing ENABLED, but this query is raster. Divided by ${RT_MODEL.onVsOff} to recover the RT-off baseline. Mixing the two without converting is the settings-drift failure the fingerprint rules exist to prevent.`,
    });
    confidence = weakest(confidence, 'interpolated');
  } else if (!refIsRtOn && wantRt) {
    refGpuFps = refGpuFps * RT_MODEL.onVsOff;
    terms.push({
      label: 'RT baseline conversion',
      value: Number(refGpuFps.toFixed(1)),
      confidence: 'interpolated',
      sources: [],
      explain: `Reference is raster; this query enables ray tracing. Multiplied by ${RT_MODEL.onVsOff}.`,
    });
    confidence = weakest(confidence, 'interpolated');
  }

  // The raster/RT index blend applies only when RT is actually enabled.
  const rtWeight = wantRt ? (RT_WEIGHT[game.archetype] ?? 0) : 0;
  let effectiveGpuIndex = gpuIdx.index.raster;
  if (rtWeight > 0 && gpu.caps.rayTracing && gpuIdx.index.rt > 0) {
    effectiveGpuIndex = Math.pow(gpuIdx.index.raster, 1 - rtWeight) * Math.pow(gpuIdx.index.rt, rtWeight);
    terms.push({
      label: 'raster/RT blend',
      value: Number(effectiveGpuIndex.toFixed(1)),
      confidence: 'spec-derived',
      sources: [],
      explain: `${game.archetype} runs ${(rtWeight * 100).toFixed(0)}% of its GPU work through the RT path: raster ${gpuIdx.index.raster.toFixed(1)}^${(1 - rtWeight).toFixed(2)} x RT ${gpuIdx.index.rt.toFixed(1)}^${rtWeight.toFixed(2)}. RT throughput per TFLOP differs sharply by vendor, so raster alone would misrank this.`,
    });
  } else if ((opts.rtTier ?? 'off') === 'on' && !gpu.caps.rayTracing) {
    terms.push({
      label: 'RT path',
      value: 'unavailable',
      confidence: 'spec-derived',
      sources: [],
      explain: `Ray tracing was requested but ${gpu.fullName} has no RT hardware, so this estimate is for the title running with ray tracing disabled. The frame rate is comparable; the visual output is not.`,
    });
  }

  const gpuBoundFps = refGpuFps * Math.pow(effectiveGpuIndex / 100, ref.gpuScalingExponent) * upscaleGain;
  terms.push({
    label: 'GPU-bound FPS',
    value: Number(gpuBoundFps.toFixed(1)),
    confidence: ref.confidence,
    sources: [],
    explain: `Reference ${refGpuFps.toFixed(1)} fps x (index ${effectiveGpuIndex.toFixed(1)}/100)^${ref.gpuScalingExponent} x upscale ${upscaleGain.toFixed(2)}`,
  });

  // --- 3. CPU-bound estimate ---------------------------------------------
  const weights = game.cpuWeightOverride
    ? { ...CPU_WEIGHTS[game.archetype], ...game.cpuWeightOverride }
    : CPU_WEIGHTS[game.archetype];
  const cpuScalar = applyCpuWeights(cpuIdx.index, weights);

  for (const s of cpuIdx.steps) {
    terms.push({ label: `cpu: ${s.label}`, value: s.value, confidence: 'spec-derived', sources: [], explain: s.explain });
  }
  terms.push({
    label: 'cpu: archetype weighting',
    value: Number(cpuScalar.toFixed(1)),
    confidence: 'spec-derived',
    sources: [],
    explain: `${game.archetype} weights — throughput^${weights.throughput}, cache^${weights.cacheEndowment}, latency^${weights.latencyScore}, threads^${weights.threadCapacity}. A scalar CPU index would rank these wrong at the tails.`,
  });

  const channelMult = RAM_MODEL.channelMultiplier[build.ram.channels] ?? 1;
  if (channelMult !== 1) {
    terms.push({
      label: 'memory channels',
      value: `${build.ram.channels}-channel`,
      confidence: 'spec-derived',
      sources: [],
      explain: `CPU-bound FPS x${channelMult}. Single-channel memory starves the CPU of bandwidth and is one of the most under-modelled failure cases in build advice.`,
    });
  }

  let capacityMult = 1;
  for (const rule of RAM_MODEL.capacityPenalty) {
    if (build.ram.totalGB < rule.belowGB) {
      capacityMult = Math.min(capacityMult, rule.multiplier);
    }
  }
  if (capacityMult !== 1) {
    terms.push({
      label: 'memory capacity',
      value: `${build.ram.totalGB}GB`,
      confidence: 'spec-derived',
      sources: [],
      explain: `Below comfortable capacity for this title; x${capacityMult} on the CPU-bound side from paging and streaming stalls.`,
    });
  }

  const cpuBoundFps = ref.cpuBound * (cpuScalar / 100) * channelMult * capacityMult;
  terms.push({
    label: 'CPU-bound FPS',
    value: Number(cpuBoundFps.toFixed(1)),
    confidence: ref.confidence,
    sources: [],
    explain: `Reference ${ref.cpuBound} fps x weighted index ${(cpuScalar / 100).toFixed(3)} x memory modifiers`,
  });

  // --- 4. Combine ---------------------------------------------------------
  const p = COMBINE.pByResolution[resolution] ?? COMBINE.p;
  let fps = softMin(cpuBoundFps, gpuBoundFps, p);
  const contentionLoss = 1 - fps / Math.min(cpuBoundFps, gpuBoundFps);
  terms.push({
    label: 'soft minimum',
    value: Number(fps.toFixed(1)),
    confidence: ref.confidence,
    sources: [],
    explain: `(cpu^-${p} + gpu^-${p})^(-1/${p}). Contention loss vs a hard min: ${(contentionLoss * 100).toFixed(1)}%. A hard Math.min() would miss this entirely.`,
  });

  // --- 5. VRAM cliff ------------------------------------------------------
  const demand = game.vramDemandGB[resolution];
  let vramAvg = 1;
  let vramLow = 1;
  let vramLimited = false;
  if (demand != null && gpu.vramGB != null) {
    // Upscaling reduces framebuffer pressure somewhat, but not texture pools.
    const effectiveDemand = upscaling.tech !== 'none' ? demand * 0.93 : demand;
    const deficitRatio = (effectiveDemand - gpu.vramGB) / effectiveDemand;
    if (deficitRatio > 0) {
      const m = vramCliffMultipliers(deficitRatio);
      vramAvg = m.avg;
      vramLow = m.low;
      vramLimited = true;
      extraUncertainty += UNCERTAINTY.vramCliff;
      terms.push({
        label: 'VRAM cliff',
        value: `${gpu.vramGB}GB vs ${effectiveDemand.toFixed(1)}GB demand`,
        confidence: 'interpolated',
        sources: [],
        explain: `Deficit ratio ${(deficitRatio * 100).toFixed(0)}%. Average x${vramAvg.toFixed(2)}, 1% low x${vramLow.toFixed(2)}. This is a discontinuity, applied as a piecewise penalty rather than a regression term — and it hits 1% lows far harder than averages.`,
      });
    } else if (gpu.vramGB - effectiveDemand < VRAM_MODEL.warnHeadroomGB) {
      terms.push({
        label: 'VRAM headroom',
        value: `${(gpu.vramGB - effectiveDemand).toFixed(1)}GB`,
        confidence: 'interpolated',
        sources: [],
        explain: 'Within warning headroom. No penalty applied, but a texture-pack or patch would push this over the cliff.',
      });
    }
  }

  // --- 6. PCIe ------------------------------------------------------------
  const linkGen = Math.min(build.motherboard?.pcieVersion ?? gpu.pcieGen ?? 4, gpu.pcieGen ?? 4);
  const linkLanes = Math.min(build.motherboard?.pcieLanesToGPU ?? gpu.pcieLanes ?? 16, gpu.pcieLanes ?? 16);
  const pcieKey = `${linkGen}-${linkLanes}`;
  let pcieMult = PCIE_MODEL.bandwidth[pcieKey] ?? 1;
  if (pcieMult !== 1) {
    if (vramLimited) {
      // Over-subscribed VRAM streams over the bus, so a narrow link compounds.
      pcieMult = 1 - (1 - pcieMult) * PCIE_MODEL.vramOversubscribeAmplifier;
      pcieMult = Math.max(pcieMult, 0.3);
    }
    terms.push({
      label: 'PCIe link',
      value: `gen${linkGen} x${linkLanes}`,
      confidence: 'spec-derived',
      sources: [],
      explain: `x${pcieMult.toFixed(3)}${vramLimited ? ' — amplified because VRAM is over-subscribed and textures stream across the bus.' : ''}`,
    });
  }

  const storageAvg = STORAGE_MODEL.avgMultiplier[build.storage] ?? 1;
  const storageLow = STORAGE_MODEL.low1PctMultiplier[build.storage] ?? 1;

  fps = fps * vramAvg * pcieMult * storageAvg;

  // --- 7. Engine caps -----------------------------------------------------
  let limiter: FpsEstimate['limiter'];
  if (game.fpsCap != null && fps > game.fpsCap) {
    fps = game.fpsCap;
    limiter = 'engine-cap';
    terms.push({
      label: 'engine cap',
      value: game.fpsCap,
      confidence: 'measured',
      sources: [],
      explain: `${game.name} is capped at ${game.fpsCap} fps by the engine. Extra GPU headroom buys nothing here.`,
    });
  }

  // --- 8. 1% lows ---------------------------------------------------------
  const lowRatio = LOW1PCT_RATIO[game.archetype];
  const low1Pct = fps * lowRatio * (vramLow / Math.max(vramAvg, 0.01)) * storageLow;
  terms.push({
    label: '1% low',
    value: Number(low1Pct.toFixed(1)),
    confidence: weakest(ref.confidence, 'interpolated'),
    sources: [],
    explain: `Average x${lowRatio} archetype ratio, then storage and VRAM effects which hit lows disproportionately.`,
  });

  // --- 9. Limiter and uncertainty ----------------------------------------
  const gpuBoundRatio = cpuBoundFps / (cpuBoundFps + gpuBoundFps);
  if (!limiter) {
    if (vramLimited && vramAvg < 0.8) limiter = 'vram';
    else if (gpuBoundFps < cpuBoundFps * 0.9) limiter = 'gpu';
    else if (cpuBoundFps < gpuBoundFps * 0.9) limiter = 'cpu';
    else limiter = 'balanced';
  }

  if (gpu.formFactor === 'igpu') {
    extraUncertainty += UNCERTAINTY.igpu;
    terms.push({
      label: 'iGPU caveat',
      value: 'shared TDP and memory',
      confidence: 'extrapolated',
      sources: [],
      explain: 'Integrated graphics share power budget and memory bandwidth with the CPU, so the separable CPU/GPU model is weaker here. Band widened accordingly.',
    });
    confidence = weakest(confidence, 'extrapolated');
  }

  const uncertainty = Math.min(UNCERTAINTY.max, (UNCERTAINTY.base[confidence] ?? 0.2) + extraUncertainty);

  return {
    status: 'ok',
    gateFailures: [],
    avgFps: fps,
    low1PctFps: low1Pct,
    uncertainty,
    band: { low: fps * (1 - uncertainty), high: fps * (1 + uncertainty) },
    confidence,
    cpuBoundFps,
    gpuBoundFps,
    gpuBoundRatio,
    limiter,
    terms,
  };
}
