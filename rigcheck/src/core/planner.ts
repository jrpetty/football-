/**
 * Build planner: budget plus monitor plus games, out comes a machine.
 *
 * This is the inverse of everything else in the project. The rest of the tool
 * takes a parts list and says how fast it is; here the player states what they
 * want — a panel to feed, a library to run, an amount of money — and the tool
 * has to work backwards.
 *
 * Three things make this harder than picking the fastest GPU under a number:
 *
 *  1. **A CPU and GPU pairing is not two independent choices.** Spending the
 *     whole budget on a GPU and pairing it with a four-core part produces a
 *     machine that is slower than a balanced build costing the same, which is
 *     the single most common expensive mistake in this hobby.
 *  2. **The catalogue does not model motherboards, memory, storage, PSUs,
 *     cases or coolers**, and a plan that ignores them understates a machine by
 *     300-400 pounds. They are priced as class allowances instead, sized from
 *     the actual build: socket decides the board, the transient peak decides
 *     the PSU, the CPU's heat output decides the cooler.
 *  3. **The target is the panel, not a number.** 240Hz at 1080p and 60Hz at
 *     2160p are different machines at the same price, and a planner that
 *     optimises raw frame rate would recommend the wrong one for both.
 */
import { recommendForLibrary, type LibraryRecommendation } from './advisor.ts';
import { estimate, type EngineData } from './engine.ts';
import { estimatePower } from './physics.ts';
import { PRESETS, type Preset } from './presets.ts';
import type { Build, CpuRecord, GpuRecord, MemoryType, RamConfig, Resolution, Storage } from './types.ts';

export type Condition = 'new' | 'used' | 'either';

export interface ComponentPrices {
  motherboard: Record<string, { budget: number; mid: number; high: number; chipsets: string }>;
  memory: Record<string, Record<string, number | null | string>>;
  storage: Record<string, Record<string, number> | string>;
  psu: { watts: number; price: number; tier: string }[];
  case: Record<string, { price: number; label: string }>;
  cooler: Record<string, { price: number; label: string; maxSustainedW: number }>;
}

export interface PlanRequest {
  /** Total spend, in the price tables' currency. Undefined = no budget limit. */
  budget?: number;
  condition: Condition;
  resolution: Resolution;
  refreshHz: number;
  /** Catalogue game ids the player actually plays. Order is not significance. */
  gameIds: string[];
  /**
   * The frame rate to plan for. Defaults to the panel's refresh, which is the
   * right default — but someone on a 240Hz panel playing single-player titles
   * may sensibly target 120 and spend the difference on visuals.
   */
  targetFps?: number;
  /** Storage capacity in GB. Games are large; the default is deliberately not 500. */
  storageGB?: number;
  ramGB?: number;
  /** Prefer a quieter machine over a marginally faster one. */
  preferQuiet?: boolean;
  /**
   * The lowest preset the player is willing to run at.
   *
   * This is not a nicety, it is what stops the planner returning a nonsense
   * answer. "Cheapest build that clears 120fps at 4K" without a quality floor
   * is satisfied by a mid-range card running everything at low — which
   * technically meets the target and is not what anyone means. Defaults to the
   * reference preset, so "meets the goal" means "at high, natively or close to
   * it", which is what a person asking the question has in mind.
   */
  minPreset?: Preset;
}

export interface BomLine {
  category: 'GPU' | 'CPU' | 'Motherboard' | 'Memory' | 'Storage' | 'PSU' | 'Case' | 'Cooler';
  label: string;
  price: number;
  /** Why this line is what it is. Shown next to the money. */
  rationale: string;
  /** True when this is a class allowance rather than a specific catalogue part. */
  allowance: boolean;
  partId?: string;
}

export interface PlanCandidate {
  build: Build;
  gpu: GpuRecord;
  cpu: CpuRecord;
  bom: BomLine[];
  total: number;
  /**
   * Median frame rate across the library AT THE ADVISOR'S CHOSEN SETTINGS.
   *
   * Read this carefully: it is not a measure of how fast the build is. The
   * advisor spends surplus performance on visual quality rather than on frames
   * nobody asked for, so every build that clears a 120fps target reports
   * roughly 120-130fps regardless of price. What separates a 7900 GRE from a
   * 5090 at the same target is `medianQuality`, not this.
   */
  medianFps: number;
  /**
   * Median visual-quality score of the settings each game ends up at. THIS is
   * the axis that separates builds once they all clear the target, and the one
   * a "what does more money buy" comparison has to be made on.
   */
  medianQuality: number;
  /** The preset the library typically lands on. The human-readable version. */
  typicalPreset: string;
  /** Fraction of the library that clears the target. */
  targetHitRate: number;
  library: LibraryRecommendation;
  /** Sustained and transient draw, and the PSU sized from it. */
  powerW: number;
  transientPeakW: number;
  /** 0-1. How evenly the two halves are matched across this library. */
  balance: number;
  /** Ranking score. Only meaningful relative to other candidates in one plan. */
  score: number;
  notes: string[];
}

export interface PlanResult {
  request: PlanRequest;
  /** Best first. Empty when nothing fits the budget. */
  candidates: PlanCandidate[];
  /**
   * The recommendation: the CHEAPEST build that achieves the best hit rate
   * available. Once every game clears the target, more money buys nothing that
   * was asked for, and quietly spending it would be the wrong answer.
   */
  pick: PlanCandidate | null;
  /**
   * The best the full budget can buy, when that differs from the pick. This is
   * the honest other half of the answer: "you can hit your goal for X, and the
   * remaining Y would buy this instead." Null when the pick already spends the
   * budget or there is no budget.
   */
  maxOut: PlanCandidate | null;
  /** What the extra money over the pick actually buys, in plain language. */
  maxOutVerdict?: string;
  /**
   * How the recommendation was arrived at, including any relaxation of the
   * quality floor. If the planner had to drop below what was asked for, it says
   * so here rather than quietly returning a worse answer to the same question.
   */
  pickBasis: string;
  /** Problems with the request itself, in plain language. */
  warnings: string[];
  /** Cheapest total the planner could assemble, for an impossible budget. */
  minimumViableTotal: number | null;
}

const DEFAULT_STORAGE_GB = 1000;

/** Money the machine needs before a CPU or GPU is chosen at all. */
function platformCost(
  cpu: CpuRecord,
  comp: ComponentPrices,
  opts: { ramGB: number; storageGB: number; storage: Storage; psuW: number; airflow: string; cooler: string },
): BomLine[] {
  const lines: BomLine[] = [];

  const mb = comp.motherboard[cpu.socket] ?? comp.motherboard.default;
  lines.push({
    category: 'Motherboard',
    label: `${cpu.socket} board (${mb.chipsets})`,
    price: mb.budget,
    rationale: `${cpu.socket} is fixed by the CPU — it is not a free choice. Budget-tier board; a mid-tier one is about ${mb.mid - mb.budget} more and buys VRM headroom and connectivity, not frame rate.`,
    allowance: true,
  });

  const memType: MemoryType = cpu.memoryType[cpu.memoryType.length - 1] ?? 'DDR5';
  const memRow = comp.memory[memType] ?? {};
  const memPrice = Number(memRow[String(opts.ramGB)] ?? memRow['32'] ?? 90);
  lines.push({
    category: 'Memory',
    label: `${opts.ramGB}GB ${memType} (2 sticks)`,
    price: memPrice,
    rationale: `${cpu.socket} takes ${cpu.memoryType.join(' or ')}. Two sticks, not four — dual channel is worth more than capacity here, and a 2x kit clocks higher than a 4x one on every platform in this catalogue.`,
    allowance: true,
  });

  const storeRow = comp.storage[opts.storage] as Record<string, number> | undefined;
  const storePrice = Number(storeRow?.[String(opts.storageGB)] ?? 70);
  lines.push({
    category: 'Storage',
    label: `${opts.storageGB >= 1000 ? `${opts.storageGB / 1000}TB` : `${opts.storageGB}GB`} ${opts.storage}`,
    price: storePrice,
    rationale:
      opts.storage === 'hdd'
        ? 'A mechanical drive is a false economy for games — texture streaming stalls show up as stutter, not as a loading-screen wait.'
        : 'NVMe. The gap between Gen3 and Gen4 barely shows in frame rate; the gap between an SSD and a hard drive shows in every open-world title.',
    allowance: true,
  });

  const psu = comp.psu.find((p) => p.watts >= opts.psuW) ?? comp.psu[comp.psu.length - 1];
  lines.push({
    category: 'PSU',
    label: `${psu.watts}W ${psu.tier}`,
    price: psu.price,
    rationale: `Sized from the TRANSIENT peak, not the average draw. Modern GPUs spike far above their rated power for microseconds, and it is the spike that trips a supply's protection — an average-sized PSU shuts the machine off under load and looks like a faulty card.`,
    allowance: true,
  });

  const cs = comp.case[opts.airflow] ?? comp.case.good;
  lines.push({
    category: 'Case',
    label: cs.label,
    price: cs.price,
    rationale:
      opts.airflow === 'restricted'
        ? 'A restricted case costs real sustained clocks. It is in here because it is what the budget allows, not because it is a good idea.'
        : 'Airflow is worth more than it costs: a throttling machine is slower than its parts list every time, and no benchmark chart shows it.',
    allowance: true,
  });

  const cool = comp.cooler[opts.cooler] ?? comp.cooler['budget-tower'];
  lines.push({
    category: 'Cooler',
    label: cool.label,
    price: cool.price,
    rationale: `Sized to hold ${cpu.fullName} at its sustained gaming draw without dropping clocks. Budgeted even if the CPU might include one — whether a given SKU ships with a cooler is not in this catalogue, and a plan that is short a cooler on arrival is worse than one that is 32 over.`,
    allowance: true,
  });

  return lines;
}

/**
 * Cheapest cooler class that can hold this CPU's sustained draw.
 *
 * Deliberately never returns 'stock'. Whether a CPU ships with a cooler is a
 * per-SKU fact this catalogue does not carry — AMD's X and F parts and Intel's
 * K parts generally do not — and a plan that assumes a free cooler and is wrong
 * is a plan that is short a cooler on the day the parts arrive. Understating a
 * budget is the one direction that leaves someone unable to finish the build,
 * so this always budgets something and says the assumption out loud.
 */
function coolerFor(cpuW: number, comp: ComponentPrices, preferQuiet: boolean): string {
  const order = ['budget-tower', 'premium-air', 'aio'];
  for (const k of order) {
    const c = comp.cooler[k];
    if (!c) continue;
    // A quiet build wants headroom rather than a cooler running at its limit.
    const need = preferQuiet ? cpuW * 1.35 : cpuW * 1.05;
    if (c.maxSustainedW >= need) return k;
  }
  return 'aio';
}

function priceOf(id: string, tables: { newP: Record<string, number>; usedP: Record<string, number> }, condition: Condition): number | null {
  const n = tables.newP[id];
  const u = tables.usedP[id];
  if (condition === 'new') return n ?? null;
  if (condition === 'used') return u ?? null;
  // 'either': the cheaper of the two, since that is what someone shopping both would pay.
  const options = [n, u].filter((x): x is number => typeof x === 'number');
  return options.length ? Math.min(...options) : null;
}

/**
 * Plan a build.
 *
 * The search is a full cross of priced GPUs against priced CPUs, which sounds
 * expensive and is not: the price tables are the constraint, so it is a few
 * thousand combinations, each evaluated against a handful of games. Filtering
 * on price BEFORE estimating is what keeps it cheap.
 */
export function planBuild(
  req: PlanRequest,
  data: EngineData,
  prices: { newP: Record<string, number>; usedP: Record<string, number> },
  comp: ComponentPrices,
): PlanResult {
  const warnings: string[] = [];
  const target = req.targetFps ?? req.refreshHz;
  const ramGB = req.ramGB ?? (req.resolution === '1080p' ? 16 : 32);
  const storageGB = req.storageGB ?? DEFAULT_STORAGE_GB;
  const games = req.gameIds.filter((g) => data.games.has(g));

  if (!games.length) {
    warnings.push('No games selected, so there is nothing to plan against. A build is only right relative to what it has to run.');
  }
  if (req.gameIds.length !== games.length) {
    warnings.push(`${req.gameIds.length - games.length} selected game(s) are not in the catalogue and were ignored.`);
  }

  const gpuIds = [...data.gpus.keys()].filter((id) => priceOf(id, prices, req.condition) != null);
  const cpuIds = [...data.cpus.keys()].filter((id) => priceOf(id, prices, req.condition) != null);
  if (!gpuIds.length || !cpuIds.length) {
    warnings.push(`No parts carry a ${req.condition} price, so nothing can be planned. Check data/pricing/.`);
    return { request: req, candidates: [], pick: null, maxOut: null, pickBasis: 'No build could be assembled.', warnings, minimumViableTotal: null };
  }

  const candidates: PlanCandidate[] = [];
  interface Screened {
    build: Build;
    gpu: GpuRecord;
    cpu: CpuRecord;
    gpuPrice: number;
    cpuPrice: number;
    platform: BomLine[];
    total: number;
    power: ReturnType<typeof estimatePower>;
    airflow: string;
    roughFps: number;
    roughHit: number;
    roughBalance: number;
  }
  const screened: Screened[] = [];
  let cheapestTotal: number | null = null;

  for (const cpuId of cpuIds) {
    const cpu = data.cpus.get(cpuId)!;
    const cpuPrice = priceOf(cpuId, prices, req.condition)!;
    for (const gpuId of gpuIds) {
      const gpu = data.gpus.get(gpuId)!;
      if (gpu.formFactor === 'igpu') continue;
      const gpuPrice = priceOf(gpuId, prices, req.condition)!;

      const ram: RamConfig = {
        totalGB: ramGB,
        channels: 2,
        speedMTs: cpu.memoryType.includes('DDR5') ? 6000 : 3600,
        type: cpu.memoryType.includes('DDR5') ? 'DDR5' : cpu.memoryType[0],
      };
      const build: Build = {
        id: `plan-${cpuId}-${gpuId}`,
        cpuId,
        gpuId,
        ram,
        storage: 'nvme-gen4',
        target: { resolution: req.resolution, refreshHz: req.refreshHz },
      };

      const power = estimatePower(gpu, cpu, build);
      const cooler = coolerFor(power.cpuW, comp, !!req.preferQuiet);
      const airflow = power.totalW > 450 ? 'good' : 'moderate';
      const platform = platformCost(cpu, comp, {
        ramGB,
        storageGB,
        storage: 'nvme-gen4',
        psuW: power.recommendedPsuW,
        airflow,
        cooler,
      });

      const total =
        gpuPrice + cpuPrice + platform.reduce((s, l) => s + l.price, 0);
      cheapestTotal = cheapestTotal == null ? total : Math.min(cheapestTotal, total);
      if (req.budget != null && total > req.budget) continue;

      // Only now, having survived the budget, is it worth estimating.
      if (!games.length) continue;

      // PHASE 1 — one estimate per game at the reference preset, native.
      //
      // The full settings search is 40 estimates per game, and a budget this
      // wide admits a thousand candidate pairings; running the search on all of
      // them is a hundred thousand estimates and several seconds of a person
      // staring at a spinner. A single reference-preset estimate orders
      // candidates almost as well and costs a fortieth, so the expensive search
      // runs only on the shortlist that survives.
      let sum = 0;
      let n = 0;
      let roughHits = 0;
      let balSum = 0;
      for (const g of games) {
        const e = estimate(build, g, req.resolution, data, { airflowTier: airflow as never });
        if (e.status !== 'ok' || e.avgFps == null) continue;
        sum += Math.log(e.avgFps);
        n++;
        if (e.avgFps >= target) roughHits++;
        if (e.gpuBoundRatio != null) balSum += 1 - Math.abs(e.gpuBoundRatio - 0.5) * 2;
      }
      if (!n) continue;
      screened.push({
        build, gpu, cpu, gpuPrice, cpuPrice, platform, total, power, airflow,
        roughFps: Math.exp(sum / n),
        roughHit: roughHits / n,
        roughBalance: balSum / n,
      });
    }
  }

  // Shortlist for the expensive pass.
  //
  // Phase 1 ranks on native frame rate at the reference preset, which is a good
  // proxy for raw capability and a BAD proxy for "can this reach the target
  // once settings are tuned" — dropping a preset and enabling upscaling can
  // nearly double a frame rate, so a build that misses at reference may clear
  // the target comfortably in practice. A shortlist taken only from the top of
  // that ranking therefore throws away exactly the cheap candidates the
  // recommendation is looking for.
  //
  // So it is sampled three ways: the fastest, the best frame rate per pound,
  // and the best in each price band. The last is what guarantees the
  // cheapest-that-meets-the-goal search has something to find at every price
  // point rather than only at the top.
  const shortlist: Screened[] = [];
  const add = (c: Screened | undefined) => {
    if (c && !shortlist.some((x) => x.build.id === c.build.id)) shortlist.push(c);
  };
  for (const c of [...screened].sort((a, b) => b.roughFps - a.roughFps).slice(0, 12)) add(c);
  for (const c of [...screened].sort((a, b) => b.roughFps / b.total - a.roughFps / a.total).slice(0, 12)) add(c);

  const lo = Math.min(...screened.map((c) => c.total));
  const hi = Math.max(...screened.map((c) => c.total));
  const BANDS = 8;
  for (let i = 0; i < BANDS; i++) {
    const from = lo + ((hi - lo) * i) / BANDS;
    const to = lo + ((hi - lo) * (i + 1)) / BANDS;
    const inBand = screened.filter((c) => c.total >= from && c.total <= to);
    if (!inBand.length) continue;
    add([...inBand].sort((a, b) => b.roughFps - a.roughFps)[0]);
    // Balance matters more in the cheap bands, where a lopsided pairing is the
    // classic mistake: a big GPU starved by a four-core CPU, or the reverse.
    add([...inBand].sort((a, b) => b.roughBalance * b.roughFps - a.roughBalance * a.roughFps)[0]);
  }

  for (const sc of shortlist) {
    {
      const { build, gpu, cpu, gpuPrice, cpuPrice, platform, total, power, airflow } = sc;

      // PHASE 2 — the real settings search, on the shortlist only.
      const library = recommendForLibrary(build, games, req.resolution, target, data, { airflowTier: airflow as never });
      const chosen = library.perGame.map((r) => r.best ?? r.fastest).filter((c): c is NonNullable<typeof c> => !!c);
      if (!chosen.length) continue;
      const median = <T,>(xs: T[], key: (t: T) => number): number => {
        const v = xs.map(key).sort((a, b) => a - b);
        return v[Math.floor(v.length / 2)];
      };
      const medianFps = median(chosen, (c) => c.avgFps);
      const medianQuality = median(chosen, (c) => c.quality);
      // The preset the library mostly lands on, which is what someone actually
      // wants to hear: "this runs your games at high" beats a quality integer.
      const presetCounts = new Map<string, number>();
      for (const c of chosen) presetCounts.set(c.config.preset, (presetCounts.get(c.config.preset) ?? 0) + 1);
      const typicalPreset = [...presetCounts.entries()].sort((a, b) => b[1] - a[1])[0]?.[0] ?? 'high';
      const hit = library.perGame.filter((r) => r.best != null).length / library.perGame.length;

      // Balance: how close the two halves are across the library. A build that
      // is GPU-bound in everything has money in the wrong place, and so does
      // one that is CPU-bound in everything. Read off the estimates the advisor
      // already produced rather than re-running the engine — the advisor's pick
      // is the configuration this machine would actually be run at, which is a
      // better place to judge balance than an arbitrary reference config, and
      // it halves the planning time.
      let balSum = 0;
      let balN = 0;
      for (const r of library.perGame) {
        const e = (r.best ?? r.fastest)?.estimate;
        if (!e || e.status !== 'ok' || e.gpuBoundRatio == null) continue;
        balSum += 1 - Math.abs(e.gpuBoundRatio - 0.5) * 2;
        balN++;
      }
      const balance = balN ? balSum / balN : 0;

      candidates.push({
        build,
        gpu,
        cpu,
        bom: [
          {
            category: 'GPU',
            label: gpu.fullName,
            price: gpuPrice,
            rationale: `The single largest lever on frame rate at ${req.resolution}, and where most of a gaming budget should go.`,
            allowance: false,
            partId: gpu.id,
          },
          {
            category: 'CPU',
            label: cpu.fullName,
            price: cpuPrice,
            rationale: `${cpu.cores}C/${cpu.threads}T on ${cpu.socket}. Sets the frame-rate ceiling the GPU can be fed up to, which is what decides whether a fast panel is usable.`,
            allowance: false,
            partId: cpu.id,
          },
          ...platform,
        ],
        total,
        medianFps,
        medianQuality,
        typicalPreset,
        targetHitRate: hit,
        library,
        powerW: power.totalW,
        transientPeakW: power.transientPeakW,
        balance,
        score: 0,
        notes: [],
      });
    }
  }

  if (!candidates.length) {
    if (req.budget != null && cheapestTotal != null && cheapestTotal > req.budget) {
      warnings.push(
        `Nothing can be built for ${req.budget}. The cheapest complete machine the planner can assemble from priced parts is about ${Math.round(cheapestTotal)}, and that includes the board, memory, storage, power supply, case and cooler that a CPU-and-GPU-only budget forgets.`,
      );
    }
    return { request: req, candidates: [], pick: null, maxOut: null, pickBasis: 'No build could be assembled.', warnings, minimumViableTotal: cheapestTotal };
  }

  // Score. Clearing the target across the library dominates, because that is
  // what was asked for; median frame rate breaks ties among builds that all
  // clear it; balance is a modest term that penalises lopsided machines; and
  // leftover budget is worth a little, because spending less for the same
  // result is strictly better.
  const maxQuality = Math.max(1, ...candidates.map((c) => c.medianQuality));
  for (const c of candidates) {
    const value = req.budget ? 1 - c.total / req.budget : 0;
    // Quality, not frame rate. Once a build clears the target, extra
    // performance is spent on how the game LOOKS, so ranking on frames would
    // rank every qualifying build as identical.
    c.score = c.targetHitRate * 100 + (c.medianQuality / maxQuality) * 25 + c.balance * 10 + value * 8;

    if (c.targetHitRate === 1) c.notes.push(`Every one of your games clears ${target}fps at ${req.resolution}.`);
    else c.notes.push(`${Math.round(c.targetHitRate * 100)}% of your games clear ${target}fps at ${req.resolution}; the rest need settings turned down.`);
    if (c.balance < 0.35) {
      const e = estimate(c.build, games[0], req.resolution, data);
      const side = (e.gpuBoundRatio ?? 0.5) > 0.5 ? 'GPU' : 'CPU';
      c.notes.push(`Lopsided: the ${side} is the limit in most of your library. Moving money to that side would buy more than anything else here.`);
    }
    if (req.budget) c.notes.push(`Leaves ${Math.round(req.budget - c.total)} of the ${req.budget} budget.`);
  }

  candidates.sort((a, b) => b.score - a.score);

  // The recommendation is the CHEAPEST build achieving the best hit rate that
  // is achievable at all — not the highest-scoring one. Someone who says
  // "£2500, 4K, 120Hz" and can have it for £1400 should be told that, not sold
  // £1100 of hardware they did not ask for. Within a hit rate, ties break on
  // median frame rate so the cheap pick is not also the slowest.
  // "Meets the goal" is target frame rate AND a preset floor. Without the
  // floor, the cheapest qualifying build is whatever hits the number at low
  // settings, which satisfies the letter of the request and none of its intent.
  const floor = req.minPreset ?? 'high';
  const floorIdx = PRESETS.indexOf(floor);
  const bestHit = Math.max(...candidates.map((c) => c.targetHitRate));

  let atBestHit = candidates.filter(
    (c) => c.targetHitRate >= bestHit - 1e-9 && PRESETS.indexOf(c.typicalPreset as Preset) >= floorIdx,
  );
  let pickBasis = `Cheapest build that runs your library at ${target}fps, ${req.resolution}, on ${floor} settings or better.`;

  // Relax the floor one rung at a time rather than giving up, and say so.
  if (!atBestHit.length) {
    for (let i = floorIdx - 1; i >= 0 && !atBestHit.length; i--) {
      atBestHit = candidates.filter(
        (c) => c.targetHitRate >= bestHit - 1e-9 && PRESETS.indexOf(c.typicalPreset as Preset) >= i,
      );
      if (atBestHit.length) {
        pickBasis = `No build in this budget reaches ${target}fps at ${req.resolution} on ${floor} settings. This is the cheapest that gets there on ${PRESETS[i]} — the quality floor was relaxed to answer the question rather than returning nothing.`;
      }
    }
  }
  if (!atBestHit.length) atBestHit = candidates.filter((c) => c.targetHitRate >= bestHit - 1e-9);

  const cheapestBand = Math.min(...atBestHit.map((c) => c.total));
  // Within the cheapest band, a lopsided pairing has to be meaningfully faster
  // to win. A twelve-core CPU behind an 8GB card is not a build anyone should
  // be handed, even if it happens to median a frame higher.
  const pick =
    atBestHit
      .filter((c) => c.total <= cheapestBand * 1.06)
      .sort(
        (a, b) =>
          b.medianQuality * (0.85 + 0.15 * b.balance) - a.medianQuality * (0.85 + 0.15 * a.balance) ||
          a.total - b.total,
      )[0] ?? candidates[0];

  // VRAM is the one spec where a shortfall is a cliff rather than a slope, and
  // an 8GB card at 1440p or above is the most common way to walk off it.
  const pickVram = pick.gpu.vramGB ?? 0;
  if (pickVram > 0 && pickVram <= 8 && req.resolution !== '1080p') {
    pick.notes.push(
      `${pick.gpu.fullName} has ${pickVram}GB of VRAM, which is the tight end for ${req.resolution}. The frame rates above already account for it, but VRAM is a cliff rather than a slope — a title that exceeds it does not run slightly slower, it stutters. Worth paying for more if a texture-heavy library is in your future.`,
    );
  }

  // And the other half of the answer: what the rest of the budget would buy.
  const maxOutCandidate =
    [...candidates].sort(
      (a, b) => b.targetHitRate - a.targetHitRate || b.medianQuality - a.medianQuality || a.total - b.total,
    )[0] ?? null;
  const worthShowing =
    !!maxOutCandidate &&
    maxOutCandidate.build.id !== pick.build.id &&
    maxOutCandidate.total > pick.total * 1.05 &&
    (maxOutCandidate.medianQuality > pick.medianQuality + 4 ||
      maxOutCandidate.targetHitRate > pick.targetHitRate + 0.01);

  let maxOutVerdict: string | undefined;
  if (worthShowing && maxOutCandidate) {
    const extra = Math.round(maxOutCandidate.total - pick.total);
    const hitGain = maxOutCandidate.targetHitRate - pick.targetHitRate;
    maxOutVerdict =
      hitGain > 0.01
        ? `Another ${extra} takes ${Math.round(maxOutCandidate.targetHitRate * 100)}% of your library over ${target}fps instead of ${Math.round(pick.targetHitRate * 100)}%. That is buying the thing you actually asked for.`
        : maxOutCandidate.typicalPreset !== pick.typicalPreset
          ? `Another ${extra} does not buy more frames — both builds hold ${target}fps. It buys better-looking ones: ${maxOutCandidate.typicalPreset} settings instead of ${pick.typicalPreset}, or less reliance on upscaling to get there.`
          : `Another ${extra} buys some visual headroom at the same ${target}fps, but not a preset step. On what you have told the planner, it is not money well spent — it is headroom for a future game or a faster panel.`;
  }

  return {
    request: req,
    candidates: candidates.slice(0, 8),
    pick,
    maxOut: worthShowing ? maxOutCandidate : null,
    maxOutVerdict,
    pickBasis,
    warnings,
    minimumViableTotal: cheapestTotal,
  };
}
