/**
 * What a processor and a graphics card are worth to each other.
 *
 * The question people actually ask is not "how fast is this card" but "is this
 * card worth putting in MY machine", and the honest answer is often no — not
 * because the card is slow, but because the processor in front of it hands out
 * the same frame budget regardless of what sits behind it. Put four cards
 * spanning 33% of raw speed on a 2017 quad-core and six of eight games land
 * within two frames of each other. Nothing in a spec sheet says that.
 *
 * This module produces that answer from the estimator, for any pair. It exists
 * because the analysis was first done by hand in a throwaway script, and a
 * finding you cannot re-run is a finding you cannot trust the second time.
 *
 * Three things it is careful about:
 *
 *  1. The ceiling is a PLATFORM, not a processor. You cannot put DDR4 in an
 *     AM5 board, so "what a current chip would give you" necessarily includes
 *     its memory. Calling that a CPU upgrade would understate what it costs
 *     and overstate what a bare chip swap buys, so it is named for what it is.
 *  2. A blocked game is not a slow game. A card without mesh shaders does not
 *     render Alan Wake 2 badly, it does not launch it. Those rows carry the
 *     gate that fired and are kept out of every average.
 *  3. Headroom is reported per game and as a median, never as a single mean.
 *     The spread is the story: the same pair loses half its frames in one
 *     title and a tenth in another.
 */
import { estimate } from './engine.ts';
import { machineReport } from './analysis.ts';
import type { Build, FpsEstimate, Limiter, RamConfig, Resolution, Storage } from './types.ts';
import type { buildEngineData } from './catalogue.ts';

type EngineData = ReturnType<typeof buildEngineData>;

/** The games a pairing is judged on: a CPU-heavy spread, not a GPU showcase. */
export const PAIRING_GAMES = [
  'counter-strike-2', 'fortnite', 'cyberpunk-2077', 'baldurs-gate-3',
  'total-war-warhammer-iii', 'elden-ring', 'call-of-duty-black-ops-6', 'alan-wake-2',
];

/**
 * The current-platform reference, best first.
 *
 * A list rather than one id so a catalogue edit degrades to the next chip
 * instead of throwing. These are the fastest gaming parts on offer; the point
 * of the ceiling is that it is not the limit, so a mid-range reference would
 * understate every headroom figure on the card.
 */
export const CEILING_CPUS = ['amd-ryzen-7-9800x3d', 'amd-ryzen-7-7800x3d', 'amd-ryzen-9-9900x3d'];

/** What a current platform runs: DDR5 in dual channel, because it cannot run DDR4. */
export const CEILING_RAM: RamConfig = { totalGB: 32, channels: 2, speedMTs: 6000, type: 'DDR5' };

/** A 2017-era pairing's memory, unless the caller knows better. */
export const LEGACY_RAM: RamConfig = { totalGB: 16, channels: 2, speedMTs: 2400, type: 'DDR4' };

export interface PairingRow {
  gameId: string;
  game: string;
  /** Null when the game will not launch, or the catalogue cannot estimate it. */
  fps: number | null;
  low1PctFps: number | null;
  limiter: Limiter | undefined;
  status: FpsEstimate['status'];
  /** Gate codes when status is WILL_NOT_RUN — e.g. MESH_SHADER_REQUIRED. */
  blockedBy: string[];
  blockedDetail: string | null;
  /** The same card on the reference platform. Null when blocked either side. */
  ceilingFps: number | null;
  headroomFps: number | null;
  headroomPct: number | null;
}

export interface PairingReport {
  cpu: { id: string; name: string; short: string };
  gpu: { id: string; name: string; short: string };
  resolution: Resolution;
  ram: RamConfig;
  ceiling: { id: string; name: string; short: string; ram: RamConfig } | null;
  rows: PairingRow[];
  /** Counts across rows that produced a number. */
  counts: { total: number; ok: number; cpuBound: number; gpuBound: number; blocked: number };
  /** Median headroom across rows where both sides ran. Null when none did. */
  medianHeadroomPct: number | null;
  /** The row losing the most, by percent. */
  worst: PairingRow | null;
  /**
   * 'cpu-wall' when the processor is the limit in most games that ran,
   * 'gpu-wall' when the card is, 'balanced' when neither dominates. This is a
   * summary of limiters, not a value judgement: a gpu-wall verdict on a slow
   * card is the healthy case, because the card is the part you can replace.
   */
  verdict: 'cpu-wall' | 'gpu-wall' | 'balanced' | 'unknown';
  power: { totalW: number; psuW: number } | null;
}

const short = (full: string) => full.replace(/^(NVIDIA|AMD|Intel)\s+/, '');

const mk = (cpuId: string, gpuId: string, ram: RamConfig, resolution: Resolution, storage: Storage): Build => ({
  id: 'pairing', cpuId, gpuId, ram, storage,
  target: { resolution, refreshHz: 144 },
});

/** The reference platform to measure headroom against, or null if none is in the catalogue. */
export function ceilingCpu(data: EngineData, exclude?: string) {
  const id = CEILING_CPUS.find((c) => c !== exclude && data.cpus.has(c));
  if (!id) return null;
  const c = data.cpus.get(id)!;
  return { id, name: c.fullName, short: short(c.fullName), ram: CEILING_RAM };
}

export interface PairingOptions {
  resolution?: Resolution;
  games?: string[];
  ram?: RamConfig;
  storage?: Storage;
}

/**
 * One processor, one graphics card, across a game list — and what the same
 * card would do on a current platform.
 */
export function pairingReport(cpuId: string, gpuId: string, data: EngineData, opts: PairingOptions = {}): PairingReport {
  const cpu = data.cpus.get(cpuId);
  const gpu = data.gpus.get(gpuId);
  if (!cpu) throw new Error(`"${cpuId}" is not a processor in the catalogue`);
  if (!gpu) throw new Error(`"${gpuId}" is not a graphics card in the catalogue`);
  const resolution = opts.resolution ?? '1080p';
  const ram = opts.ram ?? LEGACY_RAM;
  const storage = opts.storage ?? 'nvme-gen3';
  const games = (opts.games ?? PAIRING_GAMES).filter((g) => data.games.has(g));
  const ceiling = ceilingCpu(data, cpuId);

  const build = mk(cpuId, gpuId, ram, resolution, storage);
  const rows: PairingRow[] = games.map((gameId) => {
    const e = estimate(build, gameId, resolution, data);
    const fps = e.status === 'ok' ? Math.round(e.avgFps!) : null;
    let ceilingFps: number | null = null;
    if (fps != null && ceiling) {
      const c = estimate(mk(ceiling.id, gpuId, ceiling.ram, resolution, 'nvme-gen4'), gameId, resolution, data);
      if (c.status === 'ok') ceilingFps = Math.round(c.avgFps!);
    }
    return {
      gameId,
      game: data.games.get(gameId)!.name,
      fps,
      low1PctFps: e.status === 'ok' && e.low1PctFps != null ? Math.round(e.low1PctFps) : null,
      limiter: e.limiter,
      status: e.status,
      blockedBy: e.gateFailures.map((f) => f.code),
      blockedDetail: e.gateFailures[0]?.detail ?? null,
      ceilingFps,
      headroomFps: fps != null && ceilingFps != null ? ceilingFps - fps : null,
      headroomPct: fps != null && ceilingFps != null && fps > 0 ? Math.round((ceilingFps / fps - 1) * 100) : null,
    };
  });

  const ok = rows.filter((r) => r.fps != null);
  const cpuBound = ok.filter((r) => r.limiter === 'cpu').length;
  const gpuBound = ok.filter((r) => r.limiter === 'gpu').length;
  const withHeadroom = rows.filter((r) => r.headroomPct != null);
  const rep = machineReport(build, data, {});

  return {
    cpu: { id: cpuId, name: cpu.fullName, short: short(cpu.fullName) },
    gpu: { id: gpuId, name: gpu.fullName, short: short(gpu.fullName) },
    resolution, ram, ceiling, rows,
    counts: { total: rows.length, ok: ok.length, cpuBound, gpuBound, blocked: rows.filter((r) => r.status === 'WILL_NOT_RUN').length },
    medianHeadroomPct: median(withHeadroom.map((r) => r.headroomPct!)),
    worst: withHeadroom.length ? withHeadroom.reduce((a, b) => (b.headroomPct! > a.headroomPct! ? b : a)) : null,
    verdict: !ok.length ? 'unknown' : cpuBound > gpuBound && cpuBound >= ok.length / 2 ? 'cpu-wall'
      : gpuBound > cpuBound && gpuBound >= ok.length / 2 ? 'gpu-wall' : 'balanced',
    power: rep ? { totalW: Math.round(rep.power.totalW), psuW: rep.power.recommendedPsuW } : null,
  };
}

/** Middle value, averaging the two middles on an even count. Null when empty. */
export function median(xs: number[]): number | null {
  if (!xs.length) return null;
  const s = [...xs].sort((a, b) => a - b);
  const m = s.length >> 1;
  return s.length % 2 ? s[m] : Math.round((s[m - 1] + s[m]) / 2);
}

export interface ConvergenceRow {
  gameId: string;
  game: string;
  /** Per-card frame rates in the order the cards were given. Null = blocked. */
  fps: (number | null)[];
  spreadFps: number | null;
  spreadPct: number | null;
  /** True when every card that ran landed within the tolerance. */
  converged: boolean;
}

export interface Convergence {
  cpu: { id: string; name: string; short: string };
  gpus: { id: string; name: string; short: string; index: number | null }[];
  resolution: Resolution;
  rows: ConvergenceRow[];
  /** How many rows the cards were indistinguishable in, out of those that ran. */
  convergedCount: number;
  comparableCount: number;
  toleranceFps: number;
}

/**
 * Several cards on one processor: where do they stop being different?
 *
 * The finding this exists for. Cards are sold on raw speed, and raw speed is
 * what stops mattering first when the processor is the limit. A card 11%
 * faster on paper delivering one extra frame is the clearest possible argument
 * for spending the money on the other half of the machine — but only if you
 * can show it, per game, with the cards named.
 *
 * `toleranceFps` is what counts as "the same": frames that close are inside
 * the model's own uncertainty and would be inside run-to-run variance on real
 * hardware too.
 */
export function convergence(
  cpuId: string, gpuIds: string[], data: EngineData,
  opts: PairingOptions & { toleranceFps?: number } = {},
): Convergence {
  const cpu = data.cpus.get(cpuId);
  if (!cpu) throw new Error(`"${cpuId}" is not a processor in the catalogue`);
  if (gpuIds.length < 2) throw new Error('convergence needs at least two graphics cards');
  const resolution = opts.resolution ?? '1080p';
  const ram = opts.ram ?? LEGACY_RAM;
  const storage = opts.storage ?? 'nvme-gen3';
  const tol = opts.toleranceFps ?? 3;
  const games = (opts.games ?? PAIRING_GAMES).filter((g) => data.games.has(g));

  const gpus = gpuIds.map((id) => {
    const g = data.gpus.get(id);
    if (!g) throw new Error(`"${id}" is not a graphics card in the catalogue`);
    return { id, name: g.fullName, short: short(g.fullName), index: null as number | null };
  });

  const rows: ConvergenceRow[] = games.map((gameId) => {
    const fps = gpuIds.map((gid) => {
      const e = estimate(mk(cpuId, gid, ram, resolution, storage), gameId, resolution, data);
      return e.status === 'ok' ? Math.round(e.avgFps!) : null;
    });
    const ran = fps.filter((f): f is number => f != null);
    // One card that ran is not a comparison; a row needs at least two.
    const comparable = ran.length >= 2;
    const lo = comparable ? Math.min(...ran) : null;
    const hi = comparable ? Math.max(...ran) : null;
    return {
      gameId, game: data.games.get(gameId)!.name, fps,
      spreadFps: comparable ? hi! - lo! : null,
      spreadPct: comparable && lo! > 0 ? Math.round((hi! / lo! - 1) * 100) : null,
      converged: comparable ? hi! - lo! <= tol : false,
    };
  });

  const comparable = rows.filter((r) => r.spreadFps != null);
  return {
    cpu: { id: cpuId, name: cpu.fullName, short: short(cpu.fullName) },
    gpus, resolution, rows,
    convergedCount: comparable.filter((r) => r.converged).length,
    comparableCount: comparable.length,
    toleranceFps: tol,
  };
}
