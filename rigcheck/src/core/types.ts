/**
 * RIGCHECK canonical data model.
 *
 * Provenance strategy: records store plain values plus a `_prov` map from field
 * name to provenance-table keys. The provenance table is deduplicated at the top
 * of each data file. This keeps the JSON readable while still giving per-field
 * attribution, which §3 of the spec requires and which the "how was this
 * calculated" panels read directly.
 */

export type Vendor = 'nvidia' | 'amd' | 'intel';

export interface Provenance {
  /** Stable key referenced from `_prov`. */
  id: string;
  source: string;
  url: string;
  licence: string;
  retrievedAt: string;
  note?: string;
}

/** Provenance tables are keyed by Provenance.id. */
export type ProvenanceTable = Record<string, Provenance>;

/** Field name -> provenance ids that contributed the value. */
export type ProvMap = Record<string, string[]>;

export interface Conflict<T = unknown> {
  field: string;
  value: T;
  provenanceId: string;
}

/**
 * Confidence class for a single figure.
 *
 * `gate-blocked` is added beyond the v2 spec: a build that fails a hard gate has
 * no FPS estimate at all, which is different from having a low-confidence one.
 */
export type Confidence =
  | 'measured'
  | 'interpolated'
  | 'spec-derived'
  | 'extrapolated'
  | 'gate-blocked';

/** Ordering used when combining confidences; lower is weaker. */
export const CONFIDENCE_RANK: Record<Confidence, number> = {
  'gate-blocked': 0,
  extrapolated: 1,
  'spec-derived': 2,
  interpolated: 3,
  measured: 4,
};

export type Resolution = '1080p' | '1440p' | '2160p' | '3440x1440';

export const RESOLUTIONS: Resolution[] = ['1080p', '1440p', '2160p', '3440x1440'];

/** Pixel counts, used for the GPU-side resolution scaling term. */
export const RESOLUTION_PIXELS: Record<Resolution, number> = {
  '1080p': 1920 * 1080,
  '1440p': 2560 * 1440,
  '3440x1440': 3440 * 1440,
  '2160p': 3840 * 2160,
};

export type UpscalingTech = 'none' | 'dlss' | 'fsr' | 'xess' | 'tsr';
export type UpscalingQuality = 'native' | 'quality' | 'balanced' | 'performance' | 'ultra-performance';

/**
 * Upscaling is a first-class axis, not a modifier. Ignoring it makes any
 * post-2022 comparison vendor-biased and makes ingested review data
 * non-comparable with itself.
 */
export interface UpscalingSetting {
  tech: UpscalingTech;
  quality: UpscalingQuality;
  /** Frame generation multiplies presented frames without reducing latency. */
  frameGen: boolean;
}

export const NATIVE: UpscalingSetting = { tech: 'none', quality: 'native', frameGen: false };

/** Render-resolution scale factor per upscaling quality tier. */
export const UPSCALE_RENDER_SCALE: Record<UpscalingQuality, number> = {
  native: 1.0,
  quality: 1 / 1.5, // 0.667 linear
  balanced: 1 / 1.7,
  performance: 1 / 2.0,
  'ultra-performance': 1 / 3.0,
};

// ---------------------------------------------------------------------------
// Hardware records
// ---------------------------------------------------------------------------

export type GpuFormFactor = 'desktop' | 'mobile' | 'igpu';

export type DriverStatus = 'current' | 'maintenance' | 'legacy' | 'eol';

export interface GpuCapabilities {
  meshShaders: boolean;
  rayTracing: boolean;
  /** DirectX feature level, e.g. '12_2', '12_1', '11_0'. */
  dxFeatureLevel: string;
  /** Shader model, e.g. '6_7', '6_5', '5_1'. */
  shaderModel: string;
  vulkanVersion?: string;
  resizableBar?: boolean;
  av1Encode?: boolean;
  /** Upscaling technologies with hardware support on this part. */
  upscaling: UpscalingTech[];
}

export interface GpuRecord {
  id: string;
  vendor: Vendor;
  /** Marketing family, e.g. 'GeForce RTX 3060'. */
  brand: string;
  /** Disambiguating suffix where one name covers multiple parts, e.g. '12GB'. */
  variant?: string;
  fullName: string;
  architecture: string;
  chip?: string;
  launchDate?: string;
  formFactor: GpuFormFactor;

  shaders?: number;
  baseClockMHz?: number;
  boostClockMHz?: number;
  vramGB?: number;
  vramType?: string;
  memBusBits?: number;
  memBandwidthGBs?: number;
  fp32TFLOPS?: number;
  tdpW?: number;
  processNm?: number;

  // Physical / compatibility. Required by bestFromInventory to avoid
  // proposing builds that cannot physically be assembled.
  pcieGen?: number;
  pcieLanes?: number;
  lengthMm?: number;
  slotWidth?: number;
  powerConnectors?: string;
  recommendedPsuW?: number;

  caps: GpuCapabilities;
  driverStatus: DriverStatus;
  driverEolDate?: string;

  /** PCI device IDs, harvested from driver tables. Feeds the alias resolver. */
  pciIds?: string[];

  _prov: ProvMap;
  _conflicts?: Conflict[];
}

export type CpuSegment = 'entry' | 'mainstream' | 'performance' | 'enthusiast' | 'hedt';
export type MemoryType = 'DDR3' | 'DDR4' | 'DDR5';

export interface CpuCapabilities {
  avx2: boolean;
  avx512: boolean;
  /** Hybrid p-core/e-core topology changes scheduler behaviour materially. */
  hybrid: boolean;
}

export interface CpuRecord {
  id: string;
  vendor: 'intel' | 'amd';
  brand: string;
  variant?: string;
  fullName: string;
  architecture: string;
  codename?: string;
  launchDate?: string;
  segment: CpuSegment;

  cores: number;
  threads: number;
  pCores?: number;
  eCores?: number;
  baseClockMHz?: number;
  boostClockMHz?: number;
  l2CacheMB?: number;
  l3CacheMB?: number;
  /** 3D V-Cache and equivalents. The single biggest driver of game-to-game rank flips. */
  vcache?: boolean;
  tdpW?: number;
  processNm?: number;

  // Compatibility — without these the inventory optimiser is unsound.
  socket: string;
  memoryType: MemoryType[];
  maxMemChannels: 1 | 2 | 4 | 8;
  officialMemMTs?: number;
  pcieGen?: number;
  pcieLanes?: number;

  /** Integrated graphics part id, if any. */
  igpuId?: string;

  caps: CpuCapabilities;

  _prov: ProvMap;
  _conflicts?: Conflict[];
}

// ---------------------------------------------------------------------------
// Games
// ---------------------------------------------------------------------------

/**
 * Workload archetypes. CPU index weights are fitted per archetype rather than
 * per game: 50 games x 4 weights would be 200 free parameters against ~150
 * fixtures, which is pure overfit. Six archetypes is tractable.
 */
export type Archetype =
  | 'esports'          // very high fps, CPU/draw-call bound, trivial GPU load
  | 'sim-cpu'          // agent/physics simulation, memory-latency and cache bound
  | 'aaa-raster'       // conventional GPU-bound rendering
  | 'aaa-rt'           // ray tracing enabled, heavy GPU + RT units
  | 'ue5'              // Nanite/Lumen, GPU-extreme, narrow CPU load
  | 'vram-heavy';      // texture/streaming bound, cliff behaviour under VRAM pressure

export interface GameRequirements {
  meshShaders?: boolean;
  minDxFeatureLevel?: string;
  minShaderModel?: string;
  rayTracingRequired?: boolean;
  minVramGB?: number;
  minThreads?: number;
  minCores?: number;
  minSystemRamGB?: number;
}

export interface GameRecord {
  id: string;
  name: string;
  year: number;
  engine?: string;
  api: string[];
  archetype: Archetype;
  /** Has a deterministic built-in benchmark; gates inclusion in the harness suite. */
  builtInBenchmark: boolean;
  /** In the 12-game core loop every harness machine runs. */
  coreLoop?: boolean;
  /** Engine or design frame cap, e.g. Elden Ring at 60. */
  fpsCap?: number;
  requirements: GameRequirements;
  /** Per-archetype weights can be overridden per game where data supports it. */
  cpuWeightOverride?: Partial<CpuWeights>;
  /** Baseline VRAM demand by resolution at the reference preset, in GB. */
  vramDemandGB: Partial<Record<Resolution, number>>;
  upscalingSupport: UpscalingTech[];
  notes?: string;
  _prov: ProvMap;
}

// ---------------------------------------------------------------------------
// Index model
// ---------------------------------------------------------------------------

/**
 * The CPU index is a vector, not a scalar. A single number fits the average and
 * is wrong at both tails, and the tails are where the interesting queries live
 * (X3D parts, core-count cliffs, memory-latency-bound sims).
 */
export interface CpuIndexVector {
  /** General throughput, anchored at 100 = Ryzen 5 3600. */
  throughput: number;
  /** Cache endowment: normalised L3-per-core, 1.0 = reference. Drives X3D. */
  cacheEndowment: number;
  /** Memory latency capability, 1.0 = reference. Higher is better (lower ns). */
  latencyScore: number;
  /** Thread capacity, 1.0 = reference. */
  threadCapacity: number;
}

/**
 * How much a workload archetype cares about each CPU axis. Fitted per archetype,
 * not per game: 50 games x 4 weights against ~150 fixtures would be pure overfit.
 * Weights are exponents applied multiplicatively, so 0 means "does not matter".
 */
export interface CpuWeights {
  throughput: number;
  cacheEndowment: number;
  latencyScore: number;
  threadCapacity: number;
}

export interface GpuIndex {
  /** Anchored at 100 = RTX 3060 12GB. */
  raster: number;
  /** Separate RT throughput index; RT and raster rank differently by vendor. */
  rt: number;
}

// ---------------------------------------------------------------------------
// Builds
// ---------------------------------------------------------------------------

export type Storage = 'hdd' | 'sata-ssd' | 'nvme-gen3' | 'nvme-gen4';
export type Cooling = 'stock' | 'budget-tower' | 'premium-air' | 'aio';
export type Currency = 'GBP' | 'USD' | 'EUR';
export type PriceCondition = 'new' | 'used' | 'owned';

export interface RamConfig {
  totalGB: number;
  channels: 1 | 2 | 4 | 8;
  speedMTs: number;
  /** Structured, not a free string: effective latency is what sims respond to. */
  timings?: { cl: number; trcd: number; trp: number; tras: number };
  type?: MemoryType;
}

export interface BuildTarget {
  resolution: Resolution;
  refreshHz: number;
  upscaling?: UpscalingSetting;
}

export interface ComponentPrice {
  price: number;
  condition: PriceCondition;
  source: string;
}

export interface BuildPricing {
  currency: Currency;
  components: Record<string, ComponentPrice>;
  total: number;
  /** What an upgrade ACTUALLY costs, excluding parts already owned. */
  totalExcludingOwned: number;
}

export interface Build {
  id: string;
  label?: string;
  cpuId: string;
  gpuId: string;
  ram: RamConfig;
  storage: Storage;
  motherboard?: { chipset: string; pcieVersion: number; pcieLanesToGPU: number };
  psu?: { watts: number; tier?: string };
  cooling?: Cooling;
  target: BuildTarget;
  pricing?: BuildPricing;
}

// ---------------------------------------------------------------------------
// Estimation output
// ---------------------------------------------------------------------------

export type GateCode =
  | 'MESH_SHADER_REQUIRED'
  | 'DX_FEATURE_LEVEL'
  | 'SHADER_MODEL'
  | 'RAY_TRACING_REQUIRED'
  | 'VRAM_CEILING'
  | 'THREAD_FLOOR'
  | 'CORE_FLOOR'
  | 'SYSTEM_RAM_FLOOR';

export interface GateFailure {
  code: GateCode;
  detail: string;
  required: string;
  actual: string;
}

/** One line of the "how was this calculated" panel. */
export interface ModelTerm {
  label: string;
  value: number | string;
  unit?: string;
  confidence: Confidence;
  sources: string[];
  explain: string;
}

export interface FpsEstimate {
  /**
   * 'ok' — a real estimate. 'WILL_NOT_RUN' — a hard capability gate fired; the
   * title will not launch. 'NO_ESTIMATE' — the catalogue lacks the fields to
   * derive an index for a part (e.g. clocks pending harvest); the title runs,
   * but showing a number would be a guess dressed as one. Never conflate the
   * last two: one is a fact about the build, the other about our data.
   */
  status: 'ok' | 'WILL_NOT_RUN' | 'NO_ESTIMATE';
  gateFailures: GateFailure[];
  /** Undefined when status is WILL_NOT_RUN. */
  avgFps?: number;
  low1PctFps?: number;
  /** Multiplicative 1-sigma uncertainty, e.g. 0.18 means +/-18%. */
  uncertainty?: number;
  /** Absolute band derived from `uncertainty`. */
  band?: { low: number; high: number };
  confidence: Confidence;
  cpuBoundFps?: number;
  gpuBoundFps?: number;
  /** Which side dominates, as a 0..1 ratio. 1 = fully GPU bound. */
  gpuBoundRatio?: number;
  limiter?: 'cpu' | 'gpu' | 'balanced' | 'vram' | 'engine-cap';
  terms: ModelTerm[];
}

export interface BuildEstimate {
  buildId: string;
  gameId: string;
  resolution: Resolution;
  upscaling: UpscalingSetting;
  estimate: FpsEstimate;
}

// ---------------------------------------------------------------------------
// Query surface
// ---------------------------------------------------------------------------

export interface ComparisonCell {
  buildId: string;
  gameId: string;
  resolution: Resolution;
  estimate: FpsEstimate;
  /** Delta vs baseline build, fractional. Undefined for the baseline itself. */
  delta?: number;
  /**
   * True when |delta| is smaller than the combined uncertainty of the two
   * builds. The UI must grey these out — reporting "3% faster" when the model
   * error is +/-18% is exactly the opaque confidence this tool replaces.
   */
  deltaWithinNoise?: boolean;
}

export interface ComparisonMatrix {
  builds: string[];
  games: string[];
  resolutions: Resolution[];
  baselineBuildId?: string;
  cells: ComparisonCell[];
  /** Aggregate per build across the requested games/resolutions. */
  summary: {
    buildId: string;
    meanFps: number;
    geomeanFps: number;
    gamesBlocked: number;
    fpsPerCurrency?: number;
    fpsPerCurrencyExcludingOwned?: number;
  }[];
}

export interface SweepPoint {
  candidateId: string;
  candidateName: string;
  meanFps: number;
  geomeanFps: number;
  deltaVsBase: number;
  price?: number;
  fpsPerCurrency?: number;
  /** Marginal FPS gained per unit currency versus the previous frontier point. */
  marginalPerCurrency?: number;
  /**
   * True when this part is on the price/performance Pareto frontier — i.e. nothing
   * cheaper is faster. Only frontier parts are rational purchases, and marginal
   * return is only meaningful along the frontier.
   */
  onFrontier?: boolean;
  blocked: boolean;
  uncertainty: number;
}

export interface SweepResult {
  axis: 'cpu' | 'gpu';
  baseBuildId: string;
  points: SweepPoint[];
  /** Index into `points` where marginal return collapses. */
  kneeIndex?: number;
  kneeExplain: string;
  /**
   * Set when the top of the curve is flat within the model's uncertainty. This
   * is usually the most actionable output of a sweep: it means the OTHER
   * component is the limit and this axis is the wrong thing to spend on.
   */
  saturation?: { saturated: boolean; detail: string; firstSaturatedId: string };
}

export interface PartInventory {
  cpuIds: string[];
  gpuIds: string[];
  ramKits: RamConfig[];
  storage: Storage[];
  psuWatts?: number[];
  motherboards?: { chipset: string; socket: string; memoryType: MemoryType; pcieVersion: number }[];
}

export interface InventoryResult {
  build: Build;
  score: number;
  meanFps: number;
  unused: {
    cpuIds: string[];
    gpuIds: string[];
    ramKits: RamConfig[];
    storage: Storage[];
  };
  /** Combinations rejected by compatibility, with reasons, for auditability. */
  rejected: { reason: string; count: number }[];
}

export interface RiskFactor {
  factor: string;
  severity: 'ok' | 'watch' | 'risk' | 'critical';
  detail: string;
  /** Dated, sourced fact rather than a prediction. */
  evidence: string;
  sources: string[];
}

export interface FutureProofing {
  buildId: string;
  /** Composed from visible factor weights; never presented without the breakdown. */
  score: number;
  factors: RiskFactor[];
}

export interface PriceToTargetResult {
  builds: {
    build: Build;
    cost: number;
    costExcludingOwned: number;
    worstCaseFps: number;
    meanFps: number;
    clearsTarget: boolean;
  }[];
  /** Cheapest build clearing the target, if any. */
  cheapest?: Build;
  /** How much of the candidate catalogue actually had prices. */
  priceCoverage: { priced: number; total: number };
  unmetReason?: string;
}

// ---------------------------------------------------------------------------
// Measured performance records
// ---------------------------------------------------------------------------

/**
 * A settings fingerprint. Two records may only form a comparison edge if their
 * hard axes match; otherwise patch and preset drift is silently absorbed into
 * the fitted constants and looks like signal.
 */
export interface SettingsFingerprint {
  gameBuild?: string;
  preset: string;
  upscaling: UpscalingSetting;
  rtTier?: string;
  api?: string;
  driverVersion?: string;
  testPlatformCpuId?: string;
  date?: string;
}

export interface PerfRecord {
  id: string;
  gpuId: string;
  cpuId: string;
  gameId: string;
  resolution: Resolution;
  avgFps: number;
  low1Pct?: number;
  low01Pct?: number;
  ram?: RamConfig;
  fingerprint: SettingsFingerprint;
  sourceNote: string;
  provenanceId: string;
  /** Reduced for fingerprint-incomplete records. */
  weight: number;
}
