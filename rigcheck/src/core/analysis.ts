/**
 * Higher-order analyses composed over the engine.
 *
 * queries.ts holds the original five from the specification; this holds what was
 * added afterwards. The split is deliberate — these all build on compareBuilds
 * rather than calling the engine directly, so the noise floor and uncertainty
 * handling stay consistent with the rest of the product.
 */
import { estimate, type EngineData } from './engine.ts';
import { compareBuilds, type PriceLookup } from './queries.ts';
import { estimateLatency, estimatePower, estimateThermals, estimateNoise, latencyVerdict } from './physics.ts';
import type { Build, CpuRecord, GpuRecord, Resolution } from './types.ts';
import { RESOLUTIONS } from './types.ts';

// ---------------------------------------------------------------------------
// 15. Bottleneck map
// ---------------------------------------------------------------------------

export interface BottleneckCell {
  gameId: string;
  resolution: Resolution;
  limiter: string;
  /** 0 = fully CPU-bound, 1 = fully GPU-bound. */
  gpuBoundRatio: number;
  avgFps?: number;
  blocked: boolean;
}

export interface BottleneckMap {
  buildId: string;
  cells: BottleneckCell[];
  /** Plain-language reading of where this build's constraint actually sits. */
  summary: string;
  /** Which single upgrade would move the most cells. */
  recommendation: string;
}

/**
 * Where the constraint sits across every game and resolution at once.
 *
 * The whole point is that "is it CPU or GPU bound" has no single answer — it
 * flips by title and by resolution, and a build that is GPU-bound at 4K can be
 * CPU-bound at 1080p in the same game. One view makes that legible instead of
 * making you check cells one at a time.
 */
export function bottleneckMap(
  build: Build,
  games: string[],
  data: EngineData,
  resolutions: Resolution[] = RESOLUTIONS,
): BottleneckMap {
  const cells: BottleneckCell[] = [];
  for (const gameId of games) {
    for (const resolution of resolutions) {
      const est = estimate(build, gameId, resolution, data);
      cells.push({
        gameId,
        resolution,
        limiter: est.limiter ?? 'unknown',
        gpuBoundRatio: est.gpuBoundRatio ?? 0.5,
        avgFps: est.avgFps,
        blocked: est.status !== 'ok',
      });
    }
  }

  const live = cells.filter((c) => !c.blocked);
  const count = (l: string) => live.filter((c) => c.limiter === l).length;
  const cpu = count('cpu');
  const gpu = count('gpu');
  const vram = count('vram');
  const thermal = count('thermal');
  const cap = count('engine-cap');
  const total = live.length || 1;

  let summary: string;
  let recommendation: string;
  if (vram / total > 0.25) {
    summary = `VRAM is the binding constraint in ${vram} of ${total} cases — that is a cliff, not a slope.`;
    recommendation = 'A card with more VRAM, even at similar raw speed, would help more than a faster one with the same capacity.';
  } else if (thermal / total > 0.25) {
    summary = `Cooling is limiting ${thermal} of ${total} cases: the parts are being held below their rated clocks.`;
    recommendation = 'Better cooling or case airflow recovers performance you have already paid for — cheaper than any component upgrade.';
  } else if (cpu / total > 0.55) {
    summary = `CPU-bound in ${cpu} of ${total} cases. A faster GPU would change very little.`;
    recommendation = 'Upgrade the CPU. Note that upscaling cannot help here either — it only reduces GPU work.';
  } else if (gpu / total > 0.55) {
    summary = `GPU-bound in ${gpu} of ${total} cases — the usual, healthy state for a gaming build.`;
    recommendation = 'Upgrade the GPU, or turn on upscaling, which reduces exactly this side of the load.';
  } else {
    summary = `Mixed: ${cpu} CPU-bound, ${gpu} GPU-bound${cap ? `, ${cap} at an engine cap` : ''} across ${total} cases.`;
    recommendation = 'Reasonably balanced. The right upgrade depends on which titles you actually care about — check the per-game rows.';
  }

  return { buildId: build.id, cells, summary, recommendation };
}

// ---------------------------------------------------------------------------
// 22. Two-axis sweep
// ---------------------------------------------------------------------------

export interface SweepGridCell {
  cpuId: string;
  gpuId: string;
  cpuName: string;
  gpuName: string;
  geomeanFps: number;
  cost?: number;
  fpsPerCurrency?: number;
  blocked: boolean;
}

export interface SweepGrid {
  cells: SweepGridCell[];
  cpuIds: string[];
  gpuIds: string[];
  /** Best value cell where pricing exists. */
  bestValue?: SweepGridCell;
  /** Cells on the efficient frontier: nothing is both cheaper and faster. */
  frontier: SweepGridCell[];
}

/**
 * CPU x GPU jointly, rather than one axis at a time.
 *
 * Single-axis sweeps answer "what if I change the GPU", but the real question is
 * usually "given £X, where does it go" — and that has a joint answer. The
 * efficient frontier is the honest output: the set of builds where you cannot
 * get faster without paying more.
 */
export function sweepGrid(
  baseBuild: Build,
  cpuIds: string[],
  gpuIds: string[],
  games: string[],
  resolutions: Resolution[],
  data: EngineData,
  priceOf?: PriceLookup,
): SweepGrid {
  const cells: SweepGridCell[] = [];

  for (const cpuId of cpuIds) {
    const cpu = data.cpus.get(cpuId);
    if (!cpu) continue;
    for (const gpuId of gpuIds) {
      const gpu = data.gpus.get(gpuId);
      if (!gpu) continue;
      // Respect memory-generation compatibility rather than proposing impossible pairs.
      const ram = baseBuild.ram.type && !cpu.memoryType.includes(baseBuild.ram.type)
        ? { ...baseBuild.ram, type: cpu.memoryType[0] }
        : baseBuild.ram;
      const variant: Build = { ...baseBuild, id: `grid__${cpuId}__${gpuId}`, cpuId, gpuId, ram };
      const m = compareBuilds([variant], games, resolutions, data);
      const s = m.summary[0];
      const blocked = s.gamesBlocked === games.length * resolutions.length;
      const cpuPrice = priceOf?.(cpuId);
      const gpuPrice = priceOf?.(gpuId);
      const cost = cpuPrice != null && gpuPrice != null ? cpuPrice + gpuPrice : undefined;
      cells.push({
        cpuId,
        gpuId,
        cpuName: cpu.fullName,
        gpuName: gpu.fullName,
        geomeanFps: s.geomeanFps,
        cost,
        fpsPerCurrency: cost && cost > 0 ? s.geomeanFps / cost : undefined,
        blocked,
      });
    }
  }

  const priced = cells.filter((c) => !c.blocked && c.cost != null);
  const bestValue = priced.length
    ? priced.reduce((a, b) => ((b.fpsPerCurrency ?? 0) > (a.fpsPerCurrency ?? 0) ? b : a))
    : undefined;

  // Efficient frontier: keep a cell only if nothing else is both cheaper and faster.
  const frontier = priced
    .filter((c) => !priced.some((o) => o !== c && (o.cost ?? 0) <= (c.cost ?? 0) && o.geomeanFps >= c.geomeanFps &&
      ((o.cost ?? 0) < (c.cost ?? 0) || o.geomeanFps > c.geomeanFps)))
    .sort((a, b) => (a.cost ?? 0) - (b.cost ?? 0));

  return { cells, cpuIds, gpuIds, bestValue, frontier };
}

// ---------------------------------------------------------------------------
// 24. Budget allocator
// ---------------------------------------------------------------------------

export interface AllocationResult {
  budget: number;
  best?: SweepGridCell;
  /** How the winning build divides the money. */
  split?: { cpuPct: number; gpuPct: number };
  /** Guidance derived from where the winner actually landed. */
  advice: string;
  considered: number;
  affordable: number;
  priceCoverage: { priced: number; total: number };
}

/**
 * Given a budget, the best CPU/GPU split for a specific set of games.
 *
 * There is no universal ratio — the received wisdom of "spend twice as much on
 * the GPU" is only right for GPU-bound titles at high resolution. An esports
 * player at 1080p gets a different answer, and this computes it rather than
 * repeating the folklore.
 */
export function allocateBudget(
  budget: number,
  baseBuild: Build,
  cpuIds: string[],
  gpuIds: string[],
  games: string[],
  resolution: Resolution,
  data: EngineData,
  priceOf: PriceLookup,
): AllocationResult {
  const grid = sweepGrid(baseBuild, cpuIds, gpuIds, games, [resolution], data, priceOf);
  const priced = grid.cells.filter((c) => c.cost != null && !c.blocked);
  const affordable = priced.filter((c) => (c.cost ?? 0) <= budget);

  if (!affordable.length) {
    return {
      budget,
      advice:
        priced.length === 0
          ? 'No candidate pairing had prices for both parts. This query is only as good as the pricing data in data/pricing/.'
          : `Nothing in the candidate set fits £${budget}. The cheapest viable pairing is £${Math.min(...priced.map((c) => c.cost ?? Infinity)).toFixed(0)}.`,
      considered: grid.cells.length,
      affordable: 0,
      priceCoverage: { priced: priced.length, total: grid.cells.length },
    };
  }

  const best = affordable.reduce((a, b) => (b.geomeanFps > a.geomeanFps ? b : a));
  const cpuPrice = priceOf(best.cpuId) ?? 0;
  const gpuPrice = priceOf(best.gpuId) ?? 0;
  const totalSpend = cpuPrice + gpuPrice || 1;
  const cpuPct = (cpuPrice / totalSpend) * 100;
  const gpuPct = (gpuPrice / totalSpend) * 100;

  const advice =
    gpuPct > 70
      ? `Weight the GPU heavily (${gpuPct.toFixed(0)}% of the parts budget). These titles at ${resolution} are GPU-bound, so CPU spend converts poorly.`
      : gpuPct < 45
        ? `Unusually CPU-weighted (${cpuPct.toFixed(0)}% CPU). Your title selection is CPU-limited — the common "spend it all on the GPU" advice would be wrong here.`
        : `A fairly even split (${cpuPct.toFixed(0)}% CPU / ${gpuPct.toFixed(0)}% GPU) wins for this mix.`;

  return {
    budget,
    best,
    split: { cpuPct, gpuPct },
    advice,
    considered: grid.cells.length,
    affordable: affordable.length,
    priceCoverage: { priced: priced.length, total: grid.cells.length },
  };
}

// ---------------------------------------------------------------------------
// 18/19. Trade: build-vs-parts and margin
// ---------------------------------------------------------------------------

export interface TradeAppraisal {
  /** What the parts are worth sold individually. */
  partsOutTotal: number;
  /** What the best single build from the pile is worth assembled. */
  assembledValue?: number;
  /** Value of parts left over after taking the best build. */
  leftoverValue: number;
  /** assembled + leftovers, versus parts-out. */
  buildTotal: number;
  advantage: number;
  verdict: 'build it' | 'sell as parts' | 'line ball';
  reasoning: string;
  unpricedParts: string[];
}

/**
 * Is a pile of parts worth more assembled or sold piecemeal?
 *
 * A built machine usually carries a premium over the sum of its parts, but not
 * always: a pile containing one very desirable card and otherwise weak parts is
 * worth more broken up. `assemblyPremium` is the operator's judgement of what
 * a working, tested machine adds — it varies by market and is deliberately an
 * input rather than a constant.
 */
export function appraiseTrade(
  parts: { cpuIds: string[]; gpuIds: string[] },
  bestBuild: { build: Build; geomeanFps: number } | null,
  priceOf: PriceLookup,
  opts: { assemblyPremium?: number; buildValuePerFps?: number } = {},
): TradeAppraisal {
  const assemblyPremium = opts.assemblyPremium ?? 1.18;
  const all = [...parts.cpuIds, ...parts.gpuIds];
  const unpricedParts = all.filter((id) => priceOf(id) == null);
  const partsOutTotal = all.reduce((sum, id) => sum + (priceOf(id) ?? 0), 0);

  let assembledValue: number | undefined;
  let leftoverValue = 0;
  if (bestBuild) {
    const usedIds = [bestBuild.build.cpuId, bestBuild.build.gpuId];
    const usedValue = usedIds.reduce((sum, id) => sum + (priceOf(id) ?? 0), 0);
    assembledValue = usedValue * assemblyPremium;
    leftoverValue = all.filter((id) => !usedIds.includes(id)).reduce((sum, id) => sum + (priceOf(id) ?? 0), 0);
  }

  const buildTotal = (assembledValue ?? 0) + leftoverValue;
  const advantage = buildTotal - partsOutTotal;
  const verdict = Math.abs(advantage) < partsOutTotal * 0.05 ? 'line ball' : advantage > 0 ? 'build it' : 'sell as parts';

  const reasoning = !bestBuild
    ? 'No compatible build could be assembled from this pile, so parts-out is the only option.'
    : verdict === 'build it'
      ? `Assembling captures the ${((assemblyPremium - 1) * 100).toFixed(0)}% working-machine premium on the parts it uses, and the leftovers still sell.`
      : verdict === 'sell as parts'
        ? 'The pile is worth more broken up — the build consumes desirable parts without adding enough as a whole machine.'
        : 'Too close to call on price alone. Decide on your time: assembling and testing costs hours that the margin here does not clearly pay for.';

  return { partsOutTotal, assembledValue, leftoverValue, buildTotal, advantage, verdict, reasoning, unpricedParts };
}

export interface MarginResult {
  cost: number;
  salePrice: number;
  grossMargin: number;
  marginPct: number;
  /** Running cost the buyer inherits, useful as a selling point or a warning. */
  runningCostPerYearGBP: number;
  breakEvenSalePrice: number;
}

export function calculateMargin(
  build: Build,
  gpu: GpuRecord,
  cpu: CpuRecord,
  priceOf: PriceLookup,
  opts: { salePrice: number; otherCosts?: number; hoursPerWeek?: number },
): MarginResult {
  const partsCost = (priceOf(build.cpuId) ?? 0) + (priceOf(build.gpuId) ?? 0);
  const cost = partsCost + (opts.otherCosts ?? 0);
  const grossMargin = opts.salePrice - cost;
  const power = estimatePower(gpu, cpu, build);
  return {
    cost,
    salePrice: opts.salePrice,
    grossMargin,
    marginPct: opts.salePrice > 0 ? (grossMargin / opts.salePrice) * 100 : 0,
    runningCostPerYearGBP: power.costPerYearGBP(opts.hoursPerWeek ?? 15),
    breakEvenSalePrice: cost,
  };
}

// ---------------------------------------------------------------------------
// 17. Batch scoring
// ---------------------------------------------------------------------------

export interface BatchRow {
  label: string;
  cpuId: string;
  gpuId: string;
  geomeanFps: number;
  blocked: number;
  powerW: number;
  recommendedPsuW: number;
  latencyMs?: number;
  smoothness?: string;
  cost?: number;
  fpsPerCurrency?: number;
  error?: string;
}

/**
 * Score many machines at once — for triaging a job lot rather than evaluating
 * one build. Rows that fail resolve to an `error` string instead of throwing, so
 * one bad line never costs you the whole batch.
 */
export function batchScore(
  machines: { label: string; cpuId: string; gpuId: string; ram?: Build['ram']; storage?: Build['storage'] }[],
  games: string[],
  resolution: Resolution,
  data: EngineData,
  priceOf?: PriceLookup,
): BatchRow[] {
  return machines.map((m) => {
    const cpu = data.cpus.get(m.cpuId);
    const gpu = data.gpus.get(m.gpuId);
    if (!cpu || !gpu) {
      return {
        label: m.label,
        cpuId: m.cpuId,
        gpuId: m.gpuId,
        geomeanFps: 0,
        blocked: games.length,
        powerW: 0,
        recommendedPsuW: 0,
        error: !cpu ? `unknown CPU "${m.cpuId}"` : `unknown GPU "${m.gpuId}"`,
      };
    }
    const build: Build = {
      id: `batch__${m.label}`,
      cpuId: m.cpuId,
      gpuId: m.gpuId,
      ram: m.ram ?? { totalGB: 16, channels: 2, speedMTs: 3200, type: cpu.memoryType[0] },
      storage: m.storage ?? 'nvme-gen3',
      target: { resolution, refreshHz: 144 },
    };
    const matrix = compareBuilds([build], games, [resolution], data);
    const s = matrix.summary[0];
    const power = estimatePower(gpu, cpu, build);
    const thermal = estimateThermals(gpu, cpu, build);
    const first = matrix.cells.find((c) => c.estimate.status === 'ok');
    const latency = first?.estimate.avgFps
      ? estimateLatency(first.estimate.avgFps, { cpuBound: first.estimate.limiter === 'cpu' }).totalMs
      : undefined;
    const cost = priceOf ? (priceOf(m.cpuId) ?? 0) + (priceOf(m.gpuId) ?? 0) : undefined;
    void thermal;
    return {
      label: m.label,
      cpuId: m.cpuId,
      gpuId: m.gpuId,
      geomeanFps: s.geomeanFps,
      blocked: s.gamesBlocked,
      powerW: Math.round(power.totalW),
      recommendedPsuW: power.recommendedPsuW,
      latencyMs: latency,
      smoothness: first?.estimate.smoothness?.verdict,
      cost: cost || undefined,
      fpsPerCurrency: cost ? s.geomeanFps / cost : undefined,
    };
  });
}

// ---------------------------------------------------------------------------
// 27. Sensitivity analysis
// ---------------------------------------------------------------------------

export interface SensitivityFactor {
  factor: string;
  /** Percentage change in the result from a plausible change in this input. */
  impactPct: number;
  detail: string;
}

export interface SensitivityResult {
  baselineFps: number;
  factors: SensitivityFactor[];
  /** Whether a stated comparison would survive these uncertainties. */
  robustness: string;
}

/**
 * What would actually change this answer.
 *
 * An uncertainty band tells you the answer is imprecise; this tells you WHY and
 * which input to go and pin down. Each factor is perturbed by a plausible amount
 * and the result re-estimated, so the ranking reflects the model rather than
 * intuition about it.
 */
export function sensitivity(
  build: Build,
  gameId: string,
  resolution: Resolution,
  data: EngineData,
): SensitivityResult {
  const base = estimate(build, gameId, resolution, data);
  if (base.status !== 'ok' || !base.avgFps) {
    return { baselineFps: 0, factors: [], robustness: 'No estimate to analyse.' };
  }
  const baselineFps = base.avgFps;
  const pct = (v: number) => ((v - baselineFps) / baselineFps) * 100;
  const factors: SensitivityFactor[] = [];

  const tryVariant = (label: string, b: Build, detail: string, opts?: Parameters<typeof estimate>[4]) => {
    const e = estimate(b, gameId, resolution, data, opts);
    if (e.status === 'ok' && e.avgFps) factors.push({ factor: label, impactPct: pct(e.avgFps), detail });
  };

  tryVariant('Case airflow', build, 'A restricted case instead of good airflow — free to fix, and commonly overlooked.', { airflowTier: 'restricted' });
  tryVariant(
    'Memory speed',
    { ...build, ram: { ...build.ram, speedMTs: Math.round(build.ram.speedMTs * 0.8) } },
    'Running memory 20% slower, e.g. failing to enable XMP/EXPO — an extremely common real-world mistake.',
  );
  tryVariant(
    'Single channel',
    { ...build, ram: { ...build.ram, channels: 1 } },
    'One stick instead of two. Frequent in prebuilt and refurbished machines.',
  );
  tryVariant(
    'Cooling tier',
    { ...build, cooling: 'stock' },
    'Stock cooler instead of the configured one.',
  );
  if (build.storage !== 'hdd') {
    tryVariant('Storage', { ...build, storage: 'hdd' }, 'A mechanical drive — small effect on averages, large on stutter.');
  }

  // The model's own uncertainty, for comparison against the above.
  const modelBand = (base.uncertainty ?? 0) * 100;
  factors.push({
    factor: 'Model uncertainty',
    impactPct: modelBand,
    detail: `The model's own ±${modelBand.toFixed(0)}% band. Any factor smaller than this is not distinguishable from noise.`,
  });

  factors.sort((a, b) => Math.abs(b.impactPct) - Math.abs(a.impactPct));
  const biggest = factors.find((f) => f.factor !== 'Model uncertainty');
  const robustness = biggest
    ? Math.abs(biggest.impactPct) > modelBand
      ? `${biggest.factor} moves this result more than the model's own uncertainty does — worth checking before trusting the number.`
      : 'No single input moves this result further than the model\'s own uncertainty. The estimate is as good as the data allows.'
    : 'No perturbations produced a valid alternative estimate.';

  return { baselineFps, factors, robustness };
}

// ---------------------------------------------------------------------------
// Whole-machine report, composing physics with performance
// ---------------------------------------------------------------------------

export interface MachineReport {
  power: ReturnType<typeof estimatePower>;
  thermal: ReturnType<typeof estimateThermals>;
  noise: ReturnType<typeof estimateNoise>;
  latency?: ReturnType<typeof estimateLatency>;
  latencyVerdict?: ReturnType<typeof latencyVerdict>;
  psuVerdict: { ok: boolean; message: string };
}

export function machineReport(
  build: Build,
  data: EngineData,
  opts: { fps?: number; panelType?: string; airflowTier?: 'restricted' | 'moderate' | 'good' | 'excellent'; noiseTier?: 'loud' | 'moderate' | 'quiet' | 'silent'; cpuBound?: boolean } = {},
): MachineReport | null {
  const gpu = data.gpus.get(build.gpuId);
  const cpu = data.cpus.get(build.cpuId);
  if (!gpu || !cpu) return null;

  const power = estimatePower(gpu, cpu, build);
  const thermal = estimateThermals(gpu, cpu, build, opts.airflowTier ?? 'good');
  const noise = estimateNoise(thermal, power, build, opts.noiseTier ?? 'moderate');
  const latency = opts.fps
    ? estimateLatency(opts.fps, { panelType: opts.panelType, refreshHz: build.target.refreshHz, cpuBound: opts.cpuBound, frameGen: build.target.upscaling?.frameGen })
    : undefined;

  const psuWatts = build.psu?.watts;
  const psuVerdict = psuWatts
    ? psuWatts >= power.recommendedPsuW
      ? { ok: true, message: `${psuWatts}W is comfortable for a ${Math.round(power.totalW)}W sustained draw.` }
      : psuWatts >= power.transientPeakW * 0.85
        ? { ok: false, message: `${psuWatts}W covers the ${Math.round(power.totalW)}W sustained draw but sits close to the ${Math.round(power.transientPeakW)}W transient peak. This is the configuration that shuts down mid-game rather than failing outright.` }
        : { ok: false, message: `${psuWatts}W is below the ${power.recommendedPsuW}W recommendation and well under the ${Math.round(power.transientPeakW)}W transient peak. Expect shutdowns under load.` }
    : { ok: true, message: `No PSU specified. ${power.recommendedPsuW}W recommended for this build.` };

  return { power, thermal, noise, latency, latencyVerdict: latency ? latencyVerdict(latency.totalMs) : undefined, psuVerdict };
}


// ---------------------------------------------------------------------------
// 23. Inventory top-N
// ---------------------------------------------------------------------------

export interface RankedAssembly {
  build: Build;
  geomeanFps: number;
  blocked: number;
  powerW: number;
  recommendedPsuW: number;
  cost?: number;
  /** What this option gives up relative to the top-ranked one. */
  tradeOff: string;
}

/**
 * The best N assemblies from a pile, not just the winner.
 *
 * A single "best build" hides the decision that actually matters: the top option
 * is often marginally faster while consuming a part worth more sold separately,
 * or drawing 120W more for 6% more frames. Ranking with the trade-off spelled
 * out lets that be a choice rather than an assumption.
 */
export function rankAssemblies(
  parts: { cpuIds: string[]; gpuIds: string[]; ramKits: Build['ram'][]; storage: Build['storage'][] },
  games: string[],
  resolutions: Resolution[],
  data: EngineData,
  topN = 5,
  priceOf?: PriceLookup,
): RankedAssembly[] {
  const out: RankedAssembly[] = [];

  for (const cpuId of parts.cpuIds) {
    const cpu = data.cpus.get(cpuId);
    if (!cpu) continue;
    for (const gpuId of parts.gpuIds) {
      const gpu = data.gpus.get(gpuId);
      if (!gpu) continue;
      for (const ram of parts.ramKits.length ? parts.ramKits : [{ totalGB: 16, channels: 2 as const, speedMTs: 3200 }]) {
        // Compatibility is a hard filter, exactly as in bestFromInventory.
        if (ram.type && !cpu.memoryType.includes(ram.type)) continue;
        if (ram.channels > cpu.maxMemChannels) continue;
        const storage = parts.storage[0] ?? 'nvme-gen3';
        const build: Build = {
          id: `rank__${cpuId}__${gpuId}__${ram.totalGB}`,
          cpuId,
          gpuId,
          ram,
          storage,
          target: { resolution: resolutions[0] ?? '1080p', refreshHz: 144 },
        };
        const m = compareBuilds([build], games, resolutions, data);
        const s = m.summary[0];
        if (s.gamesBlocked === games.length * resolutions.length) continue;
        const power = estimatePower(gpu, cpu, build);
        const cost = priceOf ? (priceOf(cpuId) ?? 0) + (priceOf(gpuId) ?? 0) : undefined;
        out.push({
          build,
          geomeanFps: s.geomeanFps,
          blocked: s.gamesBlocked,
          powerW: Math.round(power.totalW),
          recommendedPsuW: power.recommendedPsuW,
          cost: cost || undefined,
          tradeOff: '',
        });
      }
    }
  }

  out.sort((a, b) => b.geomeanFps - a.geomeanFps);
  const top = out.slice(0, topN);
  const best = top[0];
  for (const r of top) {
    if (r === best) {
      r.tradeOff = 'Fastest assembly from this pile.';
      continue;
    }
    const fpsGap = ((best.geomeanFps - r.geomeanFps) / best.geomeanFps) * 100;
    const powerGap = best.powerW - r.powerW;
    const parts2: string[] = [`${fpsGap.toFixed(1)}% slower`];
    if (powerGap > 25) parts2.push(`but draws ${powerGap}W less`);
    if (r.cost != null && best.cost != null && r.cost < best.cost) parts2.push(`and uses £${(best.cost - r.cost).toFixed(0)} less of the pile`);
    if (r.build.gpuId !== best.build.gpuId) parts2.push(`frees the ${data.gpus.get(best.build.gpuId)?.brand} to sell separately`);
    r.tradeOff = parts2.join(', ') + '.';
  }
  return top;
}
