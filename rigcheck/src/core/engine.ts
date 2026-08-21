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
import { PRESET_MODEL, COMBINE, FRAMEGEN_MODEL, LOW_END_MODEL, LOW1PCT_RATIO, PCIE_MODEL, RAM_MODEL, RT_MODEL, RT_WEIGHT, STORAGE_MODEL, STUTTER_MODEL, UNCERTAINTY, VRAM_MODEL, CPU_WEIGHTS } from './constants.ts';
import { estimateThermals } from './physics.ts';
import { normalisePreset, presetEffect } from './presets.ts';
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
  /**
   * Graphics preset. Defaults to the reference preset ('high'), which is the
   * identity — an unspecified query behaves exactly as it did before presets
   * existed. See PRESET_MODEL for why these multipliers are priors and not
   * fitted values.
   */
  preset?: string;
  /** Ray tracing setting for THIS query. Defaults to off — raster comparison. */
  rtTier?: 'on' | 'off';
  /**
   * Case airflow. Defaults to 'good' so an unspecified build is not silently
   * penalised — but a restricted case is a real and commonly ignored cause of a
   * machine underperforming its parts list.
   */
  airflowTier?: 'restricted' | 'moderate' | 'good' | 'excellent';
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
/** True when the CPU side is the binding constraint by a clear margin. */
function limiterIsCpu(cpuFps: number, gpuFps: number): boolean {
  return cpuFps < gpuFps * 0.95;
}

export function softMin(cpuFps: number, gpuFps: number, p: number): number {
  if (cpuFps <= 0 || gpuFps <= 0) return 0;
  return Math.pow(Math.pow(cpuFps, -p) + Math.pow(gpuFps, -p), -1 / p);
}

/** The record fields `deriveGpuIndex` reads, for crediting the right sources. */
const GPU_INDEX_INPUTS = ['shaders', 'boostClockMHz', 'architecture', 'memBandwidthGBs', 'fp32TFLOPS'] as const;

/**
 * Distinct provenance ids across a set of fields, in first-seen order.
 *
 * Falls back to the record-level `'*'` key, which is what the spec parser
 * stamps when it could not attribute a value field by field.
 */
function provenanceFor(prov: Record<string, string[]> | undefined, fields: readonly string[]): string[] {
  if (!prov) return [];
  const out: string[] = [];
  for (const f of [...fields, '*']) {
    for (const id of prov[f] ?? []) if (!out.includes(id)) out.push(id);
  }
  return out;
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

  // Provenance for the graphics terms.
  //
  // This used to read `gpu._prov?.['']`. No record has an empty-string key, so
  // every graphics term in the "how was this calculated" panel carried an empty
  // source list — the attribution silently showed nothing, on the one screen
  // whose entire purpose is showing where a number came from.
  //
  // Keyed by the fields the index derivation actually consumes, so what is
  // credited is what was used, rather than every field on the record.
  const gpuSources = provenanceFor(gpu._prov, GPU_INDEX_INPUTS);
  for (const s of gpuIdx.steps) {
    terms.push({ label: `gpu: ${s.label}`, value: s.value, confidence: 'spec-derived', sources: gpuSources, explain: s.explain });
  }
  // A zero index means the derivation had nothing to work with (nulled clocks
  // or shader counts awaiting harvest). Multiplying through it would emit
  // "0 fps" with status ok — a guess dressed as a measurement. Refuse instead,
  // and say which part and which fields.
  if (gpuIdx.index.raster <= 0 || cpuIdx.index.throughput <= 0) {
    const part = gpuIdx.index.raster <= 0 ? gpu : cpu;
    const missing = gpuIdx.index.raster <= 0 ? gpuIdx.missingFields : cpuIdx.missingFields;
    return {
      status: 'NO_ESTIMATE',
      gateFailures: [],
      confidence: 'gate-blocked',
      terms: [
        {
          label: 'insufficient specification data',
          value: part.fullName,
          confidence: 'gate-blocked',
          sources: [],
          explain: `The catalogue record for ${part.fullName} is missing ${missing.join(', ') || 'the fields needed to derive an index'}. The title runs on this hardware; the model refuses to invent a number for it. Harvesting specification sources fills this.`,
        },
      ],
    };
  }

  // Sustained clocks, not boost clocks. The catalogue's clock figures assume the
  // cooling can hold boost; a stock cooler in a restricted case cannot, and the
  // gap between a benchmark run and twenty minutes of play lives here.
  const thermal = estimateThermals(gpu, cpu, build, opts.airflowTier ?? 'good');
  const thermalFactor = Math.min(thermal.cpuClockFactor, thermal.gpuClockFactor);
  if (thermal.throttling) {
    for (const t of thermal.terms) {
      terms.push({ label: `thermal: ${t.label}`, value: t.value, confidence: 'spec-derived', sources: [], explain: t.explain });
    }
  }

  extraUncertainty += gpuIdx.missingFields.length * UNCERTAINTY.perMissingField;
  extraUncertainty += cpuIdx.missingFields.length * UNCERTAINTY.perMissingField;
  if (gpuIdx.unknownArchitecture || cpuIdx.unknownArchitecture) extraUncertainty += UNCERTAINTY.unknownArchitecture;
  if (gpuIdx.missingFields.length || cpuIdx.missingFields.length) confidence = weakest(confidence, 'spec-derived');

  // Preset multipliers. `high` is the reference and multiplies by exactly 1,
  // so an unspecified preset changes nothing.
  const preset = normalisePreset(opts.preset);
  const presetEff = presetEffect(preset, game.archetype);
  if (presetEff.steps !== 0) {
    extraUncertainty += Math.abs(presetEff.steps) * PRESET_MODEL.uncertaintyPerStep;
    confidence = weakest(confidence, 'interpolated');
  }

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

  // Piecewise scaling: the title's own exponent above the floor, reverting
  // toward linear beneath it. Applied as two segments so the curve stays
  // continuous at the floor rather than stepping.
  const idxRatio = effectiveGpuIndex / 100;
  const floorRatio = LOW_END_MODEL.indexFloor / 100;
  let scaledIndex: number;
  if (idxRatio >= floorRatio) {
    scaledIndex = Math.pow(idxRatio, ref.gpuScalingExponent);
  } else {
    const atFloor = Math.pow(floorRatio, ref.gpuScalingExponent);
    scaledIndex = atFloor * Math.pow(idxRatio / floorRatio, LOW_END_MODEL.floorExponent);
    terms.push({
      label: 'low-end scaling',
      value: Number((scaledIndex * 100).toFixed(1)),
      confidence: 'interpolated',
      sources: [],
      explain: `GPU index ${effectiveGpuIndex.toFixed(0)} is below the ${LOW_END_MODEL.indexFloor} floor, so scaling reverts toward linear (exponent ${LOW_END_MODEL.floorExponent}) instead of this title's usual ${ref.gpuScalingExponent}. A draw-call-bound title barely rewards extra GPU power at the top of the range, but at the bottom the GPU genuinely is the wall.`,
    });
  }

  const gpuBoundFps = refGpuFps * scaledIndex * upscaleGain * thermal.gpuClockFactor * presetEff.gpu;
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

  const cpuBoundFps =
    ref.cpuBound * (cpuScalar / 100) * channelMult * capacityMult * thermal.cpuClockFactor * presetEff.cpu;
  if (presetEff.steps !== 0) {
    terms.push({
      label: `preset: ${preset}`,
      value: `x${presetEff.gpu.toFixed(2)} GPU, x${presetEff.cpu.toFixed(2)} CPU, x${presetEff.vram.toFixed(2)} VRAM`,
      confidence: 'interpolated',
      sources: [],
      explain: `${preset} is ${Math.abs(presetEff.steps)} step${Math.abs(presetEff.steps) === 1 ? '' : 's'} ${presetEff.steps > 0 ? 'above' : 'below'} the reference preset (${PRESET_MODEL.referencePreset}) for a ${game.archetype} title. These multipliers are PRIORS, not fitted values — the fixture corpus has no controlled preset variation to fit against, so the band is widened by ${(Math.abs(presetEff.steps) * PRESET_MODEL.uncertaintyPerStep * 100).toFixed(0)}%.`,
    });
  }

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
  // Texture pool size is what preset moves most, which is why dropping a step
  // is the standard escape from a VRAM cliff.
  const baseDemand = game.vramDemandGB[resolution];
  const demand = baseDemand != null ? baseDemand * presetEff.vram : baseDemand;
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

  // --- 8. Frame-time consistency -----------------------------------------
  //
  // Lows were previously a fixed ratio of the average, which cannot express the
  // complaint players actually have: a build can hold a respectable average and
  // still feel broken. Each stall source is modelled separately so the panel
  // can name the cause rather than just showing a smaller number.
  const lowRatio = LOW1PCT_RATIO[game.archetype];
  const stutterCauses: string[] = [];
  let stutterMult = 1;

  if (limiterIsCpu(cpuBoundFps, gpuBoundFps)) {
    stutterMult *= STUTTER_MODEL.cpuBoundLowPenalty;
    stutterCauses.push('CPU is the limiter — frame pacing degrades before the average does');
  }
  const comfortableThreads = (game.requirements.minThreads ?? 4) * 1.5;
  if (cpu.threads < comfortableThreads) {
    stutterMult *= STUTTER_MODEL.threadStarvationPenalty;
    stutterCauses.push(`${cpu.threads} threads against a comfortable ${Math.ceil(comfortableThreads)} for this title`);
  }
  const shaderPenalty = STUTTER_MODEL.shaderCompilationPenalty[game.archetype];
  if (shaderPenalty) {
    stutterMult *= shaderPenalty;
    stutterCauses.push('shader compilation during play (worst on a first run, and after every driver update)');
  }
  for (const rule of STUTTER_MODEL.ramPressurePenalty) {
    if (build.ram.totalGB < rule.belowGB) {
      stutterMult = Math.min(stutterMult, stutterMult * rule.multiplier);
      stutterCauses.push(`${build.ram.totalGB}GB system RAM forces paging mid-frame`);
      break;
    }
  }
  if (vramLimited) stutterCauses.push('VRAM over-subscribed — textures stream across the PCIe bus mid-frame');
  if (build.storage === 'hdd') stutterCauses.push('mechanical drive cannot stream assets fast enough during traversal');

  const low1Pct = fps * lowRatio * (vramLow / Math.max(vramAvg, 0.01)) * storageLow * stutterMult;
  const low01Pct =
    low1Pct * STUTTER_MODEL.low01FromLow1 * (vramLimited ? STUTTER_MODEL.low01VramCliffExtra : 1);

  const smoothnessRatio = low1Pct / Math.max(fps, 1);
  const smoothnessVerdict =
    smoothnessRatio >= 0.75 ? 'smooth' : smoothnessRatio >= 0.62 ? 'good' : smoothnessRatio >= 0.48 ? 'uneven' : 'stuttery';

  terms.push({
    label: '1% low',
    value: Number(low1Pct.toFixed(1)),
    confidence: weakest(ref.confidence, 'interpolated'),
    sources: [],
    explain: `Average x${lowRatio} archetype ratio, then VRAM, storage and stall sources${stutterCauses.length ? `: ${stutterCauses.join('; ')}` : ' (none active)'}.`,
  });
  terms.push({
    label: '0.1% low',
    value: Number(low01Pct.toFixed(1)),
    confidence: weakest(ref.confidence, 'extrapolated'),
    sources: [],
    explain: `The deep tail — the individual stalls you notice rather than feel. ${(STUTTER_MODEL.low01FromLow1 * 100).toFixed(0)}% of the 1% low${vramLimited ? ', collapsed further because a VRAM deficit produces long single-frame stalls' : ''}.`,
  });
  terms.push({
    label: 'smoothness',
    value: smoothnessVerdict,
    confidence: 'interpolated',
    sources: [],
    explain: `1% low is ${(smoothnessRatio * 100).toFixed(0)}% of the average. Above ~75% feels smooth; below ~48% feels stuttery regardless of how good the average looks.`,
  });

  // --- 9. Frame generation ------------------------------------------------
  //
  // Reported as a SEPARATE figure. Generated frames raise the counter without
  // improving latency, so folding them into avgFps would make a frame-gen build
  // look faster than a native one that is genuinely more responsive.
  let presentedFps: number | undefined;
  const frameGenActive = upscaling.frameGen && upscaling.tech !== 'none' && gpu.caps.upscaling.includes(upscaling.tech);
  if (frameGenActive) {
    const mult = FRAMEGEN_MODEL.multiplier[upscaling.tech] ?? 1;
    presentedFps = fps * mult;
    terms.push({
      label: 'frame generation',
      value: `${presentedFps.toFixed(0)} presented`,
      confidence: 'spec-derived',
      sources: [],
      explain: `${upscaling.tech.toUpperCase()} generation presents ${mult}x the rendered frames (not 2x — generating costs GPU time). Rendered rate stays ${fps.toFixed(0)}. Latency RISES slightly; this figure is reported separately precisely so it is never compared against a native number as though they measured the same thing.${fps < FRAMEGEN_MODEL.minimumSensibleBaseFps ? ` Below ${FRAMEGEN_MODEL.minimumSensibleBaseFps} rendered fps this feels bad however high the counter reads.` : ''}`,
    });
  }

  // --- 10. Limiter and uncertainty ---------------------------------------
  const gpuBoundRatio = cpuBoundFps / (cpuBoundFps + gpuBoundFps);
  if (!limiter) {
    if (thermalFactor < 0.97) limiter = 'thermal';
    else if (vramLimited && vramAvg < 0.8) limiter = 'vram';
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
    low01PctFps: low01Pct,
    presentedFps,
    frameGenActive,
    smoothness: { ratio: smoothnessRatio, verdict: smoothnessVerdict, causes: stutterCauses },
    thermalFactor,
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
