/**
 * npm run marketing:versus -- <part-a> <part-b> [--resolution 1440p] [--games a,b,c]
 * Renders a versus card in both formats and prints a caption.
 */
import { mkdirSync, readFileSync } from 'node:fs';
import { buildEngineData } from '../../src/core/catalogue.ts';
import { versusData } from './lib/versus.ts';
import { versusCaption } from './lib/captions.ts';
import { renderCards, versusCard, unesc } from './cardlib.mjs';
import type { Resolution } from '../../src/core/types.ts';

const args = process.argv.slice(2);
const flags: Record<string, string> = {}; const pos: string[] = [];
for (let i = 0; i < args.length; i++) { if (args[i].startsWith('--')) { flags[args[i].slice(2)] = args[i + 1] ?? ''; i++; } else pos.push(args[i]); }
if (pos.length !== 2) { console.error('usage: npm run marketing:versus -- <part-a> <part-b> [--resolution 1440p] [--games id,id]'); process.exit(1); }
const load = (p: string) => JSON.parse(readFileSync(p, 'utf8'));
const data = buildEngineData({ gpus: load('data/catalogue/gpus.json'), cpus: load('data/catalogue/cpus.json'), games: load('data/catalogue/games.json'), references: load('data/catalogue/references.json') });
const v = versusData(pos[0], pos[1], data, { resolution: flags.resolution as Resolution | undefined, games: flags.games?.split(',') });
const name = `versus-${v.a.id}-vs-${v.b.id}`.replace(/(nvidia|amd|intel)-/g, '');
mkdirSync('marketing/images/story', { recursive: true });
await renderCards([{ name, ...versusCard(v) }], { dir: 'marketing/images', format: 'post' });
await renderCards([{ name, ...versusCard(v, { format: 'story' }) }], { dir: 'marketing/images/story', format: 'story' });
console.log(`marketing/images/${name}.png\nmarketing/images/story/${name}.png\n`);
console.log(`shows: ${unesc(versusCard(v).subject)}\n`);
console.log(versusCaption(v));
