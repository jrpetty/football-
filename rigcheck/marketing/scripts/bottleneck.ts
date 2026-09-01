/**
 * The bottleneck series.
 *
 * The single most useful thing this engine can say, and the thing build guides
 * get most wrong: whether the processor matters depends entirely on the game.
 * Hold the graphics card fixed, swap the processor underneath it, and the same
 * four parts move Baldur's Gate 3 by two thirds and Cyberpunk by barely a
 * tenth. "Is my CPU bottlenecking me?" has no answer without naming the game,
 * and almost nobody says so.
 *
 * Every figure comes from the estimator. Writes bottleneck.json for the card
 * renderer and the post copy.
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { buildEngineData } from '../../src/core/catalogue.ts';
import { estimate } from '../../src/core/engine.ts';
import type { Build, Resolution } from '../../src/core/types.ts';

const load = (p: string) => JSON.parse(readFileSync(p, 'utf8'));
const data = buildEngineData({
  gpus: load('data/catalogue/gpus.json'), cpus: load('data/catalogue/cpus.json'),
  games: load('data/catalogue/games.json'), references: load('data/catalogue/references.json'),
});

const CPUS = ['amd-ryzen-5-3600', 'amd-ryzen-5-5600', 'amd-ryzen-7-5800x3d', 'amd-ryzen-7-7800x3d'];
const GAMES = ['baldurs-gate-3', 'total-war-warhammer-iii', 'factorio', 'cyberpunk-2077', 'counter-strike-2', 'fortnite'];

const mk = (cpuId: string, gpuId: string): Build => ({
  id: 'x', cpuId, gpuId,
  ram: { totalGB: 32, channels: 2, speedMTs: 3600, type: 'DDR4' },
  storage: 'nvme-gen4', target: { resolution: '1440p', refreshHz: 144 },
});

function series(gpuId: string, resolution: Resolution) {
  const gpu = data.gpus.get(gpuId)!;
  const games = GAMES.filter((g) => data.games.has(g)).map((gameId) => {
    const points = CPUS.filter((c) => data.cpus.has(c)).map((cpuId) => {
      const e = estimate(mk(cpuId, gpuId), gameId, resolution, data);
      return {
        cpu: data.cpus.get(cpuId)!.brand,
        fps: e.status === 'ok' ? Math.round(e.avgFps!) : null,
        limiter: e.limiter,
      };
    });
    const ok = points.filter((p) => p.fps) as { cpu: string; fps: number; limiter: string }[];
    const lo = Math.min(...ok.map((p) => p.fps));
    const hi = Math.max(...ok.map((p) => p.fps));
    return {
      game: data.games.get(gameId)!.name,
      points,
      // What the processor is worth here: the spread across four of them, on
      // one unchanged graphics card.
      gainPct: lo > 0 ? Math.round((hi / lo - 1) * 100) : 0,
      cpuBound: ok.filter((p) => p.limiter === 'cpu').length,
    };
  });
  games.sort((a, b) => b.gainPct - a.gainPct);
  return { gpu: gpu.fullName, gpuShort: gpu.brand, resolution, cpus: CPUS.map((c) => data.cpus.get(c)?.brand ?? c), games };
}

const out = [series('nvidia-geforce-rtx-4070', '1440p'), series('nvidia-geforce-rtx-4090', '1440p')];
writeFileSync('marketing/bottleneck.json', JSON.stringify(out, null, 2));

for (const s of out) {
  console.log(`\n=== ${s.gpuShort} at ${s.resolution} — what four processors are worth ===`);
  for (const g of s.games) {
    console.log(`  ${g.game.padEnd(24)} +${String(g.gainPct).padStart(3)}%   ${g.points.map((p) => `${p.fps ?? '—'}`.padStart(4)).join(' ')}   ${g.cpuBound}/4 cpu-bound`);
  }
}
