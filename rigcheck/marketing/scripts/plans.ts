import { readFileSync, writeFileSync } from 'node:fs';
import { buildEngineData } from '../../src/core/catalogue.ts';
import { planBuild } from '../../src/core/planner.ts';
import { estimate } from '../../src/core/engine.ts';
import { machineReport } from '../../src/core/analysis.ts';
import type { Resolution } from '../../src/core/types.ts';

const load = (p: string) => JSON.parse(readFileSync(p, 'utf8'));
const data = buildEngineData({
  gpus: load('data/catalogue/gpus.json'), cpus: load('data/catalogue/cpus.json'),
  games: load('data/catalogue/games.json'), references: load('data/catalogue/references.json'),
});
const newP = load('data/pricing/gbp-new.json').prices as Record<string, number>;
const usedP = load('data/pricing/gbp-used.json').prices as Record<string, number>;
const comp = load('data/pricing/components-gbp.json');

const GAMES = ['counter-strike-2', 'fortnite', 'cyberpunk-2077', 'baldurs-gate-3', 'call-of-duty-black-ops-6', 'elden-ring'];
const TIERS: { name: string; budget: number; resolution: Resolution; refreshHz: number }[] = [
  { name: 'The £700 one', budget: 700, resolution: '1080p', refreshHz: 144 },
  { name: 'The £1,100 one', budget: 1100, resolution: '1440p', refreshHz: 144 },
  { name: 'The £1,800 one', budget: 1800, resolution: '1440p', refreshHz: 165 },
  { name: 'The £2,600 one', budget: 2600, resolution: '2160p', refreshHz: 144 },
];

const out = TIERS.map((t) => {
  const r = planBuild({ budget: t.budget, resolution: t.resolution, refreshHz: t.refreshHz, condition: 'new', gameIds: GAMES.filter((g) => data.games.has(g)) }, data, { newP, usedP }, comp);
  const b = r.pick!.build;
  const cpu = data.cpus.get(b.cpuId)!, gpu = data.gpus.get(b.gpuId)!;
  const rows = GAMES.filter((g) => data.games.has(g)).map((g) => {
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
    total: Math.round(r.pick!.total),
    cpu: cpu.fullName, gpu: gpu.fullName,
    cpuShort: cpu.brand, gpuShort: gpu.brand,
    vram: gpu.vramGB, cores: cpu.cores, threads: cpu.threads,
    ram: `${b.ram.totalGB}GB ${b.ram.type}`, storage: b.storage,
    powerW: rep ? Math.round(rep.power.totalW) : null,
    psuW: rep?.power.recommendedPsuW ?? null,
    cyberpunk1440: at1440.status === 'ok' ? Math.round(at1440.avgFps!) : null,
    bom: r.pick!.bom.map((l: any) => ({ cat: l.category, price: Math.round(l.price), part: l.label ?? '' })),
    rows,
  };
});
writeFileSync('marketing/builds.json', JSON.stringify(out, null, 2));
console.log(JSON.stringify(out.map(o => ({ n: o.name, total: o.total, cpu: o.cpuShort, gpu: o.gpuShort, w: o.powerW, psu: o.psuW })), null, 1));
