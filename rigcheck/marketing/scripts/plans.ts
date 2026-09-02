/**
 * The build planner, parameterised.
 *
 * `planTier` takes a budget, a resolution, a refresh rate and a game list and
 * returns everything a build card needs. Run with no arguments it writes the
 * four standard tiers to builds.json; the calendar calls it for any budget it
 * likes, which is what makes "build of the week" a loop rather than new work.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { buildEngineData } from '../../src/core/catalogue.ts';
import { planBuild } from '../../src/core/planner.ts';
import { estimate } from '../../src/core/engine.ts';
import { machineReport } from '../../src/core/analysis.ts';
import type { Resolution } from '../../src/core/types.ts';

const load = (p: string) => JSON.parse(readFileSync(p, 'utf8'));

export interface Tier { name: string; budget: number; resolution: Resolution; refreshHz: number; games?: string[] }

export function loadPlanContext() {
  const data = buildEngineData({
    gpus: load('data/catalogue/gpus.json'), cpus: load('data/catalogue/cpus.json'),
    games: load('data/catalogue/games.json'), references: load('data/catalogue/references.json'),
  });
  return {
    data,
    newP: load('data/pricing/gbp-new.json').prices as Record<string, number>,
    usedP: load('data/pricing/gbp-used.json').prices as Record<string, number>,
    comp: load('data/pricing/components-gbp.json'),
  };
}
export type PlanContext = ReturnType<typeof loadPlanContext>;

export const GAMES = ['counter-strike-2', 'fortnite', 'cyberpunk-2077', 'baldurs-gate-3', 'call-of-duty-black-ops-6', 'elden-ring'];
const TIERS: { name: string; budget: number; resolution: Resolution; refreshHz: number }[] = [
  { name: 'The £700 one', budget: 700, resolution: '1080p', refreshHz: 144 },
  { name: 'The £1,100 one', budget: 1100, resolution: '1440p', refreshHz: 144 },
  { name: 'The £1,800 one', budget: 1800, resolution: '1440p', refreshHz: 165 },
  { name: 'The £2,600 one', budget: 2600, resolution: '2160p', refreshHz: 144 },
];

export function planTier(t: Tier, ctx: PlanContext) {
  const { data, newP, usedP, comp } = ctx;
  const games = (t.games ?? GAMES).filter((g) => data.games.has(g));
  const r = planBuild({ budget: t.budget, resolution: t.resolution, refreshHz: t.refreshHz, condition: 'new', gameIds: games }, data, { newP, usedP }, comp);
  if (!r.pick) return null;
  const b = r.pick.build;
  const cpu = data.cpus.get(b.cpuId)!, gpu = data.gpus.get(b.gpuId)!;
  const rows = games.map((g) => {
    const e = estimate(b, g, t.resolution, data);
    return {
      game: data.games.get(g)!.name,
      fps: e.status === 'ok' ? Math.round(e.avgFps!) : null,
      low: e.status === 'ok' ? Math.round(e.band!.low) : null,
      high: e.status === 'ok' ? Math.round(e.band!.high) : null,
      low1: e.status === 'ok' ? Math.round(e.low1PctFps ?? 0) : null,
      limiter: e.limiter,
    };
  });
  const rep = machineReport(b, data, {});
  // A fixed-resolution column so the four builds can be put on one ladder.
  // Comparing each at its OWN target made the £2,012 machine and the £582 one
  // both read "65fps" — true, at 2160p and 1080p respectively, and a completely
  // misleading thing to put side by side.
  const at1440 = estimate(b, 'cyberpunk-2077', '1440p', data);
  return {
    ...t,
    games: undefined,
    total: Math.round(r.pick!.total),
    cpu: cpu.fullName, gpu: gpu.fullName,
    cpuShort: cpu.brand, gpuShort: gpu.brand,
    vram: gpu.vramGB, cores: cpu.cores, threads: cpu.threads,
    ram: `${b.ram.totalGB}GB ${b.ram.type}`, storage: b.storage,
    powerW: rep ? Math.round(rep.power.totalW) : null,
    // Two different numbers, and publishing only the first was a trap.
    // psuW is the model's FLOOR — draw plus headroom, rounded up to 50W. The
    // parts list prices an actual purchasable unit, which is a size or two
    // above that floor. A header reading "400W supply" next to a bill of
    // materials containing a 550W PSU invites a reader to buy the 400W.
    psuW: rep?.power.recommendedPsuW ?? null,
    psuPartW: (() => {
      const line = r.pick!.bom.find((l: any) => l.category === 'PSU');
      const m = /(\d{3,4})\s*W/i.exec(line?.label ?? '');
      return m ? Number(m[1]) : null;
    })(),
    cyberpunk1440: at1440.status === 'ok' ? Math.round(at1440.avgFps!) : null,
    bom: r.pick!.bom.map((l: any) => ({ cat: l.category, price: Math.round(l.price), part: l.label ?? '' })),
    rows,
  };
}
export type PlannedBuild = NonNullable<ReturnType<typeof planTier>>;

if (process.argv[1]?.endsWith('plans.ts')) {
  const ctx = loadPlanContext();
  const out = TIERS.map((t) => planTier(t, ctx)).filter(Boolean);
  writeFileSync('marketing/builds.json', JSON.stringify(out, null, 2));
  console.log(JSON.stringify(out.map((o) => ({ n: o!.name, total: o!.total, cpu: o!.cpuShort, gpu: o!.gpuShort, w: o!.powerW, psuFloor: o!.psuW, psuPart: o!.psuPartW })), null, 1));
}
