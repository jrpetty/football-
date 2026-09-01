/**
 * Data for the recurring content pillars.
 *
 * Each block below is a post format that can be run again with different parts
 * rather than a one-off. Everything comes from the estimator; nothing is typed.
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

const base = (cpuId: string, gpuId: string, res: Resolution): Build => ({
  id: 'x', cpuId, gpuId,
  ram: { totalGB: 32, channels: 2, speedMTs: 3600, type: 'DDR4' },
  storage: 'nvme-gen4', target: { resolution: res, refreshHz: 144 },
});

const fps = (b: Build, game: string, res: Resolution) => {
  const e = estimate(b, game, res, data);
  return {
    fps: e.status === 'ok' ? Math.round(e.avgFps!) : null,
    status: e.status,
    limiter: e.limiter,
  };
};

const GAMES = ['baldurs-gate-3', 'counter-strike-2', 'cyberpunk-2077', 'fortnite', 'total-war-warhammer-iii'];
const name = (id: string) => data.games.get(id)?.name ?? id;

/* -- 1. The silent tax: single-channel memory --------------------------- */

const cpuId = 'amd-ryzen-7-5800x3d';
const gpuId = 'nvidia-geforce-rtx-4070';
const silentTax = {
  cpu: data.cpus.get(cpuId)!.brand,
  gpu: data.gpus.get(gpuId)!.brand,
  resolution: '1080p' as Resolution,
  rows: GAMES.filter((g) => data.games.has(g)).map((g) => {
    const one = fps({ ...base(cpuId, gpuId, '1080p'), ram: { totalGB: 32, channels: 1, speedMTs: 3600, type: 'DDR4' } }, g, '1080p');
    const two = fps(base(cpuId, gpuId, '1080p'), g, '1080p');
    return {
      game: name(g),
      before: one.fps, after: two.fps,
      gainPct: one.fps && two.fps ? Math.round((two.fps / one.fps - 1) * 100) : 0,
      limiter: two.limiter,
    };
  }).sort((a, b) => b.gainPct - a.gainPct),
};

/* -- 2. The VRAM myth: same chip, twice the memory ---------------------- */

const vram = ['nvidia-geforce-rtx-4060-ti-8gb', 'nvidia-geforce-rtx-4060-ti-16gb'].every((g) => data.gpus.has(g))
  ? {
      a: data.gpus.get('nvidia-geforce-rtx-4060-ti-8gb')!,
      b: data.gpus.get('nvidia-geforce-rtx-4060-ti-16gb')!,
      rows: (['1080p', '1440p', '2160p'] as Resolution[]).map((res) => {
        const x = fps(base(cpuId, 'nvidia-geforce-rtx-4060-ti-8gb', res), 'cyberpunk-2077', res);
        const y = fps(base(cpuId, 'nvidia-geforce-rtx-4060-ti-16gb', res), 'cyberpunk-2077', res);
        return {
          resolution: res,
          a: x.fps, b: y.fps,
          gainPct: x.fps && y.fps ? Math.round((y.fps / x.fps - 1) * 100) : 0,
          vramWall: x.limiter === 'vram',
        };
      }),
    }
  : null;

/* -- 3. Is it still good? old cards, current games ---------------------- */

const OLD = ['nvidia-geforce-gtx-970', 'nvidia-geforce-gtx-1060-6gb', 'amd-radeon-rx-580-8gb', 'nvidia-geforce-gtx-1080-ti'];
const stillGood = {
  resolution: '1080p' as Resolution,
  cpu: data.cpus.get('amd-ryzen-5-5600')!.brand,
  // The published minimum the 970 sits under. On the card, not in anyone's head.
  minVramGame: name('cyberpunk-2077'),
  minVramGB: data.games.get('cyberpunk-2077')?.requirements.minVramGB ?? null,
  cards: OLD.filter((g) => data.gpus.has(g)).map((g) => {
    const rec = data.gpus.get(g)!;
    const b = base('amd-ryzen-5-5600', g, '1080p');
    return {
      gpu: rec.brand,
      year: rec.launchDate?.slice(0, 4) ?? '',
      vram: rec.vramGB,
      // Every memory size this card was sold in, from the catalogue, so the
      // card can say "the 1060 shipped as 3GB and 6GB" without anyone typing
      // it. Same brand string, different variant record.
      siblings: [...new Set([...data.gpus.values()]
        .filter((o) => o.brand === rec.brand && o.vramGB != null)
        .map((o) => o.vramGB as number))].sort((a, b) => a - b),
      rows: ['counter-strike-2', 'fortnite', 'cyberpunk-2077'].filter((x) => data.games.has(x)).map((x) => {
        const r = fps(b, x, '1080p');
        return { game: name(x), fps: r.fps, status: r.status };
      }),
    };
  }),
};

/* -- 4. What resolution costs --------------------------------------------- */

const resolutionCost = ['nvidia-geforce-rtx-4070', 'nvidia-geforce-rtx-4090']
  .filter((g) => data.gpus.has(g))
  .map((g) => ({
    gpu: data.gpus.get(g)!.brand,
    rows: (['1080p', '1440p', '2160p'] as Resolution[]).map((res) => ({
      resolution: res,
      fps: fps(base('amd-ryzen-7-7800x3d', g, res), 'cyberpunk-2077', res).fps,
    })),
  }));

writeFileSync('marketing/pillars.json', JSON.stringify({ silentTax, vram, stillGood, resolutionCost }, null, 2));

console.log('SILENT TAX —', silentTax.gpu, '+', silentTax.cpu, 'at', silentTax.resolution);
for (const r of silentTax.rows) console.log(`  ${r.game.padEnd(24)} ${r.before} → ${r.after}  +${r.gainPct}%`);
if (vram) {
  console.log('\nVRAM —', vram.a.brand, '8GB vs 16GB, Cyberpunk');
  for (const r of vram.rows) console.log(`  ${r.resolution.padEnd(7)} ${r.a} vs ${r.b}   +${r.gainPct}%${r.vramWall ? '  (8GB hits a VRAM wall)' : ''}`);
}
console.log('\nSTILL GOOD?');
for (const c of stillGood.cards) console.log(`  ${c.gpu.padEnd(22)} ${c.year}  ${c.rows.map((r) => `${r.game.split(' ')[0]} ${r.fps ?? r.status}`).join('  ')}`);
