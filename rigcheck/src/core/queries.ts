/**
 * The comparison query surface. Comparison is the product; single-build FPS is
 * one view over it.
 */
import { estimate, type EngineData } from './engine.ts';
import type {
  Build,
  ComparisonCell,
  ComparisonMatrix,
  CpuRecord,
  FutureProofing,
  GpuRecord,
  InventoryResult,
  MemoryType,
  PartInventory,
  PriceToTargetResult,
  RamConfig,
  Resolution,
  RiskFactor,
  SweepPoint,
  SweepResult,
} from './types.ts';

// ---------------------------------------------------------------------------
// compareBuilds — a dense matrix, not a pair
// ---------------------------------------------------------------------------

export interface PriceLookup {
  /** Returns the price of a component id in the active currency, or undefined. */
  (componentId: string): number | undefined;
}

export function compareBuilds(
  builds: Build[],
  games: string[],
  resolutions: Resolution[],
  data: EngineData,
  opts: { baselineBuildId?: string } = {},
): ComparisonMatrix {
  const cells: ComparisonCell[] = [];
  const baselineId = opts.baselineBuildId ?? builds[0]?.id;

  // First pass: estimate everything.
  const byKey = new Map<string, ComparisonCell>();
  for (const build of builds) {
    for (const gameId of games) {
      for (const res of resolutions) {
        const est = estimate(build, gameId, res, data);
        const cell: ComparisonCell = { buildId: build.id, gameId, resolution: res, estimate: est };
        cells.push(cell);
        byKey.set(`${build.id}|${gameId}|${res}`, cell);
      }
    }
  }

  // Second pass: deltas against the baseline, with a noise floor.
  if (baselineId) {
    for (const cell of cells) {
      if (cell.buildId === baselineId) continue;
      const base = byKey.get(`${baselineId}|${cell.gameId}|${cell.resolution}`);
      if (!base?.estimate.avgFps || !cell.estimate.avgFps) continue;
      const delta = cell.estimate.avgFps / base.estimate.avgFps - 1;
      cell.delta = delta;
      // Combined 1-sigma of a ratio of two independent estimates.
      const combined = Math.sqrt(
        Math.pow(cell.estimate.uncertainty ?? 0, 2) + Math.pow(base.estimate.uncertainty ?? 0, 2),
      );
      // Reporting "3% faster" when the model error is +/-18% is exactly the
      // opaque confidence this tool exists to replace.
      cell.deltaWithinNoise = Math.abs(delta) < combined;
    }
  }

  const summary = builds.map((build) => {
    const mine = cells.filter((c) => c.buildId === build.id);
    const ok = mine.filter((c) => c.estimate.status === 'ok' && c.estimate.avgFps != null);
    const blocked = mine.length - ok.length;
    const meanFps = ok.length ? ok.reduce((s, c) => s + (c.estimate.avgFps ?? 0), 0) / ok.length : 0;
    // Geometric mean is the honest aggregate across titles with wildly
    // different absolute frame rates — an esports title at 400fps would
    // otherwise dominate an AAA title at 45fps.
    const geomeanFps = ok.length
      ? Math.exp(ok.reduce((s, c) => s + Math.log(Math.max(c.estimate.avgFps ?? 1, 1)), 0) / ok.length)
      : 0;

    const total = build.pricing?.total;
    const excl = build.pricing?.totalExcludingOwned;
    return {
      buildId: build.id,
      meanFps,
      geomeanFps,
      gamesBlocked: blocked,
      fpsPerCurrency: total && total > 0 ? geomeanFps / total : undefined,
      fpsPerCurrencyExcludingOwned: excl && excl > 0 ? geomeanFps / excl : undefined,
    };
  });

  return { builds: builds.map((b) => b.id), games, resolutions, baselineBuildId: baselineId, cells, summary };
}

// ---------------------------------------------------------------------------
// sweepComponent — the upgrade curve
// ---------------------------------------------------------------------------

export function sweepComponent(
  baseBuild: Build,
  axis: 'cpu' | 'gpu',
  candidateIds: string[],
  games: string[],
  resolutions: Resolution[],
  data: EngineData,
  priceOf?: PriceLookup,
): SweepResult {
  const baseMatrix = compareBuilds([baseBuild], games, resolutions, data);
  const baseGeomean = baseMatrix.summary[0]?.geomeanFps ?? 0;

  const points: SweepPoint[] = candidateIds.map((id) => {
    const variant: Build = { ...baseBuild, id: `${baseBuild.id}__${axis}__${id}`, ...(axis === 'cpu' ? { cpuId: id } : { gpuId: id }) };
    const m = compareBuilds([variant], games, resolutions, data);
    const s = m.summary[0];
    const rec = axis === 'cpu' ? data.cpus.get(id) : data.gpus.get(id);
    const blocked = s.gamesBlocked === games.length * resolutions.length;
    const price = priceOf?.(id);
    const cells = m.cells.filter((c) => c.estimate.uncertainty != null);
    const uncertainty = cells.length
      ? cells.reduce((acc, c) => acc + (c.estimate.uncertainty ?? 0), 0) / cells.length
      : 0.3;

    return {
      candidateId: id,
      candidateName: rec?.fullName ?? id,
      meanFps: s.meanFps,
      geomeanFps: s.geomeanFps,
      deltaVsBase: baseGeomean > 0 ? s.geomeanFps / baseGeomean - 1 : 0,
      price,
      fpsPerCurrency: price && price > 0 ? s.geomeanFps / price : undefined,
      blocked,
      uncertainty,
    };
  });

  // Order by performance. Knee and saturation reasoning both work along this axis.
  points.sort((a, b) => a.geomeanFps - b.geomeanFps);

  /**
   * Price/performance Pareto frontier.
   *
   * A part is on the frontier when nothing cheaper is at least as fast. Only
   * frontier parts are rational purchases: if a cheaper card is faster, the
   * dearer one is never the right answer at any budget. Marginal return is
   * computed ALONG the frontier rather than between performance-adjacent parts,
   * because the latter compares purchases nobody would actually choose between
   * and produces meaningless negative or infinite rates.
   */
  const pricedPoints = points.filter((p) => p.price != null && !p.blocked);
  if (pricedPoints.length >= 2) {
    const byPrice = [...pricedPoints].sort((a, b) => a.price! - b.price! || b.geomeanFps - a.geomeanFps);
    let bestSoFar = -Infinity;
    const frontier: SweepPoint[] = [];
    for (const p of byPrice) {
      if (p.geomeanFps > bestSoFar) {
        p.onFrontier = true;
        frontier.push(p);
        bestSoFar = p.geomeanFps;
      } else {
        p.onFrontier = false;
      }
    }
    for (let i = 1; i < frontier.length; i++) {
      const prev = frontier[i - 1];
      const cur = frontier[i];
      if (cur.price! > prev.price!) {
        cur.marginalPerCurrency = (cur.geomeanFps - prev.geomeanFps) / (cur.price! - prev.price!);
      }
    }
  }

  // Knee detection.
  //
  // Two regimes, because they are genuinely different questions:
  //  - With prices: where marginal FPS per unit currency collapses. Compared
  //    against the MEDIAN positive marginal rather than the max, because the max
  //    is set by a single cheap outlier and makes the threshold meaningless.
  //  - Without prices: maximum perpendicular distance from the chord joining the
  //    first and last point (the standard elbow construction). The previous
  //    "fraction of the first step" heuristic silently failed on dense candidate
  //    sets, where the first step is near zero and every later step looks large.
  let kneeIndex: number | undefined;
  let kneeExplain = '';
  const marginals = points.map((p) => p.marginalPerCurrency).filter((m): m is number => m != null && m > 0);

  if (marginals.length >= 3) {
    const sorted = [...marginals].sort((a, b) => a - b);
    const medianMarginal = sorted[Math.floor(sorted.length / 2)];
    for (let i = 0; i < points.length; i++) {
      const m = points[i].marginalPerCurrency;
      if (m != null && m < medianMarginal * 0.4) {
        kneeIndex = i;
        kneeExplain = `Marginal FPS per unit currency at ${points[i].candidateName} falls to ${((m / medianMarginal) * 100).toFixed(0)}% of the median rate across this curve. Spending past this point buys progressively less.`;
        break;
      }
    }
  }

  if (kneeIndex == null && points.length >= 4) {
    const usable = points.filter((p) => !p.blocked && p.geomeanFps > 0);
    if (usable.length >= 4) {
      const y0 = usable[0].geomeanFps;
      const x1 = usable.length - 1;
      const y1 = usable[usable.length - 1].geomeanFps;
      const span = y1 - y0;
      if (span > 0) {
        // Normalise both axes so the distance is scale-free.
        let bestDist = -Infinity;
        let bestI = -1;
        for (let i = 1; i < usable.length - 1; i++) {
          const nx = i / x1;
          const ny = (usable[i].geomeanFps - y0) / span;
          // Chord runs from (0,0) to (1,1) after normalisation.
          const dist = ny - nx;
          if (dist > bestDist) {
            bestDist = dist;
            bestI = i;
          }
        }
        if (bestI > 0 && bestDist > 0.05) {
          const knee = usable[bestI];
          kneeIndex = points.findIndex((p) => p.candidateId === knee.candidateId);
          const captured = ((knee.geomeanFps - y0) / span) * 100;
          kneeExplain = `No pricing available, so the knee is the elbow of the performance curve: ${knee.candidateName} captures ${captured.toFixed(0)}% of the total available gain at ${((bestI / x1) * 100).toFixed(0)}% of the way up the stack. Everything above it is paying for the remaining ${(100 - captured).toFixed(0)}%.`;
        }
      }
    }
  }

  // Saturation is often the real finding: if the top of the curve is flat, the
  // OTHER component is the wall and no amount of spending on this axis helps.
  let saturation: SweepResult['saturation'];
  const top = points.filter((p) => !p.blocked).slice(-6);
  if (top.length >= 3) {
    const best = top[top.length - 1];
    const spread = best.geomeanFps > 0 ? (best.geomeanFps - top[0].geomeanFps) / best.geomeanFps : 0;
    if (spread < Math.max(0.04, best.uncertainty * 0.5)) {
      saturation = {
        saturated: true,
        detail: `The top ${top.length} candidates land within ${(spread * 100).toFixed(1)}% of each other — inside the model's own uncertainty. On this build the ${axis === 'gpu' ? 'CPU' : 'GPU'} is the wall, so further ${axis.toUpperCase()} spending buys nothing measurable. Sweep the other axis instead.`,
        firstSaturatedId: top[0].candidateId,
      };
    }
  }

  if (kneeIndex == null && !kneeExplain) {
    kneeExplain = 'No knee identified across this candidate set — returns are still climbing at the top of the range.';
  }

  return { axis, baseBuildId: baseBuild.id, points, kneeIndex, kneeExplain, saturation };
}

// ---------------------------------------------------------------------------
// bestFromInventory — compatibility-filtered brute force
// ---------------------------------------------------------------------------

function ramCompatible(cpu: CpuRecord, ram: RamConfig): boolean {
  if (!ram.type) return true;
  return cpu.memoryType.includes(ram.type);
}

function psuAdequate(gpu: GpuRecord, cpu: CpuRecord, psuWatts: number | undefined): boolean {
  if (psuWatts == null) return true;
  // Deliberately a sanity gate, not a thermal model: the spec defers PSU and
  // airflow, but an optimiser that proposes a 4090 on a 450W unit is unsound.
  const need = (gpu.recommendedPsuW ?? (gpu.tdpW ?? 150) + 150) + (cpu.tdpW ?? 65) * 0.4;
  return psuWatts >= need;
}

export function bestFromInventory(
  parts: PartInventory,
  games: string[],
  resolutions: Resolution[],
  data: EngineData,
  constraints: { target?: { resolution: Resolution; refreshHz: number } } = {},
): InventoryResult | null {
  const rejected = new Map<string, number>();
  const bump = (reason: string) => rejected.set(reason, (rejected.get(reason) ?? 0) + 1);

  let best: { build: Build; score: number; meanFps: number } | null = null;

  const storages = parts.storage.length ? parts.storage : (['nvme-gen3'] as const);
  const target = constraints.target ?? { resolution: resolutions[0] ?? '1080p', refreshHz: 144 };

  for (const cpuId of parts.cpuIds) {
    const cpu = data.cpus.get(cpuId);
    if (!cpu) { bump('unknown CPU id'); continue; }

    for (const gpuId of parts.gpuIds) {
      const gpu = data.gpus.get(gpuId);
      if (!gpu) { bump('unknown GPU id'); continue; }

      // A board must exist that takes this CPU, if boards were supplied.
      const boards = parts.motherboards?.filter((b) => b.socket === cpu.socket);
      if (parts.motherboards && (!boards || boards.length === 0)) { bump(`no motherboard for socket ${cpu.socket}`); continue; }

      for (const ram of parts.ramKits) {
        if (!ramCompatible(cpu, ram)) { bump(`RAM type incompatible with ${cpu.socket}`); continue; }
        if (ram.channels > cpu.maxMemChannels) { bump('more channels than CPU supports'); continue; }

        for (const storage of storages) {
          const psu = parts.psuWatts?.length ? Math.max(...parts.psuWatts) : undefined;
          if (!psuAdequate(gpu, cpu, psu)) { bump('PSU below GPU requirement'); continue; }

          const board = boards?.[0];
          const build: Build = {
            id: `inv__${cpuId}__${gpuId}__${ram.totalGB}gb${ram.channels}ch__${storage}`,
            cpuId,
            gpuId,
            ram,
            storage: storage as Build['storage'],
            motherboard: board
              ? { chipset: board.chipset, pcieVersion: board.pcieVersion, pcieLanesToGPU: 16 }
              : undefined,
            psu: psu ? { watts: psu } : undefined,
            target,
          };

          const m = compareBuilds([build], games, resolutions, data);
          const s = m.summary[0];
          if (s.gamesBlocked === games.length * resolutions.length) { bump('fails every capability gate'); continue; }
          // Score on geometric mean, penalised for blocked titles.
          const score = s.geomeanFps * (1 - s.gamesBlocked / (games.length * resolutions.length));
          if (!best || score > best.score) best = { build, score, meanFps: s.meanFps };
        }
      }
    }
  }

  if (!best) return null;

  const usedRamKey = JSON.stringify(best.build.ram);
  return {
    build: best.build,
    score: best.score,
    meanFps: best.meanFps,
    unused: {
      cpuIds: parts.cpuIds.filter((id) => id !== best!.build.cpuId),
      gpuIds: parts.gpuIds.filter((id) => id !== best!.build.gpuId),
      ramKits: parts.ramKits.filter((r) => JSON.stringify(r) !== usedRamKey),
      storage: parts.storage.filter((s) => s !== best!.build.storage),
    },
    rejected: [...rejected.entries()].map(([reason, count]) => ({ reason, count })),
  };
}

// ---------------------------------------------------------------------------
// futureProofing — a structured risk breakdown, not a prediction
// ---------------------------------------------------------------------------

/**
 * Severity multipliers, applied MULTIPLICATIVELY rather than as an average.
 *
 * An averaged score compresses badly: with six factors, a single `critical`
 * moved the old additive score only ~17 points, so a card that physically cannot
 * launch current titles still scored in the eighties. A multiplicative form makes
 * one hard failure dominate, which is the honest shape — you cannot average your
 * way out of missing mesh shaders.
 */
const SEVERITY_MULTIPLIER = { ok: 1.0, watch: 0.93, risk: 0.72, critical: 0.3 } as const;

export function futureProofing(build: Build, data: EngineData, today = new Date()): FutureProofing {
  const gpu = data.gpus.get(build.gpuId);
  const cpu = data.cpus.get(build.cpuId);
  const factors: RiskFactor[] = [];

  if (!gpu || !cpu) {
    return { buildId: build.id, score: 0, factors: [{ factor: 'unknown parts', severity: 'critical', detail: 'Build references unknown components.', evidence: '', sources: [] }] };
  }

  // 1. Mesh shaders — the hardest forward gate that exists today.
  factors.push(
    gpu.caps.meshShaders
      ? { factor: 'Mesh shaders', severity: 'ok', detail: 'Supported.', evidence: `${gpu.architecture} implements mesh shaders.`, sources: [] }
      : {
          factor: 'Mesh shaders',
          severity: 'critical',
          detail: 'Not supported. Titles requiring mesh shaders will not launch at all.',
          evidence: `${gpu.architecture} predates mesh shader hardware. Alan Wake 2 (2023) already requires it; the requirement is spreading, not receding.`,
          sources: [],
        },
  );

  // 2. VRAM headroom against current demand, not speculation.
  const demands = [...data.games.values()]
    .map((g) => g.vramDemandGB[build.target.resolution])
    .filter((v): v is number => v != null);
  if (demands.length && gpu.vramGB != null) {
    const p90 = demands.sort((a, b) => a - b)[Math.floor(demands.length * 0.9)];
    const headroom = gpu.vramGB - p90;
    factors.push({
      factor: 'VRAM headroom',
      severity: headroom >= 4 ? 'ok' : headroom >= 1 ? 'watch' : headroom >= 0 ? 'risk' : 'critical',
      detail: `${gpu.vramGB}GB against a 90th-percentile demand of ${p90.toFixed(1)}GB at ${build.target.resolution}.`,
      evidence: `Computed over ${demands.length} catalogued titles at this resolution.`,
      sources: [],
    });
  }

  // 3. Driver support status — a dated fact, not a forecast.
  factors.push({
    factor: 'Driver support',
    severity:
      gpu.driverStatus === 'current' ? 'ok' : gpu.driverStatus === 'maintenance' ? 'watch' : gpu.driverStatus === 'legacy' ? 'risk' : 'critical',
    detail: `${gpu.fullName}: ${gpu.driverStatus}.`,
    evidence: gpu.driverEolDate
      ? `Vendor driver support ended ${gpu.driverEolDate}. New titles are no longer validated against this part.`
      : 'No published end-of-life date recorded.',
    sources: [],
  });

  // 4. Thread count against the trajectory of recent releases.
  const recent = [...data.games.values()].filter((g) => g.year >= today.getFullYear() - 3);
  const threadFloors = recent.map((g) => g.requirements.minThreads).filter((v): v is number => v != null);
  if (threadFloors.length) {
    const maxFloor = Math.max(...threadFloors);
    factors.push({
      factor: 'Thread count',
      severity: cpu.threads >= maxFloor * 1.5 ? 'ok' : cpu.threads >= maxFloor ? 'watch' : 'risk',
      detail: `${cpu.threads} threads against a highest published minimum of ${maxFloor} across ${recent.length} recent titles.`,
      evidence: 'Derived from published minimum system requirements, not extrapolated.',
      sources: [],
    });
  }

  // 5. Ray tracing, increasingly a requirement rather than an option.
  factors.push({
    factor: 'Ray tracing hardware',
    severity: gpu.caps.rayTracing ? 'ok' : 'risk',
    detail: gpu.caps.rayTracing ? 'Present.' : 'Absent.',
    evidence: gpu.caps.rayTracing
      ? `${gpu.architecture} has RT hardware.`
      : 'Titles have begun shipping with mandatory ray tracing (Metro Exodus Enhanced, and others since). A part without RT hardware cannot run them.',
    sources: [],
  });

  // 6. Upscaling support — increasingly assumed by default in UE5 titles.
  factors.push({
    factor: 'Upscaling support',
    severity: gpu.caps.upscaling.length > 1 ? 'ok' : gpu.caps.upscaling.length === 1 ? 'watch' : 'risk',
    detail: gpu.caps.upscaling.length ? gpu.caps.upscaling.join(', ').toUpperCase() : 'None.',
    evidence: 'UE5 titles increasingly target upscaled output as the design default rather than native rendering.',
    sources: [],
  });

  // Score is composed from visible factor multipliers and is never shown alone.
  const score = Math.round(
    factors.reduce((acc, f) => acc * SEVERITY_MULTIPLIER[f.severity], 1) * 100,
  );

  return { buildId: build.id, score, factors };
}

// ---------------------------------------------------------------------------
// priceToTarget — the inverse query
// ---------------------------------------------------------------------------

export function priceToTarget(
  games: string[],
  resolution: Resolution,
  targetFps: number,
  budget: number,
  candidates: { cpuIds: string[]; gpuIds: string[] },
  data: EngineData,
  priceOf: PriceLookup,
  opts: { ram?: RamConfig; storage?: Build['storage']; ownedIds?: string[]; requireAllGames?: boolean } = {},
): PriceToTargetResult {
  const ram: RamConfig = opts.ram ?? { totalGB: 16, channels: 2, speedMTs: 3200, type: 'DDR4' };
  const storage = opts.storage ?? 'nvme-gen3';
  const owned = new Set(opts.ownedIds ?? []);

  let priced = 0;
  let total = 0;
  const results: PriceToTargetResult['builds'] = [];

  for (const cpuId of candidates.cpuIds) {
    const cpu = data.cpus.get(cpuId);
    if (!cpu) continue;
    for (const gpuId of candidates.gpuIds) {
      const gpu = data.gpus.get(gpuId);
      if (!gpu) continue;
      total++;

      if (!ramCompatible(cpu, ram)) continue;

      const cpuPrice = owned.has(cpuId) ? 0 : priceOf(cpuId);
      const gpuPrice = owned.has(gpuId) ? 0 : priceOf(gpuId);
      if (cpuPrice == null || gpuPrice == null) continue;
      priced++;

      const cost = cpuPrice + gpuPrice;
      const costExcludingOwned = (owned.has(cpuId) ? 0 : cpuPrice) + (owned.has(gpuId) ? 0 : gpuPrice);
      if (costExcludingOwned > budget) continue;

      const build: Build = {
        id: `p2t__${cpuId}__${gpuId}`,
        cpuId,
        gpuId,
        ram,
        storage,
        target: { resolution, refreshHz: Math.max(60, Math.round(targetFps)) },
      };
      const m = compareBuilds([build], games, [resolution], data);
      const ok = m.cells.filter((c) => c.estimate.status === 'ok' && c.estimate.avgFps != null);
      if (ok.length === 0) continue;
      const worstCaseFps = Math.min(...ok.map((c) => c.estimate.avgFps!));
      const meanFps = m.summary[0].geomeanFps;
      // "Clears the target" means clears it in the WORST requested game, not on
      // average — an average that clears 144fps while one title sits at 70 has
      // not met the brief.
      const clearsTarget = opts.requireAllGames === false ? meanFps >= targetFps : worstCaseFps >= targetFps && ok.length === games.length;

      results.push({ build, cost, costExcludingOwned, worstCaseFps, meanFps, clearsTarget });
    }
  }

  results.sort((a, b) => a.costExcludingOwned - b.costExcludingOwned || b.worstCaseFps - a.worstCaseFps);
  const cheapest = results.find((r) => r.clearsTarget)?.build;

  return {
    builds: results,
    cheapest,
    priceCoverage: { priced, total },
    unmetReason: cheapest
      ? undefined
      : results.length === 0
        ? `No candidate pairing had prices for both parts within the ${budget} budget. Price coverage is ${priced}/${total} of the candidate set — this query is only as good as the pricing data.`
        : `No build within budget clears ${targetFps}fps in the worst of the ${games.length} requested titles at ${resolution}.`,
  };
}

export type { MemoryType };
