/**
 * npm run marketing:rig -- --cpu "i7 7700" --gpu "1080 ti" [--resolution 1080p]
 *
 * The check-my-rig post: someone comments a spec, this turns it into a card
 * and a caption. Deliberately the same two flags a comment gives you and
 * nothing else, because the format lives or dies on how fast one can be made.
 */
import { mkdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { buildEngineData } from '../../src/core/catalogue.ts';
import { resolveOne } from '../../src/core/resolve.ts';
import { rigData } from './lib/rig.ts';
import { rigCaption } from './lib/captions.ts';
import { renderCards, rigCard, unesc } from './cardlib.mjs';
import type { CpuRecord, GpuRecord, RamConfig, Resolution } from '../../src/core/types.ts';

const ROOT = new URL('../..', import.meta.url).pathname;
const load = (p: string) => JSON.parse(readFileSync(join(ROOT, p), 'utf8'));

const argv = process.argv.slice(2);
const flags: Record<string, string> = {};
for (let i = 0; i < argv.length; i++) {
  if (!argv[i].startsWith('--')) { console.error(`unexpected argument "${argv[i]}"`); process.exit(1); }
  flags[argv[i].slice(2)] = argv[++i] ?? '';
}
if (!flags.cpu || !flags.gpu) {
  console.error('usage: npm run marketing:rig -- --cpu <name> --gpu <name> [--resolution 1080p] [--ram 16] [--ram-type DDR4]');
  process.exit(1);
}

const data = buildEngineData({
  gpus: load('data/catalogue/gpus.json'), cpus: load('data/catalogue/cpus.json'),
  games: load('data/catalogue/games.json'), references: load('data/catalogue/references.json'),
});
const pick = (q: string, recs: { id: string; fullName: string }[], kind: string) => {
  const r = resolveOne(q, new Map(recs.map((x) => [x.id, x.fullName])));
  if (r.ok) return r.id;
  if (r.reason === 'none') { console.error(`no ${kind} matches "${q}"`); process.exit(1); }
  console.error(`\n"${q}" matches several ${kind}s. Be more specific:\n`);
  for (const c of r.candidates) console.error(`  ${c.label}`);
  process.exit(1);
};
const cpuId = pick(flags.cpu, (load('data/catalogue/cpus.json').records as CpuRecord[]), 'processor');
const gpuId = pick(flags.gpu, (load('data/catalogue/gpus.json').records as GpuRecord[]), 'graphics card');

const ramType = (flags['ram-type'] ?? 'DDR4') as RamConfig['type'];
const ram: RamConfig = {
  totalGB: Number(flags.ram ?? 16), channels: 2,
  speedMTs: Number(flags['ram-speed'] ?? (ramType === 'DDR5' ? 6000 : 2400)), type: ramType,
};
const r = rigData(cpuId, gpuId, data, { resolution: (flags.resolution ?? '1080p') as Resolution, ram });

const name = `rig-${cpuId}-${gpuId}`.replace(/(nvidia|amd|intel)-/g, '');
mkdirSync(join(ROOT, 'marketing/images/story'), { recursive: true });
await renderCards([{ name, ...rigCard(r) }], { dir: 'marketing/images', format: 'post' });
await renderCards([{ name, ...rigCard(r, { format: 'story' }) }], { dir: 'marketing/images/story', format: 'story' });
console.log(`marketing/images/${name}.png\nmarketing/images/story/${name}.png\n`);
console.log(`shows: ${unesc(rigCard(r).subject)}\n`);
console.log(rigCaption(r));
