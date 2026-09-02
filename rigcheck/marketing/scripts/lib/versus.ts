/**
 * Any two parts of one kind, across a game list, on identical partners.
 *
 * The data behind a versus card. Both parts run with the same processor (for
 * a GPU versus) or the same graphics card (for a CPU versus), the same memory
 * and the same settings, so the gap is the two parts and nothing else. The
 * average is a geometric mean of per-game ratios: it treats +50% and −33% as
 * the same distance, which an arithmetic mean of percentages does not.
 */
import { estimate } from '../../../src/core/engine.ts';
import type { Build, Resolution } from '../../../src/core/types.ts';
import type { buildEngineData } from '../../../src/core/catalogue.ts';
type EngineData = ReturnType<typeof buildEngineData>;

export const VERSUS_GAMES = ['counter-strike-2', 'fortnite', 'cyberpunk-2077', 'baldurs-gate-3', 'total-war-warhammer-iii', 'elden-ring'];
/** The partner a versus runs on: strong enough not to be the limit. */
export const PARTNER = { gpuVersus: ['amd-ryzen-7-9800x3d', 'amd-ryzen-7-7800x3d'], cpuVersus: ['nvidia-geforce-rtx-4090', 'nvidia-geforce-rtx-5080'] };

export interface VersusRow { game: string; a: number | null; b: number | null; pct: number | null; limiterA?: string; limiterB?: string }
export interface VersusSummary { meanPct: number; wins: { a: number; b: number; tie: number } }

/** Geometric mean of b/a across rows where both exist, as a whole percent. */
export function versusSummary(rows: VersusRow[]): VersusSummary {
  const both = rows.filter((r) => r.a != null && r.b != null && r.a! > 0 && r.b! > 0);
  const wins = { a: 0, b: 0, tie: 0 };
  for (const r of both) { if (r.b! > r.a!) wins.b++; else if (r.b! < r.a!) wins.a++; else wins.tie++; }
  if (!both.length) return { meanPct: 0, wins };
  const logMean = both.reduce((s, r) => s + Math.log(r.b! / r.a!), 0) / both.length;
  return { meanPct: Math.round((Math.exp(logMean) - 1) * 100), wins };
}

const part = (data: EngineData, id: string) => {
  const g = data.gpus.get(id); if (g) return { id, kind: 'gpu' as const, name: g.fullName, short: g.brand };
  const c = data.cpus.get(id); if (c) return { id, kind: 'cpu' as const, name: c.fullName, short: c.brand };
  throw new Error(`"${id}" is not a catalogue id`);
};

export function versusData(aId: string, bId: string, data: EngineData, opts: { resolution?: Resolution; games?: string[] } = {}) {
  const a = part(data, aId), b = part(data, bId);
  if (a.kind !== b.kind) throw new Error(`cannot compare a ${a.kind} with a ${b.kind}`);
  if (a.id === b.id) throw new Error('that is the same part twice');
  const kind = a.kind;
  // A CPU versus wants the card out of the way, which means a low resolution.
  const resolution: Resolution = opts.resolution ?? (kind === 'cpu' ? '1080p' : '1440p');
  const partnerId = (kind === 'gpu' ? PARTNER.gpuVersus : PARTNER.cpuVersus).find((id) => (kind === 'gpu' ? data.cpus : data.gpus).has(id));
  if (!partnerId) throw new Error('no partner part in the catalogue');
  const partnerName = kind === 'gpu' ? data.cpus.get(partnerId)!.fullName : data.gpus.get(partnerId)!.fullName;
  const games = (opts.games ?? VERSUS_GAMES).filter((g) => data.games.has(g));
  const build = (id: string): Build => ({
    id: 'versus', cpuId: kind === 'gpu' ? partnerId : id, gpuId: kind === 'gpu' ? id : partnerId,
    ram: { totalGB: 32, channels: 2, speedMTs: 6000, type: 'DDR5' }, storage: 'nvme-gen4', target: { resolution, refreshHz: 144 },
  });
  const ba = build(aId), bb = build(bId);
  const rows: VersusRow[] = games.map((g) => {
    const ea = estimate(ba, g, resolution, data), eb = estimate(bb, g, resolution, data);
    const fa = ea.status === 'ok' ? Math.round(ea.avgFps!) : null, fb = eb.status === 'ok' ? Math.round(eb.avgFps!) : null;
    return { game: data.games.get(g)!.name, a: fa, b: fb, pct: fa && fb ? Math.round((fb / fa - 1) * 100) : null, limiterA: ea.limiter, limiterB: eb.limiter };
  });
  return { kind, a, b, partnerId, partnerName, resolution, rows, summary: versusSummary(rows) };
}
export type VersusData = ReturnType<typeof versusData>;
