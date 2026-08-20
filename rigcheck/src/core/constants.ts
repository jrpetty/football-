/**
 * Every fitted constant in the model lives here, in one file, so the calibrator
 * has a single surface to tune and the "how was this calculated" panels have a
 * single place to cite. Nothing is tuned by eye — `scripts/calibrate.ts` rewrites
 * `data/calibration/constants.json`, which overrides these defaults at load.
 *
 * These values are PRIORS, derived from spec relationships and well-known
 * relative performance. They are not measurements.
 */
import type { Archetype, CpuWeights } from './types.ts';
import calibration from '../../data/calibration/constants.json';

export const ANCHORS = {
  /** Index 100 on the GPU raster axis. */
  gpuId: 'nvidia-geforce-rtx-3060-12gb',
  /** Index 100 on the CPU throughput axis. */
  cpuId: 'amd-ryzen-5-3600',
  /**
   * The anchor is a units choice, not a modelling choice: provided the
   * measurement graph is connected, it only fixes an arbitrary global scale.
   * These two were chosen for bridge density — both appear in review charts
   * spanning roughly 2016 to the present, in both directions.
   */
  rationale:
    'RTX 3060 12GB and Ryzen 5 3600 maximise overlap between legacy and contemporary measurement sets. 12GB avoids the anchor sitting on a VRAM nonlinearity; 6C/12T is the modal gaming configuration across the fixture range; neither part has a cache or topology anomaly.',
} as const;

/**
 * Gaming performance per nominal TFLOP, relative to Ampere = 1.00.
 *
 * This term exists because nominal FLOPs are not comparable across
 * architectures. Ampere and RDNA 3 both double-count FP32 lanes that games
 * cannot fully use, so their raw TFLOP figures overstate gaming throughput by
 * roughly 1.7x and 1.3x respectively. Without this correction an RTX 3080 looks
 * twice as fast as a 2080 Ti instead of ~1.3x.
 */
export const GPU_ARCH_EFFICIENCY: Record<string, number> = {
  // Priors pinned against widely-reproduced cross-generation raster
  // equivalences rather than guessed per-architecture folklore:
  //   GTX 1080 Ti ~= RTX 3060 (+/-5%)  -> Pascal ~= 1.0 with sublinear 0.70
  //   RTX 2080 Ti ~= RTX 3070 (1.35x a 3060) and RTX 2060 ~= 0.86x -> Turing ~= 1.5
  //   RTX 3080 ~= 1.75x a 3060 -> anchors the sublinear exponent itself
  //   RX 6700 XT ~= 1.17x, RX 6800 XT ~= 1.75x -> RDNA 2 ~= 1.1
  // Nvidia Ampere/Ada figures count the doubled FP32 lanes games cannot fully
  // use, so their efficiency sits near 1.0 while Turing (undoubled) sits ~1.5
  // and RDNA 3/4 (stored single-issue, half of AMD marketing) sit above 1.3.
  // These are priors; scripts/calibrate.ts refines them on the TRAIN split only.
  Fermi: 0.7,
  Kepler: 0.75,
  Maxwell: 0.98,
  Pascal: 1.0,
  Turing: 1.5,
  Ampere: 1.0,
  Ada: 1.02,
  'Ada Lovelace': 1.02,
  Blackwell: 1.05,

  /**
   * TeraScale 3 is VLIW4: its nominal FLOPS assume all four slots issue every
   * cycle, which real game shaders rarely achieved — that dependence on
   * compiler-extracted instruction-level parallelism is exactly why AMD
   * replaced it with GCN. It therefore sits BELOW GCN 1.0 per nominal FLOP.
   *
   * This is a prior with NO fixture behind it: the only parts using it are the
   * 2012-13 Trinity and Richland APUs, and the fixture set contains none of
   * them. It exists so those records do not silently fall through to the 1.0
   * default, which would rate them above every GCN part in the catalogue. Treat
   * any Trinity/Richland estimate as the least-evidenced number the tool
   * produces, and see Model Health for what that means.
   */
  'TeraScale 3 (VLIW4)': 0.6,

  GCN: 0.8,
  'GCN 1.0': 0.78,
  'GCN 2.0': 0.8,
  'GCN 3.0': 0.82,
  'GCN 4.0': 0.9,
  Polaris: 0.9,
  Vega: 0.85,
  'GCN 5.0': 0.85,
  RDNA: 1.25,
  'RDNA 1': 1.25,
  'RDNA 2': 1.18, // pin 1.1, +7.5% from the bounded train fit (regularised; see tuning log)
  'RDNA 3': 1.45, // pin 1.35, +7.5% from the bounded train fit — consistent with the RDNA 4 pin direction
  'RDNA 3.5': 1.35,
  'RDNA 4': 1.6,

  'Arc Alchemist': 0.85,
  Alchemist: 0.85,
  'Arc Battlemage': 1.15,
  Battlemage: 1.15,
  Xe: 0.85,
  'Xe-LP': 0.85,
  'Xe-LPG': 0.95,
  Xe2: 1.05,
  'Xe2-LPG': 1.05,
  'Gen 9': 0.7,
  'Gen 11': 0.75,
};

export const DEFAULT_GPU_ARCH_EFFICIENCY = 1.0;

/**
 * Ray tracing throughput per nominal TFLOP, relative to Ampere = 1.00.
 * RT and raster rank differently by vendor, which is why GpuIndex carries both.
 */
export const GPU_RT_EFFICIENCY: Record<string, number> = {
  // Pinned by tier-drop behaviour: a part's RT index should land on the raster
  // index of the tier it falls to with RT enabled. RX 6800 XT rasters ~= 3080
  // but ray-traces ~= 3070 -> RDNA 2 ~= 0.85 in stored-TFLOP units. Turing holds
  // its tier (2080 Ti RT ~= 3070 RT). Same unit caveat as the raster table:
  // Ampere/Ada TFLOPs are lane-doubled, RDNA 3/4 stored halved.
  Turing: 1.35,
  Ampere: 1.0,
  Ada: 1.35,
  'Ada Lovelace': 1.35,
  Blackwell: 1.45,
  'RDNA 2': 0.85,
  'RDNA 3': 1.0,
  'RDNA 3.5': 1.0,
  'RDNA 4': 1.35,
  'Arc Alchemist': 0.9,
  Alchemist: 0.9,
  'Arc Battlemage': 1.2,
  Battlemage: 1.2,
  'Xe-LPG': 0.9,
  Xe2: 1.0,
  'Xe2-LPG': 1.0,
};

export const GPU_MODEL = {
  /** Weight on compute vs bandwidth in the composite. */
  computeWeight: 0.7,
  /**
   * General sublinear scaling on the composite. Pinned by the 3080 ~= 1.75x and
   * 4090 ~= 3.2x equivalences: 0.85 made a 4090 4.6x a 3060, which no game
   * reproduces. High-end parts flatten hard against fixed per-frame costs.
   */
  sublinearExponent: 0.7,
  /**
   * Infinity Cache / large L2 multiplies effective bandwidth. Keyed by
   * architecture; 1.0 means no on-die cache benefit modelled.
   */
  cacheBandwidthMultiplier: {
    'RDNA 2': 1.6,
    'RDNA 3': 1.5,
    'RDNA 4': 1.5,
    Ada: 1.45,
    'Ada Lovelace': 1.45,
    Blackwell: 1.45,
  } as Record<string, number>,
};

/**
 * CPU IPC relative to Zen 2 = 1.00, measured as game-relevant per-clock
 * throughput rather than synthetic integer IPC.
 */
export const CPU_IPC: Record<string, number> = {
  Bulldozer: 0.5,
  Piledriver: 0.55,
  Excavator: 0.62,
  'Sandy Bridge': 0.68,
  'Ivy Bridge': 0.72,
  Haswell: 0.78,
  Broadwell: 0.82,
  Skylake: 0.86,
  'Kaby Lake': 0.86,
  'Coffee Lake': 0.87,
  'Comet Lake': 0.88,
  'Rocket Lake': 0.98,
  'Alder Lake': 1.16,
  'Raptor Lake': 1.19,
  'Arrow Lake': 1.24,
  Zen: 0.85,
  'Zen+': 0.88,
  'Zen 2': 1.0,
  'Zen 3': 1.19,
  'Zen 4': 1.32,
  'Zen 5': 1.4,
};

export const DEFAULT_CPU_IPC = 0.9;

export const CPU_MODEL = {
  /** Games saturate somewhere around this many threads. */
  threadCeiling: 16,
  /** Exponent on thread count; strongly sublinear. */
  threadExponent: 0.3,
  /**
   * FX-series parts market module pairs as cores but share a front end and FPU.
   * They badly underperform their core count in games.
   */
  sharedFpuPenalty: 0.78,
  /** Reference L3 per core, MB (Ryzen 5 3600: 32MB / 6c). */
  refL3PerCore: 5.33,
  /** Reference effective memory latency, ns (DDR4-3200 CL16 on Zen 2). */
  refLatencyNs: 10.0,
  /** Uncore/fabric latency floor added to DRAM latency, by architecture. */
  uncoreLatencyNs: {
    'Sandy Bridge': 22,
    'Ivy Bridge': 21,
    Haswell: 20,
    Broadwell: 20,
    Skylake: 18,
    'Kaby Lake': 18,
    'Coffee Lake': 18,
    'Comet Lake': 18,
    'Rocket Lake': 19,
    'Alder Lake': 17,
    'Raptor Lake': 16,
    'Arrow Lake': 19,
    Zen: 32,
    'Zen+': 30,
    'Zen 2': 26,
    'Zen 3': 22,
    'Zen 4': 22,
    'Zen 5': 21,
    Piledriver: 34,
    Bulldozer: 36,
  } as Record<string, number>,
  defaultUncoreLatencyNs: 22,
};

/**
 * Per-archetype CPU axis weights. Exponents, so 0 = irrelevant.
 * Fitted by scripts/calibrate.ts; these are the priors.
 */
export const CPU_WEIGHTS: Record<Archetype, CpuWeights> = {
  // Draw-call bound, single-thread dominated, very high frame rates.
  esports: { throughput: 1.0, cacheEndowment: 0.22, latencyScore: 0.35, threadCapacity: 0.1 },
  // Agent/physics simulation: the cache and latency extreme. X3D shines here.
  // NOTE: cacheEndowment is already sqrt-compressed in indices.ts, because cache
  // benefit saturates. An uncompressed ratio with a 0.75 exponent penalised a
  // 12MB-L3 Coffee Lake part by 52% against the anchor, which is far beyond what
  // measurements show.
  'sim-cpu': { throughput: 0.9, cacheEndowment: 0.42, latencyScore: 0.55, threadCapacity: 0.15 },
  'aaa-raster': { throughput: 0.85, cacheEndowment: 0.3, latencyScore: 0.3, threadCapacity: 0.3 },
  // RT adds BVH traversal work on the CPU side too.
  'aaa-rt': { throughput: 0.9, cacheEndowment: 0.35, latencyScore: 0.3, threadCapacity: 0.35 },
  // UE5 is GPU-extreme with a comparatively narrow CPU load.
  ue5: { throughput: 0.8, cacheEndowment: 0.25, latencyScore: 0.25, threadCapacity: 0.4 },
  'vram-heavy': { throughput: 0.8, cacheEndowment: 0.3, latencyScore: 0.35, threadCapacity: 0.3 },
};

/**
 * How much of a title's GPU work runs through the ray-tracing path, by archetype.
 *
 * Without this the raster index alone drives every estimate, which systematically
 * overrates RDNA 2/3 in ray-traced titles — their RT throughput per TFLOP is
 * roughly 0.6-0.7x Ampere's while their raster is competitive. Ignoring it would
 * bake in exactly the vendor bias this model is supposed to avoid.
 */
/**
 * Low-end scaling correction.
 *
 * A flat per-title scaling exponent interpolates well between capable cards but
 * breaks at the bottom of the range. Esports titles use an exponent near 0.65
 * because draw-call-bound renderers barely respond to extra GPU power — but
 * applied to an integrated GPU at index ~25 that implies 40% of a reference
 * card's frame rate, which is roughly triple what integrated graphics actually
 * deliver. Below the floor, scaling reverts toward linear: a weak GPU really is
 * the wall, whatever the title's usual behaviour.
 */
export const LOW_END_MODEL = {
  /** GPU index below which the flat exponent stops applying. */
  indexFloor: 55,
  /** Exponent used beneath the floor. Near-linear: no free frames down here. */
  floorExponent: 1.15,
};

export const RT_MODEL = {
  /**
   * Frame rate with ray tracing enabled, as a fraction of the same scene with it
   * off, on hardware whose RT throughput matches its raster tier. Used to convert
   * between RT-on and RT-off baselines so a reference captured one way can answer
   * a query asked the other way.
   *
   * Without this conversion the model silently compares an RT-on reference against
   * a raster measurement, which is precisely the settings-drift failure the
   * fingerprint rules exist to prevent — and it under-predicted every ray-traced
   * title by 55-80% in validation before it was added.
   */
  onVsOff: 0.6, // pin 0.55; bounded train fit preferred 0.6 (RT costs ~40% here, tier-dependent 30-50% in the wild)
  /** Archetypes whose seeded references represent RT ENABLED. */
  referenceRtOn: ['aaa-rt'] as Archetype[],
};

export const RT_WEIGHT: Record<Archetype, number> = {
  esports: 0,
  'sim-cpu': 0,
  'aaa-raster': 0,
  'aaa-rt': 0.55,
  ue5: 0.2, // software Lumen mostly, with hardware RT where available
  'vram-heavy': 0.1,
};

export const COMBINE = {
  /**
   * Power-mean soft minimum exponent.
   *
   * NOTE: the v2 spec says "p ~= 4" and "models the ~10% loss when both
   * components are near-equal". Those disagree. At equal inputs the power mean
   * gives 2^(-1/p): p=4 yields a 15.9% loss, not 10%. p = ln2 / -ln(0.9) = 6.58
   * is the value that actually produces 10%. We take 6.58 as the prior and fit
   * per resolution, since contention overlap at 1080p differs from 2160p.
   */
  p: 6.58,
  pByResolution: {
    '1080p': 6.0,
    '1440p': 6.6,
    '3440x1440': 6.8,
    '2160p': 7.4,
  } as Record<string, number>,
};

export const RAM_MODEL = {
  /**
   * Single-channel memory is catastrophic for CPU-bound performance and is an
   * explicit fixture case. This is a multiplier on the CPU-bound estimate.
   */
  channelMultiplier: { 1: 0.68, 2: 1.0, 4: 1.04, 8: 1.05 } as Record<number, number>,
  /** Capacity floor below which paging/streaming stalls dominate. */
  capacityPenalty: [
    { belowGB: 8, multiplier: 0.72 },
    { belowGB: 12, multiplier: 0.88 },
    { belowGB: 16, multiplier: 0.96 },
  ],
};

export const VRAM_MODEL = {
  /**
   * The VRAM cliff is a discontinuity, not a smooth term. Once demand exceeds
   * capacity the frame time is dominated by PCIe texture streaming, so a
   * regression term cannot represent it — this is a piecewise penalty applied
   * after the main estimate.
   *
   * `deficitRatio` = (demand - capacity) / demand.
   */
  // Averages flatten near ~0.5 at deep deficits: modern streaming engines shed
  // texture quality and stall the lows rather than halving the average. The
  // pre-revision deep end (0.25 avg at 50% deficit) over-penalised averages by
  // ~2x against the fixture set while the lows column was about right.
  cliff: [
    { deficitRatio: 0.0, avgMultiplier: 1.0, lowMultiplier: 1.0 },
    { deficitRatio: 0.08, avgMultiplier: 0.92, lowMultiplier: 0.7 },
    { deficitRatio: 0.2, avgMultiplier: 0.72, lowMultiplier: 0.35 },
    { deficitRatio: 0.35, avgMultiplier: 0.58, lowMultiplier: 0.2 },
    { deficitRatio: 0.5, avgMultiplier: 0.48, lowMultiplier: 0.12 },
  ],
  /** Headroom below which we warn without penalising. */
  warnHeadroomGB: 0.5,
};

/**
 * Integrated-graphics contention. An iGPU shares two things a discrete card
 * does not: the memory controller (the CPU consumes a large share of bandwidth
 * exactly when frame rates are high) and the package power budget (boost clocks
 * do not sustain under combined CPU+GPU load). Both are multiplicative derates
 * observed as systematic ~1.5-2x overprediction on iGPU esports fixtures before
 * this term existed.
 */
export const IGPU_MODEL = {
  /** Share of system memory bandwidth actually available to the iGPU under load. */
  bandwidthShare: 0.7,
  /** Sustained clock as a fraction of boost, package TDP shared with the CPU. */
  sustainedClockFactor: 0.85,
};

export const PCIE_MODEL = {
  /**
   * Effective bandwidth relative to gen4 x16. Only bites when the GPU is fast
   * or when VRAM is over-subscribed (streaming over the bus).
   */
  bandwidth: {
    '3-16': 1.0,
    '3-8': 0.93,
    '3-4': 0.8,
    '4-16': 1.0,
    '4-8': 0.99,
    '4-4': 0.92,
    '2-16': 0.93,
    '2-8': 0.8,
    '1-16': 0.8,
  } as Record<string, number>,
  /** Extra penalty multiplier applied to the PCIe term when VRAM is exceeded. */
  vramOversubscribeAmplifier: 2.5,
};

/**
 * Power draw.
 *
 * TDP is a thermal rating, not a measurement, and the two diverge in opposite
 * directions by vendor: Nvidia's board power is close to real gaming draw, while
 * AMD's TBP excludes some board components and Intel's PL1 is a sustained floor
 * that PL2 blows through for tens of seconds. Gaming load is also well under
 * an all-core stress load for CPUs — a 253W-rated part draws ~40% of that while
 * feeding a GPU, because games never saturate every core.
 */
export const POWER_MODEL = {
  /** Real gaming draw as a fraction of the CPU's rated TDP. */
  cpuGamingLoadFactor: 0.42,
  /** Gaming draw as a fraction of a GPU's rated TDP/TBP, by vendor. */
  gpuLoadFactor: { nvidia: 0.95, amd: 0.98, intel: 0.92 } as Record<string, number>,
  /** Everything that is not the CPU or GPU: board, RAM, drives, fans, USB. */
  systemBaseW: 55,
  /** Per-stick and per-drive additions, small but real in a 12-stick workstation. */
  perRamStickW: 3,
  perDriveW: 5,
  /**
   * Transient spikes. Modern GPUs pull far above their rated draw for
   * microseconds; a PSU's over-current protection trips on the spike, not the
   * average, which is why a "650W is enough" calculation kills 3080 builds.
   */
  transientMultiplier: { nvidia: 1.8, amd: 1.5, intel: 1.4 } as Record<string, number>,
  /** Ampere's spikes were unusually severe; Ada improved markedly. */
  transientByArchitecture: { Ampere: 2.0, 'RDNA 2': 1.45, Ada: 1.6, 'Ada Lovelace': 1.6 } as Record<string, number>,
  /** Recommended PSU headroom above sustained draw, for efficiency and ageing. */
  psuHeadroom: 1.35,
  /** Typical UK electricity price, p/kWh. Operator-overridable. */
  pencePerKwh: 24.5,
};

/**
 * Thermals and throttling.
 *
 * A part that cannot shed its heat does not run at its boost clock, and the
 * catalogue's clock figures assume it can. Cooler tier and case airflow together
 * set a sustained-clock multiplier; the spec deferred this, but with TDP present
 * on every record it is cheap to model and it is the difference between a
 * quoted figure and what the machine does twenty minutes in.
 */
export const THERMAL_MODEL = {
  /** Heat a cooler can dissipate while holding boost clocks, in watts. */
  coolerCapacityW: {
    stock: 75,
    'budget-tower': 130,
    'premium-air': 220,
    aio: 260,
  } as Record<string, number>,
  /** Case airflow multiplies effective cooler capacity. */
  airflowMultiplier: {
    restricted: 0.78,
    moderate: 0.92,
    good: 1.0,
    excellent: 1.08,
  } as Record<string, number>,
  /**
   * Sustained clock as a fraction of boost, given heat load over capacity.
   * Throttling is gradual, not a cliff: silicon steps down bins.
   */
  throttleCurve: [
    { loadRatio: 1.0, clockFactor: 1.0 },
    { loadRatio: 1.15, clockFactor: 0.96 },
    { loadRatio: 1.4, clockFactor: 0.88 },
    { loadRatio: 1.8, clockFactor: 0.78 },
    { loadRatio: 2.5, clockFactor: 0.68 },
  ],
  /** Blower and single-fan cards in a restricted case suffer further. */
  gpuRestrictedCasePenalty: 0.96,
};

/**
 * Acoustics. Rough, and honestly labelled as such: perceived noise depends on
 * fan curves, bearing type and case panels far more than on wattage. This
 * models the trend (more heat in a worse case is louder), not a spec figure.
 */
export const NOISE_MODEL = {
  /** Idle floor by case noise tier, dBA at ~1m. */
  baseDba: { loud: 34, moderate: 30, quiet: 26, silent: 22 } as Record<string, number>,
  /** dBA added per unit of (heat load / cooler capacity) above 0.5. */
  dbaPerLoadRatio: 11,
  coolerBonusDba: { stock: 3, 'budget-tower': 0, 'premium-air': -2, aio: -1 } as Record<string, number>,
  maxDba: 55,
};

/**
 * System latency (click-to-photon), the metric esports players actually feel.
 *
 * Deliberately separate from FPS: at a fixed frame rate, latency still varies
 * by queue depth, sync mode and whether frame generation is on. Frame gen
 * RAISES latency slightly while doubling the frame counter, which is exactly
 * why presenting generated frames as equivalent to real ones is dishonest.
 */
export const LATENCY_MODEL = {
  /** Frames the render queue holds; each costs roughly one frame time. */
  queueFrames: { default: 2, lowLatency: 1, ultraLowLatency: 0.5 } as Record<string, number>,
  /** Fixed input-stack cost: peripheral polling, OS, engine sampling, in ms. */
  inputStackMs: 6.5,
  /** Display pipeline: scanout plus panel response, in ms. */
  displayMs: { OLED: 2.5, 'QD-OLED': 2.5, TN: 4, IPS: 6, VA: 9 } as Record<string, number>,
  /** V-Sync adds up to a full refresh interval of waiting. */
  vsyncPenaltyFrames: 1.0,
  /**
   * Frame generation inserts an interpolated frame, so the real frame must be
   * held back one interval before it can be shown. Latency rises even as the
   * frame counter doubles.
   */
  frameGenLatencyPenaltyMs: 8,
  /** Being CPU-bound adds simulation-step latency beyond raw frame time. */
  cpuBoundPenaltyMs: 3,
};

/**
 * Frame generation.
 *
 * Modelled as a presentation-layer multiplier that never touches the CPU-bound
 * estimate and never improves latency. The engine reports generated frames
 * separately from rendered ones so a comparison cannot silently put a
 * frame-gen figure beside a native one.
 */
export const FRAMEGEN_MODEL = {
  /** Presented frames per rendered frame. Not 2.0: generation costs GPU time. */
  multiplier: { dlss: 1.75, fsr: 1.7, xess: 1.65, tsr: 1.0, none: 1.0 } as Record<string, number>,
  /** Below this rendered frame rate, generation feels bad regardless of counter. */
  minimumSensibleBaseFps: 55,
};

export const STORAGE_MODEL = {
  /** Storage barely moves average FPS; it moves 1% lows and traversal stutter. */
  low1PctMultiplier: { hdd: 0.72, 'sata-ssd': 0.95, 'nvme-gen3': 1.0, 'nvme-gen4': 1.01 } as Record<string, number>,
  avgMultiplier: { hdd: 0.97, 'sata-ssd': 0.997, 'nvme-gen3': 1.0, 'nvme-gen4': 1.0 } as Record<string, number>,
};

/**
 * Frame-time consistency.
 *
 * A fixed ratio of average was the previous model, and it cannot represent the
 * thing players actually complain about: a build can hold a fine average and
 * still feel broken. Lows are driven by discrete stall sources — a CPU with no
 * headroom, shader compilation, texture streaming over a slow bus, memory
 * pressure — each of which is modelled separately and multiplied in.
 */
export const STUTTER_MODEL = {
  /** Extra penalty to lows when the CPU is the limiter: frame pacing collapses. */
  cpuBoundLowPenalty: 0.88,
  /** Below the game's comfortable thread count, stalls become frequent. */
  threadStarvationPenalty: 0.8,
  /** UE5 and DX12 titles compile shaders during play on first run. */
  shaderCompilationPenalty: { ue5: 0.86, 'aaa-rt': 0.93, 'vram-heavy': 0.92 } as Record<string, number>,
  /** System RAM pressure forces paging mid-frame. */
  ramPressurePenalty: [
    { belowGB: 8, multiplier: 0.62 },
    { belowGB: 16, multiplier: 0.85 },
    { belowGB: 32, multiplier: 0.97 },
  ],
  /** 0.1% low as a fraction of the 1% low. The deep tail is far worse. */
  low01FromLow1: 0.72,
  /** With a VRAM deficit the deep tail collapses much further. */
  low01VramCliffExtra: 0.6,
};

/** Ratio of 1% low to average, by archetype. Fitted. */
export const LOW1PCT_RATIO: Record<Archetype, number> = {
  esports: 0.62,
  'sim-cpu': 0.6,
  'aaa-raster': 0.78,
  'aaa-rt': 0.74,
  ue5: 0.7,
  'vram-heavy': 0.7,
};

/**
 * Base multiplicative uncertainty by confidence class, before graph-distance and
 * missing-field penalties. Every estimate carries a band; the comparison matrix
 * greys out deltas smaller than the combined band.
 */
export const UNCERTAINTY = {
  base: {
    measured: 0.06,
    interpolated: 0.12,
    'spec-derived': 0.18,
    extrapolated: 0.3,
    'gate-blocked': 0,
  } as Record<string, number>,
  /** Added per null field the model had to fall back on. */
  perMissingField: 0.025,
  /** Added when the part's architecture has no fitted efficiency constant. */
  unknownArchitecture: 0.08,
  /** Added when VRAM is on the cliff — behaviour there is highly title-specific. */
  vramCliff: 0.14,
  /** Added for iGPU builds, where the separable CPU/GPU model breaks down. */
  igpu: 0.15,
  max: 0.6,
};

// ---------------------------------------------------------------------------
// Calibration overrides
// ---------------------------------------------------------------------------

/**
 * Merge fitted constants over the priors above.
 *
 * `data/calibration/constants.json` is written by scripts/calibrate.ts. It is
 * deliberately empty while the fixture set is recalled rather than measured — the
 * calibrator refuses to write there, because fitting recollection yields better
 * metrics and worse physics (its unrestrained fit pushed the GPU sublinear
 * exponent to 1.0, i.e. perfectly linear scaling).
 *
 * Applied at module load so every consumer — CLI scripts and the browser bundle
 * alike — sees one set of numbers.
 */
function applyCalibration() {
  const c = (calibration as { constants?: Record<string, Record<string, unknown>> }).constants;
  if (!c || Object.keys(c).length === 0) return;
  const targets: Record<string, Record<string, unknown>> = {
    GPU_MODEL: GPU_MODEL as unknown as Record<string, unknown>,
    CPU_MODEL: CPU_MODEL as unknown as Record<string, unknown>,
    COMBINE: COMBINE as unknown as Record<string, unknown>,
    RT_MODEL: RT_MODEL as unknown as Record<string, unknown>,
    VRAM_MODEL: VRAM_MODEL as unknown as Record<string, unknown>,
  };
  for (const [group, values] of Object.entries(c)) {
    if (group === 'CPU_WEIGHTS') {
      for (const [arch, w] of Object.entries(values as Record<string, CpuWeights>)) {
        if (CPU_WEIGHTS[arch as Archetype]) Object.assign(CPU_WEIGHTS[arch as Archetype], w);
      }
      continue;
    }
    const target = targets[group];
    if (target) Object.assign(target, values);
  }
}

applyCalibration();

/** True when fitted constants are in force rather than the priors alone. */
export const CALIBRATION_ACTIVE =
  Object.keys((calibration as { constants?: object }).constants ?? {}).length > 0;
